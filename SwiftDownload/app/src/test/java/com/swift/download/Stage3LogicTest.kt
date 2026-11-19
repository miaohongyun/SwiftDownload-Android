package com.swift.download

import com.swift.download.browser.AdBlocker
import com.swift.download.player.VideoSniff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.7 Stage3：广告拦截规则 / scheme 白名单 / 多链接批量提取 纯逻辑单测 */
class Stage3LogicTest {

    // ---------- 3.1 广告拦截 ----------

    @Test
    fun hostExtraction() {
        assertEquals("ad.doubleclick.net", AdBlocker.hostOf("https://ad.doubleclick.net/page?x=1"))
        assertEquals("www.example.com", AdBlocker.hostOf("http://www.example.com:8080/a/b"))
        assertEquals("sub.google-analytics.com", AdBlocker.hostOf("//sub.google-analytics.com/x"))
        assertEquals("", AdBlocker.hostOf("about:home"))
        assertEquals("", AdBlocker.hostOf("magnet:?xt=urn:btih:abc"))
    }

    @Test
    fun hostMatchSuffixNotOvermatch() {
        assertTrue(AdBlocker.hostMatches("a.doubleclick.net", "doubleclick.net"))
        assertTrue(AdBlocker.hostMatches("google-analytics.com", ".google-analytics.com"))
        assertFalse(AdBlocker.hostMatches("notdoubleclick.net", "doubleclick.net"))
        assertFalse(AdBlocker.hostMatches("example.com", "doubleclick.net"))
    }

    @Test
    fun builtinAdBlock() {
        assertTrue(AdBlocker.isBlocked("https://www.google-analytics.com/analytics.js"))
        assertTrue(AdBlocker.isBlocked("https://static.doubleclick.net/instream/ad.js"))
        assertTrue(AdBlocker.isBlocked("https://site.com/ads.js"))
        assertFalse(AdBlocker.isBlocked("https://www.example.com/video.m3u8"))
        assertFalse(AdBlocker.isBlocked("https://cdn.example.com/player.js"))
    }

    @Test
    fun customRulesAndNormalization() {
        val rules = AdBlocker.normalizeRules("""
            # 注释行
            ads.myhost.com

            ||tracker.example.org
        """.trimIndent())
        assertEquals(2, rules.size)
        assertTrue(AdBlocker.isBlocked("https://ads.myhost.com/x", rules))
        assertTrue(AdBlocker.isBlocked("https://a.tracker.example.org/z", rules))
        assertFalse(AdBlocker.isBlocked("https://ok.example.com", rules))
    }

    @Test
    fun schemeWhitelist() {
        assertTrue(AdBlocker.isAllowedScheme("https://example.com"))
        assertTrue(AdBlocker.isAllowedScheme("http://example.com/a"))
        assertTrue(AdBlocker.isAllowedScheme("magnet:?xt=urn:btih:abc"))
        assertTrue(AdBlocker.isAllowedScheme("about:blank"))
        assertFalse(AdBlocker.isAllowedScheme("intent://open/#Intent"))
        assertFalse(AdBlocker.isAllowedScheme("market://details?id=x"))
        assertFalse(AdBlocker.isAllowedScheme("weixin://dl/business"))
        assertFalse(AdBlocker.isAllowedScheme("javascript:alert(1)"))
    }

    // ---------- 3.3 多链接批量提取 ----------

    @Test
    fun linkKinds() {
        assertEquals(VideoSniff.LinkKind.HTTP, VideoSniff.kindOf("https://example.com/a.mp4"))
        assertEquals(VideoSniff.LinkKind.HTTP, VideoSniff.kindOf("http://example.com/a.m3u8"))
        assertEquals(
            VideoSniff.LinkKind.MAGNET,
            VideoSniff.kindOf("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")
        )
        assertEquals(VideoSniff.LinkKind.NONE, VideoSniff.kindOf("ftp://example.com/a"))
        assertEquals(VideoSniff.LinkKind.NONE, VideoSniff.kindOf("随便一段文字"))
    }

    @Test
    fun batchExtractionDedup() {
        val text = """
            资源1 https://a.example.com/movie.mp4
            备用 https://a.example.com/movie.mp4 重复直链
            磁力 magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567
            普通网址 https://www.example.com 也提取
        """.trimIndent()
        val links = VideoSniff.extractLinks(text)
        assertEquals(3, links.size) // 去重后：mp4（保留首次）、magnet、首页
        assertTrue(links.any { VideoSniff.kindOf(it) == VideoSniff.LinkKind.MAGNET })
        assertEquals(2, links.count { VideoSniff.kindOf(it) == VideoSniff.LinkKind.HTTP })
    }

    @Test
    fun shouldBatchMode() {
        assertFalse(VideoSniff.shouldBatch("只有一条 https://a.com/x.mp4"))
        assertTrue(VideoSniff.shouldBatch("https://a.com/x.mp4 magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"))
        assertFalse(VideoSniff.shouldBatch("没有任何链接的纯文字"))
    }
}
