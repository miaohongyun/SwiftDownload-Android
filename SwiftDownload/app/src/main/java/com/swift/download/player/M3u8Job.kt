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
                if (playlist.isMaster) {
                    val variant = M3u8.pickBestUrl(playlist)
                        ?: throw IOException("master playlist 中没有可用变体")
                    playlist = M3u8.parse(variant, fetchText(variant))
                }
                if (playlist.segments.isEmpty()) throw IOException("playlist 中没有分片")

                listener.onStatus(DownloadTask.Status.RUNNING)
                val part = File(savePath + ".part")
                part.parentFile?.mkdirs()
                // 断点续传：跳过已完成的分片。
                // 已落盘字节数直接取现有 .part 文件长度（权威值，不依赖 HEAD 探测），
                // 避免分片大小探测失败导致字节数错乱。
                val st = readState()
                val resumeSegments = st.doneSegments.coerceIn(0, playlist.segments.size)
                var downloaded = 0L
                RandomAccessFile(part, "rw").use { raf ->
                    if (resumeSegments > 0 && part.isFile && part.length() > 0) {
                        // 续传：保留已有内容，定位到文件末尾继续追加
                        downloaded = part.length()
                        raf.seek(downloaded)
                    } else {
                        raf.setLength(0)
                        downloaded = 0L
                    }
                    val buf = ByteArray(65536)
                    for ((idx, seg) in playlist.segments.withIndex()) {
                        if (cancelled) return@use
                        if (idx < resumeSegments) {
                            // 已完成的分片：直接跳过（字节数已包含在 part.length() 中）
                            continue
                        }
                        val conn = open(seg)
                        try {
                            conn.inputStream.use { input ->
                                while (!cancelled) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    raf.write(buf, 0, n)
                                    downloaded += n
                                    throttle(n)
                                    listener.onProgress(downloaded)
                                }
                            }
                        } finally {
                            conn.disconnect()
                        }
                        writeState(idx + 1, downloaded)
                    }
                }
                if (cancelled) return@Thread
                val out = File(savePath)
                out.parentFile?.mkdirs()
                if (out.exists()) out.delete()
                if (!part.renameTo(out)) {
                    part.copyTo(out, overwrite = true)
                    part.delete()
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
