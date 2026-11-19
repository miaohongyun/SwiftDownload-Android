package com.swift.download.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 代码构建 UI 的通用助手（v1.0 即为纯代码 UI，v2.0 延续） */
object Ui {
    /** 空状态语义：EMPTY 常规空态 / ERROR 警告态（仅图标色不同，主按钮恒为 BLUE 实心） */
    enum class EmptyTone { EMPTY, ERROR }

    private const val TAG_EMPTY_ACTION = "ui_empty_action"
    private const val TAG_KEY_EMPTY_LABEL = 0x5F010001

    val BLUE = 0xFF2563EB.toInt()
    val BLUE_LIGHT = 0xFFEFF6FF.toInt()
    /** UI 规范 v3：按压态（按钮/高亮反馈） */
    val BLUE_PRESSED = 0xFF1D4ED8.toInt()
    val BG = 0xFFF6F7FB.toInt()
    val CARD = Color.WHITE
    /** UI 规范 v3：次级浅底（未选中胶囊/空状态图标底） */
    val SURFACE_MUTED = 0xFFF1F4FA.toInt()
    /** UI 规范 v3：卡片/导航描边 */
    val BORDER = 0xFFECEEF3.toInt()
    val TXT = 0xFF1F2937.toInt()
    val TXT_SUB = 0xFF6B7280.toInt()
    /** UI 规范 v3：三级文字 / 禁用文字 */
    val TEXT_TERTIARY = 0xFF9CA3AF.toInt()
    val TEXT_DISABLED = 0xFFC9CED8.toInt()
    val GREEN = 0xFF059669.toInt()
    val RED = 0xFFDC2626.toInt()
    val ORANGE = 0xFFD97706.toInt()
    val DIVIDER = 0xFFEEF0F4.toInt()
    /** UI 规范 v3：任务类型徽标（BT / HLS） */
    val TYPE_BT = 0xFF0891B2.toInt()
    val TYPE_HLS = 0xFF7C3AED.toInt()

    /** 书签/文件图标用：按名字稳定取色（8 色调色板） */
    private val PALETTE = intArrayOf(
        0xFF2563EB.toInt(), 0xFF059669.toInt(), 0xFFD97706.toInt(), 0xFFDC2626.toInt(),
        0xFF7C3AED.toInt(), 0xFFDB2777.toInt(), 0xFF0891B2.toInt(), 0xFF65A30D.toInt()
    )

    fun colorOf(name: String): Int = PALETTE[Math.floorMod(name.hashCode(), PALETTE.size)]

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun <T : View> lp(w: Int, h: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h)

    fun text(ctx: Context, s: CharSequence, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            textSize = size
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(ctx, 2f).toFloat(), 1f)
        }

    /** 单行/短文案专用：不叠加 text() 的行间距，避免按钮、角标、胶囊字距被撑开 */
    fun textSingle(ctx: Context, s: CharSequence, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            textSize = size
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    /** v2.7 Stage4.1：等宽数字（tnum），消除每 500ms 刷新时数字宽度变化导致的抖动 */
    fun numericText(ctx: Context, s: CharSequence, size: Float, color: Int, bold: Boolean = false): TextView =
        text(ctx, s, size, color, bold).apply { fontFeatureSettings = "tnum" }

    fun singleLineEt(ctx: Context, hint: String): EditText = EditText(ctx).apply {
        this.hint = hint
        setSingleLine()
        textSize = 15f
        setTextColor(TXT)
        setHintTextColor(0xFF9CA3AF.toInt())
    }

    /** 白色圆角卡片 */
    @SuppressLint("ViewConstructor")
    fun card(ctx: Context, radius: Float = 16f): LinearLayout {
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(CARD)
                cornerRadius = dp(ctx, radius).toFloat()
                setStroke(dp(ctx, 1f), BORDER)
            }
            setPadding(dp(ctx, 16f), dp(ctx, 12f), dp(ctx, 16f), dp(ctx, 12f))
            elevation = dp(ctx, 1f).toFloat()
        }
        return inner
    }

    /** 通用 chip（搜索源/引擎入口用） */
    fun chip(ctx: Context, label: String, selected: Boolean, onTap: (TextView) -> Unit): TextView {
        val tv = TextView(ctx).apply {
            text = label
            textSize = 13f
            setPadding(dp(ctx, 14f), dp(ctx, 7f), dp(ctx, 14f), dp(ctx, 7f))
            setTextColor(if (selected) Color.WHITE else TXT)
            typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            background = GradientDrawable().apply {
                setColor(if (selected) BLUE else SURFACE_MUTED)
                cornerRadius = dp(ctx, 17f).toFloat()
            }
            setOnClickListener { onTap(this) }
        }
        return tv
    }

    fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    /** 语义化错误提示入口：文案本身不带符号，后续可统一替换为带样式的提示条 */
    fun toastError(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    /**
     * UI 规范 v3（勘误后）：统一空状态（图标 + 标题 + 说明 + 可选行动按钮）。
     * 图标为装饰性元素（IMPORTANT_FOR_ACCESSIBILITY_NO）；返回 LinearLayout 本体，
     * 由调用方按 MATCH_PARENT/weight 布局，与列表互斥切换。
     */
    fun emptyState(
        ctx: Context,
        glyph: String,
        title: String,
        hint: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        tone: EmptyTone = EmptyTone.EMPTY
    ): LinearLayout {
        // 防止出现「有按钮文案却无点击行为」的死按钮
        require(actionLabel == null || onAction != null) {
            "emptyState: onAction 不能为空（actionLabel=$actionLabel）"
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 24f), dp(ctx, 32f), dp(ctx, 24f), dp(ctx, 32f))
        }
        val badge = iconBadge(ctx, glyph, SURFACE_MUTED, radiusDp = 24f).apply {
            textSize = 24f
            setTextColor(if (tone == EmptyTone.ERROR) ORANGE else TEXT_DISABLED)
            // 图标旁已有标题文字，避免 TalkBack 重复朗读
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        box.addView(badge, LinearLayout.LayoutParams(dp(ctx, 48f), dp(ctx, 48f)))
        val titleView = textSingle(ctx, title, 14f, TXT, bold = true).apply { gravity = Gravity.CENTER }
        box.addView(titleView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 16f) })
        val hintView = textSingle(ctx, hint, 12f, TXT_SUB).apply { gravity = Gravity.CENTER }
        box.addView(hintView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 6f) })
        if (actionLabel != null && onAction != null) {
            val action = pillBtn(ctx, actionLabel, BLUE, filled = true, onTap = onAction).apply {
                tag = TAG_EMPTY_ACTION              // 供 setEmptyStateLoading 定位
                setTag(TAG_KEY_EMPTY_LABEL, actionLabel) // 恢复原文案
            }
            box.addView(action, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 18f) })
        }
        return box
    }

    /** 空状态按钮加载态：锁 40dp 高度防跳动、禁用点击并去掉 ripple；false 完整恢复 */
    fun setEmptyStateLoading(state: View, loading: Boolean) {
        val btn = (state as? ViewGroup)?.findViewWithTag(TAG_EMPTY_ACTION) as? TextView ?: return
        if (loading) {
            btn.text = "加载中…"
            btn.isEnabled = false
            btn.foreground = null
            btn.layoutParams.height = dp(state.context, 40f)
        } else {
            (btn.getTag(TAG_KEY_EMPTY_LABEL) as? String)?.let { btn.text = it }
            btn.isEnabled = true
            btn.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            pressable(btn) // 恢复 ripple / 可点击态
        }
        btn.requestLayout()
    }

    fun verticalScroll(ctx: Context, child: View): FrameLayout {
        val sv = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(child, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val fl = FrameLayout(ctx)
        fl.addView(sv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return fl
    }

    fun horizontalScroll(ctx: Context, strip: LinearLayout): HorizontalScrollView =
        HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    fun recyclerView(ctx: Context): RecyclerView = RecyclerView(ctx).apply {
        layoutManager = LinearLayoutManager(ctx)
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    fun ellipsize(tv: TextView) {
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.maxLines = 2
    }

    /* ---------------- v2.2 新增通用组件 ---------------- */

    /** 给卡片/行加按压涟漪（API 23+ 用 foreground，低版本退化为可点击态） */
    fun pressable(v: View) {
        v.isClickable = true
        v.isFocusable = true
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            val rd = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x1E2563EB.toInt()), null, null)
            v.foreground = rd
        }
    }

    /** 小圆角方块图标（书签/文件列表用），背景色由调用方指定 */
    fun iconBadge(ctx: Context, glyph: String, bgColor: Int, radiusDp: Float = 10f): TextView =
        TextView(ctx).apply {
            text = glyph
            textSize = if (glyph.length > 1) 13f else 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(ctx, radiusDp).toFloat()
            }
        }

    /** 文字胶囊按钮（传输页操作/工具栏用） */
    fun pillBtn(ctx: Context, label: String, color: Int, filled: Boolean = false, onTap: (() -> Unit)? = null): TextView =
        TextView(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(if (filled) Color.WHITE else color)
            typeface = if (filled) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 16f), dp(ctx, 7f), dp(ctx, 16f), dp(ctx, 7f))
            background = GradientDrawable().apply {
                setColor(if (filled) color else 0x00000000)
                setStroke(dp(ctx, 1f), color)
                cornerRadius = dp(ctx, 16f).toFloat()
            }
            if (onTap != null) setOnClickListener { onTap() }
            pressable(this)
        }
}
