package com.swift.download.remote

import com.swift.download.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.6.0：局域网远程投递协议层纯逻辑测试 */
class LanRemoteProtocolTest {

    private fun task(id: Long, name: String, status: DownloadTask.Status, dl: Long, total: Long) =
        DownloadTask(
            id = id, url = "http://x.test/$name", fileName = name, savePath = "/tmp/$name",
            totalBytes = total, downloadedBytes = dl, status = status,
            threads = 4, createdAt = 0L
        )

    @Test
    fun classifyLinks() {
        assertEquals(LanRemoteProtocol.LinkKind.HTTP, LanRemoteProtocol.classify("https://x.test/a.mp4"))
        assertEquals(LanRemoteProtocol.LinkKind.HTTP, LanRemoteProtocol.classify("HTTP://x.test/a"))
        assertEquals(
            LanRemoteProtocol.LinkKind.MAGNET,
            LanRemoteProtocol.classify("magnet:?xt=urn:btih:ABCDEF")
        )
        assertEquals(LanRemoteProtocol.LinkKind.INVALID, LanRemoteProtocol.classify("ftp://x.test/a"))
        assertEquals(LanRemoteProtocol.LinkKind.INVALID, LanRemoteProtocol.classify("javascript:alert(1)"))
    }

    @Test
    fun jsonEscapesSpecialChars() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", LanRemoteProtocol.json("a\"b\\c\nd"))
    }

    @Test
    fun tasksJsonHasExpectedFieldsAndPct() {
        val json = LanRemoteProtocol.tasksJson(listOf(
            task(1, "电影\"高清\".mp4", DownloadTask.Status.RUNNING, 50, 100),
            task(2, "磁力", DownloadTask.Status.COMPLETED, 10, 10)
        ))
        assertTrue(json.startsWith("[") && json.endsWith("]"))
        assertTrue(json.contains("\"id\":1"))
        assertTrue(json.contains("\\\"高清\\\""))
        assertTrue(json.contains("\"pct\":50"))
        assertTrue(json.contains("\"status\":\"已完成\""))
        assertTrue(json.contains("\"status\":\"下载中\""))
    }

    @Test
    fun consoleHtmlHasAllControls() {
        val html = LanRemoteProtocol.consoleHtml()
        assertTrue(html.contains("/api/add"))
        assertTrue(html.contains("/api/torrent"))
        assertTrue(html.contains("/api/tasks"))
        assertTrue(html.contains("setInterval(load,2000)"))
    }

    @Test
    fun resultJson() {
        assertEquals("{\"ok\":true,\"msg\":\"已添加\"}", LanRemoteProtocol.resultJson(true, "已添加"))
    }
}
