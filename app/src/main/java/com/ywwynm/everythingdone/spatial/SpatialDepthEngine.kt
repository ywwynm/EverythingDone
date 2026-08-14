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

        if (model.outputContract == SpatialDepthOutputContract.MOGE_POINT_MAP) {
            return generateMoge(bitmap, model, cancelled, onStage)
        }

        onStage(Stage.PREPARING)
        val prepared = SpatialInferenceTrace.measure(SpatialInferenceTrace.DEPTH_PREPARE) {
            prepareInput(bitmap, model, cancelled)
        }
        check(!cancelled.get()) { "任务已取消" }

        onStage(Stage.INFERENCE)
        val rawDepth = runModel(model, prepared.input, cancelled)
        check(!cancelled.get()) { "任务已取消" }

        onStage(Stage.POST_PROCESSING)
        return SpatialInferenceTrace.measure(SpatialInferenceTrace.DEPTH_POST) {
            SpatialDepthNormalizer.normalizeAndCrop(
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
            if (model.outputContract == SpatialDepthOutputContract.MOGE_POINT_MAP) {
                // MoGe 走的是两输入四输出的 point map 路径，prepareInput/runModel 的方形
                // 契约对它不成立；自检必须走同一条真实路径，否则等于没测。
                val moge = generateMoge(bitmap, model, cancelled) {}
                return moge.values.all { it.isFinite() } &&
                    moge.intrinsics != null && moge.intrinsics.fx > 1f &&
                    moge.metricDepth?.any { it.isFinite() && it > 0f } == true
            }
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

    /**
     * MoGe-2 的 point map 路径。与其余三个模型完全不同：
     *
     * - **两个输入**：`image [1,3,H,W]`（RGB 0..1、**按源图长宽比给，不做方形填充**）
     *   与 `num_tokens` int64 标量；
     * - **四个输出**：`points [1,H,W,3]` / `normal` / `mask` / 米制尺度。最后一路的
     *   名字**两份官方导出不一致**（ViT-S 是 `scale`，ViT-B 是 `metric_scale`），
     *   见 [MOGE_SCALE_OUTPUT_NAMES]；
     * - 内参不在图里，由 [SpatialMogeGeometry] 从 point map 反解（D205）。
     *
     * 模型内部按 `num_tokens` 决定推理分辨率再上采样回输入尺寸，因此这里直接按渲染网格
     * 尺度喂图即可，不必自己缩放到某个方形。
     */
    private fun generateMoge(
        bitmap: Bitmap,
        model: SpatialDepthModel,
        cancelled: AtomicBoolean,
        onStage: (Stage) -> Unit
    ): SpatialDepthData {
        // `num_tokens` 决定模型内部的推理分辨率，是 MoGe 唯一有效的细节旋钮
        // （提输入分辨率无效，四档有效带宽持平）。耗时随它超线性增长，见 [SpatialDepthDetail]。
        val numTokens = SpatialPreferences.depthDetail(context).numTokens.toLong()
        onStage(Stage.PREPARING)
        val prepareStartedAt = System.nanoTime()
        // 长边不超过 inputSize，两边对齐到 ViT patch（14）的倍数，且**保持长宽比**
        val (width, height) = SpatialMogeGeometry.alignToPatchPreservingAspect(
            bitmap.width, bitmap.height, model.inputSize
        )
        val scaled = if (width == bitmap.width && height == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        val pixelCount = width * height
        val pixels = IntArray(pixelCount)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()
        val imageBuffer = ByteBuffer.allocateDirect(pixelCount * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (channel in 0..2) {
            val shift = 16 - channel * 8
            for (index in 0 until pixelCount) {
                imageBuffer.put((((pixels[index] ushr shift) and 0xff) / 255f))
            }
        }
        imageBuffer.rewind()
        SpatialInferenceTrace.record(
            SpatialInferenceTrace.DEPTH_PREPARE,
            System.nanoTime() - prepareStartedAt,
            failed = false
        )
        check(!cancelled.get()) { "任务已取消" }

        onStage(Stage.INFERENCE)
        val environment = SpatialOrtRuntime.environment(context)
        val created = SpatialInferenceTrace.measure(SpatialInferenceTrace.DEPTH_SESSION) {
            createSession(model)
        }
        val recovered = created.use { session ->
            OnnxTensor.createTensor(
                environment, imageBuffer,
                longArrayOf(1, 3, height.toLong(), width.toLong())
            ).use { image ->
                OnnxTensor.createTensor(environment, java.lang.Long.valueOf(numTokens))
                    .use { tokens ->
                    SpatialInferenceTrace.measure(SpatialInferenceTrace.DEPTH_RUN) {
                        session.run(
                            mapOf(MOGE_IMAGE_INPUT to image, MOGE_TOKENS_INPUT to tokens)
                        )
                    }.use { output ->
                        check(!cancelled.get()) { "任务已取消" }
                        val named = session.outputNames.toList()
                        fun tensor(name: String): OnnxTensor {
                            val at = named.indexOf(name)
                            check(at >= 0) { "MoGe 输出缺少 $name" }
                            return output[at] as OnnxTensor
                        }
                        val points = FloatArray(pixelCount * 3)
                        tensor(MOGE_POINTS_OUTPUT).floatBuffer.get(points)
                        val maskRaw = FloatArray(pixelCount)
                        tensor(MOGE_MASK_OUTPUT).floatBuffer.get(maskRaw)
                        val scaleName = MOGE_SCALE_OUTPUT_NAMES.firstOrNull { named.contains(it) }
                        check(scaleName != null) {
                            // 把实际输出名一并带出来——只说"缺少 scale"时，无法判断是模型
                            // 坏了还是导出换了名字（2026-08-13 ViT-B 上就是后者）。
                            "MoGe 输出缺少米制尺度（找过 " +
                                MOGE_SCALE_OUTPUT_NAMES.joinToString("/") +
                                "），实际输出为 " + named.joinToString("/")
                        }
                        val scaleValue = tensor(scaleName).floatBuffer.get(0)
                        SpatialInferenceTrace.measure(SpatialInferenceTrace.DEPTH_POST) {
                            SpatialMogeGeometry.recover(
                                points = points,
                                mask = BooleanArray(pixelCount) { maskRaw[it] > 0.5f },
                                scale = scaleValue,
                                width = width,
                                height = height
                            )
                        }
                    }
                }
            }
        }
        check(!cancelled.get()) { "任务已取消" }
        check(recovered.sampleCount >= 16 && recovered.fx > 1f) {
            "MoGe 未能反解出可用内参（有效点 ${recovered.sampleCount}、fx ${recovered.fx}）"
        }

        onStage(Stage.POST_PROCESSING)
        return SpatialInferenceTrace.measure(SpatialInferenceTrace.DEPTH_POST) {
            // 归一化通路与其余模型一致：逆深度 → 远 0 近 1；米制量另行随行，不参与归一化。
            val inverse = FloatArray(pixelCount) { index ->
                val z = recovered.depth[index]
                if (z.isFinite() && z > 1e-4f) 1f / z else 0f
            }
            SpatialDepthNormalizer.normalizeFromInverseDepth(
                inverseDepth = inverse,
                width = width,
                height = height,
                closeRadius = closeRadius(model),
                disparityContrast = model.disparityContrast,
                metricDepth = recovered.depth,
                intrinsics = SpatialDepthData.Intrinsics(
                    fx = recovered.fx, fy = recovered.fy,
                    cx = recovered.cx, cy = recovered.cy
                )
            )
        }
    }

    /** ViT patch 是 14；两边对齐到它的倍数，避免模型内部再做一次非整数缩放。 */
    private fun alignToPatch(value: Int): Int =
        (value / MOGE_PATCH).coerceAtLeast(2) * MOGE_PATCH


    /** 与 [runModel] 里同一套 session 参数；MoGe 路径是两输入四输出，不能复用 runModel。 */
    private fun createSession(model: SpatialDepthModel): OrtSession {
        SpatialRuntimeStore.ensureLoaded(context)
        val environment = SpatialOrtRuntime.environment(context)
        return OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
            options.setInterOpNumThreads(1)
            options.setIntraOpNumThreads(INFERENCE_THREADS)
            environment.createSession(
                SpatialModelStore.modelFile(context, model).absolutePath,
                options
            )
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
            val created = SpatialInferenceTrace.measure(SpatialInferenceTrace.DEPTH_SESSION) {
                environment.createSession(modelFile.absolutePath, options)
            }
            created.use { session ->
                check(!cancelled.get()) { "任务已取消" }
                val inputName = session.inputNames.single()
                OnnxTensor.createTensor(environment, inputBuffer, model.inputShape).use { input ->
                    SpatialInferenceTrace.measure(SpatialInferenceTrace.DEPTH_RUN) {
                        session.run(mapOf(inputName to input))
                    }.use { output ->
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

        // MoGe-2 的输入输出名与 ViT patch。num_tokens 现由 [SpatialDepthDetail] 提供，
        // 用户可在设置里选档；默认 1800 对应约 588×602 内在分辨率，与 720 长边网格匹配。
        private const val MOGE_IMAGE_INPUT = "image"
        private const val MOGE_TOKENS_INPUT = "num_tokens"
        private const val MOGE_POINTS_OUTPUT = "points"
        private const val MOGE_MASK_OUTPUT = "mask"

        /**
         * 米制尺度那一路输出的**候选名**。官方两份 ONNX 导出在这一项上不一致：
         * ViT-S 叫 `scale`，ViT-B 叫 `metric_scale`（2026-08-13 实测，其余输入输出
         * 逐项相同）。写死任一个都会让另一档在真机自检时报"MoGe 输出缺少 …"。
         * 顺序即优先级，只取第一个命中的。
         */
        private val MOGE_SCALE_OUTPUT_NAMES = listOf("scale", "metric_scale")
        private const val MOGE_PATCH = 14

        private fun closeRadius(model: SpatialDepthModel): Int =
            if (model.sharpDepthEdges) SHARP_EDGE_CLOSE_RADIUS else 0
    }
}
