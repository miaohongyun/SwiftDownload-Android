package com.swift.download.browser

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.swift.download.settings.DownloadSettings

/**
 * v2.8 真机崩溃修复：WebView 代理的**唯一管理入口**（进程全局）。
 *
 * 历史崩溃：向 ProxyController 传入 newSingleThreadExecutor() 后立即 shutdown()，
 * Chromium 经主线程 MessageQueue 异步回调 proxyOverrideChanged 时向已 Terminated 的池提交任务，
 * 抛 RejectedExecutionException 导致主线程闪退。
 *
 * 修复要点：
 * 1. 回调 executor 用 ContextCompat.getMainExecutor —— 无生命周期、永不关闭、零线程泄漏；
 * 2. setProxyOverride/clearProxyOverride 统一在主线程调用、统一收敛到本入口；
 * 3. 仅在代理配置真正变化时触发，避免无意义重复；
 * 4. 特性检测只在此处做一次；离屏磁力抓取复用进程全局代理，绝不调用 clearProxyOverride 覆盖。
 */
object WebViewProxyManager {

    /** 已应用的代理规格；null = 无代理 */
    private data class Spec(val host: String, val port: Int)

    @Volatile
    private var applied: Spec? = null

    /** 供诊断：当前已应用的代理 host，未应用为 null */
    fun appliedHost(): String? = applied?.host

    /**
     * 应用当前代理配置到 Chromium WebView。主线程执行；配置未变化则直接返回。
     * 可在 WebView 创建前、设置页关闭后反复调用。
     */
    fun apply(context: Context) {
        val app = context.applicationContext
        // main executor 基于主线程 Handler，无生命周期、永不关闭、零线程泄漏
        val main = ContextCompat.getMainExecutor(app)
        main.execute {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return@execute
                val s = DownloadSettings.get(app)
                val next = if (s.proxyEnabled && s.proxyHost.isNotBlank())
                    Spec(s.proxyHost.trim(), s.proxyPort)
                else null

                // 只有配置真正变化时才触发，避免重复 set/clear
                if (next == applied) return@execute
                applied = next

                val ctrl = ProxyController.getInstance()
                if (next != null) {
                    val config = ProxyConfig.Builder()
                        .addProxyRule("http://${next.host}:${next.port}")
                        .addDirect() // 直连兜底，避免本地资源也走代理
                        .build()
                    ctrl.setProxyOverride(config, main) { }
                    Log.i(TAG, "setProxyOverride ${next.host}:${next.port}")
                } else {
                    ctrl.clearProxyOverride(main) { }
                    Log.i(TAG, "clearProxyOverride")
                }
            } catch (e: Exception) {
                Log.w(TAG, "apply failed", e)
            }
        }
    }

    private const val TAG = "WebViewProxyManager"
}
