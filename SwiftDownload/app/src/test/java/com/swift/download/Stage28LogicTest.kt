package com.swift.download

import com.swift.download.browser.AdBlocker
import com.swift.download.bt.TorrentEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.8 模块一~三：新增纯逻辑单测（DOM 注入 / DHT 配置 / JS 转义） */
class Stage28LogicTest {

    // ---------- 模块三：DOM 注入去广告 ----------

    @Test
    fun lightInjectionHasAdsbygoogleButNotAggressive() {
        val js = AdBlocker.domInjectionJs(strong = false)
        assertTrue(js.contains("adsbygoogle"))
        assertTrue(js.contains("display:none!important"))
        assertTrue(js.contains("addEventListener"))
        // 轻度模式不含强力选择器
        assertFalse(js.contains("popup"))
        assertFalse(js.contains("intent://"))
    }

    @Test
    fun strongInjectionAddsPopupsAndSchemeBlock() {
        val js = AdBlocker.domInjectionJs(strong = true)
        assertTrue(js.contains("popup"))
        assertTrue(js.contains("intent:"))
        assertTrue(js.contains("baiduboxapp:"))
        assertTrue(js.contains("thunder:"))
    }

    @Test
    fun jsStringEscaping() {
        assertEquals("'abc'", AdBlocker.jsString("abc"))
        assertEquals("'a\\'b'", AdBlocker.jsString("a'b"))
        assertEquals("'a\\\\b'", AdBlocker.jsString("a\\b"))
        assertTrue(AdBlocker.jsString("x\ny").contains("\\n"))
    }

    @Test
    fun hideSelectorsWellFormed() {
        assertTrue(AdBlocker.LIGHT_HIDE_SELECTORS.isNotEmpty())
        assertTrue(AdBlocker.STRONG_HIDE_SELECTORS.isNotEmpty())
        assertTrue(AdBlocker.LIGHT_HIDE_SELECTORS.any { it.contains("adsbygoogle") })
    }

    // ---------- 模块一：BT 网络引导配置 ----------

    @Test
    fun dhtBootstrapNodesComplete() {
        val nodes = TorrentEngine.DHT_BOOTSTRAP.split(',')
        assertEquals(4, nodes.size)
        assertTrue(nodes.all { it.contains(":") })
        assertTrue(TorrentEngine.DHT_BOOTSTRAP.contains("router.bittorrent.com"))
        assertTrue(TorrentEngine.DHT_BOOTSTRAP.contains("dht.transmissionbt.com"))
    }

    @Test
    fun btTuningValues() {
        assertTrue(TorrentEngine.MAX_CONNECTIONS >= 200)
        assertEquals(16, TorrentEngine.CACHE_SIZE_MB)
        assertEquals(180, TorrentEngine.MAX_METADATA_WAIT_SECS)
    }
}
