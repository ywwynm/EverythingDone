package com.ywwynm.everythingdone.views.particledismiss

/**
 * 真实 Dialog 退出前的双条件门闩。
 *
 * Activity overlay 必须已经实际提交，且粒子 GL 首帧必须已经完成 buffer swap；
 * 只满足前者就移除 Dialog，会让键盘和背景在粒子仍准备时先发生变化。
 */
internal class ParticleDismissStartGate(
    private val onReady: () -> Unit
) {
    private var overlayShown = false
    private var animationStarted = false
    private var readyFired = false

    fun markOverlayShown() {
        overlayShown = true
        fireIfReady()
    }

    fun markAnimationStarted() {
        animationStarted = true
        fireIfReady()
    }

    private fun fireIfReady() {
        if (readyFired || !overlayShown || !animationStarted) return
        readyFired = true
        onReady()
    }
}
