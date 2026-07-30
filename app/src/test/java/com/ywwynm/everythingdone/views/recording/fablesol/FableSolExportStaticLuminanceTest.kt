package com.ywwynm.everythingdone.views.recording.fablesol

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PQ 母版意图、全片静态亮度统计与静态元数据描述符（fablesol-video-export D82～D92、D166）。
 *
 * 这些数字全部写进产物、离开应用之后就再也改不了，而它们错了不会有任何运行时症状——只会让
 * 播放端按错误的边界做色调映射。因此每个字段都由测试逐字节钉住。
 */
class FableSolExportStaticLuminanceTest {

    private val whiteNits = FableSolExportTransfer.SDR_WHITE_NITS
    private val maxStrength = FableSolHdrPolicy.MAX_STRENGTH.toDouble()

    // ---- 25 字节描述符 ----

    /** ST 2086 的虚拟母版 primaries 是 **P3-D65**，不是编码容器的 BT.2020（D88）。 */
    @Test
    fun masteringPrimariesAreDisplayP3WithD65White() {
        val bytes = descriptor(measured(1.0, 0.2))
        assertEquals(FableSolExportStaticMetadataCheck.DESCRIPTOR_BYTES, bytes.size)
        assertEquals(0, bytes[0].toInt())
        // 单位 0.00002：0.680 → 34000。
        assertEquals(34000, readShort(bytes, 1))
        assertEquals(16000, readShort(bytes, 3))
        assertEquals(13250, readShort(bytes, 5))
        assertEquals(34500, readShort(bytes, 7))
        assertEquals(7500, readShort(bytes, 9))
        assertEquals(3000, readShort(bytes, 11))
        // D65 白点（D87）：0.3127 / 0.3290。
        assertEquals(15635, readShort(bytes, 13))
        assertEquals(16450, readShort(bytes, 15))
        // BT.2020 的红原色是 0.708 → 35400；它不该再出现在母版字段里。
        assertNotEquals(35400, readShort(bytes, 1))
    }

    /** 母版亮度范围：最低固定 `0.0001 nit`，最高取 `ceil(漫反射白 × HDR 强度)`（D89）。 */
    @Test
    fun masteringLuminanceRangeFollowsTheRenderingCeiling() {
        val peak = whiteNits * maxStrength
        val bytes = descriptor(measured(1.0, 0.2), peakNits = peak)
        assertEquals(1949, readShort(bytes, 17))
        assertEquals(
            FableSolExportTransfer.MASTERING_MIN_LUMINANCE_UNITS, readShort(bytes, 19)
        )
    }

    /** 实测 MaxCLL 高于理论峰值时，母版色容积必须跟着抬高——不能装不下自己的内容。 */
    @Test
    fun masteringPeakAlwaysCoversTheMeasuredContent() {
        val bytes = descriptor(measured(10.0, 1.0), peakNits = whiteNits * maxStrength)
        val maxCll = readShort(bytes, 21)
        assertEquals(2030, maxCll)
        assertTrue(readShort(bytes, 17) >= maxCll)
    }

    /** MaxCLL / MaxFALL 用实测值；向**上**取整，写进去的整数仍是实际信号的上界（D86）。 */
    @Test
    fun contentLightLevelsComeFromTheMeasurementAndRoundUp() {
        val stats = measured(peak = 4.2731, average = 0.6142)
        assertEquals(868, stats.maxContentLightLevel(whiteNits))   // 867.44 → 868
        assertEquals(125, stats.maxFrameAverageLightLevel(whiteNits)) // 124.68 → 125
        val bytes = descriptor(stats)
        assertEquals(868, readShort(bytes, 21))
        assertEquals(125, readShort(bytes, 23))
    }

    /**
     * D90 的回退：`MaxCLL` 取理论上界，`MaxFALL` 写 0。
     *
     * 零不是"零尼特画面"，按 H.274 它明确表示未提供该上界。把漫反射白顶上去会**低报**，
     * 把理论峰值顶上去又会暗示存在接近峰值的全屏亮画面，两者都比"未知"更糟。
     */
    @Test
    fun theFallbackReportsATheoreticalPeakAndAnUnknownAverage() {
        val stats = FableSolExportLuminanceStats.theoretical(maxStrength)
        assertFalse(stats.measured)
        assertEquals(1949, stats.maxContentLightLevel(whiteNits))
        assertEquals(0, stats.maxFrameAverageLightLevel(whiteNits))
        val bytes = descriptor(stats, peakNits = whiteNits * maxStrength)
        assertEquals(1949, readShort(bytes, 21))
        assertEquals(0, readShort(bytes, 23))
    }

    /** 统计存的是归一化值：只改漫反射白时直接重新缩放，不必再渲染一遍全片（D92）。 */
    @Test
    fun normalizedStatisticsRescaleWithTheDiffuseWhite() {
        val stats = measured(peak = 2.0, average = 0.5)
        assertEquals(406, stats.maxContentLightLevel(203.0))
        assertEquals(102, stats.maxContentLightLevel(51.0))
        assertEquals(800, stats.maxContentLightLevel(400.0))
        assertEquals(200, stats.maxFrameAverageLightLevel(400.0))
    }

    // ---- 帧平均的加权口径 ----

    /**
     * 边缘块不满时必须按实际像素数加权。
     *
     * 画布尺寸未必被 32 整除；直接把 1024 个块均值算术平均，等于给边缘那一列/一行多算了
     * 权重，MaxFALL 会系统性偏移。
     */
    @Test
    fun frameAverageWeightsPartialBlocksByTheirPixelCount() {
        val grid = FableSolExportLuminanceReducer.GRID
        // 1152×1472 正好被 32 整除：每块权重相同，加权平均退化成算术平均。
        val even = FableSolExportLuminanceReducer.blockWeights(1152, 1472)
        assertEquals(36.0 * 46.0, even[0], 0.0)
        assertEquals(1152.0 * 1472.0, even.sum(), 0.0)

        // 不整除时最后一列/行的块更小，总和仍然精确等于画布像素数。
        val odd = FableSolExportLuminanceReducer.blockWeights(1000, 700)
        assertEquals(1000.0 * 700.0, odd.sum(), 0.0)
        assertTrue(odd[grid - 1] < odd[0])

        // 亮的小块与暗的大块：算术平均会被小块拉高，加权平均不会。
        val means = DoubleArray(grid * grid)
        means[grid - 1] = 1.0
        val weighted = FableSolExportLuminanceReducer.frameAverage(means, odd)
        val naive = means.sum() / means.size
        assertTrue(weighted < naive)
        assertEquals(odd[grid - 1] / odd.sum(), weighted, 1e-12)
    }

    // ---- 短探测的回读核对（D166） ----

    /** 一致即通过。 */
    @Test
    fun anIdenticalDescriptorMatches() {
        val bytes = descriptor(measured(1.0, 0.2))
        assertTrue(
            FableSolExportStaticMetadataCheck.compare(bytes, bytes.copyOf())
                is FableSolExportStaticMetadataCheck.Outcome.Match
        )
    }

    /**
     * 容器写入器把最低母版亮度乘了 10000，这一读数必须接受。
     *
     * 平台结构里该字段的单位就是 0.0001 尼特，AOSP 的 MP4 写入器却当成尼特再换算一次；
     * OPPO PLZ110 上注入 1 读回来是 10000，同机的码流 SEI 里仍是 1（2026-07-29 实测）。
     * 不接受这一读数，等于每台设备都被判失败。
     */
    @Test
    fun theKnownContainerMinLuminanceConversionIsAccepted() {
        val expected = descriptor(measured(1.0, 0.2))
        val actual = expected.copyOf()
        writeShort(
            actual, 19,
            FableSolExportTransfer.MASTERING_MIN_LUMINANCE_UNITS *
                FableSolExportStaticMetadataCheck.CONTAINER_MIN_LUMINANCE_SCALE
        )
        assertTrue(
            FableSolExportStaticMetadataCheck.compare(expected, actual)
                is FableSolExportStaticMetadataCheck.Outcome.Match
        )
    }

    /** 真正冲突的字段一个都不放过。 */
    @Test
    fun aConflictingFieldFailsTheCandidate() {
        val expected = descriptor(measured(1.0, 0.2))
        for (offset in listOf(1, 13, 17, 21, 23)) {
            val actual = expected.copyOf()
            writeShort(actual, offset, readShort(expected, offset) + 7)
            val outcome = FableSolExportStaticMetadataCheck.compare(expected, actual)
            assertTrue(
                "offset $offset must conflict",
                outcome is FableSolExportStaticMetadataCheck.Outcome.Conflict
            )
        }
        // 长度不足同样是冲突：读到半截描述符说明容器里的东西根本不是它。
        assertTrue(
            FableSolExportStaticMetadataCheck.compare(expected, ByteArray(10))
                is FableSolExportStaticMetadataCheck.Outcome.Conflict
        )
    }

    /**
     * 容器完全没有携带描述符时不判失败。
     *
     * `MediaMuxer` 把 KEY_HDR_STATIC_INFO 落盘成容器级 box 是较新 Android 才有的行为；旧系统
     * 上实际承载的是编码器按 configure 注入生成的码流 SEI，而 SEI 在这一层读不到（D166）。
     */
    @Test
    fun anAbsentContainerDescriptorIsNotAFailure() {
        assertNull(
            FableSolExportStaticMetadataCheck.verify(
                File("this-file-does-not-exist.mp4"),
                FableSolExportTransfer.hdr10StaticInfo(
                    peakNits = whiteNits * maxStrength,
                    diffuseWhiteNits = whiteNits,
                    luminance = measured(1.0, 0.2)
                )
            )
        )
    }

    private fun measured(peak: Double, average: Double) =
        FableSolExportLuminanceStats(peak, average, measured = true)

    private fun descriptor(
        stats: FableSolExportLuminanceStats,
        peakNits: Double = whiteNits * maxStrength
    ): ByteArray {
        val buffer = FableSolExportTransfer.hdr10StaticInfo(
            peakNits = peakNits,
            diffuseWhiteNits = whiteNits,
            luminance = stats
        )
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun writeShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
