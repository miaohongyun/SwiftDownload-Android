package com.swift.download.settings

import android.content.Context
import android.content.SharedPreferences
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** 网络接入规则：不限 / 仅 Wi-Fi */
enum class NetworkRule { ANY, WIFI_ONLY }

/**
 * 高频控制项配置：全部落 SharedPreferences，默认关闭或不限，
 * 保证不开启任何开关时行为与 v1.0 完全一致（回归友好）。
 */
class DownloadSettings private constructor(ctx: Context) {

    private val prefs: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("swift_download_settings", Context.MODE_PRIVATE)

    /** 全局限速，字节/秒；0 = 不限 */
    var globalLimitBps: Long
        get() = prefs.getLong(KEY_GLOBAL_LIMIT, 0L)
        set(value) = prefs.edit().putLong(KEY_GLOBAL_LIMIT, value.coerceAtLeast(0)).apply()

    var networkRule: NetworkRule
        get() = if (prefs.getBoolean(KEY_WIFI_ONLY, false)) NetworkRule.WIFI_ONLY else NetworkRule.ANY
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value == NetworkRule.WIFI_ONLY).apply()

    var scheduleEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCHEDULE, false)
        set(value) = prefs.edit().putBoolean(KEY_SCHEDULE, value).apply()

    /** 允许下载的起始/结束分钟（0..1439），支持跨零点 */
    var scheduleStartMinute: Int
        get() = prefs.getInt(KEY_SCHEDULE_START, 0)
        set(value) = prefs.edit().putInt(KEY_SCHEDULE_START, value.coerceIn(0, 1439)).apply()

    var scheduleEndMinute: Int
        get() = prefs.getInt(KEY_SCHEDULE_END, 1439)
        set(value) = prefs.edit().putInt(KEY_SCHEDULE_END, value.coerceIn(0, 1439)).apply()

    var autoCategory: Boolean
        get() = prefs.getBoolean(KEY_CATEGORY, false)
        set(value) = prefs.edit().putBoolean(KEY_CATEGORY, value).apply()

    var m3u8Enabled: Boolean
        get() = prefs.getBoolean(KEY_M3U8, true)
        set(value) = prefs.edit().putBoolean(KEY_M3U8, value).apply()

    /** v2.6.0：最大同时下载任务数（1..5，默认 3） */
    var maxConcurrent: Int
        get() = com.swift.download.QueueRules.normalize(
            prefs.getInt(KEY_MAX_CONCURRENT, com.swift.download.QueueRules.DEFAULT_CONCURRENT)
        )
        set(value) = prefs.edit().putInt(
            KEY_MAX_CONCURRENT, com.swift.download.QueueRules.normalize(value)
        ).apply()

    /** v2.6.0：完成后导出到公共 Download/极速下载（默认开） */
    var exportToPublicDownloads: Boolean
        get() = prefs.getBoolean(KEY_EXPORT_PUBLIC, true)
        set(value) = prefs.edit().putBoolean(KEY_EXPORT_PUBLIC, value).apply()

    var proxyEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROXY_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_PROXY_ON, value).apply()

    var proxyHost: String
        get() = prefs.getString(KEY_PROXY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROXY_HOST, value.trim()).apply()

    var proxyPort: Int
        get() = prefs.getInt(KEY_PROXY_PORT, 7890)
        set(value) = prefs.edit().putInt(KEY_PROXY_PORT, value.coerceIn(1, 65535)).apply()

    fun taskLimitBps(taskId: Long): Long = prefs.getLong("$KEY_TASK_LIMIT$taskId", 0L)
    fun setTaskLimitBps(taskId: Long, bps: Long) =
        prefs.edit().putLong("$KEY_TASK_LIMIT$taskId", bps.coerceAtLeast(0)).apply()

    /** 安装进程级代理选择器，覆盖 HttpURLConnection（HTTP 下载 / 磁力抓取 / m3u8 分段） */
    fun installProxySelector() {
        val delegate = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                if (proxyEnabled && proxyHost.isNotBlank()) {
                    return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
                }
                return delegate?.select(uri) ?: listOf(Proxy.NO_PROXY)
            }
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {
                delegate?.connectFailed(uri, sa, ioe)
            }
        })
    }

    companion object {
        private const val KEY_GLOBAL_LIMIT = "global_limit_bps"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_SCHEDULE = "schedule_enabled"
        private const val KEY_SCHEDULE_START = "schedule_start"
        private const val KEY_SCHEDULE_END = "schedule_end"
        private const val KEY_CATEGORY = "auto_category"
        private const val KEY_M3U8 = "m3u8_enabled"
        private const val KEY_MAX_CONCURRENT = "max_concurrent"
        private const val KEY_EXPORT_PUBLIC = "export_public_downloads"
        private const val KEY_PROXY_ON = "proxy_enabled"
        private const val KEY_PROXY_HOST = "proxy_host"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_TASK_LIMIT = "task_limit_"

        @Volatile private var inst: DownloadSettings? = null
        fun get(ctx: Context): DownloadSettings =
            inst ?: synchronized(this) {
                inst ?: DownloadSettings(ctx.applicationContext).also { inst = it }
            }
    }
}
