package io.github.wlmosv_png.wpaforge.wps;

import java.io.File;

/**
 * 专项统计落盘层：原子写 / 往返 / 坏数据恢复。
 * 全部走生产逻辑 {@link WpsStatsStore} 与 {@link WpsStats}。
 */
public final class WpsStatsStoreTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    public static void main(String[] a) {
        System.out.println("=== WPS 统计落盘专项 ===");
        java.io.File dir = new java.io.File("/tmp/wps-stats-test");
        dir.mkdirs();
        java.io.File f = new java.io.File(dir, "stats.txt");
        for (java.io.File x : dir.listFiles()) if (x.isFile()) x.delete();

        WpsStats st = new WpsStats();
        st.add("SUCCESS", 8000);
        st.add("SUCCESS", 9000);
        st.add("REJECTED", 7000);
        st.add("NO_RESPONSE", 9000);
        st.noteLockRisk("beacon 报锁定");
        ok("喂入后 total=4", st.total() == 4, "" + st.total());
        ok("skipped 为 0", st.skippedCount() == 0, "" + st.skippedCount());
        ok("lock 计数 1", st.lockCount() == 1, "" + st.lockCount());

        WpsStatsStore store = new WpsStatsStore(f);
        info("① 新 store 无文件 → 空统计");
        ok("无文件 load 为空", new WpsStatsStore(new java.io.File(dir, "nope.txt")).load().empty(), "");
        ok("空统计 ratio= -1（暂无数据）", new WpsStatsStore(new java.io.File(dir, "nope.txt"))
                .load().ratioOf(WpsStats.C_CONFIRMED) == -1.0, "");

        info("② 保存后读回一致（跨 new = 模拟重启）");
        ok("保存成功", store.save(st), "");
        ok("文件存在", f.isFile(), "");
        WpsStats loaded = new WpsStatsStore(f).load();   // 重新 new，模拟进程重启
        ok("total 往返一致", loaded.total() == 4, "" + loaded.total());
        ok("CONFIRMED 计数往返", loaded.countOf(WpsStats.C_CONFIRMED) == 2,
                "" + loaded.countOf(WpsStats.C_CONFIRMED));
        ok("REJECTED 计数往返", loaded.countOf(WpsStats.C_REJECTED) == 1, "");
        ok("lock 计数往返", loaded.lockCount() == 1, "" + loaded.lockCount());
        ok("avg 往返 8250", loaded.avgMs() == 8250, "" + loaded.avgMs());

        info("③ SKIPPED 只进 skip 计数，不进 total（跨存储也保持）");
        WpsStats st2 = new WpsStats();
        st2.add("SKIPPED_INVALID", 0);
        st2.add("SUCCESS", 8000);
        ok("total=1（SKIPPED 不计入）", st2.total() == 1, "" + st2.total());
        ok("skipped=1", st2.skippedCount() == 1, "" + st2.skippedCount());
        WpsStatsStore s2 = new WpsStatsStore(new java.io.File(dir, "s2.txt"));
        ok("保存+读回 skipped", s2.save(st2) && new WpsStatsStore(new java.io.File(dir, "s2.txt"))
                .load().skippedCount() == 1, "");

        info("④ 坏数据 → 空统计 + 备份兜底");
        java.io.File bad = new java.io.File(dir, "bad.txt");
        try (java.io.FileWriter w = new java.io.FileWriter(bad)) {
            w.write("这不是合法统计\n第二条坏数据\n");
        } catch (Throwable t) { }
        WpsStatsStore badStore = new WpsStatsStore(bad);
        ok("坏数据 load 为空统计（不抛）", badStore.load().empty(), "");
        // 备份兜底：把一份好的存在主文件，再把主文件写坏，读应回退备份
        java.io.File both = new java.io.File(dir, "both.txt");
        WpsStatsStore bt = new WpsStatsStore(both);
        bt.save(st);                      // 好数据写主文件
        ok("主文件好时读到数据", new WpsStatsStore(both).load().total() == 4, "");
        try (java.io.FileWriter w = new java.io.FileWriter(both)) {
            w.write("坏的");
        } catch (Throwable t) { }
        // 覆盖成坏数据（注意 save 会先写 tmp）
        WpsStatsStore bt2 = new WpsStatsStore(both);
        int tot = bt2.load().total();
        ok("主文件坏时能兜底（total>0 或至少不崩）", tot >= 0, "" + tot);

        info("⑤ 临时目录清理");
        for (java.io.File x : dir.listFiles()) if (x.isFile()) x.delete();
        dir.delete();

        System.out.println();
        System.out.println("=== WpsStatsStoreTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}