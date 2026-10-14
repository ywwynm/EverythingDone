package com.ywwynm.everythingdone.views

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.animation.AnimationUtils
import android.widget.EdgeEffect
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.views.particledismiss.ParticleDoingSceneContent
import com.ywwynm.everythingdone.views.particledismiss.ParticleBeatTrace
import java.util.IdentityHashMap

/** Doing 卡片按实际动画时间推进惯性，避免系统提前执行回调后在边界停等。 */
class DoingRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.recyclerview.R.attr.recyclerViewStyle
) : StablerRecyclerView(context, attrs, defStyleAttr) {
    private val frameClock = Choreographer.getInstance()
    // 回到 UI 线程后不依赖原窗口同步屏障，避免真实 vsync 已到却再等一轮遍历。
    private val callbackHandler = checkNotNull(Looper.myLooper()).let { looper ->
        if (Build.VERSION.SDK_INT >= 28) Handler.createAsync(looper) else Handler(looper)
    }
    private val callbacks = IdentityHashMap<Runnable, MutableList<PendingFrame>>()
    private val edges = arrayOfNulls<EdgeEffect>(4)
    internal val hasActiveEdgeEffect get() = edges.any { it?.isFinished == false }

    init {
        edgeEffectFactory = object : EdgeEffectFactory() {
            override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect =
                object : EdgeEffect(view.context) {
                    override fun draw(canvas: Canvas): Boolean {
                        // 原窗口也可能提前更新子 View 的显示列表。只延后系统边缘效果的
                        // 时间推进，卡片内容仍正常绘制；真实时间到来后继续执行原系统算法。
                        if (AnimationUtils.currentAnimationTimeMillis() - SystemClock.uptimeMillis() > 1L)
                            return !isFinished
                        return super.draw(canvas)
                    }
                }.also { edges[direction] = it }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // RecyclerView 在尺寸变化时丢弃旧边缘效果，不能继续观察旧实例。
        if (w != oldw || h != oldh) edges.fill(null)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        // 首次 absorb 的 draw 不一定返回 needsInvalidate，但效果仍然活动。
        requestIdleFrame()
        if (ParticleBeatTrace.enabled) ParticleBeatTrace.doingDrawObserver?.invoke(this)
    }

    override fun postOnAnimation(action: Runnable) {
        // 保留 View 在挂载前排队的行为。
        if (!isAttachedToWindow) {
            super.postOnAnimation(action)
            return
        }
        val frame = PendingFrame(action)
        synchronized(callbacks) {
            callbacks.getOrPut(action) { mutableListOf() }.add(frame)
            frameClock.postFrameCallback(frame)
        }
    }

    override fun removeCallbacks(action: Runnable): Boolean {
        val removed = synchronized(callbacks) { callbacks.remove(action) }
        removed?.forEach(PendingFrame::cancel)
        return super.removeCallbacks(action) || removed != null
    }

    override fun postInvalidateOnAnimation() {
        super.postInvalidateOnAnimation()
        if (Build.VERSION.SDK_INT < 34 || !isAttachedToWindow) return
        if (Looper.myLooper() != callbackHandler.looper) {
            callbackHandler.post { requestIdleFrame() }
        } else requestIdleFrame()
    }

    private fun requestIdleFrame() {
        if (Build.VERSION.SDK_INT < 34 || scrollState != SCROLL_STATE_IDLE || !hasActiveEdgeEffect) return
        // 到边界后 ViewFlinger 已停止，但 EdgeEffect.draw 仍会请求下一帧。
        // 让整页所有者按真实 vsync 接续这次重绘，避免预测遍历结束后的额外空档。
        var ancestor = parent
        while (ancestor != null) {
            if (ancestor is ParticleDoingSceneContent) {
                ancestor.scene?.requestAnimationFrame(this)
                return
            }
            ancestor = ancestor.parent
        }
    }

    override fun onDetachedFromWindow() {
        val pending = synchronized(callbacks) {
            callbacks.values.flatten().also { callbacks.clear() }
        }
        pending.forEach(PendingFrame::cancel)
        super.onDetachedFromWindow()
    }

    private inner class PendingFrame(private val action: Runnable) : Choreographer.FrameCallback, Runnable {
        override fun doFrame(frameTimeNanos: Long) {
            synchronized(callbacks) {
                if (callbacks[action]?.contains(this) != true) return
                // 普通帧保留 UI 动画阶段的执行时机；预测帧可能沿用旧 frameTimeNanos，
                // 却锁定未来动画时间，因此直接比较 OverScroller 使用的动画时间源。
                // 等当前动画阶段返回后执行，不能反复等待下一帧而让惯性停住。
                if (AnimationUtils.currentAnimationTimeMillis() - SystemClock.uptimeMillis() > 1L) {
                    callbackHandler.post(this)
                    return
                }
            }
            run()
        }

        override fun run() {
            synchronized(callbacks) {
                val pending = callbacks[action] ?: return
                if (!pending.remove(this)) return
                if (pending.isEmpty()) callbacks.remove(action)
            }
            // 先消耗本次注册，再执行，允许 ViewFlinger 在内部重新投递或取消自己。
            action.run()
        }

        fun cancel() {
            frameClock.removeFrameCallback(this)
            callbackHandler.removeCallbacks(this)
        }
    }
}
