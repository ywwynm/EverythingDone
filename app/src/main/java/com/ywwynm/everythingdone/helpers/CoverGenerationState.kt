package com.ywwynm.everythingdone.helpers

/** 生成进度不包含写入完成；只有最终文件可用时才进入 READY。 */
data class CoverGenerationState(val phase: Phase, val percent: Int = 0) {
    enum class Phase { IDLE, WAITING, PREPARING, GENERATING, READY, FAILED }

    companion object {
        val IDLE = CoverGenerationState(Phase.IDLE)
        val WAITING = CoverGenerationState(Phase.WAITING)
        val PREPARING = CoverGenerationState(Phase.PREPARING)
        val READY = CoverGenerationState(Phase.READY, 100)
        val FAILED = CoverGenerationState(Phase.FAILED)

        fun framesProcessed(processed: Int, total: Int) = CoverGenerationState(
            Phase.GENERATING,
            if (total <= 0) 0 else (processed.toLong() * 100 / total).toInt().coerceIn(0, 99)
        )
    }
}
