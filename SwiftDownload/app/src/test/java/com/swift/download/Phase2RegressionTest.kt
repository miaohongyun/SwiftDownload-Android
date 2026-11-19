package com.swift.download

import com.swift.download.settings.DownloadRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.6.0 Phase 2 的纯 JVM 回归：UA 统一、v4 增量列、并发队列、MediaStore 路径。
 * 真正走网络的场景由 DownloadIntegrityTest 的本地服务器覆盖。
 */
class Phase2RegressionTest {

    @Test
    fun `统一UA与WebView使用同一字符串`() {
        val ua = HttpUtil.USER_AGENT
        assertTrue(ua.contains("(KHTML, like Gecko)"))
        assertTrue(ua.contains("Chrome/124.0 Mobile Safari/537.36"))
        assertTrue(ua.startsWith("Mozilla/5.0 (Linux; Android 14)"))
    }

    @Test
    fun `数据库v4只做增量ALTER且保留旧列`() {
        // v2.7 Stage1.4 起 DB_VERSION 升到 5（新增 final_url），v4 迁移脚本本身不变
        assertEquals(5, DownloadDatabase.DB_VERSION)
        assertEquals(3, DownloadDatabase.MIGRATE_V4.size)
        assertTrue(DownloadDatabase.MIGRATE_V4.all { it.startsWith("ALTER TABLE tasks ADD COLUMN") })
        val sql = DownloadDatabase.MIGRATE_V4.joinToString("\n")
        assertTrue(sql.contains("cookie"))
        assertTrue(sql.contains("user_agent"))
        assertTrue(sql.contains("export_uri"))
        assertFalse(sql.contains("DROP"))
        assertFalse(sql.contains("CREATE TABLE"))
        // v5 同样是纯增量 ALTER
        assertEquals(1, DownloadDatabase.MIGRATE_V5.size)
        assertTrue(DownloadDatabase.MIGRATE_V5.all { it.startsWith("ALTER TABLE tasks ADD COLUMN") })
        assertTrue(DownloadDatabase.MIGRATE_V5.joinToString().contains("final_url"))
    }

    @Test
    fun `新任务默认不带Cookie与导出URI`() {
        val t = DownloadTask(
            id = 1, url = "http://x/f", fileName = "f", savePath = "/tmp/f",
            totalBytes = -1, downloadedBytes = 0, status = DownloadTask.Status.QUEUED,
            threads = 4, createdAt = 0
        )
        assertEquals("", t.cookie)
        assertEquals("", t.userAgent)
        assertEquals("", t.exportUri)
    }

    @Test
    fun `并发上限归一化与满队列判定`() {
        assertEquals(1, QueueRules.normalize(0))
        assertEquals(5, QueueRules.normalize(99))
        assertEquals(3, QueueRules.normalize(3))
        assertFalse(QueueRules.isFull(2, 3))
        assertTrue(QueueRules.isFull(3, 3))
        assertTrue(QueueRules.isFull(4, 3))
    }

    @Test
    fun `等待空闲队列也属于可恢复排队原因`() {
        assertTrue(DownloadRules.isWaiting(DownloadRules.WAIT_QUEUE))
        assertTrue(DownloadRules.isWaiting(DownloadRules.WAIT_WIFI))
        assertFalse(DownloadRules.isWaiting("连接超时"))
    }

    @Test
    fun `MediaStore导出路径含分类子目录`() {
        assertEquals("极速下载/", MediaStoreExport.relativePath(""))
        assertEquals("极速下载/视频/", MediaStoreExport.relativePath("视频"))
        val root = createTempDir()
        assertEquals(
            File(File(root, "极速下载"), "a.mp4").absolutePath,
            MediaStoreExport.publicPath(root, "a.mp4", "")
        )
        assertEquals(
            File(File(File(root, "极速下载"), "视频"), "a.mp4").absolutePath,
            MediaStoreExport.publicPath(root, "a.mp4", "视频")
        )
    }
}
