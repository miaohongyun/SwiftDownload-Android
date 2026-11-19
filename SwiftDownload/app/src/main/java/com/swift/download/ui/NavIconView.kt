package com.swift.download.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * UI 规范 v3：底部导航单色几何图标（替代无法 tint 的彩色 emoji）。
 *
 * 全部使用 API 1 即有的 Canvas 基础绘制（drawCircle/drawLine/drawArc），
 * 不引入任何新依赖；通过 [setTint] 与激活态联动改色。
 *
 * @param kind 0=浏览器（圆+横线） 1=传输（双向箭头） 2=文件（文件夹轮廓） 3=我的（圆头+肩弧）
 */
@SuppressLint("ViewConstructor")
class NavIconView(
    ctx: Context,
    kind: Int,
    sizeDp: Float
) : View(ctx) {

    private val iconKind: Int = kind
    private val iconSize: Int = Ui.dp(ctx, sizeDp)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(ctx, 2f).toFloat()
        color = Ui.TEXT_DISABLED
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        // 图标旁有可见文字标签，设为装饰性，避免 TalkBack 重复朗读
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 激活/未激活换色：只改 Paint 并局部重绘，不重建 View */
    fun setTint(color: Int) {
        if (paint.color == color) return
        paint.color = color
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSizeAndState(iconSize + paddingLeft + paddingRight, widthMeasureSpec, 0)
        val h = resolveSizeAndState(iconSize + paddingTop + paddingBottom, heightMeasureSpec, 0)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val r = (min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom) / 2f) - paint.strokeWidth
        if (r <= 0f) return

        when (iconKind) {
            0 -> {
                // 浏览器：外圆 + 赤道横线
                canvas.drawCircle(cx, cy, r, paint)
                canvas.drawLine(cx - r, cy, cx + r, cy, paint)
            }
            1 -> {
                // 传输：一条竖线 + 上下箭头
                canvas.drawLine(cx, cy - r, cx, cy + r, paint)
                canvas.drawLine(cx, cy - r, cx - r * 0.45f, cy - r * 0.45f, paint)
                canvas.drawLine(cx, cy - r, cx + r * 0.45f, cy - r * 0.45f, paint)
                canvas.drawLine(cx, cy + r, cx - r * 0.45f, cy + r * 0.45f, paint)
                canvas.drawLine(cx, cy + r, cx + r * 0.45f, cy + r * 0.45f, paint)
            }
            2 -> {
                // 文件：文件夹轮廓（左侧竖边 + 顶部标签折角 + 右侧/底边）
                val left = cx - r
                val right = cx + r
                val top = cy - r * 0.85f
                val bottom = cy + r * 0.85f
                val notchX = left + r * 0.5f
                val notchY = top + r * 0.4f
                canvas.drawLines(floatArrayOf(
                    left, bottom, left, top,
                    left, top, notchX, top,
                    notchX, top, notchX + r * 0.25f, notchY,
                    notchX + r * 0.25f, notchY, right, notchY,
                    right, notchY, right, bottom,
                    right, bottom, left, bottom
                ), paint)
            }
            3 -> {
                // 我的：圆头 + 肩部弧线
                canvas.drawCircle(cx, cy - r * 0.4f, r * 0.36f, paint)
                canvas.drawArc(
                    RectF(cx - r, cy - r * 0.05f, cx + r, cy + r * 1.15f),
                    200f, 140f, false, paint
                )
            }
        }
    }
}
