package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 本应用直接写自己的诊断数据；不使用 logcat、ADB、记事 DAO 或后台上传。 */
internal object ParticlePresentationReports {
    const val EXTRA_RUN = "presentation_run"
    val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() },
            "ParticlePresentationReports")
    }
    private val main = Handler(Looper.getMainLooper())
    private val runs = ConcurrentHashMap<String, Report>()

    class Report(val id: String, val scene: String, val label: String, val folder: File,
                 override val options: ParticlePresentationDiagnostics.Options,
                 private val metadata: JSONObject, frameCapacity: Int = 2048,
                 private val checkpoints: Boolean = true) : ParticlePresentationDiagnostics.Run {
        private val started = System.nanoTime()
        private val events = PresentationEventBuffer<JSONObject>(frameCapacity = frameCapacity)
        private val claimed = HashSet<String>()
        private var eventCount = 0
        private var observation = "尚未判断"
        private val savePending = AtomicBoolean(false)

        @Synchronized override fun event(name: String, values: Map<String, Any?>) {
            events.add(JSONObject().put("event", name)
                .put("elapsedMs", (System.nanoTime() - started) / 1e6)
                .put("values", JSONObject(values)), frame = name == "window_frame" || name == "particle_buffer_submitted" ||
                    name == "particle_progress_callback" || name == "particle_presentation_decision")
            if (++eventCount % 240 == 1 && checkpoints) save()
        }

        @Synchronized override fun claimImage(name: String): Boolean =
            options.images && claimed.size < 48 && claimed.add(name)

        override fun image(name: String, producer: () -> Bitmap?) {
            worker.execute {
                var bitmap: Bitmap? = null
                var preview: Bitmap? = null
                try {
                    bitmap = producer()
                    if (bitmap == null) { event("image_unavailable", mapOf("image" to name)); return@execute }
                    val scale = minOf(1f, 512f / maxOf(bitmap.width, bitmap.height))
                    preview = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                    val pixels = IntArray(preview.width * preview.height)
                    preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
                    val alphaPixels = pixels.count { (it ushr 24) != 0 }
                    val file = File(folder, "$name.png")
                    file.outputStream().use { check(preview.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    event("image_saved", mapOf("image" to name, "width" to bitmap.width, "height" to bitmap.height,
                        "sampleNontransparentFraction" to alphaPixels.toDouble() / pixels.size,
                        "meaning" to if (name.startsWith("gl-window-")) "gl_output_not_final_screen" else "source_buffer_not_final_screen"))
                } catch (failure: Exception) {
                    event("image_failed", mapOf("image" to name, "error" to failure.toString()))
                } finally {
                    if (preview !== bitmap) preview?.recycle()
                    bitmap?.recycle()
                    writeNow()
                }
            }
        }

        @Synchronized fun observe(value: String) { observation = value; save() }
        fun json(): JSONObject {
            val (observed, captured) = synchronized(this) { observation to events.capture() }
            return JSONObject().put("id", id).put("scene", scene).put("label", label)
            .put("options", JSONObject().put("rootForeground", options.rootForeground)
                .put("dialogMode", options.dialogMode.name).put("images", options.images)
                .put("glForeground", options.glForeground)
                .put("synchronizedGlHandoff", options.synchronizedGlHandoff)
                .put("surfaceRecovery", options.surfaceRecovery).put("opaqueGlHandoff", options.opaqueGlHandoff)
                .put("fastOpaqueHandoff", options.fastOpaqueHandoff).put("unified", options.unified)
                .put("fixture", options.fixture))
            .put("observation", observed).put("metadata", metadata)
            .put("events", JSONArray(captured.values())).put("droppedFrameEvents", captured.droppedFrames)
            .put("droppedLifecycleEvents", captured.droppedLifecycle)
        }

        fun save() {
            // 整页持续显示也有逐帧事件；合并检查点，避免每几百毫秒重写整份报告。
            // 导出仍直接取得最新完整快照，不受延后落盘影响。
            if (savePending.compareAndSet(false, true)) worker.schedule({
                savePending.set(false)
                writeNow()
            }, 2, TimeUnit.SECONDS)
        }
        private fun writeNow() {
            try {
                val pending = File(folder, "report.json.tmp")
                pending.writeText(json().toString())
                val target = File(folder, "report.json")
                check(pending.renameTo(target)) { "诊断文件替换失败" }
            } catch (failure: Exception) {
                // 导出时再次同步写入，并将不可读文件显示为失败；不让诊断 IO 中断动画。
                android.util.Log.e("ParticlePresentation", "保存诊断失败", failure)
            }
        }
    }

    fun start(context: Context, scene: String, label: String,
              options: ParticlePresentationDiagnostics.Options, environment: String,
              frameCapacity: Int = 2048, checkpoints: Boolean = true): Report {
        val id = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
        val folder = File(context.filesDir, "presentation-diagnostics/$id").apply { mkdirs() }
        val report = Report(id, scene, label, folder, options, JSONObject()
            .put("versionName", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
            .put("build", context.getString(R.string.debug_update_code)).put("visibleAndroidApi", Build.VERSION.SDK_INT)
            .put("visibleAndroidRelease", Build.VERSION.RELEASE).put("visibleManufacturer", Build.MANUFACTURER)
            .put("visibleModel", Build.MODEL).put("visibleBuild", Build.DISPLAY)
            .put("testerEnvironmentNote", environment.take(1000))
            .put("buildInfoMeaning", "应用可见信息，不能证明宿主设备与卓易通版本")
            .put("particleStyle", ParticleStyle.current.name).put("fixedSeed", if (options.fixture) 42017 else JSONObject.NULL)
            .put("systemAnimationsEnabled", android.animation.ValueAnimator.areAnimatorsEnabled())
            .put("screenEvidence", "用户观察；缓冲抓图与提交回调不能证明最终合成正确"), frameCapacity, checkpoints)
        runs[id] = report
        runs.keys.sortedDescending().drop(12).forEach { runs.remove(it) }
        report.event("run_created", mapOf("scene" to scene, "label" to label))
        return report
    }

    fun bind(activity: Activity): Report? {
        val id = activity.intent.getStringExtra(EXTRA_RUN) ?: return null
        val report = runs[id] ?: return null
        ParticlePresentationDiagnostics.bind(activity, report)
        report.event("activity_bound", mapOf("activity" to activity.javaClass.simpleName))
        return report
    }

    fun find(id: String?): Report? = id?.let(runs::get)
    fun directories(context: Context): List<File> = File(context.filesDir, "presentation-diagnostics")
        .listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.take(12) ?: emptyList()

    fun summary(context: Context): String = buildString {
        append("完事儿显示兼容性诊断\n应用构建：${context.getString(R.string.debug_update_code)}\n")
        append("以下结果来自用户观察；回调成功不等于最终屏幕显示正确。\n\n")
        for (directory in directories(context)) {
            val json = runs[directory.name]?.json() ?: runCatching {
                JSONObject(File(directory, "report.json").readText())
            }.getOrNull() ?: continue
            append("${json.optString("id")} · ${json.optString("scene")} · ${json.optString("label")}\n")
            append("观察：${json.optString("observation")}\n")
            val metadata = json.optJSONObject("metadata")
            append("API：${metadata?.optInt("visibleAndroidApi")}；样式：${metadata?.optString("particleStyle")}\n")
            append("该次构建：${metadata?.optString("build")}\n")
            metadata?.optString("testerEnvironmentNote")?.takeIf { it.isNotBlank() }?.let { append("环境备注：$it\n") }
            val events = json.optJSONArray("events") ?: JSONArray()
            val backends = linkedSetOf<String>()
            val warnings = linkedSetOf<String>()
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                val name = event.optString("event")
                val values = event.optJSONObject("values")
                if (name == "particle_backend") backends += values?.optString("backend").orEmpty()
                if (name == "opaque_return_without_entry") warnings += "准备未完成，已保留真实内容"
                if (name.contains("failed") || name.contains("aborted") || name.contains("timeout") ||
                    values?.optBoolean("failed") == true || name == "surface_recovery_unavailable" ||
                    (name == "window_switch_requested" &&
                        (values?.optBoolean("groupAdded") != true || values.optBoolean("queuedOnDraw") != true)) ||
                    (name == "gl_visibility_claim" && values?.optBoolean("accepted") != true) ||
                    (name == "gl_visibility_released" && values?.optBoolean("clearCommitted") != true) ||
                    (name == "surface_recovery_ready" && values?.optBoolean("verifiedBuffers") != true)) warnings += name
            }
            append("实际后端：${backends.joinToString().ifEmpty { "尚未记录" }}")
            if (warnings.isNotEmpty()) append("；诊断：${warnings.joinToString()}")
            append("\n\n")
        }
    }

    fun export(context: Context, done: (Result<File>) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val result = runCatching {
                val directory = File(app.cacheDir, "presentation-exports").apply { mkdirs() }
                val output = File(directory, "particle-display-${System.currentTimeMillis()}.zip")
                ZipOutputStream(output.outputStream().buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry("summary.txt"))
                    zip.write(summary(app).toByteArray(Charsets.UTF_8)); zip.closeEntry()
                    for (runFolder in directories(app)) {
                        val report = runs[runFolder.name]
                        if (report != null) File(runFolder, "report.json").writeText(report.json().toString(2))
                        for (file in runFolder.listFiles().orEmpty().filter { it.extension in listOf("json", "png") }) {
                            zip.putNextEntry(ZipEntry("${runFolder.name}/${file.name}"))
                            file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                        }
                    }
                }
                output
            }
            main.post { done(result) }
        }
    }
}
