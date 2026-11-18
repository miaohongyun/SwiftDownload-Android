package com.swift.download.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v2.2：文件排序比较器单测（临时目录真文件） */
class FileSortsTest {

    private val dir = createTempDir()

    private fun f(name: String, size: Long, time: Long): File =
        File(dir, name).apply {
            writeText("x".repeat(size.toInt().coerceAtLeast(1)))
            setLastModified(time)
        }

    @Test fun `按时间升降`() {
        val a = f("a.txt", 1, 100)
        val b = f("b.txt", 1, 200)
        val c = f("c.txt", 1, 300)
        val list = listOf(a, b, c)
        assertEquals(listOf(c, b, a), list.sortedWith(FileSorts.comparator(FileSorts.Mode.TIME_DESC)))
        assertEquals(listOf(a, b, c), list.sortedWith(FileSorts.comparator(FileSorts.Mode.TIME_ASC)))
    }

    @Test fun `按名称大小写不敏感`() {
        val files = listOf(f("Beta.txt", 1, 1), f("alpha.txt", 1, 1), f("ALPHA2.txt", 1, 1))
        val asc = files.sortedWith(FileSorts.comparator(FileSorts.Mode.NAME_ASC))
        assertEquals("alpha.txt", asc[0].name)
        assertEquals("ALPHA2.txt", asc[1].name)
        assertEquals("Beta.txt", asc[2].name)
        val desc = files.sortedWith(FileSorts.comparator(FileSorts.Mode.NAME_DESC))
        assertEquals("Beta.txt", desc[0].name)
    }

    @Test fun `按大小升降`() {
        val files = listOf(f("big", 300, 1), f("mid", 200, 1), f("small", 10, 1))
        assertEquals(
            listOf("big", "mid", "small"),
            files.sortedWith(FileSorts.comparator(FileSorts.Mode.SIZE_DESC)).map { it.name }
        )
        assertEquals(
            listOf("small", "mid", "big"),
            files.sortedWith(FileSorts.comparator(FileSorts.Mode.SIZE_ASC)).map { it.name }
        )
    }

    @Test fun `Mode 循环切换与持久化名`() {
        val start = FileSorts.Mode.TIME_DESC
        var cur = start
        repeat(FileSorts.Mode.entries.size) { cur = FileSorts.Mode.next(cur) }
        assertEquals(start, cur)
        assertEquals(FileSorts.Mode.TIME_DESC, FileSorts.Mode.fromName(null))
        assertEquals(FileSorts.Mode.TIME_DESC, FileSorts.Mode.fromName("不存在"))
        assertEquals(FileSorts.Mode.SIZE_ASC, FileSorts.Mode.fromName("SIZE_ASC"))
        assertTrue(FileSorts.Mode.TIME_DESC.label.isNotEmpty())
    }
}
