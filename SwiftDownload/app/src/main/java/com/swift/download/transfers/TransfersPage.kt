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
        fun addHttpTask(url: String, fileName: String, threads: Int)
        fun pause(id: Long)
        fun resume(id: Long)
        fun delete(id: Long, withFiles: Boolean)
        fun restart(id: Long)
        fun openFile(path: String)
        /** v2.2：边下边播（本地服务器接管，边播边下） */
        fun streamTask(id: Long)
        /** v2.2：投屏播放已完成文件 */
        fun castTask(path: String)
        fun pauseAll()
        fun resumeAll()
        fun clearCompleted()
        /** 链接刷新：替换失效直链并从头重下 */
        fun refreshLink(id: Long, newUrl: String): Boolean
        /** 单任务限速，字节/秒；0 = 跟随全局 */
        fun setTaskLimit(id: Long, bps: Long)
    }

    private var threadsPref = 4
    private val tvTotal: TextView
    private val adapter = TaskAdapter()
    private val toolbar = FlowLayout(context)

    private val speedHints = HashMap<Long, Long>()

    /** v2.5：单任务限速值缓存（供弹窗回显与按钮标记） */
    private val taskLimits = HashMap<Long, Long>()

    init {
        setBackgroundColor(Ui.BG)
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 14f), Ui.dp(context, 10f), Ui.dp(context, 14f), Ui.dp(context, 4f))
        }
        val title = Ui.text(context, "传输", 20f, Ui.TXT, bold = true)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tvTotal = Ui.text(context, "", 13f, Ui.TXT_SUB)
        header.addView(tvTotal)
        val settings = TextView(context).apply {
            text = "设置"; textSize = 13f; setTextColor(Ui.BLUE)
            setPadding(Ui.dp(context, 10f), Ui.dp(context, 4f), Ui.dp(context, 2f), Ui.dp(context, 4f))
            setOnClickListener { showSettingsDialog() }
        }
        header.addView(settings)
        root.addView(header)

        // v2.2：批量操作工具栏（v2.3 改流式布局，窄屏自动换行不裁切）
        toolbar.setPadding(Ui.dp(context, 14f), Ui.dp(context, 4f), Ui.dp(context, 14f), Ui.dp(context, 2f))
        toolbar.addFlow(Ui.pillBtn(context, "全部暂停", Ui.TXT_SUB) { controller.pauseAll() })
        toolbar.addFlow(Ui.pillBtn(context, "全部继续", Ui.BLUE, filled = true) { controller.resumeAll() })
        toolbar.addFlow(Ui.pillBtn(context, "清除完成", Ui.TXT_SUB) { controller.clearCompleted() })
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val rv = Ui.recyclerView(context)
        rv.adapter = adapter
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        onTasks(emptyList())
    }

    /* ---------------- 数据刷新 ---------------- */

    fun onSpeed(id: Long, bps: Long) { speedHints[id] = bps }

    private fun DownloadTask.speedHint(): Long = speedHints[id] ?: 0L

    /**
     * v2.5：速度优先取服务端采样值（1 秒窗口差分）。
     * 本地差分自算仅作为服务端未提供时的兜底 —— 否则任务刚启动时首帧恒为 0 B/s。
     */
    private val lastBytes = HashMap<Long, Long>()
    private val lastTs = HashMap<Long, Long>()
    private val serverSpeeds = HashMap<Long, Long>()

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

    fun onTasks(tasks: List<DownloadTask>) {
        computeSpeeds(tasks)
        val running = tasks.filter { it.status == DownloadTask.Status.RUNNING }
        val totalSpeed = running.sumOf { it.speedHint() }
        tvTotal.text = buildString {
            append("${tasks.size} 个任务")
            if (totalSpeed > 0) append(" · ").append(Format.speed(totalSpeed))
        }
        toolbar.visibility = if (tasks.isEmpty()) View.GONE else View.VISIBLE
        adapter.submit(buildList {
            adapter.pendingClipUrl?.let { add(Decor.ClipCard(it)) }
            add(Decor.NewTaskCard)
            if (tasks.isEmpty()) add(Decor.EmptyState)
            for (t in tasks) add(Decor.DecorTask(t))
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        checkClipboard()
    }

    /* ---------------- 剪贴板检测卡（v1.0） ---------------- */

    private var ignoredClipboardUrl: String? = null

    private fun checkClipboard() {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: return
            if ((text.startsWith("http://") || text.startsWith("https://")) && text != ignoredClipboardUrl) {
                adapter.pendingClipUrl = text
                onTasks(lastTasks)
            }
        } catch (e: Exception) {
            // 忽略
        }
    }

    private var lastTasks: List<DownloadTask> = emptyList()
    private val lastTasksRef get() = lastTasks

    /* ---------------- 对话框 ---------------- */

    private fun showNewTaskDialog(prefill: String = "") {
        val ctx = context
        val et = Ui.singleLineEt(ctx, "粘贴 http/https 下载直链").apply { setText(prefill) }
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 8f), Ui.dp(ctx, 20f), 0)
            addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Ui.text(ctx, "支持多线程加速与断点续传，文件名自动识别。", 12f, Ui.TXT_SUB),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8f) })
        }
        AlertDialog.Builder(ctx)
            .setTitle("新建下载任务")
            .setView(wrap)
            .setPositiveButton("开始下载") { _, _ ->
                val url = et.text.toString().trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    controller.addHttpTask(url, "", threadsPref)
                    Ui.toast(ctx, "任务已添加：${com.swift.download.nameFromUrl(url)}")
                    adapter.pendingClipUrl = null
                } else {
                    Ui.toast(ctx, "请输入 http/https 开头的直链")
                }
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
        data object EmptyState : Decor
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
            Decor.EmptyState -> 2
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
                Decor.EmptyState -> bindEmpty()
                is Decor.DecorTask -> bindTask(d.task)
            }
        }

        private fun bindEmpty() {
            val ctx = card.context
            card.removeAllViews()
            val tv = Ui.text(ctx, "暂无下载任务\n\n复制一个下载直链，或点上方按钮添加", 13f, Ui.TXT_SUB)
            tv.gravity = Gravity.CENTER
            card.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 30f); bottomMargin = Ui.dp(ctx, 30f)
            })
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
            val download = action(ctx, "立即下载", Ui.BLUE, primary = true) {
                controller.addHttpTask(url, "", threadsPref)
                adapter.pendingClipUrl = null
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
            Ui.pressable(ctx, this)
            setOnClickListener { onClick() }
        }

        @SuppressLint("SetTextI18n")
        private fun bindTask(t: DownloadTask) {
            val ctx = card.context
            card.removeAllViews()
            val st = t.status

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
                // v2.5：排队原因（等待 Wi-Fi / 等待时段）单独展示，
                // 不再与失败原因混用同一个红色提示
                if (st == DownloadTask.Status.QUEUED && t.error.isNotEmpty()) {
                    append(" · ").append(t.error)
                }
            }
            card.addView(Ui.text(ctx, statusText, 13f, statusColor(st)),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = Ui.dp(ctx, 6f) })

            // 体积行（v2.2：带百分比）
            val pct = if (t.totalBytes > 0) " · %.1f%%".format(t.downloadedBytes * 100.0 / t.totalBytes) else ""
            val vol = if (t.totalBytes >= 0) "${Format.bytes(t.downloadedBytes)} / ${Format.bytes(t.totalBytes)}$pct" else Format.bytes(t.downloadedBytes)
            card.addView(Ui.text(ctx, vol, 12f, Ui.TXT_SUB),
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
                    if (MediaTypes.isVideoName(t.fileName) && java.io.File(t.savePath).isFile) {
                        actions.addFlow(action(ctx, "投屏", Ui.GREEN) { controller.castTask(t.savePath) })
                    }
                    actions.addFlow(action(ctx, "重新下载", Ui.TXT_SUB) { confirmRestart(t) })
                }
                DownloadTask.Status.FAILED -> {
                    actions.addFlow(action(ctx, "重下", Ui.BLUE, primary = true) { confirmRestart(t) })
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
            actions.addFlow(action(ctx, "删除", Ui.RED) { confirmDelete(t) })
            card.addView(actions, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 8f) })
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
            val wrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 8f), Ui.dp(ctx, 20f), 0)
                addView(et, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(Ui.text(ctx, "将替换原链接并清空进度，从头开始下载。", 11f, Ui.TXT_SUB),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = Ui.dp(ctx, 6f) })
            }
            AlertDialog.Builder(ctx)
                .setTitle("刷新链接 · ${t.fileName}")
                .setView(wrap)
                .setPositiveButton("保存并重下") { _, _ ->
                    if (!controller.refreshLink(t.id, et.text.toString())) {
                        Ui.toast(ctx, "请输入 http/https 开头的直链")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        private fun showTaskLimitDialog(t: DownloadTask) {
            val ctx = card.context
            // v2.5：回显当前已设置的限速值。
            // 原实现固定显示 "0"，用户看不到自己设过多少，重复设置时容易误操作。
            val current = taskLimits[t.id] ?: 0L
            val currentText = if (current > 0) (current / 1024 / 1024).toString() else "0"
            val et = Ui.singleLineEt(ctx, "0 = 跟随全局设置").apply { setText(currentText) }
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
        if (speeds.isNotEmpty()) {
            for ((id, s) in speeds) serverSpeeds[id] = s
        }
        onTasks(tasks)
    }
}
