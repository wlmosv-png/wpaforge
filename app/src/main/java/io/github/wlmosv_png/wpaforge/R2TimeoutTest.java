package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.WifiAuthFailureWatcher;
import io.github.wlmosv_png.wpaforge.audit.WpaLogReader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * R2 回归测试：超时、静默与缺失证据处理。
 *
 * <h3>R2 的核心区分</h3>
 * <pre>
 *   「没有观察到证据」  ≠  「观察到失败证据」
 *   到期（观察窗口结束）  ≠  认证失败        → 归 TIMEOUT，不是 AUTH_FAIL
 *   M1 之后静默(纯时间)   ≠  失败证据        → 只诊断，不结案
 *   M1 被 AP 重发(协议层) =  失败证据        → 可据此结案（仍需 120ms 复核窗口）
 *   重复事件              ≠  AP 重发          → 计数按不同时间戳去重
 * </pre>
 *
 * <p>被测对象：{@link CrackEngine#m1RetryImpliesWrongKey}、
 * {@link CrackEngine#m1SilentImpliesWrongKey}（语义仍钉住，但生产不再单独用它）、
 * {@link WifiAuthFailureWatcher#authErrorImpliesWrongKey} 的边界、
 * {@link WpaLogReader#countHandshakeM1For} 的去重，以及 {@link CrackEngine#classifyDeadline}
 * 的「无证据 → TIMEOUT」不变量与引擎源码守卫。
 */
public class R2TimeoutTest {

    private static int pass = 0, fail = 0;
    private static final String BSSID = "02:00:00:00:00:0a";

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        boolean c = (want == null) ? got == null : want.equals(got);
        if (!c) System.out.println("        want=" + want + " got=" + got);
        ok(name, c);
    }

    private static String m1(String ts) {
        return (ts == null ? "" : ts + "  5784  5784 I wpa_supplicant: ")
                + "wlan0: RSN: RX message 1 of 4-Way Handshake from " + BSSID + "\n";
    }

    public static void main(String[] args) {
        System.out.println("== R2-1 M1 重发 = 协议层失败证据（矩阵）==");
        int fired = 0, notFired = 0;
        for (int n = 0; n <= 3; n++) {
            if (CrackEngine.m1RetryImpliesWrongKey(n, false)) fired++;
            else notFired++;
        }
        eq("未见成功证据时：只有 n>=2 结案（0/1 不结案）", 2, fired);
        eq("其余组合不结案", 2, notFired);
        ok("已见成功证据 → 永不据此结案",
                !CrackEngine.m1RetryImpliesWrongKey(2, true)
                        && !CrackEngine.m1RetryImpliesWrongKey(3, true));

        System.out.println("== R2-2 重复事件不得伪造重发（按时间戳去重）==");
        String dup = m1("10-23 06:03:33.936") + m1("10-23 06:03:33.936");
        eq("同一时间戳重复两次 → 计 1（不算重发）", 1,
                WpaLogReader.countHandshakeM1For(dup, BSSID));
        ok("因此不会据此结案", !CrackEngine.m1RetryImpliesWrongKey(
                WpaLogReader.countHandshakeM1For(dup, BSSID), false));
        String retry = m1("10-23 06:03:33.936") + m1("10-23 06:03:34.940");
        eq("不同时间戳两次 → 计 2（真重发）", 2,
                WpaLogReader.countHandshakeM1For(retry, BSSID));
        ok("据此结案", CrackEngine.m1RetryImpliesWrongKey(
                WpaLogReader.countHandshakeM1For(retry, BSSID), false));
        String noTs = m1(null) + m1(null);
        eq("无时间戳时退回按条计数（合成用例兼容）", 2,
                WpaLogReader.countHandshakeM1For(noTs, BSSID));
        eq("同一个时间戳出现三次也只算一次", 1, WpaLogReader.countHandshakeM1For(
                m1("10-23 06:03:33.936") + m1("10-23 06:03:33.936")
                        + m1("10-23 06:03:33.936"), BSSID));
        eq("别的 BSSID 的 M1 不计入", 0, WpaLogReader.countHandshakeM1For(retry,
                "02:00:00:00:00:0b"));

        System.out.println("== R2-3 静默是纯时间判据：语义钉住，但生产不再单独结案 ==");
        long S = CrackEngine.EARLY_FAIL_SILENCE_MS;
        ok("没看到 M1 → 不成立", !CrackEngine.m1SilentImpliesWrongKey(false, false, 99999L, S));
        ok("已有成功证据 → 不成立", !CrackEngine.m1SilentImpliesWrongKey(true, true, 99999L, S));
        ok("差 1ms 到窗口 → 不成立（边界）",
                !CrackEngine.m1SilentImpliesWrongKey(true, false, S - 1, S));
        ok("正好到窗口 → 成立（边界）",
                CrackEngine.m1SilentImpliesWrongKey(true, false, S, S));
        String eng = read("/workspace/promo-wpaforge/app/src/main/java/"
                + "io/github/wlmosv_png/wpaforge/audit/CrackEngine.java");
        if (eng == null) {
            System.out.println("  N/RUN 引擎守卫（源码不可读）");
        } else {
            String code = stripComments(eng);
            ok("静默判据不再结案：引擎里不出现 m1silent 判词", !code.contains("\"m1silent\""));
            // 只允许剩"声明"这一处；出现第 2 处就说明有人又拿它结案了
            int uses = 0, at = 0;
            while ((at = code.indexOf("m1SilentImpliesWrongKey(", at)) >= 0) { uses++; at++; }
            eq("引擎不再调用静默谓词（只剩声明那 1 处）", 1, uses);
            ok("失败结论仍由证据路径给出（m1retry 保留）", code.contains("\"m1retry\""));
            ok("静默阈值常量保留（供单测与文档引用）",
                    code.contains("EARLY_FAIL_SILENCE_MS"));
        }

        System.out.println("== R2-4 广播判据的时间边界（事件恰好在边界到达）==");
        ok("errorAt == attemptStart → 不成立（边界不含）",
                !WifiAuthFailureWatcher.authErrorImpliesWrongKey(1000L, 1000L, false));
        ok("errorAt = attemptStart + 1ms → 成立",
                WifiAuthFailureWatcher.authErrorImpliesWrongKey(1001L, 1000L, false));
        ok("errorAt 早于 attemptStart → 不成立（迟到/旧事件不算）",
                !WifiAuthFailureWatcher.authErrorImpliesWrongKey(999L, 1000L, false));
        ok("errorAt = 0（从未收到）→ 不成立",
                !WifiAuthFailureWatcher.authErrorImpliesWrongKey(0L, 1000L, false));
        ok("attemptStart = 0（无效起点）→ 不成立",
                !WifiAuthFailureWatcher.authErrorImpliesWrongKey(5000L, 0L, false));
        ok("已有成功证据 → 不成立（成功优先）",
                !WifiAuthFailureWatcher.authErrorImpliesWrongKey(5000L, 1000L, true));

        System.out.println("== R2-5 到期无证据：TIMEOUT 而不是失败（不变量）==");
        CrackEngine.DeadlineOutcome none =
                CrackEngine.classifyDeadline(false, false, false, false, false);
        eq("全无证据 → TIMEOUT", CrackEngine.Verdict.TIMEOUT, none.verdict);
        eq("理由码 = no-evidence", "no-evidence", none.reason);
        ok("TIMEOUT 不是 AUTH_FAIL", none.verdict != CrackEngine.Verdict.AUTH_FAIL);
        ok("TIMEOUT 不计入退避样本（不是 AP 的响应）",
                !io.github.wlmosv_png.wpaforge.audit.BackoffPolicy.countable(
                        CrackEngine.Verdict.TIMEOUT));
        ok("AUTH_FAIL 仍计入（它才是 AP 给了明确结论）",
                io.github.wlmosv_png.wpaforge.audit.BackoffPolicy.countable(
                        CrackEngine.Verdict.AUTH_FAIL));

        System.out.println("== R2-6 矛盾事件：成功证据优先（顺序守卫）==");
        if (eng != null) {
            String code = stripComments(eng);
            int success = code.indexOf("sawNegotiatedFor(wlogSlice, target.bssid)");
            int failure = code.indexOf("sawWrongKeyFor(wlogSlice, target.ssid)");
            ok("成功证据的求值点排在日志失败判据之前",
                    success > 0 && failure > 0 && success < failure);
            int b = code.indexOf("if (tried && WpaLogReader.sawNegotiatedFor");
            int f = code.indexOf("if (logWinUsable\n                            && (WpaLogReader.sawWrongKeyFor");
            ok("判据 B（成功）在失败判据之前 return",
                    b > 0 && (f < 0 || b < f));
        }

        System.out.println("== R2-7 时钟一致性 ==");
        if (eng != null) {
            String code = stripComments(eng);
            int wall = 0, uptime = 0;
            for (String l : code.split("\n")) {
                if (l.contains("System.currentTimeMillis")) wall++;
                if (l.contains("SystemClock.uptimeMillis")) uptime++;
            }
            eq("判定路径全部用单调时钟（uptimeMillis 大量使用）", true, uptime > 30);
            eq("墙钟只用在生成 runId（1 处）", 1, wall);
            ok("墙钟那一处确实是 runId 生成",
                    code.contains("AttemptId.newRunId(System.currentTimeMillis()"));
        }

        System.out.println();
        System.out.println("=== R2 超时测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String stripComments(String src) {
        String s1 = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder sb = new StringBuilder();
        for (String l : s1.split("\n")) {
            int k = l.indexOf("//");
            sb.append(k >= 0 ? l.substring(0, k) : l).append('\n');
        }
        return sb.toString();
    }

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
