package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.res.AssetManager
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把导出画面从 GL 端交到编码器的**字节缓冲**输入，而不是 input surface。
 *
 * 这条路最初只为 HDR10+ 存在（它的动态元数据只能逐帧通过
 * `MediaCodec.PARAMETER_KEY_HDR10_PLUS_INFO` 提供，而该参数在 surface 输入模式下被系统明确
 * 禁止）。D158 之后它成为**所有 10-bit 导出的首选输入路径**：色度位置、降采样相位、闭环
 * 亮度修正与码值量化只有握在应用手里，同一份画面在不同设备上才会得到同一种转换。
 *
 * 每帧三趟 GPU，顺序不能换（D157 第 4 条）：
 *
 * 1. **呈现**——[presentFramebufferId] 交给 [FableSolExportPresenter]，画进高精度中间面；
 * 2. **色度**——有相位的低通 + 蓝噪声阈值量化，产出本帧真正写出去的 Cb/Cr；
 * 3. **亮度**——读上一趟的量化色度做闭环修正，再量化 Y′；
 * 4. **统计**（仅 HDR10+）——归约供 ST 2094-40 使用，读的是抖动前的呈现中间面。
 *
 * 转换的输出目标是 **RGBA8** 而不是 16 位整数纹理：ES 3.0 只保证
 * `GL_RGBA` + `GL_UNSIGNED_BYTE` 这一组 glReadPixels 组合可用，整数纹理的回读格式是实现
 * 自定的。因此一个 RGBA8 texel 装两个 16 位样本，回读的字节序直接就是 P010。
 */
internal class FableSolExportP010Bridge(
    assets: AssetManager,
    private val widthPx: Int,
    private val heightPx: Int,
    /** 本次输出定义：矩阵、传递函数与闭环参考显示成套出现（D158 第 3 条）。 */
    private val definition: FableSolExportP010Math.ColorDefinition,
    /** 短探测从实际码流读到的色度位置；未声明时是 Type 0 兼容语义（D154、D170）。 */
    private val chromaSiting: FableSolExportP010Math.ChromaSiting,
    /** 本次实际信号范围的码值边界；super-white 在 D139～D140 验证通过后放宽上界。 */
    private val signalRange: FableSolExportP010Math.SignalRange =
        FableSolExportP010Math.SignalRange.NOMINAL,
    /**
     * 能力探测需要验证 HDR10+ 统计后端时为 true；正式导出的场景统计已在预分析完成，
     * 因而传 false，避免编码阶段再做一次全画布归约与回读（D177）。
     */
    private val collectStats: Boolean,
    /** 漫反射白（尼特）：统计要把场景归一化值换算到载荷的 10000 尼特归一化域。 */
    private val diffuseWhiteNits: Double = FableSolExportTransfer.SDR_WHITE_NITS,
    /** null 表示资源不可用：退回普通四舍五入并继续同一格式导出（D157 第 6 条）。 */
    blueNoise: FableSolExportBlueNoise?
) {

    private val lumaProgram = FableSolGlProgram(
        assets, FULLSCREEN_VERT, "fablesol/glsl/p010_luma.frag"
    )
    private val chromaProgram = FableSolGlProgram(
        assets, FULLSCREEN_VERT, "fablesol/glsl/p010_chroma.frag"
    )
    /**
     * HDR10+ 的逐像素统计后端（D104）。
     *
     * 它读的是呈现趟的**第二个附件**——同一次合成的线性 BT.2020，不是 PQ 编码后的第一个
     * 附件。ST 2094-40 的每一项统计都定义在线性域（D102、D103）；从 PQ 反解回线性会在
     * 1000 尼特附近损失约 4 尼特，远粗于 0.1 尼特的载荷网格。
     */
    private val statsBackend = if (collectStats) {
        FableSolExportHdr10PlusStatsBackend(assets, widthPx, heightPx, diffuseWhiteNits)
    } else {
        null
    }

    private val vertexArrayId: Int
    private val presentTarget = FableSolExportPresentTarget.create(widthPx, heightPx)
    /**
     * 呈现趟的第二个附件：同一次合成的线性 BT.2020，只有 HDR10+ 需要。
     *
     * 走 MRT 而不是把呈现再画一遍：两遍之间任何一个 uniform 不同，统计描述的就不是编码进去
     * 的那张画面，而这种不一致在产物里看不出来。
     */
    private val linearTarget = if (collectStats) {
        FableSolExportPresentTarget.createHighPrecision(widthPx, heightPx)
    } else {
        null
    }
    private val lumaTarget = Target(widthPx / 2, heightPx)
    private val chromaTarget = Target(widthPx / 2, heightPx / 2)

    /**
     * 阈值纹理：蓝噪声表，或资源不可用时一张 1×1 的中性占位。
     *
     * 占位不是装饰——`usampler2D` 绑到不完整纹理是未定义行为，即便那条分支不会执行。
     * [noiseEnabled] 为假时着色器走固定 0.5 阈值，也就是普通四舍五入。
     *
     * 启用判据必须是"64×64 表**本身**上传成功"（[blueNoiseTextureId] 非空），不能只看
     * [noiseTextureId] 非零：`upload()` 因 GL 错误返回 0 而占位上传成功时（残留错误标志被
     * `upload()` 消费掉正是这种组合），旧判据仍为 true，shader 会对 1×1 纹理做 (0..63)²
     * 的越界 `texelFetch`——未定义行为，而不是 D157 第 6 条/D162 第 5 条要求的退回普通
     * 四舍五入。
     */
    private val blueNoiseTextureId = blueNoise?.upload()?.takeIf { it != 0 }
    private val noiseTextureId = blueNoiseTextureId
        ?: FableSolExportBlueNoise.uploadNeutral()
    private val noiseEnabled = blueNoiseTextureId != null

    // 逐帧缓冲只在这里各分配一次，尺寸固定：一帧 P010 的紧密字节数，与画布无关地有界。
    private val lumaBytes = ByteBuffer
        .allocateDirect(lumaTarget.widthPx * lumaTarget.heightPx * 4)
        .order(ByteOrder.nativeOrder())
    private val chromaBytes = ByteBuffer
        .allocateDirect(chromaTarget.widthPx * chromaTarget.heightPx * 4)
        .order(ByteOrder.nativeOrder())

    /** 呈现阶段要绑定的 framebuffer；画进来的是格式专属输出变换后的 R′G′B′。 */
    val presentFramebufferId: Int get() = presentTarget.framebufferId

    /** 本次统计后端的稳定标识；诊断如实显示走的是 compute 还是回读（D104）。 */
    val statsBackendLabel: String? get() = statsBackend?.stableLabel

    /** 本次用的是高精度中间面还是 10-bit 兼容中间面（D153）；诊断如实记录。 */
    val presentTargetLabel: String get() = presentTarget.stableLabel

    /**
     * 呈现中间面是否为 `RGBA16F`。HLG 扩展信号范围要求为真：`RGB10_A2` 兼容面在名义峰值
     * 截断，装不下 100% 以上的 super-white 信号，计划必须降为名义范围（D134、D135、D153）。
     */
    val presentHighPrecision: Boolean get() = presentTarget.highPrecision

    /** 本次实际使用的色度位置；完成信息与诊断读这一份。 */
    val chromaSitingLabel: String get() = chromaSiting.stableId

    init {
        val arrays = IntArray(1)
        GLES30.glGenVertexArrays(1, arrays, 0)
        vertexArrayId = arrays[0]
        // HDR10+ 的呈现趟走 MRT：附件 0 是 PQ 编码后的 R′G′B′（给 P010），附件 1 是同一次
        // 合成的线性 BT.2020（给统计）。附件 1 建不出来时统计通路不可用，按 D104 判候选失败。
        linearTarget?.let { linear ->
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, presentTarget.framebufferId)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT1,
                GLES30.GL_TEXTURE_2D,
                linear.textureId,
                0
            )
            GLES30.glDrawBuffers(
                2,
                intArrayOf(GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1),
                0
            )
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "HDR10+ linear attachment incomplete: 0x${Integer.toHexString(status)}"
            }
        }
    }

    /**
     * 跑完色度、亮度与（可选的）统计。调用前呈现阶段必须已经画进 [presentFramebufferId]。
     */
    fun convert() {
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(vertexArrayId)
        bindSource()

        drawInto(chromaTarget, chromaProgram) { program ->
            applyColorUniforms(program)
            GLES30.glUniform2f(
                program.uniform("uChromaScale"),
                definition.cbScale.toFloat(),
                definition.crScale.toFloat()
            )
            GLES30.glUniform2f(
                program.uniform("uChromaPhase"),
                chromaSiting.horizontalPhase.toFloat(),
                chromaSiting.verticalPhase.toFloat()
            )
            GLES30.glUniform4f(
                program.uniform("uChromaCodeRange"),
                signalRange.cbMinCode.toFloat(),
                signalRange.cbMaxCode.toFloat(),
                signalRange.crMinCode.toFloat(),
                signalRange.crMaxCode.toFloat()
            )
            GLES30.glUniform2i(
                program.uniform("uNoisePhaseCb"),
                FableSolExportBlueNoise.Phase.CB.offsetX,
                FableSolExportBlueNoise.Phase.CB.offsetY
            )
            GLES30.glUniform2i(
                program.uniform("uNoisePhaseCr"),
                FableSolExportBlueNoise.Phase.CR.offsetX,
                FableSolExportBlueNoise.Phase.CR.offsetY
            )
        }

        // 亮度趟额外读上一趟写出的量化色度：闭环必须基于真正写出去的码值。
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, chromaTarget.textureId)
        drawInto(lumaTarget, lumaProgram) { program ->
            applyColorUniforms(program)
            GLES30.glUniform1i(program.uniform("uChroma"), 1)
            GLES30.glUniform1i(program.uniform("uTransfer"), definition.transfer.shaderCode)
            GLES30.glUniform2f(
                program.uniform("uChromaScale"),
                definition.cbScale.toFloat(),
                definition.crScale.toFloat()
            )
            GLES30.glUniform2f(
                program.uniform("uChromaPhase"),
                chromaSiting.horizontalPhase.toFloat(),
                chromaSiting.verticalPhase.toFloat()
            )
            GLES30.glUniform2f(
                program.uniform("uLumaCodeRange"),
                signalRange.lumaMinCode.toFloat(),
                signalRange.lumaMaxCode.toFloat()
            )
            GLES30.glUniform1f(
                program.uniform("uMaxLumaCorrection"),
                (FableSolExportP010Math.MAX_LUMA_CORRECTION_CODES /
                    FableSolExportP010Math.LUMA_RANGE).toFloat()
            )
            GLES30.glUniform2i(
                program.uniform("uNoisePhaseLuma"),
                FableSolExportBlueNoise.Phase.LUMA.offsetX,
                FableSolExportBlueNoise.Phase.LUMA.offsetY
            )
        }

        // 回读之前先把纹理解绑：色度目标此刻既是别人的采样源、又要作为 glReadPixels 的读取
        // 目标，部分驱动对这种同时绑定很敏感。绘制已经全部提交，解绑不会影响结果。
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        readTarget(chromaTarget, chromaBytes)
        readTarget(lumaTarget, lumaBytes)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * 把转换结果按编码器实际排布写进它的输入缓冲。
     *
     * 不直接 glReadPixels 进编码器缓冲：它的行距未必等于宽度、色度平面未必紧跟亮度平面，
     * 而 glReadPixels 只能按紧密排布写。中间多一次逐行拷贝，换来的是排布处理只有一个地方
     * 要对。
     *
     * @return 实际交给 `queueInputBuffer` 的长度。
     */
    fun writeInto(destination: ByteBuffer, layout: FableSolExportP010Layout): Int {
        check(destination.capacity() >= layout.requiredBytes) {
            "Encoder input buffer holds ${destination.capacity()} bytes, " +
                "P010 frame needs ${layout.requiredBytes}"
        }
        val rowBytes = widthPx * FableSolExportP010Layout.BYTES_PER_SAMPLE
        destination.clear()
        for (row in 0 until heightPx) {
            destination.position(layout.lumaOffset + row * layout.lumaRowStride)
            lumaBytes.position(row * rowBytes)
            lumaBytes.limit(lumaBytes.position() + rowBytes)
            destination.put(lumaBytes)
        }
        lumaBytes.clear()
        for (row in 0 until heightPx / 2) {
            destination.position(layout.chromaOffset + row * layout.chromaRowStride)
            chromaBytes.position(row * rowBytes)
            chromaBytes.limit(chromaBytes.position() + rowBytes)
            destination.put(chromaBytes)
        }
        chromaBytes.clear()
        // 交给编码器的长度必须是**规范的整帧长度**，不是"我实际写到哪儿"——有些实现会按
        // 这个数校验，短一截就直接判非法。
        destination.position(0)
        destination.limit(minOf(layout.frameBytes, destination.capacity()))
        return destination.limit()
    }

    /**
     * 当前呈现帧的 HDR10+ 原始统计（D101～D112、D169）。
     *
     * 它只用于能力探测或场景预分析，不得在正式编码循环中直接作为一个完整场景发送（D177）。
     * @return null 表示统计通路失败；调用方不得发布统计不完整的 HDR10+ 产物。
     */
    fun stats(): FableSolHdr10PlusStats? {
        val backend = statsBackend ?: return null
        val linear = linearTarget ?: return null
        return backend.measure(linear.textureId)
    }

    /** 统计通路的失败原因；非 null 即本候选不可用。 */
    val statsFailure: String? get() = statsBackend?.failure

    fun release() {
        lumaProgram.release()
        chromaProgram.release()
        statsBackend?.release()
        lumaTarget.release()
        chromaTarget.release()
        linearTarget?.release()
        presentTarget.release()
        if (noiseTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(noiseTextureId), 0)
        }
        if (vertexArrayId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArrayId), 0)
        }
    }

    private fun bindSource() {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, presentTarget.textureId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noiseTextureId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private inline fun drawInto(
        target: Target,
        program: FableSolGlProgram,
        extraUniforms: (FableSolGlProgram) -> Unit
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebufferId)
        GLES30.glViewport(0, 0, target.widthPx, target.heightPx)
        program.use()
        GLES30.glUniform1i(program.uniform("uSource"), 0)
        GLES30.glUniform1i(program.uniform("uWidth"), widthPx)
        GLES30.glUniform1i(program.uniform("uHeight"), heightPx)
        extraUniforms(program)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /** 颜色转换两趟共用的 uniform；统计趟没有颜色定义，不走这里。 */
    private fun applyColorUniforms(program: FableSolGlProgram) {
        GLES30.glUniform3f(
            program.uniform("uLumaWeights"),
            definition.kr.toFloat(),
            definition.kg.toFloat(),
            definition.kb.toFloat()
        )
        GLES30.glUniform1i(program.uniform("uNoise"), 2)
        GLES30.glUniform1i(program.uniform("uNoiseEnabled"), if (noiseEnabled) 1 else 0)
    }

    private fun readTarget(target: Target, destination: ByteBuffer) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebufferId)
        destination.clear()
        GLES30.glReadPixels(
            0, 0, target.widthPx, target.heightPx,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, destination
        )
        destination.clear()
    }

    private fun applyClampNearest() {
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE
        )
    }

    /** 一个 RGBA8 的离屏目标；色度、亮度与统计各用一个。 */
    private inner class Target(val widthPx: Int, val heightPx: Int) {

        var textureId = 0
            private set
        var framebufferId = 0
            private set

        init {
            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexStorage2D(
                GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, widthPx, heightPx
            )
            applyClampNearest()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

            val framebuffers = IntArray(1)
            GLES30.glGenFramebuffers(1, framebuffers, 0)
            framebufferId = framebuffers[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                textureId,
                0
            )
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "P010 target ${widthPx}x$heightPx incomplete: " +
                    "0x${Integer.toHexString(status)}"
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }

        fun release() {
            if (framebufferId != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
                framebufferId = 0
            }
            if (textureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
        }
    }

    companion object {
        private const val FULLSCREEN_VERT = "fablesol/glsl/fullscreen.vert"

        /** P010 一帧的紧密字节数：Y 平面 2 字节/样本，色度平面半宽半高、每组 4 字节。 */
        fun packedFrameBytes(widthPx: Int, heightPx: Int): Int = widthPx * heightPx * 3
    }
}
