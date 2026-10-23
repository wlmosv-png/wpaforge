package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.StatusParser;

/**
 * `cmd wifi status` 解析的离线自测。
 *
 * 样本取自真机（Color OS 15 / Android 15）。
 * 通道读不到 logcat 时判定就靠解析 status —— 解析错了整个判定就废了。
 */
public class StatusParseTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    /** 真机：连着 B209 时 `cmd wifi status` 的输出（截取关键字段） */
    private static final String ON_B209 = "" +
            "Wifi is enabled\n" +
            "Wifi scanning is always available\n" +
            "==== ClientModeManager instance: ConcreteClientModeManager{id=40153976 iface=wlan0 role=ROLE_CLIENT_PRIMARY} ====\n" +
            "Wifi is connected to \"B209\"\n" +
            "WifiInfo: SSID: \"B209\", BSSID: 28:93:7d:45:dc:ed, MAC: 52:85:10:b7:77:b8, " +
            "IP: /192.168.1.3, Security type: 2, Supplicant state: COMPLETED, " +
            "Wi-Fi standard: 11n, RSSI: -52, Link speed: 117Mbps, Net ID: 613, " +
            "Metered hint: false, score: 60, isUsable: true";

    /** 真机：连着别的网络（211） */
    private static final String ON_211 = "" +
            "Wifi is enabled\n" +
            "Wifi is connected to \"211\"\n" +
            "WifiInfo: SSID: \"211\", BSSID: aa:bb:cc:dd:ee:ff, IP: /192.168.1.4, " +
            "Security type: 2, Net ID: 570, score: 60";

    /** 未连接 */
    private static final String OFF = "" +
            "Wifi is enabled\n" +
            "Wifi is not connected\n";

    public static void main(String[] args) {
        System.out.println("== 连着目标 ==");
        StatusParser.Result r = StatusParser.parse(ON_B209, "B209");
        check("识别出连着目标", r.onTarget);
        check("netId 解析正确 (613)", r.netId == 613);
        check("currentSsid = B209", "B209".equals(r.currentSsid));

        System.out.println("== 连着别的网络 ==");
        StatusParser.Result r2 = StatusParser.parse(ON_211, "B209");
        check("目标未连上", !r2.onTarget);
        check("仍能解析出 netId (570)", r2.netId == 570);
        check("currentSsid = 211", "211".equals(r2.currentSsid));

        System.out.println("== 未连接 ==");
        StatusParser.Result r3 = StatusParser.parse(OFF, "B209");
        check("未连接 → 不算命中", !r3.onTarget);
        check("无 WifiInfo → netId = -1", r3.netId == -1);

        System.out.println("== 边界 ==");
        check("null 输入不崩", !StatusParser.parse(null, "B209").onTarget);
        check("null ssid 不崩", !StatusParser.parse(ON_B209, null).onTarget);
        check("空串不崩", StatusParser.parse("", "B209").netId == -1);

        System.out.println("== 含引号/空格的 SSID ==");
        String spaced = "Wifi is connected to \"My Net 5G\"\n" +
                "WifiInfo: SSID: \"My Net 5G\", Net ID: 42, score: 60";
        StatusParser.Result r4 = StatusParser.parse(spaced, "My Net 5G");
        check("含空格 SSID 命中", r4.onTarget);
        check("含空格 SSID 的 netId", r4.netId == 42);

        System.out.println("== 只有 WifiInfo 行的 ROM ==");
        String onlyInfo = "WifiInfo: SSID: \"B209\", Net ID: 700, score: 60";
        StatusParser.Result r5 = StatusParser.parse(onlyInfo, "B209");
        check("主判据行缺失时仍能从 WifiInfo 判出", r5.onTarget);
        check("netId 700", r5.netId == 700);

        System.out.println("== 便捷方法 ==");
        check("connectedTo(B209) = true", StatusParser.connectedTo(ON_B209, "B209"));
        check("connectedTo(别的) = false", !StatusParser.connectedTo(ON_B209, "X"));
        check("currentSsid", "B209".equals(StatusParser.currentSsid(ON_B209)));

        System.out.println("\n=== status 解析测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
