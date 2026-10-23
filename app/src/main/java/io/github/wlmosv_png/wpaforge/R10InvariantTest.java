package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AttemptId;
import io.github.wlmosv_png.wpaforge.audit.AttemptMarker;
import io.github.wlmosv_png.wpaforge.audit.BackoffPolicy;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.WpaLogReader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * R10 不变量测试：把审计里反复出现的那几条"绝不允许"写成可执行断言。
 *
 * <h3>六条不变量</h3>
 * <pre>
 *   I1 无明确且可归因的失败证据 → 不得产生确定失败（AUTH_FAIL 必须带归因码）
 *   I2 无充分成功证据 → 不得产生确定成功（成功只有两个可归因入口）
 *   I3 一个尝试的证据不能支持另一个尝试的结论（窗口切片隔离）
 *   I4 未执行 / 未知 / 超时 / 冲突 不得隐式折算成认证失败
 *   I5 已确定的终态不能被迟到事件覆盖（成功赋值唯一且立即跳出）
 *   I6 进程恢复不得凭空制造已经丢失的证据（新 run 的候选在旧窗口里取不到切片）
 * </pre>
 *
 * <h3>组合矩阵（窗口 × 内容 = 5 × 4）</h3>
 * 每个格子都断言「切片里到底能看到什么」：窗口不可用时**任何**日志证据都看不到
 * （因此不能据此判成功或失败）；本条标记之前的陈旧行一律不可见。
 */
public class R10InvariantTest {

    private static int pass = 0, fail = 0;
    private static final String T = "LAB-TARGET";
    private static final String BSSID = "02:00:00:00:00:0a";
    private static final String MINE = AttemptId.of(
            AttemptId.newRunId(1_764_000_000_000L, T, 0), 1);
    private static final String OTHER = AttemptId.of(
            AttemptId.newRunId(1_764_000_000_000L, T, 0), 2);

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        boolean c = (want == null) ? got == null : want.equals(got);
        if (!c) System.out.println("        want=" + want + " got=" + got);
        ok(name, c);
    }

    private static final String ASSOC = "wlan0: Trying to associate with SSID '" + T + "'\n";
    private static final String NEG = "wlan0: WPA: Key negotiation completed with " + BSSID + "\n";
    private static final String WK = "wlan0: CTRL-EVENT-SSID-TEMP-DISABLED ssid=\"" + T
            + "\" reason=WRONG_KEY\n";
    private static final String M1A = "10-23 06:03:33.936  5784  5784 I wpa_supplicant: "
            + "wlan0: RSN: RX message 1 of 4-Way Handshake from " + BSSID + "\n";
    private static final String M1B = "10-23 06:03:34.940  5784  5784 I wpa_supplicant: "
            + "wlan0: RSN: RX message 1 of 4-Way Handshake from " + BSSID + "\n";

    private static String mk(String id) { return AttemptMarker.line(id); }

    public static void main(String[] args) {
        String eng = read("/workspace/promo-wpaforge/app/src/main/java/"
                + "io/github/wlmosv_png/wpaforge/audit/CrackEngine.java");
        String code = eng == null ? null : stripComments(eng);

        // ─────────── 组合矩阵 ───────────
        System.out.println("== I3 组合矩阵：窗口 × 内容（5 × 4）==");
        String[][] windows = {
                {"无标记", ""},
                {"只有本条标记", mk(MINE) + "\n"},
                {"本条标记 + 其后的外来标记", mk(MINE) + "\n" + mk(OTHER) + "\n"},
                {"只有外来标记", mk(OTHER) + "\n"},
                {"陈旧行 + 本条标记", WK + M1A + ASSOC + mk(MINE) + "\n"},
        };
        String[][] bodies = {
                {"干净内容", ASSOC},
                {"协商完成", ASSOC + NEG},
                {"失败行", ASSOC + WK},
                {"M1 两次", ASSOC + M1A + M1B},
        };
        int cells = 0, bad = 0, crossSupport = 0;
        for (String[] w : windows) {
            for (String[] b : bodies) {
                cells++;
                String text = w[1] + b[1];
                AttemptMarker.Window win = AttemptMarker.evaluate(MINE, true, true, text);
                String slice = win.evidenceUsable()
                        ? AttemptMarker.sliceAfter(text, MINE) : "";
                boolean usable = win.evidenceUsable();
                boolean seesSuccess = WpaLogReader.sawNegotiatedFor(slice, BSSID);
                boolean seesFailure = WpaLogReader.sawWrongKeyFor(slice, T);
                boolean seesM1 = WpaLogReader.countHandshakeM1For(slice, BSSID) > 0;
                boolean seesAssoc = WpaLogReader.sawAssociateFor(slice, T);

                // 窗口不可用 → 什么都看不到（不得据此判成功/失败）
                if (!usable && (seesSuccess || seesFailure || seesM1 || seesAssoc)) bad++;
                if (usable && b[0].equals("协商完成") && !seesSuccess) bad++;
                if (usable && b[0].equals("失败行") && !seesFailure) bad++;
                if (usable && b[0].equals("M1 两次")
                        && WpaLogReader.countHandshakeM1For(slice, BSSID) != 2) bad++;
                // 陈旧行（标记之前）不得可见：用「干净内容」这一列验证
                // （失败行那一列的 WRONG_KEY 是**正文**里的，属于本条候选，本来就该看到）
                if (w[0].equals("陈旧行 + 本条标记") && b[0].equals("干净内容")
                        && (WpaLogReader.sawWrongKeyFor(slice, T)
                                || WpaLogReader.countHandshakeM1For(slice, BSSID) > 0)) bad++;
                // 外来候选的标记出现之后，本候选窗口即不可用
                if (w[0].equals("本条标记 + 其后的外来标记") && usable) crossSupport++;
            }
        }
        eq("矩阵格子数 = 20", 20, cells);
        eq("没有任何格子出现「窗口不可用却看到了日志证据」或「该看到却没看到」", 0, bad);
        eq("外来标记一律让本候选窗口失效（跨尝试不互相支持）", 0, crossSupport);

        // ─────────── I1 ───────────
        System.out.println("== I1 无归因证据不得判确定失败 ==");
        int dl = 0, dlAuthFail = 0;
        for (int m = 0; m < 32; m++) {
            CrackEngine.DeadlineOutcome o = CrackEngine.classifyDeadline(
                    (m & 1) != 0, (m & 2) != 0, (m & 4) != 0, (m & 8) != 0, (m & 16) != 0);
            dl++;
            if (o.verdict == CrackEngine.Verdict.AUTH_FAIL) dlAuthFail++;
        }
        eq("到期归类 32 组合全部检查", 32, dl);
        eq("到期归类永不产出 AUTH_FAIL", 0, dlAuthFail);
        if (code == null) {
            System.out.println("  N/RUN I1 源码守卫（源码不可读）");
        } else {
            int loopFrom = code.indexOf("while (SystemClock.uptimeMillis() < deadline)");
            int loopTo = code.indexOf("DeadlineOutcome out = classifyDeadline(");
            String loop = (loopFrom > 0 && loopTo > loopFrom) ? code.substring(loopFrom, loopTo) : "";
            int auth = 0, noCode = 0, at = 0;
            while ((at = loop.indexOf("new Attempt(password, Verdict.AUTH_FAIL", at)) >= 0) {
                auth++;
                int j = loop.indexOf(')', at);
                String callArgs = j > at ? loop.substring(at, j + 1) : "";
                // 归因码是最后一个实参：必须是**非空字符串字面量**
                if (callArgs.contains("\"\"") || !callArgs.contains("\"")) noCode++;
                at++;
            }
            ok("循环内至少有 4 个带归因码的 AUTH_FAIL 证据出口（实到 " + auth + "）", auth >= 4);
            eq("每个 AUTH_FAIL 都带非空归因码（没有匿名失败）", 0, noCode);
        }

        // ─────────── I2 ───────────
        System.out.println("== I2 无充分证据不得判成功 ==");
        if (code != null) {
            int succ = 0, at = 0;
            while ((at = code.indexOf("new Attempt(password, Verdict.SUCCESS", at)) >= 0) {
                succ++; at++;
            }
            eq("成功出口恰好 2 个（status 三条件同行 / 目标 BSSID 协商完成）", 2, succ);
            ok("status 成功出口走纯逻辑入口 SuccessRule.assess",
                    code.contains("SuccessRule.assess("));
            ok("日志成功出口要求目标 BSSID 命中（不是「出现过协商完成」）",
                    code.contains("WpaLogReader.sawNegotiatedFor(wlogSlice, target.bssid)"));
        }
        ok("三条件不全时不判成功（assess 返回 null）",
                io.github.wlmosv_png.wpaforge.audit.SuccessRule
                        .assess(T, T, "ASSOCIATING", "192.168.1.3", 613, 613).verdict == null);

        // ─────────── I4 ───────────
        System.out.println("== I4 未执行/未知/超时/冲突不得折算成认证失败 ==");
        CrackEngine.Verdict[] notFail = {CrackEngine.Verdict.UNKNOWN, CrackEngine.Verdict.TIMEOUT,
                CrackEngine.Verdict.ERROR, CrackEngine.Verdict.ABORTED, CrackEngine.Verdict.TARGET_GONE};
        int leaked = 0;
        for (CrackEngine.Verdict v : notFail) {
            if (v == CrackEngine.Verdict.AUTH_FAIL) leaked++;
            if (BackoffPolicy.countable(v)) leaked++;
        }
        eq("五个非失败状态都不是 AUTH_FAIL，且都不计入退避样本", 0, leaked);
        if (code != null) {
            ok("任务只在命中口令非空时记为 DONE_HIT（非成功状态不会变成命中）",
                    read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                            + "wpaforge/CrackService.java") != null
                            && read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                            + "wpaforge/CrackService.java").contains("hitPassword != null && !hitPassword.isEmpty()"));
        }

        // ─────────── I5 ───────────
        System.out.println("== I5 已确定终态不被迟到事件覆盖 ==");
        if (code != null) {
            int assign = 0, at = 0;
            while ((at = code.indexOf("hit = c.password", at)) >= 0) { assign++; at++; }
            eq("命中赋值点唯一（不可能被后续分支改写）", 1, assign);
            int hitIdx = code.indexOf("hit = c.password");
            String around = hitIdx > 0 ? code.substring(Math.max(0, hitIdx - 600), hitIdx + 80) : "";
            ok("赋值在 a.isSuccess() 守卫内", around.contains("if (a.isSuccess())"));
            ok("赋值后立即 break（终态不再接受本轮后续事件）",
                    code.substring(hitIdx, Math.min(code.length(), hitIdx + 120)).contains("break;"));
        }

        // ─────────── I6 ───────────
        System.out.println("== I6 恢复不得凭空制造已丢失的证据 ==");
        String oldWindow = mk(MINE) + "\n" + ASSOC + NEG;
        ok("旧 run 的候选在旧窗口里能看到证据（血缘可复盘）",
                AttemptMarker.evaluate(MINE, true, true, oldWindow).evidenceUsable());
        ok("新 run 的候选在旧窗口里取不到任何证据（不继承）",
                AttemptMarker.sliceAfter(oldWindow, OTHER).isEmpty()
                        && !AttemptMarker.evaluate(OTHER, true, true, oldWindow).evidenceUsable());

        System.out.println();
        System.out.println("=== R10 不变量测试: " + pass + " 通过, " + fail + " 失败 ===");
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
