package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolSevenStateMachineTest {

    @Test
    fun liftHasNoClockLimitAndRearmsOnlyAfterEvidenceRelease() {
        val fixture = Fixture()
        fixture.establishGroove()
        fixture.drive(12.0, grade = 0.54, lift = 0.99)
        assertEquals(FableSolVisualState.LIFT, fixture.machine.currentState())

        fixture.drive(2.5, grade = 0.52, lift = 0.0)
        assertEquals(FableSolVisualState.GROOVE, fixture.machine.currentState())
        fixture.drive(0.5, grade = 0.54, lift = 0.99)
        assertEquals(FableSolVisualState.LIFT, fixture.machine.currentState())
    }

    @Test
    fun climaxHasNoClockLimitAndCanRearmAfterARealRelease() {
        val fixture = Fixture()
        fixture.establishPeak()
        fixture.drive(12.0, grade = 0.96, climax = 1.0)
        assertEquals(FableSolVisualState.CLIMAX, fixture.machine.currentState())

        fixture.drive(4.0, grade = 0.90, climax = 0.0)
        assertNotEquals(FableSolVisualState.CLIMAX, fixture.machine.currentState())
        fixture.drive(1.0, grade = 0.96, climax = 1.0)
        assertEquals(FableSolVisualState.CLIMAX, fixture.machine.currentState())
    }

    @Test
    fun nonDropClimaxWaitsForAStablePeakFoothold() {
        val fixture = Fixture()
        fixture.machine.forceState(FableSolVisualState.PEAK, 0.0)
        fixture.t = 0.0
        fixture.drive(0.60, grade = 0.98, climax = 1.0)
        assertEquals(FableSolVisualState.PEAK, fixture.machine.currentState())
        fixture.drive(0.35, grade = 0.98, climax = 1.0)
        assertEquals(FableSolVisualState.CLIMAX, fixture.machine.currentState())
    }

    @Test
    fun weakDropIsOnlyALicenceAndCannotManufactureClimax() {
        val fixture = Fixture()
        fixture.establishGroove()
        fixture.machine.notifyDrop(0.99)
        fixture.drive(0.1, grade = 0.24, climax = 0.10)
        assertNotEquals(FableSolVisualState.CLIMAX, fixture.machine.currentState())
    }

    @Test
    fun flowPolicyMatchesTheUncompressedDesignAnchors() {
        // 2026-07-21：低端整体抬高（安静段原本 176 秒才穿屏一次，读作"完全不动"），
        // 高端几乎不动——K=1.0 只从 190 抬到 200dp/s。
        val anchors = arrayOf(
            0.0 to 0.0,
            0.25 to 62.0,
            0.50 to 88.0,
            0.70 to 126.0,
            0.85 to 166.0,
            1.0 to 200.0
        )
        for ((drive, expected) in anchors) {
            assertEquals(expected, FableSolFlowPolicy.targetFlowDps(drive), 1e-9)
        }
        assertTrue(FableSolFlowPolicy.targetFlowDps(0.01) > 0.0)
        assertTrue(FableSolFlowPolicy.targetFlowDps(0.02) >
            FableSolFlowPolicy.targetFlowDps(0.01))
        // 安静段（K≈0.1~0.2）必须在 10 秒内穿屏一次，否则读作静止画面。
        for (drive in doubleArrayOf(0.10, 0.15, 0.20)) {
            assertTrue(FableSolFlowPolicy.targetFlowDps(drive) > 320.0 / 10.0)
        }
    }

    private class Fixture {
        val machine = FableSolSevenStateMachine()
        private val frame = FableSolPerceptualFrame()
        private val evidence = FableSolStateEvidence()
        var t = 0.0

        fun establishGroove() {
            drive(13.0, grade = 0.52)
            assertEquals(FableSolVisualState.GROOVE, machine.currentState())
        }

        fun establishPeak() {
            establishGroove()
            drive(1.7, grade = 0.92)
            assertEquals(FableSolVisualState.PEAK, machine.currentState())
        }

        fun drive(seconds: Double, grade: Double, lift: Double = 0.0, climax: Double = 0.0) {
            repeat((seconds * FPS).toInt()) {
                frame.t = t
                frame.silent = false
                evidence.gradeDrive01 = grade
                evidence.liftScore01 = lift
                evidence.climaxScore01 = climax
                machine.step(frame, evidence)
                t += DT
            }
        }
    }

    companion object {
        private const val FPS = 60
        private const val DT = 1.0 / FPS
    }
}
