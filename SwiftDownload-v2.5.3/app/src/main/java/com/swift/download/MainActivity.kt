package com.swift.download

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.swift.download.browser.BookmarkManagerPage
import com.swift.download.browser.BrowserPage
import com.swift.download.dlna.DlnaPicker
import com.swift.download.files.FilesPage
import com.swift.download.magnet.MagnetSearchPage
import com.swift.download.magnet.MagnetSourceManagerPage
import com.swift.download.settings.DownloadSettings
import com.swift.download.settings.DownloadSettingsPage
import com.swift.download.transfers.TransfersPage
import com.swift.download.ui.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * v2.0 主壳：底部导航四页（浏览器 / 传输 / 文件 / 我的）。
 *  - 保留 v1.0 的 http/https 直链接管；
 *  - 新增 magnet 与 .torrent 接管；
 *  - 传输页 = v1.0 主界面移植；浏览器/磁力搜索/磁力源管理 = v2.0 新增。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var pageHost: FrameLayout
    private lateinit var browserPage: BrowserPage
    private lateinit var transfersPage: TransfersPage
    private lateinit var filesPage: FilesPage
    private var magnetSearchPage: MagnetSearchPage? = null

    private var binder: DownloadService.DownloadBinder? = null
    private var overlay: FrameLayout? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val serviceConn = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) {
            binder = service as? DownloadService.DownloadBinder
            binder?.setUiListener(uiListener)
            pushTasks()
            // v2.5：服务连接后才处理待办的 Intent。
            // bindService 是异步的，onCreate 里 binder 必为 null，
            // 此前外部 magnet/.torrent 链接会被静默丢弃。
            pendingIntent?.let {
                pendingIntent = null
                handleIntent(it)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    /** v2.5：服务尚未连接时暂存的 Intent（连接后补处理） */
    private var pendingIntent: Intent? = null

    private val uiListener = object : DownloadService.UiListener {
        override fun onChanged(tasks: List<DownloadTask>) {
            lastTasks = tasks
            transfersPage.accept(tasks, emptyMap())
        }

        /** v2.5：优先使用服务端精确速度，避免 UI 端差分自算的抖动 */
        override fun onChanged(tasks: List<DownloadTask>, speeds: Map<Long, Long>) {
            lastTasks = tasks
            transfersPage.accept(tasks, speeds)
        }
    }

    private fun pushTasks() {
        binder?.let { transfersPage.accept(it.service.tasksSnapshot(), emptyMap()) }
    }

    private var lastTasks: List<DownloadTask> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureDirs()
        requestNotifPermission()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageHost = FrameLayout(this)
        root.addView(pageHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildBottomNav(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        browserPage = BrowserPage(
            this,
            onNewDownload = { url, name, referer, cookie -> addHttp(url, name, referer, cookie) },
            onNewMagnet = { uri -> addMagnet(uri) },
            onOpenMagnetSearch = { kw -> openMagnetSearch(kw) },
            onVideoDetected = { url, name, referer, cookie ->
                // v2.5：嗅探到的 mp4 直链也带 Referer（防盗链站点否则 403）
                if (com.swift.download.player.VideoSniff.isMp4(url)) {
                    binder?.addHttpTaskWithReferer(url, name, 4, referer, cookie)
                } else {
                    binder?.addVideoTask(url, name, url, referer, cookie)
                }
                switchTo(1)
                Ui.toast(this, "视频任务已添加")
            }
        )
        transfersPage = TransfersPage(this, transferController)
        filesPage = FilesPage(this, onCast = { path -> showCastPicker(java.io.File(path).name, path) })

        switchTo(0)
        bindDownloadService()
        handleIntent(intent)
        // v2.5：Android 11+ 分区存储下无法写公共下载目录，已自动降级到应用专属目录。
        // 明确告知用户文件实际位置，避免"文件去哪了"的困惑。
        if (StorageRoots.usingFallback(this)) {
            Ui.toast(this, "已适配分区存储：文件保存在应用专属目录「Android/data/极速下载/files/Download」")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /* ---------------- intent 接管 ---------------- */

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return
        // v2.5：服务未连接时先暂存，连接完成后补处理，避免任务被静默丢弃
        if (binder == null) {
            pendingIntent = intent
            return
        }
        when {
            intent.action == Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                routeUrl(text)
            }
            intent.data != null -> routeUri(intent.data!!)
        }
    }

    private fun routeUri(uri: Uri) {
        when (uri.scheme?.lowercase()) {
            "magnet" -> addMagnet(uri.toString())
            "http", "https" -> {
                val u = uri.toString()
                if (u.endsWith(".torrent", true) || u.contains(".torrent?")) {
                    addTorrentFromUrl(u)
                } else {
                    // 打开内置浏览器（v2.0 行为：接管链接进入浏览器而非直接下载）
                    browserPage.newTab(u)
                    switchTo(0)
                }
            }
            "file", "content" -> addTorrentFromFile(uri)
            else -> { /* 忽略 */ }
        }
    }

    private fun routeUrl(text: String) {
        when {
            text.startsWith("magnet:") -> addMagnet(text)
            text.startsWith("http") -> {
                if (text.endsWith(".torrent", true)) addTorrentFromUrl(text) else addHttp(text, "")
            }
        }
    }

    private fun addTorrentFromUrl(url: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000
                conn.setRequestProperty(
                    "User-Agent",
                    HttpUtil.USER_AGENT
                )
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                val f = File(cacheDir, "seed_${System.currentTimeMillis()}.torrent")
                f.writeBytes(bytes)
                scope.launch {
                    binder?.addTorrentFileTask(f.absolutePath)
                    switchTo(1)
                }
            } catch (e: Exception) {
                scope.launch { Ui.toast(this@MainActivity, "种子下载失败：${e.message}") }
            }
        }
    }

    private fun addTorrentFromFile(uri: Uri) {
        try {
            val name = uri.lastPathSegment ?: "seed.torrent"
            val f = File(cacheDir, "seed_${System.currentTimeMillis()}_${name}")
            contentResolver.openInputStream(uri)?.use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
            binder?.addTorrentFileTask(f.absolutePath)
            switchTo(1)
        } catch (e: Exception) {
            Ui.toast(this, "无法读取种子文件")
        }
    }

    private fun addHttp(
        url: String, name: String, referer: String = "",
        cookie: String = "", userAgent: String = ""
    ) {
        if (referer.isNotBlank() || cookie.isNotBlank() || userAgent.isNotBlank()) {
            binder?.addHttpTaskWithReferer(url, name, 4, referer, cookie, userAgent)
        } else {
            binder?.addHttpTask(url, name, 4)
        }
        switchTo(1)
    }

    private fun addMagnet(uri: String) {
        binder?.addMagnetTask(uri)
        switchTo(1)
    }

    /* ---------------- v2.2：边下边播 / 投屏 ---------------- */

    /** 边下边播：拿到本地流地址 → 唤起系统播放器 */
    private fun streamTask(id: Long) {
        // 播放体验兜底：moov 后置的 mp4 拖动进度不可用，提前提示用户
        val task = lastTasks.firstOrNull { it.id == id }
        if (task != null && MediaTypes.isVideoName(task.fileName)) {
            val part = java.io.File(task.savePath + ".part")
            if (part.isFile && part.length() > 256L * 1024L) {
                try {
                    val head = ByteArray(65536)
                    java.io.RandomAccessFile(part, "r").use { it.read(head) }
                    if (!com.swift.download.player.VideoProbe.hasMoovNearStart(head)) {
                        Ui.toast(this, "提示：该视频索引不在文件头，拖动进度可能不可用")
                    }
                } catch (_: Exception) { /* 探测失败不阻断播放 */ }
            }
        }
        // v2.5：探测含网络请求，改用异步接口，避免阻塞主线程（并可等待任务就绪）
        val b = binder
        if (b == null) {
            Ui.toast(this, "下载服务未就绪，请稍后再试")
            return
        }
        b.startStreamAsync(id) { url ->
            if (url == null) {
                Ui.toast(this, "该任务暂不支持边下边播（需 HTTP 直链视频且已获取文件大小）")
                return@startStreamAsync
            }
            val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(url), "video/*")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(view, "选择播放器（建议 VLC / MXPlayer）"))
            } catch (e: Exception) {
                Ui.toast(this, "没有可用的视频播放器")
            }
        }
    }

    /** 投屏：弹 DLNA 设备选择器（选定时才开本地文件服务器） */
    private fun showCastPicker(title: String, path: String) {
        DlnaPicker(this, title, urlProvider = {
            binder?.serveFile(path)
        }, onStopCasting = {
            binder?.stopServeFile()
        }).show()
    }

    /* ---------------- 底部导航（v2.2：激活胶囊 + 更大点击区域） ---------------- */

    private var currentPage = 0

    private fun switchTo(idx: Int) {
        currentPage = idx
        pageHost.removeAllViews()
        val page: View = when (idx) {
            0 -> browserPage
            1 -> transfersPage
            2 -> filesPage
            else -> buildMinePage()
        }
        pageHost.addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (idx == 1) pushTasks() else if (idx == 2) filesPage.refresh()
        renderNav()
    }

    private val navTabs = ArrayList<TextView>()
    private val navIcons = ArrayList<TextView>()

    private fun buildBottomNav(): View {
        val ctx = this
        val nav = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
            elevation = Ui.dp(ctx, 6f).toFloat()
            setPadding(0, Ui.dp(ctx, 6f), 0, Ui.dp(ctx, 6f))
        }
        val items = listOf(
            Triple("浏览器", "🌐", 0),
            Triple("传输", "⇅", 1),
            Triple("文件", "📁", 2),
            Triple("我的", "👤", 3)
        )
        for ((label, icon, idx) in items) {
            val tab = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(ctx, 2f), 0, Ui.dp(ctx, 2f))
                setOnClickListener { switchTo(idx) }
            }
            val ic = TextView(ctx).apply {
                text = icon; textSize = 18f; gravity = Gravity.CENTER
                setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 4f), Ui.dp(ctx, 12f), Ui.dp(ctx, 4f))
            }
            val tv = TextView(ctx).apply {
                text = label; textSize = 11f; setTextColor(Ui.TXT_SUB)
                gravity = Gravity.CENTER
            }
            tab.addView(ic, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            tab.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            nav.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            navTabs.add(tv)
            navIcons.add(ic)
        }
        return nav
    }

    private fun renderNav() {
        for ((i, tv) in navTabs.withIndex()) {
            val active = i == currentPage
            tv.setTextColor(if (active) Ui.BLUE else Ui.TXT_SUB)
            tv.typeface = if (active) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            navIcons.getOrNull(i)?.apply {
                setTextColor(if (active) Ui.BLUE else Ui.TXT_SUB)
                background = if (active) android.graphics.drawable.GradientDrawable().apply {
                    setColor(Ui.BLUE_LIGHT); cornerRadius = Ui.dp(this@MainActivity, 14f).toFloat()
                } else null
            }
        }
    }

    /* ---------------- 我的页 ---------------- */

    /** 版本号统一取自安装包信息，避免界面文案与 versionName 脱节 */
    private val versionLabel: String by lazy {
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun buildMinePage(): View {
        val ctx = this
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val card = Ui.card(ctx, 18f)
        val title = Ui.text(ctx, "极速下载 v$versionLabel", 22f, Ui.TXT, bold = true)
        card.addView(title)
        card.addView(Ui.text(ctx, "多线程下载 · 磁力搜索 · BT · 浏览器 · 边下边播 · 投屏", 13f, Ui.TXT_SUB),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 4f) })
        header.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(ctx, 14f), Ui.dp(ctx, 16f), Ui.dp(ctx, 14f), 0)
        })
        root.addView(header)

        val entries = listOf(
            "书签管理" to {
                showOverlay(BookmarkManagerPage(this,
                    onOpen = { url ->
                        closeOverlay()
                        switchTo(0)
                        browserPage.newTab(url)
                    },
                    onChanged = { browserPage.refreshBookmarks() }
                ), "书签管理")
            },
            "磁力源管理" to { showOverlay(MagnetSourceManagerPage(ctx), "磁力源管理") },
            "下载设置" to { showSettingsFromMine() },
            "局域网传链接" to { toggleLanRemote() },
            "关于" to { showAbout() }
        )
        for ((name, act) in entries) {
            val row = Ui.card(ctx, 14f)
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(Ui.text(ctx, name, 16f, Ui.TXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(Ui.text(ctx, "›", 18f, Ui.TXT_SUB))
            row.addView(line)
            row.setOnClickListener { act() }
            root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(Ui.dp(ctx, 14f), Ui.dp(ctx, 10f), Ui.dp(ctx, 14f), 0)
            })
        }
        root.addView(View(ctx), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 24f)))
        return Ui.verticalScroll(ctx, root)
    }

    /** v2.6.0：一键开关局域网投递面板，显示或关闭访问地址 */
    private fun toggleLanRemote() {
        val b = binder
        if (b == null) {
            Ui.toast(this, "下载服务未就绪，请稍后再试")
            return
        }
        if (b.isLanRemoteRunning()) {
            b.stopLanRemote()
            Ui.toast(this, "局域网投递已关闭")
            return
        }
        val url = b.startLanRemote()
        if (url == null) {
            android.app.AlertDialog.Builder(this)
                .setTitle("无法开启局域网投递")
                .setMessage("未检测到 Wi-Fi 局域网地址，请连接 Wi-Fi 后重试。\n\n出于安全考虑，此面板只在可信网络下临时开启，用完请及时关闭。")
                .setPositiveButton("知道了", null)
                .show()
        } else {
            android.app.AlertDialog.Builder(this)
                .setTitle("局域网投递已开启")
                .setMessage("在电脑浏览器打开：\n\n$url\n\n可粘贴直链/磁力、上传 .torrent，并查看任务列表。\n用完请回到此页面点「局域网传链接」关闭。")
                .setPositiveButton("复制地址") { _, _ ->
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("lan", url))
                        Ui.toast(this, "地址已复制")
                    } catch (e: Exception) { Ui.toast(this, url) }
                }
                .setNegativeButton("关闭面板", null)
                .show()
        }
    }

    private fun showSettingsFromMine() {
        showOverlay(DownloadSettingsPage(
            this,
            binder?.getSettings() ?: DownloadSettings.get(this),
            onClose = { closeOverlay() }
        ), "下载设置")
    }

    private fun showAbout() {
        android.app.AlertDialog.Builder(this)
            .setTitle("极速下载")
            .setMessage("v$versionLabel\n\n· 多线程 HTTP 下载 · 断点续传\n· 磁力聚合搜索（5 源）\n· BT 引擎（jlibtorrent）\n· 内置多标签浏览器 · 书签管理\n· 浏览器视频嗅探 · 清晰度可选下载\n· 边下边播（HTTP 视频）\n· DLNA 投屏 · 文件排序/分享/重命名\n· 限速 / 仅 Wi-Fi / 时段调度 / 文件分类\n· 链接刷新 / HTTP 代理 / m3u8 下载\n\nv2.5.3 为代码清理与文档完善版，下载行为与 v2.5.2 一致。\nv2.5.2 修复下载完整性：服务器不守规矩时不再产出“已完成”的坏文件。")
            .setPositiveButton("确定", null)
            .show()
    }

    /* ---------------- 磁力搜索浮层 ---------------- */

    private fun openMagnetSearch(keyword: String) {
        val page = MagnetSearchPage(this) { magnetUri ->
            addMagnet(magnetUri)
            closeOverlay()
        }
        magnetSearchPage = page
        showOverlay(page, "磁力搜索")
        if (keyword.isNotEmpty()) page.prefillKeyword(keyword)
    }

    /** v2.6.0：浮层统一加「← 返回 + 标题」导航栏，不再只能靠系统手势/返回键退出 */
    private fun showOverlay(page: View, title: String = "详情") {
        val root = findViewById<ViewGroup>(android.R.id.content)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 10f),
                Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 10f))
        }
        val back = TextView(this).apply {
            text = "← 返回"
            textSize = 15f
            setTextColor(Ui.BLUE)
            setPadding(Ui.dp(this@MainActivity, 4f), Ui.dp(this@MainActivity, 4f),
                Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 4f))
            setOnClickListener { closeOverlay() }
        }
        header.addView(back)
        header.addView(Ui.text(this, title, 16f, Ui.TXT, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(page, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val fl = FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(fl, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = fl
    }

    private fun closeOverlay() {
        (overlay?.parent as? ViewGroup)?.removeView(overlay)
        overlay = null
    }

    override fun onBackPressed() {
        if (overlay != null) { closeOverlay(); return }
        if (currentPage == 0) {
            val wv = browserPage.currentWebView()
            if (wv?.canGoBack() == true) { wv.goBack(); return }
        }
        // v2.2：双击返回退出（防误触）
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBackAt < 2000) {
            super.onBackPressed()
        } else {
            lastBackAt = now
            Ui.toast(this, "再按一次返回键退出")
        }
    }

    private var lastBackAt = 0L

    /* ---------------- v2.6.0：窗口获焦检测剪贴板 + WebView 生命周期 ---------------- */

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            try { transfersPage.checkClipboard() } catch (e: Exception) { /* 剪贴板权限/空内容 */ }
        }
    }

    override fun onPause() {
        super.onPause()
        try { browserPage.onHostPause() } catch (e: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        try { browserPage.onHostResume() } catch (e: Exception) {}
    }

    /* ---------------- 服务连接 ---------------- */
    private fun bindDownloadService() {
        DownloadService.start(this)
        bound = bindService(
            Intent(this, DownloadService::class.java),
            serviceConn,
            Context.BIND_AUTO_CREATE
        )
    }

    /** v2.5：是否已绑定服务（onDestroy 时据此决定是否解绑） */
    private var bound = false

    override fun onDestroy() {
        super.onDestroy()
        // v2.5：解绑服务。此前从不 unbindService，Activity 销毁后绑定关系仍在，
        // 服务的 stopSelf() 因仍有绑定而无法生效 → 前台通知与后台服务长期驻留。
        if (bound) {
            try {
                unbindService(serviceConn)
            } catch (e: Exception) {
                // 已解绑
            }
            bound = false
        }
        binder?.setUiListener(null)
        binder = null
        try { browserPage.onHostDestroy() } catch (e: Exception) {}
        scope.cancel()
    }

    private val transferController = object : TransfersPage.Controller {
        override fun addHttpTask(
            url: String, fileName: String, threads: Int,
            referer: String, cookie: String, userAgent: String
        ) {
            binder?.addHttpTaskWithReferer(url, fileName, threads, referer, cookie, userAgent)
        }
        override fun addMagnetLink(uri: String) { addMagnet(uri) }
        override fun pause(id: Long) { binder?.pause(id) }
        override fun resume(id: Long) { binder?.resume(id) }
        override fun delete(id: Long, withFiles: Boolean) { binder?.delete(id, withFiles) }
        override fun restart(id: Long) {
            // v2.5.2：重下改由服务端用原始参数重建（保留 m3u8 变体/来源页，.torrent 不再当磁力处理），
            // 同时彻底清除旧进度与磁盘残留，"重新下载"不会再变成"继续下载"
            binder?.restartTask(id)
        }
        override fun openFile(path: String) {
            sendBroadcast(OpenFileReceiver.intentOf(path).setPackage(packageName))
        }
        override fun streamTask(id: Long) { this@MainActivity.streamTask(id) }
        override fun castTask(path: String) { showCastPicker(java.io.File(path).name, path) }
        override fun pauseAll() { binder?.pauseAll() }
        override fun resumeAll() { binder?.resumeAll() }
        override fun clearCompleted() {
            binder?.clearCompleted()
            Ui.toast(this@MainActivity, "已清除完成记录（文件保留）")
        }
        override fun refreshLink(id: Long, newUrl: String): Boolean =
            binder?.refreshLink(id, newUrl) == true
        override fun setTaskLimit(id: Long, bps: Long) {
            binder?.setTaskLimitBps(id, bps)
        }
        override fun taskLimits(): Map<Long, Long> {
            val s = binder?.getSettings() ?: return emptyMap()
            return lastTasks.associate { it.id to s.taskLimitBps(it.id) }.filterValues { it > 0L }
        }
        override fun btFiles(id: Long): List<com.swift.download.bt.BtFile> =
            binder?.btFiles(id) ?: emptyList()
        override fun setBtFileSelection(id: Long, selected: IntArray): Boolean =
            binder?.setBtFileSelection(id, selected) == true
        override fun setBtSequential(id: Long, enabled: Boolean): Boolean =
            binder?.setBtSequential(id, enabled) == true
        override fun isBtSequential(id: Long): Boolean =
            binder?.isBtSequential(id) == true
        override fun btReadyVideoPath(id: Long): String? = binder?.btReadyVideoPath(id)
    }

    /* ---------------- 杂项 ---------------- */

    private fun ensureDirs() {
        File(
            StorageRoots.downloadRoot(this),
            "BT"
        ).mkdirs()
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }
    }
}
