package com.swift.download.bt

/**
 * v2.7 Stage2.1：精选公开 Tracker 列表（纯逻辑，JVM 单测覆盖）。
 * 只收高可用、稳定的 UDP/HTTPS tracker，避免冷门磁力无法寻源。
 * 默认开启，用户可在设置里关闭。
 */
object PublicTrackers {

    /**
     * v2.9.0：HTTP / HTTPS / UDP 混合高可用 tracker（约 30 个）。
     * 国内运营商 5G 普遍丢弃/限流 UDP，因此必须同时带 http(s) tracker，
     * 否则裸磁力在移动网络下 DHT 有节点也 announce 不出去。
     */
    val TRACKERS: List<String> = listOf(
        // UDP：节点多、延迟低，Wi-Fi 下表现最好
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.bittor.pw:1337/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.theoks.net:6969/announce",
        "udp://tracker-udp.gbitt.info:80/announce",
        "udp://tracker.leechangel.free.fr:81/announce",
        "udp://open.demonii.com:1337/announce",
        // HTTPS：5G 可达性最好，优先保证
        "https://tracker.opentrackr.org:1337/announce",
        "https://tracker.torrent.eu.org:451/announce",
        "https://tracker.bittor.pw:1337/announce",
        "https://tracker.lantator.com:1337/announce",
        "https://tracker.novg.net:1337/announce",
        "https://tracker.moeking.me:6969/announce",
        "https://tracker.yemekyedim.com:6969/announce",
        "https://tracker.hdcmct.com:2710/announce",
        "https://tracker.0x.tf:6969/announce",
        "https://tracker.lilithraws.org:6969/announce",
        // HTTP：部分老客户端/运营商 HTTPS SNI 受阻时的兜底
        "http://tracker.openbittorrent.com:80/announce",
        "http://tracker.opentrackr.org:1337/announce",
        "http://bttrack.9apps.net:26971/announce",
        "http://tracker.internetwars.ru:2641/announce.php",
        "http://tracker.tiny-vps.com:6969/announce",
        "http://t.nyaatracker.com:80/announce",
        "wss://tracker.openwebtorrent.com"
    )

    /**
     * 给磁力 URI 追加 tracker。libtorrent 通过 `tr=&tr=` 查询参数识别。
     * - 已含任意 tracker（&tr= 或 ?tr=）时不重复追加，尊重原始种子自带 tracker
     * - 纯 infohash（40 位十六进制）自动补成 magnet:?xt=urn:btih:
     */
    fun appendToMagnet(uri: String, extra: List<String> = TRACKERS): String {
        val base = if (uri.startsWith("magnet:", true)) uri
        else if (uri.matches(Regex("[0-9a-fA-F]{40}"))) "magnet:?xt=urn:btih:$uri"
        else return uri
        if (base.contains("tr=", true)) return base
        if (extra.isEmpty()) return base
        val sep = if (base.contains("?")) "&" else "?"
        return base + sep + extra.joinToString("&") { "tr=" + java.net.URLEncoder.encode(it, "UTF-8") }
    }
}
