package com.swift.download.bt

import com.swift.download.Format

/**
 * v2.9.1：BT 引擎自检纯逻辑（零 Android 依赖，JVM 单测覆盖）。
 *
 * 目的：用合法开源/公有领域种子验证当前网络下 jlibtorrent 2.1.2.1 是否畅通。
 * 核心判据不是"下载完成"，而是能否看到真实诊断数字：DHT 节点、Tracker、已连节点、速度。
 * 输入支持 magnet、40 位 infohash、.torrent 链接三种，默认预置公有领域种子，
 * 用户也可粘贴任意种子，避免内置种子失效后功能不可用。
 */
object BtSelfTestRules {

    /** 公有领域开源测试种子：Big Buck Bunny（Blender Foundation，CC-BY 3.0），archive.org 官方镜像 */
    const val DEFAULT_TEST_TORRENT_URL =
        "https://archive.org/download/BigBuckBunny_328/BigBuckBunny_328.torrent"
    const val DEFAULT_TEST_LABEL = "Big Buck Bunny（开源电影 · CC-BY）"

    private val MAGNET_HASH = Regex(
        """(?:xt=urn:btih:)([0-9a-fA-F]{40}|[A-Z2-7]{32})""", RegexOption.IGNORE_CASE
    )

    enum class InputKind { MAGNET, HASH, TORRENT_URL, INVALID }

    data class ParsedTestInput(
        val kind: InputKind,
        /** magnet/hash 场景可直接用于 session.find 的 40 位 hex；.torrent URL 场景为 null */
        val hash: String?,
        /** 原始有效输入（magnet 或 .torrent URL） */
        val value: String,
        val error: String?
    ) {
        val valid: Boolean get() = error == null && kind != InputKind.INVALID
    }

    /** 识别三种输入格式并给出机器可读结果 */
    fun parseInput(raw: String): ParsedTestInput {
        val s = raw.trim()
        if (s.isEmpty()) return ParsedTestInput(
            InputKind.INVALID, null, s, "请输入 magnet 链接、40 位 infohash 或 .torrent 地址"
        )
        if (s.startsWith("magnet:", true)) {
            val h = MAGNET_HASH.find(s)?.groupValues?.get(1)
            val hex = when {
                h == null -> null
                h.length == 40 -> h.lowercase()
                else -> null // base32 infohash 由运行时 TorrentEngine 处理，这里无法转 hex
            }
            return ParsedTestInput(
                InputKind.MAGNET, hex, s,
                if (hex == null) "magnet 中未找到可识别的 40 位 infohash（base32 种子请改用 .torrent 链接）" else null
            )
        }
        if (s.matches(Regex("[0-9a-fA-F]{40}"))) {
            val hex = s.lowercase()
            return ParsedTestInput(InputKind.HASH, hex, "magnet:?xt=urn:btih:$hex", null)
        }
        if (s.startsWith("http://", true) || s.startsWith("https://", true)) {
            if (s.substringBefore('?').endsWith(".torrent", true)) {
                return ParsedTestInput(InputKind.TORRENT_URL, null, s, null)
            }
            return ParsedTestInput(InputKind.INVALID, null, s, "该链接不是 .torrent 地址")
        }
        return ParsedTestInput(InputKind.INVALID, null, s, "无法识别的输入，请粘贴 magnet / 40 位 hash / .torrent 地址")
    }

    /** UI 展示用的诊断快照（与平台类型解耦） */
    data class Diagnostics(
        val dhtNodes: Long = 0,
        val trackerCount: Int = 0,
        val peers: Int = 0,
        val seeds: Int = 0,
        val speedBps: Long = 0,
        val stateLabel: String = "",
        val trackerError: String = "",
        val hasMetadata: Boolean = false
    )

    /** 格式化为任务卡同款诊断文案，0 值也如实显示 */
    fun format(d: Diagnostics): String = buildString {
        append("DHT节点 ").append(d.dhtNodes)
        append(" · Tracker ").append(d.trackerCount)
        append(" · 已连节点 ").append(d.peers)
        append(" · 做种 ").append(d.seeds)
        append(" · 速度 ").append(Format.speed(d.speedBps))
        if (d.stateLabel.isNotBlank()) append('\n').append("状态：").append(d.stateLabel)
        if (d.trackerError.isNotBlank()) append('\n').append("Tracker：").append(d.trackerError)
    }

    /** 只要任一连接指标 > 0 即视为引擎可达（冷门种子不要求元数据到达） */
    fun isReachable(d: Diagnostics): Boolean =
        d.dhtNodes > 0 || d.peers > 0 || d.trackerCount > 0 || d.hasMetadata

    /** 按运行时长给出分层提示，避免用户对着 0 干等 */
    fun hint(d: Diagnostics, elapsedSeconds: Int): String = when {
        isReachable(d) -> "BT 引擎工作正常：已连上 DHT/Tracker/节点。冷门种子元数据可能仍需等待。"
        elapsedSeconds < 5 -> "正在初始化 BT 会话，请稍候…"
        d.dhtNodes == 0L && d.trackerCount == 0 ->
            "未发现任何节点：请检查网络/代理，或确认 App 有联网权限。"
        else -> "DHT 有响应但暂未连到对等节点；冷门资源属正常现象，可换热门种子复测。"
    }
}
