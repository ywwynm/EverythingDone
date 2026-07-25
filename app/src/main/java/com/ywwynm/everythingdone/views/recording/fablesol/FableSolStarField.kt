package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 人眼眩光的 CPU 星光轨迹（D206~D209，与 Python gl_optics 星光系统一比一）。
 *
 * 星光源不取渲染像素：CPU 按与 water.frag 银边严格同构的公式复算九层波顶
 * 物理辐亮度场（不含显示端 uHdrGain/headroom——SDR 与录音态共用的"预显示"
 * 光源真值），apex 门内的越阈局部峰成为锚点；轨迹带攻击/位置低通，星振幅是
 * 银丝辐亮度的**瞬时同步函数**（涨落节奏完全来自银丝自身，无独立余辉包络），
 * 仅锚点不连续消失（遮挡/离场）走固定短淡出。簇内伴星按与最亮者的比值四次方
 * 压暗；被更近层水面轮廓盖住的星连同针芒整体消失（PSF 发生在人眼）。
 */
internal class FableSolStarField(private val density: Double) {

    companion object {
        const val MAX_STARS = 8
        /** 每颗星的输出 float 数：xLocalPx, yLocalPx, amplitude, r, g, b, seed。 */
        const val FLOATS_PER_STAR = 7

        private const val ATTACK_SECONDS = 0.09
        private const val SETTLE_SECONDS = 0.18
        private const val FADE_SECONDS = 0.36
        private const val POSITION_SECONDS = 0.10
        // D218 近层起晕偏置：层阈值再乘 1−bias×weight⁴（近层银丝视角
        // 更大、更低亮度即可起晕；4 次方与簇内主从同指数——用户裁决
        // L0 特效应始终最多：L0 越阈要求 −60%，L1/L2 与其拉开 51%/94%
        // 但绝对值仍比偏置前宽松，远层不变；三段素材九桶实测 8 桶
        // L0 居首）；包络参考波速把起振/稳定/位置跟随
        // 折算成恒定空间距离（τ_layer = τ×min(1, 150/波速)，只缩短
        // 快层）。淡出是消失美学，不随波速缩放。
        private const val PROXIMITY_THRESHOLD_BIAS = 0.6
        private const val ENVELOPE_REFERENCE_SPEED_DPS = 150.0
        private const val MATCH_DISTANCE_DP = 24.0
        private const val MIN_SEPARATION_DP = 12.0
        private const val LAYER_CAPACITY = 3
        private const val BIRTH_FACTOR = 0.2
        private const val RETIRE_EXCESS = 0.01
        private const val BIRTH_MIN_EXCESS_SCALE = 0.05
        private const val APEX_GATE_LO = 0.04
        private const val APEX_GATE_HI = 0.18
        /** 前层遮挡软边（dp）：Python 侧 8px@DENSITY2 = 4dp。 */
        private const val OCCLUSION_SOFT_DP = 4.0
        private const val DOMINANCE_RADIUS_DP = 32.0
        private const val DOMINANCE_EXPONENT = 4.0
        private const val MAX_ANCHORS = LAYER_CAPACITY + 1
        private const val MAX_TRACKING_DT_SECONDS = 1.0 / 15.0

        internal fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
            val t = ((value - edge0) / max(edge1 - edge0, 1e-6)).coerceIn(0.0, 1.0)
            return t * t * (3.0 - 2.0 * t)
        }

        private fun hash21(x: Double, y: Double): Double {
            val raw = sin(x * 127.1 + y * 311.7) * 43758.5453
            return raw - floor(raw)
        }

        /** water.frag valueNoiseDerivative 的值通道（IQ 值噪声）。 */
        internal fun valueNoise01(px: Double, py: Double): Double {
            val cellX = floor(px)
            val fx = px - cellX
            val cellY = floor(py)
            val fy = py - cellY
            val a = hash21(cellX, cellY)
            val b = hash21(cellX + 1.0, cellY)
            val c = hash21(cellX, cellY + 1.0)
            val d = hash21(cellX + 1.0, cellY + 1.0)
            val ux = fx * fx * fx * (fx * (fx * 6.0 - 15.0) + 10.0)
            val uy = fy * fy * fy * (fy * (fy * 6.0 - 15.0) + 10.0)
            return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
        }
    }

    private class Track(
        @JvmField var u: Double,
        @JvmField var intensity: Double,
        @JvmField var releaseFrom: Double = 0.0,
        // 图案种子（D213）：出生时定、终生稳定——逐星针表差异。
        @JvmField var seed: Double = 0.5
    )

    private val tracks = Array(FableSolSpec.N_LAYERS) { ArrayList<Track>(LAYER_CAPACITY) }
    private var lastTrackTime = 0.0

    // 逐层扫描 scratch（单线程串行调用，跨层复用）。
    private val excess = DoubleArray(FableSolSpec.N_POINTS)
    private val anchorU = DoubleArray(MAX_ANCHORS)
    private val anchorExcess = DoubleArray(MAX_ANCHORS)
    private val anchorTarget = DoubleArray(MAX_ANCHORS)
    private val anchorUsed = BooleanArray(MAX_ANCHORS)
    private var anchorCount = 0

    // 候选（层号 + 本地坐标 + 振幅 + 色调），遮挡后压入输出。
    private val candidateLayer = IntArray(FableSolSpec.N_LAYERS * LAYER_CAPACITY)
    private val candidateU = DoubleArray(FableSolSpec.N_LAYERS * LAYER_CAPACITY)
    private val candidateY = DoubleArray(FableSolSpec.N_LAYERS * LAYER_CAPACITY)
    private val candidateAmplitude = DoubleArray(FableSolSpec.N_LAYERS * LAYER_CAPACITY)
    private val candidateSeed = DoubleArray(FableSolSpec.N_LAYERS * LAYER_CAPACITY)
    private var candidateCount = 0

    /** 输出：starData[i×6..] = xLocalPx, yLocalPx, amplitude, r, g, b。 */
    @JvmField val starData = FloatArray(MAX_STARS * FLOATS_PER_STAR)
    var starCount = 0
        private set

    fun clear() {
        for (list in tracks) list.clear()
        starCount = 0
    }

    /**
     * 每帧在 optics.build 之后调用（GL 线程）。[waterVertices] 布局与
     * [FableSolGlMeshLayout] 一致（vertex = row×columns+column，本地未旋转 px）。
     */
    fun update(
        sim: FableSolSimulation,
        params: FableSolParams,
        columns: Int,
        waterVertices: FloatArray,
        layerMeanYPx: FloatArray,
        thicknessRangePx: Double,
        crestRimX0Px: Double,
        crestRimSpanPx: Double,
        crestRimActivity: Double,
        layerSubsurfaceStart: FloatArray,
        hdrAmplitudeScale: Double = 1.0
    ) {
        starCount = 0
        candidateCount = 0
        if (columns < 5) return
        val strengthParam = params.get("glare_strength")
        if (strengthParam <= 1e-4) {
            for (list in tracks) list.clear()
            return
        }
        val dt = (sim.t - lastTrackTime).coerceIn(0.0, MAX_TRACKING_DT_SECONDS)
        lastTrackTime = sim.t

        val rimStrength = params.get("uplift_crest_rim") * crestRimActivity
        // 辐亮度场恒用 3.6 标定档（触发频率/阈值/主从的既定标定不随用户
        // HDR 强度漂移）；用户强度对亮度的抬升由 hdrAmplitudeScale 在
        // 出射振幅上补（D217），与银丝 shader 端 boost×excessScale 同步。
        val peakBoost = max(params.get("uplift_rim_peak") - 1.0, 0.0)
        val threshold = params.get("glare_threshold")
        val depthFalloff = max(params.get("glare_depth_falloff"), 0.0)
        val azimuth = Math.toRadians(params.get("light_azimuth_deg"))
        val sunSide = (sin(azimuth) * 4.0).coerceIn(-1.0, 1.0)
        val azimuthClamped = azimuth.coerceIn(-0.9599, 0.9599)
        val slideDepth = 0.60 * params.get("uplift_rim_slide")
        val slidePhasePx = (64.0 * sim.t) % 360.0 * density
        val matchDistance = MATCH_DISTANCE_DP * density
        val minSeparation = MIN_SEPARATION_DP * density

        for (layer in 0 until FableSolSpec.N_LAYERS) {
            val layerTracks = tracks[layer]
            val weight = FableSolMaterialPolicy.CREST_RIM_WEIGHTS[layer].toDouble()
            val fieldReady = weight > 0.005 && rimStrength > 1e-4 && peakBoost > 1e-4
            anchorCount = 0
            if (fieldReady) {
                scanLayer(
                    sim, layer, columns, waterVertices, layerMeanYPx,
                    thicknessRangePx, crestRimX0Px, crestRimSpanPx,
                    rimStrength, peakBoost, weight, threshold, sunSide,
                    azimuthClamped, slideDepth, slidePhasePx, minSeparation
                )
            }
            if (layerTracks.isEmpty() && anchorCount == 0) continue
            applyDominance()
            val birthMin = BIRTH_MIN_EXCESS_SCALE * weight + 0.005
            val timeScale = min(
                1.0,
                ENVELOPE_REFERENCE_SPEED_DPS /
                    max(params.lget("wave_speed_dps", layer), 1.0)
            )
            updateTracks(layerTracks, dt, birthMin, matchDistance, timeScale)
            if (layerTracks.isEmpty()) continue
            val depthGain = weight.pow(depthFalloff)
            val row = layer * FableSolContinuousSurface.ROWS_PER_LAYER
            for (track in layerTracks) {
                val amplitude = sqrt(max(track.intensity, 0.0)) * depthGain
                // 可见性门在标定振幅上判定（出星与否不随 HDR 强度改变），
                // 缩放只作用于出射亮度。
                if (amplitude < 0.02) continue
                if (candidateCount >= candidateLayer.size) break
                candidateLayer[candidateCount] = layer
                candidateU[candidateCount] = track.u
                candidateY[candidateCount] =
                    surfaceYAt(waterVertices, row, columns, track.u)
                candidateAmplitude[candidateCount] = amplitude * hdrAmplitudeScale
                candidateSeed[candidateCount] = track.seed
                candidateCount++
            }
        }
        emitVisibleStars(columns, waterVertices, layerSubsurfaceStart)
    }

    /** 与 water.frag crestRimSun 同构的波顶辐亮度场 → apex 门 → 越阈局部峰。 */
    private fun scanLayer(
        sim: FableSolSimulation,
        layer: Int,
        columns: Int,
        waterVertices: FloatArray,
        layerMeanYPx: FloatArray,
        thicknessRangePx: Double,
        crestRimX0Px: Double,
        crestRimSpanPx: Double,
        rimStrength: Double,
        peakBoost: Double,
        weight: Double,
        threshold: Double,
        sunSide: Double,
        azimuthClamped: Double,
        slideDepth: Double,
        slidePhasePx: Double,
        minSeparation: Double
    ) {
        val row = layer * FableSolContinuousSurface.ROWS_PER_LAYER
        val base = row * columns
        val depth = layer.toDouble() / (FableSolSpec.N_LAYERS - 1)
        val meanY = layerMeanYPx[layer].toDouble()
        val range = max(thicknessRangePx, 1.0)
        // 触发阈值按层权重等比放宽（活跃度留在辐亮度里做响度门，不进包络），
        // 再乘近层起晕偏置（D218）：L0 越阈要求恒为最低，随深度单调升高。
        val weight01 = min(weight, 1.0)
        val layerThreshold = 1.0 + (threshold - 1.0) * weight01 *
            (1.0 - PROXIMITY_THRESHOLD_BIAS * weight01.pow(4))
        val center = (0.5 + tan(azimuthClamped) * 0.28 * (depth - 0.5))
            .coerceIn(0.18, 0.82)
        val halfWidth = 0.11 + (0.055 - 0.11) * depth
        val seed = weight.coerceIn(0.0, 1.0) * 3.7
        val span = max(crestRimSpanPx, 1.0)
        val components = FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
        // 太阳柱窗口裁剪（D214）：apex 门 smoothstep(0.04, …) 在 column 项
        // < 0.04 处精确为零 → 窗外列必无 excess，标量循环只扫窗内
        // （近层省 ~45%、远层省 ~70%，锚点逐位不变）。
        val windowX01 = halfWidth * 2.54
        val xLo = crestRimX0Px + (center - windowX01) * span
        val xHi = crestRimX0Px + (center + windowX01) * span
        var cLo = 0
        var cHi = columns
        run {
            var lo = 0
            var hi = columns - 1
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (waterVertices[(base + mid) * components] < xLo) lo = mid + 1
                else hi = mid
            }
            cLo = (lo - 1).coerceAtLeast(0)
            lo = 0
            hi = columns - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (waterVertices[(base + mid) * components] > xHi) hi = mid - 1
                else lo = mid
            }
            cHi = (lo + 2).coerceAtMost(columns)
        }
        java.util.Arrays.fill(excess, 0, columns, 0.0)
        for (column in cLo until cHi) {
            val offset = (base + column) * components
            val x = waterVertices[offset].toDouble()
            val y = waterVertices[offset + 1].toDouble()
            val sheenX =
                waterVertices[offset + FableSolGlMeshLayout.SHEEN_SLOPE_X_OFFSET].toDouble()
            val sheenZ =
                waterVertices[offset + FableSolGlMeshLayout.SHEEN_SLOPE_Z_OFFSET].toDouble()
            val prominence = (meanY - y) / range
            val crestGate = 0.55 + 0.45 * smoothstep(0.0, 0.18, prominence)
            val knotMix = if (slideDepth > 1e-4) {
                val u01 = (x - slidePhasePx) / (360.0 * density)
                val jitter = (valueNoise01(u01 * 0.53 + 3.7, seed) - 0.5) * 2.4
                val wave = 0.5 + 0.5 * sin(2.0 * Math.PI * u01 + jitter + seed * 5.1)
                (1.0 - slideDepth) + slideDepth * smoothstep(0.24, 0.78, wave)
            } else {
                1.0
            }
            val body = rimStrength * weight * crestGate * knotMix
            val normalX = -sheenX / sqrt(1.0 + sheenX * sheenX + sheenZ * sheenZ)
            val alignRaw = (0.5 + 1.1 * sunSide * normalX).coerceIn(0.0, 1.0)
            val align = smoothstep(0.40, 0.82, alignRaw)
            val flatTop = 1.0 - smoothstep(0.03, 0.30, abs(sheenX))
            val lifted = smoothstep(0.05, 0.35, prominence)
            val x01 = ((x - crestRimX0Px) / span).coerceIn(0.0, 1.0)
            val columnDelta = (x01 - center) / halfWidth
            val apex = flatTop * lifted * exp(-0.5 * columnDelta * columnDelta)
            val radiance = 1.0 + peakBoost * body * (align + 2.2 * apex)
            val apexGate = smoothstep(APEX_GATE_LO, APEX_GATE_HI, apex)
            excess[column] = max(radiance - layerThreshold, 0.0) * apexGate
        }
        findAnchors(columns, waterVertices, base, minSeparation)
    }

    /** 与 Python _find_anchors 同语义：局部峰按值降序贪心 + 最小间距抑制。 */
    private fun findAnchors(
        columns: Int,
        waterVertices: FloatArray,
        base: Int,
        minSeparation: Double
    ) {
        anchorCount = 0
        val components = FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
        // 局部峰一次收集（数量级 ≤ 十几个），再贪心选前 MAX_ANCHORS 个。
        var remaining = true
        val taken = BooleanArray(columns)
        while (remaining && anchorCount < MAX_ANCHORS) {
            var bestColumn = -1
            var bestValue = 1e-3
            for (column in 2 until columns - 2) {
                if (taken[column]) continue
                val value = excess[column]
                if (value <= bestValue) continue
                if (value < excess[column - 1] || value <= excess[column + 1]) continue
                bestColumn = column
                bestValue = value
            }
            if (bestColumn < 0) {
                remaining = false
                continue
            }
            taken[bestColumn] = true
            val u = waterVertices[(base + bestColumn) * components].toDouble()
            var suppressed = false
            for (index in 0 until anchorCount) {
                if (abs(anchorU[index] - u) < minSeparation) {
                    suppressed = true
                    break
                }
            }
            if (suppressed) continue
            anchorU[anchorCount] = u
            anchorExcess[anchorCount] = bestValue
            anchorCount++
        }
    }

    /** 簇内主从：按 32dp 邻域最亮者的比值四次方压暗伴星。 */
    private fun applyDominance() {
        val radius = DOMINANCE_RADIUS_DP * density
        for (index in 0 until anchorCount) {
            var localMax = anchorExcess[index]
            for (other in 0 until anchorCount) {
                if (abs(anchorU[other] - anchorU[index]) < radius) {
                    localMax = max(localMax, anchorExcess[other])
                }
            }
            val ratio = anchorExcess[index] / max(localMax, 1e-6)
            anchorTarget[index] = anchorExcess[index] * ratio.pow(DOMINANCE_EXPONENT)
        }
    }

    /**
     * 有锚点时短平滑跟随银丝辐亮度（同步涨落），失锚才走固定 0.36s 线性淡出。
     * 与 Python update_star_tracks 一比一。
     */
    private fun updateTracks(
        layerTracks: ArrayList<Track>,
        dt: Double,
        birthMinExcess: Double,
        matchDistance: Double,
        timeScale: Double
    ) {
        java.util.Arrays.fill(anchorUsed, 0, anchorCount, false)
        val positionGain = 1.0 - exp(-dt / max(POSITION_SECONDS * timeScale, 1e-3))
        val attackGain = 1.0 - exp(-dt / max(ATTACK_SECONDS * timeScale, 1e-3))
        val settleGain = 1.0 - exp(-dt / max(SETTLE_SECONDS * timeScale, 1e-3))
        for (track in layerTracks) {
            var best = -1
            var bestDistance = matchDistance
            for (index in 0 until anchorCount) {
                if (anchorUsed[index]) continue
                val distance = abs(anchorU[index] - track.u)
                if (distance < bestDistance) {
                    best = index
                    bestDistance = distance
                }
            }
            if (best >= 0) {
                anchorUsed[best] = true
                track.releaseFrom = 0.0
                track.u += (anchorU[best] - track.u) * positionGain
                val target = anchorTarget[best]
                val gain = if (target > track.intensity) attackGain else settleGain
                track.intensity += (target - track.intensity) * gain
            } else {
                if (track.releaseFrom <= 0.0) {
                    track.releaseFrom = max(track.intensity, 1e-6)
                }
                track.intensity -= track.releaseFrom * dt / FADE_SECONDS
            }
        }
        // 零分配整理（D214）：手写移除，避免每帧 lambda/迭代器垃圾。
        var write = 0
        for (read in layerTracks.indices) {
            val track = layerTracks[read]
            if (track.intensity > RETIRE_EXCESS) {
                if (write != read) layerTracks[write] = track
                write++
            }
        }
        while (layerTracks.size > write) layerTracks.removeAt(layerTracks.size - 1)
        for (index in 0 until anchorCount) {
            if (anchorUsed[index] || anchorTarget[index] < birthMinExcess) continue
            val seedRaw = sin(anchorU[index] * 12.9898) * 43758.5453
            layerTracks.add(Track(anchorU[index], anchorTarget[index] * BIRTH_FACTOR,
                                  seed = seedRaw - floor(seedRaw)))
        }
        // 容量 ≤4 的插入排序（降序），避免 sortByDescending 的装箱分配。
        for (i in 1 until layerTracks.size) {
            val current = layerTracks[i]
            var j = i - 1
            while (j >= 0 && layerTracks[j].intensity < current.intensity) {
                layerTracks[j + 1] = layerTracks[j]
                j--
            }
            layerTracks[j + 1] = current
        }
        while (layerTracks.size > LAYER_CAPACITY) {
            layerTracks.removeAt(layerTracks.size - 1)
        }
    }

    /** x 单调递增（投影单调修复保证），二分插值本层水面 y。 */
    private fun surfaceYAt(
        waterVertices: FloatArray,
        row: Int,
        columns: Int,
        u: Double
    ): Double {
        val components = FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
        val base = row * columns
        var lo = 0
        var hi = columns - 1
        val first = waterVertices[base * components].toDouble()
        val last = waterVertices[(base + hi) * components].toDouble()
        if (u <= first) return waterVertices[base * components + 1].toDouble()
        if (u >= last) return waterVertices[(base + hi) * components + 1].toDouble()
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (waterVertices[(base + mid) * components].toDouble() <= u) lo = mid
            else hi = mid
        }
        val x0 = waterVertices[(base + lo) * components].toDouble()
        val x1 = waterVertices[(base + hi) * components].toDouble()
        val y0 = waterVertices[(base + lo) * components + 1].toDouble()
        val y1 = waterVertices[(base + hi) * components + 1].toDouble()
        val fraction = ((u - x0) / max(x1 - x0, 1e-6)).coerceIn(0.0, 1.0)
        return y0 + (y1 - y0) * fraction
    }

    /** 前层遮挡（软边随波过渡）+ 全局容量裁剪 → 输出星表。 */
    private fun emitVisibleStars(
        columns: Int,
        waterVertices: FloatArray,
        layerSubsurfaceStart: FloatArray
    ) {
        val soft = OCCLUSION_SOFT_DP * density
        starCount = 0
        // 简单选择排序取振幅前 MAX_STARS（候选 ≤ 27，规模可忽略）。
        val order = (0 until candidateCount).sortedByDescending { candidateAmplitude[it] }
        for (index in order) {
            if (starCount >= MAX_STARS) break
            val layer = candidateLayer[index]
            val u = candidateU[index]
            val starY = candidateY[index]
            var visibility = 1.0
            for (front in 0 until layer) {
                val frontRow = front * FableSolContinuousSurface.ROWS_PER_LAYER
                val frontY = surfaceYAt(waterVertices, frontRow, columns, u)
                // 屏幕 y 向下：星落到前层轮廓之下即进入其水体。
                val t = ((starY - frontY + soft * 0.25) / soft).coerceIn(0.0, 1.0)
                visibility *= 1.0 - t * t * (3.0 - 2.0 * t)
                if (visibility < 0.02) break
            }
            val amplitude = candidateAmplitude[index] * visibility
            if (amplitude < 0.02) continue
            // 星裙色调向白 0.62（D210，原 0.888）：核心靠显示 cap 饱和依然
            // 白炽，裙边与宽晕带出水体身份色的柔光。
            val colorBase = layer * 3
            val r = layerSubsurfaceStart[colorBase].toDouble()
            val g = layerSubsurfaceStart[colorBase + 1].toDouble()
            val b = layerSubsurfaceStart[colorBase + 2].toDouble()
            val peak = max(max(r, g), max(b, 1e-3))
            val cursor = starCount * FLOATS_PER_STAR
            starData[cursor] = u.toFloat()
            starData[cursor + 1] = starY.toFloat()
            starData[cursor + 2] = amplitude.toFloat()
            starData[cursor + 3] = (1.0 - 0.38 * (1.0 - r / peak)).toFloat()
            starData[cursor + 4] = (1.0 - 0.38 * (1.0 - g / peak)).toFloat()
            starData[cursor + 5] = (1.0 - 0.38 * (1.0 - b / peak)).toFloat()
            starData[cursor + 6] = candidateSeed[index].toFloat()
            starCount++
        }
    }
}
