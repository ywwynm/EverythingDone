package com.ywwynm.everythingdone.views.particledismiss

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** 只缓存每片粒子可见寿命附近的状态；不保存整屏视频或改变材料数量。 */
internal class ParticleReversePlan(values: FloatArray, val samples: Int = samplesFor(APPEARANCE_SECONDS, 60f)) {
    val count = values.size / 12
    /** 每片四个整数：首采样、末采样、状态偏移、保留位。 */
    val ranges = IntArray(count * 4)
    val bytes: Int

    init {
        require(values.size % 12 == 0)
        require(samples in 1..MAX_SAMPLES)
        var states = 0
        for (i in 0 until count) {
            val birth = values[i * 12 + 2]
            val death = birth + values[i * 12 + 6]
            // 向两侧各留一个采样，包含边界浮点舍入及刚解除的材料。
            val first = (floor(birth * samples).toInt() - 1).coerceIn(0, samples)
            val last = (ceil(death * samples).toInt() + 1).coerceIn(first, samples)
            ranges[i * 4] = first
            ranges[i * 4 + 1] = last
            ranges[i * 4 + 2] = states
            states += last - first + 1
        }
        bytes = Math.multiplyExact(states, STATE_BYTES)
    }

    fun time(frame: Int): Float = frame.coerceIn(0, samples) / samples.toFloat()
    fun frame(progress: Float): Int = ((1f - progress.coerceIn(0f, 1f)) * samples).roundToInt()

    companion object {
        const val APPEARANCE_SECONDS = .6f
        const val MAX_SAMPLES = 60
        // 只缓存绘制使用的位置 xyz、速度 xy，保留完整 float32 精度。
        const val STATE_BYTES = 20
        // 仍积分完整 240 步，只减少本次播放不会展示的状态副本。
        // 系统放慢动画时恢复到 60 份；不因设备高刷新率增添额外历史开销。
        fun samplesFor(seconds: Float, refreshRate: Float): Int =
            (seconds * refreshRate.coerceIn(30f, 60f)).roundToInt().coerceIn(1, MAX_SAMPLES)
    }
}
