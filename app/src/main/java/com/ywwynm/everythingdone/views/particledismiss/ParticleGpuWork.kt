package com.ywwynm.everythingdone.views.particledismiss

import android.opengl.GLES30
import java.util.concurrent.atomic.AtomicInteger

/** 触摸反馈、源窗口交接及播放优先；后台逆向准备不能一次排入完整一秒计算。 */
internal object ParticleGpuWork {
    private val playing = AtomicInteger()
    fun beginPlayback() { playing.incrementAndGet() }
    fun endPlayback() { playing.decrementAndGet() }

    /** 每个 GL 上下文独享。最多两批在途，使 CPU 提交与上一批 GPU 运算重叠。 */
    class PreparationQueue : java.io.Closeable {
        private var pending = 0L
        fun finishBatch(cancelled: () -> Boolean): Boolean {
            if (playing.get() == 0) { close(); return !cancelled() }
            val previous = pending
            pending = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            check(pending != 0L) { "创建粒子准备同步点失败" }
            GLES30.glFlush()
            if (previous != 0L) try {
                while (!cancelled()) {
                    when (GLES30.glClientWaitSync(previous, 0, 2_000_000L)) {
                        GLES30.GL_ALREADY_SIGNALED, GLES30.GL_CONDITION_SATISFIED -> break
                        GLES30.GL_WAIT_FAILED -> error("等待粒子准备同步点失败")
                    }
                }
            } finally { GLES30.glDeleteSync(previous) }
            return !cancelled()
        }
        override fun close() {
            if (pending != 0L) GLES30.glDeleteSync(pending)
            pending = 0L
        }
    }
}
