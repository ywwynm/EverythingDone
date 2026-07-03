package com.ywwynm.everythingdone.views.recording

interface RecordingWaveFrameReceiver {
    fun receive(frame: RecordingWaveDriveFrame)
}
