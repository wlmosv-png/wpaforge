package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 运行记录 —— 带时间与级别的日志模型，**按频道分开**。
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
 * <p>这里给出结构化的 {@link Line}（时间 + 级别 + 文本）与两个互不干扰的
 * {@link Channel}。纯逻辑，无 Android 依赖，可离线测试。
 */
public final class RunLog {

    /** 日志级别。UI 按它上色。 */
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

    /** 一条日志。 */
    public static final class Line {
        public final long at;      // epoch ms
        public final Kind kind;
        public final String text;

        public Line(long at, Kind kind, String text) {
            this.at = at;
            this.kind = kind == null ? Kind.INFO : kind;
            this.text = text == null ? "" : text;
        }

        /** {@code HH:mm:ss} —— 只取时分秒，够用且不占宽度。 */
        public String clock() {
            long sec = Math.max(0, at) / 1000L;
            long h = (sec / 3600) % 24;
            long m = (sec / 60) % 60;
            long s = sec % 60;
            return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", h, m, s);
        }
    }

    /** 单一频道的环形缓冲。超上限丢最旧的。 */
    public static final class Channel {
        public static final int DEFAULT_CAP = 400;

        private final Deque<Line> lines = new ArrayDeque<Line>();
        private final int cap;

        public Channel() {
            this(DEFAULT_CAP);
        }

        public Channel(int cap) {
            this.cap = Math.max(8, cap);
        }

        public synchronized void add(long at, Kind kind, String text) {
            lines.addLast(new Line(at, kind, text));
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
    public final Channel offline = new Channel();
    /** 口令强度评估频道。 */
    public final Channel strength = new Channel();

    /** 两个频道一起清（用户点「清空日志」时用）。**不碰结果卡**。 */
    public void clearAll() {
        offline.clear();
        strength.clear();
    }
}
