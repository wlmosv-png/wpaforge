package io.github.wlmosv_png.wpaforge.audit;

/**
 * D2：Attempt 身份与生命周期的唯一性。
 *
 * <h3>要钉的事实</h3>
 * <ol>
 *   <li><b>同毫秒创建</b>：同一毫秒内连续两次取 runId，必须因 {@code runOrdinal}
 *       而不同（不依赖时间分辨率）。</li>
 *   <li><b>跨进程重启</b>：同毫秒、同 SSID、同 ordinal 时，唯一能分开它们的
 *       是 bootId。因此 bootId 缺失时必须由**持久化序号基线**兜底，
 *       否则重启后的第 N 条候选会与重启前的第 N 条同 id。</li>
 *   <li><b>停止 -- 重启 -- 续跑</b>：新 run 不得复用旧 runId，
 *       旧 attemptId 的证据不得被新 Attempt 采信。</li>
 *   <li><b>延迟事件</b>：任何带旧 attemptId 的证据，在 {@link Adjudicator}
 *       里必须被当作未归属丢弃。</li>
 * </ol>
 */
public final class D2IdentityLifecycleTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final long T = 1_764_000_000_123L;
    static final String SSID = "LAB-D2";
    static final String BOOT_A = "d159099b-29b5-45f5-8c8f-da602993a032";
    static final String BOOT_B = "0f3c7a11-8e2d-4c55-9a10-7b6e5d4c3b2a";

    public static void main(String[] args) {
        System.out.println("=== D2 Attempt 身份与生命周期 ===");

        // ---- ① 同毫秒、同进程：靠 runOrdinal 区分 ----
        String a0 = AttemptId.newRunId(T, SSID, 0, BOOT_A);
        String a1 = AttemptId.newRunId(T, SSID, 1, BOOT_A);
        ok("同毫秒同进程：ordinal 0/1 产生不同 runId",
                a0 != null && a1 != null && !a0.equals(a1), a0 + " vs " + a1);
        ok("同毫秒同进程：同一输入确定性复现",
                a0.equals(AttemptId.newRunId(T, SSID, 0, BOOT_A)), "不确定");

        int collide = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int k = 0; k < 200; k++) {
            String id = AttemptId.newRunId(T, SSID, k, BOOT_A);
            if (id == null || !seen.add(id)) collide++;
        }
        ok("同毫秒 200 次创建零碰撞", collide == 0, "碰撞 " + collide);

        // ---- ② 跨进程重启：bootId 不同 -> 不同 ----
        String r1 = AttemptId.newRunId(T, SSID, 0, BOOT_A);
        String r2 = AttemptId.newRunId(T, SSID, 0, BOOT_B);
        ok("跨启动（bootId 不同）同毫秒同 ordinal 不撞",
                r1 != null && r2 != null && !r1.equals(r2), r1 + " vs " + r2);

        // ---- ③ bootId 缺失时，runId 本身**必然**与 ordinal 一一对应 ----
        // 这是 AttemptId 的纯函数事实：同一 (t, ssid, ordinal) 必得同一 runId。
        // 唯一性因此**不能靠** newRunId 自己解决，必须靠调用方把 ordinal 推到新值。
        String n1 = AttemptId.newRunId(T, SSID, 0, null);
        String n2 = AttemptId.newRunId(T, SSID, 0, null);
        info("bootId=null 时同毫秒同 ordinal -> " + n1);
        ok("纯函数事实：bootId 缺失时 (t,ssid,ordinal) 决定 runId（确定性）",
                n1 != null && n1.equals(n2), "不确定：" + n1 + " vs " + n2);

        // ---- ④ CrackEngine 的持久序号基线消除该退化 ----
        // 生产路径：每次进程启动 setRunOrdinalBase(盘上最大值)，run 结束后回写。
        // 于是「重启后第一次 run」的 ordinal 天然大于重启前用过的所有值。
        try {
            java.lang.reflect.Constructor<?> ctor =
                    CrackEngine.class.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            CrackEngine e1 = (CrackEngine) ctor.newInstance(0);
            e1.setRunOrdinalBase(7);
            int used1 = e1.runOrdinalNow();
            ok("setRunOrdinalBase(7) 后 runOrdinalNow()==7（下一次 run 用 7）",
                    used1 == 7, String.valueOf(used1));

            CrackEngine e2 = (CrackEngine) ctor.newInstance(0);
            ok("未设基线的引擎从 0 起（向后兼容）", e2.runOrdinalNow() == 0,
                    String.valueOf(e2.runOrdinalNow()));
            e2.setRunOrdinalBase(-9);
            ok("负基线归 0（不产生非法 ordinal）", e2.runOrdinalNow() == 0,
                    String.valueOf(e2.runOrdinalNow()));
        } catch (Throwable t) {
            ok("CrackEngine 序号基线 API 可用", false, t.toString());
        }

        int baseBeforeRestart = 7;
        String afterRestart = AttemptId.newRunId(T, SSID, baseBeforeRestart + 1, null);
        String beforeRestart = AttemptId.newRunId(T, SSID, baseBeforeRestart, null);
        ok("持久序号基线：重启后从 baseline+1 起即不与旧号相同",
                afterRestart != null && !afterRestart.equals(beforeRestart),
                beforeRestart + " vs " + afterRestart);

        // ---- ⑤ 续跑票：绝不复用旧 runId ----
        AttemptId.ResumeTicket t = AttemptId.adoptForResume(r1, 11, T, SSID, 0);
        ok("续跑不复用旧 runId", t != null && t.newRunId != null
                && !t.newRunId.equals(r1), String.valueOf(t == null ? null : t.newRunId));
        ok("续跑保留旧 runId 血缘", t != null && r1.equals(t.previousRunId),
                String.valueOf(t == null ? null : t.previousRunId));
        ok("续跑沿用游标", t != null && t.resumeFromOffset == 11,
                String.valueOf(t == null ? -1 : t.resumeFromOffset));
        AttemptId.ResumeTicket tNeg = AttemptId.adoptForResume(null, -5, T, SSID, 0);
        ok("续跑游标负值归 0（不猜不丢）", tNeg != null && tNeg.resumeFromOffset == 0,
                String.valueOf(tNeg == null ? -1 : tNeg.resumeFromOffset));

        // ---- ⑥ 延迟事件：旧 attemptId 的证据必须被丢弃 ----
        String oldId = AttemptId.of(r1, 11);
        String newId = AttemptId.of(t.newRunId, 11);
        ok("新旧同名序号产生不同 attemptId",
                oldId != null && newId != null && !oldId.equals(newId), oldId + " vs " + newId);

        Evidence stale = new Evidence(Evidence.Source.WPALOG_A, Evidence.Claim.WRONG_KEY,
                Evidence.Attribution.AT3, oldId, 100L);
        AttemptState st = new AttemptState(newId, CrackEngine.Lifecycle.TERMINATED,
                AttemptState.Gate.OK, true);
        Adjudicator.Result r = Adjudicator.adjudicate(st,
                java.util.Collections.singletonList(stale));
        ok("旧 Attempt 的 AT3 强失败证据在新 Attempt 下被丢弃",
                r.verdict != CrackEngine.Verdict.AUTH_FAIL, r.toString());
        ok("丢弃后落到无证据分支（不误判）",
                r.grade == EvidenceGrade.Level.NONE, r.grade.toString());

        // 同一份证据在旧 Attempt 下仍然有效（证明是「归属过滤」而不是「一律无视」）
        AttemptState stOld = new AttemptState(oldId, CrackEngine.Lifecycle.TERMINATED,
                AttemptState.Gate.OK, true);
        Adjudicator.Result rOld = Adjudicator.adjudicate(stOld,
                java.util.Collections.singletonList(stale));
        ok("同一证据在它自己的 Attempt 下仍判 AUTH_FAIL/DIRECT（对照）",
                rOld.verdict == CrackEngine.Verdict.AUTH_FAIL
                        && rOld.grade == EvidenceGrade.Level.DIRECT, rOld.toString());

        // ---- ⑦ 空 attemptId 的证据永不被采信 ----
        Evidence noId = new Evidence(Evidence.Source.AUTHCAST, Evidence.Claim.WRONG_KEY,
                Evidence.Attribution.AT3, "", 100L);
        ok("空 attemptId 证据永不被采信",
                Adjudicator.adjudicate(stOld,
                        java.util.Collections.singletonList(noId)).verdict
                        != CrackEngine.Verdict.AUTH_FAIL, "violated");

        // ---- ⑧ 进程终止后恢复：状态机三态语义 ----
        AttemptState active = new AttemptState(newId, CrackEngine.Lifecycle.ACTIVE,
                AttemptState.Gate.OK, false);
        AttemptState unknown = new AttemptState(newId, CrackEngine.Lifecycle.UNKNOWN,
                AttemptState.Gate.OK, false);
        ok("ACTIVE != TERMINATED（不得把未知当终止）",
                active.lifecycle != unknown.lifecycle, "same");
        ok("UNKNOWN 生命周期下无证据不产 AUTH_FAIL",
                Adjudicator.adjudicate(unknown, java.util.Collections.<Evidence>emptyList())
                        .verdict != CrackEngine.Verdict.AUTH_FAIL, "violated");

        System.out.println();
        System.out.println("=== D2 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
