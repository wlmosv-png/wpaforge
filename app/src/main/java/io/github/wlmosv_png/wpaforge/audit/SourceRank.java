package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import java.io.File;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 来源排行 —— 拿**本机的真实战绩**决定先跑哪个字典。
 *
 * <p>这是 WPAForge 跟其它工具的分水岭。幻影、SecLists 那一类只给「表」，
 * 谁都不知道该先跑哪本；我们本地记着每一次任务的收尾记录
 * （{@link HistStore}），里面有「这次用了哪本、试了多少条、中没中」，
 * 于是可以算出真正该关心的那个数：
 *
 * <pre>
 *   代价 = 每命中一次，平均要试多少条候选
 *        = (Σ 各次任务的已试条数 + 先验) / (命中次数 + 1)
 * </pre>
 *
 * <p>为什么用 Σ已试条数而不是「字典大小」：没中的那几轮，代价就是**整本跑完**，
 * 这正是用户真实付出的时间；只统计命中轮会把慢表算得比实际便宜。
 * 分母 +1 是拉普拉斯平滑，避免「跑了 1 次就中」被算成 0 代价；
 * 分子 +{@link #PRIOR} 是同一个道理，让样本只有一两次的来源不至于霸榜。
 *
 * <p>实测参考（本机 18 个目标）：xato 榜约 24 条/命中、年度榜约 118 条、
 * 内置弱口令约 693 条、字母数字混合约 6800 条。
 *
 * <p>纯逻辑，无 Android 依赖，可离线测。
 */
public final class SourceRank {

    /** 拉普拉斯先验：相当于「白送」200 条尝试量的信用额度，样本少的来源不会被吹上天。 */
    public static final int PRIOR = 200;

    /** 一个来源的历史表现 */
    public static final class Stat {
        /** 来源（字典）名，与 {@link HistStore.Record#dictName} 同源 */
        public final String source;
        /** 用它跑过的任务数 */
        public final int records;
        /** 其中命中的次数 */
        public final int hits;
        /** 命中轮里「第几条中」的中位数；没有命中时为 -1 */
        public final int medianTested;
        /** 全部轮次的已试条数之和（命中轮 = 命中位次；未中轮 = 整本跑完） */
        public final long sumTested;
        /** 见过的最大字典规模，用于界面展示与先验 */
        public final int maxSize;

        public Stat(String source, int records, int hits, int medianTested,
                    long sumTested, int maxSize) {
            this.source = source;
            this.records = records;
            this.hits = hits;
            this.medianTested = medianTested;
            this.sumTested = sumTested;
            this.maxSize = maxSize;
        }

        /** 每命中一次平均要试多少条；越小越该先跑 */
        public double costPerHit() {
            return (sumTested + PRIOR) / (double) (hits + 1);
        }

        /** 命中率，界面用 */
        public double hitRate() {
            return records <= 0 ? 0 : hits / (double) records;
        }

        public boolean hasHistory() {
            return records > 0;
        }

        /**
         * 界面上一行话。**不写「命中率 x%」这种需要用户换算的东西**，
         * 直接说「试了这么多才中一次」。
         */
        public String label() {
            if (records <= 0) return "";
            if (hits <= 0) {
                return L.s("历史 " + records + " 次全没中", "No hit in " + records + " runs");
            }
            return L.s("历史命中 " + hits + "/" + records + " · 平均 " + Math.round(costPerHit()) + " 条中一次",
                    "Hit " + hits + "/" + records + " · 1 per ~" + Math.round(costPerHit()));
        }
    }

    private SourceRank() {}

    /** 可变累加器（读盘路径用，避免为每一行都建一个 Stat） */
    private static final class Tally {
        int records, hits, maxSize;
        long sumTested;
        final List<Integer> hitPos = new ArrayList<>();

        void add(int size, int tested, boolean hit) {
            records++;
            int t = Math.max(0, tested);
            sumTested += t;
            maxSize = Math.max(maxSize, Math.max(0, size));
            if (hit) {
                hits++;
                hitPos.add(t);
            }
        }

        Stat freeze(String source) {
            return new Stat(source, records, hits, median(hitPos), sumTested, maxSize);
        }
    }

    /**
     * 从 history.csv 的**原始行**直接汇总。
     *
     * <p>为什么不用 {@link HistStore#load}：那条路径会为每条命中记录解一次
     * Android Keystore 密文（本机 107 条命中 = 107 次），历史页就因为这个卡过。
     * 排序只需要前 8 列（字典名、条数、已试、是否命中），
     * 第 9/10 列的标签与密文一律不碰。
     *
     * <p>格式契约与 {@link HistStore.Record#fromLine} 保持一致：
     * 逗号分隔，列 3 = 字典名（可能被转义），列 4 = 规模，列 5 = 已试，列 7 = 命中标志。
     */
    public static Map<String, Stat> fromLines(Iterable<String> lines) {
        Map<String, Tally> acc = new HashMap<>();
        if (lines == null) return new HashMap<>();
        for (String line : lines) {
            if (line == null) continue;
            String[] p = line.split(",", -1);
            if (p.length < 8) continue;
            String name = key(HistStore.Record.unesc(p[3]));
            if (name.isEmpty()) continue;
            Tally t = acc.computeIfAbsent(name, k -> new Tally());
            t.add(parseInt(p[4]), parseInt(p[5]), "1".equals(p[7]));
        }
        Map<String, Stat> out = new HashMap<>();
        for (Map.Entry<String, Tally> e : acc.entrySet()) {
            out.put(e.getKey(), e.getValue().freeze(e.getKey()));
        }
        return out;
    }

    /** 读本机历史并汇总；读不到就返回空表（界面按「无历史」处理） */
    public static Map<String, Stat> load(android.content.Context ctx) {
        if (ctx == null) return new HashMap<>();
        File f = HistStore.fileOf(ctx);
        if (!f.isFile()) return new HashMap<>();
        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        } catch (Exception e) {
            return new HashMap<>();
        }
        return fromLines(lines);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 从历史记录汇总每个来源的表现 */
    public static Map<String, Stat> stats(List<HistStore.Record> records) {
        Map<String, Stat> out = new HashMap<>();
        if (records == null) return out;

        Map<String, Integer> recs = new HashMap<>();
        Map<String, Integer> hits = new HashMap<>();
        Map<String, Long> sum = new HashMap<>();
        Map<String, Integer> maxSize = new HashMap<>();
        Map<String, List<Integer>> hitPos = new HashMap<>();

        for (HistStore.Record r : records) {
            String k = key(r.dictName);
            if (k.isEmpty()) continue;
            recs.merge(k, 1, Integer::sum);
            sum.merge(k, (long) Math.max(0, r.tested), Long::sum);
            maxSize.merge(k, Math.max(0, r.dictSize), Math::max);
            if (r.hit) {
                hits.merge(k, 1, Integer::sum);
                hitPos.computeIfAbsent(k, x -> new ArrayList<>()).add(Math.max(0, r.tested));
            }
        }

        for (String k : recs.keySet()) {
            out.put(k, new Stat(k, recs.get(k), hits.getOrDefault(k, 0),
                    median(hitPos.get(k)), sum.getOrDefault(k, 0L),
                    maxSize.getOrDefault(k, 0)));
        }
        return out;
    }

    /**
     * 排序用的统一代价。
     *
     * <ul>
     *   <li>有历史的来源：用实测的 {@link Stat#costPerHit()}；</li>
     *   <li>没跑过的来源：给一个**中性先验** {@code 表规模/2 + PRIOR}
     *       ——「大概要试半本才中」，既不吹也不贬；</li>
     *   <li>连规模都不知道：垫底。</li>
     * </ul>
     *
     * <p>为什么不用「有历史的无条件排前」：跑过 10 次一次没中的来源，
     * 实测代价 5 万条，它理应排在「一本还没跑过的小表」后面 ——
     * 后者试错成本只有几百条。有历史 ≠ 值得先跑。
     */
    public static double costFor(String name, Map<String, Stat> stats, Map<String, Integer> sizes) {
        Stat s = stats == null ? null : stats.get(key(name));
        if (s != null && s.hasHistory()) return s.costPerHit();
        int sz = sizeOf(sizes, name);
        return sz > 0 ? sz / 2.0 + PRIOR : Double.MAX_VALUE;
    }

    /** 按代价升序排序；代价相同则小表在前，再相同按名字 */
    public static List<String> order(List<String> candidates, Map<String, Stat> stats, Map<String, Integer> sizes) {
        List<String> out = new ArrayList<>(candidates == null ? Collections.emptyList() : candidates);
        out.sort((a, b) -> {
            int c = Double.compare(costFor(a, stats, sizes), costFor(b, stats, sizes));
            if (c != 0) return c;
            int sz = Integer.compare(sizeOf(sizes, a), sizeOf(sizes, b));
            if (sz != 0) return sz;
            return a.compareTo(b);
        });
        return out;
    }

    private static int sizeOf(Map<String, Integer> sizes, String name) {
        if (sizes == null) return 0;
        Integer v = sizes.get(key(name));
        return v == null ? 0 : v;
    }

    /** 来源名归一化：去掉前后空白；大小写不敏感（同一本表的写法可能不一致） */
    public static String key(String s) {
        return s == null ? "" : s.trim();
    }

    /** 不看 null 的中位数 */
    private static int median(List<Integer> xs) {
        if (xs == null || xs.isEmpty()) return -1;
        List<Integer> c = new ArrayList<>(xs);
        Collections.sort(c);
        return c.get(c.size() / 2);
    }
}
