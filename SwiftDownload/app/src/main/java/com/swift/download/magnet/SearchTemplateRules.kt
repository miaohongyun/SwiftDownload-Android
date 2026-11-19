package com.swift.download.magnet

/**
 * v2.9.1：「把当前页设为搜索源」纯逻辑（零 Android 依赖，JVM 单测覆盖）。
 *
 * 用户在影视站搜索一次后，把当前 URL 中刚搜过的关键词替换为 {kw}，
 * 得到可复用的站内搜索模板。兼容：查询参数值、路径末段、URL 编码与明文。
 */
object SearchTemplateRules {

    data class TemplateResult(val template: String?, val replaced: Int, val error: String?) {
        val ok: Boolean get() = template != null
    }

    /**
     * @param url 当前网页完整 URL
     * @param keyword 用户刚才输入的关键词（明文）
     */
    fun build(url: String, keyword: String): TemplateResult {
        val kw = keyword.trim()
        if (kw.isEmpty()) return TemplateResult(null, 0, "请先输入刚才搜索的关键词")
        if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return TemplateResult(null, 0, "当前页不是有效的 http(s) 网址")
        }
        if (!url.contains("{kw}") && !url.contains(kw) &&
            !url.contains(java.net.URLEncoder.encode(kw, "UTF-8"), true)
        ) {
            return TemplateResult(null, 0, "当前网址中未找到该关键词，请确认输入的是刚才搜索的词")
        }
        if (url.contains("{kw}")) return TemplateResult(url, 0, null)

        var count = 0
        var out = url
        // 1) URL 编码形式（大小写不敏感）
        val encoded = java.net.URLEncoder.encode(kw, "UTF-8")
        out = out.replace(Regex(java.util.regex.Pattern.quote(encoded), RegexOption.IGNORE_CASE)) {
            count++; "{kw}"
        }
        // 2) 明文形式（避免把已替换的占位符再次命中）
        if (count == 0) {
            out = out.replace(Regex(java.util.regex.Pattern.quote(kw))) { count++; "{kw}" }
        }
        if (count == 0) return TemplateResult(null, 0, "未能定位关键词位置，请改用「添加新磁力源」手动填写")
        val err = MagnetSourceRules.validate("临时源", out, "generic")
        if (err != null) return TemplateResult(null, count, err)
        return TemplateResult(out, count, null)
    }
}
