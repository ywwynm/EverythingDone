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
    @JvmField val crestVeil = DoubleArray(N_POINTS)

    @JvmField var heroDp = 0.0
    @JvmField var heroTargetDp = 0.0
    @JvmField var heroPunch01 = 0.0  // 旧调用兼容；实时/演示路径不得写入
    // 三个 Hero 频段的空间能量包络。目标值只写入上游出生区，再随流进入可见区；
    // 禁止逐帧音频直接重缩放整段已可见 Hero 几何（D17）。
    @JvmField val heroBandDp = DoubleArray(3)
    @JvmField val heroBandTargetDp = DoubleArray(3)
    @JvmField val heroBandFieldDp = Array(3) { DoubleArray(N_POINTS) }
    @JvmField internal val heroBandScratchDp = Array(3) { DoubleArray(N_POINTS) }
    @JvmField val heroPunchBand01 = DoubleArray(3)
    @JvmField var roughness01 = 0.0
    @JvmField var roughnessTarget01 = 0.0
    @JvmField var shapeRoughness01 = 0.0
    @JvmField var capillary01 = 0.0
    @JvmField var capillaryTarget01 = 0.0

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
    @JvmField var surgeLiftDp = 0.0
    @JvmField var surgeLiftTargetDp = 0.0
    @JvmField var flowDps = 0.0
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

    @JvmField var flow01 = 0.0
    @JvmField var flow01Deep = 0.0   // 深层流速驱动：mapper 的数十秒积分（D16）
    @JvmField var breath01 = 0.0     // 4Hz 音节呼吸（mapper 写入；只调制环境波振幅）
    @JvmField var sparkle01 = 0.0    // 闪点活跃度（mapper：慢响度×材质档；渲染侧消费）
    @JvmField var calm01 = 1.0       // 平静度（idle/silence 权重→顶边羽化；渲染侧消费）
    @JvmField var resonance01 = 0.0  // 共鸣度（melodic/loud 权重→墙侧驻波）
    @JvmField var tension01 = 0.0    // 张力（A6 相位试验：波速相干偏置）；请经 setTension01 写入
    private var cMean = 150.0        // wave_speed_dps 九层均值（setTension01 时缓存）

    // 猫爪阵风（对应 Python gusts 列表 [{u, age, life, strength, seed}]）：
    // 上限 5 条先进先出；定长并行数组 + 计数实现，帧内零分配。渲染侧按 gustCount 读取。
    @JvmField val gustU = DoubleArray(MAX_GUSTS)
    @JvmField val gustAge = DoubleArray(MAX_GUSTS)
    @JvmField val gustLife = DoubleArray(MAX_GUSTS)
    @JvmField val gustStrength = DoubleArray(MAX_GUSTS)
    @JvmField val gustSeed = DoubleArray(MAX_GUSTS)
    var gustCount = 0
        private set

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
    @JvmField var capillary01 = 0.0
    @JvmField var spectralTilt01 = 0.5
    private var materialTargetRough = 0.0
    private var materialTargetCap = 0.0
    private var materialTargetTilt = 0.5
    @JvmField var stereoWidth01 = 0.0
    @JvmField var pan01 = 0.5
    private var spatialTargetWidth = 0.0
    private var spatialTargetPan = 0.5

    // 性格档
    @JvmField var moodLevelDp = 0.0
    private var moodFlow01 = 0.0
    @JvmField var moodBright = 0.0
    private var moodSpread01 = 0.0
    private val moodTargets = DoubleArray(4)

    // 倾斜
    @JvmField var thetaDeg = 0.0
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
    private val spongeDecay = Array(N_LAYERS) { DoubleArray(N_POINTS) { 1.0 } }
    private val cScale = Array(N_LAYERS) { DoubleArray(N_POINTS) { 1.0 } }
    private val vDecay = Array(N_LAYERS) { DoubleArray(N_POINTS) { 1.0 } }
    private val visc = Array(N_LAYERS) { DoubleArray(N_POINTS) }
    private val lagTaper = Array(N_LAYERS) { DoubleArray(N_POINTS) { 1.0 } }

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

    /** 猫爪阵风：音节在可见水面留下一块顺流漂移、软边消散的暗纹斑。 */
    fun spawnGust(uDp: Double, strength01: Double, seed: Double) {
        if (gustCount >= MAX_GUSTS) {   // 先进先出：挤掉最老一条（对应 Python pop(0)）
            for (k in 1 until MAX_GUSTS) {
                gustU[k - 1] = gustU[k]
                gustAge[k - 1] = gustAge[k]
                gustLife[k - 1] = gustLife[k]
                gustStrength[k - 1] = gustStrength[k]
                gustSeed[k - 1] = gustSeed[k]
            }
            gustCount = MAX_GUSTS - 1
        }
        gustU[gustCount] = uDp
        gustAge[gustCount] = 0.0
        gustLife[gustCount] = 1.1 + 0.5 * seed
        gustStrength[gustCount] = strength01.coerceIn(0.0, 1.0)
        gustSeed[gustCount] = seed
        gustCount++
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

    private fun rebuildBc(span: Double) {
        val soft = p.get("wall_soft")
        val agit = roundTo(tiltAgit * p.get("tilt_calm"), 1)
        val kSpan = roundTo(span, 1); val kBlend = roundTo(wallBlend, 2); val kSoft = roundTo(soft, 2)
        if (kSpan == bcSpan && kBlend == bcBlend && kSoft == bcSoft && agit == bcAgit) return
        bcSpan = kSpan; bcBlend = kBlend; bcSoft = kSoft; bcAgit = agit
        val edge = span / 2.0
        val blend = wallBlend
        for (ls in layers) {
            val i = ls.i
            val wall = edge + 2.0 * DX_DP + ls.wallOff * blend
            val softEff = min(soft * (1.0 + 0.9 * agit), 1.0)
            for (n in 0 until N_POINTS) {
                val au = auAbs[n]
                val profSp = clip01((au - edge - SPONGE_FREE_DP) / MARGIN_DP).let { it * it } * (1.0 - blend)
                val appr = clip01((au - (wall - ls.wallRamp)) / ls.wallRamp)
                val prof = profSp + clip01((au - wall) / 24.0) * 2.0 * blend
                spongeDecay[i][n] = exp(-PHYSICS_DT * SPONGE_RATE * prof)
                val fric = appr * appr * (7.0 * ls.wallAbsorb * softEff) * blend
                vDecay[i][n] = exp(-PHYSICS_DT * fric)
                visc[i][n] = (appr * appr * ls.wallVisc * (0.4 + softEff) * blend + 0.055 * agit).coerceIn(0.0, 0.30)
                cScale[i][n] = 1.0 - blend * clip01((au - wall) / (2.0 * DX_DP))
                val ct = clip01((au - wall) / 24.0)
                lagTaper[i][n] = (1.0 - 0.7 * appr * blend) * (1.0 - ct * ct * blend)
            }
        }
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
        val nRamp = max((p.get("inject_ramp_ms") / 1000.0 / PHYSICS_DT).toInt(), 1)
        ls.pending.add(FableSolPending(
            t = t + delayS, u = u, w = w, amp = ampDp * ls.gainMult,
            travel = travel, peak = peak.coerceIn(0.5, 2.5), stepsLeft = nRamp, total = nRamp
        ))
    }

    fun injectEvent(xDp: Double, widthDp: Double, ampDp: Double, travel: Double,
                    layerAmps: DoubleArray, cascade: Boolean) {
        val stepS = if (cascade) p.get("cascade_step_s") else 0.0
        for (ls in layers) injectLayer(ls.i, xDp, widthDp, ampDp * layerAmps[ls.i], travel, stepS * ls.i)
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
                val bump = DoubleArray(i1 - i0)
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
    fun setMaterialDrive(roughness: Double, capillary: Double, spectralTilt: Double) {
        materialTargetRough = roughness; materialTargetCap = capillary; materialTargetTilt = spectralTilt
    }
    fun setSpatialDrive(width01: Double, pan01v: Double) { spatialTargetWidth = width01; spatialTargetPan = pan01v }

    fun timeToNextBeat(): Double {
        if (beat01 < 0.05 || beatBpm <= 0.0) return 0.0
        return (1.0 - beatPhase % 1.0) * 60.0 / beatBpm
    }

    fun setMood(energy01: Double, brightness01: Double) {
        moodTargets[0] = 16.0 * (energy01 - 0.35)
        moodTargets[1] = 0.45 * (energy01 - 0.5)
        moodTargets[2] = (brightness01 - 0.5) * 2.0
        moodTargets[3] = (energy01 - 0.5) * 2.0
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
        while (acc >= PHYSICS_DT) {
            acc -= PHYSICS_DT
            t += PHYSICS_DT
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
            rebuildBc(span)
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
            for (ls in layers) {
                val base = p.lget("flow_base_dps", ls.i)
                val idle = p.get("idle_flow_ratio")
                // 深层无动于衷（D16）：流速只跟数十秒能量积分，不吃逐帧感知速度
                val driveRaw = if (ls.i >= DEEP_LAYER_START) flow01Deep.coerceIn(0.0, 1.0)
                               else (flow01 + moodFlow01).coerceIn(0.0, 1.0)
                val drive01 = driveRaw.pow(p.get("flow_curve"))
                val target = FLOW_DIR * base * (idle + (1.0 - idle) * drive01 * p.get("flow_gain"))
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
                var dLag = ((ls.thetaEff - oldEff) - dRender).coerceIn(-0.02, 0.02)
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
        }
        perFrame(dtReal, span, Math.toRadians(thetaDeg))
    }

    private fun perFrame(dt: Double, span: Double, thRender: Double) {
        val kMood = 1.0 - exp(-dt / max(p.get("mood_transition_s"), 0.05))
        moodLevelDp += (moodTargets[0] - moodLevelDp) * kMood
        moodFlow01 += (moodTargets[1] - moodFlow01) * kMood
        moodBright += (moodTargets[2] - moodBright) * kMood
        moodSpread01 += (moodTargets[3] - moodSpread01) * kMood
        colorBright01 += (colorTargetBright - colorBright01) * (1.0 - exp(-dt / 2.0))
        colorEnergy01 += (colorTargetEnergy - colorEnergy01) * (1.0 - exp(-dt / 1.2))
        roughness01 += (materialTargetRough - roughness01) * (1.0 - exp(-dt / 0.24))
        capillary01 += (materialTargetCap - capillary01) * (1.0 - exp(-dt / 0.12))
        spectralTilt01 += (materialTargetTilt - spectralTilt01) * (1.0 - exp(-dt / 0.7))
        stereoWidth01 += (spatialTargetWidth - stereoWidth01) * (1.0 - exp(-dt / 0.45))
        pan01 += (spatialTargetPan - pan01) * (1.0 - exp(-dt / 0.22))
        // 猫爪阵风：随层 1 背景流速顺流平移，超寿命剔除（原位压缩，零分配）
        val advGust = layers[1].flowDps
        var wg = 0
        for (g in 0 until gustCount) {
            val age = gustAge[g] + dt
            if (age >= gustLife[g]) continue
            gustU[wg] = gustU[g] + advGust * dt
            gustAge[wg] = age
            gustLife[wg] = gustLife[g]
            gustStrength[wg] = gustStrength[g]
            gustSeed[wg] = gustSeed[g]
            wg++
        }
        gustCount = wg
        val cTh = abs(cos(thRender)); val sTh = abs(sin(thRender))
        val half = span / 2.0
        val vis = BooleanArray(N_POINTS) { auAbs[it] <= half }
        val heroBreath = p.get("hero_breath")
        val ambientBreath = p.get("ambient_breath")
        val ambientGain = p.get("ambient_gain")
        val heroGain = p.get("hero_gain")
        val heroPunch = p.get("hero_punch") // 旧调用兼容，默认 0；快速能量只走 DynamicWave
        val punchDecay = exp(-dt / max(p.get("hero_punch_decay_s"), 0.05))
        val moodSpreadDp = p.get("mood_spread_dp")
        val wanderGain = p.get("wander_gain")
        for (ls in layers) {
            val target = ls.swellTargetDp
            val rising = target > ls.swellDp
            val tau = if (rising) p.get("swell_attack_s") * ls.attackMult else p.get("swell_release_s") * ls.releaseMult
            ls.swellDp += (target - ls.swellDp) * (1.0 - exp(-dt / max(tau, 1e-3)))
            ls.heroPunch01 *= punchDecay
            for (j in 0 until 3) ls.heroPunchBand01[j] *= punchDecay
            val heroMax = p.lget("hero_max_dp", ls.i) * heroGain
            val hTarget = min(ls.heroTargetDp + ls.heroPunch01 * heroPunch * heroMax, 1.25 * heroMax)
            val hTau = if (hTarget > ls.heroDp) p.get("hero_attack_s") * ls.attackMult else p.get("hero_release_s") * ls.releaseMult
            ls.heroDp += (hTarget - ls.heroDp) * (1.0 - exp(-dt / max(hTau, 1e-3)))
            val bandTarget = doubleArrayOf(ls.heroBandTargetDp[0], ls.heroBandTargetDp[1], ls.heroBandTargetDp[2])
            if (bandTarget[0] + bandTarget[1] + bandTarget[2] < 1e-6 && ls.heroTargetDp > 0.0) {
                bandTarget[0] = ls.heroTargetDp * 0.48; bandTarget[1] = ls.heroTargetDp * 0.34; bandTarget[2] = ls.heroTargetDp * 0.18
            }
            for (j in 0 until 3) bandTarget[j] += ls.heroPunchBand01[j] * heroPunch * heroMax
            val totalB = bandTarget[0] + bandTarget[1] + bandTarget[2]
            if (totalB > 1.25 * heroMax && totalB > 1e-6) {
                val f = 1.25 * heroMax / totalB
                for (j in 0 until 3) bandTarget[j] *= f
            }
            for (j in 0 until 3) {
                val risingB = bandTarget[j] > ls.heroBandDp[j]
                val tauB = if (risingB) p.get("hero_attack_s") * ATK_MULT[j] else p.get("hero_release_s") * REL_MULT[j]
                ls.heroBandDp[j] += (bandTarget[j] - ls.heroBandDp[j]) * (1.0 - exp(-dt / max(tauB, 1e-3)))
            }
            advectHeroEnvelope(ls, dt, half)
            ls.roughness01 += (ls.roughnessTarget01 - ls.roughness01) * (1.0 - exp(-dt / 0.26))
            ls.shapeRoughness01 += (ls.roughnessTarget01 - ls.shapeRoughness01) * (1.0 - exp(-dt / 1.2))
            val capTau = if (ls.capillaryTarget01 > ls.capillary01) 0.06 else 0.34
            ls.capillary01 += (ls.capillaryTarget01 - ls.capillary01) * (1.0 - exp(-dt / capTau))
            ls.surgeLiftTargetDp *= exp(-dt / 2.2)
            ls.surgeLiftDp += (ls.surgeLiftTargetDp - ls.surgeLiftDp) * (1.0 - exp(-dt / 0.35))
            val wander = p.lget("wander_amp_dp", ls.i) * wanderGain *
                    sin(2.0 * Math.PI * t / max(p.lget("wander_period_s", ls.i), 1.0) + ls.wanderPhi)
            val mood = moodLevelDp * (1.0 - 0.3 * ls.depth01) +
                    moodSpreadDp * moodSpread01 * (ls.depth01 - 0.5) * 2.0
            val level = tiltLevel(p.lget("base_level_dp", ls.i), cTh, sTh) +
                    wander + ls.swellDp + mood + ls.surgeLiftDp
            // 4Hz 音节呼吸只调制环境波振幅（浅层强、深层无）——运动学振幅包络，
            // 与 ambient_breath 同类机制，不改动已成形的动态浪（D12）。
            val breathGain = if (ls.i >= DEEP_LAYER_START) 1.0
                             else 1.0 + 0.30 * breath01 * (1.0 - 0.55 * ls.depth01)
            val amb = ls.ambient.sample(uGrid, t,
                p.lget("ambient_amp_dp", ls.i) * ambientGain * breathGain, ambientBreath)
            val lagK = ls.thetaEff - thRender
            val spatialShift = (pan01 - 0.5) * 48.0 * (0.35 + 0.65 * ls.depth01)
            val xShift = DoubleArray(N_POINTS) { uGrid[it] + spatialShift }
            val hero = ls.hero.sample(xShift, ls.heroBandFieldDp, t, heroBreath, vis, ls.shapeRoughness01)
            val wu = ls.wave.u
            val row = heights[ls.i]
            for (n in 0 until N_POINTS) row[n] = level + wu[n] + amb[n] + hero[n] + lagK * uGrid[n]
            updateCrestVeil(ls, dt)
        }
    }

    /**
     * Hero 声音包络的出生与传播（D17）：[heroBandDp] 是上游源的慢目标，只有源区会被写入；
     * 可见区里的包络只做平流，不因下一帧响度、频段或音高变化而整体改形。
     */
    private fun advectHeroEnvelope(ls: FableSolLayerSim, dt: Double, visibleHalf: Double) {
        if (dt <= 0.0) return
        val waveSpeed = p.lget("wave_speed_dps", ls.i)
        val transport = FLOW_DIR * (abs(ls.flowDps) * 1.5 + HERO_ENVELOPE_GROUP_SPEED * waveSpeed)
        val upstreamEdge = if (FLOW_DIR < 0) uGrid[N_POINTS - 1] else uGrid[0]
        val available = max(abs(upstreamEdge) - visibleHalf - DX_DP, DX_DP)
        val sourceGap = min(HERO_ENVELOPE_SOURCE_GAP_DP, available)
        val sourceBoundary = if (FLOW_DIR < 0) visibleHalf + sourceGap else -visibleHalf - sourceGap

        for (band in 0 until 3) {
            val field = ls.heroBandFieldDp[band]
            val scratch = ls.heroBandScratchDp[band]
            val source = ls.heroBandDp[band]
            for (n in 0 until N_POINTS) {
                val xq = uGrid[n] - transport * dt
                val pos = (xq - uGrid[0]) / DX_DP
                scratch[n] = when {
                    pos <= 0.0 -> if (FLOW_DIR > 0) source else field[0]
                    pos >= N_POINTS - 1.0 -> if (FLOW_DIR < 0) source else field[N_POINTS - 1]
                    else -> {
                        val i0 = pos.toInt()
                        val frac = pos - i0
                        field[i0] + (field[i0 + 1] - field[i0]) * frac
                    }
                }
            }
            for (n in 0 until N_POINTS) {
                field[n] = if ((FLOW_DIR < 0 && uGrid[n] >= sourceBoundary) ||
                    (FLOW_DIR > 0 && uGrid[n] <= sourceBoundary)) source else scratch[n]
            }
        }
    }

    /** 陡峭波冠的持久轻纱：由真实轮廓触发，随流平移并自然消散。 */
    private fun updateCrestVeil(ls: FableSolLayerSim, dt: Double) {
        if (ls.depth01 >= 0.34) { java.util.Arrays.fill(ls.crestVeil, 0.0); return }
        val h = heights[ls.i]
        val slope = FableSolMath.gradient(h, DX_DP)
        val curvature = FableSolMath.gradient(slope, DX_DP)
        val radius = 6
        val absSlope = DoubleArray(slope.size) { abs(slope[it]) }
        val padded = FableSolMath.padEdge(absSlope, radius)
        val nearSlope = DoubleArray(slope.size)
        for (m in slope.indices) {
            var mx = 0.0
            for (j in 0..(radius * 2)) { val vv = padded[j + m]; if (vv > mx) mx = vv }
            nearSlope[m] = mx
        }
        val depthGain = max(0.0, 1.0 - ls.depth01 / 0.34)
        val material = 0.30 + 0.70 * (0.55 * ls.roughness01 + 0.45 * ls.capillary01).coerceIn(0.0, 1.0)
        val backtrace = DoubleArray(uGrid.size) { uGrid[it] - ls.flowDps * dt }
        val advected = FableSolMath.interp(backtrace, uGrid, ls.crestVeil, 0.0, 0.0)
        val lifetime = 0.72 + 0.36 * (1.0 - ls.roughness01)
        val decay = exp(-dt / lifetime)
        val attack = 1.0 - exp(-dt / 0.12)
        val veil = ls.crestVeil
        for (n in slope.indices) {
            val crest = smoothstep(0.006, 0.018, -curvature[n])
            val steep = smoothstep(0.16, 0.34, nearSlope[n])
            val source = crest * steep * depthGain * material
            var vv = advected[n] * decay
            vv += max(source - vv, 0.0) * attack
            veil[n] = vv.coerceIn(0.0, 1.0)
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

    private fun clip01(x: Double): Double = if (x < 0.0) 0.0 else if (x > 1.0) 1.0 else x

    private fun smoothstep(lo: Double, hi: Double, value: Double): Double {
        val q = ((value - lo) / max(hi - lo, 1e-6)).coerceIn(0.0, 1.0)
        return q * q * (3.0 - 2.0 * q)
    }

    companion object {
        private const val HERO_ENVELOPE_GROUP_SPEED = 0.45
        private const val HERO_ENVELOPE_SOURCE_GAP_DP = 48.0
        private const val SPONGE_RATE = 9.0
        private const val SPONGE_FREE_DP = 96.0
        private const val EDGE_BIRTH_GAP_DP = 8.0  // 出生支撑外缘到可见边的最小间隙
        private const val WALL_ON_DEG = 8.0
        private const val MAX_GUSTS = 5          // 猫爪阵风上限（Python len(gusts) >= 5 → pop(0)）
        private const val SHAKE_AMP_DEG = 8.0
        private const val SHAKE_FREQ_HZ = 3.2
        private const val SHAKE_TAU_S = 0.45
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
