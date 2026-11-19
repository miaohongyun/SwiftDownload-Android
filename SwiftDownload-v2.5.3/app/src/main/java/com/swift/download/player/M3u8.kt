package com.swift.download.player

import java.net.URL

/**
 * HLS(m3u8) playlist 解析（纯 Kotlin，JVM 单测覆盖）。
 * 支持 master playlist 选最高码率变体；media playlist 提取分片地址。
 *
 * v2.6.0：新增 AES-128 密钥、IV 与 EXT-X-BYTERANGE 解析。
 * 仍不支持 fMP4（EXT-X-MAP）与 SAMPLE-AES 等 DRM —— 这两类会明确拒绝，
 * 而不是下出一个"已完成但播不了"的文件。
 */
object M3u8 {

    data class Variant(
        val bandwidth: Long,
        val url: String,
        val width: Int = 0,
        val height: Int = 0
    ) {
        /** 给用户看的一行标签，例如「1080p · 4.2 Mbps」 */
        val label: String get() = buildString {
            if (height > 0) append("${height}p") else append("码率档")
            append(" · ").append("%.1f Mbps".format(bandwidth / 1_000_000.0))
        }
    }

    /** #EXT-X-KEY：[iv] 保留原始字面量（常见 0x 前缀），由 M3u8Crypto 统一解析 */
    data class KeyInfo(
        val method: String,
        val uri: String,
        val iv: String? = null
    )

    /** #EXT-X-BYTERANGE:<len>[@<offset>]；offset 省略时表示紧接上一个分片 */
    data class ByteRange(val length: Long, val offset: Long? = null)

    /** 单个分片及其生效的加密/字节区间上下文 */
    data class Segment(
        val url: String,
        val sequence: Long,
        val key: KeyInfo? = null,
        val byteRange: ByteRange? = null
    )

    data class Playlist(
        val isMaster: Boolean,
        val variants: List<Variant>,
        /** 兼容 v2.5 的调用方：只含分片地址 */
        val segments: List<String>,
        /** v2.6.0：带密钥/区间上下文的分片明细 */
        val items: List<Segment> = emptyList(),
        /** 非空表示该 playlist 使用了当前实现无法正确拼接的特性，应拒绝下载而不是产出坏文件 */
        val unsupported: String? = null,
        val mediaSequence: Long = 0L
    )

    /** 解析 playlist 文本，[base] 为 m3u8 自身的地址，用于解析相对路径 */
    fun parse(base: String, text: String): Playlist {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val variants = ArrayList<Variant>()
        val items = ArrayList<Segment>()
        var pendingBandwidth = -1L
        var pendingWidth = 0
        var pendingHeight = 0
        var currentKey: KeyInfo? = null
        var pendingRange: ByteRange? = null
        var sequence = 0L
        var nextOffset = 0L

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) -> {
                    pendingBandwidth = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                    val res = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE).find(line)
                    pendingWidth = res?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    pendingHeight = res?.groupValues?.get(2)?.toIntOrNull() ?: 0
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) -> {
                    sequence = Regex("""(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-KEY", ignoreCase = true) -> {
                    val method = Regex("""METHOD=([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.get(1) ?: "NONE"
                    currentKey = if (method.equals("NONE", true)) null else {
                        val rawUri = Regex("URI=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                            .find(line)?.groupValues?.get(1).orEmpty()
                        val iv = Regex("""IV=([^,\s]+)""", RegexOption.IGNORE_CASE)
                            .find(line)?.groupValues?.get(1)
                        KeyInfo(method.uppercase(), resolve(base, rawUri), iv)
                    }
                }
                line.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) -> {
                    val body = line.substringAfter(':', "").trim()
                    val len = body.substringBefore('@').trim().toLongOrNull() ?: 0L
                    val at = body.indexOf('@')
                    pendingRange = if (at >= 0) {
                        ByteRange(len, body.substring(at + 1).trim().toLongOrNull())
                    } else {
                        ByteRange(len, null) // 由上一个分片的结束位置推导
                    }
                }
                line.startsWith("#") -> { /* 其余标签忽略 */ }
                pendingBandwidth >= 0 -> {
                    variants.add(Variant(pendingBandwidth, resolve(base, line), pendingWidth, pendingHeight))
                    pendingBandwidth = -1L
                    pendingWidth = 0
                    pendingHeight = 0
                }
                else -> {
                    var range = pendingRange
                    if (range != null && range.offset == null) range = range.copy(offset = nextOffset)
                    items.add(Segment(resolve(base, line), sequence + items.size, currentKey, range))
                    if (range != null) nextOffset = (range.offset ?: 0L) + range.length
                    pendingRange = null
                }
            }
        }
        val master = variants.isNotEmpty()
        return Playlist(
            master,
            variants.sortedByDescending { it.bandwidth },
            items.map { it.url },
            items,
            detectUnsupported(lines),
            sequence
        )
    }

    /**
     * v2.5.2：识别当前实现处理不了的 playlist；v2.6.0 起 AES-128 与 BYTERANGE 已支持，
     * 仅 fMP4 与 SAMPLE-AES 等 DRM 仍需拒绝。
     */
    fun detectUnsupported(lines: List<String>): String? {
        var unsupportedMethod: String? = null
        var hasMap = false
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-KEY", ignoreCase = true) -> {
                    val method = Regex("""METHOD=([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.get(1)
                    if (method != null && !method.equals("NONE", true) &&
                        !method.equals("AES-128", true)
                    ) unsupportedMethod = method.uppercase()
                }
                line.startsWith("#EXT-X-MAP", ignoreCase = true) -> hasMap = true
            }
        }
        return when {
            unsupportedMethod != null -> "分片已加密（$unsupportedMethod），当前版本仅支持 AES-128"
            hasMap -> "fMP4 流需要 EXT-X-MAP 初始化段，当前版本无法正确合并"
            else -> null
        }
    }

    /** master → 选中的变体地址；media → 自身首个分片（调用方一般用 segments） */
    fun pickBestUrl(playlist: Playlist): String? =
        if (playlist.isMaster) playlist.variants.firstOrNull()?.url else null

    fun resolve(base: String, ref: String): String = try {
        if (ref.startsWith("http://", true) || ref.startsWith("https://", true)) ref
        else URL(URL(base), ref).toString()
    } catch (e: Exception) {
        ref
    }

    /** URL 是否看起来是 m3u8（忽略 query） */
    fun looksLikeM3u8(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)
}
