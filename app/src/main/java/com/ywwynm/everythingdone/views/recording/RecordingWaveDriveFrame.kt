package com.ywwynm.everythingdone.views.recording

data class RecordingWaveDriveFrame(
    val level: Float,
    val presence: Float,
    val quietness: Float,
    val waterLevel: Float,
    val swell: Float,
    val turbulence: Float,
    val wake: Float,
    val pace: Float,
    val bassWeight: Float,
    val voiceWeight: Float,
    val brightnessWeight: Float,
    val surfaceLife: Float,
    val shimmer: Float,
    val pitchLift: Float,
    val pitchConfidence: Float,
    val stereoPan: Float,
    val feature: RecordingAudioFeatureFrame
) {
    companion object {
        val SILENCE: RecordingWaveDriveFrame = RecordingWaveDriveFrame(
            level = 0f,
            presence = 0f,
            quietness = 1f,
            waterLevel = 0f,
            swell = 0f,
            turbulence = 0f,
            wake = 0f,
            pace = 0f,
            bassWeight = 0f,
            voiceWeight = 0f,
            brightnessWeight = 0f,
            surfaceLife = 0f,
            shimmer = 0f,
            pitchLift = 0f,
            pitchConfidence = 0f,
            stereoPan = 0f,
            feature = RecordingAudioFeatureFrame.SILENCE
        )
    }
}
