package com.swift.download.settings

/**
 * 令牌桶限速器（纯 Kotlin，时钟与休眠可注入 → JVM 单测可覆盖）。
 *
 * 桶容量按 1 秒的额度限制，避免长时间空闲后一次性放行过大流量；
 * limitBps <= 0 表示不限速（直接返回，零开销）。
 */
class RateLimiter(
    private val clockNanos: () -> Long = { System.nanoTime() },
    private val sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) }
) {
    private var tokens = 0.0
    private var lastNanos = clockNanos()

    @Synchronized
    fun acquire(bytes: Int, limitBps: Long) {
        if (bytes <= 0 || limitBps <= 0) return
        val now = clockNanos()
        val elapsedNanos = (now - lastNanos).coerceAtLeast(0L)
        lastNanos = now

        val capacity = limitBps.toDouble() // 1 秒容量
        tokens = (tokens + elapsedNanos / 1_000_000_000.0 * limitBps).coerceAtMost(capacity)

        if (tokens >= bytes) {
            tokens -= bytes
            return
        }
        val deficit = bytes - tokens
        tokens = 0.0
        val waitMs = (deficit / limitBps * 1000).toLong()
        sleeper(waitMs)
        lastNanos = clockNanos()
    }
}
