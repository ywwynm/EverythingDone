package com.ywwynm.everythingdone.views.particledismiss

/** 手指与 ItemTouchHelper 的松手恢复动画共用绝对距离，不累加时间或位移。 */
internal class ParticleGestureProgress(initiallyActive: Boolean = true) {
    @Volatile var active = initiallyActive
        private set
    @Volatile var activatedAtNanos = if (initiallyActive) System.nanoTime() else 0L
        private set
    fun activate() {
        if (active) return
        activatedAtNanos = System.nanoTime()
        active = true
    }
    @Volatile var progress = 0f
        private set
    @Volatile var presented = false
    @Volatile var presentedProgress = 0f
        private set
    private val submissions = java.util.TreeMap<Long, Float>()

    fun update(displacement: Float, width: Int) {
        seek(if (width > 0 && displacement.isFinite()) -displacement / width else 0f)
    }

    fun seek(value: Float) { progress = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f }

    @Synchronized fun submitted(timestamp: Long, value: Float) {
        submissions[timestamp] = value
        while (submissions.size > 120) submissions.pollFirstEntry()
    }

    /** 使用窗口实际消费的帧，不能把最新手指位置误当成已显示进度。 */
    @Synchronized fun progressAt(timestamp: Long): Float {
        submissions[timestamp]?.let { presentedProgress = it }
        submissions.headMap(timestamp, false).clear()
        return presentedProgress
    }

    /** 识别左滑后首帧直接使用当前距离，不再等待一个已经过时的零进度帧上屏。 */
    fun framePosition(samples: Int): Float = (if (active) progress else 0f) * samples
}
