package com.swift.download

import android.app.Application

/**
 * 真机排障用 Application：在进程最早时机安装全局崩溃捕获器。
 * 这样无论是 Application、ContentProvider 还是 Activity 阶段的异常都能记录并展示。
 * 诊断用，定位完成后可移除（移除时记得同步还原 Manifest 的 android:name）。
 */
class SwiftApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
    }
}
