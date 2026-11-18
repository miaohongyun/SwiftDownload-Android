package com.swift.download

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * v1.0 行为级移植的多线程 HTTP 下载引擎：
 *  - Range 探测（Content-Length / Accept-Ranges / Content-Disposition 文件名识别）
 *  - 多线程分段下载（每线程 64KB 缓冲，独立分段计数器定位 .part 偏移）
 *  - .swiftprog 进度文件断点续传（与 v1.0 同名同义）
 *  - SpeedSampler 速度采样（服务轮询调用）
 *
 * v2.5 修复（不改 v1.0 可观察语义）：
 *  1. 探测（HEAD/GET）改在后台线程执行 —— 调用方多为主线程，
 *     targetSdk 28+ 在主线程做网络访问会抛 NetworkOnMainThreadException。
 *  2. 分段计数器先全部登记再启动线程，避免"首段先完成"被误判为"全部完成"。
 *  3. 任一分段失败即终止其余分段，且不再触发 onFinished
 *     （此前会出现任务状态"先失败、后完成"并合并不完整文件）。
 *  4. 文件名优先采用服务器 Content-Disposition（v1.0 行为），并同步更新落盘路径。
 *  5. 新任务先清空同名的残留 .part，避免上一次任务的旧数据混入新文件。
 */
class DownloadEngine private constructor() {

    interface EngineListener {
        fun onProgress(task: DownloadTask, downloadedBytes: Long, speedBps: Long)
        fun onStatus(task: DownloadTask)
        fun onFinished(task: DownloadTask)
        fun onError(task: DownloadTask, error: String)
    }

    data class ProbeResult(
        val totalBytes: Long,          // -1 表示未知（v1.0 语义）
        val acceptRanges: Boolean,
        val fileName: String?
    )

    /** 分段切割计算（纯函数，单元测试覆盖） */
    object Segments {
        /**
         * 将 [0, total) 按线程数等分；total<=0 或 threads<=1 返回 null（单线程直下）。
         * 返回每个线程的 [start, end] HTTP Range 闭区间。
         */
        fun split(total: Long, threads: Int): List<LongArray>? {
            if (total <= 0 || threads <= 1) return null
            val n = threads.toLong()
            if (n > total) return split(total, total.toInt())
            val base = total / n
            val rem = total % n
            val out = ArrayList<LongArray>(threads)
            var start = 0L
            for (i in 0 until threads) {
                val len = base + if (i < rem) 1 else 0
                out.add(longArrayOf(start, start + len - 1)) // HTTP Range 闭区间
                start += len
            }
            return out
        }

        fun progFileName(taskId: Long): String = "$taskId.swiftprog"
    }

    /** 速度采样器：≥1 秒窗口差分 */
    class SpeedSampler {
        private var lastBytes = 0L
        private var lastTime = 0L
        private var last = 0L

        fun sample(bytes: Long, now: Long): Long {
            if (lastTime == 0L) {
                lastTime = now; lastBytes = bytes
                return 0
            }
            val dt = (now - lastTime) / 1000
            if (dt < 1) return last
            val v = (bytes - lastBytes) / dt
            lastTime = now; lastBytes = bytes
            last = if (v < 0) 0 else v
            return last
        }
    }

    /**
     * 单个下载任务执行体：每分段独立计数器，写盘位置 = 分段起点 + 分段已写字节。
     */
    inner class DownloadJob(
        val task: DownloadTask,
        private val listener: EngineListener,
        private val throttle: (Int) -> Unit = {}
    ) {
        private val cancelled = AtomicBoolean(false)
        private val failed = AtomicBoolean(false)
        /** 探测完成信号：供"边下边播"等待任务就绪 */
        private val ready = CountDownLatch(1)
        private val segCounters = ConcurrentHashMap<Long, AtomicLong>()
        private val atomicBytes = AtomicLong(0)
        @Volatile private var pool: ExecutorService? = null
        @Volatile var working = DownloadTask.Status.PROBING
            private set

        fun start() {
            working = DownloadTask.Status.PROBING
            listener.onStatus(task.copy(status = working))
            // 探测是网络操作：必须在后台线程（调用方可能是主线程）
            starterPool.execute { prepare() }
        }

        /** 等待探测结束（成功或失败）；超时返回 false */
        fun awaitReady(timeoutMs: Long): Boolean = try {
            ready.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        /** 探测 + 分段准备（后台线程） */
        private fun prepare() {
            try {
                val probe = probe(task.url, task.referer)
                if (cancelled.get()) return
                if (probe.totalBytes <= 0L) {
                    working = DownloadTask.Status.FAILED
                    listener.onError(
                        task.copy(status = working),
                        "无法获取文件大小（服务器未返回 Content-Length）"
                    )
                    return
                }
                // 断点数据是否可用：进度文件存在，且服务器总大小与上次一致
                val progFile = File(task.dirFile(), Segments.progFileName(task.id))
                val hasResume = progFile.isFile &&
                    task.totalBytes > 0L && task.totalBytes == probe.totalBytes

                // 文件名：优先服务器 Content-Disposition（v1.0 行为）；
                // 仅在全新任务（无进度、无断点文件）时替换，否则与已有 .part 对不上
                val serverName = probe.fileName?.let { sanitizeFileName(it) }.orEmpty()
                val canRename = task.downloadedBytes <= 0L && !hasResume &&
                    serverName.isNotBlank()
                val finalName = if (canRename) serverName else task.fileName
                val savePath = if (finalName != task.fileName) {
                    File(File(task.savePath).parentFile ?: File("."), finalName).absolutePath
                } else task.savePath
                val t = task.copy(fileName = finalName, totalBytes = probe.totalBytes, savePath = savePath)

                if (!hasResume) {
                    // 新任务：清掉同名残留半成品，避免旧数据混入新文件
                    clearStaleParts(task.savePath, t.savePath)
                }
                working = DownloadTask.Status.RUNNING
                listener.onStatus(t.copy(status = working))

                val segs = if (probe.acceptRanges) Segments.split(probe.totalBytes, t.threads) else null
                val count = if (segs == null) 1 else segs.size
                pool = Executors.newFixedThreadPool(count)
                // 先登记全部分段计数，再启动线程：
                // 否则先跑完的分段会看到不完整的 segCounters.size，被误判为"全部完成"
                val counters = ArrayList<AtomicLong>(count)
                if (segs == null) {
                    // 服务器不支持 Range：单线程直下
                    val c = AtomicLong(0L)
                    segCounters[0L] = c
                    counters.add(c)
                } else {
                    for (s in segs) {
                        val c = AtomicLong(if (hasResume) resumeBytes(t, s[0]) else 0L)
                        segCounters[s[0]] = c
                        atomicBytes.addAndGet(c.get())
                        counters.add(c)
                    }
                }
                if (segs == null) {
                    pool?.execute { segment(t, 0L, -1L, counters[0]) }
                } else {
                    for (i in segs.indices) {
                        val s = segs[i]
                        pool?.execute { segment(t, s[0], s[1], counters[i]) }
                    }
                }
            } catch (e: Exception) {
                if (!cancelled.get()) {
                    failed.set(true)
                    working = DownloadTask.Status.FAILED
                    listener.onError(
                        task.copy(status = working), e.message ?: "未知错误"
                    )
                }
            } finally {
                ready.countDown()
            }
        }

        fun totalBytes(): Long = atomicBytes.get()

        /** 各分段已下载字节快照（服务轮询回写 .swiftprog / DB 用） */
        fun snapshot(): Map<Long, Long> = segCounters.mapValues { it.value.get() }

        /**
         * v2.2 边下边播回写：外部（本地流服务器）在 offset 处写入新拉到的字节。
         *  - 写入 .part 文件（保证盘上数据就位）
         *  - 仅当 offset 正好处在该分段的下载前沿时计入进度（计数器/速度/DB 同步推进）；
         *    处于已有数据区（重复拉取）或洞区（越前写入）时只落盘不计数。
         * 返回是否计入了进度。引擎自身线程已停止（任务进入流模式）时调用安全。
         */
        fun externalWrite(t: DownloadTask, offset: Long, buf: ByteArray, len: Int): Boolean {
            if (len <= 0 || offset < 0) return false
            // 先落盘（即使无分段计数，也保证数据就位供流服务器读取）
            val f = File(t.savePath + ".part")
            f.parentFile?.mkdirs()
            RandomAccessFile(f, "rw").use { raf ->
                raf.seek(offset)
                raf.write(buf, 0, len)
            }
            val starts = segCounters.keys.sorted()
            if (starts.isEmpty()) return false
            // 找包含 offset 的分段
            var segStart = -1L
            for ((i, s) in starts.withIndex()) {
                val next = if (i + 1 < starts.size) starts[i + 1] else Long.MAX_VALUE
                if (offset < next) { segStart = s; break }
            }
            if (segStart < 0) return false
            val counter = segCounters[segStart] ?: return false
            val counted = offset == segStart + counter.get()
            if (counted) {
                counter.addAndGet(len.toLong())
                atomicBytes.addAndGet(len.toLong())
            }
            return counted
        }

        fun cancel() {
            cancelled.set(true)
            pool?.shutdownNow()
        }

        private fun segment(t: DownloadTask, start: Long, end: Long, counter: AtomicLong) {
            var conn: HttpURLConnection? = null
            var raf: RandomAccessFile? = null
            try {
                conn = open(t.url, t.referer)
                val resumeFrom = counter.get()
                if (end >= 0) {
                    conn.setRequestProperty("Range", "bytes=${start + resumeFrom}-$end")
                } else if (resumeFrom > 0) {
                    conn.setRequestProperty("Range", "bytes=${resumeFrom}-")
                }
                val code = conn.responseCode
                if (code != 200 && code != 206) throw IOException("HTTP $code")
                // 每个分段持有自己的 RandomAccessFile：避免每 64KB 开关一次文件
                val f = File(t.savePath + ".part")
                f.parentFile?.mkdirs()
                raf = RandomAccessFile(f, "rw")
                conn.inputStream.use { input ->
                    val buf = ByteArray(65536) // v1.0：64KB 缓冲
                    while (!cancelled.get()) {
                        val read = input.read(buf)
                        if (read < 0) break
                        writeAt(t, start, counter.get(), buf, read, raf)
                        counter.addAndGet(read.toLong())
                        atomicBytes.addAndGet(read.toLong())
                        throttle(read) // 限速：在字节落盘并计数后阻塞，速度采样自然反映实际速率
                    }
                }
                if (!cancelled.get()) segDone(t)
            } catch (e: Exception) {
                if (!cancelled.get()) {
                    Log.w(TAG, "segment error", e)
                    // 任一段失败 → 标记失败并终止其余分段。
                    // 否则其余分段跑完会触发 onFinished，把失败任务翻成"已完成"并合并不完整文件
                    failed.set(true)
                    cancelled.set(true)
                    pool?.shutdownNow()
                    working = DownloadTask.Status.FAILED
                    listener.onError(t.copy(status = working), e.message ?: "未知错误")
                }
            } finally {
                try { raf?.close() } catch (e: Exception) {}
                conn?.disconnect()
            }
        }

        /** 分段完成计数；全部完成时触发 onFinished 并合并 .part → 正式文件 */
        private val doneSegs = AtomicLong(0)
        private fun segDone(t: DownloadTask) {
            if (failed.get()) return
            val done = doneSegs.addAndGet(1)
            val segCount = segCounters.size
            if (done >= segCount) {
                if (failed.get()) return
                mergeParts(t)
                working = DownloadTask.Status.COMPLETED
                listener.onFinished(t.copy(
                    status = working,
                    downloadedBytes = atomicBytes.get()
                ))
            }
        }

        /** .part → 正式文件：各线程按偏移写入同一 .part，直接就位（改名或复制） */
        private fun mergeParts(t: DownloadTask) {
            val part = File(t.savePath + ".part")
            val out = File(t.savePath)
            out.parentFile?.mkdirs()
            if (part.exists()) {
                if (out.exists()) out.delete()
                if (!part.renameTo(out)) {
                    try {
                        part.copyTo(out, overwrite = true)
                        part.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "mergeParts", e)
                    }
                }
            }
            File(t.dirFile(), Segments.progFileName(t.id)).delete()
        }

        private fun writeAt(
            t: DownloadTask, segStart: Long, segOffset: Long,
            buf: ByteArray, len: Int, raf: RandomAccessFile
        ) {
            raf.seek(segStart + segOffset)
            raf.write(buf, 0, len)
        }

        /** 断点续传：从 .swiftprog 读某分段已下载字节 */
        private fun resumeBytes(t: DownloadTask, segStart: Long): Long {
            val prog = File(t.dirFile(), Segments.progFileName(t.id))
            if (!prog.exists()) return 0L
            return try {
                for (line in prog.readLines()) {
                    val p = line.trim().split(" ")
                    if (p.size == 2) {
                        val start = p[0].toLongOrNull() ?: continue
                        val bytes = p[1].toLongOrNull() ?: continue
                        if (start == segStart) return bytes
                    }
                }
                0L
            } catch (e: Exception) { 0L }
        }
    }

    fun newJob(
        task: DownloadTask,
        listener: EngineListener,
        throttle: (Int) -> Unit = {}
    ): DownloadJob = DownloadJob(task, listener, throttle)

    /** Range 探测：HEAD 失败自动退化 GET+Range（v1.0 行为） */
    fun probe(url: String, referer: String = ""): ProbeResult {
        var conn = open(url, referer)
        conn.requestMethod = "HEAD"
        var code = try { conn.responseCode } catch (e: Exception) { -1 }
        if (code != 200) {
            conn.disconnect()
            conn = open(url, referer)
            conn.setRequestProperty("Range", "bytes=0-0")
            code = try { conn.responseCode } catch (e: Exception) { -1 }
        }
        val len = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        val cr = conn.getHeaderField("Content-Range")
        val ranges = conn.getHeaderField("Accept-Ranges")?.equals("bytes", true) == true ||
            (cr != null && cr.startsWith("bytes"))
        val total = if (cr != null && cr.contains("/")) {
            cr.substringAfter("/").toLongOrNull() ?: len
        } else len
        val name = Format.fileNameFromDisposition(conn.getHeaderField("Content-Disposition"))
            ?: nameFromUrl(conn.url.toString())
        conn.disconnect()
        return ProbeResult(total, ranges, name)
    }

    private fun open(url: String, referer: String = ""): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
        )
        // v2.5：防盗链站点（Referer 校验）必须带上来源页
        if (referer.isNotBlank()) c.setRequestProperty("Referer", referer)
        return c
    }

    companion object {
        const val TAG = "DownloadEngine"

        /** 探测/准备阶段的共享后台池（探测含网络，不能在主线程执行） */
        private val starterPool: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "dl-probe").apply { isDaemon = true }
        }

        /** 去掉文件名中的路径分隔符与非法字符，避免写到意外位置 */
        fun sanitizeFileName(raw: String): String {
            var s = raw.trim().replace('/', '_').replace('\\', '_')
            s = s.replace(Regex("[\\x00-\\x1f]"), "")
            s = s.replace(":", "_").trim()
            if (s.isBlank() || s == "." || s == "..") return ""
            return s.take(120)
        }

    /** 清理同名残留半成品（改名或重新下载时） */
    private fun clearStaleParts(oldPath: String, newPath: String) {
        val targets = ArrayList<File>()
        targets.add(File(newPath + ".part"))
        if (oldPath != newPath) targets.add(File(oldPath + ".part"))
        for (f in targets) {
            try {
                if (f.isFile && f.length() > 0) {
                    RandomAccessFile(f, "rw").use { it.setLength(0) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "clear stale part", e)
            }
        }
    }

        @Volatile private var inst: DownloadEngine? = null
        fun get(): DownloadEngine =
            inst ?: synchronized(this) { inst ?: DownloadEngine().also { inst = it } }
    }
}

/* ---------- 引擎与 UI 共用的纯函数 ---------- */

fun DownloadTask.dirFile(): File = File(savePath).parentFile ?: File(".")
fun DownloadTask.partFile(): File = File(savePath + ".part")
fun fallbackName(url: String): String = nameFromUrl(url)
fun nameFromUrl(url: String): String {
    val raw = url.substringBefore('?').substringBefore('#')
    val last = raw.substringAfterLast('/')
    val dec = try { java.net.URLDecoder.decode(last, "UTF-8") } catch (e: Exception) { last }
    return dec.ifBlank { "download" }
}
