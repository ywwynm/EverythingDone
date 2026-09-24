package com.ywwynm.everythingdone.taptransfer.ui.share

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.taptransfer.TapPrerequisite
import com.ywwynm.everythingdone.taptransfer.TapTransfer
import com.ywwynm.everythingdone.taptransfer.transport.TapFailure
import com.ywwynm.everythingdone.taptransfer.transport.TapPayloadWriter
import com.ywwynm.everythingdone.taptransfer.transport.TapSenderTransport
import com.ywwynm.everythingdone.taptransfer.ui.TapProgressModel

/**
 * 分享界面里碰一碰那一块的状态：什么时候可以被碰、碰上了谁、传到哪了、成没成。
 *
 * 只管状态，不管画面：[TapShareOverlay] 按 [Listener.onPhaseChanged] 换文案，按 [progress]
 * 推动粒子流出。所有方法与回调都在主线程。
 */
class TapSendController(
    private val activity: Activity,
    private val things: List<Thing>,
    private val listener: Listener
) : TapSenderTransport.Listener {

    enum class Phase {
        /** 设备不支持，碰一碰区域整块隐藏。 */
        HIDDEN,

        /** 缺权限或开关，见 [missing]。 */
        SETUP,

        PREPARING,

        /** 可以被碰了。[lastFailed] 为 true 时提示「发送失败，请再碰一次」。 */
        READY,

        /** 碰上了，对方正在入组。 */
        CONNECTING,

        SENDING,

        /** 对方已确认收到。 */
        SENT,

        /** 这一次没发成；画面流回之后回到 [READY]。 */
        FAILED,

        /** 准备失败（建组失败等），碰一碰这次用不了。 */
        UNAVAILABLE
    }

    interface Listener {
        fun onPhaseChanged(phase: Phase)
    }

    var phase: Phase = Phase.HIDDEN
        private set

    var peerName: String = ""
        private set

    var missing: List<TapPrerequisite> = emptyList()
        private set

    var lastFailed: Boolean = false
        private set

    /** 本次打开分享界面以来成功发出过至少一次。 */
    var sentAtLeastOnce: Boolean = false
        private set

    val progress = TapProgressModel()

    private var transport: TapSenderTransport? = null
    private var payload: TapPayloadWriter? = null

    fun start() {
        if (!isSupported(activity)) {
            setPhase(Phase.HIDDEN)
            return
        }
        evaluate()
    }

    /** 从系统设置、权限对话框回来之后重新检查一遍。 */
    fun recheck() {
        if (phase == Phase.SETUP) {
            evaluate()
        }
    }

    /** 画面上「已发送」或「失败」的动画播完了，可以接下一台。 */
    fun readyForNext() {
        if (phase == Phase.SENT || phase == Phase.FAILED) {
            setPhase(Phase.READY)
            transport?.setAccepting(true)
        }
    }

    /** 离开前台时拆掉 Wi-Fi Direct 组，回来再建；正在传的这一次按失败处理。 */
    fun pause() {
        if (transport == null) {
            return
        }
        stopTransport()
        if (phase != Phase.HIDDEN && phase != Phase.UNAVAILABLE) {
            setPhase(Phase.PREPARING)
        }
    }

    fun resumeIfPaused() {
        if (transport == null && phase == Phase.PREPARING) {
            evaluate()
        }
    }

    fun stop() {
        stopTransport()
        payload?.discard()
        payload = null
    }

    private fun evaluate() {
        missing = TapTransfer.missingPrerequisites(activity)
        if (missing.isNotEmpty()) {
            setPhase(Phase.SETUP)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arm()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun arm() {
        setPhase(Phase.PREPARING)
        // 离开前台再回来时沿用已经写好的记事文件；文件没了（被清理）就重写
        val writer = payload?.takeIf { it.isUsable() } ?: run {
            payload?.discard()
            TapPayloadWriter(activity, things).also { payload = it }
        }
        val colors = IntArray(things.size) { things[it].getBackground()?.representativeColor() ?: things[it].getColor() }
        val t = TapTransfer.createSenderTransport(activity)
        transport = t
        t.start(
            TapSenderTransport.Offer(TapTransfer.deviceName(activity), things.size, colors, writer),
            this
        )
    }

    private fun stopTransport() {
        transport?.stop()
        transport = null
    }

    private fun setPhase(next: Phase) {
        phase = next
        listener.onPhaseChanged(next)
    }

    // ---------------------------------------------------------------- 传输回调

    override fun onReady() {
        setPhase(Phase.READY)
        transport?.setAccepting(true)
    }

    override fun onUnavailable(reason: TapFailure) {
        Log.w(TAG, "碰一碰准备失败：$reason")
        stopTransport()
        setPhase(Phase.UNAVAILABLE)
    }

    override fun onPeerTouched(peerName: String) {
        this.peerName = peerName
        transport?.setAccepting(false)
        progress.touched(SystemClock.uptimeMillis())
        setPhase(Phase.CONNECTING)
    }

    override fun onPeerConnected() {
        progress.connected(SystemClock.uptimeMillis())
        setPhase(Phase.SENDING)
    }

    override fun onProgress(done: Long, total: Long) {
        progress.progress(done, total)
    }

    override fun onSent(peerName: String) {
        if (peerName.isNotEmpty()) {
            this.peerName = peerName
        }
        progress.finished()
        lastFailed = false
        sentAtLeastOnce = true
        setPhase(Phase.SENT)
    }

    override fun onFailed(reason: TapFailure) {
        Log.w(TAG, "发送失败：$reason")
        lastFailed = true
        setPhase(Phase.FAILED)
    }

    companion object {
        private const val TAG = "TapSend"

        /** 这台设备上分享界面要不要显示碰一碰那一块。 */
        @JvmStatic
        fun isSupported(activity: Activity): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && TapTransfer.isEntryAvailable(activity)
        }
    }
}
