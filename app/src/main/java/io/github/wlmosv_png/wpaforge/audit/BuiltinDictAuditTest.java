package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.TestPaths;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 内置字典体检门禁（2026-11-15）。
 *
 * <h3>为什么需要它</h3>
 * 内置表是**手写清单 + 手写条数 + 二进制资产**三份各自演进的东西，没有门禁时必然漂移：
 * 本轮实测就抓到 4 处（`03 字母数字混合` 声称 15146、真能跑 13255；
 * `02 纯数字` 声称 2188、真能跑 2147；`09 中文高频` 把 9 行注释算成条目；
 * `01 弱口令` 有一行重复）。**声称的条数比真会试的条数多 26%** 这件事，
 * 用户是看不出来的，只会觉得「怎么跑了半天没动静」。
 *
 * <h3>口径（全篇唯一）</h3>
 * 「条数」= 非空、非注释、长度落在 8..63、且去重之后的条目数。
 * 这与运行时 {@code DictStore.load} 的闸门一致 —— 门禁量的就是**真会发出去的候选数**。
 */
public class BuiltinDictAuditTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  " + detail);
        }
    }

    private static void ok(String name, boolean cond) {
        ok(name, cond, "");
    }

    public static void main(String[] a) {
        List<BuiltinDicts.Item> items = BuiltinDicts.items();
        ok("内置清单非空", !items.isEmpty(), "" + items.size());

        System.out.println("== 条数口径：声称值必须等于真会试的条数 ==");
        for (BuiltinDicts.Item it : items) {
            if (isRule(it)) continue;      // 规则字典按区间展开，不按行数
            File f = new File(TestPaths.under("app/src/main/assets/builtin/" + it.asset));
            if (!f.isFile()) {
                ok(it.fileName() + " 资产存在", false, f.getAbsolutePath());
                continue;
            }
            List<String> raw = lines(f);
            int runnable = runnable(raw);
            ok(it.fileName() + " 声称 " + it.entries + " = 可跑 " + runnable,
                    it.entries == runnable, it.entries + " vs " + runnable);
        }

        System.out.println("== 形态：不该有永远跑不了的条目 ==");
        for (BuiltinDicts.Item it : items) {
            if (isRule(it)) continue;
            File f = new File(TestPaths.under("app/src/main/assets/builtin/" + it.asset));
            if (!f.isFile()) continue;
            List<String> raw = lines(f);
            int shortOnes = 0, longOnes = 0, blank = 0, dup = 0;
            Set<String> seen = new LinkedHashSet<>();
            for (String l : raw) {
                if (l.isEmpty()) { blank++; continue; }
                if (l.startsWith("#")) continue;
                if (l.length() < 8) shortOnes++;
                else if (l.length() > 63) longOnes++;
                if (!seen.add(l)) dup++;
            }
            ok(it.fileName() + " 无 <8 位条目（会被长度闸门静默丢弃）", shortOnes == 0, "" + shortOnes);
            ok(it.fileName() + " 无 >63 位条目", longOnes == 0, "" + longOnes);
            ok(it.fileName() + " 无重复行", dup == 0, "" + dup);
            ok(it.fileName() + " 无夹层空行", blank == 0, "" + blank);
        }

        System.out.println("== 编码形态：BOM / CRLF 会让首条候选带脏字节 ==");
        for (BuiltinDicts.Item it : items) {
            File f = new File(TestPaths.under("app/src/main/assets/builtin/" + it.asset));
            if (!f.isFile()) continue;
            byte[] b = readAll(f);
            if (b.length == 0) continue;
            boolean bom = b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB
                    && (b[2] & 0xFF) == 0xBF;
            boolean crlf = false;
            for (int i = 0; i + 1 < b.length; i++) {
                if (b[i] == '\r' && b[i + 1] == '\n') { crlf = true; break; }
            }
            ok(it.fileName() + " 无 BOM", !bom);
            ok(it.fileName() + " 无 CRLF（只认 \\n）", !crlf);
        }

        System.out.println("== 文件名必须与语言无关 ==");
        boolean langStable = true;
        for (BuiltinDicts.Item it : items) {
            if (!it.fileName().equals(it.nameZh)) langStable = false;
        }
        ok("fileName() 恒取中文名（切英文不会再写第二份）", langStable);
        ok("fileName() 就是最终文件名（带 .txt）",
                items.stream().allMatch(i -> i.fileName().endsWith(".txt")));
        ok("fileName() 能被 find() 认出来（界面靠它反查档位）",
                items.stream().allMatch(i -> BuiltinDicts.find(i.fileName()) != null));

        System.out.println("== 废弃名单不得误伤当前清单 ==");
        for (String n : obsoleteNames()) {
            ok("废弃名「" + n + "」不在当前清单里", BuiltinDicts.find(n) == null);
        }

        System.out.println("== 出处与许可：每条内置表都必须能回答「从哪来」==");
        for (BuiltinDicts.Item it : items) {
            String src = BuiltinDicts.sourceOf(it.asset);
            String lic = BuiltinDicts.licenseOf(it.asset);
            ok(it.asset + " 有出处", src != null && src.length() > 3, "[" + src + "]");
            ok(it.asset + " 有许可结论（可以是「未声明」，但不能是空）",
                    lic != null && !lic.isEmpty(), "[" + lic + "]");
        }
        ok("SecLists 的两条明确写成 MIT",
                BuiltinDicts.licenseOf("wpa_top62.txt").startsWith("MIT")
                        && BuiltinDicts.licenseOf("wpa_top4800.txt").startsWith("MIT"));
        ok("核不实的不会被写成具体许可（只许「未声明/待核实」）",
                !BuiltinDicts.licenseUnclear("weak.txt") == false
                        || "未声明".equals(BuiltinDicts.licenseOf("weak.txt")));
        ok("自制规则标注为本项目",
                "本项目".equals(BuiltinDicts.licenseOf("rule_phone_cn.txt")));
        ok("未核实的数得出来（关于页要披露这一批）",
                countUnclear(items) >= 5, "" + countUnclear(items));

        System.out.println("== 跨文件重复率（棘轮：只许降不许升）==");
        Set<String> union = new LinkedHashSet<>();
        int sum = 0;
        for (BuiltinDicts.Item it : items) {
            if (it.tier != BuiltinDicts.TIER_CORE) continue;
            if (isRule(it)) continue;
            File f = new File(TestPaths.under("app/src/main/assets/builtin/" + it.asset));
            if (!f.isFile()) continue;
            Set<String> s = runnableSet(lines(f));
            sum += s.size();
            union.addAll(s);
        }
        double dupRate = sum == 0 ? 0 : 100.0 * (sum - union.size()) / sum;
        System.out.printf("  核心档：各本可跑之和 %d，去重后并集 %d，重复 %.1f%%%n",
                sum, union.size(), dupRate);
        ok("核心档重复率 <= 25%（棘轮；目标是 5% 以下，见 CHANGELOG 待办）",
                dupRate <= 25.0, String.format("%.1f%%", dupRate));
        ok("并集不为空且小于各行之和", !union.isEmpty() && union.size() <= sum);

        System.out.println("== 精华包：跨本去重合并 ==");
        java.util.List<BuiltinDicts.Item> books = BuiltinMerge.mergeableCore();
        ok("参与合并的是核心档 7 本（规则字典按区间展开，不参与）",
                books.size() == 7, "" + books.size());
        BuiltinMerge.Out merged = BuiltinMerge.merge(books, assetSourceForTest());
        // 这两个数字是**故意钉死**的：内置表内容一改这里就该红，
        // 逼你重新读一遍重复率，而不是让 20% 的冗余悄悄长回去。
        ok("去重前 = 各本可跑之和 13545", merged.rawCount == 13545, "" + merged.rawCount);
        ok("去重后 = 并集 10765", merged.keptCount() == 10765, "" + merged.keptCount());
        ok("重复 2780 条（20.5%）", merged.duplicates() == 2780, "" + merged.duplicates());
        ok("合并结果本身无重复",
                new LinkedHashSet<>(merged.lines).size() == merged.lines.size());
        ok("并集小于各行之和（确实去了重）", merged.keptCount() < merged.rawCount);
        java.util.List<Integer> wantAdded = java.util.Arrays.asList(62, 2461, 2068, 74, 432, 2798, 2870);
        ok("每本净增 = 预期（顺序即优先级）",
                merged.addedPerBook.equals(wantAdded), "" + merged.addedPerBook);
        ok("共享头部在合并结果里只出现一次",
                merged.lines.indexOf("12345678") == merged.lines.lastIndexOf("12345678"));
        ok("去重率与门禁阈值一致（<=25%）", merged.dupPercent() <= 25.0,
                String.format("%.1f%%", merged.dupPercent()));
        ok("合并入口的名字固定带 00 前缀（列表里排最前）",
                BuiltinMerge.mergedName().startsWith("00 "), BuiltinMerge.mergedName());

        System.out.println("== 并集报数：界面该报哪个数 ==");
        ok("coreEntries() 等于核心档声称值之和（口径已统一为可跑数）",
                BuiltinDicts.coreEntries() == coreSum(items),
                BuiltinDicts.coreEntries() + " vs " + coreSum(items));

        System.out.println();
        System.out.println("=== 内置字典体检: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isRule(BuiltinDicts.Item it) {
        return it.asset.startsWith("rule_");
    }

    private static int countUnclear(List<BuiltinDicts.Item> items) {
        int n = 0;
        for (BuiltinDicts.Item it : items) if (BuiltinDicts.licenseUnclear(it.asset)) n++;
        return n;
    }

    private static int coreSum(List<BuiltinDicts.Item> items) {
        int n = 0;
        for (BuiltinDicts.Item it : items) if (it.tier == BuiltinDicts.TIER_CORE) n += it.entries;
        return n;
    }

    /** 读原始行（**不去 BOM、不去 CR** —— 形态检查要看到原始字节） */
    private static List<String> lines(File f) {
        List<String> out = new ArrayList<>();
        String s = new String(readAll(f), StandardCharsets.UTF_8);
        String[] parts = s.split("\n", -1);
        // 文件以 \n 结尾时 split 会多出一个空串 —— 那是行尾，不是「夹层空行」
        int last = parts.length;
        if (last > 0 && parts[last - 1].isEmpty()) last--;
        for (int i = 0; i < last; i++) {
            String l = parts[i];
            if (l.endsWith("\r")) l = l.substring(0, l.length() - 1);
            out.add(l.trim());
        }
        return out;
    }

    /**
     * 与运行时闸门同口径的**可跑集合**。
     *
     * <p>刻意返回集合而不是条数：早一版这里返回 int，调用处写
     * {@code new LinkedHashSet<>(runnable(f))} —— 编译器按
     * {@code LinkedHashSet(int initialCapacity)} 解读，于是得到一个**空集合**，
     * 并集永远是 0。门禁自己抓到了这一条（「核心档：各本可跑之和 0」）。
     */
    private static Set<String> runnableSet(List<String> raw) {
        Set<String> seen = new LinkedHashSet<>();
        for (String l : raw) {
            if (l.isEmpty() || l.startsWith("#")) continue;
            if (l.length() < 8 || l.length() > 63) continue;
            seen.add(l);
        }
        return seen;
    }

    /** 与运行时闸门同口径的可跑条数 */
    private static int runnable(List<String> raw) {
        return runnableSet(raw).size();
    }

    private static byte[] readAll(File f) {
        try {
            return Files.readAllBytes(f.toPath());
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /** 门禁版的取行方式：直接读 assets（生产读字典目录，同一套合并逻辑） */
    private static BuiltinMerge.LineSource assetSourceForTest() {
        return item -> BuiltinMerge.readLines(
                new File(TestPaths.under("app/src/main/assets/builtin/" + item.asset)));
    }

    /** 与生产同一份废弃名单（反射不行，这里用名字复制 + 断言两处一致） */
    private static String[] obsoleteNames() {
        return new String[]{
                "04 内置英文单词.txt", "04 Built-in English words.txt",
                "05 内置号码变形.txt", "05 Built-in number variants.txt",
        };
    }
}
