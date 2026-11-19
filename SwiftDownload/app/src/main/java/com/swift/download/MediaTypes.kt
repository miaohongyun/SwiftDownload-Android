package com.swift.download

/**
 * v2.2 新增：媒体类型判定（边下边播 / 投屏 / 播放按钮显隐共用）。
 * 纯 Kotlin 无 Android 依赖，JVM 单测覆盖。
 */
object MediaTypes {

    val VIDEO_EXTS = setOf(
        "mp4", "mkv", "avi", "mov", "flv", "wmv", "ts", "m4v", "webm", "3gp", "mpg", "mpeg", "rmvb"
    )
    val AUDIO_EXTS = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "wma", "ape")

    /** v2.7 Stage4.3：可内置轻量预览的纯文本/日志/字幕类扩展名 */
    val TEXT_EXTS = setOf("txt", "log", "json", "srt", "ass")

    /** v2.7 Stage4.3：文本预览最大读取字节数（512KB），防大文件卡顿 */
    const val TEXT_PREVIEW_MAX_BYTES = 512 * 1024

    fun extOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    fun isTextName(name: String): Boolean = extOf(name) in TEXT_EXTS

    fun isVideoName(name: String): Boolean = extOf(name) in VIDEO_EXTS

    fun isMediaName(name: String): Boolean = extOf(name) in VIDEO_EXTS || extOf(name) in AUDIO_EXTS

    fun mimeOf(name: String): String = when (extOf(name)) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "ts" -> "video/mp2t"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mpg", "mpeg" -> "video/mpeg"
        "rmvb" -> "video/vnd.rn-realvideo"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/x-wav"
        "aac" -> "audio/aac"
        "m4a" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }
}
