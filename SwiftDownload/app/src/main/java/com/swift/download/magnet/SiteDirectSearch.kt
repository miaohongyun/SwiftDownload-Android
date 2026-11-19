package com.swift.download.magnet

/**
 * v2.9.0：影视站直搜（国内 5G 免代理）纯逻辑。
 * 与「全网磁力索引」不同，这里不访问境外磁力索引站，而是把关键词拼进
 * 国内影视站的站内搜索 URL，交给内置浏览器打开；用户在详情页点「抓资源」
 * 即可拿到 magnet/.torrent，绕开已失效/需代理的磁力站。
 *
 * 纯 Kotlin、零 Android 依赖，供 JVM 单测覆盖。
 */
object SiteDirectSearch {

    data class Site(val name: String, val searchUrl: String) {
        /** 把关键词填进 {kw}；站点模板非法时原样返回 */
        fun urlFor(keyword: String): String =
            if (searchUrl.contains("{kw}")) {
                searchUrl.replace("{kw}", java.net.URLEncoder.encode(keyword, "UTF-8"))
            } else searchUrl
    }

    /** 内置影视站搜索模板（用户可在设置里追加自己的站点） */
    val BUILTIN: List<Site> = listOf(
        Site("高清族", "https://hdzu.cc/?s={kw}"),
        Site("电影港", "https://www.dygang.cc/?s={kw}"),
        Site("SeedHub", "https://www.seedhub.cc/search/{kw}")
    )

    /** 解析用户自定义模板行：「名称=https://site/?s={kw}」 */
    fun parseTemplate(line: String): Site? {
        val s = line.trim()
        val i = s.indexOf('=')
        if (i <= 0 || i >= s.length - 1) return null
        val name = s.substring(0, i).trim()
        val url = s.substring(i + 1).trim()
        if (name.isEmpty()) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        if (!url.contains("{kw}")) return null
        return Site(name, url)
    }

    fun encodeCustom(sites: List<Site>): String = sites.joinToString("\n") { "${it.name}=${it.searchUrl}" }

    fun decodeCustom(raw: String): List<Site> =
        raw.lineSequence().mapNotNull(::parseTemplate).distinctBy { it.name }.toList()

    /** 内置 + 自定义合并，同名自定义覆盖内置（用户可修正失效模板） */
    fun merged(customRaw: String): List<Site> {
        val custom = decodeCustom(customRaw)
        val names = custom.map { it.name }.toSet()
        return BUILTIN.filterNot { it.name in names } + custom
    }

    /** 超时提示：明确告诉用户有免代理替代方案 */
    fun unreachableHint(httpProxyEnabled: Boolean): String = if (httpProxyEnabled) {
        "全部磁力源连接失败。请检查 HTTP 代理地址是否可用，或切到「影视站直搜」用国内站点找资源。"
    } else {
        "检测到未开启代理，部分境外磁力站无法直连。可使用「影视站直搜（免代理）」，或在浏览器打开影视站后点「抓资源」。"
    }
}
