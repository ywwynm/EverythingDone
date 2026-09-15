package com.ywwynm.everythingdone.views.particledismiss

import android.opengl.GLES30
import java.util.concurrent.atomic.AtomicInteger

/** 触摸反馈、源窗口交接及播放优先；后台逆向准备不能一次排入完整一秒计算。 */
internal object ParticleGpuWork {
    private val playing = AtomicInteger()
    fun beginPlayback() { playing.incrementAndGet() }
    fun endPlayback() { playing.decrementAndGet() }

    /** 是否已经有触摸反馈或粒子播放在进行；准备类线程据此决定要不要让出大核（见 1.7）。 */
    val busy: Boolean get() = playing.get() > 0

    /** 当前登记的反馈 + 会话数；会话判断「除了我还有别人在用 GPU 吗」。 */
    val playbackCount: Int get() = playing.get()

    /**
     * 每个 GL 上下文独享。最多两批在途，使 CPU 提交与上一批 GPU 运算重叠。
     *
     * [alwaysThrottle] 为 true 时即使没有任何动画在播也照样节流：首页空闲时的提前预算
     * （第四批 ②）走这一档，否则整段几百毫秒的 compute 会一次性排进驱动队列，用户刚一触摸
     * 就得排在它后面。
     */
    class PreparationQueue(private val alwaysThrottle: Boolean = false) : java.io.Closeable {
        private var pending = 0L
        fun finishBatch(cancelled: () -> Boolean): Boolean {
            if (playing.get() == 0 && !alwaysThrottle) { close(); return !cancelled() }
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
