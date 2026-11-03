package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 事件扰动：**重复 / 乱序 / 缺失 / 串窗**都不得改变结论。
 *
 * <h3>防的真实错误</h3>
 * 真机上同一条日志可能被读到多次（轮询重叠）、广播可能晚于日志到达、
 * 上一轮候选的失败行可能落在本轮的日志窗口里。任何一条都能让
 * 「一条正确密码」被判死，或让「一条错误密码」被判活。
 *
 * <p>本测试在 {@link Adjudicator} 层用**生产同构的 Evidence** 穷举扰动，
 * 断言结论对以下四种畸变免疫：
 * <ol>
 *   <li><b>重复</b>：同一证据出现 2/5/20 次 → 结论与 1 次完全相同。</li>
 *   <li><b>乱序</b>：证据列表顺序任意排列 → 结论相同。</li>
 *   <li><b>缺失</b>：去掉某类证据 → 结论只能变保守（不得变得更"确定失败"）。</li>
 *   <li><b>串窗</b>：混入别的 attemptId / 空 attemptId 的证据 → 被丢弃，结论不变。</li>
 * </ol>
 */
public final class EventDistortionTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String AID = "m5k3p1-3f9a2b7c-1#9";
    static final String OTHER = "m5k3p1-3f9a2b7c-1#10";

    private static AttemptState st(CrackEngine.Lifecycle lc) {
        return new AttemptState(AID, lc, AttemptState.Gate.OK, true);
    }

    private static Evidence ev(Evidence.Source s, Evidence.Claim c,
                               Evidence.Attribution a, String aid) {
        return new Evidence(s, c, a, aid, 100L);
    }

    private static Adjudicator.Result adj(AttemptState s, List<Evidence> e) {
        return Adjudicator.adjudicate(s, e);
    }

    public static void main(String[] args) {
        System.out.println("=== 事件扰动免疫 ===");

        Evidence succ = ev(Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                Evidence.Attribution.AT3, AID);
        Evidence ap1 = ev(Evidence.Source.WPALOG_B, Evidence.Claim.NOT_CONNECTED,
                Evidence.Attribution.NONE, AID);
        Evidence weakWrong = ev(Evidence.Source.WPALOG_A, Evidence.Claim.WRONG_KEY,
                Evidence.Attribution.AT1, AID);
        Evidence reject = ev(Evidence.Source.AUTHCAST, Evidence.Claim.NETWORK_REJECTED,
                Evidence.Attribution.AT1, AID);

        // ---- ① 重复：同一证据出现 N 次，结论不变 ----
        for (int n : new int[]{1, 2, 5, 20}) {
            List<Evidence> list = new ArrayList<>();
            for (int i = 0; i < n; i++) list.add(succ);
            Adjudicator.Result r = adj(st(CrackEngine.Lifecycle.ACTIVE), list);
            ok("① 成功证据 ×" + n + " -> SUCCESS（不因重复改变）",
                    r.verdict == CrackEngine.Verdict.SUCCESS, r.verdict.toString());
        }
        for (int n : new int[]{1, 2, 5, 20}) {
            List<Evidence> list = new ArrayList<>();
            for (int i = 0; i < n; i++) list.add(weakWrong);
            Adjudicator.Result r = adj(st(CrackEngine.Lifecycle.TERMINATED), list);
            ok("① 弱失败证据 ×" + n + " -> NETWORK_AUTH_FAIL（不因重复升格）",
                    r.verdict == CrackEngine.Verdict.NETWORK_AUTH_FAIL, r.verdict.toString());
        }

        // ---- ② 乱序：任意排列，结论相同 ----
        List<Evidence> three = new ArrayList<>();
        three.add(succ);
        three.add(ap1);
        three.add(weakWrong);
        CrackEngine.Verdict baseline = adj(st(CrackEngine.Lifecycle.TERMINATED), three).verdict;
        info("② 基准顺序结论 = " + baseline);
        int orders = 0, mismatch = 0;
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) {
                for (int c = 0; c < 3; c++) {
                    if (a == b || b == c || a == c) continue;
                    List<Evidence> perm = new ArrayList<>();
                    perm.add(three.get(a));
                    perm.add(three.get(b));
                    perm.add(three.get(c));
                    CrackEngine.Verdict v = adj(st(CrackEngine.Lifecycle.TERMINATED), perm).verdict;
                    orders++;
                    if (v != baseline) {
                        mismatch++;
                        info("② 排列 " + a + b + c + " 结论不同: " + v);
                    }
                }
            }
        }
        ok("② 三种证据的 6 种排列结论一致（成功证据优先）", mismatch == 0,
                "不一致 " + mismatch + "/" + orders);
        ok("② 基准结论是 SUCCESS", baseline == CrackEngine.Verdict.SUCCESS,
                String.valueOf(baseline));

        // ---- ③ 缺失：去掉证据只能变保守 ----
        Adjudicator.Result onlySucc = adj(st(CrackEngine.Lifecycle.TERMINATED),
                Collections.singletonList(succ));
        ok("③ 只剩成功证据 -> 仍 SUCCESS", onlySucc.verdict == CrackEngine.Verdict.SUCCESS,
                onlySucc.verdict.toString());

        Adjudicator.Result onlyAp1 = adj(st(CrackEngine.Lifecycle.TERMINATED),
                Collections.singletonList(ap1));
        info("③ 只剩 AP-1 -> " + onlyAp1.verdict);
        ok("③ 只剩 AP-1 -> TERMINATED_UNKNOWN（保守，不判失败）",
                onlyAp1.verdict == CrackEngine.Verdict.TERMINATED_UNKNOWN,
                onlyAp1.verdict.toString());
        ok("③ 只剩 AP-1 时绝不产 AUTH_FAIL",
                onlyAp1.verdict != CrackEngine.Verdict.AUTH_FAIL, "violated");

        Adjudicator.Result empty = adj(st(CrackEngine.Lifecycle.TERMINATED),
                new ArrayList<Evidence>());
        info("③ 完全没有证据 -> " + empty.verdict);
        ok("③ 完全没有证据 + 已终止 -> TIMEOUT", empty.verdict == CrackEngine.Verdict.TIMEOUT,
                empty.verdict.toString());

        // 缺失方向性：加入证据只可能让结论更"有结论"，不会更"失败"
        Adjudicator.Result withReject = adj(st(CrackEngine.Lifecycle.TERMINATED),
                Collections.singletonList(reject));
        ok("③ 只有 NETWORK_REJECTED -> UNKNOWN（不判失败）",
                withReject.verdict == CrackEngine.Verdict.UNKNOWN, withReject.verdict.toString());

        // ---- ④ 串窗：别人的 attemptId / 空 id 一律丢弃 ----
        Evidence foreign = ev(Evidence.Source.SUCCESS_STATUS, Evidence.Claim.CONNECTED,
                Evidence.Attribution.AT3, OTHER);
        Adjudicator.Result onlyForeign = adj(st(CrackEngine.Lifecycle.TERMINATED),
                Collections.singletonList(foreign));
        info("④ 只有别的 Attempt 的成功证据 -> " + onlyForeign.verdict);
        ok("④ 别的 Attempt 的成功证据不被采信（不判 SUCCESS）",
                onlyForeign.verdict != CrackEngine.Verdict.SUCCESS,
                onlyForeign.verdict.toString());

        Evidence emptyId = ev(Evidence.Source.WPALOG_A, Evidence.Claim.WRONG_KEY,
                Evidence.Attribution.AT3, "");
        Adjudicator.Result onlyEmpty = adj(st(CrackEngine.Lifecycle.TERMINATED),
                Collections.singletonList(emptyId));
        ok("④ 空 attemptId 的强失败证据不被采信（不判 AUTH_FAIL）",
                onlyEmpty.verdict != CrackEngine.Verdict.AUTH_FAIL,
                onlyEmpty.verdict.toString());

        // 混入串窗证据：结论必须与本条自己的证据一致
        List<Evidence> mixed = new ArrayList<>();
        mixed.add(foreign);       // 别人成功了 —— 不得算本条
        mixed.add(ap1);           // 本条只有 AP-1
        Adjudicator.Result r4 = adj(st(CrackEngine.Lifecycle.TERMINATED), mixed);
        info("④ 别人成功 + 本条 AP-1 -> " + r4);
        ok("④ 串窗不得让本条被判成功", r4.verdict != CrackEngine.Verdict.SUCCESS,
                r4.verdict.toString());
        ok("④ 串窗后结论与「只有本条证据」一致",
                r4.verdict == onlyAp1.verdict, r4.verdict + " vs " + onlyAp1.verdict);

        // ---- ⑤ 缺失关键证据时，等级必须保持 NONE（不可持久化）----
        ok("⑤ 只有 AP-1 时 grade=NONE",
                onlyAp1.grade == EvidenceGrade.Level.NONE, onlyAp1.grade.toString());
        ok("⑤ 空证据时 grade=NONE",
                empty.grade == EvidenceGrade.Level.NONE, empty.grade.toString());
        ok("⑤ 网络拒绝时 grade=NONE",
                withReject.grade == EvidenceGrade.Level.NONE, withReject.grade.toString());

        System.out.println();
        System.out.println("=== 事件扰动: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
