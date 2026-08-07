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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

class SpatialInpaintingEngine(
    private val context: Context
) {

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
    }

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
            options.setIntraOpNumThreads(INFERENCE_THREADS)
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
