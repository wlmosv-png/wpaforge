package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.ui.VerdictView;

/**
 * P1：**成功结果与日志/界面表达必须一致**。
 *
 * <h3>用户现象（2026-11-02）</h3>
 * 23 位密码实测已连上并显示「已破解」，但攻击流里持续出现
 * 「连接已终止，结果无法归因」。
 *
 * <h3>本测试回答的五个问题</h3>
 * <ol>
 *   <li>最终 Verdict 是否为 SUCCESS / DIRECT？（裁决层）</li>
 *   <li>「连接已终止」指的是底层生命周期还是最终裁决？（措辞层）</li>
 *   <li>旧生命周期是否覆盖新成功证据？（优先级层）</li>
 *   <li>日志是否用旧字段、UI 是否用新 Verdict？（一致性层）</li>
 *   <li>confirmedBy / attemptId / runId 是否同一次尝试？（追溯层）</li>
 * </ol>
 */
public final class P1SuccessNarrativeTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String AID = "m5k3p1-3f9a2b7c-1#23";

    public static void main(String[] args) {
        System.out.println("=== P1 成功叙事一致性 ===");

        // ---- Q1：成功帧 + AT3 → SUCCESS / DIRECT ----
        AttemptState st = new AttemptState(AID, CrackEngine.Lifecycle.TERMINATED,
                AttemptState.Gate.OK, true);
        Evidence succ = new Evidence(Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                Evidence.Attribution.AT3, AID, 100L);
        Adjudicator.Result r = Adjudicator.adjudicate(st,
                java.util.Collections.singletonList(succ));
        info("Q1 终局: " + r);
        ok("Q1 最终 Verdict = SUCCESS", r.verdict == CrackEngine.Verdict.SUCCESS,
                r.verdict.toString());
        ok("Q1 等级 = DIRECT（可持久化）", r.grade == EvidenceGrade.Level.DIRECT,
                r.grade.toString());

        // ---- Q3：旧生命周期（TERMINATED）不得覆盖成功证据 ----
        // 上面那条用的正是 TERMINATED 生命周期，仍然给出 SUCCESS。
        ok("Q3 生命周期 TERMINATED + 成功证据 -> 仍判 SUCCESS（成功优先）",
                r.verdict == CrackEngine.Verdict.SUCCESS, r.verdict.toString());

        // 三条生命周期逐一验证：成功证据在任何生命周期下都优先
        for (CrackEngine.Lifecycle lc : CrackEngine.Lifecycle.values()) {
            AttemptState s2 = new AttemptState(AID, lc, AttemptState.Gate.OK, true);
            Adjudicator.Result r2 = Adjudicator.adjudicate(s2,
                    java.util.Collections.singletonList(succ));
            ok("Q3 生命周期 " + lc + " 下成功证据仍优先", r2.verdict == CrackEngine.Verdict.SUCCESS,
                    r2.verdict.toString());
        }

        // ---- Q2：措辞层 —— SUCCESS 的说明里不得出现「连接已终止」 ----
        String succDesc = VerdictView.description(CrackEngine.Verdict.SUCCESS);
        info("Q2 SUCCESS 说明: " + succDesc);
        ok("Q2 SUCCESS 说明不含「连接已终止」",
                !succDesc.contains("连接已终止"), succDesc);
        ok("Q2 SUCCESS 说明不含「无法归因」", !succDesc.contains("无法归因"), succDesc);

        // 「连接已终止」只属于 TERMINATED_UNKNOWN
        String termDesc = VerdictView.description(CrackEngine.Verdict.TERMINATED_UNKNOWN);
        info("Q2 TERMINATED_UNKNOWN 说明: " + termDesc);
        // P1b：该措辞已从全部结论里移除（连 TERMINATED_UNKNOWN 也不再说
        // 「连接已终止」—— 它描述的是底层生命周期，不是用户该看到的结论）。
        ok("Q2 TERMINATED_UNKNOWN 也不再用「连接已终止」（改用「没连上目标」）",
                !termDesc.contains("连接已终止") && termDesc.contains("没连上"), termDesc);
        int otherHits = 0;
        for (CrackEngine.Verdict v : CrackEngine.Verdict.values()) {
            if (v == CrackEngine.Verdict.TERMINATED_UNKNOWN) continue;
            String d = VerdictView.description(v);
            if (d != null && d.contains("连接已终止")) otherHits++;
        }
        ok("Q2 其它 Verdict 的说明都不含「连接已终止」", otherHits == 0,
                "违例 " + otherHits + " 个");

        // ---- Q4：日志符号与 UI 符号必须同源 ----
        int mismatch = 0;
        for (CrackEngine.Verdict v : CrackEngine.Verdict.values()) {
            String logTag = CrackEngine.verdictTag(7, "password1", v, "x");
            String uiSym = VerdictView.symbol(v);
            // 判词里必须体现该 Verdict 的符号或专属标记
            // 两类刻意差异（设计如此，不是缺陷）：
            //   · TIMEOUT / TARGET_GONE 在判词里用中文短词（「超时」「目标不在」），
            //     因为它们在日志里出现频率高，符号 ⏱ / — 不便肉眼扫读；
            //   · UNKNOWN / TERMINATED_UNKNOWN 共用 "?" 家族，判词用「?未定」「…终」区分。
            boolean byDesign = (v == CrackEngine.Verdict.TIMEOUT
                    || v == CrackEngine.Verdict.TARGET_GONE
                    || v == CrackEngine.Verdict.UNKNOWN
                    || v == CrackEngine.Verdict.TERMINATED_UNKNOWN);
            boolean contains = logTag.contains(uiSym);
            if (!contains && !byDesign) {
                mismatch++;
                info("Q4 不一致: " + v + " 日志=" + logTag + " UI符号=" + uiSym);
            }
        }
        ok("Q4 日志判词与 UI 符号同源（除共用前缀外无冲突）", mismatch == 0,
                "不一致 " + mismatch + " 个");

        // SUCCESS 专用：判词必须带 ✓
        String succTag = CrackEngine.verdictTag(7, "password1", CrackEngine.Verdict.SUCCESS, "supplicant");
        info("Q4 SUCCESS 判词: " + succTag);
        ok("Q4 SUCCESS 判词含 ✓", succTag.contains("✓"), succTag);
        ok("Q4 SUCCESS 判词不含「终」", !succTag.contains("终"), succTag);

        // ---- Q5：追溯字段 ----
        CrackEngine.Attempt at = Adjudicator.toAttempt("password1", r, 6851L, "suffix", "supplicant");
        ok("Q5 toAttempt 保留 verdict", at.verdict == CrackEngine.Verdict.SUCCESS,
                at.verdict.toString());
        ok("Q5 toAttempt 保留 grade=DIRECT", at.grade == EvidenceGrade.Level.DIRECT,
                at.grade.toString());
        ok("Q5 confirmedBy = supplicant", "supplicant".equals(at.confirmedBy), at.confirmedBy);

        // attemptId 往返：runId/seq 必须一致
        String run = AttemptId.runOf(AID);
        int seq = AttemptId.seqOf(AID);
        ok("Q5 attemptId 可解析出 runId", run != null, String.valueOf(run));
        ok("Q5 attemptId 可解析出 seq=23", seq == 23, String.valueOf(seq));
        ok("Q5 同一 attemptId 归属判定成立", succ.belongsTo(AID), "violated");

        // ---- 关键回归：成功证据与失败证据同时存在时，成功优先 ----
        Evidence wrong = new Evidence(Evidence.Source.WPALOG_B, Evidence.Claim.NOT_CONNECTED,
                Evidence.Attribution.NONE, AID, 90L);
        Adjudicator.Result both = Adjudicator.adjudicate(st,
                java.util.Arrays.asList(wrong, succ));
        info("同时存在失败(AP-1)与成功证据 -> " + both);
        ok("成功证据优先于 AP-1 失败证据",
                both.verdict == CrackEngine.Verdict.SUCCESS, both.verdict.toString());

        System.out.println();
        System.out.println("=== P1 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
