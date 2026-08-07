package com.ywwynm.everythingdone.spatial

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 发丝级 alpha（前景不透明度）。[values] 行主序、范围 0..1，1 为前景。
 * 分辨率为推理分辨率（长边约等于模型 referenceSize、双边对齐 32），消费方按需上采样。
 */
data class SpatialAlphaData(
    val width: Int,
    val height: Int,
    val values: FloatArray
) {
    init {
        require(width > 0 && height > 0)
        require(values.size == width * height)
    }
}

class SpatialMattingEngine(
    private val context: Context
) {
    fun generate(
        bitmap: Bitmap,
        model: SpatialMattingModel,
        cancelled: AtomicBoolean
    ): SpatialAlphaData {
        check(!cancelled.get()) { "任务已取消" }
        // 按文件而非 ready 标记校验：下载 worker 的自检发生在写标记之前
        //（与补全模型同流程），标记由自检通过后补写。
        check(SpatialMattingModelStore.modelFile(context, model).isFile) {
            "matting 模型文件不存在"
        }
        check(SpatialRuntimeStore.isInstalled(context)) { "空间计算组件尚未安装" }
        SpatialRuntimeStore.ensureLoaded(context)

        // 官方协议：双边等比缩放到长边 referenceSize、各自对齐 32，不补边。
        val sourceLongEdge = max(bitmap.width, bitmap.height)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
            as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val inferenceLongEdge = SpatialMattingResolutionPolicy.selectLongEdge(
            sourceLongEdge = sourceLongEdge,
            baseLongEdge = model.referenceSize,
            totalRamMb = memory.totalMem / BYTES_PER_MIB,
            availableRamMb = memory.availMem / BYTES_PER_MIB
        )
        // 保持官方的等比缩放、双边对齐 32、不补边协议；高内存设备只提高采样密度。
        val scale = inferenceLongEdge.toFloat() / sourceLongEdge
        val targetWidth = align32((bitmap.width * scale).roundToInt())
        val targetHeight = align32((bitmap.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        val pixels = IntArray(targetWidth * targetHeight)
        scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        if (scaled !== bitmap) scaled.recycle()
        check(!cancelled.get()) { "任务已取消" }

        val planeSize = targetWidth * targetHeight
        val input = ByteBuffer.allocateDirect(3 * planeSize * Float.SIZE_BYTES)
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
                input.put((value - 0.5f) / 0.5f)
            }
        }
        input.rewind()

        val environment = SpatialOrtRuntime.environment(context)
        OrtSession.SessionOptions().use { options ->
            // 与深度/补全引擎同级：Runtime r4 起含 opset-11 行与优化器新造 kernel。
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
            options.setInterOpNumThreads(1)
            options.setIntraOpNumThreads(INFERENCE_THREADS)
            val modelFile = SpatialMattingModelStore.modelFile(context, model)
            environment.createSession(modelFile.absolutePath, options).use { session ->
                check(!cancelled.get()) { "任务已取消" }
                val inputName = session.inputNames.single()
                val shape = longArrayOf(1, 3, targetHeight.toLong(), targetWidth.toLong())
                OnnxTensor.createTensor(environment, input, shape).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { output ->
                        check(!cancelled.get()) { "任务已取消" }
                        val alphaTensor = output[0] as OnnxTensor
                        val outputShape = alphaTensor.info.shape
                        check(
                            outputShape.contentEquals(
                                longArrayOf(1, 1, targetHeight.toLong(), targetWidth.toLong())
                            )
                        ) { "matting 输出形状不符：${outputShape.contentToString()}" }
                        val values = FloatArray(planeSize)
                        alphaTensor.floatBuffer.get(values)
                        for (index in values.indices) {
                            val value = values[index]
                            check(value.isFinite()) { "matting 输出包含 NaN/Infinity" }
                            values[index] = value.coerceIn(0f, 1f)
                        }
                        return SpatialAlphaData(targetWidth, targetHeight, values)
                    }
                }
            }
        }
    }

    /** 下载后的硬自检：确定性小纹理，检查形状、有限性与动态范围。 */
    fun selfTest(model: SpatialMattingModel): Boolean {
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(96 * 64) { index ->
            val x = index % 96
            val y = index / 96
            android.graphics.Color.rgb((x * 3) and 0xff, (y * 5) and 0xff, ((x + y) * 2) and 0xff)
        }
        bitmap.setPixels(pixels, 0, 96, 0, 0, 96, 64)
        return try {
            val result = generate(bitmap, model, AtomicBoolean(false))
            result.values.all { it in 0f..1f }
        } finally {
            bitmap.recycle()
        }
    }

    private fun align32(value: Int): Int = max(32, (value / 32) * 32)

    companion object {
        private const val INFERENCE_THREADS = 4
        private const val BYTES_PER_MIB = 1024L * 1024L
    }
}
