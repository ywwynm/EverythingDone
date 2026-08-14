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
            // 这台设备的 logcat 每秒数千行系统日志，5 MiB 上限装不下一次生成的时长；
            // 结论一律以落盘那份为准（追加，多张图共用一份）。
            val probeLog = File(appContext.getExternalFilesDir(null), "probe.log")
            fun report(line: String) {
                Log.i(TAG, line)
                runCatching { probeLog.appendText(line + "\n") }
            }
            val startedAt = System.nanoTime()
            // 两张表并列，各自自洽，不互相嵌套计数：
            // stageMillis 是阶段墙钟（build 含补全），其和约等于 total；
            // SpatialInferenceTrace 是引擎内部的 session/run/prepare/post 细分。
            val stageMillis = LinkedHashMap<String, Long>()
            fun <T> step(name: String, block: () -> T): T {
                val at = System.nanoTime()
                try {
                    return block()
                } finally {
                    stageMillis[name] =
                        (stageMillis[name] ?: 0L) + (System.nanoTime() - at) / 1_000_000
                }
            }
            SpatialInferenceTrace.start()
            var ldi: SpatialLdiLiteData? = null
            var bitmap: Bitmap? = null
            try {
                bitmap = step("decode") {
                    checkNotNull(BitmapFactory.decodeFile(sourcePath)) {
                        "无法解码探针图片"
                    }
                }
                val source = checkNotNull(bitmap)
                val cancelled = AtomicBoolean(false)
                val depthModel = SpatialPreferences.selectedModel(appContext)
                val store = SpatialDerivativeStore(appContext)
                val generatedDepth = step("depth") {
                    SpatialDepthEngine(appContext).generate(
                        source,
                        depthModel,
                        cancelled
                    )
                }
                val depth = store.retainedStrength(sourcePath)?.let {
                    generatedDepth.copy(defaultStrength = it)
                } ?: generatedDepth

                val matteModel = SpatialMattingModel.MODNET_PHOTOGRAPHIC
                val matte = if (
                    SpatialMattingModelStore.isInstalled(appContext, matteModel) &&
                    SpatialMattingModelStore.hasSufficientAvailableMemory(appContext, matteModel)
                ) {
                    matteModel to step("matte") {
                        SpatialMattingEngine(appContext).generate(
                            source,
                            matteModel,
                            cancelled
                        )
                    }
                } else {
                    null
                }
                val segmentationModel = SpatialPreferences.selectedSegmentationModel(appContext)
                var segmentation = segmentationModel?.takeIf {
                    SpatialSegmentationModelStore.isInstalled(appContext, it) &&
                        SpatialSegmentationModelStore.isDeviceEligible(appContext, it) &&
                        SpatialSegmentationModelStore.hasSufficientAvailableMemory(appContext, it)
                }?.let {
                    it to step("segmentation") {
                        SpatialSegmentationEngine(appContext).generate(source, it, cancelled)
                    }
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
                            model to step("boundary") {
                                SpatialBoundaryRefinementEngine(appContext).refine(
                                    source,
                                    data,
                                    model,
                                    cancelled
                                )
                            }
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
                ldi = step("build") {
                    SpatialVNextBuilder(SpatialInpaintingEngine(appContext)).build(
                        bitmap = source,
                        depth = depth,
                        inpaintingModel = SpatialPreferences.selectedInpaintingModel(appContext),
                        inpaintingQuality = SpatialPreferences.inpaintingQuality(appContext),
                        cancelled = cancelled,
                        subjectMatte = matte,
                        segmentation = segmentation,
                        boundaryRefinementModel = boundaryRefinementModel?.first
                    )
                }
                val derivative = step("save") {
                    store.save(
                        sourcePath = sourcePath,
                        model = depthModel,
                        depth = depth,
                        cancelled = cancelled,
                        ldiLite = ldi
                    )
                }
                val geometry = checkNotNull(ldi).geometry
                // `load` 把一切包在 runCatching{}.getOrNull() 里，manifest 判死与后续异常
                // 会塌成同一个 null。分两步报，才知道该看哪一侧。
                val reloadedManifest = store.loadManifest(sourcePath)
                report(
                    "reload manifest=${reloadedManifest != null} " +
                        "schema=${reloadedManifest?.schemaVersion} " +
                        "renderer=${reloadedManifest?.renderer}"
                )
                val reloaded = checkNotNull(store.load(sourcePath)) {
                    if (reloadedManifest == null) {
                        "manifest 未通过校验（schema/renderer/摘要不一致）"
                    } else {
                        "manifest 通过但派生回读失败（文件读取或一致性检查抛异常）"
                    }
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
                report(
                    "done path=${File(sourcePath).name} " +
                        "renderer=${derivative.manifest.renderer} " +
                        "schema=${derivative.manifest.schemaVersion} " +
                        "depthModel=${depthModel.stableId} " +
                        "inpaint=${SpatialPreferences.selectedInpaintingModel(appContext).stableId} " +
                        "metric=${depth.metricDepth != null} " +
                        "fx=${depth.intrinsics?.fx?.let { String.format(java.util.Locale.US, "%.1f", it) }} " +
                        "matte=${matte != null} seg=${segmentation?.first?.stableId} " +
                        "ms=${(System.nanoTime() - startedAt) / 1_000_000}"
                )
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
                runCatching {
                    probeLog.appendText(
                        "failed path=${File(sourcePath).name} " +
                            "${error.javaClass.simpleName}: ${error.message}\n"
                    )
                }
            } finally {
                // 失败也要出表：已跑完的阶段同样是数据。
                val samples = SpatialInferenceTrace.stop()
                report(
                    "stages path=${File(sourcePath).name} " +
                        "total=${(System.nanoTime() - startedAt) / 1_000_000}ms " +
                        stageMillis.entries.joinToString(" ") { "${it.key}=${it.value}ms" }
                )
                report(
                    "trace path=${File(sourcePath).name}\n" +
                        SpatialInferenceTrace.format(samples)
                )
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
