package com.swift.download.settings

import com.swift.download.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 高频控制项的纯逻辑测试（无需 Android 运行环境） */
class ControlItemsTest {

    @Test
    fun unlimitedNeverSleeps() {
        var sleeps = 0
        val limiter = RateLimiter({ 0L }, { sleeps++ })
        limiter.acquire(1024 * 1024, 0L)
        assertEquals(0, sleeps)
    }

    @Test
    fun rateLimiterPaysForBurstAndCreditsIdleTime() {
        var now = 0L
        var slept = 0L
        val limiter = RateLimiter({ now }, { ms -> slept += ms; now += ms * 1_000_000L })

        // 桶初始为空：1000B @ 1000B/s 需要等 1 秒
        limiter.acquire(1000, 1000L)
        assertEquals(1000L, slept)

        // 连续再取同样额度：再等 1 秒（上一秒的额度已被消费）
        limiter.acquire(1000, 1000L)
        assertEquals(2000L, slept)

        // 空闲 1 秒后桶重新攒满，不应再等待
        now += 1_000_000_000L
        limiter.acquire(1000, 1000L)
        assertEquals(2000L, slept)
    }

    @Test
    fun rateLimiterCapsBurstAtOneSecond() {
        var now = 0L
        var slept = 0L
        val limiter = RateLimiter({ now }, { ms -> slept += ms; now += ms * 1_000_000L })
        now += 10_000_000_000L // 空闲 10 秒
        limiter.acquire(1000, 1000L)
        limiter.acquire(1000, 1000L)
        // 桶容量 = 1 秒额度，第二个 1000B 仍需等待，证明不会无限囤积
        assertEquals(1000L, slept)
    }

    @Test
    fun wifiOnlyBlocksMobileButAllowsUnknown() {
        val blocked = DownloadRules.decideStart(
            600, false, NetworkRule.WIFI_ONLY, false, 0, 1439
        )
        assertFalse(blocked.allowed)
        assertEquals(DownloadRules.WAIT_WIFI, blocked.waitReason)

        val unknown = DownloadRules.decideStart(
            600, null, NetworkRule.WIFI_ONLY, false, 0, 1439
        )
        assertTrue(unknown.allowed)
    }

    @Test
    fun scheduleWindowSupportsWrapAround() {
        assertTrue(DownloadRules.inWindow(23 * 60, 23 * 60, 7 * 60))
        assertTrue(DownloadRules.inWindow(3 * 60, 23 * 60, 7 * 60))
        assertFalse(DownloadRules.inWindow(12 * 60, 23 * 60, 7 * 60))
        assertTrue(DownloadRules.inWindow(12 * 60, 0, 1439))
        // 起止相同视为全天开放
        assertTrue(DownloadRules.inWindow(12 * 60, 600, 600))
    }

    @Test
    fun categoryMapsByExtension() {
        assertEquals("视频", DownloadRules.categoryOf("电影.MKV"))
        assertEquals("音频", DownloadRules.categoryOf("歌.mp3"))
        assertEquals("图片", DownloadRules.categoryOf("图.webp"))
        assertEquals("文档", DownloadRules.categoryOf("书.pdf"))
        assertEquals("压缩包", DownloadRules.categoryOf("包.7z"))
        assertEquals("安装包", DownloadRules.categoryOf("app.apk"))
        assertEquals("", DownloadRules.categoryOf("未知.bin"))
    }

    @Test
    fun refreshUrlOnlyAcceptsHttp() {
        assertEquals("https://a.test/f", DownloadRules.normalizeRefreshedUrl("  https://a.test/f  "))
        assertNull(DownloadRules.normalizeRefreshedUrl("magnet:?xt=urn:btih:abc"))
        assertNull(DownloadRules.normalizeRefreshedUrl("ftp://a.test/f"))
    }

    @Test
    fun onlyRunningTasksResumeAfterRestart() {
        assertTrue(DownloadRules.shouldResumeAfterRestart(DownloadTask.Status.RUNNING))
        assertTrue(DownloadRules.shouldResumeAfterRestart(DownloadTask.Status.PROBING))
        assertFalse(DownloadRules.shouldResumeAfterRestart(DownloadTask.Status.PAUSED))
        assertFalse(DownloadRules.shouldResumeAfterRestart(DownloadTask.Status.QUEUED))
        assertFalse(DownloadRules.shouldResumeAfterRestart(DownloadTask.Status.COMPLETED))
        assertFalse(DownloadRules.shouldResumeAfterRestart(DownloadTask.Status.FAILED))
    }
}
