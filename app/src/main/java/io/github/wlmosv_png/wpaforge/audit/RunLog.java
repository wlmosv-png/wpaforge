package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 运行记录 —— 带时间、级别与**操作上下文**的结构化记录，按频道分开。
 *
 * <h3>为什么要换掉一个 TextView</h3>
 * 原来审计页就一个 {@code TextView} 往里拼字符串，于是：
 * <ul>
 *   <li>没有时间戳 —— 跑几轮之后分不清哪行是哪次；</li>
 *   <li>没有级别 —— 进度、成功、失败、报错全一个颜色，得逐字读；</li>
 *   <li>「口令强度检查」和「离线校验」写进同一个框，两件事混在一起；</li>
 *   <li>没有上限，只增不减。</li>
 * </ul>
 *
 * <h3>2026-11-06（1.9.44）：从「一行日志」升级成「一条记录」</h3>
 * 用户明确要求不要再拿大块文本当日志主体。现在每条 {@link Line} 都带：
 * <pre>
 *   时间(at) + 级别(kind → 状态) + 操作对象(subject) + 简短结果(text) + 详细日志(detail)
 * </pre>
 * 界面上先给「时间 · 类型 · 对象 · 状态 · 简短结果」一行，
 * 点开才显示 {@link Line#detail}（可以多行）。这样不用读几十行原始日志
 * 也能知道发生了什么。
 *
 * <h3>四个频道</h3>
 * {@link #offline}（离线校验）／{@link #strength}（口令强度评估）／
 * {@link #dict}（字典操作）／{@link #other}（其他审计操作）——
 * 彻底分开，不再互相串台。
 *
 * <p>纯逻辑，无 Android 依赖，可离线测试。
 */
public final class RunLog {

    /** 日志级别。UI 按它上色，也按它给出「状态」文案。 */
    public enum Kind {
        /** 普通说明 */
        INFO,
        /** 进度跳动 */
        PROGRESS,
        /** 成功 */
        OK,
        /** 警告 */
        WARN,
        /** 失败 */
        FAIL,
        /** 用户取消 */
        CANCEL
    }

    /** 频道 = 操作类型。 */
    public enum Op {
        /** 离线校验 */
        OFFLINE,
        /** 口令强度评估 */
        STRENGTH,
        /** 字典操作（加载 / 生成 / 派生 / 管理） */
        DICT,
        /** 其他审计操作 */
        OTHER
    }

    /** 级别的「状态」文案（记录里的 状态 一栏就取它）。 */
    public static String statusText(Kind k) {
        if (k == null) k = Kind.INFO;
        switch (k) {
            case OK:       return "OK";
            case WARN:     return "WARN";
            case FAIL:     return "FAIL";
            case CANCEL:   return "CANCEL";
            case PROGRESS: return "RUN";
            default:       return "INFO";
        }
    }

    /** 一条记录。 */
    public static final class Line {
        public final long at;      // epoch ms
        public final Kind kind;
        public final Op op;
        /** 操作对象：材料名 / 字典名 / 口令（已脱敏）等。可为空。 */
        public final String subject;
        /** 简短结果（原来叫 text，旧调用点全部沿用）。 */
        public final String text;
        /** 详细日志，可多行。默认等于 text。 */
        public final String detail;

        public Line(long at, Kind kind, Op op, String subject, String text, String detail) {
            this.at = at;
            this.kind = kind == null ? Kind.INFO : kind;
            this.op = op == null ? Op.OTHER : op;
            this.subject = subject == null ? "" : subject;
            this.text = text == null ? "" : text;
            this.detail = detail == null ? this.text : detail;
        }

        /** 兼容旧写法（只有时间 / 级别 / 文本）。 */
        public Line(long at, Kind kind, String text) {
            this(at, kind, null, "", text, null);
        }

        /** {@code HH:mm:ss} —— 只取时分秒，够用且不占宽度。 */
        public String clock() {
            long sec = Math.max(0, at) / 1000L;
            long h = (sec / 3600) % 24;
            long m = (sec / 60) % 60;
            long s = sec % 60;
            return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", h, m, s);
        }

        /** 状态文案（记录列表的「状态」一栏）。 */
        public String status() {
            return statusText(kind);
        }

        /** 一定有详细内容可看吗（没有就别给「详情」入口）。 */
        public boolean hasDetail() {
            return !detail.isEmpty() && !detail.equals(text);
        }
    }

    /** 单一频道的环形缓冲。超上限丢最旧的。 */
    public static final class Channel {
        public static final int DEFAULT_CAP = 400;

        private final Deque<Line> lines = new ArrayDeque<Line>();
        private final int cap;
        private final Op op;

        public Channel() {
            this(Op.OTHER, DEFAULT_CAP);
        }

        public Channel(int cap) {
            this(Op.OTHER, cap);
        }

        public Channel(Op op, int cap) {
            this.op = op == null ? Op.OTHER : op;
            this.cap = Math.max(8, cap);
        }

        public Op op() {
            return op;
        }

        public synchronized void add(long at, Kind kind, String text) {
            add(at, kind, "", text, null);
        }

        /** 带上下文的记录。 */
        public synchronized void add(long at, Kind kind, String subject,
                                     String text, String detail) {
            lines.addLast(new Line(at, kind, op, subject, text, detail));
            while (lines.size() > cap) lines.removeFirst();
        }

        public synchronized void info(long at, String text) {
            add(at, Kind.INFO, text);
        }

        public synchronized void progress(long at, String text) {
            add(at, Kind.PROGRESS, text);
        }

        public synchronized void ok(long at, String text) {
            add(at, Kind.OK, text);
        }

        public synchronized void warn(long at, String text) {
            add(at, Kind.WARN, text);
        }

        public synchronized void fail(long at, String text) {
            add(at, Kind.FAIL, text);
        }

        public synchronized void cancel(long at, String text) {
            add(at, Kind.CANCEL, text);
        }

        public synchronized List<Line> snapshot() {
            return new ArrayList<Line>(lines);
        }

        public synchronized void clear() {
            lines.clear();
        }

        public synchronized int size() {
            return lines.size();
        }
    }

    /** 离线校验频道。 */
    public final Channel offline = new Channel(Op.OFFLINE, Channel.DEFAULT_CAP);
    /** 口令强度评估频道。 */
    public final Channel strength = new Channel(Op.STRENGTH, Channel.DEFAULT_CAP);
    /** 字典操作频道。 */
    public final Channel dict = new Channel(Op.DICT, Channel.DEFAULT_CAP);
    /** 其他审计操作频道。 */
    public final Channel other = new Channel(Op.OTHER, Channel.DEFAULT_CAP);

    /** 按类型取频道。 */
    public Channel channel(Op op) {
        if (op == null) return other;
        switch (op) {
            case OFFLINE:  return offline;
            case STRENGTH: return strength;
            case DICT:     return dict;
            default:       return other;
        }
    }

    /** 清空指定频道。**不碰结果卡，也不影响正在跑的任务。** */
    public void clearChannel(Op op) {
        channel(op).clear();
    }

    /** 全部频道一起清。**不碰结果卡**。 */
    public void clearAll() {
        offline.clear();
        strength.clear();
        dict.clear();
        other.clear();
    }
}
