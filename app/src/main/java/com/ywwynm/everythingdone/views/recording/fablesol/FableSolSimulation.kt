package com.ywwynm.everythingdone.views.recording.fablesol

import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.DEEP_LAYER_START
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.DX_DP
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.FLOW_DIR
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.HEIGHT_DP
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.MARGIN_DP
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.N_LAYERS
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.N_POINTS
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.PHYSICS_DT
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.REFERENCE_WIDTH_DP
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.U_HALF_DP
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** 单道注入的待生成条目（对应 simulation.py 中 pending 里的 dict）。 */
internal class FableSolPending(
    @JvmField val t: Double,
    @JvmField val u: Double,
    @JvmField val w: Double,
    @JvmField val amp: Double,
    @JvmField val travel: Double,
    @JvmField val peak: Double,
    @JvmField var stepsLeft: Int,
    @JvmField val total: Int
)

/** 单层水体状态（对应 simulation.py 的 LayerSim）。 */
class FableSolLayerSim(index: Int) {
    @JvmField val i = index
    @JvmField val depth01 = index.toDouble() / (N_LAYERS - 1)   // 0=最近, 1=最远

    @JvmField val wave = FableSolDynamicWave(N_POINTS, DX_DP)
    @JvmField val ambient = FableSolAmbientSet(1000L + index * 7, 1.0)
    @JvmField val hero = FableSolHeroWave(3000L + index * 13, depth01)
    @JvmField val optical = FableSolOpticalWaveSet(6000L + index * 19, depth01)

    @JvmField var heroDp = 0.0
    @JvmField var heroTargetDp = 0.0
    // 三个 Hero 频段的空间能量包络。目标值只写入上游出生区，再随流进入可见区；
    // 禁止逐帧音频直接重缩放整段已可见 Hero 几何（D17）。
    @JvmField val heroBandDp = DoubleArray(3)
    @JvmField val heroBandTargetDp = DoubleArray(3)
    @JvmField val heroBandFieldDp = Array(3) { DoubleArray(N_POINTS) }
    @JvmField internal val heroBandScratchDp = Array(3) { DoubleArray(N_POINTS) }
    @JvmField internal val ambientSampleDp = DoubleArray(N_POINTS)
    @JvmField internal val heroSampleDp = DoubleArray(N_POINTS)
    /**
     * 注入渐入期的 Hann 轮廓缓冲。原先每条 pending 每个物理子步分配一只
     * `DoubleArray(i1 - i0)`（注入高峰期数百次/s）。按最大宽度预分配复用，
     * 有效区间恒为 `[0, i1 - i0)`，由调用方显式传长度——下游不得依赖数组长度。
     * 归层所有，物理层循环并行时各层互不干扰。
     */
    @JvmField internal val injectBumpDp = DoubleArray(N_POINTS)
    /**
     * 原先挂在 Simulation 上、被九层逐层复写的四组 scratch：三频段目标、Hero 采样的
     * 空间平移网格、以及包络平流的插值索引/权重。它们只在本层的 perFrame 片段里
     * 生灭，归层所有之后层循环才能并行（C3）。
     */
    @JvmField internal val heroBandTargetScratch = DoubleArray(3)
    @JvmField internal val heroShiftedX = DoubleArray(N_POINTS)
    @JvmField internal val heroInterpIndex = IntArray(N_POINTS)
    @JvmField internal val heroInterpFraction = DoubleArray(N_POINTS)
    @JvmField var roughness01 = 0.0
    @JvmField var roughnessTarget01 = 0.0
    @JvmField var shapeRoughness01 = 0.0

    @JvmField val wanderPhi: Double
    @JvmField val attackMult: Double
    @JvmField val releaseMult: Double
    @JvmField val gainMult: Double
    @JvmField val tiltTau: Double
    @JvmField val wallOff: Double
    @JvmField val wallRamp: Double
    @JvmField val wallAbsorb: Double
    @JvmField val wallVisc: Double

    @JvmField var thetaEff = 0.0
    @JvmField var swellDp = 0.0
    @JvmField var swellTargetDp = 0.0
    @JvmField var flowDps = 0.0
    /** 声像几何位移的限速状态（见 Simulation.perFrame）。 */
    @JvmField var panShiftDp = 0.0
    @JvmField internal val pending = ArrayList<FableSolPending>()

    @JvmField var lagShape = DoubleArray(N_POINTS) { 1.0 }   // 由 Simulation 初始化

    init {
        val rng = FableSolRng(216L + index)
        wanderPhi = rng.uniform(0.0, 2.0 * Math.PI)
        attackMult = rng.uniform(0.72, 1.32)
        releaseMult = rng.uniform(0.72, 1.32)
        gainMult = rng.uniform(0.88, 1.12)
        tiltTau = 0.05 + 0.30 * depth01 + rng.uniform(0.0, 0.16)
        wallOff = rng.uniform(-6.0, 0.0)
        wallRamp = rng.uniform(27.0, 42.0) * (1.0 + 0.25 * depth01)
        wallAbsorb = (0.55 + 0.30 * depth01 + rng.uniform(-0.12, 0.12)).coerceIn(0.2, 1.0)
        wallVisc = rng.uniform(0.14, 0.22)
    }
}

/**
 * 九层水体编排（对应 simulation.py 的 Simulation）：流速/慢漂/涨落/注入/性格档/倾斜（重力系）/
 * 表面合成。坐标固定在重力系——u 轴 = 垂直于重力的水面方向，以容器中心投影为原点；倾斜只改变
 * 容器姿态（渲染时整体旋转 -θ）与湿润跨度 span(θ)。层体积守恒精确（解旋转矩形内水平面截面）。
 */
class FableSolSimulation(private val p: FableSolParams) {

    @JvmField var t = 0.0
    private var acc = 0.0
    var containerWidthDp = REFERENCE_WIDTH_DP
        private set
    @JvmField val uGrid = DoubleArray(N_POINTS) { (it - (N_POINTS - 1) / 2.0) * DX_DP }   // ±358dp
    private val auAbs = DoubleArray(N_POINTS) { abs(uGrid[it]) }
    @JvmField val layers = Array(N_LAYERS) { FableSolLayerSim(it) }
    @JvmField val heights = Array(N_LAYERS) { DoubleArray(N_POINTS) }
    @JvmField val grandWave = FableSolGrandWave(N_POINTS)

    /**
     * 巨浪引入的 L0 直流变化（隆起 + 冠部压制），逐帧由 [perFrame] 写入。
     * 它是局部的，不属于任何一层的直流分量，见 [fillLayerDcDp]。
     */
    @JvmField var grandDcBiasDp = 0.0

    /**
     * 冠部支配 mask 的逐列值。此前它只作用于锚点 detail，方向场与轨道从未被
     * 覆盖，巨浪平顶上因此一直浮着方向模态、波包与轨道位移（D178）。
     */
    @JvmField val grandKeep = DoubleArray(N_POINTS) { 1.0 }

    // Hero 热路径 scratch：这两项在层循环之前一次写好、循环内只读，可以跨层共享。
    // 逐层复写的那四组已迁入 FableSolLayerSim（C3 层并行的前置条件）。
    private val heroVisibleMask = BooleanArray(N_POINTS)
    private val heroSourceWeight = DoubleArray(N_POINTS)

    /**
     * 物理子步层循环（C8）与 perFrame 层循环（C3）的串行回退开关（永久保留）。
     * 默认并行；真机若出现异常可即时切回串行。两条路径共用同一份逐层代码，
     * 只有投放方式不同——对拍测试正是拿串行结果当稳定基准来验并行无竞态。
     */
    @Volatile internal var parallelLayerLoopsEnabled = true
    // 三频段攻击/释放的一阶系数：层循环前算一次，循环内只读。
    private val heroBandAttackK = DoubleArray(3)
    private val heroBandReleaseK = DoubleArray(3)

    @JvmField var flow01 = 0.0
    @JvmField var flow01Deep = 0.0   // 深层流速驱动：mapper 的数十秒积分（D16）
    @JvmField var breath01 = 0.0     // 4Hz 音节呼吸（mapper 写入；只调制环境波振幅）
    @JvmField var sparkle01 = 0.0    // 闪点活跃度（mapper：慢响度×材质档；渲染侧消费）
    @JvmField var calm01 = 1.0       // 平静度（idle/silence 权重→顶边羽化；渲染侧消费）
    @JvmField var resonance01 = 0.0  // 共鸣度（melodic/loud 权重→墙侧驻波）
    @JvmField var glintCapacity01 = 1.0
    @JvmField var layerSpread = 1.0  // 七境层距执行器；1=原始作者层距，0=九层收束到第 0 层
    @JvmField var visualState = "IDLE"
    @JvmField var visualStateLabel = "镜塘"
    @JvmField var visualWaterDrive01 = 0.0
    @JvmField var visualLevelTargetDp = 0.0
    @JvmField var visualLevelDp = 0.0
    @JvmField var visualWaveScale = 0.0
    @JvmField var visualTargetDps = 0.0
    @JvmField var tension01 = 0.0    // 张力（A6 相位试验：波速相干偏置）；请经 setTension01 写入
    private var cMean = 150.0        // wave_speed_dps 九层均值（setTension01 时缓存）

    // 节拍锁相
    private var beatBpm = 0.0
    private var beatPhase = 0.0
    @JvmField var beat01 = 0.0
    private var beatInBpm = 0.0
    private var beatInPh = 0.0
    private var beatInConf = 0.0

    // 色彩呼吸
    @JvmField var colorBright01 = 0.5
    @JvmField var colorEnergy01 = 0.0
    private var colorTargetBright = 0.5
    private var colorTargetEnergy = 0.0

    // 材质 / 空间
    @JvmField var roughness01 = 0.0
    @JvmField var spectralTilt01 = 0.5
    private var materialTargetRough = 0.0
    private var materialTargetTilt = 0.5
    @JvmField var stereoWidth01 = 0.0
    @JvmField var pan01 = 0.5
    private var spatialTargetWidth = 0.0
    private var spatialTargetPan = 0.5

    // 段落性格只保留缓慢明度；水位、流速、层距由七境执行器唯一负责。
    @JvmField var moodBright = 0.0
    private var moodBrightTarget = 0.0

    // 倾斜
    @JvmField var thetaDeg = 0.0
    /** 手机传感器的完整前后俯仰，范围 ±90°。 */
    @JvmField var pitchDeg = 0.0
    /** 由完整俯仰软压缩后的水体惯性目标，范围 ±55°。 */
    @JvmField var motionPitchDeg = 0.0
    @JvmField val surface2d = FableSolContinuousSurface(p)
    private var wallBlend = 0.0
    private var prevThRender = 0.0
    private var prevThIn = 0.0
    private var tiltAgit = 0.0
    private var shakeT = -1.0   // <0 表示未激活（对应 Python None）

    // 边界剖面缓存
    private var bcSpan = Double.NaN
    private var bcBlend = Double.NaN
    private var bcSoft = Double.NaN
    private var bcAgit = Double.NaN
    private var bcLastUpdateT = Double.NEGATIVE_INFINITY
    private var bcTargetSpan = REFERENCE_WIDTH_DP
    private var bcTargetBlend = 0.0
    private var bcTargetSoft = 0.0
    private var bcTargetAgit = 0.0
    private var bcNextLayer = N_LAYERS
    private var boundaryProfileRevision = 0
    private val spongeDecay = Array(N_LAYERS) { DoubleArray(N_POINTS) { 1.0 } }
    private val cScale = Array(N_LAYERS) { DoubleArray(N_POINTS) { 1.0 } }
    private val vDecay = Array(N_LAYERS) { DoubleArray(N_POINTS) { 1.0 } }
    private val visc = Array(N_LAYERS) { DoubleArray(N_POINTS) }
    private val lagTaper = Array(N_LAYERS) { DoubleArray(N_POINTS) { 1.0 } }

    internal var perfSubsteps = 0
        private set
    internal var perfBoundaryLayers = 0
        private set
    internal var perfBoundaryNs = 0L
        private set
    internal var perfWaveNs = 0L
        private set
    internal var perfSurfaceNs = 0L
        private set
    internal var perfComposeNs = 0L
        private set

    private val demoRng = FableSolRng(42)
    private var demoNextT = 0.0

    init {
        for (ls in layers) {
            ls.ambient.retune(p.lget("ambient_len_dp", ls.i))
            // 倾斜激励的每层形状调制：同一次旋转在各层激起不同模态混合，回弹浪形各不相同
            val rng = FableSolRng(4200L + ls.i * 17)
            val lam = rng.uniform(220.0, 420.0)
            val phi = rng.uniform(0.0, 2.0 * Math.PI)
            ls.lagShape = DoubleArray(N_POINTS) { 1.0 + 0.14 * sin(2.0 * Math.PI * uGrid[it] / lam + phi) }
        }
    }

    // ---- 倾斜控制 ----
    fun setTilt(deg: Double, snap: Boolean = false) {
        if (!deg.isFinite()) return
        // Android 重力方向覆盖完整圆周；选择与当前角度最近的等价角，既允许完全倒置，
        // 又避免传感器从 179° 跳到 -179° 时让水体反向旋转 358°。
        var delta = (deg - thetaDeg) % 360.0
        if (delta <= -180.0) delta += 360.0
        else if (delta > 180.0) delta -= 360.0
        thetaDeg += delta
        if (snap) {
            val th = Math.toRadians(thetaDeg)
            prevThRender = th
            prevThIn = th
            tiltAgit = 0.0
            for (ls in layers) ls.thetaEff = th
        }
    }

    /** 前后俯仰只驱动连续水面；旧九层重力系物理保持原样。 */
    fun setPitch(deg: Double, snap: Boolean = false) {
        if (!deg.isFinite()) return
        pitchDeg = FableSolPitchPolicy.rawPitchDeg(deg)
        motionPitchDeg = FableSolPitchPolicy.motionPitchDeg(pitchDeg)
        surface2d.setPitch(motionPitchDeg, snap)
    }

    /**
     * 张力=相位（A6 试验，D18/D20 标签）：持续渐强下各层波速缓慢向层均值靠拢
     * （混合上限 0.6），静息回失谐；注入时序不参与（绝不同步化注入）。
     */
    fun setTension01(v: Double) {
        tension01 = v.coerceIn(0.0, 1.0)
        val arr = p.larray("wave_speed_dps")
        var sum = 0.0
        for (x in arr) sum += x
        cMean = sum / arr.size
    }

    fun triggerShake() { shakeT = 0.0 }

    /**
     * Android View 完成测量后的真实内容宽度。320dp 仅是测量前回退值；物理容器、倾斜体积、
     * 墙面跨度和以屏幕坐标注入的中心都必须跟随这里的运行时宽度。
     */
    fun setContainerWidthDp(widthDp: Double) {
        if (!widthDp.isFinite() || widthDp <= 0.0) return
        containerWidthDp = widthDp
    }

    private fun wobbleDeg(): Double {
        if (shakeT < 0.0) return 0.0
        val ts = shakeT
        if (ts > 1.8) { shakeT = -1.0; return 0.0 }
        return SHAKE_AMP_DEG * sin(2.0 * Math.PI * SHAKE_FREQ_HZ * ts) * exp(-ts / SHAKE_TAU_S)
    }

    /** 同一次摇晃的纵向错频错相分量，避免二维姿态退化为对角直线。 */
    private fun pitchWobbleDeg(): Double {
        if (shakeT < 0.0 || shakeT > 1.8) return 0.0
        val ts = shakeT
        return 0.62 * SHAKE_AMP_DEG * sin(
            2.0 * Math.PI * (SHAKE_FREQ_HZ * 0.83) * ts + 0.9
        ) * exp(-ts / (SHAKE_TAU_S * 1.12))
    }

    /** 连续水面的稀有远浪入口；普通实时 onset 不调用。 */
    fun injectDepthPacket(strength: Double, pan01: Double = 0.5, zDominant: Boolean = false) {
        surface2d.injectPacket(this, strength.coerceIn(0.0, 1.0), pan01, zDominant)
    }

    /** 触发第 0 层有限支撑事件浪；已有事件浪尚未离场时返回 false。 */
    fun triggerGrandWave(expressionGain: Double = 1.0, prelaunchS: Double = 0.0): Boolean =
        grandWave.trigger(this, expressionGain, prelaunchS)

    internal fun layerWaveSpeedDps(layerIndex: Int): Double =
        p.lget("wave_speed_dps", layerIndex)

    /** 当前锁定 BPM（mapper 计算下一拍时刻用）。 */
    fun currentBeatBpm(): Double = beatBpm

    /** (span, H_g, theta_rad)：湿润跨度、容器沿重力方向的范围、渲染角。 */
    fun geometrySpan(): Double {
        val th = Math.toRadians(thetaDeg)
        val c = abs(cos(th)); val s = abs(sin(th))
        return containerWidthDp * c + HEIGHT_DP * s
    }

    private fun geometryHg(): Double {
        val th = Math.toRadians(thetaDeg)
        val c = abs(cos(th)); val s = abs(sin(th))
        return HEIGHT_DP * c + containerWidthDp * s
    }

    private fun tiltLevel(baseDp: Double, c: Double, s: Double): Double {
        if (s < 1e-4) return baseDp
        if (c < 1e-4) return baseDp * containerWidthDp / HEIGHT_DP
        val vol = containerWidthDp * baseDp
        val h1 = min(containerWidthDp * s, HEIGHT_DP * c)
        val h2 = max(containerWidthDp * s, HEIGHT_DP * c)
        val wp = h1 / (s * c)
        val a1 = 0.5 * h1 * wp
        if (vol <= a1) return sqrt(2.0 * vol * s * c)
        if (vol <= a1 + wp * (h2 - h1)) return h1 + (vol - a1) / wp
        val vAir = containerWidthDp * HEIGHT_DP - vol
        return (h1 + h2) - sqrt(max(2.0 * vAir * s * c, 0.0))
    }

    private fun roundTo(x: Double, decimals: Int): Double {
        val f = Math.pow(10.0, decimals.toDouble())
        return Math.round(x * f) / f
    }

    private fun rebuildBc(span: Double, layerBudget: Int): Int {
        if (layerBudget <= 0) return 0
        if (bcNextLayer < N_LAYERS) return rebuildBcLayers(layerBudget)
        val soft = p.get("wall_soft")
        val agit = roundTo(tiltAgit * p.get("tilt_calm"), 1)
        val kSpan = roundTo(span, 1); val kBlend = roundTo(wallBlend, 2); val kSoft = roundTo(soft, 2)
        if (kSpan == bcSpan && kBlend == bcBlend && kSoft == bcSoft && agit == bcAgit) return 0
        // 传感器运动时 span / wallBlend / agit 每个 120Hz 物理子步都在变化。边界剖面
        // 是空间阻尼系数，不需要以求解器频率重建；30Hz 更新把最大滞后限制在 33ms，
        // 同时避免每秒约 114 次的 9×216 exp 重算。
        if (boundaryProfileRevision > 0 && t - bcLastUpdateT < BOUNDARY_PROFILE_DT) return 0
        bcSpan = kSpan; bcBlend = kBlend; bcSoft = kSoft; bcAgit = agit
        bcTargetSpan = span
        bcTargetBlend = wallBlend
        bcTargetSoft = soft
        bcTargetAgit = agit
        bcLastUpdateT = t
        boundaryProfileRevision++
        bcNextLayer = 0
        return rebuildBcLayers(
            if (boundaryProfileRevision == 1) N_LAYERS else layerBudget
        )
    }

    /**
     * 倾斜期间把九层边界剖面分摊到连续物理子步，避免单帧集中执行数千次指数运算。
     * 剖面只依赖 |u|，因此左右半区严格镜像计算，结果与逐点重建一致。
     */
    private fun rebuildBcLayers(maxLayers: Int): Int {
        val edge = bcTargetSpan / 2.0
        val blend = bcTargetBlend
        val softEff = min(bcTargetSoft * (1.0 + 0.9 * bcTargetAgit), 1.0)
        val endLayer = min(bcNextLayer + maxLayers, N_LAYERS)
        for (layerIndex in bcNextLayer until endLayer) {
            val ls = layers[layerIndex]
            val i = ls.i
            val wall = edge + 2.0 * DX_DP + ls.wallOff * blend
            for (n in 0 until (N_POINTS + 1) / 2) {
                val au = auAbs[n]
                val profSp = clip01((au - edge - SPONGE_FREE_DP) / MARGIN_DP).let { it * it } * (1.0 - blend)
                val appr = clip01((au - (wall - ls.wallRamp)) / ls.wallRamp)
                val prof = profSp + clip01((au - wall) / 24.0) * 2.0 * blend
                val sponge = exp(-PHYSICS_DT * SPONGE_RATE * prof)
                val fric = appr * appr * (7.0 * ls.wallAbsorb * softEff) * blend
                val velocity = exp(-PHYSICS_DT * fric)
                val viscosity = (appr * appr * ls.wallVisc * (0.4 + softEff) * blend +
                    0.055 * bcTargetAgit).coerceIn(0.0, 0.30)
                val speed = 1.0 - blend * clip01((au - wall) / (2.0 * DX_DP))
                val ct = clip01((au - wall) / 24.0)
                val taper = (1.0 - 0.7 * appr * blend) * (1.0 - ct * ct * blend)
                val mirror = N_POINTS - 1 - n
                spongeDecay[i][n] = sponge
                vDecay[i][n] = velocity
                visc[i][n] = viscosity
                cScale[i][n] = speed
                lagTaper[i][n] = taper
                if (mirror != n) {
                    spongeDecay[i][mirror] = sponge
                    vDecay[i][mirror] = velocity
                    visc[i][mirror] = viscosity
                    cScale[i][mirror] = speed
                    lagTaper[i][mirror] = taper
                }
            }
        }
        val rebuilt = endLayer - bcNextLayer
        bcNextLayer = endLayer
        return rebuilt
    }

    // ---- 注入 ----
    private fun uLimit(span: Double): Double {
        val margin = if (wallBlend > 0.3) 12.0 else MARGIN_DP + 60.0
        return min(U_HALF_DP - 12.0, span / 2.0 + margin)
    }

    private fun toU(xDp: Double, span: Double): Double {
        val th = Math.toRadians(thetaDeg)
        val centeredX = xDp - containerWidthDp / 2.0
        val uu = if (abs(cos(th)) > cos(Math.toRadians(89.0))) {
            centeredX * abs(cos(th))
        } else centeredX
        val lim = uLimit(span)
        return uu.coerceIn(-lim, lim)
    }

    fun injectLayer(i: Int, xDp: Double, widthDp: Double, ampDp: Double, travel: Double,
                    delayS: Double = 0.0, uDp: Double? = null, peak: Double = 1.0) {
        if (ampDp < 0.9) return
        val span = geometrySpan()
        val ls = layers[i]
        var w = widthDp
        val u: Double
        if (uDp == null) {
            u = toU(xDp, span)
        } else {
            // 画外全支撑出生的硬保证（2026-07-11 根治"浪包突然隆起/鼓包"）：
            // Hann 支撑 [u−w/2, u+w/2] 不得与可见跨度相交——出生点只向外推、
            // 绝不向内拉；浪只能以行波形式进入画面。旧实现的 uLimit 向内钳位
            // 在共鸣档（余量塌缩到 12dp）把每个包的半幅直接压进可见区，是
            // 频繁大幅突变的主因；jitter/pan/frac 的随机尾部越界是次因。
            // need 超出网格容量时先收窄包宽（保持全支撑画外），不牺牲保证。
            val visHalf = span / 2.0
            val gridCap = U_HALF_DP - 12.0
            val maxW = 2.0 * (gridCap - visHalf - EDGE_BIRTH_GAP_DP)
            if (w > maxW) w = maxW
            if (w < 24.0) return  // 容器占满网格的极端情形：宁可丢弃也不入画
            val need = visHalf + w / 2.0 + EDGE_BIRTH_GAP_DP
            val raw = uDp
            val side = when {
                abs(raw) > 1e-6 -> if (raw > 0) 1.0 else -1.0
                abs(travel) > 1e-6 -> if (travel > 0) -1.0 else 1.0
                else -> 1.0
            }
            // 中心不超网格上限（否则包整体落在网格外被静默丢弃）；外侧尾巴
            // 允许被网格截断——发生在海绵深处，不可见且被吸收。
            u = side * min(max(abs(raw), need), gridCap)
        }
        // 注入渐入固化 120ms（原 inject_ramp_ms）。
        val nRamp = max((0.120 / PHYSICS_DT).toInt(), 1)
        ls.pending.add(FableSolPending(
            t = t + delayS, u = u, w = w, amp = ampDp * ls.gainMult,
            travel = travel, peak = peak.coerceIn(0.5, 2.5), stepsLeft = nRamp, total = nRamp
        ))
    }

    fun injectEvent(xDp: Double, widthDp: Double, ampDp: Double, travel: Double,
                    layerAmps: DoubleArray, cascade: Boolean) {
        // 级联层间延迟固化 0.054s（原 cascade_step_s）。
        val stepS = if (cascade) 0.054 else 0.0
        for (ls in layers) injectLayer(ls.i, xDp, widthDp, ampDp * layerAmps[ls.i], travel, stepS * ls.i)
    }

    /**
     * 单个物理子步内某一层的推进。所有写入都落在 `ls` 自身（相位、θ 惯性、波场、
     * pending 列表）；唯一的 sim 级写是第 0 层的 [visualTargetDps]，只有一个写者、
     * 循环内没有读者，层循环的汇合屏障之后才被消费。
     */
    private fun physicsLayerStep(
        ls: FableSolLayerSim,
        beatSurge: Double,
        thInRad: Double,
        thRenderRad: Double,
        dRender: Double,
        calm: Double,
        agitC: Double
    ) {
        val base = p.lget("flow_base_dps", ls.i)
        val idle = p.get("idle_flow_ratio")
        // 深层无动于衷（D16）：流速只跟数十秒能量积分，不吃逐帧感知速度
        val driveRaw = if (ls.i >= DEEP_LAYER_START) flow01Deep.coerceIn(0.0, 1.0)
                       else flow01.coerceIn(0.0, 1.0)
        val target = FLOW_DIR * FableSolFlowPolicy.targetFlowDps(
            speed01 = driveRaw,
            baseDps = base,
            gain = p.get("flow_gain"),
            curve = p.get("flow_curve"),
            idleRatio = idle
        )
        if (ls.i == 0) visualTargetDps = abs(target)
        val tau = max(p.get("flow_smooth_s"), 1e-2)
        ls.flowDps += (target - ls.flowDps) * (1.0 - exp(-PHYSICS_DT / tau))
        // 深层不吃节拍脉冲（D16 无动于衷）
        val pulse = if (ls.i >= DEEP_LAYER_START) 1.0
                    else 1.0 + beatSurge * (1.0 - 0.5 * ls.depth01)
        ls.ambient.retune(p.lget("ambient_len_dp", ls.i))
        ls.ambient.advance(PHYSICS_DT, ls.flowDps * pulse)
        ls.hero.retune(p.get("hero_len_dp"))
        val spatialRate = 1.0 + stereoWidth01 * (ls.depth01 - 0.5) * 0.36
        ls.hero.advance(PHYSICS_DT, ls.flowDps * 1.5 * spatialRate)
        ls.optical.advance(PHYSICS_DT, ls.flowDps * spatialRate)
        val oldEff = ls.thetaEff
        ls.thetaEff += (thInRad - ls.thetaEff) * (1.0 - exp(-PHYSICS_DT / ls.tiltTau))
        val dLag = ((ls.thetaEff - oldEff) - dRender).coerceIn(-0.02, 0.02)
        val dDyn: Double
        if (calm > 1e-6) {
            val gap = abs(ls.thetaEff - thRenderRad)
            val g0 = max(0.30 - 0.24 * calm, 0.02)
            val kDyn = 0.02 * (1.0 - 0.75 * calm)
            dDyn = kDyn * tanh(dLag / kDyn) / (1.0 + (gap / g0) * (gap / g0))
        } else dDyn = dLag
        if (abs(dDyn) > 1e-9) {
            val wu = ls.wave.u; val lt = lagTaper[ls.i]; val ls2 = ls.lagShape
            for (n in 0 until N_POINTS) wu[n] -= dDyn * uGrid[n] * lt[n] * ls2[n]
        }
        var c = p.lget("wave_speed_dps", ls.i)
        applyInjections(ls, c)   // 注入用原生波速：绝不同步化注入
        // 张力（A6）：注入之后波速才向九层均值相干偏置（混合上限 0.6）
        if (tension01 > 1e-3) c += 0.6 * tension01 * (cMean - c)
        var hl = p.lget("damp_halflife_s", ls.i)
        if (agitC > 1e-3) hl *= 1.0 - 0.60 * agitC
        ls.wave.step(PHYSICS_DT, c, hl, spongeDecay[ls.i], cScale[ls.i],
                vDecay[ls.i], visc[ls.i], ls.flowDps)
    }

    private fun applyInjections(ls: FableSolLayerSim, cDps: Double) {
        var idx = 0
        val list = ls.pending
        while (idx < list.size) {
            val item = list[idx]
            if (item.t > t || item.stepsLeft <= 0) { idx++; continue }
            val frac = 1.0 / item.total
            val halfW = item.w / 2.0
            val i0 = max(((item.u - halfW + U_HALF_DP) / DX_DP).toInt(), 0)
            val i1 = min(((item.u + halfW + U_HALF_DP) / DX_DP).toInt() + 1, N_POINTS)
            if (i1 - i0 >= 3) {
                val pk = item.peak
                val sharp = abs(pk - 1.0) > 1e-3
                // 复用层内 scratch；有效区间显式限定为 [0, i1 - i0)，每个元素都在
                // 使用前被写满，不依赖数组默认零值，也不读区间外的陈旧数据。
                val bump = ls.injectBumpDp
                for (k in 0 until i1 - i0) {
                    var win = 0.5 * (1.0 + cos(Math.PI * ((uGrid[i0 + k] - item.u) / halfW).coerceIn(-1.0, 1.0)))
                    if (sharp) win = win.pow(pk)
                    bump[k] = win * (item.amp * frac)
                }
                ls.wave.inject(i0, i1, bump, item.travel, cDps)
            }
            item.stepsLeft -= 1
            idx++
        }
        // 移除已用尽的条目
        var w = 0
        for (r in list.indices) if (list[r].stepsLeft > 0) { list[w] = list[r]; w++ }
        while (list.size > w) list.removeAt(list.size - 1)
    }

    // ---- 节拍 / 色彩 / 材质 / 空间驱动（mapper 每帧写入）----
    fun setBeat(bpm: Double, phase01: Double, conf01: Double) { beatInBpm = bpm; beatInPh = phase01; beatInConf = conf01 }
    fun setColorDrive(bright01: Double, energy01: Double) { colorTargetBright = bright01; colorTargetEnergy = energy01 }
    fun setMaterialDrive(roughness: Double, spectralTilt: Double) {
        materialTargetRough = roughness; materialTargetTilt = spectralTilt
    }
    fun setSpatialDrive(width01: Double, pan01v: Double) { spatialTargetWidth = width01; spatialTargetPan = pan01v }

    fun timeToNextBeat(): Double {
        if (beat01 < 0.05 || beatBpm <= 0.0) return 0.0
        return (1.0 - beatPhase % 1.0) * 60.0 / beatBpm
    }

    fun setMood(@Suppress("UNUSED_PARAMETER") energy01: Double, brightness01: Double) {
        moodBrightTarget = (brightness01 - 0.5) * 2.0
    }

    // ---- 演示驱动（无声源测试；demo_mode 默认 0，不触发）----
    private fun demoTick() {
        if (t < demoNextT) return
        demoNextT = t + demoRng.uniform(0.4, 1.3)
        val strength = demoRng.uniform(0.2, 1.0).pow(1.5)
        val span = geometrySpan()
        val side = if (FLOW_DIR < 0.0) 1.0 else -1.0
        for (ls in layers) {
            val k = (1.0 - 0.42 * ls.depth01) * demoRng.uniform(0.6, 1.0)
            val width = 150.0 * (1.0 + 0.20 * ls.depth01)
            val amp = max(2.0, strength * k * 18.0)
            val u = side * (span / 2.0 + width / 2.0 + 16.0 + 24.0 * ls.depth01)
            injectLayer(ls.i, 0.0, width, amp, FLOW_DIR * 0.90,
                delayS = 0.018 * ls.i, uDp = u, peak = 1.15)
        }
    }

    // ---- 主更新 ----
    fun update(dtReal0: Double) {
        val dtReal = min(dtReal0, 0.1)
        acc += dtReal
        val span = geometrySpan()
        var substeps = 0
        var boundaryLayers = 0
        var boundaryNs = 0L
        var waveNs = 0L
        var surfaceNs = 0L
        var boundaryLayerBudget = BOUNDARY_LAYERS_PER_FRAME
        while (acc >= PHYSICS_DT) {
            substeps++
            acc -= PHYSICS_DT
            t += PHYSICS_DT
            grandWave.advance(this)
            if (shakeT >= 0.0) shakeT += PHYSICS_DT
            if (p.get("demo_mode") >= 0.5) demoTick()
            val thetaIn = thetaDeg + wobbleDeg()
            val thInRad = Math.toRadians(thetaIn)
            // 硬墙过渡取决于水面偏离水平面的角度；0° 和 180° 都是水平水面。
            var targetBlend = min(abs(sin(thInRad)) / sin(Math.toRadians(WALL_ON_DEG)), 1.0)
            // 驻波呼吸（试验，D20）：持续乐音时墙侧吸收缓慢让位于反射，水池对音乐
            // “共鸣”；语音/静默下 resonance≈0，行为与旧版全同。
            targetBlend = max(targetBlend, 0.35 * resonance01)
            wallBlend += (targetBlend - wallBlend) * (1.0 - exp(-PHYSICS_DT / 0.3))
            val rate = abs(thInRad - prevThIn) / PHYSICS_DT
            prevThIn = thInRad
            tiltAgit = max(tiltAgit * exp(-PHYSICS_DT / 1.4), min(rate / 1.2, 1.0))
            val calm = p.get("tilt_calm")
            val agitC = tiltAgit * calm
            val boundaryStart = System.nanoTime()
            val rebuiltLayers = rebuildBc(span, boundaryLayerBudget)
            boundaryLayers += rebuiltLayers
            boundaryLayerBudget -= rebuiltLayers
            boundaryNs += System.nanoTime() - boundaryStart
            val waveStart = System.nanoTime()
            // 节拍振荡器（锁相环）
            beat01 += (min(beatInConf, 1.0) - beat01) * (1.0 - exp(-PHYSICS_DT / 0.8))
            var beatSurge = 0.0
            if (beatInBpm > 0.0 && beatBpm <= 0.0) { beatBpm = beatInBpm; beatPhase = beatInPh }
            else if (beatInBpm > 0.0) beatBpm += (beatInBpm - beatBpm) * (1.0 - exp(-PHYSICS_DT / 1.5))
            if (beatBpm > 0.0) {
                beatPhase += PHYSICS_DT * beatBpm / 60.0
                if (beatInBpm > 0.0) {
                    val err = ((beatInPh - beatPhase + 0.5).mod(1.0)) - 0.5
                    beatPhase += err * (1.0 - exp(-PHYSICS_DT / 0.5))
                }
                val bump = (0.5 + 0.5 * cos(2.0 * Math.PI * (beatPhase.mod(1.0)))).pow(3.0)
                beatSurge = p.get("beat_gain") * beat01 * 0.45 * bump
            }
            val thRenderRad = Math.toRadians(thetaDeg)
            val dRender = thRenderRad - prevThRender
            prevThRender = thRenderRad
            // 九层的三条相位推进、注入与波动方程都只写本层状态：ambient/hero/optical
            // 是层自有对象，pending 与 wave 按层隔离，注入 scratch 也已归层（C10）。
            // beatSurge / thInRad / thRenderRad / dRender / calm / agitC / tension01 /
            // cMean 都在循环前算好，循环内只读。surface2d.advance 依赖全部九层的结果，
            // 必须留在层循环之后串行。
            val physicsBody = FableSolRowBody { startLayer, endLayer ->
                for (index in startLayer until endLayer) {
                    physicsLayerStep(
                        layers[index], beatSurge, thInRad, thRenderRad, dRender, calm, agitC
                    )
                }
            }
            if (parallelLayerLoopsEnabled) {
                FableSolRowParallel.runUnits(N_LAYERS, physicsBody)
            } else {
                physicsBody.run(0, N_LAYERS)
            }
            waveNs += System.nanoTime() - waveStart
            // A/B 回退关闭时仍推进二维状态，重新启用不会从静止相位重新开始。
            val surfaceStart = System.nanoTime()
            surface2d.advance(PHYSICS_DT, this, motionPitchDeg, pitchWobbleDeg())
            surfaceNs += System.nanoTime() - surfaceStart
        }
        val composeStart = System.nanoTime()
        perFrame(dtReal, span, Math.toRadians(thetaDeg))
        perfSubsteps = substeps
        perfBoundaryLayers = boundaryLayers
        perfBoundaryNs = boundaryNs
        perfWaveNs = waveNs
        perfSurfaceNs = surfaceNs
        perfComposeNs = System.nanoTime() - composeStart
    }

    private fun perFrame(dt: Double, span: Double, thRender: Double) {
        // 段落性格只慢追明度；水位、流速与层距由七境的独立执行器负责。
        val kMood = 1.0 - exp(-dt / 1.5)
        moodBright += (moodBrightTarget - moodBright) * kMood
        colorBright01 += (colorTargetBright - colorBright01) * (1.0 - exp(-dt / 2.0))
        colorEnergy01 += (colorTargetEnergy - colorEnergy01) * (1.0 - exp(-dt / 1.2))
        roughness01 += (materialTargetRough - roughness01) * (1.0 - exp(-dt / 0.24))
        spectralTilt01 += (materialTargetTilt - spectralTilt01) * (1.0 - exp(-dt / 0.7))
        stereoWidth01 += (spatialTargetWidth - stereoWidth01) * (1.0 - exp(-dt / 0.45))
        pan01 += (spatialTargetPan - pan01) * (1.0 - exp(-dt / 0.22))
        val cTh = abs(cos(thRender)); val sTh = abs(sin(thRender))
        val half = span / 2.0
        for (n in 0 until N_POINTS) heroVisibleMask[n] = auAbs[n] <= half
        prepareHeroSourceBlend(half)
        val grandProfile = grandWave.sample(uGrid)
        if (grandProfile == null) {
            grandDcBiasDp = 0.0
            java.util.Arrays.fill(grandKeep, 1.0)
        } else {
            for (n in 0 until N_POINTS) {
                grandKeep[n] = grandWave.backgroundKeep(grandProfile[n])
            }
        }
        val heroBreath = p.get("hero_breath")
        val ambientBreath = p.get("ambient_breath")
        val ambientGain = p.get("ambient_gain")
        val heroGain = p.get("hero_gain")
        val wanderGain = p.get("wander_gain")
        val baseLevel0 = p.lget("base_level_dp", 0)
        // 三频段的攻击/释放系数只由频段与升降方向决定，九层共享同一组 tau；
        // 逐层重算等于每帧 27 次 exp。粗糙度两条更是层无关，18 次降到 2 次。
        // 值与逐层重算逐位相同（同一表达式、同一输入）。
        val heroAttackS = p.get("hero_attack_s")
        val heroReleaseS = p.get("hero_release_s")
        for (j in 0 until 3) {
            heroBandAttackK[j] = 1.0 - exp(-dt / max(heroAttackS * ATK_MULT[j], 1e-3))
            heroBandReleaseK[j] = 1.0 - exp(-dt / max(heroReleaseS * REL_MULT[j], 1e-3))
        }
        val roughnessK = 1.0 - exp(-dt / 0.26)
        val shapeRoughnessK = 1.0 - exp(-dt / 1.2)
        // 九层的包络推进、Ambient/Hero 采样、平流与 heights 行写彼此独立：每层只写
        // 自身状态与 heights[i]，heroVisibleMask / heroSourceWeight / grandProfile 都是
        // 层循环之前写好的只读量。层内数学与串行逐位一致，只是完成顺序不同。
        val composeBody = FableSolRowBody { startLayer, endLayer ->
            for (index in startLayer until endLayer) {
                perFrameLayer(
                    layers[index], dt, cTh, sTh, grandProfile, heroBreath, ambientBreath,
                    ambientGain, heroGain, wanderGain, baseLevel0,
                    roughnessK, shapeRoughnessK, thRender
                )
            }
        }
        if (parallelLayerLoopsEnabled) {
            FableSolRowParallel.runUnits(N_LAYERS, composeBody)
        } else {
            composeBody.run(0, N_LAYERS)
        }
    }

    private fun perFrameLayer(
        ls: FableSolLayerSim,
        dt: Double,
        cTh: Double,
        sTh: Double,
        grandProfile: DoubleArray?,
        heroBreath: Double,
        ambientBreath: Double,
        ambientGain: Double,
        heroGain: Double,
        wanderGain: Double,
        baseLevel0: Double,
        roughnessK: Double,
        shapeRoughnessK: Double,
        thRender: Double
    ) {
        run {
            val target = ls.swellTargetDp
            val rising = target > ls.swellDp
            val tau = if (rising) p.get("swell_attack_s") * ls.attackMult else p.get("swell_release_s") * ls.releaseMult
            ls.swellDp += (target - ls.swellDp) * (1.0 - exp(-dt / max(tau, 1e-3)))
            val heroMax = p.lget("hero_max_dp", ls.i) * heroGain
            val hTarget = min(ls.heroTargetDp, 1.25 * heroMax)
            val hTau = if (hTarget > ls.heroDp) p.get("hero_attack_s") * ls.attackMult else p.get("hero_release_s") * ls.releaseMult
            ls.heroDp += (hTarget - ls.heroDp) * (1.0 - exp(-dt / max(hTau, 1e-3)))
            val bandTarget = ls.heroBandTargetScratch
            bandTarget[0] = ls.heroBandTargetDp[0]
            bandTarget[1] = ls.heroBandTargetDp[1]
            bandTarget[2] = ls.heroBandTargetDp[2]
            if (bandTarget[0] + bandTarget[1] + bandTarget[2] < 1e-6 && ls.heroTargetDp > 0.0) {
                bandTarget[0] = ls.heroTargetDp * 0.48; bandTarget[1] = ls.heroTargetDp * 0.34; bandTarget[2] = ls.heroTargetDp * 0.18
            }
            var totalB = bandTarget[0] + bandTarget[1] + bandTarget[2]
            if (totalB > 1.25 * heroMax && totalB > 1e-6) {
                val f = 1.25 * heroMax / totalB
                for (j in 0 until 3) bandTarget[j] *= f
                totalB = 1.25 * heroMax
            }
            // 陡峭度红线（2026-07-21）：每个尺度组的振幅不得超过它自己波长允许的
            // 高度，被削掉的能量转给还有余量的更长模态。于是响度上去时浪会变高，
            // 但高度只能长在长浪上——"要高就必须也宽"，而不是把短浪拉尖。
            val ceiling = ls.hero.bandCeilingDp
            var cappedSum = 0.0
            var headroomSum = 0.0
            for (j in 0 until 3) {
                val capped = min(bandTarget[j], ceiling[j])
                bandTarget[j] = capped
                cappedSum += capped
                headroomSum += ceiling[j] - capped
            }
            val spare = totalB - cappedSum
            if (spare > 1e-9 && headroomSum > 1e-9) {
                val share = min(spare / headroomSum, 1.0)
                for (j in 0 until 3) {
                    bandTarget[j] += (ceiling[j] - bandTarget[j]) * share
                }
            }
            for (j in 0 until 3) {
                val risingB = bandTarget[j] > ls.heroBandDp[j]
                val kB = if (risingB) heroBandAttackK[j] else heroBandReleaseK[j]
                ls.heroBandDp[j] += (bandTarget[j] - ls.heroBandDp[j]) * kB
            }
            advectHeroEnvelope(ls, dt)
            ls.roughness01 += (ls.roughnessTarget01 - ls.roughness01) * roughnessK
            ls.shapeRoughness01 += (ls.roughnessTarget01 - ls.shapeRoughness01) * shapeRoughnessK
            val wander = p.lget("wander_amp_dp", ls.i) * wanderGain *
                    sin(2.0 * Math.PI * t / max(p.lget("wander_period_s", ls.i), 1.0) + ls.wanderPhi)
            val baseSpread = baseLevel0 +
                    (p.lget("base_level_dp", ls.i) - baseLevel0) * layerSpread
            val level = tiltLevel(baseSpread, cTh, sTh) + wander + ls.swellDp
            // 4Hz 音节呼吸只调制环境波振幅（浅层强、深层无）——运动学振幅包络，
            // 与 ambient_breath 同类机制，不改动已成形的动态浪（D12）。
            val breathGain = if (ls.i >= DEEP_LAYER_START) 1.0
                             else 1.0 + 0.30 * breath01 * (1.0 - 0.55 * ls.depth01)
            val amb = ls.ambientSampleDp
            ls.ambient.sampleInto(uGrid, t,
                p.lget("ambient_amp_dp", ls.i) * ambientGain * breathGain,
                ambientBreath, amb)
            val lagK = ls.thetaEff - thRender
            // 声像位移会把整片已显示的主浪一起横向平移，最多 ±24dp。pan01 的
            // 时间常数只有 0.22s，立体声像一动，屏幕里的浪就整体瞬移——正是
            // "已有的浪不该瞬间改变"要禁止的东西。这里对几何位移单独限速：
            // 每秒最多走 PAN_SHIFT_SLEW_DPS，读作缓慢的声场偏移而不是跳变。
            // 光学（pan01 本身）不受影响，仍按 0.22s 跟随。
            val panGoal = (pan01 - 0.5) * 48.0 * (0.35 + 0.65 * ls.depth01)
            val panDelta = PAN_SHIFT_SLEW_DPS * dt
            ls.panShiftDp += (panGoal - ls.panShiftDp).coerceIn(-panDelta, panDelta)
            val spatialShift = ls.panShiftDp
            val heroShiftedX = ls.heroShiftedX
            for (n in 0 until N_POINTS) heroShiftedX[n] = uGrid[n] + spatialShift
            val hero = ls.heroSampleDp
            ls.hero.sampleInto(
                heroShiftedX,
                ls.heroBandFieldDp,
                t,
                heroBreath,
                heroVisibleMask,
                ls.shapeRoughness01,
                hero
            )
            val wu = ls.wave.u
            val row = heights[ls.i]
            // 层判定提到循环外：巨浪只在 L0，逐点重判等于每帧多 216 次比较。
            val dominatedLayer =
                ls.i == FableSolGrandWave.LAYER_INDEX && grandProfile != null
            var biasSum = 0.0
            for (n in 0 until N_POINTS) {
                var detail = wu[n] + amb[n] + hero[n]
                if (dominatedLayer) {
                    val plain = detail
                    detail = detail * grandKeep[n] + grandProfile!![n]
                    biasSum += detail - plain
                }
                row[n] = level + detail + lagK * uGrid[n]
            }
            // 巨浪对本层均值的全部影响：自身隆起，加上冠部压掉的背景。两者都是
            // 局部的（主要落在画外），都不是本层的直流分量，必须一起从去 DC 与
            // 基准高度里扣掉，否则整层会在浪进出网格时瞬间上下跳（D178）。
            // 只有持有 L0 的那个 worker 会写，行并行的汇合建立 happens-before。
            if (dominatedLayer) grandDcBiasDp = biasSum / N_POINTS
        }
    }

    /**
     * Hero 声音包络的出生与传播（D17）：[heroBandDp] 是上游源的慢目标，只有源区会被写入；
     * 可见区里的包络只做平流，不因下一帧响度、频段或音高变化而整体改形。
     */
    private fun prepareHeroSourceBlend(visibleHalf: Double) {
        val upstreamEdge = if (FLOW_DIR < 0.0) uGrid[N_POINTS - 1] else uGrid[0]
        val available = max(abs(upstreamEdge) - visibleHalf - DX_DP, DX_DP)
        val sourceGap = min(HERO_ENVELOPE_SOURCE_GAP_DP, available)
        val sourceBoundary = if (FLOW_DIR < 0.0) {
            visibleHalf + sourceGap
        } else {
            -visibleHalf - sourceGap
        }
        val blendWidth = max(HERO_ENVELOPE_BLEND_DP, 6.0 * DX_DP)
        for (n in 0 until N_POINTS) {
            val q = if (FLOW_DIR < 0.0) {
                (uGrid[n] - (sourceBoundary - blendWidth)) / blendWidth
            } else {
                ((sourceBoundary + blendWidth) - uGrid[n]) / blendWidth
            }.coerceIn(0.0, 1.0)
            heroSourceWeight[n] = q * q * q * (q * (q * 6.0 - 15.0) + 10.0)
        }
    }

    /**
     * Hero 的声音能量只在上游出生，随后随载波一起进入可见区。
     *
     * 2026-07-21：三个尺度组过去共用一个"流速×1.5 + 0.45×波速"的输运速度，
     * 但每个组的载波各按自己的 `c=sqrt(g/k)` 行进。L0 安静时包络 142dp/s、
     * 载波只有 93dp/s，包络于是从自己的波峰上滑过去——屏幕里的浪原地长高变矮，
     * 正是"浪自己变来变去"的主要来源。现在每组的包络与它自己的载波同速。
     */
    private fun advectHeroEnvelope(ls: FableSolLayerSim, dt: Double) {
        if (dt <= 0.0) return
        val last = N_POINTS - 1
        val heroInterpIndex = ls.heroInterpIndex
        val heroInterpFraction = ls.heroInterpFraction
        for (band in 0 until 3) {
            val transport = FLOW_DIR * (abs(ls.flowDps) + ls.hero.bandPhaseSpeedDps[band])
            // uGrid 等距；索引 -1/-2 表示左右越界。插值索引/权重按层持有（C3），
            // 层任务之间不会互相踩踏。
            val positionOffset = -transport * dt / DX_DP
            for (n in 0 until N_POINTS) {
                val position = n + positionOffset
                when {
                    position < 0.0 -> {
                        heroInterpIndex[n] = HERO_INTERP_LEFT
                        heroInterpFraction[n] = 0.0
                    }
                    position > last.toDouble() -> {
                        heroInterpIndex[n] = HERO_INTERP_RIGHT
                        heroInterpFraction[n] = 0.0
                    }
                    position >= last.toDouble() -> {
                        heroInterpIndex[n] = last - 1
                        heroInterpFraction[n] = 1.0
                    }
                    else -> {
                        val i0 = position.toInt()
                        heroInterpIndex[n] = i0
                        heroInterpFraction[n] = position - i0
                    }
                }
            }
            val field = ls.heroBandFieldDp[band]
            val scratch = ls.heroBandScratchDp[band]
            val source = ls.heroBandDp[band]
            for (n in 0 until N_POINTS) {
                val i0 = heroInterpIndex[n]
                scratch[n] = when (i0) {
                    HERO_INTERP_LEFT -> if (FLOW_DIR > 0.0) source else field[0]
                    HERO_INTERP_RIGHT -> if (FLOW_DIR < 0.0) source else field[last]
                    else -> field[i0] + (field[i0 + 1] - field[i0]) * heroInterpFraction[n]
                }
            }
            for (n in 0 until N_POINTS) {
                val advected = scratch[n]
                field[n] = advected + (source - advected) * heroSourceWeight[n]
            }
        }
    }

    // ---- 渲染取数 ----
    /** 当前可见窗口（对应 render_geometry 的 abs(u)<=span/2+4dx 掩码，连续区间）。 */
    fun renderInfo(): FableSolRenderInfo {
        val span = geometrySpan()
        val hG = geometryHg()
        val th = Math.toRadians(thetaDeg)
        val half = span / 2.0 + 4.0 * DX_DP
        var i0 = 0
        while (i0 < N_POINTS && uGrid[i0] < -half) i0++
        var i1 = N_POINTS
        while (i1 > i0 && uGrid[i1 - 1] > half) i1--
        return FableSolRenderInfo(i0, i1, th, hG)
    }

    /**
     * 连续水面的专用采样窗口。最远行会被弱透视收窄，Gerstner 轨道又可向内偏移；
     * 若复用旧九层的未投影窗口，滚转/俯仰时两侧会露出环境背景。
     */
    fun continuousRenderInfo(): FableSolRenderInfo {
        val span = geometrySpan()
        val hG = geometryHg()
        val th = Math.toRadians(thetaDeg)
        val perspectiveDen = 1.0 + CONTINUOUS_PERSPECTIVE * CONTINUOUS_MAX_Z01
        val requiredHalf = min(
            U_HALF_DP - 2.0 * DX_DP,
            (span / 2.0 + 2.0 * DX_DP) * perspectiveDen + CONTINUOUS_MAX_ORBIT_DP
        )
        var i0 = 0
        while (i0 < N_POINTS && uGrid[i0] < -requiredHalf) i0++
        i0 = max(i0 - 1, 0) // 必须包含边界外第一列，不能向内取整
        var i1 = N_POINTS
        while (i1 > i0 && uGrid[i1 - 1] > requiredHalf) i1--
        i1 = min(i1 + 1, N_POINTS)
        return FableSolRenderInfo(i0, i1, th, hG)
    }

    /**
     * 各层的直流分量：既用于 compose 的去 DC，也用于渲染的深度基准高度。
     *
     * 巨浪出生在网格右端之外，但侧翼会盖住最右侧几十列；它的隆起与冠部压制都是
     * 局部的，不属于任何一层的直流。所有消费点必须用同一个口径，漏掉任何一个，
     * 浪进出网格时整层就会瞬间上下跳（D178）。
     */
    fun fillLayerDcDp(out: DoubleArray) {
        for (i in 0 until N_LAYERS) {
            var sum = 0.0
            for (v in heights[i]) sum += v
            out[i] = sum / heights[i].size
        }
        out[FableSolGrandWave.LAYER_INDEX] -= grandDcBiasDp
    }

    /** 连续曲面的实际渲染列数；由 View 与回归测试共享。 */
    fun continuousRenderColumnCount(rawColumns: Int): Int =
        if (rawColumns < 2) rawColumns else CONTINUOUS_RENDER_COLUMNS

    internal fun continuousRenderSourcePosition(i0: Int, rawColumns: Int,
                                                renderedColumns: Int, column: Int): Double =
        i0 + (rawColumns - 1).toDouble() * column / max(renderedColumns - 1, 1)

    internal fun boundaryProfileRevisionForTest(): Int = boundaryProfileRevision

    internal fun boundaryProfileValueForTest(layer: Int, point: Int): DoubleArray = doubleArrayOf(
        spongeDecay[layer][point],
        vDecay[layer][point],
        visc[layer][point],
        cScale[layer][point],
        lagTaper[layer][point]
    )

    private fun clip01(x: Double): Double = if (x < 0.0) 0.0 else if (x > 1.0) 1.0 else x

    private fun smoothstep(lo: Double, hi: Double, value: Double): Double {
        val q = ((value - lo) / max(hi - lo, 1e-6)).coerceIn(0.0, 1.0)
        return q * q * (3.0 - 2.0 * q)
    }

    companion object {
        /** 声像几何位移的最大速率（dp/s），见 perFrame 里的 panShiftDp。 */
        private const val PAN_SHIFT_SLEW_DPS = 9.0
        private const val HERO_ENVELOPE_SOURCE_GAP_DP = 48.0
        private const val HERO_ENVELOPE_BLEND_DP = 24.0
        private const val HERO_INTERP_LEFT = -1
        private const val HERO_INTERP_RIGHT = -2
        private const val SPONGE_RATE = 9.0
        private const val SPONGE_FREE_DP = 96.0
        private const val EDGE_BIRTH_GAP_DP = 8.0  // 出生支撑外缘到可见边的最小间隙
        private const val WALL_ON_DEG = 8.0
        private const val SHAKE_AMP_DEG = 8.0
        private const val SHAKE_FREQ_HZ = 3.2
        private const val SHAKE_TAU_S = 0.45
        private const val CONTINUOUS_PERSPECTIVE = 0.16
        private const val CONTINUOUS_MAX_Z01 = 1.1
        private const val CONTINUOUS_MAX_ORBIT_DP = 10.0
        /** 屏幕侧固定重建列数；物理网格仍由 N_POINTS=216 决定。 */
        private const val CONTINUOUS_RENDER_COLUMNS = 196
        private const val BOUNDARY_PROFILE_DT = 1.0 / 30.0
        private const val BOUNDARY_LAYERS_PER_FRAME = 5
        private val ATK_MULT = doubleArrayOf(1.15, 1.0, 0.85)
        private val REL_MULT = doubleArrayOf(1.15, 1.0, 0.85)
    }
}

/** 渲染窗口信息（可见列区间 [i0,i1) + 渲染角 + 容器沿重力向范围）。 */
class FableSolRenderInfo(
    @JvmField val i0: Int,
    @JvmField val i1: Int,
    @JvmField val thetaRad: Double,
    @JvmField val hG: Double
)
