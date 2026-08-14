package com.ywwynm.everythingdone.spatial

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Debug
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug 专用：补全模型的真机探针。用于在模型尚未进入可信 catalog 时验证
 * **算子覆盖、耗时与峰值内存**，据此校准 [SpatialInpaintingModel] 里那两个 RAM 门槛
 * （移植 Big-LaMa 时它们是按体积比例外推的估计值）。
 *
 * 安装走 [SpatialInpaintingModelStore.installVerified]，**照常校验 SHA-256 与字节数**，
 * 不伪造 ready 标记——旁路的是"必须先上架 catalog"这一步，不是校验本身。
 *
 * 先把模型推到
 * `/sdcard/Android/data/com.ywwynm.everythingdone/files/spatial_models/<fileName>`，再：
 *
 * `adb shell am broadcast -n
 * com.ywwynm.everythingdone/.spatial.SpatialInpaintingBenchmarkReceiver
 * --es model big_lama_places2_512 --es action install`
 *
 * 然后 `--es action bench --ei width 540 --ei height 720 --ei band 24`
 * 跑一次成品尺寸的补全，日志里给出分块数、耗时与 native 堆增量。
 */
class SpatialInpaintingBenchmarkReceiver : BroadcastReceiver() {

    private var probeLog: File? = null

    /** 同时写 logcat 与落盘文件；落盘那份才是读结果的依据。 */
    private fun logi(message: String) {
        Log.i(TAG, message)
        runCatching { probeLog?.appendText(message + "\n") }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        // 这台设备的 logcat 被系统日志刷爆（shsusrd 每秒数千行），5 MiB 缓冲上限装不下
        // 一次推理的时长；结果同时落盘到 files/probe.log，读结果一律以文件为准。
        probeLog = File(appContext.getExternalFilesDir(null), "probe.log")
        val modelId = intent.getStringExtra("model") ?: "big_lama_places2_512"
        val action = intent.getStringExtra("action") ?: "bench"
        val width = intent.getIntExtra("width", 540).coerceIn(64, 4096)
        val height = intent.getIntExtra("height", 720).coerceIn(64, 4096)
        val band = intent.getIntExtra("band", 24).coerceIn(1, 512)
        val provider = when (intent.getStringExtra("provider")) {
            "xnnpack" -> SpatialInpaintingEngine.Provider.XNNPACK
            else -> SpatialInpaintingEngine.Provider.CPU
        }

        Thread({
            try {
                if (action == "runtime") {
                    // 走产品自己的下载协调器：catalog 拉取、签名校验、SHA-256、原子安装
                    // 全部按线上路径来，只是绕过"必须在设置页点一下"这个 UI 触发。
                    SpatialRuntimeDownloadCoordinator.enqueue(appContext, allowMetered = true)
                    logi("spatial-runtime-enqueue 已入队；目标 " +
                        SpatialRuntimeStore.REQUIRED_PACKAGE_VERSION)
                    return@Thread
                }
                if (action == "installdepth" || action == "depthtest") {
                    when (action) {
                        "installdepth" -> installDepth(appContext, modelId)
                        else -> depthTest(appContext, modelId, width, height,
                            intent.getStringExtra("image"))
                    }
                    return@Thread
                }
                val model = SpatialInpaintingModel.fromStableId(modelId)
                    ?: error("未知补全模型：$modelId")
                when (action) {
                    "install" -> install(appContext, model)
                    "selftest" -> selfTest(appContext, model)
                    "bench" -> bench(appContext, model, width, height, band, provider)
                    // NPU 三件套的下发入口。设置页点一下也能做到，但那条路要驱动 UI，
                    // 而这里要反复重装重测，走协调器更稳。校验一律照旧。
                    "qnnruntime" -> {
                        SpatialQnnRuntimeDownloadCoordinator.enqueue(appContext, allowMetered = true)
                        logi("spatial-qnn-runtime-enqueue 已入队；目标 " +
                            SpatialRuntimeStore.QNN_PACKAGE_VERSION)
                    }
                    "qnnprecompiled" -> {
                        SpatialQnnPrecompiledDownloadCoordinator.enqueue(
                            appContext, model.stableId, model.version, allowMetered = true
                        )
                        logi("spatial-qnn-precompiled-enqueue model=${model.stableId} 已入队")
                    }
                    "qnnstate" -> qnnState(appContext, model)
                    "qnnprofile" -> qnnProfile(
                        appContext,
                        model,
                        intent.getIntExtra("iterations", 3).coerceIn(1, 50),
                        intent.getStringExtra("level") ?: "detailed"
                    )
                    else -> error("未知 action：$action")
                }
            } catch (error: Throwable) {
                Log.e(TAG, "探针失败 model=$modelId action=$action", error)
                logi("spatial-probe-failed model=$modelId action=$action ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                pending.finish()
            }
        }, "SpatialInpaintBench").apply {
            isDaemon = true
            start()
        }
    }

    private fun install(context: Context, model: SpatialInpaintingModel) {
        val source = File(
            File(context.getExternalFilesDir(null), "spatial_models"),
            model.fileName
        )
        check(source.isFile) { "模型不存在：${source.absolutePath}" }
        val start = System.nanoTime()
        // 逐字节校验 SHA-256 后原子安装；校验不通过会抛，不会留下半成品
        SpatialInpaintingModelStore.installVerified(context, model, source, markReady = true)
        logi(
            String.format(
                Locale.US,
                "spatial-inpaint-install model=%s installed=%b bytes=%d verifyMs=%.0f",
                model.stableId,
                SpatialInpaintingModelStore.isInstalled(context, model),
                model.sizeBytes,
                (System.nanoTime() - start) / 1e6
            )
        )
    }

    private fun selfTest(context: Context, model: SpatialInpaintingModel) {
        val engine = SpatialInpaintingEngine(context)
        val start = System.nanoTime()
        val ok = engine.selfTest(model)
        logi(
            String.format(
                Locale.US,
                "spatial-inpaint-selftest model=%s ok=%b ms=%.0f",
                model.stableId,
                ok,
                (System.nanoTime() - start) / 1e6
            )
        )
    }

    /**
     * 造一张成品尺寸的图与一条沿对角线的窄带（宽度 [band]，贴近真实显露带的量级），
     * 走正式的 [SpatialInpaintingEngine.inpaint] 全链路。
     */
    private fun bench(
        context: Context,
        model: SpatialInpaintingModel,
        width: Int,
        height: Int,
        band: Int,
        provider: SpatialInpaintingEngine.Provider
    ) {
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            Color.rgb((x * 7) and 0xff, (y * 5) and 0xff, ((x + y) * 3) and 0xff)
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val hidden = BooleanArray(width * height) { index ->
            val x = index % width
            val y = index / width
            // 一条沿对角线的窄带 + 一条竖带，覆盖多个分块，逼近真实带的分布
            (kotlin.math.abs(x - y) < band) || (kotlin.math.abs(x - width * 3 / 4) < band / 2)
        }
        val conditioning = BooleanArray(width * height) { index ->
            hidden[index] || (index % width - index / width) in 0..(band * 2)
        }
        val plan = SpatialInpaintingTiling.plan(width, height)

        Runtime.getRuntime().gc()
        val nativeBefore = Debug.getNativeHeapAllocatedSize()
        val javaBefore = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val start = System.nanoTime()
        val result = SpatialInpaintingEngine(context, provider).inpaint(
            bitmap = bitmap,
            hiddenMask = hidden,
            model = model,
            cancelled = AtomicBoolean(false),
            conditioningMask = conditioning
        )
        val elapsedMs = (System.nanoTime() - start) / 1e6
        val nativeAfter = Debug.getNativeHeapAllocatedSize()
        val javaAfter = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val changed = run {
            val out = IntArray(width * height)
            result.getPixels(out, 0, width, 0, 0, width, height)
            out.indices.count { out[it] != pixels[it] }
        }
        logi(
            String.format(
                Locale.US,
                "spatial-inpaint-bench model=%s provider=%s size=%dx%d band=%d tiles=%d(%dx%d) " +
                    "holePx=%d changedPx=%d ms=%.0f nativeDeltaMiB=%.1f javaDeltaMiB=%.1f",
                model.stableId, provider, width, height, band,
                plan.tileCount, plan.originsX.size, plan.originsY.size,
                conditioning.count { it }, changed, elapsedMs,
                (nativeAfter - nativeBefore) / 1048576.0,
                (javaAfter - javaBefore) / 1048576.0
            )
        )
        result.recycle()
        bitmap.recycle()
    }

    /** 深度模型也走 installVerified，照常校验 SHA-256。 */
    private fun installDepth(context: Context, modelId: String) {
        val model = SpatialDepthModel.fromStableId(modelId) ?: error("未知深度模型：$modelId")
        val source = File(File(context.getExternalFilesDir(null), "spatial_models"), model.fileName)
        check(source.isFile) { "模型不存在：${source.absolutePath}" }
        SpatialModelStore.installVerified(context, model, source)
        logi("spatial-depth-install model=${model.stableId} " +
            "installed=${SpatialModelStore.isInstalled(context, model)} bytes=${model.sizeBytes}")
    }

    /**
     * 跑一次真实的深度推理并把**反解出来的内参**打出来——这是 MoGe 这一档存在的理由，
     * 只看"跑通了"证明不了内参是对的。
     */
    private fun depthTest(
        context: Context,
        modelId: String,
        width: Int,
        height: Int,
        imagePath: String?
    ) {
        val model = SpatialDepthModel.fromStableId(modelId) ?: error("未知深度模型：$modelId")
        // 合成渐变图会让 MoGe 给出近乎平面的深度，而**平面上的焦距反解是病态的**
        // （任意 focal 配相应 shift 都能拟合平面）。验内参必须用真实照片。
        val bitmap = if (imagePath != null) {
            checkNotNull(android.graphics.BitmapFactory.decodeFile(imagePath)) {
                "读不出测试图：$imagePath"
            }.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            val pixels = IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                Color.rgb((x * 7) and 0xff, (y * 5) and 0xff, ((x + y) * 3) and 0xff)
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }
        val start = System.nanoTime()
        val data = SpatialDepthEngine(context).generate(bitmap, model, AtomicBoolean(false))
        val srcW = bitmap.width
        val srcH = bitmap.height
        val ms = (System.nanoTime() - start) / 1e6
        val k = data.intrinsics
        val z = data.metricDepth
        logi(
            String.format(
                Locale.US,
                "spatial-depth-test model=%s in=%dx%d out=%dx%d ms=%.0f " +
                    "fx=%s cx=%s metricZ(min/中位/max)=%s",
                model.stableId, srcW, srcH, data.width, data.height, ms,
                k?.let { String.format(Locale.US, "%.1f", it.fx) } ?: "无",
                k?.let { String.format(Locale.US, "%.1f", it.cx) } ?: "无",
                z?.let {
                    val f = it.filter { v -> v.isFinite() && v > 0f }.sorted()
                    if (f.isEmpty()) "无" else String.format(
                        Locale.US, "%.3f/%.3f/%.3f m", f.first(), f[f.size / 2], f.last())
                } ?: "无"
            )
        )
        bitmap.recycle()
    }

    /** 报告 NPU 三件套（运行组件、预编译产物、逐模型开关）各自装没装。 */
    private fun qnnState(context: Context, model: SpatialInpaintingModel) {
        val arch = SpatialQnnSupport.resolveDspArch()
        val ctx = arch?.let {
            runCatching {
                SpatialQnnPrecompiledStore.contextModel(context, model.stableId, model.version, it)
            }.getOrNull()
        }
        logi(
            "spatial-qnn-state dspArch=${arch ?: "不支持"} " +
                "cpuRuntime=${SpatialRuntimeStore.isVariantInstalled(context, qnn = false)} " +
                "qnnRuntime=${SpatialRuntimeStore.isVariantInstalled(context, qnn = true)} " +
                "pkgVersion=${SpatialRuntimeStore.installedPackageVersion(context) ?: "无"} " +
                "qnnEnabled=${SpatialPreferences.qnnEnabled(context)} " +
                "qnnEnabledFor=${SpatialPreferences.qnnEnabledFor(context, model.stableId)} " +
                "precompiled=${ctx?.absolutePath ?: "无"} " +
                "precompiledBytes=${ctx?.length() ?: 0}"
        )
    }

    /**
     * 逐算子 profiling 的 A/B：同一块 512² 输入，先 CPU 后 QNN，各跑 [iterations] 次。
     *
     * **必须落 CSV**：单看总耗时只知道"慢"，知道不了慢在哪一类算子上。QNN EP 的
     * `profiling_level=detailed` 会把每个算子在 HTP 上的耗时写进 CSV，这是判断
     * "算力打不满"还是"被 Reshape/Concat 之类的搬运拖死"的唯一依据。
     */
    private fun qnnProfile(
        context: Context,
        model: SpatialInpaintingModel,
        iterations: Int,
        profilingLevel: String
    ) {
        val environment = SpatialOrtRuntime.environment(context)
        val tile = 512
        val tilePixels = tile * tile
        val image = ByteBuffer.allocateDirect(tilePixels * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val mask = ByteBuffer.allocateDirect(tilePixels * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until tilePixels * 3) image.put(i, ((i % 255) / 255f))
        // 中心一块方形空洞，与真实显露带同量级
        for (y in 0 until tile) {
            for (x in 0 until tile) {
                val hole = x in 200..312 && y in 200..312
                mask.put(y * tile + x, if (hole) 1f else 0f)
            }
        }

        fun runOne(label: String, modelPath: String, configure: (OrtSession.SessionOptions) -> Unit) {
            val sessionStart = System.nanoTime()
            OrtSession.SessionOptions().use { options ->
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
                options.setInterOpNumThreads(1)
                configure(options)
                environment.createSession(modelPath, options).use { session ->
                    val sessionMs = (System.nanoTime() - sessionStart) / 1e6
                    val imageName = session.inputNames.first { it.contains("image") }
                    val maskName = session.inputNames.first { it.contains("mask") }
                    val times = DoubleArray(iterations)
                    for (i in 0 until iterations) {
                        image.rewind()
                        mask.rewind()
                        val imageTensor = OnnxTensor.createTensor(
                            environment, image, longArrayOf(1, 3, tile.toLong(), tile.toLong())
                        )
                        val maskTensor = OnnxTensor.createTensor(
                            environment, mask, longArrayOf(1, 1, tile.toLong(), tile.toLong())
                        )
                        val t0 = System.nanoTime()
                        imageTensor.use { im ->
                            maskTensor.use { mk ->
                                session.run(mapOf(imageName to im, maskName to mk)).use { }
                            }
                        }
                        times[i] = (System.nanoTime() - t0) / 1e6
                    }
                    val sorted = times.sorted()
                    logi(
                        String.format(
                            Locale.US,
                            "spatial-qnn-profile label=%s sessionMs=%.0f n=%d " +
                                "first=%.0f min=%.0f 中位=%.0f max=%.0f",
                            label, sessionMs, iterations,
                            times.first(), sorted.first(), sorted[sorted.size / 2], sorted.last()
                        )
                    )
                }
            }
        }

        val cpuModel = SpatialInpaintingModelStore.modelFile(context, model)
        runOne("cpu", cpuModel.absolutePath) { options ->
            options.setIntraOpNumThreads(4)
        }

        val arch = SpatialQnnSupport.resolveDspArch()
        if (arch == null) {
            logi("spatial-qnn-profile label=qnn 跳过：本机不是受支持的骁龙 NPU")
            return
        }
        // 没有预编译产物就直接拿原图让 QNN EP 现编——MI-GAN/AOT-GAN 这种纯卷积小图
        // 端上编得动，正好用来回答"这颗 HTP 本身行不行"。
        val precompiled = runCatching {
            SpatialQnnPrecompiledStore.contextModel(context, model.stableId, model.version, arch)
        }.getOrNull()
        val ctxModel = precompiled ?: cpuModel
        logi("spatial-qnn-profile 源=${if (precompiled != null) "预编译" else "端上现编"}")
        val directory = SpatialRuntimeStore.nativeLibraryDirectory(context)
        val backend = File(directory, "libQnnHtp.so")
        if (!backend.isFile) {
            logi("spatial-qnn-profile label=qnn 跳过：NPU 运行组件未安装")
            return
        }
        val csv = File(context.getExternalFilesDir(null), "qnn-profile-${model.stableId}.csv")
        runCatching { csv.delete() }
        runOne("qnn", ctxModel.absolutePath) { options ->
            options.setIntraOpNumThreads(1)
            options.addQnn(
                mapOf(
                    "backend_path" to backend.absolutePath,
                    "enable_htp_fp16_precision" to "1",
                    "htp_performance_mode" to "burst",
                    "profiling_level" to profilingLevel,
                    "profiling_file_path" to csv.absolutePath
                )
            )
        }
        logi("spatial-qnn-profile csv=${csv.absolutePath} bytes=${csv.length()}")
    }

    private companion object {
        const val TAG = "SpatialInpaintBench"
    }
}
