package com.ywwynm.everythingdone.views.particledismiss

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import com.ywwynm.everythingdone.BuildConfig
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * 桌面 r33 的固定步长 GPU 微片消散。每帧只推进到当前时刻，不在启动时预解算整段。
 * 本类只在调用线程已经绑定的 EGL 上下文中工作，不拥有 Activity 或 EGLDisplay。
 */
internal class ParticleMicroflakeRenderer(
    private val assets: AssetManager,
    private val width: Int,
    private val height: Int,
    private val input: Input
) : Closeable {
    data class Input(
        val frameWidth: Float,
        val frameHeight: Float,
        val cardWidth: Float,
        val cardHeight: Float,
        val originX: Float,
        val originY: Float,
        val direction: Float,
        val guideDirection: Float,
        val foreground: Bitmap,
        val materials: ParticleMicroflakeModel.Materials,
        val guide: ByteArray,
        val sourcePixels: IntArray? = null
    )

    private val programs = ArrayList<Int>()
    private val textures = ArrayList<Int>()
    private val buffers = IntArray(3)
    private val framebuffer = IntArray(1)
    private val vao = IntArray(1)
    private var compute = 0
    private var material = 0
    private var resolve = 0
    private var foregroundTexture = 0
    private var guideTexture = 0
    private var accumulationTexture = 0
    private var step = 0
    private val uniforms = HashMap<String, Int>()

    fun prepare() {
        val version = IntArray(2)
        GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, version, 0)
        GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, version, 1)
        check(version[0] > 3 || version[0] == 3 && version[1] >= 1) { "微片消散需要 GLES 3.1" }
        compute = program("step.comp")
        material = program("material.vert", "material.frag")
        resolve = program("resolve.vert", "resolve.frag")
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glGenBuffers(3, buffers, 0)
        uploadBuffer(0, input.materials.values)
        val initial = FloatArray(input.materials.count * 8)
        for (i in 0 until input.materials.count) {
            initial[i * 8] = input.materials.values[i * 12]
            initial[i * 8 + 1] = input.materials.values[i * 12 + 1]
        }
        uploadBuffer(1, initial)
        uploadBuffer(2, input.materials.pigment)
        foregroundTexture = newTexture(GLES30.GL_TEXTURE_2D)
        val bitmap = input.foreground
        val pixels = input.sourcePixels ?: IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        // getPixels 返回非预乘颜色；不用 GLUtils 上传 Bitmap 的预乘底层存储。
        val rgba = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in pixels.indices) {
            val c = pixels[i]
            pixels[i] = (c and 0xff00ff00.toInt()) or ((c ushr 16) and 255) or ((c and 255) shl 16)
        }
        rgba.asIntBuffer().put(pixels)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, bitmap.width, bitmap.height,
            0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba)
        guideTexture = newTexture(GLES30.GL_TEXTURE_3D)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RG16F, 36, 36, 32, 0,
            GLES30.GL_RG, GLES30.GL_HALF_FLOAT, direct(input.guide))
        accumulationTexture = newTexture(GLES30.GL_TEXTURE_2D)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        GLES30.glGenFramebuffers(1, framebuffer, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, accumulationTexture, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "设备不支持微片线性颜色缓冲"
        }
        val angle = Math.toRadians(input.direction.toDouble())
        val windX = cos(angle).toFloat(); val windY = -sin(angle).toFloat()
        val rotation = Math.toRadians((input.direction - input.guideDirection).toDouble())
        val span = min(input.cardWidth, input.cardHeight)
        GLES30.glUseProgram(compute)
        oneI(compute, "count", input.materials.count)
        oneI(compute, "guide_field", 3)
        one(compute, "dt", ParticleMicroflakeModel.STEP)
        one(compute, "span", span)
        one(compute, "wind_gain", 1.05f); one(compute, "curl_gain", 1f)
        one(compute, "roll_gain", .85f); one(compute, "guide_gain", .90f)
        two(compute, "wind", windX, windY)
        two(compute, "card", input.cardWidth, input.cardHeight)
        two(compute, "guide_rotation", cos(rotation).toFloat(), sin(rotation).toFloat())
        GLES30.glUseProgram(material)
        two(material, "frame", input.frameWidth, input.frameHeight)
        two(material, "card", input.cardWidth, input.cardHeight)
        two(material, "offset", input.originX, input.originY)
        two(material, "cell", input.materials.cellX, input.materials.cellY)
        two(material, "wind", windX, windY)
        one(material, "span", span); one(material, "roll_gain", .85f)
        one(material, "light_gain", .85f); one(material, "body_weight", input.materials.bodyWeight)
        oneI(material, "nx", input.materials.columns); oneI(material, "foreground", 0)
        oneI(material, "diagnostic", 0)
        GLES30.glUseProgram(resolve)
        oneI(resolve, "screen", 2)
        checkGl("准备")
    }

    /** 调试验证也调用同一函数；时间按 240 Hz 累积，刷新率只决定展示采样。 */
    fun draw(time: Float) {
        val t = time.coerceIn(0f, 1f)
        val target = floor(t.toDouble() * 240 + 1e-6).toInt()
        check(target >= step) { "运行时不能倒放材料状态" }
        for (i in buffers.indices) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, buffers[i])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, guideTexture)
        GLES30.glUseProgram(compute)
        while (step < target) {
            step++
            one(compute, "time", step / 240f)
            GLES31.glDispatchCompute((input.materials.count + 255) / 256, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        }
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(material)
        one(material, "time", t)
        one(material, "extrapolate", max(0f, t - step / 240f))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, foregroundTexture)
        for (pass in 0..1) {
            oneI(material, "material_pass", pass)
            val filter = if (pass == 0) GLES30.GL_NEAREST else GLES30.GL_LINEAR
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, input.materials.count)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(resolve)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, accumulationTexture)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    fun play(durationScale: Float, refreshRate: Float, cancelled: () -> Boolean, onFirstFrame: () -> Unit): Boolean {
        if (cancelled()) return false
        draw(0f)
        if (!swap()) return false
        onFirstFrame()
        val start = System.nanoTime()
        val intervals = ArrayList<Double>()
        var previous = start
        var frames = 1
        // 与桌面导出的 60 Hz 展示采样一致，240 Hz 物理仍照常推进。
        val frameNanos = (1e9 / refreshRate.coerceIn(30f, 60f)).toLong()
        var nextFrame = start + frameNanos
        while (!cancelled()) {
            // TextureView 的 eglSwapBuffers 未必受垂直同步限流，不能无限重复绘制同一时刻。
            val wait = nextFrame - System.nanoTime()
            if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait)
            if (cancelled()) return false
            val t = ((System.nanoTime() - start) / 1e9 / durationScale.coerceAtLeast(.1f)).toFloat().coerceAtMost(1f)
            draw(t)
            if (!swap()) return false
            val now = System.nanoTime()
            nextFrame = start + ((now - start) / frameNanos + 1) * frameNanos
            intervals.add((now - previous) / 1e6); previous = now; frames++
            if (t >= 1f) {
                if (BuildConfig.DEBUG) {
                    intervals.sort()
                    Log.i(TAG, "完成 count=${input.materials.count} frames=$frames elapsedMs=${(now-start)/1e6} " +
                        "p90Ms=${intervals[(intervals.size*.9).toInt().coerceAtMost(intervals.lastIndex)]} " +
                        "maxMs=${intervals.last()} direction=${input.direction}")
                }
                checkGl("结束")
                return true
            }
        }
        return false
    }

    fun readState(): ByteBuffer {
        GLES30.glFinish()
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[1])
        val size = input.materials.count * 8 * 4
        val source = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, size, GLES30.GL_MAP_READ_BIT) as ByteBuffer
        val result = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        result.put(source); result.flip()
        GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        return result
    }

    private fun swap() = EGL14.eglSwapBuffers(EGL14.eglGetCurrentDisplay(), EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW))

    private fun program(vararg paths: String): Int {
        val p = GLES30.glCreateProgram()
        programs.add(p)
        val shaders = ArrayList<Int>()
        try {
            for (path in paths) {
                val type = when (path.substringAfterLast('.')) {
                    "comp" -> GLES31.GL_COMPUTE_SHADER
                    "vert" -> GLES30.GL_VERTEX_SHADER
                    else -> GLES30.GL_FRAGMENT_SHADER
                }
                val shader = GLES30.glCreateShader(type)
                shaders.add(shader)
                GLES30.glShaderSource(shader, assets.open("particle-dismiss/$path").bufferedReader().use { it.readText() })
                GLES30.glCompileShader(shader)
                val status = IntArray(1)
                GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { "$path: ${GLES30.glGetShaderInfoLog(shader)}" }
                GLES30.glAttachShader(p, shader)
            }
            GLES30.glLinkProgram(p)
            val status = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "${paths.joinToString()}: ${GLES30.glGetProgramInfoLog(p)}" }
            return p
        } finally {
            for (shader in shaders) GLES30.glDeleteShader(shader)
        }
    }

    private fun newTexture(target: Int): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0); textures.add(id[0])
        GLES30.glBindTexture(target, id[0])
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        if (target == GLES30.GL_TEXTURE_3D) GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    private fun uploadBuffer(index: Int, floats: FloatArray) {
        val bytes = ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.nativeOrder())
        bytes.asFloatBuffer().put(floats)
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[index])
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, floats.size * 4, bytes, GLES30.GL_DYNAMIC_DRAW)
    }

    private fun location(program: Int, name: String) = uniforms.getOrPut("$program:$name") { GLES30.glGetUniformLocation(program, name) }
    private fun one(program: Int, name: String, value: Float) = GLES30.glUniform1f(location(program, name), value)
    private fun oneI(program: Int, name: String, value: Int) = GLES30.glUniform1i(location(program, name), value)
    private fun two(program: Int, name: String, x: Float, y: Float) = GLES30.glUniform2f(location(program, name), x, y)

    override fun close() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, framebuffer, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
        GLES30.glDeleteBuffers(buffers.size, buffers, 0)
        if (textures.isNotEmpty()) GLES30.glDeleteTextures(textures.size, textures.toIntArray(), 0)
        for (p in programs) GLES30.glDeleteProgram(p)
    }

    companion object {
        const val TAG = "ParticleMicroflake"
        private fun direct(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); flip() }
        private fun checkGl(stage: String) { check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "微片 $stage GL 错误" } }

        fun fromSpec(assets: AssetManager, width: Int, height: Int, density: Float, spec: ParticleDismissSpec): Input {
            // 720 逻辑像素对应 384 dp 手机宽度，微片约 1.25 dp；横屏和平板也保持相同界面粒径。
            val scale = (density / 1.875f).coerceAtLeast(.5f)
            val bitmap = spec.snapshot
            val cardWidth = bitmap.width / scale; val cardHeight = bitmap.height / scale
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val dx = spec.virtualTouchXPx - spec.originXPx - bitmap.width / 2f
            val dy = spec.virtualTouchYPx - spec.originYPx - bitmap.height / 2f
            val direction = Math.toDegrees(atan2(-dy, dx).toDouble()).toFloat()
            val materials = ParticleMicroflakeModel.build(cardWidth, cardHeight, pixels, bitmap.width,
                bitmap.height, direction, spec.hashSeed.toLong())
            return Input(width / scale, height / scale, cardWidth, cardHeight, spec.originXPx / scale,
                spec.originYPx / scale, direction, 90f, bitmap, materials,
                assets.open("particle-dismiss/common-flow.f16").use { it.readBytes() }, pixels)
        }
    }
}
