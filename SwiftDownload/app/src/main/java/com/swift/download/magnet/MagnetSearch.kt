package com.swift.download.magnet

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 磁力聚合（纯 Kotlin 核心 + 可注入执行器，单元测试覆盖聚合/去重/排序/非法 hash 过滤）。
 * 流程：并发请求各源 → 规则解析 → infohash 归一化过滤 → hash 去重合并（来源数累加）→
 * 排序（来源数 ↓，做种数 ↓，体积 ↓）。
 */
object MagnetSearch {

    data class Result(
        val hash: String,
        val name: String,
        val sizeBytes: Long,
        val seeders: Int,
        val sources: List<String>,
        val date: String?
    ) {
        val sourceCount: Int get() = sources.size
        /**
         * 补齐 xl（体积）与 tr（tracker）：DHT 冷门资源可借助公开 tracker 更快找到节点。
         */
        val magnetUri: String get() = buildString {
            append("magnet:?xt=urn:btih:$hash")
            if (name.isNotEmpty()) append("&dn=${enc(name)}")
            if (sizeBytes > 0) append("&xl=$sizeBytes")
            TRACKERS.forEach { append("&tr=${enc(it)}") }
        }

        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

        private val TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce"
        )
    }

    /** v2.8 模块四：单个源的诊断状态 */
    enum class SourceStatus { OK, EMPTY_SHELL, HTTP_403, TIMEOUT, NETWORK, PARSE }

    /** v2.8 模块四：单源搜索结果明细（供状态栏展开展示） */
    data class SourceOutcome(
        val sourceId: String,
        val status: SourceStatus,
        val count: Int = 0,
        val detail: String = ""
    )

    /** 网络层异常：携带 HTTP 状态码，供诊断区分 403/超时 */
    class FetchException(val statusCode: Int, message: String) : Exception(message)

    data class SearchReport(
        val results: List<Result>,
        val okSources: List<String>,     // 成功返回结果的源
        val failedSources: List<String>,  // 网络/解析失败的源（自动跳过）
        val outcomes: List<SourceOutcome> = emptyList()  // v2.8：每源明细
    )

    /** 聚合核心（纯函数）：输入 = 源 → 该源抓到的原始条目列表 */
    fun aggregate(rawBySource: Map<String, List<MagnetParsers.RawItem>>): List<Result> {
        val byHash = LinkedHashMap<String, MutableList<MagnetParsers.RawItem>>()
        val srcByHash = LinkedHashMap<String, MutableList<String>>()
        for ((srcId, items) in rawBySource) {
            for (item in items) {
                // 回归修复点：非法 hash 过滤；大小写不统一 hash 归一化后再去重合并
                if (!MagnetLinks.isHexHash(item.hash)) continue
                val key = item.hash.lowercase()
                byHash.getOrPut(key) { mutableListOf() }.add(item)
                srcByHash.getOrPut(key) { mutableListOf() }.add(srcId)
            }
        }
        val out = ArrayList<Result>()
        for ((hash, items) in byHash) {
            // 同一 hash 多源命中：合并名称（取信息量最大的名称）、体积、做种数（取最大）
            val best = items.maxByOrNull { it.name.length } ?: continue
            val mergedSize = items.map { it.sizeBytes }.filter { it > 0 }.maxOrNull() ?: -1
            val mergedSeeders = items.map { it.seeders }.filter { it >= 0 }.maxOrNull() ?: -1
            val sources = srcByHash[hash]?.distinct()?.sorted() ?: emptyList()
            out.add(
                Result(
                    hash = hash,
                    name = best.name.ifEmpty { "未命名资源" },
                    sizeBytes = mergedSize,
                    seeders = mergedSeeders,
                    sources = sources,
                    date = items.mapNotNull { it.date }.maxOrNull()
                )
            )
        }
        // 排序：来源数 ↓ → 做种数 ↓ → 体积 ↓ → 名称 A↑（稳定性）
        return out.sortedWith(
            compareByDescending<Result> { it.sourceCount }
                .thenByDescending { it.seeders.coerceAtLeast(0) }
                .thenByDescending { it.sizeBytes }
                .thenBy { it.name }
        )
    }

    /* ---------------- 网络层（可注入 fetcher 便于测试） ---------------- */

    fun interface Fetcher {
        /** 返回 HTML 字符串；失败抛异常 */
        fun fetch(url: String): String
    }

    private val pool: ExecutorService = Executors.newFixedThreadPool(6)

    /**
     * 并发搜索所有启用源。
     * @param keyword 关键词
     * @param sources 参与的源列表
     * @param fetcher 网络层
     * @param timeoutSecs 每源超时
     */
    fun search(
        keyword: String,
        sources: List<MagnetSource>,
        fetcher: Fetcher,
        timeoutSecs: Int = 10
    ): SearchReport {
        if (keyword.isBlank() || sources.isEmpty()) return SearchReport(emptyList(), emptyList(), emptyList())
        val futures = LinkedHashMap<String, Future<List<MagnetParsers.RawItem>>>()
        for (s in sources) {
            futures[s.id] = pool.submit(Callable {
                val html = fetcher.fetch(s.urlFor(keyword))
                MagnetParsers.parse(s.parser, html)
            })
        }
        val rawBySource = LinkedHashMap<String, List<MagnetParsers.RawItem>>()
        val ok = ArrayList<String>()
        val failed = ArrayList<String>()
        val outcomes = ArrayList<SourceOutcome>()
        for ((id, fut) in futures) {
            try {
                val items = fut.get(timeoutSecs.toLong(), TimeUnit.SECONDS)
                rawBySource[id] = items
                ok.add(id)
                // v2.8：成功但 0 条 → 空壳页（JS 渲染/反爬），供 UI 提示走离屏抓取兜底
                outcomes.add(SourceOutcome(id, if (items.isEmpty()) SourceStatus.EMPTY_SHELL else SourceStatus.OK,
                    items.size))
            } catch (e: Exception) {
                fut.cancel(true) // 超时后中断抓取线程，避免失效源长期占用线程池
                failed.add(id) // 失效源自动跳过
                outcomes.add(SourceOutcome(id, classifyFailure(e), 0, e.message ?: ""))
            }
        }
        return SearchReport(aggregate(rawBySource), ok, failed, outcomes)
    }

    /**
     * v2.8：合并主搜索报告与离屏 WebView 兜底抓到的结果（纯函数，可单测）。
     * 按 hash 去重，来源/做种/体积取并集与最大值。
     */
    fun mergeFallback(base: SearchReport, fallbackRaw: Map<String, List<MagnetParsers.RawItem>>): SearchReport {
        if (fallbackRaw.isEmpty()) return base
        val extra = aggregate(fallbackRaw)
        if (extra.isEmpty()) return base
        val byHash = LinkedHashMap<String, Result>()
        for (r in base.results) byHash[r.hash] = r
        var added = 0
        for (r in extra) {
            val old = byHash[r.hash]
            if (old == null) {
                byHash[r.hash] = r
                added++
            } else {
                byHash[r.hash] = old.copy(
                    sources = (old.sources + r.sources).distinct().sorted(),
                    seeders = maxOf(old.seeders, r.seeders),
                    sizeBytes = maxOf(old.sizeBytes, r.sizeBytes)
                )
            }
        }
        val newOutcomes = base.outcomes.toMutableList()
        for ((id, items) in fallbackRaw) {
            val idx = newOutcomes.indexOfFirst { it.sourceId == id }
            if (items.isNotEmpty()) {
                val upd = SourceOutcome(id, SourceStatus.OK, items.size, "离屏抓取")
                if (idx >= 0) newOutcomes[idx] = upd else newOutcomes.add(upd)
            }
        }
        val newOk = base.okSources.toMutableList()
        for (id in fallbackRaw.keys) if (id !in newOk) newOk.add(id)
        return SearchReport(
            results = byHash.values.toList(),
            okSources = newOk,
            failedSources = base.failedSources.filter { it !in fallbackRaw.keys },
            outcomes = newOutcomes
        )
    }

    /** 异常 → 诊断状态（纯函数，可单测） */
    fun classifyFailure(t: Throwable): SourceStatus {
        var e: Throwable? = t
        while (e != null) {
            when (e) {
                is java.util.concurrent.TimeoutException -> return SourceStatus.TIMEOUT
                is java.net.SocketTimeoutException -> return SourceStatus.TIMEOUT
                is FetchException -> return if (e.statusCode == 401 || e.statusCode == 403)
                    SourceStatus.HTTP_403 else SourceStatus.NETWORK
            }
            e = e.cause
        }
        return SourceStatus.NETWORK
    }

    /** 默认 HttpURLConnection 抓取（桌面 UA + gzip 支持 + 编码探测） */
    fun defaultFetcher(): Fetcher = Fetcher { url ->
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36"
            )
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            // v2.8：显式检查状态码，403/401 报给诊断层，不再笼统当"网络失败"
            val code = conn.responseCode
            if (code >= 400) throw FetchException(code, "HTTP $code")
            val enc = conn.contentEncoding ?: ""
            val stream = if (enc.contains("gzip")) {
                java.util.zip.GZIPInputStream(conn.inputStream)
            } else conn.inputStream
            val bytes = stream.use { it.readBytes() }
            val charset = Regex("""charset=([\w-]+)""")
                .find(conn.contentType ?: "")?.groupValues?.get(1) ?: "UTF-8"
            String(bytes, charset(charset))
        } finally {
            conn.disconnect()
        }
    }

    private fun charset(name: String) = try {
        java.nio.charset.Charset.forName(name)
    } catch (e: Exception) {
        java.nio.charset.StandardCharsets.UTF_8
    }
}
