package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AttributionPrecondition;
import io.github.wlmosv_png.wpaforge.audit.Evidence;

/**
 * **AT3 结构性前提**门禁（2026-11-20）。
 *
 * <p>核心不变量：**前提不成立时不许给 AT3**。
 * 前提不足 → AT3 降到 AT2 → Adjudicator 规则 2 → UNKNOWN / grade=NONE，
 * 既不判成功也不判失败。宁可少一个命中，不可多一个假成功。
 */
public class AttributionPreconditionTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        AttributionPreconditionTest t = new AttributionPreconditionTest();
        t.run();
        System.out.println(t.pass + " 通过 / " + t.fail + " 失败");
        if (t.fail > 0) System.exit(1);
    }

    private void ok(String name, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private void run() {
        allSatisfiedAllowsAt3();
        staleConfigCapsToAt2();
        linkNotDroppedCapsToAt2();
        preStateUnknownCapsToAt2();
        capNeverRaises();
        blockingReasons();
    }

    private void allSatisfiedAllowsAt3() {
        AttributionPrecondition p = AttributionPrecondition.allSatisfied();
        ok("全部满足 → satisfied()", p.satisfied());
        ok("全部满足 → blockingReason 为空", p.blockingReason().isEmpty());
        ok("全部满足 → AT3 保持 AT3",
                p.capAttribution(Evidence.Attribution.AT3) == Evidence.Attribution.AT3);
        ok("全部满足 → AT2 保持 AT2",
                p.capAttribution(Evidence.Attribution.AT2) == Evidence.Attribution.AT2);
    }

    /** 净检失败 → 不许给 AT3（这是假阳性的头号来源）。 */
    private void staleConfigCapsToAt2() {
        AttributionPrecondition p = AttributionPrecondition.staleConfig();
        ok("净检失败 → not satisfied", !p.satisfied());
        ok("净检失败 → AT3 降到 AT2",
                p.capAttribution(Evidence.Attribution.AT3) == Evidence.Attribution.AT2);
        ok("净检失败 → 原因码 stale-config",
                "stale-config".equals(p.blockingReason()));
    }

    /** 上一条连接没断开 → 不许给 AT3（假阳性第二名来源）。 */
    private void linkNotDroppedCapsToAt2() {
        AttributionPrecondition p = AttributionPrecondition.linkNotDropped();
        ok("断开失败 → not satisfied", !p.satisfied());
        ok("断开失败 → AT3 降到 AT2",
                p.capAttribution(Evidence.Attribution.AT3) == Evidence.Attribution.AT2);
        ok("断开失败 → 原因码 link-not-dropped",
                "link-not-dropped".equals(p.blockingReason()));
    }

    /** 命令前状态读不到 → 不许给 AT3。 */
    private void preStateUnknownCapsToAt2() {
        AttributionPrecondition p = AttributionPrecondition.preStateUnknown();
        ok("状态不可读 → not satisfied", !p.satisfied());
        ok("状态不可读 → AT3 降到 AT2",
                p.capAttribution(Evidence.Attribution.AT3) == Evidence.Attribution.AT2);
        ok("状态不可读 → 原因码 pre-state-unknown",
                "pre-state-unknown".equals(p.blockingReason()));
    }

    /** capAttribution 只能降、不能升。 */
    private void capNeverRaises() {
        AttributionPrecondition p = AttributionPrecondition.staleConfig();
        ok("前提不足时 AT2 仍是 AT2",
                p.capAttribution(Evidence.Attribution.AT2) == Evidence.Attribution.AT2);
        ok("前提不足时 AT1 仍是 AT1",
                p.capAttribution(Evidence.Attribution.AT1) == Evidence.Attribution.AT1);
        ok("前提不足时 NONE 仍是 NONE",
                p.capAttribution(Evidence.Attribution.NONE) == Evidence.Attribution.NONE);
        ok("null → NONE", p.capAttribution(null) == Evidence.Attribution.NONE);
        // 全部满足时也不能把 AT2 升成 AT3
        AttributionPrecondition ok2 = AttributionPrecondition.allSatisfied();
        ok("全部满足也不升 AT2→AT3",
                ok2.capAttribution(Evidence.Attribution.AT2) == Evidence.Attribution.AT2);
    }

    /** 每个前提单独不满足都要有独立原因码。 */
    private void blockingReasons() {
        ok("只有 fresh=false → not-fresh-config",
                "not-fresh-config".equals(
                        AttributionPrecondition.of(true, true, true, false).blockingReason()));
        ok("全部 false → 报第一个 stale-config",
                "stale-config".equals(
                        AttributionPrecondition.of(false, false, false, false).blockingReason()));
        // toString 不含任何敏感内容
        String s = AttributionPrecondition.staleConfig().toString();
        ok("toString 含 satisfied 状态", s.contains("satisfied=false"));
    }
}
