package com.swift.download

import android.content.Context
import android.content.Intent
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 真机排障用：全局未捕获异常捕获器。
 * 崩溃时把完整堆栈写入应用专属目录，并拉起一个极简展示页直接显示在屏幕上，
 * 便于无 adb 环境下截图定位。保留原有 handler 行为，不改变崩溃语义。
 *
 * 这是诊断辅助代码，定位完成后可移除。
 */
object CrashReport {
    const val EXTRA_STACK = "crash_stack"
    const val CRASH_FILE = "crash_report.txt"

    @Volatile private var installed = false

    fun install(appContext: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stack = "线程：${thread.name}\n" + sw.toString()

                // 1) 落盘，持久化供后续读取
                try {
                    java.io.File(appContext.filesDir, CRASH_FILE).writeText(stack)
                } catch (_: Throwable) {}

                // 2) 拉起展示页（给一点时间让 Activity 起来，再结束进程）
                try {
                    val i = Intent(appContext, CrashActivity::class.java).apply {
                        putExtra(EXTRA_STACK, stack)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    appContext.startActivity(i)
                } catch (_: Throwable) {}

                // 3) 延迟结束进程，让展示页有机会渲染
                Thread {
                    // 留足冷启动渲染时间，避免展示页还没画出来就被杀掉
                    try { Thread.sleep(3000) } catch (_: InterruptedException) {}
                    Process.killProcess(Process.myPid())
                    kotlin.system.exitProcess(1)
                }.apply { isDaemon = true }.start()

                // 不再调用 previous：避免系统再弹一次"应用已停止运行"覆盖展示页
            }
        }
    }
}
