package com.swift.download

import android.util.Log
import com.swift.download.settings.RetryPolicy
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
        val fileName: String?,
        /** v2.6.0：重定向后的最终地址，分段请求直接用它，避免每段重复跳一次 */
        val finalUrl: String = ""
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

        /**
         * v2.6.0 Work Stealing：把慢分段 [start, end] 从"当前下载前沿"一分为二。
         * 返回 (原段收缩后的闭区间终点, 新段起点, 新段终点)；剩余不足 [minBytes] 时返回 null。
         * [counted] 为慢分段已下载字节（相对 start），必须 >= 0。
         *
         * 切分点严格大于当前前沿，保证慢段线程收缩后不会写过头；
         * 两个分段合起来仍精确覆盖原区间，不重叠、不留洞。
         */
        fun stealSplit(start: Long, end: Long, counted: Long, minBytes: Long = 256 * 1024L): LongArray? {
            if (end <= start || counted < 0L) return null
            val total = end - start + 1
            if (counted >= total) return null
            val remaining = total - counted
            if (remaining <= minBytes) return null
            val splitRel = counted + remaining / 2
            val shrunkEnd = start + splitRel
            val newStart = shrunkEnd + 1
            if (newStart > end || shrunkEnd <= start + counted) return null
            return longArrayOf(shrunkEnd, newStart, end)
        }

        /**
         * v2.6.0：进度记录条目。end 为分段终点闭区间（-1 = 直到文件尾）。
         * 动态切分后起点不再是静态 split 的结果，必须把起点+终点一起持久化，
         * 否则暂停/进程被杀后无法还原分段布局，只能整段重下。
         */
        data class ProgEntry(val start: Long, val end: Long, val bytes: Long) {
            companion object {
                /** 旧格式（两列）的 end 哨兵：恢复时按静态 split 补齐 */
                const val END_UNKNOWN = Long.MIN_VALUE
            }
        }

        /**
         * 校验进度条目能否安全续传：区间必须从 0 开始、首尾相接、覆盖整个文件，
         * 每段字节数在合法范围内。动态切分产生的非等长区间同样满足这组不变量。
         */
        fun isResumable(entries: List<ProgEntry>, total: Long): Boolean {
            if (entries.isEmpty() || total <= 0L) return false
            val sorted = entries.sortedBy { it.start }
            if (sorted.first().start != 0L) return false
            for (e in sorted) {
                if (e.end == ProgEntry.END_UNKNOWN || e.end >= total) return false
                val len = if (e.end >= 0) e.end - e.start + 1 else total - e.start
                if (len <= 0L || e.bytes < 0L || e.bytes > len) return false
            }
            for (i in 1 until sorted.size) {
                if (sorted[i - 1].end + 1 != sorted[i].start) return false
            }
            val last = sorted.last()
            // 单线程直下用 end=-1 表示到文件尾，只能是唯一分段
            if (last.end == -1L) return sorted.size == 1
            return last.end == total - 1L
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
        private val throttle: (Int) -> Unit = {},
        /** v2.7 Stage1.3：注入的剩余空间读数；默认 -1（跳过校验，保持纯 JVM 测试无 Android 依赖） */
        private val availableBytes: () -> Long = { -1L },
        /** v2.7 Stage1.2：最多自动重试次数；默认 0（不重试，保持既有行为，服务层按设置注入） */
        private val maxRetries: Int = 0,
        /** v2.7 Stage1.2：重试等待回调（attempt 1-based），用于 UI 展示「网络重连 1/3…」 */
        private val onRetry: (attempt: Int, max: Int) -> Unit = { _, _ -> }
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
        /** v2.6.0：边播回写复用的 .part 句柄 + 它的锁 */
        private val writeLock = Any()
        @Volatile private var writeRaf: RandomAccessFile? = null
        /**
         * v2.6.0 Work Stealing：保护"分段进度推进 + 慢段切分"的复合操作。
         * 切分方在锁内收缩 segEnds 并登记新分段，慢段线程在锁内按收缩后的终点截断计数，
         * 两者串行后就不会出现"慢段多算字节导致验收失败"。
         */
        private val stealLock = Any()
        private val stealCount = AtomicLong(0)

        /** 单测可观测：实际发生的慢段接管次数 */
        fun stealEvents(): Long = stealCount.get()
        @Volatile var working = DownloadTask.Status.PROBING
            private set

        /** 统一收口线程池释放；now=true 用于失败/取消，立即中断残留线程 */
        private fun shutdownPool(now: Boolean) {
            val p = pool ?: return
            if (now) p.shutdownNow() else p.shutdown()
        }

        private fun openWriteRaf(t: DownloadTask): RandomAccessFile {
            val f = File(t.savePath + ".part")
            f.parentFile?.mkdirs()
            return RandomAccessFile(f, "rw")
        }

        /** 合并/取消前关闭回写句柄，避免 Windows 上 rename/delete 失败 */
        private fun closeWriteRaf() {
            val raf = synchronized(writeLock) {
                val r = writeRaf
                writeRaf = null
                r
            }
            try { raf?.close() } catch (e: Exception) {}
        }

        /** 单测可观测：线程池是否已关闭（尚未创建视为无泄漏） */
        fun isPoolShutdown(): Boolean = pool?.isShutdown ?: true

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
                val probe = probe(task.url, task.referer, task.cookie, task.userAgent)
                if (cancelled.get()) return
                val sourceUrl = probe.finalUrl.ifBlank { task.url }
                if (probe.totalBytes <= 0L) {
                    // v2.6.0：未知长度（Chunked / 无 Content-Length）不再是错误。
                    // 没有总长度就没有分段边界和断点语义，所以单线程从头写到 EOF，
                    // 完成后把实际字节数写回 totalBytes 再走同一套合并验收。
                    val serverName = probe.fileName?.let { sanitizeFileName(it) }.orEmpty()
                    val finalName = if (serverName.isNotBlank()) {
                        val dir = File(task.savePath).parentFile ?: File(".")
                        avoidNameCollision(dir, serverName, task.savePath)
                    } else task.fileName
                    val savePath = if (finalName != task.fileName) {
                        File(File(task.savePath).parentFile ?: File("."), finalName).absolutePath
                    } else task.savePath
                    if (savePath != task.savePath) clearStaleParts(task.savePath, savePath)
                    val streamTask = task.copy(
                        url = sourceUrl, totalBytes = -1L,
                        fileName = finalName, savePath = savePath
                    )
                    working = DownloadTask.Status.RUNNING
                    listener.onStatus(streamTask.copy(status = working))
                    pool = Executors.newFixedThreadPool(1)
                    val counter = AtomicLong(0L)
                    segEnds[0L] = -1L
                    segCounters[0L] = counter
                    pool?.execute { streamUnknownLength(streamTask, counter) }
                    return
                }
                // 断点数据是否可用：进度文件存在、服务器总大小与上次一致，且分段布局仍然匹配
                val progFile = File(task.dirFile(), Segments.progFileName(task.id))
                val segs = if (probe.acceptRanges) Segments.split(probe.totalBytes, task.threads) else null
                val rawProg = readProg(progFile)
                // v2.6.0：旧 .swiftprog 只有两列（start bytes），按静态分段补齐终点；
                // 新版三列（start end bytes）直接携带 Work Stealing 切出的动态区间。
                val progEntries = rawProg.mapValues { (start, e) ->
                    if (e.end != Segments.ProgEntry.END_UNKNOWN) e else {
                        val end = if (segs == null) -1L
                        else segs.firstOrNull { it[0] == start }?.let { it[1] }
                            ?: Segments.ProgEntry.END_UNKNOWN
                        Segments.ProgEntry(start, end, e.bytes)
                    }
                }
                var hasResume = progFile.isFile && task.totalBytes > 0L &&
                    task.totalBytes == probe.totalBytes && progEntries.isNotEmpty()
                if (hasResume) {
                    // v2.6.0：不再要求起点属于静态 split —— 动态切分的非等长区间只要
                    // 首尾相接且完整覆盖文件，就是合法断点，必须原样还原。
                    hasResume = Segments.isResumable(progEntries.values.toList(), probe.totalBytes)
                }
                if (hasResume) {
                    // 进度文件存在但 .part 已被删/被截断时，从记录的偏移继续写会留下"空洞"：
                    // 文件长度够、内容却是 0 字节。要求 .part 至少覆盖所有已记录的偏移。
                    val partLen = File(task.savePath + ".part").length()
                    // v2.6.0：只统计"实际写过字节"的分段。动态切出的新分段起点很大但
                    // bytes=0，若计入会把 .part 长度要求抬高到那个起点，误判为不匹配。
                    val maxResumed = progEntries.values
                        .filter { it.bytes > 0L }
                        .maxOfOrNull { it.start + it.bytes } ?: 0L
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
                // v2.6.0：Content-Disposition 给出的名字可能与别的任务撞车。
                // 直接覆盖会把别人已完成的文件或正在下载的 .part 清掉，必须避让。
                val finalName = if (canRename) {
                    val dir = File(task.savePath).parentFile ?: File(".")
                    avoidNameCollision(dir, serverName, task.savePath)
                } else task.fileName
                val savePath = if (finalName != task.fileName) {
                    File(File(task.savePath).parentFile ?: File("."), finalName).absolutePath
                } else task.savePath
                val t = task.copy(
                    url = sourceUrl, fileName = finalName,
                    totalBytes = probe.totalBytes, savePath = savePath,
                    // v2.7 Stage1.4：记录重定向后的最终地址供任务详情展示
                    finalUrl = if (probe.finalUrl.isNotBlank()) probe.finalUrl else sourceUrl
                )

                // v2.7 Stage1.3：空间前置校验。用"还需下载量"而非总大小判断，
                // 续传时只剩一小段就不该因总大小大于剩余空间而误拦。
                val remainingNeed = if (hasResume) {
                    probe.totalBytes - (progEntries.values.sumOf { it.bytes.coerceAtLeast(0L) })
                } else probe.totalBytes
                val freeBytes = availableBytes()
                if (!StorageGuard.hasEnough(freeBytes, remainingNeed)) {
                    throw IOException(StorageGuard.insufficientMessage(freeBytes, remainingNeed))
                }
                if (!hasResume) {
                    // 新任务：清掉同名残留半成品，避免旧数据混入新文件
                    clearStaleParts(task.savePath, t.savePath)
                }
                expectedTotal = probe.totalBytes
                working = DownloadTask.Status.RUNNING
                listener.onStatus(t.copy(status = working))

                // v2.6.0：恢复时优先使用 .swiftprog 里持久化的（可能是动态的）区间，
                // 完全跳过静态 split —— 否则动态切出的分段会丢失终点、被误判为无效断点。
                val ranges = when {
                    hasResume -> progEntries.values.sortedBy { it.start }
                        .map { longArrayOf(it.start, it.end, it.bytes) }
                    segs == null -> listOf(longArrayOf(0L, -1L, 0L))
                    else -> segs.map { longArrayOf(it[0], it[1], 0L) }
                }
                pool = Executors.newFixedThreadPool(ranges.size)
                // 先登记全部分段计数，再启动线程：
                // 否则先跑完的分段会看到不完整的 segCounters.size，被误判为"全部完成"
                val counters = ArrayList<AtomicLong>(ranges.size)
                for (r in ranges) {
                    val c = AtomicLong(r[2])
                    segEnds[r[0]] = r[1]
                    segCounters[r[0]] = c
                    atomicBytes.addAndGet(r[2])
                    counters.add(c)
                }
                for (i in ranges.indices) {
                    val r = ranges[i]
                    pool?.execute { segment(t, r[0], r[1], counters[i]) }
                }
            } catch (e: Exception) {
                if (!cancelled.get()) {
                    failed.set(true)
                    working = DownloadTask.Status.FAILED
                    shutdownPool(now = true)
                    closeWriteRaf()
                    listener.onError(
                        task.copy(status = working), e.message ?: "未知错误"
                    )
                }
            } finally {
                ready.countDown()
                // 探测失败/取消等没有启动分段线程的分支同样不能留下线程池
                if (pool != null && (failed.get() || cancelled.get())) shutdownPool(now = true)
            }
        }

        fun totalBytes(): Long = atomicBytes.get()

        /** 各分段已下载字节快照（服务轮询回写 .swiftprog / DB 用） */
        fun snapshot(): Map<Long, Long> = segCounters.mapValues { it.value.get() }

        /**
         * v2.6.0：带区间终点的快照。必须和 [snapshot] 分开 —— 服务写 .swiftprog 用这个，
         * 否则 Work Stealing 切出的动态分段在恢复时无法还原终点。
         */
        fun snapshotEntries(): List<Segments.ProgEntry> = segCounters.entries
            .sortedBy { it.key }
            .map { Segments.ProgEntry(it.key, segEnds[it.key] ?: -1L, it.value.get()) }

        /**
         * v2.2 边下边播回写：外部（本地流服务器）在 offset 处写入新拉到的字节。
         *  - 写入 .part 文件（保证盘上数据就位）
         *  - 仅当 offset 正好处在该分段的下载前沿时计入进度（计数器/速度/DB 同步推进）；
         *    处于已有数据区（重复拉取）或洞区（越前写入）时只落盘不计数。
         * 返回是否计入了进度。引擎自身线程已停止（任务进入流模式）时调用安全。
         */
        fun externalWrite(t: DownloadTask, offset: Long, buf: ByteArray, len: Int): Boolean {
            if (len <= 0 || offset < 0) return false
            // 合并已经开始/完成后不再接受回写：否则会在已不存在的 .part 上新建文件
            if (finishing.get() || working == DownloadTask.Status.COMPLETED) return false
            // v2.6.0：复用同一个 .part 句柄，避免播放器每次拉取都开/关文件；
            // 合并前会显式关闭，保证 rename/delete 不受句柄占用影响。
            synchronized(writeLock) {
                val raf = writeRaf ?: openWriteRaf(t).also { writeRaf = it }
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
            shutdownPool(now = true)
            closeWriteRaf()
        }

        private fun segment(t: DownloadTask, start: Long, end: Long, counter: AtomicLong) {
            // v2.7 Stage1.2：可恢复瞬断的指数退避重试。每次重试都从 counter 重新读取
            // resumeFrom（已落盘的字节由 counter 跟踪），因此续传点始终正确，不会重复/错位。
            var attempt = 0
            while (!cancelled.get()) {
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
                val wantRange = end >= 0 || resumeFrom > 0L
                // v2.6.0：Range 必须在连接建立前交给 HttpUtil —— 重定向的每一跳都要重放，
                // 否则跟随跳转到最终地址后 Range 丢失，整文件被当成某个分段落盘。
                val range = if (end >= 0) "bytes=${start + resumeFrom}-$end"
                    else if (resumeFrom > 0L) "bytes=$resumeFrom-" else null
                conn = open(t.url, t.referer, t.cookie, t.userAgent, range = range)
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
                conn.inputStream.use { input ->
                    val buf = ByteArray(65536) // v1.0：64KB 缓冲
                    while (!cancelled.get()) {
                        // v2.6.0：终点动态读取。本段可能被 Work Stealing 收缩过，
                        // 收缩后只写到新终点，后半段由接管的空闲线程负责。
                        val liveEnd = segEnds[start] ?: end
                        val liveLen = if (liveEnd >= 0) liveEnd - start + 1 else -1L
                        if (liveLen > 0 && myPos >= liveLen) break
                        val want = if (liveLen > 0) minOf(buf.size.toLong(), liveLen - myPos).toInt() else buf.size
                        val read = input.read(buf, 0, want)
                        if (read < 0) break
                        writeAt(start, myPos, buf, read, file)
                        myPos += read
                        // 计数在 stealLock 内按"当前"终点截断：切分方收缩终点后，
                        // 慢段线程即使已多读一点也不会把后半段字节算进自己，验收才对得上。
                        synchronized(stealLock) {
                            val cap = segEnds[start] ?: end
                            val limit = if (cap >= 0) cap - start + 1 else Long.MAX_VALUE
                            val counted = minOf(myPos, limit)
                            val delta = counted - counter.get()
                            if (delta > 0) {
                                counter.set(counted)
                                atomicBytes.addAndGet(delta)
                            }
                        }
                        throttle(read) // 限速：在字节落盘并计数后阻塞，速度采样自然反映实际速率
                    }
                }
                if (cancelled.get()) return
                // 先关掉本线程的文件句柄再结算：Windows/部分文件系统上
                // 句柄未关时 rename/delete 会失败，合并后 .part 会残留
                try { file.close() } catch (e: Exception) {}
                raf = null
                // v2.5.2：严格按（可能已收缩的）分段长度收尾。少读一个字节即失败，
                // 多读的部分由接管线程覆盖，不会拼出中间有洞的文件。
                val finalEnd = segEnds[start] ?: end
                val finalLen = if (finalEnd >= 0) finalEnd - start + 1 else -1L
                if (finalLen > 0 && myPos < finalLen) {
                    throw IOException("分段数据不完整：还缺 ${finalLen - myPos} 字节（已收 ${myPos - resumeFrom}）")
                }
                // 本段先完成：交给空闲线程协助最慢的分段，再结算。
                // 顺序必须是"先登记新段、后 segDone"，否则最后一段完成时
                // done >= segCounters.size 会被提前满足而漏掉新段。
                maybeSteal(t)
                segDone(t)
                return // v2.7：本段成功后退出重试循环
            } catch (e: Exception) {
                if (cancelled.get()) return
                val recoverable = !RetryPolicy.isFatal(e) && RetryPolicy.isRecoverable(e)
                if (recoverable && maxRetries > 0 && attempt < maxRetries) {
                    attempt++
                    Log.w(TAG, "segment recoverable error, retry $attempt/$maxRetries", e)
                    onRetry(attempt, maxRetries)
                    // 可打断的退避等待：用户暂停/取消时立即醒来，不空等 30s
                    var waited = 0L
                    val delay = RetryPolicy.delayMs(attempt)
                    while (waited < delay && !cancelled.get()) {
                        val step = minOf(200L, delay - waited)
                        try { Thread.sleep(step) } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt(); break
                        }
                        waited += step
                    }
                    continue // 重开连接，从 counter 的当前值继续
                }
                // 硬错误或重试耗尽 → 标记失败并终止其余分段。
                // 否则其余分段跑完会触发 onFinished，把失败任务翻成"已完成"并合并不完整文件
                Log.w(TAG, "segment error", e)
                failed.set(true)
                cancelled.set(true)
                shutdownPool(now = true)
                closeWriteRaf()
                working = DownloadTask.Status.FAILED
                listener.onError(t.copy(status = working), e.message ?: "未知错误")
                return
            } finally {
                try { raf?.close() } catch (e: Exception) {}
                conn?.disconnect()
            }
            }
        }

        /**
         * v2.6.0：未知 Content-Length 的单线程流式下载。
         * 没有总长度就没有分段边界，也不存在可靠的断点偏移，因此不登记 .swiftprog；
         * 写完 EOF 后把实际字节数回填为 totalBytes，再复用同一套合并/验收收口。
         */
        private fun streamUnknownLength(t: DownloadTask, counter: AtomicLong) {
            var conn: HttpURLConnection? = null
            var raf: RandomAccessFile? = null
            try {
                conn = open(t.url, t.referer, t.cookie, t.userAgent)
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code：服务器未返回可下载内容")
                val f = File(t.savePath + ".part")
                f.parentFile?.mkdirs()
                val file = RandomAccessFile(f, "rw")
                raf = file
                file.setLength(0L)
                var written = 0L
                conn.inputStream.use { input ->
                    val buf = ByteArray(65536)
                    while (!cancelled.get()) {
                        val n = input.read(buf)
                        if (n < 0) break
                        file.write(buf, 0, n)
                        written += n
                        counter.set(written)
                        atomicBytes.addAndGet(n.toLong())
                        throttle(n)
                        listener.onProgress(t.copy(downloadedBytes = written), written, 0L)
                    }
                }
                if (cancelled.get()) return
                try { file.close() } catch (e: Exception) {}
                raf = null
                val finalTask = t.copy(totalBytes = written, downloadedBytes = written)
                expectedTotal = written
                if (!mergeParts(finalTask)) {
                    throw IOException("合并文件失败（.part 无法改名或长度不符）")
                }
                working = DownloadTask.Status.COMPLETED
                listener.onFinished(finalTask.copy(status = working, downloadedBytes = written))
                shutdownPool(now = false)
            } catch (e: Exception) {
                if (!cancelled.get()) {
                    Log.w(TAG, "streamUnknownLength", e)
                    failed.set(true)
                    cancelled.set(true)
                    shutdownPool(now = true)
                    closeWriteRaf()
                    working = DownloadTask.Status.FAILED
                    listener.onError(t.copy(status = working), e.message ?: "未知错误")
                }
            } finally {
                try { raf?.close() } catch (e: Exception) {}
                conn?.disconnect()
            }
        }

        /**
         * v2.6.0 Work Stealing：本段已完成，扫描其余分段，找到剩余量最大且超过阈值的慢段，
         * 从它当前前沿一分为二，后半段交给本线程继续下载。
         *
         * 全程在 [stealLock] 内完成"收缩慢段终点 + 登记新计数器 + 派发新线程"，
         * 保证慢段线程看到的终点与验收使用的终点一致。
         */
        private fun maybeSteal(t: DownloadTask) {
            if (cancelled.get() || failed.get() || finishing.get()) return
            val activePool = pool ?: return
            synchronized(stealLock) {
                var victimStart = -1L
                var victimEnd = -1L
                var victimCounter: AtomicLong? = null
                var maxRemaining = 0L
                for ((start, end) in segEnds) {
                    if (end < 0) continue // 单线程直下/未知长度，无从切分
                    val counter = segCounters[start] ?: continue
                    val done = counter.get()
                    val segLen = end - start + 1
                    if (done >= segLen) continue // 已完成的分段不作为接管目标
                    val remaining = segLen - done
                    if (remaining > maxRemaining) {
                        maxRemaining = remaining
                        victimStart = start
                        victimEnd = end
                        victimCounter = counter
                    }
                }
                if (victimStart < 0L || victimCounter == null) return
                val plan = Segments.stealSplit(victimStart, victimEnd, victimCounter.get()) ?: return
                val (shrunkEnd, newStart, newEnd) = plan
                segEnds[victimStart] = shrunkEnd
                val newCounter = AtomicLong(0L)
                segCounters[newStart] = newCounter
                segEnds[newStart] = newEnd
                stealCount.incrementAndGet()
                try {
                    activePool.execute { segment(t, newStart, newEnd, newCounter) }
                } catch (e: Exception) {
                    // 线程池可能刚好被另一条完成路径关闭：撤销登记，慢段仍按原样下载
                    segCounters.remove(newStart)
                    segEnds.remove(newStart)
                    segEnds[victimStart] = victimEnd
                    stealCount.decrementAndGet()
                }
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
                shutdownPool(now = true)
                closeWriteRaf()
                listener.onError(t.copy(status = working), problem)
                return
            }
            if (!mergeParts(t)) {
                working = DownloadTask.Status.FAILED
                failed.set(true)
                shutdownPool(now = true)
                closeWriteRaf()
                listener.onError(t.copy(status = working), "合并文件失败（.part 无法改名或长度不符）")
                return
            }
            working = DownloadTask.Status.COMPLETED
            listener.onFinished(t.copy(
                status = working,
                downloadedBytes = atomicBytes.get()
            ))
            // v2.6.0：固定线程池的核心线程不会自行退出，成功后也必须显式 shutdown，
            // 否则每个任务都会永久留 N 条线程。
            shutdownPool(now = false)
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
            closeWriteRaf() // 不能让任何句柄持有 .part，否则 rename/delete 会失败
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

        /**
         * 读取 .swiftprog：兼容两列旧格式（start bytes）与三列新格式（start end bytes）。
         * 旧格式的 end 用哨兵标记，由 prepare() 按静态分段补齐。
         */
        private fun readProg(prog: File): Map<Long, Segments.ProgEntry> {
            if (!prog.isFile) return emptyMap()
            return try {
                val out = LinkedHashMap<Long, Segments.ProgEntry>()
                for (line in prog.readLines()) {
                    val p = line.trim().split(" ")
                    when (p.size) {
                        2 -> {
                            val start = p[0].toLongOrNull() ?: continue
                            val bytes = p[1].toLongOrNull() ?: continue
                            out[start] = Segments.ProgEntry(
                                start, Segments.ProgEntry.END_UNKNOWN, bytes)
                        }
                        3 -> {
                            val start = p[0].toLongOrNull() ?: continue
                            val end = p[1].toLongOrNull() ?: continue
                            val bytes = p[2].toLongOrNull() ?: continue
                            out[start] = Segments.ProgEntry(start, end, bytes)
                        }
                    }
                }
                out
            } catch (e: Exception) { emptyMap() }
        }
    }

    fun newJob(
        task: DownloadTask,
        listener: EngineListener,
        throttle: (Int) -> Unit = {},
        availableBytes: () -> Long = { -1L },
        maxRetries: Int = 0,
        onRetry: (attempt: Int, max: Int) -> Unit = { _, _ -> }
    ): DownloadJob = DownloadJob(task, listener, throttle, availableBytes, maxRetries, onRetry)

    /**
     * Range 探测：HEAD 失败自动退化 GET+Range（v1.0 行为）。
     * v2.6.0：两条路径都必须拿到 2xx —— 403/404 的 HTML 错误页绝不能被当成文件长度。
     */
    fun probe(
        url: String,
        referer: String = "",
        cookie: String = "",
        userAgent: String = ""
    ): ProbeResult {
        var conn = open(url, referer, cookie, userAgent, method = "HEAD")
        var code = try { conn.responseCode } catch (e: Exception) { -1 }
        if (code !in 200..299) {
            conn.disconnect()
            conn = open(url, referer, cookie, userAgent, range = "bytes=0-0")
            code = try { conn.responseCode } catch (e: Exception) { -1 }
            if (code !in 200..299) {
                val message = "HTTP $code：服务器未返回可下载内容"
                conn.disconnect()
                throw IOException(message)
            }
        }
        val finalUrl = conn.url.toString()
        val len = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        val cr = conn.getHeaderField("Content-Range")
        val ranges = conn.getHeaderField("Accept-Ranges")?.equals("bytes", true) == true ||
            (cr != null && cr.startsWith("bytes"))
        val total = if (cr != null && cr.contains("/")) {
            cr.substringAfter("/").toLongOrNull() ?: len
        } else len
        val name = Format.fileNameFromDisposition(conn.getHeaderField("Content-Disposition"))
            ?: nameFromUrl(finalUrl)
        conn.disconnect()
        return ProbeResult(total, ranges, name, finalUrl)
    }

    /** 统一走 HttpUtil：手动重定向 + 统一 UA + Cookie 透传 */
    private fun open(
        url: String,
        referer: String = "",
        cookie: String = "",
        userAgent: String = "",
        range: String? = null,
        method: String = "GET"
    ): HttpURLConnection {
        val extra = HashMap<String, String>()
        if (cookie.isNotBlank()) extra["Cookie"] = cookie
        if (userAgent.isNotBlank()) extra["User-Agent"] = userAgent
        return HttpUtil.open(url, referer, range, method, extra)
    }

    companion object {
        const val TAG = "DownloadEngine"

        /** 探测/准备阶段的共享后台池（探测含网络，不能在主线程执行） */
        private val starterPool: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "dl-probe").apply { isDaemon = true }
        }

        /**
         * v2.6.0：Content-Disposition 改名时的磁盘冲突避让。
         * 同名正式文件或同名 `.part`（别人正在下）都算占用，自动追加 " (2)"、" (3)"。
         * 任务自己的原始路径不算冲突，否则同一任务重下永远拿不到原名。
         */
        fun avoidNameCollision(dir: File, desiredName: String, originalPath: String? = null): String {
            val original = originalPath?.let { File(it).absolutePath }
            fun occupied(name: String): Boolean {
                val f = File(dir, name)
                if (f.absolutePath == original) return false
                if (f.exists()) return true
                val part = File(dir, name + ".part")
                return part.exists() && part.absolutePath != "$original.part"
            }
            if (!occupied(desiredName)) return desiredName
            val dot = desiredName.lastIndexOf('.')
            val stem = if (dot > 0) desiredName.substring(0, dot) else desiredName
            val ext = if (dot > 0) desiredName.substring(dot) else ""
            var i = 2
            while (true) {
                val candidate = "$stem ($i)$ext"
                if (!occupied(candidate)) return candidate
                i++
            }
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
