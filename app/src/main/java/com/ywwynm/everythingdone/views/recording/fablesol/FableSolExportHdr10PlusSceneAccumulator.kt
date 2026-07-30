package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 把连续 FableSol 动画的逐帧测量累计成一个 ST 2094-40 场景（D177）。
 *
 * HDR10+ VOD 的统计语义是场景，不是“每个 120 fps 帧都重新定义一次场景”。逐帧测量仍保留，
 * 但只作为离线预分析的原始样本：正式码流里的 MaxSCL、AverageMaxRGB、CFD、FBP 与创作曲线
 * 均由这里生成一次，然后在整个连续场景内重复同一份载荷。
 */
internal class FableSolExportHdr10PlusSceneAccumulator(
    diffuseWhiteNits: Double
) {

    data class Result(
        val stats: FableSolHdr10PlusStats,
        val luminance: FableSolExportLuminanceStats
    )

    private val relativeToAbsoluteScale =
        diffuseWhiteNits / FableSolExportTransfer.PQ_MAX_NITS
    private val counts = LongArray(FableSolExportHdr10PlusHistogram.BUCKET_COUNT)
    private val maxScl = DoubleArray(3)
    private var pixelCount = 0L
    private var sum = 0.0
    private var maxFrameAverage = 0.0
    private var brightestProxyAverage: Double? = null
    private var brightestFrameFbp = 0.0
    private var proxyComplete = true
    private var frames = 0L
    private var completed = false

    init {
        require(diffuseWhiteNits > 0.0 && diffuseWhiteNits.isFinite())
    }

    /** 按播放顺序加入一帧；代理帧平均亮度并列时自然由后加入的帧获胜。 */
    fun add(frame: FableSolHdr10PlusStats) {
        check(!completed) { "HDR10+ scene accumulation already completed" }
        val histogram = checkNotNull(frame.histogram) {
            "HDR10+ scene requires a complete per-pixel histogram"
        }
        histogram.addCountsTo(counts)
        pixelCount = Math.addExact(pixelCount, histogram.pixelCount)
        sum += histogram.sum
        for (channel in maxScl.indices) {
            val value = histogram.maxScl.getOrElse(channel) { 0.0 }
            if (value > maxScl[channel]) maxScl[channel] = value
        }
        if (histogram.averageMaxRgb > maxFrameAverage) {
            maxFrameAverage = histogram.averageMaxRgb
        }

        val proxyAverage = frame.proxyAverageLuminance?.takeIf { it.isFinite() }
        if (proxyAverage == null) {
            // 少一帧就无法证明已选中场景最亮代理帧；按 D109 写“未计算”零值，不能从剩余帧
            // 猜一个 FBP 冒充完整场景结果。
            proxyComplete = false
        } else {
            val previous = brightestProxyAverage
            if (previous == null || proxyAverage >= previous) {
                brightestProxyAverage = proxyAverage
                brightestFrameFbp = frame.fractionBrightPixels
            }
        }
        frames++
    }

    /** 一帧都没有时返回 null；结果生成后不允许继续累计。 */
    fun result(): Result? {
        if (frames <= 0L || pixelCount <= 0L) return null
        completed = true
        val histogram = FableSolExportHdr10PlusHistogram(
            counts = counts.copyOf(),
            pixelCount = pixelCount,
            maxScl = maxScl.copyOf(),
            sum = sum
        )
        val sceneStats = FableSolHdr10PlusStats.of(
            histogram = histogram,
            fractionBrightPixels = if (proxyComplete) brightestFrameFbp else 0.0,
            proxyAverageLuminance = brightestProxyAverage.takeIf { proxyComplete }
        )
        val absoluteToRelativeScale = 1.0 / relativeToAbsoluteScale
        return Result(
            stats = sceneStats,
            luminance = FableSolExportLuminanceStats(
                maxContentNormalized =
                    (maxScl.maxOrNull() ?: 0.0) * absoluteToRelativeScale,
                maxFrameAverageNormalized = maxFrameAverage * absoluteToRelativeScale,
                measured = true
            )
        )
    }
}
