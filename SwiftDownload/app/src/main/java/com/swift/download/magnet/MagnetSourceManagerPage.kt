package com.swift.download.magnet

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.swift.download.ui.Ui

/**
 * v2.0 新增：磁力源管理页（我的 → 磁力源管理）。
 * 开关每个源；长按改地址（域名轮换自救）；一键恢复默认。
 */
@SuppressLint("ViewConstructor")
class MagnetSourceManagerPage(context: Context) : FrameLayout(context) {

    private val store = MagnetSourceStore.get(context)

    init {
        setBackgroundColor(Ui.BG)
        rebuild()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun rebuild() {
        removeAllViews()
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val hint = Ui.text(
            context,
            "失效源会被自动跳过；域名轮换后长按源卡片可修改搜索地址。",
            12f, Ui.TXT_SUB
        )
        root.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(context, 14f), Ui.dp(context, 12f), Ui.dp(context, 14f), Ui.dp(context, 8f))
        })
        for (src in store.sources()) {
            root.addView(buildRow(src))
        }
        val reset = TextView(context).apply {
            text = "恢复默认源配置"
            textSize = 14f
            setTextColor(Ui.RED)
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(context, 14f), 0, Ui.dp(context, 14f))
            setOnClickListener {
                store.resetAll()
                rebuild()
                Ui.toast(context, "已恢复默认")
            }
        }
        root.addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 20f)))
        addView(Ui.verticalScroll(context, root), ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun buildRow(src: MagnetSource): View {
        val ctx = context
        val card = Ui.card(ctx)
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val name = Ui.text(ctx, src.name, 15f, Ui.TXT, bold = true)
        col.addView(name)
        val url = Ui.text(ctx, src.searchUrl, 12f, Ui.TXT_SUB)
        url.maxLines = 1
        url.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        col.addView(url)
        line.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(ctx).apply {
            isChecked = src.enabled
            setOnCheckedChangeListener { _, checked ->
                store.setEnabled(src.id, checked)
                src.enabled = checked
            }
        }
        line.addView(sw)
        card.addView(line)
        card.setOnLongClickListener {
            showEditDialog(ctx, src)
            true
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(Ui.dp(ctx, 14f), 0, Ui.dp(ctx, 14f), Ui.dp(ctx, 8f))
        card.layoutParams = lp
        return card
    }

    private fun showEditDialog(ctx: Context, src: MagnetSource) {
        val et = Ui.singleLineEt(ctx, "https://…/search?keyword={kw}").apply {
            setText(src.searchUrl)
        }
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 8f), Ui.dp(ctx, 20f), 0)
            addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Ui.text(ctx, "地址需含 {kw} 占位符，代表搜索关键词。", 11f, Ui.TXT_SUB))
        }
        AlertDialog.Builder(ctx)
            .setTitle("修改「${src.name}」地址")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val url = et.text.toString().trim()
                if (url.contains("{kw}")) {
                    store.setUrl(src.id, url)
                    rebuild()
                    Ui.toast(ctx, "已保存")
                } else {
                    Ui.toast(ctx, "地址必须包含 {kw} 占位符")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
