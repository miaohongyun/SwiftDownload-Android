package com.swift.download

import com.swift.download.player.StreamServer
import com.swift.download.player.TaskBacking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次性真实端到端测试（依赖外网，非长期回归用例）：
 *  A. 多线程分段下载真实文件 → 合并 → SHA-1 与官方一致（probe/分段/合并全链路）
 *  B. 中途取消 → 落盘进度 → 重建任务续传 → SHA-1 仍一致（断点续传链路）
 *  C. 复刻 DownloadService.startStream：引擎暂停 → StreamServer 接管 →
 *     播放器 Range 请求 206 + 数据与源站一致 + externalWrite 回写进度（边下边播链路）
 */
class RealEngineE2eTest {

    private val base = "https://maven.aliyun.com/repository/public/org/jetbrains/kotlin/kotlin-stdlib/1.9.24"
    private val jarUrl = "$base/kotlin-stdlib-1.9.24.jar"
    private val workDir = File("/tmp/e2e-dl").apply { mkdirs() }

    private fun httpGetBytes(url: String, range: String? = null): Pair<Int, ByteArray> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        range?.let { conn.setRequestProperty("Range", it) }
        try {
            val code = conn.responseCode
            val bytes = conn.inputStream.use { it.readBytes() }
            return code to bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun noopListener(): DownloadEngine.EngineListener =
        object : DownloadEngine.EngineListener {
            override fun onProgress(task: DownloadTask, downloadedBytes: Long, speedBps: Long) {}
            override fun onStatus(task: DownloadTask) {}
            override fun onFinished(task: DownloadTask) {}
            override fun onError(task: DownloadTask, error: String) {}
        }

    private fun taskOf(id: Long, savePath: String, threads: Int, totalBytes: Long = 0) = DownloadTask(
        id = id, url = jarUrl, fileName = File(savePath).name, savePath = savePath,
        totalBytes = totalBytes, downloadedBytes = 0, status = DownloadTask.Status.RUNNING,
        threads = threads, createdAt = System.currentTimeMillis()
    )

    /** 离线保护：外网不可达时跳过（真实链路测试依赖网络） */
    private fun assumeOnline(url: String) {
        val ok = try {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 3000; c.readTimeout = 3000
            c.requestMethod = "HEAD"
            val rc = c.responseCode
            c.disconnect()
            rc in 200..499
        } catch (e: Exception) { false }
        org.junit.Assume.assumeTrue("外网不可达，跳过真实链路测试", ok)
    }

    @Test
    fun `A_多线程下载真实文件_合并后SHA1与官方一致`() {
        assumeOnline("https://maven.aliyun.com/repository/public/org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar")
        val (_, sha1Bytes) = httpGetBytes("$jarUrl.sha1")
        val expect = sha1Bytes.decodeToString().trim().substringBefore(" ")
        val save = File(workDir, "a/kotlin-stdlib.jar").apply { parentFile?.mkdirs(); delete() }
        File(save.path + ".part").delete()
        File(save.parentFile, "9.swiftprog").delete()

        val engine = DownloadEngine.get()
        val probe = engine.probe(jarUrl)
        println("[A] probe: total=${probe.totalBytes} acceptRanges=${probe.acceptRanges} name=${probe.fileName}")
        assertTrue("服务器应支持 Range", probe.acceptRanges)
        assertTrue(probe.totalBytes > 1_000_000)

        val done = CountDownLatch(1)
        val renamed = java.util.concurrent.atomic.AtomicReference<DownloadTask?>()
        val l = object : DownloadEngine.EngineListener {
            override fun onProgress(task: DownloadTask, downloadedBytes: Long, speedBps: Long) {}
            override fun onStatus(task: DownloadTask) { if (task.status == DownloadTask.Status.RUNNING) renamed.set(task) }
            override fun onFinished(task: DownloadTask) { done.countDown() }
            override fun onError(task: DownloadTask, error: String) {
                throw AssertionError("下载失败: $error")
            }
        }
        val job = engine.newJob(taskOf(9, save.path, 4), l)
        job.start()
        assertTrue("下载应在 90s 内完成", done.await(90, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertTrue("引擎应已完成", job.totalBytes() == probe.totalBytes)

        // v2.5 行为：服务器 Content-Disposition 重命名 → 引擎改写最终路径，服务端同步 savePath
        val finalPath = renamed.get()?.savePath ?: save.path
        val finalFile = File(finalPath)
        println("[A] 引擎重命名: ${save.name} -> ${finalFile.name}")
        val bytes = finalFile.readBytes()
        println("[A] 完成: 大小=${bytes.size} sha1=${sha1(bytes)} (官方 $expect)")
        assertEquals("文件大小应一致", probe.totalBytes, bytes.size.toLong())
        assertEquals("SHA-1 应与官方一致", expect, sha1(bytes))
        assertTrue("合并后 .part 应删除", !File(finalFile.path + ".part").exists())
        assertTrue("完成后 .swiftprog 应删除", !File(finalFile.parentFile, "9.swiftprog").exists())
    }

    @Test
    fun `B_中途取消_重建任务续传_最终SHA1一致`() {
        assumeOnline("https://maven.aliyun.com/repository/public/org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar")
        val (_, sha1Bytes) = httpGetBytes("$jarUrl.sha1")
        val expect = sha1Bytes.decodeToString().trim().substringBefore(" ")
        val save = File(workDir, "b/kotlin-stdlib.jar").apply { parentFile?.mkdirs(); delete() }
        File(save.path + ".part").delete()
        File(save.parentFile, "10.swiftprog").delete()

        val engine = DownloadEngine.get()
        val probe = engine.probe(jarUrl)
        val total = probe.totalBytes

        // 第一段：限速下载到约 30% 时取消（复刻服务端 ticker 落盘 .swiftprog）
        val stopped = AtomicBoolean(false)
        val job1 = engine.newJob(taskOf(10, save.path, 4, total), noopListener(), throttle@{ n -> if (!stopped.get()) Thread.sleep(6L * n / 64) })
        job1.start()
        val ticker = Thread {
            while (!stopped.get()) {
                try { job1.writeProgressIfActive() } catch (e: Exception) {}
                Thread.sleep(150)
            }
        }.apply { isDaemon = true; start() }
        val deadline = System.currentTimeMillis() + 30_000
        while (job1.totalBytes() < total / 3 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        job1.cancel()
        job1.writeProgressIfActive()
        stopped.set(true)
        val pausedBytes = job1.totalBytes()
        val prog = File(save.parentFile, "10.swiftprog")
        println("[B] 已取消: 进度=${pausedBytes}/${total} (${pausedBytes * 100 / total}%), swiftprog=${prog.readText().replace("\n", " | ")}")
        assertTrue("取消前应有部分进度", pausedBytes in 1 until total)
        assertTrue("进度文件应存在", prog.isFile)

        // 第二段：服务重建任务的语义 —— 同 id/同路径/totalBytes 已知，续传
        val done = CountDownLatch(1)
        val resumed = object : DownloadEngine.EngineListener {
            override fun onProgress(task: DownloadTask, downloadedBytes: Long, speedBps: Long) {}
            override fun onStatus(task: DownloadTask) {}
            override fun onFinished(task: DownloadTask) { done.countDown() }
            override fun onError(task: DownloadTask, error: String) { throw AssertionError("续传失败: $error") }
        }
        val task2 = taskOf(10, save.path, 4, total).copy(downloadedBytes = pausedBytes)
        val job2 = engine.newJob(task2, resumed, throttle@{ _ -> })
        job2.start()
        assertTrue("续传应在 90s 内完成", done.await(90, TimeUnit.SECONDS))
        Thread.sleep(200)

        val bytes = save.readBytes()
        println("[B] 续传完成: 大小=${bytes.size} sha1=${sha1(bytes)}")
        assertEquals(total, bytes.size.toLong())
        assertEquals("续传后 SHA-1 应与官方一致", expect, sha1(bytes))
        assertTrue(!File(save.path + ".part").exists())
    }

    @Test
    fun `C_边下边播_本地流服务器Range回源与回写`() {
        assumeOnline("https://maven.aliyun.com/repository/public/org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar")
        val save = File(workDir, "c/kotlin-stdlib.jar").apply { parentFile?.mkdirs(); delete() }
        File(save.path + ".part").delete()

        val engine = DownloadEngine.get()
        val probe = engine.probe(jarUrl)
        val total = probe.totalBytes

        // 先让引擎下一小段（限速），然后 cancel —— 复刻 startStream 的会话切换
        val renamed = java.util.concurrent.atomic.AtomicReference<DownloadTask?>()
        val statusListener = object : DownloadEngine.EngineListener {
            override fun onProgress(task: DownloadTask, downloadedBytes: Long, speedBps: Long) {}
            override fun onStatus(task: DownloadTask) { if (task.status == DownloadTask.Status.RUNNING) renamed.set(task) }
            override fun onFinished(task: DownloadTask) {}
            override fun onError(task: DownloadTask, error: String) {}
        }
        val job = engine.newJob(taskOf(11, save.path, 4, total), statusListener, throttle@{ n -> Thread.sleep(4L * n / 64) })
        job.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (job.totalBytes() < 200_000 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("引擎应先下载一部分", job.totalBytes() > 100_000)
        val engineBytes = job.totalBytes()
        job.cancel() // startStream 同款：停引擎线程，保留 Job

        // 复刻 startStream：awaitReady 后从服务任务表取的是重命名后的 savePath
        val task = renamed.get()?.copy(totalBytes = total) ?: taskOf(11, save.path, 4, total)
        println("[C] 实际 .part: ${task.savePath}.part 存在=${File(task.savePath + ".part").isFile}")
        assertTrue("重命名后的 .part 应存在", File(task.savePath + ".part").isFile)
        val backing = TaskBacking(
            partPath = task.savePath + ".part",
            url = task.url,
            total = task.totalBytes,
            mime = "application/java-archive",
            referer = task.referer,
            externalWrite = { offset, buf, len -> job.externalWrite(task, offset, buf, len) },
            segments = { job.snapshot() }
        )
        val server = StreamServer(backing)
        val port = server.start()
        val localUrl = server.localUrl()
        println("[C] 边下边播服务器已启动: $localUrl (引擎先下了 $engineBytes 字节)")

        // 1) 无 Range 整文件请求（播放器首播行为）→ 200 + 完整且与源站一致的数据
        val (code1, body1) = httpGetBytes(localUrl)
        assertEquals("首播应返回 200", 200, code1)
        assertEquals("首播应返回完整文件", total, body1.size.toLong())
        val (_, originFull) = httpGetBytes(jarUrl)
        assertEquals("首播数据应与源站逐字节一致(v2.5 skip错位bug回归)", sha1(originFull), sha1(body1))
        // 回写语义：externalWrite 只对"正好落在分段前沿"的写入计数（跨段大块只落盘不计数），
        // 因此进度计数是保守值——断言"显著推进"而非"等于全量"。
        assertTrue("边播期间进度应推进", job.totalBytes() > engineBytes)
        assertEquals("全片播完后 .part 应包含完整数据", total, File(task.savePath + ".part").length())

        // 2) Range 请求（拖进度行为）→ 206 + 数据与源站一致
        val (code2, part) = httpGetBytes(localUrl, "bytes=500000-520000")
        assertEquals("Range 请求应返回 206", 206, code2)
        val (code3, origin) = httpGetBytes(jarUrl, "bytes=500000-520000")
        assertEquals("源站对照应返回 206", 206, code3)
        assertEquals("本地服务器数据应与源站一致", origin.size, part.size)
        assertEquals(sha1(origin), sha1(part))

        // 2b) 回跳 Range（播放器往回拖）：连接无法倒读，应重建连接后仍给对数据
        val (code2b, back) = httpGetBytes(localUrl, "bytes=100000-110000")
        assertEquals("回跳 Range 应返回 206", 206, code2b)
        val (_, originBack) = httpGetBytes(jarUrl, "bytes=100000-110000")
        assertEquals("回跳数据应与源站一致", sha1(originBack), sha1(back))

        // 3) 边界 Range：末尾 100 字节
        val (code4, tail) = httpGetBytes(localUrl, "bytes=${total - 100}-")
        assertEquals(206, code4)
        assertEquals(100, tail.size)

        // 4) 非法 Range（超出文件末尾）→ 416
        val conn = URL(localUrl).openConnection() as HttpURLConnection
        conn.setRequestProperty("Range", "bytes=${total + 100}-")
        val code5 = try { conn.responseCode } finally { conn.disconnect() }
        assertEquals("越界 Range 应返回 416", 416, code5)

        server.stop()
        println("[C] 边下边播链路验证通过: 首播200/拖动206/数据一致/回写完成")
    }
}
