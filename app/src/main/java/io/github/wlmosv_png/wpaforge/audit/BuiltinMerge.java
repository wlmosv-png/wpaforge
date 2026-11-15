package io.github.wlmosv_png.wpaforge.audit;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 内置字典合并（跨本去重）—— 2026-11-15。
 *
 * <h3>它解决什么</h3>
 * 核心档 7 本是各自独立攒起来的，头部高度重叠：
 * {@code 12345678} 一条同时躺在 **8 本**里。实测合计 13,545 条，**并集只有 10,765 条**，
 * 重复 20.5%。
 *
 * <p>运行期其实有 {@code triedSet} 兜底，但它的语义是「已**证实**为错的候选」——
 * 只有 {@code AUTH_FAIL + DIRECT} 才写进去。在拿不到候选级证据的目标上
 * （本机实测大量结果是「无结论」），同一批头部口令会被**每一本各试一遍**：
 * 按 7 秒/条算，光重复就白烧约 5.4 小时。
 *
 * <h3>为什么合并而不是删掉重复</h3>
 * 「浓缩」在这里的含义是**归并来源，不是砍掉内容**：原始 7 本一条不动
 * （用户可能单独用某一本，也可能已经改过某一本），只在需要跑整套时
 * 用这一份去重后的并集。本机实战样本只有 172 次，远不足以判定「尾部无用」——
 * 所以长尾一条都不丢。
 *
 * <p>纯逻辑 + 文件读取，可在 JVM 完整测试（见 {@code BuiltinDictAuditTest}）。
 */
public final class BuiltinMerge {

    /** 一次合并的结果。 */
    public static final class Out {
        /** 去重后按顺序排好的口令 */
        public final List<String> lines;
        /** 各本有效条数之和（去重前） */
        public final int rawCount;
        /** 每本对并集的**净增**条数，顺序与传入的书单一致 */
        public final List<Integer> addedPerBook;

        Out(List<String> lines, int rawCount, List<Integer> added) {
            this.lines = lines;
            this.rawCount = rawCount;
            addedPerBook = added;
        }

        public int keptCount() {
            return lines.size();
        }

        public int duplicates() {
            return rawCount - lines.size();
        }

        /** 重复率百分比 */
        public double dupPercent() {
            return rawCount <= 0 ? 0 : 100.0 * duplicates() / rawCount;
        }
    }

    /** 从哪里取某一本的行（生产读磁盘，门禁读 assets —— 同一套逻辑两处都能用）。 */
    public interface LineSource {
        List<String> lines(BuiltinDicts.Item item);
    }

    private BuiltinMerge() {}

    /**
     * 按 book 顺序合并去重。
     *
     * <p>顺序**即优先级**：前面那本先出，后面重复的直接跳过。
     * 所以调用方给的书单顺序就是「谁在前谁占位」，与 {@code SourceRank} 的执行顺序无关 ——
     * 这里是**内容去重**，排序是另一件事（用户开智能排序时由 {@code SmartSorter} 再排一次）。
     */
    public static Out merge(List<BuiltinDicts.Item> books, LineSource src) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Integer> added = new ArrayList<>();
        int raw = 0;

        if (books != null && src != null) {
            for (BuiltinDicts.Item it : books) {
                List<String> lines = src.lines(it);
                if (lines == null) lines = new ArrayList<>();
                int n = 0;
                for (String l : lines) {
                    if (l == null) continue;
                    String p = l.trim();
                    if (p.isEmpty() || p.startsWith("#")) continue;
                    if (p.length() < 8 || p.length() > 63) continue;   // 与运行时闸门同口径
                    raw++;
                    if (seen.add(p)) {
                        out.add(p);
                        n++;
                    }
                }
                added.add(n);
            }
        }
        return new Out(out, raw, added);
    }

    /** 当前清单里**参与合并**的本：核心档，且不是规则字典（规则按区间展开，没有行可合并）。 */
    public static List<BuiltinDicts.Item> mergeableCore() {
        List<BuiltinDicts.Item> out = new ArrayList<>();
        for (BuiltinDicts.Item it : BuiltinDicts.itemsOfTier(BuiltinDicts.TIER_CORE)) {
            if (it.asset.startsWith("rule_")) continue;
            out.add(it);
        }
        return out;
    }

    /** 读**磁盘上已释放**的字典目录（用户可能改过，以盘上为准）。 */
    public static LineSource dirSource(final File dir) {
        return item -> {
            File f = new File(dir, item.fileName());
            if (!f.isFile() || f.length() <= 0) {
                File alt = new File(dir, item.nameEn);
                if (alt.isFile() && alt.length() > 0) f = alt;   // 老版本可能按英文名释放过
                else return new ArrayList<>();
            }
            return readLines(f);
        };
    }

    /** 读 assets 里出厂的那份（门禁与「没释放时」用）。 */
    public static LineSource assetSource(final android.content.Context ctx) {
        return item -> {
            List<String> out = new ArrayList<>();
            try (java.io.InputStream in = ctx.getAssets().open("builtin/" + item.asset)) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) out.add(line);
            } catch (Exception ignored) {
            }
            return out;
        };
    }

    /** 逐行读（UTF-8，去 BOM/CR，跳过空行与注释）。 */
    public static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        if (f == null || !f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), "UTF-8"), 1 << 16)) {
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) {
                    line = line.replace("\uFEFF", "");
                    first = false;
                }
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                out.add(t);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 合并出来的这份叫什么（放在字典列表最前面，"00" 前缀保证排序靠前）。 */
    public static final String MERGED_NAME_ZH = "00 精华包（内置去重合并）.txt";
    public static final String MERGED_NAME_EN = "00 Essential pack (built-in, de-duplicated).txt";

    public static String mergedName() {
        return io.github.wlmosv_png.wpaforge.L.s(MERGED_NAME_ZH, MERGED_NAME_EN);
    }
}
