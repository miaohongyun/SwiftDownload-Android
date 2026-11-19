package com.swift.download.magnet

/**
 * 规则驱动的 HTML 解析（纯 Kotlin，单元测试覆盖）。
 * 每个源的规则 = 行切分 → 行内磁力链接 + 名称 + 大小 + 日期/做种数提取。
 * 全部失败时用 genericMagnetScan 兜底：扫描页面内所有 magnet 链接。
 */
object MagnetParsers {

    data class RawItem(
        val hash: String,       // 归一化后的 40 位 hex
        val name: String,
        val sizeBytes: Long,    // -1 未知
        val seeders: Int,       // -1 未知
        val date: String?       // "2026-01-01" 形式或原样
    )

    private val magnetRegex = Regex(
        """magnet:\?xt=urn:btih:([0-9a-zA-Z]{32,40})[^"'\s<>]*""",
        RegexOption.IGNORE_CASE
    )
    private val sizeRegex = Regex("""(\d+(?:\.\d+)?)\s*(TB|GB|MB|KB|B)\b""", RegexOption.IGNORE_CASE)
    private val dateRegex = Regex("""\d{4}-\d{2}-\d{2}""")
    private val seedersRegex = Regex("""(?:做种|種子|seeds?|seeders?)[^\d]{0,6}(\d+)""", RegexOption.IGNORE_CASE)

    /* ---------------- 可插拔解析器 ---------------- */

    /** 注册自定义站点解析器（同名覆盖内置规则），用于站点改版后无需发版即可适配 */
    fun register(name: String, parser: (String) -> List<RawItem>) {
        custom[name] = parser
    }

    private val custom = java.util.concurrent.ConcurrentHashMap<String, (String) -> List<RawItem>>()

    /* ---------------- 源规则 ---------------- */

    fun parse(sourceParser: String, html: String): List<RawItem> {
        // 去脚本/样式，避免误扫
        val clean = html
            .replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE), "")
        val rows = custom[sourceParser]?.invoke(clean) ?: when (sourceParser) {
            "torrentkitty" -> torrentKitty(clean)
            "dmhy" -> dmhy(clean)
            "rowtable" -> rowTable(clean)
            else -> generic(clean)
        }
        // 结构化失败 → 通用磁力扫描退化
        return rows.ifEmpty { generic(clean) }
    }

    /** TorrentKitty：结果行为 <tr>，磁力链接在行内 anchor，名称为行内第一个非磁力 anchor 文本 */
    private fun torrentKitty(html: String): List<RawItem> = rows(html).mapNotNull { row ->
        val m = magnetRegex.find(row) ?: return@mapNotNull null
        val hash = MagnetLinks.normalizeHash(m.groupValues[1]) ?: return@mapNotNull null
        val anchors = Regex("""<a\b[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE).findAll(row).toList()
        var name = ""
        for (a in anchors) {
            val text = stripTags(a.groupValues[1]).trim()
            if (text.isNotEmpty() && !text.startsWith("磁力") && !text.startsWith("Magnet") &&
                !text.equals("下载", true) && !m.value.contains(text)
            ) { name = text; break }
        }
        if (name.isEmpty()) name = decodeFromUrl(m.value)
        RawItem(hash, name, sizeOf(row), seedOf(row), dateRegex.find(row)?.value)
    }

    /** 动漫花园：行内 class="topic" 或磁力图标行，名称优先取 title 属性 */
    private fun dmhy(html: String): List<RawItem> = rows(html).mapNotNull { row ->
        val m = magnetRegex.find(row) ?: return@mapNotNull null
        val hash = MagnetLinks.normalizeHash(m.groupValues[1]) ?: return@mapNotNull null
        val title = Regex("""title="([^"]+)"""").findAll(row)
            .map { stripTags(it.groupValues[1]).trim() }
            .firstOrNull { it.isNotEmpty() } ?: ""
        val name = title.ifEmpty { decodeFromUrl(m.value).ifEmpty { "未命名资源" } }
        RawItem(hash, name, sizeOf(row), seedOf(row), dateRegex.find(row)?.value)
    }

    /** 通用行表（磁力猫类站点）：任意 <tr> 行内含 magnet 即认为是一条结果 */
    private fun rowTable(html: String): List<RawItem> = rows(html).mapNotNull { row ->
        val m = magnetRegex.find(row) ?: return@mapNotNull null
        val hash = MagnetLinks.normalizeHash(m.groupValues[1]) ?: return@mapNotNull null
        val cells = Regex("""<td\b[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE).findAll(row).map {
            stripTags(it.groupValues[1]).trim()
        }.filter { it.isNotEmpty() }.toList()
        val name = cells.firstOrNull()?.ifEmpty { null } ?: decodeFromUrl(m.value)
        RawItem(hash, name, sizeOf(row), seedOf(row), dateRegex.find(row)?.value)
    }

    /** 通用兜底：整页扫 magnet 链接；名称取链接前后的可见文本 */
    fun generic(html: String): List<RawItem> {
        val seen = LinkedHashMap<String, RawItem>()
        for (m in magnetRegex.findAll(html)) {
            val hash = MagnetLinks.normalizeHash(m.groupValues[1]) ?: continue
            if (seen.containsKey(hash)) continue
            val name = nearbyName(html, m.range.first) ?: decodeFromUrl(m.value)
            // v2.9.1：generic 通用扫描补齐相邻日期，与标题/大小/做种一起关联提取
            seen[hash] = RawItem(
                hash, name, sizeNear(html, m.range.first), seedNear(html, m.range.first),
                dateNear(html, m.range.first)
            )
        }
        return seen.values.toList()
    }

    /* ---------------- 工具 ---------------- */

    private fun rows(html: String): List<String> =
        Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.toList()
            .ifEmpty {
                Regex("""<li\b[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)
                    .findAll(html).map { it.groupValues[1] }.toList()
            }
            .ifEmpty {
                Regex("""<div\b[^>]*(?:class|id)="[^"]*(?:item|result|row)[^"]*"[^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
                    .findAll(html).map { it.groupValues[1] }.toList()
            }

    fun stripTags(s: String): String =
        s.replace(Regex("""<[^>]+>"""), " ")
            .replace(Regex("""&nbsp;?""", RegexOption.IGNORE_CASE), " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("""\s{2,}"""), " ").trim()

    /** magnet 链接的 dn= 参数作为名称 */
    private fun decodeFromUrl(magnetValue: String): String {
        val dn = Regex("""[?&]dn=([^&"'\s<>]+)""").find(magnetValue)?.groupValues?.get(1)
        if (dn != null) {
            return try {
                stripTags(java.net.URLDecoder.decode(dn, "UTF-8"))
            } catch (e: Exception) { dn }
        }
        return ""
    }

    fun sizeOf(row: String): Long {
        val m = sizeRegex.find(row) ?: return -1
        return sizeToBytes(m.groupValues[1].toDouble(), m.groupValues[2])
    }

    fun sizeToBytes(num: Double, unit: String): Long {
        val mult = when (unit.uppercase()) {
            "B" -> 1.0
            "KB" -> 1024.0
            "MB" -> 1024.0 * 1024
            "GB" -> 1024.0 * 1024 * 1024
            "TB" -> 1024.0 * 1024 * 1024 * 1024
            else -> return -1
        }
        val v = num * mult
        return if (v > 1.0e15) -1 else v.toLong()
    }

    private fun seedOf(row: String): Int =
        seedersRegex.find(row)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    private fun nearbyName(html: String, pos: Int): String? {
        val window = html.substring(maxOf(0, pos - 300), minOf(html.length, pos + 300))
        val tagTexts = Regex(""">([^<>{}]{3,80})<""").findAll(window)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() && !it.startsWith("magnet") }
        return tagTexts.firstOrNull { it.length >= 2 }
    }

    private fun sizeNear(html: String, pos: Int): Long {
        val window = html.substring(maxOf(0, pos - 200), minOf(html.length, pos + 200))
        return sizeOf(window)
    }

    private fun seedNear(html: String, pos: Int): Int {
        val window = html.substring(maxOf(0, pos - 200), minOf(html.length, pos + 200))
        return seedOf(window)
    }

    /** v2.9.1：取磁力链接邻近窗口里的日期（yyyy-MM-dd / yyyy/MM/dd 统一为 - ） */
    private fun dateNear(html: String, pos: Int): String? {
        val window = html.substring(maxOf(0, pos - 250), minOf(html.length, pos + 250))
        return Regex("""\d{4}[-/]\d{2}[-/]\d{2}""").find(window)?.value?.replace('/', '-')
    }
}
