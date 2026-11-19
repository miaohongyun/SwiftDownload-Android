package com.swift.download.magnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回归测试：规则驱动的 HTML 解析（TorrentKitty / 动漫花园 / 通用兜底） */
class MagnetParsersTest {

    private val H1 = "0123456789abcdef0123456789abcdef01234567"
    private val H2 = "89abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `TorrentKitty 行解析`() {
        val html = """
            <table class="table"><tr><th>Name</th><th>Date</th><th>Size</th><th>Link</th></tr>
            <tr>
              <td><a href="/detail/xxx">复仇者联盟 4K 蓝光</a></td>
              <td>2026-01-15</td>
              <td>3.5 GB</td>
              <td><a href="magnet:?xt=urn:btih:$H1&dn=avengers4k">磁力链接</a></td>
            </tr>
            <tr>
              <td><a href="/detail/yyy">星球大战合集</a></td>
              <td>2025-12-01</td>
              <td>820 MB</td>
              <td><a href="magnet:?xt=urn:btih:$H2">磁力链接</a></td>
            </tr>
            </table>
        """.trimIndent()
        val items = MagnetParsers.parse("torrentkitty", html)
        assertEquals(2, items.size)
        val first = items.first { it.hash == H1 }
        assertEquals("复仇者联盟 4K 蓝光", first.name)
        assertEquals(3758096384L, first.sizeBytes)   // 3.5 * 1024^3
        assertEquals("2026-01-15", first.date)
    }

    @Test
    fun `动漫花园行解析（title 优先）`() {
        val html = """
            <table><tr>
              <td><a class="topic" title="【四月新番】天使降临 1080P" href="/topics/view/1">天使降临</a></td>
              <td>2026-02-03</td>
              <td><a href="magnet:?xt=urn:btih:$H2&dn=%E5%A4%A9%E4%BD%BF">磁力</a></td>
            </tr></table>
        """.trimIndent()
        val items = MagnetParsers.parse("dmhy", html)
        assertEquals(1, items.size)
        assertEquals("【四月新番】天使降临 1080P", items[0].name)
        assertEquals(H2, items[0].hash)
    }

    @Test
    fun `通用兜底磁力扫描`() {
        val html = """
            <div class="list">
              <p>随便什么内容</p>
              <a href="magnet:?xt=urn:btih:$H1">链接一</a>
              <span>另一些文本 1.2 GB</span>
              <a href="magnet:?xt=urn:btih:$H1">重复 hash 应去重</a>
            </div>
        """.trimIndent()
        val items = MagnetParsers.parse("generic", html)
        assertEquals(1, items.size)
        assertEquals(H1, items[0].hash)
        assertEquals(1288490188L, items[0].sizeBytes)  // 1.2 * 1024^3
    }

    @Test
    fun `结构化失败自动退化通用扫描`() {
        // rowtable 规则找不到行 → 退化 generic 仍能扫出 magnet
        val html = """<div><a href="magnet:?xt=urn:btih:$H1&dn=xxx">dl</a></div>"""
        val items = MagnetParsers.parse("rowtable", html)
        assertEquals(1, items.size)
        assertEquals(H1, items[0].hash)
    }

    @Test
    fun `stripTags 与大小提取`() {
        assertEquals("纯 文本", MagnetParsers.stripTags("<b>纯</b>&nbsp;<i>文本</i>"))
        assertEquals(2048L, MagnetParsers.sizeOf("2.00 KB 剩余"))
        assertEquals(-1L, MagnetParsers.sizeOf("无大小"))
        assertTrue(MagnetParsers.sizeToBytes(2.0, "MB") == 2L * 1024 * 1024)
    }
}
