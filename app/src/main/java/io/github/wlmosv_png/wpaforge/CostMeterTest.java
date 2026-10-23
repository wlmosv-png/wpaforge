package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CostMeter;

import java.util.ArrayList;
import java.util.List;

/**
 * 每条候选耗时汇总器的测试。
 *
 * <p>这个汇总行是我做速度优化的**唯一实测依据**（后台跑时界面没开，
 * 逐条结果不会进任务日志）。所以它的行为必须被钉住：
 * 够 window 条才吐一行、吐完清空、四段均值算对、判定归类不丢。
 */
public class CostMeterTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static CostMeter.Sample s(long total, long prep, long probe, long poll, long log,
                                      int rounds, String verdict, String by) {
        return new CostMeter.Sample(total, verdict, by, prep, probe, poll, log, rounds);
    }

    public static void main(String[] args) {
        System.out.println("== 耗时汇总器 ==");

        // 1) 够 window 才吐
        CostMeter m = new CostMeter(20);
        for (int i = 0; i < 19; i++) m.record(5000, "AUTH_FAIL", "m1silent", 400, 200, 3000, 700, 12);
        check("19 条时不出汇总", m.takeSummary() == null);
        m.record(5000, "AUTH_FAIL", "m1silent", 400, 200, 3000, 700, 12);
        String line = m.takeSummary();
        check("第 20 条出汇总", line != null && line.contains("近 20 条"));
        check("吐完即清空", m.takeSummary() == null);

        // 2) 均值与分解
        List<CostMeter.Sample> one = new ArrayList<>();
        one.add(s(5000, 400, 200, 3000, 700, 12, "AUTH_FAIL", "m1silent"));
        one.add(s(3000, 200, 0, 2000, 300, 8, "SUCCESS", "wpalog"));
        String f = CostMeter.format(one);
        check("均耗时 = (5000+3000)/2 = 4000", f.contains("均 4000ms"));
        check("前置均值 = (400+200)/2 = 300", f.contains("前置 300"));
        check("netId 均值 = (200+0)/2 = 100", f.contains("netId 100"));
        check("轮询均值 = (3000+2000)/2 = 2500", f.contains("轮询 2500"));
        check("读日志均值 = (700+300)/2 = 500", f.contains("读日志 500"));
        check("轮均 = 5000/20 = 250ms", f.contains("轮 10 均 250ms"));
        check("判定归类两个都在", f.contains("m1silent 1") && f.contains("wpalog 1"));

        // 3) 无判据时退回 verdict 名；空样本不炸
        List<CostMeter.Sample> two = new ArrayList<>();
        two.add(s(9000, 0, 0, 0, 0, 0, "TIMEOUT", ""));
        String g = CostMeter.format(two);
        check("无判据用 verdict 名", g.contains("TIMEOUT 1"));
        check("轮数为 0 时不除零", g.contains("轮 0 均 0ms"));
        check("空列表返回空串", CostMeter.format(new ArrayList<>()).isEmpty());
        check("null 不炸", CostMeter.format(null).isEmpty());

        // 3b) 值为 0 的分段不打印（status/logcat 合并成一条命令后「读日志」恒为 0）
        List<CostMeter.Sample> z = new ArrayList<>();
        z.add(s(2000, 300, 100, 400, 0, 10, "AUTH_FAIL", "m1silent"));
        String h = CostMeter.format(z);
        check("恒为 0 的分段不打印", !h.contains("读日志"));
        check("非 0 分段照打", h.contains("前置 300") && h.contains("轮询 400"));
        List<CostMeter.Sample> allZero = new ArrayList<>();
        allZero.add(s(1500, 0, 0, 0, 0, 0, "TIMEOUT", ""));
        String az = CostMeter.format(allZero);
        check("全 0 时不出现空括号", !az.contains("（）") && az.contains("均 1500ms"));

        // 4) window=1 也要能用（调试时逐条）
        CostMeter one1 = new CostMeter(1);
        one1.record(1234, "AUTH_FAIL", "authcast", 100, 0, 800, 200, 5);
        check("window=1 立刻出汇总", one1.takeSummary() != null);
        check("window 下限保护（0 → 1）", new CostMeter(0).window() == 1);

        System.out.println();
        System.out.println("耗时汇总: pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
