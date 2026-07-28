@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.graphics.Typeface
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
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
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
import android.widget.Toast
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
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportCapabilityMatrix
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportCodecFamily
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportDisplayLuminance
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportHdr10PlusCurve
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportHdrFormat
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolFrontEndTuning
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolHdrExportCapability
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolHdrPolicy
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolParams
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportOptions
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportTransfer
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
    /**
     * 本 Dialog 的全部 checkbox。**未选中态一律也用完整渐变描边**，因此换色重建时必须带上
     * `uncheckedGradient`——只此一条链路，不留"有的带有的不带"的余地。
     */
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
    /** 指示性文字末尾那一段：最终会用哪种格式。探测回来或改选之后刷新。 */
    private var mResolvedExportFormat: String? = null
    /** 同一行里还要写清最终落到哪个编码器：自动档解析到哪一族，用户自己是看不出来的。 */
    private var mResolvedExportCodec: String? = null
    /**
     * 这次导出**真正**能达到的帧率。
     *
     * 设置里那一项是上限，当前组合达不到时导出会自己降级。体积估算和提示语必须按降级后的
     * 帧率算，否则会同时给出两个互相矛盾的数（面板上写 120 fps，产物是 60 fps）。
     */
    private var mResolvedExportFrameRate: Int? = null
    /** 解析出的格式若是 PQ 系，指示行还要带上漫反射白与峰值。 */
    private var mResolvedExportPqFormat: FableSolExportHdrFormat? = null
    /** HDR 强度拖动时，让导出组的自动白锚滑杆与推导文字同步预览。 */
    private var mRefreshExportDerivedInfo: ((Float) -> Unit)? = null

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
        mRefreshExportDerivedInfo = null
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
            // 未选中描边也吃完整渐变，重建时必须把这个参数带上——漏了就会在第一次换色时
            // 悄悄退回中性描边。
            BackgroundUtil.applyCheckboxAccent(checkBox, bg, uncheckedGradient = true)
            // 圆形渐变 ripple 同步换色；背景不是 GradientRippleDrawable 时此处为空操作。
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
                    mRefreshExportDerivedInfo?.invoke(strength)
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
        mRefreshExportDerivedInfo = null
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
        // 紧跟 HDR 强度行：两者都是"这块画面怎么呈现"的偏好，不属于任何一个波浪参数组，
        // 因此排在第一个组标题之前。
        container.addView(makeLiveTiltRow(ctx))
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
     * 画面是否跟随设备姿态倾斜（[FableSolTuning.liveTiltEnabled]）。勾掉之后录音与音频附件
     * 对话框不再锁死详情页的屏幕方向、录音写出的 WAV 也不再带重力轨迹。
     *
     * 这里的预览即时跟随勾选状态：否则用户刚把它关掉，上方水面却还在随手腕晃动，看起来
     * 就像这个开关没生效。
     */
    private fun makeLiveTiltRow(ctx: Context): View = makeCheckRow(
        ctx,
        getString(R.string.fablesol_param_live_tilt),
        FableSolTuning.liveTiltEnabled(ctx)
    ) { checked ->
        FableSolTuning.setLiveTiltEnabled(ctx, checked)
        applyLiveTiltPreference()
    }

    /**
     * 屏上性能面板开关（默认关闭）。它是调试工具偏好而非波浪参数：不进
     * [FableSolTuning.GROUPS] 目录、不参与"恢复默认"，只在下一次打开录音
     * Dialog 时生效（面板宿主在那边按存储值挂载）。
     */
    private fun makePerfHudRow(ctx: Context): View = makeCheckRow(
        ctx,
        getString(R.string.fablesol_param_show_perf_hud),
        FableSolTuning.isPerfHudEnabled(ctx)
    ) { checked ->
        FableSolTuning.setPerfHudEnabled(ctx, checked)
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

        val qualityRange = FableSolExportOptions.settingsQualityRange(ctx)
        val estimate = TextView(ctx)
        estimate.textSize = 12f
        estimate.alpha = 0.6f
        estimate.setPadding(dp(20f), dp(2f), dp(20f), 0)

        fun refreshEstimate(
            strength: Float = FableSolTuning.hdrStrength(ctx)
        ) {
            // 上限达不到时导出会自己降级，估算必须按降级后的帧率算。
            val frameRate = mResolvedExportFrameRate ?: FableSolTuning.exportFrameRateCap(ctx)
            val options = FableSolExportOptions.read(ctx)
            val base = if (options.constantQuality && qualityRange != null) {
                getString(R.string.fablesol_export_estimate_quality, frameRate.toString())
            } else {
                val megabytesPerMinute = options.bitrateBps(frameRate) * 60.0 / 8.0 / 1_000_000.0
                getString(
                    R.string.fablesol_export_estimate_bitrate,
                    String.format(java.util.Locale.US, "%.0f", megabytesPerMinute),
                    frameRate.toString()
                )
            }
            // 这一行是这一组设置的**推导结果**：不只写落到哪种格式，还要把由它派生出来的
            // 关键数值一并摆出来——尤其是**峰值**。峰值 = 漫反射白 × HDR 强度，两个滑杆
            // 各调各的，很容易在不知不觉间把峰值推到屏幕根本装不下的量级（那正是画面
            // 发白、掉饱和的来源），所以必须让这个乘积可见。
            val pieces = StringBuilder(base)
            pieces.append(" · ").append(mResolvedExportFormat ?: "…")
            mResolvedExportCodec?.let { pieces.append(" · ").append(it) }
            val pqFormat = mResolvedExportPqFormat
            if (pqFormat != null) {
                val automatic = FableSolTuning.isExportPqWhiteAutomatic(ctx)
                val recommendation = if (automatic) {
                    FableSolTuning.exportPqWhiteRecommendation(ctx, strength)
                } else {
                    null
                }
                val white = recommendation?.whiteNits
                    ?: FableSolTuning.exportPqWhiteNits(ctx, strength)
                val peak = white * strength
                pieces.append(
                    getString(
                        R.string.fablesol_export_estimate_white,
                        String.format(java.util.Locale.US, "%.0f", white),
                        String.format(java.util.Locale.US, "%.0f", peak)
                    )
                )
                if (pqFormat == FableSolExportHdrFormat.HDR10_PLUS) {
                    pieces.append(
                        getString(
                            R.string.fablesol_export_estimate_highlight,
                            FableSolTuning.exportHighlightStart(ctx)
                        )
                    )
                }
                if (recommendation != null) {
                    fun luminanceCapability(value: Float?): String =
                        value?.let {
                            getString(
                                R.string.fablesol_export_estimate_luminance_nits,
                                FableSolExportDisplayLuminance.formatDerivationNumber(it)
                            )
                        } ?: getString(
                            R.string.fablesol_export_estimate_luminance_unavailable
                        )

                    pieces.append(
                        getString(
                            R.string.fablesol_export_estimate_white_auto_formula,
                            luminanceCapability(recommendation.panelPeakNits),
                            luminanceCapability(recommendation.panelMaxAverageNits),
                            FableSolExportDisplayLuminance.constraintFormula(recommendation),
                            FableSolExportDisplayLuminance.formatDerivationNumber(
                                recommendation.rawConstraintWhiteNits
                            ),
                            FableSolExportDisplayLuminance.formatDerivationNumber(
                                FableSolExportOptions.MIN_PQ_WHITE_NITS
                            ),
                            FableSolExportDisplayLuminance.formatDerivationNumber(
                                FableSolExportDisplayLuminance.AUTO_WHITE_MAX_NITS
                            ),
                            FableSolExportDisplayLuminance.formatDerivationNumber(
                                FableSolExportDisplayLuminance.AUTO_WHITE_STEP_NITS
                            ),
                            FableSolExportDisplayLuminance.formatDerivationNumber(white)
                        )
                    )
                    if (recommendation.fallbackUsed) {
                        pieces.append(
                            getString(
                                R.string.fablesol_export_estimate_white_auto_fallback
                            )
                        )
                    }
                } else {
                    pieces.append(
                        getString(R.string.fablesol_export_estimate_white_manual)
                    )
                }
            }
            // 第一行是这一组设置的结论，其余是推导过程。把结论加粗并空一行隔开，读的时候
            // 一眼就能分清"我得到了什么"和"它是怎么来的"。
            val text = pieces.toString()
            val firstBreak = text.indexOf('\n')
            estimate.text = if (firstBreak < 0) {
                SpannableStringBuilder(text).apply {
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else {
                SpannableStringBuilder(text.substring(0, firstBreak)).apply {
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    append("\n\n")
                    append(text.substring(firstBreak + 1))
                }
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

        // 倾斜是**画面内容**，不是编码参数，所以排在这一组最前面。它只对本应用录制的音频
        // 有效：只有那些 WAV 里带重力轨迹。
        container.addView(
            makeCheckRow(
                ctx,
                getString(R.string.fablesol_param_export_tilt),
                FableSolTuning.exportTiltEnabled(ctx)
            ) { checked ->
                FableSolTuning.setExportTiltEnabled(ctx, checked)
            }
        )

        // 帧率行比能力探测先造出来，可用状态与联动却要等探测回来才能接上，所以留两个句柄。
        var frameRateChips: ChoiceChips? = null
        var frameRateSelected: ((Int) -> Unit)? = null
        container.addView(
            makeExportChoiceRow(
                ctx,
                getString(R.string.fablesol_param_export_frame_rate),
                listOf("60 fps", "120 fps"),
                if (FableSolTuning.exportFrameRateCap(ctx) >= FableSolExportOptions.FRAME_RATE_HIGH) 1 else 0,
                onChipsReady = { frameRateChips = it }
            ) { index ->
                val handled = frameRateSelected
                if (handled != null) {
                    // 探测回来之后，帧率与格式、编码器同属一套联动，写偏好交给那边统一做。
                    handled(index)
                } else {
                    FableSolTuning.setExportFrameRateCap(ctx, rateOrder[index])
                }
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
        // 漫反射白只有 PQ 系用得到（HLG 是相对亮度，没有绝对锚点），所以先造出来、
        // 默认藏着，等格式定下来再决定露不露。
        var setWhiteSliderValue: ((Float) -> Unit)? = null
        val whiteRow: View = makeExportSliderRow(
            ctx,
            getString(R.string.fablesol_param_export_pq_white),
            FableSolExportOptions.MIN_PQ_WHITE_NITS,
            FableSolExportOptions.MAX_PQ_WHITE_NITS,
            PQ_WHITE_STEPS,
            FableSolTuning.exportPqWhiteNits(ctx),
            { value -> String.format(java.util.Locale.US, "%.0f nits", value) },
            onValueSetterReady = { setter -> setWhiteSliderValue = setter }
        ) { value ->
            FableSolTuning.setExportPqWhiteNits(ctx, value)
            refreshEstimate()
        }
        whiteRow.visibility = View.GONE

        // 「高光起点」只有 HDR10+ 用得到——只有它带色调映射曲线。
        val highlightRow: View = makeExportSliderRow(
            ctx,
            getString(R.string.fablesol_param_export_highlight_start),
            FableSolExportHdr10PlusCurve.MIN_HIGHLIGHT_START_PERCENT.toFloat(),
            FableSolExportHdr10PlusCurve.MAX_HIGHLIGHT_START_PERCENT.toFloat(),
            FableSolExportHdr10PlusCurve.MAX_HIGHLIGHT_START_PERCENT -
                FableSolExportHdr10PlusCurve.MIN_HIGHLIGHT_START_PERCENT,
            FableSolTuning.exportHighlightStart(ctx).toFloat(),
            { value -> String.format(java.util.Locale.US, "%.0f%%", value) }
        ) { value ->
            FableSolTuning.setExportHighlightStart(ctx, value.toInt())
            refreshEstimate()
        }
        highlightRow.visibility = View.GONE

        // 不再单独给一个"导出 HDR 视频"开关：那个开关与下面的格式选择说的是同一件事，
        // 摆两处只会让人问"关掉开关但选了 HDR10 会怎样"。格式列表里第一项就是「关闭」。
        val diagnostics = addHdrFormatBlock(
            container,
            ctx,
            { frameRateChips },
            { handler -> frameRateSelected = handler }
        ) { format ->
            whiteRow.visibility =
                if (format?.transfer == FableSolExportTransfer.PQ) View.VISIBLE else View.GONE
            highlightRow.visibility =
                if (format == FableSolExportHdrFormat.HDR10_PLUS) View.VISIBLE else View.GONE
            mResolvedExportPqFormat = format?.takeIf {
                it.transfer == FableSolExportTransfer.PQ
            }
            refreshEstimate()
        }
        container.addView(whiteRow)
        container.addView(highlightRow)

        mRefreshExportDerivedInfo = { strength ->
            if (FableSolTuning.isExportPqWhiteAutomatic(ctx)) {
                setWhiteSliderValue?.invoke(
                    FableSolTuning.exportPqWhiteNits(ctx, strength)
                )
            }
            refreshEstimate(strength)
        }
        refreshModeRows()
        container.addView(estimate)
        // 编码器清单放在指示性文字之后：那是排查用的细节，不该挡在结论前面。
        container.addView(diagnostics)

        // 这两段是排查用的原始材料，长按任意一段复制两段全文。此前只能靠截图往外传，
        // 一屏放不下就得截好几张，还没法搜索。
        val copyReport = View.OnLongClickListener {
            copyExportReport(
                ctx,
                listOf(estimate.text, diagnostics.text)
                    .mapNotNull { line -> line?.toString()?.takeIf { it.isNotBlank() } }
                    .joinToString(System.lineSeparator())
            )
        }
        for (view in listOf(estimate, diagnostics)) {
            view.isLongClickable = true
            view.setOnLongClickListener(copyReport)
        }
        val copyHint = TextView(ctx)
        copyHint.setText(R.string.fablesol_export_diagnostics_copy_hint)
        copyHint.textSize = 11f
        copyHint.alpha = 0.45f
        copyHint.setPadding(dp(20f), 0, dp(20f), dp(10f))
        container.addView(copyHint)
    }

    /** @return true 表示已消费这次长按（复制成功与否都不该把手势漏给下层）。 */
    private fun copyExportReport(ctx: Context, text: String): Boolean {
        if (text.isBlank()) return true
        try {
            val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return true
            manager.setPrimaryClip(
                ClipData.newPlainText(
                    getString(R.string.fablesol_group_export), text
                )
            )
            Toast.makeText(ctx, R.string.success_clipboard, Toast.LENGTH_SHORT).show()
        } catch (ignored: Throwable) {
            // 剪贴板在部分受限环境下不可用；长按仍然算被消费掉，不做别的反应。
        }
        return true
    }

    /**
     * 二选一的档位行：标签在左，若干个可点的圆角标签在右。
     *
     * 选中项用强调色填底，文字取 [BackgroundUtil.onColor]——它按底色明暗自动给偏黑或偏白，
     * 而不是固定白字（浅色强调色上固定白字读不出来）。触摸涟漪与面板其余控件同源，
     * 换色时随 [applyUiAccent] 一起刷新。
     */
    /**
     * HDR 输出格式选择块：标题、按**实测能力**动态生成的胶囊、随选择变化的说明，以及
     * 设备能力诊断。
     *
     * 胶囊不能在这里就摆好：哪些格式真的能用，只有在后台**实际编出一帧**之后才知道。
     * `MediaCodecList` 广告支持而 `configure()` 时静默降级的情况是存在的，照广告摆选项
     * 等于让用户选一个其实不成立的东西。所以先占位，探测回来再填。
     */
    private fun addHdrFormatBlock(
        container: LinearLayout,
        ctx: Context,
        /** 帧率胶囊的句柄；它比本块先造出来，可用状态却要按本块的选择刷新。 */
        frameRateChips: () -> ChoiceChips?,
        /** 把帧率行的点击接进三条轴的联动；参数是胶囊下标。 */
        onFrameRateSelected: (((Int) -> Unit) -> Unit),
        /** 参数是这次导出最终落到的格式；null = 关闭（SDR）。 */
        onFormatChanged: (FableSolExportHdrFormat?) -> Unit
    ): TextView {
        val formatBlock = makeCapabilityBlock(
            ctx, getString(R.string.fablesol_param_export_hdr_format)
        )
        container.addView(formatBlock.root)
        val codecBlock = makeCapabilityBlock(
            ctx, getString(R.string.fablesol_param_export_codec)
        )
        container.addView(codecBlock.root)

        // 设备实际提供了什么。**不加进 block**：调用方要把它放到指示性文字之后，
        // 那是排查用的细节，不该挡在结论前面。
        val diagnostics = TextView(ctx)
        diagnostics.textSize = 11f
        diagnostics.alpha = 0.55f
        diagnostics.setPadding(dp(20f), 0, dp(20f), dp(10f))
        diagnostics.setText(R.string.fablesol_export_diagnostics_loading)

        val appContext = ctx.applicationContext
        val generation = mHdrCapabilityGeneration
        // **必须延后到首帧之后**（D24）。这里跑的是真实编码探测，会创建 codec 与 EGL；
        // 与 Dialog 首帧、实时预览初始化抢 GPU 和 codec 会让第一次打开明显卡顿。
        // 删掉旧的 HDR 开关时把这条约束一起弄丢了，这里补回来。
        val probe = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val formats = try {
                FableSolHdrExportCapability.supportedFormats(appContext)
            } catch (ignored: Throwable) {
                emptyList()
            }
            val matrix = FableSolHdrExportCapability.lastMatrix
            val report = try {
                FableSolHdrExportCapability.diagnostics(appContext)
            } catch (error: Throwable) {
                error.message ?: error.javaClass.simpleName
            }
            formatBlock.chipsHost.post {
                if (!isAdded || generation != mHdrCapabilityGeneration) return@post
                diagnostics.text = report
                populateExportCapabilityChips(
                    appContext = appContext,
                    formatBlock = formatBlock,
                    codecBlock = codecBlock,
                    frameRateChips = frameRateChips(),
                    onFrameRateSelected = onFrameRateSelected,
                    formats = formats,
                    matrix = matrix,
                    onFormatChanged = onFormatChanged
                )
            }
        }, "FableSolExportDiagnostics").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
        formatBlock.chipsHost.postDelayed({
            // Dialog 已经关掉就不必再探；下一次打开会自己重来。
            if (isAdded && generation == mHdrCapabilityGeneration) probe.start()
        }, HDR_CAPABILITY_PROBE_DELAY_MS)
        return diagnostics
    }

    /** 标题 + 胶囊容器 + 说明这一套结构，HDR 格式与编码器两块完全一致。 */
    private class CapabilityBlock(
        val root: LinearLayout,
        val chipsHost: LinearLayout,
        val description: TextView
    )

    /** 导出那三条互相约束的轴；[reconcile] 用它记住"刚动过的是哪一条"。 */
    private enum class Axis { FORMAT, CODEC, RATE }

    /** 帧率胶囊的排列顺序（低在前），与矩阵里从高到低的遍历顺序相反。 */
    private val rateOrder = listOf(
        FableSolExportOptions.FRAME_RATE_BASE, FableSolExportOptions.FRAME_RATE_HIGH
    )

    private fun makeCapabilityBlock(ctx: Context, title: String): CapabilityBlock {
        val block = LinearLayout(ctx)
        block.orientation = LinearLayout.VERTICAL
        block.setPadding(dp(20f), dp(6f), dp(20f), dp(10f))

        val titleView = TextView(ctx)
        titleView.text = title
        titleView.textSize = 13f
        block.addView(titleView)

        val chipsHost = LinearLayout(ctx)
        chipsHost.orientation = LinearLayout.VERTICAL
        block.addView(chipsHost, stackedBlockParams(dp(8f)))

        val description = TextView(ctx)
        description.textSize = 11f
        description.alpha = 0.62f
        description.setText(R.string.fablesol_export_hdr_format_probing)
        block.addView(description, stackedBlockParams(dp(8f)))
        return CapabilityBlock(block, chipsHost, description)
    }

    private fun stackedBlockParams(topMarginPx: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = topMarginPx }

    /**
     * 胶囊列表：**关闭 / 自动 / 各实测通过的格式**。
     *
     * 「关闭」就是原来那个单独的"导出 HDR 视频"开关——把它并进来，是因为开关与格式选择说的
     * 是同一件事，分成两处只会让人问"关掉开关但选了 HDR10 会怎样"。设备一种 HDR 格式都编不出
     * 来时，列表里只剩「关闭」，也就不需要再额外解释"为什么开关是灰的"。
     */
    private fun populateExportCapabilityChips(
        appContext: Context,
        formatBlock: CapabilityBlock,
        codecBlock: CapabilityBlock,
        frameRateChips: ChoiceChips?,
        onFrameRateSelected: (((Int) -> Unit) -> Unit),
        formats: List<FableSolExportHdrFormat>,
        // 「自动」的落点由本函数按当前编码器现算（见 autoFormatFor）；探测那个不带编码器
        // 约束的全局值不能直接拿来显示。
        matrix: FableSolExportCapabilityMatrix,
        onFormatChanged: (FableSolExportHdrFormat?) -> Unit
    ) {
        val ctx = formatBlock.chipsHost.context
        formatBlock.chipsHost.removeAllViews()
        codecBlock.chipsHost.removeAllViews()

        // 即使一种格式都编不出来，也要照常走完下面的 apply()——那会把界面收敛到「关闭」
        // 并刷新指示性文字；直接 return 会让界面停在"检测中"。
        // null 代表「关闭」这一项；其余项对应一个具体偏好。
        val formatChoices = ArrayList<FableSolExportOptions.HdrFormatPreference?>(
            formats.size + 2
        )
        val formatLabels = ArrayList<String>(formats.size + 2)
        formatChoices += null
        formatLabels += getString(R.string.fablesol_export_hdr_format_off)
        if (formats.isNotEmpty()) {
            formatChoices += FableSolExportOptions.HdrFormatPreference.AUTO
            formatLabels += getString(R.string.fablesol_export_hdr_format_auto)
            for (format in formats) {
                formatChoices += FableSolExportOptions.HdrFormatPreference.of(format)
                formatLabels += format.displayName(ctx)
            }
        }

        // 整机一个组合都编不出来的编码器族不摆出来：那不是"当前选择下不可用"，而是根本
        // 不存在，摆一个永远灰着的胶囊只会让人以为选错了别的东西。
        val codecChoices = ArrayList<FableSolExportOptions.CodecPreference>(4)
        val codecLabels = ArrayList<String>(4)
        codecChoices += FableSolExportOptions.CodecPreference.AUTO
        codecLabels += getString(R.string.fablesol_export_hdr_format_auto)
        for (family in FableSolExportCodecFamily.entries) {
            if (!matrix.hasUsable(family = family)) continue
            codecChoices += FableSolExportOptions.CodecPreference.of(family)
            codecLabels += family.stableLabel
        }

        // **能力不回写偏好。** 这里曾经在设备编不出 HDR 时写一次
        // `setExportHdrEnabled(false)`，而那个写入不可逆：偏好一旦为 false，之后即便探测
        // 重新通过，界面仍以偏好为准落在「关闭」上，于是又写一次 false。三星 Z Fold4 上
        // 实际发生过（2026-07-27）。能力只允许影响本次显示。
        val hdrOn = FableSolTuning.exportHdrEnabled(appContext) && formats.isNotEmpty()
        val storedFormat = FableSolTuning.exportHdrFormat(appContext)
        val storedCodec = FableSolTuning.exportCodec(appContext)
        var formatIndex = if (hdrOn) {
            formatChoices.indexOf(storedFormat).takeIf { it >= 1 } ?: 1
        } else {
            0
        }
        var codecIndex = codecChoices.indexOf(storedCodec).takeIf { it >= 0 } ?: 0

        // 帧率行的胶囊顺序是 60、120，与 FRAME_RATES（从高到低）相反，单独记一份免得记混。
        var rateIndex = if (
            FableSolTuning.exportFrameRateCap(appContext) >= FableSolExportOptions.FRAME_RATE_HIGH
        ) {
            1
        } else {
            0
        }

        var formatChips: ChoiceChips? = null
        var codecChips: ChoiceChips? = null

        fun formatFilterOf(
            preference: FableSolExportOptions.HdrFormatPreference?
        ): FableSolExportCapabilityMatrix.FormatFilter = when {
            // 「关闭」只看 SDR；「自动」HDR 与 SDR 都可能落到，所以不限。
            preference == null ->
                FableSolExportCapabilityMatrix.FormatFilter.Exactly(null)
            preference.format == null ->
                FableSolExportCapabilityMatrix.FormatFilter.Unrestricted
            else ->
                FableSolExportCapabilityMatrix.FormatFilter.Exactly(preference.format)
        }

        /**
         * 「自动」在**当前编码器选择下**会落到哪种格式。
         *
         * 必须带上编码器这条约束。此前这里直接用探测得出的全局 `autoFormat`，那是"编码器也
         * 取自动"时的答案：OPPO 上把编码器钉成 AV1 之后，格式胶囊已经正确地只留下 HDR10 与
         * HLG，说明文字却仍然写着「当前为 HDR10+」——而 AV1 根本编不出 HDR10+
         * （2026-07-27）。顺序与导出阶梯一致，取第一个在该编码器下成立的格式。
         */
        fun autoFormatFor(
            codec: FableSolExportOptions.CodecPreference
        ): FableSolExportHdrFormat? =
            matrix.autoFormat(codec.family, allowSoftware = codec.allowsSoftware)

        /**
         * 这个三元组在本机成不成立。
         *
         * **帧率是硬约束，不再是"上限，不行就自己降"。** 那样的语义会让界面同时摆出三个各自
         * 看着都合理、合起来却不成立的选择：Z Fold4 上选了 120fps 仍可选 AV1，点下去帧率被
         * 悄悄改成 60；HDR10 与 HLG 也一样摆着，而它们在 120fps 下根本没有通路
         * （用户 2026-07-28 指出）。
         */
        fun feasible(
            formatPreference: FableSolExportOptions.HdrFormatPreference?,
            codec: FableSolExportOptions.CodecPreference,
            frameRate: Int
        ): Boolean = matrix.hasUsable(
            format = formatFilterOf(formatPreference),
            family = codec.family,
            frameRate = frameRate,
            allowSoftware = codec.allowsSoftware
        )

        fun formatEnabled(
            choice: FableSolExportOptions.HdrFormatPreference?,
            codec: FableSolExportOptions.CodecPreference,
            frameRate: Int
        ): Boolean = when {
            // 「关闭」永远可选：SDR 是所有降级的终点。
            choice == null -> true
            // 「自动」不使用软件编码器，所以它要求这台机器在这个帧率下有硬件 HDR 通路。
            choice.format == null -> matrix.hasUsable(
                format = FableSolExportCapabilityMatrix.FormatFilter.AnyHdr,
                family = codec.family,
                frameRate = frameRate,
                allowSoftware = codec.allowsSoftware
            )
            // 具体格式则允许软件实现：用户点它就是明确要这种格式，编码器会随之迁到唯一能编
            // 它的那一族，并在界面上标明是软件编码。
            else -> matrix.hasUsable(
                format = FableSolExportCapabilityMatrix.FormatFilter.Exactly(choice.format),
                family = codec.family,
                frameRate = frameRate,
                allowSoftware = true
            )
        }

        fun codecEnabled(
            choice: FableSolExportOptions.CodecPreference,
            formatPreference: FableSolExportOptions.HdrFormatPreference?,
            frameRate: Int
        ): Boolean = feasible(formatPreference, choice, frameRate)

        /**
         * 按当前选择重算三条轴各自的可用状态。
         *
         * 每一条轴的判据都是"另外两条保持现值时这一项成不成立"。这样界面上不会同时摆出三个
         * 各自看着合理、合起来却不成立的选择；代价是从某些组合切到另一些需要按顺序点两三下，
         * 那好过点下去之后另一条轴被悄悄改掉。
         */
        fun refreshAxes() {
            // 表还没建起来时不要把界面锁死。
            if (matrix.isEmpty) return
            val codec = codecChoices[codecIndex]
            val formatPreference = formatChoices[formatIndex]
            val rate = rateOrder[rateIndex]
            formatChips?.setEnabledStates?.invoke(
                formatChoices.map { choice -> formatEnabled(choice, codec, rate) }
            )
            codecChips?.setEnabledStates?.invoke(
                codecChoices.map { choice -> codecEnabled(choice, formatPreference, rate) }
            )
            frameRateChips?.setEnabledStates?.invoke(
                rateOrder.map { feasible(formatPreference, codec, it) }
            )
        }

        /**
         * 把两条轴解析出的结论写进指示性文字。
         *
         * 编码器必须写出来：自动档最终落到哪一族、是不是软件实现，用户自己是推不出来的，
         * 而这正是"选了 120fps 却出 60fps 的软件 AV1"当初无从察觉的原因。
         */
        fun notifyResolved() {
            val choice = formatChoices[formatIndex]
            val codec = codecChoices[codecIndex]
            // 「自动」的落点随编码器变化，不能用探测得出的全局值。
            val autoForCodec = autoFormatFor(codec)
            val resolvedFormat = if (choice == null) null else choice.format ?: autoForCodec
            // 说明文字也要跟着编码器刷新：格式选「自动」时换编码器会改变落点，说明却停在
            // 上一个答案上，那正是 OPPO 上「显示当前为 HDR10+」的那一幕。
            formatBlock.description.text = hdrChoiceDescription(choice, autoForCodec)
            mResolvedExportFormat = resolvedExportFormatLabel(ctx, choice, autoForCodec)
            val cap = FableSolTuning.exportFrameRateCap(appContext)
            // 帧率与编码器要一起解出来：降级发生在同一次遍历里，分两处各算一遍迟早会得出
            // 一对互不相容的答案。
            val resolved = FableSolExportCapabilityMatrix.FRAME_RATES
                .filter { it <= cap }
                .firstNotNullOfOrNull { rate ->
                    val family = codec.family ?: matrix.autoFamily(resolvedFormat, rate)
                    val outcome = family?.let { matrix.outcome(resolvedFormat, it, rate) }
                    if (outcome?.usable != true) {
                        null
                    } else if (outcome.softwareOnly && !codec.allowsSoftware) {
                        null
                    } else {
                        // 硬件也要写出来：只标软件的话，看到没有标注的人分不清那是"硬件"
                        // 还是"这一项没解出来"。位深同理：SDR 阶梯首选也是 10 位，而 10 位
                        // HEVC 的分享兼容性明显差于 8 位。
                        val label = family.stableLabel +
                            (if (outcome.tenBit) " 10-bit" else " 8-bit") + getString(
                            if (outcome.softwareOnly) {
                                R.string.fablesol_export_codec_software_suffix
                            } else {
                                R.string.fablesol_export_codec_hardware_suffix
                            }
                        )
                        rate to label
                    }
                }
            mResolvedExportFrameRate = resolved?.first
            mResolvedExportCodec = resolved?.second
            // 编码器那段说明也要跟着刷新：选「自动」时它落到哪一族、是硬件还是软件，用户
            // 自己是推不出来的，而这正是当初"选了 120fps 却出 60fps 软件 AV1"无从察觉的原因。
            codecBlock.description.text = codecChoiceDescription(codec, resolved?.second)
            onFormatChanged(resolvedFormat)
        }

        /** @param fromUser false 表示这是初始化或迁移，不得回写偏好。 */
        fun applyFormat(index: Int, fromUser: Boolean) {
            formatIndex = index
            val choice = formatChoices[index]
            if (fromUser) {
                FableSolTuning.setExportHdrEnabled(appContext, choice != null)
                choice?.let { FableSolTuning.setExportHdrFormat(appContext, it) }
            }
            notifyResolved()
        }

        fun applyCodec(index: Int, fromUser: Boolean) {
            codecIndex = index
            if (fromUser) FableSolTuning.setExportCodec(appContext, codecChoices[index])
            // 说明文字由 notifyResolved 统一写：它要带上解析出来的实际编码器。
            notifyResolved()
        }

        fun applyRate(index: Int, fromUser: Boolean) {
            rateIndex = index
            if (fromUser) FableSolTuning.setExportFrameRateCap(appContext, rateOrder[index])
            notifyResolved()
        }

        /**
         * 把三条轴拉回一个**确实成立**的组合上。
         *
         * 三条轴互相约束，逐条贪心地修容易来回摆动；可行组合总共不过几十个，直接枚举取"与
         * 当前差得最少、且保住刚动过那条轴"的一个，结果稳定也讲得清。
         *
         * 迁移要落盘：冲突是用户自己造成的，解决冲突属于这次操作的一部分。界面显示与真正
         * 导出的组合不一致才是更糟的事。初始化时（[changed] 为 null）同样修，但不动格式
         * ——格式是明确的意图，不该因为一次探测结论被抹掉。
         */
        fun reconcile(changed: Axis?) {
            if (matrix.isEmpty) return
            if (!feasible(formatChoices[formatIndex], codecChoices[codecIndex], rateOrder[rateIndex])) {
                // 初始化时也钉住格式：它是明确的意图，不该因为一次探测结论被抹掉。
                val keepFormat = changed == Axis.FORMAT || changed == null
                val best = buildList {
                    for (format in formatChoices.indices) {
                        if (keepFormat && format != formatIndex) continue
                        for (codec in codecChoices.indices) {
                            if (changed == Axis.CODEC && codec != codecIndex) continue
                            for (rate in rateOrder.indices) {
                                if (changed == Axis.RATE && rate != rateIndex) continue
                                if (feasible(
                                        formatChoices[format], codecChoices[codec], rateOrder[rate]
                                    )
                                ) {
                                    add(Triple(format, codec, rate))
                                }
                            }
                        }
                    }
                }.minByOrNull { (format, codec, rate) ->
                    // 改动越少越好；同样多时优先保住格式，其次优先高帧率与「自动」编码器。
                    var cost = 0
                    if (format != formatIndex) cost += 4
                    if (codec != codecIndex) cost += 2
                    if (rate != rateIndex) cost += 1
                    cost * 8 + codec + (rateOrder.size - 1 - rate)
                }
                if (best != null) {
                    if (best.first != formatIndex) {
                        formatChips?.select?.invoke(best.first)
                        applyFormat(best.first, fromUser = true)
                    }
                    if (best.second != codecIndex) {
                        codecChips?.select?.invoke(best.second)
                        applyCodec(best.second, fromUser = true)
                    }
                    if (best.third != rateIndex) {
                        frameRateChips?.select?.invoke(best.third)
                        applyRate(best.third, fromUser = true)
                    }
                }
            }
            refreshAxes()
        }

        formatChips = buildChoiceChips(ctx, formatLabels, formatIndex) { index ->
            applyFormat(index, fromUser = true)
            reconcile(Axis.FORMAT)
        }
        codecChips = buildChoiceChips(ctx, codecLabels, codecIndex) { index ->
            applyCodec(index, fromUser = true)
            reconcile(Axis.CODEC)
        }
        // 帧率行早就造好了，这里把它接进同一套联动。
        onFrameRateSelected { index ->
            applyRate(index, fromUser = true)
            reconcile(Axis.RATE)
        }
        packChips(ctx, formatBlock.chipsHost, formatChips.views)
        packChips(ctx, codecBlock.chipsHost, codecChips.views)
        applyFormat(formatIndex, fromUser = false)
        applyCodec(codecIndex, fromUser = false)
        reconcile(null)
    }

    /** 胶囊按可用宽度贪心换行：最多五个选项，在 320dp 的对话框里排不下一行。 */
    private fun packChips(ctx: Context, host: LinearLayout, chips: List<TextView>) {
        val gap = dp(6f)
        val available = getDialogWindowWidthPx() - dp(40f)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var row: LinearLayout? = null
        var used = 0
        for (chip in chips) {
            chip.measure(unspecified, unspecified)
            val width = chip.measuredWidth
            val currentRow = row
            if (currentRow == null || used + gap + width > available) {
                val fresh = LinearLayout(ctx)
                fresh.orientation = LinearLayout.HORIZONTAL
                host.addView(
                    fresh,
                    stackedBlockParams(if (host.childCount > 0) gap else 0)
                )
                chip.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                fresh.addView(chip)
                row = fresh
                used = width
            } else {
                chip.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = gap }
                currentRow.addView(chip)
                used += gap + width
            }
        }
    }

    /** 这一次导出最终会落到哪种格式；写进指示性文字，不让用户自己回头去胶囊那里推。 */
    private fun resolvedExportFormatLabel(
        context: Context,
        preference: FableSolExportOptions.HdrFormatPreference?,
        auto: FableSolExportHdrFormat?
    ): String = when {
        preference == null -> FableSolExportHdrFormat.SDR_LABEL
        preference.format != null -> preference.format.displayName(context)
        else -> auto?.displayName(context) ?: FableSolExportHdrFormat.SDR_LABEL
    }

    /**
     * 编码器胶囊下面那段说明：这一档的特点、当前实际落到哪个编码器、以及置灰的含义。
     *
     * 三件事缺一不可。只写"自动会挑规格最高的"，用户看不出这台机器上究竟挑中了谁；而各个
     * 编码器之间的取舍（兼容性、压缩效率、有没有硬件实现）本来就该像 HDR 格式那样讲清楚。
     *
     * @param resolved 已带硬件/软件标注的实际编码器名；解析不出来时为 null。
     */
    private fun codecChoiceDescription(
        preference: FableSolExportOptions.CodecPreference,
        resolved: String?
    ): String {
        val builder = StringBuilder(
            getString(
                when (preference.family) {
                    null -> R.string.fablesol_export_codec_desc_auto
                    FableSolExportCodecFamily.HEVC -> R.string.fablesol_export_codec_desc_hevc
                    FableSolExportCodecFamily.AV1 -> R.string.fablesol_export_codec_desc_av1
                    FableSolExportCodecFamily.AVC -> R.string.fablesol_export_codec_desc_avc
                }
            )
        )
        resolved?.let {
            builder.append(getString(R.string.fablesol_export_codec_desc_resolved, it))
        }
        builder.append(getString(R.string.fablesol_export_codec_desc))
        return builder.toString()
    }

    /** null 就是「关闭」那一项。 */
    private fun hdrChoiceDescription(
        preference: FableSolExportOptions.HdrFormatPreference?,
        auto: FableSolExportHdrFormat?
    ): String =
        if (preference == null) {
            getString(R.string.fablesol_export_hdr_desc_off)
        } else {
            hdrFormatDescription(preference, auto)
        }

    private fun hdrFormatDescription(
        preference: FableSolExportOptions.HdrFormatPreference,
        auto: FableSolExportHdrFormat?
    ): String = when (preference.format) {
        null -> getString(
            R.string.fablesol_export_hdr_desc_auto,
            auto?.displayName(requireContext()) ?:
                getString(R.string.fablesol_export_hdr_format_auto)
        )
        FableSolExportHdrFormat.HDR10 -> getString(R.string.fablesol_export_hdr_desc_hdr10)
        FableSolExportHdrFormat.HDR10_PLUS ->
            getString(R.string.fablesol_export_hdr_desc_hdr10_plus)
        FableSolExportHdrFormat.HLG -> getString(R.string.fablesol_export_hdr_desc_hlg)
        FableSolExportHdrFormat.DOLBY_VISION_5 ->
            getString(R.string.fablesol_export_hdr_desc_dolby_5)
        FableSolExportHdrFormat.DOLBY_VISION_81 ->
            getString(R.string.fablesol_export_hdr_desc_dolby_81)
        FableSolExportHdrFormat.DOLBY_VISION_84 ->
            getString(R.string.fablesol_export_hdr_desc_dolby)
    }

    /**
     * 本 Dialog 里所有勾选行的唯一实现：整行可点、行涟漪与勾选框圆形涟漪都是渐变，
     * 勾选框**两种状态**都跟着强调背景走——未选中用完整渐变描边（不降 alpha），选中用完整
     * 渐变填充，对号按填充色明暗自适应偏黑或偏白。
     */
    private fun makeCheckRow(
        ctx: Context,
        label: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): View {
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
        checkBox.isChecked = checked
        checkBox.isClickable = false
        checkBox.isFocusable = false
        BackgroundUtil.applyCheckboxAccent(
            checkBox, mAppliedBackground, uncheckedGradient = true
        )
        mAccentCheckBoxes.add(checkBox)
        checkBox.setOnCheckedChangeListener { _, value -> onChange(value) }
        row.addView(tvLabel)
        row.addView(checkBox)
        // 圆形渐变 ripple 替换系统默认的半透明黑波纹；必须在 addView 之后调用，
        // 它要顺带关掉父容器的裁剪。换色跟随见 applyUiAccent。
        GradientRippleDrawable.applyCheckboxRipple(checkBox, mAppliedBackground)
        row.setOnClickListener { checkBox.isChecked = !checkBox.isChecked }
        return row
    }

    private fun makeExportChoiceRow(
        ctx: Context,
        label: String,
        options: List<String>,
        selectedIndex: Int,
        onChipsReady: ((ChoiceChips) -> Unit)? = null,
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

        val built = buildChoiceChips(ctx, options, selectedIndex, onSelect)
        for ((index, chip) in built.views.withIndex()) {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) lp.leftMargin = dp(6f)
            chip.layoutParams = lp
            row.addView(chip)
        }
        onChipsReady?.invoke(built)
        return row
    }

    /**
     * 一组档位胶囊的操作句柄。
     *
     * 导出那三条轴（HDR 格式、编码器、帧率）互相约束，某一轴的选择会让另两轴的部分取值
     * 不再成立，所以调用方需要在**不触发回调**的前提下改选中项与可用状态。
     */
    private class ChoiceChips(
        val views: List<TextView>,
        /** 改选中项但不回调 onSelect，用于按可行组合表迁移选择。 */
        val select: (Int) -> Unit,
        /** 逐项设置是否可选；不可选的胶囊淡出并停止响应点击。 */
        val setEnabledStates: (List<Boolean>) -> Unit
    )

    /**
     * 造一组档位胶囊并完成着色、涟漪与换色登记；调用方只负责把它们摆进容器。
     *
     * 单行布局与换行布局必须共用这段：选中态要走 `applyTextBackground` 的纯色分支才能让
     * 文字自适应黑白（渐变强调色会在 TextPaint 上留 shader，`setTextColor` 盖不住），
     * 未选中态要自绘描边才能保住完整渐变——这两件事各错一次就够难查了，不该维护两份。
     */
    private fun buildChoiceChips(
        ctx: Context,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit
    ): ChoiceChips {
        val chips = ArrayList<TextView>(options.size)
        var current = selectedIndex
        val enabled = BooleanArray(options.size) { true }
        val cornerRadius = dp(14f).toFloat()

        fun paint() {
            for ((index, chip) in chips.withIndex()) {
                // 不可选的组合必须看得出来也点不动。淡出整枚胶囊即可：填充、描边与涟漪都是
                // 渐变，逐个换色反而会把"选中"与"不可用"两种状态混在一起。
                chip.isEnabled = enabled[index]
                chip.isClickable = enabled[index]
                chip.alpha = if (enabled[index]) 1f else DISABLED_CHIP_ALPHA
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
            chip.setOnClickListener {
                if (current == index || !enabled[index]) return@setOnClickListener
                current = index
                paint()
                onSelect(index)
            }
            chips.add(chip)
        }
        paint()
        mAccentChipPainters.add(::paint)
        return ChoiceChips(
            views = chips,
            select = { index ->
                if (index in chips.indices) {
                    current = index
                    paint()
                }
            },
            setEnabledStates = { states ->
                for (index in enabled.indices) {
                    enabled[index] = states.getOrElse(index) { true }
                }
                paint()
            }
        )
    }





    private fun makeExportSliderRow(
        ctx: Context,
        label: String,
        lo: Float,
        hi: Float,
        steps: Int,
        initial: Float,
        format: (Float) -> String,
        onValueSetterReady: (((Float) -> Unit) -> Unit)? = null,
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
        onValueSetterReady?.invoke { requested ->
            val progress = (((requested - lo) / span) * steps).toInt().coerceIn(0, steps)
            if (seekBar.progress != progress) {
                seekBar.progress = progress
            } else {
                val value = lo + span * progress / steps
                tvValue.text = format(value)
            }
        }
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
        return makeCheckRow(ctx, getString(spec.labelRes), initial >= 0.5) { checked ->
            val value = if (checked) 1.0 else 0.0
            applyRuntimeTuning(spec, value)
            FableSolTuning.putValue(ctx, spec, value, defaultValue)
        }
    }

    private fun resetAllParams() {
        val ctx = mContentView?.context ?: return
        FableSolTuning.clearAllParams(ctx)
        // 2026-07-24 用户裁定：恢复默认包含 HDR 强度（回默认档 3.6），不再保留用户设置。
        FableSolTuning.clearHdrStrength(ctx)
        // 导出参数同样纳入「恢复默认」（fablesol-video-export D10）。
        FableSolTuning.clearExportOptions(ctx)
        // 倾斜跟随与 HDR 强度同属画面偏好，一并复位。
        FableSolTuning.clearLiveTilt(ctx)
        for (group in FableSolTuning.GROUPS) {
            for (spec in group.specs) {
                applyRuntimeTuning(spec, mDefaults.get(spec.key))
            }
        }
        applyHdrPreference()
        applyLiveTiltPreference()
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

    /**
     * 预览的倾斜跟随开关状态即时切换。关掉时要显式把容器重力推回竖直——传感器已经停了，
     * 不会再有回调替我们把水面扶正。
     */
    private fun applyLiveTiltPreference() {
        val ctx = mContentView?.context ?: mActivity ?: return
        if (FableSolTuning.liveTiltEnabled(ctx)) {
            startTiltSensor()
        } else {
            stopTiltSensor()
            mVisualizer?.setContainerGravity(0f, 1f, 0f)
        }
    }

    private fun startTiltSensor() {
        val manager = mSensorManager ?: return
        val sensor = mGravitySensor ?: return
        if (mTiltSensorRegistered) return
        val ctx = mContentView?.context ?: mActivity ?: return
        if (!FableSolTuning.liveTiltEnabled(ctx)) return
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
        /** 漫反射白滑杆：200–800 尼特，每档 25。 */
        /** 真实编码探测要等 Dialog 首帧过去再跑，否则第一次打开会卡（D24）。 */
        private const val HDR_CAPABILITY_PROBE_DELAY_MS = 800L

        private const val PQ_WHITE_STEPS = 24
        private const val CHOICE_OUTLINE_ALPHA = 96
        /** 该组合在本机未通过验证时的胶囊透明度。 */
        private const val DISABLED_CHIP_ALPHA = 0.32f
        /** 与顶部“HDR 高光增强（设备不支持）”标签完全一致的不可用态透明度。 */
        /** 避开 Dialog 首帧、录音预览与 GL 预热，随后才允许后台真实编码。 */

        /** 角标图标不透明度（0-255）：亮色模式下避免实黑，浮在水面上更轻。 */
        private const val PREVIEW_BUTTON_ICON_ALPHA = 176

        /** HDR 强度滑杆：1.0～9.6、步长 0.05（172 步），默认=上限 9.6（第 172 格）。 */
        private const val HDR_STRENGTH_STEPS = 172
    }

}
