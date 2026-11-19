package com.swift.download

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 崩溃堆栈展示页：刻意用平台 Activity + 平台主题，不依赖 AppCompat，
 * 避免它自己也因主题问题崩溃而看不到信息。诊断用，定位后可移除。
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stack = intent.getStringExtra(CrashReport.EXTRA_STACK)
            ?: (try {
                java.io.File(filesDir, CrashReport.CRASH_FILE).readText()
            } catch (e: Exception) {
                "（没有拿到堆栈：${e.message}）"
            })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        root.addView(TextView(this).apply {
            text = "应用崩溃了 · 请把下面内容截图发给开发者"
            textSize = 16f
            setTextColor(0xFFB91C1C.toInt())
        })
        val tv = TextView(this).apply {
            text = stack
            textSize = 11f
            setTextColor(0xFF1F2937.toInt())
            setTextIsSelectable(true)
        }
        root.addView(tv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val copy = Button(this).apply {
            text = "复制崩溃信息"
            setOnClickListener {
                try {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", stack))
                } catch (_: Exception) {}
            }
        }
        root.addView(copy, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        })
    }
}
