package com.swift.download.transfers

import com.swift.download.DownloadTask

/**
 * v2.7 Stage4.2：传输页状态筛选（纯 Kotlin，JVM 单测覆盖）。
 *  - 进行中 = 所有非终态（等待/准备/下载中/暂停），让用户看到"还没结束"的任务。
 */
object TaskFilters {

    enum class Filter { ALL, ACTIVE, COMPLETED, FAILED }

    data class Counts(val all: Int, val active: Int, val completed: Int, val failed: Int)

    fun matches(filter: Filter, status: DownloadTask.Status): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.ACTIVE -> status in setOf(
            DownloadTask.Status.QUEUED,
            DownloadTask.Status.PROBING,
            DownloadTask.Status.RUNNING,
            DownloadTask.Status.PAUSED
        )
        Filter.COMPLETED -> status == DownloadTask.Status.COMPLETED
        Filter.FAILED -> status == DownloadTask.Status.FAILED
    }

    fun filter(tasks: List<DownloadTask>, f: Filter): List<DownloadTask> =
        tasks.filter { matches(f, it.status) }

    fun counts(tasks: List<DownloadTask>): Counts = Counts(
        all = tasks.size,
        active = tasks.count { matches(Filter.ACTIVE, it.status) },
        completed = tasks.count { matches(Filter.COMPLETED, it.status) },
        failed = tasks.count { matches(Filter.FAILED, it.status) }
    )
}
