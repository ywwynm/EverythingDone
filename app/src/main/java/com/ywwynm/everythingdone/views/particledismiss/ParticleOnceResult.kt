package com.ywwynm.everythingdone.views.particledismiss

/** 提交、超时、退出竞争时只交付一次结果。调用者统一切回主线程。 */
internal class ParticleOnceResult(private var callback: ((Boolean) -> Unit)?) {
    fun finish(success: Boolean) {
        val pending = callback ?: return
        callback = null
        pending(success)
    }
}
