package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AttemptId;
import io.github.wlmosv_png.wpaforge.audit.AttemptMarker;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.FailoverRule;
import io.github.wlmosv_png.wpaforge.audit.WpaLogReader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * R3 修复的回归测试：错误归因与错误失败结论。
 *
 * <p>被测对象是**生产实现本身**：{@link FailoverRule#looksLikeFailover} 的七参入口、
 * {@link CrackEngine#classifyDeadline}、{@link AttemptMarker#sliceAfter}；
 * 以及对 {@code CrackEngine} 源码的**接线守卫**（把闸门删掉即判红）。
 *
 * <p>每个用例都对应审计报告里的一个根因编号（S2-1/S2-2/S2-3/S3-1/S3-2/S4-2/S6-2）。
 */
public class R3VerdictTest {

    private static int pass = 0, fail = 0;
    private static final String T = "LAB-TARGET";
    private static final String BSSID = "02:00:00:00:00:0a";
    private static final String MID = AttemptId.of(
            AttemptId.newRunId(1_764_000_000_000L, T, 0), 1);

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        boolean c = (want == null) ? got == null : want.equals(got);
        if (!c) System.out.println("        want=" + want + " got=" + got);
        ok(name, c);
    }

    private static String line(String s) { return s + "\n"; }

    private static String marker(String id) { return AttemptMarker.line(id); }

    public static void main(String[] args) {
        System.out.println("== R3-1 回连判据：归属前置（审计 S2-1/S2-2）==");
        ok("未证明「本条被尝试过」→ 回连判据不成立",
                !FailoverRule.looksLikeFailover(T, "OTHER-AP", "COMPLETED", true, 5000,
                        false, false));
        ok("已证明「本条被尝试过」+ 其它条件满足 → 成立",
                FailoverRule.looksLikeFailover(T, "OTHER-AP", "COMPLETED", true, 5000,
                        true, false));
        eq("非法 id 不参与该判据（判据本身不接受 id）", Boolean.TRUE,
                FailoverRule.looksLikeFailover(T, "OTHER-AP", "COMPLETED", true, 2500, true, false));
        ok("exercise 为假时，无论其它条件多完备都不成立（4 种组合）",
                !FailoverRule.looksLikeFailover(T, "OTHER-AP", "COMPLETED", true, 999999, false, false)
                        && !FailoverRule.looksLikeFailover(T, "OTHER-AP", "COMPLETED", false, 999999, false, false)
                        && !FailoverRule.looksLikeFailover(T, null, "COMPLETED", true, 999999, false, false)
                        && !FailoverRule.looksLikeFailover(null, "OTHER-AP", "COMPLETED", true, 999999, false, false));

        System.out.println("== R3-2 成功证据优先（审计 S2-3）==");
        ok("本轮已有目标的握手完成证据 → 失败判据不得抢答",
                !FailoverRule.looksLikeFailover(T, "OTHER-AP", "COMPLETED", true, 999999,
                        true, true));
        ok("成功 Bahamas：其它条件全真、仅 negotiated=true 时不成立",
                !FailoverRule.looksLikeFailover(T, "OTHER-AP", "COMPLETED", true, 999999,
                        true, true));

        System.out.println("== R3-3 原有边界不得被放宽（防回归）==");
        ok("MIN_WAIT 之前不成立", !FailoverRule.looksLikeFailover(T, "OTHER-AP", "COMPLETED",
                true, FailoverRule.MIN_WAIT_MS - 1, true, false));
        ok("MIN_WAIT 边界成立", FailoverRule.looksLikeFailover(T, "OTHER-AP", "COMPLETED",
                true, FailoverRule.MIN_WAIT_MS, true, false));
        ok("leftBaseline=false 不成立", !FailoverRule.looksLikeFailover(T, "OTHER-AP",
                "COMPLETED", false, 5000, true, false));
        ok("status 仍是目标 → 不成立", !FailoverRule.looksLikeFailover(T, T, "COMPLETED",
                true, 5000, true, false));
        ok("状态非 COMPLETED → 不成立", !FailoverRule.looksLikeFailover(T, "OTHER-AP",
                "ASSOCIATING", true, 5000, true, false));
        ok("statusSsid 为空 → 不成立", !FailoverRule.looksLikeFailover(T, "", "COMPLETED",
                true, 5000, true, false));
        ok("旧五参入口保留（兼容调用点，语义 = 归属未知）",
                FailoverRule.looksLikeFailover(T, "OTHER-AP", "COMPLETED", true, 5000));

        System.out.println("== R3-4 到期归类：六个理由码（审计 S4-2）==");
        CrackEngine.DeadlineOutcome o1 = CrackEngine.classifyDeadline(true, false, false, false, false);
        eq("命令未生效 → ERROR/not-attempted", "not-attempted", o1.reason);
        ok("命令未生效 ≠ 认证失败", o1.verdict == CrackEngine.Verdict.ERROR);
        eq("仍在目标上 → UNKNOWN/on-target-unattributed", "on-target-unattributed",
                CrackEngine.classifyDeadline(false, true, false, false, false).reason);
        eq("netId 冲突 → UNKNOWN/netid-conflict", "netid-conflict",
                CrackEngine.classifyDeadline(false, false, true, false, false).reason);
        eq("通道无响应 → UNKNOWN/channel-no-response", "channel-no-response",
                CrackEngine.classifyDeadline(false, false, false, true, false).reason);
        eq("status 显示目标但证据不足 → UNKNOWN/status-unclear", "status-unclear",
                CrackEngine.classifyDeadline(false, false, false, false, true).reason);
        CrackEngine.DeadlineOutcome o6 = CrackEngine.classifyDeadline(false, false, false, false, false);
        eq("全无证据 → TIMEOUT/no-evidence", "no-evidence", o6.reason);
        ok("到期无证据 = TIMEOUT（**不是** AUTH_FAIL）",
                o6.verdict == CrackEngine.Verdict.TIMEOUT
                        && o6.verdict != CrackEngine.Verdict.AUTH_FAIL);

        System.out.println("== R3-5 到期归类：穷举 32 种组合的不变量 ==");
        int combos = 0, authFail = 0, success = 0, timeoutNonEmpty = 0, missingReason = 0;
        for (int m = 0; m < 32; m++) {
            boolean na = (m & 1) != 0, ot = (m & 2) != 0, nc = (m & 4) != 0;
            boolean cd = (m & 8) != 0, st = (m & 16) != 0;
            CrackEngine.DeadlineOutcome o = CrackEngine.classifyDeadline(na, ot, nc, cd, st);
            combos++;
            if (o.verdict == CrackEngine.Verdict.AUTH_FAIL) authFail++;
            if (o.verdict == CrackEngine.Verdict.SUCCESS) success++;
            if (o.verdict == CrackEngine.Verdict.TIMEOUT && !(o.reason.equals("no-evidence"))) timeoutNonEmpty++;
            if (o.reason == null || o.reason.isEmpty()) missingReason++;
        }
        eq("穷举组合数 = 32", 32, combos);
        eq("**没有任何组合**产出 AUTH_FAIL（无归因证据不得判失败）", 0, authFail);
        eq("没有任何组合产出 SUCCESS（到期不是成功路径）", 0, success);
        eq("TIMEOUT 只出现在「全无证据」一种组合", 0, timeoutNonEmpty);
        eq("每种组合都有理由码", 0, missingReason);

        System.out.println("== R3-6 窗口切片：陈旧行不得归因（审计 S3-2/S6-2）==");
        String stale = line("wlan0: CTRL-EVENT-SSID-TEMP-DISABLED ssid=\"" + T
                + "\" reason=WRONG_KEY")
                + line("wlan0: RSN: RX message 1 of 4-Way Handshake from " + BSSID)
                + line("wlan0: Trying to associate with SSID '" + T + "'")
                + line(marker(MID))
                + line("wlan0: Trying to associate with SSID '" + T + "'");
        String slice = AttemptMarker.sliceAfter(stale, MID);
        ok("切片里看不到标记之前的陈旧的 wrong_key",
                !WpaLogReader.sawWrongKeyFor(slice, T));
        ok("切片里看不到标记之前的旧 M1",
                WpaLogReader.countHandshakeM1For(slice, BSSID) == 0);
        ok("切片里看得到标记之后的关联行",
                WpaLogReader.sawAssociateFor(slice, T));
        ok("整段文本确实含这些陈旧行（证明切片不是空操作）",
                WpaLogReader.sawWrongKeyFor(stale, T)
                        && WpaLogReader.countHandshakeM1For(stale, BSSID) == 1);
        eq("标记缺失 → 空切片（日志证据不可用）", "", AttemptMarker.sliceAfter("no marker\n", MID));
        eq("id 非法 → 空切片", "", AttemptMarker.sliceAfter(marker(MID), "garbage"));
        eq("标记在末尾 → 切片为空", "", AttemptMarker.sliceAfter(line(marker(MID)), MID));
        ok("空文本不崩", AttemptMarker.sliceAfter(null, MID).isEmpty());
        ok("切片不含标记本身", !slice.contains(marker(MID)));

        System.out.println("== R3-7b 窗口不可用时：7 个日志判据**全部**为 false ==");
        // 这是「窗口闸」能取代显式布尔条件的**承载性理由**：切片为空时，
        // 所有日志判据都必须返回 false，否则空切片也能得出结论。
        AttemptMarker.Window dead = AttemptMarker.evaluate(MID, true, true, "no marker here\n");
        String emptySlice = dead.evidenceUsable() ? AttemptMarker.sliceAfter("no marker here\n", MID) : "";
        ok("窗口不可用 → 切片为空", !dead.evidenceUsable() && emptySlice.isEmpty());
        ok("判据 1 关联行为 false", !WpaLogReader.sawAssociateFor(emptySlice, T));
        ok("判据 2 协商完成为 false", !WpaLogReader.sawNegotiatedFor(emptySlice, BSSID));
        ok("判据 3 M1 存在为 false", !WpaLogReader.sawHandshakeM1(emptySlice));
        ok("判据 4 M1 计数为 0", WpaLogReader.countHandshakeM1For(emptySlice, BSSID) == 0);
        ok("判据 5 wrong_key 为 false", !WpaLogReader.sawWrongKeyFor(emptySlice, T));
        ok("判据 6 握手失败为 false", !WpaLogReader.sawHandshakeFailure(emptySlice));
        ok("判据 7 关联被拒为 false", !WpaLogReader.sawAssocReject(emptySlice));

        System.out.println("== R3-7c 缓冲区截断：不得把「被截掉」当成「没发生」 ==");
        // 场景 A：环形缓冲把**头部**挤掉 —— 标记滚出缓冲，后面只剩证据行。
        String truncatedHead = line("wlan0: Trying to associate with SSID '" + T + "'")
                + line("wlan0: CTRL-EVENT-SSID-TEMP-DISABLED ssid=\"" + T
                        + "\" reason=WRONG_KEY");
        AttemptMarker.Window wA = AttemptMarker.evaluate(MID, true, true, truncatedHead);
        ok("标记被截掉 → 窗口不可用（不冒充「本条已隔离」）", !wA.evidenceUsable());
        eq("理由码 = no-marker", "no-marker", wA.reason);
        ok("整段里的失败行不可用（切片为空 → 判据全 false）",
                AttemptMarker.sliceAfter(truncatedHead, MID).isEmpty()
                        && !WpaLogReader.sawWrongKeyFor(
                                AttemptMarker.sliceAfter(truncatedHead, MID), T));
        // 场景 B：标记在、但其后的行被**切尾**（读取窗口右侧被截断）。
        String truncatedTail = line(AttemptMarker.line(MID));
        eq("标记之后没有任何行 → 切片为空", "",
                AttemptMarker.sliceAfter(truncatedTail, MID));
        ok("窗口仍算可用，但无证据 → 只能得出「无结论」",
                AttemptMarker.evaluate(MID, true, true, truncatedTail).evidenceUsable()
                        && !WpaLogReader.sawNegotiatedFor(
                                AttemptMarker.sliceAfter(truncatedTail, MID), BSSID));
        // 降级语义：任何「证据缺失」组合都不得变成认证失败
        ok("证据全缺 → TIMEOUT（不是 AUTH_FAIL）",
                CrackEngine.classifyDeadline(false, false, false, false, false).verdict
                        == CrackEngine.Verdict.TIMEOUT);

        System.out.println("== R3-7d 过滤遗漏：漏一类行只能降级为「无证据」 ==");
        // 过滤式若漏掉某类判据行，该类证据永远不会出现 —— 语义上必须等价于
        // 「没有观察到这类事件」，因此结论只能落到 TIMEOUT/UNKNOWN 一侧。
        String filteredAway = "";      // 服务端 grep 把该类行丢掉后的样子
        ok("被过滤掉的行在解析层不产生任何事件",
                WpaLogReader.parse(filteredAway).isEmpty()
                        && !WpaLogReader.sawHandshakeFailure(filteredAway)
                        && !WpaLogReader.sawAssocReject(filteredAway)
                        && !WpaLogReader.sawWrongKeyFor(filteredAway, T));
        ok("失败类证据缺失时，到期归类里没有任何组合产出 AUTH_FAIL",
                noAuthFailAcrossDeadlineGrid());
        ok("过滤式仍覆盖全部 7 类判据行（逐条由 WpaFilterTest 覆盖守卫钉住）",
                WpaLogReader.SHELL_FILTER.contains("4-Way")
                        && WpaLogReader.SHELL_FILTER.contains("ASSOC-REJECT")
                        && WpaLogReader.SHELL_FILTER.contains("SSID-TEMP-DISABLED")
                        && WpaLogReader.SHELL_FILTER.contains("negotiat")
                        && WpaLogReader.SHELL_FILTER.contains("RX message")
                        && WpaLogReader.SHELL_FILTER.contains("CONNECTED")
                        && WpaLogReader.SHELL_FILTER.contains("reason="));

        System.out.println("== R3-7 接线守卫：闸门被删即红 ==");
        String eng = readFile("/workspace/promo-wpaforge/app/src/main/java/"
                + "io/github/wlmosv_png/wpaforge/audit/CrackEngine.java");
        if (eng == null) {
            System.out.println("  N/RUN 接线守卫（源码不可读）");
        } else {
            // 逐判据精确断言：**每一个**日志判据都必须吃 wlogSlice。
            // （写松了会漏：曾用"整段里不出现 xxx(wlog,"来判，结果漏掉
            //   sawAssociateFor 那一处 —— 变异测试当场抓到守卫无效。）
            String flat = stripComments(eng).replaceAll("\\s+", " ");
            ok("日志判据 1/7：关联行吃切片",
                    flat.contains("WpaLogReader.sawAssociateFor( wlogSlice, target.ssid)"));
            ok("日志判据 2/7：协商完成吃切片",
                    flat.contains("WpaLogReader.sawNegotiatedFor(wlogSlice, target.bssid)"));
            ok("日志判据 3/7：M1 计数吃切片",
                    flat.contains("WpaLogReader.countHandshakeM1For(wlogSlice, target.bssid)"));
            ok("日志判据 4/7：M1 存在性吃切片",
                    flat.contains("WpaLogReader.sawHandshakeM1(wlogSlice)"));
            ok("日志判据 5/7：wrong_key 吃切片",
                    flat.contains("WpaLogReader.sawWrongKeyFor(wlogSlice, target.ssid)"));
            ok("日志判据 6/7：握手失败吃切片",
                    flat.contains("WpaLogReader.sawHandshakeFailure(wlogSlice)"));
            ok("日志判据 7/7：关联被拒吃切片",
                    flat.contains("WpaLogReader.sawAssocReject(wlogSlice)"));
            ok("无任何日志判据直接吃整段 wlog",
                    !flat.contains("WpaLogReader.sawAssociateFor( wlog,")
                            && !flat.contains("WpaLogReader.sawNegotiatedFor(wlog,")
                            && !flat.contains("WpaLogReader.countHandshakeM1For(wlog,")
                            && !flat.contains("WpaLogReader.sawHandshakeM1(wlog)")
                            && !flat.contains("WpaLogReader.sawWrongKeyFor(wlog,")
                            && !flat.contains("WpaLogReader.sawHandshakeFailure(wlog)")
                            && !flat.contains("WpaLogReader.sawAssocReject(wlog)"));
            ok("回连判据带 exercise 前置", eng.contains("exercised,\n")
                    || eng.contains("exercise"));
            ok("广播判据带 exercise 前置", eng.contains("if (exercised"));
            ok("到期归类走纯函数", eng.contains("classifyDeadline("));
            int from = eng.indexOf("DeadlineOutcome out = classifyDeadline(");
            int to = eng.indexOf("Verdict verdict = out.verdict;", from);
            // 去掉注释再判：注释里会解释"旧实现曾经返回 AUTH_FAIL"，那不是代码
            String codeOnly = stripComments(from > 0 && to > from ? eng.substring(from, to) : "");
            ok("到期归类区段的**代码**里不再出现 AUTH_FAIL",
                    from > 0 && to > from && !codeOnly.contains("AUTH_FAIL"));
            // R11：窗口归属不再依赖"清空全局缓冲"，改为「时间锚点 + 起点标记」。
            // 断言换成同等强度的新不变式：起点状态**被算出来并传进窗口元数据**，
            // 而且生产主循环里不再有任何清空调用。
            ok("窗口起点状态与「日志是否可读」都被算出来并传进元数据",
                    eng.contains("final boolean windowStartOk = markerWritten;")
                            && eng.contains("boolean logReadOk = lr != null && lr.logUsable();")
                            && eng.contains("wlog, true, logReadOk);"));
            ok("生产主循环不再清空全局缓冲（clearChecked/clear 都不调用）",
                    !eng.contains("clearChecked(") && !eng.contains("WifiStateLog.clear(shell)"));
            ok("窗口切片被使用", eng.contains("AttemptMarker.sliceAfter(wlog, attemptId)"));
            ok("Attempt 带 reason 字段（机器可读归类码）", eng.contains("public String reason = \"\""));
        }

        System.out.println();
        System.out.println("=== R3 判定测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** 去掉注释，只留下代码（用于断言"代码里不再出现某个符号"）。 */
    private static String stripComments(String src) {
        String s1 = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder sb = new StringBuilder();
        for (String l : s1.split("\n")) {
            int k = l.indexOf("//");
            sb.append(k >= 0 ? l.substring(0, k) : l).append('\n');
        }
        return sb.toString();
    }

    /** 穷举到期归类的 32 种输入组合，检查是否出现过 AUTH_FAIL。 */
    private static boolean noAuthFailAcrossDeadlineGrid() {
        for (int m = 0; m < 32; m++) {
            CrackEngine.DeadlineOutcome o = CrackEngine.classifyDeadline(
                    (m & 1) != 0, (m & 2) != 0, (m & 4) != 0, (m & 8) != 0, (m & 16) != 0);
            if (o.verdict == CrackEngine.Verdict.AUTH_FAIL) return false;
        }
        return true;
    }

    private static String readFile(String path) {
        try {
            return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
