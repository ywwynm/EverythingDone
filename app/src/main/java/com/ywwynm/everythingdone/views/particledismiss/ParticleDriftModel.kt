package com.ywwynm.everythingdone.views.particledismiss

import java.util.Random
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 飘散样式一次动画的输入（CPU 侧；全是纯算式，可在 JVM 上定死）。
 *
 * 华为源码（`docs/features/dialog-particle-dismiss/original-code-locations-2026-10-04.md`）里一次动画的随机输入只有两项：
 * 消蚀噪声的随机数（决定值噪声的格点值）与横风 `randomInteger(−30000, 30000)`（纵风固定 40000）。移植时
 * （`docs/features/harmony-particle-android-port/decisions.md`）：
 * - 格点值在 CPU 上直接给出（参考样式库 [ParticleDriftStyles] 或随机），着色器不用源码的 sin 哈希；
 * - 风的方向按现有方向规则（规格里的虚拟触点方向），大小仍取源码范围；选用参考样式时风力取参考的、风向仍按本次方向；
 * - 合成方式（加光 / 标准 src-over）不在这里定：按背景亮度取样（[ParticleDriftBackground]），由会话在开画之前交给渲染器；
 * - 粒子数与桌面实现同一公式：按设备像素面积保持 4×4 卡片 4 万颗 / 1.3M 设备像素²的密度，取整到千颗，截在 1～5 万；
 *   数量仍按原设备像素面积换算；绘制尺寸另作显示密度补偿。设置里的「粒子数量」倍率（0.1～1.6）
 *   乘在截好的粒子数上，任何尺寸的内容都按这个倍率变；大小设置为 0.5～6 倍，直接缩放每颗粒子。
 *
 * - 触点远近（用户 2026-10-06：「消逝终点位置和消逝终点方向是两码事，位置离控件距离的不同会让动画也出现不同才对」）
 *   与流体样式同口径（`step.comp` 的 `distance_gain` 与 `destination`）：触点在该方向可用背景里越远，风越强
 *   （[TOUCH_GAIN_BASE] + [TOUCH_GAIN_RANGE] × 远近，0.55～1.45 倍），颗粒飞得越远；有真实触点时每颗颗粒的风向
 *   按「出生位置 → 终点」的方位温和偏转（着色器 `TOUCH_TURN`），终点越近越往那一点收拢。没有真实触点（返回键）时
 *   风力倍数为 1、不偏转，与源码相同。
 *
 * 时间一律是源码的设备时刻（秒）：HDS 进度动画 2 秒，消蚀阈值 = 进度 × 2.1。
 */
internal class ParticleDriftModel(
    val snapshotWidth: Int,
    val snapshotHeight: Int,
    /** 值噪声的格点值，行优先，[latticeWidth] × [latticeHeight]。 */
    val lattice: FloatArray,
    val latticeWidth: Int,
    val latticeHeight: Int,
    /** 风（工作面单位，y 向下），对应源码 `aeWind`。 */
    val windX: Float,
    val windY: Float,
    val count: Int,
    /** 寿命末段衰减进尺寸、不透明度的幂（源码按两者各 0.5 分配；旧钢铁侠参考只缩小、不变淡：0.5 / 0）。 */
    val fadeSizePower: Float = .5f,
    val fadeAlphaPower: Float = .5f,
    /** 标准 src-over 时颗粒层 alpha 的倍数（背景亮、选了 src-over 时才用到）。 */
    val overAlphaMul: Float = ParticleDriftRenderer.OVER_ALPHA_MUL,
    /** 选用的参考样式；随机生成时为 null。只用于日志与验收。 */
    val styleName: String? = null,
    /**
     * 湍流取样位置的偏移（噪声空间单位）。源码的湍流场只取决于颗粒的索引与位置，同一尺寸的卡片每次飘出去的形状都一样；
     * 随机生成时加一个每次动画不同的偏移，参考样式保持 0（与源码、与参考录像一致）。
     */
    val turbulenceOffsetX: Float = 0f,
    val turbulenceOffsetY: Float = 0f,
    /**
     * 选用参考样式时，参考卡片在它自己工作面上的发射区：x、y、宽、高（工作面像素）。湍流按「粒子在卡片上的归一化位置
     * → 参考卡片上同一处」取样，每颗粒子取到的湍流与原片里对应的那颗相同，卷曲与翻折的形状于是跟着参考走；
     * 位移仍加在本卡片上。随机生成时为 null（按本卡片自己的位置取样，与源码相同）。
     */
    val turbulenceFrame: FloatArray? = null,
    /** 用户大小倍率与显示密度补偿的乘积；只影响颗粒的绘制半宽。 */
    val sizeScale: Float = 1f,
    /** 真实触点到控件边缘的距离（以短边为单位，[ParticleDismissSpec.touchGap]）；null 表示没有真实触点，颗粒不向终点偏转。 */
    val touchGap: Float? = null
) {
    /** 源码消蚀着色器的 `u_Scale`。 */
    val scaleU: Float = latticeScale(snapshotWidth, snapshotHeight)

    private val range = noiseRange(lattice, latticeWidth, latticeHeight, snapshotWidth, snapshotHeight, scaleU)

    /** 快照上最大的噪声值 n = noise × 0.8 + 0.2。 */
    val maxMaskValue: Float = range[1] * .8f + .2f

    /**
     * 第一处开始变淡的设备时刻（秒）：最小的 n 减去柔边 0.15 处阈值追上来。源码时间轴从 0 开始，到这一刻之前卡片纹丝不动；
     * 压到 1 秒之后这一段有 0.1 秒上下，左滑时手指移动的前一小段也没有反应。动画从这一刻起算，外观不变，只去掉开头的空白。
     */
    val startSeconds: Float = max(0f, (range[0] * .8f + .2f - SOFTNESS) / RATE_PER_SECOND)

    /** 卡片本体完全消失的设备时刻（秒）：最晚的那一处遮罩降到 0。 */
    val bodyGoneSeconds: Float = maxMaskValue / RATE_PER_SECOND

    /**
     * 最后一颗粒子消失的设备时刻（秒），即整段动画的长度。出生越晚死得越晚（寿命随出生进度缩短的速度慢于出生推迟），
     * 所以只看最晚出生的那一处：活动时长 = T − 出生 + 一帧，≥ 寿命时消失；这里多留一帧余量。
     */
    val totalSeconds: Float = run {
        val born = bornSeconds(maxMaskValue)
        born + lifeAt(born) * LIFE_MAX
    }

    internal companion object {
        /** 源码设备帧率（桌面复刻按 120 Hz 验证）；出生按这个帧率量化。 */
        const val HZ = 120f

        /** 消蚀阈值随设备时刻的增长率：进度 = T / 2（HDS 动画 2 秒），阈值 = 进度 × 2.1。 */
        const val RATE_PER_SECOND = 1.05f

        /** 消蚀遮罩的柔边宽度：n − 阈值在 [0, 0.15] 之间时 alpha 线性过渡。 */
        const val SOFTNESS = 0.15f

        /** 出生条件：遮罩 alpha ≤ 0.9，即 n − 阈值 ≤ 0.9 × 0.15。 */
        const val BIRTH_MARGIN = 0.135f

        const val LIFE_MAX = 0.8f

        /** 源码横风 randomInteger(−30000, 30000)，纵风 40000；工作面单位再乘 0.5。 */
        const val WIND_X_RANGE = 30000f
        const val WIND_Y = 40000f

        /** 桌面实现 `area_particle_count`：4×4 卡片 4 万颗，面积约 1.3M 设备像素²。 */
        const val REFERENCE_CARD_COUNT = 40000.0
        const val REFERENCE_CARD_AREA = 1.3e6
        const val MIN_COUNT = 10000
        const val MAX_COUNT = 50000

        /** 原贴图外框宽度（屏幕像素），只用于迁移旧 dp 设置，不代表软点可见直径。 */
        const val BASE_DIAMETER_PX = 3.5f * .7f * 4f

        /** 新轮廓普通粒子的稳定半峰值直径约 1.4 dp；不是华为设备的已知密度。 */
        private const val SIZE_REFERENCE_DENSITY = 3.5f

        fun sizeScaleForDisplay(multiplier: Float, density: Float): Float {
            require(multiplier.isFinite() && multiplier > 0f && density.isFinite() && density > 0f)
            return multiplier * (density / SIZE_REFERENCE_DENSITY)
        }

        fun sizeScaleForDp(dp: Float, density: Float): Float {
            require(dp.isFinite() && dp > 0f && density.isFinite() && density > 0f)
            return dp * density / BASE_DIAMETER_PX
        }

        /** 风力随触点远近的倍数：0.55 + 0.90 × 远近（远近 0～1，缺省 0.5 即 1 倍），与流体样式的 `distance_gain` 相同。 */
        const val TOUCH_GAIN_BASE = .55f
        const val TOUCH_GAIN_RANGE = .90f

        /** 随机湍流偏移的范围（噪声空间单位；湍流噪声的一个起伏约 1 个单位，取几十个单位即互不相关）。 */
        const val TURBULENCE_OFFSET_RANGE = 64f

        /** 格点数上限（着色器里 uniform 数组的长度）。 */
        const val MAX_LATTICE = 8

        /** 求噪声值域时的取样间距（快照像素）。 */
        private const val NOISE_RANGE_STEP = 8

        fun latticeScale(width: Int, height: Int): Float =
            (205.0 / (width.toDouble() * height.toDouble()) + 0.002).toFloat()

        /** 覆盖快照所需的格点数（含插值用的右 / 下一格），与桌面实现的 `design()` 相同。 */
        fun latticeSize(width: Int, height: Int): IntArray {
            val u = latticeScale(width, height)
            return intArrayOf(
                min(floor(width * u).toInt() + 2, MAX_LATTICE),
                min(floor(height * u).toInt() + 2, MAX_LATTICE))
        }

        /**
         * 按规格的种子建随机数发生器。`java.util.Random` 对相邻的小种子给出几乎相同的第一个数
         * （0、1、2… 的第一个 `nextFloat` 都约为 0.73），调试探针用固定的小种子时样式选取与格点值会被锁死；
         * 先用 SplitMix64 打散。
         */
        fun seededRandom(seed: Int): Random {
            var z = seed.toLong() + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return Random(z xor (z ushr 31))
        }

        /**
         * 源码的发射区（工作面像素）：发射区 = 快照 × 0.5，居中放在半分辨率画布上；画布 = 扩展尺寸取整。
         * 返回 x、y、宽、高，与 `harmony_native.py` 的 `position` / `emit_size` 相同。
         */
        fun emitFrame(snapshotWidth: Int, snapshotHeight: Int, expansionWidth: Float, expansionHeight: Float): FloatArray {
            val emitW = snapshotWidth * .5f
            val emitH = snapshotHeight * .5f
            return floatArrayOf((floor(expansionWidth) * .5f - emitW) * .5f, (floor(expansionHeight) * .5f - emitH) * .5f,
                emitW, emitH)
        }

        /** 第一个遮罩 alpha ≤ 0.9 的设备帧时刻（秒）。 */
        fun bornSeconds(n: Float): Float =
            max(1f, ceil(HZ * (n - BIRTH_MARGIN) / RATE_PER_SECOND - 1e-4f)) / HZ

        /** 源码寿命系数：`(1 − 进度) × centers1`，进度 = T / 2。 */
        fun lifeAt(bornSeconds: Float): Float = 1f - min(bornSeconds * .5f, 1f)

        /**
         * 与桌面实现的 `area_particle_count` 逐项相同：4 万 × 面积 / 1.3M，按千颗取整（Python 的 round，四舍六入五成双），
         * 截在 [1 万, 5 万]；再乘设置里的倍率 [amount]（四舍五入到整数）。倍率乘在截好之后，0.1～1.6 在小卡片与整屏内容上都起作用；
         * 缺省 1 时与桌面完全相同。
         */
        fun count(widthPx: Int, heightPx: Int, amount: Float): Int {
            val area = widthPx.toDouble() * heightPx
            val thousands = Math.rint(REFERENCE_CARD_COUNT * area / REFERENCE_CARD_AREA / 1000.0)
            val base = (thousands * 1000.0).toInt().coerceIn(MIN_COUNT, MAX_COUNT)
            return (base * amount.toDouble()).roundToInt().coerceAtLeast(1)
        }

        /** 与着色器 `driftNoise` 相同的平滑插值值噪声。 */
        fun noise(lattice: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
            val ix = floor(x); val iy = floor(y)
            var fx = x - ix; var fy = y - iy
            fx = fx * fx * (3f - 2f * fx); fy = fy * fy * (3f - 2f * fy)
            fun at(i: Int, j: Int) = lattice[j.coerceIn(0, h - 1) * w + i.coerceIn(0, w - 1)]
            val i0 = ix.toInt(); val j0 = iy.toInt()
            val top = at(i0, j0) + (at(i0 + 1, j0) - at(i0, j0)) * fx
            val bottom = at(i0, j0 + 1) + (at(i0 + 1, j0 + 1) - at(i0, j0 + 1)) * fx
            return top + (bottom - top) * fy
        }

        /**
         * 在快照上按 8 像素的格子取样，求噪声值（未乘 0.8、未加 0.2）的最小、最大值。
         *
         * 一个格点单元里噪声是平滑插值后的双线性函数：固定一个方向，沿另一个方向是单调的，所以单元里所有样点的极值
         * 都落在这个单元样点子网格的四个角上。只算各单元首末两行、首末两列交出的样点，与逐点扫描在精确算术下相同
         * （浮点上至多差 1 ulp，只在各格点值相差几个 ulp 的人造格点上出现过）；全屏快照从 6.9 万次取样降到几十次。
         */
        fun noiseRange(lattice: FloatArray, w: Int, h: Int, width: Int, height: Int, u: Float): FloatArray {
            val xs = cellEnds(width, u)
            val ys = cellEnds(height, u)
            var low = Float.MAX_VALUE
            var high = -Float.MAX_VALUE
            for (y in ys) for (x in xs) {
                val value = noise(lattice, w, h, x, y)
                low = min(low, value)
                high = max(high, value)
            }
            return floatArrayOf(low, high)
        }

        /** 沿一个方向每 8 像素取一个样点（取样坐标 (像素 + 0.5) × [u]），返回每个格点单元里首、末两个样点的坐标。 */
        private fun cellEnds(length: Int, u: Float): FloatArray {
            val ends = FloatArray(2 * ((length + NOISE_RANGE_STEP - 1) / NOISE_RANGE_STEP))
            var size = 0
            var cell = Int.MIN_VALUE
            var x = 0
            while (x < length) {
                val at = (x + .5f) * u
                val k = floor(at).toInt()
                if (k != cell) {
                    ends[size] = at; ends[size + 1] = at; size += 2; cell = k
                } else {
                    ends[size - 1] = at
                }
                x += NOISE_RANGE_STEP
            }
            return ends.copyOf(size)
        }

        /**
         * 生成一次动画的输入：方向合适时按概率选用参考样式（[ParticleDriftStyles.pick]），否则随机。
         *
         * [directionX] / [directionY]：消散方向（叠加层像素，y 向下），不必归一化；出现动画传的是倒放之前的正向方向。
         * 选用参考样式时风力大小取参考的，风向仍是本次方向（与参考相差不超过 [ParticleDriftStyles.MATCH_DEGREES]）。
         * [releaseX] / [releaseY]：左滑时手指按下处（快照像素），那一处的格点取低值，最先化开。
         */
        fun random(width: Int, height: Int, directionX: Float, directionY: Float,
                   amount: Float, random: Random, styles: List<ParticleDriftStyles.Style> = emptyList(),
                   releaseX: Float? = null, releaseY: Float? = null, size: Float = 1f,
                   touchGap: Float? = null, touchStrength: Float? = null,
                   excludedReferences: Set<String> = emptySet()): ParticleDriftModel {
            val grid = latticeSize(width, height)
            val length = hypot(directionX, directionY)
            val ux = if (length > 1e-6f) directionX / length else 0f
            val uy = if (length > 1e-6f) directionY / length else -1f
            val angle = Math.toDegrees(atan2(-uy.toDouble(), ux.toDouble())).toFloat()
            val style = ParticleDriftStyles.pick(styles, angle, random, excludedReferences)
            val lattice = style?.let { ParticleDriftStyles.resample(it, width, height) }
                ?: FloatArray(grid[0] * grid[1]) { random.nextFloat() }
            if (releaseX != null && releaseY != null) {
                val u = latticeScale(width, height)
                val i = (releaseX * u).roundToInt().coerceIn(0, grid[0] - 1)
                val j = (releaseY * u).roundToInt().coerceIn(0, grid[1] - 1)
                lattice[j * grid[0] + i] = random.nextFloat() * .1f
            }
            val count = count(width, height, amount)
            val gain = TOUCH_GAIN_BASE + TOUCH_GAIN_RANGE * (touchStrength ?: .5f)
            if (style != null) {
                // 风向转到本次方向（与参考相差不超过 MATCH_DEGREES），大小取参考的。
                return ParticleDriftModel(width, height, lattice, grid[0], grid[1],
                    ux * style.wind * gain, uy * style.wind * gain, count,
                    style.fadeSizePower, style.fadeAlphaPower, style.overAlphaMul, style.name,
                    turbulenceFrame = emitFrame(style.width, style.height, style.expansionWidth, style.expansionHeight),
                    sizeScale = size, touchGap = touchGap)
            }
            val windSource = (random.nextFloat() * 2f - 1f) * WIND_X_RANGE
            val magnitude = .5f * hypot(windSource, WIND_Y)
            return ParticleDriftModel(width, height, lattice, grid[0], grid[1],
                ux * magnitude * gain, uy * magnitude * gain, count,
                turbulenceOffsetX = random.nextFloat() * TURBULENCE_OFFSET_RANGE,
                turbulenceOffsetY = random.nextFloat() * TURBULENCE_OFFSET_RANGE,
                sizeScale = size, touchGap = touchGap)
        }
    }
}
