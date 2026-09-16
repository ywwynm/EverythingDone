package com.ywwynm.everythingdone.views.particledismiss

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.math.hypot

/**
 * 每张卡片独立持有手势，支持上一张仍在恢复时继续左滑另一张。
 *
 * [allowRightSwipe] 打开时右滑与左滑是同一个动作（正在做事界面左右滑都是完成记事），
 * 粒子按手指方向镜像飞出；首页右滑是「开始做事」，必须保持关闭。
 */
internal class ParticleSwipeAnimator(
    private val activity: Activity,
    private val allowRightSwipe: Boolean = false
) {
    private val sessions = IdentityHashMap<View, Session>()
    private val unavailable = WeakHashMap<View, Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private var warming: Session? = null
    private var touching: Session? = null
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private val cancelWarmup = Runnable { cancelPending() }

    /** 只为本次按中的卡片准备起点；尚未识别滑动时不遮挡卡片、不计算后续轨迹。 */
    fun warm(view: View, event: MotionEvent) {
        cancelPending()
        if (!ValueAnimator.areAnimatorsEnabled() || !view.isAttachedToWindow || sessions.containsKey(view)) return
        // 按下这一刻就把宿主窗口的粒子 Surface 备好：识别滑动之后不能再等表面创建，
        // 也不要在 RecyclerView 绘制过程里往 DecorView 加子 View。
        ParticleWindowSurface.of(activity)
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
                val wrongWay = !allowRightSwipe && dx > touchSlop
                val vertical = kotlin.math.abs(dy) > touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx)
                if (!session.gesture.active && (wrongWay || vertical)) {
                    cancelPending()
                } else {
                    // 第一次越过 slop 的横向位移定方向：这比 ItemTouchHelper 判定滑动更早，
                    // 消散方向因此赶得上在识别之前定下来。
                    if (kotlin.math.abs(dx) > touchSlop) session.aimAt(ParticleSwipeDirection.of(dx))
                    session.moveFinger(dx)
                }
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
            if (if (allowRightSwipe) displacement == 0f else displacement >= 0f) return false
            // 此分支在 RecyclerView 绘制中执行，软件快照可能同步回调并添加叠加层。
            Session(view).also { sessions[view] = it; main.post { if (!it.closed) it.prepare() } }
        }
        if (session.closed) return false
        session.aimAt(ParticleSwipeDirection.of(displacement))
        session.activate()
        // 活跃触摸在输入阶段已经更新，GL 的动画回调早于 RecyclerView.onDraw。
        // 不能在绘制阶段才读取位移，否则落后一拍；松手后仍跟随 ItemTouchHelper 回弹。
        if (!session.finishing && touching !== session) session.track(displacement)
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

        /** 只允许左滑时构造即定向；左右都允许时等第一次横向位移（见 [aimAt]）。 */
        private var direction =
            if (allowRightSwipe) ParticleSwipeDirection.UNKNOWN else ParticleSwipeDirection.LEFT
        var closed = false
            private set
        val finishing get() = completion != null
        private var completion: (() -> Unit)? = null
        private var completionWidth = 0
        private var completionAnimator: ValueAnimator? = null
        private var overlay: ParticleDismissOverlay? = null
        private var capturedBitmap: Bitmap? = null
        /**
         * 卡片被容器截断时，快照只抓露出来的那一块，这里是它相对卡片左上角的偏移。
         * 正在做事界面的列表比卡片矮，截断线以下的内容不在屏幕上（见 [ParticleCardClip]）。
         */
        private var clipOffsetX = 0
        private var clipOffsetY = 0
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
            val clip = ParticleCardClip.visibleRect(view)
            if (clip != null && clip.isEmpty) { dispose(); return }
            clipOffsetX = clip?.left ?: 0
            clipOffsetY = clip?.top ?: 0
            ParticleDismissController.captureContent(view, clip) captured@{ snapshot ->
                if (closed) { snapshot?.recycle(); return@captured }
                val host = activity.window.decorView as? ViewGroup
                if (snapshot == null || host == null || !view.isAttachedToWindow || activity.isFinishing) {
                    snapshot?.recycle(); dispose(); return@captured
                }
                bakeAncestorAlpha(snapshot, host)
                capturedBitmap = snapshot
                buildOverlay(host)
            }
            if (!closed && gesture.active) view.alpha = 0f
        }

        /**
         * 粒子画在宿主窗口那一个共享 Surface 上，拿不到卡片各级容器的透明度——正在做事
         * 界面的列表常驻 0.84，快照却是满浓度的，直接播会在接管的那一帧整体提亮。把容器
         * 的透明度之积烤进快照即可；首页各级容器都是不透明的，这里是空操作。
         *
         * 准备期在卡片原位补画的 [drawOriginal] 用的是同一张图、又落在容器自己的画布上，
         * 因此宿主要在粒子接管期间把容器还原成不透明（见 DoingActivity.onChildDraw），
         * 两段画面才都恰好是原来的浓度。
         */
        private fun bakeAncestorAlpha(snapshot: Bitmap, host: ViewGroup) {
            var alpha = 1f
            var parent = view.parent
            while (parent is View && parent !== host) {
                alpha *= parent.alpha
                parent = parent.parent
            }
            if (alpha >= 1f || !snapshot.isMutable) return
            val opacity = (alpha.coerceIn(0f, 1f) * 255f).toInt()
            Canvas(snapshot).drawColor(
                Color.argb(opacity, 0, 0, 0),
                PorterDuff.Mode.DST_IN)
        }

        /** 消散方向由 [direction] 决定，因此定向之前只留快照、不建动画层。 */
        private fun buildOverlay(host: ViewGroup) {
            if (closed || overlay != null || direction == ParticleSwipeDirection.UNKNOWN) return
            val snapshot = capturedBitmap ?: return
            val at = IntArray(2); host.getLocationOnScreen(at)
            val x = (origin[0] - at[0] + clipOffsetX).toFloat()
            val y = (origin[1] - at[1] + clipOffsetY).toFloat()
            val reach = hypot(snapshot.width.toFloat(), snapshot.height.toFloat()) * 1.2f
            val spec = ParticleDismissSpec(snapshot, x, y,
                ParticleSwipeDirection.virtualTouchX(direction, x, snapshot.width, reach),
                y + snapshot.height / 2f, 1f, (Math.random() * Int.MAX_VALUE).toInt())
            overlay = ParticleDismissOverlay(activity, spec,
                onAnimationStarted = Runnable {
                    if (!closed && gesture.active) { view.alpha = 0f; if (finishing) animateCompletion() }
                },
                onDone = Runnable { if (!closed) dispose() },
                onPresentedProgress = { progress ->
                    if (finishing && progress >= 1f) dispose()
                }, gesture = gesture).also {
                    // 粒子画面在宿主窗口那一个共享 Surface 上，本层只是生命周期锚点；
                    // 「识别之前不显示」由会话的可见性开关控制，不再靠 View 的 alpha。
                    it.setParticleVisible(gesture.active)
                    ParticleOverlayHost.attach(host, it)
                }
        }

        /**
         * 定向。同一次手势里划过原点换方向时允许改向，代价是重建动画层；已经有粒子上屏
         * 就锁定，不能让屏幕上正在飞的粒子突然掉头。
         */
        fun aimAt(next: Int) {
            if (closed || next == ParticleSwipeDirection.UNKNOWN || next == direction) return
            if (direction != ParticleSwipeDirection.UNKNOWN && gesture.presented) return
            direction = next
            val old = overlay
            overlay = null
            // 改向可能发生在输入分发或 RecyclerView 的绘制过程里，不能同步增删动画层。
            main.post {
                old?.release()
                if (!closed) (activity.window.decorView as? ViewGroup)?.let { buildOverlay(it) }
            }
        }

        /** 会话内部一律按「向前推进」记进度；右滑把位移镜像过来，其余逻辑完全共用。 */
        fun track(displacement: Float) {
            gesture.update(ParticleSwipeDirection.progressDisplacement(direction, displacement), view.width)
        }

        fun activate() {
            if (gesture.active) return
            warming = null
            main.removeCallbacks(cancelWarmup)
            gesture.activate()
            overlay?.setParticleVisible(true)
            view.alpha = 0f
            // 识别的那一刻就唤醒 GL 线程：首个非零画面要落在这一拍里（3.1）。
            ParticleGl.wake()
        }

        fun moveFinger(displacement: Float) {
            if (!closed && !finishing) track(displacement)
        }

        fun drawOriginal(canvas: Canvas) {
            val bitmap = capturedBitmap
            if (bitmap != null && !bitmap.isRecycled) {
                canvas.drawBitmap(bitmap, clipOffsetX.toFloat(), clipOffsetY.toFloat(), null)
            }
            else {
                // 捕获尚未返回时同样保留根卡片的自定义圆角。
                val outline = Outline()
                val rect = Rect()
                if (view.clipToOutline) {
                    view.outlineProvider?.getOutline(view, outline)
                    if (outline.getRect(rect)) {
                        val path = Path().apply {
                            addRoundRect(RectF(rect), outline.radius, outline.radius, Path.Direction.CW)
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
            if (done != null) view.translationX =
                ParticleSwipeDirection.parkTranslationX(direction, completionWidth)
            view.alpha = originalAlpha
            val old = overlay
            overlay = null
            capturedBitmap = null
            // reset 也由 RecyclerView.onDraw 在回拖跨过原点时调用。此时 DecorView
            // 仍在遍历自己的子数组，不能同步 removeView 或回调业务改变层级。
            // 等整个绘制调用栈返回后再释放；闭合状态已阻止重复执行。
            // 回拖取消时真实卡片刚刚恢复（view.alpha 已复位），粒子层必须等它上屏之后
            // 才撤——release() 内部按「先显后撤」等宿主窗口的下一次提交。
            main.post {
                old?.release()
                done?.invoke()
            }
        }
    }
}
