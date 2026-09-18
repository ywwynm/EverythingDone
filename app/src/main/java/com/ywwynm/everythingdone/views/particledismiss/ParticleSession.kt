package com.ywwynm.everythingdone.views.particledismiss

import android.content.res.AssetManager
import android.util.Log
import com.ywwynm.everythingdone.BuildConfig
import java.util.Collections
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 一次粒子动画在常驻 GL 线程上的状态机。
 *
 * 第二批之前每个动画层自带一条线程，播放是阻塞循环（`playFrames` / `prepareReverse` /
 * `playGesture`）。一窗一 Surface 之后所有会话共用一条线程、一个上下文、一个 Surface，
 * 阻塞循环必须拆成「每次推进一步」，才可能在同一拍里依次处理各会话、最后只交换一次。
 *
 * 除 [cancel]、[setVisible]、[release] 外，所有方法都只在 GL 线程调用。
 */
internal class ParticleSession(
    private val assets: AssetManager,
    private val density: Float,
    val spec: ParticleDismissSpec,
    private val preparation: ParticleMicroflakePreparation?,
    private val touchFeedback: ParticleTouchFeedback?,
    val gesture: ParticleGestureProgress?,
    private val refreshRate: Float,
    private val listener: Listener,
    /**
     * 第四批 ②：首页空闲时提前算好的轨迹。非空时本会话不建材、不预计算，只把**真实快照**
     * 换成前景贴图就开播；GL 线程上再核对一次共享资源与快照尺寸，对不上就丢掉它走原路径。
     */
    private val prepared: ParticlePreparedAppearance? = null
) {

    /** 回调都在 GL 线程触发，实现方自己切主线程。 */
    interface Listener {
        /** 第一帧确认上屏：撤真实弹窗、撤快照、启动暗层都挂在这一刻。 */
        fun onFirstFramePresented()
        /** 每一帧确认上屏：目标呈现时刻 + 该帧进度，用于暗层跟随与手势完成判定。 */
        fun onPresented(target: Long, progress: Float)
        /** 正常播完（true）或失败/中止（false）。 */
        fun onFinished(completed: Boolean)
    }

    private enum class Stage { PIPELINE, FOREGROUND, MATERIAL, FEEDBACK, PRECOMPUTE, PLAY, HOLD, DONE }

    @Volatile var cancelled = false
        private set

    /** 左滑在识别之前不显示粒子；手势收尾时先恢复真实卡片再撤本层。 */
    @Volatile var visible = true
        private set

    /** 可见性刚变过，窗口需要重画一次才能反映。 */
    @Volatile var dirty = false

    /** 快照像素已全部进入 GPU 与材料模型，主线程可以回收它。 */
    @Volatile var snapshotConsumed = false
        private set

    private var stage = Stage.PIPELINE
    private var renderer: ParticleMicroflakeRenderer? = null
    private var playbackRegistered = false
    private var firstTarget = 0L
    private var lastProgress = 0f

    /** 上一拍真正画出去的相位（追赶期内比手指落后）。 */
    private var previousPosition = Float.NaN

    /** 上一拍手指要的相位；速度与「手指停住」都按它判定。 */
    private var previousWanted = Float.NaN
    private var gestureGoal = 0
    private var lastCreepAt = 0L
    private var firstFrameReported = false
    private var finishedReported = false
    private var pendingFrames = 0

    private val isReverse get() = spec.reverse
    private val isGesture get() = gesture != null

    /** 已经开始占用 GPU 资源；共享资源尺寸只在没有会话开工时才允许变。 */
    val started get() = stage != Stage.PIPELINE

    /** 正在逐帧出画（含末帧驻留）：这一条决定预算要不要给出帧让路。 */
    val presenting get() = !cancelled && visible && (stage == Stage.PLAY || stage == Stage.HOLD)

    /** 逻辑时长（秒），已含系统时长缩放。 */
    private val durationSeconds =
        (if (spec.reverse) spec.playbackDurationS else ParticleMicroflakeModel.DURATION) *
            spec.durationScale.coerceAtLeast(.1f)

    /** 实际播放时长（秒）：跳过倒放尾段时按保留的轨迹比例缩短，每帧速度不变。 */
    private var playbackSeconds = durationSeconds.toDouble()

    /** 倒放起点采样；-1 表示还没决定（准备阶段），出帧时一定已经赋值。 */
    private var appearanceStart = -1

    /** 本次是不是用上了提前算好的轨迹。 */
    @Volatile internal var prewarmAdopted = false
        private set

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        dirty = true
        ParticleGl.wake()
    }

    /**
     * 本会话是否需要节拍**继续跑**。
     *
     * 这一条与 [wantsFrame] 必须分开：手势停在原地的那一拍不出帧，但节拍不能因此停下——
     * 停了之后每次重启都要重新登记 Choreographer、等下一次 vsync，手指再动时就会晚一拍
     * （2026-09-15 实测：三星慢拖整段掉到 30 fps，快拖在补档结束后节拍再没起来过，
     * 一次左滑只提交 2–3 帧）。
     */
    fun needsBeat(): Boolean {
        if (cancelled || !visible) return false
        if (stage == Stage.PLAY) return true
        // 3b：预计算与等触摸反馈这两段都可能走几百毫秒，节拍停在这里意味着它们结束时还要
        // 重新登记 Choreographer、再等一次 vsync，首帧白等一拍（实测 OPPO 25、三星 31 ms）。
        // 让节拍先跑起来，`composite` 在没有内容时会立刻返回。
        return ParticlePerfTuning.beatDuringPreparation &&
            (stage == Stage.PRECOMPUTE || stage == Stage.FEEDBACK)
    }

    /** 本拍是否需要窗口出一帧。 */
    fun wantsFrame(): Boolean {
        if (cancelled) return false
        if (dirty) return true
        if (!visible) return false
        if (stage != Stage.PLAY) return false
        if (!isGesture) return true
        val active = renderer ?: return false
        // 必须用「这一拍真的能画出来的相位」判定：追赶期内手指停着不动，画面仍要每拍往前挪，
        // 用手指位置判定会让追赶整段冻住。
        return !firstFrameReported || presentablePosition(active) != previousPosition
    }

    /**
     * 取证：出拍了而这一层没画，记下卡在哪一条判定上。
     *
     * 「左滑只有 38 fps」有两种完全不同的成因——**我们没提交**与**提交了没上屏**，
     * 既有产物里只有提交成功的那些帧，分不开。这一条补上「没提交的那些拍」。
     */
    fun traceSkip(target: Long) {
        val reason = when {
            cancelled -> ParticleBeatTrace.SKIP_CANCELLED
            !visible -> ParticleBeatTrace.SKIP_INVISIBLE
            stage != Stage.PLAY -> ParticleBeatTrace.SKIP_STAGE
            !isGesture -> ParticleBeatTrace.SKIP_STAGE
            renderer == null -> ParticleBeatTrace.SKIP_NO_RENDERER
            else -> ParticleBeatTrace.SKIP_SAME_POSITION
        }
        val active = renderer
        val wanted = if (active != null && isGesture)
            checkNotNull(gesture).framePosition(active.reverseSamples) else Float.NaN
        val position = if (active != null && isGesture) presentablePosition(active) else Float.NaN
        ParticleBeatTrace.record(ParticleBeatTrace.SKIP, target, stage.ordinal.toLong(), reason,
            wanted.toRawBits().toLong(), position.toRawBits().toLong(),
            previousPosition.toRawBits().toLong(), (active?.preparedSamples ?: -1).toLong())
    }

    /**
     * 这一拍真正能画的手势相位。
     *
     * 3.1：手指要的相位可能还没备好档。**绝不在这里阻塞补档**——退而画已经备好的最高一档，
     * 画面比手指落后一点，随后每拍靠预读追上（历史只增不减，所以追上之后就完全跟手）。
     * 备好的档位是整数，取整数相位时 `drawGestureFrame` 不需要 frame+1，一定安全。
     */
    private fun presentablePosition(active: ParticleMicroflakeRenderer): Float =
        presentedPosition(checkNotNull(gesture).framePosition(active.reverseSamples),
            active.preparedSamples, ParticlePerfTuning.gestureCreepToFinger)

    /** 会话还需要 pump 推进（准备、预计算、手势预读）。 */
    fun needsWork(): Boolean {
        if (cancelled) return false
        return when (stage) {
            Stage.DONE, Stage.HOLD -> false
            // 手势在播的时候要按需补档；按下阶段预备够了又还没识别左滑时不必空转 pump。
            Stage.PLAY -> {
                val active = renderer
                isGesture && active != null && (active.submittedSamples < gestureGoal ||
                    !active.reverseSettled ||
                    (gesture?.active == true && active.submittedSamples < active.reverseSamples))
            }
            else -> true
        }
    }

    /**
     * 首帧可以不等下一次节拍：本会话刚进入出帧态、一帧都还没提交过。
     * 粒子层是独立 SurfaceView，它的提交不经过宿主窗口的 traversal，最多省一整拍（研究第 10 节）。
     */
    fun wantsFirstFrame(): Boolean = !firstFrameSubmitted && wantsFrame()

    private var firstFrameSubmitted = false

    /** 已提交但还没确认上屏的帧数。 */
    val awaitingPresentation get() = pendingFrames > 0

    fun cancel() {
        if (cancelled) return
        cancelled = true
        ParticleGl.wake()
    }

    /** 宿主确认真实内容上屏后调用：退出并让窗口把这一层抹掉。 */
    fun release() = cancel()

    // ---- 准备与预计算：每次推进一步，返回是否有进展 ----

    fun advanceOnce(shared: ParticleMicroflakeRenderer.Shared): Boolean {
        if (cancelled) return false
        return try {
            when (stage) {
                Stage.PIPELINE -> {
                    ParticleGpuWork.beginPlayback(); playbackRegistered = true
                    prepareStartedAt = System.nanoTime()
                    if (prepared == null || !adopt(prepared, shared)) {
                        val created = ParticleMicroflakeRenderer(assets, shared)
                        renderer = created
                        created.preparePipeline(ParticleMicroflakeRenderer.sharedResources(assets))
                        created.prepareReversePipeline()
                    }
                    pipelineReadyAt = System.nanoTime()
                    stage = Stage.FOREGROUND
                    true
                }
                Stage.FOREGROUND -> {
                    if (preparation == null) { stage = Stage.MATERIAL; return true }
                    val pixels = preparation.pollPixels() ?: return false
                    val active = checkNotNull(renderer)
                    if (prewarmAdopted) {
                        // 轨迹是用预测快照算的，贴图换成真实快照：末帧仍是真实页面的逐像素拷贝
                        // （`material.vert` 第 0 遍的采样位置与屏幕位置是同一个恒等映射）。
                        active.replaceForeground(pixels)
                        inputReadyAt = System.nanoTime()
                        uploadsReadyAt = inputReadyAt
                        precomputeDoneAt = inputReadyAt
                        snapshotConsumed = true
                        stage = Stage.PLAY
                    } else {
                        val uploadStarted = System.nanoTime()
                        active.prepareForeground(pixels)
                        // L5：前景贴图这一次同步上传的墙钟。创建页是整屏 RGBA8，
                        // 平板上一张 2880×1920 就是 22 MB，值不值得换 PBO 由这个数决定。
                        foregroundUploadNanos = System.nanoTime() - uploadStarted
                        stage = Stage.MATERIAL
                    }
                    true
                }
                Stage.MATERIAL -> {
                    val input = if (preparation == null)
                        ParticleMicroflakeRenderer.fromSpec(assets, shared.width, shared.height, density, spec)
                    else preparation.poll(shared.width, shared.height) ?: return false
                    inputReadyAt = System.nanoTime()
                    checkNotNull(renderer).prepare(input)
                    particleCount = checkNotNull(renderer).particleCount
                    uploadsReadyAt = System.nanoTime()
                    snapshotConsumed = true
                    stage = if (isReverse || isGesture) Stage.PRECOMPUTE else Stage.FEEDBACK
                    if (stage == Stage.PRECOMPUTE) beginPrecompute()
                    true
                }
                Stage.FEEDBACK -> {
                    // 消散要等触摸反馈播完再开始，否则快照换上去时涟漪被拦腰截断。
                    if (touchFeedback != null && !touchFeedback.isSettled) return false
                    stage = Stage.PLAY
                    true
                }
                Stage.PRECOMPUTE -> precomputeStep()
                Stage.PLAY -> if (isGesture) gesturePreread() else false
                Stage.HOLD, Stage.DONE -> false
            }
        } catch (error: Throwable) {
            fail(error)
            false
        }
    }

    /**
     * 收养提前算好的轨迹（GL 线程）。任何一项对不上就原样退回：丢掉那份预算、让后台补建材，
     * 本次照常走完整路径——绝不因为「预算不可用」而让动画失败。
     */
    private fun adopt(ready: ParticlePreparedAppearance, shared: ParticleMicroflakeRenderer.Shared): Boolean {
        val reason = when {
            shared.width != ready.sharedWidth || shared.height != ready.sharedHeight ->
                "共享资源 ${shared.width}×${shared.height} ≠ 预算时 ${ready.sharedWidth}×${ready.sharedHeight}"
            spec.snapshot.width != ready.snapshotWidth || spec.snapshot.height != ready.snapshotHeight ->
                "快照 ${spec.snapshot.width}×${spec.snapshot.height} ≠ 预算时 ${ready.snapshotWidth}×${ready.snapshotHeight}"
            else -> null
        }
        if (reason != null) {
            ParticleCreationPrewarm.rejected(ready, reason)
            preparation?.requireModel()
            return false
        }
        renderer = ready.renderer
        appearanceSamples = ready.samples
        appearanceStart = ready.startFrame
        playbackSeconds = ready.playbackSeconds
        aliveCounts = ready.aliveCounts
        particleCount = ready.particleCount
        prewarmAdopted = true
        return true
    }

    private fun beginPrecompute() {
        val active = checkNotNull(renderer)
        val async = ParticlePerfTuning.asyncPrecompute
        // 历史缓冲的建立与手势第 0 档都留在出帧上下文里：第 0 档与首帧绘制同流，次序由 GL 保证。
        ParticleGl.useForeground()
        if (isGesture) {
            active.beginReverse(ParticleReversePlan.MAX_SAMPLES, precomputeProbe = false, async = async)
            active.seedGesture()
            return
        }
        val samples = ParticleReversePlan.samplesFor(durationSeconds, refreshRate)
        appearanceSamples = samples
        // 2c：倒放的最初几帧几乎没有粒子（末段绝大多数片已经死亡）。按材料的出生与寿命
        // 在 CPU 上算出每个采样的存活片数，从「仍有 5% 在出现」的最晚采样开始播；
        // 保留段内每一帧的时间值仍按 samples 归一，因此与不跳过时逐位相同。
        // 第四批：开关已在 appearContent 那一层解析完（创建页读
        // creationAppearanceSkipEmptyTail，弹窗/面板/新卡片读 dialogAppearanceSkipEmptyTail），
        // 这里只看本次 spec 的结论。
        val skip = spec.skipEmptyTail
        val counts = if (skip || BuildConfig.DEBUG)
            ParticleReversePlan.aliveCounts(active.materialValues, samples) else null
        if (BuildConfig.DEBUG) aliveCounts = counts
        appearanceStart = if (skip && counts != null)
            ParticleReversePlan.startFrameOf(counts, active.materialValues.size / 12,
                ParticlePerfTuning.appearanceAliveRatio) else samples
        // 每帧速度不变，只少播（也少算）尾段那几帧。
        playbackSeconds = durationSeconds.toDouble() * appearanceStart / samples
        active.beginReverse(samples, appearanceStart, precomputeProbe = true, async = async)
    }

    /**
     * 预计算 / 预读一步。**整段都跑在后台（低优先级）上下文上**：出帧那一条命令流因此
     * 不再排在几十次 compute 之后（F14）。后台不可用时原样留在出帧上下文里，行为与改动前一致。
     */
    private fun stepBackground(active: ParticleMicroflakeRenderer): Int {
        ParticleGl.useBackground()
        return active.advanceReverse(::cancelled)
    }

    private fun precomputeStep(): Boolean {
        val active = checkNotNull(renderer)
        if (isGesture) {
            // 3.1：按下准备完成、GPU 空闲时先备前几档，识别左滑之后的首个非零画面才可能
            // 落在一个节拍里。手指已经在拖、或者别的动画正在播时不抢它的 GPU。
            if (gesture?.active == true || ParticleGpuWork.playbackCount > 1 ||
                active.submittedSamples >= GESTURE_PREROLL) {
                gestureGoal = max(active.submittedSamples, 0)
                stage = Stage.PLAY
                return true
            }
            return when (stepBackground(active)) {
                -1 -> { cancel(); false }
                0 -> false
                else -> true
            }
        }
        if (active.reverseIncomplete || !active.reverseSettled) {
            return when (stepBackground(active)) {
                -1 -> { cancel(); false }
                0 -> false
                else -> true
            }
        }
        active.endReverse()
        precomputeDoneAt = System.nanoTime()
        stage = Stage.PLAY
        return true
    }

    /** 手势的按需预读；不占用节拍，跟着 pump 走，但创进度按一拍一次计。 */
    private fun gesturePreread(): Boolean {
        val active = checkNotNull(renderer)
        val samples = active.reverseSamples
        if (checkNotNull(gesture).active) {
            val now = System.nanoTime()
            if (now - lastCreepAt >= ParticleClock.INTERVAL_NANOS) {
                lastCreepAt = now
                val position = gesture.framePosition(samples)
                // 手指停住：用这段空闲往前多备几档。判据必须看**手指**，追赶期内画面还在动，
                // 但手指可能早就停了。第二批把它从「每拍 +2」提到 [GESTURE_IDLE_LOOKAHEAD]：
                // 预读已经不阻塞 CPU、又跑在低优先级上下文里，这段空闲是真的空闲。
                if (position == previousWanted) {
                    gestureGoal = min(active.submittedSamples + GESTURE_IDLE_LOOKAHEAD, samples)
                }
            }
        }
        if (active.submittedSamples >= gestureGoal) {
            // 目标已经备到，但在途的那几档还要靠轮询转成「可以画」，不能就此停手。
            return if (active.reverseSettled) false else active.pollReverse()
        }
        return when (stepBackground(active)) {
            -1 -> { cancel(); false }
            0 -> false
            else -> true
        }
    }

    // ---- 出帧 ----

    /**
     * 把本会话这一拍的画面叠加到已经清空的默认帧缓冲上。
     * 返回 false 表示本会话这一拍没有内容（准备中或已结束）。
     */
    fun drawInto(target: Long): Boolean {
        dirty = false
        if (cancelled || !visible) return false
        val drawStart = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        return try {
            val drew = when (stage) {
                Stage.PLAY -> { drawPlayFrame(target); !cancelled }
                Stage.HOLD -> { drawHoldFrame(); true }
                else -> false
            }
            if (drew && BuildConfig.DEBUG) pendingDraw = longArrayOf(target, drawStart, System.nanoTime())
            drew
        } catch (error: Throwable) {
            fail(error)
            false
        }
    }

    private fun drawPlayFrame(target: Long) {
        val active = checkNotNull(renderer)
        if (firstTarget == 0L) firstTarget = target
        if (isGesture) {
            val samples = active.reverseSamples
            val wanted = checkNotNull(gesture).framePosition(samples)
            val position = presentablePosition(active)
            // 目标档位仍按**手指**要的相位算，追赶才有方向；但补档只在 pump 里做（每拍最多
            // 拖住一档），不在这里阻塞出帧。关掉 3.1 时退回老实现的同步补档。
            val need = min(ceil(wanted).toInt(), samples)
            if (!ParticlePerfTuning.gestureCreepToFinger) {
                while (active.preparedSamples < need) {
                    if (!active.stepReverse(::cancelled)) { cancel(); return }
                }
            }
            // 目标档位按**手指**要的相位算，追赶才有方向；反向拖回来时也不回退（历史只增不减，
            // 多备只影响什么时候算，不影响画出来的任何一帧）。
            if (gesture.active) {
                val speed = if (previousWanted.isNaN()) 0f else abs(wanted - previousWanted)
                gestureGoal = gestureGoal(gestureGoal, need, gestureLookahead(speed), samples)
            }
            previousWanted = wanted
            val drawn = if (ParticlePerfTuning.gestureCreepToFinger) position else wanted
            if (BuildConfig.DEBUG) {
                gestureTimings += longArrayOf(target,
                    wanted.toRawBits().toLong(), drawn.toRawBits().toLong(),
                    active.preparedSamples.toLong(), active.submittedSamples.toLong(),
                    gestureGoal.toLong())
                // 不变式：出帧侧只许画到已经确认写完的档位。异步预读一旦破坏它，画面上是
                // 一帧噪声而不是崩溃，很难在录屏里认出来，所以这里显式盯着。
                if (ParticlePerfTuning.gestureCreepToFinger &&
                    ceil(drawn).toInt() > max(active.preparedSamples, 0)) {
                    Log.w(ParticleMicroflakeRenderer.TAG,
                        "手势画到了还没备好的档位 drawn=$drawn prepared=${active.preparedSamples}")
                }
            }
            active.drawGestureFrame(drawn, active.gestureStateBuffer(), clearTarget = false)
            // 提交的进度必须是**实际呈现的相位**：ItemTouchHelper 的松手恢复与快速完成都从
            // presentedProgress 接着走，记成手指位置会让画面跳一下。
            lastProgress = if (samples > 0) drawn / samples else 0f
            gesture.submitted(target, lastProgress)
            previousPosition = drawn
            return
        }
        val progress = ((target - firstTarget) / 1e9 / playbackSeconds).toFloat().coerceIn(0f, 1f)
        if (isReverse) active.drawReverseFrame(active.reverseFrameFor(progress, appearanceStart), clearTarget = false)
        else active.draw(progress, batchPressure = true, clearTarget = false)
        lastProgress = progress
        if (progress >= 1f) stage = Stage.HOLD
    }

    /**
     * 末帧驻留：出现动画要一直显示末帧（与真实面板逐像素相同）直到宿主确认面板上屏；
     * 手势在停住时被别的会话带着重画。窗口每拍会清空整个 Surface，所以这一层必须跟着画。
     */
    private fun drawHoldFrame() {
        val active = renderer ?: return
        if (isGesture) {
            active.drawGestureFrame(presentablePosition(active), active.gestureStateBuffer(), clearTarget = false)
            return
        }
        if (isReverse) active.drawReverseFrame(active.reverseFrameFor(lastProgress, appearanceStart), clearTarget = false)
        else active.draw(lastProgress, batchPressure = true, clearTarget = false)
    }

    /** 窗口 `eglSwapBuffers` 返回之后调用；口径与第一批的「请求→首个粒子提交」一致。 */
    fun frameSubmitted(target: Long) {
        pendingFrames++
        firstFrameSubmitted = true
        if (!BuildConfig.DEBUG) return
        val now = System.nanoTime()
        submittedTimings += longArrayOf(target, now, lastProgress.toRawBits().toLong())
        pendingDraw?.let { drawTimings += longArrayOf(it[0], it[1], it[2], it[2], now) }
        pendingDraw = null
        // 前五位沿用既有形状（既有脚本按下标 0..4 读）；L5 在**末尾追加**前景上传的墙钟。
        if (startupTimings.isEmpty()) startupTimings = longArrayOf(
            prepareStartedAt, pipelineReadyAt, inputReadyAt, uploadsReadyAt, now,
            foregroundUploadNanos)
    }

    /** 该帧确认上屏；[displayedAt] 是驱动报的真实呈现时刻，拿不到时为 null。 */
    fun framePresented(target: Long, displayedAt: Long? = null) {
        if (pendingFrames > 0) pendingFrames--
        // 前三位沿用既有形状（既有脚本按下标 0/1/2 读）；3b 在**末尾追加**真实呈现时刻
        // （拿不到写 0），用来把「画面真的晚上屏」与「我们晚知道」分开。
        if (BuildConfig.DEBUG) presentedTimings += longArrayOf(
            target, System.nanoTime(), System.nanoTime(), displayedAt ?: 0L)
        if (cancelled) return
        val progress = if (isGesture) checkNotNull(gesture).progressAt(target) else lastProgress
        if (!firstFrameReported) {
            firstFrameReported = true
            gesture?.presented = true
            listener.onFirstFramePresented()
            if (BuildConfig.DEBUG) Log.i(ParticleMicroflakeRenderer.TAG,
                "首帧上屏 requestToVisibleMs=${(System.nanoTime() - spec.requestedAtNanos) / 1e6}")
        }
        listener.onPresented(target, progress)
        if (stage != Stage.HOLD || finishedReported) return
        // 消散播完、末帧（全透明）也确认上屏之后收尾；出现的末帧要一直留到宿主确认
        // 真实面板上屏（release），所以只报完成、不退出。
        if (isReverse) finish(true)
        else if (pendingFrames == 0) finish(true)
    }

    /**
     * 播完一次的诊断日志。格式沿用第一批 `playFrames` 里的那两行——
     * `verify_dialog_coverage.py` 就是按「出现完成 particles=」「完成 count=」两个前缀
     * 判定 GPU 是否真的把动画播完的，换了格式会让整套覆盖回归失去判据。
     */
    private fun logCompletion() {
        if (!BuildConfig.DEBUG) return
        val active = renderer ?: return
        val stamps = synchronized(submittedTimings) { submittedTimings.map { it[0] } }
        if (stamps.size < 2) return
        val gaps = stamps.zipWithNext { a, b -> (b - a) / 1e6 }.sorted()
        val label = if (isReverse) "出现完成 particles=" else "完成 count="
        Log.i(ParticleMicroflakeRenderer.TAG,
            "$label${active.particleCount} frames=${stamps.size} " +
                "elapsedMs=${(stamps.last() - stamps.first()) / 1e6} " +
                "p90Ms=${gaps[(gaps.size * .9).toInt().coerceAtMost(gaps.lastIndex)]} " +
                "maxMs=${gaps.last()} direction=${active.direction} seed=${active.variationSeed}")
    }

    private fun finish(completed: Boolean) {
        if (finishedReported) return
        finishedReported = true
        if (completed && !isGesture) logCompletion()
        listener.onFinished(completed)
        // 消散播完就可以退场；出现与手势由宿主决定什么时候撤。
        if (!completed || (!isReverse && !isGesture)) cancel()
    }

    private fun fail(error: Throwable) {
        Log.e(ParticleMicroflakeRenderer.TAG, "粒子动画回退", error)
        stage = Stage.DONE
        cancelled = true
        finish(false)
    }

    /** 释放本会话的 GPU 对象；共享资源不动。只在 GL 线程调用。 */
    fun dispose() {
        stage = Stage.DONE
        cancelled = true
        runCatching { renderer?.close() }
        renderer = null
        snapshotConsumed = true
        if (playbackRegistered) { playbackRegistered = false; ParticleGpuWork.endPlayback() }
        if (!finishedReported) {
            finishedReported = true
            listener.onFinished(false)
        }
    }

    // ---- debug 观察器读取的时间线（release 包里恒为空） ----

    /** 每帧提交：目标呈现时刻、提交墙钟、进度位。 */
    internal val submittedTimings: MutableList<LongArray> = Collections.synchronizedList(ArrayList())

    /** 每帧上屏：目标呈现时刻、确认墙钟、处理墙钟。 */
    internal val presentedTimings: MutableList<LongArray> = Collections.synchronizedList(ArrayList())

    /** 每帧绘制：目标呈现时刻、绘制起、绘制止。 */
    internal val drawTimings: MutableList<LongArray> = Collections.synchronizedList(ArrayList())

    /**
     * 手势每帧的滞后：目标呈现时刻、手指要的相位、实际画出的相位、可画档位、已提交档位、目标档位。
     * 「不跟手」在这份表里就是 `wanted − drawn`：反向瞬间的峰值与恢复用了几拍全在这里。
     */
    internal val gestureTimings: MutableList<LongArray> = Collections.synchronizedList(ArrayList())

    /** L5：前景贴图那一次 `glTexImage2D` 的墙钟（纳秒）。 */
    @Volatile private var foregroundUploadNanos = 0L

    /** 启动分段：准备起、管线就绪、输入就绪、上传就绪、首帧提交、前景上传耗时。 */
    @Volatile internal var startupTimings = longArrayOf()
        private set

    /** 预计算结束的墙钟（出现动画才有）；0 表示还没结束或不是出现路径。 */
    @Volatile internal var precomputeDoneAt = 0L
        private set

    /** 每个历史采样上「正在以颗粒形态出现」的片数；观察器据此判定首个真正可见的帧。 */
    @Volatile internal var aliveCounts: IntArray? = null
        private set

    /** 本次倒放的起点采样与总采样数（2c 的实际效果）。 */
    internal val appearanceStartFrame get() = appearanceStart
    internal val appearancePlaybackMs get() = playbackSeconds * 1000

    /** 观察器在动画层释放之后才读，那时 renderer 已经销毁，所以这两项要当场记下来。 */
    @Volatile internal var appearanceSamples = 0
        private set

    /** 本次动画的片数；与 [aliveCounts] 一起给出每一帧的可见占比。 */
    @Volatile internal var particleCount = 0
        private set

    internal val gestureActivatedAtNanos get() = gesture?.activatedAtNanos ?: 0L

    private var prepareStartedAt = 0L
    private var pipelineReadyAt = 0L
    private var inputReadyAt = 0L
    private var uploadsReadyAt = 0L
    private var pendingDraw: LongArray? = null

    companion object {
        /** 正向消散的逻辑时长（秒），系统时长缩放另算。 */
        const val TOTAL_DURATION = ParticleMicroflakeModel.DURATION

        /**
         * 按下阶段先备的档数。
         *
         * 第二批 6 → 12：识别左滑那一刻的位移只有一个 touch slop（约 0.6 档），真正吃紧的是
         * **甩动**——材料建好时手指常常已经拖过 20%（36 档里的 7 档）。按下到识别至少有
         * 一百多毫秒，这段时间屏幕上什么都不用画，多备 12 档是纯赚；而且预读已经不阻塞 CPU。
         */
        const val GESTURE_PREROLL = 12

        /**
         * 手指停住时每拍多备的档数。
         *
         * 第二批 2 → 6：第一批的「每拍 +2」是为了配合**阻塞式**补档（一档就要 4–7 ms，
         * 备多了会把节拍挤掉）。改成异步提交 + 栅栏轮询、并跑在低优先级上下文之后，
         * 手指停住的那段时间是真的空闲，多备几档不会挤到出帧。
         */
        const val GESTURE_IDLE_LOOKAHEAD = 6

        /**
         * 按当前速度（档/拍）决定在动时预读几档。
         *
         * 第二批改成按速度成比例：慢拖仍是 1–2 档，快拖一拍跨三四档时就要备到那么多，
         * 否则下一拍的画面永远还没算出来——阶段 0' 的快拖只出 9 个不同画面就是这么来的。
         * 上限 [GESTURE_MAX_LOOKAHEAD]：再多也超出一拍能算完的量，只会白占 GPU。
         */
        internal fun gestureLookahead(samplesPerBeat: Float): Int =
            (ceil(samplesPerBeat).toInt() + 1).coerceIn(1, GESTURE_MAX_LOOKAHEAD)

        const val GESTURE_MAX_LOOKAHEAD = 6

        /**
         * 这一拍真正能画的相位：手指要 [wanted]，手上只备到第 [prepared] 档。
         *
         * [creep] 为 true（第三批的默认）时**绝不阻塞补档**，退而画已经备好的最高一档；
         * 取整数相位时 `drawGestureFrame` 的 fraction 为 0，只用到第 [prepared] 档本身，
         * 一定安全。为 false 时返回手指位置，由调用方按老实现同步补齐。
         */
        internal fun presentedPosition(wanted: Float, prepared: Int, creep: Boolean): Float {
            if (!creep) return wanted
            return min(wanted, max(prepared, 0).toFloat())
        }

        /**
         * 目标档位：至少够画当前画面（[need]），再加预读；不超过总档数，也**绝不回退**——
         * 历史是确定性的，多备只影响什么时候算，不影响画出来的任何一帧（G0）。
         */
        internal fun gestureGoal(current: Int, need: Int, lookahead: Int, samples: Int): Int =
            max(current, min(need + lookahead, samples))
    }
}
