package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 生日表生成器 —— 替代旧版那份 483,522 条的静态 assets。
 *
 * <p><b>为什么要换。</b>旧版 {@code 05 内置号码变形.txt} 是从幻影 WiFi 整包搬来的
 * （逐字节相同），内容 = 26 个字母 × 18,597 个 8 位日期，占 5.3 MB 包体。
 * 形态本身没错 —— 公开统计（48,330 条真实 SSID + 密码样本）里，
 * 11 位「字母 + 数字」口令中有 31% 是「三位字母 + 八位数字」，
 * 且字母约七成是姓名首字母、数字九成是生日。本机也确实命中了 {@code a19500201}。
 *
 * <p>问题在于**用静态穷举去覆盖一个本该由线索决定的东西**：
 * 本机 6 次使用里 6 次都在第 2 条就命中，后面 48 万条是白占空间；
 * 而真正该做的是「谁的名字首字母 + 哪一年到哪一年」，由用户和线索决定。
 *
 * <p>所以这里只提供**生成能力**：给定年份区间产出合法日期，
 * 给定前缀产出「前缀 + 生日」。条数、耗时都在界面上先说清楚
 * （{@link CostMeter#estimateText}），跑不跑由用户决定。
 */
public final class BirthdayTable {

    /** 默认年份区间：1960–2010（家用 AP 的主人在这个区间里出生） */
    public static final int DEFAULT_Y1 = 1960;
    public static final int DEFAULT_Y2 = 2010;

    private BirthdayTable() {}

    /** 区间内合法日期，格式 {@code YYYYMMDD}，按年份升序 */
    public static List<String> dates(int y1, int y2) {
        List<String> out = new ArrayList<>();
        int a = Math.min(y1, y2), b = Math.max(y1, y2);
        for (int y = Math.max(1900, a); y <= Math.min(2099, b); y++) {
            for (int m = 1; m <= 12; m++) {
                for (int d = 1; d <= daysInMonth(y, m); d++) {
                    out.add(String.format("%04d%02d%02d", y, m, d));
                }
            }
        }
        return out;
    }

    /** 区间内的日期条数（不生成，用于界面先报数） */
    public static int count(int y1, int y2) {
        int n = 0;
        int a = Math.min(y1, y2), b = Math.max(y1, y2);
        for (int y = Math.max(1900, a); y <= Math.min(2099, b); y++) {
            n += isLeap(y) ? 366 : 365;
        }
        return n;
    }

    /** 单个月份的合法天数 */
    static int daysInMonth(int y, int m) {
        switch (m) {
            case 1: case 3: case 5: case 7: case 8: case 10: case 12: return 31;
            case 4: case 6: case 9: case 11: return 30;
            case 2: return isLeap(y) ? 29 : 28;
            default: return 0;
        }
    }

    static boolean isLeap(int y) {
        return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
    }

    /**
     * 一批候选的预估耗时（走 {@link CostMeter#estimateText} 统一口径）。
     * 前缀数 × 日期数，就是 {@link DictGenerator#prefixedBirthday} 的规模。
     */
    public static String estimateText(int prefixes, int y1, int y2) {
        int n = count(y1, y2) * Math.max(1, prefixes);
        return CostMeter.estimateText(n);
    }

    /** 当前年份，供界面把默认区间写成「到今年」 */
    public static int currentYear() {
        return Calendar.getInstance().get(Calendar.YEAR);
    }
}
