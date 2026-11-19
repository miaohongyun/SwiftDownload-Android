package com.swift.download.player

import java.io.File

/**
 * v2.7 Stage2.3：HLS 输出格式决策纯逻辑（JVM 单测覆盖）。
 * 真正的 MediaExtractor/MediaMuxer 混流在 Android 侧，失败回退由调用方保证。
 */
object HlsMux {

    /** 是否 fMP4 播放列表（有 EXT-X-MAP 初始化段）→ 输出天然是 .mp4 */
    fun isFmp4(playlist: M3u8.Playlist): Boolean = playlist.map != null

    /**
     * 最终输出扩展名：
     * - fMP4 → mp4
     * - TS + 开启自动封装 → mp4（混流成功后；失败回退保留 ts）
     * - 否则 ts
     */
    fun outputExt(isFmp4: Boolean, autoRemux: Boolean): String =
        if (isFmp4 || autoRemux) "mp4" else "ts"

    /** 把路径扩展名换成目标扩展名 */
    fun withExt(path: String, ext: String): String {
        val base = path.substringBeforeLast('.', path)
        return "$base.$ext"
    }

    /** 封装产物临时文件（成功后替换正式文件） */
    fun muxTempFile(finalPath: String): File = File(finalPath + ".remux.tmp")

    /** 混流输出是否可接受：文件存在且非空；否则应静默保留源文件 */
    fun isUsableMuxResult(file: File): Boolean = file.isFile && file.length() > 0L
}
