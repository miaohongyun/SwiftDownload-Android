package com.swift.download

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 下载根目录解析（v2.5 新增）。
 *
 * 背景：Android 11+（API 30+）分区存储强制生效后，应用不能再用 File API 直接
 * 写公共下载目录 `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`，
 * 写入会抛 EACCES / FileNotFoundException —— 表现为"下载一开始就失败"，
 * 但错误信息晦涩（Permission denied），用户与排查者都难以定位。
 *
 * 完全改为 MediaStore 需要重写整个随机写路径（多线程分段 + 断点续传都依赖
 * RandomAccessFile 随机写），代价与回归风险都很高。
 *
 * 这里采用"探测 + 降级"：
 *  - 公共目录可写（Android 9 及以下、部分厂商 ROM 放行）→ 继续用公共目录，
 *    文件位置、FileProvider 分享、文件页浏览与 v1.0 完全一致；
 *  - 公共目录不可写（Android 11+ 标准行为）→ 回退到应用专属外部目录
 *    `getExternalFilesDir(DIRECTORY_DOWNLOADS)`，该路径无需任何权限、在所有
 *    Android 版本都可写，保证"下载"这一核心功能始终可用。
 *
 * 结果在进程内缓存，保证服务与文件页看到的是同一个根目录。
 */
object StorageRoots {

    @Volatile private var cached: File? = null

    /** 公共下载目录（不保证可写，仅作为探测目标） */
    fun publicDownloads(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** 应用专属外部下载目录（始终可写，无需权限） */
    fun appDownloads(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir, "")

    /**
     * 解析实际使用的下载根目录，并确保目录存在。
     * 结果缓存，保证同进程内一致。
     */
    fun downloadRoot(ctx: Context): File {
        cached?.let { if (it.isDirectory) return it }
        val app = ctx.applicationContext
        val public = publicDownloads()
        val chosen = if (isWritable(public)) public else appDownloads(app)
        try {
            chosen.mkdirs()
        } catch (e: Exception) {
            // 目录建不出来时退到应用专属目录
            val fallback = appDownloads(app)
            fallback.mkdirs()
            cached = fallback
            return fallback
        }
        cached = chosen
        return chosen
    }

    /** 是否退回到了应用专属目录（UI 可据此提示用户文件位置） */
    fun usingFallback(ctx: Context): Boolean {
        val root = cached ?: return false
        return root.absolutePath.startsWith(appDownloads(ctx).absolutePath)
    }

    /**
     * 目录是否真的可写：必须实际创建并删除一个临时文件，
     * 仅判断 exists()/canWrite() 在分区存储下会给出乐观的错误结论。
     */
    private fun isWritable(dir: File): Boolean {
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val probe = File(dir, ".swift_write_probe_${System.nanoTime()}")
            if (probe.exists()) return false
            probe.writeBytes(ByteArray(1))
            probe.delete()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 供单元测试调用（生产代码不依赖） */
    fun isWritableForTest(dir: File): Boolean = isWritable(dir)
}
