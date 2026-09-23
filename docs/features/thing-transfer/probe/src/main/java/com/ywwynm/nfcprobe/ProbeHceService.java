package com.ywwynm.nfcprobe;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import java.nio.charset.StandardCharsets;

/**
 * 卡模拟端。发送端设备跑这个，接收端开读取模式来读。
 *
 * <p>三条命令：
 * <pre>
 *   SELECT AID      00 A4 04 00 07 F0 45 44 54 52 4E 53 00
 *   ECHO len        80 01 lenHi lenLo 00   返回 len 字节填充，用来量单次 R-APDU 上限
 *   GET HANDSHAKE   80 02 00 00 00         返回握手 JSON
 * </pre>
 */
public class ProbeHceService extends HostApduService {

    private static final byte[] SW_OK = {(byte) 0x90, (byte) 0x00};
    /** 6985 conditions of use not satisfied —— 用它表示「发送端没就绪」。 */
    private static final byte[] SW_NOT_ARMED = {(byte) 0x69, (byte) 0x85};
    private static final byte[] SW_UNKNOWN = {(byte) 0x6D, (byte) 0x00};

    private static final int MAX_ECHO = 60000;

    @Override
    public byte[] processCommandApdu(byte[] cmd, Bundle extras) {
        if (cmd == null || cmd.length < 4) {
            return SW_UNKNOWN;
        }
        if (isSelectAid(cmd)) {
            ProbeState.event("收到 SELECT（" + cmd.length + " B）");
            if (!ProbeState.senderArmed) {
                ProbeState.event("未就绪，已拒绝 —— 前台门控生效");
                return SW_NOT_ARMED;
            }
            return SW_OK;
        }
        if (cmd[0] == (byte) 0x80 && cmd[1] == (byte) 0x01) {
            int len = ((cmd[2] & 0xFF) << 8) | (cmd[3] & 0xFF);
            if (len < 0 || len > MAX_ECHO) {
                return SW_UNKNOWN;
            }
            byte[] data = new byte[len];
            for (int i = 0; i < len; i++) {
                data[i] = (byte) (i & 0xFF);
            }
            return withOk(data);
        }
        if (cmd[0] == (byte) 0x80 && cmd[1] == (byte) 0x02) {
            String hs = ProbeState.handshakeJson;
            if (!ProbeState.senderArmed || hs == null) {
                return SW_NOT_ARMED;
            }
            byte[] data = hs.getBytes(StandardCharsets.UTF_8);
            ProbeState.event("握手已发出（" + data.length + " B）");
            return withOk(data);
        }
        return SW_UNKNOWN;
    }

    @Override
    public void onDeactivated(int reason) {
        String why = reason == DEACTIVATION_LINK_LOSS
                ? "离开场" : "对方选了别的应用";
        ProbeState.event("链路断开（" + why + "）");
    }

    private static byte[] withOk(byte[] data) {
        byte[] out = new byte[data.length + 2];
        System.arraycopy(data, 0, out, 0, data.length);
        out[data.length] = (byte) 0x90;
        out[data.length + 1] = (byte) 0x00;
        return out;
    }

    private static boolean isSelectAid(byte[] cmd) {
        return cmd[0] == (byte) 0x00 && cmd[1] == (byte) 0xA4
                && cmd[2] == (byte) 0x04 && cmd[3] == (byte) 0x00;
    }
}
