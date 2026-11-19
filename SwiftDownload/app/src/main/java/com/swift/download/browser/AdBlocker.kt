package com.swift.download.browser

/**
 * v2.7 Stage3.1：内置浏览器轻量广告拦截与防恶意跳转（纯 Kotlin，无 Android 依赖，JVM 单测覆盖）。
 *
 * 设计原则：
 *  - 只拦截明确的广告/统计/劫持域名与 URL 模式，绝不误伤正常资源（保持浏览器可用性）。
 *  - 自定义规则由用户在设置里追加：纯域名 = 后缀匹配；含协议 = URL 子串匹配；# 开头为注释。
 *  - 导航 scheme 白名单严格限制为 http/https/magnet/about，拦截影视站自动拉起第三方 App。
 */
object AdBlocker {

    /** 内置广告/统计/监控常见域名（精确或子域后缀匹配） */
    val BUILTIN_HOSTS: Set<String> = setOf(
        "google-analytics.com", "googletagmanager.com", "doubleclick.net",
        "googleadservices.com", "googlesyndication.com", "googleadservice.com",
        "admob.com", "adservice.google.com",
        "facebook.net", "connect.facebook.net", "pixel.facebook.com",
        "scorecardresearch.com", "quantserve.com", "hotjar.com",
        "cnzz.com", "umeng.com", "friendfeed.com", "mixpanel.com",
        "taboola.com", "outbrain.com", "criteo.com", "pubmatic.com",
        "adnxs.com", "rubiconproject.com", "casalemedia.com", "openx.net",
        "zedo.com", "brightroll.com", "moatads.com", "adsrvr.org"
    )

    /** 内置明确广告 URL 模式（子串匹配，谨慎以免误杀内容） */
    private val BUILTIN_URL_PATTERNS: List<String> = listOf(
        "/popup.", "/popunder", "/adframe", "/ads.js", "/advert.", "/banner."
    )

    /** 允许的导航协议（防恶意跳转白名单） */
    private val ALLOWED_SCHEMES = listOf("http://", "https://", "magnet:", "about:")

    /**
     * 从 URL 提取小写 host（去 userinfo / 端口 / 路径）。
     * 无 host（如 about:home、magnet:）返回空串。
     */
    fun hostOf(url: String): String {
        var s = url.trim()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        else if (s.startsWith("//")) s = s.substring(2) // 协议相对 URL
        else {
            // 无 // 的协议（magnet:/about:）没有 host
            if (s.contains(':')) return ""
        }
        val slash = s.indexOfAny(charArrayOf('/', '?', '#'))
        if (slash >= 0) s = s.substring(0, slash)
        val at = s.lastIndexOf('@')
        if (at >= 0) s = s.substring(at + 1)
        val colon = s.indexOf(':')
        if (colon >= 0) s = s.substring(0, colon)
        return s.lowercase()
    }

    /** host 精确或子域匹配：rule=doubleclick.net 命中 a.doubleclick.net，但不命中 notdoubleclick.net */
    fun hostMatches(host: String, rule: String): Boolean {
        if (host.isEmpty() || rule.isEmpty()) return false
        // 兼容 Adblock Plus 的 ||domain 锚点写法
        val r = rule.lowercase().trim().removePrefix("||").trimStart('.')
        return host == r || host.endsWith(".$r")
    }

    /** 规范化自定义规则文本：逐行 trim、去 # 注释与空行、去重保序 */
    fun normalizeRules(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            out.add(t)
        }
        return out.toList()
    }

    /** 单条自定义规则是否命中 URL */
    fun customMatch(url: String, rule: String): Boolean {
        if (rule.isEmpty()) return false
        // 含协议或斜杠：当作 URL 子串匹配（忽略大小写）
        if (rule.contains("://") || rule.contains('/')) {
            return url.contains(rule, ignoreCase = true)
        }
        // 纯域名：host 后缀匹配
        return hostMatches(hostOf(url), rule)
    }

    /** 是否应拦截该资源/请求（内置规则 + 自定义规则） */
    fun isBlocked(url: String, customRules: List<String> = emptyList()): Boolean {
        if (url.isEmpty()) return false
        val host = hostOf(url)
        if (host.isNotEmpty() && BUILTIN_HOSTS.any { hostMatches(host, it) }) return true
        val lower = url.lowercase()
        if (BUILTIN_URL_PATTERNS.any { lower.contains(it) }) return true
        return customRules.any { customMatch(url, it) }
    }

    /** 导航协议是否在白名单内（http/https/magnet/about） */
    fun isAllowedScheme(url: String): Boolean {
        val lower = url.trim().lowercase()
        return ALLOWED_SCHEMES.any { lower.startsWith(it) }
    }

    /* ---------------- v2.8 模块三：DOM 注入去悬浮广告 ---------------- */

    /**
     * 轻度隐藏规则：常见的固定定位广告容器 / 广告联盟 / 全屏透明劫持层。
     * 选择器刻意保守，避免误伤视频播放器与分页导航。
     */
    val LIGHT_HIDE_SELECTORS = listOf(
        "ins.adsbygoogle",
        "iframe[src*='adsbygoogle']",
        "iframe[src*='doubleclick']",
        "div[class*='advert']",
        "div[id*='advert']"
    )

    /** 强力模式追加：极大 z-index 的固定悬浮层 / 常见中文站悬浮广告类名 */
    val STRONG_HIDE_SELECTORS = listOf(
        "div[class*='popup']", "div[id*='popup']",
        "div[class*='float-ad']", "div[class*='floating']",
        "div[style*='position: fixed'][style*='z-index: 999']",
        "a[href^='intent://']", "a[href^='baiduboxapp://']", "a[href^='thunder://']"
    )

    /**
     * 生成注入到页面的 JS（纯逻辑，JVM 单测覆盖生成的字符串包含预期选择器）。
     * 用一次性 <style> 标签隐藏匹配元素；强力模式才追加激进选择器；
     * 同时对 intent/baiduboxapp/thunder 等非下载类广告跳转做点击拦截并轻提示。
     */
    fun domInjectionJs(strong: Boolean): String {
        val selectors = if (strong) LIGHT_HIDE_SELECTORS + STRONG_HIDE_SELECTORS else LIGHT_HIDE_SELECTORS
        val css = selectors.joinToString(",") { "$it{display:none!important}" }
        return buildString {
            append("(function(){")
            append("var s=document.createElement('style');")
            append("s.textContent=").append(jsString(css)).append(";")
            append("document.head&&document.head.appendChild(s);")
            // 点击劫持防御：拦截拉起第三方 App 的广告链接（magnet 与 http 正常放行）
            append("document.addEventListener('click',function(e){")
            append("var a=e.target.closest&&e.target.closest('a[href]');")
            append("if(!a)return;var h=a.getAttribute('href')||'';")
            append("if(/^(intent:|baiduboxapp:|thunder:)/i.test(h)){e.preventDefault();")
            append("if(window.AndroidAdBlock)window.AndroidAdBlock.onBlocked('已拦截广告跳转');}")
            append("},true);")
            append("})();")
        }
    }

    /** JS 字符串字面量转义（纯逻辑） */
    fun jsString(s: String): String {
        val sb = StringBuilder(s.length + 2).append('\'')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(c)
            }
        }
        return sb.append('\'').toString()
    }
}
