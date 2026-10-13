package com.ywwynm.everythingdone.views.particledismiss

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.EGL14
import android.opengl.GLES30
import android.os.Bundle
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** 对照正式分层绘制与底图合并，包括半透明、移位、裁剪和倒放。 */
class ParticlePageCompositeProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val latch = CountDownLatch(1)
        var result = Result.success("")
        ParticleGl.post { result = runCatching { compare() }; latch.countDown() }
        check(latch.await(50, TimeUnit.SECONDS)) { "合成像素核对超时" }
        finish(if (result.isSuccess) 0 else 1, Bundle().apply {
            putString("result", result.getOrElse { it.stackTraceToString() })
        })
    }

    private fun compare(): String {
        check(ParticleGl.ensureContextReady())
        val width = 384; val height = 640
        val target = EGL14.eglCreatePbufferSurface(ParticleGl.display, ParticleGl.config,
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        check(target != EGL14.EGL_NO_SURFACE && ParticleGl.bind(target))
        val base = ParticleGlBitmapLayer("pixel-reference", -2, false, underlay = true)
        val bitmapRenderer = ParticleGlBitmapRenderer(targetContext.assets)
        val bitmap = Bitmap.createBitmap(width - 13, height - 21, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (y in 0 until bitmap.height step 13) {
            paint.color = Color.argb(y % 256, y % 231, 90, 230 - y % 217)
            canvas.drawRect(0f, y.toFloat(), bitmap.width.toFloat(), y + 13f, paint)
        }
        paint.color = 0xffed872e.toInt(); canvas.drawCircle(88f, 153f, 37f, paint)
        paint.color = 0x884560ed.toInt(); paint.textSize = 32f
        canvas.drawText("Aa 12 阴影", 24f, 200f, paint)
        val hardware = checkNotNull(bitmap.copy(Bitmap.Config.HARDWARE, false))
        val pool = ParticleGlHardwareImages(); base.hardwareImages = pool
        bitmapRenderer.upload(base, hardware)
        val particle = Bitmap.createBitmap(209, 267, Bitmap.Config.ARGB_8888)
        Canvas(particle).apply {
            paint.color = 0xdddde5ed.toInt(); drawRoundRect(3f, 3f, 206f, 264f, 15f, 15f, paint)
            paint.color = 0xff325d84.toInt(); drawText("8 0 Test", 18f, 80f, paint)
        }
        val bytes = ByteBuffer.allocateDirect(particle.byteCount).apply { particle.copyPixelsToBuffer(this); rewind() }
        val a = ByteArray(width * height * 4); val b = ByteArray(a.size)
        val pixels = ByteBuffer.allocateDirect(a.size)
        var cases = 0; var maximum = 0; var differing = 0L
        try {
            for (additive in listOf(false, true)) for (alpha in listOf(1f, .84f)) {
                val model = ParticleDriftModel.random(particle.width, particle.height, -1f, 0f, 1f,
                    ParticleDriftModel.seededRandom(20261012), ParticleDriftStyles.all(targetContext.assets))
                val analysis = ParticleDriftContent.analyse(particle.width, particle.height, bytes, model.count)
                val renderer = ParticleDriftRenderer(targetContext.assets, model, additive, alpha)
                renderer.prepare(ParticleMicroflakePreparation.Pixels(particle.width, particle.height, bytes, null), analysis)
                try {
                    for (position in listOf(0, 17, -11)) for (fraction in listOf(0f, .35f, .75f, 1f, .35f)) {
                        base.x = position.toFloat(); base.y = (position * 2).toFloat()
                        val time = model.startSeconds + (model.totalSeconds - model.startSeconds) * fraction
                        for (merged in listOf(false, true)) {
                            check(ParticleGl.bind(target))
                            GLES30.glViewport(0, 0, width, height)
                            GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                            renderer.draw(time, 43f, 212f, beforeComposite = {
                                if (merged) base else { bitmapRenderer.draw(listOf(base), width, height); null }
                            })
                            pixels.clear()
                            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                            pixels.get(if (merged) b else a)
                        }
                        for (i in a.indices) {
                            val delta = abs((a[i].toInt() and 255) - (b[i].toInt() and 255))
                            if (delta != 0) differing++
                            maximum = maxOf(maximum, delta)
                        }
                        check(maximum <= 2) { "底图合成改变像素：max=$maximum case=$cases" }
                        cases++
                    }
                } finally { renderer.close() }
            }
        } finally {
            bitmapRenderer.release(base); pool.close(); bitmapRenderer.close()
            bitmap.recycle(); hardware.recycle(); particle.recycle()
            ParticleGl.bindIdle(); EGL14.eglDestroySurface(ParticleGl.display, target)
        }
        return "通过 $cases 组合成对照；最大通道差 $maximum，变化通道 $differing/${cases.toLong() * a.size}"
    }
}
