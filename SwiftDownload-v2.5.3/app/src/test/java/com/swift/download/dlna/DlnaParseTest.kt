package com.swift.download.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.2：DLNA 解析纯函数单测（设备描述 XML → controlURL/friendlyName；SOAP 报文构造）。
 */
class DlnaParseTest {

    private val descXml = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <friendlyName>客厅的小米电视</friendlyName>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>/dev/123/upnp/control/AVTransport</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/dev/123/upnp/control/RenderingControl</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test fun `解析 friendlyName`() {
        assertEquals("客厅的小米电视", Dlna.parseFriendlyName(descXml))
    }

    @Test fun `相对 controlURL 解析为绝对`() {
        val url = Dlna.parseControlUrl(descXml, "http://192.168.1.50:49152/desc.xml")
        assertNotNull(url)
        assertEquals("http://192.168.1.50:49152/dev/123/upnp/control/AVTransport", url)
    }

    @Test fun `绝对 controlURL 原样保留`() {
        val xml = descXml.replace(
            "/dev/123/upnp/control/AVTransport",
            "http://192.168.1.50:49152/dev/123/upnp/control/AVTransport"
        )
        assertEquals(
            "http://192.168.1.50:49152/dev/123/upnp/control/AVTransport",
            Dlna.parseControlUrl(xml, "http://192.168.1.50:49152/desc.xml")
        )
    }

    @Test fun `无 AVTransport 返回 null`() {
        val xml = descXml.replace("AVTransport", "ContentDirectory")
        assertNull(Dlna.parseControlUrl(xml, "http://192.168.1.50/desc.xml"))
        assertNull(Dlna.parseControlUrl("garbage", "http://192.168.1.50/desc.xml"))
    }

    @Test fun `absolutize 相对与绝对`() {
        assertEquals(
            "http://192.168.1.50:8080/a/b",
            Dlna.absolutize("/a/b", "http://192.168.1.50:8080/desc.xml")
        )
        assertEquals(
            "http://x/y",
            Dlna.absolutize("http://x/y", "http://z/desc.xml")
        )
        assertNull(Dlna.absolutize("a b", "::::"))
    }

    @Test fun `SetAVTransportURI SOAP 包含转义地址`() {
        val soap = Dlna.buildSetUriSoap(
            "http://192.168.1.9:39521/file?x=1&y=2"
        )
        assertTrue(soap.contains("SetAVTransportURI"))
        assertTrue(soap.contains("http://192.168.1.9:39521/file?x=1&amp;y=2"))
        assertTrue(!soap.contains("1&y=2\"")) // 原样 & 不出现于 XML 文本节点
    }

    @Test fun `escapeXml 五类字符`() {
        assertEquals("&amp;&lt;&gt;&quot;&apos;", Dlna.escapeXml("&<>\"'"))
    }
}
