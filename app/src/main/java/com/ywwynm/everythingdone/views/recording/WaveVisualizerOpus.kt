package com.ywwynm.everythingdone.views.recording

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil

import java.util.Random
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Opus 录音波浪可视化（第三轮，全新构建）。
 *
 * 水面 = **始终存在的基础波场（多分量行波，有峰有谷、Gerstner 峰尖谷平、振幅随声音涨落，安静
 * 也保留轻微起伏，绝不变成一条平线）** + **离散事件浪包（onset/持续驱动催生、会横向滚动、按
 * 生命周期升起-传播-消退）**。二者叠加后做 Gerstner 不对称整形（峰尖、谷平且有限深），再用
 * centripetal Catmull-Rom 重建为连续路径。6 层深度视差；主体/前景层用纯记事本色，越远越亮越透。
 *
 * 声音塑造的是这群波的振幅、粗细、数量、滚动速度与谷深（见 docs/features/audio-visualizer-opus/
 * decisions.md D1/D5/D8/D14）。音频帧在录音线程到达（[receive]）只更新目标，绘制由 vsync 帧循环
 * 驱动，二者解耦。
 */
class WaveVisualizerOpus @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs), WaveFrameReceiverOpus {

    private val mDensity = resources.displayMetrics.density
    private val mRandom = Random()

    private var mBackground: ThingBackground = ThingBackground.pure(Color.parseColor("#3F51B5"))
    private val mLayerPaints = Array(LAYER_COUNT) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val mLayerLightPaints = Array(LAYER_COUNT) { Paint(Paint.ANTI_ALIAS_FLAG) }   // 渐变记事竖直光照覆盖（第二遍）
    private var mUseLightOverlay = false

    // 基础波场：每层各自的多分量行波参数（实例级随机，每次打开略不同）
    private val mBaseK = Array(LAYER_COUNT) { FloatArray(BASE_COMPS) }
    private val mBaseDrift = Array(LAYER_COUNT) { FloatArray(BASE_COMPS) }
    private val mBasePhase = Array(LAYER_COUNT) { FloatArray(BASE_COMPS) }
    private val mBaseWeight = Array(LAYER_COUNT) { FloatArray(BASE_COMPS) }
    private val mBaseAmpScale = FloatArray(LAYER_COUNT)
    private val mSpawnWeightTmp = FloatArray(LAYER_COUNT)   // spawnWave 落层采样临时权重（避免每次分配）

    // 建议3.2：基础波场相量递推的预计算角步长（cos/sin(k·dx)，dx=1/(RENDER_N-1)），消除逐点 sin
    private val mCosDx = Array(LAYER_COUNT) { FloatArray(BASE_COMPS) }
    private val mSinDx = Array(LAYER_COUNT) { FloatArray(BASE_COMPS) }
    private val mPhS = FloatArray(BASE_COMPS)   // 相量 sin/cos 递推 scratch（逐层逐帧复用）
    private val mPhC = FloatArray(BASE_COMPS)
    private val mWobW = FloatArray(BASE_COMPS)  // 本帧起伏后的分量权重 scratch
    // 建议5：各分量缓慢时变起伏参数（去机械感，作用在权重上、不碰流向/推进）
    private val mBaseWobbleK = Array(LAYER_COUNT) { FloatArray(BASE_COMPS) }
    private val mBaseWobblePhase = Array(LAYER_COUNT) { FloatArray(BASE_COMPS) }

    private val mPackets = ArrayList<WavePacket>(MAX_PACKETS + 4)
    private val mLayerPacketScratch = ArrayList<WavePacket>(MAX_PACKETS + 4)   // 建议3.1：本层浪包预筛选复用

    @Volatile private var mIncoming: WaveDriveFrameOpus = WaveDriveFrameOpus.SILENCE
    private val mOnsetLock = Any()
    private var mOnsetAccum = 0f
    private var mPendingSecondary = false

    // 平滑后的视觉驱动值
    private var mIntensity = 0f
    private var mPace = 0f
    private var mBrightness = 0f
    private var mQuietness = 1f
    private var mSustain = 0f
    private var mWaterLevel = 0f
    private var mBass = 0f            // 平滑频段/噪声权重（第1条）：驱动浪的尺度倾向与噪声抑制
    private var mTreble = 0f
    private var mNoise = 0f
    private val mLayerDrive = FloatArray(LAYER_COUNT)   // 各层独立平滑的基础驱动（前景灵敏、后层迟缓 → 时间响应不同）

    private var mSpawnTimer = 0f
    private var mFlowTime = 0f       // 变速流动相位累积（流速随声音变化）
    private var mFlowDir = 1f        // 全场统一主流向（整片水面朝同一方向流动）
    private var mLastFrameTime = 0L
    private var mAnimating = false

    private val mPath = Path()
    private val mSurfaceX = FloatArray(RENDER_N)
    private val mSurfaceY = FloatArray(RENDER_N)

    init {
        // 各层**独立**波形（不同波长/相位/振幅倍率）→ 远近波浪高度不同、涨落节奏不同，绝不重复。层间不靠
        // "振幅大小"分主次（主次由 drawWater 的**波峰净空深度阶梯**保证）。
        // 流动：录音场景**主流向固定为从右往左**（mFlowDir=+1 → drift>0 → 波形向 -x 移动 = 右往左）；绝大多数层
        // 顺主流向，少数层（LAYER_REVERSE_PROB）反向（左往右）提升丰富度。各分量相速接近（收窄相速差 → 抑制驻波
        // 干涉、呈现"一列波持续推进"），相速仍遵循温和色散（长波略快）。注意：浪包 dir 约定与 drift 相反，见 spawnWave。
        mFlowDir = 1f
        for (layer in 0 until LAYER_COUNT) {
            val layerNorm = layer.toFloat() / (LAYER_COUNT - 1)          // 0=最远 … 1=最近（前景）
            val cyclesScale = lerp(CYCLES_FAR_SCALE, CYCLES_NEAR_SCALE, layerNorm)  // 远层密（波峰多、细）、近层疏（大而少）
            val layerDir = if (mRandom.nextFloat() < LAYER_REVERSE_PROB) -mFlowDir else mFlowDir  // 少数层反向(左往右)增丰富度
            var wsum = 0f
            for (c in 0 until BASE_COMPS) {
                val cycles = (when (c) { 0 -> rand(0.72f, 1.2f); 1 -> rand(1.6f, 2.7f); else -> rand(3.2f, 4.8f) }) * cyclesScale
                val k = cycles * 2f * Math.PI.toFloat()
                mBaseK[layer][c] = k
                // 相量递推预计算：dx=1/(RENDER_N-1) 的角步长 k·dx（建议3.2）
                val dTheta = k / (RENDER_N - 1)
                mCosDx[layer][c] = cos(dTheta)
                mSinDx[layer][c] = sin(dTheta)
                // 缓慢起伏 LFO（建议5）：很慢的角频率 + 随机相位，各分量各层不同
                mBaseWobbleK[layer][c] = rand(WOBBLE_K_MIN, WOBBLE_K_MAX)
                mBaseWobblePhase[layer][c] = rand(0f, 2f * Math.PI.toFloat())
                // 相速（xNorm/单位 flowTime）：基准 + 温和色散（cycles 小=长波略快），层间仅极小随机差
                val phaseVel = FLOW_VEL_BASE * (1f + FLOW_VEL_DISPERSION * (1.4f - cycles).coerceIn(-0.6f, 0.9f)) * rand(0.92f, 1.08f)
                mBaseDrift[layer][c] = layerDir * k * phaseVel
                mBasePhase[layer][c] = rand(0f, 2f * Math.PI.toFloat())
                val w = when (c) { 0 -> rand(0.52f, 0.64f); 1 -> rand(0.21f, 0.32f); else -> rand(0.10f, 0.16f) }
                mBaseWeight[layer][c] = w
                wsum += w
            }
            for (c in 0 until BASE_COMPS) mBaseWeight[layer][c] /= wsum   // 归一化 → 场值 ∈ [-1,1]
            mBaseAmpScale[layer] = rand(0.84f, 1.29f)   // 各层振幅略有差异 → 高度不重复
        }
    }

    fun setThingBackground(background: ThingBackground) {
        mBackground = background
        rebuildPaints()
        invalidate()
    }

    override fun receive(frame: WaveDriveFrameOpus) {
        mIncoming = frame
        synchronized(mOnsetLock) {
            if (frame.onset > mOnsetAccum) mOnsetAccum = frame.onset
            if (frame.onset >= SECONDARY_ONSET_GATE && frame.intensity >= SECONDARY_INTENSITY_GATE) {
                mPendingSecondary = true
            }
        }
    }

    // ------------------------------------------------------------------ 帧循环
    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        var dt = if (mLastFrameTime == 0L) 0.016f else (now - mLastFrameTime) / 1000f
        mLastFrameTime = now
        if (dt <= 0f) dt = 0.016f
        if (dt > MAX_DT) dt = MAX_DT

        update(dt)
        drawWater(canvas)

        if (shouldAnimate()) postInvalidateOnAnimation() else mAnimating = false
    }

    private fun ensureAnimating() {
        if (!mAnimating && shouldAnimate()) {
            mAnimating = true
            mLastFrameTime = 0L
            postInvalidateOnAnimation()
        }
    }

    private fun shouldAnimate(): Boolean = isAttachedToWindow && width > 0 && height > 0

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ensureAnimating() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); ensureAnimating() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); if (isVisible) ensureAnimating() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (hasWindowFocus) ensureAnimating() }

    // ------------------------------------------------------------------ 状态更新
    private fun update(dt: Float) {
        val f = mIncoming
        mIntensity += (f.intensity - mIntensity) * approach(dt, if (f.intensity > mIntensity) 0.072f else 0.264f)
        mPace += (f.pace - mPace) * approach(dt, if (f.pace > mPace) 0.12f else 0.45f)
        mBrightness += (f.brightness - mBrightness) * approach(dt, 0.25f)
        mQuietness += (f.quietness - mQuietness) * approach(dt, if (f.quietness > mQuietness) 0.36f else 0.10f)
        mSustain += (f.sustainDrive - mSustain) * approach(dt, if (f.sustainDrive > mSustain) 0.144f else 0.224f)
        mWaterLevel += (f.waterLevel - mWaterLevel) * approach(dt, if (f.waterLevel > mWaterLevel) 0.30f else 0.56f)
        // 频段/噪声权重平滑（第1条）：低频厚浪、高频细波的尺度倾向；noiseLike 抑制持续细浪
        mBass += (f.bassWeight - mBass) * approach(dt, BAND_SMOOTH_TAU)
        mTreble += (f.trebleWeight - mTreble) * approach(dt, BAND_SMOOTH_TAU)
        mNoise += (f.noiseLike - mNoise) * approach(dt, NOISE_SMOOTH_TAU)

        // 流动相位按声音变速推进：安静缓流、有声/快节奏更快（该快则快、该慢则慢）
        val flowDrive = max(mIntensity, mSustain * 0.8f)
        mFlowTime += dt * (FLOW_BASE + FLOW_PACE * mPace + FLOW_DRIVE * flowDrive)

        // 各层基础振幅驱动用不同时间常数追踪：前景灵敏（先涨快落）、后层迟缓（滞后平缓）→ 各层涨落节奏不同
        for (layer in 0 until LAYER_COUNT) {
            mLayerDrive[layer] += (flowDrive - mLayerDrive[layer]) * approach(dt, mLayerDriveTau[layer])
        }

        var onset: Float; var secondary: Boolean
        synchronized(mOnsetLock) { onset = mOnsetAccum; mOnsetAccum = 0f; secondary = mPendingSecondary; mPendingSecondary = false }
        if (onset >= ONSET_SPAWN_GATE && mQuietness < QUIET_SPAWN_BLOCK) {
            // 第4条：节奏强(pace高)且 onset 强 → 一小列错峰浪包（"节奏推动水面"）；否则单浪 + 可选副浪
            if (mPace >= TRAIN_PACE_GATE && onset >= TRAIN_ONSET_GATE) {
                spawnWaveTrain(onset, f)
            } else {
                spawnWave(onset, f, primary = true)
                if (secondary) spawnWave(onset * 0.8f, f, primary = false)
            }
        }

        mSpawnTimer -= dt
        if (mSpawnTimer <= 0f) {
            val interval = SUSTAIN_INTERVAL_SLOW - (SUSTAIN_INTERVAL_SLOW - SUSTAIN_INTERVAL_FAST) * mPace
            mSpawnTimer = interval.coerceAtLeast(SUSTAIN_INTERVAL_FAST)
            // noiseLike 抑制持续细浪（第1条）：空调/摩擦等噪声不催生 sustain 浪
            if (mSustain * (1f - NOISE_SUSTAIN_SUPPRESS * mNoise) >= SUSTAIN_SPAWN_GATE &&
                mQuietness < QUIET_SPAWN_BLOCK && mPackets.size < MAX_PACKETS) {
                spawnWave(mSustain * 0.72f, f, primary = true)
            }
        }

        var i = 0
        while (i < mPackets.size) {
            val p = mPackets[i]; p.age += dt
            if (p.age >= p.lifetime) mPackets.removeAt(i) else i++
        }
    }

    private fun spawnWave(strength: Float, f: WaveDriveFrameOpus, primary: Boolean) {
        if (mPackets.size >= MAX_PACKETS) {
            // 只回收已经很淡（接近消亡）的浪；若都还明显，本次不生成——绝不硬删可见浪造成高度突变（问题2）。
            var faded = -1; var minEnv = Float.MAX_VALUE
            for (k in mPackets.indices) {
                val e = lifecycleEnv(mPackets[k])
                if (e < minEnv) { minEnv = e; faded = k }
            }
            if (faded >= 0 && minEnv < RECYCLE_ENV_MAX) mPackets.removeAt(faded) else return
        }
        // 落层：strength 偏置(强→前) + onset 类型微调（第2条）：percussive(无音高宽频冲击)更偏前层、tonal(有音高)
        // 拉向中层；noisy(高噪)幅度压小。频段(第1条)：bass 厚长慢、treble 细快，作尺度偏置叠加在层/亮度之上。
        val tonalness = f.pitchConfidence
        val noisiness = f.noiseLike
        val percussive = ((1f - tonalness) * (1f - 0.6f * noisiness)).coerceIn(0f, 1f)
        val layer = pickLayer((strength * lerp(0.84f, 1.29f, percussive)).coerceIn(0f, 1f))
        val layerNorm = layer.toFloat() / (LAYER_COUNT - 1)
        // 波长：远短近长 + 亮度，叠加频段偏置（bass→更长、treble→更短）
        var wl = lerp(0.32f, 0.92f, layerNorm) * (1f - 0.30f * mBrightness) * lerp(1f, BASS_WL, mBass) * lerp(1f, TREBLE_WL, mTreble)
        wl = wl.coerceIn(0.16f, 1f)
        if (f.pitchConfidence > 0.4f) wl = 0.6f * wl + 0.4f * (1f - f.pitchWavelength).coerceIn(0.18f, 1f)

        val w = width.toFloat().coerceAtLeast(1f)
        val wavelengthPx = lerp(w / 8f, w * 1.29f, wl)
        // 宽度：bass 厚浪更宽（第1条）
        val widthPx = (wavelengthPx * PACKET_WIDTH_FRAC * lerp(1f, BASS_WIDTH, mBass)).coerceAtLeast(MIN_WIDTH_DP * mDensity)
        // 色散：长浪快、短纹慢 + pace（pace 系数拉大 → 快慢差异更明显）；bass 更慢、treble 更快（第1条）
        val speed = DISPERSION_BASE * sqrt(wavelengthPx) * (0.36f + 1.6f * mPace) *
                lerp(1f, BASS_SPEED, mBass) * lerp(1f, TREBLE_SPEED, mTreble) * (if (primary) 1f else rand(0.84f, 1.29f))
        val dir = if (mRandom.nextFloat() < PACKET_FLOW_ALIGN) -mFlowDir else mFlowDir  // dir<0=右往左(与 drift>0 视觉同向)
        val origin = if (dir > 0f) rand(-0.36f * w, 0.36f * w) else rand(0.64f * w, 1.29f * w)
        val ampBase = PACKET_AMP_DP * mDensity
        // noisy 事件幅度压小（第2条）：摩擦/噪声冲击不生成大浪
        val amp = ampBase * (0.45f + 0.64f * max(strength, mIntensity)) * lerp(0.96f, 1.29f, wl) *
                mLayerPacketAmp[layer] * (if (primary) 1f else 0.72f) * (1f - NOISE_AMP_SUPPRESS * noisiness)

        mPackets.add(WavePacket(
            layer = layer, origin = origin, dir = dir, widthPx = widthPx, speed = speed, amp = amp,
            age = 0f,
            // bass 厚浪更长寿（第1条）
            lifetime = LIFETIME_BASE * (0.84f + 0.4f * (1f - mPace)) * lerp(1f, BASS_LIFETIME, mBass) * rand(0.84f, 1.29f),
            // 快升后落 + percussive 前冲(大 skew)、tonal 平滑(小 skew)（第2条）
            riseFrac = rand(0.16f, 0.264f), fallStartFrac = rand(0.64f, 0.84f), skew = lerp(0.16f, 0.42f, percussive)
        ))
    }

    /** 按权重(strength 偏置)采样落层：弱/持续声偏远层(频繁细密)、强击偏近层前景(偶尔大浪)。 */
    private fun pickLayer(bias: Float): Int {
        var wsumL = 0f
        for (l in 0 until LAYER_COUNT) { mSpawnWeightTmp[l] = lerp(LAYER_SPAWN_W_BACK[l], LAYER_SPAWN_W_FRONT[l], bias); wsumL += mSpawnWeightTmp[l] }
        val rSel = mRandom.nextFloat() * wsumL
        var accW = 0f
        for (l in 0 until LAYER_COUNT) { accW += mSpawnWeightTmp[l]; if (rSel < accW) return l }
        return LAYER_COUNT - 1
    }

    /**
     * 浪列（第4条）：节奏强时一次生成一小组共享方向/尺度的浪包，在上游错峰排开、依次进入 →
     * "节奏在推动水面"，比单个随机浪包更有节奏推进感。容量不足时先回收将逝浪，仍不足则退回单浪。
     */
    private fun spawnWaveTrain(strength: Float, f: WaveDriveFrameOpus) {
        val count = (2 + (mPace * 2.4f).toInt()).coerceIn(2, TRAIN_MAX)
        while (mPackets.size + count > MAX_PACKETS) {
            var faded = -1; var minEnv = Float.MAX_VALUE
            for (k in mPackets.indices) { val e = lifecycleEnv(mPackets[k]); if (e < minEnv) { minEnv = e; faded = k } }
            if (faded >= 0 && minEnv < RECYCLE_ENV_MAX) mPackets.removeAt(faded) else { spawnWave(strength, f, primary = true); return }
        }
        // 一组共享参数（percussive 偏前层）
        val percussive = ((1f - f.pitchConfidence) * (1f - 0.6f * f.noiseLike)).coerceIn(0f, 1f)
        val layer = pickLayer((strength * lerp(0.9f, 1.2f, percussive)).coerceIn(0f, 1f))
        val layerNorm = layer.toFloat() / (LAYER_COUNT - 1)
        val wl = (lerp(0.32f, 0.92f, layerNorm) * (1f - 0.30f * mBrightness) * lerp(1f, BASS_WL, mBass) * lerp(1f, TREBLE_WL, mTreble)).coerceIn(0.16f, 1f)
        val w = width.toFloat().coerceAtLeast(1f)
        val wavelengthPx = lerp(w / 8f, w * 1.29f, wl)
        val widthPx = (wavelengthPx * PACKET_WIDTH_FRAC * lerp(1f, BASS_WIDTH, mBass)).coerceAtLeast(MIN_WIDTH_DP * mDensity)
        val speed = DISPERSION_BASE * sqrt(wavelengthPx) * (0.36f + 1.6f * mPace) * lerp(1f, BASS_SPEED, mBass) * lerp(1f, TREBLE_SPEED, mTreble)
        val dir = if (mRandom.nextFloat() < PACKET_FLOW_ALIGN) -mFlowDir else mFlowDir  // dir<0=右往左(与 drift>0 视觉同向)
        val baseOrigin = if (dir > 0f) rand(-0.35f * w, 0f) else rand(w, 1.35f * w)
        val spacing = wavelengthPx * TRAIN_SPACING_WL
        val ampBase = PACKET_AMP_DP * mDensity
        val amp0 = ampBase * (0.45f + 0.65f * max(strength, mIntensity)) * lerp(0.9f, 1.29f, wl) *
                mLayerPacketAmp[layer] * (1f - NOISE_AMP_SUPPRESS * f.noiseLike)
        for (i in 0 until count) {
            mPackets.add(WavePacket(
                layer = layer, origin = baseOrigin - dir * spacing * i, dir = dir, widthPx = widthPx, speed = speed,
                amp = amp0 * (1f - 0.12f * i),      // 队尾略小，避免整列死板
                age = 0f,
                lifetime = LIFETIME_BASE * (0.9f + 0.3f * (1f - mPace)) * lerp(1f, BASS_LIFETIME, mBass) * rand(0.96f, 1.29f),
                riseFrac = rand(0.129f, 0.21f), fallStartFrac = rand(0.72f, 0.84f), skew = lerp(0.2f, 0.42f, percussive)
            ))
        }
    }

    // ------------------------------------------------------------------ 绘制
    private fun drawWater(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val bottom = h + 2f
        val baseSurfaceY = h * (BASE_TOP_FRAC - mWaterLevel * WATER_RANGE_FRAC)
        val topLimitY = h * TOP_LIMIT_FRAC
        val troughSoft = TROUGH_SOFT_DP * mDensity
        val troughMaxPx = h * TROUGH_MAX_FRAC
        val crestSoft = CREST_SOFT_DP * mDensity
        val troughShapeSoft = TROUGH_SHAPE_SOFT_DP * mDensity
        // 层间基线偏移随声音伸缩：安静时各层贴合成一片平静水面（不成"梯田"），响时层间展开 → 6 层充分错开、
        // 后层从前景之上清晰露出、层次丰富。
        val drive = max(mIntensity, mSustain * 0.8f)
        val offsetScale = OFFSET_BASE_SCALE + OFFSET_DRIVE_SCALE * drive

        for (layer in 0 until LAYER_COUNT) {
            val layerBaseY = baseSurfaceY - mLayerOffset[layer] * mDensity * offsetScale
            val maxTroughY = layerBaseY + troughMaxPx
            // 基础波场振幅（px）：各层用**自己的**驱动（前景灵敏、后层迟缓）× 层随机倍率 × 层倍率 → 高度/节奏不重复
            val baseAmpLayer = (BASE_FLOOR_DP + BASE_GAIN_DP * mLayerDrive[layer]) * mDensity * mBaseAmpScale[layer] * mLayerAmp[layer]
            // 波峰净空深度阶梯：前景净空满（能窜到护栏附近），越远的层净空越小（波峰被压得越低）→ 前景结构性主导
            val hCeil = ((layerBaseY - topLimitY) * mLayerCeilFrac[layer]).coerceAtLeast(mDensity)

            // 建议3.1：预筛选本层浪包到复用 scratch，点循环只遍历本层浪 → packet loop 约 6×（原来每层每点扫全部）
            val bucket = mLayerPacketScratch
            bucket.clear()
            for (pi in mPackets.indices) { val p = mPackets[pi]; if (p.layer == layer) bucket.add(p) }

            // 建议3.2 + 5：基础波场相量递推（消除逐点 sin）；分量权重叠加缓慢起伏去机械感（不碰流向）。
            // 初始化本层各分量相量（n=0 → xNorm=0，相位=basePhase + drift·flowTime）与本帧起伏后的权重。
            for (c in 0 until BASE_COMPS) {
                val phi = mBasePhase[layer][c] + mBaseDrift[layer][c] * mFlowTime
                mPhS[c] = sin(phi); mPhC[c] = cos(phi)
                val wob = 1f + WOBBLE_AMP * sin(mBaseWobbleK[layer][c] * mFlowTime + mBaseWobblePhase[layer][c])
                mWobW[c] = mBaseWeight[layer][c] * wob
            }

            for (n in 0 until RENDER_N) {
                val x = w * n / (RENDER_N - 1)
                var s = 0f
                for (c in 0 until BASE_COMPS) s += mWobW[c] * mPhS[c]   // 基础波场：± 有峰有谷（相量递推，非逐点 sin）
                s *= baseAmpLayer
                for (pi in bucket.indices) s += packetContribution(bucket[pi], x)   // 事件浪（正峰）
                s = shapeHeight(s, crestSoft, troughShapeSoft)          // Gerstner 峰尖谷平
                // 波峰按该层净空 tanh 软压缩：中小浪几乎不变、越高压得越狠但始终圆润、严格 < 净空 → 永不拍平成
                // 平台；且后层最高点结构性地低于前景（净空阶梯），无需再硬性截顶。
                if (s > 0f) s = hCeil * tanh(s / hCeil * CREST_COMPRESS_GAIN)
                var y = layerBaseY - s
                y = softUpperLimit(y, maxTroughY, troughSoft)           // 谷不过深、不露按钮
                mSurfaceX[n] = x; mSurfaceY[n] = y
                // 相量沿 x 递推到下一采样点（固定角步长 k·dx；一帧内递推、不跨帧累积漂移）
                if (n < RENDER_N - 1) for (c in 0 until BASE_COMPS) {
                    val cs = mCosDx[layer][c]; val sn = mSinDx[layer][c]
                    val ns = mPhS[c] * cs + mPhC[c] * sn
                    val nc = mPhC[c] * cs - mPhS[c] * sn
                    mPhS[c] = ns; mPhC[c] = nc
                }
            }
            buildSurfacePath(mPath, mSurfaceX, mSurfaceY, bottom)
            val paint = mLayerPaints[layer]
            paint.alpha = mLayerBaseAlpha[layer]
            canvas.drawPath(mPath, paint)
            // 渐变记事：第二遍叠竖直中性光照（顶亮底暗），底色横向渐变方向保留（D24）。整体强度随层景深缩放。
            if (mUseLightOverlay) {
                val light = mLayerLightPaints[layer]
                light.alpha = mLayerBaseAlpha[layer]
                canvas.drawPath(mPath, light)
            }
        }
    }

    /** 一道事件浪在 x 处的高度贡献（≥0 的行进波峰）。 */
    private fun packetContribution(p: WavePacket, x: Float): Float {
        val env = lifecycleEnv(p)
        if (env <= 0.001f) return 0f
        val center = p.origin + p.dir * p.speed * p.age
        val u = x - center
        val ahead = u * p.dir > 0f
        val wSide = if (ahead) p.widthPx * (1f - p.skew) else p.widthPx * (1f + p.skew)
        val uu = u / wSide
        if (uu > 3.5f || uu < -3.5f) return 0f
        return p.amp * env * exp(-uu * uu)
    }

    private fun lifecycleEnv(p: WavePacket): Float {
        val t = (p.age / p.lifetime).coerceIn(0f, 1f)
        return smoothStep(0f, p.riseFrac, t) * (1f - smoothStep(p.fallStartFrac, 1f, t))
    }

    /** Gerstner 不对称整形：峰尖化（轻）、谷压浅且限深，s=0 处 C1 连续。 */
    private fun shapeHeight(s: Float, crestSoft: Float, troughSoft: Float): Float {
        return if (s >= 0f) {
            s * (1f + CREST_FACTOR * (1f - exp(-s / crestSoft)))
        } else {
            s * (TROUGH_FACTOR + (1f - TROUGH_FACTOR) * exp(s / troughSoft))
        }
    }

    /** 软上界：结果 <= maxY（波谷不深过 troughMax）。 */
    private fun softUpperLimit(y: Float, maxY: Float, soft: Float): Float {
        val d = maxY - y
        if (d > 12f * soft) return y
        return maxY - soft * ln(1f + exp(d / soft))
    }

    private fun buildSurfacePath(path: Path, xs: FloatArray, ys: FloatArray, bottom: Float) {
        val n = xs.size
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 0 until n - 1) {
            val p0x = xs[if (i - 1 < 0) 0 else i - 1]; val p0y = ys[if (i - 1 < 0) 0 else i - 1]
            val p1x = xs[i]; val p1y = ys[i]
            val p2x = xs[i + 1]; val p2y = ys[i + 1]
            val p3x = xs[if (i + 2 >= n) n - 1 else i + 2]; val p3y = ys[if (i + 2 >= n) n - 1 else i + 2]
            val t01 = distPow(p0x, p0y, p1x, p1y); val t12 = distPow(p1x, p1y, p2x, p2y); val t23 = distPow(p2x, p2y, p3x, p3y)
            var m1x = (p1x - p0x) / t01 - (p2x - p0x) / (t01 + t12) + (p2x - p1x) / t12
            var m1y = (p1y - p0y) / t01 - (p2y - p0y) / (t01 + t12) + (p2y - p1y) / t12
            var m2x = (p2x - p1x) / t12 - (p3x - p1x) / (t12 + t23) + (p3x - p2x) / t23
            var m2y = (p2y - p1y) / t12 - (p3y - p1y) / (t12 + t23) + (p3y - p2y) / t23
            m1x *= t12; m1y *= t12; m2x *= t12; m2y *= t12
            path.cubicTo(p1x + m1x / 3f, p1y + m1y / 3f, p2x - m2x / 3f, p2y - m2y / 3f, p2x, p2y)
        }
        path.lineTo(xs[n - 1], bottom); path.lineTo(xs[0], bottom); path.close()
    }

    private fun distPow(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        return max(sqrt(sqrt(dx * dx + dy * dy)), 1e-3f)
    }

    // ------------------------------------------------------------------ 颜色（D12）
    private fun rebuildPaints() {
        val w = width.toFloat()
        val h = height.toFloat()
        val bg = mBackground
        // 渐变记事：底色保留横向 orientation，竖直明暗改由第二遍中性光照覆盖承担（ComposeShader 不能组合两个
        // 同类型 LinearGradient，故用两遍绘制，见 D24）。纯色记事：单遍竖直同色系明暗渐变，无需覆盖。
        mUseLightOverlay = bg.mode == ThingBackground.Mode.GRADIENT && w > 0f && h > 0f
        for (layer in 0 until LAYER_COUNT) {
            val paint = mLayerPaints[layer]
            paint.style = Paint.Style.FILL
            paint.shader = null
            val light = mLayerLightPaints[layer]
            light.style = Paint.Style.FILL
            light.shader = null
            val isMain = layer == LAYER_COUNT - 1
            val toneAmt = mLayerTone[layer]
            if (bg.mode == ThingBackground.Mode.GRADIENT && w > 0f) {
                // 底色：横向 orientation 渐变（守"录音水体遵循记事渐变方向"）
                val c0 = if (isMain) bg.color else BackgroundUtil.lighter(bg.color, toneAmt)
                val c1 = if (isMain) bg.endColor else BackgroundUtil.lighter(bg.endColor, toneAmt)
                val reversed = bg.orientation == ThingBackground.Orientation.R_L ||
                        bg.orientation == ThingBackground.Orientation.RT_LB ||
                        bg.orientation == ThingBackground.Orientation.RB_LT
                val start = if (reversed) c1 else c0; val end = if (reversed) c0 else c1
                paint.shader = LinearGradient(0f, 0f, w, 0f, opaque(start), opaque(end), Shader.TileMode.CLAMP)
                // 覆盖：竖直中性光照（顶提亮白 → 中段透明保留底色/方向 → 底压暗黑），叠出 y 方向明暗。
                // D25：明暗包络用 smoothstep（两端斜率为 0、与本色段无缝）密集采样，替代分段线性 → 无可辨分界线。
                if (h > 0f) {
                    light.shader = LinearGradient(
                        0f, 0f, 0f, h,
                        buildOverlayDepthColors(OVERLAY_LIGHT_ALPHA, OVERLAY_DARK_ALPHA), null,
                        Shader.TileMode.CLAMP
                    )
                }
            } else {
                // 纯色记事：单遍竖直同色系明暗渐变——波峰区提亮、主体大段本色、最下方深水区压暗
                // （lighter/darker 同色系，不发灰）。D25：同 smoothstep 密集采样，明暗连续无分界线。
                val base = if (isMain) bg.color else BackgroundUtil.lighter(bg.color, toneAmt)
                if (h > 0f) {
                    paint.shader = LinearGradient(
                        0f, 0f, 0f, h,
                        buildPureDepthColors(base,
                            if (isMain) CREST_LIGHTEN_MAIN else CREST_LIGHTEN_FAR,
                            if (isMain) DEEP_DARKEN_MAIN else DEEP_DARKEN_FAR), null,
                        Shader.TileMode.CLAMP
                    )
                } else {
                    paint.color = opaque(base)
                }
            }
        }
    }

    // D25：竖直深度明暗——smoothstep 包络（两端斜率 0，与本色段无缝）密集采样成多个均匀 stop，
    // 从根上消除分段线性在 stop 处的斜率突变（Mach band → 隐约分界线）。仍是"顶亮—中本色—底暗"。
    private fun depthHi(t: Float): Float = 1f - smoothStep(SHADE_CREST_POS, SHADE_NEUTRAL_HI, t)   // 提亮包络
    private fun depthLo(t: Float): Float = smoothStep(SHADE_NEUTRAL_LO, 1f, t)                     // 压暗包络

    /** 纯色记事：逐 stop 同色系明暗（提亮/压暗区不重叠，净量单侧，过 0 连续）。 */
    private fun buildPureDepthColors(base: Int, crestAmt: Float, deepAmt: Float): IntArray =
        IntArray(DEPTH_STOPS) { i ->
            val t = i.toFloat() / (DEPTH_STOPS - 1)
            val net = depthHi(t) * crestAmt - depthLo(t) * deepAmt
            opaque(if (net >= 0f) BackgroundUtil.lighter(base, net) else BackgroundUtil.darker(base, -net))
        }

    /** 渐变记事：逐 stop 中性光照（提亮=白、压暗=黑，alpha 由包络定；本色段 alpha=0）。 */
    private fun buildOverlayDepthColors(lightAlpha: Int, darkAlpha: Int): IntArray =
        IntArray(DEPTH_STOPS) { i ->
            val t = i.toFloat() / (DEPTH_STOPS - 1)
            val hi = depthHi(t); val lo = depthLo(t)
            if (hi >= lo) Color.argb((hi * lightAlpha).toInt(), 255, 255, 255)
            else Color.argb((lo * darkAlpha).toInt(), 0, 0, 0)
        }

    private fun opaque(c: Int): Int = Color.rgb(Color.red(c), Color.green(c), Color.blue(c))

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); rebuildPaints(); ensureAnimating()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveIntrinsic(widthMeasureSpec, INTRINSIC_W_DP), resolveIntrinsic(heightMeasureSpec, INTRINSIC_H_DP))
    }

    private fun resolveIntrinsic(spec: Int, dp: Float): Int {
        val mode = MeasureSpec.getMode(spec); val size = MeasureSpec.getSize(spec); val intrinsic = (dp * mDensity).toInt()
        return when (mode) { MeasureSpec.EXACTLY -> size; MeasureSpec.AT_MOST -> min(size, intrinsic); else -> intrinsic }
    }

    private fun approach(dt: Float, tau: Float): Float = 1f - exp(-dt / tau)
    private fun smoothStep(a: Float, b: Float, v: Float): Float {
        if (b == a) return if (v >= b) 1f else 0f
        val t = ((v - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t)
    }
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    private fun rand(a: Float, b: Float): Float = a + (b - a) * mRandom.nextFloat()

    private class WavePacket(
        val layer: Int, val origin: Float, val dir: Float, val widthPx: Float, val speed: Float, val amp: Float,
        var age: Float, val lifetime: Float, val riseFrac: Float, val fallStartFrac: Float, val skew: Float
    )

    companion object {
        private const val LAYER_COUNT = 6
        private const val BASE_COMPS = 3
        private const val RENDER_N = 216   // 采样点密度：足够高才能让密波（尤其远层细波）圆润、不成"面筋"
        private const val MAX_PACKETS = 26
        private const val RECYCLE_ENV_MAX = 0.129f   // 只回收生命周期包络低于此的浪（接近消亡）
        private const val MAX_DT = 0.05f

        private const val INTRINSIC_W_DP = 280f
        private const val INTRINSIC_H_DP = 360f

        // 水位（占 h 比例，从上算）：静息盖住 56dp 按钮；涨幅收窄，让浪成为主要表现（P4）。
        private const val BASE_TOP_FRAC = 0.75f
        private const val WATER_RANGE_FRAC = 0.24f   // 水位随响度的升降幅度（加大 → 大小声的水位差更显著）

        // 基础波场振幅：安静 floor 轻微起伏（不平），声音大时汹涌
        private const val BASE_FLOOR_DP = 3.6f
        private const val BASE_GAIN_DP = 26f
        // 基础波场横向流速：相速基准 + 温和色散强度（长波略快）；全场同向。mFlowTime 增速随声音加速。
        private const val FLOW_VEL_BASE = 0.24f
        private const val FLOW_VEL_DISPERSION = 0.3f
        // 静息基速低、pace 系数很高 → 说话快慢的流速差异明显（慢真慢、快真快）
        private const val FLOW_BASE = 0.5f
        private const val FLOW_PACE = 2.8f
        private const val FLOW_DRIVE = 0.5f

        // 事件浪包
        private const val PACKET_AMP_DP = 36f
        private const val MIN_WIDTH_DP = 12f
        private const val PACKET_WIDTH_FRAC = 0.42f
        private const val DISPERSION_BASE = 36f
        private const val LIFETIME_BASE = 2.1f          // 寿命拉长 → 浪包有足够时间横穿并移出屏幕（强化流动）
        private const val PACKET_FLOW_ALIGN = 0.84f      // 浪包顺主流向(右往左)的概率（其余逆向添丰富度）
        private const val LAYER_REVERSE_PROB = 0.18f     // 基础波场各层反向(左往右)的概率（少数层，增丰富度）
        // 频段→浪的尺度偏置（第1条，作偏置叠加在层/亮度上，非硬频段轨道）：bass 厚长慢、treble 细快
        private const val BASS_WL = 1.29f
        private const val TREBLE_WL = 0.84f
        private const val BASS_WIDTH = 1.29f
        private const val BASS_SPEED = 0.84f
        private const val TREBLE_SPEED = 1.29f
        private const val BASS_LIFETIME = 1.25f
        private const val BAND_SMOOTH_TAU = 0.20f
        private const val NOISE_SMOOTH_TAU = 0.25f
        private const val NOISE_SUSTAIN_SUPPRESS = 0.72f  // noiseLike 对持续细浪生成的抑制（第1条）
        private const val NOISE_AMP_SUPPRESS = 0.45f     // noisy onset 对浪幅的抑制（第2条）
        // 浪列（第4条）：节奏强时成组错峰浪包
        private const val TRAIN_PACE_GATE = 0.5f         // pace ≥ 此值才可能触发浪列
        private const val TRAIN_ONSET_GATE = 0.45f       // onset ≥ 此值才可能触发浪列
        private const val TRAIN_MAX = 4                  // 浪列最多浪包数
        private const val TRAIN_SPACING_WL = 0.84f       // 相邻浪间距（波长倍数）

        // Gerstner 整形 + 上下软限
        private const val CREST_FACTOR = 0.21f
        private const val CREST_SOFT_DP = 24f
        private const val CREST_COMPRESS_GAIN = 1.0f   // 波峰 tanh 软压缩增益：越大越早压缩、越贴净空上限
        private const val TROUGH_FACTOR = 0.5f
        private const val TROUGH_SHAPE_SOFT_DP = 18f
        private const val TOP_LIMIT_FRAC = 0.20f    // 波峰最高约到 72dp（护住 20–60dp 计时）
        private const val TROUGH_MAX_FRAC = 0.05f   // 波谷最深约到基线下 18dp（不露按钮）
        private const val TROUGH_SOFT_DP = 12f

        // 生成门槛
        private const val ONSET_SPAWN_GATE = 0.12f
        private const val QUIET_SPAWN_BLOCK = 0.96f
        private const val SECONDARY_ONSET_GATE = 0.45f
        private const val SECONDARY_INTENSITY_GATE = 0.36f
        private const val SUSTAIN_SPAWN_GATE = 0.10f
        private const val SUSTAIN_INTERVAL_SLOW = 0.30f
        private const val SUSTAIN_INTERVAL_FAST = 0.129f

        // 每层视差（0=最后/最远/最亮最透，5=最前/主体/纯本色不透明）
        private val mLayerTone = floatArrayOf(0.56f, 0.42f, 0.32f, 0.21f, 0.129f, 0.0f)
        private val mLayerBaseAlpha = intArrayOf(120, 144, 169, 200, 224, 255)
        // 基线偏移基础梯度（dp，实际乘 offsetScale 伸缩）：**大胆加大**——即使绝对平静(offsetScale=base)也要
        // 让每层基线明显错开、清晰可辨（相邻 12×0.8=9.6dp，远大于平静时的 floor 波动）。远层坐得高、层层叠成透视水面。
        private val mLayerOffset = floatArrayOf(60f, 48f, 36f, 24f, 12f, 0f)
        private const val OFFSET_BASE_SCALE = 0.8f      // 绝对平静时的偏移比例（相邻层基线差 9.6dp，清晰可分）
        private const val OFFSET_DRIVE_SCALE = 0.35f     // 随声音再略展开（层间偏移以 base 为主、较稳定）
        // 波峰净空深度阶梯（× 该层到护栏的净空）：**近层极值高、远层极值低**。近层(前景)满 1.0 → 偶尔能冲很高；
        // 远层压到 0.5 → 极值受限、冲不高、只作细密低浪，前景恒不会被远层盖过。
        private val mLayerCeilFrac = floatArrayOf(0.5f, 0.6f, 0.72f, 0.84f, 0.91f, 1.0f)
        // 各层基础驱动响应时间常数（秒）：前景小=灵敏先涨快落，后层大=迟缓滞后 → 各层涨落节奏不重复
        private val mLayerDriveTau = floatArrayOf(0.42f, 0.36f, 0.30f, 0.24f, 0.18f, 0.13f)
        // 基础波场每层振幅倍率：**远层略大、近层小**。近层(前景)平均振幅低(0.5)→大部分时间平静、不挡后层；
        // 远层(1.29)持续起伏、有存在感，但收敛过大的振幅以免撞净空被 tanh 压变形（保持圆润）。近层高度靠事件大浪。
        private val mLayerAmp = floatArrayOf(1.29f, 1.08f, 1.0f, 0.84f, 0.64f, 0.5f)
        // 事件浪包每层振幅倍率：**近层大、远层小**。近层前景偶尔一记大浪(1.25)、远层只有细密小浪(0.5)。
        private val mLayerPacketAmp = floatArrayOf(0.5f, 0.64f, 0.72f, 0.88f, 1.08f, 1.29f)
        // 基础波场波峰密度随层缩放：远层 ×1.35（波峰多、细密但不过短陡→保持圆润），近层 ×0.72（大而疏）
        private const val CYCLES_FAR_SCALE = 1.35f
        private const val CYCLES_NEAR_SCALE = 0.72f
        // 建议5：基础波场分量权重的缓慢时变起伏（去机械感；作用在权重上，不改流向/推进速度）
        private const val WOBBLE_AMP = 0.18f
        private const val WOBBLE_K_MIN = 0.12f
        private const val WOBBLE_K_MAX = 0.40f
        // 建议1/D24：竖直深度明暗着色（模拟自然水 y 方向明暗）。竖直渐变分三段：顶部提亮（波峰受光）→
        // 中段本色（主体大段保留记事颜色/方向）→ 底部压暗（深水）。归一位置（占 h）：
        // D25：竖直明暗曲线的采样 stop 数（够密 + smoothstep 包络 → 平滑连续、无可辨分界线）
        private const val DEPTH_STOPS = 48
        // 三个 smoothstep 断点（占 h）：顶部满亮到 CREST_POS、提亮平滑淡出到 NEUTRAL_HI（本色）、
        // 本色保持到 NEUTRAL_LO、其下平滑渐入压暗（深水）。断点处 smoothstep 斜率为 0，与本色段无缝。
        private const val SHADE_CREST_POS = 0.18f     // 满亮平台末端（约波峰净空高度）
        private const val SHADE_NEUTRAL_HI = 0.45f    // 提亮淡出到本色
        private const val SHADE_NEUTRAL_LO = 0.84f    // 本色保持到此，其下渐入压暗（"最下方深水区"）
        // 纯色记事：同色系明暗量（不发灰）。提亮/压暗都克制（用户要"稍微"）。
        private const val CREST_LIGHTEN_MAIN = 0.10f
        private const val CREST_LIGHTEN_FAR = 0.16f
        private const val DEEP_DARKEN_MAIN = 0.13f
        private const val DEEP_DARKEN_FAR = 0.09f
        // 渐变记事：第二遍中性光照覆盖的白/黑 alpha（0..255），保留底色横向渐变方向的同时叠 y 方向明暗
        private const val OVERLAY_LIGHT_ALPHA = 36
        private const val OVERLAY_DARK_ALPHA = 48
        // 事件浪包落层权重：弱/持续声用 BACK（偏远层→远层浪频繁），强击按 strength 插值到 FRONT（偏近层→近层偶尔大浪）
        private val LAYER_SPAWN_W_BACK = floatArrayOf(2.24f, 1.8f, 1.5f, 1.29f, 1.08f, 1.0f)
        private val LAYER_SPAWN_W_FRONT = floatArrayOf(0.56f, 0.72f, 1.0f, 1.44f, 1.96f, 2.4f)
    }
}
