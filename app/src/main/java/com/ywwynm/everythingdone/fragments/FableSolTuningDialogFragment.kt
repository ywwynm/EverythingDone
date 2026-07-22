@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

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
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat

import com.ywwynm.everythingdone.App
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
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolParams
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
    private val mAccentRippleRows = ArrayList<View>()
    private var mHdrCheckBox: CheckBox? = null
    private var mHdrRow: View? = null

    private var mSensorManager: SensorManager? = null
    private var mGravitySensor: Sensor? = null
    private var mSensorThread: HandlerThread? = null
    private var mTiltSensorRegistered = false
    private var mOriginalRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var mOrientationLocked = false
    private var mLockedRotation = Surface.ROTATION_0

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
        for (checkBox in mAccentCheckBoxes) BackgroundUtil.applyCheckboxAccent(checkBox, bg)
        for (row in mAccentRippleRows) {
            (row.background as? GradientRippleDrawable)?.updateBackground(bg)
        }
        mHdrCheckBox?.let { checkBox ->
            BackgroundUtil.applyCheckboxAccent(checkBox, bg)
            (checkBox.background as? GradientRippleDrawable)?.updateBackground(bg)
        }
        (mHdrRow?.background as? GradientRippleDrawable)?.updateBackground(bg)
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

    // ---- HDR 行 ----

    private fun setupHdrRow() {
        val ctx = mContentView!!.context
        val row = f<RelativeLayout>(R.id.rl_fablesol_tuning_hdr)
        val checkBox = f<CheckBox>(R.id.cb_fablesol_tuning_hdr)
        val label = f<TextView>(R.id.tv_fablesol_tuning_hdr)
        mHdrRow = row
        mHdrCheckBox = checkBox
        BackgroundUtil.applyCheckboxAccent(checkBox, mAppliedBackground)
        // 复选框自身的圆形涟漪也换成强调渐变（设置界面同款），随换色更新。
        GradientRippleDrawable.applyCheckboxRipple(checkBox, mAppliedBackground)
        row.background = GradientRippleDrawable(
            mAppliedBackground, shapeOval = false, cornerRadiusPx = 0f
        )
        val supported = isHdrDisplaySupported()
        if (!supported) {
            label.text = getString(R.string.fablesol_tuning_hdr) + " " +
                getString(R.string.fablesol_tuning_hdr_unsupported)
            label.alpha = 0.5f
            checkBox.isEnabled = false
            checkBox.isChecked = false
            row.isClickable = false
            return
        }
        checkBox.isChecked = FableSolTuning.isHdrEnabled(ctx)
        checkBox.setOnCheckedChangeListener { _, checked ->
            FableSolTuning.setHdrEnabled(ctx, checked)
            applyHdrPreference()
        }
        row.setOnClickListener { checkBox.isChecked = !checkBox.isChecked }
    }

    private fun applyHdrPreference() {
        val ctx = mContentView?.context ?: return
        mVisualizer?.setRecordingHdrActive(
            isHdrDisplaySupported() && FableSolTuning.isHdrEnabled(ctx)
        )
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
        val ctx = container.context
        for (group in FableSolTuning.GROUPS) {
            container.addView(makeGroupHeader(ctx, getString(group.titleRes)))
            for (spec in group.specs) {
                container.addView(
                    if (spec.boolLike) makeSwitchRow(ctx, spec) else makeSliderRow(ctx, spec)
                )
            }
        }
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
        for (group in FableSolTuning.GROUPS) {
            for (spec in group.specs) {
                applyRuntimeTuning(spec, mDefaults.get(spec.key))
            }
        }
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

        /** 角标图标不透明度（0-255）：亮色模式下避免实黑，浮在水面上更轻。 */
        private const val PREVIEW_BUTTON_ICON_ALPHA = 176
    }
}
