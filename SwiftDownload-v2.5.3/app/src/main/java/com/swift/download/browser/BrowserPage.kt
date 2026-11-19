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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.swift.download.player.VideoSniff
import com.swift.download.ui.Ui
import org.json.JSONArray
import org.json.JSONObject
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
    private val onVideoDetected: (url: String, name: String, referer: String, cookie: String) -> Unit
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
    private val etUrl: EditText
    private val progress: ProgressBar
    private var homepage: View

    private val siteStore = QuickSites.get(context)

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
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 3f)))

        // 标签条
        tabsStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(context, 8f), Ui.dp(context, 4f), Ui.dp(context, 8f), Ui.dp(context, 4f))
        }
        root.addView(Ui.horizontalScroll(context, tabsStrip), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 地址栏
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        etUrl = Ui.singleLineEt(context, "搜索或输入网址").apply {
            setPadding(Ui.dp(context, 12f), Ui.dp(context, 8f), Ui.dp(context, 12f), Ui.dp(context, 8f))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { go(); true } else false
            }
        }
        val barCard = Ui.card(context, 18f)
        barCard.addView(etUrl)
        bar.addView(barCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for ((label, act) in listOf<Pair<String, () -> Unit>>(
            "←" to { current?.webView?.goBack() },
            "→" to { current?.webView?.goForward() },
            "⟳" to { current?.webView?.reload() },
            "⌂" to { goHome() },
            "☆" to { bookmarkCurrentPage() },
            "嗅探" to { showSniffSheet() }
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
        root.addView(bar)

        // 内容区
        contentHost = FrameLayout(context)
        homepage = buildHomepage()
        contentHost.addView(homepage, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(contentHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        newTab()
    }

    /* ---------------- 标签管理 ---------------- */

    /** 宿主（返回键）需要：当前标签的 WebView */
    fun currentWebView(): WebView? = current?.webView

    /* v2.6.0：宿主生命周期转发，防止退出后网页后台音视频仍在播放 */
    fun onHostPause() { tabs.mapNotNull { it.webView }.forEach { it.onPause() } }
    fun onHostResume() { tabs.mapNotNull { it.webView }.forEach { it.onResume() } }
    fun onHostDestroy() { tabs.mapNotNull { it.webView }.forEach { it.destroy() } }

    /** v2.6.0：嗅探结果角标（数量 = 当前标签去重后的视频链接数） */
    private var sniffBadge: TextView? = null

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
            homepage.visibility = VISIBLE
            etUrl.setText("")
            etUrl.hint = "搜索或输入网址"
        } else {
            homepage.visibility = GONE
            etUrl.setText(t.url)
            ensureWebView(t)
        }
    }

    private fun renderTabs() {
        tabsStrip.removeAllViews()
        for (t in tabs) {
            val isSel = t === current
            val chip = TextView(context).apply {
                text = t.title.ifEmpty { "新标签页" }
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(Ui.dp(context, 14f), Ui.dp(context, 6f), Ui.dp(context, 14f), Ui.dp(context, 6f))
                setTextColor(if (isSel) 0xFFFFFFFF.toInt() else Ui.TXT)
                typeface = if (isSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (isSel) Ui.BLUE else 0xFFEDF0F6.toInt())
                    cornerRadius = Ui.dp(context, 14f).toFloat()
                }
                setOnClickListener { switchTo(t) }
                setOnLongClickListener { closeTab(t); true }
            }
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
        s.domStorageEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.userAgentString = HttpUtil.USER_AGENT
        wv.setBackgroundColor(0xFFFFFFFF.toInt())
    }

    /** 视频嗅探：同一页面只提示一次，避免分片请求反复弹窗 */
    private var sniffKey: String? = null

    private fun client(t: Tab) = object : WebViewClient() {
        /** 页面加载的任意子资源都经过这里；命中 m3u8/mp4 即提示用户，不拦截不影响播放 */
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): android.webkit.WebResourceResponse? {
            val url = request.url.toString()
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
            return when {
                url.startsWith("magnet:") -> {
                    confirmMagnet(url); true
                }
                url.startsWith("http://") || url.startsWith("https://") -> false
                url.startsWith("about:") -> true
                else -> true
            }
        }
        override fun onPageFinished(view: WebView, url: String) {
            t.url = url
            t.title = view.title ?: url
            if (t === current) {
                homepage.visibility = if (url == HOME_URL) VISIBLE else GONE
                etUrl.setText(if (url == HOME_URL) "" else url)
                renderTabs()
            }
        }
    }

    private fun chrome(t: Tab) = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.visibility = if (newProgress in 1..99) VISIBLE else GONE
            progress.progress = newProgress
        }
        override fun onReceivedTitle(view: WebView, title: String?) {
            t.title = title ?: t.url
            renderTabs()
        }
    }

    /** 页面内下载请求 → 多线程引擎（弹确认） */
    private fun dl() = DownloadListener { url, _, contentDisposition, _, _ ->
        val name = com.swift.download.Format.fileNameFromDisposition(contentDisposition)
            ?: com.swift.download.nameFromUrl(url)
        confirmDownload(url, name)
    }

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
    }

    /** 打开网页：隐藏首页，并确保当前标签的 WebView 真的可见（可能是从首页返回的旧 WebView） */
    private fun showWeb(t: Tab, url: String) {
        homepage.visibility = GONE
        val wv = ensureWebView(t)
        wv.visibility = VISIBLE
        wv.loadUrl(url)
    }

    private fun goHome() {
        val t = current ?: return
        homepage.visibility = VISIBLE
        // v2.5.2：只把首页设为 VISIBLE 是不够的 —— 当前标签的 WebView 仍盖在上面，
        // 表现就是"点主页没反应，还是旧页面"
        for (tab in tabs) tab.webView?.visibility = GONE
        etUrl.setText("")
        t.url = HOME_URL
        t.title = "新标签页"
        renderTabs()
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

    private fun buildHomepage(): View {
        val ctx = context
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // 搜索卡
        val searchCard = Ui.card(ctx, 26f)
        val searchRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val etHome = Ui.singleLineEt(ctx, "搜索网页，或输入磁力关键词后选磁力引擎")
        searchRow.addView(etHome, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val goBtn = TextView(ctx).apply {
            text = "搜索"; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.BLUE); cornerRadius = Ui.dp(ctx, 20f).toFloat()
            }
            setOnClickListener {
                val kw = etHome.text.toString().trim()
                if (kw.isEmpty()) return@setOnClickListener
                etUrl.setText(kw)
                go()
            }
        }
        searchRow.addView(goBtn, LinearLayout.LayoutParams(Ui.dp(ctx, 80f), Ui.dp(ctx, 40f)).apply { marginStart = Ui.dp(ctx, 8f) })
        searchCard.addView(searchRow)
        searchCard.addView(
            Ui.text(ctx, "支持 http/https 直链、magnet 磁力链接与普通关键词", 11f, Ui.TXT_SUB),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 6f) }
        )
        root.addView(searchCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(ctx, 14f), Ui.dp(ctx, 16f), Ui.dp(ctx, 14f), Ui.dp(ctx, 10f))
        })

        // 引擎入口
        val engines = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val webEngine = engineCard(ctx, "网页引擎", "必应搜索") {
            val kw = etHome.text.toString().trim()
            if (kw.isEmpty()) { Ui.toast(ctx, "先输入关键词") } else { etUrl.setText(kw); go() }
        }
        val magnetEngine = engineCard(ctx, "磁力引擎", "聚合 5 大磁力站") {
            onOpenMagnetSearch(etHome.text.toString().trim())
        }
        engines.addView(webEngine, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(ctx, 10f) })
        engines.addView(magnetEngine, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(engines, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(ctx, 14f), 0, Ui.dp(ctx, 14f), Ui.dp(ctx, 10f))
        })

        // 书签区（v2.1：即自定义网站/书签，可在首页与「我的→书签管理」增删改）
        root.addView(Ui.text(ctx, "书签", 13f, Ui.TXT_SUB, bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(Ui.dp(ctx, 18f), Ui.dp(ctx, 4f), Ui.dp(ctx, 18f), Ui.dp(ctx, 6f))
            })
        root.addView(Ui.text(ctx, "点击打开 · 长按编辑/置顶/删除 · 地址栏☆可收藏当前页", 11f, 0xFF9CA3AF.toInt()),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(Ui.dp(ctx, 18f), 0, Ui.dp(ctx, 18f), Ui.dp(ctx, 6f))
            })
        val sitesGrid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sitesGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(ctx, 14f), 0, Ui.dp(ctx, 14f), Ui.dp(ctx, 20f))
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
        val card = Ui.card(ctx, 14f)
        Ui.pressable(card)
        val iv = TextView(ctx).apply {
            text = site.name.take(1)
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.colorOf(site.name)); cornerRadius = Ui.dp(ctx, 12f).toFloat()
            }
        }
        card.addView(iv, LinearLayout.LayoutParams(Ui.dp(ctx, 40f), Ui.dp(ctx, 40f)))
        card.addView(Ui.text(ctx, site.name, 13f, Ui.TXT, bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(ctx, 6f)
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
        Site("高清族", "https://www.gaoqingzu.com"),
        Site("SHB影视", "https://www.shb3.com")
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
