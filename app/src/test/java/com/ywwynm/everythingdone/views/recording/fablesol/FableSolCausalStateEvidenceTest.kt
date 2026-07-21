package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolCausalStateEvidenceTest {

    @Test
    fun sustainedFullMixGradeIsMonotone() {
        val grades = doubleArrayOf(0.25, 0.45, 0.65, 0.85).map { settle(it).gradeDrive01 }
        for (index in 1 until grades.size) {
            assertTrue("grades=$grades", grades[index] > grades[index - 1])
            assertTrue("grades=$grades", grades[index] - grades[index - 1] > 0.20)
        }
    }

    @Test
    fun fastDryVocalSoloCannotPromoteTheSustainedGrade() {
        val nonVocal = settle(
            level = 0.50,
            vocal = 0.05,
            frames = 240,
            water = 0.68,
            intensity = 0.62,
            kinetic = 0.95,
            percussive = 0.28,
            harmonic = 0.32,
            groove = 0.35,
            arousal = 0.38,
            energy = 0.62,
            punch = 0.30,
            lowShare = 0.35
        )
        val vocal = settle(
            level = 0.50,
            vocal = 0.95,
            frames = 240,
            water = 0.68,
            intensity = 0.62,
            kinetic = 0.95,
            percussive = 0.28,
            harmonic = 0.32,
            groove = 0.35,
            arousal = 0.38,
            energy = 0.62,
            punch = 0.30,
            lowShare = 0.35
        )

        assertTrue(vocal.vocalSoloPenalty01 > 0.90)
        assertTrue("nonVocal=${nonVocal.gradeDrive01}, vocal=${vocal.gradeDrive01}",
            vocal.gradeDrive01 < nonVocal.gradeDrive01 - 0.12)
    }

    @Test
    fun programmeNewHighRemainsReachableAtProductionHopRate() {
        val rate = 93.75
        val evidence = FableSolCausalStateEvidence(rate)
        val frame = FableSolPerceptualFrame()
        val output = FableSolStateEvidence()
        var maximum = 0.0
        val split = (6.0 * rate).toInt()
        repeat((12.0 * rate).toInt()) { index ->
            val level = if (index < split) 0.46 else 0.92
            fill(frame, index / rate, level, vocal = 0.0)
            frame.energyRising01 = 0.0
            frame.buildUp01 = 0.0
            frame.positiveNovelty01 = 0.0
            frame.punch01 = 0.0
            evidence.step(frame, output)
            if (index >= split) maximum = maxOf(maximum, output.climaxScore01)
        }
        assertTrue("max climax=$maximum", maximum > 0.60)
    }

    @Test
    fun resetReplaysTheSameScalarTrajectory() {
        val evidence = FableSolCausalStateEvidence(RATE)
        val frame = FableSolPerceptualFrame()
        val output = FableSolStateEvidence()
        val first = DoubleArray(160)
        val second = DoubleArray(160)

        fun replay(destination: DoubleArray) {
            repeat(destination.size) { index ->
                val q = 0.30 + 0.55 * minOf(index / 100.0, 1.0)
                fill(frame, index / RATE, q, vocal = 0.0)
                frame.energyRising01 = if (index in 50 until 65) 0.90 else 0.0
                frame.buildUp01 = if (index in 50 until 65) 0.85 else 0.0
                frame.positiveNovelty01 = if (index == 50 || index == 110) 0.75 else 0.0
                evidence.step(frame, output)
                destination[index] = output.gradeDrive01 + 2.0 * output.liftScore01 +
                    4.0 * output.climaxScore01
            }
        }

        replay(first)
        evidence.reset()
        replay(second)
        assertEquals(first.size, second.size)
        for (index in first.indices) assertEquals(first[index], second[index], 0.0)
    }

    @Test
    fun scalarTrajectoryMatchesThePythonReference() {
        val evidence = FableSolCausalStateEvidence(RATE)
        val frame = FableSolPerceptualFrame()
        val output = FableSolStateEvidence()
        repeat(121) { index ->
            val level = when {
                index < 40 -> 0.42
                index < 90 -> 0.78
                else -> 0.58
            }
            fill(frame, index / RATE, level, vocal = 0.10)
            frame.energyRising01 = if (frame.t >= 2.0 && frame.t < 2.5) 0.70 else 0.0
            frame.buildUp01 = if (frame.t >= 2.0 && frame.t < 2.5) 0.60 else 0.0
            frame.positiveNovelty01 = if (index == 40) 0.80 else 0.0
            evidence.step(frame, output)
        }

        assertEquals(0.624350654470323, output.gradeDrive01, 1e-12)
        assertEquals(0.719999999985946, output.liftScore01, 1e-12)
        assertEquals(0.0395904705806154, output.climaxScore01, 1e-12)
        assertEquals(0.589350654470323, output.gradeAbsolute01, 1e-12)
        assertEquals(0.0, output.gradeContext01, 1e-12)
        assertEquals(0.0, output.vocalSoloPenalty01, 1e-12)
    }

    private fun settle(
        level: Double,
        vocal: Double = 0.0,
        frames: Int = 200,
        water: Double = level,
        intensity: Double = level,
        kinetic: Double = level,
        percussive: Double = level,
        harmonic: Double = level,
        groove: Double = level,
        arousal: Double = level,
        energy: Double = level,
        punch: Double = 0.5 * level,
        lowShare: Double = level
    ): FableSolStateEvidence {
        val evidence = FableSolCausalStateEvidence(RATE)
        val frame = FableSolPerceptualFrame()
        val output = FableSolStateEvidence()
        repeat(frames) { index ->
            fill(frame, index / RATE, level, vocal)
            frame.waterDrive01 = water
            frame.intensityDrive01 = intensity
            frame.kineticDrive01 = kinetic
            frame.percussiveMotion01 = percussive
            frame.harmonicMotion01 = harmonic
            frame.grooveMotion01 = groove
            frame.musicArousal01 = arousal
            frame.energy01 = energy
            frame.punch01 = punch
            frame.punchLu01 = 0.3 * level
            frame.lowShare01 = lowShare
            evidence.step(frame, output)
        }
        return output
    }

    private fun fill(
        frame: FableSolPerceptualFrame,
        t: Double,
        level: Double,
        vocal: Double
    ) {
        frame.t = t
        frame.silent = false
        frame.waterDrive01 = level
        frame.intensityDrive01 = level
        frame.kineticDrive01 = level
        frame.percussiveMotion01 = level
        frame.vocalMotion01 = vocal
        frame.harmonicMotion01 = level
        frame.grooveMotion01 = level
        frame.musicArousal01 = level
        frame.energy01 = level
        frame.energyRising01 = 0.0
        frame.buildUp01 = 0.0
        frame.positiveNovelty01 = 0.0
        frame.punch01 = 0.5 * level
        frame.punchLu01 = 0.3 * level
        frame.lowShare01 = level
        frame.domainGradeTrim01 = 0.0
    }

    companion object {
        private const val RATE = 20.0
    }
}
