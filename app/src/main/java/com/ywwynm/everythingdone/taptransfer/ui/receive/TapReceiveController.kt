package com.ywwynm.everythingdone.taptransfer.ui.receive

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.RoundedCorner
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.taptransfer.TapPrerequisite
import com.ywwynm.everythingdone.taptransfer.TapTransfer
import com.ywwynm.everythingdone.taptransfer.transport.TapFailure
import com.ywwynm.everythingdone.taptransfer.transport.TapIncomingOffer
import com.ywwynm.everythingdone.taptransfer.transport.TapReceiverTransport
import com.ywwynm.everythingdone.taptransfer.ui.ProgressSmoother
import com.ywwynm.everythingdone.taptransfer.ui.TapPrerequisiteWatcher
import com.ywwynm.everythingdone.taptransfer.ui.TapProgressModel
import com.ywwynm.everythingdone.taptransfer.ui.TapSetupNavigator
import com.ywwynm.everythingdone.taptransfer.ui.flight.TapParticleFlight
import com.ywwynm.everythingdone.taptransfer.ui.flight.TapParticleParams
import com.ywwynm.everythingdone.taptransfer.ui.flight.TapSessionRecorder
import com.ywwynm.everythingdone.thingfile.ThingFileImporter
import com.ywwynm.everythingdone.thingfile.ThingFileReader
import com.ywwynm.everythingdone.utils.AppearanceUtil
import com.ywwynm.everythingdone.views.ActivityHeader
import com.ywwynm.everythingdone.views.particledismiss.ParticleCardSurfaceHandoff
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 首页的接收记事：下拉按钮、进入前的逐项引导、接收状态、收到后的导入与拼出。
 *
 * - 按钮与下拉手势由 [TapReceivePull] 负责；
 * - 接收状态下 [TapReceiveStageView] 盖住首页（按钮除外），列表完全不动；
 * - 收到的记事文件经 [ThingFileReader] 读、[ThingFileImporter] 写进发起接收时所在的文件夹，
 *   每写好一条就由 [Host] 插进列表并先藏起来，列表随之为它们腾出空位（有置顶记事、空位不在
 *   屏幕上时先滚过去）。
 *
 * 粒子（第三版，设计见 `docs/features/thing-transfer/design-2026-09-25-particle-motion.md`）：两层共用一条
 * 倒放时间轴（[TapReceiveTimeline]），流入开始时交叉淡化——
 * - 碰上之后：记事内容还没到，按握手里的卡片形状预计落点（[TapListPrediction]），把代表色的圆角矩形当作
 *   代理卡片，播它的倒放（进度期间那一层）：颗粒从天线处浮现、从四周的光尘带流进来，朝落点飘，随传输进度推进，
 *   数据到齐之后越来越慢但不停；
 * - 数据到齐、插进列表之后：拼卡那一层的运动只取决于新卡片的位置与大小（按同样大小的代表色卡片算），插入之后
 *   第一次布局就知道了，所以那时就开始建材与预算（占位快照）；列表让位、图片加载好、截出真实快照之后交进去换上
 *   颜色。它的第一帧上屏即流入开始：从同一个进度淡入，进度期间那一层淡出，时间轴平滑加速到拼好；四周的光尘
 *   就近接手一部分流向四周的颗粒（[TapDustHandoff]）。拼好的那一刻列表里的卡片显出来。
 *
 * 所有方法都在主线程调用。
 */
class TapReceiveController(
    private val activity: FragmentActivity,
    private val host: Host
) : TapReceiverTransport.Listener, TapReceiveStageView.Listener {

    interface Host {
        val container: FrameLayout
        val recyclerView: RecyclerView
        val header: ActivityHeader
        val actionbar: View

        /** 当前能不能拉出按钮：正在进行的列表、正常模式、不在搜索、没有按类型筛选。 */
        fun isReceiveEligible(): Boolean

        fun currentFolderId(): Long?

        /** 进入 / 退出接收状态：锁住抽屉、藏起新建按钮、保持屏幕常亮。 */
        fun onReceivingChanged(receiving: Boolean)

        /** 一条收到的记事刚写进数据库：插进列表，[hidden] 时先藏起来。返回它在列表里的位置。 */
        fun insertReceivedThing(thing: Thing, hidden: Boolean): Int

        /** 这条记事在列表里的位置，不在列表里时返回 -1。 */
        fun listPositionOf(id: Long): Int

        /** 收到的记事会插在列表的哪个位置：置顶的记事与文件夹之后（头部占位项是第 0 项）。 */
        fun receivedInsertPosition(): Int

        /** 列表里这条记事的卡片（已布局好的），不在屏幕上时返回 null。 */
        fun findReceivedCard(id: Long): View?

        fun revealReceivedThings(ids: Collection<Long>)

        /** 一批记事写完：刷新头部的条数、抽屉里的计数、空列表提示。 */
        fun onReceivedThingsCommitted()
    }

    private val main = Handler(Looper.getMainLooper())
    private val density = activity.resources.displayMetrics.density
    private val params = TapParticleParams.get(activity)

    private val pull = TapReceivePull(
        host.container, host.recyclerView, host.header, host.actionbar,
        host::isReceiveEligible
    ) { onButtonClicked() }

    private val navigator = TapSetupNavigator(activity) { granted -> onPermissionResult(granted) }
    private val watcher = TapPrerequisiteWatcher(activity) { onPrerequisitesChanged() }

    /** 从开关面板回来时开关可能还在打开中：等一会儿广播，还没开再告诉用户。 */
    private val settleCheck = Runnable { settleGuidance() }

    private var receiving = false
    private var stage: TapReceiveStageView? = null
    private var transport: TapReceiverTransport? = null
    private var folderId: Long? = null

    /** 正在引导用户打开的那一项；从系统界面回来时据此判断有没有打开。 */
    private var guiding: TapPrerequisite? = null

    private val model = TapProgressModel()
    private val smoother = ProgressSmoother(minDurationS = 1.2f)
    private var shownPercent = -1
    private var senderName = ""

    // 这一台的接收状态：碰上时建立，拼出、失败或退出时清掉
    private var busy = false
    private var receivedFile: File? = null
    private var importedIds: MutableList<Long>? = null
    private var landingStarted = false

    /** 每一次碰触一个编号：等列表、等滚动、等图片的回调据此判断自己是不是已经过时。 */
    private var landingGeneration = 0
    private val hiddenIds = LinkedHashSet<Long>()

    /** 收到的记事的代表色（按 id），拼卡那一层的代表色卡片按它上色。 */
    private val colorsById = HashMap<Long, Int>()

    // ---- 粒子：两层与时间轴
    private val timeline = TapReceiveTimeline(params.emergePace[0], params.emergePace[1], params.emergePace[2],
        params.emergePace[3], params.appearS)
    private var proxy: TapParticleFlight.ReceiveLayer? = null
    private var real: TapParticleFlight.ReceiveLayer? = null
    private var handoff: TapDustHandoff? = null

    /** 这一次接收两层共用的种子（碰上时取）：预计准确时两层逐片相同。 */
    private var particleSeed = 0

    /** 进度期间那一层从建起到第一帧上屏用了多久（秒）：拼卡那一层按它估计流入开始的时刻。 */
    private var proxyCreatedS = Double.NaN
    private var realCreatedS = Double.NaN
    private var prepareEstimateS = DEFAULT_PREPARE_S

    /** 拼卡那一层拼出的卡片；拼好时显出来。 */
    private var assembling: List<Pair<Long, View>> = emptyList()
    private var assembledCount = 0

    /**
     * 提前建的拼卡那一层（占位快照）：建它时各张卡片的 id、矩形（DecorView 坐标）与快照范围。截出真实快照时
     * 逐项核对，对得上才交进去，对不上（让位之后位置变了）就撤掉、按截出来的快照重建。
     */
    private class EarlyReal(val ids: List<Long>, val cards: List<RectF>, val bounds: RectF)

    private var early: EarlyReal? = null

    /** 调试：整段会话的记录（落盘开关关着时为 null）；插入第一条之前抓过页面没有。 */
    private var recorder: TapSessionRecorder? = null
    private var recordedBeforeInsert = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exit(immediate = false)
    }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> onHostResumed()
            // 离开首页、锁屏即结束（决策 2026-09-24「接收状态不限时，直到用户退出」）
            Lifecycle.Event.ON_STOP -> if (receiving) exit(immediate = true)
            Lifecycle.Event.ON_DESTROY -> {
                stopGuiding()
                navigator.release()
            }
            else -> {}
        }
    }

    /**
     * 逐帧（碰上到拼好）：提示里的百分比跟着传输进度走（入组那一段按时间渐近，不会停在 0）；两层按倒放
     * 时间轴取帧、交叉淡化；光尘带按传输进度扩散。
     */
    private var lastFrameNanos = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!receiving || !busy) {
                return
            }
            val dt = if (lastFrameNanos == 0L) 1f / 60f else (frameTimeNanos - lastFrameNanos) / 1e9f
            lastFrameNanos = frameTimeNanos
            val shown = smoother.step(model.target(SystemClock.uptimeMillis()), dt)
            if (!landingStarted) {
                val percent = (shown * 100f).toInt().coerceIn(0, 100)
                if (percent != shownPercent) {
                    shownPercent = percent
                    pull.setHint(activity.getString(R.string.tap_receive_progress, senderName, percent))
                }
            }
            advanceParticles(frameTimeNanos / 1e9, shown)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun attach() {
        pull.attach()
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
        activity.lifecycle.addObserver(lifecycleObserver)
    }

    val isReceiving: Boolean
        get() = receiving

    /** 进入选择模式等情况下收起按钮。 */
    fun closeSlot(anim: Boolean) {
        pull.closeSlot(anim)
    }

    // ---------------------------------------------------------------- 进入

    private fun onButtonClicked() {
        if (receiving) {
            exit(immediate = false)
            return
        }
        if (!TapTransfer.isEntryAvailable(activity)) {
            return
        }
        guideOrEnter()
    }

    /** 缺什么就先引导打开什么，全部就绪后自动进入（决策 2026-09-24「逐项引导，就绪后自动继续」）。 */
    private fun guideOrEnter() {
        main.removeCallbacks(settleCheck)
        val missing = TapTransfer.missingPrerequisites(activity)
        if (missing.isEmpty()) {
            stopGuiding()
            enter()
            return
        }
        val next = missing.first()
        guiding = next
        watcher.start()
        navigator.open(next)
    }

    private fun stopGuiding() {
        guiding = null
        watcher.stop()
        main.removeCallbacks(settleCheck)
    }

    private fun onPermissionResult(granted: Boolean) {
        if (guiding != TapPrerequisite.PERMISSION) {
            return
        }
        if (!granted) {
            giveUp(TapPrerequisite.PERMISSION)
            return
        }
        guideOrEnter()
    }

    private fun onHostResumed() {
        val step = guiding ?: return
        // 授权框的结果由回调给出；只有被送去应用详情页授权的那一次，要在回来时自己查
        if (step == TapPrerequisite.PERMISSION && !navigator.consumePermissionSettingsReturn()) {
            return
        }
        if (!TapTransfer.missingPrerequisites(activity).contains(step)) {
            guideOrEnter()
            return
        }
        if (step == TapPrerequisite.PERMISSION) {
            giveUp(step)
            return
        }
        main.removeCallbacks(settleCheck)
        main.postDelayed(settleCheck, SWITCH_SETTLE_MS)
    }

    /** 开关状态变了。只在前台时继续：打开读取模式、弹下一个面板都要求页面在前台，不在前台就等回来再说。 */
    private fun onPrerequisitesChanged() {
        val step = guiding ?: return
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return
        }
        if (!TapTransfer.missingPrerequisites(activity).contains(step)) {
            guideOrEnter()
        }
    }

    private fun settleGuidance() {
        val step = guiding ?: return
        if (TapTransfer.missingPrerequisites(activity).contains(step)) {
            giveUp(step)
        } else {
            guideOrEnter()
        }
    }

    private fun giveUp(step: TapPrerequisite) {
        stopGuiding()
        Toast.makeText(
            activity,
            activity.getString(R.string.tap_setup_still_missing, TapSetupNavigator.titleOf(activity, step)),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun enter() {
        if (receiving || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        receiving = true
        folderId = host.currentFolderId()
        recorder = TapSessionRecorder.start(activity, "receive")?.also { r ->
            val at = IntArray(2).also { host.container.getLocationInWindow(it) }
            val decorAt = IntArray(2).also { activity.window.decorView.getLocationInWindow(it) }
            r.event("enter") {
                put("container", JSONArray().put(at[0] - decorAt[0]).put(at[1] - decorAt[1])
                    .put(host.container.width).put(host.container.height))
                put("actionbar_bottom", host.actionbar.bottom)
                put("offset", pull.currentOffset.toDouble())
                put("antenna", JSONArray().also { a -> antennaInDecor().let { a.put(it.x.toDouble()).put(it.y.toDouble()) } })
            }
            r.captureView("page-enter", host.container, listOf(pull.button))
        }
        recordedBeforeInsert = false
        backCallback.isEnabled = true
        host.onReceivingChanged(true)
        pull.setReceiving(true)

        val s = TapReceiveStageView(activity, this)
        // 等待被碰期间屏幕不能熄：熄屏读取模式就停了。舞台移除时自动撤销，不影响别处设的窗口标志
        s.keepScreenOn = true
        val dark = AppearanceUtil.isDarkMode(activity)
        s.dimColor = ContextCompat.getColor(activity, R.color.app_chrome_surface)
        val dims = params.receiveDimAlpha
        s.dimMaxAlpha = if (dark) dims[1] else dims[0]
        // 叠在列表与顶栏之上、按钮之下：按钮始终可以点
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        host.container.addView(s, host.container.indexOfChild(pull.button), FrameLayout.LayoutParams(match, match))
        stage = s
        s.post {
            if (stage !== s) {
                return@post
            }
            s.setScreenCornerRadius(screenCornerRadius(s))
            // 舞台铺满容器：舞台坐标 = 容器坐标
            val offset = containerInDecor()
            val antenna = antennaInDecor()
            s.setAntenna(antenna.x - offset.x, antenna.y - offset.y)
            s.enter()
        }

        val t = TapTransfer.createReceiverTransport(activity)
        transport = t
        t.start(TapTransfer.deviceName(activity), this)
    }

    private fun screenCornerRadius(view: View): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val corner = view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
            if (corner != null && corner.radius > 0) {
                return corner.radius.toFloat()
            }
        }
        return DEFAULT_CORNER_DP * density
    }

    // ---------------------------------------------------------------- 退出

    /**
     * 退出接收状态。[immediate] 为 true 时不播动画（离开首页、锁屏：画面已经看不见了，
     * 动画也不会再推进）。
     */
    fun exit(immediate: Boolean) {
        if (!receiving) {
            return
        }
        receiving = false
        backCallback.isEnabled = false
        recorder?.event("exit")
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        main.removeCallbacksAndMessages(null)
        transport?.stop()
        transport = null
        stopGuiding()
        cancelLanding()
        // 导入可能还在后台进行：写进去的记事不能一直藏着
        revealHidden()
        resetReceiveState()
        // 按钮立刻换回「接收记事」，光尘与罩层随后淡出。舞台立刻与这次接收脱钩：
        // 淡出期间用户可能再点一次按钮，新的一次用新的舞台
        pull.setReceiving(false)
        val s = stage
        stage = null
        if (s == null || immediate) {
            s?.let { removeStage(it) }
            host.onReceivingChanged(false)
            recorder?.finish()
            recorder = null
        } else {
            s.exit()
        }
    }

    override fun onExitFinished(stage: TapReceiveStageView) {
        removeStage(stage)
        // 退场淡出播完才收尾：按钮收回的那段位移也要记下来
        recorder?.let { r -> host.recyclerView.postDelayed({ r.finish() }, 600L) }
        recorder = null
        // 淡出期间又进入了接收：首页保持接收状态的样子
        if (!receiving) {
            host.onReceivingChanged(false)
        }
    }

    private fun removeStage(s: TapReceiveStageView) {
        (s.parent as? FrameLayout)?.removeView(s)
    }

    private fun resetReceiveState() {
        busy = false
        receivedFile = null
        importedIds = null
        landingStarted = false
        lastFrameNanos = 0L
        shownPercent = -1
        colorsById.clear()
        assembling = emptyList()
        assembledCount = 0
        early = null
    }

    /** 撤掉还在播的两层粒子，作废还在等的列表、滚动与图片。 */
    private fun cancelLanding() {
        landingGeneration++
        proxy?.release(null)
        proxy = null
        real?.release(null)
        real = null
        handoff = null
        early = null
    }

    // ---------------------------------------------------------------- 传输回调

    override fun onTouched(offer: TapIncomingOffer) {
        val s = stage
        if (s == null) {
            // 不该发生（接收状态下总有舞台）；万一发生，别让传输一直停在「忙」
            transport?.resume()
            return
        }
        cancelLanding()
        resetReceiveState()
        busy = true
        val nowMs = SystemClock.uptimeMillis()
        model.touched(nowMs)
        smoother.reset()
        timeline.touched(nowMs / 1000.0)
        particleSeed = ParticleCardSurfaceHandoff.seed()
        senderName = offer.senderName
        TapTransfer.performTouchedFeedback(s)
        pull.setHint(activity.getString(R.string.tap_receive_from, offer.senderName))
        s.touched(offer.colors, offer.aspects, offer.fullSpan)
        startProxy(offer)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onConnected(totalBytes: Long) {
        model.connected(SystemClock.uptimeMillis())
    }

    override fun onProgress(done: Long, total: Long) {
        model.progress(done, total)
    }

    override fun onReceived(file: File) {
        if (!receiving) {
            // 确认字节发出之后才退出的接收：发送方已经显示已发送，文件照样导入，只是不播动画
            importQuietly(file)
            return
        }
        // 数据到齐：倒放时间轴改成指数逼近上限，等列表腾出位置
        timeline.complete(SystemClock.uptimeMillis() / 1000.0)
        receivedFile = file
        importInBackground(file)
    }

    override fun onFailed(reason: TapFailure) {
        Log.w(TAG, "接收失败：$reason")
        failCurrent(null)
    }

    // ---------------------------------------------------------------- 进度期间那一层

    /**
     * 碰上时按预计落点建进度期间那一层：代表色的圆角矩形（只截屏幕上看得见的那一部分），颗粒从天线处
     * 浮现、从四周的光尘带流进来，朝落点飘。
     */
    private fun startProxy(offer: TapIncomingOffer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val count = min(offer.thingCount, offer.colors.size)
        if (count <= 0) return
        val slots = predictSlots(offer, count)
        if (slots.isEmpty()) return
        val colors = IntArray(count) { k -> offer.colors[k].let { if (it == 0) FALLBACK_COLOR else it or OPAQUE } }
        val area = visibleArea()
        val offset = containerInDecor()
        val drawn = rectsBitmap(slots, colors, area) ?: return
        val (bitmap, local) = drawn
        val bounds = RectF(local).apply { offset(offset.x, offset.y) }
        val cards = slots.map { RectF(it).apply { offset(offset.x, offset.y) } }
        recorder?.event("proxy") {
            put("slots", JSONArray().also { list -> slots.forEach { r ->
                list.put(JSONArray().put(r.left.toDouble()).put(r.top.toDouble()).put(r.right.toDouble())
                    .put(r.bottom.toDouble()))
            } })
        }
        proxyCreatedS = SystemClock.uptimeMillis() / 1000.0
        val generation = landingGeneration
        proxy = TapParticleFlight.ReceiveLayer(activity, bitmap, bounds, antennaInDecor(), ringFrameInDecor(), cards,
            position = 1f - timeline.tauAt(proxyCreatedS), alpha = 1f, drawSurface = false,
            listener = object : TapParticleFlight.ReceiveLayer.Listener {
                override fun onStarted() {
                    if (generation != landingGeneration) return
                    // 下一层（拼卡那一层）片数与它相近，准备时长按它估计
                    val measured = SystemClock.uptimeMillis() / 1000.0 - proxyCreatedS
                    prepareEstimateS = measured.coerceIn(MIN_PREPARE_S, MAX_PREPARE_S)
                    recorder?.event("proxy-start") { put("prepare", measured) }
                    if (BuildConfig.DEBUG) Log.i(TAG, "进度期间那一层首帧上屏：准备 %.3f 秒".format(measured))
                }

                override fun onPresented(position: Float) {}

                override fun onLost() {
                    if (generation == landingGeneration) proxy = null
                }
            }, seed = particleSeed)
    }

    /**
     * 预计来件的落点（容器坐标，收到的先后）。列表在接收状态下停在顶部（只有滑到顶才拉得出按钮）、整体
     * 下移了按钮让出的那一截：头部占位项的底边就是第一张卡片的起点，置顶的几项都在屏幕上时逐项量出高度。
     */
    private fun predictSlots(offer: TapIncomingOffer, count: Int): List<RectF> {
        val rv = host.recyclerView
        val layoutManager = rv.layoutManager as? StaggeredGridLayoutManager ?: return emptyList()
        val contentWidth = rv.width - rv.paddingLeft - rv.paddingRight
        if (contentWidth <= 0) return emptyList()
        val margin = activity.resources.getDimensionPixelSize(R.dimen.thing_card_outer_spacing).toFloat()
        val list = inContainer(rv)
        val area = visibleArea()
        val scrollTop = area.top + CARD_GAP_DP * density + margin
        val header = rv.findViewHolderForAdapterPosition(0)?.itemView?.takeIf { it.isLaidOut }
        var pinned: MutableList<TapListPrediction.Entry>? = if (header == null) null else ArrayList()
        for (position in 1 until host.receivedInsertPosition()) {
            val view = rv.findViewHolderForAdapterPosition(position)?.itemView
            if (view == null || !view.isLaidOut || pinned == null) {
                pinned = null
                break
            }
            val full = (view.layoutParams as? StaggeredGridLayoutManager.LayoutParams)?.isFullSpan == true
            pinned.add(TapListPrediction.Entry(view.height.toFloat(), full))
        }
        val top = header?.let { inContainer(it).bottom } ?: (scrollTop - margin)
        val grid = TapListPrediction.Grid(list.left + rv.paddingLeft, contentWidth, layoutManager.spanCount, margin, top)
        return TapListPrediction.predict(grid, pinned, offer.aspects, offer.fullSpan, count, area.top, area.bottom,
            scrollTop).map { RectF(it[0], it[1], it[2], it[3]) }
    }

    /**
     * 代表色的圆角矩形画成一张透明底的位图，只画 [clip] 以内（屏幕上看得见的部分）。返回位图与它的范围
     * （与 [rects] 同一坐标系）；一张也不在 [clip] 里时返回 null。
     */
    private fun rectsBitmap(rects: List<RectF>, colors: IntArray, clip: RectF): Pair<Bitmap, RectF>? {
        val union = RectF()
        for (r in rects) {
            val shown = RectF(r)
            if (shown.intersect(clip)) union.union(shown)
        }
        if (union.isEmpty) return null
        union.set(floor(union.left), floor(union.top), ceil(union.right), ceil(union.bottom))
        return drawRects(union.width().toInt(), union.height().toInt(), union.left, union.top, rects, colors) to union
    }

    /** 宽 [width]、高 [height]、左上角在 ([left], [top]) 的透明位图上画代表色的圆角矩形。 */
    private fun drawRects(width: Int, height: Int, left: Float, top: Float, rects: List<RectF>,
                          colors: IntArray): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = activity.resources.getDimension(R.dimen.thing_card_corner_radius)
        rects.forEachIndexed { k, r ->
            paint.color = colors[k % colors.size]
            canvas.drawRoundRect(r.left - left, r.top - top, r.right - left, r.bottom - top, radius, radius, paint)
        }
        return bitmap
    }

    /**
     * 每帧：两层按倒放时间轴取帧（正向位置 = 1 − tau）、交叉淡化，光尘按传输进度扩散；进度期间那一层淡完即撤。
     * 拼卡那一层走到头由它的呈现回调判定（[onRealPresented]）。
     */
    private fun advanceParticles(nowS: Double, shown: Float) {
        timeline.advance(nowS, shown)
        val tau = timeline.tauAt(nowS)
        val inflow = timeline.inflow
        val fade = params.crossfade
        proxy?.let { layer ->
            if (inflow != null && nowS > inflow.startS + fade[2] + PROXY_TAIL_S) {
                layer.release(null)
                proxy = null
            } else {
                layer.seek(1f - tau)
                layer.alpha = if (inflow == null) 1f else
                    1f - smooth(inflow.startS + fade[1], inflow.startS + fade[2], nowS)
            }
        }
        real?.let { layer ->
            layer.seek(1f - tau)
            layer.alpha = if (inflow == null) 0f else smooth(inflow.startS, inflow.startS + fade[0], nowS)
        }
        stage?.update(timeline.spread(nowS, shown), tau)
    }

    // ---------------------------------------------------------------- 导入

    private fun importInBackground(file: File) {
        val context = activity.applicationContext
        val target = folderId
        val ids = ArrayList<Long>()
        importedIds = ids
        IMPORTER.execute {
            val result = try {
                FileInputStream(file).use { ThingFileReader.read(context, it) }
            } catch (e: Exception) {
                Log.e(TAG, "读收到的记事文件失败", e)
                null
            }
            if (result !is ThingFileReader.Result.Ok) {
                file.delete()
                val message = (result as? ThingFileReader.Result.Broken)?.message
                main.post { failCurrent(message) }
                return@execute
            }
            val summary = try {
                ThingFileImporter.import(context, result.things, target) { thing ->
                    // 主线程，与写进数据库在同一条消息里
                    val hide = receiving
                    recorder?.let { r ->
                        if (hide && !recordedBeforeInsert) {
                            recordedBeforeInsert = true
                            r.captureView("page-before", host.container, listOfNotNull(stage, pull.button))
                            r.trackItems(host.recyclerView, host.receivedInsertPosition())
                        }
                        r.event("insert")
                    }
                    colorsById[thing.id] = thing.getBackground()?.representativeColor() ?: thing.getColor()
                    if (host.insertReceivedThing(thing, hide) >= 0 && hide) {
                        hiddenIds.add(thing.id)
                    }
                    ids.add(thing.id)
                }
            } finally {
                ThingFileReader.cleanUp(result.tempDir)
                file.delete()
            }
            main.post { onImported(summary) }
        }
    }

    /** 不参与这次接收的状态与动画，只把记事写进去、显示在列表里。 */
    private fun importQuietly(file: File) {
        val context = activity.applicationContext
        val target = folderId
        IMPORTER.execute {
            val result = try {
                FileInputStream(file).use { ThingFileReader.read(context, it) }
            } catch (e: Exception) {
                Log.e(TAG, "读收到的记事文件失败", e)
                null
            }
            if (result !is ThingFileReader.Result.Ok) {
                file.delete()
                return@execute
            }
            try {
                ThingFileImporter.import(context, result.things, target) { thing ->
                    host.insertReceivedThing(thing, false)
                }
            } finally {
                ThingFileReader.cleanUp(result.tempDir)
                file.delete()
            }
            main.post { host.onReceivedThingsCommitted() }
        }
    }

    private fun onImported(summary: ThingFileImporter.Result) {
        host.onReceivedThingsCommitted()
        if (!receiving) {
            return
        }
        if (summary.imported == 0) {
            failCurrent(null)
            return
        }
        model.finished()
        land(importedIds.orEmpty().toList())
    }

    // ---------------------------------------------------------------- 在空位里拼出

    /**
     * 列表已经为收到的记事插好了（藏着的）卡片：等列表的动画走完，空位不在屏幕上就先滚过去，
     * 等卡片里的图片加载好，截出真实快照交给拼卡那一层。其间进度期间那一层一直在播（时间轴越来越慢但不停）。
     *
     * 拼卡那一层不等这些：插入之后第一次布局一完成就按新卡片的位置提前建（[startRealEarly]）。
     *
     * 必须等列表的动画走完：几条记事是一条一条插进来的，后插的会把先插的往下推，先插的那几张
     * 这时还在让位的途中，位置是过渡中的；插入的淡入动画也还在改它们的透明度（2026-09-24 魅族上
     * 三张卡片截在同一列、上下重叠，其中一张提前露了出来）。
     */
    private fun land(ids: List<Long>) {
        landingStarted = true
        recorder?.event("land")
        pull.setHint(activity.getString(R.string.tap_receive_progress, senderName, 100))
        val generation = ++landingGeneration
        TapParticleFlight.afterFrame(host.recyclerView) {
            if (generation == landingGeneration && receiving) startRealEarly(generation, ids)
        }
        afterItemAnimations(generation) {
            scrollIntoView(generation, ids) {
                afterItemAnimations(generation) {
                    awaitCards(generation, ids, SystemClock.uptimeMillis()) { assemble(generation, ids) }
                }
            }
        }
    }

    /**
     * 列表按最新的数据布局、所有插入与让位动画都走完之后执行。插入之后要先等一帧布局：
     * 布局之前问动画是否在跑，得到的是「没有」。
     */
    private fun afterItemAnimations(generation: Int, next: () -> Unit) {
        val rv = host.recyclerView
        TapParticleFlight.afterFrame(rv) {
            if (generation != landingGeneration) return@afterFrame
            val animator = rv.itemAnimator
            if (animator == null) {
                next()
            } else {
                animator.isRunning { rv.post { if (generation == landingGeneration) next() } }
            }
        }
    }

    /**
     * 第一张新卡片没有完整地露在按钮下方（上面有置顶记事、或者列表不在顶部）时，把列表滚到
     * 它的上沿紧贴按钮下方，停稳再继续。
     */
    private fun scrollIntoView(generation: Int, ids: List<Long>, next: () -> Unit) {
        val rv = host.recyclerView
        val first = ids.map { host.listPositionOf(it) }.filter { it >= 0 }.minOrNull()
        if (first == null) {
            next()
            return
        }
        val area = visibleArea()
        if (!needsScroll(first, area)) {
            next()
            return
        }
        val layoutManager = rv.layoutManager ?: run { next(); return }
        // 目标：卡片上沿落在按钮下方 CARD_GAP_DP 处（列表自己的坐标，列表整体下移了空位的高度）
        val desiredTop = (area.top + CARD_GAP_DP * density - rv.top - rv.translationY).toInt()
        val scroller = object : LinearSmoothScroller(activity) {
            override fun getVerticalSnapPreference(): Int = SNAP_TO_START

            override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int =
                desiredTop - viewStart
        }
        scroller.targetPosition = first
        var finished = false
        val done = Runnable {
            if (finished) return@Runnable
            finished = true
            if (generation == landingGeneration) next()
        }
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    recyclerView.removeOnScrollListener(this)
                    recyclerView.post(done)
                }
            }
        })
        layoutManager.startSmoothScroll(scroller)
        // 兜底：置顶块很高时滚动本身就要一两秒；超过上限不再等，停下来直接拼
        main.postDelayed({
            if (!finished) {
                rv.stopScroll()
                done.run()
            }
        }, SCROLL_LIMIT_MS)
    }

    /**
     * 列表里第 [first] 项（第一张新卡片）没有完整地露在 [area] 里、列表还能往下滚：要先滚过去。按布局位置判断
     * （让位动画走完之后的位置）。
     */
    private fun needsScroll(first: Int, area: RectF): Boolean {
        val rv = host.recyclerView
        val card = rv.findViewHolderForAdapterPosition(first)?.itemView ?: return true
        if (!card.isLaidOut) return true
        val top = settledInContainer(card).top
        val bottom = top + card.height
        return !(top >= area.top && (bottom <= area.bottom || !rv.canScrollVertically(1)))
    }

    /** 屏幕上（[area] 以内）看得见的新卡片，按布局位置判断。 */
    private fun visibleCards(ids: List<Long>, area: RectF): List<Pair<Long, View>> =
        ids.mapNotNull { id -> host.findReceivedCard(id)?.let { id to it } }.filter { (_, card) ->
            val rect = settledInContainer(card)
            card.isLaidOut && rect.bottom > area.top && rect.top < area.bottom
        }

    /**
     * 插入之后第一次布局一完成就建拼卡那一层：运动只取决于新卡片的位置与大小，占位快照是同样大小的代表色卡片，
     * 真实快照等让位、图片加载、截图之后再交进来（[captureAndFly]）。第一张新卡片要先滚过去时位置还会变，
     * 照旧等截图之后再建。
     */
    private fun startRealEarly(generation: Int, ids: List<Long>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || real != null) return
        val area = visibleArea()
        val shown = visibleCards(ids, area)
        if (shown.isEmpty()) return
        val first = ids.map { host.listPositionOf(it) }.filter { it >= 0 }.minOrNull() ?: return
        if (needsScroll(first, area)) return
        val offset = containerInDecor()
        val visibleInDecor = RectF(area).apply { offset(offset.x, offset.y) }
        val bounds = TapParticleFlight.compositeBounds(activity, shown.map { it.second }, visibleInDecor) ?: return
        // 让位动画还在播：按布局位置（动画走完之后的位置）
        val cards = shown.map { (_, card) -> settledInContainer(card).apply { offset(offset.x, offset.y) } }
        val placeholder = drawRects(bounds.width().toInt(), bounds.height().toInt(), bounds.left, bounds.top, cards,
            colorsOf(shown))
        early = EarlyReal(shown.map { it.first }, cards, RectF(bounds))
        recorder?.event("real-early")
        if (BuildConfig.DEBUG) Log.i(TAG, "拼卡那一层提前建：${shown.size} 张卡片，快照 ${bounds.toShortString()}")
        startReal(generation, ids, shown, placeholder, bounds, deferred = true, cards = cards)
    }

    /** 截出的真实快照与提前建的那一层对得上：同样几张卡片、位置大小都没变、快照范围相同。 */
    private fun matchesEarly(pending: EarlyReal, shown: List<Pair<Long, View>>, bounds: RectF, snapshot: Bitmap): Boolean {
        if (pending.ids != shown.map { it.first } || pending.bounds != bounds) return false
        if (snapshot.width != bounds.width().toInt() || snapshot.height != bounds.height().toInt()) return false
        val offset = containerInDecor()
        return shown.indices.all { k ->
            val now = inContainer(shown[k].second).apply { offset(offset.x, offset.y) }
            val then = pending.cards[k]
            abs(now.left - then.left) <= 1f && abs(now.top - then.top) <= 1f &&
                abs(now.right - then.right) <= 1f && abs(now.bottom - then.bottom) <= 1f
        }
    }

    private fun colorsOf(shown: List<Pair<Long, View>>): IntArray = IntArray(shown.size) { k ->
        (colorsById[shown[k].first] ?: 0).let { if (it == 0) FALLBACK_COLOR else it or OPAQUE }
    }

    /**
     * 等屏幕上的新卡片布局好、图片加载完（截进快照的就是最终的样子），最多等 [IMAGE_WAIT_MS]。
     * 图片卡片加载时显示进度圈、作为背景的图片先是空的，这两处都到位才算好了。
     */
    private fun awaitCards(generation: Int, ids: List<Long>, since: Long, next: () -> Unit) {
        if (generation != landingGeneration) return
        val area = visibleArea()
        val pending = ids.mapNotNull { host.findReceivedCard(it) }.any { card ->
            val rect = inContainer(card)
            rect.bottom > area.top && rect.top < area.bottom && !isCardReady(card)
        }
        if (!pending || SystemClock.uptimeMillis() - since > IMAGE_WAIT_MS) {
            next()
            return
        }
        host.recyclerView.postOnAnimation { awaitCards(generation, ids, since, next) }
    }

    private fun isCardReady(card: View): Boolean {
        if (!card.isLaidOut || card.width == 0) {
            return false
        }
        // 卡片本身藏着（INVISIBLE），不能用 isShown：只看从控件到卡片这一段是不是都可见
        if (visibleWithin(card.findViewById(R.id.pb_thing_image_attachment), card)) {
            return false
        }
        val background = card.findViewById<ImageView>(R.id.iv_thing_media_background)
        return !visibleWithin(background, card) || background.drawable != null
    }

    private fun visibleWithin(view: View?, root: View): Boolean {
        var v: View = view ?: return false
        while (v !== root) {
            if (v.visibility != View.VISIBLE) {
                return false
            }
            v = v.parent as? View ?: return false
        }
        return true
    }

    /** 把屏幕上的新卡片合成一张快照，建拼卡那一层。 */
    private fun assemble(generation: Int, ids: List<Long>) {
        if (generation != landingGeneration || !receiving || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        val area = visibleArea()
        val shown = visibleCards(ids, area)
        // 不在屏幕上的直接显示：滚到它们时就是普通的卡片
        revealOnly(ids.filter { id -> shown.none { it.first == id } })
        if (shown.isEmpty()) {
            dropEarly()
            dropProxy()
            finishLanding(ids.size)
            return
        }
        // 这几张卡片从插进列表起一直是 INVISIBLE，没有画过：直接截会是空的。先换成 alpha 0 的
        // 可见（看上去一样），让它们画过一帧再截（与首页新建记事的粒子出现同一个做法）。
        // 插入的淡入动画可能还在跑，先结束它，否则它会把透明度拉回 1，卡片提前露出来
        val rv = host.recyclerView
        shown.forEach { (_, card) ->
            rv.findContainingViewHolder(card)?.let { rv.itemAnimator?.endAnimation(it) }
            card.animate().cancel()
            card.alpha = 0f
            card.visibility = View.VISIBLE
        }
        TapParticleFlight.afterFrame(host.recyclerView) {
            if (generation == landingGeneration && receiving) {
                captureAndFly(generation, ids, shown, area)
            }
        }
    }

    private fun captureAndFly(generation: Int, ids: List<Long>, shown: List<Pair<Long, View>>, area: RectF) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        val offset = containerInDecor()
        val visibleInDecor = RectF(area).apply { offset(offset.x, offset.y) }
        recorder?.captureView("page-slots", host.container, listOfNotNull(stage, pull.button))
        TapParticleFlight.captureComposite(activity, shown.map { it.second }, visibleInDecor) { bitmap, bounds ->
            if (generation != landingGeneration || !receiving) {
                bitmap?.recycle()
                return@captureComposite
            }
            if (bitmap == null) {
                dropEarly()
                dropProxy()
                showCards(shown)
                finishLanding(ids.size)
                return@captureComposite
            }
            val pending = early
            val layer = real
            if (pending != null && layer != null && matchesEarly(pending, shown, bounds, bitmap)) {
                // 提前建的那一层对得上：交进真实快照，它换上颜色就出第一帧（流入开始）
                assembling = shown
                // 交出去之后这一层就是正式的了：再出问题按拼卡失败处理（显示卡片、收尾），不再重建
                early = null
                recorder?.event("real-supplied")
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "交进真实快照：拼卡那一层提前建之后 %.3f 秒".format(
                        SystemClock.uptimeMillis() / 1000.0 - realCreatedS))
                }
                layer.supplyForeground(bitmap)
                return@captureComposite
            }
            // 没有提前建，或者让位之后位置变了：按截出来的快照重建
            if (pending != null && BuildConfig.DEBUG) {
                val offset = containerInDecor()
                val now = shown.map { (id, card) -> id to inContainer(card).apply { offset(offset.x, offset.y) } }
                Log.i(TAG, "提前建的拼卡那一层与截图对不上，重建：提前 ids=${pending.ids} " +
                    "cards=${pending.cards.map { it.toShortString() }} bounds=${pending.bounds.toShortString()}；" +
                    "截图 ids=${now.map { it.first }} cards=${now.map { it.second.toShortString() }} " +
                    "bounds=${bounds.toShortString()} snapshot=${bitmap.width}x${bitmap.height}")
            }
            dropEarly()
            startReal(generation, ids, shown, bitmap, bounds, deferred = false)
        }
    }

    /** 撤掉提前建的拼卡那一层（还没交进真实快照的那种）。 */
    private fun dropEarly() {
        if (early == null) return
        real?.release(null)
        real = null
        handoff = null
        early = null
    }

    /**
     * 拼卡那一层：按真实位置建，运动按同样大小的代表色卡片算（与进度期间那一层逐片重合）、颜色取真实快照；
     * 四周的光尘就近接手一部分流向四周的颗粒。第一帧上屏即流入开始。
     *
     * [deferred] 时 [snapshot] 就是占位（代表色卡片，材料按它建），真实快照之后由 [captureAndFly] 交进来；
     * 否则 [snapshot] 是真实快照，另画一张同尺寸的代表色卡片算运动。
     */
    private fun startReal(generation: Int, ids: List<Long>, shown: List<Pair<Long, View>>, snapshot: Bitmap,
                          bounds: RectF, deferred: Boolean, cards: List<RectF> = currentCards(shown)) {
        val offset = containerInDecor()
        // 与快照同尺寸、同原点：材料按它建，颜色仍取快照
        val dynamics = if (deferred) null else
            drawRects(snapshot.width, snapshot.height, bounds.left, bounds.top, cards, colorsOf(shown))
        val nowS = SystemClock.uptimeMillis() / 1000.0
        realCreatedS = nowS
        val s = stage
        val band = s?.dustBand
        // 流入开始 = 算完历史且真实快照已经换上；提前建时还要等让位、图片与截图
        val lead = if (deferred) max(prepareEstimateS, SNAPSHOT_ESTIMATE_S) else prepareEstimateS
        handoff = if (s != null && band != null && timeline.isTouched) {
            TapDustHandoff(band, timeline.inflowAt(nowS + lead), offset.x, offset.y, s.clockOriginMs,
                timeline.touchedS, timeline.completeS, s.targetPalette, params.borderPaletteCycleS, params.dustJoin,
                density)
        } else null
        assembling = shown
        assembledCount = ids.size
        real = TapParticleFlight.ReceiveLayer(activity, snapshot, bounds, antennaInDecor(), ringFrameInDecor(), cards,
            position = 1f - timeline.tauAt(nowS), alpha = 0f, drawSurface = true,
            listener = object : TapParticleFlight.ReceiveLayer.Listener {
                override fun onStarted() {
                    if (generation != landingGeneration) return
                    onInflowStarted()
                }

                override fun onPresented(position: Float) {
                    if (generation != landingGeneration) return
                    onRealPresented(position)
                }

                override fun onLost() {
                    if (generation != landingGeneration) return
                    real = null
                    handoff = null
                    if (early != null) {
                        // 提前建的那一层没建起来：截图出来之后照原路重建
                        early = null
                        return
                    }
                    dropProxy()
                    showCards(assembling.ifEmpty { shown })
                    finishLanding(ids.size)
                }
            },
            dynamics = dynamics, handoff = handoff, dumpKind = "receive", deferForeground = deferred,
            seed = particleSeed)
    }

    /** 拼卡那一层整段算完、第一帧上屏：流入开始，时间轴平滑加速到拼好，两层交叉淡化。 */
    private fun onInflowStarted() {
        val nowS = SystemClock.uptimeMillis() / 1000.0
        early = null
        val inflow = timeline.startInflow(nowS)
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "拼卡那一层首帧上屏：准备 %.3f 秒（预计 %.3f），流入从 tau %.3f 开始".format(
                nowS - realCreatedS, prepareEstimateS, inflow.tauH))
        }
        recorder?.event("inflow-start")
        recorder?.stopTracking()
        stage?.let { s ->
            handoff?.let { s.setHandoff(it.hideTau) }
            s.inflow()
        }
    }

    /** 拼卡那一层呈现的位置：时间轴到头、末帧（完整的卡片）上屏即拼好。 */
    private fun onRealPresented(position: Float) {
        val inflow = timeline.inflow ?: return
        if (real == null || position > ASSEMBLED_EPSILON ||
            SystemClock.uptimeMillis() / 1000.0 < inflow.endS) return
        recorder?.event("assembled")
        val shown = assembling
        showCards(shown)
        real?.release(host.recyclerView)
        real = null
        dropProxy()
        finishLanding(assembledCount)
    }

    private fun dropProxy() {
        proxy?.release(null)
        proxy = null
    }

    /**
     * 拼好了：列表里的卡片显出来，罩层在它们的位置留出空洞——粒子层的末帧是没有罩层的卡片，
     * 这一帧前后一样亮。两件事在同一条消息里做完。
     */
    private fun showCards(shown: List<Pair<Long, View>>) {
        val radius = activity.resources.getDimension(R.dimen.thing_card_corner_radius)
        stage?.highlight(shown.map { inContainer(it.second) }, radius)
        revealOnly(shown.map { it.first })
        recorder?.let { r ->
            TapParticleFlight.afterFrame(host.recyclerView) {
                r.captureView("page-done", host.container, listOfNotNull(stage, pull.button))
            }
        }
    }

    private fun revealOnly(ids: List<Long>) {
        val hidden = ids.filter { hiddenIds.remove(it) }
        if (hidden.isNotEmpty()) {
            host.revealReceivedThings(hidden)
        }
    }

    /** 这一批收完：提示收到几条，光尘回到等待的样子，等下一台。 */
    private fun finishLanding(count: Int) {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        revealHidden()
        pull.setHint(activity.resources.getQuantityString(R.plurals.tap_receive_done, count, count))
        // 传输马上恢复，这两秒里可能已经碰上了下一台：那时提示归它管
        main.postDelayed({
            if (receiving && !busy) {
                pull.setHint(activity.getString(R.string.tap_receive_hint))
            }
        }, DONE_HINT_MS)
        resetReceiveState()
        stage?.resumeWaiting()
        transport?.resume()
    }

    /**
     * 这一台没收成（数据到齐之前就断了，或者文件读不出来）：光尘换回原色，提示「接收失败，
     * 请再碰一次」，随后接着等。
     */
    private fun failCurrent(message: String?) {
        recorder?.event("failed")
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        receivedFile?.delete()
        cancelLanding()
        revealHidden()
        resetReceiveState()
        if (!receiving) {
            return
        }
        stage?.resumeWaiting()
        pull.setHint(activity.getString(R.string.tap_receive_failed))
        if (!message.isNullOrEmpty()) {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
        main.postDelayed({
            if (receiving) {
                pull.setHint(activity.getString(R.string.tap_receive_hint))
                transport?.resume()
            }
        }, FAILED_HINT_MS)
    }

    private fun revealHidden() {
        if (hiddenIds.isEmpty()) {
            return
        }
        host.revealReceivedThings(ArrayList(hiddenIds))
        hiddenIds.clear()
    }

    // ---------------------------------------------------------------- 坐标

    /** 新卡片可以露出来的范围（容器坐标）：按钮（含提示）之下、导航栏之上。 */
    private fun visibleArea(): RectF {
        val c = host.container
        val bottomInset = ViewCompat.getRootWindowInsets(c)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        return RectF(0f, pull.openBottom(), c.width.toFloat(), (c.height - bottomInset).toFloat())
    }

    /** 新卡片此刻在 DecorView 里的范围（含平移）。 */
    private fun currentCards(shown: List<Pair<Long, View>>): List<RectF> {
        val offset = containerInDecor()
        return shown.map { (_, card) -> inContainer(card).apply { offset(offset.x, offset.y) } }
    }

    /**
     * 视图在容器里的布局位置：当前位置去掉它自己的平移。列表让位动画用平移把卡片从旧位置挪到新位置，
     * 动画还在播时这就是走完之后的位置（祖先的平移照算：列表整体下移了按钮让出的那一截）。
     */
    private fun settledInContainer(view: View): RectF =
        inContainer(view).apply { offset(-view.translationX, -view.translationY) }

    /** 视图在容器里的范围（含平移），与舞台的坐标一致。 */
    private fun inContainer(view: View): RectF {
        val at = IntArray(2).also { view.getLocationInWindow(it) }
        val containerAt = IntArray(2).also { host.container.getLocationInWindow(it) }
        val left = (at[0] - containerAt[0]).toFloat()
        val top = (at[1] - containerAt[1]).toFloat()
        return RectF(left, top, left + view.width, top + view.height)
    }

    private fun containerInDecor(): PointF {
        val at = IntArray(2).also { host.container.getLocationInWindow(it) }
        val decorAt = IntArray(2).also { activity.window.decorView.getLocationInWindow(it) }
        return PointF((at[0] - decorAt[0]).toFloat(), (at[1] - decorAt[1]).toFloat())
    }

    /** 流向四周的颗粒的终点所在的那条线（DecorView 坐标）。 */
    private fun ringFrameInDecor(): RectF {
        val offset = containerInDecor()
        val frame = RectF()
        val s = stage
        if (s != null) {
            s.ringFrame(frame)
        } else {
            frame.set(0f, 0f, host.container.width.toFloat(), host.container.height.toFloat())
        }
        frame.offset(offset.x, offset.y)
        return frame
    }

    /** 本机天线在 DecorView 里的位置；取不到时用屏幕顶部中间。 */
    private fun antennaInDecor(): PointF {
        val decor = activity.window.decorView
        val out = PointF()
        if (!TapTransfer.antennaPosition(activity, decor.width, decor.height, out)) {
            val top = ViewCompat.getRootWindowInsets(decor)
                ?.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
                ?.top ?: 0
            out.set(decor.width / 2f, top + ANTENNA_FALLBACK_DP * density)
        }
        return out
    }

    private companion object {
        const val TAG = "TapReceive"

        // 罩层的不透明度见 shared/tap-transfer/particles.properties（receive_dim_alpha）
        const val DEFAULT_CORNER_DP = 24f

        /** 需要滚动时，第一张新卡片的上沿离按钮（含提示）下沿多远。 */
        const val CARD_GAP_DP = 12f

        /** 等卡片里的图片加载的上限：超过就按当时的样子截。 */
        const val IMAGE_WAIT_MS = 1500L

        /** 滚到空位的上限。 */
        const val SCROLL_LIMIT_MS = 2500L

        /** 取不到天线位置时颗粒从屏幕顶部中间流出：状态栏下方这么远。 */
        const val ANTENNA_FALLBACK_DP = 28f
        const val DONE_HINT_MS = 2400L
        const val FAILED_HINT_MS = 2400L

        /** NFC、Wi-Fi 从关到开通常要一两秒。 */
        const val SWITCH_SETTLE_MS = 2500L

        /**
         * 拼卡那一层从建起到第一帧上屏的时长估计（秒）：光尘接手按「现在 + 它」预计流入开始的时刻。
         * 进度期间那一层建起来之后按它实测的时长改写，限制在下面两项之间。
         */
        const val DEFAULT_PREPARE_S = .35
        const val MIN_PREPARE_S = .12
        const val MAX_PREPARE_S = 1.2

        /** 提前建拼卡那一层时，从建起到截出真实快照的时长估计（秒）：让位动画、图片加载与一次截图。 */
        const val SNAPSHOT_ESTIMATE_S = .6

        /** 进度期间那一层淡完之后再留这么久才撤（最后一帧透明度为 0 的画面上屏）。 */
        const val PROXY_TAIL_S = .05

        /** 呈现的正向位置落到这以下就算拼好：整张卡片已经是完整的表面。 */
        const val ASSEMBLED_EPSILON = .002f

        /** 取不到背景色的来件，代表色卡片用这个颜色。 */
        const val FALLBACK_COLOR = 0xFF9E9E9E.toInt()
        const val OPAQUE = 0xFF000000.toInt()

        /** 读文件与挪附件都在这条线程上，一次接收一条，顺序执行。 */
        val IMPORTER = Executors.newSingleThreadExecutor()

        fun smooth(edge0: Double, edge1: Double, x: Double): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
            return (t * t * (3.0 - 2.0 * t)).toFloat()
        }
    }
}
