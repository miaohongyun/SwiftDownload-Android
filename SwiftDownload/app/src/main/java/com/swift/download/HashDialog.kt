package com.swift.download

import android.app.AlertDialog
import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import com.swift.download.ui.Ui
import java.io.File

/**
 * v2.6.0：文件哈希校验 / 详情弹窗。后台线程计算，主线程回填，避免卡 UI。
 */
object HashDialog {

    fun show(ctx: Context, file: File) {
        if (!file.isFile) {
            Ui.toast(ctx, "文件不存在，无法校验")
            return
        }
        val status = Ui.text(ctx, "正在计算 MD5 / SHA-1 / SHA-256…", 13f, Ui.TXT_SUB)
        val resultView = Ui.text(ctx, "", 12f, Ui.TXT)
        resultView.setTextIsSelectable(true)
        val et = Ui.singleLineEt(ctx, "粘贴官方哈希值比对（可选）")
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 10f), Ui.dp(ctx, 20f), 0)
            addView(status)
            addView(resultView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 8f) })
            addView(et, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 10f) })
        }
        // 用数组持有可变引用，供「比对」按钮在后台算完后读取
        val holder = arrayOfNulls<FileHash.Result>(1)
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("文件详情 · ${file.name}")
            .setView(wrap)
            .setPositiveButton("比对") { _, _ ->
                val r = holder[0]
                if (r == null) {
                    Ui.toast(ctx, "哈希仍在计算，请稍候")
                } else {
                    val match = FileHash.compare(et.text.toString(), r)
                    when {
                        match == null -> Ui.toast(ctx, "无法识别该哈希（需 32/40/64 位十六进制）")
                        match.ok -> Ui.toast(ctx, "✓ 哈希一致：文件完整")
                        else -> Ui.toast(ctx, "✗ 哈希不一致：文件可能已损坏或被替换")
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()
        Thread {
            val r = try { FileHash.of(file) } catch (e: Exception) { null }
            holder[0] = r
            resultView.post {
                if (!dialog.isShowing) return@post
                if (r == null) {
                    status.text = "计算失败：文件无法读取"
                } else {
                    status.text = "大小 ${Format.bytes(r.size)}  ·  路径 ${file.absolutePath}"
                    resultView.text = buildString {
                        append("MD5     ").append(r.md5).append('\n')
                        append("SHA-1   ").append(r.sha1).append('\n')
                        append("SHA-256 ").append(r.sha256)
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
