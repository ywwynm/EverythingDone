package com.ywwynm.everythingdone.spatial

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class SpatialSegmentationEngine(
    private val context: Context
) {
    fun generate(
        bitmap: Bitmap,
        model: SpatialSegmentationModel,
        cancelled: AtomicBoolean
    ): SpatialSegmentationData {
        check(!cancelled.get()) { "任务已取消" }
        check(SpatialSegmentationModelStore.modelFile(context, model).isFile) {
            "实例分割模型文件不存在"
        }
        check(SpatialRuntimeStore.isInstalled(context)) { "空间计算组件尚未安装" }
        check(SpatialSegmentationModelStore.isDeviceEligible(context, model)) {
            "设备总内存不足"
        }
        check(SpatialSegmentationModelStore.hasSufficientAvailableMemory(context, model)) {
            "当前可用内存不足，请关闭其它大型应用后重试"
        }
        SpatialRuntimeStore.ensureLoaded(context)
        val input = prepareInput(bitmap, model, cancelled)
        val raw = runModel(input, model, cancelled)
        check(!cancelled.get()) { "任务已取消" }
        return SpatialSegmentationPostprocessor.process(
            logits = raw.logits,
            masks = raw.masks,
            model = model,
            boxes = raw.boxes
        )
    }

    fun selfTest(model: SpatialSegmentationModel): Boolean {
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(96 * 64) { index ->
            val x = index % 96
            val y = index / 96
            android.graphics.Color.rgb((x * 3) and 0xff, (y * 5) and 0xff, (x + y) and 0xff)
        }
        bitmap.setPixels(pixels, 0, 96, 0, 0, 96, 64)
        return try {
            val cancelled = AtomicBoolean(false)
            val raw = runModel(prepareInput(bitmap, model, cancelled), model, cancelled)
            val result = SpatialSegmentationPostprocessor.process(
                logits = raw.logits,
                masks = raw.masks,
                model = model,
                boxes = raw.boxes
            )
            result.labels.size == model.inputSize * model.inputSize &&
                result.alpha.size == result.labels.size
        } finally {
            bitmap.recycle()
        }
    }

    private fun prepareInput(
        bitmap: Bitmap,
        model: SpatialSegmentationModel,
        cancelled: AtomicBoolean
    ): java.nio.FloatBuffer {
        val size = model.inputSize
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== bitmap) scaled.recycle()
        check(!cancelled.get()) { "任务已取消" }
        val buffer = ByteBuffer.allocateDirect(3 * size * size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        repeat(3) { channel ->
            val shift = when (channel) {
                0 -> 16
                1 -> 8
                else -> 0
            }
            for (pixel in pixels) {
                val value = ((pixel ushr shift) and 0xff) / 255f
                buffer.put((value - IMAGENET_MEAN[channel]) / IMAGENET_STD[channel])
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun runModel(
        input: java.nio.FloatBuffer,
        model: SpatialSegmentationModel,
        cancelled: AtomicBoolean
    ): RawOutput {
        val environment = SpatialOrtRuntime.environment(context)
        OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
            options.setInterOpNumThreads(1)
            options.setIntraOpNumThreads(INFERENCE_THREADS)
            val modelFile = SpatialSegmentationModelStore.modelFile(context, model)
            environment.createSession(modelFile.absolutePath, options).use { session ->
                check(!cancelled.get()) { "任务已取消" }
                val inputName = session.inputNames.single()
                val shape = longArrayOf(
                    1,
                    3,
                    model.inputSize.toLong(),
                    model.inputSize.toLong()
                )
                OnnxTensor.createTensor(environment, input, shape).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { output ->
                        check(!cancelled.get()) { "任务已取消" }
                        check(output.size() == 3) { "实例分割输出数量不符" }
                        val boxesTensor = output[0] as OnnxTensor
                        val logitsTensor = output[1] as OnnxTensor
                        val masksTensor = output[2] as OnnxTensor
                        check(
                            boxesTensor.info.shape.contentEquals(
                                longArrayOf(1, model.queryCount.toLong(), 4)
                            )
                        ) { "实例分割检测框输出形状不符" }
                        check(
                            logitsTensor.info.shape.contentEquals(
                                longArrayOf(
                                    1,
                                    model.queryCount.toLong(),
                                    model.classLogitCount.toLong()
                                )
                            )
                        ) { "实例分割分类输出形状不符" }
                        check(
                            masksTensor.info.shape.contentEquals(
                                longArrayOf(
                                    1,
                                    model.queryCount.toLong(),
                                    model.maskSize.toLong(),
                                    model.maskSize.toLong()
                                )
                            )
                        ) { "实例分割 mask 输出形状不符" }
                        val boxes = FloatArray(model.queryCount * 4)
                        val logits = FloatArray(model.queryCount * model.classLogitCount)
                        val masks = FloatArray(
                            model.queryCount * model.maskSize * model.maskSize
                        )
                        boxesTensor.floatBuffer.get(boxes)
                        logitsTensor.floatBuffer.get(logits)
                        masksTensor.floatBuffer.get(masks)
                        return RawOutput(boxes, logits, masks)
                    }
                }
            }
        }
    }

    companion object {
        private const val INFERENCE_THREADS = 4
        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    private data class RawOutput(
        val boxes: FloatArray,
        val logits: FloatArray,
        val masks: FloatArray
    )
}
