package com.swift.download.magnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回归测试：聚合 —— infohash 去重 / 来源合并 / 排序 / 非法 hash 过滤（v2.0 首轮发现的两个真实 bug 的守护测试） */
class MagnetSearchTest {

    private fun item(hash: String, name: String = "资源", size: Long = 100, seeders: Int = -1) =
        MagnetParsers.RawItem(hash, name, size, seeders, null)

    @Test
    fun `多源命中同一 hash 去重且来源数累加`() {
        val raw = mapOf(
            "torrentkitty" to listOf(item("A".repeat(40), "电影A", 1024, 5)),
            "dmhy" to listOf(item("a".repeat(40), "电影A完整版名称更长", 2048, 3)),
            "lemon" to listOf(item("B".repeat(40), "孤岛资源", 10, 1))
        )
        val results = MagnetSearch.aggregate(raw)
        assertEquals(2, results.size)
        val merged = results.first { it.hash == "a".repeat(40) }
        assertEquals(2, merged.sourceCount)
        assertEquals(listOf("dmhy", "torrentkitty"), merged.sources)
        // 合并规则：取信息量最大的名称、最大体积、最大做种
        assertEquals("电影A完整版名称更长", merged.name)
        assertEquals(2048, merged.sizeBytes)
        assertEquals(5, merged.seeders)
    }

    @Test
    fun `排序为 来源数降序 再做种数降序 再体积降序`() {
        val raw = mapOf(
            "s1" to listOf(
                item("1".repeat(40), "双源高做种", 100, 50),
                item("2".repeat(40), "双源低做种", 100, 10),
                item("3".repeat(40), "单源高做种", 999, 99)
            ),
            "s2" to listOf(
                item("1".repeat(40), "双源高做种", 100, 50),
                item("2".repeat(40), "双源低做种", 100, 10)
            )
        )
        val r = MagnetSearch.aggregate(raw)
        assertEquals(listOf("双源高做种", "双源低做种", "单源高做种"), r.map { it.name })
    }

    @Test
    fun `非法 hash 不进入聚合`() {
        val raw = mapOf(
            "s1" to listOf(
                item("not-a-hash-at-all"),
                item("9".repeat(39)),          // 长度 39 非法
                item("4".repeat(40), "合法资源", 1, 1)
            )
        )
        val r = MagnetSearch.aggregate(raw)
        assertEquals(1, r.size)
        assertEquals("4".repeat(40), r[0].hash)
    }

    @Test
    fun `结果自带 magnetUri 与来源标识`() {
        val r = MagnetSearch.aggregate(mapOf(
            "torrentkitty" to listOf(item("c".repeat(40), "名称", 5, 2))
        ))
        // v2.3：URI 现补齐 xl（体积）与 tr（tracker），改为校验前缀与关键参数
        assertTrue(r[0].magnetUri.startsWith("magnet:?xt=urn:btih:" + "c".repeat(40)))
        assertTrue(r[0].magnetUri.contains("&dn=%E5%90%8D%E7%A7%B0"))
        assertTrue(r[0].magnetUri.contains("&xl="))
        assertTrue(r[0].sources.contains("torrentkitty"))
    }

    @Test
    fun `空输入与空源安全`() {
        assertEquals(0, MagnetSearch.aggregate(emptyMap()).size)
        assertEquals(0, MagnetSearch.aggregate(mapOf("s" to emptyList())).size)
    }
}
