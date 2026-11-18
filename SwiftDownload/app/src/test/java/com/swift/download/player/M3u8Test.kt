package com.swift.download.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** m3u8 解析与识别的纯逻辑测试 */
class M3u8Test {

    @Test
    fun masterPlaylistPicksHighestBandwidth() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080
            high/index.m3u8
        """.trimIndent()
        val p = M3u8.parse("https://site.test/v/master.m3u8", text)
        assertTrue(p.isMaster)
        assertEquals(2, p.variants.size)
        assertEquals("https://site.test/v/high/index.m3u8", M3u8.pickBestUrl(p))
    }

    @Test
    fun mediaPlaylistResolvesRelativeAndAbsoluteSegments() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            seg0.ts
            #EXTINF:10.0,
            https://cdn.test/abs/seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = M3u8.parse("https://site.test/v/index.m3u8", text)
        assertFalse(p.isMaster)
        assertEquals(listOf(
            "https://site.test/v/seg0.ts",
            "https://cdn.test/abs/seg1.ts"
        ), p.segments)
    }

    @Test
    fun looksLikeM3u8IgnoresQueryString() {
        assertTrue(M3u8.looksLikeM3u8("https://site.test/v/index.m3u8?token=abc#frag"))
        assertFalse(M3u8.looksLikeM3u8("https://site.test/v/movie.mp4"))
    }

    @Test
    fun resolveFallsBackOnMalformedBase() {
        assertEquals("seg.ts", M3u8.resolve("not a url", "seg.ts"))
    }

    @Test
    fun masterVariantsCarryResolutionLabel() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
            1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            audioish.m3u8
        """.trimIndent()
        val p = M3u8.parse("https://site.test/v/master.m3u8", text)
        assertEquals(3, p.variants.size)
        val top = p.variants.first()
        assertEquals(1920, top.width)
        assertEquals(1080, top.height)
        assertTrue(top.label.startsWith("1080p"))
        assertTrue(top.label.contains("6.0 Mbps"))
        val noRes = p.variants.last()
        assertTrue(noRes.label.startsWith("码率档"))
    }

    @Test
    fun videoSniffDistinguishesMediaFromNoise() {
        assertTrue(VideoSniff.isCandidate("https://x.test/v/index.m3u8?token=abc"))
        assertTrue(VideoSniff.isCandidate("https://x.test/v/movie.mp4"))
        assertFalse(VideoSniff.isCandidate("https://x.test/v/seg123.ts"))
        assertFalse(VideoSniff.isCandidate("https://x.test/v/seg.m4s"))
        assertFalse(VideoSniff.isCandidate("https://x.test/page.html"))
        assertEquals("index.m3u8", VideoSniff.displayName("https://x.test/v/index.m3u8?token=abc"))
        assertEquals("视频", VideoSniff.displayName("https://x.test/v/"))
    }

    @Test
    fun videoProbeDetectsMoovNearStart() {
        assertTrue(VideoProbe.hasMoovNearStart("ftypisommoovtrakmdat".toByteArray()))
        assertFalse(VideoProbe.hasMoovNearStart("ftypisommdatonly".toByteArray()))
        assertFalse(VideoProbe.hasMoovNearStart(ByteArray(2)))
    }
}
