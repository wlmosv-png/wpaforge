package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.SupplicantParser;

/**
 * WifiInfo 行解析的离线自测（样本取自真机 ColorOS 15）。
 *
 * 这是判定命中的**唯一硬判据**：SSID + Supplicant state: COMPLETED
 * + 有效 IP + Net ID 必须同行同源。解析错了就会误判。
 */
public class SupplicantParserTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    /** 真机：正确密码连上 B209 时的 WifiInfo 行 */
    private static final String OK_B209 =
            "WifiInfo: SSID: \"B209\", BSSID: 28:93:7d:11:22:02, MAC: 52:85:10:11:22:18, "
            + "IP: /192.168.1.3, Security type: 2, Supplicant state: COMPLETED, "
            + "Wi-Fi standard: 11n, RSSI: -52, Link speed: 117Mbps, Net ID: 613, "
            + "Metered hint: false, score: 60, isUsable: true";

    /** 真机：错误密码后系统回连 B217（别的网）—— 同样是 COMPLETED */
    private static final String OK_B217 =
            "WifiInfo: SSID: \"B217\", BSSID: 28:93:7d:11:22:06, MAC: 42:7d:5b:11:22:17, "
            + "IP: /192.168.1.5, Security type: 2, Supplicant state: COMPLETED, "
            + "Wi-Fi standard: 11n, RSSI: -57, Link speed: 162Mbps, Net ID: 631, score: 60";

    /** 连接中（握手未完成） */
    private static final String HANDSHAKING =
            "WifiInfo: SSID: \"B209\", BSSID: 28:93:7d:11:22:02, IP: /0.0.0.0, "
            + "Security type: 2, Supplicant state: 4WAY_HANDSHAKE, Net ID: 700";

    /** 未连接 */
    private static final String NOT_CONNECTED =
            "Wifi is enabled\nWifi scanning is always available\nWifi is not connected\n";

    public static void main(String[] args) {
        System.out.println("== 正确连上 ==");
        SupplicantParser.Info i = SupplicantParser.parse(OK_B209);
        check("解析出 raw 行", i.raw);
        check("SSID = B209", "B209".equals(i.ssid));
        check("state = COMPLETED", "COMPLETED".equals(i.state));
        check("IP = 192.168.1.3", "192.168.1.3".equals(i.ip));
        check("netId = 613", i.netId == 613L);
        check("握手完成", i.handshakeCompleted());
        check("IP 有效", i.hasValidIp());
        check("判定：目标已就绪", i.isTargetConnected("B209"));
        check("便捷方法一致", SupplicantParser.targetReady(OK_B209, "B209"));

        System.out.println("== 回连了别的网络（假阳性来源）==");
        SupplicantParser.Info j = SupplicantParser.parse(OK_B217);
        check("它自己是 COMPLETED", j.handshakeCompleted());
        check("但不是目标 B209", !j.isTargetConnected("B209"));
        check("targetReady(B209) = false", !SupplicantParser.targetReady(OK_B217, "B209"));
        check("targetReady(B217) = true", SupplicantParser.targetReady(OK_B217, "B217"));

        System.out.println("== 连接中（握手未完成）==");
        SupplicantParser.Info h = SupplicantParser.parse(HANDSHAKING);
        check("state 不是 COMPLETED", !h.handshakeCompleted());
        check("IP 是 0.0.0.0 → 无效", !h.hasValidIp());
        check("不判命中", !h.isTargetConnected("B209"));

        System.out.println("== 未连接 ==");
        SupplicantParser.Info n = SupplicantParser.parse(NOT_CONNECTED);
        check("没有 WifiInfo 行", !n.raw);
        check("不判命中", !n.isTargetConnected("B209"));

        System.out.println("== 边界 ==");
        check("null 不崩", !SupplicantParser.parse(null).isTargetConnected("X"));
        check("空串不崩", !SupplicantParser.parse("").isTargetConnected("X"));
        check("null 目标名不判命中",
                !SupplicantParser.parse(OK_B209).isTargetConnected(null));
        check("unknown ssid 被当作空",
                SupplicantParser.parse("WifiInfo: SSID: \"<unknown ssid>\", Supplicant state: COMPLETED")
                        .ssid == null);

        System.out.println("== 含空格的 SSID ==");
        String spaced = "WifiInfo: SSID: \"My Net 5G\", IP: /10.0.0.2, "
                + "Supplicant state: COMPLETED, Net ID: 42";
        check("含空格 SSID 能判定", SupplicantParser.targetReady(spaced, "My Net 5G"));
        check("不误配前缀", !SupplicantParser.targetReady(spaced, "My"));

        System.out.println("== IP 异常 ==");
        String noIp = "WifiInfo: SSID: \"X\", Supplicant state: COMPLETED, Net ID: 1";
        check("无 IP 字段 → 不判命中", !SupplicantParser.targetReady(noIp, "X"));

        System.out.println("\n=== WifiInfo 解析测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
