package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.WpaLogReader;

/**
 * 早退判据（M1 重发）的离线自测。
 *
 * 真机依据（2026-10-23，ColorOS 15 / Android 15，一加 Ace 3 Pro）：
 *   M1#1  +0 ms
 *   M1#2  +962 ms     <- 第 2 次 M1 = 第 1 次握手已失败
 *   M1#3  +1007 ms
 *   4-Way Handshake failed  +1.0~2.1s（在 M1#3 之后才吐）
 * 所以「看到第 2 次 M1 就判失败」可省 2.0~3.1 秒/条；
 * 正确密码不会产生重发的 M1，因此不会漏判。
 */
public class EarlyFailTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) {
        System.out.println("== 早退判据：M1 重发 ==");

        String m1a = "10-23 06:03:33.936  5784  5784 I wpa_supplicant: wlan0: WPA: RX message 1 of 4-Way Handshake from 28:93:7d:44:95:a9 (ver=2)";
        String m1b = "10-23 06:03:34.940  5784  5784 I wpa_supplicant: wlan0: WPA: RX message 1 of 4-Way Handshake from 28:93:7d:44:95:a9 (ver=2)";
        String m1c = "10-23 06:03:35.945  5784  5784 I wpa_supplicant: wlan0: WPA: RX message 1 of 4-Way Handshake from 28:93:7d:44:95:a9 (ver=2)";
        String assoc = "10-23 06:03:32.162  5784  5784 I wpa_supplicant: wlan0: Trying to associate with SSID 'B214'";
        String neg = "10-23 06:03:34.000  5784  5784 I wpa_supplicant: WPA: Key negotiation completed with 28:93:7d:44:95:a9";
        String failLine = "10-23 06:03:36.980  5784  5784 I wpa_supplicant: wlan0: WPA: 4-Way Handshake failed - pre-shared key may be incorrect";

        check("空日志 → 0 次", WpaLogReader.countHandshakeM1("") == 0);
        check("只有关联行 → 0 次", WpaLogReader.countHandshakeM1(assoc) == 0);
        check("1 次 M1 → 1", WpaLogReader.countHandshakeM1(m1a) == 1);
        check("2 次 M1 → 2", WpaLogReader.countHandshakeM1(m1a + "\n" + m1b) == 2);
        check("3 次 M1 → 3", WpaLogReader.countHandshakeM1(m1a + "\n" + m1b + "\n" + m1c) == 3);
        check("握手失败行不冒充 M1", WpaLogReader.countHandshakeM1(failLine) == 0);
        check("成功行不冒充 M1", WpaLogReader.countHandshakeM1(neg) == 0);

        check("0 次 M1 → 不早退", !CrackEngine.m1RetryImpliesWrongKey(0, false));
        check("1 次 M1 → 不早退", !CrackEngine.m1RetryImpliesWrongKey(1, false));
        check("2 次 M1 且无成功证据 → 早退", CrackEngine.m1RetryImpliesWrongKey(2, false));
        check("3 次 M1 → 早退", CrackEngine.m1RetryImpliesWrongKey(3, false));
        check("2 次 M1 但已见目标握手完成 → 不早退（防漏判）", !CrackEngine.m1RetryImpliesWrongKey(2, true));


        // ---- 第二级：M1 之后静默即判错 ----
        long S = CrackEngine.EARLY_FAIL_SILENCE_MS;
        check("静默窗口 > 正确密码实测耗时（670~722ms 完成 → 窗口应 >= 600ms）", S >= 600L);
        check("没看到 M1 → 不早退", !CrackEngine.m1SilentImpliesWrongKey(false, false, 9999L, S));
        check("看到 M1 且已握手完成 → 不早退", !CrackEngine.m1SilentImpliesWrongKey(true, true, 9999L, S));
        check("M1 后未到窗口 → 不早退", !CrackEngine.m1SilentImpliesWrongKey(true, false, S - 1, S));
        check("M1 后到窗口且无进展 → 早退", CrackEngine.m1SilentImpliesWrongKey(true, false, S, S));
        check("M1 后远超窗口仍无进展 → 早退", CrackEngine.m1SilentImpliesWrongKey(true, false, 5000L, S));


        // ---- 按 BSSID 归属（真机翻车后补：关联行会被日志配额丢掉，不能靠它归属）----
        check("按 BSSID 归属：目标那条算 1", WpaLogReader.countHandshakeM1For(m1a, "28:93:7d:44:95:a9") == 1);
        check("按 BSSID 归属：别的 BSSID 不算", WpaLogReader.countHandshakeM1For(m1a, "aa:bb:cc:dd:ee:ff") == 0);
        check("按 BSSID 归属：重发两次算 2",
                WpaLogReader.countHandshakeM1For(m1a + "\n" + m1b, "28:93:7d:44:95:a9") == 2);
        check("按 BSSID 归属：大小写不敏感",
                WpaLogReader.countHandshakeM1For(m1a, "28:93:7D:44:95:A9") == 1);
        check("按 BSSID 归属：BSSID 为空时返回 0（由调用方退回关联行判据）",
                WpaLogReader.countHandshakeM1For(m1a, "") == 0);
        check("没有关联行也能归属（这正是修这个 bug 的目的）",
                !WpaLogReader.sawAssociateFor(m1a, "B214")
                        && WpaLogReader.countHandshakeM1For(m1a, "28:93:7d:44:95:a9") == 1);

        System.out.println("早退判据: pass=" + pass + " fail=" + fail);
        if (fail > 0) throw new RuntimeException("早退判据有用例失败");
    }
}
