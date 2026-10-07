package com.ywwynm.everythingdone.views.particledismiss

import android.content.res.AssetManager
import android.opengl.GLES30
import android.util.Log
import com.ywwynm.everythingdone.BuildConfig
import java.util.concurrent.Future
import kotlin.math.hypot
import kotlin.math.roundToInt

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
    private val playback: ParticleDriftSequence.Playback,
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
    @Volatile private var model = playback.model
    private var trajectoryStarted = false
    private var renderer: ParticleDriftRenderer? = null

    /**
     * 快照纹理一备好就先占着，等内容分析与背景取样；建渲染器时交过去（之前退出则在 [dispose] 里删掉）。
     * 零拷贝时还带着它的 EGLImage（[pendingEglImage]），字节上传时为 0。
     */
    private var pendingImage = 0
    private var pendingEglImage = 0L
    private var pendingAnalysis: Future<ParticleDriftContent.Analysis>? = null

    /** 零拷贝快照（[ParticleDriftHardwareImport]）：只试一次，GPU 取样还没回来时由它占着纹理。 */
    private var hardwareTried = false
    private var hardwareImport: ParticleDriftHardwareImport? = null
    private var zeroCopy = false
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
    private val pacing = ParticleFramePacing("飘散")

    /** 仅调试包：各段完成的时刻（纳秒），第一帧上屏时打进日志，用来定位起播延迟花在哪一段。 */
    private val createdAt = if (BuildConfig.DEBUG) System.nanoTime() else 0L
    private var firstAdvanceAt = 0L
    private var pipelineAt = 0L
    private var backgroundAt = 0L
    private var pixelsAt = 0L
    private var uploadedAt = 0L
    private var analysedAt = 0L
    private var preparedAt = 0L
    private var firstSubmitAt = 0L
    private var holdSubmittedAt = 0L
    private var framesLogged = false

    private val isReverse get() = spec.reverse
    private val isGesture get() = gesture != null

    /** 逻辑时长（秒），已含系统时长缩放。 */
    private val durationSeconds =
        (if (spec.reverse) spec.playbackDurationS else ParticleMicroflakeModel.DURATION) *
            spec.durationScale.coerceAtLeast(.1f)

    override val bodyGoneProgress: Float get() {
        val current = model
        return ((current.bodyGoneSeconds - current.startSeconds) /
            (current.totalSeconds - current.startSeconds)).coerceIn(0f, 1f)
    }

    override val started get() = stage != Stage.PIPELINE
    override val presenting get() = !cancelled && visible && (stage == Stage.PLAY || stage == Stage.HOLD)
    override val needsMicroflakeShared get() = false

    override fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        dirty = true
        ParticleGl.wake()
    }

    /** 飘散每帧是设备时刻的闭式函数，任意时刻都能直接画。 */
    override val prefersHighFrameRate get() = true

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

    override fun wantsLateFrame(): Boolean = isGesture && firstFrameSubmitted && !cancelled && visible &&
        stage == Stage.PLAY && gesturePosition() != previousPosition

    override fun needsWork(): Boolean = !cancelled &&
        (stage == Stage.PIPELINE || stage == Stage.FOREGROUND || stage == Stage.FEEDBACK)

    override fun traceSkip(target: Long) = Unit

    override fun advanceOnce(shared: ParticleMicroflakeRenderer.Shared?): Boolean {
        if (cancelled) return false
        return try {
            when (stage) {
                Stage.PIPELINE -> {
                    if (BuildConfig.DEBUG) firstAdvanceAt = System.nanoTime()
                    ParticleGpuWork.beginPlayback(); playbackRegistered = true
                    ParticleDriftRenderer.ensurePrograms(assets)
                    if (BuildConfig.DEBUG) pipelineAt = System.nanoTime()
                    stage = Stage.FOREGROUND
                    true
                }
                Stage.FOREGROUND -> {
                    // 快照是 HardwareBuffer（新建页，API 31+）就零拷贝：直接绑成纹理，内容分析要读的像素在 GPU 上取。
                    // 否则（或零拷贝任何一步失败）快照字节一到就传成纹理，内容分析交给后台线程。两条路都不必先等背景取样。
                    if (pendingImage == 0) {
                        if (!hardwareTried) {
                            hardwareTried = true
                            hardwareImport = ParticleDriftHardwareImport.begin(spec.snapshot, assets)
                            if (hardwareImport == null) preparation.start()
                        }
                        val hardware = hardwareImport
                        if (hardware != null) {
                            val samples = runCatching { hardware.pollSamples() }.getOrElse { error ->
                                Log.w(ParticleDriftRenderer.TAG, "飘散零拷贝取样失败，退回拷贝上传", error)
                                hardware.release()
                                hardwareImport = null
                                preparation.start()
                                return false
                            } ?: return false
                            if (BuildConfig.DEBUG) {
                                pixelsAt = System.nanoTime()
                                uploadedAt = pixelsAt
                                if (ParticleDriftHardwareImport.verifyForTests) Log.i(ParticleDriftRenderer.TAG,
                                    "飘散零拷贝核对 ${hardware.verify(spec.snapshot, samples)}")
                            }
                            pendingAnalysis = ParticleDriftContent.analyseSamplesAsync(hardware.width, hardware.height,
                                samples, model.count)
                            pendingImage = hardware.texture
                            pendingEglImage = hardware.eglImage
                            hardwareImport = null
                            zeroCopy = true
                        } else {
                            val pixels = preparation.pollPixels() ?: return false
                            if (BuildConfig.DEBUG) pixelsAt = System.nanoTime()
                            pendingAnalysis = ParticleDriftContent.analyseAsync(pixels.width, pixels.height, pixels.rgba,
                                model.count)
                            pendingImage = ParticleDriftRenderer.uploadImage(pixels)
                            if (BuildConfig.DEBUG) uploadedAt = System.nanoTime()
                        }
                    }
                    pendingAnalysis?.let { analysis ->
                        if (!analysis.isDone) return false
                        content = analysis.get()
                        pendingAnalysis = null
                        if (BuildConfig.DEBUG) analysedAt = System.nanoTime()
                    }
                    // 合成方式要在开画之前定下来：背景取样还没回来就再等一会儿（有上限，过了按主题）。
                    val additive = background.poll() ?: return false
                    if (BuildConfig.DEBUG && backgroundAt == 0L) backgroundAt = System.nanoTime()
                    renderer = ParticleDriftRenderer(assets, model, additive).also {
                        it.prepare(content, pendingImage, pendingEglImage)
                    }
                    pendingImage = 0
                    pendingEglImage = 0L
                    if (BuildConfig.DEBUG) preparedAt = System.nanoTime()
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
                    // 按下卡片的预热可以先画静止帧，但不算播放。真正开播再按全局记录确认参考。
                    if (!trajectoryStarted && (gesture == null || gesture.active)) {
                        model = playback.start()
                        active.useModel(model)
                        trajectoryStarted = true
                    }
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
        if (BuildConfig.DEBUG && !firstFrameSubmitted) firstSubmitAt = System.nanoTime()
        if (BuildConfig.DEBUG && stage == Stage.HOLD && holdSubmittedAt == 0L) holdSubmittedAt = System.nanoTime()
        firstFrameSubmitted = true
        if (!isGesture) submittedProgress[target] = lastProgress
    }

    private fun stageMs(from: Long, to: Long) = if (from == 0L || to == 0L) "-" else "%.1f".format((to - from) / 1e6)

    override fun framePresented(target: Long, displayedAt: Long?) {
        if (pendingFrames > 0) pendingFrames--
        if (cancelled) return
        if (BuildConfig.DEBUG) {
            val at = displayedAt ?: target
            if (presentedCount++ == 0) firstPresentedAt = at
            lastPresentedAt = at
            pacing.onPresented(displayedAt)
        }
        val progress = if (isGesture) checkNotNull(gesture).progressAt(target)
            else submittedProgress.remove(target) ?: 0f
        if (!firstFrameReported) {
            firstFrameReported = true
            gesture?.presented = true
            listener.onFirstFramePresented()
            if (BuildConfig.DEBUG) Log.i(ParticleDriftRenderer.TAG,
                "飘散首帧上屏 requestToVisibleMs=${(System.nanoTime() - spec.requestedAtNanos) / 1e6} " +
                    "分段 建会话=${stageMs(spec.requestedAtNanos, createdAt)} 排队=${stageMs(createdAt, firstAdvanceAt)} " +
                    "着色器=${stageMs(firstAdvanceAt, pipelineAt)} " +
                    "背景=${stageMs(pipelineAt, backgroundAt)} " +
                    "像素=${stageMs(pipelineAt, pixelsAt)} 上传=${stageMs(pixelsAt, uploadedAt)} " +
                    "分析完成=${stageMs(pixelsAt, analysedAt)} 建渲染器=${stageMs(analysedAt, preparedAt)} " +
                    "到首帧提交=${stageMs(preparedAt, firstSubmitAt)} 提交到上屏=${stageMs(firstSubmitAt, System.nanoTime())} " +
                    "快照=${spec.snapshot.width}×${spec.snapshot.height} 零拷贝=$zeroCopy " +
                    "颗粒=${model.count}+${content.replicas} 本体置信度=${"%.2f".format(content.panelWeight)} " +
                    "加光=${renderer?.additive}（${background.source}） 样式=${model.styleName ?: "随机"} " +
                    "起点=${model.startSeconds}s 时长=${model.totalSeconds}s 格点=${model.latticeWidth}×${model.latticeHeight} " +
                    "触点距离=${model.touchGap ?: "无"} 风力=${hypot(model.windX, model.windY).roundToInt()} " +
                    "大小=${model.sizeScale}")
        }
        listener.onPresented(target, progress)
        if (stage != Stage.HOLD || finishedReported) return
        // 消散播完、末帧（全透明）也确认上屏之后收尾；出现的末帧要一直留到宿主确认真实面板上屏（release）。
        if (BuildConfig.DEBUG && isReverse) Log.i(ParticleDriftRenderer.TAG,
            "飘散出现收尾 末帧提交到确认Ms=${stageMs(holdSubmittedAt, System.nanoTime())} " +
                "呈现时刻=${if (displayedAt != null) "有" else "无"} 待确认=$pendingFrames")
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
        if (BuildConfig.DEBUG && isGesture) Log.i(ParticleDriftRenderer.TAG, "飘散跟手 ${checkNotNull(gesture).changeSummary()}")
        pacing.log(if (isReverse) "出现" else if (isGesture) "跟手" else "消散", spec.requestedAtNanos,
            model.count + content.replicas)
        if (!BuildConfig.DEBUG || framesLogged || presentedCount < 2) return
        framesLogged = true
        val span = lastPresentedAt - firstPresentedAt
        val beat = ParticleClock.outputPeriodNanos.takeIf { it > 0L } ?: BEAT_NANOS
        val expected = ((span + beat / 2) / beat).toInt() + 1
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
        if (pendingImage != 0) {
            runCatching { GLES30.glDeleteTextures(1, intArrayOf(pendingImage), 0) }
            pendingImage = 0
        }
        if (pendingEglImage != 0L) {
            runCatching { ParticleHardwareTexture.destroy(ParticleGl.display.nativeHandle, pendingEglImage) }
            pendingEglImage = 0L
        }
        hardwareImport?.let { runCatching { it.release() } }
        hardwareImport = null
        pendingAnalysis = null
        snapshotConsumed = true
        if (playbackRegistered) { playbackRegistered = false; ParticleGpuWork.endPlayback() }
        if (!finishedReported) {
            finishedReported = true
            listener.onFinished(false)
        }
    }
}
