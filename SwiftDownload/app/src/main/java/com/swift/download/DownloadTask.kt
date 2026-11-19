package com.swift.download

/**
 * 下载任务模型（v1.0 字段签名完全保持，v2.0 新增 kind / hash 两个可空字段用于 BT 任务）。
 * v1.0 构造签名：(id, url, fileName, savePath, totalBytes, downloadedBytes, status, threads, createdAt, error)
 */
data class DownloadTask(
    val id: Long,
    val url: String,
    val fileName: String,
    val savePath: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: Status,
    val threads: Int,
    val createdAt: Long,
    val error: String = "",
    // ---- v2.0 新增 ----
    val kind: Int = KIND_HTTP,
    val hash: String = "",
    // ---- v2.5 新增：m3u8 任务的清晰度选择与来源页（跨进程重启保留）----
    val variant: String = "",
    val referer: String = "",
    // ---- v2.6.0 新增：登录态与 UA 透传（v4 迁移）----
    val cookie: String = "",
    val userAgent: String = "",
    /** MediaStore 导出后的 content:// URI（v4 迁移，供打开/重播兜底） */
    val exportUri: String = "",
    /** v2.7 Stage1.4：重定向后的最终 URL（v5 迁移，任务详情展示） */
    val finalUrl: String = ""
) {
    enum class Status private constructor(val value: Int, val label: String) {
        QUEUED(0, "等待"),
        PROBING(1, "准备就绪"),
        RUNNING(2, "下载中"),
        PAUSED(3, "暂停"),
        COMPLETED(4, "已完成"),
        FAILED(5, "失败");

        companion object {
            private val map = entries.associateBy(Status::value)
            fun fromInt(v: Int): Status = map[v] ?: QUEUED
        }
    }

    val isHttp: Boolean get() = kind == KIND_HTTP
    val isBt: Boolean get() = kind == KIND_BT
    val isM3u8: Boolean get() = kind == KIND_M3U8

    companion object {
        const val KIND_HTTP = 0
        const val KIND_BT = 1
        const val KIND_M3U8 = 2
    }
}
