package com.swift.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * v2.5.2：下载完整性验收的离线回归测试（本地裸 Socket HTTP 服务器，不依赖外网）。
 *
 * 覆盖此前会"静默产出坏文件"的场景：
 *  1. 服务器忽略 Range 请求，直接返回 200 + 整个文件
 *  2. 响应字节数少于应答长度（提前结束分块流）
 *  3. 206 的 Content-Range 起点与请求偏移不一致
 * 三种情况下任务都必须是 FAILED，且不能生成"已完成"的正式文件。
 */
class DownloadIntegrityTest {

    private enum class Mode { OK, IGNORE_RANGE, SHORT_BODY, WRONG_RANGE }

    private val payload = ByteArray(200_000) { ((it * 31) % 251).toByte() }

    private class Recorder : DownloadEngine.EngineListener {
        val error = AtomicReference<String?>(null)
        val finished = CountDownLatch(1)
        val failed = CountDownLatch(1)
        override fun onProgress(task: DownloadTask, downloadedBytes: Long, speedBps: Long) {}
        override fun onStatus(task: DownloadTask) {}
        override fun onFinished(task: DownloadTask) { finished.countDown() }
        override fun onError(task: DownloadTask, error: String) {
            this.error.set(error)
            failed.countDown()
        }
    }

    /** 最小 HTTP/1.1 服务器：只为验证客户端的完整性校验行为 */
    private inner class StubServer(private val mode: Mode) {
        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val url: String get() = "http://127.0.0.1:${socket.localPort}/file.bin"

        fun start() {
            Thread {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (e: Exception) { break }
                    Thread { handle(client) }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; start() }
        }

        fun stop() {
            try { socket.close() } catch (e: Exception) {}
        }

        private fun handle(client: Socket) {
            try {
                client.soTimeout = 10_000
                val input = client.getInputStream()
                val requestLine = readLine(input) ?: return
                var range: String? = null
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    if (line.lowercase().startsWith("range:")) {
                        range = line.substringAfter(':').trim()
                    }
                }
                val out = client.getOutputStream()
                if (!requestLine.startsWith("GET")) {
                    // HEAD：只回头部，供引擎探测 Content-Length / Accept-Ranges
                    write(out, "HTTP/1.1 200 OK\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Content-Length: ${payload.size}\r\n\r\n", null)
                    return
                }
                if (range == null || mode == Mode.IGNORE_RANGE) {
                    write(out, "HTTP/1.1 200 OK\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Content-Length: ${payload.size}\r\n\r\n", payload)
                    return
                }
                val m = Regex("bytes=(\\d+)-(\\d*)").find(range) ?: run {
                    write(out, "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n", null)
                    return
                }
                val start = m.groupValues[1].toInt()
                val end = m.groupValues[2].ifBlank { (payload.size - 1).toString() }
                    .toInt().coerceAtMost(payload.size - 1)
                val len = end - start + 1
                if (start >= payload.size) {
                    write(out, "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                        "Content-Range: bytes */${payload.size}\r\nContent-Length: 0\r\n\r\n", null)
                    return
                }
                val crStart = if (mode == Mode.WRONG_RANGE) start + 777 else start
                if (mode == Mode.SHORT_BODY) {
                    // 分块传输但只发一半：客户端读到流的正常结束（-1），必须自己发现缺字节
                    val half = len / 2
                    val head = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Range: bytes $crStart-$end/${payload.size}\r\n" +
                        "Transfer-Encoding: chunked\r\n\r\n" +
                        Integer.toHexString(half) + "\r\n"
                    out.write(head.toByteArray(Charsets.ISO_8859_1))
                    out.write(payload, start, half)
                    out.write("\r\n0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                } else {
                    val head = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Range: bytes $crStart-$end/${payload.size}\r\n" +
                        "Content-Length: $len\r\n\r\n"
                    out.write(head.toByteArray(Charsets.ISO_8859_1))
                    out.write(payload, start, len)
                    out.flush()
                }
            } catch (e: Exception) {
                // 客户端校验失败会主动断开，属预期
            } finally {
                try { client.close() } catch (e: Exception) {}
            }
        }

        private fun write(out: OutputStream, head: String, body: ByteArray?) {
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            body?.let { out.write(it) }
            out.flush()
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
            }
        }
    }

    private fun withServer(mode: Mode, block: (String) -> Unit) {
        val srv = StubServer(mode)
        srv.start()
        try {
            block(srv.url)
        } finally {
            srv.stop()
        }
    }

    private fun task(id: Long, dir: File) = DownloadTask(
        id = id, url = "", fileName = "file.bin",
        savePath = File(dir, "file.bin").absolutePath,
        totalBytes = -1, downloadedBytes = 0,
        status = DownloadTask.Status.QUEUED, threads = 4, createdAt = 0
    )

    @Test(timeout = 60_000)
    fun `正常服务器_分段下载完成且内容逐字节一致`() {
        withServer(Mode.OK) { url ->
            val dir = createTempDir()
            val t = task(9001, dir).copy(url = url)
            val rec = Recorder()
            DownloadEngine.get().newJob(t, rec).start()
            assertTrue("应在 30s 内完成", rec.finished.await(30, TimeUnit.SECONDS))
            val out = File(t.savePath)
            assertTrue("应产出正式文件", out.isFile)
            assertEquals(payload.size.toLong(), out.length())
            assertArrayEquals(payload, out.readBytes())
            assertFalse("合并后不应残留 .part", File(t.savePath + ".part").exists())
        }
    }

    @Test(timeout = 60_000)
    fun `服务器忽略Range_必须失败而不是标成已完成`() {
        withServer(Mode.IGNORE_RANGE) { url ->
            val dir = createTempDir()
            val t = task(9002, dir).copy(url = url)
            val rec = Recorder()
            DownloadEngine.get().newJob(t, rec).start()
            assertTrue("应报错", rec.failed.await(30, TimeUnit.SECONDS))
            assertFalse("不得判定完成", rec.finished.await(500, TimeUnit.MILLISECONDS))
            assertFalse("不得生成正式文件", File(t.savePath).exists())
        }
    }

    @Test(timeout = 60_000)
    fun `响应字节不足_必须失败而不是标成已完成`() {
        withServer(Mode.SHORT_BODY) { url ->
            val dir = createTempDir()
            val t = task(9003, dir).copy(url = url)
            val rec = Recorder()
            DownloadEngine.get().newJob(t, rec).start()
            assertTrue("应报错", rec.failed.await(30, TimeUnit.SECONDS))
            assertFalse("不得判定完成", rec.finished.await(500, TimeUnit.MILLISECONDS))
            assertFalse("不得生成正式文件", File(t.savePath).exists())
        }
    }

    @Test(timeout = 60_000)
    fun `ContentRange起点不符_必须失败`() {
        withServer(Mode.WRONG_RANGE) { url ->
            val dir = createTempDir()
            val t = task(9004, dir).copy(url = url)
            val rec = Recorder()
            DownloadEngine.get().newJob(t, rec).start()
            assertTrue("应报错", rec.failed.await(30, TimeUnit.SECONDS))
            assertFalse("不得判定完成", rec.finished.await(500, TimeUnit.MILLISECONDS))
            assertFalse("不得生成正式文件", File(t.savePath).exists())
        }
    }
}
