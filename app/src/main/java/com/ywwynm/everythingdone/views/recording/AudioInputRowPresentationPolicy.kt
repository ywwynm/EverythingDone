package com.ywwynm.everythingdone.views.recording

enum class AudioInputRowPresentation {
    EDITABLE,
    HIDDEN
}

/** 音频输入行只属于录音前的准备界面；录音中和停止后都不参与 Dialog 布局。 */
object AudioInputRowPresentationPolicy {

    fun forPhase(phase: AudioRecordingPhase): AudioInputRowPresentation = when (phase) {
        AudioRecordingPhase.IDLE,
        AudioRecordingPhase.PREPARED,
        AudioRecordingPhase.ERROR -> AudioInputRowPresentation.EDITABLE
        AudioRecordingPhase.RECORDING,
        AudioRecordingPhase.STOPPED -> AudioInputRowPresentation.HIDDEN
    }
}
