package com.ywwynm.everythingdone.taptransfer.transport.wifi

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.ywwynm.everythingdone.taptransfer.TapTransfer
import com.ywwynm.everythingdone.taptransfer.protocol.TapApdu
import com.ywwynm.everythingdone.taptransfer.protocol.TapHandshake
import com.ywwynm.everythingdone.taptransfer.protocol.TapReaderExchange
import com.ywwynm.everythingdone.taptransfer.protocol.TapStream
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
 *
 * 读取模式只在前台生效，这也是接收状态「离开首页、锁屏即结束」的依据（决策 2026-09-24）。
 * 读取模式开着时本机的公交卡、门禁卡暂时不能用，所以只在用户显式进入接收状态时开。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class WifiDirectReceiverTransport(private val activity: Activity) :
    TapReceiverTransport, NfcAdapter.ReaderCallback {

    private val context: Context = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val nfc: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null

    private var listener: TapReceiverTransport.Listener? = null
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

    /** 这一次碰触要加入的组。离组时只拆这个组：设备可能同时在别的应用建的组里（投屏、打印）。 */
    private var joiningSsid: String? = null
    private var awaitingGroup = false

    /** 碰上之后必须在这个时刻（uptime）之前连上发送方，否则发送方那边已经判了超时。 */
    private var deadline = 0L
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
        this.listener = listener
        this.receiverName = receiverName
        SecureRandom().nextBytes(receiverId)
        generation++
        busy = false
        doneTokens.clear()
        channel = manager?.initialize(context, Looper.getMainLooper(), null)
        ContextCompat.registerReceiver(
            context, p2pReceiver,
            IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        val extras = Bundle()
        extras.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_MS)
        nfc?.enableReaderMode(activity, this, READER_FLAGS, extras)
    }

    override fun resume() {
        pending = null
        busy = false
    }

    override fun stop() {
        synchronized(ackLock) {
            generation++
        }
        main.removeCallbacksAndMessages(null)
        joinTimeout = null
        try {
            nfc?.disableReaderMode(activity)
        } catch (e: IllegalStateException) {
            // Activity 已经不在前台时系统会自己关掉读取模式
        }
        if (receiverRegistered) {
            context.unregisterReceiver(p2pReceiver)
            receiverRegistered = false
        }
        downloader?.interrupt()
        downloader = null
        closeQuietly(socket)
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
        if (busy) {
            return
        }
        val g = generation
        val iso = IsoDep.get(tag) ?: return
        try {
            iso.connect()
            iso.timeout = ISO_TIMEOUT_MS
            val exchange = TapReaderExchange { iso.transceive(it) }
            val outcome = exchange.readHandshake()
            if (outcome !is TapReaderExchange.Outcome.Offered) {
                return
            }
            val handshake = outcome.handshake
            if (doneTokens.contains(handshake.token.toHex())) {
                return
            }
            val sw = exchange.hello(receiverId, receiverName)
            if (sw != TapApdu.SW_OK) {
                Log.i(TAG, "发送方拒绝：${Integer.toHexString(sw)}")
                return
            }
            busy = true
            main.post {
                if (g == generation) {
                    onTouched(g, handshake)
                }
            }
        } catch (e: IOException) {
            // 贴得不稳、刚碰上就分开，或者对方给的握手不合规：这次碰触不算，接着等
            Log.i(TAG, "这次碰触没读完：${e.message}")
        } catch (e: RuntimeException) {
            // 碰上来的可以是任何设备。这里在 binder 线程上，漏出去的异常会让整个应用崩溃
            Log.w(TAG, "这次碰触出错", e)
        } finally {
            closeQuietly(iso)
        }
    }

    private fun onTouched(g: Int, handshake: TapHandshake) {
        pending = handshake
        deadline = SystemClock.uptimeMillis() + CONNECT_DEADLINE_MS
        listener?.onTouched(
            TapIncomingOffer(handshake.senderName, handshake.thingCount, handshake.colors)
        )
        join(g, handshake)
    }

    // ---------------------------------------------------------------- Wi-Fi Direct

    private fun join(g: Int, handshake: TapHandshake) {
        val m = manager
        val c = channel
        if (m == null || c == null) {
            fail(g, TapFailure.JOIN)
            return
        }
        val timeout = Runnable { fail(g, TapFailure.JOIN) }
        joinTimeout = timeout
        main.postDelayed(timeout, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L))
        awaitingGroup = true
        joiningSsid = handshake.ssid
        try {
            val config = WifiP2pConfig.Builder()
                .setNetworkName(handshake.ssid)
                .setPassphrase(handshake.passphrase)
                .build()
            m.connect(c, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    // 入组结果从 WIFI_P2P_CONNECTION_CHANGED_ACTION 来；已经在组里时不会再有广播，主动查一次
                    try {
                        m.requestConnectionInfo(c) { info -> onConnectionChanged(info) }
                    } catch (ignored: SecurityException) {
                    }
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "入组请求失败：$reason")
                    fail(g, TapFailure.JOIN)
                }
            })
        } catch (e: SecurityException) {
            fail(g, TapFailure.JOIN)
        } catch (e: IllegalArgumentException) {
            // 握手解码时已经按同样的规则校验过，这里只是兜底
            fail(g, TapFailure.JOIN)
        }
    }

    private fun onConnectionChanged(info: WifiP2pInfo?) {
        if (!awaitingGroup || info == null || !info.groupFormed || info.isGroupOwner) {
            return
        }
        val host = info.groupOwnerAddress?.hostAddress ?: return
        val handshake = pending ?: return
        awaitingGroup = false
        joinTimeout?.let { main.removeCallbacks(it) }
        joinTimeout = null
        download(generation, host, handshake)
    }

    private fun download(g: Int, host: String, handshake: TapHandshake) {
        // 确认发出之后，即使接收状态已经退出也要把文件交给它：发送方已经显示已发送了
        val receiver = listener ?: return
        val until = deadline
        val thread = Thread({
            val dst = File(TapTransfer.workDir(context, "in"), "recv-${System.currentTimeMillis()}.edthing")
            var s: Socket? = null
            var committed = false
            try {
                s = connectWithRetry(g, host, handshake.port, until) ?: run {
                    main.post { fail(g, TapFailure.CONNECT) }
                    return@Thread
                }
                socket = s
                // stop() 可能发生在上一行之前：那时它关不到这条连接，由 finally 关
                if (g != generation) {
                    return@Thread
                }
                s.tcpNoDelay = true
                val input = BufferedInputStream(s.getInputStream(), BUFFER)
                val output = BufferedOutputStream(s.getOutputStream())
                TapStream.writeClientHello(output, handshake.token, receiverId)
                // 附件大时发送方可能还在写记事文件：头部与发送方等文件的时限对齐，数据段恢复短超时
                s.soTimeout = HEADER_TIMEOUT_MS
                val total = TapStream.readHeader(input)
                s.soTimeout = SOCKET_TIMEOUT_MS
                main.post { if (g == generation) listener?.onConnected(total) }
                var lastPost = 0L
                FileOutputStream(dst).use { fos ->
                    TapStream.copyExactly(input, fos, total) { done ->
                        val now = SystemClock.uptimeMillis()
                        if (now - lastPost >= PROGRESS_INTERVAL_MS || done == total) {
                            lastPost = now
                            main.post { if (g == generation) listener?.onProgress(done, total) }
                        }
                    }
                }
                synchronized(ackLock) {
                    if (g != generation) {
                        throw IOException("接收状态已经退出")
                    }
                    TapStream.writeAck(output)
                    committed = true
                }
                doneTokens.add(handshake.token.toHex())
                main.post {
                    if (g == generation) {
                        channel?.let { leaveGroup(it, joiningSsid, null) }
                        joiningSsid = null
                    }
                    receiver.onReceived(dst)
                }
            } catch (e: IOException) {
                if (g == generation) {
                    Log.w(TAG, "接收中断", e)
                }
                main.post { fail(g, TapFailure.TRANSFER) }
            } finally {
                closeQuietly(s)
                if (socket === s) {
                    socket = null
                }
                if (!committed) {
                    // 传了一半的内容一律丢弃（决策 2026-09-24）
                    dst.delete()
                }
            }
        }, "tap-receive")
        downloader = thread
        thread.start()
    }

    /** 入组广播到来时 DHCP 未必已经分好地址，按验证程序的节奏短间隔重试，直到 [until]。 */
    private fun connectWithRetry(g: Int, host: String, port: Int, until: Long): Socket? {
        while (g == generation && SystemClock.uptimeMillis() < until) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                return s
            } catch (e: IOException) {
                closeQuietly(s)
                try {
                    Thread.sleep(CONNECT_RETRY_MS)
                } catch (interrupted: InterruptedException) {
                    return null
                }
            }
        }
        return null
    }

    private fun fail(g: Int, reason: TapFailure) {
        if (g != generation) {
            return
        }
        joinTimeout?.let { main.removeCallbacks(it) }
        joinTimeout = null
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
            manager?.cancelConnect(c, null)
        } catch (ignored: SecurityException) {
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
                if (group == null || group.networkName != ssid) {
                    then?.run()
                    return@requestGroupInfo
                }
                try {
                    m.removeGroup(c, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            then?.run()
                        }

                        override fun onFailure(reason: Int) {
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

        /**
         * 碰上到连上发送方的总时限（入组 + 建连接），比发送方的 25 秒短：发送方超时之后才连上来
         * 的连接会被拒绝，不如这边先判失败、让用户再碰一次。
         */
        const val CONNECT_DEADLINE_MS = 22_000L
        const val CONNECT_TIMEOUT_MS = 3000
        const val CONNECT_RETRY_MS = 250L

        /** 发送方最多等 120 秒写完记事文件，头部多留一点余量。 */
        const val HEADER_TIMEOUT_MS = 130_000
        const val SOCKET_TIMEOUT_MS = 15_000
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
