package com.swift.download.magnet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.swift.download.HttpUtil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v2.8 模块四 4.2：离屏 WebView HTML 抓取兜底。
 *
 * 针对返回空壳 HTML（前端 JS 渲染）或 403（Cloudflare JS 挑战）的磁力源：
 * 复用进程全局的 CookieManager 与 WebView 代理（由 WebViewProxyManager 统一配置）——
 * 本类绝不调用 setProxyOverride/clearProxyOverride，避免覆盖掉主浏览器的全局代理设置；
 * 在主线程创建隐藏 WebView，等 DOM 渲染出 magnet 后提取 outerHTML 交 [MagnetParsers]。
 *
 * 注意：WebView 必须在主线程创建/销毁；IO 线程用 CountDownLatch 等待，超时 [timeoutMs]。
 */
object OffscreenHtmlFetcher {

    /** 同步抓取（调用方须在 IO 线程）；返回 null 表示超时/失败 */
    fun fetch(context: Context, url: String, timeoutMs: Long = 12_000): String? {
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<String>(1)
        val main = Handler(Looper.getMainLooper())

        main.post {
            var wv: WebView? = null
            try {
                wv = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = false   // 省流量，磁力结果不需要图片
                    settings.userAgentString = HttpUtil.USER_AGENT
                    webViewClient = object : WebViewClient() {
                        private var started = false
                        override fun onPageFinished(view: WebView, u: String) {
                            if (started) return
                            started = true
                            pollForMagnet(view, 0, box, latch, view)
                        }

                        override fun onReceivedError(
                            view: WebView, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true && !latch.await(0, TimeUnit.MILLISECONDS)) {
                                box[0] = null
                                latch.countDown()
                                destroyOnMain(view)
                            }
                        }
                    }
                }
                wv.loadUrl(url)
            } catch (e: Exception) {
                box[0] = null
                latch.countDown()
                wv?.let { destroyOnMain(it) }
            }
        }

        val ok = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!ok) {
            // 超时：主线程销毁 WebView，避免泄漏
            main.post { /* WebView 引用随匿名对象持有，超时后由 poll 链自然结束 */ }
        }
        return box[0]
    }

    /** 每 500ms 检测一次页面是否渲染出 magnet，最多 6 次；超时则提取当前 DOM */
    private fun pollForMagnet(
        view: WebView, attempt: Int, box: Array<String?>, latch: CountDownLatch, wv: WebView
    ) {
        if (latch.count == 0L) { destroyOnMain(wv); return }
        if (attempt >= 6) {
            view.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                box[0] = raw?.let { unquote(it) }
                latch.countDown()
                destroyOnMain(wv)
            }
            return
        }
        val js = "(function(){var h=document.documentElement.outerHTML;return h.indexOf('magnet:')>=0?h:'';})()"
        view.evaluateJavascript(js) { raw ->
            val html = raw?.let { unquote(it) }
            if (!html.isNullOrEmpty()) {
                box[0] = html
                latch.countDown()
                destroyOnMain(wv)
            } else {
                view.postDelayed({ pollForMagnet(view, attempt + 1, box, latch, wv) }, 500)
            }
        }
    }

    private fun destroyOnMain(view: WebView) {
        val main = Handler(Looper.getMainLooper())
        val runnable = Runnable {
            try {
                view.stopLoading()
                view.destroy()
            } catch (_: Exception) {}
        }
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run() else main.post(runnable)
    }

    /** evaluateJavascript 返回 JSON 编码字符串，反转义为原始 HTML */
    private fun unquote(js: String): String = try {
        org.json.JSONObject("{\"v\":$js}").getString("v")
    } catch (e: Exception) {
        js.trim('"')
    }
}
