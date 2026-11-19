package com.swift.download.settings

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * v2.7 Stage1.2：网络瞬断自动重试策略（纯逻辑，JVM 单测覆盖）。
 *
 * 区分「可恢复瞬断」与「硬错误」：
 * - 可恢复：连接类异常 + 服务端临时错误 429/500/502/503/504 → 指数退避重试
 * - 硬错误：4xx 权限/不存在/范围非法、完整性校验、AES/DRM、空间不足 → 立即失败
 *
 * 不依赖任何 Android 类型，HTTP 状态码由调用方从 IOException.message 中解析后传入
 * （[classify] 同时支持直接从消息文本识别 `HTTP <code>`，便于引擎层零侵入接入）。
 */
object RetryPolicy {

    const val DEFAULT_MAX_RETRIES = 3
    /** 退避序列：3s → 10s → 30s；超出按 30s 封顶 */
    private val DELAYS_MS = longArrayOf(3_000L, 10_000L, 30_000L)

    /** 临时错误 HTTP 状态码 */
    private val TRANSIENT_CODES = setOf(429, 500, 502, 503, 504)
    /** 硬错误 HTTP 状态码：一旦出现，重试无意义 */
    private val FATAL_CODES = setOf(400, 401, 403, 404, 410, 416, 406)

    /** 可恢复的异常类型 */
    fun isTransientException(e: Throwable): Boolean =
        e is SocketTimeoutException || e is ConnectException ||
            e is UnknownHostException || e is EOFException

    /** 从错误文案中提取 `HTTP <code>` 的状态码；无匹配返回 null */
    fun httpCodeOf(message: String?): Int? {
        if (message.isNullOrBlank()) return null
        val m = Regex("""\bHTTP\s+(\d{3})\b""", RegexOption.IGNORE_CASE).find(message) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /** 该错误是否值得重试 */
    fun isRecoverable(e: Throwable): Boolean {
        if (isTransientException(e)) return true
        val code = httpCodeOf(e.message) ?: return false
        return code in TRANSIENT_CODES
    }

    /** 明确的硬错误（优先于 isRecoverable 判断） */
    fun isFatal(e: Throwable): Boolean {
        val code = httpCodeOf(e.message) ?: return false
        return code in FATAL_CODES
    }

    /** 第 attempt 次重试（1-based）的退避毫秒数；0 或负数返回 0 */
    fun delayMs(attempt: Int): Long =
        if (attempt <= 0) 0L else DELAYS_MS[(attempt - 1).coerceAtMost(DELAYS_MS.lastIndex)]

    /** 规范化设置里的重试次数（0..5） */
    fun normalizeMax(value: Int): Int = value.coerceIn(0, 5)
}
