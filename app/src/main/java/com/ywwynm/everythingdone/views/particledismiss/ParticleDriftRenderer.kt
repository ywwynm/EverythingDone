package com.ywwynm.everythingdone.views.particledismiss

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import com.ywwynm.everythingdone.BuildConfig
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * 飘散样式的 GL 部分。只在 GL 线程调用；容器对象（VAO / FBO）只在出帧上下文里建与用
 * （[draw] 由窗口表面在绑定出帧上下文之后调用，[prepare] 只建可共享的对象）。
 *
 * 每帧四步，与桌面实现 `harmony_native.py` 的合成顺序一致：
 * 1. compute（`drift-update.comp`）按粒子索引闭式算出全部颗粒；
 * 2. 颗粒画进覆盖完整窗口的半分辨率工作面：加光时 ONE / ONE，标准 src-over 时 ONE / ONE_MINUS_SRC_ALPHA；
 * 3. 工作面最近邻放大、逐通道截到 1，按预乘 over 叠到窗口表面；
 * 4. 卡片残留（快照 × 消蚀遮罩）按预乘 over 盖在最上面。
 *
 * 窗口表面按预乘 over 由系统合成到界面之上：加光时颗粒颜色大于自身 alpha，合成结果就是「背景 + 颗粒」。
 */
internal class ParticleDriftRenderer(
    private val assets: AssetManager,
    private var model: ParticleDriftModel,
    /** true：加光（颗粒颜色按已预乘直接累加）；false：标准 src-over。按背景亮度定（[ParticleDriftBackground]）。 */
    val additive: Boolean,
    private val sourceAlpha: Float = 1f
) : Closeable {
    private val fast = ParticleFastOptions.current
    private var cacheBuffer = 0
    private var cacheReady = false

    private var imageTexture = 0

    /** 零拷贝快照的 EGLImage（[ParticleDriftHardwareImport]）；字节上传的快照为 0。纹理删掉之后再销毁它。 */
    private var imageEgl = 0L
    private var particleBuffer = 0
    private var particleVao = 0
    private var quadVao = 0
    private var workTexture = 0
    private var workFramebuffer = 0
    private var workWidth = 0
    private var workHeight = 0
    private var canvasWidth = 0
    private var canvasHeight = 0
    private var targetWidth = 0
    private var targetHeight = 0
    private val viewport = IntArray(4)
    private var workOffsetX = Float.NaN
    private var workOffsetY = Float.NaN
    private var particleFrame = Int.MIN_VALUE
    private var particleSkip = -1

    /** 同一模拟帧复用颗粒工作面；离屏基准可关闭以逐像素核对和量开销。 */
    internal var reuseParticleFrames = true
    private val lattice = FloatArray(LATTICE_UNIFORMS).also { model.lattice.copyInto(it) }
    private var cellBuffer = 0
    private var content = ParticleDriftContent.Analysis.NONE

    /** 颗粒总数：按面积换算的原有颗粒，加上内容强调追加的副本。 */
    val total get() = model.count + content.replicas

    /** 开播前替换预热候选；快照、颗粒数与 GPU 缓冲不变，丢弃旧轨迹的工作面缓存。 */
    fun useModel(next: ParticleDriftModel) {
        if (model === next) return
        require(next.count == model.count && next.snapshotWidth == model.snapshotWidth &&
            next.snapshotHeight == model.snapshotHeight)
        model = next
        cacheReady = false
        lattice.fill(0f)
        next.lattice.copyInto(lattice)
        particleFrame = Int.MIN_VALUE
    }

    /**
     * 上传快照、建颗粒缓冲与副本分布表（可共享对象，不依赖上下文）。[image] 是先行上传好的快照纹理（[uploadImage]），
     * 交给本渲染器管理；0 表示在这里上传。
     */
    fun prepare(pixels: ParticleMicroflakePreparation.Pixels, analysis: ParticleDriftContent.Analysis, image: Int = 0) =
        prepare(analysis, if (image != 0) image else uploadImage(pixels), 0L)

    /**
     * 快照纹理已经备好（字节上传，或零拷贝绑上的 [eglImage]），交给本渲染器管理；这里只建颗粒缓冲与副本分布表。
     */
    fun prepare(analysis: ParticleDriftContent.Analysis, image: Int, eglImage: Long) {
        content = analysis
        drainErrors()
        ensurePrograms(assets, fast)
        imageTexture = image
        imageEgl = eglImage
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        particleBuffer = ids[0]
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, particleBuffer)
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, total * PARTICLE_BYTES, null, GLES30.GL_DYNAMIC_COPY)
        if (fast.driftCache) {
            GLES30.glGenBuffers(1, ids, 0)
            cacheBuffer = ids[0]
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, cacheBuffer)
            GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, total * CACHE_BYTES, null, GLES30.GL_STATIC_COPY)
        }
        // 副本分布表：没有副本时也建一项，着色器声明的缓冲始终有东西绑着
        val cumulative = analysis.cumulative.takeIf { it.isNotEmpty() } ?: floatArrayOf(1f)
        val cells = ByteBuffer.allocateDirect(cumulative.size * 4).order(ByteOrder.nativeOrder())
        cells.asFloatBuffer().put(cumulative)
        GLES30.glGenBuffers(1, ids, 0)
        cellBuffer = ids[0]
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, cellBuffer)
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, cumulative.size * 4, cells, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        checkGl("准备")
    }

    /**
     * 画源码设备时刻 [deviceTime] 的那一帧。快照左上角在叠加层里的位置是 ([originX], [originY])；
     * 目标尺寸取当前视口（窗口表面或离屏帧缓冲 [target] 在调用前已经设好）。
     *
     * [expansion]：模拟基准尺寸（设备像素），保留桌面参考的出生和湍流坐标。
     * 实际绘制工作面始终覆盖整个视口，粒子离开面板后不会被内部画布截断。
     */
    fun draw(deviceTime: Float, originX: Float, originY: Float, target: Int = 0, expansion: FloatArray? = null,
             beforeComposite: ((Boolean) -> ParticleGlBitmapLayer?)? = null) {
        drainErrors()
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0)
        val canvas = ParticleDriftCanvas.layout(viewport[2], viewport[3], model.snapshotWidth,
            model.snapshotHeight, originX, originY, expansion)
        ensureTargets(viewport[2], viewport[3], canvas)
        val programs = checkNotNull(programs)
        val width = model.snapshotWidth.toFloat()
        val height = model.snapshotHeight.toFloat()
        // 状态只在设备帧上更新（第 k 帧在 k / hz 秒）：卡片残留也取同一帧的时刻
        val frameIndex = floor(deviceTime * ParticleDriftModel.HZ + 1e-3f).toInt()
        val frameTime = frameIndex / ParticleDriftModel.HZ
        // 扩展画布（设备像素）以卡片中心为中心；源码按 cvttps2dq 取整。工作面与发射区都在它的半分辨率坐标里。
        val canvasW = canvasWidth.toFloat()
        val canvasH = canvasHeight.toFloat()
        val emitW = width * .5f
        val emitH = height * .5f
        val emitX = (canvasW * .5f - emitW) * .5f
        val emitY = (canvasH * .5f - emitH) * .5f

        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glColorMask(true, true, true, true)

        val skip = debugSkip
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        // 慢滑、回退与末帧交接可能反复请求同一个设备帧；模型与快照在会话内不变，
        // 颗粒计算和半分辨率工作面可以直接复用。窗口合成与卡片残留仍正常绘制。
        if (!reuseParticleFrames || particleFrame != frameIndex || particleSkip != skip) {
            // 1. 颗粒
            val initializeCache = fast.driftCache && !cacheReady
            repeat(if (initializeCache) 2 else 1) { pass ->
                val initializing = initializeCache && pass == 0
                val update = if (!fast.driftCache) programs.update else
                    checkNotNull(cachePrograms[fast.driftCurve]).let { if (initializing) it.first else it.second }
                GLES30.glUseProgram(update.id)
                if (!fast.driftCache || initializing) {
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imageTexture)
                    GLES30.glUniform1i(update["u_image"], 0)
                    GLES30.glUniform1i(update["u_baseCount"], model.count)
                    GLES30.glUniform2i(update["u_grid"], content.gridWidth, content.gridHeight)
                    GLES30.glUniform3f(update["u_panel"], content.panel[0], content.panel[1], content.panel[2])
                    GLES30.glUniform1f(update["u_panelWeight"], content.panelWeight)
                    GLES30.glUniform1f(update["u_total"], model.totalSeconds)
                    GLES30.glUniform2f(update["u_emitPos"], emitX, emitY)
                    GLES30.glUniform2f(update["u_emitSize"], emitW, emitH)
                    GLES30.glUniform2i(update["u_half"], (width * .5f).toInt(), (height * .5f).toInt())
                    GLES30.glUniform2i(update["u_full"], model.snapshotWidth, model.snapshotHeight)
                }
                GLES30.glUniform1i(update["u_count"], total)
                GLES30.glUniform1f(update["u_time"], deviceTime)
                GLES30.glUniform1f(update["u_hz"], ParticleDriftModel.HZ)
                GLES30.glUniform2f(update["u_wind"], model.windX, model.windY)
                GLES30.glUniform1i(update["u_add"], if (additive) 1 else 0)
                GLES30.glUniform1f(update["u_alphaMul"], model.overAlphaMul)
                GLES30.glUniform1f(update["u_fadeSizePower"], model.fadeSizePower)
                GLES30.glUniform1f(update["u_fadeAlphaPower"], model.fadeAlphaPower)
                GLES30.glUniform1f(update["u_sizeScale"], model.sizeScale)
                if (!fast.driftCache || initializing) {
                    GLES30.glUniform1f(update["u_touchGap"], model.touchGap ?: -1f)
                    GLES30.glUniform2f(update["u_turbOffset"], model.turbulenceOffsetX, model.turbulenceOffsetY)
                    // 选用参考样式时，湍流取样换算到参考卡片自己的发射区：lookup = pos0 × scale + shift
                    val frame = model.turbulenceFrame
                    if (frame != null) {
                        val scaleX = frame[2] / emitW
                        val scaleY = frame[3] / emitH
                        GLES30.glUniform2f(update["u_turbScale"], scaleX, scaleY)
                        GLES30.glUniform2f(update["u_turbShift"], frame[0] - emitX * scaleX, frame[1] - emitY * scaleY)
                    } else {
                        GLES30.glUniform2f(update["u_turbScale"], 1f, 1f)
                        GLES30.glUniform2f(update["u_turbShift"], 0f, 0f)
                    }
                    latticeUniforms(update)
                }
                GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, particleBuffer)
                GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, cellBuffer)
                if (fast.driftCache) GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, cacheBuffer)
                if (initializing || skip and SKIP_COMPUTE == 0) GLES31.glDispatchCompute((total + 255) / 256, 1, 1)
                GLES31.glMemoryBarrier(if (initializing) GLES31.GL_SHADER_STORAGE_BARRIER_BIT else
                    GLES31.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT)
                if (initializing) cacheReady = true
                GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 0)
                GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, 0)
                if (fast.driftCache) GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, 0)
            }

            // 2. 颗粒画进工作面
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, workFramebuffer)
            GLES30.glViewport(0, 0, workWidth, workHeight)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (additive) GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            else GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            val particle = programs.particle
            GLES30.glUseProgram(particle.id)
            GLES30.glUniform2f(particle["u_work"], workWidth.toFloat(), workHeight.toFloat())
            GLES30.glUniform2f(particle["u_workOffset"], canvas.particleOffsetX, canvas.particleOffsetY)
            GLES30.glUniform1i(particle["u_tightQuad"], if (fast.driftTightQuad) 1 else 0)
            GLES30.glBindVertexArray(particleVao)
            if (skip and SKIP_PARTICLES == 0) GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, total)
            particleFrame = frameIndex
            particleSkip = skip
        }

        // 3. 工作面叠到窗口表面（或离屏帧缓冲）
        // 先完成离屏颗粒，再绘制窗口底图，避免已画好的整页在切换 FBO 时被迫存回、重新载入。
        val base = beforeComposite?.invoke(skip and SKIP_COMPOSITE == 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target)
        GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        if (base == null) GLES30.glEnable(GLES30.GL_BLEND) else GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glBindVertexArray(quadVao)
        val composite = if (base == null) programs.composite else programs.compositeWithBase
        GLES30.glUseProgram(composite.id)
        if (base == null) GLES30.glUniform4f(composite["u_rect"], canvas.workLeft, canvas.workTop, canvas.workRight, canvas.workBottom)
        else GLES30.glUniform4f(composite["u_rect"], 0f, 0f, viewport[2].toFloat(), viewport[3].toFloat())
        if (base != null) {
            GLES30.glUniform4f(composite["u_workRect"], canvas.workLeft, canvas.workTop, canvas.workRight, canvas.workBottom)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, base.texture)
            GLES30.glUniform1i(composite["u_base"], 3)
            GLES30.glUniform4f(composite["u_baseRect"], base.x, base.y, base.width.toFloat(), base.height.toFloat())
        }
        GLES30.glUniform4f(composite["u_viewport"], viewport[0].toFloat(), viewport[1].toFloat(),
            viewport[2].toFloat(), viewport[3].toFloat())
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, workTexture)
        GLES30.glUniform1i(composite["u_work"], 2)
        GLES30.glUniform1f(composite["u_containerFade"], 1f - sourceAlpha)
        GLES30.glUniform2i(composite["u_workSize"], workWidth, workHeight)
        if (skip and SKIP_COMPOSITE == 0) GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glEnable(GLES30.GL_BLEND)

        // 4. 卡片残留盖在最上面
        if (skip and SKIP_CARD == 0 && frameTime < model.bodyGoneSeconds + 1f / ParticleDriftModel.HZ) {
            val card = programs.card
            GLES30.glUseProgram(card.id)
            GLES30.glUniform4f(card["u_rect"], originX, originY, originX + width, originY + height)
            GLES30.glUniform4f(card["u_viewport"], viewport[0].toFloat(), viewport[1].toFloat(),
                viewport[2].toFloat(), viewport[3].toFloat())
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imageTexture)
            GLES30.glUniform1i(card["u_image"], 0)
            GLES30.glUniform2i(card["u_full"], model.snapshotWidth, model.snapshotHeight)
            GLES30.glUniform1f(card["u_time"], frameTime)
            GLES30.glUniform1f(card["u_containerFade"], 1f - sourceAlpha)
            latticeUniforms(card)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        checkGl("绘制")
    }

    private fun latticeUniforms(program: Program) {
        GLES30.glUniform1i(program["u_latticeW"], model.latticeWidth)
        GLES30.glUniform1i(program["u_latticeH"], model.latticeHeight)
        GLES30.glUniform1fv(program["u_lattice"], LATTICE_UNIFORMS, lattice, 0)
        GLES30.glUniform1f(program["u_scaleU"], model.scaleU)
    }

    /** 工作面只在出帧上下文里建；尺寸变化时重建，坐标平移变化时丢弃已绘制的颗粒帧。 */
    private fun ensureTargets(width: Int, height: Int, canvas: ParticleDriftCanvas.Layout) {
        val ids = IntArray(1)
        if (particleVao == 0) {
            GLES30.glGenVertexArrays(1, ids, 0)
            particleVao = ids[0]
            GLES30.glBindVertexArray(particleVao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, particleBuffer)
            for (location in 0..1) {
                GLES30.glEnableVertexAttribArray(location)
                GLES30.glVertexAttribPointer(location, 4, GLES30.GL_FLOAT, false, PARTICLE_BYTES, location * 16)
                GLES30.glVertexAttribDivisor(location, 1)
            }
            GLES30.glBindVertexArray(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glGenVertexArrays(1, ids, 0)
            quadVao = ids[0]
        }
        val nextWorkW = canvas.workWidth
        val nextWorkH = canvas.workHeight
        if (canvasWidth != canvas.simulationWidth || canvasHeight != canvas.simulationHeight) cacheReady = false
        if (canvasWidth != canvas.simulationWidth || canvasHeight != canvas.simulationHeight ||
            workOffsetX != canvas.particleOffsetX || workOffsetY != canvas.particleOffsetY) particleFrame = Int.MIN_VALUE
        workOffsetX = canvas.particleOffsetX
        workOffsetY = canvas.particleOffsetY
        canvasWidth = canvas.simulationWidth
        canvasHeight = canvas.simulationHeight
        if (width == targetWidth && height == targetHeight && nextWorkW == workWidth && nextWorkH == workHeight &&
            workFramebuffer != 0) return
        targetWidth = width
        targetHeight = height
        releaseWork()
        workWidth = nextWorkW
        workHeight = nextWorkH
        GLES30.glGenTextures(1, ids, 0)
        workTexture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, workTexture)
        nearestClamp()
        GLES30.glGenFramebuffers(1, ids, 0)
        workFramebuffer = ids[0]
        // 保持原 sRGB 数值上的混合方式；标准档仅改为 8 位归一化存储，不额外转换色彩空间。
        val floating = !fast.driftStandardColor && attachWork(GLES30.GL_RGBA16F, GLES30.GL_HALF_FLOAT)
        if (!floating) {
            check(attachWork(GLES30.GL_RGBA8, GLES30.GL_UNSIGNED_BYTE)) { "飘散工作面帧缓冲不完整" }
        }
        if (BuildConfig.DEBUG) Log.i(TAG,
            "accumulation=" + (if (floating) "RGBA16F" else "RGBA8") + " ${workWidth}x${workHeight}")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun attachWork(internalFormat: Int, type: Int): Boolean {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, workTexture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internalFormat, workWidth, workHeight, 0,
            GLES30.GL_RGBA, type, null)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, workFramebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, workTexture, 0)
        return GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
    }

    private fun releaseWork() {
        particleFrame = Int.MIN_VALUE
        if (workFramebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(workFramebuffer), 0)
        if (workTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(workTexture), 0)
        workFramebuffer = 0
        workTexture = 0
    }

    override fun close() {
        releaseWork()
        if (particleVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(particleVao), 0)
        if (quadVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(quadVao), 0)
        if (particleBuffer != 0) GLES30.glDeleteBuffers(1, intArrayOf(particleBuffer), 0)
        if (cellBuffer != 0) GLES30.glDeleteBuffers(1, intArrayOf(cellBuffer), 0)
        if (cacheBuffer != 0) GLES30.glDeleteBuffers(1, intArrayOf(cacheBuffer), 0)
        if (imageTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(imageTexture), 0)
        if (imageEgl != 0L) ParticleHardwareTexture.destroy(ParticleGl.display.nativeHandle, imageEgl)
        particleVao = 0; quadVao = 0; particleBuffer = 0; imageTexture = 0; cellBuffer = 0; imageEgl = 0L
        cacheBuffer = 0; cacheReady = false
    }

    internal class Program(val id: Int) {
        private val locations = HashMap<String, Int>()
        operator fun get(name: String): Int =
            locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    }

    private class Programs(val update: Program, val particle: Program, val composite: Program, val compositeWithBase: Program,
                           val card: Program, val gather: Program)

    internal companion object {
        const val TAG = "ParticleDrift"

        /**
         * 仅调试包的 GPU 基准（`DriftParityRender.bench`）：逐项去掉某一遍，量各遍在整段时间轴里的份额。正式路径恒为 0。
         * 只在 GL 线程上读写。
         */
        var debugSkip = 0
        const val SKIP_COMPUTE = 1
        const val SKIP_PARTICLES = 2
        const val SKIP_COMPOSITE = 4
        const val SKIP_CARD = 8

        /** 每颗粒子在缓冲里两个 vec4：位置与半宽、预乘颜色。 */
        const val PARTICLE_BYTES = 32

        /** 标准 src-over 时颗粒层 alpha 的倍数（桌面复刻参考 5 按暗点数、暗点深度与整体压暗量定）。 */
        const val OVER_ALPHA_MUL = 1.15f

        const val LATTICE_UNIFORMS = 64

        /** 进程内只编译一次（上下文常驻）；只在 GL 线程访问。 */
        private var programs: Programs? = null
        private const val CACHE_BYTES = 96
        private val cachePrograms = HashMap<Boolean, Pair<Program, Program>>()

        /**
         * 在常驻 GL 线程上先把着色器编好；失败不要紧，首个动画会再编一次。参考样式表与输入算式也先走一遍
         * （类加载、JSON 解析），首个动画在主线程上定输入时只剩真正的计算。内容分析拿一张合成的小图连跑几次：
         * 进程里第一次飘散的分析没经过编译，实测 55–70 ms，热了之后 13–26 ms。
         */
        fun warm(assets: AssetManager) {
            // 合成样本与内容分析不使用 GL。冷启动、媒体加载时可能尚未经过 JIT，
            // 不能排在整页首帧的上传之前占住唯一渲染线程。
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runCatching {
                    ParticleDriftModel.random(1080, 1920, 0f, -1f, 1f, ParticleDriftModel.seededRandom(0),
                        ParticleDriftStyles.all(assets))
                }
                runCatching {
                    val sample = warmSample()
                    repeat(3) { ParticleDriftContent.analyse(WARM_WIDTH, WARM_HEIGHT, sample, 20000) }
                }
            }, "ParticleDriftModelWarmup").start()
            ParticleGl.post {
                runCatching { if (ParticleGl.ensureContextReady()) ensurePrograms(assets) }
                    .onFailure { Log.w(TAG, "飘散着色器预热未完成，首个动画时再编译", it) }
            }
        }

        /**
         * 把快照传成纹理（最近邻、边缘截断），返回纹理名；交给随后的 [prepare]，或由调用方自己删除。
         * 直接从内存上传：经像素缓冲对象上传在三星 SM-S9180 上实测更慢（整屏快照 18–27 ms，直接上传 9–17 ms）。
         */
        fun uploadImage(pixels: ParticleMicroflakePreparation.Pixels): Int {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            nearestClamp()
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            pixels.rgba.position(0)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, pixels.width, pixels.height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels.rgba)
            return ids[0]
        }

        private const val WARM_WIDTH = 512
        private const val WARM_HEIGHT = 256

        /** 预热用的合成快照（预乘 RGBA）：白色面板，上方一条彩色标题、右下一块彩色按钮，内容分析的各条分支都会走到。 */
        private fun warmSample(): ByteBuffer {
            val buffer = ByteBuffer.allocateDirect(WARM_WIDTH * WARM_HEIGHT * 4).order(ByteOrder.nativeOrder())
            for (y in 0 until WARM_HEIGHT) for (x in 0 until WARM_WIDTH) {
                val title = y in 24 until 56 && x in 32 until 320
                val button = y in 196 until 232 && x in 380 until 488
                buffer.put(if (title) 0x21.toByte() else if (button) 0xE9.toByte() else 0xFF.toByte())
                buffer.put(if (title) 0x96.toByte() else if (button) 0x1E.toByte() else 0xFF.toByte())
                buffer.put(if (title) 0xF3.toByte() else if (button) 0x63.toByte() else 0xFF.toByte())
                buffer.put(0xFF.toByte())
            }
            buffer.rewind()
            return buffer
        }

        fun ensurePrograms(assets: AssetManager, fast: ParticleFastOptions = ParticleFastOptions.current) {
            if (fast.driftCache && fast.driftCurve !in cachePrograms) {
                val body = read(assets, "drift-update.comp").replace("#include \"drift-common.glsl\"",
                    read(assets, "drift-common.glsl"))
                val defines = "#define DRIFT_CACHE 1\n" + (if (fast.driftCurve) "#define DRIFT_CURVE 1\n" else "")
                fun cached(extra: String): Program {
                    val source = ParticleReverseShader.withDefines(body, defines + extra)
                    return Program(link(compile(GLES31.GL_COMPUTE_SHADER, source, "drift cached $extra")))
                }
                cachePrograms[fast.driftCurve] = cached("#define DRIFT_INITIALIZE 1\n") to cached("")
            }
            if (programs != null) return
            val common = read(assets, "drift-common.glsl")
            val coverage = read(assets, "drift-coverage.glsl")
            fun source(name: String) = read(assets, name)
                .replace("#include \"drift-common.glsl\"", common)
                .replace("#include \"drift-coverage.glsl\"", coverage)
            val update = link(compile(GLES31.GL_COMPUTE_SHADER, source("drift-update.comp"), "drift-update.comp"))
            val quad = { compile(GLES30.GL_VERTEX_SHADER, source("drift-quad.vert"), "drift-quad.vert") }
            val particle = link(compile(GLES30.GL_VERTEX_SHADER, source("drift-particle.vert"), "drift-particle.vert"),
                compile(GLES30.GL_FRAGMENT_SHADER, source("drift-particle.frag"), "drift-particle.frag"))
            val composite = link(quad(),
                compile(GLES30.GL_FRAGMENT_SHADER, source("drift-composite.frag"), "drift-composite.frag"))
            val compositeWithBase = link(quad(), compile(GLES30.GL_FRAGMENT_SHADER,
                source("drift-composite.frag").replace("#version 310 es", "#version 310 es\n#define PAGE_BASE 1"),
                "drift-composite-page.frag"))
            val card = link(quad(), compile(GLES30.GL_FRAGMENT_SHADER, source("drift-card.frag"), "drift-card.frag"))
            val gather = link(compile(GLES31.GL_COMPUTE_SHADER, source("drift-gather.comp"), "drift-gather.comp"))
            programs = Programs(Program(update), Program(particle), Program(composite), Program(compositeWithBase), Program(card),
                Program(gather))
        }

        /** 零拷贝快照的内容取样程序（`drift-gather.comp`）。 */
        fun gatherProgram(assets: AssetManager): Program {
            ensurePrograms(assets)
            return checkNotNull(programs).gather
        }

        fun nearestClamp() {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }

        private fun read(assets: AssetManager, name: String): String =
            assets.open("particle-dismiss/$name").bufferedReader().use { it.readText() }

        private fun compile(type: Int, source: String, name: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                error("飘散着色器 $name 编译失败：$log")
            }
            return shader
        }

        private fun link(vararg shaders: Int): Int {
            val program = GLES30.glCreateProgram()
            shaders.forEach { GLES30.glAttachShader(program, it) }
            GLES30.glLinkProgram(program)
            shaders.forEach { GLES30.glDeleteShader(it) }
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                error("飘散着色器链接失败：$log")
            }
            return program
        }

        /** 同一上下文里别的绘制可能留下了错误；只检查本段自己的。 */
        private fun drainErrors() {
            var guard = 0
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR && guard++ < 16) Unit
        }

        private fun checkGl(stage: String) {
            val error = GLES30.glGetError()
            check(error == GLES30.GL_NO_ERROR) { "飘散样式$stage GL 错误 0x${Integer.toHexString(error)}" }
        }
    }
}
