package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.WpaLogReader;

/**
 * 日志判据的「归属校验」测试 —— 假阳性回归。
 *
 * <h3>事故（2026-10-22）</h3>
 * 目标 `2.4G-3` 尝试失败后，系统回连 `B217` 并握手成功，日志里出现
 * {@code Key negotiation completed with 28:93:7d:11:22:01}。旧实现只检查
 * 「有没有出现过协商完成」，不检查那是**哪个 AP** 的 —— 于是报出
 * 「2.4G-3 = redskins 命中」，而密码其实是错的。
 *
 * <p>这一组用例把「别的网络握手成功」钉死成**不能算命中**。
 */
public class LogAttributionTest {

    /** 目标 2.4G-3 的 BSSID（虚构） */
    private static final String TARGET = "AA:BB:CC:DD:EE:FF";
    /** B217 的 BSSID（真机抓到的样本） */
    private static final String OTHER = "28:93:7d:11:22:01";

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static String line(String msg) {
        return "10-22 10:00:00.000 28272 28272 I wpa_supplicant: wlan0: " + msg + "\n";
    }

    public static void main(String[] args) {
        System.out.println("== 复现事故：别的 AP 握手成功不算目标的 ==");
        String accident = line("Trying to associate with SSID '2.4G-3'")
                + line("WPA: 4-Way Handshake failed - pre-shared key may be incorrect")
                + line("CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"2.4G-3\" auth_failures=1 duration=5 reason=WRONG_KEY")
                + line("Trying to associate with SSID 'B217'")
                + line("WPA: Key negotiation completed with " + OTHER + " [PTK=CCMP GTK=TKIP]");
        ok("旧判据确实会被骗（事故可复现）", WpaLogReader.sawNegotiated(accident));
        ok("新判据：目标 BSSID 没握过手 -> 不算命中",
                !WpaLogReader.sawNegotiatedFor(accident, TARGET));
        ok("同一个日志里，确实能认出是 B217 的手",
                WpaLogReader.sawNegotiatedFor(accident, OTHER));

        System.out.println("== 目标自己握手成功 ==");
        String okLog = line("Trying to associate with SSID '2.4G-3'")
                + line("WPA: Key negotiation completed with " + TARGET + " [PTK=CCMP GTK=TKIP]");
        ok("目标 BSSID 握手 -> 命中", WpaLogReader.sawNegotiatedFor(okLog, TARGET));
        ok("换成别的 BSSID -> 不算命中", !WpaLogReader.sawNegotiatedFor(okLog, OTHER));

        System.out.println("== CTRL-EVENT-CONNECTED 同样要校验归属 ==");
        String connected = line("CTRL-EVENT-CONNECTED - Connection to " + OTHER
                + " completed [id=0 id_str=%7B%22configKey%22%3A%22B217%22%7D]");
        ok("别的 AP 连上 -> 不算命中", !WpaLogReader.sawNegotiatedFor(connected, TARGET));
        ok("旧判据会被这条骗到（也是事故路径）", WpaLogReader.sawNegotiated(connected));

        System.out.println("== BSSID 大小写不敏感 ==");
        String upper = line("WPA: Key negotiation completed with " + TARGET.toUpperCase() + " [PTK=CCMP]");
        ok("大写 BSSID 也能对上", WpaLogReader.sawNegotiatedFor(upper, TARGET.toLowerCase()));

        System.out.println("== 没有目标 BSSID 时宁可不判成功 ==");
        ok("BSSID 为 null -> false", !WpaLogReader.sawNegotiatedFor(okLog, null));
        ok("BSSID 为空串 -> false", !WpaLogReader.sawNegotiatedFor(okLog, ""));

        System.out.println("== 失败判据的归属：WRONG_KEY 要认准 SSID ==");
        String wrongForTarget = line("CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"2.4G-3\" reason=WRONG_KEY");
        ok("目标是 2.4G-3 时，这条算目标的失败",
                WpaLogReader.sawWrongKeyFor(wrongForTarget, "2.4G-3"));
        ok("但别的网络的失败不能算到目标头上",
                !WpaLogReader.sawWrongKeyFor(wrongForTarget, "B217"));

        System.out.println("== 空输入不崩 ==");
        ok("null 输入", !WpaLogReader.sawNegotiatedFor(null, TARGET));
        ok("空串输入", !WpaLogReader.sawNegotiatedFor("", TARGET));
        ok("空 log + null bssid", !WpaLogReader.sawNegotiatedFor(null, null));

        System.out.println();
        System.out.println("=== 日志归属校验测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
