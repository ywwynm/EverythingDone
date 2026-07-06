package com.ywwynm.everythingdone.views.recording

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
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
    private val mSurfaceS = FloatArray(RENDER_N)
    private val mRectU = FloatArray(4)
    private val mRectV = FloatArray(4)
    private val mClipU0 = FloatArray(8)
    private val mClipV0 = FloatArray(8)

    @Volatile private var mInputGravityX = 0f
    @Volatile private var mInputGravityY = 1f
    @Volatile private var mInputGravityZ = 0f
    private var mGravityX = 0f
    private var mGravityY = 1f
    private var mStableGravityX = 0f
    private var mStableGravityY = 1f
    private var mPrevTargetGravityX = 0f
    private var mPrevTargetGravityY = 1f
    private var mPrevGravityZ = 0f
    // 自由液面晃动速度场（Phase 1，D48/D49）：1D 交错网格浅水 η+切向速度 u；回荡/爬墙/反射从中涌现。
    // 零均值形变叠在面积守恒平衡线上；只被容器倾斜/前后倾驱动、绝不吃音频（守 D5/D18）。静止时清零休眠。
    private val mSloshH = FloatArray(SLOSH_N)        // 形变（渲染时按层缩放到 px）
    private val mSloshU = FloatArray(SLOSH_N)        // 交错网格切向流速（face i 在 h[i]、h[i+1] 之间）
    private val mSloshRender = FloatArray(RENDER_N)  // 本帧重采样到渲染分辨率的晃动形变（层间复用）
    private var mSloshSubAccum = 0f                  // 固定子步时间累加（与帧率解耦）
    private var mSloshTiltForce = 0f                 // 本帧倾斜激励（重力旋转增量，反对称注入 u）
    private var mSloshZForce = 0f                    // 本帧前后倾激励（z 变化量，对称注入 u）
    private var mSloshAwake = false                  // 速度场是否活跃（否则走静态路径、对音频零影响）
    private var mGravitySeeded = false               // 是否已把重力状态锚定到首个真实读数（防开场收敛喷冲量）
    private var mWaveStartU = 0f
    private var mWaveSpanPx = 0f

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

    fun setContainerGravity(x: Float, y: Float, z: Float) {
        mInputGravityX = x
        mInputGravityY = y
        mInputGravityZ = z
        ensureAnimating()
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
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // View 分离即复位晃动状态：下次重新锚定重力、清空速度场，避免复用时残留导致开场异常
        mGravitySeeded = false; mSloshAwake = false; mSloshSubAccum = 0f
        for (i in 0 until SLOSH_N) { mSloshH[i] = 0f; mSloshU[i] = 0f }
    }
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
        updateContainerGravity(dt)

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

        updateSloshField(dt)

        var i = 0
        while (i < mPackets.size) {
            val p = mPackets[i]; p.age += dt
            // ③ 音频浪包被晃动流速平流：浪骑着晃动水面一起涌。静止时休眠 → 直接跳过、零影响。
            if (mSloshAwake && mWaveSpanPx > 1f) {
                val centerPx = p.origin + p.dir * p.speed * p.age + p.drift
                p.drift += sampleSloshArray(mSloshU, centerPx / mWaveSpanPx) * SLOSH_ADVECT_GAIN * dt
            }
            if (p.age >= p.lifetime) mPackets.removeAt(i) else i++
        }
    }

    private fun updateContainerGravity(dt: Float) {
        val rawX = mInputGravityX
        val rawY = mInputGravityY
        val rawZ = mInputGravityZ
        val len = sqrt(rawX * rawX + rawY * rawY)
        val confidence = smoothStep(GRAVITY_PROJECTION_LOW, GRAVITY_PROJECTION_HIGH, len)
        val rawUnitX = if (len > 1e-4f) rawX / len else mPrevTargetGravityX
        val rawUnitY = if (len > 1e-4f) rawY / len else mPrevTargetGravityY
        if (!mGravitySeeded) {
            // 首次可信读数：把重力/稳定方向/prev 全锚定到真实方向，避免从假设的 (0,1) 收敛时喷出大 dθ（开 dialog 即狂涌）。
            if (confidence > 0.5f && len > 1e-4f) {
                mStableGravityX = rawUnitX; mStableGravityY = rawUnitY
                mGravityX = rawUnitX; mGravityY = rawUnitY
                mPrevTargetGravityX = rawUnitX; mPrevTargetGravityY = rawUnitY
                mPrevGravityZ = rawZ
                mGravitySeeded = true
            }
            mSloshTiltForce = 0f
            mSloshZForce = 0f
            return
        }
        var targetX: Float
        var targetY: Float
        if (confidence > 0.08f && len > 1e-4f) {
            val follow = approach(dt, if (confidence > 0.6f) STABLE_GRAVITY_TAU else STABLE_GRAVITY_WEAK_TAU)
            mStableGravityX += (rawUnitX - mStableGravityX) * follow
            mStableGravityY += (rawUnitY - mStableGravityY) * follow
            val stableLen = sqrt(mStableGravityX * mStableGravityX + mStableGravityY * mStableGravityY).coerceAtLeast(1e-4f)
            mStableGravityX /= stableLen
            mStableGravityY /= stableLen
            targetX = mStableGravityX
            targetY = mStableGravityY
        } else {
            val back = approach(dt, FLAT_RETURN_TAU)
            mStableGravityX += (0f - mStableGravityX) * back
            mStableGravityY += (1f - mStableGravityY) * back
            val stableLen = sqrt(mStableGravityX * mStableGravityX + mStableGravityY * mStableGravityY).coerceAtLeast(1e-4f)
            targetX = mStableGravityX / stableLen
            targetY = mStableGravityY / stableLen
            mStableGravityX = targetX
            mStableGravityY = targetY
        }

        // 倾斜激励：重力方向有符号旋转增量（叉积 z 分量 ≈ 小角度 dθ）→ 反对称注入切向流速。前后倾：z 变化 → 对称。
        // 死区滤掉传感器噪声（否则每帧持续注入、累积成狂涌、永不休眠）；再 clamp 上限，避免一次大动作灌爆速度场。
        val dTheta = deadzone(mPrevTargetGravityX * targetY - mPrevTargetGravityY * targetX, TILT_DEADZONE)
        val zDelta = deadzone(abs((rawZ - mPrevGravityZ) / GRAVITY_NOMINAL), Z_DEADZONE)
        val confidenceWeight = 0.35f + 0.65f * confidence
        mSloshTiltForce = (dTheta * confidenceWeight).coerceIn(-TILT_MAX, TILT_MAX)
        mSloshZForce = (zDelta * confidenceWeight).coerceAtMost(Z_MAX)
        mPrevTargetGravityX = targetX
        mPrevTargetGravityY = targetY
        mPrevGravityZ = rawZ

        val follow = approach(dt, GRAVITY_FOLLOW_TAU)
        mGravityX += (targetX - mGravityX) * follow
        mGravityY += (targetY - mGravityY) * follow
        val currentLen = sqrt(mGravityX * mGravityX + mGravityY * mGravityY).coerceAtLeast(1e-4f)
        mGravityX /= currentLen
        mGravityY /= currentLen
    }

    // ------------------------------------------------------------------ 自由液面晃动速度场（Phase 1，D48/D49）
    /**
     * 1D 交错网格浅水（η=[mSloshH]、切向流速 u=[mSloshU]）：倾斜/前后倾注入 u，浅水动力学自发产生晃动、
     * 爬墙、壁面反射、多次衰减回荡（阻尼调到"甲"，可见 5~8 次）。η 零均值（守恒，形变不改水量），与去均值/
     * 面积守恒天然兼容。固定子步积分（与帧率解耦、稳定）。无激励且能量衰竭时清零休眠 → 走静态路径、对音频零影响。
     */
    private fun updateSloshField(dt: Float) {
        val tilt = mSloshTiltForce
        val zsym = mSloshZForce
        mSloshTiltForce = 0f
        mSloshZForce = 0f
        if (abs(tilt) < 1e-5f && zsym < 1e-5f) {
            if (!mSloshAwake) return
            var energy = 0f
            for (i in 0 until SLOSH_N) energy += mSloshH[i] * mSloshH[i] + mSloshU[i] * mSloshU[i]
            if (energy < SLOSH_SLEEP_EPS * SLOSH_N) {   // 衰竭：清零休眠（此后渲染加 0 = 当前静态路径）
                for (i in 0 until SLOSH_N) { mSloshH[i] = 0f; mSloshU[i] = 0f }
                mSloshAwake = false
                return
            }
        } else {
            mSloshAwake = true
            // 注入激励到切向流速（交错 face）：倾斜反对称（一侧+一侧-）、前后倾对称外涌（两端外推、中间下陷）
            for (i in 0 until SLOSH_N - 1) {
                val faceNorm = (i + 0.5f) / (SLOSH_N - 1)
                // 倾斜：注入 **1 阶速度本征模** sin(π·u)（两壁为 0、中间最大）→ 只激发基础摇摆、几乎不带高次谐波。
                // （均匀注入虽方向对，但会激发一串奇次谐波 → 主摇摆之外持续高频颤动很久；本征模干净得多、摇完就停。）
                val fund = sin(Math.PI.toFloat() * faceNorm)
                val sym = cos(2f * Math.PI.toFloat() * faceNorm)             // 前后倾：平滑对称 2 阶模（辅助）
                mSloshU[i] += (tilt * SLOSH_TILT_GAIN * fund + zsym * SLOSH_Z_SURGE_GAIN * sym) * mDensity
            }
        }
        // 频率随容器宽缩放：宽越大 → G 越小 → 波速越低 → 晃动越慢越大气（≈ ω∝1/√span 色散）
        val g = SLOSH_G * (SLOSH_REF_SPAN_PX / effectiveWaveSpan()).coerceIn(SLOSH_SCALE_MIN, SLOSH_SCALE_MAX)
        mSloshSubAccum += dt
        var steps = 0
        while (mSloshSubAccum >= SLOSH_SUB_DT && steps < SLOSH_MAX_SUB) {
            stepSloshField(g)
            mSloshSubAccum -= SLOSH_SUB_DT
            steps++
        }
        if (mSloshSubAccum > SLOSH_SUB_DT) mSloshSubAccum = 0f   // 长卡顿后不追补、防爆冲
    }

    /** 交错网格浅水单子步：动量(面)→连续(格)→阻尼→零均值。两端 flux=0 = 反射墙（水撞壁反弹）。 */
    private fun stepSloshField(g: Float) {
        for (i in 0 until SLOSH_N - 1) {          // 动量：face 流速被两侧高度差驱动（-g·∂η/∂x）+ 阻尼
            mSloshU[i] += -g * (mSloshH[i + 1] - mSloshH[i])
            mSloshU[i] *= SLOSH_DAMP
        }
        for (i in 0 until SLOSH_N) {              // 连续：格高度被通量散度改变（-HH·∂u/∂x）；墙处 flux=0
            val fluxRight = if (i < SLOSH_N - 1) mSloshU[i] else 0f
            val fluxLeft = if (i > 0) mSloshU[i - 1] else 0f
            mSloshH[i] += -SLOSH_HH * (fluxRight - fluxLeft)
        }
        var mean = 0f                             // 零均值：形变不改变水量（守恒）
        for (i in 0 until SLOSH_N) mean += mSloshH[i]
        mean /= SLOSH_N
        for (i in 0 until SLOSH_N) {              // 零均值 + 安全 clamp（防极端输入下速度场爆冲甩飞浪包/尖峰）
            mSloshH[i] = (mSloshH[i] - mean).coerceIn(-SLOSH_H_CLAMP, SLOSH_H_CLAMP)
            mSloshU[i] = mSloshU[i].coerceIn(-SLOSH_U_CLAMP, SLOSH_U_CLAMP)
        }
    }

    /** 在归一化位置 uNorm∈[0,1] 线性采样晃动数组。 */
    private fun sampleSloshArray(arr: FloatArray, uNorm: Float): Float {
        val x = uNorm.coerceIn(0f, 1f) * (SLOSH_N - 1)
        val i = x.toInt().coerceIn(0, SLOSH_N - 2)
        val frac = x - i
        return arr[i] * (1f - frac) + arr[i + 1] * frac
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

        val w = effectiveWaveSpan()
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
        val w = effectiveWaveSpan()
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
        val diag = sqrt(w * w + h * h)
        val cx = w * 0.5f
        val cy = h * 0.5f
        val gx = mGravityX
        val gy = mGravityY
        val tx = gy
        val ty = -gx
        updateRectLocal(w, h, tx, ty, gx, gy)
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (i in 0 until 4) {
            if (mRectU[i] < minU) minU = mRectU[i]
            if (mRectU[i] > maxU) maxU = mRectU[i]
            if (mRectV[i] < minV) minV = mRectV[i]
            if (mRectV[i] > maxV) maxV = mRectV[i]
        }
        val uStart = minU
        val uSpan = (maxU - minU).coerceAtLeast(1f)
        mWaveStartU = uStart
        mWaveSpanPx = uSpan
        // 容器沿重力轴的实际跨度（竖直=h、横放=w、斜放=矩形在重力向的投影）与计时保护线，作波峰净空/
        // 波谷限深的参照：竖直静止时精确等于旧的 h 口径（复原原动画的峰高/谷深），倾斜时按容器真实深度
        // 自适应。取代此前固定 diag（对角线恒偏大、把前景大浪爆发力削掉约 40dp）。
        val vSpan = (maxV - minV).coerceAtLeast(1f)
        val topLimitV = minV + TOP_LIMIT_FRAC * vSpan
        val fillRatio = currentFillRatio()
        val targetArea = w * h * fillRatio
        val baseSurfaceV = solveBaseV(targetArea)
        val closeDistance = diag * 2f
        val crestSoft = CREST_SOFT_DP * mDensity
        val troughShapeSoft = TROUGH_SHAPE_SOFT_DP * mDensity
        // 层间基线偏移随声音伸缩：安静时各层贴合成一片平静水面（不成"梯田"），响时层间展开 → 6 层充分错开、
        // 后层从前景之上清晰露出、层次丰富。
        val drive = max(mIntensity, mSustain * 0.8f)
        val offsetScale = OFFSET_BASE_SCALE + OFFSET_DRIVE_SCALE * drive
        val save = canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        // 晃动形变重采样到渲染分辨率一次、层间复用（① 自由液面速度场）；休眠时不采样、零影响。
        if (mSloshAwake) for (n in 0 until RENDER_N) mSloshRender[n] = sampleSloshArray(mSloshH, n.toFloat() / (RENDER_N - 1))

        for (layer in 0 until LAYER_COUNT) {
            val equilibriumLayerBaseV = baseSurfaceV - mLayerOffset[layer] * mDensity * offsetScale
            val layerBaseV = equilibriumLayerBaseV
            // 基础波场振幅（px）：各层用**自己的**驱动（前景灵敏、后层迟缓）× 层随机倍率 × 层倍率 → 高度/节奏不重复
            val baseAmpLayer = (BASE_FLOOR_DP + BASE_GAIN_DP * mLayerDrive[layer]) * mDensity * mBaseAmpScale[layer] * mLayerAmp[layer]
            // 波峰净空深度阶梯：净空 = 层基线到计时保护线的重力向距离 × 该层阶梯（前景 1.0 满、越远越小）→
            // 前景结构性主导。竖直时等于旧的"层基线到顶部 72dp"口径，前景大浪恢复能冲到护栏附近的爆发力。
            val hCeil = ((equilibriumLayerBaseV - topLimitV) * mLayerCeilFrac[layer]).coerceAtLeast(mDensity)
            // 波谷限深 = 容器深度 × TROUGH_MAX_FRAC（竖直≈基线下 18dp，护住录音按钮）；保留 tanh 连续限深。
            val troughMaxPx = (TROUGH_MAX_FRAC * vSpan).coerceAtLeast(mDensity)

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

            var mean = 0f
            for (n in 0 until RENDER_N) {
                val waveX = uSpan * n / (RENDER_N - 1)
                var s = 0f
                for (c in 0 until BASE_COMPS) s += mWobW[c] * mPhS[c]   // 基础波场：± 有峰有谷（相量递推，非逐点 sin）
                s *= baseAmpLayer
                for (pi in bucket.indices) s += packetContribution(bucket[pi], waveX)   // 事件浪（正峰）
                if (mSloshAwake) s += SLOSH_RENDER_GAIN * mLayerSloshAmp[layer] * mSloshRender[(n + mLayerSloshShift[layer]).coerceIn(0, RENDER_N - 1)]   // ① 晃动形变（层间微错位破除机械齐动）
                s = shapeHeight(s, crestSoft, troughShapeSoft)          // Gerstner 峰尖谷平
                // 波峰按该层净空 tanh 软压缩：中小浪几乎不变、越高压得越狠但始终圆润、严格 < 净空 → 永不拍平成
                // 平台；且后层最高点结构性地低于前景（净空阶梯），无需再硬性截顶。
                if (s > 0f) s = hCeil * tanh(s / hCeil * CREST_COMPRESS_GAIN)
                if (s < 0f) s = -troughMaxPx * tanh((-s) / troughMaxPx)
                mSurfaceS[n] = s
                mean += s
                // 相量沿 x 递推到下一采样点（固定角步长 k·dx；一帧内递推、不跨帧累积漂移）
                if (n < RENDER_N - 1) for (c in 0 until BASE_COMPS) {
                    val cs = mCosDx[layer][c]; val sn = mSinDx[layer][c]
                    val ns = mPhS[c] * cs + mPhC[c] * sn
                    val nc = mPhC[c] * cs - mPhS[c] * sn
                    mPhS[c] = ns; mPhC[c] = nc
                }
            }
            mean /= RENDER_N
            for (n in 0 until RENDER_N) {
                val u = uStart + uSpan * n / (RENDER_N - 1)
                val surfaceV = layerBaseV - (mSurfaceS[n] - mean)
                mSurfaceX[n] = cx + tx * u + gx * surfaceV
                mSurfaceY[n] = cy + ty * u + gy * surfaceV
            }
            buildGravitySurfacePath(mPath, mSurfaceX, mSurfaceY, gx, gy, closeDistance)
            val paint = mLayerPaints[layer]
            paint.alpha = mLayerBaseAlpha[layer]
            canvas.drawPath(mPath, paint)
        }
        canvas.restoreToCount(save)
    }

    /** 一道事件浪在 x 处的高度贡献（≥0 的行进波峰）。 */
    private fun packetContribution(p: WavePacket, x: Float): Float {
        val env = lifecycleEnv(p)
        if (env <= 0.001f) return 0f
        val center = p.origin + p.dir * p.speed * p.age + p.drift
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

    private fun currentFillRatio(): Float {
        return (1f - BASE_TOP_FRAC + mWaterLevel * WATER_RANGE_FRAC)
            .coerceIn(MIN_FILL_RATIO, MAX_FILL_RATIO)
    }

    private fun effectiveWaveSpan(): Float {
        return if (mWaveSpanPx > 1f) mWaveSpanPx else width.toFloat().coerceAtLeast(1f)
    }

    private fun updateRectLocal(w: Float, h: Float, tx: Float, ty: Float, gx: Float, gy: Float) {
        setRectLocal(0, -w * 0.5f, -h * 0.5f, tx, ty, gx, gy)
        setRectLocal(1,  w * 0.5f, -h * 0.5f, tx, ty, gx, gy)
        setRectLocal(2,  w * 0.5f,  h * 0.5f, tx, ty, gx, gy)
        setRectLocal(3, -w * 0.5f,  h * 0.5f, tx, ty, gx, gy)
    }

    private fun setRectLocal(i: Int, dx: Float, dy: Float, tx: Float, ty: Float, gx: Float, gy: Float) {
        mRectU[i] = dx * tx + dy * ty
        mRectV[i] = dx * gx + dy * gy
    }

    private fun solveBaseV(targetArea: Float): Float {
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (i in 0 until 4) {
            if (mRectV[i] < minV) minV = mRectV[i]
            if (mRectV[i] > maxV) maxV = mRectV[i]
        }
        var low = minV - 1f
        var high = maxV + 1f
        repeat(22) {
            val mid = (low + high) * 0.5f
            if (clippedWaterArea(mid) > targetArea) {
                low = mid
            } else {
                high = mid
            }
        }
        return (low + high) * 0.5f
    }

    private fun clippedWaterArea(baseV: Float): Float {
        var outN = 0
        for (i in 0 until 4) {
            val j = (i + 1) and 3
            val cu = mRectU[i]; val cv = mRectV[i]
            val nu = mRectU[j]; val nv = mRectV[j]
            val cIn = cv >= baseV
            val nIn = nv >= baseV
            if (cIn != nIn) {
                val a = ((baseV - cv) / (nv - cv)).coerceIn(0f, 1f)
                mClipU0[outN] = cu + (nu - cu) * a
                mClipV0[outN] = baseV
                outN++
            }
            if (nIn) {
                mClipU0[outN] = nu
                mClipV0[outN] = nv
                outN++
            }
        }
        if (outN < 3) return 0f
        var area = 0f
        for (i in 0 until outN) {
            val j = if (i + 1 == outN) 0 else i + 1
            area += mClipU0[i] * mClipV0[j] - mClipU0[j] * mClipV0[i]
        }
        return abs(area) * 0.5f
    }

    private fun buildGravitySurfacePath(
        path: Path,
        xs: FloatArray,
        ys: FloatArray,
        gx: Float,
        gy: Float,
        far: Float
    ) {
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
        path.lineTo(xs[n - 1] + gx * far, ys[n - 1] + gy * far)
        path.lineTo(xs[0] + gx * far, ys[0] + gy * far)
        path.close()
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
        // 颜色只表达记事底色与层间远近；不再叠加竖直明暗，避免底部显脏。
        for (layer in 0 until LAYER_COUNT) {
            val paint = mLayerPaints[layer]
            paint.style = Paint.Style.FILL
            paint.shader = null
            val isMain = layer == LAYER_COUNT - 1
            val toneAmt = mLayerTone[layer]
            if (bg.mode == ThingBackground.Mode.GRADIENT && w > 0f && h > 0f) {
                // 底色：完整沿用记事的 8 向渐变方向。
                val c0 = if (isMain) bg.color else BackgroundUtil.lighter(bg.color, toneAmt)
                val c1 = if (isMain) bg.endColor else BackgroundUtil.lighter(bg.endColor, toneAmt)
                val layerBg = ThingBackground.gradient(opaque(c0), opaque(c1), bg.orientation)
                paint.shader = BackgroundUtil.createLinearGradient(layerBg, w, h)
            } else {
                val base = if (isMain) bg.color else BackgroundUtil.lighter(bg.color, toneAmt)
                paint.color = opaque(base)
            }
        }
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
    private fun deadzone(v: Float, dz: Float): Float = if (v > dz) v - dz else if (v < -dz) v + dz else 0f

    private class WavePacket(
        val layer: Int, val origin: Float, val dir: Float, val widthPx: Float, val speed: Float, val amp: Float,
        var age: Float, val lifetime: Float, val riseFrac: Float, val fallStartFrac: Float, val skew: Float
    ) {
        var drift: Float = 0f   // ③ 被晃动流速平流的累计横移（静止时恒 0）
    }

    companion object {
        private const val LAYER_COUNT = 6
        private const val BASE_COMPS = 3
        private const val RENDER_N = 216   // 采样点密度：足够高才能让密波（尤其远层细波）圆润、不成"面筋"
        private const val MAX_PACKETS = 26
        private const val RECYCLE_ENV_MAX = 0.129f   // 只回收生命周期包络低于此的浪（接近消亡）
        private const val MAX_DT = 0.05f

        private const val GRAVITY_NOMINAL = 9.80665f
        private const val GRAVITY_PROJECTION_LOW = 1.0f
        private const val GRAVITY_PROJECTION_HIGH = 3.2f
        private const val GRAVITY_FOLLOW_TAU = 0.16f
        private const val STABLE_GRAVITY_TAU = 0.08f
        private const val STABLE_GRAVITY_WEAK_TAU = 0.24f
        private const val FLAT_RETURN_TAU = 1.45f
        private const val MIN_FILL_RATIO = 0.10f
        private const val MAX_FILL_RATIO = 0.88f
        // 自由液面晃动速度场（Phase 1，D48/D49）：1D 交错网格浅水，回荡/爬墙/反射从中涌现。
        private const val SLOSH_N = 48                // 晃动网格点数（渲染时线性采样到 RENDER_N，再走 Catmull-Rom）
        private const val SLOSH_SUB_DT = 1f / 120f    // 固定子步（与帧率解耦、稳定）
        private const val SLOSH_MAX_SUB = 8           // 每帧最多子步（防长卡顿爆冲）
        private const val SLOSH_G = 0.6f              // 动量系数（波速²≈G·HH，定晃动频率）；× 容器宽缩放
        private const val SLOSH_HH = 0.85f            // 连续系数；G·HH<1 保 CFL 稳定
        private const val SLOSH_DAMP = 0.995f         // 每子步流速阻尼：调大→更快settle（约 3~4 次干净摇摆后平静，不再长时间颤动）
        private const val SLOSH_REF_SPAN_PX = 900f    // 频率缩放基准跨度：宽越大越慢（≈ω∝1/√span）
        private const val SLOSH_SCALE_MIN = 0.4f
        private const val SLOSH_SCALE_MAX = 1.4f      // 上限保 G·HH<1（CFL）
        private const val SLOSH_TILT_GAIN = 40f       // 倾斜(rad 旋转增量)→切向流速激励【主振幅旋钮，真机调】。按帧累积故取小值
        private const val SLOSH_Z_SURGE_GAIN = 12f    // 前后倾(z 变化)→对称外涌激励（压低：让倾斜的 1 阶摇摆为主，z 只作辅助）
        private const val SLOSH_RENDER_GAIN = 1.0f    // η→渲染 px 缩放【可调总振幅】
        private const val SLOSH_ADVECT_GAIN = 0.15f   // 流速→浪包平流强度（③，无 tanh 保护、保守起步防甩飞）
        private const val SLOSH_SLEEP_EPS = 0.1f      // 场能量（每点）低于此且无激励 → 清零休眠
        private const val TILT_DEADZONE = 0.006f      // rad/帧倾斜死区：低于此视作传感器噪声/手抖、不注入（防持续狂涌、保休眠）
        private const val TILT_MAX = 0.06f            // 单帧倾斜注入上限（防一次大动作灌爆速度场）
        private const val Z_DEADZONE = 0.01f          // 前后倾(z)死区
        private const val Z_MAX = 0.08f               // 单帧 z 注入上限
        private const val SLOSH_U_CLAMP = 60f         // 速度场安全上限（防甩飞浪包）
        private const val SLOSH_H_CLAMP = 220f        // 形变安全上限（渲染另有 tanh 净空限制兜底）

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
        // 晃动形变每层渲染倍率：**大幅错开**——有的层晃得多、有的少（少的露出各自音频纹理），破除齐动。
        private val mLayerSloshAmp = floatArrayOf(0.45f, 0.95f, 0.6f, 1.0f, 0.75f, 1.0f)
        // 晃动形变每层水平错位（渲染索引，≈±9% 宽）：进一步破除 6 层齐动的机械感（近似各层惯性/相位差）。
        private val mLayerSloshShift = intArrayOf(20, -14, 9, -8, 13, 0)
        // 基础波场波峰密度随层缩放：远层 ×1.35（波峰多、细密但不过短陡→保持圆润），近层 ×0.72（大而疏）
        private const val CYCLES_FAR_SCALE = 1.35f
        private const val CYCLES_NEAR_SCALE = 0.72f
        // 建议5：基础波场分量权重的缓慢时变起伏（去机械感；作用在权重上，不改流向/推进速度）
        private const val WOBBLE_AMP = 0.18f
        private const val WOBBLE_K_MIN = 0.12f
        private const val WOBBLE_K_MAX = 0.40f
        // 事件浪包落层权重：弱/持续声用 BACK（偏远层→远层浪频繁），强击按 strength 插值到 FRONT（偏近层→近层偶尔大浪）
        private val LAYER_SPAWN_W_BACK = floatArrayOf(2.24f, 1.8f, 1.5f, 1.29f, 1.08f, 1.0f)
        private val LAYER_SPAWN_W_FRONT = floatArrayOf(0.56f, 0.72f, 1.0f, 1.44f, 1.96f, 2.4f)
    }
}
