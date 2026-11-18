package com.swift.download.player

/** MP4 快速探测（纯 Kotlin，JVM 单测覆盖） */
object VideoProbe {

    /** 前 [bytes] 中是否出现 moov atom —— moov 前置才能可靠拖动进度 */
    fun hasMoovNearStart(bytes: ByteArray): Boolean {
        val needle = byteArrayOf('m'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'v'.code.toByte())
        if (bytes.size < needle.size) return false
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) {
                if (bytes[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
