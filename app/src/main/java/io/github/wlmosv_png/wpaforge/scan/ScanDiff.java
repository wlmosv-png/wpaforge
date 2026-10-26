package io.github.wlmosv_png.wpaforge.scan;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 两次扫描结果的差异（纯逻辑，可离线单测）。
 *
 * <h3>为什么刷新要有「差异」</h3>
 * 只给一个「重新扫描」按钮，用户点完看到的是同一张图 —— 他会怀疑按钮没用。
 * 把「新增了哪几台、消失了哪几台」直接说出来，刷新这件事才有信息量：
 *
 * <pre>
 *   最后更新 3 秒前 · 新增 2 台 · 消失 1 台
 * </pre>
 *
 * <p>按 **BSSID** 比对（同一台 AP 的唯一身份）；SSID 会重名、隐藏网络没名字，
 * 拿名字比会瞎报。
 */
public final class ScanDiff {

    public static final class Result {
        /** 这次新出现的 AP 数 */
        public final int added;
        /** 这次不见了 AP 数 */
        public final int removed;
        /** 两次都在的 AP 数 */
        public final int kept;
        /** 是不是第一次扫描（没有可比的上一次） */
        public final boolean firstEver;

        Result(int added, int removed, int kept, boolean firstEver) {
            this.added = added;
            this.removed = removed;
            this.kept = kept;
            this.firstEver = firstEver;
        }

        /** 有没有值得说的变化 */
        public boolean changed() {
            return added > 0 || removed > 0;
        }

        public String label(String sep) {
            if (firstEver) return "";
            if (!changed()) return "";
            StringBuilder sb = new StringBuilder();
            if (added > 0) sb.append("新增 ").append(added).append(" 台");
            if (removed > 0) {
                if (sb.length() > 0) sb.append(sep);
                sb.append("消失 ").append(removed).append(" 台");
            }
            return sb.toString();
        }
    }

    private ScanDiff() {}

    /**
     * @param prev 上次的 BSSID 列表；null = 还没有上一次
     * @param cur  这次的 BSSID 列表
     */
    public static Result of(List<String> prev, List<String> cur) {
        Set<String> a = norm(prev);
        Set<String> b = norm(cur);
        if (prev == null) {
            return new Result(0, 0, b.size(), true);
        }
        int added = 0, removed = 0, kept = 0;
        for (String s : b) {
            if (a.contains(s)) kept++;
            else added++;
        }
        for (String s : a) {
            if (!b.contains(s)) removed++;
        }
        return new Result(added, removed, kept, false);
    }

    /** BSSID 归一化：去空白 + 大写（系统在不同版本下大小写不一致） */
    private static Set<String> norm(List<String> in) {
        Set<String> out = new HashSet<>();
        if (in == null) return out;
        for (String s : in) {
            if (s == null) continue;
            String t = s.trim().toUpperCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
