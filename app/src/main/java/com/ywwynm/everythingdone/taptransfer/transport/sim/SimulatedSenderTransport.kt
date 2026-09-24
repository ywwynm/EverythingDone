package com.ywwynm.everythingdone.taptransfer.transport.sim

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.taptransfer.TapTransfer
import com.ywwynm.everythingdone.taptransfer.transport.TapFailure
import com.ywwynm.everythingdone.taptransfer.transport.TapSenderTransport
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 调试包里的模拟发送方（决策 2026-09-24「开发阶段用调试包里的模拟开关代替真的贴」）。
 *
 * 不碰任何硬件：准备好之后等几秒，模拟一台名为「模拟设备」的手机碰上来，按真实的节奏走完
 * 入组（没有数据在传的那一段）与传输，最后报告已发送。每次打开分享界面只模拟一次，便于录屏
 * 与肉眼核对完整的一轮。
 *
 * 记事文件照样真的写出来，文件大小决定模拟的字节数，进度的走法与真实传输一致。
 */
class SimulatedSenderTransport(private val context: Context) : TapSenderTransport {

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private var listener: TapSenderTransport.Listener? = null
    private var offer: TapSenderTransport.Offer? = null
    private var generation = 0
    private var accepting = true
    private var touched = false

    override fun start(offer: TapSenderTransport.Offer, listener: TapSenderTransport.Listener) {
        this.offer = offer
        this.listener = listener
        val g = ++generation
        main.postDelayed({
            if (g != generation) {
                return@postDelayed
            }
            listener.onReady()
            scheduleTouch(g)
        }, READY_DELAY_MS)
    }

    override fun setAccepting(accepting: Boolean) {
        this.accepting = accepting
        if (accepting) {
            scheduleTouch(generation)
        }
    }

    override fun stop() {
        generation++
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        listener = null
    }

    private fun scheduleTouch(g: Int) {
        if (touched || !accepting) {
            return
        }
        main.postDelayed({
            if (g == generation && accepting && !touched) {
                simulate(g)
            }
        }, TOUCH_DELAY_MS)
    }

    private fun simulate(g: Int) {
        touched = true
        val peer = context.getString(R.string.tap_simulated_peer_name)
        listener?.onPeerTouched(peer)
        main.postDelayed({
            if (g != generation) {
                return@postDelayed
            }
            listener?.onPeerConnected()
            val payload = offer?.payload ?: return@postDelayed
            io.execute {
                val file = payload.await(PAYLOAD_TIMEOUT_MS)
                main.post {
                    if (g != generation) {
                        return@post
                    }
                    if (file == null) {
                        listener?.onFailed(TapFailure.FILE)
                    } else {
                        stepProgress(g, peer, file.length().coerceAtLeast(1L), 1)
                    }
                }
            }
        }, JOIN_DELAY_MS)
    }

    private fun stepProgress(g: Int, peer: String, total: Long, step: Int) {
        if (g != generation) {
            return
        }
        if (step > STEPS / 2 && TapTransfer.isSimulatedFailure(context)) {
            listener?.onFailed(TapFailure.TRANSFER)
            return
        }
        listener?.onProgress(total * step / STEPS, total)
        if (step >= STEPS) {
            main.postDelayed({
                if (g == generation) {
                    listener?.onSent(peer)
                }
            }, ACK_DELAY_MS)
            return
        }
        main.postDelayed({ stepProgress(g, peer, total, step + 1) }, TRANSFER_MS / STEPS)
    }

    private companion object {
        /** 模拟建组的耗时。 */
        const val READY_DELAY_MS = 450L

        /** 就绪后多久模拟对方碰上：留出时间看清抬升好的卡片与等待提示。 */
        const val TOUCH_DELAY_MS = 3200L

        /** 模拟入组：实测 0.6–3 秒，取中间偏快的值。 */
        const val JOIN_DELAY_MS = 1300L

        const val TRANSFER_MS = 800L
        const val STEPS = 16
        const val ACK_DELAY_MS = 120L
        const val PAYLOAD_TIMEOUT_MS = 60_000L
    }
}
