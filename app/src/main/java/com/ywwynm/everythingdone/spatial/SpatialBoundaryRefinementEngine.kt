package com.ywwynm.everythingdone.spatial

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class SpatialBoundaryRefinementEngine(
    private val context: Context
) {

    fun refine(
        bitmap: Bitmap,
        segmentation: SpatialSegmentationData,
        model: SpatialBoundaryRefinementModel,
        cancelled: AtomicBoolean
    ): SpatialSegmentationData {
        check(!cancelled.get()) { "任务已取消" }
        check(SpatialBoundaryRefinementModelStore.isInstalled(context, model)) {
            "边界细化模型尚未安装"
        }
        check(SpatialRuntimeStore.isInstalled(context)) { "空间计算组件尚未安装" }
        check(SpatialBoundaryRefinementModelStore.isDeviceEligible(context, model)) {
            "设备总内存不足"
        }
        check(SpatialBoundaryRefinementModelStore.hasSufficientAvailableMemory(context, model)) {
            "当前可用内存不足，请关闭其它大型应用后重试"
        }
        if (segmentation.instances.isEmpty()) return segmentation
        SpatialRuntimeStore.ensureLoaded(context)
        val candidates = runModel(bitmap, segmentation, model, cancelled)
        check(!cancelled.get()) { "任务已取消" }
        return SpatialInferenceTrace.measure(SpatialInferenceTrace.BOUNDARY_POST) {
            SpatialBoundaryRefinementPostprocessor.refine(segmentation, candidates)
        }
    }

    fun selfTest(model: SpatialBoundaryRefinementModel): Boolean {
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(bitmap.width * bitmap.height) { index ->
            val x = index % bitmap.width
            val y = index / bitmap.width
            Color.rgb((x * 3) and 0xff, (y * 5) and 0xff, ((x + y) * 2) and 0xff)
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val width = SpatialSegmentationModel.RF_DETR_SEG_NANO.inputSize
        val labels = ByteArray(width * width)
        val alpha = ByteArray(labels.size)
        for (y in 70 until 245) {
            for (x in 80 until 230) {
                val index = y * width + x
                labels[index] = 1
                alpha[index] = 0xff.toByte()
            }
        }
        val segmentation = SpatialSegmentationData(
            width = width,
            height = width,
            labels = labels,
            alpha = alpha,
            instances = listOf(
                SpatialSegmentationInstance(
                    label = 1,
                    classId = SpatialSegmentationPostprocessor.PERSON_CLASS_ID,
                    confidence = 0.9f,
                    pixelCount = 150 * 175,
                    box = SpatialNormalizedBox(80f / width, 70f / width, 230f / width, 245f / width)
                )
            )
        )
        return try {
            val candidates = runModel(bitmap, segmentation, model, AtomicBoolean(false))
            candidates.size == 1 &&
                candidates.single().maskLogits.size == model.maskSize * model.maskSize &&
                candidates.single().maskLogits.all(Float::isFinite) &&
                candidates.single().predictedIou.isFinite()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * QNN 执行期失败时整段用 CPU 重跑，见 [SpatialQnnSessionFactory.withExecuteFallback]。
     * 上 QNN 的只有 image encoder，拉黑与 [openEncoder] 建 session 统一用
     * [SpatialBoundaryRefinementModel.qnnEncoderModelId]。
     */
    private fun runModel(
        bitmap: Bitmap,
        segmentation: SpatialSegmentationData,
        model: SpatialBoundaryRefinementModel,
        cancelled: AtomicBoolean
    ): List<SpatialBoundaryRefinementPostprocessor.Candidate> =
        SpatialQnnSessionFactory.withExecuteFallback(context, model.qnnEncoderModelId) {
            runModelOnce(bitmap, segmentation, model, cancelled)
        }

    private fun runModelOnce(
        bitmap: Bitmap,
        segmentation: SpatialSegmentationData,
        model: SpatialBoundaryRefinementModel,
        cancelled: AtomicBoolean
    ): List<SpatialBoundaryRefinementPostprocessor.Candidate> {
        val environment = SpatialOrtRuntime.environment(context)
        val components = model.components.associateBy(SpatialBoundaryRefinementComponent::fileName)
        val encoderFile = SpatialBoundaryRefinementModelStore.componentFile(
            context,
            model,
            components.getValue(ENCODER_FILE)
        )
        val promptFile = SpatialBoundaryRefinementModelStore.componentFile(
            context,
            model,
            components.getValue(PROMPT_ENCODER_FILE)
        )
        val decoderFile = SpatialBoundaryRefinementModelStore.componentFile(
            context,
            model,
            components.getValue(DECODER_FILE)
        )
        fun openSession(path: String, options: OrtSession.SessionOptions): OrtSession =
            SpatialInferenceTrace.measure(SpatialInferenceTrace.BOUNDARY_SESSION) {
                environment.createSession(path, options)
            }

        /**
         * 只有 image encoder 值得上 QNN：它固定 1024²、实测占 boundary 阶段的绝大部分
         * （475 ms，而 prompt encoder 1.6 ms、decoder 56 ms）。后两者交给 CPU，
         * 省下两次图编译与两份 context binary。
         */
        fun openEncoder(options: OrtSession.SessionOptions): OrtSession {
            val component = components.getValue(ENCODER_FILE)
            val qnn = SpatialQnnSessionFactory.createSession(
                context = context,
                environment = environment,
                request = SpatialQnnSessionFactory.Request(
                    modelFile = encoderFile,
                    modelId = model.qnnEncoderModelId,
                    modelVersion = model.version,
                    modelSha256 = component.sha256,
                    shapeTag = "${model.inputSize}x${model.inputSize}"
                )
            ) { qnnOptions ->
                qnnOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
                qnnOptions.setInterOpNumThreads(1)
                qnnOptions.setIntraOpNumThreads(1)
            }
            return qnn?.session ?: openSession(encoderFile.absolutePath, options)
        }
        sessionOptions().use { options ->
            openEncoder(options).use { encoder ->
                openSession(promptFile.absolutePath, options).use { promptEncoder ->
                    openSession(decoderFile.absolutePath, options).use { decoder ->
                        validateSessionContracts(encoder, promptEncoder, decoder)
                        val input = SpatialInferenceTrace.measure(
                            SpatialInferenceTrace.BOUNDARY_PREPARE
                        ) {
                            prepareInput(bitmap, model, cancelled)
                        }
                        val inputShape = longArrayOf(
                            1,
                            3,
                            model.inputSize.toLong(),
                            model.inputSize.toLong()
                        )
                        OnnxTensor.createTensor(environment, input, inputShape).use { tensor ->
                            SpatialInferenceTrace.measure(
                                SpatialInferenceTrace.BOUNDARY_RUN_ENCODER
                            ) {
                                encoder.run(mapOf(ENCODER_INPUT to tensor))
                            }.use { encoderOutput ->
                                check(!cancelled.get()) { "任务已取消" }
                                val imageEmbeddings = encoderOutput[0] as OnnxTensor
                                val highResolution0 = encoderOutput[1] as OnnxTensor
                                val highResolution1 = encoderOutput[2] as OnnxTensor
                                return segmentation.instances.map { instance ->
                                    check(!cancelled.get()) { "任务已取消" }
                                    val promptBox = promptBox(instance, segmentation, model)
                                    OnnxTensor.createTensor(
                                        environment,
                                        floatBuffer(promptBox),
                                        longArrayOf(1, 4)
                                    ).use { boxTensor ->
                                        SpatialInferenceTrace.measure(
                                            SpatialInferenceTrace.BOUNDARY_RUN_PROMPT
                                        ) {
                                            promptEncoder.run(
                                                mapOf(PROMPT_ENCODER_INPUT to boxTensor)
                                            )
                                        }.use { promptOutput ->
                                            val sparse = promptOutput[0] as OnnxTensor
                                            val dense = promptOutput[1] as OnnxTensor
                                            SpatialInferenceTrace.measure(
                                                SpatialInferenceTrace.BOUNDARY_RUN_DECODER
                                            ) {
                                                decoder.run(
                                                    mapOf(
                                                        DECODER_IMAGE_EMBEDDINGS to imageEmbeddings,
                                                        DECODER_SPARSE_EMBEDDINGS to sparse,
                                                        DECODER_DENSE_EMBEDDINGS to dense,
                                                        DECODER_HIGH_RESOLUTION_0 to highResolution0,
                                                        DECODER_HIGH_RESOLUTION_1 to highResolution1
                                                    )
                                                )
                                            }.use { decoded ->
                                                check(!cancelled.get()) { "任务已取消" }
                                                val masks = decoded[0] as OnnxTensor
                                                val iou = decoded[1] as OnnxTensor
                                                check(
                                                    masks.info.shape.contentEquals(
                                                        longArrayOf(
                                                            1,
                                                            1,
                                                            model.maskSize.toLong(),
                                                            model.maskSize.toLong()
                                                        )
                                                    )
                                                ) { "EdgeTAM mask 输出形状不符" }
                                                check(
                                                    iou.info.shape.contentEquals(longArrayOf(1, 1))
                                                ) { "EdgeTAM IoU 输出形状不符" }
                                                val maskLogits = FloatArray(
                                                    model.maskSize * model.maskSize
                                                )
                                                masks.floatBuffer.get(maskLogits)
                                                val predictedIou = iou.floatBuffer.get(0)
                                                SpatialBoundaryRefinementPostprocessor.Candidate(
                                                    label = instance.label,
                                                    predictedIou = predictedIou,
                                                    maskLogits = maskLogits,
                                                    width = model.maskSize,
                                                    height = model.maskSize
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun prepareInput(
        bitmap: Bitmap,
        model: SpatialBoundaryRefinementModel,
        cancelled: AtomicBoolean
    ): java.nio.FloatBuffer {
        val size = model.inputSize
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== bitmap) scaled.recycle()
        check(!cancelled.get()) { "任务已取消" }
        val result = ByteBuffer.allocateDirect(3 * size * size * Float.SIZE_BYTES)
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
                result.put((value - IMAGENET_MEAN[channel]) / IMAGENET_STD[channel])
            }
        }
        result.rewind()
        return result
    }

    private fun promptBox(
        instance: SpatialSegmentationInstance,
        segmentation: SpatialSegmentationData,
        model: SpatialBoundaryRefinementModel
    ): FloatArray {
        val box = instance.box ?: boxFromOwnership(instance.label, segmentation)
        return floatArrayOf(
            box.left * model.inputSize,
            box.top * model.inputSize,
            box.right * model.inputSize,
            box.bottom * model.inputSize
        )
    }

    private fun boxFromOwnership(
        label: Int,
        segmentation: SpatialSegmentationData
    ): SpatialNormalizedBox {
        var minX = segmentation.width
        var minY = segmentation.height
        var maxX = -1
        var maxY = -1
        for (index in segmentation.labels.indices) {
            if ((segmentation.labels[index].toInt() and 0xff) != label) continue
            val x = index % segmentation.width
            val y = index / segmentation.width
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
        }
        check(maxX >= minX && maxY >= minY) { "实例缺少 prompt 区域" }
        return SpatialNormalizedBox(
            left = minX.toFloat() / segmentation.width,
            top = minY.toFloat() / segmentation.height,
            right = (maxX + 1f) / segmentation.width,
            bottom = (maxY + 1f) / segmentation.height
        )
    }

    private fun sessionOptions(): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
            setInterOpNumThreads(1)
            setIntraOpNumThreads(INFERENCE_THREADS)
        }

    private fun validateSessionContracts(
        encoder: OrtSession,
        promptEncoder: OrtSession,
        decoder: OrtSession
    ) {
        check(encoder.inputNames == setOf(ENCODER_INPUT)) { "EdgeTAM encoder 输入 ABI 不符" }
        check(promptEncoder.inputNames == setOf(PROMPT_ENCODER_INPUT)) {
            "EdgeTAM prompt encoder 输入 ABI 不符"
        }
        check(
            decoder.inputNames == setOf(
                DECODER_IMAGE_EMBEDDINGS,
                DECODER_SPARSE_EMBEDDINGS,
                DECODER_DENSE_EMBEDDINGS,
                DECODER_HIGH_RESOLUTION_0,
                DECODER_HIGH_RESOLUTION_1
            )
        ) { "EdgeTAM decoder 输入 ABI 不符" }
    }

    private fun floatBuffer(values: FloatArray): java.nio.FloatBuffer =
        ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                rewind()
            }

    companion object {
        private const val INFERENCE_THREADS = 4
        private const val ENCODER_FILE = "edgetam_image_encoder_1024.onnx"
        private const val PROMPT_ENCODER_FILE = "edgetam_box_prompt_encoder.onnx"
        private const val DECODER_FILE = "edgetam_mask_decoder.onnx"
        private const val ENCODER_INPUT = "image"
        private const val PROMPT_ENCODER_INPUT = "boxes"
        private const val DECODER_IMAGE_EMBEDDINGS = "image_embeddings"
        private const val DECODER_SPARSE_EMBEDDINGS = "sparse_prompt_embeddings"
        private const val DECODER_DENSE_EMBEDDINGS = "dense_prompt_embeddings"
        private const val DECODER_HIGH_RESOLUTION_0 = "high_res_feat_0"
        private const val DECODER_HIGH_RESOLUTION_1 = "high_res_feat_1"
        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
