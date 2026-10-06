package com.ywwynm.everythingdone.activities

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.AnimatorDurationScale
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import com.ywwynm.everythingdone.views.particledismiss.ParticleDismissController
import com.ywwynm.everythingdone.views.particledismiss.ParticleFrameStats
import com.ywwynm.everythingdone.views.particledismiss.ParticlePanelAnimator
import com.ywwynm.everythingdone.views.particledismiss.ParticleStyle
import com.ywwynm.everythingdone.views.particledismiss.ParticleSwipeAnimator
import com.ywwynm.everythingdone.views.particledismiss.ParticleTouchFeedback
import com.ywwynm.everythingdone.views.particledismiss.ParticleTuning
import com.ywwynm.everythingdone.views.particledismiss.ThingSwipeReturn
import java.text.NumberFormat
import java.util.Random
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 粒子动画设置：上面是实时预览，下面是参数。
 *
 * 预览有两种内容，可以切换：
 *
 * 1. **记事卡片**——一张示例卡，可以真的用手指左滑或来回拖。走的是首页那条路径：
 *    `RecyclerView` + `ItemTouchHelper` + [ParticleSwipeAnimator] 的
 *    `warm` / `recordTouch` / `draw` / `reset`，与 `ThingsActivity` 的回调逐条对应，
 *    阈值与回位规则也用同一套默认值。区别只在**卡片消失之后**：预览页不需要「完成记事」
 *    这个结果，于是换上一张新卡片再让粒子沿反向轨迹聚回来，见 [SwipeCallback] 与
 *    [bringCardBack]。
 * 2. **示例对话框**——底色、圆角、内边距与按钮排布照真实对话框取值，由
 *    [ParticlePanelAnimator]（详情页里的面板用的就是它）播出现与消散。切过去**直接显示静止的
 *    对话框**，再自动播一轮「消散 → 出现」（[beginDialogPreview]）之后停住，点对话框里的
 *    「稍后 / 继续」再播一轮（[replayDialogRound]），**不循环**。
 *
 * 参数改动后 [REBUILD_DELAY_MS] 毫秒内重建材料：设置先落盘，再把正在播的那一轮重开，
 * 于是下一轮用的就是新值。
 *
 * **整页只有一种主题色**：进页面时是应用的强调渐变，按下预览下方的换色按钮之后才从用户自己的
 * 记事里随机取一条，顶栏、选项卡、滑动条、按钮与两块预览内容一起跟过去（[applyThemeColor]）。
 */
class ParticleTuningActivity : EverythingDoneBaseActivity() {

    private var toolbar: Toolbar? = null
    private var cardList: RecyclerView? = null
    private var dialogHost: FrameLayout? = null
    private var sampleDialog: CardView? = null
    private var statsText: TextView? = null
    private var hintText: TextView? = null
    private var cellSlider: SeekBar? = null
    private var flowSlider: SeekBar? = null
    private var cellValue: TextView? = null
    private var flowValue: TextView? = null
    private var cardMode: TextView? = null
    private var dialogMode: TextView? = null
    private var colorStandardTab: TextView? = null
    private var colorHighTab: TextView? = null
    private var styleFlowTab: TextView? = null
    private var styleDriftTab: TextView? = null
    private var amountSlider: SeekBar? = null
    private var amountValue: TextView? = null
    private var driftSizeSlider: SeekBar? = null
    private var driftSizeValue: TextView? = null
    private var autoCheckBox: CheckBox? = null
    private var autoRow: View? = null
    private var resetButton: TextView? = null
    private var paletteButton: ImageView? = null
    private var directionButton: ImageView? = null
    private var dialogCancel: TextView? = null
    private var dialogConfirm: TextView? = null
    private var directionPopup: PopupWindow? = null
    private var directionAnimator: ValueAnimator? = null

    /**
     * 示例对话框的粒子方向（度，0 = 右、90 = 上、180 = 左、270 = 下），默认左上。
     * **只作用于预览**：不落盘、不进用户设置，换个页面回来仍是左上。
     */
    private var dialogDirection = DEFAULT_DIRECTION

    private val main = Handler(Looper.getMainLooper())
    private val swipeAnimator by lazy { ParticleSwipeAnimator(this, allowRightSwipe = true) }
    private val panelAnimator by lazy { ParticlePanelAnimator(this) }
    private var cardReturnOrNull: ThingSwipeReturn? = null

    /**
     * 示例卡片完成之后的回位，与首页留在列表里的卡片走同一条（[ThingSwipeReturn]）：
     * 换上一张新 View 再让粒子沿反向轨迹聚回来。这一页永远用粒子，不看首页的风格设置。
     */
    private val cardReturn: ThingSwipeReturn
        get() = cardReturnOrNull
            ?: ThingSwipeReturn(this, cardList!!, alwaysParticles = true)
                .also { cardReturnOrNull = it }
    private val adapter = SampleCardAdapter()
    private val counts: NumberFormat = NumberFormat.getIntegerInstance()
    private val random = Random()
    private val argb = ArgbEvaluator()

    /**
     * 换色用的记事颜色池：用户自己建过的记事的背景，外加应用的强调渐变。
     * **只在按下换色按钮之后才用到**——进页面的主题色固定是强调渐变。
     */
    private val colorPool = ArrayList<ThingBackground>()

    /** 整页当前的主题色：顶栏、控件与两块预览内容共用它。 */
    private var themeBackground: ThingBackground = App.defaultAccentBackground

    private var colorAnimator: ValueAnimator? = null

    /** 顶栏返回箭头的原始图标：每次换色都从它重新着色，避免在已着色的图标上反复叠加。 */
    private var navigationIcon: Drawable? = null

    /** true = 记事卡片，false = 示例对话框。 */
    private var previewingCard = true

    private var dialogShowing = false

    /**
     * 对话框预览还欠几步自动播放。**一律是两步（消散 → 出现）**：静止的可见态既是起点也是
     * 终点，切过去、点「稍后 / 继续」、换方向、改参数走的都是同一轮。**不循环。**
     */
    private var dialogStepsLeft = 0

    /**
     * 每进一次对话框预览换一个号。等「静止的对话框真的画出来了」的那个回调必然会回来
     * （不到就走兜底），回来时如果已经切走或又重进过一次，这个号就对不上，那一轮直接作废。
     */
    private var dialogPreviewToken = 0

    /** 等对话框就绪重试了几次；只为让等待有个尽头，超出之后保持静止显示、不播这一轮。 */
    private var dialogRoundRetries = 0

    /**
     * 上一步（出现或消散）还没播完。切换方向、换色这类会重开一轮的操作都要等它落地，
     * 否则会把正在播的那一轮打断成「瞬间消失」。由 [ParticlePanelAnimator] 的收尾回调清掉，
     * 另有一道看门狗兜底（动画层自己也有看门狗，这里只是不让锁永远留着）。
     */
    private var dialogBusy = false

    /**
     * 正播着的这一轮走完之后还欠一轮「消散 → 出现」。两个来源：播放期间**选了新方向**，
     * 以及播放期间**改了参数**（[restartPreview]）。两者都不打断当前这一轮——打断就是一次
     * 瞬间跳变，正是第 9、11 批要消掉的东西。
     */
    private var pendingRound = false

    /** 用户是否显式选过粒子大小；未选过时滑杆停在最接近规则下限的那一档并标注「默认」。 */
    private var cellExplicit = false

    /** 松手之后的收尾动画正在播：这段时间里不接受新的滑动（第 5 条）。 */
    private var returning = false

    /** 卡片已经彻底消失、正在以粒子倒放聚回来：这一段不能被 `clearView` 提前解锁。 */
    private var condensing = false

    private var nightModeMask = 0

    private val unlockSwipe = Runnable { condensing = false; returning = false }
    private val dialogStep = Runnable { stepDialog() }
    private val dialogStepWatchdog = Runnable { finishDialogStep() }
    private val dialogRoundStart = Runnable { startDialogRound() }
    private val rebuild = Runnable { restartPreview() }
    private val collectStats = Runnable { publishStats() }

    override fun getLayoutResource(): Int = R.layout.activity_particle_tuning

    override fun initMembers() {
        cellExplicit = ParticleTuning.isCellExplicit(this)
        nightModeMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    }

    /**
     * 本页在清单里声明了 `orientation | screenSize | uiMode`，这三种变化都不会重建 Activity。
     *
     * 深浅色切换与设置页同一做法：重建，否则整页的颜色停在切换之前。转屏不重建，
     * 但预览区的高度（横屏另有取值）与示例的宽度要按新配置再算一次。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val mask = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (mask != nightModeMask) {
            nightModeMask = mask
            recreate()
            return
        }
        f<View>(R.id.fl_particle_tuning_preview)?.let { preview ->
            val height = resources.getDimensionPixelSize(R.dimen.particle_tuning_preview_height)
            if (preview.layoutParams.height != height) {
                preview.layoutParams = preview.layoutParams.apply { this.height = height }
            }
        }
        main.post { applyPreviewWidths() }
    }

    override fun findViews() {
        toolbar = f(R.id.actionbar)
        cardList = f(R.id.rv_particle_tuning_card)
        dialogHost = f(R.id.fl_particle_tuning_dialog)
        sampleDialog = f(R.id.cv_particle_tuning_dialog)
        statsText = f(R.id.tv_particle_tuning_stats)
        hintText = f(R.id.tv_particle_tuning_hint)
        cellSlider = f(R.id.sb_particle_tuning_cell)
        flowSlider = f(R.id.sb_particle_tuning_flow)
        cellValue = f(R.id.tv_particle_tuning_cell_value)
        flowValue = f(R.id.tv_particle_tuning_flow_value)
        cardMode = f(R.id.tv_particle_tuning_mode_card)
        dialogMode = f(R.id.tv_particle_tuning_mode_dialog)
        colorStandardTab = f(R.id.tv_particle_tuning_color_standard)
        colorHighTab = f(R.id.tv_particle_tuning_color_high)
        styleFlowTab = f(R.id.tv_particle_tuning_style_flow)
        styleDriftTab = f(R.id.tv_particle_tuning_style_drift)
        amountSlider = f(R.id.sb_particle_tuning_amount)
        amountValue = f(R.id.tv_particle_tuning_amount_value)
        driftSizeSlider = f(R.id.sb_particle_tuning_drift_size)
        driftSizeValue = f(R.id.tv_particle_tuning_drift_size_value)
        autoCheckBox = f(R.id.cb_particle_tuning_auto)
        autoRow = f(R.id.rl_particle_tuning_auto_row)
        resetButton = f(R.id.tv_particle_tuning_reset)
        paletteButton = f(R.id.iv_particle_tuning_palette)
        directionButton = f(R.id.iv_particle_tuning_direction)
        dialogCancel = f(R.id.tv_particle_tuning_dialog_cancel)
        dialogConfirm = f(R.id.tv_particle_tuning_dialog_confirm)
    }

    override fun initUI() {
        DisplayUtil.expandLayoutToStatusBarAboveLollipop(this)
        DisplayUtil.expandStatusBarViewAboveKitkat(f<View>(R.id.view_status_bar))
        DisplayUtil.darkStatusBar(this)

        f<TextView>(R.id.tv_particle_tuning_style_label).setText(R.string.particle_tuning_style)
        f<TextView>(R.id.tv_particle_tuning_style_detail).setText(R.string.particle_tuning_style_detail)
        f<TextView>(R.id.tv_particle_tuning_amount_label).setText(R.string.particle_tuning_amount)
        f<TextView>(R.id.tv_particle_tuning_amount_detail).setText(R.string.particle_tuning_amount_detail)
        // 标题与流体的「粒子大小」用同一条文案
        f<TextView>(R.id.tv_particle_tuning_drift_size_label).setText(R.string.particle_tuning_cell_size)
        f<TextView>(R.id.tv_particle_tuning_drift_size_detail).setText(R.string.particle_tuning_drift_size_detail)
        f<TextView>(R.id.tv_particle_tuning_cell_label).setText(R.string.particle_tuning_cell_size)
        f<TextView>(R.id.tv_particle_tuning_cell_detail).setText(R.string.particle_tuning_cell_size_detail)
        f<TextView>(R.id.tv_particle_tuning_flow_label).setText(R.string.particle_tuning_flow_detail)
        f<TextView>(R.id.tv_particle_tuning_flow_detail)
            .setText(R.string.particle_tuning_flow_detail_explanation)
        f<TextView>(R.id.tv_particle_tuning_color_label).setText(R.string.particle_tuning_color_precision)
        f<TextView>(R.id.tv_particle_tuning_color_detail)
            .setText(R.string.particle_tuning_color_precision_detail)
        f<TextView>(R.id.tv_particle_tuning_auto_label).setText(R.string.particle_tuning_auto_tune)
        f<TextView>(R.id.tv_particle_tuning_auto_detail)
            .setText(R.string.particle_tuning_auto_tune_detail)
        cardMode?.setText(R.string.particle_tuning_preview_card)
        dialogMode?.setText(R.string.particle_tuning_preview_dialog)
        colorStandardTab?.setText(R.string.particle_tuning_color_precision_standard)
        colorHighTab?.setText(R.string.particle_tuning_color_precision_high)
        styleFlowTab?.setText(R.string.particle_tuning_style_flow)
        styleDriftTab?.setText(R.string.particle_tuning_style_drift)
        resetButton?.setText(R.string.particle_tuning_reset)

        listOf(cardMode, dialogMode).forEach { makeCapsule(it) }
        listOf(colorStandardTab, colorHighTab, styleFlowTab, styleDriftTab).forEach { makeChoiceChip(it) }
        setUpSampleDialog()
        // 预览下方的两个图标按钮：不带底色，只有图标本身与一圈主题色涟漪。
        //
        // 图标色照「音频海浪动画设置」页的同类按钮（`installPreviewButtonChrome`）取：
        // `android:tint` 是 on-surface **次级**色（浅色 #8A000000 / 深色 #A8FFFFFF），
        // 再压一层 69% 的不透明度。第 7 批用的是 on-surface 主级色（#DE000000），
        // 而图标本身就是纯黑的矢量，`setColorFilter` 的 SRC_ATOP 叠上去仍是实黑
        //（浅色截图上量到 (0,0,0)），正是用户看到的「纯黑」（第 8 批第 1 条）。
        listOf(paletteButton, directionButton).forEach {
            it ?: return@forEach
            it.background = null
            it.clipToOutline = false
            it.foreground = GradientRippleDrawable(themeBackground, shapeOval = true)
            it.imageAlpha = PREVIEW_BUTTON_ICON_ALPHA
        }
        updateDirectionButton()
        // 整行可点：勾选框自己不接管点击，按下态与两种涟漪都由这一行下发（第 7 批第 4 条）。
        autoRow?.let {
            it.background = GradientRippleDrawable(
                themeBackground, shapeOval = false, cornerRadiusPx = 0f)
            it.setOnClickListener { autoCheckBox?.toggle() }
        }
        loadColorPool()

        cardList?.layoutManager = LinearLayoutManager(this)
        cardList?.adapter = adapter
        // **必须先于 ItemTouchHelper 注册**：倒放期间要在事件到达 ItemTouchHelper 之前就吞掉，
        // 否则它会在 ACTION_DOWN 接管正在跑的回位动画（见 [SwipeCallback] 的注释）。
        cardList?.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
                if (returning) return true
                // 首页也是这样接的：手势的相位由 Activity 的输入分发喂给动画器，比
                // ItemTouchHelper 判定滑动更早，消散方向才赶得上在识别之前定下来。
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    rv.findChildViewUnder(event.x, event.y)?.let { swipeAnimator.warm(it, event) }
                }
                return false
            }
        })
        ItemTouchHelper(SwipeCallback()).attachToRecyclerView(cardList)
        // 本页声明了 orientation | screenSize，转屏不会重建 Activity，示例卡与示例对话框的
        // 宽度因此不会自己跟着变；按预览区的实际宽度重算一次。
        f<View>(R.id.fl_particle_tuning_preview)?.addOnLayoutChangeListener {
            _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) main.post { applyPreviewWidths() }
        }

        cellSlider?.max = ParticleTuning.CELL_DP_LEVELS.lastIndex
        cellSlider?.progress = ParticleTuning.cellIndex(this)
        flowSlider?.max = ParticleTuning.FLOW_MAX
        flowSlider?.progress = ParticleTuning.flowDetail(this)
        amountSlider?.max = ParticleTuning.AMOUNT_LEVELS.lastIndex
        amountSlider?.progress = ParticleTuning.amountIndex(this)
        driftSizeSlider?.max = ParticleTuning.DRIFT_SIZE_LEVELS.lastIndex
        driftSizeSlider?.progress = ParticleTuning.driftSizeIndex(this)
        autoCheckBox?.isChecked = ParticleTuning.isAutoTuneOn(this)
        updateStyleGroups()

        DisplayUtil.applyBottomInsetAsScrollPadding(f<ScrollView>(R.id.sv_particle_tuning_params))
        applyThemeColor(themeBackground, full = true)
        updateValueLabels()
        updateModeTabs()
        animateDirectionButton(show = !previewingCard, animated = false)
        publishStats()
    }

    override fun setActionbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_particle_tuning)
        toolbar?.setNavigationOnClickListener { finish() }
        navigationIcon = toolbar?.navigationIcon
        applyToolbarColor(themeBackground)
    }

    override fun setEvents() {
        cardMode?.setOnClickListener { switchPreview(card = true) }
        dialogMode?.setOnClickListener { switchPreview(card = false) }
        paletteButton?.setOnClickListener { switchThemeColor() }
        directionButton?.setOnClickListener { showDirectionPopup() }
        colorStandardTab?.setOnClickListener { setColorStandard(true) }
        colorHighTab?.setOnClickListener { setColorStandard(false) }
        styleFlowTab?.setOnClickListener { setStyle(ParticleStyle.FLOW) }
        styleDriftTab?.setOnClickListener { setStyle(ParticleStyle.DRIFT) }
        autoCheckBox?.setOnCheckedChangeListener { _, checked ->
            ParticleTuning.setAutoTune(this, checked)
            publishStats()
        }
        resetButton?.setOnClickListener {
            ParticleTuning.restoreDefaults(this)
            cellExplicit = false
            cellSlider?.progress = ParticleTuning.DEFAULT_CELL_INDEX
            flowSlider?.progress = ParticleTuning.DEFAULT_FLOW
            amountSlider?.progress = ParticleTuning.DEFAULT_AMOUNT_INDEX
            driftSizeSlider?.progress = ParticleTuning.DEFAULT_DRIFT_SIZE_INDEX
            autoCheckBox?.isChecked = false
            updateValueLabels()
            updateColorTabs()
            updateStyleTabs()
            updateStyleGroups()
            publishStats()
            scheduleRebuild()
        }
        // 只在拖动结束时落盘：拖动过程中每一档都写一次设置、又重建一次材料，
        // 会把正在播的那一轮反复打断。
        cellSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) cellExplicit = true
                updateValueLabels()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                cellExplicit = true
                ParticleTuning.setCellIndex(this@ParticleTuningActivity, bar.progress)
                updateValueLabels()
                scheduleRebuild()
            }
        })
        flowSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                updateValueLabels()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                ParticleTuning.setFlowDetail(this@ParticleTuningActivity, bar.progress)
                updateValueLabels()
                scheduleRebuild()
            }
        })
        amountSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                updateValueLabels()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                ParticleTuning.setAmountIndex(this@ParticleTuningActivity, bar.progress)
                updateValueLabels()
                scheduleRebuild()
            }
        })
        driftSizeSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                updateValueLabels()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                ParticleTuning.setDriftSizeIndex(this@ParticleTuningActivity, bar.progress)
                updateValueLabels()
                scheduleRebuild()
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipeAnimator.recordTouch(ev)
        panelAnimator.recordTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        if (!previewingCard) beginDialogPreview()
    }

    override fun onPause() {
        super.onPause()
        dismissDirectionPopup()
        main.removeCallbacks(dialogStep)
        main.removeCallbacks(dialogStepWatchdog)
        main.removeCallbacks(dialogRoundStart)
        main.removeCallbacks(rebuild)
        dialogBusy = false
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        dismissDirectionPopup()
        directionAnimator?.cancel()
        colorAnimator?.cancel()
        cardReturnOrNull?.cancel()
        swipeAnimator.reset()
        super.onDestroy()
    }

    // ---- 预览 ----

    private fun switchPreview(card: Boolean) {
        if (previewingCard == card) return
        previewingCard = card
        dismissDirectionPopup()
        animateDirectionButton(show = !card)
        main.removeCallbacks(dialogStep)
        main.removeCallbacks(dialogStepWatchdog)
        main.removeCallbacks(dialogRoundStart)
        main.removeCallbacks(unlockSwipe)
        cardReturnOrNull?.cancel()
        swipeAnimator.reset()
        returning = false
        condensing = false
        cardList?.visibility = if (card) View.VISIBLE else View.GONE
        dialogHost?.visibility = if (card) View.GONE else View.VISIBLE
        // 切回卡片那一格时把正在播的那一层收掉，否则对话框的粒子会继续飘在卡片上面。
        dialogShowing = false
        dialogStepsLeft = 0
        dialogBusy = false
        pendingRound = false
        panelAnimator.cancel(sampleDialog)
        if (card) {
            sampleDialog?.visibility = View.INVISIBLE
            adapter.notifyItemChanged(0)
        } else {
            // 切到对话框那一格：**这一帧就把静止的对话框显示出来**，一轮粒子随后再播
            // （第 11 批）。两个 `visibility` 在同一次事件里改完，下一次遍历一起生效，
            // 所以切过去的第一帧上就有对话框，不会先空一下。
            beginDialogPreview()
        }
        updateModeTabs()
    }

    /**
     * 两个预览选项卡：**没有底色**，只有一圈胶囊形的触摸涟漪，选中与否靠文字的颜色与字重区分
     * （第 7 批第 1 条）。
     */
    private fun makeCapsule(tab: TextView?) {
        tab ?: return
        tab.background = null
        tab.clipToOutline = false
        tab.foreground = GradientRippleDrawable(
            themeBackground, shapeOval = false, cornerRadiusPx = -1f)
    }

    /** 选中的选项卡用主题色加粗，未选中的用页面的次级文字色。 */
    private fun paintCapsule(tab: TextView?, selected: Boolean, background: ThingBackground) {
        tab ?: return
        // 换色动画每步都调这里，涟漪一律复用同一个实例，不重建 drawable。
        (tab.foreground as? GradientRippleDrawable)?.updateBackground(background)
        tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        // 两个选项卡都在页面底色上，不在顶栏的主题色上——未选中的文字颜色要按**页面底色**算，
        // 否则浅色主题下会是白字压浅灰，几乎看不见。
        val idle = ThingBackground.pure(
            ContextCompat.getColor(this, R.color.app_chrome_on_surface_secondary))
        BackgroundUtil.applyTextBackground(tab, if (selected) background else idle)
    }

    /**
     * 颜色精度的两个选项：与「音频海浪动画设置」页的「导出色彩设置」同一种胶囊
     * （选中填满主题色、未选中只描一圈渐变边），涟漪走 foreground（第 7 批第 3 条）。
     */
    private fun makeChoiceChip(chip: TextView?) {
        chip ?: return
        chip.isClickable = true
        chip.isFocusable = true
        chip.foreground = GradientRippleDrawable(
            themeBackground, shapeOval = false, cornerRadiusPx = chipCornerRadius())
    }

    private fun chipCornerRadius(): Float = CHIP_CORNER_DP * resources.displayMetrics.density

    private fun paintChoiceChip(chip: TextView?, selected: Boolean, background: ThingBackground) {
        chip ?: return
        if (selected) {
            // 填充必须保留完整的起止色与方向；只取 color 会在换色时把渐变压成起点单色。
            val fill = BackgroundUtil.fillDrawable(background)
            (fill as? GradientDrawable)?.cornerRadius = chipCornerRadius()
            chip.background = fill
            // 必须走 applyTextBackground 的纯色分支：渐变强调色会在 TextPaint 上留一个 shader，
            // 直接 setTextColor 盖不住它。
            BackgroundUtil.applyTextBackground(chip, ThingBackground.pure(
                BackgroundUtil.onColor(background, BackgroundUtil.ON_ALPHA_PRIMARY)))
        } else {
            // GradientDrawable 的 stroke 只收单个 int；自绘描边才能让未选中项同样保留完整渐变。
            chip.background = BackgroundUtil.GradientStrokeDrawable(
                background, chipCornerRadius(), resources.displayMetrics.density
            ).apply { alpha = CHIP_OUTLINE_ALPHA }
            BackgroundUtil.applyTextBackground(chip, background)
        }
        (chip.foreground as? GradientRippleDrawable)?.updateBackground(background)
    }

    private fun updateModeTabs() {
        paintCapsule(cardMode, previewingCard, themeBackground)
        paintCapsule(dialogMode, !previewingCard, themeBackground)
        hintText?.setText(if (previewingCard) R.string.particle_tuning_hint_card
            else R.string.particle_tuning_hint_dialog)
    }

    // ---- 方向按钮与方向选择弹层（只作用于预览，不进用户设置） ----

    /**
     * 方向按钮只在示例对话框那一格出现。切换选项卡时它的**宽度**参与动画：
     * 记事卡片那一格宽度收到 0，外层容器 `gravity=center_horizontal` 于是把换色按钮
     * 平滑地带回正中；对话框那一格再张开到 40 dp，两个按钮并排居中（第 8 批第 7 条）。
     *
     * 用宽度而不是 `visibility`：后者是跳变，换色按钮会「啪」地跳到中间。
     */
    private fun animateDirectionButton(show: Boolean, animated: Boolean = true) {
        val button = directionButton ?: return
        val full = resources.getDimensionPixelSize(R.dimen.particle_tuning_icon_button_size)
        val target = if (show) full else 0
        directionAnimator?.cancel()
        directionAnimator = null
        val from = button.layoutParams.width.coerceAtLeast(0)
        if (!animated || from == target) {
            applyDirectionButtonExtent(target, full)
            return
        }
        directionAnimator = ValueAnimator.ofInt(from, target).apply {
            duration = DIRECTION_SWITCH_MS
            addUpdateListener { applyDirectionButtonExtent(it.animatedValue as Int, full) }
            start()
        }
    }

    private fun applyDirectionButtonExtent(width: Int, full: Int) {
        val button = directionButton ?: return
        button.layoutParams = button.layoutParams.apply { this.width = width }
        val fraction = if (full <= 0) 0f else width.toFloat() / full
        button.alpha = fraction
        // 收到 0 宽之后还留着左边距会让换色按钮偏出中心 4 dp。
        (button.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            val gap = (DIRECTION_BUTTON_GAP_DP * resources.displayMetrics.density * fraction).toInt()
            it.marginStart = gap
            it.leftMargin = gap
        }
        button.isClickable = width > 0
        button.requestLayout()
    }

    /**
     * 按钮上的箭头就是当前选中的方向；箭头资源指向正上方，旋转量是 `90 − 方向角`。
     *
     * 换方向时箭头**转过去**而不是跳过去，并且走最短的那一侧（315° → 0° 是 +45°，
     * 不是 −315°）。页面刚打开时直接落到位，不播这段动画。
     */
    private fun updateDirectionButton(animated: Boolean = false) {
        val button = directionButton ?: return
        val target = (90f - dialogDirection + 360f) % 360f
        button.animate().cancel()
        if (!animated) {
            button.rotation = target
            return
        }
        // `rotation` 是按**数值**线性插值的，直接给归一化后的角度会绕远路。先把当前角度
        // 也折回 [0, 360)（视觉上等价），再把差值折进 [−180, 180) 之后按相对量转。
        val from = (button.rotation % 360f + 360f) % 360f
        button.rotation = from
        val delta = ((target - from + 180f) % 360f + 360f) % 360f - 180f
        button.animate().rotationBy(delta).setDuration(DIRECTION_ROTATE_MS).start()
    }

    private fun showDirectionPopup() {
        val anchor = directionButton ?: return
        dismissDirectionPopup()
        val content = LayoutInflater.from(this)
            .inflate(R.layout.popup_particle_tuning_direction, null, false)
        DIRECTION_CELLS.forEach { (id, degrees) ->
            val cell: ImageView = content.findViewById(id)
            cell.rotation = (90f - degrees + 360f) % 360f
            cell.imageAlpha = if (degrees == dialogDirection) 255 else PREVIEW_BUTTON_ICON_ALPHA
            // 选中的那一格用主题色，其余用页面的 on-surface 次级色，与按钮本身同一条。
            cell.setColorFilter(if (degrees == dialogDirection)
                themeBackground.representativeColor()
                else ContextCompat.getColor(this, R.color.app_chrome_on_surface_secondary))
            cell.foreground = GradientRippleDrawable(themeBackground, shapeOval = true)
            cell.setOnClickListener {
                dismissDirectionPopup()
                applyDirection(degrees)
            }
        }
        val popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        directionPopup = popup
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        // 弹层横向对准按钮中心，纵向压在按钮上方，不遮住它自己。
        val dx = (anchor.width - content.measuredWidth) / 2
        popup.showAsDropDown(anchor, dx, -(content.measuredHeight + anchor.height))
    }

    private fun dismissDirectionPopup() {
        directionPopup?.let { if (it.isShowing) it.dismiss() }
        directionPopup = null
    }

    /**
     * 选了一个新方向。**对话框不会先凭空消失**：
     *
     * - 对话框停在可见的静止态 → 立刻按新方向播一轮「消散 → 出现」，一共一轮；
     * - 这一刻正在播（这一轮没走完，或某一步的动画还没落地）→ 先记下来，
     *   等当前这一轮真的结束再补上那一轮（[finishDialogStep]）；
     * - 对话框此刻不可见（理论上只有刚切过来那一瞬）→ 走 [beginDialogPreview]：
     *   先把静止的对话框显示出来，再播那一轮。
     *
     * 第 8 批那一版是 `startDialogSequence()`：它把 `visibility` 直接置 `INVISIBLE` 再排
     * 「出现 → 消散 → 出现」三步，观感正是用户说的「瞬间消失，然后出现、消散、又出现」。
     */
    private fun applyDirection(degrees: Float) {
        if (dialogDirection == degrees) return
        dialogDirection = degrees
        updateDirectionButton(animated = true)
        if (previewingCard || isFinishing) return
        if (dialogStepsLeft > 0 || dialogBusy) { pendingRound = true; return }
        pendingRound = false
        if (!dialogShowing) { beginDialogPreview(); return }
        dialogStepsLeft = ROUND_STEPS
        stepDialog()
    }

    private fun updateColorTabs() {
        val standard = ParticleTuning.isColorStandard(this)
        paintChoiceChip(colorStandardTab, standard, themeBackground)
        paintChoiceChip(colorHighTab, !standard, themeBackground)
    }

    private fun setColorStandard(standard: Boolean) {
        if (ParticleTuning.isColorStandard(this) == standard) return
        ParticleTuning.setColorStandard(this, standard)
        updateColorTabs()
        scheduleRebuild()
    }

    private fun updateStyleTabs() {
        val drift = ParticleTuning.style(this) == ParticleStyle.DRIFT
        paintChoiceChip(styleFlowTab, !drift, themeBackground)
        paintChoiceChip(styleDriftTab, drift, themeBackground)
    }

    /** 粒子样式。设置落盘后与改参数同一条路径重开预览：正在播的那一轮不打断，播完用新样式补一轮。 */
    private fun setStyle(style: ParticleStyle) {
        if (ParticleTuning.style(this) == style) return
        ParticleTuning.setStyle(this, style)
        amountSlider?.progress = ParticleTuning.amountIndex(this)
        updateValueLabels()
        updateStyleTabs()
        updateStyleGroups()
        scheduleRebuild()
    }

    /**
     * 只属于流体样式的四项（粒子大小、流动细节、颜色精度、性能自动调节）在飘散样式下整组隐藏，
     * 换成飘散自己的「粒子大小」。两种样式都显示数量滑杆，按当前样式读取各自保存的值。
     */
    private fun updateStyleGroups() {
        val drift = ParticleTuning.style(this) == ParticleStyle.DRIFT
        f<View>(R.id.ll_particle_tuning_drift_size_group)?.visibility = if (drift) View.VISIBLE else View.GONE
        listOf(R.id.ll_particle_tuning_cell_group, R.id.ll_particle_tuning_flow_group,
            R.id.ll_particle_tuning_color_group, R.id.rl_particle_tuning_auto_row).forEach {
            f<View>(it)?.visibility = if (drift) View.GONE else View.VISIBLE
        }
    }

    /**
     * 示例对话框按真实对话框取值：底色是常规 surface 色（跟随深浅色模式），
     * 只有标题与「确定」用主题色——与 `AlertDialogFragment` 的做法逐条对应。
     */
    private fun setUpSampleDialog() {
        val title: TextView = f(R.id.tv_particle_tuning_dialog_title)!!
        val content: TextView = f(R.id.tv_particle_tuning_dialog_content)!!
        val cancel: TextView = dialogCancel!!
        val confirm: TextView = dialogConfirm!!
        title.setText(R.string.particle_tuning_dialog_title)
        content.setText(R.string.particle_tuning_dialog_content)
        cancel.setText(R.string.particle_tuning_dialog_cancel)
        confirm.setText(R.string.particle_tuning_dialog_confirm)
        BackgroundUtil.installAppChromeDialogActionButton(cancel, this)
        // **两个按钮各自可点**：否则它们只是不可点的子 View，父容器一按下就把按下态整棵树
        // 分发下去（`ViewGroup.dispatchSetPressed` 只跳过可点的子 View），两个按钮的涟漪
        // 会同时播（第 7 批第 6 条的缺陷）。可点之后各自只收自己那一份。
        listOf(cancel, confirm).forEach { button ->
            button.isClickable = true
            button.isFocusable = true
            button.setOnClickListener { replayDialogRound(button) }
        }

        sampleDialog?.visibility = View.INVISIBLE
        applyPreviewWidths()
    }

    /** 转屏或分屏之后按预览区的实际宽度重算示例卡与示例对话框的宽度。 */
    private fun applyPreviewWidths() {
        if (isFinishing) return
        applyDialogWidth()
        adapter.notifyItemChanged(0)
    }

    /**
     * 真实对话框的窗口宽度是 320 dp（见 `AlertDialogFragment.onResume`），
     * 平板上不封顶的话示例会铺满整行、粒子数虚高，与真正的对话框不是一回事。
     */
    private fun applyDialogWidth() {
        val dialog = sampleDialog ?: return
        val density = resources.displayMetrics.density
        val host = dialogHost?.width ?: 0
        val available = (if (host > 0) host else resources.displayMetrics.widthPixels) -
            (DIALOG_SIDE_GUTTER_DP * 2 * density).toInt()
        if (available <= 0) return
        val width = min((DIALOG_WIDTH_DP * density).toInt(), available)
        val params = dialog.layoutParams as FrameLayout.LayoutParams
        if (params.width == width) return
        params.width = width
        dialog.layoutParams = params
    }

    // ---- 主题色 ----

    /**
     * 颜色池在后台线程建：用户建过的记事的背景 + 应用的强调渐变，按取值去重。
     * 与「音频海浪动画设置」页同一条做法；数据库不可用或一条记事都没有时，退回应用
     * 给新记事用的那组默认记事色。**只读，不碰任何记事数据。**
     *
     * 建好之后**不换色**：进页面的主题色固定是应用的强调渐变，池子要等用户按下换色按钮才用到。
     */
    private fun loadColorPool() {
        val appContext = applicationContext
        Thread({
            val pool = LinkedHashMap<String, ThingBackground>()
            fun add(bg: ThingBackground?) {
                if (bg != null && !pool.containsKey(poolKey(bg))) pool[poolKey(bg)] = bg
            }
            add(App.defaultAccentBackground)
            try {
                val dao = ThingDAO.getInstance(appContext)
                dao?.getThingsCursor(
                    Def.Database.COLUMN_TYPE_THINGS + ">=" + Thing.NOTE +
                        " and " + Def.Database.COLUMN_TYPE_THINGS + "<=" + Thing.GOAL
                )?.use { cursor ->
                    while (cursor.moveToNext()) add(Thing(cursor).getBackground())
                }
            } catch (_: Exception) {
                // 数据库不可用时退化为默认记事色，功能不受阻。
            }
            if (pool.size <= 1) {
                for (color in appContext.resources.getIntArray(R.array.thing)) {
                    add(ThingBackground.pure(color))
                }
            }
            main.post {
                if (isFinishing) return@post
                colorPool.clear()
                colorPool.addAll(pool.values)
            }
        }, "ParticleTuningColors").start()
    }

    private fun poolKey(bg: ThingBackground): String {
        val end = if (bg.mode === ThingBackground.Mode.GRADIENT) bg.endColor else bg.color
        return "${bg.mode},${bg.color},$end,${bg.orientation}"
    }

    /** 换一种主题色：从池里随机取一条与当前不同的，整页渐变过去。 */
    private fun switchThemeColor() {
        if (colorPool.isEmpty()) return
        val currentKey = poolKey(themeBackground)
        val candidates = colorPool.filter { poolKey(it) != currentKey }
        if (candidates.isEmpty()) return
        startColorTransition(candidates[random.nextInt(candidates.size)])
    }

    /** 与「音频海浪动画设置」页同一做法：逐步插值，收尾再整页重刷一次。 */
    private fun startColorTransition(target: ThingBackground) {
        val from = themeBackground
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COLOR_TRANSITION_MS
            var lastStep = -1
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                // 滑动条的渐变着色每次都要重建 drawable，按 ~12 档节流。
                val step = (fraction * COLOR_STEPS).toInt()
                if (step != lastStep) {
                    lastStep = step
                    applyThemeColor(lerpBackground(from, target, fraction), full = false)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) { cancelled = true }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled || isFinishing) return
                    applyThemeColor(target, full = true)
                }
            })
            start()
        }
    }

    private fun lerpBackground(from: ThingBackground, to: ThingBackground,
                               fraction: Float): ThingBackground {
        val start = argb.evaluate(fraction, from.color, to.color) as Int
        val gradient = from.mode === ThingBackground.Mode.GRADIENT ||
            to.mode === ThingBackground.Mode.GRADIENT
        if (!gradient) return ThingBackground.pure(start)
        val fromEnd = if (from.mode === ThingBackground.Mode.GRADIENT) from.endColor else from.color
        val toEnd = if (to.mode === ThingBackground.Mode.GRADIENT) to.endColor else to.color
        val end = argb.evaluate(fraction, fromEnd, toEnd) as Int
        val orientation = if (to.mode === ThingBackground.Mode.GRADIENT) to.orientation
            else from.orientation
        return ThingBackground.gradient(start, end, orientation)
    }

    /**
     * 把主题色套到整页：顶栏、状态栏、两组胶囊、两根滑动条、两处读数、恢复默认、勾选框，
     * 以及两块预览内容（示例卡底色、示例对话框的标题与「确定」）。
     *
     * [full] 为假时是换色动画中间的某一步，跳过只需要在收尾做一次的那几项。
     */
    private fun applyThemeColor(background: ThingBackground, full: Boolean) {
        themeBackground = background
        // 状态栏占位与顶栏**合起来**只上一次色：渐变按容器的 bounds 铺，一条横跨两块。
        // 分开各上一次的话，`GradientDrawable` 会在各自的 bounds 里各跑一整条，
        // 交界处直接断开（见 `activity_particle_tuning.xml` 里 `ll_particle_tuning_header`
        // 的注释与第 10 批第 1 条的实测）。
        BackgroundUtil.applyBackground(f<View>(R.id.ll_particle_tuning_header), background)
        applyToolbarColor(background)
        paintCapsule(cardMode, previewingCard, background)
        paintCapsule(dialogMode, !previewingCard, background)
        updateColorTabs()
        updateStyleTabs()
        cellSlider?.let {
            DisplayUtil.setSeekBarBackground(it, background)
            alignSliderToTextEdges(it)
        }
        flowSlider?.let {
            DisplayUtil.setSeekBarBackground(it, background)
            alignSliderToTextEdges(it)
        }
        amountSlider?.let {
            DisplayUtil.setSeekBarBackground(it, background)
            alignSliderToTextEdges(it)
        }
        driftSizeSlider?.let {
            DisplayUtil.setSeekBarBackground(it, background)
            alignSliderToTextEdges(it)
        }
        BackgroundUtil.applyTextBackground(cellValue, background)
        BackgroundUtil.applyTextBackground(flowValue, background)
        BackgroundUtil.applyTextBackground(amountValue, background)
        BackgroundUtil.applyTextBackground(driftSizeValue, background)
        resetButton?.let {
            BackgroundUtil.applyTextBackground(it, background)
            GradientRippleDrawable.applyAccentRipple(it, background, background.representativeColor())
        }
        autoCheckBox?.let {
            BackgroundUtil.applyCheckboxAccent(it, background, uncheckedGradient = true)
            val ripple = it.background as? GradientRippleDrawable
            if (ripple != null) ripple.updateBackground(background)
            else GradientRippleDrawable.applyCheckboxRipple(it, background)
        }
        // 两个图标按钮的**图标色不跟主题走**（固定是页面的 on-surface 次级色），跟过去的只有涟漪。
        (paletteButton?.foreground as? GradientRippleDrawable)?.updateBackground(background)
        (directionButton?.foreground as? GradientRippleDrawable)?.updateBackground(background)
        (autoRow?.background as? GradientRippleDrawable)?.updateBackground(background)
        applySampleDialogColor(background)
        cardList?.findViewHolderForAdapterPosition(0)?.itemView?.let { applyCardColor(it, background) }
        if (!full) return
        f<ScrollView>(R.id.sv_particle_tuning_params)?.let {
            EdgeEffectUtil.forScrollView(it, background.color)
        }
    }

    /**
     * 滑动条与文本边缘（标题左缘 / 读数右缘）的对齐：**轨道内缩与滑块外探各让一半**。
     *
     * `AbsSeekBar` 把 `mThumbOffset` 取成滑块宽的一半，滑块**中心**因此正好停在
     * `paddingLeft`（progress = 0）与 `width - paddingRight`（最大值）上。于是
     *
     * ```
     * 轨道内缩 = 内边距 - 行内边距          滑块外探 = 滑块半径 - 轨道内缩
     * ```
     *
     * 两者之和恒等于**滑块半径**，只能分配、不能同时归零：只留行内边距（16dp）时轨道
     * 严丝合缝而滑块外探整整一个半径（魅族实测 10.00 dp）；把半径整个补进内边距则反过来，
     * 滑块外缘对齐而轨道内缩 10 dp。滑块只在两端才顶到边，**轨道却一直在**，所以取
     * 中点：各让 [SLIDER_EDGE_BALANCE] 份的半径。第 10 批第 2 条。
     */
    private fun alignSliderToTextEdges(bar: SeekBar) {
        val radius = (bar.thumb?.intrinsicWidth ?: 0) / 2
        if (radius <= 0) return
        val inset = (radius * SLIDER_EDGE_BALANCE).roundToInt()
        val side = (ROW_SIDE_PADDING_DP * resources.displayMetrics.density).roundToInt() + inset
        if (bar.paddingStart == side && bar.paddingEnd == side) return
        bar.setPaddingRelative(side, bar.paddingTop, side, bar.paddingBottom)
    }

    private fun applyToolbarColor(background: ThingBackground) {
        val fg = BackgroundUtil.onColor(background, BackgroundUtil.ON_ALPHA_PRIMARY)
        toolbar?.setTitleTextColor(fg)
        navigationIcon?.let { toolbar?.navigationIcon = DisplayUtil.opaqueTintDrawable(this, it, fg) }
    }

    /** 示例对话框里只有标题与「确定」着色，与 `AlertDialogFragment` 的做法逐条对应。 */
    private fun applySampleDialogColor(background: ThingBackground) {
        val title: TextView? = f(R.id.tv_particle_tuning_dialog_title)
        val confirm: TextView? = f(R.id.tv_particle_tuning_dialog_confirm)
        BackgroundUtil.applyTextBackground(title, background)
        BackgroundUtil.applyTextBackground(confirm, background)
        confirm?.let {
            GradientRippleDrawable.applyAccentRipple(
                it, background, background.representativeColor())
        }
    }

    /**
     * 与列表里的记事卡同一套着色：底色取主题色（纯色或渐变），文字按底色明暗自适应。
     *
     * 标题与正文用 [BackgroundUtil.onColor] 的 [ThingBackground] 重载（首页卡片的
     * `textColorPrimary` / `textColorSecondary` 是同一组 86% / 76% 的档位），浅色底给黑字、
     * 深色底给白字；应用的强调渐变按深色处理。第 7 批之前这两处写死了 `white_86p` /
     * `white_76p`，换成浅色记事色之后白字压浅底几乎看不见（第 8 批第 5 条）。
     */
    private fun applyCardColor(card: View, background: ThingBackground) {
        (card as? CardView)?.setCardBackgroundColor(background.color)
        BackgroundUtil.applyBackground(
            card.findViewById(R.id.ll_particle_tuning_card_body), background)
        card.findViewById<TextView>(R.id.tv_particle_tuning_card_title)
            ?.setTextColor(BackgroundUtil.onColor(background, BackgroundUtil.ON_ALPHA_PRIMARY))
        card.findViewById<TextView>(R.id.tv_particle_tuning_card_content)
            ?.setTextColor(BackgroundUtil.onColor(background, BackgroundUtil.ON_ALPHA_SECONDARY))
    }

    /**
     * 进入对话框预览：**直接显示静止的对话框**，再播一轮「消散 → 出现」（第 11 批）。
     *
     * 第 10 批及以前走的是 `startDialogSequence()`：先把 `visibility` 置 `INVISIBLE`，再排
     * 「出现 → 消散 → 出现」三步。代价有两条——切过去的第一帧是空的，而且粒子动画白播了一遍。
     * 那三步的由来是奇偶性：**从不可见起步、以可见收尾，出现就必然比消散多一次**。
     * 把静止的可见态当成**起点**（而不是只当终点）之后，一轮「消散 → 出现」自己就闭合了，
     * 多出来的那一次「出现」随之消失。
     *
     * 静止显示与出现动画的末帧**必须是同一个画面**，否则交接处会跳一下：位置由布局给
     * （`layout_gravity="center"`，两条路径都没碰过 translation），阴影由 `cardElevation` 给
     * （原子交接那一条自始至终没改过真实 elevation），透明度两边都是 1——[showDialogIdle]
     * 把这三项一次写全，与 [stepDialog] 里出现那一步起步前的状态逐项相同。
     *
     * 那一轮**等对话框真的画出来之后**才开始（[awaitDialogIdleFrame] + [startDialogRound]）：
     * 消散要在这棵树上抓快照，没画过、没布好局就抓不到（`startContent` 会因为尺寸为 0 直接拒收）。
     * 等待期间对话框就那么静止地显示着，画面上不会有空白。
     */
    private fun beginDialogPreview() {
        main.removeCallbacks(dialogStep)
        main.removeCallbacks(dialogStepWatchdog)
        main.removeCallbacks(dialogRoundStart)
        dialogBusy = false
        pendingRound = false
        dialogStepsLeft = 0
        panelAnimator.cancel(sampleDialog)
        showDialogIdle()
        awaitDialogIdleFrame()
    }

    /**
     * 静止的可见态：位置、阴影与透明度**一项不缺**地写全，与出现动画的末帧完全一致。
     *
     * `panelAnimator.cancel` 只撤动画层，撤不掉上一轮在对话框身上留下的状态——原子交接期间
     * 真实面板的 `alpha` 是 0，中途切走再切回来就会停在那里，看上去像「对话框没了」。
     */
    private fun showDialogIdle() {
        val target = sampleDialog ?: return
        target.alpha = 1f
        target.translationX = 0f
        target.translationY = 0f
        target.visibility = View.VISIBLE
        dialogShowing = true
    }

    /**
     * 等静止的对话框真的**提交过一帧**，再去排那一轮。
     *
     * 判据不能只用 `post`：那只能证明这一次遍历排完了，证明不了 RenderThread 已经提交
     * （与跨窗口交接同一条纪律，见 `ParticleDismissController.runAfterShown`）。回调必然会回来，
     * 拿不到提交回调时走兜底时限，最坏情况只是这一轮晚一点开始——那段时间里对话框是静止显示的。
     */
    private fun awaitDialogIdleFrame() {
        val target = sampleDialog ?: return
        val token = ++dialogPreviewToken
        dialogRoundRetries = 0
        ParticleDismissController.runAfterShown(target, IDLE_FRAME_TIMEOUT_MS, Runnable {
            if (token != dialogPreviewToken || previewingCard || isFinishing) return@Runnable
            main.removeCallbacks(dialogRoundStart)
            main.postDelayed(dialogRoundStart, FIRST_STEP_DELAY_MS)
        })
    }

    /**
     * 开始那一轮「消散 → 出现」。对话框还没布好局就再等一会儿，**期间保持静止显示**；
     * 等过头了就干脆不播这一轮，也不把它藏起来——画面上永远不会因为这条路径而空掉。
     */
    private fun startDialogRound() {
        if (previewingCard || isFinishing) return
        if (dialogStepsLeft > 0 || dialogBusy) return
        val target = sampleDialog ?: return
        if (!dialogReadyForRound(target)) {
            if (dialogRoundRetries++ < ROUND_READY_RETRIES) {
                main.postDelayed(dialogRoundStart, ROUND_READY_POLL_MS)
            }
            return
        }
        dialogRoundRetries = 0
        showDialogIdle()
        dialogStepsLeft = ROUND_STEPS
        stepDialog()
    }

    /** 消散那一条对宿主的要求：已附着、布好局、尺寸非零、没有还没落地的布局请求。 */
    private fun dialogReadyForRound(target: View): Boolean =
        target.isAttachedToWindow && target.isShown && target.isLaidOut &&
            target.width > 0 && target.height > 0 && !target.isLayoutRequested

    /**
     * 点「稍后 / 继续」重播一轮：消散一次再出现一次，回到可见的静止态。
     *
     * **先把这个按钮的涟漪播完再开始消散**，与真实弹窗同一条纪律
     * （[ParticleTouchFeedback]，「消散要等触摸反馈播完再开始」）。消散那条路自己也有一道
     * 同样的门（`ParticleSession` 的 `Stage.FEEDBACK`），但那道门只在**抓图那一刻**还看得见
     * 涟漪时才装得上；点击回调是 `performClick` 排进消息队列之后才跑的，快速点一下时涟漪
     * 可能刚被 `UnsetPressedState` 收掉，门就落空了。在这里显式等一次，两种时序都成立。
     */
    private fun replayDialogRound(source: View) {
        if (previewingCard || isFinishing) return
        if (dialogStepsLeft > 0 || dialogBusy || !dialogShowing) return
        dialogStepsLeft = ROUND_STEPS
        main.removeCallbacks(dialogStep)
        val feedback = ParticleTouchFeedback.begin(this, source)
        if (feedback == null) stepDialog()
        else feedback.afterSettled { if (!isFinishing && !previewingCard) stepDialog() }
    }

    /** 对话框预览的一步：没显示就播出现，显示着就播消散；还欠步数才排下一步。 */
    private fun stepDialog() {
        val target = sampleDialog ?: return
        if (previewingCard || isFinishing || dialogStepsLeft <= 0) return
        dialogStepsLeft--
        beginStats()
        beginDialogStep()
        val elevation = resources.getDimension(R.dimen.particle_tuning_dialog_elevation)
        val settled = { finishDialogStep() }
        if (dialogShowing) {
            dialogShowing = false
            if (!panelAnimator.dismiss(target, dialogDirection, elevation, settled, {
                    target.visibility = View.INVISIBLE
                })) {
                // 消散没能开播（系统动画被关掉、快照失败……）。**不能把对话框藏起来**：
                // 那一下就是用户说的空白，而且后面还得等一步才补回来。整轮作废，保持静止显示。
                // `settled` 在 dismiss 返回之前已经跑过一次，这里要把它排下的那一步撤掉。
                main.removeCallbacks(dialogStep)
                dialogStepsLeft = 0
                dialogBusy = false
                pendingRound = false
                showDialogIdle()
            }
        } else {
            dialogShowing = true
            target.visibility = View.VISIBLE
            // 出现那一条会把当前的透明度记下来、播完再还原。消散走原子交接时对话框的透明度
            // 是 0（交接期由自有图层顶着），所以这里必须先还原成不透明再起步，
            // 否则「播完还原」会把它还原成看不见。
            target.alpha = 1f
            if (!panelAnimator.appear(target, dialogDirection, elevation, settled)) finishDialogStep()
        }
    }

    /** 一步开播：上锁并挂看门狗，动画层不回调时锁也一定会解开。 */
    private fun beginDialogStep() {
        dialogBusy = true
        main.removeCallbacks(dialogStepWatchdog)
        val scale = AnimatorDurationScale.scale(this).coerceAtLeast(1f)
        main.postDelayed(dialogStepWatchdog, (DIALOG_STEP_WATCHDOG_MS * scale).toLong())
    }

    /**
     * 一步真的播完了。这一轮全部走完、而播放期间又选过新方向时，在这里补上那一轮
     * 「消散 → 出现」——**不重置可见性，也不走开场序列**，所以画面上不会有凭空消失那一下。
     */
    private fun finishDialogStep() {
        main.removeCallbacks(dialogStepWatchdog)
        if (!dialogBusy) return
        dialogBusy = false
        // 下一步等这一步真的落地再排，不按固定间隔：一轮动画的时长会被设备的动画缩放放大
        // （OPD2515 实测 5 倍），按固定间隔排会让下一步压在上一步的收尾上。
        if (dialogStepsLeft > 0) { main.postDelayed(dialogStep, STEP_GAP_MS); return }
        if (!pendingRound) return
        if (previewingCard || isFinishing) { pendingRound = false; return }
        pendingRound = false
        if (!dialogShowing) { beginDialogPreview(); return }
        dialogStepsLeft = ROUND_STEPS
        stepDialog()
    }

    private fun restartPreview() {
        if (previewingCard) {
            cardReturnOrNull?.cancel()
            swipeAnimator.reset()
            adapter.notifyItemChanged(0)
            main.removeCallbacks(unlockSwipe)
            returning = false
            condensing = false
        } else if (dialogStepsLeft > 0 || dialogBusy) {
            // 正播着：**不打断**，等这一轮走完再用新参数补一轮。中途掐掉会让对话框从半散的
            // 状态一下子跳回整块，与第 11 批要消掉的那一跳是同一类。
            pendingRound = true
        } else {
            // 静止着：直接用新参数播一轮，出现与消散各重新建一次材料。
            beginDialogPreview()
        }
    }

    // ---- 参数 ----

    private fun updateValueLabels() {
        val cell = cellSlider?.progress ?: ParticleTuning.DEFAULT_CELL_INDEX
        val flow = flowSlider?.progress ?: ParticleTuning.DEFAULT_FLOW
        val size = getString(R.string.particle_tuning_cell_size_value,
            ParticleTuning.cellDpLabel(cell), pixelLabel(ParticleTuning.cellDpValue(cell)))
        cellValue?.text = if (cellExplicit) size
            else getString(R.string.particle_tuning_value_default, size)
        flowValue?.text = flow.toString()
        val amount = amountSlider?.progress ?: ParticleTuning.DEFAULT_AMOUNT_INDEX
        val amountLabel = ParticleTuning.amountLabel(amount)
        amountValue?.text = if (amount == ParticleTuning.DEFAULT_AMOUNT_INDEX)
            getString(R.string.particle_tuning_value_default, amountLabel) else amountLabel
        val driftSize = driftSizeSlider?.progress ?: ParticleTuning.DEFAULT_DRIFT_SIZE_INDEX
        val driftSizeLabel = ParticleTuning.driftSizeLabel(driftSize)
        driftSizeValue?.text = if (driftSize == ParticleTuning.DEFAULT_DRIFT_SIZE_INDEX)
            getString(R.string.particle_tuning_value_default, driftSizeLabel) else driftSizeLabel
        BackgroundUtil.applyTextBackground(cellValue, themeBackground)
        BackgroundUtil.applyTextBackground(flowValue, themeBackground)
        BackgroundUtil.applyTextBackground(amountValue, themeBackground)
        BackgroundUtil.applyTextBackground(driftSizeValue, themeBackground)
    }

    /** 本机密度下这一档折合多少像素；整数能表示时不写小数点。 */
    private fun pixelLabel(dp: Float): String {
        val px = (dp * resources.displayMetrics.density * 10f).roundToInt() / 10f
        return if (px == px.toInt().toFloat()) px.toInt().toString() else String.format("%.1f", px)
    }

    private fun scheduleRebuild() {
        main.removeCallbacks(rebuild)
        main.postDelayed(rebuild, REBUILD_DELAY_MS)
    }

    // ---- 本机读数 ----

    private fun beginStats() {
        ParticleFrameStats.begin()
        main.removeCallbacks(collectStats)
        main.postDelayed(collectStats, STATS_DELAY_MS)
    }

    private fun publishStats() {
        ParticleFrameStats.end()
        // 用 `last` 而不是 `end()` 的返回值：这一轮没采到帧时界面继续显示上一次量到的值，
        // 而不是退回「等一轮」。
        val summary = ParticleFrameStats.last
        val count = ParticleFrameStats.flakes
        val flakes = counts.format(count.toLong())
        val reading = when {
            // 进程刚起来、一轮动画都还没播过时连粒子数都没有，不写「粒子总数 0」。
            count <= 0 -> getString(R.string.particle_tuning_stats_idle)
            summary == null -> getString(R.string.particle_tuning_stats_waiting, flakes)
            else -> getString(R.string.particle_tuning_stats, flakes, summary.frames,
                summary.expected, String.format("%.1f", summary.intervalMs),
                summary.fps.roundToInt())
        }
        // 性能自动调节真的降过档时要让用户看得见，并且知道怎么改回去（「恢复默认」那个按钮
        // 会连同这条记录一起清掉）。默认不会出现——自动调节默认是关的。
        statsText?.text = if (ParticleTuning.autoEasedSteps(this) > 0)
            reading + "\n" + getString(R.string.particle_tuning_auto_eased) +
                "\n" + getString(R.string.particle_tuning_auto_eased_restore)
        else reading
    }

    // ---- 示例卡片 ----

    private inner class SampleCardAdapter : RecyclerView.Adapter<SampleCardHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SampleCardHolder =
            SampleCardHolder(LayoutInflater.from(parent.context)
                .inflate(R.layout.card_particle_tuning_preview, parent, false))

        override fun onBindViewHolder(holder: SampleCardHolder, position: Int) {
            applyCardColor(holder.itemView, themeBackground)
            holder.itemView.findViewById<TextView>(R.id.tv_particle_tuning_card_title)
                .setText(R.string.particle_tuning_card_title)
            holder.itemView.findViewById<TextView>(R.id.tv_particle_tuning_card_content)
                .setText(R.string.particle_tuning_card_content)
            applyCardWidth(holder.itemView)
            holder.itemView.translationX = 0f
            holder.itemView.alpha = 1f
        }

        override fun getItemCount(): Int = 1
    }

    /**
     * 示例卡最宽 [CARD_MAX_WIDTH_DP] dp 并居中：平板横屏下铺满整行的卡片不是列表里会出现的
     * 形状，粒子数也会跟着虚高。宽度与左右边距一起写，**不靠 gravity 居中**——
     * `RecyclerView` 的条目布局参数没有 gravity 这一项。
     */
    private fun applyCardWidth(card: View) {
        val params = card.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val density = resources.displayMetrics.density
        val gutter = (CARD_SIDE_GUTTER_DP * density).toInt()
        val host = cardList?.width ?: 0
        val available = (if (host > 0) host else resources.displayMetrics.widthPixels) - gutter * 2
        if (available <= 0) return
        val width = min((CARD_MAX_WIDTH_DP * density).toInt(), available)
        val side = gutter + (available - width) / 2
        if (params.width == width && params.leftMargin == side) return
        params.width = width
        params.leftMargin = side
        params.rightMargin = side
        card.layoutParams = params
    }

    private inner class SampleCardHolder(view: View) : RecyclerView.ViewHolder(view)

    /**
     * 与首页 `ThingsActivity` 的左滑回调逐条对应，**阈值、甩动速度与回位时长全用默认值**，
     * 也就是首页那一套规则：没过阈值松手就滑回来（粒子沿原轨迹倒放回 0），
     * 过了阈值松手就把剩下那一段播完、卡片彻底消失。
     *
     * 差别只在消失**之后**：首页要去完成记事，这一页没有这回事，于是消失之后立刻换上一张新卡片，
     * 再让粒子沿反向轨迹把它聚回来（[bringCardBack]，与首页「卡片留在列表里」的回位同一条路）。
     *
     * 第六批那一版「永远判不成完成」已经撤掉：那样做是为了绕开「判完成之后 itemView 归待清理
     * 队列管、每帧被按到 −列表宽度」这件事，代价是半程松手不再自动播完，与首页不一致。
     */
    private inner class SwipeCallback :
        ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START or ItemTouchHelper.END) {

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                            target: RecyclerView.ViewHolder): Boolean = false

        /** 收尾期间不许起新的滑动；真正挡住手指的是页面上那个 `OnItemTouchListener`。 */
        override fun getSwipeDirs(recyclerView: RecyclerView,
                                  viewHolder: RecyclerView.ViewHolder): Int =
            if (returning) 0 else super.getSwipeDirs(recyclerView, viewHolder)

        override fun onChildDraw(c: android.graphics.Canvas, recyclerView: RecyclerView,
                                 viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float,
                                 actionState: Int, isCurrentlyActive: Boolean) {
            var particles = false
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                // 手指离开之后位移还在变：要么在倒放回原位，要么在把完成那一段播完。
                // 两种都不该再接受新的手势。
                if (!isCurrentlyActive && dX != 0f) beginRelease()
                // 两个方向都走粒子（[ParticleSwipeAnimator] 的 `allowRightSwipe`），
                // 右滑是左滑的镜像：方向、消散锚点、进度符号与停靠位移都由
                // `ParticleSwipeDirection` 按符号给出（第 8 批第 8 条）。
                particles = swipeAnimator.draw(viewHolder.itemView, dX)
            }
            super.onChildDraw(c, recyclerView, viewHolder, if (particles) 0f else dX, dY,
                actionState, isCurrentlyActive)
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && !particles) {
                val view = viewHolder.itemView
                view.alpha = (1f - abs(dX) / view.right).coerceIn(0f, 1f)
            }
        }

        /**
         * 过了阈值：与首页一样先把剩下那一段播完（[ParticleSwipeAnimator.finishSwipe]），
         * 卡片彻底消失之后再让它以粒子倒放回来。
         */
        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val list = cardList
            returning = true
            condensing = true
            val done = { bringCardBack(direction) }
            if (list == null || !swipeAnimator.finishSwipe(viewHolder.itemView, list.width, done)) {
                done()
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            swipeAnimator.reset(viewHolder.itemView)
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.alpha = 1f
            // 走完成那一条时，`clearView` 是被 `notifyItemChanged` 换下旧 View 触发的，
            // 这一刻粒子才刚开始聚回来，不能在这里解锁。
            if (!condensing) {
                main.removeCallbacks(unlockSwipe)
                returning = false
            }
        }
    }

    /** 松手：锁住滑动，并从这一刻起量这一轮的帧。 */
    private fun beginRelease() {
        if (returning) return
        returning = true
        beginStats()
        armUnlockWatchdog(RELEASE_WATCHDOG_MS)
    }

    /**
     * 卡片彻底消失之后：换上一张新卡片，再让粒子沿反向轨迹把它聚回来。
     *
     * 换 View 这一步是必须的——被判完成的那张 itemView 归 `ItemTouchHelper` 的待清理队列管，
     * 每一帧都被按到 −列表宽度，只有换上一张新 View 才放得回原位
     * （见 `memory/recyclerview-swipe-return-view-identity`）。
     */
    private fun bringCardBack(direction: Int) {
        if (isFinishing || !previewingCard) {
            condensing = false
            returning = false
            return
        }
        adapter.notifyItemChanged(0)
        beginStats()
        armUnlockWatchdog(RETURN_WATCHDOG_MS)
        // 从飞出去的那一侧聚回来：左滑从正左方，右滑从正右方。
        cardReturn.start(0, particles = true, onSettled = {
            main.removeCallbacks(unlockSwipe)
            condensing = false
            returning = false
        }, fromRight = direction == ItemTouchHelper.END)
    }

    /**
     * 解锁本来由 `clearView`（没过阈值那一条）或回位收尾（过了阈值那一条）负责。万一哪一条没到，
     * 卡片就再也滑不动了，所以另留一道看门狗。
     *
     * 里面几段都是 `ValueAnimator`，时长会被设备的动画缩放放大（OPD2515 上实测 5 倍），
     * 等待时间要按同一个缩放算；缩放小于 1 时不跟着缩短，看门狗宁可晚到也不能早到。
     */
    private fun armUnlockWatchdog(nominalMs: Long) {
        main.removeCallbacks(unlockSwipe)
        val scale = AnimatorDurationScale.scale(this).coerceAtLeast(1f)
        main.postDelayed(unlockSwipe, (nominalMs * scale).toLong())
    }

    private companion object {
        /** 参数改动到重建材料之间的等待：拖完最后一档再等这么久，就不会一档重建一次。 */
        const val REBUILD_DELAY_MS = 300L
        /** 一步落地到下一步之间的停顿。一轮动画约 1.2 s，合起来与此前的 2.2 s 节奏相当。 */
        const val STEP_GAP_MS = 800L
        /** 静止的对话框画出来之后停多久再开始那一轮：让「切过去先看到它」这件事真的看得见。 */
        const val FIRST_STEP_DELAY_MS = 350L
        /**
         * 等「静止的对话框提交过一帧」的兜底时限。拿不到提交回调时照样往下走，
         * 最坏也只是这一轮晚开始一点——那段时间对话框是静止显示的，画面不会空。
         */
        const val IDLE_FRAME_TIMEOUT_MS = 300L
        /**
         * 对话框还没布好局时的重试间隔与次数（合计 2 s）。等过头就不播这一轮，
         * **也不把对话框藏起来**：宁可少一轮动画，不要一帧空白。
         */
        const val ROUND_READY_POLL_MS = 100L
        const val ROUND_READY_RETRIES = 20
        /**
         * 「这一步播完了」的兜底时限（标称值，按设备的动画缩放放大）。一轮动画约 1.2 s，
         * 加上抓图、建材与首帧上屏的余量；只在动画层不回调时才会真的用上。
         */
        const val DIALOG_STEP_WATCHDOG_MS = 2000L
        /**
         * 一轮就是两步：**消散 → 出现**。切进对话框预览、点「稍后 / 继续」、换方向、改参数
         * 走的都是这一轮——静止的可见态既是起点也是终点，不再有第 10 批那个三步开场序列。
         */
        const val ROUND_STEPS = 2
        /** 一轮动画最长约 1.2 s，收尾之后再汇总。 */
        const val STATS_DELAY_MS = 1500L
        /**
         * 看门狗的两档标称时长（毫秒），都只在出意外时才会真的用上。
         *
         * 松手到卡片彻底消失：`ItemTouchHelper` 的回位动画 250 ms + 把剩下那一段播完最多
         * 420 ms + 等末帧真的上屏最多 700 ms，再留一点余量。
         * 卡片回来这一段以 [ThingSwipeReturn] 自己那道 2000 ms 的兜底为准，同样再留余量。
         */
        const val RELEASE_WATCHDOG_MS = 1800L
        const val RETURN_WATCHDOG_MS = 2400L
        /** 换色过渡的时长与中间步数；每一步都要重建滑动条的渐变 drawable，所以要节流。 */
        const val COLOR_TRANSITION_MS = 560L
        const val COLOR_STEPS = 12
        /** 与 `AlertDialogFragment` 给真实对话框窗口设的宽度一致。 */
        const val DIALOG_WIDTH_DP = 320
        const val DIALOG_SIDE_GUTTER_DP = 24
        const val CARD_MAX_WIDTH_DP = 640
        const val CARD_SIDE_GUTTER_DP = 16
        /**
         * 颜色精度胶囊的圆角与未选中项的描边不透明度。第 8 批把胶囊的高度与字号恢复成
         * 第 6 批的取值（36 dp / 14 sp），圆角随之取高度的一半，仍是胶囊形。
         */
        const val CHIP_CORNER_DP = 18f
        const val CHIP_OUTLINE_ALPHA = 96
        /**
         * 参数区每一行的左右内边距，与「空间照片效果」设置页同值（第 9 批）。
         * 滑动条的内边距按它再补一份滑块半径，见 [alignSliderToTextEdges]。
         */
        const val ROW_SIDE_PADDING_DP = 16f
        /**
         * 滑动条两端「轨道内缩」占滑块半径的比例，余下的那一份就是「滑块外探」。
         * 0 = 轨道与文本边缘严丝合缝、滑块整个半径探出去；1 = 反过来。取 0.5 是因为
         * 滑块只在两端才顶到边，轨道却一直在，两边的观感各让一半。见 [alignSliderToTextEdges]。
         */
        const val SLIDER_EDGE_BALANCE = 0.5f
        /**
         * 预览下方图标按钮的不透明度，与「音频海浪动画设置」页的同类按钮取同一个值
         * （`FableSolTuningDialogFragment.PREVIEW_BUTTON_ICON_ALPHA`）。
         */
        const val PREVIEW_BUTTON_ICON_ALPHA = 176
        /** 方向按钮随选项卡张开 / 收起的时长；与选项卡之间的间距。 */
        const val DIRECTION_SWITCH_MS = 256L
        const val DIRECTION_BUTTON_GAP_DP = 8f
        /** 换方向时箭头转到新角度的时长，与按钮张开 / 收起取同一档。 */
        const val DIRECTION_ROTATE_MS = 256L
        /** 默认方向：左上。度数约定见 [dialogDirection]。 */
        const val DEFAULT_DIRECTION = 135f
        /** 九宫格里八个格子各自代表的方向。 */
        val DIRECTION_CELLS = listOf(
            R.id.iv_particle_direction_lt to 135f,
            R.id.iv_particle_direction_t to 90f,
            R.id.iv_particle_direction_rt to 45f,
            R.id.iv_particle_direction_l to 180f,
            R.id.iv_particle_direction_r to 0f,
            R.id.iv_particle_direction_lb to 225f,
            R.id.iv_particle_direction_b to 270f,
            R.id.iv_particle_direction_rb to 315f)
    }
}
