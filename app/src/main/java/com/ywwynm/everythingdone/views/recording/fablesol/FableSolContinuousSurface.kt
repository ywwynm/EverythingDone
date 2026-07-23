package com.ywwynm.everythingdone.views.recording.fablesol

import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.N_LAYERS
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.N_POINTS
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 连续 2.5D 水面的二维方向波场。
 *
 * 九层原有轮廓继续作为 Z 向几何、颜色和声音角色锚点；本类只补充远近方向传播、
 * 有限相干波包、Gerstner X/Z 轨道与前后俯仰惯性。普通实时音头仍走原九层即时
 * 注入，只有稀有远浪和段落事件进入纵向波包，避免整片既有水面被逐帧重塑。
 */
class FableSolContinuousSurface(private val p: FableSolParams) {

    private class Packet(
        var wavelength: Double,
        var angle: Double,
        var amplitude: Double,
        var x: Double,
        var z: Double,
        var sigmaX: Double,
        var sigmaZ: Double,
        var phase: Double,
        var energy: Double,
        var age: Double,
        var life: Double
    )

    /** 每帧复用的采样结果，所有二维数组均为 [Z_ROWS][N_POINTS]。 */
    class Sample internal constructor() {
        @JvmField val z01 = DoubleArray(Z_ROWS) { it.toDouble() / (Z_ROWS - 1) }
        @JvmField val zDp = DoubleArray(Z_ROWS)
        @JvmField var depthDp = 0.0
        @JvmField val eta = Array(Z_ROWS) { DoubleArray(N_POINTS) }
        @JvmField val orbitX = Array(Z_ROWS) { DoubleArray(N_POINTS) }
        @JvmField val orbitZ = Array(Z_ROWS) { DoubleArray(N_POINTS) }
        @JvmField val orbitXSlope = Array(Z_ROWS) { DoubleArray(N_POINTS) }
        @JvmField val orbitZSlope = Array(Z_ROWS) { DoubleArray(N_POINTS) }
        @JvmField val slopeX = Array(Z_ROWS) { DoubleArray(N_POINTS) }
        @JvmField val slopeZ = Array(Z_ROWS) { DoubleArray(N_POINTS) }
        @JvmField val worldEta = Array(Z_ROWS) { DoubleArray(N_POINTS) }
        /**
         * 本帧实际求值的列区间（闭区间）。窗口外的元素是上一帧的陈旧值，不得读取。
         * `buildFrame` 只读 `[i0, i1-1]`，恒在此区间内。
         */
        @JvmField var windowLo = 0
        @JvmField var windowHi = N_POINTS - 1
    }

    private val wavelength = doubleArrayOf(420.0, 330.0, 260.0, 205.0, 164.0,
        132.0, 102.0, 78.0, 58.0)
    private val baseAmplitude = doubleArrayOf(2.8, 2.5, 2.15, 1.85, 1.55,
        1.25, 0.92, 0.66, 0.42)
    private val band = intArrayOf(0, 0, 0, 1, 1, 1, 2, 2, 2)
    private val angleUnit = doubleArrayOf(-0.42, 0.05, 0.38, -0.55, 0.18,
        0.72, -0.88, 0.36, 1.0)
    private val spreadScale = doubleArrayOf(0.34, 0.42, 0.50, 0.62, 0.72,
        0.82, 0.94, 1.06, 1.18)
    private val phase = DoubleArray(wavelength.size)
    private val energyBand = DoubleArray(3)
    private val packets = ArrayList<Packet>(8)
    private val rng = FableSolRng(7319L)
    private val sample = Sample()
    private val k = DoubleArray(wavelength.size)
    private val kx = DoubleArray(wavelength.size)
    private val kz = DoubleArray(wavelength.size)
    private var nextPacketT = 0.0
    // 修复发生在行并行区内，故用原子计数。
    private val worldRepairRows = java.util.concurrent.atomic.AtomicInteger(0)

    // Step A：打光法线改从真渲染面 worldEta 求，跨层用 Catmull-Rom 平滑防止层锚点处的坡度
    // 跳变（横向接缝）。行间插值权重只依赖固定的 z01[r]，init 预计算一次，稳态零分配。
    private val layerMean = DoubleArray(N_LAYERS)
    private val depthMeanX = DoubleArray(N_POINTS)
    private val crRowIndex = Array(Z_ROWS) { IntArray(4) }
    private val crRowWeight = Array(Z_ROWS) { DoubleArray(4) }
    // 原始控制样本与公开的 fair C2 结果分离：行并行阶段只读 raw、只写 Sample，
    // 无原地邻点覆盖，也不需要逐帧临时数组。
    private val rawWorldEta = Array(Z_ROWS) { DoubleArray(N_POINTS) }
    private val rawOrbitX = Array(Z_ROWS) { DoubleArray(N_POINTS) }
    private val rawOrbitZ = Array(Z_ROWS) { DoubleArray(N_POINTS) }

    // sample() 并行行阶段的只读预备量（逐模态/逐波包标量与 X 向包络），
    // 全部在串行预备段写入、并行段只读，稳态零分配。
    private val modeAmp = DoubleArray(wavelength.size)
    private val modeOrbitXWeight = DoubleArray(wavelength.size)
    private val modeOrbitZWeight = DoubleArray(wavelength.size)
    private val modeStepCos = DoubleArray(wavelength.size)
    private val modeStepSin = DoubleArray(wavelength.size)
    private val modeBaseX = DoubleArray(wavelength.size)
    private val packetAmp = DoubleArray(MAX_PACKETS)
    private val packetOrbitXWeight = DoubleArray(MAX_PACKETS)
    private val packetOrbitZWeight = DoubleArray(MAX_PACKETS)
    private val packetKz = DoubleArray(MAX_PACKETS)
    private val packetBaseX = DoubleArray(MAX_PACKETS)
    private val packetPhaseOffset = DoubleArray(MAX_PACKETS)
    private val packetCenterZ = DoubleArray(MAX_PACKETS)
    private val packetSigmaZ = DoubleArray(MAX_PACKETS)
    private val packetStepCos = DoubleArray(MAX_PACKETS)
    private val packetStepSin = DoubleArray(MAX_PACKETS)
    private val packetEnvX = Array(MAX_PACKETS) { DoubleArray(N_POINTS) }
    // 波向量只依赖两个 params；参数未变时跳过重算（纯函数缓存，逐位一致）。
    private var waveVectorHeading = Double.NaN
    private var waveVectorSpread = Double.NaN
    @JvmField internal var lastCommonTransportDpsForTest = 0.0

    // field 列外层推进用的逐行相位状态：前 modeCount 槽是模态，其后是波包。
    // 按行预分配（每行只由一个线程处理），热路径零分配。
    private val fieldPhaseCos = Array(Z_ROWS) { DoubleArray(FIELD_CHAIN_SLOTS) }
    private val fieldPhaseSin = Array(Z_ROWS) { DoubleArray(FIELD_CHAIN_SLOTS) }
    private val fieldPacketEnvZ = Array(Z_ROWS) { DoubleArray(MAX_PACKETS) }
    /** 测试专用：强制走旧的「模态外层、列内层」路径，用于与列外层实现逐位对拍。 */
    internal var forceModeOuterFieldForTest = false

    @JvmField var pitchEffRad = 0.0
    private var pitchVelocity = 0.0

    // sample() 的分段计时（2026-07-21 帧率排查）。桌面 JVM 与 Android ART 对同一段
    // 代码的相对代价差异可达两个数量级（`Math.cbrt` 在 ART 上是 libcore 的纯 Java
    // FDLIBM，桌面 HotSpot 则近乎免费），因此这几段必须在真机上分别读数。
    @JvmField var perfPrepNs = 0L
    @JvmField var perfFieldNs = 0L
    @JvmField var perfLimitNs = 0L
    @JvmField var perfFairNs = 0L
    @JvmField var perfSlopeNs = 0L
    /** 本帧参与方向场累加的波包数。自然生成上限 7，但事件注入可在两次剪枝间叠到 24。 */
    @JvmField var perfPacketCount = 0
    /**
     * 世界空间单调修复实际触发的行数。这是"把 sample() 裁到渲染窗口"是否安全的唯一未知量：
     * 波包按设计先生在画外，画外正是 orbitX 幅度最大处；若该修复真的会触发，裁窗后
     * 可见区的行缩放比例就会变，倾斜导致窗口边界跳变时还会出现幅度突跳。若实测恒为 0，
     * 则裁窗在实际参数下不改变任何输出。
     */
    @JvmField var perfWorldRepairRows = 0

    init {
        require(wavelength.size + MAX_PACKETS <= FIELD_CHAIN_SLOTS)
        for (j in wavelength.indices) phase[j] = rng.uniform(0.0, 2.0 * PI)
        for (q in doubleArrayOf(0.18, 0.50, 0.82)) spawnPacket(null, q)
        for (r in 0 until Z_ROWS) {
            val f = (r.toDouble() / (Z_ROWS - 1)) * (N_LAYERS - 1)
            val i0 = min(f.toInt(), N_LAYERS - 2)
            val q = f - i0
            crRowIndex[r][0] = max(i0 - 1, 0)
            crRowIndex[r][1] = i0
            crRowIndex[r][2] = i0 + 1
            crRowIndex[r][3] = min(i0 + 2, N_LAYERS - 1)
            crRowWeight[r][0] = 0.5 * (-q + 2.0 * q * q - q * q * q)
            crRowWeight[r][1] = 0.5 * (2.0 - 5.0 * q * q + 3.0 * q * q * q)
            crRowWeight[r][2] = 0.5 * (q + 4.0 * q * q - 3.0 * q * q * q)
            crRowWeight[r][3] = 0.5 * (-q * q + q * q * q)
        }
    }

    fun setPitch(degrees: Double, snap: Boolean = false) {
        val target = Math.toRadians(degrees.coerceIn(
            -FableSolPitchPolicy.MOTION_LIMIT_DEG,
            FableSolPitchPolicy.MOTION_LIMIT_DEG
        ))
        if (snap) {
            pitchEffRad = target
            pitchVelocity = 0.0
        }
    }

    /**
     * 九层锚点减去各层均值后跨层 Catmull-Rom 平滑插值，再叠加二维方向场（并去掉与深度无关的
     * 公共横波）。锚点行（q=0/1）严格穿过该层自身轮廓，因此不改变既有分层语义；平滑插值消除
     * 线性混合在层锚点处的坡度跳变，使从 worldEta 求得的打光法线不产生横向接缝。
     */
    fun composeLayerField(layerHeights: Array<DoubleArray>,
                          directionalEta: Array<DoubleArray>): Array<DoubleArray> {
        prepareComposeMeans(layerHeights, directionalEta)
        FableSolRowParallel.run(Z_ROWS) { startRow, endRow ->
            for (r in startRow until endRow) composeRow(r, layerHeights, directionalEta)
        }
        return rawWorldEta
    }

    /**
     * 合成前的串行预备：逐层均值与逐列纵深均值。
     * `depthMeanX` 是整列跨全部 Z_ROWS 的均值，必须等方向场累加全部完成，
     * 因此它天然是 [sample] 里唯一无法回避的跨行汇合点。
     */
    private fun prepareComposeMeans(layerHeights: Array<DoubleArray>,
                                    directionalEta: Array<DoubleArray>,
                                    lo: Int = 0,
                                    hi: Int = N_POINTS - 1,
                                    grandDcBiasDp: Double = 0.0) {
        // layerMean 必须保留全 216 列——它是各层的 DC 去除项，裁窗会改变水位。
        // grandDcBiasDp 扣掉巨浪引入的局部 DC，与 FableSolSimulation.fillLayerDcDp
        // 是同一口径的两个消费点，改动必须同步（D178）。
        for (i in 0 until N_LAYERS) {
            var sum = 0.0
            for (v in layerHeights[i]) sum += v
            layerMean[i] = sum / layerHeights[i].size
        }
        layerMean[FableSolGrandWave.LAYER_INDEX] -= grandDcBiasDp
        // depthMeanX 是逐列的纵深均值，只有窗口内的列会被消费，可以裁。
        // 行外层、列内层：directionalEta 是行主序 [97][216]，列外层遍历每一步都要
        // 换 cache line 并重做一次外层解引用。每列的加法到达顺序仍是 r=0→96，
        // 除法同样只在最后做一次，逐位一致。
        val means = depthMeanX
        java.util.Arrays.fill(means, lo, hi + 1, 0.0)
        for (r in 0 until Z_ROWS) {
            val row = directionalEta[r]
            for (x in lo..hi) means[x] += row[x]
        }
        for (x in lo..hi) means[x] = means[x] / Z_ROWS
    }

    /** 单行合成；只写 `rawWorldEta[r]`，行间无共享写。 */
    private fun composeRow(r: Int, layerHeights: Array<DoubleArray>,
                           directionalEta: Array<DoubleArray>,
                           lo: Int = 0, hi: Int = N_POINTS - 1) {
        val idx = crRowIndex[r]
        val w = crRowWeight[r]
        val h0 = layerHeights[idx[0]]; val m0 = layerMean[idx[0]]; val w0 = w[0]
        val h1 = layerHeights[idx[1]]; val m1 = layerMean[idx[1]]; val w1 = w[1]
        val h2 = layerHeights[idx[2]]; val m2 = layerMean[idx[2]]; val w2 = w[2]
        val h3 = layerHeights[idx[3]]; val m3 = layerMean[idx[3]]; val w3 = w[3]
        val worldRow = rawWorldEta[r]
        val dirRow = directionalEta[r]
        for (x in lo..hi) {
            worldRow[x] = w0 * (h0[x] - m0) + w1 * (h1[x] - m1) +
                w2 * (h2[x] - m2) + w3 * (h3[x] - m3) +
                dirRow[x] - depthMeanX[x]
        }
    }

    /**
     * field 的列外层实现：12 组 (phCos, phSin) 作为状态数组一起沿列推进，
     * 每列以寄存器 `s = 0.0` 起步按 j 升序、再 packet 升序累加，最后一次 store。
     *
     * 与 [accumulateFieldRowModeOuter] 逐位等价：
     * - 固定列 x 的加法到达顺序仍是模态 0→8、再波包 0→k−1，起点同为 `0.0`
     *   （旧路径的起点是 `Arrays.fill(..., 0.0)`，`0.0 + y` 只在 y = −0.0 时
     *   与 y 不同，而那正是 fill 版的行为，两边一致）；
     * - 每条递推链的初值、步进旋转与推进次数不变，只是 12 条链改为交错推进
     *   （彼此独立，延迟可被流水线隐藏）；
     * - 波包项保持 `a * envX[x] * envZ` 的原乘法结合顺序。
     *
     * 收益来自内存趟数：eta/orbitX/orbitZ 从约 12 趟 read-modify-write 降为 1 趟写。
     */
    private fun accumulateFieldRowColumnOuter(
        r: Int,
        rawLo: Int,
        rawHi: Int,
        modeCount: Int,
        packetCount: Int
    ) {
        val etaRow = sample.eta[r]
        val orbitXRow = rawOrbitX[r]
        val orbitZRow = rawOrbitZ[r]
        val z = sample.zDp[r]
        val phaseCos = fieldPhaseCos[r]
        val phaseSin = fieldPhaseSin[r]
        val packetEnvZ = fieldPacketEnvZ[r]
        for (j in 0 until modeCount) {
            val rowPhase = modeBaseX[j] + kz[j] * z + phase[j]
            phaseCos[j] = cos(rowPhase)
            phaseSin[j] = sin(rowPhase)
        }
        for (index in 0 until packetCount) {
            val dz = (z - packetCenterZ[index]) / packetSigmaZ[index]
            packetEnvZ[index] = exp(-0.5 * dz * dz)
            val rowPhase = packetBaseX[index] + packetKz[index] * z +
                packetPhaseOffset[index]
            val slot = modeCount + index
            phaseCos[slot] = cos(rowPhase)
            phaseSin[slot] = sin(rowPhase)
        }
        for (x in rawLo..rawHi) {
            var etaSum = 0.0
            var orbitXSum = 0.0
            var orbitZSum = 0.0
            for (j in 0 until modeCount) {
                val phCos = phaseCos[j]
                val phSin = phaseSin[j]
                etaSum += modeAmp[j] * phCos
                orbitXSum += phSin * modeOrbitXWeight[j]
                orbitZSum += phSin * modeOrbitZWeight[j]
                val stepCos = modeStepCos[j]
                val stepSin = modeStepSin[j]
                phaseCos[j] = phCos * stepCos - phSin * stepSin
                phaseSin[j] = phSin * stepCos + phCos * stepSin
            }
            for (index in 0 until packetCount) {
                val slot = modeCount + index
                val phCos = phaseCos[slot]
                val phSin = phaseSin[slot]
                val local = packetAmp[index] * packetEnvX[index][x] * packetEnvZ[index]
                etaSum += local * phCos
                val tang = local * phSin
                orbitXSum += tang * packetOrbitXWeight[index]
                orbitZSum += tang * packetOrbitZWeight[index]
                val stepCos = packetStepCos[index]
                val stepSin = packetStepSin[index]
                phaseCos[slot] = phCos * stepCos - phSin * stepSin
                phaseSin[slot] = phSin * stepCos + phCos * stepSin
            }
            etaRow[x] = etaSum
            orbitXRow[x] = orbitXSum
            orbitZRow[x] = orbitZSum
        }
    }

    /** 旧的「模态外层、列内层」实现；只在对拍开关打开时执行，是逐位等价的参照。 */
    private fun accumulateFieldRowModeOuter(
        r: Int,
        rawLo: Int,
        rawHi: Int,
        modeCount: Int,
        packetCount: Int
    ) {
        val etaRow = sample.eta[r]
        val orbitXRow = rawOrbitX[r]
        val orbitZRow = rawOrbitZ[r]
        java.util.Arrays.fill(etaRow, rawLo, rawHi + 1, 0.0)
        java.util.Arrays.fill(orbitXRow, rawLo, rawHi + 1, 0.0)
        java.util.Arrays.fill(orbitZRow, rawLo, rawHi + 1, 0.0)
        val z = sample.zDp[r]
        for (j in 0 until modeCount) {
            val amp = modeAmp[j]
            val orbitXWeight = modeOrbitXWeight[j]
            val orbitZWeight = modeOrbitZWeight[j]
            val stepCos = modeStepCos[j]
            val stepSin = modeStepSin[j]
            val rowPhase = modeBaseX[j] + kz[j] * z + phase[j]
            var phCos = cos(rowPhase)
            var phSin = sin(rowPhase)
            for (x in rawLo..rawHi) {
                etaRow[x] += amp * phCos
                orbitXRow[x] += phSin * orbitXWeight
                orbitZRow[x] += phSin * orbitZWeight
                val nextCos = phCos * stepCos - phSin * stepSin
                phSin = phSin * stepCos + phCos * stepSin
                phCos = nextCos
            }
        }
        for (index in 0 until packetCount) {
            val dz = (z - packetCenterZ[index]) / packetSigmaZ[index]
            val envZ = exp(-0.5 * dz * dz)
            val a = packetAmp[index]
            val orbitXWeight = packetOrbitXWeight[index]
            val orbitZWeight = packetOrbitZWeight[index]
            val stepCos = packetStepCos[index]
            val stepSin = packetStepSin[index]
            val envX = packetEnvX[index]
            val rowPhase = packetBaseX[index] + packetKz[index] * z +
                packetPhaseOffset[index]
            var phCos = cos(rowPhase)
            var phSin = sin(rowPhase)
            for (x in rawLo..rawHi) {
                val local = a * envX[x] * envZ
                etaRow[x] += local * phCos
                val tang = local * phSin
                orbitXRow[x] += tang * orbitXWeight
                orbitZRow[x] += tang * orbitZWeight
                val nextCos = phCos * stepCos - phSin * stepSin
                phSin = phSin * stepCos + phCos * stepSin
                phCos = nextCos
            }
        }
    }

    /**
     * 软饱和与单调修复曾经单独派发，只为在真机上把「模态/波包累加」与「逐点软饱和」
     * 分开读数；诊断已完成，C6 把它并回 field 的行体。lift / softLimit / 单调修复
     * 都只依赖本行的 field 结果，逐行数学与拆开时逐位一致，省掉一次汇合和
     * eta/orbitX/orbitZ 三数组的整轮重读重写。
     */
    private fun limitRow(r: Int, rawLo: Int, rawHi: Int, lag: Double, depth: Double,
                         grandKeep: DoubleArray) {
        val etaRow = sample.eta[r]
        val orbitXRow = rawOrbitX[r]
        val orbitZRow = rawOrbitZ[r]
        val lift = lag * (sample.zDp[r] - 0.5 * depth)
        for (x in rawLo..rawHi) {
            // 冠部支配 mask 必须同时覆盖方向场与轨道（D178）。它此前只作用于 L0
            // 的锚点 detail，而这两项是在锚层之后合成的，于是巨浪平顶上仍浮着约
            // 14dp 的方向模态与波包、外加 ±10dp 轨道位移，正是「顶不平」的来源。
            // 倾斜滞后 lift 是整体斜面而非水面细节，不参与压制。
            val keep = grandKeep[x]
            etaRow[x] = etaRow[x] * keep + lift
            // 硬裁剪会在 ±10dp 处突然把导数归零。高阶软饱和保留
            // 常用区间，同时让极值仍有连续导数，不产生平台或尖点。
            // 在压制之后施加，使上限仍作用于实际位移。
            orbitXRow[x] = FableSolCubicResampler.softLimit(orbitXRow[x] * keep, 10.0)
            orbitZRow[x] = FableSolCubicResampler.softLimit(orbitZRow[x] * keep, 10.0)
        }
        // 若多源叠加逼近横向翻折，只用一个全行比例把 X 轨道朝无轨道
        // 基线收回；禁止逐点 accumulate/clip 制造局部平段与阶梯。
        if (FableSolCubicResampler.repairOrbitRowMonotone(
                orbitXRow,
                FableSolSpec.DX_DP,
                WORLD_MINIMUM_SPACING_RATIO,
                rawLo,
                rawHi
            ) < 1.0
        ) {
            worldRepairRows.incrementAndGet()
        }
    }

    private fun updateWaveVectors() {
        val headingDeg = p.get("surface_heading_deg")
        val spreadDeg = p.get("surface_spread_deg")
        if (headingDeg == waveVectorHeading && spreadDeg == waveVectorSpread) return
        waveVectorHeading = headingDeg
        waveVectorSpread = spreadDeg
        val heading = Math.toRadians(headingDeg)
        val spread = Math.toRadians(spreadDeg)
        for (j in wavelength.indices) {
            val angle = (heading + angleUnit[j] * spread * spreadScale[j])
                .coerceIn(Math.toRadians(-18.0), Math.toRadians(62.0))
            k[j] = 2.0 * PI / wavelength[j]
            kx[j] = -k[j] * cos(angle)
            kz[j] = -k[j] * sin(angle)
        }
    }

    private fun depthSpanDp(): Double {
        val projected = abs(p.lget("base_level_dp", 8) - p.lget("base_level_dp", 0))
        val elev = Math.toRadians(max(p.get("surface_view_elev_deg"), 12.0))
        return (projected / max(sin(elev), 0.2)).coerceIn(84.0, 180.0)
    }

    private fun spawnPacket(sim: FableSolSimulation?, initialQ: Double? = null,
                            strength: Double? = null, pan01: Double? = null,
                            zDominant: Boolean = false) {
        val depth = depthSpanDp()
        val span = sim?.geometrySpan() ?: 360.0
        val energy = sim?.let { (0.30 + 0.70 * it.colorEnergy01).coerceIn(0.0, 1.0) } ?: 0.35
        val flow = sim?.let {
            var total = 0.0
            for (i in 0 until 7) total += it.layers[i].flowDps
            total / 7.0
        } ?: -72.0
        val now = sim?.t ?: 0.0
        val s = (strength ?: energy).coerceIn(0.0, 1.0)
        val pan = (pan01 ?: rng.uniform(0.25, 0.75)).coerceIn(0.0, 1.0)
        val lambda = rng.uniform(145.0, 310.0) * (1.05 - 0.18 * s)
        val heading = Math.toRadians(p.get("surface_heading_deg"))
        val spread = Math.toRadians(p.get("surface_spread_deg"))
        val angle = if (zDominant) {
            (max(heading, Math.toRadians(38.0)) + rng.gaussian(0.0, spread * 0.16))
                .coerceIn(Math.toRadians(32.0), Math.toRadians(58.0))
        } else {
            (heading + rng.gaussian(0.0, spread * 0.24))
                .coerceIn(Math.toRadians(7.0), Math.toRadians(42.0))
        }
        val amp = (2.6 + 4.8 * s) * rng.uniform(0.82, 1.16)
        val sigmaX = rng.uniform(0.34, 0.55) * span * (0.9 + 0.2 * s)
        val sigmaZ = rng.uniform(0.24, 0.40) * depth
        val x: Double
        val z: Double
        val age: Double
        if (initialQ == null) {
            z = depth * rng.uniform(1.00, 1.18)
            x = (pan - 0.5) * span * 0.55 + span * rng.uniform(0.18, 0.48)
            age = 0.0
        } else {
            z = depth * (1.05 - 1.12 * initialQ)
            x = span * (0.42 - 0.72 * initialQ) + (pan - 0.5) * span * 0.25
            age = initialQ * 5.0
        }
        val kp = 2.0 * PI / lambda
        val cg = 0.5 * sqrt(GRAVITY_DP_S2 / kp)
        val speed = max(abs(0.42 * flow - cg * cos(angle)), 16.0)
        val spectral = (190.0 / lambda).pow(0.72).coerceIn(0.55, 1.8)
        val life = (p.get("surface_decay_dp") / (speed * spectral)).coerceIn(2.8, 14.0)
        packets.add(Packet(lambda, angle, amp, x, z, sigmaX, sigmaZ,
            rng.uniform(0.0, 2.0 * PI), 1.0, age, life))
        nextPacketT = now + rng.uniform(1.5, 3.0) / (0.72 + 0.55 * s)
    }

    fun injectPacket(sim: FableSolSimulation, strength: Double, pan01: Double = 0.5,
                     zDominant: Boolean = false) {
        spawnPacket(sim, strength = strength, pan01 = pan01, zDominant = zDominant)
    }

    fun advance(dt: Double, sim: FableSolSimulation, pitchTargetDeg: Double,
                pitchWobbleDeg: Double = 0.0) {
        if (dt <= 0.0) return
        updateWaveVectors()
        var flow = 0.0
        for (i in 0 until 7) flow += sim.layers[i].flowDps
        flow /= 7.0
        val stability = p.get("surface_shape_stability").coerceIn(0.0, 1.0)
        if (stability <= 1e-12) {
            for (j in wavelength.indices) {
                phase[j] = (phase[j] -
                    (sqrt(GRAVITY_DP_S2 * k[j]) + kx[j] * flow) * dt) % (2.0 * PI)
            }
        } else {
            var weightedTransport = 0.0
            var weightSum = 0.0
            for (j in wavelength.indices) {
                val rate = sqrt(GRAVITY_DP_S2 * k[j]) + kx[j] * flow
                val weight = baseAmplitude[j] * baseAmplitude[j]
                weightedTransport += rate / kx[j] * weight
                weightSum += weight
            }
            val commonTransport = weightedTransport / weightSum
            lastCommonTransportDpsForTest = commonTransport
            for (j in wavelength.indices) {
                val rate = sqrt(GRAVITY_DP_S2 * k[j]) + kx[j] * flow
                val coherentRate = kx[j] * commonTransport
                val blendedRate = rate + stability * (coherentRate - rate)
                phase[j] = (phase[j] - blendedRate * dt) % (2.0 * PI)
            }
        }
        for (b in 0 until 3) {
            var target = 0.0
            for (i in 0 until 7) {
                val cap = max(p.lget("hero_max_dp", i), 1.0)
                target += sim.layers[i].heroBandDp[b] / cap
            }
            target = (target / 7.0).coerceIn(0.0, 1.0)
            energyBand[b] += (target - energyBand[b]) * (1.0 - exp(-dt / 3.2))
        }

        val targetPitch = Math.toRadians((pitchTargetDeg + pitchWobbleDeg).coerceIn(
            -FableSolPitchPolicy.MOTION_LIMIT_DEG,
            FableSolPitchPolicy.MOTION_LIMIT_DEG
        ))
        val w0 = 2.0 * PI * 0.72
        val zeta = 0.48
        val accel = w0 * w0 * (targetPitch - pitchEffRad) - 2.0 * zeta * w0 * pitchVelocity
        pitchVelocity += accel * dt
        pitchEffRad += pitchVelocity * dt

        val depth = depthSpanDp()
        for (packet in packets) {
            val kp = 2.0 * PI / packet.wavelength
            val cg = 0.5 * sqrt(GRAVITY_DP_S2 / kp)
            val vx = 0.42 * flow - cg * cos(packet.angle)
            val vz = -cg * sin(packet.angle)
            packet.x += vx * dt
            packet.z += vz * dt
            packet.phase -= (sqrt(GRAVITY_DP_S2 * kp) - kp * cos(packet.angle) * 0.42 * flow) * dt
            packet.age += dt
            val spectral = (190.0 / packet.wavelength).pow(0.72).coerceIn(0.55, 1.8)
            packet.energy *= exp(-hypot(vx, vz) * spectral * dt / max(p.get("surface_decay_dp"), 1.0))
        }
        // 手写下标压缩：`removeAll { … }` 会为捕获 depth 的谓词每个物理子步分配一个
        // lambda（120 次/s）。保留顺序与判定条件与原式逐项相同。
        var kept = 0
        for (index in packets.indices) {
            val packet = packets[index]
            val expired = packet.age >= packet.life || packet.energy <= 0.055 ||
                packet.z <= -0.60 * depth || abs(packet.x) >= 620.0
            if (!expired) {
                if (kept != index) packets[kept] = packet
                kept++
            }
        }
        while (packets.size > kept) packets.removeAt(packets.size - 1)
        if (sim.t >= nextPacketT && packets.size < 7) spawnPacket(sim)
    }

    /**
     * 采样最终宏观高度、轨道位移和解析二维坡度；返回对象及其数组每帧复用。
     *
     * 逐模态/逐波包标量在串行预备段一次算好（轨道系数按 Python 的
     * `q·a·(kx/k)` 折叠形式预乘，两端一致），三个重循环（方向场累加、
     * worldEta 合成、坡度）按行并行——行间无共享写，输出与串行一致。
     */
    fun sample(sim: FableSolSimulation): Sample = sample(sim, sim.continuousRenderInfo())

    /**
     * 渲染路径专用入口：窗口信息由 `buildFrame` 算好后传入，整帧只算一次
     * （原先渲染器与本方法各算一次，等于每帧两次窗口扫描 + 两次对象分配，
     * 且两处消费的必须是同一帧同一次计算的结果）。
     */
    fun sample(sim: FableSolSimulation, info: FableSolRenderInfo): Sample {
        val prepStart = System.nanoTime()
        // 只对渲染窗口求值：`buildFrame` 只读 [i0, i1-1]，而物理网格有 216 点，
        // 容器 280dp、θ≈0 时其中约 42% 永远不会出现在画面上。
        // fair 化在 p 处要读 raw[p-1..p+1]，因此 raw 要比 fair 各宽一列。
        // 单调修复也随之限制在窗口内——`rs` 长期实测恒为 0（该修复在真实参数下
        // 从不触发），故这一限制不改变任何输出。
        val fairLo = max(info.i0 - 1, 0)
        val fairHi = min(info.i1, N_POINTS - 1)
        val rawLo = max(fairLo - 1, 0)
        val rawHi = min(fairHi + 1, N_POINTS - 1)
        sample.windowLo = fairLo
        sample.windowHi = fairHi
        updateWaveVectors()
        val depth = depthSpanDp()
        sample.depthDp = depth
        for (r in 0 until Z_ROWS) sample.zDp[r] = sample.z01[r] * depth

        val ambientScale = p.get("ambient_gain") / 1.2
        val spectrumGain = p.get("surface_spectrum_gain")
        val audioResponse = p.get("surface_spectrum_audio_response")
        val u0 = sim.uGrid[rawLo]
        val modeCount = wavelength.size
        for (j in 0 until modeCount) {
            val amp = baseAmplitude[j] * ambientScale * spectrumGain *
                (0.78 + 0.52 * audioResponse * energyBand[band[j]])
            val q = min(0.58, 0.46 / max(k[j] * amp * modeCount, 1e-4))
            modeAmp[j] = amp
            modeOrbitXWeight[j] = q * amp * (kx[j] / k[j])
            modeOrbitZWeight[j] = q * amp * (kz[j] / k[j])
            val phaseStep = kx[j] * FableSolSpec.DX_DP
            modeStepCos[j] = cos(phaseStep)
            modeStepSin[j] = sin(phaseStep)
            modeBaseX[j] = kx[j] * u0
        }
        val packetCount = min(packets.size, MAX_PACKETS)
        perfPacketCount = packetCount
        for (index in 0 until packetCount) {
            val packet = packets[index]
            val kp = 2.0 * PI / packet.wavelength
            val kxp = -kp * cos(packet.angle)
            val kzp = -kp * sin(packet.angle)
            val lifeEnv = sin(PI * min(packet.age / max(packet.life, 1e-3), 1.0)).pow(0.7)
            val a = packet.amplitude * packet.energy * lifeEnv
            val q = min(0.62, 0.48 / max(kp * max(a, 0.1), 1e-4))
            packetAmp[index] = a
            packetOrbitXWeight[index] = q * (kxp / kp)
            packetOrbitZWeight[index] = q * (kzp / kp)
            packetKz[index] = kzp
            packetBaseX[index] = kxp * u0
            packetPhaseOffset[index] = packet.phase
            packetCenterZ[index] = packet.z
            packetSigmaZ[index] = packet.sigmaZ
            val phaseStep = kxp * FableSolSpec.DX_DP
            packetStepCos[index] = cos(phaseStep)
            packetStepSin[index] = sin(phaseStep)
            val envX = packetEnvX[index]
            for (x in rawLo..rawHi) {
                val dx = (sim.uGrid[x] - packet.x) / packet.sigmaX
                envX[x] = exp(-0.5 * dx * dx)
            }
        }

        val pitchIn = Math.toRadians(sim.motionPitchDeg)
        val lag = (pitchEffRad - pitchIn).coerceIn(-0.24, 0.24)
        val fieldStart = System.nanoTime()
        perfPrepNs = fieldStart - prepStart
        worldRepairRows.set(0)
        val modeOuter = forceModeOuterFieldForTest
        FableSolRowParallel.run(Z_ROWS) { startRow, endRow ->
            for (r in startRow until endRow) {
                if (modeOuter) {
                    accumulateFieldRowModeOuter(r, rawLo, rawHi, modeCount, packetCount)
                } else {
                    accumulateFieldRowColumnOuter(r, rawLo, rawHi, modeCount, packetCount)
                }
                limitRow(r, rawLo, rawHi, lag, depth, sim.grandKeep)
            }
        }
        // 合成真渲染面 worldEta（各层轮廓 + 二维方向场）并 fair 化。打光法线从
        // worldEta 求，使光贴着看得见的波形走，而不是只跟随二维方向场。
        //
        // 合成与 fairing 都只依赖本行，因此并入同一次行并行；三路 fairing 也共用
        // 这一次派发。物理节点是 cubic B-spline 控制样本，不是画面必须逐点命中的
        // 折线角；局部凸包抑制离群尖峰，解析切线使屏幕 Hermite 重建严格 C2。
        // 逐行结果与拆成四次派发时逐位一致，只是少了三次汇合。
        val fairStart = System.nanoTime()
        perfFieldNs = fairStart - fieldStart
        // limit 已并回 field 派发（C6），不再单独读数；字段与 HUD 布局保持不变。
        perfLimitNs = 0L
        prepareComposeMeans(sim.heights, sample.eta, rawLo, rawHi, sim.grandDcBiasDp)
        val heights = sim.heights
        FableSolRowParallel.run(Z_ROWS) { startRow, endRow ->
            for (r in startRow until endRow) {
                composeRow(r, heights, sample.eta, rawLo, rawHi)
                FableSolCubicResampler.fairCubicBsplineRange(
                    rawWorldEta[r], sample.worldEta[r], sample.slopeX[r],
                    FableSolSpec.DX_DP, fairLo, fairHi
                )
                FableSolCubicResampler.fairCubicBsplineRange(
                    rawOrbitX[r], sample.orbitX[r], sample.orbitXSlope[r],
                    FableSolSpec.DX_DP, fairLo, fairHi
                )
                FableSolCubicResampler.fairCubicBsplineRange(
                    rawOrbitZ[r], sample.orbitZ[r], sample.orbitZSlope[r],
                    FableSolSpec.DX_DP, fairLo, fairHi
                )
            }
        }
        // slopeZ 要读相邻行的 fair 结果，无法并入上一段，只能单独派发一次。
        val slopeStart = System.nanoTime()
        perfFairNs = slopeStart - fairStart
        val world = sample.worldEta
        val depthStep = sample.zDp[1] - sample.zDp[0]
        val inverseDepthStep2 = 1.0 / (2.0 * depthStep)
        FableSolRowParallel.run(Z_ROWS) { startRow, endRow ->
            for (r in startRow until endRow) {
                val slopeZRow = sample.slopeZ[r]
                // 与 numpy.gradient(edge_order=2) 同式；外层同样保持二阶单侧导数，
                // 避免第一/最后一行法线突然改折。三个分支提到行外，内层只剩三项式。
                when (r) {
                    0 -> {
                        val w0 = world[0]; val w1 = world[1]; val w2 = world[2]
                        for (x in fairLo..fairHi) {
                            slopeZRow[x] = (-3.0 * w0[x] + 4.0 * w1[x] - w2[x]) * inverseDepthStep2
                        }
                    }
                    Z_ROWS - 1 -> {
                        val w0 = world[r]; val w1 = world[r - 1]; val w2 = world[r - 2]
                        for (x in fairLo..fairHi) {
                            slopeZRow[x] = (3.0 * w0[x] - 4.0 * w1[x] + w2[x]) * inverseDepthStep2
                        }
                    }
                    else -> {
                        val next = world[r + 1]; val previous = world[r - 1]
                        for (x in fairLo..fairHi) {
                            slopeZRow[x] = (next[x] - previous[x]) * inverseDepthStep2
                        }
                    }
                }
            }
        }
        perfSlopeNs = System.nanoTime() - slopeStart
        perfWorldRepairRows = worldRepairRows.get()
        return sample
    }

    internal fun clearPacketsForTest() {
        packets.clear()
    }

    internal fun setEnergyBandsForTest(low: Double, mid: Double, high: Double) {
        energyBand[0] = low
        energyBand[1] = mid
        energyBand[2] = high
    }

    internal fun phaseForTest(): DoubleArray = phase.copyOf()

    internal fun waveVectorXForTest(): DoubleArray {
        updateWaveVectors()
        return kx.copyOf()
    }

    companion object {
        /** 每两个产品层锚线之间的纵深采样数；锚线本身仍严格对应九层原始轮廓。 */
        const val ROWS_PER_LAYER = 12
        const val Z_ROWS = (N_LAYERS - 1) * ROWS_PER_LAYER + 1 // 97
        const val RENDER_GROUPS = N_LAYERS - 1                  // 8 次网格提交
        private const val GRAVITY_DP_S2 = 32.0
        // 波包预备数组容量。自然生成上限 7，注入事件在两次剪枝间最多再加数个；
        // 24 远高于可达数量，超出部分按序丢弃（防御性钳制，实际不可达）。
        private const val MAX_PACKETS = 24
        /** field 列外层推进的相位链槽位：9 个模态 + 至多 [MAX_PACKETS] 个波包。 */
        private const val FIELD_CHAIN_SLOTS = 9 + MAX_PACKETS
        private const val WORLD_MINIMUM_SPACING_RATIO = 0.16
    }
}
