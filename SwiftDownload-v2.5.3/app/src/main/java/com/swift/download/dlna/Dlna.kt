package com.swift.download.dlna

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.swift.download.ui.Ui
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.2 新增：DLNA 投屏。
 *  - SSDP M-SEARCH 发现局域网 MediaRenderer（UDP 239.255.255.250:1900）
 *  - 解析设备描述 XML（friendlyName + AVTransport controlURL，相对地址解析为绝对）
 *  - SOAP SetAVTransportURI + Play（把本地流服务器地址推给电视/盒子）
 *  - 纯函数（parseControlUrl / parseFriendlyName / buildSetUriSoap / escapeXml）JVM 单测覆盖
 */
object Dlna {

    data class Device(val name: String, val controlUrl: String)

    /** 设备发现（阻塞 timeoutMs，宿主放 IO 线程调用） */
    fun discover(timeoutMs: Long = 3000): List<Device> {
        val locations = LinkedHashSet<String>()
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket()
            sock.soTimeout = 500
            val msearch = (
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
                ).toByteArray()
            val target = InetAddress.getByName("239.255.255.250")
            repeat(3) {
                sock.send(DatagramPacket(msearch, msearch.size, target, 1900))
            }
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length, Charsets.ISO_8859_1)
                    val loc = text.lineSequence()
                        .firstOrNull { it.lowercase().startsWith("location:") }
                        ?.substringAfter(':')?.trim()
                    if (loc != null && loc.startsWith("http")) locations.add(loc)
                } catch (e: java.net.SocketTimeoutException) {
                    // 继续等到 deadline
                }
            }
        } catch (e: Exception) {
            // 无网络 / 无设备
        } finally {
            try { sock?.close() } catch (e: Exception) {}
        }
        val out = ArrayList<Device>()
        for (loc in locations) {
            try {
                val desc = fetchText(loc, 2500) ?: continue
                val name = parseFriendlyName(desc) ?: continue
                val control = parseControlUrl(desc, loc) ?: continue
                out.add(Device(name, control))
            } catch (e: Exception) {
                // 单个设备失败忽略
            }
        }
        return out
    }

    /** 把播放地址推给渲染器并播放 */
    fun cast(dev: Device, url: String): Boolean = try {
        soap(dev.controlUrl, "SetAVTransportURI", buildSetUriSoap(url)) &&
            soap(dev.controlUrl, "Play", PLAY_SOAP)
    } catch (e: Exception) {
        false
    }

    fun stop(dev: Device): Boolean = try {
        soap(dev.controlUrl, "Stop", STOP_SOAP)
    } catch (e: Exception) {
        false
    }

    /* ---------------- 纯函数（单测覆盖） ---------------- */

    /** 从设备描述 XML 提取 AVTransport 的 controlURL（相对路径按设备描述地址解析为绝对） */
    fun parseControlUrl(xml: String, descriptionUrl: String): String? {
        // 找到包含 AVTransport serviceType 的 <service> 块
        val serviceBlocks = Regex("<service\\b[^>]*>(.*?)</service>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }.toList()
        val av = serviceBlocks.firstOrNull {
            it.contains("urn:schemas-upnp-org:service:AVTransport")
        } ?: return null
        val rel = Regex("<controlURL>\\s*(.*?)\\s*</controlURL>").find(av)?.groupValues?.get(1)
            ?: return null
        return absolutize(rel.trim(), descriptionUrl)
    }

    fun parseFriendlyName(xml: String): String? =
        Regex("<friendlyName>\\s*(.*?)\\s*</friendlyName>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    fun buildSetUriSoap(url: String): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body>")
        append("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">")
        append("<InstanceID>0</InstanceID>")
        append("<CurrentURI>").append(escapeXml(url)).append("</CurrentURI>")
        append("<CurrentURIMetaData></CurrentURIMetaData>")
        append("</u:SetAVTransportURI></s:Body></s:Envelope>")
    }

    private val PLAY_SOAP = buildString {
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">")
        append("<InstanceID>0</InstanceID><Speed>1</Speed>")
        append("</u:Play></s:Body></s:Envelope>")
    }

    private val STOP_SOAP = buildString {
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">")
        append("<InstanceID>0</InstanceID>")
        append("</u:Stop></s:Body></s:Envelope>")
    }

    fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    /** 相对 URL → 绝对（含 host+port 修正；不合法返回 null） */
    fun absolutize(rel: String, baseUrl: String): String? {
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
        return try {
            URL(URL(baseUrl), rel).toString()
        } catch (e: Exception) {
            null
        }
    }

    /* ---------------- 网络动作 ---------------- */

    private fun fetchText(url: String, timeoutMs: Int): String? {
        val conn = URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            if (conn.responseCode != 200) return null
            conn.inputStream.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                    r.readText()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun soap(controlUrl: String, action: String, body: String): Boolean {
        val conn = URL(controlUrl).openConnection() as java.net.HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 4000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty(
                "SOAPACTION",
                "\"urn:schemas-upnp-org:service:AVTransport:1#$action\""
            )
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            code in 200..299
        } finally {
            conn.disconnect()
        }
    }

    /** 本机局域网 IPv4（投屏地址用；无局域网返回 null） */
    fun lanIp(): String? {
        return try {
            val en = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in en) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.interfaceAddresses) {
                    val a = addr.address
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress) {
                        val host = a.hostAddress ?: continue
                        if (host.startsWith("192.168.") || host.startsWith("10.") ||
                            host.startsWith("172.")
                        ) return host
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 投屏设备选择对话框：搜索（IO 线程）→ 列表 → 点选投出；已投出后可「停止投屏」。
 * urlProvider 在设备选定后才调用（此时才启动本地文件服务器，避免提前占端口）。
 */
@SuppressLint("ViewConstructor")
class DlnaPicker(
    private val ctx: Context,
    private val title: String,
    private val urlProvider: () -> String?,
    /** 停止投屏时释放本地文件服务（否则服务器会一直挂在后台等空闲回收） */
    private val onStopCasting: () -> Unit = {}
) {
    private val main = Handler(Looper.getMainLooper())
    private var devices: List<Dlna.Device> = emptyList()
    private var casted: Dlna.Device? = null
    private var dialog: AlertDialog? = null
    private var listHost: LinearLayout? = null
    private var statusView: TextView? = null
    private val stopped = AtomicBoolean(false)

    fun show() {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 12f), Ui.dp(ctx, 20f), Ui.dp(ctx, 4f))
        }
        val status = Ui.text(ctx, "正在搜索投屏设备…\n请确保手机与电视/盒子在同一 Wi-Fi", 13f, Ui.TXT_SUB)
        status.gravity = Gravity.CENTER
        container.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = Ui.dp(ctx, 12f)
        })
        dialog = AlertDialog.Builder(ctx)
            .setTitle("投屏 · $title")
            .setView(container)
            .setNegativeButton("取消", null)
            .show()
        listHost = container
        statusView = status
        Thread {
            val found = Dlna.discover(3000)
            if (stopped.get()) return@Thread
            main.post { devices = found; renderList(container, status) }
        }.apply { isDaemon = true }.start()
    }

    fun dismiss() {
        stopped.set(true)
        dialog?.dismiss()
    }

    @SuppressLint("SetTextI18n")
    private fun renderList(container: LinearLayout, status: TextView) {
        // 重新渲染时先清掉旧行（第 0 个是状态文本），否则设备列表会重复叠加
        while (container.childCount > 1) container.removeViewAt(container.childCount - 1)
        status.visibility = View.GONE
        if (devices.isEmpty()) {
            status.visibility = View.VISIBLE
            status.text = "未找到投屏设备\n\n· 手机与电视需在同一 Wi-Fi\n· 电视需支持 DLNA（投屏/多屏互动）\n· 部分电视需手动打开 DLNA 功能"
            return
        }
        for (dev in devices) {
            val row = Ui.card(ctx, 12f)
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(Ui.iconBadge(ctx, "TV", Ui.BLUE, 8f))
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(Ui.text(ctx, dev.name, 15f, Ui.TXT, bold = true))
            line.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(ctx, 10f)
            })
            line.addView(Ui.pillBtn(ctx, if (casted == dev) "投屏中" else "投屏", Ui.BLUE, filled = casted == dev))
            row.addView(line)
            row.setOnClickListener {
                castDevice(dev)
            }
            container.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(ctx, 8f)
            })
        }
        if (casted != null) {
            val stop = Ui.pillBtn(ctx, "停止投屏（恢复电视原画面）", Ui.RED)
            container.addView(stop, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            stop.setOnClickListener {
                casted?.let { d -> Thread { Dlna.stop(d) }.start() }
                casted = null
                onStopCasting()
                val host = listHost
                val statusView = statusView
                if (host != null && statusView != null) renderList(host, statusView) else container.removeView(stop)
                Ui.toast(ctx, "已发送停止指令")
            }
        }
    }

    private fun castDevice(dev: Dlna.Device) {
        Thread {
            val url = urlProvider() ?: run {
                main.post { Ui.toast(ctx, "无法建立本地播放地址（检查文件是否存在）") }
                return@Thread
            }
            val ok = Dlna.cast(dev, url)
            main.post {
                if (ok) {
                    casted = dev
                    Ui.toast(ctx, "已投屏到「${dev.name}」，用电视遥控器控制播放")
                    // v2.5.2：投屏成功后不再关闭对话框 —— 之前直接 dismiss()，
                    // "停止投屏"按钮也随之消失，用户没有任何入口可以结束投屏
                    val host = listHost
                    val statusView = statusView
                    if (host != null && statusView != null) renderList(host, statusView)
                } else {
                    Ui.toast(ctx, "投屏指令被「${dev.name}」拒绝，换一台设备试试")
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
