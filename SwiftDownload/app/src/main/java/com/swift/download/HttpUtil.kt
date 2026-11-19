package com.swift.download

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 公共 HTTP 连接工具（v2.6.0 Phase 1）。
 *
 * 只依赖 JDK，不引入第三方网络框架。`HttpURLConnection` 自带的跟随重定向有两个问题：
 *  - 跨协议（http <-> https）时部分 ROM 实现会丢掉自定义请求头；
 *  - 重放 Range 头的行为不一致，边下边播重新建连时可能拿到错位数据。
 *
 * 这里手动处理 301/302/303/307/308，最多 [DEFAULT_MAX_REDIRECTS] 跳，
 * 并在每一跳重新写入 Range / Referer / User-Agent 等请求头。
 */
object HttpUtil {

    /** 与内置浏览器 WebView 的 UA 保持一致，避免同一站点两种身份 */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    const val DEFAULT_MAX_REDIRECTS = 5

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    /**
     * 打开连接并完成重定向跟踪。返回的 [HttpURLConnection] 请求头已可直接使用；
     * 调用方负责读取响应码/响应体并在结束时 `disconnect()`。
     *
     * @param range 原始 Range 值（如 `bytes=100-`），会在每一跳都重新写入。
     * @param method 初始方法；303 会按要求改为 GET。
     */
    fun open(
        url: String,
        referer: String = "",
        range: String? = null,
        method: String = "GET",
        extraHeaders: Map<String, String> = emptyMap(),
        maxRedirects: Int = DEFAULT_MAX_REDIRECTS
    ): HttpURLConnection {
        var current = url
        var currentMethod = method
        for (hop in 0..maxRedirects) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.requestMethod = currentMethod
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (referer.isNotBlank()) c.setRequestProperty("Referer", referer)
            if (range != null) c.setRequestProperty("Range", range)
            for ((k, v) in extraHeaders) c.setRequestProperty(k, v)

            val code = c.responseCode
            if (code !in REDIRECT_CODES) return c

            val location = c.getHeaderField("Location")
            c.disconnect()
            if (location.isNullOrBlank()) return c
            if (hop == maxRedirects) throw IOException("HTTP 重定向超过 $maxRedirects 次：$current")
            // 303 语义：无论原方法是什么都用 GET 重放；其余保留方法与 Range。
            if (code == 303 && currentMethod != "GET") currentMethod = "GET"
            // 相对地址与跨协议绝对地址都由 URL(base, ref) 处理。
            current = URL(URL(current), location).toString()
        }
        throw IOException("HTTP 重定向超过 $maxRedirects 次：$url")
    }
}
