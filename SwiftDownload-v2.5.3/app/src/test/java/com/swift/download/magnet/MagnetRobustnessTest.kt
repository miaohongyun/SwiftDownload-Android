package com.swift.download.magnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 磁力搜索健壮性：URI 元信息补全 + 可插拔解析器 */
class MagnetRobustnessTest {

    @Test
    fun resultUriCarriesSizeAndTrackers() {
        val r = MagnetSearch.Result(
            hash = "a".repeat(40),
            name = "测试资源",
            sizeBytes = 1024L,
            seeders = 3,
            sources = listOf("dmhy"),
            date = null
        )
        assertTrue(r.magnetUri.startsWith("magnet:?xt=urn:btih:${"a".repeat(40)}"))
        assertTrue(r.magnetUri.contains("&xl=1024"))
        assertTrue(r.magnetUri.contains("&dn="))
        assertTrue(r.magnetUri.contains("&tr="))
    }

    @Test
    fun customParserOverridesBuiltinRule() {
        val name = "unit-test-parser-" + System.nanoTime()
        MagnetParsers.register(name) { html ->
            if (html.contains("hit")) listOf(
                MagnetParsers.RawItem("b".repeat(40), "自定义", 2048L, 5, null)
            ) else emptyList()
        }
        val rows = MagnetParsers.parse(name, "<html>hit</html>")
        assertEquals(1, rows.size)
        assertEquals("自定义", rows[0].name)

        val empty = MagnetParsers.parse(name, "<html>miss</html>")
        // 结构化结果为空时按既有设计退化为通用磁力扫描，空页面仍为空
        assertTrue(empty.isEmpty())
    }
}
