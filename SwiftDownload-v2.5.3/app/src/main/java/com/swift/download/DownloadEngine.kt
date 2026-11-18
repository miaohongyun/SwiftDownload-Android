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
        /** 分段起点 → Range 闭区间终点（-1 表示直到文件尾），用于计数截断与完整性校验 */
        private val segEnds = ConcurrentHashMap<Long, Long>()
        private val atomicBytes = AtomicLong(0)
        /** 探测到的文件总长度，-1 表示未知 */
        @Volatile private var expectedTotal = -1L
        /** 只允许一次"校验 + 合并 + 完成"结算 */
        private val finishing = AtomicBoolean(false)
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
                // 断点数据是否可用：进度文件存在、服务器总大小与上次一致，且分段布局仍然匹配
                val progFile = File(task.dirFile(), Segments.progFileName(task.id))
                val segs = if (probe.acceptRanges) Segments.split(probe.totalBytes, task.threads) else null
                fun expectLen(start: Long): Long {
                    if (segs == null) return probe.totalBytes
                    val idx = segs.indexOfFirst { it[0] == start }
                    return if (idx < 0) -1L else segs[idx][1] - start + 1
                }
                val progEntries = readProg(progFile)
                var hasResume = progFile.isFile && task.totalBytes > 0L &&
                    task.totalBytes == probe.totalBytes && progEntries.isNotEmpty()
                if (hasResume) {
                    // 线程数/分段数变化，或进度值超出分段长度 → 旧进度不可信，全部重下
                    hasResume = progEntries.all { (s, v) ->
                        s in (segs?.map { it[0] } ?: listOf(0L)) && v >= 0L && v <= expectLen(s)
                    }
                }
                if (hasResume) {
                    // 进度文件存在但 .part 已被删/被截断时，从记录的偏移继续写会留下"空洞"：
                    // 文件长度够、内容却是 0 字节。要求 .part 至少覆盖所有已记录的偏移。
                    val partLen = File(task.savePath + ".part").length()
                    val maxResumed = progEntries.maxOfOrNull { (s, v) -> s + v } ?: 0L
                    if (partLen < maxResumed) {
                        Log.w(TAG, "断点文件与进度不匹配（part=$partLen < $maxResumed），改为重新下载")
                        hasResume = false
                    }
                }

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
                expectedTotal = probe.totalBytes
                working = DownloadTask.Status.RUNNING
                listener.onStatus(t.copy(status = working))

                val count = if (segs == null) 1 else segs.size
                pool = Executors.newFixedThreadPool(count)
                // 先登记全部分段计数，再启动线程：
                // 否则先跑完的分段会看到不完整的 segCounters.size，被误判为"全部完成"
                val counters = ArrayList<AtomicLong>(count)
                if (segs == null) {
                    // 服务器不支持 Range：单线程直下
                    val resume = if (hasResume) progEntries[0L] ?: 0L else 0L
                    val c = AtomicLong(resume)
                    segEnds[0L] = -1L
                    segCounters[0L] = c
                    atomicBytes.addAndGet(resume)
                    counters.add(c)
                } else {
                    for (s in segs) {
                        val resume = if (hasResume) progEntries[s[0]] ?: 0L else 0L
                        val c = AtomicLong(resume)
                        segEnds[s[0]] = s[1]
                        segCounters[s[0]] = c
                        atomicBytes.addAndGet(resume)
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
            if (offset != segStart + counter.get()) return false
            // v2.5.2：写入可能跨过分段边界，计数必须按本段剩余空间截断，
            // 否则一次跨段写入会把下一段的字节也记到本段，提前满足"全部完成"
            val segEnd = segEnds[segStart] ?: return false
            val segLen = if (segEnd >= 0) segEnd - segStart + 1 else expectedTotal - segStart
            if (segLen <= 0L) return false
            val room = segLen - counter.get()
            if (room <= 0L) return false
            val add = minOf(len.toLong(), room)
            counter.addAndGet(add)
            atomicBytes.addAndGet(add)
            return true
        }

        fun cancel() {
            cancelled.set(true)
            pool?.shutdownNow()
        }

        private fun segment(t: DownloadTask, start: Long, end: Long, counter: AtomicLong) {
            var conn: HttpURLConnection? = null
            var raf: RandomAccessFile? = null
            try {
                // 分段长度：-1 表示"直到文件尾"（单线程直下）
                val segLen = if (end >= 0) end - start + 1 else -1L
                var resumeFrom = counter.get()
                if (segLen > 0 && resumeFrom > segLen) {
                    // 进度文件不可信：该段从头重下
                    resumeFrom = 0L
                    counter.set(0L)
                }
                if (segLen > 0 && resumeFrom == segLen) {
                    // 该段上次已下完（.part 中数据已在），无需再发请求
                    segDone(t)
                    return
                }
                conn = open(t.url, t.referer)
                val wantRange = end >= 0 || resumeFrom > 0L
                if (end >= 0) {
                    conn.setRequestProperty("Range", "bytes=${start + resumeFrom}-$end")
                } else if (resumeFrom > 0) {
                    conn.setRequestProperty("Range", "bytes=$resumeFrom-")
                }
                val code = conn.responseCode
                if (code != 200 && code != 206) throw IOException("HTTP $code")

                if (wantRange && code != 206) {
                    if (end >= 0) {
                        // 服务器忽略 Range 却返回整个文件：继续写只会得到错位文件
                        throw IOException("服务器未按 Range 返回分段（HTTP 200），已停止以避免文件损坏")
                    }
                    // 单线程续传：丢弃本地断点，从 0 重下
                    val dropped = resumeFrom
                    resumeFrom = 0L
                    counter.set(0L)
                    atomicBytes.addAndGet(-dropped)
                } else if (code == 206) {
                    // 校验服务器真的从请求的偏移开始返回，且文件总大小未变
                    val cr = conn.getHeaderField("Content-Range")
                        ?: throw IOException("HTTP 206 缺少 Content-Range，无法校验分段偏移")
                    val m = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)").find(cr)
                        ?: throw IOException("无法解析 Content-Range: $cr")
                    val expectStart = if (end >= 0) start + resumeFrom else resumeFrom
                    val actualStart = m.groupValues[1].toLong()
                    if (actualStart != expectStart) {
                        throw IOException("Content-Range 起点不符（期望 $expectStart，实际 $actualStart）")
                    }
                    val total = m.groupValues[3]
                    if (total != "*") {
                        val totalLen = total.toLongOrNull() ?: -1L
                        if (t.totalBytes > 0L && totalLen > 0L && totalLen != t.totalBytes) {
                            throw IOException("文件大小在下载过程中变化（期望 ${t.totalBytes}，实际 $totalLen）")
                        }
                    }
                }

                // 每个分段持有自己的 RandomAccessFile：避免每 64KB 开关一次文件
                val f = File(t.savePath + ".part")
                f.parentFile?.mkdirs()
                val file = RandomAccessFile(f, "rw")
                raf = file
                // v2.5.1：本线程私有写入位置。cancel() 后线程可能阻塞在 read() 里晚一步才退出，
                // 而边下边播的 externalWrite 会推进共享 counter —— 若用 counter.get() 当偏移，
                // 残留线程会把数据写到错误位置。私有位置保证各写者永不错位。
                var myPos = resumeFrom
                // v2.5.2：严格按分段长度收尾。少读一个字节即失败，多读的部分丢弃，
                // 避免短响应被当成"该段完成"后合并出中间有洞的文件
                var remaining = if (segLen > 0) segLen - resumeFrom else -1L
                conn.inputStream.use { input ->
                    val buf = ByteArray(65536) // v1.0：64KB 缓冲
                    while (!cancelled.get()) {
                        if (remaining == 0L) break
                        val want = if (remaining > 0) minOf(buf.size.toLong(), remaining).toInt() else buf.size
                        val read = input.read(buf, 0, want)
                        if (read < 0) break
                        writeAt(start, myPos, buf, read, file)
                        myPos += read
                        // 计数只前进不后退（externalWrite 并发推进时取更大值，两者写的都是真实数据）
                        counter.accumulateAndGet(read.toLong()) { cur, _ -> maxOf(cur, myPos) }
                        atomicBytes.addAndGet(read.toLong())
                        if (remaining > 0) remaining -= read
                        throttle(read) // 限速：在字节落盘并计数后阻塞，速度采样自然反映实际速率
                    }
                }
                if (cancelled.get()) return
                // 先关掉本线程的文件句柄再结算：Windows/部分文件系统上
                // 句柄未关时 rename/delete 会失败，合并后 .part 会残留
                try { file.close() } catch (e: Exception) {}
                raf = null
                if (remaining > 0L) {
                    throw IOException("分段数据不完整：还缺 $remaining 字节（已收 ${myPos - resumeFrom}）")
                }
                segDone(t)
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
            if (done < segCount) return
            if (failed.get() || cancelled.get()) return
            // 结算只做一次：避免多个分段线程同时走到这里重复合并/重复回调
            if (!finishing.compareAndSet(false, true)) return

            val problem = verifyComplete(t)
            if (problem != null) {
                working = DownloadTask.Status.FAILED
                failed.set(true)
                listener.onError(t.copy(status = working), problem)
                return
            }
            if (!mergeParts(t)) {
                working = DownloadTask.Status.FAILED
                failed.set(true)
                listener.onError(t.copy(status = working), "合并文件失败（.part 无法改名或长度不符）")
                return
            }
            working = DownloadTask.Status.COMPLETED
            listener.onFinished(t.copy(
                status = working,
                downloadedBytes = atomicBytes.get()
            ))
        }

        /** 合并前的完整性验收：每段实际字节数 + 最终文件长度都必须与探测结果一致 */
        private fun verifyComplete(t: DownloadTask): String? {
            for ((start, counter) in segCounters) {
                val segEnd = segEnds[start] ?: return "分段 $start 缺少长度信息"
                val expect = if (segEnd >= 0) segEnd - start + 1 else expectedTotal - start
                if (expect > 0L && counter.get() != expect) {
                    return "分段 $start 数据不完整（${counter.get()} / $expect 字节）"
                }
            }
            val part = File(t.savePath + ".part")
            if (!part.isFile) return "临时文件丢失，无法完成合并"
            if (t.totalBytes > 0L && part.length() != t.totalBytes) {
                return "文件长度不符（${part.length()} / ${t.totalBytes} 字节），已停止合并"
            }
            return null
        }

        /** .part → 正式文件：各线程按偏移写入同一 .part，直接就位（改名或复制） */
        private fun mergeParts(t: DownloadTask): Boolean {
            val part = File(t.savePath + ".part")
            val out = File(t.savePath)
            out.parentFile?.mkdirs()
            if (!part.isFile) return false
            if (out.exists() && !out.delete()) {
                Log.w(TAG, "mergeParts: 旧文件无法删除 ${out.absolutePath}")
                return false
            }
            if (!part.renameTo(out)) {
                try {
                    part.copyTo(out, overwrite = true)
                    if (!part.delete()) Log.w(TAG, "mergeParts: .part 残留 ${part.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "mergeParts", e)
                    return false
                }
            }
            if (!out.isFile || (t.totalBytes > 0L && out.length() != t.totalBytes)) return false
            File(t.dirFile(), Segments.progFileName(t.id)).delete()
            return true
        }

        private fun writeAt(
            segStart: Long, segOffset: Long,
            buf: ByteArray, len: Int, raf: RandomAccessFile
        ) {
            raf.seek(segStart + segOffset)
            raf.write(buf, 0, len)
        }

        /** 读取 .swiftprog：分段起点 → 已下载字节 */
        private fun readProg(prog: File): Map<Long, Long> {
            if (!prog.isFile) return emptyMap()
            return try {
                val out = LinkedHashMap<Long, Long>()
                for (line in prog.readLines()) {
                    val p = line.trim().split(" ")
                    if (p.size == 2) {
                        val start = p[0].toLongOrNull() ?: continue
                        val bytes = p[1].toLongOrNull() ?: continue
                        out[start] = bytes
                    }
                }
                out
            } catch (e: Exception) { emptyMap() }
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
        val headOk = try { conn.responseCode } catch (e: Exception) { -1 } == 200
        if (!headOk) {
            conn.disconnect()
            conn = open(url, referer)
            conn.setRequestProperty("Range", "bytes=0-0")
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
                // 直接删除而不是 setLength(0)：残留的空 .part 会让"是否下完"的判断失效
                if (f.exists() && !f.delete()) {
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
fun fallbackName(url: String): String = nameFromUrl(url)
fun nameFromUrl(url: String): String {
    val raw = url.substringBefore('?').substringBefore('#')
    val last = raw.substringAfterLast('/')
    val dec = try { java.net.URLDecoder.decode(last, "UTF-8") } catch (e: Exception) { last }
    return dec.ifBlank { "download" }
}
