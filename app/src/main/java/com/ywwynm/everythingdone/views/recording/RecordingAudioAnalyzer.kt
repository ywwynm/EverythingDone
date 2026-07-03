package com.ywwynm.everythingdone.views.recording

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class RecordingAudioAnalyzer(private val sampleRate: Int) {

    private val monoRing = FloatArray(FFT_SIZE)
    private val leftRing = FloatArray(FFT_SIZE)
    private val rightRing = FloatArray(FFT_SIZE)
    private var writeIndex = 0
    private var sampleCount = 0

    private val window = FloatArray(FFT_SIZE) { i ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * i / (FFT_SIZE - 1))).toFloat()
    }
    private val real = DoubleArray(FFT_SIZE)
    private val imag = DoubleArray(FFT_SIZE)
    private val bandStartBins = IntArray(BAND_COUNT)
    private val bandEndBins = IntArray(BAND_COUNT)
    private val previousBands = FloatArray(BAND_COUNT)
    private val smoothedBands = FloatArray(BAND_COUNT)
    private val pitchBuffer = FloatArray(PITCH_FRAME_SIZE)
    private val yin = FloatArray(PITCH_FRAME_SIZE)
    private val onsetHistory = FloatArray(RHYTHM_HISTORY_SIZE)

    private var adaptiveFloorDb = -62f
    private var adaptivePeakDb = -30f
    private var fluxMean = 0.02f
    private var fluxDeviation = 0.03f
    private var smoothedPresence = 0f
    private var paceEnergy = 0f
    private var frameIndex = 0
    private var onsetHistoryIndex = 0
    private var onsetHistoryCount = 0
    private var lastOnsetFrame = -RHYTHM_HISTORY_SIZE
    private var lastBeatFrame = -RHYTHM_HISTORY_SIZE
    private var beatPeriodFrames = 0f
    private var tempoBpm = 0f
    private var tempoConfidence = 0f
    private var pitchHz = 0f
    private var pitchConfidence = 0f
    private var pitchNormalized = 0.5f
    private var previousRelativeLevel = 0f
    private var previousFastLevel = 0f

    init {
        val nyquistBin = FFT_SIZE / 2
        for (i in 0 until BAND_COUNT) {
            val startHz = BAND_RANGES[i * 2]
            val endHz = BAND_RANGES[i * 2 + 1]
            bandStartBins[i] = (startHz * FFT_SIZE / sampleRate)
                .coerceIn(1, nyquistBin - 1)
            bandEndBins[i] = (endHz * FFT_SIZE / sampleRate)
                .coerceIn(bandStartBins[i] + 1, nyquistBin)
        }
    }

    fun ingest(buf: ByteArray, byteReadSize: Int) {
        var i = 0
        val usable = byteReadSize - byteReadSize % BYTES_PER_STEREO_FRAME
        while (i + BYTES_PER_STEREO_FRAME <= usable) {
            val left = readPcm16(buf, i) / PCM_16_MAX
            val right = readPcm16(buf, i + BYTES_PER_SAMPLE) / PCM_16_MAX
            val mono = ((left + right) * 0.5f).coerceIn(-1f, 1f)
            leftRing[writeIndex] = left.coerceIn(-1f, 1f)
            rightRing[writeIndex] = right.coerceIn(-1f, 1f)
            monoRing[writeIndex] = mono
            writeIndex = (writeIndex + 1) % FFT_SIZE
            if (sampleCount < FFT_SIZE) {
                sampleCount++
            }
            i += BYTES_PER_STEREO_FRAME
        }
    }

    fun analyze(elapsedMs: Long): RecordingWaveDriveFrame {
        if (sampleCount < MIN_ANALYSIS_SAMPLES) {
            return RecordingWaveDriveFrame.SILENCE
        }

        val frameSeconds = (elapsedMs.coerceIn(MIN_FRAME_MS, MAX_FRAME_MS)).toFloat() / 1000f
        val available = sampleCount.coerceAtMost(FFT_SIZE)
        val leadingZeros = FFT_SIZE - available
        val start = (writeIndex - available + FFT_SIZE) % FFT_SIZE

        var sumSq = 0.0
        var leftSq = 0.0
        var rightSq = 0.0
        for (i in 0 until FFT_SIZE) {
            val sample: Float
            val left: Float
            val right: Float
            if (i < leadingZeros) {
                sample = 0f
                left = 0f
                right = 0f
            } else {
                val ringIndex = (start + i - leadingZeros) % FFT_SIZE
                sample = monoRing[ringIndex]
                left = leftRing[ringIndex]
                right = rightRing[ringIndex]
                sumSq += sample.toDouble() * sample.toDouble()
                leftSq += left.toDouble() * left.toDouble()
                rightSq += right.toDouble() * right.toDouble()
            }
            real[i] = (sample * window[i]).toDouble()
            imag[i] = 0.0
        }

        val rms = sqrt(sumSq / available.coerceAtLeast(1)).toFloat()
        val dbFs = amplitudeToDbFs(rms)
        val absoluteLevel = smoothStep(-56f, -14f, dbFs)
        val fastLevel = recentLevel(FAST_RMS_SIZE)
        val relativeLevel = adaptiveRelativeLevel(dbFs, frameSeconds)
        val stereoBalance = stereoBalance(leftSq, rightSq)

        fft(real, imag)
        val spectrum = analyzeSpectrum(relativeLevel)
        val levelRise = max(0f, relativeLevel - previousRelativeLevel)
        val fastRise = max(0f, fastLevel - previousFastLevel)
        previousRelativeLevel = relativeLevel
        previousFastLevel = fastLevel

        val fluxDelta = spectrum.spectralFlux - fluxMean
        fluxMean += fluxDelta * FLUX_MEAN_ALPHA
        fluxDeviation += (abs(fluxDelta) - fluxDeviation) * FLUX_DEVIATION_ALPHA
        val onsetStrength = clamp01(
            (spectrum.spectralFlux + levelRise * 1.35f + fastRise * 1.15f -
                    fluxMean - fluxDeviation * 0.45f) /
                    (fluxDeviation * 2.35f + 0.035f)
        )
        val voicePresence = clamp01(
            smoothStep(0.12f, 0.56f, spectrum.voiceEnergy) *
                    (1f - spectrum.spectralFlatness * 0.58f) +
                    spectrum.bodyEnergy * 0.16f
        )
        updatePitch(relativeLevel, onsetStrength, spectrum.spectralFlatness)
        val rhythm = analyzeRhythm(
            frameSeconds = frameSeconds,
            relativeLevel = relativeLevel,
            fastLevel = fastLevel,
            onsetStrength = onsetStrength,
            lowFlux = spectrum.lowFlux,
            midFlux = spectrum.midFlux,
            highFlux = spectrum.highFlux
        )

        val noiseLike = clamp01(
            (spectrum.spectralFlatness * 0.72f +
                    max(spectrum.highEnergy, spectrum.airEnergy) * 0.16f +
                    (1f - voicePresence) * 0.12f) *
                    smoothStep(0.08f, 0.48f, relativeLevel) *
                    (1f - onsetStrength * 0.54f) *
                    (1f - pitchConfidence * 0.22f) *
                    (1f - voicePresence * 0.40f) *
                    (1f - absoluteLevel * 0.18f)
        )
        val rawPresence = clamp01(
            max(
                max(relativeLevel * 0.86f + absoluteLevel * 0.18f, absoluteLevel * 0.70f + fastLevel * 0.18f),
                fastLevel * 0.50f + onsetStrength * 0.50f
            ) +
                    voicePresence * 0.16f -
                    noiseLike * 0.36f
        )
        smoothedPresence += (rawPresence - smoothedPresence) *
                if (rawPresence > smoothedPresence) PRESENCE_ATTACK else PRESENCE_RELEASE
        val presence = clamp01(smoothedPresence)
        val quietness = 1f - presence

        val bassWeight = clamp01((spectrum.bassEnergy * 1.15f + spectrum.lowFlux * 0.65f) * presence)
        val voiceWeight = clamp01((voicePresence * 0.92f + pitchConfidence * 0.20f) * presence)
        val brightnessWeight = clamp01(
            (spectrum.highEnergy * 0.48f +
                    spectrum.airEnergy * 0.62f +
                    spectrum.spectralRolloff * 0.22f +
                    spectrum.highFlux * 0.58f) * presence
        )
        val fullMixEnergy = clamp01(
            absoluteLevel * 0.40f +
                    relativeLevel * 0.34f +
                    fastLevel * 0.20f +
                    spectrum.bassEnergy * 0.12f +
                    spectrum.bodyEnergy * 0.15f +
                    spectrum.voiceEnergy * 0.15f +
                    spectrum.highEnergy * 0.06f
        )
        val wake = clamp01(
            max(onsetStrength, rhythm.beatPulse) * (0.42f + relativeLevel * 0.72f) +
                    smoothStep(0.56f, 0.88f, fullMixEnergy) * 0.20f
        ) *
                smoothStep(0.12f, 0.32f, presence)
        val swell = clamp01(
            relativeLevel * 0.46f +
                    absoluteLevel * 0.36f +
                    fastLevel * 0.26f +
                    fullMixEnergy * 0.24f +
                    bassWeight * 0.22f +
                    voiceWeight * 0.16f +
                    rhythm.beatPulse * 0.25f +
                    rhythm.pace * 0.14f +
                    onsetStrength * 0.16f -
                    noiseLike * 0.06f
        )
        val turbulence = clamp01(
            onsetStrength * 0.68f +
                    spectrum.spectralFlux * 0.48f +
                    brightnessWeight * 0.24f +
                    rhythm.beatPulse * 0.26f
        ) * smoothStep(0.08f, 0.30f, presence)
        val waterLevel = clamp01(
            relativeLevel * 0.48f +
                    absoluteLevel * 0.38f +
                    fastLevel * 0.18f +
                    bassWeight * 0.10f +
                    voiceWeight * 0.12f +
                    rhythm.pace * 0.05f -
                    noiseLike * 0.035f
        )
        val surfaceGate = smoothStep(0.16f, 0.42f, presence)
        val surfaceLife = clamp01(
            onsetStrength * 0.46f +
                    spectrum.highFlux * 0.82f +
                    brightnessWeight * 0.28f +
                    pitchConfidence * 0.12f
        ) * surfaceGate
        val shimmer = clamp01(
            spectrum.highFlux * 0.74f +
                    spectrum.airEnergy * 0.20f +
                    spectrum.spectralCentroid * 0.15f
        ) * surfaceGate
        val level = clamp01(
            absoluteLevel * 0.42f +
                    relativeLevel * 0.42f +
                    fastLevel * 0.22f +
                    rhythm.pace * 0.08f -
                    noiseLike * 0.08f
        )
        val pitchLift = (pitchNormalized - 0.5f) * 2f * pitchConfidence

        val feature = RecordingAudioFeatureFrame(
            rms = rms,
            dbFs = dbFs,
            absoluteLevel = absoluteLevel,
            relativeLevel = relativeLevel,
            fastLevel = fastLevel,
            bassEnergy = spectrum.bassEnergy,
            bodyEnergy = spectrum.bodyEnergy,
            voiceEnergy = spectrum.voiceEnergy,
            highEnergy = spectrum.highEnergy,
            airEnergy = spectrum.airEnergy,
            spectralCentroid = spectrum.spectralCentroid,
            spectralRolloff = spectrum.spectralRolloff,
            spectralFlatness = spectrum.spectralFlatness,
            spectralFlux = spectrum.spectralFlux,
            lowFlux = spectrum.lowFlux,
            midFlux = spectrum.midFlux,
            highFlux = spectrum.highFlux,
            onsetStrength = onsetStrength,
            pitchHz = pitchHz,
            pitchConfidence = pitchConfidence,
            pitchNormalized = pitchNormalized,
            stereoBalance = stereoBalance,
            noiseLike = noiseLike,
            voicePresence = voicePresence,
            tempoBpm = rhythm.tempoBpm,
            tempoConfidence = rhythm.tempoConfidence,
            beatPulse = rhythm.beatPulse,
            beatPhase = rhythm.beatPhase
        )

        frameIndex++
        return RecordingWaveDriveFrame(
            level = level,
            presence = presence,
            quietness = quietness,
            waterLevel = waterLevel,
            swell = swell,
            turbulence = turbulence,
            wake = wake,
            pace = rhythm.pace,
            bassWeight = bassWeight,
            voiceWeight = voiceWeight,
            brightnessWeight = brightnessWeight,
            surfaceLife = surfaceLife,
            shimmer = shimmer,
            pitchLift = pitchLift,
            pitchConfidence = pitchConfidence,
            stereoPan = stereoBalance,
            feature = feature
        )
    }

    private data class SpectrumFrame(
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
        val highFlux: Float
    )

    private data class RhythmFrame(
        val beatPulse: Float,
        val beatPhase: Float,
        val tempoBpm: Float,
        val tempoConfidence: Float,
        val pace: Float
    )

    private fun analyzeSpectrum(relativeLevel: Float): SpectrumFrame {
        val rawBands = FloatArray(BAND_COUNT)
        var totalMagnitude = 0.0
        var weightedFrequency = 0.0
        var totalPower = 0.0
        var geometricLog = 0.0
        val nyquistBin = FFT_SIZE / 2

        for (bin in 1 until nyquistBin) {
            val re = real[bin]
            val im = imag[bin]
            val magnitude = sqrt(re * re + im * im) / (FFT_SIZE * 0.5)
            val power = magnitude * magnitude
            val frequency = bin.toDouble() * sampleRate / FFT_SIZE
            totalMagnitude += magnitude
            weightedFrequency += magnitude * frequency
            totalPower += power
            geometricLog += ln(magnitude + SPECTRUM_EPSILON)
        }

        for (band in 0 until BAND_COUNT) {
            var bandPower = 0.0
            var bins = 0
            for (bin in bandStartBins[band] until bandEndBins[band]) {
                val re = real[bin]
                val im = imag[bin]
                val magnitude = sqrt(re * re + im * im) / (FFT_SIZE * 0.5)
                bandPower += magnitude * magnitude
                bins++
            }
            val bandRms = sqrt(bandPower / bins.coerceAtLeast(1))
            val bandDb = amplitudeToDbFs(bandRms.toFloat())
            rawBands[band] = clamp01(smoothStep(BAND_MIN_DB, BAND_MAX_DB, bandDb) *
                    (0.48f + relativeLevel * 0.82f))
        }

        var spectralFlux = 0f
        var lowFlux = 0f
        var midFlux = 0f
        var highFlux = 0f
        for (band in 0 until BAND_COUNT) {
            val positive = max(0f, rawBands[band] - previousBands[band])
            spectralFlux += positive
            when (band) {
                0, 1 -> lowFlux += positive
                2, 3, 4 -> midFlux += positive
                else -> highFlux += positive
            }
            previousBands[band] = rawBands[band]
            smoothedBands[band] += (rawBands[band] - smoothedBands[band]) *
                    if (rawBands[band] > smoothedBands[band]) BAND_ATTACK else BAND_RELEASE
        }
        spectralFlux = clamp01(spectralFlux / BAND_COUNT * 4.4f)
        lowFlux = clamp01(lowFlux * 1.35f)
        midFlux = clamp01(midFlux * 1.10f)
        highFlux = clamp01(highFlux * 1.18f)

        val centroid = if (totalMagnitude <= SPECTRUM_EPSILON) {
            0f
        } else {
            clamp01((weightedFrequency / totalMagnitude / 8000.0).toFloat())
        }
        val flatness = if (totalMagnitude <= SPECTRUM_EPSILON) {
            0f
        } else {
            val binCount = (nyquistBin - 1).coerceAtLeast(1)
            val geometricMean = exp(geometricLog / binCount)
            val arithmeticMean = totalMagnitude / binCount
            clamp01((geometricMean / (arithmeticMean + SPECTRUM_EPSILON)).toFloat())
        }
        val rolloff = spectralRolloff(totalPower)
        val bass = clamp01(smoothedBands[0] * 0.70f + smoothedBands[1] * 0.52f)
        val body = clamp01(smoothedBands[2] * 0.56f + smoothedBands[3] * 0.50f)
        val voice = clamp01(smoothedBands[3] * 0.34f + smoothedBands[4] * 0.42f +
                smoothedBands[5] * 0.28f)
        val high = clamp01(smoothedBands[5] * 0.46f + smoothedBands[6] * 0.42f)
        val air = clamp01(smoothedBands[6] * 0.32f + smoothedBands[7] * 0.58f)

        return SpectrumFrame(
            bassEnergy = bass,
            bodyEnergy = body,
            voiceEnergy = voice,
            highEnergy = high,
            airEnergy = air,
            spectralCentroid = centroid,
            spectralRolloff = rolloff,
            spectralFlatness = flatness,
            spectralFlux = spectralFlux,
            lowFlux = lowFlux,
            midFlux = midFlux,
            highFlux = highFlux
        )
    }

    private fun analyzeRhythm(
        frameSeconds: Float,
        relativeLevel: Float,
        fastLevel: Float,
        onsetStrength: Float,
        lowFlux: Float,
        midFlux: Float,
        highFlux: Float
    ): RhythmFrame {
        val onsetForTempo = if (relativeLevel > 0.14f) onsetStrength else 0f
        pushOnset(onsetForTempo)
        estimateTempo(frameSeconds)

        val minOnsetGap = max(1, (MIN_ONSET_GAP_SEC / frameSeconds).roundToInt())
        val strongOnset = onsetForTempo >= ONSET_TRIGGER &&
                frameIndex - lastOnsetFrame >= minOnsetGap
        var beatPulse = 0f
        if (strongOnset) {
            lastOnsetFrame = frameIndex
            if (acceptBeat(frameSeconds)) {
                if (lastBeatFrame > -RHYTHM_HISTORY_SIZE / 2) {
                    val interval = frameIndex - lastBeatFrame
                    val minPeriod = max(1, (60f / MAX_BPM / frameSeconds).roundToInt())
                    val maxPeriod = max(minPeriod, (60f / MIN_BPM / frameSeconds).roundToInt())
                    if (interval in minPeriod..maxPeriod) {
                        beatPeriodFrames = if (beatPeriodFrames <= 0f) {
                            interval.toFloat()
                        } else {
                            beatPeriodFrames * 0.74f + interval * 0.26f
                        }
                        tempoBpm = 60f / (beatPeriodFrames * frameSeconds)
                        tempoConfidence = clamp01(tempoConfidence + onsetStrength * 0.16f)
                    }
                }
                lastBeatFrame = frameIndex
                beatPulse = onsetStrength
            }
        }

        val beatPhase = currentBeatPhase()
        val predictedBeat = if (tempoConfidence > 0.42f) {
            val phaseDistance = min(beatPhase, 1f - beatPhase)
            (exp(-(phaseDistance * phaseDistance) / 0.0065f) * 0.42f *
                    tempoConfidence * relativeLevel).toFloat()
        } else {
            0f
        }
        beatPulse = max(beatPulse, predictedBeat)

        val paceImpulse = clamp01(
            onsetStrength * 0.58f +
                    lowFlux * 0.30f +
                    midFlux * 0.25f +
                    highFlux * 0.14f +
                    fastLevel * 0.16f
        )
        paceEnergy += (paceImpulse - paceEnergy) *
                if (paceImpulse > paceEnergy) PACE_ATTACK else PACE_RELEASE
        val tempoPace = if (tempoConfidence > 0.36f) {
            smoothStep(70f, 170f, tempoBpm) * tempoConfidence
        } else {
            0f
        }
        val pace = clamp01(max(paceEnergy, tempoPace) * smoothStep(0.10f, 0.34f, relativeLevel))
        return RhythmFrame(
            beatPulse = clamp01(beatPulse),
            beatPhase = beatPhase,
            tempoBpm = tempoBpm,
            tempoConfidence = tempoConfidence,
            pace = pace
        )
    }

    private fun adaptiveRelativeLevel(dbFs: Float, frameSeconds: Float): Float {
        val db = dbFs.coerceIn(SILENCE_DB_FS, -3f)
        val floorTau = if (db < adaptiveFloorDb) FLOOR_FALL_TAU else FLOOR_RISE_TAU
        adaptiveFloorDb += (db - adaptiveFloorDb) * smoothingFactor(frameSeconds, floorTau)
        val peakTau = if (db > adaptivePeakDb) PEAK_RISE_TAU else PEAK_FALL_TAU
        adaptivePeakDb += (db - adaptivePeakDb) * smoothingFactor(frameSeconds, peakTau)
        if (adaptivePeakDb < adaptiveFloorDb + MIN_DYNAMIC_RANGE_DB) {
            adaptivePeakDb = adaptiveFloorDb + MIN_DYNAMIC_RANGE_DB
        }
        val range = (adaptivePeakDb - adaptiveFloorDb).coerceIn(MIN_DYNAMIC_RANGE_DB, MAX_DYNAMIC_RANGE_DB)
        val relative = (db - adaptiveFloorDb - RELATIVE_OFFSET_DB) / range
        return smoothStep(0.05f, 0.74f, relative)
    }

    private fun recentLevel(size: Int): Float {
        val available = sampleCount.coerceAtMost(size)
        if (available <= 0) return 0f
        val start = (writeIndex - available + FFT_SIZE) % FFT_SIZE
        var sumSq = 0.0
        for (i in 0 until available) {
            val sample = monoRing[(start + i) % FFT_SIZE]
            sumSq += sample.toDouble() * sample.toDouble()
        }
        val rms = sqrt(sumSq / available.coerceAtLeast(1)).toFloat()
        return smoothStep(-54f, -13f, amplitudeToDbFs(rms))
    }

    private fun updatePitch(relativeLevel: Float, onsetStrength: Float, flatness: Float) {
        if (frameIndex % PITCH_ANALYSIS_INTERVAL != 0) {
            pitchConfidence *= 0.96f
            return
        }
        if (sampleCount < PITCH_FRAME_SIZE || relativeLevel < 0.16f || flatness > 0.72f) {
            pitchConfidence *= 0.88f
            return
        }

        val start = (writeIndex - PITCH_FRAME_SIZE + FFT_SIZE) % FFT_SIZE
        for (i in 0 until PITCH_FRAME_SIZE) {
            pitchBuffer[i] = monoRing[(start + i) % FFT_SIZE]
        }

        val minTau = (sampleRate / PITCH_MAX_HZ).roundToInt().coerceAtLeast(2)
        val maxTau = (sampleRate / PITCH_MIN_HZ).roundToInt().coerceAtMost(PITCH_FRAME_SIZE - 2)
        val compareLength = PITCH_FRAME_SIZE - maxTau - 1
        if (compareLength <= 0 || minTau >= maxTau) {
            pitchConfidence *= 0.88f
            return
        }

        yin[0] = 1f
        var runningSum = 0f
        for (tau in 1..maxTau) {
            var difference = 0f
            for (i in 0 until compareLength) {
                val delta = pitchBuffer[i] - pitchBuffer[i + tau]
                difference += delta * delta
            }
            runningSum += difference
            yin[tau] = if (runningSum <= 0f) 1f else difference * tau / runningSum
        }

        var tau = -1
        var t = minTau
        while (t <= maxTau) {
            if (yin[t] < YIN_THRESHOLD) {
                while (t + 1 <= maxTau && yin[t + 1] < yin[t]) {
                    t++
                }
                tau = t
                break
            }
            t++
        }
        if (tau < 0) {
            pitchConfidence *= 0.88f
            return
        }

        val refinedTau = parabolicTau(tau, maxTau)
        val candidateHz = sampleRate / refinedTau
        if (candidateHz < PITCH_MIN_HZ || candidateHz > PITCH_MAX_HZ) {
            pitchConfidence *= 0.88f
            return
        }

        val rawConfidence = clamp01((1f - yin[tau]) * (1f - flatness * 0.55f) *
                (1f - onsetStrength * 0.18f))
        if (rawConfidence < 0.30f) {
            pitchConfidence *= 0.88f
            return
        }
        pitchHz = if (pitchHz <= 0f) {
            candidateHz
        } else {
            pitchHz * 0.72f + candidateHz * 0.28f
        }
        val normalized = (ln((candidateHz / PITCH_MIN_HZ).toDouble()) /
                ln((PITCH_MAX_HZ / PITCH_MIN_HZ).toDouble())).toFloat()
        pitchNormalized = clamp01(pitchNormalized * 0.76f + normalized * 0.24f)
        pitchConfidence += (rawConfidence - pitchConfidence) *
                if (rawConfidence > pitchConfidence) 0.36f else 0.18f
    }

    private fun parabolicTau(tau: Int, maxTau: Int): Float {
        if (tau <= 1 || tau >= maxTau) return tau.toFloat()
        val left = yin[tau - 1]
        val center = yin[tau]
        val right = yin[tau + 1]
        val denominator = left - 2f * center + right
        if (abs(denominator) < 0.00001f) return tau.toFloat()
        return tau + 0.5f * (left - right) / denominator
    }

    private fun pushOnset(onset: Float) {
        onsetHistory[onsetHistoryIndex] = onset
        onsetHistoryIndex = (onsetHistoryIndex + 1) % RHYTHM_HISTORY_SIZE
        if (onsetHistoryCount < RHYTHM_HISTORY_SIZE) {
            onsetHistoryCount++
        }
    }

    private fun onsetAtAge(age: Int): Float {
        val index = (onsetHistoryIndex - 1 - age + RHYTHM_HISTORY_SIZE) % RHYTHM_HISTORY_SIZE
        return onsetHistory[index]
    }

    private fun estimateTempo(frameSeconds: Float) {
        val minPeriod = max(2, (60f / MAX_BPM / frameSeconds).roundToInt())
        val maxPeriod = min(RHYTHM_HISTORY_SIZE / 2, (60f / MIN_BPM / frameSeconds).roundToInt())
        if (onsetHistoryCount < max(18, minPeriod * 3) || minPeriod >= maxPeriod) {
            tempoConfidence *= 0.985f
            return
        }

        var bestPeriod = 0
        var bestScore = 0f
        var totalScore = 0f
        var scoreCount = 0
        for (period in minPeriod..maxPeriod) {
            var score = 0f
            var pairs = 0
            var age = 0
            while (age + period < onsetHistoryCount) {
                score += onsetAtAge(age) * onsetAtAge(age + period)
                pairs++
                age++
            }
            if (pairs > 0) {
                score /= pairs
                totalScore += score
                scoreCount++
                if (score > bestScore) {
                    bestScore = score
                    bestPeriod = period
                }
            }
        }

        if (bestPeriod <= 0 || scoreCount <= 0) {
            tempoConfidence *= 0.985f
            return
        }
        val averageScore = totalScore / scoreCount
        val confidence = clamp01((bestScore - averageScore) / (bestScore + 0.0001f) * 2.2f)
        if (confidence > 0.38f) {
            beatPeriodFrames = if (beatPeriodFrames <= 0f) {
                bestPeriod.toFloat()
            } else {
                beatPeriodFrames * 0.82f + bestPeriod * 0.18f
            }
            tempoBpm = 60f / (beatPeriodFrames * frameSeconds)
            tempoConfidence += (confidence - tempoConfidence) * 0.18f
        } else {
            tempoConfidence *= 0.985f
        }
    }

    private fun acceptBeat(frameSeconds: Float): Boolean {
        if (beatPeriodFrames <= 0f || lastBeatFrame <= -RHYTHM_HISTORY_SIZE / 2) {
            return true
        }
        val framesSinceBeat = frameIndex - lastBeatFrame
        val minGap = max(1, (beatPeriodFrames * 0.45f).roundToInt())
        if (framesSinceBeat < minGap) {
            return false
        }
        if (tempoConfidence < 0.46f) {
            return true
        }
        val expected = beatPeriodFrames
        val phaseError = abs(framesSinceBeat - expected) / expected
        val lateEnough = framesSinceBeat >= (expected * 1.24f).roundToInt()
        val absoluteGap = framesSinceBeat >= (MIN_BEAT_GAP_SEC / frameSeconds).roundToInt()
        return absoluteGap && (phaseError <= 0.32f || lateEnough)
    }

    private fun currentBeatPhase(): Float {
        if (beatPeriodFrames <= 0f || lastBeatFrame <= -RHYTHM_HISTORY_SIZE / 2) {
            return 0f
        }
        val raw = (frameIndex - lastBeatFrame) / beatPeriodFrames
        return raw - kotlin.math.floor(raw)
    }

    private fun spectralRolloff(totalPower: Double): Float {
        if (totalPower <= SPECTRUM_EPSILON) {
            return 0f
        }
        val target = totalPower * 0.85
        var cumulative = 0.0
        val nyquistBin = FFT_SIZE / 2
        for (bin in 1 until nyquistBin) {
            val re = real[bin]
            val im = imag[bin]
            val magnitude = sqrt(re * re + im * im) / (FFT_SIZE * 0.5)
            cumulative += magnitude * magnitude
            if (cumulative >= target) {
                val hz = bin.toFloat() * sampleRate / FFT_SIZE
                return clamp01(hz / 10000f)
            }
        }
        return 1f
    }

    private fun stereoBalance(leftSq: Double, rightSq: Double): Float {
        val left = sqrt(leftSq)
        val right = sqrt(rightSq)
        val total = left + right
        if (total <= 0.000001) return 0f
        return ((right - left) / total).toFloat().coerceIn(-1f, 1f)
    }

    private fun amplitudeToDbFs(amplitude: Float): Float {
        if (amplitude <= 0.000001f) return SILENCE_DB_FS
        return (20f * log10(amplitude)).coerceIn(SILENCE_DB_FS, 0f)
    }

    private fun readPcm16(buf: ByteArray, offset: Int): Float {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt()
        return ((hi shl 8) or lo).toShort().toFloat()
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tempRe = re[i]
                val tempIm = im[i]
                re[i] = re[j]
                im[i] = im[j]
                re[j] = tempRe
                im[j] = tempIm
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            val wLenRe = cos(angle)
            val wLenIm = sin(angle)
            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val evenRe = re[i + k]
                    val evenIm = im[i + k]
                    val oddRe = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val oddIm = re[i + k + half] * wIm + im[i + k + half] * wRe
                    re[i + k] = evenRe + oddRe
                    im[i + k] = evenIm + oddIm
                    re[i + k + half] = evenRe - oddRe
                    im[i + k + half] = evenIm - oddIm
                    val nextWRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun smoothingFactor(frameSeconds: Float, tau: Float): Float {
        return 1f - exp(-frameSeconds / tau)
    }

    private fun smoothStep(start: Float, end: Float, value: Float): Float {
        if (end <= start) return if (value >= end) 1f else 0f
        val t = clamp01((value - start) / (end - start))
        return t * t * (3f - 2f * t)
    }

    private fun clamp01(value: Float): Float {
        if (value < 0f) return 0f
        if (value > 1f) return 1f
        return value
    }

    companion object {
        private const val FFT_SIZE = 2048
        private const val PITCH_FRAME_SIZE = 2048
        private const val BAND_COUNT = 8
        private val BAND_RANGES = intArrayOf(
            35, 95,
            95, 180,
            180, 360,
            360, 720,
            720, 1500,
            1500, 3000,
            3000, 6200,
            6200, 12000
        )

        private const val BYTES_PER_SAMPLE = 2
        private const val BYTES_PER_STEREO_FRAME = 4
        private const val PCM_16_MAX = 32768f
        private const val MIN_ANALYSIS_SAMPLES = 512
        private const val FAST_RMS_SIZE = 512
        private const val MIN_FRAME_MS = 8L
        private const val MAX_FRAME_MS = 80L

        private const val FLOOR_FALL_TAU = 0.9f
        private const val FLOOR_RISE_TAU = 8.0f
        private const val PEAK_RISE_TAU = 0.08f
        private const val PEAK_FALL_TAU = 3.2f
        private const val MIN_DYNAMIC_RANGE_DB = 18f
        private const val MAX_DYNAMIC_RANGE_DB = 44f
        private const val RELATIVE_OFFSET_DB = 4.4f

        private const val BAND_MIN_DB = -88f
        private const val BAND_MAX_DB = -24f
        private const val BAND_ATTACK = 0.42f
        private const val BAND_RELEASE = 0.14f
        private const val SPECTRUM_EPSILON = 0.0000000001
        private const val FLUX_MEAN_ALPHA = 0.035f
        private const val FLUX_DEVIATION_ALPHA = 0.055f
        private const val PRESENCE_ATTACK = 0.42f
        private const val PRESENCE_RELEASE = 0.12f

        private const val RHYTHM_HISTORY_SIZE = 160
        private const val MIN_BPM = 58f
        private const val MAX_BPM = 210f
        private const val ONSET_TRIGGER = 0.54f
        private const val MIN_ONSET_GAP_SEC = 0.09f
        private const val MIN_BEAT_GAP_SEC = 0.22f
        private const val PACE_ATTACK = 0.36f
        private const val PACE_RELEASE = 0.10f

        private const val PITCH_ANALYSIS_INTERVAL = 3
        private const val PITCH_MIN_HZ = 80f
        private const val PITCH_MAX_HZ = 650f
        private const val YIN_THRESHOLD = 0.16f
    }
}
