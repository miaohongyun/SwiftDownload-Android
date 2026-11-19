package com.swift.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回归测试：分段切割与进度文件命名（v1.0 引擎行为的纯函数守护） */
class SegmentsTest {

    @Test
    fun `等分与余数分配`() {
        val segs = DownloadEngine.Segments.split(1000, 4)!!
        assertEquals(4, segs.size)
        // 覆盖完整：[0,999] 无缝
        assertEquals(0L, segs.first()[0])
        assertEquals(999L, segs.last()[1])
        var cursor = 0L
        for (s in segs) {
            assertEquals(cursor, s[0])
            cursor = s[1] + 1
        }
        assertEquals(1000L, cursor)
        // 1000 % 4 == 0 → 每段 250
        for (s in segs) assertEquals(249L, s[1] - s[0])
        // 余数场景：1001 / 3
        val segs2 = DownloadEngine.Segments.split(1001, 3)!!
        assertEquals(3, segs2.size)
        val lens = segs2.map { it[1] - it[0] + 1 }
        assertEquals(listOf(334L, 334L, 333L), lens)
    }

    @Test
    fun `退化条件与进度文件命名`() {
        assertNull(DownloadEngine.Segments.split(-1, 4))
        assertNull(DownloadEngine.Segments.split(0, 4))
        assertNull(DownloadEngine.Segments.split(100, 1))   // 单线程直下
        assertNull(DownloadEngine.Segments.split(100, 0))
        // 线程数大于体积 → 自动收缩为体积数
        val tiny = DownloadEngine.Segments.split(3, 8)!!
        assertEquals(3, tiny.size)
        // .swiftprog 命名（v1.0 同名同义）
        assertEquals("42.swiftprog", DownloadEngine.Segments.progFileName(42))
        assertTrue(DownloadEngine.Segments.progFileName(1).endsWith(".swiftprog"))
    }

    @Test
    fun `URL 文件名提取`() {
        assertEquals("book.zip", nameFromUrl("https://cdn.example.com/files/book.zip?sign=abc"))
        assertEquals("电影.mkv", nameFromUrl("https://x.cn/%E7%94%B5%E5%BD%B1.mkv"))
        assertEquals("download", nameFromUrl("https://example.com/"))
        assertEquals("a.bin", fallbackName("http://t.cn/a.bin#frag"))
    }

    @Test
    fun `SpeedSampler 一秒窗口差分`() {
        val s = DownloadEngine.SpeedSampler()
        assertEquals(0L, s.sample(100, 1_000))     // 首次仅记录
        assertEquals(0L, s.sample(300, 1_500))    // 不足 1 秒 → 返回上次值
        assertEquals(200L, s.sample(300, 2_000))  // (300-100)/1s
        assertEquals(300L, s.sample(600, 3_000))  // (600-300)/1s
        assertEquals(0L, s.sample(500, 6_000))    // 回退保护（差为负 → 0）
    }
}
