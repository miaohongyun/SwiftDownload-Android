package com.swift.download.magnet

/**
 * 磁力源模型（可增删、可改地址、可开关 —— 状态持久化为 JSON 文件）。
 * v2.9.0：清理长期失效的 磁力猫/柠檬BT/SkrBT，只保留境外需代理源；
 * 国内 5G 免代理场景请用磁力搜页的「影视站直搜」模式。
 * 结构化解析失败时自动退化「通用磁力扫描」（GENERIC 规则）。
 */
data class MagnetSource(
    val id: String,
    val name: String,
    val searchUrl: String,          // 含 {kw} 占位
    val parser: String,            // torrentkitty | dmhy | rowtable | generic
    var enabled: Boolean = true,
    /** v2.9.0：境外源需 HTTP 代理才能在国内 5G 直连，UI 上必须显著标注 */
    val proxyRequired: Boolean = false
) {
    fun urlFor(keyword: String): String =
        searchUrl.replace("{kw}", keyword.urlEnc())

    private fun String.urlEnc(): String = java.net.URLEncoder.encode(this, "UTF-8")
}

object MagnetSources {

    val defaults: List<MagnetSource> = listOf(
        MagnetSource(
            id = "torrentkitty", name = "TorrentKitty",
            searchUrl = "https://www.torrentkitty.tv/search/{kw}",
            parser = "torrentkitty",
            proxyRequired = true
        ),
        MagnetSource(
            id = "dmhy", name = "动漫花园",
            searchUrl = "https://share.dmhy.org/topics/list?keyword={kw}",
            parser = "dmhy",
            proxyRequired = true
        )
    )
}
