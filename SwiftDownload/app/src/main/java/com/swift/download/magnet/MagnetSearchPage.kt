package com.swift.download.magnet

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.swift.download.Format
import com.swift.download.ui.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2.0 新增：磁力搜索页。
 * 搜索框 + 源状态条 + 结果列表（名称 / 大小 / 来源数 / 做种 / 日期）；
 * 点结果 → 确认开始 BT 下载；长按 → 复制磁力链接。
 */
@SuppressLint("ViewConstructor")
class MagnetSearchPage(
    context: Context,
    private val onDownload: (magnetUri: String) -> Unit,
    /** v2.9.0：影视站直搜命中站点后交给宿主在浏览器打开详情页 */
    private val onOpenSite: (url: String) -> Unit = { _ -> },
    /** v2.9.0：浏览器「影视站搜（免代理）」入口直接以站点直搜模式打开 */
    private val initialSiteMode: Boolean = false
) : FrameLayout(context) {

    private val store = MagnetSourceStore.get(context)
    private val scope = CoroutineScope(Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    /** v2.9.0：false=全网磁力索引（需代理），true=影视站直搜（国内 5G 免代理） */
    private var siteMode = initialSiteMode
    private val sitePrefs = context.getSharedPreferences("site_direct_search", Context.MODE_PRIVATE)
    private lateinit var modeRow: LinearLayout
    private lateinit var siteList: LinearLayout
    private val settings = com.swift.download.settings.DownloadSettings.get(context)

    private val etKeyword: EditText
    private val tvStatus: TextView
    private val rv: RecyclerView
    /** 0 结果时显示空状态并与结果列表互斥 */
    private val emptyView: View
    private val detailPanel: LinearLayout
    private val adapter = ResultAdapter()
    private var lastReport: MagnetSearch.SearchReport? = null
    private var detailExpanded = false
    /** 搜索进行中：清空旧结果时不显示「未找到资源」，避免加载态误报空 */
    private var searching = false

    init {
        setBackgroundColor(Ui.BG)
        val header = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        etKeyword = Ui.singleLineEt(context, "搜索影视 / 音乐 / 资源关键词").apply {
            setPadding(Ui.dp(context, 14f), Ui.dp(context, 10f), Ui.dp(context, 14f), Ui.dp(context, 10f))
            background = null
        }
        val searchCard = Ui.card(context, 24f).apply { addView(etKeyword) }
        val searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchRow.addView(searchCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = Ui.dp(context, 8f)
        })
        val btn = TextView(context).apply {
            text = "搜索"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.BLUE); cornerRadius = Ui.dp(context, 24f).toFloat()
            }
            setOnClickListener { doSearch() }
        }
        searchRow.addView(btn, LinearLayout.LayoutParams(Ui.dp(context, 88f), Ui.dp(context, 48f)))
        header.addView(searchRow)

        // v2.9.0：双模式切换 —— 全网索引（境外源需代理） / 影视站直搜（国内 5G 免代理）
        modeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(modeRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(context, 10f) })
        renderModeChips()

        siteList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        header.addView(siteList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(context, 8f) })

        tvStatus = Ui.text(context, "聚合 ${store.enabled().size} 个磁力源 · 点结果开始下载", 12f, Ui.TXT_SUB)
        header.addView(tvStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(context, 4f), Ui.dp(context, 10f), Ui.dp(context, 4f), Ui.dp(context, 6f))
        })
        tvStatus.setOnClickListener {
            detailExpanded = !detailExpanded
            renderDetail()
        }
        detailPanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        header.addView(detailPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rv = Ui.recyclerView(context)
        rv.adapter = adapter
        emptyView = Ui.emptyState(
            context, "搜", "未找到资源",
            "换个关键词，或到「磁力源管理」检查源状态",
            "换关键词",
            onAction = {
                // 真实状态转移：聚焦关键词输入框并唤起键盘，用户可直接改词重搜
                etKeyword.requestFocus()
                etKeyword.post {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                    imm?.showSoftInput(
                        etKeyword,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT
                    )
                }
            }
        )
        emptyView.visibility = View.GONE

        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(context, 12f), Ui.dp(context, 10f), Ui.dp(context, 12f), 0)
        })
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(emptyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (siteMode) {
            rv.visibility = View.GONE
            siteList.visibility = View.VISIBLE
            tvStatus.text = "国内影视站免代理直搜 · 打开详情页后点「抓资源」提取磁力"
            renderSiteCards("")
        }
    }

    fun prefillKeyword(kw: String) {
        etKeyword.setText(kw)
        doSearch()
    }

    /* ---------------- v2.9.0：全网索引 / 影视站直搜双模式 ---------------- */

    private fun renderModeChips() {
        modeRow.removeAllViews()
        val a = Ui.chip(context, "全网磁力索引", !siteMode) { setMode(false) }
        val b = Ui.chip(context, "影视站直搜（免代理）", siteMode) { setMode(true) }
        modeRow.addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = Ui.dp(context, 6f)
        })
        modeRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun setMode(site: Boolean) {
        if (siteMode == site) return
        siteMode = site
        renderModeChips()
        rv.visibility = if (site) View.GONE else View.VISIBLE
        emptyView.visibility = View.GONE
        siteList.visibility = if (site) View.VISIBLE else View.GONE
        tvStatus.text = if (site) {
            "国内影视站免代理直搜 · 打开详情页后点「抓资源」提取磁力"
        } else {
            "聚合 ${store.enabled().size} 个磁力源 · 境外源需代理"
        }
        if (site) renderSiteCards(etKeyword.text.toString().trim())
    }

    /** 影视站直搜：把关键词套进站点模板，列出可直接打开的结果入口 */
    private fun renderSiteCards(keyword: String) {
        siteList.removeAllViews()
        val raw = sitePrefs.getString("custom_sites", "").orEmpty()
        val sites = SiteDirectSearch.merged(raw)
        for (s in sites) {
            val card = Ui.card(context, 14f).apply { orientation = LinearLayout.HORIZONTAL }
            val label = Ui.text(context, s.name, 14f, Ui.TXT, bold = true)
            card.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val hint = Ui.text(context, "免代理", 11f, Ui.GREEN)
            card.addView(hint, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = Ui.dp(context, 8f) })
            val go = Ui.text(context, "搜索 ›", 13f, Ui.BLUE, bold = true)
            card.addView(go)
            Ui.pressable(card)
            card.setOnClickListener {
                val kw = etKeyword.text.toString().trim()
                if (kw.isEmpty()) { Ui.toast(context, "请先输入关键词"); return@setOnClickListener }
                val url = s.urlFor(kw)
                onOpenSite(url)
            }
            card.setOnLongClickListener { showSiteTemplateDialog(); true }
            siteList.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(context, 8f) })
        }
        val add = Ui.pillBtn(context, "＋ 添加影视站搜索模板", Ui.BLUE) { showSiteTemplateDialog() }
        siteList.addView(add, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(context, 2f) })
        siteList.visibility = View.VISIBLE
        if (keyword.isNotEmpty()) tvStatus.text = "输入关键词后点站点卡片，直接在浏览器站内搜索“$keyword”"
    }

    private fun showSiteTemplateDialog() {
        val etName = Ui.singleLineEt(context, "站点名，如 高清族")
        val etUrl = Ui.singleLineEt(context, "https://site/?s={kw}（必须含 {kw}）")
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 20f), Ui.dp(context, 8f), Ui.dp(context, 20f), 0)
            addView(etName)
            addView(etUrl, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(context, 8f) })
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("添加影视站搜索模板")
            .setMessage("模板里用 {kw} 代表关键词；长按站点卡片可重新设置本站列表。")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val site = SiteDirectSearch.parseTemplate("${etName.text}=${etUrl.text}")
                if (site == null) {
                    Ui.toast(context, "格式不对：名称=https://…?s={kw}")
                } else {
                    val raw = sitePrefs.getString("custom_sites", "").orEmpty()
                    val list = SiteDirectSearch.decodeCustom(raw).filterNot { it.name == site.name } + site
                    sitePrefs.edit().putString("custom_sites", SiteDirectSearch.encodeCustom(list)).apply()
                    renderSiteCards(etKeyword.text.toString().trim())
                    Ui.toast(context, "已添加 ${site.name}")
                }
            }
            .setNeutralButton("恢复内置") { _, _ ->
                sitePrefs.edit().putString("custom_sites", "").apply()
                renderSiteCards(etKeyword.text.toString().trim())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doSearch() {
        val kw = etKeyword.text.toString().trim()
        if (kw.isEmpty()) { Ui.toast(context, "请输入关键词"); return }
        if (siteMode) {
            tvStatus.text = "点下方站点卡片，用“$kw”直搜影视站；打开详情页后点「抓资源」提取磁力"
            renderSiteCards(kw)
            return
        }
        tvStatus.text = "正在聚合搜索“$kw”…"
        searching = true
        adapter.submit(emptyList())
        val sources = store.enabled()
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                val first = MagnetSearch.search(kw, sources, MagnetSearch.defaultFetcher())
                // v2.8 模块四 4.2：对空壳/被拦截源启用离屏 WebView 等 JS 渲染后兜底
                val fallbackIds = first.outcomes
                    .filter { it.status != MagnetSearch.SourceStatus.OK }
                    .map { it.sourceId }
                if (fallbackIds.isEmpty()) return@withContext first
                val fallbackRaw = LinkedHashMap<String, List<MagnetParsers.RawItem>>()
                for (s in sources.filter { it.id in fallbackIds }) {
                    val html = OffscreenHtmlFetcher.fetch(context, s.urlFor(kw))
                    if (!html.isNullOrBlank()) {
                        val items = MagnetParsers.parse(s.parser, html)
                        if (items.isNotEmpty()) fallbackRaw[s.id] = items
                    }
                }
                MagnetSearch.mergeFallback(first, fallbackRaw)
            }
            lastReport = report
            searching = false
            adapter.submit(report.results)
            tvStatus.text = buildString {
                append("找到 ${report.results.size} 条")
                if (report.okSources.isNotEmpty()) append(" · ${report.okSources.size}/${sources.size} 源在线")
                if (report.failedSources.isNotEmpty()) append(" · 失效 ${report.failedSources.size} 个")
                append(" · 点此看各源明细")
            }
            // v2.9.0：全源不可达时给明确出路，不再让用户对着一堆「连接超时」干等
            if (report.results.isEmpty() && report.okSources.isEmpty() && sources.isNotEmpty()) {
                tvStatus.text = SiteDirectSearch.unreachableHint(settings.proxyEnabled)
                // 超时也可能来自代理本身，再给一键切换入口
                Ui.toast(context, "可点上方「影视站直搜（免代理）」继续找资源")
            }
            renderDetail()
        }
    }

    /** v2.8 模块四：点状态栏展开每个源的命中/超时/拦截明细 */
    private fun renderDetail() {
        detailPanel.removeAllViews()
        val report = lastReport
        if (!detailExpanded || report == null) {
            detailPanel.visibility = View.GONE
            return
        }
        detailPanel.visibility = View.VISIBLE
        if (report.outcomes.isEmpty()) {
            detailPanel.addView(Ui.text(context, "暂无源明细，先搜索一次", 11f, Ui.TXT_SUB))
            return
        }
        val names = store.sources().associate { it.id to it.name }
        for (o in report.outcomes) {
            val label = MagnetSourceRules.statusLabel(o.status, o.count)
            val color = if (o.status == MagnetSearch.SourceStatus.OK && o.count > 0) Ui.BLUE else Ui.TXT_SUB
            detailPanel.addView(Ui.text(context, "· ${names[o.sourceId] ?: o.sourceId}：$label", 11f, color))
        }
    }

    fun destroy() { scope.cancel() }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultVh>() {
        private val items = ArrayList<MagnetSearch.Result>()
        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<MagnetSearch.Result>) {
            items.clear(); items.addAll(list)
            notifyDataSetChanged()
            val empty = list.isEmpty() && !searching
            rv.visibility = if (empty) View.GONE else View.VISIBLE
            emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultVh {
            val v = buildRow(parent.context)
            return ResultVh(v)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(h: ResultVh, pos: Int) { h.bind(items[pos]) }
    }

    private inner class ResultVh(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        fun bind(r: MagnetSearch.Result) {
            row.removeAllViews()
            row.addView(buildBoundContent(row.context, r))
        }
    }

    private fun buildBoundContent(ctx: Context, r: MagnetSearch.Result): View {
        val card = Ui.card(ctx)
        val title = Ui.text(ctx, r.name, 15f, Ui.TXT, bold = true)
        Ui.ellipsize(title)
        card.addView(title)
        val meta = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val size = r.sizeBytes.let { if (it > 0) Format.bytes(it) else "大小未知" }
        val seed = if (r.seeders >= 0) "做种 ${r.seeders}" else "做种未知"
        val m1 = Ui.text(ctx, size, 12f, Ui.TXT_SUB)
        val m2 = Ui.text(ctx, " · $seed", 12f, Ui.TXT_SUB)
        val m3 = Ui.text(ctx, " · ${r.sources.size} 个来源命中", 12f, Ui.BLUE)
        val m4 = Ui.text(ctx, r.date?.let { " · $it" } ?: "", 12f, Ui.TXT_SUB)
        listOf(m1, m2, m3, m4).forEach {
            meta.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(ctx, 6f)
        })
        card.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("开始磁力下载")
                .setMessage("${r.name}\n\n${r.sizeBytes.let { if (it > 0) Format.bytes(it) else "大小未知" }} · 来源 ${r.sources.joinToString()}\n冷门资源可能需等待 1~3 分钟出文件名。")
                .setPositiveButton("下载") { _, _ -> onDownload(r.magnetUri) }
                .setNegativeButton("取消", null)
                .show()
        }
        card.setOnLongClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("magnet", r.magnetUri))
            Ui.toast(ctx, "已复制磁力链接")
            true
        }
        return card
    }

    private fun buildRow(ctx: Context): LinearLayout {
        val out = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.setMargins(0, Ui.dp(ctx, 6f), 0, 0)
        out.layoutParams = p
        return out
    }
}
