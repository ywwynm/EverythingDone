package com.ywwynm.everythingdone.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.ywwynm.everythingdone.R
import kotlin.math.max
import kotlin.math.min

class ThingCardRatioTicksView @JvmOverloads constructor(
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

    init {
        val sidePadding = (16f * density).toInt()
        setPadding(sidePadding, 0, sidePadding, 0)
        setColors(
            ContextCompat.getColor(context, R.color.app_accent),
            ContextCompat.getColor(context, R.color.app_chrome_on_surface_hint)
        )
    }

    fun setColors(tickColor: Int, textColor: Int) {
        tickPaint.color = tickColor
        textPaint.color = textColor
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
        val range = maxRatio - minRatio
        if (width <= paddingLeft + paddingRight || range <= 0.0) return

        val trackLeft = paddingLeft.toFloat()
        val trackRight = (width - paddingRight).toFloat()
        val trackCenterY = height / 2f
        val tickTop = trackCenterY - 4f * density
        val tickBottom = trackCenterY + 4f * density
        val topLabelY = max(textPaint.textSize, trackCenterY - 10f * density)
        val bottomLabelY = min(height - 3f * density, trackCenterY + 17f * density)
        val contentWidth = trackRight - trackLeft

        for (i in ratios.indices) {
            val ratio = ratios[i]
            if (ratio < minRatio || ratio > maxRatio) continue

            val fraction = ((ratio - minRatio) / range).toFloat()
            val x = trackLeft + contentWidth * fraction
            canvas.drawLine(x, tickTop, x, tickBottom, tickPaint)
            val label = labels.getOrNull(i) ?: continue
            canvas.drawText(label, x, if (i % 2 == 0) bottomLabelY else topLabelY, textPaint)
        }
    }
}
