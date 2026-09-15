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
                isGesture && active != null && (active.preparedSamples < gestureGoal ||
                    (gesture?.active == true && active.preparedSamples < active.reverseSamples))
            }
            else -> true
        }
    }

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
                        active.prepareForeground(pixels)
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
        if (isGesture) {
            active.beginReverse(ParticleReversePlan.MAX_SAMPLES, precomputeProbe = false)
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
        active.beginReverse(samples, appearanceStart, precomputeProbe = true)
    }

    private fun precomputeStep(): Boolean {
        val active = checkNotNull(renderer)
        if (isGesture) {
            // 3.1：按下准备完成、GPU 空闲时先备前几档，识别左滑之后的首个非零画面才可能
            // 落在一个节拍里。手指已经在拖、或者别的动画正在播时不抢它的 GPU。
            if (gesture?.active == true || ParticleGpuWork.playbackCount > 1 ||
                active.preparedSamples >= GESTURE_PREROLL) {
                gestureGoal = active.preparedSamples
                stage = Stage.PLAY
                return true
            }
            if (!active.stepReverse(::cancelled)) { cancel(); return false }
            return true
        }
        if (active.reverseIncomplete) {
            if (!active.stepReverse(::cancelled)) { cancel(); return false }
            return true
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
                // 手指停住：沿用第一批口径，每拍多备两档。判据必须看**手指**，
                // 追赶期内画面还在动，但手指可能早就停了。
                if (position == previousWanted) {
                    gestureGoal = min(active.preparedSamples + GESTURE_IDLE_LOOKAHEAD, samples)
                }
            }
        }
        if (active.preparedSamples >= gestureGoal) return false
        if (!active.stepReverse(::cancelled)) { cancel(); return false }
        return true
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
            if (gesture.active) {
                val speed = if (previousWanted.isNaN()) 0f else abs(wanted - previousWanted)
                gestureGoal = gestureGoal(gestureGoal, need, gestureLookahead(speed), samples)
            }
            previousWanted = wanted
            val drawn = if (ParticlePerfTuning.gestureCreepToFinger) position else wanted
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
        if (!BuildConfig.DEBUG) return
        val now = System.nanoTime()
        submittedTimings += longArrayOf(target, now, lastProgress.toRawBits().toLong())
        pendingDraw?.let { drawTimings += longArrayOf(it[0], it[1], it[2], it[2], now) }
        pendingDraw = null
        if (startupTimings.isEmpty()) startupTimings = longArrayOf(
            prepareStartedAt, pipelineReadyAt, inputReadyAt, uploadsReadyAt, now)
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

    /** 启动分段：准备起、管线就绪、输入就绪、上传就绪、首帧提交。 */
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

        /** 按下阶段先备的档数：触发左滑识别时的位移通常落在这个范围内。 */
        const val GESTURE_PREROLL = 6

        /** 手指停住时每拍多备的档数（沿用第一批口径）。 */
        const val GESTURE_IDLE_LOOKAHEAD = 2

        /**
         * 3.1：按当前速度（档/拍）决定在动时预读几档。慢拖 1 档够用；快拖一拍跨过一档以上，
         * 多备一档才能让下一拍的画面也已经在手上。
         */
        internal fun gestureLookahead(samplesPerBeat: Float): Int = if (samplesPerBeat > 1f) 2 else 1

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
