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
    initialInput: Input? = null,
    /**
     * 碰一碰的吸入模式（[ParticleSink]）。积分着色器多编进一段「转向终点」，程序缓存按宏
     * 区分，与其余动画的程序互不相干。只能在建渲染器时决定：管线在拿到材料之前就要编译。
     */
    private val sinkMode: Boolean = false
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
        val touchStrength: Float = .5f,
        /** 吸入终点在材料坐标里的几何（与微片出生位置同一坐标系）；非吸入模式为 null。 */
        val sink: ParticleSink.Geometry? = null
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

        /**
         * U7：累积目标的内部格式。`false` = `RGBA16F`（现状），`true` = `SRGB8_ALPHA8`。
         * 混合模式是预乘 over，结果留在 `[0,1]`，unorm 目标可用；代价是 8 位量化。
         */
        var accumulationSrgb = false
            private set

        /** 切换累积格式时重建纹理；格式没变就是空操作。 */
        fun ensureAccumulationFormat(srgb: Boolean) {
            if (!prepared || srgb == accumulationSrgb) { accumulationSrgb = srgb; return }
            accumulationSrgb = srgb
            val w = width; val h = height
            width = 0; height = 0
            ensure(w, h)
        }

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
            if (accumulationSrgb) {
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_SRGB8_ALPHA8, width, height, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            } else {
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0,
                    GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, accumulationTexture, 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "设备不支持微片线性颜色缓冲"
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            prepared = true
            // 「颜色精度」这项设置唯一可观测的落点就是这张纹理的内部格式，debug 包打一行出来，
            // 真机验收据此核对设置真的生效了（纯 ASCII，方便 grep）。
            if (BuildConfig.DEBUG) Log.i(TAG,
                "accumulation=" + (if (accumulationSrgb) "SRGB8_ALPHA8" else "RGBA16F") +
                    " " + width + "x" + height)
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
            fieldShape = intArrayOf(w, h, time)
            fieldSource = guide to confidence
        }

        /**
         * U2：把流场（RG16F）与置信度（R8）合成一张 RGBA16F 三维纹理，`field_cell()` 的
         * 取样从 2 次降到 1 次。**在进程里合成，不新增打包资源**：guide 本身已经是 half 编码，
         * 逐 texel 抄 4 字节再补一个 `half(confidence/255)` 即可。64×64×48 × 8 B = 1.5 MB。
         */
        fun mergedField(): Int {
            if (mergedTexture != 0) return mergedTexture
            val shape = fieldShape ?: return 0
            val (guide, confidence) = fieldSource ?: return 0
            val texels = shape[0] * shape[1] * shape[2]
            val bytes = ByteBuffer.allocateDirect(texels * 8).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until texels) {
                bytes.put(guide, i * 4, 4)
                bytes.putShort(android.util.Half.toHalf((confidence[i].toInt() and 0xff) / 255f))
                bytes.putShort(0)
            }
            bytes.flip()
            mergedTexture = newTexture(GLES30.GL_TEXTURE_3D, textures)
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F, shape[0], shape[1], shape[2],
                0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, bytes)
            return mergedTexture
        }

        var mergedTexture = 0
            private set
        private var fieldShape: IntArray? = null
        private var fieldSource: Pair<ByteArray, ByteArray>? = null

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
            mergedTexture = 0
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

    // ---- 逐帧成本专项第一批：取值只在 preparePipeline 那一次确定 ----
    //
    // 与 `reverseHasHistory` 同一条纪律：开关中途变化会让已经编译好的程序与 Kotlin 侧的
    // 分支对不上（属性布局、SSBO 绑定、indirect 参数全都要一致）。离屏 A/B 靠
    // [ParticlePerfTuning.withPerFrameBaseline] 在**建会话时**决定走哪一套。
    private var useDrawRecords = false
    private var useActiveRegion = false

    /** A1 的绘制记录缓冲：每片四个 vec4（位置流两个 + 外观流两个）。 */
    private val recordBuffer = IntArray(1)
    private var recordProgram = 0

    /**
     * 逐帧成本专项第二阶段：整批开关在准备管线那一次取快照，之后不再读。
     * 理由与 [useDrawRecords] 相同——属性布局、纹理格式、dispatch 除数都要与已编译的变体一致。
     */
    private var phase2 = 0

    private fun p2(item: Int) = phase2 and item != 0

    private fun ensurePhase2() {
        if (phase2Read) return
        phase2Read = true
        phase2 = ParticlePerfTuning.phase2Snapshot()
    }

    private var phase2Read = false

    /** M1：非 Adreno 的工作组变体。一维核 256 → 64，二维核 16×16 → 8×8。 */
    private val maliGroups get() = p2(ParticlePerfTuning.P2_MALI_LOCAL_SIZE) && !adreno()

    private val linearGroup get() = if (maliGroups) 64 else 256

    private val planeGroup get() = if (maliGroups) 8 else 16

    private fun groupDefines() =
        if (maliGroups) "#define LINEAR_GROUP 64\n#define PLANE_GROUP 8\n" else ""

    private fun linearGroups(items: Int) = (items + linearGroup - 1) / linearGroup

    /** 编译期宏：压力着色器一套，材料着色器一套。 */
    private fun regionDefines() = (if (useActiveRegion) "#define ACTIVE_REGION 1\n" else "") +
        groupDefines() +
        (if (p2(ParticlePerfTuning.P2_CLEAR_ACTIVE_REGION)) "#define CLEAR_ACTIVE_REGION 1\n" else "") +
        (if (p2(ParticlePerfTuning.P2_NEAREST_SPLAT)) "#define NEAREST_SPLAT 1\n" else "") +
        (if (p2(ParticlePerfTuning.P2_MEDIUMP_ALU)) "#define MEDIUMP_ALU 1\n" else "") +
        (if (p2(ParticlePerfTuning.P2_PEEL_FIELD_HALF)) "#define PEEL_FIELD_HALF 1\n" else "")

    /** 积分着色器的宏：流场取样与精度那几项。 */
    private fun stepDefines() = groupDefines() +
        (if (p2(ParticlePerfTuning.P2_MERGED_FIELD)) "#define MERGED_FIELD 1\n" else "") +
        (if (p2(ParticlePerfTuning.P2_MEDIUMP_SAMPLER)) "#define MEDIUMP_SAMPLER 1\n" else "") +
        (if (p2(ParticlePerfTuning.P2_PEEL_FIELD_HALF)) "#define PEEL_FIELD_HALF 1\n" else "") +
        (if (p2(ParticlePerfTuning.P2_MEDIUMP_ALU)) "#define MEDIUMP_ALU 1\n" else "") +
        (if (p2(ParticlePerfTuning.P2_HARDWARE_TRILINEAR)) "#define HARDWARE_TRILINEAR 1\n" else "") +
        (if (p2(ParticlePerfTuning.P2_PROBE_SINGLE_CELL)) "#define PROBE_SINGLE_CELL 1\n" else "") +
        (if (sinkMode) "#define SINK 1\n" else "")

    private fun materialDefines() = (if (useDrawRecords) "#define DRAW_RECORDS 1\n" else "") +
        (if (useDrawRecords && p2(ParticlePerfTuning.P2_MEDIUMP_VARYING)) "#define MEDIUMP_VARYING 1\n" else "") +
        (if (useFlakeConstants) "#define FLAKE_CONSTANTS 1\n" else "") +
        (if (useSingleInstanceBuffer) "#define SINGLE_INSTANCE 1\n" else "") +
        (if (p2(ParticlePerfTuning.P2_SRGB8_ACCUMULATION)) "#define SRGB_TARGET 1\n" else "")

    /** U4 与 U10a 都是绘制记录的扩展，绘制记录关掉时它们无从谈起。 */
    private val useFlakeConstants get() =
        useDrawRecords && p2(ParticlePerfTuning.P2_FLAKE_CONSTANTS)

    // U10a 建立在 U4 之上：`random` 的四个分量在 U4 之后片元侧一个都不需要了，
    // 只剩复制片要的 `src.xy`，正好放进绘制记录多出来的那一段。所以它的对照基线是「U4 单开」。
    private val useSingleInstanceBuffer get() =
        useFlakeConstants && p2(ParticlePerfTuning.P2_SINGLE_INSTANCE_BUFFER)

    /**
     * 绘制记录的每片字节数。**U4 不改变它**：逐片外观常量换掉了 age/life/light，多出来的量
     * 靠打包塞进同样四段。只有 U10a 会多一段（复制片的采样中心）。
     */
    private val recordStride get() = if (useSingleInstanceBuffer) RECORD_STRIDE_WIDE else RECORD_STRIDE

    /** `draw-record.comp` 的宏：U4 让它多算四个逐片量。 */
    private fun recordDefines() = groupDefines() +
        (if (useFlakeConstants) "#define FLAKE_CONSTANTS 1\n" else "") +
        (if (useSingleInstanceBuffer) "#define SINGLE_INSTANCE 1\n" else "")

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
        ensurePhase2()
        useDrawRecords = ParticlePerfTuning.drawRecords
        // C10 在 splat 里多绑一个 SSBO（区域/包围盒），ES 3.1 只保证 4 个 compute 存储块；
        // 目标设备都给 8 以上，但不满足时宁可整项退回整网格 dispatch。
        useActiveRegion = ParticlePerfTuning.pressureActiveRegion && storageBlocksAvailable()
        compute = program("step.comp", defines = stepDefines())
        material = program("material.vert", "material.frag", defines = materialDefines())
        resolve = program("resolve.vert", "resolve.frag",
            defines = if (p2(ParticlePerfTuning.P2_SRGB8_ACCUMULATION)) "#define SRGB_TARGET 1\n" else "")
        program("peel-splat.comp", defines = regionDefines())
        program("peel-blur.comp", defines = regionDefines())
        program("peel-project.comp", defines = regionDefines())
        if (useActiveRegion) program("peel-region.comp")
        if (useDrawRecords) recordProgram = program("draw-record.comp", defines = recordDefines())
        shared.ensureAccumulationFormat(p2(ParticlePerfTuning.P2_SRGB8_ACCUMULATION))
        GLES30.glGenBuffers(buffers.size, buffers, 0)
        if (sharedFields != null) {
            shared.uploadFields(sharedFields.guide, sharedFields.rules, sharedFields.confidence)
        }
        pipelinePrepared = true
    }

    /** 只编译出现准备所需程序，不生成轨迹；可与后台建材重叠或在空闲时预热。 */
    fun prepareReversePipeline() {
        if (reversePipelinePrepared) return
        reversePipelinePrepared = true
        ensurePhase2()
        // 取值只在编译这一次确定：之后一律按「这个程序里有没有历史写入」判断，
        // 不再读开关。否则开关中途变化会让 history_frame 停在默认 0 上，
        // 着色器就会把历史写进当时绑在 4/5 上的压力缓冲。
        reverseHasHistory = ParticlePerfTuning.historyInIntegration
        reverseLargeTile = largeTileJacobi()
        reverseCompute = program("step.comp", reverseIntegration = true,
            reverseHistory = reverseHasHistory, defines = stepDefines())
        if (reverseLargeTile) {
            program("particle-playback/pressure-batched-large.comp", defines = jacobiDefines())
            program("particle-playback/pressure-tiles-large.comp")
        } else if (!plainJacobi) {
            program("particle-playback/pressure-batched.comp", defines = batchedDefines())
            program("particle-playback/pressure-tiles.comp", defines = regionDefines())
        }
        program("particle-playback/history.comp", defines = groupDefines())
    }

    /**
     * M3：非 Adreno 上压力求解改走**逐次** Jacobi（`peel-project.comp`，无 shared）。
     * Arm 明写 shared 不是片上内存、且不要把全局内存抄进 shared——批处理变体的三条前提
     * 在 Mali 族上逐条不成立。默认按第一步的 B2 对照结果定（见方案 1.4）。
     */
    private val plainJacobi get() =
        p2(ParticlePerfTuning.P2_PLAIN_JACOBI_ON_MALI) && !adreno()

    /** U6：批处理 Jacobi 的散度与占据度移出 shared（16 → 8 KiB），逐位恒等。 */
    private fun batchedDefines() =
        if (p2(ParticlePerfTuning.P2_SHARED_HALF)) "#define REGISTER_BANDS 1\n" else ""

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
        if (useDrawRecords && recordBuffer[0] == 0) {
            GLES30.glGenBuffers(1, recordBuffer, 0)
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, recordBuffer[0])
            GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER,
                input.materials.count * recordStride, null, GLES30.GL_DYNAMIC_DRAW)
        }
        if (foregroundTexture == 0) {
            val bitmap = checkNotNull(input.foreground) { "没有前景位图时必须先 prepareForeground" }
            val pixels = input.sourcePixels ?: IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
            prepareForeground(ParticleMicroflakePreparation.Pixels(bitmap.width, bitmap.height,
                ParticleMicroflakePreparation.packColors(pixels), pixels))
        }
        shared.uploadFields(input.guide, input.rules,
            input.confidence ?: sharedResources(assets).confidence)
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
        // U7：累积目标换成 SRGB8_ALPHA8 时前景也一起换——采样器直接给线性值，
        // `material.frag` 的 `linear()` 整段省掉（判据的两个阈值按 sRGB→线性换算）。
        val internal = if (p2(ParticlePerfTuning.P2_SRGB8_ACCUMULATION)) GLES30.GL_SRGB8_ALPHA8
            else GLES30.GL_RGBA8
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, pixels.width, pixels.height,
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
        if (useDrawRecords) {
            GLES30.glUseProgram(recordProgram)
            two(recordProgram, "card", input.cardWidth, input.cardHeight)
            two(recordProgram, "cell", input.materials.cellX, input.materials.cellY)
            two(recordProgram, "wind", cos(angle).toFloat(), -sin(angle).toFloat())
            one(recordProgram, "span", min(input.cardWidth, input.cardHeight))
            one(recordProgram, "roll_gain", input.rules.number("roll_gain"))
            one(recordProgram, "body_weight", input.materials.bodyWeight)
            if (useFlakeConstants) one(recordProgram, "light_gain", input.rules.number("light_gain"))
            one(recordProgram, "release_spread", input.rules.number("release_spread") +
                input.rules.number("white_spread") * input.materials.bodyWeight)
            one(recordProgram, "panel_weight", input.materials.statistics.getValue("panelWeight").toFloat())
            oneI(recordProgram, "nx", input.materials.columns)
            oneI(recordProgram, "grid_count", input.materials.columns * input.materials.rows)
            oneI(recordProgram, "count", input.materials.count)
        }
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
        // U2：合成纹理占纹理单元 6；两张原纹理仍然绑着（`sample_peel` 与别的会话共用程序）。
        if (p2(ParticlePerfTuning.P2_MERGED_FIELD)) oneI(compute, "merged_field", 6)
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
        if (sinkMode) {
            val sink = checkNotNull(input.sink) { "吸入模式缺少终点" }
            two(compute, "sink_point", sink.x, sink.y)
            one(compute, "sink_swirl", ParticleSink.SWIRL)
            GLES30.glUniform4f(location(compute, "sink_frame"),
                sink.frameLeft, sink.frameTop, sink.frameRight, sink.frameBottom)
            two(compute, "sink_frame_mix", sink.frameShare, sink.frameJitter)
        }
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
        if (p2(ParticlePerfTuning.P2_MERGED_FIELD)) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE6)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, shared.mergedField())
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, peelPressure.texture)
        GLES30.glUseProgram(compute)
        if (reverseHasHistory && batchPressure) oneI(compute, "history_frame", -1)
        val probe = probe()
        // 第四批 ③：涟漪在播时把驱动队列压短，让窗口的那一帧不必排在整段 compute 后面。
        val yielding = batchPressure && ParticlePerfTuning.yieldPrecomputeDuringFeedback &&
            ParticleTouchFeedback.feedbackActive
        probe?.mark("integrate", ParticlePerfProbe.LEVEL_SEGMENT)
        val skipIntegrate = ParticleAblation.skipping(ParticleAblation.INTEGRATE)
        // 四项全跳才算「整段压力不做」；只跳其中一两项由 PeelPressure.update 内部处理。
        //
        // 流动精细度取 0 时同样整段不跑：修正场保持 PeelPressure 构造时 `resolveField()` 清出的
        // 零场，粒子照常积分，只是彼此不再互相推挤。跳掉这一段而不是「迭代 0 次」，是因为
        // 后者仍会 splat / 模糊 / 收集 tile，还会让压力缓冲里留下没被求解过的散度。
        val skipPressure = ParticleAblation.bits and ParticleAblation.PRESSURE == ParticleAblation.PRESSURE ||
            ParticlePerfTuning.jacobiIterationsNow <= 0
        while (step < target) {
            step++
            one(compute, "time", step / 240f)
            if (merge && step == target) {
                // 压力更新占用了绑定 4/5/6，最后一步写历史前必须重新绑定范围表与历史缓冲。
                checkNotNull(reverseHistory).bindTargets()
                oneI(compute, "history_frame", historyFrame)
            }
            if (!skipIntegrate || merge && step == target) {
                GLES31.glDispatchCompute(linearGroups(input.materials.count), 1, 1)
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
                probe?.submit("integrate")
            }
            if (step % 4 == 0 && !skipPressure) {
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
        if (useDrawRecords) {
            // A1：位置只看 0/1 两个属性（共 32 字节，来自同一块紧凑缓冲），4/5 是外观侧仍然
            // 需要的两份原始材料分量（片段着色器的 random 与复制片的采样中心）。
            val slots = recordStride / 16
            for (i in 0 until slots) attribute(i, 4, recordBuffer[0], recordStride, i * 16)
            if (!useSingleInstanceBuffer) {
                // 4/5 是外观侧仍然需要的两份原始材料分量（片段着色器的 random 与复制片的
                // 采样中心）。U4 之后 random 一个分量都不用了，只剩复制片的采样中心；
                // U10a 再把它并进绘制记录，一次绘制就只剩一个实例化缓冲（Arm 5.9 的 Don't）。
                attribute(4, 4, buffers[0], MATERIAL_STRIDE, 0)
                // U4 之后片元一个 random 分量都不用了，位置 5 的那一路整条省掉。
                if (!useFlakeConstants) attribute(5, 4, buffers[0], MATERIAL_STRIDE, 32)
            }
        } else {
            for (i in 0..2) attribute(i, 4, buffers[0], MATERIAL_STRIDE, i * 16)
            attribute(3, 4, stateBuffer, STATE_STRIDE, 0)
            attribute(4, 4, stateBuffer, STATE_STRIDE, 16)
            attribute(5, 1, buffers[2], 4, 0)
        }
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

    /**
     * A1：本帧的绘制记录。两遍绘制共用同一份，现场与回放也共用同一条路径——它只读
     * 「材料 + 本次要画的那个状态缓冲 + 色素」，与状态是刚积分出来的还是刚从历史恢复的无关。
     *
     * 屏障必须带 `GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT`：写进去是 SSBO，读出来是顶点属性。
     */
    private fun writeDrawRecords(t: Float, extrapolation: Float, stateBuffer: Int) {
        if (!useDrawRecords) {
            // 基线路径（`withPerFrameBaseline` 关掉绘制记录时走这条）：`material.vert` 直接把
            // compute 写过的**状态缓冲**当实例化顶点属性读，按规范需要这一位，而 `advance()`
            // 的积分循环每步只发了 `GL_SHADER_STORAGE_BARRIER_BIT`。开着绘制记录时下面那次
            // 屏障带了这一位，关掉之后就没人发——而所有 G0/G1 对照都以这条路径为参考。
            GLES31.glMemoryBarrier(GLES31.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT)
            return
        }
        val probe = probe()
        probe?.mark("records", ParticlePerfProbe.LEVEL_SEGMENT)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffers[0])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, stateBuffer)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, buffers[2])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 6, recordBuffer[0])
        GLES30.glUseProgram(recordProgram)
        one(recordProgram, "time", t)
        one(recordProgram, "extrapolate", extrapolation)
        GLES31.glDispatchCompute(linearGroups(input.materials.count), 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT or
            GLES31.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT)
        probe?.submit("records")
    }

    private fun render(t: Float, extrapolation: Float = max(0f, t - step / 240f),
                       stateBuffer: Int = buffers[1], clearTarget: Boolean = true) {
        activate()
        val probe = probe()
        writeDrawRecords(t, extrapolation, stateBuffer)
        probe?.mark("clear", ParticlePerfProbe.LEVEL_SEGMENT)
        GLES30.glBindVertexArray(shared.vao[0])
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shared.framebuffer[0])
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        if (!ParticleAblation.skipping(ParticleAblation.CLEAR)) {
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            probe?.submit("clear")
        }
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
        val ablation = ParticleAblation.bits
        val passes = (0..1).filter {
            ablation and (if (it == 0) ParticleAblation.PASS0 else ParticleAblation.PASS1) == 0
        }
        for (pass in passes) {
            probe?.mark("pass$pass", ParticlePerfProbe.LEVEL_SEGMENT)
            oneI(material, "material_pass", pass)
            val filter = if (pass == 0) GLES30.GL_NEAREST else GLES30.GL_LINEAR
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, input.materials.count)
            probe?.submit("pass$pass")
        }
        probe?.mark("resolve", ParticlePerfProbe.LEVEL_SEGMENT)
        disableInstanceAttributes()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        if (clearTarget) {
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        if (ablation and ParticleAblation.RESOLVE == 0) {
            GLES30.glUseProgram(resolve)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shared.accumulationTexture)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            probe?.submit("resolve")
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        probe?.stop(ParticlePerfProbe.LEVEL_SEGMENT)
    }

    // ---- 逆向（出现 / 手势）准备：分步推进，可与别的会话交错 ----

    private var reverseQueue: ParticleGpuWork.PreparationQueue? = null
    private var reverseFrame = 0
    private var reverseStartedAt = 0L

    /**
     * 第二批 F15：异步推进。提交一个采样就放一个栅栏、`glFlush` 之后**不等**，
     * 由 [pollReverse] 轮询确认完成。CPU 不再每个采样阻塞 4–7 ms，节拍与合成才有地方落脚。
     *
     * 出帧侧只许画到**确认完成**的采样（[preparedSamples]），所以后台上下文写、出帧上下文读
     * 之间不需要任何 `glWaitSync`——客户端观察到栅栏已发信号，就意味着那之前的写入对
     * share group 里的任何上下文都可见。
     */
    private var reverseAsync = false
    private var reverseActive = false
    private val reverseFences = ArrayDeque<LongArray>()
    private var reverseCompleted = -1

    /** 已经准备好、可以安全画出来的最后一个采样下标（手势按需补档时用）。 */
    val preparedSamples get() = if (reverseAsync) reverseCompleted else reverseFrame - 1

    /** 已经发给 GPU 的最后一个采样；异步模式下它可以跑在 [preparedSamples] 前面。 */
    val submittedSamples get() = reverseFrame - 1

    /** 采样全部算完并且 GPU 确认写完。 */
    val reverseSettled get() = !reverseIncomplete && reverseFences.isEmpty()

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
                     throttled: Boolean = false, async: Boolean = false) {
        val history = createHistory(samples)
        reverseTarget = target.coerceIn(0, samples)
        reverseStartedAt = System.nanoTime()
        if (precomputeProbe) {
            probeStage = "appear"
            probe()?.beginBusy()
            probe()?.mark("precompute", ParticlePerfProbe.LEVEL_TOTAL)
            reverseProbe = true
        }
        reverseAsync = async
        reverseActive = true
        reverseCompleted = -1
        reverseQueue = if (async) null else ParticleGpuWork.PreparationQueue(throttled)
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

    /** 逐帧成本专项的规模读数：压力网格列 / 行 / 格数、材料格子列 / 行、累积纹理像素数。 */
    val diagnosticSizes: IntArray get() = intArrayOf(
        if (::peelPressure.isInitialized) peelPressure.columns else 0,
        if (::peelPressure.isInitialized) peelPressure.rows else 0,
        if (::peelPressure.isInitialized) peelPressure.cells else 0,
        input.materials.columns, input.materials.rows, width, height, input.materials.count)

    /** 仅供离屏基准给探针记录的行打上帧号与阶段名；探针关着时什么也不做。 */
    fun probeFrame(index: Int, phase: String) { probe()?.frame(index, phase) }
    val direction get() = input.direction
    val variationSeed get() = input.materials.variation.seed

    /** 是否还有采样没算（只算到 [reverseTarget]）。 */
    val reverseIncomplete get() = reverseActive && reverseFrame <= reverseTarget

    /** 推进一个采样；返回 false 表示被取消。同步口径：提交之后等上一批算完。 */
    fun stepReverse(cancelled: () -> Boolean): Boolean = ParticleTrace.section("particle-precompute") {
        stepReverseOnce(cancelled)
    }

    private fun stepReverseOnce(cancelled: () -> Boolean): Boolean {
        // 异步模式下同样给出阻塞语义（离屏对照与关掉追赶时的同步补档要的就是「回来即备好」）。
        if (reverseAsync) {
            if (advanceReverse(cancelled) < 0) return false
            drainReverse()
            return true
        }
        val queue = checkNotNull(reverseQueue)
        if (!integrateSample(cancelled)) return false
        val frame = reverseFrame
        if (!queue.finishBatch(cancelled)) return false
        reverseFrame = frame + 1
        return true
    }

    /** 一个采样的积分 + 历史转存；提交与等待由两种口径各自处理。返回 false 表示被取消。 */
    private fun integrateSample(cancelled: () -> Boolean): Boolean {
        val history = checkNotNull(reverseHistory)
        if (cancelled()) return false
        val frame = reverseFrame
        probe()?.frame(frame, "pre")
        val merged = advance(history.plan.time(frame), batchPressure = true, historyFrame = frame)
        if (!merged && !ParticleAblation.skipping(ParticleAblation.HISTORY)) {
            val probe = probe()
            probe?.mark("history", ParticlePerfProbe.LEVEL_SEGMENT)
            history.transfer(frame, restore = false)
            probe?.submit("history")
            probe?.stop(ParticlePerfProbe.LEVEL_SEGMENT)
        }
        return true
    }

    /**
     * 异步推进：-1 取消、0 没有进展（在途已满或已经算完）、1 有进展。
     *
     * 每次先轮询一遍在途栅栏——[preparedSamples] 只有在这里才往前走，出帧侧的可画档位
     * 因此永远落在「GPU 确认写完」的那一侧。
     */
    fun advanceReverse(cancelled: () -> Boolean): Int = ParticleTrace.section("particle-precompute") {
        if (!reverseAsync) {
            if (!reverseIncomplete) return@section 0
            return@section if (stepReverse(cancelled)) 1 else -1
        }
        var progressed = pollReverse()
        if (reverseIncomplete && reverseFences.size < reverseInFlightLimit()) {
            if (!integrateSample(cancelled)) return@section -1
            val frame = reverseFrame
            val sync = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            check(sync != 0L) { "创建粒子采样同步点失败" }
            // `glWaitSync` 没有自带的 flush 位，生产侧必须自己冲，否则消费侧可能永远等不到。
            GLES30.glFlush()
            reverseFences.addLast(longArrayOf(frame.toLong(), sync))
            reverseFrame = frame + 1
            progressed = true
        }
        if (progressed) 1 else 0
    }

    /**
     * 在途采样上限。
     *
     * 沿用第一批 `PreparationQueue` 的**两批在途**——改的是「CPU 不再阻塞等它」，不是
     * 「往驱动里排更多」。排得更深会让弹窗 A 的一帧要等上后台几十毫秒的 compute，而
     * 「低优先级上下文一定会被抢占」这件事在 Adreno 上没有公开保证（研究第 8 节：抢占点
     * 在 bin 边界，compute dispatch 内部能不能抢占没有资料）。深度本身留成可调项。
     */
    private fun reverseInFlightLimit(): Int {
        val configured = ParticlePerfTuning.reverseInFlight
        return if (configured > 0) configured else 2
    }

    /** 轮询在途栅栏，把 [preparedSamples] 推到已经确认完成的那一个；返回是否前进。 */
    fun pollReverse(): Boolean {
        var advanced = false
        while (reverseFences.isNotEmpty()) {
            val head = reverseFences.first()
            val status = GLES30.glClientWaitSync(head[1], 0, 0)
            if (status != GLES30.GL_ALREADY_SIGNALED && status != GLES30.GL_CONDITION_SATISFIED &&
                status != GLES30.GL_WAIT_FAILED) break
            reverseFences.removeFirst()
            GLES30.glDeleteSync(head[1])
            reverseCompleted = head[0].toInt()
            advanced = true
        }
        return advanced
    }

    /** 排空在途栅栏（交接点、收尾与离屏对照用）。 */
    private fun drainReverse() {
        while (reverseFences.isNotEmpty()) {
            val head = reverseFences.removeFirst()
            GLES30.glClientWaitSync(head[1], GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, FENCE_TIMEOUT_NANOS)
            GLES30.glDeleteSync(head[1])
            reverseCompleted = head[0].toInt()
        }
    }

    /** 全部采样算完后调用一次：排空在途、关闭队列并落下诊断记录。 */
    fun endReverse() {
        if (!reverseActive) return
        drainReverse()
        reverseActive = false
        val queue = reverseQueue
        reverseQueue = null
        queue?.close()
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
        return ParticleReverseHistory(program("particle-playback/history.comp", defines = groupDefines()),
            ParticleReversePlan(input.materials.values, samples), shared.history,
            shared.uniforms, linearGroup).also { reverseHistory = it }
    }

    /** 使用正向播放相同时间值、相同状态和相同材质，避免对阻尼／压力求逆引入新轨迹。 */
    fun drawReverseFrame(frame: Int, stateBuffer: Int = buffers[1], clearTarget: Boolean = true) {
        activate()
        val history = checkNotNull(reverseHistory)
        require(frame in 0..history.plan.samples)
        for (i in buffers.indices) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, buffers[i])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, stateBuffer)
        val restoreProbe = probe()
        restoreProbe?.mark("restore", ParticlePerfProbe.LEVEL_SEGMENT)
        if (!ParticleAblation.skipping(ParticleAblation.HISTORY)) {
            history.transfer(frame, restore = true)
            restoreProbe?.submit("restore")
        }
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
        val restoreProbe = probe()
        restoreProbe?.mark("restore", ParticlePerfProbe.LEVEL_SEGMENT)
        history.transfer(frame, restore = true, fraction = fraction)
        restoreProbe?.submit("restore")
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

    /**
     * 手势的第 0 档：没有任何积分步可搭，历史单独转存一次。
     *
     * **必须在出帧上下文里调用**（[ParticleSession.beginPrecompute] 已经显式切过去）：
     * 它与随后的首帧绘制在同一条命令流上，次序由 GL 自己保证，所以第 0 档直接算「已完成」，
     * 识别左滑之后的首个非零画面不必再等一次栅栏。
     */
    fun seedGesture() {
        advance(0f, batchPressure = true)
        checkNotNull(reverseHistory).transfer(0, restore = false)
        reverseCompleted = 0
        reverseFrame = 1
    }

    internal val drawTimings = ArrayList<LongArray>()

    /**
     * U7 的判据（仅诊断）：累积纹理里 **alpha 的直方图**，64 档，只统计非零的像素。
     *
     * 回答的是「换成 8 位定点之后量化档位够不够用」——大量低 alpha 微片叠加时，
     * 直方图挤在最低几档就说明会出条带。按行分段读回，整屏浮点一次读是 88 MB。
     */
    fun accumulationAlphaHistogram(buckets: Int = 64): IntArray {
        val histogram = IntArray(buckets)
        val srgb = shared.accumulationSrgb
        val band = 64
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shared.framebuffer[0])
        val bytesPerPixel = if (srgb) 4 else 16
        val buffer = ByteBuffer.allocateDirect(width * band * bytesPerPixel).order(ByteOrder.nativeOrder())
        var y = 0
        while (y < height) {
            val rows = min(band, height - y)
            buffer.clear()
            if (srgb) GLES30.glReadPixels(0, y, width, rows, GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE, buffer)
            else GLES30.glReadPixels(0, y, width, rows, GLES30.GL_RGBA, GLES30.GL_FLOAT, buffer)
            if (srgb) {
                for (i in 0 until width * rows) {
                    val a = buffer.get(i * 4 + 3).toInt() and 255
                    if (a > 0) histogram[min(buckets - 1, a * buckets / 256)]++
                }
            } else {
                val floats = buffer.asFloatBuffer()
                for (i in 0 until width * rows) {
                    val a = floats.get(i * 4 + 3)
                    if (a > 0f) histogram[(a.coerceIn(0f, 1f) * (buckets - 1)).toInt()]++
                }
            }
            y += rows
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return histogram
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

    /**
     * 仅诊断（C7 在 Maleoon 上「迭代数不生效」的排查）：读回最近一次压力更新的中间状态。
     *
     * 返回 `[网格列, 网格行, 活动 tile 数, 占据格数, 非零压力格数, Σ|p|, max|p|]`。
     * 会 `glFinish` 并映射两个 SSBO，**只许诊断路径调用**，不在任何计时段里用。
     */
    fun pressureDiagnostics(): DoubleArray = peelPressure.diagnose()

    /** 从当前分布抵消剥离汇聚；不删除材料，也不替换每片已有的流动速度。 */
    private inner class PeelPressure(private val span: Float,
                                     private val windX: Float, private val windY: Float) : Closeable {
        private val gridCell = span.toDouble() / 96.0
        val columns = ceil(input.cardWidth / gridCell).toInt() + 192
        val rows = ceil(input.cardHeight / gridCell).toInt() + 192
        val cells = columns * rows
        // `pressure-tiles.comp` 的 16×16 与 tile 粒度、活动区域的 16 格对齐绑死，**不随 M1 变**；
        // 只有 `peel-blur` / `peel-project` 这两个纯二维核跟着工作组变体走（方案 1.3）。
        private val groupsX = (columns + 15) / 16
        private val groupsY = (rows + 15) / 16
        private val planeGroupsX = (columns + planeGroup - 1) / planeGroup
        private val planeGroupsY = (rows + planeGroup - 1) / planeGroup
        private val pressureBuffers = IntArray(5)
        // 三个程序必须带上与 preparePipeline 相同的宏，否则 program() 的缓存键不同、
        // 拿到的是另一份**没有**区域分支的程序对象，而 Kotlin 侧还在按区域分发。
        private val splat = program("peel-splat.comp", defines = regionDefines())
        private val blur = program("peel-blur.comp", defines = regionDefines())
        private val project = program("peel-project.comp", defines = regionDefines())
        private var current = 0
        private val tileBuffer = IntArray(1)

        /** C10：0 = 还没初始化，1 = 整网格，2 = 按包围盒驱动。切换两种形态都要先复位一次。 */
        private var regionState = 0
        private val region get() = if (useActiveRegion) program("peel-region.comp") else 0

        val texture = newTexture(GLES30.GL_TEXTURE_2D, textures)

        init {
            // U3：半精度时 `sample_peel` 换成一次硬件双线性，采样器必须是 LINEAR；
            // RGBA32F 那条路径仍是显式 4 点 `texelFetch`，过滤器取值不参与结果。
            // **格式只能是 RGBA16F**：GLES 3.1 的存储图像格式表里没有 RG16F（Adreno 740 实测拒编）。
            val linearPeel = p2(ParticlePerfTuning.P2_PEEL_FIELD_HALF)
            val filter = if (linearPeel) GLES30.GL_LINEAR else GLES30.GL_NEAREST
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1,
                if (linearPeel) GLES30.GL_RGBA16F else GLES30.GL_RGBA32F, columns, rows)
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
            // C10：`peel-project.comp` 编进了区域分支，所以区域缓冲必须在第一次用它之前就位。
            if (useActiveRegion) { ensureTileBuffer(); resetRegion() }
            // 首帧只清空修正场；不提前计算整段动画或执行空压力迭代。
            resolveField()
        }

        /** 工作缓冲：表头是 Jacobi / 清零 / 区域三组 indirect 参数加包围盒，其后才是 tile 列表。 */
        private fun ensureTileBuffer() {
            if (tileBuffer[0] != 0) return
            GLES30.glGenBuffers(1, tileBuffer, 0)
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, tileBuffer[0])
            // 按 16 格粒度（tile 数更多的那一种）分配，两条路径共用同一个缓冲。
            GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER,
                WORK_HEADER_BYTES + groupsX * groupsY * 8, null, GLES30.GL_DYNAMIC_DRAW)
        }

        private fun bindWork() {
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 7, tileBuffer[0])
            GLES30.glBindBuffer(GLES31.GL_DISPATCH_INDIRECT_BUFFER, tileBuffer[0])
        }

        /** 把区域状态写回「整网格」；构造时、以及在两种形态之间切换时各来一次。 */
        private fun resetRegion() {
            ensureTileBuffer()
            bindWork()
            GLES30.glUseProgram(region)
            GLES30.glUniform2i(location(region, "grid_shape"), columns, rows)
            oneI(region, "reset", 1)
            GLES31.glDispatchCompute(1, 1, 1)
            barrier()
            oneI(region, "reset", 0)
            regionState = 1
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
            if (useActiveRegion) {
                // 每帧只剩 glUseProgram + dispatch：网格形状与 reset 都不随帧变，
                // 放在这里与别的构造期 uniform 一起重设（共享程序被别的会话改过就会走到这）。
                GLES30.glUseProgram(region)
                GLES30.glUniform2i(location(region, "grid_shape"), columns, rows)
                oneI(region, "reset", 0)
            }
        }

        fun configureIntegration(compute: Int) {
            GLES30.glUseProgram(compute)
            oneI(compute, "peel_field", 5)
            GLES30.glUniform4f(location(compute, "peel_bounds"), -span, -span,
                (columns * gridCell).toFloat(), (rows * gridCell).toFloat())
        }

        private fun bind(index: Int, binding: Int) =
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, binding, pressureBuffers[index])

        /**
         * U1：**compute → compute 之间只有 SSBO 依赖**，只需要 `GL_SHADER_STORAGE_BARRIER_BIT`。
         * 现场消散一帧里这样的屏障约 20 次；图像与纹理那两位要求的可见性在 tile 架构上
         * 可能意味着把整场景冲进主存（Arm 的 OpenGL ES SDK）。
         *
         * 唯一需要三位全开的是 [resolveField]：它 `imageStore` 到 `peel_field`，随后
         * `step.comp` 要 `texelFetch` / `texture()` 它。
         */
        private fun barrier() =
            if (p2(ParticlePerfTuning.P2_NARROW_BARRIERS)) GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            else fullBarrier()

        private fun fullBarrier() = GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT or
            GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)

        fun update(time: Float, batched: Boolean) {
            val probe = probe()
            val ablation = ParticleAblation.bits
            val large = batched && reverseLargeTile
            // C10：只有「16 格 tile + 批处理 Jacobi」这条生产路径按包围盒驱动。大 tile 变体
            // 默认关闭且没有收益，两者都保持整网格。
            //
            // M2（第二阶段）：把活动区域也接到**逐次** Jacobi 路径上。`peel-project.comp`
            // 早就带 `ACTIVE_REGION` 早退，只差这个条件——区域外恒为零，不变式与
            // `peel-region.comp` 的注释一致，属 G0。
            val plain = batched && plainJacobi
            val driven = useActiveRegion && !large &&
                (batched && !plain || p2(ParticlePerfTuning.P2_REGION_PLAIN_JACOBI))
            if (useActiveRegion) {
                if (driven) { if (regionState != 2) { resetRegion(); regionState = 2 } }
                else if (regionState != 1) resetRegion()
                bindWork()
            }
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffers[0])
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, buffers[1])
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, buffers[3])
            bind(0, 4)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, foregroundTexture)
            probe?.mark("splat", ParticlePerfProbe.LEVEL_SEGMENT)
            if (ablation and ParticleAblation.SPLAT == 0) {
                GLES30.glUseProgram(splat)
                one(splat, "time", time)
                oneI(splat, "pass", 0)
                GLES31.glDispatchCompute(linearGroups(cells), 1, 1); barrier()
                oneI(splat, "pass", 1)
                GLES31.glDispatchCompute(linearGroups(input.materials.count), 1, 1); barrier()
                probe?.submit("splat", 2)
            }
            if (driven) {
                // 把本帧的原子包围盒换算成活动区域。**不做间接分发**：indirect 要配
                // `GL_COMMAND_BARRIER_BIT`，Adreno 上那道屏障比省下的格子还贵（2026-09-17
                // 实测整段反而慢 4%–13%）。分发形状保持整网格，区域外的线程直接早退。
                GLES30.glUseProgram(region)
                GLES31.glDispatchCompute(1, 1, 1); barrier()
                probe?.submit("splat")
            }
            bind(1, 5); bind(2, 6)
            probe?.mark("blur", ParticlePerfProbe.LEVEL_SEGMENT)
            if (ablation and ParticleAblation.BLUR == 0) {
                GLES30.glUseProgram(blur)
                for (pass in 0..1) {
                    oneI(blur, "pass", pass)
                    GLES31.glDispatchCompute(planeGroupsX, planeGroupsY, 1); barrier()
                }
                probe?.submit("blur", 2)
            }
            bind(2, 0)
            // M3：非 Adreno 上把 `batched` 请求落到逐次路径上（`plain`）。两条路径的迭代式
            // 逐条相同，差别只在「分块 + shared」与「整网格 + 更多屏障」。
            val useBatched = batched && !plain
            val solver = if (!useBatched) project
                else if (large) program("particle-playback/pressure-batched-large.comp", defines = jacobiDefines())
                else program("particle-playback/pressure-batched.comp", defines = batchedDefines())
            probe?.mark("tiles", ParticlePerfProbe.LEVEL_SEGMENT)
            if (useBatched) { prepareTiles(large); probe?.submit("tiles", 2) }
            GLES30.glUseProgram(solver)
            GLES30.glUniform2i(location(solver, "grid_shape"), columns, rows)
            val batch = if (!useBatched) 1 else if (large) reverseJacobiBatch else 8
            probe?.mark("jacobi", ParticlePerfProbe.LEVEL_SEGMENT)
            if (ablation and ParticleAblation.JACOBI == 0) {
                // C7 的调参口；默认 100 即现状。
                val total = ParticlePerfTuning.jacobiIterationsNow
                for (iteration in 0 until total step batch) {
                    bind(3 + current, 1); bind(4 - current, 2)
                    if (useBatched) {
                        oneI(solver, "first_iteration", iteration)
                        oneI(solver, "iterations", min(batch, total - iteration))
                    } else oneI(solver, "iteration", iteration)
                    if (useBatched) GLES31.glDispatchComputeIndirect(0L)
                    else GLES31.glDispatchCompute(planeGroupsX, planeGroupsY, 1)
                    barrier()
                    probe?.submit("jacobi")
                    current = 1 - current
                    if (useBatched && iteration == 0) {
                        // 首批读取了上一时刻压力；之后才清除另一个缓冲，保留原首轮邻域贡献。
                        clearTileDestination(large)
                        probe?.submit("jacobi")
                        GLES30.glUseProgram(solver)
                    }
                }
            }
            probe?.mark("project", ParticlePerfProbe.LEVEL_SEGMENT)
            if (ablation and ParticleAblation.PROJECT == 0) { resolveField(); probe?.submit("project") }
            probe?.stop(ParticlePerfProbe.LEVEL_SEGMENT)
        }

        /** 大 tile 路径的工作组覆盖 32×32 格，收集粒度必须与求解器的输出 tile 一致。 */
        private val largeGroupsX = (columns + 31) / 32
        private val largeGroupsY = (rows + 31) / 32

        private fun tilesProgram(large: Boolean) =
            if (large) program("particle-playback/pressure-tiles-large.comp")
            else program("particle-playback/pressure-tiles.comp", defines = regionDefines())

        private fun prepareTiles(large: Boolean) {
            ensureTileBuffer()
            bindWork()
            val tiles = tilesProgram(large)
            GLES30.glUseProgram(tiles)
            GLES30.glUniform2i(location(tiles, "grid_shape"), columns, rows)
            oneI(tiles, "pass", 0)
            GLES31.glDispatchCompute(1, 1, 1); barrier()
            bind(4 - current, 2)
            oneI(tiles, "pass", 1)
            dispatchGrid(large)
            barrier()
            GLES31.glMemoryBarrier(GLES31.GL_COMMAND_BARRIER_BIT)
        }

        private fun clearTileDestination(large: Boolean) {
            bind(4 - current, 2)
            val tiles = tilesProgram(large)
            GLES30.glUseProgram(tiles)
            oneI(tiles, "pass", 2)
            dispatchGrid(large)
            barrier()
        }

        private fun dispatchGrid(large: Boolean) {
            if (large) GLES31.glDispatchCompute(largeGroupsX, largeGroupsY, 1)
            else GLES31.glDispatchCompute(groupsX, groupsY, 1)
        }

        /** 见 [pressureDiagnostics]。Jacobi 循环每批翻一次 `current`，最新的一份是 `3 + current`。 */
        fun diagnose(): DoubleArray {
            GLES30.glFinish()
            var tiles = -1.0
            val header = IntArray(16)
            if (tileBuffer[0] != 0) {
                GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, tileBuffer[0])
                val mapped = (GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0,
                    WORK_HEADER_BYTES, GLES30.GL_MAP_READ_BIT) as ByteBuffer)
                    .order(ByteOrder.nativeOrder()).asIntBuffer()
                for (i in header.indices) header[i] = mapped.get(i)
                tiles = (header[0].toLong() and 0xFFFFFFFFL).toDouble()
                GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            }
            // 绑定 0（tile 收集与求解器眼里的 `velocity`）是 pressureBuffers[2]，不是 [0]；
            // [0] 是 splat 的整数累积缓冲。`.z` 就是 tile 收集判活跃用的那一项。
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, pressureBuffers[2])
            val velocity = (GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, cells * 16,
                GLES30.GL_MAP_READ_BIT) as ByteBuffer).order(ByteOrder.nativeOrder()).asFloatBuffer()
            var occupied = 0
            for (i in 0 until cells) if (velocity.get(i * 4 + 2) > .025f) occupied++
            GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, pressureBuffers[0])
            val accumulation = (GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, cells * 16,
                GLES30.GL_MAP_READ_BIT) as ByteBuffer).order(ByteOrder.nativeOrder()).asIntBuffer()
            var splatted = 0
            var splattedIndex = -1
            for (i in 0 until cells) if (accumulation.get(i * 4 + 2) != 0) {
                splatted++
                if (splattedIndex < 0) splattedIndex = i
            }
            GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, pressureBuffers[3 + current])
            val field = (GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, cells * 4,
                GLES30.GL_MAP_READ_BIT) as ByteBuffer).order(ByteOrder.nativeOrder()).asFloatBuffer()
            var nonZero = 0
            var sum = 0.0
            var peak = 0.0
            for (i in 0 until cells) {
                val v = abs(field.get(i).toDouble())
                if (v > 0.0) { nonZero++; sum += v; if (v > peak) peak = v }
            }
            GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            // 后八项是工作缓冲表头里的 region 与 previous（= 本帧 splat 记下的 tile 包围盒）：
            // 「活动 tile 数为 0」还要分是区域退化了还是 tile 收集没收到。
            return doubleArrayOf(columns.toDouble(), rows.toDouble(), tiles,
                occupied.toDouble(), nonZero.toDouble(), sum, peak,
                header[4].toDouble(), header[5].toDouble(), header[6].toDouble(), header[7].toDouble(),
                header[8].toDouble(), header[9].toDouble(), header[10].toDouble(), header[11].toDouble(),
                splatted.toDouble(), splattedIndex.toDouble())
        }

        private fun resolveField() {
            bind(2, 0); bind(3 + current, 1); bind(4 - current, 2)
            GLES31.glBindImageTexture(0, texture, 0, false, 0, GLES31.GL_WRITE_ONLY,
                if (p2(ParticlePerfTuning.P2_PEEL_FIELD_HALF)) GLES30.GL_RGBA16F else GLES30.GL_RGBA32F)
            GLES30.glUseProgram(project)
            oneI(project, "iteration", 100)
            GLES31.glDispatchCompute(planeGroupsX, planeGroupsY, 1)
            // 这一步写的是图像、读的是纹理采样，三位必须全开（U1 的唯一例外）。
            fullBarrier()
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
        reverseActive = false
        while (reverseFences.isNotEmpty()) GLES30.glDeleteSync(reverseFences.removeFirst()[1])
        reverseHistory?.close(); reverseHistory = null
        if (::peelPressure.isInitialized) peelPressure.close()
        if (gestureState != 0) { GLES30.glDeleteBuffers(1, intArrayOf(gestureState), 0); gestureState = 0 }
        if (recordBuffer[0] != 0) { GLES30.glDeleteBuffers(1, recordBuffer, 0); recordBuffer[0] = 0 }
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

        /**
         * A1 的绘制记录：每片四个 vec4。前两个是位置流（预乘好的 2×2 矩阵 + 原点与格子号），
         * 后两个是外观流（年龄/寿命/明暗/形状、表面/复制层/可见）。
         */
        private const val RECORD_STRIDE = 16 * 4
        /** U4 起绘制记录多一段（逐片外观常量 + 复制片的采样中心）。 */
        private const val RECORD_STRIDE_WIDE = 16 * 5

/**
         * C10 的工作缓冲表头：Jacobi 的活动 tile 数（间接分发参数）、活动区域与上一帧包围盒，
         * 其后才是 tile 列表。std430 下 `ivec2 tiles[]` 落在 64 字节处。
         */
        private const val WORK_HEADER_BYTES = 64

        @Volatile private var storageBlocks = -1

        /**
         * C10 在 `peel-splat.comp` 里多用一个存储块（区域与包围盒），splat 因此要 5 个；
         * ES 3.1 只保证 4 个 compute 存储块、4 个绑定点，而本管线已经用到绑定点 7。
         * 目标设备实测都在 8 以上，不满足时整项退回整网格 dispatch，不去赌。
         */
        fun storageBlocksAvailable(): Boolean {
            if (storageBlocks < 0) {
                val value = IntArray(1)
                GLES30.glGetIntegerv(GLES31.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS, value, 0)
                while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { /* 清掉不认这一项的错误 */ }
                storageBlocks = value[0]
            }
            return storageBlocks >= 5
        }

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

        /** 排空在途采样栅栏的上限；只有交接与收尾走这条，正常一批只有几毫秒。 */
        private const val FENCE_TIMEOUT_NANOS = 200_000_000L
        private fun checkGl(stage: String) { check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "微片 $stage GL 错误" } }

        fun fromSpec(assets: AssetManager, width: Int, height: Int, density: Float, spec: ParticleDismissSpec,
                     sourcePixels: IntArray? = null): Input {
            val bitmap = spec.snapshot
            val pixels = sourcePixels ?: IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
            return fromPixels(assets, width, height, density, pixels, bitmap.width, bitmap.height,
                spec.originXPx, spec.originYPx, spec.virtualTouchXPx, spec.virtualTouchYPx,
                spec.hashSeed, spec.touchGap, spec.touchStrength, bitmap, spec.materialAreaScale,
                spec.releaseOrigin, spec.sink)
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
                       materialAreaScale: Float = 1f, releaseOrigin: ParticleReleaseOrigin? = null,
                       sink: ParticleSink? = null): Input {
            // 720 逻辑像素对应 384 dp 手机宽度，微片约 1.25 dp；横屏和平板也保持相同界面粒径。
            val scale = (density / ParticleMicroflakeModel.DP_UNITS.toFloat()).coerceAtLeast(.5f)
            val cardWidth = pixelWidth / scale; val cardHeight = pixelHeight / scale
            val dx = virtualTouchXPx - originXPx - pixelWidth / 2f
            val dy = virtualTouchYPx - originYPx - pixelHeight / 2f
            val direction = Math.toDegrees(atan2(-dy, dx).toDouble()).toFloat()
            val resources = sharedResources(assets)
            val rules = resources.rules.withMaterialArea(materialAreaScale)
            val materials = ParticleMicroflakeModel.build(cardWidth, cardHeight, pixels, pixelWidth,
                pixelHeight, direction, hashSeed.toLong(), rules, releaseOrigin)
            // 吸入终点换到材料坐标（以快照左上角为原点、同一缩放）；寿命按到达终点重写
            val sinkGeometry = sink?.toMaterial(originXPx, originYPx, scale)
            if (sinkGeometry != null) ParticleSink.shape(materials.values, sinkGeometry, min(cardWidth, cardHeight))
            return Input(width / scale, height / scale, cardWidth, cardHeight, originXPx / scale,
                originYPx / scale, direction, bitmap, materials,
                resources.guide, rules, pixels, touchGap ?: rules.number("touch_gap_default"),
                resources.confidence, touchStrength ?: .5f, sinkGeometry)
        }
    }
}
