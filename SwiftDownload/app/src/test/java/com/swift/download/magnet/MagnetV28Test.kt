package com.swift.download.magnet

import com.swift.download.player.VideoSniff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.8 模块四纯逻辑单测：源校验/编解码、诊断分类与合并、DOM 批量资源提取 */
class MagnetV28Test {

    /* -------- 自定义源校验与编解码 -------- */

    @Test
    fun `自定义源地址校验`() {
        assertNull(MagnetSourceRules.validate("源", "https://x.com/s/{kw}", "generic"))
        assertTrue(MagnetSourceRules.validate("", "https://x.com/s/{kw}", "generic")!!.contains("名称"))
        assertTrue(MagnetSourceRules.validate("源", "ftp://x.com/{kw}", "generic")!!.contains("http"))
        assertTrue(MagnetSourceRules.validate("源", "https://x.com/s/", "generic")!!.contains("{kw}"))
    }

    @Test
    fun `自定义源 id 稳定且解析规则归一化`() {
        val id1 = MagnetSourceRules.makeId("https://x.com/s/{kw}")
        val id2 = MagnetSourceRules.makeId("https://x.com/s/{kw}")
        assertEquals(id1, id2)
        assertTrue(id1.startsWith("custom_"))
        assertEquals("generic", MagnetSourceRules.normalizeParser(""))
        assertEquals("torrentkitty", MagnetSourceRules.normalizeParser("torrentkitty"))
        assertEquals("rowtable", MagnetSourceRules.normalizeParser("磁力猫"))
    }

    @Test
    fun `自定义源 JSON 往返还原含转义字符`() {
        val src = listOf(
            MagnetSource("custom_1", "我的\"资源站", "https://x.com/s/{kw}", "rowtable", true),
            MagnetSource("custom_2", "禁用源", "https://y.com/s?k={kw}", "generic", false)
        )
        val json = MagnetSourceRules.encodeCustom(src)
        val back = MagnetSourceRules.decodeCustom(json)
        assertEquals(2, back.size)
        assertEquals(src[0].name, back[0].name)
        assertEquals(src[0].searchUrl, back[0].searchUrl)
        assertEquals(src[0].parser, back[0].parser)
        assertTrue(back[0].enabled)
        assertFalse(back[1].enabled)
    }

    @Test
    fun `损坏 JSON 不抛异常`() {
        assertTrue(MagnetSourceRules.decodeCustom("not json").isEmpty())
        assertTrue(MagnetSourceRules.decodeCustom("{}").isEmpty())
    }

    /* -------- 诊断分类与合并 -------- */

    @Test
    fun `异常分类为状态`() {
        assertEquals(MagnetSearch.SourceStatus.HTTP_403,
            MagnetSearch.classifyFailure(MagnetSearch.FetchException(403, "HTTP 403")))
        assertEquals(MagnetSearch.SourceStatus.TIMEOUT,
            MagnetSearch.classifyFailure(java.util.concurrent.TimeoutException()))
        assertEquals(MagnetSearch.SourceStatus.TIMEOUT,
            MagnetSearch.classifyFailure(IllegalStateException("x", java.net.SocketTimeoutException())))
        assertEquals(MagnetSearch.SourceStatus.NETWORK,
            MagnetSearch.classifyFailure(java.io.IOException("fail")))
    }

    @Test
    fun `诊断状态文案`() {
        assertTrue(MagnetSourceRules.statusLabel(MagnetSearch.SourceStatus.HTTP_403, 0).contains("403"))
        assertEquals("3 条", MagnetSourceRules.statusLabel(MagnetSearch.SourceStatus.OK, 3))
        assertTrue(MagnetSourceRules.statusLabel(MagnetSearch.SourceStatus.EMPTY_SHELL, 0).contains("空壳"))
    }

    @Test
    fun `离屏兜底合并去重并更新诊断`() {
        val h = "a".repeat(40)
        val base = MagnetSearch.SearchReport(
            results = listOf(MagnetSearch.Result(h, "原名", 100, 0, listOf("s1"), null)),
            okSources = listOf("s1"), failedSources = listOf("s2"),
            outcomes = listOf(
                MagnetSearch.SourceOutcome("s1", MagnetSearch.SourceStatus.OK, 1),
                MagnetSearch.SourceOutcome("s2", MagnetSearch.SourceStatus.EMPTY_SHELL, 0)
            )
        )
        val fallback = mapOf("s2" to listOf(MagnetParsers.RawItem(h, "更完整名称", 999, 5, null)))
        val merged = MagnetSearch.mergeFallback(base, fallback)
        assertEquals(1, merged.results.size) // 同 hash 去重
        assertEquals(2, merged.results[0].sources.size)
        assertEquals(999L, merged.results[0].sizeBytes)
        assertEquals(5, merged.results[0].seeders)
        assertEquals(MagnetSearch.SourceStatus.OK, merged.outcomes.first { it.sourceId == "s2" }.status)
        assertTrue("s2" in merged.okSources)
        assertTrue(merged.failedSources.isEmpty())
    }

    /* -------- DOM 批量资源提取 -------- */

    @Test
    fun `DOM 提取磁力 种子 电驴 裸hash 并去重`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val html = buildString {
            append("<a href=\"magnet:?xt=urn:btih:$hash&dn=%E7%94%B5%E5%BD%B1A\">电影A下载</a>")
            append("<a href=\"https://x.com/file.torrent?u=1\">种子B</a>")
            append("<a href=\"ed2k://|file|Song.mp3|12345|abcdef0123456789abcdef0123456789|/\">电驴C</a>")
            append("<input value=\"magnet:?xt=urn:btih:${"b".repeat(40)}\">")
            append("<p>备用hash：$hash</p>") // 与首个磁力重复，应去重
        }
        val res = VideoSniff.extractPageResources(html)
        assertEquals(4, res.size) // 磁力A + 种子 + 电驴 + 磁力B（裸hash去重）
        assertTrue(res.any { it.kind == VideoSniff.PageResource.Kind.MAGNET && it.hash == hash })
        assertTrue(res.any { it.kind == VideoSniff.PageResource.Kind.TORRENT && it.uri.contains(".torrent") })
        assertTrue(res.any { it.kind == VideoSniff.PageResource.Kind.ED2K })
        assertTrue(res.any { it.title.contains("电影") || it.title.contains("A") })
    }

    @Test
    fun `DOM 无资源返回空`() {
        assertTrue(VideoSniff.extractPageResources("<html><body>普通网页</body></html>").isEmpty())
    }
}
