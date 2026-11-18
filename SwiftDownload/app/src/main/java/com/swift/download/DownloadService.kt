package com.swift.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Binder
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.swift.download.bt.TorrentEngine
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
class DownloadService : Service() {

    inner class DownloadBinder : Binder() {
        val service: DownloadService get() = this@DownloadService

        fun setUiListener(l: UiListener?) = this@DownloadService.setUiListener(l)

        fun addHttpTask(url: String, fileName: String, threads: Int) =
            this@DownloadService.addHttpTask(url, fileName, threads)

        /** v2.5：带 Referer 的直链（嗅探到的防盗链视频需要） */
        fun addHttpTaskWithReferer(url: String, fileName: String, threads: Int, referer: String) =
            this@DownloadService.addHttpTask(url, fileName, threads, referer)

        fun addMagnetTask(magnetUri: String) = this@DownloadService.addMagnetTask(magnetUri)

        /** 嗅探到的视频：master 地址 + 用户选定变体 + 来源页 Referer */
        fun addVideoTask(masterUrl: String, fileName: String, variantUrl: String, referer: String) =
            this@DownloadService.addVideoTask(masterUrl, fileName, variantUrl, referer)

        fun addTorrentFileTask(path: String) = this@DownloadService.addTorrentFileTask(path)

        fun pause(id: Long) = this@DownloadService.pause(id)
        fun resume(id: Long) = this@DownloadService.resume(id)
        fun delete(id: Long, withFiles: Boolean) = this@DownloadService.delete(id, withFiles)
        fun tasksSnapshot(): List<DownloadTask> = this@DownloadService.tasksSnapshot()

        /** v2.5：服务端速度快照（避免 UI 端用差分自算导致首次显示 0 B/s） */
        fun speedsSnapshot(): Map<Long, Long> = this@DownloadService.speedsSnapshot()

        fun renotifyAll() = this@DownloadService.renotifyAll()

        /** v2.2：边下边播（HTTP 视频任务）→ 返回本地播放地址 */
        fun startStream(id: Long): String? = this@DownloadService.startStream(id)

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

        fun setTaskLimitBps(id: Long, bps: Long) = this@DownloadService.setTaskLimitBps(id, bps)
        fun getSettings(): DownloadSettings = this@DownloadService.settings
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
    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    private val httpJobs = ConcurrentHashMap<Long, DownloadEngine.DownloadJob>()
    private val btHashes = ConcurrentHashMap<Long, String>()
    private val samplers = ConcurrentHashMap<Long, DownloadEngine.SpeedSampler>()
    private val tasks = ConcurrentHashMap<Long, DownloadTask>()
    private var uiListener: UiListener? = null

    /** v2.5：所有 UI 刷新统一切回主线程 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /* ---------------- v2.2：边下边播 / 投屏 ---------------- */

    private class StreamSession(
        val taskId: Long,
        val job: DownloadEngine.DownloadJob,
        val server: StreamServer
    )

    @Volatile private var streamSession: StreamSession? = null
    @Volatile private var fileServer: StreamServer? = null
    /** v2.2：流会话空闲计时（播放器断开后多久恢复多线程） */
    private val idleSince = ConcurrentHashMap<Long, Long>()
    /** v2.2：投屏文件服务器空闲计时（无客户端 120 秒后回收） */
    @Volatile private var fileIdleSince = 0L

    /**
     * 边下边播：暂停多线程引擎 → 本地服务器接管（盘上有则读盘，无则远端拉取并回写引擎）。
     * 播放器断开（空闲 8 秒）后自动恢复多线程补齐未完成分段。
     */
    fun startStream(id: Long): String? {
        var t = tasks[id] ?: return null
        if (!t.isHttp) return null
        // 已有会话且是同一任务：直接复用地址
        streamSession?.let { if (it.taskId == id) return it.server.localUrl() }
        // 换任务：结束旧会话并恢复旧任务多线程
        endStream(resume = true)
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
            externalWrite = { offset, buf, len -> job.externalWrite(task, offset, buf, len) },
            segments = { job.snapshot() }
        )
        val server = StreamServer(backing)
        val port = server.start()
        streamSession = StreamSession(id, job, server)
        idleSince.remove(id)
        Log.i(TAG, "stream started task=$id port=$port")
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
                if (t.status != DownloadTask.Status.COMPLETED) startHttp(s.taskId)
            }
        }
        notifyUi()
    }

    /** 投屏/本地播已完成文件：本地文件服务器（支持 Range，供电视拖进度） */
    fun serveFile(path: String): String? {
        val f = File(path)
        if (!f.isFile) return null
        val ip = com.swift.download.dlna.Dlna.lanIp()
        stopServeFile()
        val server = StreamServer(FileBacking(f, MediaTypes.mimeOf(f.name)))
        server.start()
        fileServer = server
        return if (ip != null) server.lanUrl(ip) else server.localUrl()
    }

    fun stopServeFile() {
        fileServer?.stop()
        fileServer = null
    }

    /** v2.2：流模式下手动收尾（引擎线程已停，segDone 不会触发） */
    private fun finishStreamHttp(id: Long, t: DownloadTask) {
        val part = File(t.savePath + ".part")
        val out = File(t.savePath)
        out.parentFile?.mkdirs()
        if (part.exists()) {
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) {
                try {
                    part.copyTo(out, overwrite = true)
                    part.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "finishStreamHttp merge", e)
                }
            }
        }
        File(t.dirFile(), DownloadEngine.Segments.progFileName(id)).delete()
        db.updateProgress(id, t.totalBytes, DownloadTask.Status.COMPLETED, "")
        tasks[id] = t.copy(status = DownloadTask.Status.COMPLETED, downloadedBytes = t.totalBytes)
        httpJobs.remove(id)
        streamSession?.takeIf { it.taskId == id }?.let {
            it.server.stop()
            streamSession = null
        }
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

    override fun onCreate() {
        super.onCreate()
        settings.installProxySelector()
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
                    TorrentEngine.get().addMagnet(t.hash, uri, dir, t.fileName)
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
        // v2.5：移除 BT 监听，避免服务重建后回调重复触发（重复完成通知）
        TorrentEngine.get().removeListener(btListener)
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
    fun speedsSnapshot(): Map<Long, Long> {
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

    /** 单任务限速优先，其次全局限速；0 表示不限 */
    private fun effectiveLimit(taskId: Long): Long =
        settings.taskLimitBps(taskId).takeIf { it > 0 } ?: settings.globalLimitBps

    fun addHttpTask(url: String, fileName: String, threads: Int): Long =
        addHttpTask(url, fileName, threads, "")

    fun addHttpTask(url: String, fileName: String, threads: Int, referer: String): Long {
        val name = fileName.ifBlank { fallbackName(url) }
        val kind = if (settings.m3u8Enabled && M3u8.looksLikeM3u8(url))
            DownloadTask.KIND_M3U8 else DownloadTask.KIND_HTTP
        val sub = if (settings.autoCategory) DownloadRules.categoryOf(name) else ""
        val parent = File(
            StorageRoots.downloadRoot(this), sub
        )
        val decision = startDecision()
        val id = db.insert(DownloadTask(
            id = 0, url = url, fileName = name,
            savePath = File(parent, name).absolutePath,
            totalBytes = -1, downloadedBytes = 0,
            status = DownloadTask.Status.QUEUED,
            threads = threads.coerceIn(1, 16),
            createdAt = System.currentTimeMillis(),
            error = decision.waitReason,
            kind = kind,
            referer = referer
        ))
        reload()
        if (decision.allowed) {
            if (kind == DownloadTask.KIND_M3U8) startM3u8(id) else startHttp(id)
        }
        notifyUi()
        return id
    }

    /** 嗅探入口：保存 master 地址，并把用户选中的变体与来源页一起落库（重启可恢复） */
    fun addVideoTask(masterUrl: String, fileName: String, variantUrl: String, referer: String): Long {
        val name = fileName.ifBlank { fallbackName(masterUrl) }
        val sub = if (settings.autoCategory) DownloadRules.categoryOf(name) else ""
        val parent = File(
            StorageRoots.downloadRoot(this), sub
        )
        val decision = startDecision()
        val id = db.insert(DownloadTask(
            id = 0, url = masterUrl, fileName = name,
            savePath = File(parent, name).absolutePath,
            totalBytes = -1, downloadedBytes = 0,
            status = DownloadTask.Status.QUEUED,
            threads = 4,
            createdAt = System.currentTimeMillis(),
            error = decision.waitReason,
            kind = DownloadTask.KIND_M3U8,
            variant = variantUrl,
            referer = referer
        ))
        reload()
        if (decision.allowed) startM3u8(id)
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
            listener = object : M3u8Job.Listener {
                override fun onStatus(status: DownloadTask.Status) {
                    db.updateStatus(id, status)
                    tasks[id]?.let { tasks[id] = it.copy(status = status) }
                    notifyUi()
                }
                override fun onProgress(downloaded: Long) {
                    val cur = tasks[id] ?: return
                    val upd = cur.copy(downloadedBytes = downloaded, status = DownloadTask.Status.RUNNING)
                    tasks[id] = upd
                    db.updateProgress(id, downloaded, DownloadTask.Status.RUNNING, "")
                }
                override fun onFinished() {
                    val cur = tasks[id] ?: return
                    db.updateProgress(id, cur.totalBytes.coerceAtLeast(downloadedBytesOf(id)), DownloadTask.Status.COMPLETED, "")
                    tasks[id] = cur.copy(status = DownloadTask.Status.COMPLETED)
                    m3u8Jobs.remove(id)
                    notifyDone(id, cur.fileName)
                    notifyUi()
                }
                override fun onError(message: String) {
                    db.updateStatus(id, DownloadTask.Status.FAILED, message)
                    val cur = tasks[id] ?: return
                    tasks[id] = cur.copy(status = DownloadTask.Status.FAILED, error = message)
                    m3u8Jobs.remove(id)
                    notifyUi()
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
                TorrentEngine.get().addMagnet(hash, magnetUri, saveDir, displayName)
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
        val hash = TorrentEngine.get().addTorrentFile(f, File(
            StorageRoots.downloadRoot(this), "BT"
        ))
        val name = f.nameWithoutExtension
        val id = db.insert(DownloadTask(
            id = 0, url = path, fileName = name,
            savePath = File(
                StorageRoots.downloadRoot(this),
                "BT/$name"
            ).absolutePath,
            totalBytes = -1, downloadedBytes = 0,
            status = DownloadTask.Status.PROBING,
            threads = 4,
            createdAt = System.currentTimeMillis(),
            kind = DownloadTask.KIND_BT,
            hash = hash ?: ""
        ))
        reload()
        if (hash != null) btHashes[id] = hash
        notifyUi()
        return id
    }

    private fun startHttp(id: Long) {
        val t = tasks[id] ?: return
        // 已有活跃 Job：先停掉，避免同一任务出现两个执行体（重复下载同一文件）
        httpJobs[id]?.cancel()
        val job = DownloadEngine.get().newJob(t, engineListener(id)) { n ->
            rateLimiter.acquire(n, effectiveLimit(id))
        }
        httpJobs[id] = job
        job.start()
    }

    private fun engineListener(id: Long) = object : DownloadEngine.EngineListener {
        override fun onProgress(task: DownloadTask, downloadedBytes: Long, speedBps: Long) {
            // ticker 统一回写，这里不处理（v1.0 即为轮询模型）
        }
        override fun onStatus(task: DownloadTask) {
            // probe 得到的 totalBytes / fileName 必须落库，否则 resume() 里的 reload()
            // 会把它们覆盖回 -1，导致边下边播与重启恢复再次拿到未知大小
            val current = tasks[id]
            if (current == null || current.totalBytes != task.totalBytes ||
                current.fileName != task.fileName || current.savePath != task.savePath
            ) {
                db.updateMeta(id, task.fileName, task.totalBytes)
                db.updateSavePath(id, task.savePath)
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
            db.updateProgress(id, task.downloadedBytes, DownloadTask.Status.COMPLETED, "")
            tasks[id] = task.copy(status = DownloadTask.Status.COMPLETED)
            httpJobs.remove(id)
            notifyDone(id, task.fileName)
            notifyUi()
            Log.i(TAG, "finished ${task.fileName}")
        }
        override fun onError(task: DownloadTask, error: String) {
            db.updateProgress(id, task.downloadedBytes, DownloadTask.Status.FAILED, error)
            tasks[id] = task.copy(status = DownloadTask.Status.FAILED, error = error)
            httpJobs.remove(id)
            notifyUi()
        }
    }

    fun pause(id: Long) {
        val t = tasks[id] ?: return
        if (t.isM3u8) {
            m3u8Jobs[id]?.cancel()
            m3u8Jobs.remove(id)
            db.updateStatus(id, DownloadTask.Status.PAUSED)
        } else if (t.isHttp) {
            // v2.2：流模式下的任务暂停 → 结束会话不恢复
            if (streamSession?.taskId == id) {
                endStream(resume = false)
            } else {
                httpJobs[id]?.cancel()
                httpJobs.remove(id)
            }
            db.updateStatus(id, DownloadTask.Status.PAUSED)
        } else {
            btHashes[id]?.let { TorrentEngine.get().find(it)?.pause() }
            db.updateStatus(id, DownloadTask.Status.PAUSED)
        }
        reload(); notifyUi()
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
                TorrentEngine.get().addMagnet(t.hash, uri, dir, t.fileName)
                db.updateStatus(id, DownloadTask.Status.PROBING)
            }
        }
        reload(); notifyUi()
    }

    /** 链接刷新：校验新直链，停掉旧任务，写回 DB 并遵守当前调度规则后重下 */
    fun refreshLink(id: Long, newUrl: String): Boolean {
        val url = DownloadRules.normalizeRefreshedUrl(newUrl) ?: return false
        val t = tasks[id] ?: return false
        httpJobs[id]?.cancel(); httpJobs.remove(id)
        m3u8Jobs[id]?.cancel(); m3u8Jobs.remove(id)
        db.updateUrl(id, url)
        reload()
        val decision = startDecision()
        if (decision.allowed) {
            if (t.isM3u8 || M3u8.looksLikeM3u8(url)) startM3u8(id) else startHttp(id)
        }
        notifyUi()
        return true
    }

    fun setTaskLimitBps(id: Long, bps: Long) = settings.setTaskLimitBps(id, bps)

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
        }
        // v2.5：无论是否删除文件，断点进度文件都必须清理。
        // 否则"仅删任务"会留下孤儿 .swiftprog，占用空间且可能被后续任务误读。
        File(t.dirFile(), DownloadEngine.Segments.progFileName(id)).delete()
        // m3u8 续传状态文件同理
        File(t.savePath + ".m3u8state").delete()
        db.delete(id)
        tasks.remove(id)
        samplers.remove(id)
        notifyUi()
    }

    /* ---------------- 轮询回写（v1.0 模型：500ms tick / 1s DB 写盘） ---------------- */

    private var lastDbWrite = 0L
    private var lastPendingScan = 0L

    /** 每 5 秒重试一次因 Wi-Fi/时段规则而排队的任务，条件满足自动开下 */
    private fun scanPending(now: Long) {
        if (now - lastPendingScan < 5000L) return
        lastPendingScan = now
        val decision = startDecision()
        if (!decision.allowed) return
        for ((id, t) in tasks.toList()) {
            if (t.status != DownloadTask.Status.QUEUED || !DownloadRules.isWaiting(t.error)) continue
            db.updateStatus(id, DownloadTask.Status.QUEUED, "")
            if (t.isM3u8) startM3u8(id) else if (t.isHttp) startHttp(id) else {
                val hash = t.hash
                if (hash.isNotBlank()) {
                    val dir = File(t.savePath).parentFile ?: continue
                    val uri = if (t.url.startsWith("magnet:", true)) t.url
                    else "magnet:?xt=urn:btih:$hash"
                    TorrentEngine.get().addMagnet(hash, uri, dir, t.fileName)
                    btHashes[id] = hash
                }
            }
            startForegroundCompat()
        }
    }

    private fun ticker() {
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val now = System.currentTimeMillis()
                var dirty = false
                scanPending(now)
                // 延后动作（避免遍历 httpJobs 时增删集合）
                val after = ArrayList<() -> Unit>()
                // HTTP 任务
                for ((id, job) in httpJobs) {
                    val t = tasks[id] ?: continue
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
                after.forEach { it() }
                // v2.2：投屏文件服务器空闲回收（电视短暂断开重连不受影响，仅长时间无客户端才回收）
                val fs = fileServer
                if (fs != null) {
                    if (fs.hasClients()) {
                        fileIdleSince = 0L
                    } else {
                        if (fileIdleSince == 0L) fileIdleSince = now
                        if (now - fileIdleSince >= 120000) {
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
                    if (p.hasMetadata && name.startsWith("磁力任务")) name = p.name
                    if (p.hasMetadata && total != p.total && p.total > 0) {
                        total = p.total
                        db.updateMeta(id, name, total)
                    }
                    if (p.finished) st = DownloadTask.Status.COMPLETED else if (st != DownloadTask.Status.PAUSED) st = DownloadTask.Status.RUNNING
                    val upd = t.copy(downloadedBytes = p.downloaded, totalBytes = total, fileName = name, status = st)
                    tasks[id] = upd
                    dirty = true
                    updateProgressNotif(id, upd, speed, p.seeds, p.peers)
                    if (now - lastDbWrite >= DB_WRITE_MS) {
                        db.updateProgress(id, p.downloaded, st, "")
                    }
                    if (p.finished) {
                        btHashes.remove(id)
                        notifyDone(id, name)
                    }
                }
                if (now - lastDbWrite >= DB_WRITE_MS) lastDbWrite = now
                if (dirty) notifyUi()
                maybeStopForeground()
            }
        }
    }

    private val btListener = object : TorrentEngine.BtListener {
        override fun onMetadata(hash: String, name: String, totalBytes: Long, files: Int) {
            val id = btHashes.entries.firstOrNull { it.value == hash }?.key ?: return
            val t = tasks[id] ?: return
            if (t.totalBytes != totalBytes) {
                db.updateMeta(id, name, totalBytes)
                tasks[id] = t.copy(fileName = name, totalBytes = totalBytes, status = DownloadTask.Status.RUNNING)
                notifyUi()
            }
        }
        override fun onFinished(hash: String) {
            val id = btHashes.entries.firstOrNull { it.value == hash }?.key ?: return
            db.updateStatus(id, DownloadTask.Status.COMPLETED)
            btHashes.remove(id)
            reload()
            notifyDone(id, tasks[id]?.fileName ?: "BT 任务")
            notifyUi()
        }
        override fun onError(hash: String, message: String) {
            val id = btHashes.entries.firstOrNull { it.value == hash }?.key ?: return
            db.updateStatus(id, DownloadTask.Status.FAILED, message)
            reload(); notifyUi()
        }
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

    private fun renotifyAll() {
        startForegroundCompat()
        tasks.values.filter { it.status == DownloadTask.Status.RUNNING || it.status == DownloadTask.Status.PROBING }
            .forEach { updateProgressNotif(it.id, it, 0) }
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
            val pct = if (t.totalBytes > 0) (t.downloadedBytes * 100 / t.totalBytes).toInt() else 0
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
        fun start(ctx: Context) {
            ctx.startForegroundService(
                Intent(ctx, DownloadService::class.java)
            )
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
        tmp.writeText(snapshot().entries.joinToString("\n") { "${it.key} ${it.value}" })
        if (f.exists() && !f.delete()) { tmp.delete(); return }
        if (!tmp.renameTo(f)) tmp.delete()
    } catch (e: Exception) {
        Log.w(DownloadService.TAG, "writeProgress", e)
    }
}
