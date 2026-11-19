package com.swift.download.browser

import com.swift.download.HttpUtil

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.swift.download.player.VideoSniff
import com.swift.download.settings.DownloadSettings
import com.swift.download.ui.Ui
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * v2.0 新增：内置多标签浏览器。
 *  - 多标签（＋新建 / 点切换 / 长按关闭）
 *  - 地址栏 + 顶部进度条 + 后退/前进/刷新/主页/☆收藏
 *  - 首页：搜索栏 + 网页引擎（必应）+ 磁力引擎入口 + 书签卡片（添加/长按编辑置顶删除）
 *  - 图片类站点：JS + DOM Storage + 缩放支持
 *  - 下载接管：页面下载请求 → 多线程下载引擎
 *  - 磁力接管：页面 magnet: 链接 → BT 引擎一键下载
 *  - v2.1：地址栏☆一键收藏当前页；书签可编辑/置顶；我的页→书签管理
 */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
class BrowserPage(
    context: Context,
    private val onNewDownload: (url: String, suggestedName: String, referer: String, cookie: String) -> Unit,
    private val onNewMagnet: (magnetUri: String) -> Unit,
    private val onOpenMagnetSearch: (keyword: String) -> Unit,
    /** 嗅探到页面视频：master 地址、建议文件名、来源页 Referer */
    private val onVideoDetected: (url: String, name: String, referer: String, cookie: String) -> Unit,
    /** v2.8 模块一：.torrent 链接 → 拉取种子到缓存后直接建 BT 任务 */
    private val onTorrentLink: (url: String, referer: String, cookie: String) -> Unit = { _, _, _ -> }
) : FrameLayout(context) {

    data class Tab(
        val id: Int,
        var title: String,
        var url: String,
        var webView: WebView? = null,
        /** v2.6.0：本标签嗅探到的视频链接（url → 名称），角标与复核用 */
        val sniffed: LinkedHashMap<String, String> = LinkedHashMap()
    )

    private var nextId = 1
    private val tabs = ArrayList<Tab>()
    private var current: Tab? = null

    private val contentHost: FrameLayout
    private val tabsStrip: LinearLayout
    private val tabsScroller: HorizontalScrollView
    private val etUrl: EditText
    private val progress: ProgressBar
    private var homepage: View

    /** v2.8 模块二：单手体验相关（全部声明在 init 之前，遵守成员顺序铁律） */
    /** 地址栏容器（首页展开 / 网页态收起为紧凑标题） */
    private lateinit var addressBar: LinearLayout
    /** 顶部紧凑标题栏（网页态显示域名/标题，点击展开地址栏） */
    private lateinit var compactTitle: TextView
    private var addressExpanded = false
    /** 浏览器底部工具栏（单手拇指热区） */
    private lateinit var bottomToolbar: LinearLayout
    private lateinit var btnBack: TextView
    private lateinit var btnForward: TextView
    /** v2.8 模块四 4.1：一键抓资源按钮（发现资源时文字高亮） */
    private lateinit var btnGrab: TextView

    /** v2.7 Stage3.2：桌面版 UA 标志（切换后自动刷新当前页） */
    private var desktopMode = false

    /**
     * v2.7 真机修复：以下属性必须声明在 init 块之前。
     * Kotlin 按源码顺序初始化属性；若写在 init 之后，init 里对 sniffBadge 的赋值
     * 会被随后的 `= null` 覆盖（角标永远不显示），其它 init 期访问的属性则会 NPE。
     */
    /** 嗅探结果角标（数量 = 当前标签去重后的视频链接数） */
    private var sniffBadge: TextView? = null
    /** 视频嗅探：同一页面只提示一次，避免分片请求反复弹窗 */
    private var sniffKey: String? = null
    /** v2.8 模块三：本次会话已接受的证书 host（不持久化） */
    private val sslAcceptedHosts = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    private val siteStore = QuickSites.get(context)
    /** v2.7 Stage3.1：广告拦截开关与自定义规则（与设置页共享同一单例） */
    private val settings = DownloadSettings.get(context)

    /** v2.7 Stage3.1：获取用户自定义拦截规则（规范化） */
    private fun customAdRules(): List<String> =
        com.swift.download.browser.AdBlocker.normalizeRules(settings.adBlockRules)

    init {
        setBackgroundColor(Ui.BG)
        CookieManager.getInstance().setAcceptCookie(true)

        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // 顶部进度条
        progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            visibility = GONE
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.BLUE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.BORDER)
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 3f)))

        // 标签条
        tabsStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(context, 8f), Ui.dp(context, 4f), Ui.dp(context, 8f), Ui.dp(context, 4f))
        }
        tabsScroller = Ui.horizontalScroll(context, tabsStrip)
        root.addView(tabsScroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 地址栏容器（v2.8：整块可折叠，网页态默认收起为紧凑标题）
        addressBar = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        etUrl = Ui.singleLineEt(context, "输入地址或搜索内容").apply {
            setPadding(Ui.dp(context, 12f), Ui.dp(context, 8f), Ui.dp(context, 12f), Ui.dp(context, 8f))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { go(); true } else false
            }
            // v2.7 Stage3.2：聚焦自动全选，方便一键复制或覆盖输入
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) post { selectAll() } }
        }
        val barCard = Ui.card(context, 18f)
        val urlRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        urlRow.addView(etUrl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // v2.7 Stage3.2：地址栏清空 ✕
        val clearUrl = TextView(context).apply {
            text = "✕"; textSize = 15f; setTextColor(Ui.TXT_SUB)
            setPadding(Ui.dp(context, 6f), Ui.dp(context, 4f), Ui.dp(context, 4f), Ui.dp(context, 4f))
            visibility = GONE
            setOnClickListener { etUrl.setText(""); etUrl.requestFocus() }
        }
        urlRow.addView(clearUrl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clearUrl.visibility = if (s.isNullOrEmpty()) GONE else VISIBLE
            }
        })
        barCard.addView(urlRow)
        bar.addView(barCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for ((label, act) in listOf<Pair<String, () -> Unit>>(
            "←" to { current?.webView?.goBack() },
            "→" to { current?.webView?.goForward() },
            "⟳" to { current?.webView?.reload() },
            "⌂" to { goHome() },
            "☆" to { bookmarkCurrentPage() },
            "嗅探" to { showSniffSheet() },
            "⋮" to { showBrowserMenu() }
        )) {
            val b = TextView(context).apply {
                text = label; textSize = if (label == "嗅探") 12f else 18f
                gravity = Gravity.CENTER
                setTextColor(if (label == "嗅探") Ui.BLUE else Ui.TXT)
                setPadding(Ui.dp(context, 6f), Ui.dp(context, 6f), Ui.dp(context, 6f), Ui.dp(context, 6f))
                setOnClickListener { act() }
            }
            if (label == "嗅探") sniffBadge = b
            bar.addView(b)
        }
        addressBar.addView(bar)
        root.addView(addressBar)

        // v2.8 模块二：网页态紧凑标题栏（点击展开地址输入）
        compactTitle = TextView(context).apply {
            text = ""; textSize = 14f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(Ui.TXT)
            setPadding(Ui.dp(context, 16f), Ui.dp(context, 12f), Ui.dp(context, 16f), Ui.dp(context, 12f))
            visibility = GONE
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.CARD); cornerRadius = Ui.dp(context, 16f).toFloat()
            }
            setOnClickListener { expandAddressBar() }
        }
        root.addView(compactTitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(Ui.dp(context, 12f), Ui.dp(context, 6f), Ui.dp(context, 12f), 0) })

        // 内容区
        contentHost = FrameLayout(context)
        homepage = buildHomepage()
        contentHost.addView(homepage, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(contentHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // v2.8 模块二：浏览器专用底部工具栏（单手拇指热区）
        bottomToolbar = buildBottomToolbar()
        root.addView(bottomToolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        bottomToolbar.visibility = GONE

        addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applyWebViewProxy()
        newTab()
    }

    /**
     * 让 Chromium WebView 走 App 代理。统一委托进程级单一入口 [WebViewProxyManager]：
     * 主线程执行 + main executor 回调 + 特性检测 + 仅配置变化时触发。
     * 保留方法名是兼容 MainActivity 设置页关闭时的调用。
     */
    fun applyWebViewProxy() {
        WebViewProxyManager.apply(context)
    }

    /** v2.8 模块二：底部工具栏（后退/前进/刷新/添加书签/首页或标签） */
    private fun buildBottomToolbar(): LinearLayout {
        val ctx = context
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.CARD)
            setPadding(Ui.dp(ctx, 8f), Ui.dp(ctx, 8f), Ui.dp(ctx, 8f), Ui.dp(ctx, 8f))
        }
        /** 单色符号统一 tint，彩色 emoji「🎬」改为文字「抓」；每个键带无障碍描述 */
        fun tool(glyph: String, desc: String, act: () -> Unit): TextView = TextView(ctx).apply {
            text = glyph; textSize = 20f; gravity = Gravity.CENTER
            contentDescription = desc
            setTextColor(Ui.BLUE)
            setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 10f), Ui.dp(ctx, 12f), Ui.dp(ctx, 10f))
            Ui.pressable(this)
            setOnClickListener { act() }
        }
        btnBack = tool("←", "后退") { current?.webView?.goBack() }
        btnForward = tool("→", "前进") { current?.webView?.goForward() }
        bar.addView(btnBack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btnForward, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(tool("↻", "刷新") { current?.webView?.reload() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(tool("☆", "添加书签") { bookmarkCurrentPage() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 第5键：单击回首页，长按弹出多标签管理
        val homeTab = tool("⌂", "首页") { goHome() }
        homeTab.setOnLongClickListener { showTabManager(); true }
        bar.addView(homeTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // v2.8 模块四 4.1：一键抓本页磁力/视频资源
        btnGrab = tool("抓", "抓资源") { grabPageResources() }
        bar.addView(btnGrab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return bar
    }

    /** 网页态点击紧凑标题：展开完整地址栏便于输入 */
    private fun expandAddressBar() {
        addressExpanded = true
        compactTitle.visibility = GONE
        addressBar.visibility = VISIBLE
        etUrl.setText(current?.url?.takeIf { it != HOME_URL }.orEmpty())
        etUrl.requestFocus()
        etUrl.selectAll()
    }

    /** 收起地址栏回到紧凑标题（go / 失焦时调用） */
    private fun collapseAddressBar() {
        if (!addressExpanded) return
        addressExpanded = false
        etUrl.clearFocus()
        addressBar.visibility = GONE
        compactTitle.visibility = if (isWebMode()) VISIBLE else GONE
    }

    /** 是否处于网页浏览态 */
    private fun isWebMode(): Boolean {
        val u = current?.url
        return u != null && u != HOME_URL && u.isNotBlank()
    }

    /**
     * v2.8 模块二：根据首页/网页态统一切换 chrome（地址栏/紧凑标题/标签条/底部工具栏）。
     * 首页：完整地址栏 + 标签条 + 隐藏底部工具栏；
     * 网页态：标签条收起、地址栏默认收起为紧凑标题、显示底部工具栏。
     */
    private fun applyChromeMode() {
        val web = isWebMode()
        homepage.visibility = if (web) GONE else VISIBLE
        bottomToolbar.visibility = if (web) VISIBLE else GONE
        tabsStrip.visibility = if (web) GONE else VISIBLE
        (tabsStrip.parent as? View)?.visibility = if (web) GONE else VISIBLE
        if (web) {
            if (addressExpanded) {
                addressBar.visibility = VISIBLE
                compactTitle.visibility = GONE
            } else {
                addressBar.visibility = GONE
                compactTitle.visibility = VISIBLE
                compactTitle.text = current?.title?.takeIf { it.isNotBlank() }
                    ?: current?.url?.let { it.removePrefix("https://").removePrefix("http://").substringBefore('/') }
                    ?: "网页"
            }
            refreshNavButtons()
        } else {
            addressExpanded = false
            addressBar.visibility = VISIBLE
            compactTitle.visibility = GONE
        }
    }

    /** 底部工具栏前进/后退按钮置灰 */
    private fun refreshNavButtons() {
        val wv = current?.webView
        fun dim(tv: TextView, enabled: Boolean) {
            tv.setTextColor(if (enabled) Ui.BLUE else Ui.TEXT_DISABLED)
            tv.isEnabled = enabled
        }
        dim(btnBack, wv?.canGoBack() == true)
        dim(btnForward, wv?.canGoForward() == true)
    }

    /** v2.8 模块二：多标签管理底部面板（切换/新建/关闭） */
    private fun showTabManager() {
        val ctx = context
        val names = tabs.map { it.title.ifEmpty { "新标签页" } }.toTypedArray()
        android.app.AlertDialog.Builder(ctx)
            .setTitle("标签（${tabs.size}）· 长按项关闭")
            .setItems(names) { _, which -> switchTo(tabs[which]) }
            .setPositiveButton("新建标签") { _, _ -> newTab() }
            .setNeutralButton("关闭当前") { _, _ -> closeTab(current ?: return@setNeutralButton) }
            .setNegativeButton("取消", null)
            .show()
    }

    /* ---------------- v2.8 模块四 4.1：一键抓本页资源 ---------------- */

    /**
     * 抓资源：注入 JS 取渲染后的 DOM（含 <a href>/<input value>/可见文本），
     * 用纯逻辑 VideoSniff 提取 magnet/.torrent/ed2k/40 位 hash，列表勾选批量下载。
     */
    private fun grabPageResources() {
        val wv = current?.webView
        if (wv == null || isWebMode().not()) {
            Ui.toast(context, "请先打开一个网页")
            return
        }
        val js = "(function(){var a=[];" +
            "document.querySelectorAll('a[href]').forEach(function(e){a.push(e.href)});" +
            "document.querySelectorAll('input[type=text],textarea').forEach(function(e){if(e.value)a.push(e.value)});" +
            "a.push(document.body?document.body.innerText:'');" +
            "return JSON.stringify(a.join('\\n'));})()"
        try {
            wv.evaluateJavascript(js) { raw ->
                val text = try {
                    org.json.JSONObject("{\"v\":$raw}").getString("v")
                } catch (e: Exception) { raw ?: "" }
                val resources = com.swift.download.player.VideoSniff.extractPageResources(text)
                post {
                    if (resources.isEmpty()) {
                        btnGrab.text = "🎬"; Ui.toast(context, "本页未发现可抓取的磁力/种子资源")
                    } else {
                        btnGrab.text = "🎬${resources.size}"
                        showResourcePicker(resources)
                    }
                }
            }
        } catch (e: Exception) {
            Ui.toast(context, "抓取失败：${e.message ?: "未知错误"}")
        }
    }

    /** 资源选择弹窗：多选 + 单项点击下载 */
    private fun showResourcePicker(resources: List<com.swift.download.player.VideoSniff.PageResource>) {
        val ctx = context
        val labels = resources.map { r ->
            val tag = when (r.kind) {
                com.swift.download.player.VideoSniff.PageResource.Kind.MAGNET -> "磁力"
                com.swift.download.player.VideoSniff.PageResource.Kind.TORRENT -> "种子"
                com.swift.download.player.VideoSniff.PageResource.Kind.ED2K -> "电驴"
            }
            "[$tag] ${r.title}"
        }.toTypedArray()
        val checked = BooleanArray(resources.size) { true }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("发现 ${resources.size} 个资源（勾选后批量下载）")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("下载选中") { _, _ ->
                val selected = resources.filterIndexed { i, _ -> checked[i] }
                if (selected.isEmpty()) { Ui.toast(ctx, "未选择任何资源"); return@setPositiveButton }
                for (r in selected) startPageResource(r)
                Ui.toast(ctx, "已提交 ${selected.size} 个任务")
            }
            .setNeutralButton("全部复制") { _, _ ->
                val text = resources.joinToString("\n") { it.uri }
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("resources", text))
                Ui.toast(ctx, "已复制 ${resources.size} 条链接")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 单条资源下载分发 */
    private fun startPageResource(r: com.swift.download.player.VideoSniff.PageResource) {
        when (r.kind) {
            com.swift.download.player.VideoSniff.PageResource.Kind.MAGNET -> onNewMagnet(r.uri)
            com.swift.download.player.VideoSniff.PageResource.Kind.TORRENT ->
                onTorrentLink(r.uri, current?.url.orEmpty(), cookieFor(r.uri))
            com.swift.download.player.VideoSniff.PageResource.Kind.ED2K ->
                Ui.toast(context, "电驴链接暂不支持直接下载，请用「全部复制」")
        }
    }

    /* ---------------- 标签管理 ---------------- */

    /** 宿主（返回键）需要：当前标签的 WebView */
    fun currentWebView(): WebView? = current?.webView

    /** 宿主（返回键）需要：是否处于网页态；复用内部判定，避免两处逻辑漂移 */
    fun isInWebMode(): Boolean = isWebMode()

    /* v2.6.0：宿主生命周期转发，防止退出后网页后台音视频仍在播放 */
    fun onHostPause() { tabs.mapNotNull { it.webView }.forEach { it.onPause() } }
    fun onHostResume() { tabs.mapNotNull { it.webView }.forEach { it.onResume() } }
    fun onHostDestroy() { tabs.mapNotNull { it.webView }.forEach { it.destroy() } }

    private fun refreshSniffBadge() {
        val b = sniffBadge ?: return
        val n = current?.sniffed?.size ?: 0
        b.text = "嗅探($n)"
        b.visibility = if (n > 0) VISIBLE else GONE
    }

    private fun showSniffSheet() {
        val ctx = context
        val links = (current?.sniffed?.toList()) ?: emptyList()
        if (links.isEmpty()) {
            Ui.toast(ctx, "当前页面未嗅探到视频")
            return
        }
        val items = links.map { (url, name) -> "$name  ·  ${url.substringBefore('?').substringAfterLast('/')}" }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("嗅探结果（${links.size}）")
            .setItems(items.toTypedArray()) { _, which ->
                val (url, name) = links[which]
                showVideoSheet(url, name, current?.url.orEmpty(), cookieFor(url))
            }
            .setNeutralButton("全部下载") { _, _ ->
                val page = current?.url.orEmpty()
                for ((url, name) in links) {
                    if (VideoSniff.isMp4(url)) confirmDownload(url, name)
                    else onVideoDetected(url, name, page, cookieFor(url))
                }
                Ui.toast(ctx, "已添加 ${links.size} 个视频任务")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    fun newTab(url: String? = null): Tab {
        val t = Tab(nextId++, "新标签页", url ?: HOME_URL)
        tabs.add(t)
        switchTo(t)
        return t
    }

    fun closeTab(t: Tab) {
        tabs.remove(t)
        t.webView?.let { wv ->
            wv.stopLoading()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        t.webView = null
        if (current == t) {
            current = null
            val next = tabs.lastOrNull()
            if (next != null) switchTo(next) else newTab()
        }
        renderTabs()
    }

    fun switchTo(t: Tab) {
        current = t
        for (other in tabs) {
            other.webView?.visibility = if (other === t) VISIBLE else GONE
        }
        refreshSniffBadge()
        renderTabs()
        if (t.url == HOME_URL || t.url.isBlank()) {
            etUrl.setText("")
            etUrl.hint = "输入地址或搜索内容"
        } else {
            etUrl.setText(t.url)
            ensureWebView(t)
        }
        applyChromeMode()
    }

    private fun renderTabs() {
        tabsStrip.removeAllViews()
        for (t in tabs) {
            val isSel = t === current
            // v2.7 Stage3.2：胶囊 = 标题 + 明显的 × 关闭按钮（× 独立热区，标题区仍可长按关闭）
            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(context, 10f), Ui.dp(context, 5f), Ui.dp(context, 2f), Ui.dp(context, 5f))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (isSel) Ui.BLUE else 0xFFEDF0F6.toInt())
                    cornerRadius = Ui.dp(context, 14f).toFloat()
                }
                setOnClickListener { switchTo(t) }
                setOnLongClickListener { closeTab(t); true }
            }
            val title = TextView(context).apply {
                text = t.title.ifEmpty { "新标签页" }
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(if (isSel) 0xFFFFFFFF.toInt() else Ui.TXT)
                typeface = if (isSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            chip.addView(title, LinearLayout.LayoutParams(Ui.dp(context, 100f), ViewGroup.LayoutParams.WRAP_CONTENT))
            val close = TextView(context).apply {
                text = "×"; textSize = 17f; gravity = Gravity.CENTER
                setTextColor(if (isSel) 0xFFFFFFFF.toInt() else Ui.TXT_SUB)
                // 扩大点击热区：24dp 见方，视觉仍是一个字符
                setPadding(Ui.dp(context, 6f), Ui.dp(context, 6f), Ui.dp(context, 6f), Ui.dp(context, 6f))
                setOnClickListener { closeTab(t) }
            }
            chip.addView(close, LinearLayout.LayoutParams(Ui.dp(context, 30f), Ui.dp(context, 30f)))
            tabsStrip.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = Ui.dp(context, 6f)
            })
        }
        val plus = TextView(context).apply {
            text = "＋"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Ui.BLUE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFEDF0F6.toInt()); cornerRadius = Ui.dp(context, 14f).toFloat()
            }
            setOnClickListener { newTab() }
        }
        tabsStrip.addView(plus, LinearLayout.LayoutParams(Ui.dp(context, 28f), Ui.dp(context, 28f)))
        // v2.7 Stage3.2：新增/切换标签后平滑滚动到当前激活胶囊
        tabsScroller.post {
            val selIndex = tabs.indexOfFirst { it === current }
            if (selIndex >= 0) {
                val child = tabsStrip.getChildAt(selIndex)
                if (child != null) {
                    val targetX = (child.left + child.width / 2 - tabsScroller.width / 2)
                        .coerceAtLeast(0)
                    tabsScroller.smoothScrollTo(targetX, 0)
                }
            }
        }
    }

    /* ---------------- WebView ---------------- */

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(t: Tab): WebView {
        t.webView?.let { return it }
        val wv = WebView(context)
        configure(wv)
        wv.webViewClient = client(t)
        wv.webChromeClient = chrome(t)
        wv.setDownloadListener(dl())
        t.webView = wv
        contentHost.addView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wv.visibility = if (t === current) VISIBLE else GONE
        if (t.url != HOME_URL && t.url.isNotBlank()) wv.loadUrl(t.url)
        return wv
    }

    private fun configure(wv: WebView) {
        val s: WebSettings = wv.settings
        s.javaScriptEnabled = true            // 图片站/普通站都依赖
        // v2.8 模块三：完整现代 WebView 能力（部分影视站依赖 Database/多窗口）
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.domStorageEnabled = true
        s.loadsImagesAutomatically = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.userAgentString = if (desktopMode) DESKTOP_UA else HttpUtil.USER_AGENT
        wv.setBackgroundColor(0xFFFFFFFF.toInt())
        // v2.7 Stage3.2：长按图片/链接弹菜单；普通文本交回 WebView 默认选择行为
        wv.setOnLongClickListener { v ->
            val hit = (v as WebView).hitTestResult
            if (hit.type in setOf(
                    WebView.HitTestResult.IMAGE_TYPE,
                    WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                    WebView.HitTestResult.SRC_ANCHOR_TYPE
                ) && !hit.extra.isNullOrBlank()
            ) {
                showHitTestMenu(hit)
                true
            } else false
        }
    }

    private fun client(t: Tab) = object : WebViewClient() {
        /** 页面加载的任意子资源都经过这里；命中 m3u8/mp4 即提示用户，不拦截不影响播放 */
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): android.webkit.WebResourceResponse? {
            val url = request.url.toString()
            // v2.7 Stage3.1：命中广告/统计/劫持规则 → 返回空响应，直接掐断
            if (settings.adBlockEnabled && AdBlocker.isBlocked(url, customAdRules())) {
                return android.webkit.WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            if (VideoSniff.isCandidate(url)) {
                val page = t.url.ifBlank { url }
                // v2.6.0：无论是否弹窗都记录到本标签，关闭弹窗后仍可从角标重新查看
                val isNew = !t.sniffed.containsKey(url)
                t.sniffed[url] = VideoSniff.displayName(url)
                if (t === current) post { refreshSniffBadge() }
                if (isNew && sniffKey != page) {
                    sniffKey = page
                    post {
                        if (t === current) showVideoSheet(url, VideoSniff.displayName(url), page, cookieFor(url))
                    }
                }
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            // v2.7 Stage3.1：仅放行白名单协议；外部未知 Scheme（intent/market/weixin/alipay…）
            // 一律拦截并轻提示，防止影视站自动弹走打开第三方 App。
            return when {
                url.startsWith("magnet:", true) -> {
                    confirmMagnet(url); true
                }
                url.startsWith("http://", true) || url.startsWith("https://", true) -> {
                    // 导航到广告域名也可拦截（站点跳转广告）
                    if (settings.adBlockEnabled && AdBlocker.isBlocked(url, customAdRules())) {
                        Ui.toast(context, "已拦截广告跳转")
                        true
                    } else false
                }
                url.startsWith("about:", true) -> false
                else -> {
                    Ui.toast(context, "已拦截外部跳转")
                    true
                }
            }
        }
        override fun onPageFinished(view: WebView, url: String) {
            t.url = url
            t.title = view.title ?: url
            if (t === current) {
                etUrl.setText(if (url == HOME_URL) "" else url)
                renderTabs()
                applyChromeMode()
            }
            // v2.8 模块三：广告 DOM 注入（开关开启时，页面加载完成后注入一次）
            if (settings.adBlockEnabled && !url.startsWith("about:")) {
                view.evaluateJavascript(AdBlocker.domInjectionJs(settings.adBlockStrong), null)
            }
        }

        /** v2.8 模块三：证书错误不再静默白屏，询问用户并记住本次会话选择 */
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            if (sslAcceptedHosts.contains(t.url.removePrefix("https://").substringBefore('/'))) {
                handler.proceed(); return
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("证书安全提示")
                .setMessage("该站点证书存在问题（${sslErrorLabel(error)}），继续访问可能被监听。是否仍要继续？")
                .setPositiveButton("继续访问") { _, _ ->
                    sslAcceptedHosts.add(t.url.removePrefix("https://").substringBefore('/'))
                    handler.proceed()
                }
                .setNegativeButton("停止加载") { _, _ -> handler.cancel() }
                .show()
        }

        /** v2.8 模块三：主框架加载失败时展示内置错误页与快捷引导 */
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (!request.isForMainFrame) return
            val reason = when (error.errorCode) {
                -2 -> "DNS 解析失败"
                -3 -> "连接超时"
                -6 -> "连接被重置"
                -1 -> "请求被拦截或超时"
                else -> "加载失败（错误码 ${error.errorCode}）"
            }
            view.loadUrl(errorPage(reason, request.url.toString()))
        }
    }

    private fun sslErrorLabel(e: SslError): String = when (e.primaryError) {
        SslError.SSL_EXPIRED -> "已过期"
        SslError.SSL_IDMISMATCH -> "域名不匹配"
        SslError.SSL_NOTYETVALID -> "尚未生效"
        SslError.SSL_UNTRUSTED -> "不受信任"
        else -> "未知问题"
    }

    /** 内置错误引导页（data URI，含刷新/检查代理/改书签三个入口） */
    private fun errorPage(reason: String, failedUrl: String): String {
        val title = "网页加载失败"
        val html = """
            <!doctype html><meta charset=utf-8>
            <body style="font-family:system-ui;padding:32px;color:#1f2937;background:#f6f7fb">
            <h2 style="color:#dc2626">$title</h2>
            <p>$reason</p>
            <p style="color:#6b7280;font-size:12px;word-break:break-all">$failedUrl</p>
            <p style="margin-top:24px">
            <button onclick="history.go(0)" style="padding:10px 18px;margin-right:8px">刷新重试</button>
            <button onclick="alert('请到 我的 → 下载设置 → HTTP 代理 检查开关与地址')" style="padding:10px 18px">检查代理</button>
            </p></body>"""
        val encoded = android.util.Base64.encodeToString(html.toByteArray(), android.util.Base64.NO_WRAP)
        return "data:text/html;base64,$encoded"
    }

    /** 显式返回类型，避免 onCreateWindow 内部递归调用 chrome() 时编译器类型推断死循环 */
    private fun chrome(t: Tab): WebChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.visibility = if (newProgress in 1..99) VISIBLE else GONE
            progress.progress = newProgress
        }
        override fun onReceivedTitle(view: WebView, title: String?) {
            t.title = title ?: t.url
            renderTabs()
        }

        /**
         * v2.7 Stage3.1：拦截 window.open 弹窗。
         * 非用户意图（脚本自动弹广告）直接拒绝；用户主动点击的弹窗挂到新标签，
         * 避免站点用弹窗跳出同步广告页。
         */
        override fun onCreateWindow(
            view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
        ): Boolean {
            // v2.8 模块三：用户手势的 target=_blank 必须放行（电影详情/磁力按钮常用），
            // 绝不能当广告吞掉；只拦脚本自动弹窗。
            if (!isUserGesture) {
                Ui.toast(context, "已拦截弹出广告窗口")
                return false
            }
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            val nt = newTab()
            // 给 transport WebView 附加一个纯监听 client：捕获目标 URL 回填到标签，
            // 解决部分站点 target=_blank 打开后标题/地址不同步、后续导航失效的问题。
            val wv = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = if (desktopMode) DESKTOP_UA else HttpUtil.USER_AGENT
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, url: String) {
                        nt.url = url; nt.title = v.title ?: url
                        if (nt === current) applyChromeMode()
                        renderTabs()
                    }
                }
                webChromeClient = chrome(nt)
                setDownloadListener(dl())
            }
            nt.webView = wv
            contentHost.addView(wv, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            wv.visibility = if (nt === current) VISIBLE else GONE
            transport.webView = wv
            resultMsg.sendToTarget()
            return true
        }
    }

    /** 页面内下载请求 → 多线程引擎（弹确认） */
    private fun dl() = DownloadListener { url, _, contentDisposition, mimeType, _ ->
        val name = com.swift.download.Format.fileNameFromDisposition(contentDisposition)
            ?: com.swift.download.nameFromUrl(url)
        // v2.8 模块一：.torrent 种子直接建 BT 任务，不再当普通文件存 Download 完事
        if (isTorrentLink(url, name, mimeType)) {
            android.app.AlertDialog.Builder(context)
                .setTitle("种子文件")
                .setMessage("$name\n\n将拉取种子并开始 BT 下载。")
                .setPositiveButton("下载") { _, _ -> onTorrentLink(url, current?.url.orEmpty(), cookieFor(url)) }
                .setNegativeButton("取消", null)
                .show()
            return@DownloadListener
        }
        confirmDownload(url, name)
    }

    /** v2.8 模块一：判定 .torrent 链接（路径后缀 / Content-Disposition / mimeType） */
    private fun isTorrentLink(url: String, name: String, mimeType: String?): Boolean =
        url.substringBefore('?').endsWith(".torrent", ignoreCase = true) ||
        name.endsWith(".torrent", ignoreCase = true) ||
        (mimeType?.contains("x-bittorrent", ignoreCase = true) == true)

    /**
     * v2.6.0：从 WebView 的 CookieManager 抓取目标地址与来源页的登录态。
     * 两处的同名 Cookie 以目标地址优先，避免来源页旧值覆盖。
     */
    private fun cookieFor(url: String): String {
        val manager = try { CookieManager.getInstance() } catch (e: Exception) { return "" }
        val page = current?.url.orEmpty()
        val out = LinkedHashMap<String, String>()
        for (host in listOf(url, page).filter { it.isNotBlank() }) {
            val raw = try { manager.getCookie(host).orEmpty() } catch (e: Exception) { "" }
            for (item in raw.split(';')) {
                val pair = item.trim()
                val i = pair.indexOf('=')
                if (i > 0) out.putIfAbsent(pair.substring(0, i).trim(), pair.substring(i + 1).trim())
            }
        }
        return out.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun confirmDownload(url: String, name: String) {
        val referer = current?.url.orEmpty()
        val cookie = cookieFor(url)
        android.app.AlertDialog.Builder(context)
            .setTitle("下载文件")
            .setMessage("$name\n\n已接管为多线程下载，支持断点续传。")
            .setPositiveButton("下载") { _, _ -> onNewDownload(url, name, referer, cookie) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmMagnet(url: String) {
        android.app.AlertDialog.Builder(context)
            .setTitle("磁力链接")
            .setMessage("$url\n\n将开始 BT 下载（磁力引擎）。冷门资源可能需 1~3 分钟出现文件名。")
            .setPositiveButton("下载") { _, _ -> onNewMagnet(url) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 视频嗅探结果处理：
     *  - mp4 直链 → 直接询问是否下载
     *  - m3u8 → 后台抓一次 playlist，列出各档清晰度，用户选定后把变体地址交给下载服务
     * 抓 playlist 失败（签名/网络）时退化为"按原始地址下载"，交由引擎自动选档。
     */
    private fun showVideoSheet(url: String, name: String, referer: String, cookie: String = "") {
        if (VideoSniff.isMp4(url)) {
            confirmDownload(url, name)
            return
        }
        val ctx = context
        val status = Ui.text(ctx, "正在解析清晰度…", 13f, Ui.TXT_SUB)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 18f), Ui.dp(ctx, 10f), Ui.dp(ctx, 18f), 0)
            addView(status)
            addView(list, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 8f) })
        }
        val dialog = android.app.AlertDialog.Builder(ctx)
            .setTitle("发现视频 · $name")
            .setView(wrap)
            .setNegativeButton("忽略", null)
            .create()
        dialog.show()

        Thread {
            val variants = try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.setRequestProperty("Referer", referer)
                conn.setRequestProperty("User-Agent", HttpUtil.USER_AGENT)
                if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
                val text = try {
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    conn.disconnect()
                }
                com.swift.download.player.M3u8.parse(url, text).variants
            } catch (e: Exception) {
                emptyList()
            }
            post {
                if (!dialog.isShowing) return@post
                list.removeAllViews()
                if (variants.isEmpty()) {
                    status.text = "未能解析出清晰度列表，将按原始地址下载（引擎自动选最高档）。"
                    list.addView(Ui.pillBtn(ctx, "下载视频", Ui.BLUE, filled = true) {
                        dialog.dismiss()
                        onVideoDetected(url, name, referer, cookieFor(url))
                    })
                } else {
                    status.text = "选择清晰度（${variants.size} 档）："
                    for (v in variants) {
                        val row = Ui.card(ctx, 12f)
                        val line = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        line.addView(Ui.text(ctx, v.label, 14f, Ui.TXT, bold = true),
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        line.addView(Ui.text(ctx, "下载 ›", 13f, Ui.BLUE))
                        row.addView(line)
                        row.setOnClickListener {
                            dialog.dismiss()
                            onVideoDetected(v.url, name, referer, cookieFor(v.url))
                        }
                        list.addView(row, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = Ui.dp(ctx, 6f) })
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** v2.7 Stage3.2：地址栏 ⋮ 功能菜单（桌面版切换入口） */
    private fun showBrowserMenu() {
        val ctx = context
        val items = arrayOf(
            if (desktopMode) "切换为手机版网页" else "切换为桌面版网页",
            "复制当前网址"
        )
        android.app.AlertDialog.Builder(ctx)
            .setTitle("浏览器功能")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleDesktopMode()
                    1 -> {
                        val url = current?.url.orEmpty()
                        if (url.isNotBlank() && url != HOME_URL) copyText("网址", url)
                        else Ui.toast(ctx, "当前页没有可复制的网址")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** v2.7 Stage3.2：切换 UA 并刷新当前页（解决部分网盘/下载站仅桌面端显示直链） */
    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        val ua = if (desktopMode) DESKTOP_UA else HttpUtil.USER_AGENT
        // 当前已创建的 WebView 全部同步 UA；未创建的在 configure() 时按标志设置
        for (tab in tabs) tab.webView?.settings?.userAgentString = ua
        val wv = current?.webView
        if (wv != null) {
            wv.reload()
            Ui.toast(context, if (desktopMode) "已切换桌面版，正在刷新" else "已切换手机版，正在刷新")
        } else {
            Ui.toast(context, if (desktopMode) "已切换桌面版（打开网页后生效）" else "已切换手机版")
        }
    }

    private fun copyText(label: String, value: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
            Ui.toast(context, "已复制$label")
        } catch (e: Exception) {
            Ui.toast(context, "复制失败")
        }
    }

    /** v2.7 Stage3.2：WebView 长按菜单（图片保存/复制地址，链接新建下载/新标签/复制） */
    private fun showHitTestMenu(hit: WebView.HitTestResult) {
        val ctx = context
        val extra = hit.extra ?: return
        when (hit.type) {
            WebView.HitTestResult.IMAGE_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                android.app.AlertDialog.Builder(ctx)
                    .setTitle("图片")
                    .setItems(arrayOf("保存图片", "复制图片地址")) { _, which ->
                        when (which) {
                            0 -> confirmDownload(extra, VideoSniff.displayName(extra))
                            1 -> copyText("图片地址", extra)
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                android.app.AlertDialog.Builder(ctx)
                    .setTitle("链接")
                    .setItems(arrayOf("新建下载", "新标签打开", "复制链接")) { _, which ->
                        when (which) {
                            0 -> if (extra.startsWith("magnet:", true)) confirmMagnet(extra)
                                 else confirmDownload(extra, VideoSniff.displayName(extra))
                            1 -> newTab(extra)
                            2 -> copyText("链接", extra)
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            else -> { /* 普通文本交给 WebView 默认长按选择 */ }
        }
    }

    private fun go() {
        val input = etUrl.text.toString().trim()
        if (input.isEmpty()) return
        val t = current ?: newTab()
        when {
            input.startsWith("magnet:") -> confirmMagnet(input)
            input.startsWith("http://") || input.startsWith("https://") -> {
                showWeb(t, input)
            }
            input.contains('.') && !input.contains(' ') -> {
                showWeb(t, "https://$input")
            }
            else -> {
                showWeb(t, "https://www.bing.com/search?q=${java.net.URLEncoder.encode(input, "UTF-8")}")
            }
        }
        addressExpanded = false
        etUrl.clearFocus()
    }

    /** 打开网页：隐藏首页，并确保当前标签的 WebView 真的可见（可能是从首页返回的旧 WebView） */
    private fun showWeb(t: Tab, url: String) {
        val wv = ensureWebView(t)
        wv.visibility = VISIBLE
        wv.loadUrl(url)
        applyChromeMode()
    }

    private fun goHome() {
        val t = current ?: return
        // v2.8 交互修复：回首页必须连带清掉 WebView 历史，否则首页按系统返回键会跳回旧网页。
        // 先载 about:blank 再清历史，确保 about:blank 成为唯一条目且 canGoBack()==false。
        t.webView?.let { wv ->
            try {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.clearHistory()
            } catch (_: Exception) { /* WebView 已销毁时不阻断回首页 */ }
        }
        // v2.5.2：只把首页设为 VISIBLE 是不够的 —— 当前标签的 WebView 仍盖在上面，
        // 表现就是"点主页没反应，还是旧页面"
        for (tab in tabs) tab.webView?.visibility = GONE
        etUrl.setText("")
        t.url = HOME_URL
        t.title = "新标签页"
        renderTabs()
        applyChromeMode()
    }

    /** v2.1：地址栏☆ —— 把当前页收藏为书签（存在则改名） */
    private fun bookmarkCurrentPage() {
        val t = current ?: return
        val url = t.url
        if (url == HOME_URL || url.isBlank() || !url.startsWith("http")) {
            Ui.toast(context, "先打开一个网页再收藏")
            return
        }
        val existing = siteStore.find(url)
        val initial = QuickSites.Site(existing?.name ?: t.title.ifBlank { url }, url)
        showSiteEditDialog(context, initial, "收藏书签") { name, newUrl ->
            siteStore.upsert(existing?.url, name, newUrl)
            rebuildHomepage()
            Ui.toast(context, "已收藏 $name（浏览器首页可看到）")
        }
    }

    /* ---------------- 首页 ---------------- */

    /**
     * v2.8 模块二：首页极简重塑。
     * 地址栏本身就是顶部搜索胶囊（首页态常驻），首页内只保留"磁力搜"按钮 + 3 列书签网格，
     * 删除大段冗余提示文案与多行堆叠。
     */
    private fun buildHomepage(): View {
        val ctx = context
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // 磁力搜按钮（靠右，紧接顶部地址栏）
        val magBtn = TextView(ctx).apply {
            text = "🧲 磁力搜"; textSize = 13f; setTextColor(Ui.BLUE)
            gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 14f), Ui.dp(ctx, 8f), Ui.dp(ctx, 14f), Ui.dp(ctx, 8f))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.BLUE_LIGHT); cornerRadius = Ui.dp(ctx, 18f).toFloat()
            }
            Ui.pressable(this)
            setOnClickListener { onOpenMagnetSearch("") }
        }
        root.addView(magBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.END; setMargins(Ui.dp(ctx, 16f), Ui.dp(ctx, 12f), Ui.dp(ctx, 16f), Ui.dp(ctx, 4f)) })

        // 书签区：3 列纯白圆角方块网格（圆形首字母 + 站点名）
        val sitesGrid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sitesGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(ctx, 14f), Ui.dp(ctx, 6f), Ui.dp(ctx, 14f), Ui.dp(ctx, 20f))
        })
        renderSites(sitesGrid)

        return Ui.verticalScroll(ctx, root)
    }

    private fun renderSites(sitesGrid: LinearLayout) {
        val ctx = context
        sitesGrid.removeAllViews()
        val sites = siteStore.all()
        val rows = (sites.size + 1 + 2) / 3 // 站点 + 添加卡
        for (r in 0 until rows) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            sitesGrid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(ctx, 10f)
            })
            for (c in 0 until 3) {
                val i = r * 3 + c
                when {
                    i < sites.size -> {
                        val site = sites[i]
                        row.addView(siteCard(ctx, site), LinearLayout.LayoutParams(0, Ui.dp(ctx, 84f), 1f).apply {
                            marginEnd = Ui.dp(ctx, if (c < 2) 10f else 0f)
                        })
                    }
                    i == sites.size -> {
                        row.addView(addSiteCard(ctx), LinearLayout.LayoutParams(0, Ui.dp(ctx, 84f), 1f).apply {
                            marginEnd = Ui.dp(ctx, if (c < 2) 10f else 0f)
                        })
                    }
                }
            }
        }
    }

    /** 站点有增删后重建首页（卡片数少，代价可接受） */
    private fun rebuildHomepage() {
        (homepage.parent as? ViewGroup)?.removeView(homepage)
        val newHome = buildHomepage()
        contentHost.addView(newHome, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        homepage = newHome
        newHome.visibility = if (current?.url == HOME_URL || current?.url.isNullOrBlank()) VISIBLE else GONE
    }

    /** v2.1：外部（书签管理页）改了书签后刷新浏览器首页 */
    fun refreshBookmarks() {
        post { rebuildHomepage() }
    }

    private fun engineCard(ctx: Context, title: String, sub: String, onTap: () -> Unit): View {
        val card = Ui.card(ctx, 16f)
        card.addView(Ui.text(ctx, title, 16f, Ui.BLUE, bold = true))
        card.addView(Ui.text(ctx, sub, 12f, Ui.TXT_SUB), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(ctx, 4f)
        })
        card.setOnClickListener { onTap() }
        return card
    }

    private fun siteCard(ctx: Context, site: QuickSites.Site): View {
        // v2.8 模块二：纯白圆角方块 + 居中圆形首字母 + 名称
        val card = Ui.card(ctx, 16f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(ctx, 8f), Ui.dp(ctx, 14f), Ui.dp(ctx, 8f), Ui.dp(ctx, 12f))
        }
        Ui.pressable(card)
        val iv = TextView(ctx).apply {
            text = site.name.take(1)
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.colorOf(site.name)); cornerRadius = Ui.dp(ctx, 22f).toFloat() // 圆形
            }
        }
        card.addView(iv, LinearLayout.LayoutParams(Ui.dp(ctx, 44f), Ui.dp(ctx, 44f)))
        val nameTv = Ui.text(ctx, site.name, 12f, Ui.TXT, bold = true)
        nameTv.gravity = Gravity.CENTER
        nameTv.maxLines = 1
        nameTv.ellipsize = android.text.TextUtils.TruncateAt.END
        card.addView(nameTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(ctx, 8f)
        })
        card.setOnClickListener {
            val t = current ?: newTab()
            t.url = site.url; t.title = site.name
            showWeb(t, site.url)
        }
        card.setOnLongClickListener {
            android.app.AlertDialog.Builder(ctx)
                .setTitle(site.name)
                .setItems(arrayOf("编辑书签", "移到最前", "删除书签")) { _, which ->
                    when (which) {
                        0 -> showSiteEditDialog(ctx, site, "编辑书签") { n, u ->
                            siteStore.upsert(site.url, n, u)
                            rebuildHomepage()
                            Ui.toast(ctx, "已保存 $n")
                        }
                        1 -> {
                            siteStore.moveToTop(site.url)
                            rebuildHomepage()
                            Ui.toast(ctx, "已置顶 ${site.name}")
                        }
                        else -> android.app.AlertDialog.Builder(ctx)
                            .setMessage("删除书签「${site.name}」？")
                            .setPositiveButton("删除") { _, _ ->
                                siteStore.remove(site.url)
                                rebuildHomepage()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        return card
    }

    private fun addSiteCard(ctx: Context): View {
        val card = Ui.card(ctx, 14f)
        val iv = TextView(ctx).apply {
            text = "＋"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Ui.BLUE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.BLUE_LIGHT); cornerRadius = Ui.dp(ctx, 12f).toFloat()
            }
        }
        card.addView(iv, LinearLayout.LayoutParams(Ui.dp(ctx, 40f), Ui.dp(ctx, 40f)))
        card.addView(Ui.text(ctx, "添加网址", 13f, Ui.TXT, bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(ctx, 6f)
        })
        card.setOnClickListener { showSiteEditDialog(ctx, null, "添加书签") { n, u ->
            siteStore.add(n, u)
            rebuildHomepage()
            Ui.toast(ctx, "已添加 $n")
        } }
        return card
    }

    companion object {
        const val HOME_URL = "about:home"

        /** v2.7 Stage3.2：桌面版 UA（网盘/下载站仅桌面端显示直链时用） */
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

/* ---------------- 通用书签编辑对话框（v2.1，BrowserPage / BookmarkManagerPage 共用） ---------------- */

fun showSiteEditDialog(
    ctx: Context,
    editing: QuickSites.Site?,
    title: String,
    onSave: (name: String, url: String) -> Unit
) {
    val etName = Ui.singleLineEt(ctx, "名称，如 电影港").apply {
        setText(editing?.name ?: "")
    }
    val etAddr = Ui.singleLineEt(ctx, "https://…").apply {
        setText(editing?.url ?: "")
    }
    val wrap = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 8f), Ui.dp(ctx, 20f), 0)
        addView(etName, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(etAddr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8f) })
        addView(Ui.text(ctx, "地址可省略 https:// 前缀；名称就是卡片图标文字", 11f, 0xFF9CA3AF.toInt()),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 6f) })
    }
    android.app.AlertDialog.Builder(ctx)
        .setTitle(title)
        .setView(wrap)
        .setPositiveButton("保存") { _, _ ->
            val name = etName.text.toString().trim()
            var url = etAddr.text.toString().trim()
            if (name.isEmpty() || url.isEmpty()) { Ui.toast(ctx, "名称和网址都要填"); return@setPositiveButton }
            if (!url.startsWith("http")) url = "https://$url"
            onSave(name, url)
        }
        .setNegativeButton("取消", null)
        .show()
}

/* ---------------- 快捷站点持久化 ---------------- */

class QuickSites private constructor(private val ctx: Context) {

    data class Site(val name: String, val url: String)

    private val file: File get() = File(ctx.filesDir, "quick_sites.json")

    private val defaults = listOf(
        Site("必应", "https://www.bing.com"),
        Site("电影港", "https://www.dygang.cc"),
        // v2.8 模块二：更新失效域名（高清族换新域，移除长期打不开的 SHB）
        Site("高清族", "https://hdzu.cc"),
        Site("磁力搜", "https://www.bing.com/search?q=magnet")
    )

    fun all(): List<Site> {
        if (!file.exists()) return defaults
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Site(o.optString("name"), o.optString("url"))
            }
        } catch (e: Exception) {
            defaults
        }
    }

    fun add(name: String, url: String) {
        val list = all().toMutableList()
        list.add(Site(name, url))
        save(list)
    }

    /** v2.1：新增或更新书签（同名址存在则改，不存在则追加） */
    fun upsert(oldUrl: String?, name: String, url: String) {
        val list = all().toMutableList()
        val i = if (oldUrl == null) -1 else list.indexOfFirst { it.url == oldUrl }
        if (i >= 0) list[i] = Site(name, url) else list.add(Site(name, url))
        save(list)
    }

    /** v2.1：移到最前（长按书签卡片「置顶」用） */
    fun moveToTop(url: String) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.url == url }
        if (i > 0) {
            val s = list.removeAt(i)
            list.add(0, s)
            save(list)
        }
    }

    fun find(url: String): Site? = all().firstOrNull { it.url == url }

    /** v2.1：恢复默认书签（保留用户已加的会一并重置，需二次确认后调用） */
    fun resetDefaults() = save(defaults)

    fun remove(url: String) {
        save(all().filterNot { it.url == url })
    }

    private fun save(list: List<Site>) {
        try {
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            for (s in list) arr.put(JSONObject().put("name", s.name).put("url", s.url))
            file.writeText(arr.toString())
        } catch (e: Exception) {
            // 忽略
        }
    }

    companion object {
        @Volatile private var inst: QuickSites? = null
        fun get(ctx: Context): QuickSites =
            inst ?: synchronized(this) {
                inst ?: QuickSites(ctx.applicationContext).also { inst = it }
            }
    }
}
