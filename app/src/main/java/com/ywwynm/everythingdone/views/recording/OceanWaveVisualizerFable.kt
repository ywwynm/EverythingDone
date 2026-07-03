package com.ywwynm.everythingdone.views.recording

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

import com.ywwynm.everythingdone.model.ThingBackground

import java.util.Random

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fable 海浪可视化（Voice Waveform 的海洋主题实现）。
 *
 * 波场 = 每层 2 个环境正弦分量（锐化波峰、深水色散）+ 声音波包（Hann 包络、
 * 群速行进、全局相位场）线性叠加；7 层实心水体自远及近以透明度阶梯绘制。
 * 巨浪为最近层上的专属大波包 + 对数螺线卷头 + 抛物线射流 + 水滴/泡沫粒子。
 * 设计与参数依据 docs/features/audio-visualizer-fable/plan.md。
 *
 * 帧循环由 Choreographer 驱动，以 frameTimeNanos 差值推进（任意刷新率下速度
 * 一致）；onDraw 零分配，Path/数组全部复用。宿主对 View 的 alpha 动画经
 * [onSetAlpha] 吸收进画笔透明度，避免离屏合成。
 */
class OceanWaveVisualizerFable @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), OceanWaveFrameReceiverFable, Choreographer.FrameCallback {

    private val mDp: Float = resources.displayMetrics.density
    private val mRandom = Random()

    // ------------------------------------------------------------ 颜色

    private var mThingBackground: ThingBackground? = null
    private val mLayerPaints = Array(LAYER_COUNT) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    }
    private val mBreakerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val mFoamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // ------------------------------------------------------------ 几何与复用缓冲

    private var mW = 0f
    private var mH = 0f
    private var mPad = 0f
    private val mGridX = FloatArray(GRID_POINTS)
    private val mPacketField = FloatArray(GRID_POINTS)
    private val mPtX = FloatArray(GRID_POINTS)
    private val mPtY = FloatArray(GRID_POINTS)
    private val mPaths = Array(LAYER_COUNT) { Path() }
    private val mBreakerPath = Path()

    // ------------------------------------------------------------ 环境波分量（每层 2 个）

    private val mCompK = FloatArray(LAYER_COUNT * 2)
    private val mCompOmega = FloatArray(LAYER_COUNT * 2)
    private val mCompPhi = FloatArray(LAYER_COUNT * 2)
    private val mCompAmp = FloatArray(LAYER_COUNT * 2)
    private val mSharpMean = FloatArray(LAYER_COUNT)
    private val mGroupOmega = FloatArray(LAYER_COUNT)
    private val mGroupPhi = FloatArray(LAYER_COUNT)

    // 副分量换代：SEED_STABLE 计时 → 淡出 → 换参数 → 淡入，海面长期不重样。
    private val mReseedFade = FloatArray(LAYER_COUNT) { 1f }
    private val mReseedState = IntArray(LAYER_COUNT)
    private val mReseedTimer = FloatArray(LAYER_COUNT)

    // ------------------------------------------------------------ 音频快照（采集线程写入）

    private val mAudioLock = Any()
    private var mInLoudness = 0f
    private var mInPitchHz = 0f
    private var mInVoiced = false
    private var mInRate = 0f
    private var mInTransient = 0f

    // ------------------------------------------------------------ 驱动状态

    private var mLevel = 0f
    private var mT = 0f
    private var mLastFrameNanos = 0L
    private var mRunning = false

    // ------------------------------------------------------------ 波包池

    private val mPkActive = BooleanArray(MAX_PACKETS)
    private val mPkBreaker = BooleanArray(MAX_PACKETS)
    private val mPkK = FloatArray(MAX_PACKETS)
    private val mPkOmega = FloatArray(MAX_PACKETS)
    private val mPkPhi = FloatArray(MAX_PACKETS)
    private val mPkAmp = FloatArray(MAX_PACKETS)
    private val mPkHalfW = FloatArray(MAX_PACKETS)
    private val mPkXc = FloatArray(MAX_PACKETS)
    private val mPkAge = FloatArray(MAX_PACKETS)
    private var mSpawnCountdown = 0.4f
    private var mTransientCooldown = 0f

    // ------------------------------------------------------------ 巨浪

    private var mBkState = BK_IDLE
    private var mBkAge = 0f
    private var mBkPacket = -1
    private var mBkSweep = 0f
    private var mBkCrestX = 0f
    private var mBkCrestY = 0f
    private var mBkR0 = 0f
    private var mBkTipX = 0f
    private var mBkTipY = 0f
    private var mBkTipVx = 0f
    private var mBkTipVy = 0f
    private var mBkFade = 1f
    private var mBkCooldown = 0f
    private var mLoudHighTime = 0f
    private var mSustainCountdown = 0f

    // ------------------------------------------------------------ 粒子池

    private val mDropActive = BooleanArray(MAX_DROPLETS)
    private val mDropX = FloatArray(MAX_DROPLETS)
    private val mDropY = FloatArray(MAX_DROPLETS)
    private val mDropVx = FloatArray(MAX_DROPLETS)
    private val mDropVy = FloatArray(MAX_DROPLETS)
    private val mDropR = FloatArray(MAX_DROPLETS)

    private val mFoamActive = BooleanArray(MAX_FOAM)
    private val mFoamX = FloatArray(MAX_FOAM)
    private val mFoamR = FloatArray(MAX_FOAM)
    private val mFoamLife = FloatArray(MAX_FOAM)
    private val mFoamDrift = FloatArray(MAX_FOAM)

    init {
        initAmbientComponents()
        mLevel = REST_LEVEL_DP * mDp
    }

    // ================================================================ 对外接口

    fun setThingBackground(background: ThingBackground) {
        mThingBackground = background
        rebuildPaints()
        invalidate()
    }

    override fun receive(frame: OceanWaveAudioFrameFable) {
        synchronized(mAudioLock) {
            mInLoudness = frame.loudness
            mInPitchHz = frame.pitchHz
            mInVoiced = frame.voiced
            mInRate = frame.syllableRate
            if (frame.transient > mInTransient) {
                mInTransient = frame.transient
            }
        }
    }

    // ================================================================ 生命周期与帧循环

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mRunning = true
        mLastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        mRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!mRunning) {
            return
        }
        if (mLastFrameNanos != 0L) {
            var dt = (frameTimeNanos - mLastFrameNanos) / 1.0e9f
            if (dt > MAX_DT) dt = MAX_DT
            if (dt > 0f) {
                update(dt)
                invalidate()
            }
        }
        mLastFrameNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    /**
     * 宿主 animate().alpha() 吸收进画笔，整个 View 不再需要离屏合成。
     * 注意：XML 的 android:alpha 在父类构造期间就会触发本回调，此时子类字段
     * 尚未初始化，applyPaintAlphas 内部有相应防护。
     */
    override fun onSetAlpha(alpha: Int): Boolean {
        applyPaintAlphas()
        return true
    }

    override fun hasOverlappingRendering(): Boolean = false

    /**
     * Dialog 的 WRAP_CONTENT/AT_MOST 测量链下，View 默认的 getDefaultSize 会按
     * 可用空间上报尺寸，把根 FrameLayout 撑到全屏。本 View 没有固有尺寸：非
     * EXACTLY 模式一律只上报固有最小值，让根布局的 minWidth/minHeight
     * （280×360dp）决定对话框大小；随后 FrameLayout 会对 match_parent 子项以
     * EXACTLY 做第二遍测量，把本 View 铺满最终尺寸。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolvePassiveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolvePassiveSize(suggestedMinimumHeight, heightMeasureSpec)
        )
    }

    private fun resolvePassiveSize(minSize: Int, measureSpec: Int): Int {
        val mode = MeasureSpec.getMode(measureSpec)
        val size = MeasureSpec.getSize(measureSpec)
        return when (mode) {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> min(minSize, size)
            else -> minSize
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mW = w.toFloat()
        mH = h.toFloat()
        mPad = EDGE_PAD_DP * mDp
        val dx = (mW + 2f * mPad) / (GRID_POINTS - 1)
        for (j in 0 until GRID_POINTS) {
            mGridX[j] = -mPad + dx * j
        }
        rebuildPaints()
    }

    // ================================================================ 初始化

    private fun initAmbientComponents() {
        val g = G_DP * mDp
        for (i in 0 until LAYER_COUNT) {
            val nearBoost = 0.85f + 0.4f * i / (LAYER_COUNT - 1f)
            val lambda1 = LAMBDA_DP[i] * mDp * jitter(0.9f, 1.1f)
            val lambda2 = lambda1 * 1.9f * jitter(0.9f, 1.1f)
            for (c in 0..1) {
                val idx = i * 2 + c
                val lambda = if (c == 0) lambda1 else lambda2
                val k = TWO_PI / lambda
                mCompK[idx] = k
                mCompOmega[idx] = sqrt(g * k)
                mCompPhi[idx] = mRandom.nextFloat() * TWO_PI
                val baseAmp = STEEPNESS[i] * lambda / TWO_PI * jitter(0.85f, 1.15f) * nearBoost
                mCompAmp[idx] = if (c == 0) baseAmp else baseAmp * 0.5f
            }
            mSharpMean[i] = sharpenedSineMean(SHARPNESS[i])
            mGroupOmega[i] = 0.12f + 0.18f * mRandom.nextFloat()
            mGroupPhi[i] = mRandom.nextFloat() * TWO_PI
            mReseedTimer[i] = RESEED_MIN_S + mRandom.nextFloat() * RESEED_SPAN_S
            mReseedState[i] = SEED_STABLE
        }
    }

    private fun sharpenedSineMean(p: Float): Float {
        var sum = 0.0
        for (n in 0 until 256) {
            val s = (sin(TWO_PI * n / 256f) + 1f) * 0.5f
            sum += s.toDouble().pow(p.toDouble())
        }
        return (sum / 256.0).toFloat()
    }

    private fun rebuildPaints() {
        val bg = mThingBackground ?: return
        val gradient: Shader? = if (bg.mode == ThingBackground.Mode.GRADIENT && mW > 0f) {
            // 横向整条复用；orientation 含右→左语义时交换端点保持记事的方向感。
            val reversed = bg.orientation == ThingBackground.Orientation.R_L ||
                    bg.orientation == ThingBackground.Orientation.RT_LB ||
                    bg.orientation == ThingBackground.Orientation.RB_LT
            val c0 = if (reversed) bg.endColor else bg.color
            val c1 = if (reversed) bg.color else bg.endColor
            LinearGradient(0f, 0f, mW, 0f, opaque(c0), opaque(c1), Shader.TileMode.CLAMP)
        } else {
            null
        }
        for (i in 0 until LAYER_COUNT) {
            if (gradient != null) {
                mLayerPaints[i].shader = gradient
            } else {
                mLayerPaints[i].shader = null
                mLayerPaints[i].color = opaque(bg.color)
            }
        }
        if (gradient != null) {
            mBreakerPaint.shader = gradient
        } else {
            mBreakerPaint.shader = null
            mBreakerPaint.color = opaque(bg.color)
        }
        mFoamPaint.shader = null
        mFoamPaint.color = blendTowardsWhite(bg.representativeColor(), FOAM_WHITE_MIX)
        applyPaintAlphas()
    }

    private fun applyPaintAlphas() {
        // onSetAlpha 可能在父类构造期间到达（见该回调注释），此时字段还是 null。
        @Suppress("SENSELESS_COMPARISON")
        if (mLayerPaints == null) {
            return
        }
        val hostAlpha = alpha
        for (i in 0 until LAYER_COUNT) {
            mLayerPaints[i].alpha = (LAYER_ALPHA[i] * hostAlpha * 255f).toInt().coerceIn(0, 255)
        }
        mBreakerPaint.alpha = (BREAKER_ALPHA * mBkFade * hostAlpha * 255f).toInt().coerceIn(0, 255)
    }

    // ================================================================ 每帧推进

    private fun update(dt: Float) {
        if (mW <= 0f) {
            return
        }
        mT += dt

        val loud: Float
        val pitch: Float
        val rate: Float
        val transient: Float
        synchronized(mAudioLock) {
            loud = mInLoudness
            pitch = mInPitchHz
            rate = mInRate
            transient = mInTransient
            mInTransient = 0f
        }

        // 水位：攻快释慢。
        val floorPx = FLOOR_LEVEL_DP * mDp
        val ceilPx = CEIL_LEVEL_DP * mDp
        val target = floorPx + loud.pow(1.1f) * (ceilPx - floorPx)
        val tau = if (target > mLevel) ATTACK_TAU else RELEASE_TAU
        mLevel += (target - mLevel) * (1f - exp(-dt / tau))

        updateReseed(dt)
        updateSpawning(dt, loud, pitch, rate, transient)
        updatePackets(dt)
        updateBreaker(dt, loud, transient)
        updateDroplets(dt)
        updateFoam(dt)
    }

    private fun updateReseed(dt: Float) {
        for (i in 0 until LAYER_COUNT) {
            when (mReseedState[i]) {
                SEED_STABLE -> {
                    mReseedTimer[i] -= dt
                    if (mReseedTimer[i] <= 0f) mReseedState[i] = SEED_FADE_OUT
                }
                SEED_FADE_OUT -> {
                    mReseedFade[i] -= dt / RESEED_FADE_S
                    if (mReseedFade[i] <= 0f) {
                        mReseedFade[i] = 0f
                        val idx = i * 2 + 1
                        val lambda = mCompK[i * 2].let { TWO_PI / it } * 1.9f * jitter(0.85f, 1.15f)
                        val k = TWO_PI / lambda
                        mCompK[idx] = k
                        mCompOmega[idx] = sqrt(G_DP * mDp * k)
                        mCompPhi[idx] = mRandom.nextFloat() * TWO_PI
                        mReseedState[i] = SEED_FADE_IN
                    }
                }
                SEED_FADE_IN -> {
                    mReseedFade[i] += dt / RESEED_FADE_S
                    if (mReseedFade[i] >= 1f) {
                        mReseedFade[i] = 1f
                        mReseedTimer[i] = RESEED_MIN_S + mRandom.nextFloat() * RESEED_SPAN_S
                        mReseedState[i] = SEED_STABLE
                    }
                }
            }
        }
    }

    private fun updateSpawning(dt: Float, loud: Float, pitch: Float, rate: Float, transient: Float) {
        mSpawnCountdown -= dt
        mTransientCooldown -= dt

        if (loud > SPAWN_LOUD_GATE) {
            if (mSpawnCountdown <= 0f) {
                spawnVoicePacket(loud, pitch)
                val interval = (0.9f / max(rate, 0.9f)).coerceIn(SPAWN_MIN_S, SPAWN_MAX_S)
                mSpawnCountdown = interval * jitter(0.85f, 1.15f)
            }
        } else {
            // 静音期不生成，但保持短倒计时，声音一来即刻响应。
            if (mSpawnCountdown > 0.25f) mSpawnCountdown = 0.25f
        }

        if (transient > MICRO_TRANSIENT_GATE && mTransientCooldown <= 0f) {
            spawnMicroPacket(transient)
            mTransientCooldown = MICRO_COOLDOWN_S
        }
    }

    private fun spawnVoicePacket(loud: Float, pitch: Float) {
        val lambdaDp = if (pitch > 0f) {
            (240f * (pitch / 80f).pow(-0.55f)).coerceIn(78f, 250f)
        } else {
            150f * jitter(0.85f, 1.15f)
        }
        val amp = (8f + 22f * loud.pow(1.3f)) * mDp * jitter(0.85f, 1.15f)
        spawnPacket(lambdaDp * mDp, amp, 1.25f, offscreenSpawnX(lambdaDp * mDp * 1.25f), false)
    }

    private fun spawnMicroPacket(transient: Float) {
        val lambda = 90f * mDp * jitter(0.9f, 1.1f)
        val amp = (4f + 14f * transient) * mDp
        spawnPacket(lambda, amp, 0.9f, offscreenSpawnX(lambda * 0.9f), false)
    }

    private fun offscreenSpawnX(halfW: Float): Float = mW + halfW + mPad

    private fun spawnPacket(lambda: Float, amp: Float, halfWidthInLambda: Float,
                            xc: Float, breaker: Boolean): Int {
        var slot = -1
        var oldestAge = -1f
        for (p in 0 until MAX_PACKETS) {
            if (!mPkActive[p]) {
                slot = p
                break
            }
            if (!mPkBreaker[p] && mPkAge[p] > oldestAge) {
                oldestAge = mPkAge[p]
                slot = p
            }
        }
        if (slot < 0) return -1
        val k = TWO_PI / lambda
        mPkActive[slot] = true
        mPkBreaker[slot] = breaker
        mPkK[slot] = k
        mPkOmega[slot] = sqrt(G_DP * mDp * k)
        mPkPhi[slot] = mRandom.nextFloat() * TWO_PI
        mPkAmp[slot] = amp
        mPkHalfW[slot] = lambda * halfWidthInLambda
        mPkXc[slot] = xc
        mPkAge[slot] = 0f
        return slot
    }

    private fun updatePackets(dt: Float) {
        for (p in 0 until MAX_PACKETS) {
            if (!mPkActive[p]) continue
            mPkAge[p] += dt
            // 包络按群速 = 相速/2 向左行进；载波峰以相速穿过包络。
            mPkXc[p] -= mPkOmega[p] / (2f * mPkK[p]) * dt
            val decayedOut = mPkAge[p] > PK_SUSTAIN_S && packetEnvelopeGain(p) < 0.02f
            if (mPkXc[p] + mPkHalfW[p] < -2f * mPad || decayedOut) {
                if (p == mBkPacket && mBkState != BK_IDLE && mBkState != BK_FADE) {
                    continue // 巨浪主体包由巨浪状态机收尾
                }
                mPkActive[p] = false
            }
        }
    }

    /** 起振 ease-in + 入画后期指数衰减的幅度系数。 */
    private fun packetEnvelopeGain(p: Int): Float {
        val age = mPkAge[p]
        val easeU = min(age / PK_EASE_IN_S, 1f)
        var gain = easeU * easeU * (3f - 2f * easeU)
        if (age > PK_SUSTAIN_S) {
            gain *= exp(-(age - PK_SUSTAIN_S) / PK_DECAY_TAU_S)
        }
        return gain
    }

    // ================================================================ 巨浪

    private fun updateBreaker(dt: Float, loud: Float, transient: Float) {
        mBkCooldown -= dt
        if (loud > SUSTAIN_LOUDNESS) {
            mLoudHighTime += dt
        } else {
            mLoudHighTime = max(0f, mLoudHighTime - dt * 2f)
        }
        if (mLoudHighTime > SUSTAIN_HOLD_S) {
            mSustainCountdown -= dt
        }

        when (mBkState) {
            BK_IDLE -> {
                val transientTrigger = transient > BK_TRANSIENT_GATE && loud > BK_TRANSIENT_LOUDNESS
                val sustainTrigger = mLoudHighTime > SUSTAIN_HOLD_S && mSustainCountdown <= 0f
                if ((transientTrigger || sustainTrigger) && mBkCooldown <= 0f && mW > 0f) {
                    startBreaker(loud)
                    mSustainCountdown = SUSTAIN_PERIOD_MIN_S +
                            mRandom.nextFloat() * SUSTAIN_PERIOD_SPAN_S
                }
            }
            BK_SWELL -> {
                if (mBkPacket < 0 || !mPkActive[mBkPacket]) {
                    finishBreaker()
                } else {
                    trackCrest()
                    if (mPkXc[mBkPacket] <= mW * BK_CURL_AT_X) {
                        mBkState = BK_CURL
                        mBkAge = 0f
                    }
                }
            }
            BK_CURL -> {
                mBkAge += dt
                trackCrest()
                val u = min(mBkAge / BK_CURL_S, 1f)
                mBkSweep = FULL_SWEEP * u * u * (3f - 2f * u)
                if (u >= 1f) {
                    mBkState = BK_FALL
                    mBkAge = 0f
                    val cp = mPkOmega[mBkPacket] / mPkK[mBkPacket]
                    spiralPoint(mBkSweep)
                    mBkTipX = mSpiralX
                    mBkTipY = mSpiralY
                    mBkTipVx = -1.15f * cp
                    mBkTipVy = 60f * mDp
                }
            }
            BK_FALL -> {
                mBkAge += dt
                trackCrest()
                mBkTipX += mBkTipVx * dt
                mBkTipY += mBkTipVy * dt
                mBkTipVy += DROP_G_DP * mDp * dt
                if (mBkTipY >= surfaceYNear(mBkTipX) || mBkAge > BK_FALL_MAX_S) {
                    splash()
                }
            }
            BK_FADE -> {
                // 淡出期不再跟踪波峰：主体包可能已被回收复用，锚点保持最后位置。
                mBkAge += dt
                mBkFade = max(0f, 1f - mBkAge / BK_FADE_S)
                applyPaintAlphas()
                if (mBkFade <= 0f) {
                    finishBreaker()
                }
            }
        }
    }

    private fun startBreaker(loud: Float) {
        val lambda = BK_LAMBDA_DP * mDp
        val amp = (30f + 14f * loud) * mDp
        val slot = spawnPacket(lambda, amp, 0.6f, offscreenSpawnX(lambda * 0.6f), true)
        if (slot < 0) return
        mBkPacket = slot
        mBkState = BK_SWELL
        mBkAge = 0f
        mBkSweep = 0f
        mBkFade = 1f
        applyPaintAlphas()
    }

    /** 巨浪主峰位置：解 sin(kx+ωt+φ)=1 取离包络中心最近的波峰。 */
    private fun trackCrest() {
        val p = mBkPacket
        if (p < 0) return
        val k = mPkK[p]
        val base = (HALF_PI - mPkOmega[p] * mT - mPkPhi[p]) / k
        val period = TWO_PI / k
        val n = round((mPkXc[p] - base) / period)
        mBkCrestX = base + n * period
        mBkCrestY = surfaceYNear(mBkCrestX)
        mBkR0 = max(8f * mDp, 0.5f * mPkAmp[p] * packetEnvelopeGain(p))
    }

    private fun splash() {
        val impact = max(mBkTipVy, 120f * mDp)
        val surfaceY = surfaceYNear(mBkTipX)
        var spawned = 0
        for (d in 0 until MAX_DROPLETS) {
            if (spawned >= BK_DROPLET_COUNT) break
            if (mDropActive[d]) continue
            mDropActive[d] = true
            mDropX[d] = mBkTipX + (mRandom.nextFloat() - 0.5f) * 10f * mDp
            mDropY[d] = surfaceY - 2f * mDp
            // 上扇形：-150°..-30°（y 向下，sin 为负即向上）。
            val a = Math.toRadians((-150 + mRandom.nextInt(121)).toDouble())
            val speed = impact * (0.25f + 0.45f * mRandom.nextFloat())
            mDropVx[d] = (cos(a) * speed).toFloat()
            mDropVy[d] = (sin(a) * speed).toFloat()
            mDropR[d] = (1.5f + 1.5f * mRandom.nextFloat()) * mDp
            spawned++
        }
        var foamSpawned = 0
        for (f in 0 until MAX_FOAM) {
            if (foamSpawned >= BK_FOAM_COUNT) break
            if (mFoamActive[f]) continue
            spawnFoamAt(f, mBkTipX + (mRandom.nextFloat() - 0.5f) * 44f * mDp,
                    (2f + 2f * mRandom.nextFloat()) * mDp)
            foamSpawned++
        }
        // 落点次级波包：落水能量转化为向左传播的短波，与水面融合。
        spawnPacket(60f * mDp, 10f * mDp, 0.9f, mBkTipX, false)
        // 主体包进入衰减段，随波继续前行淡出。
        if (mBkPacket >= 0) {
            mPkAge[mBkPacket] = max(mPkAge[mBkPacket], PK_SUSTAIN_S + 0.6f)
        }
        mBkCooldown = BK_COOLDOWN_MIN_S + mRandom.nextFloat() * BK_COOLDOWN_SPAN_S
        mBkState = BK_FADE
        mBkAge = 0f
    }

    private fun finishBreaker() {
        if (mBkPacket >= 0) {
            mPkBreaker[mBkPacket] = false
        }
        mBkPacket = -1
        mBkState = BK_IDLE
        mBkFade = 1f
        applyPaintAlphas()
    }

    // ================================================================ 粒子

    private fun updateDroplets(dt: Float) {
        val g = DROP_G_DP * mDp
        for (d in 0 until MAX_DROPLETS) {
            if (!mDropActive[d]) continue
            mDropX[d] += mDropVx[d] * dt
            mDropY[d] += mDropVy[d] * dt
            mDropVy[d] += g * dt
            if (mDropVy[d] > 0f && mDropY[d] >= surfaceYNear(mDropX[d])) {
                mDropActive[d] = false
                for (f in 0 until MAX_FOAM) {
                    if (!mFoamActive[f]) {
                        spawnFoamAt(f, mDropX[d], mDropR[d])
                        break
                    }
                }
            }
        }
    }

    private fun spawnFoamAt(f: Int, x: Float, r: Float) {
        mFoamActive[f] = true
        mFoamX[f] = x
        mFoamR[f] = r
        mFoamLife[f] = FOAM_LIFE_S
        mFoamDrift[f] = -(20f + 20f * mRandom.nextFloat()) * mDp
    }

    private fun updateFoam(dt: Float) {
        for (f in 0 until MAX_FOAM) {
            if (!mFoamActive[f]) continue
            mFoamLife[f] -= dt
            mFoamX[f] += mFoamDrift[f] * dt
            if (mFoamLife[f] <= 0f || mFoamX[f] < -mPad) {
                mFoamActive[f] = false
            }
        }
    }

    // ================================================================ 波场求值

    /** 波包场在连续 x 处的值（巨浪主体包也在其中）。 */
    private fun packetFieldAt(x: Float): Float {
        var sum = 0f
        for (p in 0 until MAX_PACKETS) {
            if (!mPkActive[p]) continue
            val u = (x - mPkXc[p]) / mPkHalfW[p]
            if (u < -1f || u > 1f) continue
            val env = 0.5f * (1f + cos(PI_F * u))
            sum += mPkAmp[p] * packetEnvelopeGain(p) * env *
                    sin(mPkK[p] * x + mPkOmega[p] * mT + mPkPhi[p])
        }
        return sum
    }

    /** 单层在 x 处的波高（含波包权重与波谷软钳制；正值向上）。 */
    private fun etaLayer(i: Int, x: Float, packetVal: Float): Float {
        val gm = 0.72f + 0.28f * sin(mGroupOmega[i] * mT + mGroupPhi[i])
        var eta = 0f
        val p = SHARPNESS[i]
        for (c in 0..1) {
            val idx = i * 2 + c
            var a = mCompAmp[idx] * gm
            if (c == 1) a *= mReseedFade[i]
            if (a <= 0f) continue
            val theta = mCompK[idx] * x + mCompOmega[idx] * mT + mCompPhi[idx]
            val s = (sin(theta) + 1f) * 0.5f
            eta += a * 2f * (s.pow(p) - mSharpMean[i])
        }
        eta += packetVal * PACKET_WEIGHT[i]
        if (eta < 0f) {
            val lt = (TROUGH_LIMIT_DP + 0.7f * i) * mDp
            val u = -eta / lt
            eta = -lt * u / (1f + u)
        }
        return eta
    }

    /** 最近层水面 y（水滴落水、巨浪锚点用）。 */
    private fun surfaceYNear(x: Float): Float {
        val i = LAYER_COUNT - 1
        val baseY = mH - mLevel
        return baseY - etaLayer(i, x, packetFieldAt(x))
    }

    // ================================================================ 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mW <= 0f || mThingBackground == null) {
            return
        }

        for (j in 0 until GRID_POINTS) {
            mPacketField[j] = packetFieldAt(mGridX[j])
        }

        for (i in 0 until LAYER_COUNT) {
            val baseY = mH - mLevel - LIFT_DP[i] * mDp
            val gerstner = i >= GERSTNER_FROM_LAYER
            val idx0 = i * 2
            for (j in 0 until GRID_POINTS) {
                val x = mGridX[j]
                var px = x
                if (gerstner) {
                    val theta = mCompK[idx0] * x + mCompOmega[idx0] * mT + mCompPhi[idx0]
                    px = x - GERSTNER_QKA / mCompK[idx0] * cos(theta)
                }
                mPtX[j] = px
                mPtY[j] = baseY - etaLayer(i, x, mPacketField[j])
            }
            buildWaterPath(mPaths[i])
            canvas.drawPath(mPaths[i], mLayerPaints[i])
        }

        if (mBkState == BK_CURL || mBkState == BK_FALL || mBkState == BK_FADE) {
            buildCurlPath()
            canvas.drawPath(mBreakerPath, mBreakerPaint)
        }

        val nearPaint = mLayerPaints[LAYER_COUNT - 1]
        for (d in 0 until MAX_DROPLETS) {
            if (mDropActive[d]) {
                canvas.drawCircle(mDropX[d], mDropY[d], mDropR[d], nearPaint)
            }
        }
        for (f in 0 until MAX_FOAM) {
            if (mFoamActive[f]) {
                val a = (mFoamLife[f] / FOAM_LIFE_S).coerceIn(0f, 1f)
                mFoamPaint.alpha = (a * FOAM_ALPHA * alpha * 255f).toInt().coerceIn(0, 255)
                canvas.drawCircle(mFoamX[f], surfaceYNear(mFoamX[f]) - mFoamR[f] * 0.6f,
                        mFoamR[f], mFoamPaint)
            }
        }
    }

    /** 中点二次贝塞尔连接采样点（C1 连续），闭合填充到底边。 */
    private fun buildWaterPath(path: Path) {
        path.rewind()
        path.moveTo(mPtX[0], mPtY[0])
        for (j in 1 until GRID_POINTS) {
            val midX = (mPtX[j - 1] + mPtX[j]) * 0.5f
            val midY = (mPtY[j - 1] + mPtY[j]) * 0.5f
            path.quadTo(mPtX[j - 1], mPtY[j - 1], midX, midY)
        }
        path.lineTo(mPtX[GRID_POINTS - 1], mPtY[GRID_POINTS - 1])
        path.lineTo(mW + mPad, mH + mPad)
        path.lineTo(-mPad, mH + mPad)
        path.close()
    }

    private var mSpiralX = 0f
    private var mSpiralY = 0f

    /** 对数螺线 r = r0·e^(-b·φ)，φ=0 在波峰；结果写入 mSpiralX/Y。 */
    private fun spiralPoint(phi: Float) {
        val r = mBkR0 * exp(-SPIRAL_B * phi)
        val cx = mBkCrestX
        val cyCenter = mBkCrestY + mBkR0
        mSpiralX = cx - r * sin(phi)
        mSpiralY = cyCenter - r * cos(phi)
    }

    private fun buildCurlPath() {
        mBreakerPath.rewind()
        val sweep = if (mBkSweep <= 0f) 0.01f else mBkSweep
        // 外缘：波峰起、向前下方卷入。
        for (s in 0..CURL_SAMPLES) {
            val phi = sweep * s / CURL_SAMPLES
            spiralPoint(phi)
            if (s == 0) {
                mBreakerPath.moveTo(mSpiralX, mSpiralY)
            } else {
                mBreakerPath.lineTo(mSpiralX, mSpiralY)
            }
        }
        // 内缘：带唇厚往回收，唇厚向尖端收窄。
        val thick0 = max(2f * mDp, 0.22f * mBkR0)
        for (s in CURL_SAMPLES downTo 0) {
            val phi = sweep * s / CURL_SAMPLES
            val taper = 1f - 0.85f * (phi / FULL_SWEEP)
            val r = mBkR0 * exp(-SPIRAL_B * phi)
            val ri = max(r - thick0 * taper, r * 0.25f)
            val cx = mBkCrestX
            val cyCenter = mBkCrestY + mBkR0
            mBreakerPath.lineTo(cx - ri * sin(phi), cyCenter - ri * cos(phi))
        }
        mBreakerPath.close()

        // 下落/溅落期的射流：从螺线尖端到弹道尖端的细条。
        if (mBkState == BK_FALL || mBkState == BK_FADE) {
            spiralPoint(sweep)
            val w = 2.4f * mDp
            mBreakerPath.moveTo(mSpiralX - w, mSpiralY)
            mBreakerPath.lineTo(mSpiralX + w, mSpiralY)
            mBreakerPath.lineTo(mBkTipX + w * 0.3f, mBkTipY)
            mBreakerPath.lineTo(mBkTipX - w * 0.3f, mBkTipY)
            mBreakerPath.close()
        }
    }

    // ================================================================ 工具

    private fun jitter(from: Float, to: Float): Float {
        return from + mRandom.nextFloat() * (to - from)
    }

    private fun opaque(color: Int): Int {
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun blendTowardsWhite(color: Int, fraction: Float): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return Color.rgb(
            (r + (255 - r) * fraction).toInt().coerceIn(0, 255),
            (g + (255 - g) * fraction).toInt().coerceIn(0, 255),
            (b + (255 - b) * fraction).toInt().coerceIn(0, 255)
        )
    }

    companion object {
        private const val PI_F = Math.PI.toFloat()
        private const val TWO_PI = (Math.PI * 2.0).toFloat()
        private const val HALF_PI = (Math.PI * 0.5).toFloat()

        private const val LAYER_COUNT = 7
        private const val GRID_POINTS = 66
        private const val EDGE_PAD_DP = 8f
        private const val MAX_DT = 0.064f

        // 远→近的层参数梯度（plan.md 第 4 节）。
        private val LAYER_ALPHA = floatArrayOf(0.16f, 0.22f, 0.30f, 0.40f, 0.52f, 0.68f, 0.92f)
        private val LIFT_DP = floatArrayOf(24f, 20f, 16f, 12f, 8f, 4f, 0f)
        private val LAMBDA_DP = floatArrayOf(320f, 278f, 242f, 210f, 183f, 159f, 138f)
        private val STEEPNESS = floatArrayOf(0.055f, 0.075f, 0.095f, 0.115f, 0.14f, 0.165f, 0.19f)
        private val PACKET_WEIGHT = floatArrayOf(0.30f, 0.38f, 0.48f, 0.58f, 0.70f, 0.84f, 1.0f)
        private val SHARPNESS = floatArrayOf(1.5f, 1.7f, 1.9f, 2.1f, 2.4f, 2.7f, 3.0f)

        private const val G_DP = 360f
        private const val TROUGH_LIMIT_DP = 10f
        private const val GERSTNER_QKA = 0.35f
        private const val GERSTNER_FROM_LAYER = 5

        // 副分量换代：稳定期随机 25–40s，淡出/淡入各 2s。
        private const val SEED_STABLE = 0
        private const val SEED_FADE_OUT = 1
        private const val SEED_FADE_IN = 2
        private const val RESEED_MIN_S = 25f
        private const val RESEED_SPAN_S = 15f
        private const val RESEED_FADE_S = 2f

        private const val FLOOR_LEVEL_DP = 86f
        private const val REST_LEVEL_DP = 100f
        private const val CEIL_LEVEL_DP = 200f
        private const val ATTACK_TAU = 0.12f
        private const val RELEASE_TAU = 0.9f

        private const val MAX_PACKETS = 12
        private const val PK_EASE_IN_S = 0.3f
        private const val PK_SUSTAIN_S = 4f
        private const val PK_DECAY_TAU_S = 2.5f
        private const val SPAWN_LOUD_GATE = 0.06f
        private const val SPAWN_MIN_S = 0.15f
        private const val SPAWN_MAX_S = 1.2f
        private const val MICRO_TRANSIENT_GATE = 0.4f
        private const val MICRO_COOLDOWN_S = 0.12f

        private const val BK_IDLE = 0
        private const val BK_SWELL = 1
        private const val BK_CURL = 2
        private const val BK_FALL = 3
        private const val BK_FADE = 4
        private const val BK_LAMBDA_DP = 170f
        private const val BK_TRANSIENT_GATE = 0.55f
        private const val BK_TRANSIENT_LOUDNESS = 0.5f
        private const val SUSTAIN_LOUDNESS = 0.62f
        private const val SUSTAIN_HOLD_S = 2f
        private const val SUSTAIN_PERIOD_MIN_S = 6f
        private const val SUSTAIN_PERIOD_SPAN_S = 4f
        private const val BK_COOLDOWN_MIN_S = 4f
        private const val BK_COOLDOWN_SPAN_S = 4f
        private const val BK_CURL_AT_X = 0.78f
        private const val BK_CURL_S = 0.4f
        private const val BK_FALL_MAX_S = 0.6f
        private const val BK_FADE_S = 0.35f
        private const val BREAKER_ALPHA = 0.95f
        private const val FULL_SWEEP = (Math.PI * 1.75).toFloat()
        private const val SPIRAL_B = 0.22f
        private const val CURL_SAMPLES = 16

        private const val MAX_DROPLETS = 24
        private const val BK_DROPLET_COUNT = 16
        private const val DROP_G_DP = 1000f
        private const val MAX_FOAM = 24
        private const val BK_FOAM_COUNT = 10
        private const val FOAM_LIFE_S = 1.5f
        private const val FOAM_ALPHA = 0.55f
        private const val FOAM_WHITE_MIX = 0.7f
    }
}
