package com.ywwynm.everythingdone.spatial

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class SpatialDepthEngine(
    private val context: Context
) {
    enum class Stage {
        PREPARING,
        INFERENCE,
        POST_PROCESSING
    }

    fun generate(
        bitmap: Bitmap,
        model: SpatialDepthModel,
        cancelled: AtomicBoolean,
        onStage: (Stage) -> Unit = {}
    ): SpatialDepthData {
        check(!cancelled.get()) { "任务已取消" }
        check(SpatialModelStore.isInstalled(context, model)) { "模型尚未安装" }
        check(SpatialRuntimeStore.isInstalled(context)) { "空间计算组件尚未安装" }
        check(SpatialModelStore.isDeviceEligible(context, model)) { "设备总内存不足" }
        check(SpatialModelStore.hasSufficientAvailableMemory(context, model)) {
            "当前可用内存不足，请关闭其它大型应用后重试"
        }
        SpatialRuntimeStore.ensureLoaded(context)

        onStage(Stage.PREPARING)
        val prepared = prepareInput(bitmap, model, cancelled)
        check(!cancelled.get()) { "任务已取消" }

        onStage(Stage.INFERENCE)
        val rawDepth = runModel(model, prepared.input, cancelled)
        check(!cancelled.get()) { "任务已取消" }

        onStage(Stage.POST_PROCESSING)
        return SpatialDepthNormalizer.normalizeAndCrop(
            raw = rawDepth,
            inputSize = model.inputSize,
            contentLeft = prepared.contentLeft,
            contentTop = prepared.contentTop,
            contentWidth = prepared.contentWidth,
            contentHeight = prepared.contentHeight,
            closeRadius = closeRadius(model),
            disparityContrast = model.disparityContrast,
            keepRawInverseDepth = model.outputIsDepth
        )
    }

    /**
     * 下载完成后的硬自检。使用确定性小纹理，检查完整 session、输出形状与数值。
     */
    fun selfTest(model: SpatialDepthModel): Boolean {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(64 * 48) { index ->
            val x = index % 64
            val y = index / 64
            android.graphics.Color.rgb(x * 4, y * 5, (x * 3 + y * 7) and 0xff)
        }
        bitmap.setPixels(pixels, 0, 64, 0, 0, 64, 48)
        return try {
            val cancelled = AtomicBoolean(false)
            val prepared = prepareInput(bitmap, model, cancelled)
            val raw = runModel(model, prepared.input, cancelled)
            val result = SpatialDepthNormalizer.normalizeAndCrop(
                raw = raw,
                inputSize = model.inputSize,
                contentLeft = prepared.contentLeft,
                contentTop = prepared.contentTop,
                contentWidth = prepared.contentWidth,
                contentHeight = prepared.contentHeight,
                closeRadius = closeRadius(model),
                disparityContrast = model.disparityContrast,
                keepRawInverseDepth = model.outputIsDepth
            )
            result.values.all { it.isFinite() } && result.values.any { it > 0.05f }
        } finally {
            bitmap.recycle()
        }
    }

    private fun runModel(
        model: SpatialDepthModel,
        inputBuffer: java.nio.FloatBuffer,
        cancelled: AtomicBoolean
    ): FloatArray {
        SpatialRuntimeStore.ensureLoaded(context)
        val environment = SpatialOrtRuntime.environment(context)
        OrtSession.SessionOptions().use { options ->
            // Runtime r3 起算子清单是五模型 BASIC+EXTENDED 优化后的并集（含图优化器
            // 新造的 FusedConv/FusedMatMul/Gelu/SkipLayerNormalization 与 Gemm/Split），
            // 2026-08-01 已在 OnePlus r3-PoC 构建上以 EXTENDED 实测通过且数值与 NO_OPT
            // 逐位一致。App 侧由 REQUIRED_PACKAGE_VERSION 保证只加载 r3+。
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
            options.setInterOpNumThreads(1)
            options.setIntraOpNumThreads(INFERENCE_THREADS)
            val modelFile = SpatialModelStore.modelFile(context, model)
            environment.createSession(modelFile.absolutePath, options).use { session ->
                check(!cancelled.get()) { "任务已取消" }
                val inputName = session.inputNames.single()
                OnnxTensor.createTensor(environment, inputBuffer, model.inputShape).use { input ->
                    session.run(mapOf(inputName to input)).use { output ->
                        check(!cancelled.get()) { "任务已取消" }
                        val tensor = output[0] as OnnxTensor
                        check(tensor.info.shape.contentEquals(model.outputShape)) {
                            "模型输出形状不符：${tensor.info.shape.contentToString()}"
                        }
                        val values = tensor.floatBuffer
                        val result = FloatArray(model.inputSize * model.inputSize)
                        values.get(result)
                        if (model.outputIsDepth) {
                            // 契约要求相对逆深度（近大远小）；深度输出取倒数统一方向，
                            // 非正值按无穷远处理。
                            for (index in result.indices) {
                                val depth = result[index]
                                result[index] = if (depth > 1e-6f) 1f / depth else 0f
                            }
                        }
                        return result
                    }
                }
            }
        }
    }

    private fun prepareInput(
        bitmap: Bitmap,
        model: SpatialDepthModel,
        cancelled: AtomicBoolean
    ): PreparedInput {
        check(bitmap.width > 0 && bitmap.height > 0)
        val size = model.inputSize
        val scale = minOf(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
        val contentWidth = (bitmap.width * scale).roundToInt().coerceIn(1, size)
        val contentHeight = (bitmap.height * scale).roundToInt().coerceIn(1, size)
        val contentLeft = (size - contentWidth) / 2
        val contentTop = (size - contentHeight) / 2

        val sourcePixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(sourcePixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val planes = Array(3) { FloatArray(size * size) }

        for (targetY in 0 until size) {
            if ((targetY and 15) == 0) check(!cancelled.get()) { "任务已取消" }
            val sourceY = (((targetY - contentTop + 0.5f) / scale) - 0.5f)
                .roundToInt()
                .coerceIn(0, bitmap.height - 1)
            for (targetX in 0 until size) {
                val sourceX = (((targetX - contentLeft + 0.5f) / scale) - 0.5f)
                    .roundToInt()
                    .coerceIn(0, bitmap.width - 1)
                val color = sourcePixels[sourceY * bitmap.width + sourceX]
                val index = targetY * size + targetX
                planes[0][index] = ((color ushr 16) and 0xff) / 255f
                planes[1][index] = ((color ushr 8) and 0xff) / 255f
                planes[2][index] = (color and 0xff) / 255f
            }
        }

        val direct = ByteBuffer.allocateDirect(3 * size * size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (channel in 0..2) {
            val plane = planes[channel]
            if (model.imageNetNormalization) {
                val mean = IMAGENET_MEAN[channel]
                val standardDeviation = IMAGENET_STD[channel]
                for (value in plane) direct.put((value - mean) / standardDeviation)
            } else {
                direct.put(plane)
            }
        }
        direct.rewind()
        return PreparedInput(
            input = direct,
            contentLeft = contentLeft,
            contentTop = contentTop,
            contentWidth = contentWidth,
            contentHeight = contentHeight
        )
    }

    private data class PreparedInput(
        val input: java.nio.FloatBuffer,
        val contentLeft: Int,
        val contentTop: Int,
        val contentWidth: Int,
        val contentHeight: Int
    )

    companion object {
        private const val INFERENCE_THREADS = 4
        /** 锐边模型的闭运算半径（深度分辨率像素）：吸收 ≤ 约 2r 宽的发丝间隙。 */
        private const val SHARP_EDGE_CLOSE_RADIUS = 3
        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        private fun closeRadius(model: SpatialDepthModel): Int =
            if (model.sharpDepthEdges) SHARP_EDGE_CLOSE_RADIUS else 0
    }
}
