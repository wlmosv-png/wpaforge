package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * 第三阶段：错误证据的**时序归属**。
 *
 * <h3>缺陷</h3>
 * {@code sawWrongKeyForAttempt} 要求切片里「同时出现」三样东西：
 * 目标 SSID 的 associate 行、失败前导、带 SSID 的 TEMP-DISABLED，
 * 但**从不校验它们的先后顺序**。于是：
 * <pre>
 *   上一条候选残留的失败行（标记之后才吐出）
 *     + 本条的 associate 行
 *   -> 三者齐备 -> 被判定为「本条确证失败」（DIRECT）
 * </pre>
 *
 * <h3>规则</h3>
 * 失败必须发生在**本次关联之后**：
 * {@code associate 行下标 < 失败前导下标 <= TEMP-DISABLED 下标}。
 *
 * <h3>边界（如实标注）</h3>
 * 日志只有时间戳，无法识别某行属于哪个请求。因此本约束只能排除
 * **顺序明显不合理**的残留；顺序恰好合理的延迟行仍无法区分 ——
 * 那属于协议层的可观测上限，本测试不宣称已解决。
 */
public final class EvidenceAttributionOrderTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static final String T = "LAB-ORD";

    private static String ts(int sec, int ms) {
        return String.format("10-31 12:00:%02d.%03d  1000  1000 I wpa_supplicant: ", sec, ms);
    }

    private static String assoc() {
        return ts(1, 100) + "wlan0: Trying to associate with SSID '" + T + "'";
    }

    private static String m1() {
        return ts(1, 300) + "RSN: RX message 1 of 4-Way Handshake from 02:00:00:00:00:50";
    }

    private static String hf() {
        return ts(1, 500) + "WPA: 4-Way Handshake failed - pre-shared key may be incorrect";
    }

    private static String td() {
        return ts(1, 600) + "CTRL-EVENT-SSID-TEMP-DISABLED ssid=\"" + T
                + "\" reason=WRONG_KEY auth_failures=1";
    }

    public static void main(String[] args) {
        System.out.println("== 错误证据的时序归属 ==");

        // ---------- 1. 正常顺序：associate -> M1 -> failed -> TEMP-DISABLED
        List<String> ok1 = new ArrayList<>();
        ok1.add(assoc()); ok1.add(m1()); ok1.add(hf()); ok1.add(td());
        info("1 正常顺序");
        ok("1 associate 在前的完整失败链 -> 采信",
                WpaLogReader.sawWrongKeyForAttempt(String.join("\n", ok1), T,
                        "02:00:00:00:00:50"), "");

        // ---------- 2. 核心：失败行出现在 associate **之前**
        List<String> bad = new ArrayList<>();
        bad.add(td());        // 残留：来自上一条候选
        bad.add(hf());        // 残留
        bad.add(assoc());     // 本条的关联行（在残留之后）
        info("2 失败行先于 associate");
        ok("**2 失败行早于本次关联 -> 不得采信（残留污染）**",
                !WpaLogReader.sawWrongKeyForAttempt(String.join("\n", bad), T,
                        "02:00:00:00:00:50"),
                "三者齐备但顺序不合理，当前仍会采信 -> 把上一条的失败算到本条");

        // ---------- 3. 只有 TEMP-DISABLED（无 associate）-> 不采信
        List<String> onlyTd = new ArrayList<>();
        onlyTd.add(td());
        ok("3 只有 TEMP-DISABLED、无本次关联 -> 不采信",
                !WpaLogReader.sawWrongKeyForAttempt(String.join("\n", onlyTd), T,
                        "02:00:00:00:00:50"), "");

        // ---------- 4. associate + 失败，但 TEMP-DISABLED 属于别的网络
        List<String> otherSsid = new ArrayList<>();
        otherSsid.add(assoc()); otherSsid.add(hf());
        otherSsid.add(ts(1, 600) + "CTRL-EVENT-SSID-TEMP-DISABLED ssid=\"OTHER\" "
                + "reason=WRONG_KEY auth_failures=1");
        ok("4 TEMP-DISABLED 属于别的网络 -> 不采信",
                !WpaLogReader.sawWrongKeyForAttempt(String.join("\n", otherSsid), T,
                        "02:00:00:00:00:50"), "");

        // ---------- 5. 两条 associate + 一次失败：仍应采信（本条的关联在前）
        List<String> twoAssoc = new ArrayList<>();
        twoAssoc.add(assoc()); twoAssoc.add(m1()); twoAssoc.add(hf()); twoAssoc.add(td());
        twoAssoc.add(ts(2, 000) + "wlan0: Trying to associate with SSID '" + T + "'");
        ok("5 本条的完整失败链在其后仍有别的关联 -> 仍采信",
                WpaLogReader.sawWrongKeyForAttempt(String.join("\n", twoAssoc), T,
                        "02:00:00:00:00:50"), "");

        System.out.println();
        System.out.println("=== 证据时序测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
