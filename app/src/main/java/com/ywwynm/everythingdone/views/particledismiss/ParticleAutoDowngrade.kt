package com.ywwynm.everythingdone.views.particledismiss

/** 按实测帧率降低粒子数量和流动精细度；大小只控制绘制倍率，不再用于减少材料数量。 */
internal object ParticleAutoDowngrade {
    const val POOR_RATIO = .8f
    const val CONSECUTIVE_ROUNDS = 2
    const val MAX_STEPS = 1

    class State(val ratio: Float, val poorRounds: Int, val amountIndex: Int, val flowDetail: Int,
                val appliedSteps: Int, val userChanged: Boolean)

    class Decision(val poorRounds: Int, val downgrade: Boolean,
                   val amountIndex: Int, val flowDetail: Int) {
        val appliedDelta get() = if (downgrade) 1 else 0
    }

    /** 连续两轮低于门槛才动手；最多一次，用户随后调过参数就不再干预。 */
    fun decide(state: State): Decision {
        if (state.ratio < 0f) return Decision(state.poorRounds, false, state.amountIndex, state.flowDetail)
        if (state.ratio >= POOR_RATIO) return Decision(0, false, state.amountIndex, state.flowDetail)
        val rounds = state.poorRounds + 1
        if (state.userChanged || state.appliedSteps >= MAX_STEPS || rounds < CONSECUTIVE_ROUNDS)
            return Decision(rounds, false, state.amountIndex, state.flowDetail)
        val amount = (state.amountIndex - 1).coerceAtLeast(0)
        val flow = (state.flowDetail - ParticleTuning.FLOW_EASE_STEP).coerceAtLeast(ParticleTuning.FLOW_MIN)
        if (amount == state.amountIndex && flow == state.flowDetail)
            return Decision(rounds, false, state.amountIndex, state.flowDetail)
        return Decision(rounds, true, amount, flow)
    }
}
