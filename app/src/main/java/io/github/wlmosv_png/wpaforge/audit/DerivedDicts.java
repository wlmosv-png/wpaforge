package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 派生产物（缓存）的识别与清理。
 *
 * <h3>为什么需要这个类</h3>
 * 「定向派生」（旧名「SSID 派生」）和「内置生成器」每次用完都会往字典目录里
 * 丢一个文件。它们是**缓存**：由目标 + 线索就能重算，本身没有独立价值，
 * 但会一直堆着 —— 用户的原话是「用一次就不用了，留着看起来很多，还很麻烦」。
 *
 * <p>所以要做三件事，且必须做对：
 * <ol>
 *   <li><b>识别</b>：按名字前缀认出派生产物，含历史命名
 *       （旧版 {@code SSID 派生 · X} 与 {@code 00 内置生成器.txt} 都算）；</li>
 *   <li><b>滚动</b>：写新的派生产物之前，删掉**同一目标**的旧产物，
 *       让「跑一次多一份」变成「每个目标最多一份」；</li>
 *   <li><b>清理</b>：一键删净，但**绝不碰正在排队/正在跑的任务引用的文件**
 *       —— 那些路径一旦消失，续跑的 offset 就对不上了。</li>
 * </ol>
 *
 * <p>纯逻辑（只有列举目录一处 IO），可离线单测。
 */
public final class DerivedDicts {

    /** 中文名前缀（含历史命名，删任何一个都要先看有没有老用户文件） */
    private static final String[] PREFIX_ZH = {
            "SSID 派生 · ", "SSID 派生", "定向派生 · ",
            "内置生成器", "00 内置生成器",
    };

    /** 英文名前缀 */
    private static final String[] PREFIX_EN = {
            "SSID-derived ", "Targeted · ",
            "Built-in generator", "00 Built-in generator",
    };

    /** 全部内置生成器产物归同一个组 */
    public static final String GROUP_GENERATOR = "__generator__";

    /** 线索指纹的形状：{@link SeedModel#fingerprint()} 给的是 8 位十六进制 */
    private static final String FINGERPRINT = "[0-9a-fA-F]{6,12}";

    private DerivedDicts() {}

    /** 内置生成器的规范名 —— **唯一入口**，别再出现 `00 内置生成器` 这种第二名字 */
    public static String generatorName() {
        return L.s("内置生成器", "Built-in generator");
    }

    /** 定向派生的文件名：目标 [+ 线索指纹] */
    public static String targetedName(String ssid, String fingerprint) {
        String base = L.s("定向派生 · ", "Targeted · ") + ssid;
        return (fingerprint == null || fingerprint.isEmpty()
                || "none".equals(fingerprint)) ? base : base + " · " + fingerprint;
    }

    /** 去掉扩展名 —— 字典列表与历史记录里的名字都没有扩展名 */
    public static String stripExt(String name) {
        return name == null ? "" : name.replaceAll("\\.(txt|lst|dic)$", "");
    }

    /** 这个（可带扩展名的）名字是不是派生产物 */
    public static boolean isDerivedName(String name) {
        String s = stripExt(name).trim();
        if (s.isEmpty()) return false;
        for (String p : PREFIX_ZH) if (s.startsWith(p)) return true;
        String low = s.toLowerCase(Locale.ROOT);
        for (String p : PREFIX_EN) if (low.startsWith(p.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static boolean isGeneratorName(String s) {
        return s.startsWith("内置生成器") || s.startsWith("00 内置生成器")
                || s.toLowerCase(Locale.ROOT).contains("built-in generator");
    }

    /**
     * 同目标归组键。
     *
     * <p>带指纹的写法 {@code 定向派生 · B209 · 55d32539} 与不带的
     * {@code 定向派生 · B209} 是**同一个目标**，必须归到一组 ——
     * 否则滚动删除永远删不掉旧的那份。
     *
     * <p>只把<strong>看起来就是指纹</strong>的最后一段（6~12 位十六进制）去掉：
     * 旧名 {@code SSID 派生 · 2.4G-3} 没有指纹，若一律按最后一个分隔符切，
     * 会把「SSID 派生」当成组名，一次滚动就把别的目标的产物也删了。
     */
    public static String groupKey(String name) {
        String s = stripExt(name).trim();
        if (isGeneratorName(s)) return GROUP_GENERATOR;
        int at = s.lastIndexOf(" · ");
        if (at > 0) {
            String tail = s.substring(at + 3).trim();
            if (tail.matches(FINGERPRINT)) return s.substring(0, at);
        }
        return s;
    }

    /** 姓名取值器：让本类不绑定 DictStore.Entry 的具体类型，方便单测 */
    public interface NameOf<T> { String name(T t); }

    /** 路径取值器 */
    public interface PathOf<T> { String path(T t); }

    /** 一批条目里的派生产物 */
    public static <T> List<T> derivedOnly(List<T> all, NameOf<T> nameOf) {
        List<T> out = new ArrayList<>();
        if (all == null) return out;
        for (T e : all) if (isDerivedName(nameOf.name(e))) out.add(e);
        return out;
    }

    /**
     * 清理对象 = 派生产物 − 保护名单。
     *
     * @param protectedPaths 正在排队 / 正在跑的任务引用的绝对路径（**必须**排除）
     */
    public static <T> List<T> victims(List<T> all, NameOf<T> nameOf, PathOf<T> pathOf,
                                     Set<String> protectedPaths) {
        List<T> out = new ArrayList<>();
        if (all == null) return out;
        Set<String> prot = protectedPaths == null ? new HashSet<>() : protectedPaths;
        for (T e : all) {
            if (!isDerivedName(nameOf.name(e))) continue;
            String p = pathOf.path(e);
            if (p != null && prot.contains(p)) continue;
            out.add(e);
        }
        return out;
    }

    /** 从任务队列里取出「不能删」的字典路径（排队中、正在跑、暂停中都要保） */
    public static Set<String> protectedPaths(List<TaskQueue.Task> tasks) {
        Set<String> out = new HashSet<>();
        if (tasks == null) return out;
        for (TaskQueue.Task t : tasks) {
            if (t == null || t.dictPath == null || t.dictPath.isEmpty()) continue;
            if (t.state == TaskQueue.State.PENDING
                    || t.state == TaskQueue.State.RUNNING
                    || t.state == TaskQueue.State.PAUSED) {
                out.add(t.dictPath);
            }
        }
        return out;
    }

    /**
     * 字典目录里「同一组」的旧派生产物 —— 写新文件之前先删它们。
     *
     * @param newName 即将写入的文件名（不带目录）
     */
    public static List<File> sameGroup(File dir, String newName) {
        List<File> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        String g = groupKey(newName), self = stripExt(newName).trim();
        for (File f : fs) {
            if (!f.isFile()) continue;
            String n = stripExt(f.getName()).trim();
            if (n.equals(self)) continue;            // 同名 = 覆盖写，不是旧产物
            if (!isDerivedName(n)) continue;
            if (groupKey(n).equals(g)) out.add(f);
        }
        return out;
    }

    /** 一批文件的总字节数（界面显示「清理后可省 X」） */
    public static long totalBytes(List<File> files) {
        long n = 0;
        if (files == null) return 0;
        for (File f : files) if (f != null && f.isFile()) n += f.length();
        return n;
    }

    /** 字节日志：1.2 MB / 112 KB */
    public static String sizeLabel(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
