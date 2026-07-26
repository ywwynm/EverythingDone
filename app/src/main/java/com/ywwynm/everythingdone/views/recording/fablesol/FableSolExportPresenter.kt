package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils

/**
 * 导出的最终呈现：把场景纹理放进画框、叠上时钟、套传递函数，写进编码器 input surface。
 *
 * 与屏上的 present 完全分离（fablesol-video-export D4）：独立 program、独立 shader，
 * 逐帧关键路径不为导出背任何分支。
 */
internal class FableSolExportPresenter(
    assets: AssetManager,
    private val plan: FableSolExportPlan,
    private val clock: FableSolExportClock,
    /** 0 = BT.709 SDR；1 = BT.2020 HLG。 */
    private val transfer: Int,
    /** 8-bit 编码档位必须重新启用抖动（D9）。 */
    private val dither: Boolean
) : ScenePresenter {

    private val program = FableSolGlProgram(
        assets,
        "fablesol/glsl/fullscreen.vert",
        "fablesol/glsl/export_present.frag"
    )
    private val vertexArrayId: Int
    private var clockTextureId = 0
    private var clockUploadedWidth = 0
    private var clockUploadedHeight = 0
    private val backdrop = colorToFloats(plan.backdropColor)
    private val rim = colorToFloats(plan.rimColor)

    /** 本帧的音频时间；驱动循环在 render 之前写入。 */
    @Volatile var elapsedMs: Long = 0L

    init {
        val arrays = IntArray(1)
        GLES30.glGenVertexArrays(1, arrays, 0)
        vertexArrayId = arrays[0]
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        clockTextureId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, clockTextureId)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    override fun present(
        sceneTextureId: Int,
        sceneWidthPx: Int,
        sceneHeightPx: Int,
        sceneLinear: Boolean,
        hdrHeadroom: Float
    ) {
        uploadClock()

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, plan.canvasWidthPx, plan.canvasHeightPx)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(vertexArrayId)
        program.use()

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTextureId)
        GLES30.glUniform1i(program.uniform("uScene"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, clockTextureId)
        GLES30.glUniform1i(program.uniform("uClock"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        val cardOriginX = plan.cardOriginXPx.toFloat()
        val cardOriginY = plan.cardOriginYPx.toFloat()
        GLES30.glUniform2f(
            program.uniform("uCardOriginPx"), cardOriginX, cardOriginY
        )
        GLES30.glUniform2f(
            program.uniform("uCardSizePx"),
            plan.cardWidthPx.toFloat(),
            plan.cardHeightPx.toFloat()
        )
        GLES30.glUniform1f(program.uniform("uCornerRadiusPx"), plan.cornerRadiusPx)
        GLES30.glUniform3fv(program.uniform("uBackdropColor"), 1, backdrop, 0)
        GLES30.glUniform1f(program.uniform("uShadowOffsetPx"), plan.shadowOffsetPx)
        GLES30.glUniform1f(program.uniform("uShadowRadiusPx"), plan.shadowRadiusPx)
        GLES30.glUniform1f(program.uniform("uShadowAlpha"), plan.shadowAlpha)
        GLES30.glUniform3fv(program.uniform("uRimColor"), 1, rim, 0)
        GLES30.glUniform1f(program.uniform("uRimAlpha"), plan.rimAlpha)
        GLES30.glUniform1f(program.uniform("uRimWidthPx"), plan.rimWidthPx)

        // 时钟矩形：布局按屏幕坐标（y 向下）给出，这里换算成 GL 的 y 向上。
        val clockLeft = cardOriginX + plan.clockLeftPx
        val clockBottom = cardOriginY + plan.cardHeightPx -
            plan.clockTopPx - plan.clockHeightPx
        GLES30.glUniform4f(
            program.uniform("uClockRectPx"),
            clockLeft,
            clockBottom,
            plan.clockWidthPx.toFloat(),
            plan.clockHeightPx.toFloat()
        )
        GLES30.glUniform1f(program.uniform("uClockAlpha"), clock.alphaAt(elapsedMs))

        GLES30.glUniform1i(program.uniform("uSceneLinear"), if (sceneLinear) 1 else 0)
        GLES30.glUniform1f(program.uniform("uHdrHeadroom"), hdrHeadroom)
        GLES30.glUniform1i(program.uniform("uTransfer"), transfer)
        GLES30.glUniform1i(program.uniform("uDither"), if (dither) 1 else 0)
        GLES30.glUniform1f(program.uniform("uHlgDiffuseScene"), HLG_DIFFUSE_WHITE_SCENE)
        GLES30.glUniform1f(program.uniform("uHlgKnee"), HLG_SHOULDER_KNEE)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun uploadClock() {
        val bitmap: Bitmap = clock.bitmapAt(elapsedMs)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, clockTextureId)
        if (clockUploadedWidth != bitmap.width || clockUploadedHeight != bitmap.height) {
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            clockUploadedWidth = bitmap.width
            clockUploadedHeight = bitmap.height
        } else {
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun release() {
        program.release()
        if (clockTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(clockTextureId), 0)
            clockTextureId = 0
        }
        if (vertexArrayId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArrayId), 0)
        }
    }

    private fun colorToFloats(color: Int): FloatArray = floatArrayOf(
        ((color shr 16) and 0xff) / 255f,
        ((color shr 8) and 0xff) / 255f,
        (color and 0xff) / 255f
    )

    companion object {
        const val TRANSFER_BT709 = 0
        const val TRANSFER_HLG = 1

        /**
         * BT.2408：HLG 的漫反射白落在信号 0.75，对应场景线性 0.26497。
         * 于是 SDR 参考白之上只有 1/0.26497 ≈ 3.77 倍余量，而用户 HDR 强度上限是 9.6——
         * 超出部分由 shader 里的软肩压缩承接，而不是硬钳成死白平顶。
         */
        const val HLG_DIFFUSE_WHITE_SCENE = 0.26497f
        /** 该倍数以下完全线性，之上进入软肩。 */
        const val HLG_SHOULDER_KNEE = 2.0f
    }
}
