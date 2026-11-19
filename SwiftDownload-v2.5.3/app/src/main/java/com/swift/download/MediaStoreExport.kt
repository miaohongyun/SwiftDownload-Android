package com.swift.download

import java.io.File

/**
 * v2.6.0：MediaStore 导出的纯路径规则（无 Android 依赖，JVM 单测直接覆盖）。
 *
 * 下载过程仍全部在可随机写的目录里完成；只有**完成后的最终文件**才复制一份到
 * 公共 `Download/极速下载/`，让系统文件管理器与第三方播放器无需访问 Android/data。
 */
object MediaStoreExport {

    const val ROOT_FOLDER = "极速下载"

    /** MediaStore.RELATIVE_PATH 需要的相对路径，末尾带 `/` */
    fun relativePath(category: String): String =
        if (category.isBlank()) "$ROOT_FOLDER/" else "$ROOT_FOLDER/$category/"

    /** 导出后在文件系统里可见的公共路径 */
    fun publicPath(publicDownloads: File, fileName: String, category: String): String {
        val dir = if (category.isBlank()) File(publicDownloads, ROOT_FOLDER)
            else File(File(publicDownloads, ROOT_FOLDER), category)
        return File(dir, fileName).absolutePath
    }
}
