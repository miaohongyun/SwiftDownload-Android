package com.swift.download.player

import com.swift.download.DownloadTask
import com.swift.download.HttpUtil
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * HLS(m3u8) 下载：master → 变体 → 分片并发拉取 → 按序落盘 → 合并单文件。
 *
 * v2.6.0 升级：
 *  - 分片由串行改为 4 线程并发，单片失败指数退避重试 3 次；
 *  - 支持 AES-128 解密（SAMPLE-AES 等 DRM 在 M3u8 解析阶段已拒绝）；
 *  - 支持 EXT-X-BYTERANGE 区间请求；
 *  - 已完成分片缓存到 .hls/ 且 .m3u8state 记录"已拼装分片数 + 偏移"，
 *    恢复时跳过已拼装分片，实现精确断点。
 *
 * v2.5.2 完整性约束保持不变：分片按序写入、单片失败回滚到分片边界、
 * 合并前关闭句柄、输出长度必须与 .part 一致。
 *
 * [chosenVariant] 非空时直接使用用户选定的变体地址；[referer]/[cookie] 用于防盗链。
 */
class M3u8Job(
    private val url: String,
    private val savePath: String,
    private val throttle: (Int) -> Unit,
    private val listener: Listener,
    private val chosenVariant: String? = null,
    private val referer: String = "",
    private val cookie: String = "",
    private val concurrency: Int = 4
) {
    interface Listener {
        fun onStatus(status: DownloadTask.Status)
        fun onProgress(downloaded: Long)
        fun onFinished()
        fun onError(message: String)

        /** v2.6.0：分片级进度；[totalEstimate] 按已完成分片均长动态估算，-1 表示尚无样本 */
        fun onProgressDetail(
            downloaded: Long, totalEstimate: Long, doneSegments: Int, allSegments: Int
        ) {}
    }

    @Volatile private var cancelled = false
    private var worker: Thread? = null
    private val keyCache = ConcurrentHashMap<String, ByteArray>()

    private fun stateFile(): File = File(savePath + ".m3u8state")
    private fun cacheDir(): File = File(savePath + ".hls")
    private fun segCache(index: Int): File = File(cacheDir(), "seg_$index.bin")
    /** 写入中残留的临时片：恢复时绝不能被当成已完成分片 */
    private fun segTemp(index: Int): File = File(cacheDir(), "seg_$index.bin.tmp")

    private data class ResumeState(val assembled: Int, val bytes: Long)

    /** 兼容 v2.5 的 "N bytes" 状态格式；新格式含义相同，仍为"前 N 个分片已拼装" */
    private fun readState(): ResumeState = try {
        val f = stateFile()
        if (!f.isFile) ResumeState(0, 0L)
        else {
            val parts = f.readText().trim().split(" ")
            ResumeState(
                parts.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                parts.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            )
        }
    } catch (e: Exception) {
        ResumeState(0, 0L)
    }

    private fun writeState(assembled: Int, bytes: Long) {
        try {
            stateFile().writeText("$assembled $bytes")
        } catch (e: Exception) {
            // 状态丢失只会导致重下，不影响内容正确性
        }
    }

    fun start() {
        worker = Thread({ run() }, "m3u8-job").apply { isDaemon = true; start() }
    }

    private fun run() {
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
            val items = playlist.items.ifEmpty { playlist.segments.map { M3u8.Segment(it, 0L) } }
            if (items.isEmpty()) throw IOException("playlist 中没有分片")

            listener.onStatus(DownloadTask.Status.RUNNING)
            val part = File(savePath + ".part")
            part.parentFile?.mkdirs()
            val st = readState()
            var assembled = st.assembled.coerceIn(0, items.size)
            var downloaded = st.bytes

            RandomAccessFile(part, "rw").use { raf ->
                val partLen = if (part.isFile) part.length() else 0L
                if (assembled > 0 && partLen >= downloaded) {
                    // 状态与文件自洽：截掉崩溃时残留的半个分片
                    if (partLen > downloaded) raf.setLength(downloaded)
                } else {
                    assembled = 0
                    downloaded = 0L
                    raf.setLength(0L)
                }

                // 阶段一：并发拉取分片到缓存目录（不触碰 .part，恢复时可直接复用）
                val pool = Executors.newFixedThreadPool(concurrency.coerceAtLeast(1))
                val failures = Collections.synchronizedList(ArrayList<String>())
                val latch = CountDownLatch(items.size)
                try {
                    for ((i, seg) in items.withIndex()) {
                        if (cancelled) break
                        if (i < assembled) { latch.countDown(); continue }
                        pool.execute {
                            try {
                                val target = segCache(i)
                                if (!target.isFile || target.length() == 0L) {
                                    downloadWithRetry(seg, segTemp(i), target)
                                }
                            } catch (e: Exception) {
                                if (!cancelled) failures.add("分片 $i：${e.message ?: "下载失败"}")
                            } finally {
                                latch.countDown()
                            }
                        }
                    }
                    if (!latch.await(30, TimeUnit.MINUTES)) throw IOException("分片下载超时")
                } finally {
                    pool.shutdownNow()
                }
                if (cancelled) return@use
                if (failures.isNotEmpty()) throw IOException(failures.first())

                // 阶段二：按序拼装。单片失败立即回滚到分片边界，状态文件同步作废。
                raf.seek(downloaded)
                for (i in assembled until items.size) {
                    if (cancelled) return@use
                    val cache = segCache(i)
                    if (!cache.isFile) throw IOException("分片 $i 缓存丢失")
                    val plain = try {
                        val raw = cache.readBytes()
                        val key = items[i].key
                        if (key != null && key.method.equals("AES-128", true)) {
                            val k = keyBytes(key.uri)
                            M3u8Crypto.decrypt(
                                raw, k, M3u8Crypto.ivFor(key.iv, items[i].sequence)
                            )
                        } else raw
                    } catch (e: Exception) {
                        throw IOException("分片 $i 解密失败：${e.message ?: "未知错误"}")
                    }
                    if (plain.isEmpty()) throw IOException("分片 $i 内容为空")
                    val segStart = downloaded
                    try {
                        raf.write(plain)
                        downloaded += plain.size
                        assembled = i + 1
                        writeState(assembled, downloaded)
                        throttle(plain.size)
                        listener.onProgress(downloaded)
                        listener.onProgressDetail(downloaded, estimateTotal(items.size, assembled, downloaded), assembled, items.size)
                    } catch (e: Exception) {
                        // 回滚到分片起点，保证下次恢复不会跳过半片
                        try {
                            raf.setLength(segStart)
                            raf.seek(segStart)
                        } catch (ignored: Exception) {}
                        downloaded = segStart
                        writeState(assembled, downloaded)
                        throw e
                    }
                }
            }
            if (cancelled) return

            // 收尾：文件非空 + 长度一致，与 v2.5.2 的校验口径相同
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
            deleteCache()
            listener.onStatus(DownloadTask.Status.COMPLETED)
            listener.onFinished()
        } catch (e: Exception) {
            if (!cancelled) listener.onError(e.message ?: "m3u8 下载失败")
        }
    }

    /** 已完成分片均长 × 总分片数；没有样本时返回 -1 让 UI 走"未知大小"显示 */
    private fun estimateTotal(all: Int, done: Int, bytes: Long): Long =
        if (done <= 0 || all <= 0) -1L else bytes / done * all

    fun cancel() {
        cancelled = true
        worker?.interrupt()
    }

    private fun deleteCache() = deleteCacheDir(savePath)

    companion object {
        /**
         * v2.6.0：递归清理 HLS 分片缓存目录。删除任务/刷新链接/重新下载都要调用 ——
         * 否则长视频被删后 .hls/ 里的几百 MB 分片会长期残留成为孤儿。
         * 纯文件操作，JVM 单测可直接覆盖。
         */
        fun deleteCacheDir(savePath: String) {
            val dir = File(savePath + ".hls")
            if (!dir.isDirectory) return
            try {
                dir.listFiles()?.forEach { child ->
                    if (child.isDirectory) child.listFiles()?.forEach { it.delete() }
                    child.delete()
                }
                dir.delete()
            } catch (e: Exception) {
                // 清理失败不影响主流程，最多留下可被下次清理的残留
            }
        }
    }

    /**
     * 单个分片：指数退避重试 3 次（100/200/400ms）。
     * 先写 .tmp、成功后原子改名为正式缓存片 —— 被强杀时只留 .tmp，
     * 下次恢复会重新下载，绝不会把半片当成完整片拼接。
     */
    private fun downloadWithRetry(seg: M3u8.Segment, temp: File, target: File) {
        var last: Exception? = null
        target.parentFile?.mkdirs()
        for (attempt in 0..3) {
            try {
                downloadSegment(seg, temp)
                if (temp.length() <= 0L) throw IOException("分片内容为空")
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                return
            } catch (e: Exception) {
                if (cancelled) return
                last = e
                if (temp.exists()) temp.delete()
                if (target.exists()) target.delete()
                // v2.7 Stage1.2：硬错误（404/403/完整性/解密）不重试，避免对失效分片空等
                if (com.swift.download.settings.RetryPolicy.isFatal(e)) throw e
                if (attempt < 3) {
                    try { Thread.sleep(100L shl attempt) } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt(); return
                    }
                }
            }
        }
        throw last ?: IOException("分片下载失败")
    }

    /** 按 EXT-X-BYTERANGE 请求区间；服务器忽略 Range 时本地截取 */
    private fun downloadSegment(seg: M3u8.Segment, target: File) {
        val conn = open(seg.url)
        try {
            val range = seg.byteRange
            if (range != null && range.length > 0L) {
                val from = range.offset ?: 0L
                conn.setRequestProperty("Range", "bytes=$from-${from + range.length - 1}")
            }
            // v2.6.0：非 2xx 的 HTML 错误页绝不能拼进 .ts
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code：分片下载失败")

            val skip = if (range != null && code == 200) (range.offset ?: 0L) else 0L
            val limit = if (range != null && code == 206) range.length
                else if (range != null) range.length else -1L

            if (range != null && code == 206) {
                val from = range.offset ?: 0L
                val cr = conn.getHeaderField("Content-Range")
                    ?: throw IOException("HTTP 206 缺少 Content-Range")
                val m = Regex("bytes\\s+(\\d+)-").find(cr)
                    ?: throw IOException("无法解析 Content-Range: $cr")
                if (m.groupValues[1].toLong() != from) {
                    throw IOException("分片 Content-Range 起点不符（期望 $from）")
                }
            }

            val out = target.outputStream()
            try {
                val input = conn.inputStream
                val buf = ByteArray(65536)
                var left = limit
                var skipped = 0L
                while (!cancelled) {
                    if (left == 0L) break
                    val want = if (left > 0) minOf(buf.size.toLong(), left).toInt() else buf.size
                    val n = input.read(buf, 0, want)
                    if (n < 0) break
                    if (skipped < skip) {
                        val drop = minOf(n.toLong(), skip - skipped).toInt()
                        skipped += drop
                        if (drop >= n) continue
                        out.write(buf, drop, n - drop)
                        if (left > 0) left -= (n - drop).toLong()
                    } else {
                        out.write(buf, 0, n)
                        if (left > 0) left -= n.toLong()
                    }
                }
                // left < 0 表示未指定 BYTERANGE、读到 EOF 即完整
                if (left > 0L) throw IOException("分片数据不完整：还缺 $left 字节")
            } finally {
                try { out.close() } catch (e: Exception) {}
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchText(target: String): String {
        val conn = open(target)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code：无法读取 m3u8 playlist")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** AES-128 密钥按 URI 缓存，同一 playlist 通常共用一个 key */
    private fun keyBytes(uri: String): ByteArray {
        keyCache[uri]?.let { return it }
        val conn = open(uri)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code：无法读取 AES-128 密钥")
            val bytes = conn.inputStream.use { it.readBytes() }
            val key = M3u8Crypto.requireKey(bytes)
            keyCache[uri] = key
            return key
        } finally {
            conn.disconnect()
        }
    }

    /** 统一走 HttpUtil（重定向/UA/Cookie），与 HTTP 引擎同一套网络行为 */
    private fun open(target: String): HttpURLConnection {
        val extra = if (cookie.isNotBlank()) mapOf("Cookie" to cookie) else emptyMap()
        return HttpUtil.open(target, referer, extraHeaders = extra)
    }
}
