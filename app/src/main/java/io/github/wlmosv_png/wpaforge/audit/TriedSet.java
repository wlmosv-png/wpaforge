package io.github.wlmosv_png.wpaforge.audit;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/**
 * 某个目标上**已经试过的候选集合**（按目标分开存）。
 *
 * ── 它解决什么问题 ────────────────────────────────────────────
 * 用户跑完一本字典、再换一本，以前会把重叠的部分**再跑一遍**。
 * 实测一条 3.6 秒：两本各 3000 条、重叠 1500 条，白跑 1.5 小时。
 *
 * ── 为什么用哈希而不是 offset ─────────────────────────────────
 *   offset     只对"同一份字典、同一顺序"有效 —— 换字典/改顺序就作废
 *   哈希集合   对"任何字典组合、任何顺序"都有效
 * 所以换字典、合并字典、往池里加字典，这个集合都照常生效。
 *
 * ── 三条硬约束 ───────────────────────────────────────────────
 * 1. **只存 8 字节哈希，不存原文** —— 沿用明文卫生原则。
 * 2. **绝不跨目标共享** —— 同一个候选在 A 网失败 ≠ 在 B 网失败，
 *    共享会直接漏掉正确答案。文件按 BSSID 分开。
 * 3. **丢失/损坏的代价是重复劳动，不是错误结论** —— 读不出来就当空集合，
 *    最坏情况重跑一遍。所以这个设计是安全降级的（见 load()）。
 */
public final class TriedSet {

    /** 单目标上限。一条 8 字节，100 万条 = 8 MB。超了只提示、不静默丢。 */
    public static final int MAX = 1_000_000;

    /**
     * 两段式存放，为了**摊还 O(log n)** 而不是 O(n)：
     *
     * <pre>
     *   [0, sortedN)  已排序、已去重  → 二分查找
     *   [sortedN, n)  新加进来的尾巴  → 线性扫（很短）
     * </pre>
     *
     * <p>上一版是"每次 add 都查一遍（触发全量排序）"，实测 12000 条要 323 ms，
     * 而且随条数平方增长 —— 这正是跑长任务时"越跑越卡"的那类坑。
     * 现在每 {@link #SORT_CHUNK} 条才整理一次，剩下的靠线性扫尾巴，
     * 而尾巴最多只有 SORT_CHUNK 条。
     */
    private long[] buf;
    private int n;
    /** [0, sortedN) 是已排序去重的 */
    private int sortedN;
    private boolean dirty;
    private boolean overflow;

    /** 尾巴攒到多少条就整理一次（越大越省 CPU，越大线性扫越慢） */
    private static final int SORT_CHUNK = 256;

    public TriedSet() {
        buf = new long[512];
    }

    /**
     * 记一行日志，但**吞掉一切异常**。
     *
     * <p>桌面单测跑在 android.jar 上，那里的 {@code android.util.Log} 是 stub，
     * 一调就抛 RuntimeException("Stub!")。这个方法让"损坏文件 → 安全降级"
     * 这条路径在单测里也能跑通，而在真机上行为不变。
     */
    private static void warn(String msg) {
        try {
            android.util.Log.w("WPAForge", msg);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 查询/写入

    /** 这个口令在本目标上试过没有 */
    public boolean contains(String pw) {
        if (pw == null || n == 0) return false;
        ensureSorted();
        return Arrays.binarySearch(buf, 0, n, hash(pw)) >= 0;
    }

    /**
     * 记一条已试。
     *
     * @return true 表示确实是新记录（重复的不算）
     */
    public boolean add(String pw) {
        if (pw == null) return false;
        long h = hash(pw);
        if (find(h)) return false;
        if (n >= MAX) {
            overflow = true;
            return false;
        }
        if (n == buf.length) {
            int cap = buf.length < 4096 ? buf.length * 2 : buf.length + (buf.length >> 2);
            buf = Arrays.copyOf(buf, Math.min(cap, MAX));
        }
        buf[n++] = h;
        dirty = true;
        if (n - sortedN > SORT_CHUNK) ensureSorted();   // 摊还整理
        return true;
    }

    /** 先查已排序前缀（二分），再线性扫尾巴（至多 SORT_CHUNK 条） */
    private boolean find(long h) {
        if (sortedN > 0 && Arrays.binarySearch(buf, 0, sortedN, h) >= 0) return true;
        for (int i = sortedN; i < n; i++) {
            if (buf[i] == h) return true;
        }
        return false;
    }

    /** 把尾巴并进已排序前缀：整体排序 + 去重 */
    private void ensureSorted() {
        if (sortedN == n) return;
        Arrays.sort(buf, 0, n);
        int w = 0;
        for (int i = 0; i < n; i++) {
            if (w == 0 || buf[w - 1] != buf[i]) buf[w++] = buf[i];
        }
        n = w;
        sortedN = w;
    }

    public int size() { return n; }

    public boolean isDirty() { return dirty; }

    /** 是否撞到上限（界面要提示，而不是让用户以为都试过了） */
    public boolean isOverflow() { return overflow; }

    public void markClean() { dirty = false; }

    public void clear() {
        n = 0;
        sortedN = 0;
        dirty = true;
    }

    // ---------------------------------------------------------------- 落盘

    /**
     * 读。**任何异常/损坏都返回空集合** —— 丢了的代价只是重复劳动，
     * 所以这里刻意不做"报错让上层决定"，直接把降级做在里面。
     */
    public static TriedSet load(File f) {
        TriedSet t = new TriedSet();
        if (f == null || !f.isFile()) return t;
        long len = f.length();
        if (len <= 0 || len % 8 != 0 || len / 8 > MAX) return t;
        int cnt = (int) (len / 8);
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] raw = new byte[cnt * 8];
            int read = 0;
            while (read < raw.length) {
                int r = in.read(raw, read, raw.length - read);
                if (r < 0) break;
                read += r;
            }
            if (read != raw.length) return t;   // 截断 → 当空集合
            t.buf = new long[Math.max(512, cnt)];
            for (int i = 0; i < cnt; i++) {
                long v = 0;
                for (int b = 0; b < 8; b++) {
                    v |= ((long) (raw[i * 8 + b] & 0xff)) << (8 * b);
                }
                t.buf[i] = v;
            }
            t.n = cnt;
            t.sortedN = 0;         // 落盘时是排好的，但文件可能被外部改过 —— 查前补一次排序
            t.dirty = false;
        } catch (Exception e) {
            warn("已试集合读取失败，按空集合继续: " + e);
            return new TriedSet();
        }
        return t;
    }

    /** 写：排序去重后按 8 字节小端连续落盘 */
    public boolean save(File f) {
        if (f == null) return false;
        try {
            ensureSorted();
            File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return false;
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] chunk = new byte[8 * 4096];
                int off = 0;
                for (int i = 0; i < n; i++) {
                    long v = buf[i];
                    int base = (i % 4096) * 8;
                    for (int b = 0; b < 8; b++) chunk[base + b] = (byte) (v >>> (8 * b));
                    if ((i + 1) % 4096 == 0) {
                        out.write(chunk);
                    }
                }
                int rest = n % 4096;
                if (rest > 0) out.write(chunk, 0, rest * 8);
                out.flush();
            }
            if (f.exists() && !f.delete()) return false;
            dirty = false;
            return tmp.renameTo(f) || f.exists();
        } catch (Exception e) {
            warn("已试集合写入失败: " + e);
            return false;
        }
    }

    // ---------------------------------------------------------------- 位置与哈希

    /**
     * 目标键：**BSSID 优先**（SSID 会重名，BSSID 不会），
     * BSSID 缺失时退化成 SSID 的哈希。
     */
    public static String targetKey(String bssid, String ssid) {
        String b = bssid == null ? "" : bssid.replaceAll("[^0-9a-fA-F]", "").toLowerCase(Locale.ROOT);
        if (b.length() >= 12) return "b" + b;
        String s = ssid == null ? "" : ssid.trim();
        if (s.isEmpty()) return "unknown";
        return "s" + Long.toHexString(hash(s));
    }

    /**
     * 已试集合文件。放在应用私有目录（不是 /sdcard），
     * 因为它属于运行状态、不该被文件管理器随便删。
     */
    public static File fileFor(Context ctx, String key) {
        return new File(new File(ctx.getFilesDir(), "tried"), key + ".tried");
    }

    public static File dirOf(Context ctx) {
        return new File(ctx.getFilesDir(), "tried");
    }

    /** 指纹：FNV-1a 64。不需要密码学强度 —— 只要"同串同值、不同串基本不同值"。 */
    public static long hash(String pw) {
        long h = 0xcbf29ce484222325L;
        byte[] b = pw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte x : b) {
            h ^= (x & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /**
     * 流式统计一个字典文件里有多少条命中本集合 —— **不把文件读进内存**。
     *
     * <p>为什么需要：合并后的字典可能几十万行，用 readLines 统计会一次性
     * 建几十万个 String，手机上很容易直接 OOM。这里逐行读、逐行判、只记数字。
     */
    public static int countIn(File dictFile, TriedSet tried) {
        if (dictFile == null || !dictFile.isFile() || tried == null || tried.size() == 0) return 0;
        int hits = 0;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(dictFile),
                        java.nio.charset.StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) {
                String v = line.replace("\uFEFF", "").trim();
                if (v.isEmpty() || v.startsWith("#")) continue;
                if (tried.contains(v)) hits++;
            }
        } catch (Exception e) {
            warn("统计已试条数失败: " + e);
        }
        return hits;
    }

    /** 纯逻辑版：给测试用，不碰文件系统 */
    public static int filterCount(Iterable<String> candidates, TriedSet tried) {
        int skipped = 0;
        for (String c : candidates) if (tried.contains(c)) skipped++;
        return skipped;
    }
}
