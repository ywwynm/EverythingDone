package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.GLES31
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

/**
 * HDR10+ 逐像素统计的两级后端（fablesol-video-export D104、D124、D169）。
 *
 * 1. **GLES 3.1**：compute shader + SSBO，一次 dispatch 同时产出完整 CFD 直方图、逐工作组
 *    三通道峰值与 maxRGB 线性总和。
 * 2. **GLES 3.0**：把逐像素量按 24 位打进全分辨率 RGBA8 回读，CPU 只做解包、计数与求和。
 *    统计定义与精度不降级，代价是每帧一次全分辨率回读（D124）。
 *
 * 两个后端产出**同一种** [FableSolExportHdr10PlusHistogram]，桶定义与求值规则完全一致，
 * 因此"逐项相等"是可判定的（D169）。当前 32×32 块平均不得成为第三个发布后端——它可以留作
 * 诊断，但不能冒充 ST 2094-40 的逐像素 CFD。
 */
internal class FableSolExportHdr10PlusStatsBackend(
    private val assets: AssetManager,
    private val widthPx: Int,
    private val heightPx: Int,
    /** 漫反射白（尼特）；统计要换算到以 10000 尼特为上限的载荷归一化域。 */
    diffuseWhiteNits: Double
) {

    private val scale = (diffuseWhiteNits / FableSolExportTransfer.PQ_MAX_NITS).toFloat()

    private val proxyProgram = FableSolGlProgram(
        assets, FULLSCREEN_VERT, "fablesol/glsl/hdr10plus_proxy.frag"
    )
    private val proxyWidth = (widthPx + FableSolHdr10PlusStats.PROXY_SCALE - 1) /
        FableSolHdr10PlusStats.PROXY_SCALE
    private val proxyHeight = (heightPx + FableSolHdr10PlusStats.PROXY_SCALE - 1) /
        FableSolHdr10PlusStats.PROXY_SCALE
    private val proxyTarget = Rgba8Target(proxyWidth, proxyHeight)
    private val proxyBytes = ByteArray(proxyWidth * proxyHeight * 4)
    private val proxyBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(proxyBytes.size)
        .order(ByteOrder.nativeOrder())
    private val proxyLuminance = DoubleArray(proxyWidth * proxyHeight)

    private val counts = IntArray(FableSolExportHdr10PlusHistogram.BUCKET_COUNT)

    private var compute: Compute? = null
    private var fallback: Fallback? = null

    /** 非 null 即本条统计通路不可用；调用方按 D104 判当前 HDR10+ 候选失败。 */
    var failure: String? = null
        private set

    /**
     * FBP 代理链的已知图结论（D109）；非 null 表示不可信，本次导出的 FBP 一律按规范写
     * 零值（未计算）并由完成信息披露，不判候选失败。
     */
    var fbpKnownImageFailure: String? = null
        private set

    init {
        // 门控落在**真实** GL 版本上，不看请求的 EGL_CONTEXT_CLIENT_VERSION（D104）。
        if (FableSolGlComputeProgram.supportsCompute()) {
            val stats = FableSolGlComputeProgram.create(
                assets, "fablesol/glsl/hdr10plus_stats.comp"
            )
            val clear = FableSolGlComputeProgram.create(
                assets, "fablesol/glsl/hdr10plus_clear.comp"
            )
            if (stats != null && clear != null) {
                val candidate = Compute(stats, clear)
                if (candidate.failure == null) {
                    compute = candidate
                } else {
                    candidate.release()
                }
            } else {
                stats?.release()
                clear?.release()
            }
        }
        if (compute == null) {
            fallback = try {
                Fallback(
                    FableSolGlProgram(
                        assets, FULLSCREEN_VERT, "fablesol/glsl/hdr10plus_pack.frag"
                    )
                )
            } catch (error: Throwable) {
                failure = "No usable HDR10+ statistics backend: ${error.message}"
                null
            }
        }
    }

    /** 本次实际使用的后端；诊断与完成信息如实显示（D104）。 */
    val stableLabel: String get() = if (compute != null) LABEL_COMPUTE else LABEL_READBACK

    /**
     * 归约本帧。
     *
     * @param linearTextureId 最终可见合成的线性 BT.2020 纹理（呈现趟的第二个附件）。
     * @return null 表示统计失败；调用方不得发布统计不完整的 HDR10+ 产物。
     */
    fun measure(linearTextureId: Int): FableSolHdr10PlusStats? {
        if (failure != null) return null
        val histogram = compute?.run(linearTextureId, counts)
            ?: fallback?.run(linearTextureId, counts)
        if (histogram == null) {
            failure = compute?.failure ?: fallback?.failure ?: "HDR10+ statistics unavailable"
            return null
        }
        // FractionBrightPixels 单独失败时写规范零值并继续（D109）：它只描述高亮面积，不参与
        // 曲线，也不影响其它任何门禁；不得用它掩盖核心逐像素通路故障。代理帧平均值与 FBP
        // 必须来自同一次回读，跨帧场景会用前者选出规范要求的最亮代理帧。
        val proxy = try {
            measureProxy(linearTextureId)
        } catch (ignored: Throwable) {
            null
        }
        val fbp = proxy?.let(FableSolHdr10PlusStats::fractionBrightPixels) ?: 0.0
        val proxyAverage = proxy?.takeIf { it.isNotEmpty() }?.average()
        return FableSolHdr10PlusStats.of(histogram, fbp, proxyAverage)
    }

    /**
     * 5:1 平滑代理帧的亮度（D108）。
     *
     * @return null 表示代理链本次不可用（已知图未通过，或回读报 GL 错误）；调用方按 D109
     *   写规范零值，**不得**把陈旧或未定义的回读字节当成测量结果。
     */
    private fun measureProxy(linearTextureId: Int): DoubleArray? {
        if (fbpKnownImageFailure != null) return null
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, proxyTarget.framebufferId)
        GLES30.glViewport(0, 0, proxyWidth, proxyHeight)
        GLES30.glDisable(GLES30.GL_BLEND)
        proxyProgram.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, linearTextureId)
        GLES30.glUniform1i(proxyProgram.uniform("uSource"), 0)
        GLES30.glUniform1i(proxyProgram.uniform("uWidth"), widthPx)
        GLES30.glUniform1i(proxyProgram.uniform("uHeight"), heightPx)
        GLES30.glUniform1f(proxyProgram.uniform("uScale"), scale)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        proxyBuffer.clear()
        GLES30.glReadPixels(
            0, 0, proxyWidth, proxyHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, proxyBuffer
        )
        // 回读失败必须显式判掉（D109）：不检查错误码就继续，会把上一帧的陈旧字节或未定义
        // 内容算成一个"看起来正常"的 FBP 写进载荷。核心统计的回退路径 `pass()` 一直有这条
        // 检查，代理链此前漏了。
        val error = GLES30.glGetError()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (error != GLES30.GL_NO_ERROR) return null
        proxyBuffer.clear()
        proxyBuffer.get(proxyBytes)
        for (index in proxyLuminance.indices) {
            proxyLuminance[index] = decode24(proxyBytes, index * 4)
        }
        return proxyLuminance
    }

    /**
     * 已知图自检（D104、D109、D169）。
     *
     * 向**当前选中的后端**喂一张 8×8 的两色已知图（左 5 列色 A、右 3 列色 B），走与正式
     * 统计完全相同的 `measure()` 流水线，与 CPU 参考逐项比对：
     *
     * - **直方图**：两个非零桶的计数必须精确等于 40 与 24（D169"逐项完全相等"——测试值
     *   经 [midBucketValue] 自适应挑在桶中央，f32 与 double、24 位定点三种算术在这里必然
     *   落同一个桶，精确相等是可判定的）；
     * - **MaxSCL / 线性和**：与参考值的偏差不超过两条路径各自的舍入上界；
     * - **FBP**：两色的亮度差远超权重带宽，代理 2×2 恰为 {A,B,A,B}，期望值精确为 0.5。
     *
     * compute 后端核心统计不过时释放它并降到 GLES 3.0 兼容后端重测——这正是 D104 写明的
     * "已知图验证失败时使用兼容路径"；兼容后端也不过才置 [failure]。FBP 单独不过只置
     * [fbpKnownImageFailure]，本次导出按 D109 写零值，不判候选失败。
     *
     * 必须在 GL 上下文当前时调用，且先于任何正式 `measure()`。
     */
    fun verifyKnownImage() {
        if (failure != null) return
        val texture = uploadKnownImage() ?: run {
            // 建不出 8×8 的已知图纹理说明 GL 已整体失效，正式统计同样不可能成功。
            failure = "HDR10+ known-image texture unavailable"
            return
        }
        try {
            var report = runKnownImage(texture)
            if (report.core != null && compute != null) {
                compute?.release()
                compute = null
                fallback = try {
                    Fallback(
                        FableSolGlProgram(
                            assets, FULLSCREEN_VERT, "fablesol/glsl/hdr10plus_pack.frag"
                        )
                    )
                } catch (error: Throwable) {
                    failure = "No usable HDR10+ statistics backend: ${error.message}"
                    return
                }
                report = runKnownImage(texture)
            }
            if (report.core != null) {
                failure = "HDR10+ known-image verification failed: ${report.core}"
                return
            }
            fbpKnownImageFailure = report.fbp
        } finally {
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }

    private class KnownImageReport(val core: String?, val fbp: String?)

    /** 已知图的左右分界（像素）：对齐 5 像素代理块，两侧色块因此不跨代理块。 */
    private val knownSplitX = ((widthPx / 2) / FableSolHdr10PlusStats.PROXY_SCALE)
        .coerceAtLeast(1) * FableSolHdr10PlusStats.PROXY_SCALE

    private fun runKnownImage(textureId: Int): KnownImageReport {
        // 与 shader 同一条 f32 乘法；参考值必须复刻实际算术，不能各算各的。
        val aMax = KNOWN_A.max() * scale
        val bMax = KNOWN_B.max() * scale
        val bucketA = FableSolExportHdr10PlusHistogram.bucketOf(aMax.toDouble())
        val bucketB = FableSolExportHdr10PlusHistogram.bucketOf(bMax.toDouble())
        val pixels = widthPx.toLong() * heightPx
        val aPixels = knownSplitX.toLong() * heightPx
        val bPixels = pixels - aPixels
        // 代理块列数与 B 色纯块列数：分界对齐 5 像素，末列部分块也整块是 B。
        val proxyColumns = (widthPx + FableSolHdr10PlusStats.PROXY_SCALE - 1) /
            FableSolHdr10PlusStats.PROXY_SCALE
        val brightColumns = proxyColumns - knownSplitX / FableSolHdr10PlusStats.PROXY_SCALE
        val expectedFbp = brightColumns.toDouble() / proxyColumns
        val sumEpsilon = kotlin.math.max(1e-4, pixels * 1e-7)

        val stats = measureKnown(textureId)
            ?: return KnownImageReport(
                core = compute?.failure ?: fallback?.failure ?: "no statistics", fbp = null
            )
        val histogram = stats.histogram
            ?: return KnownImageReport(core = "histogram missing", fbp = null)
        val core = when {
            bucketA == bucketB -> "degenerate test values"
            histogram.pixelCount != pixels -> "pixel count ${histogram.pixelCount}"
            histogram.massBetween(aMax.toDouble(), aMax.toDouble()) != aPixels ->
                "bucket A count != $aPixels"
            histogram.massBetween(bMax.toDouble(), bMax.toDouble()) != bPixels ->
                "bucket B count != $bPixels"
            (0 until 3).any { channel ->
                val expected = maxOf(KNOWN_A[channel], KNOWN_B[channel]) * scale
                kotlin.math.abs(histogram.maxScl[channel] - expected) > MAXSCL_EPSILON
            } -> "maxScl mismatch"
            kotlin.math.abs(
                histogram.sum -
                    (aPixels * aMax.toDouble() + bPixels * bMax.toDouble())
            ) > sumEpsilon -> "sum mismatch"
            else -> null
        }
        val fbp = when {
            core != null -> null
            stats.proxyAverageLuminance == null -> "proxy readback unavailable"
            kotlin.math.abs(stats.fractionBrightPixels - expectedFbp) > FBP_EPSILON ->
                "fbp %.4f != %.4f".format(stats.fractionBrightPixels, expectedFbp)
            else -> null
        }
        return KnownImageReport(core, fbp)
    }

    /** 与 [measure] 同一条流水线，但绕开 [fbpKnownImageFailure] 门（自检自己要读代理链）。 */
    private fun measureKnown(textureId: Int): FableSolHdr10PlusStats? {
        val histogram = compute?.run(textureId, counts)
            ?: fallback?.run(textureId, counts)
            ?: return null
        val proxy = try {
            measureProxy(textureId)
        } catch (ignored: Throwable) {
            null
        }
        val fbp = proxy?.let(FableSolHdr10PlusStats::fractionBrightPixels) ?: 0.0
        return FableSolHdr10PlusStats.of(
            histogram, fbp, proxy?.takeIf { it.isNotEmpty() }?.average()
        )
    }

    /**
     * 生成**画布尺寸**的 RGBA16F 已知图：左 [knownSplitX] 列色 A、其余色 B；行方向均匀。
     *
     * 两个后端的归约固定在画布尺寸上（uWidth/uHeight 与各缓冲按画布分配），已知图必须同尺寸，
     * 否则 texelFetch 会大面积越界。用两次 `glClear` 填充（浮点颜色附件的 clear 值不被钳制，
     * ES 3.0），代价只是两次清屏，没有兆级的 CPU 上传。分界对齐 5 像素代理块，5:1 代理帧的
     * 每一块因此只含一种颜色，FBP 期望值不依赖边缘平均的实现细节。数值经 [midBucketValue]
     * 自适应挑到桶中央，三种算术不会在桶边界上分家。
     */
    private fun uploadKnownImage(): Int? {
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) Unit
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val texture = textures[0]
        if (texture == 0) return null
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexStorage2D(
            GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, widthPx, heightPx
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        val framebuffers = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffers, 0)
        val framebuffer = framebuffers[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, texture, 0
        )
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) ==
            GLES30.GL_FRAMEBUFFER_COMPLETE
        if (complete) {
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glClearColor(KNOWN_A[0], KNOWN_A[1], KNOWN_A[2], 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            GLES30.glScissor(knownSplitX, 0, widthPx - knownSplitX, heightPx)
            GLES30.glClearColor(KNOWN_B[0], KNOWN_B[1], KNOWN_B[2], 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        if (!complete || GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            return null
        }
        return texture
    }

    /** 已知图的两个颜色（漫反射白相对域，half 精确）；构造时按当前 [scale] 挑到桶中央。 */
    private val KNOWN_A = floatArrayOf(
        midBucketValue(26f / 256f), midBucketValue(12f / 256f), midBucketValue(6f / 256f)
    )
    private val KNOWN_B = floatArrayOf(
        midBucketValue(384f / 256f), midBucketValue(512f / 256f), midBucketValue(256f / 256f)
    )

    /**
     * 把 half 精确的候选值调整到"缩放后落在桶中央"的最近值。
     *
     * 三种算术（compute 的 f32、CPU 的 double、兼容路径的 24 位定点）只会在桶**边界**上
     * 分家；把测试值挑到桶中央（缩放值 × 100000 的小数部分落在 0.25～0.75），精确相等就是
     * 结构性质而不是运气。步进 1/256 保持 half 精确（值 < 2 时 half 间距 ≤ 1/1024）。
     */
    private fun midBucketValue(base: Float): Float {
        var value = base
        repeat(64) {
            val position = value * scale * 100000f
            val fraction = position - kotlin.math.floor(position)
            if (fraction in 0.25f..0.75f) return value
            value += 1f / 256f
        }
        return value
    }

    fun release() {
        proxyProgram.release()
        proxyTarget.release()
        compute?.release()
        fallback?.release()
    }

    // ---- GLES 3.1 ----

    private inner class Compute(
        private val stats: FableSolGlComputeProgram,
        private val clear: FableSolGlComputeProgram
    ) {

        private val groupsX = (widthPx + GROUP_SIZE - 1) / GROUP_SIZE
        private val groupsY = (heightPx + GROUP_SIZE - 1) / GROUP_SIZE
        private val groupCount = groupsX * groupsY
        private val buffers = IntArray(2)
        private val partialBytes = ByteArray(groupCount * 16)
        private val partialBuffer: ByteBuffer = ByteBuffer
            .allocateDirect(partialBytes.size)
            .order(ByteOrder.nativeOrder())
        private val histogramBuffer: ByteBuffer = ByteBuffer
            .allocateDirect(FableSolExportHdr10PlusHistogram.BUCKET_COUNT * 4)
            .order(ByteOrder.nativeOrder())

        var failure: String? = null
            private set

        init {
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) Unit
            GLES31.glGenBuffers(2, buffers, 0)
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[0])
            GLES31.glBufferData(
                GLES31.GL_SHADER_STORAGE_BUFFER,
                FableSolExportHdr10PlusHistogram.BUCKET_COUNT * 4,
                null,
                GLES31.GL_DYNAMIC_COPY
            )
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[1])
            GLES31.glBufferData(
                GLES31.GL_SHADER_STORAGE_BUFFER, groupCount * 16, null, GLES31.GL_DYNAMIC_COPY
            )
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
            if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                failure = "HDR10+ SSBO allocation failed"
            }
        }

        fun run(
            linearTextureId: Int,
            counts: IntArray
        ): FableSolExportHdr10PlusHistogram? {
            if (failure != null) return null
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffers[0])
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, buffers[1])

            // 400 KB 的计数器在 GPU 上写零；每帧从 CPU 上传一遍是同样量级的白白带宽。
            clear.use()
            GLES31.glUniform1i(
                clear.uniform("uCount"), FableSolExportHdr10PlusHistogram.BUCKET_COUNT
            )
            GLES31.glDispatchCompute(
                (FableSolExportHdr10PlusHistogram.BUCKET_COUNT + 255) / 256, 1, 1
            )
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

            stats.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, linearTextureId)
            GLES31.glUniform1i(stats.uniform("uSource"), 0)
            GLES31.glUniform1i(stats.uniform("uWidth"), widthPx)
            GLES31.glUniform1i(stats.uniform("uHeight"), heightPx)
            GLES31.glUniform1f(stats.uniform("uScale"), scale)
            GLES31.glDispatchCompute(groupsX, groupsY, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

            if (!readInto(buffers[0], FableSolExportHdr10PlusHistogram.BUCKET_COUNT * 4, histogramBuffer)) {
                failure = "HDR10+ histogram readback failed"
                return null
            }
            if (!readInto(buffers[1], groupCount * 16, partialBuffer)) {
                failure = "HDR10+ partial readback failed"
                return null
            }

            histogramBuffer.clear()
            histogramBuffer.asIntBuffer().get(counts)

            partialBuffer.clear()
            val floats = partialBuffer.asFloatBuffer()
            val maxScl = DoubleArray(3)
            var sum = 0.0
            for (group in 0 until groupCount) {
                val base = group * 4
                for (channel in 0 until 3) {
                    val value = floats.get(base + channel).toDouble()
                    if (value > maxScl[channel]) maxScl[channel] = value
                }
                sum += floats.get(base + 3).toDouble()
            }
            return FableSolExportHdr10PlusHistogram(
                counts = counts,
                pixelCount = widthPx.toLong() * heightPx.toLong(),
                maxScl = maxScl,
                sum = sum
            )
        }

        private fun readInto(buffer: Int, size: Int, destination: ByteBuffer): Boolean {
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer)
            val mapped = GLES31.glMapBufferRange(
                GLES31.GL_SHADER_STORAGE_BUFFER, 0, size, GLES31.GL_MAP_READ_BIT
            ) as? ByteBuffer
            if (mapped == null) {
                GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
                return false
            }
            mapped.order(ByteOrder.nativeOrder())
            destination.clear()
            destination.put(mapped)
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
            destination.clear()
            return true
        }

        fun release() {
            stats.release()
            clear.release()
            GLES31.glDeleteBuffers(2, buffers, 0)
        }
    }

    // ---- GLES 3.0 ----

    private inner class Fallback(private val program: FableSolGlProgram) {

        private val target = Rgba8Target(widthPx, heightPx)
        private val bytes = ByteArray(widthPx * heightPx * 4)
        private val buffer: ByteBuffer = ByteBuffer
            .allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())

        var failure: String? = null
            private set

        fun run(
            linearTextureId: Int,
            counts: IntArray
        ): FableSolExportHdr10PlusHistogram? {
            Arrays.fill(counts, 0)
            if (!pass(linearTextureId, channel = 0)) return null
            var sum = 0.0
            val pixels = widthPx * heightPx
            for (index in 0 until pixels) {
                val value = decode24(bytes, index * 4)
                counts[FableSolExportHdr10PlusHistogram.bucketOf(value)]++
                sum += value
            }
            // MaxSCL 不经过桶（D103、D169）：三个通道各跑一趟，保留 24 位精度。
            val maxScl = DoubleArray(3)
            for (channel in 1..3) {
                if (!pass(linearTextureId, channel)) return null
                var peak = 0.0
                for (index in 0 until pixels) {
                    val value = decode24(bytes, index * 4)
                    if (value > peak) peak = value
                }
                maxScl[channel - 1] = peak
            }
            return FableSolExportHdr10PlusHistogram(
                counts = counts,
                pixelCount = pixels.toLong(),
                maxScl = maxScl,
                sum = sum
            )
        }

        private fun pass(linearTextureId: Int, channel: Int): Boolean {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebufferId)
            GLES30.glViewport(0, 0, widthPx, heightPx)
            GLES30.glDisable(GLES30.GL_BLEND)
            program.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, linearTextureId)
            GLES30.glUniform1i(program.uniform("uSource"), 0)
            GLES30.glUniform1i(program.uniform("uWidth"), widthPx)
            GLES30.glUniform1i(program.uniform("uHeight"), heightPx)
            GLES30.glUniform1f(program.uniform("uScale"), scale)
            GLES30.glUniform1i(program.uniform("uChannel"), channel)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            buffer.clear()
            GLES30.glReadPixels(
                0, 0, widthPx, heightPx, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer
            )
            val error = GLES30.glGetError()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            if (error != GLES30.GL_NO_ERROR) {
                failure = "HDR10+ readback failed: 0x${Integer.toHexString(error)}"
                return false
            }
            buffer.clear()
            buffer.get(bytes)
            return true
        }

        fun release() {
            program.release()
            target.release()
        }
    }

    /** 一个 RGBA8 离屏目标。 */
    private inner class Rgba8Target(val widthPx: Int, val heightPx: Int) {

        var textureId = 0
            private set
        var framebufferId = 0
            private set

        init {
            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, widthPx, heightPx)
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST
            )
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            val framebuffers = IntArray(1)
            GLES30.glGenFramebuffers(1, framebuffers, 0)
            framebufferId = framebuffers[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                textureId,
                0
            )
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "HDR10+ stats target ${widthPx}x$heightPx incomplete"
            }
        }

        fun release() {
            if (framebufferId != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
                framebufferId = 0
            }
            if (textureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
        }
    }

    companion object {

        const val LABEL_COMPUTE = "GLES 3.1 compute"
        const val LABEL_READBACK = "GLES 3.0 readback"

        private const val FULLSCREEN_VERT = "fablesol/glsl/fullscreen.vert"
        private const val GROUP_SIZE = 16

        /** 已知图 MaxSCL 允许偏差：两条路径的舍入上界都在 1e-7 量级（f32 / 24 位定点）。 */
        private const val MAXSCL_EPSILON = 1e-6

        /** 已知图 FBP 允许偏差：期望值由纯色代理块精确给出，只留浮点噪声余量。 */
        private const val FBP_EPSILON = 1e-6

        /** 24 位小端解包，与 `hdr10plus_pack.frag` / `hdr10plus_proxy.frag` 同源。 */
        fun decode24(bytes: ByteArray, offset: Int): Double {
            val low = bytes[offset].toInt() and 0xFF
            val mid = bytes[offset + 1].toInt() and 0xFF
            val high = bytes[offset + 2].toInt() and 0xFF
            return (low or (mid shl 8) or (high shl 16)).toDouble() / 16777215.0
        }
    }
}
