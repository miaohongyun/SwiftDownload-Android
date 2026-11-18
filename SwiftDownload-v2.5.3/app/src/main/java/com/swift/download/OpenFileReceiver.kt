package com.swift.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * v1.0 行为保持：通知/界面的"打开文件"动作 → FileProvider 授权 → 外部应用打开。
 * 兼容 Android 7+ FileUriExposed 限制。
 */
class OpenFileReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_OPEN_FILE -> openFile(context, intent.getStringExtra(EXTRA_PATH))
        }
    }

    private fun openFile(context: Context, path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(context, "文件已不存在", Toast.LENGTH_SHORT).show() // v1.0 原文
            return
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, guessMime(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (view.resolveActivity(context.packageManager) != null) {
                context.startActivity(view)
            } else {
                Toast.makeText(context, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show() // v1.0 原文
            }
        } catch (e: Exception) {
            Toast.makeText(context, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "ts" -> "video/mp2t"
            "m4v" -> "video/x-m4v"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/x-wav"
            "aac" -> "audio/aac"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "pdf" -> "application/pdf"
            "epub" -> "application/epub+zip"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "torrent" -> "application/x-bittorrent"
            "txt" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    companion object {
        const val ACTION_OPEN_FILE = "com.swift.download.OPEN_FILE"
        const val EXTRA_PATH = "path"

        fun intentOf(path: String): Intent =
            Intent(ACTION_OPEN_FILE).apply { putExtra(EXTRA_PATH, path) }
    }
}
