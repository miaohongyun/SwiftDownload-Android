package com.swift.download

/**
 * v2.6.0：并发队列的纯判定（无 Android 依赖，JVM 单测直接覆盖）。
 */
object QueueRules {

    const val MIN_CONCURRENT = 1
    const val MAX_CONCURRENT = 5
    const val DEFAULT_CONCURRENT = 3

    fun normalize(value: Int): Int = value.coerceIn(MIN_CONCURRENT, MAX_CONCURRENT)

    /** 活动任务数是否已达到上限（达到即新任务排队） */
    fun isFull(active: Int, limit: Int): Boolean = active >= normalize(limit)
}
