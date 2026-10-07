package com.ywwynm.everythingdone.views.particledismiss

import android.content.res.AssetManager
import org.json.JSONObject
import java.util.Random
import kotlin.math.abs

/**
 * 飘散参考库：十二段录像估计出的格点值、风力与颗粒衰减方式。
 * 正常播放先筛方向、去掉灭霸和近期重复来源，再按 [referenceProbability] 决定是否用参考；
 * 命中后从候选里等概率选一条。原版与变体共用一个来源份额，不额外增加总参考概率。
 * [ParticleDriftSequence] 负责让候选与计数反映实际开播时的播放记录。
 */
internal object ParticleDriftStyles {

    /** 本次方向与参考风向的夹角上限（度）：八个方向扇区的一半。 */
    const val MATCH_DEGREES = 22.5f

    /** 每增加一个可用参考来源，总概率增加 12 个百分点，最多 36%。 */
    const val REFERENCE_PROBABILITY_PER_SOURCE = 0.12f
    const val MAX_REFERENCE_PROBABILITY = 0.36f

    fun referenceProbability(sourceCount: Int): Float =
        (sourceCount.coerceAtLeast(0) * REFERENCE_PROBABILITY_PER_SOURCE).coerceAtMost(MAX_REFERENCE_PROBABILITY)

    /** 用户最喜欢的那段参考：旧钢铁侠。 */
    const val FAVORITE = "ironman"

    /** 变体的名字（日志与调试键用）：`ironman-variant`。 */
    const val VARIANT_SUFFIX = "-variant"

    /** 支持变体的来源里，原版占 40%，其余为变体；在选定参考来源之后才判断。 */
    const val ORIGINAL_SHARE = 0.4f

    /** 「朝上一组」：风向离正上方（90°）不超过这么多度的参考。 */
    const val UP_FAMILY_HALF_SPAN = 35f

    /** 格点值低于它算一处初始消逝点（最先化开的地方）。 */
    const val START_POINT_VALUE = 0.12f

    /** 变体对全部格点值的整体抖动幅度。 */
    const val VARIANT_JITTER = 0.06f

    /** 变体的风力在参考的基础上上下浮动的比例。 */
    const val VARIANT_WIND_RANGE = 0.15f

    class Style(
        val name: String,
        /** 风向（度，y 向上，0 = 右、90 = 上）。 */
        val angle: Float,
        /** 风力大小（工作面单位）。 */
        val wind: Float,
        /** 参考卡片的快照尺寸（设备像素）。 */
        val width: Int,
        val height: Int,
        /** 参考的扩展画布尺寸（设备像素）；湍流取样按它换算到参考卡片自己的坐标（[ParticleDriftModel.turbulenceFrame]）。 */
        val expansionWidth: Float,
        val expansionHeight: Float,
        /** 值噪声格点值，行优先（y 向下）。 */
        val lattice: FloatArray,
        val latticeWidth: Int,
        val latticeHeight: Int,
        val fadeSizePower: Float,
        val fadeAlphaPower: Float,
        val overAlphaMul: Float
    )

    @Volatile private var loaded: List<Style>? = null

    /**
     * 仅调试包：钉住某段参考样式（名字），不看方向与概率；`random` 表示一律随机生成。
     * 由 [ParticleTuning.apply] 从调试键读入，正式包恒为 null。
     */
    @Volatile var forcedForTests: String? = null

    fun all(assets: AssetManager): List<Style> = loaded ?: synchronized(this) {
        loaded ?: runCatching { parse(assets.open("particle-dismiss/drift-styles.json")
            .bufferedReader().use { it.readText() }) }.getOrElse { emptyList() }.also { loaded = it }
    }

    fun parse(text: String): List<Style> {
        val array = JSONObject(text).getJSONArray("styles")
        return (0 until array.length()).map { k ->
            val o = array.getJSONObject(k)
            val rows = o.getJSONArray("lattice")
            val ny = rows.length()
            val nx = rows.getJSONArray(0).length()
            val lattice = FloatArray(nx * ny)
            for (j in 0 until ny) {
                val row = rows.getJSONArray(j)
                for (i in 0 until nx) lattice[j * nx + i] = row.getDouble(i).toFloat()
            }
            val size = o.getJSONArray("size")
            val expansion = o.optJSONArray("expansion")
            Style(o.getString("name"), o.getDouble("angle").toFloat(), o.getDouble("wind").toFloat(),
                size.getInt(0), size.getInt(1),
                expansion?.getDouble(0)?.toFloat() ?: size.getInt(0) * 1.25f,
                expansion?.getDouble(1)?.toFloat() ?: size.getInt(1) * 1.25f,
                lattice, nx, ny,
                o.optDouble("fadeSizePower", .5).toFloat(), o.optDouble("fadeAlphaPower", .5).toFloat(),
                o.optDouble("overAlphaMul", ParticleDriftRenderer.OVER_ALPHA_MUL.toDouble()).toFloat())
        }
    }

    /** 两个方向角之差，折进 [−180, 180)。 */
    fun angleDifference(a: Float, b: Float): Float = ((a - b + 540f) % 360f) - 180f

    /** 本次方向 [angleDegrees] 与参考 [style] 的风向相差不超过 [MATCH_DEGREES]。 */
    private fun matches(style: Style, angleDegrees: Float): Boolean =
        abs(angleDifference(style.angle, angleDegrees)) <= MATCH_DEGREES

    /** 朝上一组的成员（风向离正上方不超过 [UP_FAMILY_HALF_SPAN]）。 */
    fun isUpFamily(style: Style): Boolean = style.name != FAVORITE && abs(angleDifference(style.angle, 90f)) <= UP_FAMILY_HALF_SPAN

    /** 变体与原样属于同一参考来源；显示名字中附带的起点说明不参与去重。 */
    fun referenceName(name: String?): String? = name?.substringBefore(VARIANT_SUFFIX)

    /** 按本次方向挑参考；排除灭霸与近期播放过的来源，没有合适候选时随机生成。 */
    fun pick(styles: List<Style>, angleDegrees: Float, random: Random,
             excludedReferences: Set<String> = emptySet()): Style? {
        forcedForTests?.let { name ->
            // 「<样式名>-variant」钉住该样式的变体
            if (name.endsWith(VARIANT_SUFFIX)) {
                val base = name.removeSuffix(VARIANT_SUFFIX)
                return styles.firstOrNull { it.name == base }?.let { variantOf(it, random) }
            }
            return styles.firstOrNull { it.name == name }
        }
        val candidates = styles.filter {
            val source = referenceName(it.name)
            source != "thanos" && source !in excludedReferences && matches(it, angleDegrees)
        }.distinctBy { referenceName(it.name) }
        if (candidates.isEmpty() || random.nextFloat() >= referenceProbability(candidates.size)) return null
        val selected = candidates[random.nextInt(candidates.size)]
        return if ((selected.name == FAVORITE || isUpFamily(selected)) && random.nextFloat() >= ORIGINAL_SHARE)
            variantOf(selected, random) else selected
    }

    /**
     * 在 [base] 的基础上生成一个变体（任意一段参考都可以，旧钢铁侠一族与朝上一组用到）：
     * 格点值整体抖一点（[VARIANT_JITTER]）；初始消逝点（格点值低于 [START_POINT_VALUE]
     * 的格点）去掉一处、不变、加一处或加两处；风力上下浮动 [VARIANT_WIND_RANGE]。颗粒的衰减方式与湍流场不变，
     * 飘走的轨迹仍是原来那一类。
     *
     * 增减只动参考卡片**可见范围里**的格点：格点间距约 460 像素，参考卡片右边与下边那一排格点在卡片外，只影响边缘。
     * 旧钢铁侠卡片内只有一处起点，另外三处在右边那排卡片外的格点上（于是右缘先化开）；去掉卡片内那一处之后
     * 就只从右缘开始，所以「至少留一处」按全部格点算。
     */
    fun variantOf(base: Style, random: Random): Style {
        val lattice = FloatArray(base.lattice.size) {
            (base.lattice[it] + (random.nextFloat() * 2f - 1f) * VARIANT_JITTER).coerceIn(0f, 1f)
        }
        val spacing = 1f / ParticleDriftModel.latticeScale(base.width, base.height)
        val visible = lattice.indices.filter {
            (it % base.latticeWidth) * spacing <= base.width && (it / base.latticeWidth) * spacing <= base.height
        }
        val starts = visible.filter { lattice[it] < START_POINT_VALUE }.toMutableList()
        val others = visible.filter { lattice[it] >= START_POINT_VALUE }.toMutableList()
        val allStarts = lattice.count { it < START_POINT_VALUE }
        var change = random.nextInt(4) - 1
        if (change < 0) {
            if (starts.isNotEmpty() && allStarts > 1) {
                lattice[starts.removeAt(random.nextInt(starts.size))] = .3f + random.nextFloat() * .3f
            } else change = 0
        } else repeat(change) {
            if (others.isNotEmpty()) lattice[others.removeAt(random.nextInt(others.size))] = random.nextFloat() * .06f
        }
        val wind = base.wind * (1f + (random.nextFloat() * 2f - 1f) * VARIANT_WIND_RANGE)
        val label = if (change == 0) "起点不变" else "起点%+d".format(change)
        return Style("${base.name}$VARIANT_SUFFIX（$label）", base.angle, wind, base.width, base.height,
            base.expansionWidth, base.expansionHeight, lattice, base.latticeWidth, base.latticeHeight,
            base.fadeSizePower, base.fadeAlphaPower, base.overAlphaMul)
    }

    /**
     * 把参考的格点值套到 [width] × [height] 的快照上：本卡片每个格点按它在快照里的**归一化位置**去参考卡片上取
     * 同一处的噪声值（与着色器同一个平滑插值），再把本卡片可见范围内噪声的最小、最大值仿射对齐到参考卡片上的，
     * 于是「哪里先化开、哪里最后化开」与化开的起止时刻都跟着参考走。
     */
    fun resample(style: Style, width: Int, height: Int): FloatArray {
        val size = ParticleDriftModel.latticeSize(width, height)
        val nx = size[0]
        val ny = size[1]
        val u = ParticleDriftModel.latticeScale(width, height)
        val uRef = ParticleDriftModel.latticeScale(style.width, style.height)
        val out = FloatArray(nx * ny)
        for (j in 0 until ny) for (i in 0 until nx) {
            val rx = i / u / width * style.width * uRef
            val ry = j / u / height * style.height * uRef
            out[j * nx + i] = ParticleDriftModel.noise(style.lattice, style.latticeWidth, style.latticeHeight, rx, ry)
        }
        val reference = ParticleDriftModel.noiseRange(style.lattice, style.latticeWidth, style.latticeHeight,
            style.width, style.height, uRef)
        val own = ParticleDriftModel.noiseRange(out, nx, ny, width, height, u)
        val span = own[1] - own[0]
        if (span > 1e-4f) {
            val scale = (reference[1] - reference[0]) / span
            for (k in out.indices) out[k] = (reference[0] + (out[k] - own[0]) * scale).coerceIn(-.5f, 1.5f)
        }
        return out
    }
}
