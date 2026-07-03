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

    // 基础波场：每层各自的多分量行波参数（实例级随机，每次打开略不同）
    private val mBaseK = Array(LAYER_COUNT) { FloatArray(BASE_COMPS) }
    private val mBaseDrift = Array(LAYER_COUNT) { FloatArray(BASE_COMPS) }
    private val mBasePhase = Array(LAYER_COUNT) { FloatArray(BASE_COMPS) }
    private val mBaseWeight = Array(LAYER_COUNT) { FloatArray(BASE_COMPS) }
    private val mBaseAmpScale = FloatArray(LAYER_COUNT)
    private val mSpawnWeightTmp = FloatArray(LAYER_COUNT)   // spawnWave 落层采样临时权重（避免每次分配）

    private val mPackets = ArrayList<WavePacket>(MAX_PACKETS + 4)

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
        // 流动：**全场统一主流向**（整片水面朝同一方向流，杜绝层间对冲导致的"原地晃"）；各分量相速接近（收窄
        // 相速差 → 抑制驻波干涉的分叉/合并、呈现"一列波持续推进"的整体流动），相速仍遵循温和色散（长波略快）。
        mFlowDir = if (mRandom.nextBoolean()) 1f else -1f
        for (layer in 0 until LAYER_COUNT) {
            val layerNorm = layer.toFloat() / (LAYER_COUNT - 1)          // 0=最远 … 1=最近（前景）
            val cyclesScale = lerp(CYCLES_FAR_SCALE, CYCLES_NEAR_SCALE, layerNorm)  // 远层密（波峰多、细）、近层疏（大而少）
            var wsum = 0f
            for (c in 0 until BASE_COMPS) {
                val cycles = (when (c) { 0 -> rand(0.7f, 1.2f); 1 -> rand(1.6f, 2.7f); else -> rand(3.2f, 4.8f) }) * cyclesScale
                val k = cycles * 2f * Math.PI.toFloat()
                mBaseK[layer][c] = k
                // 相速（xNorm/单位 flowTime）：基准 + 温和色散（cycles 小=长波略快），层间仅极小随机差
                val phaseVel = FLOW_VEL_BASE * (1f + FLOW_VEL_DISPERSION * (1.4f - cycles).coerceIn(-0.6f, 0.9f)) * rand(0.92f, 1.08f)
                mBaseDrift[layer][c] = mFlowDir * k * phaseVel
                mBasePhase[layer][c] = rand(0f, 2f * Math.PI.toFloat())
                val w = when (c) { 0 -> rand(0.52f, 0.64f); 1 -> rand(0.22f, 0.32f); else -> rand(0.10f, 0.16f) }
                mBaseWeight[layer][c] = w
                wsum += w
            }
            for (c in 0 until BASE_COMPS) mBaseWeight[layer][c] /= wsum   // 归一化 → 场值 ∈ [-1,1]
            mBaseAmpScale[layer] = rand(0.9f, 1.1f)   // 各层振幅略有差异 → 高度不重复
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
        mIntensity += (f.intensity - mIntensity) * approach(dt, if (f.intensity > mIntensity) 0.07f else 0.26f)
        mPace += (f.pace - mPace) * approach(dt, if (f.pace > mPace) 0.12f else 0.45f)
        mBrightness += (f.brightness - mBrightness) * approach(dt, 0.25f)
        mQuietness += (f.quietness - mQuietness) * approach(dt, if (f.quietness > mQuietness) 0.35f else 0.10f)
        mSustain += (f.sustainDrive - mSustain) * approach(dt, if (f.sustainDrive > mSustain) 0.14f else 0.22f)
        mWaterLevel += (f.waterLevel - mWaterLevel) * approach(dt, if (f.waterLevel > mWaterLevel) 0.30f else 0.55f)

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
            spawnWave(onset, f, primary = true)
            if (secondary) spawnWave(onset * 0.8f, f, primary = false)
        }

        mSpawnTimer -= dt
        if (mSpawnTimer <= 0f) {
            val interval = SUSTAIN_INTERVAL_SLOW - (SUSTAIN_INTERVAL_SLOW - SUSTAIN_INTERVAL_FAST) * mPace
            mSpawnTimer = interval.coerceAtLeast(SUSTAIN_INTERVAL_FAST)
            if (mSustain >= SUSTAIN_SPAWN_GATE && mQuietness < QUIET_SPAWN_BLOCK && mPackets.size < MAX_PACKETS) {
                spawnWave(mSustain * 0.7f, f, primary = true)
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
        // 跨全部 6 层分布，保证前景/主体层也有事件浪；音色（亮）让整体更细，不决定落层（D14）。
        // 偏向前景层但不过度：野性高峰多落前景，后层也能得到一些小事件浪（有存在感），不出现突兀高峰。
        // 落层：弱/持续声偏远层（远层浪频繁、细密、幅小），强击按 strength 插值偏近层前景（近层偶尔来一记又大又高的
        // 浪）→ "近极值高但平均低、频率低；远极值低但频率高"，使任意时刻各层都有浪、内容丰富。
        val bias = strength.coerceIn(0f, 1f)
        var wsumL = 0f
        for (l in 0 until LAYER_COUNT) { mSpawnWeightTmp[l] = lerp(LAYER_SPAWN_W_BACK[l], LAYER_SPAWN_W_FRONT[l], bias); wsumL += mSpawnWeightTmp[l] }
        val rSel = mRandom.nextFloat() * wsumL
        var accW = 0f; var layer = 0
        for (l in 0 until LAYER_COUNT) { accW += mSpawnWeightTmp[l]; if (rSel < accW) { layer = l; break } }
        val layerNorm = layer.toFloat() / (LAYER_COUNT - 1)
        // 波长随层：远层短窄（细密频繁）、近层长宽（大而疏），与基础波场同向
        var wl = lerp(0.32f, 0.92f, layerNorm) * (1f - 0.30f * mBrightness)
        wl = wl.coerceIn(0.16f, 1f)
        if (f.pitchConfidence > 0.4f) wl = 0.6f * wl + 0.4f * (1f - f.pitchWavelength).coerceIn(0.18f, 1f)

        val w = width.toFloat().coerceAtLeast(1f)
        val wavelengthPx = lerp(w / 8f, w * 1.1f, wl)
        val widthPx = (wavelengthPx * PACKET_WIDTH_FRAC).coerceAtLeast(MIN_WIDTH_DP * mDensity)
        // 色散：长浪快、短纹慢；再乘 pace。速度足够大，浪是"滚过来"而非"原地长起来"（P3）。
        val speed = DISPERSION_BASE * sqrt(wavelengthPx) * (0.7f + 0.6f * mPace) * (if (primary) 1f else rand(0.85f, 1.15f))
        // 浪包大概率顺主流向（与水面同向、横穿后移出屏幕），少量逆向添真实感
        val dir = if (mRandom.nextFloat() < PACKET_FLOW_ALIGN) mFlowDir else -mFlowDir
        // 从上游屏外一侧出生 → 全程横穿可见区 → 从下游屏外移出（真正"移动并离开"，而非原地长起来）
        val origin = if (dir > 0f) rand(-0.35f * w, 0.35f * w) else rand(0.65f * w, 1.35f * w)
        val ampBase = PACKET_AMP_DP * mDensity
        val amp = ampBase * (0.45f + 0.65f * max(strength, mIntensity)) * lerp(0.9f, 1.18f, wl) * mLayerPacketAmp[layer] * (if (primary) 1f else 0.72f)

        mPackets.add(WavePacket(
            layer = layer, origin = origin, dir = dir, widthPx = widthPx, speed = speed, amp = amp,
            age = 0f,
            lifetime = LIFETIME_BASE * (0.85f + 0.4f * (1f - mPace)) * rand(0.85f, 1.15f),
            // 快升、后段才落 → 升起后保持满幅横穿大部分行程、接近移出时才衰减（强化"移动"观感）
            riseFrac = rand(0.16f, 0.26f), fallStartFrac = rand(0.66f, 0.82f), skew = rand(0.18f, 0.34f)
        ))
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
            for (n in 0 until RENDER_N) {
                val x = w * n / (RENDER_N - 1)
                val xNorm = x / w
                var s = baseFieldNorm(layer, xNorm) * baseAmpLayer     // ± 有峰有谷
                for (pi in mPackets.indices) { val p = mPackets[pi]; if (p.layer == layer) s += packetContribution(p, x) }  // 事件浪（正峰）
                s = shapeHeight(s, crestSoft, troughShapeSoft)          // Gerstner 峰尖谷平
                // 波峰按该层净空 tanh 软压缩：中小浪几乎不变、越高压得越狠但始终圆润、严格 < 净空 → 永不拍平成
                // 平台；且后层最高点结构性地低于前景（净空阶梯），无需再硬性截顶。
                if (s > 0f) s = hCeil * tanh(s / hCeil * CREST_COMPRESS_GAIN)
                var y = layerBaseY - s
                y = softUpperLimit(y, maxTroughY, troughSoft)           // 谷不过深、不露按钮
                mSurfaceX[n] = x; mSurfaceY[n] = y
            }
            buildSurfacePath(mPath, mSurfaceX, mSurfaceY, bottom)
            val paint = mLayerPaints[layer]
            paint.alpha = mLayerBaseAlpha[layer]
            canvas.drawPath(mPath, paint)
        }
    }

    /** 基础波场归一化场值 ∈ [-1,1]：多分量行波叠加（随时间横向漂移 → 滚动、有峰有谷）。 */
    private fun baseFieldNorm(layer: Int, xNorm: Float): Float {
        var s = 0f
        for (c in 0 until BASE_COMPS) {
            s += mBaseWeight[layer][c] * sin(mBaseK[layer][c] * xNorm + mBasePhase[layer][c] + mBaseDrift[layer][c] * mFlowTime)
        }
        return s
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
        val bg = mBackground
        for (layer in 0 until LAYER_COUNT) {
            val paint = mLayerPaints[layer]
            paint.style = Paint.Style.FILL
            paint.shader = null
            val isMain = layer == LAYER_COUNT - 1
            val toneAmt = mLayerTone[layer]
            if (bg.mode == ThingBackground.Mode.GRADIENT && w > 0f) {
                val c0 = if (isMain) bg.color else BackgroundUtil.lighter(bg.color, toneAmt)
                val c1 = if (isMain) bg.endColor else BackgroundUtil.lighter(bg.endColor, toneAmt)
                val reversed = bg.orientation == ThingBackground.Orientation.R_L ||
                        bg.orientation == ThingBackground.Orientation.RT_LB ||
                        bg.orientation == ThingBackground.Orientation.RB_LT
                val start = if (reversed) c1 else c0; val end = if (reversed) c0 else c1
                paint.shader = LinearGradient(0f, 0f, w, 0f, opaque(start), opaque(end), Shader.TileMode.CLAMP)
            } else {
                paint.color = opaque(if (isMain) bg.color else BackgroundUtil.lighter(bg.color, toneAmt))
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

    private class WavePacket(
        val layer: Int, val origin: Float, val dir: Float, val widthPx: Float, val speed: Float, val amp: Float,
        var age: Float, val lifetime: Float, val riseFrac: Float, val fallStartFrac: Float, val skew: Float
    )

    companion object {
        private const val LAYER_COUNT = 6
        private const val BASE_COMPS = 3
        private const val RENDER_N = 112
        private const val MAX_PACKETS = 26
        private const val RECYCLE_ENV_MAX = 0.14f   // 只回收生命周期包络低于此的浪（接近消亡）
        private const val MAX_DT = 0.05f

        private const val INTRINSIC_W_DP = 280f
        private const val INTRINSIC_H_DP = 360f

        // 水位（占 h 比例，从上算）：静息盖住 56dp 按钮；涨幅收窄，让浪成为主要表现（P4）。
        private const val BASE_TOP_FRAC = 0.75f
        private const val WATER_RANGE_FRAC = 0.15f

        // 基础波场振幅：安静 floor 轻微起伏（不平），声音大时汹涌
        private const val BASE_FLOOR_DP = 3.5f
        private const val BASE_GAIN_DP = 26f
        // 基础波场横向流速：相速基准 + 温和色散强度（长波略快）；全场同向。mFlowTime 增速随声音加速。
        private const val FLOW_VEL_BASE = 0.24f
        private const val FLOW_VEL_DISPERSION = 0.3f
        private const val FLOW_BASE = 1.1f
        private const val FLOW_PACE = 1.0f
        private const val FLOW_DRIVE = 0.7f

        // 事件浪包
        private const val PACKET_AMP_DP = 34f
        private const val MIN_WIDTH_DP = 12f
        private const val PACKET_WIDTH_FRAC = 0.42f
        private const val DISPERSION_BASE = 34f
        private const val LIFETIME_BASE = 2.1f          // 寿命拉长 → 浪包有足够时间横穿并移出屏幕（强化流动）
        private const val PACKET_FLOW_ALIGN = 0.78f      // 浪包顺主流向的概率（其余逆向添真实感）

        // Gerstner 整形 + 上下软限
        private const val CREST_FACTOR = 0.22f
        private const val CREST_SOFT_DP = 24f
        private const val CREST_COMPRESS_GAIN = 1.0f   // 波峰 tanh 软压缩增益：越大越早压缩、越贴净空上限
        private const val TROUGH_FACTOR = 0.5f
        private const val TROUGH_SHAPE_SOFT_DP = 18f
        private const val TOP_LIMIT_FRAC = 0.20f    // 波峰最高约到 72dp（护住 20–60dp 计时）
        private const val TROUGH_MAX_FRAC = 0.05f   // 波谷最深约到基线下 18dp（不露按钮）
        private const val TROUGH_SOFT_DP = 12f

        // 生成门槛
        private const val ONSET_SPAWN_GATE = 0.12f
        private const val QUIET_SPAWN_BLOCK = 0.92f
        private const val SECONDARY_ONSET_GATE = 0.45f
        private const val SECONDARY_INTENSITY_GATE = 0.35f
        private const val SUSTAIN_SPAWN_GATE = 0.10f
        private const val SUSTAIN_INTERVAL_SLOW = 0.30f
        private const val SUSTAIN_INTERVAL_FAST = 0.11f

        // 每层视差（0=最后/最远/最亮最透，5=最前/主体/纯本色不透明）
        private val mLayerTone = floatArrayOf(0.52f, 0.42f, 0.32f, 0.22f, 0.11f, 0.0f)
        private val mLayerBaseAlpha = intArrayOf(120, 145, 170, 200, 230, 255)
        // 基线偏移基础梯度（dp，实际乘 offsetScale 伸缩）：**大胆加大**——即使绝对平静(offsetScale=base)也要
        // 让每层基线明显错开、清晰可辨（相邻 12×0.8=9.6dp，远大于平静时的 floor 波动）。远层坐得高、层层叠成透视水面。
        private val mLayerOffset = floatArrayOf(60f, 48f, 36f, 24f, 12f, 0f)
        private const val OFFSET_BASE_SCALE = 0.8f      // 绝对平静时的偏移比例（相邻层基线差 9.6dp，清晰可分）
        private const val OFFSET_DRIVE_SCALE = 0.35f     // 随声音再略展开（层间偏移以 base 为主、较稳定）
        // 波峰净空深度阶梯（× 该层到护栏的净空）：**近层极值高、远层极值低**。近层(前景)满 1.0 → 偶尔能冲很高；
        // 远层压到 0.5 → 极值受限、冲不高、只作细密低浪，前景恒不会被远层盖过。
        private val mLayerCeilFrac = floatArrayOf(0.5f, 0.6f, 0.7f, 0.82f, 0.92f, 1.0f)
        // 各层基础驱动响应时间常数（秒）：前景小=灵敏先涨快落，后层大=迟缓滞后 → 各层涨落节奏不重复
        private val mLayerDriveTau = floatArrayOf(0.42f, 0.36f, 0.30f, 0.24f, 0.18f, 0.13f)
        // 基础波场每层振幅倍率：**远层大、近层小**。近层(前景)平均振幅低(0.48)→大部分时间平静、不挡后层；
        // 远层高(1.3)→持续起伏、有存在感。近层的高度靠事件大浪(极值)偶尔体现，而非基础波场。
        private val mLayerAmp = floatArrayOf(1.3f, 1.15f, 1.0f, 0.82f, 0.64f, 0.48f)
        // 事件浪包每层振幅倍率：**近层大、远层小**。近层前景偶尔一记大浪(1.25)、远层只有细密小浪(0.5)。
        private val mLayerPacketAmp = floatArrayOf(0.5f, 0.62f, 0.74f, 0.88f, 1.05f, 1.25f)
        // 基础波场波峰密度随层缩放：远层 ×1.5（波峰多、细密），近层 ×0.72（大而疏）
        private const val CYCLES_FAR_SCALE = 1.5f
        private const val CYCLES_NEAR_SCALE = 0.72f
        // 事件浪包落层权重：弱/持续声用 BACK（偏远层→远层浪频繁），强击按 strength 插值到 FRONT（偏近层→近层偶尔大浪）
        private val LAYER_SPAWN_W_BACK = floatArrayOf(2.2f, 1.8f, 1.5f, 1.3f, 1.1f, 1.0f)
        private val LAYER_SPAWN_W_FRONT = floatArrayOf(0.55f, 0.7f, 1.0f, 1.4f, 1.9f, 2.4f)
    }
}
