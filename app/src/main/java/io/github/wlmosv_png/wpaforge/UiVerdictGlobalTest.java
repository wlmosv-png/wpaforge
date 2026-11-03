package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;
import io.github.wlmosv_png.wpaforge.audit.VerdictTally;
import io.github.wlmosv_png.wpaforge.ui.VerdictView;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 全局 UI 判定一致性（U3）。
 *
 * <h3>要钉死的事实</h3>
 * <ol>
 *   <li><b>每个 Verdict 都有唯一表达</b>：符号、标签互不重复（除刻意合并的情况，
 *       这里要求**符号唯一**，因为符号是列表里第一眼看到的东西）。</li>
 *   <li><b>不同结论不得被错误合并</b>：SUCCESS / AUTH_FAIL / NETWORK_AUTH_FAIL /
 *       TIMEOUT / INCOMPLETE / TERMINATED_UNKNOWN / UNKNOWN / ERROR 的
 *       「算不算已证伪」必须彼此不同或符合契约。</li>
 *   <li><b>统计与判定同源</b>：{@code VerdictView.countsAsProvenWrong} 与
 *       {@code VerdictTally.confirmedFail} 必须一致；{@code countedAsTested}
 *       与 {@code tally.executed} 必须一致。</li>
 *   <li><b>无结论总数与 UI 口径一致</b>：网络级失败与推断失败都必须进入
 *       「无结论」计数（否则结果卡会误说「没有」）。</li>
 * </ol>
 */
public final class UiVerdictGlobalTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final CrackEngine.Verdict[] ALL = {
            CrackEngine.Verdict.SUCCESS,
            CrackEngine.Verdict.AUTH_FAIL,
            CrackEngine.Verdict.NETWORK_AUTH_FAIL,
            CrackEngine.Verdict.TIMEOUT,
            CrackEngine.Verdict.INCOMPLETE,
            CrackEngine.Verdict.TERMINATED_UNKNOWN,
            CrackEngine.Verdict.UNKNOWN,
            CrackEngine.Verdict.ERROR,
            CrackEngine.Verdict.ABORTED,
            CrackEngine.Verdict.TARGET_GONE,
    };

    private static int tallyExecutedFor(CrackEngine.Verdict v) {
        VerdictTally t = new VerdictTally();
        t.add(v, EvidenceGrade.defaultOf(v));
        return t.executed();
    }

    public static void main(String[] args) {
        System.out.println("=== 全局 UI 判定一致性（U3）===");

        // ---- ① 符号唯一 ----
        Map<String, CrackEngine.Verdict> bySymbol = new java.util.HashMap<>();
        int dup = 0;
        for (CrackEngine.Verdict v : ALL) {
            String s = VerdictView.symbol(v);
            CrackEngine.Verdict prev = bySymbol.put(s, v);
            if (prev != null) {
                dup++;
                info("符号重复: [" + s + "] " + prev + " 与 " + v);
            }
        }
        ok("每个 Verdict 的列表符号唯一", dup == 0, "重复 " + dup + " 组");

        // ---- ② 标签唯一（"无结论"这类合并也禁止出现在两种结论上）----
        Map<String, CrackEngine.Verdict> byLabel = new java.util.HashMap<>();
        int dupLabel = 0;
        for (CrackEngine.Verdict v : ALL) {
            String s = VerdictView.label(v);
            CrackEngine.Verdict prev = byLabel.put(s, v);
            if (prev != null) { dupLabel++; info("标签重复: [" + s + "] " + prev + " 与 " + v); }
        }
        ok("每个 Verdict 的短标签唯一", dupLabel == 0, "重复 " + dupLabel + " 组");

        // ---- ③ 说明非空且互不相同 ----
        Set<String> descs = new HashSet<>();
        int emptyDesc = 0, dupDesc = 0;
        for (CrackEngine.Verdict v : ALL) {
            String d = VerdictView.description(v);
            if (d == null || d.isEmpty()) { emptyDesc++; continue; }
            if (!descs.add(d)) { dupDesc++; info("说明重复: " + v); }
        }
        ok("每个 Verdict 都有一句话说明", emptyDesc == 0, "空 " + emptyDesc);
        ok("说明互不相同", dupDesc == 0, "重复 " + dupDesc);

        // ---- ④ 只有 AUTH_FAIL 算「已证实错误」----
        int wrongMarked = 0;
        for (CrackEngine.Verdict v : ALL) {
            boolean marked = VerdictView.countsAsProvenWrong(v);
            if (marked != (v == CrackEngine.Verdict.AUTH_FAIL)) {
                wrongMarked++;
                info("错标的: " + v + " -> " + marked);
            }
        }
        ok("只有 AUTH_FAIL 计入「已证实错误」", wrongMarked == 0, "错标 " + wrongMarked);

        // ---- ⑤ SUCCESS 不得同时被标成「已证实错误」或「可重试」----
        ok("SUCCESS 不算已证实错误",
                !VerdictView.countsAsProvenWrong(CrackEngine.Verdict.SUCCESS), "violated");
        ok("SUCCESS 不可重试",
                !VerdictView.retryable(CrackEngine.Verdict.SUCCESS), "violated");
        ok("AUTH_FAIL 不可重试",
                !VerdictView.retryable(CrackEngine.Verdict.AUTH_FAIL), "violated");
        ok("NETWORK_AUTH_FAIL 可重试（未证伪）",
                VerdictView.retryable(CrackEngine.Verdict.NETWORK_AUTH_FAIL), "violated");
        ok("INCOMPLETE 可重试（连接还在跑）",
                VerdictView.retryable(CrackEngine.Verdict.INCOMPLETE), "violated");

        // ---- ⑥ countsAsTested 与 VerdictTally.executed 一致 ----
        // 例外：SUCCESS 由命中路径单独处理，不进入「试过多少条」的统计
        // （命中时任务即刻结束，那条不是"试了一条失败"）。
        for (CrackEngine.Verdict v : ALL) {
            if (v == CrackEngine.Verdict.SUCCESS) continue;
            VerdictTally t = new VerdictTally();
            t.add(v, EvidenceGrade.defaultOf(v));
            boolean tallied = t.executed() > 0;
            boolean viewSays = VerdictView.countsAsTested(v);
            ok("「是否算试过」口径一致: " + v, tallied == viewSays,
                    "tally=" + tallied + " view=" + viewSays);
        }
        ok("SUCCESS 由命中路径单独处理，不计入 executed",
                tallyExecutedFor(CrackEngine.Verdict.SUCCESS) == 0, "violated");

        // ---- ⑦ 「已证实错误」口径一致 ----
        for (CrackEngine.Verdict v : ALL) {
            VerdictTally t = new VerdictTally();
            t.add(v, EvidenceGrade.Level.DIRECT);
            boolean tallied = t.confirmedFail() > 0;
            boolean viewSays = VerdictView.countsAsProvenWrong(v);
            ok("「已证实错误」口径一致: " + v, tallied == viewSays,
                    "tally=" + tallied + " view=" + viewSays);
        }

        // ---- ⑧ 无结论总数必须含网络级失败与推断失败 ----
        VerdictTally t2 = new VerdictTally();
        t2.add(CrackEngine.Verdict.NETWORK_AUTH_FAIL, EvidenceGrade.Level.NONE);
        t2.add(CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.INFERRED);
        t2.add(CrackEngine.Verdict.TIMEOUT, EvidenceGrade.Level.NONE);
        info("三类未证伪 -> inconclusive=" + t2.inconclusive()
                + " net=" + t2.networkAuthFail() + " inferred=" + t2.inferred());
        ok("NETWORK_AUTH_FAIL 不计入已证实失败", t2.confirmedFail() == 0, "violated");
        ok("推断失败不计入已证实失败", t2.confirmedFail() == 0, "violated");
        ok("未证伪条数（引擎口径）= 3", t2.inconclusive() + t2.networkAuthFail()
                + t2.inferred() == 3, String.valueOf(t2.inconclusive()));

        // ---- ⑨ missProven：有未证伪时不得说「已证明未命中」----
        ok("存在未证伪条目时 missProven=false", !t2.missProven(), "violated");
        VerdictTally t3 = new VerdictTally();
        t3.add(CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.DIRECT);
        ok("全部 DIRECT 证伪时 missProven=true", t3.missProven(), "violated");

        // ---- ⑩ needsAttention 覆盖全部「最容易被误读成失败」的类别 ----
        for (CrackEngine.Verdict v : new CrackEngine.Verdict[]{
                CrackEngine.Verdict.INCOMPLETE,
                CrackEngine.Verdict.TERMINATED_UNKNOWN,
                CrackEngine.Verdict.NETWORK_AUTH_FAIL,
                CrackEngine.Verdict.ERROR}) {
            ok("需要提示复核: " + v, VerdictView.needsAttention(v), "missing");
        }

        System.out.println();
        System.out.println("=== U3 全局 UI: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
