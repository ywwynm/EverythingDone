package com.ywwynm.everythingdone.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.ImageView
import com.github.chrisbanes.photoview.PhotoView
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** 只变换当前页的外层容器；PhotoView、动态图及独立 HDR Surface 保留各自的状态。 */
internal class MediaPreviewDismissGesture(
    private val activity: Activity,
    private val pager: ViewGroup,
    private val currentPhoto: () -> PhotoView?,
    private val enabled: () -> Boolean,
    private val excluded: (MotionEvent) -> Boolean,
    private val mediaBounds: (PhotoView) -> RectF?,
    private val background: () -> Bitmap?,
    private val chrome: () -> List<View>,
    private val cancelContent: (MotionEvent) -> Unit,
    private val commit: () -> Unit,
    private val backgroundMedia: () -> RectF? = { null },
    private val backgroundFill: () -> MediaPreviewDetailBackground? = { null }
) {
    private val root get() = activity.window.decorView
    private val density = activity.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
    private var candidate: PhotoView? = null
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var excess = 0f
    private var axisVertical = false
    private var suppressed = false
    private var velocity: VelocityTracker? = null
    private var drag: Drag? = null
    private var animator: ValueAnimator? = null
    private var returnAnimation: MediaPreviewReturnAnimation? = null
    private var releaseVx = 0f
    private var releaseVy = 0f
    private var returning = false
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        val state = drag
        if (state != null && !returning) {
            if (!state.page.isAttachedToWindow || state.photo.drawable !== state.drawable ||
                root.width != state.rootWidth || root.height != state.rootHeight) cancelImmediately()
            else updateClip(state)
        }
        true
    }

    val isActive get() = drag != null

    private class Drag(val photo: PhotoView, val page: View, val backdrop: Backdrop,
                       val rootWidth: Int, val rootHeight: Int, val startX: Float, val startY: Float,
                       val chrome: List<Pair<View, Float>>) {
        val drawable: Drawable? = photo.drawable
        val scaleX = page.scaleX
        val scaleY = page.scaleY
        val alpha = page.alpha
        val translationX = page.translationX
        val translationY = page.translationY
        val pivotX = page.pivotX
        val pivotY = page.pivotY
        val clip = page.clipBounds?.let(::Rect)
        var dx = 0f
        var dy = 0f
    }

    fun dispatch(event: MotionEvent, content: (MotionEvent) -> Boolean): Boolean {
        if (returning) return true
        // 旋转／资源替换可能使旧手势没有尾事件；新 DOWN 始终表示完整的新序列。
        if (suppressed && animator == null && event.actionMasked == MotionEvent.ACTION_DOWN) suppressed = false
        if (animator != null || suppressed) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                suppressed = false
                clearCandidate()
            } else if (event.actionMasked == MotionEvent.ACTION_DOWN) suppressed = true
            return true
        }
        if (drag != null) {
            velocity?.addMovement(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> move(event.x, event.y)
                MotionEvent.ACTION_UP -> {
                    move(event.x, event.y)
                    val tracker = velocity
                    tracker?.computeCurrentVelocity(1000, ViewConfiguration.get(activity).scaledMaximumFlingVelocity.toFloat())
                    val state = drag!!
                    if (MediaPreviewDismissPolicy.shouldDismiss(state.dy, tracker?.xVelocity ?: 0f,
                            tracker?.yVelocity ?: 0f, state.page.height.toFloat(), density)) {
                        releaseVx = tracker?.xVelocity ?: 0f
                        releaseVy = tracker?.yVelocity ?: 0f
                        freezeForReturn()
                        commit()
                    } else rebound()
                    clearCandidate()
                }
                MotionEvent.ACTION_POINTER_DOWN -> { suppressed = true; rebound(); clearCandidate() }
                MotionEvent.ACTION_CANCEL -> { rebound(); clearCandidate() }
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            clearCandidate()
            if (enabled() && !excluded(event)) {
                val photo = currentPhoto()
                val local = photo?.let(mediaBounds)
                if (photo != null && photo.drawable != null && local != null) {
                    val page = photo.parent as? View
                    val hit = page?.let { MediaPreviewTransition.mapToRoot(it, root, local) }
                    if (hit?.contains(event.x, event.y) == true) {
                        candidate = photo
                        downX = event.x; downY = event.y; lastY = event.y
                        velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                    }
                }
            }
            return content(event)
        }
        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            clearCandidate()
            return content(event)
        }
        val photo = candidate
        if (photo == null) return content(event)
        if (!enabled() || currentPhoto() !== photo) { clearCandidate(); return content(event) }
        velocity?.addMovement(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val dx = event.x - downX
            val dy = event.y - downY
            if (!axisVertical) {
                if (abs(dx) > slop && abs(dx) >= abs(dy)) {
                    clearCandidate(); return content(event)
                }
                axisVertical = MediaPreviewDismissPolicy.vertical(dx, dy, slop)
            }
            val step = event.y - lastY
            lastY = event.y
            val rect = photo.displayRect
            val remainder = if (axisVertical && rect != null)
                MediaPreviewDismissPolicy.beyondEdge(step, rect.top, rect.bottom, photo.height.toFloat()) else 0f
            excess = if (remainder == 0f || (excess != 0f && sign(remainder) != sign(excess))) remainder
                else excess + remainder
            // 先让 PhotoView 完成这一步的剩余平移，接管时不会丢掉到达边缘的距离。
            val handled = content(event)
            if (axisVertical && abs(excess) > slop && enabled() && photo.drawable != null &&
                photo.parent is View && pager.parent is ViewGroup) {
                cancelContent(event)
                begin(photo, event, excess - sign(excess) * slop)
            }
            return handled || drag != null
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) clearCandidate()
        return content(event)
    }

    private fun begin(photo: PhotoView, event: MotionEvent, initialY: Float) {
        val page = photo.parent as? View ?: return
        val host = pager.parent as? ViewGroup ?: return
        val backdrop = Backdrop(activity, background(), backgroundMedia(), backgroundFill())
        host.addView(backdrop, host.indexOfChild(pager), ViewGroup.LayoutParams(-1, -1))
        val startY = event.y - initialY
        val state = Drag(photo, page, backdrop, root.width, root.height, event.x, startY,
            chrome().map { it to it.alpha })
        drag = state
        releaseVx = 0f; releaseVy = 0f
        val bounds = ThingDetailTransition.bounds(page, root)
        page.pivotX = event.x - bounds.left
        page.pivotY = startY - bounds.top
        root.viewTreeObserver.addOnPreDrawListener(preDraw)
        velocity?.clear()
        velocity?.addMovement(event)
        move(event.x, event.y)
        MediaPreviewTransition.mark("drag-start")
    }

    private fun move(x: Float, y: Float) {
        val state = drag ?: return
        apply(state, x - state.startX, y - state.startY)
    }

    private fun apply(state: Drag, dx: Float, dy: Float) {
        state.dx = dx; state.dy = dy
        val progress = MediaPreviewDismissPolicy.progress(dy, state.page.height.toFloat())
        val scale = MediaPreviewDismissPolicy.scale(progress)
        state.page.scaleX = state.scaleX * scale
        state.page.scaleY = state.scaleY * scale
        state.page.translationX = state.translationX + dx
        state.page.translationY = state.translationY + dy
        state.backdrop.progress = progress
        state.chrome.forEach { (view, alpha) -> view.alpha = alpha * (1f - progress * 3f).coerceAtLeast(0f) }
        updateClip(state)
    }

    private fun updateClip(state: Drag) {
        val area = mediaBounds(state.photo) ?: return
        if (!area.intersect(0f, 0f, state.page.width.toFloat(), state.page.height.toFloat())) return
        state.clip?.let { if (!area.intersect(RectF(it))) return }
        val clip = Rect().also(area::roundOut)
        if (state.page.clipBounds != clip) state.page.clipBounds = clip
    }

    private fun rebound() {
        val state = drag ?: return
        val x = state.dx; val y = state.dy
        if (!ValueAnimator.areAnimatorsEnabled()) { restore(); return }
        animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 216L
            interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener { apply(state, x * (it.animatedValue as Float), y * (it.animatedValue as Float)) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { animator = null; restore() }
            })
            start()
        }
        MediaPreviewTransition.mark("drag-rebound")
    }

    /** 固定手势起点交给返回动画，不能先恢复到全屏。 */
    fun freezeForReturn() {
        if (drag == null) return
        animator?.removeAllListeners(); animator?.cancel(); animator = null
        returning = true
        clearCandidate()
    }

    fun animateReturn(target: RectF, targetImage: RectF, targetMedia: ImageView, targetMediaBounds: RectF,
                      detailChrome: MediaPreviewDetailChrome? = null,
                      finished: () -> Unit): Boolean {
        val state = drag ?: return false
        if (returnAnimation != null) return true
        val local = mediaBounds(state.photo) ?: return false
        val from = MediaPreviewTransition.mapToRoot(state.page, root, local)
        val clip = state.page.clipBounds?.let { MediaPreviewTransition.mapToRoot(state.page, root, RectF(it)) } ?: RectF(from)
        if (!clip.intersect(0f, 0f, root.width.toFloat(), root.height.toFloat()) || from.isEmpty || targetImage.isEmpty) return false
        freezeForReturn()
        val progress = MediaPreviewDismissPolicy.progress(state.dy, state.page.height.toFloat())
        val scaleSpeed = if (progress > 0f && progress < 1f)
            -.36f * sign(state.dy) * releaseVy / (state.page.height * .4f) * state.scaleX else 0f
        val vx = releaseVx + (local.centerX() - state.page.pivotX) * scaleSpeed
        val vy = releaseVy + (local.centerY() - state.page.pivotY) * scaleSpeed
        val widthSpeed = local.width() * scaleSpeed
        val initialBackground = state.backdrop.progress
        val initialChrome = state.chrome.map { (view, _) -> view to view.alpha }
        returnAnimation = MediaPreviewReturnAnimation(root as ViewGroup, pager, state.page, RectF(local), from, RectF(clip),
            targetImage, target, targetMedia, targetMediaBounds, vx, vy, widthSpeed, { p ->
                state.backdrop.progress = initialBackground + (1f - initialBackground) * p
                initialChrome.forEach { (view, alpha) -> view.alpha = alpha * (1f - p) }
            }, finished, detailChrome).also { it.start() }
        return true
    }

    /** 返回端点一直遮盖到窗口移除；后台中断则立即完成，不恢复出全屏图。 */
    fun onPause() {
        returnAnimation?.complete() ?: close()
    }

    fun cancelImmediately() {
        if (returnAnimation != null) { returnAnimation?.complete(); return }
        val wasActive = isActive
        animator?.removeAllListeners(); animator?.cancel(); animator = null
        returning = false
        clearCandidate()
        restore()
        suppressed = wasActive
    }

    fun close() {
        returnAnimation?.close(); returnAnimation = null
        cancelImmediately(); suppressed = false
    }

    private fun restore() {
        val state = drag ?: return
        drag = null
        root.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        state.page.apply {
            scaleX = state.scaleX; scaleY = state.scaleY
            alpha = state.alpha
            translationX = state.translationX; translationY = state.translationY
            pivotX = state.pivotX; pivotY = state.pivotY
            clipBounds = state.clip
        }
        state.chrome.forEach { (view, alpha) -> view.alpha = alpha }
        (state.backdrop.parent as? ViewGroup)?.removeView(state.backdrop)
        MediaPreviewTransition.mark("drag-restored")
    }

    private fun clearCandidate() {
        candidate = null
        axisVertical = false
        excess = 0f
        velocity?.recycle(); velocity = null
    }

    private class Backdrop(context: Activity, private val bitmap: Bitmap?, private val omittedMedia: RectF?,
                           private val fill: MediaPreviewDetailBackground?) : View(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var progress = 0f
            set(value) { field = value; invalidate() }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            if (bitmap != null && !bitmap.isRecycled) {
                val offset = ThingDetailTransition.bounds(this, rootView)
                val save = canvas.save()
                canvas.translate(-offset.left, -offset.top)
                // 空区显示原位置的记事背景；完整快照仍保留，供端点交接使用。
                omittedMedia?.let {
                    fill?.draw(canvas, it)
                    canvas.clipOutRect(it)
                }
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
                canvas.restoreToCount(save)
            }
            canvas.drawColor(Color.argb(((1f - progress) * 255).roundToInt(), 0, 0, 0))
        }
    }
}
