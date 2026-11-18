package com.swift.download.browser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.swift.download.ui.Ui

/**
 * v2.1 新增：书签管理页（我的 → 书签管理）。
 *  - 列表展示全部书签（名称 + 网址），点击行直接在浏览器打开
 *  - 编辑 / 删除 / 置顶单个书签
 *  - 添加新书签；一键恢复默认书签
 *  - 任何改动都会回调 onChanged，让浏览器首页卡片即时同步
 */
@SuppressLint("ViewConstructor")
class BookmarkManagerPage(
    context: Context,
    private val onOpen: (url: String) -> Unit,
    private val onChanged: () -> Unit = {}
) : FrameLayout(context) {

    private val store = QuickSites.get(context)

    init {
        setBackgroundColor(Ui.BG)
        rebuild()
    }

    fun rebuild() {
        removeAllViews()
        val ctx = context
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // 头部说明卡
        val head = Ui.card(ctx, 16f)
        head.addView(Ui.text(ctx, "书签管理", 20f, Ui.TXT, bold = true))
        head.addView(Ui.text(ctx, "点行打开 · 「编辑」改名改址 · 「置顶」排到浏览器首页最前\n收藏的网站会显示在浏览器首页「书签」区。", 12f, Ui.TXT_SUB),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 6f)
            })
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(ctx, 14f), Ui.dp(ctx, 14f), Ui.dp(ctx, 14f), 0)
        })

        val sites = store.all()
        if (sites.isEmpty()) {
            root.addView(Ui.text(ctx, "暂无书签，点下方「＋ 添加书签」", 13f, Ui.TXT_SUB),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(Ui.dp(ctx, 18f), Ui.dp(ctx, 16f), Ui.dp(ctx, 18f), Ui.dp(ctx, 8f))
                })
        }
        for (site in sites) {
            root.addView(buildRow(site))
        }

        // 底部操作
        val addBtn = TextView(ctx).apply {
            text = "＋ 添加书签"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(ctx, 14f), 0, Ui.dp(ctx, 14f))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.BLUE); cornerRadius = Ui.dp(ctx, 14f).toFloat()
            }
            setOnClickListener {
                showSiteEditDialog(ctx, null, "添加书签") { n, u ->
                    store.add(n, u)
                    rebuild(); onChanged()
                    Ui.toast(ctx, "已添加 $n")
                }
            }
        }
        root.addView(addBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(ctx, 14f), Ui.dp(ctx, 16f), Ui.dp(ctx, 14f), 0)
        })
        val reset = TextView(context).apply {
            text = "恢复默认书签（必应 / 电影港 / 高清族 / SHB影视）"
            textSize = 13f
            setTextColor(Ui.RED)
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(ctx, 14f), 0, Ui.dp(ctx, 14f))
            setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setMessage("将清空你自定义的书签并恢复 4 个默认站点，确定？")
                    .setPositiveButton("恢复") { _, _ ->
                        store.resetDefaults()
                        rebuild(); onChanged()
                        Ui.toast(ctx, "已恢复默认书签")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        root.addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(View(ctx), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 20f)))

        addView(Ui.verticalScroll(ctx, root), ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun buildRow(site: QuickSites.Site): View {
        val ctx = context
        val card = Ui.card(ctx, 14f)
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val icon = TextView(ctx).apply {
            text = site.name.take(1)
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.BLUE); cornerRadius = Ui.dp(ctx, 11f).toFloat()
            }
        }
        line.addView(icon, LinearLayout.LayoutParams(Ui.dp(ctx, 36f), Ui.dp(ctx, 36f)).apply {
            marginEnd = Ui.dp(ctx, 10f)
        })

        val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val nameTv = Ui.text(ctx, site.name, 15f, Ui.TXT, bold = true)
        Ui.ellipsize(nameTv)
        nameTv.ellipsize = android.text.TextUtils.TruncateAt.END
        nameTv.maxLines = 1
        mid.addView(nameTv)
        val urlTv = Ui.text(ctx, site.url, 11f, Ui.TXT_SUB)
        urlTv.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        urlTv.maxLines = 1
        urlTv.isSingleLine = true
        mid.addView(urlTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(ctx, 2f)
        })
        line.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        for ((label, color, act) in listOf(
            Triple("置顶", Ui.TXT_SUB, { store.moveToTop(site.url); rebuild(); onChanged(); Ui.toast(ctx, "已置顶 ${site.name}") }),
            Triple("编辑", Ui.BLUE, {
                showSiteEditDialog(ctx, site, "编辑书签") { n, u ->
                    store.upsert(site.url, n, u)
                    rebuild(); onChanged()
                    Ui.toast(ctx, "已保存 $n")
                }
            }),
            Triple("删除", Ui.RED, {
                AlertDialog.Builder(ctx)
                    .setMessage("删除书签「${site.name}」？")
                    .setPositiveButton("删除") { _, _ ->
                        store.remove(site.url)
                        rebuild(); onChanged()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            })
        )) {
            val b = TextView(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(color)
                setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 8f), Ui.dp(ctx, 10f), Ui.dp(ctx, 8f))
                setOnClickListener { act() }
            }
            line.addView(b)
        }

        card.addView(line)
        card.setOnClickListener { onOpen(site.url) }
        return card
    }
}
