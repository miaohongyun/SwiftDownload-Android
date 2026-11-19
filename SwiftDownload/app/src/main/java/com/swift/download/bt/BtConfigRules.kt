package com.swift.download.bt

/**
 * v2.7 Stage2.1：BT 配置纯逻辑（JVM 单测覆盖，不含 jlibtorrent 依赖）。
 */
object BtConfigRules {

    /** 代理类型（与 UI 选项一一对应） */
    enum class ProxyType { HTTP, SOCKS5 }

    /**
     * 是否对 BT 生效：总开关关闭 → 一定不生效；否则跟随「代理对 BT 生效」子开关。
     * [btOverride] 为 null 时表示"跟随总开关"（默认行为）。
     */
    fun proxyApplies(proxyEnabled: Boolean, btOverride: Boolean?): Boolean =
        proxyEnabled && (btOverride ?: true)

    /** 停种策略 */
    enum class SeedingPolicy { IMMEDIATE, DELAY_5MIN, ALWAYS }

    /**
     * 完成时是否立即停种。ALWAYS 永不停；DELAY_5MIN 交给调用方安排延迟任务。
     */
    fun stopOnFinish(policy: SeedingPolicy): Boolean = policy == SeedingPolicy.IMMEDIATE

    fun shouldScheduleDelay(policy: SeedingPolicy): Boolean = policy == SeedingPolicy.DELAY_5MIN

    /** 延迟毫秒数（5 分钟），抽常量便于测试与设置项文案对齐 */
    const val DELAY_5MIN_MS = 5L * 60 * 1000

    /** 上传限速：0 = 不限，否则按 MB/s 转字节/秒并限制在合理区间 */
    fun uploadLimitBps(mbps: Int): Int =
        if (mbps <= 0) 0 else (mbps.coerceIn(0, 1024) * 1024 * 1024)

    /** 把 MB/s 输入规范化到 0..1024 */
    fun normalizeUploadMbps(mbps: Int): Int = mbps.coerceIn(0, 1024)
}
