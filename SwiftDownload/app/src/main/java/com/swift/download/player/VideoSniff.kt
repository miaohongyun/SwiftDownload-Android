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

    /** v2.6.0：除 mp4/m3u8 外还支持 mkv/flv/mov/webm 的直接嗅探 */
    private val OTHER_VIDEO_EXT = setOf("mkv", "flv", "mov", "webm")
    fun isOtherVideo(url: String): Boolean =
        OTHER_VIDEO_EXT.contains(pathOf(url).substringAfterLast('.', "").lowercase())

    /** TS 分片本身不单独作为下载候选（它属于某个 m3u8） */
    private fun isTs(url: String): Boolean =
        pathOf(url).endsWith(".ts", ignoreCase = true) ||
            pathOf(url).endsWith(".m4s", ignoreCase = true)

    /** 是否值得作为"可下载视频"候选展示给用户 */
    fun isCandidate(url: String): Boolean =
        (isM3u8(url) || isMp4(url) || isOtherVideo(url)) &&
            !isTs(url) && url.startsWith("http", true)

    /**
     * v2.6.0：从整段分享文案里提取链接（直链 + 磁力），用于剪贴板智能识别。
     * 只取明确的 http(s):// 与 magnet:?xt=urn:btih:，不误伤中文文案与普通域名。
     */
    fun extractLinks(text: String): List<String> {
        val out = LinkedHashMap<String, Boolean>()
        val http = Regex("""https?://[^\s<>"'，。；）)\]]+""", RegexOption.IGNORE_CASE)
        for (m in http.findAll(text)) out.putIfAbsent(m.value.trimEnd('.', ',', ';'), false)
        val magnet = Regex("""magnet:\?xt=urn:btih:[^\s<>"'，。；）)\]]+""", RegexOption.IGNORE_CASE)
        for (m in magnet.findAll(text)) out.putIfAbsent(m.value, true)
        return out.keys.toList()
    }

    fun isMagnet(text: String): Boolean =
        text.startsWith("magnet:?xt=urn:btih:", ignoreCase = true)

    /**
     * v2.7 Stage3.3：链接分类（批量建任务用）。
     * 只识别 http(s) 直链与 magnet 磁力，其它一律 NONE；供纯 JVM 单测覆盖。
     */
    enum class LinkKind { HTTP, MAGNET, NONE }
    fun kindOf(url: String): LinkKind = when {
        url.startsWith("magnet:", ignoreCase = true) -> LinkKind.MAGNET
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> LinkKind.HTTP
        else -> LinkKind.NONE
    }

    /** v2.7 Stage3.3：批量输入是否应切换为"识别多链接"模式（≥2 条明确链接） */
    fun shouldBatch(text: String): Boolean = extractLinks(text).size >= 2

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

    /* ---------------- v2.8 模块四 4.1：DOM 批量资源提取 ---------------- */

    /** 一键抓取到的资源项 */
    data class PageResource(
        val uri: String,        // 可直接下载：magnet: / http(s) .torrent / ed2k:
        val kind: Kind,
        val title: String,      // 结合附近标题
        val hash: String = ""   // 40 位 infohash（hash 类资源）
    ) {
        enum class Kind { MAGNET, TORRENT, ED2K }
    }

    private val hash40Regex = Regex("""\b[0-9a-fA-F]{40}\b""")
    private val ed2kRegex = Regex("""ed2k://\|file\|[^|\s]+\|\d+\|[0-9a-fA-F]{32}\|/?""", RegexOption.IGNORE_CASE)

    /**
     * 从页面可见文本/HTML 批量提取磁力资源。
     * 覆盖：完整 magnet 链接、.torrent 直链、ed2k、40 位裸 infohash；
     * 按 uri 去重，标题优先取链接<a>文本或 dn= 参数，其次取附近文本。
     */
    fun extractPageResources(rawHtml: String): List<PageResource> {
        // 去 script/style 避免干扰，但保留 <a> 文本用于标题
        val html = rawHtml
            .replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
        val out = LinkedHashMap<String, PageResource>()

        // 1. 完整 magnet 链接（含 btih 后的 dn= 作标题）
        val magnetRegex = Regex("""magnet:\?xt=urn:btih:[0-9a-zA-Z]{32,40}[^\s<>"']*""", RegexOption.IGNORE_CASE)
        for (m in magnetRegex.findAll(html)) {
            val uri = m.value.trimEnd('&', '"', '\'')
            val hash = Regex("""btih:([0-9a-zA-Z]{32,40})""", RegexOption.IGNORE_CASE)
                .find(uri)?.groupValues?.get(1)?.lowercase().orEmpty()
            val dn = Regex("""[?&]dn=([^&"'\s<]+)""", RegexOption.IGNORE_CASE)
                .find(uri)?.groupValues?.get(1)?.let { decode(it) }
            out.putIfAbsent(uri, PageResource(uri, PageResource.Kind.MAGNET,
                dn?.ifBlank { null } ?: nearbyTitle(html, m.range.first), hash))
        }

        // 2. .torrent 直链（http(s)）
        val torrentRegex = Regex("""https?://[^\s<>"']+\.torrent(?:\?[^\s<>"']*)?""", RegexOption.IGNORE_CASE)
        for (m in torrentRegex.findAll(html)) {
            val uri = m.value
            out.putIfAbsent(uri, PageResource(uri, PageResource.Kind.TORRENT, nearbyTitle(html, m.range.first)))
        }

        // 3. ed2k
        for (m in ed2kRegex.findAll(html)) {
            val uri = m.value
            val name = Regex("""ed2k://\|file\|([^|\s]+)""").find(uri)?.groupValues?.get(1)?.let { decode(it) }
            out.putIfAbsent(uri, PageResource(uri, PageResource.Kind.ED2K, name ?: "ed2k 资源"))
        }

        // 4. 40 位裸 hash → 组装 magnet（排除已被完整 magnet 覆盖的）
        for (m in hash40Regex.findAll(html)) {
            val hash = m.value.lowercase()
            val uri = "magnet:?xt=urn:btih:$hash"
            if (out.containsKey(uri)) continue
            // 若该 hash 出现在已记录的 magnet 里，跳过
            if (out.values.any { it.hash.equals(hash, ignoreCase = true) }) continue
            out.putIfAbsent(uri, PageResource(uri, PageResource.Kind.MAGNET, nearbyTitle(html, m.range.first), hash))
        }
        return out.values.toList()
    }

    private fun decode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) { s }

    /** 取链接附近可见文本作标题（去标签） */
    private fun nearbyTitle(html: String, pos: Int): String {
        val from = maxOf(0, pos - 120)
        val to = minOf(html.length, pos + 160)
        val window = html.substring(from, to)
        val texts = Regex(""">([^<>]{2,60})<""").findAll(window)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() && !it.startsWith("http") && !it.startsWith("magnet") }
        return texts.firstOrNull() ?: "未命名资源"
    }
}
