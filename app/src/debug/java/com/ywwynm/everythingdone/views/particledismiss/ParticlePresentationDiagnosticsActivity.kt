package com.ywwynm.everythingdone.views.particledismiss

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.fragments.BaseDialogFragment
import java.io.File

private fun installDiagnosticInsets(root: View) {
    val left = root.paddingLeft; val top = root.paddingTop
    val right = root.paddingRight; val bottom = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
        insets
    }
    root.doOnAttach { ViewCompat.requestApplyInsets(it) }
}

/** 无需命令行的入口；虚构场景与实际页面分别提供对照，统一实现是应用默认。 */
class ParticlePresentationDiagnosticsActivity : AppCompatActivity() {
    private lateinit var column: LinearLayout
    private lateinit var mode: Spinner
    private lateinit var dialogMode: Spinner
    private lateinit var images: CheckBox
    private lateinit var environment: EditText
    private lateinit var resultText: TextView
    private var lastRun: String? = null
    private var awaitingReturnTouch = false
    private var awaitingReturnScroll = false
    private var summaryGeneration = 0
    private var pendingZip: File? = null
    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = pendingZip
        pendingZip = null
        if (uri != null && file != null) ParticlePresentationReports.worker.execute {
            val result = runCatching {
                checkNotNull(contentResolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
            }
            runOnUiThread { toast(if (result.isSuccess) "诊断包已保存" else "保存失败：${result.exceptionOrNull()?.message}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastRun = savedInstanceState?.getString("lastRun")
        pendingZip = savedInstanceState?.getString("pendingZip")
            ?.takeIf { it.matches(Regex("particle-display-[0-9]+\\.zip")) }
            ?.let { File(cacheDir, "presentation-exports/$it") }?.takeIf { it.isFile }
        title = "显示兼容性检查"
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(24)) }
        setContentView(ScrollView(this).apply {
            addView(column); installDiagnosticInsets(this)
            setOnScrollChangeListener { _, _, y, _, oldY ->
                if (awaitingReturnScroll && y != oldY) {
                    awaitingReturnScroll = false
                    ParticlePresentationReports.find(lastRun)?.event("diagnostic_parent_first_scroll",
                        mapOf("from" to oldY, "to" to y))
                }
            }
        })
        text("显示兼容性检查", 23f)
        text("统一实现同时用于实际页面。下拉选项仅改变虚构测试；实际页面对照使用下方专用按钮，重启应用恢复统一实现。无需 ADB。\n当前样式：${if (ParticleStyle.current == ParticleStyle.DRIFT) "飘散" else "流体"}；切换样式请返回粒子设置。\n应用可见 Android API：${Build.VERSION.SDK_INT}")
        environment = EditText(this).apply {
            hint = "卓易通版本／设备及补充说明（可选）"
            setText(savedInstanceState?.getString("environment"))
            maxLines = 3
        }
        column.addView(environment)
        text("1．选择本次对照")
        mode = spinner(listOf("原实现", "上一版前景根节点", "只改 GL 前景与交接", "只改 Dialog", "上一版 GL 组合",
            "旧透明度交接（已知空白）", "只修独立 Surface", "上一轮完整帧组合（较慢）", "只修完整帧交接",
            "上一轮快速准备与按钮阴影", "统一实现：动态背景与完整控件"))
        text("Dialog 候选（仅选择修改 Dialog 时生效）")
        dialogMode = spinner(listOf("提前设置窗口透明度", "额外隐藏普通内容"))
        images = CheckBox(this).apply { text = "采集关键图像（关闭后用于流畅度对照）"; isChecked = true }
        column.addView(images)
        text("2．打开一个测试。返回本页后记录结果；每个对照先试首次和连续操作。")
        button("首页前景与其它卡片阴影：快速滑动、慢拖、回拉") { launchCase("卡片") }
        button("Doing 页面：透明壁纸、左右滑动（不计时）") { launchCase("Doing") }
        button(getString(R.string.particle_doing_scene_probe)) { launchDoingGlScene() }
        button(getString(R.string.particle_doing_long_scene_probe)) { launchDoingGlScene(longCard = true) }
        text(getString(R.string.particle_doing_scene_probe_description))
        button("普通 Dialog：打开与关闭") { launchCase("普通弹窗") }
        button("按钮波纹：关闭 Dialog 时的消散") { launchRipple("dismiss", "custom") }
        button("按钮波纹：连续弹窗转场") { launchRipple("transition", "custom") }
        button("按钮波纹：Doing 页面自然淡出") { launchRipple("doing-control", "native") }
        button("叠层 Dialog：弹窗内再打开") { launchCase("叠层弹窗") }
        button("底部面板：打开与关闭") { launchCase("底部面板") }
        button("独立 Surface：固定色块弹窗") { launchCase("独立Surface") }
        button("复杂控件：嵌套阴影、旋转与裁剪") { launchCase("复杂控件") }
        button("连续弹窗：关闭与出现重叠") { launchCase("连续弹窗") }
        button("固定色块：跟随所选前景方式") { launchCase("挂载色块") }
        text("实际页面也默认使用统一实现。以下对照只在本次应用运行生效，诊断不采集记事内容。")
        text("本轮请同时观察静止卡片的阴影。录音问题请开启实际页面诊断，再从已有记事进入“添加附件 → 录音”，观察计时文字与水体恢复；无需开始录音。完成后回本页分享诊断包。")
        button("实际页面：统一实现并记录诊断") {
            ParticlePresentationBusinessTrace.enable(application, false); toast("已选择统一实现，可返回首页测试")
        }
        button("实际页面：原实现并记录诊断") {
            ParticlePresentationBusinessTrace.enable(application, true); toast("已选择原实现，可返回首页对照")
        }
        text("3．记录刚才测试的实际画面")
        listOf("正常", "消散未播，只看到回聚", "开始明显延迟", "按钮阴影后出现", "阴影或文字变深",
            "Surface 露出灰块", "计时文字变淡或描边", "粒子后水体停顿", "其它卡片阴影突变", "波纹冻结或残留",
            "前景消失", "面板提前出现", "闪烁或残影", "返回后暂时无法滑动", "无法判断").forEach { observation ->
            button(observation) {
                val report = ParticlePresentationReports.find(lastRun)
                if (report == null) toast("请先完成一个测试")
                else { report.observe(observation); refreshResults() }
            }
        }
        button("查看本次捕获图像") { showImages() }
        button("复制最近测试简报") {
            loadSummary { summary ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                    ClipData.newPlainText("显示兼容性诊断", summary))
                toast("简报已复制")
            }
        }
        button("分享诊断包") { export(save = false) }
        button("保存诊断包到文件") { export(save = true) }
        text("快速滑动后应先消散再回聚；背景色条应持续移动。检查嵌套阴影、旋转与裁剪、连续弹窗和独立 Surface 恢复。关闭关键图像后再比较流畅度和响应；真实记事只作查看与取消操作。")
        resultText = text("")
        button("返回粒子设置") { finish() }
        mode.setSelection(savedInstanceState?.getInt("mode") ?: 10)
        dialogMode.setSelection(savedInstanceState?.getInt("dialogMode") ?: 1)
        images.isChecked = savedInstanceState?.getBoolean("images") ?: true
    }

    override fun onResume() {
        super.onResume()
        ParticlePresentationReports.find(lastRun)?.event("diagnostic_parent_resumed")
        awaitingReturnTouch = lastRun != null; awaitingReturnScroll = lastRun != null
        if (::resultText.isInitialized) refreshResults()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        ParticlePresentationReports.find(lastRun)?.event("diagnostic_parent_window_focus", mapOf("focus" to hasFocus))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (awaitingReturnTouch && event.actionMasked == MotionEvent.ACTION_DOWN) {
            awaitingReturnTouch = false
            ParticlePresentationReports.find(lastRun)?.event("diagnostic_parent_first_touch")
        }
        return super.dispatchTouchEvent(event)
    }

    internal fun bindExitReportForTests(report: ParticlePresentationReports.Report) { lastRun = report.id }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("lastRun", lastRun); outState.putInt("mode", mode.selectedItemPosition)
        outState.putInt("dialogMode", dialogMode.selectedItemPosition); outState.putBoolean("images", images.isChecked)
        outState.putString("environment", environment.text.toString())
        outState.putString("pendingZip", pendingZip?.name)
        super.onSaveInstanceState(outState)
    }

    private fun launchRipple(scene: String, ripple: String) {
        val original = mode.selectedItemPosition == 0
        val report = ParticlePresentationReports.start(this, "按钮波纹：$scene",
            if (original) "原实现对照" else "统一实现",
            ParticlePresentationPolicy.debugOptions(original, images.isChecked).copy(fixture = true), environment.text.toString())
        lastRun = report.id
        startActivity(Intent(this, ParticleRippleProbeActivity::class.java)
            .putExtra(ParticlePresentationReports.EXTRA_RUN, report.id)
            .putExtra("scene", scene).putExtra("ripple", ripple)
            .putExtra("images", images.isChecked).putExtra("mode", if (original) "original" else "unified"))
    }

    private fun launchDoingGlScene(longCard: Boolean = false) {
        if (Build.VERSION.SDK_INT < 34) { toast("此对照需要 API 34 或更高版本"); return }
        val original = mode.selectedItemPosition == 0
        val report = ParticlePresentationReports.start(this, if (longCard) "Doing 长卡片与计时" else "Doing 整页 GL",
            if (original) "原实现对照" else "统一 SurfaceView 页面绘制",
            ParticlePresentationPolicy.debugOptions(original, images.isChecked).copy(fixture = true), environment.text.toString())
        lastRun = report.id
        startActivity(Intent(this, ParticleDoingProbeActivity::class.java)
            .putExtra(ParticlePresentationReports.EXTRA_RUN, report.id).putExtra("doing_gl_scene", !original)
            .putExtra("doing_long_card", longCard).putExtra("doing_demo_clock", longCard)
            .putExtra("dynamic_background", true))
    }

    private fun launchCase(scene: String) {
        if ((scene == "卡片" || scene == "挂载色块") && Build.VERSION.SDK_INT < 34) {
            toast("当前 API 不支持卡片 Surface 同步路径，本项不改用 TextureView。请先测试 Dialog 并导出简报。")
            return
        }
        val choice = mode.selectedItemPosition
        val changesDialog = choice >= 3
        val selectedDialog = if (!changesDialog) ParticlePresentationDiagnostics.DialogMode.ORIGINAL
            else if (dialogMode.selectedItemPosition == 0) ParticlePresentationDiagnostics.DialogMode.EARLY_WINDOW
            else ParticlePresentationDiagnostics.DialogMode.CONTENT
        val label = mode.selectedItem.toString() + if (changesDialog) " / ${dialogMode.selectedItem}" else ""
        val report = ParticlePresentationReports.start(this, scene, label,
            ParticlePresentationDiagnostics.Options(rootForeground = choice == 1, dialogMode = selectedDialog,
                images = images.isChecked, glForeground = choice == 2 || choice >= 4,
                synchronizedGlHandoff = choice == 5, surfaceRecovery = choice == 6 || choice == 7 || choice >= 9,
                opaqueGlHandoff = choice in 7..10, fastOpaqueHandoff = choice >= 9, unified = choice == 10),
            environment.text.toString())
        lastRun = report.id
        val target = if (scene == "Doing") ParticleDoingProbeActivity::class.java
            else if (scene == "卡片") ParticleSwipeShadowProbeActivity::class.java else ParticlePresentationCaseActivity::class.java
        startActivity(Intent(this, target).putExtra(ParticlePresentationReports.EXTRA_RUN, report.id)
            .putExtra("scene", scene).putExtra("with_chrome", true).putExtra("normal_chrome", true)
            .putExtra("dynamic_background", true)
            .putExtra("card_count", if (scene == "卡片") 3 else 1)
            .putExtra("card_height_dp", if (scene == "卡片") 120 else 180)
            .putExtra("with_fab", true).putExtra("performance_only", true).putExtra("run_id", report.id))
    }

    private fun refreshResults() {
        val generation = ++summaryGeneration
        val run = lastRun
        loadSummary { summary ->
            if (generation == summaryGeneration && run == lastRun)
                resultText.text = "本次结果对应：${run ?: "尚未开始"}\n\n$summary"
        }
    }

    private fun loadSummary(done: (String) -> Unit) {
        val start = System.nanoTime()
        val report = ParticlePresentationReports.find(lastRun)
        val app = applicationContext
        ParticlePresentationReports.worker.execute {
            val summary = ParticlePresentationReports.summary(app)
            report?.event("diagnostic_summary_updated",
                mapOf("durationMs" to (System.nanoTime() - start) / 1e6, "backgroundThread" to true))
            runOnUiThread { if (!isDestroyed) done(summary) }
        }
    }

    private fun showImages() {
        val id = lastRun ?: return toast("请先完成一个测试")
        startActivity(Intent(this, ParticlePresentationImagesActivity::class.java)
            .putExtra(ParticlePresentationReports.EXTRA_RUN, id))
    }

    private fun export(save: Boolean) {
        toast("正在整理最近 12 次测试")
        ParticlePresentationReports.export(this) done@{ result ->
            if (isDestroyed) return@done
            result.onSuccess { file ->
                pendingZip = file
                try {
                    if (save) saveDocument.launch(file.name)
                    else {
                        val uri = FileProvider.getUriForFile(this, "com.ywwynm.everythingdone", file)
                        val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        send.clipData = ClipData.newRawUri("诊断包", uri)
                        startActivity(Intent.createChooser(send, "分享显示兼容性诊断"))
                    }
                } catch (failure: android.content.ActivityNotFoundException) {
                    toast(if (save) "当前环境没有文件保存入口，请分享诊断包或复制简报"
                        else "当前环境没有分享入口，请保存到文件或复制简报")
                }
            }.onFailure { toast("导出失败：${it.message}") }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 15f): TextView = TextView(this).apply {
        text = value; textSize = size; setPadding(0, dp(12), 0, dp(8)); setTextIsSelectable(true)
        column.addView(this)
    }
    private fun button(label: String, action: () -> Unit) { column.addView(Button(this).apply { text = label; setOnClickListener { action() } }) }
    private fun spinner(values: List<String>) = Spinner(this).also {
        it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values); column.addView(it)
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}

/** 每个测试独占 Activity，配置在窗口生命周期内固定；真正的 Dialog 使用生产基类。 */
class ParticlePresentationCaseActivity : AppCompatActivity() {
    private var report: ParticlePresentationReports.Report? = null
    private var marker: ParticlePresentationMarker? = null
    private var metricsAttached = false
    private val frameListener = android.view.Window.OnFrameMetricsAvailableListener { _, frame, dropped ->
        report?.event("window_frame", mapOf("intendedVsync" to frame.getMetric(android.view.FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
            "durationNanos" to frame.getMetric(android.view.FrameMetrics.TOTAL_DURATION),
            "droppedCallbacks" to dropped, "meaning" to "window_only_not_particle_surface"))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        report = ParticlePresentationReports.bind(this)
        val current = report
        if (current == null) { finish(); return }
        window.addOnFrameMetricsAvailableListener(frameListener, android.os.Handler(android.os.Looper.getMainLooper()))
        metricsAttached = true
        val spacing = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(spacing, spacing, spacing, spacing) }
        val scene = intent.getStringExtra("scene") ?: "普通弹窗"
        root.addView(TextView(this).apply { text = "${current.id}\n${current.label}\n$scene"; textSize = 20f })
        root.addView(TextView(this).apply {
            text = if (scene == "挂载色块") "页面中部应出现蓝色和橙色色块；返回后比较“原实现”和“GL 前景”方式。本版已修正独立色块的缓冲用途。原 View 不参与隐藏。"
                else "观察打开时是否只有粒子，凝聚结束后面板再出现。关闭后可再次打开。返回检查页记录结果。"
        })
        root.addView(Button(this).apply {
            text = if (scene == "挂载色块") "显示固定色块" else "打开测试弹窗"
            setOnClickListener {
                if (scene == "挂载色块") {
                    if (Build.VERSION.SDK_INT >= 34) {
                        marker?.close(); marker = ParticlePresentationMarker(this@ParticlePresentationCaseActivity, current)
                        marker?.show()
                    }
                } else if (supportFragmentManager.fragments.none { it is ParticlePresentationTestDialog }) {
                    ParticlePresentationTestDialog().apply { arguments = Bundle().apply { putString("scene", scene) } }
                        .show(supportFragmentManager, "presentation-test")
                }
            }
        })
        root.addView(Button(this).apply { text = "返回检查页"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(root); installDiagnosticInsets(this) })
    }
    override fun onStop() {
        supportFragmentManager.fragments.filterIsInstance<BaseDialogFragment>().forEach { fragment ->
            fragment.dialog?.window?.let { window ->
                ParticleDismissController.cancelAppearance(window.decorView)
                ParticleDialogVisibility.restore(window, "activity_stopped")
            }
        }
        ParticleDismissOverlay.activeIn(this).toList().forEach { it.release() }
        marker?.close(); marker = null
        report?.event("activity_stopped"); report?.save(); super.onStop()
    }
    override fun onDestroy() {
        marker?.close()
        if (metricsAttached) window.removeOnFrameMetricsAvailableListener(frameListener)
        super.onDestroy()
        report?.event("activity_destroyed"); report?.save()
        ParticlePresentationDiagnostics.unbind(this)
    }
}

class ParticlePresentationTestDialog : BaseDialogFragment() {
    override fun getLayoutResource() = 0
    override fun createDialogContent(inflater: LayoutInflater, container: ViewGroup?): View {
        val context = inflater.context
        val scene = arguments?.getString("scene") ?: "普通弹窗"
        val level = arguments?.getInt("level") ?: 0
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; minimumWidth = dp(280); setPadding(dp(20), dp(20), dp(20), dp(12))
        }
        root.addView(TextView(context).apply {
            text = "$scene · 第 ${level + 1} 层\n固定虚构内容\n检查文字、背景与圆角是否提前出现。"
            textSize = 18f; setTextColor(ContextCompat.getColor(context, R.color.app_chrome_on_surface_primary))
        })
        if (scene == "独立Surface" || scene == "复杂控件") {
            val surface = ParticlePresentationColorSurface(context)
            if (scene == "复杂控件") root.addView(android.widget.FrameLayout(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(216, 233, 245)); cornerRadius = dp(22).toFloat()
                }
                clipToOutline = true; elevation = dp(6).toFloat()
                scaleX = .9f; translationX = dp(6).toFloat()
                addView(surface, android.widget.FrameLayout.LayoutParams(-1, -1))
                addView(TextView(context).apply { text = "半透明覆盖文字"; setTextColor(Color.WHITE)
                    setBackgroundColor(0x66442211); elevation = dp(3).toFloat()
                }, android.widget.FrameLayout.LayoutParams(-1, dp(30)))
            }, LinearLayout.LayoutParams(-1, dp(96)))
            else root.addView(surface, LinearLayout.LayoutParams(-1, dp(96)))
        }
        if (scene == "复杂控件") {
            root.addView(android.widget.FrameLayout(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(216, 233, 245)); cornerRadius = dp(16).toFloat()
                }
                elevation = dp(8).toFloat(); rotation = -4f; clipToOutline = true
                addView(Button(context).apply {
                    text = "嵌套阴影与裁剪"; elevation = dp(12).toFloat(); rotation = 7f
                }, android.widget.FrameLayout.LayoutParams(dp(230), dp(64)).apply {
                    leftMargin = dp(20); topMargin = dp(16)
                })
            }, LinearLayout.LayoutParams(-1, dp(100)).apply { topMargin = dp(16); bottomMargin = dp(16) })
        }
        if (scene == "连续弹窗" && level == 0) root.addView(Button(context).apply {
            text = "切换下一弹窗"
            setOnClickListener {
                ParticlePresentationTestDialog().apply {
                    arguments = Bundle().apply { putString("scene", "复杂控件"); putInt("level", 1) }
                }.show(parentFragmentManager, "presentation-transition")
                dismiss()
            }
        })
        if (scene == "叠层弹窗" && level == 0) root.addView(Button(context).apply {
            text = "在此弹窗上再打开"
            setOnClickListener {
                ParticlePresentationTestDialog().apply {
                    arguments = Bundle().apply { putString("scene", scene); putInt("level", 1) }
                }.show(parentFragmentManager, "presentation-nested")
            }
        })
        root.addView(Button(context).apply { text = "关闭"; setOnClickListener { dismiss() } })
        return root
    }
    override fun onStart() {
        super.onStart()
        if (arguments?.getString("scene") == "底部面板") dialog?.window?.apply {
            setGravity(Gravity.BOTTOM); setWindowAnimations(R.style.EverythingDoneAnimationBottomPanel)
        }
    }
}

class ParticlePresentationImagesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(ParticlePresentationReports.EXTRA_RUN).orEmpty()
        if (!id.matches(Regex("[0-9-]+"))) { finish(); return }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 40, 24, 24) }
        setContentView(ScrollView(this).apply { addView(column); installDiagnosticInsets(this) })
        column.addView(TextView(this).apply { text = "$id\n以下包含源图像和 GL 输出，均不代表屏幕最终合成。原始硬件缓冲可能按旋转方向排列。" })
        val files = File(filesDir, "presentation-diagnostics/$id").listFiles().orEmpty().filter { it.extension == "png" }.sortedBy { it.name }
        if (files.isEmpty()) column.addView(TextView(this).apply { text = "尚无图像：请启用关键图像采集并完成测试后重试。" })
        files.forEach { file ->
            column.addView(TextView(this).apply { text = file.name })
            column.addView(ImageView(this).apply {
                setBackgroundColor(Color.LTGRAY); adjustViewBounds = true
                setImageURI(android.net.Uri.fromFile(file))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        column.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
    }
}
