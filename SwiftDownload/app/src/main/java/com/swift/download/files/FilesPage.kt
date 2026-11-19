package com.swift.download.files

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.swift.download.Format
import com.swift.download.MediaTypes
import com.swift.download.OpenFileReceiver
import com.swift.download.StorageRoots
import com.swift.download.ui.Ui
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.0 新增：文件页 —— 浏览公共下载目录（Download/ 及 BT 子目录）。
 * v2.2 升级：
 *  - 排序（时间/名称/大小 × 升降，记忆选择）
 *  - 文件类型彩色图标 + 体积合计
 *  - 长按菜单：打开 / 分享 / 投屏播放（视频）/ 重命名 / 删除
 */
@SuppressLint("ViewConstructor")
class FilesPage(
    context: Context,
    private val onCast: (path: String) -> Unit = {}
) : FrameLayout(context) {

    /**
     * v2.5：扫描根目录。
     * 优先扫描实际使用的下载根目录（与下载任务落盘位置一致），
     * 同时也扫公共下载目录 —— 用户可能同时存在历史文件与降级后的新文件。
     */
    private val rootDir: File = StorageRoots.downloadRoot(context)
    private val extraRoots: List<File> =
        listOf(StorageRoots.publicDownloads()).filter { it.absolutePath != rootDir.absolutePath }
    private val adapter = FileAdapter()
    /** 空状态与列表互斥：有文件时显示 rv，无文件时显示 emptyView */
    private val rv: RecyclerView
    private val emptyView: View
    private val tvPath: TextView
    private val tvSort: TextView
    private var sortMode = FileSorts.Mode.TIME_DESC
    private val prefs = context.getSharedPreferences("swift_files", Context.MODE_PRIVATE)

    init {
        sortMode = FileSorts.Mode.fromName(prefs.getString("sort", null))
        setBackgroundColor(Ui.BG)
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 14f), Ui.dp(context, 10f), Ui.dp(context, 14f), Ui.dp(context, 4f))
        }
        val title = Ui.text(context, "文件", 20f, Ui.TXT, bold = true)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tvSort = Ui.text(context, "", 13f, Ui.BLUE)
        tvSort.setOnClickListener { showSortDialog() }
        header.addView(tvSort)
        root.addView(header)

        tvPath = Ui.text(context, "", 12f, Ui.TXT_SUB)
        root.addView(tvPath, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(context, 14f), 0, Ui.dp(context, 14f), Ui.dp(context, 4f))
        })

        rv = Ui.recyclerView(context)
        rv.adapter = adapter
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        emptyView = Ui.emptyState(context, "件", "还没有文件", "下载完成的文件会出现在这里")
        emptyView.visibility = View.GONE
        root.addView(emptyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); refresh() }

    /** 文件扫描放后台线程，避免文件多时切到「文件」页卡顿；用序号丢弃过期结果 */
    @Volatile private var refreshSeq = 0

    fun refresh() {
        val seq = ++refreshSeq
        Thread {
            val seen = HashSet<String>()
            val files = ArrayList<File>()
            for (root in listOf(rootDir) + extraRoots) {
                for (f in listAll(root, 0)) {
                    if (seen.add(f.absolutePath)) files.add(f)
                }
            }
            val sorted = files.sortedWith(FileSorts.comparator(sortMode))
            val total = sorted.sumOf { it.length() }
            post {
                if (seq != refreshSeq) return@post
                adapter.submit(sorted)
                val empty = sorted.isEmpty()
                rv.visibility = if (empty) View.GONE else View.VISIBLE
                emptyView.visibility = if (empty) View.VISIBLE else View.GONE
                tvSort.text = "排序 · ${sortMode.label} ▾"
                tvPath.text = "下载目录及子目录 · ${sorted.size} 个文件 · 合计 ${Format.bytes(total)}"
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 递归扫描下载目录。
     * v2.5：原实现固定 depth=0 且只在 depth<2 时递归 —— 开启"文件自动分类"后
     * 文件落在 Download/视频/xxx/ 这类更深层目录里，文件页就看不到它们。
     * 现在按固定最大深度扫描，保证分类目录下的文件也能列出。
     */
    private fun listAll(dir: File, depth: Int): List<File> {
        val out = ArrayList<File>()
        val subs = dir.listFiles() ?: return out
        for (f in subs) {
            if (f.isDirectory) {
                if (depth < MAX_DEPTH) out.addAll(listAll(f, depth + 1))
            } else if (!f.name.endsWith(".part") && !f.name.endsWith(".swiftprog") &&
                !f.name.endsWith(".m3u8state") && !f.name.endsWith(".tmp")
            ) {
                out.add(f)
            }
        }
        return out
    }

    /* ---------------- 排序选择 ---------------- */

    private fun showSortDialog() {
        val labels = FileSorts.Mode.entries.map { it.label }.toTypedArray()
        android.app.AlertDialog.Builder(context)
            .setTitle("文件排序")
            .setSingleChoiceItems(labels, sortMode.ordinal) { d, which ->
                sortMode = FileSorts.Mode.entries[which]
                prefs.edit().putString("sort", sortMode.name).apply()
                refresh()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /* ---------------- 适配器 ---------------- */

    private inner class FileAdapter : RecyclerView.Adapter<FileVh>() {
        private val items = ArrayList<File>()
        fun submit(list: List<File>) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = items.size
                override fun getNewListSize(): Int = list.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    items[oldPos].absolutePath == list[newPos].absolutePath
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                    items[oldPos].absolutePath == list[newPos].absolutePath &&
                        items[oldPos].length() == list[newPos].length() &&
                        items[oldPos].lastModified() == list[newPos].lastModified()
            })
            items.clear(); items.addAll(list)
            diff.dispatchUpdatesTo(this)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileVh {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val card = Ui.card(parent.context)
            row.addView(card)
            return FileVh(row, card)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(h: FileVh, pos: Int) { h.bind(items[pos]) }
    }

    private inner class FileVh(row: View, private val card: LinearLayout) : RecyclerView.ViewHolder(row) {
        @SuppressLint("SetTextI18n")
        fun bind(f: File) {
            val ctx = card.context
            card.removeAllViews()
            Ui.pressable(card)

            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(Ui.iconBadge(ctx, glyphOf(f.name), colorOf(f.name)))

            val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val name = Ui.text(ctx, f.name, 15f, Ui.TXT, bold = true)
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            mid.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            mid.addView(Ui.text(ctx, "${typeLabel(f)} · ${Format.bytes(f.length())} · ${df.format(Date(f.lastModified()))}", 12f, Ui.TXT_SUB),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Ui.dp(ctx, 2f)
                })
            line.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(ctx, 10f)
            })
            card.addView(line)

            card.setOnClickListener { open(f) }
            card.setOnLongClickListener { showMenu(f); true }
        }

        private fun open(f: File) {
            context.sendBroadcast(OpenFileReceiver.intentOf(f.absolutePath).setPackage(context.packageName))
        }

        private fun showMenu(f: File) {
            val ctx = card.context
            val opts = ArrayList<Pair<String, () -> Unit>>()
            opts.add("打开" to { open(f) })
            opts.add("分享" to { share(f) })
            if (MediaTypes.isVideoName(f.name)) opts.add("投屏播放" to { onCast(f.absolutePath) })
            // v2.7 Stage4.3：文本快速查看 / ZIP 安全解压
            if (MediaTypes.isTextName(f.name)) opts.add("快速查看" to { showTextViewer(f) })
            if (MediaTypes.extOf(f.name) == "zip") opts.add("解压到同名文件夹" to { extractZip(f) })
            opts.add("重命名" to { rename(f) })
            opts.add("校验哈希 / 详情" to { com.swift.download.HashDialog.show(ctx, f) })
            opts.add("删除" to {
                android.app.AlertDialog.Builder(ctx)
                    .setMessage("删除「${f.name}」？此操作不可恢复。")
                    .setPositiveButton("删除") { _, _ -> f.delete(); refresh() }
                    .setNegativeButton("取消", null)
                    .show()
            })
            android.app.AlertDialog.Builder(ctx)
                .setTitle(f.name)
                .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
                .setNegativeButton("取消", null)
                .show()
        }

        private fun share(f: File) {
            try {
                val uri: Uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", f)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = MediaTypes.mimeOf(f.name)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "分享 ${f.name}"))
            } catch (e: Exception) {
                Ui.toast(context, "无法分享此文件")
            }
        }

        /** v2.7 Stage4.3：轻量文本查看弹窗（限前 512KB） */
        private fun showTextViewer(f: File) {
            val ctx = card.context
            val text = try {
                // 手动限长读取（readNBytes 需 API 33，minSdk 24 不可用）
                val out = java.io.ByteArrayOutputStream()
                f.inputStream().use { input ->
                    val buf = ByteArray(8192)
                    var remaining = MediaTypes.TEXT_PREVIEW_MAX_BYTES
                    while (remaining > 0) {
                        val n = input.read(buf, 0, minOf(buf.size, remaining))
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        remaining -= n
                    }
                }
                val content = String(out.toByteArray(), Charsets.UTF_8)
                val truncated = f.length() > MediaTypes.TEXT_PREVIEW_MAX_BYTES
                content + if (truncated) "\n\n……（文件较大，仅显示前 ${Format.bytes(MediaTypes.TEXT_PREVIEW_MAX_BYTES.toLong())}）" else ""
            } catch (e: Exception) {
                "无法读取文件：${e.message}"
            }
            val tv = TextView(ctx).apply {
                this.text = text
                textSize = 13f
                setTextColor(Ui.TXT)
                setTextIsSelectable(true)
            }
            android.app.AlertDialog.Builder(ctx)
                .setTitle(f.name)
                .setView(Ui.verticalScroll(ctx, tv))
                .setPositiveButton("关闭", null)
                .show()
        }

        /** v2.7 Stage4.3：后台安全解压到同名文件夹，完成提示并刷新 */
        private fun extractZip(f: File) {
            val ctx = card.context
            val dest = File(f.parentFile, f.nameWithoutExtension)
            if (dest.exists()) {
                android.app.AlertDialog.Builder(ctx)
                    .setMessage("已存在同名文件夹「${dest.name}」，解压会覆盖同名文件。继续？")
                    .setPositiveButton("继续") { _, _ -> doExtract(ctx, f, dest) }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                doExtract(ctx, f, dest)
            }
        }

        private fun doExtract(ctx: android.content.Context, f: File, dest: File) {
            Ui.toast(ctx, "正在解压 ${f.name}…")
            Thread {
                val result = ZipExtract.extract(f, dest)
                post {
                    if (result.failed != null) {
                        Ui.toast(ctx, "解压失败：${result.failed}")
                    } else {
                        Ui.toast(ctx, "解压完成：${result.files} 个文件")
                        refresh()
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        private fun rename(f: File) {
            val ctx = card.context
            val et = Ui.singleLineEt(ctx, f.name).apply { setSelection(text.length) }
            val wrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 8f), Ui.dp(ctx, 20f), 0)
                addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            android.app.AlertDialog.Builder(ctx)
                .setTitle("重命名")
                .setView(wrap)
                .setPositiveButton("确定") { _, _ ->
                    val n = et.text.toString().trim()
                    if (n.isEmpty() || n == f.name) return@setPositiveButton
                    // v2.5.2 / v2.6.0 命名校验抽成纯函数（renameError），JVM 单测覆盖
                    val err = renameError(n)
                    if (err != null) {
                        Ui.toast(ctx, err)
                        return@setPositiveButton
                    }
                    val target = File(f.parentFile, n)
                    if (target.exists()) {
                        Ui.toast(ctx, "同名文件已存在")
                    } else if (f.renameTo(target)) {
                        syncRename(ctx, f.absolutePath, target.absolutePath, n)
                    } else {
                        // v2.7 Stage0：Android 11+ 上 MediaStore 导出的公共文件常无法用
                        // File.renameTo 改名（无公共目录写权限），回退到 ContentResolver.update(DISPLAY_NAME)。
                        val exported = try {
                            com.swift.download.DownloadDatabase.get(ctx).all()
                                .firstOrNull { it.savePath == f.absolutePath }
                        } catch (e: Exception) { null }
                        if (exported != null && isContentUri(exported.exportUri) &&
                            renameMediaStore(ctx, exported.exportUri, n)
                        ) {
                            syncRename(ctx, f.absolutePath, target.absolutePath, n)
                        } else {
                            Ui.toast(ctx, "重命名失败")
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    companion object {
        /** 扫描最大深度：Download/ → 分类子目录 → BT/种子目录 → 内容目录 */
        private const val MAX_DEPTH = 4

        /** 重命名命名校验（纯函数，JVM 单测覆盖）；null 表示合法 */
        fun renameError(name: String): String? = when {
            name.contains('/') || name.contains('\\') || name == "." || name == ".." ->
                "文件名不能包含路径分隔符"
            name.startsWith('.') -> "文件名不能以 . 开头（隐藏文件）"
            else -> null
        }

        /** 是否 MediaStore content URI（v2.7 Stage0） */
        fun isContentUri(uri: String): Boolean =
            uri.startsWith("content://", ignoreCase = true)

        /** 通过 MediaStore 改 DISPLAY_NAME；成功返回 true */
        private fun renameMediaStore(ctx: Context, uri: String, name: String): Boolean = try {
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            }
            ctx.contentResolver.update(android.net.Uri.parse(uri), cv, null, null)
            true
        } catch (e: Exception) {
            android.util.Log.w("FilesPage", "renameMediaStore", e)
            false
        }

        /** 同步数据库里的落盘路径，否则任务仍指向旧文件名 */
        private fun syncRename(ctx: Context, oldPath: String, newPath: String, newName: String) {
            try {
                com.swift.download.DownloadDatabase.get(ctx).renameSavePath(oldPath, newPath)
            } catch (e: Exception) {
                android.util.Log.w("FilesPage", "renameSavePath", e)
            }
            Ui.toast(ctx, "已重命名为 $newName")
        }

        /** 按扩展名给图标字形（存纯函数供单测参照：主要逻辑在 glyphOf/colorOf） */
        fun glyphOf(name: String): String = when (com.swift.download.MediaTypes.extOf(name)) {
            in MediaTypes.VIDEO_EXTS -> "▶"
            in MediaTypes.AUDIO_EXTS -> "♪"
            "png", "jpg", "jpeg", "gif", "webp", "bmp" -> "图"
            "pdf" -> "PDF"
            "epub", "txt", "mobi" -> "文"
            "zip", "rar", "7z", "tar", "gz" -> "压"
            "apk" -> "APK"
            "torrent" -> "BT"
            else -> "文"
        }

        fun colorOf(name: String): Int = when (com.swift.download.MediaTypes.extOf(name)) {
            in MediaTypes.VIDEO_EXTS -> Ui.ORANGE
            in MediaTypes.AUDIO_EXTS -> Ui.GREEN
            "png", "jpg", "jpeg", "gif", "webp", "bmp" -> 0xFF7C3AED.toInt()
            "pdf", "epub", "txt", "mobi" -> Ui.TXT_SUB
            "zip", "rar", "7z", "tar", "gz" -> 0xFFD97706.toInt()
            "apk" -> Ui.BLUE
            "torrent" -> Ui.GREEN
            else -> Ui.colorOf(name)
        }

        fun typeLabel(f: File): String = when (com.swift.download.MediaTypes.extOf(f.name)) {
            in MediaTypes.VIDEO_EXTS -> "视频"
            in MediaTypes.AUDIO_EXTS -> "音频"
            "png", "jpg", "jpeg", "gif", "webp", "bmp" -> "图片"
            "pdf" -> "PDF 文档"
            "epub", "txt", "mobi" -> "电子书"
            "zip", "rar", "7z", "tar", "gz" -> "压缩包"
            "apk" -> "安装包"
            "torrent" -> "种子"
            else -> "文件"
        }
    }
}
