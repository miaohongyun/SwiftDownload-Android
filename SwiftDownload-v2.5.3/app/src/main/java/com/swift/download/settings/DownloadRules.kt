package com.swift.download.settings

/**
 * 下载调度与归类的纯逻辑（无 Android 依赖，JVM 单测覆盖）。
 */
object DownloadRules {

    data class StartDecision(val allowed: Boolean, val waitReason: String = "")

    /**
     * 是否允许此刻开始下载。
     * [wifiOk] 传 null 表示无法判定网络类型（此时不因 Wi-Fi 规则拦截，避免误伤）。
     * 时间窗口支持跨零点，例如 23:00 → 07:00。
     */
    fun decideStart(
        minuteOfDay: Int,
        wifiOk: Boolean?,
        rule: NetworkRule,
        scheduleEnabled: Boolean,
        startMinute: Int,
        endMinute: Int
    ): StartDecision {
        if (rule == NetworkRule.WIFI_ONLY && wifiOk == false) {
            return StartDecision(false, WAIT_WIFI)
        }
        if (scheduleEnabled && !inWindow(minuteOfDay, startMinute, endMinute)) {
            return StartDecision(false, WAIT_SCHEDULE)
        }
        return StartDecision(true)
    }

    fun inWindow(now: Int, start: Int, end: Int): Boolean =
        if (start == end) true // 起止相同视为全天开放，避免用户配出永远不下载
        else if (start < end) now in start until end
        else now >= start || now < end

    const val WAIT_WIFI = "等待 Wi-Fi 连接"
    const val WAIT_SCHEDULE = "等待允许下载时段"

    fun isWaiting(error: String): Boolean = error == WAIT_WIFI || error == WAIT_SCHEDULE

    /** 按文件名归类到 Download/ 下的子目录；空串表示根目录 */
    fun categoryOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in VIDEO -> "视频"
            in AUDIO -> "音频"
            in IMAGE -> "图片"
            in DOC -> "文档"
            in ARCHIVE -> "压缩包"
            "apk" -> "安装包"
            "torrent" -> "种子"
            else -> ""
        }
    }

    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "flv", "wmv", "ts", "m4v", "webm", "3gp", "mpg", "mpeg", "rmvb")
    private val AUDIO = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "wma", "ape")
    private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
    private val DOC = setOf("pdf", "epub", "txt", "mobi", "doc", "docx", "xls", "xlsx", "ppt", "pptx")
    private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")

    /**
     * 进程重启恢复判定：只恢复 RUNNING/PROBING，绝不恢复用户主动暂停的任务。
     * 抽成纯函数使"僵尸任务"修复可被 JVM 单测覆盖，无需 Robolectric。
     */
    fun shouldResumeAfterRestart(status: com.swift.download.DownloadTask.Status): Boolean =
        status == com.swift.download.DownloadTask.Status.RUNNING ||
            status == com.swift.download.DownloadTask.Status.PROBING

    /** 链接刷新：仅接受 http/https，且去掉首尾空白 */
    fun normalizeRefreshedUrl(raw: String): String? {
        val url = raw.trim()
        return if (url.startsWith("http://", true) || url.startsWith("https://", true)) url else null
    }
}
