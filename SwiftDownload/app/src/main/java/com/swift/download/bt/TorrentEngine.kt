package com.swift.download.bt

import android.util.Log
import com.frostwire.jlibtorrent.AddTorrentParams
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.alerts.AlertType
import com.frostwire.jlibtorrent.alerts.MetadataReceivedAlert
import com.frostwire.jlibtorrent.alerts.TorrentAlert
import com.frostwire.jlibtorrent.alerts.TorrentFinishedAlert
import com.frostwire.jlibtorrent.swig.settings_pack
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * v2.0 新增：BT/磁力下载引擎（jlibtorrent 1.2.0.18 封装）。
 *  - 元数据优先：addMagnet 先 fetchMagnet 拿元数据回填任务名/大小，再开始下载
 *  - 轮询式进度：progressOf() 由 DownloadService 的 ticker 调用（与 HTTP 引擎同一通知体系）
 */
class TorrentEngine private constructor() {

    interface BtListener {
        fun onMetadata(hash: String, name: String, totalBytes: Long, files: Int)
        fun onFinished(hash: String)
        fun onError(hash: String, message: String)
    }

    private val session = SessionManager()
    /**
     * v2.8 模块一：原 newSingleThreadExecutor 是致命缺陷 —— fetchMagnet 阻塞 20s×9 轮
     * 占住唯一线程，期间所有 BT 操作（find/pause/progress）排队，表现为"0 B/s 卡死"。
     * 改为缓存池：BT 操作各走独立任务，互不阻塞。
     */
    private val io: ExecutorService = Executors.newCachedThreadPool()
    /** 非阻塞元数据超时计时器：只投递检查任务，不占用 io 线程睡眠 */
    private val timeoutScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "bt-meta-timeout").apply { isDaemon = true }
    }
    @Volatile private var started = false

    /** v2.7 Stage2.1：BT 网络配置（服务层从 DownloadSettings 注入） */
    @Volatile private var netConfig: NetworkConfig = NetworkConfig()

    data class NetworkConfig(
        val proxyEnabled: Boolean = false,
        val proxyType: BtConfigRules.ProxyType = BtConfigRules.ProxyType.HTTP,
        val proxyHost: String = "",
        val proxyPort: Int = 7890,
        val uploadLimitBps: Int = 0
    )

    /**
     * 更新 BT 网络配置并应用到会话。未启动时仅保存，ensureStarted 会读取。
     */
    fun applyNetworkConfig(config: NetworkConfig) {
        netConfig = config
        if (!started) return
        try {
            val sp = SettingsPack()
            applyProxyAndRate(sp, config)
            session.applySettings(sp)
        } catch (e: Exception) {
            Log.w(TAG, "applyNetworkConfig", e)
        }
    }

    /** 把代理与上传限速写入 SettingsPack（代理关闭时清零复位） */
    private fun applyProxyAndRate(sp: SettingsPack, c: NetworkConfig) {
        val useProxy = c.proxyEnabled && c.proxyHost.isNotBlank()
        if (useProxy) {
            val ptype = when (c.proxyType) {
                BtConfigRules.ProxyType.SOCKS5 -> settings_pack.proxy_type_t.socks5
                BtConfigRules.ProxyType.HTTP -> settings_pack.proxy_type_t.http
            }
            sp.setInteger(settings_pack.int_types.proxy_type.swigValue(), ptype.swigValue())
            sp.setString(settings_pack.string_types.proxy_hostname.swigValue(), c.proxyHost)
            sp.setInteger(settings_pack.int_types.proxy_port.swigValue(), c.proxyPort)
        } else {
            sp.setInteger(settings_pack.int_types.proxy_type.swigValue(), settings_pack.proxy_type_t.none.swigValue())
            sp.setString(settings_pack.string_types.proxy_hostname.swigValue(), "")
            sp.setInteger(settings_pack.int_types.proxy_port.swigValue(), 0)
        }
        sp.uploadRateLimit(c.uploadLimitBps)
    }
    private val listeners = ArrayList<BtListener>()
    /** v2.6.0：shutdown() 后 session 仍持有 listener，重启时不能重复 add */
    private val listenerGuard = ListenerOnceGuard()

    fun addListener(l: BtListener) { synchronized(listeners) { listeners.add(l) } }

    /** v2.5：服务销毁时移除监听，避免服务重建后同一事件被回调多次（重复完成通知） */
    fun removeListener(l: BtListener) { synchronized(listeners) { listeners.remove(l) } }

    fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            val sp = SettingsPack()
            sp.activeDownloads(4)
            sp.activeLimit(8)
            sp.connectionsLimit(MAX_CONNECTIONS)
            sp.downloadRateLimit(0)   // 不限速
            sp.uploadRateLimit(0)
            sp.cacheSize(CACHE_SIZE_MB)
            // v2.8 模块一：DHT/LSD/UPnP/NAT-PMP 全开 + 公共引导节点
            sp.enableDht(true)
            sp.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
            sp.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
            sp.broadcastLSD(true)
            sp.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
            sp.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
            // 向所有 tracker/tier 并发宣告，冷门资源寻源关键
            sp.setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
            sp.setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
            sp.setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), DHT_BOOTSTRAP)
            sp.listenInterfaces("0.0.0.0:0")
            // v2.7 Stage2.1：应用代理与上传限速（默认配置=无代理不限速，行为不变）
            applyProxyAndRate(sp, netConfig)
            session.start(SessionParams(sp))
            session.startDht()
            listenerGuard.registerOnce { session.addListener(alertListener) }
            started = true
        }
    }

    fun shutdown() {
        if (!started) return
        synchronized(this) {
            if (!started) return
            try { session.stop() } catch (e: Exception) { Log.w(TAG, "stop", e) }
            started = false
        }
    }

    /** 等待元数据的 hash 集合：METADATA_RECEIVED 到达后移除，超时任务据此判定 */
    private val pendingMeta = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /**
     * v2.8 模块一：新增磁力任务改为纯事件驱动。
     *
     * 原实现有两大致命问题：
     *  1. session.download() 与 fetchMagnet() 双重添加句柄，DHT 命中后产生冲突；
     *  2. fetchMagnet 是阻塞调用，在单线程池里占住线程 180 秒，期间所有 BT 操作排队卡死。
     * 现在只调 session.download()（它本身就会连 DHT/tracker 拉元数据并开始下载），
     * 元数据到达由 alertListener 的 METADATA_RECEIVED 回调驱动；
     * 超时用调度器延迟检查，绝不阻塞工作线程。
     */
    fun addMagnet(hash: String, magnetUri: String, saveDir: File) {
        io.execute {
            ensureStarted()
            try {
                session.download(magnetUri, saveDir)
            } catch (e: Exception) {
                Log.w(TAG, "addMagnet", e)
                fireError(hash, "添加 BT 任务失败：${e.message ?: "未知错误"}")
                return@execute
            }
            // 注册非阻塞超时：180 秒后若仍无元数据才报错（真超时才报，不是轮询占线程）
            pendingMeta.add(hash)
            timeoutScheduler.schedule({
                if (pendingMeta.remove(hash)) {
                    val h = try { find(hash) } catch (e: Exception) { null }
                    val hasMeta = h?.status()?.hasMetadata() == true
                    if (!hasMeta) {
                        fireError(hash, "等待种子元数据超时（${MAX_METADATA_WAIT_SECS} 秒）：未找到可用节点，请换源或检查网络/代理")
                    }
                }
            }, MAX_METADATA_WAIT_SECS.toLong(), TimeUnit.SECONDS)
        }
    }

    /** 元数据到达回调：从待处理集合移除，避免超时误报 */
    private fun onMetadataArrived(hash: String) {
        pendingMeta.remove(hash)
    }

    /** 从 .torrent 文件直接添加 */
    fun addTorrentFile(torrentFile: File, saveDir: File): String? {
        ensureStarted()
        return try {
            val ti = TorrentInfo(torrentFile)
            session.download(ti, saveDir)
            ti.infoHash().toHex()
        } catch (e: Exception) {
            Log.w(TAG, "addTorrentFile", e); null
        }
    }

    fun find(hash: String) = try {
        ensureStartedNoSync()
        session.find(Sha1Hash(hash))
    } catch (e: Exception) {
        Log.w(TAG, "find", e); null
    }

    /** 轮询进度（服务 ticker 调用）。返回 null 表示该 hash 尚未出现在 session。 */
    fun progressOf(hash: String): BtProgress? {
        val h = find(hash) ?: return null
        if (!h.isValid()) return null
        val st = h.status()
        val meta = st.hasMetadata()
        // 中文状态标签：让用户一眼看出是在连节点、拉元数据还是真在下载
        val stateLabel = when {
            !meta -> "连接节点 · 获取元数据中"
            st.isFinished -> "已完成 · 做种中"
            else -> when (st.state()) {
                com.frostwire.jlibtorrent.TorrentStatus.State.DOWNLOADING_METADATA -> "获取元数据中"
                com.frostwire.jlibtorrent.TorrentStatus.State.DOWNLOADING -> "下载中"
                com.frostwire.jlibtorrent.TorrentStatus.State.SEEDING -> "做种中"
                com.frostwire.jlibtorrent.TorrentStatus.State.CHECKING_FILES -> "校验文件"
                com.frostwire.jlibtorrent.TorrentStatus.State.ALLOCATING -> "分配磁盘空间"
                else -> "连接节点中"
            }
        }
        // Tracker / 错误诊断
        val trackerError = try {
            val ec = st.errorCode()
            val msg = ec?.message().orEmpty()
            val tracker = st.currentTracker().orEmpty()
            when {
                msg.isNotBlank() && tracker.isNotBlank() -> "$tracker：$msg"
                msg.isNotBlank() -> msg
                tracker.isNotBlank() -> "Tracker：$tracker"
                else -> ""
            }
        } catch (e: Exception) { "" }
        val dhtNodes = try { session.dhtNodes() } catch (e: Exception) { 0L }
        return BtProgress(
            downloaded = st.totalWantedDone(),
            total = st.totalWanted(),
            speedBps = st.downloadPayloadRate().toLong(),
            seeds = st.numSeeds(),
            peers = st.numPeers(),
            hasMetadata = meta,
            finished = st.isFinished,
            name = try { h.name() } catch (e: Exception) { "" },
            dhtNodes = dhtNodes,
            stateLabel = stateLabel,
            trackerError = trackerError
        )
    }

    /** v2.6.0：排队中的种子先暂停，避免"仅 Wi-Fi / 时段"规则期间偷跑 */
    fun pause(hash: String): Boolean {
        val h = find(hash) ?: return false
        return try {
            h.pause()
            true
        } catch (e: Exception) {
            Log.w(TAG, "pause", e)
            false
        }
    }

    fun remove(hash: String) {
        try {
            find(hash)?.let { session.remove(it) }
        } catch (e: Exception) {
            Log.w(TAG, "remove", e)
        }
    }

    /**
     * v2.5.2：暂停后继续。已经在会话里的种子直接 resume()，
     * 之前是重新 addMagnet —— 对已在会话中的种子等于什么都没做，任务会卡在暂停态。
     * 返回 false 表示会话里没有这个种子（需要重新添加）。
     */
    fun resume(hash: String): Boolean {
        val h = find(hash) ?: return false
        return try {
            h.resume()
            true
        } catch (e: Exception) {
            Log.w(TAG, "resume", e)
            false
        }
    }

    /* ---------------- v2.6.0：选文件 / 顺序下载 ---------------- */

    /** 种子内文件列表（含当前是否勾选）；元数据未到达时返回空列表 */
    fun fileList(hash: String): List<BtFile> {
        val h = find(hash) ?: return emptyList()
        if (!h.status().hasMetadata()) return emptyList()
        val files = try { h.torrentFile()?.files() } catch (e: Exception) { null } ?: return emptyList()
        val priorities = try { h.filePriorities() } catch (e: Exception) { null }
        return (0 until files.numFiles()).map { i ->
            BtFile(
                index = i,
                name = try { files.fileName(i) } catch (e: Exception) { "文件 $i" },
                path = try { files.filePath(i) } catch (e: Exception) { "" },
                size = try { files.fileSize(i) } catch (e: Exception) { 0L },
                wanted = priorities?.getOrNull(i) != Priority.IGNORE
            )
        }
    }

    /**
     * 设置文件勾选：未选中的设为 IGNORE，选中的设为 NORMAL。
     * 传入空集合表示"全不选"，调用方应阻止这种非法状态（至少一个文件）。
     */
    fun setFilePriorities(hash: String, selected: Set<Int>): Boolean {
        val h = find(hash) ?: return false
        if (selected.isEmpty()) return false
        val count = try { h.torrentFile()?.numFiles() ?: 0 } catch (e: Exception) { 0 }
        if (count <= 0) return false
        val normalized = BtFileSelection.normalize(selected, count)
        if (normalized.isEmpty()) return false
        return try {
            h.prioritizeFiles(BtFileSelection.priorities(count, normalized))
            true
        } catch (e: Exception) {
            Log.w(TAG, "setFilePriorities", e)
            false
        }
    }

    /** 顺序下载：让前置连续块优先完成，单视频种子可提前播放 */
    fun setSequential(hash: String, enabled: Boolean): Boolean {
        val h = find(hash) ?: return false
        return try {
            if (enabled) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            else h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            true
        } catch (e: Exception) {
            Log.w(TAG, "setSequential", e)
            false
        }
    }

    fun isSequential(hash: String): Boolean = try {
        find(hash)?.flags()?.and_(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            ?.eq(TorrentFlags.SEQUENTIAL_DOWNLOAD) == true
    } catch (e: Exception) {
        Log.w(TAG, "isSequential", e)
        false
    }

    /**
     * 已完整落盘且已勾选视频的本地路径（顺序下载"就绪即播"用）。
     * jlibtorrent 的 fileProgress() 返回每个文件已完成字节数组。
     */
    fun readyVideoPath(hash: String): String? {
        val h = find(hash) ?: return null
        val files = fileList(hash).filter { it.wanted && BtFileSelection.isVideoName(it.name) }
        if (files.isEmpty()) return null
        val progress = try { h.fileProgress() } catch (e: Exception) { return null }
        val base = try { h.savePath() } catch (e: Exception) { return null }
        for (f in files) {
            val done = progress.getOrNull(f.index) ?: continue
            if (BtFileSelection.isReady(done, f.size)) {
                val rel = f.path.ifBlank { f.name }
                return listOf("$base/$rel", "$base/${f.name}")
                    .map(::File).firstOrNull { it.isFile }?.absolutePath
            }
        }
        return null
    }

    /* ---------------- v2.7 Stage2.2：按需边下边播（piece 调度） ---------------- */

    /**
     * 为视频文件的请求区间调度 piece：当前位置 + 预读设为 deadline=0（最高优先），
     * 文件首尾若干 piece 也提优先级（moov/mdat 索引）。返回该区间是否已全部就绪。
     */
    fun scheduleVideoRange(
        hash: String, fileIndex: Int, offsetInFile: Long, length: Int, prefetch: Int = 8
    ): Boolean {
        val h = find(hash) ?: return false
        val ti = try { h.torrentFile() } catch (e: Exception) { return false } ?: return false
        val pieceLen = ti.pieceLength()
        val totalPieces = ti.numPieces()
        if (pieceLen <= 0 || totalPieces <= 0) return false
        // 文件首字节所在的全局 piece：mapFile(index, 0, 1) 得到
        val firstPiece = try { ti.mapFile(fileIndex, 0L, 1).piece() } catch (e: Exception) { return false }
        val range = PieceRange.covering(firstPiece, offsetInFile, length.toLong(), pieceLen, totalPieces)
            ?: return false
        val withRead = PieceRange.withPrefetch(range, prefetch, totalPieces)
        return try {
            // 首尾 piece 高优先（moov 常在头尾）
            for (p in PieceRange.edgePieces(firstPiece, totalPieces)) {
                h.setPieceDeadline(p, 0)
            }
            var allReady = true
            for (p in withRead.first..withRead.last) {
                h.setPieceDeadline(p, 0)
                if (!h.havePiece(p)) allReady = false
            }
            allReady
        } catch (e: Exception) {
            Log.w(TAG, "scheduleVideoRange", e)
            false
        }
    }

    /**
     * 从 offsetInFile 起连续已就绪字节的右端（绝对文件内容偏移）。
     * 用 havePiece 判断，遇到第一个未就绪 piece 即停。
     */
    fun readyEndInFile(hash: String, fileIndex: Int, offsetInFile: Long): Long {
        val h = find(hash) ?: return offsetInFile
        val ti = try { h.torrentFile() } catch (e: Exception) { return offsetInFile } ?: return offsetInFile
        val pieceLen = ti.pieceLength()
        val totalPieces = ti.numPieces()
        if (pieceLen <= 0 || totalPieces <= 0) return offsetInFile
        val firstPiece = try { ti.mapFile(fileIndex, 0L, 1).piece() } catch (e: Exception) { return offsetInFile }
        var piece = firstPiece + (offsetInFile / pieceLen).toInt()
        if (piece >= totalPieces) return offsetInFile
        var end = offsetInFile
        while (piece < totalPieces) {
            if (!try { h.havePiece(piece) } catch (e: Exception) { false }) break
            end = ((piece - firstPiece + 1).toLong() * pieceLen)
            piece++
        }
        val fileSize = try { ti.files().fileSize(fileIndex) } catch (e: Exception) { Long.MAX_VALUE }
        return minOf(end, fileSize)
    }

    /** BT 视频目标文件：优先选已勾选的最大视频文件 */
    fun preferredVideoFile(hash: String): BtFile? =
        fileList(hash).filter { it.wanted && BtFileSelection.isVideoName(it.name) }
            .maxByOrNull { it.size }

    /**
     * v2.8 模块一：BT 实时诊断字段。
     * dhtNodes/stateLabel/trackerError 让 UI 能区分"正在连节点"与"真没资源"，
     * 不再让用户对着 0 B/s 干等。
     */
    data class BtProgress(
        val downloaded: Long,
        val total: Long,
        val speedBps: Long,
        val seeds: Int,
        val peers: Int,
        val hasMetadata: Boolean,
        val finished: Boolean,
        val name: String,
        val dhtNodes: Long = 0,
        val stateLabel: String = "",
        val trackerError: String = ""
    )

    private fun fireMetadata(hash: String, name: String, total: Long, files: Int) {
        val ls = synchronized(listeners) { listeners.toList() }
        for (l in ls) l.onMetadata(hash, name, total, files)
    }

    private fun fireError(hash: String, message: String) {
        val ls = synchronized(listeners) { listeners.toList() }
        for (l in ls) l.onError(hash, message)
    }

    private val alertListener = object : com.frostwire.jlibtorrent.AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.TORRENT_FINISHED.swig()
        )
        override fun alert(a: com.frostwire.jlibtorrent.alerts.Alert<*>) {
            when (a) {
                is MetadataReceivedAlert -> {
                    val hex = a.handle().infoHash().toHex()
                    onMetadataArrived(hex)
                    val data: ByteArray? = a.torrentData()
                    if (data != null && data.isNotEmpty()) {
                        try {
                            val ti = TorrentInfo(data)
                            fireMetadata(hex, ti.name(), ti.totalSize(), ti.numFiles())
                        } catch (e: Exception) {
                            Log.w(TAG, "meta alert", e)
                        }
                    }
                }
                is TorrentFinishedAlert -> {
                    val hex = (a as TorrentAlert<*>).handle().infoHash().toHex()
                    val ls = synchronized(listeners) { listeners.toList() }
                    for (l in ls) l.onFinished(hex)
                }
            }
        }
    }

    private fun ensureStartedNoSync() { if (!started) ensureStarted() }

    companion object {
        const val TAG = "TorrentEngine"
        const val MAX_METADATA_WAIT_SECS = 180
        const val MAX_CONNECTIONS = 200
        const val CACHE_SIZE_MB = 16
        /** v2.8 模块一：公共 DHT 引导节点，冷门资源寻源关键 */
        const val DHT_BOOTSTRAP =
            "router.bittorrent.com:6881,dht.transmissionbt.com:6881,router.utorrent.com:6881,dht.libtorrent.org:25401"
        @Volatile private var inst: TorrentEngine? = null
        fun get(): TorrentEngine =
            inst ?: synchronized(this) { inst ?: TorrentEngine().also { inst = it } }
    }
}
