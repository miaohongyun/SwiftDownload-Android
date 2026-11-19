package com.swift.download.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * 自动换行的流式布局：子 View 排不下时自动移到下一行。
 * 用于任务卡操作区——窄屏、大字体或按钮较多时不会被裁出屏幕。
 */
class FlowLayout(context: Context) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val contentWidth = widthSize - paddingLeft - paddingRight

        var lineWidth = 0
        var lineHeight = 0
        var maxWidth = 0
        var totalHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as MarginLayoutParams
            val cw = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val ch = child.measuredHeight + lp.topMargin + lp.bottomMargin

            if (lineWidth > 0 && lineWidth + cw > contentWidth) {
                maxWidth = maxOf(maxWidth, lineWidth)
                totalHeight += lineHeight
                lineWidth = cw
                lineHeight = ch
            } else {
                lineWidth += cw
                lineHeight = maxOf(lineHeight, ch)
            }
        }
        maxWidth = maxOf(maxWidth, lineWidth)
        totalHeight += lineHeight

        val w = if (widthMode == MeasureSpec.EXACTLY) widthSize else maxWidth + paddingLeft + paddingRight
        val h = if (heightMode == MeasureSpec.EXACTLY) heightSize else totalHeight + paddingTop + paddingBottom
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        val rightBound = width - paddingRight

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val cw = child.measuredWidth
            val ch = child.measuredHeight

            if (x > paddingLeft && x + lp.leftMargin + cw + lp.rightMargin > rightBound) {
                x = paddingLeft
                y += lineHeight
                lineHeight = 0
            }
            val left = x + lp.leftMargin
            val top = y + lp.topMargin
            child.layout(left, top, left + cw, top + ch)
            x += cw + lp.leftMargin + lp.rightMargin
            lineHeight = maxOf(lineHeight, ch + lp.topMargin + lp.bottomMargin)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)

    override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams
}

/** 以流式布局的方式添加子 View，统一设置按钮间距 */
fun FlowLayout.addFlow(v: View, spacingDp: Float = 6f) {
    addView(v, ViewGroup.MarginLayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        marginEnd = Ui.dp(context, spacingDp)
        bottomMargin = Ui.dp(context, 3f)
    })
}
