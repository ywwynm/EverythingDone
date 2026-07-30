package com.ywwynm.everythingdone.views.recording.fablesol

import android.opengl.GLES30

/**
 * 应用自有 P010 路径的**呈现中间面**（D153）。
 *
 * 格式专属输出变换的结果先写进这里，亮度、色度与逐帧统计都读同一份，最后只在生成 P010
 * 码值时量化一次。此前的 `FP16 → RGB10_A2 → P010` 要量化两次，而第一次量化对统计与编码
 * 输入是共同的系统误差。
 *
 * 首选 `RGBA16F`。**不能仅凭 GLES 版本或扩展字符串判定它可用**：必须真的建出附件并检查
 * framebuffer completeness（D153）。建不成就无提示地退到 `RGB10_A2` 兼容中间面，继续导出
 * 用户选择的原格式——该后备不得引起格式切换、导出失败或整段重编码。
 */
internal class FableSolExportPresentTarget private constructor(
    val textureId: Int,
    val framebufferId: Int,
    /** true = `RGBA16F` 高精度中间面；false = `RGB10_A2` 兼容中间面。 */
    val highPrecision: Boolean,
    val widthPx: Int,
    val heightPx: Int
) {

    /** 设备诊断与导出完成信息里的稳定标识；不本地化，展示时再翻。 */
    val stableLabel: String
        get() = if (highPrecision) LABEL_HIGH_PRECISION else LABEL_COMPATIBLE

    fun release() {
        if (framebufferId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
        }
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }

    companion object {

        const val LABEL_HIGH_PRECISION = "RGBA16F"
        const val LABEL_COMPATIBLE = "RGB10_A2"

        /**
         * 正式渲染开始**之前**选定实际路径，整段视频不中途换面。
         *
         * @throws IllegalStateException 两种中间面都建不出来——此时这条候选真的不可用，
         *   交由候选阶梯换下一档（同格式 Surface 输入优先）。
         */
        fun create(widthPx: Int, heightPx: Int): FableSolExportPresentTarget =
            attempt(widthPx, heightPx, GLES30.GL_RGBA16F, highPrecision = true)
                ?: attempt(widthPx, heightPx, GLES30.GL_RGB10_A2, highPrecision = false)
                ?: error("No renderable presentation target for ${widthPx}x$heightPx")

        /**
         * 只要 `RGBA16F`，不接受 `RGB10_A2` 兼容中间面。
         *
         * 全片亮度预分析读的是**未经 OETF 的线性值**，最高可达 HDR 强度（9.6）；归一化定点
         * 面装不下 `>1.0`，拿它统计会把所有高光一律截成 1.0，MaxCLL 直接失真。建不出来时
         * 按 D90 的理论回退处理，而不是发布一个错误的实测值。
         *
         * @return null 表示本机没有 FP16 颜色缓冲。
         */
        fun createHighPrecision(widthPx: Int, heightPx: Int): FableSolExportPresentTarget? =
            attempt(widthPx, heightPx, GLES30.GL_RGBA16F, highPrecision = true)

        private fun attempt(
            widthPx: Int,
            heightPx: Int,
            internalFormat: Int,
            highPrecision: Boolean
        ): FableSolExportPresentTarget? {
            // 先清掉此前遗留的错误码，否则下面那次 glGetError 会报到别人头上。
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) Unit

            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            val texture = textures[0]
            if (texture == 0) return null
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, internalFormat, widthPx, heightPx)
            // 转换阶段一律 texelFetch，取的是整数坐标上的原样本；线性过滤既用不上，
            // 半浮点颜色缓冲的可过滤性也不是所有实现都保证。
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
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
                return null
            }

            val framebuffers = IntArray(1)
            GLES30.glGenFramebuffers(1, framebuffers, 0)
            val framebuffer = framebuffers[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                texture,
                0
            )
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE ||
                GLES30.glGetError() != GLES30.GL_NO_ERROR
            ) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
                GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
                return null
            }
            return FableSolExportPresentTarget(
                textureId = texture,
                framebufferId = framebuffer,
                highPrecision = highPrecision,
                widthPx = widthPx,
                heightPx = heightPx
            )
        }
    }
}
