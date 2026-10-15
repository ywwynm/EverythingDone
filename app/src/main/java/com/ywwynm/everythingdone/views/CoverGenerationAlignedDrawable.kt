package com.ywwynm.everythingdone.views

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable

/** 让系统加载圈和确定进度共用文字的视觉中心，不改变数量行的测量尺寸。 */
internal class CoverGenerationAlignedDrawable(
    private val delegate: Drawable,
    private val iconWidth: Int,
    private val iconHeight: Int,
    private val verticalOffset: () -> Float
) : Drawable(), Animatable, Drawable.Callback {
    private var startOnDraw = false
    private val snapshotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }
    private val snapshotBounds = RectF()
    init { delegate.callback = this }
    override fun getIntrinsicWidth() = iconWidth
    override fun getIntrinsicHeight() = iconHeight
    override fun draw(canvas: Canvas) {
        val side = minOf(bounds.width(), bounds.height())
        val left = bounds.left + (bounds.width() - side) / 2
        val top = bounds.top + (bounds.height() - side) / 2
        delegate.setBounds(left, top, left + side, top + side)
        val save = canvas.save()
        canvas.translate(0f, verticalOffset())
        if (!canvas.isHardwareAccelerated && delegate is Animatable) {
            // 拖动悬浮卡片使用软件快照。系统转圈在渲染线程更新，软件侧只能读到
            // 初始的极短弧线；捕获一个可辨认的固定帧，与快照中冻结的其它动态内容一致。
            snapshotPaint.strokeWidth = (side / 10f).coerceAtLeast(1f)
            val inset = side / 8f
            snapshotBounds.set(left + inset, top + inset, left + side - inset, top + side - inset)
            canvas.drawArc(snapshotBounds, -90f, 270f, false, snapshotPaint)
        } else delegate.draw(canvas)
        canvas.restoreToCount(save)
        // 与 ProgressBar 一致：先让动态图标获得绘制目标，再启动动画。
        if (startOnDraw && canvas.isHardwareAccelerated) {
            startOnDraw = false
            (delegate as? Animatable)?.start()
        }
    }
    override fun setTint(tintColor: Int) { delegate.setTint(tintColor); snapshotPaint.color = tintColor }
    override fun setAlpha(alpha: Int) { delegate.alpha = alpha; snapshotPaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        delegate.colorFilter = colorFilter; snapshotPaint.colorFilter = colorFilter
    }
    override fun onLevelChange(level: Int) = delegate.setLevel(level)
    override fun start() {
        delegate.setVisible(true, false)
        if ((delegate as? Animatable)?.isRunning == false) {
            startOnDraw = true
            invalidateSelf()
        }
    }
    override fun stop() { startOnDraw = false; (delegate as? Animatable)?.stop() }
    override fun isRunning() = startOnDraw || (delegate as? Animatable)?.isRunning == true
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        return delegate.setVisible(visible, restart) || changed
    }
    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) = scheduleSelf(what, at)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
