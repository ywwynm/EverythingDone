package com.ywwynm.everythingdone.taptransfer.ui.receive

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.taptransfer.TapPrerequisite
import com.ywwynm.everythingdone.taptransfer.TapTransfer
import com.ywwynm.everythingdone.taptransfer.diagnostics.TapDiagnostics
import com.ywwynm.everythingdone.taptransfer.transport.TapFailure
import com.ywwynm.everythingdone.taptransfer.transport.TapIncomingOffer
import com.ywwynm.everythingdone.taptransfer.transport.TapReceiverTransport
import com.ywwynm.everythingdone.taptransfer.transport.TapReceivedPayload
import com.ywwynm.everythingdone.taptransfer.protocol.TapFileTransfer
import com.ywwynm.everythingdone.taptransfer.ui.ProgressSmoother
import com.ywwynm.everythingdone.taptransfer.ui.TapPrerequisiteWatcher
import com.ywwynm.everythingdone.taptransfer.ui.TapProgressModel
import com.ywwynm.everythingdone.taptransfer.ui.TapSetupNavigator
import com.ywwynm.everythingdone.taptransfer.ui.flight.TapParticleFlight
import com.ywwynm.everythingdone.taptransfer.ui.flight.TapParticleParams
import com.ywwynm.everythingdone.thingfile.PendingThing
import com.ywwynm.everythingdone.thingfile.ThingFileReader
import com.ywwynm.everythingdone.thingfile.ThingFileUpdate
import com.ywwynm.everythingdone.views.particledismiss.ParticleCardSurfaceHandoff
import java.io.File
import java.util.concurrent.Executors

/** 详情页的一次接收更新。旧画面一直在底层，新内容与背景由粒子直接覆盖。 */
class TapDetailReceiveController(private val activity: FragmentActivity, private val host: Host) :
    TapReceiverTransport.Listener, TapReceiveStageView.Listener {
    interface Host {
        val container: FrameLayout
        val recyclerView: RecyclerView
        val actionbar: View
        val foregroundColor: Int
        val accentBackground: ThingBackground
        fun canReceive(): Boolean
        fun existingAttachments(): List<TapFileTransfer.LocalAttachment>
        fun prepareForReceive()
        fun needsSettingsChoice(pending: PendingThing): Boolean
        /** 可见附件与详情布局已就绪，快照不能只包含尚未加载的封面占位。 */
        fun isContentReadyForSnapshot(): Boolean
        /** 数据提交后立即标记附件已接管，之后的界面异常不能再按未写入处理。 */
        fun apply(prepared: ThingFileUpdate.Prepared, settings: ThingFileUpdate.Settings)
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val params = TapParticleParams.get(activity)
    private val timeline = TapReceiveTimeline(params.emergePace[0], params.emergePace[1],
        params.emergePace[2], params.emergePace[3], params.appearS)
    private val model = TapProgressModel()
    private val smoother = ProgressSmoother(minDurationS = 1.2f)
    private val density = activity.resources.displayMetrics.density
    private var stage: TapReceiveStageView? = null
    private val pull = TapReceivePull(host.container, host.recyclerView, null, host.actionbar,
        host::canReceive, allowPullGesture = false, onOffsetChanged = ::onReceiveOffsetChanged) { exit() }
    private var contentClipBeforeReceive: Rect? = null
    private var contentClipCaptured = false
    private var receiveSlotHeight = 1f
    private var frozen: ImageView? = null
    private var frozenBitmap: Bitmap? = null
    private var receiveClipToPadding: Boolean? = null
    private var transport: TapReceiverTransport? = null
    private var diagnostics = TapDiagnostics.NONE
    private var prepared: ThingFileUpdate.Prepared? = null
    private var proxy: TapParticleFlight.ReceiveLayer? = null
    private var real: TapParticleFlight.ReceiveLayer? = null
    private var busy = false
    private var generation = 0
    private var seed = 0
    private var color = 0
    private var senderName = ""
    private var lastFrame = 0L
    private var shownPercent = -1
    private var waitingForWifiConfirmation = false
    private var applying = false
    private var guiding: TapPrerequisite? = null
    private val settleCheck = Runnable { finishGuidance() }
    private val enterAfterKeyboard = Runnable { enterWhenKeyboardHidden() }
    private val navigator = TapSetupNavigator(activity) { granted ->
        if (guiding == TapPrerequisite.PERMISSION) {
            if (granted) guideOrEnter() else giveUp(TapPrerequisite.PERMISSION)
        }
    }
    private val watcher = TapPrerequisiteWatcher(activity) {
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && guiding != null &&
            !TapTransfer.missingPrerequisites(activity).contains(guiding)) guideOrEnter()
    }
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exit()
    }
    var isReceiving = false
        private set

    init {
        pull.attach()
        activity.onBackPressedDispatcher.addCallback(activity, back)
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    val step = guiding
                    if (step != null && (step != TapPrerequisite.PERMISSION || navigator.consumePermissionSettingsReturn())) {
                        main.removeCallbacks(settleCheck)
                        if (!TapTransfer.missingPrerequisites(activity).contains(step)) guideOrEnter()
                        else main.postDelayed(settleCheck, 700)
                    }
                }
                Lifecycle.Event.ON_STOP -> { main.removeCallbacks(enterAfterKeyboard); exit(immediate = true) }
                Lifecycle.Event.ON_DESTROY -> { exit(immediate = true); stopGuiding(); navigator.release(); worker.shutdown() }
                else -> Unit
            }
        })
        host.container.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (isReceiving && oldRight > oldLeft && oldBottom > oldTop &&
                (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) exit(immediate = true)
        }
    }

    fun start() {
        if (isReceiving || !host.canReceive() || !TapTransfer.isEntryAvailable(activity)) return
        diagnostics = TapDiagnostics.begin(activity, "更新当前记事")
        host.prepareForReceive()
        guideOrEnter()
    }

    private fun guideOrEnter() {
        main.removeCallbacks(settleCheck)
        val missing = TapTransfer.missingPrerequisites(activity)
        diagnostics.event("接收前提检查", "missing=$missing")
        if (missing.isEmpty()) { stopGuiding(); enterWhenKeyboardHidden(); return }
        guiding = missing.first()
        watcher.start()
        navigator.open(missing.first())
    }

    private fun finishGuidance() {
        val step = guiding ?: return
        if (TapTransfer.missingPrerequisites(activity).contains(step)) giveUp(step) else guideOrEnter()
    }

    private fun giveUp(step: TapPrerequisite) {
        stopGuiding()
        Toast.makeText(activity, activity.getString(R.string.tap_setup_still_missing,
            TapSetupNavigator.titleOf(activity, step)), Toast.LENGTH_SHORT).show()
    }

    private fun stopGuiding() { guiding = null; watcher.stop(); main.removeCallbacks(settleCheck) }

    private fun enterWhenKeyboardHidden() {
        main.removeCallbacks(enterAfterKeyboard)
        if (activity.isFinishing || activity.isDestroyed) return
        if (ViewCompat.getRootWindowInsets(host.container)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
            main.postDelayed(enterAfterKeyboard, 32)
        } else enter()
    }

    private fun enter() {
        if (isReceiving || !host.canReceive() || Build.VERSION.SDK_INT < 29 || activity.isFinishing) return
        isReceiving = true
        back.isEnabled = true
        val root = host.container
        if (receiveClipToPadding == null) receiveClipToPadding = root.clipToPadding
        root.clipToPadding = false
        if (!contentClipCaptured) {
            contentClipBeforeReceive = host.recyclerView.clipBounds?.let(::Rect)
            contentClipCaptured = true
        }
        pull.setHintTextColor(host.foregroundColor)
        pull.setReceiving(true)
        receiveSlotHeight = pull.slotHeight.coerceAtLeast(1f)
        val surface = TapReceiveStageView(activity, this).apply {
            keepScreenOn = true
            // 原背景直接留在底下，避免默认首页罩层改变旧记事的颜色。
            dimMaxAlpha = 0f
        }
        stage = surface
        // 舞台包含导航栏安全区，四周粒子贴着屏幕边缘，按钮仍可点击。
        root.addView(surface, root.indexOfChild(pull.button), FrameLayout.LayoutParams(root.width, root.height).apply {
            leftMargin = -root.paddingLeft
            topMargin = -root.paddingTop
        })
        surface.post {
            if (stage !== surface) return@post
            val bounds = bounds()
            val antenna = antenna()
            val corner = if (Build.VERSION.SDK_INT >= 31)
                surface.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius else null
            surface.setScreenCornerRadius(corner?.takeIf { it > 0 }?.toFloat() ?: 24f * density)
            surface.setAntenna(antenna.x - bounds.left, antenna.y - bounds.top)
            surface.enter()
        }
        val receiver = TapTransfer.createReceiverTransport(activity, diagnostics)
        transport = receiver
        receiver.setExistingAttachments(host.existingAttachments())
        receiver.start(TapTransfer.deviceName(activity), listenerFor(receiver))
    }

    /** 真实传输在 stop 后仍可能交付已收齐的文件；重进接收不能接管上一轮的回调。 */
    private fun listenerFor(receiver: TapReceiverTransport) = object : TapReceiverTransport.Listener {
        private fun isCurrent() = isReceiving && transport === receiver
        override fun onPreparingChanged(preparing: Boolean) {
            if (isCurrent()) this@TapDetailReceiveController.onPreparingChanged(preparing)
        }
        override fun onTouched(offer: TapIncomingOffer) {
            if (isCurrent()) this@TapDetailReceiveController.onTouched(offer)
        }
        override fun onConnected(totalBytes: Long) {
            if (isCurrent()) this@TapDetailReceiveController.onConnected(totalBytes)
        }
        override fun onWifiConfirmationChanged(waiting: Boolean) {
            if (isCurrent()) this@TapDetailReceiveController.onWifiConfirmationChanged(waiting)
        }
        override fun onProgress(done: Long, total: Long) {
            if (isCurrent()) this@TapDetailReceiveController.onProgress(done, total)
        }
        override fun onReceived(file: TapReceivedPayload) {
            if (isCurrent()) this@TapDetailReceiveController.onReceived(file) else file.discard()
        }
        override fun onFailed(reason: TapFailure) {
            if (isCurrent()) this@TapDetailReceiveController.onFailed(reason)
        }
    }

    override fun onPreparingChanged(preparing: Boolean) {
        if (isReceiving) pull.setPreparing(preparing)
    }

    override fun onTouched(offer: TapIncomingOffer) {
        if (!isReceiving || busy) return
        busy = true
        generation++
        senderName = offer.senderName
        shownPercent = -1
        waitingForWifiConfirmation = false
        pull.setHint(activity.getString(R.string.tap_receive_from, senderName))
        color = (offer.colors.firstOrNull() ?: 0xFF607D8B.toInt()) or 0xFF000000.toInt()
        seed = ParticleCardSurfaceHandoff.seed()
        model.touched(SystemClock.uptimeMillis())
        smoother.reset()
        timeline.touched(now())
        stage?.let {
            TapTransfer.performTouchedFeedback(it)
            it.touched(offer.colors, offer.aspects, offer.fullSpan)
        }
        startProxy()
        lastFrame = 0
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onWifiConfirmationChanged(waiting: Boolean) {
        waitingForWifiConfirmation = waiting
        pull.setHint(if (waiting) activity.getString(R.string.tap_receive_wifi_confirmation)
            else activity.getString(R.string.tap_receive_from, senderName))
    }
    override fun onConnected(totalBytes: Long) {
        waitingForWifiConfirmation = false
        pull.setHint(activity.getString(R.string.tap_receive_from, senderName))
        model.connected(SystemClock.uptimeMillis())
    }
    override fun onProgress(done: Long, total: Long) { model.progress(done, total) }

    override fun onReceived(file: TapReceivedPayload) {
        diagnostics.event("收到更新文件", "attachmentBytes=${file.transferredBytes}")
        // 更新模式退出意味着取消更新，不再把迟到的来件应用到已离开的目标记事。
        if (!isReceiving) { file.discard(); return }
        val token = generation
        model.finished()
        timeline.complete(now())
        worker.execute {
            var result: ThingFileReader.Result? = null
            var payload: ThingFileUpdate.Prepared? = null
            var error = R.string.tap_update_failed
            try {
                result = file.read(activity.applicationContext)
                val ok = result as? ThingFileReader.Result.Ok
                if (ok != null && ok.singleStandaloneThing) payload = ThingFileUpdate.prepare(ok.things.single())
                else if (ok != null) error = R.string.tap_update_single_only
            } catch (e: Exception) { Log.e(TAG, "准备更新文件失败", e) }
            finally {
                (result as? ThingFileReader.Result.Ok)?.let { ThingFileReader.cleanUp(it.tempDir) }
                file.discard()
            }
            val ready = payload
            main.post {
                if (!isReceiving || token != generation) { ready?.discard(); return@post }
                if (ready == null) { fail(error); return@post }
                prepared = ready
                if (host.needsSettingsChoice(ready.pending)) chooseSettings(token)
                else apply(ThingFileUpdate.Settings.USE_RECEIVED, token)
            }
        }
    }

    private fun chooseSettings(token: Int) {
        pull.setHint(activity.getString(R.string.tap_update_choose_settings))
        val dialog = TapUpdateSettingsDialog.newInstance(host.accentBackground)
        dialog.onChoice = { choice ->
            if (isReceiving && token == generation) {
                apply(choice, token)
            }
        }
        dialog.show(activity.supportFragmentManager, TapUpdateSettingsDialog.TAG)
    }

    private fun apply(settings: ThingFileUpdate.Settings, token: Int) {
        if (!isReceiving || token != generation) return
        // 小文件可能在进场下移结束前到齐，旧、新快照必须使用同一个稳定位置。
        if (pull.isMoving) {
            main.postDelayed({ apply(settings, token) }, 16)
            return
        }
        val ready = prepared ?: return
        applying = true
        pull.setHint(activity.getString(R.string.tap_receive_progress, senderName, 100))
        capturePage { old ->
            if (!isReceiving || token != generation) { old?.recycle(); return@capturePage }
            if (old != null) freeze(old)
            try {
                host.apply(ready, settings)
                prepared = null
            } catch (e: Exception) {
                Log.e(TAG, "更新当前记事失败", e)
                // 已提交后仍让程序错误进入崩溃日志，不假装回滚，更不能清理正在使用的附件。
                if (ready.isCommitted) throw e
                thaw()
                fail(R.string.tap_update_failed)
                return@capturePage
            }
            // 布局和封面加载是异步的；旧画面在等待期间仍完整显示。
            TapParticleFlight.afterFrame(host.container) {
                captureWhenContentReady(token, SystemClock.uptimeMillis())
            }
        }
    }

    private fun captureWhenContentReady(token: Int, started: Long) {
        if (!isReceiving || token != generation) return
        if (!host.isContentReadyForSnapshot()) {
            if (SystemClock.uptimeMillis() - started < 8000) {
                main.postDelayed({ captureWhenContentReady(token, started) }, 32)
            } else finish()
            return
        }
        TapParticleFlight.afterFrame(host.container) {
            if (!isReceiving || token != generation) return@afterFrame
            onReceiveOffsetChanged(pull.currentOffset)
            capturePage { fresh ->
                if (!isReceiving || token != generation) { fresh?.recycle(); return@capturePage }
                if (fresh == null || frozen == null) { fresh?.recycle(); finish(); return@capturePage }
                startReal(fresh, token)
            }
        }
    }

    private fun capturePage(done: (Bitmap?) -> Unit) {
        val hidden = listOfNotNull(stage, pull.button, frozen).filter { it.visibility == View.VISIBLE }
        hidden.forEach { it.visibility = View.INVISIBLE }
        try {
            val root = host.container
            TapParticleFlight.capture(root, Rect(0, 0, root.width, root.height), done)
        } finally { hidden.forEach { it.visibility = View.VISIBLE } }
    }

    private fun freeze(bitmap: Bitmap) {
        val root = host.container
        // 详情根布局把导航栏安全区作为 padding；冻结画面也必须覆盖这部分原背景。
        frozenBitmap = bitmap
        frozen = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(bitmap) }.also {
            root.addView(it, root.indexOfChild(stage).coerceAtLeast(0), FrameLayout.LayoutParams(root.width, root.height).apply {
                leftMargin = -root.paddingLeft
                topMargin = -root.paddingTop
            })
        }
    }

    private fun thaw() {
        frozen?.let { (it.parent as? FrameLayout)?.removeView(it); it.setImageDrawable(null) }
        frozen = null
        frozenBitmap?.let { bitmap -> TapParticleFlight.afterFrame(host.container) { bitmap.recycle() } }
        frozenBitmap = null
    }

    private fun flatBitmap(): Bitmap = Bitmap.createBitmap(host.container.width, host.container.height,
        Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun startProxy() {
        if (host.container.width <= 0 || host.container.height <= 0) return
        proxy?.release(null)
        proxy = TapParticleFlight.ReceiveLayer(activity, flatBitmap(), bounds(), antenna(), ring(), listOf(bounds()),
            1f - timeline.tauAt(now()), 1f, false, object : TapParticleFlight.ReceiveLayer.Listener {
                override fun onStarted() = Unit
                override fun onPresented(position: Float) = Unit
                override fun onLost() { proxy = null }
            }, seed = seed)
    }

    private fun startReal(bitmap: Bitmap, token: Int) {
        real = TapParticleFlight.ReceiveLayer(activity, bitmap, bounds(), antenna(), ring(), listOf(bounds()),
            1f - timeline.tauAt(now()), 0f, true, object : TapParticleFlight.ReceiveLayer.Listener {
                override fun onStarted() {
                    if (!isReceiving || token != generation) return
                    timeline.startInflow(now())
                    stage?.inflow()
                }
                override fun onPresented(position: Float) {
                    val inflow = timeline.inflow ?: return
                    if (isReceiving && token == generation && position <= .001f && now() >= inflow.endS) finish()
                }
                override fun onLost() { if (isReceiving && token == generation) finish() }
            }, dynamics = flatBitmap(), seed = seed)
        // GPU 丢失呈现回调时也必须显出真实内容。
        main.postDelayed({ if (isReceiving && token == generation && applying) finish() }, 15000)
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isReceiving || !busy) return
            val dt = if (lastFrame == 0L) 1f / 60f else (frameTimeNanos - lastFrame) / 1e9f
            lastFrame = frameTimeNanos
            val shown = smoother.step(model.target(SystemClock.uptimeMillis()), dt)
            val seconds = frameTimeNanos / 1e9
            timeline.advance(seconds, shown)
            val tau = timeline.tauAt(seconds)
            val inflow = timeline.inflow
            if (!timeline.isComplete && !waitingForWifiConfirmation) {
                val percent = (shown * 100).toInt().coerceIn(0, 100)
                if (percent != shownPercent) {
                    shownPercent = percent
                    pull.setHint(activity.getString(R.string.tap_receive_progress, senderName, percent))
                }
            }
            val fade = params.crossfade
            proxy?.apply {
                seek(1f - tau)
                alpha = if (inflow == null) 1f else 1f - smooth(inflow.startS + fade[1], inflow.startS + fade[2], seconds)
                if (inflow != null && seconds > inflow.startS + fade[2] + .15) { release(null); proxy = null }
            }
            real?.apply {
                seek(1f - tau)
                alpha = if (inflow == null) 0f else smooth(inflow.startS, inflow.startS + fade[0], seconds)
            }
            stage?.update(timeline.spread(seconds, shown), tau)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onFailed(reason: TapFailure) {
        diagnostics.failure("更新接收失败提示", "reason=$reason")
        if (isReceiving) fail(R.string.tap_receive_failed)
    }

    private fun fail(message: Int) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        reset()
    }

    private fun reset() {
        generation++
        busy = false
        applying = false
        Choreographer.getInstance().removeFrameCallback(frame)
        proxy?.release(null); proxy = null
        real?.release(null); real = null
        prepared?.discard(); prepared = null
        thaw()
        pull.setHint(activity.getString(R.string.tap_receive_hint))
        stage?.resumeWaiting()
        transport?.setExistingAttachments(host.existingAttachments())
        transport?.resume()
    }

    private fun finish() {
        thaw()
        real?.release(host.container); real = null
        exit()
        Toast.makeText(activity, R.string.tap_update_done, Toast.LENGTH_SHORT).show()
    }

    fun exit(immediate: Boolean = false) {
        if (!isReceiving) {
            if (immediate) { pull.closeSlot(false); restoreClipping() }
            return
        }
        isReceiving = false
        generation++
        back.isEnabled = false
        stopGuiding()
        transport?.stop(); transport = null
        Choreographer.getInstance().removeFrameCallback(frame)
        proxy?.release(null); proxy = null
        real?.release(null); real = null
        prepared?.discard(); prepared = null
        (activity.supportFragmentManager.findFragmentByTag(TapUpdateSettingsDialog.TAG) as? TapUpdateSettingsDialog)?.let {
            it.onChoice = null
            it.dismissAllowingStateLoss()
        }
        thaw()
        pull.setReceiving(false, animate = !immediate)
        val surface = stage
        stage = null
        if (surface != null && !immediate) surface.exit()
        else { surface?.let { host.container.removeView(it) }; restoreClipping() }
        busy = false
        applying = false
    }

    override fun onExitFinished(stage: TapReceiveStageView) {
        host.container.removeView(stage)
        if (!isReceiving) restoreClipping()
    }

    private fun restoreClipping() {
        receiveClipToPadding?.let { host.container.clipToPadding = it }
        receiveClipToPadding = null
    }

    /**
     * 图片详情从屏幕顶端开始，普通详情本来就留出了顶栏高度。
     * 让位时补齐前者缺少的顶栏间距，完整平移附件；仅限制下边缘，不能裁掉顶部来腾空间。
     */
    private fun onReceiveOffsetChanged(offset: Float) {
        if (!contentClipCaptured) return
        val list = host.recyclerView
        if (offset <= .5f) {
            list.clipBounds = contentClipBeforeReceive
            if (!isReceiving) contentClipCaptured = false
            return
        }
        val toolbar = host.actionbar
        val top = Rect(0, 0, toolbar.width, toolbar.height)
        host.container.offsetDescendantRectToMyCoords(toolbar, top)
        val missingTopInset = maxOf(0, top.bottom - list.top)
        val progress = (offset / receiveSlotHeight).coerceIn(0f, 1f)
        val translation = offset + missingTopInset * progress
        list.translationY = translation
        val clip = contentClipBeforeReceive?.let(::Rect) ?: Rect(0, 0, list.width, list.height)
        clip.bottom = minOf(clip.bottom, (list.height - translation).toInt()).coerceAtLeast(clip.top)
        list.clipBounds = clip
    }

    private fun bounds() = RectF().also {
        TapParticleFlight.boundsInDecor(activity, host.container,
            Rect(0, 0, host.container.width, host.container.height), it)
    }
    private fun ring() = RectF().also {
        stage?.ringFrame(it)
        val at = bounds()
        it.offset(at.left, at.top)
    }
    private fun antenna() = PointF().also {
        val decor = activity.window.decorView
        if (!TapTransfer.antennaPosition(activity, decor.width, decor.height, it)) it.set(decor.width / 2f, 48 * density)
    }
    private fun now() = SystemClock.uptimeMillis() / 1000.0
    private fun smooth(start: Double, end: Double, value: Double): Float {
        val t = ((value - start) / (end - start).coerceAtLeast(.001)).toFloat().coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    companion object { private const val TAG = "TapDetailReceive" }
}
