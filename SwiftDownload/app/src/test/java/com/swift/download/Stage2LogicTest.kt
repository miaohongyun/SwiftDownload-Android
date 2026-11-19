package com.swift.download

import com.swift.download.bt.BtConfigRules
import com.swift.download.bt.PieceRange
import com.swift.download.bt.PublicTrackers
import com.swift.download.player.HlsMux
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v2.7 Stage2：代理/停种/tracker/piece/封装决策 纯逻辑单测 */
class Stage2LogicTest {

    // ---------- 2.1 代理与停种 ----------

    @Test
    fun proxyAppliesRules() {
        assertFalse(BtConfigRules.proxyApplies(false, true))       // 总开关关 → 不生效
        assertFalse(BtConfigRules.proxyApplies(false, false))
        assertTrue(BtConfigRules.proxyApplies(true, true))         // 跟随/显式开
        assertTrue(BtConfigRules.proxyApplies(true, null))         // null=跟随总开关
        assertFalse(BtConfigRules.proxyApplies(true, false))       // 显式关
    }

    @Test
    fun seedingPolicyRules() {
        assertTrue(BtConfigRules.stopOnFinish(BtConfigRules.SeedingPolicy.IMMEDIATE))
        assertFalse(BtConfigRules.stopOnFinish(BtConfigRules.SeedingPolicy.ALWAYS))
        assertFalse(BtConfigRules.stopOnFinish(BtConfigRules.SeedingPolicy.DELAY_5MIN))
        assertTrue(BtConfigRules.shouldScheduleDelay(BtConfigRules.SeedingPolicy.DELAY_5MIN))
        assertEquals(300_000L, BtConfigRules.DELAY_5MIN_MS)
    }

    @Test
    fun uploadLimitConversion() {
        assertEquals(0, BtConfigRules.uploadLimitBps(0))
        assertEquals(0, BtConfigRules.uploadLimitBps(-3))
        assertEquals(2 * 1024 * 1024, BtConfigRules.uploadLimitBps(2))
        assertEquals(1024, BtConfigRules.normalizeUploadMbps(99999))
    }

    @Test
    fun trackerAppend() {
        val t = PublicTrackers.appendToMagnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")
        assertTrue(t.contains("tr="))
        // v2.9.0：HTTP/HTTPS/UDP 混合列表扩到 27 个，5G 下不能只有裸 UDP
        assertTrue(PublicTrackers.TRACKERS.size in 25..40)
        // 40 位 hex 自动补 magnet 前缀并加 tracker
        val fromHash = PublicTrackers.appendToMagnet("0123456789abcdef0123456789abcdef01234567")
        assertTrue(fromHash.startsWith("magnet:?xt=urn:btih:"))
        assertTrue(fromHash.contains("tr="))
    }

    @Test
    fun trackerNotDuplicated() {
        val withTracker = "magnet:?xt=urn:btih:abc&tr=udp%3A%2F%2Fexisting"
        assertEquals(withTracker, PublicTrackers.appendToMagnet(withTracker))
        assertEquals("https://x.test/a.torrent", PublicTrackers.appendToMagnet("https://x.test/a.torrent"))
    }

    // ---------- 2.2 piece 计算 ----------

    @Test
    fun coveringPieces() {
        // piece=1KB，文件从第 5 个 piece 起；请求文件内 [0, 2000) → 全局 piece 5,6
        val r = PieceRange.covering(5, 0L, 2000L, 1024, 100)!!
        assertEquals(5, r.first); assertEquals(6, r.last)
        // 文件内偏移 500..1500 → piece 5,6
        val r2 = PieceRange.covering(5, 500L, 1000L, 1024, 100)!!
        assertEquals(5, r2.first); assertEquals(6, r2.last)
    }

    @Test
    fun coveringClampsToTotal() {
        val r = PieceRange.covering(98, 0L, 100000L, 1024, 100)!!
        assertEquals(98, r.first); assertEquals(99, r.last)
        assertEquals(null, PieceRange.covering(0, 0L, 0L, 1024, 100))
    }

    @Test
    fun prefetchAndEdges() {
        val r = PieceRange.covering(10, 0L, 100L, 1024, 100)!!
        val withRead = PieceRange.withPrefetch(r, 8, 100)
        assertEquals(18, withRead.last)
        val clamped = PieceRange.withPrefetch(PieceRange.Range(95, 96), 8, 100)
        assertEquals(99, clamped.last)
        val edges = PieceRange.edgePieces(10, 100)
        assertTrue(edges.contains(10)); assertTrue(edges.contains(11))
        assertTrue(edges.contains(99)); assertTrue(edges.contains(98))
        assertEquals(4, edges.size)
    }

    // ---------- 2.3 封装决策 ----------

    @Test
    fun hlsMuxDecisions() {
        assertTrue(HlsMux.outputExt(isFmp4 = true, autoRemux = false) == "mp4")
        assertTrue(HlsMux.outputExt(isFmp4 = false, autoRemux = true) == "mp4")
        assertTrue(HlsMux.outputExt(isFmp4 = false, autoRemux = false) == "ts")
        assertEquals("/tmp/v.mp4", HlsMux.withExt("/tmp/v.ts", "mp4"))
        assertTrue(HlsMux.muxTempFile("/tmp/v.mp4").name == "v.mp4.remux.tmp")
    }
}
