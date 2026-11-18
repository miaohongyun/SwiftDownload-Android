package com.swift.download.player

import java.net.URL

/**
 * HLS(m3u8)  playlist 解析（纯 Kotlin，JVM 单测覆盖）。
 * 支持 master playlist 选最高码率变体；media playlist 提取分片地址。
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

    data class Playlist(val isMaster: Boolean, val variants: List<Variant>, val segments: List<String>)

    /** 解析 playlist 文本，[base] 为 m3u8 自身的地址，用于解析相对路径 */
    fun parse(base: String, text: String): Playlist {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val variants = ArrayList<Variant>()
        val segments = ArrayList<String>()
        var pendingBandwidth = -1L
        var pendingWidth = 0
        var pendingHeight = 0

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    pendingBandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                    val res = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE).find(line)
                    pendingWidth = res?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    pendingHeight = res?.groupValues?.get(2)?.toIntOrNull() ?: 0
                }
                line.startsWith("#") -> { /* 其余标签忽略 */ }
                pendingBandwidth >= 0 -> {
                    variants.add(Variant(pendingBandwidth, resolve(base, line), pendingWidth, pendingHeight))
                    pendingBandwidth = -1L
                    pendingWidth = 0
                    pendingHeight = 0
                }
                else -> segments.add(resolve(base, line))
            }
        }
        val master = variants.isNotEmpty()
        return Playlist(master, variants.sortedByDescending { it.bandwidth }, segments)
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
