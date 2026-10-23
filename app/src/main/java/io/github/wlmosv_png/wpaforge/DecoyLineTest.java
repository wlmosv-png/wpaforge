package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.StatusParser;

/**
 * 「诱饵行」防回归测试。
 *
 * <h3>为什么单独一个测试类</h3>
 * 2026-10-12 在本机（OnePlus PJX110 / ColorOS 15）实拉 `cmd wifi status`，
 * 发现输出里**同时**存在两段网络状态：
 *
 *   WifiInfo: SSID: "B212", ..., Supplicant state: COMPLETED,
 *             IP: /192.168.1.2, ..., Net ID: 666
 *   ...
 *   NetworkCapabilities: [ ... TransportInfo: <SSID: <unknown ssid>, ...,
 *             IP: /192.168.1.2, ..., Net ID: -1 ...> ... SSID: "B212" ...]
 *
 * 第二条是 NetworkCapabilities 内嵌的 TransportInfo，SSID 显示为
 * `<unknown ssid>`、Net ID 是 -1，但它**同样带 COMPLETED 和有效 IP**。
 *
 * 任何「扫全文找关键词」的判定都会被这行骗到：
 *   · 看到 COMPLETED 就以为连上了
 *   · 看到 IP 有效就以为连上了
 *   · 拿到 Net ID = -1 而误判「netId 建不出来」
 *
 * 这个工作发生过真实事故（记忆里记着：系统弹「无法连接到 BTWIFL_0923」，
 * App 却报「已连上（依据: wifiinfo）」）。所以每一台机器每一次
 * 都会出现这条诱饵行，必须有测试固化住「不被它骗」。
 */
public class DecoyLineTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    /**
     * 真机原始输出（2026-10-12 实拉，已删减无关字段，两段顺序与人眼所见一致）。
     */
    private static final String REAL_DECOY = "" +
            "Wifi is enabled\n" +
            "Wifi scanning is always available\n" +
            "==== ClientModeManager instance: ConcreteClientModeManager{id=40153976 iface=wlan0 role=ROLE_CLIENT_PRIMARY} ====\n" +
            "Wifi is connected to \"B212\"\n" +
            "WifiInfo: SSID: \"B212\", BSSID: 28:93:7d:11:22:01, MAC: 6a:f9:13:11:22:19, " +
            "IP: /192.168.1.2, Security type: 2, Supplicant state: COMPLETED, " +
            "Wi-Fi standard: 11n, RSSI: -44, Link speed: 117Mbps, " +
            "Frequency: 2432MHz, Net ID: 666, score: 60, isUsable: true\n" +
            "NetworkCapabilities: [ Transports: WIFI Capabilities: NOT_METERED&INTERNET" +
            " TransportInfo: <SSID: <unknown ssid>, BSSID: 02:00:00:00:00:00, " +
            "IP: /192.168.1.2, Security type: 2, Supplicant state: COMPLETED, " +
            "Wi-Fi standard: 11n, RSSI: -40, Frequency: 2432MHz, Net ID: -1, " +
            "score: 60, isUsable: true><none> SignalStrength: -40 AdminUids: [0] " +
            "SSID: \"B212\" UnderlyingNetworks: Null]\n";

    /** 诱饵行在后、真行在前（本机实测顺序） */
    public static void main(String[] args) {
        System.out.println("== 诱饵行在后（本机实测顺序）==");
        StatusParser.Result r = StatusParser.parse(REAL_DECOY, "B212");
        check("仍判定连着目标", r.onTarget);
        check("netId 未被 -1 覆盖（=666）", r.netId == 666);
        check("currentSsid 仍是 B212", "B212".equals(r.currentSsid));

        System.out.println("== 诱饵行在前、真行在后（顺序反过来）==");
        String rev = swapSegments(REAL_DECOY);
        StatusParser.Result r2 = StatusParser.parse(rev, "B212");
        check("仍判定连着目标", r2.onTarget);
        check("netId 取到 666 而不是 -1", r2.netId == 666);

        System.out.println("== 只有诱饵行、真行缺失（不该误判为连上别的网）==");
        String decoyOnly = "" +
                "Wifi is enabled\n" +
                "Wifi is connected to \"B212\"\n" +
                "NetworkCapabilities: [ TransportInfo: <SSID: <unknown ssid>, " +
                "IP: /192.168.1.2, Supplicant state: COMPLETED, Net ID: -1, score: 60>\n";
        StatusParser.Result r3 = StatusParser.parse(decoyOnly, "B212");
        check("只有诱饵行时仍以主判据行为准", r3.onTarget);
        check("netId 回落 -1（没有 WifiInfo 行）", r3.netId == -1);

        System.out.println("== 目标换成诱饵行里的 <unknown ssid> 不该命中 ==");
        StatusParser.Result r4 = StatusParser.parse(REAL_DECOY, "<unknown ssid>");
        check("unknown ssid 不作为目标命中", !r4.onTarget);

        System.out.println("== 空串 / null 仍然安全 ==");
        check("null 不崩", StatusParser.parse(null, "B212").netId == -1);
        check("空串不崩", StatusParser.parse("", "B212").netId == -1);

        System.out.println("\n=== 诱饵行测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** 把 WifiInfo 行与 NetworkCapabilities 行调个位置，验证顺序无关性 */
    private static String swapSegments(String s) {
        String[] lines = s.split("\n", -1);
        int a = -1, b = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("WifiInfo:")) a = i;
            if (lines[i].startsWith("NetworkCapabilities:")) b = i;
        }
        if (a < 0 || b < 0) return s;
        String tmp = lines[a];
        lines[a] = lines[b];
        lines[b] = tmp;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}
