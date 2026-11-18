package com.swift.download.magnet

import com.swift.download.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回归测试：磁力链接解析与 infohash 归一化（含非法 hash 过滤修复点） */
class MagnetLinksTest {

    @Test
    fun `hex 大小写归一化为 40 位小写`() {
        assertEquals(
            "0123456789abcdef0123456789abcdef01234567",
            MagnetLinks.normalizeHash("0123456789ABCDEF0123456789abcdef01234567")
        )
        assertEquals(40, MagnetLinks.normalizeHash("a".repeat(40))!!.length)
    }

    @Test
    fun `base32 解码为 20 字节 hex`() {
        val b32 = "abcdefghijklmnopqrstuvwxyz234567"
        val hex = MagnetLinks.normalizeHash(b32)
        assertNotNull(hex)
        assertEquals(40, hex!!.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(hex, MagnetLinks.normalizeHash(b32.uppercase()))
    }

    @Test
    fun `非法 hash 被拒绝`() {
        assertNull(MagnetLinks.normalizeHash("xyz"))                     // 长度错误
        assertNull(MagnetLinks.normalizeHash("g".repeat(40)))             // 非 hex 字符
        assertNull(MagnetLinks.normalizeHash("0".repeat(39)))             // 长度 39
        assertNull(MagnetLinks.normalizeHash(""))                          // 空
        // 合法的 32 位 base32 仍应通过
        assertNotNull(MagnetLinks.normalizeHash("a".repeat(32)))
    }

    @Test
    fun `完整磁力链接解析`() {
        val uri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=%E7%94%B5%E5%BD%B1&xl=1024"
        val p = MagnetLinks.parse(uri)!!
        assertEquals("0123456789abcdef0123456789abcdef01234567", p.hashNormalized)
        assertEquals("电影", p.name)
        assertEquals(1024, p.sizeBytes)
        assertTrue(p.magnetUri.startsWith("magnet:?xt=urn:btih:0123"))
        assertNull(MagnetLinks.parse("https://example.com"))
        assertNull(MagnetLinks.parse("magnet:?dn=nohash"))
    }

    @Test
    fun `体积换算与 Format 一致`() {
        assertEquals(1024L * 1024 * 1024, MagnetParsers.sizeToBytes(1.0, "GB"))
        assertEquals(1024L, MagnetParsers.sizeToBytes(1.0, "KB"))
        assertEquals(1L, MagnetParsers.sizeToBytes(1.0, "B"))
        assertEquals("1.50 GB", Format.bytes(1610612736L)) // 1.5 * 1024^3
    }
}
