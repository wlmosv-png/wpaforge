package io.github.wlmosv_png.wpaforge.wps;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * WPS PIN 候选生成。纯逻辑，可离线测试。
 *
 * WPS PIN 是 8 位十进制，但第 8 位是前 7 位的校验位，
 * 所以真实搜索空间约 10^7 若按顺序枚举 —— 实际攻击都会先用弱 PIN 和拆半法。
 */
public final class WpsPins {

    private WpsPins() {
    }

    /** WPS PIN 校验位：奇数位×3 + 偶数位×1 之和的补数。 */
    public static int checksum(String first7) {
        if (first7 == null || first7.length() != 7) return -1;
        int acc = 0;
        for (int i = 0; i < 7; i++) {
            char c = first7.charAt(i);
            if (c < '0' || c > '9') return -1;
            int d = c - '0';
            acc += (i % 2 == 0) ? d * 3 : d;
        }
        return (10 - acc % 10) % 10;
    }

    /** 8 位 PIN 是否校验位合法。 */
    public static boolean valid(String pin) {
        if (pin == null || pin.length() != 8) return false;
        char last = pin.charAt(7);
        if (last < '0' || last > '9') return false;
        int c = checksum(pin.substring(0, 7));
        return c >= 0 && c == (last - '0');
    }

    /** 由前 7 位补全成合法 8 位 PIN。 */
    public static String complete(String first7) {
        int c = checksum(first7);
        return c < 0 ? null : first7 + c;
    }

    /**
     * 弱 PIN 候选：重复数字、递增/递减序列等。
     * 命中率远高于随机猜测，且只有几十个 —— 几分钟就能跑完，
     * 值得在任何完整枚举之前先试。
     */
    public static List<String> weakDefaults() {
        String[] heads = {
                "1234567", "0000000", "1111111", "2222222", "3333333",
                "4444444", "5555555", "6666666", "7777777", "8888888",
                "9999999", "0123456", "9876543", "1000000", "2000000",
                "1212121", "1020304", "0123456", "1122334", "1231231",
        };
        Set<String> set = new LinkedHashSet<>();
        for (String h : heads) {
            String p = complete(h);
            if (p != null) set.add(p);
        }
        return new ArrayList<>(set);
    }

    /**
     * 完整前 7 位序列（升序），limit 为上限条数。
     *
     * <p><b>2026-11-08（第三阶段任务书）：不再作为生产候选路径。</b>
     * 10^7 条 × ~10s ≈ 3.2 年，物理上不可行；且修复前 <code>limit&lt;=0</code> 会
     * 真的构造 1000 万条字符串把内存打满。此方法仅保留给**离线测试**，
     * 并强制受 {@link io.github.wlmosv_png.wpaforge.wps.WpsCandidates#MAX_CANDIDATES}
     * 硬上限 —— 任何路径都不得突破（不靠 UI 隐藏规避内存风险）。
     */
    public static List<String> enumerate(int limit) {
        int cap = io.github.wlmosv_png.wpaforge.wps.WpsCandidates.MAX_CANDIDATES;
        int n = Math.min(limit <= 0 ? cap : limit, cap);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String p = complete(pad7(i));
            if (p != null) out.add(p);
        }
        return out;
    }

    static String pad7(int v) {
        String s = Integer.toString(v);
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < 7; i++) sb.append('0');
        return sb.append(s).toString();
    }

    /**
     * 从指定序号开始生成 count 个候选，用于断点续跑。
     * 序号语义与 {@link #enumerate(int)} 一致（第 0 条 = 00000000）。
     */
    public static List<String> enumerateRange(int fromIndex, int count) {
        List<String> out = new ArrayList<>();
        if (count <= 0) return out;
        // 防御性硬上限（正常不会触发，防止调用方误传巨型 count）
        int cap = io.github.wlmosv_png.wpaforge.wps.WpsCandidates.MAX_CANDIDATES;
        int start = Math.max(0, fromIndex);
        int end = start + Math.min(count, cap);
        for (int i = start; i < end; i++) {
            String p = complete(pad7(i));
            if (p != null) out.add(p);
        }
        return out;
    }

    /** 完整枚举的候选总数（前 7 位 10^7 种）。 */
    public static int enumerateTotal() {
        return 10000000;
    }
}
