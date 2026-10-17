package com.ywwynm.everythingdone.helpers

/** 饼图只表示逐帧处理。原生集中编码没有可测百分比，单独显示不定进度；文件可用才 READY。 */
data class CoverGenerationState(val phase: Phase, val percent: Int = 0) {
    enum class Phase { IDLE, WAITING, PREPARING, GENERATING, FINALIZING, READY, FAILED }

    companion object {
        val IDLE = CoverGenerationState(Phase.IDLE)
        val WAITING = CoverGenerationState(Phase.WAITING)
        val PREPARING = CoverGenerationState(Phase.PREPARING)
        val FINALIZING = CoverGenerationState(Phase.FINALIZING)
        val READY = CoverGenerationState(Phase.READY, 100)
        val FAILED = CoverGenerationState(Phase.FAILED)

        fun framesProcessed(processed: Int, total: Int) = if (total > 0 && processed >= total) FINALIZING else CoverGenerationState(
            Phase.GENERATING,
            if (total <= 0) 0 else (processed.toLong() * 100 / total).toInt().coerceIn(0, 99)
        )

        /** WorkInfo 的运行次数已包含当前这轮。旧轮次进度不能覆盖正在重新准备的任务。 */
        internal fun running(currentAttempt: Int, progressAttempt: Int, phase: String?, percent: Int): CoverGenerationState {
            if (currentAttempt != progressAttempt) return PREPARING
            return when (phase) {
                Phase.WAITING.name -> WAITING
                Phase.GENERATING.name -> CoverGenerationState(Phase.GENERATING, percent.coerceIn(0, 99))
                Phase.FINALIZING.name -> FINALIZING
                else -> PREPARING
            }
        }
    }
}
