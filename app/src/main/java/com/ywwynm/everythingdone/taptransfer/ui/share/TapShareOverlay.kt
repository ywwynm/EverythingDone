package com.ywwynm.everythingdone.taptransfer.ui.share

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.taptransfer.TapTransfer
import com.ywwynm.everythingdone.taptransfer.ui.ProgressSmoother
import com.ywwynm.everythingdone.taptransfer.ui.TapPrerequisiteWatcher
import com.ywwynm.everythingdone.taptransfer.ui.TapSetupNavigator
import com.ywwynm.everythingdone.taptransfer.ui.card.TapThingCards
import com.ywwynm.everythingdone.taptransfer.ui.flight.TapParticleFlight
import com.ywwynm.everythingdone.taptransfer.ui.flight.TapParticleParams
import com.ywwynm.everythingdone.taptransfer.ui.flight.TapSessionRecorder
import com.ywwynm.everythingdone.utils.AppearanceUtil
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import org.json.JSONArray
import kotlin.math.max

/**
 * 分享界面（决策 2026-09-24「分享界面改为卡片在上、面板在下，盖在当前页面上」）。
 *
 * 当前页面变暗但保持显示；要分享的记事以首页卡片的样子抬升起来停在上半屏（加宽、按首页的字号排，
 * 放不下时删减内容；选择模式里从列表里的卡片位置升起、叠成一叠；详情页里从页面中升起）；下方面板
 * 是碰一碰区域与「文本与图片」「长截图」「记事文件」三项。手机横屏时改成左右排：面板贴右半边、
 * 从右侧滑入，卡片在左半边。按返回或点暗处关闭，卡片落回。
 *
 * 碰上之后卡片交给首页与弹窗共用的粒子消散，颗粒整团滑进 NFC 天线（[TapParticleFlight.Outflow]）。
 * 发完之后「已发送」停留一会儿，卡片按打开时的那段抬起重新出现；发送失败、流出被打断时从天线流回来、
 * 拼回卡片（一份轨迹往前拖是流出、往回拖是流回）。
 *
 * 界面直接挂在 Activity 的内容视图上，不开新窗口：卡片要从页面里连续地抬升出来、再落回去，
 * 跨窗口交接做不到连续。
 */
class TapShareOverlay private constructor(
    private val activity: FragmentActivity,
    private val request: Request
) : TapShareCardStage.Listener, TapSendController.Listener {

    /** 一张要抬升的卡片在屏幕上的位置（窗口坐标）；为空表示这条记事此刻不在屏幕上。 */
    class Source(val rect: Rect?)

    /** 宿主页面要提供的动作。 */
    interface Host {
        /** 不支持时返回 false，面板上不显示「长截图」（选多条时，决策 2026-09-24）。 */
        val longScreenshotAvailable: Boolean

        fun shareTextAndImages()
        fun shareLongScreenshot()
        fun shareThingFile()

        /** 分享界面完全关闭。[sentByTap] 为 true 表示期间经碰一碰发出去过。 */
        fun onShareClosed(sentByTap: Boolean)
    }

    /**
     * @param things 要分享的记事，已在主线程复制好：碰一碰的记事文件在后台线程写。
     * @param sources 与 [things] 同序。卡片从这些位置抬升（选择模式）；为空时卡片从页面里升起（详情页）。
     */
    class Request(
        val things: List<Thing>,
        val sources: List<Source>,
        val accent: ThingBackground,
        val host: Host
    )

    /** 碰上之后粒子的状态。 */
    private enum class Flow {
        /** 没有粒子：卡片由真实视图显示。 */
        NONE,

        /** 已碰上，正在压下卡片投影、截图、建粒子层。 */
        PREPARING,

        /** 粒子层已接管，正在流向天线（或已流完）。 */
        OUT,

        /** 正在从天线流回。 */
        RETURNING
    }

    private val density = activity.resources.displayMetrics.density
    private val params = TapParticleParams.get(activity)
    private val outflowEase = params.outflowInterpolator()
    private val reformEase = params.reformInterpolator()
    private val main = Handler(Looper.getMainLooper())
    private val root = FrameLayout(activity)
    private val dim = View(activity)
    private val stage = TapShareCardStage(activity, this)
    private val panel: View = LayoutInflater.from(activity).inflate(R.layout.overlay_tap_share_panel, root, false)
    private val tapArea: View = panel.findViewById(R.id.ll_tap_area)
    private val tapRow: LinearLayout = panel.findViewById(R.id.ll_tap_row)
    private val tapIcon: ImageView = panel.findViewById(R.id.iv_tap_icon)
    private val tapTitle: TextView = panel.findViewById(R.id.tv_tap_title)
    private val tapHint: TextView = panel.findViewById(R.id.tv_tap_hint)
    private val tapAction: TextView = panel.findViewById(R.id.tv_tap_action)
    private val tapProgress: ProgressBar = panel.findViewById(R.id.pb_tap)

    private val controller = TapSendController(activity, request.things, this)
    private val navigator = TapSetupNavigator(activity) { controller.recheck() }
    private val watcher = TapPrerequisiteWatcher(activity) { onPrerequisitesChanged() }
    private val smoother = ProgressSmoother()

    private var closing = false

    /**
     * 手机横屏：面板贴右半边、从右侧滑入，卡片放在左半边，卡片区高度接近整屏（决策 2026-09-26 第 8 条）。
     * 平板横屏仍是上下排。打开之后窗口尺寸变了就直接收起（[sizeWatcher]），不用中途切换。
     */
    private var sideBySide = false

    /** 卡片已经开始抬升（第一帧画出来之前关闭的话，没有要落回的东西）。 */
    private var started = false
    private var startListener: ViewTreeObserver.OnPreDrawListener? = null

    /** 天线在 DecorView 里的位置（粒子层的坐标系）。 */
    private val antennaInDecor = PointF()

    private var flow = Flow.NONE
    private var outflow: TapParticleFlight.Outflow? = null

    /** 这一次的流出已经走到头（颗粒全部进了天线）。 */
    private var drained = false
    private var pendingAfterClose: (() -> Unit)? = null
    private var dimAnimator: ValueAnimator? = null

    /** 调试：整段会话的记录（落盘开关关着时为 null）。 */
    private var recorder: TapSessionRecorder? = null

    /**
     * 打开之后窗口尺寸变了（旋转屏幕、调整分屏）：卡片的目标位置、原件的位置、天线的位置全都
     * 变了，就地收起，不播落回动画。两个宿主页面都自己处理旋转，不会重建。
     */
    private val sizeWatcher = View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
        if (r - l != oldR - oldL || b - t != oldB - oldT) {
            root.post { closeNow() }
        }
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = close(null)
    }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                controller.recheck()
                controller.resumeIfPaused()
            }
            Lifecycle.Event.ON_STOP -> controller.pause()
            Lifecycle.Event.ON_DESTROY -> dispose()
            else -> {}
        }
    }

    /** 逐帧：面板进度条跟着传输进度走，天线处的扩散圈跟着粒子实际呈现的进度收缩。 */
    private var lastFrameNanos = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dt = if (lastFrameNanos == 0L) 1f / 60f else (frameTimeNanos - lastFrameNanos) / 1e9f
            lastFrameNanos = frameTimeNanos
            if (isTransferring()) {
                val shown = smoother.step(controller.progress.target(SystemClock.uptimeMillis()), dt)
                val progress = (shown * 1000f).toInt()
                if (progress != tapProgress.progress) recorder?.captureSoon("panel", panel, PANEL_CAPTURE_MS)
                tapProgress.progress = progress
            }
            stage.setPulseShrink(outflow?.presented ?: 0f)
            if (!closing && (isTransferring() || flow != Flow.NONE)) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                stage.setPulseShrink(0f)
            }
        }
    }

    // ---------------------------------------------------------------- 打开

    private fun buildViews() {
        // 调试：分享界面盖上去之前的页面，模拟器在它上面重放整段
        recorder = TapSessionRecorder.start(activity, "send")?.also { it.captureWindow("page") }
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        // 卡模拟只在亮屏时应答：分享界面开着就不熄屏。界面移除时自动撤销
        root.keepScreenOn = true
        dim.setBackgroundColor(Color.BLACK)
        dim.alpha = 0f
        dim.setOnClickListener { close(null) }
        root.addView(dim, FrameLayout.LayoutParams(match, match))
        val decor = activity.window.decorView
        sideBySide = !DisplayUtil.isTablet(activity) && decor.width > decor.height
        if (sideBySide) {
            val lp = FrameLayout.LayoutParams(decor.width / 2, match, Gravity.END)
            panel.setBackgroundResource(R.drawable.bg_tap_share_panel_side)
            (panel as LinearLayout).gravity = Gravity.CENTER_VERTICAL
            root.addView(panel, lp)
        } else {
            root.addView(panel)
        }
        // 舞台叠在面板之上：天线常在面板那一片，等待圈与光要画得出来。舞台不消费触摸
        root.addView(stage, FrameLayout.LayoutParams(match, match))
        root.isClickable = false
        bindPanel()
        // 不支持碰一碰的设备在第一次布局之前就收起那一块：卡片区域按实际的面板高度算
        if (!TapSendController.isSupported(activity)) {
            tapArea.visibility = View.GONE
        }
        panel.visibility = View.INVISIBLE
        stage.visibility = View.INVISIBLE
        content.addView(root, ViewGroup.LayoutParams(match, match))

        // 抬起的卡片按首页的卡片排，舞台知道卡片区之后再加宽、放不下时删减
        val metrics = TapThingCards.listMetrics(activity)
        val cards = request.things.take(MAX_STACK).map {
            TapThingCards.LiftedCard(activity, it, metrics, revealPrivate = true)
        }

        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
        activity.lifecycle.addObserver(lifecycleObserver)

        // 分享界面在所有设备上都用（不支持碰一碰只是隐藏那一块），插图走兼容库，Android 8 起可用
        val panelTopPadding = panel.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val nav = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            if (sideBySide) {
                // 贴右半边、上下通顶：让开状态栏与右侧的导航栏、挖孔
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                panel.setPadding(panel.paddingLeft, bars.top + panelTopPadding, bars.right,
                    bars.bottom + (8f * density).toInt())
            } else {
                panel.setPadding(panel.paddingLeft, panel.paddingTop, panel.paddingRight, nav.bottom + (8f * density).toInt())
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)

        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (root.width == 0 || panel.height == 0) {
                    return true
                }
                root.viewTreeObserver.removeOnPreDrawListener(this)
                startListener = null
                start(cards)
                return true
            }
        }
        startListener = listener
        root.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun start(cards: List<TapThingCards.LiftedCard>) {
        if (closing) {
            return
        }
        started = true
        val rootInsets = ViewCompat.getRootWindowInsets(root)
        val top = rootInsets
            ?.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            ?.top ?: 0
        if (sideBySide) {
            // 左半边：让开左侧的挖孔与底部的导航栏
            val bars = rootInsets?.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            stage.setCardArea(RectF((bars?.left ?: 0).toFloat(), top + 12f * density,
                (root.width - panel.width).toFloat(), root.height - (bars?.bottom ?: 0) - 12f * density))
        } else {
            val panelTop = (root.height - panel.height).toFloat()
            stage.setCardArea(RectF(0f, top + 12f * density, root.width.toFloat(), panelTop - 8f * density))
        }

        // 天线：先在 DecorView 里定位（粒子层的坐标系），再换到舞台里画等待圈
        val decor = activity.window.decorView
        val offset = stageOffsetInDecor()
        if (!TapTransfer.antennaPosition(activity, decor.width, decor.height, antennaInDecor)) {
            // 左右排时屏幕顶部中间正好是面板的左缘：改用卡片区顶部的中间
            val centerX = if (sideBySide) offset.x + (root.width - panel.width) / 2f else decor.width / 2f
            antennaInDecor.set(centerX, top + ANTENNA_FALLBACK_DP * density)
        }
        stage.setAntenna(antennaInDecor.x - offset.x, antennaInDecor.y - offset.y)

        val stageAt = IntArray(2).also { stage.getLocationInWindow(it) }
        val origins = request.sources.take(cards.size).map { source ->
            source.rect?.let {
                RectF(
                    (it.left - stageAt[0]).toFloat(), (it.top - stageAt[1]).toFloat(),
                    (it.right - stageAt[0]).toFloat(), (it.bottom - stageAt[1]).toFloat()
                )
            }
        }
        stage.setCards(cards, origins, request.things.size)
        recorder?.let { recordStart(it) }
        stage.visibility = View.VISIBLE
        stage.lift()

        animateDim(if (AppearanceUtil.isDarkMode(activity)) params.sendDimAlpha[1] else params.sendDimAlpha[0], params.openMs)
        val slide = panelSlideDistance()
        setPanelOffset(slide)
        panel.visibility = View.VISIBLE
        recorder?.event("panel") {
            put("from", slide.toDouble()); put("to", 0.0); put("duration", params.openMs); put("ease", "open")
            put("axis", if (sideBySide) "x" else "y")
        }
        panelOffsetAnimator(0f).setDuration(params.openMs).setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()

        root.addOnLayoutChangeListener(sizeWatcher)
        controller.start()
        if (controller.phase != TapSendController.Phase.HIDDEN) {
            watcher.start()
        }
    }

    /** 调试：抬起开始的那一刻，模拟器重放抬起与落回所需的几何。 */
    private fun recordStart(r: TapSessionRecorder) {
        val offset = stageOffsetInDecor()
        val antenna = stage.antennaInStage
        r.event("start") {
            put("stage", JSONArray().put(offset.x.toDouble()).put(offset.y.toDouble()).put(stage.width).put(stage.height))
            put("slots", stage.describeSlots())
            put("antenna", JSONArray().put(antenna[0].toDouble()).put(antenna[1].toDouble()))
            put("antenna_decor", JSONArray().put(antennaInDecor.x.toDouble()).put(antennaInDecor.y.toDouble()))
            put("total", stage.stackCount)
            put("pulse_start", r.at(stage.pulseStartUptime))
            put("panel", JSONArray().put(panel.left).put(panel.top).put(panel.width).put(panel.height))
            put("dark", AppearanceUtil.isDarkMode(activity))
        }
        TapParticleFlight.afterFrame(stage) {
            stage.slotViews().forEachIndexed { k, view -> r.captureView("card-$k", view) }
            r.captureSoon("panel", panel, PANEL_CAPTURE_MS)
        }
    }

    /** 面板收起时离开屏幕要走的距离：上下排时是面板高，左右排时是面板宽。 */
    private fun panelSlideDistance(): Float = if (sideBySide) panel.width.toFloat() else panel.height.toFloat()

    private fun setPanelOffset(offset: Float) {
        if (sideBySide) panel.translationX = offset else panel.translationY = offset
    }

    private fun panelOffsetAnimator(to: Float) =
        if (sideBySide) panel.animate().translationX(to) else panel.animate().translationY(to)

    private fun stageOffsetInDecor(): PointF {
        val stageAt = IntArray(2).also { stage.getLocationInWindow(it) }
        val decorAt = IntArray(2).also { activity.window.decorView.getLocationInWindow(it) }
        return PointF((stageAt[0] - decorAt[0]).toFloat(), (stageAt[1] - decorAt[1]).toFloat())
    }

    /** NFC、Wi-Fi、位置信息的开关变了：在前台时立刻重新检查（在后台时回到前台会查）。 */
    private fun onPrerequisitesChanged() {
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            controller.recheck()
        }
    }

    // ---------------------------------------------------------------- 面板

    private fun bindPanel() {
        val accent = request.accent
        bindOption(panel.findViewById(R.id.tv_share_text_image)) { request.host.shareTextAndImages() }
        val screenshot = panel.findViewById<TextView>(R.id.tv_share_screenshot)
        if (request.host.longScreenshotAvailable) {
            bindOption(screenshot) { request.host.shareLongScreenshot() }
        } else {
            screenshot.visibility = View.GONE
        }
        bindOption(panel.findViewById(R.id.tv_share_thing_file)) { request.host.shareThingFile() }

        tapIcon.setImageDrawable(bakeTint(tapIcon.drawable, accent))
        lockTapRowHeight()
        BackgroundUtil.applyProgressBarGradient(tapProgress, accent)
        val pill = GradientDrawable()
        pill.cornerRadius = 18f * density
        pill.colors = if (accent.mode == ThingBackground.Mode.GRADIENT) {
            intArrayOf(accent.color, accent.endColor)
        } else {
            intArrayOf(accent.color, accent.color)
        }
        pill.orientation = GradientDrawable.Orientation.LEFT_RIGHT
        tapAction.background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), pill, null)
        tapAction.setOnClickListener {
            controller.missing.firstOrNull()?.let { navigator.open(it) }
        }
    }

    /**
     * 碰一碰那一行的最小高度按「标题一行 + 说明两行」算：说明不再固定占两行，没有说明时收起，图标与实际
     * 显示的文字一起居中；面板高度也不随文案跳动，上方的卡片不会被压住（决策 2026-09-26 第 9 条）。
     */
    private fun lockTapRowHeight() {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val title = tapTitle.text
        val hint = tapHint.text
        tapTitle.text = "A"
        tapHint.text = "A\nA"
        tapTitle.measure(unspecified, unspecified)
        tapHint.measure(unspecified, unspecified)
        val gap = (tapHint.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        tapRow.minimumHeight = max(tapRow.minimumHeight, tapTitle.measuredHeight + gap + tapHint.measuredHeight)
        tapTitle.text = title
        tapHint.text = hint
    }

    /**
     * 图标着色：颜色直接烘进位图。纯色时逐像素等同于原来三选一对话框用的颜色滤镜（SRC_ATOP，
     * 保留图标自己的透明度）；渐变时沿用 [BackgroundUtil.tintDrawable]，它本来就是烘进位图的。
     *
     * 不用颜色滤镜：部分定制系统（Flyme）在深色模式下无视应用的设置强制反色，会把颜色滤镜的
     * 颜色改掉，图标变成黑色；位图的内容不受影响。
     */
    private fun bakeTint(source: Drawable?, accent: ThingBackground): Drawable? {
        if (source == null || accent.mode == ThingBackground.Mode.GRADIENT) {
            return BackgroundUtil.tintDrawable(activity.resources, source, accent)
        }
        val w = source.intrinsicWidth.coerceAtLeast(1)
        val h = source.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val mask = source.mutate()
        mask.colorFilter = null
        mask.setBounds(0, 0, w, h)
        mask.draw(canvas)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = accent.color or (0xFF shl 24)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return BitmapDrawable(activity.resources, bitmap)
    }

    /** 三项的样式与原来的三选一对话框一致：图标与文字按强调色着色，按下是强调色的波纹。 */
    private fun bindOption(view: TextView, action: () -> Unit) {
        val accent = request.accent
        BackgroundUtil.applyTextBackground(view, accent)
        val drawables = view.compoundDrawables
        for (i in drawables.indices) {
            drawables[i] = bakeTint(drawables[i], accent)
        }
        view.setCompoundDrawablesWithIntrinsicBounds(drawables[0], drawables[1], drawables[2], drawables[3])
        view.background = GradientRippleDrawable(accent, shapeOval = false, cornerRadiusPx = 0f)
        view.setOnClickListener { close(action) }
    }

    override fun onPhaseChanged(phase: TapSendController.Phase) {
        if (closing) {
            return
        }
        recorder?.event("phase") { put("name", phase.name) }
        recorder?.captureSoon("panel", panel, PANEL_CAPTURE_MS)
        tapArea.visibility = if (phase == TapSendController.Phase.HIDDEN) View.GONE else View.VISIBLE
        tapAction.visibility = View.GONE
        // 第三版：从可以被碰一直播到关闭，碰上、发送、已发送期间都不断
        stage.setPulse(phase == TapSendController.Phase.READY || phase == TapSendController.Phase.CONNECTING ||
            phase == TapSendController.Phase.SENDING || phase == TapSendController.Phase.SENT ||
            phase == TapSendController.Phase.FAILED)
        when (phase) {
            TapSendController.Phase.HIDDEN -> {}
            TapSendController.Phase.SETUP -> {
                reform()
                val first = controller.missing.first()
                showText(TapSetupNavigator.titleOf(activity, first), TapSetupNavigator.hintOf(activity, first))
                tapAction.text = TapSetupNavigator.actionOf(activity, first)
                tapAction.visibility = View.VISIBLE
                tapProgress.visibility = View.INVISIBLE
            }
            TapSendController.Phase.PREPARING -> {
                // 离开前台时正在流出：这一次作废，已经流出去的部分流回来
                reform()
                showText(activity.getString(R.string.tap_share_preparing), null)
                tapProgress.visibility = View.INVISIBLE
            }
            TapSendController.Phase.READY -> {
                val title = if (controller.lastFailed) R.string.tap_share_failed else R.string.tap_share_ready
                showText(activity.getString(title), activity.getString(R.string.tap_share_ready_hint))
                tapProgress.visibility = View.INVISIBLE
            }
            TapSendController.Phase.CONNECTING -> {
                showText(activity.getString(R.string.tap_share_connecting, controller.peerName), null)
                tapProgress.progress = 0
                tapProgress.visibility = View.VISIBLE
                TapTransfer.performTouchedFeedback(panel)
                smoother.reset()
                beginOutflow()
            }
            TapSendController.Phase.SENDING -> {
                showText(activity.getString(R.string.tap_share_sending, controller.peerName), null)
            }
            TapSendController.Phase.SENT -> {
                // 等最后的颗粒进了天线（onOutflowDrained）再换成「已发送」
                if (drained) {
                    showSent()
                }
            }
            TapSendController.Phase.FAILED -> {
                showText(activity.getString(R.string.tap_share_failed), null)
                tapProgress.visibility = View.INVISIBLE
                if (!reform()) {
                    // 没有在流出：没有要流回的，直接接下一台
                    main.post { if (!closing) controller.readyForNext() }
                }
            }
            TapSendController.Phase.UNAVAILABLE -> {
                reform()
                showText(
                    activity.getString(R.string.tap_share_unavailable),
                    activity.getString(R.string.tap_share_unavailable_hint)
                )
                tapProgress.visibility = View.INVISIBLE
            }
        }
    }

    /** 标题与说明。说明为空时收起，图标只与标题对齐；面板高度由那一行的最小高度托住（[lockTapRowHeight]）。 */
    private fun showText(title: String, hint: String?) {
        recorder?.captureSoon("panel", panel, PANEL_CAPTURE_MS)
        tapTitle.text = title
        if (hint.isNullOrEmpty()) {
            tapHint.visibility = View.GONE
        } else {
            tapHint.text = hint
            tapHint.visibility = View.VISIBLE
        }
    }

    private fun showSent() {
        showText(
            activity.getString(R.string.tap_share_sent, controller.peerName),
            activity.getString(R.string.tap_share_sent_hint)
        )
        tapProgress.progress = 1000
        main.postDelayed({
            if (!closing) {
                tapProgress.visibility = View.INVISIBLE
                relift()
            }
        }, params.sentHoldMs)
    }

    /**
     * 发完之后卡片重新抬起（与打开时同一段抬起），抬起走完再接下一台。颗粒此刻全在天线里、粒子层上
     * 什么也没有，而卡片从透明开始淡入：撤粒子层与显示卡片之间没有可见的交接。
     */
    private fun relift() {
        val current = outflow
        if (current == null || flow != Flow.OUT) {
            // 没用粒子（截不到卡片、粒子层没建起来）：卡片一直在
            if (!reform()) {
                controller.readyForNext()
            }
            return
        }
        outflow = null
        flow = Flow.NONE
        current.release()
        recorder?.event("relift")
        stage.relift { if (!closing) controller.readyForNext() }
    }

    private fun isTransferring(): Boolean {
        val p = controller.phase
        return !closing && (p == TapSendController.Phase.CONNECTING || p == TapSendController.Phase.SENDING)
    }

    private fun startFrames() {
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    // ---------------------------------------------------------------- 粒子

    /** 碰上了：压下投影、截下卡片，交给粒子层流向天线。 */
    private fun beginOutflow() {
        drained = false
        startFrames()
        val existing = outflow
        if (existing != null) {
            // 上一台刚发完、还在流回，就又碰上了：从当前位置接着流出
            flow = Flow.OUT
            existing.animateTo(1f, params.outflowMs, outflowEase) { onOutflowDrained() }
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            drained = true
            return
        }
        flow = Flow.PREPARING
        // 投影压到 0 再截：粒子层接管的那一帧与真实卡片逐像素相同，投影不会突然消失
        stage.animateElevation(0f, params.shadowDropMs)
        main.postDelayed({ captureForOutflow() }, params.shadowDropMs)
    }

    private fun captureForOutflow() {
        if (closing || flow != Flow.PREPARING || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        val region = Rect()
        if (!stage.snapshotRegion(region)) {
            outflowUnavailable()
            return
        }
        TapParticleFlight.capture(stage.stackView, region) { bitmap -> onOutflowSnapshot(bitmap, region) }
    }

    private fun onOutflowSnapshot(bitmap: Bitmap?, region: Rect) {
        if (bitmap == null || closing || flow != Flow.PREPARING) {
            bitmap?.recycle()
            if (!closing && flow == Flow.PREPARING) {
                outflowUnavailable()
            }
            return
        }
        val bounds = RectF()
        TapParticleFlight.boundsInDecor(activity, stage.stackView, region, bounds)
        outflow = TapParticleFlight.Outflow(activity, bitmap, bounds, PointF(antennaInDecor.x, antennaInDecor.y),
            object : TapParticleFlight.Outflow.Listener {
                override fun onTakenOver() {
                    stage.setCardsShown(false)
                    if (flow == Flow.PREPARING) {
                        flow = Flow.OUT
                        outflow?.animateTo(1f, params.outflowMs, outflowEase) { onOutflowDrained() }
                    }
                }

                override fun onReturned() {
                    outflow = null
                    showCardsAgain()
                    onReformFinished()
                }

                override fun onLost() {
                    outflow = null
                    val wasReturning = flow == Flow.RETURNING
                    showCardsAgain()
                    if (wasReturning) {
                        onReformFinished()
                    } else {
                        onOutflowDrained()
                    }
                }
            })
    }

    /** 截不到卡片或粒子层建不起来：不用粒子，只走文字与进度。 */
    private fun outflowUnavailable() {
        flow = Flow.NONE
        stage.animateElevation(1f, params.shadowDropMs)
        onOutflowDrained()
    }

    private fun showCardsAgain() {
        flow = Flow.NONE
        stage.setCardsShown(true)
        stage.animateElevation(1f, params.shadowRestoreMs)
    }

    private fun onOutflowDrained() {
        drained = true
        if (!closing && controller.phase == TapSendController.Phase.SENT) {
            showSent()
        }
    }

    /**
     * 让流出去的颗粒流回来、拼回卡片，流回播完由 [onReformFinished] 接下一台。发完、发送失败、
     * 流出被打断（离开前台、Wi-Fi Direct 断开）都走这里。返回是否真的开始了流回。
     */
    private fun reform(): Boolean {
        return when (flow) {
            Flow.NONE -> false
            Flow.PREPARING -> {
                // 还没截图就结束了：直接恢复投影，截图回来时会被丢掉
                flow = Flow.NONE
                stage.animateElevation(1f, params.shadowRestoreMs)
                false
            }
            Flow.OUT -> {
                flow = Flow.RETURNING
                startFrames()
                outflow?.animateTo(0f, params.reformMs, reformEase, null)
                true
            }
            Flow.RETURNING -> true
        }
    }

    private fun onReformFinished() {
        if (!closing) {
            controller.readyForNext()
        }
    }

    // ---------------------------------------------------------------- 舞台回调

    override fun onLiftFinished() {}

    override fun onDropFinished() {
        finishClose()
    }

    // ---------------------------------------------------------------- 关闭

    /** 关闭分享界面；[then] 在卡片落回之后执行。 */
    fun close(then: (() -> Unit)?) {
        if (closing) {
            return
        }
        closing = true
        if (!started) {
            // 卡片还没抬升（按键事件不等下一帧）：没有要落回的，直接撤掉
            dispose()
            request.host.onShareClosed(false)
            then?.invoke()
            return
        }
        pendingAfterClose = then
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        main.removeCallbacksAndMessages(null)
        controller.stop()
        backCallback.remove()
        // 粒子层撤掉，卡片立刻回到真实视图再落回
        outflow?.release()
        outflow = null
        flow = Flow.NONE
        stage.setCardsShown(true)
        stage.setPulse(false)
        animateDim(0f, params.closeMs)
        recorder?.event("close")
        val slide = panelSlideDistance()
        recorder?.event("panel") {
            put("from", (if (sideBySide) panel.translationX else panel.translationY).toDouble())
            put("to", slide.toDouble()); put("duration", params.closeMs); put("ease", "close")
            put("axis", if (sideBySide) "x" else "y")
        }
        panelOffsetAnimator(slide).setDuration(params.closeMs)
            .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f)).start()
        stage.drop()
    }

    /** 不播落回动画，立刻收起（窗口尺寸变了，原位置已经不对）。 */
    private fun closeNow() {
        if (closing) {
            return
        }
        closing = true
        dispose()
        request.host.onShareClosed(controller.sentAtLeastOnce)
    }

    private fun finishClose() {
        root.postOnAnimation {
            dispose()
            request.host.onShareClosed(controller.sentAtLeastOnce)
            pendingAfterClose?.invoke()
            pendingAfterClose = null
        }
    }

    private fun dispose() {
        recorder?.finish()
        recorder = null
        // 监听挂在窗口的观察者上，要在视图摘下来之前取消，摘下来之后就找不到那个观察者了
        startListener?.let {
            if (root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnPreDrawListener(it)
            }
        }
        startListener = null
        root.removeOnLayoutChangeListener(sizeWatcher)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        main.removeCallbacksAndMessages(null)
        outflow?.release()
        outflow = null
        controller.stop()
        navigator.release()
        watcher.stop()
        backCallback.remove()
        activity.lifecycle.removeObserver(lifecycleObserver)
        dimAnimator?.cancel()
        stage.release()
        (root.parent as? ViewGroup)?.removeView(root)
    }

    private fun animateDim(to: Float, duration: Long) {
        dimAnimator?.cancel()
        recorder?.event("dim") { put("from", dim.alpha.toDouble()); put("to", to.toDouble()); put("duration", duration) }
        val animator = ValueAnimator.ofFloat(dim.alpha, to)
        animator.duration = duration
        animator.addUpdateListener { dim.alpha = it.animatedValue as Float }
        dimAnimator = animator
        animator.start()
    }

    companion object {
        private const val MAX_STACK = 4
        // 罩层、打开与关闭的时长见 shared/tap-transfer/particles.properties（send_*）
        // 流出、流回的时长与缓动，以及「已发送」的停留，见 shared/tap-transfer/particles.properties

        /** 取不到天线位置时流向卡片区顶部的中间（上下排时就是屏幕顶部中间）：状态栏下方这么远。 */
        private const val ANTENNA_FALLBACK_DP = 28f

        /** 调试（会话记录）：面板文字与进度最多这么久抓一张。 */
        private const val PANEL_CAPTURE_MS = 90L

        /** 打开分享界面。 */
        @JvmStatic
        fun show(activity: FragmentActivity, request: Request): TapShareOverlay {
            val overlay = TapShareOverlay(activity, request)
            overlay.buildViews()
            return overlay
        }
    }
}
