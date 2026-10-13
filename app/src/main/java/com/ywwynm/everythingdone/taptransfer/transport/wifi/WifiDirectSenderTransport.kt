package com.ywwynm.everythingdone.taptransfer.transport.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.taptransfer.diagnostics.TapDiagnostics
import com.ywwynm.everythingdone.taptransfer.diagnostics.TapConnectRetryLog
import com.ywwynm.everythingdone.taptransfer.diagnostics.TapP2pDiagnostics
import com.ywwynm.everythingdone.taptransfer.protocol.TapApdu
import com.ywwynm.everythingdone.taptransfer.protocol.TapCardResponder
import com.ywwynm.everythingdone.taptransfer.protocol.TapHandshake
import com.ywwynm.everythingdone.taptransfer.protocol.TapReceiverGroupOffer
import com.ywwynm.everythingdone.taptransfer.protocol.TapStream
import com.ywwynm.everythingdone.taptransfer.transport.TapFailure
import com.ywwynm.everythingdone.taptransfer.transport.TapSenderTransport
import com.ywwynm.everythingdone.taptransfer.transport.nfc.TapSenderGate
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections

/**
 * 真实的发送方：建 Wi-Fi Direct 组、开一个 TCP 端口，把 SSID、密码、端口与一次性令牌通过卡模拟
 * 交给碰上来的接收方，再把记事文件传过去。
 * 双方支持接收建组时，在同一次 NFC 碰触中接受接收组信息，退出本机组并主动连接接收方。
 *
 * 做法与 2026-09-21 的真机验证一致（`docs/features/thing-transfer/probe/`）：组信息要轮询才拿得到；
 * 等对方回确认字节才算发完。与验证程序不同的几处：
 *
 * - 端口由系统分配，随握手交给对方；
 * - 每条连接先核对令牌与接收方标识，只放行碰上来、说过 HELLO 的那一台（见 [TapStream]）；
 * - Wi-Fi Direct 组是整机共用的，投屏、打印等别的应用建的组不能拆。只拆自己建的组，
 *   包括上次进程被杀时没来得及拆的那个（保存 [KEY_GROUP_SSID]，核对名称与组主）；已有别人的组时这次不能碰。
 *   反过来，自己的组也会被系统拆掉（系统自己的碰一碰、互传、设备互联），拆掉之后通知界面重新准备。
 *
 * 一次只服务一台接收方。某一台收完之后，同一次分享里它再碰上来会被告知「已经收过」
 * （决策 2026-09-24「同一次碰触不会收两遍」）。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class WifiDirectSenderTransport(private val context: Context,
                                private val diagnostics: TapDiagnostics.Session = TapDiagnostics.NONE) : TapSenderTransport {

    private val main = Handler(Looper.getMainLooper())
    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null

    private var listener: TapSenderTransport.Listener? = null
    private var offer: TapSenderTransport.Offer? = null

    /** 每次 start / stop 加一；服务线程据此判断自己是否已经过时。写在主线程，读在各线程。 */
    @Volatile
    private var generation = 0

    private val token = ByteArray(TapHandshake.TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
    private val serverLock = Any()
    private var gateOffer: TapCardResponder.Offer? = null

    @Volatile
    private var server: ServerSocket? = null
    private var serverThread: Thread? = null

    /**
     * 正在服务的连接与服务它的线程。[stop] 时关掉连接、打断线程（可能正在等记事文件写完）：
     * 关闭分享界面就是取消这一次发送。
     */
    private val clients: MutableMap<Socket, Thread> = Collections.synchronizedMap(HashMap())

    /** 以下只在主线程写。[current] 还会被服务线程读。 */
    private var accepting = true
    private var createIssued = false
    private var ownedSsid: String? = null
    private var receiverGroupSsid: String? = null
    private var receiverGroupUsed = false
    private var reverseConnectIssued = false

    @Volatile
    private var current: TapSendPeer? = null
    private val served = HashSet<String>()
    private var connectTimeout: Runnable? = null

    /**
     * 可以被碰之后盯着自己的组：Wi-Fi Direct 是整机共用的，系统自己的碰一碰、互传、手表与平板的互联都会
     * 占用它，把这边的组拆掉（2026-09-26 Find X9 Ultra 上两次）。组没了还显示「可以碰」，对方读到握手之后
     * 入不了组，要等 22 秒才判失败。以下只在主线程读写。
     */
    private var armedSsid: String? = null
    private var groupSeen = false
    private var groupLost = false
    private var groupReceiverRegistered = false

    private val groupReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                return
            }
            @Suppress("DEPRECATION")
            val info: WifiP2pInfo? = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
            @Suppress("DEPRECATION")
            val group: WifiP2pGroup? = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
            onGroupChanged(info, group)
        }
    }

    override fun start(offer: TapSenderTransport.Offer, listener: TapSenderTransport.Listener) {
        diagnostics.hide(token.toHex())
        diagnostics.event("启动发送传输", "manager=${manager != null}, items=${offer.thingCount}")
        this.offer = offer
        this.listener = listener
        val g = ++generation
        val m = manager
        if (m == null) {
            diagnostics.failure("P2P 发送服务不可用")
            main.post { if (g == generation) listener.onUnavailable(TapFailure.GROUP) }
            return
        }
        val c = m.initialize(context, Looper.getMainLooper()) {
            // Wi-Fi Direct 框架断开（例如用户关了 Wi-Fi）：这次碰一碰用不了了
            if (g == generation) {
                diagnostics.failure("P2P 发送通道断开")
                this.listener?.onUnavailable(TapFailure.GROUP)
            } else diagnostics.event("已停止的 P2P 发送通道断开", "generation=$g")
        }
        channel = c
        diagnostics.event("P2P 发送通道初始化", "channel=${c != null}")
        TapP2pDiagnostics.capture(diagnostics, m, c, "发送通道初始化后")
        try {
            m.requestGroupInfo(c) { group ->
                if (g != generation) {
                    return@requestGroupInfo
                }
                diagnostics.event("P2P 初始组信息", "exists=${group != null}, ownedBefore=${isGroupCreated(context)}")
                when {
                    group == null -> createGroup(g)
                    group.isGroupOwner && group.networkName == context.getSharedPreferences(
                        TapReceiverGroupHost.PREFS, Context.MODE_PRIVATE).getString(TapReceiverGroupHost.KEY_SSID, null) ->
                        removeLeftoverThenCreate(g, group.networkName)
                    // 上一次进程被杀，自己建的组没来得及拆
                    group.isGroupOwner && group.networkName == ownedGroupName(context) ->
                        removeLeftoverThenCreate(g, group.networkName)
                    else -> {
                        diagnostics.failure("P2P 已被其它组占用")
                        Log.i(TAG, "设备正在别的 Wi-Fi Direct 组里，这次不能碰一碰")
                        unavailable(g)
                    }
                }
            }
        } catch (e: SecurityException) {
            diagnostics.failure("P2P 发送准备权限异常", error = e)
            Log.w(TAG, "没有权限操作 Wi-Fi Direct", e)
            unavailable(g)
        }
    }

    override fun setAccepting(accepting: Boolean) {
        this.accepting = accepting
        if (accepting && receiverGroupUsed && current == null) {
            val savedOffer = offer ?: return
            val savedListener = listener ?: return
            diagnostics.event("恢复发送方建组", "保留本次分享令牌和已接收设备记录")
            val nextGeneration = generation + 1
            savedListener.onPreparing()
            shutdown {
                if (generation == nextGeneration) start(savedOffer, savedListener)
            }
            return
        }
        if (accepting && groupLost && current == null) {
            // 组是在上一次传输途中没的：那一次已经按失败走完，现在才让界面重新准备
            val g = generation
            main.post { if (g == generation) listener?.onGroupLost() }
        }
    }

    override fun stop() = shutdown(null)

    private fun shutdown(then: (() -> Unit)?) {
        diagnostics.event("停止发送传输", "generation=$generation, peer=${current != null}")
        generation++
        unwatchGroup()
        gateOffer?.let { TapSenderGate.close(it) }
        gateOffer = null
        connectTimeout?.let { main.removeCallbacks(it) }
        connectTimeout = null
        current?.state?.set(TIMED_OUT)
        current = null
        synchronized(serverLock) {
            closeQuietly(server)
            server = null
        }
        serverThread?.interrupt()
        serverThread = null
        synchronized(clients) {
            for ((socket, thread) in clients) {
                closeQuietly(socket)
                thread.interrupt()
            }
            clients.clear()
        }
        main.removeCallbacksAndMessages(null)
        val m = manager
        val c = channel
        channel = null
        val names = setOfNotNull(ownedSsid, receiverGroupSsid)
        val unnamed = createIssued && ownedSsid == null
        val joined = reverseConnectIssued
        ownedSsid = null
        receiverGroupSsid = null
        receiverGroupUsed = false
        reverseConnectIssued = false
        createIssued = false
        listener = null
        if (m != null && c != null) {
            if (joined) runCatching { m.cancelConnect(c, null) }
            closeTapP2pChannel(m, c, main, diagnostics, names, unnamed) { ok ->
                if (ok) setGroupCreated(context, false)
                then?.invoke()
            }
        } else then?.invoke()
    }

    private fun removeLeftoverThenCreate(g: Int, expectedSsid: String) {
        diagnostics.event("移除本应用残留组")
        val m = manager ?: return
        val c = channel ?: return
        try {
            m.removeGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (g != generation) return
                    setGroupCreated(context, false)
                    createWhenVacant(g, expectedSsid, 0)
                }

                // 组已经被系统收掉时也会失败，照样去建
                override fun onFailure(reason: Int) {
                    diagnostics.event("移除残留组失败，继续尝试建组", "reason=${TapDiagnostics.p2pReason(reason)}")
                    createWhenVacant(g, expectedSsid, 0)
                }
            })
        } catch (e: SecurityException) {
            diagnostics.failure("移除残留组权限异常", error = e)
            unavailable(g)
        }
    }

    private fun createWhenVacant(g: Int, previousSsid: String, tries: Int) {
        if (g != generation) return
        val m = manager ?: return
        val c = channel ?: return
        try {
            m.requestGroupInfo(c) { group ->
                if (g != generation) return@requestGroupInfo
                if (group == null) createGroup(g)
                else if (group.isGroupOwner && group.networkName == previousSsid && tries < GROUP_INFO_TRIES)
                    main.postDelayed({ createWhenVacant(g, previousSsid, tries + 1) }, GROUP_INFO_INTERVAL_MS)
                else unavailable(g)
            }
        } catch (e: RuntimeException) { unavailable(g) }
    }

    private fun createGroup(g: Int) {
        if (g != generation) {
            return
        }
        val m = manager ?: return
        val c = channel ?: return
        // 先记下「自己建了组」再发请求：请求发出之后进程随时可能被杀，组却会留下来
        setGroupCreated(context, true)
        createIssued = true
        try {
            diagnostics.event("请求 P2P createGroup", "generation=$g")
            m.createGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    diagnostics.event("P2P createGroup 请求成功")
                    fetchGroupInfo(g, 0)
                }
                override fun onFailure(reason: Int) {
                    diagnostics.failure("P2P createGroup 失败", "reason=${TapDiagnostics.p2pReason(reason)}")
                    if (g != generation) return
                    if (g == generation) TapP2pDiagnostics.capture(diagnostics, m, c, "发送建组被拒绝后")
                    Log.w(TAG, "建组失败：$reason")
                    createIssued = false
                    setGroupCreated(context, false)
                    unavailable(g)
                }
            })
        } catch (e: SecurityException) {
            diagnostics.failure("P2P createGroup 权限异常", error = e)
            Log.w(TAG, "没有权限建组", e)
            createIssued = false
            setGroupCreated(context, false)
            unavailable(g)
        }
    }

    /** 建组成功后组信息要过一会儿才拿得到，按 200 ms 轮询，最多 5 秒（验证程序的做法）。 */
    private fun fetchGroupInfo(g: Int, tries: Int) {
        if (g != generation) {
            return
        }
        val m = manager ?: return
        val c = channel ?: return
        try {
            m.requestGroupInfo(c) { group ->
                if (g != generation) {
                    return@requestGroupInfo
                }
                val ssid = group?.networkName
                val passphrase = group?.passphrase
                diagnostics.event("查询发送组配置", "attempt=$tries, group=${group != null}, " +
                    "owner=${group?.isGroupOwner}, ssidLength=${ssid?.length}, passwordLength=${passphrase?.length}")
                if (ssid.isNullOrEmpty() || passphrase.isNullOrEmpty()) {
                    if (tries < GROUP_INFO_TRIES) {
                        main.postDelayed({ fetchGroupInfo(g, tries + 1) }, GROUP_INFO_INTERVAL_MS)
                    } else {
                        diagnostics.failure("P2P 组配置等待超时", "limitMs=${GROUP_INFO_TRIES * GROUP_INFO_INTERVAL_MS}")
                        unavailable(g)
                    }
                    return@requestGroupInfo
                }
                diagnostics.hide(ssid, passphrase)
                ownedSsid = ssid
                context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().putString(KEY_GROUP_SSID, ssid).apply()
                openServer(g, ssid, passphrase)
            }
        } catch (e: SecurityException) {
            diagnostics.failure("查询发送组配置权限异常", error = e)
            unavailable(g)
        }
    }

    private fun openServer(g: Int, ssid: String, passphrase: String) {
        val thread = Thread({
            diagnostics.event("开启 TCP 监听端口")
            val ss = try {
                ServerSocket(0)
            } catch (e: IOException) {
                diagnostics.failure("TCP 监听失败", error = e)
                Log.w(TAG, "开端口失败", e)
                main.post { unavailable(g) }
                return@Thread
            }
            synchronized(serverLock) {
                if (g == generation) server = ss else closeQuietly(ss)
            }
            diagnostics.event("TCP 已开始监听", "port=${ss.localPort}")
            // stop() 可能发生在上一行之前：那时它关不到这个端口，只能由这里自己关
            if (g != generation) {
                closeQuietly(ss)
                return@Thread
            }
            main.post {
                if (g == generation) {
                    armWithGroupAddress(g, ssid, passphrase, ss.localPort)
                }
            }
            while (!Thread.currentThread().isInterrupted) {
                val socket = try {
                    ss.accept()
                } catch (e: IOException) {
                    diagnostics.event("TCP 监听结束", "cancelled=${g != generation}, exception=${e.javaClass.name}")
                    break
                }
                diagnostics.event("TCP 收到连接", "peer=${socket.inetAddress?.hostAddress}")
                // 每条连接一条线程：连上来却不发数据的连接，不能卡住后面真正碰过的那一台
                Thread({ serve(g, socket) }, "tap-send-client").start()
            }
            closeQuietly(ss)
        }, "tap-send-server")
        serverThread = thread
        thread.start()
    }

    /** 调试兼容路径需要实际组主地址。查询失败或无回调仍保留原来可用的 P2P 握手。 */
    private fun armWithGroupAddress(g: Int, ssid: String, passphrase: String, port: Int) {
        val m = manager
        val c = channel
        if (!BuildConfig.DEBUG || m == null || c == null) {
            arm(g, ssid, passphrase, port, null)
            return
        }
        var completed = false
        fun finish(host: String?) {
            if (completed || g != generation) return
            completed = true
            val localIpv4 = host?.takeIf(TapHandshake::isLocalIpv4)
            diagnostics.event("发送组地址已核对", "localIpv4=$localIpv4, compatibilityAvailable=${localIpv4 != null}")
            arm(g, ssid, passphrase, port, localIpv4)
        }
        val timeout = Runnable {
            if (!completed && g == generation) {
                diagnostics.event("发送组地址查询尚无回调", "不提供普通 Wi-Fi 兼容地址")
                finish(null)
            }
        }
        main.postDelayed(timeout, 750)
        try {
            m.requestConnectionInfo(c) { info ->
                main.removeCallbacks(timeout)
                finish(info?.takeIf { it.groupFormed && it.isGroupOwner }?.groupOwnerAddress?.hostAddress)
            }
        } catch (e: RuntimeException) {
            main.removeCallbacks(timeout)
            diagnostics.event("发送组地址查询异常", error = e)
            finish(null)
        }
    }

    private fun arm(g: Int, ssid: String, passphrase: String, port: Int, groupOwnerIpv4: String?) {
        val o = offer ?: return
        val waitMs = if (BuildConfig.DEBUG && groupOwnerIpv4 != null) WIFI_CONNECTION_WAIT_MS
            else TapHandshake.DEFAULT_CONNECTION_WAIT_MS
        val handshake = try {
            val cards = minOf(o.aspects.size, TapHandshake.MAX_COLORS)
            TapHandshake(
                token, port, ssid, passphrase, o.senderName, o.thingCount,
                o.colors.copyOf(minOf(o.colors.size, TapHandshake.MAX_COLORS)),
                o.aspects.copyOf(cards), o.fullSpan.copyOf(cards), groupOwnerIpv4, waitMs
            ).encode()
        } catch (e: IllegalArgumentException) {
            diagnostics.failure("NFC 握手编码失败", error = e)
            // 系统给的组名或密码对方入不了组
            Log.w(TAG, "组信息不能用：${e.message}")
            unavailable(g)
            return
        }
        val gate = object : TapCardResponder.Offer {
            override val handshake: ByteArray = handshake
            override val supportsReceiverGroup = BuildConfig.DEBUG &&
                !TapGroupRolePolicy.isKnownCompatibilityEnvironment(Build.MANUFACTURER, Build.DISPLAY)
            override fun onHello(receiverId: ByteArray, receiverName: String): Int {
                return handleHello(g, receiverId, receiverName, waitMs)
            }
            override fun onReceiverGroup(offer: TapReceiverGroupOffer): Int {
                if (!MessageDigest.isEqual(offer.token, token)) return TapApdu.SW_NOT_READY
                diagnostics.hide(offer.ssid, offer.passphrase, offer.receiverId.toHex())
                return handleHello(g, offer.receiverId, offer.receiverName,
                    TapReceiverGroupOffer.CONNECTION_WAIT_MS, offer)
            }
        }
        gateOffer = gate
        TapSenderGate.open(gate, diagnostics)
        diagnostics.event("NFC 卡模拟已就绪", "handshakeBytes=${handshake.size}, port=$port, connectionWaitMs=$waitMs, " +
            "supportsReceiverGroup=${gate.supportsReceiverGroup}")
        TapP2pDiagnostics.capture(diagnostics, manager, channel, "发送组已就绪", ssid)
        watchGroup(ssid)
        listener?.onReady()
    }

    private fun watchGroup(ssid: String) {
        armedSsid = ssid
        groupSeen = false
        groupLost = false
        if (!groupReceiverRegistered) {
            // 连接状态是粘性广播：注册时立刻收到当前状态（组已经建好），据此记下「见过自己的组」
            ContextCompat.registerReceiver(
                context, groupReceiver,
                IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            groupReceiverRegistered = true
        }
    }

    private fun unwatchGroup() {
        armedSsid = null
        if (groupReceiverRegistered) {
            context.unregisterReceiver(groupReceiver)
            groupReceiverRegistered = false
        }
    }

    private fun onGroupChanged(info: WifiP2pInfo?, group: WifiP2pGroup?) {
        val ssid = armedSsid ?: return
        diagnostics.event("P2P 发送连接广播", "formed=${info?.groupFormed}, owner=${info?.isGroupOwner}, " +
            "host=${info?.groupOwnerAddress?.hostAddress}, group=${group != null}, matches=${group?.networkName == ssid}, " +
            "peerState=${current?.state?.get()}")
        // 没有 EXTRA_WIFI_P2P_GROUP（旧系统、权限不全）时只看自己是不是组主：同一时刻设备上只能有一个组
        val ours = info?.groupFormed == true && info.isGroupOwner && (group == null || group.networkName == ssid)
        if (ours) {
            groupSeen = true
            return
        }
        if (!groupSeen || groupLost) {
            return
        }
        groupLost = true
        diagnostics.failure("P2P 发送组丢失", "activePeer=${current != null}")
        Log.i(TAG, "自己建的组没了，现在的组：${group?.networkName}")
        // 已经不能被碰：摘掉握手，这时碰上来的会读到「未就绪」
        gateOffer?.let { TapSenderGate.close(it) }
        gateOffer = null
        val peer = current
        if (peer == null) {
            if (accepting) {
                listener?.onGroupLost()
            }
            return
        }
        // 对方读过握手、还没连上：入不了组了，不必等超时
        if (peer.state.compareAndSet(WAITING, TIMED_OUT)) {
            connectTimeout?.let { main.removeCallbacks(it) }
            connectTimeout = null
            current = null
            listener?.onFailed(TapFailure.JOIN)
        }
        // 已经连上的那一次：连接随组断开，由服务线程按传输中断报失败
    }

    /** 卡模拟服务在主线程回调，必须立刻给出状态字；界面更新投递出去再做。 */
    private fun handleHello(g: Int, receiverId: ByteArray, receiverName: String, waitMs: Int,
                            receiverGroup: TapReceiverGroupOffer? = null): Int {
        diagnostics.hide(receiverId.toHex())
        diagnostics.event("NFC 收到 HELLO", "generation=$g, accepting=$accepting, activePeer=${current != null}")
        if (g != generation) {
            return TapApdu.SW_NOT_READY
        }
        val idHex = receiverId.toHex()
        if (served.contains(idHex)) {
            diagnostics.event("NFC HELLO 拒绝", "alreadyReceived=true")
            return TapApdu.SW_ALREADY_RECEIVED
        }
        val c = current
        if (c != null) {
            // 同一台在传输途中又贴了一下：照常放行，它自己会忽略
            return if (c.idHex == idHex && ((c.receiverGroup == null && receiverGroup == null) ||
                (c.receiverGroup != null && receiverGroup != null &&
                    c.receiverGroup.encode().contentEquals(receiverGroup.encode())))) TapApdu.SW_OK else TapApdu.SW_BUSY
        }
        if (!accepting) {
            return TapApdu.SW_BUSY
        }
        val peer = TapSendPeer(receiverId.copyOf(), idHex, receiverName, receiverGroup,
            SystemClock.uptimeMillis() + TapReceiverGroupOffer.CONNECTION_WAIT_MS)
        current = peer
        diagnostics.checkpoint("NFC 已接受接收方", "等待 TCP，timeoutMs=$waitMs, receiverCreatesGroup=${receiverGroup != null}")
        main.post { if (g == generation) listener?.onPeerTouched(receiverName) }
        val timeout = Runnable {
            // 服务线程先把状态改成已连上的话，这里什么也不做
            if (g == generation && current === peer && peer.state.compareAndSet(WAITING, TIMED_OUT)) {
                diagnostics.failure(if (receiverGroup == null) "等待接收方 TCP 连接超时" else "主动连接接收方 TCP 超时",
                    "limitMs=$waitMs, connecting=${peer.connecting}")
                if (receiverGroup != null)
                    TapP2pDiagnostics.capture(diagnostics, manager, channel, "发送方主动 TCP 超时", receiverGroup.ssid)
                current = null
                listener?.onFailed(TapFailure.JOIN)
            }
        }
        connectTimeout = timeout
        main.postDelayed(timeout, waitMs.toLong())
        if (receiverGroup != null) {
            receiverGroupUsed = true
            main.post { switchToReceiverGroup(g, peer) }
        }
        return TapApdu.SW_OK
    }

    private fun waiting(g: Int, peer: TapSendPeer): Boolean =
        g == generation && current === peer && peer.state.get() == WAITING

    /** HELLO 应答发出之后才改组网；主动离组不能被当成意外断线。 */
    private fun switchToReceiverGroup(g: Int, peer: TapSendPeer) {
        if (!waiting(g, peer)) return
        val m = manager ?: return
        val c = channel ?: return
        unwatchGroup()
        groupLost = false
        gateOffer?.let { TapSenderGate.close(it) }
        gateOffer = null
        closeQuietly(server)
        server = null
        serverThread?.interrupt()
        synchronized(clients) { clients.keys.toList().forEach { closeQuietly(it) } }
        diagnostics.checkpoint("开始切换到接收方组", "先退出本机发送组")
        try {
            m.requestGroupInfo(c) { group ->
                if (!waiting(g, peer)) return@requestGroupInfo
                if (group == null) {
                    createIssued = false
                    setGroupCreated(context, false)
                    joinReceiverGroup(g, peer)
                } else if (group.isGroupOwner && group.networkName == ownedSsid) {
                    try {
                        m.removeGroup(c, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                if (!waiting(g, peer)) return
                                createIssued = false
                                setGroupCreated(context, false)
                                diagnostics.event("原发送组移除请求已受理", "继续查询，等待组实际退出")
                                awaitSenderGroupGone(g, peer)
                            }
                            override fun onFailure(reason: Int) {
                                diagnostics.failure("切换前释放发送组失败", "reason=${TapDiagnostics.p2pReason(reason)}")
                                failPeer(g, peer, TapFailure.JOIN)
                            }
                        })
                    } catch (e: RuntimeException) { failPeer(g, peer, TapFailure.JOIN) }
                } else {
                    diagnostics.failure("切换组网已中止", "当前组不属于本次发送")
                    failPeer(g, peer, TapFailure.JOIN)
                }
            }
        } catch (e: RuntimeException) {
            diagnostics.failure("释放发送组异常", error = e)
            failPeer(g, peer, TapFailure.JOIN)
        }
    }

    private fun awaitSenderGroupGone(g: Int, peer: TapSendPeer) {
        if (!waiting(g, peer)) return
        val m = manager ?: return
        val c = channel ?: return
        try {
            m.requestGroupInfo(c) { group ->
                if (!waiting(g, peer)) return@requestGroupInfo
                if (group == null) {
                    diagnostics.checkpoint("已确认退出原发送组")
                    joinReceiverGroup(g, peer)
                } else if (group.isGroupOwner && group.networkName == ownedSsid) {
                    main.postDelayed({ awaitSenderGroupGone(g, peer) }, 200)
                } else {
                    diagnostics.failure("等待退出发送组时出现其它组")
                    failPeer(g, peer, TapFailure.JOIN)
                }
            }
        } catch (e: RuntimeException) { failPeer(g, peer, TapFailure.JOIN) }
    }

    private fun joinReceiverGroup(g: Int, peer: TapSendPeer, retries: Int = 0) {
        if (!waiting(g, peer)) return
        val target = peer.receiverGroup ?: return
        val m = manager ?: return
        val c = channel ?: return
        receiverGroupSsid = target.ssid
        reverseConnectIssued = true
        try {
            val config = WifiP2pConfig.Builder().setNetworkName(target.ssid).setPassphrase(target.passphrase).build()
            diagnostics.event("发送方请求加入接收组", "ssidLength=${target.ssid.length}, port=${target.port}")
            m.connect(c, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!waiting(g, peer)) return
                    diagnostics.event("发送方入组请求已受理")
                    pollReceiverGroup(g, peer)
                }
                override fun onFailure(reason: Int) {
                    diagnostics.failure("发送方加入接收组被拒绝", "reason=${TapDiagnostics.p2pReason(reason)}")
                    if (!waiting(g, peer)) return
                    if (reason == WifiP2pManager.BUSY && retries < 6) {
                        diagnostics.event("组切换尚忙，稍后再次入组", "retry=${retries + 1}")
                        main.postDelayed({ joinReceiverGroup(g, peer, retries + 1) }, 250)
                        return
                    }
                    if (waiting(g, peer)) TapP2pDiagnostics.capture(diagnostics, m, c, "发送方入接收组失败", target.ssid)
                    failPeer(g, peer, TapFailure.JOIN)
                }
            })
        } catch (e: RuntimeException) {
            diagnostics.failure("发送方加入接收组异常", error = e)
            failPeer(g, peer, TapFailure.JOIN)
        }
    }

    private fun pollReceiverGroup(g: Int, peer: TapSendPeer) {
        if (!waiting(g, peer) || peer.connecting) return
        val target = peer.receiverGroup ?: return
        val m = manager ?: return
        val c = channel ?: return
        fun again() { main.postDelayed({ pollReceiverGroup(g, peer) }, 200) }
        try {
            m.requestGroupInfo(c) { group ->
                if (!waiting(g, peer) || peer.connecting) return@requestGroupInfo
                if (group == null || group.isGroupOwner || group.networkName != target.ssid) {
                    again(); return@requestGroupInfo
                }
                try {
                    m.requestConnectionInfo(c) { info ->
                        if (!waiting(g, peer) || peer.connecting) return@requestConnectionInfo
                        val host = info?.groupOwnerAddress?.hostAddress
                        if (info?.groupFormed != true || info.isGroupOwner || host == null || !TapHandshake.isLocalIpv4(host)) {
                            again(); return@requestConnectionInfo
                        }
                        peer.connecting = true
                        diagnostics.checkpoint("发送方已加入接收组", "host=$host, port=${target.port}")
                        TapP2pDiagnostics.capture(diagnostics, m, c, "发送方主动 TCP 前", target.ssid)
                        connectReceiver(g, peer, host)
                    }
                } catch (e: RuntimeException) { failPeer(g, peer, TapFailure.JOIN) }
            }
        } catch (e: RuntimeException) { failPeer(g, peer, TapFailure.JOIN) }
    }

    private fun connectReceiver(g: Int, peer: TapSendPeer, host: String) {
        val target = peer.receiverGroup ?: return
        Thread({
            val retries = TapConnectRetryLog(SystemClock.uptimeMillis(), diagnostics.enabled) { detail, error, checkpoint ->
                if (checkpoint) diagnostics.checkpoint("发送方主动 TCP 建连记录", detail, error)
                else diagnostics.event("发送方主动 TCP 建连记录", detail, error)
            }
            try {
                while (waiting(g, peer) && SystemClock.uptimeMillis() < peer.deadlineMs) {
                    val socket = Socket()
                    clients[socket] = Thread.currentThread()
                    var connected = false
                    try {
                        if (!waiting(g, peer)) return@Thread
                        socket.connect(InetSocketAddress(host, target.port), 3000)
                        connected = true
                        if (!waiting(g, peer)) return@Thread
                        retries.finish(SystemClock.uptimeMillis(), "已连接")
                        diagnostics.checkpoint("发送方主动 TCP 连接成功", "local=${socket.localAddress?.hostAddress}, host=$host, port=${target.port}")
                        TapStream.writeSenderHello(socket.getOutputStream(), token, peer.id)
                        serve(g, socket, peer)
                        return@Thread
                    } catch (e: IOException) {
                        if (!waiting(g, peer)) return@Thread
                        if (connected) {
                            diagnostics.failure("发送方主动 TCP 身份写入失败", error = e)
                            main.post { failPeer(g, peer, TapFailure.CONNECT) }
                            return@Thread
                        }
                        if (retries.failed(SystemClock.uptimeMillis(), e)) main.post {
                            if (waiting(g, peer))
                                TapP2pDiagnostics.capture(diagnostics, manager, channel, "发送方第一次主动 TCP 失败", target.ssid)
                        }
                    } finally {
                        clients.remove(socket)
                        closeQuietly(socket)
                    }
                    try { Thread.sleep(250) } catch (e: InterruptedException) { return@Thread }
                }
                main.post { failPeer(g, peer, TapFailure.CONNECT) }
            } finally {
                retries.finish(SystemClock.uptimeMillis(), if (SystemClock.uptimeMillis() >= peer.deadlineMs) "期限结束" else "本次尝试已结束")
            }
        }, "tap-send-connect").start()
    }

    /** 在这条连接自己的线程上服务它。 */
    private fun serve(g: Int, socket: Socket, expectedPeer: TapSendPeer? = null) {
        clients[socket] = Thread.currentThread()
        var claimed: TapSendPeer? = null
        var stage = "读取身份"
        try {
            if (g != generation) {
                return
            }
            socket.tcpNoDelay = true
            socket.soTimeout = CLIENT_HELLO_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream(), BUFFER)
            val hello = TapStream.readClientHello(input)
            diagnostics.event("TCP 收到身份信息")
            if (!MessageDigest.isEqual(hello.token, token)) {
                diagnostics.failure("TCP 身份校验失败", "tokenMismatch=true")
                TapStream.writeHeader(output, TapStream.STATUS_BAD_TOKEN, 0L)
                return
            }
            val peer = current
            if (peer == null || !peer.claim(hello.receiverId, expectedPeer)) {
                // 没说过 HELLO、已经超时，或者这次 HELLO 已经连上过一条
                TapStream.writeHeader(output, TapStream.STATUS_NOT_EXPECTED, 0L)
                diagnostics.failure("TCP 连接不符合当前会话", "activePeer=${peer != null}, state=${peer?.state?.get()}")
                return
            }
            claimed = peer
            stage = "等待记事文件"
            diagnostics.event("TCP 身份校验通过", "等待文件，timeoutMs=$PAYLOAD_TIMEOUT_MS")
            main.post {
                if (g == generation && current === peer) {
                    connectTimeout?.let { main.removeCallbacks(it) }
                    connectTimeout = null
                    listener?.onPeerConnected()
                }
            }
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val file = offer?.payload?.await(PAYLOAD_TIMEOUT_MS)
            if (file == null) {
                diagnostics.failure("TCP 无可发送文件")
                TapStream.writeHeader(output, TapStream.STATUS_NO_PAYLOAD, 0L)
                main.post { failPeer(g, peer, TapFailure.FILE) }
                return
            }
            val total = file.length()
            stage = "发送文件"
            diagnostics.event("TCP 开始发送文件", "bytes=$total")
            TapStream.writeHeader(output, TapStream.STATUS_OK, total)
            var lastPost = 0L
            var diagnosticProgress = 0L
            FileInputStream(file).use { fis ->
                TapStream.copyExactly(fis, output, total) { done ->
                    val now = SystemClock.uptimeMillis()
                    if (now - diagnosticProgress >= 1000 || done == total) {
                        diagnosticProgress = now
                        diagnostics.event("发送字节进度", "$done/$total")
                    }
                    if (now - lastPost >= PROGRESS_INTERVAL_MS || done == total) {
                        lastPost = now
                        main.post { if (g == generation && current === peer) listener?.onProgress(done, total) }
                    }
                }
            }
            diagnostics.event("文件字节已发送", "等待 ACK，timeoutMs=$SOCKET_TIMEOUT_MS")
            stage = "等待接收 ACK"
            TapStream.readAck(input)
            stage = "已收到接收 ACK"
            diagnostics.event("TCP 发送完成", "已收到 ACK")
            main.post {
                if (g == generation && current === peer) {
                    current = null
                    served.add(peer.idHex)
                    listener?.onSent(peer.name)
                }
            }
        } catch (e: IOException) {
            diagnostics.failure("TCP 发送连接异常", "stage=$stage, claimed=${claimed != null}, cancelled=${g != generation}", e)
            val peer = claimed
            if (peer != null) {
                Log.w(TAG, "发送中断", e)
                main.post { failPeer(g, peer, TapFailure.TRANSFER) }
            } else {
                Log.i(TAG, "拒绝了一条连接：${e.message}")
                if (expectedPeer != null) main.post { failPeer(g, expectedPeer, TapFailure.CONNECT) }
            }
        } finally {
            diagnostics.event("关闭发送 TCP", "stage=$stage, cancelled=${g != generation}")
            clients.remove(socket)
            closeQuietly(socket)
        }
    }

    private fun failPeer(g: Int, peer: TapSendPeer, reason: TapFailure) {
        if (g != generation || current !== peer) {
            return
        }
        diagnostics.event("发送传输失败", "reason=$reason")
        connectTimeout?.let { main.removeCallbacks(it) }
        connectTimeout = null
        peer.state.set(TIMED_OUT)
        current = null
        listener?.onFailed(reason)
    }

    private fun unavailable(g: Int) {
        if (g == generation) {
            listener?.onUnavailable(TapFailure.GROUP)
        }
    }

    private companion object {
        const val TAG = "TapSender"
        const val GROUP_INFO_TRIES = 25
        const val GROUP_INFO_INTERVAL_MS = 200L

        const val WAITING = TapSendPeer.WAITING
        const val TIMED_OUT = TapSendPeer.FINISHED

        /**
         * 调试版兼容连接可能需要系统查找设备并等待人工确认，声明并保留一分钟。
         * 接收方只有切换到兼容连接时才延长期限，并比发送方短 3 秒（见 `TapJoinAttempt`）。
         */
        const val WIFI_CONNECTION_WAIT_MS = 60_000

        /** 真正的接收方连上就报身份，读不到的连接很快放掉。 */
        const val CLIENT_HELLO_TIMEOUT_MS = 5_000
        const val SOCKET_TIMEOUT_MS = 15_000
        const val PAYLOAD_TIMEOUT_MS = 120_000L
        const val PROGRESS_INTERVAL_MS = 40L
        const val BUFFER = 64 * 1024

        private const val STATE_PREFS = "tap_transfer_state"

        /** 本应用建了 Wi-Fi Direct 组、还没确认拆掉。 */
        private const val KEY_GROUP_CREATED = "group_created"
        private const val KEY_GROUP_SSID = "owned_ssid"

        fun ownedGroupName(context: Context): String? =
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getString(KEY_GROUP_SSID, null)

        fun isGroupCreated(context: Context): Boolean =
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GROUP_CREATED, false)

        fun setGroupCreated(context: Context, created: Boolean) {
            val edit = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_GROUP_CREATED, created)
            if (!created) edit.remove(KEY_GROUP_SSID)
            edit.apply()
        }

        fun closeQuietly(closeable: AutoCloseable?) {
            try {
                closeable?.close()
            } catch (ignored: Exception) {
            }
        }
    }
}

internal fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return sb.toString()
}

private val HEX = "0123456789abcdef".toCharArray()
