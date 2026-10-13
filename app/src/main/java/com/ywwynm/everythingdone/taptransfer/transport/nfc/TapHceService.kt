package com.ywwynm.everythingdone.taptransfer.transport.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.ywwynm.everythingdone.taptransfer.protocol.TapCardResponder
import com.ywwynm.everythingdone.taptransfer.protocol.TapApdu
import com.ywwynm.everythingdone.taptransfer.diagnostics.TapDiagnostics

/**
 * 发送方的卡模拟服务：接收方的手机开着读取模式碰过来时，系统把选中我们 AID 的命令交到这里。
 *
 * 服务随时可能被系统拉起（任何读卡设备碰一下都会），所以它本身不持有任何内容：只有分享界面
 * 打开、Wi-Fi Direct 组已经建好时，[TapSenderGate] 里才有可以应答的内容，否则一律回「未就绪」。
 */
class TapHceService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        return TapSenderGate.responder.process(commandApdu).also { response ->
            TapSenderGate.diagnostics.event("HCE 命令应答",
                "ins=${commandApdu?.getOrNull(1)?.toInt()?.and(0xff)}, " +
                    "requestBytes=${commandApdu?.size}, responseBytes=${response.size}, " +
                    "sw=${Integer.toHexString(TapApdu.statusWord(response))}")
        }
    }

    override fun onDeactivated(reason: Int) {
        TapSenderGate.diagnostics.event("HCE 链路断开", "reason=$reason；握手后分开属正常情况")
        // 链路断开（两台手机分开）不需要处理：握手在一次连接里就读完了
    }
}

/**
 * 卡模拟服务与分享界面之间的门：分享界面准备好之后把 [TapCardResponder.Offer] 挂上来，
 * 关闭时摘掉。
 */
object TapSenderGate {

    @Volatile
    var diagnostics: TapDiagnostics.Session = TapDiagnostics.NONE
        private set

    @Volatile
    private var offer: TapCardResponder.Offer? = null

    val responder = TapCardResponder { offer }

    fun open(offer: TapCardResponder.Offer, diagnostics: TapDiagnostics.Session = TapDiagnostics.NONE) {
        this.diagnostics = diagnostics
        this.offer = offer
    }

    /** 只摘掉自己挂上的那个：新打开的分享界面可能已经挂上了新的。 */
    fun close(offer: TapCardResponder.Offer) {
        if (this.offer === offer) {
            this.offer = null
        }
    }
}
