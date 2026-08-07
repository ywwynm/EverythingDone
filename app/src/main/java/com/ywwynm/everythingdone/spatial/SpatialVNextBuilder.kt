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
    ): SpatialLdiLiteData {
        check(!cancelled.get()) { "任务已取消" }
        onStage(Stage.GEOMETRY)
        val backgroundSource = scaledLongEdge(bitmap, BACKGROUND_LONG_EDGE)
        val meshSize = scaledSize(
            backgroundSource.width,
            backgroundSource.height,
            MESH_LONG_EDGE
        )
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
        Log.d(
            TAG,
                "continuousDepthField=true, semanticMotion=false, " +
                "continuityPrior=${continuityMask?.count { it } ?: 0}, " +
                "motionCandidate=${geometryResult.motionCandidateId}, " +
                "mediumResidualWeight=${geometryResult.mediumResidualWeight}"
        )
        check(!cancelled.get()) { "任务已取消" }

        onStage(Stage.INPAINTING)
        val conditioningSource = SpatialInpaintingMask.withOccluder(
            writeMask = geometryResult.geometry.hiddenBackgroundMask,
            occluderMask = geometryResult.inpaintingOccluderMask
        )
        val hiddenMask = upsampleAndDilateMask(
            source = geometryResult.geometry.hiddenBackgroundMask,
            sourceWidth = geometryResult.geometry.width,
            sourceHeight = geometryResult.geometry.height,
            targetWidth = backgroundSource.width,
            targetHeight = backgroundSource.height
        )
        val conditioningMask = upsampleAndDilateMask(
            source = conditioningSource,
            sourceWidth = geometryResult.geometry.width,
            sourceHeight = geometryResult.geometry.height,
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
                geometry = geometryResult.geometry,
                matte = matte,
                targetWidth = background.width,
                targetHeight = background.height
            )
        }

        return SpatialLdiLiteData(
            geometry = geometryResult.geometry,
            backgroundBitmap = background,
            inpaintingModelId = inpaintingModel.stableId,
            inpaintingModelVersion = inpaintingModel.version,
            renderer = SpatialLdiRenderer.SURFACE_DEPTH_VNEXT11_ADAPTIVE_VISIBILITY_48PX,
            viewEnvelope = geometryResult.viewEnvelope,
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

    companion object {
        private const val TAG = "SpatialVNextBuilder"
        const val MESH_LONG_EDGE = 512
        const val BACKGROUND_LONG_EDGE = 1440
        private const val MASK_DILATION_RADIUS = 3
    }
}
