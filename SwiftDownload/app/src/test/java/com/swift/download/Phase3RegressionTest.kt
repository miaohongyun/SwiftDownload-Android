package com.swift.download

import com.swift.download.bt.BtFile
import com.swift.download.bt.BtFileSelection
import com.swift.download.player.M3u8Crypto
import com.swift.download.player.M3u8Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** v2.6.0 Phase 3：Work Stealing / HLS AES-128 / BT 选文件的离线验证 */
class Phase3RegressionTest {

    /* ---------------- Task 3.1：慢段接管 ---------------- */

    @Test
    fun stealSplitSplitsExactlyWithoutOverlap() {
        // [0, 1000)，已下 200 → 剩余 800，切点 600
        val plan = DownloadEngine.Segments.stealSplit(0L, 999L, 200L, minBytes = 100L)!!
        assertEquals(600L, plan[0])   // 原段收缩到 [0, 600]
        assertEquals(601L, plan[1])   // 新段 [601, 999]
        assertEquals(999L, plan[2])
        assertTrue(plan[1] <= plan[2])
        assertEquals(1000L, (plan[0] + 1) + (plan[2] - plan[1] + 1))
    }

    @Test
    fun stealSplitRejectsTinyRemainder() {
        assertEquals(null, DownloadEngine.Segments.stealSplit(0L, 999L, 900L, 256L))
        assertEquals(null, DownloadEngine.Segments.stealSplit(0L, 999L, 1000L, 256L))
        assertEquals(null, DownloadEngine.Segments.stealSplit(0L, 0L, 0L, 0L))
    }

    /* ---------------- Task 3.2：AES / ByteRange ---------------- */

    @Test
    fun aesCryptoRoundTripAndImplicitIv() {
        val key = ByteArray(16) { (it + 1).toByte() }
        val iv = M3u8Crypto.ivFor(null, 7L)
        assertEquals(16, iv.size)
        // 序号 7 的大端低字节必须是 7
        assertEquals(7.toByte(), iv[15])
        val plain = "一段需要加密的 HLS 分片内容".toByteArray(Charsets.UTF_8)
        val cipher = M3u8Crypto.encryptForTest(plain, key, iv)
        assertNotEquals(plain.toList(), cipher.toList())
        assertEquals(plain.toList(), M3u8Crypto.decrypt(cipher, key, iv).toList())
        // 显式 IV（0x 前缀）必须按字节解析
        val hex = "0x0000000000000000000000000000002a"
        assertEquals(0x2a.toByte(), M3u8Crypto.ivFor(hex, 0L)[15])
    }

    @Test
    fun aesRejectsWrongKeyLength() {
        try {
            M3u8Crypto.requireKey(ByteArray(8))
            assertFalse("8 字节密钥必须被拒绝", true)
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("16"))
        }
    }

    /* ---------------- Task 3.3：BT 选文件 ---------------- */

    @Test
    fun btFilePrioritiesIgnoreUnselected() {
        val arr = BtFileSelection.priorities(3, setOf(0, 2))
        assertEquals(com.frostwire.jlibtorrent.Priority.NORMAL, arr[0])
        assertEquals(com.frostwire.jlibtorrent.Priority.IGNORE, arr[1])
        assertEquals(com.frostwire.jlibtorrent.Priority.NORMAL, arr[2])
        // 越界索引必须被过滤，不能把数组撑大
        assertEquals(setOf(1), BtFileSelection.normalize(setOf(1, 5, -1), 3))
    }

    @Test
    fun btPicksLargestVideoAndChecksReadiness() {
        val files = listOf(
            BtFile(0, "cover.jpg", "cover.jpg", 1024L, false),
            BtFile(1, "movie.mp4", "sub/movie.mp4", 2048L, true),
            BtFile(2, "sample.mkv", "sample.mkv", 1024L, true)
        )
        assertEquals(1, BtFileSelection.largestVideo(files)?.index)
        assertTrue(BtFileSelection.isReady(2048L, 2048L))
        assertFalse(BtFileSelection.isReady(2047L, 2048L))
        assertFalse(BtFileSelection.isReady(10L, 0L))
    }

    /* ---------------- 端到端：加密 HLS（AES-128 + 并发 + 分片重试） ---------------- */

    @Test(timeout = 90_000)
    fun encryptedHlsDownloadsAndDecryptsExactly() {
        val key = ByteArray(16) { (it * 7 + 3).toByte() }
        val ivHex = "0x00000000000000000000000000000005"
        val iv = M3u8Crypto.ivFor(ivHex, 0L)
        val plain0 = "第一条 TS 分片内容-0123456789".toByteArray(Charsets.UTF_8)
        val plain1 = "第二条 TS 分片内容-abcdefghij".toByteArray(Charsets.UTF_8)
        val cipher0 = M3u8Crypto.encryptForTest(plain0, key, iv)
        val cipher1 = M3u8Crypto.encryptForTest(plain1, key, iv)
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x00000000000000000000000000000005
            #EXTINF:1.0,
            seg0.ts
            #EXTINF:1.0,
            seg1.ts
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val server = ResponseServer { path ->
            when (path) {
                "/index.m3u8" -> playlist
                "/key.bin" -> key
                "/seg0.ts" -> cipher0
                "/seg1.ts" -> cipher1
                else -> ByteArray(0)
            }
        }
        val port = server.start()
        try {
            val dir = createTempDir()
            val save = File(dir, "out.ts")
            val done = CountDownLatch(1)
            val errRef = AtomicReference<String>()
            val job = M3u8Job(
                "http://127.0.0.1:$port/index.m3u8", save.absolutePath,
                throttle = {},
                listener = object : M3u8Job.Listener {
                    override fun onStatus(status: DownloadTask.Status) {}
                    override fun onProgress(downloaded: Long) {}
                    override fun onFinished() { done.countDown() }
                    override fun onError(message: String) { errRef.set(message); done.countDown() }
                }
            )
            job.start()
            assertTrue("加密 HLS 应完成：${errRef.get()}", done.await(60, TimeUnit.SECONDS))
            assertTrue("输出文件必须存在：${errRef.get()}", save.isFile)
            val expected = plain0 + plain1
            assertTrue("解密后必须逐字节一致", save.readBytes().contentEquals(expected))
        } finally {
            server.stop()
        }
    }

    /* ---------------- 端到端：慢连接触发 Work Stealing ---------------- */

    @Test(timeout = 120_000)
    fun slowSegmentIsStolenAndFileStaysComplete() {
        val total = 4 * 1024 * 1024
        val slowLimit = 1024 * 1024 // 第一个 Range 慢，其余正常
        val server = RangeServer(total, slowLimit)
        val port = server.start()
        try {
            val dir = createTempDir()
            val save = File(dir, "big.bin")
            val done = CountDownLatch(1)
            val errRef = AtomicReference<String>()
            val rec = object : DownloadEngine.EngineListener {
                override fun onProgress(task: DownloadTask, downloadedBytes: Long, speedBps: Long) {}
                override fun onStatus(task: DownloadTask) {}
                override fun onFinished(task: DownloadTask) { done.countDown() }
                override fun onError(task: DownloadTask, error: String) {
                    errRef.set(error); done.countDown()
                }
            }
            val job = DownloadEngine.get().newJob(
                DownloadTask(
                    id = 93001, url = "http://127.0.0.1:$port/big.bin",
                    fileName = "big.bin", savePath = save.absolutePath,
                    totalBytes = -1, downloadedBytes = 0,
                    status = DownloadTask.Status.QUEUED, threads = 4,
                    createdAt = System.currentTimeMillis()
                ), rec
            )
            job.start()
            assertTrue("慢连接任务应完成：${errRef.get()}", done.await(90, TimeUnit.SECONDS))
            assertTrue("应发生慢段接管", job.stealEvents() > 0)
            assertTrue("输出长度必须完整", save.isFile && save.length() == total.toLong())
            val bytes = save.readBytes()
            assertTrue("文件内容不得错位/留洞", bytes.all { it == 0x5A.toByte() })
        } finally {
            server.stop()
        }
    }

    /* ---------------- 测试用本地 HTTP 服务 ---------------- */

    private class ResponseServer(private val body: (String) -> ByteArray) {
        private val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        @Volatile private var running = true
        fun start(): Int {
            Thread {
                while (running) {
                    val client = try { socket.accept() } catch (e: Exception) { break }
                    Thread {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
                            val line = reader.readLine() ?: return@Thread
                            while (reader.readLine()?.isNotEmpty() == true) { /* 跳过请求头 */ }
                            val path = line.split(' ').getOrNull(1) ?: "/"
                            val data = body(path)
                            val head = "HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n"
                            client.getOutputStream().apply {
                                write(head.toByteArray(Charsets.ISO_8859_1))
                                write(data)
                                flush()
                            }
                        } catch (e: Exception) {
                            // 客户端提前断开属正常
                        } finally {
                            try { client.close() } catch (e: Exception) {}
                        }
                    }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
            return socket.localPort
        }
        fun stop() { running = false; try { socket.close() } catch (e: Exception) {} }
    }

    /** 支持 Range；慢段每 64KB 休眠，模拟参差不齐的 CDN 节点 */
    private class RangeServer(private val total: Int, private val slowLimit: Int) {
        private val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        @Volatile private var running = true
        fun start(): Int {
            Thread {
                while (running) {
                    val client = try { socket.accept() } catch (e: Exception) { break }
                    Thread {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
                            val line = reader.readLine() ?: return@Thread
                            var range: String? = null
                            while (true) {
                                val h = reader.readLine() ?: break
                                if (h.isEmpty()) break
                                if (h.lowercase().startsWith("range:")) range = h.substringAfter(':').trim()
                            }
                            var from = 0L
                            var to = total - 1L
                            var code = 200
                            if (range != null && line.startsWith("HEAD").not()) {
                                val m = Regex("bytes=(\\d+)-(\\d*)").find(range)
                                if (m != null) {
                                    from = m.groupValues[1].toLong()
                                    val end = m.groupValues[2]
                                    if (end.isNotBlank()) to = minOf(end.toLong(), total - 1L)
                                    code = 206
                                }
                            }
                            val len = (to - from + 1).toInt()
                            val head = if (code == 206) {
                                "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-$to/$total\r\nContent-Length: $len\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n"
                            } else {
                                "HTTP/1.1 200 OK\r\nContent-Length: $total\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n"
                            }
                            val out = client.getOutputStream()
                            out.write(head.toByteArray(Charsets.ISO_8859_1))
                            val chunk = ByteArray(65536) { 0x5A.toByte() }
                            var sent = 0
                            val slow = from < slowLimit
                            while (sent < len) {
                                val n = minOf(chunk.size, len - sent)
                                out.write(chunk, 0, n)
                                sent += n
                                if (slow) Thread.sleep(40L)
                            }
                            out.flush()
                        } catch (e: Exception) {
                            // 取消/切段导致的断开属正常
                        } finally {
                            try { client.close() } catch (e: Exception) {}
                        }
                    }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
            return socket.localPort
        }
        fun stop() { running = false; try { socket.close() } catch (e: Exception) {} }
    }
}
