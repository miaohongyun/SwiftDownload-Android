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
    val BLUE = 0xFF2563EB.toInt()
    val BLUE_LIGHT = 0xFFEFF6FF.toInt()
    val BG = 0xFFF6F7FB.toInt()
    val CARD = Color.WHITE
    val TXT = 0xFF1F2937.toInt()
    val TXT_SUB = 0xFF6B7280.toInt()
    val GREEN = 0xFF059669.toInt()
    val RED = 0xFFDC2626.toInt()
    val ORANGE = 0xFFD97706.toInt()
    val DIVIDER = 0xFFEEF0F4.toInt()

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

    fun singleLineEt(ctx: Context, hint: String): EditText = EditText(ctx).apply {
        this.hint = hint
        setSingleLine()
        textSize = 15f
        setTextColor(TXT)
        setHintTextColor(0xFF9CA3AF.toInt())
    }

    /** 白色圆角卡片 */
    @SuppressLint("ViewConstructor")
    fun card(ctx: Context, radius: Float = 14f): LinearLayout {
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(CARD)
                cornerRadius = dp(ctx, radius).toFloat()
            }
            setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 12f))
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
                setColor(if (selected) BLUE else 0xFFF1F3F9.toInt())
                cornerRadius = dp(ctx, 17f).toFloat()
            }
            setOnClickListener { onTap(this) }
        }
        return tv
    }

    fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

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
    fun pressable(ctx: Context, v: View) {
        v.isClickable = true
        v.isFocusable = true
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            val rd = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x1E2563EB.toInt()), null, null)
            v.foreground = rd
        }
    }

    /** 小圆角方块图标（书签/文件列表用），背景色由调用方指定 */
    fun iconBadge(ctx: Context, glyph: String, bgColor: Int, sizeDp: Float = 36f, radiusDp: Float = 10f): TextView =
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
            pressable(ctx, this)
        }
}
