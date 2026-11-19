package com.swift.download.magnet

/**
 * v2.8 模块四：磁力源纯逻辑（零 Android 依赖，覆盖单测）。
 *  - 自定义源的地址校验（必须 http(s) 且含 {kw}）
 *  - 自定义源 id 生成
 *  - 自定义源列表的纯 Kotlin JSON 编解码（不依赖 org.json，规避 JVM 单测 stub）
 *  - 搜索诊断状态文案（源明细展示）
 */
object MagnetSourceRules {

    /** 校验新增源输入；返回 null 表示合法，否则返回给用户看的原因 */
    fun validate(name: String, searchUrl: String, parser: String): String? {
        if (name.isBlank()) return "名称不能为空"
        val u = searchUrl.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return "地址必须以 http:// 或 https:// 开头"
        if (!u.contains("{kw}")) return "地址必须包含 {kw} 占位符"
        if (parser.isBlank()) return "解析规则不能为空"
        return null
    }

    /** 稳定 id：基于地址（域名轮换后用户可删除重建） */
    fun makeId(searchUrl: String): String =
        "custom_" + Integer.toHexString(searchUrl.trim().hashCode())

    /** 展示用的解析规则名（用户可填中文别名） */
    fun normalizeParser(parser: String): String = when (parser.trim().lowercase()) {
        "torrentkitty", "动漫花园", "dmhy" -> "torrentkitty"
        "行表", "rowtable", "磁力猫" -> "rowtable"
        else -> "generic"
    }

    /* ---------------- 纯 Kotlin JSON（针对固定结构的自定义源列表） ---------------- */

    fun encodeCustom(sources: List<MagnetSource>): String {
        val body = sources.joinToString(",") { s ->
            "{\"id\":${q(s.id)},\"name\":${q(s.name)},\"searchUrl\":${q(s.searchUrl)}," +
                "\"parser\":${q(s.parser)},\"enabled\":${s.enabled}}"
        }
        return "{\"sources\":[$body]}"
    }

    fun decodeCustom(json: String): List<MagnetSource> {
        val arr = arrayObjects(json, "sources") ?: return emptyList()
        return arr.mapNotNull { obj ->
            val id = strField(obj, "id") ?: return@mapNotNull null
            val name = strField(obj, "name") ?: return@mapNotNull null
            val url = strField(obj, "searchUrl") ?: return@mapNotNull null
            val parser = strField(obj, "parser") ?: "generic"
            if (id.isBlank() || name.isBlank() || url.isBlank()) return@mapNotNull null
            MagnetSource(id, name, url, parser, boolField(obj, "enabled", true))
        }
    }

    private fun q(s: String): String = "\"" + jsString(s) + "\""

    private fun jsString(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { append('\n'); i += 2; continue }
                    'r' -> { append('\r'); i += 2; continue }
                    't' -> { append('\t'); i += 2; continue }
                    '"' -> { append('"'); i += 2; continue }
                    '\\' -> { append('\\'); i += 2; continue }
                }
            }
            append(s[i]); i++
        }
    }

    private fun strField(obj: String, key: String): String? {
        val i = obj.indexOf("\"$key\"")
        if (i < 0) return null
        var p = i + key.length + 2
        while (p < obj.length && obj[p] != ':') p++
        p++ // 跳过冒号
        while (p < obj.length && obj[p].isWhitespace()) p++
        if (p >= obj.length || obj[p] != '"') return null
        p++
        val sb = StringBuilder()
        while (p < obj.length) {
            val c = obj[p]
            if (c == '\\' && p + 1 < obj.length) { sb.append(c).append(obj[p + 1]); p += 2; continue }
            if (c == '"') break
            sb.append(c); p++
        }
        return unescape(sb.toString())
    }

    private fun boolField(obj: String, key: String, default: Boolean): Boolean {
        val i = obj.indexOf("\"$key\"")
        if (i < 0) return default
        val tail = obj.substring(i).substringAfter(':', "")
        return when {
            tail.trimStart().startsWith("true") -> true
            tail.trimStart().startsWith("false") -> false
            else -> default
        }
    }

    /** 提取数组对象（只支持一层 {} 嵌套，字段值里不含未转义 {}） */
    private fun arrayObjects(json: String, key: String): List<String>? {
        val i = json.indexOf("\"$key\"")
        if (i < 0) return null
        var p = i + key.length + 2
        while (p < json.length && json[p] != '[') p++
        if (p >= json.length) return null
        p++
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        var inStr = false
        var esc = false
        while (p < json.length) {
            val c = json[p]
            if (inStr) {
                sb.append(c)
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
                p++; continue
            }
            when (c) {
                '"' -> { inStr = true; sb.append(c) }
                '{' -> { depth++; sb.append(c) }
                '}' -> { depth--; sb.append(c); if (depth == 0) { out.add(sb.toString()); sb.setLength(0) } }
                ']' -> { if (depth == 0) return out; sb.append(c) }
                else -> sb.append(c)
            }
            p++
        }
        return out.ifEmpty { emptyList() }
    }

    /* ---------------- 诊断文案（纯逻辑） ---------------- */

    fun statusLabel(status: MagnetSearch.SourceStatus, count: Int): String = when (status) {
        MagnetSearch.SourceStatus.OK -> if (count > 0) "$count 条" else "在线但 0 条"
        MagnetSearch.SourceStatus.EMPTY_SHELL -> "空壳页（可能被 JS 渲染/拦截）"
        MagnetSearch.SourceStatus.HTTP_403 -> "403 被拦截"
        MagnetSearch.SourceStatus.TIMEOUT -> "连接超时"
        MagnetSearch.SourceStatus.NETWORK -> "网络不可达"
        MagnetSearch.SourceStatus.PARSE -> "解析失败"
    }
}
