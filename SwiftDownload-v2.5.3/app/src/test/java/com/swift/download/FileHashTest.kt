package com.swift.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** v2.6.0：文件哈希计算与比对的纯逻辑测试 */
class FileHashTest {

    @Test
    fun knownVectors() {
        val r = FileHash.of(ByteArrayInputStream("abc".toByteArray(Charsets.US_ASCII)))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", r.md5)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", r.sha1)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", r.sha256)
        assertEquals(3L, r.size)
    }

    @Test
    fun hexAndEmptyFile() {
        assertEquals("00ff10", FileHash.hex(byteArrayOf(0x00, -1, 0x10)))
        val r = FileHash.of(ByteArrayInputStream(ByteArray(0)))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", r.md5)
    }

    @Test
    fun compareAutodetectsAlgorithmAndNormalizesInput() {
        val r = FileHash.of(ByteArrayInputStream("abc".toByteArray(Charsets.US_ASCII)))
        // 大写 + 空格 + sha256: 前缀都能识别
        val m = FileHash.compare(" sha256: BA78 16BF 8F01 CFEA 4141 40DE 5DAE 2223 B003 61A3 9617 7A9C B410 FF61 F200 15AD ", r)
        assertFalse(m == null)
        assertTrue(m!!.ok)
        assertEquals(r.sha256, m.actual)

        assertTrue(FileHash.compare(r.md5.uppercase(), r)!!.ok)
        assertTrue(FileHash.compare(r.sha1, r)!!.ok)
        assertFalse(FileHash.compare("0".repeat(32), r)!!.ok)
    }

    @Test
    fun compareRejectsUnknownLengthOrChars() {
        val r = FileHash.of(ByteArrayInputStream("x".toByteArray()))
        assertNull(FileHash.compare("abc", r))
        assertNull(FileHash.compare("g".repeat(32), r))
    }
}
