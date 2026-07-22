package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolFeatureMapperStateIntegrationTest {

    @Test
    fun authoritativeFrameOwnsLevelFlowAndSevenStateVisualChannels() {
        val params = FableSolParams()
        val mapper = FableSolFeatureMapper(params)
        val sim = FableSolSimulation(params)
        repeat(600) { index ->
            mapper.applyFrame(sim, frame(
                t = index / 60.0,
                water = 0.80,
                kinetic = 0.88,
                grade = 0.94,
                arousal = 0.82
            ))
        }

        assertEquals(FableSolVisualState.PEAK, mapper.currentVisualState())
        assertEquals("PEAK", sim.visualState)
        assertEquals("层波叠浪", sim.visualStateLabel)
        assertEquals(
            FableSolContinuousStateChannels.waterLevelGoalDp(0.8),
            sim.visualLevelTargetDp, 1e-12
        )
        assertEquals(sim.visualLevelDp, sim.layers[0].swellTargetDp, 0.0)
        assertTrue(sim.layers[8].swellTargetDp < sim.layers[0].swellTargetDp)
        assertTrue(sim.flow01 > 0.85)
        assertTrue(sim.visualTargetDps > 150.0)
        assertTrue(sim.visualWaveScale > 1.5)
        assertTrue(sim.layerSpread > 1.0)
        assertEquals(1.0, sim.resonance01, 0.0)
    }

    @Test
    fun stateDoesNotRewardWaterLevelAndLegacyRegisterCannotOverwriteIt() {
        val grooveParams = FableSolParams()
        val peakParams = FableSolParams()
        val grooveMapper = FableSolFeatureMapper(grooveParams)
        val peakMapper = FableSolFeatureMapper(peakParams)
        val grooveSim = FableSolSimulation(grooveParams)
        val peakSim = FableSolSimulation(peakParams)
        repeat(600) { index ->
            val t = index / 60.0
            grooveMapper.applyFrame(grooveSim, frame(t, 0.72, 0.58, 0.52, 0.52))
            peakMapper.applyFrame(peakSim, frame(t, 0.72, 0.82, 0.94, 0.82))
        }

        assertEquals(FableSolVisualState.GROOVE, grooveMapper.currentVisualState())
        assertEquals(FableSolVisualState.PEAK, peakMapper.currentVisualState())
        assertEquals(grooveSim.visualLevelDp, peakSim.visualLevelDp, 0.0)
        assertEquals(grooveSim.visualLevelDp, grooveSim.layers[0].swellTargetDp, 0.0)
        assertEquals(peakSim.visualLevelDp, peakSim.layers[0].swellTargetDp, 0.0)
        assertTrue(peakMapper.currentWaveScale() > grooveMapper.currentWaveScale())
    }

    @Test
    fun legacyDiagnosticLoudnessCannotLeakIntoVisualShapeOrWaterLevel() {
        val quietDiagnosticParams = FableSolParams()
        val loudDiagnosticParams = FableSolParams()
        val quietDiagnosticMapper = FableSolFeatureMapper(quietDiagnosticParams)
        val loudDiagnosticMapper = FableSolFeatureMapper(loudDiagnosticParams)
        val quietDiagnosticSim = FableSolSimulation(quietDiagnosticParams)
        val loudDiagnosticSim = FableSolSimulation(loudDiagnosticParams)
        repeat(360) { index ->
            val t = index / 60.0
            quietDiagnosticMapper.applyFrame(
                quietDiagnosticSim,
                frame(t, 0.68, 0.66, 0.60, 0.58, diagnosticLoudness = 0.02)
            )
            loudDiagnosticMapper.applyFrame(
                loudDiagnosticSim,
                frame(t, 0.68, 0.66, 0.60, 0.58, diagnosticLoudness = 0.98)
            )
        }

        assertEquals(quietDiagnosticSim.visualLevelDp, loudDiagnosticSim.visualLevelDp, 0.0)
        assertEquals(
            quietDiagnosticSim.layers[0].heroTargetDp,
            loudDiagnosticSim.layers[0].heroTargetDp,
            0.0
        )
        assertEquals(
            quietDiagnosticSim.layers[7].heroTargetDp,
            loudDiagnosticSim.layers[7].heroTargetDp,
            0.0
        )
    }

    @Test
    fun qualifiedCausalArrivalTriggersTheSimulationGrandWave() {
        val params = FableSolParams()
        val mapper = FableSolFeatureMapper(params)
        val sim = FableSolSimulation(params)
        repeat(45) { index ->
            mapper.applyFrame(sim, frame(
                t = index / 60.0,
                water = 0.90,
                kinetic = 0.90,
                grade = 0.96,
                arousal = 0.88,
                context = 0.68,
                rising = 0.72
            ))
        }

        assertEquals(FableSolVisualState.PEAK, mapper.currentVisualState())
        assertEquals(1, sim.grandWave.triggerCount)
        assertTrue(sim.grandWave.active)
    }

    @Test
    fun loudContextWithoutPhysicalAttackDoesNotTriggerTheSimulationGrandWave() {
        val params = FableSolParams()
        val mapper = FableSolFeatureMapper(params)
        val sim = FableSolSimulation(params)
        repeat(180) { index ->
            mapper.applyFrame(sim, frame(
                t = index / 60.0,
                water = 0.90,
                kinetic = 0.93,
                grade = 0.96,
                arousal = 0.88,
                context = 0.82,
                rising = 0.0
            ))
        }

        assertEquals(FableSolVisualState.PEAK, mapper.currentVisualState())
        assertEquals(0, sim.grandWave.triggerCount)
    }

    @Test
    fun sectionBoundaryOnlyAuthorizesAQualifiedPresentPhrase() {
        val params = FableSolParams()
        val mapper = FableSolFeatureMapper(params)
        val sim = FableSolSimulation(params)
        repeat(180) { index ->
            mapper.applyFrame(sim, frame(
                t = index / 60.0,
                water = 0.86,
                kinetic = 0.82,
                grade = 0.96,
                arousal = 0.88
            ))
        }
        assertEquals(FableSolVisualState.PEAK, mapper.currentVisualState())
        assertEquals(0, sim.grandWave.triggerCount)

        mapper.applySection(
            sim,
            FableSolEvent.Section(
                t = 3.0,
                magnitude01 = 0.90,
                energy01 = 0.35,
                brightness01 = 0.50,
                surge = true
            )
        )
        assertEquals(0, sim.grandWave.triggerCount)

        repeat(24) { index ->
            mapper.applyFrame(sim, frame(
                t = 3.0 + index / 60.0,
                water = 0.83,
                kinetic = 0.81,
                grade = 0.96,
                arousal = 0.88,
                context = 0.22,
                rising = 0.0
            ))
        }
        assertEquals(1, sim.grandWave.triggerCount)
    }

    @Test
    fun structuralDropFeedsBothClimaxAndGrandWaveGates() {
        val params = FableSolParams()
        val mapper = FableSolFeatureMapper(params)
        val sim = FableSolSimulation(params)
        repeat(90) { index ->
            mapper.applyFrame(sim, frame(
                t = index / 60.0,
                water = 0.86,
                kinetic = 0.88,
                grade = 0.96,
                arousal = 0.88
            ))
        }
        assertEquals(FableSolVisualState.PEAK, mapper.currentVisualState())
        assertEquals(0, sim.grandWave.triggerCount)

        mapper.applyStructuralEvent(sim, FableSolEvent.Drop(1.5, 0.98))
        mapper.applyFrame(sim, frame(
            t = 1.5,
            water = 0.90,
            kinetic = 0.90,
            grade = 0.98,
            arousal = 0.92,
            climax = 0.98
        ))
        assertEquals(FableSolVisualState.CLIMAX, mapper.currentVisualState())
        assertEquals(1, sim.grandWave.triggerCount)
    }

    @Test
    fun displayPeakCannotRaiseRawGateStateOrAuthorizeGrandWave() {
        val params = FableSolParams()
        val mapper = FableSolFeatureMapper(params)
        val sim = FableSolSimulation(params)
        repeat(600) { index ->
            mapper.applyFrame(
                sim,
                frame(
                    t = index / 60.0,
                    water = 0.68,
                    kinetic = 0.62,
                    grade = 0.50,
                    arousal = 0.52,
                    rising = 0.85,
                    displayWater = 0.96,
                    displayGrade = 0.98,
                    displayLift = 0.0,
                    displayClimax = 0.0
                )
            )
        }

        assertEquals(FableSolVisualState.PEAK, mapper.currentVisualState())
        assertTrue(
            "gateState=${mapper.currentGateState()}",
            mapper.currentGateState() != FableSolVisualState.PEAK &&
                mapper.currentGateState() != FableSolVisualState.CLIMAX
        )
        assertEquals(0, sim.grandWave.triggerCount)
    }

    @Test
    fun presentationLevelReadsDisplayWaterWithoutBandDilution() {
        val params = FableSolParams()
        val mapper = FableSolFeatureMapper(params)
        val sim = FableSolSimulation(params)
        repeat(360) { index ->
            mapper.applyFrame(
                sim,
                frame(
                    t = index / 60.0,
                    water = 0.95,
                    kinetic = 0.60,
                    grade = 0.58,
                    arousal = 0.52,
                    displayWater = 0.45,
                    displayGrade = 0.58
                )
            )
        }

        assertEquals(
            FableSolContinuousStateChannels.waterLevelGoalDp(0.45),
            sim.visualLevelTargetDp,
            1e-12
        )
    }

    private fun frame(
        t: Double,
        water: Double,
        kinetic: Double,
        grade: Double,
        arousal: Double,
        context: Double = 0.0,
        rising: Double = 0.0,
        climax: Double = 0.0,
        diagnosticLoudness: Double = water,
        displayWater: Double = -1.0,
        displayGrade: Double = -1.0,
        displayLift: Double = -1.0,
        displayClimax: Double = -1.0
    ) = FableSolFeatureFrame(
        t = t,
        loudness01 = diagnosticLoudness,
        bandLow = water * 0.90,
        bandMid = water,
        bandHigh = water * 0.86,
        relLow = 0.34,
        relMid = 0.36,
        relHigh = 0.30,
        centroid01 = 0.52,
        spectralTilt01 = 0.50,
        flatness01 = 0.20,
        percussive01 = 0.74,
        punch01 = 0.82,
        stereoWidth01 = 0.45,
        pan01 = 0.50,
        onsetEnv = 0.0,
        flow01 = kinetic,
        activity01 = kinetic,
        loudDb = -12.0,
        floorDb = -60.0,
        isSilent = false,
        tempoBpm = 126.0,
        beatPhase01 = 0.0,
        beatConf01 = 0.80,
        arousal01 = arousal,
        waterDrive01 = water,
        speed01 = kinetic,
        kineticDrive01 = kinetic,
        motionContextBoost01 = context,
        percussiveMotion01 = 0.78,
        vocalMotion01 = 0.12,
        harmonicMotion01 = 0.80,
        grooveMotion01 = kinetic,
        intensityDrive01 = 0.72,
        musicArousal01 = arousal,
        punchLu01 = 0.80,
        energy01 = water,
        energyRising01 = rising,
        buildUp01 = rising,
        gradeDrive01 = grade,
        liftScore01 = 0.0,
        climaxScore01 = climax,
        gradeAbsolute01 = grade,
        novelty01 = context,
        displayWaterDrive01 = displayWater,
        displayGradeDrive01 = displayGrade,
        displayLiftScore01 = displayLift,
        displayClimaxScore01 = displayClimax
    )
}
