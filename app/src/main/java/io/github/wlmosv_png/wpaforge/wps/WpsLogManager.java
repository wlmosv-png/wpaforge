package io.github.wlmosv_png.wpaforge.wps;

import java.util.ArrayList;
import java.util.List;

/**
 * WPS 结构化日志（纯逻辑，线程安全）。
 *
 * <h3>职责</h3>
 * 统一「事件 → 带时间戳的一行」的格式，持有有界缓冲，提供快照与导出。
 * 原来这段逻辑散在 MainActivity 的 {@code wpsLog}（StringBuilder + 手写裁剪），
 * 抽出来后可独立测试，且**后台线程与 UI 线程并发写**有明确同步语义。
 *
 * <h3>约定</h3>
 * <ul>
 *   <li>每行前缀 {@code HH:mm:ss}（时间排序可读）；</li>
 *   <li>分级前缀由调用方给出（如 {@code [任务]} {@code [能力]}），本类不加语义；</li>
 *   <li>缓冲有界（超出裁掉最旧的），**不静默丢当前事件**；</li>
 *   <li>不记录任何凭据（调用方负责；导出前会做一次明显口令形态的遮蔽）。</li>
 * </ul>
 */
public final class WpsLogManager {

    /** 缓冲上限（字符）。 */
    public static final int MAX_CHARS = 16000;
    /** 裁剪后保留（字符）。 */
    public static final int KEEP_CHARS = 12000;

    private final StringBuilder buf = new StringBuilder();
    private long seq;              // 事件序号（测试与去重用）
    private int dropped;           // 因裁剪丢弃的行数（统计，不静默）

    /** 追加一行。 */
    public void log(String line) {
        log(line, System.currentTimeMillis());
    }

    /** 追加一行（显式时间，便于测试）。 */
    public void log(String line, long atMs) {
        String stamp = stamp(atMs);
        synchronized (buf) {
            seq++;
            buf.append(stamp).append("  ").append(line == null ? "" : line).append('\n');
            if (buf.length() > MAX_CHARS) {
                int cut = buf.length() - KEEP_CHARS;
                int nl = buf.indexOf("\n", cut);
                if (nl < 0) nl = cut;
                buf.delete(0, nl + 1);
                dropped++;
            }
        }
    }

    /** 结构化事件：{@code [tag] msg}。 */
    public void event(String tag, String msg) {
        log("[" + (tag == null ? "?" : tag) + "] " + (msg == null ? "" : msg));
    }

    /** 当前全文快照。 */
    public String snapshot() {
        synchronized (buf) {
            return buf.toString();
        }
    }

    /** 是否为空。 */
    public boolean isEmpty() {
        synchronized (buf) {
            return buf.length() == 0;
        }
    }

    /** 事件序号（每写一行 +1）。 */
    public long seq() {
        synchronized (buf) {
            return seq;
        }
    }

    /** 因上限被裁掉的行数。 */
    public int droppedLines() {
        synchronized (buf) {
            return dropped;
        }
    }

    /** 清空（清空后写一行"已清空"由调用方决定）。 */
    public void clear() {
        synchronized (buf) {
            buf.setLength(0);
        }
    }

    /** 按行导出（保持插入顺序）。 */
    public List<String> lines() {
        List<String> out = new ArrayList<>();
        String s = snapshot();
        for (String l : s.split("\n")) {
            if (!l.isEmpty()) out.add(l);
        }
        return out;
    }

    /**
     * 导出文本；对明显口令形态做遮蔽（防御性，保留长度与首尾）。
     *
     * <p>只处理形如 {@code PIN=12345678} / {@code password=xxxx} 的值 —— 我们本来
     * 也不会把 Wi-Fi 明文口令写进日志，这里是双保险。
     */
    public String exportRedacted() {
        String s = snapshot();
        StringBuilder sb = new StringBuilder();
        for (String l : s.split("\n")) {
            sb.append(redactLine(l)).append('\n');
        }
        return sb.toString();
    }

    private static String redactLine(String l) {
        if (l == null) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < l.length()) {
            int eq = l.indexOf('=', i);
            if (eq < 0) {
                sb.append(l, i, l.length());
                break;
            }
            int sp = eq;
            while (sp > i && isKeyChar(l.charAt(sp - 1))) sp--;
            String key = l.substring(sp, eq);
            sb.append(l, i, sp).append(key).append('=');
            int end = eq + 1;
            while (end < l.length() && l.charAt(end) != ' ' && l.charAt(end) != '\n') end++;
            String val = l.substring(eq + 1, end);
            String kl = key.toLowerCase(java.util.Locale.US);
            boolean secret = kl.contains("pass") || kl.contains("pin") || kl.contains("psk")
                    || kl.contains("secret");
            sb.append(secret ? mask(val) : val);
            i = end;
        }
        return sb.toString();
    }

    private static boolean isKeyChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }

    private static String mask(String v) {
        if (v == null || v.length() <= 2) return "***";
        return v.substring(0, 1) + "***" + v.substring(v.length() - 1);
    }

    /** 毫秒 → HH:mm:ss（不依赖 Android，便于单测）。 */
    static String stamp(long ms) {
        long sec = ms / 1000;
        long h = (sec / 3600) % 24, m = (sec / 60) % 60, s = sec % 60;
        return two(h) + ":" + two(m) + ":" + two(s);
    }

    private static String two(long v) {
        return (v < 10 ? "0" : "") + v;
    }
}
