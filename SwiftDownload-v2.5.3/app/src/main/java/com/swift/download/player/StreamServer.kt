package com.swift.download.player

import com.swift.download.HttpUtil
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale

/**
 * v2.2 新增：边下边播 / 投屏共用的本地 HTTP 服务器。
 *
 *  - 绑定 0.0.0.0（本机播放器与局域网 DLNA 渲染器均可访问）
 *  - 支持 Range（播放器拖进度条即发 Range，服务器按段响应 206）
 *  - 两种数据来源（[Backing]）：
 *      [TaskBacking]   —— 边下边播：已在盘上的字节直接读 .part，缺失字节从远端拉取，
 *                         并回写给下载引擎（externalWrite）→ 边播边把任务下完
 *      [FileBacking]   —— 投屏/完成后播放：直接整文件读盘
 *
 *  StreamMath 为纯函数（Range 解析 / 已下载区段计算），JVM 单测覆盖。
 */
object StreamMath {

    /**
     * 解析 HTTP Range 头 → [start, endInclusive]（已按 total 裁剪）。
     *  - null / 格式非法 → 返回 [0, total-1]（整文件）
     *  - "bytes=S-"  → [S, total-1]
     *  - "bytes=S-E" → [S, min(E, total-1)]
     *  - "bytes=-N"  → 后缀 N 字节 [max(total-N,0), total-1]
     *  - start >= total → 返回空数组（调用方回 416）
     */
    fun parseRange(header: String?, total: Long): LongArray {
        if (total <= 0) return longArrayOf(0L, -1L)
        if (header.isNullOrBlank()) return longArrayOf(0L, total - 1)
        val spec = header.trim().removePrefix("bytes=").trim()
        if (spec.contains(',')) {
            // 多段请求：取第一段（视频播放器一般不会发）
            val first = spec.substringBefore(',')
            return parseRange("bytes=$first", total)
        }
        val dash = spec.indexOf('-')
        if (dash < 0) return longArrayOf(0L, total - 1)
        val left = spec.substring(0, dash).trim()
        val right = spec.substring(dash + 1).trim()
        val start: Long
        val end: Long
        if (left.isEmpty()) {
            // 后缀形式 bytes=-N
            val n = right.toLongOrNull() ?: return longArrayOf(0L, total - 1)
            start = (total - n).coerceAtLeast(0L)
            end = total - 1
        } else {
            start = left.toLongOrNull() ?: return longArrayOf(0L, total - 1)
            val e = right.toLongOrNull()
            end = if (e == null || e < 0) total - 1 else e.coerceAtMost(total - 1)
        }
        if (start < 0 || start >= total) return LongArray(0)
        if (end < start) return longArrayOf(start, start)
        return longArrayOf(start, end)
    }

    /**
     * offset 处的字节是否已在盘上。
     * segs：分段起点 → 该段已下载字节数（引擎 snapshot 语义）。
     * 覆盖规则：段 k 覆盖 [segStart, segStart + downloaded)；段边界由 segStart 升序推导。
     */
    fun onDisk(offset: Long, segs: Map<Long, Long>): Boolean {
        if (offset < 0) return false
        val starts = segs.keys.sorted()
        for ((i, s) in starts.withIndex()) {
            val segEndBound = if (i + 1 < starts.size) starts[i + 1] else Long.MAX_VALUE
            if (offset >= s && offset < segEndBound) {
                return offset < s + (segs[s] ?: 0L)
            }
        }
        return false
    }

    /**
     * 从 offset 起连续在盘上的字节区间右端（独占）。offset 不在盘上则返回 offset 本身。
     */
    fun diskRunEnd(offset: Long, segs: Map<Long, Long>, total: Long): Long {
        if (total <= 0 || !onDisk(offset, segs)) return offset
        val starts = segs.keys.sorted()
        if (starts.isEmpty()) return offset
        var end = offset
        // 找到 offset 所在段
        var idx = -1
        for (i in starts.indices) {
            val bound = if (i + 1 < starts.size) starts[i + 1] else Long.MAX_VALUE
            if (offset < bound) { idx = i; break }
        }
        if (idx < 0) return offset
        var k = idx
        var cur = offset
        while (k < starts.size) {
            val s = starts[k]
            val bound = if (k + 1 < starts.size) starts[k + 1] else total
            val frontier = s + (segs[s] ?: 0L)
            if (frontier <= cur) break                 // 段内有洞
            val segEnd = minOf(frontier, minOf(bound, total))
            if (segEnd <= cur) break
            cur = segEnd
            end = segEnd
            if (frontier < minOf(bound, total)) break  // 该段未下满，连续区到此为止
            k += 1
        }
        return end
    }
}

/** 数据来源抽象 */
interface Backing {
    fun total(): Long
    fun contentType(): String
    /** 底层文件（边下边播 → .part；投屏 → 本体） */
    fun backingFile(): File
    /** 读盘上已存在的字节（保证 onDisk 才会调） */
    fun readDisk(raf: RandomAccessFile, offset: Long, len: Int, out: ByteArray): Int
    /** offset 起连续在盘上的右端（独占） */
    fun diskRunEnd(offset: Long): Long
    /**
     * 从远端拉取 [offset, offset+len) 写入 out，返回实际读到字节数，<=0 结束。
     * 实现须把字节持久化（边下边播回写引擎）。
     */
    fun fetch(offset: Long, out: ByteArray, len: Int): Int
    /** 服务器 stop 时回调（收尾） */
    fun close() {}
}

/** 边下边播：远端直链 + 引擎回写 */
class TaskBacking(
    private val partPath: String,
    private val url: String,
    private val total: Long,
    private val mime: String,
    private val referer: String = "",
    private val cookie: String = "",
    private val externalWrite: (offset: Long, buf: ByteArray, len: Int) -> Boolean,
    private val segments: () -> Map<Long, Long>
) : Backing {

    override fun total(): Long = total
    override fun contentType(): String = mime
    override fun backingFile(): File = File(partPath)
    override fun diskRunEnd(offset: Long): Long = StreamMath.diskRunEnd(offset, segments(), total)

    override fun readDisk(raf: RandomAccessFile, offset: Long, len: Int, out: ByteArray): Int {
        raf.seek(offset)
        return raf.read(out, 0, len)
    }

    /**
     * v2.5：复用一条长连接按序拉取。
     * 原实现每次 fetch 都新建一条 HTTP 连接 —— 播放器拖动进度会触发大量
     * Range 请求，每次重建连接（TCP+TLS 握手）导致播放严重卡顿。
     * 现在：连接保持打开，按需 seek 续读；仅在连接失效时才重建。
     */
    private var conn: java.net.HttpURLConnection? = null
    private var stream: InputStream? = null
    private var streamPos = -1L    // 流当前实际读到的位置（v2.5.1：skip 必须基于实时位置计算）
    private var streamEnd = -1L     // 当前流覆盖到的末尾偏移（闭区间）

    /**
     * v2.6.0：多连接播放器会同时发不同 Range，而 streamPos/conn/stream 是这条
     * 长连接的共享状态。两个 fetch 并发进入 reopen() 会互相把状态改掉，
     * 轻则数据错位，重则一个连接读到另一个连接的偏移。整段读取与 close 互斥。
     */
    @Synchronized
    override fun fetch(offset: Long, out: ByteArray, len: Int): Int {
        var got = 0
        while (got < len) {
            val s = stream
            val desired = offset + got
            // 位置不可用 / 需要回退（连接无法倒读）/ 超出覆盖范围：重建连接
            if (s == null || conn == null || streamPos < 0 || desired < streamPos || desired > streamEnd) {
                reopen(desired)
                continue
            }
            if (desired > streamPos) {
                // 前跳：只跳「实时位置 → 目标位置」的差值。
                // v2.5 bug：此前用 offset+got-streamStart，把已消费字节重复计入，
                // 复用长连接时每次多跳一段 → 播放/落盘数据整体错位。
                val skip = desired - streamPos
                val skipped = s.skip(skip)
                streamPos += skipped
                if (skipped < skip) { reopen(desired); continue }
            }
            val n = s.read(out, got, len - got)
            if (n < 0) {
                // 流已到尽头：从当前偏移重建
                reopen(desired)
                if (conn == null) return if (got > 0) got else -1
                continue
            }
            streamPos += n
            got += n
        }
        if (got > 0) externalWrite(offset, out, got)
        return got
    }

    /** 以 [from] 为起点重新建立一条 Range 连接 */
    private fun reopen(from: Long) {
        closeStream()
        // v2.6.0：统一走 HttpUtil —— 手动跟随 301/302/303/307/308（含跨协议），
        // 且每一跳都重新带上 Range/Referer/UA，避免重定向后数据错位。
        val extra = if (cookie.isNotBlank()) mapOf("Cookie" to cookie) else emptyMap()
        val c = HttpUtil.open(url, referer, "bytes=$from-", extraHeaders = extra)
        try {
            val code = c.responseCode
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            val cr = c.getHeaderField("Content-Range")
            val end = cr?.substringAfter('-')?.substringBefore('/')?.toLongOrNull() ?: (total - 1)
            // v2.5.2：服务器忽略 Range 返回 200 时，响应体是从 0 开始的整个文件。
            // 若仍把 streamPos 记成 from，后续读取会整体错位（读到的字节被当成 from 处的内容）。
            streamPos = if (code == 200) 0L else from
            streamEnd = end
            conn = c
            stream = c.inputStream
        } catch (e: Exception) {
            try { c.disconnect() } catch (e2: Exception) {}
            conn = null
            stream = null
            streamPos = -1L
            streamEnd = -1L
            throw e
        }
    }

    private fun closeStream() {
        try { stream?.close() } catch (e: Exception) {}
        try { conn?.disconnect() } catch (e: Exception) {}
        stream = null
        conn = null
        streamPos = -1L
        streamEnd = -1L
    }

    @Synchronized
    override fun close() {
        closeStream()
    }
}

/** 已完成文件 / 投屏：整文件读盘 */
class FileBacking(private val file: File, private val mime: String) : Backing {
    override fun total(): Long = file.length()
    override fun contentType(): String = mime
    override fun backingFile(): File = file
    override fun diskRunEnd(offset: Long): Long = file.length()
    override fun fetch(offset: Long, out: ByteArray, len: Int): Int = -1
    override fun readDisk(raf: RandomAccessFile, offset: Long, len: Int, out: ByteArray): Int {
        raf.seek(offset)
        return raf.read(out, 0, len)
    }
}

/**
 * 单用途本地 HTTP 服务器。start() 返回实际端口；localUrl() 给本机播放器，
 * lanUrl(ip) 给 DLNA 渲染器。onClientGone：一次连接结束后回调。
 */
class StreamServer(
    private val backing: Backing,
    private val onClientGone: () -> Unit = {}
) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private val rafLock = Any()
    private var raf: RandomAccessFile? = null
    private val clientCount = java.util.concurrent.atomic.AtomicInteger(0)
    /** v2.5.2：跟踪活动连接，stop() 时主动断开 —— 否则播放器连接会一直挂到读超时，
     *  期间仍持有 .part 的文件句柄，导致合并/改名失败 */
    private val clients = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Socket, Boolean>()
    )

    /** 是否有播放器/渲染器连着（服务侧判定流会话空闲用） */
    fun hasClients(): Boolean = clientCount.get() > 0

    fun start(): Int {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(0))
        server = ss
        running = true
        thread = Thread {
            while (running) {
                try {
                    val client = ss.accept()
                    clients.add(client)
                    // v2.5：每个客户端独立线程处理。
                    // 原实现在 accept 循环里同步 handle()，一个慢客户端（例如正在
                    // 拉取缺字节的视频）会把后续所有连接堵住，播放器表现为"卡住"。
                    Thread {
                        try { handle(client) } catch (e: Exception) { } finally { clients.remove(client) }
                    }.apply { isDaemon = true; name = "stream-client"; start() }
                } catch (e: Exception) {
                    if (running) continue
                }
            }
        }.apply { isDaemon = true; name = "stream-server"; start() }
        return ss.localPort
    }

    fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) {}
        // 主动断开所有在传的客户端（播放器/电视），让它们立即放弃本次读取
        for (c in clients.toList()) {
            try { c.close() } catch (e: Exception) {}
        }
        clients.clear()
        synchronized(rafLock) {
            try { raf?.close() } catch (e: Exception) {}
            raf = null
        }
        thread?.interrupt()
        backing.close()
    }

    /** 本机（边下边播）地址 */
    fun localUrl(): String = "http://127.0.0.1:${server?.localPort ?: 0}/file"

    /** 局域网（投屏）地址 */
    fun lanUrl(ip: String): String = "http://$ip:${server?.localPort ?: 0}/file"

    /* ---------------- 连接处理 ---------------- */

    private fun handle(client: Socket) {
        clientCount.incrementAndGet()
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val out = client.getOutputStream()
            val requestLine = readLine(input) ?: run { client.close(); return }
            // v2.6.0：播放器/电视的探测请求常用 HEAD，只取响应头不取 body。
            val isGet = requestLine.startsWith("GET ")
            val isHead = requestLine.startsWith("HEAD ")
            if (!isGet && !isHead) {
                client.close(); return
            }
            var rangeHeader: String? = null
            var connectionHeader: String? = null
            while (true) {
                val next = readLine(input) ?: break
                if (next.isEmpty()) break
                val lower = next.lowercase(Locale.US)
                if (lower.startsWith("range:")) rangeHeader = next.substringAfter(':').trim()
                if (lower.startsWith("connection:")) connectionHeader = next.substringAfter(':').trim()
            }
            val keepAlive = connectionHeader?.lowercase(Locale.US) == "keep-alive"
            val range = StreamMath.parseRange(rangeHeader, backing.total())
            if (range.isEmpty()) {
                respond(out, 416, mapOf(
                    "Content-Type" to backing.contentType(),
                    "Content-Length" to "0",
                    "Accept-Ranges" to "bytes",
                    "Content-Range" to "bytes */${backing.total()}",
                    "Connection" to "close"
                ), null)
                return
            }
            val isFull = rangeHeader == null
            val status = if (isFull) 200 else 206
            val headers = linkedMapOf(
                "Content-Type" to backing.contentType(),
                "Accept-Ranges" to "bytes",
                "Content-Length" to "${range[1] - range[0] + 1}"
            )
            if (!isFull) {
                headers["Content-Range"] = "bytes ${range[0]}-${range[1]}/${backing.total()}"
            }
            headers["Connection"] = if (keepAlive) "keep-alive" else "close"
            respond(out, status, headers, null)
            // HEAD：响应头与 GET 完全一致（含 Content-Length / Content-Range / Accept-Ranges），
            // 但绝不写 body，也不触发远端拉取，避免探测请求把任务数据拉一遍。
            if (isGet) pump(out, range[0], range[1] + 1)
            out.flush()
        } catch (e: Exception) {
            // 播放器断开 / 网络错误：静默
        } finally {
            try { client.close() } catch (e: Exception) {}
            clientCount.decrementAndGet()
            onClientGone()
        }
    }
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun respond(out: OutputStream, status: Int, headers: Map<String, String>, body: ByteArray?) {
        val reason = when (status) {
            200 -> "OK"; 206 -> "Partial Content"; 416 -> "Range Not Satisfiable"; else -> "OK"
        }
        val sb = StringBuilder("HTTP/1.1 $status $reason\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        body?.let { out.write(it) }
        out.flush()
    }

    /** 从 [from, untilExclusive) 传输：盘上有则读盘，否则拉远端（Backing 内部回写持久化） */
    private fun pump(out: OutputStream, from: Long, until: Long) {
        val buf = ByteArray(256 * 1024)
        var pos = from
        while (pos < until) {
            val diskEnd = backing.diskRunEnd(pos)
            if (diskEnd > pos) {
                val len = minOf(buf.size.toLong(), minOf(diskEnd, until) - pos).toInt()
                if (len <= 0) return
                val got = synchronized(rafLock) {
                    val f = raf ?: openRaf().also { raf = it }
                    backing.readDisk(f, pos, len, buf)
                }
                if (got <= 0) return
                out.write(buf, 0, got)
                pos += got
            } else {
                val len = minOf(buf.size.toLong(), until - pos).toInt()
                if (len <= 0) return
                val got = backing.fetch(pos, buf, len)
                if (got <= 0) return
                out.write(buf, 0, got)
                pos += got
            }
            out.flush()
        }
    }

    private fun openRaf(): RandomAccessFile = RandomAccessFile(backing.backingFile(), "r")
}
