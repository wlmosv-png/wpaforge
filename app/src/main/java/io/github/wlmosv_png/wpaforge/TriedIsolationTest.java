package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.TriedSet;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * P0-1：{@code TriedSet} 跨任务继承的隔离验证。
 *
 * <h3>要证明的语义</h3>
 * <ol>
 *   <li><b>按目标隔离</b>：不同 BSSID 的记录互不可见（现有设计已满足）。</li>
 *   <li><b>同 BSSID 跨任务会继承</b>：这是现有设计，用于「换字典不重复劳动」。</li>
 *   <li><b>继承只影响「跳过」，不影响判定</b>：被跳过的候选不会被执行，
 *       但**执行过的候选的结论**由引擎决定，本类不参与。</li>
 *   <li><b>损坏/缺失 → 安全降级为空集</b>，不会误判。</li>
 * </ol>
 *
 * <p>本测试只用文件级 API（{@code load/save/contains/add/targetKey}），
 * 全部为生产方法。
 */
public final class TriedIsolationTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        boolean c = want == null ? got == null : want.equals(got);
        if (!c) System.out.println("        期望=" + want + " 实际=" + got);
        ok(name, c);
    }

    private static File tmpDir() throws Exception {
        File d = File.createTempFile("tried", "");
        d.delete();
        d.mkdirs();
        return d;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== TriedIsolationTest（已试记录的目标隔离）===");

        File dir = tmpDir();

        // ============================================================ T1 基本存取
        System.out.println();
        System.out.println("== T1 基本存取 ==");
        File fA = new File(dir, "A.tried");
        TriedSet a = new TriedSet();
        ok("T1-a add 新口令返回 true", a.add("12345678"));
        ok("T1-b 重复 add 返回 false", !a.add("12345678"));
        ok("T1-c contains 命中", a.contains("12345678"));
        ok("T1-d save 成功", a.save(fA));
        TriedSet a2 = TriedSet.load(fA);
        eq("T1-e 重新加载条数一致", a.size(), a2.size());
        ok("T1-f 重新加载仍命中", a2.contains("12345678"));

        // ============================================================ T2 跨目标隔离
        System.out.println();
        System.out.println("== T2 不同目标（不同 BSSID）必须隔离 ==");
        String k1 = TriedSet.targetKey("28:93:7d:11:22:01", "B212");
        String k2 = TriedSet.targetKey("28:93:7d:11:22:99", "B212");
        ok("T2-a 不同 BSSID → 不同 key", !k1.equals(k2));
        eq("T2-b key 带 b 前缀（BSSID 优先）", true, k1.startsWith("b"));
        File f1 = new File(dir, k1 + ".tried");
        File f2 = new File(dir, k2 + ".tried");
        TriedSet s1 = new TriedSet(); s1.add("pw-target-1"); s1.save(f1);
        TriedSet s2 = new TriedSet(); s2.add("pw-target-2"); s2.save(f2);
        TriedSet l1 = TriedSet.load(f1);
        TriedSet l2 = TriedSet.load(f2);
        ok("T2-c 目标1 看不到目标2 的记录", !l1.contains("pw-target-2"));
        ok("T2-d 目标2 看不到目标1 的记录", !l2.contains("pw-target-1"));

        // ============================================================ T3 同 BSSID 跨任务继承
        System.out.println();
        System.out.println("== T3 同 BSSID：新任务会继承（现有设计，非缺陷）==");
        File f3 = new File(dir, "inherit.tried");
        TriedSet task1 = new TriedSet();
        task1.add("already-tried-1");
        task1.add("already-tried-2");
        task1.save(f3);
        TriedSet task2 = TriedSet.load(f3);   // 模拟「第二个新任务加载同一目标」
        ok("T3-a 第二个任务能看到第一个任务记录", task2.contains("already-tried-1"));
        eq("T3-b 条数继承", 2, task2.size());
        System.out.println("  ▲    这是**有意设计**（换字典不重复劳动），");
        System.out.println("  ▲    但缺少「重新开始」开关 —— 已在报告中列为待决策项。");

        // ============================================================ T4 损坏降级
        System.out.println();
        System.out.println("== T4 损坏 / 截断 / 缺失 → 安全降级 ==");
        File bad = new File(dir, "bad.tried");
        java.nio.file.Files.write(bad.toPath(), new byte[]{1, 2, 3});   // 非 8 倍数
        TriedSet lb = TriedSet.load(bad);
        eq("T4-a 截断文件 → 空集合（不是抛异常）", 0, lb.size());
        File missing = new File(dir, "nope.tried");
        eq("T4-b 不存在的文件 → 空集合", 0, TriedSet.load(missing).size());
        TriedSet lnull = TriedSet.load(null);
        eq("T4-c null 文件 → 空集合", 0, lnull.size());

        // ============================================================ T5 过滤计数
        System.out.println();
        System.out.println("== T5 filterCount / countIn 与 contains 一致 ==");
        TriedSet ts = new TriedSet();
        ts.add("11111111");
        ts.add("22222222");
        List<String> pool = new ArrayList<>();
        pool.add("11111111");
        pool.add("22222222");
        pool.add("33333333");
        eq("T5-a filterCount = 2", 2, TriedSet.filterCount(pool, ts));
        eq("T5-b 未过滤时 = 0", 0, TriedSet.filterCount(pool, new TriedSet()));

        // ============================================================ T6 上限与溢出标记
        System.out.println();
        System.out.println("== T6 上限常量与溢出标记 ==");
        ok("T6-a MAX 为正数", TriedSet.MAX > 0);
        ok("T6-b 新集合未溢出", !new TriedSet().isOverflow());

        // 清理
        try {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        } catch (Throwable ignored) {}

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) throw new RuntimeException("TriedIsolationTest 失败 " + fail + " 项");
    }
}
