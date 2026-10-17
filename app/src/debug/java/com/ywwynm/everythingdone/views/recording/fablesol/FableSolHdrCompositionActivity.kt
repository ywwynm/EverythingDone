@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.views.recording.fablesol

import android.app.Dialog
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.hdr.HdrDiagnosticJournal
import com.ywwynm.everythingdone.fragments.BaseDialogFragment
import com.ywwynm.everythingdone.interaction.UiInteractions
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.views.particledismiss.DialogDimLayer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 卓易通合成试验；固定图与真实海浪分别对照，只存在于 debug，不改用户参数。 */
class FableSolHdrCompositionActivity : FragmentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var journal: HdrDiagnosticJournal
    private lateinit var dialog: Dialog
    private lateinit var preview: FrameLayout
    private lateinit var status: TextView
    private lateinit var explanation: TextView
    private lateinit var observation: EditText
    private lateinit var panel: View
    private lateinit var conditionLabel: TextView
    private lateinit var conditionSpinner: Spinner
    private var probe: SurfaceView? = null
    private var canvasProbe: View? = null
    private var directProbe: FableSolCompositionControlView? = null
    private var wave: WaveVisualizerFableSolHost? = null
    private var appDialog = false
    private var prepared = false
    private var frozen = false
    private var destroying = false
    private var mode = Mode.SDR
    private var generation = 0
    private var presentedGeneration = -1
    private var exportBusy = false
    private var condition = Condition.BASELINE
    private var conditionRevision = 0
    private var conditionReady = false
    private var windowBaseline: WindowBaseline? = null
    private var cornerMask: FableSolDialogCornerMask? = null
    private var windowShadowDisabled = false

    /** A～I 保留原定义；J、K 从 C 出发，单独排查窗口外角，不混入穿透问题。 */
    private enum class Condition(val title: String, val description: String) {
        BASELINE("A · 原始配置", "应用暗层 0.6、原窗口与面板背景、窗口和面板圆角裁剪。"),
        NO_DIM("B · 无暗层", "只隐藏应用暗层；系统暗层也保持关闭。后方变亮属于预期变化。"),
        SYSTEM_DIM("C · 改用系统暗层", "用相同 0.6 浓度的系统暗层替代应用暗层，其余同 A。"),
        CLEAR_WINDOW("D · 透明窗口背景", "只去掉窗口背景填充；面板白底和两处裁剪保留。"),
        CLEAR_PANEL("E · 透明面板背景", "只去掉内容根节点的背景填充；窗口背景和裁剪保留。"),
        NO_WINDOW_CLIP("F · 关闭窗口裁剪", "只关闭窗口 decor 的 clipToOutline；面板及画面圆角保留。"),
        NO_PANEL_CLIP("G · 关闭面板裁剪", "只关闭内容根节点的 clipToOutline；窗口及画面圆角保留。"),
        NO_BOTH_CLIPS("H · 关闭两处裁剪", "组合项：同时关闭窗口和内容根节点裁剪；背景和画面圆角仍保留。"),
        SQUARE_SURFACE("I · 固定图无圆角", "只关闭固定画面 shader 的圆角；窗口与面板仍同 A。此项限模式 1～4。"),
        SYSTEM_DIM_NO_WINDOW_CLIP("J · C＋关闭窗口裁剪", "保留 C 系统暗层，只关闭 Decor 轮廓裁剪；背景、内容裁剪与海浪圆角不变。"),
        SYSTEM_DIM_CORNER_MASK("K · C＋显式清角", "在 J 基础上，最后清除窗口圆角外的像素。此前实测仍有黑角。"),
        SYSTEM_DIM_CANVAS("L · 无独立 Surface", "以 C 为基准，完全不创建 SurfaceView，用普通 Canvas 画静态 SDR 图。此项没有 HDR；观察窗口黑角。"),
        SYSTEM_DIM_NO_SHADOW("M · C＋去阴影", "保留 C 的透明 PQ 固定图，只把窗口、Decor 和面板祖先的 elevation／translationZ 归零。"),
        SYSTEM_DIM_DIRECT("N · 直接 SurfaceControl", "保留 C 条件，直接创建非 opaque 的 RGBA8 图层，复用相同 PQ 和固定图。检查黑角、左白圈是否被覆盖及高光。")
    }

    private fun Condition.isLayerComparison() = this == Condition.SYSTEM_DIM_CANVAS ||
        this == Condition.SYSTEM_DIM_NO_SHADOW || this == Condition.SYSTEM_DIM_DIRECT

    private fun usesSystemDim() = condition == Condition.SYSTEM_DIM ||
        condition == Condition.SYSTEM_DIM_NO_WINDOW_CLIP || condition == Condition.SYSTEM_DIM_CORNER_MASK ||
        condition.isLayerComparison()

    private fun backend() = when (condition) {
        Condition.SYSTEM_DIM_CANVAS -> "canvas_no_surface"
        Condition.SYSTEM_DIM_DIRECT -> "direct_surface_control"
        else -> if (mode.waves) "wave_surface_view" else "surface_view"
    }

    private fun modeLabel() = if (condition == Condition.SYSTEM_DIM_CANVAS) "Canvas SDR" else mode.title

    private fun probeView(): View? = directProbe ?: canvasProbe ?: probe

    private fun captureCurrent() {
        val view = probeView() ?: return
        capture(view, generation, mode, directProbe?.copySurface ?: probe?.holder?.surface)
    }

    private fun usesWindowClip() = condition != Condition.NO_WINDOW_CLIP &&
        condition != Condition.NO_BOTH_CLIPS && condition != Condition.SYSTEM_DIM_NO_WINDOW_CLIP &&
        condition != Condition.SYSTEM_DIM_CORNER_MASK

    private data class WindowBaseline(
        val background: Drawable?, val panelBackground: Drawable?,
        val decorClip: Boolean, val panelClip: Boolean, val padding: IntArray,
        val decorOutline: ViewOutlineProvider?, val panelOutline: ViewOutlineProvider?,
        val format: Int, val dimEnabled: Boolean, val dimAmount: Float,
        val shadows: List<ShadowBaseline>
    )

    private data class ShadowBaseline(val view: View, val elevation: Float, val translationZ: Float)

    private enum class Mode(val title: String, val pq: Boolean, val opaque: Boolean, val front: Boolean,
                           val waves: Boolean = false) {
        SDR("1 · SDR", false, false, false),
        PQ("2 · 透明 PQ", true, false, false),
        OPAQUE("3 · 不透明 PQ", true, true, false),
        FRONT("4 · 前置 PQ", true, false, true),
        WAVE_SDR("5 · 海浪 SDR", false, false, false, true),
        WAVE_PQ("6 · 海浪 PQ", true, false, false, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG || !FableSolDisplayPolicy.usesPqCompatibility(
                Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.DISPLAY)) {
            finish()
            return
        }
        val prefs = getSharedPreferences("fablesol-hdr-composition-debug", MODE_PRIVATE)
        val diagnosticVersion = getString(R.string.debug_update_code)
        FableSolCompositionPerformance.prepareDiagnosticLogs(this)
        val id = savedInstanceState?.getString("journal") ?: prefs.getString("journal", null)
        val session = if (HdrDiagnosticJournal.validId(id)) id!! else HdrDiagnosticJournal.newId()
        journal = HdrDiagnosticJournal(File(filesDir, "fablesol-hdr-composition/$session"), 512L * 1024)
        if (prefs.getString("journal-version", null) != diagnosticVersion) journal.clear()
        prefs.edit().putString("journal", session).putString("journal-version", diagnosticVersion).apply()
        event("open", JSONObject().put("version", BuildConfig.VERSION_NAME)
            .put("probeRevision", 8)
            .put("code", BuildConfig.VERSION_CODE).put("api", Build.VERSION.SDK_INT)
            .put("debugUpdateCode", getString(R.string.debug_update_code))
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("display", Build.DISPLAY).put("hardware", Build.HARDWARE)
            .put("panelPixelsCaptured", false))
        intent.getStringExtra(SOURCE_WINDOW)?.let {
            event("original_tuning_window_before_navigation", JSONObject(it))
            intent.removeExtra(SOURCE_WINDOW)
        }
        window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
        setContentView(Backdrop(this))
        UiInteractions.attach(this)
        mode = Mode.entries.getOrElse(savedInstanceState?.getInt("mode") ?: Mode.PQ.ordinal) { Mode.PQ }
        appDialog = savedInstanceState?.getBoolean("appDialog") ?: true
        condition = Condition.entries.getOrElse(savedInstanceState?.getInt("condition")
            ?: Condition.SYSTEM_DIM.ordinal) { Condition.SYSTEM_DIM }
        if (condition.isLayerComparison()) mode = Mode.PQ
        prepared = savedInstanceState?.getBoolean("prepared") ?: false
        frozen = savedInstanceState?.getBoolean("frozen") ?: false
        if (appDialog) {
            if (supportFragmentManager.findFragmentByTag(REAL_DIALOG_TAG) == null) {
                RealDialog().showNow(supportFragmentManager, REAL_DIALOG_TAG)
            }
        } else {
            buildDialog()
            chooseMode(mode)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun label(value: String, size: Float = 14f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(40, 40, 40))
        setPadding(dp(12), dp(5), dp(12), dp(5))
    }

    private fun row(parent: LinearLayout, vararg actions: Pair<String, () -> Unit>) {
        val line = LinearLayout(this)
        actions.forEach { (title, action) ->
            line.addView(Button(this).apply {
                text = title
                textSize = 12f
                isAllCaps = false
                minWidth = 0
                setPadding(dp(2), 0, dp(2), 0)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        parent.addView(line)
    }

    private fun buildDialog() {
        dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(buildContent(this))
        dialog.setOnCancelListener { finish() }
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window!!.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(), dialogHeight())
            colorMode = android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            setDimAmount(0.35f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        bindComparisonWindow()
    }

    private fun dialogWidth() = minOf(dp(380), resources.displayMetrics.widthPixels - dp(24))
    private fun dialogHeight() = minOf(dp(760), (resources.displayMetrics.heightPixels * 0.92f).toInt())

    private fun buildContent(context: Context): View {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat() }
        }
        panel = outer
        windowBaseline = null
        outer.addView(label(if (appDialog) "HDR 对照 · 实际弹窗结构" else "HDR 对照 · 普通弹窗", 17f))
        preview = FrameLayout(context)
        val previewHeight = minOf(dp(200), maxOf(dp(120), dialogHeight() / 3))
        outer.addView(preview, LinearLayout.LayoutParams(-1, previewHeight))
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(ScrollView(context).apply { addView(root); isFillViewport = false },
            LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(label("左圈：普通控件白；固定图右圈：画面内白。\n观察是否能读出“后方文字”，不只比较背景颜色。", 12f))
        conditionLabel = label("")
        root.addView(conditionLabel)
        conditionSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item,
                Condition.entries.map { it.title }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(condition.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (parent !== conditionSpinner || !parent.isAttachedToWindow) return
                    val next = Condition.entries[position]
                    if (next != condition) chooseCondition(next)
                }
            }
        }
        root.addView(conditionSpinner, LinearLayout.LayoutParams(-1, dp(48)))
        row(root, "恢复 A 基准" to { chooseCondition(Condition.BASELINE) },
            "下一项对照" to { chooseCondition(Condition.entries[(condition.ordinal + 1) % Condition.entries.size]) })
        row(root, "C · 原裁剪" to { chooseCondition(Condition.SYSTEM_DIM) },
            "J · 关窗口裁剪" to { chooseCondition(Condition.SYSTEM_DIM_NO_WINDOW_CLIP) },
            "K · 显式清角" to { chooseCondition(Condition.SYSTEM_DIM_CORNER_MASK) })
        row(root, "L · 无 Surface" to { chooseCondition(Condition.SYSTEM_DIM_CANVAS) },
            "M · 去阴影" to { chooseCondition(Condition.SYSTEM_DIM_NO_SHADOW) },
            "N · 直接图层" to { chooseCondition(Condition.SYSTEM_DIM_DIRECT) })
        root.addView(label("本轮依次比较 C→L→M→N。L 没有 HDR，M／N 固定 PQ；进入或离开这三项会重建窗口。每项等就绪后记录黑角，再导出。", 12f))
        row(root, "普通弹窗" to { switchDialog(false) }, "实际弹窗结构" to { switchDialog(true) })
        explanation = label("")
        root.addView(explanation)
        row(root, Mode.SDR.title to { chooseMode(Mode.SDR) }, Mode.PQ.title to { chooseMode(Mode.PQ) })
        row(root, Mode.OPAQUE.title to { chooseMode(Mode.OPAQUE) }, Mode.FRONT.title to { chooseMode(Mode.FRONT) })
        row(root, Mode.WAVE_SDR.title to { chooseMode(Mode.WAVE_SDR) }, Mode.WAVE_PQ.title to { chooseMode(Mode.WAVE_PQ) })
        row(root, "准备态 / 录音态" to {
            prepared = !prepared
            applyWaveState()
        }, "冻结 / 继续海浪" to {
            frozen = !frozen
            applyWaveState()
        })
        root.addView(label("实际录音、附件和设置弹窗的系统暗层日志也随下方文件导出。", 12f))
        root.addView(label("海浪复用正式渲染器与已保存的特效参数，固定粉紫配色；不采集或保存音频。", 12f))
        observation = EditText(this).apply {
            hint = "可补充：左圈、右圈或白底偏色"
            textSize = 12f
            isSingleLine = true
            setTextColor(Color.BLACK)
            setPadding(dp(12), 0, dp(12), 0)
        }
        // 观感与导出常驻底部，逐项对照无需反复滚动去找记录按钮。
        outer.addView(observation, LinearLayout.LayoutParams(-1, dp(38)))
        row(outer, "正常" to { feedback("normal") }, "偏色" to { feedback("color_shift") },
            "穿透" to { feedback("see_through") }, "两者都有" to { feedback("both") })
        row(outer, "圆角正常" to { feedback("corners_normal") }, "仍有黑角" to { feedback("black_corners") })
        status = label("准备画面…", 12f).apply { minLines = 2; maxLines = 3 }
        outer.addView(status)
        row(outer, "保存日志到下载" to { export(false) }, "分享日志文件" to { export(true) })
        row(outer, "清空并重新记录" to { clearDiagnosticLogs() })
        return outer
    }

    private fun switchDialog(actual: Boolean, recreate: Boolean = false, nextCondition: Condition? = null) {
        if ((!recreate && actual == appDialog) || supportFragmentManager.isStateSaved) return
        event("window_recreate_begin", comparisonFields().put("oldWindow", System.identityHashCode(dialog.window))
            .put("actual", windowConditionState()))
        cornerMask?.close()
        cornerMask = null
        generation++
        conditionRevision++
        conditionReady = false
        wave = null
        probe = null
        canvasProbe = null
        directProbe?.close("window_recreate")
        directProbe = null
        preview.removeAllViews()
        if (appDialog) {
            supportFragmentManager.findFragmentByTag(REAL_DIALOG_TAG)?.let {
                supportFragmentManager.beginTransaction().remove(it).commitNow()
            }
        } else dialog.dismiss()
        if (nextCondition != null) {
            condition = nextCondition
            mode = Mode.PQ
        }
        appDialog = actual
        event("dialog_switch", JSONObject().put("appDialog", actual).put("recreated", recreate))
        if (actual) RealDialog().showNow(supportFragmentManager, REAL_DIALOG_TAG)
        else {
            buildDialog()
            chooseMode(mode)
        }
    }

    private fun bindComparisonWindow() {
        val target = dialog.window ?: return
        val decor = target.decorView
        windowShadowDisabled = false
        windowBaseline = WindowBaseline(decor.background, panel.background,
            decor.clipToOutline, panel.clipToOutline,
            intArrayOf(decor.paddingLeft, decor.paddingTop, decor.paddingRight, decor.paddingBottom),
            decor.outlineProvider, panel.outlineProvider,
            target.attributes.format,
            target.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0,
            target.attributes.dimAmount, shadowViews().map {
                ShadowBaseline(it, it.elevation, it.translationZ)
            })
        applyCondition("window_bound", initial = true)
    }

    private fun chooseCondition(next: Condition) {
        if (supportFragmentManager.isStateSaved) return
        val recreate = condition.isLayerComparison() || next.isLayerComparison()
        event("condition_selection", comparisonFields().put("nextCase", next.name)
            .put("recreateWindow", recreate))
        if (recreate) {
            switchDialog(appDialog, recreate = true, nextCondition = next)
            return
        }
        condition = next
        if (conditionSpinner.selectedItemPosition != next.ordinal) conditionSpinner.setSelection(next.ordinal)
        observation.text.clear()
        // 不修改正式渲染器的圆角参数；该额外对照明确限定在固定测试图。
        if (next == Condition.SQUARE_SURFACE && mode.waves) {
            chooseMode(Mode.PQ)
            Toast.makeText(this, "I 项已切换为模式 2 固定 PQ。", Toast.LENGTH_LONG).show()
        }
        applyCondition("user_selected")
    }

    private fun comparisonFields() = JSONObject().put("case", condition.name).put("label", condition.title)
        .put("backend", backend()).put("modeLabel", modeLabel())
        .put("effectiveMode", if (condition == Condition.SYSTEM_DIM_CANVAS) "CANVAS_SDR" else mode.name)
        .put("hdrRequested", condition != Condition.SYSTEM_DIM_CANVAS && mode.pq)
        .put("revision", conditionRevision).put("ready", conditionReady)
        .put("appDialog", appDialog).put("surfaceGeneration", generation)
        .put("surfacePresented", presentedGeneration == generation)
        .put("fixedShaderRounded", condition != Condition.SQUARE_SURFACE)

    /** 留住同一 Window / Surface / EGL；C→J→K 仅改变窗口裁剪，不重建海浪。 */
    private fun applyCondition(reason: String, initial: Boolean = false) {
        val revision = ++conditionRevision
        conditionReady = false
        conditionLabel.text = "${condition.title}：等待条件生效…\n${condition.description}"
        event("condition_requested", comparisonFields().put("reason", reason)
            .put("description", condition.description))
        fun apply(attempt: Int) {
            if (destroying || isDestroyed || revision != conditionRevision) return
            val target = dialog.window ?: return
            val baseline = windowBaseline ?: return
            val appAmount = if (condition == Condition.NO_DIM || usesSystemDim()) 0f else 0.6f
            if (appDialog && !DialogDimLayer.overrideForHdrDiagnostics(window.decorView, appAmount)) {
                if (attempt < 12) main.postDelayed({ apply(attempt + 1) }, 80)
                else {
                    conditionLabel.text = "${condition.title}：暗层未就绪，请重新选择此项或导出日志。"
                    event("condition_apply_failed", comparisonFields().put("reason", "app_dim_not_ready"))
                }
                return
            }
            val systemDim = when {
                usesSystemDim() -> true
                condition == Condition.NO_DIM -> false
                else -> baseline.dimEnabled
            }
            cornerMask?.close()
            cornerMask = null
            target.setDimAmount(if (usesSystemDim()) 0.6f else baseline.dimAmount)
            if (systemDim) target.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            else target.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            target.setBackgroundDrawable(if (condition == Condition.CLEAR_WINDOW)
                ColorDrawable(Color.TRANSPARENT) else baseline.background)
            // 替换 Drawable 不得顺带改变窗口格式或 padding，避免把几何变化混入背景对照。
            if (target.attributes.format != baseline.format) target.setFormat(baseline.format)
            val decor = target.decorView
            // setElevation 会触发窗口 Surface 重建；仅 M 设置一次，离开 M 必须重建窗口。
            // Window.getElevation 是非 SDK 方法，实际值通过公开的 Decor／祖先 View 属性核对。
            if (condition == Condition.SYSTEM_DIM_NO_SHADOW && !windowShadowDisabled) {
                target.setElevation(0f)
                windowShadowDisabled = true
            }
            baseline.shadows.forEach {
                it.view.elevation = if (condition == Condition.SYSTEM_DIM_NO_SHADOW) 0f else it.elevation
                it.view.translationZ = if (condition == Condition.SYSTEM_DIM_NO_SHADOW) 0f else it.translationZ
            }
            val expectedBackground = decor.background
            decor.setPadding(baseline.padding[0], baseline.padding[1], baseline.padding[2], baseline.padding[3])
            panel.background = if (condition == Condition.CLEAR_PANEL) null else baseline.panelBackground
            // 背景替换不应改变轮廓的来源，尤其透明背景仍须保留 A 的自定义圆角形状。
            decor.outlineProvider = baseline.decorOutline
            panel.outlineProvider = baseline.panelOutline
            decor.clipToOutline = baseline.decorClip && usesWindowClip()
            panel.clipToOutline = baseline.panelClip && condition != Condition.NO_PANEL_CLIP && condition != Condition.NO_BOTH_CLIPS
            if (condition == Condition.SYSTEM_DIM_CORNER_MASK) cornerMask = FableSolDialogCornerMask(target)
            (probe as? CompositionSurface)?.setSquareCorners(condition == Condition.SQUARE_SURFACE)
            event("condition_applied", comparisonFields().put("actual", windowConditionState()))
            // 系统 dim 的提交可能有过渡；稳定后再接受观感，切换期间仍可导出日志。
            main.postDelayed({
                if (revision != conditionRevision || isDestroyed || !dialog.isShowing) return@postDelayed
                val appCoverage = DialogDimLayer.coverage(window.decorView)
                val valid = (!appDialog || kotlin.math.abs(appCoverage - appAmount) < 0.02f) &&
                    (target.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) == systemDim &&
                    (!systemDim || kotlin.math.abs(target.attributes.dimAmount -
                        (if (usesSystemDim()) 0.6f else baseline.dimAmount)) < 0.02f) &&
                    decor.background === expectedBackground &&
                    panel.background === (if (condition == Condition.CLEAR_PANEL) null else baseline.panelBackground) &&
                    decor.outlineProvider === baseline.decorOutline && panel.outlineProvider === baseline.panelOutline &&
                    target.attributes.format == baseline.format &&
                    decor.clipToOutline == (baseline.decorClip && usesWindowClip()) &&
                    panel.clipToOutline == (baseline.panelClip && condition != Condition.NO_PANEL_CLIP && condition != Condition.NO_BOTH_CLIPS) &&
                    (condition != Condition.SYSTEM_DIM_NO_SHADOW || (windowShadowDisabled &&
                        baseline.shadows.all { it.view.elevation == 0f && it.view.translationZ == 0f }))
                conditionReady = valid
                conditionLabel.text = "${condition.title} · ${if (valid) "已生效，可记录观感" else "状态不符，请导出日志"}\n${condition.description}"
                event("condition_settled", comparisonFields().put("actual", windowConditionState()))
                val sampledComparison = comparisonFields()
                FableSolDialogCornerMask.sampleCornerAlpha(target) { result ->
                    if (!destroying) event("window_corner_buffer_alpha", JSONObject()
                        .put("comparison", sampledComparison).put("result", result)
                        .put("stillCurrent", revision == conditionRevision))
                }
                captureCurrent()
            }, 700)
        }
        // 新建弹窗先让旧暗层正常淡出、当前暗层完成布局，随后只覆盖唯一的当前层。
        if (initial) main.postDelayed({ apply(0) }, 600) else apply(0)
    }

    private fun windowConditionState(): JSONObject {
        val target = dialog.window
        val decor = target?.decorView
        val location = IntArray(2).also { preview.getLocationInWindow(it) }
        return JSONObject().put("windowBackground", drawableFields(decor?.background))
            .put("panelBackground", drawableFields(panel.background))
            .put("windowClip", decor?.clipToOutline).put("panelClip", panel.clipToOutline)
            .put("explicitWindowCornerMask", cornerMask != null)
            .put("windowOutline", outlineFields(decor)).put("panelOutline", outlineFields(panel))
            .put("systemDimEnabled", (target?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
            .put("systemDimAmount", target?.attributes?.dimAmount)
            .put("appDimCoverage", DialogDimLayer.coverage(window.decorView))
            .put("windowFormat", target?.attributes?.format)
            .put("windowIdentity", System.identityHashCode(target))
            .put("backend", backend()).put("windowElevationZeroRequested", windowShadowDisabled)
            .put("decorElevation", decor?.elevation)
            .put("shadowViews", JSONArray(shadowViews().map { JSONObject()
                .put("class", it.javaClass.simpleName).put("identity", System.identityHashCode(it))
                .put("elevation", it.elevation).put("translationZ", it.translationZ) }))
            .put("surfaceViewCount", countSurfaceViews(decor))
            .put("directControl", directProbe?.diagnostics())
            .put("surfaceIdentity", probe?.let { System.identityHashCode(it) })
            .put("fixedSquareCornersPresented", (probe as? CompositionSurface)?.presentedSquareCorners)
            .put("fixedEglSessionCount", (probe as? CompositionSurface)?.eglSessionCount)
            .put("windowWidth", decor?.width).put("windowHeight", decor?.height)
            .put("previewX", location[0]).put("previewY", location[1])
            .put("previewWidth", preview.width).put("previewHeight", preview.height)
    }

    private fun shadowViews(): List<View> {
        val views = mutableListOf<View>()
        var node: View? = panel
        while (node != null) {
            views.add(node)
            node = node.parent as? View
        }
        return views
    }

    private fun countSurfaceViews(view: View?): Int = when (view) {
        is SurfaceView -> 1
        is ViewGroup -> (0 until view.childCount).sumOf { countSurfaceViews(view.getChildAt(it)) }
        else -> 0
    }

    private fun drawableFields(drawable: Drawable?): JSONObject = JSONObject()
        .put("class", drawable?.javaClass?.simpleName ?: "none").put("alpha", drawable?.alpha)
        .put("opacity", drawable?.opacity)
        .apply { if (drawable is ColorDrawable) put("color", String.format("#%08X", drawable.color)) }

    private fun outlineFields(view: View?): JSONObject {
        val outline = Outline()
        if (view != null) view.outlineProvider?.getOutline(view, outline)
        val bounds = Rect()
        val rectangular = outline.getRect(bounds)
        return JSONObject().put("provider", view?.outlineProvider?.javaClass?.name ?: "none")
            .put("canClip", outline.canClip()).put("empty", outline.isEmpty)
            .put("radius", outline.radius).put("rectangular", rectangular)
            .put("bounds", if (rectangular) bounds.toShortString() else "unavailable")
    }

    /** 使用真实公共窗口、裁剪和应用暗层；关闭粒子以单独比较稳定态。 */
    class RealDialog : BaseDialogFragment() {
        private val host get() = requireActivity() as FableSolHdrCompositionActivity
        private var configured = false
        override fun getLayoutResource() = 0
        override fun useParticleDismiss() = false
        override fun getDialogWindowWidthPx() = host.dialogWidth()
        override fun createDialogContent(inflater: LayoutInflater, container: ViewGroup?) = host.buildContent(inflater.context)
        override fun onStart() {
            super.onStart()
            host.dialog = requireDialog()
            requireDialog().setCanceledOnTouchOutside(false)
            requireDialog().window?.setLayout(host.dialogWidth(), host.dialogHeight())
            if (!configured) {
                configured = true
                host.bindComparisonWindow()
                host.chooseMode(host.mode)
            } else host.applyCondition("window_restarted", initial = true)
        }
        override fun onDestroyView() { configured = false; super.onDestroyView() }
        override fun onCancel(dialog: android.content.DialogInterface) {
            super.onCancel(dialog)
            if (!host.destroying) host.finish()
        }
    }

    private fun chooseMode(next: Mode) {
        if (condition.isLayerComparison() && next != Mode.PQ) {
            Toast.makeText(this, "L／M／N 使用固定对照画面；请选择 C 后再切换模式。", Toast.LENGTH_SHORT).show()
            return
        }
        if (next.waves && condition == Condition.SQUARE_SURFACE) {
            chooseCondition(Condition.BASELINE)
            Toast.makeText(this, "画面圆角单项仅用于固定图；海浪已恢复 A 配置。", Toast.LENGTH_LONG).show()
        }
        mode = next
        observation.text.clear()
        val token = ++generation
        presentedGeneration = -1
        directProbe?.close("mode_changed")
        directProbe = null
        canvasProbe = null
        preview.removeAllViews()
        probe = null
        wave = null
        explanation.text = when (next) {
            Mode.SDR -> "1：SDR 基准。"
            Mode.PQ -> "2：圆角透明 Surface，位于普通控件下方。"
            Mode.OPAQUE -> "3：仅改为声明不透明。模式 1～3 都曾出现黑角，分别记录。"
            Mode.FRONT -> "4：仅改为前置图层；左侧普通白圈被遮住是预期现象。"
            Mode.WAVE_SDR -> "5：真实海浪，关闭 HDR；右侧不绘制固定图白圈。"
            Mode.WAVE_PQ -> "6：真实海浪，PQ HDR；右侧不绘制固定图白圈。"
        }
        if (condition == Condition.SYSTEM_DIM_CANVAS) explanation.text = "L：普通 Canvas 静态 SDR 图；本窗口没有独立 Surface，也没有 EGL。"
        if (condition == Condition.SYSTEM_DIM_DIRECT) explanation.text = "N：直接图层输出相同 PQ 固定图。左白圈若被覆盖请备注；不能只凭图层创建成功判断屏幕正确。"
        status.text = "${modeLabel()}：等待绘制…"
        event("select", JSONObject().put("mode", next.name).put("generation", token)
            .put("appDialog", appDialog).put("waveRenderer", next.waves)
            .put("comparison", comparisonFields())
            .put("prepared", prepared).put("frozen", frozen)
            .put("backend", backend()).put("holderUsed", !condition.isLayerComparison() || condition == Condition.SYSTEM_DIM_NO_SHADOW)
            .put("requestedHolderFormat", if (condition == Condition.SYSTEM_DIM_CANVAS) "none" else if (next.opaque) "RGBX_8888" else "RGBA_8888")
            .put("onTop", next.front).put("headroomApiAvailable", Build.VERSION.SDK_INT >= 35)
            .put("requestedSurfaceHeadroom", if (next.pq) 0 else 1))
        if (condition == Condition.SYSTEM_DIM_CANVAS) {
            val view = FableSolCompositionCanvasView(preview.context) {
                if (generation == token && !isDestroyed) {
                    presentedGeneration = token
                    status.text = "L：Canvas SDR 已绘制，无独立 Surface；请记录黑角。"
                    event("canvas_presented", comparisonFields().put("windowCondition", windowConditionState()))
                    main.postDelayed({ if (generation == token) captureCurrent() }, 350)
                }
            }
            canvasProbe = view
            preview.addView(view, FrameLayout.LayoutParams(-1, -1))
        } else if (condition == Condition.SYSTEM_DIM_DIRECT) {
            val view = FableSolCompositionControlView(preview.context, dp(18).toFloat(),
                comparison = { comparisonFields() },
                log = { name, fields -> event(name, fields.put("generation", token)
                    .put("case", Condition.SYSTEM_DIM_DIRECT.name).put("backend", "direct_surface_control")) },
                report = { ready, detail ->
                    if (generation == token && !isDestroyed) {
                        presentedGeneration = if (ready) token else -1
                        status.text = detail
                        if (ready) main.postDelayed({ if (generation == token) captureCurrent() }, 350)
                    }
                })
            directProbe = view
            preview.addView(view, FrameLayout.LayoutParams(-1, -1))
        } else if (next.waves) {
            val host = WaveVisualizerFableSolHost.forSurfaceViewComparison(preview.context)
            wave = host
            host.setThingBackground(ThingBackground.gradient(Color.rgb(232, 55, 102),
                Color.rgb(121, 83, 207), ThingBackground.Orientation.L_R))
            val strength = if (next.pq) FableSolTuning.hdrStrength(this).takeIf { it > 1f } ?: 3.6f else 1f
            host.setHdrStrength(strength)
            event("wave_strength", JSONObject().put("generation", token).put("requested", strength))
            host.setPresentationAlpha(if (prepared) 0.16f else 1f)
            host.setRecordingHdrActive(next.pq && !prepared)
            host.setFrozen(frozen)
            val gl = host.getChildAt(0) as WaveVisualizerFableSolGl
            probe = gl
            host.onGlFallback = {
                event("wave_fallback", JSONObject().put("generation", token))
                if (generation == token) status.text = "海浪已回退 Canvas，不能作为 PQ 验证；请导出日志。"
            }
            gl.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    event("wave_surface_created", JSONObject().put("generation", token)
                        .put("comparison", comparisonFields()))
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    event("wave_surface_destroyed", JSONObject().put("generation", token)
                        .put("comparison", comparisonFields()))
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    event("wave_holder", JSONObject().put("generation", token).put("format", format)
                        .put("width", width).put("height", height))
                    // 在正式宿主已绑定会话后登记；冻结态也会由 onNextPresented 请求补一帧。
                    gl.post {
                        if (generation == token && gl.holder.surface.isValid) gl.onNextPresented {
                            if (generation == token) {
                                presentedGeneration = token
                                showWaveState()
                                main.postDelayed({ capture(gl, token, next) }, 1000)
                            }
                        }
                    }
                }
            })
            preview.addView(host, FrameLayout.LayoutParams(-1, -1))
            // 创建失败或回退时也保留窗口状态；这项不声称 EGL 首帧已经提交。
            main.postDelayed({ capture(gl, token, next) }, 1800)
        } else {
            val surface = CompositionSurface(preview.context, next, token) { detail ->
                if (generation == token && !isDestroyed) status.text = detail
            }
            probe = surface
            preview.addView(surface, FrameLayout.LayoutParams(-1, -1))
        }
        // 故意使用与真实录音按钮相同的原生 SDR 白，和同一 PQ Surface 内的白圈比较。
        preview.addView(object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            override fun onDraw(canvas: Canvas) {
                canvas.drawCircle(width * 0.25f, height * 0.75f, width * 0.075f, paint)
            }
        }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showWaveState() {
        status.text = "${mode.title} · ${if (prepared) "准备态" else "录音态呈现"} · ${if (frozen) "已冻结" else "持续更新"}\n只运行画面，不采集音频。"
    }

    private fun applyWaveState() {
        val host = wave ?: run {
            status.text = "请先选择模式 5 或 6。"
            return
        }
        host.setPresentationAlpha(if (prepared) 0.16f else 1f)
        host.setRecordingHdrActive(mode.pq && !prepared)
        host.setFrozen(frozen)
        // 冻结时也通过正式首帧回调请求呈现新状态，不把旧缓冲误当作已切换。
        val token = generation
        (probe as? WaveVisualizerFableSolGl)?.onNextPresented {
            if (token == generation && !isDestroyed) showWaveState()
        }
        event("wave_state", JSONObject().put("generation", generation)
            .put("prepared", prepared).put("frozen", frozen))
    }

    private fun feedback(result: String) {
        val fixed = probe as? CompositionSurface
        val validProbe = when (condition) {
            Condition.SYSTEM_DIM_CANVAS -> canvasProbe?.isAttachedToWindow == true && countSurfaceViews(dialog.window?.decorView) == 0
            Condition.SYSTEM_DIM_DIRECT -> directProbe?.ready == true && countSurfaceViews(dialog.window?.decorView) == 0
            else -> probe?.holder?.surface?.isValid == true && (fixed == null || !mode.pq || fixed.verifiedOutput == FableSolScreenOutput.PQ)
        }
        if (!conditionReady || presentedGeneration != generation || !validProbe ||
            (fixed != null && fixed.presentedSquareCorners != (condition == Condition.SQUARE_SURFACE))) {
            Toast.makeText(this, "条件或画面尚未就绪，请稍候；仍可导出日志。", Toast.LENGTH_SHORT).show()
            event("feedback_not_ready", comparisonFields().put("result", result))
            return
        }
        event("visual_feedback", JSONObject().put("mode", mode.name).put("generation", generation)
            .put("appDialog", appDialog).put("prepared", prepared).put("frozen", frozen)
            .put("comparison", comparisonFields()).put("windowCondition", windowConditionState())
            .put("result", result).put("note", observation.text.toString()))
        captureCurrent()
        status.text = "${condition.title} / ${modeLabel()}：已记录${when (result) {
            "normal" -> "正常"
            "color_shift" -> "偏色"
            "see_through" -> "穿透"
            "corners_normal" -> "圆角正常"
            "black_corners" -> "仍有黑角"
            else -> "偏色与穿透"
        }}"
        Toast.makeText(this, "已记录 ${condition.title} / ${modeLabel()} 的观感", Toast.LENGTH_SHORT).show()
    }

    private fun event(name: String, fields: JSONObject) {
        journal.event("[DEBUG-fablesol-composition] $name", fields.toString(2))
    }

    private fun capture(surface: View, token: Int, selected: Mode,
                        source: android.view.Surface? = (surface as? SurfaceView)?.holder?.surface) {
        if (isDestroyed || token != generation || !surface.isAttachedToWindow || (source != null && !source.isValid)) return
        val capturedRevision = conditionRevision
        val comparison = comparisonFields()
        event("view_state", JSONObject().put("mode", selected.name).put("generation", token)
            .put("comparison", comparison).put("windowCondition", windowConditionState())
            .put("surfaceAlpha", surface.alpha).put("previewAlpha", preview.alpha)
            .put("windowAlpha", dialog.window?.attributes?.alpha)
            .put("decorAlpha", dialog.window?.decorView?.alpha)
            .put("appDialog", appDialog).put("windowClass", dialog.javaClass.name)
            .put("windowFlags", dialog.window?.attributes?.flags)
            .put("windowFormat", dialog.window?.attributes?.format)
            .put("windowColorMode", dialog.window?.colorMode)
            .put("windowDim", dialog.window?.attributes?.dimAmount)
            .put("appDimCoverage", DialogDimLayer.coverage(window.decorView))
            .put("prepared", prepared).put("frozen", frozen)
            .put("waveGlActive", wave?.isGlActive())
            .put("wavePqNotRejected", wave?.compatibilityHdrSupported())
            .put("ancestors", ancestors(surface))
            .put("reportedHdrSdrRatio", runCatching { surface.display?.hdrSdrRatio.toString() }.getOrDefault("unavailable")))
        fun copy(windowCopy: Boolean) {
            val targetWindow = dialog.window ?: return
            val width = if (windowCopy) targetWindow.decorView.width else surface.width
            val height = if (windowCopy) targetWindow.decorView.height else surface.height
            if (width < 1 || height < 1) return
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val xy = IntArray(2)
            if (windowCopy) surface.getLocationInWindow(xy)
            val callback = PixelCopy.OnPixelCopyFinishedListener { code ->
                try {
                    val report = JSONObject().put("mode", selected.name).put("generation", token)
                        .put("kind", if (windowCopy) "window_without_child_surfaces" else "surface_sdr_copy")
                        .put("rightSampleIsWhiteReference", !selected.waves)
                        .put("status", code).put("stillCurrent", token == generation && capturedRevision == conditionRevision)
                        .put("comparison", comparison)
                    if (code == PixelCopy.SUCCESS && token == generation && capturedRevision == conditionRevision) {
                        val samples = JSONObject()
                        listOf("left_native_position" to 0.25f, "right_surface_white" to 0.75f).forEach { (label, x) ->
                            val px = (xy[0] + surface.width * x).toInt().coerceIn(0, width - 1)
                            val py = (xy[1] + surface.height * 0.75f).toInt().coerceIn(0, height - 1)
                            samples.put(label, String.format("#%08X", bitmap.getPixel(px, py)))
                        }
                        report.put("samples", samples)
                    }
                    event("pixel_copy", report)
                } finally { bitmap.recycle() }
            }
            try {
                if (windowCopy) PixelCopy.request(targetWindow, bitmap, callback, main)
                else PixelCopy.request(checkNotNull(source), bitmap, callback, main)
            } catch (error: Exception) {
                bitmap.recycle()
                event("pixel_copy_error", JSONObject().put("generation", token).put("message", error.toString()))
            }
        }
        if (source != null) copy(false)
        else event("surface_copy_skipped", JSONObject().put("comparison", comparison)
            .put("reason", if (condition == Condition.SYSTEM_DIM_CANVAS) "canvas_has_no_surface" else "surface_unavailable"))
        copy(true)
    }

    private fun export(share: Boolean) {
        if (exportBusy) return
        exportBusy = true
        event("export", JSONObject().put("share", share).put("mode", mode.name)
            .put("comparison", comparisonFields()).put("windowCondition", windowConditionState())
            .put("note", observation.text.toString()))
        FableSolCompositionPerformance.flushAll { exportAfterPerformanceFlush(share) }
    }

    private fun exportAfterPerformanceFlush(share: Boolean) {
        com.ywwynm.everythingdone.helpers.DebugFileLogger.afterPendingWrites {
            val attachments = mutableListOf<Pair<String, String>>()
            // 仅合并已存在的数值型 HDR 日志；不附带记事、音频或截图。
            try {
                for ((fileName, eventName) in listOf("fablesol_hdr.log" to "production_hdr_log",
                    "fablesol_dialog_dim.log" to "production_dialog_dim_log",
                    "fablesol_direct.log" to "production_direct_log",
                    "fablesol_frame_perf.log" to "production_frame_perf_log",
                    "fablesol_composition_perf.log.old" to "production_composition_perf_previous_log",
                    "fablesol_composition_perf.log" to "production_composition_perf_log")) {
                    val source = File(getExternalFilesDir(null) ?: filesDir, "debug_logs/$fileName")
                    if (source.isFile) RandomAccessFile(source, "r").use { input ->
                        val length = input.length()
                        val limit = if (fileName.startsWith("fablesol_composition_perf")) 1048576L else 524288L
                        val start = maxOf(0L, length - limit)
                        input.seek(start)
                        val data = ByteArray((length - start).toInt())
                        input.readFully(data)
                        val raw = data.toString(Charsets.UTF_8)
                        val fields = JSONObject().put("scope", "current_version_recent_dialogs")
                            .put("debugUpdateCode", getString(R.string.debug_update_code))
                            .put("pendingWritesFlushed", true)
                            .put("truncated", start > 0).put("text", if (start > 0) raw.substringAfter('\n') else raw)
                        attachments += "[DEBUG-fablesol-composition] $eventName" to fields.toString(2)
                    }
                }
            } catch (error: Exception) {
                attachments += "[DEBUG-fablesol-composition] production_log_error" to
                    JSONObject().put("message", error.toString()).toString(2)
            }
            try {
                val file = journal.snapshot(File(cacheDir, "hdr-exports/fablesol-composition-${System.currentTimeMillis()}.txt"), attachments)
                if (share) {
                    val uri = FileProvider.getUriForFile(this, packageName, file)
                    main.post {
                        if (!isDestroyed) {
                            try {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "分享诊断日志文件"))
                            } catch (error: Exception) { status.text = "分享失败：${error.message}" }
                        }
                    }
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/EverythingDone")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = checkNotNull(contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                    try {
                        checkNotNull(contentResolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        check(contentResolver.update(uri, values, null, null) > 0)
                    } catch (error: Exception) {
                        contentResolver.delete(uri, null, null)
                        throw error
                    }
                    main.post { if (!isDestroyed) status.text = "已保存：下载/EverythingDone/${file.name}" }
                }
            } catch (error: Exception) {
                main.post { if (!isDestroyed) status.text = "导出失败：${error.message}" }
            } finally { main.post { exportBusy = false } }
        }
    }

    private fun clearDiagnosticLogs() {
        if (exportBusy) return
        exportBusy = true
        FableSolCompositionPerformance.flushAll {
            com.ywwynm.everythingdone.helpers.DebugFileLogger.afterPendingWrites {
                val result = runCatching {
                    FableSolDiagnosticLogFiles(File(getExternalFilesDir(null) ?: filesDir, "debug_logs"))
                        .clear(getString(R.string.debug_update_code))
                    journal.clear()
                    event("logs_reset", JSONObject().put("debugUpdateCode", getString(R.string.debug_update_code))
                        .put("reason", "user_requested").put("exportCopiesPreserved", true))
                }
                main.post {
                    exportBusy = false
                    if (!isDestroyed) status.text = if (result.isSuccess)
                        "日志已清空。重新打开录音或音频弹窗后即可测试并导出。"
                    else "清空失败：${result.exceptionOrNull()?.message}"
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::journal.isInitialized) outState.putString("journal", journal.directory.name)
        outState.putInt("mode", mode.ordinal)
        outState.putBoolean("appDialog", appDialog)
        outState.putBoolean("prepared", prepared)
        outState.putBoolean("frozen", frozen)
        outState.putInt("condition", condition.ordinal)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); UiInteractions.resumed(this) }
    override fun onPause() { UiInteractions.paused(this); super.onPause() }

    override fun onDestroy() {
        destroying = true
        directProbe?.close("activity_destroyed")
        directProbe = null
        cornerMask?.close()
        cornerMask = null
        generation++
        main.removeCallbacksAndMessages(null)
        if (!appDialog && ::dialog.isInitialized) dialog.dismiss()
        super.onDestroy()
        UiInteractions.destroyed(this)
    }

    private inner class CompositionSurface(context: Context, private val selected: Mode,
                                           private val token: Int, private val report: (String) -> Unit) :
        SurfaceView(context), SurfaceHolder.Callback {
        private var worker: HandlerThread? = null
        private var render: Handler? = null
        private var renderer: FableSolCompositionRenderer? = null
        @Volatile var verifiedOutput: FableSolScreenOutput? = null
            private set
        @Volatile private var squareCorners = condition == Condition.SQUARE_SURFACE
        @Volatile var presentedSquareCorners: Boolean? = null
            private set
        @Volatile var eglSessionCount = 0
            private set
        private var bufferWidth = 0
        private var bufferHeight = 0

        /** 只重绘现有缓冲；不重新创建 Surface 或 EGL。 */
        fun setSquareCorners(square: Boolean) {
            if (square == squareCorners) return
            squareCorners = square
            val comparison = comparisonFields()
            render?.post {
                if (renderer == null || bufferWidth < 1 || bufferHeight < 1) return@post
                try {
                    renderer!!.draw(bufferWidth, bufferHeight, comparison, square)
                    presentedSquareCorners = square
                }
                catch (error: Throwable) { failed(error) }
            }
        }

        init {
            setZOrderOnTop(selected.front)
            if (Build.VERSION.SDK_INT >= 35) {
                runCatching { setDesiredHdrHeadroom(if (selected.pq) 0f else 1f) }
                    .onFailure { event("headroom_error", JSONObject().put("generation", token).put("message", it.toString())) }
            }
            // RGBX 仅改变 SurfaceView 的不透明声明；实际 EGL 仍复用生产 RGBA8 配置。
            holder.setFormat(if (selected.opaque) PixelFormat.RGBX_8888 else PixelFormat.RGBA_8888)
            holder.addCallback(this)
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            worker = HandlerThread("FableSolComposition-$token").also { it.start() }
            render = Handler(worker!!.looper).also { handler ->
                handler.post {
                    try {
                        eglSessionCount++
                        renderer = FableSolCompositionRenderer(assets, holder.surface, selected.pq, dp(18).toFloat()) { name, fields ->
                            event(name, fields.put("mode", selected.name).put("generation", token)
                                .put("backend", "surface_view").put("sessionCount", eglSessionCount))
                        }
                        verifiedOutput = renderer!!.output
                    } catch (error: Throwable) { failed(error) }
                }
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            val comparison = comparisonFields()
            val square = squareCorners
            render?.post {
                if (renderer == null || width < 1 || height < 1) return@post
                try {
                    event("holder", JSONObject().put("mode", selected.name).put("generation", token)
                        .put("callbackFormat", format).put("width", width).put("height", height))
                    bufferWidth = width
                    bufferHeight = height
                    renderer!!.draw(width, height, comparison, square)
                    presentedSquareCorners = square
                    main.post {
                        if (generation == token) presentedGeneration = token
                        report(if (selected.pq && verifiedOutput != FableSolScreenOutput.PQ)
                            "PQ 创建失败，当前为 SDR；请导出日志。" else "${selected.title} 已绘制；请记录观感。")
                        main.postDelayed({ capture(this, token, selected) }, 350)
                    }
                } catch (error: Throwable) { failed(error) }
            }
        }


        private fun failed(error: Throwable) {
            event("render_error", JSONObject().put("mode", selected.name).put("generation", token).put("message", error.toString()))
            main.post { report("绘制失败：${error.message}；仍可导出日志。") }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            presentedSquareCorners = null
            if (generation == token) presentedGeneration = -1
            event("fixed_surface_destroyed", JSONObject().put("generation", token)
                .put("comparison", comparisonFields()).put("sessionCount", eglSessionCount))
            val done = CountDownLatch(1)
            val thread = worker
            render?.post {
                try {
                    renderer?.close()
                    renderer = null
                    verifiedOutput = null
                } finally { done.countDown(); thread?.quitSafely() }
            } ?: done.countDown()
            if (!done.await(2, TimeUnit.SECONDS)) event("release_timeout", JSONObject().put("generation", token))
            render = null
            worker = null
        }
    }

    private class Backdrop(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val row = 70f * resources.displayMetrics.density
            var y = 0f
            var index = 0
            while (y < height) {
                paint.color = if (index % 2 == 0) Color.rgb(245, 174, 186) else Color.rgb(151, 190, 234)
                canvas.drawRect(0f, y, width.toFloat(), y + row, paint)
                paint.color = Color.rgb(25, 25, 25)
                paint.textSize = 22f * resources.displayMetrics.scaledDensity
                canvas.drawText("后方文字 ABC 0123　后方文字", 12f, y + row * 0.65f, paint)
                y += row
                index++
            }
        }
    }

    companion object {
        private const val REAL_DIALOG_TAG = "fablesol-composition-real-dialog"
        private const val SOURCE_WINDOW = "fablesol.original.window"

        /** 长按入口调用时窗口仍可见；只记录结构和状态，不采集真实记事内容。 */
        @JvmStatic
        fun openFrom(activity: Activity, source: Window, content: View) {
            val host = content as? WaveVisualizerFableSolHost
            val surface = host?.getChildAt(0) as? SurfaceView
            val state = JSONObject().put("owner", "FableSolTuningDialogFragment")
                .put("windowAlpha", source.attributes.alpha).put("windowFlags", source.attributes.flags)
                .put("windowFormat", source.attributes.format).put("windowColorMode", source.colorMode)
                .put("windowDim", source.attributes.dimAmount).put("decorAlpha", source.decorView.alpha)
                .put("appDimCoverage", DialogDimLayer.coverage(activity.window.decorView))
                .put("savedHdrStrength", FableSolTuning.hdrStrength(activity))
                .put("waveGlActive", host?.isGlActive()).put("wavePqNotRejected", host?.compatibilityHdrSupported())
                .put("ancestors", ancestors(surface ?: content))
            activity.startActivity(Intent(activity, FableSolHdrCompositionActivity::class.java)
                .putExtra(SOURCE_WINDOW, state.toString()))
        }

        private fun ancestors(surface: View): JSONArray {
            val result = JSONArray()
            var node: View? = surface
            while (node != null) {
                val view = node
                val xy = IntArray(2).also { view.getLocationInWindow(it) }
                result.put(JSONObject().put("class", view.javaClass.simpleName).put("alpha", view.alpha)
                    .put("width", view.width).put("height", view.height).put("clipToOutline", view.clipToOutline)
                    .put("layerType", view.layerType).put("elevation", view.elevation)
                    .put("xInWindow", xy[0]).put("yInWindow", xy[1]).put("visibility", view.visibility))
                node = view.parent as? View
            }
            return result
        }
    }
}
