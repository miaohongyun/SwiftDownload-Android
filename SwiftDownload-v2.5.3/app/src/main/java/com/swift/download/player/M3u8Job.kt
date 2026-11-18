package com.swift.download.player

import com.swift.download.DownloadTask
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * HLS(m3u8) 顺序下载：master → 变体 → 逐个分片写入同一个 .part → 合并为单个文件。
 * 分片串行保证拼接顺序；限速钩子 [throttle] 与取消标志由服务层提供。
 *
 * [chosenVariant] 非空时直接使用用户选定的变体地址（跳过自动选最高码率）；
 * [referer] 为来源页地址，随请求头发出以通过常见防盗链校验。
 */
class M3u8Job(
    private val url: String,
    private val savePath: String,
    private val throttle: (Int) -> Unit,
    private val listener: Listener,
    private val chosenVariant: String? = null,
    private val referer: String = ""
) {
    interface Listener {
        fun onStatus(status: DownloadTask.Status)
        fun onProgress(downloaded: Long)
        fun onFinished()
        fun onError(message: String)
    }

    @Volatile private var cancelled = false
    private var worker: Thread? = null

    /**
     * v2.5：断点续传状态（已完成分片数 + 已落盘字节）。
     * 原实现每次 start 都 setLength(0) 清空文件，暂停后继续会从头下，
     * 长视频等于白下。改为记录已完成分片数，恢复时跳过这些分片。
     */
    private fun stateFile(): File = File(savePath + ".m3u8state")

    private data class ResumeState(val doneSegments: Int, val bytes: Long)

    private fun readState(): ResumeState {
        return try {
            val f = stateFile()
            if (!f.isFile) return ResumeState(0, 0)
            val parts = f.readText().trim().split(" ")
            ResumeState(parts.getOrNull(0)?.toIntOrNull() ?: 0, parts.getOrNull(1)?.toLongOrNull() ?: 0)
        } catch (e: Exception) {
            ResumeState(0, 0)
        }
    }

    private fun writeState(doneSegments: Int, bytes: Long) {
        try {
            stateFile().writeText("$doneSegments $bytes")
        } catch (e: Exception) {
            // 忽略：状态丢失只会导致重下，不影响正确性
        }
    }

    fun start() {
        worker = Thread({
            try {
                listener.onStatus(DownloadTask.Status.PROBING)
                val entry = chosenVariant?.takeIf { it.isNotBlank() } ?: url
                var playlist = M3u8.parse(entry, fetchText(entry))
                playlist.unsupported?.let { throw IOException(it) }
                if (playlist.isMaster) {
                    val variant = M3u8.pickBestUrl(playlist)
                        ?: throw IOException("master playlist 中没有可用变体")
                    playlist = M3u8.parse(variant, fetchText(variant))
                    playlist.unsupported?.let { throw IOException(it) }
                }
                if (playlist.segments.isEmpty()) throw IOException("playlist 中没有分片")

                listener.onStatus(DownloadTask.Status.RUNNING)
                val part = File(savePath + ".part")
                part.parentFile?.mkdirs()
                // 断点续传：状态文件记录"已完成分片数 + 对应字节偏移"，两者必须自洽。
                // v2.5.2：恢复时把 .part 截断回记录的偏移，避免崩溃/被杀后残留的半段数据
                // 被当成完整分片保留下来，拼出"半个分片 + 完整分片"的坏文件。
                val st = readState()
                var resumeSegments = st.doneSegments.coerceIn(0, playlist.segments.size)
                var downloaded = 0L
                RandomAccessFile(part, "rw").use { raf ->
                    val partLen = if (part.isFile) part.length() else 0L
                    if (resumeSegments > 0 && st.bytes > 0 && partLen >= st.bytes) {
                        if (partLen > st.bytes) raf.setLength(st.bytes)
                        downloaded = st.bytes
                        raf.seek(downloaded)
                    } else {
                        // 状态与文件不一致（或从未开始）：整段重下，保证拼接连续
                        resumeSegments = 0
                        raf.setLength(0)
                    }
                    val buf = ByteArray(65536)
                    for ((idx, seg) in playlist.segments.withIndex()) {
                        if (cancelled) return@use
                        if (idx < resumeSegments) {
                            // 已完成的分片：直接跳过（字节数已包含在 .part 中）
                            continue
                        }
                        val segStartBytes = downloaded
                        var segComplete = false
                        val conn = open(seg)
                        try {
                            conn.inputStream.use { input ->
                                while (!cancelled) {
                                    val n = input.read(buf)
                                    if (n < 0) { segComplete = true; break }
                                    raf.write(buf, 0, n)
                                    downloaded += n
                                    throttle(n)
                                    listener.onProgress(downloaded)
                                }
                            }
                        } finally {
                            conn.disconnect()
                        }
                        if (!segComplete) {
                            // 分片只下了一半：回滚到分片边界，否则恢复时会跳过残缺分片
                            try {
                                raf.setLength(segStartBytes)
                                raf.seek(segStartBytes)
                            } catch (e: Exception) {
                                // 回滚失败 → 状态文件作废，下次从头开始
                                stateFile().delete()
                            }
                            downloaded = segStartBytes
                            return@use
                        }
                        writeState(idx + 1, downloaded)
                    }
                }
                if (cancelled) return@Thread
                // 收尾校验：文件必须非空，合并后长度必须与 .part 一致
                if (!part.isFile || part.length() <= 0L) throw IOException("m3u8 下载结果为空")
                val expectedLen = part.length()
                val out = File(savePath)
                out.parentFile?.mkdirs()
                if (out.exists() && !out.delete()) throw IOException("无法覆盖已有文件：${out.name}")
                if (!part.renameTo(out)) {
                    try {
                        part.copyTo(out, overwrite = true)
                        part.delete()
                    } catch (e: Exception) {
                        throw IOException("合并 m3u8 分片失败：${e.message}")
                    }
                }
                if (!out.isFile || out.length() != expectedLen) {
                    throw IOException("m3u8 输出文件长度异常（${out.length()} / $expectedLen）")
                }
                stateFile().delete()
                listener.onStatus(DownloadTask.Status.COMPLETED)
                listener.onFinished()
            } catch (e: Exception) {
                if (!cancelled) listener.onError(e.message ?: "m3u8 下载失败")
            }
        }, "m3u8-job").apply { isDaemon = true; start() }
    }

    fun cancel() {
        cancelled = true
        worker?.interrupt()
    }

    private fun fetchText(target: String): String {
        val conn = open(target)
        try {
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(target: String): HttpURLConnection {
        val c = URL(target).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        if (referer.isNotBlank()) c.setRequestProperty("Referer", referer)
        return c
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    }
}
