package com.ywwynm.everythingdone.hdr

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.Display
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.github.chrisbanes.photoview.PhotoView
import com.ywwynm.everythingdone.BuildConfig
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.function.Consumer

/** 仅 debug 可访问；诊断数据立即落盘，图片不会进入导出的日志。 */
class HdrDiagnosticsActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "hdr-diagnostics-io") }
    private val main = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("hdr-diagnostics", MODE_PRIVATE) }
    private lateinit var journal: HdrDiagnosticJournal
    private lateinit var preview: FrameLayout
    private lateinit var status: TextView
    private lateinit var sourceLabel: TextView
    private lateinit var metadataStatus: TextView
    private lateinit var environment: EditText
    private lateinit var modePicker: Spinner
    private var image: HdrProbeImage? = null
    private var photoView: PhotoView? = null
    private var mode = 0
    private var headroomIndex = 0
    private var imageGeneration = 0
    private var renderGeneration = 0
    private var loading = false
    private var resumed = false
    private var lastDisplayLog = 0L
    private var pendingExport: String? = null
    private var hdrRatioListenerRegistered = false
    private val hdrRatioListener = Consumer<Display> { recordState("HDR_SDR_RATIO_CHANGED") }
    private val headrooms = floatArrayOf(0f, 2f, 4f, 8f)
    private val modes = arrayOf("1 · SDR 基图（ImageView）", "2 · HDR（ImageView，运行时请求）",
        "3 · HDR（Glide + PhotoView）", "4 · HDR（创建窗口时请求）",
        "5 · HDR（直接创建 scRGB FP16 Surface）", "6 · HDR（直接创建 PQ 10 位 Surface）",
        "7 · SDR（独立 Surface，8 位）")

    private val selectImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            event("IMAGE_INPUT_PROVIDER", "scheme=${uri.scheme}; authority=${uri.authority}; " +
                "mime=${runCatching { contentResolver.getType(uri) }.getOrNull()}; privateCopy=byte_for_byte; " +
                "上游是否转换未知，不记录 URI 路径或显示文件名。")
            loadSource("系统选图") { contentResolver.openInputStream(uri) ?: error("无法打开所选图片") }
        }
    }
    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val name = pendingExport
        pendingExport = null
        if (uri != null && name != null) writeDocument(uri, File(cacheDir, "hdr-exports/$name"))
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (System.currentTimeMillis() - lastDisplayLog > 1000) {
                lastDisplayLog = System.currentTimeMillis()
                recordState("DISPLAY_CHANGED")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = (savedInstanceState?.getInt("mode") ?: intent.getIntExtra("probe_mode", 0)).coerceIn(modes.indices)
        headroomIndex = (savedInstanceState?.getInt("headroom") ?: intent.getIntExtra("headroom", 0)).coerceIn(headrooms.indices)
        pendingExport = savedInstanceState?.getString("pending_export")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val existing = preferences.getString("session", null)
        val id = if (HdrDiagnosticJournal.validId(existing)) existing!! else HdrDiagnosticJournal.newId()
        journal = HdrDiagnosticJournal(File(filesDir, "hdr-diagnostics/$id"))
        preferences.edit().putString("session", id).apply()
        event("ACTIVITY_CREATE", "session=$id; mode=${modes[mode]}; requestedBeforeContent=${mode == 3}; " +
            "restored=${savedInstanceState != null}\n本日志不包含图片、记事内容或账号。包含设备型号、系统版本、图片摘要和手动备注。\n" +
            "HDR 能力、窗口请求、EGL 成功提交均不能单独证明屏幕高光提亮。")
        // 此对照必须在首次创建 ViewRoot / 窗口 Surface 前请求 HDR。
        runCatching { window.colorMode = if (mode == 3) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT }
            .onFailure { event("EARLY_WINDOW_REQUEST_FAILED", it.stackTraceToString()) }
        buildUi()
        recordState("ENVIRONMENT")
        if (Build.VERSION.SDK_INT < 34) {
            status.text = "运行环境上报 Android ${Build.VERSION.SDK_INT}，低于 Ultra HDR 所需 API 34。仍可导出环境日志。"
            event("ULTRA_HDR_API_UNAVAILABLE", status.text.toString())
            return
        }
        val path = intent.getStringExtra("image_path")
        if (path != null) {
            intent.removeExtra("image_path")
            loadSource("查看器原图") { File(path).inputStream() }
        } else {
            val storedName = preferences.getString("source", null)
            val stored = storedName?.takeIf { it.matches(Regex("source-[0-9]+\\.jpg")) }?.let { File(journal.directory, it) }
            if (stored?.isFile == true) decodeSource(stored, preferences.getString("source_label", "上次图片")!!)
            else loadBuiltin()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(18, 18, 18)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(12) + bars.left, dp(4) + bars.top, dp(12) + bars.right, dp(4) + bars.bottom)
            insets
        }
        fun label(text: String, size: Float = 13f) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.WHITE)
        }
        root.addView(label("HDR 对照测试", 19f))
        row(root, "内置测试图" to { loadBuiltin() }, "选择原图" to {
            if (loading) toast("正在读取图片，请稍候")
            else if (Build.VERSION.SDK_INT >= 34) runCatching { selectImage.launch(arrayOf("image/*")) }
                .onFailure { showFailure("系统选图不可用", it) }
        })
        sourceLabel = label("读取图片中…")
        root.addView(sourceLabel)
        modePicker = spinner(modes, mode) { selection ->
            if (selection != mode) {
                val recreateWindow = mode == 3 || selection == 3
                mode = selection
                event("MODE_SELECTED", modes[mode])
                if (recreateWindow) {
                    intent.putExtra("probe_mode", mode).putExtra("headroom", headroomIndex)
                    recreate()
                } else render()
            }
        }
        root.addView(modePicker)
        root.addView(spinner(arrayOf("亮度余量：系统自动（Surface 重建按 4 倍）", "亮度余量：请求 2 倍",
            "亮度余量：请求 4 倍", "亮度余量：请求 8 倍"), headroomIndex) {
            if (it != headroomIndex) { headroomIndex = it; event("HEADROOM_SELECTED", "requested=${headrooms[it]}"); render() }
        })
        preview = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); minimumHeight = dp(90) }
        val compact = resources.configuration.screenHeightDp < 550
        root.addView(preview, if (compact) LinearLayout.LayoutParams(-1, dp(200)) else LinearLayout.LayoutParams(-1, 0, 1f))
        status = label("初始化…", 12f).apply { minLines = 2; maxLines = 3; setPadding(0, dp(4), 0, dp(4)) }
        root.addView(status)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFillViewport = false; addView(controls) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, dp(214)))
        controls.addView(label("内置图三行 SDR 基图相同：上行无增益，中行 2 倍，下行 4 倍。保持系统亮度一致，逐项切换并记录观感。", 12f))
        controls.addView(label("模式 5／6 已跳过扩展预检，直接尝试创建 HDR Surface。原图无 gainmap 时，请用内置图完成这两项测试。", 12f))
        metadataStatus = label("选图后自动检查原文件元数据与未缩放解码，结果写入日志。", 12f)
        controls.addView(metadataStatus)
        row(controls, "明显提亮" to { observation("高光明显提亮") }, "没有变化" to { observation("没有变化") },
            "异常 / 黑屏" to { observation("颜色异常或黑屏") })
        environment = EditText(this).apply {
            hint = "备注：卓易通版本、系统图库对照或异常现象"; textSize = 12f
            setText(preferences.getString("environment_note", "")); maxLines = 2
        }
        controls.addView(environment)
        row(controls, "记录备注 / 状态" to { saveNote(); recordState("MANUAL_STATE"); toast("已记录") },
            "分享日志文件" to { export("share") })
        row(controls, "保存到下载" to { export("downloads") }, "另存为…" to { export("document") })
        controls.addView(label("日志持续保存在本机，重新进入可继续导出；导出不含图片。API 成功只表示请求或提交成功，实际效果以观感为准。", 11f))
        // 横屏或分屏时允许整页滚动，保证图片和导出按钮都能到达。
        if (compact) setContentView(ScrollView(this).apply { addView(root) }) else setContentView(root)
    }

    private fun spinner(options: Array<String>, initial: Int, changed: (Int) -> Unit): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@HdrDiagnosticsActivity, android.R.layout.simple_spinner_item, options).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        setSelection(initial, false)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = changed(position)
        }
    }

    private fun row(parent: LinearLayout, vararg actions: Pair<String, () -> Unit>) {
        val row = LinearLayout(this)
        for ((title, action) in actions) row.addView(Button(this).apply {
            text = title; textSize = 12f; isAllCaps = false; minWidth = 0
            setPadding(dp(2), 0, dp(2), 0); setOnClickListener { action() }
        }, LinearLayout.LayoutParams(0, dp(43), 1f))
        parent.addView(row)
    }

    private fun loadBuiltin() {
        if (Build.VERSION.SDK_INT < 34) return
        loadSource("内置增益图（1 / 2 / 4 倍）", null)
    }

    private fun loadSource(label: String, open: (() -> InputStream)?) {
        if (Build.VERSION.SDK_INT < 34) return
        if (loading) { toast("正在读取图片，请稍候"); return }
        loading = true
        modePicker.isEnabled = false
        val generation = ++imageGeneration
        clearPreview()
        image = null
        status.text = "正在读取图片…"
        event("IMAGE_READ_STARTED", "source=$label")
        io.execute {
            var file: File? = null
            try {
                file = File(journal.directory, "source-${System.nanoTime()}.jpg")
                if (open == null) HdrProbeImage.builtin(file)
                else open().use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        check(total <= 80L * 1024 * 1024) { "测试页仅接受不超过 80 MB 的图片" }
                        output.write(buffer, 0, n)
                    }
                } }
                event("IMAGE_COPY_COMPLETED", "source=$label; bytes=${file.length()}")
                val rawMetadata = inspectInput(file, fullSize = true)
                val decoded = HdrProbeImage.decode(file)
                if (open == null) check(decoded.bitmap.hasGainmap()) { "运行环境未保留内置测试图的 gainmap" }
                event("IMAGE_DECODED", "source=$label\n${decoded.description}")
                event("IMAGE_APP5_ADAPTER", decoded.app5Diagnostics)
                // 新图片完整解码后才替换持久化指针；失败不会丢失上次有效图片。
                preferences.edit().putString("source", file.name).putString("source_label", label).commit()
                val keep = file.name
                journal.directory.listFiles()?.filter { it.name.startsWith("source-") && it.name != keep }?.forEach { it.delete() }
                main.post {
                    if (isDestroyed || generation != imageGeneration) return@post
                    loading = false; modePicker.isEnabled = true; image = decoded; sourceLabel.text = label
                    metadataStatus.text = "文件检查：$rawMetadata。详情已写入日志。" +
                        if (decoded.usesApp5) "\n已提取 APP5 附加图；模式 6 提供未校准的实验重建。" else ""
                    render()
                }
            } catch (failure: Throwable) {
                file?.delete()
                event("IMAGE_LOAD_FAILED", failure.stackTraceToString())
                main.post { if (!isDestroyed && generation == imageGeneration) { loading = false; modePicker.isEnabled = true; showFailure("读取图片失败", failure) } }
            }
        }
    }

    private fun decodeSource(file: File, label: String) {
        loading = true
        modePicker.isEnabled = false
        val generation = ++imageGeneration
        io.execute {
            try {
                val rawMetadata = inspectInput(file, fullSize = false)
                val decoded = HdrProbeImage.decode(file)
                event("IMAGE_RESTORED", "source=$label\n${decoded.description}")
                event("IMAGE_APP5_ADAPTER", decoded.app5Diagnostics)
                main.post {
                    if (isDestroyed || generation != imageGeneration) return@post
                    loading = false; modePicker.isEnabled = true; image = decoded; sourceLabel.text = label
                    metadataStatus.text = "文件检查：$rawMetadata。重新选择原图可重做未缩放解码。" +
                        if (decoded.usesApp5) "\n已提取 APP5 附加图；模式 6 提供未校准的实验重建。" else ""
                    render()
                }
            } catch (failure: Throwable) {
                event("IMAGE_RESTORE_FAILED", failure.stackTraceToString())
                main.post { if (!isDestroyed) { loading = false; modePicker.isEnabled = true; showFailure("恢复图片失败，请重新选图", failure) } }
            }
        }
    }

    private fun inspectInput(file: File, fullSize: Boolean): String {
        event("IMAGE_INSPECTION_STARTED", "fullSizeRequested=$fullSize")
        val raw = HdrSourceMetadata.inspect(file)
        event("IMAGE_RAW_METADATA", raw.details)
        if (fullSize) event("IMAGE_FULL_SIZE_DECODE", HdrProbeImage.inspectFullSize(file))
        return raw.summary
    }

    private fun clearPreview() {
        renderGeneration++
        photoView?.let { Glide.with(this).clear(it) }
        photoView = null
        if (::preview.isInitialized) preview.removeAllViews()
    }

    private fun render() {
        if (Build.VERSION.SDK_INT < 34 || !resumed) return
        clearPreview()
        val selected = image ?: return
        val generation = renderGeneration
        val selectedMode = mode
        val requested = headrooms[headroomIndex]
        try {
            window.colorMode = if (mode in 1..3) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
            if (Build.VERSION.SDK_INT >= 35) window.desiredHdrHeadroom = if (mode in 1..3) requested else 1f
        } catch (failure: Throwable) { event("WINDOW_REQUEST_FAILED", failure.stackTraceToString()) }
        event("RENDER_REQUEST", "mode=${modes[mode]}; headroomRequest=$requested; " +
            "hasGainmap=${selected.bitmap.hasGainmap()}; app5Experimental=${selected.usesApp5}; PQ referenceWhite=203 nit (probe assumption); " +
            "Surface reconstructionHeadroom=${if (requested == 0f) 4f else requested}")
        status.text = if (mode == 0 || mode == 6) "SDR 对照：只显示基图。" else "HDR 请求已发出，正在显示…"
        when (mode) {
            0, 1, 3 -> {
                preview.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(if (mode == 0) selected.base else selected.bitmap)
                }, FrameLayout.LayoutParams(-1, -1))
                status.text = if (mode == 0) "SDR 基图。切换其他模式后观察高光是否增强。"
                    else "ImageView 已设置图片；gainmap=${selected.bitmap.hasGainmap()}。请记录观感。"
            }
            2 -> {
                val target = PhotoView(this)
                photoView = target
                preview.addView(target, FrameLayout.LayoutParams(-1, -1))
                Glide.with(this).asBitmap().load(selected.file).dontTransform().disallowHardwareConfig()
                    .downsample(DownsampleStrategy.AT_MOST)
                    .override(HdrProbeImage.MAX_EDGE, HdrProbeImage.MAX_EDGE)
                    .listener(object : RequestListener<Bitmap> {
                        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>, isFirstResource: Boolean): Boolean {
                            event("GLIDE_FAILED", e?.stackTraceToString().orEmpty())
                            if (!isDestroyed && generation == renderGeneration) status.text = "Glide 加载失败，详情已记录到日志。"
                            return false
                        }
                        override fun onResourceReady(resource: Bitmap, model: Any, target: Target<Bitmap>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                            event("GLIDE_DECODED", "cache=$dataSource\n${HdrProbeImage.describe(resource)}")
                            if (!isDestroyed && generation == renderGeneration) status.text = "Glide + PhotoView 已显示；gainmap=${resource.hasGainmap()}。请记录观感。"
                            return false
                        }
                    }).into(target)
            }
            else -> {
                if (selectedMode != 6 && !selected.hasHdrSurfaceData) {
                    status.text = "图片已读入，但未获得标准 Gainmap 或受支持的 APP5 附加图。可查看模式 1／7；解析原因已记录，可直接导出日志。"
                    event("HDR_SURFACE_SKIPPED", "mode=${modes[selectedMode]}; reason=no_usable_hdr_source; " +
                        "imageDecoded=true; surfaceCreationAttempted=false; ${selected.app5Diagnostics}")
                    return
                }
                status.text = if (selected.usesApp5 && selectedMode != 6) "正在准备 APP5 实验重建，曲线尚未校准…" else "正在准备独立 Surface…"
                io.execute {
                    try {
                        val data = selected.gpuData(pq = selectedMode == 5, sdr = selectedMode == 6)
                        main.post {
                            if (isDestroyed || !resumed || generation != renderGeneration) return@post
                            val output = when (selectedMode) { 4 -> HdrProbeSurface.Output.SCRGB; 5 -> HdrProbeSurface.Output.PQ; else -> HdrProbeSurface.Output.SDR }
                            val surface = HdrProbeSurface(this, data, output, if (requested == 0f) 4f else requested, requested) { name, details, success ->
                                event(name, "mode=${modes[selectedMode]}\n$details")
                                if (name == "EGL_FRAME_SUBMITTED" || !success) main.post {
                                    if (!isDestroyed && generation == renderGeneration) status.text = if (success)
                                        if (selected.usesApp5 && selectedMode != 6)
                                            "APP5 实验帧已提交（$output，参考余量 ${if (requested == 0f) 4f else requested} 倍）；曲线未校准，请对照图库记录高光、颜色和暗部。"
                                        else "Surface 帧已提交（$output）；实际提亮需记录观感。"
                                    else "Surface 失败：${details.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") } ?: name}"
                                }
                            }
                            preview.addView(surface, FrameLayout.LayoutParams(-1, -1))
                        }
                    } catch (failure: Throwable) {
                        event("SURFACE_PREPARATION_FAILED", "mode=${modes[selectedMode]}\n${failure.stackTraceToString()}")
                        main.post { if (!isDestroyed && generation == renderGeneration) showFailure("Surface 准备失败", failure) }
                    }
                }
            }
        }
        preview.postDelayed({ if (!isDestroyed && resumed && generation == renderGeneration) recordState("AFTER_RENDER_1S") }, 1000)
    }

    private fun observation(value: String) {
        saveNote()
        event("USER_OBSERVATION", "mode=${modes[mode]}; image=${sourceLabel.text}; requestedHeadroom=${headrooms[headroomIndex]}; " +
            "observation=$value; renderStatus=${status.text}")
        recordState("OBSERVATION_STATE")
        toast("已记录：$value")
    }

    private fun saveNote() {
        if (!::environment.isInitialized) return
        val note = environment.text.toString().take(4000)
        preferences.edit().putString("environment_note", note).apply()
        if (note.isNotBlank()) event("USER_NOTE", note)
    }

    @Suppress("DEPRECATION")
    private fun recordState(name: String) {
        if (!::journal.isInitialized) return
        val details = buildString {
            fun field(key: String, read: () -> Any?) { append("$key=${runCatching(read).getOrElse { "unavailable: $it" }}\n") }
            field("app") { "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) debug=${BuildConfig.DEBUG}" }
            field("debugUpdateCode") { resources.getIdentifier("debug_update_code", "string", packageName).let { if (it == 0) "local" else getString(it) } }
            field("runtime") { "sdk=${Build.VERSION.SDK_INT}; release=${Build.VERSION.RELEASE}; securityPatch=${Build.VERSION.SECURITY_PATCH}" }
            field("device") { "manufacturer=${Build.MANUFACTURER}; brand=${Build.BRAND}; model=${Build.MODEL}; device=${Build.DEVICE}; hardware=${Build.HARDWARE}" }
            field("fingerprint") { Build.FINGERPRINT }
            val display = windowManager.defaultDisplay
            field("display") { "id=${display.displayId}; name=${display.name}; mode=${display.mode}; state=${display.state}; isHdr=${display.isHdr}; wideColorGamut=${display.isWideColorGamut}" }
            field("supportedHdrTypes") { display.hdrCapabilities.supportedHdrTypes.contentToString() + " (1=DolbyVision,2=HDR10,3=HLG,4=HDR10+)" }
            field("desiredLuminance") { display.hdrCapabilities.let { "max=${it.desiredMaxLuminance}; avg=${it.desiredMaxAverageLuminance}; min=${it.desiredMinLuminance}" } }
            if (Build.VERSION.SDK_INT >= 34) {
                field("hdrSdrRatioAvailable") { display.isHdrSdrRatioAvailable }
                field("hdrSdrRatio") { display.hdrSdrRatio }
                append("ratioInterpretation=不可用时比例返回 1，不能据此判定 HDR 无效；只表示此 Android 显示环境。\n")
            }
            if (Build.VERSION.SDK_INT >= 36) field("highestHdrSdrRatio") { display.highestHdrSdrRatio }
            field("windowColorModeRequested") { window.colorMode }
            if (Build.VERSION.SDK_INT >= 35) field("windowDesiredHeadroomRequested") { window.desiredHdrHeadroom }
            field("hardwareAccelerated") { window.decorView.isHardwareAccelerated }
            field("windowBrightness") { window.attributes.screenBrightness }
            field("systemBrightness") { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) }
            field("systemBrightnessMode") { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) }
            val power = getSystemService(PowerManager::class.java)
            field("powerSave") { power.isPowerSaveMode }
            if (Build.VERSION.SDK_INT >= 29) field("thermalStatus") { power.currentThermalStatus }
            field("mode") { modes[mode] }
            field("headroomSelection") { headrooms[headroomIndex] }
        }
        event(name, details)
    }

    private fun export(destination: String) {
        saveNote()
        recordState("EXPORT_STATE")
        event("EXPORT_REQUEST", "destination=$destination")
        io.execute {
            try {
                val file = journal.snapshot(File(cacheDir, "hdr-exports/${journal.directory.name}-${System.currentTimeMillis()}.txt"))
                HdrViewerDiagnostics.appendSnapshot(this, file)
                if (destination == "downloads") {
                    if (Build.VERSION.SDK_INT < 29) error("此环境请使用另存为或分享日志")
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/EverythingDone/HDR")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("无法创建下载文件")
                    try {
                        contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                            ?: error("无法写入下载文件")
                        check(contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0)
                    } catch (failure: Throwable) { contentResolver.delete(uri, null, null); throw failure }
                    event("EXPORT_SAVED", "Download/EverythingDone/HDR/${file.name}")
                    main.post { if (!isDestroyed) { status.text = "已保存：Download/EverythingDone/HDR/${file.name}"; toast("日志已保存到下载目录") } }
                } else main.post {
                    if (isDestroyed) return@post
                    try {
                        if (destination == "document") {
                            pendingExport = file.name
                            saveDocument.launch(file.name)
                        } else {
                            val uri = FileProvider.getUriForFile(this, packageName, file)
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newRawUri("HDR 诊断日志", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "导出 HDR 诊断日志"))
                        }
                    } catch (failure: Throwable) { showFailure("导出界面不可用，请尝试另一种导出方式", failure) }
                }
            } catch (failure: Throwable) {
                event("EXPORT_FAILED", failure.stackTraceToString())
                main.post { if (!isDestroyed) showFailure("导出失败，请尝试另存为或分享", failure) }
            }
        }
    }

    private fun writeDocument(uri: Uri, file: File) {
        io.execute {
            try {
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: error("无法写入所选位置")
                event("EXPORT_DOCUMENT_SAVED", file.name)
                main.post { if (!isDestroyed) toast("日志文件已保存") }
            } catch (failure: Throwable) {
                event("EXPORT_DOCUMENT_FAILED", failure.stackTraceToString())
                main.post { if (!isDestroyed) showFailure("另存为失败", failure) }
            }
        }
    }

    private fun event(name: String, details: String = "") {
        if (!::journal.isInitialized) return
        try { journal.event(name, details) } catch (failure: Exception) {
            main.post { if (!isDestroyed && ::status.isInitialized) status.text = "日志写入失败：${failure.message}" }
        }
    }

    private fun showFailure(message: String, failure: Throwable) {
        event("ERROR", "$message\n${failure.stackTraceToString()}")
        status.text = "$message：${failure.message}"
        toast(message)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        resumed = true
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        if (Build.VERSION.SDK_INT >= 34) runCatching {
            windowManager.defaultDisplay.registerHdrSdrRatioChangedListener(mainExecutor, hdrRatioListener)
            hdrRatioListenerRegistered = true
        }.onFailure { event("HDR_RATIO_LISTENER_UNAVAILABLE", it.toString()) }
        recordState("ACTIVITY_RESUME")
        render()
    }

    override fun onPause() {
        saveNote()
        event("ACTIVITY_PAUSE")
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        if (Build.VERSION.SDK_INT >= 34 && hdrRatioListenerRegistered) {
            runCatching { windowManager.defaultDisplay.unregisterHdrSdrRatioChangedListener(hdrRatioListener) }
                .onFailure { event("HDR_RATIO_LISTENER_REMOVE_FAILED", it.toString()) }
            hdrRatioListenerRegistered = false
        }
        resumed = false
        clearPreview()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("mode", mode)
        outState.putInt("headroom", headroomIndex)
        outState.putString("pending_export", pendingExport)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        event("ACTIVITY_DESTROY")
        clearPreview()
        // 已开始的图片复制、日志导出继续落盘；回调检查 Activity 是否销毁。
        io.shutdown()
        image = null
        super.onDestroy()
    }
}
