package com.ywwynm.everythingdone.views.particledismiss

/**
 * **性能自动调节**（U9，逐帧成本专项第二阶段）：按实测帧率自动降一档的判定规则。
 *
 * 这里只有纯逻辑，不碰 SharedPreferences、不碰 GL、不碰界面，JVM 单测直接覆盖。
 * 触发条件与边界都写在 [decide] 的注释里。
 *
 * 为什么需要它：同一份默认档位在不同 GPU 上的代价差一个量级——华为 MatePad Pro 13.2 上
 * 默认档位的附件弹窗消散只出到应有帧数的 0.19，把粒子大小与流动精细度各调一档就回到 1.01。
 * 让用户自己去设置页发现这件事是不合理的。
 *
 * **默认关闭**，由设置页里的「性能自动调节」打开（[ParticleTuning.KEY_AUTO_TUNE] →
 * [ParticlePerfTuning.autoDowngrade]）。
 */
internal object ParticleAutoDowngrade {

    /** 「这一轮掉帧了」的判据：真正上屏的帧数 / 应有帧数。 */
    const val POOR_RATIO = .8f

    /** 连续几轮低于门槛才动手。一轮可能只是被别的应用抢了 GPU。 */
    const val CONSECUTIVE_ROUNDS = 2

    /** 一次只降一档，而且只降这么多次——再差也不替用户一路降到底。 */
    const val MAX_STEPS = 1

    /**
     * 一次判定的输入。全部是调用方从设置与 [ParticleFrameStats] 读出来的当前值。
     *
     * @param ratio 这一轮的「呈现帧数 / 应有帧数」；小于 0 表示这一轮没采到，忽略。
     * @param poorRounds 在这一轮之前已经连续几轮低于门槛。
     * @param cellIndex 粒子大小的当前档位下标（越小粒子越细、越贵）。
     * @param flowDetail 流动精细度的当前取值（0–100，越大越贵）。
     * @param cellExplicit 用户是否**显式**选过粒子大小。
     * @param appliedSteps 本机已经自动降过几档。
     * @param userChanged 用户在自动降档之后自己改过设置——改过就再也不自动降。
     */
    class State(val ratio: Float, val poorRounds: Int, val cellIndex: Int, val flowDetail: Int,
                val cellExplicit: Boolean, val appliedSteps: Int, val userChanged: Boolean)

    /**
     * 判定结果。[poorRounds] 永远要写回去（它是下一次判定的输入）；
     * [downgrade] 为真时才动档位。
     */
    class Decision(val poorRounds: Int, val downgrade: Boolean,
                   val cellIndex: Int, val flowDetail: Int) {
        val appliedDelta get() = if (downgrade) 1 else 0
    }

    /**
     * 规则：
     *
     * 1. 这一轮没采到（`ratio < 0`）就原样返回，连续计数不动——没有证据不该当成好，也不该当成坏。
     * 2. `ratio >= POOR_RATIO` 把连续计数清零。
     * 3. 连续 [CONSECUTIVE_ROUNDS] 轮低于门槛才降档，且**粒子大小与流动精细度各降一档**
     *    （第四批实测：华为上这两项各调一档就从 0.19 回到 1.01，单调一项不够）。
     *    流动精细度是连续取值，一档取 [ParticleTuning.FLOW_EASE_STEP]。
     * 4. 已经降过 [MAX_STEPS] 次、或者用户在那之后自己改过设置，就不再自动降。
     * 5. 粒子大小原来是「未设置」时，降档等于**第一次显式选一档**——从默认停留的那一档
     *    （[ParticleTuning.DEFAULT_CELL_INDEX]）往粗里走一档。
     * 6. 两项都已经在最省的一档时不再降，连续计数保留（下次还是这个结论，不会反复写设置）。
     */
    fun decide(state: State): Decision {
        if (state.ratio < 0f) {
            return Decision(state.poorRounds, false, state.cellIndex, state.flowDetail)
        }
        if (state.ratio >= POOR_RATIO) {
            return Decision(0, false, state.cellIndex, state.flowDetail)
        }
        val rounds = state.poorRounds + 1
        if (state.userChanged || state.appliedSteps >= MAX_STEPS || rounds < CONSECUTIVE_ROUNDS) {
            return Decision(rounds, false, state.cellIndex, state.flowDetail)
        }
        val from = if (state.cellExplicit) state.cellIndex else ParticleTuning.DEFAULT_CELL_INDEX
        val cell = (from + 1).coerceAtMost(ParticleTuning.CELL_DP_LEVELS.lastIndex)
        val flow = (state.flowDetail - ParticleTuning.FLOW_EASE_STEP)
            .coerceAtLeast(ParticleTuning.FLOW_MIN)
        if (cell == state.cellIndex && state.cellExplicit && flow == state.flowDetail) {
            // 两项都到头了：不写设置，也不把连续计数清掉。
            return Decision(rounds, false, state.cellIndex, state.flowDetail)
        }
        return Decision(rounds, true, cell, flow)
    }
}
