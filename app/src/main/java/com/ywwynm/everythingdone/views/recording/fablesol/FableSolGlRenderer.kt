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

    private val assets = context.assets
    private val params = FableSolParams()
    private val sim = FableSolSimulation(params)
    private val mapper = FableSolFeatureMapper(params)
    private val inputLock = Any()
    private var pendingFrames = ArrayList<FableSolFeatureFrame>()
    private var pendingEvents = ArrayList<FableSolEvent>()
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
    private var sceneFramebufferId = 0
    private var sceneTextureId = 0
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
    private val sourceFraction = DoubleArray(FableSolSpec.N_POINTS)
    private val layerMeans = DoubleArray(FableSolSpec.N_LAYERS)
    private val sheenSlopeX = FloatArray(FableSolContinuousSurface.Z_ROWS * FableSolSpec.N_POINTS)
    private val sheenSlopeZ = FloatArray(FableSolContinuousSurface.Z_ROWS * FableSolSpec.N_POINTS)
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
    private val layerDeepStart = FloatArray(FableSolSpec.N_LAYERS * 3)
    private val layerDeepStop1 = FloatArray(FableSolSpec.N_LAYERS * 3)
    private val layerDeepStop2 = FloatArray(FableSolSpec.N_LAYERS * 3)
    private val layerDeepEnd = FloatArray(FableSolSpec.N_LAYERS * 3)
    private val layerSubsurfaceStart = FloatArray(FableSolSpec.N_LAYERS * 3)
    private val layerSubsurfaceStop1 = FloatArray(FableSolSpec.N_LAYERS * 3)
    private val layerSubsurfaceStop2 = FloatArray(FableSolSpec.N_LAYERS * 3)
    private val layerSubsurfaceEnd = FloatArray(FableSolSpec.N_LAYERS * 3)
    private val layerStartRgb = Array(FableSolSpec.N_LAYERS) { IntArray(3) }
    private val layerEndRgb = Array(FableSolSpec.N_LAYERS) { IntArray(3) }
    private val layerAlpha = FloatArray(FableSolSpec.N_LAYERS)
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

    fun initialize(hdrOutput: Boolean) {
        sceneLinear = hdrOutput
        hdrContentEnabled = hdrOutput
        hdrGain = 0f
        hdrHeadroom = 1f
        hdrTransition.reset()
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
        val buffers = IntArray(4)
        GLES30.glGenBuffers(4, buffers, 0)
        vertexBufferId = buffers[0]
        frontBufferId = buffers[1]
        indexBufferId = buffers[2]
        opticalBufferId = buffers[3]
        val arrays = IntArray(1)
        GLES30.glGenVertexArrays(1, arrays, 0)
        vertexArrayId = arrays[0]
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
        ensureSceneTarget(this.width, this.height)
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

    fun render(frameTimeNanos: Long): Timing {
        val drainStart = SystemClock.elapsedRealtimeNanos()
        val now = SystemClock.elapsedRealtime()
        drainAndApply(now)
        applyLatestGravity()
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
        sim.update(boundedDt)
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
        if (vertexArrayId != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArrayId), 0)
        releaseSceneTarget()
        vertexBufferId = 0
        frontBufferId = 0
        indexBufferId = 0
        opticalBufferId = 0
        vertexArrayId = 0
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
        synchronized(inputLock) {
            frames = if (pendingFrames.isEmpty()) null else pendingFrames
            pendingFrames = ArrayList()
            events = pendingEvents
            pendingEvents = ArrayList()
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
        val viewBase = params.get("surface_view_elev_deg")
        val viewElevation = FableSolPitchPolicy.viewElevationDeg(sim.pitchDeg, viewBase)
        val depthScale = sin(Math.toRadians(viewElevation)) /
            max(sin(Math.toRadians(viewBase)), 0.2)
        rotationRad = info.thetaRad.toFloat()
        viewElevationRad = Math.toRadians(viewElevation).toFloat()

        for (column in 0 until columns) {
            val source = sim.continuousRenderSourcePosition(info.i0, rawColumns, columns, column)
            val index = min(source.toInt(), info.i1 - 2)
            sourceIndex[column] = index
            sourceFraction[column] = (source - index).coerceIn(0.0, 1.0)
        }

        var cursor = 0
        for (row in 0 until FableSolContinuousSurface.Z_ROWS) {
            for (column in 0 until columns) {
                val index = sourceIndex[column]
                val fraction = sourceFraction[column]
                val orbitZ = lerp(sample.orbitZ[row][index], sample.orbitZ[row][index + 1], fraction)
                val orbitX = lerp(sample.orbitX[row][index], sample.orbitX[row][index + 1], fraction)
                val worldEta = lerp(sample.worldEta[row][index], sample.worldEta[row][index + 1], fraction)
                val uDp = lerp(sim.uGrid[index], sim.uGrid[index + 1], fraction)
                val z01 = ((sample.zDp[row] + orbitZ) / max(sample.depthDp, 1e-6))
                    .coerceIn(-0.08, 1.08)
                val layerPosition = z01.coerceIn(0.0, 1.0) * (FableSolSpec.N_LAYERS - 1)
                val baseLayer = min(layerPosition.toInt(), FableSolSpec.N_LAYERS - 2)
                val layerFraction = layerPosition - baseLayer
                var baseHeight = lerp(
                    layerMeans[baseLayer],
                    layerMeans[baseLayer + 1],
                    layerFraction
                )
                baseHeight = layerMeans[0] + (baseHeight - layerMeans[0]) * depthScale
                val perspective = 1.0 / (1.0 + 0.16 * z01.coerceIn(0.0, 1.1))
                val slopeX = lerp(
                    sample.slopeX[row][index], sample.slopeX[row][index + 1], fraction
                ).toFloat()
                val slopeZ = lerp(
                    sample.slopeZ[row][index], sample.slopeZ[row][index + 1], fraction
                ).toFloat()
                val gridIndex = row * columns + column
                vertexData[cursor++] = ((uDp + orbitX) * density * perspective).toFloat()
                vertexData[cursor++] = ((info.hG / 2.0 - (baseHeight + worldEta)) * density).toFloat()
                vertexData[cursor++] = slopeX
                vertexData[cursor++] = slopeZ
                vertexData[cursor++] =
                    (row.toDouble() / (FableSolContinuousSurface.Z_ROWS - 1)).toFloat()
                val orbitDerivative = lerp(
                    orbitXDerivative(sample, row, index),
                    orbitXDerivative(sample, row, index + 1),
                    fraction
                )
                vertexData[cursor++] =
                    FableSolDepthScatteringPolicy.crestPinch(orbitDerivative).toFloat()
                sheenSlopeX[gridIndex] = slopeX
                sheenSlopeZ[gridIndex] = slopeZ
                vertexData[cursor++] = slopeX
                vertexData[cursor++] = slopeZ
            }
        }
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
        vertexFloatCount = cursor

        val fillBottom = (info.hG / 2.0 + FILL_EXTRA_DP) * density
        cursor = 0
        for (column in 0 until columns) {
            val sourceOffset = column * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
            frontData[cursor++] = vertexData[sourceOffset]
            frontData[cursor++] = vertexData[sourceOffset + 1]
            frontData[cursor++] = 0f
            frontData[cursor++] = 0f
            frontData[cursor++] = 0f
            frontData[cursor++] = vertexData[sourceOffset + 5]
            frontData[cursor++] = 0f
            frontData[cursor++] = 0f
            frontData[cursor++] = vertexData[sourceOffset]
            frontData[cursor++] = fillBottom.toFloat()
            frontData[cursor++] = 0f
            frontData[cursor++] = 0f
            frontData[cursor++] = 0f
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
            sourceFraction
        )
    }

    private fun buildColors(fillBottom: Double) {
        val snapshot = background
        val base = FableSolLayerColorPolicy.baseColors(
            FableSolColor.fromColor(snapshot.color),
            if (snapshot.gradient) FableSolColor.fromColor(snapshot.endColor) else null
        )
        // deep/subsurface 从未混白的身份色派生：deep 只供受控背坡阴影，subsurface 只供日出 SSS；
        // 二者都不再参与整层深度散射混色。
        val baseScatterStop1 = FableSolColor.mixOklab(base.start, base.end, 0.21)
        val baseScatterStop2 = FableSolColor.mixOklab(base.start, base.end, 0.56)
        for (layer in 0 until FableSolSpec.N_LAYERS) {
            val depth = layer.toDouble() / (FableSolSpec.N_LAYERS - 1)
            val breath = params.get("color_breath") *
                (0.30 * (sim.colorBright01 - 0.45) + 0.18 * (sim.colorEnergy01 - 0.5))
            val lighten = FableSolLayerColorPolicy.lightenAmount(
                depth, params.get("lighten_far"), sim.moodBright, breath
            )
            val start = FableSolColor.mixOklab(base.start, WHITE, lighten)
            val end = FableSolColor.mixOklab(base.end, WHITE, lighten)
            val stop1 = FableSolColor.mixOklab(start, end, 0.21)
            val stop2 = FableSolColor.mixOklab(start, end, 0.56)
            for (channel in 0 until 3) {
                layerStartRgb[layer][channel] = start[channel]
                layerEndRgb[layer][channel] = end[channel]
            }
            putColor(layerStart, layer, start)
            putColor(layerStop1, layer, stop1)
            putColor(layerStop2, layer, stop2)
            putColor(layerEnd, layer, end)
            putScatteringColors(layer, base.start, layerDeepStart, layerSubsurfaceStart)
            putScatteringColors(layer, baseScatterStop1, layerDeepStop1, layerSubsurfaceStop1)
            putScatteringColors(layer, baseScatterStop2, layerDeepStop2, layerSubsurfaceStop2)
            putScatteringColors(layer, base.end, layerDeepEnd, layerSubsurfaceEnd)
            layerAlpha[layer] = params.lget("alpha", layer).toFloat()
            buildLayerGradientGeometry(layer, fillBottom, snapshot)
        }
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
        check(sceneFramebufferId != 0 && sceneTextureId != 0) { "Scene target is unavailable" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFramebufferId)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glBindVertexArray(vertexArrayId)

        environmentProgram.use()
        uploadEnvironmentUniforms(environmentProgram)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        if (vertexFloatCount > 0) {
            ensureIndexBuffer(columns)
            uploadBuffer(vertexBufferId, vertexData, vertexFloatCount, vertexUpload)
            uploadBuffer(frontBufferId, frontData, frontFloatCount, frontUpload)
            if (opticalFloatCount > 0) {
                uploadBuffer(opticalBufferId, optics.vertices, opticalFloatCount, opticalUpload)
            }
            waterProgram.use()
            uploadWaterUniforms()
            bindWaterVertexLayout(vertexBufferId)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)
            GLES30.glUniform1i(waterProgram.uniform("uFrontFill"), 0)
            for (group in 0 until FableSolGlMeshLayout.GROUP_COUNT) {
                val layer = 8 - group
                waterProgram.use()
                bindWaterVertexLayout(vertexBufferId)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)
                GLES30.glUniform1i(waterProgram.uniform("uStartLayer"), layer)
                GLES30.glDrawElements(
                    GLES30.GL_TRIANGLES,
                    indexBufferState.indexCountPerGroup,
                    GLES30.GL_UNSIGNED_SHORT,
                    group * indexBufferState.indexCountPerGroup * 2
                )
                drawOpticalLayer(layer)
            }

            waterProgram.use()
            bindWaterVertexLayout(frontBufferId)
            GLES30.glUniform1i(waterProgram.uniform("uFrontFill"), 1)
            GLES30.glUniform1i(waterProgram.uniform("uStartLayer"), 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, columns * 2)
            drawOpticalLayer(0)
        }
        presentScene()
        checkGl("drawFrame")
    }

    private fun ensureSceneTarget(width: Int, height: Int) {
        if (sceneFramebufferId != 0 && sceneTextureId != 0 &&
            sceneTargetWidth == width && sceneTargetHeight == height
        ) return
        releaseSceneTarget()

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        sceneTextureId = textures[0]
        check(sceneTextureId != 0) { "glGenTextures failed for scene target" }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val internalFormat = if (hdrContentEnabled) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
        val componentType = if (hdrContentEnabled) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE
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

        val framebuffers = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffers, 0)
        sceneFramebufferId = framebuffers[0]
        check(sceneFramebufferId != 0) { "glGenFramebuffers failed for scene target" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFramebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            sceneTextureId,
            0
        )
        val framebufferStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (framebufferStatus != GLES30.GL_FRAMEBUFFER_COMPLETE && hdrContentEnabled) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            releaseSceneTarget()
            hdrContentEnabled = false
            ensureSceneTarget(width, height)
            return
        }
        check(framebufferStatus == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Scene framebuffer is incomplete" }
        sceneTargetWidth = width
        sceneTargetHeight = height
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        checkGl("ensureSceneTarget")
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
        GLES30.glUniform1i(
            presentationProgram.uniform("uSceneLinear"),
            if (sceneLinear) 1 else 0
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun releaseSceneTarget() {
        if (sceneFramebufferId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(sceneFramebufferId), 0)
        }
        if (sceneTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(sceneTextureId), 0)
        sceneFramebufferId = 0
        sceneTextureId = 0
        sceneTargetWidth = 0
        sceneTargetHeight = 0
    }

    private fun drawOpticalLayer(layer: Int) {
        val count = optics.layerVertexCount[layer]
        if (count <= 0 || opticalFloatCount <= 0) return
        opticalProgram.use()
        bindOpticalVertexLayout()
        GLES30.glUniform2f(opticalProgram.uniform("uViewportPx"), width.toFloat(), height.toFloat())
        GLES30.glUniform1f(opticalProgram.uniform("uRotationRad"), rotationRad)
        GLES30.glUniform1i(opticalProgram.uniform("uSceneLinear"), if (sceneLinear) 1 else 0)
        GLES30.glUniform1f(opticalProgram.uniform("uHdrGain"), hdrGain)
        GLES30.glUniform1f(opticalProgram.uniform("uHdrHeadroom"), hdrHeadroom)
        GLES30.glUniform1f(
            opticalProgram.uniform("uHdrCorePeak"),
            FableSolHdrPolicy.glintCorePeak(layer)
        )
        GLES30.glUniform1f(
            opticalProgram.uniform("uHdrCrestPeak"),
            FableSolHdrPolicy.litCrestPeak(layer)
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
    }

    private fun ensureIndexBuffer(columns: Int) {
        if (!indexBufferState.requiresUpload(columns)) return
        val indices = FableSolGlMeshLayout.buildIndices(columns)
        val upload = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
        upload.put(indices).position(0)
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

    private fun bindWaterVertexLayout(id: Int) {
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

    private fun bindOpticalVertexLayout() {
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
        GLES30.glUniform3fv(waterProgram.uniform("uLayerStart[0]"), FableSolSpec.N_LAYERS, layerStart, 0)
        GLES30.glUniform3fv(waterProgram.uniform("uLayerStop1[0]"), FableSolSpec.N_LAYERS, layerStop1, 0)
        GLES30.glUniform3fv(waterProgram.uniform("uLayerStop2[0]"), FableSolSpec.N_LAYERS, layerStop2, 0)
        GLES30.glUniform3fv(waterProgram.uniform("uLayerEnd[0]"), FableSolSpec.N_LAYERS, layerEnd, 0)
        GLES30.glUniform3fv(
            waterProgram.uniform("uLayerDeepStart[0]"),
            FableSolSpec.N_LAYERS,
            layerDeepStart,
            0
        )
        GLES30.glUniform3fv(
            waterProgram.uniform("uLayerDeepStop1[0]"),
            FableSolSpec.N_LAYERS,
            layerDeepStop1,
            0
        )
        GLES30.glUniform3fv(
            waterProgram.uniform("uLayerDeepStop2[0]"),
            FableSolSpec.N_LAYERS,
            layerDeepStop2,
            0
        )
        GLES30.glUniform3fv(
            waterProgram.uniform("uLayerDeepEnd[0]"),
            FableSolSpec.N_LAYERS,
            layerDeepEnd,
            0
        )
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
        uploadEnvironmentUniforms(waterProgram)
        GLES30.glUniform1f(waterProgram.uniform("uViewElevationRad"), viewElevationRad)
        GLES30.glUniform1f(
            waterProgram.uniform("uLightAzimuthRad"),
            Math.toRadians(params.get("light_azimuth_deg")).toFloat()
        )
        GLES30.glUniform1f(
            waterProgram.uniform("uMacroShadowLumaCap"),
            params.get("macro_shadow_luma_cap").toFloat()
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
        GLES30.glUniform1f(
            waterProgram.uniform("uHdrTransmissionPeak"),
            FableSolHdrPolicy.WATER_TRANSMISSION_PEAK
        )
    }

    private fun putScatteringColors(layer: Int, base: IntArray,
                                    deepTarget: FloatArray, subsurfaceTarget: FloatArray) {
        val palette = FableSolDepthScatteringPolicy.derive(base)
        putColor(deepTarget, layer, palette.deep)
        putColor(subsurfaceTarget, layer, palette.subsurface)
    }

    private fun orbitXDerivative(sample: FableSolContinuousSurface.Sample,
                                 row: Int, index: Int): Double {
        val previous = max(index - 1, 0)
        val next = min(index + 1, FableSolSpec.N_POINTS - 1)
        return (sample.orbitX[row][next] - sample.orbitX[row][previous]) /
            max((next - previous) * FableSolSpec.DX_DP, 1e-6)
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
        const val IDLE_SILENCE_MS = 200L
        const val MAX_PENDING_EVENTS = 128
        const val FILL_EXTRA_DP = 80.0
        const val PREPARED_PRESENTATION_ALPHA = 0.16f
        val WHITE = intArrayOf(255, 255, 255)
    }
}

/** 光学覆盖只混合 RGB，保持已经不透明的环境/水面 framebuffer alpha。 */
internal object FableSolGlOpticalBlendPolicy {
    const val RGB_SOURCE_FACTOR = GLES30.GL_SRC_ALPHA
    const val RGB_DESTINATION_FACTOR = GLES30.GL_ONE_MINUS_SRC_ALPHA
    const val ALPHA_SOURCE_FACTOR = GLES30.GL_ZERO
    const val ALPHA_DESTINATION_FACTOR = GLES30.GL_ONE

    fun resultingAlpha(destinationAlpha: Double): Double = destinationAlpha
}
