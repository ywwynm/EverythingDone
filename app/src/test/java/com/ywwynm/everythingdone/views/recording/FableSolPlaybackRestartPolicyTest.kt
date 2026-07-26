package com.ywwynm.everythingdone.views.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolPlaybackRestartPolicyTest {

    @Test
    fun seekAfterNaturalCompletionMustCreateANewDecoderThread() {
        assertTrue(
            FableSolPlaybackRestartPolicy.shouldRestartForSeek(
                completedNaturally = true
            )
        )
    }

    @Test
    fun seekDuringActivePlaybackKeepsTheCurrentDecoderThread() {
        assertFalse(
            FableSolPlaybackRestartPolicy.shouldRestartForSeek(
                completedNaturally = false
            )
        )
    }
}
