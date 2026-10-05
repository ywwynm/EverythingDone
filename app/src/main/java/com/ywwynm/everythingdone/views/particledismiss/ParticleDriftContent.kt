package com.ywwynm.everythingdone.views.particledismiss

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 飘散样式的内容强调。用户 2026-10-05：弹窗大多以白色（浅色）或灰黑色（深色）为主，标题、确认按钮等别的颜色
 * 「要让这些真正的颜色的粒子显得更加突出一些，就像之前我们做的粒子消散/出现动画一样」。
 *
 * 口径照搬流体样式（[ParticleMicroflakeContent] 与 `rules.properties` 的同名参数）：
 * - 本体色：快照里出现最多的颜色（各通道 16 级量化），占比在 [PANEL_MIN_FRACTION] → [PANEL_FULL_FRACTION] 之间平滑给出置信度；
 * - 内容权重：饱和度项 + (与本体色之差 − 饱和度项) × 置信度。
 *
 * 飘散只在有明确本体色时强调，强调量 = 置信度 × 内容权重：照片类卡片没有本体色，保持源码原样（参考录像的对照也因此不受影响）。
 * 强调的四种手段与流体相同：有色区域多出颗粒（最多 [COPIES] 倍，见 [Analysis.replicas]）、更大、更不透明、寿命略长；
 * 本体色颗粒更细、更通透。后三者在 `drift-update.comp` 里按每颗粒子的颜色算。
 *
 * 只在 GL 线程上、拿到快照字节之后调用一次；取样上限约 6.5 万个像素，耗时在毫秒量级。
 */
internal object ParticleDriftContent {

    const val PANEL_MIN_FRACTION = .24f
    const val PANEL_FULL_FRACTION = .60f
    const val DISTANCE_START = .06f
    const val DISTANCE_SPAN = .26f

    /** 有色区域每个格子最多多出的颗粒倍数（流体样式的 `content_copies`）。 */
    const val COPIES = 2

    /** 格子数的上限（每边）；分布表最多 64 × 64 项。 */
    const val GRID = 64

    /** 每个格子里取样的点数（每边）。 */
    private const val CELL_SAMPLES = 4

    /** 副本连同原有颗粒的总数上限。 */
    const val MAX_TOTAL = 75000

    class Analysis(
        /** 本体色（0–1，直通）。 */
        val panel: FloatArray,
        /** 本体色的置信度（0–1）；0 表示没有明确本体色，不做强调。 */
        val panelWeight: Float,
        val gridWidth: Int,
        val gridHeight: Int,
        /** 副本落在各格子的累积分布（行优先，最后一项为 1）；没有副本时为空。 */
        val cumulative: FloatArray,
        /** 副本颗粒数（在原有颗粒之外追加）。 */
        val replicas: Int
    ) {
        companion object {
            val NONE = Analysis(FloatArray(3), 0f, 1, 1, FloatArray(0), 0)
        }
    }

    /**
     * [rgba]：预乘 RGBA8（R 在低字节，`Bitmap.copyPixelsToBuffer` 的输出），与上传给着色器的是同一份。
     * 本体色只取不透明像素（预乘与直通相同）；格子取样处半透明时先除以 alpha 还原直通颜色，再按 alpha 加权。
     */
    fun analyse(width: Int, height: Int, rgba: ByteBuffer, baseCount: Int): Analysis {
        if (width <= 0 || height <= 0) return Analysis.NONE
        fun channel(index: Int, k: Int) = rgba.get(index * 4 + k).toInt() and 255

        // 1. 本体色：16 级量化的众数
        val stride = ceil(sqrt(width.toDouble() * height / 65536.0)).toInt().coerceAtLeast(1)
        val counts = IntArray(4096)
        val sums = Array(3) { LongArray(4096) }
        var opaque = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val i = y * width + x
                if (channel(i, 3) == 255) {
                    val r = channel(i, 0); val g = channel(i, 1); val b = channel(i, 2)
                    val key = (r / 16) * 256 + (g / 16) * 16 + b / 16
                    counts[key]++
                    sums[0][key] += r.toLong(); sums[1][key] += g.toLong(); sums[2][key] += b.toLong()
                    opaque++
                }
                x += stride
            }
            y += stride
        }
        if (opaque == 0) return Analysis.NONE
        var best = 0
        for (key in counts.indices) if (counts[key] > counts[best]) best = key
        val panel = FloatArray(3) { (sums[it][best].toDouble() / counts[best] / 255.0).toFloat() }
        val fraction = counts[best].toFloat() / opaque
        val panelWeight = smooth((fraction - PANEL_MIN_FRACTION) / (PANEL_FULL_FRACTION - PANEL_MIN_FRACTION))
        if (panelWeight <= .01f) return Analysis(panel, 0f, 1, 1, FloatArray(0), 0)

        // 2. 每个格子的强调量 = COPIES × 置信度 × 平均内容权重（透明处为 0）
        val cellPx = max(1f, max(width, height).toFloat() / GRID)
        val gridWidth = ceil(width / cellPx).toInt().coerceIn(1, GRID)
        val gridHeight = ceil(height / cellPx).toInt().coerceIn(1, GRID)
        val density = FloatArray(gridWidth * gridHeight)
        var total = 0.0
        for (j in 0 until gridHeight) for (i in 0 until gridWidth) {
            var sum = 0f
            for (sy in 0 until CELL_SAMPLES) for (sx in 0 until CELL_SAMPLES) {
                val px = (((i + (sx + .5f) / CELL_SAMPLES) / gridWidth) * width).toInt().coerceIn(0, width - 1)
                val py = (((j + (sy + .5f) / CELL_SAMPLES) / gridHeight) * height).toInt().coerceIn(0, height - 1)
                val index = py * width + px
                val a = channel(index, 3)
                if (a == 0) continue
                val alpha = a.toFloat()
                sum += weight(min(channel(index, 0) / alpha, 1f), min(channel(index, 1) / alpha, 1f),
                    min(channel(index, 2) / alpha, 1f), panel, panelWeight) * (a / 255f)
            }
            val d = COPIES * panelWeight * sum / (CELL_SAMPLES * CELL_SAMPLES)
            density[j * gridWidth + i] = d
            total += d
        }
        val cells = gridWidth * gridHeight
        val replicas = min((baseCount * total / cells).roundToInt(), MAX_TOTAL - baseCount).coerceAtLeast(0)
        if (replicas == 0 || total <= 0.0) return Analysis(panel, panelWeight, gridWidth, gridHeight, FloatArray(0), 0)
        val cumulative = FloatArray(cells)
        var running = 0.0
        for (k in 0 until cells) {
            running += density[k]
            cumulative[k] = (running / total).toFloat()
        }
        cumulative[cells - 1] = 1f
        return Analysis(panel, panelWeight, gridWidth, gridHeight, cumulative, replicas)
    }

    /** 与 `drift-update.comp` 的 `contentWeight` 同一公式（不乘 alpha）。 */
    fun weight(r: Float, g: Float, b: Float, panel: FloatArray, panelWeight: Float): Float {
        val chroma = smooth((max(r, max(g, b)) - min(r, min(g, b)) - .30f) / .42f)
        val difference = max(abs(r - panel[0]), max(abs(g - panel[1]), abs(b - panel[2])))
        val contrast = smooth((difference - DISTANCE_START) / DISTANCE_SPAN)
        return chroma + (contrast - chroma) * panelWeight
    }

    private fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
