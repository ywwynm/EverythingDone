package com.ywwynm.everythingdone.spatial

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialPhotoRendererSourceTest {

    private val source by lazy {
        readSource(
            "app/src/main/java/com/ywwynm/everythingdone/spatial/SpatialPhotoRenderer.kt"
        )
    }

    private val mpiSource by lazy {
        readSource(
            "app/src/main/java/com/ywwynm/everythingdone/spatial/SpatialMpiBuilder.kt"
        )
    }

    private val ldiBuilderSource by lazy {
        readSource(
            "app/src/main/java/com/ywwynm/everythingdone/spatial/SpatialLdiLiteBuilder.kt"
        )
    }

    private val vNextBuilderSource by lazy {
        readSource(
            "app/src/main/java/com/ywwynm/everythingdone/spatial/SpatialVNextBuilder.kt"
        )
    }

    private val derivativeStoreSource by lazy {
        readSource(
            "app/src/main/java/com/ywwynm/everythingdone/spatial/SpatialDerivativeStore.kt"
        )
    }

    private val imageViewerSource by lazy {
        readSource(
            "app/src/main/java/com/ywwynm/everythingdone/activities/ImageViewerActivity.kt"
        )
    }

    private val spatialSettingsSource by lazy {
        readSource(
            "app/src/main/java/com/ywwynm/everythingdone/activities/SpatialPhotoSettingsActivity.kt"
        )
    }

    @Test
    fun `取景边距为每档幅度常量且不再有零视点直通分支`() {
        // P1（design-2026-08-03）：恒定边距换零呼吸；直通分支会在过中心产生缩放突跳。
        assertFalse(source.contains("SpatialSourceLock.isReferenceViewpoint("))
        assertFalse(source.contains("drawSourceLockedReference"))
        assertTrue(source.contains("SpatialSourceLock.coverMargin(amplitude)"))
        assertTrue(source.contains("uniform vec2 uCoverMargin;"))
        assertFalse(source.contains("uOverscan"))
    }

    @Test
    fun `全局连续位移场必须由固定点inverse warp消费而不是切断前向网格`() {
        assertTrue(source.contains("uniform sampler2D uMotionBasis;"))
        assertTrue(source.contains("vec2 inverseMotionWarp(vec2 targetUv)"))
        assertTrue(source.contains("iteration < 4"))
        assertTrue(source.contains("sourceUv = targetUv + displacement"))
        assertTrue(source.contains("activeLdiRenderer.usesGlobalInverseWarp"))
        assertTrue(source.contains("createMotionBasisTexture("))
    }

    @Test
    fun `立体可见层必须使用连续面加断层边界splat的混合拓扑`() {
        // P2（design-2026-08-03）：对象回归连续表面——无独立刚性平面、无 base 排除、
        // 无标签纹理；实例只在生成期作断边先验。
        assertTrue(source.contains("SpatialHybridMeshBuilder.build("))
        // 立体网格必须用几何阶段全程 surfaceDepth（v18 残差压缩器会塌缩视差）。
        // 按限定调用点名，解释性注释可以提及该函数名。
        assertFalse(
            source.contains("SpatialRenderDepthStabilizer.stabilizeForSplat(")
        )
        assertTrue(source.contains("val surfaceDepth = geometry.surfaceDepth"))
        assertTrue(source.contains("safeParallaxMotion(amplitude, ldiGradientProfile)"))
        assertTrue(source.contains("excludedSamples = null"))
        assertFalse(source.contains("SpatialOwnershipLayer.buildGraphFromMask("))
        assertFalse(source.contains("uOwnershipLabels"))
        assertFalse(source.contains("ownershipChunks"))
        assertFalse(source.contains("uOwnershipMode"))
        assertFalse(source.contains("applySoftRenderLayer"))
        assertTrue(source.contains("val renderGeometry = ldiLite.geometry"))
        assertFalse(source.contains("promoteSteepEdgesToCuts(ldiLite.geometry)"))
        assertTrue(
            source.contains(
                "applyLdiPassState(SpatialLdiRenderPassPolicy.BOUNDARY_SPLAT)"
            )
        )
        assertTrue(source.contains("boundarySurfaceChunks"))
    }

    @Test
    fun `matting必须先去除背景污染再做预乘alpha合成`() {
        assertFalse(source.contains("color.rgb * surfaceAlpha"))
        assertTrue(source.contains("vec3 decontaminated = clamp("))
        assertTrue(source.contains("vec3 exteriorColor = texture2D("))
        assertTrue(
            source.contains(
                "color.rgb - (1.0 - surfaceAlpha) * exteriorColor"
            )
        )
        assertFalse(
            source.contains(
                "color.rgb - (1.0 - surfaceAlpha) * hiddenColor.rgb"
            )
        )
        assertTrue(source.contains("SpatialAlphaEdgeRefiner.refine("))
        assertTrue(source.contains("vec2 alphaAaOffset = uAlphaTexel * 0.4"))
        assertTrue(source.contains("float sampledDisplayAlpha = 0.25 * ("))
        assertTrue(source.contains("float sampledSurfaceAlpha = sampledDisplayAlpha;"))
        assertFalse(source.contains("sampledOwnershipAlpha"))
        assertTrue(source.contains("vec3 inwardColor = texture2D("))
        assertTrue(source.contains("foregroundEstimate * surfaceAlpha"))
    }


    @Test
    fun `MPI不能预写身后颜色后再次逐层over`() {
        assertFalse(mpiSource.contains("compositeBehind("))
        assertFalse(mpiSource.contains("DEPTH_FEATHER_BAND"))
    }

    @Test
    fun `刚性取景移动必须让颜色与深度使用同一相机坐标`() {
        assertTrue(source.contains("vec2 cameraUv = baseUv +"))
        assertTrue(source.contains("texture2D(uRenderDepth, cameraUv)"))
        assertTrue(Regex("""cameraUv\s*\+\s*uParallaxMotion""").containsMatchIn(source))
        assertFalse(source.contains("texture2D(uRenderDepth, baseUv)"))
    }

    @Test
    fun `补图输入只能遮住运行时确实可能显露的区域`() {
        assertFalse(ldiBuilderSource.contains("objectInferenceMask"))
        assertFalse(ldiBuilderSource.contains("inpaintingContextPixels("))
        assertTrue(ldiBuilderSource.contains("hiddenMask = hiddenMask,"))
    }

    @Test
    fun `单层反向重投影必须经过形变预算后再传给着色器`() {
        assertTrue(source.contains("profile?.limitMotion("))
        assertTrue(source.split("safeParallaxMotion(").size - 1 >= 2)
        assertTrue(source.contains("\"uParallaxMotion\""))
        assertTrue(source.contains("safeMotion.x"))
        assertTrue(source.contains("safeMotion.y"))
    }

    @Test
    fun `vNext使用与视点无关的恒定边距而不是暴露画框外拉伸`() {
        assertFalse(source.contains("SpatialSourceLock.Margin(0f, 0f)"))
        assertTrue(source.contains("maximumMotionAmplitude(strength)"))
        assertTrue(source.contains("SpatialSourceLock.coverMargin(vNextMaximumAmplitude)"))
        assertTrue(source.contains("extendBackgroundCanvas ="))
        assertTrue(source.contains("VNEXT_BACKGROUND_CANVAS_PADDING"))
        assertTrue(source.contains("activeViewEnvelope?.motion("))
        assertTrue(source.contains("VNEXT_ALPHA_REVEAL_RADIUS"))
    }

    @Test
    fun `新派生只允许分割matting和EdgeTAM承担连续性补景条件和边缘覆盖`() {
        assertTrue(vNextBuilderSource.contains("SpatialVNextGeometryBuilder.build("))
        assertTrue(vNextBuilderSource.contains("SpatialOwnershipFusion.build("))
        assertTrue(vNextBuilderSource.contains("continuityMask ="))
        assertTrue(vNextBuilderSource.contains("continuityLabels ="))
        assertFalse(vNextBuilderSource.contains("ownershipGroups"))
        assertTrue(vNextBuilderSource.contains("SpatialAlphaFusion.buildDisplayAlpha("))
        assertTrue(imageViewerSource.contains("SpatialVNextBuilder("))
        val generationCall = imageViewerSource.substringAfter("pendingLdi = SpatialVNextBuilder(")
            .substringBefore("mSpatialStore.save(")
        assertTrue(imageViewerSource.contains("var segmentation = generateOptionalSpatialSegmentation("))
        assertTrue(imageViewerSource.contains("val subjectMatte = generateOptionalSpatialMatte("))
        assertTrue(imageViewerSource.contains("generateOptionalSpatialBoundaryRefinement("))
        assertTrue(imageViewerSource.contains("SpatialBoundaryRefinementEngine"))
        assertTrue(generationCall.contains("subjectMatte ="))
        assertTrue(generationCall.contains("segmentation ="))
        assertTrue(generationCall.contains("boundaryRefinementModel ="))
    }

    @Test
    fun `旧v19与各代vNext必须使用独立schema和renderer标识`() {
        assertTrue(derivativeStoreSource.contains("VNEXT1_SCHEMA_VERSION = 3"))
        assertTrue(derivativeStoreSource.contains("VNEXT2_SCHEMA_VERSION = 4"))
        assertTrue(derivativeStoreSource.contains("VNEXT3_SCHEMA_VERSION = 5"))
        assertTrue(derivativeStoreSource.contains("VNEXT4_SCHEMA_VERSION = 6"))
        assertTrue(derivativeStoreSource.contains("VNEXT5_SCHEMA_VERSION = 7"))
        assertTrue(derivativeStoreSource.contains("VNEXT6_SCHEMA_VERSION = 8"))
        assertTrue(derivativeStoreSource.contains("VNEXT7_SCHEMA_VERSION = 9"))
        assertTrue(derivativeStoreSource.contains("VNEXT8_SCHEMA_VERSION = 10"))
        assertTrue(derivativeStoreSource.contains("VNEXT9_SCHEMA_VERSION = 11"))
        assertTrue(derivativeStoreSource.contains("VNEXT10_SCHEMA_VERSION = 12"))
        assertTrue(derivativeStoreSource.contains("VNEXT11_SCHEMA_VERSION = 13"))
        assertTrue(derivativeStoreSource.contains("VNEXT12_SCHEMA_VERSION = 14"))
        assertTrue(derivativeStoreSource.contains("VNEXT13_SCHEMA_VERSION = 15"))
        assertTrue(derivativeStoreSource.contains("SpatialLdiRenderer.LEGACY_V19"))
        assertTrue(
            derivativeStoreSource.contains("SpatialLdiRenderer.SURFACE_CHARTS_VNEXT1")
        )
        assertTrue(
            derivativeStoreSource.contains(
                "SpatialLdiRenderer.SURFACE_CHARTS_VNEXT2_AFFINE_RESIDUAL"
            )
        )
        assertTrue(
            derivativeStoreSource.contains(
                "SpatialLdiRenderer.SURFACE_CHARTS_VNEXT3_RIGID_CHARTS"
            )
        )
        assertTrue(
            derivativeStoreSource.contains(
                "SpatialLdiRenderer.SURFACE_CHARTS_VNEXT4_RIGID_SUBJECTS"
            )
        )
        assertTrue(
            derivativeStoreSource.contains(
                "SpatialLdiRenderer.SURFACE_CHARTS_VNEXT5_LOCAL_SIMILARITY"
            )
        )
        assertTrue(
            derivativeStoreSource.contains(
                "SpatialLdiRenderer.SURFACE_CHARTS_VNEXT6_DIRECTIONAL_36PX"
            )
        )
        assertTrue(
            derivativeStoreSource.contains(
                "SpatialLdiRenderer.SURFACE_CHARTS_VNEXT7_DIRECTIONAL_36PX_VOLUME_BALANCED"
            )
        )
        assertTrue(
            derivativeStoreSource.contains(
                "SpatialLdiRenderer.SURFACE_DEPTH_VNEXT8_GLOBAL_CONTINUOUS_28PX"
            )
        )
        assertTrue(
            derivativeStoreSource.contains(
                "SpatialLdiRenderer.SURFACE_DEPTH_VNEXT9_MULTISCALE_INVERSE_28PX"
            )
        )
        assertTrue(
            vNextBuilderSource.contains(
                "SpatialLdiRenderer.SURFACE_CHARTS_VNEXT12_ALL_SURFACE_NORMALIZED_36PX"
            )
        )
        assertTrue(imageViewerSource.contains("mSpatialStore.isCurrentGeneration(existing)"))
        // `isCurrentGeneration` 指向**哪一代**是随版本走的世界状态，写死代号会在每次
        // 迭代时假失败（2026-08-13 换到 VNEXT14 时就是这样）。这里只断言不变量：它必须
        // 恰好点名一个 renderer；"是不是最新那一个"由
        // SpatialDerivativeSchemaRegistrationTest 负责。
        val currentGeneration = derivativeStoreSource
            .substringAfter("fun isCurrentGeneration(manifest: Manifest)")
            .substringBefore("fun retainedStrength(")
        val named = Regex("""SpatialLdiRenderer\.(\w+)\.stableId""")
            .findAll(currentGeneration).map { it.groupValues[1] }.toList()
        assertEquals("isCurrentGeneration 必须恰好点名一个 renderer", 1, named.size)
        assertTrue(
            "isCurrentGeneration 点名了不存在的 renderer：${named.first()}",
            SpatialLdiRenderer.entries.any { it.name == named.first() }
        )
        assertTrue(derivativeStoreSource.contains("motionBasisSha256"))
        assertTrue(derivativeStoreSource.contains("viewEnvelopeAmplitudes"))
        assertTrue(derivativeStoreSource.contains("surfaceChartLabelsSha256"))
        assertTrue(derivativeStoreSource.contains("backgroundMotionBasisSha256"))
        assertTrue(derivativeStoreSource.contains("depthSurfelSha256"))
        assertTrue(derivativeStoreSource.contains("depth-surfels.f32z"))
    }

    @Test
    fun `连续深度微表面必须逐像素目标空间遮挡且禁止整图warp降级`() {
        val surfelPath = source.substringAfter("private fun drawDepthSurfels()")
            .substringBefore("private fun drawChartAccumulationPass(")
        assertTrue(surfelPath.contains("GLES20.glDrawArrays(GLES20.GL_POINTS"))
        assertTrue(surfelPath.contains("GLES20.glEnable(GLES20.GL_DEPTH_TEST)"))
        assertTrue(surfelPath.contains("GLES20.glDepthFunc(GLES20.GL_LEQUAL)"))
        assertTrue(surfelPath.contains("targets.framebuffer"))
        assertTrue(surfelPath.contains("targets.colorTexture"))
        assertTrue(surfelPath.contains("drawStaticSourceFallback()") ||
            source.contains("if (!drawDepthSurfels()) drawStaticSourceFallback()"))
        assertFalse(surfelPath.contains("inverseMotionWarp("))
        assertFalse(surfelPath.contains("drawSingleLayer("))
        assertFalse(surfelPath.contains("drawVisibleSurface("))
        assertTrue(source.contains("attribute float aMotionScalar;"))
        assertTrue(source.contains("attribute float aSurfaceDepth;"))
        assertTrue(source.contains("1.0 - 2.0 * aSurfaceDepth"))
        assertTrue(source.contains("gl_FragCoord.x / uTargetSize.x"))
        assertTrue(source.contains("occupied(left) * occupied(right)"))
    }

    @Test
    fun `十三代人物运动只能来自连续深度而不是语义刚性层`() {
        val candidatePath = vNextBuilderSource
            .substringAfter("private fun buildInternal(")
            .substringBefore("private fun scaledLongEdge(")
        val defaultPath = vNextBuilderSource
            .substringAfter("fun build(")
            .substringBefore("internal fun buildRejectedSurfaceChartReference(")
        assertTrue(defaultPath.contains("representation = SurfaceRepresentation.DEPTH_SURFELS_V13"))
        assertTrue(candidatePath.contains("SpatialDepthSurfelBuilder.build("))
        assertTrue(candidatePath.contains("sourceDepth = depth"))
        assertTrue(candidatePath.contains("motionBasis = motionBasis"))
        // 落盘的 surfel 只能来自 SpatialDepthSurfelBuilder 那一支，不得另起语义刚性层。
        // vNext15 起真透视档改走断边三角网格，这一档不再产出 surfel（点元的缝隙正是
        // "透明条带"的来源，D211），所以 surfels 可以为 null。
        assertTrue(
            "落盘 surfel 的来源不是 SpatialDepthSurfelBuilder",
            candidatePath.contains("depthSurfels = surfels,") &&
                candidatePath.contains("val surfels = surfelResult?.surfels")
        )
        assertTrue(
            "真透视档必须走网格而不是点元",
            candidatePath.contains("truePerspective == null") &&
                candidatePath.contains("SURFACE_DEPTH_VNEXT15_TRUE_PERSPECTIVE_MESH")
        )
        assertFalse(candidatePath.contains("ownershipGroups"))
        assertFalse(candidatePath.contains("rigidSubject"))
        assertFalse(candidatePath.contains("personPlane"))
    }

    @Test
    fun `全表面chart必须使用目标位置浮点归一化且禁止普通alpha降级`() {
        assertTrue(source.contains("drawNormalizedSurfaceCharts()"))
        assertTrue(source.contains("targets.accumulationFramebuffer"))
        assertTrue(source.contains("targets.coverageFramebuffer"))
        assertTrue(source.contains("GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)"))
        assertTrue(source.contains("accumulation.rgb / max(accumulation.a"))
        assertTrue(source.contains("smoothstep(uCoverageLow, uCoverageHigh, coverage)"))
        assertTrue(source.contains("drawStaticSourceFallback()"))
        assertTrue(source.contains("禁止降级为普通 alpha"))
        assertFalse(source.contains("drawSurfaceChartsAlphaOver"))
    }

    @Test
    fun `vNext5顶点必须消费持久化二维局部相似位移基`() {
        assertTrue(source.contains("attribute vec2 aMotionBasisX"))
        assertTrue(source.contains("attribute vec2 aMotionBasisY"))
        assertTrue(source.contains("uParallaxMotion.x * aMotionBasisX"))
        assertTrue(source.contains("uParallaxMotion.y * aMotionBasisY"))
        assertTrue(source.contains("expandSurfaceMotionVertices("))
    }

    @Test
    fun `vNext稳定模式必须复用分层几何且只关闭边缘alpha显露`() {
        assertTrue(source.contains("SpatialRenderPath.resolve("))
        assertTrue(source.contains("mode = renderMode"))
        assertTrue(
            source.contains(
                "renderMode == SpatialRenderMode.SINGLE_LAYER && activeLdiRenderer.isVNext"
            )
        )
        assertTrue(source.contains("useDisplayAlpha = useSurfaceAlpha"))
    }

    @Test
    fun `vNext停用的分割组件只允许取消旧下载或删除已安装文件`() {
        assertTrue(
            spatialSettingsSource.contains("SpatialSegmentationDownloadCoordinator.enqueue(")
        )
        assertTrue(
            spatialSettingsSource.contains("SpatialPreferences.setSelectedSegmentationModel(")
        )
        assertFalse(
            spatialSettingsSource.contains(
                "SpatialBoundaryRefinementDownloadCoordinator.enqueue("
            )
        )
        assertTrue(
            spatialSettingsSource.contains("SpatialSegmentationDownloadCoordinator.cancel(")
        )
        assertTrue(
            spatialSettingsSource.contains(
                "SpatialBoundaryRefinementDownloadCoordinator.cancel("
            )
        )
    }

    private fun readSource(relativePath: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(7) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到源码：$relativePath")
    }
}
