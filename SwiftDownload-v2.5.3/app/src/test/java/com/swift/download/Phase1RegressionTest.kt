package com.swift.download

import com.swift.download.bt.ListenerOnceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.6.0 Phase 1 的纯 JVM 回归：
 *  - Task 1.2：Content-Disposition 改名避让
 *  - Task 1.3：BT listener 只注册一次、ticker 不覆盖暂停/失败终态
 */
class Phase1RegressionTest {

    @Test
    fun `改名遇到同名正式文件与part自动追加序号`() {
        val dir = createTempDir()
        File(dir, "movie.mp4").writeText("别人的完成文件")
        File(dir, "movie (2).mp4.part").writeBytes(ByteArray(1))
        assertEquals("movie (3).mp4", DownloadEngine.avoidNameCollision(dir, "movie.mp4"))
    }

    @Test
    fun `无冲突时保持原名`() {
        val dir = createTempDir()
        assertEquals("movie.mp4", DownloadEngine.avoidNameCollision(dir, "movie.mp4"))
    }

    @Test
    fun `任务自己的原路径不算冲突`() {
        val dir = createTempDir()
        val own = File(dir, "movie.mp4").apply { writeText("自己的文件") }
        assertEquals(
            "movie.mp4",
            DownloadEngine.avoidNameCollision(dir, "movie.mp4", own.absolutePath)
        )
    }

    @Test
    fun `无扩展名文件也能避让`() {
        val dir = createTempDir()
        File(dir, "README").writeText("x")
        assertEquals("README (2)", DownloadEngine.avoidNameCollision(dir, "README"))
    }

    @Test
    fun `BT监听器在多次shutdown后重启只注册一次`() {
        val guard = ListenerOnceGuard()
        var registrations = 0
        // 模拟 shutdown → ensureStarted 两轮
        repeat(3) { guard.registerOnce { registrations++ } }
        assertEquals(1, registrations)
        assertTrue(guard.isRegistered)
    }

    @Test
    fun `ticker不得覆盖暂停与失败任务的进度与错误`() {
        assertFalse(BtTaskRules.canWriteDbProgress(DownloadTask.Status.PAUSED))
        assertFalse(BtTaskRules.canWriteDbProgress(DownloadTask.Status.FAILED))
        assertFalse(BtTaskRules.canFinish(DownloadTask.Status.PAUSED))
        assertFalse(BtTaskRules.canFinish(DownloadTask.Status.FAILED))
        assertTrue(BtTaskRules.canWriteDbProgress(DownloadTask.Status.RUNNING))
        assertTrue(BtTaskRules.canWriteDbProgress(DownloadTask.Status.PROBING))
        assertTrue(BtTaskRules.canFinish(DownloadTask.Status.RUNNING))
    }
}
