package com.ywwynm.everythingdone.spatial

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale

/**
 * Debug 专用 QNN EP 探针（Phase 0）。
 *
 * 刻意**不走** [SpatialRuntimeStore] / [SpatialOrtRuntime]：那两个加载的是产品用的裁剪版
 * `libonnxruntime.so`（不含 QNN EP），而同一进程里 `libonnxruntime.so` 只能加载一份。
 * 本探针自己从 `files/qnnpoc` 复制到私有目录再 `System.load`，因此**必须在没有生成过
 * 空间照片的干净进程里跑**——先 `am force-stop`。
 *
 * 库来自：
 * - `com.microsoft.onnxruntime:onnxruntime-android-qnn:1.28.0`（`libonnxruntime.so` 21.6 MB
 *   与 `libonnxruntime4j_jni.so`；其 `classes.jar` 与 `onnxruntime-android:1.28.0` 的
 *   SHA-256 完全相同，所以 app 里那份补丁 jar 不用换）
 * - `com.qualcomm.qti:qnn-runtime:2.48.0` 的 v73 组（8 Gen 2）
 *
 * 用法：
 * ```
 * adb shell am broadcast -n com.ywwynm.everythingdone/.spatial.SpatialQnnProbeReceiver \
 *     --es model biglama --es provider qnn --ei runs 5
 * ```
 */
class SpatialQnnProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: "bench"
        val modelKey = intent.getStringExtra("model") ?: "biglama"
        val provider = intent.getStringExtra("provider") ?: "qnn"
        val runs = intent.getIntExtra("runs", 5).coerceIn(1, 50)
        val fp16 = intent.getStringExtra("fp16") ?: "1"
        val perf = intent.getStringExtra("perf") ?: "burst"
        val contextCache = intent.getStringExtra("ctx") ?: "0"
        val forceAdsp = intent.getStringExtra("adsp") == "1"
        val preset = intent.getStringExtra("preset") ?: "full"
        val backend = intent.getStringExtra("backend") ?: "htp"
        val verbose = intent.getStringExtra("verbose") == "1"
        val profiling = intent.getStringExtra("profile") != "0"
        val modelPathOverride = intent.getStringExtra("path")
        val libsSubdir = intent.getStringExtra("libs") ?: "qnnpoc"
        val numTokens = intent.getIntExtra("tokens", 1800)
        val pending = goAsync()
        Thread({
            val appContext = context.applicationContext
            val log = File(appContext.getExternalFilesDir(null), "qnn-probe.log")
            fun report(line: String) {
                Log.i(TAG, line)
                runCatching { log.appendText(line + "\n") }
            }
            try {
                if (action == "install-override" || action == "clear-override") {
                    manageOverride(appContext, action, ::report)
                    return@Thread
                }
                report(
                    "=== run model=$modelKey provider=$provider fp16=$fp16 perf=$perf " +
                        "ctx=$contextCache runs=$runs soc=${socModel()} ==="
                )
                probe(
                    appContext, modelKey, provider, runs, fp16, perf, contextCache,
                    forceAdsp, preset, backend, verbose, profiling, modelPathOverride,
                    libsSubdir, numTokens, ::report
                )
            } catch (error: Throwable) {
                report("FAILED ${error.javaClass.name}: ${error.message}")
                error.stackTrace.take(12).forEach { report("    at $it") }
                var cause = error.cause
                var depth = 0
                while (cause != null && depth < 3) {
                    report("  caused by ${cause.javaClass.name}: ${cause.message}")
                    cause = cause.cause
                    depth++
                }
            } finally {
                pending.finish()
            }
        }, "SpatialQnnProbe").start()
    }

    /**
     * 把 adb push 上来的 QNN 版运行组件装进 [SpatialRuntimeStore] 的 debug 覆盖目录，
     * 让**产品路径**（而不是本探针）也能跑在 QNN 版 `libonnxruntime.so` 上。
     * 装完必须重启进程：同一进程只能加载一份 `libonnxruntime.so`。
     */
    private fun manageOverride(context: Context, action: String, report: (String) -> Unit) {
        val target = File(context.noBackupFilesDir, "spatial-photo/runtime-override")
        if (action == "clear-override") {
            val removed = !target.exists() || target.deleteRecursively()
            report("override cleared=$removed dir=${target.absolutePath}")
            return
        }
        val source = File(context.getExternalFilesDir(null), "qnnpoc")
        check(source.isDirectory) { "缺少 ${source.absolutePath}" }
        if (target.exists()) check(target.deleteRecursively()) { "无法清理旧覆盖目录" }
        check(target.mkdirs()) { "无法创建覆盖目录" }
        var count = 0
        source.listFiles { file -> file.name.endsWith(".so") }?.forEach { file ->
            file.inputStream().use { input ->
                File(target, file.name).outputStream().use { output -> input.copyTo(output) }
            }
            count++
        }
        report("override installed files=$count dir=${target.absolutePath}")
        report("runtime packageVersion=${SpatialRuntimeStore.installedPackageVersion(context)}")
        report("qnn available=${SpatialQnnSessionFactory.isAvailable(context)}")
        report("dspArch=${SpatialQnnSupport.resolveDspArch(context)} soc=${socModel()}")
    }

    private fun probe(
        context: Context,
        modelKey: String,
        provider: String,
        runs: Int,
        fp16: String,
        perf: String,
        contextCache: String,
        forceAdspPath: Boolean,
        preset: String,
        backend: String,
        verbose: Boolean,
        profiling: Boolean,
        modelPathOverride: String?,
        libsSubdir: String,
        numTokens: Int,
        report: (String) -> Unit
    ) {
        val libraryDirectory = prepareLibraries(context, forceAdspPath, libsSubdir, report)
        val spec = modelSpec(context, modelKey).let { base ->
            if (modelPathOverride.isNullOrBlank()) base
            else ModelSpec(File(modelPathOverride), base.fallbackShapes)
        }
        check(spec.file.isFile) { "模型不存在：${spec.file.absolutePath}" }
        report("model=${spec.file.name} bytes=${spec.file.length()}")

        val environment = loadRuntime(libraryDirectory, verbose, report)

        val cacheDirectory = File(context.noBackupFilesDir, "qnn-poc/ctx").apply { mkdirs() }
        // context binary 的复用是两步：第一次用 ep.context_enable=1 生成 <name>_ctx.onnx，
        // 之后**直接把那个文件当模型加载**（此时不能再设 ep.context_enable）。
        val contextModel = File(cacheDirectory, "${spec.file.nameWithoutExtension}_ctx.onnx")
        val reuseContext = contextCache == "1" && contextModel.isFile
        val modelPath = if (reuseContext) contextModel.absolutePath else spec.file.absolutePath
        if (contextCache == "1") {
            report(
                "context model=${contextModel.absolutePath} exists=${contextModel.isFile} " +
                    "bytes=${if (contextModel.isFile) contextModel.length() else 0} reuse=$reuseContext"
            )
        }
        val options = OrtSession.SessionOptions()
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
        options.setInterOpNumThreads(1)
        when (provider) {
            "cpu" -> options.setIntraOpNumThreads(4)
            "xnnpack" -> {
                options.setIntraOpNumThreads(1)
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                options.addXnnpack(mapOf("intra_op_num_threads" to "4"))
            }
            "qnn" -> {
                options.setIntraOpNumThreads(1)
                if (contextCache == "1" && !reuseContext) {
                    options.addConfigEntry("ep.context_enable", "1")
                    options.addConfigEntry("ep.context_embed_mode", "0")
                    options.addConfigEntry(
                        "ep.context_file_path",
                        contextModel.absolutePath
                    )
                }
                val qnnOptions = LinkedHashMap<String, String>()
                val backendLibrary = when (backend) {
                    "htp" -> "libQnnHtp.so"
                    "gpu" -> "libQnnGpu.so"
                    "cpu" -> "libQnnCpu.so"
                    else -> error("未知 QNN backend：$backend")
                }
                qnnOptions["backend_path"] =
                    File(libraryDirectory, backendLibrary).absolutePath
                // 逐项加，便于用 preset 做减法定位是哪一项让 QnnDevice_create 返回
                // QNN_DEVICE_ERROR_INVALID_CONFIG。
                if (preset != "minimal") {
                    qnnOptions["enable_htp_fp16_precision"] = fp16
                }
                if (preset == "full" || preset == "soc") {
                    qnnOptions["htp_performance_mode"] = perf
                    qnnOptions["htp_graph_finalization_optimization_mode"] = "0"
                }
                if (preset == "soc") {
                    // SM8550 / 8 Gen 2：soc_model 43、htp_arch v73。
                    qnnOptions["soc_model"] = "43"
                    qnnOptions["htp_arch"] = "73"
                    qnnOptions["device_id"] = "0"
                }
                report("qnn_options=$qnnOptions")
                options.addQnn(qnnOptions)
            }
            else -> error("未知 provider：$provider")
        }
        // profiling 对大模型代价很高：Big-LaMa 开着 profiling 跑到一半被 LMK 杀掉
        // （RSS 已到 842 MB）。只在需要看 EP 划分时打开。
        if (profiling) {
            val profile =
                File(context.getExternalFilesDir(null), "qnn-profile-$modelKey-$provider")
            options.enableProfiling(profile.absolutePath)
        }

        val sessionStartedAt = System.nanoTime()
        val session = options.use { environment.createSession(modelPath, it) }
        val sessionMillis = (System.nanoTime() - sessionStartedAt) / 1_000_000
        report("session created in ${sessionMillis}ms")
        report("inputs=${session.inputNames} outputs=${session.outputNames}")

        session.use {
            val tensors = buildInputs(environment, session, spec, numTokens, report)
            try {
                val durations = LongArray(runs)
                for (index in 0 until runs) {
                    val at = System.nanoTime()
                    session.run(tensors).close()
                    durations[index] = (System.nanoTime() - at) / 1_000_000
                }
                report("runs_ms=${durations.joinToString(",")}")
                val sorted = durations.sortedArray()
                report(
                    String.format(
                        Locale.US,
                        "first=%dms median=%dms min=%dms",
                        durations[0], sorted[sorted.size / 2], sorted[0]
                    )
                )
            } finally {
                tensors.values.forEach { it.close() }
            }
            if (profiling) {
                report("profile=${session.endProfiling()}")
            } else {
                report("profile=disabled")
            }
        }
    }

    /**
     * Android 不允许从共享存储 `dlopen`，必须先落到应用私有目录。
     *
     * **不要自己设 `ADSP_LIBRARY_PATH`。** ORT 的 QNN EP 会根据 `backend_path` 自行设定；
     * 手动设会被它检测到并跳过自己的设定，日志里是
     * `Using existing ADSP_LIBRARY_PATH setting of …, which may cause the HTP backend to fail`，
     * 随后 `Failed to create device. Error: QNN_DEVICE_ERROR_INVALID_CONFIG`，整图静默退回
     * CPU（2026-08-13 实测，4540 个节点无一落到 QNN）。`adsp=1` 只用于复现这条故障。
     */
    private fun prepareLibraries(
        context: Context,
        forceAdspPath: Boolean,
        libsSubdir: String,
        report: (String) -> Unit
    ): File {
        check(libsSubdir.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "非法库目录名：$libsSubdir"
        }
        val source = File(context.getExternalFilesDir(null), libsSubdir)
        check(source.isDirectory) { "缺少 ${source.absolutePath}，先 adb push 库" }
        val target = File(context.noBackupFilesDir, "qnn-poc/lib-$libsSubdir")
        check(target.exists() || target.mkdirs()) { "无法创建 ${target.absolutePath}" }
        var copied = 0
        source.listFiles { file -> file.name.endsWith(".so") }?.forEach { file ->
            val destination = File(target, file.name)
            if (!destination.isFile || destination.length() != file.length()) {
                file.inputStream().use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                copied++
            }
        }
        report("libs dir=${target.absolutePath} copied=$copied total=${target.listFiles()?.size}")
        if (forceAdspPath) {
            Os.setenv("ADSP_LIBRARY_PATH", "${target.absolutePath};/vendor/lib/rfsa/adsp;/dsp", true)
        }
        report("ADSP_LIBRARY_PATH(before session)=${Os.getenv("ADSP_LIBRARY_PATH")}")
        return target
    }

    private fun loadRuntime(
        libraryDirectory: File,
        verbose: Boolean,
        report: (String) -> Unit
    ): OrtEnvironment {
        System.setProperty("onnxruntime.native.path", libraryDirectory.absolutePath)
        System.load(File(libraryDirectory, "libonnxruntime.so").absolutePath)
        System.load(File(libraryDirectory, "libonnxruntime4j_jni.so").absolutePath)
        // QNN EP 会把 ORT 的日志级别透传给 QNN 库的 QnnLog，VERBOSE 才看得到
        // QnnDevice_create 失败的具体原因。
        val environment = if (verbose) {
            OrtEnvironment.getEnvironment(
                ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE, "ort-java"
            )
        } else {
            OrtEnvironment.getEnvironment()
        }
        report("providers=${OrtEnvironment.getAvailableProviders()}")
        return environment
    }

    private class ModelSpec(val file: File, val fallbackShapes: Map<String, LongArray>)

    private fun modelSpec(context: Context, key: String): ModelSpec {
        val root = File(context.noBackupFilesDir, "spatial-photo")
        return when (key) {
            "biglama" -> ModelSpec(
                File(
                    root,
                    "inpainting-models/big_lama_places2_512/1.0.0/big_lama_places2_512_fp32.onnx"
                ),
                mapOf(
                    "image" to longArrayOf(1, 3, 512, 512),
                    "mask" to longArrayOf(1, 1, 512, 512)
                )
            )
            // MoGe 动态版的 image 是 [batch,3,height,width] 全动态，必须给回退形状；
            // 取项目实际在用的 720 长边档（alignToPatchPreservingAspect(540,720,720)）。
            "moge" -> ModelSpec(
                File(root, "models/moge_2_vits_normal/1.0.0/moge-2-vits-normal.onnx"),
                mapOf("image" to longArrayOf(1, 3, 714, 532))
            )
            "rfdetr" -> ModelSpec(
                File(root, "segmentation-models/rf_detr_seg_nano/1.0.0/rfdetr_seg_nano_312.onnx"),
                emptyMap()
            )
            "modnet" -> ModelSpec(
                File(root, "matting-models/modnet_photographic/1.0.0/modnet_photographic.onnx"),
                emptyMap()
            )
            "edgetam" -> ModelSpec(
                File(
                    root,
                    "boundary-refinement-models/edgetam_boundary_refiner/1.0.0/" +
                        "edgetam_image_encoder_1024.onnx"
                ),
                emptyMap()
            )
            else -> error("未知模型：$key")
        }
    }

    /**
     * 按 session 声明的输入形状生成张量，不硬编码输入名。声明里的动态维（-1）用
     * [ModelSpec.fallbackShapes] 补，补不上就报错——**这本身就是「该模型对 QNN 需要先钉
     * 形状」的证据**，正是 Phase 0 要盘的东西。
     */
    private fun buildInputs(
        environment: OrtEnvironment,
        session: OrtSession,
        spec: ModelSpec,
        numTokens: Int,
        report: (String) -> Unit
    ): Map<String, OnnxTensor> {
        val tensors = LinkedHashMap<String, OnnxTensor>()
        for ((name, info) in session.inputInfo) {
            val tensorInfo = info.info as ai.onnxruntime.TensorInfo
            // MoGe 动态版的 num_tokens 是 int64 标量，决定模型内部的推理分辨率，
            // 不能按 float 造。它是本项目唯一的画质旋钮（decisions 7099）。
            if (tensorInfo.type == ai.onnxruntime.OnnxJavaType.INT64 &&
                tensorInfo.shape.isEmpty()
            ) {
                report("  input $name = num_tokens scalar $numTokens")
                tensors[name] = OnnxTensor.createTensor(
                    environment, java.lang.Long.valueOf(numTokens.toLong())
                )
                continue
            }
            val declared = tensorInfo.shape
            val fallback = spec.fallbackShapes[name]
            val shape = LongArray(declared.size) { index ->
                val value = declared[index]
                when {
                    value > 0 -> value
                    fallback != null && index < fallback.size -> fallback[index]
                    else -> error("输入 $name 第 $index 维是动态的且无回退值")
                }
            }
            report("  input $name declared=${declared.joinToString()} used=${shape.joinToString()}")
            val count = shape.fold(1L) { acc, value -> acc * value }
            check(count in 1..(1L shl 28)) { "输入 $name 元素数异常：$count" }
            tensors[name] = OnnxTensor.createTensor(environment, floats(count.toInt()), shape)
        }
        return tensors
    }

    /** 确定性伪随机纹理：常数输入会让某些后端走上不具代表性的快路径。 */
    private fun floats(count: Int): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(count * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        var state = 0x2545F491
        for (index in 0 until count) {
            state = state * 1103515245 + 12345
            buffer.put(((state ushr 8) and 0xffff) / 65535f)
        }
        buffer.rewind()
        return buffer
    }

    private fun socModel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL}"
        } else {
            Build.HARDWARE
        }

    companion object {
        private const val TAG = "SpatialQnnProbe"
    }
}
