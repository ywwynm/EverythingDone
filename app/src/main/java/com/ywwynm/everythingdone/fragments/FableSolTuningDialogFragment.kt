@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.DialogInterface
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.EverythingDoneBaseActivity
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import com.ywwynm.everythingdone.views.recording.AudioRecorder
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolFrontEndTuning
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolHdrExportCapability
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolHdrPolicy
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolParams
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportOptions
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolTuning
import com.ywwynm.everythingdone.views.recording.fablesol.WaveVisualizerFableSolGl
import com.ywwynm.everythingdone.views.recording.fablesol.WaveVisualizerFableSolHost

import java.util.Locale
import java.util.Random
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 设置界面"音频海浪动画参数调节"Dialog：顶部固定一块与录音界面完全一致的 FableSol
 * 实时预览（AudioRecorder 实时驱动、重力倾斜、HDR；贴顶满宽、可暂停冻结、可换色），
 * 下方可滚动列出全部在 Android 端实际生效的特效参数（目录见 [FableSolTuning.GROUPS]）
 * 与 HDR 开关。
 *
 * 调节即时生效（渲染参数经渲染线程 drain 写入共享 params，声音分析参数经线程安全快照在
 * 下一批 PCM 生效），松手后持久化；持久化覆盖由各渲染器/AudioRecorder 构造时套用，
 * 因此录音界面下次打开同样生效。换色时水体走 GL 端"新色从
 * 右缘涌入"的过渡，Dialog 里全部强调色元素同步渐变跟随。调用方须先确保
 * RECORD_AUDIO 权限已授予（SettingsActivity 入口负责）。
 */
class FableSolTuningDialogFragment : BaseDialogFragment() {

    private var mActivity: EverythingDoneBaseActivity? = null

    private var mRecorder: AudioRecorder? = null
    private var mVisualizer: WaveVisualizerFableSolHost? = null

    /** 全部默认值来源：一个未套用任何覆盖的 params 实例。 */
    private val mDefaults = FableSolParams()

    /** 当前 UI 实际展示的强调背景（换色动画每步更新，供重建行/接力取色）。 */
    private var mAppliedBackground: ThingBackground = App.defaultAccentBackground
    /** 最近一次落定（或正过渡向）的目标背景。 */
    private var mCurrentBackground: ThingBackground = App.defaultAccentBackground
    private var mPreviewPaused = false
    private val mColorPool = ArrayList<ThingBackground>()
    private var mColorAnimator: ValueAnimator? = null
    private val mArgbEvaluator = ArgbEvaluator()
    private val mRandom = Random()

    // 换色动画需要跟随的强调色元素；参数区的随 buildParamRows 重建重收。
    private val mAccentHeaders = ArrayList<TextView>()
    private val mAccentSeekBars = ArrayList<SeekBar>()
    private val mAccentCheckBoxes = ArrayList<CompoundButton>()
    /** 档位标签的重绘回调；换色时与其余控件一起刷新。 */
    private val mAccentChipPainters = ArrayList<() -> Unit>()
    private val mAccentRippleRows = ArrayList<View>()
    // HDR 强度行固定在布局里（index 0），不随 buildParamRows 重建，单独跟色。
    private var mHdrSeekBar: SeekBar? = null

    private var mSensorManager: SensorManager? = null
    private var mGravitySensor: Sensor? = null
    private var mSensorThread: HandlerThread? = null
    private var mTiltSensorRegistered = false
    private var mOriginalRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var mOrientationLocked = false
    private var mLockedRotation = Surface.ROTATION_0
    /** 让已经离开 Dialog 的后台 HDR 探测结果失效，不再回写旧 View。 */
    private var mHdrCapabilityGeneration = 0

    override fun getLayoutResource(): Int = R.layout.dialog_fablesol_tuning

    override fun getDialogWindowWidthPx(): Int =
        min(dp(320f), (resources.displayMetrics.widthPixels * 0.94f).toInt())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = super.onCreateView(inflater, container, savedInstanceState)
        mActivity = activity as EverythingDoneBaseActivity
        lockHostOrientation()
        prepareTiltSensor()

        mVisualizer = f(R.id.fablesol_tuning_preview)
        mVisualizer!!.setThingBackground(mCurrentBackground)
        // 预览常驻录制态呈现：完整不透明 + 按设置激活 HDR；取景整体上移让第 0 层
        // 波谷不被下边缘遮挡；底部两角切直角与下方参数区无缝衔接。
        mVisualizer!!.setPresentationAlpha(1.0f)
        mVisualizer!!.setContentVerticalOffsetDp(PREVIEW_CONTENT_OFFSET_DP)
        mVisualizer!!.setBottomCornerRadiusPx(0f)
        applyHdrPreference()

        setupPreviewButtons()
        setupHdrRow()
        buildParamRows()
        setupScrollIndicators()

        f<TextView>(R.id.tv_fablesol_tuning_reset_as_bt).setOnClickListener { resetAllParams() }
        f<TextView>(R.id.tv_fablesol_tuning_done_as_bt).setOnClickListener { dismiss() }
        applyDoneButtonAccent(mAppliedBackground)

        mRecorder = AudioRecorder(mActivity)
        mRecorder!!.linkFableSol(mVisualizer!!)
        mRecorder!!.startListening()

        loadColorPool()

        return v
    }

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        val attributes = window.attributes
        attributes.preferredRefreshRate = TARGET_REFRESH_RATE
        window.attributes = attributes
        if (Build.VERSION.SDK_INT >= 35) {
            mVisualizer?.setRequestedFrameRate(TARGET_REFRESH_RATE)
        }
        // 参数很多：窗口高度收敛到理想值与屏高之间，ScrollView 用 weight 吸收差额，
        // 预览区与按钮行始终完整可见。
        val maxHeight = (resources.displayMetrics.heightPixels * 0.94f).toInt()
        window.setLayout(getDialogWindowWidthPx(), min(dp(IDEAL_HEIGHT_DP), maxHeight))
    }

    override fun onResume() {
        super.onResume()
        startTiltSensor()
    }

    override fun onPause() {
        stopTiltSensor()
        super.onPause()
    }

    override fun onDestroyView() {
        mHdrCapabilityGeneration++
        mColorAnimator?.cancel()
        mColorAnimator = null
        stopTiltSensor()
        restoreHostOrientation()
        mVisualizer?.setContainerGravity(0f, 1f, 0f)
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        mColorAnimator?.cancel()
        mColorAnimator = null
        stopTiltSensor()
        restoreHostOrientation()
        val recorder = mRecorder
        mRecorder = null
        Thread({
            recorder?.release()
            FileUtil.deleteDirectory(FileUtil.getTempPath(mActivity) + "/audio_raw")
        }, "FableSolTuningRelease").start()
        super.onDismiss(dialog)
    }

    // ---- 预览角标按钮：暂停/继续 + 换色 ----

    private fun setupPreviewButtons() {
        val ivPause = f<ImageView>(R.id.iv_fablesol_tuning_pause)
        val ivPalette = f<ImageView>(R.id.iv_fablesol_tuning_palette)
        installPreviewButtonChrome(ivPause)
        installPreviewButtonChrome(ivPalette)
        ivPause.setOnClickListener { setPreviewPaused(!mPreviewPaused) }
        ivPalette.setOnClickListener { switchWaterColor() }
    }

    /** 透明背景 + 圆形涟漪；图标压低不透明度，亮色模式下不压成实黑。 */
    private fun installPreviewButtonChrome(iv: ImageView) {
        iv.background = null
        iv.imageAlpha = PREVIEW_BUTTON_ICON_ALPHA
        iv.foreground = BackgroundUtil.circularRipple(
            BackgroundUtil.appChromeRippleColor(mActivity!!)
        )
    }

    private fun setPreviewPaused(paused: Boolean) {
        if (mPreviewPaused == paused) return
        mPreviewPaused = paused
        // 冻结模拟而非停渲染：静止画面上调参/换色/HDR 切换仍实时可见
        //（与 Python 模拟器的暂停语义一致）。
        mVisualizer?.setSimulationPaused(paused)
        f<ImageView>(R.id.iv_fablesol_tuning_pause).setImageResource(
            if (paused) R.drawable.act_fablesol_play else R.drawable.act_fablesol_pause
        )
    }

    // ---- 换色：颜色池 + 水体涌入过渡 + UI 元素渐变跟随 ----

    /** 颜色池 = accent 渐变 + 内置 10 色 + 用户记事背景（去重）；后台加载。 */
    private fun loadColorPool() {
        val host = mActivity ?: return
        val appContext = host.applicationContext
        Thread({
            val pool = LinkedHashMap<String, ThingBackground>()
            fun add(bg: ThingBackground?) {
                if (bg != null && !pool.containsKey(poolKey(bg))) pool[poolKey(bg)] = bg
            }
            add(App.defaultAccentBackground)
            for (color in appContext.resources.getIntArray(R.array.thing)) {
                add(ThingBackground.pure(color))
            }
            try {
                val dao = ThingDAO.getInstance(appContext)
                val cursor = dao?.getThingsCursor(
                    Def.Database.COLUMN_TYPE_THINGS + ">=" + Thing.NOTE +
                        " and " + Def.Database.COLUMN_TYPE_THINGS + "<=" + Thing.GOAL
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        add(Thing(it).getBackground())
                    }
                }
            } catch (_: Exception) {
                // 数据库不可用时颜色池退化为内置色 + accent，功能不受阻。
            }
            mContentView?.post {
                if (!isAdded) return@post
                mColorPool.clear()
                mColorPool.addAll(pool.values)
            }
        }, "FableSolTuningColors").start()
    }

    private fun poolKey(bg: ThingBackground): String {
        val end = if (bg.mode == ThingBackground.Mode.GRADIENT) bg.endColor else bg.color
        return "${bg.mode},${bg.color},$end,${bg.orientation}"
    }

    private fun switchWaterColor() {
        if (mColorPool.isEmpty()) return
        val currentKey = poolKey(mCurrentBackground)
        val candidates = mColorPool.filter { poolKey(it) != currentKey }
        if (candidates.isEmpty()) return
        startColorTransition(candidates[mRandom.nextInt(candidates.size)])
    }

    private fun startColorTransition(target: ThingBackground) {
        // 暂停冻结的是模拟而非渲染，换色涌入在静止波形上同样逐帧可见，无需恢复播放。
        mCurrentBackground = target
        mVisualizer?.animateThingBackground(target)

        val from = mAppliedBackground
        mColorAnimator?.cancel()
        mColorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COLOR_TRANSITION_MS
            var lastStep = -1
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                // SeekBar tint 每次都重建 drawable：按 ~12 档节流，且档中只更新
                // 视口内可见的滑杆（其余留给动画结束的一次性全量），否则 82 条
                // 全量重建会把主线程一帧打爆、连带预览掉帧。
                val step = (fraction * UI_COLOR_STEPS).toInt()
                if (step != lastStep) {
                    lastStep = step
                    applyUiAccent(lerpBackground(from, target, fraction), allSeekBars = false)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled || mContentView == null) return
                    applyUiAccent(target, allSeekBars = true)
                }
            })
            start()
        }
    }

    private fun lerpBackground(
        from: ThingBackground,
        to: ThingBackground,
        fraction: Float
    ): ThingBackground {
        val start = mArgbEvaluator.evaluate(fraction, from.color, to.color) as Int
        val gradient = from.mode == ThingBackground.Mode.GRADIENT ||
            to.mode == ThingBackground.Mode.GRADIENT
        if (!gradient) return ThingBackground.pure(start)
        val fromEnd = if (from.mode == ThingBackground.Mode.GRADIENT) from.endColor else from.color
        val toEnd = if (to.mode == ThingBackground.Mode.GRADIENT) to.endColor else to.color
        val end = mArgbEvaluator.evaluate(fraction, fromEnd, toEnd) as Int
        val orientation = if (to.mode == ThingBackground.Mode.GRADIENT) {
            to.orientation
        } else {
            from.orientation
        }
        return ThingBackground.gradient(start, end, orientation)
    }

    /** 把强调背景套到 Dialog 全部可见强调色元素上（换色动画每步调用）。 */
    private fun applyUiAccent(bg: ThingBackground, allSeekBars: Boolean) {
        mAppliedBackground = bg
        for (header in mAccentHeaders) BackgroundUtil.applyTextBackground(header, bg)
        if (allSeekBars) {
            for (seekBar in mAccentSeekBars) DisplayUtil.setSeekBarBackground(seekBar, bg)
        } else {
            val scroll = f<ScrollView>(R.id.sv_fablesol_tuning_params)
            for (seekBar in mAccentSeekBars) {
                if (isVisibleInScroll(seekBar, scroll)) {
                    DisplayUtil.setSeekBarBackground(seekBar, bg)
                }
            }
        }
        for (checkBox in mAccentCheckBoxes) {
            BackgroundUtil.applyCheckboxAccent(checkBox, bg)
            // 带圆形渐变 ripple 的指示 checkbox（性能面板行）同步换色；
            // 背景不是 GradientRippleDrawable 的普通 checkbox 此处为空操作。
            (checkBox.background as? GradientRippleDrawable)?.updateBackground(bg)
        }
        for (row in mAccentRippleRows) {
            (row.background as? GradientRippleDrawable)?.updateBackground(bg)
        }
        for (paint in mAccentChipPainters) paint()
        mHdrSeekBar?.let { DisplayUtil.setSeekBarBackground(it, bg) }
        applyDoneButtonAccent(bg)
    }

    private val mVisibilityScratch = android.graphics.Rect()

    private fun isVisibleInScroll(view: View, scroll: ScrollView): Boolean {
        mVisibilityScratch.set(0, 0, view.width, view.height)
        scroll.offsetDescendantRectToMyCoords(view, mVisibilityScratch)
        val top = scroll.scrollY
        val bottom = top + scroll.height
        return mVisibilityScratch.bottom >= top && mVisibilityScratch.top <= bottom
    }

    private fun applyDoneButtonAccent(bg: ThingBackground) {
        val done = f<TextView>(R.id.tv_fablesol_tuning_done_as_bt)
        BackgroundUtil.applyTextBackground(done, bg)
        GradientRippleDrawable.applyAccentRipple(
            done, bg, ContextCompat.getColor(mActivity!!, R.color.app_chrome_on_surface_strong)
        )
    }

    // ---- HDR 强度行（D204）：1.0=关闭、默认=上限 9.6、标定档 3.6，实时生效、松手持久化 ----

    private fun setupHdrRow() {
        val ctx = mContentView!!.context
        val label = f<TextView>(R.id.tv_fablesol_tuning_hdr)
        val value = f<TextView>(R.id.tv_fablesol_tuning_hdr_value)
        val seekBar = f<SeekBar>(R.id.sb_fablesol_tuning_hdr)
        mHdrSeekBar = seekBar
        seekBar.max = HDR_STRENGTH_STEPS
        DisplayUtil.setSeekBarBackground(seekBar, mAppliedBackground)
        if (!isHdrDisplaySupported()) {
            label.text = getString(R.string.fablesol_tuning_hdr) + " " +
                getString(R.string.fablesol_tuning_hdr_unsupported)
            label.alpha = 0.5f
            value.text = formatHdrStrength(FableSolHdrPolicy.STRENGTH_OFF)
            seekBar.progress = 0
            seekBar.isEnabled = false
            return
        }
        val initial = FableSolTuning.hdrStrength(ctx)
        seekBar.progress = hdrProgressOf(initial)
        value.text = formatHdrStrength(initial)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val strength = hdrStrengthOf(progress)
                value.text = formatHdrStrength(strength)
                if (fromUser) {
                    applyHdrStrength(strength)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit

            override fun onStopTrackingTouch(sb: SeekBar?) {
                FableSolTuning.setHdrStrength(ctx, hdrStrengthOf(seekBar.progress))
            }
        })
    }

    /** 强度实时下发：滑到 1.0 即关闭（等价旧开关取消勾选）。 */
    private fun applyHdrStrength(strength: Float) {
        mVisualizer?.setHdrStrength(strength)
        mVisualizer?.setRecordingHdrActive(
            isHdrDisplaySupported() && strength > FableSolHdrPolicy.STRENGTH_OFF
        )
    }

    private fun applyHdrPreference() {
        val ctx = mContentView?.context ?: return
        applyHdrStrength(FableSolTuning.hdrStrength(ctx))
    }

    private fun hdrStrengthOf(progress: Int): Float {
        val fraction = progress.toFloat() / HDR_STRENGTH_STEPS
        return FableSolHdrPolicy.STRENGTH_OFF +
            (FableSolHdrPolicy.MAX_STRENGTH - FableSolHdrPolicy.STRENGTH_OFF) * fraction
    }

    private fun hdrProgressOf(strength: Float): Int {
        val fraction = (strength - FableSolHdrPolicy.STRENGTH_OFF) /
            (FableSolHdrPolicy.MAX_STRENGTH - FableSolHdrPolicy.STRENGTH_OFF)
        return (fraction * HDR_STRENGTH_STEPS).roundToInt().coerceIn(0, HDR_STRENGTH_STEPS)
    }

    private fun formatHdrStrength(strength: Float): String =
        if (strength <= FableSolHdrPolicy.STRENGTH_OFF) {
            getString(R.string.fablesol_tuning_hdr_off)
        } else {
            String.format(Locale.US, "%.2f×", strength)
        }

    /** 与 WaveVisualizerFableSolGl.canBuildHdrSurface 同判据（View 尚未 attach 时用 Activity 的 display）。 */
    private fun isHdrDisplaySupported(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val display = mVisualizer?.display ?: mActivity?.display ?: return false
        return display.isHdr && display.isHdrSdrRatioAvailable
    }

    // ---- 参数行 ----

    private fun buildParamRows() {
        val container = f<LinearLayout>(R.id.ll_fablesol_tuning_params)
        // 保留 index 0 的 HDR 行，其余（重建时的旧参数行）全部移除。
        while (container.childCount > 1) {
            container.removeViewAt(container.childCount - 1)
        }
        mAccentHeaders.clear()
        mAccentSeekBars.clear()
        mAccentCheckBoxes.clear()
        mAccentRippleRows.clear()
        mAccentChipPainters.clear()
        val ctx = container.context
        for (group in FableSolTuning.GROUPS) {
            container.addView(makeGroupHeader(ctx, getString(group.titleRes)))
            for (spec in group.specs) {
                container.addView(
                    if (spec.boolLike) makeSwitchRow(ctx, spec) else makeSliderRow(ctx, spec)
                )
            }
        }
        addExportGroup(container, ctx)
        // debug 专属的调试组固定排在所有参数组之后；release 构建整段不出现。
        if (BuildConfig.DEBUG) {
            container.addView(makeGroupHeader(ctx, getString(R.string.fablesol_group_debug)))
            container.addView(makePerfHudRow(ctx))
        }
    }

    /**
     * 屏上性能面板开关（默认关闭）。它是调试工具偏好而非波浪参数：不进
     * [FableSolTuning.GROUPS] 目录、不参与"恢复默认"，只在下一次打开录音
     * Dialog 时生效（面板宿主在那边按存储值挂载）。
     */
    private fun makePerfHudRow(ctx: Context): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.setPadding(dp(20f), 0, dp(20f), 0)
        row.minimumHeight = dp(48f)
        row.background = GradientRippleDrawable(
            mAppliedBackground, shapeOval = false, cornerRadiusPx = 0f
        )
        mAccentRippleRows.add(row)

        val tvLabel = TextView(ctx)
        tvLabel.text = getString(R.string.fablesol_param_show_perf_hud)
        tvLabel.textSize = 13f
        tvLabel.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        val checkBox = CheckBox(ctx)
        checkBox.isChecked = FableSolTuning.isPerfHudEnabled(ctx)
        checkBox.isClickable = false
        checkBox.isFocusable = false
        BackgroundUtil.applyCheckboxAccent(checkBox, mAppliedBackground)
        mAccentCheckBoxes.add(checkBox)
        checkBox.setOnCheckedChangeListener { _, checked ->
            FableSolTuning.setPerfHudEnabled(ctx, checked)
        }
        row.addView(tvLabel)
        row.addView(checkBox)
        // 圆形渐变 ripple 替换系统默认的半透明黑波纹；必须在 addView 之后调用，
        // 它要顺带关掉父容器的裁剪。换色跟随见 applyUiAccent。
        GradientRippleDrawable.applyCheckboxRipple(checkBox, mAppliedBackground)
        row.setOnClickListener { checkBox.isChecked = !checkBox.isChecked }
        return row
    }

    /**
     * 导出编码参数（fablesol-video-export D10）：帧率、编码模式、质量参数或目标码率、
     * 关键帧间隔、是否导出 HDR。全部 release 可见、用户可调。
     *
     * 这组没有实时预览，所以末尾带一行**推导结果**作为反馈回路。恒定质量档下不给体积估算
     * ——那个档位里 `KEY_BIT_RATE` 只是提示，实际体积由画面复杂度决定，给数字反而误导。
     */
    private fun addExportGroup(container: LinearLayout, ctx: Context) {
        container.addView(makeGroupHeader(ctx, getString(R.string.fablesol_group_export)))

        val qualityRange = FableSolExportOptions.settingsQualityRange()
        val estimate = TextView(ctx)
        estimate.textSize = 12f
        estimate.alpha = 0.6f
        estimate.setPadding(dp(20f), dp(2f), dp(20f), dp(8f))

        fun refreshEstimate() {
            val frameRate = FableSolTuning.exportFrameRateCap(ctx)
            val options = FableSolExportOptions.read(ctx)
            estimate.text = if (options.constantQuality && qualityRange != null) {
                getString(R.string.fablesol_export_estimate_quality, frameRate.toString())
            } else {
                val megabytesPerMinute = options.bitrateBps(frameRate) * 60.0 / 8.0 / 1_000_000.0
                getString(
                    R.string.fablesol_export_estimate_bitrate,
                    String.format(java.util.Locale.US, "%.0f", megabytesPerMinute),
                    frameRate.toString()
                )
            }
        }

        // 先把两条互斥的行造出来，再定义切换逻辑——它们互相引用，顺序反了 Kotlin 会认为
        // 变量可能未初始化。
        val qualityRow: View? = qualityRange?.let { range ->
            val lower = range.lower
            val upper = range.upper
            makeExportSliderRow(
                ctx,
                getString(R.string.fablesol_param_export_quality_value),
                lower.toFloat(),
                upper.toFloat(),
                (upper - lower).coerceAtLeast(1),
                FableSolExportOptions.read(ctx).resolveWithin(range).toFloat(),
                { value ->
                    String.format(java.util.Locale.US, "%.0f  (%d-%d)", value, lower, upper)
                }
            ) { value ->
                FableSolTuning.setExportQualityValue(ctx, value.toInt())
            }
        }
        val bitrateRow: View = makeExportSliderRow(
            ctx,
            getString(R.string.fablesol_param_export_bitrate),
            FableSolExportOptions.MIN_BITRATE_MBPS,
            FableSolExportOptions.MAX_BITRATE_MBPS,
            58,
            FableSolTuning.exportBitrateMbps(ctx),
            { value -> String.format(java.util.Locale.US, "%.0f Mbps", value) }
        ) { value ->
            FableSolTuning.setExportBitrateMbps(ctx, value)
            refreshEstimate()
        }

        fun refreshModeRows() {
            val constant = FableSolTuning.exportConstantQuality(ctx) && qualityRange != null
            qualityRow?.visibility = if (constant) View.VISIBLE else View.GONE
            bitrateRow.visibility = if (constant) View.GONE else View.VISIBLE
            refreshEstimate()
        }

        container.addView(
            makeExportChoiceRow(
                ctx,
                getString(R.string.fablesol_param_export_frame_rate),
                listOf("60 fps", "120 fps"),
                if (FableSolTuning.exportFrameRateCap(ctx) >= FableSolExportOptions.FRAME_RATE_HIGH) 1 else 0
            ) { index ->
                FableSolTuning.setExportFrameRateCap(
                    ctx,
                    if (index == 1) {
                        FableSolExportOptions.FRAME_RATE_HIGH
                    } else {
                        FableSolExportOptions.FRAME_RATE_BASE
                    }
                )
                refreshEstimate()
            }
        )
        if (qualityRange != null) {
            container.addView(
                makeExportChoiceRow(
                    ctx,
                    getString(R.string.fablesol_param_export_bitrate_mode),
                    listOf(
                        getString(R.string.fablesol_export_mode_quality),
                        getString(R.string.fablesol_export_mode_bitrate)
                    ),
                    if (FableSolTuning.exportConstantQuality(ctx)) 0 else 1
                ) { index ->
                    FableSolTuning.setExportConstantQuality(ctx, index == 0)
                    refreshModeRows()
                }
            )
        }
        if (qualityRow != null) container.addView(qualityRow)
        container.addView(bitrateRow)
        container.addView(
            makeExportSliderRow(
                ctx,
                getString(R.string.fablesol_param_export_keyframe),
                FableSolExportOptions.MIN_KEYFRAME_SECONDS,
                FableSolExportOptions.MAX_KEYFRAME_SECONDS,
                19,
                FableSolTuning.exportKeyframeSeconds(ctx),
                { value -> String.format(java.util.Locale.US, "%.1f s", value) }
            ) { value ->
                FableSolTuning.setExportKeyframeSeconds(ctx, value)
            }
        )
        val hdrSwitch = makeExportSwitchRow(
            ctx,
            getString(R.string.fablesol_param_export_hdr),
            initial = false
        ) { checked -> FableSolTuning.setExportHdrEnabled(ctx, checked) }
        setHdrExportSwitchState(hdrSwitch, enabled = false, unsupported = false)
        container.addView(hdrSwitch.row)
        probeHdrExportCapability(ctx.applicationContext, hdrSwitch)

        refreshModeRows()
        container.addView(estimate)
    }

    /**
     * 二选一的档位行：标签在左，若干个可点的圆角标签在右。
     *
     * 选中项用强调色填底，文字取 [BackgroundUtil.onColor]——它按底色明暗自动给偏黑或偏白，
     * 而不是固定白字（浅色强调色上固定白字读不出来）。触摸涟漪与面板其余控件同源，
     * 换色时随 [applyUiAccent] 一起刷新。
     */
    private fun makeExportChoiceRow(
        ctx: Context,
        label: String,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit
    ): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.setPadding(dp(20f), dp(6f), dp(20f), dp(6f))
        row.minimumHeight = dp(48f)

        val tvLabel = TextView(ctx)
        tvLabel.text = label
        tvLabel.textSize = 13f
        tvLabel.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        row.addView(tvLabel)

        val chips = ArrayList<TextView>(options.size)
        var current = selectedIndex
        val cornerRadius = dp(14f).toFloat()

        fun paint() {
            for ((index, chip) in chips.withIndex()) {
                if (index == current) {
                    // 胶囊填充必须保留 ThingBackground 的完整起止色与方向；只取 color
                    // 会在换色时把渐变压成起点单色。
                    val fill = BackgroundUtil.fillDrawable(mAppliedBackground)
                    (fill as? android.graphics.drawable.GradientDrawable)?.cornerRadius =
                        cornerRadius
                    chip.background = fill
                    // 必须走 applyTextBackground 的纯色分支而不是 setTextColor：渐变强调色
                    // 会在 TextPaint 上留一个 shader，直接 setTextColor 盖不住它，切换选中
                    // 状态时文字就还是渐变色，看不出黑白自适应。
                    BackgroundUtil.applyTextBackground(
                        chip,
                        ThingBackground.pure(BackgroundUtil.onColor(mAppliedBackground, 1f))
                    )
                } else {
                    // GradientDrawable 的 stroke 只能接收单个 int；这里用自绘描边让未选中
                    // 胶囊同样保留完整渐变，并以统一 alpha 淡化整条渐变，而非取代表色。
                    chip.background = BackgroundUtil.GradientStrokeDrawable(
                        mAppliedBackground,
                        cornerRadius,
                        dp(1f).toFloat()
                    ).apply {
                        alpha = CHOICE_OUTLINE_ALPHA
                    }
                    BackgroundUtil.applyTextBackground(chip, mAppliedBackground)
                }
                // 涟漪走 foreground：background 位置被填底/描边占着，而 foreground 同样
                // 会收到按下态与 hotspot。
                val existing = chip.foreground as? GradientRippleDrawable
                if (existing != null) {
                    existing.updateBackground(mAppliedBackground)
                } else {
                    chip.foreground = GradientRippleDrawable(
                        mAppliedBackground, shapeOval = false, cornerRadiusPx = cornerRadius
                    )
                }
            }
        }

        for ((index, optionText) in options.withIndex()) {
            val chip = TextView(ctx)
            chip.text = optionText
            chip.textSize = 12f
            chip.setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
            chip.isClickable = true
            chip.isFocusable = true
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) lp.leftMargin = dp(6f)
            chip.layoutParams = lp
            chip.setOnClickListener {
                if (current == index) return@setOnClickListener
                current = index
                paint()
                onSelect(index)
            }
            chips.add(chip)
            row.addView(chip)
        }
        paint()
        mAccentChipPainters.add(::paint)
        return row
    }

    private fun makeExportSwitchRow(
        ctx: Context,
        label: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ): ExportSwitchControl {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.setPadding(dp(20f), 0, dp(20f), 0)
        row.minimumHeight = dp(48f)
        row.background = GradientRippleDrawable(
            mAppliedBackground, shapeOval = false, cornerRadiusPx = 0f
        )
        mAccentRippleRows.add(row)

        val tvLabel = TextView(ctx)
        tvLabel.text = label
        tvLabel.textSize = 13f
        tvLabel.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        val checkBox = CheckBox(ctx)
        checkBox.isChecked = initial
        checkBox.isClickable = false
        checkBox.isFocusable = false
        BackgroundUtil.applyCheckboxAccent(checkBox, mAppliedBackground)
        mAccentCheckBoxes.add(checkBox)
        checkBox.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        row.addView(tvLabel)
        row.addView(checkBox)
        GradientRippleDrawable.applyCheckboxRipple(checkBox, mAppliedBackground)
        row.setOnClickListener {
            if (row.isEnabled) checkBox.isChecked = !checkBox.isChecked
        }
        return ExportSwitchControl(row, tvLabel, checkBox)
    }

    private fun setHdrExportSwitchState(
        control: ExportSwitchControl,
        enabled: Boolean,
        unsupported: Boolean
    ) {
        control.row.isEnabled = enabled
        control.row.isClickable = enabled
        // 顶部“HDR 高光增强（设备不支持）”保持 TextView 的 enabled 色，仅把 alpha 设为
        // 0.5。这里采用完全相同的方式，不能再叠加 TextView disabled 色与整行 0.38 alpha。
        control.label.isEnabled = true
        control.label.text = getString(R.string.fablesol_param_export_hdr) +
            if (unsupported) {
                " " + getString(R.string.fablesol_tuning_hdr_unsupported)
            } else {
                ""
            }
        control.label.alpha = if (enabled) 1f else HDR_UNSUPPORTED_TEXT_ALPHA
        control.checkBox.isEnabled = enabled
        control.row.alpha = 1f
    }

    /**
     * codec 广告只能作候选筛选；真正交换并封装一帧后仍是 HDR，开关才可操作。
     * 已有进程缓存时立即恢复；否则先让 Dialog 完成首帧和预览初始化，再以后台低优先级读取
     * 持久化结果或执行真实编码。未知期间与明确不支持时都保持置灰。
     */
    private fun probeHdrExportCapability(
        appContext: Context,
        control: ExportSwitchControl
    ) {
        val generation = ++mHdrCapabilityGeneration
        FableSolHdrExportCapability.peekCachedResult()?.let { supported ->
            applyHdrExportCapabilityResult(appContext, control, supported)
            return
        }

        control.row.postDelayed({
            if (!isAdded || generation != mHdrCapabilityGeneration) return@postDelayed
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val supported = FableSolHdrExportCapability.probe(appContext)
                control.row.post {
                    if (!isAdded || generation != mHdrCapabilityGeneration) return@post
                    applyHdrExportCapabilityResult(appContext, control, supported)
                }
            }, "FableSolHdrCapability").apply {
                isDaemon = true
                start()
            }
        }, HDR_CAPABILITY_PROBE_DELAY_MS)
    }

    private fun applyHdrExportCapabilityResult(
        appContext: Context,
        control: ExportSwitchControl,
        supported: Boolean
    ) {
        if (supported) {
            control.checkBox.isChecked = FableSolTuning.exportHdrEnabled(appContext)
            setHdrExportSwitchState(control, enabled = true, unsupported = false)
        } else {
            FableSolTuning.setExportHdrEnabled(appContext, false)
            control.checkBox.isChecked = false
            setHdrExportSwitchState(control, enabled = false, unsupported = true)
        }
    }

    private fun makeExportSliderRow(
        ctx: Context,
        label: String,
        lo: Float,
        hi: Float,
        steps: Int,
        initial: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit
    ): View {
        val column = LinearLayout(ctx)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(20f), dp(9f), dp(20f), dp(5f))

        val headerRow = LinearLayout(ctx)
        headerRow.orientation = LinearLayout.HORIZONTAL
        val tvLabel = TextView(ctx)
        tvLabel.text = label
        tvLabel.textSize = 13f
        tvLabel.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        val tvValue = TextView(ctx)
        tvValue.textSize = 12f
        tvValue.alpha = 0.6f
        tvValue.text = format(initial)
        headerRow.addView(tvLabel)
        headerRow.addView(tvValue)
        column.addView(headerRow)

        val seekBar = SeekBar(ctx)
        seekBar.max = steps
        val span = if (hi > lo) hi - lo else 1f
        seekBar.progress = (((initial - lo) / span) * steps).toInt().coerceIn(0, steps)
        DisplayUtil.setSeekBarBackground(seekBar, mAppliedBackground)
        mAccentSeekBars.add(seekBar)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = lo + span * progress / steps
                tvValue.text = format(value)
                if (fromUser) onChange(value)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        column.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return column
    }

    private fun makeGroupHeader(ctx: Context, title: String): View {
        val tv = TextView(ctx)
        tv.text = title
        tv.textSize = 13f
        tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        BackgroundUtil.applyTextBackground(tv, mAppliedBackground)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(dp(20f), dp(22f), dp(20f), dp(4f))
        tv.layoutParams = lp
        mAccentHeaders.add(tv)
        return tv
    }

    private fun makeSliderRow(ctx: Context, spec: FableSolTuning.Spec): View {
        val defaultValue = mDefaults.get(spec.key)
        val initial = FableSolTuning.storedValue(ctx, spec, defaultValue)
        val steps = stepsOf(spec)

        val column = LinearLayout(ctx)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(20f), dp(9f), dp(20f), dp(5f))

        val headerRow = LinearLayout(ctx)
        headerRow.orientation = LinearLayout.HORIZONTAL
        val tvLabel = TextView(ctx)
        tvLabel.text = getString(spec.labelRes)
        tvLabel.textSize = 13f
        tvLabel.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        val tvValue = TextView(ctx)
        tvValue.textSize = 12f
        tvValue.alpha = 0.6f
        tvValue.text = formatValue(spec, initial)
        headerRow.addView(tvLabel)
        headerRow.addView(tvValue)
        column.addView(headerRow)

        val seekBar = SeekBar(ctx)
        seekBar.max = steps
        seekBar.progress = progressOf(spec, initial, steps)
        DisplayUtil.setSeekBarBackground(seekBar, mAppliedBackground)
        mAccentSeekBars.add(seekBar)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = valueOf(spec, progress, steps)
                tvValue.text = formatValue(spec, value)
                if (fromUser) {
                    applyRuntimeTuning(spec, value)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val value = valueOf(spec, seekBar.progress, steps)
                FableSolTuning.putValue(ctx, spec, value, defaultValue)
            }
        })
        column.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return column
    }

    private fun makeSwitchRow(ctx: Context, spec: FableSolTuning.Spec): View {
        val defaultValue = mDefaults.get(spec.key)
        val initial = FableSolTuning.storedValue(ctx, spec, defaultValue)

        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.setPadding(dp(20f), 0, dp(20f), 0)
        row.minimumHeight = dp(48f)
        row.background = GradientRippleDrawable(
            mAppliedBackground, shapeOval = false, cornerRadiusPx = 0f
        )
        mAccentRippleRows.add(row)

        val tvLabel = TextView(ctx)
        tvLabel.text = getString(spec.labelRes)
        tvLabel.textSize = 13f
        tvLabel.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        val checkBox = CheckBox(ctx)
        checkBox.isChecked = initial >= 0.5
        checkBox.isClickable = false
        checkBox.isFocusable = false
        BackgroundUtil.applyCheckboxAccent(checkBox, mAppliedBackground)
        mAccentCheckBoxes.add(checkBox)
        checkBox.setOnCheckedChangeListener { _, checked ->
            val value = if (checked) 1.0 else 0.0
            applyRuntimeTuning(spec, value)
            FableSolTuning.putValue(ctx, spec, value, defaultValue)
        }
        row.addView(tvLabel)
        row.addView(checkBox)
        row.setOnClickListener { checkBox.isChecked = !checkBox.isChecked }
        return row
    }

    private fun resetAllParams() {
        val ctx = mContentView?.context ?: return
        FableSolTuning.clearAllParams(ctx)
        // 2026-07-24 用户裁定：恢复默认包含 HDR 强度（回默认档 3.6），不再保留用户设置。
        FableSolTuning.clearHdrStrength(ctx)
        // 导出参数同样纳入「恢复默认」（fablesol-video-export D10）。
        FableSolTuning.clearExportOptions(ctx)
        for (group in FableSolTuning.GROUPS) {
            for (spec in group.specs) {
                applyRuntimeTuning(spec, mDefaults.get(spec.key))
            }
        }
        applyHdrPreference()
        setupHdrRow()
        buildParamRows()
    }

    private fun applyRuntimeTuning(spec: FableSolTuning.Spec, value: Double) {
        when (spec.target) {
            FableSolTuning.Target.RENDERER -> {
                mVisualizer?.setTuningValue(spec.key, value)
                if (spec.key == FableSolFrontEndTuning.KEY_STATE_SENSITIVITY) {
                    mRecorder?.setFableSolFrontEndTuning(spec.key, value)
                }
            }
            FableSolTuning.Target.AUDIO_FRONT_END ->
                mRecorder?.setFableSolFrontEndTuning(spec.key, value)
        }
    }

    private fun setupScrollIndicators() {
        val scroll = f<ScrollView>(R.id.sv_fablesol_tuning_params)
        val topIndicator = f<View>(R.id.view_fablesol_tuning_scroll_indicator)
        val bottomIndicator = f<View>(R.id.view_fablesol_tuning_bottom_divider)
        fun update() {
            topIndicator.visibility =
                if (scroll.canScrollVertically(-1)) View.VISIBLE else View.INVISIBLE
            bottomIndicator.visibility =
                if (scroll.canScrollVertically(1)) View.VISIBLE else View.INVISIBLE
        }
        scroll.viewTreeObserver.addOnScrollChangedListener { update() }
        scroll.post { update() }
    }

    // ---- 数值映射与格式化 ----
    // 均匀插值映射（粒度≈step，两端点精确可达）：step 不整除范围时（如 lighten_far
    // 上限 0.864、步长 0.01），按步长量化会永远取不到上限/默认值。

    private fun stepsOf(spec: FableSolTuning.Spec): Int =
        (((spec.hi - spec.lo) / spec.step).roundToInt()).coerceAtLeast(1)

    private fun progressOf(spec: FableSolTuning.Spec, value: Double, steps: Int): Int =
        (((value - spec.lo) / (spec.hi - spec.lo) * steps).roundToInt()).coerceIn(0, steps)

    private fun valueOf(spec: FableSolTuning.Spec, progress: Int, steps: Int): Double =
        (spec.lo + (spec.hi - spec.lo) * progress / steps).coerceIn(spec.lo, spec.hi)

    private fun formatValue(spec: FableSolTuning.Spec, value: Double): String {
        val pattern = when {
            spec.step >= 1.0 -> "%.0f"
            spec.step >= 0.1 -> "%.1f"
            spec.step >= 0.01 -> "%.2f"
            else -> "%.3f"
        }
        val number = String.format(Locale.US, pattern, value)
        return if (spec.unit.isEmpty()) number else number + spec.unit
    }

    // ---- 重力倾斜（与录音 Dialog 同一手法，预览的水面滚转/俯仰跟随设备姿态） ----

    private val mTiltListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 3) return
            val gx = event.values[0]
            val gy = event.values[1]
            val gz = event.values[2]
            val (screenX, screenY) = when (mLockedRotation) {
                Surface.ROTATION_90 -> -gy to gx
                Surface.ROTATION_180 -> -gx to -gy
                Surface.ROTATION_270 -> gy to -gx
                else -> gx to gy
            }
            mVisualizer?.setContainerGravity(-screenX, screenY, gz)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun lockHostOrientation() {
        val host = mActivity ?: return
        if (mOrientationLocked) return
        mLockedRotation = host.windowManager.defaultDisplay.rotation
        mOriginalRequestedOrientation = host.requestedOrientation
        host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        mOrientationLocked = true
    }

    private fun restoreHostOrientation() {
        val host = mActivity ?: return
        if (!mOrientationLocked) return
        host.requestedOrientation = mOriginalRequestedOrientation
        mOrientationLocked = false
    }

    private fun prepareTiltSensor() {
        val host = mActivity ?: return
        val manager = host.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        mSensorManager = manager
        mGravitySensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    private fun startTiltSensor() {
        val manager = mSensorManager ?: return
        val sensor = mGravitySensor ?: return
        if (mTiltSensorRegistered) return
        val thread = HandlerThread("FableSolTuningTilt").also { it.start() }
        mSensorThread = thread
        mTiltSensorRegistered = manager.registerListener(
            mTiltListener, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(thread.looper)
        )
        if (!mTiltSensorRegistered) {
            thread.quitSafely()
            mSensorThread = null
        }
    }

    private fun stopTiltSensor() {
        if (mTiltSensorRegistered) {
            mSensorManager?.unregisterListener(mTiltListener)
            mTiltSensorRegistered = false
        }
        mSensorThread?.quitSafely()
        mSensorThread = null
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val TAG = "FableSolTuningDialogFragment"

        // 与录音 Dialog 一致：请求 ≤120Hz 高刷模式，渲染节奏由 pacer 跟随显示。
        private const val TARGET_REFRESH_RATE =
            WaveVisualizerFableSolGl.MAX_RENDER_FPS.toFloat()

        /** 预览 240dp + 参数区 + 按钮行的理想总高。 */
        private const val IDEAL_HEIGHT_DP = 648f

        /** 预览取景上移量：让第 0 层水线离下边缘约 42dp，波谷不再贴边。 */
        private const val PREVIEW_CONTENT_OFFSET_DP = -36f

        /** 与 GL 端 FableSolGlRenderer.COLOR_TRANSITION_MS 同步。 */
        private const val COLOR_TRANSITION_MS = 1600L
        private const val UI_COLOR_STEPS = 12
        /** 未选中胶囊的完整渐变描边 alpha（0-255）。 */
        private const val CHOICE_OUTLINE_ALPHA = 96
        /** 与顶部“HDR 高光增强（设备不支持）”标签完全一致的不可用态透明度。 */
        private const val HDR_UNSUPPORTED_TEXT_ALPHA = 0.5f
        /** 避开 Dialog 首帧、录音预览与 GL 预热，随后才允许后台真实编码。 */
        private const val HDR_CAPABILITY_PROBE_DELAY_MS = 800L

        /** 角标图标不透明度（0-255）：亮色模式下避免实黑，浮在水面上更轻。 */
        private const val PREVIEW_BUTTON_ICON_ALPHA = 176

        /** HDR 强度滑杆：1.0～9.6、步长 0.05（172 步），默认=上限 9.6（第 172 格）。 */
        private const val HDR_STRENGTH_STEPS = 172
    }

    private data class ExportSwitchControl(
        val row: LinearLayout,
        val label: TextView,
        val checkBox: CheckBox
    )
}
