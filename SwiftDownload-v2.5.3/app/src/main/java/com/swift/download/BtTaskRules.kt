package com.swift.download

/**
 * v2.6.0：BT 任务状态保护的纯规则，供 ticker 复用并由 JVM 单测直接覆盖。
 *
 * ticker 每 500ms 采样一次 BT 进度。暂停/失败的任务其 error 字段是有意义的
 * （排队原因或失败原因），无条件 `db.updateProgress(..., "")` 会把这些信息抹掉。
 */
object BtTaskRules {

    /** 是否允许 ticker 把进度与空错误写回数据库 */
    fun canWriteDbProgress(status: DownloadTask.Status): Boolean =
        status == DownloadTask.Status.RUNNING || status == DownloadTask.Status.PROBING

    /** p.finished 是否足以把任务标为已完成（暂停/失败的任务不能被翻盘） */
    fun canFinish(status: DownloadTask.Status): Boolean =
        status == DownloadTask.Status.RUNNING || status == DownloadTask.Status.PROBING
}
