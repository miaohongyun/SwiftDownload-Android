package com.swift.download.settings

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import com.swift.download.ui.Ui

/**
 * 下载设置页（我的 → 下载设置）：限速 / Wi-Fi only / 调度 / 自动分类 / m3u8 / 代理。
 * 全部即时写入 DownloadSettings；关闭页后新任务立即生效。
 */
@SuppressLint("ViewConstructor")
class DownloadSettingsPage(
    context: Context,
    private val settings: DownloadSettings,
    private val onClose: () -> Unit = {}
) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.BG)

        val root = LinearLayout(context).apply { orientation = VERTICAL }

        root.addView(titleCard())
        root.addView(speedCard())
        root.addView(networkCard())
        root.addView(scheduleCard())
        root.addView(categoryCard())
        root.addView(proxyCard())

        val close = Ui.pillBtn(context, "完成", Ui.BLUE, filled = true) { onClose() }
        root.addView(close, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(Ui.dp(context, 14f), Ui.dp(context, 12f), Ui.dp(context, 14f), Ui.dp(context, 24f)) })

        addView(Ui.verticalScroll(context, root), ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun titleCard(): View = Ui.card(context, 16f).apply {
        addView(Ui.text(context, "下载设置", 20f, Ui.TXT, bold = true))
        addView(Ui.text(context, "所有开关默认关闭；仅对新任务生效，不影响进行中的任务。", 12f, Ui.TXT_SUB),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Ui.dp(context, 6f) })
        layoutParams = cardLp()
    }

    private fun speedCard(): View {
        val card = Ui.card(context)
        card.addView(Ui.text(context, "限速", 15f, Ui.TXT, bold = true))
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val et = Ui.singleLineEt(context, "0 = 不限速").apply {
            val current = settings.globalLimitBps
            setText(if (current > 0) (current / 1024 / 1024).toString() else "0")
        }
        row.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.text(context, "MB/s", 13f, Ui.TXT_SUB),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = Ui.dp(context, 8f) })
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = Ui.dp(context, 8f) })
        card.addView(Ui.text(context, "全局速度上限；也可以在传输页对单个任务单独限速。", 11f, Ui.TXT_SUB),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Ui.dp(context, 4f) })
        card.setOnClickListener {
            val v = et.text.toString().trim().toLongOrNull() ?: 0L
            settings.globalLimitBps = v.coerceAtLeast(0) * 1024 * 1024
            Ui.toast(context, if (v > 0) "全局限速 $v MB/s" else "已取消限速")
        }
        // v2.5：显式"保存"按钮。原实现靠点击整张卡片保存，输入框会吃掉点击事件，
        // 用户改了值却没有保存时机，表现为"改了没反应"。
        val save = Ui.pillBtn(context, "保存", Ui.BLUE, filled = true) {
            val v = et.text.toString().trim().toLongOrNull() ?: 0L
            settings.globalLimitBps = v.coerceAtLeast(0) * 1024 * 1024
            Ui.toast(context, if (v > 0) "全局限速 $v MB/s" else "已取消限速")
        }
        card.addView(save, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(context, 8f) })
        card.layoutParams = cardLp()
        return card
    }

    private fun networkCard(): View {
        val card = Ui.card(context)
        val row = switchRow(context, "仅 Wi-Fi 下载", "移动数据下新任务将排队，回到 Wi-Fi 自动开始") { sw ->
            sw.isChecked = settings.networkRule == NetworkRule.WIFI_ONLY
            sw.setOnCheckedChangeListener { _, checked ->
                settings.networkRule = if (checked) NetworkRule.WIFI_ONLY else NetworkRule.ANY
            }
        }
        card.addView(row)
        card.layoutParams = cardLp()
        return card
    }

    private fun scheduleCard(): View {
        val card = Ui.card(context)
        card.addView(Ui.text(context, "下载时段", 15f, Ui.TXT, bold = true))
        card.addView(switchRowNoLabel(context, "只在允许时段下载") { sw ->
            sw.isChecked = settings.scheduleEnabled
            sw.setOnCheckedChangeListener { _, checked -> settings.scheduleEnabled = checked }
        })
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val start = Ui.singleLineEt(context, "0").apply {
            setText((settings.scheduleStartMinute / 60).toString())
        }
        val end = Ui.singleLineEt(context, "24").apply {
            setText((settings.scheduleEndMinute / 60).toString())
        }
        row.addView(start, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.text(context, "点", 13f, Ui.TXT_SUB), lpWrap())
        row.addView(end, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.text(context, "点", 13f, Ui.TXT_SUB), lpWrap())
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = Ui.dp(context, 8f) })
        card.addView(Ui.text(context, "支持跨零点，例如 23 点 → 7 点；点击文字保存。", 11f, Ui.TXT_SUB),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Ui.dp(context, 4f) })
        row.setOnClickListener {
            settings.scheduleStartMinute = (start.text.toString().trim().toIntOrNull() ?: 0).coerceIn(0, 23) * 60
            val endHour = (end.text.toString().trim().toIntOrNull() ?: 24).coerceIn(0, 24)
            settings.scheduleEndMinute = if (endHour >= 24) 1439 else endHour * 60
            Ui.toast(context, "时段已保存")
        }
        // v2.5：显式"保存"按钮，避免输入框吞掉点击导致"改了没反应"
        val save = Ui.pillBtn(context, "保存", Ui.BLUE, filled = true) {
            settings.scheduleStartMinute = (start.text.toString().trim().toIntOrNull() ?: 0).coerceIn(0, 23) * 60
            val endHour = (end.text.toString().trim().toIntOrNull() ?: 24).coerceIn(0, 24)
            settings.scheduleEndMinute = if (endHour >= 24) 1439 else endHour * 60
            Ui.toast(context, "时段已保存")
        }
        card.addView(save, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(context, 8f) })
        card.layoutParams = cardLp()
        return card
    }

    private fun categoryCard(): View {
        val card = Ui.card(context)
        card.addView(switchRow(context, "文件自动分类", "按视频/音频/图片/文档等存入 Download 子目录") { sw ->
            sw.isChecked = settings.autoCategory
            sw.setOnCheckedChangeListener { _, checked -> settings.autoCategory = checked }
        })
        card.addView(switchRowNoLabel(context, "支持 m3u8 流媒体") { sw ->
            sw.isChecked = settings.m3u8Enabled
            sw.setOnCheckedChangeListener { _, checked -> settings.m3u8Enabled = checked }
        })
        card.layoutParams = cardLp()
        return card
    }

    private fun proxyCard(): View {
        val card = Ui.card(context)
        card.addView(switchRow(context, "HTTP 代理", "对直链下载与磁力抓取生效") { sw ->
            sw.isChecked = settings.proxyEnabled
            sw.setOnCheckedChangeListener { _, checked -> settings.proxyEnabled = checked }
        })
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val host = Ui.singleLineEt(context, "127.0.0.1").apply { setText(settings.proxyHost) }
        val port = Ui.singleLineEt(context, "7890").apply { setText(settings.proxyPort.toString()) }
        row.addView(host, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        row.addView(port, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = Ui.dp(context, 8f) })
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = Ui.dp(context, 8f) })
        card.addView(Ui.text(context, "点击地址行保存", 11f, Ui.TXT_SUB),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Ui.dp(context, 4f) })
        row.setOnClickListener {
            settings.proxyHost = host.text.toString()
            settings.proxyPort = port.text.toString().trim().toIntOrNull() ?: 7890
            Ui.toast(context, "代理已保存（新任务生效）")
        }
        // v2.5：显式"保存"按钮，避免输入框吞掉点击导致"改了没反应"
        val save = Ui.pillBtn(context, "保存", Ui.BLUE, filled = true) {
            settings.proxyHost = host.text.toString()
            settings.proxyPort = port.text.toString().trim().toIntOrNull() ?: 7890
            Ui.toast(context, "代理已保存（新任务生效）")
        }
        card.addView(save, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(context, 8f) })
        card.layoutParams = cardLp()
        return card
    }

    private fun switchRow(
        ctx: Context, label: String, hint: String, setup: (Switch) -> Unit
    ): View {
        val line = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        col.addView(Ui.text(ctx, label, 15f, Ui.TXT, bold = true))
        col.addView(Ui.text(ctx, hint, 11f, Ui.TXT_SUB),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Ui.dp(ctx, 2f) })
        line.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(ctx).also(setup)
        line.addView(sw)
        return line
    }

    private fun switchRowNoLabel(ctx: Context, label: String, setup: (Switch) -> Unit): View {
        val line = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        line.addView(Ui.text(ctx, label, 13f, Ui.TXT),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(Switch(ctx).also(setup))
        return line
    }

    private fun lpWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { marginStart = Ui.dp(context, 6f); marginEnd = Ui.dp(context, 6f) }

    private fun cardLp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Ui.dp(context, 10f) }
}
