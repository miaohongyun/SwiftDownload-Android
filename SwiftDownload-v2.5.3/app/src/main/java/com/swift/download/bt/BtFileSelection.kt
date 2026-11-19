package com.swift.download.bt

import com.frostwire.jlibtorrent.Priority

/**
 * v2.6.0：BT 种子内文件选择的纯逻辑（不触碰 jlibtorrent native 会话，JVM 单测可覆盖）。
 */
data class BtFile(
    val index: Int,
    val name: String,
    val path: String,
    val size: Long,
    val wanted: Boolean
)

object BtFileSelection {

    /** 未选中的文件一律 IGNORE，选中的用 NORMAL —— 对应 jlibtorrent 的 prioritizeFiles */
    fun priorities(fileCount: Int, selected: Set<Int>): Array<Priority> =
        Array(fileCount.coerceAtLeast(0)) { i ->
            if (i in selected) Priority.NORMAL else Priority.IGNORE
        }

    /** 过滤越界索引：种子文件数变化或传入脏数据时不能把 priorities 撑爆 */
    fun normalize(selected: Set<Int>, fileCount: Int): Set<Int> =
        selected.filter { it >= 0 && it < fileCount }.toSet()

    fun all(fileCount: Int): Set<Int> = (0 until fileCount.coerceAtLeast(0)).toSet()

    /** 单视频种子：体积最大的视频文件，用于"就绪即可播放"与默认勾选建议 */
    fun largestVideo(files: List<BtFile>): BtFile? = files
        .filter { isVideoName(it.name) }
        .maxByOrNull { it.size }

    fun isVideoName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp4", "mkv", "mov", "avi", "flv", "webm", "m4v", "ts", "wmv")
    }

    /** 分片进度（0..1）判定文件是否已完整落盘，供顺序下载时提前播放 */
    fun isReady(doneBytes: Long, totalBytes: Long): Boolean =
        totalBytes > 0L && doneBytes >= totalBytes
}
