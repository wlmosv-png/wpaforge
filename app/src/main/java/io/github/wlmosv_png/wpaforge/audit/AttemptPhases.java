package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次在线验证尝试的**七阶段状态机**（2026-11-20）。
 *
 * <h3>为什么需要它（任务书第四部分）</h3>
 * 改造前，一次尝试的全部前置条件与观察都挤在 {@code tryOneShell} 的几百行里：
 * forget、回读、baseline、断开、发命令、轮询、判定。它们**没有各自的时间戳**，
 * 也没有一个对象能回答「这次尝试走到了哪一步、在哪一步失败、每一步花了多久」。
 *
 * <p>后果是具体的：
 * <ul>
 *   <li>用户看到「未确定」，但不知道是前置闸门没过还是观察窗口没等到证据；</li>
 *   <li>排查「正确密码被判未确定」时只能读日志文本，无法按阶段定位；</li>
 *   <li>「迟到回调污染下一次尝试」这类问题时序上说不清 —— 因为根本没有阶段边界。</li>
 * </ul>
 *
 * <h3>阶段定义（顺序即执行顺序）</h3>
 * <pre>
 *   PRECHECK    读命令前状态（status / list-networks），确认能否判断
 *   ISOLATE     删净旧配置 + 回读确认 + 断开上一跳并确认
 *   SUBMIT      发出 connect-network（异步，不等待结果）
 *   OBSERVE     轮询 status 采集证据，直到超时或命中
 *   ATTRIBUTE   把观察到的证据绑定到本次 Attempt（AT1/AT2/AT3）
 *   ADJUDICATE  交给 Adjudicator 产出 verdict/grade
 *   FINALIZE    落账（triedSet / 账本 / 统计 / 游标）
 * </pre>
 *
 * <p>每个阶段都必须有：进入时刻、结束时刻、结果码、失败原因。
 * **任一阶段失败即终止后续阶段**，并把失败阶段如实报出去。
 *
 * <p>纯逻辑、无 Android 依赖，可离线穷举。
 */
public final class AttemptPhases {

    /** 七个阶段。声明顺序即执行顺序。 */
    public enum Phase {
        PRECHECK("预检"),
        ISOLATE("隔离"),
        SUBMIT("提交"),
        OBSERVE("观察"),
        ATTRIBUTE("归因"),
        ADJUDICATE("裁决"),
        FINALIZE("落账");

        public final String zh;

        Phase(String zh) {
            this.zh = zh;
        }

        /** 该阶段是否属于「必须成功才能继续」的关键闸门。 */
        public boolean isGate() {
            return this == PRECHECK || this == ISOLATE || this == SUBMIT;
        }
    }

    /** 单个阶段的结果。 */
    public static final class Step {
        public final Phase phase;
        /** 进入时刻（单调时钟）。 */
        public final long startedAtMs;
        /** 结束时刻；0 = 还没结束。 */
        public long endedAtMs;
        /** 结果码：ok / 具体失败原因（空 = 还没结束）。 */
        public String outcome = "";
        /** 是否成功。 */
        public boolean ok;
        /** 失败/说明细节（人话，不含口令）。 */
        public String detail = "";

        Step(Phase phase, long startedAtMs) {
            this.phase = phase;
            this.startedAtMs = startedAtMs;
        }

        public long elapsedMs() {
            return endedAtMs > 0 ? Math.max(0L, endedAtMs - startedAtMs) : 0L;
        }

        @Override
        public String toString() {
            return phase.zh + "[" + (ok ? "ok" : (outcome.isEmpty() ? "…" : outcome))
                    + " " + elapsedMs() + "ms]";
        }
    }

    private final String attemptId;
    private final List<Step> steps = new ArrayList<>(8);
    private Phase current = null;
    /** 第一个失败阶段；null = 全部成功。 */
    private Phase failedAt = null;

    public AttemptPhases(String attemptId) {
        this.attemptId = attemptId == null ? "" : attemptId;
    }

    public String attemptId() {
        return attemptId;
    }

    /**
     * 进入某个阶段。
     *
     * @return 本阶段的 Step（调用方随后用 {@link #finish} 收尾）
     */
    public Step begin(Phase phase, long nowMs) {
        Step s = new Step(phase, nowMs);
        steps.add(s);
        current = phase;
        return s;
    }

    /** 结束当前阶段。 */
    public void finish(boolean ok, String outcome, String detail, long nowMs) {
        if (steps.isEmpty()) return;
        Step s = steps.get(steps.size() - 1);
        s.endedAtMs = nowMs;
        s.ok = ok;
        s.outcome = outcome == null ? "" : outcome;
        s.detail = detail == null ? "" : detail;
        if (!ok && failedAt == null) failedAt = s.phase;
    }

    /** 当前阶段（可能为 null）。 */
    public Phase currentPhase() {
        return current;
    }

    /** 第一个失败阶段；null = 全部成功。 */
    public Phase failedPhase() {
        return failedAt;
    }

    /** 是否已经因某阶段失败而终止。 */
    public boolean blocked() {
        return failedAt != null;
    }

    /** 已完成的阶段列表（不可变视图）。 */
    public List<Step> steps() {
        return java.util.Collections.unmodifiableList(steps);
    }

    /** 某个阶段是否**已经成功走完**（用于断言「没走完就不许给 DIRECT」）。 */
    public boolean passed(Phase p) {
        for (Step s : steps) {
            if (s.phase == p) return s.endedAtMs > 0 && s.ok;
        }
        return false;
    }

    /**
     * **是否满足进入 DIRECT 判定的阶段前提**。
     *
     * <p>这是本类最关键的方法：把「凭什么能说这条成功」从注释变成可断言的条件。
     * 要求 PRECHECK、ISOLATE、SUBMIT、OBSERVE、ATTRIBUTE 全部成功走完。
     * 缺任何一段都不许给 DIRECT —— 与 {@link AttributionPrecondition} 互为补充：
     * 那边管「证据归属够不够硬」，这边管「流程走没走完」。
     */
    public boolean eligibleForDirect() {
        if (blocked()) return false;
        return passed(Phase.PRECHECK) && passed(Phase.ISOLATE)
                && passed(Phase.SUBMIT) && passed(Phase.OBSERVE)
                && passed(Phase.ATTRIBUTE);
    }

    /**
     * 一行诊断摘要（**不含口令**，可写日志）。
     *
     * <p>格式固定，便于用户复制：
     * <pre>
     *   attempt=xxx | PRECHECK[ok 12ms] ISOLATE[stale-config 240ms] | BLOCKED@ISOLATE
     * </pre>
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("attempt=").append(attemptId.isEmpty() ? "<invalid>" : attemptId).append(" | ");
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(steps.get(i));
        }
        if (blocked()) sb.append(" | BLOCKED@").append(failedAt.zh);
        return sb.toString();
    }

    /** 总耗时（从第一个阶段进入到最后一次结束）。 */
    public long totalMs() {
        if (steps.isEmpty()) return 0L;
        long start = steps.get(0).startedAtMs;
        long end = 0L;
        for (Step s : steps) {
            if (s.endedAtMs > end) end = s.endedAtMs;
        }
        return end > start ? end - start : 0L;
    }
}
