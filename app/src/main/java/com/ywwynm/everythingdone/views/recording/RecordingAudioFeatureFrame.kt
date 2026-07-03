package com.ywwynm.everythingdone.views.recording

data class RecordingAudioFeatureFrame(
    val rms: Float,
    val dbFs: Float,
    val absoluteLevel: Float,
    val relativeLevel: Float,
    val fastLevel: Float,
    val bassEnergy: Float,
    val bodyEnergy: Float,
    val voiceEnergy: Float,
    val highEnergy: Float,
    val airEnergy: Float,
    val spectralCentroid: Float,
    val spectralRolloff: Float,
    val spectralFlatness: Float,
    val spectralFlux: Float,
    val lowFlux: Float,
    val midFlux: Float,
    val highFlux: Float,
    val onsetStrength: Float,
    val pitchHz: Float,
    val pitchConfidence: Float,
    val pitchNormalized: Float,
    val stereoBalance: Float,
    val noiseLike: Float,
    val voicePresence: Float,
    val tempoBpm: Float,
    val tempoConfidence: Float,
    val beatPulse: Float,
    val beatPhase: Float
) {
    companion object {
        val SILENCE: RecordingAudioFeatureFrame = RecordingAudioFeatureFrame(
            rms = 0f,
            dbFs = SILENCE_DB_FS,
            absoluteLevel = 0f,
            relativeLevel = 0f,
            fastLevel = 0f,
            bassEnergy = 0f,
            bodyEnergy = 0f,
            voiceEnergy = 0f,
            highEnergy = 0f,
            airEnergy = 0f,
            spectralCentroid = 0f,
            spectralRolloff = 0f,
            spectralFlatness = 0f,
            spectralFlux = 0f,
            lowFlux = 0f,
            midFlux = 0f,
            highFlux = 0f,
            onsetStrength = 0f,
            pitchHz = 0f,
            pitchConfidence = 0f,
            pitchNormalized = 0.5f,
            stereoBalance = 0f,
            noiseLike = 0f,
            voicePresence = 0f,
            tempoBpm = 0f,
            tempoConfidence = 0f,
            beatPulse = 0f,
            beatPhase = 0f
        )
    }
}

const val SILENCE_DB_FS: Float = -96f
