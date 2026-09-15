package com.ywwynm.everythingdone.views.particledismiss

import android.opengl.EGLDisplay
import android.opengl.EGLSurface

/**
 * `EGL_ANDROID_get_frame_timestamps` 的薄封装：判定「这一帧真的上屏了」。
 *
 * 只有 `EGL_DISPLAY_PRESENT_TIME_ANDROID` 直达扫出时刻；`FrameMetrics` 拿不到，
 * `SurfaceControl.Transaction` 的 latch 也不等于 present（见 research-2026-09-14-platform 第 7 条）。
 * Java 侧没有这三个入口的绑定，所以走 JNI（[ParticleMaterialNative] 那个 so）。
 *
 * 拿不到扩展、`eglSurfaceAttrib` 失败、或驱动对这一项报不支持时 [enable] 返回 false，
 * 调用方退回保守判定：**缓冲入队 + 下一次 vsync 即视为已显示**。
 */
internal object ParticleFrameTimestamps {

    /** 尚未确定，需要下一拍再问。 */
    const val PENDING = -2L

    /** 这一帧不会再有这个时间戳（例如被丢弃）。 */
    const val INVALID = -1L

    /** 调用本身失败。 */
    const val FAILED = -3L

    val available: Boolean get() = ParticleMaterialNative.available

    fun enable(display: EGLDisplay, surface: EGLSurface): Boolean {
        if (!available) return false
        return runCatching { nEnable(display.nativeHandle, surface.nativeHandle) }.getOrDefault(false)
    }

    /** 下一次 `eglSwapBuffers` 产生的帧号；-1 表示取不到。 */
    fun nextFrameId(display: EGLDisplay, surface: EGLSurface): Long {
        if (!available) return -1L
        return runCatching { nNextFrameId(display.nativeHandle, surface.nativeHandle) }.getOrDefault(-1L)
    }

    fun presentTime(display: EGLDisplay, surface: EGLSurface, frameId: Long): Long {
        if (!available || frameId < 0) return FAILED
        return runCatching { nPresentTime(display.nativeHandle, surface.nativeHandle, frameId) }
            .getOrDefault(FAILED)
    }

    @JvmStatic private external fun nEnable(display: Long, surface: Long): Boolean
    @JvmStatic private external fun nNextFrameId(display: Long, surface: Long): Long
    @JvmStatic private external fun nPresentTime(display: Long, surface: Long, frameId: Long): Long
}
