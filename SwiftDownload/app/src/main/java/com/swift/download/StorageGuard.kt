package com.swift.download

/**
 * v2.7 Stage1.3：存储空间前置校验（纯逻辑，JVM 单测覆盖）。
 * 平台相关的 StatFs 读取放在 [StorageRoots]，这里只做阈值判断与提示文案。
 */
object StorageGuard {

    /** 额外缓冲余量 32MB：给文件系统元数据、.part 与临时文件留空间 */
    const val RESERVE_BYTES = 32L * 1024 * 1024

    /**
     * 空间是否足够容纳待下载内容。
     * [required] <= 0（未知大小）时不校验，直接通过 —— 避免把未知长度的流误判为空间不足。
     */
    fun hasEnough(availableBytes: Long, required: Long, reserve: Long = RESERVE_BYTES): Boolean {
        if (required <= 0L) return true
        if (availableBytes < 0L) return true // 无法判定（StatFs 失败）时不阻断
        return availableBytes >= required + reserve
    }

    /** 空间不足时的错误文案 */
    fun insufficientMessage(availableBytes: Long, required: Long, reserve: Long = RESERVE_BYTES): String =
        "存储空间不足（需 ${Format.bytes(required + reserve)}，剩余 ${Format.bytes(availableBytes.coerceAtLeast(0))}）"
}
