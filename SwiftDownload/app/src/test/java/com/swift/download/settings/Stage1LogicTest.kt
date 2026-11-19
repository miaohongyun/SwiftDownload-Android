package com.swift.download.settings

import com.swift.download.StorageGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** v2.7 Stage1：重试策略 / 断点保持 / 存储阈值 纯逻辑单测 */
class Stage1LogicTest {

    // ---------- 1.2 重试策略 ----------

    @Test
    fun transientExceptionsAreRecoverable() {
        assertTrue(RetryPolicy.isRecoverable(SocketTimeoutException("timeout")))
        assertTrue(RetryPolicy.isRecoverable(ConnectException("refused")))
        assertTrue(RetryPolicy.isRecoverable(UnknownHostException("dns")))
        assertTrue(RetryPolicy.isRecoverable(EOFException("eof")))
    }

    @Test
    fun transientHttpCodesAreRecoverable() {
        listOf(429, 500, 502, 503, 504).forEach { code ->
            assertTrue("HTTP $code 应可重试", RetryPolicy.isRecoverable(IOException("HTTP $code：服务器错误")))
        }
    }

    @Test
    fun fatalHttpCodesAreNotRecoverable() {
        listOf(400, 401, 403, 404, 410, 416).forEach { code ->
            val e = IOException("HTTP $code：资源不可用")
            assertFalse("HTTP $code 不应重试", RetryPolicy.isRecoverable(e))
            assertTrue("HTTP $code 应判为硬错误", RetryPolicy.isFatal(e))
        }
    }

    @Test
    fun integrityErrorIsNotRecoverable() {
        assertFalse(RetryPolicy.isRecoverable(IOException("分段数据不完整：还缺 12 字节")))
        assertFalse(RetryPolicy.isRecoverable(IOException("分片 3 解密失败")))
    }

    @Test
    fun httpCodeParsing() {
        assertEquals(503, RetryPolicy.httpCodeOf("HTTP 503：服务不可用"))
        assertEquals(200, RetryPolicy.httpCodeOf("http 200 OK"))
        assertEquals(null, RetryPolicy.httpCodeOf("连接超时"))
        assertEquals(null, RetryPolicy.httpCodeOf(null))
    }

    @Test
    fun backoffSequence() {
        assertEquals(0L, RetryPolicy.delayMs(0))
        assertEquals(3_000L, RetryPolicy.delayMs(1))
        assertEquals(10_000L, RetryPolicy.delayMs(2))
        assertEquals(30_000L, RetryPolicy.delayMs(3))
        assertEquals(30_000L, RetryPolicy.delayMs(9)) // 封顶
        assertEquals(5, RetryPolicy.normalizeMax(99))
        assertEquals(0, RetryPolicy.normalizeMax(-1))
        assertEquals(3, RetryPolicy.normalizeMax(3))
    }

    // ---------- 1.1 断点保持 ----------

    @Test
    fun refreshKeepsProgressWhenSizeMatches() {
        val d = RefreshRules.decide(true, 1000L, 1000L, true, true)
        assertTrue(d.keep)
    }

    @Test
    fun refreshRejectsMismatchedSize() {
        assertFalse(RefreshRules.decide(true, 1000L, 999L, true, true).keep)
    }

    @Test
    fun refreshRejectsUnknownOrNoRange() {
        assertFalse(RefreshRules.decide(true, 1000L, -1L, true, true).keep)
        assertFalse(RefreshRules.decide(true, 1000L, 1000L, false, true).keep)
    }

    @Test
    fun refreshRejectsNonHttpOrUnchecked() {
        assertFalse(RefreshRules.decide(true, 1000L, 1000L, true, false).keep)
        assertFalse(RefreshRules.decide(false, 1000L, 1000L, true, true).keep)
    }

    // ---------- 1.3 存储阈值 ----------

    @Test
    fun spaceEnoughBoundary() {
        val required = 1000L
        val need = required + StorageGuard.RESERVE_BYTES
        assertFalse(StorageGuard.hasEnough(need - 1, required))
        assertTrue(StorageGuard.hasEnough(need, required))
        assertTrue(StorageGuard.hasEnough(need + 1, required))
    }

    @Test
    fun spaceUnknownSizeOrUnavailableAlwaysPass() {
        assertTrue(StorageGuard.hasEnough(0L, -1L)) // 未知大小不阻断
        assertTrue(StorageGuard.hasEnough(-1L, 5000L)) // StatFs 失败不阻断
    }

    @Test
    fun insufficientMessageHasBothNumbers() {
        val msg = StorageGuard.insufficientMessage(1024L, 10L * 1024 * 1024)
        assertTrue(msg.contains("存储空间不足"))
        assertTrue(msg.contains("剩余"))
    }
}
