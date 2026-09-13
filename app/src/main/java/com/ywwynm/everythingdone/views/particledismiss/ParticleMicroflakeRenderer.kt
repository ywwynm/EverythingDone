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
 * 与桌面共享规则的固定步长 GPU 微片消散。消散按需推进；出现缓存同一积分结果后倒序播放。
 * 本类只在调用线程已经绑定的 EGL 上下文中工作，不拥有 Activity 或 EGLDisplay。
 */
internal class ParticleMicroflakeRenderer(
    private val assets: AssetManager,
    private val width: Int,
    private val height: Int,
    initialInput: Input? = null
) : Closeable {
    private lateinit var input: Input
    init { if (initialInput != null) input = initialInput }
    data class Input(
        val frameWidth: Float,
        val frameHeight: Float,
        val cardWidth: Float,
        val cardHeight: Float,
        val originX: Float,
        val originY: Float,
        val direction: Float,
        val foreground: Bitmap,
        val materials: ParticleMicroflakeModel.Materials,
        val guide: ByteArray,
        val rules: ParticleMicroflakeRules,
        val sourcePixels: IntArray? = null,
        val touchGap: Float = rules.number("touch_gap_default"),
        val confidence: ByteArray? = null,
        val touchStrength: Float = .5f
    )

    private val programs = ArrayList<Int>()
    private val textures = ArrayList<Int>()
    private val buffers = IntArray(4)
    private val framebuffer = IntArray(1)
    private val vao = IntArray(1)
    private var compute = 0
    private var reverseCompute = 0
    private var material = 0
    private var resolve = 0
    private var foregroundTexture = 0
    private var guideTexture = 0
    private var confidenceTexture = 0
    private var accumulationTexture = 0
    private var step = 0
    private lateinit var peelPressure: PeelPressure
    private val uniforms = HashMap<String, Int>()
    private val compiledPrograms = HashMap<String, Int>()
    private var pipelinePrepared = false
    private var reverseHistory: ParticleReverseHistory? = null
    private var optimizedIntegrationPrepared = false
    /** 与后台建材重叠：着色器和屏幕缓冲不依赖本次触点或粒子材料。 */
    fun preparePipeline(sharedFields: SharedResources? = null) {
        if (pipelinePrepared) return
        val version = IntArray(2)
        GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, version, 0)
        GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, version, 1)
        check(version[0] > 3 || version[0] == 3 && version[1] >= 1) { "微片消散需要 GLES 3.1" }
        compute = program("step.comp")
        material = program("material.vert", "material.frag")
        resolve = program("resolve.vert", "resolve.frag")
        program("peel-splat.comp"); program("peel-blur.comp"); program("peel-project.comp")
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glGenBuffers(buffers.size, buffers, 0)
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
        if (sharedFields != null) uploadFields(sharedFields.guide, sharedFields.rules, sharedFields.confidence)
        pipelinePrepared = true
    }

    /** 只编译出现准备所需程序，不生成轨迹；可与后台建材重叠或在空闲时预热。 */
    fun prepareReversePipeline() {
        reverseCompute = program("step.comp", reverseIntegration = true)
        program("particle-playback/pressure-batched.comp")
        program("particle-playback/pressure-tiles.comp")
        program("particle-playback/history.comp")
    }

    fun prepare(materialInput: Input? = null) {
        if (materialInput != null) input = materialInput
        preparePipeline()
        val bitmap = input.foreground
        val pixels = input.sourcePixels ?: IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        val initial = ByteBuffer.allocateDirect(input.materials.count * 8 * 4).order(ByteOrder.nativeOrder())
        val rgba = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        if (ParticleMaterialNative.enabled) {
            ParticleMaterialNative.packUploads(pixels, input.materials.values, input.materials.count, rgba, initial)
        } else {
            val state=initial.asFloatBuffer()
            for(i in 0 until input.materials.count) {
                state.put(i*8,input.materials.values[i*12]);state.put(i*8+1,input.materials.values[i*12+1])
            }
            val colors=rgba.asIntBuffer()
            for(c in pixels) colors.put((c and 0xff00ff00.toInt()) or ((c ushr 16) and 255) or ((c and 255) shl 16))
        }
        uploadBuffer(0, input.materials.values)
        uploadBytes(1,initial)
        uploadBuffer(2, input.materials.pigment)
        uploadBuffer(3, input.materials.peelCompression)
        foregroundTexture = newTexture(GLES30.GL_TEXTURE_2D)
        // getPixels 返回非预乘颜色；不用 GLUtils 上传 Bitmap 的预乘底层存储。
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, bitmap.width, bitmap.height,
            0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba)
        if (guideTexture == 0) uploadFields(input.guide, input.rules, input.confidence ?: sharedResources(assets).confidence)
        val angle = Math.toRadians(input.direction.toDouble())
        val windX = cos(angle).toFloat(); val windY = -sin(angle).toFloat()
        val span = min(input.cardWidth, input.cardHeight)
        configureIntegration(compute)
        GLES30.glUseProgram(material)
        two(material, "frame", input.frameWidth, input.frameHeight)
        two(material, "card", input.cardWidth, input.cardHeight)
        two(material, "offset", input.originX, input.originY)
        two(material, "cell", input.materials.cellX, input.materials.cellY)
        two(material, "wind", windX, windY)
        one(material, "span", span); one(material, "roll_gain", input.rules.number("roll_gain"))
        one(material, "light_gain", input.rules.number("light_gain")); one(material, "body_weight", input.materials.bodyWeight)
        one(material, "release_spread", input.rules.number("release_spread") +
            input.rules.number("white_spread") * input.materials.bodyWeight)
        one(material, "panel_weight", input.materials.statistics.getValue("panelWeight").toFloat())
        oneI(material, "nx", input.materials.columns); oneI(material, "foreground", 0)
        oneI(material, "grid_count", input.materials.columns * input.materials.rows)
        oneI(material, "diagnostic", 0)
        GLES30.glUseProgram(resolve)
        oneI(resolve, "screen", 2)
        peelPressure = PeelPressure(span, windX, windY)
        checkGl("准备")
    }

    private fun configureIntegration(compute: Int) {
        val angle = Math.toRadians(input.direction.toDouble())
        val windX = cos(angle).toFloat(); val windY = -sin(angle).toFloat()
        val geometry = ParticleFlowGeometry.from(input.cardWidth, input.cardHeight)
        val rotation = geometry.rotation(input.direction)
        val span = min(input.cardWidth, input.cardHeight)
        GLES30.glUseProgram(compute)
        oneI(compute, "count", input.materials.count)
        oneI(compute, "guide_field", 3)
        oneI(compute, "confidence_field", 4)
        one(compute, "touch_gap", input.touchGap)
        one(compute, "touch_strength", input.touchStrength)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(compute, "field_geometry"), geometry.width.toFloat(), geometry.height.toFloat(), geometry.blend.toFloat(), if (geometry.vertical) 1f else 0f)
        one(compute, "dt", ParticleMicroflakeModel.STEP)
        one(compute, "span", span)
        one(compute, "wind_gain", input.rules.number("wind_gain")); one(compute, "curl_gain", input.rules.number("curl_gain"))
        one(compute, "roll_gain", input.rules.number("roll_gain")); one(compute, "guide_gain", input.rules.number("guide_gain"))
        two(compute, "wind", windX, windY)
        two(compute, "card", input.cardWidth, input.cardHeight)
        two(compute, "guide_rotation", cos(rotation).toFloat(), sin(rotation).toFloat())
        val variation = input.materials.variation.values
        for ((name, offset) in listOf("variation_affine" to 0, "variation_bend" to 4, "variation_wave" to 8)) {
            GLES30.glUniform4f(GLES30.glGetUniformLocation(compute, name), variation[offset], variation[offset + 1], variation[offset + 2], variation[offset + 3])
        }
        two(compute, "variation_clock", variation[12], variation[13])
    }

    /** 调试验证也调用同一函数；时间按 240 Hz 累积，刷新率只决定展示采样。 */
    fun draw(time: Float, batchPressure: Boolean = false) {
        val t = time.coerceIn(0f, 1f)
        if (batchPressure) prepareOptimizedIntegration()
        advance(t, batchPressure)
        render(t)
    }

    private fun prepareOptimizedIntegration() {
        if (optimizedIntegrationPrepared) return
        prepareReversePipeline()
        configureIntegration(reverseCompute)
        peelPressure.configureIntegration(reverseCompute)
        optimizedIntegrationPrepared = true
    }

    private fun advance(t: Float, batchPressure: Boolean = false) {
        val compute = if (batchPressure) reverseCompute else this.compute
        val target = floor(t.toDouble() * 240 + 1e-6).toInt()
        check(target >= step) { "运行时不能倒放材料状态" }
        for (i in buffers.indices) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, buffers[i])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, guideTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, confidenceTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, peelPressure.texture)
        GLES30.glUseProgram(compute)
        while (step < target) {
            step++
            one(compute, "time", step / 240f)
            GLES31.glDispatchCompute((input.materials.count + 255) / 256, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            if (step % 4 == 0) {
                peelPressure.update(step / 240f, batchPressure)
                for (i in buffers.indices) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, buffers[i])
                GLES30.glUseProgram(compute)
            }
        }
    }

    private fun render(t: Float) {
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

    /** 一次正向解算，只保存颗粒可见寿命附近的状态，GL 线程可随时取消。 */
    fun prepareReverse(samples: Int = ParticleReversePlan.samplesFor(ParticleReversePlan.APPEARANCE_SECONDS, 60f),
                       cancelled: () -> Boolean = { false }): Boolean {
        check(step == 0 && reverseHistory == null)
        val started = System.nanoTime()
        prepareOptimizedIntegration()
        val history = ParticleReverseHistory(program("particle-playback/history.comp"), ParticleReversePlan(input.materials.values, samples))
        reverseHistory = history
        ParticleGpuWork.PreparationQueue().use { queue ->
            for (frame in 0..history.plan.samples) {
                if (cancelled()) return false
                advance(history.plan.time(frame), batchPressure = true)
                history.transfer(frame, restore = false)
                if (!queue.finishBatch(cancelled)) return false
            }
        }
        GLES30.glFinish()
        checkGl("逆向准备")
        if (BuildConfig.DEBUG) Log.i(TAG,
            "出现轨迹准备 elapsedMs=${(System.nanoTime()-started)/1e6} bytes=${history.plan.bytes} samples=${history.plan.samples} count=${input.materials.count}")
        return !cancelled()
    }

    /** 使用正向播放相同时间值、相同状态和相同材质，避免对阻尼／压力求逆引入新轨迹。 */
    fun drawReverseFrame(frame: Int) {
        val history = checkNotNull(reverseHistory)
        require(frame in 0..history.plan.samples)
        for (i in buffers.indices) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, buffers[i])
        history.transfer(frame, restore = true)
        val t = history.plan.time(frame)
        step = floor(t.toDouble() * 240 + 1e-6).toInt()
        render(t)
    }

    fun playReverse(durationSeconds: Float, durationScale: Float, refreshRate: Float,
                    cancelled: () -> Boolean, onFirstFrame: () -> Unit,
                    beforePlayback: () -> Boolean = { true },
                    onSubmitted: (Long, Float) -> Unit = { _, _ -> }): Boolean {
        val duration = durationSeconds * durationScale.coerceAtLeast(.1f)
        if (!prepareReverse(ParticleReversePlan.samplesFor(duration, refreshRate), cancelled)) return false
        if (!beforePlayback()) return false
        val plan = checkNotNull(reverseHistory).plan
        return playFrames(duration, cancelled, onFirstFrame, onSubmitted, true) { progress ->
            drawReverseFrame(plan.frame(progress))
        }
    }

    fun play(durationScale: Float, refreshRate: Float, cancelled: () -> Boolean,
             onFirstFrame: () -> Unit, onSubmitted: (Long, Float) -> Unit = { _, _ -> }): Boolean =
        playFrames(durationScale.coerceAtLeast(.1f), cancelled, onFirstFrame, onSubmitted, false) {
            draw(it, batchPressure = true)
        }

    internal val drawTimings = ArrayList<LongArray>()

    private fun playFrames(duration: Float, cancelled: () -> Boolean,
                           onFirstFrame: () -> Unit, onSubmitted: (Long, Float) -> Unit,
                           reverse: Boolean, drawFrame: (Float) -> Unit): Boolean {
        var frames = 0
        var started = 0L
        var previous = 0L
        val intervals = ArrayList<Double>()
        val completed = ParticlePlaybackClock.play(duration, cancelled) { progress, timestamp ->
            val drawStart = System.nanoTime()
            drawFrame(progress)
            val drawEnd = System.nanoTime()
            // CPU 提交可能快于 GPU 完成，尤其是逆向缓存与双弹窗重叠时。
            // 在专用 GL 线程等本帧就绪再交给 HWUI，减少缓冲排队后被替代的跳帧。
            GLES30.glFinish()
            val ready = System.nanoTime()
            android.opengl.EGLExt.eglPresentationTimeANDROID(EGL14.eglGetCurrentDisplay(),
                EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW), timestamp)
            if (!swap()) false else {
                if (BuildConfig.DEBUG) drawTimings += longArrayOf(timestamp, drawStart, drawEnd, ready, System.nanoTime())
                onSubmitted(timestamp, progress)
                if (frames == 0) { started = timestamp; onFirstFrame() }
                else intervals += (timestamp - previous) / 1e6
                previous = timestamp
                frames++
                true
            }
        }
        if (completed) {
            if (BuildConfig.DEBUG) {
                intervals.sort()
                val label = if (reverse) "出现完成 particles=" else "完成 count="
                Log.i(TAG, "$label${input.materials.count} frames=$frames elapsedMs=${(previous-started)/1e6} " +
                    "p90Ms=${intervals[(intervals.size*.9).toInt().coerceAtMost(intervals.lastIndex)]} " +
                    "maxMs=${intervals.last()} direction=${input.direction} seed=${input.materials.variation.seed}")
            }
            checkGl("结束")
        }
        return completed
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

    /** 从当前分布抵消剥离汇聚；不删除材料，也不替换每片已有的流动速度。 */
    private inner class PeelPressure(private val span: Float, windX: Float, windY: Float) : Closeable {
        private val gridCell = span.toDouble() / 96.0
        private val columns = ceil(input.cardWidth / gridCell).toInt() + 192
        private val rows = ceil(input.cardHeight / gridCell).toInt() + 192
        private val cells = columns * rows
        private val groupsX = (columns + 15) / 16
        private val groupsY = (rows + 15) / 16
        private val pressureBuffers = IntArray(5)
        private val splat = program("peel-splat.comp")
        private val blur = program("peel-blur.comp")
        private val project = program("peel-project.comp")
        private var current = 0
        private val tileBuffer = IntArray(1)
        val texture = newTexture(GLES30.GL_TEXTURE_2D)

        init {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA32F, columns, rows)
            GLES30.glGenBuffers(pressureBuffers.size, pressureBuffers, 0)
            for (i in pressureBuffers.indices) {
                val size = cells * if (i < 3) 16 else 4
                GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, pressureBuffers[i])
                // 压力从零开始；其余网格在使用前由计算着色器全部写入。
                GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, size,
                    if (i >= 3) ByteBuffer.allocateDirect(size) else null, GLES30.GL_DYNAMIC_DRAW)
            }
            for (p in intArrayOf(splat, blur, project)) {
                GLES30.glUseProgram(p)
                GLES30.glUniform2i(location(p, "grid_shape"), columns, rows)
            }
            GLES30.glUseProgram(splat)
            oneI(splat, "count", input.materials.count)
            oneI(splat, "grid_count", input.materials.columns * input.materials.rows)
            oneI(splat, "foreground", 0)
            two(splat, "card", input.cardWidth, input.cardHeight)
            two(splat, "wind", windX, windY)
            one(splat, "span", span)
            one(splat, "touch_strength", input.touchStrength)
            GLES30.glUniform4f(location(splat, "grid_bounds"), -span, -span,
                (columns * gridCell).toFloat(), (rows * gridCell).toFloat())
            GLES30.glUseProgram(blur)
            one(blur, "occupancy_scale", (input.materials.cellX * input.materials.cellY / (gridCell * gridCell)).toFloat())
            configureIntegration(compute)
            // 首帧只清空修正场；不提前计算整段动画或执行空压力迭代。
            resolveField()
        }

        fun configureIntegration(compute: Int) {
            GLES30.glUseProgram(compute)
            oneI(compute, "peel_field", 5)
            GLES30.glUniform4f(location(compute, "peel_bounds"), -span, -span,
                (columns * gridCell).toFloat(), (rows * gridCell).toFloat())
        }

        private fun bind(index: Int, binding: Int) =
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, binding, pressureBuffers[index])

        private fun barrier() = GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT or
            GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)

        fun update(time: Float, batched: Boolean) {
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffers[0])
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, buffers[1])
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, buffers[3])
            bind(0, 4)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, foregroundTexture)
            GLES30.glUseProgram(splat)
            one(splat, "time", time)
            oneI(splat, "pass", 0)
            GLES31.glDispatchCompute((cells + 255) / 256, 1, 1); barrier()
            oneI(splat, "pass", 1)
            GLES31.glDispatchCompute((input.materials.count + 255) / 256, 1, 1); barrier()
            bind(1, 5); bind(2, 6)
            GLES30.glUseProgram(blur)
            for (pass in 0..1) {
                oneI(blur, "pass", pass)
                GLES31.glDispatchCompute(groupsX, groupsY, 1); barrier()
            }
            bind(2, 0)
            val solver = if (batched) program("particle-playback/pressure-batched.comp") else project
            if (batched) prepareTiles()
            GLES30.glUseProgram(solver)
            GLES30.glUniform2i(location(solver, "grid_shape"), columns, rows)
            val batch = if (batched) 8 else 1
            for (iteration in 0 until 100 step batch) {
                bind(3 + current, 1); bind(4 - current, 2)
                if (batched) {
                    oneI(solver, "first_iteration", iteration)
                    oneI(solver, "iterations", min(batch, 100 - iteration))
                } else oneI(solver, "iteration", iteration)
                if (batched) GLES31.glDispatchComputeIndirect(0L)
                else GLES31.glDispatchCompute(groupsX, groupsY, 1)
                barrier()
                current = 1 - current
                if (batched && iteration == 0) {
                    // 首批读取了上一时刻压力；之后才清除另一个缓冲，保留原首轮邻域贡献。
                    clearTileDestination()
                    GLES30.glUseProgram(solver)
                }
            }
            resolveField()
        }

        private fun prepareTiles() {
            if (tileBuffer[0] == 0) {
                GLES30.glGenBuffers(1, tileBuffer, 0)
                GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, tileBuffer[0])
                GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 16 + groupsX * groupsY * 8, null, GLES30.GL_DYNAMIC_DRAW)
            }
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 7, tileBuffer[0])
            GLES30.glBindBuffer(GLES31.GL_DISPATCH_INDIRECT_BUFFER, tileBuffer[0])
            val tiles = program("particle-playback/pressure-tiles.comp")
            GLES30.glUseProgram(tiles)
            GLES30.glUniform2i(location(tiles, "grid_shape"), columns, rows)
            oneI(tiles, "pass", 0)
            GLES31.glDispatchCompute(1, 1, 1); barrier()
            bind(4 - current, 2)
            oneI(tiles, "pass", 1)
            GLES31.glDispatchCompute(groupsX, groupsY, 1)
            barrier()
            GLES31.glMemoryBarrier(GLES31.GL_COMMAND_BARRIER_BIT)
        }

        private fun clearTileDestination() {
            bind(4 - current, 2)
            val tiles = program("particle-playback/pressure-tiles.comp")
            GLES30.glUseProgram(tiles)
            oneI(tiles, "pass", 2)
            GLES31.glDispatchCompute(groupsX, groupsY, 1); barrier()
        }

        private fun resolveField() {
            bind(2, 0); bind(3 + current, 1); bind(4 - current, 2)
            GLES31.glBindImageTexture(0, texture, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA32F)
            GLES30.glUseProgram(project)
            oneI(project, "iteration", 100)
            GLES31.glDispatchCompute(groupsX, groupsY, 1); barrier()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        }

        override fun close() {
            GLES30.glDeleteBuffers(pressureBuffers.size, pressureBuffers, 0)
            GLES30.glDeleteBuffers(1, tileBuffer, 0)
        }
    }

    private fun swap() = EGL14.eglSwapBuffers(EGL14.eglGetCurrentDisplay(), EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW))

    private fun program(vararg paths: String, reverseIntegration: Boolean = false): Int {
        val key = paths.joinToString(";") + if (reverseIntegration) ":reverse" else ""
        compiledPrograms[key]?.let { return it }
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
                val source = assets.open(if ('/' in path) path else "particle-dismiss/$path").bufferedReader().use { it.readText() }
                GLES30.glShaderSource(shader, if (reverseIntegration) ParticleReverseShader.integration(source) else source)
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
            compiledPrograms[key] = p
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

    private fun uploadFields(guide: ByteArray, rules: ParticleMicroflakeRules, confidence: ByteArray) {
        val w=rules.number("flow_width").toInt(); val h=rules.number("flow_height").toInt()
        val time=rules.number("flow_time").toInt()
        guideTexture = newTexture(GLES30.GL_TEXTURE_3D)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D,0,GLES30.GL_RG16F,w,h,time,0,GLES30.GL_RG,GLES30.GL_HALF_FLOAT,direct(guide))
        confidenceTexture = newTexture(GLES30.GL_TEXTURE_3D)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D,0,GLES30.GL_R8,w,h,time,0,GLES30.GL_RED,GLES30.GL_UNSIGNED_BYTE,direct(confidence))
    }

    private fun uploadBuffer(index: Int, floats: FloatArray) {
        val bytes = ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.nativeOrder())
        bytes.asFloatBuffer().put(floats)
        uploadBytes(index,bytes)
    }

    private fun uploadBytes(index: Int, bytes: ByteBuffer) {
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[index])
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER,bytes.remaining(),bytes,GLES30.GL_DYNAMIC_DRAW)
    }

    private fun location(program: Int, name: String) = uniforms.getOrPut("$program:$name") { GLES30.glGetUniformLocation(program, name) }
    private fun one(program: Int, name: String, value: Float) = GLES30.glUniform1f(location(program, name), value)
    private fun oneI(program: Int, name: String, value: Int) = GLES30.glUniform1i(location(program, name), value)
    private fun two(program: Int, name: String, x: Float, y: Float) = GLES30.glUniform2f(location(program, name), x, y)

    override fun close() {
        reverseHistory?.close()
        if (::peelPressure.isInitialized) peelPressure.close()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, framebuffer, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
        GLES30.glDeleteBuffers(buffers.size, buffers, 0)
        if (textures.isNotEmpty()) GLES30.glDeleteTextures(textures.size, textures.toIntArray(), 0)
        for (p in programs) GLES30.glDeleteProgram(p)
    }

    companion object {
        const val TAG = "ParticleMicroflake"
        data class SharedResources(val rules: ParticleMicroflakeRules, val guide: ByteArray, val confidence: ByteArray)
        @Volatile private var shared: SharedResources? = null

        /** 进程内打包资源不变，首次弹窗预热和每次关闭共用，不缓存任何快照。 */
        fun sharedResources(assets: AssetManager): SharedResources = shared ?: synchronized(this) {
            shared ?: SharedResources(
                ParticleMicroflakeRules.read(assets.open("particle-dismiss/rules.properties"), assets.open("particle-dismiss/common-release.f32")),
                assets.open("particle-dismiss/common-flow.f16").use { it.readBytes() },
                assets.open("particle-dismiss/flow-confidence.u8").use { it.readBytes() }
            ).also { shared = it }
        }
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
            val resources = sharedResources(assets)
            val rules = resources.rules
            val materials = ParticleMicroflakeModel.build(cardWidth, cardHeight, pixels, bitmap.width,
                bitmap.height, direction, spec.hashSeed.toLong(), rules)
            return Input(width / scale, height / scale, cardWidth, cardHeight, spec.originXPx / scale,
                spec.originYPx / scale, direction, bitmap, materials,
                resources.guide, rules, pixels, spec.touchGap ?: rules.number("touch_gap_default"), resources.confidence, spec.touchStrength ?: .5f)
        }
    }
}
