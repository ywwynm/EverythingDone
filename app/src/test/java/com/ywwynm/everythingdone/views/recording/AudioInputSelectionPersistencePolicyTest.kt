package com.ywwynm.everythingdone.views.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioInputSelectionPersistencePolicyTest {

    @Test
    fun `system modes are not committed before authorization and configuration succeed`() {
        assertNull(
            AudioInputSelectionPersistencePolicy.modeToCommitWhenPreparationStarts(
                AudioInputMode.SYSTEM
            )
        )
        assertNull(
            AudioInputSelectionPersistencePolicy.modeToCommitWhenPreparationStarts(
                AudioInputMode.SYSTEM_AND_MICROPHONE
            )
        )
    }

    @Test
    fun `successful configuration commits selected mode across dialogs`() {
        assertEquals(
            AudioInputMode.SYSTEM,
            AudioInputSelectionPersistencePolicy.modeToCommitAfterSuccess(AudioInputMode.SYSTEM)
        )
        assertEquals(
            AudioInputMode.MICROPHONE,
            AudioInputSelectionPersistencePolicy.modeToCommitWhenPreparationStarts(
                AudioInputMode.MICROPHONE
            )
        )
    }

    @Test
    fun `fallback always commits microphone`() {
        assertEquals(
            AudioInputMode.MICROPHONE,
            AudioInputSelectionPersistencePolicy.modeToCommitOnFallback()
        )
    }

    @Test
    fun `legacy unconfirmed system preference is migrated back to microphone`() {
        assertEquals(
            AudioInputMode.MICROPHONE,
            AudioInputSelectionPersistencePolicy.restore(
                storedMode = AudioInputMode.SYSTEM,
                systemModeConfirmed = false
            )
        )
        assertEquals(
            AudioInputMode.SYSTEM,
            AudioInputSelectionPersistencePolicy.restore(
                storedMode = AudioInputMode.SYSTEM,
                systemModeConfirmed = true
            )
        )
    }
}
