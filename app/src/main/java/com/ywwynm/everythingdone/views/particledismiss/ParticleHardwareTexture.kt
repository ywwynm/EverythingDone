package com.ywwynm.everythingdone.views.particledismiss

import android.hardware.HardwareBuffer

/**
 * 把 [HardwareBuffer] 经 EGLImage 绑成 GL 纹理的 JNI 绑定（`particle_hardware_texture.cpp`，与
 * [ParticleMaterialNative] 同一个 so）。飘散的零拷贝快照用它（[ParticleDriftHardwareImport]）。
 * 拿不到扩展入口时 [available] 为 false，调用方退回「拷回内存再上传」。只在 GL 线程上调用。
 */
internal object ParticleHardwareTexture {

    val available: Boolean by lazy {
        ParticleMaterialNative.available && runCatching { nAvailable() }.getOrDefault(false)
    }

    /** 为 [buffer] 建 EGLImage，绑到当前绑定的 `GL_TEXTURE_2D` 上；返回 EGLImage 句柄，失败为 0。 */
    fun bind(display: Long, buffer: HardwareBuffer): Long =
        if (!available) 0L else runCatching { nBind(display, buffer) }.getOrDefault(0L)

    /** 纹理删掉之后再销毁它的 EGLImage。 */
    fun destroy(display: Long, image: Long) {
        if (available && image != 0L) runCatching { nDestroy(display, image) }
    }

    @JvmStatic private external fun nAvailable(): Boolean
    @JvmStatic private external fun nBind(display: Long, buffer: HardwareBuffer): Long
    @JvmStatic private external fun nDestroy(display: Long, image: Long)
}
