package com.ywwynm.everythingdone.views

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import kotlin.math.min

/** 在当前窗口直接移动既有媒体层；整个运动不等待截图或 Activity 切换。 */
internal class MediaPreviewReturnAnimation(
    private val root: ViewGroup,
    private val pager: ViewGroup,
    private val page: View,
    private val localImage: RectF,
    private val from: RectF,
    private val fromClip: RectF,
    private val targetImage: RectF,
    private val targetClip: RectF,
    private val targetMedia: ImageView,
    private val targetMediaBounds: RectF,
    private val vx: Float,
    private val vy: Float,
    private val widthVelocity: Float,
    private val updateBackground: (Float) -> Unit,
    private val finished: () -> Unit
) {
    private var startMs = 0L
    private val initialScaleX = page.scaleX
    private val initialScaleY = page.scaleY
    private val initialX = page.translationX
    private val initialY = page.translationY
    private val pivotX = from.left - (localImage.left - page.pivotX) * initialScaleX
    private val pivotY = from.top - (localImage.top - page.pivotY) * initialScaleY
    private val host = pager.parent as ViewGroup
    private val under = MediaLayer()
    private val over = MediaLayer()
    private val density = root.resources.displayMetrics.density
    private fun spring(start: Float, target: Float, velocity: Float, excursion: Float) =
        MediaPreviewReturnMotion.Spring(start, target, velocity, excursion, .5f * density, 12f * density)
    private val centerX = spring(from.centerX(), targetImage.centerX(), vx, min(36f * density, root.width * .04f))
    private val centerY = spring(from.centerY(), targetImage.centerY(), vy, min(36f * density, root.height * .04f))
    private val imageWidth = spring(from.width(), targetImage.width(), widthVelocity,
        min(36f * density, min(from.width(), targetImage.width()) * .06f))
    private val effects = MediaPreviewReturnMotion.Spring(0f, 1f, 0f, 0f, .001f, .024f)
    private val durationMs = maxOf(centerX.durationMs, centerY.durationMs, imageWidth.durationMs, effects.durationMs)
    private var completed = false
    private var cancelled = false
    private val frames = Choreographer.getInstance()
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (completed || cancelled) return
            // 使用本次显示帧的时刻，排队延迟不会改变相邻帧的运动节奏。
            val elapsed = (frameTimeNanos / 1_000_000L - startMs).coerceAtLeast(0L).toFloat()
            val p = (elapsed / durationMs).coerceIn(0f, 1f)
            apply(elapsed, p)
            if (p >= 1f) complete() else frames.postFrameCallback(this)
        }
    }

    fun start() {
        host.addView(under, host.indexOfChild(pager), ViewGroup.LayoutParams(-1, -1))
        host.addView(over, host.indexOfChild(pager) + 1, ViewGroup.LayoutParams(-1, -1))
        startMs = SystemClock.uptimeMillis()
        apply(0f, 0f)
        frames.postFrameCallback(frame)
    }

    private fun apply(elapsedMs: Float, p: Float) {
        val ease = if (p >= 1f) 1f else effects.position(elapsedMs).coerceIn(0f, 1f)
        val width = if (p >= 1f) targetImage.width() else imageWidth.position(elapsedMs)
        val height = width * from.height() / from.width()
        val cx = if (p >= 1f) targetImage.centerX() else centerX.position(elapsedMs)
        val cy = if (p >= 1f) targetImage.centerY() else centerY.position(elapsedMs)
        val moving = RectF(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2)
        fun fraction(start: Float, end: Float) = start + (end - start) * ease
        val left = fraction((fromClip.left - from.left) / from.width(), (targetClip.left - targetImage.left) / targetImage.width())
        val right = fraction((fromClip.right - from.left) / from.width(), (targetClip.right - targetImage.left) / targetImage.width())
        val top = fraction((fromClip.top - from.top) / from.height(), (targetClip.top - targetImage.top) / targetImage.height())
        val bottom = fraction((fromClip.bottom - from.top) / from.height(), (targetClip.bottom - targetImage.top) / targetImage.height())
        val clip = RectF(moving.left + left * width, moving.top + top * height,
            moving.left + right * width, moving.top + bottom * height)
        val ratio = width / from.width()
        page.scaleX = initialScaleX * ratio
        page.scaleY = initialScaleY * ratio
        page.translationX = initialX + moving.left - from.left - (from.left - pivotX) * (ratio - 1f)
        page.translationY = initialY + moving.top - from.top - (from.top - pivotY) * (ratio - 1f)
        // 原窗口外没有实时像素的部分由详情图片补齐，不把叠在图片上的工具栏带入运动。
        val localClip = RectF(localImage.left + left * localImage.width(), localImage.top + top * localImage.height(),
            localImage.left + right * localImage.width(), localImage.top + bottom * localImage.height())
        if (localClip.intersect(0f, 0f, page.width.toFloat(), page.height.toFloat())) {
            page.clipBounds = Rect().also(localClip::roundOut)
        }
        under.update(moving, clip, 1f)
        over.update(moving, clip, ((p - .84f) / .16f).coerceIn(0f, 1f))
        updateBackground(ease)
        MediaPreviewTransition.mark("return-progress:$p")
        MediaPreviewTransition.reportInteractiveFrame(p, elapsedMs, moving, clip)
    }

    fun complete() {
        if (completed || cancelled) return
        completed = true
        frames.removeFrameCallback(frame)
        apply(durationMs.toFloat(), 1f)
        // onPause 会停止播放器，端点由详情画面覆盖，避免露出重置后的全屏图。
        page.alpha = 0f
        finished()
    }

    fun close() {
        cancelled = true
        frames.removeFrameCallback(frame)
        host.removeView(under)
        host.removeView(over)
    }

    private inner class MediaLayer : View(page.context) {
        private val moving = RectF(from)
        private val clip = RectF(fromClip)
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        fun update(image: RectF, crop: RectF, opacity: Float) {
            moving.set(image); clip.set(crop); alpha = opacity; invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            if (targetMedia.drawable == null) return
            val save = canvas.save()
            val offset = ThingDetailTransition.bounds(this, root)
            canvas.translate(-offset.left, -offset.top)
            canvas.clipRect(clip)
            val scale = moving.width() / targetImage.width()
            canvas.translate(moving.left + (targetMediaBounds.left - targetImage.left) * scale,
                moving.top + (targetMediaBounds.top - targetImage.top) * scale)
            canvas.scale(scale, scale)
            // 只重绘已解码的图片控件；祖先工具栏、删除按钮和徽标保持在固定详情背景中。
            targetMedia.draw(canvas)
            canvas.restoreToCount(save)
        }
    }
}
