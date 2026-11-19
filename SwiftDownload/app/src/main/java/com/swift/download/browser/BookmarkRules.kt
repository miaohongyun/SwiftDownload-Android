package com.swift.download.browser

/**
 * v2.9.1：默认书签与迁移纯逻辑（零 Android 依赖，JVM 单测覆盖）。
 * 用 Pair(name, url) 表达，避免与 QuickSites.Site 的 Android 层类型耦合。
 */
object BookmarkRules {

    /** 原有默认书签（v2.9.0 已修正域名） */
    val LEGACY_DEFAULTS: List<Pair<String, String>> = listOf(
        "必应" to "https://cn.bing.com",
        "电影港" to "https://www.dygang.cc",
        "高清族" to "https://hdzu.cc",
        "磁力搜" to "https://cn.bing.com/search?q=magnet"
    )

    /** v2.9.1：3 个合法公有领域 / 开源资源站 */
    val PUBLIC_DOMAIN_DEFAULTS: List<Pair<String, String>> = listOf(
        "Archive影视" to "https://archive.org/details/feature_films",
        "公有领域BT" to "https://www.publicdomaintorrents.info",
        "合法BT导航" to "https://github.com/torrust/awesome-legal-torrent-sources"
    )

    /** 首次安装 / 恢复默认时的完整默认列表 */
    val DEFAULTS: List<Pair<String, String>> = LEGACY_DEFAULTS + PUBLIC_DOMAIN_DEFAULTS

    /**
     * 合并缺失的默认书签：用户已有项（含改名/改址）一律原样保留，
     * 只在 URL 完全不存在时追加新默认项。满足"仅追加不覆盖用户自定义项"。
     */
    fun mergeMissingDefaults(
        loaded: List<Pair<String, String>>,
        defaults: List<Pair<String, String>> = DEFAULTS
    ): List<Pair<String, String>> {
        val out = loaded.toMutableList()
        val urls = out.map { it.second.trim() }.toHashSet()
        for ((name, url) in defaults) {
            if (url.isNotBlank() && url !in urls) {
                out.add(name to url)
                urls.add(url)
            }
        }
        return out
    }

    /** 老版本失效域名的就地纠正（仅匹配已知默认名 + 已知旧域） */
    fun normalizeDefaultDomain(name: String, url: String): Pair<String, String> = when (name) {
        "必应" -> if (url.contains("bing.com")) "必应" to "https://cn.bing.com" else name to url
        "电影港" -> if (url.contains("dygang")) "电影港" to "https://www.dygang.cc" else name to url
        "高清族" -> if (url.contains("hdzu") || url.contains("gaoqingzu")) "高清族" to "https://hdzu.cc" else name to url
        else -> name to url
    }
}
