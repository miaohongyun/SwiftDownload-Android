package com.swift.download

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * v2.6.0：文件哈希校验（纯 JDK MessageDigest，无第三方依赖）。
 * 一次读取同时算 MD5 / SHA-1 / SHA-256，避免大文件读三遍。
 */
object FileHash {

    data class Result(
        val md5: String,
        val sha1: String,
        val sha256: String,
        val size: Long
    )

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        val digits = "0123456789abcdef"
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(digits[v ushr 4]).append(digits[v and 0x0F])
        }
    }

    /** 流式计算，适合大文件；调用方在后台线程执行 */
    fun of(input: InputStream, size: Long = -1L): Result {
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        input.use { stream ->
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                md5.update(buf, 0, n)
                sha1.update(buf, 0, n)
                sha256.update(buf, 0, n)
                total += n
            }
        }
        return Result(hex(md5.digest()), hex(sha1.digest()), hex(sha256.digest()),
            if (size >= 0) size else total)
    }

    fun of(file: File): Result = of(file.inputStream(), file.length())

    /**
     * 用户粘贴的官方哈希比对：自动识别算法（按长度），忽略大小写/空格/常见前缀。
     * 返回 null 表示无法识别（长度不是 32/40/64 位十六进制）。
     */
    fun compare(expected: String, result: Result): Match? {
        // 顺序要紧：先去算法前缀（含冒号），再剥离剩余空格/冒号/连字符，
        // 否则 "sha256:" 的冒号会先被清掉，前缀就识别不出来了
        val clean = expected.trim().lowercase()
            .removePrefix("sha256:").removePrefix("sha1:").removePrefix("md5:")
            .replace(Regex("[\\s:-]"), "")
        if (!clean.all { it.isDigit() || it in 'a'..'f' }) return null
        val actual = when (clean.length) {
            32 -> result.md5
            40 -> result.sha1
            64 -> result.sha256
            else -> return null
        }
        return Match(actual == clean, actual)
    }

    data class Match(val ok: Boolean, val actual: String)
}
