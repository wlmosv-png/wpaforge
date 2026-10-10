package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 高级字典规则。一种用参数描述海量密码的方式，比存明文省空间得多。
 *
 * 格式（每行一个字段，冒号后为值）：
 *   前缀:abc
 *   后缀:123
 *   起始:00000000
 *   末尾:99999999
 *   步长:1            （可选，默认 1）
 *   进制:数字|字母|字母数字  （可选，默认数字）
 *
 * 生成逻辑：从L.s("起始", "Start")到"末尾"逐个取值（按步长递增），
 * 每个值前面拼L.s("前缀", "Prefix")、后面拼"后缀"。
 *
 * 例：起始 00000000 / 末尾 00000099 / 步长 1
 *     → 00000000, 00000001, ... 00000099
 *
 * 这是一个纯粹的生成器，不含任何第三方数据。
 */
public final class DictRule {

    public String prefix = "";
    public String suffix = "";
    public String start = "";
    public String end = "";
    public int step = 1;
    /** 数字 / 字母 / 字母数字 */
    public String mode = L.s("数字", "Digits");

    public boolean isValid() {
        return !start.isEmpty() && !end.isEmpty() && step > 0;
    }

    /** 从规则文本解析（宽容解析，忽略无法识别的行） */
    public static DictRule parse(String text) {
        DictRule r = new DictRule();
        if (text == null) return r;
        for (String raw : text.split("\n")) {
            String line = raw.replace("\uFEFF", "").replace("\r", "").trim();
            if (line.isEmpty()) continue;
            int idx = line.indexOf(':');
            String key, val;
            if (idx < 0) {
                // 兼容"起始 00000000"这种没冒号的写法
                String[] sp = line.split("\\s+", 2);
                if (sp.length < 2) continue;
                key = sp[0];
                val = sp[1];
            } else {
                key = line.substring(0, idx).trim();
                val = line.substring(idx + 1).trim();
            }
            switch (key) {
                case "前缀": case "prefix": r.prefix = val; break;
                case "后缀": case "suffix": r.suffix = val; break;
                case "起始": case "start": r.start = val; break;
                case "末尾": case "end": r.end = val; break;
                case "步长": case "step":
                    try { r.step = Integer.parseInt(val); } catch (Exception ignored) {}
                    break;
                case "进制": case "mode": r.mode = val; break;
                default: break;
            }
        }
        return r;
    }

    /** 序列化成文本 */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("前缀:").append(prefix).append('\n');
        sb.append("后缀:").append(suffix).append('\n');
        sb.append("起始:").append(start).append('\n');
        sb.append("末尾:").append(end).append('\n');
        if (step != 1) sb.append("步长:").append(step).append('\n');
        if (!L.s("数字", "Digits").equals(mode)) sb.append("进制:").append(mode).append('\n');
        return sb.toString();
    }

    /**
     * 估算会生成多少条。用于界面提示，避免用户不小心建了个几亿条的规则。
     */
    public long estimateCount() {
        if (!isValid()) return 0;
        try {
            long s = Long.parseLong(start);
            long e = Long.parseLong(end);
            if (e < s) return 0;
            return (e - s) / Math.max(1, step) + 1;
        } catch (NumberFormatException ex) {
            // 非纯数字的起始/末尾：按字符串长度估算
            return 0;
        }
    }

    /**
     * 展开成候选列表。带硬上限，防止内存爆掉。
     *
     * @param max 最多生成多少条
     */
    public List<Candidate> expand(int max) {
        List<Candidate> out = new ArrayList<>();
        if (!isValid()) return out;
        int limit = max <= 0 ? 100_000 : max;

        try {
            long s = Long.parseLong(start);
            long e = Long.parseLong(end);
            int width = start.length();   // 保留前导零
            for (long i = s; i <= e && out.size() < limit; i += Math.max(1, step)) {
                String num = pad(i, width);
                out.add(new Candidate(prefix + num + suffix, Candidate.Origin.DIGITS));
            }
        } catch (NumberFormatException ex) {
            // 字母/字母数字模式：把起止当作进制进位序列
            expandAlpha(out, limit);
        }
        return out;
    }

    private static String pad(long v, int width) {
        String s = String.valueOf(v);
        while (s.length() < width) s = "0" + s;
        return s;
    }

    /**
     * 字母/字母数字模式：把 start 当起点、end 当终点，按字符表进位。
     * 例：start=aaa end=zzz -> aaa, aab, aac ...
     */
    private void expandAlpha(List<Candidate> out, int limit) {
        String charset = L.s("字母", "Letters").equals(mode)
                ? "abcdefghijklmnopqrstuvwxyz"
                : "0123456789abcdefghijklmnopqrstuvwxyz";
        int width = Math.max(start.length(), 1);
        char[] buf = new char[width];
        // 起点填满
        for (int i = 0; i < width; i++) {
            buf[i] = i < start.length() ? start.charAt(i)
                    : charset.charAt(0);
        }
        int steps = 0;
        while (out.size() < limit && steps < 10_000_000) {
            String cur = new String(buf);
            if (end.length() == width) {
                if (cur.compareTo(end) > 0) break;
            }
            out.add(new Candidate(prefix + cur + suffix,
                    L.s("字母", "Letters").equals(mode) ? Candidate.Origin.LETTERS : Candidate.Origin.ALNUM));
            // 进位
            int pos = width - 1;
            while (pos >= 0) {
                int ci = charset.indexOf(Character.toLowerCase(buf[pos]));
                if (ci < 0) ci = 0;
                if (ci + 1 < charset.length()) {
                    buf[pos] = charset.charAt(ci + 1);
                    break;
                } else {
                    buf[pos] = charset.charAt(0);
                    pos--;
                }
            }
            if (pos < 0) break;
            steps++;
        }
    }

    @Override
    public String toString() {
        return "前缀[" + prefix + "] 后缀[" + suffix + "] "
                + start + " → " + end + " 步长" + step + " (" + mode + ")";
    }

    /** 给界面用的一句话描述 */
    public String summary() {
        long n = estimateCount();
        String cnt = n > 0 ? formatCount(n) + L.s(" 条", " entries") : "条数未知";
        return prefix + "?" + suffix + "  " + start + "→" + end + "  " + cnt;
    }

    public static String formatCount(long n) {
        if (n < 10_000) return String.valueOf(n);
        if (n < 100_000_000) return String.format(Locale.US, "%.1f万", n / 10_000.0);
        return String.format(Locale.US, "%.1f亿", n / 100_000_000.0);
    }
}
