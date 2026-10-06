package com.ywwynm.everythingdone.views.particledismiss

/**
 * 粒子样式（设置 → 粒子动画设置 → 粒子样式）。
 *
 * - [FLOW]「流体」：原有实现（微片 + 压力求解，[ParticleSession]）。
 * - [DRIFT]「飘散」：按华为 `GEParticleAblationFilter` 源码逐项移植的消蚀 + 风与湍流（[ParticleDriftSession]），
 *   见 `docs/features/harmony-particle-android-port/`。缺省值。
 *
 * 碰一碰固定用流体：它依赖流体样式专有的汇聚终点与材料共享（[ParticleDismissSpec.style] 的缺省规则）。
 */
internal enum class ParticleStyle(val key: String) {
    FLOW("flow"),
    DRIFT("drift");

    companion object {
        val DEFAULT = DRIFT

        fun of(key: String?): ParticleStyle = values().firstOrNull { it.key == key } ?: DEFAULT

        /** 当前设置；由 [ParticleTuning.apply] 写入，建规格时读。 */
        @Volatile var current: ParticleStyle = DEFAULT

        /** 飘散样式的「粒子数量」倍率；由 [ParticleTuning.apply] 写入。 */
        @Volatile var driftAmount: Float = 1f

        /** 流体样式的「粒子数量」倍率；与飘散独立，由 [ParticleTuning.apply] 写入。 */
        @Volatile var flowAmount: Float = 1f

        /** 飘散样式的「粒子大小」倍率（1 = 源码大小）；由 [ParticleTuning.apply] 写入。 */
        @Volatile var driftSize: Float = 1f
    }
}
