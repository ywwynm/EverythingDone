package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.res.AssetManager
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把导出画面从 GL 端交到编码器的**字节缓冲**输入，而不是 input surface。
 *
 * 为什么需要这条路：HDR10+ 的动态元数据只能逐帧通过
 * `MediaCodec.PARAMETER_KEY_HDR10_PLUS_INFO` 提供，而该参数在 **surface 输入模式下被系统
 * 明确禁止**（文档原文如此，AOSP CTS 的 HDR10+ 编码用例走的也正是字节缓冲）。换到字节缓冲
 * 之后 RGB→YUV 就不再由编码器代劳，得我们自己交出 P010——这个类干的就是这件事。
 *
 * 三趟 GPU：
 *
 * 1. **呈现**——[presentFramebufferId] 交给 [FableSolExportPresenter]，画进 RGB10_A2 纹理；
 * 2. **转换**——两个片元着色器分别产出 P010 的 Y 平面与交错 CbCr 平面；
 * 3. **统计**——归约到 32×32，供 ST 2094-40 的 maxscl / 均值 / 分位点使用。
 *
 * 转换的输出目标是 **RGBA8** 而不是 16 位整数纹理：ES 3.0 只保证
 * `GL_RGBA` + `GL_UNSIGNED_BYTE` 这一组 glReadPixels 组合可用，整数纹理的回读格式是实现
 * 自定的。因此一个 RGBA8 texel 装两个 16 位样本，回读的字节序直接就是 P010。
 */
internal class FableSolExportP010Bridge(
    assets: AssetManager,
    private val widthPx: Int,
    private val heightPx: Int
) {

    private val lumaProgram = FableSolGlProgram(
        assets, FULLSCREEN_VERT, "fablesol/glsl/p010_luma.frag"
    )
    private val chromaProgram = FableSolGlProgram(
        assets, FULLSCREEN_VERT, "fablesol/glsl/p010_chroma.frag"
    )
    private val statsProgram = FableSolGlProgram(
        assets, FULLSCREEN_VERT, "fablesol/glsl/p010_stats.frag"
    )

    private val vertexArrayId: Int
    private val presentTexture: Int
    private val presentFramebuffer: Int
    private val lumaTarget = Target(widthPx / 2, heightPx)
    private val chromaTarget = Target(widthPx / 2, heightPx / 2)
    private val statsTarget = Target(
        FableSolExportHdr10PlusMetadata.STATS_SIZE,
        FableSolExportHdr10PlusMetadata.STATS_SIZE
    )

    private val lumaBytes = ByteBuffer
        .allocateDirect(lumaTarget.widthPx * lumaTarget.heightPx * 4)
        .order(ByteOrder.nativeOrder())
    private val chromaBytes = ByteBuffer
        .allocateDirect(chromaTarget.widthPx * chromaTarget.heightPx * 4)
        .order(ByteOrder.nativeOrder())
    private val statsBytes = ByteArray(statsTarget.widthPx * statsTarget.heightPx * 4)
    private val statsBuffer = ByteBuffer
        .allocateDirect(statsBytes.size)
        .order(ByteOrder.nativeOrder())

    /** 呈现阶段要绑定的 framebuffer；画进来的是 PQ 编码后的 RGB10_A2。 */
    val presentFramebufferId: Int

    init {
        val arrays = IntArray(1)
        GLES30.glGenVertexArrays(1, arrays, 0)
        vertexArrayId = arrays[0]

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        presentTexture = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, presentTexture)
        GLES30.glTexStorage2D(
            GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGB10_A2, widthPx, heightPx
        )
        applyClampNearest()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        val framebuffers = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffers, 0)
        presentFramebuffer = framebuffers[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, presentFramebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            presentTexture,
            0
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "RGB10_A2 present target incomplete: 0x${Integer.toHexString(status)}"
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        presentFramebufferId = presentFramebuffer
    }

    /**
     * 跑完转换与统计三趟。调用前呈现阶段必须已经画进 [presentFramebufferId]。
     */
    fun convert() {
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(vertexArrayId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, presentTexture)

        drawInto(lumaTarget, lumaProgram) { }
        drawInto(chromaTarget, chromaProgram) { }
        drawInto(statsTarget, statsProgram) { program ->
            // 块尺寸向上取整，最后一块可能越界——着色器里按 uWidth/uHeight 提前 break。
            val blockW = (widthPx + statsTarget.widthPx - 1) / statsTarget.widthPx
            val blockH = (heightPx + statsTarget.heightPx - 1) / statsTarget.heightPx
            GLES30.glUniform1i(program.uniform("uBlockW"), blockW)
            GLES30.glUniform1i(program.uniform("uBlockH"), blockH)
        }

        readTarget(lumaTarget, lumaBytes)
        readTarget(chromaTarget, chromaBytes)
        readTarget(statsTarget, statsBuffer)
        statsBuffer.rewind()
        statsBuffer.get(statsBytes)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /**
     * 把转换结果按编码器要求的行距写进它的输入缓冲。
     *
     * 不直接 glReadPixels 进编码器缓冲：它的行距未必等于宽度，而 glReadPixels 只能按紧密
     * 排布写。中间多一次逐行拷贝，换来的是行距处理只有一个地方要对。
     *
     * @return 实际写入的字节数。
     */
    fun writeInto(destination: ByteBuffer, stride: Int, sliceHeight: Int): Int {
        val lumaRowBytes = widthPx * 2
        val chromaRows = heightPx / 2
        destination.clear()
        for (row in 0 until heightPx) {
            destination.position(row * stride)
            lumaBytes.position(row * lumaRowBytes)
            lumaBytes.limit(lumaBytes.position() + lumaRowBytes)
            destination.put(lumaBytes)
        }
        lumaBytes.clear()
        val chromaBase = stride * sliceHeight
        for (row in 0 until chromaRows) {
            destination.position(chromaBase + row * stride)
            chromaBytes.position(row * lumaRowBytes)
            chromaBytes.limit(chromaBytes.position() + lumaRowBytes)
            destination.put(chromaBytes)
        }
        chromaBytes.clear()
        // 交给编码器的长度必须是**规范的整帧长度**（行距 × 平面高 × 3/2），不是"我实际写到
        // 哪儿"——有些实现会按这个数校验，短一截就直接判非法。
        val written = stride * sliceHeight * 3 / 2
        destination.position(0)
        destination.limit(minOf(written, destination.capacity()))
        return destination.limit()
    }

    fun stats(): FableSolHdr10PlusStats = FableSolExportHdr10PlusMetadata.measure(
        statsBytes, statsTarget.widthPx * statsTarget.heightPx
    )

    fun release() {
        lumaProgram.release()
        chromaProgram.release()
        statsProgram.release()
        lumaTarget.release()
        chromaTarget.release()
        statsTarget.release()
        if (presentFramebuffer != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(presentFramebuffer), 0)
        }
        if (presentTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(presentTexture), 0)
        }
        if (vertexArrayId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArrayId), 0)
        }
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

    /** 一个 RGBA8 的离屏目标；三趟转换/统计各用一个。 */
    private inner class Target(val widthPx: Int, val heightPx: Int) {

        private var textureId = 0
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
