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

    /**
     * 播放进度对应的采样下标。[start] 是倒放的起点采样（见 [appearanceStartFrame]）：
     * 默认就是 [samples]，跳过尾段空白时取更小的值。**[time] 仍按 [samples] 归一**，
     * 所以保留下来的每一帧与不跳过时逐位相同。
     */
    fun frame(progress: Float, start: Int = samples): Int =
        position(progress, start).roundToInt()

    /** 显示频率与缓存密度分开；高刷播放读取相邻采样，历史内存不随刷新率翻倍。 */
    fun position(progress: Float, start: Int = samples): Float =
        (1f - progress.coerceIn(0f, 1f)) * start.coerceIn(0, samples)

    companion object {
        const val APPEARANCE_SECONDS = .6f
        const val MAX_SAMPLES = 60

        /**
         * 倒放起点的门槛：正在以颗粒形态出现的片数低于全部片数的这个比例时，
         * 那一帧在画面上几乎什么也没有。
         *
         * 3b 从 5% 提到 10%：两台真机上多跳一个采样（OPPO 34 → 33、三星 33 → 32），
         * 既让起始段更明显（首帧的存活占比从 6–7% 提到 13–14%），也按比例换回预计算时间。
         */
        const val APPEARANCE_ALIVE_RATIO = .10f

        /**
         * 每个采样上「正在以颗粒形态出现」的片数。
         *
         * 判据与 `material.vert` 的裁剪完全一致：`age = t - birth` 落在 `(0, life)` 开区间内
         * 才画颗粒（`age<=0.` 或 `age>=life` 直接把顶点丢到裁剪体外）。出生之前那一片仍属于
         * 完整面板（第 0 遍的表面），不计入这里——出现动画的**末**帧本来就是完整面板。
         */
        fun aliveCounts(values: FloatArray, samples: Int): IntArray {
            require(values.size % 12 == 0)
            require(samples >= 1)
            val difference = IntArray(samples + 2)
            var i = 0
            while (i < values.size) {
                val birth = values[i + 2]
                val death = birth + values[i + 6]
                // t > birth 即 f > birth*samples；整数边界也要真的排除掉，与着色器的严格不等号一致。
                val first = (floor(birth.toDouble() * samples).toInt() + 1).coerceIn(0, samples)
                val last = (ceil(death.toDouble() * samples).toInt() - 1).coerceIn(-1, samples)
                if (last >= first) { difference[first]++; difference[last + 1]-- }
                i += 12
            }
            val counts = IntArray(samples + 1)
            var running = 0
            for (frame in 0..samples) { running += difference[frame]; counts[frame] = running }
            return counts
        }

        /** 由已经算好的存活片数取起点；[count] 是全部片数。 */
        fun startFrameOf(counts: IntArray, count: Int,
                         minAliveRatio: Float = APPEARANCE_ALIVE_RATIO): Int {
            val samples = counts.size - 1
            if (count <= 0 || samples < 1 || minAliveRatio <= 0f) return samples
            val threshold = count * minAliveRatio
            for (frame in samples downTo 1) if (counts[frame] >= threshold) return frame
            // 从来没有达标过（例如片数极少）：照旧完整倒放，不做任何裁剪。
            return samples
        }

        /**
         * 倒放的起点采样：最晚的那个仍有 [minAliveRatio] 比例的片在出现的采样。
         * 更晚的采样几乎全是空画面——既看不出动画，也白算一遍积分。
         */
        fun appearanceStartFrame(values: FloatArray, samples: Int,
                                 minAliveRatio: Float = APPEARANCE_ALIVE_RATIO): Int =
            startFrameOf(aliveCounts(values, samples), values.size / 12, minAliveRatio)
        // 只缓存绘制使用的位置 xyz、速度 xy，保留完整 float32 精度。
        const val STATE_BYTES = 20
        // 仍积分完整 240 步，只减少本次播放不会展示的状态副本。
        // 系统放慢动画时恢复到 60 份；不因设备高刷新率增添额外历史开销。
        fun samplesFor(seconds: Float, refreshRate: Float): Int =
            (seconds * refreshRate.coerceIn(30f, 60f)).roundToInt().coerceIn(1, MAX_SAMPLES)
    }
}
