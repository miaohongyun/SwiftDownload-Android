package com.swift.download.magnet

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.swift.download.ui.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 磁力源管理页（我的 → 磁力源管理）。
 * 开关每个源；长按改地址 / 删除自定义源；域名轮换后可自查；
 * v2.8 模块四：新增「＋ 添加新磁力源」与「一键测试连通性」，逐源显示命中数/403/超时诊断。
 */
@SuppressLint("ViewConstructor")
class MagnetSourceManagerPage(context: Context) : FrameLayout(context) {

    private val store = MagnetSourceStore.get(context)
    // 铁律：所有属性必须在 init 之前声明
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val testResults = HashMap<String, String>()

    init {
        setBackgroundColor(Ui.BG)
        rebuild()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun rebuild() {
        removeAllViews()
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(Ui.text(
            context,
            "开启的源参与聚合搜索；长按源卡片可改地址/删除。测试连通性可查看每个源的实时状态。",
            12f, Ui.TXT_SUB
        ), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(context, 14f), Ui.dp(context, 12f), Ui.dp(context, 14f), Ui.dp(context, 8f))
        })

        // 三个操作按钮：添加新源 + 批量导入 + 一键测试
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(primaryBtn(context, "＋ 添加") { showAddDialog(context) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(context, 6f) })
        actions.addView(primaryBtn(context, "批量导入") { showBatchImportDialog(context) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(context, 6f) })
        actions.addView(primaryBtn(context, "测试") { testAll() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(context, 14f), 0, Ui.dp(context, 14f), Ui.dp(context, 6f))
        })
        root.addView(Ui.text(context,
            "批量导入支持「名称|网址」「名称 空格 网址」或 JSON 数组，网址需含 {kw}。",
            11f, Ui.TXT_SUB), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(context, 14f), 0, Ui.dp(context, 14f), Ui.dp(context, 8f))
        })

        for (src in store.sources()) root.addView(buildRow(src))

        val reset = TextView(context).apply {
            text = "恢复默认源配置（会清除自定义源）"
            textSize = 14f
            setTextColor(Ui.RED)
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(context, 14f), 0, Ui.dp(context, 14f))
            setOnClickListener {
                val customCount = store.customSources().size
                AlertDialog.Builder(context)
                    .setTitle("恢复默认源配置")
                    .setMessage("恢复默认？将清除 $customCount 个自定义源。")
                    .setPositiveButton("恢复默认") { _, _ ->
                        store.resetAll()
                        testResults.clear()
                        rebuild()
                        Ui.toast(context, "已恢复默认")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        root.addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 20f)))
        addView(Ui.verticalScroll(context, root), ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun primaryBtn(ctx: Context, label: String, click: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        textSize = 13f
        setTextColor(0xFFFFFFFF.toInt())
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(Ui.dp(ctx, 8f), Ui.dp(ctx, 11f), Ui.dp(ctx, 8f), Ui.dp(ctx, 11f))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Ui.BLUE); cornerRadius = Ui.dp(ctx, 20f).toFloat()
        }
        Ui.pressable(this)
        setOnClickListener { click() }
    }

    private fun buildRow(src: MagnetSource): View {
        val ctx = context
        val card = Ui.card(ctx)
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(Ui.text(ctx, src.name, 15f, Ui.TXT, bold = true))
        if (src.proxyRequired) {
            titleRow.addView(Ui.text(ctx, "需代理", 10f, Ui.ORANGE, bold = true), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Ui.dp(ctx, 8f) })
        }
        if (src.useWebView) {
            titleRow.addView(Ui.text(ctx, "WebView", 10f, Ui.TYPE_HLS, bold = true), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Ui.dp(ctx, 8f) })
        }
        col.addView(titleRow)
        val url = Ui.text(ctx, src.searchUrl, 12f, Ui.TXT_SUB)
        url.maxLines = 1
        url.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        col.addView(url)
        val diag = testResults[src.id]
        if (diag != null) {
            col.addView(Ui.text(ctx, "测试：$diag", 11f, if (diag.contains("条") && !diag.startsWith("0")) Ui.BLUE else Ui.RED))
        }
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
            showLongPressMenu(ctx, src)
            true
        }
        card.setOnClickListener { testOne(src) }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(Ui.dp(ctx, 14f), 0, Ui.dp(ctx, 14f), Ui.dp(ctx, 8f))
        card.layoutParams = lp
        return card
    }

    private fun showLongPressMenu(ctx: Context, src: MagnetSource) {
        val isCustom = store.customSources().any { it.id == src.id }
        val opts = ArrayList<String>()
        opts.add("修改搜索地址")
        if (isCustom) opts.add("完整编辑（名称/代理/WebView）")
        opts.add("测试连通性")
        if (isCustom) opts.add("删除此源")
        AlertDialog.Builder(ctx)
            .setTitle(src.name)
            .setItems(opts.toArray(arrayOfNulls<String>(0))) { _, which ->
                when (opts[which]) {
                    "修改搜索地址" -> showEditDialog(ctx, src)
                    "完整编辑（名称/代理/WebView）" -> showEditCustomDialog(ctx, src)
                    "测试连通性" -> testOne(src)
                    "删除此源" -> {
                        AlertDialog.Builder(ctx)
                            .setTitle("删除磁力源")
                            .setMessage("删除磁力源「${src.name}」？")
                            .setPositiveButton("删除") { _, _ ->
                                store.removeCustom(src.id)
                                testResults.remove(src.id)
                                rebuild()
                                Ui.toast(ctx, "已删除「${src.name}」")
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun showAddDialog(ctx: Context) {
        val etName = Ui.singleLineEt(ctx, "源名称，如：我的资源站")
        val etUrl = Ui.singleLineEt(ctx, "https://example.com/s/{kw}")
        val etParser = Ui.singleLineEt(ctx, "generic（可选：torrentkitty / rowtable）")
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 8f), Ui.dp(ctx, 20f), 0)
            addView(etName, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(etUrl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8f) })
            addView(etParser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8f) })
            addView(Ui.text(ctx, "地址须含 {kw} 占位符；解析规则不确定就留空（通用磁力扫描）。", 11f, Ui.TXT_SUB))
        }
        AlertDialog.Builder(ctx)
            .setTitle("添加新磁力源")
            .setView(wrap)
            .setPositiveButton("添加") { _, _ ->
                val err = store.addCustom(
                    etName.text.toString(),
                    etUrl.text.toString(),
                    etParser.text.toString().ifBlank { "generic" }
                )
                if (err == null) {
                    rebuild()
                    Ui.toast(ctx, "已添加，可在列表里点卡片测试")
                } else Ui.toast(ctx, err)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditDialog(ctx: Context, src: MagnetSource) {
        val et = Ui.singleLineEt(ctx, "https://…/search?keyword={kw}").apply { setText(src.searchUrl) }
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
                } else Ui.toast(ctx, "地址必须包含 {kw} 占位符")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditCustomDialog(ctx: Context, src: MagnetSource) {
        val etName = Ui.singleLineEt(ctx, "源名称").apply { setText(src.name) }
        val etUrl = Ui.singleLineEt(ctx, "https://…/{kw}").apply { setText(src.searchUrl) }
        val etParser = Ui.singleLineEt(ctx, "generic / torrentkitty / rowtable").apply { setText(src.parser) }
        val swProxy = Switch(ctx).apply { isChecked = src.proxyRequired; text = "需代理" }
        val swWebView = Switch(ctx).apply { isChecked = src.useWebView; text = "离屏 WebView 抓取（JS 站/403 盾）" }
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 8f), Ui.dp(ctx, 20f), 0)
            addView(etName)
            addView(etUrl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8f) })
            addView(etParser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8f) })
            addView(swProxy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 10f) })
            addView(swWebView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 6f) })
        }
        AlertDialog.Builder(ctx)
            .setTitle("完整编辑「${src.name}」")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val url = MagnetSourceImport.normalizeUrl(etUrl.text.toString().trim())
                val updated = src.copy(
                    name = etName.text.toString().trim().ifEmpty { src.name },
                    searchUrl = url,
                    parser = MagnetSourceRules.normalizeParser(etParser.text.toString()),
                    proxyRequired = swProxy.isChecked,
                    useWebView = swWebView.isChecked
                )
                val err = MagnetSourceRules.validate(updated.name, updated.searchUrl, updated.parser)
                if (err != null) { Ui.toast(ctx, err); return@setPositiveButton }
                store.updateCustom(updated)
                rebuild()
                Ui.toast(ctx, "已保存")
            }
            .setNeutralButton("测试此源") { _, _ -> testOne(src) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** v2.9.1：批量导入对话框 —— 粘贴后先预览解析结果，确认后一次性持久化 */
    private fun showBatchImportDialog(ctx: Context) {
        val et = Ui.singleLineEt(ctx, "每行一条：名称|https://site/?s={kw}\n或粘贴 JSON 数组")
        et.isSingleLine = false
        et.maxLines = 8
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 8f), Ui.dp(ctx, 20f), 0)
            addView(et)
            addView(Ui.text(ctx, "示例：\n我的站|https://example.com/s/{kw}\n动漫站 https://a.test/search?q={kw}\n[JSON 数组亦可]", 11f, Ui.TXT_SUB),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8f) })
        }
        AlertDialog.Builder(ctx)
            .setTitle("批量导入磁力源")
            .setView(wrap)
            .setPositiveButton("解析并导入") { _, _ ->
                val preview = MagnetSourceImport.parse(et.text.toString())
                if (preview.accepted == 0) {
                    Ui.toast(ctx, "没有可导入的源：${preview.errors.firstOrNull() ?: "格式不正确"}")
                    return@setPositiveButton
                }
                val added = store.addCustomBatch(preview.sources)
                rebuild()
                Ui.toast(ctx, "已导入 $added 个源" +
                    (if (preview.duplicates > 0) "（去重跳过 ${preview.duplicates}）" else "") +
                    (if (preview.errors.isNotEmpty()) "，${preview.errors.size} 行有误" else ""))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun testAll() {
        val sources = store.sources()
        Ui.toast(context, "正在测试 ${sources.size} 个源…")
        for (src in sources) testOne(src, silent = true)
    }

    private fun testOne(src: MagnetSource, silent: Boolean = false) {
        if (!silent) Ui.toast(context, "正在测试「${src.name}」…")
        scope.launch {
            val label = withContext(Dispatchers.IO) {
                try {
                    val html = MagnetSearch.defaultFetcher().fetch(src.urlFor("a"))
                    val items = MagnetParsers.parse(src.parser, html)
                    if (items.isEmpty()) "空壳/0 条" else "${items.size} 条"
                } catch (e: Exception) {
                    MagnetSourceRules.statusLabel(MagnetSearch.classifyFailure(e), 0)
                }
            }
            testResults[src.id] = label
            rebuild()
            if (!silent) Ui.toast(context, "「${src.name}」：$label")
        }
    }

    fun destroy() { scope.cancel() }
}
