package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * HDR10+／HDR Vivid「参考显示峰值」滑杆的档位刻度（D94、D116、D11）。
 *
 * 档距不是均匀的：`300～1000` 每档 25 尼特、`1000～4000` 每档 100 尼特、`4000～10000` 每档
 * 500 尼特。理由是人眼对亮度的分辨随绝对值增大而变粗——低端 25 尼特已经看得出差别，而在
 * 4000 尼特那一带，500 尼特也未必分得清。均匀档距要么让低端粗到没法用，要么让高端多出几百
 * 个没有意义的格子。
 *
 * 因此滑杆的 `progress` 是**档位下标**，不是尼特值；下标与尼特的换算只有这一处。
 */
internal object FableSolExportReferencePeak {

    /** 滑杆的最大 `progress`；共 [STEPS] + 1 个档位。 */
    const val STEPS = 70

    /** 滑杆下方可直接选取的参考值（D94）。 */
    val SHORTCUTS = intArrayOf(400, 600, 1000, 2000, 4000)

    /** 档位下标 → 尼特。 */
    fun nitsAt(index: Int): Float {
        val step = index.coerceIn(0, STEPS)
        return when {
            step <= LOW_STEPS -> LOW_START + step * LOW_STRIDE
            step <= LOW_STEPS + MID_STEPS -> MID_START + (step - LOW_STEPS) * MID_STRIDE
            else -> HIGH_START + (step - LOW_STEPS - MID_STEPS) * HIGH_STRIDE
        }.toFloat()
    }

    /**
     * 尼特 → 最接近的档位下标。
     *
     * 用于把持久化的数值、快捷值和"采用本机值"读回来的数落到滑杆上。取最近档而不是向下取整：
     * 本机声明值往往落在两档之间（例如 1600），向下取整会让"采用本机值"之后显示的数与用户
     * 刚刚看到的那个不一样。
     */
    fun indexOf(nits: Float): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (step in 0..STEPS) {
            val distance = kotlin.math.abs(nitsAt(step) - nits)
            if (distance < bestDistance) {
                bestDistance = distance
                best = step
            }
        }
        return best
    }

    /** 把任意数值对齐到滑杆刻度上；持久化的一律是对齐后的值。 */
    fun snap(nits: Float): Float = nitsAt(indexOf(nits))

    private const val LOW_START = 300
    private const val LOW_STRIDE = 25
    private const val LOW_STEPS = 28          // 300 → 1000

    private const val MID_START = 1000
    private const val MID_STRIDE = 100
    private const val MID_STEPS = 30          // 1000 → 4000

    private const val HIGH_START = 4000
    private const val HIGH_STRIDE = 500       // 4000 → 10000
}
