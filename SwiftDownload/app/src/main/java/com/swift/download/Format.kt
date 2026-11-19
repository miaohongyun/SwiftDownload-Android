package com.swift.download

import java.util.Locale

/**
 * 纯 Kotlin 格式化工具（无 Android 依赖，可在 JVM 单元测试中覆盖）。
 * 体积单位统一为 1024 进制（v2.0 回归修复：v1.0 中 MB 曾按 1000 进制，导致与文件管理器显示不一致）。
 */
object Format {

    private const val KB = 1024L
    private const val MB = KB * 1024
    private const val GB = MB * 1024
    private const val TB = GB * 1024

    /** 字节数 → 人类可读（1024 进制），例如 1536 → "1.50 KB" */
    fun bytes(v: Long): String {
        if (v < 0) return "未知"
        if (v < KB) return "$v B"
        return when {
            v >= TB -> String.format(Locale.US, "%.2f TB", v.toDouble() / TB)
            v >= GB -> String.format(Locale.US, "%.2f GB", v.toDouble() / GB)
            v >= MB -> String.format(Locale.US, "%.2f MB", v.toDouble() / MB)
            else -> String.format(Locale.US, "%.2f KB", v.toDouble() / KB)
        }
    }

    /** 速度：字节/秒 → "1.25 MB/s" */
    fun speed(bytesPerSec: Long): String =
        if (bytesPerSec <= 0) "0 B/s" else bytes(bytesPerSec) + "/s"

    /** 剩余时间：秒 → "1 小时 2 分 3 秒"（v1.0 的中文单位） */
    fun eta(seconds: Long): String {
        if (seconds < 0) return "未知"
        if (seconds < 1) return "即将完成"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val sb = StringBuilder()
        if (h > 0) sb.append(h).append(" 小时 ")
        if (m > 0) sb.append(m).append(" 分 ")
        if (s > 0 || sb.isEmpty()) sb.append(s).append(" 秒")
        return sb.toString().trim()
    }

    /** 从 Content-Disposition 中提取 filename（RFC 5987 与 RFC 2047 两种形式） */
    fun fileNameFromDisposition(cd: String?): String? {
        if (cd.isNullOrEmpty()) return null
        // filename*=UTF-8''%E4%B9%A6.zip
        val utf8 = Regex("""filename\*\s*=\s*(?:utf-8|UTF-8)''([^;]+)""").find(cd)
        if (utf8 != null) {
            return try {
                java.net.URLDecoder.decode(utf8.groupValues[1].trim(), "UTF-8")
            } catch (e: Exception) {
                utf8.groupValues[1].trim()
            }
        }
        // filename="book.zip"
        val quoted = Regex("""filename\s*=\s*"([^"]+)"""").find(cd)
        if (quoted != null) return quoted.groupValues[1]
        val plain = Regex("""filename\s*=\s*([^;]+)""").find(cd)
        if (plain != null) return plain.groupValues[1].trim()
        return null
    }
}
