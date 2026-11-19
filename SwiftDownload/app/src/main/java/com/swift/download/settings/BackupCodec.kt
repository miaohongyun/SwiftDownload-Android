package com.swift.download.settings

/**
 * v2.7 Stage4.4：配置备份的 JSON 编解码（纯 Kotlin，零依赖，JVM 单测覆盖往返还原）。
 * 不依赖 org.json —— 后者在 JVM 单测里是 android stub（方法返回 null），
 * 无法为"JSON 备份序列化与往返还原"提供真实单测。这里针对备份的固定结构手写编解码。
 *
 * 备份内容：书签、磁力源覆盖、广告自定义规则、下载设置键值对，带版本号与时间戳。
 */
object BackupCodec {

    const val VERSION = 1

    data class Site(val name: String, val url: String)
    data class MagnetOverride(val id: String, val searchUrl: String, val enabled: Boolean)
    data class BackupData(
        val version: Int,
        val timestamp: Long,
        val sites: List<Site>,
        val magnetOverrides: List<MagnetOverride>,
        val adRules: String,
        val settings: Map<String, String>
    ) {
        companion object {
            fun empty(): BackupData = BackupData(
                VERSION, System.currentTimeMillis(), emptyList(), emptyList(), "", emptyMap()
            )
        }
    }

    class IncompatibleVersion(val found: Int) : Exception("不支持的备份版本：$found")

    /* ---------------- 编码 ---------------- */

    fun toJson(data: BackupData): String = buildString {
        append("{\"format\":\"SwiftDownload_Backup\",")
        append("\"version\":").append(data.version).append(',')
        append("\"timestamp\":").append(data.timestamp).append(',')
        append("\"sites\":[")
        data.sites.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append("{\"name\":").append(q(s.name)).append(",\"url\":").append(q(s.url)).append('}')
        }
        append("],\"magnetOverrides\":[")
        data.magnetOverrides.forEachIndexed { i, m ->
            if (i > 0) append(',')
            append("{\"id\":").append(q(m.id))
            append(",\"searchUrl\":").append(q(m.searchUrl))
            append(",\"enabled\":").append(m.enabled).append('}')
        }
        append("],\"adRules\":").append(q(data.adRules))
        append(",\"settings\":{")
        data.settings.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) append(',')
            append(q(k)).append(':').append(q(v))
        }
        append("}}")
    }

    /** JSON 字符串字面量（含引号），转义特殊字符 */
    private fun q(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /* ---------------- 解码（针对固定结构的轻量扫描） ---------------- */

    fun fromJson(json: String): BackupData {
        if (!json.contains("\"format\"") || !json.contains("SwiftDownload_Backup")) {
            throw IllegalArgumentException("不是 SwiftDownload 备份文件")
        }
        val versionLong = intField(json, "version") ?: throw IllegalArgumentException("备份版本号无效")
        if (versionLong > VERSION) throw IncompatibleVersion(versionLong.toInt())
        if (versionLong <= 0) throw IllegalArgumentException("备份版本号无效")
        val version = versionLong.toInt()

        val timestamp = intField(json, "timestamp") ?: 0L

        val sites = arrayObjects(json, "sites").mapNotNull {
            val name = strField(it, "name")
            val url = strField(it, "url")
            if (name != null && url != null && name.isNotBlank() && url.isNotBlank()) Site(name, url) else null
        }
        val mags = arrayObjects(json, "magnetOverrides").mapNotNull {
            val id = strField(it, "id") ?: return@mapNotNull null
            if (id.isBlank()) return@mapNotNull null
            MagnetOverride(id, strField(it, "searchUrl") ?: "", boolField(it, "enabled") ?: true)
        }
        val adRules = strField(json, "adRules") ?: ""
        val settings = LinkedHashMap<String, String>()
        objectField(json, "settings")?.let { body ->
            for ((k, v) in parseStringPairs(body)) settings[k] = v
        }

        return BackupData(version, timestamp, sites, mags, adRules, settings)
    }

    /** 取顶层整数字段（含负数） */
    private fun intField(json: String, key: String): Long? {
        val i = json.indexOf("\"$key\"")
        if (i < 0) return null
        var p = i + key.length + 2
        while (p < json.length && json[p] != ':') p++
        p++
        while (p < json.length && json[p] == ' ') p++
        val start = p
        if (p < json.length && (json[p] == '-' || json[p] == '+')) p++
        while (p < json.length && json[p].isDigit()) p++
        if (p == start) return null
        return json.substring(start, p).toLongOrNull()
    }

    /** 取顶层/对象内字符串字段 */
    private fun strField(obj: String, key: String): String? {
        val i = obj.indexOf("\"$key\"")
        if (i < 0) return null
        var p = i + key.length + 2
        while (p < obj.length && obj[p] != ':') p++
        p++
        while (p < obj.length && obj[p] == ' ') p++
        return parseJsonString(obj, p)?.first
    }

    private fun boolField(obj: String, key: String): Boolean? {
        val i = obj.indexOf("\"$key\"")
        if (i < 0) return null
        var p = i + key.length + 2
        while (p < obj.length && obj[p] != ':') p++
        val rest = obj.substring(p + 1).trimStart()
        return when {
            rest.startsWith("true") -> true
            rest.startsWith("false") -> false
            else -> null
        }
    }

    /** 从位置 p 起解析一个 JSON 字符串，返回 (值, 结束位置+1)；非字符串返回 null */
    private fun parseJsonString(s: String, p: Int): Pair<String, Int>? {
        if (p >= s.length || s[p] != '"') return null
        val out = StringBuilder()
        var i = p + 1
        while (i < s.length) {
            when (val c = s[i]) {
                '"' -> return out.toString() to i + 1
                '\\' -> {
                    i++
                    if (i >= s.length) break
                    when (s[i]) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        'u' -> {
                            val hex = s.substring(i + 1, (i + 5).coerceAtMost(s.length))
                            val code = hex.toIntOrNull(16)
                            if (code != null) out.append(code.toChar())
                            i += 4
                        }
                        else -> out.append(s[i])
                    }
                    i++
                }
                else -> { out.append(c); i++ }
            }
        }
        return null
    }

    /** 取数组的每个 {...} 对象体 */
    private fun arrayObjects(json: String, key: String): List<String> {
        val i = json.indexOf("\"$key\"")
        if (i < 0) return emptyList()
        val bracket = json.indexOf('[', i)
        if (bracket < 0) return emptyList()
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inStr = false
        var esc = false
        for (p in bracket until json.length) {
            val c = json[p]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) start = p; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) { out.add(json.substring(start, p + 1)); start = -1 }
                }
                ']' -> if (depth == 0) return out
            }
        }
        return out
    }

    /** 取对象的 {...} 体 */
    private fun objectField(json: String, key: String): String? {
        val i = json.indexOf("\"$key\"")
        if (i < 0) return null
        val brace = json.indexOf('{', i)
        if (brace < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (p in brace until json.length) {
            val c = json[p]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return json.substring(brace + 1, p) }
            }
        }
        return null
    }

    /** 解析 {"k":"v",...} 的字符串键值对 */
    private fun parseStringPairs(body: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < body.length) {
            while (i < body.length && (body[i] == ' ' || body[i] == ',')) i++
            val key = parseJsonString(body, i) ?: break
            i = key.second
            while (i < body.length && body[i] != ':') i++
            i++
            while (i < body.length && body[i] == ' ') i++
            val value = parseJsonString(body, i) ?: break
            out.add(key.first to value.first)
            i = value.second
        }
        return out
    }
}
