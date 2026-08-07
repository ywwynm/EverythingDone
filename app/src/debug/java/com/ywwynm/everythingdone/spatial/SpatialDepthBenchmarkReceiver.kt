package com.ywwynm.everythingdone.spatial

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Debug 专用的 ONNX Runtime 真机探针。
 *
 * 模型不进入 APK。先把模型推送到：
 * `/sdcard/Android/data/com.ywwynm.everythingdone/files/spatial_models/`，再执行：
 *
 * `adb shell am broadcast -n
 * com.ywwynm.everythingdone/.spatial.SpatialDepthBenchmarkReceiver
 * --es model zipdepth --es provider cpu --ei iterations 6`
 *
 * 可选 `--es libsource <目录名>`：从外部 files 下该目录取
 * libonnxruntime.so + libonnxruntime4j_jni.so，复制进内部目录（外部存储 noexec，
 * 不能直接 dlopen）后按 `onnxruntime.native.path` 协议加载，用于在不改动
 * runtime store 的前提下基准候选 Runtime（如 r3-PoC 构建）。**换 Runtime 前必须
 * force-stop**：ORT 原生库一个进程只加载一次，进程里已加载 store 版本时该参数
 * 静默无效（可用「r2 上必失败的模型是否跑通」交叉验证实际加载的是谁）。
 */
class SpatialDepthBenchmarkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        val modelId = intent.getStringExtra(EXTRA_MODEL) ?: MODEL_ZIPDEPTH
        val provider = intent.getStringExtra(EXTRA_PROVIDER) ?: PROVIDER_CPU
        val iterations = intent.getIntExtra(EXTRA_ITERATIONS, 6).coerceIn(1, 30)
        val threads = intent.getIntExtra(
            EXTRA_THREADS,
            Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        ).coerceIn(1, 16)
        // 默认与正式引擎同级（r3 起为 EXTENDED）；对照实验用 --es opt no_opt|basic 覆盖。
        val optLevel = intent.getStringExtra(EXTRA_OPT_LEVEL) ?: OPT_EXTENDED
        val libSource = intent.getStringExtra(EXTRA_LIB_SOURCE)

        Thread({
            try {
                runBenchmark(appContext, modelId, provider, iterations, threads, optLevel, libSource)
            } catch (error: Throwable) {
                Log.e(TAG, "空间深度模型基准失败 model=$modelId provider=$provider", error)
            } finally {
                pending.finish()
            }
        }, "SpatialDepthBench").apply {
            isDaemon = true
            start()
        }
    }

    private fun runBenchmark(
        context: Context,
        modelId: String,
        provider: String,
        iterations: Int,
        threads: Int,
        optLevel: String,
        libSource: String?
    ) {
        val spec = ModelSpec.forId(modelId)
        val modelDir = File(context.getExternalFilesDir(null), MODEL_DIRECTORY)
        val modelFile = File(modelDir, spec.fileName)
        check(modelFile.isFile) { "模型不存在：${modelFile.absolutePath}" }

        val environment = if (libSource == null) {
            SpatialOrtRuntime.environment(context)
        } else {
            alternateEnvironment(context, libSource)
        }
        val nativeBefore = Debug.getNativeHeapAllocatedSize()
        val sessionStart = System.nanoTime()
        OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(
                when (optLevel) {
                    OPT_NO_OPT -> OrtSession.SessionOptions.OptLevel.NO_OPT
                    OPT_BASIC -> OrtSession.SessionOptions.OptLevel.BASIC_OPT
                    OPT_EXTENDED -> OrtSession.SessionOptions.OptLevel.EXTENDED_OPT
                    else -> error("未知优化级别：$optLevel")
                }
            )
            options.setInterOpNumThreads(1)
            when (provider) {
                PROVIDER_CPU -> options.setIntraOpNumThreads(threads)
                PROVIDER_XNNPACK -> {
                    // XNNPACK 自带线程池；ORT 自身再开同等线程会形成争用，官方 runtime 也会警告。
                    options.setIntraOpNumThreads(1)
                    options.addConfigEntry("session.intra_op.allow_spinning", "0")
                    options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                }
                else -> error("未知 provider：$provider")
            }

            environment.createSession(modelFile.absolutePath, options).use { session ->
                val sessionMs = elapsedMs(sessionStart)
                val inputName = session.inputNames.single()
                val inputBuffer = createInput(spec)
                OnnxTensor.createTensor(environment, inputBuffer, spec.inputShape).use { input ->
                    val inputs = mapOf(inputName to input)
                    // 首次执行会包含内核初始化与内存规划，不混入稳态统计。
                    session.run(inputs).use { result ->
                        validateOutput(result[0] as OnnxTensor, spec)
                    }

                    val samplesMs = DoubleArray(iterations)
                    var outputSummary = OutputSummary.EMPTY
                    repeat(iterations) { index ->
                        val start = System.nanoTime()
                        session.run(inputs).use { result ->
                            samplesMs[index] = elapsedMs(start)
                            if (index == iterations - 1) {
                                outputSummary = validateOutput(result[0] as OnnxTensor, spec)
                            }
                        }
                    }

                    val nativeAfter = Debug.getNativeHeapAllocatedSize()
                    val sorted = samplesMs.sorted()
                    val report = String.format(
                        Locale.US,
                        "spatial-depth-bench model=%s provider=%s opt=%s runtime=%s device=%s sdk=%d " +
                            "threads=%d modelBytes=%d input=%s output=%s session=%.2fms " +
                            "p50=%.2fms p95=%.2fms min=%.6f max=%.6f finite=%s " +
                            "nativeDeltaMiB=%.2f",
                        spec.id,
                        provider,
                        optLevel,
                        libSource ?: "store",
                        Build.MODEL,
                        Build.VERSION.SDK_INT,
                        threads,
                        modelFile.length(),
                        spec.inputShape.contentToString(),
                        outputSummary.shape.contentToString(),
                        sessionMs,
                        percentile(sorted, 50.0),
                        percentile(sorted, 95.0),
                        outputSummary.min,
                        outputSummary.max,
                        outputSummary.finite,
                        (nativeAfter - nativeBefore) / (1024.0 * 1024.0)
                    )
                    Log.i(TAG, report)
                    val logDir = File(context.getExternalFilesDir(null), "debug_logs")
                    check(logDir.exists() || logDir.mkdirs()) {
                        "无法创建日志目录：${logDir.absolutePath}"
                    }
                    File(logDir, LOG_FILE).appendText(report + "\n")
                }
            }
        }
    }

    /**
     * 从外部 files/<sourceName> 复制双库到内部目录后，按 store 同款流程初始化 ORT：
     * 先 System.load 双库（JNI 库的 DT_NEEDED 含 libonnxruntime.so，核心库必须先进
     * linker namespace），再走 `onnxruntime.native.path` 属性分支。仅进程首次初始化
     * ORT 时有效。
     */
    @android.annotation.SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun alternateEnvironment(context: Context, sourceName: String): ai.onnxruntime.OrtEnvironment {
        val source = File(context.getExternalFilesDir(null), sourceName)
        val target = File(context.filesDir, "spatial_bench_runtime/$sourceName")
        target.deleteRecursively()
        check(target.mkdirs()) { "无法创建基准 Runtime 目录：${target.absolutePath}" }
        for (name in arrayOf("libonnxruntime.so", "libonnxruntime4j_jni.so")) {
            val from = File(source, name)
            check(from.isFile) { "备用 Runtime 缺少 $name：${from.absolutePath}" }
            from.inputStream().use { input ->
                File(target, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        System.load(File(target, "libonnxruntime.so").absolutePath)
        System.load(File(target, "libonnxruntime4j_jni.so").absolutePath)
        val previous = System.getProperty(ORT_NATIVE_PATH_PROPERTY)
        try {
            System.setProperty(ORT_NATIVE_PATH_PROPERTY, target.absolutePath)
            return ai.onnxruntime.OrtEnvironment.getEnvironment()
        } finally {
            if (previous == null) {
                System.clearProperty(ORT_NATIVE_PATH_PROPERTY)
            } else {
                System.setProperty(ORT_NATIVE_PATH_PROPERTY, previous)
            }
        }
    }

    private fun createInput(spec: ModelSpec): java.nio.FloatBuffer {
        val elementCount = spec.inputShape.fold(1L) { acc, value -> acc * value }.toInt()
        val buffer = ByteBuffer.allocateDirect(elementCount * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val planeSize = spec.width * spec.height
        repeat(3) { channel ->
            repeat(planeSize) { pixel ->
                val raw = ((pixel * 37 + channel * 61) % 251) / 250f
                val value = if (spec.normalizeImageNet) {
                    (raw - IMAGENET_MEAN[channel]) / IMAGENET_STD[channel]
                } else {
                    raw
                }
                buffer.put(value)
            }
        }
        check(buffer.position() == elementCount)
        buffer.rewind()
        return buffer
    }

    private fun validateOutput(tensor: OnnxTensor, spec: ModelSpec): OutputSummary {
        val infoShape = tensor.info.shape
        check(infoShape.contentEquals(spec.outputShape)) {
            "输出形状错误：${infoShape.contentToString()}，预期 ${spec.outputShape.contentToString()}"
        }
        val values = tensor.floatBuffer
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        var finite = true
        while (values.hasRemaining()) {
            val value = values.get()
            if (!value.isFinite()) finite = false
            if (value < min) min = value
            if (value > max) max = value
        }
        check(finite) { "模型输出包含 NaN/Infinity" }
        check(min < max) { "模型输出没有有效动态范围：min=$min max=$max" }
        return OutputSummary(infoShape, min, max, finite)
    }

    private fun percentile(sorted: List<Double>, percentile: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val position = (percentile / 100.0) * (sorted.size - 1)
        val low = position.toInt()
        val high = (low + 1).coerceAtMost(sorted.lastIndex)
        val fraction = position - low
        return sorted[low] + (sorted[high] - sorted[low]) * fraction
    }

    private fun elapsedMs(startNs: Long): Double = (System.nanoTime() - startNs) / 1_000_000.0

    private data class OutputSummary(
        val shape: LongArray,
        val min: Float,
        val max: Float,
        val finite: Boolean
    ) {
        companion object {
            val EMPTY = OutputSummary(longArrayOf(), Float.NaN, Float.NaN, false)
        }
    }

    private data class ModelSpec(
        val id: String,
        val fileName: String,
        val width: Int,
        val height: Int,
        val normalizeImageNet: Boolean,
        val outputChannels: Boolean
    ) {
        val inputShape = longArrayOf(1, 3, height.toLong(), width.toLong())
        val outputShape = if (outputChannels) {
            longArrayOf(1, 1, height.toLong(), width.toLong())
        } else {
            longArrayOf(1, height.toLong(), width.toLong())
        }

        companion object {
            fun forId(id: String): ModelSpec = when (id) {
                MODEL_ZIPDEPTH -> ModelSpec(
                    id = MODEL_ZIPDEPTH,
                    fileName = "zipdepth_base_npu_384.onnx",
                    width = 384,
                    height = 384,
                    normalizeImageNet = false,
                    outputChannels = true
                )
                MODEL_DEPTH_ANYTHING_V2_SMALL -> ModelSpec(
                    id = MODEL_DEPTH_ANYTHING_V2_SMALL,
                    fileName = "depth_anything_v2_vits_518.onnx",
                    width = 518,
                    height = 518,
                    normalizeImageNet = true,
                    outputChannels = false
                )
                MODEL_DA3_SMALL -> ModelSpec(
                    id = MODEL_DA3_SMALL,
                    fileName = "da3_small_mono_518.onnx",
                    width = 518,
                    height = 518,
                    normalizeImageNet = true,
                    outputChannels = false
                )
                else -> error("未知模型：$id")
            }
        }
    }

    companion object {
        private const val TAG = "SpatialDepthBench"
        private const val LOG_FILE = "spatial_depth_bench.log"
        private const val MODEL_DIRECTORY = "spatial_models"

        private const val EXTRA_MODEL = "model"
        private const val EXTRA_PROVIDER = "provider"
        private const val EXTRA_ITERATIONS = "iterations"
        private const val EXTRA_THREADS = "threads"
        private const val EXTRA_OPT_LEVEL = "opt"
        private const val EXTRA_LIB_SOURCE = "libsource"
        private const val ORT_NATIVE_PATH_PROPERTY = "onnxruntime.native.path"

        private const val MODEL_ZIPDEPTH = "zipdepth"
        private const val MODEL_DEPTH_ANYTHING_V2_SMALL = "depth_anything_v2_small"
        private const val MODEL_DA3_SMALL = "da3small"
        private const val PROVIDER_CPU = "cpu"
        private const val PROVIDER_XNNPACK = "xnnpack"
        private const val OPT_NO_OPT = "no_opt"
        private const val OPT_BASIC = "basic"
        private const val OPT_EXTENDED = "extended"

        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
