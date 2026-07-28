package com.ywwynm.everythingdone.views.recording.fablesol

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.view.Surface

/**
 * 导出用的 EGL 会话：目标是 `MediaCodec.createInputSurface()`，不是任何窗口。
 *
 * 因此 HDR 与显示器无关（fablesol-video-export D5）——只取决于 EGL 是否广告
 * `EGL_EXT_gl_colorspace_bt2020_hlg`、以及 GL 能否渲染 `GL_RGBA16F`。
 */
internal class FableSolExportEgl(
    /**
     * 编码器的 input surface；**null 表示离屏**。
     *
     * HDR10+ 走字节缓冲输入，压根没有 input surface，但仍然需要一个 GL 上下文来渲染并做
     * RGB→P010 的转换，此时用一张 1×1 的 pbuffer 顶着——真正的画面画进
     * [FableSolExportP010Bridge] 自己的离屏 framebuffer，与这张表面无关。
     */
    codecSurface: Surface?,
    /** 给表面打哪一种色彩空间；建不起来直接抛，由调用方沿阶梯降级。 */
    transfer: FableSolExportTransfer,
    /**
     * true 时按 RGB10_A2 建表面。**它与 [hdr] 是两件事**：HEVC Main10 SDR 档同样需要
     * 10-bit 表面，否则画面先被量化到 8-bit 再交给 10-bit 编码器，既拿不到精度，又因为
     * 不算 8-bit 档而没启用抖动，色带反而更明显。
     */
    tenBit: Boolean = transfer.isHdr
) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

            val offscreen = codecSurface == null
            // 离屏那张 pbuffer 只是用来持有上下文，画面进的是自己的 RGB10_A2 framebuffer
            // ——FBO 附件的格式与 config 位深无关。此时再要求 10-bit config，只会在没有
            // 10-bit pbuffer config 的设备上白白建不起来。
            val config = chooseConfig(display, tenBit && !offscreen, offscreen)
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

            val colorSpace = transfer.eglColorSpace
            surface = if (codecSurface != null) {
                val attributes = if (colorSpace != null) {
                    intArrayOf(EGL_GL_COLORSPACE_KHR, colorSpace, EGL14.EGL_NONE)
                } else {
                    intArrayOf(EGL14.EGL_NONE)
                }
                EGL14.eglCreateWindowSurface(display, config, codecSurface, attributes, 0)
            } else {
                // 离屏只是为了有个当前上下文；1×1 就够，画面进的是自己的 framebuffer。
                // 这里不打色彩空间属性——传递函数由导出 shader 亲自编码，pbuffer 上再声明
                // 一次既无意义，还可能因为某些驱动不支持 pbuffer 的色彩空间而建不起来。
                EGL14.eglCreatePbufferSurface(
                    display,
                    config,
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                    0
                )
            }
            check(surface != EGL14.EGL_NO_SURFACE) {
                "eglCreateSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
            }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                "eglMakeCurrent failed"
            }
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    fun swapBuffers(presentationTimeNanos: Long): Boolean {
        EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeNanos)
        return EGL14.eglSwapBuffers(display, surface)
    }

    fun release() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(
            display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
        )
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        surface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
    }

    /**
     * 10-bit 表面的 config **不能只试一种组合**。
     *
     * 华为平板（Kirin / `OMX.hisi.video.encoder.hevc`）上原先那一种组合直接
     * `eglChooseConfig failed`，整机 HDR 因此全灭。所以这里改成一条阶梯逐档放宽。
     *
     * **但"退到不带 recordable 的那一档"是有真实代价的，此前这里的注释把它写成了"只是给驱动
     * 的一个提示"，那是错的。** `EGL_RECORDABLE_ANDROID` 决定分配出来的缓冲带不带视频编码器
     * 用途位；少了它，交给 `MediaCodec.createInputSurface()` 的缓冲编码器根本消费不了。三星
     * Z Fold4 上 10-bit 各档一律以编码器"已被释放"告终，而 8-bit 档走的是带 recordable 的
     * 那一档，一切正常（2026-07-28）。
     *
     * 因此真正该做的是**先把 config 找全**：`EGL_RECORDABLE_ANDROID` 是厂商扩展属性，部分驱动
     * 不把它纳入 `eglChooseConfig` 的匹配，却在 `eglGetConfigAttrib` 里如实回报。[pickConfig]
     * 在 `eglChooseConfig` 落空后会自己枚举全部 config 逐个核对。放宽的那两档保留，作为枚举
     * 也找不到时的最后退路——那种设备上这条通路本来就走不通，留着至少让真实编码探测去判定。
     */
    private fun chooseConfig(
        display: EGLDisplay,
        tenBit: Boolean,
        offscreen: Boolean
    ): EGLConfig {
        val tried = ArrayList<String>(4)
        for (variant in configVariants(tenBit)) {
            pickConfig(display, variant, offscreen)?.let { return it }
            tried += variant.label
        }
        error("eglChooseConfig failed (tenBit=$tenBit)：依次试过 " + tried.joinToString("、"))
    }

    private class ConfigVariant(
        val componentBits: Int,
        val alphaBits: Int,
        val recordable: Boolean,
        val label: String
    )

    companion object {

        /** 最近一次探测读到的 EGL 扩展串；只用于把"为什么不支持 HDR"如实显示给用户。 */
        @Volatile
        var eglExtensionDump: String = ""
            private set

        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private fun configVariants(tenBit: Boolean): List<ConfigVariant> = if (tenBit) {
            listOf(
                ConfigVariant(10, 2, true, "RGB10_A2+recordable"),
                ConfigVariant(10, 0, true, "RGB10+recordable"),
                ConfigVariant(10, 2, false, "RGB10_A2"),
                ConfigVariant(10, 0, false, "RGB10")
            )
        } else {
            listOf(
                ConfigVariant(8, 8, true, "RGBA8+recordable"),
                ConfigVariant(8, 0, true, "RGB8+recordable"),
                ConfigVariant(8, 8, false, "RGBA8")
            )
        }

        private fun pickConfig(
            display: EGLDisplay,
            variant: ConfigVariant,
            offscreen: Boolean = false
        ): EGLConfig? {
            val attributes = ArrayList<Int>(16)
            attributes += listOf(
                EGL14.EGL_SURFACE_TYPE,
                if (offscreen) EGL14.EGL_PBUFFER_BIT else EGL14.EGL_WINDOW_BIT
            )
            attributes += listOf(EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR)
            attributes += listOf(EGL14.EGL_RED_SIZE, variant.componentBits)
            attributes += listOf(EGL14.EGL_GREEN_SIZE, variant.componentBits)
            attributes += listOf(EGL14.EGL_BLUE_SIZE, variant.componentBits)
            if (variant.alphaBits > 0) {
                attributes += listOf(EGL14.EGL_ALPHA_SIZE, variant.alphaBits)
            }
            if (variant.recordable && !offscreen) {
                attributes += listOf(EGL_RECORDABLE_ANDROID, 1)
            }
            attributes += EGL14.EGL_NONE
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val ok = try {
                EGL14.eglChooseConfig(
                    display, attributes.toIntArray(), 0, configs, 0, 1, count, 0
                ) && count[0] > 0
            } catch (ignored: Throwable) {
                false
            }
            // **`eglChooseConfig` 匹配不到不等于这台机器没有。** `EGL_RECORDABLE_ANDROID` 是
            // 厂商扩展属性，部分驱动不把它纳入 eglChooseConfig 的匹配，却在
            // eglGetConfigAttrib 里如实回报。这时自己枚举全部 config 逐个核对才拿得到。
            //
            // 这一步对本项目是要害：recordable 不是"给驱动的提示"，它决定分配出来的缓冲带不
            // 带视频编码器用途位。少了它，交给 MediaCodec 输入表面的缓冲编码器根本消费不了
            // ——三星 Z Fold4 上 10-bit 档一律以编码器"已被释放"告终，而 8-bit 档（走的是带
            // recordable 的那一档）一切正常（2026-07-28）。
            return if (ok) configs[0] else enumerateConfig(display, variant, offscreen)
        }

        /** 枚举全部 config 并逐个核对，绕开 eglChooseConfig 对厂商属性的匹配差异。 */
        private fun enumerateConfig(
            display: EGLDisplay,
            variant: ConfigVariant,
            offscreen: Boolean
        ): EGLConfig? = try {
            allConfigs(display).firstOrNull { matches(display, it, variant, offscreen) }
        } catch (ignored: Throwable) {
            null
        }

        private fun allConfigs(display: EGLDisplay): List<EGLConfig> {
            val total = IntArray(1)
            if (!EGL14.eglGetConfigs(display, null, 0, 0, total, 0) || total[0] <= 0) {
                return emptyList()
            }
            val configs = arrayOfNulls<EGLConfig>(total[0])
            val count = IntArray(1)
            if (!EGL14.eglGetConfigs(display, configs, 0, total[0], count, 0)) return emptyList()
            return configs.take(count[0]).filterNotNull()
        }

        private fun attribute(display: EGLDisplay, config: EGLConfig, key: Int): Int? {
            val value = IntArray(1)
            return if (EGL14.eglGetConfigAttrib(display, config, key, value, 0)) value[0] else null
        }

        private fun matches(
            display: EGLDisplay,
            config: EGLConfig,
            variant: ConfigVariant,
            offscreen: Boolean
        ): Boolean {
            val surfaceBit = if (offscreen) EGL14.EGL_PBUFFER_BIT else EGL14.EGL_WINDOW_BIT
            val surfaceType = attribute(display, config, EGL14.EGL_SURFACE_TYPE) ?: return false
            if (surfaceType and surfaceBit == 0) return false
            val renderable = attribute(display, config, EGL14.EGL_RENDERABLE_TYPE) ?: return false
            if (renderable and EGL_OPENGL_ES3_BIT_KHR == 0) return false
            for (channel in intArrayOf(
                EGL14.EGL_RED_SIZE, EGL14.EGL_GREEN_SIZE, EGL14.EGL_BLUE_SIZE
            )) {
                if (attribute(display, config, channel) != variant.componentBits) return false
            }
            val alpha = attribute(display, config, EGL14.EGL_ALPHA_SIZE) ?: return false
            if (alpha < variant.alphaBits) return false
            if (variant.recordable && !offscreen) {
                // 读不到这个属性就当作没有：宁可退到下一档，也不要拿一个不确定的 config 去
                // 喂编码器。
                if (attribute(display, config, EGL_RECORDABLE_ANDROID) != 1) return false
            }
            return true
        }

        /** 这台机器究竟有没有 10-bit 窗口 config，以及要放宽到哪一档才拿得到。 */
        private fun tenBitWindowConfigLabel(display: EGLDisplay): String? =
            configVariants(tenBit = true).firstOrNull { pickConfig(display, it) != null }?.label

        /**
         * 10-bit 窗口 config 的清点：一共几个、其中几个带 `EGL_RECORDABLE_ANDROID`。
         *
         * 这两个数直接决定 10-bit 编码在这台机器上成不成立，比"退到了哪一档"更有说服力。
         */
        private fun tenBitConfigCensus(display: EGLDisplay): Pair<Int, Int> = try {
            val tenBit = allConfigs(display).filter { config ->
                val surfaceType = attribute(display, config, EGL14.EGL_SURFACE_TYPE) ?: 0
                surfaceType and EGL14.EGL_WINDOW_BIT != 0 &&
                    attribute(display, config, EGL14.EGL_RED_SIZE) == 10
            }
            tenBit.size to tenBit.count {
                attribute(display, it, EGL_RECORDABLE_ANDROID) == 1
            }
        } catch (ignored: Throwable) {
            0 to 0
        }
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EXTENSION_COLORSPACE = "EGL_KHR_gl_colorspace"
        private const val GL_EXT_COLOR_BUFFER_HALF_FLOAT = "GL_EXT_color_buffer_half_float"
        private const val GL_EXT_COLOR_BUFFER_FLOAT = "GL_EXT_color_buffer_float"

        /**
         * 建链之前先探一次能力：EGL 是否支持 BT.2020 HLG 色彩空间，GL 能否渲染
         * `GL_RGBA16F`。用一次性的 pbuffer 上下文完成，探完立刻拆掉，不影响后续建链。
         */
        fun probe(): Capability {
            var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            var context: EGLContext = EGL14.EGL_NO_CONTEXT
            var surface: EGLSurface = EGL14.EGL_NO_SURFACE
            try {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (display == EGL14.EGL_NO_DISPLAY) return Capability(false, false, false)
                val version = IntArray(2)
                if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                    return Capability(false, false, false)
                }
                val eglExtensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: ""
                val hasColorSpace = eglExtensions.contains(EXTENSION_COLORSPACE)
                // 两种传递函数分别判定。此前只认 HLG 一个扩展，只有 PQ 的设备（三星那一系
                // 的 HDR10+ 生态正是 PQ）会被整条 HDR 通路挡在门外。
                val hlg = hasColorSpace &&
                    eglExtensions.contains(FableSolExportTransfer.EXTENSION_BT2020_HLG)
                val pq = hasColorSpace &&
                    eglExtensions.contains(FableSolExportTransfer.EXTENSION_BT2020_PQ)
                eglExtensionDump = eglExtensions
                // 扩展串说"有 PQ"不代表建得起 10-bit 表面：华为平板两者都广告，却在
                // eglChooseConfig 这一步整机 HDR 全灭。所以把它单独探出来如实显示。
                val tenBit = tenBitWindowConfigLabel(display)
                val census = tenBitConfigCensus(display)

                val attributes = intArrayOf(
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) ||
                    count[0] <= 0
                ) return Capability(false, hlg, pq, tenBit, census.first, census.second)
                context = EGL14.eglCreateContext(
                    display,
                    configs[0],
                    EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                    0
                )
                if (context == EGL14.EGL_NO_CONTEXT) return Capability(false, hlg, pq, tenBit, census.first, census.second)
                surface = EGL14.eglCreatePbufferSurface(
                    display,
                    configs[0],
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                    0
                )
                if (surface == EGL14.EGL_NO_SURFACE) return Capability(false, hlg, pq, tenBit, census.first, census.second)
                if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                    return Capability(false, hlg, pq, tenBit, census.first, census.second)
                }
                val glExtensions = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
                val halfFloat = glExtensions.contains(GL_EXT_COLOR_BUFFER_HALF_FLOAT) ||
                    glExtensions.contains(GL_EXT_COLOR_BUFFER_FLOAT)
                return Capability(halfFloat, hlg, pq, tenBit, census.first, census.second)
            } catch (ignored: Throwable) {
                return Capability(false, false, false)
            } finally {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                    )
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(display)
                }
            }
        }
    }

    /**
     * @param linearSceneSupported 场景能否用 `GL_RGBA16F` 合成（超白值算不算得出来）。
     * @param bt2020HlgSupported EGL 能否给编码器 surface 打 BT.2020 HLG 色彩空间。
     * @param bt2020PqSupported 同上，PQ（HDR10）。两者独立判定，缺一不代表不支持 HDR。
     */
    internal data class Capability(
        val linearSceneSupported: Boolean,
        val bt2020HlgSupported: Boolean,
        val bt2020PqSupported: Boolean,
        /**
         * 建得起 10-bit 窗口表面的那一档 config 名；null = 一档都建不起来。
         *
         * 广告了 PQ 扩展**不等于**建得起 10-bit 表面：华为平板两者都广告，却整机卡在
         * `eglChooseConfig`。这一项是把那种情况说清楚的唯一依据。
         */
        val tenBitWindowConfig: String? = null,
        /** 本机 10-bit 窗口 config 总数。 */
        val tenBitWindowConfigCount: Int = 0,
        /**
         * 其中带 `EGL_RECORDABLE_ANDROID` 的个数。
         *
         * 这个数决定 10-bit 编码在这台机器上成不成立。recordable 不是"给驱动的提示"：它决定
         * 分配出来的缓冲带不带视频编码器用途位，少了它，交给 `MediaCodec` 输入表面的缓冲编码
         * 器根本消费不了。三星 Z Fold4 上 10-bit 各档一律以编码器"已被释放"告终，而 8-bit 档
         * 走的是带 recordable 的那一档，一切正常（2026-07-28）。
         */
        val tenBitRecordableConfigCount: Int = 0
    ) {

        val anyHdrColorSpace: Boolean get() = bt2020HlgSupported || bt2020PqSupported

        /** 按偏好顺序列出可用的 HDR 传递函数：PQ 在前——余量更大、扩展支持面也更广。 */
        fun availableHdrTransfers(): List<FableSolExportTransfer> = buildList {
            if (bt2020PqSupported) add(FableSolExportTransfer.PQ)
            if (bt2020HlgSupported) add(FableSolExportTransfer.HLG)
        }
    }
}
