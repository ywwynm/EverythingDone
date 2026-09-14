package com.ywwynm.everythingdone.views.particledismiss

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import kotlin.math.hypot

/** 每张卡片独立持有手势，支持上一张仍在恢复时继续左滑另一张。 */
internal class ParticleSwipeAnimator(private val activity: Activity) {
    private val sessions = java.util.IdentityHashMap<View, Session>()
    private val unavailable = java.util.WeakHashMap<View, Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private var warming: Session? = null
    private var touching: Session? = null
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private val cancelWarmup = Runnable { cancelPending() }

    /** 只为本次按中的卡片准备起点；尚未识别左滑时不遮挡卡片、不计算后续轨迹。 */
    fun warm(view: View, event: MotionEvent) {
        cancelPending()
        if (!ValueAnimator.areAnimatorsEnabled() || !view.isAttachedToWindow || sessions.containsKey(view)) return
        unavailable.remove(view)
        downX = event.rawX; downY = event.rawY
        warming = Session(view, initiallyActive = false).also {
            sessions[view] = it
            touching = it
            it.prepare()
        }
        main.postDelayed(cancelWarmup, ViewConfiguration.getLongPressTimeout().toLong())
    }

    fun recordTouch(event: MotionEvent) {
        val session = touching ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                touching = null
                cancelPending()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX; val dy = event.rawY - downY
                if (!session.gesture.active && (dx > touchSlop || kotlin.math.abs(dy) > touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx))) {
                    cancelPending()
                } else session.moveFinger(dx)
            }
        }
    }

    private fun cancelPending() {
        val previous = warming
        warming = null
        main.removeCallbacks(cancelWarmup)
        if (previous != null && !previous.gesture.active) previous.dispose(markUnavailable = false)
    }

    fun draw(canvas: Canvas, view: View, displacement: Float): Boolean {
        if (!ValueAnimator.areAnimatorsEnabled()) { reset(view); return false }
        if (unavailable.containsKey(view) || !view.isAttachedToWindow) return false
        val session = sessions[view] ?: run {
            if (displacement >= 0f) return false
            // 此分支在 RecyclerView 绘制中执行，软件快照可能同步回调并添加叠加层。
            Session(view).also { sessions[view] = it; main.post { if (!it.closed) it.prepare() } }
        }
        if (session.closed) return false
        session.activate()
        // 活跃触摸在输入阶段已经更新，GL 的动画回调早于 RecyclerView.onDraw。
        // 不能在绘制阶段才读取位移，否则落后一拍；松手后仍跟随 ItemTouchHelper 回弹。
        if (!session.finishing && touching !== session) session.gesture.update(displacement, view.width)
        if (!session.gesture.presented) {
            // 准备期间在卡片原位绘制真实内容。实际 View 仍按 dX 移动但透明，
            // ItemTouchHelper 松手时才能从正确位移开始恢复，避免进度突然归零。
            val checkpoint = canvas.save()
            canvas.translate(view.left.toFloat(), view.top.toFloat())
            session.drawOriginal(canvas)
            canvas.restoreToCount(checkpoint)
        }
        return true
    }

    /** 甩动比首次准备更快时，先播放剩余画面再执行完成操作；失败与中断同样只提交一次。 */
    fun finishSwipe(view: View, width: Int, onComplete: () -> Unit): Boolean {
        val session = sessions[view] ?: return false
        session.finish(width, onComplete)
        return true
    }

    fun reset(view: View? = null) {
        cancelPending()
        if (view == null) {
            sessions.values.toList().forEach { it.dispose() }
            unavailable.clear()
        } else {
            sessions[view]?.dispose()
            unavailable.remove(view)
        }
    }

    private inner class Session(private val view: View, initiallyActive: Boolean = true) {
        val gesture = ParticleGestureProgress(initiallyActive)
        var closed = false
            private set
        val finishing get() = completion != null
        private var completion: (() -> Unit)? = null
        private var completionWidth = 0
        private var completionAnimator: ValueAnimator? = null
        private var overlay: ParticleDismissOverlay? = null
        private var capturedBitmap: android.graphics.Bitmap? = null
        private val originalAlpha = view.alpha
        private val origin = IntArray(2)
        private val current = IntArray(2)
        private val originalWidth = view.width
        private val originalHeight = view.height
        private val completionTimeout = Runnable { dispose() }
        private val geometryObserver = ViewTreeObserver.OnPreDrawListener {
            originalPosition(current)
            if (!origin.contentEquals(current) || view.width != originalWidth || view.height != originalHeight) {
                dispose()
            }
            true
        }
        private val attachmentObserver = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { dispose() }
        }

        fun prepare() {
            originalPosition(origin)
            view.addOnAttachStateChangeListener(attachmentObserver)
            view.viewTreeObserver.addOnPreDrawListener(geometryObserver)
            ParticleDismissController.captureContent(view) captured@{ snapshot ->
                if (closed) { snapshot?.recycle(); return@captured }
                val host = activity.window.decorView as? ViewGroup
                if (snapshot == null || host == null || !view.isAttachedToWindow || activity.isFinishing) {
                    snapshot?.recycle(); dispose(); return@captured
                }
                capturedBitmap = snapshot
                val at = IntArray(2); host.getLocationOnScreen(at)
                val x = (origin[0] - at[0]).toFloat()
                val y = (origin[1] - at[1]).toFloat()
                val reach = hypot(snapshot.width.toFloat(), snapshot.height.toFloat()) * 1.2f
                val spec = ParticleDismissSpec(snapshot, x, y,
                    x + snapshot.width / 2f - reach, y + snapshot.height / 2f,
                    1f, (Math.random() * Int.MAX_VALUE).toInt())
                overlay = ParticleDismissOverlay(activity, spec,
                    onAnimationStarted = Runnable {
                        if (!closed && gesture.active) { view.alpha = 0f; if (finishing) animateCompletion() }
                    },
                    onDone = Runnable { if (!closed) dispose() },
                    onPresentedProgress = { progress ->
                        if (finishing && progress >= 1f) dispose()
                    }, gesture = gesture).also {
                        it.alpha = if (gesture.active) 1f else 0f
                        host.addView(it, ViewGroup.LayoutParams(-1, -1))
                    }
            }
            if (!closed && gesture.active) view.alpha = 0f
        }

        fun activate() {
            if (gesture.active) return
            warming = null
            main.removeCallbacks(cancelWarmup)
            gesture.activate()
            overlay?.alpha = 1f
            view.alpha = 0f
        }

        fun moveFinger(displacement: Float) {
            if (!closed && !finishing) gesture.update(displacement, view.width)
        }

        fun drawOriginal(canvas: Canvas) {
            val bitmap = capturedBitmap
            if (bitmap != null && !bitmap.isRecycled) canvas.drawBitmap(bitmap, 0f, 0f, null)
            else {
                // 捕获尚未返回时同样保留根卡片的自定义圆角。
                val outline = android.graphics.Outline()
                val rect = android.graphics.Rect()
                if (view.clipToOutline) {
                    view.outlineProvider?.getOutline(view, outline)
                    if (outline.getRect(rect)) {
                        val path = android.graphics.Path().apply {
                            addRoundRect(android.graphics.RectF(rect), outline.radius, outline.radius, android.graphics.Path.Direction.CW)
                        }
                        canvas.clipPath(path)
                    }
                }
                view.draw(canvas)
            }
        }

        private fun originalPosition(out: IntArray) {
            val parent = view.parent as? View ?: return
            parent.getLocationOnScreen(out)
            // 按压反馈会短暂缩放卡片；它与 ItemTouchHelper 的位移都不改变
            // 布局位置。直接使用父容器坐标，避免缩放中心和像素取整误触清理。
            out[0] += view.left - parent.scrollX
            out[1] += view.top - parent.scrollY
        }

        fun finish(width: Int, onComplete: () -> Unit) {
            completionWidth = width
            completion = onComplete
            gesture.seek(gesture.presentedProgress)
            if (gesture.presentedProgress >= 1f) dispose()
            else if (gesture.presented) animateCompletion()
        }

        private fun animateCompletion() {
            if (completionAnimator != null || closed) return
            val start = gesture.presentedProgress
            completionAnimator = ValueAnimator.ofFloat(start, 1f).apply {
                duration = ((1f - start) * 420).toLong().coerceAtLeast(90)
                interpolator = LinearInterpolator()
                addUpdateListener { gesture.seek(it.animatedValue as Float) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // 等真正呈现末帧；GL 出错或停止提交时不能丢失已经确认的完成操作。
                        main.postDelayed(completionTimeout, 700)
                    }
                })
                start()
            }
        }

        fun dispose(markUnavailable: Boolean = true) {
            if (closed) return
            closed = true
            if (touching === this) touching = null
            sessions.remove(view)
            // 已完成的 holder 在 ItemTouchHelper 清理前仍可能被绘制，不能再次创建粒子。
            if (markUnavailable) unavailable[view] = true
            view.removeOnAttachStateChangeListener(attachmentObserver)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(geometryObserver)
            main.removeCallbacks(completionTimeout)
            completionAnimator?.let { it.removeAllListeners(); it.removeAllUpdateListeners(); it.cancel() }
            completionAnimator = null
            val done = completion
            completion = null
            // 恢复 ItemTouchHelper 正常完成后的屏外位置，避免删除动画重新露出卡片。
            if (done != null) view.translationX = -completionWidth.toFloat()
            view.alpha = originalAlpha
            val old = overlay
            overlay = null
            capturedBitmap = null
            // reset 也由 RecyclerView.onDraw 在回拖跨过原点时调用。此时 DecorView
            // 仍在遍历自己的子数组，不能同步 removeView 或回调业务改变层级。
            // 先隐藏失效画面，等整个绘制调用栈返回后再释放；闭合状态已阻止重复执行。
            old?.alpha = 0f
            main.post {
                old?.release()
                done?.invoke()
            }
        }
    }
}
