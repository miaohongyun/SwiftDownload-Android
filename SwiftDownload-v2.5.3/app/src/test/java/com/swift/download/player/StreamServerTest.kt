package com.swift.download.player

import com.swift.download.HttpUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * v2.6.0 Phase 1 / Task 1.1：本地流服务器的离线回归。
 *
 * 覆盖三件此前没人守住的事：
 *  1. HEAD 返回与 GET 一致的头但绝不发 body；
 *  2. 两个播放器连接同时请求不同 Range 时字节不错位；
 *  3. TaskBacking 的共享长连接在多连接下不竞态，且重定向后仍保留 Range/Referer。
 */
class StreamServerTest {

    private val data = "0123456789".toByteArray()

    private data class Reply(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0)
    )

    /** 最小 HTTP 服务器：用裸 ServerSocket，JVM 单测不依赖 android.net */
    private class MiniHttp(private val handler: (String, Map<String, String>) -> Reply) : AutoCloseable {
        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val url: String get() = "http://127.0.0.1:$port/file"
        @Volatile private var running = true

        init {
            Thread {
                while (running && !socket.isClosed) {
                    val client = try { socket.accept() } catch (e: Exception) { break }
                    Thread { serve(client) }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; name = "mini-http"; start() }
        }

        private fun serve(client: Socket) {
            try {
                client.soTimeout = 5000
                val input = client.getInputStream()
                val line = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val h = readLine(input) ?: break
                    if (h.isEmpty()) break
                    val i = h.indexOf(':')
                    if (i > 0) headers[h.substring(0, i).lowercase()] = h.substring(i + 1).trim()
                }
                val r = try { handler(line, headers) } catch (e: Exception) {
                    Reply(500, mapOf("Content-Length" to "0"))
                }
                val sb = StringBuilder("HTTP/1.1 ${r.status} ${reason(r.status)}\r\n")
                r.headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
                sb.append("Content-Length: ${r.body.size}\r\nConnection: close\r\n\r\n")
                val out = client.getOutputStream()
                out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                out.write(r.body)
                out.flush()
            } catch (e: Exception) {
                // 客户端主动断开属预期
            } finally {
                try { client.close() } catch (e: Exception) {}
            }
        }

        private fun reason(s: Int) = when (s) {
            200 -> "OK"; 206 -> "Partial Content"; 302 -> "Found"
            416 -> "Range Not Satisfiable"; else -> "OK"
        }

        override fun close() {
            running = false
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private data class Parsed(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
        /** HEAD 探测用：响应头之后是否还有正文字节（>=0 表示有） */
        val trailing: Int
    )

    private fun exchange(port: Int, raw: String, readBody: Boolean = true): Parsed {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 5000
        val output = socket.getOutputStream()
        output.write(raw.toByteArray(Charsets.ISO_8859_1))
        output.flush()
        val input = socket.getInputStream()
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) break
            sb.append(c.toChar())
            if (sb.endsWith("\r\n\r\n")) break
        }
        val lines = sb.toString().split("\r\n")
        val status = lines[0].split(' ')[1].toInt()
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).lowercase()] = line.substring(i + 1).trim()
        }
        val body: ByteArray
        var trailing = -1
        if (readBody) {
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            body = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(body, off, len - off)
                if (n < 0) break
                off += n
            }
        } else {
            body = ByteArray(0)
            // 服务端会主动关闭；若真发了 body 这里会读到 >=0
            trailing = try { input.read() } catch (e: Exception) { -1 }
        }
        try { socket.close() } catch (e: Exception) {}
        return Parsed(status, headers, body, trailing)
    }

    private fun fileServer(): StreamServer {
        val f = File(createTempDir(), "v.ts")
        f.writeBytes(data)
        return StreamServer(FileBacking(f, "video/mp2t"))
    }

    private fun headRaw(range: String?) =
        "HEAD /file HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
            (if (range != null) "Range: $range\r\n" else "") + "Connection: close\r\n\r\n"

    private fun getRaw(range: String) =
        "GET /file HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: $range\r\nConnection: close\r\n\r\n"

    @Test(timeout = 30_000)
    fun `HEAD返回206完整响应头且不发送body`() {
        val server = fileServer()
        val port = server.start()
        try {
            val r = exchange(port, headRaw("bytes=2-5"), readBody = false)
            assertEquals(206, r.status)
            assertEquals("4", r.headers["content-length"])
            assertEquals("bytes 2-5/${data.size}", r.headers["content-range"])
            assertEquals("bytes", r.headers["accept-ranges"])
            assertTrue("HEAD 不得发 body", r.trailing < 0)

            val full = exchange(port, headRaw(null), readBody = false)
            assertEquals(200, full.status)
            assertEquals("${data.size}", full.headers["content-length"])
            assertEquals(null, full.headers["content-range"])

            val bad = exchange(port, headRaw("bytes=99-"), readBody = false)
            assertEquals(416, bad.status)
            assertEquals("bytes */${data.size}", bad.headers["content-range"])
            assertEquals("0", bad.headers["content-length"])
        } finally {
            server.stop()
        }
    }

    @Test(timeout = 30_000)
    fun `两个连接同时请求不同Range字节正确`() {
        val server = fileServer()
        val port = server.start()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<String> { String(exchange(port, getRaw("bytes=0-3")).body) }
            val b = pool.submit<String> { String(exchange(port, getRaw("bytes=6-9")).body) }
            assertEquals("0123", a.get(10, TimeUnit.SECONDS))
            assertEquals("6789", b.get(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
            server.stop()
        }
    }

    @Test(timeout = 30_000)
    fun `TaskBacking并发fetch不同偏移不发生竞态错位`() {
        val origin = MiniHttp { _, h ->
            val range = h["range"] ?: return@MiniHttp Reply(200, emptyMap(), data)
            val m = Regex("bytes=(\\d+)-").find(range) ?: return@MiniHttp Reply(400)
            val from = m.groupValues[1].toInt()
            val slice = data.copyOfRange(from, data.size)
            Reply(206, mapOf("Content-Range" to "bytes $from-${data.size - 1}/${data.size}"), slice)
        }
        val part = File(createTempDir(), "v.part")
        val backing = TaskBacking(
            partPath = part.absolutePath,
            url = origin.url,
            total = data.size.toLong(),
            mime = "video/mp2t",
            externalWrite = { _, _, _ -> true },
            // 全部字节都视为"盘上没有"，强制走 fetch（才会触及共享长连接状态）
            segments = { mapOf(0L to 0L) }
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<Pair<Int, ByteArray>> {
                val buf = ByteArray(5)
                val got = backing.fetch(0, buf, 5)
                got to buf.copyOf()
            }
            val b = pool.submit<Pair<Int, ByteArray>> {
                val buf = ByteArray(5)
                val got = backing.fetch(5, buf, 5)
                got to buf.copyOf()
            }
            val ra = a.get(10, TimeUnit.SECONDS)
            val rb = b.get(10, TimeUnit.SECONDS)
            assertEquals(5, ra.first)
            assertEquals(5, rb.first)
            assertArrayEquals("01234".toByteArray(), ra.second)
            assertArrayEquals("56789".toByteArray(), rb.second)
        } finally {
            pool.shutdownNow()
            backing.close()
            origin.close()
        }
    }

    @Test(timeout = 30_000)
    fun `HttpUtil跨端口重定向仍携带Range与Referer`() {
        val seen = AtomicReference<Map<String, String>>(emptyMap())
        val target = MiniHttp { _, h ->
            seen.set(h)
            val range = h["range"] ?: return@MiniHttp Reply(200, emptyMap(), data)
            val m = Regex("bytes=(\\d+)-(\\d+)").find(range) ?: return@MiniHttp Reply(400)
            val from = m.groupValues[1].toInt()
            val to = m.groupValues[2].toInt().coerceAtMost(data.size - 1)
            Reply(
                206,
                mapOf("Content-Range" to "bytes $from-$to/${data.size}"),
                data.copyOfRange(from, to + 1)
            )
        }
        val first = MiniHttp { _, _ -> Reply(302, mapOf("Location" to target.url)) }
        try {
            val conn = HttpUtil.open(first.url, referer = "http://ref.test/page", range = "bytes=2-5")
            assertEquals(206, conn.responseCode)
            assertEquals("2345", conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            assertEquals("bytes=2-5", seen.get()["range"])
            assertEquals("http://ref.test/page", seen.get()["referer"])
            assertTrue("UA 应统一", seen.get()["user-agent"]?.contains("Chrome/124") == true)
        } finally {
            first.close()
            target.close()
        }
    }
}

/** 逐字节读 HTTP 头行，避免依赖 JDK 9+ 的 BufferedReader 行为 */
private fun readLine(input: InputStream): String? {
    val sb = StringBuilder()
    while (true) {
        val c = input.read()
        if (c < 0) return if (sb.isEmpty()) null else sb.toString()
        if (c == '\n'.code) return sb.toString().trimEnd('\r')
        sb.append(c.toChar())
    }
}
