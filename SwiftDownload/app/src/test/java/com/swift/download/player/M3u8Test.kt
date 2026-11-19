package com.swift.download.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun videoSniffSupportsMoreFormatsAndExtractsLinks() {
        assertTrue(VideoSniff.isCandidate("https://x.test/a.MKV"))
        assertTrue(VideoSniff.isCandidate("https://x.test/a.flv?t=1"))
        assertTrue(VideoSniff.isCandidate("https://x.test/a.mov"))
        assertTrue(VideoSniff.isCandidate("https://x.test/a.webm"))
        assertFalse(VideoSniff.isCandidate("https://x.test/a.txt"))

        val shared = "看这个 https://x.test/v/movie.mp4 或者 https://y.test/live/index.m3u8 谢谢！"
        val links = VideoSniff.extractLinks(shared)
        assertEquals(2, links.size)
        assertTrue(links.contains("https://x.test/v/movie.mp4"))
        assertTrue(links.contains("https://y.test/live/index.m3u8"))
        val mixed = "复制链接：magnet:?xt=urn:btih:ABCDEF1234567890 口令 123456"
        val mixedLinks = VideoSniff.extractLinks(mixed)
        assertEquals(1, mixedLinks.size)
        assertTrue(VideoSniff.isMagnet(mixedLinks[0]))
    }

    @Test
    fun videoProbeDetectsMoovNearStart() {
        assertTrue(VideoProbe.hasMoovNearStart("ftypisommoovtrakmdat".toByteArray()))
        assertFalse(VideoProbe.hasMoovNearStart("ftypisommdatonly".toByteArray()))
        assertFalse(VideoProbe.hasMoovNearStart(ByteArray(2)))
    }

    /* ---- v2.5.2：不支持的 HLS 特性必须被识别出来（否则会下出一个不可播放的"已完成"文件） ---- */

    @Test
    fun aes128IsNowSupported() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x00000000000000000000000000000001
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()
        val p = M3u8.parse("https://site.test/v/index.m3u8", text)
        assertEquals(null, p.unsupported)
        val seg = p.items.single()
        assertEquals("AES-128", seg.key?.method)
        assertEquals("https://site.test/v/key.bin", seg.key?.uri)
        assertEquals("0x00000000000000000000000000000001", seg.key?.iv)
    }

    @Test
    fun sampleAesIsStillRejected() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="key.bin"
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()
        assertTrue(M3u8.parse("https://site.test/v/index.m3u8", text)
            .unsupported!!.contains("SAMPLE-AES"))
    }

    @Test
    fun allowsKeyMethodNone() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()
        assertEquals(null, M3u8.parse("https://site.test/v/index.m3u8", text).unsupported)
    }

    @Test
    fun parsesFmp4InitSegment() {
        // v2.7 Stage2.3：EXT-X-MAP 现在被支持，不再拒绝；解析出绝对 URI 与 BYTERANGE
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="728@0"
            #EXTINF:10.0,
            seg0.m4s
        """.trimIndent()
        val p = M3u8.parse("https://site.test/v/index.m3u8", text)
        assertNull(p.unsupported)
        assertEquals("https://site.test/v/init.mp4", p.map?.uri)
        assertEquals(728L, p.map?.byteRange?.length)
        assertEquals(0L, p.map?.byteRange?.offset)
    }

    @Test
    fun byteRangeIsParsedWithImplicitOffset() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXTINF:10.0,
            #EXT-X-BYTERANGE:1000@0
            all.ts
            #EXTINF:10.0,
            #EXT-X-BYTERANGE:2000
            all.ts
        """.trimIndent()
        val p = M3u8.parse("https://site.test/v/index.m3u8", text)
        assertEquals(null, p.unsupported)
        assertEquals(2, p.items.size)
        assertEquals(M3u8.ByteRange(1000, 0), p.items[0].byteRange)
        // 未写 @ 时紧接上一个分片：0 + 1000
        assertEquals(M3u8.ByteRange(2000, 1000), p.items[1].byteRange)
        assertEquals(7L, p.items[0].sequence)
        assertEquals(8L, p.items[1].sequence)
    }

    @Test
    fun keyScopeResetsWithMethodNone() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="k.bin"
            a.ts
            #EXT-X-KEY:METHOD=NONE
            b.ts
        """.trimIndent()
        val p = M3u8.parse("https://site.test/v/index.m3u8", text)
        assertEquals("AES-128", p.items[0].key?.method)
        assertEquals(null, p.items[1].key)
    }
}
