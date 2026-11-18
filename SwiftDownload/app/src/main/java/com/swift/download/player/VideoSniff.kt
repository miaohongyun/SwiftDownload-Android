package com.swift.download.player

/**
 * 视频地址嗅探的纯逻辑（无 Android 依赖，JVM 单测覆盖）。
 * 只做"看起来像媒体地址"的判断，不解析任何平台私有接口——因此不会随平台改版失效。
 */
object VideoSniff {

    /** 去掉 query 与 fragment 后的路径部分 */
    private fun pathOf(url: String): String =
        url.substringBefore('?').substringBefore('#')

    fun isM3u8(url: String): Boolean =
        pathOf(url).endsWith(".m3u8", ignoreCase = true)

    fun isMp4(url: String): Boolean =
        pathOf(url).endsWith(".mp4", ignoreCase = true)

    /** TS 分片本身不单独作为下载候选（它属于某个 m3u8） */
    private fun isTs(url: String): Boolean =
        pathOf(url).endsWith(".ts", ignoreCase = true) ||
            pathOf(url).endsWith(".m4s", ignoreCase = true)

    /** 是否值得作为"可下载视频"候选展示给用户 */
    fun isCandidate(url: String): Boolean =
        (isM3u8(url) || isMp4(url)) && !isTs(url) && url.startsWith("http", true)

    /** 从 URL 推断一个可读的文件名 */
    fun displayName(url: String): String {
        val raw = pathOf(url).substringAfterLast('/')
        val decoded = try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (e: Exception) {
            raw
        }
        return decoded.ifBlank { "视频" }
    }
}
