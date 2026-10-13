package com.ywwynm.everythingdone.views.particledismiss

/** 主线程协调持续页面的缓冲所有权：当前显示的一张保留，旧帧读完才继续生产。 */
internal class ParticlePageFrameGate {
    var retained = 0
        private set
    private var ready: (() -> Unit)? = null
    private var closed = false

    fun retain() { check(!closed); retained++ }

    fun release() {
        check(retained > 0)
        retained--
        drain()
    }

    fun submitted(done: () -> Unit) {
        if (closed) return
        check(ready == null)
        ready = done
        drain()
    }

    fun close() { closed = true; ready = null }

    private fun drain() {
        if (retained > 1 || closed) return
        val done = ready ?: return
        ready = null
        done()
    }
}
