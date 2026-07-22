package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Test

class FableSolAnalysisBatchInboxTest {

    @Test
    fun delayedEventCannotBeInsertedIntoAnEarlierObservedBatch() {
        val batches = listOf(
            FableSolAnalysisBatch(listOf(frame(1.0), frame(2.0)), emptyList()),
            FableSolAnalysisBatch(
                listOf(frame(3.0)),
                listOf(FableSolEvent.Drop(t = 1.5, confidence01 = 1.0))
            )
        )
        val order = ArrayList<String>()

        FableSolAnalysisBatchConsumer.consume(
            batches,
            { order.add("f${it.t}") },
            { order.add("e${it.t}") }
        )

        assertEquals(listOf("f1.0", "f2.0", "e1.5", "f3.0"), order)
    }

    @Test
    fun framePrecedesEventAtTheSameTimeWithinItsObservedBatch() {
        val batches = listOf(
            FableSolAnalysisBatch(
                listOf(frame(3.0)),
                listOf(FableSolEvent.Drop(t = 3.0, confidence01 = 1.0))
            )
        )
        val order = ArrayList<String>()

        FableSolAnalysisBatchConsumer.consume(
            batches,
            { order.add("f${it.t}") },
            { order.add("e${it.t}") }
        )

        assertEquals(listOf("f3.0", "e3.0"), order)
    }

    private fun frame(t: Double) = FableSolFeatureFrame(
        t = t,
        loudness01 = 0.0,
        bandLow = 0.0,
        bandMid = 0.0,
        bandHigh = 0.0,
        relLow = 1.0 / 3.0,
        relMid = 1.0 / 3.0,
        relHigh = 1.0 / 3.0,
        centroid01 = 0.5,
        spectralTilt01 = 0.5,
        flatness01 = 0.0,
        percussive01 = 0.0,
        punch01 = 0.0,
        stereoWidth01 = 0.0,
        pan01 = 0.5,
        onsetEnv = 0.0,
        flow01 = 0.0,
        activity01 = 0.0,
        loudDb = -120.0,
        floorDb = -120.0,
        isSilent = true,
        tempoBpm = 0.0,
        beatPhase01 = 0.0,
        beatConf01 = 0.0
    )
}
