package com.swift.download.remote

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.6.0：局域网 Web 远程投递面板（纯 JDK ServerSocket，默认关闭）。
 * 电脑浏览器打开 http://<手机IP>:<端口> 即可粘贴链接/上传种子推送任务。
 *
 * 仅绑定局域网地址，无鉴权 —— 仅建议在可信家庭/办公 Wi-Fi 下临时开启。
 */
class LanRemoteServer(
    private val controller: Controller,
    private val port: Int = 8877
) {
    interface Controller {
        fun tasksJson(): String
        fun addUrl(url: String): Boolean
        fun addTorrent(fileName: String, bytes: ByteArray): Boolean
    }

    private val socket = ServerSocket()
    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null

    fun start(): Int {
        if (running.get()) return port
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("0.0.0.0", port))
        running.set(true)
        thread = Thread {
            while (running.get()) {
                val client = try { socket.accept() } catch (e: Exception) { break }
                Thread { handle(client) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; name = "lan-remote" }
        thread?.start()
        return socket.localPort
    }

    fun stop() {
        running.set(false)
        try { socket.close() } catch (e: Exception) {}
        thread = null
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15000
            val input = client.getInputStream()
            // 读请求行
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0) ?: "GET"
            val rawPath = parts.getOrNull(1) ?: "/"
            val path = rawPath.substringBefore('?')
            val query = if ('?' in rawPath) rawPath.substringAfter('?') else ""

            when {
                method == "GET" && (path == "/" || path == "/index.html") ->
                    respond(client, 200, "text/html; charset=utf-8", LanRemoteProtocol.consoleHtml().toByteArray(Charsets.UTF_8))
                method == "GET" && path == "/api/tasks" ->
                    respond(client, 200, "application/json; charset=utf-8",
                        controller.tasksJson().toByteArray(Charsets.UTF_8))
                method == "GET" && path == "/api/add" -> {
                    val url = queryParam(query, "url")
                    val ok = if (url.isNotBlank()) controller.addUrl(url) else false
                    val message = when {
                        url.isBlank() -> "链接为空"
                        LanRemoteProtocol.classify(url) == LanRemoteProtocol.LinkKind.INVALID -> "仅支持 http(s):// 或 magnet: 链接"
                        ok -> "已添加"
                        else -> "添加失败"
                    }
                    respond(client, 200, "application/json; charset=utf-8",
                        LanRemoteProtocol.resultJson(ok, message).toByteArray(Charsets.UTF_8))
                }
                method == "POST" && path == "/api/torrent" -> {
                    val result = readMultipart(input, headers["content-length"]?.toLongOrNull() ?: 0L,
                        headers["content-type"].orEmpty())
                    val ok = if (result != null && result.name.endsWith(".torrent", true)) {
                        controller.addTorrent(result.name, result.bytes)
                    } else false
                    val message = if (result == null) "未收到文件" else if (!result.name.endsWith(".torrent", true)) "仅支持 .torrent 文件" else if (ok) "已添加" else "添加失败"
                    respond(client, 200, "application/json; charset=utf-8",
                        LanRemoteProtocol.resultJson(ok, message).toByteArray(Charsets.UTF_8))
                }
                else -> respond(client, 404, "text/plain; charset=utf-8", "Not Found".toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            // 客户端断开/超时属正常
        } finally {
            try { client.close() } catch (e: Exception) {}
        }
    }

    private data class Upload(val name: String, val bytes: ByteArray)

    /** 极简 multipart/form-data 解析：只取第一个文件字段，单请求体上限 64MB */
    private fun readMultipart(input: java.io.InputStream, contentLength: Long, contentType: String): Upload? {
        if (contentLength <= 0L || contentLength > 64L * 1024 * 1024) return null
        val boundary = Regex("boundary=(.+)").find(contentType)?.groupValues?.get(1)?.trim()?.trim('"')
            ?: return null
        val raw = ByteArray(contentLength.toInt())
        var off = 0
        while (off < raw.size) {
            val n = input.read(raw, off, raw.size - off)
            if (n < 0) break
            off += n
        }
        val text = String(raw, 0, off, Charsets.ISO_8859_1)
        val disposition = Regex("Content-Disposition:[^\r\n]*filename=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.substringAfterLast('\\')?.substringAfterLast('/')
            ?: return null
        val marker = "--$boundary"
        val dataStart = text.indexOf("\r\n\r\n")
        if (dataStart < 0) return null
        val bodyStart = dataStart + 4
        val endMarker = text.indexOf("\r\n$marker", bodyStart)
        val bodyEnd = if (endMarker >= 0) endMarker else off
        if (bodyEnd <= bodyStart) return null
        return Upload(disposition, raw.copyOfRange(bodyStart, bodyEnd))
    }

    private fun queryParam(query: String, key: String): String {
        for (pair in query.split('&')) {
            val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrNull(1).orEmpty() }
            if (k == key) return try {
                URLDecoder.decode(v, "UTF-8")
            } catch (e: Exception) { v }
        }
        return ""
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun respond(client: Socket, code: Int, type: String, body: ByteArray) {
        val reason = if (code == 200) "OK" else "Not Found"
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(body)
            flush()
        }
    }
}
