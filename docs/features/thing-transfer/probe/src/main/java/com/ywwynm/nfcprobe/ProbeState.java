package com.ywwynm.nfcprobe;

import android.os.Handler;
import android.os.Looper;

/**
 * 发送端的就绪状态与握手内容，供卡模拟服务读取；以及把卡模拟侧的事件送回界面。
 *
 * <p>卡模拟服务是后台服务，系统随时可能因为一次触碰把它拉起来。{@link #senderArmed}
 * 就是验证方案里说的「前台状态门控」：只有用户在界面上明确按了「当发送端」才为 true，
 * 否则任何人拿读卡设备碰一下都能读走握手内容。
 */
public final class ProbeState {

    public interface Sink {
        void onLine(String line);
    }

    public static volatile boolean senderArmed = false;

    /** Wi-Fi Direct 的 SSID、密码、一次性令牌与待传字节数，JSON 形式。 */
    public static volatile String handshakeJson = null;

    private static volatile Sink sSink = null;
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private ProbeState() {
    }

    public static void setSink(Sink sink) {
        sSink = sink;
    }

    /** 卡模拟服务跑在自己的回调线程上，统一切回主线程再写界面。 */
    public static void event(final String line) {
        final Sink sink = sSink;
        if (sink == null) {
            return;
        }
        sMain.post(new Runnable() {
            @Override
            public void run() {
                sink.onLine(line);
            }
        });
    }

    public static void disarm() {
        senderArmed = false;
        handshakeJson = null;
    }
}
