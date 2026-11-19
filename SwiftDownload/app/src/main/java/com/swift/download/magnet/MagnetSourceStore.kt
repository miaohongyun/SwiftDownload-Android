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
    /** v2.8 模块四：自定义源单独存（纯 Kotlin JSON，便于 JVM 单测往返） */
    private val customFile: File get() = File(ctx.filesDir, "magnet_custom_sources.json")

    fun sources(): List<MagnetSource> {
        val overrides = readOverrides()
        val builtins = MagnetSources.defaults.map { def ->
            val ov = overrides.optJSONObject(def.id)
            if (ov != null) {
                MagnetSource(
                    id = def.id, name = def.name,
                    searchUrl = ov.optString("searchUrl", def.searchUrl).ifBlank { def.searchUrl },
                    parser = def.parser,
                    enabled = ov.optBoolean("enabled", def.enabled),
                    // v2.9.0：覆盖地址/开关时保留「需代理」标注，避免老配置丢失标签
                    proxyRequired = def.proxyRequired
                )
            } else def.copy()
        }
        return builtins + customSources()
    }

    /** v2.8：读取用户自定义源 */
    fun customSources(): List<MagnetSource> = try {
        if (customFile.exists()) MagnetSourceRules.decodeCustom(customFile.readText()) else emptyList()
    } catch (e: Exception) { emptyList() }

    /**
     * v2.8：新增自定义源。返回 null 表示成功，否则为给用户看的校验错误。
     */
    fun addCustom(name: String, searchUrl: String, parser: String): String? {
        val err = MagnetSourceRules.validate(name, searchUrl, parser)
        if (err != null) return err
        val src = MagnetSource(
            id = MagnetSourceRules.makeId(searchUrl),
            name = name.trim(),
            searchUrl = searchUrl.trim(),
            parser = MagnetSourceRules.normalizeParser(parser),
            enabled = true
        )
        val list = customSources().filter { it.id != src.id } + src
        writeCustom(list)
        return null
    }

    /** v2.8：删除自定义源；内置源不允许删除（只能禁用） */
    fun removeCustom(id: String) {
        writeCustom(customSources().filter { it.id != id })
    }

    /** v2.8：整体替换自定义源列表（编辑名称/解析规则时保留原 id） */
    fun saveCustom(list: List<MagnetSource>) = writeCustom(list)

    fun enabled(): List<MagnetSource> = sources().filter { it.enabled }

    fun setEnabled(id: String, enabled: Boolean) {
        if (customSources().any { it.id == id }) {
            writeCustom(customSources().map { if (it.id == id) it.copy(enabled = enabled) else it })
        } else mutate(id) { it.put("enabled", enabled) }
    }

    fun setUrl(id: String, url: String) {
        if (customSources().any { it.id == id }) {
            writeCustom(customSources().map { if (it.id == id) it.copy(searchUrl = url) else it })
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            mutate(id) { it.put("searchUrl", url) }
        }
    }

    fun resetAll() { file.delete(); customFile.delete() }

    private fun writeCustom(list: List<MagnetSource>) {
        try {
            customFile.parentFile?.mkdirs()
            customFile.writeText(MagnetSourceRules.encodeCustom(list))
        } catch (e: Exception) {
            // 忽略持久化失败（下轮重写）
        }
    }

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
