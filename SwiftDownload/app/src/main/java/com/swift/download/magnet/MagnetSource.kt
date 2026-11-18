package com.swift.download.magnet

/**
 * 磁力源模型（可增删、可改地址、可开关 —— 状态持久化为 JSON 文件）。
 * 内置 5 个规则源：TorrentKitty / 动漫花园 / 磁力猫 / 柠檬BT / SkrBT。
 * 结构化解析失败时自动退化「通用磁力扫描」（GENERIC 规则）。
 */
data class MagnetSource(
    val id: String,
    val name: String,
    val searchUrl: String,          // 含 {kw} 占位
    val parser: String,            // torrentkitty | dmhy | rowtable | generic
    var enabled: Boolean = true
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
            parser = "torrentkitty"
        ),
        MagnetSource(
            id = "dmhy", name = "动漫花园",
            searchUrl = "https://share.dmhy.org/topics/list?keyword={kw}",
            parser = "dmhy"
        ),
        MagnetSource(
            id = "clm5", name = "磁力猫",
            searchUrl = "https://clm5.xyz/s/{kw}",
            parser = "rowtable"
        ),
        MagnetSource(
            id = "lemon", name = "柠檬BT",
            searchUrl = "https://www.lemonbt.xyz/search?keyword={kw}",
            parser = "generic"
        ),
        MagnetSource(
            id = "skrbt", name = "SkrBT",
            searchUrl = "https://www.skrbt.buzz/search?keyword={kw}",
            parser = "generic"
        )
    )

    fun byId(id: String): MagnetSource? = defaults.firstOrNull { it.id == id }
}
