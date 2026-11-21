package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * 判据 D 的**采集时序**测试（1.9.65 新增）。
 *
 * <p>钉死 1.9.65 相对 1.9.64 的核心修正：
 * <ul>
 *   <li>固定时刻表 2600 / 3600 / 5200 都生效（5200 覆盖真机 3.8~4.9s 落盘）；</li>
 *   <li>一次查询没有证据**不**永久标记「已探测完毕」，到点还能再查；</li>
 *   <li>连接生命周期终止（TERMINATED）后立即触发一次（不必等下一固定时刻）；</li>
 *   <li>主循环退出后，在截止时间内有间隔地有限轮询（graceDeadline / graceDue）；</li>
 *   <li>多个候选的 plan 互不影响（每条 attempt 独立采集）。</li>
 * </ul>
 * 纯逻辑，无 Android / shell 依赖，可离线穷举。
 */
public final class DumpsProbePlanTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }
    private static void ok(String name, boolean cond) { ok(name, cond, ""); }

    private static final CrackEngine.Lifecycle ACTIVE = CrackEngine.Lifecycle.ACTIVE;
    private static final CrackEngine.Lifecycle TERM = CrackEngine.Lifecycle.TERMINATED;

    public static void main(String[] args) {
        System.out.println("== 判据 D 采集时序 ==");
        try {
            // 1. 时刻表内容
            long[] sc = DumpsProbePlan.SCHEDULE_MS;
            ok("★SCHEDULE_MS = {2600,3600,5200}",
                    sc.length == 3 && sc[0] == 2600L && sc[1] == 3600L && sc[2] == 5200L);

            // 2. 闸门未过 → 从不采集
            DumpsProbePlan p0 = new DumpsProbePlan();
            ok("闸门未过 → 不采集", !p0.due(9000L, false, TERM));
            ok("闸门未过 → everProbed=false", !p0.everProbed());

            // 3. 到点才采集（2599 vs 2600）
            DumpsProbePlan p1 = new DumpsProbePlan();
            ok("2599ms 未到首个时刻 → 不采集", !p1.due(2599L, true, ACTIVE));
            ok("2600ms 到首个时刻 → 采集", p1.due(2600L, true, ACTIVE));

            // 4. 采集后推进到 3600
            p1.markProbed(2600L);
            ok("采集后 nextScheduled=3600", p1.nextScheduledMs() == 3600L,
                    "" + p1.nextScheduledMs());
            ok("2700ms 未到下一时刻 → 不采集", !p1.due(2700L, true, ACTIVE));

            // 5. 一次未命中不永久封锁：3600 到点仍采集
            ok("★3600ms 再次到点 → 采集（一次未命中不封锁）", p1.due(3600L, true, ACTIVE));
            p1.markProbed(3600L);
            ok("采集后 nextScheduled=5200", p1.nextScheduledMs() == 5200L,
                    "" + p1.nextScheduledMs());
            ok("5200ms 到点 → 采集", p1.due(5200L, true, ACTIVE));

            // 6. 终止触发：不等下一固定时刻，但受 MIN_GAP 约束
            DumpsProbePlan p2 = new DumpsProbePlan();
            p2.markProbed(1000L);
            ok("1200ms TERMINATED 但未满 MIN_GAP → 不采集",
                    !p2.due(1200L, true, TERM));
            ok("★1600ms TERMINATED 且满 MIN_GAP → 立即采集",
                    p2.due(1600L, true, TERM));

            // 7. 模拟整轮：2.6s 无事件、4.9s 才落盘 → 5200 探测点覆盖
            List<Long> probes = new ArrayList<>();
            DumpsProbePlan p3 = new DumpsProbePlan();
            for (long t = 0; t <= 6000; t += 100) {
                if (p3.due(t, true, ACTIVE)) { p3.markProbed(t); probes.add(t); }
            }
            ok("★时刻表模拟得到 2600/3600/5200",
                    probes.contains(2600L) && probes.contains(3600L) && probes.contains(5200L),
                    probes.toString());
            long last = probes.get(probes.size() - 1);
            ok("★事件在 4900ms 落盘时，最后探测点(" + last + ") ≥ 4900 → 能读到",
                    last >= 4900L, "" + last);

            // 8. 事件晚于时刻表（6100ms）→ 靠终止触发仍能再采集
            DumpsProbePlan p4 = new DumpsProbePlan();
            p4.markProbed(5200L);
            boolean dueAfter = false;
            for (long t = 5200; t <= 6500; t += 100) {
                if (p4.due(t, true, TERM)) { dueAfter = true; p4.markProbed(t); break; }
            }
            ok("★5200 之后事件晚到时，终止触发仍能再采集", dueAfter);

            // 9. 兜底截止与轮询
            DumpsProbePlan p5 = new DumpsProbePlan();
            long gd = p5.graceDeadlineMs(8000L);
            ok("graceDeadline = elapsed + 3000", gd == 11000L, "" + gd);
            ok("兜底期内(8000)应轮询", p5.graceDue(8000L, gd));
            ok("兜底期内(10700)仍轮询", p5.graceDue(10700L, gd));
            ok("★达到截止(11000)不再轮询", !p5.graceDue(11000L, gd));
            ok("超过截止(12000)不再轮询", !p5.graceDue(12000L, gd));

            // 10. everProbed / terminationProbed 状态
            DumpsProbePlan p6 = new DumpsProbePlan();
            ok("初始 everProbed=false", !p6.everProbed());
            ok("初始 terminationProbed=false", !p6.isTerminationProbed());
            p6.markProbed(2600L);
            p6.markTerminationProbed();
            ok("采集后 everProbed=true", p6.everProbed());
            ok("标记后 terminationProbed=true", p6.isTerminationProbed());

            // 11. 多候选独立采集
            DumpsProbePlan a = new DumpsProbePlan(), b = new DumpsProbePlan();
            a.markProbed(2600L);
            a.markTerminationProbed();
            ok("★候选 A 的采集不影响候选 B 的时刻表",
                    b.nextScheduledMs() == 2600L && !b.everProbed() && !b.isTerminationProbed());

            // 12. 时刻表用尽后（超过 5200），仅在终止态触发
            DumpsProbePlan p7 = new DumpsProbePlan();
            p7.markProbed(5200L);
            ok("超过 5200 且 ACTIVE → 不再按时刻表采集", !p7.due(7000L, true, ACTIVE));
            ok("超过 5200 但 TERMINATED → 仍需触发", p7.due(7000L, true, TERM));

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println("=== 判据 D 采集时序: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
