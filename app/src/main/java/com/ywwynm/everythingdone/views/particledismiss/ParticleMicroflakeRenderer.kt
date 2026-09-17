package com.ywwynm.everythingdone.views.particledismiss

import android.content.res.AssetManager
import android.graphics.Bitmap
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
 *
 * 第二批（D4）把「一个上下文里所有会话都一样」的资源提到 [Shared]：着色器程序、累积纹理与
 * 帧缓冲、全屏 VAO、两张三维流场纹理、历史缓冲池。每个会话只再建自己的材料/状态/色素/压缩
 * SSBO、前景纹理与压力网格，结束只删这些。多个会话共用同一份程序对象，所以 uniform 也共用——
 * 切换会话时必须整套重设一遍（[applyUniforms]），由 [activate] 按「上一个用它的是不是我」判定。
 */
internal class ParticleMicroflakeRenderer(
    private val assets: AssetManager,
    private val shared: Shared,
    initialInput: Input? = null
) : Closeable {
    private lateinit var input: Input
    init { if (initialInput != null) input = initialInput }

    private val width get() = shared.width
    private val height get() = shared.height

    data class Input(
        val frameWidth: Float,
        val frameHeight: Float,
        val cardWidth: Float,
        val cardHeight: Float,
        val originX: Float,
        val originY: Float,
        val direction: Float,
        /**
         * 前景位图；只在还没有前景纹理时用来取像素。提前预算的路径（[ParticleCreationPrewarm]）
         * 先 [prepareForeground] 再 [prepare]，手上只有像素数组、没有 Bitmap，所以可以为空。
         */
        val foreground: Bitmap?,
        val materials: ParticleMicroflakeModel.Materials,
        val guide: ByteArray,
        val rules: ParticleMicroflakeRules,
        val sourcePixels: IntArray? = null,
        val touchGap: Float = rules.number("touch_gap_default"),
        val confidence: ByteArray? = null,
        val touchStrength: Float = .5f
    )

    /**
     * 一个 EGL 上下文内可以复用的全部资源。常驻上下文用一份进程级实例；离屏对照与预热
     * 各自建临时实例（程序对象不跨上下文有效）。
     */
    internal class Shared(private val assets: AssetManager) : Closeable {
        val programs = ArrayList<Int>()
        val compiled = HashMap<String, Int>()
        val uniforms = HashMap<String, Int>()
        val textures = ArrayList<Int>()
        val framebuffer = IntArray(1)
        val vao = IntArray(1)
        var accumulationTexture = 0
            private set
        var guideTexture = 0
            private set
        var confidenceTexture = 0
            private set
        var width = 0
            private set
        var height = 0
            private set
        var prepared = false
            private set

        /** 最近一次把自己的 uniform 写进共享程序的会话。 */
        var owner: Any? = null

        val history = HistoryPool()

        /** 按当前显示尺寸建立累积纹理与帧缓冲；尺寸变化时重建，其余一次到位。 */
        fun ensure(targetWidth: Int, targetHeight: Int) {
            require(targetWidth > 0 && targetHeight > 0)
            if (prepared && targetWidth == width && targetHeight == height) return
            val version = IntArray(2)
            GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, version, 0)
            GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, version, 1)
            check(version[0] > 3 || version[0] == 3 && version[1] >= 1) { "微片消散需要 GLES 3.1" }
            if (!prepared) {
                GLES30.glGenVertexArrays(1, vao, 0)
                GLES30.glGenFramebuffers(1, framebuffer, 0)
            } else if (accumulationTexture != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(accumulationTexture), 0)
                textures.remove(accumulationTexture)
            }
            width = targetWidth
            height = targetHeight
            GLES30.glBindVertexArray(vao[0])
            accumulationTexture = newTexture(GLES30.GL_TEXTURE_2D, textures)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, accumulationTexture, 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "设备不支持微片线性颜色缓冲"
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            prepared = true
        }

        /** 三维流场与置信度只与打包资源有关，进程内上传一次。 */
        fun uploadFields(guide: ByteArray, rules: ParticleMicroflakeRules, confidence: ByteArray) {
            if (guideTexture != 0) return
            val w = rules.number("flow_width").toInt(); val h = rules.number("flow_height").toInt()
            val time = rules.number("flow_time").toInt()
            guideTexture = newTexture(GLES30.GL_TEXTURE_3D, textures)
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RG16F, w, h, time, 0,
                GLES30.GL_RG, GLES30.GL_HALF_FLOAT, direct(guide))
            confidenceTexture = newTexture(GLES30.GL_TEXTURE_3D, textures)
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_R8, w, h, time, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, direct(confidence))
        }

        fun assets(): AssetManager = assets

        /** 内存告警时释放可再生的大块（历史缓冲）；上下文、程序与纹理仍常驻。 */
        fun trim() = history.trim()

        override fun close() {
            history.close()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            if (prepared) {
                GLES30.glDeleteFramebuffers(1, framebuffer, 0)
                GLES30.glDeleteVertexArrays(1, vao, 0)
            }
            if (textures.isNotEmpty()) GLES30.glDeleteTextures(textures.size, textures.toIntArray(), 0)
            textures.clear()
            for (p in programs) GLES30.glDeleteProgram(p)
            programs.clear(); compiled.clear(); uniforms.clear()
            prepared = false; accumulationTexture = 0; guideTexture = 0; confidenceTexture = 0
        }
    }

    /**
     * 历史缓冲池：按 D4「常驻、只增不减」。一次动画的历史可达数十 MB，重新分配实测只要
     * 0.003–0.036 ms（第一批实测），所以池的意义主要是避免反复的大块分配抖动；
     * 上限是「同时存活的会话数」，并在内存告警时整体释放。
     */
    internal class HistoryPool {
        private class Entry(val buffer: Int, var capacity: Int, var busy: Boolean)
        private val entries = ArrayList<Entry>()

        /** 返回一个容量不小于 [bytes] 的缓冲；容量不足时就地扩容（只增不减）。 */
        fun acquire(bytes: Int): Int {
            val reusable = entries.firstOrNull { !it.busy && it.capacity >= bytes }
            if (reusable != null) { reusable.busy = true; return reusable.buffer }
            val grow = entries.firstOrNull { !it.busy }
            if (grow != null) {
                GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, grow.buffer)
                GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, null, GLES30.GL_DYNAMIC_DRAW)
                grow.capacity = bytes; grow.busy = true
                return grow.buffer
            }
            val id = IntArray(1)
            GLES30.glGenBuffers(1, id, 0)
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, id[0])
            GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, null, GLES30.GL_DYNAMIC_DRAW)
            entries += Entry(id[0], bytes, true)
            return id[0]
        }

        fun release(buffer: Int) { entries.firstOrNull { it.buffer == buffer }?.busy = false }

        fun trim() {
            val idle = entries.filter { !it.busy }
            if (idle.isEmpty()) return
            GLES30.glDeleteBuffers(idle.size, idle.map { it.buffer }.toIntArray(), 0)
            entries.removeAll(idle.toSet())
        }

        fun close() {
            if (entries.isEmpty()) return
            GLES30.glDeleteBuffers(entries.size, entries.map { it.buffer }.toIntArray(), 0)
            entries.clear()
        }
    }

    // ---- 会话私有的 GL 对象 ----
    private val textures = ArrayList<Int>()
    private val buffers = IntArray(4)
    private var compute = 0
    private var reverseCompute = 0
    private var material = 0
    private var resolve = 0
    private var foregroundTexture = 0
    private var step = 0
    private lateinit var peelPressure: PeelPressure
    private var pipelinePrepared = false
    private var reverseHistory: ParticleReverseHistory? = null
    private var optimizedIntegrationPrepared = false
    private var reverseHasHistory = false
    private var reverseLargeTile = false
    private var reverseJacobiBatch = 8
    private var reversePipelinePrepared = false

    /** 仅 debug 且显式开启诊断文件时非空；release 恒为 null。 */
    private var probe: ParticlePerfProbe.Session? = null
    private var probeStarted = false
    private var probeStage = "play"
    private fun probe(): ParticlePerfProbe.Session? {
        if (!probeStarted) { probeStarted = true; probe = ParticlePerfProbe.begin() }
        return probe
    }

    /** 与后台建材重叠：着色器和屏幕缓冲不依赖本次触点或粒子材料。 */
    fun preparePipeline(sharedFields: SharedResources? = null) {
        if (pipelinePrepared) return
        compute = program("step.comp")
        material = program("material.vert", "material.frag")
        resolve = program("resolve.vert", "resolve.frag")
        program("peel-splat.comp"); program("peel-blur.comp"); program("peel-project.comp")
        GLES30.glGenBuffers(buffers.size, buffers, 0)
        if (sharedFields != null) shared.uploadFields(sharedFields.guide, sharedFields.rules, sharedFields.confidence)
        pipelinePrepared = true
    }

    /** 只编译出现准备所需程序，不生成轨迹；可与后台建材重叠或在空闲时预热。 */
    fun prepareReversePipeline() {
        if (reversePipelinePrepared) return
        reversePipelinePrepared = true
        // 取值只在编译这一次确定：之后一律按「这个程序里有没有历史写入」判断，
        // 不再读开关。否则开关中途变化会让 history_frame 停在默认 0 上，
        // 着色器就会把历史写进当时绑在 4/5 上的压力缓冲。
        reverseHasHistory = ParticlePerfTuning.historyInIntegration
        reverseLargeTile = largeTileJacobi()
        reverseCompute = program("step.comp", reverseIntegration = true,
            reverseHistory = reverseHasHistory)
        if (reverseLargeTile) {
            program("particle-playback/pressure-batched-large.comp", defines = jacobiDefines())
            program("particle-playback/pressure-tiles-large.comp")
        } else {
            program("particle-playback/pressure-batched.comp")
            program("particle-playback/pressure-tiles.comp")
        }
        program("particle-playback/history.comp")
    }

    /**
     * 2.2 的大 tile 路径只在 Adreno 上启用：它把两份压力放进片上 shared memory，
     * Mali / Xclipse 没有这块存储，前提不成立（见 followups）。同时要求 shared 装得下。
     */
    private fun largeTileJacobi(): Boolean {
        if (!ParticlePerfTuning.jacobiLargeTile) return false
        if (!adreno()) return false
        reverseJacobiBatch = ParticlePerfTuning.jacobiLargeBatch
        val band = reverseJacobiBatch
        val tile = 32 + 2 * band
        val slots = (tile * tile + 255) / 256 * 256
        return slots * 2 * 4 <= maxSharedMemory()
    }

    private fun jacobiDefines() = "#define BAND $reverseJacobiBatch\n"

    fun prepare(materialInput: Input? = null) {
        if (materialInput != null) input = materialInput
        preparePipeline()
        val initial = ByteBuffer.allocateDirect(input.materials.count * 8 * 4).order(ByteOrder.nativeOrder())
        if (ParticleMaterialNative.enabled) {
            ParticleMaterialNative.packState(input.materials.values, input.materials.count, initial)
        } else {
            val state=initial.asFloatBuffer()
            for(i in 0 until input.materials.count) {
                state.put(i*8,input.materials.values[i*12]);state.put(i*8+1,input.materials.values[i*12+1])
            }
        }
        uploadBuffer(0, input.materials.values)
        uploadBytes(1,initial)
        uploadBuffer(2, input.materials.pigment)
        uploadBuffer(3, input.materials.peelCompression)
        if (foregroundTexture == 0) {
            val bitmap = checkNotNull(input.foreground) { "没有前景位图时必须先 prepareForeground" }
            val pixels = input.sourcePixels ?: IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
            prepareForeground(ParticleMicroflakePreparation.Pixels(bitmap.width, bitmap.height,
                ParticleMicroflakePreparation.packColors(pixels), pixels))
        }
        shared.uploadFields(input.guide, input.rules, input.confidence ?: sharedResources(assets).confidence)
        val angle = Math.toRadians(input.direction.toDouble())
        val windX = cos(angle).toFloat(); val windY = -sin(angle).toFloat()
        val span = min(input.cardWidth, input.cardHeight)
        peelPressure = PeelPressure(span, windX, windY)
        applyUniforms()
        checkGl("准备")
    }

    /** 前景颜色不依赖材料模型；GPU 上传可与后台建材同时进行。 */
    fun prepareForeground(pixels: ParticleMicroflakePreparation.Pixels) {
        check(foregroundTexture == 0)
        preparePipeline()
        foregroundTexture = newTexture(GLES30.GL_TEXTURE_2D, textures)
        uploadForeground(pixels)
    }

    /**
     * 第四批 ②：把前景贴图换成另一张同尺寸的图，材料与轨迹一个字节不动。
     *
     * `material.vert` 的第 0 遍按 `uv = source / card` 采样、画在 `world = offset + source`——
     * 采样位置与屏幕位置是同一个恒等仿射映射，所以末帧（t = 0，全部格子 surface = 1）就是
     * 这张贴图的一次逐像素拷贝，**与格子分辨率无关**。提前预算用的是预测快照，真正开播前
     * 换成真实快照，末帧仍与真实页面逐像素相同。
     *
     * 尺寸必须一致：格子是按 `card`（快照尺寸）切的，换一张不同尺寸的图会让采样错位。
     */
    fun replaceForeground(pixels: ParticleMicroflakePreparation.Pixels) {
        check(foregroundTexture != 0) { "还没有前景纹理" }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, foregroundTexture)
        uploadForeground(pixels)
    }

    /** 本次动画的快照尺寸（逻辑像素），用于核对换贴图时尺寸是否一致。 */
    val cardSize: Pair<Float, Float> get() = input.cardWidth to input.cardHeight

    private fun uploadForeground(pixels: ParticleMicroflakePreparation.Pixels) {
        val rgba = pixels.rgba
        rgba.rewind()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, pixels.width, pixels.height,
            0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba)
    }

    /**
     * 把本会话的全部「构造期 uniform」重新写进共享程序。
     *
     * 程序对象在整个上下文里只有一份，uniform 也就只有一份：另一个会话画过之后，
     * 这里的每一个值都可能被改掉。因此凡是只在 [prepare] / [PeelPressure] 构造时设过一次的
     * uniform，都必须在本函数里原样重设（逐帧设置的 time / material_pass / pass 等不在此列）。
     */
    private fun applyUniforms() {
        configureIntegration(compute)
        GLES30.glUseProgram(material)
        two(material, "frame", input.frameWidth, input.frameHeight)
        two(material, "card", input.cardWidth, input.cardHeight)
        two(material, "offset", input.originX, input.originY)
        two(material, "cell", input.materials.cellX, input.materials.cellY)
        val angle = Math.toRadians(input.direction.toDouble())
        two(material, "wind", cos(angle).toFloat(), -sin(angle).toFloat())
        one(material, "span", min(input.cardWidth, input.cardHeight))
        one(material, "roll_gain", input.rules.number("roll_gain"))
        one(material, "light_gain", input.rules.number("light_gain"))
        one(material, "body_weight", input.materials.bodyWeight)
        one(material, "release_spread", input.rules.number("release_spread") +
            input.rules.number("white_spread") * input.materials.bodyWeight)
        one(material, "panel_weight", input.materials.statistics.getValue("panelWeight").toFloat())
        oneI(material, "nx", input.materials.columns); oneI(material, "foreground", 0)
        oneI(material, "grid_count", input.materials.columns * input.materials.rows)
        oneI(material, "diagnostic", 0)
        GLES30.glUseProgram(resolve)
        oneI(resolve, "screen", 2)
        if (::peelPressure.isInitialized) {
            peelPressure.applyUniforms()
            peelPressure.configureIntegration(compute)
            if (optimizedIntegrationPrepared) {
                configureIntegration(reverseCompute)
                peelPressure.configureIntegration(reverseCompute)
            }
        }
        reverseHistory?.applyUniforms()
        shared.owner = this
    }

    /** 共享上下文里换了会话就整套重设 uniform；同一个会话连续工作时是一次布尔比较。 */
    private fun activate() {
        if (shared.owner === this) return
        if (!::peelPressure.isInitialized) { shared.owner = this; return }
        applyUniforms()
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
    fun draw(time: Float, batchPressure: Boolean = false, clearTarget: Boolean = true) {
        val t = time.coerceIn(0f, 1f)
        if (batchPressure) prepareOptimizedIntegration()
        advance(t, batchPressure)
        render(t, clearTarget = clearTarget)
    }

    private fun prepareOptimizedIntegration() {
        if (optimizedIntegrationPrepared) return
        prepareReversePipeline()
        configureIntegration(reverseCompute)
        peelPressure.configureIntegration(reverseCompute)
        optimizedIntegrationPrepared = true
    }

    /**
     * @param historyFrame ≥0 且历史合并生效时，本次采样的最后一步积分顺带写五分量历史。
     * @return 是否已经把 [historyFrame] 写进历史（为 false 时调用方仍需单独转存）。
     */
    private fun advance(t: Float, batchPressure: Boolean = false, historyFrame: Int = -1): Boolean {
        activate()
        val compute = if (batchPressure) reverseCompute else this.compute
        val target = floor(t.toDouble() * 240 + 1e-6).toInt()
        check(target >= step) { "运行时不能倒放材料状态" }
        // 2.4：最早出生时间之前没有任何一片进入运动——积分着色器对 age<=0 写回的就是初值，
        // 压力侧 splat 同样整片跳过，压力场与修正场保持构造时的全零。这段直接跳过。
        if (batchPressure && ParticlePerfTuning.skipBeforeFirstBirth && step == 0) {
            val skip = min(target, firstBirthStep())
            if (skip > 0) step = skip
        }
        val merge = historyFrame >= 0 && reverseHasHistory && batchPressure && step < target
        for (i in buffers.indices) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, buffers[i])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, shared.guideTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, shared.confidenceTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, peelPressure.texture)
        GLES30.glUseProgram(compute)
        if (reverseHasHistory && batchPressure) oneI(compute, "history_frame", -1)
        val probe = probe()
        // 第四批 ③：涟漪在播时把驱动队列压短，让窗口的那一帧不必排在整段 compute 后面。
        val yielding = batchPressure && ParticlePerfTuning.yieldPrecomputeDuringFeedback &&
            ParticleTouchFeedback.feedbackActive
        probe?.mark("integrate", ParticlePerfProbe.LEVEL_SEGMENT)
        while (step < target) {
            step++
            one(compute, "time", step / 240f)
            if (merge && step == target) {
                // 压力更新占用了绑定 4/5/6，最后一步写历史前必须重新绑定范围表与历史缓冲。
                checkNotNull(reverseHistory).bindTargets()
                oneI(compute, "history_frame", historyFrame)
            }
            GLES31.glDispatchCompute((input.materials.count + 255) / 256, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            if (step % 4 == 0) {
                probe?.mark("pressure", ParticlePerfProbe.LEVEL_SEGMENT)
                peelPressure.update(step / 240f, batchPressure)
                if (probe != null) {
                    if (step < target) probe.mark("integrate", ParticlePerfProbe.LEVEL_SEGMENT)
                    else probe.stop(ParticlePerfProbe.LEVEL_SEGMENT)
                }
                for (i in buffers.indices) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, buffers[i])
                GLES30.glUseProgram(compute)
                if (yielding) yieldToWindow()
            }
        }
        probe?.stop(ParticlePerfProbe.LEVEL_SEGMENT)
        return merge
    }

    /**
     * 冲一次并等它算完：驱动队列里最多只剩一个压力批次的工作，窗口那一帧不必排在后面。
     * 代价是失去 CPU 提交与 GPU 运算的重叠，预计算整体会变慢——所以只在涟漪可见时做。
     */
    private fun yieldToWindow() {
        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        if (fence == 0L) return
        GLES30.glClientWaitSync(fence, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, YIELD_TIMEOUT_NANOS)
        GLES30.glDeleteSync(fence)
    }

    private var firstBirthStepCache = -1

    /** 最大的 S，使 S/240f 仍不晚于最早出生时间；着色器里 age=time-src.z<=0 与这一判据逐位等价。 */
    private fun firstBirthStep(): Int {
        if (firstBirthStepCache >= 0) return firstBirthStepCache
        val values = input.materials.values
        var minBirth = Float.MAX_VALUE
        var i = 2
        while (i < values.size) { if (values[i] < minBirth) minBirth = values[i]; i += 12 }
        var s = floor(minBirth.toDouble() * 240).toInt().coerceIn(0, 240)
        while (s < 240 && (s + 1) / 240f <= minBirth) s++
        while (s > 0 && s / 240f > minBirth) s--
        firstBirthStepCache = s
        probe()?.note("firstBirth", minBirth.toDouble())
        probe()?.note("firstBirthStep", s)
        return s
    }

    /**
     * 把当前状态解析到默认帧缓冲。
     *
     * [clearTarget] 为 true 时本函数自己清空目标（离屏对照、预热等单会话路径）；
     * 一窗一 Surface 的合成路径由宿主每拍清一次，各会话依次叠画，所以传 false。
     * resolve 输出的是预乘 alpha，叠画用 `ONE, ONE_MINUS_SRC_ALPHA`——目标清成全零时
     * `src + 0×(1-a) == src`，与改动前「不开混合直接覆写」逐位相同。
     */
    /**
     * 把材料、状态与色素绑成 `divisor=1` 的实例化顶点属性。
     *
     * 位置与 std430 的 stride 一一对应：材料每实例三个 vec4（48 字节）、状态每实例两个
     * vec4（32 字节，后四个分量只在正向积分里用）、色素每实例一个 float。绑的就是
     * compute 写的那几个缓冲对象，同一块显存换一种读法，没有任何数据拷贝。
     */
    private fun bindInstanceAttributes(stateBuffer: Int) {
        fun attribute(location: Int, size: Int, buffer: Int, stride: Int, offset: Int) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
            GLES30.glVertexAttribPointer(location, size, GLES30.GL_FLOAT, false, stride, offset)
            GLES30.glVertexAttribDivisor(location, 1)
            GLES30.glEnableVertexAttribArray(location)
        }
        for (i in 0..2) attribute(i, 4, buffers[0], MATERIAL_STRIDE, i * 16)
        attribute(3, 4, stateBuffer, STATE_STRIDE, 0)
        attribute(4, 4, stateBuffer, STATE_STRIDE, 16)
        attribute(5, 1, buffers[2], 4, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /**
     * 本次绘制用完就关掉。VAO 属于 [Shared]，resolve 与别的会话都用它，而
     * `resolve.vert` 只靠 `gl_VertexID` 取顶点、一个属性也不声明——用完不关，
     * VAO 的属性状态就会带着上一个会话的缓冲留给下一个用它的人。
     */
    private fun disableInstanceAttributes() {
        for (location in 0..5) GLES30.glDisableVertexAttribArray(location)
    }

    private fun render(t: Float, extrapolation: Float = max(0f, t - step / 240f),
                       stateBuffer: Int = buffers[1], clearTarget: Boolean = true) {
        activate()
        probe()?.mark("render", ParticlePerfProbe.LEVEL_SEGMENT)
        GLES30.glBindVertexArray(shared.vao[0])
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shared.framebuffer[0])
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        // material.vert 按实例读材料/状态/色素；顶点阶段不能用 SSBO（见着色器内注释），
        // 同一批缓冲对象在这里绑成实例化顶点属性。共享上下文里上一个会话可能换过它们，
        // 而 stateBuffer 本身也逐路径不同，所以每次绘制都要重设。
        bindInstanceAttributes(stateBuffer)
        GLES30.glUseProgram(material)
        one(material, "time", t)
        one(material, "extrapolate", extrapolation)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, foregroundTexture)
        for (pass in 0..1) {
            oneI(material, "material_pass", pass)
            val filter = if (pass == 0) GLES30.GL_NEAREST else GLES30.GL_LINEAR
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, input.materials.count)
        }
        disableInstanceAttributes()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        if (clearTarget) {
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glUseProgram(resolve)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shared.accumulationTexture)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDisable(GLES30.GL_BLEND)
        probe?.stop(ParticlePerfProbe.LEVEL_SEGMENT)
    }

    // ---- 逆向（出现 / 手势）准备：分步推进，可与别的会话交错 ----

    private var reverseQueue: ParticleGpuWork.PreparationQueue? = null
    private var reverseFrame = 0
    private var reverseStartedAt = 0L

    /** 已经准备到的最后一个采样下标（手势按需补档时用）。 */
    val preparedSamples get() = reverseFrame - 1

    val reverseSamples get() = reverseHistory?.plan?.samples ?: 0

    /** 本次准备要算到的最后一个采样；跳过尾段空白时小于 `plan.samples`。 */
    private var reverseTarget = 0

    /** 材料的十二分量原始值；出生与寿命由它给出（只读，供起点帧判定使用）。 */
    val materialValues: FloatArray get() = input.materials.values

    /**
     * 建立历史缓冲并进入分步准备；[samples] 与 [ParticleReversePlan] 一致。
     * [target] 是真正要算到的采样（默认全算）：倒放起点更早时，比它更晚的采样既不播也不用算。
     * [precomputeProbe] 为 false 时不记「预计算」这一段（手势是按需补档，没有完整预计算）。
     */
    fun beginReverse(samples: Int, target: Int = samples, precomputeProbe: Boolean = true,
                     throttled: Boolean = false) {
        val history = createHistory(samples)
        reverseTarget = target.coerceIn(0, samples)
        reverseStartedAt = System.nanoTime()
        if (precomputeProbe) {
            probeStage = "appear"
            probe()?.beginBusy()
            probe()?.mark("precompute", ParticlePerfProbe.LEVEL_TOTAL)
            reverseProbe = true
        }
        reverseQueue = ParticleGpuWork.PreparationQueue(throttled)
        reverseFrame = 0
        check(history.plan.samples >= 0)
    }

    private var reverseProbe = false

    /** 播放进度对应的历史采样下标；[start] 是倒放起点（默认完整倒放）。 */
    fun reverseFrameFor(progress: Float, start: Int = -1): Int {
        val plan = checkNotNull(reverseHistory).plan
        return plan.frame(progress, if (start >= 0) start else plan.samples)
    }

    /** 本次动画的微片数量与方向、种子；只用于诊断日志。 */
    val particleCount get() = input.materials.count
    val direction get() = input.direction
    val variationSeed get() = input.materials.variation.seed

    /** 是否还有采样没算（只算到 [reverseTarget]）。 */
    val reverseIncomplete get() = reverseQueue != null && reverseFrame <= reverseTarget

    /** 推进一个采样；返回 false 表示被取消。 */
    fun stepReverse(cancelled: () -> Boolean): Boolean = ParticleTrace.section("particle-precompute") {
        stepReverseOnce(cancelled)
    }

    private fun stepReverseOnce(cancelled: () -> Boolean): Boolean {
        val history = checkNotNull(reverseHistory)
        val queue = checkNotNull(reverseQueue)
        if (cancelled()) return false
        val frame = reverseFrame
        probe()?.frame(frame, "pre")
        val merged = advance(history.plan.time(frame), batchPressure = true, historyFrame = frame)
        if (!merged) {
            probe()?.mark("history", ParticlePerfProbe.LEVEL_SEGMENT)
            history.transfer(frame, restore = false)
            probe()?.stop(ParticlePerfProbe.LEVEL_SEGMENT)
        }
        if (!queue.finishBatch(cancelled)) return false
        reverseFrame = frame + 1
        return true
    }

    /** 全部采样算完后调用一次：关闭在途队列并落下诊断记录。 */
    fun endReverse() {
        val queue = reverseQueue ?: return
        reverseQueue = null
        queue.close()
        val history = checkNotNull(reverseHistory)
        val probe = if (reverseProbe) probe() else null
        probe?.stop(ParticlePerfProbe.LEVEL_TOTAL)
        if (probe != null) {
            // 只有开着诊断时才排空管线：墙钟口径要求 GPU 真的算完。
            GLES30.glFinish()
            probe.endBusy()
            probe.note("precomputeWallMs", (System.nanoTime() - reverseStartedAt) / 1e6)
            probe.note("samples", history.plan.samples)
            probe.note("target", reverseTarget)
            probe.note("count", input.materials.count)
            probe.note("historyBytes", history.plan.bytes)
            probe.note("historyAllocMs", history.allocMs)
            probe.note("largeTile", reverseLargeTile)
            probe.note("jacobiBatch", if (reverseLargeTile) reverseJacobiBatch else 8)
            probe.note("historyMerged", reverseHasHistory)
            probe.note("skipBirth", ParticlePerfTuning.skipBeforeFirstBirth)
            probe.flush()
        }
        checkGl("逆向准备")
        if (BuildConfig.DEBUG) Log.i(TAG,
            "出现轨迹准备 elapsedMs=${(System.nanoTime()-reverseStartedAt)/1e6} bytes=${history.plan.bytes} samples=${history.plan.samples} target=$reverseTarget count=${input.materials.count}")
    }

    /** 一次正向解算（离屏对照用的阻塞版本）；GL 线程可随时取消。 */
    fun prepareReverse(samples: Int = ParticleReversePlan.samplesFor(ParticleReversePlan.APPEARANCE_SECONDS, 60f),
                       cancelled: () -> Boolean = { false }): Boolean {
        beginReverse(samples)
        while (reverseIncomplete) if (!stepReverse(cancelled)) return false
        endReverse()
        return !cancelled()
    }

    private fun createHistory(samples: Int): ParticleReverseHistory {
        check(step == 0 && reverseHistory == null)
        prepareOptimizedIntegration()
        return ParticleReverseHistory(program("particle-playback/history.comp"),
            ParticleReversePlan(input.materials.values, samples), shared.history,
            shared.uniforms).also { reverseHistory = it }
    }

    /** 使用正向播放相同时间值、相同状态和相同材质，避免对阻尼／压力求逆引入新轨迹。 */
    fun drawReverseFrame(frame: Int, stateBuffer: Int = buffers[1], clearTarget: Boolean = true) {
        activate()
        val history = checkNotNull(reverseHistory)
        require(frame in 0..history.plan.samples)
        for (i in buffers.indices) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, buffers[i])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, stateBuffer)
        history.transfer(frame, restore = true)
        val t = history.plan.time(frame)
        val frameStep = floor(t.toDouble() * 240 + 1e-6).toInt()
        if (stateBuffer == buffers[1]) step = frameStep
        render(t, max(0f, t - frameStep / 240f), stateBuffer, clearTarget)
    }

    fun drawGestureFrame(position: Float, stateBuffer: Int, clearTarget: Boolean = true) {
        activate()
        val frame = position.toInt()
        val fraction = position - frame
        if (fraction == 0f) {
            drawReverseFrame(frame, stateBuffer, clearTarget)
            return
        }
        val history = checkNotNull(reverseHistory)
        for (i in buffers.indices) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, buffers[i])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, stateBuffer)
        history.transfer(frame, restore = true, fraction = fraction)
        // 相邻缓存已经插值得到此时刻的位置，不能再叠加积分步长的速度外推。
        render(position / history.plan.samples, extrapolation = 0f,
            stateBuffer = stateBuffer, clearTarget = clearTarget)
    }

    /** 手势回放用的独立状态缓冲：五分量历史不能覆盖继续正向积分所需的八分量状态。 */
    private var gestureState = 0

    fun gestureStateBuffer(): Int {
        if (gestureState == 0) {
            val id = IntArray(1)
            GLES30.glGenBuffers(1, id, 0)
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, id[0])
            GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, input.materials.count * 32,
                null, GLES30.GL_DYNAMIC_DRAW)
            gestureState = id[0]
        }
        return gestureState
    }

    /** 手势的第 0 档：没有任何积分步可搭，历史单独转存一次。 */
    fun seedGesture() {
        advance(0f, batchPressure = true)
        checkNotNull(reverseHistory).transfer(0, restore = false)
        reverseFrame = 1
    }

    internal val drawTimings = ArrayList<LongArray>()

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
    private inner class PeelPressure(private val span: Float,
                                     private val windX: Float, private val windY: Float) : Closeable {
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
        val texture = newTexture(GLES30.GL_TEXTURE_2D, textures)

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
            applyUniforms()
            configureIntegration(compute)
            // 首帧只清空修正场；不提前计算整段动画或执行空压力迭代。
            resolveField()
        }

        /** 构造期设过一次的 uniform；共享程序被别的会话改过之后要原样重设。 */
        fun applyUniforms() {
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
            val large = batched && reverseLargeTile
            val solver = if (!batched) project
                else if (large) program("particle-playback/pressure-batched-large.comp", defines = jacobiDefines())
                else program("particle-playback/pressure-batched.comp")
            if (batched) prepareTiles(large)
            GLES30.glUseProgram(solver)
            GLES30.glUniform2i(location(solver, "grid_shape"), columns, rows)
            val batch = if (!batched) 1 else if (large) reverseJacobiBatch else 8
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
                    clearTileDestination(large)
                    GLES30.glUseProgram(solver)
                }
            }
            resolveField()
        }

        /** 大 tile 路径的工作组覆盖 32×32 格，收集粒度必须与求解器的输出 tile 一致。 */
        private val largeGroupsX = (columns + 31) / 32
        private val largeGroupsY = (rows + 31) / 32

        private fun tilesProgram(large: Boolean) =
            if (large) program("particle-playback/pressure-tiles-large.comp")
            else program("particle-playback/pressure-tiles.comp")

        private fun prepareTiles(large: Boolean) {
            if (tileBuffer[0] == 0) {
                GLES30.glGenBuffers(1, tileBuffer, 0)
                GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, tileBuffer[0])
                // 按 16 格粒度（tile 数更多的那一种）分配，两条路径共用同一个缓冲。
                GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 16 + groupsX * groupsY * 8, null, GLES30.GL_DYNAMIC_DRAW)
            }
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 7, tileBuffer[0])
            GLES30.glBindBuffer(GLES31.GL_DISPATCH_INDIRECT_BUFFER, tileBuffer[0])
            val tiles = tilesProgram(large)
            GLES30.glUseProgram(tiles)
            GLES30.glUniform2i(location(tiles, "grid_shape"), columns, rows)
            oneI(tiles, "pass", 0)
            GLES31.glDispatchCompute(1, 1, 1); barrier()
            bind(4 - current, 2)
            oneI(tiles, "pass", 1)
            if (large) GLES31.glDispatchCompute(largeGroupsX, largeGroupsY, 1)
            else GLES31.glDispatchCompute(groupsX, groupsY, 1)
            barrier()
            GLES31.glMemoryBarrier(GLES31.GL_COMMAND_BARRIER_BIT)
        }

        private fun clearTileDestination(large: Boolean) {
            bind(4 - current, 2)
            val tiles = tilesProgram(large)
            GLES30.glUseProgram(tiles)
            oneI(tiles, "pass", 2)
            if (large) GLES31.glDispatchCompute(largeGroupsX, largeGroupsY, 1)
            else GLES31.glDispatchCompute(groupsX, groupsY, 1)
            barrier()
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

    private fun program(vararg paths: String, reverseIntegration: Boolean = false,
                        reverseHistory: Boolean = false, defines: String = ""): Int {
        val key = paths.joinToString(";") + (if (reverseIntegration) ":reverse" else "") +
            (if (reverseHistory) ":history" else "") + defines
        shared.compiled[key]?.let { return it }
        val p = GLES30.glCreateProgram()
        shared.programs.add(p)
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
                val raw = assets.open(if ('/' in path) path else "particle-dismiss/$path").bufferedReader().use { it.readText() }
                val transformed = if (reverseIntegration) ParticleReverseShader.integration(raw, reverseHistory) else raw
                GLES30.glShaderSource(shader, ParticleReverseShader.withDefines(transformed, defines))
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
            shared.compiled[key] = p
            return p
        } finally {
            for (shader in shaders) GLES30.glDeleteShader(shader)
        }
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

    private fun location(program: Int, name: String) =
        shared.uniforms.getOrPut("$program:$name") { GLES30.glGetUniformLocation(program, name) }
    private fun one(program: Int, name: String, value: Float) = GLES30.glUniform1f(location(program, name), value)
    private fun oneI(program: Int, name: String, value: Int) = GLES30.glUniform1i(location(program, name), value)
    private fun two(program: Int, name: String, x: Float, y: Float) = GLES30.glUniform2f(location(program, name), x, y)

    override fun close() {
        probe?.finish(probeStage)
        probe = null
        reverseQueue?.close(); reverseQueue = null
        reverseHistory?.close(); reverseHistory = null
        if (::peelPressure.isInitialized) peelPressure.close()
        if (gestureState != 0) { GLES30.glDeleteBuffers(1, intArrayOf(gestureState), 0); gestureState = 0 }
        if (pipelinePrepared) GLES30.glDeleteBuffers(buffers.size, buffers, 0)
        if (textures.isNotEmpty()) GLES30.glDeleteTextures(textures.size, textures.toIntArray(), 0)
        textures.clear()
        // 程序、累积纹理、流场纹理属于 Shared，会话结束不删。
        if (shared.owner === this) shared.owner = null
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

        private fun newTexture(target: Int, sink: MutableList<Int>): Int {
            val id = IntArray(1)
            GLES30.glGenTextures(1, id, 0); sink.add(id[0])
            GLES30.glBindTexture(target, id[0])
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            if (target == GLES30.GL_TEXTURE_3D) GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
            return id[0]
        }

        @Volatile private var adrenoRenderer: Boolean? = null
        @Volatile private var sharedMemoryBytes = -1

        /** GL_RENDERER 与 shared 上限是设备属性，进程内只查一次（必须在已绑定上下文时调用）。 */
        fun adreno(): Boolean = adrenoRenderer ?: run {
            val value = (GLES30.glGetString(GLES30.GL_RENDERER) ?: "").contains("Adreno", ignoreCase = true)
            adrenoRenderer = value
            value
        }

        fun maxSharedMemory(): Int {
            if (sharedMemoryBytes >= 0) return sharedMemoryBytes
            val value = IntArray(1)
            GLES30.glGetIntegerv(GL_MAX_COMPUTE_SHARED_MEMORY_SIZE, value, 0)
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { /* 清掉查询失败的错误 */ }
            sharedMemoryBytes = if (value[0] > 0) value[0] else 16384
            return sharedMemoryBytes
        }

        private const val GL_MAX_COMPUTE_SHARED_MEMORY_SIZE = 0x8262

        /** `Material`：三个 vec4。与 [ParticleMicroflakeModel.Materials.values] 的每实例 12 float 对应。 */
        private const val MATERIAL_STRIDE = 12 * 4

        /** `State`：位置与速度各一个 vec4；积分缓冲每实例就是 8 float。 */
        private const val STATE_STRIDE = 8 * 4

        /** GLES 3.2 核心；3.1 上由 `EXT_texture_buffer` 提供，驱动不认时会置错误位。 */
        private const val GL_MAX_TEXTURE_BUFFER_SIZE = 0x8C2B

        @Volatile private var capabilitiesLogged = false

        /**
         * 进程内打一次驱动身份与本管线依赖的上限（必须在已绑定上下文时调用）。
         *
         * 这些量在 GLES 3.1/3.2 规范里的最小值低到足以让整条管线不成立：顶点着色器的
         * SSBO 数最小值就是 0，Mali 系一直报 0。2026-09-15 华为 Maleoon 910 上
         * `material.vert` 因此链接失败、每次动画都回退，而开发用的两台都是 Adreno
         * （报 24），缺陷只能在真机日志里认出来。
         */
        fun logCapabilities() {
            if (!BuildConfig.DEBUG || capabilitiesLogged) return
            capabilitiesLogged = true
            fun limit(name: Int): Int {
                val value = IntArray(1)
                GLES30.glGetIntegerv(name, value, 0)
                while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { /* 不认这一项时清掉错误 */ }
                return value[0]
            }
            Log.i(TAG, "粒子 GL 驱动 ${GLES30.glGetString(GLES30.GL_VENDOR)} / " +
                "${GLES30.glGetString(GLES30.GL_RENDERER)} / ${GLES30.glGetString(GLES30.GL_VERSION)}")
            Log.i(TAG, "粒子 GL 上限 " +
                "ssbo顶点=${limit(GLES31.GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS)} " +
                "ssbo片元=${limit(GLES31.GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS)} " +
                "ssbo计算=${limit(GLES31.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS)} " +
                "ssbo绑定点=${limit(GLES31.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS)} " +
                "输出资源=${limit(GLES31.GL_MAX_COMBINED_SHADER_OUTPUT_RESOURCES)} " +
                "shared=${limit(GL_MAX_COMPUTE_SHARED_MEMORY_SIZE)} " +
                "工作组线程=${limit(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS)} " +
                "顶点属性=${limit(GLES30.GL_MAX_VERTEX_ATTRIBS)} " +
                "顶点纹理=${limit(GLES30.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS)} " +
                "纹理缓冲=${limit(GL_MAX_TEXTURE_BUFFER_SIZE)}")
        }

        /** 让路等待的上限；等不到也要往下走，预计算不能卡死。 */
        private const val YIELD_TIMEOUT_NANOS = 50_000_000L
        private fun checkGl(stage: String) { check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "微片 $stage GL 错误" } }

        fun fromSpec(assets: AssetManager, width: Int, height: Int, density: Float, spec: ParticleDismissSpec,
                     sourcePixels: IntArray? = null): Input {
            val bitmap = spec.snapshot
            val pixels = sourcePixels ?: IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
            return fromPixels(assets, width, height, density, pixels, bitmap.width, bitmap.height,
                spec.originXPx, spec.originYPx, spec.virtualTouchXPx, spec.virtualTouchYPx,
                spec.hashSeed, spec.touchGap, spec.touchStrength, bitmap, spec.materialAreaScale)
        }

        /**
         * 只按像素建材，不需要 Bitmap。
         *
         * 提前预算（[ParticleCreationPrewarm]）手上只有上一次真实创建页的像素数组；[fromSpec]
         * 现在就是它的一层包装，两条路径的几何、方向与随机完全同一套代码。
         */
        fun fromPixels(assets: AssetManager, width: Int, height: Int, density: Float,
                       pixels: IntArray, pixelWidth: Int, pixelHeight: Int,
                       originXPx: Float, originYPx: Float,
                       virtualTouchXPx: Float, virtualTouchYPx: Float, hashSeed: Int,
                       touchGap: Float?, touchStrength: Float?, bitmap: Bitmap? = null,
                       materialAreaScale: Float = 1f): Input {
            // 720 逻辑像素对应 384 dp 手机宽度，微片约 1.25 dp；横屏和平板也保持相同界面粒径。
            val scale = (density / 1.875f).coerceAtLeast(.5f)
            val cardWidth = pixelWidth / scale; val cardHeight = pixelHeight / scale
            val dx = virtualTouchXPx - originXPx - pixelWidth / 2f
            val dy = virtualTouchYPx - originYPx - pixelHeight / 2f
            val direction = Math.toDegrees(atan2(-dy, dx).toDouble()).toFloat()
            val resources = sharedResources(assets)
            val rules = resources.rules.withMaterialArea(materialAreaScale)
            val materials = ParticleMicroflakeModel.build(cardWidth, cardHeight, pixels, pixelWidth,
                pixelHeight, direction, hashSeed.toLong(), rules)
            return Input(width / scale, height / scale, cardWidth, cardHeight, originXPx / scale,
                originYPx / scale, direction, bitmap, materials,
                resources.guide, rules, pixels, touchGap ?: rules.number("touch_gap_default"),
                resources.confidence, touchStrength ?: .5f)
        }
    }
}
