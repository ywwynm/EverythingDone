package com.ywwynm.everythingdone.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

import com.ywwynm.everythingdone.R

/**
 * 「黑 → 全色相饱和彩虹 → 白」一维复合渐变颜色条，带可拖 handle。
 *
 * 见 docs/adr/0005-thing-background-editor-color-model.md：颜色条只承担粗选与
 * 可视化，RGB/Hex 才是精确数据源。拖拽 handle 落在曲线上取色；外部赋色时用
 * 「曲线采样最近点」反向定位 handle，handle 圆点内部始终填充当前真实颜色。
 *
 * 内部绘制尺寸（条高、handle 半径等）读自 dimens，控件之间的间距由布局 XML 负责。
 */
class ColorSpectrumBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 当前颜色变更回调；fromUser=true 表示用户拖拽产生。 */
    var onColorChanged: ((color: Int, fromUser: Boolean) -> Unit)? = null

    private var currentColor: Int = Color.GRAY
    private var handleT: Float = 0f

    private val barHeight = resources.getDimension(R.dimen.tbe_spectrum_bar_height)
    private val barCorner = resources.getDimension(R.dimen.tbe_spectrum_bar_corner)
    private val handleRadius = resources.getDimension(R.dimen.tbe_spectrum_handle_radius)
    private val handleStroke = resources.getDimension(R.dimen.tbe_spectrum_handle_stroke)
    private val minHeight = resources.getDimensionPixelSize(R.dimen.tbe_spectrum_min_height)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = handleStroke
    }
    private val handleHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = handleStroke
        color = 0x33000000
    }

    private val barRect = RectF()
    private var trackLeft = 0f
    private var trackRight = 0f

    init {
        isClickable = true
        isFocusable = true
    }

    /** 外部赋色：移动 handle 到最近点，handle 内显真实色。notify 控制是否回调。 */
    fun setColor(color: Int, notify: Boolean = false) {
        val opaque = color or -0x1000000
        currentColor = opaque
        handleT = nearestSpectrumT(opaque)
        invalidate()
        if (notify) onColorChanged?.invoke(opaque, false)
    }

    fun getColor(): Int = currentColor

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        // 高度需容下 handle 圆 + 外侧 halo（半径 = handleRadius + handleStroke/2）。
        val handleExtent = ((handleRadius + handleStroke) * 2f).toInt()
        val desiredH = maxOf(minHeight, handleExtent + paddingTop + paddingBottom)
        val h = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // handle 圆(含 halo)不能超出边界，故 track 两端内缩 handleRadius + handleStroke。
        trackLeft = paddingLeft + handleRadius + handleStroke
        trackRight = w - paddingRight - handleRadius - handleStroke
        val cy = h / 2f
        barRect.set(trackLeft, cy - barHeight / 2f, trackRight, cy + barHeight / 2f)
        barPaint.shader = LinearGradient(
            barRect.left, 0f, barRect.right, 0f,
            SPECTRUM_STOPS, SPECTRUM_POSITIONS, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(barRect, barCorner, barCorner, barPaint)

        val cx = trackLeft + (trackRight - trackLeft) * handleT
        val cy = height / 2f
        handleFillPaint.color = currentColor
        canvas.drawCircle(cx, cy, handleRadius, handleFillPaint)
        // 白描边 + 外侧淡黑 halo，保证 handle 在任意底色上都可见。
        handleStrokePaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, handleRadius - handleStroke / 2f, handleStrokePaint)
        canvas.drawCircle(cx, cy, handleRadius + handleStroke / 2f, handleHaloPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(x: Float) {
        val span = (trackRight - trackLeft).coerceAtLeast(1f)
        handleT = ((x - trackLeft) / span).coerceIn(0f, 1f)
        currentColor = spectrumColorAt(handleT) or -0x1000000
        invalidate()
        onColorChanged?.invoke(currentColor, true)
    }

    companion object {
        // 黑 → 红 → 黄 → 绿 → 青 → 蓝 → 品红 → 白：一根带子里同时含黑、全部色相、白。
        private val SPECTRUM_STOPS = intArrayOf(
            0xFF000000.toInt(), // black
            0xFFFF0000.toInt(), // red
            0xFFFFFF00.toInt(), // yellow
            0xFF00FF00.toInt(), // green
            0xFF00FFFF.toInt(), // cyan
            0xFF0000FF.toInt(), // blue
            0xFFFF00FF.toInt(), // magenta
            0xFFFFFFFF.toInt()  // white
        )
        private val SPECTRUM_POSITIONS: FloatArray = run {
            val n = SPECTRUM_STOPS.size
            FloatArray(n) { it.toFloat() / (n - 1) }
        }

        /** t∈[0,1] → 沿曲线插值的颜色（RGB 线性插值，停靠点足够密视觉干净）。 */
        fun spectrumColorAt(t: Float): Int {
            val tt = t.coerceIn(0f, 1f)
            val n = SPECTRUM_STOPS.size
            val scaled = tt * (n - 1)
            val i = scaled.toInt().coerceIn(0, n - 2)
            val f = scaled - i
            return lerpColor(SPECTRUM_STOPS[i], SPECTRUM_STOPS[i + 1], f)
        }

        /** color → 最近曲线点的 t（采样 + 加权 RGB 距离），仅用于 handle 摆放。 */
        fun nearestSpectrumT(color: Int): Float {
            var bestT = 0f
            var bestD = Float.MAX_VALUE
            val steps = 256
            for (k in 0..steps) {
                val t = k.toFloat() / steps
                val d = weightedRgbDistance(spectrumColorAt(t), color)
                if (d < bestD) {
                    bestD = d
                    bestT = t
                }
            }
            return bestT
        }

        private fun lerpColor(a: Int, b: Int, f: Float): Int {
            val ar = Color.red(a); val ag = Color.green(a); val ab = Color.blue(a)
            val br = Color.red(b); val bg = Color.green(b); val bb = Color.blue(b)
            val r = Math.round(ar + (br - ar) * f)
            val g = Math.round(ag + (bg - ag) * f)
            val bl = Math.round(ab + (bb - ab) * f)
            return Color.rgb(r, g, bl)
        }

        private fun weightedRgbDistance(a: Int, b: Int): Float {
            val dr = (Color.red(a) - Color.red(b)) * 0.30f
            val dg = (Color.green(a) - Color.green(b)) * 0.59f
            val db = (Color.blue(a) - Color.blue(b)) * 0.11f
            return dr * dr + dg * dg + db * db
        }
    }
}
