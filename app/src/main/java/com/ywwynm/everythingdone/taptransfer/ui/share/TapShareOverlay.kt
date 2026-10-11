package com.ywwynm.everythingdone.taptransfer.ui.share

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
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
import com.ywwynm.everythingdone.model.ThingListEntry
import com.ywwynm.everythingdone.taptransfer.TapTransfer
import com.ywwynm.everythingdone.taptransfer.ui.ProgressSmoother
import com.ywwynm.everythingdone.taptransfer.ui.TapPrerequisiteWatcher
import com.ywwynm.everythingdone.taptransfer.ui.TapSetupNavigator
import com.ywwynm.everythingdone.taptransfer.ui.card.TapThingCards
import com.ywwynm.everythingdone.taptransfer.ui.flight.TapParticleFlight
import com.ywwynm.everythingdone.taptransfer.ui.flight.TapParticleParams
import com.ywwynm.everythingdone.taptransfer.ui.flight.TapSessionRecorder
import com.ywwynm.everythingdone.thingfile.ThingShareContent
import com.ywwynm.everythingdone.utils.AppearanceUtil
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

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
     * @param content 要分享的记事与文件夹，已在主线程复制好：碰一碰的记事文件在后台线程写。
     * @param folderEntries 顶层的文件夹在列表里的那一项（按文件夹 id）：抬起的卡片照它画，计数、缩略图与列表
     *        一致。缺的按 [content] 里的子树算计数、缩略图由首页适配器按当前的筛选取。
     * @param sources 与 [ThingShareContent.items] 同序。卡片从这些位置抬升（选择模式）；为空时卡片从页面里升起
     *        （详情页、文件夹里的「分享此文件夹」）。
     */
    class Request(
        val content: ThingShareContent,
        val folderEntries: Map<Long, ThingListEntry.FolderEntry>,
        val sources: List<Source>,
        val accent: ThingBackground,
        val host: Host
    ) {
        /** 只分享记事（详情页）。 */
        constructor(things: List<Thing>, sources: List<Source>, accent: ThingBackground, host: Host) :
            this(ThingShareContent.ofThings(things), emptyMap(), sources, accent, host)

        /** 顶层各项的卡片，与 [ThingShareContent.items] 同序。 */
        internal val cardItems: List<TapThingCards.Item> = content.items.map { node ->
            when (node) {
                is ThingShareContent.ThingNode -> TapThingCards.Item.ThingItem(node.thing)
                is ThingShareContent.FolderNode -> TapThingCards.Item.FolderItem(
                    folderEntries[node.folder.id] ?: ThingListEntry.FolderEntry(
                        folder = node.folder,
                        recursiveThingCount = ThingShareContent(node.children).things.size,
                        directFolderCount = node.children.count { it is ThingShareContent.FolderNode }
                    )
                )
            }
        }
    }

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
    private val shareTitle: TextView = panel.findViewById(R.id.tv_tap_share_title)
    private val tapArea: View = panel.findViewById(R.id.ll_tap_area)
    private val tapRow: LinearLayout = panel.findViewById(R.id.ll_tap_row)
    private val tapIcon: ImageView = panel.findViewById(R.id.iv_tap_icon)
    private val tapTitle: TextView = panel.findViewById(R.id.tv_tap_title)
    private val tapHint: TextView = panel.findViewById(R.id.tv_tap_hint)
    private val tapAction: TextView = panel.findViewById(R.id.tv_tap_action)
    private val tapProgress: ProgressBar = panel.findViewById(R.id.pb_tap)

    private val controller = TapSendController(activity, request.content, request.cardItems, this)
    private val navigator = TapSetupNavigator(activity) { controller.recheck() }
    private val watcher = TapPrerequisiteWatcher(activity) { onPrerequisitesChanged() }
    private val smoother = ProgressSmoother()

    private var closing = false

    /**
     * 手机横屏：面板悬浮在右侧、从右侧滑入，卡片放在它左边，卡片区高度接近整屏（决策 2026-09-26 第 8 条，面板的样子
     * 2026-09-30 改成与竖屏相同的悬浮面板）。平板横屏仍是上下排。打开之后窗口变了按新的形状就地重排（[relayoutWatcher]）。
     */
    private var sideBySide = false

    /** 抬起的卡片：窗口变了要按新的首页尺寸重新排版。 */
    private var liftedCards: List<TapThingCards.LiftedCard> = emptyList()

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

    /** 卡片区与天线是按哪一种窗口排的：宽、高与屏幕的方向（转 180 度时尺寸不变，天线换了位置）。 */
    private var laidOutWidth = 0
    private var laidOutHeight = 0
    private var laidOutRotation = 0

    /** 就地重排走了一半：面板已经换了形状，等它按新的形状量好、排好，再放卡片区与天线。 */
    private var placementPending = false

    /**
     * 打开之后窗口变了（旋转屏幕、调整分屏）：面板、卡片区、天线与卡片的目标位置全都变了，就地重排（[relayout]），
     * 不收起。放在绘制之前：发现窗口变了的那一帧不画，面板按新的形状排好之后放好卡片再画，旋转之后的第一帧就是
     * 新的样子。两个宿主页面都自己处理旋转，不会重建。
     */
    private val relayoutWatcher = ViewTreeObserver.OnPreDrawListener {
        if (closing || !started) {
            true
        } else if (placementPending) {
            placementPending = false
            placeCardAreaAndAntenna()
            recorder?.let { recordLayout(it, "relayout") }
            true
        } else if (root.width == laidOutWidth && root.height == laidOutHeight &&
            displayRotation() == laidOutRotation) {
            true
        } else {
            relayout()
            false
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
        root.addView(panel)
        applyPanelLayout()
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
        val cards = request.cardItems.take(MAX_STACK).map {
            TapThingCards.LiftedCard.of(activity, it, metrics, revealPrivate = true)
        }
        liftedCards = cards

        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
        activity.lifecycle.addObserver(lifecycleObserver)

        // 分享界面在所有设备上都用（不支持碰一碰只是隐藏那一块），插图走兼容库，Android 8 起可用
        val panelSpacing = activity.resources.getDimensionPixelSize(R.dimen.thing_card_outer_spacing)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val lp = panel.layoutParams as FrameLayout.LayoutParams
            if (sideBySide) {
                // 悬浮在右侧：在状态栏与导航栏之间上下居中（不压状态栏：首页、详情页上的状态栏图标是白色的），
                // 右边让开导航栏、挖孔再留一个卡片外边距
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                val right = bars.right + panelSpacing
                if (lp.topMargin != bars.top || lp.bottomMargin != bars.bottom || lp.rightMargin != right) {
                    lp.topMargin = bars.top
                    lp.bottomMargin = bars.bottom
                    lp.rightMargin = right
                    panel.layoutParams = lp
                }
            } else {
                // 悬浮在导航栏之上，与底边隔一个卡片外边距
                val nav = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
                )
                if (lp.bottomMargin != nav.bottom + panelSpacing) {
                    lp.bottomMargin = nav.bottom + panelSpacing
                    panel.layoutParams = lp
                }
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
        laidOutWidth = root.width
        laidOutHeight = root.height
        laidOutRotation = displayRotation()
        placeCardAreaAndAntenna()

        val stageAt = IntArray(2).also { stage.getLocationInWindow(it) }
        val origins = request.sources.take(cards.size).map { source ->
            source.rect?.let {
                RectF(
                    (it.left - stageAt[0]).toFloat(), (it.top - stageAt[1]).toFloat(),
                    (it.right - stageAt[0]).toFloat(), (it.bottom - stageAt[1]).toFloat()
                )
            }
        }
        stage.setCards(cards, origins, request.cardItems.size)
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

        root.viewTreeObserver.addOnPreDrawListener(relayoutWatcher)
        controller.start()
        if (controller.phase != TapSendController.Phase.HIDDEN) {
            watcher.start()
        }
    }

    /**
     * 按窗口此刻的形状排面板：与「调整颜色」面板一样悬浮（四角圆角，最宽 480dp，高度随内容，决策 2026-09-27）。竖屏与
     * 平板在底部，左右与底边各留一个卡片外边距；手机横屏在右侧、上下居中，宽度与竖屏时相同（屏幕短边减两侧留白，也不超过
     * 半屏），2026-09-30 取代原来的贴满右半边、上下通高（上下右都空着一大块）。离底边、状态栏与右边的距离在插图回调里
     * 加上系统栏。打开时排一次，窗口变了再排一次（[relayout]）。
     */
    private fun applyPanelLayout() {
        val decor = activity.window.decorView
        val res = activity.resources
        val decorWidth = if (decor.width > 0) decor.width else res.displayMetrics.widthPixels
        val decorHeight = if (decor.height > 0) decor.height else res.displayMetrics.heightPixels
        sideBySide = !DisplayUtil.isTablet(activity) && decorWidth > decorHeight
        val spacing = res.getDimensionPixelSize(R.dimen.thing_card_outer_spacing)
        val maxWidth = res.getDimensionPixelSize(R.dimen.thing_card_appearance_max_width)
        panel.layoutParams = if (sideBySide) {
            val width = minOf(decorHeight - 2 * spacing, decorWidth / 2 - 2 * spacing, maxWidth)
            FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL).also { it.rightMargin = spacing }
        } else {
            FrameLayout.LayoutParams(min(decorWidth - 2 * spacing, maxWidth), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also { it.bottomMargin = spacing }
        }
        val corner = res.getDimension(R.dimen.app_chrome_dialog_popup_corner_radius)
        stage.setPanel(panel, request.accent, FloatArray(8) { corner })
    }

    /**
     * 卡片区与天线，按面板此刻的位置算：上下排时是状态栏之下、面板之上；左右排时是面板左边，让开左侧的挖孔与底部
     * 的导航栏。天线先在 DecorView 里定位（粒子层的坐标系），再换到舞台里画等待圈；不能碰一碰的设备没有等待圈，
     * 卡片也不必让开天线，在卡片区里居中。
     */
    private fun placeCardAreaAndAntenna() {
        val rootInsets = ViewCompat.getRootWindowInsets(root)
        val top = rootInsets
            ?.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            ?.top ?: 0
        val area = if (sideBySide) {
            val bars = rootInsets?.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            RectF((bars?.left ?: 0).toFloat(), top + 12f * density, panel.left.toFloat(),
                root.height - (bars?.bottom ?: 0) - 12f * density)
        } else {
            RectF(0f, top + 12f * density, root.width.toFloat(), panel.top - 8f * density)
        }
        stage.setCardArea(area)
        val decor = activity.window.decorView
        val offset = stageOffsetInDecor()
        if (TapSendController.isSupported(activity)) {
            if (!TapTransfer.antennaPosition(activity, decor.width, decor.height, antennaInDecor)) {
                // 左右排时屏幕顶部中间落在面板附近：改用卡片区顶部的中间
                val centerX = if (sideBySide) offset.x + area.centerX() else decor.width / 2f
                antennaInDecor.set(centerX, top + ANTENNA_FALLBACK_DP * density)
            }
            stage.setAntenna(antennaInDecor.x - offset.x, antennaInDecor.y - offset.y)
        }
    }

    /** 屏幕此刻的方向（天线的位置随它换）。Android 11 以下取不到，按不变算：那些版本也拿不到天线的位置。 */
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display?.rotation ?: 0 else 0

    /**
     * 窗口变了（旋转屏幕、调整分屏）：就地重排，不收起（取代决策 2026-09-24 第 10 条的「直接收起」）。
     *
     * 面板按新的形状重排、直接停在打开的位置；卡片按新的首页尺寸重新排版，面板排好之后再按新的卡片区放
     * （[relayoutWatcher]）。抬起时的原位已经不对了，之后落回改为沉回页面里。正在流出或流回的颗粒是按旧的屏幕
     * 坐标算的，撤掉、卡片直接显示；传输本身不受影响，进度照走，发完照常显示「已发送」再接下一台。
     */
    private fun relayout() {
        laidOutWidth = root.width
        laidOutHeight = root.height
        laidOutRotation = displayRotation()
        dropParticlesForRelayout()
        panel.animate().cancel()
        panel.translationX = 0f
        panel.translationY = 0f
        applyPanelLayout()
        stage.clearOrigins()
        val metrics = TapThingCards.listMetrics(activity)
        liftedCards.forEach { it.updateHome(metrics) }
        // 插图回调按新的排法再走一遍：上下排的离底距离、左右排让开系统栏的内边距
        ViewCompat.requestApplyInsets(root)
        placementPending = true
        recorder?.event("relayout-begin") { put("side_by_side", sideBySide) }
    }

    /** 就地重排时撤掉在飞的颗粒（按旧的屏幕坐标算的），卡片直接显示，按流出、流回各自走完的样子接着往下走。 */
    private fun dropParticlesForRelayout() {
        val was = flow
        if (was == Flow.NONE) {
            return
        }
        outflow?.release()
        outflow = null
        flow = Flow.NONE
        stage.setCardsShown(true)
        stage.animateElevation(1f, params.shadowRestoreMs)
        when (was) {
            // 流出还没走完：「已发送」不必再等颗粒进天线
            Flow.PREPARING, Flow.OUT -> if (!drained) onOutflowDrained()
            Flow.RETURNING -> onReformFinished()
            Flow.NONE -> {}
        }
    }

    /** 调试：抬起开始的那一刻，模拟器重放抬起与落回所需的几何。 */
    private fun recordStart(r: TapSessionRecorder) {
        recordLayout(r, "start")
        TapParticleFlight.afterFrame(stage) {
            stage.slotViews().forEachIndexed { k, view -> r.captureView("card-$k", view) }
            r.captureSoon("panel", panel, PANEL_CAPTURE_MS)
        }
    }

    /** 调试：此刻的几何（打开时、就地重排之后各记一次）。 */
    private fun recordLayout(r: TapSessionRecorder, type: String) {
        val offset = stageOffsetInDecor()
        val antenna = stage.antennaInStage
        r.event(type) {
            put("stage", JSONArray().put(offset.x.toDouble()).put(offset.y.toDouble()).put(stage.width).put(stage.height))
            put("slots", stage.describeSlots())
            put("antenna", JSONArray().put(antenna[0].toDouble()).put(antenna[1].toDouble()))
            put("antenna_decor", JSONArray().put(antennaInDecor.x.toDouble()).put(antennaInDecor.y.toDouble()))
            put("total", stage.stackCount)
            put("pulse_start", r.at(stage.pulseStartUptime))
            put("panel", JSONArray().put(panel.left).put(panel.top).put(panel.width).put(panel.height))
            put("dark", AppearanceUtil.isDarkMode(activity))
            put("side_by_side", sideBySide)
            // 强调色（面板上的扩散圈、标题与图标的颜色）：模拟器按它画面板上的那一段圈
            put("accent", JSONObject(request.accent.toJson()))
        }
    }

    /** 面板收起时离开屏幕要走的距离：上下排时从面板上沿到屏幕底边，左右排时从面板左缘到屏幕右边（都含外边的留空）。 */
    private fun panelSlideDistance(): Float =
        if (sideBySide) (root.width - panel.left).toFloat() else (root.height - panel.top).toFloat()

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
        // 分享的都是文件夹时标题说「分享文件夹」，其余（含混选）仍是「分享记事」
        val items = request.content.items
        shareTitle.text = com.ywwynm.everythingdone.utils.LocalizedText.selectionShareTitle(
            activity, items.all { it is ThingShareContent.FolderNode }, items.size)
        BackgroundUtil.applyTextBackground(shareTitle, accent)
        bindOption(R.id.ll_share_text_image, R.id.iv_share_text_image, R.id.tv_share_text_image) {
            request.host.shareTextAndImages()
        }
        if (request.host.longScreenshotAvailable) {
            bindOption(R.id.ll_share_screenshot, R.id.iv_share_screenshot, R.id.tv_share_screenshot) {
                request.host.shareLongScreenshot()
            }
        } else {
            panel.findViewById<View>(R.id.ll_share_screenshot).visibility = View.GONE
        }
        bindOption(R.id.ll_share_thing_file, R.id.iv_share_thing_file, R.id.tv_share_thing_file) {
            request.host.shareThingFile()
        }

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

    /**
     * 一项分享方式：整格可点，图标与文字按强调色着色；按下时的波纹是图标周围的圆（图标复制格子的按下状态，
     * 波纹从按下处向圆心收拢，决策 2026-09-27）。
     */
    private fun bindOption(cellId: Int, iconId: Int, labelId: Int, action: () -> Unit) {
        val accent = request.accent
        val cell = panel.findViewById<View>(cellId)
        val icon = panel.findViewById<ImageView>(iconId)
        BackgroundUtil.applyTextBackground(panel.findViewById(labelId), accent)
        val drawable = bakeTint(icon.drawable, accent)
        icon.setImageDrawable(drawable)
        fitOptionIcon(icon, drawable)
        icon.background = GradientRippleDrawable(accent, shapeOval = true, centered = true)
        cell.setOnClickListener { close(action) }
    }

    /**
     * 三个图标共用 100dp 的画布（墨迹 30–38.5dp、垂直居中，见 `act_share_thing_file.xml`）：按
     * [OPTION_ICON_CANVAS_DP] 画在图标格子正中，墨迹缩小、画布四周的空白裁掉，图标与文字也就靠近了。
     */
    private fun fitOptionIcon(icon: ImageView, drawable: Drawable?) {
        val w = drawable?.intrinsicWidth ?: return
        val h = drawable.intrinsicHeight
        if (w <= 0 || h <= 0) return
        val canvas = OPTION_ICON_CANVAS_DP * density
        val box = icon.layoutParams.width.toFloat()
        icon.imageMatrix = Matrix().apply {
            setScale(canvas / w, canvas / h)
            postTranslate((box - canvas) / 2f, (box - canvas) / 2f)
        }
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
        if (root.viewTreeObserver.isAlive) {
            root.viewTreeObserver.removeOnPreDrawListener(relayoutWatcher)
        }
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

        /** 分享方式的图标（100dp 的画布）在 48dp 的格子里按这么大画：墨迹约为原来的 0.72。 */
        private const val OPTION_ICON_CANVAS_DP = 72f

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
