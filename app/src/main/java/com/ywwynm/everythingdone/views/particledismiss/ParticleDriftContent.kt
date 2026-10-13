package com.ywwynm.everythingdone.views.particledismiss

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
 * 飘散只在有明确本体色时强调：照片类卡片没有本体色，保持源码原样（参考录像的对照也因此不受影响）。
 * 此处仍按原内容权重分配副本候选（最多 [COPIES] 倍，见 [Analysis.replicas]），保留分布与粒子索引。
 * `drift-update.comp` 按 RGB 通道差 0.04–0.20 平滑拒绝黑白灰副本，并撤去其尺寸、不透明度和寿命增益；
 * 彩色强调继续保留。本体色颗粒仍按原权重更细、更通透，不把撤去强调的文字重新归为本体。
 *
 * 每次动画分析一次（后台线程）；两遍合计读至多约 13 万个像素（整屏快照约 8.6 万个），耗时在毫秒量级。
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

    /** 副本与基础颗粒一同按 1.6 dp 新基准减量：原 75,000 × 49/64，取最近整数。 */
    const val MAX_TOTAL = 57422

    class Analysis(
        /** 本体色（0–1，直通）。 */
        val panel: FloatArray,
        /** 本体色的置信度（0–1）；0 表示没有明确本体色，不做强调。 */
        val panelWeight: Float,
        val gridWidth: Int,
        val gridHeight: Int,
        /** 副本落在各格子的累积分布（行优先，最后一项为 1）；没有副本时为空。 */
        val cumulative: FloatArray,
        /** 副本候选数（在原有颗粒之外追加；着色器还会拒绝低彩度副本）。 */
        val replicas: Int
    ) {
        companion object {
            val NONE = Analysis(FloatArray(3), 0f, 1, 1, FloatArray(0), 0)
        }
    }

    /** 后台单线程：内容分析只读快照字节，与 GL 线程上的上传同时进行。 */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ParticleDriftContent").apply { isDaemon = true }
    }

    /** 在后台线程上分析；[rgba] 复制一份独立的读写位置，GL 线程同时拿原缓冲上传也互不影响。 */
    fun analyseAsync(width: Int, height: Int, rgba: ByteBuffer, baseCount: Int): Future<Analysis> {
        val view = rgba.duplicate()
        val queued = if (ParticleBeatTrace.enabled) System.nanoTime() else 0L
        return worker.submit<Analysis> {
            if (queued == 0L || width <= 0 || height <= 0) analyse(width, height, view, baseCount)
            else {
                val began = System.nanoTime()
                val cpu = android.os.Debug.threadCpuTimeNanos()
                val samples = samplesFrom(view, samplePositions(width, height))
                val sampled = System.nanoTime()
                val result = analyseSamples(width, height, samples, baseCount)
                ParticleBeatTrace.record(ParticleBeatTrace.CONTENT_ANALYSIS, queued, began, sampled,
                    System.nanoTime(), android.os.Debug.threadCpuTimeNanos() - cpu)
                result
            }
        }
    }

    /**
     * [rgba]：预乘 RGBA8（R 在低字节，`Bitmap.copyPixelsToBuffer` 的输出），与上传给着色器的是同一份。
     * 本体色只取不透明像素（预乘与直通相同）；格子取样处半透明时先除以 alpha 还原直通颜色，再按 alpha 加权。
     */
    fun analyse(width: Int, height: Int, rgba: ByteBuffer, baseCount: Int): Analysis {
        if (width <= 0 || height <= 0) return Analysis.NONE
        return analyseSamples(width, height, samplesFrom(rgba, samplePositions(width, height)), baseCount)
    }

    /** 零拷贝快照那条路：样本由 GPU 按 [samplePositions] 取好（`drift-gather.comp`），在后台线程上分析。 */
    fun analyseSamplesAsync(width: Int, height: Int, samples: IntArray, baseCount: Int): Future<Analysis> =
        worker.submit<Analysis> { analyseSamples(width, height, samples, baseCount) }

    /**
     * 分析要读的全部像素（行优先下标），按读取顺序排：先是第 1 遍的步长网格（行优先），再是第 2 遍各格子里的
     * [CELL_SAMPLES]² 个点（格子行优先、格内行优先）。只取决于尺寸，可以先交给 GPU 去取（整屏快照约 8.6 万个）。
     */
    fun samplePositions(width: Int, height: Int): IntArray = layout(width, height).positions

    /** [rgba] 里 [positions] 处的像素，R 在低字节打包成 Int（与 `drift-gather.comp` 的 `packUnorm4x8` 同一排法）。 */
    fun samplesFrom(rgba: ByteBuffer, positions: IntArray): IntArray {
        if (positions.isEmpty()) return IntArray(0)
        if (rgba.isDirect && ParticleMaterialNative.enabled)
            return ParticleMaterialNative.gatherRgba(rgba, rgba.limit(), positions)
        // Android 冷启动时逐点 DirectByteBuffer.getInt 可占约 180ms；一次批量读取后
        // 按完全相同的下标取样。保留字节序、预乘值与取样顺序，不改变分析输入。
        val words = rgba.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        words.position(0)
        val pixels = IntArray(words.limit() / 4)
        words.asIntBuffer().get(pixels)
        return IntArray(positions.size) { pixels[positions[it]] }
    }

    /** [samples] 与 [samplePositions] 一一对应；无论样本从字节还是从 GPU 取来，结果逐位相同。 */
    fun analyseSamples(width: Int, height: Int, samples: IntArray, baseCount: Int): Analysis {
        if (width <= 0 || height <= 0) return Analysis.NONE
        val layout = layout(width, height)
        require(samples.size == layout.positions.size) { "样本数 ${samples.size} ≠ 取样点数 ${layout.positions.size}" }
        fun channel(k: Int, c: Int) = (samples[k] ushr (8 * c)) and 255

        // 1. 本体色：16 级量化的众数
        val counts = IntArray(4096)
        val sums = Array(3) { LongArray(4096) }
        var opaque = 0
        for (k in 0 until layout.first) {
            if (channel(k, 3) == 255) {
                val r = channel(k, 0); val g = channel(k, 1); val b = channel(k, 2)
                val key = (r / 16) * 256 + (g / 16) * 16 + b / 16
                counts[key]++
                sums[0][key] += r.toLong(); sums[1][key] += g.toLong(); sums[2][key] += b.toLong()
                opaque++
            }
        }
        if (opaque == 0) return Analysis.NONE
        var best = 0
        for (key in counts.indices) if (counts[key] > counts[best]) best = key
        val panel = FloatArray(3) { (sums[it][best].toDouble() / counts[best] / 255.0).toFloat() }
        val fraction = counts[best].toFloat() / opaque
        val panelWeight = smooth((fraction - PANEL_MIN_FRACTION) / (PANEL_FULL_FRACTION - PANEL_MIN_FRACTION))
        if (panelWeight <= .01f) return Analysis(panel, 0f, 1, 1, FloatArray(0), 0)

        // 2. 每个格子的强调量 = COPIES × 置信度 × 平均内容权重（透明处为 0）
        val gridWidth = layout.gridWidth
        val gridHeight = layout.gridHeight
        val density = FloatArray(gridWidth * gridHeight)
        // 面板、文字与按钮的大量样本颜色完全相同。只缓存本次分析内完整 RGBA 的权重，
        // 不量化颜色；槽位冲突时重算并覆盖，因此仍与逐样本计算逐位相同。
        val weightColors = IntArray(256)
        val weightValues = FloatArray(256)
        val weightValid = BooleanArray(256)
        var total = 0.0
        var k = layout.first
        for (j in 0 until gridHeight) for (i in 0 until gridWidth) {
            var sum = 0f
            repeat(CELL_SAMPLES * CELL_SAMPLES) {
                val pixel = samples[k]
                val a = pixel ushr 24
                if (a != 0) {
                    val slot = (pixel * -1640531527) ushr 24
                    if (!weightValid[slot] || weightColors[slot] != pixel) {
                        val alpha = a.toFloat()
                        weightValues[slot] = weight(min((pixel and 255) / alpha, 1f),
                            min(((pixel ushr 8) and 255) / alpha, 1f), min(((pixel ushr 16) and 255) / alpha, 1f),
                            panel, panelWeight) * (a / 255f)
                        weightColors[slot] = pixel
                        weightValid[slot] = true
                    }
                    sum += weightValues[slot]
                }
                k++
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
        for (c in 0 until cells) {
            running += density[c]
            cumulative[c] = (running / total).toFloat()
        }
        cumulative[cells - 1] = 1f
        return Analysis(panel, panelWeight, gridWidth, gridHeight, cumulative, replicas)
    }

    /** 取样布局：只取决于尺寸。最近一次的留着，同一尺寸（例如新建页）反复用。 */
    private class Layout(val width: Int, val height: Int, val first: Int, val gridWidth: Int, val gridHeight: Int,
                         val positions: IntArray)

    @Volatile private var lastLayout: Layout? = null

    private fun layout(width: Int, height: Int): Layout {
        lastLayout?.let { if (it.width == width && it.height == height) return it }
        val stride = ceil(sqrt(width.toDouble() * height / 65536.0)).toInt().coerceAtLeast(1)
        val first = ((width + stride - 1) / stride) * ((height + stride - 1) / stride)
        val cellPx = max(1f, max(width, height).toFloat() / GRID)
        val gridWidth = ceil(width / cellPx).toInt().coerceIn(1, GRID)
        val gridHeight = ceil(height / cellPx).toInt().coerceIn(1, GRID)
        val positions = IntArray(first + gridWidth * gridHeight * CELL_SAMPLES * CELL_SAMPLES)
        var k = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                positions[k++] = y * width + x
                x += stride
            }
            y += stride
        }
        for (j in 0 until gridHeight) for (i in 0 until gridWidth) {
            for (sy in 0 until CELL_SAMPLES) for (sx in 0 until CELL_SAMPLES) {
                val px = (((i + (sx + .5f) / CELL_SAMPLES) / gridWidth) * width).toInt().coerceIn(0, width - 1)
                val py = (((j + (sy + .5f) / CELL_SAMPLES) / gridHeight) * height).toInt().coerceIn(0, height - 1)
                positions[k++] = py * width + px
            }
        }
        check(k == positions.size)
        return Layout(width, height, first, gridWidth, gridHeight, positions).also { lastLayout = it }
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
