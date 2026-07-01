package com.ywwynm.everythingdone.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * 半透明偏白圆角药丸 + **镂空文字**：先画白底，再用 [PorterDuff.Mode.CLEAR] 把文字区域掏成透明，
 * 露出背后的照片。用于详情附件网格的 HDR / GIF 标识。
 *
 * 用软件层([LAYER_TYPE_SOFTWARE])保证 CLEAR 掏空稳定生效。文字取自 XML 的 `android:text`。
 */
class KnockoutTextBadge @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD8FFFFFF.toInt() // 偏白、带透明度
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) // 把文字掏成透明
    }
    private val rect = RectF()
    private val radiusPx = 4f * resources.displayMetrics.density

    private var badgeText: String = ""

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics
        )
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.text))
            badgeText = ta.getString(0) ?: ""
            ta.recycle()
        }
    }

    fun setBadgeText(text: String) {
        badgeText = text
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = textPaint.fontMetrics
        val w = paddingLeft + textPaint.measureText(badgeText) + paddingRight
        val h = paddingTop + (fm.descent - fm.ascent) + paddingBottom
        setMeasuredDimension(
            resolveSize(Math.ceil(w.toDouble()).toInt(), widthMeasureSpec),
            resolveSize(Math.ceil(h.toDouble()).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radiusPx, radiusPx, bgPaint)
        val fm = textPaint.fontMetrics
        val x = paddingLeft.toFloat()
        val baseline = paddingTop.toFloat() - fm.ascent
        canvas.drawText(badgeText, x, baseline, textPaint)
    }
}
