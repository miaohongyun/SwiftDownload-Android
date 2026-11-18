package com.swift.download

import com.swift.download.player.M3u8
import com.swift.download.player.VideoSniff
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一次性真实世界链路探针（非长期回归用例，依赖外网）：
 *  1. 真实 m3u8 master → M3u8.parse → 清晰度列表/选档（复刻 BrowserPage.showVideoSheet 的抓取路径）
 *  2. 变体 media playlist → 分片解析为绝对地址
 *  3. VideoSniff.isCandidate 对各平台真实 URL 形态的判定（B站 DASH / 抖音 tosvod / 百度 / 通用）
 *  4. 剪贴板识别条件对各类"复制内容"的判定（复刻 TransfersPage.checkClipboard 的条件）
 */
class RealWorldProbeTest {

    private fun httpGet(url: String, referer: String = ""): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
        if (referer.isNotBlank()) conn.setRequestProperty("Referer", referer)
        try {
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 离线保护：外网不可达时跳过（真实链路测试依赖网络） */
    private fun assumeOnline(url: String) {
        val ok = try {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 3000; c.readTimeout = 3000
            c.requestMethod = "HEAD"
            val rc = c.responseCode
            c.disconnect()
            rc in 200..499
        } catch (e: Exception) { false }
        org.junit.Assume.assumeTrue("外网不可达，跳过真实链路测试", ok)
    }

    @Test
    fun `真实m3u8主播放列表_解析清晰度并选档`() {
        assumeOnline("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
        val masterUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        val text = httpGet(masterUrl)
        val pl = M3u8.parse(masterUrl, text)
        println("[1] master 解析: isMaster=${pl.isMaster}, 变体数=${pl.variants.size}")
        pl.variants.forEach { println("    档位: ${it.label} -> ${it.url}") }
        assert(pl.isMaster) { "应为 master playlist" }
        assert(pl.variants.isNotEmpty()) { "应有清晰度变体" }
        val best = M3u8.pickBestUrl(pl)
        println("[1] 自动选最高档: $best")
        assert(best != null && best.startsWith("http"))

        // 复刻 showVideoSheet：选定档后拉 media playlist
        val mediaText = httpGet(best!!)
        val media = M3u8.parse(best, mediaText)
        println("[2] media 解析: 分片数=${media.segments.size}, isMaster=${media.isMaster}")
        media.segments.take(3).forEach { println("    分片: $it") }
        assert(!media.isMaster) { "变体应为 media playlist" }
        assert(media.segments.size >= 3) { "应有分片" }
        assert(media.segments.first().startsWith("http")) { "分片应为绝对地址" }
    }

    @Test
    fun `VideoSniff对各平台真实URL形态的判定`() {
        assumeOnline("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
        // (url, 平台说明) —— 全部取自真实站点典型资源形态
        val samples = listOf(
            "https://ts3.xxx.site/20240801/720p/playlist.m3u8?sign=abc" to "通用m3u8(带签名query)",
            "https://example.com/videos/movie_1080p.mp4?token=x" to "通用mp4(带token)",
            "https://cn-example.bilivideo.com/upgcxcode/xx/nn/vid30080m4s.mp4?e=ot&sign=x" to "B站DASH视频轨(.m4s伪装mp4路径)",
            "https://cn-example.bilivideo.com/upgcxcode/xx/nn/vid.m4s?e=ot" to "B站DASH(m4s)",
            "https://v.douyin.com/remix/xxx/video/tos/cn/tos-cn-ve-2730/oQxGfDAhRCCfhb.mp4?s=packed" to "抖音tosvod mp4",
            "https://www.bilibili.com/video/BV1GJ411x7h7" to "B站页面链接(用户复制场景)",
            "https://v.douyin.com/iL3nMhf/" to "抖音分享短链(用户复制场景)",
            "https://pan.baidu.com/s/1abcXYZ" to "百度网盘分享页",
            "https://www.example.com/index.m3u8" to "m3u8",
            "https://www.example.com/hls/seg-001.ts" to "ts分片(应排除)",
            "https://www.example.com/dash/vid.m4s" to "m4s分片(应排除)"
        )
        for ((url, desc) in samples) {
            val v = VideoSniff.isCandidate(url)
            println("[3] $desc -> isCandidate=$v  ($url)")
        }
        // 断言关键结论
        assert(VideoSniff.isCandidate("https://a.com/v/playlist.m3u8?sign=1")) { "带query的m3u8应命中" }
        assert(VideoSniff.isCandidate("https://a.com/v/movie.mp4?token=1")) { "带query的mp4应命中" }
        assert(!VideoSniff.isCandidate("https://a.com/dash/vid.m4s")) { "m4s应被排除(B站DASH抓不到的原因)" }
        assert(!VideoSniff.isCandidate("https://www.bilibili.com/video/BV1xx")) { "页面链接不是媒体直链" }
        // 文件名推断
        println("[3] displayName: ${VideoSniff.displayName("https://a.com/path/%E7%94%B5%E5%BD%B1.mp4?x=1")}")
    }

    @Test
    fun `剪贴板识别条件对真实复制内容的判定`() {
        assumeOnline("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
        // 复刻 TransfersPage.checkClipboard 的识别条件
        fun clipboardRecognized(text: String): Boolean =
            text.trim().startsWith("http://") || text.trim().startsWith("https://")
        val samples = listOf(
            "https://example.com/movie.mp4" to "直链mp4",
            "magnet:?xt=urn:btih:abcdef&dn=test" to "磁力链接",
            "8.92 pQg:/ 复制打开抖音，看看【某人】的作品 https://v.douyin.com/iL3nMhf/ 复制此链接" to "抖音分享文本",
            "【标题】https://www.bilibili.com/video/BV1xx411c7mD?share_source=copy_web" to "B站分享文本",
            "https://www.bilibili.com/video/BV1GJ411x7h7" to "纯B站链接",
            "https://pan.baidu.com/s/1abc?pwd=1234 提取码: 1234" to "百度网盘文本"
        )
        for ((text, desc) in samples) {
            val v = clipboardRecognized(text)
            println("[4] $desc -> 识别=$v")
        }
        assert(clipboardRecognized("https://example.com/movie.mp4"))
        assert(!clipboardRecognized("magnet:?xt=urn:btih:abcdef")) { "磁力链接不被剪贴板识别" }
        assert(!clipboardRecognized("8.92 pQg:/ 复制打开抖音 https://v.douyin.com/iL3nMhf/")) { "分享文本不被识别" }
    }
}
