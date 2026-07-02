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
import android.widget.FrameLayout

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil

import java.util.Random
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
 * 由 [AudioRecorder] 每 100ms 通过 [receive] 推入 [VoiceAudioFrame]，此处只更新缓动目标；
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
    private val mRandom: Random = Random()

    private val mHistoryComp: Array<Array<FloatArray>> =
        Array(INPUT_HISTORY_FRAMES) { Array(LAYERS) { FloatArray(COMPONENTS) } }
    private var mHistoryIndex: Int = 0
    private var mHistoryFilled: Int = 0
    private var mAppliedMotionSeq: Int = 0

    private val mCompPhase: Array<FloatArray> = Array(LAYERS) { FloatArray(COMPONENTS) }
    private var mTime: Float = 0f
    private var mLastFrameNanos: Long = 0L
    private var mRunning: Boolean = false

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
                (0.85f + j * 1.35f + (r.nextFloat() * 2f - 1f) * 0.78f).coerceAtLeast(0.35f)
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
                exp(-j * 0.30f) * (0.50f + r.nextFloat() * 1.05f)
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
    }

    private class MotionTargets(
        val comp: Array<FloatArray>,
        val level: Float,
        val transient: Float,
        val seq: Int
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
        mRunning = true
        mLastFrameNanos = 0L
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        mRunning = false
        super.onDetachedFromWindow()
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
                transient = 0f
            )
        )
    }

    /**
     * 接收来自 [AudioRecorder] 的音频特征。仅更新缓动目标，不直接绘制。
     * RMS/响度给所有分量一个共同底盘；FFT 频段决定不同波分量的涨落；transient 触发短时浪涌。
     */
    internal fun receive(frame: VoiceAudioFrame) {
        val loudness: Float = clamp01(frame.loudness)
        // 非线性曲线：压低中小音量、突出大音量，让浪的大小声对比更强
        val shapedLoudness: Float = loudness.pow(CURVE_GAMMA)
        val desired = Array(LAYERS) { i ->
            FloatArray(COMPONENTS) { j ->
                val bandDrive: Float = bandDrive(frame, i, j).pow(BAND_GAMMA)
                val drive: Float = clamp01(shapedLoudness * GLOBAL_LOUDNESS_MIX + bandDrive * BAND_MIX)
                (drive * mCompWeight[i][j]).coerceAtLeast(0f)
            }
        }
        mIncomingMotion = MotionTargets(desired, shapedLoudness, frame.transient, mIncomingMotion.seq + 1)
    }

    private fun rebuildPaints() {
        val w: Int = width
        if (w <= 0) {
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
                paint.shader = LinearGradient(
                    0f, 0f, w.toFloat(), 0f,
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
        mTime += dt
        storeIncomingMotionIfNeeded()

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
                mCompPhase[i][j] += mDrift[i][j] * dt
            }
        }
        val levelK: Float = 1f - exp(-dt / TAU_LEVEL)
        mCurrentLevel += (mTargetLevel - mCurrentLevel) * levelK
        for (i in 0 until LAYERS) {
            if (mLayerSurgeCountdown[i] > 0f) {
                mLayerSurgeCountdown[i] -= dt
                if (mLayerSurgeCountdown[i] <= 0f) {
                    if (mLayerPendingSurge[i] > mLayerSurgeTarget[i]) {
                        mLayerSurgeTarget[i] = mLayerPendingSurge[i]
                    }
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
                val wander: Float =
                    WANDER_DP * mDensity * sin(mWanderSpeed[i] * mTime + mWanderPhase[i])
                val layerSurfaceY: Float = surfaceY + wander
                val ampPx: Float = maxAmpPx * mAmpFactor[i] * (1f + mLayerCurrentSurge[i] * SURGE_AMP_BOOST)
                val crestFactor: Float = (CREST_FACTOR + mLayerCurrentSurge[i] * mLayerSurgeCrest[i])
                    .coerceAtMost(CREST_FACTOR_MAX)

                mPath.rewind()
                mPath.moveTo(0f, bottom)
                var x = 0f
                while (x <= w) {
                    var s = 0f
                    for (j in 0 until COMPONENTS) {
                        val jitterEnvelope: Float =
                            1f + AMP_JITTER * sin(mJitterSpeed[i][j] * mTime + mJitterPhase[i][j])
                        s += mCurrentComp[i][j] * jitterEnvelope *
                                sin(mK[i][j] * k0 * x + mCompPhase[i][j] + mPhi[i][j])
                    }
                    // 静音微动：安静时各分量趋 0，叠一个极小的持续波，让水面仍轻轻荡漾不僵死
                    s += IDLE_AMP * sin(mK[i][0] * k0 * x + mCompPhase[i][0] + mPhi[i][0])
                    val shapedS: Float = shapeWave(s, crestFactor)
                    val rawY: Float = layerSurfaceY - ampPx * shapedS
                    // 顶部软限：浪尖平滑趋近上界，绝不糊住上方计时文本（水位抬高后的护栏）
                    val y: Float = topLimit + soft * ln(1f + exp((rawY - topLimit) / soft))
                    mPath.lineTo(x, y)
                    x += if (w / 120f > 2f) w / 120f else 2f
                }
                mPath.lineTo(w.toFloat(), bottom)
                mPath.close()
                canvas.drawPath(mPath, mLayerPaints[i])
            }
        }

        if (mRunning) {
            postInvalidateOnAnimation()
        }
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

    private fun clamp01(v: Float): Float {
        if (v < 0f) return 0f
        if (v > 1f) return 1f
        return v
    }

    companion object {
        private const val LAYERS: Int = 6
        private const val COMPONENTS: Int = 5
        private const val TWO_PI: Float = (2.0 * Math.PI).toFloat()

        // 保存最近几帧音频目标，供每层 / 每分量按自己的延迟取样，形成随机先后顺序。
        private const val INPUT_HISTORY_FRAMES: Int = 5
        private const val LAYER_SURGE_MAX_DELAY: Float = 0.22f

        // 单帧目标变化上限：保留每层自己的错峰响应，但避免第二个音节把已成形的波峰突然改高/改低。
        private const val TARGET_RISE_SLEW: Float = 3.2f
        private const val TARGET_FALL_SLEW: Float = 2.1f

        // 音量（分贝）→ 归一化区间。待真机微调。
        private const val MIN_DB: Int = 25
        private const val MAX_DB: Int = 65

        // 音量归一化后的非线性幂指数（>1：压低中小音量、突出大音量，增强大小声对比）。
        private const val CURVE_GAMMA: Float = 1.8f

        // 频段能量的非线性幂指数；略小于响度曲线，让高频细纹不至于被压得太死。
        private const val BAND_GAMMA: Float = 1.35f
        private const val GLOBAL_LOUDNESS_MIX: Float = 0.34f
        private const val BAND_MIX: Float = 0.70f

        // 慢速振幅随机包络深度。随机性随帧连续流动，不在每次 100ms 音频采样时突然换目标。
        private const val AMP_JITTER: Float = 0.32f

        // 水位缓动时间常数（秒）：偏慢，让"整体上升"柔和而非瞬间弹起。
        private const val TAU_LEVEL: Float = 0.25f

        // 瞬态浪涌：突然发声时按每层自己的延迟、强度和衰减推高浪幅，随后自然回落。
        private const val SURGE_AMP_BOOST: Float = 0.55f
        private const val SURGE_TRIGGER_THRESHOLD: Float = 0.20f
        private const val SURGE_ATTACK_TAU: Float = 0.11f
        private const val SURGE_RELEASE_TAU: Float = 0.24f

        // 静音微动的最小波振幅系数（合成权重单位）。
        private const val IDLE_AMP: Float = 0.06f

        // 水位占视图高度：静息 / 最高。刻意把两者拉得很近，让基础水位几乎不随音量变化——
        // 声音的变化主要由波浪振幅体现（见 MAX_AMP_DP），而非整片水位匀速抬升。
        private const val REST_FRAC: Float = 0.36f
        private const val MAX_FRAC: Float = 0.42f

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

        // 顶部软限：浪尖平滑趋近的上界（dp，距视图顶）与软化尺度。水位抬高后防止大浪糊住计时；
        // 浪尖能达到的最高点约为 TOP_LIMIT_DP + SOFT_DP*ln2。
        private const val TOP_LIMIT_DP: Float = 64f
        private const val SOFT_DP: Float = 14f

        // 每层水面基线慢速微漂幅度（dp）。
        private const val WANDER_DP: Float = 6f
    }
}
