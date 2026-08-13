package com.ywwynm.everythingdone.spatial

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

data class SpatialLdiLiteData(
    val geometry: SpatialLdiLiteGeometry,
    val backgroundBitmap: Bitmap,
    val inpaintingModelId: String,
    val inpaintingModelVersion: String,
    val renderer: SpatialLdiRenderer = SpatialLdiRenderer.LEGACY_V19,
    val viewEnvelope: SpatialViewEnvelope? = null,
    val surfaceCharts: SpatialSurfaceChartData? = null,
    val depthSurfels: SpatialDepthSurfelData? = null,
    /**
     * 取景内缩比例，由生成期按**真实位移场**算出（见
     * [SpatialTrueParallaxMotion.coverMarginFraction]）。仅真透视档提供；其余档运行时
     * 仍按幅度现推，因为那些档的幅度确实是归一化位移。
     */
    val coverMarginFraction: Float? = null,
    val inpaintingQualityId: String? = null,
    /**
     * 表面层显示 alpha 平面（8-bit，行主序，背景板分辨率；见 [SpatialAlphaFusion]）。
     * null = 未启用 matting，渲染与既有行为一致。
     */
    val displayAlpha: ByteArray? = null,
    /**
     * 对象层软覆盖率（8-bit，行主序，背景板分辨率）。与具体 provider 无关：当前可来自
     * MODNet，后续可由 EdgeTAM + boundary refiner 生成。
     */
    val ownershipAlpha: ByteArray? = null,
    /** 互斥实例身份图（8-bit，0 = 连续表面，1..254 = 独立对象；网格分辨率）。 */
    val ownershipLabels: ByteArray? = null,
    /** matting 生成的几何主体保护层（8-bit 二值，网格分辨率）。 */
    val subjectMask: ByteArray? = null,
    val mattingModelId: String? = null,
    val mattingModelVersion: String? = null,
    val segmentationModelId: String? = null,
    val segmentationModelVersion: String? = null,
    val boundaryRefinementModelId: String? = null,
    val boundaryRefinementModelVersion: String? = null
) {
    init {
        require(
            !renderer.isVNext || viewEnvelope != null
        ) { "vNext 空间场景缺少安全视点包络" }
        require(
            renderer.usesNormalizedSurfaceCharts == (surfaceCharts != null)
        ) { "归一化全表面 chart renderer 与 chart 数据不匹配" }
        require(
            renderer.usesDepthSurfels == (depthSurfels != null)
        ) { "连续深度微表面 renderer 与点元数据不匹配" }
        surfaceCharts?.let {
            require(it.width == geometry.width && it.height == geometry.height) {
                "全表面 chart 尺寸与几何不匹配"
            }
        }
        depthSurfels?.let {
            require(it.width == geometry.width && it.height == geometry.height) {
                "连续深度微表面尺寸与几何不匹配"
            }
        }
    }
}

class SpatialLdiLiteBuilder(
    private val inpaintingEngine: SpatialInpaintingEngine
) {
    enum class Stage {
        GEOMETRY,
        INPAINTING
    }

    fun build(
        bitmap: Bitmap,
        depth: SpatialDepthData,
        inpaintingModel: SpatialInpaintingModel,
        inpaintingQuality: SpatialInpaintingQuality = SpatialInpaintingQuality.HIGH,
        cancelled: AtomicBoolean,
        subjectMatte: SpatialAlphaData? = null,
        segmentation: Pair<SpatialSegmentationModel, SpatialSegmentationData>? = null,
        boundaryRefinementModel: SpatialBoundaryRefinementModel? = null,
        onStage: (Stage) -> Unit = {}
    ): SpatialLdiLiteData {
        check(!cancelled.get()) { "任务已取消" }
        onStage(Stage.GEOMETRY)
        val backgroundSource = scaledLongEdge(bitmap, BACKGROUND_LONG_EDGE)
        val meshBitmap = scaledLongEdge(backgroundSource, MESH_LONG_EDGE)
        val meshPixels = IntArray(meshBitmap.width * meshBitmap.height)
        meshBitmap.getPixels(
            meshPixels,
            0,
            meshBitmap.width,
            0,
            0,
            meshBitmap.width,
            meshBitmap.height
        )
        val ownership = segmentation?.second?.let {
            SpatialOwnershipFusion.build(
                segmentation = it,
                matte = subjectMatte,
                meshWidth = meshBitmap.width,
                meshHeight = meshBitmap.height,
                alphaWidth = backgroundSource.width,
                alphaHeight = backgroundSource.height
            )
        }
        val subjectMask = ownership?.subjectMask ?: subjectMatte?.let {
            SpatialSubjectLayer.buildMask(it, meshBitmap.width, meshBitmap.height)
        }
        val prepared = try {
            SpatialLdiLiteGeometryBuilder.prepare(
                colorPixels = meshPixels,
                width = meshBitmap.width,
                height = meshBitmap.height,
                sourceDepth = depth
            )
        } finally {
            if (meshBitmap !== backgroundSource && meshBitmap !== bitmap) {
                meshBitmap.recycle()
            }
        }
        check(!cancelled.get()) { "任务已取消" }
        // P2（design-2026-08-03）：关系解析先于断边判定，解析结果作为断边门控的
        // 运动归组——分割是先验而非独立运动层。
        val resolvedOwnershipLabels = ownership?.labels?.let { labels ->
            val segmentationData = segmentation?.second
            if (segmentationData == null) {
                labels
            } else {
                SpatialOwnershipRelationResolver.resolve(
                    labels = labels,
                    width = prepared.width,
                    height = prepared.height,
                    instances = segmentationData.instances,
                    depth = prepared.surface
                )
            }
        }
        // 无分割时以 MODNet subject mask 充当单实例组，人像内部同样禁断。
        val ownershipGroups = resolvedOwnershipLabels ?: subjectMask?.let { mask ->
            ByteArray(mask.size) { index ->
                if ((mask[index].toInt() and 0xff) >= 128) 1 else 0
            }
        }
        val geometry = SpatialLdiLiteGeometryBuilder.finish(
            prepared = prepared,
            ownershipGroups = ownershipGroups
        )
        check(!cancelled.get()) { "任务已取消" }
        // 补全预算必须与渲染时真正移动的对象层一致。若仍按 0..1 的理论最坏深度差
        // 预生成整块主体内部，模型会被迫重建大量永远不会显露的内容，并在大视差下露出幻觉。
        val runtimeOwnershipGraph = when {
            resolvedOwnershipLabels != null -> SpatialOwnershipLayer.buildGraphFromLabels(
                // P4：代表深度改用未压缩的 surfaceDepth——残差压缩场会把各标签的
                // 代表深度拉向分量均值，低估最大相对位移，进而把显露带算窄。
                baseDepth = geometry.surfaceDepth,
                width = geometry.width,
                height = geometry.height,
                ownershipLabels = resolvedOwnershipLabels
            )
            subjectMask != null -> SpatialOwnershipLayer.buildGraphFromMask(
                baseDepth = geometry.surfaceDepth,
                width = geometry.width,
                height = geometry.height,
                ownershipMask = subjectMask
            )
            else -> null
        }

        onStage(Stage.INPAINTING)
        val hiddenSource = geometry.hiddenBackgroundMask.copyOf()
        val hiddenMask = upsampleAndDilateMask(
            source = hiddenSource,
            sourceWidth = geometry.width,
            sourceHeight = geometry.height,
            targetWidth = backgroundSource.width,
            targetHeight = backgroundSource.height
        )
        val fullObjectMask = when {
            ownership?.alpha != null -> {
                val alpha = ownership.alpha
                require(alpha.size == backgroundSource.width * backgroundSource.height)
                upsampleAndDilateMask(
                    source = BooleanArray(alpha.size) { index ->
                        (alpha[index].toInt() and 0xff) > 0
                    },
                    sourceWidth = backgroundSource.width,
                    sourceHeight = backgroundSource.height,
                    targetWidth = backgroundSource.width,
                    targetHeight = backgroundSource.height
                )
            }
            subjectMask != null -> upsampleAndDilateMask(
                source = BooleanArray(subjectMask.size) { index ->
                    (subjectMask[index].toInt() and 0xff) >= 128
                },
                sourceWidth = geometry.width,
                sourceHeight = geometry.height,
                targetWidth = backgroundSource.width,
                targetHeight = backgroundSource.height
            )
            else -> null
        }
        val maximumRelativeDepth = runtimeOwnershipGraph
            ?.takeIf { it.layers.isNotEmpty() }
            ?.let { graph ->
                SpatialDisocclusionBand.maximumRelativeDepth(
                    labels = graph.labels,
                    backgroundDepth = geometry.backgroundDepth,
                    layers = graph.layers
                )
            }
            ?: 1f
        val objectRevealRadius = SpatialDisocclusionBand.requiredRadius(
            width = backgroundSource.width,
            height = backgroundSource.height,
            maximumRelativeDepth = maximumRelativeDepth
        )
        val objectHiddenMask = fullObjectMask?.let { objectMask ->
            SpatialDisocclusionBand.inside(
                objectMask = objectMask,
                width = backgroundSource.width,
                height = backgroundSource.height,
                radius = objectRevealRadius
            )
        }
        // 对象深处在允许的最大视差下也不会被采样。把整块人物交给补全模型会把一个
        // 本来只需生成窄显露带的问题扩大成大面积场景重建，并在强视差时露出结构幻觉。
        objectHiddenMask?.forEachIndexed { index, hidden ->
            if (hidden) hiddenMask[index] = true
        }
        val background = try {
            val generated = if (hiddenMask.any { it }) {
                inpaintingEngine.inpaint(
                    bitmap = backgroundSource,
                    hiddenMask = hiddenMask,
                    model = inpaintingModel,
                    cancelled = cancelled,
                    quality = inpaintingQuality
                )
            } else {
                backgroundSource.copy(Bitmap.Config.ARGB_8888, false)
            }
            if (objectHiddenMask != null) {
                stabilizeObjectDisocclusionBoundary(
                    source = backgroundSource,
                    generated = generated,
                    hiddenMask = hiddenMask,
                    objectHiddenMask = objectHiddenMask,
                    fullObjectMask = checkNotNull(fullObjectMask)
                )
            } else {
                generated
            }
        } finally {
            if (backgroundSource !== bitmap) backgroundSource.recycle()
        }
        check(!cancelled.get()) {
            background.recycle()
            "任务已取消"
        }
        // P2：ownership 软 alpha 只做断边带内的剪影羽化（D54 机制），不承担身份。
        val displayAlpha = ownership?.alpha?.let { alpha ->
            SpatialAlphaFusion.buildDisplayAlpha(
                geometry = geometry,
                matte = SpatialAlphaData(
                    width = background.width,
                    height = background.height,
                    values = FloatArray(alpha.size) { index ->
                        (alpha[index].toInt() and 0xff) / 255f
                    }
                ),
                targetWidth = background.width,
                targetHeight = background.height
            )
        }
        return SpatialLdiLiteData(
            geometry = geometry,
            backgroundBitmap = background,
            displayAlpha = displayAlpha,
            inpaintingModelId = inpaintingModel.stableId,
            inpaintingModelVersion = inpaintingModel.version,
            inpaintingQualityId = inpaintingQuality.stableId.takeIf {
                inpaintingModel.inputContract ==
                    SpatialInpaintingInputContract.FLOAT32_AOTGAN_RGB_MASK
            },
            ownershipAlpha = ownership?.alpha,
            ownershipLabels = resolvedOwnershipLabels,
            subjectMask = subjectMask,
            segmentationModelId = segmentation?.first?.stableId?.takeIf {
                ownership != null
            },
            segmentationModelVersion = segmentation?.first?.version?.takeIf {
                ownership != null
            },
            boundaryRefinementModelId = boundaryRefinementModel?.stableId?.takeIf {
                ownership != null
            },
            boundaryRefinementModelVersion = boundaryRefinementModel?.version?.takeIf {
                ownership != null
            }
        )
    }

    private fun scaledLongEdge(source: Bitmap, maximumLongEdge: Int): Bitmap {
        val longEdge = maxOf(source.width, source.height)
        if (longEdge <= maximumLongEdge) return source
        val scale = maximumLongEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }

    private fun upsampleAndDilateMask(
        source: BooleanArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): BooleanArray {
        val upsampled = BooleanArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sourceY = ((y + 0.5f) * sourceHeight / targetHeight)
                .toInt()
                .coerceIn(0, sourceHeight - 1)
            for (x in 0 until targetWidth) {
                val sourceX = ((x + 0.5f) * sourceWidth / targetWidth)
                    .toInt()
                    .coerceIn(0, sourceWidth - 1)
                upsampled[y * targetWidth + x] =
                    source[sourceY * sourceWidth + sourceX]
            }
        }
        val dilated = upsampled.copyOf()
        // 半径 2：背景板在轮廓两侧 1–2 像素内是前景/背景混合像素，只膨胀 1 格会把
        // 这圈暗环留给补图模型当作上下文，显露时呈现为深色碎屑边（D47）。
        for (y in 0 until targetHeight) {
            for (x in 0 until targetWidth) {
                if (!upsampled[y * targetWidth + x]) continue
                for (offsetY in -2..2) {
                    val targetY = y + offsetY
                    if (targetY !in 0 until targetHeight) continue
                    for (offsetX in -2..2) {
                        val targetX = x + offsetX
                        if (targetX in 0 until targetWidth) {
                            dilated[targetY * targetWidth + targetX] = true
                        }
                    }
                }
            }
        }
        return dilated
    }

    private fun stabilizeObjectDisocclusionBoundary(
        source: Bitmap,
        generated: Bitmap,
        hiddenMask: BooleanArray,
        objectHiddenMask: BooleanArray,
        fullObjectMask: BooleanArray
    ): Bitmap {
        require(source.width == generated.width && source.height == generated.height)
        val pixels = source.width * source.height
        val sourcePixels = IntArray(pixels)
        val generatedPixels = IntArray(pixels)
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        generated.getPixels(
            generatedPixels,
            0,
            generated.width,
            0,
            0,
            generated.width,
            generated.height
        )
        val stabilized = SpatialDisocclusionGuardBand.stabilize(
            source = sourcePixels,
            generated = generatedPixels,
            hiddenMask = hiddenMask,
            revealMask = objectHiddenMask,
            fullObjectMask = fullObjectMask,
            width = source.width,
            height = source.height,
            radius = OBJECT_GUARD_BAND_PIXELS
        )
        val target = if (generated.isMutable) {
            generated
        } else {
            checkNotNull(generated.copy(Bitmap.Config.ARGB_8888, true))
                .also { generated.recycle() }
        }
        target.setPixels(stabilized, 0, target.width, 0, 0, target.width, target.height)
        return target
    }

    companion object {
        const val MESH_LONG_EDGE = 600
        const val BACKGROUND_LONG_EDGE = 1440
        // 这里只处理贴边色差，不再把外侧纹理向遮挡区复制几十个像素。
        private const val OBJECT_GUARD_BAND_PIXELS = 3
    }
}
