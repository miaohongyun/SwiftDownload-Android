package com.swift.download.player

import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v2.6.0：HLS AES-128 解密（纯 JDK javax.crypto，无第三方依赖）。
 *
 * 规范要点：
 *  - 密钥固定 16 字节；IV 显式给出时为 16 字节十六进制（可带 0x），
 *    未给出时用分片的 media sequence number 作为 128 位大端整数。
 *  - 分片密文是 PKCS7 填充（Java 的 PKCS5Padding 对 AES 即等价实现）。
 */
object M3u8Crypto {

    fun requireKey(bytes: ByteArray): ByteArray {
        if (bytes.size != 16) throw IOException("AES-128 密钥长度异常（${bytes.size} / 16 字节）")
        return bytes
    }

    /** 解析 IV：显式十六进制优先，否则用分片序号（大端 128 位） */
    fun ivFor(raw: String?, sequence: Long): ByteArray {
        if (!raw.isNullOrBlank()) {
            val hex = raw.removePrefix("0x").removePrefix("0X")
            if (hex.length == 32 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            }
            // 少数服务端直接写 16 个 ASCII 字符
            if (hex.length == 16) return hex.toByteArray(Charsets.US_ASCII)
        }
        val out = ByteArray(16)
        var value = sequence
        for (i in 15 downTo 0) {
            out[i] = (value and 0xFFL).toByte()
            value = value shr 8
        }
        return out
    }

    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        requireKey(key)
        if (iv.size != 16) throw IOException("AES-128 IV 长度异常（${iv.size} / 16 字节）")
        if (data.isEmpty() || data.size % 16 != 0) {
            throw IOException("AES-128 密文长度必须是 16 的整数倍（实际 ${data.size}）")
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** 仅测试使用的对称加密，方便端到端构造加密分片 */
    fun encryptForTest(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        requireKey(key)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }
}
