package com.swift.download.bt

/**
 * v2.6.0：SessionManager.stop() 不会自动移除 AlertListener。
 * 若每次 ensureStarted() 都 addListener，shutdown → 重启一轮后同一个事件会被回调两次，
 * 表现为"完成通知响两次"。这个守卫把"注册"这件事约束为整个进程内只做一次（纯 JVM，可单测）。
 */
class ListenerOnceGuard {

    @Volatile
    var isRegistered: Boolean = false
        private set

    fun registerOnce(action: () -> Unit) {
        if (isRegistered) return
        synchronized(this) {
            if (isRegistered) return
            action()
            isRegistered = true
        }
    }
}
