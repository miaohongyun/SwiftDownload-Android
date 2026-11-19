package com.swift.download

import com.swift.download.DownloadTask.Status
import com.swift.download.files.ZipExtract
import com.swift.download.settings.BackupCodec
import com.swift.download.settings.BackupManager
import com.swift.download.transfers.TaskFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** v2.7 Stage4：筛选 / Zip Slip 防护 / JSON 备份往返 纯逻辑单测 */
class Stage4LogicTest {

    private fun t(id: Long, status: Status) = DownloadTask(
        id = id, url = "", fileName = "f", savePath = "/tmp/f",
        totalBytes = 0, downloadedBytes = 0, status = status, threads = 4, createdAt = 0
    )

    // ---------- 4.2 筛选 ----------

    @Test
    fun filterMatching() {
        assertTrue(TaskFilters.matches(TaskFilters.Filter.ALL, Status.FAILED))
        assertTrue(TaskFilters.matches(TaskFilters.Filter.ACTIVE, Status.RUNNING))
        assertTrue(TaskFilters.matches(TaskFilters.Filter.ACTIVE, Status.QUEUED))
        assertTrue(TaskFilters.matches(TaskFilters.Filter.ACTIVE, Status.PAUSED))
        assertFalse(TaskFilters.matches(TaskFilters.Filter.ACTIVE, Status.COMPLETED))
        assertTrue(TaskFilters.matches(TaskFilters.Filter.COMPLETED, Status.COMPLETED))
        assertTrue(TaskFilters.matches(TaskFilters.Filter.FAILED, Status.FAILED))
    }

    @Test
    fun filterAndCounts() {
        val tasks = listOf(
            t(1, Status.RUNNING), t(2, Status.QUEUED), t(3, Status.COMPLETED), t(4, Status.FAILED)
        )
        assertEquals(2, TaskFilters.filter(tasks, TaskFilters.Filter.ACTIVE).size)
        assertEquals(1, TaskFilters.filter(tasks, TaskFilters.Filter.COMPLETED).size)
        val c = TaskFilters.counts(tasks)
        assertEquals(4, c.all); assertEquals(2, c.active); assertEquals(1, c.completed); assertEquals(1, c.failed)
    }

    // ---------- 4.3 Zip Slip 防护 ----------

    @Test
    fun zipSlipRejected() {
        assertNull(ZipExtract.safeRelativePath("../evil.txt"))
        assertNull(ZipExtract.safeRelativePath("a/../../evil.txt"))
        assertNull(ZipExtract.safeRelativePath("/absolute/path.txt"))
        assertNull(ZipExtract.safeRelativePath("C:\\windows\\evil.txt"))
        assertNull(ZipExtract.safeRelativePath(".."))
    }

    @Test
    fun zipSafePaths() {
        assertEquals("folder/file.txt", ZipExtract.safeRelativePath("folder/file.txt"))
        assertEquals("folder/file.txt", ZipExtract.safeRelativePath("folder\\file.txt"))
        assertEquals("folder/sub/f.txt", ZipExtract.safeRelativePath("folder/./sub//f.txt"))
        assertEquals("a.txt", ZipExtract.safeRelativePath("a.txt"))
    }

    @Test
    fun insideBoundary() {
        val dest = File("/tmp/dest")
        assertTrue(ZipExtract.isInside(dest, File("/tmp/dest/a/b.txt")))
        assertFalse(ZipExtract.isInside(dest, File("/tmp/other/x.txt")))
    }

    // ---------- 4.4 备份 JSON 往返 ----------

    @Test
    fun backupRoundTrip() {
        val data = BackupCodec.BackupData(
            version = BackupCodec.VERSION,
            timestamp = 1_700_000_000_000L,
            sites = listOf(BackupCodec.Site("必应", "https://www.bing.com")),
            magnetOverrides = listOf(BackupCodec.MagnetOverride("dmhy", "https://x/{kw}", false)),
            adRules = "# comment\nads.example.com",
            settings = linkedMapOf("wifi_only" to "b:true", "proxy_port" to "i:7890")
        )
        val json = BackupCodec.toJson(data)
        val back = BackupCodec.fromJson(json)
        assertEquals(data.version, back.version)
        assertEquals(data.timestamp, back.timestamp)
        assertEquals(1, back.sites.size)
        assertEquals("必应", back.sites[0].name)
        assertEquals("dmhy", back.magnetOverrides[0].id)
        assertFalse(back.magnetOverrides[0].enabled)
        assertEquals(data.adRules, back.adRules)
        assertEquals("b:true", back.settings["wifi_only"])
        assertEquals("i:7890", back.settings["proxy_port"])
    }

    @Test
    fun backupVersionAndFormatCheck() {
        val good = BackupCodec.toJson(BackupCodec.BackupData.empty())
        assertTrue(BackupCodec.fromJson(good).version == BackupCodec.VERSION)

        val future = good.replace("\"version\":1", "\"version\":99")
        try {
            BackupCodec.fromJson(future)
            fail("应拒绝未来版本")
        } catch (e: BackupCodec.IncompatibleVersion) {
            assertEquals(99, e.found)
        }

        try {
            BackupCodec.fromJson("{\"format\":\"Other\"}")
            fail("应拒绝非备份格式")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun backupFileNameFormat() {
        val name = BackupManager.backupFileName(1_700_000_000_000L)
        assertTrue(name.startsWith("SwiftDownload_Backup_"))
        assertTrue(name.endsWith(".json"))
        // 形如 SwiftDownload_Backup_20231114_2200.json
        assertTrue(name.matches(Regex("SwiftDownload_Backup_\\d{8}_\\d{4}\\.json")))
    }
}
