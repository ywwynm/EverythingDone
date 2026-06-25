package com.ywwynm.everythingdone.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 比例档位刻度视图：按对数位置绘制档位 tick 与 label，只画落在 [minRatio, maxRatio]
 * 区间内的档位。供 [RatioSlider] 内部使用。
 */
class RatioTicksView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            10f,
            resources.displayMetrics
        )
    }

    private var minRatio = 0.5
    private var maxRatio = 2.0
    private var ratios = doubleArrayOf()
    private var labels = emptyArray<String>()
    private var tickBackground: ThingBackground = App.defaultAccentBackground
    private var labelTextColor = ContextCompat.getColor(
        context,
        R.color.app_chrome_on_surface_hint
    )
    private var activeRatio: Double? = null

    init {
        val sidePadding = (16f * density).toInt()
        setPadding(sidePadding, 0, sidePadding, 0)
        setAccentBackground(
            App.defaultAccentBackground,
            ContextCompat.getColor(context, R.color.app_chrome_on_surface_hint)
        )
    }

    fun setColors(tickColor: Int, textColor: Int) {
        tickBackground = ThingBackground.pure(tickColor)
        labelTextColor = textColor
        invalidate()
    }

    fun setAccentBackground(background: ThingBackground, textColor: Int) {
        tickBackground = background
        labelTextColor = textColor
        invalidate()
    }

    fun setActiveRatio(ratio: Double?) {
        val safeRatio = if (ratio == null || ratio.isNaN() || ratio.isInfinite()) null else ratio
        val oldRatio = activeRatio
        if (oldRatio == null && safeRatio == null) return
        if (oldRatio != null && safeRatio != null && kotlin.math.abs(oldRatio - safeRatio) < 0.0001) {
            return
        }
        activeRatio = safeRatio
        invalidate()
    }

    fun setRatios(
        minRatio: Double,
        maxRatio: Double,
        ratios: DoubleArray,
        labels: Array<String>
    ) {
        this.minRatio = min(minRatio, maxRatio)
        this.maxRatio = max(minRatio, maxRatio)
        this.ratios = ratios
        this.labels = labels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= paddingLeft + paddingRight || minRatio <= 0.0) return
        val logMin = ln(minRatio)
        val logMax = ln(maxRatio)
        val logRange = logMax - logMin
        if (logRange <= 0.0) return

        val trackLeft = paddingLeft.toFloat()
        val trackRight = (width - paddingRight).toFloat()
        val trackCenterY = height / 2f
        val tickTop = trackCenterY - 4f * density
        val tickBottom = trackCenterY + 4f * density
        val topLabelY = max(textPaint.textSize, trackCenterY - 10f * density)
        val bottomLabelY = min(height - 3f * density, trackCenterY + 17f * density)
        val contentWidth = trackRight - trackLeft

        if (tickBackground.mode === ThingBackground.Mode.GRADIENT) {
            tickPaint.shader = BackgroundUtil.createLinearGradient(
                tickBackground,
                width.toFloat(),
                height.toFloat()
            )
        } else {
            tickPaint.shader = null
            tickPaint.color = tickBackground.color
        }
        var visibleIndex = 0
        for (i in ratios.indices) {
            val ratio = ratios[i]
            if (ratio < minRatio || ratio > maxRatio) continue

            val fraction = ((ln(ratio) - logMin) / logRange).toFloat()
            val x = trackLeft + contentWidth * fraction
            canvas.drawLine(x, tickTop, x, tickBottom, tickPaint)
            val label = labels.getOrNull(i)
            if (label != null) {
                drawLabel(
                    canvas,
                    label,
                    x,
                    if (visibleIndex % 2 == 0) bottomLabelY else topLabelY,
                    isActiveRatio(ratio)
                )
            }
            visibleIndex++
        }
        tickPaint.shader = null
        textPaint.shader = null
        textPaint.color = labelTextColor
    }

    private fun isActiveRatio(ratio: Double): Boolean {
        val active = activeRatio ?: return false
        return kotlin.math.abs(ratio - active) < 0.0001
    }

    private fun drawLabel(
        canvas: Canvas,
        label: String,
        x: Float,
        baseline: Float,
        active: Boolean
    ) {
        if (!active) {
            textPaint.shader = null
            textPaint.color = labelTextColor
            canvas.drawText(label, x, baseline, textPaint)
            return
        }

        if (tickBackground.mode === ThingBackground.Mode.GRADIENT) {
            val textWidth = textPaint.measureText(label).coerceAtLeast(1f)
            val fontMetrics = textPaint.fontMetrics
            val textHeight = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(1f)
            val shader = BackgroundUtil.createLinearGradient(
                tickBackground,
                textWidth,
                textHeight
            )
            val matrix = Matrix()
            matrix.setTranslate(x - textWidth / 2f, baseline + fontMetrics.ascent)
            shader.setLocalMatrix(matrix)
            textPaint.shader = shader
            textPaint.color = tickBackground.color
        } else {
            textPaint.shader = null
            textPaint.color = tickBackground.color
        }
        canvas.drawText(label, x, baseline, textPaint)
    }
}
