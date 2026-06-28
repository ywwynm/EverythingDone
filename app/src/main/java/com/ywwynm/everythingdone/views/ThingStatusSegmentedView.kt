@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.views

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil

/**
 * Single segmented capsule for the record status filter: 正在进行 / 已完成 /
 * 回收站, in that fixed left-to-right order. The whole capsule shares one
 * Scope-derived gradient (accent → accent2 at the "全部记事" root, or the folder's
 * own colour/gradient inside a folder): the gradient outline and the selected
 * segment's solid fill are painted with the SAME full-width shader, so the fill
 * reads as a continuous part of the outline gradient. The fill reaches the inner
 * edge of the stroke (no gap) on the top/bottom always and on the left/right when
 * the selected segment is at an edge.
 *
 * The selected segment expands to show its icon and text; the other two collapse
 * to a square icon-only cell (so the touch ripple is circular) and sit at the
 * edges. Selection changes animate the expand / collapse, and every segment shows
 * a ripple on touch.
 */
class ThingStatusSegmentedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Invoked whenever a status segment is tapped (even if already selected). */
    var onStatusChange: ((Int) -> Unit)? = null

    private var scopeBackground: ThingBackground = defaultScopeBackground()
    private var scopeIsRoot: Boolean = true
    private var scopeSignature: String = "root"

    private var currentStatus: Int = Def.ThingStatus.UNDERWAY

    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f).toFloat()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlineRect = RectF()
    private val fillRect = RectF()
    private var gradientShader: Shader? = null
    private var gradientWidth = 0

    private class Segment(
        val status: Int,
        val cell: FrameLayout,
        val icon: ImageView,
        val text: TextView,
        @param:DrawableRes val iconRes: Int
    )

    private val segments = ArrayList<Segment>()
    private val cellWidths = ArrayList<Int>()
    private var widthAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(HEIGHT_DP)))
        addSegment(Def.ThingStatus.UNDERWAY, R.drawable.drawer_all, R.string.underway)
        addSegment(Def.ThingStatus.FINISHED, R.drawable.drawer_finished, R.string.finished)
        addSegment(Def.ThingStatus.DELETED, R.drawable.drawer_deleted, R.string.drawer_deleted)
        updateTextVisibility()
        applyForegroundsAndTints()
    }

    fun setScopeBackground(background: ThingBackground?) {
        val signature = if (background == null) "root" else background.toJson()
        if (signature == scopeSignature) return
        scopeSignature = signature
        scopeIsRoot = background == null
        scopeBackground = background ?: defaultScopeBackground()
        gradientShader = null
        applyForegroundsAndTints()
        invalidate()
    }

    fun setStatus(status: Int) {
        val normalized = normalizeStatus(status)
        if (normalized == currentStatus) return
        currentStatus = normalized
        updateTextVisibility()
        applyForegroundsAndTints()
        animateWidths(animate = false)
    }

    fun getStatus(): Int = currentStatus

    private fun selectedIndex(): Int {
        val index = segments.indexOfFirst { it.status == currentStatus }
        return if (index < 0) 0 else index
    }

    private fun onSegmentTapped(status: Int) {
        if (status != currentStatus) {
            currentStatus = status
            updateTextVisibility()
            applyForegroundsAndTints()
            animateWidths(animate = true)
        }
        onStatusChange?.invoke(status)
    }

    private fun addSegment(status: Int, @DrawableRes iconRes: Int, @StringRes textRes: Int) {
        val cell = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            clipChildren = true
            setOnClickListener { onSegmentTapped(status) }
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER }
        inner.addView(icon, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
        val text = TextView(context).apply {
            textSize = 14f
            setSingleLine(true)
            setText(textRes)
        }
        inner.addView(
            text,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6f) }
        )
        cell.addView(
            inner,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        row.addView(
            cell,
            LinearLayout.LayoutParams(dp(SEGMENT_MIN_DP), LinearLayout.LayoutParams.MATCH_PARENT)
        )
        segments.add(Segment(status, cell, icon, text, iconRes))
        cellWidths.add(dp(SEGMENT_MIN_DP))
    }

    private fun updateTextVisibility() {
        val selected = selectedIndex()
        for ((index, segment) in segments.withIndex()) {
            segment.text.visibility = if (index == selected) VISIBLE else GONE
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (widthAnimator?.isRunning != true) {
            val targets = targetWidthsFor(measuredWidth)
            if (targets != null && applyWidthParamsIfChanged(targets)) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) gradientShader = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        for (segment in segments) {
            val fg = segment.cell.foreground
            if (fg is GradientRippleDrawable) fg.stopAnimations()
        }
    }

    private fun targetWidthsFor(totalWidth: Int): IntArray? {
        if (totalWidth <= 0) return null
        val collapsed = dp(SEGMENT_MIN_DP)
        val selected = selectedIndex()
        val selectedWidth = (totalWidth - collapsed * (segments.size - 1)).coerceAtLeast(collapsed)
        return IntArray(segments.size) { if (it == selected) selectedWidth else collapsed }
    }

    private fun applyWidthParamsIfChanged(widths: IntArray): Boolean {
        var changed = false
        for ((index, segment) in segments.withIndex()) {
            cellWidths[index] = widths[index]
            val lp = segment.cell.layoutParams as LinearLayout.LayoutParams
            if (lp.width != widths[index]) {
                lp.width = widths[index]
                segment.cell.layoutParams = lp
                changed = true
            }
        }
        return changed
    }

    private fun animateWidths(animate: Boolean) {
        val targets = targetWidthsFor(measuredWidth) ?: return
        widthAnimator?.cancel()
        if (!animate) {
            applyWidthParamsIfChanged(targets)
            invalidate()
            return
        }
        val starts = IntArray(segments.size) { cellWidths[it] }
        widthAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val current = IntArray(segments.size) {
                    (starts[it] + (targets[it] - starts[it]) * fraction).toInt()
                }
                applyWidthParamsIfChanged(current)
                invalidate()
            }
            start()
        }
    }

    private fun applyForegroundsAndTints() {
        val selected = selectedIndex()
        for ((index, segment) in segments.withIndex()) {
            val isSelected = index == selected
            val fgColor = if (isSelected) selectedForeground() else unselectedForeground()
            segment.text.setTextColor(fgColor)
            val drawable = AppCompatResources.getDrawable(context, segment.iconRes)
            if (drawable != null) {
                segment.icon.setImageDrawable(
                    DisplayUtil.opaqueTintDrawable(context, drawable, fgColor)
                )
            }
            // Selected segment already paints the Scope gradient as its fill, so keep its
            // ripple a faint neutral; unselected segments get the gradient ripple on touch.
            segment.cell.foreground = if (isSelected) {
                rippleForeground()
            } else {
                GradientRippleDrawable(
                    scopeBackground,
                    shapeOval = false,
                    cornerRadiusPx = dp(HEIGHT_DP / 2f).toFloat()
                )
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        ensureGradientShader()

        val sw = outlinePaint.strokeWidth
        val outerInset = sw / 2f + dp(0.5f)
        outlineRect.set(outerInset, outerInset, width - outerInset, height - outerInset)
        val outlineRadius = (height - outerInset * 2f) / 2f

        // Inner edge of the stroke; the fill reaches here so there is no gap
        // between the outline and the selected fill.
        val innerInset = outerInset + sw / 2f

        val selected = segments.getOrNull(selectedIndex())
        if (selected != null && selected.cell.width > 0) {
            val cellLeft = (row.left + selected.cell.left).toFloat()
            val cellRight = (row.left + selected.cell.right).toFloat()
            val left = cellLeft.coerceAtLeast(innerInset)
            val right = cellRight.coerceAtMost(width - innerInset)
            val top = innerInset
            val bottom = height - innerInset
            if (right > left) {
                fillRect.set(left, top, right, bottom)
                val fillRadius = (bottom - top) / 2f
                applyShaderOrColor(fillPaint)
                canvas.drawRoundRect(fillRect, fillRadius, fillRadius, fillPaint)
            }
        }

        applyShaderOrColor(outlinePaint)
        canvas.drawRoundRect(outlineRect, outlineRadius, outlineRadius, outlinePaint)
    }

    private fun ensureGradientShader() {
        if (scopeBackground.mode != ThingBackground.Mode.GRADIENT) {
            gradientShader = null
            return
        }
        if (gradientShader == null || gradientWidth != width) {
            gradientWidth = width
            gradientShader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                scopeBackground.color, scopeBackground.endColor, Shader.TileMode.CLAMP
            )
        }
    }

    private fun applyShaderOrColor(paint: Paint) {
        if (scopeBackground.mode == ThingBackground.Mode.GRADIENT) {
            paint.shader = gradientShader
        } else {
            paint.shader = null
            paint.color = scopeBackground.color
        }
    }

    private fun rippleForeground(): RippleDrawable {
        // Square cells, so a half-height corner radius makes the ripple a circle.
        val radius = dp(HEIGHT_DP / 2f).toFloat()
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        return RippleDrawable(
            ColorStateList.valueOf(BackgroundUtil.adaptiveRippleColor(scopeBackground)),
            null,
            mask
        )
    }

    private fun selectedForeground(): Int {
        if (scopeIsRoot) return SELECTED_FG_LIGHT
        return if (BackgroundUtil.isLight(scopeBackground)) {
            SELECTED_FG_DARK
        } else {
            SELECTED_FG_LIGHT
        }
    }

    private fun unselectedForeground(): Int {
        return ContextCompat.getColor(context, R.color.app_chrome_drawer_item_foreground)
    }

    private fun defaultScopeBackground(): ThingBackground {
        return ThingBackground.gradient(
            ContextCompat.getColor(context, R.color.app_accent),
            ContextCompat.getColor(context, R.color.app_accent2),
            ThingBackground.Orientation.L_R
        )
    }

    private fun normalizeStatus(status: Int): Int {
        return when (status) {
            Def.ThingStatus.UNDERWAY,
            Def.ThingStatus.FINISHED,
            Def.ThingStatus.DELETED -> status
            else -> Def.ThingStatus.UNDERWAY
        }
    }

    private fun dp(value: Float): Int {
        return (resources.displayMetrics.density * value).toInt()
    }

    companion object {
        private const val HEIGHT_DP = 46.0f
        private const val SEGMENT_MIN_DP = 46.0f
        private const val ICON_DP = 18.0f
        private const val SELECTED_FG_DARK = 0xDE000000.toInt()
        private const val SELECTED_FG_LIGHT = 0xF2FFFFFF.toInt()
    }
}
