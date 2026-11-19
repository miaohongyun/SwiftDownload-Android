package com.swift.download

import com.swift.download.bt.PublicTrackers
import com.swift.download.bt.TorrentEngine
import com.swift.download.magnet.MagnetSourceRules
import com.swift.download.magnet.MagnetSources
import com.swift.download.magnet.SiteDirectSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.9.0：BT 升级 + 手机单手交互 + 影视站直搜的纯 JVM 回归门禁 */
class V29RegressionTest {

    /* ---------- BT：新版依赖与混合 tracker ---------- */

    @Test
    fun `内置 tracker 必须同时包含 HTTP HTTPS UDP 三类`() {
        assertTrue(PublicTrackers.TRACKERS.any { it.startsWith("http://") })
        assertTrue(PublicTrackers.TRACKERS.any { it.startsWith("https://") })
        assertTrue(PublicTrackers.TRACKERS.any { it.startsWith("udp://") })
        assertTrue(PublicTrackers.TRACKERS.size >= 24)
    }

    @Test
    fun `纯 infohash 自动补齐磁力协议与 tracker`() {
        val hash = "a".repeat(40)
        val uri = PublicTrackers.appendToMagnet(hash)
        assertTrue(uri.startsWith("magnet:?xt=urn:btih:$hash"))
        assertTrue(uri.contains("tr="))
        assertTrue(uri.contains("https%3A%2F%2F") || uri.contains("https://"))
    }

    @Test
    fun `BT 诊断字段包含 tracker 命中数`() {
        val p = TorrentEngine.BtProgress(
            downloaded = 0, total = 0, speedBps = 0,
            seeds = 0, peers = 0, hasMetadata = false, finished = false, name = "",
            dhtNodes = 0, trackerCount = 12, stateLabel = "连接节点 · 获取元数据中"
        )
        assertEquals(12, p.trackerCount)
        assertEquals(0L, p.dhtNodes)
    }

    @Test
    fun `DHT 引导节点保持四个公共节点`() {
        val nodes = TorrentEngine.DHT_BOOTSTRAP.split(',')
        assertEquals(4, nodes.size)
        assertTrue(nodes.all { it.contains(":") })
    }

    /* ---------- 磁力源：清理死源 + 需代理标注 ---------- */

    @Test
    fun `默认源已移除长期失效域名`() {
        val urls = MagnetSources.defaults.map { it.searchUrl }
        assertTrue(urls.none { it.contains("clm5") || it.contains("lemonbt") || it.contains("skrbt") })
        assertEquals(2, MagnetSources.defaults.size)
    }

    @Test
    fun `境外默认源标注需代理`() {
        assertTrue(MagnetSources.defaults.all { it.proxyRequired })
        assertTrue(MagnetSourceRules.statusLabel(
            com.swift.download.magnet.MagnetSearch.SourceStatus.TIMEOUT, 0
        ).contains("超时"))
    }

    /* ---------- 影视站直搜 ---------- */

    @Test
    fun `影视站模板关键词替换与校验`() {
        val site = SiteDirectSearch.parseTemplate("高清族=https://hdzu.cc/?s={kw}")
        assertEquals("高清族", site?.name)
        assertEquals("https://hdzu.cc/?s=%E6%90%9C%E7%B4%A2", site?.urlFor("搜索"))
        assertEquals(null, SiteDirectSearch.parseTemplate("无占位符=https://hdzu.cc/"))
        assertEquals(null, SiteDirectSearch.parseTemplate("=https://hdzu.cc/?s={kw}"))
    }

    @Test
    fun `自定义站点覆盖内置同名模板`() {
        val merged = SiteDirectSearch.merged("高清族=https://custom.example/?q={kw}")
        assertEquals(3, merged.size)
        assertEquals("https://custom.example/?q={kw}", merged.first { it.name == "高清族" }.searchUrl)
    }

    @Test
    fun `全源超时提示区分代理开关`() {
        val off = SiteDirectSearch.unreachableHint(false)
        val on = SiteDirectSearch.unreachableHint(true)
        assertTrue(off.contains("影视站直搜"))
        assertTrue(off.contains("未开启代理"))
        assertTrue(on.contains("代理地址"))
    }
}
