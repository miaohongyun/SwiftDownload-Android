package com.swift.download

import com.swift.download.browser.BookmarkRules
import com.swift.download.bt.BtSelfTestRules
import com.swift.download.magnet.MagnetSource
import com.swift.download.magnet.MagnetSourceImport
import com.swift.download.magnet.MagnetSourceRules
import com.swift.download.magnet.MagnetSearch
import com.swift.download.magnet.SearchTemplateRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.9.1：批量导入 / 网页转源模板 / 公有领域书签迁移 / WebView 兜底 的纯 JVM 回归 */
class V291RegressionTest {

    /* ---------------- 批量导入 ---------------- */

    @Test
    fun `管道分隔与空格分隔批量导入解析`() {
        val text = """
            # 注释行应忽略
            我的站|example.com/s/{kw}
            动漫站 https://a.test/search?q={kw}
            坏行没有分隔符也没有网址
        """.trimIndent()
        val p = MagnetSourceImport.parse(text)
        assertEquals(2, p.accepted)
        assertEquals("我的站", p.sources[0].name)
        assertEquals("https://example.com/s/{kw}", p.sources[0].searchUrl) // 补全 https
        assertEquals("https://a.test/search?q={kw}", p.sources[1].searchUrl)
        assertTrue(p.errors.isNotEmpty()) // 无分隔符无网址的行应报错
    }

    @Test
    fun `JSON 数组批量导入含 needProxy 与 useWebView`() {
        val json = """[{"name":"站A","searchUrl":"https://a.test/s/{kw}","parser":"generic","needProxy":true,"useWebView":true},{"name":"站B","searchUrl":"b.test/?q={kw}"}]"""
        val p = MagnetSourceImport.parse(json)
        assertEquals(2, p.accepted)
        assertTrue(p.sources[0].proxyRequired)
        assertTrue(p.sources[0].useWebView)
        assertEquals("https://b.test/?q={kw}", p.sources[1].searchUrl)
    }

    @Test
    fun `批量导入按 URL 去重并拒绝缺占位符`() {
        val text = """
            站A|https://x.test/s/{kw}
            站B|https://x.test/s/{kw}
            站C|https://y.test/s/
        """.trimIndent()
        val p = MagnetSourceImport.parse(text)
        assertEquals(1, p.accepted)
        assertEquals(1, p.duplicates)
        assertTrue(p.errors.any { it.contains("{kw}") })
    }

    @Test
    fun `自定义源 JSON 往返保留 needProxy 与 useWebView`() {
        val src = listOf(MagnetSource("id1", "站", "https://x/{kw}", "generic", true, true, true))
        val back = MagnetSourceRules.decodeCustom(MagnetSourceRules.encodeCustom(src))
        assertEquals(1, back.size)
        assertTrue(back[0].enabled)
        assertTrue(back[0].proxyRequired)
        assertTrue(back[0].useWebView)
    }

    /* ---------------- 网页转搜索源 ---------------- */

    @Test
    fun `从查询参数关键词生成模板`() {
        val r = SearchTemplateRules.build(
            "https://site.test/search?q=%E5%A5%A5%E5%BE%B7%E8%B5%9B&page=1", "奥德赛"
        )
        val tpl = r.template ?: ""
        assertTrue(r.ok)
        assertTrue(tpl.contains("{kw}"))
        assertFalse(tpl.contains("奥德赛"))
        assertTrue(r.replaced >= 1)
    }

    @Test
    fun `从路径末段关键词生成模板`() {
        val r = SearchTemplateRules.build("https://site.test/s/奥德赛.html", "奥德赛")
        assertTrue(r.ok)
        assertEquals("https://site.test/s/{kw}.html", r.template)
    }

    @Test
    fun `关键词不在网址中时拒绝生成`() {
        val r = SearchTemplateRules.build("https://site.test/index.html", "无关词")
        assertFalse(r.ok)
        assertNotNull(r.error)
    }

    /* ---------------- 公有领域书签迁移 ---------------- */

    @Test
    fun `默认书签含三个公有领域站`() {
        assertEquals(7, BookmarkRules.DEFAULTS.size)
        assertTrue(BookmarkRules.PUBLIC_DOMAIN_DEFAULTS.any { it.second.contains("archive.org") })
        assertTrue(BookmarkRules.PUBLIC_DOMAIN_DEFAULTS.any { it.second.contains("publicdomaintorrents.info") })
        assertTrue(BookmarkRules.PUBLIC_DOMAIN_DEFAULTS.any { it.second.contains("awesome-legal-torrent-sources") })
    }

    @Test
    fun `合并默认书签不覆盖用户自定义项`() {
        val loaded = listOf(
            "必应" to "https://www.google.com",     // 用户改过的同名项，应原样保留
            "我的站" to "https://my.test/{kw}"        // 用户自定义项，应保留
        )
        val merged = BookmarkRules.mergeMissingDefaults(loaded)
        assertEquals("https://www.google.com", merged.first { it.first == "必应" }.second)
        assertTrue(merged.any { it.first == "我的站" })
        // 追加了 3 个公有领域默认站
        assertTrue(BookmarkRules.PUBLIC_DOMAIN_DEFAULTS.all { d -> merged.any { it.second == d.second } })
    }

    @Test
    fun `重复合并不会新增重复书签`() {
        val once = BookmarkRules.mergeMissingDefaults(BookmarkRules.LEGACY_DEFAULTS)
        val twice = BookmarkRules.mergeMissingDefaults(once)
        assertEquals(once.size, twice.size)
    }

    /* ---------------- WebView 兜底与 BT 自检 ---------------- */

    @Test
    fun `useWebView 源强制兜底但成功源不兜底`() {
        val ok = MagnetSource("ok", "正常", "https://a/{kw}", "generic", useWebView = false)
        val web = MagnetSource("web", "JS站", "https://b/{kw}", "generic", useWebView = true)
        val report = MagnetSearch.SearchReport(
            results = emptyList(), okSources = listOf("ok"), failedSources = emptyList(),
            outcomes = listOf(
                MagnetSearch.SourceOutcome("ok", MagnetSearch.SourceStatus.OK, 1),
                MagnetSearch.SourceOutcome("web", MagnetSearch.SourceStatus.EMPTY_SHELL, 0)
            )
        )
        val picked = MagnetSearch.webViewFallbackSources(listOf(ok, web), report).map { it.id }
        assertFalse(picked.contains("ok"))
        assertTrue(picked.contains("web"))
    }

    @Test
    fun `BT 自检解析三种输入`() {
        assertEquals(BtSelfTestRules.InputKind.HASH, BtSelfTestRules.parseInput("a".repeat(40)).kind)
        assertEquals(BtSelfTestRules.InputKind.MAGNET,
            BtSelfTestRules.parseInput("magnet:?xt=urn:btih:" + "b".repeat(40)).kind)
        assertEquals(BtSelfTestRules.InputKind.TORRENT_URL,
            BtSelfTestRules.parseInput("https://archive.org/x.torrent").kind)
        assertFalse(BtSelfTestRules.parseInput("随便文本").valid)
    }

    @Test
    fun `BT 自检可达性判据与格式化`() {
        val d0 = BtSelfTestRules.Diagnostics()
        assertFalse(BtSelfTestRules.isReachable(d0))
        assertTrue(BtSelfTestRules.format(d0).contains("DHT节点 0"))
        val d1 = BtSelfTestRules.Diagnostics(dhtNodes = 30)
        assertTrue(BtSelfTestRules.isReachable(d1))
    }
}
