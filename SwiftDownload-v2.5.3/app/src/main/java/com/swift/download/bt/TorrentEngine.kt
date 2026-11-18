package com.swift.download.bt

import android.util.Log
import com.frostwire.jlibtorrent.AddTorrentParams
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.alerts.AlertType
import com.frostwire.jlibtorrent.alerts.MetadataReceivedAlert
import com.frostwire.jlibtorrent.alerts.TorrentAlert
import com.frostwire.jlibtorrent.alerts.TorrentFinishedAlert
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var started = false
    private val listeners = ArrayList<BtListener>()

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
            sp.connectionsLimit(200)
            sp.downloadRateLimit(0)   // 不限速
            sp.uploadRateLimit(0)
            sp.enableDht(true)
            sp.listenInterfaces("0.0.0.0:0")
            session.start(SessionParams(sp))
            session.startDht()
            session.addListener(alertListener)
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

    /**
     * 新增磁力任务：后台线程等待元数据（DHT 冷门资源约 1~3 分钟），到达即回填名称并开下。
     * [hash] 为 40 位十六进制 infohash；[saveDir] 为保存目录。
     */
    fun addMagnet(hash: String, magnetUri: String, saveDir: File) {
        io.execute {
            ensureStarted()
            try {
                session.download(magnetUri, saveDir)
            } catch (e: Exception) {
                Log.w(TAG, "addMagnet", e)
                // v2.5.2：加入会话失败必须回报，否则任务永远停在"准备就绪"
                fireError(hash, "添加 BT 任务失败：${e.message ?: "未知错误"}")
                return@execute
            }
            // 元数据等待（fetchMagnet 阻塞至超时或拿到；displayName 在 session.download 内部
            // 由 AddTorrentParams 默认填充，元数据到达后由 libtorrent 覆盖为真实名）
            fetchMetadataLoop(hash, magnetUri)
        }
    }

    /** 冷门资源元数据慢：分多轮等待，最长 [MAX_METADATA_WAIT_SECS] */
    private fun fetchMetadataLoop(hash: String, magnetUri: String) {
        var waited = 0
        while (waited < MAX_METADATA_WAIT_SECS) {
            val data = try {
                session.fetchMagnet(magnetUri, FETCH_CHUNK_SECS)
            } catch (e: Exception) {
                Log.w(TAG, "fetchMagnet", e); null
            }
            if (data != null && data.isNotEmpty()) {
                try {
                    val ti = TorrentInfo(data)
                    fireMetadata(hash, ti.name(), ti.totalSize(), ti.numFiles())
                } catch (e: Exception) {
                    Log.w(TAG, "metadata parse", e)
                }
                return
            }
            // 已在下载？（同名任务已在 session 中且已有元数据）
            val h = find(hash)
            if (h != null && h.status().hasMetadata()) {
                try {
                    val ti = h.torrentFile()
                    fireMetadata(hash, ti.name(), ti.totalSize(), ti.numFiles())
                } catch (e: Exception) {
                    Log.w(TAG, "metadata parse2", e)
                }
                return
            }
            waited += FETCH_CHUNK_SECS
        }
        // v2.5.2：元数据迟迟不到（死链/冷门资源）必须给出失败原因，而不是无限"准备就绪"
        fireError(hash, "等待种子元数据超时（${MAX_METADATA_WAIT_SECS} 秒）：没有找到可用的节点")
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
        return BtProgress(
            downloaded = st.totalWantedDone(),
            total = st.totalWanted(),
            speedBps = st.downloadPayloadRate().toLong(),
            seeds = st.numSeeds(),
            peers = st.numPeers(),
            hasMetadata = st.hasMetadata(),
            finished = st.isFinished,
            name = try { h.name() } catch (e: Exception) { "" }
        )
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

    data class BtProgress(
        val downloaded: Long,
        val total: Long,
        val speedBps: Long,
        val seeds: Int,
        val peers: Int,
        val hasMetadata: Boolean,
        val finished: Boolean,
        val name: String
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
        const val FETCH_CHUNK_SECS = 20
        @Volatile private var inst: TorrentEngine? = null
        fun get(): TorrentEngine =
            inst ?: synchronized(this) { inst ?: TorrentEngine().also { inst = it } }
    }
}
