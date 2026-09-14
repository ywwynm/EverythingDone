package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/** 只读观察真实动画输入后离屏比较 GPU 输出，不触发页面或修改记事。诊断轮不计性能。 */
internal object ParticleTrajectoryCheck {
    fun run(context: Context, spec: ParticleDismissSpec, width: Int, height: Int, output: File,
            inspectFrames: Set<Int> = emptySet()) {
        Thread({
            val result = JSONObject().put("width", width).put("height", height).put("seed", spec.hashSeed)
            try {
                verifyUploads()
                result.put("uploadBytesEqual", true)
                val input = ParticleMicroflakeRenderer.fromSpec(context.assets, width, height,
                    context.resources.displayMetrics.density, spec)
                withEgl(width, height) {
                    val hashes = ArrayList<String>()
                    val referencePixels = mutableMapOf<Int, ByteArray>()
                    val pixels = ByteBuffer.allocateDirect(width * height * 4)
                    fun hash(): String {
                        pixels.clear()
                        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                        return java.security.MessageDigest.getInstance("SHA-256").apply { update(pixels) }
                            .digest().joinToString("") { "%02x".format(it) }
                    }
                    ParticleMicroflakeRenderer(context.assets, width, height, input).use { renderer ->
                        renderer.prepare()
                        for (frame in 0..60) {
                            renderer.draw(frame / 60f); hashes += hash()
                            if (frame in inspectFrames && referencePixels.size < 2) {
                                pixels.rewind()
                                referencePixels[frame] = ByteArray(pixels.remaining()).also { pixels.get(it) }
                            }
                        }
                    }
                    val mismatches = JSONArray()
                    val mismatchedPixels = linkedMapOf<Int, ByteArray>()
                    ParticleMicroflakeRenderer(context.assets, width, height, input).use { renderer ->
                        renderer.prepare()
                        for (frame in 0..60) {
                            renderer.draw(frame / 60f, batchPressure = true)
                            if (hash() != hashes[frame]) {
                                mismatches.put(frame)
                                if (mismatchedPixels.size < 2) {
                                    pixels.rewind()
                                    mismatchedPixels[frame] = ByteArray(pixels.remaining()).also { pixels.get(it) }
                                }
                            }
                        }
                    }
                    result.put("frames", 61).put("mismatches", mismatches).put("passed", mismatches.length() == 0)
                        .put("gpu", GLES30.glGetString(GLES30.GL_RENDERER))
                    // 不用换一个随机种子的通过结果掩盖失败：记录差异幅度与重复性。
                    val differences = JSONArray()
                    for ((frame, optimized) in mismatchedPixels) {
                        ParticleMicroflakeRenderer(context.assets, width, height, input).use { renderer ->
                            renderer.prepare()
                            for (index in 0..frame) renderer.draw(index / 60f)
                            val repeatedHash = hash()
                            pixels.rewind()
                            var changedPixels = 0; var maximum = 0; var total = 0L
                            for (i in optimized.indices step 4) {
                                var changed = false
                                repeat(4) { channel ->
                                    val delta = kotlin.math.abs((pixels.get().toInt() and 255) - (optimized[i+channel].toInt() and 255))
                                    if (delta > 0) changed = true
                                    maximum = maxOf(maximum, delta); total += delta
                                }
                                if (changed) changedPixels++
                            }
                            val detail = JSONObject().put("frame", frame).put("referenceRepeatEqual", repeatedHash == hashes[frame])
                                .put("changedPixels", changedPixels).put("maxChannelDifference", maximum)
                                .put("meanChannelDifference", total.toDouble()/optimized.size)
                            referencePixels[frame]?.let { original ->
                                var changed = 0; var maxDelta = 0
                                for (i in original.indices step 4) {
                                    var pixelChanged = false
                                    repeat(4) { channel ->
                                        val delta = kotlin.math.abs((original[i+channel].toInt() and 255) - (optimized[i+channel].toInt() and 255))
                                        if (delta > 0) pixelChanged = true
                                        maxDelta = maxOf(maxDelta, delta)
                                    }
                                    if (pixelChanged) changed++
                                }
                                detail.put("initialReferenceChangedPixels", changed).put("initialReferenceMaxChannelDifference", maxDelta)
                            }
                            differences.put(detail)
                        }
                    }
                    result.put("pixelDifferences", differences)
                }
            } catch (error: Throwable) { result.put("passed", false).put("error", error.stackTraceToString()) }
            finally { spec.snapshot.recycle() }
            File(output, "gpu-parity.json").writeText(result.toString())
        }, "ParticleTrajectoryCheck").start()
    }

    private fun verifyUploads() {
        check(ParticleMaterialNative.available)
        val random = java.util.Random(9018)
        val pixels = IntArray(1027) { random.nextInt() }
        for (count in listOf(1, 8192)) {
            val materials = FloatArray(count * 12) { random.nextFloat() * 2000 - 1000 }
            val oldColors = ByteBuffer.allocateDirect(pixels.size * 4)
            val oldState = ByteBuffer.allocateDirect(count * 32)
            val colors = ByteBuffer.allocateDirect(pixels.size * 4)
            val state = ByteBuffer.allocateDirect(count * 32)
            ParticleMaterialNative.packUploads(pixels, materials, count, oldColors, oldState)
            ParticleMaterialNative.packColors(pixels, colors)
            ParticleMaterialNative.packState(materials, count, state)
            check(oldColors == colors && oldState == state) { "分段上传改变了原始字节" }
        }
    }

    private fun withEgl(width: Int, height: Int, action: () -> Unit) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0))
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
        try { action() } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context); EGL14.eglReleaseThread()
        }
    }
}
