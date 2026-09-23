package com.ywwynm.nfcprobe;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * 应用内碰一碰的真机可行性验证。方案见 ../probe-plan.md。
 *
 * <p>发送端跑卡模拟并提前建好 Wi-Fi Direct 组，把 SSID、密码、令牌、待传字节数写进握手内容；
 * 接收端开读取模式，读到后按 SSID + 密码直接入组（{@link WifiP2pConfig.Builder} 这条路
 * 跳过发现与 GO 协商），再用 Socket 收数据。
 *
 * <p>结果全部显示在屏幕上，不写 logcat —— 魅族 20 Infinity 的 main 缓冲只有 256 KiB，
 * 跑一轮就被冲掉了。
 */
public class ProbeActivity extends Activity implements NfcAdapter.ReaderCallback, ProbeState.Sink {

    /** F0（专有 RID 前缀）+ ASCII "EDTRNS" */
    private static final byte[] AID = {(byte) 0xF0, 0x45, 0x44, 0x54, 0x52, 0x4E, 0x53};

    private static final int PORT = 8899;
    private static final int PERM_REQ = 1;
    private static final int BUF = 64 * 1024;

    private static final int ROLE_NONE = 0;
    private static final int ROLE_SENDER = 1;
    private static final int ROLE_RECEIVER = 2;
    private static final int ROLE_CAPACITY = 3;

    private static final int[] SIZES_MB = {1, 10, 100};

    private final StringBuilder mLines = new StringBuilder();
    private final SimpleDateFormat mStamp =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private TextView mLogView;
    private TextView mStatus;
    private ScrollView mScroll;
    private Button mBtnSize;

    private NfcAdapter mNfc;
    private WifiP2pManager mP2p;
    private WifiP2pManager.Channel mChannel;

    private volatile int mRole = ROLE_NONE;
    private int mSizeIdx = 0;
    private int mRound = 0;

    private Thread mServerThread;
    private volatile ServerSocket mServer;
    private volatile long mSendBytes;

    private volatile long mT0;
    private volatile long mT1;
    private volatile long mExpect;
    private volatile boolean mAwaitingGroup;

    private final BroadcastReceiver mP2pRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(i.getAction())) {
                return;
            }
            WifiP2pInfo info = extractInfo(i);
            if (info == null || !info.groupFormed || !mAwaitingGroup || info.isGroupOwner) {
                return;
            }
            mAwaitingGroup = false;
            String host = info.groupOwnerAddress == null
                    ? null : info.groupOwnerAddress.getHostAddress();
            if (host == null) {
                log("入组了，但拿不到 GO 地址");
                return;
            }
            long t2 = SystemClock.elapsedRealtime();
            log("T2 握手完成→入组 " + (t2 - mT1) + " ms，GO = " + host);
            receiveFrom(host, t2);
        }
    };

    @SuppressWarnings("deprecation")
    private static WifiP2pInfo extractInfo(Intent i) {
        return i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        mNfc = NfcAdapter.getDefaultAdapter(this);
        mP2p = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (mP2p != null) {
            mChannel = mP2p.initialize(this, getMainLooper(), null);
        }
        ProbeState.setSink(this);

        log("碰一碰可行性验证 · " + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）");
        if (mNfc == null) {
            log("！本机没有 NFC，这台做不了");
        } else if (!mNfc.isEnabled()) {
            log("！NFC 已关闭，请先到系统设置里打开");
        }
        if (mP2p == null || mChannel == null) {
            log("！拿不到 Wi-Fi Direct 服务");
        }
        log("用法：一台点「当发送端」，另一台点「当接收端」，再把两台背面贴上。");
        log("第一轮先量容量：一台仍点「当发送端」，另一台改点「测握手容量」再贴。");
        log("（容量测试也要对方处于发送端就绪状态，否则前台门控会挡住 SELECT。）");

        // 权限在打开时就申请。申请是异步的，留到点按钮时再申请等于白点一次。
        String p = wifiPermission();
        if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
            log("正在申请 Wi-Fi Direct 需要的权限…");
            requestPermissions(new String[]{p}, PERM_REQ);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mP2pRx, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mP2pRx, f);
        }
        if (mRole == ROLE_RECEIVER || mRole == ROLE_CAPACITY) {
            enableReader();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 读取模式绑定在前台 Activity 上，离开前台必须关掉，否则系统 NFC 一直被占着
        if (mNfc != null) {
            mNfc.disableReaderMode(this);
        }
        try {
            unregisterReceiver(mP2pRx);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ProbeState.setSink(null);
        stopAll();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == PERM_REQ) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            log(ok ? "权限已授予，再点一次开始" : "！权限被拒，Wi-Fi Direct 用不了");
        }
    }

    // ---------------------------------------------------------------- 界面

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // 背景与正文颜色一律交给主题（DayNight），不硬编码 —— 硬编码成浅色会被
        // 厂商 ROM 的强制深色整片反过来。只有状态条自己指定浅底，所以它固定黑字。
        final int pad = dp(12);
        // targetSdk 36 在 API 35+ 上强制 edge-to-edge：窗口内容从屏幕物理顶端开始，
        // 状态栏、导航栏、挖孔都压在上面。不自己让开就会被盖住。
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad);
            return WindowInsets.CONSUMED;
        });

        mStatus = new TextView(this);
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mStatus.setTextColor(Color.BLACK);
        mStatus.setGravity(Gravity.CENTER);
        mStatus.setPadding(0, dp(8), 0, dp(8));
        root.addView(mStatus);
        setStatus("未开始", 0xFFE0E0E0);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(button("当发送端", v -> startSender()), weighted());
        row1.addView(button("当接收端", v -> startReceiver(false)), weighted());
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        mBtnSize = button("大小 1 MB", v -> {
            mSizeIdx = (mSizeIdx + 1) % SIZES_MB.length;
            mBtnSize.setText("大小 " + SIZES_MB[mSizeIdx] + " MB");
        });
        row2.addView(mBtnSize, weighted());
        row2.addView(button("测握手容量", v -> startReceiver(true)), weighted());
        root.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.addView(button("停止", v -> {
            stopAll();
            log("已停止，NFC 与 Wi-Fi Direct 都已释放");
        }), weighted());
        row3.addView(button("复制", v -> copyAll()), weighted());
        row3.addView(button("清空", v -> {
            mLines.setLength(0);
            mRound = 0;
            mLogView.setText("");
        }), weighted());
        root.addView(row3);

        mLogView = new TextView(this);
        mLogView.setTypeface(Typeface.MONOSPACE);
        mLogView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mLogView.setTextIsSelectable(true);

        mScroll = new ScrollView(this);
        mScroll.addView(mLogView);
        root.addView(mScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        return b;
    }

    private static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void copyAll() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("probe", mLines.toString()));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onLine(String line) {
        log(line);
    }

    private void setStatus(final String text, final int bg) {
        runOnUiThread(() -> {
            mStatus.setText(text);
            mStatus.setBackgroundColor(bg);
        });
    }

    private void log(final String s) {
        runOnUiThread(() -> {
            mLines.append(mStamp.format(new Date())).append("  ").append(s).append('\n');
            mLogView.setText(mLines.toString());
            mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ---------------------------------------------------------------- 发送端

    private void startSender() {
        if (!ensurePermission()) {
            return;
        }
        stopAll();
        mRole = ROLE_SENDER;
        mSendBytes = SIZES_MB[mSizeIdx] * 1024L * 1024L;
        setStatus("发送端 · 建组中…", 0xFFFFF9C4);
        log("=== 发送端 · 待传 " + SIZES_MB[mSizeIdx] + " MB ===");
        // 先清掉可能残留的组；本来就没有组时 onFailure 也是正常的
        mP2p.removeGroup(mChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                createGroup();
            }

            @Override
            public void onFailure(int reason) {
                createGroup();
            }
        });
    }

    private void createGroup() {
        final long t = SystemClock.elapsedRealtime();
        try {
            mP2p.createGroup(mChannel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    log("建组成功（" + (SystemClock.elapsedRealtime() - t) + " ms）");
                    fetchGroupInfo(0);
                }

                @Override
                public void onFailure(int reason) {
                    log("！建组失败 " + reasonName(reason));
                }
            });
        } catch (SecurityException e) {
            log("！建组被拒：" + e.getMessage());
        }
    }

    private void fetchGroupInfo(final int tries) {
        try {
            mP2p.requestGroupInfo(mChannel, group -> {
                if (group == null || group.getNetworkName() == null
                        || group.getPassphrase() == null) {
                    if (tries < 25) {
                        mLogView.postDelayed(() -> fetchGroupInfo(tries + 1), 200);
                    } else {
                        log("！等了 5 秒仍拿不到组信息");
                    }
                    return;
                }
                armSender(group.getNetworkName(), group.getPassphrase());
            });
        } catch (SecurityException e) {
            log("！取组信息被拒：" + e.getMessage());
        }
    }

    private void armSender(String ssid, String passphrase) {
        String json;
        try {
            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("ssid", ssid);
            o.put("pw", passphrase);
            o.put("t", UUID.randomUUID().toString().substring(0, 8));
            o.put("n", mSendBytes);
            json = o.toString();
        } catch (JSONException e) {
            log("！握手内容构造失败");
            return;
        }
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        ProbeState.handshakeJson = json;
        ProbeState.senderArmed = true;

        log("SSID  " + ssid);
        log("密码  " + passphrase);
        log("握手内容 " + bytes + " B —— 与「测握手容量」量出的上限比一比，看要不要分段");
        startServer();
        setStatus("发送端就绪 · 等对方贴上来", 0xFFC8E6C9);
        log("已就绪。请在另一台点「当接收端」，再把两台背面贴上。");
    }

    private void startServer() {
        stopServer();
        mServerThread = new Thread(() -> {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PORT));
                mServer = ss;
                log("监听 " + PORT);
                while (!Thread.currentThread().isInterrupted()) {
                    serveOne(ss.accept());
                }
            } catch (IOException e) {
                if (mRole == ROLE_SENDER) {
                    log("监听结束：" + e.getMessage());
                }
            } finally {
                closeQuietly(ss);
                mServer = null;
            }
        }, "probe-server");
        mServerThread.start();
    }

    private void serveOne(Socket s) {
        long n = mSendBytes;
        log("对方已连上，开始发送 " + fmtBytes(n));
        long t = SystemClock.elapsedRealtime();
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(30000);
            byte[] buf = new byte[BUF];
            for (int i = 0; i < buf.length; i++) {
                buf[i] = (byte) (i & 0xFF);
            }
            OutputStream os = s.getOutputStream();
            long sent = 0;
            while (sent < n) {
                int k = (int) Math.min(buf.length, n - sent);
                os.write(buf, 0, k);
                sent += k;
            }
            os.flush();
            long written = SystemClock.elapsedRealtime() - t;
            s.shutdownOutput();
            // 写完只代表数据进了内核缓冲区，不代表对方收到了。等对方读完并关闭连接
            // （read 返回 -1）才是真的传完 —— 否则 1 MB 会报出 500 MB/s 这种假速率。
            //noinspection ResultOfMethodCallIgnored
            s.getInputStream().read();
            long dt = SystemClock.elapsedRealtime() - t;
            log("发送完成，" + dt + " ms（" + rate(n, dt) + "）；写入缓冲区用了 " + written + " ms");
        } catch (IOException e) {
            log("！发送中断：" + e.getMessage());
        } finally {
            closeQuietly(s);
        }
    }

    // ---------------------------------------------------------------- 接收端

    private void startReceiver(boolean capacityOnly) {
        if (!capacityOnly && !ensurePermission()) {
            return;
        }
        stopAll();
        mRole = capacityOnly ? ROLE_CAPACITY : ROLE_RECEIVER;
        setStatus(capacityOnly ? "容量测试 · 贴上即开始" : "接收端 · 贴上即开始",
                capacityOnly ? 0xFFFFF9C4 : 0xFFBBDEFB);
        log(capacityOnly ? "=== 握手容量测试 ===" : "=== 接收端 ===");
        enableReader();
    }

    private void enableReader() {
        if (mNfc == null) {
            return;
        }
        int flags = NfcAdapter.FLAG_READER_NFC_A
                | NfcAdapter.FLAG_READER_NFC_B
                | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;
        mNfc.enableReaderMode(this, this, flags, null);
        log("读取模式已开启。验证项 5：此刻拿本机去刷公交 / 门禁，应当刷不了。");
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        long t0 = SystemClock.elapsedRealtime();
        IsoDep iso = IsoDep.get(tag);
        if (iso == null) {
            log("碰到了，但不是 ISO-DEP —— 对方可能没在前台跑卡模拟");
            return;
        }
        try {
            iso.connect();
            iso.setTimeout(8000);
            log("--- 第 " + (++mRound) + " 轮 ---");
            log("ISO-DEP 已建立，maxTransceiveLength = " + iso.getMaxTransceiveLength() + " B");

            byte[] r = iso.transceive(selectApdu());
            if (!isOk(r)) {
                log("！SELECT 被拒 " + hex(r) + swMeaning(r));
                return;
            }
            if (mRole == ROLE_CAPACITY) {
                measureCapacity(iso);
                return;
            }
            byte[] hs = iso.transceive(new byte[]{(byte) 0x80, 0x02, 0x00, 0x00, 0x00});
            if (!isOk(hs)) {
                log("！取握手失败 " + hex(hs) + swMeaning(hs));
                return;
            }
            long t1 = SystemClock.elapsedRealtime();
            mT0 = t0;
            mT1 = t1;
            log("T1 贴上→握手完成 " + (t1 - t0) + " ms，握手 " + (hs.length - 2) + " B");
            joinGroup(new String(hs, 0, hs.length - 2, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log("！NFC 交换失败 " + e.getClass().getSimpleName() + "：" + e.getMessage());
        } finally {
            closeQuietly(iso);
        }
    }

    /** 验证项 1：单次 R-APDU 实际能返回多少字节。 */
    private void measureCapacity(IsoDep iso) {
        int[] steps = {128, 255, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 60000};
        int best = 0;
        for (int len : steps) {
            try {
                byte[] r = iso.transceive(new byte[]{
                        (byte) 0x80, 0x01, (byte) (len >> 8), (byte) len, 0x00});
                if (isOk(r) && r.length == len + 2) {
                    best = len;
                    log("  " + len + " B  成功");
                } else {
                    log("  " + len + " B  返回 " + (r == null ? 0 : r.length) + " B，到此为止");
                    break;
                }
            } catch (IOException e) {
                log("  " + len + " B  失败：" + e.getMessage());
                break;
            }
        }
        log("单次 R-APDU 上限 ≈ " + best + " B");
        log("握手内容约 120 B。装得下就不用分段，装不下协议要改。");
    }

    private void joinGroup(String json) {
        final String ssid;
        final String pw;
        try {
            JSONObject o = new JSONObject(json);
            ssid = o.getString("ssid");
            pw = o.getString("pw");
            mExpect = o.getLong("n");
        } catch (JSONException e) {
            log("！握手内容解析失败：" + json);
            return;
        }
        log("对方 SSID " + ssid + "，待收 " + fmtBytes(mExpect));
        runOnUiThread(() -> {
            try {
                WifiP2pConfig cfg = new WifiP2pConfig.Builder()
                        .setNetworkName(ssid)
                        .setPassphrase(pw)
                        .build();
                mAwaitingGroup = true;
                mP2p.connect(mChannel, cfg, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        log("入组请求已提交（没弹系统确认框就是好事）");
                    }

                    @Override
                    public void onFailure(int reason) {
                        mAwaitingGroup = false;
                        log("！入组请求失败 " + reasonName(reason));
                    }
                });
            } catch (SecurityException e) {
                mAwaitingGroup = false;
                log("！入组被拒：" + e.getMessage());
            }
        });
    }

    private void receiveFrom(final String host, final long t2) {
        new Thread(() -> {
            Socket sock = null;
            try {
                long c0 = SystemClock.elapsedRealtime();
                IOException last = null;
                // 入组广播到来时 DHCP 未必已经给我们分好地址，短间隔重试
                for (int i = 0; i < 24 && sock == null; i++) {
                    try {
                        Socket s = new Socket();
                        s.connect(new InetSocketAddress(host, PORT), 3000);
                        sock = s;
                    } catch (IOException e) {
                        last = e;
                        try {
                            Thread.sleep(250);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (sock == null) {
                    log("！连不上 " + host + ":" + PORT
                            + (last == null ? "" : " —— " + last.getMessage()));
                    return;
                }
                long c1 = SystemClock.elapsedRealtime();
                log("  socket 连上，用了 " + (c1 - c0) + " ms");

                InputStream is = sock.getInputStream();
                byte[] buf = new byte[BUF];
                long got = 0;
                int k;
                while ((k = is.read(buf)) > 0) {
                    got += k;
                }
                long t3 = SystemClock.elapsedRealtime();
                // 立刻回一个字节，发送端据此才能算出真实速率（而不是写进缓冲区的速率）
                try {
                    sock.getOutputStream().write(1);
                    sock.getOutputStream().flush();
                } catch (IOException ignored) {
                }
                log("T3 入组→收完 " + (t3 - t2) + " ms，收到 " + fmtBytes(got)
                        + "（" + rate(got, Math.max(1, t3 - c1) )+ "）");
                log("合计 贴上→收完 " + (t3 - mT0) + " ms");
                if (mExpect > 0 && got != mExpect) {
                    log("！收到的字节数与约定不符，约定 " + mExpect);
                }
            } catch (IOException e) {
                log("！接收失败：" + e.getMessage());
            } finally {
                closeQuietly(sock);
                // 离组，下一轮的 T2 才是真实的重连耗时
                runOnUiThread(() -> {
                    if (mP2p != null && mChannel != null) {
                        mP2p.removeGroup(mChannel, null);
                    }
                });
                log("已离组，可以碰下一轮");
            }
        }, "probe-client").start();
    }

    // ---------------------------------------------------------------- 收尾与工具

    private void stopAll() {
        if (mNfc != null) {
            mNfc.disableReaderMode(this);
        }
        ProbeState.disarm();
        stopServer();
        if (mP2p != null && mChannel != null) {
            mP2p.removeGroup(mChannel, null);
        }
        mAwaitingGroup = false;
        mRole = ROLE_NONE;
        if (mStatus != null) {
            setStatus("未开始", 0xFFE0E0E0);
        }
    }

    private void stopServer() {
        if (mServerThread != null) {
            mServerThread.interrupt();
            mServerThread = null;
        }
        closeQuietly(mServer);
        mServer = null;
    }

    private boolean ensurePermission() {
        if (mP2p == null || mChannel == null) {
            log("！没有 Wi-Fi Direct 服务");
            return false;
        }
        String p = wifiPermission();
        if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        requestPermissions(new String[]{p}, PERM_REQ);
        log("！权限还没给，授予之后请再点一次");
        return false;
    }

    private static String wifiPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    private static byte[] selectApdu() {
        byte[] apdu = new byte[6 + AID.length];
        apdu[0] = 0x00;
        apdu[1] = (byte) 0xA4;
        apdu[2] = 0x04;
        apdu[3] = 0x00;
        apdu[4] = (byte) AID.length;
        System.arraycopy(AID, 0, apdu, 5, AID.length);
        apdu[apdu.length - 1] = 0x00;
        return apdu;
    }

    private static boolean isOk(byte[] r) {
        return r != null && r.length >= 2
                && r[r.length - 2] == (byte) 0x90 && r[r.length - 1] == (byte) 0x00;
    }

    private static String swMeaning(byte[] r) {
        if (r == null || r.length < 2) {
            return "（没有响应）";
        }
        int sw = ((r[r.length - 2] & 0xFF) << 8) | (r[r.length - 1] & 0xFF);
        switch (sw) {
            case 0x6985:
                return "（对方还没按「当发送端」）";
            case 0x6A82:
                return "（找不到这个 AID —— 对方没装本程序，或被别的应用接走了）";
            case 0x6D00:
                return "（命令不支持）";
            default:
                return "";
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED:
                return "P2P_UNSUPPORTED（本机不支持 Wi-Fi Direct）";
            case WifiP2pManager.ERROR:
                return "ERROR（内部错误）";
            case WifiP2pManager.BUSY:
                return "BUSY（框架忙，稍后重试）";
            case WifiP2pManager.NO_SERVICE_REQUESTS:
                return "NO_SERVICE_REQUESTS";
            default:
                return "reason=" + reason;
        }
    }

    private static String hex(byte[] b) {
        if (b == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) {
            sb.append(String.format(Locale.US, "%02X ", x));
        }
        return sb.toString().trim();
    }

    private static String fmtBytes(long n) {
        if (n >= 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", n / 1048576.0);
        }
        if (n >= 1024) {
            return String.format(Locale.US, "%.1f KB", n / 1024.0);
        }
        return n + " B";
    }

    private static String rate(long bytes, long ms) {
        if (ms <= 0) {
            return "-";
        }
        return String.format(Locale.US, "%.2f MB/s", bytes / 1048576.0 / (ms / 1000.0));
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
