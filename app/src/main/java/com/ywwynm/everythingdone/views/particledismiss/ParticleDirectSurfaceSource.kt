package com.ywwynm.everythingdone.views.particledismiss

/** 自有 SurfaceControl 的粒子合同；不将普通 View 当作 SurfaceView 使用。 */
internal interface ParticleDirectSurfaceSource : ParticleSurfaceSnapshotSource {
    val particleSurfaceValid: Boolean
    val particleSurfaceGeneration: Long
    /** 提交生产者恢复帧，并等当前图层事务完成；独立于窗口缓冲的呈现判据。 */
    fun presentForParticleRecovery(done: (Boolean) -> Unit)
}
