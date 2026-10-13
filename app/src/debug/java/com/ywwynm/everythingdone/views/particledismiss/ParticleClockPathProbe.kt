package com.ywwynm.everythingdone.views.particledismiss

import android.animation.ValueAnimator
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import com.github.adnansm.timelytextview.TimelyClockView

/** 同一字体、颜色和插值状态，对照缓存与每次强制重建；不启动页面或写入设置。 */
class ParticleClockPathProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = runCatching {
            var cases = 0
            runOnMainSync {
                val cached = TimelyClockView(targetContext)
                val rebuilt = TimelyClockView(targetContext)
                val slotsField = TimelyClockView::class.java.getDeclaredField("slots").apply { isAccessible = true }
                val views = listOf(cached, rebuilt)
                fun slots(view: TimelyClockView) = slotsField.get(view) as Array<*>
                fun animator(slot: Any): ValueAnimator? = slot.javaClass.getDeclaredField("animator")
                    .apply { isAccessible = true }.get(slot) as? ValueAnimator
                fun reset(view: TimelyClockView) = slots(view).forEach { slot ->
                    checkNotNull(slot).javaClass.getDeclaredField("pathShape").apply { isAccessible = true }.set(slot, null)
                }
                val imageA = Bitmap.createBitmap(430, 190, Bitmap.Config.ARGB_8888)
                val imageB = Bitmap.createBitmap(430, 190, Bitmap.Config.ARGB_8888)
                try {
                    for (style in checkNotNull(targetContext.assets.list("timely")).filter { it.endsWith(".json") }) {
                        views.forEach { it.setStyleName(style.removeSuffix(".json")) }
                        for (fill in listOf(false, true)) {
                            views.forEach { view ->
                                view.setRenderMode(fill)
                                view.setHostDark(fill)
                                view.setInkGradient(0xfffa8624.toInt(), 0xff468dee.toInt(), 0)
                                view.animateDigits(intArrayOf(0, 1, 2, 9, 3, 6), intArrayOf(0, 1, 3, 0, 0, 4), 5404000L)
                                slots(view).forEach { animator(checkNotNull(it))?.pause() }
                            }
                            for ((index, fraction) in listOf(0f, .15f, .5f, .8f, 1f, .5f).withIndex()) {
                                views.forEach { view ->
                                    slots(view).forEach { animator(checkNotNull(it))?.setCurrentFraction(fraction) }
                                    val width = if (index % 2 == 0) 430 else 390
                                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                        View.MeasureSpec.makeMeasureSpec(190, View.MeasureSpec.EXACTLY))
                                    view.layout(0, 0, width, 190)
                                }
                                repeat(2) {
                                    imageA.eraseColor(0); imageB.eraseColor(0)
                                    reset(rebuilt)
                                    cached.draw(Canvas(imageA)); rebuilt.draw(Canvas(imageB))
                                    check(imageA.sameAs(imageB)) { "计时路径变化 $style fill=$fill progress=$fraction repeat=$it" }
                                    cases++
                                }
                            }
                            views.forEach { view -> slots(view).forEach { animator(checkNotNull(it))?.cancel() } }
                        }
                    }
                } finally { imageA.recycle(); imageB.recycle() }
            }
            "通过 $cases 组逐像素对照，覆盖全部字体、两种描画模式、原地插值、尺寸改变及重复绘制"
        }
        finish(if (result.isSuccess) 0 else 1, Bundle().apply {
            putString("result", result.getOrElse { it.stackTraceToString() })
        })
    }
}
