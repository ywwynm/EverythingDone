package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.github.adnansm.timelytextview.TimelyClockView

/** 普通设备仍是 FrameLayout；卓易通只将控件的像素交给海浪，输入与无障碍保留原 View。 */
class FableSolForegroundLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    private var capture: FableSolForegroundCapture? = null
    private val observedClocks = mutableListOf<TimelyClockView>()
    internal var performance: FableSolCompositionPerformance? = null
        set(value) {
            if (field === value) return
            observedClocks.forEach { it.setRenderDiagnostics(null) }
            observedClocks.clear()
            field = value
            if (value != null) {
                val observer = object : TimelyClockView.RenderDiagnostics {
                    override fun onMorphStep() = value.clockMorphStep()
                    override fun onClockDraw(startedNs: Long, endedNs: Long) =
                        value.clockDraw(startedNs, endedNs, capture?.renderPending == true)
                }
                fun visit(view: View) {
                    if (view is TimelyClockView) {
                        observedClocks += view
                        view.setRenderDiagnostics(observer)
                    }
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
                visit(this)
                value.event("clock_observers", "count=${observedClocks.size} burstGapMs=500")
            }
        }
    private var composited = false
    private val preDraw = ViewTreeObserver.OnPreDrawListener { performance?.preDraw(); capture?.draw(); true }

    internal fun startForegroundCapture(frame: (FableSolForegroundFrame) -> Unit,
                                        failure: (String) -> Unit) {
        if (capture != null) return
        capture = FableSolForegroundCapture(this, { canvas -> super.dispatchDraw(canvas) }, frame, failure)
        viewTreeObserver.addOnPreDrawListener(preDraw)
        invalidate()
    }

    internal fun showCompositedForeground() {
        if (composited) return
        composited = true
        invalidate()
    }

    internal fun stopForegroundCapture() {
        if (capture == null && !composited) return
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDraw)
        capture?.close()
        capture = null
        composited = false
        invalidateNativeTree()
    }

    internal fun invalidateNativeTree() {
        // 离屏 RenderNode 曾借用子控件显示列表，逐层失效保证回退仍能重新绘制。
        fun refresh(view: View) {
            view.invalidate()
            if (view is ViewGroup) for (i in 0 until view.childCount) refresh(view.getChildAt(i))
        }
        refresh(this)
    }

    internal fun foregroundDiagnostics() = capture?.diagnostics()?.put("composited", composited)

    override fun dispatchDraw(canvas: Canvas) {
        if (!composited) super.dispatchDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        performance = null
        stopForegroundCapture()
        super.onDetachedFromWindow()
    }
}
