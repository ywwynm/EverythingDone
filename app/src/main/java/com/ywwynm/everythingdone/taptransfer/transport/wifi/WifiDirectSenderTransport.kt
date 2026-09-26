package com.ywwynm.everythingdone.taptransfer.transport.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.ywwynm.everythingdone.taptransfer.protocol.TapApdu
import com.ywwynm.everythingdone.taptransfer.protocol.TapCardResponder
import com.ywwynm.everythingdone.taptransfer.protocol.TapHandshake
import com.ywwynm.everythingdone.taptransfer.protocol.TapStream
import com.ywwynm.everythingdone.taptransfer.transport.TapFailure
import com.ywwynm.everythingdone.taptransfer.transport.TapSenderTransport
import com.ywwynm.everythingdone.taptransfer.transport.nfc.TapSenderGate
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 真实的发送方：建 Wi-Fi Direct 组、开一个 TCP 端口，把 SSID、密码、端口与一次性令牌通过卡模拟
 * 交给碰上来的接收方，再把记事文件传过去。
 *
 * 做法与 2026-09-21 的真机验证一致（`docs/features/thing-transfer/probe/`）：组信息要轮询才拿得到；
 * 等对方回确认字节才算发完。与验证程序不同的几处：
 *
 * - 端口由系统分配，随握手交给对方；
 * - 每条连接先核对令牌与接收方标识，只放行碰上来、说过 HELLO 的那一台（见 [TapStream]）；
 * - Wi-Fi Direct 组是整机共用的，投屏、打印等别的应用建的组不能拆。只拆自己建的组，
 *   包括上次进程被杀时没来得及拆的那个（靠 [KEY_GROUP_CREATED] 记着）；已有别人的组时这次不能碰。
 *   反过来，自己的组也会被系统拆掉（系统自己的碰一碰、互传、设备互联），拆掉之后通知界面重新准备。
 *
 * 一次只服务一台接收方。某一台收完之后，同一次分享里它再碰上来会被告知「已经收过」
 * （决策 2026-09-24「同一次碰触不会收两遍」）。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class WifiDirectSenderTransport(private val context: Context) : TapSenderTransport {

    /**
     * 碰上来、说过 HELLO 的那一台。[state] 由服务线程（连上了）与主线程（超时了）争用，
     * 用比较并交换决出唯一的结果：连上与超时不会同时生效。
     */
    private class Peer(val id: ByteArray, val idHex: String, val name: String) {
        val state = AtomicInteger(WAITING)
    }

    private val main = Handler(Looper.getMainLooper())
    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null

    private var listener: TapSenderTransport.Listener? = null
    private var offer: TapSenderTransport.Offer? = null

    /** 每次 start / stop 加一；服务线程据此判断自己是否已经过时。写在主线程，读在各线程。 */
    @Volatile
    private var generation = 0

    private val token = ByteArray(TapHandshake.TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
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

    @Volatile
    private var current: Peer? = null
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
        this.offer = offer
        this.listener = listener
        val g = ++generation
        val m = manager
        if (m == null) {
            main.post { if (g == generation) listener.onUnavailable(TapFailure.GROUP) }
            return
        }
        val c = m.initialize(context, Looper.getMainLooper()) {
            // Wi-Fi Direct 框架断开（例如用户关了 Wi-Fi）：这次碰一碰用不了了
            if (g == generation) {
                this.listener?.onUnavailable(TapFailure.GROUP)
            }
        }
        channel = c
        try {
            m.requestGroupInfo(c) { group ->
                if (g != generation) {
                    return@requestGroupInfo
                }
                when {
                    group == null -> createGroup(g)
                    // 上一次进程被杀，自己建的组没来得及拆
                    isGroupCreated(context) -> removeLeftoverThenCreate(g)
                    else -> {
                        Log.i(TAG, "设备正在别的 Wi-Fi Direct 组里，这次不能碰一碰")
                        unavailable(g)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "没有权限操作 Wi-Fi Direct", e)
            unavailable(g)
        }
    }

    override fun setAccepting(accepting: Boolean) {
        this.accepting = accepting
        if (accepting && groupLost && current == null) {
            // 组是在上一次传输途中没的：那一次已经按失败走完，现在才让界面重新准备
            val g = generation
            main.post { if (g == generation) listener?.onGroupLost() }
        }
    }

    override fun stop() {
        generation++
        unwatchGroup()
        gateOffer?.let { TapSenderGate.close(it) }
        gateOffer = null
        connectTimeout?.let { main.removeCallbacks(it) }
        connectTimeout = null
        current = null
        closeQuietly(server)
        server = null
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
        if (m != null && c != null) {
            if (createIssued) {
                removeOwnGroup(m, c)
            } else {
                // 没建过组：此刻设备上的组（如果有）是别人的，不能拆
                c.close()
            }
        }
        createIssued = false
        listener = null
    }

    /** 拆掉自己建的组，拆完再关通道：通道一关，回调就收不到了。 */
    private fun removeOwnGroup(m: WifiP2pManager, c: WifiP2pManager.Channel) {
        try {
            m.removeGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    setGroupCreated(context, false)
                    c.close()
                }

                override fun onFailure(reason: Int) {
                    // 建组的请求可能还没完成：组稍后出现时标记还在，下次打开分享界面会当作残留拆掉
                    c.close()
                }
            })
        } catch (e: SecurityException) {
            c.close()
        }
    }

    private fun removeLeftoverThenCreate(g: Int) {
        val m = manager ?: return
        val c = channel ?: return
        try {
            m.removeGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    setGroupCreated(context, false)
                    createGroup(g)
                }

                // 组已经被系统收掉时也会失败，照样去建
                override fun onFailure(reason: Int) = createGroup(g)
            })
        } catch (e: SecurityException) {
            unavailable(g)
        }
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
            m.createGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = fetchGroupInfo(g, 0)
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "建组失败：$reason")
                    setGroupCreated(context, false)
                    unavailable(g)
                }
            })
        } catch (e: SecurityException) {
            Log.w(TAG, "没有权限建组", e)
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
                if (ssid.isNullOrEmpty() || passphrase.isNullOrEmpty()) {
                    if (tries < GROUP_INFO_TRIES) {
                        main.postDelayed({ fetchGroupInfo(g, tries + 1) }, GROUP_INFO_INTERVAL_MS)
                    } else {
                        unavailable(g)
                    }
                    return@requestGroupInfo
                }
                openServer(g, ssid, passphrase)
            }
        } catch (e: SecurityException) {
            unavailable(g)
        }
    }

    private fun openServer(g: Int, ssid: String, passphrase: String) {
        val thread = Thread({
            val ss = try {
                ServerSocket(0)
            } catch (e: IOException) {
                Log.w(TAG, "开端口失败", e)
                main.post { unavailable(g) }
                return@Thread
            }
            server = ss
            // stop() 可能发生在上一行之前：那时它关不到这个端口，只能由这里自己关
            if (g != generation) {
                closeQuietly(ss)
                return@Thread
            }
            main.post {
                if (g == generation) {
                    arm(g, ssid, passphrase, ss.localPort)
                }
            }
            while (!Thread.currentThread().isInterrupted) {
                val socket = try {
                    ss.accept()
                } catch (e: IOException) {
                    break
                }
                // 每条连接一条线程：连上来却不发数据的连接，不能卡住后面真正碰过的那一台
                Thread({ serve(g, socket) }, "tap-send-client").start()
            }
            closeQuietly(ss)
        }, "tap-send-server")
        serverThread = thread
        thread.start()
    }

    private fun arm(g: Int, ssid: String, passphrase: String, port: Int) {
        val o = offer ?: return
        val handshake = try {
            val cards = minOf(o.aspects.size, TapHandshake.MAX_COLORS)
            TapHandshake(
                token, port, ssid, passphrase, o.senderName, o.thingCount,
                o.colors.copyOf(minOf(o.colors.size, TapHandshake.MAX_COLORS)),
                o.aspects.copyOf(cards), o.fullSpan.copyOf(cards)
            ).encode()
        } catch (e: IllegalArgumentException) {
            // 系统给的组名或密码对方入不了组
            Log.w(TAG, "组信息不能用：${e.message}")
            unavailable(g)
            return
        }
        val gate = object : TapCardResponder.Offer {
            override val handshake: ByteArray = handshake
            override fun onHello(receiverId: ByteArray, receiverName: String): Int {
                return handleHello(g, receiverId, receiverName)
            }
        }
        gateOffer = gate
        TapSenderGate.open(gate)
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
    private fun handleHello(g: Int, receiverId: ByteArray, receiverName: String): Int {
        if (g != generation) {
            return TapApdu.SW_NOT_READY
        }
        val idHex = receiverId.toHex()
        if (served.contains(idHex)) {
            return TapApdu.SW_ALREADY_RECEIVED
        }
        val c = current
        if (c != null) {
            // 同一台在传输途中又贴了一下：照常放行，它自己会忽略
            return if (c.idHex == idHex) TapApdu.SW_OK else TapApdu.SW_BUSY
        }
        if (!accepting) {
            return TapApdu.SW_BUSY
        }
        val peer = Peer(receiverId.copyOf(), idHex, receiverName)
        current = peer
        main.post { if (g == generation) listener?.onPeerTouched(receiverName) }
        val timeout = Runnable {
            // 服务线程先把状态改成已连上的话，这里什么也不做
            if (g == generation && current === peer && peer.state.compareAndSet(WAITING, TIMED_OUT)) {
                current = null
                listener?.onFailed(TapFailure.JOIN)
            }
        }
        connectTimeout = timeout
        main.postDelayed(timeout, CONNECT_TIMEOUT_MS)
        return TapApdu.SW_OK
    }

    /** 在这条连接自己的线程上服务它。 */
    private fun serve(g: Int, socket: Socket) {
        clients[socket] = Thread.currentThread()
        var claimed: Peer? = null
        try {
            if (g != generation) {
                return
            }
            socket.tcpNoDelay = true
            socket.soTimeout = CLIENT_HELLO_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream(), BUFFER)
            val hello = TapStream.readClientHello(input)
            if (!MessageDigest.isEqual(hello.token, token)) {
                TapStream.writeHeader(output, TapStream.STATUS_BAD_TOKEN, 0L)
                return
            }
            val peer = current
            if (peer == null || !MessageDigest.isEqual(hello.receiverId, peer.id) ||
                !peer.state.compareAndSet(WAITING, CONNECTED)
            ) {
                // 没说过 HELLO、已经超时，或者这次 HELLO 已经连上过一条
                TapStream.writeHeader(output, TapStream.STATUS_NOT_EXPECTED, 0L)
                return
            }
            claimed = peer
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
                TapStream.writeHeader(output, TapStream.STATUS_NO_PAYLOAD, 0L)
                main.post { failPeer(g, peer, TapFailure.FILE) }
                return
            }
            val total = file.length()
            TapStream.writeHeader(output, TapStream.STATUS_OK, total)
            var lastPost = 0L
            FileInputStream(file).use { fis ->
                TapStream.copyExactly(fis, output, total) { done ->
                    val now = SystemClock.uptimeMillis()
                    if (now - lastPost >= PROGRESS_INTERVAL_MS || done == total) {
                        lastPost = now
                        main.post { if (g == generation && current === peer) listener?.onProgress(done, total) }
                    }
                }
            }
            TapStream.readAck(input)
            main.post {
                if (g == generation && current === peer) {
                    current = null
                    served.add(peer.idHex)
                    listener?.onSent(peer.name)
                }
            }
        } catch (e: IOException) {
            val peer = claimed
            if (peer != null) {
                Log.w(TAG, "发送中断", e)
                main.post { failPeer(g, peer, TapFailure.TRANSFER) }
            } else {
                Log.i(TAG, "拒绝了一条连接：${e.message}")
            }
        } finally {
            clients.remove(socket)
            closeQuietly(socket)
        }
    }

    private fun failPeer(g: Int, peer: Peer, reason: TapFailure) {
        if (g != generation || current !== peer) {
            return
        }
        connectTimeout?.let { main.removeCallbacks(it) }
        connectTimeout = null
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

        const val WAITING = 0
        const val CONNECTED = 1
        const val TIMED_OUT = 2

        /**
         * 碰上之后多久没连上就算失败：实测入组最慢 3 秒，socket 重试最多 6 秒，留足余量。
         * 接收方自己的期限比这短（`WifiDirectReceiverTransport.CONNECT_DEADLINE_MS`），不会在这边
         * 已经判失败之后才连上来。
         */
        const val CONNECT_TIMEOUT_MS = 25_000L

        /** 真正的接收方连上就报身份，读不到的连接很快放掉。 */
        const val CLIENT_HELLO_TIMEOUT_MS = 5_000
        const val SOCKET_TIMEOUT_MS = 15_000
        const val PAYLOAD_TIMEOUT_MS = 120_000L
        const val PROGRESS_INTERVAL_MS = 40L
        const val BUFFER = 64 * 1024

        private const val STATE_PREFS = "tap_transfer_state"

        /** 本应用建了 Wi-Fi Direct 组、还没确认拆掉。 */
        private const val KEY_GROUP_CREATED = "group_created"

        fun isGroupCreated(context: Context): Boolean =
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GROUP_CREATED, false)

        fun setGroupCreated(context: Context, created: Boolean) {
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_GROUP_CREATED, created).apply()
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
