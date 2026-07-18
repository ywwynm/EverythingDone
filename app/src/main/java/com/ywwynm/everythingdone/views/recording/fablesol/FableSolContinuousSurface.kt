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
        @JvmField val slopeX = Array(Z_ROWS) { DoubleArray(N_POINTS) }
        @JvmField val slopeZ = Array(Z_ROWS) { DoubleArray(N_POINTS) }
        @JvmField val worldEta = Array(Z_ROWS) { DoubleArray(N_POINTS) }
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

    // Step A：打光法线改从真渲染面 worldEta 求，跨层用 Catmull-Rom 平滑防止层锚点处的坡度
    // 跳变（横向接缝）。行间插值权重只依赖固定的 z01[r]，init 预计算一次，稳态零分配。
    private val layerMean = DoubleArray(N_LAYERS)
    private val depthMeanX = DoubleArray(N_POINTS)
    private val crRowIndex = Array(Z_ROWS) { IntArray(4) }
    private val crRowWeight = Array(Z_ROWS) { DoubleArray(4) }

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

    @JvmField var pitchEffRad = 0.0
    private var pitchVelocity = 0.0

    init {
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
        for (i in 0 until N_LAYERS) {
            var sum = 0.0
            for (v in layerHeights[i]) sum += v
            layerMean[i] = sum / layerHeights[i].size
        }
        for (x in 0 until N_POINTS) {
            var depthMean = 0.0
            for (r in 0 until Z_ROWS) depthMean += directionalEta[r][x]
            depthMeanX[x] = depthMean / Z_ROWS
        }
        FableSolRowParallel.run(Z_ROWS) { startRow, endRow ->
            for (r in startRow until endRow) {
                val idx = crRowIndex[r]
                val w = crRowWeight[r]
                val h0 = layerHeights[idx[0]]; val m0 = layerMean[idx[0]]; val w0 = w[0]
                val h1 = layerHeights[idx[1]]; val m1 = layerMean[idx[1]]; val w1 = w[1]
                val h2 = layerHeights[idx[2]]; val m2 = layerMean[idx[2]]; val w2 = w[2]
                val h3 = layerHeights[idx[3]]; val m3 = layerMean[idx[3]]; val w3 = w[3]
                val worldRow = sample.worldEta[r]
                val dirRow = directionalEta[r]
                for (x in 0 until N_POINTS) {
                    worldRow[x] = w0 * (h0[x] - m0) + w1 * (h1[x] - m1) +
                        w2 * (h2[x] - m2) + w3 * (h3[x] - m3) +
                        dirRow[x] - depthMeanX[x]
                }
            }
        }
        return sample.worldEta
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
        for (j in wavelength.indices) {
            phase[j] = (phase[j] - (sqrt(GRAVITY_DP_S2 * k[j]) + kx[j] * flow) * dt) % (2.0 * PI)
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
        packets.removeAll {
            it.age >= it.life || it.energy <= 0.055 || it.z <= -0.60 * depth || abs(it.x) >= 620.0
        }
        if (sim.t >= nextPacketT && packets.size < 7) spawnPacket(sim)
    }

    /**
     * 采样最终宏观高度、轨道位移和解析二维坡度；返回对象及其数组每帧复用。
     *
     * 逐模态/逐波包标量在串行预备段一次算好（轨道系数按 Python 的
     * `q·a·(kx/k)` 折叠形式预乘，两端一致），三个重循环（方向场累加、
     * worldEta 合成、坡度）按行并行——行间无共享写，输出与串行一致。
     */
    fun sample(sim: FableSolSimulation): Sample {
        updateWaveVectors()
        val depth = depthSpanDp()
        sample.depthDp = depth
        for (r in 0 until Z_ROWS) sample.zDp[r] = sample.z01[r] * depth

        val ambientScale = p.get("ambient_gain") / 1.2
        val u0 = sim.uGrid[0]
        val modeCount = wavelength.size
        for (j in 0 until modeCount) {
            val amp = baseAmplitude[j] * ambientScale * (0.78 + 0.52 * energyBand[band[j]])
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
            for (x in 0 until N_POINTS) {
                val dx = (sim.uGrid[x] - packet.x) / packet.sigmaX
                envX[x] = exp(-0.5 * dx * dx)
            }
        }

        val pitchIn = Math.toRadians(sim.motionPitchDeg)
        val lag = (pitchEffRad - pitchIn).coerceIn(-0.24, 0.24)
        FableSolRowParallel.run(Z_ROWS) { startRow, endRow ->
            for (r in startRow until endRow) {
                val etaRow = sample.eta[r]
                val orbitXRow = sample.orbitX[r]
                val orbitZRow = sample.orbitZ[r]
                java.util.Arrays.fill(etaRow, 0.0)
                java.util.Arrays.fill(orbitXRow, 0.0)
                java.util.Arrays.fill(orbitZRow, 0.0)
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
                    for (x in 0 until N_POINTS) {
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
                    for (x in 0 until N_POINTS) {
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
                val lift = lag * (z - 0.5 * depth)
                for (x in 0 until N_POINTS) {
                    etaRow[x] += lift
                    orbitXRow[x] = orbitXRow[x].coerceIn(-10.0, 10.0)
                    orbitZRow[x] = orbitZRow[x].coerceIn(-10.0, 10.0)
                }
            }
        }
        // 先合成真渲染面 worldEta（含各层轮廓 + 二维方向场），打光法线改从它求，
        // 使光贴着看得见的波形走，而不是只跟随二维方向场。
        composeLayerField(sim.heights, sample.eta)
        val world = sample.worldEta
        FableSolRowParallel.run(Z_ROWS) { startRow, endRow ->
            for (r in startRow until endRow) {
                val slopeXRow = sample.slopeX[r]
                val slopeZRow = sample.slopeZ[r]
                for (x in 0 until N_POINTS) {
                    slopeXRow[x] = when (x) {
                        0 -> (world[r][1] - world[r][0]) / FableSolSpec.DX_DP
                        N_POINTS - 1 -> (world[r][x] - world[r][x - 1]) / FableSolSpec.DX_DP
                        else -> (world[r][x + 1] - world[r][x - 1]) / (2.0 * FableSolSpec.DX_DP)
                    }
                    slopeZRow[x] = when (r) {
                        0 -> (world[1][x] - world[0][x]) / (sample.zDp[1] - sample.zDp[0])
                        Z_ROWS - 1 -> (world[r][x] - world[r - 1][x]) /
                            (sample.zDp[r] - sample.zDp[r - 1])
                        else -> (world[r + 1][x] - world[r - 1][x]) /
                            (sample.zDp[r + 1] - sample.zDp[r - 1])
                    }
                }
            }
        }
        return sample
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
    }
}
