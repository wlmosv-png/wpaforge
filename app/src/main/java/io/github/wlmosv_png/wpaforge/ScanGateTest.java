package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.scan.ScanDiff;
import io.github.wlmosv_png.wpaforge.scan.ScanGate;

import java.util.Arrays;
import java.util.List;

/**
 * 扫描节流闸门与两次扫描差异自检。
 *
 * <p>盯两件事：
 * <ol>
 *   <li><b>按钮不撒谎</b>：能不能扫、还要等几秒，界面必须能拿到准确答案；
 *       Android 对 {@code startScan()} 有约 30 秒的限制，超频请求会被直接丢掉，
 *       旧代码只写日志 —— 用户点了没反应会以为软件坏了；</li>
 *   <li><b>刷新有信息量</b>：新增/消失的台数按 BSSID 比对，
 *       大小写、顺序、空白都不能影响结果。</li>
 * </ol>
 */
public class ScanGateTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    public static void main(String[] a) {
        System.out.println("== 闸门：能不能扫 ==");
        ScanGate g = new ScanGate();
        long t0 = 1_000_000L;
        ok("从没扫过 → 允许", g.allowed(t0), "");
        ok("从没扫过 → 剩余 0", g.remainingMs(t0) == 0, "" + g.remainingMs(t0));
        ok("从没扫过 → 文案是「可以扫描」", g.label(t0).contains("可以"), g.label(t0));

        g.markRequest(t0, true);
        ok("刚扫过 → 不允许", !g.allowed(t0), "");
        ok("刚扫过 → 剩余≈30 秒",
                Math.abs(g.remainingMs(t0) - ScanGate.MIN_INTERVAL_MS) < 1, "" + g.remainingMs(t0));
        ok("冷却文案带秒数", g.label(t0).contains("30"), g.label(t0));
        ok("过了 10 秒 → 还剩约 20 秒",
                Math.abs(g.remainingMs(t0 + 10_000) - 20_000) < 1, "" + g.remainingMs(t0 + 10_000));
        ok("秒数向上取整（剩 28.2 秒报 29 秒）",
                g.label(t0 + 1_800).contains("29"), g.label(t0 + 1_800));
        ok("满 30 秒 → 允许", g.allowed(t0 + ScanGate.MIN_INTERVAL_MS), "");
        ok("剩余不会是负数", g.remainingMs(t0 + 999_999) == 0, "");

        System.out.println("== 闸门：被系统拒了怎么说 ==");
        ScanGate r = new ScanGate();
        r.markRequest(t0, false);
        ok("被拒 → 文案说明是系统限制", r.label(t0).contains("系统限制"), r.label(t0));
        ok("被拒 → 明确告知重试秒数", r.label(t0).contains("30"), r.label(t0));
        ok("被拒后 20 秒仍不允许（要多等一会儿）",
                !r.allowed(t0 + 20_000), "" + r.remainingMs(t0 + 20_000));
        ok("被拒后满冷却 → 允许", r.allowed(t0 + ScanGate.REJECT_COOLDOWN_MS), "");
        ok("lastAccepted 反映被拒", !r.lastAccepted(), "");
        ok("everRequested 反映请求过", r.everRequested(), "");
        r.reset();
        ok("reset 后回到初始态", r.allowed(t0) && r.lastAccepted() && !r.everRequested(), "");

        System.out.println("== 差异：新增 / 消失 ==");
        List<String> prev = Arrays.asList("AA", "BB", "CC");
        List<String> cur = Arrays.asList("BB", "CC", "DD", "EE");
        ScanDiff.Result d = ScanDiff.of(prev, cur);
        ok("新增 2", d.added == 2, "" + d.added);
        ok("消失 1", d.removed == 1, "" + d.removed);
        ok("保留 2", d.kept == 2, "" + d.kept);
        ok("有变化 → changed()", d.changed(), "");
        ok("文案含新增与消失", d.label(" · ").contains("新增 2") && d.label(" · ").contains("消失 1"),
                d.label(" · "));

        ScanDiff.Result same = ScanDiff.of(prev, Arrays.asList("CC", "BB", "AA"));
        ok("顺序不同不算变化", !same.changed() && same.added == 0 && same.removed == 0, "");
        ok("没变化时文案为空", same.label(" · ").isEmpty(), same.label(" · "));

        ScanDiff.Result ci = ScanDiff.of(Arrays.asList("aa:bb"), Arrays.asList("AA:BB "));
        ok("大小写与空白不敏感", !ci.changed() && ci.kept == 1, ci.kept + "");

        ScanDiff.Result first = ScanDiff.of(null, cur);
        ok("第一次扫描：不算变化", first.firstEver && !first.changed(), "");
        ok("第一次扫描：文案为空（没什么可比的）", first.label(" · ").isEmpty(), "");
        ok("第一次扫描：保留数＝当前台数", first.kept == cur.size(), "" + first.kept);

        ScanDiff.Result empty = ScanDiff.of(prev, java.util.Collections.emptyList());
        ok("全消失", empty.removed == 3 && empty.added == 0, "");
        ok("空/null 不炸",
                ScanDiff.of(null, null).kept == 0
                        && ScanDiff.of(java.util.Collections.emptyList(), null).removed == 0, "");

        System.out.println();
        System.out.println("=== 扫描闸门/差异测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
