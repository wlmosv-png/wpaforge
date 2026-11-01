package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * D5 补充测试：**多目标证据串扰** + NONE 不产生永久记录。
 *
 * <h3>场景</h3>
 * 同一个日志窗口里同时出现两个网络的失败行：
 * <pre>
 *   Trying to associate with SSID 'A'      ← 目标 A 的关联
 *   4-Way Handshake failed                 ← 无归属（可能属于 B）
 * </pre>
 * 这是「别的网络握手失败被算到 A 头上」的最小形态。
 */
public final class P05CrossTalkTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static String ts(int sec, int ms) {
        return String.format("10-31 12:00:%02d.%03d  1000  1000 I wpa_supplicant: ", sec, ms);
    }

    public static void main(String[] args) {
        System.out.println("== D5 补充：跨目标串扰 / NONE 永久性 ==");

        final String A = "NET-A";
        final String B = "NET-B";

        // ---------- 1. 无归属失败行：sawWrongKeyForAttempt 不应采信
        List<String> lines1 = new ArrayList<>();
        lines1.add(ts(1, 100) + "wlan0: Trying to associate with SSID '" + A + "'");
        lines1.add(ts(1, 900) + "WPA: 4-Way Handshake failed - pre-shared key may be incorrect");
        String slice1 = String.join("\n", lines1);

        boolean wrongKey1 = WpaLogReader.sawWrongKeyForAttempt(slice1, A, "02:00:00:00:00:0A");
        info("无归属失败行 -> sawWrongKeyForAttempt = " + wrongKey1);
        ok("无 TEMP-DISABLED 时 sawWrongKeyForAttempt 不采信（不可能判 DIRECT）",
                !wrongKey1, "返回 " + wrongKey1);

        // ---------- 2. 两个网络各自的直接否认，不应互相串
        List<String> lines2 = new ArrayList<>();
        lines2.add(ts(1, 100) + "wlan0: Trying to associate with SSID '" + B + "'");
        // 失败前导是 sawWrongKeyForAttempt 的第三个必要条件（缺它不会采信）
        lines2.add(ts(1, 300) + "RSN: RX message 1 of 4-Way Handshake from 02:00:00:00:00:0B");
        lines2.add(ts(1, 500) + "CTRL-EVENT-SSID-TEMP-DISABLED ssid=\"" + B + "\" reason=WRONG_KEY auth_failures=1");
        String sliceB = String.join("\n", lines2);

        boolean wrongKeyA = WpaLogReader.sawWrongKeyForAttempt(sliceB, A, "02:00:00:00:00:0A");
        boolean wrongKeyB = WpaLogReader.sawWrongKeyForAttempt(sliceB, B, "02:00:00:00:00:0B");
        info("B 的否认 -> 对 A 采信=" + wrongKeyA + " 对 B 采信=" + wrongKeyB);
        ok("B 的直接否认不得被算到 A 头上", !wrongKeyA, "对 A 返回 " + wrongKeyA);
        ok("B 的直接否认对 B 本身成立（对照组）", wrongKeyB, "对 B 返回 " + wrongKeyB);

        // ---------- 3. 无归属谓词确实会跨目标命中（说明为何必须降级）
        boolean hfAny = WpaLogReader.sawHandshakeFailure(slice1);
        info("sawHandshakeFailure 对无归属行 = " + hfAny);
        ok("前提确认：sawHandshakeFailure 确实不区分网络（这正是必须降为 INFERRED 的理由）",
                hfAny, "返回 " + hfAny);

        // ---------- 4. NONE 类结论不得参与任何永久化
        CrackEngine.Verdict[] nones = {
                CrackEngine.Verdict.TIMEOUT, CrackEngine.Verdict.UNKNOWN,
                CrackEngine.Verdict.ERROR, CrackEngine.Verdict.ABORTED
        };
        boolean okNone = true;
        StringBuilder bad = new StringBuilder();
        for (CrackEngine.Verdict v : nones) {
            EvidenceGrade.Level g = EvidenceGrade.defaultOf(v);
            if (EvidenceGrade.persistable(g) || EvidenceGrade.advancesCursor(g)) {
                okNone = false; bad.append(v).append(' ');
            }
        }
        ok("NONE 类结论既不持久化也不推进游标", okNone, "违反: " + bad);

        // ---------- 5. INFERRED 不推进游标（D5 核心不变式回归）
        ok("INFERRED 不得推进续跑游标",
                !EvidenceGrade.advancesCursor(EvidenceGrade.Level.INFERRED), "");
        ok("INFERRED 不得永久化",
                !EvidenceGrade.persistable(EvidenceGrade.Level.INFERRED), "");
        ok("INFERRED 仍可用于本轮跳过（conclusive）",
                EvidenceGrade.conclusive(EvidenceGrade.Level.INFERRED), "");

        System.out.println();
        System.out.println("=== D5 跨目标/NONE 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
