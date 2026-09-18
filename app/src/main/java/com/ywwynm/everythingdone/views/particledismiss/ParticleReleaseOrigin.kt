package com.ywwynm.everythingdone.views.particledismiss

/** 消逝起点在实际快照内的归一化坐标，与飞出方向无关。 */
internal data class ParticleReleaseOrigin(val x: Float, val y: Float) {
    companion object {
        /** 快照原点已包含裁切和阴影外扩；屏幕坐标相减后不再受窗口偏移或密度影响。 */
        fun fromScreen(touchX: Float, touchY: Float, snapshotX: Float, snapshotY: Float,
                       width: Int, height: Int) = ParticleReleaseOrigin(
            ((touchX - snapshotX) / width).coerceIn(0f, 1f),
            ((touchY - snapshotY) / height).coerceIn(0f, 1f))
    }
}
