package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

/** 始终变化的普通 View：录像直接检验背景是否被静态底图冻结。 */
internal class ParticleDynamicBackdropProbe(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val began = SystemClock.uptimeMillis()
    override fun onDraw(canvas: Canvas) {
        val elapsed = SystemClock.uptimeMillis() - began
        canvas.drawColor(Color.rgb(19, 72, 85))
        val x = (elapsed % 1200) / 1200f * width
        paint.color = Color.rgb(255, 198, 63)
        canvas.drawRect(x, 0f, x + width / 12f, height.toFloat(), paint)
        paint.color = Color.WHITE; paint.textSize = 15f * resources.displayMetrics.scaledDensity
        canvas.drawText("动态背景 ${elapsed / 100} · 色条应持续移动", 16f, height * .65f, paint)
        postInvalidateOnAnimation()
    }
}
