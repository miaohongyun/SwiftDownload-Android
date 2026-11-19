package com.swift.download

import com.swift.download.settings.RefreshRules
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * v2.7 Stage1 集成测试：
 * - 可恢复 503 触发重试后下载成功且文件逐字节完整
 * - 空间不足时引擎在 probe 后中止，不产生 .part
 * 用本地最小 HTTP 服务器，零外网依赖。
 */
class Stage1IntegrationTest {

    private val payload = ByteArray(180_000) { ((it * 17) % 251).toByte() }

    private class Recorder : DownloadEngine.EngineListener {
        val error = AtomicReference<String?>(null)
        val retries = AtomicInteger(0)
        val finished = CountDownLatch(1)
        val failed = CountDownLatch(1)
        override fun onProgress(task: DownloadTask, downloadedBytes: Long, speedBps: Long) {}
        override fun onStatus(task: DownloadTask) {}
        override fun onFinished(task: DownloadTask) { finished.countDown() }
        override fun onError(task: DownloadTask, error: String) {
            this.error.set(error); failed.countDown()
        }
    }

    /** 前 failFirst 次 GET 分段请求返回 503，之后正常 206；HEAD 始终正常 */
    private inner class FlakyServer(private val failFirst: Int) {
        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val hits = AtomicInteger(0)
        val url: String get() = "http://127.0.0.1:${socket.localPort}/f.bin"
        fun start() {
            Thread {
                while (!socket.isClosed) {
                    val c = try { socket.accept() } catch (e: Exception) { break }
                    Thread { handle(c) }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; start() }
        }
        fun stop() { try { socket.close() } catch (e: Exception) {} }

        private fun handle(c: Socket) {
            try {
                c.soTimeout = 10_000
                val input = c.getInputStream()
                val request = readLine(input) ?: return
                while (true) { val l = readLine(input) ?: break; if (l.isEmpty()) break }
                val out = c.getOutputStream()
                if (request.startsWith("HEAD")) {
                    out.write(("HTTP/1.1 200 OK\r\nAccept-Ranges: bytes\r\n" +
                        "Content-Length: ${payload.size}\r\n\r\n").toByteArray())
                    out.flush(); return
                }
                val n = hits.incrementAndGet()
                if (n <= failFirst) {
                    out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    out.flush(); return
                }
                val range = "bytes 0-${payload.size - 1}/${payload.size}"
                out.write(("HTTP/1.1 206 Partial Content\r\nAccept-Ranges: bytes\r\n" +
                    "Content-Range: $range\r\n" +
                    "Content-Length: ${payload.size}\r\n\r\n").toByteArray())
                out.write(payload); out.flush()
            } catch (e: Exception) {
                // 客户端取消属正常
            } finally {
                try { c.close() } catch (e: Exception) {}
            }
        }
        private fun readLine(input: java.io.InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val ch = input.read()
                if (ch < 0) return if (sb.isEmpty()) null else sb.toString()
                if (ch == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(ch.toChar())
            }
        }
    }

    private fun task(id: Long, dir: File, url: String) = DownloadTask(
        id = id, url = url, fileName = "f.bin", savePath = File(dir, "f.bin").absolutePath,
        totalBytes = 0L, downloadedBytes = 0L, status = DownloadTask.Status.QUEUED,
        threads = 1, createdAt = System.currentTimeMillis()
    )

    @Test
    fun recoverable503RetriesThenDownloadsIntact() {
        val server = FlakyServer(failFirst = 2).also { it.start() }
        val dir = createTempDir()
        val rec = Recorder()
        val retried = AtomicInteger(0)
        val job = com.swift.download.DownloadEngine.get().newJob(
            task(9101, dir, server.url), rec,
            maxRetries = 3,
            onRetry = { _, _ -> retried.incrementAndGet() }
        )
        try {
            job.start()
            val done = rec.finished.await(40, TimeUnit.SECONDS)
            val diag = "done=$done retried=${retried.get()} hits=${server.hits.get()} failed=${rec.failed.count == 0L} error=${rec.error.get()}"
            assertTrue("应在重试后完成：$diag", done)
            assertEquals(2, retried.get())
            assertArrayEquals(payload, File(dir, "f.bin").readBytes())
        } finally {
            job.cancel(); server.stop()
        }
    }

    @Test
    fun fatalErrorDoesNotRetry() {
        // HTTP 404 是硬错误：即使允许重试也不应重试，直接失败
        val dir = createTempDir()
        val rec = Recorder()
        val retried = AtomicInteger(0)
        val server = object {
            val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
            val url: String get() = "http://127.0.0.1:${socket.localPort}/f.bin"
            fun start() {
                Thread {
                    while (!socket.isClosed) {
                        val c = try { socket.accept() } catch (e: Exception) { break }
                        Thread {
                            try {
                                val i = c.getInputStream(); readLine(i)
                                while (true) { val l = readLine(i) ?: break; if (l.isEmpty()) break }
                                c.getOutputStream().write(
                                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                                c.getOutputStream().flush()
                            } catch (e: Exception) {} finally { try { c.close() } catch (e: Exception) {} }
                        }.apply { isDaemon = true; start() }
                    }
                }.apply { isDaemon = true; start() }
            }
            fun stop() { try { socket.close() } catch (e: Exception) {} }
            private fun readLine(input: java.io.InputStream): String? {
                val sb = StringBuilder()
                while (true) {
                    val ch = input.read(); if (ch < 0) return if (sb.isEmpty()) null else sb.toString()
                    if (ch == '\n'.code) return sb.toString().trimEnd('\r'); sb.append(ch.toChar())
                }
            }
        }.also { it.start() }
        val job = com.swift.download.DownloadEngine.get().newJob(
            task(9102, dir, server.url), rec, maxRetries = 3,
            onRetry = { _, _ -> retried.incrementAndGet() }
        )
        try {
            job.start()
            assertTrue("应失败", rec.failed.await(20, TimeUnit.SECONDS))
            assertEquals(0, retried.get())
        } finally {
            job.cancel(); server.stop()
        }
    }

    @Test
    fun insufficientSpaceAbortsBeforeWritingPart() {
        val server = FlakyServer(failFirst = 0).also { it.start() }
        val dir = createTempDir()
        val rec = Recorder()
        // 注入剩余空间远小于所需 → probe 后应立即中止
        val job = com.swift.download.DownloadEngine.get().newJob(
            task(9103, dir, server.url), rec,
            availableBytes = { 1024L }
        )
        try {
            job.start()
            assertTrue("应因空间不足失败", rec.failed.await(20, TimeUnit.SECONDS))
            assertTrue(rec.error.get()?.contains("存储空间不足") == true)
            assertFalse("不应产生 .part", File(dir, "f.bin.part").exists())
        } finally {
            job.cancel(); server.stop()
        }
    }
}
