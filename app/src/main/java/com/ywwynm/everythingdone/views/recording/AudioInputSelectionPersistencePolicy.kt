package com.ywwynm.everythingdone.views.recording

/** 将“用户点了来源”和“该来源已经可以使用”分开，防止授权中断污染下次 Dialog。 */
object AudioInputSelectionPersistencePolicy {

    fun modeToCommitWhenPreparationStarts(mode: AudioInputMode): AudioInputMode? =
        mode.takeUnless(AudioInputMode::requiresSystemAudio)

    fun modeToCommitAfterSuccess(mode: AudioInputMode): AudioInputMode = mode

    fun modeToCommitOnFallback(): AudioInputMode = AudioInputMode.MICROPHONE

    fun restore(
        storedMode: AudioInputMode,
        systemModeConfirmed: Boolean
    ): AudioInputMode = if (storedMode.requiresSystemAudio && !systemModeConfirmed) {
        AudioInputMode.MICROPHONE
    } else {
        storedMode
    }
}
