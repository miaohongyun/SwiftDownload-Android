package com.swift.download.magnet

/**
 * 磁力链接解析与 infohash 归一化（纯 Kotlin，单元测试覆盖）。
 *  - 支持 hex（40 位）与 base32（32 位）两种 btih 编码
 *  - 非法 hash（长度不符 / 非法字符）返回 null —— 聚合前过滤（v2.0 回归修复点）
 */
object MagnetLinks {

    data class Parsed(
        val hashRaw: String,
        val hashNormalized: String, // 40 位小写十六进制
        val name: String,
        val sizeBytes: Long         // xl 参数，-1 表示未知
    ) {
        val magnetUri: String get() = "magnet:?xt=urn:btih:$hashNormalized" +
            (if (name.isNotEmpty()) "&dn=${urlEncode(name)}" else "")
    }

    fun parse(uri: String): Parsed? {
        if (!uri.startsWith("magnet:?", true)) return null
        val q = uri.substringAfter('?', "")
        val params = splitParams(q)
        val xt = params["xt"] ?: return null
        if (!xt.startsWith("urn:btih:", true)) return null
        // 修复点：substringAfter(':') 会停在 "urn"，必须取最后一个冒号后的部分
        val hashRaw = xt.substringAfterLast(':')
        val norm = normalizeHash(hashRaw) ?: return null
        val dn = params["dn"] ?: ""
        val xl = params["xl"]?.toLongOrNull() ?: -1L
        return Parsed(hashRaw, norm, dn, xl)
    }

    /** hex → 40 位小写；base32 → 解码为 20 字节 hex；非法返回 null */
    fun normalizeHash(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return when (s.length) {
            40 -> if (s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) s.lowercase() else null
            32 -> base32ToHex(s)
            else -> null
        }
    }

    private fun splitParams(q: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            out[k] = try { urlDecode(v) } catch (e: Exception) { v }
        }
        return out
    }

    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun base32ToHex(s: String): String? {
        val up = s.uppercase()
        if (up.length != 32) return null
        if (!up.all { it in B32 }) return null
        var bits = 0
        var value = 0L
        val bytes = ArrayList<Int>()
        for (c in up) {
            value = (value shl 5) or B32.indexOf(c).toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                bytes.add(((value shr bits) and 0xFFL).toInt())
            }
        }
        if (bytes.size != 20) return null
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isHexHash(s: String): Boolean =
        s.length == 40 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    private fun urlDecode(s: String): String =
        java.net.URLDecoder.decode(s, "UTF-8")
}
