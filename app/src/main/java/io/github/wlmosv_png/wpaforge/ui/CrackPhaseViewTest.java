package io.github.wlmosv_png.wpaforge.ui;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;
import io.github.wlmosv_png.wpaforge.audit.TaskQueue;

/**
 * {@link CrackPhaseView} 门禁（2026-11-21）。
 *
 * <p><b>重建声明</b>：源码已丢失，按保留的编译产物（{@code javap -c -p} 的
 * 字节码与字符串常量）重新编写，<b>不是</b>原始源码。用例名与断言措辞
 * 从字节码还原；实现代码为重新编写。
 *
 * <h3>钉死什么</h3>
 * <ol>
 *   <li><b>镜像不漂移</b>：{@code TaskQueueState} 与真实 {@code TaskQueue.State}
 *       逐个值对应。</li>
 *   <li><b>只有 DIRECT 才叫「已证实错误」</b>：{@code AUTH_FAIL + INFERRED/NONE}
 *       必须落到「未确定」。</li>
 *   <li><b>每种 Verdict 都有确定的落点</b>：穷举全部枚举值。</li>
 *   <li><b>active 与 concluded 互斥</b>。</li>
 * </ol>
 */
public final class CrackPhaseViewTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void ok(String name, boolean cond) { ok(name, cond, ""); }

    private static void info(String s) { System.out.println("  ·    " + s); }

    public static void main(String[] args) {
        System.out.println("== CrackPhaseView 状态派生 ==");
        try {
            // ------------------------------------------------ 1. 镜像与真实枚举
            System.out.println();
            System.out.println("-- 1. TaskQueueState 镜像与真实枚举一一对应 --");
            for (TaskQueue.State real : TaskQueue.State.values()) {
                CrackPhaseView.TaskQueueState m = CrackPhaseView.TaskQueueState.of(real);
                boolean same = m != null && real.name().equals(m.name());
                ok("真实 " + real.name() + " → 镜像 " + m, same, "got=" + m);
            }
            ok("null → null", CrackPhaseView.TaskQueueState.of(null) == null);
            for (CrackPhaseView.TaskQueueState m : CrackPhaseView.TaskQueueState.values()) {
                boolean found = false;
                for (TaskQueue.State real : TaskQueue.State.values()) {
                    if (real.name().equals(m.name())) { found = true; break; }
                }
                ok("镜像 " + m + " 有对应的真实枚举", found);
            }

            // ------------------------------------------------ 2. 队列事实优先
            System.out.println();
            System.out.println("-- 2. 队列事实优先于上轮结论 --");
            ok("RUNNING → RUNNING",
                    CrackPhaseView.derive(CrackPhaseView.TaskQueueState.RUNNING,
                            false, CrackEngine.Verdict.AUTH_FAIL,
                            EvidenceGrade.Level.DIRECT, true) == CrackPhaseView.Phase.RUNNING);
            ok("RUNNING + finishing → FINISHING",
                    CrackPhaseView.derive(CrackPhaseView.TaskQueueState.RUNNING,
                            true, null, null, false) == CrackPhaseView.Phase.FINISHING);
            ok("PAUSED → PAUSED",
                    CrackPhaseView.derive(CrackPhaseView.TaskQueueState.PAUSED,
                            false, CrackEngine.Verdict.TIMEOUT,
                            EvidenceGrade.Level.NONE, true) == CrackPhaseView.Phase.PAUSED);
            ok("PENDING → PREPARING",
                    CrackPhaseView.derive(CrackPhaseView.TaskQueueState.PENDING,
                            false, null, null, false) == CrackPhaseView.Phase.PREPARING);

            // ------------------------------------------------ 3. 等级区分（核心）
            System.out.println();
            System.out.println("-- 3. ★ AUTH_FAIL 的等级区分（本轮核心修复）--");
            ok("★ AUTH_FAIL + DIRECT → PROVEN_WRONG（已证实错误）",
                    CrackPhaseView.ofVerdict(CrackEngine.Verdict.AUTH_FAIL,
                            EvidenceGrade.Level.DIRECT)
                            == CrackPhaseView.Phase.PROVEN_WRONG);
            ok("★ AUTH_FAIL + INFERRED → UNCERTAIN（不得显示已证实）",
                    CrackPhaseView.ofVerdict(CrackEngine.Verdict.AUTH_FAIL,
                            EvidenceGrade.Level.INFERRED)
                            == CrackPhaseView.Phase.UNCERTAIN);
            ok("★ AUTH_FAIL + NONE → UNCERTAIN",
                    CrackPhaseView.ofVerdict(CrackEngine.Verdict.AUTH_FAIL,
                            EvidenceGrade.Level.NONE)
                            == CrackPhaseView.Phase.UNCERTAIN);
            ok("旧 1 参入口保持兼容（AUTH_FAIL → PROVEN_WRONG）",
                    CrackPhaseView.ofVerdict(CrackEngine.Verdict.AUTH_FAIL)
                            == CrackPhaseView.Phase.PROVEN_WRONG);

            // ------------------------------------------------ 4. 穷举 Verdict
            System.out.println();
            System.out.println("-- 4. 穷举全部 Verdict（不允许漏项）--");
            int covered = 0;
            for (CrackEngine.Verdict v : CrackEngine.Verdict.values()) {
                CrackPhaseView.Phase p = CrackPhaseView.ofVerdict(v);
                if (p == null) info("未映射: " + v); else covered++;
            }
            ok("全部 " + CrackEngine.Verdict.values().length + " 个 Verdict 都有落点",
                    covered == CrackEngine.Verdict.values().length, "covered=" + covered);

            ok("SUCCESS → VERIFIED",
                    CrackPhaseView.ofVerdict(CrackEngine.Verdict.SUCCESS)
                            == CrackPhaseView.Phase.VERIFIED);
            ok("NETWORK_AUTH_FAIL → NETWORK_FAIL（不是已证实错误）",
                    CrackPhaseView.ofVerdict(CrackEngine.Verdict.NETWORK_AUTH_FAIL)
                            == CrackPhaseView.Phase.NETWORK_FAIL);
            ok("TIMEOUT → UNCERTAIN",
                    CrackPhaseView.ofVerdict(CrackEngine.Verdict.TIMEOUT)
                            == CrackPhaseView.Phase.UNCERTAIN);
            ok("UNKNOWN → UNCERTAIN",
                    CrackPhaseView.ofVerdict(CrackEngine.Verdict.UNKNOWN)
                            == CrackPhaseView.Phase.UNCERTAIN);
            ok("INCOMPLETE → UNCERTAIN",
                    CrackPhaseView.ofVerdict(CrackEngine.Verdict.INCOMPLETE)
                            == CrackPhaseView.Phase.UNCERTAIN);
            ok("TERMINATED_UNKNOWN → UNCERTAIN",
                    CrackPhaseView.ofVerdict(CrackEngine.Verdict.TERMINATED_UNKNOWN)
                            == CrackPhaseView.Phase.UNCERTAIN);
            ok("ERROR → ENV_ERROR",
                    CrackPhaseView.ofVerdict(CrackEngine.Verdict.ERROR)
                            == CrackPhaseView.Phase.ENV_ERROR);
            ok("TARGET_GONE → ENV_ERROR",
                    CrackPhaseView.ofVerdict(CrackEngine.Verdict.TARGET_GONE)
                            == CrackPhaseView.Phase.ENV_ERROR);
            ok("ABORTED → ABORTED",
                    CrackPhaseView.ofVerdict(CrackEngine.Verdict.ABORTED)
                            == CrackPhaseView.Phase.ABORTED);
            ok("null → null（尚未开始）",
                    CrackPhaseView.ofVerdict(null) == null);

            // ------------------------------------------------ 5. 互斥
            System.out.println();
            System.out.println("-- 5. active 与 concluded 互斥 --");
            boolean exclusive = true;
            for (CrackPhaseView.Phase p : CrackPhaseView.Phase.values()) {
                if (CrackPhaseView.active(p) && CrackPhaseView.concluded(p)) {
                    exclusive = false; info("既 active 又 concluded: " + p);
                }
            }
            ok("没有任何 Phase 同时是 active 与 concluded", exclusive);
            ok("RUNNING 是 active", CrackPhaseView.active(CrackPhaseView.Phase.RUNNING));
            ok("FINISHING 是 active", CrackPhaseView.active(CrackPhaseView.Phase.FINISHING));
            ok("VERIFIED 是 concluded", CrackPhaseView.concluded(CrackPhaseView.Phase.VERIFIED));
            ok("PROVEN_WRONG 是 concluded",
                    CrackPhaseView.concluded(CrackPhaseView.Phase.PROVEN_WRONG));
            ok("UNCERTAIN 是 concluded",
                    CrackPhaseView.concluded(CrackPhaseView.Phase.UNCERTAIN));
            ok("IDLE 既非 active 也非 concluded",
                    !CrackPhaseView.active(CrackPhaseView.Phase.IDLE)
                            && !CrackPhaseView.concluded(CrackPhaseView.Phase.IDLE));

            // ------------------------------------------------ 6. hint
            System.out.println();
            System.out.println("-- 6. 每个 Phase 都有双语 hint --");
            boolean hintOk = true;
            for (CrackPhaseView.Phase p : CrackPhaseView.Phase.values()) {
                String zh = CrackPhaseView.hint(p, true);
                String en = CrackPhaseView.hint(p, false);
                if (zh == null || zh.trim().isEmpty() || en == null || en.trim().isEmpty()) {
                    hintOk = false; info("缺 hint: " + p);
                }
            }
            ok("全部 Phase 的中英文 hint 都非空", hintOk);

            // ------------------------------------------------ 7. Tone
            System.out.println();
            System.out.println("-- 7. Tone 语义（绿=成功、红=错误、灰=未知）--");
            ok("VERIFIED 是 OK（绿）",
                    CrackPhaseView.Phase.VERIFIED.tone == CrackPhaseView.Tone.OK);
            ok("PROVEN_WRONG 是 DANGER（红）",
                    CrackPhaseView.Phase.PROVEN_WRONG.tone == CrackPhaseView.Tone.DANGER);
            ok("ENV_ERROR 是 DANGER（红）",
                    CrackPhaseView.Phase.ENV_ERROR.tone == CrackPhaseView.Tone.DANGER);
            ok("UNCERTAIN 是 WARN（黄，不是红）",
                    CrackPhaseView.Phase.UNCERTAIN.tone == CrackPhaseView.Tone.WARN);
            ok("IDLE 是 NEUTRAL（灰）",
                    CrackPhaseView.Phase.IDLE.tone == CrackPhaseView.Tone.NEUTRAL);

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== CrackPhaseView 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
