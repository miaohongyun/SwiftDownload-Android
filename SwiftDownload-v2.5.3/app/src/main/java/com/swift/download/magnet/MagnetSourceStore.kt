package com.swift.download.magnet

import android.content.Context

import org.json.JSONObject
import java.io.File

/**
 * 磁力源注册表：内置规则 + 用户覆盖（开关 / 地址），持久化到 files/magnet_sources.json。
 * 域名轮换时可在「磁力源管理」长按改地址，无需升级 App。
 */
class MagnetSourceStore private constructor(private val ctx: Context) {

    private val file: File get() = File(ctx.filesDir, "magnet_sources.json")

    fun sources(): List<MagnetSource> {
        val overrides = readOverrides()
        return MagnetSources.defaults.map { def ->
            val ov = overrides.optJSONObject(def.id)
            if (ov != null) {
                MagnetSource(
                    id = def.id, name = def.name,
                    searchUrl = ov.optString("searchUrl", def.searchUrl).ifBlank { def.searchUrl },
                    parser = def.parser,
                    enabled = ov.optBoolean("enabled", def.enabled)
                )
            } else def
        }
    }

    fun enabled(): List<MagnetSource> = sources().filter { it.enabled }

    fun setEnabled(id: String, enabled: Boolean) = mutate(id) { it.put("enabled", enabled) }

    fun setUrl(id: String, url: String) = mutate(id) {
        if (url.startsWith("http://") || url.startsWith("https://")) it.put("searchUrl", url)
    }

    fun resetAll() { file.delete() }

    private fun mutate(id: String, block: (JSONObject) -> Unit) {
        val root = readRoot()
        var ov = root.optJSONObject(id)
        if (ov == null) { ov = JSONObject(); root.put(id, ov) }
        block(ov)
        writeRoot(root)
    }

    private fun readOverrides(): JSONObject = readRoot()

    private fun readRoot(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) { JSONObject() }

    private fun writeRoot(root: JSONObject) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(root.toString())
        } catch (e: Exception) {
            // 忽略持久化失败（下轮重写）
        }
    }

    companion object {
        @Volatile private var inst: MagnetSourceStore? = null
        fun get(ctx: Context): MagnetSourceStore =
            inst ?: synchronized(this) {
                inst ?: MagnetSourceStore(ctx.applicationContext).also { inst = it }
            }
    }
}
