package com.ywwynm.everythingdone.views.recording

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil

import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 录音对话框里的 Voice Waveform（见 CONTEXT.md）：一片由被录音记事的 ThingBackground 派生
 * 配色的"水体"——从底边向上填充，水面是多层波浪；麦克风音量越大，水位越高、浪越大。
 *
 * 为避免"一声大声音下几个波峰同时、等高升起"的程序化感，每层水面不是单条正弦，而是多条
 * （当前 5 条，对应 low / lowMid / mid / high / air 频段）不同空间频率 / 漂移速度 / 相位的
 * **波分量**叠加（准周期干涉）：
 * - 空间灵动：多分量干涉使波峰高低天然参差、不等高，且随时间不断此消彼长；
 * - 时间灵动：每个分量各自独立跟随音量（缓动时间常数不同——高频细纹快、低频大浪慢，且有
 *   连续慢速随机包络），一声大声音来时是细纹先起、大浪后涌，而非整条线齐刷刷弹起。
 * - 个性化响应：每层 / 每分量都有不同的延迟、attack、release 与局部浪涌敏感度，同一个声音
 *   事件会被错峰地转译成不同层的先后起伏。
 *
 * 波形结构参数（各分量的频率 / 速度 / 相位 / 时间常数 / 权重，各层的振幅尺度 / 颜色 / 微漂）
 * 在**每个实例创建时用无种子随机现场生成**——每次打开录音，波浪分布都不同，更野。
 *
 * 由 [AudioRecorder] 约每 20ms 通过 [receive] 推入 [VoiceAudioFrame]，此处只更新缓动目标；
 * 真正的绘制由自驱动帧循环（onDraw 末尾 postInvalidateOnAnimation）完成。
 *
 * Created by tyorikan on 2015/06/08 (bar visualizer).
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Rewritten as a layered water-wave visualizer by ywwynm and Claude Opus 4.8 on 2026/7/2.
 */
open class VoiceVisualizer(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    // 记事背景（颜色身份来源）与兜底纯色
    private var mBackground: ThingBackground? = null
    private var mFallbackColor: Int = Color.BLACK

    // 每层画笔（宽度已知时构建；渐变记事用横向 LinearGradient）
    private val mLayerPaints: Array<Paint> = Array(LAYERS) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    }
    private var mPaintsReady: Boolean = false

    // 输入由后台线程 receive 写入（volatile 引用整体换新，保证发布可见）；
    // 主线程先把 incoming 经过每分量的延迟/attack/release 转成 target，再缓动到 current。
    @Volatile private var mIncomingMotion: MotionTargets =
        MotionTargets(Array(LAYERS) { FloatArray(COMPONENTS) }, 0f, 0f, 0)
    private val mTargetComp: Array<FloatArray> = Array(LAYERS) { FloatArray(COMPONENTS) }
    private val mCurrentComp: Array<FloatArray> = Array(LAYERS) { FloatArray(COMPONENTS) }
    private var mTargetLevel: Float = 0f
    private var mCurrentLevel: Float = 0f
    private val mLayerPendingSurge: FloatArray = FloatArray(LAYERS)
    private val mLayerSurgeTarget: FloatArray = FloatArray(LAYERS)
    private val mLayerCurrentSurge: FloatArray = FloatArray(LAYERS)
    private val mLayerSurgeCountdown: FloatArray = FloatArray(LAYERS)
    private val mLayerAmpBoostCurrent: FloatArray = FloatArray(LAYERS) { 1f }
    private val mLayerCrestCurrent: FloatArray = FloatArray(LAYERS) { CREST_FACTOR }
    private val mLayerRhythmPending: FloatArray = FloatArray(LAYERS)
    private val mLayerRhythmTarget: FloatArray = FloatArray(LAYERS)
    private val mLayerRhythmCurrent: FloatArray = FloatArray(LAYERS)
    private val mLayerRhythmCountdown: FloatArray = FloatArray(LAYERS)
    private val mRandom: Random = Random()

    private val mHistoryComp: Array<Array<FloatArray>> =
        Array(INPUT_HISTORY_FRAMES) { Array(LAYERS) { FloatArray(COMPONENTS) } }
    private var mHistoryIndex: Int = 0
    private var mHistoryFilled: Int = 0
    private var mAppliedMotionSeq: Int = 0

    private val mCompPhase: Array<FloatArray> = Array(LAYERS) { FloatArray(COMPONENTS) }
    private val mLayerRhythmPhase: FloatArray = FloatArray(LAYERS)
    private var mLastFrameNanos: Long = 0L
    private var mRunning: Boolean = false
    private var mTargetRhythmEnergy: Float = 0f
    private var mCurrentRhythmEnergy: Float = 0f
    private var mTargetActivity: Float = 0f
    private var mCurrentActivity: Float = 0f
    private var mTargetIntensity: Float = 0f
    private var mCurrentIntensity: Float = 0f
    private var mTargetPace: Float = 0f
    private var mCurrentPace: Float = 0f
    private var mTargetTempoBpm: Float = 0f
    private var mCurrentTempoBpm: Float = 120f
    private var mTargetTempoConfidence: Float = 0f
    private var mCurrentTempoConfidence: Float = 0f
    private var mTargetBeatPhase: Float = 0f
    private var mTargetBeatPhaseConfidence: Float = 0f
    private var mVisualBeatPhase: Float = 0f

    private val mPath: Path = Path()
    private val mDensity: Float = resources.displayMetrics.density

    // 波形结构参数（[层][分量] 及每层一维），实例创建时用无种子 mRandom 现场生成。
    private val mAmpFactor: FloatArray            // 每层整体振幅尺度 back→front
    private val mLighten: FloatArray              // 颜色变亮量 back→front
    private val mAlpha: FloatArray                // 层透明度 back→front
    private val mWanderSpeed: FloatArray
    private val mWanderPhase: FloatArray
    private val mCompWeight: Array<FloatArray>    // 分量权重（低频大浪主导，每层和≈1）
    private val mK: Array<FloatArray>             // 空间频率（跨宽度周期数）
    private val mDrift: Array<FloatArray>         // 时间漂移速度 rad/s（正负快慢混合）
    private val mPhi: Array<FloatArray>           // 初始相位
    private val mTau: Array<FloatArray>           // 各分量缓动时间常数（秒，高频快 / 低频慢）
    private val mJitterSpeed: Array<FloatArray>   // 慢速振幅随机包络，替代每次采样瞬时随机
    private val mJitterPhase: Array<FloatArray>
    private val mResponseDelayFrames: Array<IntArray>
    private val mResponseAttack: Array<FloatArray>
    private val mResponseRelease: Array<FloatArray>
    private val mLayerSurgeDelay: FloatArray
    private val mLayerSurgeAmp: FloatArray
    private val mLayerSurgeCrest: FloatArray
    private val mLayerSurgeTau: FloatArray
    private val mLayerRhythmDelay: FloatArray
    private val mLayerRhythmAmp: FloatArray
    private val mLayerRhythmSpeed: FloatArray
    private var mFlowTime: Float = 0f
    private var mIdleTime: Float = 0f

    init {
        setWillNotDraw(false)
        val args: TypedArray = context.obtainStyledAttributes(attrs, R.styleable.VoiceVisualizer)
        mFallbackColor = args.getColor(R.styleable.VoiceVisualizer_renderColor, Color.BLACK)
        args.recycle()

        val r: Random = mRandom
        val last: Float = (LAYERS - 1).toFloat()
        mAmpFactor   = FloatArray(LAYERS) { i -> 0.50f + i / last * 0.90f }   // 0.50..1.40
        mLighten     = FloatArray(LAYERS) { i -> 0.60f - i / last * 0.56f }   // 0.60..0.04
        mAlpha       = FloatArray(LAYERS) { i -> 0.22f + i / last * 0.44f }   // 0.22..0.66
        mWanderSpeed = FloatArray(LAYERS) { 0.58f + r.nextFloat() * 1.05f }
        mWanderPhase = FloatArray(LAYERS) { r.nextFloat() * TWO_PI }

        mK = Array(LAYERS) {
            FloatArray(COMPONENTS) { j ->
                (0.78f + j * 1.12f + (r.nextFloat() * 2f - 1f) * 0.62f).coerceAtLeast(0.35f)
            }
        }
        mTau = Array(LAYERS) {
            FloatArray(COMPONENTS) { j ->
                (0.31f - j * 0.048f + (r.nextFloat() * 2f - 1f) * 0.045f).coerceIn(0.050f, 0.38f)
            }
        }
        mDrift = Array(LAYERS) { i ->
            FloatArray(COMPONENTS) { j ->
                val dispersive: Float = 0.58f + sqrt(mK[i][j]) * 0.88f
                val layerBias: Float = 0.84f + i / last * 0.34f
                val mag: Float = dispersive * layerBias * (0.72f + r.nextFloat() * 0.62f)
                if (r.nextBoolean()) mag else -mag
            }
        }
        mPhi = Array(LAYERS) { FloatArray(COMPONENTS) { r.nextFloat() * TWO_PI } }
        mJitterSpeed = Array(LAYERS) {
            FloatArray(COMPONENTS) { 0.50f + r.nextFloat() * 1.10f }
        }
        mJitterPhase = Array(LAYERS) { FloatArray(COMPONENTS) { r.nextFloat() * TWO_PI } }
        mCompWeight = Array(LAYERS) {
            val raw = FloatArray(COMPONENTS) { j ->
                val detailScale = if (j >= DETAIL_COMPONENT_START) 0.62f else 1f
                exp(-j * 0.43f) * detailScale * (0.58f + r.nextFloat() * 0.92f)
            }
            var sum = 0f
            for (v in raw) sum += v
            FloatArray(COMPONENTS) { j -> raw[j] / sum }
        }

        mResponseDelayFrames = Array(LAYERS) {
            IntArray(COMPONENTS) { r.nextInt(INPUT_HISTORY_FRAMES.coerceAtMost(4)) }
        }
        mResponseAttack = Array(LAYERS) {
            FloatArray(COMPONENTS) { j ->
                (0.045f + j * 0.018f + r.nextFloat() * 0.19f).coerceIn(0.040f, 0.32f)
            }
        }
        mResponseRelease = Array(LAYERS) {
            FloatArray(COMPONENTS) { j ->
                (0.18f + j * 0.035f + r.nextFloat() * 0.35f).coerceIn(0.16f, 0.70f)
            }
        }
        mLayerSurgeDelay = FloatArray(LAYERS) { r.nextFloat() * LAYER_SURGE_MAX_DELAY }
        mLayerSurgeAmp = FloatArray(LAYERS) { 0.36f + r.nextFloat() * 0.42f }
        mLayerSurgeCrest = FloatArray(LAYERS) { 0.28f + r.nextFloat() * 0.38f }
        mLayerSurgeTau = FloatArray(LAYERS) { 0.20f + r.nextFloat() * 0.32f }
        mLayerRhythmDelay = FloatArray(LAYERS) { r.nextFloat() * LAYER_RHYTHM_MAX_DELAY }
        mLayerRhythmAmp = FloatArray(LAYERS) { 0.68f + r.nextFloat() * 0.48f }
        mLayerRhythmSpeed = FloatArray(LAYERS) { 0.82f + r.nextFloat() * 0.40f }
        for (i in 0 until LAYERS) {
            mLayerRhythmPhase[i] = r.nextFloat() * TWO_PI
        }
    }

    private class MotionTargets(
        val comp: Array<FloatArray>,
        val level: Float,
        val transient: Float,
        val seq: Int,
        val onset: Float = 0f,
        val beatPulse: Float = 0f,
        val beatPhase: Float = 0f,
        val tempoBpm: Float = 0f,
        val tempoConfidence: Float = 0f,
        val rhythmEnergy: Float = 0f,
        val lowPulse: Float = 0f,
        val highPulse: Float = 0f,
        val intensity: Float = 0f,
        val pace: Float = 0f,
        val activity: Float = 0f
    )

    private fun storeIncomingMotionIfNeeded() {
        val incoming: MotionTargets = mIncomingMotion
        if (incoming.seq == mAppliedMotionSeq) return

        mHistoryIndex = (mHistoryIndex + 1) % INPUT_HISTORY_FRAMES
        for (i in 0 until LAYERS) {
            for (j in 0 until COMPONENTS) {
                mHistoryComp[mHistoryIndex][i][j] = incoming.comp[i][j]
            }
        }
        if (mHistoryFilled < INPUT_HISTORY_FRAMES) {
            mHistoryFilled++
        }
        mTargetLevel = incoming.level
        mTargetActivity = incoming.activity
        mTargetIntensity = incoming.intensity
        mTargetPace = incoming.pace
        mTargetRhythmEnergy = incoming.rhythmEnergy
        mTargetTempoConfidence = incoming.tempoConfidence * incoming.activity
        if (incoming.tempoBpm > 0f) {
            mTargetTempoBpm = incoming.tempoBpm
        }
        mTargetBeatPhaseConfidence = incoming.tempoConfidence * incoming.activity
        if (mTargetBeatPhaseConfidence > 0f) {
            mTargetBeatPhase = incoming.beatPhase.floorMod1()
        }

        if (incoming.transient >= SURGE_TRIGGER_THRESHOLD) {
            val surge: Float = clamp01((incoming.transient - SURGE_TRIGGER_THRESHOLD) /
                    (1f - SURGE_TRIGGER_THRESHOLD))
            for (i in 0 until LAYERS) {
                val layerSurge: Float = surge * mLayerSurgeAmp[i] * (0.80f + mRandom.nextFloat() * 0.40f)
                if (layerSurge > mLayerPendingSurge[i]) {
                    mLayerPendingSurge[i] = layerSurge
                    mLayerSurgeCountdown[i] = mLayerSurgeDelay[i] * (0.65f + mRandom.nextFloat() * 0.70f)
                }
            }
        }
        val rawRhythmTrigger: Float = clamp01(
            incoming.onset * 0.96f +
                    incoming.beatPulse * 0.50f +
                    incoming.rhythmEnergy * RHYTHM_ENERGY_TRIGGER_MIX
        )
        val rhythmTrigger = rawRhythmTrigger *
                (RHYTHM_QUIET_TRIGGER_FLOOR + incoming.activity * (1f - RHYTHM_QUIET_TRIGGER_FLOOR))
        val canTriggerRhythm = incoming.activity >= RHYTHM_ACTIVITY_TRIGGER_GATE ||
                incoming.onset >= RHYTHM_WAKE_ONSET
        if (canTriggerRhythm && rhythmTrigger >= RHYTHM_TRIGGER_THRESHOLD) {
            val lastLayer: Float = (LAYERS - 1).toFloat()
            for (i in 0 until LAYERS) {
                val depth: Float = if (LAYERS <= 1) 0f else i / lastLayer
                val gatedDetail: Float = incoming.highPulse *
                        clamp01((incoming.onset - RHYTHM_DETAIL_ONSET_GATE) / (1f - RHYTHM_DETAIL_ONSET_GATE))
                val spectralAccent: Float = incoming.lowPulse * (0.36f - depth * 0.12f) +
                        incoming.rhythmEnergy * 0.14f +
                        gatedDetail * depth * 0.055f
                val layerRhythm: Float = clamp01(rhythmTrigger + spectralAccent) *
                        mLayerRhythmAmp[i] * (0.82f + mRandom.nextFloat() * 0.36f)
                if (layerRhythm > mLayerRhythmPending[i]) {
                    val delay = mLayerRhythmDelay[i] * (0.45f + mRandom.nextFloat() * 0.55f)
                    if (delay <= RHYTHM_EVENT_BACKFILL_SEC) {
                        mLayerRhythmTarget[i] = mergedPulseTarget(
                            mLayerRhythmTarget[i],
                            mLayerRhythmCurrent[i],
                            layerRhythm,
                            RHYTHM_RETRIGGER_HOLD_RATIO
                        )
                        mLayerRhythmPending[i] = 0f
                        mLayerRhythmCountdown[i] = 0f
                    } else {
                        mLayerRhythmPending[i] = layerRhythm
                        mLayerRhythmCountdown[i] = delay - RHYTHM_EVENT_BACKFILL_SEC
                    }
                }
            }
        }

        mAppliedMotionSeq = incoming.seq
    }

    /** 设置记事背景，据此派生 4 层颜色（纯色取同色系深浅、渐变整条按层复用）。 */
    fun setThingBackground(background: ThingBackground?) {
        mBackground = background
        rebuildPaints()
        invalidate()
    }

    /** 兜底：仅有单一颜色时转为纯色 ThingBackground。 */
    fun setRenderColor(renderColor: Int) {
        mFallbackColor = renderColor
        setThingBackground(ThingBackground.pure(renderColor))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startFrameLoop(resetFrameClock = true)
    }

    override fun onDetachedFromWindow() {
        stopFrameLoop()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            startFrameLoop(resetFrameClock = true)
        } else {
            stopFrameLoop()
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            startFrameLoop(resetFrameClock = true)
        } else {
            stopFrameLoop()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            startFrameLoop(resetFrameClock = true)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPaints()
    }

    /**
     * 兼容旧入口：仅有分贝时，把同一个响度值包装为全频段特征帧。
     */
    internal fun receive(volume: Int) {
        val norm: Float = decibelToNorm(volume)
        receive(
            VoiceAudioFrame(
                loudness = norm,
                low = norm,
                lowMid = norm,
                mid = norm,
                high = norm,
                air = norm,
                transient = 0f,
                intensity = norm,
                activity = norm
            )
        )
    }

    /**
     * 接收来自 [AudioRecorder] 的音频特征。仅更新缓动目标，不直接绘制。
     * RMS/响度给所有分量一个共同底盘；FFT 频段决定不同波分量的涨落；transient 触发短时浪涌。
     */
    internal fun receive(frame: VoiceAudioFrame) {
        val previous: MotionTargets = mIncomingMotion
        val loudness: Float = clamp01(frame.loudness)
        val intensity: Float = clamp01(frame.intensity).pow(INTENSITY_INPUT_GAMMA)
        val loudnessContrast: Float = clamp01(
            loudness * LOUDNESS_ABSOLUTE_MIX + intensity * LOUDNESS_INTENSITY_MIX
        )
        val bandIntensityScale: Float = BAND_INTENSITY_FLOOR +
                intensity * (1f - BAND_INTENSITY_FLOOR)
        val activity: Float = clamp01(frame.activity).pow(ACTIVITY_INPUT_GAMMA)
        val bodyActivity = smoothStep(BODY_ACTIVITY_START, BODY_ACTIVITY_FULL, activity)
        val detailActivity = smoothStep(DETAIL_ACTIVITY_START, DETAIL_ACTIVITY_FULL, activity)
        val bodyActivityScale = QUIET_BODY_DRIVE_FLOOR +
                bodyActivity * (1f - QUIET_BODY_DRIVE_FLOOR)
        val detailActivityScale = QUIET_DETAIL_DRIVE_FLOOR +
                detailActivity * (1f - QUIET_DETAIL_DRIVE_FLOOR)
        // 非线性曲线：压低中小音量、突出大音量，让浪的大小声对比更强
        val shapedLoudness: Float = loudnessContrast.pow(CURVE_GAMMA) * bodyActivityScale
        val levelDriveBase: Float = clamp01(
            loudness * LEVEL_LOUDNESS_MIX + intensity * LEVEL_INTENSITY_MIX
        )
        val levelDrive: Float = levelDriveBase.pow(LEVEL_GAMMA) *
                (LEVEL_ACTIVITY_FLOOR + bodyActivity * (1f - LEVEL_ACTIVITY_FLOOR))
        val desired = Array(LAYERS) { i ->
            FloatArray(COMPONENTS) { j ->
                val componentActivity = if (j >= DETAIL_COMPONENT_START) {
                    detailActivityScale
                } else {
                    bodyActivityScale
                }
                val bandDrive: Float = bandDrive(frame, i, j).pow(BAND_GAMMA) *
                        componentActivity * bandIntensityScale
                val drive: Float = clamp01(shapedLoudness * GLOBAL_LOUDNESS_MIX + bandDrive * BAND_MIX)
                stableInput(
                    previous = previous.comp[i][j],
                    desired = (drive * mCompWeight[i][j]).coerceAtLeast(0f),
                    absoluteDeadband = INPUT_COMPONENT_DEADBAND,
                    relativeDeadband = INPUT_COMPONENT_RELATIVE_DEADBAND,
                    zeroGate = INPUT_COMPONENT_ZERO_GATE
                )
            }
        }
        mIncomingMotion = MotionTargets(
            comp = desired,
            level = stableInput(
                previous = previous.level,
                desired = levelDrive,
                absoluteDeadband = INPUT_LEVEL_DEADBAND,
                relativeDeadband = INPUT_LEVEL_RELATIVE_DEADBAND,
                zeroGate = INPUT_LEVEL_ZERO_GATE
            ),
            transient = frame.transient * bodyActivityScale,
            seq = mIncomingMotion.seq + 1,
            onset = frame.onset,
            beatPulse = frame.beatPulse,
            beatPhase = frame.beatPhase,
            tempoBpm = frame.tempoBpm,
            tempoConfidence = frame.tempoConfidence,
            rhythmEnergy = stableInput(
                previous = previous.rhythmEnergy,
                desired = frame.rhythmEnergy,
                absoluteDeadband = INPUT_RHYTHM_ENERGY_DEADBAND,
                relativeDeadband = INPUT_RHYTHM_ENERGY_RELATIVE_DEADBAND,
                zeroGate = INPUT_RHYTHM_ENERGY_ZERO_GATE
            ),
            lowPulse = stableInput(
                previous = previous.lowPulse,
                desired = frame.lowPulse,
                absoluteDeadband = INPUT_PULSE_DEADBAND,
                relativeDeadband = INPUT_PULSE_RELATIVE_DEADBAND,
                zeroGate = INPUT_PULSE_ZERO_GATE
            ),
            highPulse = stableInput(
                previous = previous.highPulse,
                desired = frame.highPulse,
                absoluteDeadband = INPUT_PULSE_DEADBAND,
                relativeDeadband = INPUT_PULSE_RELATIVE_DEADBAND,
                zeroGate = INPUT_PULSE_ZERO_GATE
            ),
            intensity = stableInput(
                previous = previous.intensity,
                desired = intensity,
                absoluteDeadband = INPUT_INTENSITY_DEADBAND,
                relativeDeadband = INPUT_INTENSITY_RELATIVE_DEADBAND,
                zeroGate = INPUT_INTENSITY_ZERO_GATE
            ),
            pace = stableInput(
                previous = previous.pace,
                desired = frame.pace,
                absoluteDeadband = INPUT_PACE_DEADBAND,
                relativeDeadband = INPUT_PACE_RELATIVE_DEADBAND,
                zeroGate = INPUT_PACE_ZERO_GATE
            ),
            activity = stableInput(
                previous = previous.activity,
                desired = activity,
                absoluteDeadband = INPUT_ACTIVITY_DEADBAND,
                relativeDeadband = INPUT_ACTIVITY_RELATIVE_DEADBAND,
                zeroGate = INPUT_ACTIVITY_ZERO_GATE
            )
        )
    }

    private fun rebuildPaints() {
        val w: Int = width
        val h: Int = height
        if (w <= 0 || h <= 0) {
            mPaintsReady = false
            return
        }
        val bg: ThingBackground = mBackground ?: ThingBackground.pure(mFallbackColor)
        val gradient: Boolean = bg.mode === ThingBackground.Mode.GRADIENT
        for (i in 0 until LAYERS) {
            val paint: Paint = mLayerPaints[i]
            if (gradient) {
                val c1: Int = BackgroundUtil.lighter(bg.color, mLighten[i])
                val c2: Int = BackgroundUtil.lighter(bg.endColor, mLighten[i])
                val line: FloatArray = gradientLine(bg.orientation, w.toFloat(), h.toFloat())
                paint.shader = LinearGradient(
                    line[0], line[1], line[2], line[3],
                    intArrayOf(c1, c2), null, Shader.TileMode.CLAMP
                )
                paint.color = Color.WHITE // 由 shader 提供颜色；alpha 单独控制
                paint.alpha = (mAlpha[i] * 255f).toInt()
            } else {
                paint.shader = null
                val c: Int = BackgroundUtil.lighter(bg.color, mLighten[i])
                paint.color = withAlpha(c, mAlpha[i])
            }
        }
        mPaintsReady = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w: Int = width
        val h: Int = height
        if (w <= 0 || h <= 0) {
            if (mRunning) postInvalidateOnAnimation()
            return
        }
        if (!mPaintsReady) {
            rebuildPaints()
        }

        // 帧间隔（秒），首帧置 0 避免跳变
        val now: Long = System.nanoTime()
        val dt: Float = if (mLastFrameNanos == 0L) 0f
            else ((now - mLastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
        mLastFrameNanos = now
        storeIncomingMotionIfNeeded()
        val activityTau = if (mTargetActivity > mCurrentActivity) {
            ACTIVITY_ATTACK_TAU
        } else {
            ACTIVITY_RELEASE_TAU
        }
        val activityK: Float = 1f - exp(-dt / activityTau)
        mCurrentActivity += (mTargetActivity - mCurrentActivity) * activityK
        val visualActivity: Float = mCurrentActivity
        val intensityTau = if (mTargetIntensity > mCurrentIntensity) {
            INTENSITY_ATTACK_TAU
        } else {
            INTENSITY_RELEASE_TAU
        }
        mCurrentIntensity += (mTargetIntensity - mCurrentIntensity) *
                (1f - exp(-dt / intensityTau))
        val paceTau = if (mTargetPace > mCurrentPace) {
            PACE_ATTACK_TAU
        } else {
            PACE_RELEASE_TAU
        }
        mCurrentPace += (mTargetPace - mCurrentPace) * (1f - exp(-dt / paceTau))
        val rhythmEnergyK: Float = 1f - exp(-dt / RHYTHM_GLOBAL_TAU)
        mCurrentRhythmEnergy += (mTargetRhythmEnergy - mCurrentRhythmEnergy) * rhythmEnergyK
        mCurrentTempoConfidence += (mTargetTempoConfidence - mCurrentTempoConfidence) * rhythmEnergyK
        val flowActivity: Float = clamp01(
            visualActivity + mCurrentRhythmEnergy * FLOW_RHYTHM_ACTIVITY_MIX +
                    mCurrentPace * FLOW_PACE_ACTIVITY_MIX
        )
        val flowDrive: Float = smoothStep(FLOW_ACTIVITY_START, FLOW_ACTIVITY_FULL, flowActivity)
        val paceDrive: Float = smoothStep(PACE_FLOW_START, PACE_FLOW_FULL, mCurrentPace)
        val flowBaseScale: Float = FLOW_QUIET_SCALE +
                flowDrive * (FLOW_ACTIVE_BASE_SCALE - FLOW_QUIET_SCALE) +
                paceDrive * FLOW_PACE_BOOST
        if (mTargetTempoBpm > 0f) {
            val tempoK: Float = 1f - exp(-dt / RHYTHM_TEMPO_TAU)
            mCurrentTempoBpm += (mTargetTempoBpm - mCurrentTempoBpm) * tempoK
        }
        val tempoRate: Float = (mCurrentTempoBpm / FLOW_REFERENCE_BPM)
            .coerceIn(FLOW_TEMPO_MIN_SCALE, FLOW_TEMPO_MAX_SCALE)
        val tempoFollow: Float = mCurrentTempoConfidence *
                (TEMPO_FOLLOW_BASE + paceDrive * (1f - TEMPO_FOLLOW_BASE)) * flowDrive
        val tempoFlowScale: Float = 1f + (tempoRate - 1f) * tempoFollow
        val flowScale: Float = (flowBaseScale * tempoFlowScale).coerceAtMost(FLOW_MAX_SCALE)
        val idleTimeScale: Float = IDLE_TIME_QUIET_SCALE +
                visualActivity * (1f - IDLE_TIME_QUIET_SCALE)
        mFlowTime += dt * flowScale
        mIdleTime += dt * idleTimeScale
        if (mCurrentTempoBpm > 0f) {
            val beatStep: Float = dt * mCurrentTempoBpm / 60f
            mTargetBeatPhase = (mTargetBeatPhase + beatStep).floorMod1()
            if (mCurrentTempoConfidence > TEMPO_VISUAL_MIN_CONFIDENCE) {
                mVisualBeatPhase = (mVisualBeatPhase + beatStep).floorMod1()
            }
        }
        if (mTargetBeatPhaseConfidence > 0f) {
            val phaseDelta: Float = ((mTargetBeatPhase - mVisualBeatPhase + 1.5f) % 1f) - 0.5f
            val phaseK: Float = 1f - exp(-dt / BEAT_PHASE_PULL_TAU)
            val maxPhaseStep: Float = BEAT_PHASE_MAX_PULL_PER_SEC * dt
            val phaseStep: Float = (phaseDelta * mTargetBeatPhaseConfidence * BEAT_PHASE_SYNC * phaseK)
                .coerceIn(-maxPhaseStep, maxPhaseStep)
            mVisualBeatPhase = (mVisualBeatPhase + phaseStep).floorMod1()
        }

        // incoming → delayed target → current。每个分量取不同历史帧，并用各自 attack/release
        // 追随目标，制造随机先后顺序与不同惯性。
        for (i in 0 until LAYERS) {
            for (j in 0 until COMPONENTS) {
                val delay = mResponseDelayFrames[i][j].coerceAtMost((mHistoryFilled - 1).coerceAtLeast(0))
                val historySlot = (mHistoryIndex - delay + INPUT_HISTORY_FRAMES) % INPUT_HISTORY_FRAMES
                val desired = if (mHistoryFilled > 0) mHistoryComp[historySlot][i][j] else 0f
                val tau = if (desired > mTargetComp[i][j]) mResponseAttack[i][j] else mResponseRelease[i][j]
                val k = 1f - exp(-dt / tau)
                val nextTarget = mTargetComp[i][j] + (desired - mTargetComp[i][j]) * k
                val maxDelta = (if (nextTarget > mTargetComp[i][j]) {
                    TARGET_RISE_SLEW
                } else {
                    TARGET_FALL_SLEW
                }) * dt
                mTargetComp[i][j] += (nextTarget - mTargetComp[i][j]).coerceIn(-maxDelta, maxDelta)
            }
        }

        // 各分量独立缓动 target → current（时间常数不同 → 时间上错落地长起 / 落下），
        // 并各自推进相位（不同漂移速度 → 波峰位置不断此消彼长）
        for (i in 0 until LAYERS) {
            for (j in 0 until COMPONENTS) {
                val k: Float = 1f - exp(-dt / mTau[i][j])
                mCurrentComp[i][j] += (mTargetComp[i][j] - mCurrentComp[i][j]) * k
                val detailTempoScale: Float = if (j >= DETAIL_COMPONENT_START) RHYTHM_DETAIL_DRIFT_SCALE else 1f
                val rhythmFlowBoost: Float = 1f + flowDrive * mCurrentTempoConfidence * mCurrentRhythmEnergy *
                        (RHYTHM_DRIFT_BOOST_BASE + j.coerceAtMost(2) * RHYTHM_DRIFT_BOOST_STEP) *
                        detailTempoScale
                mCompPhase[i][j] += mDrift[i][j] * dt * flowBaseScale * tempoFlowScale * rhythmFlowBoost
            }
        }
        val levelTau: Float = if (mTargetLevel > mCurrentLevel) {
            LEVEL_ATTACK_TAU
        } else {
            LEVEL_RELEASE_TAU
        }
        val levelK: Float = 1f - exp(-dt / levelTau)
        mCurrentLevel += (mTargetLevel - mCurrentLevel) * levelK
        for (i in 0 until LAYERS) {
            if (mLayerSurgeCountdown[i] > 0f) {
                mLayerSurgeCountdown[i] -= dt
                if (mLayerSurgeCountdown[i] <= 0f) {
                    mLayerSurgeTarget[i] = mergedPulseTarget(
                        mLayerSurgeTarget[i],
                        mLayerCurrentSurge[i],
                        mLayerPendingSurge[i],
                        SURGE_RETRIGGER_HOLD_RATIO
                    )
                    mLayerPendingSurge[i] = 0f
                }
            }
            mLayerSurgeTarget[i] *= exp(-dt / mLayerSurgeTau[i])
            val surgeTau = if (mLayerSurgeTarget[i] > mLayerCurrentSurge[i]) {
                SURGE_ATTACK_TAU
            } else {
                SURGE_RELEASE_TAU
            }
            val surgeK = 1f - exp(-dt / surgeTau)
            mLayerCurrentSurge[i] += (mLayerSurgeTarget[i] - mLayerCurrentSurge[i]) * surgeK
            if (mLayerRhythmCountdown[i] > 0f) {
                mLayerRhythmCountdown[i] -= dt
                if (mLayerRhythmCountdown[i] <= 0f) {
                    mLayerRhythmTarget[i] = mergedPulseTarget(
                        mLayerRhythmTarget[i],
                        mLayerRhythmCurrent[i],
                        mLayerRhythmPending[i],
                        RHYTHM_RETRIGGER_HOLD_RATIO
                    )
                    mLayerRhythmPending[i] = 0f
                }
            }
            mLayerRhythmTarget[i] *= exp(-dt / RHYTHM_TARGET_TAU)
            val rhythmTau = if (mLayerRhythmTarget[i] > mLayerRhythmCurrent[i]) {
                RHYTHM_ATTACK_TAU
            } else {
                RHYTHM_RELEASE_TAU
            }
            val rhythmK = 1f - exp(-dt / rhythmTau)
            mLayerRhythmCurrent[i] += (mLayerRhythmTarget[i] - mLayerRhythmCurrent[i]) * rhythmK
            val rhythmHz = (mCurrentTempoBpm / 60f).coerceIn(RHYTHM_MIN_HZ, RHYTHM_MAX_HZ)
            val rhythmPhaseDrive = smoothStep(RHYTHM_PHASE_ACTIVITY_START, RHYTHM_PHASE_ACTIVITY_FULL, visualActivity)
            val rhythmPhaseScale = RHYTHM_PHASE_QUIET_SCALE +
                    rhythmPhaseDrive * (1f - RHYTHM_PHASE_QUIET_SCALE)
            mLayerRhythmPhase[i] += TWO_PI * rhythmHz * mLayerRhythmSpeed[i] * dt * rhythmPhaseScale
        }

        val waterH: Float = h * (REST_FRAC + mCurrentLevel * (MAX_FRAC - REST_FRAC))
        val surfaceY: Float = h - waterH
        val maxAmpPx: Float = MAX_AMP_DP * mDensity
        val k0: Float = TWO_PI / w
        val bottom: Float = h.toFloat()
        val topLimit: Float = TOP_LIMIT_DP * mDensity
        val soft: Float = SOFT_DP * mDensity

        if (mPaintsReady) {
            for (i in 0 until LAYERS) {
                val wanderScale = WANDER_QUIET_SCALE +
                        visualActivity * (1f - WANDER_QUIET_SCALE)
                val wander: Float =
                    WANDER_DP * wanderScale * mDensity *
                            sin(mWanderSpeed[i] * mIdleTime + mWanderPhase[i])
                val layerSurfaceY: Float = surfaceY + wander
                val rhythm: Float = mLayerRhythmCurrent[i]
                val intensityAmpScale: Float = AMP_INTENSITY_FLOOR +
                        mCurrentIntensity.pow(AMP_INTENSITY_GAMMA) *
                        (AMP_INTENSITY_FULL_SCALE - AMP_INTENSITY_FLOOR)
                val ampBoostTarget: Float = 1f + mLayerCurrentSurge[i] * SURGE_AMP_BOOST +
                        rhythm * RHYTHM_AMP_BOOST +
                        mCurrentRhythmEnergy * RHYTHM_BODY_AMP_BOOST
                val ampBoost: Float = smoothWithDeadband(
                    current = mLayerAmpBoostCurrent[i],
                    target = ampBoostTarget,
                    dt = dt,
                    attackTau = AMP_BOOST_ATTACK_TAU,
                    releaseTau = AMP_BOOST_RELEASE_TAU,
                    absoluteDeadband = AMP_BOOST_DEADBAND,
                    relativeDeadband = AMP_BOOST_RELATIVE_DEADBAND
                )
                mLayerAmpBoostCurrent[i] = ampBoost
                val ampPx: Float = maxAmpPx * mAmpFactor[i] * ampBoost * intensityAmpScale
                val crestTarget: Float = (CREST_FACTOR + mLayerCurrentSurge[i] * mLayerSurgeCrest[i] +
                        rhythm * RHYTHM_CREST_BOOST)
                    .coerceAtMost(CREST_FACTOR_MAX)
                val crestFactor: Float = smoothWithDeadband(
                    current = mLayerCrestCurrent[i],
                    target = crestTarget,
                    dt = dt,
                    attackTau = CREST_ATTACK_TAU,
                    releaseTau = CREST_RELEASE_TAU,
                    absoluteDeadband = CREST_DEADBAND,
                    relativeDeadband = CREST_RELATIVE_DEADBAND
                ).coerceAtMost(CREST_FACTOR_MAX)
                mLayerCrestCurrent[i] = crestFactor
                val jitterActivityScale: Float = JITTER_QUIET_SCALE +
                        visualActivity * (1f - JITTER_QUIET_SCALE)

                mPath.rewind()
                mPath.moveTo(0f, bottom)
                for (sampleIndex in 0..SURFACE_SAMPLE_COUNT) {
                    val x: Float = w * sampleIndex / SURFACE_SAMPLE_COUNT.toFloat()
                    var bodyS = 0f
                    var detailS = 0f
                    for (j in 0 until COMPONENTS) {
                        val jitterScale: Float = if (j < DETAIL_COMPONENT_START) {
                            BODY_JITTER_SCALE
                        } else {
                            DETAIL_JITTER_SCALE
                        }
                        val jitterEnvelope: Float =
                            1f + AMP_JITTER * jitterScale * jitterActivityScale *
                                    sin(mJitterSpeed[i][j] * mFlowTime + mJitterPhase[i][j])
                        val componentS = mCurrentComp[i][j] * jitterEnvelope *
                                sin(mK[i][j] * k0 * x + mCompPhase[i][j] + mPhi[i][j])
                        if (j < DETAIL_COMPONENT_START) {
                            bodyS += componentS
                        } else {
                            detailS += componentS
                        }
                    }
                    val idleScale = IDLE_QUIET_SCALE + visualActivity * (1f - IDLE_QUIET_SCALE)
                    // 静音微动走更慢、更低频的独立相位；安静时保留轻微水感，但不再高频翻动。
                    bodyS += IDLE_AMP * idleScale *
                            sin(mK[i][0] * IDLE_SPATIAL_SCALE * k0 * x +
                                    mWanderSpeed[i] * IDLE_PHASE_SPEED_SCALE * mIdleTime + mPhi[i][0])
                    val frontDepth: Float = if (LAYERS <= 1) 0f else i / (LAYERS - 1).toFloat()
                    val rhythmContour: Float = rhythm * visualActivity * (0.56f + frontDepth * 0.24f)
                    if (rhythmContour > 0.001f) {
                        bodyS += rhythmContour * RHYTHM_CONTOUR_AMP *
                                sin((mK[i][0] * 0.44f + 0.38f) * k0 * x +
                                        mVisualBeatPhase * TWO_PI +
                                        mLayerRhythmPhase[i] * 0.16f + mPhi[i][0])
                    }
                    val detailActivityScale = DETAIL_QUIET_SCALE +
                            visualActivity * (1f - DETAIL_QUIET_SCALE)
                    val detailBudget: Float = (DETAIL_BUDGET_BASE + abs(bodyS) * DETAIL_BUDGET_BODY_SCALE) *
                            (1f - rhythm * DETAIL_BUDGET_RHYTHM_DAMP).coerceAtLeast(DETAIL_BUDGET_MIN_SCALE) *
                            detailActivityScale
                    val peakDetailT: Float = smoothStep(
                        PEAK_DETAIL_DAMP_START,
                        PEAK_DETAIL_DAMP_FULL,
                        bodyS
                    )
                    val peakDetailScale: Float = 1f - PEAK_DETAIL_DAMP * peakDetailT
                    val s: Float = bodyS + detailS.coerceIn(-detailBudget, detailBudget) * peakDetailScale
                    val shapedS: Float = shapeWave(s, crestFactor)
                    val rawY: Float = layerSurfaceY - ampPx * shapedS
                    // 顶部软限：浪尖平滑趋近上界，绝不糊住上方计时文本（水位抬高后的护栏）
                    val y: Float = topLimit + soft * ln(1f + exp((rawY - topLimit) / soft))
                    mPath.lineTo(x, y)
                }
                mPath.lineTo(w.toFloat(), bottom)
                mPath.close()
                canvas.drawPath(mPath, mLayerPaints[i])
            }
        }

        if (mRunning && windowVisibility == View.VISIBLE && isShown) {
            postInvalidateOnAnimation()
        }
    }

    private fun startFrameLoop(resetFrameClock: Boolean) {
        if (!isAttachedToWindow || windowVisibility != View.VISIBLE || !isShown) return
        mRunning = true
        if (resetFrameClock) {
            mLastFrameNanos = 0L
        }
        postInvalidateOnAnimation()
    }

    private fun stopFrameLoop() {
        mRunning = false
        mLastFrameNanos = 0L
    }

    private fun bandDrive(frame: VoiceAudioFrame, layer: Int, component: Int): Float {
        val depth: Float = if (LAYERS <= 1) 0f else layer / (LAYERS - 1).toFloat()
        val v: Float = when (component) {
            0 -> frame.low * (1.12f - depth * 0.18f) + frame.lowMid * 0.12f
            1 -> frame.lowMid * 0.95f + frame.low * 0.18f + frame.mid * 0.12f
            2 -> frame.mid * 0.95f + frame.lowMid * 0.16f + frame.high * 0.12f
            3 -> frame.high * (0.72f + depth * 0.20f) + frame.mid * 0.12f + frame.air * 0.06f
            else -> frame.air * (0.52f + depth * 0.30f) + frame.high * 0.14f
        }
        return clamp01(v)
    }

    private fun shapeWave(s: Float, crestFactor: Float): Float {
        return if (s >= 0f) {
            val taperT: Float = clamp01((s - CREST_TAPER_START) / CREST_TAPER_RANGE)
            val taper: Float = taperT * taperT * (3f - 2f * taperT)
            val roundedCrest: Float = crestFactor * (1f - CREST_TAPER_AMOUNT * taper)
            val sharpened: Float = s * (1f + roundedCrest * (1f - exp(-s / CREST_SOFT)))
            if (sharpened <= CREST_ROUND_START) {
                sharpened
            } else {
                val excess: Float = sharpened - CREST_ROUND_START
                CREST_ROUND_START + CREST_ROUND_RANGE * (1f - exp(-excess / CREST_ROUND_RANGE))
            }
        } else {
            s * (TROUGH_FACTOR + (1f - TROUGH_FACTOR) * exp(s / TROUGH_SOFT))
        }
    }

    private fun decibelToNorm(volume: Int): Float {
        return clamp01((volume - MIN_DB).toFloat() / (MAX_DB - MIN_DB).toFloat())
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a: Int = (clamp01(alpha) * 255f).toInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun stableInput(
        previous: Float,
        desired: Float,
        absoluteDeadband: Float,
        relativeDeadband: Float,
        zeroGate: Float
    ): Float {
        if (desired <= zeroGate) {
            return 0f
        }
        val threshold: Float = absoluteDeadband + previous * relativeDeadband
        return if (abs(desired - previous) <= threshold) previous else desired
    }

    private fun smoothWithDeadband(
        current: Float,
        target: Float,
        dt: Float,
        attackTau: Float,
        releaseTau: Float,
        absoluteDeadband: Float,
        relativeDeadband: Float
    ): Float {
        val threshold: Float = absoluteDeadband + abs(current) * relativeDeadband
        if (abs(target - current) <= threshold) {
            return current
        }
        val tau: Float = if (target > current) attackTau else releaseTau
        val k: Float = 1f - exp(-dt / tau)
        return current + (target - current) * k
    }

    private fun smoothStep(start: Float, end: Float, value: Float): Float {
        val t: Float = clamp01((value - start) / (end - start))
        return t * t * (3f - 2f * t)
    }

    private fun gradientLine(
        orientation: ThingBackground.Orientation,
        width: Float,
        height: Float
    ): FloatArray {
        return when (orientation) {
            ThingBackground.Orientation.L_R -> floatArrayOf(0f, 0f, width, 0f)
            ThingBackground.Orientation.T_B -> floatArrayOf(0f, 0f, 0f, height)
            ThingBackground.Orientation.LT_RB -> floatArrayOf(0f, 0f, width, height)
            ThingBackground.Orientation.RT_LB -> floatArrayOf(width, 0f, 0f, height)
            ThingBackground.Orientation.LB_RT -> floatArrayOf(0f, height, width, 0f)
            ThingBackground.Orientation.RB_LT -> floatArrayOf(width, height, 0f, 0f)
            ThingBackground.Orientation.R_L -> floatArrayOf(width, 0f, 0f, 0f)
            ThingBackground.Orientation.B_T -> floatArrayOf(0f, height, 0f, 0f)
        }
    }

    private fun mergedPulseTarget(
        target: Float,
        current: Float,
        candidate: Float,
        holdRatio: Float
    ): Float {
        return if (current >= candidate * holdRatio) {
            target.coerceAtLeast(current)
        } else {
            target.coerceAtLeast(candidate)
        }
    }

    private fun clamp01(v: Float): Float {
        if (v < 0f) return 0f
        if (v > 1f) return 1f
        return v
    }

    private fun Float.floorMod1(): Float {
        val v = this % 1f
        return if (v < 0f) v + 1f else v
    }

    companion object {
        private const val LAYERS: Int = 6
        private const val COMPONENTS: Int = 5
        private const val SURFACE_SAMPLE_COUNT: Int = 216
        private const val TWO_PI: Float = (2.0 * Math.PI).toFloat()

        // 保存最近几帧音频目标，供每层 / 每分量按自己的延迟取样，形成随机先后顺序。
        private const val INPUT_HISTORY_FRAMES: Int = 5
        private const val LAYER_SURGE_MAX_DELAY: Float = 0.22f
        private const val LAYER_RHYTHM_MAX_DELAY: Float = 0.026f
        private const val RHYTHM_TRIGGER_THRESHOLD: Float = 0.13f
        private const val RHYTHM_ENERGY_TRIGGER_MIX: Float = 0.22f
        private const val RHYTHM_ACTIVITY_TRIGGER_GATE: Float = 0.18f
        private const val RHYTHM_WAKE_ONSET: Float = 0.48f
        private const val RHYTHM_QUIET_TRIGGER_FLOOR: Float = 0.08f
        private const val RHYTHM_DETAIL_ONSET_GATE: Float = 0.56f
        private const val RHYTHM_EVENT_BACKFILL_SEC: Float = 0.035f
        private const val BEAT_PHASE_SYNC: Float = 0.36f
        private const val BEAT_PHASE_PULL_TAU: Float = 0.12f
        private const val BEAT_PHASE_MAX_PULL_PER_SEC: Float = 1.10f
        private const val RHYTHM_GLOBAL_TAU: Float = 0.070f
        private const val RHYTHM_TEMPO_TAU: Float = 0.15f
        private const val TEMPO_VISUAL_MIN_CONFIDENCE: Float = 0.16f
        private const val RHYTHM_TARGET_TAU: Float = 0.095f
        private const val RHYTHM_ATTACK_TAU: Float = 0.016f
        private const val RHYTHM_RELEASE_TAU: Float = 0.155f
        private const val RHYTHM_MIN_HZ: Float = 1.0f
        private const val RHYTHM_MAX_HZ: Float = 4.0f
        private const val RHYTHM_PHASE_QUIET_SCALE: Float = 0.035f
        private const val RHYTHM_PHASE_ACTIVITY_START: Float = 0.28f
        private const val RHYTHM_PHASE_ACTIVITY_FULL: Float = 0.66f
        private const val RHYTHM_DRIFT_BOOST_BASE: Float = 0.18f
        private const val RHYTHM_DRIFT_BOOST_STEP: Float = 0.065f
        private const val RHYTHM_DETAIL_DRIFT_SCALE: Float = 0.30f
        private const val RHYTHM_AMP_BOOST: Float = 0.31f
        private const val RHYTHM_BODY_AMP_BOOST: Float = 0.16f
        private const val RHYTHM_CREST_BOOST: Float = 0.018f
        private const val RHYTHM_CONTOUR_AMP: Float = 0.055f
        private const val RHYTHM_RETRIGGER_HOLD_RATIO: Float = 0.68f
        private const val DETAIL_COMPONENT_START: Int = 3
        private const val DETAIL_BUDGET_BASE: Float = 0.12f
        private const val DETAIL_BUDGET_BODY_SCALE: Float = 0.18f
        private const val DETAIL_BUDGET_RHYTHM_DAMP: Float = 0.42f
        private const val DETAIL_BUDGET_MIN_SCALE: Float = 0.46f
        private const val FLOW_QUIET_SCALE: Float = 0.055f
        private const val FLOW_ACTIVE_BASE_SCALE: Float = 0.58f
        private const val FLOW_ACTIVITY_START: Float = 0.28f
        private const val FLOW_ACTIVITY_FULL: Float = 0.66f
        private const val FLOW_RHYTHM_ACTIVITY_MIX: Float = 0.14f
        private const val FLOW_PACE_ACTIVITY_MIX: Float = 0.18f
        private const val PACE_FLOW_START: Float = 0.10f
        private const val PACE_FLOW_FULL: Float = 0.72f
        private const val FLOW_PACE_BOOST: Float = 0.74f
        private const val FLOW_MAX_SCALE: Float = 1.75f
        private const val FLOW_REFERENCE_BPM: Float = 120f
        private const val FLOW_TEMPO_MIN_SCALE: Float = 0.68f
        private const val FLOW_TEMPO_MAX_SCALE: Float = 1.42f
        private const val TEMPO_FOLLOW_BASE: Float = 0.35f

        // 单帧目标变化上限：保留每层自己的错峰响应，但避免第二个音节把已成形的波峰突然改高/改低。
        private const val TARGET_RISE_SLEW: Float = 3.2f
        private const val TARGET_FALL_SLEW: Float = 2.1f

        // 音量（分贝）→ 归一化区间。待真机微调。
        private const val MIN_DB: Int = 25
        private const val MAX_DB: Int = 65

        // 音量归一化后的非线性幂指数（>1：压低中小音量、突出大音量，增强大小声对比）。
        private const val CURVE_GAMMA: Float = 1.8f
        private const val INTENSITY_INPUT_GAMMA: Float = 1.10f
        private const val LOUDNESS_ABSOLUTE_MIX: Float = 0.42f
        private const val LOUDNESS_INTENSITY_MIX: Float = 0.66f
        private const val BAND_INTENSITY_FLOOR: Float = 0.38f

        // 频段能量的非线性幂指数；略小于响度曲线，让高频细纹不至于被压得太死。
        private const val BAND_GAMMA: Float = 1.35f
        private const val GLOBAL_LOUDNESS_MIX: Float = 0.34f
        private const val BAND_MIX: Float = 0.70f

        // 视觉输入死区：忽略很小的目标变化，避免 20ms 采样下的小噪声持续驱动波浪产生果冻感。
        private const val INPUT_COMPONENT_DEADBAND: Float = 0.0045f
        private const val INPUT_COMPONENT_RELATIVE_DEADBAND: Float = 0.10f
        private const val INPUT_COMPONENT_ZERO_GATE: Float = 0.002f
        private const val INPUT_LEVEL_DEADBAND: Float = 0.014f
        private const val INPUT_LEVEL_RELATIVE_DEADBAND: Float = 0.045f
        private const val INPUT_LEVEL_ZERO_GATE: Float = 0.006f
        private const val INPUT_RHYTHM_ENERGY_DEADBAND: Float = 0.018f
        private const val INPUT_RHYTHM_ENERGY_RELATIVE_DEADBAND: Float = 0.06f
        private const val INPUT_RHYTHM_ENERGY_ZERO_GATE: Float = 0.008f
        private const val INPUT_PULSE_DEADBAND: Float = 0.020f
        private const val INPUT_PULSE_RELATIVE_DEADBAND: Float = 0.05f
        private const val INPUT_PULSE_ZERO_GATE: Float = 0.010f
        private const val INPUT_INTENSITY_DEADBAND: Float = 0.012f
        private const val INPUT_INTENSITY_RELATIVE_DEADBAND: Float = 0.040f
        private const val INPUT_INTENSITY_ZERO_GATE: Float = 0.006f
        private const val INPUT_PACE_DEADBAND: Float = 0.018f
        private const val INPUT_PACE_RELATIVE_DEADBAND: Float = 0.050f
        private const val INPUT_PACE_ZERO_GATE: Float = 0.010f
        private const val INPUT_ACTIVITY_DEADBAND: Float = 0.020f
        private const val INPUT_ACTIVITY_RELATIVE_DEADBAND: Float = 0.06f
        private const val INPUT_ACTIVITY_ZERO_GATE: Float = 0.018f
        private const val ACTIVITY_INPUT_GAMMA: Float = 0.85f
        private const val ACTIVITY_ATTACK_TAU: Float = 0.075f
        private const val ACTIVITY_RELEASE_TAU: Float = 0.62f
        private const val INTENSITY_ATTACK_TAU: Float = 0.070f
        private const val INTENSITY_RELEASE_TAU: Float = 0.42f
        private const val PACE_ATTACK_TAU: Float = 0.055f
        private const val PACE_RELEASE_TAU: Float = 0.30f
        private const val BODY_ACTIVITY_START: Float = 0.12f
        private const val BODY_ACTIVITY_FULL: Float = 0.58f
        private const val DETAIL_ACTIVITY_START: Float = 0.28f
        private const val DETAIL_ACTIVITY_FULL: Float = 0.72f
        private const val QUIET_BODY_DRIVE_FLOOR: Float = 0.035f
        private const val QUIET_DETAIL_DRIVE_FLOOR: Float = 0.0015f

        // 慢速振幅随机包络深度。随机性随帧连续流动，不在每次 100ms 音频采样时突然换目标。
        private const val AMP_JITTER: Float = 0.32f
        private const val BODY_JITTER_SCALE: Float = 0.42f
        private const val DETAIL_JITTER_SCALE: Float = 0.70f
        private const val JITTER_QUIET_SCALE: Float = 0.16f

        // 水位使用独立的潮位通道：由声强驱动，但比浪高更慢，避免整片水体机械抬升。
        private const val LEVEL_LOUDNESS_MIX: Float = 0.34f
        private const val LEVEL_INTENSITY_MIX: Float = 0.78f
        private const val LEVEL_GAMMA: Float = 1.20f
        private const val LEVEL_ACTIVITY_FLOOR: Float = 0.18f
        private const val LEVEL_ATTACK_TAU: Float = 0.32f
        private const val LEVEL_RELEASE_TAU: Float = 0.64f

        // 瞬态浪涌：突然发声时按每层自己的延迟、强度和衰减推高浪幅，随后自然回落。
        private const val SURGE_AMP_BOOST: Float = 0.55f
        private const val SURGE_TRIGGER_THRESHOLD: Float = 0.20f
        private const val SURGE_ATTACK_TAU: Float = 0.11f
        private const val SURGE_RELEASE_TAU: Float = 0.24f
        private const val SURGE_RETRIGGER_HOLD_RATIO: Float = 0.72f
        private const val AMP_BOOST_ATTACK_TAU: Float = 0.13f
        private const val AMP_BOOST_RELEASE_TAU: Float = 0.34f
        private const val AMP_BOOST_DEADBAND: Float = 0.012f
        private const val AMP_BOOST_RELATIVE_DEADBAND: Float = 0.020f
        private const val AMP_INTENSITY_FLOOR: Float = 0.56f
        private const val AMP_INTENSITY_FULL_SCALE: Float = 1.22f
        private const val AMP_INTENSITY_GAMMA: Float = 1.15f

        // 静音微动的最小波振幅系数（合成权重单位）。
        private const val IDLE_AMP: Float = 0.032f
        private const val IDLE_QUIET_SCALE: Float = 0.10f
        private const val IDLE_SPATIAL_SCALE: Float = 0.52f
        private const val IDLE_PHASE_SPEED_SCALE: Float = 0.22f
        private const val IDLE_TIME_QUIET_SCALE: Float = 0.07f

        // 水位占视图高度：静息 / 最高。D36 恢复一定"涨潮"反馈，但保持限幅，主表达仍由浪高承担。
        private const val REST_FRAC: Float = 0.24f
        private const val MAX_FRAC: Float = 0.49f

        // 波浪最大振幅（dp）。声音的变化主要由它体现，故取较大值。
        private const val MAX_AMP_DP: Float = 60f

        // 波谷不对称（Gerstner 式峰尖谷平）：波峰保留、波谷压浅。TROUGH_FACTOR 为最深谷的压缩
        // 系数（越小谷越浅，1=对称正弦），TROUGH_SOFT 为过渡尺度（越小越快压到位）。
        private const val TROUGH_FACTOR: Float = 0.4f
        private const val TROUGH_SOFT: Float = 0.5f
        private const val CREST_FACTOR: Float = 0.26f
        private const val CREST_FACTOR_MAX: Float = 0.48f
        private const val CREST_SOFT: Float = 0.78f
        private const val CREST_TAPER_START: Float = 0.72f
        private const val CREST_TAPER_RANGE: Float = 0.58f
        private const val CREST_TAPER_AMOUNT: Float = 0.38f
        private const val CREST_ROUND_START: Float = 1.12f
        private const val CREST_ROUND_RANGE: Float = 0.72f
        private const val CREST_ATTACK_TAU: Float = 0.14f
        private const val CREST_RELEASE_TAU: Float = 0.32f
        private const val CREST_DEADBAND: Float = 0.004f
        private const val CREST_RELATIVE_DEADBAND: Float = 0.012f
        private const val PEAK_DETAIL_DAMP_START: Float = 0.48f
        private const val PEAK_DETAIL_DAMP_FULL: Float = 0.96f
        private const val PEAK_DETAIL_DAMP: Float = 0.80f

        // 顶部软限：浪尖平滑趋近的上界（dp，距视图顶）与软化尺度。水位抬高后防止大浪糊住计时；
        // 浪尖能达到的最高点约为 TOP_LIMIT_DP + SOFT_DP*ln2。
        private const val TOP_LIMIT_DP: Float = 64f
        private const val SOFT_DP: Float = 14f

        // 每层水面基线慢速微漂幅度（dp）。
        private const val WANDER_DP: Float = 6f
        private const val WANDER_QUIET_SCALE: Float = 0.16f
        private const val DETAIL_QUIET_SCALE: Float = 0.09f
    }
}
