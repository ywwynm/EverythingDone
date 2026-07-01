package com.ywwynm.everythingdone.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.ywwynm.everythingdone.R

/**
 * 全屏图片查看器里的 HDR 徽标,可点按在 HDR / 强制 SDR 之间切换:
 * - **选中(HDR 生效)**:白底 + [PorterDuff.Mode.CLEAR] 镂空文字(文字处透出背后的照片);按压 ripple 偏黑。
 * - **未选中(强制 SDR)**:半透明深色底 + 白描边 + 白色文字;按压 ripple 偏白。
 *
 * ripple 由 [setForeground] 承载,随选中态切换其颜色/形状;镂空用 [Canvas.saveLayer] 离屏合成,
 * 保留硬件加速,ripple 揭示动画走 RenderThread、顺滑。文案取自 XML 的 `android:text`。
 */
class HdrBadgeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val radiusPx = 4f * density
    private val strokePx = 1.5f * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = 0xCCFFFFFF.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val clearXfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val rect = RectF()

    private var badgeText: String = ""
    private var boostOn: Boolean = true

    init {
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics
        )
        isClickable = true
        isFocusable = true
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.text))
            badgeText = ta.getString(0) ?: ""
            ta.recycle()
        }
        applyForegroundRipple()
    }

    fun setBadgeText(text: String) {
        badgeText = text
        requestLayout()
        invalidate()
    }

    /** 切换选中态(HDR 生效/强制 SDR),同步文字/底色渲染与 ripple 颜色。 */
    fun setBoostOn(on: Boolean) {
        if (boostOn == on) return
        boostOn = on
        applyForegroundRipple()
        invalidate()
    }

    private fun applyForegroundRipple() {
        val res = if (boostOn) R.drawable.ripple_hdr_badge_on else R.drawable.ripple_hdr_badge_off
        foreground = ContextCompat.getDrawable(context, res)
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
        val w = width.toFloat()
        val h = height.toFloat()
        if (boostOn) {
            // 白底 + 镂空文字：离屏层内先铺白底，再用 CLEAR 把文字掏成透明，露出背后照片。
            val saved = canvas.saveLayer(0f, 0f, w, h, null)
            rect.set(0f, 0f, w, h)
            bgPaint.color = Color.WHITE
            canvas.drawRoundRect(rect, radiusPx, radiusPx, bgPaint)
            textPaint.xfermode = clearXfermode
            drawCenteredText(canvas)
            textPaint.xfermode = null
            canvas.restoreToCount(saved)
        } else {
            // 半透明深色底 + 白描边 + 白字。
            rect.set(0f, 0f, w, h)
            bgPaint.color = 0x66000000
            canvas.drawRoundRect(rect, radiusPx, radiusPx, bgPaint)
            val inset = strokePx / 2f
            rect.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(rect, radiusPx, radiusPx, strokePaint)
            textPaint.color = Color.WHITE
            drawCenteredText(canvas)
        }
    }

    private fun drawCenteredText(canvas: Canvas) {
        val fm = textPaint.fontMetrics
        val tw = textPaint.measureText(badgeText)
        val x = (width - tw) / 2f
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(badgeText, x, baseline, textPaint)
    }
}
