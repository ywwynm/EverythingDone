package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class FableSolContinuousSurfaceTest {

    @Test
    fun continuousSurfaceIsTheDefaultAndroidPath() {
        assertEquals(1.0, FableSolParams().get("surface2d_on"), 0.0)
    }

    @Test
    fun continuousRibbonsAreBatchedByNineLayerDepthInterval() {
        assertEquals(12, FableSolContinuousSurface.ROWS_PER_LAYER)
        assertEquals(97, FableSolContinuousSurface.Z_ROWS)
        assertEquals(
            (FableSolSpec.N_LAYERS - 1) * FableSolContinuousSurface.ROWS_PER_LAYER + 1,
            FableSolContinuousSurface.Z_ROWS
        )
        assertEquals(8, FableSolContinuousSurface.RENDER_GROUPS)
        assertTrue(FableSolContinuousSurface.RENDER_GROUPS <
            FableSolContinuousSurface.Z_ROWS - 1)
    }

    @Test
    fun anchorRowsPreserveEveryLayersOwnContour() {
        val params = FableSolParams()
        val surface = FableSolContinuousSurface(params)
        val layers = Array(FableSolSpec.N_LAYERS) { i ->
            DoubleArray(FableSolSpec.N_POINTS) { x ->
                (1.0 + 0.35 * i) * sin((1 + i % 3) *
                    (x.toDouble() / (FableSolSpec.N_POINTS - 1) * 2.0 * PI - PI) + i * 0.71)
            }
        }
        val directional = Array(FableSolContinuousSurface.Z_ROWS) {
            DoubleArray(FableSolSpec.N_POINTS)
        }

        val field = surface.composeLayerField(layers, directional)

        for (i in layers.indices) {
            val mean = layers[i].average()
            val row = i * FableSolContinuousSurface.ROWS_PER_LAYER
            for (x in layers[i].indices) {
                assertEquals(layers[i][x] - mean, field[row][x], 1e-9)
            }
        }
    }

    @Test
    fun commonHorizontalWaveIsRemovedFromDirectionalDepthField() {
        val surface = FableSolContinuousSurface(FableSolParams())
        val layers = Array(FableSolSpec.N_LAYERS) { i ->
            DoubleArray(FableSolSpec.N_POINTS) { x -> sin(x * 0.07 + i) }
        }
        val transverse = Array(FableSolContinuousSurface.Z_ROWS) { r ->
            DoubleArray(FableSolSpec.N_POINTS) { x ->
                sin(2.0 * PI * r / (FableSolContinuousSurface.Z_ROWS - 1)) * cos(x * 0.05)
            }
        }
        val baseline = surface.composeLayerField(layers, transverse).map { it.copyOf() }
        val withCommon = Array(FableSolContinuousSurface.Z_ROWS) { r ->
            DoubleArray(FableSolSpec.N_POINTS) { x -> transverse[r][x] + 6.0 * cos(x * 0.03) }
        }

        val actual = surface.composeLayerField(layers, withCommon)

        for (r in actual.indices) for (x in actual[r].indices) {
            assertEquals(baseline[r][x], actual[r][x], 1e-9)
        }
    }

    @Test
    fun sampledFieldContainsFiniteLongitudinalMotionAndPitchLag() {
        val params = FableSolParams()
        val sim = FableSolSimulation(params)
        sim.setPitch(25.0, snap = true)
        repeat(120) { sim.update(1.0 / 60.0) }
        sim.setPitch(-20.0)
        repeat(20) { sim.update(1.0 / 60.0) }

        val sample = sim.surface2d.sample(sim)
        var maxSlopeZ = 0.0
        for (r in 0 until FableSolContinuousSurface.Z_ROWS) {
            for (x in 0 until FableSolSpec.N_POINTS) {
                assertTrue(sample.eta[r][x].isFinite())
                assertTrue(sample.orbitX[r][x].isFinite())
                assertTrue(sample.orbitZ[r][x].isFinite())
                maxSlopeZ = maxOf(maxSlopeZ, abs(sample.slopeZ[r][x]))
            }
        }
        assertTrue("maxSlopeZ=$maxSlopeZ", maxSlopeZ > 0.05)
        assertTrue(abs(sim.surface2d.pitchEffRad - Math.toRadians(sim.motionPitchDeg)) > 1e-3)
    }

    @Test
    fun physicalPitchKeepsChangingBeyondTheOldFiftyFiveDegreeLimit() {
        val sim = FableSolSimulation(FableSolParams())

        sim.setPitch(55.0, snap = true)
        val at55 = sim.motionPitchDeg
        sim.setPitch(70.0, snap = true)
        val at70 = sim.motionPitchDeg
        sim.setPitch(90.0, snap = true)
        val at90 = sim.motionPitchDeg

        assertTrue("55=$at55 70=$at70", at70 > at55)
        assertTrue("70=$at70 90=$at90", at90 > at70)
        assertEquals(90.0, sim.pitchDeg, 0.0)
    }

    @Test
    fun pitchSpringInitiallyKeepsMovingForwardBeyondTheOldLimit() {
        val sim = FableSolSimulation(FableSolParams())
        sim.setPitch(55.0, snap = true)
        var previous = sim.surface2d.pitchEffRad

        sim.setPitch(90.0)
        repeat(12) {
            sim.update(FableSolSpec.PHYSICS_DT)
            val actual = sim.surface2d.pitchEffRad
            assertTrue("step=$it previous=$previous actual=$actual", actual > previous)
            previous = actual
        }
    }

    @Test
    fun farSurfaceProjectionCoversBothContainerSidesAcrossRollAngles() {
        val sim = FableSolSimulation(FableSolParams())
        for (width in doubleArrayOf(276.0, 320.0)) {
            sim.setContainerWidthDp(width)
            for (angle in doubleArrayOf(-90.0, -45.0, 0.0, 45.0, 90.0, 180.0)) {
                sim.setTilt(angle, snap = true)
                val info = sim.continuousRenderInfo()
                val selectedHalf = minOf(
                    abs(sim.uGrid[info.i0]),
                    abs(sim.uGrid[info.i1 - 1])
                )
                // 最远行的弱透视最多收缩到 1/(1+0.16*1.1)，轨道又可向内偏移 10dp。
                val guaranteedProjectedHalf = (selectedHalf - 10.0) / (1.0 + 0.16 * 1.1)
                val requiredHalf = sim.geometrySpan() / 2.0
                assertTrue(
                    "width=$width angle=$angle projected=$guaranteedProjectedHalf required=$requiredHalf",
                    guaranteedProjectedHalf >= requiredHalf
                )
            }
        }
    }

    @Test
    fun tiltKeepsContinuousRenderColumnBudgetBounded() {
        val sim = FableSolSimulation(FableSolParams())
        sim.setContainerWidthDp(276.0)
        val renderedCounts = doubleArrayOf(0.0, 30.0, 45.0, 60.0, 90.0).map { angle ->
            sim.setTilt(angle, snap = true)
            val info = sim.continuousRenderInfo()
            sim.continuousRenderColumnCount(info.i1 - info.i0)
        }
        assertTrue("counts=$renderedCounts", renderedCounts.max() <= 120)
        assertTrue("counts=$renderedCounts", renderedCounts.max() - renderedCounts.min() <= 10)

        val i0 = 11
        val raw = 190
        val rendered = sim.continuousRenderColumnCount(raw)
        assertEquals(i0.toDouble(),
            sim.continuousRenderSourcePosition(i0, raw, rendered, 0), 0.0)
        assertEquals((i0 + raw - 1).toDouble(),
            sim.continuousRenderSourcePosition(i0, raw, rendered, rendered - 1), 0.0)
    }

    @Test
    fun continuousTiltDoesNotRebuildBoundaryProfilesAboveThirtyHertz() {
        val sim = FableSolSimulation(FableSolParams())
        repeat(120) { frame ->
            sim.setTilt(42.0 * sin(frame * 0.037))
            sim.update(1.0 / 60.0)
        }
        val revisions = sim.boundaryProfileRevisionForTest()
        assertTrue("revisions=$revisions", revisions in 30..65)
    }

    @Test
    fun continuousTiltCapsBoundaryProfileWorkPerDisplayFrame() {
        val sim = FableSolSimulation(FableSolParams())
        sim.update(1.0 / 60.0) // 首次初始化允许一次性建立全部九层

        var rebuiltLayers = 0
        repeat(12) { frame ->
            sim.setTilt(35.0 * sin(frame * 0.19))
            sim.update(1.0 / 60.0)
            rebuiltLayers += sim.perfBoundaryLayers
            assertTrue("frame=$frame layers=${sim.perfBoundaryLayers}", sim.perfBoundaryLayers <= 5)
        }

        assertTrue("rebuiltLayers=$rebuiltLayers", rebuiltLayers > 0)
    }

    @Test
    fun boundaryProfilesRemainExactlySymmetricAfterAmortizedRebuild() {
        val sim = FableSolSimulation(FableSolParams())
        sim.update(1.0 / 60.0)
        repeat(8) { frame ->
            sim.setTilt(38.0 * sin(frame * 0.21))
            sim.update(1.0 / 60.0)
        }

        for (layer in 0 until FableSolSpec.N_LAYERS) {
            for (point in 0 until FableSolSpec.N_POINTS / 2) {
                assertArrayEquals(
                    sim.boundaryProfileValueForTest(layer, point),
                    sim.boundaryProfileValueForTest(layer, FableSolSpec.N_POINTS - 1 - point),
                    0.0
                )
            }
        }
    }
}
