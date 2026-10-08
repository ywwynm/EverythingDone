package com.ywwynm.everythingdone.views.particledismiss

/**
 * 每个渲染器在构造时固定算法变体；离屏对照用线程局部覆盖，不能影响正在播放的会话。
 * 三星实测采用流体颜色下沉、真圆、积分合并、稳定可见实例压缩、记录生成与扫描合并及严格压力阈值。
 * 飘散采用覆盖方框裁剪；缓存／拟合、粗压力网格及后半段降频保留为实验对照：
 * 前者在大粒子场景没有稳定净收益，后者的轨迹误差过大。正式路径不分配飘散缓存。
 */
internal data class ParticleFastOptions(
    val driftCache: Boolean = false,
    val driftCurve: Boolean = false,
    val flowColors: Boolean = true,
    val flowCircle: Boolean = true,
    val pressureResolution: Int = 96,
    val driftTightQuad: Boolean = true,
    /** 压力批间停止的绝对缺陷上限；0 保留完整迭代。 */
    val pressureTolerance: Float = .001f,
    val flowLateSteps: Boolean = false,
    val pressureLateStride: Boolean = false,
    val integrationBatch: Boolean = true,
    val compactCircles: Boolean = true,
    /** 散度复用仅保留调试对照：三星现场收益未稳定超出噪声。 */
    val pressureInputReuse: Boolean = false,
    /** 已验证的 Adreno 路径合并记录生成与稳定扫描，保持原粒子顺序。 */
    val fusedCircleRecords: Boolean = true,
    val driftStandardColor: Boolean = false,
    /** 正式流体材质；线程局部对照可关闭，不写入用户偏好。 */
    val flowSoftMaterial: Boolean = true
) {
    init {
        require(pressureResolution == 96 || pressureResolution == 72)
        require(pressureTolerance.isFinite() && pressureTolerance >= 0f)
    }

    companion object {
        val BASELINE = ParticleFastOptions(false, false, false, false, driftTightQuad = false,
            pressureTolerance = 0f, integrationBatch = false, compactCircles = false,
            pressureInputReuse = false, fusedCircleRecords = false)
        private val override = ThreadLocal<ParticleFastOptions?>()
        val current: ParticleFastOptions get() = override.get() ?: ParticleFastOptions().let {
            it.copy(pressureInputReuse = ParticlePerfTuning.flowPressureInputReuse ?: it.pressureInputReuse,
                fusedCircleRecords = ParticlePerfTuning.flowFusedCircleRecords ?: it.fusedCircleRecords,
                driftStandardColor = ParticleStyle.driftColorStandard)
        }

        fun <T> withOptions(options: ParticleFastOptions, block: () -> T): T {
            val previous = override.get()
            override.set(options)
            return try { block() } finally {
                if (previous == null) override.remove() else override.set(previous)
            }
        }

    }
}
