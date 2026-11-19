package com.swift.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.swift.download.bt.TorrentEngine
import com.swift.download.player.Backing
import com.swift.download.player.ContentUriBacking
import com.swift.download.player.FileBacking
import com.swift.download.player.M3u8Job
import com.swift.download.player.M3u8
import com.swift.download.player.StreamServer
import com.swift.download.player.TaskBacking
import com.swift.download.settings.DownloadRules
import com.swift.download.settings.DownloadSettings
import com.swift.download.settings.NetworkRule
import com.swift.download.settings.RateLimiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/* ---------- DownloadServiceKt：顶层常量（v1.0 语义保持 + v2.0 新通道命名） ---------- */
const val CHANNEL_PROGRESS = "swift_download_progress"
const val CHANNEL_DONE = "swift_download_done"
const val NOTIF_FOREGROUND_ID = 1
/** v2.5：任务通知 ID 段起点。与前台通知 ID(1) 隔离，避免相互覆盖 */
private const val NOTIF_TASK_BASE = 1000
private const val TICK_MS = 500L
private const val DB_WRITE_MS = 1000L

/**
 * v1.0 行为保持：前台服务 + DownloadBinder + 双通知通道（进度 / 完成提醒）。
 * v2.0：统一接管 HTTP（DownloadEngine）与 BT（TorrentEngine）两类任务，同一 ticker 轮询回写。
 *
 * v2.5 修复要点：
 *  1. UI 回调统一切回主线程 —— 此前 ticker/引擎/BT 回调线程直接刷新 RecyclerView，
 *     会在下载过程中触发 "Called from the invalid thread" 崩溃。
 *  2. 任务通知 ID 与前台通知 ID 分离 —— 原先任务 id=1 会覆盖前台通知，
 *     任务完成时又会把前台通知 cancel 掉。
 *  3. 重启恢复补齐 m3u8 类型，并复用原始磁力链接（保留 tracker）。
 *  4. 边下边播改为异步等待探测就绪，避免主线程阻塞。
 */

/** v2.8 模块一：BT 诊断信息（纯数据，跨 Binder 传递，不暴露 jlibtorrent 类型） */
data class BtDiag(
    val dhtNodes: Long,
    val trackerCount: Int,
    val stateLabel: String,
    val trackerError: String,
    val seeds: Int,
    val peers: Int
)

class DownloadService : Service() {

    inner class DownloadBinder : Binder() {
        val service: DownloadService get() = this@DownloadService

        fun setUiListener(l: UiListener?) = this@DownloadService.setUiListener(l)

        fun addHttpTask(url: String, fileName: String, threads: Int) =
            this@DownloadService.addHttpTask(url, fileName, threads)

        /** v2.5：带 Referer 的直链（嗅探到的防盗链视频需要） */
        fun addHttpTaskWithReferer(
            url: String, fileName: String, threads: Int, referer: String,
            cookie: String = "", userAgent: String = ""
        ) = this@DownloadService.addHttpTask(url, fileName, threads, referer, cookie, userAgent)

        fun addMagnetTask(magnetUri: String) = this@DownloadService.addMagnetTask(magnetUri)

        /** 嗅探到的视频：master 地址 + 用户选定变体 + 来源页 Referer */
        fun addVideoTask(
            masterUrl: String, fileName: String, variantUrl: String, referer: String,
            cookie: String = "", userAgent: String = ""
        ) = this@DownloadService.addVideoTask(masterUrl, fileName, variantUrl, referer, cookie, userAgent)

        fun addTorrentFileTask(path: String) = this@DownloadService.addTorrentFileTask(path)

        fun pause(id: Long) = this@DownloadService.pause(id)
        fun resume(id: Long) = this@DownloadService.resume(id)
        fun delete(id: Long, withFiles: Boolean) = this@DownloadService.delete(id, withFiles)
        fun tasksSnapshot(): List<DownloadTask> = this@DownloadService.tasksSnapshot()

        /** v2.5：异步边下边播（内部等待探测就绪，不阻塞调用线程） */
        fun startStreamAsync(id: Long, cb: (String?) -> Unit) =
            this@DownloadService.startStreamAsync(id, cb)

        /** v2.2：投屏/本地播已完成文件 → 返回局域网（或本机）地址 */
        fun serveFile(path: String): String? = this@DownloadService.serveFile(path)

        fun stopServeFile() = this@DownloadService.stopServeFile()

        fun pauseAll() = this@DownloadService.pauseAll()

        fun resumeAll() = this@DownloadService.resumeAll()

        fun clearCompleted() = this@DownloadService.clearCompleted()

        /** 链接刷新：替换失效直链并从头重下 */
        fun refreshLink(id: Long, newUrl: String): Boolean = this@DownloadService.refreshLink(id, newUrl)
        /** v2.7 Stage1.1：刷新链接时是否保留断点（异步探测后回调结果） */
        fun refreshLinkKeepProgress(
            id: Long, newUrl: String, keep: Boolean, cb: (Boolean, String) -> Unit
        ) = this@DownloadService.refreshLinkKeepProgress(id, newUrl, keep, cb)

        /** v2.7 Stage1.2：网络重连提示文案；空串表示未在重试 */
        fun retryHint(id: Long): String = this@DownloadService.retryHints[id].orEmpty()
        /** v2.7 Stage1.3：下载目录剩余空间文案 */
        fun freeSpaceLabel(): String =
            com.swift.download.StorageRoots.freeSpaceLabel(com.swift.download.StorageRoots.downloadRoot(this@DownloadService))

        fun restartTask(id: Long): Long? = this@DownloadService.restartTask(id)
        /** v2.8 模块一：以指定线程数重建任务（单线程重试受限站点） */
        fun restartTaskWithThreads(id: Long, threads: Int): Long? = this@DownloadService.restartTask(id, threads)

        fun setTaskLimitBps(id: Long, bps: Long) = this@DownloadService.setTaskLimitBps(id, bps)
        fun getSettings(): DownloadSettings = this@DownloadService.settings
        /** v2.7 Stage2.1：设置变更后把 BT 代理/限速应用到会话 */
        fun applyBtNetworkSettings() = this@DownloadService.applyBtNetworkSettings()

        /* v2.6.0：BT 选文件 / 顺序下载 / 就绪即播 */
        fun btFiles(id: Long): List<com.swift.download.bt.BtFile> =
            btHashes[id]?.let { com.swift.download.bt.TorrentEngine.get().fileList(it) } ?: emptyList()
        fun setBtFileSelection(id: Long, selected: IntArray): Boolean {
            val hash = btHashes[id] ?: return false
            return com.swift.download.bt.TorrentEngine.get()
                .setFilePriorities(hash, selected.toSet())
        }
        fun setBtSequential(id: Long, enabled: Boolean): Boolean {
            val hash = btHashes[id] ?: return false
            return com.swift.download.bt.TorrentEngine.get().setSequential(hash, enabled)
        }
        fun isBtSequential(id: Long): Boolean {
            val hash = btHashes[id] ?: return false
            return com.swift.download.bt.TorrentEngine.get().isSequential(hash)
        }
        fun btReadyVideoPath(id: Long): String? {
            val hash = btHashes[id] ?: return null
            return com.swift.download.bt.TorrentEngine.get().readyVideoPath(hash)
        }

        /* v2.7 Stage1.4：任务详情数据 */
        fun httpSegmentCount(id: Long): Int = httpJobs[id]?.snapshot()?.size ?: -1
        fun btSeedsPeers(id: Long): Pair<Int, Int>? {
            val hash = btHashes[id] ?: return null
            val p = com.swift.download.bt.TorrentEngine.get().progressOf(hash) ?: return null
            return p.seeds to p.peers
        }

        /** v2.8 模块一：BT 实时诊断（DHT 节点 / 状态标签 / tracker 错误），供任务卡与详情展示 */
        fun btDiagnostics(id: Long): BtDiag? {
            val hash = btHashes[id] ?: return null
            val p = com.swift.download.bt.TorrentEngine.get().progressOf(hash) ?: return null
            return BtDiag(
                dhtNodes = p.dhtNodes,
                trackerCount = p.trackerCount,
                stateLabel = p.stateLabel,
                trackerError = p.trackerError,
                seeds = p.seeds,
                peers = p.peers
            )
        }

        /* v2.6.0：局域网远程投递面板 */
        fun startLanRemote(): String? = this@DownloadService.startLanRemote()
        fun stopLanRemote() = this@DownloadService.stopLanRemote()
        fun isLanRemoteRunning(): Boolean = this@DownloadService.lanRemote != null
    }

    interface UiListener {
        fun onChanged(tasks: List<DownloadTask>)
        /** v2.5：附带服务端采样速度（避免 UI 端差分自算的抖动与首帧 0 B/s） */
        fun onChanged(tasks: List<DownloadTask>, speeds: Map<Long, Long>) {
            onChanged(tasks)
        }
    }

    private val binder = DownloadBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db by lazy { DownloadDatabase.get(this) }
    private val notifManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private val settings by lazy { DownloadSettings.get(this) }
    private val rateLimiter = RateLimiter()
    private val m3u8Jobs = ConcurrentHashMap<Long, M3u8Job>()
    /** v2.7 Stage1.2：HTTP 任务瞬断重试提示（如「网络重连 1/3」），不入库，仅 UI 展示 */
    private val retryHints = ConcurrentHashMap<Long, String>()
    /** m3u8 进度通知/落库节流时间戳（分片回调过于密集） */
    private val m3u8TickAt = ConcurrentHashMap<Long, Long>()
    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    private val httpJobs = ConcurrentHashMap<Long, DownloadEngine.DownloadJob>()
    private val btHashes = ConcurrentHashMap<Long, String>()
    /** v2.6.0：BT 完成收口防重（alert 回调与 ticker 都可能先到） */
    private val finishedBtIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val samplers = ConcurrentHashMap<Long, DownloadEngine.SpeedSampler>()
    private val tasks = ConcurrentHashMap<Long, DownloadTask>()
    private var uiListener: UiListener? = null

    /** v2.5：所有 UI 刷新统一切回主线程 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /* ---------------- v2.2：边下边播 / 投屏 ---------------- */

    private class StreamSession(
        val taskId: Long,
        val job: DownloadEngine.DownloadJob?,  // v2.7：BT 流会话没有 HTTP Job
        val server: StreamServer
    )

    @Volatile private var streamSession: StreamSession? = null
    @Volatile private var fileServer: StreamServer? = null
    /** v2.2：流会话空闲计时（播放器断开后多久恢复多线程） */
    private val idleSince = ConcurrentHashMap<Long, Long>()
    /** v2.2：投屏文件服务器空闲计时（无客户端 120 秒后回收） */
    @Volatile private var fileIdleSince = 0L
    /* v2.6.0：投屏保活锁 —— 锁屏后 Wi-Fi 休眠会让电视投屏断流 */
    @Volatile private var castWakeLock: android.os.PowerManager.WakeLock? = null
    @Volatile private var castWifiLock: android.net.wifi.WifiManager.WifiLock? = null
    /* v2.6.0：局域网远程投递服务器（默认关闭） */
    @Volatile private var lanRemote: com.swift.download.remote.LanRemoteServer? = null

    /**
     * 边下边播：暂停多线程引擎 → 本地服务器接管（盘上有则读盘，无则远端拉取并回写引擎）。
     * 播放器断开（空闲 8 秒）后自动恢复多线程补齐未完成分段。
     */
    private fun startStream(id: Long): String? {
        var t = tasks[id] ?: return null
        // 已有会话且是同一任务：直接复用地址
        streamSession?.let { if (it.taskId == id) return it.server.localUrl() }
        // 换任务：结束旧会话并恢复旧任务多线程
        endStream(resume = true)
        // v2.7 Stage2.2：BT 任务按需 piece 调度边下边播
        if (t.isBt) return startBtStream(id, t)
        // 暂停/排队态没有 Job：先恢复任务（探测在后台线程完成）
        if (httpJobs[id] == null) resume(id)
        val job = httpJobs[id] ?: return null
        // 等待探测就绪，拿到真实文件大小与分段信息
        if (!job.awaitReady(PROBE_WAIT_MS)) return null
        t = tasks[id] ?: return null
        if (t.totalBytes <= 0) return null
        job.cancel() // 停引擎线程（保留在 httpJobs 里：ticker 继续回写进度/DB/通知）
        val task = t
        val backing = TaskBacking(
            partPath = task.savePath + ".part",
            url = task.url,
            total = task.totalBytes,
            mime = MediaTypes.mimeOf(task.fileName),
            referer = task.referer,
            cookie = task.cookie,
            externalWrite = { offset, buf, len -> job.externalWrite(task, offset, buf, len) },
            segments = { job.snapshot() }
        )
        val server = StreamServer(backing)
        val port = server.start()
        streamSession = StreamSession(id, job, server)
        startForegroundCompat()
        idleSince.remove(id)
        Log.i(TAG, "stream started task=$id port=$port")
        return server.localUrl()
    }

    /**
     * v2.7 Stage2.2：BT 视频按需边下边播。选中已勾选的最大视频文件，用 TorrentBacking
     * 按需 setPieceDeadline 调度 piece，未就绪时短轮询等待后从落盘文件读。
     */
    private fun startBtStream(id: Long, t: DownloadTask): String? {
        val hash = btHashes[id] ?: return null
        val engine = TorrentEngine.get()
        val vf = engine.preferredVideoFile(hash) ?: return null
        val saveBase = try { engine.find(hash)?.savePath() } catch (e: Exception) { null }
            ?: File(t.savePath).parentFile?.absolutePath ?: return null
        val rel = vf.path.ifBlank { vf.name }
        val videoFile = listOf(File(saveBase, rel), File(saveBase, vf.name))
            .firstOrNull { it.isFile } ?: return null
        val backing = com.swift.download.bt.TorrentBacking(
            videoFile = videoFile,
            size = vf.size.coerceAtLeast(videoFile.length()),
            mime = MediaTypes.mimeOf(vf.name),
            onRequest = { offset, len ->
                engine.scheduleVideoRange(hash, vf.index, offset, len)
            }
        )
        backing.setReadyEndProvider { offset -> engine.readyEndInFile(hash, vf.index, offset) }
        val server = StreamServer(backing)
        val port = server.start()
        streamSession = StreamSession(id, null, server)
        startForegroundCompat()
        idleSince.remove(id)
        Log.i(TAG, "BT stream started task=$id file=${vf.name} port=$port")
        return server.localUrl()
    }

    /**
     * v2.5：异步边下边播。探测含网络请求，不能在主线程阻塞等待，
     * 因此整个准备过程放到 IO 线程，完成后回调（回调在主线程执行）。
     */
    fun startStreamAsync(id: Long, cb: (String?) -> Unit) {
        scope.launch {
            val url = startStream(id)
            mainHandler.post { cb(url) }
        }
    }

    /** 结束边下边播会话（resume=true 时恢复多线程补齐） */
    private fun endStream(resume: Boolean) {
        val s = streamSession ?: return
        streamSession = null
        s.server.stop()
        httpJobs.remove(s.taskId)
        idleSince.remove(s.taskId)
        if (resume) {
            tasks[s.taskId]?.let { t ->
                if (t.status == DownloadTask.Status.COMPLETED) return@let
                if (t.isBt) {
                    // v2.7 Stage2.2：BT 流结束恢复做种（resume torrent）
                    btHashes[s.taskId]?.let { TorrentEngine.get().resume(it) }
                } else startHttp(s.taskId)
            }
        }
        notifyUi()
    }

    /** 投屏/本地播已完成文件：本地文件服务器（支持 Range，供电视拖进度） */
    fun serveFile(path: String): String? {
        var f = File(path)
        // v2.6.0：已导出到 MediaStore 的文件优先零拷贝投屏 —— 不再把 2~4GB 电影
        // 整份复制进 cacheDir，改用 ParcelFileDescriptor 随机定位直接 Range 读。
        val exported = tasks.values.firstOrNull {
            (it.savePath == path || it.exportUri == path) && it.exportUri.isNotBlank()
        }
        val ip = com.swift.download.dlna.Dlna.lanIp()
        stopServeFile()
        val backing: Backing = when {
            exported != null -> {
                val uri = try { android.net.Uri.parse(exported.exportUri) } catch (e: Exception) { null }
                val size = if (exported.totalBytes > 0) exported.totalBytes else f.length()
                if (uri == null || size <= 0) return null
                ContentUriBacking(
                    openPfd = { try { contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null } },
                    size = size,
                    mime = MediaTypes.mimeOf(exported.fileName)
                )
            }
            f.isFile -> FileBacking(f, MediaTypes.mimeOf(f.name))
            else -> return null
        }
        val server = StreamServer(backing)
        server.start()
        fileServer = server
        acquireCastLocks()
        startForegroundCompat()
        return if (ip != null) server.lanUrl(ip) else server.localUrl()
    }

    /** v2.6.0：投屏期间持有 PARTIAL_WAKE_LOCK + WIFI_MODE_FULL_HIGH_PERF，锁屏不断流 */
    private fun acquireCastLocks() {
        releaseCastLocks()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            castWakeLock = pm?.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK, "$TAG:cast"
            )?.apply { setReferenceCounted(false); acquire(10 * 60 * 1000L) }
        } catch (e: Exception) { Log.w(TAG, "cast wake lock", e) }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            } else {
                @Suppress("DEPRECATION")
                android.net.wifi.WifiManager.WIFI_MODE_FULL
            }
            castWifiLock = wm?.createWifiLock(mode, "$TAG:cast")?.apply {
                setReferenceCounted(false); acquire()
            }
        } catch (e: Exception) { Log.w(TAG, "cast wifi lock", e) }
    }

    /* ---------------- v2.6.0：局域网远程投递 ---------------- */

    /** 开启面板，返回可供电脑浏览器访问的地址；未连 Wi-Fi 时返回 null */
    fun startLanRemote(): String? {
        val ip = com.swift.download.dlna.Dlna.lanIp() ?: return null
        if (lanRemote != null) return "http://$ip:${LAN_REMOTE_PORT}/"
        val controller = object : com.swift.download.remote.LanRemoteServer.Controller {
            override fun tasksJson(): String =
                com.swift.download.remote.LanRemoteProtocol.tasksJson(tasksSnapshot())

            override fun addUrl(url: String): Boolean {
                val kind = com.swift.download.remote.LanRemoteProtocol.classify(url)
                if (kind == com.swift.download.remote.LanRemoteProtocol.LinkKind.INVALID) return false
                if (kind == com.swift.download.remote.LanRemoteProtocol.LinkKind.MAGNET) {
                    addMagnetTask(url)
                } else {
                    addHttpTask(url, "", 4)
                }
                return true
            }

            override fun addTorrent(fileName: String, bytes: ByteArray): Boolean {
                return try {
                    val f = File(cacheDir, "remote_${System.currentTimeMillis()}_${
                        fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                    }")
                    f.writeBytes(bytes)
                    addTorrentFileTask(f.absolutePath)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "remote torrent", e)
                    false
                }
            }
        }
        val server = com.swift.download.remote.LanRemoteServer(controller, LAN_REMOTE_PORT)
        try {
            server.start()
            lanRemote = server
            startForegroundCompat()
            return "http://$ip:${LAN_REMOTE_PORT}/"
        } catch (e: Exception) {
            Log.w(TAG, "startLanRemote", e)
            server.stop()
            return null
        }
    }

    fun stopLanRemote() {
        lanRemote?.stop()
        lanRemote = null
        maybeStopForeground()
    }

    /** v2.6.0：删除 MediaStore 导出的文件条目；失败不阻断任务删除 */
    private fun deleteExportedFile(t: DownloadTask) {
        if (t.exportUri.isBlank()) return
        try {
            val uri = android.net.Uri.parse(t.exportUri)
            contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "deleteExportedFile", e)
        }
    }

    private fun releaseCastLocks() {
        try { castWifiLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) {}
        try { castWakeLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) {}
        castWifiLock = null
        castWakeLock = null
    }

    fun stopServeFile() {
        fileServer?.stop()
        fileServer = null
        releaseCastLocks()
    }

    /**
     * v2.6.0：Android 10+ 且下载根目录是应用专属目录时，把**已完成**的最终文件
     * 通过 MediaStore 写进公共 Download/极速下载/[分类]/，并更新任务的 save_path/export_uri。
     * 私有副本随后删除；导出失败时保留原文件，绝不把任务误标成"找不到文件"。
     */
    private fun exportToPublicDownloads(id: Long, t: DownloadTask) {
        if (Build.VERSION.SDK_INT < 29) return
        if (!settings.exportToPublicDownloads) return
        if (!StorageRoots.usingFallback(this)) return // 本来就写在公共 Download，无需导出
        val src = File(t.savePath)
        if (!src.isFile) return
        val category = if (settings.autoCategory) DownloadRules.categoryOf(t.fileName) else ""
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            put(MediaStore.MediaColumns.MIME_TYPE, MediaTypes.mimeOf(src.name))
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + MediaStoreExport.relativePath(category).trimEnd('/')
            )
        }
        val uri = try {
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore insert", e); null
        } ?: return
        var copied = false
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { input -> input.copyTo(out) }
                copied = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore copy", e)
        }
        if (!copied) {
            try { contentResolver.delete(uri, null, null) } catch (e: Exception) {}
            return
        }
        val publicPath = MediaStoreExport.publicPath(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            src.name, category
        )
        db.markExported(id, publicPath, uri.toString())
        tasks[id] = t.copy(savePath = publicPath, exportUri = uri.toString())
        if (!src.delete()) Log.w(TAG, "私有副本删除失败：${src.absolutePath}")
        notifyUi()
    }

    /** v2.2：流模式下手动收尾（引擎线程已停，segDone 不会触发） */
    private fun finishStreamHttp(id: Long, t: DownloadTask) {
        val part = File(t.savePath + ".part")
        val out = File(t.savePath)
        out.parentFile?.mkdirs()
        // v2.5.2：边播结束同样要验收 —— 之前只要计数够了就直接标记完成，
        // 一旦盘上还缺字节，用户得到的就是一个"已完成"的残缺文件
        var ok = part.isFile && (t.totalBytes <= 0L || part.length() == t.totalBytes)
        if (ok) {
            if (out.exists()) ok = out.delete()
            if (ok && !part.renameTo(out)) {
                ok = try {
                    part.copyTo(out, overwrite = true)
                    part.delete()
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "finishStreamHttp merge", e)
                    false
                }
            }
            if (ok && t.totalBytes > 0L) ok = out.length() == t.totalBytes
        }
        if (!ok) {
            val msg = "边下边播结束但文件不完整，已停止标记完成"
            db.updateProgress(id, t.downloadedBytes, DownloadTask.Status.FAILED, msg)
            tasks[id] = t.copy(status = DownloadTask.Status.FAILED, error = msg)
            httpJobs.remove(id)
            streamSession?.takeIf { it.taskId == id }?.let {
                it.server.stop()
                streamSession = null
            }
            notifyUi()
            return
        }
        File(t.dirFile(), DownloadEngine.Segments.progFileName(id)).delete()
        db.updateProgress(id, t.totalBytes, DownloadTask.Status.COMPLETED, "")
        val done = t.copy(status = DownloadTask.Status.COMPLETED, downloadedBytes = t.totalBytes)
        tasks[id] = done
        httpJobs.remove(id)
        // 必须先停流服务器：它持有 .part/正式文件的 RandomAccessFile 句柄，
        // 否则导出后删除私有副本在 Windows/部分文件系统上会失败。
        streamSession?.takeIf { it.taskId == id }?.let {
            it.server.stop()
            streamSession = null
        }
        exportToPublicDownloads(id, done)
        notifyDone(id, t.fileName)
        notifyUi()
    }

    fun pauseAll() {
        for ((id, t) in tasks.toList()) {
            if (t.status == DownloadTask.Status.RUNNING || t.status == DownloadTask.Status.PROBING) {
                pause(id)
            }
        }
        reload(); notifyUi()
    }

    fun resumeAll() {
        for ((id, t) in tasks.toList()) {
            if (t.status == DownloadTask.Status.PAUSED || t.status == DownloadTask.Status.QUEUED) resume(id)
        }
    }

    fun clearCompleted() {
        for ((id, t) in tasks.toList()) {
            if (t.status == DownloadTask.Status.COMPLETED) delete(id, false)
        }
    }

    /** v2.7 Stage2.1：从设置构建 BT 网络配置并应用到会话 */
    private fun applyBtNetworkSettings() {
        val applies = com.swift.download.bt.BtConfigRules.proxyApplies(
            settings.proxyEnabled, settings.proxyForBt
        )
        val host = if (applies) settings.proxyHost else ""
        val type = settings.proxyType
        TorrentEngine.get().applyNetworkConfig(
            com.swift.download.bt.TorrentEngine.NetworkConfig(
                proxyEnabled = applies,
                proxyType = type,
                proxyHost = host,
                proxyPort = settings.proxyPort,
                uploadLimitBps = com.swift.download.bt.BtConfigRules.uploadLimitBps(settings.btUploadMbps)
            )
        )
    }

    override fun onCreate() {
        super.onCreate()
        settings.installProxySelector()
        applyBtNetworkSettings()
        createChannels()
        startForegroundCompat()
        TorrentEngine.get().addListener(btListener)
        reload()
        // 进程被杀后重建执行体：DB 只存了状态，没有 Job/会话，不重建则永远"下载中"
        resumeActiveAfterRestart()
        ticker()
    }

    /**
     * 区分「用户暂停」与「进程被杀」：只恢复 RUNNING/PROBING 的任务；
     * HTTP 重建 DownloadJob（从 .swiftprog 续传），BT 重新注册进 libtorrent 会话，
     * m3u8 重新启动 HLS 下载（沿用用户选定的清晰度与来源页）。
     */
    private fun resumeActiveAfterRestart() {
        for ((id, t) in tasks.toList()) {
            if (!DownloadRules.shouldResumeAfterRestart(t.status)) continue
            when {
                t.isHttp -> startHttp(id)
                t.isM3u8 -> startM3u8(id)
                t.hash.isNotBlank() -> {
                    val dir = File(t.savePath).parentFile ?: continue
                    // 复用原始磁力链接以保留 tracker（冷门资源靠它找节点）
                    val uri = if (t.url.startsWith("magnet:", true)) t.url
                    else "magnet:?xt=urn:btih:${t.hash}"
                    TorrentEngine.get().addMagnet(t.hash, uri, dir)
                    btHashes[id] = t.hash
                }
            }
        }
        if (httpJobs.isNotEmpty() || btHashes.isNotEmpty() || m3u8Jobs.isNotEmpty()) startForegroundCompat()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        httpJobs.values.forEach { it.cancel() }
        m3u8Jobs.values.forEach { it.cancel() }
        streamSession?.server?.stop()
        fileServer?.stop()
        releaseCastLocks()
        lanRemote?.stop()
        lanRemote = null
        // v2.5：移除 BT 监听，避免服务重建后回调重复触发（重复完成通知）
        TorrentEngine.get().removeListener(btListener)
        // v2.5.2：服务退出时真正关闭 BT 会话，否则进程内会一直做种/占用连接与电量
        if (btHashes.isEmpty()) TorrentEngine.get().shutdown()
        super.onDestroy()
    }

    fun setUiListener(l: UiListener?) {
        uiListener = l
        l?.onChanged(tasksSnapshot())
    }

    /* ---------------- 任务生命周期 ---------------- */

    /**
     * v2.5：从 DB 重新加载任务表。
     * 原实现直接 clear() 再重填，会把内存中的实时状态（进度、探测出的文件名与大小）
     * 冲回 DB 里的旧值 —— 表现为进度条回退、文件名变回 URL 推断名。
     * 现在改为合并：DB 提供权威的 status/error，内存保留更新的进度与元信息。
     */
    private fun reload() {
        val rows = db.all()
        val ids = HashSet<Long>(rows.size)
        for (t in rows) {
            ids.add(t.id)
            val old = tasks[t.id]
            tasks[t.id] = if (old == null) t else t.copy(
                downloadedBytes = maxOf(old.downloadedBytes, t.downloadedBytes),
                totalBytes = if (t.totalBytes > 0) t.totalBytes else old.totalBytes,
                fileName = if (t.fileName.isNotBlank()) t.fileName else old.fileName,
                savePath = if (t.savePath.isNotBlank()) t.savePath else old.savePath
            )
        }
        // DB 中已删除的任务要从内存移除
        for (id in tasks.keys.toList()) if (id !in ids) tasks.remove(id)
    }

    fun tasksSnapshot(): List<DownloadTask> = tasks.values
        .sortedByDescending { it.createdAt }
        .map { it.copy() }

    /** v2.5：服务端精确速度采样（1 秒窗口差分），供 UI 直接显示，避免自算抖动 */
    private fun speedsSnapshot(): Map<Long, Long> {
        val out = HashMap<Long, Long>(samplers.size)
        for ((id, s) in samplers) {
            val t = tasks[id] ?: continue
            if (t.status != DownloadTask.Status.RUNNING) continue
            out[id] = s.sample(t.downloadedBytes, System.currentTimeMillis())
        }
        return out
    }

    /** 当前是否满足 Wi-Fi / 时段规则；不满足时任务进入排队并记录等待原因 */
    private fun startDecision(): DownloadRules.StartDecision {
        val cal = java.util.Calendar.getInstance()
        val minute = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val wifiOk: Boolean? = if (settings.networkRule == NetworkRule.WIFI_ONLY) isWifiConnected() else null
        return DownloadRules.decideStart(
            minute, wifiOk, settings.networkRule,
            settings.scheduleEnabled, settings.scheduleStartMinute, settings.scheduleEndMinute
        )
    }

    private fun isWifiConnected(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** v2.6.0：当前占用执行槽位的任务数（HTTP / HLS / BT） */
    private fun activeCount(): Int = httpJobs.size + m3u8Jobs.size + btHashes.size

    private fun queueFull(): Boolean = QueueRules.isFull(activeCount(), settings.maxConcurrent)

    /** 任一任务结束/暂停/失败后立刻补位，不等 5 秒轮询 */
    private fun onSlotFreed() = scanPending(System.currentTimeMillis(), force = true)

    /** 单任务限速优先，其次全局限速；0 表示不限 */
    private fun effectiveLimit(taskId: Long): Long =
        settings.taskLimitBps(taskId).takeIf { it > 0 } ?: settings.globalLimitBps

    /**
     * v2.5.2：同一目录下不允许两个"活动任务"共用同一个落盘路径。
     * 共用路径时两个任务会互相清空 .part / 覆盖对方文件，最终两边都是坏文件。
     */
    private fun uniqueSavePath(dir: File, name: String, selfId: Long = 0L): String {
        val active = tasks.values.filter {
            it.id != selfId && it.status != DownloadTask.Status.COMPLETED &&
                it.status != DownloadTask.Status.FAILED
        }
        fun taken(p: String) = active.any { it.savePath.equals(p, ignoreCase = true) }
        var candidate = File(dir, name).absolutePath
        if (!taken(candidate)) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (i < 1000) {
            candidate = File(dir, "$base ($i)$ext").absolutePath
            if (!taken(candidate)) return candidate
            i++
        }
        return candidate
    }

    /** m3u8 下完是拼接好的 TS 流，扩展名不能继续叫 .m3u8（否则播放器不认） */
    private fun hlsFileName(name: String): String {
        val n = name.trim().ifBlank { "video" }
        return if (n.endsWith(".m3u8", ignoreCase = true)) {
            n.dropLast(5).ifBlank { "video" } + ".ts"
        } else n
    }

    fun addHttpTask(url: String, fileName: String, threads: Int): Long =
        addHttpTask(url, fileName, threads, "")

    fun addHttpTask(
        url: String, fileName: String, threads: Int,
        referer: String = "", cookie: String = "", userAgent: String = ""
    ): Long {
        var name = fileName.ifBlank { fallbackName(url) }
        val kind = if (settings.m3u8Enabled && M3u8.looksLikeM3u8(url))
            DownloadTask.KIND_M3U8 else DownloadTask.KIND_HTTP
        if (kind == DownloadTask.KIND_M3U8) name = hlsFileName(name)
        val sub = if (settings.autoCategory) DownloadRules.categoryOf(name) else ""
        val parent = File(
            StorageRoots.downloadRoot(this), sub
        )
        val decision = startDecision()
        val full = queueFull()
        val waitReason = if (decision.waitReason.isNotBlank()) decision.waitReason
            else if (full) DownloadRules.WAIT_QUEUE else ""
        val id = db.insert(DownloadTask(
            id = 0, url = url, fileName = name,
            savePath = uniqueSavePath(parent, name),
            totalBytes = -1, downloadedBytes = 0,
            status = DownloadTask.Status.QUEUED,
            threads = threads.coerceIn(1, 16),
            createdAt = System.currentTimeMillis(),
            error = waitReason,
            kind = kind,
            referer = referer,
            cookie = cookie,
            userAgent = userAgent
        ))
        reload()
        if (decision.allowed && !full) {
            if (kind == DownloadTask.KIND_M3U8) startM3u8(id) else startHttp(id)
            // 服务可能刚因空闲 stopSelf()，新任务必须重新挂前台通知，否则切后台即被杀
            startForegroundCompat()
        }
        notifyUi()
        return id
    }

    /** 嗅探入口：保存 master 地址，并把用户选中的变体与来源页一起落库（重启可恢复） */
    fun addVideoTask(
        masterUrl: String, fileName: String, variantUrl: String, referer: String,
        cookie: String = "", userAgent: String = ""
    ): Long {
        val name = hlsFileName(fileName.ifBlank { fallbackName(masterUrl) })
        val sub = if (settings.autoCategory) DownloadRules.categoryOf(name) else ""
        val parent = File(
            StorageRoots.downloadRoot(this), sub
        )
        val decision = startDecision()
        val full = queueFull()
        val waitReason = if (decision.waitReason.isNotBlank()) decision.waitReason
            else if (full) DownloadRules.WAIT_QUEUE else ""
        val id = db.insert(DownloadTask(
            id = 0, url = masterUrl, fileName = name,
            savePath = uniqueSavePath(parent, name),
            totalBytes = -1, downloadedBytes = 0,
            status = DownloadTask.Status.QUEUED,
            threads = 4,
            createdAt = System.currentTimeMillis(),
            error = waitReason,
            kind = DownloadTask.KIND_M3U8,
            variant = variantUrl,
            referer = referer,
            cookie = cookie,
            userAgent = userAgent
        ))
        reload()
        if (decision.allowed && !full) {
            startM3u8(id)
            startForegroundCompat()
        }
        notifyUi()
        return id
    }

    private fun startM3u8(id: Long) {
        val t = tasks[id] ?: return
        m3u8Jobs[id]?.cancel()
        val job = M3u8Job(t.url, t.savePath,
            throttle = { n -> rateLimiter.acquire(n, effectiveLimit(id)) },
            chosenVariant = t.variant.takeIf { it.isNotBlank() },
            referer = t.referer,
            cookie = t.cookie,
            autoRemux = settings.autoRemuxMp4,
            listener = object : M3u8Job.Listener {
                override fun onOutputPath(path: String) {
                    // v2.7 Stage2.3：封装后扩展名/路径可能变化，同步 DB 与内存任务
                    val cur = tasks[id] ?: return
                    val name = File(path).name
                    if (cur.savePath != path || cur.fileName != name) {
                        db.updateSavePath(id, path)
                        db.updateMeta(id, name, cur.totalBytes)
                        tasks[id] = cur.copy(savePath = path, fileName = name)
                    }
                }
                override fun onStatus(status: DownloadTask.Status) {
                    db.updateStatus(id, status)
                    tasks[id]?.let { tasks[id] = it.copy(status = status) }
                    notifyUi()
                }
                override fun onProgress(downloaded: Long) {
                    val cur = tasks[id] ?: return
                    val upd = cur.copy(downloadedBytes = downloaded, status = DownloadTask.Status.RUNNING)
                    tasks[id] = upd
                    // v2.5.2：分片回调非常密集，通知与落库按 1 秒节流；
                    // 同时把 HLS 进度接入通知栏（此前 m3u8 任务完全没有进度通知）
                    val now = System.currentTimeMillis()
                    if (now - (m3u8TickAt[id] ?: 0L) >= 1000L) {
                        m3u8TickAt[id] = now
                        updateProgressNotif(id, upd, 0)
                        db.updateProgress(id, downloaded, DownloadTask.Status.RUNNING, "")
                    }
                }
                override fun onProgressDetail(
                    downloaded: Long, totalEstimate: Long, doneSegments: Int, allSegments: Int
                ) {
                    // v2.6.0：分片均长动态估算总大小，让进度条与 ETA 在 HLS 任务上也能工作。
                    // 估算值只进内存、不落库：完成时按真实长度覆盖，避免把估值当事实持久化。
                    if (totalEstimate <= 0L) return
                    val cur = tasks[id] ?: return
                    if (totalEstimate <= cur.totalBytes) return
                    tasks[id] = cur.copy(totalBytes = totalEstimate)
                }
                override fun onFinished() {
                    val cur = tasks[id] ?: return
                    // v2.6.0：落库用合并后的真实文件长度（分片均长估算只用于过程中的 UI）
                    val realTotal = runCatching { java.io.File(cur.savePath).length() }
                        .getOrNull()?.takeIf { it > 0L } ?: downloadedBytesOf(id)
                    db.updateProgress(id, realTotal, DownloadTask.Status.COMPLETED, "")
                    tasks[id] = cur.copy(status = DownloadTask.Status.COMPLETED, totalBytes = realTotal)
                    exportToPublicDownloads(id, cur)
                    m3u8Jobs.remove(id)
                    m3u8TickAt.remove(id)
                    samplers.remove(id)
                    updateProgressNotif(id, cur.copy(status = DownloadTask.Status.COMPLETED), 0)
                    notifyDone(id, cur.fileName)
                    notifyUi()
                    onSlotFreed()
                }
                override fun onError(message: String) {
                    db.updateStatus(id, DownloadTask.Status.FAILED, message)
                    val cur = tasks[id] ?: return
                    tasks[id] = cur.copy(status = DownloadTask.Status.FAILED, error = message)
                    m3u8Jobs.remove(id)
                    m3u8TickAt.remove(id)
                    samplers.remove(id)
                    updateProgressNotif(id, cur.copy(status = DownloadTask.Status.FAILED), 0)
                    notifyUi()
                    onSlotFreed()
                }
            })
        m3u8Jobs[id] = job
        job.start()
    }

    private fun downloadedBytesOf(id: Long): Long = tasks[id]?.downloadedBytes ?: 0L

    fun addMagnetTask(magnetUri: String): Long {
        val info = com.swift.download.magnet.MagnetLinks.parse(magnetUri)
            ?: com.swift.download.magnet.MagnetLinks.parse("magnet:?xt=urn:btih:${magnetUri.trim()}")
        val hash = info?.hashNormalized ?: ""
        val displayName = info?.name ?: "磁力任务 $hash".trim()
        val saveDir = File(
            StorageRoots.downloadRoot(this),
            "BT"
        ).apply { mkdirs() }
        val id = db.insert(DownloadTask(
            id = 0, url = magnetUri, fileName = displayName,
            savePath = File(saveDir, displayName).absolutePath,
            totalBytes = -1, downloadedBytes = 0,
            status = DownloadTask.Status.PROBING, // 等待元数据（准备就绪）
            threads = 4,
            createdAt = System.currentTimeMillis(),
            kind = DownloadTask.KIND_BT,
            hash = hash
        ))
        reload()
        if (hash.isEmpty()) {
            db.updateStatus(id, DownloadTask.Status.FAILED, "磁力链接无效（缺少 infohash）")
            reload(); notifyUi()
        } else {
            val decision = startDecision()
            if (decision.allowed) {
                // v2.7 Stage2.1：可选地为磁力追加精选公开 tracker，加速冷门资源寻源
                val effectiveUri = if (settings.btAutoTracker)
                    com.swift.download.bt.PublicTrackers.appendToMagnet(magnetUri)
                else magnetUri
                TorrentEngine.get().addMagnet(hash, effectiveUri, saveDir)
                btHashes[id] = hash
                startForegroundCompat()
            } else {
                db.updateStatus(id, DownloadTask.Status.QUEUED, decision.waitReason)
                reload()
            }
        }
        notifyUi()
        return id
    }

    fun addTorrentFileTask(path: String): Long {
        val f = File(path)
        // v2.6.0：.torrent 入口此前绕过 startDecision()，仅 Wi-Fi / 时段规则对它无效
        val decision = startDecision()
        val hash = TorrentEngine.get().addTorrentFile(f, File(
            StorageRoots.downloadRoot(this), "BT"
        ))
        val name = f.nameWithoutExtension
        val full = queueFull()
        val initialStatus = when {
            hash == null -> DownloadTask.Status.FAILED
            decision.allowed && !full -> DownloadTask.Status.PROBING
            else -> DownloadTask.Status.QUEUED
        }
        val waitReason = if (decision.waitReason.isNotBlank()) decision.waitReason
            else if (full) DownloadRules.WAIT_QUEUE else ""
        val id = db.insert(DownloadTask(
            id = 0, url = path, fileName = name,
            savePath = File(
                StorageRoots.downloadRoot(this),
                "BT/$name"
            ).absolutePath,
            totalBytes = -1, downloadedBytes = 0,
            status = initialStatus,
            threads = 4,
            createdAt = System.currentTimeMillis(),
            error = if (hash == null) "种子文件无法解析" else waitReason,
            kind = DownloadTask.KIND_BT,
            hash = hash ?: ""
        ))
        reload()
        if (hash != null) {
            if (decision.allowed && !full) {
                btHashes[id] = hash
                startForegroundCompat()
            } else {
                // 种子已进 session，规则未允许时先暂停；scanPending 会 resume
                TorrentEngine.get().pause(hash)
            }
        }
        notifyUi()
        return id
    }

    private fun startHttp(id: Long) {
        val t = tasks[id] ?: return
        // 已有活跃 Job：先停掉，避免同一任务出现两个执行体（重复下载同一文件）
        httpJobs[id]?.cancel()
        val job = DownloadEngine.get().newJob(
            t,
            engineListener(id),
            { n -> rateLimiter.acquire(n, effectiveLimit(id)) },
            // v2.7 Stage1.3：注入真实剩余空间，probe 后做前置校验
            availableBytes = {
                com.swift.download.StorageRoots.availableBytes(
                    File(t.savePath).parentFile ?: com.swift.download.StorageRoots.downloadRoot(this)
                )
            },
            // v2.7 Stage1.2：注入自动重试次数与状态提示
            maxRetries = settings.autoRetryCount,
            onRetry = { attempt, max ->
                retryHints[id] = "网络重连 $attempt/$max"
                notifyUi()
            }
        )
        httpJobs[id] = job
        job.start()
    }

    private fun engineListener(id: Long) = object : DownloadEngine.EngineListener {
        override fun onProgress(task: DownloadTask, downloadedBytes: Long, speedBps: Long) {
            // ticker 统一回写，这里不处理（v1.0 即为轮询模型）
        }
        override fun onStatus(task: DownloadTask) {
            // v2.7 Stage1.2：进入稳定 RUNNING 态即清掉重连提示（probe 完成后这里会再回调一次）
            if (task.status == DownloadTask.Status.RUNNING) retryHints.remove(id)
            // probe 得到的 totalBytes / fileName 必须落库，否则 resume() 里的 reload()
            // 会把它们覆盖回 -1，导致边下边播与重启恢复再次拿到未知大小
            val current = tasks[id]
            if (current == null || current.totalBytes != task.totalBytes ||
                current.fileName != task.fileName || current.savePath != task.savePath
            ) {
                db.updateMeta(id, task.fileName, task.totalBytes)
                db.updateSavePath(id, task.savePath)
            }
            // v2.7 Stage1.4：probe 得到的最终重定向地址单独落库，供任务详情展示
            if (task.finalUrl.isNotBlank() && current?.finalUrl != task.finalUrl) {
                db.updateFinalUrl(id, task.finalUrl)
            }
            // 进入运行态即清除"等待 Wi-Fi / 等待时段"这类排队原因，避免 UI 显示为失败
            val err = if (task.status == DownloadTask.Status.RUNNING ||
                task.status == DownloadTask.Status.PROBING
            ) "" else task.error
            db.updateStatus(id, task.status, err)
            tasks[id] = task.copy(error = err)
            notifyUi()
        }
        override fun onFinished(task: DownloadTask) {
            retryHints.remove(id)
            db.updateProgress(id, task.downloadedBytes, DownloadTask.Status.COMPLETED, "")
            tasks[id] = task.copy(status = DownloadTask.Status.COMPLETED)
            exportToPublicDownloads(id, task)
            httpJobs.remove(id)
            notifyDone(id, task.fileName)
            notifyUi()
            onSlotFreed()
            Log.i(TAG, "finished ${task.fileName}")
        }
        override fun onError(task: DownloadTask, error: String) {
            retryHints.remove(id)
            db.updateProgress(id, task.downloadedBytes, DownloadTask.Status.FAILED, error)
            tasks[id] = task.copy(status = DownloadTask.Status.FAILED, error = error)
            httpJobs.remove(id)
            notifyUi()
            onSlotFreed()
        }
    }

    fun pause(id: Long) {
        val t = tasks[id] ?: return
        retryHints.remove(id)
        if (t.isM3u8) {
            m3u8Jobs[id]?.cancel()
            m3u8Jobs.remove(id)
            db.updateStatus(id, DownloadTask.Status.PAUSED)
        } else if (t.isHttp) {
            // v2.2：流模式下的任务暂停 → 结束会话不恢复
            if (streamSession?.taskId == id) {
                endStream(resume = false)
            } else {
                httpJobs[id]?.let { job ->
                    job.cancel()
                    // 暂停瞬间把分段进度落盘：否则最多丢 1 秒（ticker 周期）的进度，
                    // 继续时要把这段重新下一遍
                    job.writeProgressIfActive()
                }
                httpJobs.remove(id)
            }
            db.updateStatus(id, DownloadTask.Status.PAUSED)
        } else {
            btHashes[id]?.let { TorrentEngine.get().find(it)?.pause() }
            db.updateStatus(id, DownloadTask.Status.PAUSED)
        }
        reload(); notifyUi()
        onSlotFreed()
    }

    fun resume(id: Long) {
        val t = tasks[id] ?: return
        // 用户主动继续也要遵守 Wi-Fi / 时段规则
        val decision = startDecision()
        if (!decision.allowed) {
            db.updateStatus(id, DownloadTask.Status.QUEUED, decision.waitReason)
            reload(); notifyUi(); return
        }
        when {
            t.isM3u8 -> startM3u8(id)
            t.isHttp -> startHttp(id)
            else -> {
                val dir = File(t.savePath).parentFile ?: return
                val uri = if (t.url.startsWith("magnet:", true)) t.url
                else "magnet:?xt=urn:btih:${t.hash}"
                btHashes[id] = t.hash
                // v2.5.2：已在会话中的种子直接 resume，不再重复 addMagnet（否则等于没恢复）
                if (!TorrentEngine.get().resume(t.hash)) {
                    TorrentEngine.get().addMagnet(t.hash, uri, dir)
                }
                db.updateStatus(id, DownloadTask.Status.PROBING)
                startForegroundCompat()
            }
        }
        reload(); notifyUi()
    }

    /**
     * 链接刷新：校验新直链，停掉旧任务并把旧任务的所有派生状态清空后重下。
     * v2.5.2 修复：此前只替换了 url，旧 .part / .swiftprog / .m3u8state 都留着，
     * 且 reload() 用 maxOf 保留内存进度 —— 新链接会直接续到旧数据上；
     * 类型变化（普通文件 ↔ m3u8）与清晰度（variant）也没有重置。
     */
    fun refreshLink(id: Long, newUrl: String): Boolean {
        val url = DownloadRules.normalizeRefreshedUrl(newUrl) ?: return false
        val t = tasks[id] ?: return false

        // 1. 停掉旧执行体与旧的本地流会话
        if (streamSession?.taskId == id) endStream(resume = false)
        httpJobs[id]?.cancel(); httpJobs.remove(id)
        m3u8Jobs[id]?.cancel(); m3u8Jobs.remove(id)
        m3u8TickAt.remove(id)
        samplers.remove(id)
        idleSince.remove(id)

        // 2. 旧链接的半成品与断点状态不能带到新链接上
        File(t.savePath + ".part").delete()
        File(t.savePath + ".m3u8state").delete()
        com.swift.download.player.M3u8Job.deleteCacheDir(t.savePath)
        File(t.dirFile(), DownloadEngine.Segments.progFileName(id)).delete()

        // 3. 新链接可能换了类型和清晰度：重新判定 kind / 文件名 / 落盘路径
        val kind = if (settings.m3u8Enabled && M3u8.looksLikeM3u8(url))
            DownloadTask.KIND_M3U8 else DownloadTask.KIND_HTTP
        val name = if (kind == DownloadTask.KIND_M3U8) hlsFileName(t.fileName) else t.fileName
        val dir = File(t.savePath).parentFile ?: StorageRoots.downloadRoot(this)
        val savePath = uniqueSavePath(dir, name, id)

        db.resetForRefresh(id, url, name, savePath, kind)
        // 让 reload() 从 DB 重新读这一行（否则 maxOf 会把旧进度保留在内存里）
        tasks.remove(id)
        reload()

        val decision = startDecision()
        val full = queueFull()
        if (decision.allowed && !full) {
            if (kind == DownloadTask.KIND_M3U8) startM3u8(id) else startHttp(id)
        } else {
            val reason = if (decision.waitReason.isNotBlank()) decision.waitReason
                else DownloadRules.WAIT_QUEUE
            db.updateStatus(id, DownloadTask.Status.QUEUED, reason)
            tasks.remove(id)
            reload()
        }
        notifyUi()
        return true
    }

    /**
     * v2.7 Stage1.1：刷新链接并尽可能保留断点。
     * 在 IO 线程探测新链接，只有大小完全一致且支持 Range 才保留 .part/.swiftprog；
     * 否则回退到普通清空重下（refreshLink）。结果回主线程回调 UI。
     */
    fun refreshLinkKeepProgress(
        id: Long, newUrl: String, keep: Boolean, cb: (Boolean, String) -> Unit
    ) {
        val url = DownloadRules.normalizeRefreshedUrl(newUrl)
        if (url == null) {
            cb(false, "请输入 http/https 开头的直链")
            return
        }
        val t = tasks[id]
        if (t == null) {
            cb(false, "任务不存在")
            return
        }
        if (!keep) {
            val ok = refreshLink(id, url)
            cb(ok, if (ok) "已替换链接并从头下载" else "刷新失败")
            return
        }
        scope.launch {
            try {
                if (!t.isHttp) {
                    val ok = refreshLink(id, url)
                    withContext(Dispatchers.Main) { cb(ok, "非 HTTP 任务无法保留断点，已从头重下") }
                    return@launch
                }
                val probe = DownloadEngine.get().probe(url, t.referer, t.cookie, t.userAgent)
                val finalUrl = probe.finalUrl.ifBlank { url }
                val newKindIsHttp = !settings.m3u8Enabled ||
                    !com.swift.download.player.M3u8.looksLikeM3u8(finalUrl)
                val decision = com.swift.download.settings.RefreshRules.decide(
                    keepRequested = true,
                    oldTotalBytes = t.totalBytes,
                    newTotalBytes = probe.totalBytes,
                    acceptRanges = probe.acceptRanges,
                    newKindIsHttp = newKindIsHttp
                )
                if (decision.keep) {
                    // 停掉旧执行体，但保留 .part / .swiftprog / 进度
                    if (streamSession?.taskId == id) endStream(resume = false)
                    httpJobs[id]?.cancel(); httpJobs.remove(id)
                    retryHints.remove(id)
                    tasks.remove(id)
                    db.updateUrlKeepProgress(id, url, t.referer, t.cookie)
                    reload()
                    startHttp(id)
                    withContext(Dispatchers.Main) { cb(true, "已保留进度，从断点继续") }
                } else {
                    // 条件不满足：提示原因并回退为清空重下
                    val ok = refreshLink(id, url)
                    withContext(Dispatchers.Main) {
                        cb(ok, "无法保留进度（${decision.reason}），已从头重下")
                    }
                }
            } catch (e: Exception) {
                // 探测失败（链接仍失效）：回退清空重下并提示
                val ok = refreshLink(id, url)
                withContext(Dispatchers.Main) {
                    cb(ok, "新链接探测失败（${e.message ?: "未知错误"}），已从头重下")
                }
            }
        }
    }

    fun setTaskLimitBps(id: Long, bps: Long) = settings.setTaskLimitBps(id, bps)

    /**
     * v2.5.2：重新下载。先把旧任务连同磁盘残留一起删干净，再用**原始参数**重建：
     *  - m3u8 保留变体地址/来源页/文件名，不会退回 master 自动选最高码率
     *  - .torrent 任务按种子文件重建，不再被当成磁力链接（hash 为空直接失败）
     */
    fun restartTask(id: Long, threadsOverride: Int = 0): Long? {
        val t = tasks[id] ?: return null
        delete(id, withFiles = true)
        return when {
            t.isBt -> when {
                t.url.startsWith("magnet:", ignoreCase = true) -> addMagnetTask(t.url)
                t.hash.isNotBlank() -> addMagnetTask("magnet:?xt=urn:btih:${t.hash}")
                File(t.url).isFile -> addTorrentFileTask(t.url)
                else -> null
            }
            t.isM3u8 -> addVideoTask(t.url, t.fileName, t.variant, t.referer)
            // v2.8 模块一：threadsOverride=1 支持 403/Referer 受限站点的单线程重试
            else -> addHttpTask(t.url, t.fileName, if (threadsOverride > 0) threadsOverride else t.threads, t.referer)
        }
    }

    fun delete(id: Long, withFiles: Boolean) {
        val t = tasks[id] ?: return
        if (t.isM3u8) {
            m3u8Jobs[id]?.cancel(); m3u8Jobs.remove(id)
        } else if (t.isHttp) {
            if (streamSession?.taskId == id) endStream(resume = false)
            httpJobs[id]?.cancel(); httpJobs.remove(id)
        } else {
            btHashes[id]?.let { TorrentEngine.get().remove(it) }
            btHashes.remove(id)
        }
        if (withFiles) {
            File(t.savePath).delete()
            File(t.savePath + ".part").delete()
            // v2.6.0：Phase 2 导出后正式文件在 MediaStore（content://），
            // File.delete() 对 content URI 无效 —— 必须同时删除媒体库条目
            deleteExportedFile(t)
        }
        // v2.5：无论是否删除文件，断点进度文件都必须清理。
        // 否则"仅删任务"会留下孤儿 .swiftprog，占用空间且可能被后续任务误读。
        File(t.dirFile(), DownloadEngine.Segments.progFileName(id)).delete()
        // m3u8 续传状态文件同理；v2.6.0：并发分片缓存目录也必须清理，
        // 否则删任务后 .hls/ 里的分片会长期残留
        File(t.savePath + ".m3u8state").delete()
        com.swift.download.player.M3u8Job.deleteCacheDir(t.savePath)
        db.delete(id)
        tasks.remove(id)
        samplers.remove(id)
        finishedBtIds.remove(id)
        m3u8TickAt.remove(id)
        notifyUi()
        onSlotFreed()
    }

    /* ---------------- 轮询回写（v1.0 模型：500ms tick / 1s DB 写盘） ---------------- */

    private var lastDbWrite = 0L
    private var lastPendingScan = 0L

    /**
     * 每 5 秒重试一次排队任务；[force] 用于任务结束后的立即补位。
     * v2.6.0：补位也要遵守并发上限，不能因为一次扫描把队列全部放出来。
     */
    private fun scanPending(now: Long, force: Boolean = false) {
        if (!force && now - lastPendingScan < 5000L) return
        lastPendingScan = now
        val decision = startDecision()
        if (!decision.allowed) return
        for ((id, t) in tasks.toList()) {
            if (queueFull()) break
            if (t.status != DownloadTask.Status.QUEUED || !DownloadRules.isWaiting(t.error)) continue
            db.updateStatus(id, DownloadTask.Status.QUEUED, "")
            if (t.isM3u8) startM3u8(id) else if (t.isHttp) startHttp(id) else {
                val hash = t.hash
                if (hash.isNotBlank()) {
                    val dir = File(t.savePath).parentFile ?: continue
                    val uri = if (t.url.startsWith("magnet:", true)) t.url
                    else "magnet:?xt=urn:btih:$hash"
                    // v2.6.0：.torrent 排队时只是被 pause()，session 里已有种子；
                    // 先 resume，只有不在会话里才重新 addMagnet。
                    if (!TorrentEngine.get().resume(hash)) {
                        TorrentEngine.get().addMagnet(hash, uri, dir)
                    }
                    btHashes[id] = hash
                }
            }
            startForegroundCompat()
        }
    }

    private var lastRuleCheck = 0L

    /**
     * v2.6.0：规则实时联动。每秒检查一次，运行中的任务若不再满足 Wi-Fi/时段，
     * 立即挂起为 QUEUED（带等待原因）；条件恢复后由 scanPending() 自动拉起。
     */
    private fun enforceRules(now: Long) {
        if (now - lastRuleCheck < 1000L) return
        lastRuleCheck = now
        val decision = startDecision()
        if (decision.allowed) return
        val reason = decision.waitReason
        for ((id, t) in tasks.toList()) {
            if (t.status != DownloadTask.Status.RUNNING &&
                t.status != DownloadTask.Status.PROBING
            ) continue
            httpJobs[id]?.cancel(); httpJobs.remove(id)
            m3u8Jobs[id]?.cancel(); m3u8Jobs.remove(id)
            val hash = btHashes.remove(id)
            if (hash != null) TorrentEngine.get().pause(hash)
            samplers.remove(id)
            db.updateStatus(id, DownloadTask.Status.QUEUED, reason)
            tasks[id] = t.copy(status = DownloadTask.Status.QUEUED, error = reason)
        }
        notifyUi()
    }

    private fun ticker() {
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val now = System.currentTimeMillis()
                var dirty = false
                enforceRules(now)
                scanPending(now)
                // 延后动作（避免遍历 httpJobs 时增删集合）
                val after = ArrayList<() -> Unit>()
                // HTTP 任务
                for ((id, job) in httpJobs) {
                    val t = tasks[id] ?: continue
                    // v2.5.2：只回写仍在进行中的任务。暂停/完成/失败后再写会把终态翻回 RUNNING，
                    // 导致列表里已完成的任务又"跑"起来
                    if (t.status != DownloadTask.Status.RUNNING &&
                        t.status != DownloadTask.Status.PROBING
                    ) continue
                    val bytes = job.totalBytes()
                    val sampler = samplers.getOrPut(id) { DownloadEngine.SpeedSampler() }
                    val speed = sampler.sample(bytes, now)
                    val prog = t.copy(downloadedBytes = bytes)
                    tasks[id] = prog
                    dirty = true
                    updateProgressNotif(id, prog, speed)
                    if (now - lastDbWrite >= DB_WRITE_MS) {
                        db.updateProgress(id, bytes, DownloadTask.Status.RUNNING, "")
                        job.writeProgressIfActive()
                    }
                    // v2.2：流模式生命周期（完成收尾 / 断开空闲恢复）
                    val sess = streamSession
                    if (sess != null && sess.taskId == id) {
                        if (t.totalBytes > 0 && bytes >= t.totalBytes) {
                            after.add { finishStreamHttp(id, prog) }
                        } else if (!sess.server.hasClients()) {
                            idleSince.getOrPut(id) { now }
                            if (now - (idleSince[id] ?: now) >= 8000) {
                                idleSince.remove(id)
                                after.add { endStream(resume = true) }
                            }
                        } else {
                            idleSince.remove(id)
                        }
                    }
                }
                // v2.6.0：HLS 分片并发下载的速度采样。此前 m3u8 任务从不进 samplers，
                // 通知栏与任务卡片永远显示 0 B/s、ETA 也无从计算。
                for ((id, _) in m3u8Jobs) {
                    val t = tasks[id] ?: continue
                    if (t.status != DownloadTask.Status.RUNNING &&
                        t.status != DownloadTask.Status.PROBING
                    ) continue
                    val sampler = samplers.getOrPut(id) { DownloadEngine.SpeedSampler() }
                    val speed = sampler.sample(t.downloadedBytes, now)
                    updateProgressNotif(id, t, speed)
                }
                after.forEach { it() }
                // v2.2：投屏文件服务器空闲回收（电视短暂断开重连不受影响，仅长时间无客户端才回收）
                val fs = fileServer
                if (fs != null) {
                    if (fs.hasClients()) {
                        fileIdleSince = 0L
                    } else {
                        if (fileIdleSince == 0L) fileIdleSince = now
                        // v2.6.0：电视暂停 2 分钟后重连是常态，阈值延长到 10 分钟，
                        // 避免刚暂停就把文件服务器关掉导致无法继续播放
                        if (now - fileIdleSince >= 600000L) {
                            fileIdleSince = 0L
                            stopServeFile()
                        }
                    }
                }
                // BT 任务
                for ((id, hash) in btHashes) {
                    val t = tasks[id] ?: continue
                    val p = TorrentEngine.get().progressOf(hash) ?: continue
                    val sampler = samplers.getOrPut(id) { DownloadEngine.SpeedSampler() }
                    val speed = sampler.sample(p.downloaded, now)
                    var st = t.status
                    var name = t.fileName
                    var total = t.totalBytes
                    if (p.hasMetadata && name.startsWith("磁力任务") && p.name.isNotBlank()) name = p.name
                    if (p.hasMetadata && p.total > 0) total = p.total
                    // 元数据到达后同步真实种子名与落盘路径，否则"打开/投屏"会指向占位路径
                    val base = if (p.hasMetadata) applyBtMeta(id, t, name, total) else t
                    // v2.6.0：只有仍在运行/探测的任务才允许被 p.finished 收尾；
                    // 暂停或失败的任务不能被 ticker"翻盘"成已完成
                    val finishedNow = p.finished && BtTaskRules.canFinish(t.status)
                    if (finishedNow) st = DownloadTask.Status.COMPLETED
                    else if (st == DownloadTask.Status.RUNNING || st == DownloadTask.Status.PROBING) {
                        // v2.8 模块一：元数据未到且一点没下时保持 PROBING，
                        // 不能翻成 RUNNING 显示"下载中 0 B/s"误导用户（实际还在连 DHT/tracker）
                        st = if (!p.hasMetadata && p.downloaded <= 0L)
                            DownloadTask.Status.PROBING
                        else DownloadTask.Status.RUNNING
                    }
                    // 其它状态（暂停/排队/失败）保持原样，不被 ticker 翻回 RUNNING
                    val upd = base.copy(downloadedBytes = p.downloaded, totalBytes = total, fileName = name, status = st)
                    tasks[id] = upd
                    dirty = true
                    updateProgressNotif(id, upd, speed, p.seeds, p.peers)
                    if (now - lastDbWrite >= DB_WRITE_MS && BtTaskRules.canWriteDbProgress(st)) {
                        db.updateProgress(id, p.downloaded, st, "")
                    }
                    if (finishedNow) finishBtTask(id, name)
                }
                if (now - lastDbWrite >= DB_WRITE_MS) lastDbWrite = now
                if (dirty) notifyUi()
                maybeStopForeground()
            }
        }
    }

    /**
     * BT 元数据到达后回填真实种子名与落盘路径。
     * 磁力任务创建时用的是占位名（dn 或"磁力任务 hash"），路径也基于占位名，
     * 不同步的话任务完成后"打开/投屏"会指向一个不存在的路径。
     */
    private fun applyBtMeta(id: Long, t: DownloadTask, name: String, total: Long): DownloadTask {
        val safeName = sanitizeName(name).ifBlank { t.fileName }
        val parent = File(t.savePath).parentFile
        val path = if (parent != null) File(parent, safeName).absolutePath else t.savePath
        if (safeName == t.fileName && total == t.totalBytes && path == t.savePath) return t
        db.updateMeta(id, safeName, total)
        if (path != t.savePath) db.updateSavePath(id, path)
        return t.copy(fileName = safeName, totalBytes = total, savePath = path)
    }

    private fun sanitizeName(raw: String): String =
        raw.trim().replace('/', '_').replace('\\', '_').take(120)

    private val btListener = object : TorrentEngine.BtListener {
        override fun onMetadata(hash: String, name: String, totalBytes: Long, files: Int) {
            val id = btHashes.entries.firstOrNull { it.value == hash }?.key ?: return
            val t = tasks[id] ?: return
            // v2.7 Stage1.3：BT 元数据到手后前置校验空间，避免下到 99% 才发现放不下
            val free = com.swift.download.StorageRoots.availableBytes(
                File(t.savePath).parentFile
                    ?: com.swift.download.StorageRoots.downloadRoot(this@DownloadService)
            )
            if (!com.swift.download.StorageGuard.hasEnough(free, totalBytes)) {
                val msg = com.swift.download.StorageGuard.insufficientMessage(free, totalBytes)
                Log.w(TAG, "BT 空间不足：$msg")
                TorrentEngine.get().find(hash)?.pause()
                btHashes.remove(id)
                db.updateStatus(id, DownloadTask.Status.FAILED, msg)
                reload(); notifyUi(); onSlotFreed()
                return
            }
            val upd = applyBtMeta(id, t, name, totalBytes)
            if (upd !== t) {
                tasks[id] = upd.copy(status = DownloadTask.Status.RUNNING)
                notifyUi()
            }
        }
        override fun onFinished(hash: String) {
            val id = btHashes.entries.firstOrNull { it.value == hash }?.key ?: return
            // v2.6.0：与 ticker 的 p.finished 共用同一收口，避免两次完成通知
            finishBtTask(id, tasks[id]?.fileName ?: "BT 任务")
        }
        override fun onError(hash: String, message: String) {
            val id = btHashes.entries.firstOrNull { it.value == hash }?.key ?: return
            // v2.6.0：失败后必须从跟踪表移除，否则 ticker 会继续采样并把 FAILED 覆盖掉
            btHashes.remove(id)
            db.updateStatus(id, DownloadTask.Status.FAILED, message)
            reload(); notifyUi()
            onSlotFreed()
        }
    }

    /** BT 完成唯一入口：防重、清跟踪表、单条完成通知 */
    private fun finishBtTask(id: Long, name: String) {
        if (!finishedBtIds.add(id)) return
        // v2.7 Stage2.1：按停种策略处理做种；先取 hash（下面会清跟踪表）
        val hash = btHashes[id]
        when (settings.btSeedingPolicy) {
            com.swift.download.bt.BtConfigRules.SeedingPolicy.IMMEDIATE ->
                hash?.let { TorrentEngine.get().pause(it) }
            com.swift.download.bt.BtConfigRules.SeedingPolicy.DELAY_5MIN ->
                hash?.let { h -> scope.launch {
                    delay(com.swift.download.bt.BtConfigRules.DELAY_5MIN_MS)
                    TorrentEngine.get().pause(h)
                } }
            com.swift.download.bt.BtConfigRules.SeedingPolicy.ALWAYS -> { /* 一直做种 */ }
        }
        btHashes.remove(id)
        samplers.remove(id)
        db.updateStatus(id, DownloadTask.Status.COMPLETED)
        tasks[id]?.let {
            val done = it.copy(status = DownloadTask.Status.COMPLETED)
            tasks[id] = done
            exportToPublicDownloads(id, done)
        }
        notifyDone(id, name)
        notifyUi()
        onSlotFreed()
    }

    private fun maybeStopForeground() {
        // 排队等待 Wi-Fi/时段的任务需保活，否则 ticker 停止后条件满足也无人唤醒
        val hasWaiting = tasks.values.any { it.status == DownloadTask.Status.QUEUED && DownloadRules.isWaiting(it.error) }
        val active = httpJobs.isNotEmpty() || btHashes.isNotEmpty() || m3u8Jobs.isNotEmpty() ||
            streamSession != null || fileServer != null || hasWaiting
        if (!active) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /* ---------------- 通知（v1.0 双通道语义保持） ---------------- */

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            notifManager.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "下载进度", NotificationManager.IMPORTANCE_LOW)
            )
            notifManager.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "任务提醒", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun startForegroundCompat() {
        val n = buildForeground()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_FOREGROUND_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_FOREGROUND_ID, n)
        }
    }

    private fun buildForeground(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return builder(CHANNEL_PROGRESS)
            .setContentTitle("极速下载运行中") // v1.0 原文
            .setContentText("多线程 · 断点续传") // v1.0 原文
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    /** v2.5：任务通知 ID 统一走独立段位，避免与前台通知（ID=1）互相覆盖 */
    private fun taskNotifId(id: Long): Int = NOTIF_TASK_BASE + id.toInt()

    private fun updateProgressNotif(id: Long, t: DownloadTask, speed: Long, seeds: Int = -1, peers: Int = -1) {
        val nid = taskNotifId(id)
        if (t.status != DownloadTask.Status.RUNNING && t.status != DownloadTask.Status.PROBING) {
            notifManager.cancel(nid)
            return
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val content = if (t.status == DownloadTask.Status.PROBING) {
            if (t.isBt) "等待元数据…" else "准备就绪"
        } else {
            val extra = if (seeds >= 0) " · 做种 $seeds / 节点 $peers" else ""
            "${Format.bytes(t.downloadedBytes)}/${Format.bytes(t.totalBytes)} · ${Format.speed(speed)}$extra"
        }
        val b = builder(CHANNEL_PROGRESS)
            .setContentTitle(if (t.isBt) "BT：${t.fileName}" else t.fileName)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
        if (t.totalBytes > 0 && t.status == DownloadTask.Status.RUNNING) {
            b.setProgress(100, if (t.totalBytes > 0) (t.downloadedBytes * 100 / t.totalBytes).toInt() else 0, false)
        } else {
            b.setProgress(0, 0, true)
        }
        notifManager.notify(nid, b.build())
    }

    private fun notifyDone(id: Long, fileName: String) {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = builder(CHANNEL_DONE)
            .setContentTitle("下载完成") // v1.0 原文
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        notifManager.notify((NOTIF_TASK_BASE + 100000 + id).toInt(), n)
        notifManager.cancel(taskNotifId(id))
    }

    private fun builder(channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel)
        else Notification.Builder(this)

    /**
     * v2.5：UI 刷新统一切回主线程。
     * 此前 ticker（IO 线程）、引擎线程、BT 回调线程都会直接调用监听器，
     * 导致 RecyclerView 在非主线程刷新 → "Called from the invalid thread" 崩溃。
     */
    private fun notifyUi() {
        val snapshot = tasksSnapshot()
        val speeds = speedsSnapshot()
        mainHandler.post { uiListener?.onChanged(snapshot, speeds) }
    }

    companion object {
        const val TAG = "DownloadService"
        /** 边下边播等待探测就绪的上限 */
        private const val PROBE_WAIT_MS = 15000L
        /** v2.6.0：局域网远程投递端口 */
        private const val LAN_REMOTE_PORT = 8877
        fun start(ctx: Context) {
            val intent = Intent(ctx, DownloadService::class.java)
            // v2.6.0：startForegroundService 是 API 26 才有的接口，
            // minSdk=24 的设备上直接调用会在类验证/运行时崩溃。
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }
    }
}

/* 引擎 Job 进度文件回写扩展：只在活动时写，避免抖动 */
fun DownloadEngine.DownloadJob.writeProgressIfActive() {
    try {
        val dir = File(task.savePath).parentFile ?: return
        val f = File(dir, DownloadEngine.Segments.progFileName(task.id))
        f.parentFile?.mkdirs()
        // 原子替换：进程在写中途被杀时不会留下截断的 .swiftprog（否则整个断点丢失）
        val tmp = File(dir, "${f.name}.tmp")
        // v2.6.0：写三列（start end bytes）。动态切分后的非静态区间必须落盘，
        // 否则暂停/重启时 prepare() 认不出这些起点，会把进度清零从头下载。
        tmp.writeText(snapshotEntries().joinToString("\n") { "${it.start} ${it.end} ${it.bytes}" })
        if (f.exists() && !f.delete()) { tmp.delete(); return }
        if (!tmp.renameTo(f)) tmp.delete()
    } catch (e: Exception) {
        Log.w(DownloadService.TAG, "writeProgress", e)
    }
}
