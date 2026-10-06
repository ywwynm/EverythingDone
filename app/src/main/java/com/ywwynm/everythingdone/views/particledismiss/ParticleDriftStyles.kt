package com.ywwynm.everythingdone.views.particledismiss

import android.content.res.AssetManager
import org.json.JSONObject
import java.util.Random
import kotlin.math.abs

/**
 * 飘散样式的参考样式库：十二段参考录像逐段估计出的格点值、风与颗粒衰减方式
 * （`shared/particle-dismiss/drift-styles.json`，由 `docs/features/harmony-particle-android-port/export_styles.py` 生成）。
 *
 * 用户 2026-10-05 裁定：参考样子要在方向合适时**按概率**真的出现，但不能每次都出现。本次方向与某段参考的风向
 * 相差不超过 [MATCH_DEGREES] 时它才是候选；有候选时以 [REFERENCE_PROBABILITY] 的概率从候选里等概率选一段，
 * 否则随机生成格点值与风力。参考的风都朝上（左上、正上、右上），向下或水平的方向只会随机生成。
 *
 * 同日用户补充：最喜欢旧钢铁侠（[FAVORITE]），左上方向（含触摸与返回键退出）要更常出现它和它的变体。落在它的方向范围里时，
 * 先以 [FAVORITE_PROBABILITY] 的概率选这一族：其中 [FAVORITE_ORIGINAL_SHARE] 是原样，其余是 [variantOf] 生成的变体；
 * 没选中这一族才按上面的规则在**其余**参考与随机之间选。
 *
 * 2026-10-06 用户补充：消逝方向是上方、上方偏左、上方偏右（不是左上、右上）的那几段参考，也要更常出现它们和它们的变体。
 * 风向离正上方不超过 [UP_FAMILY_HALF_SPAN] 度的参考是「朝上一组」（参考 5、4、7、1、向上钢铁侠、参考 2，63°～118°）；
 * 本次方向对得上其中几段时，先以 [UP_FAMILY_PROBABILITY] 的概率从对得上的这几段里等概率选一段，原样与变体的比例同上。
 * 旧钢铁侠与朝上一组都对得上时（本次方向在两者之间），看哪一组离本次方向最近的那一段更近；一样近时算旧钢铁侠。
 */
internal object ParticleDriftStyles {

    /** 本次方向与参考风向的夹角上限（度）：八个方向扇区的一半。 */
    const val MATCH_DEGREES = 22.5f

    /** 有候选时选用参考样式的概率。 */
    const val REFERENCE_PROBABILITY = 0.5f

    /** 用户最喜欢的那段参考：旧钢铁侠。 */
    const val FAVORITE = "ironman"

    /** 变体的名字（日志与调试键用）：`ironman-variant`。 */
    const val VARIANT_SUFFIX = "-variant"

    /** 方向落在旧钢铁侠的范围里时，选这一族（原样或变体）的概率。 */
    const val FAVORITE_PROBABILITY = 0.5f

    /** 这一族里原样所占的份额，其余是变体。 */
    const val FAVORITE_ORIGINAL_SHARE = 0.4f

    /** 「朝上一组」：风向离正上方（90°）不超过这么多度的参考。 */
    const val UP_FAMILY_HALF_SPAN = 35f

    /**
     * 方向落在朝上一组里时，选这一组（原样或变体）的概率。正上方向只有朝上一组的参考对得上，没选中时只剩随机生成；
     * 取 0.75 使参考与随机的比例与左上方向相同（旧钢铁侠一族 0.5 + 其余参考 0.25）。
     */
    const val UP_FAMILY_PROBABILITY = 0.75f

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

    /** 按本次方向 [angleDegrees]（度，y 向上）挑参考样式；不选时返回 null。 */
    fun pick(styles: List<Style>, angleDegrees: Float, random: Random): Style? {
        forcedForTests?.let { name ->
            // 「<样式名>-variant」钉住该样式的变体
            if (name.endsWith(VARIANT_SUFFIX)) {
                val base = name.removeSuffix(VARIANT_SUFFIX)
                return styles.firstOrNull { it.name == base }?.let { variantOf(it, random) }
            }
            return styles.firstOrNull { it.name == name }
        }
        val favorite = styles.firstOrNull { it.name == FAVORITE }?.takeIf { matches(it, angleDegrees) }
        val up = styles.filter { isUpFamily(it) && matches(it, angleDegrees) }
        val upNearer = up.isNotEmpty() && (favorite == null ||
            up.minOf { abs(angleDifference(it.angle, angleDegrees)) } < abs(angleDifference(favorite.angle, angleDegrees)))
        val family = when {
            upNearer -> up
            favorite != null -> listOf(favorite)
            else -> emptyList()
        }
        if (family.isNotEmpty() && random.nextFloat() < (if (upNearer) UP_FAMILY_PROBABILITY else FAVORITE_PROBABILITY)) {
            val member = if (family.size == 1) family[0] else family[random.nextInt(family.size)]
            return if (random.nextFloat() < FAVORITE_ORIGINAL_SHARE) member else variantOf(member, random)
        }
        // 没选中时其余对得上的参考（不含这一组）与随机各占一半；只有旧钢铁侠一族时与此前逐位相同
        val candidates = styles.filter { it !in family && matches(it, angleDegrees) }
        if (candidates.isEmpty() || random.nextFloat() >= REFERENCE_PROBABILITY) return null
        return candidates[random.nextInt(candidates.size)]
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
