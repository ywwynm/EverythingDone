package com.ywwynm.everythingdone.views.particledismiss

/** 普通窗口与每个独立表面都就绪才撤掉覆盖；重复、迟到回调不重复交付。 */
internal class ParticleSurfaceReadyBarrier(count: Int, private val ready: () -> Unit) {
    private val surfaces = BooleanArray(count)
    private var window = false
    private var closed = false
    fun windowReady() { window = true; checkReady() }
    fun surfaceReady(index: Int) { if (!closed) surfaces[index] = true; checkReady() }
    fun surfaceReplaced(index: Int) { if (!closed) surfaces[index] = false }
    fun close() { closed = true }
    private fun checkReady() {
        if (closed || !window || surfaces.any { !it }) return
        closed = true
        ready()
    }
}
