package com.ywwynm.everythingdone.spatial

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.GLSurfaceView
import android.util.Log
import com.ywwynm.everythingdone.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

internal class SpatialPhotoRenderer : GLSurfaceView.Renderer {

    @Volatile
    private var pendingScene: Scene? = null

    @Volatile
    private var sceneDirty = false

    @Volatile
    private var offsetX = 0f

    @Volatile
    private var offsetY = 0f

    @Volatile
    private var strength = 0.5f

    @Volatile
    private var renderMode = SpatialRenderMode.SINGLE_LAYER

    private var program = 0
    private var ldiProgram = 0
    private var chartProgram = 0
    private var chartCompositeProgram = 0
    private var surfelProgram = 0
    private var surfelCompositeProgram = 0
    private var colorTexture = 0
    private var backgroundTexture = 0
    private var surfaceAlphaTexture = 0
    private var depthTexture = 0
    private var renderDepthTexture = 0
    private var motionBasisTexture = 0
    private var chartWeightTexture = 0
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var imageWidth = 1
    private var imageHeight = 1
    private var depthWidth = 1
    private var depthHeight = 1
    private var surfaceAlphaWidth = 1
    private var surfaceAlphaHeight = 1
    private var warpProfile: SpatialWarpBudget.Profile? = null
    private var ldiGradientProfile: SpatialWarpBudget.Profile? = null
    private var ldiMesh: LdiMesh? = null
    private var chartRenderData: SpatialSurfaceChartRenderData? = null
    private var chartVertices: FloatBuffer? = null
    private var chartVertexCount = 0
    private var chartTargets: ChartTargets? = null
    private var chartFloatType = 0
    private var surfelVertices: FloatBuffer? = null
    private var surfelVertexCount = 0
    private var surfelTargets: SurfelTargets? = null
    private var meshTargets: SurfelTargets? = null
    private var meshResolveProgram = 0
    private var maximumPointSize = 1f
    private var activeLdiRenderer = SpatialLdiRenderer.LEGACY_V19
    private var activeViewEnvelope: SpatialViewEnvelope? = null

    @Volatile
    private var mpiPlanes: List<SpatialMpiPlane>? = null
    private var mpiProgram = 0
    private var mpiTextures: IntArray? = null
    private var mpiUploadedFor: List<SpatialMpiPlane>? = null
    private var mpiDrawNanos = 0L
    private var mpiDrawCount = 0

    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(VERTICES.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(VERTICES)
            position(0)
        }

    fun setScene(
        bitmap: Bitmap,
        depth: SpatialDepthData,
        initialStrength: Float,
        ldiLite: SpatialLdiLiteData? = null,
        initialRenderMode: SpatialRenderMode = SpatialRenderMode.resolve(
            value = null,
            hasLdiLite = ldiLite != null
        )
    ) {
        pendingScene = Scene(bitmap, depth, ldiLite)
        renderMode = SpatialRenderMode.resolve(
            value = initialRenderMode.stableId,
            hasLdiLite = ldiLite != null
        )
        strength = initialStrength.coerceIn(
            SpatialDerivativeStore.MIN_STRENGTH,
            SpatialDerivativeStore.MAX_STRENGTH
        )
        sceneDirty = true
    }

    fun setViewpoint(x: Float, y: Float) {
        offsetX = x.coerceIn(-1f, 1f)
        offsetY = y.coerceIn(-1f, 1f)
    }

    fun setStrength(value: Float) {
        strength = value.coerceIn(
            SpatialDerivativeStore.MIN_STRENGTH,
            SpatialDerivativeStore.MAX_STRENGTH
        )
    }

    fun setRenderMode(mode: SpatialRenderMode) {
        renderMode = SpatialRenderMode.resolve(
            value = mode.stableId,
            hasLdiLite = pendingScene?.ldiLite != null
        )
    }

    /** MPI 平面由 [SpatialPhotoView] 在后台线程构建后注入；null 表示清空。 */
    fun setMpiPlanes(planes: List<SpatialMpiPlane>?) {
        mpiPlanes = planes
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        ldiProgram = createProgram(LDI_VERTEX_SHADER, LDI_FRAGMENT_SHADER)
        chartProgram = createProgram(CHART_VERTEX_SHADER, CHART_FRAGMENT_SHADER)
        chartCompositeProgram = createProgram(
            CHART_COMPOSITE_VERTEX_SHADER,
            CHART_COMPOSITE_FRAGMENT_SHADER
        )
        surfelProgram = createOptionalProgram(
            SURFEL_VERTEX_SHADER,
            SURFEL_FRAGMENT_SHADER,
            "连续深度微表面"
        )
        surfelCompositeProgram = createOptionalProgram(
            CHART_COMPOSITE_VERTEX_SHADER,
            SURFEL_COMPOSITE_FRAGMENT_SHADER,
            "连续深度微表面合成"
        )
        meshResolveProgram = createOptionalProgram(
            CHART_COMPOSITE_VERTEX_SHADER,
            MESH_RESOLVE_FRAGMENT_SHADER,
            "网格超采样分辨与窄缝闭合"
        )
        mpiProgram = createProgram(MPI_VERTEX_SHADER, MPI_FRAGMENT_SHADER)
        chartFloatType = resolveChartFloatType(
            GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        )
        val pointSizeRange = FloatArray(2)
        GLES20.glGetFloatv(GLES20.GL_ALIASED_POINT_SIZE_RANGE, pointSizeRange, 0)
        maximumPointSize = pointSizeRange[1].coerceAtLeast(1f)
        // GL 上下文重建后旧纹理全部失效，MPI 平面按需重新上传。
        mpiTextures = null
        mpiUploadedFor = null
        chartWeightTexture = 0
        chartTargets = null
        surfelTargets = null
        meshTargets = null
        sceneDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        releaseChartTargets()
        releaseSurfelTargets()
        releaseMeshTargets()
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (sceneDirty) uploadPendingScene()
        // P1（design-2026-08-03）：取景边距为每档强度常量，参考视点与偏移视点共用
        // 同一路径——原「回中零裁切直通」会在过中心时产生缩放突跳。
        if (renderMode == SpatialRenderMode.MPI && mpiProgram != 0) {
            ensureMpiTextures()
            val textures = mpiTextures
            if (textures != null && textures.isNotEmpty()) {
                drawMpi(textures)
                return
            }
            // 平面尚未构建/上传完成时先按双层显示，避免黑屏等待。
        }
        val layeredSceneReady =
            ldiProgram != 0 && backgroundTexture != 0 && ldiMesh != null
        if (
            activeLdiRenderer.usesDepthSurfels &&
            layeredSceneReady
        ) {
            if (!drawDepthSurfels()) drawStaticSourceFallback()
            return
        }
        if (
            activeLdiRenderer.usesNormalizedSurfaceCharts &&
            layeredSceneReady
        ) {
            if (!drawNormalizedSurfaceCharts()) drawStaticSourceFallback()
            return
        }
        if (
            SpatialRenderPath.resolve(
                mode = renderMode,
                renderer = activeLdiRenderer,
                hasLayeredScene = layeredSceneReady
            ) == SpatialRenderPath.LAYERED_SCENE
        ) {
            drawLdiLite()
            return
        }
        if (
            program == 0 ||
            colorTexture == 0 ||
            depthTexture == 0 ||
            renderDepthTexture == 0
        ) return

        GLES20.glUseProgram(program)
        vertices.position(0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(
            position,
            2,
            GLES20.GL_FLOAT,
            false,
            STRIDE_BYTES,
            vertices
        )
        vertices.position(2)
        val textureCoordinate = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(textureCoordinate)
        GLES20.glVertexAttribPointer(
            textureCoordinate,
            2,
            GLES20.GL_FLOAT,
            false,
            STRIDE_BYTES,
            vertices
        )

        val viewAspect = surfaceWidth.toFloat() / surfaceHeight
        val imageAspect = imageWidth.toFloat() / imageHeight
        val scaleX: Float
        val scaleY: Float
        if (imageAspect > viewAspect) {
            scaleX = 1f
            scaleY = viewAspect / imageAspect
        } else {
            scaleX = imageAspect / viewAspect
            scaleY = 1f
        }
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uScale"), scaleX, scaleY)
        val amplitude =
            activeViewEnvelope
                ?.takeIf {
                    activeLdiRenderer.usesGlobalInverseWarp && motionBasisTexture != 0
                }
                ?.motion(offsetX, offsetY, strength)
        val requestedAmplitude = amplitude?.amplitude ?: (
            SpatialRenderDepthStabilizer.MIN_PARALLAX_AMPLITUDE +
                (
                    SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE -
                        SpatialRenderDepthStabilizer.MIN_PARALLAX_AMPLITUDE
                    ) * strength
            )
        val safeMotion = amplitude?.let {
            SpatialWarpBudget.Motion(it.x, it.y, 1f)
        } ?: safeParallaxMotion(requestedAmplitude, warpProfile)
        val maximumAmplitude = if (amplitude != null) {
            activeViewEnvelope?.maximumMotionAmplitude(strength) ?: requestedAmplitude
        } else {
            requestedAmplitude
        }
        val coverMargin = SpatialSourceLock.coverMargin(maximumAmplitude)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uCoverMargin"),
            coverMargin.x,
            coverMargin.y
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uViewpoint"),
            offsetX,
            offsetY
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uParallaxMotion"),
            safeMotion.x,
            safeMotion.y
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uUseMotionBasis"),
            if (amplitude != null) 1f else 0f
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uRigidPan"),
            SpatialRenderDepthStabilizer.RIGID_PAN_AMPLITUDE
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uStrength"), strength)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uReliefGain"),
            SpatialRenderDepthStabilizer.RELIEF_GAIN
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uMaxRelief"),
            SpatialRenderDepthStabilizer.MAX_RELIEF
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uDepthTexel"),
            1f / depthWidth.coerceAtLeast(1),
            1f / depthHeight.coerceAtLeast(1)
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uColor"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uDepth"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, renderDepthTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uRenderDepth"), 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionBasisTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uMotionBasis"), 3)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(textureCoordinate)
    }

    fun release() {
        if (colorTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(colorTexture), 0)
        if (backgroundTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(backgroundTexture), 0)
        }
        if (surfaceAlphaTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(surfaceAlphaTexture), 0)
        }
        if (depthTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(depthTexture), 0)
        if (renderDepthTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(renderDepthTexture), 0)
        }
        if (motionBasisTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(motionBasisTexture), 0)
        }
        if (chartWeightTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(chartWeightTexture), 0)
        }
        chartWeightTexture = 0
        chartRenderData = null
        chartVertices = null
        chartVertexCount = 0
        if (chartWeightTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(chartWeightTexture), 0)
        }
        releaseChartTargets()
        releaseSurfelTargets()
        releaseMeshTargets()
        mpiTextures?.let { GLES20.glDeleteTextures(it.size, it, 0) }
        if (program != 0) GLES20.glDeleteProgram(program)
        if (ldiProgram != 0) GLES20.glDeleteProgram(ldiProgram)
        if (chartProgram != 0) GLES20.glDeleteProgram(chartProgram)
        if (chartCompositeProgram != 0) GLES20.glDeleteProgram(chartCompositeProgram)
        if (surfelProgram != 0) GLES20.glDeleteProgram(surfelProgram)
        if (surfelCompositeProgram != 0) GLES20.glDeleteProgram(surfelCompositeProgram)
        if (meshResolveProgram != 0) GLES20.glDeleteProgram(meshResolveProgram)
        if (mpiProgram != 0) GLES20.glDeleteProgram(mpiProgram)
        colorTexture = 0
        backgroundTexture = 0
        surfaceAlphaTexture = 0
        depthTexture = 0
        renderDepthTexture = 0
        motionBasisTexture = 0
        chartWeightTexture = 0
        program = 0
        ldiProgram = 0
        chartProgram = 0
        chartCompositeProgram = 0
        surfelProgram = 0
        surfelCompositeProgram = 0
        meshResolveProgram = 0
        mpiProgram = 0
        mpiTextures = null
        mpiUploadedFor = null
        warpProfile = null
        ldiGradientProfile = null
        ldiMesh = null
        chartRenderData = null
        chartVertices = null
        chartVertexCount = 0
        surfelVertices = null
        surfelVertexCount = 0
        maximumPointSize = 1f
        chartFloatType = 0
        activeLdiRenderer = SpatialLdiRenderer.LEGACY_V19
        activeViewEnvelope = null
    }

    private fun uploadPendingScene() {
        sceneDirty = false
        val scene = pendingScene ?: return
        imageWidth = scene.bitmap.width
        imageHeight = scene.bitmap.height
        depthWidth = scene.depth.width
        depthHeight = scene.depth.height

        if (colorTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(colorTexture), 0)
        if (backgroundTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(backgroundTexture), 0)
        }
        if (surfaceAlphaTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(surfaceAlphaTexture), 0)
        }
        if (depthTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(depthTexture), 0)
        if (renderDepthTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(renderDepthTexture), 0)
        }
        if (motionBasisTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(motionBasisTexture), 0)
        }
        if (chartWeightTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(chartWeightTexture), 0)
        }
        chartWeightTexture = 0
        chartRenderData = null
        chartVertices = null
        chartVertexCount = 0
        surfelVertices = null
        surfelVertexCount = 0
        colorTexture = createTexture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, scene.bitmap, 0)

        val ldiLite = scene.ldiLite
        if (ldiLite != null) {
            activeLdiRenderer = ldiLite.renderer
            activeViewEnvelope = ldiLite.viewEnvelope
            motionBasisTexture = ldiLite.geometry.motionBasis?.let {
                createMotionBasisTexture(it)
            } ?: 0
            backgroundTexture = createTexture()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backgroundTexture)
            GLUtils.texImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                ldiLite.backgroundBitmap,
                0
            )
            surfaceAlphaTexture = ldiLite.displayAlpha?.let { alpha ->
                surfaceAlphaWidth = ldiLite.backgroundBitmap.width
                surfaceAlphaHeight = ldiLite.backgroundBitmap.height
                createLuminanceTexture(
                    values = SpatialAlphaEdgeRefiner.refine(
                        source = alpha,
                        width = surfaceAlphaWidth,
                        height = surfaceAlphaHeight
                    ),
                    width = surfaceAlphaWidth,
                    height = surfaceAlphaHeight
                )
            } ?: run {
                surfaceAlphaWidth = 1
                surfaceAlphaHeight = 1
                0
            }
            // Splat 足迹会覆盖连续表面样本之间的最坏相对位移，因此不能再把单纯的陡梯度
            // 升格成遮挡断边。这里只消费几何阶段确认的拓扑断边；否则主体
            // 内部的深度噪声会被错误切开并露出补图。
            val renderGeometry = ldiLite.geometry
            ldiMesh = buildLdiMesh(
                geometry = renderGeometry,
                hasSurfaceAlpha = ldiLite.displayAlpha != null,
                extendBackgroundCanvas = ldiLite.renderer.isVNext
            )
            if (ldiLite.renderer.usesNormalizedSurfaceCharts) {
                val charts = checkNotNull(ldiLite.surfaceCharts)
                val basis = checkNotNull(renderGeometry.motionBasis)
                if (chartFloatType != 0) {
                    runCatching {
                        val maximumTextureSize = IntArray(1)
                        GLES20.glGetIntegerv(
                            GLES20.GL_MAX_TEXTURE_SIZE,
                            maximumTextureSize,
                            0
                        )
                        SpatialSurfaceChartRenderDataBuilder.build(
                            charts = charts,
                            motionBasis = basis,
                            maximumAtlasSize = maximumTextureSize[0]
                        )
                    }.onSuccess { renderData ->
                        chartRenderData = renderData
                        chartWeightTexture = createChartWeightTexture(renderData)
                        val vertices = buildChartVertices(renderData)
                        chartVertices = floatBuffer(vertices)
                        chartVertexCount = vertices.size / CHART_FLOATS_PER_VERTEX
                    }.onFailure { error ->
                        Log.e(
                            "SpatialSurfaceCharts",
                            "无法建立归一化 chart 图集，禁止降级为普通 alpha",
                            error
                        )
                    }
                } else {
                    Log.e(
                        "SpatialSurfaceCharts",
                        "设备缺少浮点颜色缓冲；禁止降级为普通 alpha 或单层 warp"
                    )
                }
            }
            if (ldiLite.renderer.usesDepthSurfels) {
                val surfels = checkNotNull(ldiLite.depthSurfels)
                val pointVertices = buildSurfelVertices(surfels)
                surfelVertices = floatBuffer(pointVertices)
                surfelVertexCount = pointVertices.size / SURFEL_FLOATS_PER_VERTEX
            }
            if (BuildConfig.DEBUG && ldiLite.renderer.isVNext) {
                val chartModel = SpatialChartMotionModel.fit(
                    renderGeometry.width,
                    renderGeometry.height,
                    renderGeometry.surfaceDepth,
                    renderGeometry.cutRight,
                    renderGeometry.cutDown
                )
                val chartSizes = IntArray(chartModel.components.size)
                chartModel.labels.forEach { chartSizes[it]++ }
                val amplitude = ldiLite.viewEnvelope
                    ?.amplitudes
                    ?.maxOrNull()
                    ?: 0f
                val affine = chartModel.affineGradientNorms()
                val residual = chartModel.residualGradientNorms(
                    renderGeometry.surfaceDepth
                )
                val maximumCombinedStrain = affine.indices.maxOfOrNull { component ->
                    amplitude * (affine[component] + residual[component])
                } ?: 0f
                Log.d(
                    "SpatialVNextGeometry",
                    "renderer=${ldiLite.renderer.stableId}, charts=${chartSizes.size}, " +
                        "largest=${(chartSizes.maxOrNull() ?: 0).toFloat() / chartModel.labels.size}, " +
                        "amplitude=$amplitude, combinedStrain=$maximumCombinedStrain"
                )
            }
            ldiGradientProfile = if (
                ldiLite.renderer == SpatialLdiRenderer.LEGACY_V19
            ) {
                SpatialWarpBudget.analyze(renderGeometry)
            } else {
                null
            }
        } else {
            backgroundTexture = 0
            surfaceAlphaTexture = 0
            surfaceAlphaWidth = 1
            surfaceAlphaHeight = 1
            ldiGradientProfile = null
            ldiMesh = null
            activeLdiRenderer = SpatialLdiRenderer.LEGACY_V19
            activeViewEnvelope = null
            motionBasisTexture = 0
            chartWeightTexture = 0
            chartRenderData = null
            chartVertices = null
            chartVertexCount = 0
            surfelVertices = null
            surfelVertexCount = 0
        }

        depthTexture = createDepthTexture(scene.depth)
        val renderDepth = SpatialRenderDepthStabilizer.stabilize(scene.depth)
        warpProfile = SpatialWarpBudget.analyze(renderDepth)
        renderDepthTexture = createDepthTexture(renderDepth)
        if (BuildConfig.DEBUG) {
            // 每场景一次：P0 记录 Jacobian 限幅；P1 只记录连续面梯度，实际由 splat
            // 足迹覆盖而不削减行程。
            val p0 = warpProfile?.gradientNorm ?: 0f
            val p1 = ldiGradientProfile?.gradientNorm
            Log.d(
                "SpatialWarpBudget",
                "p99.5 gradient p0=%.2f p1=%s, motion cap p0=%.4f p1=splat-footprint, request max=%.3f"
                    .format(
                        p0,
                        p1?.let { "%.2f".format(it) } ?: "-",
                        if (p0 > 0f) SpatialWarpBudget.MAX_DISPLACEMENT_GRADIENT / p0 else 1f,
                        SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE
                    )
            )
        }
    }

    /**
     * 全表面 chart 的正式合成路径：两个浮点离屏目标分别累加颜色分子＋软 z 分母和
     * 原始 coverage，最后在目标位置归一化。任何失败都返回 false，由调用方显示静态
     * 原图；这里禁止回退到普通 alpha、硬 z 或单层 warp。
     */
    private fun drawNormalizedSurfaceCharts(): Boolean {
        val renderData = chartRenderData ?: return false
        val chartBuffer = chartVertices ?: return false
        val charts = pendingScene?.ldiLite?.surfaceCharts ?: return false
        val mesh = ldiMesh ?: return false
        val envelope = activeViewEnvelope ?: return false
        if (
            chartProgram == 0 || chartCompositeProgram == 0 ||
            chartWeightTexture == 0 || chartVertexCount == 0
        ) return false
        val targets = ensureChartTargets() ?: return false
        val (scaleX, scaleY) = imageScale()
        val imageScissor = SpatialImageViewport.scissor(
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight,
            scaleX = scaleX,
            scaleY = scaleY
        )
        val motion = envelope.motion(offsetX, offsetY, strength)
        val guard = charts.guardFraction

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDepthMask(true)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(
            imageScissor.left,
            imageScissor.bottom,
            imageScissor.width,
            imageScissor.height
        )

        // 隐藏底板只跟随持久化的公共 reframe；不读取局部 chart 深度，也不参与归一化。
        GLES20.glUseProgram(ldiProgram)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(ldiProgram, "uScale"), scaleX, scaleY)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(ldiProgram, "uCoverMargin"), guard, guard)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(ldiProgram, "uViewpoint"),
            offsetX,
            offsetY
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(ldiProgram, "uParallaxMotion"),
            motion.x,
            motion.y
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ldiProgram, "uRigidPan"), 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ldiProgram, "uStrength"), strength)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(ldiProgram, "uReliefGain"),
            SpatialRenderDepthStabilizer.RELIEF_GAIN
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(ldiProgram, "uMaxRelief"),
            SpatialRenderDepthStabilizer.MAX_RELIEF
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(ldiProgram, "uDepthTexel"),
            1f / depthWidth.coerceAtLeast(1),
            1f / depthHeight.coerceAtLeast(1)
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(ldiProgram, "uAlphaTexel"),
            1f / surfaceAlphaWidth.coerceAtLeast(1),
            1f / surfaceAlphaHeight.coerceAtLeast(1)
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(ldiProgram, "uDepth"), 1)
        applyLdiPassState(SpatialLdiRenderPassPolicy.HIDDEN_BACKGROUND)
        drawLdiLayer(
            chunks = mesh.backgroundChunks,
            background = true,
            texture = backgroundTexture,
            useRelief = false,
            useDisplayAlpha = false
        )
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

        drawChartAccumulationPass(
            framebuffer = targets.accumulationFramebuffer,
            coveragePass = false,
            renderData = renderData,
            vertices = chartBuffer,
            scaleX = scaleX,
            scaleY = scaleY,
            guard = guard,
            motionX = motion.x,
            motionY = motion.y
        )
        drawChartAccumulationPass(
            framebuffer = targets.coverageFramebuffer,
            coveragePass = true,
            renderData = renderData,
            vertices = chartBuffer,
            scaleX = scaleX,
            scaleY = scaleY,
            guard = guard,
            motionX = motion.x,
            motionY = motion.y
        )

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(
            imageScissor.left,
            imageScissor.bottom,
            imageScissor.width,
            imageScissor.height
        )
        GLES20.glUseProgram(chartCompositeProgram)
        vertices.position(0)
        val position = GLES20.glGetAttribLocation(chartCompositeProgram, "aPosition")
        val textureCoordinate = GLES20.glGetAttribLocation(
            chartCompositeProgram,
            "aTexCoord"
        )
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(
            position,
            2,
            GLES20.GL_FLOAT,
            false,
            STRIDE_BYTES,
            vertices
        )
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(textureCoordinate)
        GLES20.glVertexAttribPointer(
            textureCoordinate,
            2,
            GLES20.GL_FLOAT,
            false,
            STRIDE_BYTES,
            vertices
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targets.accumulationTexture)
        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(chartCompositeProgram, "uAccumulation"),
            0
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targets.coverageTexture)
        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(chartCompositeProgram, "uCoverage"),
            1
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(chartCompositeProgram, "uCoverageLow"),
            SpatialSurfaceChartBuilder.COVERAGE_ALPHA_LOW
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(chartCompositeProgram, "uCoverageHigh"),
            SpatialSurfaceChartBuilder.COVERAGE_ALPHA_HIGH
        )
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(textureCoordinate)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        return true
    }

    /**
     * 连续深度微表面正式路径：每个 guide 样本作为刚性点元平移，RGBA8＋深度离屏目标
     * 在目标位置只保留最近表面；合成阶段仅修补被相对邻点夹住的一像素裂缝。它不会把
     * 人物或其他语义区域合并成平面，也不会回退到整图 warp。
     */
    private fun drawDepthSurfels(): Boolean {
        val surfels = pendingScene?.ldiLite?.depthSurfels ?: return false
        val pointBuffer = surfelVertices ?: return false
        val mesh = ldiMesh ?: return false
        val envelope = activeViewEnvelope ?: return false
        if (
            surfelProgram == 0 || surfelCompositeProgram == 0 ||
            surfelVertexCount != surfels.width * surfels.height
        ) return false
        val targets = ensureSurfelTargets() ?: return false
        val (scaleX, scaleY) = imageScale()
        val imageScissor = SpatialImageViewport.scissor(
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight,
            scaleX = scaleX,
            scaleY = scaleY
        )
        val motion = envelope.motion(offsetX, offsetY, strength)
        val guard = surfels.guardFraction
        val cropScale = (1f - 2f * guard).coerceAtLeast(1e-6f)
        val requiredPointSize = kotlin.math.ceil(
            maxOf(
                surfaceWidth * scaleX / surfels.width,
                surfaceHeight * scaleY / surfels.height,
                1f
            ) / cropScale
        ).toFloat()
        if (requiredPointSize > maximumPointSize + 1e-3f) {
            Log.e(
                "SpatialDepthSurfels",
                "设备点元上限不足：required=$requiredPointSize, maximum=$maximumPointSize"
            )
            return false
        }

        // 隐藏底板只跟随 p8 远景公共位移；不复制任何前景内容的局部运动。
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDepthMask(true)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(
            imageScissor.left,
            imageScissor.bottom,
            imageScissor.width,
            imageScissor.height
        )
        GLES20.glUseProgram(ldiProgram)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(ldiProgram, "uScale"), scaleX, scaleY)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(ldiProgram, "uCoverMargin"), guard, guard)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(ldiProgram, "uViewpoint"),
            offsetX,
            offsetY
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(ldiProgram, "uParallaxMotion"),
            motion.x,
            motion.y
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ldiProgram, "uRigidPan"), 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ldiProgram, "uStrength"), strength)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(ldiProgram, "uReliefGain"),
            SpatialRenderDepthStabilizer.RELIEF_GAIN
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(ldiProgram, "uMaxRelief"),
            SpatialRenderDepthStabilizer.MAX_RELIEF
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(ldiProgram, "uDepthTexel"),
            1f / depthWidth.coerceAtLeast(1),
            1f / depthHeight.coerceAtLeast(1)
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(ldiProgram, "uAlphaTexel"),
            1f / surfaceAlphaWidth.coerceAtLeast(1),
            1f / surfaceAlphaHeight.coerceAtLeast(1)
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(ldiProgram, "uDepth"), 1)
        applyLdiPassState(SpatialLdiRenderPassPolicy.HIDDEN_BACKGROUND)
        drawLdiLayer(
            chunks = mesh.backgroundChunks,
            background = true,
            texture = backgroundTexture,
            useRelief = false,
            useDisplayAlpha = false
        )
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

        // 一次点元绘制完成连续深度重投影与最近表面选择。
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets.framebuffer)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glDepthMask(true)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glUseProgram(surfelProgram)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(surfelProgram, "uScale"),
            scaleX,
            scaleY
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(surfelProgram, "uParallaxMotion"),
            motion.x,
            motion.y
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(surfelProgram, "uCoverMargin"),
            guard,
            guard
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(surfelProgram, "uScalarToUv"),
            1f / (surfels.width * surfels.requestedMaximumParallax),
            1f / (surfels.height * surfels.requestedMaximumParallax)
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(surfelProgram, "uTargetSize"),
            surfaceWidth.toFloat(),
            surfaceHeight.toFloat()
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(surfelProgram, "uPointSize"),
            requiredPointSize
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(surfelProgram, "uColor"), 0)

        val sourceUv = GLES20.glGetAttribLocation(surfelProgram, "aSourceUv")
        val motionScalar = GLES20.glGetAttribLocation(surfelProgram, "aMotionScalar")
        val surfaceDepth = GLES20.glGetAttribLocation(surfelProgram, "aSurfaceDepth")
        GLES20.glEnableVertexAttribArray(sourceUv)
        GLES20.glEnableVertexAttribArray(motionScalar)
        GLES20.glEnableVertexAttribArray(surfaceDepth)
        pointBuffer.position(0)
        GLES20.glVertexAttribPointer(
            sourceUv,
            2,
            GLES20.GL_FLOAT,
            false,
            SURFEL_VERTEX_STRIDE_BYTES,
            pointBuffer
        )
        pointBuffer.position(2)
        GLES20.glVertexAttribPointer(
            motionScalar,
            1,
            GLES20.GL_FLOAT,
            false,
            SURFEL_VERTEX_STRIDE_BYTES,
            pointBuffer
        )
        pointBuffer.position(3)
        GLES20.glVertexAttribPointer(
            surfaceDepth,
            1,
            GLES20.GL_FLOAT,
            false,
            SURFEL_VERTEX_STRIDE_BYTES,
            pointBuffer
        )
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, surfelVertexCount)
        GLES20.glDisableVertexAttribArray(sourceUv)
        GLES20.glDisableVertexAttribArray(motionScalar)
        GLES20.glDisableVertexAttribArray(surfaceDepth)
        GLES20.glDepthMask(true)
        GLES20.glDepthFunc(GLES20.GL_LESS)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        // 合成只填被相对邻点夹住的单像素内部裂缝；真实显露区保持透明，由补景承接。
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(
            imageScissor.left,
            imageScissor.bottom,
            imageScissor.width,
            imageScissor.height
        )
        GLES20.glUseProgram(surfelCompositeProgram)
        vertices.position(0)
        val position = GLES20.glGetAttribLocation(surfelCompositeProgram, "aPosition")
        val textureCoordinate = GLES20.glGetAttribLocation(
            surfelCompositeProgram,
            "aTexCoord"
        )
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(
            position,
            2,
            GLES20.GL_FLOAT,
            false,
            STRIDE_BYTES,
            vertices
        )
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(textureCoordinate)
        GLES20.glVertexAttribPointer(
            textureCoordinate,
            2,
            GLES20.GL_FLOAT,
            false,
            STRIDE_BYTES,
            vertices
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targets.colorTexture)
        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(surfelCompositeProgram, "uSurfels"),
            0
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(surfelCompositeProgram, "uTargetTexel"),
            1f / surfaceWidth,
            1f / surfaceHeight
        )
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(textureCoordinate)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        return true
    }

    private fun drawChartAccumulationPass(
        framebuffer: Int,
        coveragePass: Boolean,
        renderData: SpatialSurfaceChartRenderData,
        vertices: FloatBuffer,
        scaleX: Float,
        scaleY: Float,
        guard: Float,
        motionX: Float,
        motionY: Float
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glUseProgram(chartProgram)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(chartProgram, "uScale"), scaleX, scaleY)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(chartProgram, "uParallaxMotion"),
            motionX,
            motionY
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(chartProgram, "uCoverMargin"),
            guard,
            guard
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(chartProgram, "uAtlasSize"),
            renderData.atlasWidth.toFloat(),
            renderData.atlasHeight.toFloat()
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(chartProgram, "uCoveragePass"),
            if (coveragePass) 1f else 0f
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(chartProgram, "uColor"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, chartWeightTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(chartProgram, "uWeights"), 1)

        val sourceUv = GLES20.glGetAttribLocation(chartProgram, "aSourceUv")
        val weightUv = GLES20.glGetAttribLocation(chartProgram, "aWeightUv")
        val motionBasisX = GLES20.glGetAttribLocation(chartProgram, "aMotionBasisX")
        val motionBasisY = GLES20.glGetAttribLocation(chartProgram, "aMotionBasisY")
        val zWeight = GLES20.glGetAttribLocation(chartProgram, "aZWeight")
        listOf(sourceUv, weightUv, motionBasisX, motionBasisY, zWeight).forEach(
            GLES20::glEnableVertexAttribArray
        )
        vertices.position(0)
        GLES20.glVertexAttribPointer(
            sourceUv, 2, GLES20.GL_FLOAT, false, CHART_VERTEX_STRIDE_BYTES, vertices
        )
        vertices.position(2)
        GLES20.glVertexAttribPointer(
            weightUv, 2, GLES20.GL_FLOAT, false, CHART_VERTEX_STRIDE_BYTES, vertices
        )
        vertices.position(4)
        GLES20.glVertexAttribPointer(
            motionBasisX, 2, GLES20.GL_FLOAT, false, CHART_VERTEX_STRIDE_BYTES, vertices
        )
        vertices.position(6)
        GLES20.glVertexAttribPointer(
            motionBasisY, 2, GLES20.GL_FLOAT, false, CHART_VERTEX_STRIDE_BYTES, vertices
        )
        vertices.position(8)
        GLES20.glVertexAttribPointer(
            zWeight, 1, GLES20.GL_FLOAT, false, CHART_VERTEX_STRIDE_BYTES, vertices
        )
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, chartVertexCount)
        listOf(sourceUv, weightUv, motionBasisX, motionBasisY, zWeight).forEach(
            GLES20::glDisableVertexAttribArray
        )
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** 浮点链路不可用时只显示未变形原图，绝不伪装成通过的空间效果。 */
    private fun drawStaticSourceFallback() {
        if (mpiProgram == 0 || colorTexture == 0) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(mpiProgram)
        val (scaleX, scaleY) = imageScale()
        GLES20.glUniform2f(GLES20.glGetUniformLocation(mpiProgram, "uScale"), scaleX, scaleY)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(mpiProgram, "uShift"), 0f, 0f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(mpiProgram, "uInset"), 0f, 0f)
        vertices.position(0)
        val position = GLES20.glGetAttribLocation(mpiProgram, "aPosition")
        val textureCoordinate = GLES20.glGetAttribLocation(mpiProgram, "aTexCoord")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(
            position, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertices
        )
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(textureCoordinate)
        GLES20.glVertexAttribPointer(
            textureCoordinate, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertices
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(mpiProgram, "uColor"), 0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(textureCoordinate)
    }

    private fun imageScale(): Pair<Float, Float> {
        val viewAspect = surfaceWidth.toFloat() / surfaceHeight
        val imageAspect = imageWidth.toFloat() / imageHeight
        return if (imageAspect > viewAspect) {
            1f to viewAspect / imageAspect
        } else {
            imageAspect / viewAspect to 1f
        }
    }

    private fun drawLdiLite() {
        val mesh = ldiMesh ?: return
        // 上一帧的背景 pass 会关闭 depth write；清屏前先恢复，保证深度缓冲确实归一。
        GLES20.glDepthMask(true)
        GLES20.glClear(
            GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT
        )
        GLES20.glUseProgram(ldiProgram)

        val viewAspect = surfaceWidth.toFloat() / surfaceHeight
        val imageAspect = imageWidth.toFloat() / imageHeight
        val scaleX: Float
        val scaleY: Float
        if (imageAspect > viewAspect) {
            scaleX = 1f
            scaleY = viewAspect / imageAspect
        } else {
            scaleX = imageAspect / viewAspect
            scaleY = 1f
        }
        // cover margin 会把网格边缘推到参考图矩形之外以避免露黑；若不裁剪，这些不同
        // 深度的外缘会直接画进 letterbox，表现为整张照片上下边界弯曲。固定 scissor
        // 让空间运动只发生在原图画框内部，不靠额外曲面畸变掩盖 artifact。
        val imageScissor = SpatialImageViewport.scissor(
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight,
            scaleX = scaleX,
            scaleY = scaleY
        )
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(
            imageScissor.left,
            imageScissor.bottom,
            imageScissor.width,
            imageScissor.height
        )
        val vNextMotion = if (activeLdiRenderer.isVNext) {
            activeViewEnvelope?.motion(offsetX, offsetY, strength)
        } else {
            null
        }
        val amplitude = vNextMotion?.amplitude ?: run {
            SpatialRenderDepthStabilizer.MIN_PARALLAX_AMPLITUDE +
                (
                    SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE -
                        SpatialRenderDepthStabilizer.MIN_PARALLAX_AMPLITUDE
                    ) * strength
        }
        val vNextMaximumAmplitude = if (vNextMotion != null) {
            activeViewEnvelope?.maximumMotionAmplitude(strength)
        } else {
            null
        }
        // v19 保留旧预算以兼容已有 derivative。vNext 的 chart 深度已经在生成阶段按
        // 对象尺度无关的 Jacobian 门槛治理，运行时只插值持久化方向包络。
        val safeMotion = if (vNextMotion != null) {
            SpatialWarpBudget.Motion(vNextMotion.x, vNextMotion.y, 1f)
        } else {
            safeParallaxMotion(amplitude, ldiGradientProfile)
        }
        // vNext 恢复完整视差后，画框边缘会在端点视角暴露边缘复制区。使用当前强度下、任意方向
        // 最大幅度一次性计算等比边距；该值不随视点半径和方向改变，所以不会引入缩放呼吸，
        // 也不会压缩主体与背景的相对视差。
        // 真透视档的取景内缩由生成期按真实位移场算好并落盘：运行时那条
        // `coverMargin(amplitude)` 假定幅度是归一化位移，而这一档的幅度单位是**米**，
        // 代进去得到的数没有意义。
        val persistedCoverMargin = pendingScene?.ldiLite
            ?.takeIf { activeLdiRenderer.usesPersistedCoverMargin }
            ?.coverMarginFraction
        val coverMargin = when {
            persistedCoverMargin != null ->
                SpatialSourceLock.Margin(persistedCoverMargin, persistedCoverMargin)
            vNextMaximumAmplitude != null -> SpatialSourceLock.coverMargin(vNextMaximumAmplitude)
            else -> SpatialSourceLock.coverMargin(amplitude)
        }
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(ldiProgram, "uScale"),
            scaleX,
            scaleY
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(ldiProgram, "uCoverMargin"),
            coverMargin.x,
            coverMargin.y
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(ldiProgram, "uViewpoint"),
            offsetX,
            offsetY
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(ldiProgram, "uParallaxMotion"),
            safeMotion.x,
            safeMotion.y
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(ldiProgram, "uRigidPan"),
            SpatialRenderDepthStabilizer.RIGID_PAN_AMPLITUDE
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(ldiProgram, "uStrength"),
            strength
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(ldiProgram, "uReliefGain"),
            SpatialRenderDepthStabilizer.RELIEF_GAIN
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(ldiProgram, "uMaxRelief"),
            SpatialRenderDepthStabilizer.MAX_RELIEF
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(ldiProgram, "uDepthTexel"),
            1f / depthWidth.coerceAtLeast(1),
            1f / depthHeight.coerceAtLeast(1)
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(ldiProgram, "uAlphaTexel"),
            1f / surfaceAlphaWidth.coerceAtLeast(1),
            1f / surfaceAlphaHeight.coerceAtLeast(1)
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexture)
        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(ldiProgram, "uDepth"),
            1
        )

        applyLdiPassState(SpatialLdiRenderPassPolicy.HIDDEN_BACKGROUND)
        // vNext 的“稳定”与“立体”使用同一保形 chart 运动，避免稳定模式退回高形变
        // 单纹理 warp；稳定仅关闭 matting alpha 显露，保留完整空间视差与隐藏背景。
        val useSurfaceAlpha = !(
            renderMode == SpatialRenderMode.SINGLE_LAYER && activeLdiRenderer.isVNext
            )
        drawLdiLayer(
            chunks = mesh.backgroundChunks,
            background = true,
            texture = backgroundTexture,
            useRelief = false,
            useDisplayAlpha = false
        )
        // 前景表面可以先画进超采样离屏目标，分辨遍再做盒式降采样与窄缝闭合。底板留在
        // 屏幕分辨率：它是一整幅平滑图像，超采样对它几乎没有收益，却要多花一倍多显存。
        val superSampled = if (activeLdiRenderer.usesSupersampledMesh) {
            ensureMeshTargets(imageScissor.width, imageScissor.height)
        } else {
            null
        }
        if (superSampled != null) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, superSampled.framebuffer)
            GLES20.glViewport(0, 0, superSampled.width, superSampled.height)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glDepthMask(true)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            // 目标正好等于画面矩形，所以这一遍不再做信箱式缩放
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(ldiProgram, "uScale"),
                1f,
                1f
            )
        }
        applyLdiPassState(SpatialLdiRenderPassPolicy.CONNECTED_SURFACE)
        drawLdiLayer(
            chunks = mesh.connectedSurfaceChunks,
            background = false,
            texture = colorTexture,
            useRelief = true,
            useDisplayAlpha = useSurfaceAlpha
        )
        // 连通面已经覆盖的同深度像素不能再累积一次半透明 alpha。边界 splat 只补严格
        // 更靠近相机或尚未被连通面覆盖的 cut 缺口；远侧同深度区域由背景板承接。
        // P2（design-2026-08-03）：对象回归连续表面，不再有独立刚性平面通道；
        // 空间感来自全帧逐像素深度形变，实例仅在生成期作断边先验。
        applyLdiPassState(SpatialLdiRenderPassPolicy.BOUNDARY_SPLAT)
        drawLdiLayer(
            chunks = mesh.boundarySurfaceChunks,
            background = false,
            texture = colorTexture,
            useRelief = true,
            useDisplayAlpha = useSurfaceAlpha
        )
        if (superSampled != null) {
            resolveSuperSampledMesh(superSampled, imageScissor, scaleX, scaleY)
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    /** 把超采样目标盒式降采样并闭合窄缝后，预乘叠加到已画好底板的屏幕上。 */
    private fun resolveSuperSampledMesh(
        targets: SurfelTargets,
        imageScissor: SpatialImageViewport.ScissorRect,
        scaleX: Float,
        scaleY: Float
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(
            imageScissor.left,
            imageScissor.bottom,
            imageScissor.width,
            imageScissor.height
        )
        GLES20.glUseProgram(meshResolveProgram)
        val box = (targets.width.toFloat() / imageScissor.width.coerceAtLeast(1))
            .coerceIn(1f, 4f)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(meshResolveProgram, "uSourceTexel"),
            1f / targets.width,
            1f / targets.height
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(meshResolveProgram, "uBoxStep"),
            1f / targets.width,
            1f / targets.height
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(meshResolveProgram, "uBoxSamples"),
            box
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(meshResolveProgram, "uRectOrigin"),
            0.5f - scaleX * 0.5f,
            0.5f - scaleY * 0.5f
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(meshResolveProgram, "uRectSize"),
            scaleX,
            scaleY
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targets.colorTexture)
        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(meshResolveProgram, "uSurface"),
            0
        )
        val position = GLES20.glGetAttribLocation(meshResolveProgram, "aPosition")
        val textureCoordinate = GLES20.glGetAttribLocation(meshResolveProgram, "aTexCoord")
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(
            position, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertices
        )
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(textureCoordinate)
        GLES20.glVertexAttribPointer(
            textureCoordinate, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertices
        )
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(textureCoordinate)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        vertices.position(0)
    }

    private fun applyLdiPassState(state: SpatialLdiPassState) {
        if (state.depthTest) {
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        } else {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        }
        GLES20.glDepthMask(state.depthWrite)
        GLES20.glDepthFunc(
            when (state.depthFunction) {
                SpatialLdiDepthFunction.LESS -> GLES20.GL_LESS
                SpatialLdiDepthFunction.LESS_OR_EQUAL -> GLES20.GL_LEQUAL
            }
        )
    }

    /**
     * 单层 backward warp 仍需局部形变预算。前向 splat 的样本只做刚性平移，连续表面
     * 覆盖由足迹解析，不使用这条 Jacobian 预算。
     */
    private fun safeParallaxMotion(
        amplitude: Float,
        profile: SpatialWarpBudget.Profile?
    ): SpatialWarpBudget.Motion =
        profile?.limitMotion(
            requestedX = offsetX * amplitude,
            requestedY = offsetY * amplitude
        ) ?: SpatialWarpBudget.Motion(
            x = offsetX * amplitude,
            y = offsetY * amplitude,
            scale = 1f
        )

    private fun drawLdiLayer(
        chunks: List<LdiMeshChunk>,
        background: Boolean,
        texture: Int,
        useRelief: Boolean,
        useDisplayAlpha: Boolean
    ) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(ldiProgram, "uColor"),
            0
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(ldiProgram, "uUseRelief"),
            if (useRelief) 1f else 0f
        )
        val useSurfaceAlpha =
            !background && useDisplayAlpha && surfaceAlphaTexture != 0
        val alphaReveal = if (
            useSurfaceAlpha &&
            activeLdiRenderer.isVNext
        ) {
            val linear = (
                kotlin.math.hypot(offsetX, offsetY) / VNEXT_ALPHA_REVEAL_RADIUS
                ).coerceIn(0f, 1f)
            linear * linear * (3f - 2f * linear)
        } else if (useSurfaceAlpha) {
            1f
        } else {
            0f
        }
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(ldiProgram, "uUseSurfaceAlpha"),
            alphaReveal
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, surfaceAlphaTexture)
        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(ldiProgram, "uSurfaceAlpha"),
            3
        )
        if (useSurfaceAlpha) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        } else {
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        val textureCoordinate =
            GLES20.glGetAttribLocation(ldiProgram, "aTexCoord")
        val vertexDepth = GLES20.glGetAttribLocation(ldiProgram, "aDepth")
        val motionBasisX = GLES20.glGetAttribLocation(ldiProgram, "aMotionBasisX")
        val motionBasisY = GLES20.glGetAttribLocation(ldiProgram, "aMotionBasisY")
        GLES20.glEnableVertexAttribArray(textureCoordinate)
        GLES20.glEnableVertexAttribArray(vertexDepth)
        GLES20.glEnableVertexAttribArray(motionBasisX)
        GLES20.glEnableVertexAttribArray(motionBasisY)
        for (chunk in chunks) {
            val vertices = chunk.vertices
            val indices = chunk.indices
            if (indices.limit() == 0) continue
            vertices.position(0)
            GLES20.glVertexAttribPointer(
                textureCoordinate,
                2,
                GLES20.GL_FLOAT,
                false,
                LDI_VERTEX_STRIDE_BYTES,
                vertices
            )
            vertices.position(2)
            GLES20.glVertexAttribPointer(
                vertexDepth,
                1,
                GLES20.GL_FLOAT,
                false,
                LDI_VERTEX_STRIDE_BYTES,
                vertices
            )
            vertices.position(3)
            GLES20.glVertexAttribPointer(
                motionBasisX,
                2,
                GLES20.GL_FLOAT,
                false,
                LDI_VERTEX_STRIDE_BYTES,
                vertices
            )
            vertices.position(5)
            GLES20.glVertexAttribPointer(
                motionBasisY,
                2,
                GLES20.GL_FLOAT,
                false,
                LDI_VERTEX_STRIDE_BYTES,
                vertices
            )
            indices.position(0)
            GLES20.glDrawElements(
                GLES20.GL_TRIANGLES,
                indices.limit(),
                GLES20.GL_UNSIGNED_SHORT,
                indices
            )
        }
        GLES20.glDisableVertexAttribArray(textureCoordinate)
        GLES20.glDisableVertexAttribArray(vertexDepth)
        GLES20.glDisableVertexAttribArray(motionBasisX)
        GLES20.glDisableVertexAttribArray(motionBasisY)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * 参考视点直接显示全分辨率原图，统一守住所有实验表示的 source-lock 不变量。
     * 该路径不读取深度、隐藏背景、matting 或 MPI 平面。
     */
    private fun ensureMpiTextures() {
        val planes = mpiPlanes
        if (mpiUploadedFor === planes) return
        mpiTextures?.let { GLES20.glDeleteTextures(it.size, it, 0) }
        mpiTextures = null
        mpiUploadedFor = planes
        if (planes.isNullOrEmpty()) return
        val textures = IntArray(planes.size)
        for (index in planes.indices) {
            textures[index] = createTexture()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[index])
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, planes[index].bitmap, 0)
        }
        mpiTextures = textures
    }

    private fun drawMpi(textures: IntArray) {
        val planes = mpiUploadedFor ?: return
        val drawStart = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        GLES20.glUseProgram(mpiProgram)

        val viewAspect = surfaceWidth.toFloat() / surfaceHeight
        val imageAspect = imageWidth.toFloat() / imageHeight
        val scaleX: Float
        val scaleY: Float
        if (imageAspect > viewAspect) {
            scaleX = 1f
            scaleY = viewAspect / imageAspect
        } else {
            scaleX = imageAspect / viewAspect
            scaleY = 1f
        }
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(mpiProgram, "uScale"),
            scaleX,
            scaleY
        )
        val amplitude =
            SpatialRenderDepthStabilizer.MIN_PARALLAX_AMPLITUDE +
                (
                    SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE -
                        SpatialRenderDepthStabilizer.MIN_PARALLAX_AMPLITUDE
                    ) * strength
        // MPI 无网格拉伸，不经形变预算；越界采样以内缩采样窗抵消。
        val motionX = offsetX * amplitude
        val motionY = offsetY * amplitude
        val rigidX = offsetX * SpatialRenderDepthStabilizer.RIGID_PAN_AMPLITUDE
        val rigidY = offsetY * SpatialRenderDepthStabilizer.RIGID_PAN_AMPLITUDE
        val inset = SpatialSourceLock.coverMargin(amplitude)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(mpiProgram, "uInset"),
            inset.x,
            inset.y
        )

        vertices.position(0)
        val position = GLES20.glGetAttribLocation(mpiProgram, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(
            position, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertices
        )
        vertices.position(2)
        val textureCoordinate = GLES20.glGetAttribLocation(mpiProgram, "aTexCoord")
        GLES20.glEnableVertexAttribArray(textureCoordinate)
        GLES20.glVertexAttribPointer(
            textureCoordinate, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertices
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(mpiProgram, "uColor"), 0)
        val shiftLocation = GLES20.glGetUniformLocation(mpiProgram, "uShift")

        // 静止底图：极少数全层缺口由仅随刚性平移的原图承接。此前用「按远层深度
        // 平移的背景板」打底，板上的前景内容以错误深度移动，半透明处透出成片
        // 位移鬼影（2026-08-01 用户反馈的重影主源之一）；静态残影远不显眼。
        if (colorTexture != 0) {
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUniform2f(shiftLocation, rigidX, rigidY)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexture)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glEnable(GLES20.GL_BLEND)
        // 平面位图经 GLUtils 上传为预乘 alpha。
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // 列表按深度从远到近排序，按序 over 合成。
        for (index in planes.indices) {
            val plane = planes[index]
            GLES20.glUniform2f(
                shiftLocation,
                motionX * (plane.depth - 0.5f) + rigidX,
                motionY * (plane.depth - 0.5f) + rigidY
            )
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[index])
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(textureCoordinate)
        GLES20.glDisable(GLES20.GL_BLEND)
        if (BuildConfig.DEBUG) {
            // CPU 侧提交耗时；GPU 填充率需 systrace/gfx 工具另测。
            mpiDrawNanos += System.nanoTime() - drawStart
            mpiDrawCount++
            if (mpiDrawCount >= 120) {
                Log.d(
                    "SpatialMpi",
                    "cpu submit avg %.2f ms over %d frames, planes=%d"
                        .format(
                            mpiDrawNanos / 1e6 / mpiDrawCount,
                            mpiDrawCount,
                            planes.size
                        )
                )
                mpiDrawNanos = 0L
                mpiDrawCount = 0
            }
        }
    }

    private fun buildLdiMesh(
        geometry: SpatialLdiLiteGeometry,
        hasSurfaceAlpha: Boolean,
        extendBackgroundCanvas: Boolean
    ): LdiMesh {
        // P2 修正（用户实测立体视差塌缩）：stabilizeForSplat 是 v18 里给"残差"设计的
        // 分量均值压缩器——刚性平面移除后再用它，场景分量内部被压平，跨帧连续视差
        // 消失。连续网格直接使用几何阶段的 surfaceDepth：regularize 已保证断边间
        // 每边应变有界，跨断边全程保留，层间与面内视差同时成立。
        val surfaceDepth = geometry.surfaceDepth
        val hybridMesh = SpatialHybridMeshBuilder.build(
            width = geometry.width,
            height = geometry.height,
            surfaceDepth = surfaceDepth,
            cutRight = geometry.cutRight,
            cutDown = geometry.cutDown,
            // 仅在 matting 能给出连续覆盖率时放宽几何硬边：补片多覆盖半个采样间距，
            // 真正的发丝轮廓仍由高分辨率 alpha 裁定。无 matting 时保持原显露区不变。
            nearCutPaddingSamples = if (hasSurfaceAlpha) {
                SpatialAlphaEdgeRefiner.NEAR_CUT_PADDING_SAMPLES
            } else {
                0f
            },
            excludedSamples = null
        )
        fun mapChunks(
            chunks: List<SpatialSplatMeshBuilder.Chunk>,
            boundarySplats: Boolean
        ) =
            chunks.map { chunk ->
            LdiMeshChunk(
                vertices = floatBuffer(
                    expandSurfaceMotionVertices(
                        source = chunk.vertices,
                        geometry = geometry,
                        boundarySplats = boundarySplats
                    )
                ),
                indices = shortBuffer(chunk.indices, chunk.indices.size)
            )
        }
        return LdiMesh(
            connectedSurfaceChunks = mapChunks(
                hybridMesh.connected,
                boundarySplats = false
            ),
            boundarySurfaceChunks = mapChunks(
                hybridMesh.boundarySplats,
                boundarySplats = true
            ),
            backgroundChunks = buildConnectedBackgroundChunks(
                geometry,
                extendCanvas = extendBackgroundCanvas
            )
        )
    }

    private fun expandSurfaceMotionVertices(
        source: FloatArray,
        geometry: SpatialLdiLiteGeometry,
        boundarySplats: Boolean
    ): FloatArray {
        require(source.size % SpatialSplatMeshBuilder.FLOATS_PER_VERTEX == 0)
        val vertexCount = source.size / SpatialSplatMeshBuilder.FLOATS_PER_VERTEX
        if (boundarySplats) require(vertexCount % 4 == 0)
        val result = FloatArray(vertexCount * LDI_FLOATS_PER_VERTEX)
        fun sampleIndex(firstVertex: Int): Int {
            val count = if (boundarySplats) 4 else 1
            var sumU = 0f
            var sumV = 0f
            repeat(count) { offset ->
                val sourceOffset = (firstVertex + offset) *
                    SpatialSplatMeshBuilder.FLOATS_PER_VERTEX
                sumU += source[sourceOffset]
                sumV += source[sourceOffset + 1]
            }
            val u = sumU / count
            val v = sumV / count
            val x = (u * (geometry.width - 1)).roundToInt()
                .coerceIn(0, geometry.width - 1)
            val y = (v * (geometry.height - 1)).roundToInt()
                .coerceIn(0, geometry.height - 1)
            return y * geometry.width + x
        }
        var vertex = 0
        while (vertex < vertexCount) {
            val first = vertex
            val count = if (boundarySplats) 4 else 1
            val basisIndex = sampleIndex(first)
            repeat(count) { local ->
                val current = first + local
                val sourceOffset = current * SpatialSplatMeshBuilder.FLOATS_PER_VERTEX
                val targetOffset = current * LDI_FLOATS_PER_VERTEX
                val depth = source[sourceOffset + 2]
                result[targetOffset] = source[sourceOffset]
                result[targetOffset + 1] = source[sourceOffset + 1]
                result[targetOffset + 2] = depth
                val basis = geometry.motionBasis
                result[targetOffset + 3] = basis?.horizontalX?.get(basisIndex) ?: depth - 0.5f
                result[targetOffset + 4] = basis?.horizontalY?.get(basisIndex) ?: 0f
                result[targetOffset + 5] = basis?.verticalX?.get(basisIndex) ?: 0f
                result[targetOffset + 6] = basis?.verticalY?.get(basisIndex) ?: depth - 0.5f
            }
            vertex += count
        }
        return result
    }

    /** 每个实例使用独立刚性父层；互斥 label texture 决定该全屏平面实际可见的像素。 */

    /** 隐藏背景保持连续；其洞只允许来自派生数据没有覆盖，而不是网格删面。 */
    private fun buildConnectedBackgroundChunks(
        geometry: SpatialLdiLiteGeometry,
        extendCanvas: Boolean
    ): List<LdiMeshChunk> {
        val width = geometry.width
        val height = geometry.height
        val backgroundBasis = geometry.backgroundMotionBasis
        if (width < 2 || height < 2) return emptyList()
        val rowsPerChunk = minOf(
            MAX_ROWS_PER_MESH_CHUNK,
            MAX_UNSIGNED_SHORT_VERTICES / width
        )
        check(rowsPerChunk >= 2) { "空间网格宽度超出 GLES2 索引能力" }
        val result = mutableListOf<LdiMeshChunk>()
        val canvasPadding = if (extendCanvas) VNEXT_BACKGROUND_CANVAS_PADDING else 0f
        var firstRow = 0
        while (firstRow < height - 1) {
            val rowCount = minOf(rowsPerChunk, height - firstRow)
            val vertexCount = width * rowCount
            val background = FloatArray(vertexCount * LDI_FLOATS_PER_VERTEX)
            for (localY in 0 until rowCount) {
                val globalY = firstRow + localY
                val normalizedV = globalY.toFloat() / (height - 1)
                val v = when (globalY) {
                    0 -> normalizedV - canvasPadding
                    height - 1 -> normalizedV + canvasPadding
                    else -> normalizedV
                }
                for (x in 0 until width) {
                    val normalizedU = x.toFloat() / (width - 1)
                    val u = when (x) {
                        0 -> normalizedU - canvasPadding
                        width - 1 -> normalizedU + canvasPadding
                        else -> normalizedU
                    }
                    val sourceIndex = globalY * width + x
                    val targetIndex =
                        (localY * width + x) * LDI_FLOATS_PER_VERTEX
                    background[targetIndex] = u
                    background[targetIndex + 1] = v
                    background[targetIndex + 2] =
                        geometry.backgroundDepth[sourceIndex]
                    val depthCoefficient = geometry.backgroundDepth[sourceIndex] - 0.5f
                    background[targetIndex + 3] =
                        backgroundBasis?.horizontalX?.get(sourceIndex) ?: depthCoefficient
                    background[targetIndex + 4] =
                        backgroundBasis?.horizontalY?.get(sourceIndex) ?: 0f
                    background[targetIndex + 5] =
                        backgroundBasis?.verticalX?.get(sourceIndex) ?: 0f
                    background[targetIndex + 6] =
                        backgroundBasis?.verticalY?.get(sourceIndex) ?: depthCoefficient
                }
            }

            val backgroundIndices = ShortArray(
                (rowCount - 1) * (width - 1) * 6
            )
            var backgroundCount = 0

            fun append(target: ShortArray, offset: Int, value: Int) {
                target[offset] = value.toShort()
            }

            for (localY in 0 until rowCount - 1) {
                for (x in 0 until width - 1) {
                    val topLeft = localY * width + x
                    val topRight = topLeft + 1
                    val bottomLeft = topLeft + width
                    val bottomRight = bottomLeft + 1
                    // 背景层永远全连通：隐藏带外缘必然存在「远深度跳回表面深度」的
                    // 跳变，按深度差丢三角形会在带缘留洞，前景移开时两层都不覆盖，
                    // 露出清屏黑（D48 修复的锯齿黑块）。接缝处的斜坡拉伸大部分被前景
                    // 盖住，远好于露黑。
                    append(backgroundIndices, backgroundCount++, topLeft)
                    append(backgroundIndices, backgroundCount++, topRight)
                    append(backgroundIndices, backgroundCount++, bottomRight)
                    append(backgroundIndices, backgroundCount++, topLeft)
                    append(backgroundIndices, backgroundCount++, bottomRight)
                    append(backgroundIndices, backgroundCount++, bottomLeft)
                }
            }
            result += LdiMeshChunk(
                vertices = floatBuffer(background),
                indices = shortBuffer(backgroundIndices, backgroundCount)
            )
            firstRow += rowCount - 1
        }
        return result
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private fun shortBuffer(values: ShortArray, count: Int): ShortBuffer =
        ByteBuffer.allocateDirect(count * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(values, 0, count)
                position(0)
                limit(count)
            }

    private fun createDepthTexture(depth: SpatialDepthData): Int {
        val depthBytes = ByteBuffer.allocateDirect(depth.values.size)
        for (value in depth.values) {
            depthBytes.put((value.coerceIn(0f, 1f) * 255f).toInt().toByte())
        }
        depthBytes.rewind()
        val texture = createTexture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_LUMINANCE,
            depth.width,
            depth.height,
            0,
            GLES20.GL_LUMINANCE,
            GLES20.GL_UNSIGNED_BYTE,
            depthBytes
        )
        return texture
    }

    private fun createMotionBasisTexture(basis: SpatialScreenSpaceMotionBasis): Int {
        val bytes = ByteBuffer.allocateDirect(basis.width * basis.height * 4).apply {
            put(SpatialMotionBasisTexture.encode(basis))
            rewind()
        }
        val texture = createTexture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            basis.width,
            basis.height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            bytes
        )
        return texture
    }

    private fun createLuminanceTexture(
        values: ByteArray,
        width: Int,
        height: Int,
        linear: Boolean = true
    ): Int {
        require(values.size == width * height) { "亮度纹理尺寸不匹配" }
        val bytes = ByteBuffer.allocateDirect(values.size).apply {
            put(values)
            rewind()
        }
        val texture = createTexture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (!linear) {
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_NEAREST
            )
        }
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_LUMINANCE,
            width,
            height,
            0,
            GLES20.GL_LUMINANCE,
            GLES20.GL_UNSIGNED_BYTE,
            bytes
        )
        return texture
    }

    private fun createChartWeightTexture(
        renderData: SpatialSurfaceChartRenderData
    ): Int {
        check(chartFloatType != 0)
        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_NEAREST
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_NEAREST
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        val pixels = if (chartFloatType == GL_HALF_FLOAT_OES) {
            halfFloatBuffer(renderData.atlasWeights)
        } else {
            floatBuffer(renderData.atlasWeights)
        }
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_LUMINANCE,
            renderData.atlasWidth,
            renderData.atlasHeight,
            0,
            GLES20.GL_LUMINANCE,
            chartFloatType,
            pixels
        )
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            GLES20.glDeleteTextures(1, texture, 0)
            error("上传 chart 浮点权重图集失败：0x${error.toString(16)}")
        }
        return texture[0]
    }

    private fun ensureChartTargets(): ChartTargets? {
        chartTargets?.takeIf {
            it.width == surfaceWidth && it.height == surfaceHeight
        }?.let { return it }
        releaseChartTargets()
        if (chartFloatType == 0) return null
        return runCatching {
            val accumulation = createFloatingTarget(surfaceWidth, surfaceHeight)
            val coverage = try {
                createFloatingTarget(surfaceWidth, surfaceHeight)
            } catch (error: Throwable) {
                GLES20.glDeleteFramebuffers(
                    1,
                    intArrayOf(accumulation.framebuffer),
                    0
                )
                GLES20.glDeleteTextures(1, intArrayOf(accumulation.texture), 0)
                throw error
            }
            ChartTargets(
                width = surfaceWidth,
                height = surfaceHeight,
                accumulationTexture = accumulation.texture,
                accumulationFramebuffer = accumulation.framebuffer,
                coverageTexture = coverage.texture,
                coverageFramebuffer = coverage.framebuffer
            )
        }.onFailure { error ->
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            Log.e(
                "SpatialSurfaceCharts",
                "无法创建浮点累加目标，禁止降级为普通 alpha",
                error
            )
        }.getOrNull()?.also { chartTargets = it }
    }

    private data class FloatingTarget(val texture: Int, val framebuffer: Int)

    private fun createFloatingTarget(width: Int, height: Int): FloatingTarget {
        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_NEAREST
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_NEAREST
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            chartFloatType,
            null
        )
        val framebuffer = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffer, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            texture[0],
            0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glDeleteFramebuffers(1, framebuffer, 0)
            GLES20.glDeleteTextures(1, texture, 0)
            error("浮点 framebuffer 不完整：0x${status.toString(16)}")
        }
        return FloatingTarget(texture[0], framebuffer[0])
    }

    private fun releaseChartTargets() {
        chartTargets?.let { targets ->
            GLES20.glDeleteFramebuffers(
                2,
                intArrayOf(
                    targets.accumulationFramebuffer,
                    targets.coverageFramebuffer
                ),
                0
            )
            GLES20.glDeleteTextures(
                2,
                intArrayOf(targets.accumulationTexture, targets.coverageTexture),
                0
            )
        }
        chartTargets = null
    }

    private fun ensureSurfelTargets(): SurfelTargets? {
        surfelTargets?.takeIf {
            it.width == surfaceWidth && it.height == surfaceHeight
        }?.let { return it }
        releaseSurfelTargets()
        return createOffscreenTargets(surfaceWidth, surfaceHeight, "SpatialDepthSurfels")
            ?.also { surfelTargets = it }
    }

    /**
     * 网格超采样目标。尺寸是**画面矩形**（不是整个 surface）乘以超采样倍数——照片在
     * 竖屏上是信箱式居中，按整个 surface 开会白白多出一倍多的像素。
     *
     * 倍数按显存与 `GL_MAX_TEXTURE_SIZE` 自适应降档；一路降到 1 仍失败就返回 null，
     * 由调用方回落到直接上屏（不改变既有行为，只是没有超采样与闭缝）。
     */
    private fun ensureMeshTargets(rectWidth: Int, rectHeight: Int): SurfelTargets? {
        if (rectWidth < 2 || rectHeight < 2) return null
        val maximumTexture = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maximumTexture, 0)
        val textureLimit = maximumTexture[0].coerceAtLeast(1024)
        var factor = MESH_SUPER_SAMPLE
        while (factor >= 1) {
            val width = rectWidth * factor
            val height = rectHeight * factor
            if (
                maxOf(width, height) <= textureLimit &&
                width.toLong() * height <= MAX_MESH_TARGET_PIXELS
            ) {
                meshTargets?.takeIf { it.width == width && it.height == height }
                    ?.let { return it }
                releaseMeshTargets()
                val created = createOffscreenTargets(width, height, "SpatialMeshResolve")
                if (created != null) {
                    meshTargets = created
                    return created
                }
            }
            factor--
        }
        return null
    }

    private fun releaseMeshTargets() {
        meshTargets?.let { targets ->
            GLES20.glDeleteRenderbuffers(1, intArrayOf(targets.depthRenderbuffer), 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(targets.framebuffer), 0)
            GLES20.glDeleteTextures(1, intArrayOf(targets.colorTexture), 0)
        }
        meshTargets = null
    }

    private fun createOffscreenTargets(
        targetWidth: Int,
        targetHeight: Int,
        tag: String
    ): SurfelTargets? {
        return runCatching {
            val colorTexture = IntArray(1)
            val framebuffer = IntArray(1)
            val depthRenderbuffer = IntArray(1)
            try {
                GLES20.glGenTextures(1, colorTexture, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexture[0])
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_NEAREST
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE
                )
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_RGBA,
                    targetWidth,
                    targetHeight,
                    0,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    null
                )
                GLES20.glGenFramebuffers(1, framebuffer, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    colorTexture[0],
                    0
                )
                GLES20.glGenRenderbuffers(1, depthRenderbuffer, 0)
                GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depthRenderbuffer[0])
                GLES20.glRenderbufferStorage(
                    GLES20.GL_RENDERBUFFER,
                    GLES20.GL_DEPTH_COMPONENT16,
                    targetWidth,
                    targetHeight
                )
                GLES20.glFramebufferRenderbuffer(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_DEPTH_ATTACHMENT,
                    GLES20.GL_RENDERBUFFER,
                    depthRenderbuffer[0]
                )
                val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    "framebuffer 不完整：0x${status.toString(16)}"
                }
                SurfelTargets(
                    width = targetWidth,
                    height = targetHeight,
                    colorTexture = colorTexture[0],
                    framebuffer = framebuffer[0],
                    depthRenderbuffer = depthRenderbuffer[0]
                )
            } catch (error: Throwable) {
                if (depthRenderbuffer[0] != 0) {
                    GLES20.glDeleteRenderbuffers(1, depthRenderbuffer, 0)
                }
                if (framebuffer[0] != 0) GLES20.glDeleteFramebuffers(1, framebuffer, 0)
                if (colorTexture[0] != 0) GLES20.glDeleteTextures(1, colorTexture, 0)
                throw error
            }
        }.onFailure { error ->
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            Log.e(tag, "无法创建 ${targetWidth}x${targetHeight} 的 RGBA8＋深度目标", error)
        }.getOrNull()
    }

    private fun releaseSurfelTargets() {
        surfelTargets?.let { targets ->
            GLES20.glDeleteRenderbuffers(1, intArrayOf(targets.depthRenderbuffer), 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(targets.framebuffer), 0)
            GLES20.glDeleteTextures(1, intArrayOf(targets.colorTexture), 0)
        }
        surfelTargets = null
    }

    private fun resolveChartFloatType(extensions: String): Int = when {
        "GL_OES_texture_half_float" in extensions &&
            "GL_EXT_color_buffer_half_float" in extensions -> GL_HALF_FLOAT_OES
        "GL_OES_texture_float" in extensions &&
            "GL_EXT_color_buffer_float" in extensions -> GLES20.GL_FLOAT
        else -> 0
    }

    private fun buildChartVertices(
        renderData: SpatialSurfaceChartRenderData
    ): FloatArray {
        val result = FloatArray(
            renderData.quads.size * CHART_VERTICES_PER_QUAD * CHART_FLOATS_PER_VERTEX
        )
        var output = 0
        fun append(
            quad: SpatialSurfaceChartRenderData.ChartQuad,
            sourceU: Float,
            sourceV: Float,
            atlasU: Float,
            atlasV: Float
        ) {
            result[output++] = sourceU
            result[output++] = sourceV
            result[output++] = atlasU
            result[output++] = atlasV
            result[output++] = quad.horizontalX
            result[output++] = quad.horizontalY
            result[output++] = quad.verticalX
            result[output++] = quad.verticalY
            result[output++] = quad.zWeight
        }
        for (quad in renderData.quads) {
            append(quad, quad.sourceLeft, quad.sourceTop, quad.atlasLeft, quad.atlasTop)
            append(quad, quad.sourceRight, quad.sourceTop, quad.atlasRight, quad.atlasTop)
            append(
                quad,
                quad.sourceRight,
                quad.sourceBottom,
                quad.atlasRight,
                quad.atlasBottom
            )
            append(quad, quad.sourceLeft, quad.sourceTop, quad.atlasLeft, quad.atlasTop)
            append(
                quad,
                quad.sourceRight,
                quad.sourceBottom,
                quad.atlasRight,
                quad.atlasBottom
            )
            append(
                quad,
                quad.sourceLeft,
                quad.sourceBottom,
                quad.atlasLeft,
                quad.atlasBottom
            )
        }
        check(output == result.size)
        return result
    }

    private fun buildSurfelVertices(surfels: SpatialDepthSurfelData): FloatArray {
        val scalarMinimum = surfels.motionScalars.minOrNull() ?: 0f
        val scalarMaximum = surfels.motionScalars.maxOrNull() ?: scalarMinimum
        val inverseSpan = 1f / (scalarMaximum - scalarMinimum).coerceAtLeast(1e-6f)
        val result = FloatArray(
            surfels.width * surfels.height * SURFEL_FLOATS_PER_VERTEX
        )
        var output = 0
        for (y in 0 until surfels.height) {
            for (x in 0 until surfels.width) {
                val index = y * surfels.width + x
                val scalar = surfels.motionScalars[index]
                result[output++] = (x + 0.5f) / surfels.width
                result[output++] = (y + 0.5f) / surfels.height
                result[output++] = scalar
                result[output++] = (scalar - scalarMinimum) * inverseSpan
            }
        }
        check(output == result.size)
        return result
    }

    private fun halfFloatBuffer(values: FloatArray): ShortBuffer =
        ByteBuffer.allocateDirect(values.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                values.forEach { put(floatToHalf(it)) }
                position(0)
            }

    private fun floatToHalf(value: Float): Short {
        val bits = java.lang.Float.floatToIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        var exponent = ((bits ushr 23) and 0xff) - 127 + 15
        var mantissa = bits and 0x7fffff
        val result = when {
            exponent <= 0 -> {
                if (exponent < -10) {
                    sign
                } else {
                    mantissa = (mantissa or 0x800000) ushr (1 - exponent)
                    sign or ((mantissa + 0x1000) ushr 13)
                }
            }
            exponent >= 31 -> sign or 0x7c00
            else -> {
                mantissa += 0x1000
                if (mantissa and 0x800000 != 0) {
                    mantissa = 0
                    exponent++
                }
                if (exponent >= 31) sign or 0x7c00 else
                    sign or (exponent shl 10) or (mantissa ushr 13)
            }
        }
        return result.toShort()
    }

    private fun createTexture(): Int {
        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        return texture[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vertex)
        GLES20.glAttachShader(result, fragment)
        GLES20.glLinkProgram(result)
        val status = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "空间照片着色器链接失败：${GLES20.glGetProgramInfoLog(result)}"
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return result
    }

    private fun createOptionalProgram(
        vertexSource: String,
        fragmentSource: String,
        label: String
    ): Int = runCatching {
        createProgram(vertexSource, fragmentSource)
    }.onFailure { error ->
        Log.e(
            "SpatialDepthSurfels",
            "$label shader 不可用；该场景只允许静态中心图回退",
            error
        )
    }.getOrDefault(0)

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "空间照片着色器编译失败：${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    private data class Scene(
        val bitmap: Bitmap,
        val depth: SpatialDepthData,
        val ldiLite: SpatialLdiLiteData?
    )

    private data class LdiMesh(
        val connectedSurfaceChunks: List<LdiMeshChunk>,
        val boundarySurfaceChunks: List<LdiMeshChunk>,
        val backgroundChunks: List<LdiMeshChunk>,
    )

    private data class LdiMeshChunk(
        val vertices: FloatBuffer,
        val indices: ShortBuffer,
        val ownershipLabel: Float = 0f
    )

    private data class ChartTargets(
        val width: Int,
        val height: Int,
        val accumulationTexture: Int,
        val accumulationFramebuffer: Int,
        val coverageTexture: Int,
        val coverageFramebuffer: Int
    )

    private data class SurfelTargets(
        val width: Int,
        val height: Int,
        val colorTexture: Int,
        val framebuffer: Int,
        val depthRenderbuffer: Int
    )

    companion object {
        private const val STRIDE_BYTES = 4 * Float.SIZE_BYTES
        private const val LDI_FLOATS_PER_VERTEX = 7
        private const val LDI_VERTEX_STRIDE_BYTES =
            LDI_FLOATS_PER_VERTEX * Float.SIZE_BYTES
        private const val CHART_FLOATS_PER_VERTEX = 9
        private const val CHART_VERTEX_STRIDE_BYTES =
            CHART_FLOATS_PER_VERTEX * Float.SIZE_BYTES
        private const val CHART_VERTICES_PER_QUAD = 6
        private const val SURFEL_FLOATS_PER_VERTEX = 4
        private const val SURFEL_VERTEX_STRIDE_BYTES =
            SURFEL_FLOATS_PER_VERTEX * Float.SIZE_BYTES
        private const val GL_HALF_FLOAT_OES = 0x8D61
        /** 网格超采样倍数上限；显存不够时逐档降到 1。与网页端的 SS=2 同口径。 */
        private const val MESH_SUPER_SAMPLE = 2
        /** 超采样目标的像素预算：2880x3840 ≈ 1106 万，RGBA8+深度约 66 MB。 */
        private const val MAX_MESH_TARGET_PIXELS = 12_000_000L
        private const val MAX_ROWS_PER_MESH_CHUNK = 96
        private const val MAX_UNSIGNED_SHORT_VERTICES = 65_535
        // 背景板向原画框外扩展，承接最大 centered-depth 位移与采样保护；只影响
        // vNext 背景 pass，参考视点的原图表面仍保持完整原 FOV。
        private const val VNEXT_BACKGROUND_CANVAS_PADDING = 0.07f
        // 中心附近逐渐启用 matting 显露，避免 reference frame 被补图污染或过中心突跳。
        private const val VNEXT_ALPHA_REVEAL_RADIUS = 0.08f
        private const val MPI_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform vec2 uScale;
            varying vec2 vTexCoord;
            void main() {
                vTexCoord = aTexCoord;
                gl_Position = vec4(aPosition * uScale, 0.0, 1.0);
            }
        """

        private const val MPI_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uColor;
            uniform vec2 uShift;
            uniform vec2 uInset;
            void main() {
                vec2 uv = vec2(0.5) +
                    (vTexCoord - vec2(0.5)) * (vec2(1.0) - 2.0 * uInset) + uShift;
                gl_FragColor = texture2D(
                    uColor,
                    clamp(uv, vec2(0.001), vec2(0.999))
                );
            }
        """

        private val VERTICES = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f
        )

        private const val VERTEX_SHADER = """
            uniform vec2 uScale;
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;

            void main() {
                gl_Position = vec4(aPosition * uScale, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        private const val CHART_VERTEX_SHADER = """
            uniform vec2 uScale;
            uniform vec2 uParallaxMotion;
            uniform vec2 uCoverMargin;
            attribute vec2 aSourceUv;
            attribute vec2 aWeightUv;
            attribute vec2 aMotionBasisX;
            attribute vec2 aMotionBasisY;
            attribute float aZWeight;
            varying vec2 vSourceUv;
            varying vec2 vWeightUv;
            varying float vZWeight;

            void main() {
                vec2 targetUv =
                    aSourceUv -
                    uParallaxMotion.x * aMotionBasisX -
                    uParallaxMotion.y * aMotionBasisY;
                vec2 canvasUv =
                    (targetUv - uCoverMargin) /
                    (vec2(1.0) - 2.0 * uCoverMargin);
                vec2 position = vec2(
                    canvasUv.x * 2.0 - 1.0,
                    1.0 - canvasUv.y * 2.0
                );
                gl_Position = vec4(position * uScale, 0.0, 1.0);
                vSourceUv = aSourceUv;
                vWeightUv = aWeightUv;
                vZWeight = aZWeight;
            }
        """

        private const val CHART_FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D uColor;
            uniform sampler2D uWeights;
            uniform vec2 uAtlasSize;
            uniform float uCoveragePass;
            varying vec2 vSourceUv;
            varying vec2 vWeightUv;
            varying float vZWeight;

            float sampleWeight(vec2 uv) {
                vec2 position = uv * uAtlasSize - vec2(0.5);
                vec2 base = floor(position);
                vec2 fraction = fract(position);
                vec2 maximum = uAtlasSize - vec2(1.0);
                vec2 first = clamp(base, vec2(0.0), maximum);
                vec2 second = clamp(base + vec2(1.0), vec2(0.0), maximum);
                float topLeft = texture2D(
                    uWeights,
                    (first + vec2(0.5)) / uAtlasSize
                ).r;
                float topRight = texture2D(
                    uWeights,
                    (vec2(second.x, first.y) + vec2(0.5)) / uAtlasSize
                ).r;
                float bottomLeft = texture2D(
                    uWeights,
                    (vec2(first.x, second.y) + vec2(0.5)) / uAtlasSize
                ).r;
                float bottomRight = texture2D(
                    uWeights,
                    (second + vec2(0.5)) / uAtlasSize
                ).r;
                return mix(
                    mix(topLeft, topRight, fraction.x),
                    mix(bottomLeft, bottomRight, fraction.x),
                    fraction.y
                );
            }

            void main() {
                float weight = sampleWeight(vWeightUv);
                if (uCoveragePass > 0.5) {
                    gl_FragColor = vec4(weight, 0.0, 0.0, 0.0);
                } else {
                    vec3 color = texture2D(uColor, vSourceUv).rgb;
                    float weightedZ = weight * vZWeight;
                    gl_FragColor = vec4(color * weightedZ, weightedZ);
                }
            }
        """

        private const val CHART_COMPOSITE_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;

            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
            }
        """

        private const val CHART_COMPOSITE_FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D uAccumulation;
            uniform sampler2D uCoverage;
            uniform float uCoverageLow;
            uniform float uCoverageHigh;
            varying vec2 vTexCoord;

            void main() {
                vec4 accumulation = texture2D(uAccumulation, vTexCoord);
                float coverage = texture2D(uCoverage, vTexCoord).r;
                float alpha = smoothstep(uCoverageLow, uCoverageHigh, coverage);
                vec3 foreground = accumulation.rgb / max(accumulation.a, 0.000001);
                gl_FragColor = vec4(foreground * alpha, alpha);
            }
        """

        private const val SURFEL_VERTEX_SHADER = """
            uniform vec2 uScale;
            uniform vec2 uParallaxMotion;
            uniform vec2 uCoverMargin;
            uniform vec2 uScalarToUv;
            uniform float uPointSize;
            attribute vec2 aSourceUv;
            attribute float aMotionScalar;
            attribute float aSurfaceDepth;
            varying vec2 vSourceUv;
            varying vec2 vTargetScreenUv;

            void main() {
                vec2 targetUv =
                    aSourceUv -
                    uParallaxMotion * aMotionScalar * uScalarToUv;
                vec2 canvasUv =
                    (targetUv - uCoverMargin) /
                    (vec2(1.0) - 2.0 * uCoverMargin);
                vec2 position = vec2(
                    canvasUv.x * 2.0 - 1.0,
                    1.0 - canvasUv.y * 2.0
                );
                gl_Position = vec4(
                    position * uScale,
                    1.0 - 2.0 * aSurfaceDepth,
                    1.0
                );
                gl_PointSize = uPointSize;
                vSourceUv = aSourceUv;
                vTargetScreenUv = vec2(0.5) + (canvasUv - vec2(0.5)) * uScale;
            }
        """

        private const val SURFEL_FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D uColor;
            uniform vec2 uScale;
            uniform vec2 uCoverMargin;
            uniform vec2 uTargetSize;
            varying vec2 vSourceUv;
            varying vec2 vTargetScreenUv;

            void main() {
                vec2 fragmentScreenUv = vec2(
                    gl_FragCoord.x / uTargetSize.x,
                    1.0 - gl_FragCoord.y / uTargetSize.y
                );
                vec2 canvasOffset =
                    (fragmentScreenUv - vTargetScreenUv) /
                    max(uScale, vec2(0.000001));
                vec2 sourceUv = vSourceUv +
                    canvasOffset * (vec2(1.0) - 2.0 * uCoverMargin);
                gl_FragColor = vec4(
                    texture2D(
                        uColor,
                        clamp(sourceUv, vec2(0.001), vec2(0.999))
                    ).rgb,
                    1.0
                );
            }
        """


        /**
         * 超采样分辨 + 窄缝闭合。
         *
         * 前景表面画在一张清成 `(0,0,0,0)` 的超采样目标里，颜色是**预乘**的，所以
         * alpha 直接就是覆盖率。这一遍做两件事：
         *
         * 1. 盒式降采样：把 N×N 个超采样点平均回一个输出像素——剪影的阶梯被摊成灰阶，
         *    这正是网页端 SS=2 的作用；
         * 2. 窄缝闭合：某个超采样点没被覆盖时，沿四个方向各探一段，只有**两侧都被覆盖**
         *    才判定为缝并借用邻居的颜色。单侧有覆盖是真正的边界，必须留着，否则前景会
         *    向外糊出一圈假边。
         *
         * 半径以超采样后的像素计，与网页端「窄缝闭合半径 8 px（超采样后）」同口径。
         */
        private const val MESH_RESOLVE_FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D uSurface;
            uniform vec2 uSourceTexel;
            uniform vec2 uBoxStep;
            uniform float uBoxSamples;
            // 离屏目标只覆盖**画面矩形**，而这一遍画的是全屏四边形，所以要把屏幕 uv
            // 重映射到矩形内部；矩形之外直接丢弃，交给已经画好的底板。
            uniform vec2 uRectOrigin;
            uniform vec2 uRectSize;
            varying vec2 vTexCoord;

            vec4 sampleAt(vec2 uv) {
                return texture2D(uSurface, clamp(uv, vec2(0.0), vec2(1.0)));
            }

            // 只在两侧都有覆盖时才认定是缝；返回借来的预乘颜色，无效时 alpha 为 0。
            vec4 closeGap(vec2 uv) {
                vec4 filled = vec4(0.0);
                for (int axis = 0; axis < 2; axis++) {
                    vec2 step = axis == 0
                        ? vec2(uSourceTexel.x, 0.0)
                        : vec2(0.0, uSourceTexel.y);
                    vec4 forward = vec4(0.0);
                    vec4 backward = vec4(0.0);
                    for (int i = 1; i <= 8; i++) {
                        float d = float(i);
                        if (forward.a < 0.5) {
                            vec4 c = sampleAt(uv + step * d);
                            if (c.a >= 0.5) forward = c;
                        }
                        if (backward.a < 0.5) {
                            vec4 c = sampleAt(uv - step * d);
                            if (c.a >= 0.5) backward = c;
                        }
                    }
                    if (forward.a >= 0.5 && backward.a >= 0.5) {
                        vec4 mixed = (forward + backward) * 0.5;
                        if (mixed.a > filled.a) filled = mixed;
                    }
                }
                return filled;
            }

            void main() {
                vec2 local = (vTexCoord - uRectOrigin) / uRectSize;
                if (
                    local.x < 0.0 || local.x > 1.0 ||
                    local.y < 0.0 || local.y > 1.0
                ) {
                    gl_FragColor = vec4(0.0);
                    return;
                }
                vec4 accumulated = vec4(0.0);
                for (int y = 0; y < 4; y++) {
                    if (float(y) >= uBoxSamples) break;
                    for (int x = 0; x < 4; x++) {
                        if (float(x) >= uBoxSamples) break;
                        vec2 uv = local +
                            vec2(float(x) + 0.5, float(y) + 0.5) * uBoxStep -
                            uBoxStep * uBoxSamples * 0.5;
                        vec4 c = sampleAt(uv);
                        if (c.a < 0.5) {
                            vec4 repaired = closeGap(uv);
                            if (repaired.a >= 0.5) c = repaired;
                        }
                        accumulated += c;
                    }
                }
                gl_FragColor = accumulated / (uBoxSamples * uBoxSamples);
            }
        """

        private const val SURFEL_COMPOSITE_FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D uSurfels;
            uniform vec2 uTargetTexel;
            varying vec2 vTexCoord;

            float occupied(vec4 value) {
                return step(0.5, value.a);
            }

            void main() {
                vec4 center = texture2D(uSurfels, vTexCoord);
                if (occupied(center) > 0.5) {
                    gl_FragColor = vec4(center.rgb, 1.0);
                    return;
                }
                vec4 left = texture2D(
                    uSurfels,
                    vTexCoord - vec2(uTargetTexel.x, 0.0)
                );
                vec4 right = texture2D(
                    uSurfels,
                    vTexCoord + vec2(uTargetTexel.x, 0.0)
                );
                vec4 top = texture2D(
                    uSurfels,
                    vTexCoord - vec2(0.0, uTargetTexel.y)
                );
                vec4 bottom = texture2D(
                    uSurfels,
                    vTexCoord + vec2(0.0, uTargetTexel.y)
                );
                vec4 topLeft = texture2D(
                    uSurfels,
                    vTexCoord - uTargetTexel
                );
                vec4 topRight = texture2D(
                    uSurfels,
                    vTexCoord + vec2(uTargetTexel.x, -uTargetTexel.y)
                );
                vec4 bottomLeft = texture2D(
                    uSurfels,
                    vTexCoord + vec2(-uTargetTexel.x, uTargetTexel.y)
                );
                vec4 bottomRight = texture2D(
                    uSurfels,
                    vTexCoord + uTargetTexel
                );
                float paired = max(
                    max(
                        occupied(left) * occupied(right),
                        occupied(top) * occupied(bottom)
                    ),
                    max(
                        occupied(topLeft) * occupied(bottomRight),
                        occupied(topRight) * occupied(bottomLeft)
                    )
                );
                if (paired < 0.5) {
                    gl_FragColor = vec4(0.0);
                    return;
                }
                vec4 selected = left;
                if (occupied(selected) < 0.5) selected = right;
                if (occupied(selected) < 0.5) selected = top;
                if (occupied(selected) < 0.5) selected = bottom;
                if (occupied(selected) < 0.5) selected = topLeft;
                if (occupied(selected) < 0.5) selected = topRight;
                if (occupied(selected) < 0.5) selected = bottomLeft;
                if (occupied(selected) < 0.5) selected = bottomRight;
                gl_FragColor = vec4(selected.rgb, 1.0);
            }
        """

        private const val LDI_VERTEX_SHADER = """
            uniform vec2 uScale;
            uniform vec2 uViewpoint;
            uniform vec2 uParallaxMotion;
            uniform vec2 uCoverMargin;
            uniform float uRigidPan;
            attribute vec2 aTexCoord;
            attribute float aDepth;
            attribute vec2 aMotionBasisX;
            attribute vec2 aMotionBasisY;
            varying vec2 vTexCoord;

            void main() {
                vec2 targetUv =
                    aTexCoord -
                    uParallaxMotion.x * aMotionBasisX -
                    uParallaxMotion.y * aMotionBasisY -
                    uViewpoint * uRigidPan;
                vec2 canvasUv =
                    (targetUv - uCoverMargin) /
                    (vec2(1.0) - 2.0 * uCoverMargin);
                vec2 position = vec2(
                    canvasUv.x * 2.0 - 1.0,
                    1.0 - canvasUv.y * 2.0
                );
                gl_Position = vec4(
                    position * uScale,
                    1.0 - 2.0 * aDepth,
                    1.0
                );
                vTexCoord = aTexCoord;
            }
        """

        private const val LDI_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uColor;
            uniform sampler2D uDepth;
            uniform sampler2D uSurfaceAlpha;
            uniform vec2 uViewpoint;
            uniform vec2 uDepthTexel;
            uniform vec2 uAlphaTexel;
            uniform float uStrength;
            uniform float uReliefGain;
            uniform float uMaxRelief;
            uniform float uUseRelief;
            uniform float uUseSurfaceAlpha;
            varying vec2 vTexCoord;

            void main() {
                vec4 color = texture2D(uColor, vTexCoord);
                float originalDepth = texture2D(uDepth, vTexCoord).r;
                float depthRight = texture2D(
                    uDepth,
                    clamp(
                        vTexCoord + vec2(uDepthTexel.x, 0.0),
                        vec2(0.001),
                        vec2(0.999)
                    )
                ).r;
                float depthDown = texture2D(
                    uDepth,
                    clamp(
                        vTexCoord + vec2(0.0, uDepthTexel.y),
                        vec2(0.001),
                        vec2(0.999)
                    )
                ).r;
                float relief = clamp(
                    dot(
                        vec2(
                            depthRight - originalDepth,
                            depthDown - originalDepth
                        ),
                        uViewpoint
                    ) * uReliefGain * uStrength,
                    -uMaxRelief,
                    uMaxRelief
                );
                color.rgb *= 1.0 + relief * uUseRelief;
                // 纹理 alpha 是高分辨率轮廓，但普通 fragment 在每个屏幕像素只取一次
                // 样本，斜向发丝仍会暴露栅格台阶。四点旋转采样只重建透明边缘覆盖率，
                // 不对脸部或其他不透明内容做全屏后处理。
                vec2 alphaAaOffset = uAlphaTexel * 0.4;
                float alphaTopLeft = texture2D(
                    uSurfaceAlpha,
                    clamp(vTexCoord - alphaAaOffset, vec2(0.001), vec2(0.999))
                ).r;
                float alphaTopRight = texture2D(
                    uSurfaceAlpha,
                    clamp(
                        vTexCoord + vec2(alphaAaOffset.x, -alphaAaOffset.y),
                        vec2(0.001),
                        vec2(0.999)
                    )
                ).r;
                float alphaBottomLeft = texture2D(
                    uSurfaceAlpha,
                    clamp(
                        vTexCoord + vec2(-alphaAaOffset.x, alphaAaOffset.y),
                        vec2(0.001),
                        vec2(0.999)
                    )
                ).r;
                float alphaBottomRight = texture2D(
                    uSurfaceAlpha,
                    clamp(vTexCoord + alphaAaOffset, vec2(0.001), vec2(0.999))
                ).r;
                float sampledDisplayAlpha = 0.25 * (
                    alphaTopLeft +
                    alphaTopRight +
                    alphaBottomLeft +
                    alphaBottomRight
                );
                float sampledSurfaceAlpha = sampledDisplayAlpha;
                // 实例标签只负责互斥归属门控，不能作为覆盖率参与合成。标签纹理比
                // ownership alpha 低一档分辨率；把四点标签命中率乘进 alpha 会把
                // MODNet/EdgeTAM 的连续软边重新量化为五档台阶。
                float surfaceAlpha = mix(
                    1.0,
                    sampledSurfaceAlpha,
                    uUseSurfaceAlpha
                );
                vec2 displayAlphaGradient = vec2(
                    0.5 * (
                        alphaTopRight + alphaBottomRight -
                        alphaTopLeft - alphaBottomLeft
                    ),
                    0.5 * (
                        alphaBottomLeft + alphaBottomRight -
                        alphaTopLeft - alphaTopRight
                    )
                );
                vec2 alphaGradient = displayAlphaGradient;
                float alphaGradientLength = length(alphaGradient);
                vec2 inwardDirection = alphaGradient / max(alphaGradientLength, 0.0001);
                vec3 inwardColor = texture2D(
                    uColor,
                    clamp(
                        vTexCoord + inwardDirection * uAlphaTexel * 1.5,
                        vec2(0.001),
                        vec2(0.999)
                    )
                ).rgb;
                // 去污必须估计原图中的局部外侧背景。生成式隐藏背景与原图曝光、纹理不一致，
                // 直接拿它反解前景会把模型误差放大成发丝白边或暗边。
                vec3 exteriorColor = texture2D(
                    uColor,
                    clamp(
                        vTexCoord - inwardDirection * uAlphaTexel * 3.0,
                        vec2(0.001),
                        vec2(0.999)
                    )
                ).rgb;
                vec3 decontaminated = clamp(
                    (
                        color.rgb - (1.0 - surfaceAlpha) * exteriorColor
                    ) / max(surfaceAlpha, 0.08),
                    0.0,
                    1.0
                );
                float inwardFallback =
                    (1.0 - smoothstep(0.12, 0.72, surfaceAlpha)) *
                    smoothstep(0.002, 0.08, alphaGradientLength);
                vec3 foregroundEstimate = mix(
                    decontaminated,
                    inwardColor,
                    inwardFallback
                );
                vec4 mattedColor = vec4(
                    foregroundEstimate * surfaceAlpha,
                    surfaceAlpha
                );
                gl_FragColor = mix(color, mattedColor, uUseSurfaceAlpha);
            }
        """

        /**
         * 位移使用斜率受限的渲染深度，避免强视差在物体轮廓处形成 UV 折返；
         * 原始深度只参与有界明暗塑形，保留局部层次而不重新引入几何撕裂。
         */
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uColor;
            uniform sampler2D uDepth;
            uniform sampler2D uRenderDepth;
            uniform sampler2D uMotionBasis;
            uniform vec2 uViewpoint;
            uniform vec2 uParallaxMotion;
            uniform vec2 uDepthTexel;
            uniform vec2 uCoverMargin;
            uniform float uRigidPan;
            uniform float uStrength;
            uniform float uReliefGain;
            uniform float uMaxRelief;
            uniform float uUseMotionBasis;
            varying vec2 vTexCoord;

            vec2 inverseMotionWarp(vec2 targetUv) {
                vec2 sourceUv = targetUv;
                for (int iteration = 0; iteration < 4; iteration++) {
                    vec4 packedBasis = texture2D(
                        uMotionBasis,
                        clamp(sourceUv, vec2(0.001), vec2(0.999))
                    );
                    vec4 basis = packedBasis * 2.0 - 1.0;
                    vec2 displacement =
                        uParallaxMotion.x * basis.rg +
                        uParallaxMotion.y * basis.ba;
                    sourceUv = targetUv + displacement;
                }
                return clamp(sourceUv, vec2(0.001), vec2(0.999));
            }

            void main() {
                vec2 baseUv = uCoverMargin +
                    vTexCoord * (vec2(1.0) - 2.0 * uCoverMargin);
                vec2 cameraUv = baseUv + uViewpoint * uRigidPan;
                vec2 sampleUv;
                if (uUseMotionBasis > 0.5) {
                    sampleUv = inverseMotionWarp(cameraUv);
                } else {
                    float renderDepth = texture2D(uRenderDepth, cameraUv).r;
                    sampleUv = clamp(
                        cameraUv +
                            uParallaxMotion * (renderDepth - 0.5),
                        vec2(0.001),
                        vec2(0.999)
                    );
                }

                vec4 color = texture2D(uColor, sampleUv);
                float originalDepth = texture2D(uDepth, sampleUv).r;
                float depthRight = texture2D(
                    uDepth,
                    clamp(sampleUv + vec2(uDepthTexel.x, 0.0), vec2(0.001), vec2(0.999))
                ).r;
                float depthDown = texture2D(
                    uDepth,
                    clamp(sampleUv + vec2(0.0, uDepthTexel.y), vec2(0.001), vec2(0.999))
                ).r;
                float relief = clamp(
                    dot(
                        vec2(
                            depthRight - originalDepth,
                            depthDown - originalDepth
                        ),
                        uViewpoint
                    )
                        * uReliefGain * uStrength,
                    -uMaxRelief,
                    uMaxRelief
                );
                color.rgb *= 1.0 + relief;
                gl_FragColor = color;
            }
        """
    }
}
