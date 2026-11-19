package com.swift.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * v2.6.0 收尾：针对交叉检查发现的 4 处隐患的回归测试。
 */
class Phase5RegressionTest {

    /* ---------------- 隐患 1：Work Stealing 断点恢复 ---------------- */

    @Test
    fun dynamicSegmentIntervalsAreAcceptedAsResumable() {
        // 动态切分后的非等长区间：0..600、601..999 —— 起点 601 不在静态 split 里
        val dynamic = listOf(
            DownloadEngine.Segments.ProgEntry(0L, 600L, 400L),
            DownloadEngine.Segments.ProgEntry(601L, 999L, 0L)
        )
        assertTrue("动态切分区间必须被认作合法断点",
            DownloadEngine.Segments.isResumable(dynamic, 1000L))

        // 旧逻辑会否掉的场景：起点不属于静态 split
        val staticStarts = setOf(0L, 250L, 500L, 750L)
        assertFalse("601 确实不在静态起点集合里（证明旧校验会误判）", 601L in staticStarts)

        // 有洞 / 不连续 / 越界都必须拒绝
        assertFalse(DownloadEngine.Segments.isResumable(
            listOf(DownloadEngine.Segments.ProgEntry(0L, 400L, 100L),
                DownloadEngine.Segments.ProgEntry(501L, 999L, 0L)), 1000L))
        assertFalse(DownloadEngine.Segments.isResumable(
            listOf(DownloadEngine.Segments.ProgEntry(0L, 1000L, 100L)), 1000L))
        assertFalse(DownloadEngine.Segments.isResumable(emptyList(), 1000L))
    }

    @Test(timeout = 90_000)
    fun dynamicSwiftprogResumesFromRecordedOffsets() {
        val total = 1024 * 1024
        val server = RecordingRangeServer(total)
        val port = server.start()
        try {
            val dir = createTempDir()
            val save = File(dir, "big.bin")
            // 模拟 Work Stealing 后的断点：切分点 300000 不是静态 split 起点
            File(dir, DownloadEngine.Segments.progFileName(95001)).writeText(
                "0 300000 200000\n300001 ${total - 1} 0"
            )
            // .part 必须有至少 max(start+bytes)=200000 字节，否则会被判为不匹配
            File(save.path + ".part").apply {
                writeBytes(ByteArray(200000) { 0x5A.toByte() })
            }

            val done = CountDownLatch(1)
            val errRef = AtomicReference<String>()
            val job = DownloadEngine.get().newJob(
                DownloadTask(
                    id = 95001, url = "http://127.0.0.1:$port/big.bin",
                    fileName = "big.bin", savePath = save.absolutePath,
                    totalBytes = total.toLong(), downloadedBytes = 200000,
                    status = DownloadTask.Status.PAUSED, threads = 4,
                    createdAt = System.currentTimeMillis()
                ),
                object : DownloadEngine.EngineListener {
                    override fun onProgress(task: DownloadTask, downloadedBytes: Long, speedBps: Long) {}
                    override fun onStatus(task: DownloadTask) {}
                    override fun onFinished(task: DownloadTask) { done.countDown() }
                    override fun onError(task: DownloadTask, message: String) {
                        errRef.set(message); done.countDown()
                    }
                }
            )
            job.start()
            assertTrue("动态断点应能续传完成：${errRef.get()}", done.await(60, TimeUnit.SECONDS))
            assertTrue("文件长度必须完整", save.isFile && save.length() == total.toLong())
            // 关键：不得出现 bytes=0- 的整段重下请求（说明断点被采纳）
            assertFalse("不应从头重下第一段：${server.ranges}",
                server.ranges.any { it.startsWith("bytes=0-") && !it.startsWith("bytes=0-299999") })
            assertTrue("应从记录偏移续传 bytes=200000-300000：${server.ranges}",
                server.ranges.any { it.startsWith("bytes=200000-300000") })
        } finally {
            server.stop()
        }
    }

    /* ---------------- 隐患 3：HLS 缓存清理与多 Key ---------------- */

    @Test
    fun hlsCacheDirIsRemovedRecursively() {
        val dir = createTempDir()
        val save = File(dir, "movie.ts").absolutePath
        val cache = File(save + ".hls")
        cache.mkdirs()
        File(cache, "seg_0.bin").writeBytes(ByteArray(1024))
        File(cache, "seg_1.bin.tmp").writeBytes(ByteArray(512))
        File(cache, "nested").mkdirs()
        File(cache, "nested/seg_2.bin").writeBytes(ByteArray(256))
        assertTrue("前置条件：缓存目录已建立", cache.isDirectory)

        com.swift.download.player.M3u8Job.deleteCacheDir(save)
        assertFalse("缓存目录必须被递归清除", cache.exists())
    }

    @Test
    fun keyRotationBindsEachSegmentToItsOwnKey() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key-a.bin"
            a0.ts
            a1.ts
            #EXT-X-KEY:METHOD=AES-128,URI="key-b.bin",IV=0x00000000000000000000000000000009
            b0.ts
            #EXT-X-KEY:METHOD=NONE
            plain.ts
        """.trimIndent()
        val p = com.swift.download.player.M3u8.parse(
            "https://site.test/v/index.m3u8", text)
        assertEquals(4, p.items.size)
        assertEquals("https://site.test/v/key-a.bin", p.items[0].key?.uri)
        assertEquals("https://site.test/v/key-a.bin", p.items[1].key?.uri)
        // 第二个 Key 必须绑定到切换后的分片，而不是沿用第一个
        assertEquals("https://site.test/v/key-b.bin", p.items[2].key?.uri)
        assertEquals("0x00000000000000000000000000000009", p.items[2].key?.iv)
        assertEquals(null, p.items[3].key)
    }

    /** 支持 Range 且记录所有请求的本地服务器 */
    private class RecordingRangeServer(private val total: Int) {
        val ranges = Collections.synchronizedList(ArrayList<String>())
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
                            range?.let { ranges.add(it) }
                            var from = 0L
                            var to = total - 1L
                            var code = 200
                            if (range != null) {
                                val m = Regex("bytes=(\\d+)-(\\d*)").find(range)
                                if (m != null) {
                                    from = m.groupValues[1].toLong()
                                    if (m.groupValues[2].isNotBlank()) {
                                        to = minOf(m.groupValues[2].toLong(), total - 1L)
                                    }
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
                            while (sent < len) {
                                val n = minOf(chunk.size, len - sent)
                                out.write(chunk, 0, n)
                                sent += n
                            }
                            out.flush()
                        } catch (e: Exception) {
                            // 客户端断开属正常
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
