package com.ywwynm.everythingdone.views.particledismiss

/** 隔离异步首帧、取消和窗口恢复的顺序；迟到回调不能重新隐藏原控件。只在主线程使用。 */
internal class ParticleGlHandoffState {
    enum class Phase { PREPARED, STAGING, ACTIVE, RETURNING, CLOSED }
    var phase = Phase.PREPARED
        private set

    fun beginStart(): Boolean {
        if (phase != Phase.PREPARED) return false
        phase = Phase.STAGING
        return true
    }

    fun acceptStartFrame(): Boolean {
        if (phase != Phase.STAGING) return false
        phase = Phase.ACTIVE
        return true
    }

    fun beginReturn(): Boolean {
        if (phase == Phase.RETURNING || phase == Phase.CLOSED) return false
        phase = Phase.RETURNING
        return true
    }

    fun close(): Boolean {
        if (phase == Phase.CLOSED) return false
        phase = Phase.CLOSED
        return true
    }
}
