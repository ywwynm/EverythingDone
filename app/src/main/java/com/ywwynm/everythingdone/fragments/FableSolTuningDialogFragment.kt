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
import android.util.Range
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
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportColorMode
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportDisplayLuminance
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportInputPath
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportPqWhiteMode
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportRateControl
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportReferencePeak
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportBitrateModel
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportCombinationOutcome
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportHlgSignalRange
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportHlgVerification
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportRateControlForm
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportSpec
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportTier
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportSdrBitDepth
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportSdrMapping
import com.ywwynm.everythingdone.views.recording.fablesol.fableSolExportQualitySignature
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
    /** 「视频导出」组头；导出失败入口带 [KEY_SCROLL_TO_EXPORT] 打开时滚动到这里（D107）。 */
    private var mExportGroupHeader: View? = null
    /** 「色调映射方式」的胶囊句柄；动态统计探测不过时把"动态映射"置灰（D77）。 */
    private var mMappingChips: ChoiceChips? = null
    /**
     * 动态映射统计通路的已知图探测结论（D77）。探测回来之前按可用处理；false 只置灰选项
     * 并在说明行写明原因，不改写用户保存的映射偏好。
     */
    private var mDynamicSdrStatsSupported = true

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
     * 当前精确组合已验证可用的帧率；与用户选择必须一致（D179）。
     */
    private var mResolvedExportFrameRate: Int? = null
    /** 解析出的格式若是 PQ 系，指示行还要带上漫反射白与峰值。 */
    private var mResolvedExportPqFormat: FableSolExportHdrFormat? = null
    /** 解析出的格式若是 HLG 系（含杜比视界 8.4），指示行要说明信号范围的两种取舍。 */
    private var mResolvedExportHlgFormat: FableSolExportHdrFormat? = null
    /**
     * 当前组合下 D147 推导出的自动目标码率（Mbps）；解析不出候选时为 null。
     *
     * 码率滑杆在自动态下跟着它走：组合一变（换编码器族、换位深、换帧率），自动值随之更新。
     */
    private var mResolvedExportAutoBitrateMbps: Float? = null
    /** 当前解析出的编码器族；B 帧是否适用按它判断（D148）。 */
    private var mResolvedExportCodecFamily: FableSolExportCodecFamily? = null
    /**
     * 当前解析出的候选是否落在 H.264 Baseline——B 帧不适用的第二种具体情形（D148）：
     * 该 Profile 的语法不含 B 片。判据是阶梯稳定标签（"H.264 Baseline SDR"），非本地化文本。
     */
    private var mResolvedExportAvcBaseline = false
    /** 当前解析出的矩阵行；信号范围预测（D135）按它重建候选签名。 */
    private var mResolvedExportOutcome: FableSolExportCombinationOutcome? = null
    /** 当前解析出的位深；与 [mResolvedExportCodecFamily] 一起收窄预测用的候选枚举。 */
    private var mResolvedExportTenBit: Boolean? = null
    /**
     * 当前解析出的候选签名，CQ 自定义原值按它分别保存（D146）。
     *
     * 尚未解析出候选时为 null，此时质量值写进"待归属"槽位，第一次真正解析出候选时再绑定。
     */
    private var mResolvedExportQualitySignature: String? = null
    /** 当前完整规格在 CQ 模式下实测通过的质量区间；不得借用其它编码路径的代表值（D183）。 */
    private var mResolvedExportQualityRange: Range<Int>? = null
    /** 完整五维能力矩阵是否已经返回；用于区分“检测中”与“当前组合不支持 CQ”。 */
    private var mExportCapabilityLoaded = false
    /** HDR 强度拖动时，让导出组的推导文字同步预览。 */
    private var mRefreshExportDerivedInfo: ((Float) -> Unit)? = null
    /**
     * 改动色调映射方式或视频位深之后重写色彩模式那段信息栏。
     *
     * D65 与 D160 都要求信息栏随选择解释当前档位的取舍，而不是只显示档位名；两个二级控件
     * 都写在同一段信息栏里，所以改任一项都要重算那一段。
     */
    private var mRefreshExportColorModeInfo: (() -> Unit)? = null
    /** 自动目标码率变化时让码率滑杆跟上；用户已经拖过滑杆时这个回调什么都不做。 */
    private var mRefreshExportAutoBitrate: (() -> Unit)? = null
    /** 刷新挂在各个选项下面的说明行；与 `refreshEstimate` 同步调用。 */
    private var mRefreshExportOptionNotes: (() -> Unit)? = null
    /** 编码模式或完整规格变化后，按实测 CQ 区间重建对应参数行。 */
    private var mRefreshExportModeRows: (() -> Unit)? = null

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
        // 导出失败的「调整导出设置」入口要求定位到导出组（D107）。只在首次创建时滚动：
        // 旋转重建带着 savedInstanceState，不该把用户已经滚到别处的位置拽回来。
        if (savedInstanceState == null &&
            arguments?.getBoolean(KEY_SCROLL_TO_EXPORT) == true
        ) {
            scrollToExportGroup()
        }

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
        mRefreshExportColorModeInfo = null
        mRefreshExportAutoBitrate = null
        mRefreshExportOptionNotes = null
        mRefreshExportModeRows = null
        mResolvedExportQualityRange = null
        mExportCapabilityLoaded = false
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
        mRefreshExportColorModeInfo = null
        mRefreshExportAutoBitrate = null
        mRefreshExportOptionNotes = null
        mRefreshExportModeRows = null
        mResolvedExportQualityRange = null
        mExportCapabilityLoaded = false
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
        val header = makeGroupHeader(ctx, getString(R.string.fablesol_group_export))
        mExportGroupHeader = header
        container.addView(header)

        val estimate = TextView(ctx)
        estimate.textSize = 12f
        estimate.alpha = 0.6f
        estimate.setPadding(dp(20f), dp(2f), dp(20f), 0)

        fun refreshEstimate(
            strength: Float = FableSolTuning.hdrStrength(ctx)
        ) {
            // 已解析帧率与用户选择必须一致；尚未完成能力解析时才读取持久化选择。
            val frameRate = mResolvedExportFrameRate ?: FableSolTuning.exportFrameRate(ctx)
            val options = FableSolExportOptions.read(ctx)
            val exactUnavailable = mExportCapabilityLoaded && mResolvedExportFrameRate == null
            val base = when {
                options.prefersConstantQuality && !mExportCapabilityLoaded ->
                    getString(R.string.fablesol_export_hdr_format_probing)
                exactUnavailable ->
                    getString(R.string.fablesol_export_no_exact_specification, frameRate)
                options.prefersConstantQuality && mResolvedExportQualityRange != null ->
                    getString(R.string.fablesol_export_estimate_quality, frameRate.toString())
                else -> {
                    // 体积估算读**解析后的**目标码率：自动态跟着当前组合走，自定义态就是用户
                    // 拖到的那个绝对值（D147）。
                    val mbps = options.bitrateMbps
                        ?: mResolvedExportAutoBitrateMbps
                        ?: FableSolExportOptions.DEFAULT_BITRATE_MBPS
                    val megabytesPerMinute = mbps * 1_000_000.0 * 60.0 / 8.0 / 1_000_000.0
                    getString(
                        R.string.fablesol_export_estimate_bitrate,
                        String.format(java.util.Locale.US, "%.0f", megabytesPerMinute),
                        frameRate.toString()
                    )
                }
            }
            // 这一行是这一组设置的**推导结果**：不只写落到哪种格式，还要把由它派生出来的
            // 关键数值一并摆出来——尤其是**峰值**。峰值 = 漫反射白 × HDR 强度，两个滑杆
            // 各调各的，很容易在不知不觉间把峰值推到屏幕根本装不下的量级（那正是画面
            // 发白、掉饱和的来源），所以必须让这个乘积可见。
            val pieces = StringBuilder(base)
            if (!exactUnavailable) {
                pieces.append(" · ").append(mResolvedExportFormat ?: "…")
                mResolvedExportCodec?.let { pieces.append(" · ").append(it) }
                val pqFormat = mResolvedExportPqFormat
                if (pqFormat != null) {
                    // 漫反射白是与导出设备无关的创作基准（D82/D83）：默认就是标准 203 尼特，
                    // 不再由本机屏幕峰值推导。屏幕能力只在下面作为播放参考列出。
                    val white = FableSolTuning.exportPqWhiteNits(ctx)
                    val peak = white * strength
                    pieces.append(
                        getString(
                            R.string.fablesol_export_estimate_white,
                            String.format(java.util.Locale.US, "%.0f", white),
                            String.format(java.util.Locale.US, "%.0f", peak)
                        )
                    )
                    if (pqFormat == FableSolExportHdrFormat.HDR10_PLUS) {
                        val referencePeak = FableSolTuning.exportReferenceDisplayPeakNits(ctx)
                        pieces.append(
                            getString(
                                R.string.fablesol_export_estimate_reference_peak,
                                String.format(java.util.Locale.US, "%.0f", referencePeak)
                            )
                        )
                        pieces.append(
                            getString(
                                R.string.fablesol_export_estimate_highlight,
                                FableSolTuning.exportHighlightStart(ctx)
                            )
                        )
                    }
                }
            }
            // **信息栏只留这一组设置的推导结论。** 每个选项自己的取舍说明已经挂在它那一行
            // 下面了（`mRefreshExportOptionNotes`）——用户改的是哪一行，就该在那一行下面读到
            // 为什么，而不是滚到屏幕外几屏之下的一段长文里去找。
            mRefreshExportOptionNotes?.invoke()
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
        // CQ 区间必须来自当前完整规格实际探测通过的具体编码器，不能在 Dialog 构建时用另一条
        // HEVC／AV1 路径的代表值。规格变化后按新签名和新区间重建这一行（D146、D183）。
        val qualityHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var renderedQualityKey: String? = null
        fun refreshQualityRow() {
            val constant = FableSolTuning.exportRateControl(ctx) ==
                FableSolExportRateControl.CONSTANT_QUALITY
            val range = mResolvedExportQualityRange
            val signature = mResolvedExportQualitySignature
            if (!constant || range == null || signature == null) {
                qualityHost.visibility = View.GONE
                qualityHost.removeAllViews()
                renderedQualityKey = null
                return
            }
            qualityHost.visibility = View.VISIBLE
            val key = "$signature|${range.lower}|${range.upper}"
            if (renderedQualityKey == key && qualityHost.childCount > 0) return
            renderedQualityKey = key
            qualityHost.removeAllViews()
            val lower = range.lower
            val upper = range.upper
            qualityHost.addView(
                makeExportSliderRow(
                    ctx,
                    getString(R.string.fablesol_param_export_quality_value),
                    lower.toFloat(),
                    upper.toFloat(),
                    (upper - lower).coerceAtLeast(1),
                    FableSolExportOptions.read(ctx)
                        .resolveWithin(range, signature)
                        .toFloat(),
                    { value ->
                        String.format(
                            java.util.Locale.US,
                            "%.0f  (%d-%d)",
                            value,
                            lower,
                            upper
                        )
                    }
                ) { value ->
                    FableSolTuning.setExportQualityValue(ctx, signature, value.toInt())
                }
            )
        }
        // 码率滑杆有"自动"与"自定义"两个状态，但**不新增控件**（D147）：自动态就是没保存过
        // 这个键，滑杆位置跟着当前组合的自动推导值走；用户一拖就成为绝对 Mbps 的自定义值，
        // 现有的"恢复默认"删键之后自然回到自动态。状态差别只体现在数值旁边的那行字。
        var bitrateSetter: ((Float) -> Unit)? = null
        val bitrateRow: View = makeExportSliderRow(
            ctx,
            getString(R.string.fablesol_param_export_bitrate),
            FableSolExportOptions.MIN_BITRATE_MBPS,
            FableSolExportOptions.MAX_BITRATE_MBPS,
            58,
            FableSolTuning.exportBitrateMbps(ctx)
                ?: mResolvedExportAutoBitrateMbps
                ?: FableSolExportOptions.DEFAULT_BITRATE_MBPS,
            { value ->
                val unit = String.format(java.util.Locale.US, "%.0f Mbps", value)
                if (FableSolTuning.exportBitrateMbps(ctx) == null) {
                    getString(R.string.fablesol_export_bitrate_auto, unit)
                } else {
                    unit
                }
            },
            onValueSetterReady = { setter -> bitrateSetter = setter }
        ) { value ->
            FableSolTuning.setExportBitrateMbps(ctx, value)
            refreshEstimate()
        }
        mRefreshExportAutoBitrate = {
            // 只在自动态跟随：用户已经拖过滑杆之后，换分辨率或帧率不得再按比例改写它（D147）。
            if (FableSolTuning.exportBitrateMbps(ctx) == null) {
                mResolvedExportAutoBitrateMbps?.let { bitrateSetter?.invoke(it) }
            }
            refreshEstimate()
        }

        // 每个选项自己的说明行。它们跟着 refreshEstimate 一起刷新——那是所有轴变化都会走到的
        // 那一处，另立一条刷新路径迟早会有一条忘记调用。
        val rateControlNote = makeExportNote(ctx)
        val qpGuardNote = makeExportNote(ctx)
        val complexityNote = makeExportNote(ctx)
        val bFrameNote = makeExportNote(ctx)
        val keyframeNote = makeExportNote(ctx)
        val whiteNote = makeExportNote(ctx)
        val referencePeakNote = makeExportNote(ctx)
        val highlightNote = makeExportNote(ctx)
        mRefreshExportOptionNotes = {
            val current = FableSolExportOptions.read(ctx)
            val constantQuality =
                current.prefersConstantQuality && mResolvedExportQualityRange != null
            rateControlNote.setNote(
                when {
                    constantQuality -> getString(R.string.fablesol_export_desc_cq)
                    current.prefersConstantQuality && mExportCapabilityLoaded ->
                        getString(
                            R.string.fablesol_export_no_exact_specification,
                            current.frameRate
                        )
                    current.prefersConstantQuality ->
                        getString(R.string.fablesol_export_hdr_format_probing)
                    current.bitrateMbps == null ->
                        getString(R.string.fablesol_export_desc_vbr_auto)
                    else -> getString(R.string.fablesol_export_desc_vbr_custom)
                }
            )
            // **三条说明都常显，不随开关显隐。** 说明的用途是让人知道这一项在做什么；只在
            // 打开时才出现，等于恰好在用户想弄清"要不要打开"的那一刻把话收走了。
            //
            // 但"常显"只针对开关的**开／关**，不针对选项**在不在**：复杂帧质量保护只作用于
            // 目标码率（D151），恒定质量下 `qpGuardRow` 是 GONE 的，说明必须跟着收起，
            // 否则界面上会出现一段没有归属的文字（2026-07-30 用户反馈）。判据与
            // `refreshModeRows` 里那一行的显隐条件必须同源。
            qpGuardNote.setNote(
                if (current.prefersConstantQuality) {
                    ""
                } else {
                    getString(R.string.fablesol_export_desc_qp_guard)
                }
            )
            complexityNote.setNote(getString(R.string.fablesol_export_desc_high_complexity))
            bFrameNote.setNote(
                buildString {
                    append(getString(R.string.fablesol_export_desc_b_frames))
                    // 不适用时按**具体原因**补一句（D148）：系统级限制盖过族判断；
                    // 「自动」要到解析出候选才知道落点，未解析时不预告"不适用"。
                    val family = mResolvedExportCodecFamily
                    when {
                        // Android 8～9 没有 KEY_MAX_B_FRAMES：导出恒不申请 B 帧，
                        // 并以低延迟约束阻止编码器自行产生（D148）。
                        Build.VERSION.SDK_INT < 29 ->
                            append(getString(R.string.fablesol_export_desc_b_frames_api))
                        family == FableSolExportCodecFamily.AV1 ->
                            append(getString(R.string.fablesol_export_desc_b_frames_av1))
                        family == FableSolExportCodecFamily.AVC &&
                            mResolvedExportAvcBaseline ->
                            append(
                                getString(R.string.fablesol_export_desc_b_frames_baseline)
                            )
                    }
                }
            )
            keyframeNote.setNote(getString(R.string.fablesol_export_desc_keyframe))

            // 漫反射白：标准/自定义的语义，以及本机屏幕亮度**作为观看参考**的读数（D82、D84）。
            // 后者必须明确标成设备诊断，不能读成默认导出参数。
            val pqFormat = mResolvedExportPqFormat
            // 高光起点只对 HDR10+ 成立——只有它带场景级色调映射曲线（D43、D177）。
            highlightNote.setNote(
                if (pqFormat == FableSolExportHdrFormat.HDR10_PLUS) {
                    getString(R.string.fablesol_export_desc_highlight_start)
                } else {
                    ""
                }
            )
            if (pqFormat == null) {
                whiteNote.setNote("")
                referencePeakNote.setNote("")
            } else {
                fun luminanceCapability(value: Float?): String =
                    value?.let {
                        getString(
                            R.string.fablesol_export_estimate_luminance_nits,
                            FableSolExportDisplayLuminance.formatDerivationNumber(it)
                        )
                    } ?: getString(R.string.fablesol_export_estimate_luminance_unavailable)

                val display = FableSolExportDisplayLuminance.read(ctx)
                whiteNote.setNote(
                    buildString {
                        append(
                            getString(
                                when (FableSolTuning.exportPqWhiteMode(ctx)) {
                                    FableSolExportPqWhiteMode.STANDARD ->
                                        R.string.fablesol_export_estimate_white_standard
                                    FableSolExportPqWhiteMode.CUSTOM ->
                                        R.string.fablesol_export_estimate_white_custom
                                },
                                FableSolExportDisplayLuminance.formatDerivationNumber(
                                    FableSolExportOptions.DEFAULT_PQ_WHITE_NITS
                                ),
                                luminanceCapability(display.peakNits),
                                luminanceCapability(display.maxAverageNits)
                            )
                        )
                        // 解析母版峰值超出本机声明峰值时提示播放端可能高光映射；只提示，
                        // 不自动修改漫反射白、HDR 强度或任何输出元数据（D84 末段）。
                        val masteringPeak =
                            current.pqWhiteNits * FableSolTuning.hdrStrength(ctx)
                        val panelPeak = display.peakNits
                        if (panelPeak != null && panelPeak > 0f &&
                            masteringPeak > panelPeak
                        ) {
                            append(
                                getString(
                                    R.string.fablesol_export_desc_white_headroom_exceeded,
                                    Math.round(masteringPeak),
                                    Math.round(panelPeak)
                                )
                            )
                        }
                    }
                )
                if (pqFormat != FableSolExportHdrFormat.HDR10_PLUS) {
                    referencePeakNote.setNote("")
                } else {
                    // 参考显示峰值的说明（D94、D177）：面板声明值不等于实际播放亮度，
                    // 低峰值目标有更强压缩取舍，本机也未必支持 HDR10+ 播放。
                    val referencePeak = FableSolTuning.exportReferenceDisplayPeakNits(ctx)
                    referencePeakNote.setNote(
                        buildString {
                            append(getString(R.string.fablesol_export_reference_peak_desc))
                            if (referencePeak <= LOW_REFERENCE_PEAK_NITS) {
                                append(getString(R.string.fablesol_export_reference_peak_low))
                            }
                            if (
                                FableSolExportDisplayLuminance.panelSupportsHdr10Plus(ctx) == false
                            ) {
                                append(
                                    getString(
                                        R.string.fablesol_export_reference_peak_no_local_hdr10plus
                                    )
                                )
                            }
                        }
                    )
                }
            }
        }

        // 复杂帧质量保护只作用于 VBR（D151）：CQ 已由质量值直接表达目标，CBR 再加质量下限
        // 会破坏它自身的固定码率约束。因此这一行跟着码率滑杆一起显隐。
        val qpGuardRow: View = makeCheckRow(
            ctx,
            getString(R.string.fablesol_param_export_qp_guard),
            FableSolTuning.exportComplexFrameGuardEnabled(ctx)
        ) { checked ->
            FableSolTuning.setExportComplexFrameGuardEnabled(ctx, checked)
            refreshEstimate()
        }

        fun refreshModeRows() {
            val constant = FableSolTuning.exportRateControl(ctx) ==
                FableSolExportRateControl.CONSTANT_QUALITY
            refreshQualityRow()
            bitrateRow.visibility = if (constant) View.GONE else View.VISIBLE
            qpGuardRow.visibility = if (constant) View.GONE else View.VISIBLE
            refreshEstimate()
        }
        mRefreshExportModeRows = ::refreshModeRows

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
                if (FableSolTuning.exportFrameRate(ctx) >= FableSolExportOptions.FRAME_RATE_HIGH) 1 else 0,
                onChipsReady = { frameRateChips = it }
            ) { index ->
                val handled = frameRateSelected
                if (handled != null) {
                    // 探测回来之后，帧率与格式、编码器同属一套联动，写偏好交给那边统一做。
                    handled(index)
                } else {
                    FableSolTuning.setExportFrameRate(ctx, rateOrder[index])
                }
                refreshEstimate()
            }
        )
        // 编码模式与帧率一样，探测回来之后要接进同一套轴联动，所以也留一个句柄。
        var rateControlChips: ChoiceChips? = null
        var rateControlSelected: ((Int) -> Unit)? = null
        container.addView(
            makeExportChoiceRow(
                ctx,
                getString(R.string.fablesol_param_export_bitrate_mode),
                listOf(
                    getString(R.string.fablesol_export_mode_quality),
                    getString(R.string.fablesol_export_mode_bitrate)
                ),
                if (
                    FableSolTuning.exportRateControl(ctx) ==
                    FableSolExportRateControl.CONSTANT_QUALITY
                ) 0 else 1,
                onChipsReady = { rateControlChips = it }
            ) { index ->
                val handled = rateControlSelected
                if (handled != null) {
                    // 探测回来之后，编码模式与格式、编码器、帧率同属一套联动，写偏好交给那边
                    // 统一做——那边还要同步 modeIndex，只写偏好会让求解读到旧值。
                    handled(index)
                } else {
                    FableSolTuning.setExportRateControl(
                        ctx,
                        if (index == 0) {
                            FableSolExportRateControl.CONSTANT_QUALITY
                        } else {
                            FableSolExportRateControl.TARGET_BITRATE
                        }
                    )
                    // 完整矩阵同时包含两种模式，切换后立即用同一组格式、编码器、位深和帧率重算。
                    mRefreshExportColorModeInfo?.invoke()
                    refreshModeRows()
                }
            }
        )
        container.addView(qualityHost)
        container.addView(bitrateRow)
        container.addView(rateControlNote)
        container.addView(qpGuardRow)
        container.addView(qpGuardNote)
        // 高复杂度默认开启（D149）：让编码器用它公开的最高复杂度，代价是更慢、更耗电、更热。
        container.addView(
            makeCheckRow(
                ctx,
                getString(R.string.fablesol_param_export_high_complexity),
                FableSolTuning.exportHighComplexityEnabled(ctx)
            ) { checked ->
                FableSolTuning.setExportHighComplexityEnabled(ctx, checked)
                refreshEstimate()
            }
        )
        container.addView(complexityNote)
        // B 帧默认关闭（D148）：它表示用户明确接受用帧重排换压缩效率，不由画质优先自动打开，
        // 也不并入编码模式或高复杂度。AV1 与 H.264 Baseline 不适用，说明行据实际落点写。
        container.addView(
            makeCheckRow(
                ctx,
                getString(R.string.fablesol_param_export_b_frames),
                FableSolTuning.exportBFramesEnabled(ctx)
            ) { checked ->
                FableSolTuning.setExportBFramesEnabled(ctx, checked)
                refreshEstimate()
            }
        )
        container.addView(bFrameNote)
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
        container.addView(keyframeNote)
        // 漫反射白只有 PQ 系用得到（HLG 是相对亮度，没有绝对锚点），所以先造出来、
        // 默认藏着，等格式定下来再决定露不露。
        val whiteRow: View = makeExportSliderRow(
            ctx,
            getString(R.string.fablesol_param_export_pq_white),
            FableSolExportOptions.MIN_PQ_WHITE_NITS,
            FableSolExportOptions.MAX_PQ_WHITE_NITS,
            PQ_WHITE_STEPS,
            FableSolTuning.exportPqWhiteNits(ctx),
            // 「标准/自定义」而不是「自动/手动」（D84）：母版亮度意图与导出设备无关，把一个
            // 固定的创作基准说成设备自适应结果会误导。判据是"用户动过没有"，不是当前数值——
            // 拖回 203 仍是一次明确的创作选择。
            { value ->
                val nits = String.format(java.util.Locale.US, "%.0f", value)
                when (FableSolTuning.exportPqWhiteMode(ctx)) {
                    FableSolExportPqWhiteMode.STANDARD ->
                        getString(R.string.fablesol_export_pq_white_standard, nits)
                    FableSolExportPqWhiteMode.CUSTOM ->
                        getString(R.string.fablesol_export_pq_white_custom, nits)
                }
            }
        ) { value ->
            FableSolTuning.setExportPqWhiteNits(ctx, value)
            refreshEstimate()
        }
        whiteRow.visibility = View.GONE

        // 「参考显示峰值」只有 HDR10+ 用得到：它写进 targeted_system_display_maximum_luminance，
        // 是"这条曲线按多亮的显示器创作"的**创作意图**，不是本机屏幕有多亮（D82、D93）。
        // 档距不均匀，所以滑杆的 progress 是档位下标，尼特换算只在 FableSolExportReferencePeak
        // 一处（D94）。
        var referencePeakSetter: ((Float) -> Unit)? = null
        var referenceShortcuts: ChoiceChips? = null

        fun referenceShortcutIndex(nits: Float): Int =
            FableSolExportReferencePeak.SHORTCUTS.indexOfFirst {
                kotlin.math.abs(it - nits) < 0.5f
            }

        val referencePeakRow: View = makeExportSliderRow(
            ctx,
            getString(R.string.fablesol_param_export_reference_peak),
            0f,
            FableSolExportReferencePeak.STEPS.toFloat(),
            FableSolExportReferencePeak.STEPS,
            FableSolExportReferencePeak.indexOf(
                FableSolTuning.exportReferenceDisplayPeakNits(ctx)
            ).toFloat(),
            { step ->
                val nits = FableSolExportReferencePeak.nitsAt(step.roundToInt())
                val formatted = String.format(java.util.Locale.US, "%.0f", nits)
                if (kotlin.math.abs(nits - FableSolExportOptions.DEFAULT_REFERENCE_PEAK_NITS) < 0.5f) {
                    getString(R.string.fablesol_export_reference_peak_standard, formatted)
                } else {
                    getString(R.string.fablesol_export_reference_peak_custom, formatted)
                }
            },
            onValueSetterReady = { setter -> referencePeakSetter = setter }
        ) { step ->
            val nits = FableSolExportReferencePeak.nitsAt(step.roundToInt())
            FableSolTuning.setExportReferenceDisplayPeakNits(ctx, nits)
            // 快捷值那一行跟着走：拖到 1000 时「1000」应当亮起，拖开则全部熄灭。
            referenceShortcuts?.select?.invoke(referenceShortcutIndex(nits))
            refreshEstimate()
        }
        referencePeakRow.visibility = View.GONE

        // 快捷参考值 + 「采用本机值」。后者是**一次性取值**，不建立持续跟随关系：折叠屏
        // 内外屏、外接屏或显示模式变化都不得改写已经选定的创作参数（D94）。
        val panelPeak = FableSolExportDisplayLuminance.panelPeakNits(ctx)
        val shortcutLabels = ArrayList<String>(FableSolExportReferencePeak.SHORTCUTS.size + 1)
        for (value in FableSolExportReferencePeak.SHORTCUTS) {
            shortcutLabels += getString(
                R.string.fablesol_export_reference_peak_shortcut, value
            )
        }
        if (panelPeak != null) {
            shortcutLabels += getString(
                R.string.fablesol_export_reference_peak_panel,
                FableSolExportDisplayLuminance.formatDerivationNumber(panelPeak)
            )
        }
        val referenceShortcutRow: View = makeExportWrappingChoiceRow(
            ctx,
            getString(R.string.fablesol_param_export_reference_peak_shortcuts),
            shortcutLabels,
            referenceShortcutIndex(FableSolTuning.exportReferenceDisplayPeakNits(ctx)),
            onChipsReady = { referenceShortcuts = it }
        ) { index ->
            val nits = if (index < FableSolExportReferencePeak.SHORTCUTS.size) {
                FableSolExportReferencePeak.SHORTCUTS[index].toFloat()
            } else {
                // 本机声明的是"期望内容峰值"，不是仪器实测面板峰值，也不等于当前亮度；
                // 它未必落在刻度上，对齐到最近一档再存。
                FableSolExportReferencePeak.snap(panelPeak ?: 0f)
            }
            FableSolTuning.setExportReferenceDisplayPeakNits(ctx, nits)
            referencePeakSetter?.invoke(FableSolExportReferencePeak.indexOf(nits).toFloat())
            referenceShortcuts?.select?.invoke(referenceShortcutIndex(nits))
            refreshEstimate()
        }
        referenceShortcutRow.visibility = View.GONE

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

        // 「色调映射方式」只在保留高光 SDR 下有意义（D65）；先造出来默认藏着，与漫反射白
        // 和高光起点同一套做法。
        val mappingRow: View = makeExportChoiceRow(
            ctx,
            getString(R.string.fablesol_param_export_sdr_mapping),
            listOf(
                getString(R.string.fablesol_export_sdr_mapping_stable),
                getString(R.string.fablesol_export_sdr_mapping_dynamic)
            ),
            if (
                FableSolTuning.exportSdrMapping(ctx) == FableSolExportSdrMapping.DYNAMIC
            ) 1 else 0,
            onChipsReady = { chips ->
                mMappingChips = chips
                // 重建参数行时沿用已有的探测结论；首次探测回来后由后台探测的回调再置一次。
                chips.setEnabledStates(listOf(true, mDynamicSdrStatsSupported))
            }
        ) { index ->
            FableSolTuning.setExportSdrMapping(
                ctx,
                if (index == 0) {
                    FableSolExportSdrMapping.STABLE
                } else {
                    FableSolExportSdrMapping.DYNAMIC
                }
            )
            mRefreshExportColorModeInfo?.invoke()
            refreshEstimate()
        }
        mappingRow.visibility = View.GONE

        // 「视频位深」只在明确选择 SDR 时出现；HDR 一律 10-bit，也不读取隐藏的历史值（D160）。
        val bitDepthRow: View = makeExportChoiceRow(
            ctx,
            getString(R.string.fablesol_param_export_bit_depth),
            listOf(
                getString(R.string.fablesol_export_bit_depth_auto),
                getString(R.string.fablesol_export_bit_depth_ten),
                getString(R.string.fablesol_export_bit_depth_eight)
            ),
            when (FableSolTuning.exportSdrBitDepth(ctx)) {
                FableSolExportSdrBitDepth.TEN_BIT -> 1
                FableSolExportSdrBitDepth.EIGHT_BIT -> 2
                else -> 0
            }
        ) { index ->
            FableSolTuning.setExportSdrBitDepth(
                ctx,
                when (index) {
                    1 -> FableSolExportSdrBitDepth.TEN_BIT
                    2 -> FableSolExportSdrBitDepth.EIGHT_BIT
                    else -> FableSolExportSdrBitDepth.AUTO
                }
            )
            mRefreshExportColorModeInfo?.invoke()
        }
        bitDepthRow.visibility = View.GONE

        // 「信号范围」只在**显式**选择 HLG 系格式时出现并可编辑（D136、D137、D144）。
        // "自动"最终落到 HLG 或杜比视界 8.4 时固定采用自动增强语义，不读取此刻隐藏的
        // "名义范围"历史值——隐藏设置让自动档静默放弃可用色容积，正是 D137 要防的那件事。
        var signalRangeLabel: TextView? = null
        val signalRangeRow: View = makeExportChoiceRow(
            ctx,
            getString(R.string.fablesol_param_export_hlg_signal_range),
            listOf(
                getString(R.string.fablesol_export_hlg_signal_range_auto),
                getString(R.string.fablesol_export_hlg_signal_range_nominal)
            ),
            if (
                FableSolTuning.exportHlgSignalRange(ctx) ==
                FableSolExportHlgSignalRange.NOMINAL
            ) 1 else 0,
            onLabelReady = { label -> signalRangeLabel = label }
        ) { index ->
            FableSolTuning.setExportHlgSignalRange(
                ctx,
                if (index == 1) {
                    FableSolExportHlgSignalRange.NOMINAL
                } else {
                    FableSolExportHlgSignalRange.AUTO_ENHANCED
                }
            )
            mRefreshExportColorModeInfo?.invoke()
        }
        signalRangeRow.visibility = View.GONE

        // 三个二级控件各自的说明行。它们此前一律拼在「导出色彩模式」那一段说明里，而那段
        // 在屏幕上位于这三行**上方**几屏——改的是这一行、要读的却在上面，等于没写。
        val mappingNote = makeExportNote(ctx)
        val bitDepthNote = makeExportNote(ctx)
        val signalRangeNote = makeExportNote(ctx)

        // 不再单独给一个"导出 HDR 视频"开关：那个开关与下面的格式选择说的是同一件事，
        // 摆两处只会让人问"关掉开关但选了 HDR10 会怎样"。列表首项就是原生 SDR。
        val diagnostics = addHdrFormatBlock(
            container,
            ctx,
            { frameRateChips },
            { rateControlChips },
            { handler -> frameRateSelected = handler },
            { handler -> rateControlSelected = handler },
            conditionalRows = listOf(
                mappingRow, mappingNote, bitDepthRow, bitDepthNote,
                signalRangeRow, signalRangeNote
            )
        ) { mode, format ->
            val toneMapped = mode == FableSolExportColorMode.SDR_TONE_MAPPED
            mappingRow.visibility = if (toneMapped) View.VISIBLE else View.GONE
            mappingNote.setNote(
                if (!toneMapped) {
                    ""
                } else {
                    buildString {
                        val dynamic = FableSolTuning.exportSdrMapping(ctx) ==
                            FableSolExportSdrMapping.DYNAMIC
                        append(
                            getString(
                                if (dynamic) {
                                    R.string.fablesol_export_sdr_mapping_desc_dynamic
                                } else {
                                    R.string.fablesol_export_sdr_mapping_desc_stable
                                }
                            )
                        )
                        // 动态映射有一条运行时降级要说清楚：统计失败会从第 1 帧改用稳定映射，
                        // 完成信息也据实标成稳定映射（D77）。
                        if (dynamic) {
                            append(getString(R.string.fablesol_export_sdr_mapping_desc_fallback))
                        }
                        // 探测已知不可用时置灰并说明原因；偏好保持不变（D77 前半）。
                        if (!mDynamicSdrStatsSupported) {
                            append(
                                getString(
                                    R.string.fablesol_export_sdr_mapping_dynamic_unsupported
                                )
                            )
                        }
                    }
                }
            )
            bitDepthRow.visibility = if (mode.isSdr) View.VISIBLE else View.GONE
            bitDepthNote.setNote(
                if (mode.isSdr) getString(R.string.fablesol_export_bit_depth_desc) else ""
            )
            // 判据是**用户显式选了哪个格式**，不是解析出来的落点：`format` 在"自动"下也会
            // 给出候选顺序的第一名，用它做判据就会让自动档冒出一个自动档并不读取的设置。
            val explicitHlg = mode.explicitFormat?.takeIf { it.usesHlgBaseLayer }
            signalRangeRow.visibility = if (explicitHlg != null) View.VISIBLE else View.GONE
            signalRangeLabel?.setText(
                if (explicitHlg == FableSolExportHdrFormat.DOLBY_VISION_84) {
                    R.string.fablesol_param_export_dolby_base_signal_range
                } else {
                    R.string.fablesol_param_export_hlg_signal_range
                }
            )
            signalRangeNote.setNote(
                if (explicitHlg == null) {
                    ""
                } else {
                    buildString {
                        append(getString(R.string.fablesol_export_hlg_range_desc))
                        // 杜比视界 8.4：该设置只改 HLG 兼容基层及其高饱和彩色高光容量，
                        // 不是开关杜比动态元数据（D144）。
                        if (explicitHlg == FableSolExportHdrFormat.DOLBY_VISION_84) {
                            append(getString(R.string.fablesol_export_dolby_base_range_note))
                        }
                        // 「自动增强」补预计落点（D135）：读已缓存的回环结论，绝不触发回环；
                        // 尚未验证时维持"首次会短验证"的说明（D138）。「名义范围」落点由
                        // 选项本身决定，正文已表达，不加预测。
                        if (
                            FableSolTuning.exportHlgSignalRange(ctx) ==
                            FableSolExportHlgSignalRange.AUTO_ENHANCED
                        ) {
                            val prediction = hlgRangePrediction(ctx, explicitHlg)
                            append(
                                getString(
                                    when {
                                        prediction == null ->
                                            R.string.fablesol_export_hlg_range_verify
                                        prediction.verified ->
                                            R.string.fablesol_export_hlg_range_predict_extended
                                        else ->
                                            R.string.fablesol_export_hlg_range_predict_nominal
                                    }
                                )
                            )
                        }
                    }
                }
            )
            whiteRow.visibility =
                if (format?.transfer == FableSolExportTransfer.PQ) View.VISIBLE else View.GONE
            val hdr10Plus = format == FableSolExportHdrFormat.HDR10_PLUS
            highlightRow.visibility = if (hdr10Plus) View.VISIBLE else View.GONE
            referencePeakRow.visibility = if (hdr10Plus) View.VISIBLE else View.GONE
            referenceShortcutRow.visibility = if (hdr10Plus) View.VISIBLE else View.GONE
            mResolvedExportHlgFormat = format?.takeIf { it.usesHlgBaseLayer }
            mResolvedExportPqFormat = format?.takeIf {
                it.transfer == FableSolExportTransfer.PQ
            }
            refreshEstimate()
        }
        container.addView(whiteRow)
        container.addView(whiteNote)
        container.addView(referencePeakRow)
        container.addView(referenceShortcutRow)
        container.addView(referencePeakNote)
        container.addView(highlightRow)
        container.addView(highlightNote)

        // 漫反射白不再随 HDR 强度变化（D82）：强度拖动时只需重算峰值那一行。
        mRefreshExportDerivedInfo = { strength -> refreshEstimate(strength) }
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

    /**
     * 信号范围的预计落点（D135）：按当前解析出的矩阵行重建候选签名，读已缓存的回环结论。
     *
     * 只组签名、查缓存，**绝不触发回环**。任何一环缺失（组合尚未解析、行里没有码控形态、
     * 候选枚举匹配不上）都返回 null，说明维持"首次导出会先做一次短验证"（D138）——预测
     * 缺席的代价只是少一句话，预测错误的代价是页面与实际产物矛盾。
     */
    private fun hlgRangePrediction(
        ctx: Context,
        format: FableSolExportHdrFormat
    ): FableSolExportHlgVerification.Outcome? {
        val outcome = mResolvedExportOutcome ?: return null
        val codecName = outcome.codecName ?: return null
        // 旧缓存的行可能没有码控形态；签名含形态（D139），猜一个可能命中**另一形态**的
        // 结论，宁缺毋错。
        val formId = outcome.rateControlFormId ?: return null
        val frameRate = mResolvedExportFrameRate ?: return null
        val options = FableSolExportOptions.read(ctx)
        val plan = FableSolExportSpec.plan(ctx, FableSolExportSpec.MAX_CARD_WIDTH_DP)
        val tier = try {
            FableSolExportTier.candidatesForMode(
                format = format,
                widthPx = plan.canvasWidthPx,
                heightPx = plan.canvasHeightPx,
                frameRate = frameRate,
                tenBit = mResolvedExportTenBit,
                preferConstantQuality = options.prefersConstantQuality,
                family = mResolvedExportCodecFamily,
                allowSoftware = true,
                customBitrateMbps = options.bitrateMbps,
                bFrames = options.bFramesEnabled,
                complexFrameGuard = options.complexFrameGuardEnabled
            ).firstOrNull {
                it.codecName == codecName && it.inputPath.stableId == outcome.inputPathId
            } ?: return null
        } catch (ignored: Throwable) {
            return null
        }
        return FableSolExportHlgVerification.cachedPrediction(
            ctx,
            tier,
            options,
            frameRate,
            FableSolExportRateControlForm.fromStableId(formId)
        )
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
        /** 编码模式胶囊；CQ 与目标码率是同一份能力矩阵的第五轴（D183）。 */
        rateControlChips: () -> ChoiceChips?,
        /** 把帧率行的点击接进各条轴的联动；参数是胶囊下标。 */
        onFrameRateSelected: (((Int) -> Unit) -> Unit),
        /** 编码模式行同理：它也是联动的一轴，不能只写偏好。 */
        onRateControlSelected: (((Int) -> Unit) -> Unit),
        /** 只在特定色彩模式下出现的二级控件；紧跟色彩模式胶囊，不能被编码器块隔开。 */
        conditionalRows: List<View>,
        /** 参数是本次请求的色彩模式与它最终落到的 HDR 格式；格式为 null 即 SDR。 */
        onFormatChanged: (FableSolExportColorMode, FableSolExportHdrFormat?) -> Unit
    ): TextView {
        val formatBlock = makeCapabilityBlock(
            ctx, getString(R.string.fablesol_param_export_color_mode)
        )
        container.addView(formatBlock.root)
        for (row in conditionalRows) container.addView(row)
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
            // 动态映射统计通路的已知图探测（D77）：先于 FP16 那条调用——同一次 EGL 探测
            // 顺带回填 FP16 结论，下一行就是缓存命中。结论只置灰选项，不改写偏好。
            val dynamicStats = try {
                FableSolHdrExportCapability.dynamicSdrStatsSupported(appContext)
            } catch (ignored: Throwable) {
                false
            }
            // FP16 扩展显示线性是 `SDR（保留高光层次）` 的硬前提（D78）。探测必须留在这条
            // 后台线程上：它要建一次性的 EGL 上下文。
            val linearScene = try {
                FableSolHdrExportCapability.linearSceneSupported()
            } catch (ignored: Throwable) {
                false
            }
            val report = try {
                FableSolHdrExportCapability.diagnostics(appContext)
            } catch (error: Throwable) {
                error.message ?: error.javaClass.simpleName
            }
            formatBlock.chipsHost.post {
                if (!isAdded || generation != mHdrCapabilityGeneration) return@post
                diagnostics.text = report
                // 动态映射置灰与原因说明（D77）：结论到位后同步胶囊状态并重写说明行。
                mDynamicSdrStatsSupported = dynamicStats
                mMappingChips?.setEnabledStates(listOf(true, dynamicStats))
                if (!dynamicStats) mRefreshExportColorModeInfo?.invoke()
                populateExportCapabilityChips(
                    appContext = appContext,
                    formatBlock = formatBlock,
                    codecBlock = codecBlock,
                    frameRateChips = frameRateChips(),
                    rateControlChips = rateControlChips(),
                    onFrameRateSelected = onFrameRateSelected,
                    onRateControlSelected = onRateControlSelected,
                    formats = formats,
                    matrix = matrix,
                    linearSceneSupported = linearScene,
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

    /**
     * 导出那几条互相约束的轴；`reconcile` 用它记住"刚动过的是哪一条"。
     *
     * [MODE]（编码模式）也在其中，但它在 `reconcile` 里的**保护权重最低**：恒定质量编不出来
     * 时把目标码率调高同样能提升画质，而降帧率是实打实的损失。详见 `reconcile` 的注释。
     */
    private enum class Axis { FORMAT, CODEC, RATE, MODE }

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

    /**
     * 紧跟在某一个选项下面的说明行。
     *
     * 与 [makeCapabilityBlock] 里那段说明同款式（11sp、62% 透明度），只是独立成行，好贴在
     * 任意一行控件之后。此前这些解释全部堆在最底部那一段信息栏里：用户改的是这一行，要读的
     * 却在屏幕外几屏之下，等于没写。信息栏因此只保留**这一组设置的推导结论**与设备诊断。
     *
     * 文案本身多以 `\n` 开头（它们原本是拼接到信息栏里的），这里统一去掉首部换行。
     */
    private fun makeExportNote(ctx: Context): TextView {
        val note = TextView(ctx)
        note.textSize = 11f
        note.alpha = 0.62f
        note.setPadding(dp(20f), dp(2f), dp(20f), dp(10f))
        return note
    }

    /** 设置说明文字并按内容自动显隐；空串就整行收起，不留一段空白。 */
    private fun TextView.setNote(value: String) {
        val trimmed = value.trimStart('\n')
        text = trimmed
        visibility = if (trimmed.isBlank()) View.GONE else View.VISIBLE
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
        rateControlChips: ChoiceChips?,
        onFrameRateSelected: (((Int) -> Unit) -> Unit),
        onRateControlSelected: (((Int) -> Unit) -> Unit),
        formats: List<FableSolExportHdrFormat>,
        // 「自动」的落点由能力矩阵按照当前编码器与精确帧率解析；不带这些约束的
        // 全局探测结果不能直接用于显示。
        matrix: FableSolExportCapabilityMatrix,
        /** FP16 扩展显示线性渲染能力；`SDR（保留高光层次）` 的硬前提（D78）。 */
        linearSceneSupported: Boolean,
        onFormatChanged: (FableSolExportColorMode, FableSolExportHdrFormat?) -> Unit
    ) {
        val ctx = formatBlock.chipsHost.context
        mExportCapabilityLoaded = true
        formatBlock.chipsHost.removeAllViews()
        codecBlock.chipsHost.removeAllViews()

        // 即使一种格式都编不出来，也要照常走完下面的 apply()——那会把界面收敛到原生 SDR
        // 并刷新指示性文字；直接 return 会让界面停在"检测中"。
        //
        // 选项是**导出色彩模式**（D62），不是"HDR 开关 + 格式"两件事：前两项是两种 SDR，
        // 它们表达的是两种不同的创作意图，不是同一件事的开关。
        //
        // **HDR 排在 SDR 前面。** 默认就是「自动」（HDR），而本功能的取向是能支持多高规格就
        // 支持多高规格；把两个 SDR 摆在最前面，等于让用户先读完两个降级选项才看到默认值。
        // 顺序：自动 → 各具体 HDR 格式（按 AUTO_ORDER）→ 两种 SDR。
        val formatChoices = ArrayList<FableSolExportColorMode>(formats.size + 3)
        val formatLabels = ArrayList<String>(formats.size + 3)
        if (formats.isNotEmpty()) {
            formatChoices += FableSolExportColorMode.HDR_AUTO
            formatLabels += getString(R.string.fablesol_export_hdr_format_auto)
            for (format in formats) {
                formatChoices += FableSolExportColorMode.entries.first {
                    it.explicitFormat == format
                }
                formatLabels += format.displayName(ctx)
            }
        }
        formatChoices += FableSolExportColorMode.SDR_NATIVE
        formatLabels += getString(R.string.fablesol_export_color_mode_sdr_native)
        formatChoices += FableSolExportColorMode.SDR_TONE_MAPPED
        formatLabels += getString(R.string.fablesol_export_color_mode_sdr_tone_mapped)

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
        val storedMode = FableSolTuning.exportColorMode(appContext)
        val storedCodec = FableSolTuning.exportCodec(appContext)
        var formatIndex = formatChoices.indexOf(storedMode).takeIf { it >= 0 }
            // 存着的模式在本机编不出来时只影响这次显示：HDR 收敛到「自动」（没有可用 HDR
            // 格式时收敛到原生 SDR），偏好本身不动。**按值找下标，不写死数字**——胶囊顺序
            // 改过一次（HDR 提到 SDR 前面），写死的 0 会静默指向另一个模式。
            ?: if (storedMode.requestsHdr && formats.isNotEmpty()) {
                formatChoices.indexOf(FableSolExportColorMode.HDR_AUTO)
            } else {
                formatChoices.indexOf(FableSolExportColorMode.SDR_NATIVE)
            }
        var codecIndex = codecChoices.indexOf(storedCodec).takeIf { it >= 0 } ?: 0

        // 帧率行的胶囊顺序是 60、120，与 FRAME_RATES（从高到低）相反，单独记一份免得记混。
        var rateIndex = if (
            FableSolTuning.exportFrameRate(appContext) >= FableSolExportOptions.FRAME_RATE_HIGH
        ) {
            1
        } else {
            0
        }

        // 编码模式胶囊的顺序就是 entries 的顺序（恒定质量、目标码率），与 refreshAxes 里的
        // 置灰映射同源。它是矩阵的第五轴（D183），也参与 reconcile。
        val modeOrder = FableSolExportRateControl.entries
        var modeIndex = modeOrder.indexOf(FableSolTuning.exportRateControl(appContext))
            .takeIf { it >= 0 } ?: modeOrder.indexOf(FableSolExportRateControl.DEFAULT)

        var formatChips: ChoiceChips? = null
        var codecChips: ChoiceChips? = null

        /**
         * 格式、编码器、帧率与编码模式的唯一解析入口；SDR 位深在同一次查询中参与求值。
         *
         * 格式、编码器、位深与帧率必须在同一次查询中求解；拆开计算会再次产生“120 fps 页面
         * 显示仅在 60 fps 成立的 HDR10／AV1”这种互相冲突的结论（D179）。
         */
        fun resolve(
            formatPreference: FableSolExportColorMode,
            codec: FableSolExportOptions.CodecPreference,
            frameRate: Int,
            // 默认读**界面当前选中的**编码模式而不是偏好：reconcile 改过之后 applyMode 会立刻
            // 落盘，两者本来一致；但把界面状态作为唯一来源，才不会在中途出现一次读旧值。
            rateControl: FableSolExportRateControl = modeOrder[modeIndex]
        ): FableSolExportCapabilityMatrix.ResolvedSelection? {
            if (
                formatPreference == FableSolExportColorMode.SDR_TONE_MAPPED &&
                !linearSceneSupported
            ) {
                return null
            }
            val effectiveFormat = if (
                formatPreference == FableSolExportColorMode.HDR_AUTO &&
                FableSolTuning.hdrStrength(appContext) <=
                FableSolHdrPolicy.STRENGTH_OFF
            ) {
                FableSolExportColorMode.SDR_NATIVE
            } else {
                formatPreference
            }
            return matrix.resolve(
                colorMode = effectiveFormat,
                codec = codec,
                frameRate = frameRate,
                sdrBitDepth = FableSolTuning.exportSdrBitDepth(appContext),
                rateControl = rateControl
            )
        }

        /**
         * 当前完整组合在本机是否成立。
         *
         * **帧率是硬约束，不再是"上限，不行就自己降"。** 那样的语义会让界面同时摆出三个各自
         * 看着都合理、合起来却不成立的选择：Z Fold4 上选了 120fps 仍可选 AV1，点下去帧率被
         * 悄悄改成 60；HDR10 与 HLG 也一样摆着，而它们在 120fps 下根本没有通路
         * （用户 2026-07-28 指出）。
         */
        fun feasible(
            formatPreference: FableSolExportColorMode,
            codec: FableSolExportOptions.CodecPreference,
            frameRate: Int,
            rateControl: FableSolExportRateControl = modeOrder[modeIndex]
        ): Boolean = resolve(formatPreference, codec, frameRate, rateControl) != null

        fun formatEnabled(
            choice: FableSolExportColorMode,
            codec: FableSolExportOptions.CodecPreference,
            frameRate: Int
        ): Boolean = feasible(choice, codec, frameRate)

        fun codecEnabled(
            choice: FableSolExportOptions.CodecPreference,
            formatPreference: FableSolExportColorMode,
            frameRate: Int
        ): Boolean = feasible(formatPreference, choice, frameRate)

        /**
         * 按当前选择重算四条可见轴各自的可用状态。
         *
         * 每一条轴的判据都是"其它轴保持现值时这一项成不成立"。这样界面上不会同时摆出多个
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
            rateControlChips?.setEnabledStates?.invoke(
                modeOrder.map { feasible(formatPreference, codec, rate, it) }
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
            val rate = rateOrder[rateIndex]
            val rateControl = modeOrder[modeIndex]
            val resolved = resolve(choice, codec, rate, rateControl)
            // “自动”的说明也读取当前精确帧率；120 fps 下只有 SDR 时必须明确显示 SDR，
            // 不得借用 60 fps 的 HDR 结论。
            val autoForCodec = resolve(
                FableSolExportColorMode.HDR_AUTO,
                codec,
                rate,
                rateControl
            )?.format
            val resolvedFormat = resolved?.format
            // 说明文字也要跟着编码器刷新：格式选「自动」时换编码器会改变落点，说明却停在
            // 上一个答案上，那正是 OPPO 上「显示当前为 HDR10+」的那一幕。
            formatBlock.description.text = hdrChoiceDescription(
                choice, autoForCodec, linearSceneSupported
            )
            mResolvedExportFormat = resolved?.let {
                resolvedExportFormatLabel(ctx, choice, it.format)
            }
            mResolvedExportFrameRate = resolved?.frameRate
            mResolvedExportCodec = resolved?.let {
                // 硬件也要写出来：只标软件的话，看到没有标注的人分不清那是“硬件”还是
                // “尚未解析”。位深同理。
                it.family.stableLabel +
                    (if (it.tenBit) " 10-bit" else " 8-bit") + getString(
                    if (it.outcome.softwareOnly) {
                        R.string.fablesol_export_codec_software_suffix
                    } else {
                        R.string.fablesol_export_codec_hardware_suffix
                    }
                )
            }
            // 自动目标码率按**已解析的实际输出**推导（D147）：族、位深、信号与帧率缺一不可，
            // 所以只能放在这里算——设置页任何一条轴变了，这个数都要跟着变。
            mResolvedExportCodecFamily = resolved?.family
            mResolvedExportAvcBaseline =
                resolved?.outcome?.profileLabel?.contains("Baseline") == true
            mResolvedExportOutcome = resolved?.outcome
            mResolvedExportTenBit = resolved?.tenBit
            mResolvedExportAutoBitrateMbps = resolved?.let {
                val plan = FableSolExportSpec.plan(ctx, FableSolExportSpec.MAX_CARD_WIDTH_DP)
                FableSolExportBitrateModel.autoBitrateBps(
                    widthPx = plan.canvasWidthPx,
                    heightPx = plan.canvasHeightPx,
                    frameRate = it.frameRate,
                    family = it.family,
                    tenBit = it.tenBit,
                    hdr = it.format?.transfer?.isHdr == true
                ) / 1_000_000f
            }
            mRefreshExportAutoBitrate?.invoke()
            // CQ 自定义原值按实际编码器路径分别保存（D146）：签名要与导出侧同源。
            mResolvedExportQualitySignature = resolved?.let {
                it.outcome.codecName?.let { codecName ->
                    val inputPath = FableSolExportInputPath.entries.firstOrNull { path ->
                        path.stableId == resolved.outcome.inputPathId
                    } ?: FableSolExportInputPath.SURFACE
                    fableSolExportQualitySignature(
                        codecName = codecName,
                        format = resolvedFormat,
                        tenBit = it.tenBit,
                        inputPath = inputPath
                    )
                }
            }
            mResolvedExportQualityRange = resolved?.outcome?.qualityRange
                ?.takeIf { rateControl == FableSolExportRateControl.CONSTANT_QUALITY }
            mRefreshExportModeRows?.invoke()
            // 编码器那段说明也要跟着刷新：选「自动」时它落到哪一族、是硬件还是软件，用户
            // 自己是推不出来的，而这正是当初"选了 120fps 却出 60fps 软件 AV1"无从察觉的原因。
            codecBlock.description.text = codecChoiceDescription(codec, mResolvedExportCodec)
            onFormatChanged(choice, resolvedFormat)
        }

        /** @param fromUser false 表示这是初始化或迁移，不得回写偏好。 */
        fun applyFormat(index: Int, fromUser: Boolean) {
            formatIndex = index
            if (fromUser) {
                FableSolTuning.setExportColorMode(appContext, formatChoices[index])
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
            if (fromUser) FableSolTuning.setExportFrameRate(appContext, rateOrder[index])
            notifyResolved()
        }

        fun applyMode(index: Int, fromUser: Boolean) {
            modeIndex = index
            if (fromUser) FableSolTuning.setExportRateControl(appContext, modeOrder[index])
            // 码率滑杆与复杂帧质量保护那两行的显隐跟着编码模式走；notifyResolved 末尾会调
            // mRefreshExportModeRows，不必在这里另走一条刷新路径。
            notifyResolved()
        }

        /**
         * 把四条轴拉回一个**确实成立**的组合上。
         *
         * 四条轴互相约束，逐条贪心地修容易来回摆动；可行组合总共不过几十个，直接枚举取"与
         * 当前差得最少、且保住刚动过那条轴"的一个，结果稳定也讲得清。
         *
         * **让步顺序：编码模式 → 编码器族 → 输出格式 → 帧率。** 权重按这个顺序递增，最先被
         * 改掉的是编码模式：
         *
         * - **编码模式让在最前。** 恒定质量编不出来时，把目标码率调高同样能提升画质，因此它
         *   不该压过任何输出规格（2026-07-30 用户裁定）。此前它根本不在枚举里，而是被当成
         *   硬约束，于是"120 fps 没有恒定质量通路"会表现为**恢复默认后掉到 60 fps 的恒定
         *   质量**——把一项真实的规格损失换成了一项本可无损替代的偏好。
         * - **帧率让在最后。** 与 D179"帧率是严格输出规格"、D179 末尾的画质优先顺序
         *   （帧率固定 → 保持格式与位深 → 换编码器族）一致；那份顺序要求设置页与运行时建议
         *   共用，此前设置页这一处的权重与它相反。
         *
         * 迁移要落盘：冲突是用户自己造成的，解决冲突属于这次操作的一部分。界面显示与真正
         * 导出的组合不一致才是更糟的事。初始化时（[changed] 为 null）同样修，但不动格式
         * ——格式是明确的意图，不该因为一次探测结论被抹掉。
         */
        fun reconcile(changed: Axis?) {
            if (matrix.isEmpty) return
            if (
                !feasible(
                    formatChoices[formatIndex],
                    codecChoices[codecIndex],
                    rateOrder[rateIndex],
                    modeOrder[modeIndex]
                )
            ) {
                // 初始化时也钉住格式：它是明确的意图，不该因为一次探测结论被抹掉。
                val keepFormat = changed == Axis.FORMAT || changed == null
                val best = buildList {
                    for (format in formatChoices.indices) {
                        if (keepFormat && format != formatIndex) continue
                        for (codec in codecChoices.indices) {
                            if (changed == Axis.CODEC && codec != codecIndex) continue
                            for (rate in rateOrder.indices) {
                                if (changed == Axis.RATE && rate != rateIndex) continue
                                for (mode in modeOrder.indices) {
                                    if (changed == Axis.MODE && mode != modeIndex) continue
                                    if (feasible(
                                            formatChoices[format],
                                            codecChoices[codec],
                                            rateOrder[rate],
                                            modeOrder[mode]
                                        )
                                    ) {
                                        add(listOf(format, codec, rate, mode))
                                    }
                                }
                            }
                        }
                    }
                }.minByOrNull { (format, codec, rate, mode) ->
                    // 改动越少越好；同样多时优先高帧率与「自动」编码器。权重的**相对大小**
                    // 就是让步顺序，改动它等于改动上面那份裁定。
                    var cost = 0
                    if (rate != rateIndex) cost += 8
                    if (format != formatIndex) cost += 4
                    if (codec != codecIndex) cost += 2
                    if (mode != modeIndex) cost += 1
                    cost * 8 + codec + (rateOrder.size - 1 - rate)
                }
                if (best != null) {
                    val (bestFormat, bestCodec, bestRate, bestMode) = best
                    if (bestFormat != formatIndex) {
                        formatChips?.select?.invoke(bestFormat)
                        applyFormat(bestFormat, fromUser = true)
                    }
                    if (bestCodec != codecIndex) {
                        codecChips?.select?.invoke(bestCodec)
                        applyCodec(bestCodec, fromUser = true)
                    }
                    if (bestRate != rateIndex) {
                        frameRateChips?.select?.invoke(bestRate)
                        applyRate(bestRate, fromUser = true)
                    }
                    if (bestMode != modeIndex) {
                        rateControlChips?.select?.invoke(bestMode)
                        applyMode(bestMode, fromUser = true)
                    }
                }
            }
            refreshAxes()
        }

        // 二级控件改动之后要重写同一段信息栏；notifyResolved 本来就负责整段的内容。
        mRefreshExportColorModeInfo = {
            notifyResolved()
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
        // 编码模式行同理。它必须走 applyMode 而不是各自写偏好：`resolve` 的默认参数读的是
        // `modeIndex`，只写偏好会让这条轴的界面状态与求解用的值分家。
        onRateControlSelected { index ->
            applyMode(index, fromUser = true)
            reconcile(Axis.MODE)
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

    /** D116：300～400 尼特属于低峰值 HDR 目标，普通亮部会更早进入压缩。 */
    private val LOW_REFERENCE_PEAK_NITS = 400f

    /** 这一次导出最终会落到哪种格式；写进指示性文字，不让用户自己回头去胶囊那里推。 */
    private fun resolvedExportFormatLabel(
        context: Context,
        mode: FableSolExportColorMode,
        auto: FableSolExportHdrFormat?
    ): String = when {
        mode.isSdr -> FableSolExportHdrFormat.SDR_LABEL
        mode.explicitFormat != null -> mode.explicitFormat.displayName(context)
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

    /**
     * 色彩模式那段信息栏。
     *
     * 保留高光 SDR 有两条必须写清楚的状态（D64、D78）：HDR 强度为 `1.0×` 时它自然退化成与
     * 原生 SDR 一致——不置灰、不自动提高强度，但必须说明，否则用户会以为色调映射能凭空
     * 恢复高光；FP16 不可用时它是真的用不了，原因也要写出来。
     */
    private fun hdrChoiceDescription(
        mode: FableSolExportColorMode,
        auto: FableSolExportHdrFormat?,
        linearSceneSupported: Boolean
    ): String {
        val ctx = requireContext()
        val text = StringBuilder(
            when {
                mode == FableSolExportColorMode.SDR_TONE_MAPPED -> buildString {
                    append(getString(R.string.fablesol_export_hdr_desc_sdr_tone_mapped))
                    if (!linearSceneSupported) {
                        append(
                            getString(
                                R.string.fablesol_export_hdr_desc_sdr_tone_mapped_unavailable
                            )
                        )
                    } else if (
                        FableSolTuning.hdrStrength(ctx) <= FableSolHdrPolicy.STRENGTH_OFF
                    ) {
                        append(getString(R.string.fablesol_export_hdr_desc_sdr_tone_mapped_off))
                    }
                }
                mode.isSdr -> getString(R.string.fablesol_export_hdr_desc_sdr_native)
                mode.explicitFormat == null -> getString(
                    R.string.fablesol_export_hdr_desc_auto,
                    auto?.displayName(ctx)
                        ?: FableSolExportHdrFormat.SDR_LABEL
                )
                else -> hdrFormatDescription(mode.explicitFormat)
            }
        )
        // 「色调映射方式」与「视频位深」的取舍**不再**拼在这里：它们各自有一行控件，说明
        // 就该贴在那一行下面（见 `mappingNote` / `bitDepthNote`）。留在这段的只有色彩模式
        // 自己的语义。
        //
        // 唯一的例外是"自动"落到 HLG 系时的那一句（D137）：它讲的是**自动档的行为**，而
        // 自动档下信号范围那一行根本不显示，没有别的地方可挂。
        if (mode.automaticHdr && auto?.usesHlgBaseLayer == true) {
            text.append(getString(R.string.fablesol_export_hlg_range_auto_format))
        }
        // 本机屏幕不支持该 HDR 格式时，屏上预览不可能准确（D93）：只提示"到兼容设备上看"，
        // 不影响导出资格，也不改变任何候选排序。
        val previewFormat = mode.explicitFormat ?: auto?.takeIf { mode.automaticHdr }
        if (previewFormat != null &&
            FableSolExportDisplayLuminance.panelSupportsFormat(
                requireContext(), previewFormat
            ) == false
        ) {
            text.append(getString(R.string.fablesol_export_desc_format_no_local_preview))
        }
        return text.toString()
    }

    private fun hdrFormatDescription(format: FableSolExportHdrFormat): String = when (format) {
        FableSolExportHdrFormat.HDR10 -> getString(R.string.fablesol_export_hdr_desc_hdr10)
        FableSolExportHdrFormat.HDR10_PLUS ->
            getString(R.string.fablesol_export_hdr_desc_hdr10_plus)
        FableSolExportHdrFormat.HLG -> getString(R.string.fablesol_export_hdr_desc_hlg)
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

    /**
     * 标签在上、胶囊按可用宽度换行在下。
     *
     * [makeExportChoiceRow] 把标签与胶囊挤在一行里，超过四五个就会溢出到屏幕外——溢出的胶囊
     * 既点不到、也不在 uiautomator 的可见节点里，等于凭空消失。参考显示峰值的快捷值最多有
     * 六个（五个参考值加"本机"），必须换行。
     */
    private fun makeExportWrappingChoiceRow(
        ctx: Context,
        label: String,
        options: List<String>,
        selectedIndex: Int,
        onChipsReady: ((ChoiceChips) -> Unit)? = null,
        onSelect: (Int) -> Unit
    ): View {
        val column = LinearLayout(ctx)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(20f), dp(6f), dp(20f), dp(6f))

        val tvLabel = TextView(ctx)
        tvLabel.text = label
        tvLabel.textSize = 13f
        column.addView(tvLabel)

        val chipsHost = LinearLayout(ctx)
        chipsHost.orientation = LinearLayout.VERTICAL
        column.addView(chipsHost, stackedBlockParams(dp(8f)))

        val built = buildChoiceChips(ctx, options, selectedIndex, onSelect)
        packChips(ctx, chipsHost, built.views)
        onChipsReady?.invoke(built)
        return column
    }

    private fun makeExportChoiceRow(
        ctx: Context,
        label: String,
        options: List<String>,
        selectedIndex: Int,
        onChipsReady: ((ChoiceChips) -> Unit)? = null,
        /**
         * 交出标签视图，供调用方在同一行的语义随上游选择改变时改写文案。
         *
         * 目前只有信号范围用得到：同一个设置在普通 HLG 下叫「HLG 信号范围」，在杜比视界 8.4
         * 下叫「HLG 基层信号范围」（D137、D144）。造两行再互相显隐会让两份持久化状态并存。
         */
        onLabelReady: ((TextView) -> Unit)? = null,
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
        onLabelReady?.invoke(tvLabel)

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

    /**
     * 滚动到「视频导出」组头。组头之上全是固定高度的滑杆行，首帧布局后位置即稳定；
     * 组内的能力探测文字在组头**之下**异步变高，不影响这个目标位置。
     */
    private fun scrollToExportGroup() {
        val header = mExportGroupHeader ?: return
        val scroll = f<ScrollView>(R.id.sv_fablesol_tuning_params)
        scroll.post {
            val target = mExportGroupHeader ?: return@post
            val rect = android.graphics.Rect(0, 0, target.width, target.height)
            scroll.offsetDescendantRectToMyCoords(target, rect)
            scroll.scrollTo(0, rect.top.coerceAtLeast(0))
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

        /** 打开后直接滚动到「视频导出」组；导出失败的「调整导出设置」入口用（D107）。 */
        private const val KEY_SCROLL_TO_EXPORT = "scroll_to_export"

        fun newInstanceScrolledToExport(): FableSolTuningDialogFragment =
            FableSolTuningDialogFragment().apply {
                arguments = Bundle().apply { putBoolean(KEY_SCROLL_TO_EXPORT, true) }
            }

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
        /** 真实编码探测要等 Dialog 首帧过去再跑，否则第一次打开会卡（D24）。 */
        private const val HDR_CAPABILITY_PROBE_DELAY_MS = 800L

        /**
         * 漫反射白滑杆：200–800 尼特，每档 1 尼特。
         *
         * 旧档距是 25 尼特，与"自动值只落 25 尼特档"那套推导配套。D82/D83 之后默认是标准的
         * 203 尼特，它落不到 25 的栅格上——滑杆会把它显示成 200，而信息栏写着 203，两处对不上。
         * 改成 1 尼特档后标准值可以精确表示，范围与语义不变。
         */
        private const val PQ_WHITE_STEPS = 600
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
