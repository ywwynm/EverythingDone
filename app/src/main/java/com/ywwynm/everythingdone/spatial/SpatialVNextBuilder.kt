package com.ywwynm.everythingdone.spatial

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * vNext 场景生成器。深度独立决定连续运动场和位移；实例分割只删除可信人物内部的
 * 伪遮挡 cut，并为补景提供完整遮挡物条件；matting 只细化深度确认边界的显示覆盖率。
 */
class SpatialVNextBuilder(
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
        subjectMatte: Pair<SpatialMattingModel, SpatialAlphaData>? = null,
        segmentation: Pair<SpatialSegmentationModel, SpatialSegmentationData>? = null,
        boundaryRefinementModel: SpatialBoundaryRefinementModel? = null,
        onStage: (Stage) -> Unit = {}
    ): SpatialLdiLiteData = buildInternal(
        bitmap = bitmap,
        depth = depth,
        inpaintingModel = inpaintingModel,
        inpaintingQuality = inpaintingQuality,
        cancelled = cancelled,
        subjectMatte = subjectMatte,
        segmentation = segmentation,
        boundaryRefinementModel = boundaryRefinementModel,
        onStage = onStage,
        representation = SurfaceRepresentation.DEPTH_SURFELS_V13
    )

    /** 仅保留给已否决 vNext12 的离线回归对照；正式生成不得调用。 */
    internal fun buildRejectedSurfaceChartReference(
        bitmap: Bitmap,
        depth: SpatialDepthData,
        inpaintingModel: SpatialInpaintingModel,
        inpaintingQuality: SpatialInpaintingQuality = SpatialInpaintingQuality.HIGH,
        cancelled: AtomicBoolean,
        subjectMatte: Pair<SpatialMattingModel, SpatialAlphaData>? = null,
        segmentation: Pair<SpatialSegmentationModel, SpatialSegmentationData>? = null,
        boundaryRefinementModel: SpatialBoundaryRefinementModel? = null,
        onStage: (Stage) -> Unit = {}
    ): SpatialLdiLiteData = buildInternal(
        bitmap = bitmap,
        depth = depth,
        inpaintingModel = inpaintingModel,
        inpaintingQuality = inpaintingQuality,
        cancelled = cancelled,
        subjectMatte = subjectMatte,
        segmentation = segmentation,
        boundaryRefinementModel = boundaryRefinementModel,
        onStage = onStage,
        representation = SurfaceRepresentation.SURFACE_CHARTS_V12
    )

    private fun buildInternal(
        bitmap: Bitmap,
        depth: SpatialDepthData,
        inpaintingModel: SpatialInpaintingModel,
        inpaintingQuality: SpatialInpaintingQuality = SpatialInpaintingQuality.HIGH,
        cancelled: AtomicBoolean,
        subjectMatte: Pair<SpatialMattingModel, SpatialAlphaData>? = null,
        segmentation: Pair<SpatialSegmentationModel, SpatialSegmentationData>? = null,
        boundaryRefinementModel: SpatialBoundaryRefinementModel? = null,
        onStage: (Stage) -> Unit,
        representation: SurfaceRepresentation
    ): SpatialLdiLiteData {
        check(!cancelled.get()) { "任务已取消" }
        onStage(Stage.GEOMETRY)
        val backgroundSource = scaledLongEdge(bitmap, BACKGROUND_LONG_EDGE)
        // 有米制深度时，**网格直接建在深度网格上**——与网页端同一口径（每个深度像素一个
        // 顶点）。好处不止是"分辨率一致"：
        //
        // - 不再需要 `resampleDepth`，最近邻重采样在断崖处造出的锯齿一并消失；
        // - `scaleX == scaleY == 1`，于是 `meshFx == k.fx`、`meshFy == k.fy`，而 MoGe 给的
        //   fx、fy 本就严格相等——D210 里为了迁就"每点一个标量"取的几何平均、以及那
        //   ±1.4%/轴的残差，在这一档上直接不存在。
        //
        // 深度分辨率本身由 MoGe 的 `num_tokens` 决定内在细节（1800 tokens 在 patch-14 下
        // 约合 588×602 px）；实测把输入长边从 518 抬到 1440，深度场的有效带宽完全持平
        // （0.00238 / 0.00229 / 0.00237 / 0.00229），所以长边取 720 即可匹配，再高是浪费。
        val meshSize = if (depth.metricDepth != null && depth.intrinsics != null) {
            depth.width to depth.height
        } else {
            scaledSize(
                backgroundSource.width,
                backgroundSource.height,
                MESH_LONG_EDGE
            )
        }
        val ownership = segmentation?.let { (_, data) ->
            SpatialOwnershipFusion.build(
                segmentation = data,
                matte = subjectMatte?.second,
                meshWidth = meshSize.first,
                meshHeight = meshSize.second,
                alphaWidth = backgroundSource.width,
                alphaHeight = backgroundSource.height
            )
        }
        val continuityLabels = ownership?.continuityLabels
        val continuityMask = continuityLabels?.let { labels ->
            BooleanArray(labels.size) { index ->
                (labels[index].toInt() and 0xff) != 0
            }
        }
        val geometryResult = SpatialVNextGeometryBuilder.build(
            width = meshSize.first,
            height = meshSize.second,
            sourceDepth = depth,
            continuityMask = continuityMask,
            continuityLabels = continuityLabels
        )
        val chartResult = if (representation == SurfaceRepresentation.SURFACE_CHARTS_V12) {
            val meshBitmap = scaledLongEdge(backgroundSource, MESH_LONG_EDGE)
            val meshPixels = IntArray(meshBitmap.width * meshBitmap.height)
            try {
                meshBitmap.getPixels(
                    meshPixels,
                    0,
                    meshBitmap.width,
                    0,
                    0,
                    meshBitmap.width,
                    meshBitmap.height
                )
                SpatialSurfaceChartBuilder.build(
                    colorPixels = meshPixels,
                    width = meshSize.first,
                    height = meshSize.second,
                    surfaceDepth = geometryResult.geometry.surfaceDepth
                )
            } finally {
                if (meshBitmap !== backgroundSource && meshBitmap !== bitmap) {
                    meshBitmap.recycle()
                }
            }
        } else {
            null
        }
        // 深度模型给出米制深度与内参时（目前只有 MoGe-2），运动基**直接按真透视视差算**，
        // 不走局部刚性拟合——后者产出的基交叉项不为零、两个主项也不同源，那不是视差，
        // 是拟合出来的形变场（D204）。此路径不套保形预算：预算抑制的局部拉伸正是视差本身。
        // 网格建在深度网格上，所以两个缩放比都是 1，内参原样即网格像素单位。
        val meshFx = depth.intrinsics?.fx?.times(meshSize.first.toFloat() / depth.width) ?: 0f
        val meshFy = depth.intrinsics?.fy?.times(meshSize.second.toFloat() / depth.height) ?: 0f
        val truePerspective = depth.metricDepth?.let { metric ->
            depth.intrinsics?.let { _ ->
                val meshDepth = SpatialTrueParallaxMotion.resampleDepth(
                    metric, depth.width, depth.height, meshSize.first, meshSize.second
                )
                // 支点取**主体**深度中位数，与网页端 export_moge_geometry.py 的
                // `median(z[matte > 0.5])` 同口径。支点处位移恒为零，所以它决定"什么
                // 东西钉在屏幕上不动"；退回全图中位数会让主体跟着背景一起漂。
                val subject = subjectMatte?.second?.let { matte ->
                    SpatialTrueParallaxMotion.subjectMaskFrom(
                        alpha = matte.values,
                        alphaWidth = matte.width,
                        alphaHeight = matte.height,
                        targetWidth = meshSize.first,
                        targetHeight = meshSize.second
                    )
                }
                SpatialTrueParallaxMotion.build(
                    depth = meshDepth,
                    width = meshSize.first,
                    height = meshSize.second,
                    fx = meshFx,
                    fy = meshFy,
                    subject = subject,
                    baselineMeters = TRUE_PERSPECTIVE_BASELINE_METERS
                )
            }
        }
        // 真透视走**断边三角网格**（vNext15），点元只留给没有米制深度的旧模型。
        // 点元每点只带一个标量，两轴只能取几何平均，而且片元着色器把 alpha 写死 1.0、
        // 不采样软 α；更要命的是点大小按未形变的网格间距算，真透视的视差幅度会在深度
        // 断崖处把相邻点元拉开到远超点大小，剪影上留出让底板透出来的缺口（D211）。
        val surfelResult = if (
            representation == SurfaceRepresentation.DEPTH_SURFELS_V13 &&
            truePerspective == null
        ) {
            SpatialDepthSurfelBuilder.build(
                sourceDepth = depth,
                width = meshSize.first,
                height = meshSize.second
            )
        } else {
            null
        }
        val surfels = surfelResult?.surfels
        val motionBasis = truePerspective?.basis
            ?: chartResult?.motionBasis ?: checkNotNull(surfelResult).motionBasis
        val viewEnvelope = truePerspective?.let {
            // 幅度单位在这条路上是**米**（物理基线），与网页端滑杆同口径；
            // 4.5cm 对齐 D169 实测的 iOS 空间照片视差。
            SpatialViewEnvelope.uniform(
                amplitude = TRUE_PERSPECTIVE_BASELINE_METERS,
                maximumLocalStrain = 0.20f
            )
        } ?: chartResult?.viewEnvelope ?: checkNotNull(surfelResult).viewEnvelope
        // 取景内缩按**真实位移场**算好落盘：运行时的 `coverMargin(amplitude)` 假定幅度是
        // 归一化位移，而这一档的幅度是米。
        val trueCoverMargin = truePerspective?.let {
            SpatialTrueParallaxMotion.coverMarginFraction(
                result = it,
                amplitudeMeters = TRUE_PERSPECTIVE_BASELINE_METERS
            )
        }
        if (truePerspective != null) {
            Log.d(
                TAG,
                "truePerspective: pivot=%.3fm fx=%.1f 相对视差=%.1fpx@%d".format(
                    truePerspective.pivotDepth, depth.intrinsics!!.fx,
                    truePerspective.relativeParallaxPixels, meshSize.first
                )
            )
        }
        val visibility = SpatialVNextVisibilityBuilder.build(
            surfaceDepth = geometryResult.geometry.surfaceDepth,
            width = geometryResult.geometry.width,
            height = geometryResult.geometry.height,
            cutRight = geometryResult.geometry.cutRight,
            cutDown = geometryResult.geometry.cutDown,
            motionBasis = motionBasis,
            viewEnvelope = viewEnvelope,
            continuityLabels = continuityLabels
        )
        val renderGeometry = geometryResult.geometry.copy(
            backgroundDepth = visibility.backgroundDepth,
            hiddenBackgroundMask = visibility.hiddenBackgroundMask,
            motionBasis = motionBasis,
            // 底板只保留公共 reframe；不得复制局部表面运动形成拖影。
            backgroundMotionBasis = truePerspective?.let { tp ->
                // 真透视档：底板跟随远景的公共位移，取标量场的 p92（`1/Z0 − 1/Z` 在远处
                // 为正，越大越远；这与 V13 取 surfel 标量 p8 是同一档，只是符号相反）。
                // 不能沿用 V13 的底板基——那是另一套幅度口径。
                val sBackground = percentileOf(tp.scalarField, 0.92f)
                val basis = tp.basis
                SpatialScreenSpaceMotionBasis(
                    width = basis.width,
                    height = basis.height,
                    horizontalX = FloatArray(basis.horizontalX.size) {
                        meshFx * sBackground / basis.width
                    },
                    horizontalY = FloatArray(basis.horizontalX.size),
                    verticalX = FloatArray(basis.horizontalX.size),
                    verticalY = FloatArray(basis.horizontalX.size) {
                        meshFy * sBackground / basis.height
                    }
                )
            } ?: chartResult?.backgroundMotionBasis
                ?: checkNotNull(surfelResult).backgroundMotionBasis
        )
        Log.d(
            TAG,
                "representation=$representation, semanticMotion=false, " +
                "continuityPrior=${continuityMask?.count { it } ?: 0}, " +
                "charts=${chartResult?.charts?.chartCount ?: 0}, " +
                "surfels=${surfels?.motionScalars?.size ?: 0}, " +
                "truePerspectiveMesh=${truePerspective != null}, " +
                "mesh=${meshSize.first}x${meshSize.second}, " +
                "coverMargin=$trueCoverMargin, " +
                "guard=${chartResult?.charts?.guardFraction ?: surfels?.guardFraction}"
        )
        check(!cancelled.get()) { "任务已取消" }

        onStage(Stage.INPAINTING)
        val conditioningSource = SpatialInpaintingMask.withOccluder(
            writeMask = renderGeometry.hiddenBackgroundMask,
            occluderMask = visibility.inpaintingOccluderMask
        )
        val hiddenMask = upsampleAndDilateMask(
            source = renderGeometry.hiddenBackgroundMask,
            sourceWidth = renderGeometry.width,
            sourceHeight = renderGeometry.height,
            targetWidth = backgroundSource.width,
            targetHeight = backgroundSource.height
        )
        val conditioningMask = upsampleAndDilateMask(
            source = conditioningSource,
            sourceWidth = renderGeometry.width,
            sourceHeight = renderGeometry.height,
            targetWidth = backgroundSource.width,
            targetHeight = backgroundSource.height
        )
        Log.d(
            TAG,
            "inpaintingWritePixels=${hiddenMask.count { it }}, " +
                "inpaintingConditioningPixels=${conditioningMask.count { it }}"
        )
        val background = try {
            if (hiddenMask.any { it }) {
                inpaintingEngine.inpaint(
                    bitmap = backgroundSource,
                    hiddenMask = hiddenMask,
                    model = inpaintingModel,
                    cancelled = cancelled,
                    quality = inpaintingQuality,
                    conditioningMask = conditioningMask
                )
            } else {
                backgroundSource.copy(Bitmap.Config.ARGB_8888, false)
            }
        } finally {
            if (backgroundSource !== bitmap) backgroundSource.recycle()
        }
        check(!cancelled.get()) {
            background.recycle()
            "任务已取消"
        }

        val displayAlpha = subjectMatte?.second?.let { matte ->
            SpatialAlphaFusion.buildDisplayAlpha(
                geometry = renderGeometry,
                matte = matte,
                targetWidth = background.width,
                targetHeight = background.height
            )
        }

        return SpatialLdiLiteData(
            geometry = renderGeometry,
            backgroundBitmap = background,
            inpaintingModelId = inpaintingModel.stableId,
            inpaintingModelVersion = inpaintingModel.version,
            renderer = if (truePerspective != null) {
                SpatialLdiRenderer.SURFACE_DEPTH_VNEXT15_TRUE_PERSPECTIVE_MESH
            } else if (representation == SurfaceRepresentation.DEPTH_SURFELS_V13) {
                SpatialLdiRenderer.SURFACE_DEPTH_VNEXT13_ADAPTIVE_SURFELS_36PX
            } else {
                SpatialLdiRenderer.SURFACE_CHARTS_VNEXT12_ALL_SURFACE_NORMALIZED_36PX
            },
            viewEnvelope = viewEnvelope,
            surfaceCharts = chartResult?.charts,
            depthSurfels = surfels,
            coverMarginFraction = trueCoverMargin,
            inpaintingQualityId = inpaintingQuality.stableId.takeIf {
                inpaintingModel.inputContract ==
                    SpatialInpaintingInputContract.FLOAT32_AOTGAN_RGB_MASK
            },
            displayAlpha = displayAlpha,
            ownershipAlpha = null,
            ownershipLabels = null,
            subjectMask = null,
            mattingModelId = subjectMatte?.first?.stableId,
            mattingModelVersion = subjectMatte?.first?.version,
            segmentationModelId = segmentation?.first?.stableId,
            segmentationModelVersion = segmentation?.first?.version,
            boundaryRefinementModelId = boundaryRefinementModel?.stableId,
            boundaryRefinementModelVersion = boundaryRefinementModel?.version
        )
    }

    private fun scaledLongEdge(source: Bitmap, maximumLongEdge: Int): Bitmap {
        val target = scaledSize(source.width, source.height, maximumLongEdge)
        if (target.first == source.width && target.second == source.height) return source
        return Bitmap.createScaledBitmap(source, target.first, target.second, true)
    }

    private fun scaledSize(width: Int, height: Int, maximumLongEdge: Int): Pair<Int, Int> {
        val longEdge = maxOf(width, height)
        if (longEdge <= maximumLongEdge) return width to height
        val scale = maximumLongEdge.toFloat() / longEdge
        return (width * scale).roundToInt().coerceAtLeast(2) to
            (height * scale).roundToInt().coerceAtLeast(2)
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
                upsampled[y * targetWidth + x] = source[sourceY * sourceWidth + sourceX]
            }
        }
        val dilated = upsampled.copyOf()
        for (y in 0 until targetHeight) {
            for (x in 0 until targetWidth) {
                if (!upsampled[y * targetWidth + x]) continue
                for (offsetY in -MASK_DILATION_RADIUS..MASK_DILATION_RADIUS) {
                    val targetY = y + offsetY
                    if (targetY !in 0 until targetHeight) continue
                    for (offsetX in -MASK_DILATION_RADIUS..MASK_DILATION_RADIUS) {
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

    private enum class SurfaceRepresentation {
        SURFACE_CHARTS_V12,
        DEPTH_SURFELS_V13
    }

    /** 与 SpatialDepthSurfelBuilder 同口径的线性插值分位数。 */
    private fun percentileOf(values: FloatArray, fraction: Float): Float {
        require(values.isNotEmpty())
        val sorted = values.copyOf()
        sorted.sort()
        if (sorted.size == 1) return sorted[0]
        val position = fraction.coerceIn(0f, 1f) * (sorted.size - 1)
        val low = kotlin.math.floor(position).toInt()
        val high = kotlin.math.ceil(position).toInt()
        if (low == high) return sorted[low]
        val mix = position - low
        return sorted[low] * (1f - mix) + sorted[high] * mix
    }

    companion object {
        private const val TAG = "SpatialVNextBuilder"
        const val MESH_LONG_EDGE = 512
        const val BACKGROUND_LONG_EDGE = 1440
        private const val MASK_DILATION_RADIUS = 3
        /** 真透视路径的物理基线（米）。4.5cm 对齐 D169 实测的 iOS 空间照片视差。 */
        const val TRUE_PERSPECTIVE_BASELINE_METERS = 0.045f
    }
}
