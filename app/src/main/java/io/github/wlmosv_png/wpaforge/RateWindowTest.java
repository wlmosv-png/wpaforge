package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.RateWindow;

/**
 * 速率滑窗回归（用户反馈：界面显示"每条约 5.2 秒"，实际只有 2.2 秒）。
 *
 * <p>钉住三件事：
 * <ol>
 *   <li>窗口没满时就用已有样本（不要等到攒满才给数）；</li>
 *   <li>满了之后淘汰**最旧**的样本 —— 开头那批慢条不该一直拖着界面；</li>
 *   <li>中断/回退那种非正耗时不算样本，ETA 在没样本时明确返回 -1。</li>
 * </ol>
 */
public class RateWindowTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, long want, long got) {
        if (want != got) System.out.println("        want=" + want + " got=" + got);
        ok(name, want == got);
    }

    public static void main(String[] args) {
        System.out.println("== W1 空窗 ==");
        RateWindow w = new RateWindow(5);
        eq("没有样本 → 均值 0", 0L, w.avgMs());
        eq("没有样本 → ETA -1", -1L, w.etaMs(100));
        eq("没有样本 → 条/分 0", 0L, (long) w.perMinute());
        ok("窗口大小按传入值", w.size() == 5 && w.count() == 0);

        System.out.println("== W2 未满窗：用已有样本 ==");
        w.add(2000);
        eq("1 条 → 2000", 2000L, w.avgMs());
        w.add(4000);
        eq("2 条 → 3000", 3000L, w.avgMs());
        eq("2 条 · 剩 10 条 → 30 秒", 30000L, w.etaMs(10));
        ok("未满时不报 full", !w.full());

        System.out.println("== W3 满窗后淘汰最旧（开头的慢批次不再拖累）==");
        RateWindow w2 = new RateWindow(20);
        for (int i = 0; i < 20; i++) w2.add(5000);   // 前 20 条都是慢的 5s
        eq("满窗均 5000", 5000L, w2.avgMs());
        ok("full", w2.full());
        for (int i = 0; i < 20; i++) w2.add(2200);   // 后面 20 条都是快的 2.2s
        eq("全部换成快的之后 → 2200", 2200L, w2.avgMs());
        eq("样本数封顶在窗口大小", 20, w2.count());
        eq("剩 100 条 → 220 秒（按当前节奏，而不是按开头的 5s）",
                220000L, w2.etaMs(100));

        System.out.println("== W4 混合窗口取最近 N 条 ==");
        RateWindow w3 = new RateWindow(4);
        w3.add(5000);
        w3.add(1000);
        w3.add(1000);
        w3.add(1000);
        eq("(5000+1000+1000+1000)/4 = 2000", 2000L, w3.avgMs());
        w3.add(1000);                                 // 淘汰最旧的 5000
        eq("淘汰后 (1000*4)/4 = 1000", 1000L, w3.avgMs());

        System.out.println("== W5 非正耗时忽略 / reset ==");
        RateWindow w4 = new RateWindow(3);
        w4.add(1500);
        w4.add(0);
        w4.add(-20);
        eq("0 与负数不计入样本", 1, w4.count());
        eq("均值仍是 1500", 1500L, w4.avgMs());
        eq("条/分 = 60000/1500 = 40", 40L, (long) w4.perMinute());
        w4.reset();
        eq("reset 后无样本", 0L, w4.avgMs());
        eq("reset 后 ETA -1", -1L, w4.etaMs(5));

        System.out.println("== W6 剩余 0 条不报 ETA ==");
        RateWindow w5 = new RateWindow(3);
        w5.add(1000);
        eq("剩 0 条 → -1", -1L, w5.etaMs(0));
        eq("剩 -3 条 → -1", -1L, w5.etaMs(-3));

        System.out.println();
        System.out.println("=== 速率滑窗测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
