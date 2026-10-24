package io.github.wlmosv_png.wpaforge.ui;

import java.util.Locale;

/**
 * 破解页 HUD 的数字格式化（纯逻辑，桌面 JVM 可测）。
 *
 * <h3>为什么抽出来</h3>
 * 改版前这一页同一件事说了三遍：
 * 「运行中 2 / 13255」「整个字典 0.0%」「已试 2 / 13255」——
 * 三个数字块表达同一个进度，还都是小字。
 * 现在只留：一条进度 + 一行 ETA（大字）+ 一行计数与速率（小字）。
 * 数字怎么取整、怎么加千位分隔、慢速时怎么表达，全在这里，可测。
 *
 * <h3>设计上的两条硬规定</h3>
 * <ol>
 *   <li><b>速度用「条/分」不用「条/秒」。</b>本机实测单条 2~6 秒，
 *       换成条/秒会出现 0.2 这种看着像「没在动」的数字。</li>
 *   <li><b>数字带千位分隔</b>。13255 一眼看不出量级，13,255 可以。</li>
 * </ol>
 */
public final class CrackHud {

    private CrackHud() {}

    /** 千位分隔；负数按 0 处理（进度里不该出现负数）。 */
    public static String thousands(long n) {
        if (n <= 0) return "0";
        String s = Long.toString(n);
        StringBuilder sb = new StringBuilder();
        int head = s.length() % 3;
        if (head > 0) sb.append(s, 0, head);
        for (int i = head; i < s.length(); i += 3) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s, i, i + 3);
        }
        return sb.toString();
    }

    /** 进度百分比（一位小数）。total <= 0 时返回 0。 */
    public static double percent(int tested, int total) {
        if (total <= 0) return 0d;
        double p = (double) tested / (double) total * 100d;
        if (p < 0) return 0d;
        return Math.min(100d, p);
    }

    /**
     * 「6.2%」——精度随量级走。
     *
     * <p>为什么不能一律一位小数：字典动辄几万到二十万条，跑到第 1 条时
     * 「0.0%」会**一直**显示「0.0%」（几十分钟都不动），看着像卡死。
     * 现在：&lt;0.1% 给三位小数、&lt;1% 给两位、其余一位。
     */
    public static String percentLabel(int tested, int total) {
        double p = percent(tested, total);
        if (p <= 0d) return "0.0%";                      // 还没开始：保持原来的 0.0%
        if (p < 0.1d) return String.format(Locale.US, "%.3f%%", p);
        if (p < 1d) return String.format(Locale.US, "%.2f%%", p);
        return String.format(Locale.US, "%.1f%%", p);
    }

    /** 「1,204 / 13,255」 */
    public static String countsLabel(int tested, int total) {
        return thousands(tested) + " / " + thousands(total);
    }

    /** 速率：条/分。perSec <= 0 时返回空串（由调用方决定是否拼这一节）。 */
    public static String ratePerMin(double perSec) {
        if (perSec <= 0) return "";
        double perMin = perSec * 60d;
        if (perMin < 10d) return String.format(Locale.US, "%.1f 条/分", perMin);
        return String.format(Locale.US, "%.0f 条/分", perMin);
    }

    /**
     * 「1,204 / 13,255 · 5.4 条/分」——进度下方那一行小字。
     *
     * <p>速率还不知道时**只显示数量**，不要拼一个占位符号上去：
     * 「15 / 13,255 · —」在真机上看着像坏掉了（2026-10-24 反馈）。
     */
    public static String footnote(int tested, int total, double perSec) {
        if (perSec <= 0) return countsLabel(tested, total);
        return countsLabel(tested, total) + " · " + ratePerMin(perSec);
    }

    /**
     * 剩余时间（毫秒）→ 「剩 2 小时 09 分」。
     *
     * @param etaMs 负数＝还不知道（刚开始跑，样本不够）
     * @return 拿不到剩余时间时返回空串，调用方据此不显示这一行
     */
    public static String etaLabel(long etaMs) {
        if (etaMs < 0) return "";
        long s = etaMs / 1000;
        if (s < 60) return "剩 " + s + " 秒";
        long m = s / 60;
        if (m < 60) return "剩 " + m + " 分";
        long h = m / 60;
        // 超过一天就说天数：二十万条字典跑起来 ETA 是 200 小时往上，
        // 「剩 211 小时 36 分」既读不出量级，也让整行数字长得离谱。
        if (h >= 24) return "剩 " + (h / 24) + " 天 " + (h % 24) + " 小时";
        return "剩 " + h + " 小时 " + (m % 60) + " 分";
    }
}
