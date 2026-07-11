package com.ywwynm.everythingdone.views.recording.fablesol

import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.FLOW_DIR
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 特征帧/事件 → 动画驱动（对应 mapping.py 的 FeatureMapper）：涨落/主浪包络、流速、脉冲与稀有
 * 注入（远浪/段涌）。常态基底是每层常驻宽浪；普通音头竞争受穿屏时间约束的宽物理波包，顶级音头
 * （远浪，长冷却）与段落切换（段涌）仍是更强、更稀有的独立行波。
 */
class FableSolFeatureMapper(private val p: FableSolParams) {

    private val rng = FableSolRng(7)
    private var lastIncomingT = -10.0
    private val slow = hashMapOf("loud" to 0.0, "low" to 0.0, "mid" to 0.0, "high" to 0.0)
    private val timbre = hashMapOf(
        "cent" to 0.5, "tilt" to 0.5, "flat" to 0.2, "perc" to 0.0, "punch" to 0.0,
        "width" to 0.0, "pan" to 0.5, "rel_low" to 1.0 / 3, "rel_mid" to 1.0 / 3, "rel_high" to 1.0 / 3
    )
    private var levelEnergy = 0.0
    private var smT = Double.NaN   // NaN 表示 None
    private var lastRhythmWaveT = -10.0
    private var rhythmWaveCount = 0

    fun applySilence(sim: FableSolSimulation) {
        sim.flow01 = 0.0
        smT = Double.NaN
        for (k in slow.keys) slow[k] = slow[k]!! * 0.94
        for (k in listOf("flat", "perc", "punch", "width")) timbre[k] = timbre[k]!! * 0.94
        timbre["cent"] = timbre["cent"]!! + (0.5 - timbre["cent"]!!) * 0.05
        timbre["pan"] = timbre["pan"]!! + (0.5 - timbre["pan"]!!) * 0.05
        levelEnergy *= 0.96
        sim.setBeat(0.0, 0.0, 0.0)
        sim.setColorDrive(0.5, 0.0)
        sim.setMaterialDrive(0.0, 0.0, 0.5)
        sim.setSpatialDrive(0.0, 0.5)
        for (ls in sim.layers) {
            ls.swellTargetDp = 0.0
            ls.heroTargetDp = 0.0
            ls.heroBandTargetDp[0] = 0.0; ls.heroBandTargetDp[1] = 0.0; ls.heroBandTargetDp[2] = 0.0
            ls.capillaryTarget01 = 0.0
            ls.roughnessTarget01 = 0.0
        }
    }

    fun applyFrame(sim: FableSolSimulation, fr: FableSolFeatureFrame) {
        sim.flow01 = fr.flow01
        val silent = fr.isSilent
        val t = fr.t
        val dt = if (smT.isNaN()) 1.0 / 60.0 else (t - smT).coerceIn(0.0, 0.1)
        smT = t
        val ke = if (dt > 0) 1.0 - exp(-dt / 0.28) else 0.0
        val kt = if (dt > 0) 1.0 - exp(-dt / 0.24) else 0.0
        val kr = if (dt > 0) 1.0 - exp(-dt / 0.85) else 0.0
        val slowIn = mapOf(
            "loud" to if (silent) 0.0 else fr.loudness01,
            "low" to if (silent) 0.0 else fr.bandLow,
            "mid" to if (silent) 0.0 else fr.bandMid,
            "high" to if (silent) 0.0 else fr.bandHigh
        )
        for ((key, value) in slowIn) slow[key] = slow[key]!! + (value - slow[key]!!) * ke
        val timbreIn = mapOf(
            "cent" to if (silent) 0.5 else fr.centroid01,
            "tilt" to fr.spectralTilt01,
            "flat" to if (silent) 0.0 else fr.flatness01,
            "perc" to if (silent) 0.0 else fr.percussive01,
            "punch" to if (silent) 0.0 else fr.punch01,
            "width" to if (silent) 0.0 else fr.stereoWidth01,
            "pan" to fr.pan01,
            "rel_low" to fr.relLow,
            "rel_mid" to fr.relMid,
            "rel_high" to fr.relHigh
        )
        for ((key, value) in timbreIn) {
            val k = if (key.startsWith("rel_")) kr else kt
            timbre[key] = timbre[key]!! + (value - timbre[key]!!) * k
        }
        sim.setBeat(fr.tempoBpm, fr.beatPhase01, if (silent) 0.0 else fr.beatConf01)
        val bandsSlow = doubleArrayOf(slow["low"]!!, slow["mid"]!!, slow["high"]!!)
        val levelIn = if (silent) 0.0
        else 0.86 * fr.loudness01 + 0.14 * (fr.bandLow + fr.bandMid + fr.bandHigh) / 3.0
        val levelTau = if (levelIn > levelEnergy) p.get("swell_presmooth_s") else p.get("swell_presmooth_release_s")
        if (dt > 0.0) levelEnergy += (levelIn - levelEnergy) * (1.0 - exp(-dt / max(levelTau, 0.05)))
        val relSum = max(timbre["rel_low"]!! + timbre["rel_mid"]!! + timbre["rel_high"]!!, 1e-6)
        val rel = doubleArrayOf(timbre["rel_low"]!! / relSum, timbre["rel_mid"]!! / relSum, timbre["rel_high"]!! / relSum)
        val eMix = 0.45 * slow["loud"]!! + 0.55 * (bandsSlow[0] + bandsSlow[1] + bandsSlow[2]) / 3.0
        val colorEnergy = min(eMix.pow(0.7) * 1.15, 1.0)
        sim.setColorDrive(timbre["cent"]!!, colorEnergy)
        val rough = (0.62 * timbre["flat"]!! + 0.23 * timbre["perc"]!! + 0.15 * (1.0 - timbre["tilt"]!!)).coerceIn(0.0, 1.0)
        val capillary = (0.45 * timbre["flat"]!! + 0.35 * timbre["perc"]!! + 0.20 * rel[2]).coerceIn(0.0, 1.0)
        sim.setMaterialDrive(rough, capillary, timbre["tilt"]!!)
        sim.setSpatialDrive(timbre["width"]!!, timbre["pan"]!!)
        for (ls in sim.layers) {
            val role = bandWeights(ls.depth01)
            val drive = role[0] * bandsSlow[0] + role[1] * bandsSlow[1] + role[2] * bandsSlow[2]
            val identity = role[0] * rel[0] + role[1] * rel[1] + role[2] * rel[2]
            val identityGain = (0.78 + 1.25 * (identity - 1.0 / 3)).coerceIn(0.58, 1.22)
            var shapeMix = (0.45 * slow["loud"]!! + 0.55 * drive) * identityGain
            shapeMix = min(shapeMix.pow(0.7) * 1.15, 1.0)
            val levelMix = min(max(levelEnergy, 0.0).pow(0.82) * 1.04, 1.0)
            val rawT = p.lget("swell_max_dp", ls.i) * p.get("swell_gain") * levelMix
            val db = p.get("swell_deadband_pct") * 0.01 * p.lget("swell_max_dp", ls.i)
            if (db <= 1e-6 || rawT > ls.swellTargetDp + db) ls.swellTargetDp = rawT
            else if (rawT < ls.swellTargetDp - db) ls.swellTargetDp = rawT
            val overall = p.lget("hero_max_dp", ls.i) * p.get("hero_gain") * shapeMix.pow(0.8)
            ls.heroTargetDp = overall
            val contribution = DoubleArray(3) { role[it] * max(bandsSlow[it], 0.03) * (0.55 + 1.35 * rel[it]) }
            val cSum = max(contribution[0] + contribution[1] + contribution[2], 1e-6)
            for (j in 0 until 3) ls.heroBandTargetDp[j] = overall * contribution[j] / cSum
            ls.roughnessTarget01 = rough * (0.82 + 0.18 * role[2])
            ls.capillaryTarget01 = (capillary * (0.35 + 0.95 * role[2]) + 0.18 * timbre["width"]!!).coerceIn(0.0, 1.0)
        }
    }

    fun applyOnset(sim: FableSolSimulation, ev: FableSolEvent.Onset) {
        val s = ev.strength01
        val bands = doubleArrayOf(ev.low, ev.mid, ev.high)
        for (ls in sim.layers) {
            // 快速事件只改变光学毛细纹；几何能量统一进入下方 DynamicWave 物理注入。
            ls.capillaryTarget01 = min(ls.capillaryTarget01 + s * (0.18 + 0.42 * ev.flatness01), 1.0)
        }
        injectRhythmWave(sim, ev, bands)
        val cd = p.get("incoming_cooldown_s") * (1.5 - 0.9 * sim.flow01.coerceIn(0.0, 1.0))
        if (s >= p.get("incoming_threshold") && sim.t - lastIncomingT >= cd && rng.nextDouble() < p.get("incoming_prob")) {
            lastIncomingT = sim.t
            val amp = s * p.get("inject_amp_max_dp") * p.get("inject_gain")
            var delay0 = 0.0
            if (sim.beat01 > 0.45) {
                delay0 = sim.timeToNextBeat()
                val per = 60.0 / max(sim.currentBeatBpm(), 1.0)
                if (delay0 < 0.08) delay0 += per
            }
            inject(sim, amp, ev.centroid01, bands, incoming = true, cascade = true, punch = s, delay0 = delay0, pan01 = ev.pan01)
        }
    }

    private fun injectRhythmWave(sim: FableSolSimulation, ev: FableSolEvent.Onset, bandVec: DoubleArray) {
        val strength = ev.strength01
        if (strength < p.get("rhythm_wave_min_strength") || p.get("rhythm_wave_gain") <= 1e-6) return
        val span = sim.geometrySpan()
        val probe = sim.layers[6]
        val transport = Math.abs(probe.flowDps) + 0.55 * p.lget("wave_speed_dps", 6)
        val interval = (span / max(transport * 1.55, 1.0)).coerceIn(0.72, 2.8)
        if (sim.t - lastRhythmWaveT < interval) return
        lastRhythmWaveT = sim.t
        rhythmWaveCount += 1
        val centroid = ev.centroid01
        val width = 108.0 + (1.0 - centroid) * 84.0
        val flow = sim.flow01.coerceIn(0.0, 1.0)
        val amp = p.get("rhythm_wave_gain") * (3.0 + 6.0 * strength) * (0.72 + 0.28 * flow)
        val side = if (FLOW_DIR < 0) 1.0 else -1.0
        val uBase = side * (span / 2.0 + width * 0.22 + 8.0)
        val raw = DoubleArray(sim.layers.size) {
            val role = bandWeights(sim.layers[it].depth01)
            role[0] * bandVec[0] + role[1] * bandVec[1] + role[2] * bandVec[2]
        }
        var rMax = 1e-6
        for (r in raw) if (r > rMax) rMax = r
        for (i in raw.indices) raw[i] /= rMax
        val peak = 0.85 + centroid * 0.65
        for (ls in sim.layers) {
            val depthGain = 0.34 + 0.66 * ls.depth01
            val response = 0.62 + 0.38 * raw[ls.i]
            val jitter = rng.gaussian(0.0, 7.0)
            val deep = side * ls.depth01 * 12.0
            sim.injectLayer(ls.i, 0.0, width * (1.0 + 0.22 * ls.depth01),
                amp * depthGain * response, FLOW_DIR * 0.86,
                delayS = 0.018 * ls.i, uDp = uBase + deep + jitter, peak = peak)
        }
    }

    private fun inject(sim: FableSolSimulation, ampIn: Double, centroid01: Double, bands: DoubleArray,
                       incoming: Boolean, cascade: Boolean, punch: Double = 0.7,
                       delay0: Double = 0.0, pan01: Double = 0.5) {
        var amp = ampIn
        val wMin = p.get("inject_width_min_dp"); val wMax = p.get("inject_width_max_dp")
        val width = wMax + (wMin - wMax) * centroid01
        val peak = 0.85 + 1.0 * centroid01.coerceIn(0.0, 1.0)
        val span = sim.geometrySpan()
        val side = if (FLOW_DIR < 0) 1.0 else -1.0
        var uBase: Double
        val travel: Double
        if (incoming) {
            uBase = side * (span / 2.0 + width / 2.0 + 16.0)
            travel = FLOW_DIR * 0.95
            amp *= 1.15
        } else {
            val frac = rng.uniform(0.30, 0.65)
            uBase = side * (span / 2.0 + width * frac)
            var tf = p.get("travel_bias_max") * (0.6 + 0.4 * sim.flow01)
            tf += (0.95 - tf) * frac
            travel = FLOW_DIR * tf
        }
        uBase += (pan01.coerceIn(0.0, 1.0) - 0.5) * span * 0.16
        val raw = DoubleArray(sim.layers.size) {
            val role = bandWeights(sim.layers[it].depth01)
            role[0] * bands[0] + role[1] * bands[1] + role[2] * bands[2]
        }
        var m = raw[0]
        for (r in raw) if (r > m) m = r
        if (m < 1e-3) { for (i in raw.indices) raw[i] = 1.0; m = 1.0 }
        val step = p.get("cascade_step_s") * (if (cascade) 0.6 else 1.6)
        for (ls in sim.layers) {
            if (ls.i > 0 && !incoming && rng.nextDouble() < ls.depth01 * 0.55 * (1.0 - punch)) continue
            val k = (raw[ls.i] / m) * (1.0 - 0.42 * ls.depth01)
            val jit = rng.gaussian(0.0, 16.0)
            val deep = side * ls.depth01 * 36.0
            val wi = width * rng.uniform(0.95, 1.35)
            val delay = delay0 + step * ls.i + rng.uniform(0.0, 0.09)
            sim.injectLayer(ls.i, 0.0, wi, amp * k, travel, delay, uDp = uBase + jit + deep, peak = peak)
        }
    }

    fun applySection(sim: FableSolSimulation, ev: FableSolEvent.Section) {
        val m = ev.magnitude01
        sim.setMood(ev.energy01, ev.brightness01)
        val g = p.get("surge_gain")
        if (g <= 1e-6 || !ev.surge) return
        val amp = g * p.get("surge_amp_max_dp") * (0.6 + 0.4 * m)
        sim.layers[0].surgeLiftTargetDp = max(sim.layers[0].surgeLiftTargetDp, g * p.get("surge_lift_dp"))
        val width = sim.containerWidthDp * 0.75
        val span = sim.geometrySpan()
        val side = if (FLOW_DIR < 0) 1.0 else -1.0
        val uBase = side * (span / 2.0 + width * 0.05)
        val travel = FLOW_DIR * 0.27
        val step = p.get("cascade_step_s") * 1.5
        sim.injectLayer(0, 0.0, width, amp, travel, 0.0, uDp = uBase + rng.gaussian(0.0, 10.0))
        val dd = g * p.get("surge_drawdown_dp") * (0.5 + 0.5 * m)
        for (i in 1 until sim.layers.size) {
            val ls = sim.layers[i]
            ls.surgeLiftTargetDp = min(ls.surgeLiftTargetDp, -dd * (0.3 + 0.7 * ls.depth01))
            sim.injectLayer(ls.i, 0.0, width * (1.0 - 0.3 * ls.depth01),
                amp * 0.30 * (1.0 - 0.6 * ls.depth01), travel, step * ls.i,
                uDp = uBase + rng.gaussian(0.0, 12.0))
        }
    }

    private fun bandWeights(depth01: Double): DoubleArray {
        val i = Math.round(depth01 * (LAYER_ROLE_WEIGHTS.size - 1)).toInt().coerceIn(0, LAYER_ROLE_WEIGHTS.size - 1)
        return LAYER_ROLE_WEIGHTS[i]
    }

    companion object {
        // 九层三组视觉声部：前景=重量/低频，中景=旋律/中频，远景=空气/高频。每层仍听见全频段。
        private val LAYER_ROLE_WEIGHTS = arrayOf(
            doubleArrayOf(0.72, 0.20, 0.08), doubleArrayOf(0.62, 0.28, 0.10), doubleArrayOf(0.52, 0.35, 0.13),
            doubleArrayOf(0.24, 0.60, 0.16), doubleArrayOf(0.16, 0.68, 0.16), doubleArrayOf(0.15, 0.57, 0.28),
            doubleArrayOf(0.12, 0.38, 0.50), doubleArrayOf(0.08, 0.28, 0.64), doubleArrayOf(0.05, 0.20, 0.75)
        )
    }
}
