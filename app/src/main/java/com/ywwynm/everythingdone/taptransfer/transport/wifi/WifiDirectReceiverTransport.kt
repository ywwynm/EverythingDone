package com.ywwynm.everythingdone.taptransfer.transport.wifi

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.taptransfer.TapTransfer
import com.ywwynm.everythingdone.taptransfer.diagnostics.TapDiagnostics
import com.ywwynm.everythingdone.taptransfer.diagnostics.TapConnectRetryLog
import com.ywwynm.everythingdone.taptransfer.diagnostics.TapP2pDiagnostics
import com.ywwynm.everythingdone.taptransfer.protocol.TapApdu
import com.ywwynm.everythingdone.taptransfer.protocol.TapHandshake
import com.ywwynm.everythingdone.taptransfer.protocol.TapReaderExchange
import com.ywwynm.everythingdone.taptransfer.protocol.TapReceiverGroupOffer
import com.ywwynm.everythingdone.taptransfer.protocol.TapStream
import com.ywwynm.everythingdone.taptransfer.protocol.TapFileTransfer
import com.ywwynm.everythingdone.taptransfer.transport.TapReceivedPayload
import com.ywwynm.everythingdone.taptransfer.transport.TapFailure
import com.ywwynm.everythingdone.taptransfer.transport.TapIncomingOffer
import com.ywwynm.everythingdone.taptransfer.transport.TapReceiverTransport
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections

/**
 * 真实的接收方：开 NFC 读取模式，碰上之后读出握手、按 SSID 与密码直接加入发送方的
 * Wi-Fi Direct 组，经 TCP 收下记事文件，收完离组。
 * 调试兼容环境也可以提前建组，通过 NFC 让发送方加入，再验证主动连来的发送身份。
 *
 * 读取模式只在前台生效，这也是接收状态「离开首页、锁屏即结束」的依据（决策 2026-09-24）。
 * 读取模式开着时本机的公交卡、门禁卡暂时不能用，所以只在用户显式进入接收状态时开。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class WifiDirectReceiverTransport(private val activity: Activity,
                                  private val diagnostics: TapDiagnostics.Session = TapDiagnostics.NONE) :
    TapReceiverTransport, NfcAdapter.ReaderCallback {

    private val context: Context = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val nfc: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null

    private var listener: TapReceiverTransport.Listener? = null
    private var existingAttachments = emptyList<TapFileTransfer.LocalAttachment>()

    override fun setExistingAttachments(attachments: List<TapFileTransfer.LocalAttachment>) {
        existingAttachments = attachments.toList()
    }
    private var receiverName = ""
    private val receiverId = ByteArray(TapApdu.RECEIVER_ID_BYTES)

    /** 每次 start / stop 加一；后台线程与迟到的回调据此判断自己是否已经过时。写在主线程，读在各线程。 */
    @Volatile
    private var generation = 0

    /**
     * 「回确认字节」与「退出接收」互斥。确认一旦发出，发送方就会显示已发送，这份文件就不能再丢：
     * 要么在退出之前发出确认、文件照常交出去，要么退出在先、确认不发，两边都算失败。
     */
    private val ackLock = Any()

    /** 碰上之后到 [resume] 之前为 true，期间再碰一律忽略。读取回调在 binder 线程，故用 volatile。 */
    @Volatile
    private var busy = false

    /** 本次接收状态里已经收完的发送（按令牌），同一次发送不会收两遍。 */
    private val doneTokens: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** 以下只在主线程读写。 */
    private var pending: TapHandshake? = null
    @Volatile private var attempt: TapJoinAttempt? = null
    private var wifiConnection: TapWifiNetworkConnection? = null
    @Volatile private var receiverGroupHost: TapReceiverGroupHost? = null
    private var preferReceiverGroup = false

    /** 这一次碰触要加入的组。离组时只拆这个组：设备可能同时在别的应用建的组里（投屏、打印）。 */
    private var joiningSsid: String? = null
    private var awaitingGroup = false

    private var joinTimeout: Runnable? = null
    private var receiverRegistered = false

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var downloader: Thread? = null

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                return
            }
            @Suppress("DEPRECATION")
            val info: WifiP2pInfo? = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
            onConnectionChanged(info)
        }
    }

    override fun start(receiverName: String, listener: TapReceiverTransport.Listener) {
        diagnostics.event("启动接收传输", "manager=${manager != null}, nfc=${nfc != null}")
        this.listener = listener
        this.receiverName = receiverName
        SecureRandom().nextBytes(receiverId)
        generation++
        busy = false
        doneTokens.clear()
        val g = generation
        preferReceiverGroup = TapTransfer.preferReceiverGroup(context)
        if (!preferReceiverGroup) ensureReceiverChannel(g)
        ContextCompat.registerReceiver(
            context, p2pReceiver,
            IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        diagnostics.event("P2P 接收广播已注册", "CONNECTION_CHANGED, NOT_EXPORTED")
        diagnostics.event("接收组网策略", "receiverCreatesGroup=$preferReceiverGroup")
        prepareReception(g)
    }

    /** 每次重试先完成上次拆组；等待期间不接受新的 NFC 握手。 */
    private fun prepareReception(g: Int) {
        busy = true
        if (preferReceiverGroup) listener?.onPreparingChanged(true)
        val prepare: () -> Unit = {
            if (g == generation) {
                receiverGroupHost = null
                if (preferReceiverGroup) {
                    val host = TapReceiverGroupHost(context, diagnostics)
                    receiverGroupHost = host
                    host.prepare { ready ->
                        if (g == generation && receiverGroupHost === host) {
                            diagnostics.event("接收准备完成", "receiverGroupReady=$ready")
                            busy = false
                            listener?.onPreparingChanged(false)
                            enableReader()
                        }
                    }
                } else {
                    busy = false
                    listener?.onPreparingChanged(false)
                    enableReader()
                }
            }
        }
        val previous = receiverGroupHost
        if (previous == null) prepare() else previous.close("准备下一次接收", prepare)
    }

    private fun enableReader() {
        val extras = Bundle()
        extras.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_MS)
        try {
            nfc?.enableReaderMode(activity, this, READER_FLAGS, extras)
            diagnostics.event("NFC 读取模式调用返回", "adapter=${nfc != null}, flags=$READER_FLAGS")
        } catch (e: IllegalStateException) {
            diagnostics.failure("NFC 读取模式开启失败", error = e)
            // 同一个页面上分享界面停掉的轮询还没恢复（TapNfcDiscovery）：两者互斥
            Log.w(TAG, "开不了读取模式", e)
        }
    }

    override fun resume() {
        diagnostics.event("恢复等待下一次碰触")
        attempt?.finish()
        attempt = null
        releaseWifiConnection()
        pending = null
        prepareReception(generation)
    }

    override fun stop() {
        diagnostics.event("停止接收传输", "busy=$busy, awaitingGroup=$awaitingGroup")
        synchronized(ackLock) {
            generation++
            attempt?.finish()
            attempt = null
        }
        main.removeCallbacksAndMessages(null)
        joinTimeout = null
        try {
            nfc?.disableReaderMode(activity)
        } catch (e: IllegalStateException) {
            diagnostics.failure("NFC 读取模式关闭失败", error = e)
            // Activity 已经不在前台时系统会自己关掉读取模式
        }
        if (receiverRegistered) {
            context.unregisterReceiver(p2pReceiver)
            receiverRegistered = false
        }
        downloader?.interrupt()
        downloader = null
        closeQuietly(socket)
        releaseWifiConnection()
        receiverGroupHost?.close("退出接收状态")
        receiverGroupHost = null
        val c = channel
        channel = null
        if (c != null) {
            cancelJoin(c)
            // 拆组要先问一次组信息，回调到了再关通道；回调迟迟不来也要关掉
            val closeChannel = object : Runnable {
                private var closed = false
                override fun run() {
                    if (!closed) {
                        closed = true
                        c.close()
                    }
                }
            }
            leaveGroup(c, joiningSsid, closeChannel)
            main.postDelayed(closeChannel, CHANNEL_CLOSE_FALLBACK_MS)
        }
        joiningSsid = null
        pending = null
        listener = null
    }

    // ---------------------------------------------------------------- NFC

    /** 在 binder 线程上回调。 */
    override fun onTagDiscovered(tag: Tag) {
        diagnostics.event("NFC 发现设备", "busy=$busy, tech=${tag.techList.joinToString()}")
        if (busy) {
            return
        }
        val g = generation
        val iso = IsoDep.get(tag) ?: run {
            diagnostics.event("NFC 忽略本次碰触", "未提供 IsoDep")
            return
        }
        try {
            diagnostics.event("NFC 连接 IsoDep")
            iso.connect()
            iso.timeout = ISO_TIMEOUT_MS
            diagnostics.event("NFC IsoDep 已连接", "maxTransceive=${iso.maxTransceiveLength}, timeout=$ISO_TIMEOUT_MS")
            val exchange = TapReaderExchange { command ->
                val ins = command.getOrNull(1)?.toInt()?.and(0xff)
                diagnostics.event("NFC 发出命令", "ins=$ins, bytes=${command.size}")
                iso.transceive(command).also { response ->
                    diagnostics.event("NFC 收到应答", "ins=$ins, bytes=${response.size}, " +
                        "sw=${Integer.toHexString(TapApdu.statusWord(response))}")
                }
            }
            val outcome = exchange.readHandshake()
            if (outcome !is TapReaderExchange.Outcome.Offered) {
                diagnostics.event("NFC 未取得可用握手", "对方未就绪或不是本应用")
                return
            }
            val handshake = outcome.handshake
            diagnostics.hide(handshake.ssid, handshake.passphrase, handshake.token.toHex())
            diagnostics.event("NFC 握手解码成功", "version=${TapHandshake.VERSION}, items=${handshake.thingCount}, " +
                "port=${handshake.port}, ssidLength=${handshake.ssid.length}, passwordLength=${handshake.passphrase.length}, " +
                "wifiCompatibilityAddress=${handshake.groupOwnerIpv4 != null}, " +
                "wifiSenderWaitMs=${handshake.wifiConnectionTimeoutMs}")
            if (doneTokens.contains(handshake.token.toHex())) {
                diagnostics.event("NFC 忽略重复来件", "本接收会话已完成过该发送")
                return
            }
            val receiveGroup = receiverGroupHost?.ready?.takeIf { outcome.supportsReceiverGroup }
            val reverse = receiveGroup?.offer(handshake.token, receiverId, receiverName)
            diagnostics.checkpoint("NFC 选择组网方向", "senderSupportsReceiverGroup=${outcome.supportsReceiverGroup}, " +
                "receiverCreatesGroup=${reverse != null}")
            val sw = if (reverse != null) exchange.receiverGroup(reverse) else exchange.hello(receiverId, receiverName)
            if (sw != TapApdu.SW_OK) {
                diagnostics.failure("NFC HELLO 被拒绝", "sw=${Integer.toHexString(sw)}")
                Log.i(TAG, "发送方拒绝：${Integer.toHexString(sw)}")
                return
            }
            busy = true
            diagnostics.event("NFC 握手完成", "HELLO=9000")
            main.post {
                if (g == generation) {
                    onTouched(g, handshake, receiveGroup, reverse)
                }
            }
        } catch (e: IOException) {
            diagnostics.failure("NFC 碰触未完成", error = e)
            // 贴得不稳、刚碰上就分开，或者对方给的握手不合规：这次碰触不算，接着等
            Log.i(TAG, "这次碰触没读完：${e.message}")
        } catch (e: RuntimeException) {
            diagnostics.failure("NFC 碰触异常", error = e)
            // 碰上来的可以是任何设备。这里在 binder 线程上，漏出去的异常会让整个应用崩溃
            Log.w(TAG, "这次碰触出错", e)
        } finally {
            closeQuietly(iso)
        }
    }

    private fun onTouched(g: Int, handshake: TapHandshake, receiveGroup: TapReceiverGroupHost.Ready?,
                          reverse: TapReceiverGroupOffer?) {
        TapDiagnostics.environment(context, diagnostics)
        pending = handshake
        val currentAttempt = TapJoinAttempt(SystemClock.uptimeMillis(), handshake.wifiConnectionTimeoutMs, reverse != null)
        attempt = currentAttempt
        diagnostics.checkpoint("开始本次接收", "generation=$g, connectionDeadlineMs=${currentAttempt.windowMs}, " +
            "receiverCreatesGroup=${reverse != null}")
        listener?.onTouched(
            TapIncomingOffer(handshake.senderName, handshake.thingCount, handshake.colors, handshake.aspects,
                handshake.fullSpan)
        )
        if (receiveGroup != null && reverse != null) {
            receiverGroupHost?.capture("NFC 完成，等待主动 TCP")
            if (diagnostics.enabled) main.postDelayed({
                if (isCurrent(g, currentAttempt) && currentAttempt.waitingFor(TapJoinAttempt.Route.RECEIVER_GROUP))
                    receiverGroupHost?.capture("等待主动 TCP 已达 8 秒")
            }, 8000)
            scheduleJoinTimeout(g, currentAttempt)
            download(g, currentAttempt, "", handshake, receiveGroup = receiveGroup, reverse = reverse)
        } else {
            // 旧发送方没有能力位：先释放预建组，仍使用原 HELLO 和兼容接收。
            val host = receiverGroupHost
            if (host == null) join(g, currentAttempt, handshake)
            else {
                scheduleJoinTimeout(g, currentAttempt)
                diagnostics.event("发送方使用原组网协议", "先释放接收方预建组")
                host.close("发送方使用原组网协议") { if (isCurrent(g, currentAttempt)) join(g, currentAttempt, handshake) }
            }
        }
    }

    // ---------------------------------------------------------------- Wi-Fi Direct

    private fun ensureReceiverChannel(g: Int) {
        if (channel != null || g != generation) return
        channel = manager?.initialize(context, Looper.getMainLooper()) {
            if (g == generation) diagnostics.failure("P2P 接收通道断开", "generation=$g, busy=$busy")
            else diagnostics.event("已停止的 P2P 接收通道断开", "generation=$g")
        }
        diagnostics.event("P2P 接收通道初始化", "channel=${channel != null}")
        TapP2pDiagnostics.capture(diagnostics, manager, channel, "接收通道初始化后")
    }

    private fun join(g: Int, currentAttempt: TapJoinAttempt, handshake: TapHandshake) {
        if (!isCurrent(g, currentAttempt)) return
        ensureReceiverChannel(g)
        val m = manager
        val c = channel
        if (m == null || c == null) {
            diagnostics.failure("无法请求 P2P 入组", "manager=${m != null}, channel=${c != null}")
            fail(g, currentAttempt, TapFailure.JOIN)
            return
        }
        scheduleJoinTimeout(g, currentAttempt)
        awaitingGroup = true
        joiningSsid = handshake.ssid
        try {
            diagnostics.event("构造 P2P 入组配置", "WifiP2pConfig.Builder，按组名和密码连接")
            val config = WifiP2pConfig.Builder()
                .setNetworkName(handshake.ssid)
                .setPassphrase(handshake.passphrase)
                .build()
            TapP2pDiagnostics.config(diagnostics, config)
            TapP2pDiagnostics.capture(diagnostics, m, c, "入组请求前", handshake.ssid)
            diagnostics.event("请求 P2P connect")
            val requestedAt = SystemClock.elapsedRealtime()
            m.connect(c, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!isCurrent(g, currentAttempt) || !currentAttempt.waitingFor(TapJoinAttempt.Route.P2P)) return
                    diagnostics.event("P2P connect 请求已受理", "elapsedMs=${SystemClock.elapsedRealtime() - requestedAt}；等待入组结果，尚未表示已连接")
                    // 入组结果从 WIFI_P2P_CONNECTION_CHANGED_ACTION 来；已经在组里时不会再有广播，主动查一次
                    try {
                        m.requestConnectionInfo(c) { info ->
                            if (!isCurrent(g, currentAttempt)) return@requestConnectionInfo
                            diagnostics.event("P2P 主动查询结果")
                            onConnectionChanged(info)
                        }
                    } catch (e: SecurityException) {
                        diagnostics.failure("查询 P2P 连接状态失败", error = e)
                    }
                }

                override fun onFailure(reason: Int) {
                    if (!isCurrent(g, currentAttempt) || !currentAttempt.waitingFor(TapJoinAttempt.Route.P2P)) return
                    diagnostics.failure("P2P connect 请求被拒绝", "reason=${TapDiagnostics.p2pReason(reason)}, " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - requestedAt}")
                    if (g == generation) TapP2pDiagnostics.capture(diagnostics, m, c, "入组拒绝后、清理前发起", handshake.ssid)
                    Log.w(TAG, "入组请求失败：$reason")
                    if (BuildConfig.DEBUG && reason == WifiP2pManager.ERROR && handshake.groupOwnerIpv4 != null) {
                        joinViaWifi(g, currentAttempt, handshake)
                    } else {
                        diagnostics.event("未启用普通 Wi-Fi 兼容尝试", "debug=${BuildConfig.DEBUG}, " +
                            "errorMatches=${reason == WifiP2pManager.ERROR}, address=${handshake.groupOwnerIpv4 != null}")
                        fail(g, currentAttempt, TapFailure.JOIN)
                    }
                }
            })
        } catch (e: SecurityException) {
            diagnostics.failure("P2P 入组权限异常", error = e)
            fail(g, currentAttempt, TapFailure.JOIN)
        } catch (e: IllegalArgumentException) {
            diagnostics.failure("P2P 入组配置异常", error = e)
            // 握手解码时已经按同样的规则校验过，这里只是兜底
            fail(g, currentAttempt, TapFailure.JOIN)
        } catch (e: RuntimeException) {
            diagnostics.failure("P2P 入组调用异常", error = e)
            throw e
        }
    }

    private fun isCurrent(g: Int, currentAttempt: TapJoinAttempt): Boolean =
        g == generation && attempt === currentAttempt && currentAttempt.active

    private fun scheduleJoinTimeout(g: Int, currentAttempt: TapJoinAttempt) {
        joinTimeout?.let { main.removeCallbacks(it) }
        val timeout = Runnable {
            if (!isCurrent(g, currentAttempt)) return@Runnable
            diagnostics.failure("接收连接等待超时", "limitMs=${currentAttempt.windowMs}, awaitingP2p=$awaitingGroup, " +
                "wifiCompatibility=${wifiConnection != null}")
            if (currentAttempt.waitingFor(TapJoinAttempt.Route.RECEIVER_GROUP))
                receiverGroupHost?.capture("主动 TCP 等待超时，关闭前")
            fail(g, currentAttempt, TapFailure.JOIN)
        }
        joinTimeout = timeout
        main.postDelayed(timeout, currentAttempt.remainingMs(SystemClock.uptimeMillis()))
    }

    private fun joinViaWifi(g: Int, currentAttempt: TapJoinAttempt, handshake: TapHandshake) {
        if (!isCurrent(g, currentAttempt) || !currentAttempt.switchToWifi()) return
        val host = handshake.groupOwnerIpv4 ?: return
        // connect 已被明确拒绝，停止本次 P2P 入组，再尝试同一组的普通客户端入口。
        channel?.let { cancelJoin(it) }
        scheduleJoinTimeout(g, currentAttempt)
        val remaining = currentAttempt.remainingMs(SystemClock.uptimeMillis())
        if (remaining <= 0) {
            fail(g, currentAttempt, TapFailure.JOIN)
            return
        }
        diagnostics.event("切换到普通 Wi-Fi 兼容接收", "host=$host, remainingMs=$remaining, " +
            "senderWaitMs=${handshake.wifiConnectionTimeoutMs}；请在系统提示中选择发送设备并允许连接")
        listener?.onWifiConfirmationChanged(true)
        val connection = TapWifiNetworkConnection(context, diagnostics)
        wifiConnection = connection
        connection.request(handshake.ssid, handshake.passphrase, remaining.toInt(), onReady = { network ->
            if (isCurrent(g, currentAttempt) && currentAttempt.beginTransfer(TapJoinAttempt.Route.WIFI)) {
                joinTimeout?.let { main.removeCallbacks(it) }
                joinTimeout = null
                listener?.onWifiConfirmationChanged(false)
                download(g, currentAttempt, host, handshake, network)
            }
        }, onFailure = {
            fail(g, currentAttempt, TapFailure.JOIN)
        })
    }

    private fun releaseWifiConnection() {
        wifiConnection?.close()
        wifiConnection = null
    }

    private fun onConnectionChanged(info: WifiP2pInfo?) {
        diagnostics.event("P2P 接收连接状态", "awaiting=$awaitingGroup, info=${info != null}, " +
            "formed=${info?.groupFormed}, owner=${info?.isGroupOwner}, host=${info?.groupOwnerAddress?.hostAddress}")
        if (!awaitingGroup || info == null || !info.groupFormed || info.isGroupOwner) {
            return
        }
        val host = info.groupOwnerAddress?.hostAddress ?: return
        val handshake = pending ?: return
        val currentAttempt = attempt ?: return
        if (!currentAttempt.beginTransfer(TapJoinAttempt.Route.P2P)) return
        awaitingGroup = false
        diagnostics.event("P2P 入组完成", "host=$host, port=${handshake.port}")
        joinTimeout?.let { main.removeCallbacks(it) }
        joinTimeout = null
        download(generation, currentAttempt, host, handshake)
    }

    private fun download(g: Int, currentAttempt: TapJoinAttempt, host: String, handshake: TapHandshake,
                         network: Network? = null, receiveGroup: TapReceiverGroupHost.Ready? = null,
                         reverse: TapReceiverGroupOffer? = null) {
        // 确认发出之后，即使接收状态已经退出也要把文件交给它：发送方已经显示已发送了
        val receiver = listener ?: return
        val existing = existingAttachments
        val until = currentAttempt.deadlineMs
        val thread = Thread({
            diagnostics.event("开始 TCP 接收")
            val dst = File(TapTransfer.workDir(context, "in"), "recv-${java.util.UUID.randomUUID()}")
            var s: Socket? = null
            var committed = false
            try {
                s = (if (receiveGroup != null && reverse != null) {
                    if (diagnostics.enabled)
                        diagnostics.checkpoint("等待发送方加入接收组并连接 TCP", receiveGroup.server.diagnosticState())
                    receiveGroup.server.accept(reverse,
                        { isCurrent(g, currentAttempt) && SystemClock.uptimeMillis() < until },
                        { diagnostics.event("拒绝未匹配本次碰触的 TCP", error = it) })
                } else connectWithRetry(g, currentAttempt, host, handshake.port, until, network)) ?: run {
                    if (!isCurrent(g, currentAttempt)) {
                        diagnostics.event("已结束的 TCP 接收等待退出")
                        return@Thread
                    }
                    diagnostics.failure("TCP 连接未建立", "remainingMs=${until - SystemClock.uptimeMillis()}")
                    main.post { fail(g, currentAttempt, TapFailure.CONNECT) }
                    return@Thread
                }
                socket = s
                // stop() 可能发生在上一行之前：那时它关不到这条连接，由 finally 关
                if (!isCurrent(g, currentAttempt)) {
                    return@Thread
                }
                if (reverse != null) {
                    diagnostics.event("接收组 TCP 发送身份校验通过")
                    main.post {
                        if (isCurrent(g, currentAttempt) && currentAttempt.beginTransfer(TapJoinAttempt.Route.RECEIVER_GROUP)) {
                            joinTimeout?.let { main.removeCallbacks(it) }
                            joinTimeout = null
                        }
                    }
                }
                s.tcpNoDelay = true
                val input = BufferedInputStream(s.getInputStream(), BUFFER)
                val output = BufferedOutputStream(s.getOutputStream())
                TapStream.writeClientHello(output, handshake.token, receiverId)
                diagnostics.event("TCP 身份信息已发送", "等待文件头，timeoutMs=$HEADER_TIMEOUT_MS")
                // 附件大时发送方可能还在写记事文件：头部与发送方等文件的时限对齐，数据段恢复短超时
                s.soTimeout = HEADER_TIMEOUT_MS
                var lastPost = 0L
                var diagnosticProgress = 0L
                val received = TapFileTransfer.receive(input, output, dst, existing, connected = { total ->
                    s.soTimeout = SOCKET_TIMEOUT_MS
                    diagnostics.event("附件协商完成", "bytes=$total")
                    main.post { if (isCurrent(g, currentAttempt)) listener?.onConnected(total) }
                }) { done, total ->
                        val now = SystemClock.uptimeMillis()
                        if (now - diagnosticProgress >= 1000 || done == total) {
                            diagnosticProgress = now
                            diagnostics.event("接收字节进度", "$done/$total")
                        }
                        if (now - lastPost >= PROGRESS_INTERVAL_MS || done == total) {
                            lastPost = now
                            main.post { if (isCurrent(g, currentAttempt)) listener?.onProgress(done, total) }
                        }
                }
                synchronized(ackLock) {
                    if (!isCurrent(g, currentAttempt)) {
                        throw IOException("接收状态已经退出")
                    }
                    TapStream.writeAck(output)
                    diagnostics.event("TCP 接收 ACK 已写出", "attachmentBytes=${received.transferredBytes}, reused=${received.borrowedKeys.size}")
                    committed = true
                }
                run {
                    // ACK flush 只进入本机缓冲。所有组网方式均等发送方关闭 TCP 后再拆组，
                    // 或短暂等待到期；此时文件已确认交付，不因这一步失败删掉完整文件。
                    s.soTimeout = WIFI_FINISH_WAIT_MS
                    try {
                        TapStream.awaitSenderClose(input)
                        diagnostics.event("发送方已结束 TCP", "可释放本次网络")
                    } catch (e: IOException) {
                        diagnostics.event("TCP 结束等待未确认", "已写出 ACK，仍交付完整文件", e)
                    }
                }
                doneTokens.add(handshake.token.toHex())
                main.post {
                    if (isCurrent(g, currentAttempt)) {
                        currentAttempt.finish()
                        releaseWifiConnection()
                        receiverGroupHost?.close("完整文件已确认交付")
                        channel?.let { leaveGroup(it, joiningSsid, null) }
                        joiningSsid = null
                    }
                    receiver.onReceived(TapReceivedPayload.Files(received))
                }
            } catch (e: IOException) {
                if (!isCurrent(g, currentAttempt)) {
                    // 超时／退出会主动关闭 accept；它是已记录失败的清理结果，不是新故障。
                    diagnostics.event("已结束的 TCP 接收线程退出", "exception=${e.javaClass.simpleName}")
                } else {
                    diagnostics.failure("TCP 接收中断", error = e)
                    Log.w(TAG, "接收中断", e)
                    main.post { fail(g, currentAttempt, TapFailure.TRANSFER) }
                }
            } finally {
                diagnostics.event("关闭接收 TCP", "committed=$committed, attemptEnded=${!isCurrent(g, currentAttempt)}")
                closeQuietly(s)
                if (socket === s) {
                    socket = null
                }
                if (!committed) {
                    // 传了一半的内容一律丢弃（决策 2026-09-24）
                    dst.deleteRecursively()
                }
            }
        }, "tap-receive")
        downloader = thread
        thread.start()
    }

    /** 入组广播到来时 DHCP 未必已经分好地址，按验证程序的节奏短间隔重试，直到 [until]。 */
    private fun connectWithRetry(g: Int, currentAttempt: TapJoinAttempt, host: String, port: Int,
                                 until: Long, network: Network?): Socket? {
        var attempt = 0
        val retries = TapConnectRetryLog(SystemClock.uptimeMillis(), diagnostics.enabled) { detail, error, checkpoint ->
            if (checkpoint) diagnostics.checkpoint("接收方 TCP 建连记录", detail, error)
            else diagnostics.event("接收方 TCP 建连记录", detail, error)
        }
        diagnostics.checkpoint("接收方开始主动 TCP", "host=$host, port=$port, timeoutMs=$CONNECT_TIMEOUT_MS, bindNetwork=${network != null}")
        try {
            while (isCurrent(g, currentAttempt) && SystemClock.uptimeMillis() < until) {
                val s = Socket()
                try {
                    attempt++
                    if (network != null) network.bindSocket(s)
                    s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    retries.finish(SystemClock.uptimeMillis(), "已连接")
                    diagnostics.checkpoint("TCP 连接成功", "attempt=$attempt, local=${s.localAddress?.hostAddress}")
                    return s
                } catch (e: IOException) {
                    closeQuietly(s)
                    if (!isCurrent(g, currentAttempt)) return null
                    retries.failed(SystemClock.uptimeMillis(), e)
                    try {
                        Thread.sleep(CONNECT_RETRY_MS)
                    } catch (interrupted: InterruptedException) {
                        return null
                    }
                }
            }
        } finally {
            retries.finish(SystemClock.uptimeMillis(), if (SystemClock.uptimeMillis() >= until) "期限结束" else "本次尝试已结束")
        }
        return null
    }

    private fun fail(g: Int, currentAttempt: TapJoinAttempt, reason: TapFailure) {
        if (!isCurrent(g, currentAttempt) || !currentAttempt.finish()) {
            return
        }
        diagnostics.event("接收传输失败", "reason=$reason, generation=$g")
        joinTimeout?.let { main.removeCallbacks(it) }
        joinTimeout = null
        releaseWifiConnection()
        closeQuietly(socket)
        receiverGroupHost?.close("接收失败：$reason")
        channel?.let {
            cancelJoin(it)
            leaveGroup(it, joiningSsid, null)
        }
        joiningSsid = null
        listener?.onFailed(reason)
    }

    private fun cancelJoin(c: WifiP2pManager.Channel) {
        if (!awaitingGroup) {
            return
        }
        awaitingGroup = false
        try {
            diagnostics.event("取消 P2P 入组请求")
            manager?.cancelConnect(c, null)
        } catch (e: SecurityException) {
            diagnostics.failure("取消 P2P 入组失败", error = e)
        }
    }

    /** 离开这一次碰触加入的组 [ssid]；设备此刻在别的组里时什么也不做。完成后（无论拆没拆）执行 [then]。 */
    private fun leaveGroup(c: WifiP2pManager.Channel, ssid: String?, then: Runnable?) {
        val m = manager
        if (m == null || ssid == null) {
            then?.run()
            return
        }
        try {
            m.requestGroupInfo(c) { group ->
                diagnostics.event("退出前查询 P2P 组", "exists=${group != null}, matches=${group?.networkName == ssid}")
                if (group == null || group.networkName != ssid) {
                    then?.run()
                    return@requestGroupInfo
                }
                try {
                    diagnostics.event("请求 P2P 离组", "仅移除与本次握手匹配的组")
                    m.removeGroup(c, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            diagnostics.event("P2P 离组成功")
                            then?.run()
                        }

                        override fun onFailure(reason: Int) {
                            diagnostics.failure("P2P 离组失败", "reason=${TapDiagnostics.p2pReason(reason)}")
                            then?.run()
                        }
                    })
                } catch (e: SecurityException) {
                    then?.run()
                }
            }
        } catch (e: SecurityException) {
            then?.run()
        }
    }

    private companion object {
        const val TAG = "TapReceiver"

        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        const val PRESENCE_CHECK_MS = 250
        const val ISO_TIMEOUT_MS = 3000

        const val CONNECT_TIMEOUT_MS = 3000
        const val CONNECT_RETRY_MS = 250L

        /** 发送方最多等 120 秒写完记事文件，头部多留一点余量。 */
        const val HEADER_TIMEOUT_MS = 130_000
        const val SOCKET_TIMEOUT_MS = 15_000
        const val WIFI_FINISH_WAIT_MS = 2000
        const val PROGRESS_INTERVAL_MS = 40L
        const val BUFFER = 64 * 1024
        const val CHANNEL_CLOSE_FALLBACK_MS = 3000L

        fun closeQuietly(closeable: AutoCloseable?) {
            try {
                closeable?.close()
            } catch (ignored: Exception) {
            }
        }
    }
}
