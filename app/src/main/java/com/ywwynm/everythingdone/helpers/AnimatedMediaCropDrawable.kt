package com.ywwynm.everythingdone.helpers

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import androidx.annotation.RequiresApi

/**
 * 平台动态 WebP 不能交给 Glide 的 BitmapTransformation；在每帧绘制时裁剪原动画。
 * 只持有本次请求独占的解码器，不生成逐帧位图，也不改变全屏查看器的原图请求。
 */
@RequiresApi(28)
internal class AnimatedMediaCropDrawable(
    internal val animation: AnimatedImageDrawable,
    private val width: Int,
    private val height: Int,
    crop: MediaCropBitmapRenderer.Crop
) : Drawable(), Drawable.Callback, Animatable {
    private val matrix = MediaCropBitmapRenderer.cropMatrix(
        animation.intrinsicWidth, animation.intrinsicHeight, width, height, crop)

    init {
        animation.setBounds(0, 0, animation.intrinsicWidth, animation.intrinsicHeight)
        animation.callback = this
    }

    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width().toFloat() / width, bounds.height().toFloat() / height)
        canvas.clipRect(0, 0, width, height)
        canvas.concat(matrix)
        animation.draw(canvas)
        canvas.restoreToCount(saved)
    }

    fun setPlayback(play: Boolean, loop: Boolean, onFinished: () -> Unit) {
        animation.clearAnimationCallbacks()
        if (!play) {
            stop()
            return
        }
        animation.repeatCount = if (loop) AnimatedImageDrawable.REPEAT_INFINITE else 0
        if (!loop) animation.registerAnimationCallback(object : Animatable2.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) {
                // 忽略暂停前已排队、在新一轮播放期间才到达的结束通知。
                if (!animation.isRunning) onFinished()
            }
        })
        start() // 平台对运行中的动画幂等；停止后的 start 从首帧开始。
    }

    override fun start() = animation.start()
    override fun stop() {
        // 主动暂停不能冒充一轮完成，否则逐一队列会跳过当前附件。
        animation.clearAnimationCallbacks()
        animation.stop()
    }
    override fun isRunning(): Boolean = animation.isRunning
    override fun getIntrinsicWidth(): Int = width
    override fun getIntrinsicHeight(): Int = height
    override fun setAlpha(alpha: Int) { animation.alpha = alpha }
    override fun getAlpha(): Int = animation.alpha
    override fun setColorFilter(colorFilter: ColorFilter?) { animation.colorFilter = colorFilter }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) = scheduleSelf(what, at)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
}
