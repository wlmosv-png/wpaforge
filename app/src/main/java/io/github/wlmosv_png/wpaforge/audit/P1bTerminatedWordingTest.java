package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.ui.VerdictView;

/**
 * P1b：**「本次没连上」不得被说成「连接已终止」**。
 *
 * <h3>用户真实反馈（2026-11-02）</h3>
 * 暂停后日志反复出现「连接已终止，但原因无法归因（不等于密码错误）」，
 * 读起来像「网络断了 / 软件一直连接失败 / 密码可能错了」。
 *
 * <h3>真实生产路径</h3>
 * 本机（whoami 热点，同网段还有 B212/B214 等已保存网络）实测：
 * 发一条错误密码后，系统几秒内**回连到别的网络** → {@code FailoverRule}
 * 命中 → 提交 {@code FAILOVER / NOT_CONNECTED / AT1}，
 * 此时 {@code lastLifecycle} 因「WifiInfo 显示连着其它网」而为 {@code TERMINATED}
 * → {@link Adjudicator} 规则 7 产出 {@code TERMINATED_UNKNOWN}。
 *
 * <p>裁决本身**是正确的**（本次确实没连上目标，也确实没有证据证明密码对错）。
 * 出问题的是**表达**：VERDICT 名与文案都在描述「连接终止」这个底层事实，
 * 而用户关心的是「这条密码到底算不算试过、会不会重试」。
 *
 * <p>本测试只约束表达层，不碰任何裁决规则。
 */
public final class P1bTerminatedWordingTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String AID = "m5k3p1-3f9a2b7c-1#12";

    public static void main(String[] args) {
        System.out.println("=== P1b TERMINATED_UNKNOWN 表达 ===");

        // ---- ① 裁决规则不变：FAILOVER 仍是 TERMINATED_UNKNOWN ----
        AttemptState st = new AttemptState(AID, CrackEngine.Lifecycle.TERMINATED,
                AttemptState.Gate.OK, true);
        Evidence failover = new Evidence(Evidence.Source.FAILOVER, Evidence.Claim.NOT_CONNECTED,
                Evidence.Attribution.AT1, AID, 100L);
        Adjudicator.Result r = Adjudicator.adjudicate(st,
                java.util.Collections.singletonList(failover));
        info("① failover 裁决 = " + r);
        ok("① failover 仍裁决为 TERMINATED_UNKNOWN（规则不变）",
                r.verdict == CrackEngine.Verdict.TERMINATED_UNKNOWN, r.verdict.toString());
        ok("① grade 仍为 NONE（不写 triedSet、不推 cursor）",
                r.grade == EvidenceGrade.Level.NONE, r.grade.toString());

        // ---- ② UI 标签不得说「连接终止」 ----
        String label = VerdictView.label(CrackEngine.Verdict.TERMINATED_UNKNOWN);
        info("② label = " + label);
        ok("② 标签不含「连接终止」", !label.contains("连接终止"), label);
        ok("② 标签表达「未证实/未确定」", label.contains("未"), label);

        // ---- ③ UI 说明不得以「连接已终止」开头 ----
        String desc = VerdictView.description(CrackEngine.Verdict.TERMINATED_UNKNOWN);
        info("③ description = " + desc);
        ok("③ 说明不含「连接已终止」", !desc.contains("连接已终止"), desc);
        ok("③ 说明明确「不代表密码错」或「会重试」",
                desc.contains("不代表") || desc.contains("不等于") || desc.contains("重试"),
                desc);
        ok("③ 说明点明「本次没连上目标」（事实，不夸大）",
                desc.contains("没连上") || desc.contains("未连上"), desc);

        // ---- ④ 判词必须带证据来源，不能只说「终止」----
        String tag = CrackEngine.verdictTag(AID, 12, "password1",
                CrackEngine.Verdict.TERMINATED_UNKNOWN, "failover");
        info("④ 判词 = " + tag);
        ok("④ 判词带来源 failover", tag.contains("failover"), tag);
        ok("④ 判词不含「终」这类易误读字", !tag.contains("=…终"), tag);

        // ---- ⑤ 与 UNKNOWN 仍可区分（两者不得同符号同标签）----
        ok("⑤ TERMINATED_UNKNOWN 与 UNKNOWN 符号不同",
                !VerdictView.symbol(CrackEngine.Verdict.TERMINATED_UNKNOWN)
                        .equals(VerdictView.symbol(CrackEngine.Verdict.UNKNOWN)), "相同");
        ok("⑤ TERMINATED_UNKNOWN 与 UNKNOWN 标签不同",
                !VerdictView.label(CrackEngine.Verdict.TERMINATED_UNKNOWN)
                        .equals(VerdictView.label(CrackEngine.Verdict.UNKNOWN)), "相同");

        // ---- ⑥ 三种「没连上」的来源必须都能写进判词 ----
        for (String by : new String[]{"failover", "m1retry", "wpalog"}) {
            String t = CrackEngine.verdictTag(AID, 12, "password1",
                    CrackEngine.Verdict.TERMINATED_UNKNOWN, by);
            ok("⑥ 判词能区分来源: " + by, t.contains(by), t);
        }

        System.out.println();
        System.out.println("=== P1b 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
