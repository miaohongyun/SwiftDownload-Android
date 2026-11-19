package com.swift.download.bt

import com.swift.download.player.Backing
import java.io.File
import java.io.RandomAccessFile

/**
 * v2.7 Stage2.2：BT 视频按需边下边播数据源（实现 StreamServer.Backing）。
 *
 * 播放器请求某字节区间时：
 * - [diskRunEnd] 用 [readyEnd] 拿到"从 offset 起连续已就绪"的右端；同时 [onRequest]
 *   回调让服务调用 setPieceDeadline 优先调度当前位置 + 预读 piece。
 * - 区间已就绪 → readDisk 从落盘文件随机读（零额外拷贝）。
 * - 区间未就绪 → fetch 短轮询等待 piece 到位（有上限避免挂死），就绪后直接读盘返回。
 *
 * 所有 piece 计算由 [PieceRange] 纯逻辑完成；这里只做文件 IO 与等待循环。
 */
class TorrentBacking(
    private val videoFile: File,
    private val size: Long,
    private val mime: String,
    /** 服务层：调度请求区间对应 piece 并返回是否已全部就绪 */
    private val onRequest: (offset: Long, len: Int) -> Boolean,
    /** 等待未就绪 piece 的上限（毫秒） */
    private val waitTimeoutMs: Long = 20_000L
) : Backing {

    override fun total(): Long = size
    override fun contentType(): String = mime
    override fun backingFile(): File = videoFile
    override fun readDisk(raf: RandomAccessFile, offset: Long, len: Int, out: ByteArray): Int {
        return try {
            raf.seek(offset)
            raf.read(out, 0, len)
        } catch (e: Exception) {
            -1
        }
    }

    /** 由服务层注入：从 offset 起连续已就绪字节的右端（独占） */
    @Volatile private var readyEndProvider: (Long) -> Long = { offset -> if (videoFile.isFile) minOf(size, offset + 65536L) else offset }

    fun setReadyEndProvider(provider: (Long) -> Long) { readyEndProvider = provider }

    override fun diskRunEnd(offset: Long): Long = readyEndProvider(offset).coerceIn(offset, size)

    override fun fetch(offset: Long, out: ByteArray, len: Int): Int {
        // 触发 piece 调度；若已就绪直接读盘
        onRequest(offset, len)
        val deadline = System.currentTimeMillis() + waitTimeoutMs
        var raf: RandomAccessFile? = null
        return try {
            while (System.currentTimeMillis() < deadline) {
                if (readyEndProvider(offset) > offset) {
                    if (raf == null) raf = RandomAccessFile(videoFile, "r")
                    raf.seek(offset)
                    val n = raf.read(out, 0, len)
                    if (n > 0) return n
                }
                try { Thread.sleep(200L) } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt(); return -1
                }
            }
            -1 // 等待超时：播放器会收到短响应，可重试
        } catch (e: Exception) {
            -1
        } finally {
            try { raf?.close() } catch (e: Exception) {}
        }
    }

    override fun close() { /* 无持久资源；RAF 由 StreamServer 统一管理 */ }
}
