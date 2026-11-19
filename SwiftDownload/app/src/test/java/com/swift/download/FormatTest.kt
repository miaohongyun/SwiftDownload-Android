package com.swift.download

import org.junit.Assert.assertEquals
import org.junit.Test

/** 回归测试：格式化（含 v2.0 修复点 —— 体积统一 1024 进制） */
class FormatTest {

    @Test
    fun `bytes 采用 1024 进制`() {
        assertEquals("999 B", Format.bytes(999))
        assertEquals("1.50 KB", Format.bytes(1536))          // 1536/1024 = 1.5KB
        assertEquals("1.50 MB", Format.bytes(1536L * 1024))
        assertEquals("2.00 GB", Format.bytes(2L * 1024 * 1024 * 1024))
        assertEquals("1.00 TB", Format.bytes(1L * 1024 * 1024 * 1024 * 1024))
        assertEquals("未知", Format.bytes(-1))
    }

    @Test
    fun `speed 格式化`() {
        assertEquals("0 B/s", Format.speed(0))
        assertEquals("1.00 MB/s", Format.speed(1024L * 1024))
    }

    @Test
    fun `eta 使用中文单位`() {
        assertEquals("即将完成", Format.eta(0))
        assertEquals("59 秒", Format.eta(59))
        assertEquals("1 分 5 秒", Format.eta(65))
        assertEquals("2 小时 3 分 4 秒", Format.eta(2 * 3600 + 3 * 60 + 4))
        assertEquals("未知", Format.eta(-3))
    }

    @Test
    fun `Content-Disposition 文件名提取`() {
        assertEquals("book.zip", Format.fileNameFromDisposition("attachment; filename=\"book.zip\""))
        assertEquals("书.zip", Format.fileNameFromDisposition("attachment; filename*=UTF-8''%E4%B9%A6.zip"))
        assertEquals("a b.rar", Format.fileNameFromDisposition("attachment; filename=a b.rar; size=1"))
        assertEquals(null, Format.fileNameFromDisposition(null))
        assertEquals(null, Format.fileNameFromDisposition("inline"))
    }
}
