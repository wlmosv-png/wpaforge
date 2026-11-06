package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.RunLog;

/**
 * 运行记录模型：频道隔离、环形上限、时间戳格式、清空语义。
 */
public class RunLogTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private static void eq(String name, Object got, Object want) {
        boolean same = want == null ? got == null : want.equals(got);
        if (same) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "   got=" + got + "  want=" + want);
        }
    }

    public static void main(String[] args) {
        System.out.println("== 两个频道互不干扰 ==");
        RunLog log = new RunLog();
        log.offline.info(1000, "离线 A");
        log.strength.info(1000, "强度 B");
        log.offline.fail(2000, "离线 C");
        eq("离线条数", log.offline.size(), 2);
        eq("强度条数", log.strength.size(), 1);
        eq("离线第一条", log.offline.snapshot().get(0).text, "离线 A");
        eq("强度第一条", log.strength.snapshot().get(0).text, "强度 B");
        ok("互不串台",
                log.offline.snapshot().get(1).text.equals("离线 C")
                        && log.strength.size() == 1);

        System.out.println("== 级别 ==");
        RunLog lg = new RunLog();
        lg.offline.info(0, "i");
        lg.offline.progress(0, "p");
        lg.offline.ok(0, "o");
        lg.offline.warn(0, "w");
        lg.offline.fail(0, "f");
        lg.offline.cancel(0, "c");
        eq("info", lg.offline.snapshot().get(0).kind, RunLog.Kind.INFO);
        eq("progress", lg.offline.snapshot().get(1).kind, RunLog.Kind.PROGRESS);
        eq("ok", lg.offline.snapshot().get(2).kind, RunLog.Kind.OK);
        eq("warn", lg.offline.snapshot().get(3).kind, RunLog.Kind.WARN);
        eq("fail", lg.offline.snapshot().get(4).kind, RunLog.Kind.FAIL);
        eq("cancel", lg.offline.snapshot().get(5).kind, RunLog.Kind.CANCEL);

        System.out.println("== 时间戳 ==");
        RunLog.Channel ch = new RunLog.Channel();
        ch.info(0, "epoch");
        ok("epoch 显示 00:00:00", ch.snapshot().get(0).clock().equals("00:00:00"));
        ch.info(3661000L, "01:01:01");     // 1h1m1s
        eq("1h1m1s", ch.snapshot().get(1).clock(), "01:01:01");
        ch.info(1793900000000L, "real");
        ok("真实时间戳格式为 HH:mm:ss",
                ch.snapshot().get(2).clock().matches("\\d{2}:\\d{2}:\\d{2}"));
        ok("负时间戳不炸", new RunLog.Line(-5, RunLog.Kind.INFO, "x").clock().equals("00:00:00"));

        System.out.println("== 环形上限 ==");
        RunLog.Channel small = new RunLog.Channel(8);
        for (int i = 0; i < 30; i++) small.info(i, "n" + i);
        eq("不超过上限", small.size(), 8);
        eq("保留最新一条", small.snapshot().get(7).text, "n29");
        eq("最旧被挤掉", small.snapshot().get(0).text, "n22");

        System.out.println("== 清空 ==");
        RunLog cl = new RunLog();
        cl.offline.info(1, "a");
        cl.strength.info(1, "b");
        cl.clearAll();
        eq("离线已空", cl.offline.size(), 0);
        eq("强度已空", cl.strength.size(), 0);
        cl.offline.info(2, "c");
        eq("清空后还能继续写", cl.offline.size(), 1);

        RunLog chOnly = new RunLog();
        chOnly.offline.info(1, "keep");
        chOnly.strength.info(1, "x");
        chOnly.offline.clear();
        eq("只清一个频道不影响另一个", chOnly.strength.size(), 1);

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}
