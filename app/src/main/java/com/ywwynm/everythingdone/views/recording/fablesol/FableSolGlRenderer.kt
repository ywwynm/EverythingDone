package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.graphics.Color
import android.opengl.GLES30
import android.os.SystemClock
import android.util.TypedValue
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Stage 1 连续水面 GLES 渲染器。Simulation、采样与 GL 调用都只在独立 GL 线程执行。 */
internal class FableSolGlRenderer(context: Context, private val density: Double) {

    data class Timing(
        val drainNs: Long,
        val physicsNs: Long,
        val buildNs: Long,
        val drawNs: Long,
        val physicsSubsteps: Int,
        val boundaryLayers: Int,
        val boundaryNs: Long,
        val waveNs: Long,
        val surfaceNs: Long,
        val composeNs: Long
    )

    private data class BackgroundSnapshot(
        val color: Int,
        val endColor: Int,
        val gradient: Boolean,
        val orientation: ThingBackground.Orientation
    )

    private data class MaterialColorKey(
        val color: Int,
        val endColor: Int,
        val gradient: Boolean
    )

    private val assets = context.assets
    // 持久化调参覆盖必须在 sim/mapper 构造前套用，二者与渲染共享同一 params 实例。
    private val params = FableSolParams().also { FableSolTuning.applyStored(context, it) }
    private val sim = FableSolSimulation(params)
    private val mapper = FableSolFeatureMapper(params)
    private val inputLock = Any()
    private var pendingFrames = ArrayList<FableSolFeatureFrame>()
    private var pendingEvents = ArrayList<FableSolEvent>()
    private val pendingTuning = HashMap<String, Double>()
    // 颜色过渡（调参 Dialog 换色）：UI 线程投递目标，GL 线程消费推进。
    @Volatile private var pendingTransitionTarget: BackgroundSnapshot? = null
    private var transitionFrom: BackgroundSnapshot? = null
    private var transitionTo: BackgroundSnapshot? = null
    private var transitionStartMs = 0L
    private var transitionEased = 0f
    private var lastFillBottom = 0.0
    // 预览取景：内容沿屏幕 y 的平移（px，负 = 上移；旋转在 CPU 侧补偿）与
    // 底部两角半径覆盖（<0 = 与顶部一致）。默认零/负一 = 录音界面原样。
    @Volatile private var contentOffsetYPx = 0f
    @Volatile private var bottomCornerRadiusPx = -1f
    // 暂停冻结（与 Python canvas 同语义）：不推进模拟与音频泵，渲染循环
    // 照跑——冻结画面上调参、换色、HDR 切换仍逐帧实时生效。
    @Volatile private var simulationPaused = false
    private val gravityInbox = FableSolGravityInbox()
    private val gravityScratch = FloatArray(3)
    private var consumedGravitySequence = 0
    private var gravitySeeded = false
    private var lastAudioElapsed = 0L
    private var lastFrameTimeNanos = 0L
    @Volatile private var background = BackgroundSnapshot(
        Color.parseColor("#F02A4B"),
        Color.parseColor("#F02A4B"),
        false,
        ThingBackground.Orientation.T_B
    )

    private val environmentBase = run {
        val value = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.colorBackground, value, true) &&
            value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
        ) FableSolColor.fromColor(value.data) else intArrayOf(255, 255, 255)
    }
    private val presentationBackdrop = FloatArray(3) { environmentBase[it] / 255f }
    private val cornerRadiusPx = context.resources.getDimension(
        R.dimen.app_chrome_dialog_popup_corner_radius
    )

    private var width = 1
    private var height = 1
    private lateinit var environmentProgram: FableSolGlProgram
    private lateinit var waterProgram: FableSolGlProgram
    private lateinit var opticalProgram: FableSolGlProgram
    private lateinit var presentationProgram: FableSolGlProgram
    private var vertexBufferId = 0
    private var frontBufferId = 0
    private var indexBufferId = 0
    private var opticalBufferId = 0
    private var vertexArrayId = 0
    private var waterVaoId = 0
    private var frontVaoId = 0
    private var opticalVaoId = 0
    private var sceneFramebufferId = 0
    private var sceneTextureId = 0
    private var preWaterFramebufferId = 0
    private var preWaterTextureId = 0
    // 场景几何画进多重采样 renderbuffer，再 resolve 进单采样 sceneTexture 供折射/present 采样。
    // 只解决九层弯曲界线、水天轮廓和光学几何的覆盖锯齿；材质与颜色仍逐像素一次计算。
    private var sceneMsaaFramebufferId = 0
    private var sceneMsaaRenderbufferId = 0
    private var sceneSamples = 1
    private var sceneTargetWidth = 0
    private var sceneTargetHeight = 0
    private val indexBufferState = FableSolGlIndexBufferState()
    @Volatile private var presentationAlpha = PREPARED_PRESENTATION_ALPHA
    @Volatile private var hdrRecordingRequested = false
    @Volatile private var displayHdrSdrRatio = 1f
    private val hdrTransition = FableSolHdrTransition()
    private var sceneLinear = false
    private var hdrContentEnabled = false
    private var hdrGain = 0f
    private var hdrHeadroom = 1f

    private val sourceIndex = IntArray(FableSolSpec.N_POINTS)
    private val sourceBefore = IntArray(FableSolSpec.N_POINTS)
    private val sourceAfter = IntArray(FableSolSpec.N_POINTS)
    private val sourceFraction = DoubleArray(FableSolSpec.N_POINTS)
    private val cubicWeights = FableSolCatmullRomWeightTable(FableSolSpec.N_POINTS)
    private val hermiteWeights = FableSolHermiteWeightTable(FableSolSpec.N_POINTS)
    private val layerMeans = DoubleArray(FableSolSpec.N_LAYERS)
    private val layerMeanTangents = DoubleArray(FableSolSpec.N_LAYERS)
    private val sheenSlopeX = FloatArray(FableSolContinuousSurface.Z_ROWS * FableSolSpec.N_POINTS)
    private val sheenSlopeZ = FloatArray(FableSolContinuousSurface.Z_ROWS * FableSolSpec.N_POINTS)
    // D151 厚度透光：逐锚层轮廓均值 y（物理 px，未旋转），供 uLayerMeanYPx。
    private val layerMeanYPx = FloatArray(FableSolSpec.N_LAYERS)
    // D156 v17 银丝太阳柱：row 0 可见跨度（本地 px），供 shader 换算 x01。
    private var crestRimX0Px = 0f
    private var crestRimSpanPx = 1f
    private val sheenSlopeScratch = FloatArray(
        FableSolContinuousSurface.Z_ROWS * FableSolSpec.N_POINTS
    )
    private val vertexData = FloatArray(
        FableSolContinuousSurface.Z_ROWS * FableSolSpec.N_POINTS *
            FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
    )
    private val frontData = FloatArray(
        FableSolSpec.N_POINTS * 2 * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
    )
    private val vertexUpload: FloatBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val frontUpload: FloatBuffer = ByteBuffer.allocateDirect(frontData.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val optics = FableSolGlOptics(density)
    private val opticalUpload: FloatBuffer = ByteBuffer.allocateDirect(optics.vertices.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val layerStart = FloatArray(FableSolSpec.N_LAYERS * 3)
    private val layerStop1 = FloatArray(FableSolSpec.N_LAYERS * 3)
    private val layerStop2 = FloatArray(FableSolSpec.N_LAYERS * 3)
    private val layerEnd = FloatArray(FableSolSpec.N_LAYERS * 3)
    private val layerSubsurfaceStart = FloatArray(FableSolSpec.N_LAYERS * 3)
    private val layerSubsurfaceStop1 = FloatArray(FableSolSpec.N_LAYERS * 3)
    private val layerSubsurfaceStop2 = FloatArray(FableSolSpec.N_LAYERS * 3)
    private val layerSubsurfaceEnd = FloatArray(FableSolSpec.N_LAYERS * 3)
    private val layerStartRgb = Array(FableSolSpec.N_LAYERS) { IntArray(3) }
    private val layerStop1Rgb = Array(FableSolSpec.N_LAYERS) { IntArray(3) }
    private val layerStop2Rgb = Array(FableSolSpec.N_LAYERS) { IntArray(3) }
    private val layerEndRgb = Array(FableSolSpec.N_LAYERS) { IntArray(3) }
    private val layerAlpha = FloatArray(FableSolSpec.N_LAYERS)
    private val interfaceWeightStart = FloatArray(FableSolSpec.N_LAYERS)
    private val interfaceWeightStop1 = FloatArray(FableSolSpec.N_LAYERS)
    private val interfaceWeightStop2 = FloatArray(FableSolSpec.N_LAYERS)
    private val interfaceWeightEnd = FloatArray(FableSolSpec.N_LAYERS)
    private val layerGradientOrigin = FloatArray(FableSolSpec.N_LAYERS * 2)
    private val layerGradientDirection = FloatArray(FableSolSpec.N_LAYERS * 2)
    private val layerGradientDenominator = FloatArray(FableSolSpec.N_LAYERS)
    private val environmentTop = FloatArray(3)
    private val environmentHorizon = FloatArray(3)
    private val environmentBottom = FloatArray(3)
    private val environmentHorizonRgb = IntArray(3)
    private var columns = 0
    private var vertexFloatCount = 0
    private var frontFloatCount = 0
    private var opticalFloatCount = 0
    private var rotationRad = 0f
    private var viewElevationRad = 0f
    private var materialColorKey: MaterialColorKey? = null
    private var framesUntilGlErrorCheck = GL_ERROR_CHECK_INTERVAL_FRAMES
    private var environmentUniformsDirty = true
    private var waterMaterialUniformsDirty = true
    // 每帧上传光学顶点后置位；帧内全层共享的光学 uniform 只在首个光学层上传。
    private var opticalUniformsDirty = true

    fun initialize(hdrOutput: Boolean) {
        sceneLinear = hdrOutput
        hdrContentEnabled = hdrOutput
        hdrGain = 0f
        hdrHeadroom = 1f
        hdrTransition.reset()
        materialColorKey = null
        framesUntilGlErrorCheck = GL_ERROR_CHECK_INTERVAL_FRAMES
        environmentUniformsDirty = true
        waterMaterialUniformsDirty = true
        environmentProgram = FableSolGlProgram(
            assets,
            "fablesol/glsl/fullscreen.vert",
            "fablesol/glsl/environment.frag"
        )
        waterProgram = FableSolGlProgram(
            assets,
            "fablesol/glsl/water.vert",
            "fablesol/glsl/water.frag"
        )
        opticalProgram = FableSolGlProgram(
            assets,
            "fablesol/glsl/optical.vert",
            "fablesol/glsl/optical.frag"
        )
        presentationProgram = FableSolGlProgram(
            assets,
            "fablesol/glsl/fullscreen.vert",
            "fablesol/glsl/present.frag"
        )
        waterProgram.use()
        uploadStaticWaterUniforms()
        val buffers = IntArray(4)
        GLES30.glGenBuffers(4, buffers, 0)
        vertexBufferId = buffers[0]
        frontBufferId = buffers[1]
        indexBufferId = buffers[2]
        opticalBufferId = buffers[3]
        val arrays = IntArray(4)
        GLES30.glGenVertexArrays(4, arrays, 0)
        vertexArrayId = arrays[0]
        waterVaoId = arrays[1]
        frontVaoId = arrays[2]
        opticalVaoId = arrays[3]
        // 顶点布局各自捕获进专属 VAO：绘制循环内只需 glBindVertexArray 一次切换，
        // 不再逐组重设 5~6 个 attrib pointer（布局与既有完全一致）。
        GLES30.glBindVertexArray(waterVaoId)
        specifyWaterVertexLayout(vertexBufferId)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)
        GLES30.glBindVertexArray(frontVaoId)
        specifyWaterVertexLayout(frontBufferId)
        GLES30.glBindVertexArray(opticalVaoId)
        specifyOpticalVertexLayout()
        // vertexArrayId 保持零 attrib，供全屏三角形的环境/呈现 pass 使用。
        GLES30.glBindVertexArray(vertexArrayId)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        checkGl("initialize")
    }

    fun resize(width: Int, height: Int) {
        this.width = max(width, 1)
        this.height = max(height, 1)
        sim.setContainerWidthDp(this.width / density)
        ensureSceneTargets(this.width, this.height)
        GLES30.glViewport(0, 0, this.width, this.height)
    }

    fun setPresentationAlpha(alpha: Float) {
        presentationAlpha = alpha.coerceIn(0f, 1f)
    }

    fun setHdrRecordingRequested(requested: Boolean) {
        hdrRecordingRequested = requested
    }

    fun setDisplayHdrSdrRatio(ratio: Float) {
        displayHdrSdrRatio = ratio
    }

    fun isHdrContentEnabled(): Boolean = hdrContentEnabled

    fun setThingBackground(background: ThingBackground) {
        this.background = BackgroundSnapshot(
            background.color,
            background.endColor,
            background.mode == ThingBackground.Mode.GRADIENT,
            background.orientation
        )
    }

    /**
     * 渐变切换到新配色（调参 Dialog 换色）：新颜色的水体从右缘涌入、软带边界
     * 左推、最终完全替代；进行中再次调用会以当前插值色为起点接力，无跳变。
     */
    fun beginBackgroundTransition(background: ThingBackground) {
        pendingTransitionTarget = BackgroundSnapshot(
            background.color,
            background.endColor,
            background.mode == ThingBackground.Mode.GRADIENT,
            background.orientation
        )
    }

    /** 预览取景：内容整体沿屏幕 y 平移（dp，负 = 上移）。只影响构图，不动物理。 */
    fun setContentVerticalOffsetDp(offsetDp: Float) {
        contentOffsetYPx = (offsetDp * density).toFloat()
    }

    /** 底部两角半径覆盖（px；<0 恢复与顶部一致）。 */
    fun setBottomCornerRadiusPx(radiusPx: Float) {
        bottomCornerRadiusPx = radiusPx
    }

    /** 暂停冻结：模拟与音频泵停住、画面静止，渲染照跑（调参实时可见）。 */
    fun setSimulationPaused(paused: Boolean) {
        simulationPaused = paused
    }

    fun setGravity(x: Float, y: Float, z: Float) {
        gravityInbox.offer(x, y, z)
    }

    fun onAudioFrames(frames: List<FableSolFeatureFrame>, events: List<FableSolEvent>) {
        if (frames.isEmpty() && events.isEmpty()) return
        synchronized(inputLock) {
            pendingFrames.addAll(frames)
            pendingEvents.addAll(events)
            if (pendingEvents.size > MAX_PENDING_EVENTS) {
                pendingEvents.subList(0, pendingEvents.size - MAX_PENDING_EVENTS).clear()
            }
        }
    }

    /** 运行时调参（UI 线程调用）：入待应用表，渲染帧起始在 GL 线程统一写入 params。 */
    fun setTuningValue(key: String, value: Double) {
        synchronized(inputLock) { pendingTuning[key] = value }
    }

    fun render(frameTimeNanos: Long): Timing {
        val drainStart = SystemClock.elapsedRealtimeNanos()
        val now = SystemClock.elapsedRealtime()
        drainAndApply(now)
        if (!simulationPaused) applyLatestGravity()
        advanceColorTransition(now)
        val physicsStart = SystemClock.elapsedRealtimeNanos()
        var dt = if (lastFrameTimeNanos == 0L) TARGET_FRAME_SECONDS else
            (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000.0
        lastFrameTimeNanos = frameTimeNanos
        if (dt <= 0.0) dt = TARGET_FRAME_SECONDS
        val boundedDt = dt.coerceAtMost(MAX_DT_SECONDS)
        hdrHeadroom = if (hdrContentEnabled) {
            FableSolHdrPolicy.advanceHeadroom(
                hdrHeadroom,
                displayHdrSdrRatio,
                boundedDt.toFloat()
            )
        } else {
            1f
        }
        hdrGain = hdrTransition.update(
            hdrContentEnabled && hdrRecordingRequested && hdrHeadroom > 1f,
            boundedDt.toFloat()
        )
        if (!simulationPaused) sim.update(boundedDt)
        val buildStart = SystemClock.elapsedRealtimeNanos()
        buildFrame()
        val drawStart = SystemClock.elapsedRealtimeNanos()
        drawFrame()
        val drawEnd = SystemClock.elapsedRealtimeNanos()
        return Timing(
            physicsStart - drainStart,
            buildStart - physicsStart,
            drawStart - buildStart,
            drawEnd - drawStart,
            sim.perfSubsteps,
            sim.perfBoundaryLayers,
            sim.perfBoundaryNs,
            sim.perfWaveNs,
            sim.perfSurfaceNs,
            sim.perfComposeNs
        )
    }

    fun release() {
        if (::environmentProgram.isInitialized) environmentProgram.release()
        if (::waterProgram.isInitialized) waterProgram.release()
        if (::opticalProgram.isInitialized) opticalProgram.release()
        if (::presentationProgram.isInitialized) presentationProgram.release()
        if (vertexBufferId != 0) {
            GLES30.glDeleteBuffers(
                4,
                intArrayOf(vertexBufferId, frontBufferId, indexBufferId, opticalBufferId),
                0
            )
        }
        if (vertexArrayId != 0) {
            GLES30.glDeleteVertexArrays(
                4,
                intArrayOf(vertexArrayId, waterVaoId, frontVaoId, opticalVaoId),
                0
            )
        }
        releaseSceneTargets()
        vertexBufferId = 0
        frontBufferId = 0
        indexBufferId = 0
        opticalBufferId = 0
        vertexArrayId = 0
        waterVaoId = 0
        frontVaoId = 0
        opticalVaoId = 0
        indexBufferState.onGlResourcesReleased()
        sceneLinear = false
        hdrContentEnabled = false
        hdrGain = 0f
        hdrHeadroom = 1f
        hdrTransition.reset()
    }

    private fun drainAndApply(now: Long) {
        val frames: ArrayList<FableSolFeatureFrame>?
        val events: ArrayList<FableSolEvent>
        var tuned = false
        synchronized(inputLock) {
            frames = if (pendingFrames.isEmpty()) null else pendingFrames
            pendingFrames = ArrayList()
            events = pendingEvents
            pendingEvents = ArrayList()
            if (pendingTuning.isNotEmpty()) {
                for ((key, value) in pendingTuning) params.set(key, value)
                pendingTuning.clear()
                tuned = true
            }
        }
        if (tuned) {
            // 静态材质色缓存读 lighten_far/color_breath/environment_tint 等参数，
            // 调参后强制下一帧重建。
            materialColorKey = null
        }
        if (simulationPaused) {
            // 冻结：丢弃本帧 drain 到的特征与事件（恢复后从最新实时输入继续），
            // 静默衰减计时锚随帧推移一并冻结。
            if (lastAudioElapsed != 0L) lastAudioElapsed = now
            return
        }
        if (frames != null) {
            mapper.applyFrame(sim, frames[frames.lastIndex])
            lastAudioElapsed = now
        } else if (lastAudioElapsed != 0L && now - lastAudioElapsed > IDLE_SILENCE_MS) {
            mapper.applySilence(sim)
        }
        for (event in events) when (event) {
            is FableSolEvent.Onset -> mapper.applyOnset(sim, event)
            is FableSolEvent.Section -> mapper.applySection(sim, event)
            is FableSolEvent.Prominence -> mapper.applyProminence(sim, event)
        }
    }

    /** 推进颜色过渡：消费待启动目标、按真实时间求缓动进度，结束时落定目标色。 */
    private fun advanceColorTransition(now: Long) {
        val target = pendingTransitionTarget
        if (target != null) {
            pendingTransitionTarget = null
            transitionFrom = if (transitionTo != null) currentLerpSnapshot() else background
            transitionTo = target
            transitionStartMs = now
            transitionEased = 0f
        }
        val to = transitionTo ?: return
        val progress = ((now - transitionStartMs).toFloat() / COLOR_TRANSITION_MS)
            .coerceIn(0f, 1f)
        transitionEased = progress * progress * (3f - 2f * progress)
        if (progress >= 1f) {
            background = to
            transitionFrom = null
            transitionTo = null
            materialColorKey = null
        }
    }

    private fun currentLerpSnapshot(): BackgroundSnapshot {
        val from = transitionFrom ?: return background
        val to = transitionTo ?: return background
        return lerpSnapshot(from, to, transitionEased.toDouble())
    }

    private fun lerpSnapshot(
        from: BackgroundSnapshot,
        to: BackgroundSnapshot,
        fraction: Double
    ): BackgroundSnapshot {
        val start = mixColorOklab(from.color, to.color, fraction)
        val fromEnd = if (from.gradient) from.endColor else from.color
        val toEnd = if (to.gradient) to.endColor else to.color
        val gradient = from.gradient || to.gradient
        val end = if (gradient) mixColorOklab(fromEnd, toEnd, fraction) else start
        return BackgroundSnapshot(start, end, gradient, to.orientation)
    }

    private fun mixColorOklab(a: Int, b: Int, fraction: Double): Int {
        val mixed = FableSolColor.mixOklab(
            FableSolColor.fromColor(a),
            FableSolColor.fromColor(b),
            fraction
        )
        return Color.rgb(mixed[0], mixed[1], mixed[2])
    }

    private fun applyLatestGravity() {
        val sequence = gravityInbox.drainLatest(consumedGravitySequence, gravityScratch)
        if (sequence == consumedGravitySequence) return
        consumedGravitySequence = sequence
        val x = gravityScratch[0].toDouble()
        val y = gravityScratch[1].toDouble()
        val z = gravityScratch[2].toDouble()
        val tilt = Math.toDegrees(atan2(x, y))
        val pitch = Math.toDegrees(atan2(z, hypot(x, y)))
        sim.setTilt(tilt, snap = !gravitySeeded)
        sim.setPitch(pitch, snap = !gravitySeeded)
        gravitySeeded = true
    }

    private fun buildFrame() {
        val info = sim.continuousRenderInfo()
        val rawColumns = info.i1 - info.i0
        columns = sim.continuousRenderColumnCount(rawColumns)
        if (columns < 2) {
            vertexFloatCount = 0
            frontFloatCount = 0
            opticalFloatCount = 0
            return
        }
        val sample = sim.surface2d.sample(sim)
        for (layer in layerMeans.indices) {
            var sum = 0.0
            for (value in sim.heights[layer]) sum += value
            layerMeans[layer] = sum / sim.heights[layer].size
        }
        FableSolDepthBaseline.updateTangents(layerMeans, layerMeanTangents)
        val viewBase = params.get("surface_view_elev_deg")
        val viewElevation = FableSolPitchPolicy.viewElevationDeg(sim.pitchDeg, viewBase)
        val depthScale = sin(Math.toRadians(viewElevation)) /
            max(sin(Math.toRadians(viewBase)), 0.2)
        rotationRad = info.thetaRad.toFloat()
        viewElevationRad = Math.toRadians(viewElevation).toFloat()
        // 预览取景平移：顶点在旋转前空间，偏移向量按 R^{-1}·(0, dy) 补偿，
        // 使屏幕上是纯垂直平移（录音界面 offset=0，两分量恒 0）。
        val offsetDy = contentOffsetYPx.toDouble()
        val offsetXPx = sin(info.thetaRad) * offsetDy
        val offsetYPx = cos(info.thetaRad) * offsetDy

        for (column in 0 until columns) {
            val source = sim.continuousRenderSourcePosition(info.i0, rawColumns, columns, column)
            val index = min(source.toInt(), info.i1 - 2)
            sourceIndex[column] = index
            sourceBefore[column] = (index - 1).coerceAtLeast(0)
            sourceAfter[column] = (index + 2).coerceAtMost(FableSolSpec.N_POINTS - 1)
            val fraction = (source - index).coerceIn(0.0, 1.0)
            sourceFraction[column] = fraction
            cubicWeights.update(column, fraction, FableSolSpec.DX_DP)
            hermiteWeights.update(column, fraction, FableSolSpec.DX_DP)
        }

        // 逐行独立填充（游标 = 行基址 + 列内偏移），行间无共享写，可安全并行；
        // 每个顶点的数学与串行版逐条一致。
        val fillColumns = columns
        FableSolRowParallel.run(FableSolContinuousSurface.Z_ROWS) { startRow, endRow ->
            var cursor = startRow * fillColumns * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
            for (row in startRow until endRow) {
                for (column in 0 until fillColumns) {
                    val index = sourceIndex[column]
                    val before = sourceBefore[column]
                    val after = sourceAfter[column]
                    val fraction = sourceFraction[column]
                    val next = index + 1
                    val w0 = cubicWeights.w0[column]
                    val w1 = cubicWeights.w1[column]
                    val w2 = cubicWeights.w2[column]
                    val w3 = cubicWeights.w3[column]
                    val orbitZ = (
                        sample.orbitZ[row][before] * w0 +
                            sample.orbitZ[row][index] * w1 +
                            sample.orbitZ[row][next] * w2 +
                            sample.orbitZ[row][after] * w3
                        ).coerceIn(-10.0, 10.0)
                    val rawOrbitX =
                        sample.orbitX[row][before] * w0 +
                            sample.orbitX[row][index] * w1 +
                            sample.orbitX[row][next] * w2 +
                            sample.orbitX[row][after] * w3
                    val orbitX = rawOrbitX.coerceIn(-10.0, 10.0)
                    val worldEta =
                        sample.worldEta[row][index] * hermiteWeights.h00[column] +
                            sample.slopeX[row][index] * hermiteWeights.h10[column] +
                            sample.worldEta[row][next] * hermiteWeights.h01[column] +
                            sample.slopeX[row][next] * hermiteWeights.h11[column]
                    val uDp = lerp(sim.uGrid[index], sim.uGrid[index + 1], fraction)
                    val z01 = ((sample.zDp[row] + orbitZ) / max(sample.depthDp, 1e-6))
                        .coerceIn(-0.08, 1.08)
                    val layerPosition = z01.coerceIn(0.0, 1.0) * (FableSolSpec.N_LAYERS - 1)
                    var baseHeight = FableSolDepthBaseline.value(
                        layerMeans,
                        layerMeanTangents,
                        layerPosition
                    )
                    baseHeight = layerMeans[0] + (baseHeight - layerMeans[0]) * depthScale
                    val perspective = 1.0 / (1.0 + 0.16 * z01.coerceIn(0.0, 1.1))
                    val slopeX = (
                        sample.worldEta[row][index] * hermiteWeights.dh00[column] +
                            sample.slopeX[row][index] * hermiteWeights.dh10[column] +
                            sample.worldEta[row][next] * hermiteWeights.dh01[column] +
                            sample.slopeX[row][next] * hermiteWeights.dh11[column]
                        ).toFloat()
                    val slopeZ = (
                        sample.slopeZ[row][before] * w0 +
                            sample.slopeZ[row][index] * w1 +
                            sample.slopeZ[row][next] * w2 +
                            sample.slopeZ[row][after] * w3
                        ).toFloat()
                    val gridIndex = row * fillColumns + column
                    vertexData[cursor++] =
                        ((uDp + orbitX) * density * perspective + offsetXPx).toFloat()
                    vertexData[cursor++] =
                        ((info.hG / 2.0 - (baseHeight + worldEta)) * density + offsetYPx).toFloat()
                    vertexData[cursor++] = slopeX
                    vertexData[cursor++] = slopeZ
                    vertexData[cursor++] =
                        (row.toDouble() / (FableSolContinuousSurface.Z_ROWS - 1)).toFloat()
                    val orbitDerivative = if (rawOrbitX != orbitX) {
                        0.0
                    } else {
                        sample.orbitX[row][before] * cubicWeights.dw0[column] +
                            sample.orbitX[row][index] * cubicWeights.dw1[column] +
                            sample.orbitX[row][next] * cubicWeights.dw2[column] +
                            sample.orbitX[row][after] * cubicWeights.dw3[column]
                    }
                    vertexData[cursor++] =
                        FableSolDepthScatteringPolicy.crestPinch(orbitDerivative).toFloat()
                    sheenSlopeX[gridIndex] = slopeX
                    sheenSlopeZ[gridIndex] = slopeZ
                    vertexData[cursor++] = slopeX
                    vertexData[cursor++] = slopeZ
                }
            }
        }
        var cursor = FableSolContinuousSurface.Z_ROWS * fillColumns *
            FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
        FableSolSheenSlopeFilter.smooth(
            sheenSlopeX,
            sheenSlopeScratch,
            FableSolContinuousSurface.Z_ROWS,
            columns
        )
        FableSolSheenSlopeFilter.smooth(
            sheenSlopeZ,
            sheenSlopeScratch,
            FableSolContinuousSurface.Z_ROWS,
            columns
        )
        for (vertex in 0 until FableSolContinuousSurface.Z_ROWS * columns) {
            val offset = vertex * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
            vertexData[offset + FableSolGlMeshLayout.SHEEN_SLOPE_X_OFFSET] = sheenSlopeX[vertex]
            vertexData[offset + FableSolGlMeshLayout.SHEEN_SLOPE_Z_OFFSET] = sheenSlopeZ[vertex]
        }
        // D156 v17 银丝太阳柱：row 0 可见跨度（与 Python crest_rim_x0/span 一比一）。
        crestRimX0Px = vertexData[0]
        crestRimSpanPx = (vertexData[(columns - 1) *
            FableSolGlMeshLayout.COMPONENTS_PER_VERTEX] - vertexData[0])
            .coerceAtLeast(1f)
        // D151 厚度透光：9 个锚行的可见列均值 y（与 aPositionPx 同空间，y 向下为正，
        // 波峰更小）。与 Python gl_scene 的 layer_mean_y_px 一比一。
        for (layer in 0 until FableSolSpec.N_LAYERS) {
            val row = layer * FableSolContinuousSurface.ROWS_PER_LAYER
            var sum = 0.0
            for (column in 0 until columns) {
                val offset = (row * columns + column) *
                    FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
                sum += vertexData[offset + 1]
            }
            layerMeanYPx[layer] = (sum / columns).toFloat()
        }
        vertexFloatCount = cursor

        val fillBottom = (info.hG / 2.0 + FILL_EXTRA_DP) * density + offsetYPx
        cursor = 0
        // D155：fill 宏观坡度 x 恒 0，闲置的 aSlope.y（slopeZ 分量）改运本列
        // 水面 y（上下两排同值），供片元按"水面下深度"做 Beer–Lambert 衰减；
        // 顶边另继承 row 0 的 sheen slope，迎光门在水线接缝处连续（底边 0）。
        for (column in 0 until columns) {
            val sourceOffset = column * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
            frontData[cursor++] = vertexData[sourceOffset]
            frontData[cursor++] = vertexData[sourceOffset + 1]
            frontData[cursor++] = 0f
            frontData[cursor++] = vertexData[sourceOffset + 1]
            frontData[cursor++] = 0f
            frontData[cursor++] = vertexData[sourceOffset + 5]
            frontData[cursor++] =
                vertexData[sourceOffset + FableSolGlMeshLayout.SHEEN_SLOPE_X_OFFSET]
            frontData[cursor++] =
                vertexData[sourceOffset + FableSolGlMeshLayout.SHEEN_SLOPE_Z_OFFSET]
            frontData[cursor++] = vertexData[sourceOffset]
            frontData[cursor++] = fillBottom.toFloat()
            frontData[cursor++] = 0f
            frontData[cursor++] = vertexData[sourceOffset + 1]
            // front fill 顶边使用 depth=0，向水体内部递减；fragment shader 据此构造 1px coverage。
            frontData[cursor++] = -1f
            frontData[cursor++] = 0f
            frontData[cursor++] = 0f
            frontData[cursor++] = 0f
        }
        frontFloatCount = cursor
        buildColors(fillBottom)
        opticalFloatCount = optics.build(
            sim,
            params,
            columns,
            vertexData,
            layerStartRgb,
            layerEndRgb,
            environmentHorizonRgb,
            sourceIndex,
            sourceFraction,
            layerStop1 = layerStop1Rgb,
            layerStop2 = layerStop2Rgb,
            gradientOrigin = layerGradientOrigin,
            gradientDirection = layerGradientDirection,
            gradientDenominator = layerGradientDenominator,
            interfaceWeightStart = interfaceWeightStart,
            interfaceWeightStop1 = interfaceWeightStop1,
            interfaceWeightStop2 = interfaceWeightStop2,
            interfaceWeightEnd = interfaceWeightEnd
        )
    }

    private fun buildColors(fillBottom: Double) {
        lastFillBottom = fillBottom
        // 颜色过渡中：主遍（含环境与 optics 的取色数组）每帧用插值配色重建；
        // 目标配色的揭示遍在 drawColorRevealPass 里另行构建上传。
        val snapshot = if (transitionTo != null) {
            materialColorKey = null
            currentLerpSnapshot().also(::buildStaticMaterialColors)
        } else {
            val steady = background
            val key = MaterialColorKey(
                color = steady.color,
                endColor = if (steady.gradient) steady.endColor else steady.color,
                gradient = steady.gradient
            )
            if (materialColorKey != key) {
                buildStaticMaterialColors(steady)
                materialColorKey = key
            }
            steady
        }
        // 方向与轮廓会随背景设置、重力和波形变化；只有这部分需要逐帧更新。
        for (layer in 0 until FableSolSpec.N_LAYERS) {
            buildLayerGradientGeometry(layer, fillBottom, snapshot)
        }
    }

    private fun buildStaticMaterialColors(snapshot: BackgroundSnapshot) {
        val base = FableSolLayerColorPolicy.baseColors(
            FableSolColor.fromColor(snapshot.color),
            if (snapshot.gradient) FableSolColor.fromColor(snapshot.endColor) else null
        )
        val breath = params.get("color_breath") *
            (0.30 * (sim.colorBright01 - 0.45) + 0.18 * (sim.colorEnergy01 - 0.5))
        val palette = FableSolLayerColorPolicy.palette(
            base = base,
            lightenFar = params.get("lighten_far"),
            moodBright = sim.moodBright,
            breath = breath
        )
        // subsurface 只供局部透射/SSS，并在下方从每层四停靠点的最终主体色分别派生；
        // 不再把原始 Thing 的同一组散射色复制给九层。
        for (layer in 0 until FableSolSpec.N_LAYERS) {
            val stops = palette.layers[layer]
            val start = stops.start
            val stop1 = stops.stop1
            val stop2 = stops.stop2
            val end = stops.end
            for (channel in 0 until 3) {
                layerStartRgb[layer][channel] = start[channel]
                layerStop1Rgb[layer][channel] = stop1[channel]
                layerStop2Rgb[layer][channel] = stop2[channel]
                layerEndRgb[layer][channel] = end[channel]
            }
            putColor(layerStart, layer, start)
            putColor(layerStop1, layer, stop1)
            putColor(layerStop2, layer, stop2)
            putColor(layerEnd, layer, end)
            putSubsurfaceColor(
                layer, FableSolDepthScatteringPolicy.derive(start), layerSubsurfaceStart
            )
            putSubsurfaceColor(
                layer, FableSolDepthScatteringPolicy.derive(stop1), layerSubsurfaceStop1
            )
            putSubsurfaceColor(
                layer, FableSolDepthScatteringPolicy.derive(stop2), layerSubsurfaceStop2
            )
            putSubsurfaceColor(
                layer, FableSolDepthScatteringPolicy.derive(end), layerSubsurfaceEnd
            )
            layerAlpha[layer] = params.lget("alpha", layer).toFloat()
        }
        copyInterfaceWeights(palette.interfaceWeights.start, interfaceWeightStart)
        copyInterfaceWeights(palette.interfaceWeights.stop1, interfaceWeightStop1)
        copyInterfaceWeights(palette.interfaceWeights.stop2, interfaceWeightStop2)
        copyInterfaceWeights(palette.interfaceWeights.end, interfaceWeightEnd)
        val tint = params.get("environment_tint")
        val top = FableSolColor.mixOklab(
            environmentBase,
            FableSolColor.mixOklab(base.end, WHITE, 0.72),
            tint * 0.55
        )
        val horizon = FableSolColor.mixOklab(
            environmentBase,
            FableSolColor.mixOklab(base.start, WHITE, 0.78),
            tint
        )
        val bottom = FableSolColor.mixOklab(
            environmentBase,
            FableSolColor.mixOklab(base.end, WHITE, 0.84),
            tint * 0.42
        )
        putColor(environmentTop, top)
        putColor(environmentHorizon, horizon)
        putColor(environmentBottom, bottom)
        for (channel in 0 until 3) environmentHorizonRgb[channel] = horizon[channel]
        environmentUniformsDirty = true
        waterMaterialUniformsDirty = true
    }

    /** 界面肩权重由色板策略基于最终层色统一给出；Renderer 只负责上传。 */
    private fun copyInterfaceWeights(source: DoubleArray, target: FloatArray) {
        for (boundary in target.indices) target[boundary] = source[boundary].toFloat()
    }

    private fun buildLayerGradientGeometry(layer: Int, fillBottom: Double,
                                           snapshot: BackgroundSnapshot) {
        val row = layer * FableSolContinuousSurface.ROWS_PER_LAYER
        var x0 = Double.POSITIVE_INFINITY
        var x1 = Double.NEGATIVE_INFINITY
        var yTop = Double.POSITIVE_INFINITY
        for (column in 0 until columns) {
            val offset = (row * columns + column) * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
            val px = vertexData[offset].toDouble()
            val py = vertexData[offset + 1].toDouble()
            x0 = min(x0, px)
            x1 = max(x1, px)
            yTop = min(yTop, py)
        }
        val midX = (x0 + x1) * 0.5
        val midY = (yTop + fillBottom) * 0.5
        val orientation = if (snapshot.gradient) snapshot.orientation else
            ThingBackground.Orientation.T_B
        val ox: Double
        val oy: Double
        val dx: Double
        val dy: Double
        when (orientation) {
            ThingBackground.Orientation.L_R -> { ox = x0; oy = midY; dx = x1 - x0; dy = 0.0 }
            ThingBackground.Orientation.R_L -> { ox = x1; oy = midY; dx = x0 - x1; dy = 0.0 }
            ThingBackground.Orientation.T_B -> { ox = midX; oy = yTop; dx = 0.0; dy = fillBottom - yTop }
            ThingBackground.Orientation.B_T -> { ox = midX; oy = fillBottom; dx = 0.0; dy = yTop - fillBottom }
            ThingBackground.Orientation.LT_RB -> { ox = x0; oy = yTop; dx = x1 - x0; dy = fillBottom - yTop }
            ThingBackground.Orientation.RB_LT -> { ox = x1; oy = fillBottom; dx = x0 - x1; dy = yTop - fillBottom }
            ThingBackground.Orientation.LB_RT -> { ox = x0; oy = fillBottom; dx = x1 - x0; dy = yTop - fillBottom }
            ThingBackground.Orientation.RT_LB -> { ox = x1; oy = yTop; dx = x0 - x1; dy = fillBottom - yTop }
        }
        val offset = layer * 2
        layerGradientOrigin[offset] = ox.toFloat()
        layerGradientOrigin[offset + 1] = oy.toFloat()
        layerGradientDirection[offset] = dx.toFloat()
        layerGradientDirection[offset + 1] = dy.toFloat()
        layerGradientDenominator[layer] = max(dx * dx + dy * dy, 1e-6).toFloat()
    }

    private fun drawFrame() {
        check(sceneFramebufferId != 0 && sceneTextureId != 0 &&
            preWaterFramebufferId != 0 && preWaterTextureId != 0
        ) { "Scene targets are unavailable" }
        GLES30.glBindVertexArray(vertexArrayId)

        // 折射必须采样不含水面的不可变背景。Android 常见 tile GPU 上，直接在 scene
        // 再画一次极简环境可留在 tile 内；从 FP16 pre-water Blit 反而可能强制外存 resolve。
        // MSAA 时几何画进多重采样目标；pre-water 始终单采样（只是平滑环境，且是折射采样源）。
        val sceneDrawFramebufferId =
            if (sceneSamples > 1) sceneMsaaFramebufferId else sceneFramebufferId
        drawEnvironmentTo(preWaterFramebufferId)
        drawEnvironmentTo(sceneDrawFramebufferId)
        if (vertexFloatCount > 0) {
            ensureIndexBuffer(columns)
            uploadBuffer(vertexBufferId, vertexData, vertexFloatCount, vertexUpload)
            uploadBuffer(frontBufferId, frontData, frontFloatCount, frontUpload)
            if (opticalFloatCount > 0) {
                uploadBuffer(opticalBufferId, optics.vertices, opticalFloatCount, opticalUpload)
                opticalUniformsDirty = true
            }
            waterProgram.use()
            bindPreWaterScene()
            uploadWaterUniforms()
            GLES30.glBindVertexArray(waterVaoId)
            GLES30.glUniform1i(waterProgram.uniform("uFrontFill"), 0)
            GLES30.glDisable(GLES30.GL_BLEND)
            for (group in 0 until FableSolGlMeshLayout.GROUP_COUNT) {
                val layer = 8 - group
                GLES30.glUniform1i(waterProgram.uniform("uStartLayer"), layer)
                GLES30.glDrawElements(
                    GLES30.GL_TRIANGLES,
                    indexBufferState.indexCountPerGroup,
                    GLES30.GL_UNSIGNED_SHORT,
                    group * indexBufferState.indexCountPerGroup * 2
                )
                if (drawOpticalLayer(layer)) {
                    // 光学遍切走了 program/VAO/混合状态，切回水体主遍。
                    waterProgram.use()
                    GLES30.glBindVertexArray(waterVaoId)
                    GLES30.glDisable(GLES30.GL_BLEND)
                }
            }

            GLES30.glBindVertexArray(frontVaoId)
            GLES30.glUniform1i(waterProgram.uniform("uFrontFill"), 1)
            GLES30.glUniform1i(waterProgram.uniform("uStartLayer"), 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, columns * 2)
            if (drawOpticalLayer(0)) {
                waterProgram.use()
                GLES30.glDisable(GLES30.GL_BLEND)
            }
            if (transitionTo != null) {
                drawColorRevealPass()
            }
            unbindPreWaterScene()
            GLES30.glBindVertexArray(vertexArrayId)
        }
        if (sceneSamples > 1) resolveSceneMsaa()
        presentScene()
        framesUntilGlErrorCheck--
        if (framesUntilGlErrorCheck <= 0) {
            checkGl("drawFrame sampled")
            framesUntilGlErrorCheck = GL_ERROR_CHECK_INTERVAL_FRAMES
        }
    }

    /**
     * 颜色过渡的揭示遍：以目标配色重建材质并把水体（九层 + front fill）再画一遍，
     * fragment 端 colorRevealAlpha 只在软带边界右侧显影——新颜色的波浪从右缘
     * 涌入、覆盖主遍的插值配色。optics 不重画（主遍已用插值色；被新色浪头
     * 盖过是"涌入"的一部分）。成员色数组被目标配色覆盖，下一帧 buildColors
     * 会以插值配色整体重建，环境与 optics 取色随之恢复。
     */
    private fun drawColorRevealPass() {
        val target = transitionTo ?: return
        buildStaticMaterialColors(target)
        for (layer in 0 until FableSolSpec.N_LAYERS) {
            buildLayerGradientGeometry(layer, lastFillBottom, target)
        }
        waterProgram.use()
        uploadWaterMaterialUniforms()
        waterMaterialUniformsDirty = false
        GLES30.glUniform2fv(
            waterProgram.uniform("uGradientOrigin[0]"),
            FableSolSpec.N_LAYERS,
            layerGradientOrigin,
            0
        )
        GLES30.glUniform2fv(
            waterProgram.uniform("uGradientDirection[0]"),
            FableSolSpec.N_LAYERS,
            layerGradientDirection,
            0
        )
        GLES30.glUniform1fv(
            waterProgram.uniform("uGradientDenominator[0]"),
            FableSolSpec.N_LAYERS,
            layerGradientDenominator,
            0
        )
        val softPx = (COLOR_REVEAL_SOFT_DP * density).toFloat()
        val sweepPx = width + 2f * softPx
        val edgePx = width + softPx - transitionEased * sweepPx
        GLES30.glUniform1f(waterProgram.uniform("uColorRevealEdgePx"), edgePx)
        GLES30.glUniform1f(waterProgram.uniform("uColorRevealSoftPx"), softPx)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glBindVertexArray(waterVaoId)
        GLES30.glUniform1i(waterProgram.uniform("uFrontFill"), 0)
        for (group in 0 until FableSolGlMeshLayout.GROUP_COUNT) {
            GLES30.glUniform1i(waterProgram.uniform("uStartLayer"), 8 - group)
            GLES30.glDrawElements(
                GLES30.GL_TRIANGLES,
                indexBufferState.indexCountPerGroup,
                GLES30.GL_UNSIGNED_SHORT,
                group * indexBufferState.indexCountPerGroup * 2
            )
        }
        GLES30.glBindVertexArray(frontVaoId)
        GLES30.glUniform1i(waterProgram.uniform("uFrontFill"), 1)
        GLES30.glUniform1i(waterProgram.uniform("uStartLayer"), 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, columns * 2)
        GLES30.glDisable(GLES30.GL_BLEND)
        // 复位揭示门：program 级状态会跨帧滞留，主遍必须恒 1。
        GLES30.glUniform1f(waterProgram.uniform("uColorRevealSoftPx"), 0f)
    }

    private fun drawEnvironmentTo(framebufferId: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        environmentProgram.use()
        if (environmentUniformsDirty) {
            uploadEnvironmentUniforms(environmentProgram)
            environmentUniformsDirty = false
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun bindPreWaterScene() {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, preWaterTextureId)
        GLES30.glUniform1i(waterProgram.uniform("uPreWaterScene"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun unbindPreWaterScene() {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun ensureSceneTargets(width: Int, height: Int) {
        if (sceneFramebufferId != 0 && sceneTextureId != 0 &&
            preWaterFramebufferId != 0 && preWaterTextureId != 0 &&
            sceneTargetWidth == width && sceneTargetHeight == height
        ) return
        releaseSceneTargets()

        val requestedHdrTargets = hdrContentEnabled
        if (!createSceneTargets(width, height, requestedHdrTargets)) {
            releaseSceneTargets()
            if (requestedHdrTargets) {
                // 两个 FP16 目标必须一起成功；任一失败就原子回退到同格式 RGBA8。
                // sceneLinear 描述 EGL 输出/合成颜色空间，不得因离屏格式回退而改写。
                discardGlErrorsBeforeTargetFallback()
                hdrContentEnabled = false
                if (!createSceneTargets(width, height, hdrTargets = false)) {
                    releaseSceneTargets()
                    error("RGBA8 scene framebuffers are incomplete")
                }
            } else {
                error("RGBA8 scene framebuffers are incomplete")
            }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        checkGl("ensureSceneTargets")
    }

    private fun createSceneTargets(width: Int, height: Int, hdrTargets: Boolean): Boolean {
        val textures = IntArray(2)
        GLES30.glGenTextures(2, textures, 0)
        sceneTextureId = textures[0]
        preWaterTextureId = textures[1]
        if (sceneTextureId == 0 || preWaterTextureId == 0) return false

        val internalFormat = if (hdrTargets) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
        val componentType = if (hdrTargets) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE
        configureSceneTexture(sceneTextureId, width, height, internalFormat, componentType,
            GLES30.GL_NEAREST)
        configureSceneTexture(preWaterTextureId, width, height, internalFormat, componentType,
            GLES30.GL_LINEAR)

        val framebuffers = IntArray(2)
        GLES30.glGenFramebuffers(2, framebuffers, 0)
        sceneFramebufferId = framebuffers[0]
        preWaterFramebufferId = framebuffers[1]
        if (sceneFramebufferId == 0 || preWaterFramebufferId == 0) return false
        val sceneComplete = attachAndCheckSceneTarget(sceneFramebufferId, sceneTextureId)
        val preWaterComplete = attachAndCheckSceneTarget(
            preWaterFramebufferId,
            preWaterTextureId
        )
        if (!sceneComplete || !preWaterComplete) return false

        // MSAA 只是单采样目标之上的可选增益：建立失败仅退回单采样，不改变 HDR 精度回退契约。
        setupSceneMsaa(width, height, internalFormat)

        sceneTargetWidth = width
        sceneTargetHeight = height
        return true
    }

    /** 场景 MSAA renderbuffer 与 resolve FBO；不支持该格式采样时安静退回单采样。 */
    private fun setupSceneMsaa(width: Int, height: Int, internalFormat: Int) {
        sceneSamples = 1
        val samples = querySceneSamples(internalFormat)
        if (samples < 2) return
        val renderbuffers = IntArray(1)
        GLES30.glGenRenderbuffers(1, renderbuffers, 0)
        val framebuffers = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffers, 0)
        if (renderbuffers[0] == 0 || framebuffers[0] == 0) {
            deleteSceneMsaa(renderbuffers[0], framebuffers[0])
            discardGlErrorsBeforeTargetFallback()
            return
        }
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, renderbuffers[0])
        GLES30.glRenderbufferStorageMultisample(
            GLES30.GL_RENDERBUFFER, samples, internalFormat, width, height
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[0])
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_RENDERBUFFER,
            renderbuffers[0]
        )
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) ==
            GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, 0)
        if (!complete) {
            deleteSceneMsaa(renderbuffers[0], framebuffers[0])
            discardGlErrorsBeforeTargetFallback()
            return
        }
        sceneMsaaRenderbufferId = renderbuffers[0]
        sceneMsaaFramebufferId = framebuffers[0]
        sceneSamples = samples
    }

    /** 查询该内部格式支持的采样数上限；查询本身失败或不支持多采样时返回 1。 */
    private fun querySceneSamples(internalFormat: Int): Int {
        val formatSamples = IntArray(1)
        GLES30.glGetInternalformativ(
            GLES30.GL_RENDERBUFFER,
            internalFormat,
            GLES30.GL_SAMPLES,
            1,
            formatSamples,
            0
        )
        // glGetInternalformativ 返回降序采样列表，首元素即最大值；FP16 在部分设备返回 0。
        discardGlErrorsBeforeTargetFallback()
        val supported = formatSamples[0]
        if (supported < 2) return 1
        return min(TARGET_MSAA_SAMPLES, supported).coerceAtLeast(1)
    }

    private fun deleteSceneMsaa(renderbufferId: Int, framebufferId: Int) {
        if (framebufferId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
        }
        if (renderbufferId != 0) {
            GLES30.glDeleteRenderbuffers(1, intArrayOf(renderbufferId), 0)
        }
    }

    private fun configureSceneTexture(textureId: Int, width: Int, height: Int,
                                      internalFormat: Int, componentType: Int, filter: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            internalFormat,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            componentType,
            null
        )
    }

    private fun attachAndCheckSceneTarget(framebufferId: Int, textureId: Int): Boolean {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            textureId,
            0
        )
        return GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) ==
            GLES30.GL_FRAMEBUFFER_COMPLETE
    }

    private fun discardGlErrorsBeforeTargetFallback() {
        repeat(8) {
            if (GLES30.glGetError() == GLES30.GL_NO_ERROR) return
            // 这里只丢弃已经确认失败的 FP16 目标尝试产生的错误，再独立验证 RGBA8 目标。
        }
    }

    /** 把多重采样场景 resolve 进单采样 sceneTexture。resolve 尺寸相同，滤波必须为 NEAREST。 */
    private fun resolveSceneMsaa() {
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, sceneMsaaFramebufferId)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, sceneFramebufferId)
        GLES30.glBlitFramebuffer(
            0, 0, width, height,
            0, 0, width, height,
            GLES30.GL_COLOR_BUFFER_BIT,
            GLES30.GL_NEAREST
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun presentScene() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        presentationProgram.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTextureId)
        GLES30.glUniform1i(presentationProgram.uniform("uScene"), 0)
        GLES30.glUniform3fv(
            presentationProgram.uniform("uBackdropColor"),
            1,
            presentationBackdrop,
            0
        )
        GLES30.glUniform1f(
            presentationProgram.uniform("uPresentationAlpha"),
            presentationAlpha
        )
        GLES30.glUniform2f(
            presentationProgram.uniform("uViewportPx"),
            width.toFloat(),
            height.toFloat()
        )
        GLES30.glUniform1f(presentationProgram.uniform("uCornerRadiusPx"), cornerRadiusPx)
        val bottomRadiusPx = if (bottomCornerRadiusPx >= 0f) bottomCornerRadiusPx else cornerRadiusPx
        GLES30.glUniform1f(
            presentationProgram.uniform("uCornerRadiusBottomDeltaPx"),
            bottomRadiusPx - cornerRadiusPx
        )
        GLES30.glUniform1i(
            presentationProgram.uniform("uSceneLinear"),
            if (sceneLinear) 1 else 0
        )
        GLES30.glUniform1f(
            presentationProgram.uniform("uHdrHeadroom"),
            hdrHeadroom
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun releaseSceneTargets() {
        if (sceneFramebufferId != 0 || preWaterFramebufferId != 0) {
            GLES30.glDeleteFramebuffers(
                2,
                intArrayOf(sceneFramebufferId, preWaterFramebufferId),
                0
            )
        }
        if (sceneTextureId != 0 || preWaterTextureId != 0) {
            GLES30.glDeleteTextures(2, intArrayOf(sceneTextureId, preWaterTextureId), 0)
        }
        deleteSceneMsaa(sceneMsaaRenderbufferId, sceneMsaaFramebufferId)
        sceneMsaaFramebufferId = 0
        sceneMsaaRenderbufferId = 0
        sceneSamples = 1
        sceneFramebufferId = 0
        sceneTextureId = 0
        preWaterFramebufferId = 0
        preWaterTextureId = 0
        sceneTargetWidth = 0
        sceneTargetHeight = 0
    }

    /** 画一层光学实体；返回是否真的切换了 program/VAO/混合状态（供主遍决定是否切回）。 */
    private fun drawOpticalLayer(layer: Int): Boolean {
        val count = optics.layerVertexCount[layer]
        if (count <= 0 || opticalFloatCount <= 0) return false
        opticalProgram.use()
        GLES30.glBindVertexArray(opticalVaoId)
        if (opticalUniformsDirty) {
            // 帧内全层共享的 uniform 只在本帧首个光学层上传一次。
            opticalUniformsDirty = false
            GLES30.glUniform2f(
                opticalProgram.uniform("uViewportPx"),
                width.toFloat(),
                height.toFloat()
            )
            GLES30.glUniform1f(opticalProgram.uniform("uRotationRad"), rotationRad)
            GLES30.glUniform1f(opticalProgram.uniform("uRasterScale"), 1f)
            GLES30.glUniform1i(opticalProgram.uniform("uSceneLinear"), if (sceneLinear) 1 else 0)
            GLES30.glUniform1f(opticalProgram.uniform("uHdrGain"), hdrGain)
            GLES30.glUniform1f(opticalProgram.uniform("uHdrHeadroom"), hdrHeadroom)
        }
        GLES30.glUniform1f(
            opticalProgram.uniform("uHdrCorePeak"),
            FableSolHdrPolicy.glintCorePeak(layer)
        )
        GLES30.glUniform1f(
            opticalProgram.uniform("uHdrCrestPeak"),
            FableSolHdrPolicy.surfaceReflectionPeak(layer)
        )
        GLES30.glUniform1f(
            opticalProgram.uniform("uHdrTransmissionPeak"),
            FableSolHdrPolicy.transmissionPeak(layer)
        )
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(
            FableSolGlOpticalBlendPolicy.RGB_SOURCE_FACTOR,
            FableSolGlOpticalBlendPolicy.RGB_DESTINATION_FACTOR,
            FableSolGlOpticalBlendPolicy.ALPHA_SOURCE_FACTOR,
            FableSolGlOpticalBlendPolicy.ALPHA_DESTINATION_FACTOR
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, optics.layerFirstVertex[layer], count)
        GLES30.glDisable(GLES30.GL_BLEND)
        return true
    }

    private fun ensureIndexBuffer(columns: Int) {
        if (!indexBufferState.requiresUpload(columns)) return
        val indices = FableSolGlMeshLayout.buildIndices(columns)
        val upload = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
        upload.put(indices).position(0)
        // ELEMENT_ARRAY 绑定属于 VAO 状态；上传前先绑定水体 VAO，避免污染其它 VAO。
        GLES30.glBindVertexArray(waterVaoId)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            indices.size * 2,
            upload,
            GLES30.GL_STATIC_DRAW
        )
        indexBufferState.onUploaded(columns)
    }

    private fun uploadBuffer(id: Int, values: FloatArray, count: Int, upload: FloatBuffer) {
        upload.clear()
        upload.put(values, 0, count)
        upload.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * 4, upload, GLES30.GL_STREAM_DRAW)
    }

    /** 只在 VAO 初始捕获时调用；布局与既有逐帧重设版本完全一致。 */
    private fun specifyWaterVertexLayout(id: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id)
        val stride = FableSolGlMeshLayout.COMPONENTS_PER_VERTEX * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 8)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 16)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 20)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 2, GLES30.GL_FLOAT, false, stride, 24)
    }

    private fun specifyOpticalVertexLayout() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, opticalBufferId)
        val stride = FableSolGlOptics.COMPONENTS_PER_VERTEX * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 8)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 16)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 32)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 3, GLES30.GL_FLOAT, false, stride, 36)
        GLES30.glEnableVertexAttribArray(5)
        GLES30.glVertexAttribPointer(5, 1, GLES30.GL_FLOAT, false, stride, 48)
    }

    private fun uploadEnvironmentUniforms(program: FableSolGlProgram) {
        GLES30.glUniform3fv(program.uniform("uEnvironmentTop"), 1, environmentTop, 0)
        GLES30.glUniform3fv(program.uniform("uEnvironmentHorizon"), 1, environmentHorizon, 0)
        GLES30.glUniform3fv(program.uniform("uEnvironmentBottom"), 1, environmentBottom, 0)
        GLES30.glUniform1i(program.uniform("uSceneLinear"), if (sceneLinear) 1 else 0)
    }

    private fun uploadWaterUniforms() {
        GLES30.glUniform2f(waterProgram.uniform("uViewportPx"), width.toFloat(), height.toFloat())
        GLES30.glUniform1f(waterProgram.uniform("uRotationRad"), rotationRad)
        GLES30.glUniform1f(waterProgram.uniform("uRasterScale"), 1f)
        if (waterMaterialUniformsDirty) {
            uploadWaterMaterialUniforms()
            waterMaterialUniformsDirty = false
        }
        GLES30.glUniform2fv(
            waterProgram.uniform("uGradientOrigin[0]"),
            FableSolSpec.N_LAYERS,
            layerGradientOrigin,
            0
        )
        GLES30.glUniform2fv(
            waterProgram.uniform("uGradientDirection[0]"),
            FableSolSpec.N_LAYERS,
            layerGradientDirection,
            0
        )
        GLES30.glUniform1fv(
            waterProgram.uniform("uGradientDenominator[0]"),
            FableSolSpec.N_LAYERS,
            layerGradientDenominator,
            0
        )
        GLES30.glUniform1f(waterProgram.uniform("uViewElevationRad"), viewElevationRad)
        GLES30.glUniform1f(
            waterProgram.uniform("uLightAzimuthRad"),
            Math.toRadians(params.get("light_azimuth_deg")).toFloat()
        )
        GLES30.glUniform1f(waterProgram.uniform("uTimeSeconds"), sim.t.toFloat())
        GLES30.glUniform1f(
            waterProgram.uniform("uSurfaceHeadingRad"),
            Math.toRadians(params.get("surface_heading_deg")).toFloat()
        )
        GLES30.glUniform1f(
            waterProgram.uniform("uMicroNormalStrength"),
            params.get("micro_normal_strength").toFloat()
        )
        GLES30.glUniform1f(
            waterProgram.uniform("uSpecularAaStrength"),
            params.get("specular_aa_strength").toFloat()
        )
        GLES30.glUniform1f(
            waterProgram.uniform("uSunSssStrength"),
            params.get("sun_sss_strength").toFloat()
        )
        GLES30.glUniform1f(
            waterProgram.uniform("uSunSssFalloff"),
            params.get("sun_sss_falloff").toFloat()
        )
        // Step C：把 HDR 增益与实时 headroom 也喂给水面，用于掠射 Fresnel 超白光泽。
        GLES30.glUniform1f(waterProgram.uniform("uHdrGain"), hdrGain)
        GLES30.glUniform1f(waterProgram.uniform("uHdrHeadroom"), hdrHeadroom)
        // D151/D152 厚度透光（SDR 透光与 HDR 透射 excess 同源）。
        GLES30.glUniform1f(
            waterProgram.uniform("uThicknessGlowStrength"),
            params.get("uplift_thick_glow").toFloat()
        )
        GLES30.glUniform1f(
            waterProgram.uniform("uGlowDerivedBoost"),
            params.get("uplift_glow_boost").toFloat()
        )
        GLES30.glUniform1fv(
            waterProgram.uniform("uLayerMeanYPx[0]"),
            FableSolSpec.N_LAYERS,
            layerMeanYPx,
            0
        )
        GLES30.glUniform1f(
            waterProgram.uniform("uThicknessRangePx"),
            (22.0 * density).toFloat()
        )
        // D156 波峰银边：场强 = 参数 × 音频活跃度（0.30+0.70×sparkle01，
        // 沿用闪点 sparkle 前例、HDR 峰值预算不变）。与 Python gl_renderer
        // 一比一：粗细（dp→物理 px，片元另乘层级空气透视）、光晕幅度、
        // HDR 峰值增量（峰值−1）、滑动（恒速 64dp/s 沿 +x 右滑的逆流视差，
        // 相位为 sim.t 纯函数、对 λ=240dp 取模，冻结时静止）。
        val crestRimActivity = 0.30 + 0.70 * sim.sparkle01.coerceIn(0.0, 1.0)
        GLES30.glUniform1f(
            waterProgram.uniform("uCrestRimStrength"),
            (params.get("uplift_crest_rim") * crestRimActivity).toFloat()
        )
        GLES30.glUniform1f(
            waterProgram.uniform("uCrestRimWidthPx"),
            (params.get("uplift_rim_width") * density).toFloat()
        )
        GLES30.glUniform1f(
            waterProgram.uniform("uCrestRimHaloAmp"),
            params.get("uplift_rim_halo").toFloat()
        )
        GLES30.glUniform1f(
            waterProgram.uniform("uCrestRimPeakBoost"),
            (params.get("uplift_rim_peak") - 1.0).coerceAtLeast(0.0).toFloat()
        )
        // v18：λ=360dp（取模必须等于波长，否则相位回绕跳变）；深度 0.60
        // （段间谷底 0.40，过渡更绵）。与 Python gl_renderer 一比一。
        GLES30.glUniform1f(
            waterProgram.uniform("uCrestRimSlidePhase"),
            ((64.0 * sim.t) % 360.0 * density).toFloat()
        )
        GLES30.glUniform1f(
            waterProgram.uniform("uCrestRimSlideScale"),
            (1.0 / (360.0 * density)).toFloat()
        )
        GLES30.glUniform1f(
            waterProgram.uniform("uCrestRimSlideDepth"),
            (0.60 * params.get("uplift_rim_slide")).toFloat()
        )
        // D156 v17 太阳柱：顶点高亮只在太阳柱内的波峰出现（每层 1~2 处）。
        GLES30.glUniform1f(
            waterProgram.uniform("uCrestRimSpanX0Px"), crestRimX0Px
        )
        GLES30.glUniform1f(
            waterProgram.uniform("uCrestRimSpanPx"), crestRimSpanPx
        )
    }

    /** Thing 色未变化时，逐层材质数组无需每帧重复跨 JNI 上传。 */
    private fun uploadWaterMaterialUniforms() {
        GLES30.glUniform3fv(waterProgram.uniform("uLayerStart[0]"), FableSolSpec.N_LAYERS, layerStart, 0)
        GLES30.glUniform3fv(waterProgram.uniform("uLayerStop1[0]"), FableSolSpec.N_LAYERS, layerStop1, 0)
        GLES30.glUniform3fv(waterProgram.uniform("uLayerStop2[0]"), FableSolSpec.N_LAYERS, layerStop2, 0)
        GLES30.glUniform3fv(waterProgram.uniform("uLayerEnd[0]"), FableSolSpec.N_LAYERS, layerEnd, 0)
        GLES30.glUniform3fv(
            waterProgram.uniform("uLayerSubsurfaceStart[0]"),
            FableSolSpec.N_LAYERS,
            layerSubsurfaceStart,
            0
        )
        GLES30.glUniform3fv(
            waterProgram.uniform("uLayerSubsurfaceStop1[0]"),
            FableSolSpec.N_LAYERS,
            layerSubsurfaceStop1,
            0
        )
        GLES30.glUniform3fv(
            waterProgram.uniform("uLayerSubsurfaceStop2[0]"),
            FableSolSpec.N_LAYERS,
            layerSubsurfaceStop2,
            0
        )
        GLES30.glUniform3fv(
            waterProgram.uniform("uLayerSubsurfaceEnd[0]"),
            FableSolSpec.N_LAYERS,
            layerSubsurfaceEnd,
            0
        )
        GLES30.glUniform1fv(waterProgram.uniform("uLayerAlpha[0]"), FableSolSpec.N_LAYERS, layerAlpha, 0)
        uploadEnvironmentUniforms(waterProgram)
    }

    /** 程序链接后不再变化的逐层曲线只上传一次，避免每帧重复 JNI/driver 调用。 */
    private fun uploadStaticWaterUniforms() {
        GLES30.glUniform1fv(
            waterProgram.uniform("uMacroLightWeights[0]"),
            FableSolSpec.N_LAYERS,
            FableSolMaterialPolicy.MACRO_LIGHT_WEIGHTS,
            0
        )
        GLES30.glUniform1fv(
            waterProgram.uniform("uMacroShadowWeights[0]"),
            FableSolSpec.N_LAYERS,
            FableSolMaterialPolicy.MACRO_SHADOW_WEIGHTS,
            0
        )
        GLES30.glUniform1fv(
            waterProgram.uniform("uMicroNormalWeights[0]"),
            FableSolSpec.N_LAYERS,
            FableSolMaterialPolicy.MICRO_NORMAL_WEIGHTS,
            0
        )
        GLES30.glUniform1fv(
            waterProgram.uniform("uSdrSssWeights[0]"),
            FableSolSpec.N_LAYERS,
            FableSolMaterialPolicy.SDR_SSS_WEIGHTS,
            0
        )
        // D154：厚度透光独立权重表；shader 在未上传时回退 SDR_SSS。
        GLES30.glUniform1fv(
            waterProgram.uniform("uThicknessGlowWeights[0]"),
            FableSolSpec.N_LAYERS,
            FableSolMaterialPolicy.THICKNESS_GLOW_WEIGHTS,
            0
        )
        // D156 波峰银边逐层存在度。
        GLES30.glUniform1fv(
            waterProgram.uniform("uCrestRimWeights[0]"),
            FableSolSpec.N_LAYERS,
            FableSolMaterialPolicy.CREST_RIM_WEIGHTS,
            0
        )
        GLES30.glUniform1fv(
            waterProgram.uniform("uHdrTransmissionPeaks[0]"),
            FableSolSpec.N_LAYERS,
            FableSolHdrPolicy.CONTINUOUS_TRANSMISSION_PEAKS,
            0
        )
    }

    private fun putSubsurfaceColor(layer: Int, palette: FableSolDepthScatteringPolicy.Palette,
                                   subsurfaceTarget: FloatArray) {
        putColor(subsurfaceTarget, layer, palette.subsurface)
    }

    private fun putColor(target: FloatArray, index: Int, color: IntArray) {
        val offset = index * 3
        target[offset] = color[0] / 255f
        target[offset + 1] = color[1] / 255f
        target[offset + 2] = color[2] / 255f
    }

    private fun putColor(target: FloatArray, color: IntArray) {
        target[0] = color[0] / 255f
        target[1] = color[1] / 255f
        target[2] = color[2] / 255f
    }

    private fun lerp(a: Double, b: Double, fraction: Double): Double = a + (b - a) * fraction

    private fun checkGl(operation: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) {
            "$operation failed: GL 0x${Integer.toHexString(error)}"
        }
    }

    private companion object {
        const val TARGET_FRAME_SECONDS = 1.0 / 60.0
        const val MAX_DT_SECONDS = 0.05
        // 颜色过渡：总时长与揭示软带宽（新色从右缘推进到左缘）。
        const val COLOR_TRANSITION_MS = 1600f
        const val COLOR_REVEAL_SOFT_DP = 72.0
        const val IDLE_SILENCE_MS = 200L
        const val MAX_PENDING_EVENTS = 128
        const val FILL_EXTRA_DP = 80.0
        const val GL_ERROR_CHECK_INTERVAL_FRAMES = 129
        const val TARGET_MSAA_SAMPLES = 4
        const val PREPARED_PRESENTATION_ALPHA = 0.16f
        val WHITE = intArrayOf(255, 255, 255)
    }
}

/** 光学 shader 输出预乘 RGB；覆盖只混合 RGB，保持环境/水面 framebuffer alpha。 */
internal object FableSolGlOpticalBlendPolicy {
    const val RGB_SOURCE_FACTOR = GLES30.GL_ONE
    const val RGB_DESTINATION_FACTOR = GLES30.GL_ONE_MINUS_SRC_ALPHA
    const val ALPHA_SOURCE_FACTOR = GLES30.GL_ZERO
    const val ALPHA_DESTINATION_FACTOR = GLES30.GL_ONE

    fun resultingAlpha(destinationAlpha: Double): Double = destinationAlpha
}
