package com.ywwynm.everythingdone.spatial

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Debug
import android.util.Log
import java.io.File
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

    private companion object {
        const val TAG = "SpatialInpaintBench"
    }
}
