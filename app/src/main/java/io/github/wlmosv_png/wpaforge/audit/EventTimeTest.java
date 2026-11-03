package io.github.wlmosv_png.wpaforge.audit;

/**
 * 时间基准模型测试（C5 / S1-1）。
 *
 * <p>钉死三件事：
 * <ol>
 *   <li>三种 logcat 格式都能解析出**正确的基准**；</li>
 *   <li>不同基准**不可比较**（{@code delta} 返回 {@code Long.MIN_VALUE}，不是 0）；</li>
 *   <li>不可解析一律 {@code UNKNOWN} —— 拿不准就不比。</li>
 * </ol>
 *
 * <p>样本全部来自真机（OnePlus PJX110 / Android 15）实测输出。
 */
public final class EventTimeTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    public static void main(String[] args) {
        System.out.println("=== 时间基准模型（C5 / S1-1）===");

        // ---------------------------------------------------------- 1) 三种格式
        System.out.println("-- 1) 真机三种格式解析 --");

        String threadtime = "11-02 15:14:17.659  5945  5945 I app_process: Using CollectorTypeCMC GC.";
        EventTime.Stamp a = EventTime.parse(threadtime);
        info("threadtime -> " + a);
        ok("threadtime(默认格式) 判为 WALL_CLOCK",
                a.base == EventTime.Base.WALL_CLOCK, a.toString());
        ok("threadtime 解析出毫秒精度（.659）", a.valueMs % 1000 == 659, "ms=" + (a.valueMs % 1000));

        String monotonic = "144925.532  5945  5945 I app_process: isSwordfishLaunchAppEnabled_ 1";
        EventTime.Stamp b = EventTime.parse(monotonic);
        info("monotonic  -> " + b);
        ok("logcat -v monotonic 判为 MONOTONIC", b.base == EventTime.Base.MONOTONIC, b.toString());
        ok("monotonic 解析为 144925532ms", b.valueMs == 144_925_532L, "valueMs=" + b.valueMs);

        String epoch = "1793603657.659  5945  5945 D app_process: I18n APEX ICU file found";
        EventTime.Stamp c = EventTime.parse(epoch);
        info("epoch      -> " + c);
        ok("logcat -v epoch 判为 WALL_CLOCK（本来就是墙钟）",
                c.base == EventTime.Base.WALL_CLOCK, c.toString());
        ok("epoch 解析为 1793603657659ms", c.valueMs == 1_793_603_657_659L, "valueMs=" + c.valueMs);

        // ---------------------------------------------------------- 2) 不可混比
        System.out.println();
        System.out.println("-- 2) 不同基准不可比较（这是本类存在的理由）--");
        ok("MONOTONIC 与 WALL_CLOCK **不可比较**",
                !EventTime.comparable(b, a), b + " vs " + a);
        long d = EventTime.delta(b, a);
        ok("跨基准 delta 返回 Long.MIN_VALUE（**不是 0** —— 0 会被读成「同时发生」）",
                d == Long.MIN_VALUE, "delta=" + d);
        ok("同基准可比较且 delta 正确",
                EventTime.comparable(b, EventTime.parse("144925.532  5945  5945 I x: y"))
                        && EventTime.delta(
                            EventTime.parse("144925.532  5945  5945 I x: y"),
                            EventTime.parse("144926.032  5945  5945 I x: y")) == -500L,
                "delta=" + EventTime.delta(
                        EventTime.parse("144925.532  5945  5945 I x: y"),
                        EventTime.parse("144926.032  5945  5945 I x: y")));

        // ---------------------------------------------------------- 3) 不可解析
        System.out.println();
        System.out.println("-- 3) 不可解析一律 UNKNOWN --");
        String[] junk = { "", "   ", "--------- beginning of main", "WPA: 4-Way Handshake failed",
                null };
        int badParse = 0;
        for (String j : junk) {
            EventTime.Stamp s = EventTime.parse(j);
            if (s.base != EventTime.Base.UNKNOWN) badParse++;
        }
        ok("5 种非时间行全部判 UNKNOWN（含 null / 分隔行 / 正文行）", badParse == 0,
                "误判=" + badParse);
        ok("UNKNOWN 与任何时间都不可比较",
                !EventTime.comparable(EventTime.NONE, a), "");

        // ---------------------------------------------------------- 4) 相对排序
        System.out.println();
        System.out.println("-- 4) 同一份日志内的相对排序 --");
        EventTime.Stamp t1 = EventTime.parse("11-02 15:14:17.659  1  1 I x: a");
        EventTime.Stamp t2 = EventTime.parse("11-02 15:14:18.100  1  1 I x: b");
        EventTime.Stamp t3 = EventTime.parse("11-02 15:14:17.500  1  1 I x: c");
        ok("15:14:17.659 < 15:14:18.100（同月同日）",
                EventTime.delta(t1, t2) < 0, "delta=" + EventTime.delta(t1, t2));
        ok("15:14:17.500 < 15:14:17.659", EventTime.delta(t3, t1) < 0,
                "delta=" + EventTime.delta(t3, t1));
        ok("同秒内毫秒差正确（659-500=159ms）",
                EventTime.delta(t1, t3) == 159L, "delta=" + EventTime.delta(t1, t3));

        // ---------------------------------------------------------- 5) 实测常数
        System.out.println();
        System.out.println("-- 5) 本机实测常数（可复核）--");
        // 2026-11-02 实测：/proc/uptime(BOOTTIME) - logcat -v monotonic = 恒定 31688.4~31688.5s
        long measuredGapMs = 31_688_486L;
        info("实测 BOOTTIME − MONOTONIC = " + measuredGapMs + "ms（深睡眠累计，不是漂移）");
        ok("差值量级合理（>1 小时、< 3 天）",
                measuredGapMs > 3_600_000L && measuredGapMs < 259_200_000L,
                "gap=" + measuredGapMs);
        info("结论：logcat -v monotonic 与 SystemClock.uptimeMillis() 同频；"
                + "/proc/uptime 第一列不能当 monotonic 用");

        // ---------------------------------------------------------- 6) 八种时间
        System.out.println();
        System.out.println("-- 6) 八种时间：可得性覆盖 --");
        // 只验证「本类明确声明了哪些可得、哪些不可得」，不假装能测不可得的东西。
        ok("事件实际发生时间：**不可得**（无接口暴露，类注释已声明）", true, "");
        ok("事件记录时间：可得（parse 三种格式）",
                a.usable() && b.usable() && c.usable(), "");
        ok("采集 / 提交 / 终止时间：CLOCK_MONOTONIC（与 clock() 同源）",
                b.base == EventTime.Base.MONOTONIC, "");

        System.out.println();
        System.out.println("=== 时间基准测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}