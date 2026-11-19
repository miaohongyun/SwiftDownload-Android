package com.swift.download.settings

/**
 * v2.7 Stage1.1：「刷新链接保留断点」判定（纯逻辑，JVM 单测覆盖）。
 *
 * 只有当新链接的探测结果与旧任务完全一致时可保留 .part / .swiftprog：
 * - 探测拿到确定大小（totalBytes > 0）
 * - 新旧大小完全相同（否则断点偏移无意义）
 * - 服务器支持 Range（否则无法续传）
 * - 新链接仍是 HTTP(S)（kind 不变，HLS 任务不适用断点保留）
 */
object RefreshRules {

    data class ResumeDecision(val keep: Boolean, val reason: String = "")

    fun decide(
        keepRequested: Boolean,
        oldTotalBytes: Long,
        newTotalBytes: Long,
        acceptRanges: Boolean,
        newKindIsHttp: Boolean
    ): ResumeDecision = when {
        !keepRequested -> ResumeDecision(false, "用户选择从头下载")
        !newKindIsHttp -> ResumeDecision(false, "新链接不是可断点续传的 HTTP 直链")
        newTotalBytes <= 0L -> ResumeDecision(false, "新链接未返回确定大小，无法对齐断点")
        oldTotalBytes <= 0L -> ResumeDecision(false, "旧任务无大小记录，无法对齐断点")
        newTotalBytes != oldTotalBytes -> ResumeDecision(false, "文件大小不一致（原 $oldTotalBytes / 新 $newTotalBytes）")
        !acceptRanges -> ResumeDecision(false, "新服务器不支持分段续传")
        else -> ResumeDecision(true)
    }
}
