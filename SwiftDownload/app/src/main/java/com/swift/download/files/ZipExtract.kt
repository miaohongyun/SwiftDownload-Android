package com.swift.download.files

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * v2.7 Stage4.3：ZIP 安全解压（纯 JDK ZipInputStream）。
 * 核心价值在 [safeRelativePath]：把 ZipEntry 名归一化为安全相对路径，
 * 拒绝 ../ 路径穿越（Zip Slip）、绝对路径与盘符，供 JVM 单测重点覆盖。
 */
object ZipExtract {

    /** 解压结果 */
    data class Result(val files: Int, val dirs: Int, val failed: String? = null)

    /**
     * 校验并归一化 ZipEntry 路径，返回相对 destDir 的安全相对路径；不安全则返回 null。
     *  - 反斜杠统一成正斜杠（Windows 生成的 zip 常见）
     *  - 拒绝绝对路径（/ 或 盘符 C:）、空段、'.'、'..'
     *  - 规范化后必须仍在合法相对路径内（防 ../../ 穿越）
     */
    fun safeRelativePath(entryName: String): String? {
        if (entryName.isBlank()) return null
        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith("/")) return null           // 绝对路径
        if (Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null // 盘符
        val parts = ArrayList<String>()
        for (seg in normalized.split('/')) {
            when (seg) {
                "", "." -> continue                            // 忽略空段与当前目录
                ".." -> return null                            // 明确拒绝回退段（Zip Slip）
                else -> parts.add(seg)
            }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("/")
    }

    /** 目标文件是否落在 destDir 之内（规范化路径前缀校验，二次保险） */
    fun isInside(destDir: File, target: File): Boolean {
        val dest = destDir.canonicalPath + File.separator
        return target.canonicalPath.startsWith(dest)
    }

    /**
     * 解压 zip 到 destDir/同名文件夹。返回解压统计；任一 entry 不安全则整体中止。
     * 纯 JDK，不在主线程调用。
     */
    fun extract(zip: File, destDir: File): Result {
        var files = 0
        var dirs = 0
        try {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var e: ZipEntry?
                while (true) {
                    e = zis.nextEntry ?: break
                    val rel = safeRelativePath(e.name)
                        ?: return Result(files, dirs, "发现不安全的压缩路径：${e.name}")
                    val target = File(destDir, rel)
                    if (!isInside(destDir, target)) {
                        return Result(files, dirs, "压缩路径越界：${e.name}")
                    }
                    if (e.isDirectory) {
                        target.mkdirs()
                        dirs++
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> zis.copyTo(out) }
                        files++
                    }
                    zis.closeEntry()
                }
            }
            return Result(files, dirs)
        } catch (ex: Exception) {
            return Result(files, dirs, ex.message ?: "解压失败")
        }
    }
}
