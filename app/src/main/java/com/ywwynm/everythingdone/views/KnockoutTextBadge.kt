package com.ywwynm.everythingdone.views

import android.R
import android.content.Context
import android.graphics.Bitmap
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
 * 半透明黑/白圆角标记与镂空文字，文字区域露出背后的照片。
 * 用于详情附件网格的 HDR / GIF 标识。
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
    private var darkInk = false
    private var inkBitmaps: Pair<Bitmap, Bitmap>? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        // 8sp + 垂直 padding 2dp(见 attachment_image.xml),整体高度 ≈13dp,比同排实况 icon(16dp)更紧凑。
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 8f, resources.displayMetrics
        )
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, intArrayOf(R.attr.text))
            badgeText = ta.getString(0) ?: ""
            ta.recycle()
        }
    }

    fun setBadgeText(text: String) {
        badgeText = text
        inkBitmaps = null
        requestLayout()
        invalidate()
    }

    fun setDarkInk(dark: Boolean) {
        if (darkInk == dark) return
        darkInk = dark
        invalidate()
    }

    /** 只给局部对比度取样提供字形，不改变正在显示的镂空绘制状态。 */
    fun drawContrastGlyphs(canvas: Canvas) {
        val paint = Paint(textPaint).apply { color = android.graphics.Color.WHITE; xfermode = null }
        canvas.drawText(badgeText, paddingLeft.toFloat(), paddingTop - paint.fontMetrics.ascent, paint)
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
        if (width <= 0 || height <= 0) return
        val pair = inkBitmaps?.takeIf { it.first.width == width && it.first.height == height }
            ?: createInkBitmaps().also { inkBitmaps = it }
        canvas.drawBitmap(if (darkInk) pair.second else pair.first, 0f, 0f, null)
    }

    private fun createInkBitmaps(): Pair<Bitmap, Bitmap> {
        val white = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(white)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radiusPx, radiusPx, bgPaint)
        val fm = textPaint.fontMetrics
        val x = paddingLeft.toFloat()
        val baseline = paddingTop.toFloat() - fm.ascent
        canvas.drawText(badgeText, x, baseline, textPaint)
        // 只使用绘制结果的 alpha；显式写 RGB，防止 Flyme 强制深色改写画笔颜色。
        val pixels = IntArray(width * height)
        white.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) pixels[i] = pixels[i] or 0x00ffffff
        white.setPixels(pixels, 0, width, 0, 0, width, height)
        val black = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (i in pixels.indices) pixels[i] = pixels[i] and 0xff000000.toInt()
        black.setPixels(pixels, 0, width, 0, 0, width, height)
        return white to black
    }
}
