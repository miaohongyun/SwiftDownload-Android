package com.swift.download

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * v1.0 数据库完全保持：swift_download.db / tasks 表 / v1 建表语句原文。
 * v2.0 通过 onUpgrade ALTER TABLE 增加 kind、hash 两列，老任务数据全量保留（kind 默认 0 = HTTP）。
 */
class DownloadDatabase private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_V1)
        // v2.0 直接新建的库也带新列（等价于 v1 建表后补 ALTER）
        db.execSQL("ALTER TABLE tasks ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN hash TEXT NOT NULL DEFAULT ''")
        // v2.5：m3u8 任务选定的变体地址与来源页（重启恢复需要）
        db.execSQL("ALTER TABLE tasks ADD COLUMN variant TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE tasks ADD COLUMN referer TEXT NOT NULL DEFAULT ''")
        // v2.6.0：新建库也补齐 v4 列
        MIGRATE_V4.forEach(db::execSQL)
        // v2.7：新建库也补齐 v5 列
        MIGRATE_V5.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 → v2：非破坏性迁移，老任务行原样保留
            db.execSQL("ALTER TABLE tasks ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE tasks ADD COLUMN hash TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) {
            // v2 → v3：新增两列，老行补默认值
            db.execSQL("ALTER TABLE tasks ADD COLUMN variant TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tasks ADD COLUMN referer TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 4) {
            // v3 → v4：登录态/UA 透传 + MediaStore 导出 URI。
            // 全部 ALTER TABLE ADD COLUMN，老任务行原样保留。
            MIGRATE_V4.forEach(db::execSQL)
        }
        if (oldVersion < 5) {
            // v4 → v5：最终重定向 URL（任务详情展示）。非破坏性增量迁移。
            MIGRATE_V5.forEach(db::execSQL)
        }
    }

    fun insert(task: DownloadTask): Long {
        val cv = ContentValues()
        cv.put("url", task.url)
        cv.put("file_name", task.fileName)
        cv.put("save_path", task.savePath)
        cv.put("total_bytes", task.totalBytes)
        cv.put("downloaded_bytes", task.downloadedBytes)
        cv.put("status", task.status.value)
        cv.put("threads", task.threads)
        cv.put("error", task.error)
        cv.put("created_at", task.createdAt)
        cv.put("kind", task.kind)
        cv.put("hash", task.hash)
        cv.put("variant", task.variant)
        cv.put("referer", task.referer)
        cv.put("cookie", task.cookie)
        cv.put("user_agent", task.userAgent)
        cv.put("export_uri", task.exportUri)
        cv.put("final_url", task.finalUrl)
        return writableDatabase.insert("tasks", null, cv)
    }

    fun updateProgress(id: Long, downloaded: Long, status: DownloadTask.Status, error: String) {
        val cv = ContentValues()
        cv.put("downloaded_bytes", downloaded)
        cv.put("status", status.value)
        cv.put("error", error)
        writableDatabase.update("tasks", cv, "id=?", arrayOf(id.toString()))
    }

    /**
     * v2.5.2：换链接后旧任务的一切派生信息都失效（类型、文件名、落盘路径、变体、进度）。
     * 只改 url 会让新链接续到旧 .part 上，产出内容错位的文件。
     */
    fun resetForRefresh(id: Long, url: String, fileName: String, savePath: String, kind: Int) {
        val cv = ContentValues()
        cv.put("url", url)
        cv.put("file_name", fileName)
        cv.put("save_path", savePath)
        cv.put("kind", kind)
        cv.put("variant", "")
        cv.put("total_bytes", -1)
        cv.put("downloaded_bytes", 0)
        cv.put("error", "")
        cv.put("status", DownloadTask.Status.QUEUED.value)
        writableDatabase.update("tasks", cv, "id=?", arrayOf(id.toString()))
    }

    fun updateMeta(id: Long, fileName: String, totalBytes: Long) {
        val cv = ContentValues()
        cv.put("file_name", fileName)
        cv.put("total_bytes", totalBytes)
        writableDatabase.update("tasks", cv, "id=?", arrayOf(id.toString()))
    }

    /** v2.7 Stage1.4：记录重定向后的最终 URL */
    fun updateFinalUrl(id: Long, finalUrl: String) {
        val cv = ContentValues()
        cv.put("final_url", finalUrl)
        writableDatabase.update("tasks", cv, "id=?", arrayOf(id.toString()))
    }

    /** v2.5：文件改名后同步落盘路径。
     * 服务器 Content-Disposition 给出的真实文件名可能与初始 URL 推断名不同，
     * 若不同步 save_path，完成后的文件会"找不到"（打开/投屏都会失败）。
     */
    fun updateSavePath(id: Long, savePath: String) {
        val cv = ContentValues()
        cv.put("save_path", savePath)
        writableDatabase.update("tasks", cv, "id=?", arrayOf(id.toString()))
    }

    /** v2.7 Stage1.1：仅更新链接与认证头，保留 total_bytes/downloaded_bytes/status/进度文件 */
    fun updateUrlKeepProgress(id: Long, url: String, referer: String, cookie: String) {
        val cv = ContentValues()
        cv.put("url", url)
        cv.put("referer", referer)
        cv.put("cookie", cookie)
        cv.put("error", "")
        writableDatabase.update("tasks", cv, "id=?", arrayOf(id.toString()))
    }

    /** v2.6.0：MediaStore 导出成功后记录可访问路径与 content URI */
    fun markExported(id: Long, savePath: String, uri: String) {
        val cv = ContentValues()
        cv.put("save_path", savePath)
        cv.put("export_uri", uri)
        writableDatabase.update("tasks", cv, "id=?", arrayOf(id.toString()))
    }

    /** v2.5.2：文件页重命名后同步任务记录（按旧路径匹配，可能 0 行） */
    fun renameSavePath(oldPath: String, newPath: String) {
        val cv = ContentValues()
        cv.put("save_path", newPath)
        cv.put("file_name", newPath.substringAfterLast('/'))
        writableDatabase.update("tasks", cv, "save_path=?", arrayOf(oldPath))
    }

    fun updateStatus(id: Long, status: DownloadTask.Status, error: String = "") {
        val cv = ContentValues()
        cv.put("status", status.value)
        cv.put("error", error)
        writableDatabase.update("tasks", cv, "id=?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("tasks", "id=?", arrayOf(id.toString()))
    }

    fun all(): List<DownloadTask> {
        val out = ArrayList<DownloadTask>()
        readableDatabase.rawQuery(
            "SELECT id,url,file_name,save_path,total_bytes,downloaded_bytes,status,threads,error,created_at,kind,hash,variant,referer,cookie,user_agent,export_uri,final_url FROM tasks ORDER BY id DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(DownloadTask(
                    id = c.getLong(0),
                    url = c.getString(1),
                    fileName = c.getString(2),
                    savePath = c.getString(3),
                    totalBytes = c.getLong(4),
                    downloadedBytes = c.getLong(5),
                    status = DownloadTask.Status.fromInt(c.getInt(6)),
                    threads = c.getInt(7),
                    createdAt = c.getLong(9),
                    error = c.getString(8) ?: "",
                    kind = c.getInt(10),
                    hash = c.getString(11) ?: "",
                    variant = c.getString(12) ?: "",
                    referer = c.getString(13) ?: "",
                    cookie = c.getString(14) ?: "",
                    userAgent = c.getString(15) ?: "",
                    exportUri = c.getString(16) ?: "",
                    finalUrl = c.getString(17) ?: ""
                ))
            }
        }
        return out
    }

    companion object {
        const val DB_NAME = "swift_download.db"
        const val DB_VERSION = 5

        /** v2.6.0：v3 → v4 的全部增量列（纯 ALTER，老数据零改动） */
        val MIGRATE_V4 = listOf(
            "ALTER TABLE tasks ADD COLUMN cookie TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE tasks ADD COLUMN user_agent TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE tasks ADD COLUMN export_uri TEXT NOT NULL DEFAULT ''"
        )

        /** v2.7 Stage1.4：v4 → v5 增量列（纯 ALTER，老数据零改动） */
        val MIGRATE_V5 = listOf(
            "ALTER TABLE tasks ADD COLUMN final_url TEXT NOT NULL DEFAULT ''"
        )

        /** v1.0 建表语句原文（从 v1.0 APK 中逐字符提取） */
        const val CREATE_V1 = "CREATE TABLE tasks (\n" +
            "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
            "    url TEXT NOT NULL,\n" +
            "    file_name TEXT NOT NULL,\n" +
            "    save_path TEXT NOT NULL,\n" +
            "    total_bytes INTEGER NOT NULL DEFAULT -1,\n" +
            "    downloaded_bytes INTEGER NOT NULL DEFAULT 0,\n" +
            "    status INTEGER NOT NULL DEFAULT 0,\n" +
            "    threads INTEGER NOT NULL DEFAULT 4,\n" +
            "    error TEXT NOT NULL DEFAULT '',\n" +
            "    created_at INTEGER NOT NULL\n" +
            ")"

        @Volatile private var inst: DownloadDatabase? = null
        fun get(ctx: Context): DownloadDatabase =
            inst ?: synchronized(this) {
                inst ?: DownloadDatabase(ctx.applicationContext).also { inst = it }
            }
    }
}
