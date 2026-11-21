package io.github.wlmosv_png.wpaforge.ui;

/**
 * 攻击流（逐条候选结果）的**记录模型与格式化**（1.9.66 破解页重设计）。纯逻辑，可离线穷举。
 *
 * <h3>解决什么问题<\/h3>
 * 1.9.65 的攻击流把「主行 + 说明行」写成两段，说明行又重复了主行的类别——
 * 用户看到的是「无法判断 · 第 17 条 · 0.2s」紧接着「无法判断 没拿到可归因的证据…」，
 * 同一件事说两遍；且相同 Verdict 合并成「×17」后，**候选序号范围丢失**，
 * 分不清是哪 17 条；时间范围与序号挤在一起。
 *
 * <h3>本类的结构<\/h3>
 * 一条记录 = 一次候选尝试（可被合并）。字段分四层：
 * <ul>
 *   <li><b>主行</b>：类别 · 候选序号（或范围） · 耗时（+ 合并时 ×N 与时间范围）。<\/li>
 *   <li><b>事实行</b>：一句简短准确的事实，**不重复类别措辞**。<\/li>
 *   <li><b>详情行</b>：attempt / verdict / grade / reason / src / triedSet / cursor（展开时）。<\/li>
 * <\/ul>
 * 合并规则：仅当**相邻**且 {@link #mergeKey} 相同才合并；合并时保留首条序号、
 * 延伸末条序号与末次时间——**绝不丢失独立尝试的身份**（序号范围仍可读）。
 */
public final class StreamFormatter {

    private StreamFormatter() {}

    /** 一条攻击流记录。 */
    public static final class Record {
        /** 用户可理解的类别（来自 ResultCategory）。 */
        public ResultCategory.Kind kind;
        /** 机器可读原因码（用于分类内的进一步说明，也让合并键区分不同 reason）。 */
        public String reason = "";
        /** 首条候选序号（1 基）。 */
        public int firstIdx;
        /** 末条候选序号（= firstIdx 表示单条）。 */
        public int lastIdx;
        /** 本条耗时（毫秒）；合并时取末次。 */
        public long durationMs;
        /** 合并条数（1 = 单条）。 */
        public int repeat = 1;
        /** 首次出现的墙钟毫秒（合并时间范围用）；0 = 未记录。 */
        public long firstAtMs;
        /** 末次出现的墙钟毫秒。 */
        public long lastAtMs;
        /** 详情字段（展开时展示，全部来自后端，不由 UI 推导）。 */
        public String attemptId = "";
        public String verdictName = "";
        public String gradeName = "";
        public String source = "";

        public Record() {}

        public Record(ResultCategory.Kind kind, String reason, int idx, long durationMs,
                      long atMs, String attemptId, String verdictName, String gradeName,
                      String source) {
            this.kind = kind;
            this.reason = reason == null ? "" : reason;
            this.firstIdx = idx;
            this.lastIdx = idx;
            this.durationMs = durationMs;
            this.firstAtMs = atMs;
            this.lastAtMs = atMs;
            this.attemptId = attemptId == null ? "" : attemptId;
            this.verdictName = verdictName == null ? "" : verdictName;
            this.gradeName = gradeName == null ? "" : gradeName;
            this.source = source == null ? "" : source;
        }

        /**
         * 把相邻的另一条同键记录**并入本条**：保留首条序号，延伸末条序号与末次时间。
         *
         * <p>这就是「合并不丢身份」的关键：range = [firstIdx, lastIdx]，
         * repeat 是真实条数，attemptId 取**末次**（最近一次可追溯）。
         */
        public void absorb(Record n) {
            if (n == null) return;
            this.lastIdx = n.lastIdx;
            this.durationMs = n.durationMs;
            this.lastAtMs = n.lastAtMs;
            this.repeat += 1;
            this.attemptId = n.attemptId;
            this.verdictName = n.verdictName;
            this.gradeName = n.gradeName;
            this.source = n.source;
        }
    }

    /** 合并键：类别 + reason（不同 reason 的同类结果**不合并**，避免混淆）。 */
    public static String mergeKey(ResultCategory.Kind kind, String reason) {
        return (kind == null ? "?" : kind.name()) + "|" + (reason == null ? "" : reason);
    }

    /** 候选序号或范围：单条「第 5 条」，合并「第 5–21 条」。 */
    public static String candidateRange(int first, int last, boolean zh) {
        if (first <= 0) return "";
        if (last <= first) return zh ? ("第 " + first + " 条") : ("#" + first);
        return zh ? ("第 " + first + "–" + last + " 条") : ("#" + first + "–" + last);
    }

    /** 主行：类别 · 序号(范围) · 耗时 [+ ×N 时间范围]。不重复事实。 */
    public static String mainLine(Record r, boolean zh) {
        if (r == null) return "";
        StringBuilder sb = new StringBuilder();
        String range = candidateRange(r.firstIdx, r.lastIdx, zh);
        if (range.isEmpty()) {
            sb.append(ResultCopy.primaryLabel(r.kind, zh));
        } else {
            sb.append(ResultCopy.primaryLabel(r.kind, zh))
              .append(" · ").append(range)
              .append(" · ").append(fmtSeconds(r.durationMs));
        }
        if (r.repeat > 1) {
            sb.append("   ×").append(r.repeat);
            if (r.firstAtMs > 0) {
                sb.append("  ").append(clockHm(r.firstAtMs));
                if (r.lastAtMs > r.firstAtMs) sb.append("–").append(clockHm(r.lastAtMs));
            }
        }
        return sb.toString();
    }

    /** 事实行：缩进 + 一句事实（不重复类别）。 */
    public static String factLine(Record r, boolean zh) {
        if (r == null) return "";
        return "  " + ResultCopy.shortFact(r.kind, r.reason, zh);
    }

    /** 详情行：缩进 + 技术字段（展开时）。 */
    public static String detailLine(Record r, boolean zh) {
        if (r == null) return "";
        io.github.wlmosv_png.wpaforge.audit.CrackEngine.Verdict v = null;
        io.github.wlmosv_png.wpaforge.audit.EvidenceGrade.Level g = null;
        try {
            if (r.verdictName != null && !r.verdictName.isEmpty()) {
                v = io.github.wlmosv_png.wpaforge.audit.CrackEngine.Verdict
                        .valueOf(r.verdictName);
            }
            if (r.gradeName != null && !r.gradeName.isEmpty()) {
                g = io.github.wlmosv_png.wpaforge.audit.EvidenceGrade.Level
                        .valueOf(r.gradeName);
            }
        } catch (Throwable ignored) {}
        return "  " + ResultCopy.detailLine(r.attemptId, v, g, r.reason, r.source, zh);
    }

    // ── 时间格式化（与页面既有的 fmtSeconds/clockHm 同语义，但放在这里便于单测）──

    /** 秒数格式化：<1s 用 0.1s 精度，>=1s 一位小数，>=10s 取整。 */
    public static String fmtSeconds(long ms) {
        if (ms < 0) return "—";
        if (ms < 950) return "0." + Math.max(1, ms / 100) + "s";
        double sec = ms / 1000.0;
        if (sec < 10) return String.format(java.util.Locale.US, "%.1fs", sec);
        return Math.round(sec) + "s";
    }

    /** 墙钟 HH:mm。 */
    public static String clockHm(long ms) {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(new java.util.Date(ms));
    }
}
