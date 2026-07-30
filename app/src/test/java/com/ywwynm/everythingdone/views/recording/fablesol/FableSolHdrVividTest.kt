package com.ywwynm.everythingdone.views.recording.fablesol

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T/UWA 005.1 与 T/UWA 005.2-1 的 HDR Vivid 1.0 统计载荷、HEVC SEI 和 MP4 `cuvv`。
 */
class FableSolHdrVividTest {

    @Test
    fun statisticsPayloadMatchesTheOfficialBitLayout() {
        val payload = FableSolHdrVividMetadata.payload(
            FableSolHdrVividStatistics(
                minimumMaxRgbPq = 0x123,
                averageMaxRgbPq = 0x456,
                varianceMaxRgbPq = 0x789,
                maximumMaxRgbPq = 0xABC
            )
        )
        assertArrayEquals(
            byteArrayOf(
                0x26,
                0x00, 0x04,
                0x00, 0x05,
                0x01,
                0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte(),
                // tone-mapping flag、saturation flag 与 6 个 stuffing bits 全部为 0。
                0x00
            ),
            payload
        )
        assertEquals(FableSolHdrVividMetadata.PAYLOAD_BYTES, payload.size)
        assertTrue(FableSolHdrVividMetadata.containsSignature(payload))
    }

    @Test
    fun vividQuantisationUsesFloorAndVarianceConvertsTheLinearP90MinusP10Once() {
        assertEquals(2047, FableSolHdrVividStatistics.quantize(0.5))
        val samples = DoubleArray(100) { index ->
            // 1%～100% PQ，先转到现有直方图使用的线性域。
            FableSolExportHdr10PlusMetadata.pqToLinear((index + 1) / 100.0)
        }
        val histogram = FableSolExportHdr10PlusHistogram.of(samples)
        val stats = FableSolHdr10PlusStats.of(histogram, fractionBrightPixels = 0.0)
        val vivid = FableSolHdrVividStatistics.from(stats)

        assertEquals(
            FableSolHdrVividStatistics.quantize(
                FableSolExportHdr10PlusMetadata.linearToPq(histogram.percentile(0.0))
            ),
            vivid.minimumMaxRgbPq
        )
        assertEquals(
            FableSolHdrVividStatistics.quantize(
                FableSolExportHdr10PlusMetadata.linearToPq(histogram.averageMaxRgb)
            ),
            vivid.averageMaxRgbPq
        )
        val expectedRange = FableSolExportHdr10PlusMetadata.linearToPq(
            histogram.percentile(90.0) - histogram.percentile(10.0)
        )
        assertEquals(
            FableSolHdrVividStatistics.quantize(expectedRange),
            vivid.varianceMaxRgbPq
        )
        assertEquals(
            FableSolHdrVividStatistics.quantize(
                FableSolExportHdr10PlusMetadata.linearToPq(
                    stats.maxScl.maxOrNull() ?: 0.0
                )
            ),
            vivid.maximumMaxRgbPq
        )
    }

    @Test
    fun fullToneMappingPayloadCarriesBaseCurveAndTwoThreeSplineIntervals() {
        val stats = vividStatsAtPq(0.72, spread = 0.18)
        val toneMapping = FableSolHdrVividCurve.parameters(
            stats = stats,
            targetNits = 400.0,
            highlightStartPercent = 90
        )
        val frame = FableSolHdrVividFrameMetadata(
            statistics = FableSolHdrVividStatistics.from(stats),
            toneMappings = listOf(toneMapping)
        )
        val payload = FableSolHdrVividMetadata.payload(frame)

        assertTrue(payload.size > FableSolHdrVividMetadata.PAYLOAD_BYTES)
        assertEquals(2, toneMapping.splines.size)
        assertEquals(3, toneMapping.base.deltaMode)
        assertEquals(0, toneMapping.base.delta)

        val bits = PayloadBitReader(payload, offsetBytes = 5)
        assertEquals(FableSolHdrVividMetadata.SYSTEM_START_CODE, bits.read(8))
        repeat(4) { bits.read(12) }
        assertEquals(1, bits.read(1)) // tone_mapping_enable_mode_flag
        assertEquals(0, bits.read(1)) // 一组 tone-mapping 参数
        assertEquals(toneMapping.targetedSystemDisplayMaximumLuminancePq, bits.read(12))
        assertEquals(1, bits.read(1)) // base_enable_flag
        assertEquals(toneMapping.base.mP, bits.read(14))
        assertEquals(toneMapping.base.mM, bits.read(6))
        assertEquals(toneMapping.base.mA, bits.read(10))
        assertEquals(toneMapping.base.mB, bits.read(10))
        assertEquals(toneMapping.base.mN, bits.read(6))
        assertEquals(toneMapping.base.k1, bits.read(2))
        assertEquals(toneMapping.base.k2, bits.read(2))
        assertEquals(toneMapping.base.k3, bits.read(4))
        assertEquals(toneMapping.base.deltaMode, bits.read(3))
        assertEquals(toneMapping.base.delta, bits.read(7))
        assertEquals(1, bits.read(1)) // 3Spline_enable_flag
        assertEquals(1, bits.read(1)) // 两组 3Spline
        for (spline in toneMapping.splines) {
            assertEquals(spline.mode, bits.read(2))
            if (spline.mode == 0 || spline.mode == 2) {
                assertEquals(spline.mb, bits.read(8))
            }
            assertEquals(spline.threshold, bits.read(12))
            assertEquals(spline.delta1, bits.read(10))
            assertEquals(spline.delta2, bits.read(10))
            assertEquals(spline.strength, bits.read(8))
        }
        assertEquals(0, bits.read(1)) // color_saturation_mapping_enable_flag
    }

    @Test
    fun fittedBaseCurveIsMonotonicAndReachesTheReferenceDisplayPeak() {
        val stats = vividStatsAtPq(0.82, spread = 0.22)
        val toneMapping = FableSolHdrVividCurve.parameters(
            stats = stats,
            targetNits = 500.0,
            highlightStartPercent = 90
        )
        val sourcePq = FableSolHdrVividStatistics.from(stats).maximumMaxRgbPq /
            FableSolHdrVividStatistics.MAX_PQ_CODE.toDouble()
        val targetPq = FableSolExportHdr10PlusMetadata.linearToPq(
            500.0 / FableSolExportTransfer.PQ_MAX_NITS
        )

        var previous = toneMapping.base.evaluate(0.0)
        for (index in 1..64) {
            val current = toneMapping.base.evaluate(sourcePq * index / 64.0)
            assertTrue("base curve is not monotonic at $index", current + 1e-9 >= previous)
            previous = current
        }
        assertEquals(targetPq, toneMapping.base.evaluate(sourcePq), 0.035)
    }

    @Test
    fun quantizedBaseCurvePassesTheMonotonicAndEndpointGateAcrossHdrRanges() {
        val cases = listOf(
            0.48 to 1000.0,
            0.68 to 500.0,
            0.82 to 500.0,
            0.90 to 300.0
        )
        for ((centerPq, targetNits) in cases) {
            val stats = vividStatsAtPq(centerPq, spread = 0.12)
            val toneMapping = FableSolHdrVividCurve.parameters(
                stats = stats,
                targetNits = targetNits,
                highlightStartPercent = 90
            )
            val sourcePq = FableSolHdrVividStatistics.from(stats).maximumMaxRgbPq /
                FableSolHdrVividStatistics.MAX_PQ_CODE.toDouble()
            val targetPq = FableSolExportHdr10PlusMetadata.linearToPq(
                targetNits / FableSolExportTransfer.PQ_MAX_NITS
            )
            val expectedEndpoint = minOf(sourcePq, targetPq)

            var previous = toneMapping.base.evaluate(0.0)
            for (index in 1..64) {
                val current = toneMapping.base.evaluate(sourcePq * index / 64.0)
                assertTrue(
                    "center=$centerPq target=$targetNits index=$index",
                    current + 1e-9 >= previous
                )
                previous = current
            }
            assertEquals(
                "center=$centerPq target=$targetNits",
                expectedEndpoint,
                toneMapping.base.evaluate(sourcePq),
                0.03
            )
            assertTrue(
                "2080 is reserved for an SDR tone-mapping target",
                toneMapping.targetedSystemDisplayMaximumLuminancePq != 2080
            )
            assertEquals(2, toneMapping.splines.size)
        }
    }

    @Test
    fun timelineUsesHardSceneBoundariesAndPresentationTimestamps() {
        val builder = FableSolHdrVividTimeline.Builder(
            frameRate = 10,
            diffuseWhiteNits = 203.0,
            targetNits = 400.0,
            highlightStartPercent = 90,
            policy = FableSolHdrVividTimeline.Policy(
                minimumSceneFrames = 3,
                maximumSceneFrames = 20,
                transitionFrames = 2
            )
        )
        repeat(6) { builder.add(vividStatsAtPq(0.28, spread = 0.04)) }
        repeat(6) { builder.add(vividStatsAtPq(0.82, spread = 0.08)) }

        val timeline = requireNotNull(builder.result())
        assertEquals(2, timeline.sceneCount)
        assertTrue(timeline.hardBoundaryCount >= 1)
        assertFalse(timeline.payloadForFrame(0).contentEquals(timeline.payloadForFrame(6)))
        assertArrayEquals(timeline.payloadForFrame(6), timeline.payloadForFrame(7))
        assertArrayEquals(timeline.payloadForFrame(0), timeline.payloadAt(0L))
        assertArrayEquals(timeline.payloadForFrame(6), timeline.payloadAt(600_000L))
        // 模拟 B 帧重排后的非单调输出顺序；查表结果只由 PTS 决定。
        assertArrayEquals(timeline.payloadForFrame(0), timeline.payloadAt(0L))
    }

    @Test
    fun softSceneBoundariesAverageMetadataElementsWithoutCrossingHardCuts() {
        val builder = FableSolHdrVividTimeline.Builder(
            frameRate = 10,
            diffuseWhiteNits = 203.0,
            targetNits = 400.0,
            highlightStartPercent = 90,
            policy = FableSolHdrVividTimeline.Policy(
                minimumSceneFrames = 2,
                maximumSceneFrames = 4,
                transitionFrames = 2,
                hardAverageDeltaPq = 1.0,
                hardDistributionDeltaPq = 1.0
            )
        )
        repeat(4) { builder.add(vividStatsAtPq(0.30, spread = 0.04)) }
        repeat(4) { builder.add(vividStatsAtPq(0.48, spread = 0.04)) }

        val timeline = requireNotNull(builder.result())
        assertEquals(2, timeline.sceneCount)
        assertEquals(0, timeline.hardBoundaryCount)
        val before = averageCode(timeline.payloadForFrame(3))
        val transition = averageCode(timeline.payloadForFrame(4))
        val settled = averageCode(timeline.payloadForFrame(5))
        assertTrue("transition=$transition before=$before", transition > before)
        assertTrue("settled=$settled transition=$transition", settled > transition)
    }

    @Test
    fun prefixSeiHasTheHevcHeaderPayloadTypeSizeAndTrailingBits() {
        val payload = FableSolHdrVividMetadata.payload(
            FableSolHdrVividStatistics(0, 0, 0, 0)
        )
        val nal = FableSolHdrVividHevc.prefixSeiNal(payload)
        assertEquals(0x4E, nal[0].toInt() and 0xFF)
        assertEquals(0x01, nal[1].toInt() and 0xFF)

        val rbsp = FableSolHdrVividHevc.unescapeRbsp(nal)
        assertEquals(4, rbsp[0].toInt() and 0xFF)
        assertEquals(payload.size, rbsp[1].toInt() and 0xFF)
        assertArrayEquals(payload, rbsp.copyOfRange(2, 2 + payload.size))
        assertEquals(0x80, rbsp.last().toInt() and 0xFF)

        // 全零统计会形成 00 00 00；NAL 中必须插入 0x03，解码后再恢复原 RBSP。
        assertTrue(hasSequence(nal, byteArrayOf(0x00, 0x00, 0x03)))
    }

    @Test
    fun seiIsInsertedAfterAudAndBeforeTheFirstVclNal() {
        val aud = byteArrayOf((35 shl 1).toByte(), 0x01, 0x50)
        val vcl = byteArrayOf((19 shl 1).toByte(), 0x01, 0x11, 0x22)
        val input = annexB(aud, vcl)
        val payload = FableSolHdrVividMetadata.payload(
            FableSolHdrVividStatistics(1, 2, 3, 4)
        )

        val injected = FableSolHdrVividHevc.inject(input, payload)
        assertTrue(injected.inserted)
        assertEquals(listOf(35, 39, 19), annexBNalTypes(injected.annexB))
        assertTrue(FableSolHdrVividHevc.containsHdrVivid(injected.annexB))

        val repeated = FableSolHdrVividHevc.inject(injected.annexB, payload)
        assertFalse(repeated.inserted)
        assertArrayEquals(injected.annexB, repeated.annexB)
    }

    @Test
    fun lengthPrefixedInputIsNormalisedToAnnexBForAndroidMediaMuxer() {
        val aud = byteArrayOf((35 shl 1).toByte(), 0x01, 0x50)
        val vcl = byteArrayOf((1 shl 1).toByte(), 0x01, 0x33, 0x44)
        val input = lengthPrefixed(aud, vcl)
        val payload = FableSolHdrVividMetadata.payload(
            FableSolHdrVividStatistics(1, 2, 3, 4)
        )

        val result = FableSolHdrVividHevc.inject(input, payload).annexB
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), result.copyOfRange(0, 4))
        assertEquals(listOf(35, 39, 1), annexBNalTypes(result))
    }

    @Test
    fun cuvvBoxIsThirtyBytesAndCarriesVersionOne() {
        val cuvv = FableSolHdrVividMp4.configurationBox()
        assertEquals(30, cuvv.size)
        val buffer = ByteBuffer.wrap(cuvv).order(ByteOrder.BIG_ENDIAN)
        assertEquals(30, buffer.int)
        assertEquals("cuvv", ascii(cuvv, 4))
        buffer.position(8)
        assertEquals(0x0001, buffer.short.toInt() and 0xFFFF)
        assertEquals(0x0004, buffer.short.toInt() and 0xFFFF)
        assertEquals(0x0005, buffer.short.toInt() and 0xFFFF)
        assertTrue(ByteArray(16).contentEquals(cuvv.copyOfRange(14, 30)))
    }

    @Test
    fun cuvvIsAppendedToTheHevcVisualSampleEntryAndAllAncestorsGrow() {
        val moov = sampleMoov()
        val patched = FableSolHdrVividMp4.patchMoov(moov)
        assertEquals(moov.size + FableSolHdrVividMp4.CUVV_BOX_BYTES, patched.size)
        assertEquals(patched.size, uint32(patched, 0))
        assertTrue(FableSolHdrVividMp4.containsConfiguration(patched))

        // 重复执行不再增加第二个 box。
        assertArrayEquals(patched, FableSolHdrVividMp4.patchMoov(patched))
    }

    @Test
    fun tailMoovCanGrowWithoutMovingMediaData() {
        val file = temporaryMp4(
            box("ftyp", "mp42".toByteArray()),
            box("mdat", ByteArray(32) { it.toByte() }),
            sampleMoov()
        )
        try {
            val before = file.length()
            val result = FableSolHdrVividMp4.patchInPlace(file)
            assertEquals(FableSolHdrVividMp4.Placement.MOOV_AT_END, result.placement)
            assertTrue(result.changed)
            assertEquals(before + FableSolHdrVividMp4.CUVV_BOX_BYTES, file.length())
            assertTrue(readMoov(file).let(FableSolHdrVividMp4::containsConfiguration))
        } finally {
            file.delete()
        }
    }

    @Test
    fun frontMoovConsumesReservedFreeSpaceWithoutMovingMdat() {
        val ftyp = box("ftyp", "mp42".toByteArray())
        val moov = sampleMoov()
        val free = box("free", ByteArray(120))
        val mdat = box("mdat", ByteArray(32) { it.toByte() })
        val file = temporaryMp4(ftyp, moov, free, mdat)
        try {
            val before = file.readBytes()
            val oldMdatOffset = ftyp.size + moov.size + free.size
            val result = FableSolHdrVividMp4.patchInPlace(file)
            val after = file.readBytes()

            assertEquals(
                FableSolHdrVividMp4.Placement.RESERVED_FREE_SPACE,
                result.placement
            )
            assertEquals(before.size, after.size)
            assertEquals("mdat", ascii(after, oldMdatOffset + 4))
            assertArrayEquals(
                before.copyOfRange(oldMdatOffset, before.size),
                after.copyOfRange(oldMdatOffset, after.size)
            )
            assertTrue(readMoov(file).let(FableSolHdrVividMp4::containsConfiguration))
        } finally {
            file.delete()
        }
    }

    @Test
    fun frontMoovWithoutReservedSpaceIsRejectedBeforeMediaMoves() {
        val file = temporaryMp4(
            box("ftyp", "mp42".toByteArray()),
            sampleMoov(),
            box("mdat", ByteArray(32))
        )
        try {
            val before = file.readBytes()
            try {
                FableSolHdrVividMp4.patchInPlace(file)
                fail("Expected unsafe front-moov layout to be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("reserved free space"))
            }
            assertArrayEquals(before, file.readBytes())
        } finally {
            file.delete()
        }
    }

    private fun sampleMoov(): ByteArray {
        val hvcC = box("hvcC", ByteArray(23).also { it[0] = 1 })
        val visual = box("hvc1", ByteArray(78) + hvcC)
        val stsd = box(
            "stsd",
            ByteArray(4) + ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1).array() +
                visual
        )
        val stbl = box("stbl", stsd + box("stts", ByteArray(8)))
        val minf = box("minf", stbl)
        val mdia = box("mdia", minf)
        val trak = box("trak", mdia)
        return box("moov", box("mvhd", ByteArray(16)) + trak)
    }

    private fun readMoov(file: File): ByteArray {
        val bytes = file.readBytes()
        var offset = 0
        while (offset < bytes.size) {
            val size = uint32(bytes, offset)
            if (ascii(bytes, offset + 4) == "moov") {
                return bytes.copyOfRange(offset, offset + size)
            }
            offset += size
        }
        error("No moov")
    }

    private fun temporaryMp4(vararg boxes: ByteArray): File {
        val file = File.createTempFile("fablesol-hdr-vivid-", ".mp4")
        file.writeBytes(boxes.fold(ByteArray(0)) { result, box -> result + box })
        return file
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        require(type.length == 4)
        return ByteBuffer.allocate(8 + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(8 + payload.size)
            .put(type.toByteArray(Charsets.US_ASCII))
            .put(payload)
            .array()
    }

    private fun annexB(vararg units: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            for (unit in units) {
                write(byteArrayOf(0, 0, 0, 1))
                write(unit)
            }
        }.toByteArray()

    private fun lengthPrefixed(vararg units: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            for (unit in units) {
                write(
                    ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                        .putInt(unit.size)
                        .array()
                )
                write(unit)
            }
        }.toByteArray()

    private fun annexBNalTypes(bytes: ByteArray): List<Int> {
        val types = ArrayList<Int>()
        var offset = 0
        while (offset + 6 <= bytes.size) {
            assertArrayEquals(byteArrayOf(0, 0, 0, 1), bytes.copyOfRange(offset, offset + 4))
            val start = offset + 4
            types += (bytes[start].toInt() ushr 1) and 0x3F
            var next = start + 2
            while (
                next + 3 < bytes.size &&
                !bytes.copyOfRange(next, next + 4).contentEquals(byteArrayOf(0, 0, 0, 1))
            ) {
                next++
            }
            offset = if (next + 3 < bytes.size) next else bytes.size
        }
        return types
    }

    private fun hasSequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.size > haystack.size) return false
        return (0..haystack.size - needle.size).any { start ->
            needle.indices.all { index -> haystack[start + index] == needle[index] }
        }
    }

    private fun uint32(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int

    private fun ascii(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun vividStatsAtPq(center: Double, spread: Double): FableSolHdr10PlusStats {
        val samples = DoubleArray(256) { index ->
            val unit = index / 255.0
            val pq = (center - spread / 2.0 + spread * unit).coerceIn(0.0, 1.0)
            FableSolExportHdr10PlusMetadata.pqToLinear(pq)
        }
        return FableSolHdr10PlusStats.of(
            FableSolExportHdr10PlusHistogram.of(samples),
            fractionBrightPixels = 0.1,
            proxyAverageLuminance = samples.average()
        )
    }

    private fun averageCode(payload: ByteArray): Int {
        val bits = PayloadBitReader(payload, offsetBytes = 5)
        bits.read(8)
        bits.read(12)
        return bits.read(12)
    }

    private class PayloadBitReader(
        private val bytes: ByteArray,
        offsetBytes: Int
    ) {

        private var bit = offsetBytes * 8

        fun read(count: Int): Int {
            var value = 0
            repeat(count) {
                val byte = bytes[bit ushr 3].toInt() and 0xFF
                value = (value shl 1) or ((byte ushr (7 - (bit and 7))) and 1)
                bit++
            }
            return value
        }
    }
}
