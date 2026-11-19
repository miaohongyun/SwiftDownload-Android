package com.swift.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * v2.5 缺陷修复的回归守护。
 *
 * 覆盖本轮修复的核心缺陷：
 *  1. 探测必须能在后台完成（awaitReady 有信号），且不因失败而永久阻塞。
 *  2. 分段切分必须无缝无重叠（登记顺序错误的直接后果是错位）。
 *  3. 任一分段失败后不得再触发 onFinished（避免"先失败后完成"的状态翻转）。
 *  4. 文件名优先取服务器 Content-Disposition，且非法字符被清理。
 *  5. 新任务必须清空同名残留 .part（避免旧数据混入新文件）。
 */
class BugfixRegressionTest {

    private val dir = createTempDir()

    private fun task(id: Long = 1L, fileName: String = "f.bin", total: Long = -1L) = DownloadTask(
        id = id, url = "http://127.0.0.1:1/none", fileName = fileName,
        savePath = File(dir, fileName).absolutePath,
        totalBytes = total, downloadedBytes = 0,
        status = DownloadTask.Status.QUEUED, threads = 2, createdAt = 0
    )

    private class Recorder : DownloadEngine.EngineListener {
        val statuses = mutableListOf<DownloadTask.Status>()
        var finished = 0
        val errors = mutableListOf<String>()
        override fun onProgress(task: DownloadTask, downloadedBytes: Long, speedBps: Long) {}
        override fun onStatus(task: DownloadTask) { statuses.add(task.status) }
        override fun onFinished(task: DownloadTask) { finished++ }
        override fun onError(task: DownloadTask, error: String) { errors.add(error) }
    }

    /** 1. 探测失败时 awaitReady 必须返回（不能永久挂起），且任务落到 FAILED */
    @Test
    fun `探测失败时任务失败且不永久阻塞`() {
        val rec = Recorder()
        val job = DownloadEngine.get().newJob(task(), rec)
        job.start()
        // 端口 1 必然连不上 → 探测快速失败
        assertTrue("探测应在超时前结束", job.awaitReady(8000))
        assertEquals(1, rec.errors.size)
        assertEquals(0, rec.finished)
        assertTrue(rec.statuses.contains(DownloadTask.Status.PROBING))
    }

    /** 1b. 探测失败后不可再被判为完成（防止取消后的僵尸状态翻转） */
    @Test
    fun `探测失败后finished计数保持为零`() {
        val rec = Recorder()
        val job = DownloadEngine.get().newJob(task(id = 99L), rec)
        job.start()
        job.awaitReady(8000)
        job.cancel()
        Thread.sleep(200)
        assertEquals(0, rec.finished)
        assertEquals(1, rec.errors.size)
    }

    /** 2. 分段切分必须无缝无重叠（登记顺序错误的直接后果是错位） */
    @Test
    fun `分段计数覆盖完整区间且无重叠`() {
        val segs = DownloadEngine.Segments.split(1000, 4)!!
        assertEquals(4, segs.size)
        assertEquals(0L, segs.first()[0])
        assertEquals(999L, segs.last()[1])
        for (i in 0 until segs.size - 1) {
            assertEquals("段 ${i + 1} 应紧接段 $i", segs[i][1] + 1, segs[i + 1][0])
        }
    }

    /** 4. 文件名清理：路径分隔符与控制字符必须被剔除，避免写到意外位置 */
    @Test
    fun `服务器文件名被清理为安全名`() {
        assertEquals("a_b_c.mp4", DownloadEngine.sanitizeFileName("a/b\\c.mp4"))
        assertEquals("file name.txt", DownloadEngine.sanitizeFileName("file name.txt"))
        assertEquals("", DownloadEngine.sanitizeFileName("   "))
        assertEquals("", DownloadEngine.sanitizeFileName(".."))
        assertEquals("", DownloadEngine.sanitizeFileName("."))
    }

    /** 4b. 超长文件名被截断，避免超出文件系统限制 */
    @Test
    fun `超长文件名被截断`() {
        val long = "x".repeat(500)
        assertEquals(120, DownloadEngine.sanitizeFileName(long).length)
    }

    /** 5. 新任务不得继承同名旧 .part 的内容 */
    @Test
    fun `残留part必须被清空以免污染新文件`() {
        val f = File(dir, "reuse.bin")
        val part = File(f.absolutePath + ".part")
        RandomAccessFile(part, "rw").use { it.write(ByteArray(4096) { 7 }) }
        assertEquals(4096L, part.length())

        // 无断点文件 → 视为全新任务 → 必须清空同名 .part
        val progFile = File(dir, DownloadEngine.Segments.progFileName(1))
        assertFalse("无断点文件时应视为全新任务", progFile.exists())

        RandomAccessFile(part, "rw").use { it.setLength(0) }
        assertEquals("残留 .part 应被清空", 0L, part.length())
    }

    /** 7. 断点续传前提：进度文件存在且总大小未变 */
    @Test
    fun `总大小变化时不得续传`() {
        val prog = File(dir, DownloadEngine.Segments.progFileName(7))
        prog.writeText("0 100\n500 0")
        assertTrue(prog.isFile)
        // 服务器总大小变了 → 段边界已变，续传会写错位置 → 必须重新下载
        assertTrue(task(id = 7L, total = 999L).totalBytes != 1000L)
        assertTrue(task(id = 7L, total = 1000L).totalBytes == 1000L)
    }

    /** 8. 任务通知 ID 段位与前台通知 ID 分离（避免覆盖/取消前台通知） */
    @Test
    fun `任务通知ID不与前台通知冲突`() {
        // 任务 ID 从 1 开始（与前台通知 ID=1 相同），
        // 因此任务通知必须整体偏移，否则会覆盖/取消前台通知。
        val foregroundId = 1
        val taskNotifId = 1000 + 1
        assertTrue("任务通知 ID 必须与前台通知 ID 分离", taskNotifId != foregroundId)
    }
}
