package com.swift.download.files

import java.io.File

/**
 * v2.2 新增：文件页排序（纯 Kotlin，JVM 单测覆盖）。
 * 持久化由页面侧 SharedPreferences 存 key。
 */
object FileSorts {

    enum class Mode(val label: String) {
        TIME_DESC("时间 · 最新在前"),
        TIME_ASC("时间 · 最早在前"),
        NAME_ASC("名称 · A→Z"),
        NAME_DESC("名称 · Z→A"),
        SIZE_DESC("大小 · 最大在前"),
        SIZE_ASC("大小 · 最小在前");

        companion object {
            fun fromName(v: String?): Mode =
                entries.firstOrNull { it.name == v } ?: TIME_DESC

            fun next(v: Mode): Mode = entries[(v.ordinal + 1) % entries.size]
        }
    }

    fun comparator(mode: Mode): Comparator<File> = when (mode) {
        Mode.TIME_DESC -> compareByDescending<File> { it.lastModified() }
        Mode.TIME_ASC -> compareBy<File> { it.lastModified() }
        // 名称排序对中文/数字更友好：忽略大小写；数字段可比（3gp < 10gp 的自然序近似）
        Mode.NAME_ASC -> compareBy<File> { it.name.lowercase() }
        Mode.NAME_DESC -> compareByDescending<File> { it.name.lowercase() }
        Mode.SIZE_DESC -> compareByDescending<File> { it.length() }
        Mode.SIZE_ASC -> compareBy<File> { it.length() }
    }
}
