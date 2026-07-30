package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.abs

/**
 * HDR Vivid 逐场景元数据时间线。
 *
 * 预分析按呈现顺序建立时间线，编码阶段再用输出样本 PTS 查表。因此即使编码器输出 B 帧重排，
 * 每个 access unit 仍会拿到属于其呈现帧的 T.35 载荷。
 */
internal class FableSolHdrVividTimeline private constructor(
    private val frameRate: Int,
    private val payloads: List<ByteArray>,
    val sceneCount: Int,
    val hardBoundaryCount: Int,
    val luminance: FableSolExportLuminanceStats
) {

    val frameCount: Int get() = payloads.size

    val uniquePayloadCount: Int by lazy {
        val unique = ArrayList<ByteArray>()
        for (payload in payloads) {
            if (unique.none { it.contentEquals(payload) }) unique += payload
        }
        unique.size
    }

    fun payloadForFrame(frameIndex: Int): ByteArray {
        check(payloads.isNotEmpty()) { "HDR Vivid timeline is empty" }
        return payloads[frameIndex.coerceIn(0, payloads.lastIndex)]
    }

    fun payloadAt(presentationTimeUs: Long): ByteArray {
        if (presentationTimeUs <= 0L) return payloadForFrame(0)
        val wholeSeconds = presentationTimeUs / MICROS_PER_SECOND
        val remainingMicros = presentationTimeUs % MICROS_PER_SECOND
        val roundedFrame = wholeSeconds * frameRate +
            (remainingMicros * frameRate + MICROS_PER_SECOND / 2L) / MICROS_PER_SECOND
        return payloadForFrame(roundedFrame.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /**
     * 场景检测与时间滤波策略。默认值以时间换算成帧数，保证 30/60/120 fps 的行为一致。
     */
    data class Policy(
        val minimumSceneFrames: Int,
        val maximumSceneFrames: Int,
        val transitionFrames: Int,
        val hardAverageDeltaPq: Double = DEFAULT_HARD_AVERAGE_DELTA_PQ,
        val hardDistributionDeltaPq: Double = DEFAULT_HARD_DISTRIBUTION_DELTA_PQ,
        val softAverageDriftPq: Double = DEFAULT_SOFT_AVERAGE_DRIFT_PQ,
        val softDistributionDriftPq: Double = DEFAULT_SOFT_DISTRIBUTION_DRIFT_PQ
    ) {

        init {
            require(minimumSceneFrames >= 1)
            require(maximumSceneFrames >= minimumSceneFrames)
            require(transitionFrames >= 0)
            require(hardAverageDeltaPq >= 0.0)
            require(hardDistributionDeltaPq >= 0.0)
            require(softAverageDriftPq >= 0.0)
            require(softDistributionDriftPq >= 0.0)
        }

        companion object {

            fun forFrameRate(frameRate: Int): Policy {
                require(frameRate > 0)
                return Policy(
                    minimumSceneFrames = maxOf(3, frameRate / 3),
                    maximumSceneFrames = maxOf(frameRate * 5, 4),
                    transitionFrames = maxOf(2, frameRate / 4)
                )
            }
        }
    }

    class Builder(
        private val frameRate: Int,
        private val diffuseWhiteNits: Double,
        private val targetNits: Double,
        private val highlightStartPercent: Int,
        private val policy: Policy = Policy.forFrameRate(frameRate)
    ) {

        private val scenes = ArrayList<Scene>()
        private var accumulator = newAccumulator()
        private var currentBoundary = Boundary.NONE
        private var sceneStartFrame = 0
        private var framesInScene = 0
        private var totalFrames = 0
        private var previousDescriptor: Descriptor? = null
        private var sceneAnchor: Descriptor? = null
        private var completed: FableSolHdrVividTimeline? = null
        private var resultRequested = false

        init {
            require(frameRate > 0)
            require(diffuseWhiteNits.isFinite() && diffuseWhiteNits > 0.0)
            require(targetNits.isFinite() && targetNits > 0.0)
        }

        fun add(frame: FableSolHdr10PlusStats) {
            check(!resultRequested) { "HDR Vivid timeline is already completed" }
            checkNotNull(frame.histogram) {
                "HDR Vivid timeline requires complete per-frame histograms"
            }
            val descriptor = Descriptor.from(frame)
            val boundary = boundaryBefore(descriptor)
            if (boundary != Boundary.NONE) {
                finishCurrentScene()
                accumulator = newAccumulator()
                currentBoundary = boundary
                sceneStartFrame = totalFrames
                framesInScene = 0
                sceneAnchor = null
            }
            accumulator.add(frame)
            if (sceneAnchor == null) sceneAnchor = descriptor
            previousDescriptor = descriptor
            framesInScene++
            totalFrames++
        }

        fun result(): FableSolHdrVividTimeline? {
            if (resultRequested) return completed
            resultRequested = true
            finishCurrentScene()
            if (scenes.isEmpty()) return null

            val payloads = MutableList(totalFrames) {
                FableSolHdrVividMetadata.payload(scenes.first().metadata)
            }
            for (scene in scenes) {
                val payload = FableSolHdrVividMetadata.payload(scene.metadata)
                for (frame in scene.startFrame until scene.endFrameExclusive) {
                    payloads[frame] = payload
                }
            }
            for (index in 1 until scenes.size) {
                val scene = scenes[index]
                if (scene.boundaryFromPrevious != Boundary.SOFT) continue
                val previous = scenes[index - 1]
                val transitionCount = minOf(
                    policy.transitionFrames,
                    scene.endFrameExclusive - scene.startFrame
                )
                for (offset in 0 until transitionCount) {
                    val amount = (offset + 1).toDouble() / transitionCount
                    payloads[scene.startFrame + offset] = FableSolHdrVividMetadata.payload(
                        previous.metadata.blend(scene.metadata, amount)
                    )
                }
            }

            val luminance = FableSolExportLuminanceStats(
                maxContentNormalized =
                    scenes.maxOf { it.luminance.maxContentNormalized },
                maxFrameAverageNormalized =
                    scenes.maxOf { it.luminance.maxFrameAverageNormalized },
                measured = scenes.all { it.luminance.measured }
            )
            return FableSolHdrVividTimeline(
                frameRate = frameRate,
                payloads = payloads,
                sceneCount = scenes.size,
                hardBoundaryCount =
                    scenes.count { it.boundaryFromPrevious == Boundary.HARD },
                luminance = luminance
            ).also { completed = it }
        }

        private fun boundaryBefore(descriptor: Descriptor): Boundary {
            if (framesInScene < policy.minimumSceneFrames) return Boundary.NONE
            val previous = previousDescriptor
            if (
                previous != null &&
                abs(descriptor.averagePq - previous.averagePq) >=
                policy.hardAverageDeltaPq &&
                descriptor.distributionDistance(previous) >=
                policy.hardDistributionDeltaPq
            ) {
                return Boundary.HARD
            }
            if (framesInScene >= policy.maximumSceneFrames) return Boundary.SOFT
            val anchor = sceneAnchor ?: return Boundary.NONE
            if (
                abs(descriptor.averagePq - anchor.averagePq) >=
                policy.softAverageDriftPq &&
                descriptor.distributionDistance(anchor) >=
                policy.softDistributionDriftPq
            ) {
                return Boundary.SOFT
            }
            return Boundary.NONE
        }

        private fun finishCurrentScene() {
            if (framesInScene <= 0) return
            val result = accumulator.result() ?: return
            val metadata = FableSolHdrVividFrameMetadata(
                statistics = FableSolHdrVividStatistics.from(result.stats),
                toneMappings = listOf(
                    FableSolHdrVividCurve.parameters(
                        stats = result.stats,
                        targetNits = targetNits,
                        highlightStartPercent = highlightStartPercent
                    )
                )
            )
            scenes += Scene(
                startFrame = sceneStartFrame,
                endFrameExclusive = totalFrames,
                boundaryFromPrevious = currentBoundary,
                metadata = metadata,
                luminance = result.luminance
            )
        }

        private fun newAccumulator() =
            FableSolExportHdr10PlusSceneAccumulator(diffuseWhiteNits)
    }

    private enum class Boundary {
        NONE,
        SOFT,
        HARD
    }

    private data class Scene(
        val startFrame: Int,
        val endFrameExclusive: Int,
        val boundaryFromPrevious: Boundary,
        val metadata: FableSolHdrVividFrameMetadata,
        val luminance: FableSolExportLuminanceStats
    )

    private data class Descriptor(
        val averagePq: Double,
        val distributionPq: DoubleArray
    ) {

        fun distributionDistance(other: Descriptor): Double {
            var total = 0.0
            for (index in distributionPq.indices) {
                total += abs(distributionPq[index] - other.distributionPq[index])
            }
            return total / distributionPq.size
        }

        companion object {

            fun from(stats: FableSolHdr10PlusStats): Descriptor =
                Descriptor(
                    averagePq = FableSolExportHdr10PlusMetadata.linearToPq(
                        stats.averageMaxRgb
                    ),
                    distributionPq = DoubleArray(DETECTION_PERCENTILES.size) { index ->
                        FableSolExportHdr10PlusMetadata.linearToPq(
                            stats.percentile(DETECTION_PERCENTILES[index])
                        )
                    }
                )
        }
    }

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
        const val DEFAULT_HARD_AVERAGE_DELTA_PQ = 0.08
        const val DEFAULT_HARD_DISTRIBUTION_DELTA_PQ = 0.06
        const val DEFAULT_SOFT_AVERAGE_DRIFT_PQ = 0.035
        const val DEFAULT_SOFT_DISTRIBUTION_DRIFT_PQ = 0.025
        val DETECTION_PERCENTILES = doubleArrayOf(10.0, 25.0, 50.0, 75.0, 90.0, 95.0, 99.0)
    }
}
