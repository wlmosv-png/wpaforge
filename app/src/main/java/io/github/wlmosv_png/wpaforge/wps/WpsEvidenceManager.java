package io.github.wlmosv_png.wpaforge.wps;

import java.util.ArrayList;
import java.util.List;

/**
 * WPS 证据记录与纠错历史（纯逻辑，**只增不改**）。
 *
 * <h3>职责</h3>
 * 按任务保存「原始证据 + 判定结果 + 后续纠错」，保证：
 * <ul>
 *   <li>原始记录写入后**不被覆盖**（{@link Entry} 不可变）；</li>
 *   <li>纠错以**追加新条目**的方式表达，原始条目仍在列表里（可审计）；</li>
 *   <li>提供"当前有效判定"（最后一条 non-correction 或纠错后的结果）。</li>
 * </ul>
 *
 * <p>本类不参与判定本身（那是 {@link WpsHitJudge} 的职责），只做**留痕**。
 */
public final class WpsEvidenceManager {

    /** 一条证据记录（不可变）。 */
    public static final class Entry {
        public final String taskId;
        public final long atMs;
        /** HIT_CONFIRMED / HIT_UNVERIFIED / NOT_TARGET / NO_HIT / CORRECTION。 */
        public final String verdict;
        /** 事实位串（可为空）。 */
        public final String facts;
        /** 人话原因。 */
        public final String reason;
        /** 被纠错时的原始判定（仅 CORRECTION 条目非空）。 */
        public final String originalVerdict;

        Entry(String taskId, long atMs, String verdict, String facts,
              String reason, String originalVerdict) {
            this.taskId = taskId == null ? "" : taskId;
            this.atMs = atMs;
            this.verdict = verdict == null ? "" : verdict;
            this.facts = facts == null ? "" : facts;
            this.reason = reason == null ? "" : reason;
            this.originalVerdict = originalVerdict == null ? "" : originalVerdict;
        }

        public boolean isCorrection() {
            return "CORRECTION".equals(verdict);
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /** 记录一次判定（只增）。 */
    public synchronized void record(String taskId, String verdict, String facts, String reason) {
        record(taskId, verdict, facts, reason, System.currentTimeMillis());
    }

    public synchronized void record(String taskId, String verdict, String facts,
                                    String reason, long atMs) {
        entries.add(new Entry(taskId, atMs, verdict, facts, reason, ""));
    }

    /**
     * 记录一次纠错（原始判定仍保留在 {@code originalVerdict} 字段里）。
     *
     * @return 是否记录成功（未记录过原始判定时返回 false —— 不制造无源纠错）
     */
    public synchronized boolean correct(String taskId, String newVerdict, String reason, long atMs) {
        String orig = lastVerdictOf(taskId);
        if (orig.isEmpty()) return false;
        entries.add(new Entry(taskId, atMs, "CORRECTION", "", reason, orig));
        return true;
    }

    /** 某任务"当前有效判定"：纠错条目返回其承载的新判定由调用方从 reason 解析太脆，
     *  因此这里约定：纠错条目本身不算有效判定，有效判定 = 最后一条非纠错条目。 */
    public synchronized String effectiveVerdict(String taskId) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.taskId.equals(taskId) && !e.isCorrection()) return e.verdict;
        }
        return "";
    }

    /** 某任务最后一次记录的判定（含纠错条目本身不作数）。 */
    private String lastVerdictOf(String taskId) {
        return effectiveVerdict(taskId);
    }

    /** 是否发生过纠错。 */
    public synchronized boolean corrected(String taskId) {
        for (Entry e : entries) {
            if (e.taskId.equals(taskId) && e.isCorrection()) return true;
        }
        return false;
    }

    /** 全部条目（快照副本，调用方改不了内部状态）。 */
    public synchronized List<Entry> all() {
        return new ArrayList<>(entries);
    }

    /** 某任务的条目。 */
    public synchronized List<Entry> of(String taskId) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e.taskId.equals(taskId)) out.add(e);
        return out;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }

    /** 一行摘要（供日志/历史展示）。 */
    public synchronized String summary(String taskId, boolean zh) {
        Entry last = null;
        for (Entry e : entries) if (e.taskId.equals(taskId)) last = e;
        if (last == null) return zh ? "（无证据记录）" : "(no evidence)";
        StringBuilder sb = new StringBuilder();
        sb.append("verdict=").append(last.isCorrection()
                ? last.originalVerdict + "→corrected" : last.verdict);
        if (!last.facts.isEmpty()) sb.append(" facts=").append(last.facts);
        if (!last.reason.isEmpty()) sb.append(" why=").append(last.reason);
        return sb.toString();
    }
}
