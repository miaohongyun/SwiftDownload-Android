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
        root.addView(retryCard())
        root.addView(networkCard())
        root.addView(scheduleCard())
        root.addView(concurrencyCard())
        root.addView(categoryCard())
        root.addView(exportCard())
        root.addView(proxyCard())
        root.addView(btCard())
        root.addView(adBlockCard())

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
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
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

    /** v2.7 Stage1.2：网络瞬断自动重试次数（0..5，0 = 关闭） */
    private fun retryCard(): View {
        val card = Ui.card(context)
        card.addView(Ui.text(context, "网络重连", 15f, Ui.TXT, bold = true))
        val et = Ui.singleLineEt(context, "0 = 关闭自动重试").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(settings.autoRetryCount.toString())
        }
        card.addView(et, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(context, 8f) })
        card.addView(Ui.text(context, "瞬断/5xx 自动重试 0~5 次（间隔 3s→10s→30s）；0 表示失败即停。", 11f, Ui.TXT_SUB),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Ui.dp(context, 4f) })
        val save = Ui.pillBtn(context, "保存", Ui.BLUE, filled = true) {
            val v = et.text.toString().trim().toIntOrNull() ?: 0
            settings.autoRetryCount = v
            Ui.toast(context, if (v > 0) "自动重试 $v 次" else "已关闭自动重试")
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
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText((settings.scheduleStartMinute / 60).toString())
        }
        val end = Ui.singleLineEt(context, "24").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
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
        // v2.7 Stage2.3：TS 完成后无重编码封装 MP4（失败自动保留 .ts）
        card.addView(switchRowNoLabel(context, "m3u8 完成后封装 MP4") { sw ->
            sw.isChecked = settings.autoRemuxMp4
            sw.setOnCheckedChangeListener { _, checked -> settings.autoRemuxMp4 = checked }
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
        val port = Ui.singleLineEt(context, "7890").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(settings.proxyPort.toString()) }
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

    /** v2.7 Stage2.1：BT 代理类型 / 代理对 BT 生效 / 停种 / 上传限速 / 公开 Tracker */
    private fun btCard(): View {
        val card = Ui.card(context)
        card.addView(Ui.text(context, "BT 下载", 15f, Ui.TXT, bold = true))

        // 代理类型：点击在 HTTP / SOCKS5 间切换
        val typeLabel = Ui.text(context, "", 13f, Ui.BLUE)
        fun refreshType() {
            typeLabel.text = "代理类型：" + when (settings.proxyType) {
                com.swift.download.bt.BtConfigRules.ProxyType.HTTP -> "HTTP"
                com.swift.download.bt.BtConfigRules.ProxyType.SOCKS5 -> "SOCKS5"
            } + "（点击切换）"
        }
        refreshType()
        typeLabel.setOnClickListener {
            settings.proxyType = when (settings.proxyType) {
                com.swift.download.bt.BtConfigRules.ProxyType.HTTP -> com.swift.download.bt.BtConfigRules.ProxyType.SOCKS5
                else -> com.swift.download.bt.BtConfigRules.ProxyType.HTTP
            }
            refreshType()
        }
        card.addView(typeLabel, lpWrapTop(8f))

        card.addView(switchRow(context, "代理对 BT 生效", "关闭时 BT 直连，不受 HTTP 代理影响") { sw ->
            val cur = settings.proxyForBt ?: settings.proxyEnabled
            sw.isChecked = cur
            sw.setOnCheckedChangeListener { _, checked -> settings.proxyForBt = checked }
        })

        // 停种策略：点击循环 立即 → 延迟5分钟 → 一直做种
        val seedLabel = Ui.text(context, "", 13f, Ui.BLUE)
        fun refreshSeed() {
            seedLabel.text = "完成后：" + when (settings.btSeedingPolicy) {
                com.swift.download.bt.BtConfigRules.SeedingPolicy.IMMEDIATE -> "立即停种"
                com.swift.download.bt.BtConfigRules.SeedingPolicy.DELAY_5MIN -> "延迟 5 分钟停种"
                com.swift.download.bt.BtConfigRules.SeedingPolicy.ALWAYS -> "一直做种"
            } + "（点击切换）"
        }
        refreshSeed()
        seedLabel.setOnClickListener {
            settings.btSeedingPolicy = when (settings.btSeedingPolicy) {
                com.swift.download.bt.BtConfigRules.SeedingPolicy.IMMEDIATE -> com.swift.download.bt.BtConfigRules.SeedingPolicy.DELAY_5MIN
                com.swift.download.bt.BtConfigRules.SeedingPolicy.DELAY_5MIN -> com.swift.download.bt.BtConfigRules.SeedingPolicy.ALWAYS
                else -> com.swift.download.bt.BtConfigRules.SeedingPolicy.IMMEDIATE
            }
            refreshSeed()
        }
        card.addView(seedLabel, lpWrapTop(8f))

        val upload = Ui.singleLineEt(context, "0 = 不限速").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(settings.btUploadMbps.toString())
        }
        card.addView(upload, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(context, 8f) })
        card.addView(Ui.text(context, "上传限速 MB/s。", 11f, Ui.TXT_SUB), lpWrapTop(4f))

        card.addView(switchRow(context, "自动追加公开 Tracker", "磁力任务追加精选 Tracker，加速冷门资源寻源") { sw ->
            sw.isChecked = settings.btAutoTracker
            sw.setOnCheckedChangeListener { _, checked -> settings.btAutoTracker = checked }
        })

        val save = Ui.pillBtn(context, "保存上传限速", Ui.BLUE, filled = true) {
            settings.btUploadMbps = upload.text.toString().trim().toIntOrNull() ?: 0
            Ui.toast(context, "BT 设置已保存")
        }
        card.addView(save, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(context, 8f) })
        card.layoutParams = cardLp()
        return card
    }

    /** v2.7 Stage3.1：浏览器广告拦截总开关 + 自定义规则 */
    private fun adBlockCard(): View {
        val card = Ui.card(context)
        card.addView(Ui.text(context, "浏览器净化", 15f, Ui.TXT, bold = true))
        card.addView(switchRowNoLabel(context, "拦截网页广告与恶意跳转") { sw ->
            sw.isChecked = settings.adBlockEnabled
            sw.setOnCheckedChangeListener { _, checked -> settings.adBlockEnabled = checked }
        })
        val etRules = Ui.singleLineEt(context, "每行一条：域名或含协议的地址").apply {
            setText(settings.adBlockRules)
        }
        etRules.isSingleLine = false
        etRules.maxLines = 5
        card.addView(etRules, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(context, 8f) })
        card.addView(Ui.text(context, "内置规则已拦截常见广告/统计域名；自定义规则每行一条，# 开头为注释。", 11f, Ui.TXT_SUB),
            lpWrapTop(4f))
        card.addView(Ui.pillBtn(context, "保存自定义规则", Ui.BLUE, filled = true) {
            settings.adBlockRules = etRules.text.toString()
            Ui.toast(context, "拦截规则已保存（新页面生效）")
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(context, 8f) })
        card.layoutParams = cardLp()
        return card
    }

    private fun lpWrapTop(dp: Float) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Ui.dp(context, dp) }

    private fun concurrencyCard(): View {
        val card = Ui.card(context)
        card.addView(Ui.text(context, "最大同时下载任务数", 15f, Ui.TXT, bold = true))
        val et = Ui.singleLineEt(context, "1-5").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(settings.maxConcurrent.toString())
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.text(context, "个（1-5）", 12f, Ui.TXT_SUB),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = Ui.dp(context, 8f) })
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 8f) })
        card.addView(Ui.text(context, "超出的任务排队，每完成一个自动补位。", 11f, Ui.TXT_SUB),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 4f) })
        card.addView(Ui.pillBtn(context, "保存", Ui.BLUE, filled = true) {
            settings.maxConcurrent = et.text.toString().trim().toIntOrNull()
                ?: com.swift.download.QueueRules.DEFAULT_CONCURRENT
            Ui.toast(context, "最大同时下载：${settings.maxConcurrent} 个")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 8f) })
        card.layoutParams = cardLp()
        return card
    }

    /** v2.6.0：Android 10+ 完成后导出到系统 Download/极速下载 */
    private fun exportCard(): View {
        val card = Ui.card(context)
        card.addView(switchRow(context, "完成后导出到系统下载目录",
            "Android 10+ 写入 Download/极速下载，文件管理器直接可见") { sw ->
            sw.isChecked = settings.exportToPublicDownloads
            sw.setOnCheckedChangeListener { _, checked -> settings.exportToPublicDownloads = checked }
        })
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
