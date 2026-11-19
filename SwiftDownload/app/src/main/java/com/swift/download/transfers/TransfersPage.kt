package com.swift.download.transfers

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.swift.download.DownloadTask
import com.swift.download.Format
import com.swift.download.MediaTypes
import com.swift.download.ui.FlowLayout
import com.swift.download.ui.Ui
import com.swift.download.ui.addFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * v2.0「传输」页 —— v1.0 主界面的行为级移植：
 *  - 任务卡（进度条 / 状态 / 速度+剩余时间 / 已下载体积 / 线程数徽标 / 失败原因）
 *  - 暂停/继续、删除（确认对话框文案 v1.0 原文）、完成后打开/重新下载
 *  - 「+ 新建下载任务」卡片、剪贴板直链检测卡（检测到已复制的下载链接 → 立即下载/忽略）
 *  - 下载设置（线程数 seek，v1.0 文案原文）
 *  - 空状态文案 v1.0 原文
 *  v2.0 增量：BT 任务卡（做种/节点数、等待元数据状态）
 */
@SuppressLint("ViewConstructor")
class TransfersPage(
    context: Context,
    private val controller: Controller
) : android.widget.FrameLayout(context) {

    interface Controller {
        fun addHttpTask(
            url: String, fileName: String, threads: Int,
            referer: String = "", cookie: String = "", userAgent: String = ""
        )
        /** v2.6.0：剪贴板识别到的磁力链接 */
        fun addMagnetLink(uri: String) {}
        fun pause(id: Long)
        fun resume(id: Long)
        fun delete(id: Long, withFiles: Boolean)
        fun restart(id: Long)
        /** v2.8 模块一：单线程重建（403/Referer 受限站点重试） */
        fun restartSingleThread(id: Long) {}
        fun openFile(path: String)
        /** v2.2：边下边播（本地服务器接管，边播边下） */
        fun streamTask(id: Long)
        /** v2.2：投屏播放已完成文件 */
        fun castTask(path: String)
        fun pauseAll()
        fun resumeAll()
        fun clearCompleted()
        /** v2.7 Stage1.1：刷新链接时是否保留断点（异步探测后回调 结果/提示） */
        fun refreshLinkKeepProgress(
            id: Long, newUrl: String, keep: Boolean, cb: (Boolean, String) -> Unit
        ) {
            cb(refreshLink(id, newUrl), "")
        }

        /** 链接刷新：替换失效直链并从头重下 */
        fun refreshLink(id: Long, newUrl: String): Boolean
        /** 单任务限速，字节/秒；0 = 跟随全局 */
        fun setTaskLimit(id: Long, bps: Long)
        /** v2.6.0：从持久化设置回读所有任务限速值（重启后仍能回显） */
        fun taskLimits(): Map<Long, Long> = emptyMap()
        /** v2.6.0：文件是否仍可用（兼容 MediaStore content:// 导出文件） */
        fun fileAvailable(t: DownloadTask): Boolean =
            java.io.File(t.savePath).isFile || t.exportUri.isNotBlank()
        /* v2.6.0：BT 选文件 / 顺序下载 / 就绪即播 */
        fun btFiles(id: Long): List<com.swift.download.bt.BtFile> = emptyList()
        fun setBtFileSelection(id: Long, selected: IntArray): Boolean = false
        fun setBtSequential(id: Long, enabled: Boolean): Boolean = false
        fun isBtSequential(id: Long): Boolean = false
        fun btReadyVideoPath(id: Long): String? = null
        /* v2.7 Stage1：任务详情 / 重连提示 / 剩余空间 */
        fun retryHint(id: Long): String = ""
        fun freeSpaceLabel(): String = ""
        /** HTTP 分段数（无则用 -1 表示不适用） */
        fun httpSegmentCount(id: Long): Int = -1
        /** BT 做种/节点数；无则 null */
        fun btSeedsPeers(id: Long): Pair<Int, Int>? = null
        /** v2.8 模块一：BT 实时诊断（DHT 节点/状态标签/tracker 错误） */
        fun btDiagnostics(id: Long): com.swift.download.BtDiag? = null
    }

    /** 空任务时隐藏 RecyclerView，改显统一空状态（二者互斥） */
    private val rv: RecyclerView
    private val emptyView: View
    private var threadsPref = 4
    private val tvTotal: TextView
    private lateinit var tvFreeSpace: TextView
    private val adapter = TaskAdapter()
    private val toolbar = FlowLayout(context)
    /** v2.8 交互优化：空任务时隐藏筛选栏，减少空状态杂乱 */
    private val filterBar = FlowLayout(context)

    /** v2.7 Stage4.2：状态筛选 */
    private var currentFilter = TaskFilters.Filter.ALL
    private val filterPills = ArrayList<TextView>()

    /**
     * 任务列表缓存。必须声明在 init 块之前 —— init 里构建筛选胶囊时会调用
     * labelOf() → TaskFilters.counts(lastTasks)，若此时 lastTasks 尚未初始化则为 null，
     * 传给非空参数 tasks 会直接 NPE 导致 Activity 启动崩溃（真机实测踩坑）。
     */
    private var lastTasks: List<DownloadTask> = emptyList()

    private val speedHints = HashMap<Long, Long>()

    /** v2.5：单任务限速值缓存（供弹窗回显与按钮标记） */
    private val taskLimits = HashMap<Long, Long>()

    /** 速度差分采样（init 末尾的 onTasks 会经 computeSpeeds 访问，必须在 init 之前初始化） */
    private val lastBytes = HashMap<Long, Long>()
    private val lastTs = HashMap<Long, Long>()
    private val serverSpeeds = HashMap<Long, Long>()

    /** 剪贴板忽略记录 */
    private var ignoredClipboardUrl: String? = null

    private val lastTasksRef get() = lastTasks

    init {
        setBackgroundColor(Ui.BG)
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 14f), Ui.dp(context, 10f), Ui.dp(context, 14f), Ui.dp(context, 4f))
        }
        val title = Ui.text(context, "传输", 20f, Ui.TXT, bold = true)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tvTotal = Ui.numericText(context, "", 13f, Ui.TXT_SUB)
        header.addView(tvTotal)
        // v2.8 交互优化：醒目的「＋ 新建」主入口，用户不必滚到列表底部找新建卡片
        val addBtn = TextView(context).apply {
            text = "＋ 新建"; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(Ui.dp(context, 12f), Ui.dp(context, 6f), Ui.dp(context, 12f), Ui.dp(context, 6f))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.BLUE); cornerRadius = Ui.dp(context, 16f).toFloat()
            }
            Ui.pressable(this)
            setOnClickListener { showNewTaskDialog() }
        }
        header.addView(addBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(context, 8f) })
        val settings = TextView(context).apply {
            text = "设置"; textSize = 13f; setTextColor(Ui.BLUE)
            setPadding(Ui.dp(context, 10f), Ui.dp(context, 4f), Ui.dp(context, 2f), Ui.dp(context, 4f))
            setOnClickListener { showSettingsDialog() }
        }
        header.addView(settings)
        root.addView(header)

        // v2.7 Stage1.3：剩余存储空间提示
        tvFreeSpace = Ui.text(context, "", 11f, Ui.TXT_SUB)
        root.addView(tvFreeSpace, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = Ui.dp(context, 14f); rightMargin = Ui.dp(context, 14f) })

        // v2.2：批量操作工具栏（v2.3 改流式布局，窄屏自动换行不裁切）
        toolbar.setPadding(Ui.dp(context, 14f), Ui.dp(context, 4f), Ui.dp(context, 14f), Ui.dp(context, 2f))
        toolbar.addFlow(Ui.pillBtn(context, "全部暂停", Ui.TXT_SUB) { controller.pauseAll() })
        toolbar.addFlow(Ui.pillBtn(context, "全部继续", Ui.BLUE, filled = true) { controller.resumeAll() })
        toolbar.addFlow(Ui.pillBtn(context, "清除完成", Ui.TXT_SUB) { controller.clearCompleted() })
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // v2.7 Stage4.2：状态筛选胶囊栏（带数量角标）
        filterBar.setPadding(Ui.dp(context, 14f), Ui.dp(context, 2f), Ui.dp(context, 14f), Ui.dp(context, 4f))
        for (f in TaskFilters.Filter.entries) {
            val pill = Ui.pillBtn(context, labelOf(f), Ui.BLUE, filled = (f == currentFilter)) {
                currentFilter = f
                refreshFilterPills()
                onTasks(lastTasks)
            }
            filterPills.add(pill)
            filterBar.addFlow(pill)
        }
        root.addView(filterBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        rv = Ui.recyclerView(context)
        rv.adapter = adapter
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        emptyView = Ui.emptyState(
            context, "⇅", "还没有下载任务",
            "点「＋新建」粘贴链接，或复制磁力链接自动识别",
            "＋新建任务",
            onAction = { showNewTaskDialog() }
        )
        emptyView.visibility = View.GONE
        root.addView(emptyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        onTasks(emptyList())
    }

    /* ---------------- 数据刷新 ---------------- */

    private fun DownloadTask.speedHint(): Long = speedHints[id] ?: 0L

    /**
     * v2.5：速度优先取服务端采样值（1 秒窗口差分）。
     * 本地差分自算仅作为服务端未提供时的兜底 —— 否则任务刚启动时首帧恒为 0 B/s。
     */
    private fun computeSpeeds(tasks: List<DownloadTask>) {
        val now = System.currentTimeMillis()
        val live = tasks.filter { it.status == DownloadTask.Status.RUNNING }
        for (t in live) {
            val svc = serverSpeeds[t.id]
            if (svc != null) {
                speedHints[t.id] = svc
                continue
            }
            val lb = lastBytes[t.id]
            val lt = lastTs[t.id]
            if (lb != null && lt != null && now > lt) {
                val dt = (now - lt) / 1000.0
                val v = ((t.downloadedBytes - lb) / dt).toLong()
                speedHints[t.id] = v.coerceAtLeast(0L)
            } else if (lb == null) {
                speedHints[t.id] = 0L
            }
            lastBytes[t.id] = t.downloadedBytes
            lastTs[t.id] = now
        }
        // 清理非运行任务的采样
        val liveIds = live.map { it.id }.toHashSet()
        for (id in speedHints.keys.toList()) if (id !in liveIds) speedHints.remove(id)
        for (id in lastBytes.keys.toList()) if (id !in liveIds) { lastBytes.remove(id); lastTs.remove(id) }
        for (id in serverSpeeds.keys.toList()) if (id !in liveIds) serverSpeeds.remove(id)
    }

    /** v2.7 Stage4.2：筛选胶囊文案（带数量角标） */
    private fun labelOf(f: TaskFilters.Filter): String {
        val c = TaskFilters.counts(lastTasks)
        val n = when (f) {
            TaskFilters.Filter.ALL -> c.all
            TaskFilters.Filter.ACTIVE -> c.active
            TaskFilters.Filter.COMPLETED -> c.completed
            TaskFilters.Filter.FAILED -> c.failed
        }
        val name = when (f) {
            TaskFilters.Filter.ALL -> "全部"
            TaskFilters.Filter.ACTIVE -> "进行中"
            TaskFilters.Filter.COMPLETED -> "已完成"
            TaskFilters.Filter.FAILED -> "失败"
        }
        return "$name($n)"
    }

    private fun refreshFilterPills() {
        for ((i, pill) in filterPills.withIndex()) {
            val f = TaskFilters.Filter.entries[i]
            pill.text = labelOf(f)
            // 选中：BLUE 底白字；未选中：SURFACE_MUTED 底 + TXT_SUB 字 + 无描边
            pill.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (f == currentFilter) Ui.BLUE else Ui.SURFACE_MUTED)
                cornerRadius = Ui.dp(context, 16f).toFloat()
            }
            pill.setTextColor(if (f == currentFilter) 0xFFFFFFFF.toInt() else Ui.TXT_SUB)
        }
    }

    fun onTasks(tasks: List<DownloadTask>) {
        computeSpeeds(tasks)
        val running = tasks.filter { it.status == DownloadTask.Status.RUNNING }
        val totalSpeed = running.sumOf { it.speedHint() }
        tvTotal.text = buildString {
            append("${tasks.size} 个任务")
            if (totalSpeed > 0) append(" · ").append(Format.speed(totalSpeed))
        }
        // v2.7 Stage1.3：剩余空间（从服务读取 StatFs，避免 UI 层重复解析根目录）
        if (::tvFreeSpace.isInitialized) {
            val free = controller.freeSpaceLabel()
            tvFreeSpace.text = free
            tvFreeSpace.visibility = if (free.isEmpty()) View.GONE else View.VISIBLE
        }
        toolbar.visibility = if (tasks.isEmpty()) View.GONE else View.VISIBLE
        filterBar.visibility = if (tasks.isEmpty()) View.GONE else View.VISIBLE
        refreshFilterPills()
        // v2.7 Stage4.2：按当前筛选条件展示任务（剪贴板卡/新建卡始终在最上方保留）
        val shown = TaskFilters.filter(tasks, currentFilter)
        // 内容为空 = 无任务且无剪贴板识别卡；此时显示统一空状态并隐藏列表
        val hasContent = shown.isNotEmpty() || adapter.pendingClipUrl != null
        rv.visibility = if (hasContent) View.VISIBLE else View.GONE
        emptyView.visibility = if (hasContent) View.GONE else View.VISIBLE
        adapter.submit(buildList {
            adapter.pendingClipUrl?.let { add(Decor.ClipCard(it)) }
            add(Decor.NewTaskCard)
            for (t in shown) add(Decor.DecorTask(t))
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        checkClipboard()
    }

    /* ---------------- 剪贴板检测卡（v1.0） ---------------- */

    /** v2.6.0：从整段分享文案中提取直链/磁力（获焦时由宿主调用） */
    fun checkClipboard() {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: return
            val links = com.swift.download.player.VideoSniff.extractLinks(text)
            val hit = links.firstOrNull { it != ignoredClipboardUrl } ?: return
            adapter.pendingClipUrl = hit
            onTasks(lastTasks)
        } catch (e: Exception) {
            // 忽略
        }
    }

    /* ---------------- 对话框 ---------------- */

    private fun showNewTaskDialog(prefill: String = "") {
        val ctx = context
        // v2.7 Stage3.3：支持多行粘贴（不再强制单行），检测到 ≥2 条链接时提示可批量建任务
        val et = Ui.singleLineEt(ctx, "粘贴 http/https 直链或磁力（支持多行，一次多条）").apply {
            setText(prefill)
            isSingleLine = false
            maxLines = 6
        }
        // v2.6.0：高级选项可折叠，平时不干扰"粘贴即下"的主流程
        val advanced = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val etName = Ui.singleLineEt(ctx, "自定义文件名（留空自动识别）")
        val etReferer = Ui.singleLineEt(ctx, "Referer（防盗链来源页）")
        val etCookie = Ui.singleLineEt(ctx, "Cookie（登录态）")
        val etUa = Ui.singleLineEt(ctx, "User-Agent（留空用内置 UA）")
        for (field in listOf(etName, etReferer, etCookie, etUa)) {
            advanced.addView(field, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 6f) })
        }
        val toggle = Ui.text(ctx, "高级选项 ▾", 13f, Ui.BLUE).apply {
            setOnClickListener {
                val open = advanced.visibility == View.GONE
                advanced.visibility = if (open) View.VISIBLE else View.GONE
                text = if (open) "高级选项 ▴" else "高级选项 ▾"
            }
        }
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 8f), Ui.dp(ctx, 20f), 0)
            addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Ui.text(ctx, "支持多线程加速与断点续传；可一次粘贴多条链接批量识别。", 12f, Ui.TXT_SUB),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8f) })
            // v2.7 Stage3.3：批量识别链接按钮
            addView(Ui.pillBtn(ctx, "批量识别链接", Ui.BLUE, filled = true) {
                val links = com.swift.download.player.VideoSniff.extractLinks(et.text.toString())
                if (links.size < 2) {
                    Ui.toast(ctx, "未识别到 2 条以上链接（支持 http/https 与 magnet）")
                } else {
                    showBatchConfirmDialog(links)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8f) })
            addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8f) })
            addView(advanced, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 4f) })
        }
        AlertDialog.Builder(ctx)
            .setTitle("新建下载任务")
            .setView(wrap)
            .setPositiveButton("开始下载") { _, _ ->
                val input = et.text.toString().trim()
                // v2.7 Stage3.3：单条 magnet 直接建 BT 任务；多链接走批量确认
                if (com.swift.download.player.VideoSniff.isMagnet(input)) {
                    controller.addMagnetLink(input)
                    Ui.toast(ctx, "磁力任务已添加")
                    adapter.pendingClipUrl = null
                    return@setPositiveButton
                }
                val links = com.swift.download.player.VideoSniff.extractLinks(input)
                if (links.size >= 2) {
                    showBatchConfirmDialog(links)
                    return@setPositiveButton
                }
                val url = links.firstOrNull { com.swift.download.player.VideoSniff.kindOf(it) == com.swift.download.player.VideoSniff.LinkKind.HTTP }
                if (url != null) {
                    val name = etName.text.toString().trim()
                    controller.addHttpTask(
                        url, name, threadsPref,
                        etReferer.text.toString().trim(),
                        etCookie.text.toString().trim(),
                        etUa.text.toString().trim()
                    )
                    Ui.toast(ctx, "任务已添加：${name.ifBlank { com.swift.download.nameFromUrl(url) }}")
                    adapter.pendingClipUrl = null
                } else {
                    Ui.toast(ctx, "请输入 http/https 开头的直链或 magnet 磁力")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** v2.7 Stage3.3：批量确认列表（复选框 + 全选/反选），一键建立所有选中任务 */
    private fun showBatchConfirmDialog(links: List<String>) {
        val ctx = context
        val checked = BooleanArray(links.size) { true }
        val names = Array(links.size) { i ->
            val kind = com.swift.download.player.VideoSniff.kindOf(links[i])
            val tag = if (kind == com.swift.download.player.VideoSniff.LinkKind.MAGNET) "[磁力]" else "[直链]"
            val shown = if (links[i].length > 72) links[i].take(72) + "…" else links[i]
            "$tag $shown"
        }
        AlertDialog.Builder(ctx)
            .setTitle("批量建任务 · 识别到 ${links.size} 条")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("全部下载") { _, _ ->
                var n = 0
                for (i in links.indices) {
                    if (!checked[i]) continue
                    when (com.swift.download.player.VideoSniff.kindOf(links[i])) {
                        com.swift.download.player.VideoSniff.LinkKind.MAGNET -> controller.addMagnetLink(links[i])
                        com.swift.download.player.VideoSniff.LinkKind.HTTP -> controller.addHttpTask(links[i], "", threadsPref)
                        else -> continue
                    }
                    n++
                }
                Ui.toast(ctx, if (n > 0) "已添加 $n 个任务" else "未选择任何链接")
                if (n > 0) adapter.pendingClipUrl = null
            }
            .setNeutralButton("反选") { _, _ ->
                // AlertDialog 的 setNeutralButton 会关闭弹窗，反选后重新弹出以反映新选择
                for (i in checked.indices) checked[i] = !checked[i]
                showBatchConfirmDialogWithState(links, checked)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 反选后带着已有勾选状态重开确认框 */
    private fun showBatchConfirmDialogWithState(links: List<String>, checked: BooleanArray) {
        val ctx = context
        val names = Array(links.size) { i ->
            val kind = com.swift.download.player.VideoSniff.kindOf(links[i])
            val tag = if (kind == com.swift.download.player.VideoSniff.LinkKind.MAGNET) "[磁力]" else "[直链]"
            val shown = if (links[i].length > 72) links[i].take(72) + "…" else links[i]
            "$tag $shown"
        }
        AlertDialog.Builder(ctx)
            .setTitle("批量建任务 · 识别到 ${links.size} 条")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("全部下载") { _, _ ->
                var n = 0
                for (i in links.indices) {
                    if (!checked[i]) continue
                    when (com.swift.download.player.VideoSniff.kindOf(links[i])) {
                        com.swift.download.player.VideoSniff.LinkKind.MAGNET -> controller.addMagnetLink(links[i])
                        com.swift.download.player.VideoSniff.LinkKind.HTTP -> controller.addHttpTask(links[i], "", threadsPref)
                        else -> continue
                    }
                    n++
                }
                Ui.toast(ctx, if (n > 0) "已添加 $n 个任务" else "未选择任何链接")
                if (n > 0) adapter.pendingClipUrl = null
            }
            .setNeutralButton("全选") { _, _ ->
                for (i in checked.indices) checked[i] = true
                showBatchConfirmDialogWithState(links, checked)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSettingsDialog() {
        val ctx = context
        val value = AtomicInteger(threadsPref)
        val tvVal = Ui.text(ctx, "下载线程数：$threadsPref", 15f, Ui.TXT)
        val seek = SeekBar(ctx).apply {
            max = 15
            progress = threadsPref - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    value.set(p + 1)
                    tvVal.text = "下载线程数：${p + 1}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 8f), Ui.dp(ctx, 20f), 0)
            addView(tvVal, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 10f) })
            addView(Ui.text(ctx, "线程越多通常越快，但过多可能被服务器限制。\n新任务将使用此设置。", 12f, Ui.TXT_SUB),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8f) })
        }
        AlertDialog.Builder(ctx)
            .setTitle("下载设置")
            .setView(wrap)
            .setPositiveButton("确定") { _, _ -> threadsPref = value.get() }
            .setNegativeButton("取消", null)
            .show()
    }

    /* ---------------- 适配器 ---------------- */

    sealed interface Decor {
        data object NewTaskCard : Decor
        data class ClipCard(val url: String) : Decor
        data class DecorTask(val task: DownloadTask) : Decor
    }

    private inner class TaskAdapter : RecyclerView.Adapter<TaskVh>() {
        var pendingClipUrl: String? = null
        var current: List<Decor> = emptyList()
        /** DiffUtil 局部刷新：进度每 500ms 变化只重绑变化的行，不再全量重建所有卡片 */
        fun submit(list: List<Decor>) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = current.size
                override fun getNewListSize(): Int = list.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val a = current[oldPos]; val b = list[newPos]
                    return when {
                        a is Decor.DecorTask && b is Decor.DecorTask -> a.task.id == b.task.id
                        a is Decor.ClipCard && b is Decor.ClipCard -> a.url == b.url
                        else -> a.javaClass == b.javaClass
                    }
                }
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                    current[oldPos] == list[newPos]
            })
            current = list
            diff.dispatchUpdatesTo(this)
        }
        override fun getItemViewType(position: Int): Int = when (current[position]) {
            Decor.NewTaskCard -> 0
            is Decor.ClipCard -> 1
            is Decor.DecorTask -> 3
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskVh {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val card = Ui.card(ctx)
            row.addView(card)
            return TaskVh(row, card)
        }
        override fun getItemCount(): Int = current.size
        override fun onBindViewHolder(h: TaskVh, pos: Int) { h.bind(current[pos]) }
    }

    private inner class TaskVh(val row: View, private val card: LinearLayout) : RecyclerView.ViewHolder(row) {
        fun bind(d: Decor) {
            when (d) {
                Decor.NewTaskCard -> bindNewTask()
                is Decor.ClipCard -> bindClip(d.url)
                is Decor.DecorTask -> bindTask(d.task)
            }
        }

        private fun bindNewTask() {
            val ctx = card.context
            card.removeAllViews()
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val plus = TextView(ctx).apply {
                text = "＋"; textSize = 20f; gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Ui.BLUE); cornerRadius = Ui.dp(ctx, 10f).toFloat()
                }
            }
            line.addView(plus, LinearLayout.LayoutParams(Ui.dp(ctx, 36f), Ui.dp(ctx, 36f)))
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(Ui.text(ctx, "新建下载任务", 16f, Ui.TXT, bold = true))
            col.addView(Ui.text(ctx, "多线程 · 断点续传", 12f, Ui.TXT_SUB),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 2f) })
            line.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(ctx, 12f) })
            card.addView(line)
            card.setOnClickListener { showNewTaskDialog() }
        }

        private fun bindClip(url: String) {
            val ctx = card.context
            card.removeAllViews()
            card.addView(Ui.text(ctx, "检测到已复制的下载链接", 14f, Ui.BLUE, bold = true))
            val tv = Ui.text(ctx, url, 12f, Ui.TXT_SUB)
            tv.maxLines = 2
            tv.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            card.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 4f) })
            val actions = FlowLayout(ctx)
            val ignore = action(ctx, "忽略", Ui.TXT_SUB) {
                ignoredClipboardUrl = url; adapter.pendingClipUrl = null; onTasks(lastTasksRef)
            }
            val isMagnet = com.swift.download.player.VideoSniff.isMagnet(url)
            card.addView(Ui.text(ctx, if (isMagnet) "磁力链接" else "下载直链", 11f, Ui.TXT_SUB),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = Ui.dp(ctx, 2f) })
            val download = action(ctx, "立即下载", Ui.BLUE, primary = true) {
                if (isMagnet) controller.addMagnetLink(url)
                else controller.addHttpTask(url, "", threadsPref)
                adapter.pendingClipUrl = null
                ignoredClipboardUrl = url
                onTasks(lastTasksRef)
            }
            actions.addFlow(ignore)
            actions.addFlow(download)
            card.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8f) })
        }

        /**
         * 操作按钮：主操作实心、次操作描边；统一胶囊外观，点击区域不缩小。
         * [primary] 决定视觉权重，不改变行为。
         */
        private fun action(
            ctx: Context,
            label: String,
            color: Int,
            primary: Boolean = false,
            onClick: () -> Unit
        ): TextView = TextView(ctx).apply {
            text = label; textSize = 13f
            setTextColor(if (primary) 0xFFFFFFFF.toInt() else color)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 6f), Ui.dp(ctx, 12f), Ui.dp(ctx, 6f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Ui.dp(ctx, 15f).toFloat()
                if (primary) {
                    setColor(color)
                } else {
                    setColor(0x00000000)
                    setStroke(Ui.dp(ctx, 1f), color)
                }
            }
            Ui.pressable(this)
            setOnClickListener { onClick() }
        }

        @SuppressLint("SetTextI18n")
        private fun bindTask(t: DownloadTask) {
            val ctx = card.context
            card.removeAllViews()
            val st = t.status
            // v2.7 Stage1.4：点击卡片非按钮区域弹出任务详情（子按钮各自消费点击，不会冒泡到这里）
            card.setOnClickListener { showInspector(t) }

            // 名称行
            val nameRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val name = Ui.text(ctx, t.fileName.ifEmpty { t.hash.ifEmpty { "未命名" } }, 15f, Ui.TXT, bold = true)
            Ui.ellipsize(name)
            nameRow.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val kindBadge = TextView(ctx).apply {
                text = when {
                    t.isBt -> "BT"
                    t.isM3u8 -> "HLS"
                    else -> "多线程"
                }
                textSize = 10f; setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (t.isBt) Ui.GREEN else if (t.isM3u8) Ui.ORANGE else Ui.BLUE)
                    cornerRadius = Ui.dp(ctx, 7f).toFloat()
                }
                setPadding(Ui.dp(ctx, 8f), Ui.dp(ctx, 2f), Ui.dp(ctx, 8f), Ui.dp(ctx, 2f))
            }
            nameRow.addView(kindBadge)
            card.addView(nameRow)

            // 进度条
            val pb = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = st == DownloadTask.Status.PROBING
                max = 100
                progress = if (t.totalBytes > 0) (t.downloadedBytes * 100 / t.totalBytes).toInt() else 0
                progressTintList = android.content.res.ColorStateList.valueOf(
                    when (st) {
                        DownloadTask.Status.COMPLETED -> Ui.GREEN
                        DownloadTask.Status.FAILED -> Ui.RED
                        else -> Ui.BLUE
                    }
                )
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.BORDER)
            }
            card.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 6f)))

            // 状态/速度行
            val statusText = buildString {
                append(
                    when (st) {
                        DownloadTask.Status.QUEUED -> "等待"
                        DownloadTask.Status.PROBING -> if (t.isBt) "准备就绪（等待元数据…）" else "准备就绪"
                        DownloadTask.Status.RUNNING -> "下载中"
                        DownloadTask.Status.PAUSED -> "暂停"
                        DownloadTask.Status.COMPLETED -> "已完成"
                        DownloadTask.Status.FAILED -> "失败"
                    }
                )
                if (st == DownloadTask.Status.RUNNING) {
                    val spd = t.speedHint()
                    append("：").append(Format.speed(spd))
                    if (t.totalBytes > t.downloadedBytes && spd > 0) {
                        append(" · 剩余 ").append(Format.eta((t.totalBytes - t.downloadedBytes) / spd))
                    }
                }
                // v2.9.0：状态标签保留简短文案，真实连接数字改到独立诊断行（0 值也必须显示）
                if (t.isBt && (st == DownloadTask.Status.PROBING || st == DownloadTask.Status.RUNNING)) {
                    val diag = controller.btDiagnostics(t.id)
                    if (diag != null) {
                        val label = if (diag.stateLabel.isNotBlank()) diag.stateLabel
                                    else if (st == DownloadTask.Status.PROBING) "等待元数据" else "下载中"
                        append(" · ").append(label)
                    } else if (st == DownloadTask.Status.PROBING) {
                        append(" · 正在连接 DHT 网络…")
                    }
                }
                // v2.5：排队原因（等待 Wi-Fi / 等待时段）单独展示，
                // 不再与失败原因混用同一个红色提示
                if (st == DownloadTask.Status.QUEUED && t.error.isNotEmpty()) {
                    append(" · ").append(t.error)
                }
                // v2.7 Stage1.2：瞬断重连提示
                if (st == DownloadTask.Status.RUNNING) {
                    val hint = controller.retryHint(t.id)
                    if (hint.isNotEmpty()) append(" · ").append(hint)
                }
            }
            // v2.7 Stage4.1：速度/进度等动态数字用等宽字体，避免刷新时宽度跳动
            card.addView(Ui.numericText(ctx, statusText, 13f, statusColor(st)),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = Ui.dp(ctx, 6f) })

            // v2.9.0：BT 真实连接诊断行 —— 告别黑盒，0 也如实展示
            if (t.isBt && (st == DownloadTask.Status.PROBING || st == DownloadTask.Status.RUNNING)) {
                val diag = controller.btDiagnostics(t.id)
                val diagText = if (diag == null) {
                    "等待元数据 · DHT节点 0 · Tracker 0 · 已连节点 0 · 正在初始化 BT 引擎"
                } else buildString {
                    append(if (st == DownloadTask.Status.PROBING) "等待元数据" else "下载中")
                    append(" · DHT节点 ").append(diag.dhtNodes)
                    append(" · Tracker ").append(diag.trackerCount)
                    append(" · 已连节点 ").append(diag.peers)
                    if (diag.seeds > 0) append(" · 做种 ").append(diag.seeds)
                    if (diag.trackerError.isNotBlank()) append(" · ").append(diag.trackerError)
                }
                card.addView(Ui.numericText(ctx, diagText, 12f, Ui.TXT_SUB),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = Ui.dp(ctx, 3f) })
            }

            // 体积行（v2.2：带百分比）
            val pct = if (t.totalBytes > 0) " · %.1f%%".format(t.downloadedBytes * 100.0 / t.totalBytes) else ""
            val vol = if (t.totalBytes >= 0) "${Format.bytes(t.downloadedBytes)} / ${Format.bytes(t.totalBytes)}$pct" else Format.bytes(t.downloadedBytes)
            card.addView(Ui.numericText(ctx, vol, 12f, Ui.TXT_SUB),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = Ui.dp(ctx, 3f) })

            // v2.5：仅失败态显示红色错误原因；排队原因已在状态行展示
            if (st == DownloadTask.Status.FAILED && t.error.isNotEmpty()) {
                card.addView(Ui.text(ctx, "下载失败：${t.error}", 12f, Ui.RED),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = Ui.dp(ctx, 3f) })
            }

            // 操作行：自动换行 + 主次分明，窄屏/多按钮不会被裁出屏幕
            card.addView(View(ctx).apply { setBackgroundColor(Ui.DIVIDER) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    topMargin = Ui.dp(ctx, 8f)
                })

            val actions = FlowLayout(ctx)
            val streamable = t.isHttp && t.totalBytes > 0 && MediaTypes.isVideoName(t.fileName)
            when (st) {
                DownloadTask.Status.RUNNING, DownloadTask.Status.PROBING -> {
                    if (streamable) {
                        actions.addFlow(action(ctx, "边下边播", Ui.ORANGE, primary = true) { controller.streamTask(t.id) })
                    }
                    actions.addFlow(action(ctx, "暂停", Ui.TXT_SUB) { controller.pause(t.id) })
                }
                DownloadTask.Status.PAUSED, DownloadTask.Status.QUEUED -> {
                    if (streamable) {
                        actions.addFlow(action(ctx, "边下边播", Ui.ORANGE, primary = true) { controller.streamTask(t.id) })
                    }
                    actions.addFlow(action(ctx, "继续", Ui.BLUE, primary = true) { controller.resume(t.id) })
                }
                DownloadTask.Status.COMPLETED -> {
                    actions.addFlow(action(
                        ctx,
                        if (MediaTypes.isMediaName(t.fileName)) "播放" else "打开",
                        Ui.BLUE,
                        primary = true
                    ) { controller.openFile(t.savePath) })
                    val available = controller.fileAvailable(t)
                    if (MediaTypes.isVideoName(t.fileName) && available) {
                        actions.addFlow(action(ctx, "投屏", Ui.GREEN) { controller.castTask(t.savePath) })
                    }
                    if (available) {
                        actions.addFlow(action(ctx, "校验哈希", Ui.TXT_SUB) {
                            com.swift.download.HashDialog.show(ctx, java.io.File(t.savePath))
                        })
                    }
                    actions.addFlow(action(ctx, "重新下载", Ui.TXT_SUB) { confirmRestart(t) })
                }
                DownloadTask.Status.FAILED -> {
                    actions.addFlow(action(ctx, "重下", Ui.BLUE, primary = true) { confirmRestart(t) })
                    // v2.8 模块一：HTTP 失败（403/Referer）提供单线程重试入口
                    if (t.isHttp) {
                        actions.addFlow(action(ctx, "单线程重试", Ui.ORANGE) { controller.restartSingleThread(t.id) })
                    }
                    // 失效直链：让用户粘贴新地址，原地替换并从头重下
                    actions.addFlow(action(ctx, "刷新链接", Ui.TXT_SUB) { showRefreshDialog(t) })
                }
            }
            if (!t.isBt && st != DownloadTask.Status.COMPLETED) {
                // v2.5：已限速的任务在按钮上标出数值，用户一眼可辨
                val limit = taskLimits[t.id] ?: 0L
                val label = if (limit > 0) "限速 ${limit / 1024 / 1024}M" else "限速"
                actions.addFlow(action(ctx, label, Ui.TXT_SUB) { showTaskLimitDialog(t) })
            }
            // v2.6.0：BT 选文件 / 顺序下载 / 就绪即播（运行中或暂停时可调）
            if (t.isBt && st != DownloadTask.Status.COMPLETED && st != DownloadTask.Status.FAILED) {
                actions.addFlow(action(ctx, "选择文件", Ui.TXT_SUB) { showBtFileDialog(t) })
                val sequential = controller.isBtSequential(t.id)
                actions.addFlow(action(ctx, if (sequential) "顺序✓" else "顺序下载", Ui.TXT_SUB) {
                    if (controller.setBtSequential(t.id, !sequential)) {
                        Ui.toast(ctx, if (sequential) "已关闭顺序下载" else "已开启顺序下载")
                    } else Ui.toast(ctx, "种子尚未就绪，请稍后再试")
                })
                val ready = controller.btReadyVideoPath(t.id)
                if (ready != null) {
                    actions.addFlow(action(ctx, "预览", Ui.GREEN) { controller.openFile(ready) })
                }
            }
            actions.addFlow(action(ctx, "删除", Ui.RED) { confirmDelete(t) })
            card.addView(actions, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 8f) })
        }

        /** v2.6.0：种子内文件勾选弹窗，未勾选的文件设为 IGNORE 不再下载 */
        private fun showBtFileDialog(t: com.swift.download.DownloadTask) {
            val ctx = card.context
            val files = controller.btFiles(t.id)
            if (files.isEmpty()) {
                Ui.toast(ctx, "种子元数据尚未到达，请稍后再试")
                return
            }
            val checked = BooleanArray(files.size) { files[it].wanted }
            val names = Array(files.size) { i ->
                "${files[i].name}  ${Format.bytes(files[i].size)}"
            }
            AlertDialog.Builder(ctx)
                .setTitle("选择文件 · ${t.fileName}")
                .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("应用") { _, _ ->
                    val selected = IntArray(files.size) { i -> if (checked[i]) files[i].index else -1 }
                        .filter { it >= 0 }.toIntArray()
                    if (selected.isEmpty()) {
                        Ui.toast(ctx, "请至少选择一个文件")
                    } else if (controller.setBtFileSelection(t.id, selected)) {
                        Ui.toast(ctx, "已选择 ${selected.size} 个文件")
                    } else Ui.toast(ctx, "设置失败，请稍后重试")
                }
                .setNegativeButton("取消", null)
                .show()
        }

        private fun statusColor(st: DownloadTask.Status): Int = when (st) {
            DownloadTask.Status.COMPLETED -> Ui.GREEN
            DownloadTask.Status.FAILED -> Ui.RED
            DownloadTask.Status.RUNNING -> Ui.BLUE
            else -> Ui.TXT_SUB
        }

        private fun confirmDelete(t: DownloadTask) {
            val ctx = card.context
            AlertDialog.Builder(ctx)
                .setTitle("删除任务")
                .setMessage("确定删除「${t.fileName}」及其下载文件吗？此操作不可恢复。")
                .setPositiveButton("删除") { _, _ -> controller.delete(t.id, true) }
                .setNeutralButton("仅删任务") { _, _ -> controller.delete(t.id, false) }
                .setNegativeButton("取消", null)
                .show()
        }

        private fun showRefreshDialog(t: DownloadTask) {
            val ctx = card.context
            val et = Ui.singleLineEt(ctx, "粘贴新的 http/https 直链").apply { setText(t.url) }
            // v2.7 Stage1.1：默认勾选保留进度；仅当新链接大小一致且支持 Range 时生效
            val keep = android.widget.CheckBox(ctx).apply {
                text = "保留已下载进度继续（文件大小需一致）"
                isChecked = true
                textSize = 13f
                setTextColor(Ui.TXT)
            }
            val wrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 8f), Ui.dp(ctx, 20f), 0)
                addView(et, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(keep, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Ui.dp(ctx, 8f) })
                addView(Ui.text(ctx, "勾选时先探测新链接，大小一致且支持分段才续传，否则自动清空重下。", 11f, Ui.TXT_SUB),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = Ui.dp(ctx, 6f) })
            }
            AlertDialog.Builder(ctx)
                .setTitle("刷新链接 · ${t.fileName}")
                .setView(wrap)
                .setPositiveButton("保存") { _, _ ->
                    controller.refreshLinkKeepProgress(t.id, et.text.toString(), keep.isChecked) { ok, msg ->
                        Ui.toast(ctx, if (msg.isNotBlank()) msg else if (ok) "已刷新链接" else "请输入 http/https 开头的直链")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        /**
         * v2.7 Stage1.4：任务详情弹窗。展示完整元数据，URL 点击可一键复制。
         */
        private fun showInspector(t: DownloadTask) {
            val ctx = card.context
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(ctx, 18f), Ui.dp(ctx, 6f), Ui.dp(ctx, 18f), 0)
            }
            fun row(label: String, value: String, copyable: Boolean = false) {
                val line = Ui.text(ctx, "$label：$value", 13f, Ui.TXT)
                if (copyable && value.isNotBlank()) {
                    line.setTextColor(Ui.BLUE)
                    line.setOnClickListener {
                        try {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
                            Ui.toast(ctx, "已复制$label")
                        } catch (e: Exception) { Ui.toast(ctx, "复制失败") }
                    }
                }
                box.addView(line, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Ui.dp(ctx, 6f) })
            }

            val pct = if (t.totalBytes > 0) "%.1f%%".format(t.downloadedBytes * 100.0 / t.totalBytes) else "—"
            val kind = when {
                t.isBt -> "BT 种子"
                t.isM3u8 -> "HLS 流媒体"
                else -> "多线程 HTTP"
            }
            val statusText = when (t.status) {
                DownloadTask.Status.QUEUED -> "等待${if (t.error.isNotBlank()) "（${t.error}）" else ""}"
                DownloadTask.Status.PROBING -> "准备就绪"
                DownloadTask.Status.RUNNING -> "下载中"
                DownloadTask.Status.PAUSED -> "暂停"
                DownloadTask.Status.COMPLETED -> "已完成"
                DownloadTask.Status.FAILED -> "失败"
            }
            row("文件名", t.fileName.ifEmpty { t.hash })
            row("类型", kind)
            row("状态", statusText)
            if (t.error.isNotBlank()) row("错误/原因", t.error)
            row("原始 URL", t.url, copyable = true)
            if (t.finalUrl.isNotBlank() && t.finalUrl != t.url) row("最终 URL", t.finalUrl, copyable = true)
            row("保存路径", t.savePath, copyable = true)
            if (t.exportUri.isNotBlank()) row("导出 URI", t.exportUri, copyable = true)
            row("总大小", if (t.totalBytes >= 0) Format.bytes(t.totalBytes) else "未知")
            row("已下载", "${Format.bytes(t.downloadedBytes)}（$pct）")
            row("线程数", t.threads.toString())
            if (t.referer.isNotBlank()) row("Referer", t.referer, copyable = true)
            if (t.cookie.isNotBlank()) row("Cookie", t.cookie, copyable = true)
            // 引擎细节
            if (t.isHttp) {
                val seg = controller.httpSegmentCount(t.id)
                if (seg > 0) row("HTTP 分段", "$seg 段")
                val hint = controller.retryHint(t.id)
                if (hint.isNotBlank()) row("网络状态", hint)
            }
            if (t.isBt) {
                row("InfoHash", t.hash, copyable = true)
                controller.btSeedsPeers(t.id)?.let { (seeds, peers) ->
                    row("做种 / 节点", "$seeds / $peers")
                }
                // v2.8 模块一：BT 诊断细节（DHT 节点 / 状态 / tracker 错误）
                controller.btDiagnostics(t.id)?.let { d ->
                    row("DHT 节点", d.dhtNodes.toString())
                    row("Tracker", d.trackerCount.toString())
                    row("已连节点", d.peers.toString())
                    if (d.stateLabel.isNotBlank()) row("连接状态", d.stateLabel)
                    if (d.trackerError.isNotBlank()) row("Tracker", d.trackerError)
                }
            }

            AlertDialog.Builder(ctx)
                .setTitle("任务详情")
                .setView(Ui.verticalScroll(ctx, box))
                .setPositiveButton("关闭", null)
                .show()
        }

        private fun showTaskLimitDialog(t: DownloadTask) {
            val ctx = card.context
            // v2.5：回显当前已设置的限速值。
            // 原实现固定显示 "0"，用户看不到自己设过多少，重复设置时容易误操作。
            val current = taskLimits[t.id] ?: 0L
            val currentText = if (current > 0) (current / 1024 / 1024).toString() else "0"
            val et = Ui.singleLineEt(ctx, "0 = 跟随全局设置").apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(currentText)
            }
            val wrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 8f), Ui.dp(ctx, 20f), 0)
                addView(Ui.text(ctx, "单任务速度上限（MB/s）", 13f, Ui.TXT))
                addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8f) })
                if (current > 0) {
                    addView(Ui.text(ctx, "当前：${current / 1024 / 1024} MB/s", 11f, Ui.TXT_SUB),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            .apply { topMargin = Ui.dp(ctx, 4f) })
                }
            }
            AlertDialog.Builder(ctx)
                .setTitle("限速 · ${t.fileName}")
                .setView(wrap)
                .setPositiveButton("确定") { _, _ ->
                    val mb = et.text.toString().trim().toLongOrNull() ?: 0L
                    val bps = mb.coerceAtLeast(0) * 1024 * 1024
                    taskLimits[t.id] = bps
                    controller.setTaskLimit(t.id, bps)
                    Ui.toast(ctx, if (mb > 0) "已限速 $mb MB/s" else "已取消单任务限速")
                }
                .setNegativeButton("取消", null)
                .show()
        }

        private fun confirmRestart(t: DownloadTask) {
            val ctx = card.context
            AlertDialog.Builder(ctx)
                .setTitle("重新下载")
                .setMessage("将清空「${t.fileName}」的现有进度，从头开始下载。")
                .setPositiveButton("确定") { _, _ -> controller.restart(t.id) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /* 宿主每轮通知时更新页面 */
    fun accept(tasks: List<DownloadTask>, speeds: Map<Long, Long>) {
        lastTasks = tasks
        // v2.6.0：限速标记与弹窗回显以服务端持久化值为准，不再只依赖本次会话的本地缓存
        val limits = controller.taskLimits()
        val liveIds = tasks.map { it.id }.toHashSet()
        taskLimits.keys.removeAll { it !in liveIds }
        for (id in liveIds) {
            val bps = limits[id] ?: 0L
            if (bps > 0L) taskLimits[id] = bps else taskLimits.remove(id)
        }
        if (speeds.isNotEmpty()) {
            for ((id, s) in speeds) serverSpeeds[id] = s
        }
        onTasks(tasks)
    }
}
