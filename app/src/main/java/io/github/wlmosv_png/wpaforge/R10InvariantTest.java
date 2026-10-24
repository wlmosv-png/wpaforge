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
            // 复核加强：范围从「循环内」扩到**整个文件** —— 否则会漏掉
            // tryOne 里那条长度校验（复核时确实漏过，已修为 ERROR/invalid-candidate）。
            int auth = 0, noCode = 0, at = 0;
            while ((at = code.indexOf("new Attempt(password, Verdict.AUTH_FAIL", at)) >= 0) {
                auth++;
                int j = code.indexOf(')', at);
                String callArgs = j > at ? code.substring(at, j + 1) : "";
                if (callArgs.contains("\"\"") || !callArgs.contains("\"")) noCode++;
                at++;
            }
            eq("全文件的 AUTH_FAIL 出口数 = 4（全部是证据路径）", 4, auth);
            eq("每个 AUTH_FAIL 都带非空归因码（没有匿名失败）", 0, noCode);
            ok("命令前的「候选不合法」不再是 AUTH_FAIL（复核修正）",
                    code.contains("if (!isValidLength(password)) {")
                            && code.contains("Attempt a = new Attempt(password, Verdict.ERROR,"));
            ok("无效候选带机器可读归类码", code.contains("a.reason = \"invalid-candidate\""));
            ok("无效候选不计入退避样本（ERROR 不可计数）",
                    !BackoffPolicy.countable(CrackEngine.Verdict.ERROR));
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
            // 用**括号配平**判定"是否在守卫块内"，不再用固定长度窗口 ——
            // 窗口法会因为块内新增几行而误报（复核时被自己的修复打破过一次）。
            int guardIdx = code.indexOf("if (a.isSuccess())");
            int guardEnd = -1;
            if (guardIdx > 0) {
                int open = code.indexOf('{', guardIdx);
                if (open > 0) {
                    int depth = 0;
                    for (int k = open; k < code.length(); k++) {
                        char ch = code.charAt(k);
                        if (ch == '{') depth++;
                        else if (ch == '}') {
                            depth--;
                            if (depth == 0) { guardEnd = k; break; }
                        }
                    }
                }
            }
            ok("赋值在 a.isSuccess() 守卫块内（按括号配平判定）",
                    guardIdx > 0 && guardEnd > guardIdx && hitIdx > guardIdx && hitIdx < guardEnd);
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

        // ─────────── I7（复核新增）───────────
        System.out.println("== I7 未尝试的候选不得算作认证失败 ==");
        ok("合法长度 8/9/63 通过", CrackEngine.isValidLength("12345678")
                && CrackEngine.isValidLength("123456789")
                && CrackEngine.isValidLength("x".repeat(63)));
        ok("非法长度 null/7/64 被拒", !CrackEngine.isValidLength(null)
                && !CrackEngine.isValidLength("1234567")
                && !CrackEngine.isValidLength("x".repeat(64)));
        eq("非法候选的判词标为 invalid（不是通道错）",
                "#1 #2d7116(len=1)=!invalid",
                CrackEngine.verdictTag(1, "x", CrackEngine.Verdict.ERROR, "invalid"));
        eq("通道类 ERROR 的判词保持不变（兼容既有断言）",
                "#1 #2d7116(len=1)=!通道",
                CrackEngine.verdictTag(1, "x", CrackEngine.Verdict.ERROR, ""));

        // ─────────── I10（打包范围）───────────
        System.out.println("== I10 生产 dex 不得包含测试类与审计探针 ==");
        String build = read("/workspace/promo-wpaforge/2-build-dex.sh");
        if (build == null) {
            System.out.println("  N/RUN 打包守卫（脚本不可读）");
        } else {
            ok("编译清单排除 *Test.java", build.contains("! -name \"*Test.java\""));
            ok("编译清单排除 R3AuditProbe.java", build.contains("! -name \"R3AuditProbe.java\""));
            ok("编译清单排除 MarkerSelfCheck.java（自检工具无运行期入口）",
                    build.contains("! -name \"MarkerSelfCheck.java\""));
            ok("没有用 \"*Probe.java\" 这种会误伤 LanProbe.java 的写法",
                    !build.contains("! -name \"*Probe.java\""));
            // 按同一规则模拟一遍：谁进 dex、谁不进
            java.io.File dir = new java.io.File("/workspace/promo-wpaforge/app/src/main/java");
            java.util.List<java.io.File> all = new java.util.ArrayList<>();
            collect(dir, all);
            int included = 0, excludedTest = 0, excludedProbe = 0, lanProbeIncluded = 0;
            int excludedSelfCheck = 0;
            for (java.io.File f : all) {
                String n = f.getName();
                if (n.endsWith("Test.java")) { excludedTest++; continue; }
                if (n.equals("R3AuditProbe.java")) { excludedProbe++; continue; }
                if (n.equals("MarkerSelfCheck.java")) { excludedSelfCheck++; continue; }
                included++;
                if (n.equals("LanProbe.java")) lanProbeIncluded++;
            }
            ok("测试类全部被排除（当前 " + excludedTest + " 个，且不少于 50）",
                    excludedTest >= 50 && excludedTest == countEndingWith(all, "Test.java"));
            eq("审计探针被排除（1 个）", 1, excludedProbe);
            eq("自检工具被排除（1 个）", 1, excludedSelfCheck);
            ok("生产代码里没有 MarkerSelfCheck 的运行期引用（所以可安全排除）",
                    !productionContains("MarkerSelfCheck."));
            eq("生产源码文件数 = 总数 - 测试类 - 探针 - 自检工具",
                    all.size() - excludedTest - 1 - 1, included);
            eq("编译清单里没有任何 *Test.java",
                    0, countIncludedEndingWith(all, "Test.java"));
            eq("生产类 scan/LanProbe.java 仍在编译清单里（未被误伤）", 1, lanProbeIncluded);
            ok("判定链核心类仍在编译清单里",
                    included > 0 && includesFile(all, "CrackEngine.java")
                            && includesFile(all, "AttemptMarker.java")
                            && includesFile(all, "SuccessRule.java")
                            && includesFile(all, "FailoverRule.java"));
        }

        // ─────────── I9（复核补充）───────────
        System.out.println("== I9 窗口隔离状态必须进持久化判词（不只在 detail/界面）==");
        if (code != null) {
            ok("判词尾巴带窗口状态（可事后判断日志通道是否可用/被污染）",
                    code.contains("a.logWindow == null ? \"未采集\" : a.logWindow.reason"));
            ok("窗口状态与 attemptId 同处一条判词尾巴",
                    code.contains("verdictTag(a.attemptId, cursor, c.password"));
            ok("attemptId 进判词（不是只在 detail）", code.contains("verdictTag(a.attemptId"));
            // 复核补齐：**命中**（SUCCESS）也是终态结论，其持久化记录同样必须带 attemptId。
            // 原实现只有"未命中时"才写判词尾巴，而命中那行日志不带 attemptId →
            // 一次成功破解在文件日志里无法回溯到具体尝试。
            int hitLog = code.indexOf("⚡ 密钥命中");
            String hitSeg = hitLog > 0 ? code.substring(hitLog,
                    Math.min(code.length(), hitLog + 400)) : "";
            ok("命中日志行带 attemptId（成功结论可回溯到尝试）",
                    !hitSeg.isEmpty() && hitSeg.contains("a.attemptId"));
        }
        ok("窗口状态的四种取值可枚举（不撒谎）",
                "ok".equals(AttemptMarker.evaluate(MINE, true, true, mk(MINE) + "\nx").reason)
                        && "no-marker".equals(AttemptMarker.evaluate(MINE, true, true, "x").reason)
                        && "foreign".equals(AttemptMarker.evaluate(MINE, true, true,
                                mk(MINE) + "\n" + mk(OTHER) + "\n").reason)
                        && "id-invalid".equals(AttemptMarker.evaluate(null, true, true, "x").reason));

        // ─────────── I8（复核新增）───────────
        System.out.println("== I8 未加固的旁路判定链不得被接线 ==");
        String[] banned = {"RootProber.tryOne", "RootProber.probe", "RootProber.crack",
                "RootProber.audit", "RootProber.judge"};
        int wired = 0;
        for (String b : banned) {
            if (productionContains(b)) wired++;
        }
        eq("生产代码里没有任何 RootProber 判定入口的调用", 0, wired);
        String rp = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/audit/RootProber.java");
        if (rp != null) {
            // I8 强化（F4 同族清理）：原来这里只断言「没人调用」，但代码还在 ——
            // 一旦有人接回去就绕过全部新规则，并且会在每条候选前删掉目标的已保存配置。
            // 现在断言的是**它已经不存在**，风险从「靠纪律约束」变成「结构上不可能」。
            ok("RootProber 不再含 tryOne（未加固判定链已删除）", rp.indexOf("tryOne(") < 0);
            ok("RootProber 不再自带 AUTH_FAIL/TIMEOUT 结论",
                    !rp.contains("Verdict.AUTH_FAIL") && !rp.contains("Verdict.TIMEOUT"));
            // 只断言**执行型构造**消失；注释与 help 文本里提到命令名是允许的。
            ok("RootProber 不再含执行型 forget/connect 命令",
                    rp.indexOf("new String[]{\"su\", \"-c\", \"cmd wifi forget-network") < 0
                            && rp.indexOf("\"cmd wifi connect-network \" +") < 0
                            && rp.indexOf("\"timeout 20 \" + cmd") < 0);
            ok("RootProber 不再持有 attempt 链（tryOne/forgetTarget/execAsync）",
                    rp.indexOf("tryOne(") < 0 && rp.indexOf("private void forgetTarget(") < 0
                            && rp.indexOf("execAsync(") < 0);
            ok("RootProber 仍保留被外部使用的四项（check/checkRoot/lastTimedOut/exec）",
                    rp.contains("public static CheckResult check(")
                            && rp.contains("public static String checkRoot()")
                            && rp.contains("public static boolean lastTimedOut()")
                            && rp.contains("public static String exec("));
        }

        // F4：verifyHit 及其携带的三条破坏性命令必须**整体消失**（不是「没人调用」）。
        int vh = 0;
        // 注意：不要在这里禁 `wifi.disconnect()` —— 前置闸门 disconnectAndVerify()
        // 里的那次是**合法且必需**的（命令前把基线清成「未连接目标」）。
        // 要禁的是复验路径专有的那几段：写死 netId=0 的删配置、按 SSID 的删配置。
        for (String b : new String[]{"public boolean verifyHit(", "public VerifyResult ",
                "cmd wifi forget-network 0", "shell.forgetTarget("}) {
            if (productionContains(b)) vh++;
        }
        eq("引擎里不再有 verifyHit / VerifyResult / 复验期的删配置与断连命令", 0, vh);

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

    private static int countEndingWith(java.util.List<java.io.File> all, String suffix) {
        int n = 0;
        for (java.io.File f : all) if (f.getName().endsWith(suffix)) n++;
        return n;
    }

    /** 按构建规则筛过后（排除 *Test.java 与探针），清单里还有多少个以该后缀结尾的文件。 */
    private static int countIncludedEndingWith(java.util.List<java.io.File> all, String suffix) {
        int n = 0;
        for (java.io.File f : all) {
            String name = f.getName();
            boolean excluded = name.endsWith("Test.java") || name.equals("R3AuditProbe.java");
            if (!excluded && name.endsWith(suffix)) n++;
        }
        return n;
    }

    /** 某个文件名是否在「按构建规则筛过」的编译清单里。 */
    private static boolean includesFile(java.util.List<java.io.File> all, String name) {
        for (java.io.File f : all) {
            String n = f.getName();
            if (n.equals(name) && !n.endsWith("Test.java") && !n.equals("R3AuditProbe.java")) {
                return true;
            }
        }
        return false;
    }

    /** 在生产源码里搜一个符号（排除测试类与探针本身）。 */
    private static boolean productionContains(String needle) {
        java.io.File dir = new java.io.File("/workspace/promo-wpaforge/app/src/main/java");
        java.util.List<java.io.File> all = new java.util.ArrayList<>();
        collect(dir, all);
        for (java.io.File f : all) {
            String n = f.getName();
            if (n.endsWith("Test.java") || n.startsWith("R3AuditProbe") || n.endsWith("RootProber.java")) {
                continue;
            }
            String t = readFile(f);
            if (t != null && t.contains(needle)) return true;
        }
        return false;
    }

    private static void collect(java.io.File f, java.util.List<java.io.File> out) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            java.io.File[] kids = f.listFiles();
            if (kids != null) for (java.io.File k : kids) collect(k, out);
        } else if (f.getName().endsWith(".java")) {
            out.add(f);
        }
    }

    private static String readFile(java.io.File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
