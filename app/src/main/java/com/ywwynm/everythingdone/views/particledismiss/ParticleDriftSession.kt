package com.ywwynm.everythingdone.views.particledismiss

import android.content.res.AssetManager
import android.util.Log
import com.ywwynm.everythingdone.BuildConfig

/** 粒子层按 60 Hz 投票出帧：一拍的名义长度（与 [ParticleFrameStats] 同一口径）。 */
private const val BEAT_NANOS = 1_000_000_000L / 60

/**
 * 飘散样式一次粒子动画在常驻 GL 线程上的状态机（与 [ParticleSession] 同一组对外方法）。
 *
 * 飘散的任何时刻都能按粒子索引直接算出来（见 `drift-update.comp`），所以没有建材、预计算与历史：
 * 准备只有「拿到快照像素 → 上传」一步；出现动画按时间从末端倒放，左滑按手指进度直接画、允许回退。
 *
 * 时间：源码的整段动画（设备时刻 [ParticleDriftModel.startSeconds] → [ParticleDriftModel.totalSeconds]，
 * 起点是第一处开始变淡的时刻）按比例压到现有时长
 * （消散 1 秒、出现 [ParticleDismissSpec.playbackDurationS]，都乘系统动画缩放）。
 *
 * 除 [cancel]、[setVisible]、[release] 外，所有方法都只在 GL 线程调用。
 */
internal class ParticleDriftSession(
    private val assets: AssetManager,
    override val spec: ParticleDismissSpec,
    private val model: ParticleDriftModel,
    private val background: ParticleDriftBackground,
    private val preparation: ParticleMicroflakePreparation,
    private val touchFeedback: ParticleTouchFeedback?,
    private val gesture: ParticleGestureProgress?,
    private val listener: ParticleLayerSession.Listener
) : ParticleLayerSession {

    private enum class Stage { PIPELINE, FOREGROUND, FEEDBACK, PLAY, HOLD, DONE }

    @Volatile override var cancelled = false
        private set
    @Volatile override var visible = true
        private set
    @Volatile override var snapshotConsumed = false
        private set
    @Volatile private var dirty = false

    private var stage = Stage.PIPELINE
    private var renderer: ParticleDriftRenderer? = null
    private var content = ParticleDriftContent.Analysis.NONE
    private var playbackRegistered = false
    private var firstTarget = 0L
    private var lastProgress = 0f
    private var previousPosition = Float.NaN
    private var firstFrameReported = false
    private var firstFrameSubmitted = false
    private var finishedReported = false
    private var pendingFrames = 0
    private val submittedProgress = HashMap<Long, Float>()

    // 仅调试包的出帧统计：实际上屏帧数与按 60 Hz 节拍应有的帧数（与设置页读数同一口径）。
    private var presentedCount = 0
    private var firstPresentedAt = 0L
    private var lastPresentedAt = 0L
    private var framesLogged = false

    private val isReverse get() = spec.reverse
    private val isGesture get() = gesture != null

    /** 逻辑时长（秒），已含系统时长缩放。 */
    private val durationSeconds =
        (if (spec.reverse) spec.playbackDurationS else ParticleMicroflakeModel.DURATION) *
            spec.durationScale.coerceAtLeast(.1f)

    override val bodyGoneProgress: Float =
        ((model.bodyGoneSeconds - model.startSeconds) / (model.totalSeconds - model.startSeconds)).coerceIn(0f, 1f)

    override val started get() = stage != Stage.PIPELINE
    override val presenting get() = !cancelled && visible && (stage == Stage.PLAY || stage == Stage.HOLD)
    override val needsMicroflakeShared get() = false

    override fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        dirty = true
        ParticleGl.wake()
    }

    override fun needsBeat(): Boolean {
        if (cancelled || !visible) return false
        if (stage == Stage.PLAY) return true
        return ParticlePerfTuning.beatDuringPreparation && stage == Stage.FEEDBACK
    }

    override fun wantsFrame(): Boolean {
        if (cancelled) return false
        if (dirty) return true
        if (!visible || stage != Stage.PLAY) return false
        if (!isGesture) return true
        return !firstFrameReported || gesturePosition() != previousPosition
    }

    override fun wantsFirstFrame(): Boolean = !firstFrameSubmitted && wantsFrame()

    override fun needsWork(): Boolean = !cancelled &&
        (stage == Stage.PIPELINE || stage == Stage.FOREGROUND || stage == Stage.FEEDBACK)

    override fun traceSkip(target: Long) = Unit

    override fun advanceOnce(shared: ParticleMicroflakeRenderer.Shared?): Boolean {
        if (cancelled) return false
        return try {
            when (stage) {
                Stage.PIPELINE -> {
                    ParticleGpuWork.beginPlayback(); playbackRegistered = true
                    ParticleDriftRenderer.ensurePrograms(assets)
                    stage = Stage.FOREGROUND
                    true
                }
                Stage.FOREGROUND -> {
                    // 合成方式要在开画之前定下来：背景取样还没回来就再等一会儿（有上限，过了按主题）。
                    val additive = background.poll() ?: return false
                    val pixels = preparation.pollPixels() ?: return false
                    content = ParticleDriftContent.analyse(pixels.width, pixels.height, pixels.rgba, model.count)
                    renderer = ParticleDriftRenderer(assets, model, additive).also { it.prepare(pixels, content) }
                    snapshotConsumed = true
                    ParticleFrameStats.reportFlakes(model.count + content.replicas)
                    stage = if (isReverse || isGesture) Stage.PLAY else Stage.FEEDBACK
                    true
                }
                Stage.FEEDBACK -> {
                    // 消散要等触摸反馈播完再开始，否则快照换上去时涟漪被拦腰截断（与流体样式相同）。
                    if (touchFeedback != null && !touchFeedback.isSettled) return false
                    stage = Stage.PLAY
                    true
                }
                Stage.PLAY, Stage.HOLD, Stage.DONE -> false
            }
        } catch (error: Throwable) {
            fail(error)
            false
        }
    }

    /** 手势进度（0–1）；识别左滑之前为 0。 */
    private fun gesturePosition(): Float = checkNotNull(gesture).framePosition(1)

    /** 进度 → 源码设备时刻（从第一处开始变淡的那一刻起算）：消散与手势正着走，出现从末端倒放。 */
    private fun deviceTime(progress: Float): Float = model.startSeconds +
        (if (isReverse) 1f - progress else progress) * (model.totalSeconds - model.startSeconds)

    override fun drawInto(target: Long): Boolean {
        dirty = false
        if (cancelled || !visible) return false
        val active = renderer ?: return false
        return try {
            when (stage) {
                Stage.PLAY -> {
                    if (firstTarget == 0L) firstTarget = target
                    val progress = if (isGesture) {
                        gesturePosition().also {
                            checkNotNull(gesture).submitted(target, it)
                            previousPosition = it
                        }
                    } else ((target - firstTarget) / 1e9 / durationSeconds).toFloat().coerceIn(0f, 1f)
                    active.draw(deviceTime(progress), spec.originXPx, spec.originYPx)
                    lastProgress = progress
                    if (!isGesture && progress >= 1f) stage = Stage.HOLD
                    true
                }
                Stage.HOLD -> {
                    active.draw(deviceTime(lastProgress), spec.originXPx, spec.originYPx)
                    true
                }
                else -> false
            }
        } catch (error: Throwable) {
            fail(error)
            false
        }
    }

    override fun frameSubmitted(target: Long) {
        pendingFrames++
        firstFrameSubmitted = true
        if (!isGesture) submittedProgress[target] = lastProgress
    }

    override fun framePresented(target: Long, displayedAt: Long?) {
        if (pendingFrames > 0) pendingFrames--
        if (cancelled) return
        if (BuildConfig.DEBUG) {
            val at = displayedAt ?: target
            if (presentedCount++ == 0) firstPresentedAt = at
            lastPresentedAt = at
        }
        val progress = if (isGesture) checkNotNull(gesture).progressAt(target)
            else submittedProgress.remove(target) ?: 0f
        if (!firstFrameReported) {
            firstFrameReported = true
            gesture?.presented = true
            listener.onFirstFramePresented()
            if (BuildConfig.DEBUG) Log.i(ParticleDriftRenderer.TAG,
                "飘散首帧上屏 requestToVisibleMs=${(System.nanoTime() - spec.requestedAtNanos) / 1e6} " +
                    "颗粒=${model.count}+${content.replicas} 本体置信度=${"%.2f".format(content.panelWeight)} " +
                    "加光=${renderer?.additive}（${background.source}） 样式=${model.styleName ?: "随机"} " +
                    "起点=${model.startSeconds}s 时长=${model.totalSeconds}s 格点=${model.latticeWidth}×${model.latticeHeight}")
        }
        listener.onPresented(target, progress)
        if (stage != Stage.HOLD || finishedReported) return
        // 消散播完、末帧（全透明）也确认上屏之后收尾；出现的末帧要一直留到宿主确认真实面板上屏（release）。
        if (isReverse) finish(true)
        else if (pendingFrames == 0) finish(true)
    }

    private fun finish(completed: Boolean) {
        if (finishedReported) return
        finishedReported = true
        logFrames()
        listener.onFinished(completed)
        if (!completed || (!isReverse && !isGesture)) cancel()
    }

    private fun fail(error: Throwable) {
        Log.e(ParticleDriftRenderer.TAG, "飘散样式回退", error)
        stage = Stage.DONE
        cancelled = true
        finish(false)
    }

    override fun cancel() {
        if (cancelled) return
        cancelled = true
        ParticleGl.wake()
    }

    override fun release() = cancel()

    /** 仅调试包：一次动画只记一行。左滑跟手的会话不走 [finish]，在 [dispose] 里记。 */
    private fun logFrames() {
        if (!BuildConfig.DEBUG || framesLogged || presentedCount < 2) return
        framesLogged = true
        val span = lastPresentedAt - firstPresentedAt
        val expected = ((span + BEAT_NANOS / 2) / BEAT_NANOS).toInt() + 1
        Log.i(ParticleDriftRenderer.TAG, "飘散出帧 上屏=$presentedCount 应有=$expected " +
            "用时Ms=${span / 1_000_000} 颗粒=${model.count + content.replicas} 样式=${model.styleName ?: "随机"} " +
            (if (isReverse) "出现" else if (isGesture) "跟手" else "消散"))
    }

    override fun dispose() {
        logFrames()
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
}
