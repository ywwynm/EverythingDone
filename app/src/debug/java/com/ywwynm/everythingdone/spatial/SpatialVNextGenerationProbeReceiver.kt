package com.ywwynm.everythingdone.spatial

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Debug 真机生成探针：锁屏时也能执行与产品一致的端侧模型和派生存储链。 */
class SpatialVNextGenerationProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sourcePath = intent.getStringExtra(EXTRA_PATH) ?: return
        if (!File(sourcePath).isFile) return
        val pending = goAsync()
        Thread({
            val appContext = context.applicationContext
            var ldi: SpatialLdiLiteData? = null
            var bitmap: Bitmap? = null
            try {
                bitmap = checkNotNull(BitmapFactory.decodeFile(sourcePath)) {
                    "无法解码探针图片"
                }
                val source = checkNotNull(bitmap)
                val cancelled = AtomicBoolean(false)
                val depthModel = SpatialPreferences.selectedModel(appContext)
                val store = SpatialDerivativeStore(appContext)
                val generatedDepth = SpatialDepthEngine(appContext).generate(
                    source,
                    depthModel,
                    cancelled
                )
                val depth = store.retainedStrength(sourcePath)?.let {
                    generatedDepth.copy(defaultStrength = it)
                } ?: generatedDepth

                val matteModel = SpatialMattingModel.MODNET_PHOTOGRAPHIC
                val matte = if (
                    SpatialMattingModelStore.isInstalled(appContext, matteModel) &&
                    SpatialMattingModelStore.hasSufficientAvailableMemory(appContext, matteModel)
                ) {
                    matteModel to SpatialMattingEngine(appContext).generate(
                        source,
                        matteModel,
                        cancelled
                    )
                } else {
                    null
                }
                val segmentationModel = SpatialPreferences.selectedSegmentationModel(appContext)
                var segmentation = segmentationModel?.takeIf {
                    SpatialSegmentationModelStore.isInstalled(appContext, it) &&
                        SpatialSegmentationModelStore.isDeviceEligible(appContext, it) &&
                        SpatialSegmentationModelStore.hasSufficientAvailableMemory(appContext, it)
                }?.let {
                    it to SpatialSegmentationEngine(appContext).generate(source, it, cancelled)
                }
                val boundaryRefinementModel = try {
                    segmentation?.let { (_, data) ->
                        SpatialPreferences.selectedBoundaryRefinementModel(appContext)?.takeIf {
                            SpatialBoundaryRefinementModelStore.isInstalled(appContext, it) &&
                                SpatialBoundaryRefinementModelStore.isDeviceEligible(appContext, it) &&
                                SpatialBoundaryRefinementModelStore.hasSufficientAvailableMemory(
                                    appContext,
                                    it
                                )
                        }?.let { model ->
                            model to SpatialBoundaryRefinementEngine(appContext).refine(
                                source,
                                data,
                                model,
                                cancelled
                            )
                        }
                    }
                } catch (error: Throwable) {
                    if (cancelled.get()) throw error
                    Log.w(TAG, "boundary refinement failed, keeping RF-DETR", error)
                    null
                }
                if (segmentation != null && boundaryRefinementModel != null) {
                    segmentation = segmentation.first to boundaryRefinementModel.second
                }
                ldi = SpatialVNextBuilder(SpatialInpaintingEngine(appContext)).build(
                    bitmap = source,
                    depth = depth,
                    inpaintingModel = SpatialPreferences.selectedInpaintingModel(appContext),
                    inpaintingQuality = SpatialPreferences.inpaintingQuality(appContext),
                    cancelled = cancelled,
                    subjectMatte = matte,
                    segmentation = segmentation,
                    boundaryRefinementModel = boundaryRefinementModel?.first
                )
                val derivative = store.save(
                    sourcePath = sourcePath,
                    model = depthModel,
                    depth = depth,
                    cancelled = cancelled,
                    ldiLite = ldi
                )
                val geometry = checkNotNull(ldi).geometry
                val reloaded = checkNotNull(store.load(sourcePath)) {
                    "无法回读刚保存的空间派生"
                }
                try {
                    checkNotNull(reloaded.ldiLite?.geometry?.backgroundMotionBasis) {
                        "隐藏背景连续运动基未能从派生重建"
                    }
                    check(reloaded.manifest.segmentationModelId != null) {
                        "派生未保存分割模型来源"
                    }
                    if (boundaryRefinementModel != null) {
                        check(reloaded.manifest.boundaryRefinementModelId != null) {
                            "派生未保存边界细化模型来源"
                        }
                    }
                } finally {
                    reloaded.ldiLite?.backgroundBitmap?.recycle()
                }
                Log.i(
                    TAG,
                    "done path=$sourcePath renderer=${derivative.manifest.renderer} " +
                        "mesh=${geometry.width}x${geometry.height} " +
                        "cuts=${geometry.cutRight.count { it } + geometry.cutDown.count { it }} " +
                        "hidden=${geometry.hiddenBackgroundMask.count { it }} " +
                        "displayAlpha=${ldi?.displayAlpha != null} " +
                        "boundaryRefinement=${boundaryRefinementModel?.first?.stableId} " +
                        "backgroundMotion=true"
                )
            } catch (error: Throwable) {
                Log.e(TAG, "failed path=$sourcePath", error)
            } finally {
                ldi?.backgroundBitmap?.recycle()
                bitmap?.recycle()
                pending.finish()
            }
        }, "SpatialVNextProbe").start()
    }

    companion object {
        private const val TAG = "SpatialVNextProbe"
        private const val EXTRA_PATH = "path"
    }
}
