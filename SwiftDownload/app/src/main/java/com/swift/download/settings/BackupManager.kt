package com.swift.download.settings

import android.content.Context
import com.swift.download.browser.QuickSites
import com.swift.download.magnet.MagnetSourceStore
import com.swift.download.settings.BackupCodec.BackupData
import com.swift.download.settings.BackupCodec.MagnetOverride
import com.swift.download.settings.BackupCodec.Site
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * v2.7 Stage4.4：配置/书签/磁力源/广告规则的一键导出与导入（Context 绑定）。
 * JSON 编解码在 [BackupCodec]（纯逻辑、可单测）；这里只负责数据的收集与落位。
 */
class BackupManager(private val ctx: Context) {

    private val settings = DownloadSettings.get(ctx)
    private val quickSites = QuickSites.get(ctx)
    private val magnetStore = MagnetSourceStore.get(ctx)

    /** 收集当前全部配置 */
    fun collect(): BackupData {
        val sites = quickSites.all().map { Site(it.name, it.url) }
        // 磁力源：导出全部当前状态（含默认源的开关/地址覆盖）
        val mags = magnetStore.sources().map {
            MagnetOverride(it.id, it.searchUrl, it.enabled)
        }
        return BackupData(
            version = BackupCodec.VERSION,
            timestamp = System.currentTimeMillis(),
            sites = sites,
            magnetOverrides = mags,
            adRules = settings.adBlockRules,
            settings = settings.exportBackupMap()
        )
    }

    /** 把备份 JSON 落盘到 files 目录（供 MediaStore/选择器读取），返回文件 */
    fun writeBackupFile(data: BackupData, dir: File): File {
        dir.mkdirs()
        val f = File(dir, backupFileName(data.timestamp))
        f.writeText(BackupCodec.toJson(data))
        return f
    }

    /** 恢复：书签合并、磁力源覆盖、广告规则、下载设置；返回恢复条目概要 */
    fun restore(data: BackupData, overwrite: Boolean): RestoreSummary {
        // 书签：按 URL 合并（overwrite 时全量替换用户书签）
        if (overwrite) {
            for (s in quickSites.all()) quickSites.remove(s.url)
        }
        for (s in data.sites) quickSites.upsert(null, s.name, s.url)

        // 磁力源：按 id 应用地址与开关覆盖
        for (m in data.magnetOverrides) {
            if (m.searchUrl.isNotBlank()) magnetStore.setUrl(m.id, m.searchUrl)
            magnetStore.setEnabled(m.id, m.enabled)
        }

        // 广告自定义规则
        settings.adBlockRules = data.adRules
        // 下载设置
        settings.applyBackupMap(data.settings)

        return RestoreSummary(
            sites = data.sites.size,
            magnets = data.magnetOverrides.size,
            settings = data.settings.size
        )
    }

    data class RestoreSummary(val sites: Int, val magnets: Int, val settings: Int)

    companion object {
        /** 时间戳文件名：SwiftDownload_Backup_yyyyMMdd_HHmm.json */
        fun backupFileName(timestamp: Long): String {
            val df = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
            return "SwiftDownload_Backup_${df.format(java.util.Date(timestamp))}.json"
        }

        @Volatile private var inst: BackupManager? = null
        fun get(ctx: Context): BackupManager =
            inst ?: synchronized(this) {
                inst ?: BackupManager(ctx.applicationContext).also { inst = it }
            }
    }
}
