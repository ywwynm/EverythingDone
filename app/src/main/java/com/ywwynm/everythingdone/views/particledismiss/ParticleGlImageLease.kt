package com.ywwynm.everythingdone.views.particledismiss

import android.opengl.GLES30

/** 已导入的生产者缓冲必须等最后一次 GPU 读取完成，才能退回 ImageReader。 */
internal object ParticleGlImageLease {
    fun releaseAfterReading(release: () -> Unit, pollDelayMs: Long = 4) {
        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        if (fence == 0L) { GLES30.glFinish(); release(); return }
        GLES30.glFlush()
        fun check() {
            val state = GLES30.glClientWaitSync(fence, 0, 0L)
            if (state == GLES30.GL_TIMEOUT_EXPIRED) ParticleGl.postDelayed(pollDelayMs) { check() }
            else {
                // 出错时也先等完本上下文，不能把仍被采样的缓冲交还生产者。
                if (state == GLES30.GL_WAIT_FAILED) GLES30.glFinish()
                GLES30.glDeleteSync(fence)
                release()
            }
        }
        check()
    }
}
