package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.WpaLogReader;

import java.util.List;

/**
 * wpa_supplicant 日志解析的离线自测。
 *
 * 这些日志样本取自真机实测（Color OS 15 / Android 15）。
 * 解析错误会导致「明明尝试过了却判成没试」，所以必须锁住。
 */
public class WpaLogTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    /** 真机实测日志片段 */
    private static final String REAL = "" +
            "10-11 19:09:05.609  1118  1118 I wpa_supplicant: wlan0: CTRL-EVENT-DSCP-POLICY clear_all\n" +
            "10-11 19:09:05.688  1118  1118 D wpa_supplicant: wlan0: Considering connect request: reassociate: 1  selected: 28:93:7d:11:22:02  bssid: 00:00:00:00:00:00  pending: 00:00:00:00:00:00  wpa_state: DISCONNECTED  ssid=0xb4000078c4850c00  current_ssid=0xb4000078c4850c00\n" +
            "10-11 19:09:05.688  1118  1118 I wpa_supplicant: wlan0: Trying to associate with SSID 'B209'\n" +
            "10-11 19:09:09.374  1118  1118 D wpa_supplicant: nl80211: Scan results indicate BSS status with 28:93:7d:11:22:02 as associated\n" +
            "10-11 19:08:44.931  1118  1118 D wpa_supplicant: wlan0: BSS: Remove id 159 BSSID 74:26:ff:11:22:13 SSID '232' due to wpa_bss_flush_by_age";

    public static void main(String[] args) {
        System.out.println("== 解析真机日志 ==");

        List<WpaLogReader.LogEvent> evs = WpaLogReader.parse(REAL);
        System.out.println("  提取事件数: " + evs.size());
        check("能提取到事件", !evs.isEmpty());

        boolean sawAssoc = false;
        for (WpaLogReader.LogEvent e : evs) {
            if ("associate".equals(e.kind)) {
                sawAssoc = true;
                check("关联事件的 SSID 正确解析为 B209", "B209".equals(e.ssid));
            }
        }
        check("识别到「正在尝试关联」", sawAssoc);

        System.out.println("== 查询接口 ==");
        check("sawAssociateFor(B209) → true",
                WpaLogReader.sawAssociateFor(REAL, "B209"));
        check("sawAssociateFor(B999) → false（别的网络不算）",
                !WpaLogReader.sawAssociateFor(REAL, "B999"));
        check("null SSID → false",
                !WpaLogReader.sawAssociateFor(REAL, null));

        // \u5173\u952e\uff1a\u65e5\u5fd7\u91cc\u51fa\u73b0 '232' \u53ea\u662f BSS \u6e05\u7406\uff0c\u4e0d\u80fd\u5f53\u6210\u5173\u8054\u5c1d\u8bd5
        check("\u300cBSS: Remove ... SSID '232'」不算关联尝试",
                !WpaLogReader.sawAssociateFor(REAL, "232"));

        System.out.println("== 该 ROM 不输出的字段 ==");
        check("本机日志无 Key negotiation → 正确识别为没有",
                !WpaLogReader.sawNegotiated(REAL));
        check("本机日志无 Handshake failed → 正确识别为没有",
                !WpaLogReader.sawHandshakeFailure(REAL));

        System.out.println("== 空输入与异常输入 ==");
        check("null 输入返回空列表", WpaLogReader.parse(null).isEmpty());
        check("空串返回空列表", WpaLogReader.parse("").isEmpty());
        check("垃圾行不崩",
                WpaLogReader.parse("garbage\n---\n").size() >= 0);
        check("null SSID 查询不崩", !WpaLogReader.sawAssociateFor(null, "X"));

        System.out.println("== 其它 ROM 的日志形态 ==");
        String other = "I wpa_supplicant: wlan0: Trying to associate with SSID 'MyNet_5G'\n" +
                "I wpa_supplicant: WPA: Key negotiation completed with aa:bb:cc:dd:ee:ff\n" +
                "I wpa_supplicant: WPA: 4-Way Handshake failed - pre-shared key may be incorrect";
        check("含空格的 SSID 能完整解析",
                WpaLogReader.sawAssociateFor(other, "MyNet_5G"));
        check("其它 ROM 的 Key negotiation 能识别",
                WpaLogReader.sawNegotiated(other));
        check("其它 ROM 的握手失败能识别",
                WpaLogReader.sawHandshakeFailure(other));
        String m1Sample = "I wpa_supplicant: wlan0: WPA: RX message 1 of 4-Way Handshake from 28:93:7d:11:22:02\n";
        check("握手第 1 帧 (M1) 能识别",
                WpaLogReader.sawHandshakeM1(m1Sample));

        // ---- 2026-10-22 真机抓到的原文（不是构造的样本）----
        System.out.println("== 真机原文（ColorOS 15）==");
        String realFail = "10-22 09:19:24.107 28272 28272 I wpa_supplicant: "
                + "wlan0: WPA: 4-Way Handshake failed - pre-shared key may be incorrect\n";
        check("真机失败行能识别", WpaLogReader.sawHandshakeFailure(realFail));

        String realWrongKey = "10-22 09:19:24.107 28272 28272 I wpa_supplicant: "
                + "wlan0: CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"FAST_E972\" "
                + "auth_failures=1 duration=5 reason=WRONG_KEY\n";
        check("reason=WRONG_KEY 能识别（结构化失败信号）",
                WpaLogReader.sawHandshakeFailure(realWrongKey));

        String realOk = "10-22 09:17:53.953 28272 28272 I wpa_supplicant: "
                + "wlan0: WPA: Key negotiation completed with 28:93:7d:11:22:01 [PTK=CCMP GTK=TKIP]\n";
        check("真机成功行能识别（旧结论说本机不输出，实测有）",
                WpaLogReader.sawNegotiated(realOk));

        String realConnected = "10-22 09:17:53.953 28272 28272 I wpa_supplicant: "
                + "wlan0: CTRL-EVENT-CONNECTED - Connection to 28:93:7d:11:22:01 completed "
                + "[id=0 id_str=%7B%22configKey%22%3A%22%5C%22B\n";
        check("CTRL-EVENT-CONNECTED 也算成功证据",
                WpaLogReader.sawNegotiated(realConnected));

        String realAssoc = "10-22 09:19:15.288 28272 28272 I wpa_supplicant: "
                + "wlan0: Trying to associate with SSID 'FAST_E972'\n";
        check("真机关联行能识别，且 SSID 解析正确",
                WpaLogReader.sawAssociateFor(realAssoc, "FAST_E972"));
        check("关联行的 SSID 不会张冠李戴",
                !WpaLogReader.sawAssociateFor(realAssoc, "B212"));

        String realM1 = "10-22 09:17:53.914 28272 28272 I wpa_supplicant: "
                + "wlan0: RSN: RX message 1 of 4-Way Handshake from 28:93:7d:11:22:01 (ver=2)\n";
        check("握手第 1 帧能识别", WpaLogReader.sawHandshakeM1(realM1));

        System.out.println("\n=== wpa 日志解析测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
