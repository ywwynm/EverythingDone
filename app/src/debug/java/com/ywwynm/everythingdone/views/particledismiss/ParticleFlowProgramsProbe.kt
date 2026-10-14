package com.ywwynm.everythingdone.views.particledismiss

import android.app.Instrumentation
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.GLES30
import android.os.Bundle
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** 将另一上下文预热并移交的程序与现场编译程序逐像素比较。 */
class ParticleFlowProgramsProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = runCatching {
            val assets = targetContext.assets
            val bitmap = Bitmap.createBitmap(180, 280, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(180 * 280) { i -> if (i % 180 in 20..155 && i / 180 in 15..260)
                (if (i % 23 < 7) 0xff5086ce else 0xffefb154).toInt() else 0 }
            bitmap.setPixels(pixels, 0, 180, 0, 0, 180, 280)
            try {
                val spec = ParticleDismissSpec(bitmap, 30f, 80f, -300f, 160f, 1f, 20261014)
                val input = ParticleMicroflakeRenderer.fromSpec(assets, 384, 640, 1.875f, spec)
                var share: EGLContext = EGL14.EGL_NO_CONTEXT
                val contextReady = CountDownLatch(1)
                ParticleGl.post {
                    if (ParticleGl.ensureContextReady()) share = EGL14.eglGetCurrentContext()
                    contextReady.countDown()
                }
                check(contextReady.await(10, TimeUnit.SECONDS) && share != EGL14.EGL_NO_CONTEXT)
                ParticleMicroflakeGpuWarmup.run(assets, input.materials, share)
                val finished = CountDownLatch(1)
                var compared: Result<String>? = null
                ParticleGl.post {
                    compared = runCatching {
                        val warm = checkNotNull(ParticleGl.shared)
                        check(warm.compiled.isNotEmpty())
                        val surface = EGL14.eglCreatePbufferSurface(ParticleGl.display, ParticleGl.config,
                            intArrayOf(EGL14.EGL_WIDTH, 384, EGL14.EGL_HEIGHT, 640, EGL14.EGL_NONE), 0)
                        check(surface != EGL14.EGL_NO_SURFACE && ParticleGl.bind(surface))
                        var maximum = 0
                        try {
                            warm.ensure(384, 640)
                            ParticleMicroflakeRenderer.Shared(assets).use { fresh ->
                                fresh.ensure(384, 640)
                                ParticleMicroflakeRenderer(assets, warm, input).use { a ->
                                    ParticleMicroflakeRenderer(assets, fresh, input).use { b ->
                                        a.prepare(); b.prepare()
                                        fun image(renderer: ParticleMicroflakeRenderer, time: Float): ByteArray {
                                            renderer.draw(time, batchPressure = true)
                                            val bytes = ByteBuffer.allocateDirect(384 * 640 * 4)
                                            GLES30.glReadPixels(0, 0, 384, 640, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
                                            return ByteArray(bytes.capacity()).also { bytes.get(it) }
                                        }
                                        for (time in listOf(0f, .2f, .6f)) {
                                            val left = image(a, time); val right = image(b, time)
                                            for (i in left.indices) maximum = maxOf(maximum,
                                                abs((left[i].toInt() and 255) - (right[i].toInt() and 255)))
                                        }
                                    }
                                }
                            }
                            check(maximum <= 1) { "共享预热程序改变像素：$maximum" }
                            "通过 3 个流体相位的共享程序／现场程序对照，最大通道差 $maximum"
                        } finally { ParticleGl.bindIdle(); EGL14.eglDestroySurface(ParticleGl.display, surface) }
                    }
                    finished.countDown()
                }
                check(finished.await(40, TimeUnit.SECONDS))
                checkNotNull(compared).getOrThrow()
            } finally { bitmap.recycle() }
        }
        finish(if (result.isSuccess) 0 else 1, Bundle().apply {
            putString("result", result.getOrElse { it.stackTraceToString() })
        })
    }
}
