package com.swift.download.magnet

/**
 * v2.9.1：磁力源一键批量导入纯逻辑（零 Android 依赖，JVM 单测覆盖）。
 *
 * 兼容三种格式（可混排）：
 *  1. 名称|搜索URL(含 {kw})
 *  2. 名称 空格/Tab 搜索URL(含 {kw})
 *  3. JSON 数组 [{"name":"...","searchUrl":"...","parser":"generic","needProxy":false,"useWebView":false}]
 *
 * 统一做：URL 合法性校验、补全 https://、{kw} 占位校验、按 URL 去重、生成稳定 id。
 */
object MagnetSourceImport {

    data class Preview(
        val sources: List<MagnetSource>,
        /** 逐行错误说明："第 3 行：地址缺少 {kw} 占位符" */
        val errors: List<String>,
        /** 因 URL 重复被跳过的条数 */
        val duplicates: Int
    ) {
        val accepted: Int get() = sources.size
    }

    /**
     * 解析多行粘贴文本。JSON 数组整体识别；否则逐行按 | 或空白切分。
     */
    fun parse(text: String): Preview {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Preview(emptyList(), listOf("内容为空"), 0)

        val sources = ArrayList<MagnetSource>()
        val errors = ArrayList<String>()
        val seenUrls = HashSet<String>()
        var duplicates = 0

        if (trimmed.startsWith("[")) {
            val parsed = parseJson(trimmed)
            if (parsed == null) {
                return Preview(emptyList(), listOf("JSON 解析失败：需为对象数组，且每项含 name / searchUrl"), 0)
            }
            parsed.first.forEachIndexed { i, s ->
                // 先补全 https 再校验，否则 b.test/... 会被误判为非法地址
                val key = normalizeUrl(s.searchUrl)
                val normalized = s.copy(searchUrl = key, id = MagnetSourceRules.makeId(key))
                val err = validate(normalized)
                if (err != null) { errors.add("第 ${i + 1} 项：$err"); return@forEachIndexed }
                if (!seenUrls.add(key)) { duplicates++; return@forEachIndexed }
                sources.add(normalized)
            }
            if (parsed.second.isNotEmpty()) errors.addAll(parsed.second)
            return Preview(sources, errors, duplicates)
        }

        trimmed.lineSequence().forEachIndexed { index, raw ->
            val lineNo = index + 1
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith("#")) return@forEachIndexed // 注释行

            val pair = splitLine(line)
            if (pair == null) {
                errors.add("第 $lineNo 行：无法识别，格式为「名称|地址」或「名称 空格 地址」")
                return@forEachIndexed
            }
            val (name, rawUrl) = pair
            if (name.isEmpty()) { errors.add("第 $lineNo 行：名称不能为空"); return@forEachIndexed }
            val url = normalizeUrl(rawUrl)
            val candidate = MagnetSource(
                id = MagnetSourceRules.makeId(url),
                name = name,
                searchUrl = url,
                parser = "generic",
                enabled = true
            )
            val err = validate(candidate)
            if (err != null) { errors.add("第 $lineNo 行：$err"); return@forEachIndexed }
            if (!seenUrls.add(url)) { duplicates++; return@forEachIndexed }
            sources.add(candidate)
        }
        return Preview(sources, errors, duplicates)
    }

    /** 名称|地址 或 名称 空白 地址（取第一段空白后的全部为地址，兼容地址里的空格） */
    private fun splitLine(line: String): Pair<String, String>? {
        val bar = line.indexOf('|')
        if (bar > 0) return line.substring(0, bar).trim() to line.substring(bar + 1).trim()
        val ws = Regex("""\s{1,}""").find(line) ?: return null
        val name = line.substring(0, ws.range.first).trim()
        val url = line.substring(ws.range.last + 1).trim()
        if (name.isEmpty() || url.isEmpty()) return null
        return name to url
    }

    /** 协议缺失时补全 https://；去掉首尾空白 */
    fun normalizeUrl(raw: String): String {
        val u = raw.trim()
        return when {
            u.startsWith("http://", true) || u.startsWith("https://", true) -> u
            u.isEmpty() -> u
            else -> "https://$u"
        }
    }

    private fun validate(s: MagnetSource): String? = when {
        s.name.isBlank() -> "名称不能为空"
        !s.searchUrl.startsWith("http://") && !s.searchUrl.startsWith("https://") -> "地址必须以 http(s) 开头"
        !s.searchUrl.contains("{kw}") -> "地址缺少 {kw} 占位符"
        else -> null
    }

    /**
     * 轻量 JSON 数组解析：只处理固定 5 字段，避免引入 org.json（JVM 单测 stub 会抛异常）。
     * 返回 (成功项, 跳过项错误说明)。字段 needProxy 兼容 proxyRequired。
     */
    private fun parseJson(json: String): Pair<List<MagnetSource>, List<String>>? {
        val objs = arrayObjects(json) ?: return null
        val out = ArrayList<MagnetSource>()
        val errs = ArrayList<String>()
        objs.forEachIndexed { i, obj ->
            val name = strField(obj, "name")
            val url = strField(obj, "searchUrl") ?: strField(obj, "url")
            if (name.isNullOrBlank() || url.isNullOrBlank()) {
                errs.add("第 ${i + 1} 项缺少 name 或 searchUrl")
                return@forEachIndexed
            }
            val parser = strField(obj, "parser") ?: "generic"
            val needProxy = boolField(obj, "needProxy") || boolField(obj, "proxyRequired")
            val useWebView = boolField(obj, "useWebView")
            out.add(
                MagnetSource(
                    id = MagnetSourceRules.makeId(url),
                    name = name.trim(),
                    searchUrl = url.trim(),
                    parser = MagnetSourceRules.normalizeParser(parser),
                    enabled = boolField(obj, "enabled", default = true),
                    proxyRequired = needProxy,
                    useWebView = useWebView
                )
            )
        }
        return out to errs
    }

    private fun boolField(obj: String, key: String, default: Boolean = false): Boolean {
        val i = obj.indexOf("\"$key\"")
        if (i < 0) return default
        val tail = obj.substring(i).substringAfter(':', "")
        return when {
            tail.trimStart().startsWith("true") -> true
            tail.trimStart().startsWith("false") -> false
            else -> default
        }
    }

    private fun strField(obj: String, key: String): String? {
        val i = obj.indexOf("\"$key\"")
        if (i < 0) return null
        var p = i + key.length + 2
        while (p < obj.length && obj[p] != ':') p++
        p++
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
        return sb.toString()
    }

    /** 提取数组内的对象（支持一层嵌套与转义引号），与 MagnetSourceRules 同思路 */
    private fun arrayObjects(json: String): List<String>? {
        var p = json.indexOf('[')
        if (p < 0) return null
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
                ']' -> if (depth == 0) return out
                else -> sb.append(c)
            }
            p++
        }
        return out.ifEmpty { emptyList() }
    }
}
