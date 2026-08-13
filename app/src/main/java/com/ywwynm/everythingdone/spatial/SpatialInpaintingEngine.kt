package com.ywwynm.everythingdone.spatial

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

class SpatialInpaintingEngine(
    private val context: Context,
    /**
     * 执行提供者。默认 CPU，与 MI-GAN/AOT-GAN 上线时一致。
     * Debug 探针可传 XNNPACK 做对照——Big-LaMa 的内核时间 58.6% 花在
     * Conv/FusedConv/ConvTranspose 上（桌面 ORT profiling 实测），
     * 而 XNNPACK 正是针对 ARM 卷积优化的。改默认值前必须有真机数据。
     */
    private val provider: Provider = Provider.CPU
) {

    enum class Provider { CPU, XNNPACK }

    fun inpaint(
        bitmap: Bitmap,
        hiddenMask: BooleanArray,
        model: SpatialInpaintingModel,
        cancelled: AtomicBoolean,
        quality: SpatialInpaintingQuality = SpatialPreferences.inpaintingQuality(context),
        conditioningMask: BooleanArray = hiddenMask
    ): Bitmap {
        check(!cancelled.get()) { "任务已取消" }
        check(hiddenMask.size == bitmap.width * bitmap.height) { "补全 mask 尺寸不符" }
        check(conditioningMask.size == hiddenMask.size) { "补全条件 mask 尺寸不符" }
        check(hiddenMask.indices.all { !hiddenMask[it] || conditioningMask[it] }) {
            "补全条件 mask 必须覆盖实际写入 mask"
        }
        check(SpatialInpaintingModelStore.isInstalled(context, model)) {
            "空间背景补全模型尚未安装"
        }
        check(SpatialRuntimeStore.isInstalled(context)) { "空间计算组件尚未安装" }
        check(SpatialInpaintingModelStore.isDeviceEligible(context, model)) {
            "设备总内存不足"
        }
        val effectiveQuality = quality.takeIf {
            model.inputContract ==
                SpatialInpaintingInputContract.FLOAT32_AOTGAN_RGB_MASK
        }
        check(
            SpatialInpaintingModelStore.hasSufficientAvailableMemory(
                context,
                model,
                effectiveQuality
            )
        ) {
            "当前可用内存不足，请关闭其他大型应用或降低补图分辨率后重试"
        }
        SpatialRuntimeStore.ensureLoaded(context)
        return runModel(
            bitmap,
            hiddenMask,
            conditioningMask,
            model,
            cancelled,
            quality
        )
    }

    fun selfTest(model: SpatialInpaintingModel): Boolean {
        val width = 64
        val height = 48
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            Color.rgb(x * 4, y * 5, (x * 3 + y * 7) and 0xff)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val mask = BooleanArray(width * height) { index ->
            val x = index % width
            val y = index / width
            x in 24..39 && y in 16..31
        }
        return try {
            val result = runModel(
                bitmap = bitmap,
                hiddenMask = mask,
                conditioningMask = mask,
                model = model,
                cancelled = AtomicBoolean(false),
                quality = SpatialInpaintingQuality.STANDARD
            )
            val valid = result.width == width && result.height == height
            result.recycle()
            valid
        } finally {
            bitmap.recycle()
        }
    }

    private fun runModel(
        bitmap: Bitmap,
        hiddenMask: BooleanArray,
        conditioningMask: BooleanArray,
        model: SpatialInpaintingModel,
        cancelled: AtomicBoolean,
        quality: SpatialInpaintingQuality
    ): Bitmap = when (model.inputContract) {
        SpatialInpaintingInputContract.UINT8_PIPELINE ->
            runUint8Pipeline(bitmap, hiddenMask, conditioningMask, model, cancelled)
        SpatialInpaintingInputContract.FLOAT32_AOTGAN_RGB_MASK ->
            runAotGan(bitmap, hiddenMask, conditioningMask, model, quality, cancelled)
        SpatialInpaintingInputContract.FLOAT32_LAMA_RGB_MASK_512 ->
            runLamaTiled(bitmap, hiddenMask, conditioningMask, model, cancelled)
    }

    /**
     * Big-LaMa：按 512 原生像素分块推理，块内不缩放。几何与窗函数见
     * [SpatialInpaintingTiling]，与桌面 `inpaint_onnx_tiled` 逐条对齐。
     *
     * **不复用 AOT-GAN 那条路**：那条是"裁 bbox → 缩到工作分辨率 → 单次推理 → 放大回去"，
     * 而 Big-LaMa 的空间维写死 512，且缩图会吃掉 4–17px 的显露带（D160/D188）。
     *
     * 无掩膜的块直接跳过（实测约七成块可跳），代价可控。
     */
    private fun runLamaTiled(
        bitmap: Bitmap,
        hiddenMask: BooleanArray,
        conditioningMask: BooleanArray,
        model: SpatialInpaintingModel,
        cancelled: AtomicBoolean
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val plan = SpatialInpaintingTiling.plan(width, height)
        val tile = plan.tile
        val tilePixels = tile * tile
        val window = SpatialInpaintingTiling.window(tile, SpatialInpaintingTiling.OVERLAP)
        val accumulated = FloatArray(pixelCount * 3)
        val weights = FloatArray(pixelCount)

        val environment = SpatialOrtRuntime.environment(context)
        val imageBuffer = ByteBuffer.allocateDirect(tilePixels * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val maskBuffer = ByteBuffer.allocateDirect(tilePixels * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        // 取样下标与"是否落在真实像素区"每块都要重算，缓冲区提到循环外复用，
        // 避免每块新分配 1.25 MB
        val sourceIndex = IntArray(tilePixels)
        val inside = BooleanArray(tilePixels)
        var executed = 0

        createSession(model).use { session ->
            check(session.inputNames.contains(IMAGE_INPUT)) { "Big-LaMa 缺少 image 输入" }
            check(session.inputNames.contains(MASK_INPUT)) { "Big-LaMa 缺少 mask 输入" }
            for (originY in plan.originsY) {
                for (originX in plan.originsX) {
                    check(!cancelled.get()) { "任务已取消" }
                    if (!tileHasHole(conditioningMask, width, height, originX, originY, tile)) {
                        continue
                    }
                    executed++
                    fillTileBuffers(
                        pixels = pixels,
                        conditioningMask = conditioningMask,
                        width = width,
                        height = height,
                        originX = originX,
                        originY = originY,
                        tile = tile,
                        imageBuffer = imageBuffer,
                        maskBuffer = maskBuffer,
                        sourceIndex = sourceIndex,
                        inside = inside
                    )
                    OnnxTensor.createTensor(
                        environment,
                        imageBuffer,
                        longArrayOf(1, 3, tile.toLong(), tile.toLong())
                    ).use { imageTensor ->
                        OnnxTensor.createTensor(
                            environment,
                            maskBuffer,
                            longArrayOf(1, 1, tile.toLong(), tile.toLong())
                        ).use { maskTensor ->
                            session.run(
                                mapOf(IMAGE_INPUT to imageTensor, MASK_INPUT to maskTensor)
                            ).use { output ->
                                check(!cancelled.get()) { "任务已取消" }
                                val tensor = output[0] as OnnxTensor
                                check(
                                    tensor.info.shape.contentEquals(
                                        longArrayOf(1, 3, tile.toLong(), tile.toLong())
                                    )
                                ) { "Big-LaMa 输出形状不符：${tensor.info.shape.contentToString()}" }
                                accumulateTile(
                                    values = tensor.floatBuffer,
                                    window = window,
                                    width = width,
                                    height = height,
                                    originX = originX,
                                    originY = originY,
                                    tile = tile,
                                    accumulated = accumulated,
                                    weights = weights
                                )
                            }
                        }
                    }
                }
            }
        }
        check(executed > 0) { "Big-LaMa 分块推理没有覆盖任何洞" }

        val outputPixels = IntArray(pixelCount)
        for (index in 0 until pixelCount) {
            // 权重为 0 的像素退回原图：最外圈窗值恰为 0 且只被一个块覆盖，与桌面同处理。
            if (!hiddenMask[index] || weights[index] <= 1e-6f) {
                outputPixels[index] = pixels[index]
                continue
            }
            val scale = 1f / weights[index]
            outputPixels[index] = Color.rgb(
                lamaChannel(accumulated[index] * scale),
                lamaChannel(accumulated[pixelCount + index] * scale),
                lamaChannel(accumulated[pixelCount * 2 + index] * scale)
            )
        }
        return Bitmap.createBitmap(outputPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun tileHasHole(
        conditioningMask: BooleanArray,
        width: Int,
        height: Int,
        originX: Int,
        originY: Int,
        tile: Int
    ): Boolean {
        // 洞只可能落在真实像素区；反射填充区是真实像素的镜像，掩膜不随之镜像
        // （桌面 `np.pad(hole, ...)` 用的是零填充），所以这里只扫真实区。
        val endY = minOf(originY + tile, height)
        val endX = minOf(originX + tile, width)
        for (y in originY until endY) {
            val row = y * width
            for (x in originX until endX) {
                if (conditioningMask[row + x]) return true
            }
        }
        return false
    }

    private fun fillTileBuffers(
        pixels: IntArray,
        conditioningMask: BooleanArray,
        width: Int,
        height: Int,
        originX: Int,
        originY: Int,
        tile: Int,
        imageBuffer: FloatBuffer,
        maskBuffer: FloatBuffer,
        sourceIndex: IntArray,
        inside: BooleanArray
    ) {
        imageBuffer.rewind()
        maskBuffer.rewind()
        val tilePixels = tile * tile
        for (y in 0 until tile) {
            val globalY = originY + y
            val sampleY = if (globalY < height) {
                globalY
            } else {
                SpatialInpaintingTiling.reflectIndex(globalY, height)
            }
            val row = y * tile
            for (x in 0 until tile) {
                val globalX = originX + x
                val sampleX = if (globalX < width) {
                    globalX
                } else {
                    SpatialInpaintingTiling.reflectIndex(globalX, width)
                }
                sourceIndex[row + x] = sampleY * width + sampleX
                inside[row + x] = globalX < width && globalY < height
            }
        }
        for (channel in 0..2) {
            val shift = 16 - channel * 8
            for (index in 0 until tilePixels) {
                val pixel = pixels[sourceIndex[index]]
                imageBuffer.put((((pixel ushr shift) and 0xff) / 255f))
            }
        }
        for (index in 0 until tilePixels) {
            // 填充区一律记为已知：桌面对 hole 用的是零填充，不做镜像。
            maskBuffer.put(
                if (inside[index] && conditioningMask[sourceIndex[index]]) 1f else 0f
            )
        }
        imageBuffer.rewind()
        maskBuffer.rewind()
    }

    private fun accumulateTile(
        values: FloatBuffer,
        window: FloatArray,
        width: Int,
        height: Int,
        originX: Int,
        originY: Int,
        tile: Int,
        accumulated: FloatArray,
        weights: FloatArray
    ) {
        val tilePixels = tile * tile
        val pixelCount = width * height
        val endY = minOf(originY + tile, height)
        val endX = minOf(originX + tile, width)
        for (y in originY until endY) {
            val localRow = (y - originY) * tile - originX
            val targetRow = y * width
            for (x in originX until endX) {
                val local = localRow + x
                val weight = window[local]
                if (weight <= 0f) continue
                val target = targetRow + x
                weights[target] += weight
                accumulated[target] += values.get(local) * weight
                accumulated[pixelCount + target] += values.get(tilePixels + local) * weight
                accumulated[pixelCount * 2 + target] +=
                    values.get(tilePixels * 2 + local) * weight
            }
        }
    }

    private fun lamaChannel(value: Float): Int =
        value.roundToInt().coerceIn(0, 255)

    private fun runUint8Pipeline(
        bitmap: Bitmap,
        hiddenMask: BooleanArray,
        conditioningMask: BooleanArray,
        model: SpatialInpaintingModel,
        cancelled: AtomicBoolean
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val imageBuffer = ByteBuffer.allocateDirect(pixelCount * 3)
            .order(ByteOrder.nativeOrder())
        for (channel in 0..2) {
            for (index in pixels.indices) {
                val shift = 16 - channel * 8
                imageBuffer.put(((pixels[index] ushr shift) and 0xff).toByte())
            }
        }
        imageBuffer.rewind()
        val maskBuffer = ByteBuffer.allocateDirect(pixelCount)
            .order(ByteOrder.nativeOrder())
        for (hidden in conditioningMask) {
            maskBuffer.put(if (hidden) 0.toByte() else 0xff.toByte())
        }
        maskBuffer.rewind()

        val environment = SpatialOrtRuntime.environment(context)
        createSession(model).use { session ->
            check(session.inputNames.contains(IMAGE_INPUT)) {
                "补全模型缺少 image 输入"
            }
            check(session.inputNames.contains(MASK_INPUT)) {
                "补全模型缺少 mask 输入"
            }
            OnnxTensor.createTensor(
                environment,
                imageBuffer,
                longArrayOf(1, 3, height.toLong(), width.toLong()),
                OnnxJavaType.UINT8
            ).use { imageTensor ->
                OnnxTensor.createTensor(
                    environment,
                    maskBuffer,
                    longArrayOf(1, 1, height.toLong(), width.toLong()),
                    OnnxJavaType.UINT8
                ).use { maskTensor ->
                    check(!cancelled.get()) { "任务已取消" }
                    session.run(
                        mapOf(
                            IMAGE_INPUT to imageTensor,
                            MASK_INPUT to maskTensor
                        )
                    ).use { output ->
                        check(!cancelled.get()) { "任务已取消" }
                        val tensor = output[0] as OnnxTensor
                        val shape = tensor.info.shape
                        check(
                            shape.contentEquals(
                                longArrayOf(1, 3, height.toLong(), width.toLong())
                            )
                        ) { "补全模型输出形状不符：${shape.contentToString()}" }
                        val bytes = tensor.byteBuffer
                        val outputPixels = pixels.copyOf()
                        for (index in outputPixels.indices) {
                            if (!hiddenMask[index]) continue
                            val red = bytes.get(index).toInt() and 0xff
                            val green = bytes.get(pixelCount + index).toInt() and 0xff
                            val blue = bytes.get(pixelCount * 2 + index).toInt() and 0xff
                            outputPixels[index] = Color.rgb(red, green, blue)
                        }
                        return Bitmap.createBitmap(
                            outputPixels,
                            width,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                    }
                }
            }
        }
    }

    private fun runAotGan(
        bitmap: Bitmap,
        hiddenMask: BooleanArray,
        conditioningMask: BooleanArray,
        model: SpatialInpaintingModel,
        quality: SpatialInpaintingQuality,
        cancelled: AtomicBoolean
    ): Bitmap {
        val region = maskRegion(
            hiddenMask = conditioningMask,
            width = bitmap.width,
            height = bitmap.height
        ) ?: return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val targetWidth = alignedDimension(
            region.width(),
            region.height(),
            quality.targetLongEdge
        )
        val targetHeight = alignedDimension(
            region.height(),
            region.width(),
            quality.targetLongEdge
        )
        val crop = Bitmap.createBitmap(
            bitmap,
            region.left,
            region.top,
            region.width(),
            region.height()
        )
        val scaled = if (crop.width == targetWidth && crop.height == targetHeight) {
            crop
        } else {
            Bitmap.createScaledBitmap(crop, targetWidth, targetHeight, true)
        }
        try {
            check(!cancelled.get()) { "任务已取消" }
            val scaledMask = resampleMask(
                hiddenMask = conditioningMask,
                sourceWidth = bitmap.width,
                region = region,
                targetWidth = targetWidth,
                targetHeight = targetHeight
            )
            val generated = runAotGanSession(
                bitmap = scaled,
                hiddenMask = scaledMask,
                model = model,
                cancelled = cancelled
            )
            try {
                val restored = if (
                    generated.width == region.width() &&
                    generated.height == region.height()
                ) {
                    generated
                } else {
                    Bitmap.createScaledBitmap(
                        generated,
                        region.width(),
                        region.height(),
                        true
                    )
                }
                try {
                    return compositeHiddenRegion(
                        original = bitmap,
                        generatedRegion = restored,
                        hiddenMask = hiddenMask,
                        region = region
                    )
                } finally {
                    if (restored !== generated) restored.recycle()
                }
            } finally {
                generated.recycle()
            }
        } finally {
            if (scaled !== crop) scaled.recycle()
            crop.recycle()
        }
    }

    private fun runAotGanSession(
        bitmap: Bitmap,
        hiddenMask: BooleanArray,
        model: SpatialInpaintingModel,
        cancelled: AtomicBoolean
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val imageBuffer = ByteBuffer.allocateDirect(pixelCount * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (channel in 0..2) {
            for (index in pixels.indices) {
                val value = if (hiddenMask[index]) {
                    1f
                } else {
                    val shift = 16 - channel * 8
                    (((pixels[index] ushr shift) and 0xff) / 127.5f) - 1f
                }
                imageBuffer.put(value)
            }
        }
        imageBuffer.rewind()
        val maskBuffer = ByteBuffer.allocateDirect(pixelCount * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (hidden in hiddenMask) maskBuffer.put(if (hidden) 1f else 0f)
        maskBuffer.rewind()

        val environment = SpatialOrtRuntime.environment(context)
        createSession(model).use { session ->
            check(session.inputNames.contains(IMAGE_INPUT)) {
                "AOT-GAN 缺少 image 输入"
            }
            check(session.inputNames.contains(MASK_INPUT)) {
                "AOT-GAN 缺少 mask 输入"
            }
            OnnxTensor.createTensor(
                environment,
                imageBuffer,
                longArrayOf(1, 3, height.toLong(), width.toLong())
            ).use { imageTensor ->
                OnnxTensor.createTensor(
                    environment,
                    maskBuffer,
                    longArrayOf(1, 1, height.toLong(), width.toLong())
                ).use { maskTensor ->
                    check(!cancelled.get()) { "任务已取消" }
                    session.run(
                        mapOf(
                            IMAGE_INPUT to imageTensor,
                            MASK_INPUT to maskTensor
                        )
                    ).use { output ->
                        check(!cancelled.get()) { "任务已取消" }
                        val tensor = output[0] as OnnxTensor
                        check(
                            tensor.info.shape.contentEquals(
                                longArrayOf(1, 3, height.toLong(), width.toLong())
                            )
                        ) { "AOT-GAN 输出形状不符" }
                        val values = tensor.floatBuffer
                        val outputPixels = IntArray(pixelCount)
                        for (index in outputPixels.indices) {
                            val red = aotChannel(values.get(index))
                            val green = aotChannel(values.get(pixelCount + index))
                            val blue = aotChannel(values.get(pixelCount * 2 + index))
                            outputPixels[index] = Color.rgb(red, green, blue)
                        }
                        return Bitmap.createBitmap(
                            outputPixels,
                            width,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                    }
                }
            }
        }
    }

    private fun createSession(model: SpatialInpaintingModel): OrtSession {
        val environment = SpatialOrtRuntime.environment(context)
        return OrtSession.SessionOptions().use { options ->
            // 同 SpatialDepthEngine：Runtime r3 起编入了图优化器新造算子的 kernel
            // （含 AOT-GAN/MI-GAN 在 EXTENDED 级融合出的 com.microsoft.FusedConv），
            // App 侧由 REQUIRED_PACKAGE_VERSION 保证只加载 r3+，恢复 EXTENDED。
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
            options.setInterOpNumThreads(1)
            when (provider) {
                Provider.CPU -> options.setIntraOpNumThreads(INFERENCE_THREADS)
                Provider.XNNPACK -> {
                    // XNNPACK 自带线程池；ORT 自身再开同等线程会争用（官方 runtime 也会警告）
                    options.setIntraOpNumThreads(1)
                    options.addConfigEntry("session.intra_op.allow_spinning", "0")
                    options.addXnnpack(mapOf("intra_op_num_threads" to "$INFERENCE_THREADS"))
                }
            }
            environment.createSession(
                SpatialInpaintingModelStore.modelFile(context, model).absolutePath,
                options
            )
        }
    }

    private fun compositeHiddenRegion(
        original: Bitmap,
        generatedRegion: Bitmap,
        hiddenMask: BooleanArray,
        region: Rect
    ): Bitmap {
        val fullPixels = IntArray(original.width * original.height)
        original.getPixels(
            fullPixels,
            0,
            original.width,
            0,
            0,
            original.width,
            original.height
        )
        val generatedPixels = IntArray(region.width() * region.height())
        generatedRegion.getPixels(
            generatedPixels,
            0,
            region.width(),
            0,
            0,
            region.width(),
            region.height()
        )
        for (y in 0 until region.height()) {
            val sourceRow = (region.top + y) * original.width
            val generatedRow = y * region.width()
            for (x in 0 until region.width()) {
                val sourceIndex = sourceRow + region.left + x
                if (hiddenMask[sourceIndex]) {
                    fullPixels[sourceIndex] = generatedPixels[generatedRow + x]
                }
            }
        }
        return Bitmap.createBitmap(
            fullPixels,
            original.width,
            original.height,
            Bitmap.Config.ARGB_8888
        )
    }

    private fun maskRegion(
        hiddenMask: BooleanArray,
        width: Int,
        height: Int
    ): Rect? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (index in hiddenMask.indices) {
            if (!hiddenMask[index]) continue
            val x = index % width
            val y = index / width
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
        if (right < left || bottom < top) return null
        val maskWidth = right - left + 1
        val maskHeight = bottom - top + 1
        val padding = max(MIN_CONTEXT_PIXELS, max(maskWidth, maskHeight) / 2)
        return Rect(
            (left - padding).coerceAtLeast(0),
            (top - padding).coerceAtLeast(0),
            (right + padding + 1).coerceAtMost(width),
            (bottom + padding + 1).coerceAtMost(height)
        )
    }

    private fun alignedDimension(
        dimension: Int,
        otherDimension: Int,
        targetLongEdge: Int
    ): Int {
        val scaled = dimension.toFloat() * targetLongEdge / max(dimension, otherDimension)
        return ((scaled.roundToInt().coerceAtLeast(MIN_MODEL_DIMENSION)) / 4 * 4)
            .coerceAtMost(targetLongEdge)
    }

    private fun resampleMask(
        hiddenMask: BooleanArray,
        sourceWidth: Int,
        region: Rect,
        targetWidth: Int,
        targetHeight: Int
    ): BooleanArray = SpatialInpaintingMask.conservativeResize(
        source = hiddenMask,
        sourceWidth = sourceWidth,
        sourceHeight = hiddenMask.size / sourceWidth,
        regionLeft = region.left,
        regionTop = region.top,
        regionWidth = region.width(),
        regionHeight = region.height(),
        targetWidth = targetWidth,
        targetHeight = targetHeight
    )

    private fun aotChannel(value: Float): Int =
        (((value.coerceIn(-1f, 1f) + 1f) * 127.5f).roundToInt())
            .coerceIn(0, 255)

    companion object {
        private const val IMAGE_INPUT = "image"
        private const val MASK_INPUT = "mask"
        private const val INFERENCE_THREADS = 4
        private const val MIN_CONTEXT_PIXELS = 32
        private const val MIN_MODEL_DIMENSION = 64
    }
}
