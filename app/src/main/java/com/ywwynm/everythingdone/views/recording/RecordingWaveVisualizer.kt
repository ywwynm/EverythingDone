package com.ywwynm.everythingdone.views.recording

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil

import java.util.ArrayDeque
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class RecordingWaveVisualizer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), RecordingWaveFrameReceiver {

    private val density = context.resources.displayMetrics.density
    private val preferredWidthPx = (density * 280f).roundToInt()
    private val preferredHeightPx = (density * 360f).roundToInt()

    private val random = Random(System.nanoTime())
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val wavePath = Path()
    private val pathSampleX = FloatArray(MAX_PATH_SAMPLES)
    private val pathSampleY = FloatArray(MAX_PATH_SAMPLES)

    private val layers = Array(WAVE_LAYER_COUNT) { index -> createLayer(index) }
    private val layerColors = IntArray(WAVE_LAYER_COUNT)
    private val layerShaders = arrayOfNulls<Shader>(WAVE_LAYER_COUNT)
    private val bodyColors = IntArray(2)
    private var bodyShader: Shader? = null
    private var surfaceBaseColor = Color.WHITE
    private var thingBackground: ThingBackground = ThingBackground.pure(Color.rgb(80, 150, 255))
    private var backgroundIsLight = false

    private val fieldU = FloatArray(FIELD_N)
    private val fieldV = FloatArray(FIELD_N)
    private val fieldTmp = FloatArray(FIELD_N)
    private val impulseQueue: ArrayDeque<QueuedImpulse> = ArrayDeque()
    private val generatedWaves = Array(WAVE_LAYER_COUNT) {
        Array(GENERATED_WAVES_PER_LAYER) { GeneratedWave.inactive() }
    }
    private val glimmers = ArrayList<SurfaceGlimmer>()

    private var lastFrameTimeMs = 0L
    private var previousTargetWake = 0f
    private var previousRawLevel = 0f
    private var previousRawSwell = 0f
    private var previousRawPresence = 0f
    private var lastQueuedWaveMs = 0L
    private var impulseSequence = 0
    private var generatedWaveCursor = 0
    private var lastImpulseX = 0.5f
    private var running = false

    private var targetLevel = 0f
    private var targetPresence = 0f
    private var targetWaterLevel = 0f
    private var targetSwell = 0f
    private var targetTurbulence = 0f
    private var targetWake = 0f
    private var targetPace = 0f
    private var targetBass = 0f
    private var targetVoice = 0f
    private var targetBrightness = 0f
    private var targetSurfaceLife = 0f
    private var targetShimmer = 0f
    private var targetPitchLift = 0f
    private var targetPitchConfidence = 0f
    private var targetPan = 0f
    private var targetBeatPulse = 0f
    private var targetBeatPhase = 0f
    private var targetTempoBpm = 0f
    private var targetTempoConfidence = 0f

    private var level = 0f
    private var presence = 0f
    private var waterLevel = 0f
    private var swell = 0f
    private var turbulence = 0f
    private var wake = 0f
    private var pace = 0f
    private var bass = 0f
    private var voice = 0f
    private var brightness = 0f
    private var surfaceLife = 0f
    private var shimmer = 0f
    private var pitchLift = 0f
    private var pitchConfidence = 0f
    private var pan = 0f
    private var beatPulse = 0f
    private var visualBeatPhase = 0f
    private var tempoBpm = 0f
    private var tempoConfidence = 0f
    private var visualWildness = 0f

    init {
        wavePaint.style = Paint.Style.FILL
        wavePaint.isDither = true
        bodyPaint.isDither = true
        setWillNotDraw(false)
        rebuildColors(width, height)
    }

    fun setThingBackground(background: ThingBackground) {
        thingBackground = background
        backgroundIsLight = BackgroundUtil.isLight(background)
        rebuildColors(width, height)
        invalidate()
    }

    override fun receive(frame: RecordingWaveDriveFrame) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            acceptFrame(frame)
        } else {
            post { acceptFrame(frame) }
        }
    }

    private fun acceptFrame(frame: RecordingWaveDriveFrame) {
        targetLevel = stableUnitTarget(targetLevel, frame.level, TARGET_DEADBAND_CORE)
        targetPresence = stableUnitTarget(targetPresence, frame.presence, TARGET_DEADBAND_CORE)
        targetWaterLevel = stableUnitTarget(targetWaterLevel, frame.waterLevel, TARGET_DEADBAND_WATER)
        targetSwell = stableUnitTarget(targetSwell, frame.swell, TARGET_DEADBAND_CORE)
        targetTurbulence = stableUnitTarget(targetTurbulence, frame.turbulence, TARGET_DEADBAND_TEXTURE)
        targetWake = stableUnitTarget(targetWake, frame.wake, TARGET_DEADBAND_WAKE)
        targetPace = stableUnitTarget(targetPace, frame.pace, TARGET_DEADBAND_TEXTURE)
        targetBass = stableUnitTarget(targetBass, frame.bassWeight, TARGET_DEADBAND_TONE)
        targetVoice = stableUnitTarget(targetVoice, frame.voiceWeight, TARGET_DEADBAND_TONE)
        targetBrightness = stableUnitTarget(targetBrightness, frame.brightnessWeight, TARGET_DEADBAND_TONE)
        targetSurfaceLife = stableUnitTarget(targetSurfaceLife, frame.surfaceLife, TARGET_DEADBAND_TEXTURE)
        targetShimmer = stableUnitTarget(targetShimmer, frame.shimmer, TARGET_DEADBAND_TEXTURE)
        targetPitchLift = stableUnitTarget(targetPitchLift, frame.pitchLift, TARGET_DEADBAND_TONE)
        targetPitchConfidence = stableUnitTarget(targetPitchConfidence, frame.pitchConfidence, TARGET_DEADBAND_TONE)
        targetPan = stableSignedTarget(targetPan, frame.stereoPan, TARGET_DEADBAND_PAN)
        targetBeatPulse = stableUnitTarget(targetBeatPulse, frame.feature.beatPulse, TARGET_DEADBAND_EVENT)
        targetBeatPhase = stablePhaseTarget(targetBeatPhase, frame.feature.beatPhase, TARGET_DEADBAND_PHASE)
        targetTempoBpm = stableScalarTarget(
            targetTempoBpm,
            frame.feature.tempoBpm,
            TARGET_DEADBAND_TEMPO_BPM,
            zeroThreshold = 1f
        )
        targetTempoConfidence = stableUnitTarget(
            targetTempoConfidence,
            frame.feature.tempoConfidence,
            TARGET_DEADBAND_EVENT
        )

        val levelRise = max(0f, frame.level - previousRawLevel)
        val swellRise = max(0f, frame.swell - previousRawSwell)
        val presenceRise = max(0f, frame.presence - previousRawPresence)
        val wakeRise = max(0f, frame.wake - previousTargetWake)
        val waveRise = max(
            wakeRise,
            max(levelRise * 1.18f, swellRise * 0.82f + presenceRise * 0.38f)
        )
        val now = SystemClock.uptimeMillis()
        val musicDrive = clamp01(
            frame.presence * 0.28f +
                    frame.level * 0.36f +
                    frame.swell * 0.22f +
                    frame.pace * 0.48f
        )
        val spawnIntervalMs = waveSpawnIntervalMs(frame.pace, frame.feature.beatPulse, musicDrive)
        val periodicMusicWave = musicDrive > PERIODIC_WAVE_DRIVE_START &&
                now - lastQueuedWaveMs >= spawnIntervalMs
        val strongWaveEvent = waveRise > SURGE_RISE_TRIGGER || frame.feature.beatPulse > 0.48f

        if ((strongWaveEvent || periodicMusicWave) && now - lastQueuedWaveMs >= MIN_WAVE_SPAWN_INTERVAL_MS) {
            enqueueImpulse(
                QueuedImpulse(
                    strength = max(
                        max(waveRise * 1.18f, musicDrive * 0.46f),
                        frame.wake * 0.32f + frame.level * 0.26f + frame.swell * 0.16f +
                                frame.feature.beatPulse * 0.30f
                    ),
                    brightness = frame.brightnessWeight,
                    beatPulse = frame.feature.beatPulse,
                    wake = frame.wake,
                    pace = frame.pace,
                    stereoPan = frame.stereoPan
                )
            )
            lastQueuedWaveMs = now
        }
        if (frame.surfaceLife > 0.38f) {
            spawnGlimmers(frame)
        }
        previousTargetWake = frame.wake
        previousRawLevel = frame.level
        previousRawSwell = frame.swell
        previousRawPresence = frame.presence
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startFrameLoop(resetClock = true)
    }

    override fun onDetachedFromWindow() {
        stopFrameLoop()
        super.onDetachedFromWindow()
        lastFrameTimeMs = 0L
        fieldU.fill(0f)
        fieldV.fill(0f)
        fieldTmp.fill(0f)
        synchronized(impulseQueue) {
            impulseQueue.clear()
        }
        for (layerIndex in generatedWaves.indices) {
            for (waveIndex in generatedWaves[layerIndex].indices) {
                generatedWaves[layerIndex][waveIndex] = GeneratedWave.inactive()
            }
        }
        lastQueuedWaveMs = 0L
        glimmers.clear()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            startFrameLoop(resetClock = true)
        } else {
            stopFrameLoop()
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            startFrameLoop(resetClock = true)
        } else {
            stopFrameLoop()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            startFrameLoop(resetClock = true)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildColors(w, h)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(preferredWidthPx, widthMeasureSpec),
            resolveSize(preferredHeightPx, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            return
        }

        advance()
        drawWaterBody(canvas, w, h)
        for (i in 0 until WAVE_LAYER_COUNT) {
            drawLayer(canvas, layers[i], i, w, h)
        }
        drawSurfaceLife(canvas, w, h)

        if (running && isAttachedToWindow) {
            postInvalidateOnAnimation()
        }
    }

    private fun startFrameLoop(resetClock: Boolean) {
        if (resetClock) {
            lastFrameTimeMs = SystemClock.uptimeMillis()
        }
        if (!running) {
            running = true
            postInvalidateOnAnimation()
        }
    }

    private fun stopFrameLoop() {
        running = false
    }

    private fun advance(): Float {
        val now = SystemClock.uptimeMillis()
        val previous = lastFrameTimeMs
        lastFrameTimeMs = now
        val dt = if (previous <= 0L) {
            1f / 60f
        } else {
            ((now - previous).coerceIn(1L, 48L)).toFloat() / 1000f
        }

        level = approach(level, targetLevel, dt, 0.12f, 0.34f)
        presence = approach(presence, targetPresence, dt, 0.14f, 0.40f)
        waterLevel = approach(waterLevel, targetWaterLevel, dt, 0.55f, 1.18f)
        swell = approach(swell, targetSwell, dt, 0.16f, 0.52f)
        turbulence = approach(turbulence, targetTurbulence, dt, 0.14f, 0.38f)
        wake = approach(wake, targetWake, dt, 0.12f, 0.32f)
        pace = approach(pace, targetPace, dt, 0.24f, 0.70f)
        bass = approach(bass, targetBass, dt, 0.18f, 0.48f)
        voice = approach(voice, targetVoice, dt, 0.18f, 0.44f)
        brightness = approach(brightness, targetBrightness, dt, 0.16f, 0.38f)
        surfaceLife = approach(surfaceLife, targetSurfaceLife, dt, 0.14f, 0.34f)
        shimmer = approach(shimmer, targetShimmer, dt, 0.12f, 0.30f)
        pitchLift = approach(pitchLift, targetPitchLift, dt, 0.20f, 0.38f)
        pitchConfidence = approach(pitchConfidence, targetPitchConfidence, dt, 0.18f, 0.34f)
        pan = approach(pan, targetPan, dt, 0.18f, 0.40f)
        beatPulse = approach(beatPulse, targetBeatPulse, dt, 0.08f, 0.32f)
        tempoConfidence = approach(tempoConfidence, targetTempoConfidence, dt, 0.36f, 0.72f)
        if (targetTempoBpm > 1f) {
            tempoBpm = approach(tempoBpm, targetTempoBpm, dt, 0.42f, 0.82f)
        }
        if (tempoBpm > 1f && tempoConfidence > 0.18f) {
            visualBeatPhase = (visualBeatPhase + dt * tempoBpm / 60f) % 1f
            var phaseDiff = targetBeatPhase - visualBeatPhase
            if (phaseDiff > 0.5f) phaseDiff -= 1f
            if (phaseDiff < -0.5f) phaseDiff += 1f
            visualBeatPhase = (visualBeatPhase + phaseDiff * min(1f, dt / BEAT_PHASE_PULL_SEC) *
                    tempoConfidence + 1f) % 1f
        }
        visualWildness = computeWaveWildness()

        for (layer in layers) {
            val tideFlow = tideFlowScale()
            val flowDrive = clamp01(
                presence * 0.18f +
                        waterLevel * 0.34f +
                        swell * 0.08f +
                        level * 0.08f
            )
            val flowGate = smoothStep(FLOW_ACTIVITY_START, FLOW_ACTIVITY_FULL, flowDrive)
            val audioFlow = 0.030f +
                    tideFlow * 0.050f +
                    flowGate * (0.095f + tideFlow * 0.245f)
            layer.phase += dt * TWO_PI * MAIN_DRIFT_HZ * audioFlow * layer.speedScale
            layer.shoulderPhase -= dt * TWO_PI * SHOULDER_DRIFT_HZ *
                    (0.10f + tideFlow * 0.14f + audioFlow * 0.76f) * layer.shoulderSpeedScale
            layer.detailPhase += dt * TWO_PI * DETAIL_DRIFT_HZ *
                    (0.07f + tideFlow * 0.11f + audioFlow * 0.82f) * layer.detailSpeedScale
            layer.driftPhase += dt * TWO_PI * BASELINE_DRIFT_HZ *
                    (0.09f + tideFlow * 0.11f + presence * 0.30f) * layer.driftSpeedScale
        }

        drainImpulses()
        stepField(dt)
        updateGeneratedWaves(dt)

        var i = glimmers.size - 1
        while (i >= 0) {
            val glimmer = glimmers[i]
            glimmer.age += dt
            glimmer.x = (glimmer.x + glimmer.speed * dt).coerceIn(0.02f, 0.98f)
            if (glimmer.age >= glimmer.lifetime) {
                glimmers.removeAt(i)
            }
            i--
        }
        return dt
    }

    private fun drawWaterBody(canvas: Canvas, w: Float, h: Float) {
        val sampleCount = bodyPathSampleCount(w)
        for (i in 0 until sampleCount) {
            val x = w * i / (sampleCount - 1f)
            pathSampleX[i] = x
            pathSampleY[i] = bodySurfaceY(x / w, h).coerceIn(0f, h)
        }
        softenIsolatedPathSpikes(sampleCount, h, 1.15f)
        buildFilledCubicPath(sampleCount, w, h)

        bodyPaint.shader = bodyShader
        if (bodyShader == null) {
            bodyPaint.color = bodyColors[0]
        }
        bodyPaint.alpha = alphaToInt(0.11f + presence * 0.09f + level * 0.055f)
        canvas.drawPath(wavePath, bodyPaint)
        bodyPaint.shader = null
    }

    private fun drawLayer(canvas: Canvas, layer: WaveLayer, index: Int, w: Float, h: Float) {
        val sampleCount = layerPathSampleCount(w)
        val layerAlpha = clamp01(
            layer.alpha +
                    presence * layer.presenceAlpha +
                    waterLevel * layer.levelAlpha +
                    swell * 0.055f +
                    turbulence * layer.turbulenceAlpha
        )
        for (i in 0 until sampleCount) {
            val x = w * i / (sampleCount - 1f)
            pathSampleX[i] = x
            pathSampleY[i] = waveY(layer, x / w, w, h)
        }
        softenIsolatedPathSpikes(sampleCount, h, 1f)
        buildFilledCubicPath(sampleCount, w, h)

        wavePaint.shader = layerShaders[index]
        if (layerShaders[index] == null) {
            wavePaint.color = layerColors[index]
        }
        wavePaint.alpha = alphaToInt(layerAlpha)
        canvas.drawPath(wavePath, wavePaint)
        wavePaint.shader = null
    }

    private fun buildFilledCubicPath(sampleCount: Int, w: Float, h: Float) {
        wavePath.reset()
        wavePath.moveTo(0f, h)
        wavePath.lineTo(pathSampleX[0], pathSampleY[0])
        for (i in 0 until sampleCount - 1) {
            val x0 = pathSampleX[i]
            val y0 = pathSampleY[i]
            val x1 = pathSampleX[i + 1]
            val y1 = pathSampleY[i + 1]
            val dx = (x1 - x0) / 3f
            val tangent0 = cubicSampleTangent(i, sampleCount)
            val tangent1 = cubicSampleTangent(i + 1, sampleCount)
            wavePath.cubicTo(
                x0 + dx,
                y0 + tangent0 / 3f,
                x1 - dx,
                y1 - tangent1 / 3f,
                x1,
                y1
            )
        }
        wavePath.lineTo(w, h)
        wavePath.close()
    }

    private fun cubicSampleTangent(index: Int, sampleCount: Int): Float {
        val y = pathSampleY[index]
        val yPrev = if (index > 0) pathSampleY[index - 1] else y
        val yNext = if (index + 1 < sampleCount) pathSampleY[index + 1] else y
        val backward = y - yPrev
        val forward = yNext - y
        if (backward * forward <= 0f) return 0f
        val localLimit = max(1f, min(abs(backward), abs(forward)) * 1.45f)
        return ((backward + forward) * 0.5f).coerceIn(-localLimit, localLimit)
    }

    private fun softenIsolatedPathSpikes(sampleCount: Int, h: Float, thresholdScale: Float) {
        if (sampleCount < 5) return
        val threshold = h * (0.0048f + waveWildness() * 0.0022f) * thresholdScale
        for (i in 2 until sampleCount - 2) {
            val y = pathSampleY[i]
            val left = pathSampleY[i - 1]
            val right = pathSampleY[i + 1]
            val isLocalExtrema = y < min(left, right) || y > max(left, right)
            if (!isLocalExtrema) continue

            val wideLeft = pathSampleY[i - 2]
            val wideRight = pathSampleY[i + 2]
            val localMean = (left + right) * 0.5f
            val wideMean = (wideLeft + wideRight) * 0.5f
            val baseline = localMean * 0.78f + wideMean * 0.22f
            val deviation = y - baseline
            val localSlope = max(abs(right - left), (abs(left - wideLeft) + abs(wideRight - right)) * 0.5f)
            val allowance = threshold + localSlope * 0.55f
            val excess = abs(deviation) - allowance
            if (excess <= 0f) continue

            val sign = if (deviation >= 0f) 1f else -1f
            pathSampleY[i] = baseline + sign * (allowance + excess * 0.34f)
        }
    }

    private fun bodyPathSampleCount(w: Float): Int {
        return (w / 1.75f).roundToInt().coerceIn(180, MAX_PATH_SAMPLES)
    }

    private fun layerPathSampleCount(w: Float): Int {
        return (w / 1.55f).roundToInt().coerceIn(220, MAX_PATH_SAMPLES)
    }

    private fun drawSurfaceLife(canvas: Canvas, w: Float, h: Float) {
        if (glimmers.isEmpty()) return
        highlightPaint.color = surfaceBaseColor
        for (glimmer in glimmers) {
            val progress = (glimmer.age / glimmer.lifetime).coerceIn(0f, 1f)
            val envelope = sin(progress * PI).toFloat()
            val alpha = glimmer.alpha * envelope * (0.18f + surfaceLife * 0.82f)
            if (alpha <= 0.01f) continue
            val layer = layers[glimmer.layerIndex]
            val x = glimmer.x * w
            val y = waveY(layer, glimmer.x, w, h) + h * glimmer.verticalOffset
            val length = h * glimmer.length
            val tilt = h * glimmer.tilt
            highlightPaint.alpha = alphaToInt(alpha)
            highlightPaint.strokeWidth = max(1f, h * glimmer.stroke)
            canvas.drawLine(x - length, y - tilt, x + length, y + tilt, highlightPaint)
        }
    }

    private fun waveY(layer: WaveLayer, nx: Float, w: Float, h: Float): Float {
        val base = baseSurfaceY(h) + h * layer.verticalOffset
        val surface = base +
                h * sin(layer.driftPhase + layer.phaseOffset * 3.1f).toFloat() *
                (0.0026f + waterLevel * 0.0026f)
        val wildness = waveWildness()
        val quietBreath = 0.45f + layer.quietBias * 0.40f
        val amplitude = h * (
                layer.baseAmplitude * quietBreath +
                        layer.energyAmplitude * 0.075f +
                        waterLevel * layer.baseAmplitude * 0.18f
                )
        val amplified = amplitude * (1.00f + waterLevel * 0.05f)
        val frequency = layer.frequencyScale
        val panBend = 0f
        val main = sin(
            (nx * frequency + panBend) * TWO_PI + layer.phase + layer.phaseOffset
        ).toFloat()
        val shoulder = sin(
            (nx * (frequency * layer.shoulderScale) - panBend * 0.7f) *
                    TWO_PI + layer.shoulderPhase + layer.phaseOffset * 1.73f
        ).toFloat()
        val detail = sin(
            (nx * (DETAIL_FREQUENCY * layer.detailScale) +
                    panBend * 0.38f) * TWO_PI + layer.detailPhase + layer.phaseOffset * 2.31f
        ).toFloat()
        val interference = main * shoulder * 0.018f
        val detailGate = 0.075f
        val bodyWave = main * 0.68f +
                shoulder * 0.21f +
                detail * 0.050f * layer.detailScale * detailGate +
                interference
        val field = fieldOffset(nx, layer) * (1f + wildness * 0.08f)
        val generated = generatedWaveOffset(nx, layer, h)
        val rawY = surface + shapeAudioWave(bodyWave, wildness * 0.24f) * amplified + field + generated
        return limitDownwardFromBase(rawY, base, h, layer, wildness)
    }

    private fun tideFlowScale(): Float {
        val tide = clamp01(waterLevel * 0.78f + targetWaterLevel * 0.18f + level * 0.04f)
        return smoothStep(0.08f, 0.70f, tide)
    }

    private fun waveWildness(): Float {
        return visualWildness
    }

    private fun computeWaveWildness(): Float {
        val drive = clamp01(
            presence * 0.24f +
                    level * 0.36f +
                    swell * 0.30f +
                    turbulence * 0.30f +
                    wake * 0.24f +
                    pace * 0.18f
        )
        return smoothStep(0.36f, 0.92f, drive)
    }

    private fun shapeAudioWave(value: Float, wildness: Float): Float {
        if (wildness <= 0.001f) return value
        val v = value.coerceIn(-1.70f, 1.70f)
        return if (v < 0f) {
            val lift = 1f + wildness * 0.18f * (1f - exp(v / -0.38f))
            v * lift
        } else {
            val drop = 1f + wildness * 0.04f * (1f - exp(-v / 0.62f))
            v * drop
        }
    }

    private fun generatedWaveOffset(nx: Float, layer: WaveLayer, h: Float): Float {
        return generatedWaveOffsetRaw(nx, layer, h)
    }

    private fun generatedWaveOffsetRaw(nx: Float, layer: WaveLayer, h: Float): Float {
        var signedSum = 0f
        var mergeEnergySq = 0f
        for (wave in generatedWaves[layer.index]) {
            val offset = generatedWaveSignedOffset(nx, layer, wave, h)
            signedSum += offset
            mergeEnergySq += offset * offset
        }
        val mergeEnergy = sqrt(mergeEnergySq)
        if (abs(signedSum) <= 0.001f && mergeEnergy <= 0.001f) return 0f

        val layerDepth = layer.index / (WAVE_LAYER_COUNT - 1f)
        val wildness = waveWildness()
        val highWaveRoom = smoothStep(
            0.48f,
            1.10f,
            level * 0.44f + swell * 0.28f + wake * 0.20f + beatPulse * 0.24f
        )
        val crestLimit = h * (
                0.070f +
                        layerDepth * 0.050f +
                        wildness * 0.032f +
                        highWaveRoom * 0.044f
                )
        val constructiveCrest = if (signedSum < 0f) -signedSum else 0f
        val crestSupport = constructiveCrest * 0.82f +
                mergeEnergy * (0.16f + wildness * 0.05f)
        val crestRoom = softCapPositive(crestSupport, crestLimit)
        if (signedSum < 0f) {
            return -softCapPositive(-signedSum, crestLimit)
        }

        val troughLimit = h * (0.010f + layerDepth * 0.008f + wildness * 0.006f) +
                crestRoom * (0.20f + wildness * 0.045f)
        return softCapPositive(signedSum, troughLimit)
    }

    private fun generatedWaveSignedOffset(
        nx: Float,
        layer: WaveLayer,
        wave: GeneratedWave,
        h: Float
    ): Float {
        if (!wave.active) return 0f
        val progress = (wave.age / wave.lifetime).coerceIn(0f, 1f)
        val center = generatedWaveCenter(wave)
        val width = wave.width
        val d = nx - center
        val envelope = exp(-(d * d) / (2f * width * width))
        if (envelope <= 0.000001f) return 0f

        val rise = smoothStep(0f, wave.riseFraction, progress)
        val fall = 1f - smoothStep(wave.fallStartFraction, 1f, progress)
        val lifecycle = rise * fall
        if (lifecycle <= 0f) return 0f

        val trailingD = d + wave.direction * width * 1.20f
        val leadingD = d - wave.direction * width * 0.72f
        val trailing = exp(-(trailingD * trailingD) / (2f * width * width * 1.65f))
        val leading = exp(-(leadingD * leadingD) / (2f * width * width * 2.20f))
        val innerRipple = cos(d / width * PI.toFloat() + wave.phase).toFloat() *
                exp(-(d * d) / (2f * width * width * 2.85f))
        val layerDepth = layer.index / (WAVE_LAYER_COUNT - 1f)
        val amplitude = h * wave.strength * (0.040f + layerDepth * 0.048f) *
                (0.90f + wave.depthBias * 0.16f)

        return (
                -envelope * wave.crestWeight +
                        trailing * wave.trailingWeight +
                        leading * wave.leadingWeight +
                        innerRipple * wave.rippleWeight
                ) * amplitude * lifecycle
    }

    private fun softCapPositive(value: Float, limit: Float): Float {
        if (value <= 0f) return 0f
        val safeLimit = max(1f, limit)
        return safeLimit * kotlin.math.ln(1f + value / safeLimit)
    }

    private fun limitDownwardFromBase(
        y: Float,
        baseY: Float,
        h: Float,
        layer: WaveLayer,
        wildness: Float
    ): Float {
        val layerDepth = layer.index / (WAVE_LAYER_COUNT - 1f)
        val allowedDrop = h * (
                0.044f +
                        layerDepth * 0.016f +
                        level * 0.010f +
                        swell * 0.010f +
                        wake * 0.008f +
                        wildness * 0.014f
                ).coerceIn(0.040f, 0.092f)
        val maxY = baseY + allowedDrop
        if (y <= maxY) return y

        val softness = max(1f, h * (0.014f + wildness * 0.006f))
        return maxY + softness * (1f - exp(-(y - maxY) / softness))
    }

    private fun fieldOffset(nx: Float, layer: WaveLayer): Float {
        val position = (nx * (FIELD_N - 1) + layer.fieldShift)
            .coerceIn(0f, (FIELD_N - 1).toFloat())
        val index = position.toInt().coerceIn(0, FIELD_N - 2)
        val frac = position - index
        val sample = fieldU[index] * (1f - frac) + fieldU[index + 1] * frac
        val shaped = shapeField(sample)
        return -shaped * layer.fieldScale
    }

    private fun shapeField(value: Float): Float {
        return if (value >= 0f) {
            val crestSoft = 14f * density
            value * (1f + 0.10f * (1f - exp(-value / crestSoft)))
        } else {
            val troughSoft = 12f * density
            value * (0.55f + 0.45f * exp(value / troughSoft))
        }
    }

    private fun beatBreath(): Float {
        if (tempoConfidence < BEAT_BREATH_MIN_CONFIDENCE) return 0f
        val active = smoothStep(0.18f, 0.72f, presence * 0.44f + pace * 0.46f + swell * 0.22f)
        if (active <= 0f) return 0f
        return cos((visualBeatPhase - BEAT_LEAD) * TWO_PI).toFloat() *
                BEAT_BREATH_DEPTH * tempoConfidence * active
    }

    private fun enqueueImpulse(impulse: QueuedImpulse) {
        synchronized(impulseQueue) {
            while (impulseQueue.size >= IMPULSE_QUEUE_LIMIT) {
                impulseQueue.removeFirst()
            }
            impulseQueue.addLast(impulse)
        }
    }

    private fun drainImpulses() {
        while (true) {
            val impulse = synchronized(impulseQueue) {
                if (impulseQueue.isEmpty()) null else impulseQueue.removeFirst()
            } ?: break
            spawnGeneratedWave(impulse)
            injectFieldImpulse(impulse)
        }
    }

    private fun spawnGeneratedWave(impulse: QueuedImpulse) {
        val strength = clamp01(impulse.strength)
        if (strength < GENERATED_WAVE_MIN_STRENGTH) return
        spawnGeneratedWaveCore(impulse, strength, secondary = false)

        val secondaryDrive = strength + impulse.beatPulse * 0.56f + impulse.pace * 0.28f
        if (secondaryDrive > SECONDARY_GENERATED_WAVE_TRIGGER) {
            val secondaryStrength = (strength * 0.70f + impulse.beatPulse * 0.20f + impulse.pace * 0.10f)
                .coerceIn(GENERATED_WAVE_MIN_STRENGTH, 1f)
            spawnGeneratedWaveCore(impulse, secondaryStrength, secondary = true)
        }
    }

    private fun spawnGeneratedWaveCore(impulse: QueuedImpulse, rawStrength: Float, secondary: Boolean) {
        val strength = clamp01(rawStrength)
        val slot = nextGeneratedWaveSlot() ?: return
        val layerDepth = slot.layerIndex / (WAVE_LAYER_COUNT - 1f)
        val direction = if (
            (impulseSequence + slot.layerIndex + slot.waveIndex + if (secondary) 1 else 0) % 2 == 0
        ) 1f else -1f
        val panShift = impulse.stereoPan * 0.18f
        val brightness = clamp01(impulse.brightness)
        val secondaryScale = if (secondary) 0.74f else 1f
        val width = (
                0.112f +
                        layerDepth * 0.018f +
                        strength * 0.024f -
                        brightness * 0.010f +
                        randomOffset(0.018f)
                ).coerceIn(0.090f, 0.184f)
        val originJitter = if (secondary) 0.34f else 0.27f
        val candidateOrigin = (0.50f + panShift + randomOffset(originJitter)).coerceIn(0.10f, 0.90f)
        val origin = settleGeneratedWaveOrigin(slot.layerIndex, candidateOrigin, direction, width)
        val birthFlow = tideFlowScale()
        generatedWaves[slot.layerIndex][slot.waveIndex] = GeneratedWave(
            active = true,
            age = 0f,
            lifetime = (
                    1.02f +
                            (1f - impulse.pace) * 0.28f +
                            strength * 0.16f +
                            random.nextFloat() * 0.18f +
                            if (secondary) -0.10f else 0f
                    ).coerceIn(0.78f, 1.56f),
            origin = origin,
            direction = direction,
            speed = (
                    0.28f +
                            strength * 0.24f +
                            impulse.pace * 0.28f +
                            impulse.beatPulse * 0.08f +
                            randomOffset(0.040f)
                    ).coerceIn(0.24f, 0.82f) * (0.58f + birthFlow * 0.42f),
            width = width,
            strength = (
                    0.82f +
                            strength * 1.10f +
                            impulse.beatPulse * 0.42f +
                            impulse.pace * 0.14f +
                            layerDepth * 0.16f
                    ).coerceIn(0.50f, 2.08f) * secondaryScale,
            phase = random.nextFloat() * TWO_PI,
            riseFraction = (0.135f + random.nextFloat() * 0.070f).coerceIn(0.12f, 0.24f),
            fallStartFraction = (0.50f + random.nextFloat() * 0.16f).coerceIn(0.48f, 0.70f),
            crestWeight = 0.96f + random.nextFloat() * 0.28f,
            trailingWeight = 0.30f + random.nextFloat() * 0.22f,
            leadingWeight = 0.10f + random.nextFloat() * 0.09f,
            rippleWeight = 0.060f + random.nextFloat() * 0.085f,
            depthBias = layerDepth
        )
    }

    private fun nextGeneratedWaveSlot(): GeneratedWaveSlot? {
        var fallback: GeneratedWaveSlot? = null
        var fallbackVisibility = Float.MAX_VALUE
        for (step in 0 until WAVE_LAYER_COUNT) {
            val layerIndex = (generatedWaveCursor + step) % WAVE_LAYER_COUNT
            val layerWaves = generatedWaves[layerIndex]
            for (waveIndex in layerWaves.indices) {
                val wave = layerWaves[waveIndex]
                if (!wave.active) {
                    generatedWaveCursor = (layerIndex + 1) % WAVE_LAYER_COUNT
                    return GeneratedWaveSlot(layerIndex, waveIndex)
                }
                val visibility = generatedWaveVisibility(wave)
                if (visibility < fallbackVisibility) {
                    fallbackVisibility = visibility
                    fallback = GeneratedWaveSlot(layerIndex, waveIndex)
                }
            }
        }
        val slot = fallback ?: return null
        if (fallbackVisibility > GENERATED_REPLACE_VISIBILITY_LIMIT) {
            return null
        }
        generatedWaveCursor = (slot.layerIndex + 1) % WAVE_LAYER_COUNT
        return slot
    }

    private fun settleGeneratedWaveOrigin(
        layerIndex: Int,
        candidateOrigin: Float,
        direction: Float,
        width: Float
    ): Float {
        var origin = candidateOrigin
        repeat(2) {
            var adjusted = false
            val leadingShoulder = origin + direction * width * 0.72f
            val trailingShoulder = origin - direction * width * 1.20f
            for (wave in generatedWaves[layerIndex]) {
                if (!wave.active || generatedWaveVisibility(wave) < 0.12f) continue
                val center = generatedWaveCenter(wave)
                val leadingHitsCrest = abs(leadingShoulder - center) < width * 0.56f
                val trailingHitsCrest = abs(trailingShoulder - center) < width * 0.62f
                if (leadingHitsCrest || trailingHitsCrest) {
                    origin = (origin + direction * width * 0.66f).coerceIn(0.08f, 0.92f)
                    adjusted = true
                    break
                }
            }
            if (!adjusted) return origin
        }
        return origin
    }

    private fun generatedWaveCenter(wave: GeneratedWave): Float {
        return wave.origin + wave.direction * wave.speed * wave.age
    }

    private fun generatedWaveVisibility(wave: GeneratedWave): Float {
        if (!wave.active) return 0f
        val progress = (wave.age / wave.lifetime).coerceIn(0f, 1f)
        val rise = smoothStep(0f, wave.riseFraction, progress)
        val fall = 1f - smoothStep(wave.fallStartFraction, 1f, progress)
        val center = generatedWaveCenter(wave)
        val inside = smoothStep(-0.28f, -0.08f, center) *
                (1f - smoothStep(1.08f, 1.28f, center))
        return rise * fall * inside
    }

    private fun injectFieldImpulse(impulse: QueuedImpulse) {
        if (width <= 0 || height <= 0) return

        var sumSq = 0f
        for (i in 0 until FIELD_N) {
            sumSq += fieldU[i] * fieldU[i]
        }
        val fieldRms = sqrt(sumSq / FIELD_N)
        val refRms = FIELD_RMS_REF_DP * density
        val energyBudget = refRms / (refRms + fieldRms)

        val brightnessDrive = clamp01(impulse.brightness)
        val tideFlow = tideFlowScale()
        val impulseDirection = if (impulseSequence % 2 == 0) 1f else -1f
        val sequenceOffset = IMPULSE_SEQUENCE_OFFSETS[
            impulseSequence % IMPULSE_SEQUENCE_OFFSETS.size
        ] * (0.68f + brightnessDrive * 0.62f)
        var x = 0.5f +
                impulse.stereoPan * 0.18f +
                sequenceOffset +
                (brightnessDrive - 0.5f) * 0.05f
        x = x.coerceIn(0.08f, 0.92f)
        if (abs(x - lastImpulseX) < 0.055f) {
            x = (x + if (x < 0.5f) 0.095f else -0.095f).coerceIn(0.08f, 0.92f)
        }
        lastImpulseX = x
        impulseSequence++

        val kernel = FIELD_KERNEL_WIDE +
                (FIELD_KERNEL_NARROW - FIELD_KERNEL_WIDE) * brightnessDrive
        val velocity = FIELD_IMPULSE_VELOCITY_DP * density *
                (0.32f + clamp01(impulse.strength) * 0.78f) *
                (1.06f - brightnessDrive * 0.18f) *
                (0.64f + presence * 0.36f) *
                (0.68f + tideFlow * 0.32f) *
                (1f + waveWildness() * 0.42f) *
                energyBudget
        injectGaussianVelocity(x, kernel, velocity)
        val troughX = (x - impulseDirection * (0.038f + brightnessDrive * 0.028f + impulse.pace * 0.014f))
            .coerceIn(0.08f, 0.92f)
        injectGaussianVelocity(
            troughX,
            kernel * FIELD_TROUGH_KERNEL_SCALE,
            -velocity * FIELD_TROUGH_VELOCITY_SCALE
        )

        if (impulse.beatPulse > 0.64f || impulse.wake > 0.74f) {
            val secondaryX = (x + if (x < 0.5f) 0.12f else -0.12f).coerceIn(0.08f, 0.92f)
            injectGaussianVelocity(secondaryX, kernel * 0.62f, velocity * 0.28f)
        }
        removeVelocityMean()
    }

    private fun injectGaussianVelocity(x: Float, kernel: Float, velocity: Float) {
        val center = x * (FIELD_N - 1)
        val reach = (kernel * 3f).roundToInt()
        val from = max(1, (center - reach).toInt())
        val to = min(FIELD_N - 2, (center + reach).toInt())
        for (i in from..to) {
            val d = (i - center) / kernel
            fieldV[i] += velocity * exp(-d * d)
        }
    }

    private fun removeVelocityMean() {
        var sum = 0f
        for (i in 1 until FIELD_N - 1) {
            sum += fieldV[i]
        }
        val mean = sum / (FIELD_N - 2)
        for (i in 1 until FIELD_N - 1) {
            fieldV[i] -= mean * FIELD_MEAN_RESTORE
        }
    }

    private fun stepField(dt: Float) {
        var remaining = dt.coerceAtMost(0.050f)
        val waveSpeedSquared = FIELD_WAVE_SPEED * FIELD_WAVE_SPEED
        while (remaining > 1e-5f) {
            val subStep = min(remaining, FIELD_SUB_DT)
            remaining -= subStep
            val velocityDamping = exp(-subStep / FIELD_DAMP_TAU)
            val heightLeak = exp(-subStep / FIELD_LEAK_TAU)

            for (i in 1 until FIELD_N - 1) {
                val laplacian = fieldU[i - 1] + fieldU[i + 1] - 2f * fieldU[i]
                fieldV[i] = (fieldV[i] + waveSpeedSquared * laplacian * subStep) *
                        velocityDamping
            }
            for (i in 1 until FIELD_N - 1) {
                fieldTmp[i] = fieldV[i] +
                        FIELD_VISCOSITY * (fieldV[i - 1] + fieldV[i + 1] - 2f * fieldV[i])
            }
            for (i in 1 until FIELD_N - 1) {
                fieldV[i] = fieldTmp[i]
                fieldU[i] = (fieldU[i] + fieldV[i] * subStep) * heightLeak
            }
            fieldU[0] = fieldU[1] * FIELD_EDGE_ABSORB
            fieldU[FIELD_N - 1] = fieldU[FIELD_N - 2] * FIELD_EDGE_ABSORB
            fieldV[0] = 0f
            fieldV[FIELD_N - 1] = 0f
        }
    }

    private fun updateGeneratedWaves(dt: Float) {
        for (layerIndex in generatedWaves.indices) {
            for (waveIndex in generatedWaves[layerIndex].indices) {
                val wave = generatedWaves[layerIndex][waveIndex]
                if (!wave.active) continue
                wave.age += dt
                val center = generatedWaveCenter(wave)
                if (wave.age >= wave.lifetime || center < -0.36f || center > 1.36f) {
                    generatedWaves[layerIndex][waveIndex] = GeneratedWave.inactive()
                }
            }
        }
    }

    private fun waveSpawnIntervalMs(pace: Float, beatPulse: Float, drive: Float): Long {
        val energy = clamp01(pace * 0.62f + beatPulse * 0.22f + drive * 0.24f)
        val interval = WAVE_SPAWN_INTERVAL_SLOW_MS -
                (WAVE_SPAWN_INTERVAL_SLOW_MS - WAVE_SPAWN_INTERVAL_FAST_MS) * energy
        return interval.roundToInt().toLong().coerceIn(
            MIN_WAVE_SPAWN_INTERVAL_MS,
            WAVE_SPAWN_INTERVAL_SLOW_MS.toLong()
        )
    }

    private fun baseSurfaceY(h: Float): Float {
        return h * (0.724f - waterLevel * 0.202f)
    }

    private fun bodySurfaceY(nx: Float, h: Float): Float {
        val base = baseSurfaceY(h) - h * (0.038f + waterLevel * 0.018f)
        val amplitude = h * (0.0065f + waterLevel * 0.0060f)
        val first = sin(nx * 1.22f * TWO_PI +
                layers[0].phase * 0.42f + layers[0].phaseOffset).toFloat()
        val second = sin(nx * TWO_PI * 2.05f +
                layers[2].shoulderPhase * 0.26f + layers[1].phaseOffset).toFloat()
        return base + first * amplitude * 0.72f + second * amplitude * 0.28f
    }

    private fun spawnGlimmers(frame: RecordingWaveDriveFrame) {
        if (width <= 0 || height <= 0) return
        val chance = frame.surfaceLife * (0.12f + frame.shimmer * 0.12f)
        if (random.nextFloat() > chance) return
        while (glimmers.size >= MAX_GLIMMERS) {
            glimmers.removeAt(0)
        }
        val count = if (frame.surfaceLife > 0.72f && random.nextFloat() < 0.18f) 2 else 1
        for (i in 0 until count) {
            val layerIndex = (WAVE_LAYER_COUNT - 1 - random.nextInt(3)).coerceIn(0, WAVE_LAYER_COUNT - 1)
            glimmers.add(
                SurfaceGlimmer(
                    x = (random.nextFloat() * 0.90f + 0.05f + frame.stereoPan * 0.08f)
                        .coerceIn(0.03f, 0.97f),
                    age = 0f,
                    lifetime = 0.46f + random.nextFloat() * 0.48f,
                    speed = (random.nextFloat() - 0.5f) * (0.035f + frame.pace * 0.075f),
                    alpha = 0.050f + random.nextFloat() * 0.100f + frame.shimmer * 0.075f,
                    length = 0.020f + random.nextFloat() * 0.028f + frame.brightnessWeight * 0.012f,
                    stroke = 0.0010f + random.nextFloat() * 0.0009f,
                    tilt = (random.nextFloat() - 0.5f) * 0.004f,
                    verticalOffset = (random.nextFloat() - 0.5f) * 0.010f,
                    layerIndex = layerIndex
                )
            )
        }
    }

    private fun createLayer(index: Int): WaveLayer {
        val rank = index / (WAVE_LAYER_COUNT - 1f)
        val phaseOffset = LAYER_PHASE_OFFSET[index] + randomOffset(0.22f)
        val alphaJitter = randomOffset(0.010f + rank * 0.006f)
        val verticalOffset = LAYER_VERTICAL_OFFSET[index] + randomOffset(0.0026f)
        val fieldShift = max(0f, LAYER_FIELD_SHIFT[index] + randomOffset(4.8f))
        val fieldScale = LAYER_FIELD_SCALE[index] * randomScale(0.085f)
        val baseAmplitude = LAYER_BASE_AMPLITUDE[index] * randomScale(0.055f)
        val energyAmplitude = LAYER_ENERGY_AMPLITUDE[index] * randomScale(0.075f)
        val wakeAmplitude = LAYER_WAKE_AMPLITUDE[index] * randomScale(0.105f)
        val frequencyScale = LAYER_FREQUENCY_SCALE[index] * randomScale(0.040f)
        val shoulderScale = LAYER_SHOULDER_SCALE[index] * randomScale(0.050f)
        val detailScale = LAYER_DETAIL_SCALE[index] * randomScale(0.080f)
        val speedScale = randomScale(0.055f)
        val driftPhaseOffset = randomOffset(0.28f)
        return WaveLayer(
            index = index,
            phaseOffset = phaseOffset,
            verticalOffset = verticalOffset,
            alpha = clamp01(LAYER_ALPHA[index] + alphaJitter),
            presenceAlpha = LAYER_PRESENCE_ALPHA[index],
            levelAlpha = LAYER_LEVEL_ALPHA[index],
            turbulenceAlpha = LAYER_TURBULENCE_ALPHA[index],
            baseAmplitude = baseAmplitude,
            energyAmplitude = energyAmplitude,
            wakeAmplitude = wakeAmplitude,
            frequencyScale = frequencyScale,
            shoulderScale = shoulderScale,
            detailScale = detailScale,
            speedScale = LAYER_SPEED_SCALE[index] * speedScale,
            shoulderSpeedScale = LAYER_SHOULDER_SPEED_SCALE[index] * randomScale(0.060f),
            detailSpeedScale = LAYER_DETAIL_SPEED_SCALE[index] * randomScale(0.070f),
            driftSpeedScale = LAYER_DRIFT_SPEED_SCALE[index] * randomScale(0.050f),
            swellBias = LAYER_SWELL_BIAS[index],
            bassBias = LAYER_BASS_BIAS[index],
            voiceBias = LAYER_VOICE_BIAS[index],
            brightnessBias = LAYER_BRIGHTNESS_BIAS[index],
            turbulenceBias = LAYER_TURBULENCE_BIAS[index],
            quietBias = LAYER_QUIET_BIAS[index],
            fieldScale = fieldScale,
            fieldShift = fieldShift,
            panBias = LAYER_PAN_BIAS[index],
            phase = phaseOffset + randomOffset(0.18f),
            shoulderPhase = LAYER_SHOULDER_PHASE[index] + randomOffset(0.24f),
            detailPhase = LAYER_DETAIL_PHASE[index] + randomOffset(0.30f),
            driftPhase = LAYER_DRIFT_PHASE[index] + driftPhaseOffset
        )
    }

    private fun randomOffset(amount: Float): Float {
        return (random.nextFloat() * 2f - 1f) * amount
    }

    private fun randomScale(amount: Float): Float {
        return 1f + randomOffset(amount)
    }

    private fun rebuildColors(w: Int, h: Int) {
        val width = max(1, w)
        val height = max(1, h)
        val bodyStart = tone(thingBackground.color, 0.02f)
        val bodyEndBase = if (thingBackground.mode === ThingBackground.Mode.GRADIENT) {
            thingBackground.endColor
        } else {
            thingBackground.color
        }
        val bodyEnd = tone(bodyEndBase, 0.08f)
        bodyColors[0] = bodyStart
        bodyColors[1] = bodyEnd
        bodyShader = if (thingBackground.mode === ThingBackground.Mode.GRADIENT) {
            val p = gradientPoints(width.toFloat(), height.toFloat(), thingBackground.orientation)
            LinearGradient(p[0], p[1], p[2], p[3], bodyStart, bodyEnd, Shader.TileMode.CLAMP)
        } else {
            null
        }

        for (i in 0 until WAVE_LAYER_COUNT) {
            val amount = LAYER_TONE_AMOUNT[i]
            val start = tone(thingBackground.color, amount)
            val endBase = if (thingBackground.mode === ThingBackground.Mode.GRADIENT) {
                thingBackground.endColor
            } else {
                thingBackground.color
            }
            val end = tone(endBase, amount + LAYER_END_TONE_DELTA)
            layerColors[i] = start
            layerShaders[i] = if (thingBackground.mode === ThingBackground.Mode.GRADIENT) {
                val p = gradientPoints(width.toFloat(), height.toFloat(), thingBackground.orientation)
                LinearGradient(p[0], p[1], p[2], p[3], start, end, Shader.TileMode.CLAMP)
            } else {
                null
            }
        }
        surfaceBaseColor = opaqueRgb(BackgroundUtil.onColor(thingBackground, 1f))
    }

    private fun tone(color: Int, amount: Float): Int {
        return if (backgroundIsLight) {
            BackgroundUtil.darker(color, 0.13f + amount * 1.15f)
        } else {
            BackgroundUtil.lighter(color, amount * 0.45f)
        }
    }

    private fun gradientPoints(w: Float, h: Float, orientation: ThingBackground.Orientation): FloatArray {
        val cx = w * 0.5f
        val cy = h * 0.5f
        return when (orientation) {
            ThingBackground.Orientation.L_R -> floatArrayOf(0f, cy, w, cy)
            ThingBackground.Orientation.T_B -> floatArrayOf(cx, 0f, cx, h)
            ThingBackground.Orientation.LT_RB -> floatArrayOf(0f, 0f, w, h)
            ThingBackground.Orientation.RT_LB -> floatArrayOf(w, 0f, 0f, h)
            ThingBackground.Orientation.LB_RT -> floatArrayOf(0f, h, w, 0f)
            ThingBackground.Orientation.RB_LT -> floatArrayOf(w, h, 0f, 0f)
            ThingBackground.Orientation.R_L -> floatArrayOf(w, cy, 0f, cy)
            ThingBackground.Orientation.B_T -> floatArrayOf(cx, h, cx, 0f)
        }
    }

    private fun approach(current: Float, target: Float, dt: Float, attackTau: Float, releaseTau: Float): Float {
        val tau = if (target > current) attackTau else releaseTau
        return current + (target - current) * (1f - exp(-dt / tau))
    }

    private fun stableUnitTarget(current: Float, incoming: Float, deadband: Float): Float {
        val target = clamp01(incoming)
        if (target <= TARGET_ZERO_EPSILON) return 0f
        return if (abs(target - current) >= deadband) target else current
    }

    private fun stableSignedTarget(current: Float, incoming: Float, deadband: Float): Float {
        val target = incoming.coerceIn(-1f, 1f)
        if (abs(target) <= TARGET_ZERO_EPSILON) return 0f
        return if (abs(target - current) >= deadband) target else current
    }

    private fun stableScalarTarget(
        current: Float,
        incoming: Float,
        deadband: Float,
        zeroThreshold: Float
    ): Float {
        if (incoming <= zeroThreshold) return 0f
        return if (abs(incoming - current) >= deadband) incoming else current
    }

    private fun stablePhaseTarget(current: Float, incoming: Float, deadband: Float): Float {
        var target = incoming % 1f
        if (target < 0f) target += 1f
        var diff = target - current
        if (diff > 0.5f) diff -= 1f
        if (diff < -0.5f) diff += 1f
        return if (abs(diff) >= deadband) target else current
    }

    private fun smoothStep(start: Float, end: Float, value: Float): Float {
        if (end <= start) return if (value >= end) 1f else 0f
        val t = clamp01((value - start) / (end - start))
        return t * t * (3f - 2f * t)
    }

    private fun alphaToInt(alpha: Float): Int {
        return (clamp01(alpha) * 255f).roundToInt().coerceIn(0, 255)
    }

    private fun opaqueRgb(color: Int): Int {
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun clamp01(value: Float): Float {
        if (value < 0f) return 0f
        if (value > 1f) return 1f
        return value
    }

    private data class WaveLayer(
        val index: Int,
        val phaseOffset: Float,
        val verticalOffset: Float,
        val alpha: Float,
        val presenceAlpha: Float,
        val levelAlpha: Float,
        val turbulenceAlpha: Float,
        val baseAmplitude: Float,
        val energyAmplitude: Float,
        val wakeAmplitude: Float,
        val frequencyScale: Float,
        val shoulderScale: Float,
        val detailScale: Float,
        val speedScale: Float,
        val shoulderSpeedScale: Float,
        val detailSpeedScale: Float,
        val driftSpeedScale: Float,
        val swellBias: Float,
        val bassBias: Float,
        val voiceBias: Float,
        val brightnessBias: Float,
        val turbulenceBias: Float,
        val quietBias: Float,
        val fieldScale: Float,
        val fieldShift: Float,
        val panBias: Float,
        var phase: Float,
        var shoulderPhase: Float,
        var detailPhase: Float,
        var driftPhase: Float
    )

    private data class SurfaceGlimmer(
        var x: Float,
        var age: Float,
        val lifetime: Float,
        val speed: Float,
        val alpha: Float,
        val length: Float,
        val stroke: Float,
        val tilt: Float,
        val verticalOffset: Float,
        val layerIndex: Int
    )

    private data class QueuedImpulse(
        val strength: Float,
        val brightness: Float,
        val beatPulse: Float,
        val wake: Float,
        val pace: Float,
        val stereoPan: Float
    )

    private data class GeneratedWaveSlot(
        val layerIndex: Int,
        val waveIndex: Int
    )

    private data class GeneratedWave(
        val active: Boolean,
        var age: Float,
        val lifetime: Float,
        val origin: Float,
        val direction: Float,
        val speed: Float,
        val width: Float,
        val strength: Float,
        val phase: Float,
        val riseFraction: Float,
        val fallStartFraction: Float,
        val crestWeight: Float,
        val trailingWeight: Float,
        val leadingWeight: Float,
        val rippleWeight: Float,
        val depthBias: Float
    ) {
        companion object {
            fun inactive(): GeneratedWave {
                return GeneratedWave(
                    active = false,
                    age = 0f,
                    lifetime = 1f,
                    origin = 0.5f,
                    direction = 1f,
                    speed = 0f,
                    width = 0.12f,
                    strength = 0f,
                    phase = 0f,
                    riseFraction = 0.22f,
                    fallStartFraction = 0.52f,
                    crestWeight = 0.90f,
                    trailingWeight = 0.28f,
                    leadingWeight = 0.12f,
                    rippleWeight = 0.05f,
                    depthBias = 0f
                )
            }
        }
    }

    companion object {
        private const val WAVE_LAYER_COUNT = 6
        private const val GENERATED_WAVES_PER_LAYER = 4
        private const val MAX_GLIMMERS = 28
        private const val MAX_PATH_SAMPLES = 560
        private val TWO_PI = (Math.PI * 2.0).toFloat()

        private const val FIELD_N = 196
        private const val FIELD_WAVE_SPEED = 158f
        private const val FIELD_SUB_DT = 0.004f
        private const val FIELD_DAMP_TAU = 0.62f
        private const val FIELD_VISCOSITY = 0.15f
        private const val FIELD_LEAK_TAU = 2.4f
        private const val FIELD_EDGE_ABSORB = 0.94f
        private const val FIELD_RMS_REF_DP = 10f
        private const val FIELD_IMPULSE_VELOCITY_DP = 302f
        private const val FIELD_KERNEL_WIDE = 24f
        private const val FIELD_KERNEL_NARROW = 9f
        private const val FIELD_TROUGH_KERNEL_SCALE = 1.42f
        private const val FIELD_TROUGH_VELOCITY_SCALE = 0.22f
        private const val FIELD_MEAN_RESTORE = 0.34f
        private const val IMPULSE_QUEUE_LIMIT = 8

        private const val SURGE_RISE_TRIGGER = 0.048f
        private const val GENERATED_WAVE_MIN_STRENGTH = 0.040f
        private const val SECONDARY_GENERATED_WAVE_TRIGGER = 0.72f
        private const val PERIODIC_WAVE_DRIVE_START = 0.285f
        private const val MIN_WAVE_SPAWN_INTERVAL_MS = 90L
        private const val WAVE_SPAWN_INTERVAL_FAST_MS = 96f
        private const val WAVE_SPAWN_INTERVAL_SLOW_MS = 360f
        private const val GENERATED_REPLACE_VISIBILITY_LIMIT = 0.105f

        private const val TARGET_ZERO_EPSILON = 0.001f
        private const val TARGET_DEADBAND_CORE = 0.018f
        private const val TARGET_DEADBAND_WATER = 0.015f
        private const val TARGET_DEADBAND_TEXTURE = 0.026f
        private const val TARGET_DEADBAND_WAKE = 0.030f
        private const val TARGET_DEADBAND_TONE = 0.024f
        private const val TARGET_DEADBAND_PAN = 0.020f
        private const val TARGET_DEADBAND_EVENT = 0.035f
        private const val TARGET_DEADBAND_PHASE = 0.025f
        private const val TARGET_DEADBAND_TEMPO_BPM = 2.0f

        private const val FLOW_ACTIVITY_START = 0.12f
        private const val FLOW_ACTIVITY_FULL = 0.72f
        private const val BEAT_BREATH_MIN_CONFIDENCE = 0.35f
        private const val BEAT_BREATH_DEPTH = 0.055f
        private const val BEAT_LEAD = 0.12f
        private const val BEAT_PHASE_PULL_SEC = 0.50f

        private const val MAIN_DRIFT_HZ = 0.090f
        private const val SHOULDER_DRIFT_HZ = 0.135f
        private const val DETAIL_DRIFT_HZ = 0.220f
        private const val BASELINE_DRIFT_HZ = 0.038f
        private const val DETAIL_FREQUENCY = 2.72f

        private val IMPULSE_SEQUENCE_OFFSETS = floatArrayOf(
            -0.120f, 0.105f, 0.205f, -0.215f, 0.040f, -0.050f, 0.155f, -0.165f
        )

        private val LAYER_VERTICAL_OFFSET = floatArrayOf(
            -0.058f, -0.039f, -0.022f, -0.006f, 0.011f, 0.028f
        )
        private val LAYER_PHASE_OFFSET = floatArrayOf(
            0.00f, 0.64f, -0.47f, 1.06f, -0.83f, 1.43f
        )
        private val LAYER_SHOULDER_PHASE = floatArrayOf(
            1.18f, 2.03f, 0.42f, 2.71f, 1.55f, 3.24f
        )
        private val LAYER_DETAIL_PHASE = floatArrayOf(
            2.42f, 0.75f, 3.18f, 1.36f, 2.86f, 0.20f
        )
        private val LAYER_DRIFT_PHASE = floatArrayOf(
            0.35f, 1.62f, 2.78f, 0.94f, 2.15f, 3.42f
        )
        private val LAYER_ALPHA = floatArrayOf(
            0.12f, 0.18f, 0.27f, 0.39f, 0.54f, 0.72f
        )
        private val LAYER_PRESENCE_ALPHA = floatArrayOf(
            0.05f, 0.07f, 0.10f, 0.13f, 0.18f, 0.24f
        )
        private val LAYER_LEVEL_ALPHA = floatArrayOf(
            0.03f, 0.04f, 0.05f, 0.07f, 0.09f, 0.12f
        )
        private val LAYER_TURBULENCE_ALPHA = floatArrayOf(
            0.016f, 0.022f, 0.030f, 0.040f, 0.050f, 0.060f
        )
        private val LAYER_BASE_AMPLITUDE = floatArrayOf(
            0.0068f, 0.0074f, 0.0082f, 0.0088f, 0.0094f, 0.0102f
        )
        private val LAYER_ENERGY_AMPLITUDE = floatArrayOf(
            0.021f, 0.024f, 0.028f, 0.033f, 0.038f, 0.044f
        )
        private val LAYER_WAKE_AMPLITUDE = floatArrayOf(
            0.0040f, 0.0060f, 0.0090f, 0.0130f, 0.0180f, 0.0240f
        )
        private val LAYER_FREQUENCY_SCALE = floatArrayOf(
            0.86f, 0.95f, 1.04f, 1.13f, 1.22f, 1.31f
        )
        private val LAYER_SHOULDER_SCALE = floatArrayOf(
            1.48f, 1.55f, 1.63f, 1.73f, 1.84f, 1.96f
        )
        private val LAYER_DETAIL_SCALE = floatArrayOf(
            0.26f, 0.36f, 0.50f, 0.66f, 0.84f, 1.00f
        )
        private val LAYER_SPEED_SCALE = floatArrayOf(
            0.86f, 0.93f, 0.99f, 1.06f, 1.13f, 1.20f
        )
        private val LAYER_SHOULDER_SPEED_SCALE = floatArrayOf(
            0.82f, 0.90f, 1.00f, 1.08f, 1.15f, 1.24f
        )
        private val LAYER_DETAIL_SPEED_SCALE = floatArrayOf(
            0.62f, 0.72f, 0.84f, 0.96f, 1.10f, 1.24f
        )
        private val LAYER_DRIFT_SPEED_SCALE = floatArrayOf(
            0.70f, 0.82f, 0.94f, 1.06f, 1.18f, 1.30f
        )
        private val LAYER_SWELL_BIAS = floatArrayOf(
            0.18f, 0.22f, 0.27f, 0.32f, 0.38f, 0.44f
        )
        private val LAYER_BASS_BIAS = floatArrayOf(
            0.54f, 0.50f, 0.45f, 0.40f, 0.34f, 0.30f
        )
        private val LAYER_VOICE_BIAS = floatArrayOf(
            0.20f, 0.24f, 0.30f, 0.36f, 0.42f, 0.48f
        )
        private val LAYER_BRIGHTNESS_BIAS = floatArrayOf(
            0.12f, 0.17f, 0.23f, 0.30f, 0.38f, 0.46f
        )
        private val LAYER_TURBULENCE_BIAS = floatArrayOf(
            0.06f, 0.09f, 0.13f, 0.18f, 0.24f, 0.31f
        )
        private val LAYER_QUIET_BIAS = floatArrayOf(
            0.74f, 0.68f, 0.60f, 0.52f, 0.45f, 0.38f
        )
        private val LAYER_FIELD_SCALE = floatArrayOf(
            0.12f, 0.20f, 0.34f, 0.52f, 0.76f, 1.00f
        )
        private val LAYER_FIELD_SHIFT = floatArrayOf(
            32f, 24f, 16f, 9f, 4f, 0f
        )
        private val LAYER_PAN_BIAS = floatArrayOf(
            -0.24f, 0.18f, -0.10f, 0.14f, -0.08f, 0.06f
        )
        private val LAYER_TONE_AMOUNT = floatArrayOf(
            0.130f, 0.105f, 0.080f, 0.055f, 0.025f, 0.000f
        )
        private const val LAYER_END_TONE_DELTA = 0.014f
    }
}
