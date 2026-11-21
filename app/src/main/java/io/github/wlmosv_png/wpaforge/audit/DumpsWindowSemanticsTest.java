package io.github.wlmosv_png.wpaforge.audit;

/**
 * 采集窗口语义测试（1.9.66 专项 B）。纯逻辑。
 *
 * <p>钉死 `inAttemptWindow` 的核心修正：`mConnectionEvents` 的 `startTime` 是
 * **连接开始**时刻，而记录要等 `startTime + durationMillis` 才写进 dump。
 * 只按 startTime 卡窗口终点，会把「本次开始、但 3.8~4.9s 后才落盘」的事件误判为
 * 窗口外 —— 那正是错误密码被漏判的真实原因。
 *
 * <p>同时守住安全方向：开始时刻晚于窗口终点的**下一次**尝试绝不能被算进来。
 */
public final class DumpsWindowSemanticsTest {

    private static int pass = 0, fail = 0;
    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; System.out.println("  OK   " + n); }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }
    private static void ok(String n, boolean c) { ok(n, c, ""); }

    /** 造一条连接事件：开始于 base，持续 dur，失败原因 WRONG_PSWD，uid=0。 */
    private static WifiEvidenceProbe.ConnEvent ev(long startMs, long dur, String ssid) {
        return new WifiEvidenceProbe.ConnEvent(
                "11-22 0:44:22.225", startMs, ssid, "6a:93:7d:41:cc:3b", dur,
                0, "AUTHENTICATION_FAILURE", "AUTH_FAILURE_WRONG_PSWD", 0);
    }

    public static void main(String[] args) {
        System.out.println("== 采集窗口语义 (1.9.66) ==");
        try {
            long wFrom = WifiEvidenceProbe.parseStamp("11-22 0:44:20.000");
            long wTo   = WifiEvidenceProbe.parseStamp("11-22 0:44:24.000");
            ok("测试窗口可信", WifiEvidenceProbe.windowUsable(wFrom, wTo));

            // 1. 常规：开始与结束都在窗口内
            long s1 = WifiEvidenceProbe.parseStamp("11-22 0:44:21.000");
            ok("★常规事件（窗口内开始+结束）→ 认",
                    WifiEvidenceProbe.inAttemptWindow(ev(s1, 1000, "2.4G-3"), wFrom, wTo));

            // 2. 关键修正：窗口内开始、落盘晚于窗口终点（延迟 4.9s）
            long s2 = WifiEvidenceProbe.parseStamp("11-22 0:44:21.000");
            long late = 4900L;   // start + 4.9s = 0:44:25.9，晚于 wTo
            ok("★窗口内开始但 4.9s 后才落盘 → 仍认（1.9.66 修正）",
                    WifiEvidenceProbe.inAttemptWindow(ev(s2, late, "2.4G-3"), wFrom, wTo));

            // 3. 开始早于窗口起点、但生命期与窗口交叠 → 认（可能是上一条的尾巴，
            //    归属仍由 uid=0 + 配置关联 + SSID/BSSID 决定，不由窗口单独决定）
            long s3 = WifiEvidenceProbe.parseStamp("11-22 0:44:19.000");
            ok("开始早于窗口但结束在窗口内 → 认（生命期交叠）",
                    WifiEvidenceProbe.inAttemptWindow(ev(s3, 3000, "2.4G-3"), wFrom, wTo));

            // 4. 开始与结束都早于窗口 → 不认（上一条候选的旧事件）
            long s4 = WifiEvidenceProbe.parseStamp("11-22 0:44:10.000");
            ok("★窗口之前的旧事件 → 不认",
                    !WifiEvidenceProbe.inAttemptWindow(ev(s4, 2000, "2.4G-3"), wFrom, wTo));

            // 5. 开始晚于窗口终点 → 不认（下一次尝试，防串线）
            long s5 = WifiEvidenceProbe.parseStamp("11-22 0:44:30.000");
            ok("★窗口之后的下一事件 → 不认（防串线）",
                    !WifiEvidenceProbe.inAttemptWindow(ev(s5, 1000, "2.4G-3"), wFrom, wTo));

            // 6. duration 缺失时只看 startTime（不放宽）
            ok("缺 duration + 开始晚于窗口终点 → 不认",
                    !WifiEvidenceProbe.inAttemptWindow(ev(s5, -1, "2.4G-3"), wFrom, wTo));
            ok("缺 duration + 开始早于窗口起点 → 不认",
                    !WifiEvidenceProbe.inAttemptWindow(ev(s4, -1, "2.4G-3"), wFrom, wTo));
            ok("缺 duration + 开始落在窗口内 → 认",
                    WifiEvidenceProbe.inAttemptWindow(ev(s1, -1, "2.4G-3"), wFrom, wTo));

            // 7. 时间解析失败（-1）→ 一律不认（宁可漏判）
            ok("★时间解析失败 → 不认",
                    !WifiEvidenceProbe.inAttemptWindow(ev(-1L, 4000, "2.4G-3"), wFrom, wTo));

            // 8. 端到端：延迟落盘事件能被 findWrongPsk 命中
            long s8 = WifiEvidenceProbe.parseStamp("11-22 0:44:21.000");
            java.util.List<WifiEvidenceProbe.ConnEvent> list =
                    java.util.Collections.singletonList(ev(s8, 4900L, "2.4G-3"));
            ok("★延迟 4.9s 落盘的 WRONG_PSWD 被 findWrongPsk 命中",
                    WifiEvidenceProbe.findWrongPsk(list, "2.4G-3",
                            "6a:93:7d:41:cc:3b", wFrom, wTo, true) != null);

            // 9. 同 SSID 不同 BSSID 不认（端到端）
            ok("★同 SSID 不同 BSSID → findWrongPsk 不命中",
                    WifiEvidenceProbe.findWrongPsk(list, "2.4G-3",
                            "aa:bb:cc:dd:ee:ff", wFrom, wTo, true) == null);

            // 10. uid 非 0 不认（端到端）
            WifiEvidenceProbe.ConnEvent sys = new WifiEvidenceProbe.ConnEvent(
                    "11-22 0:44:22.225", s8, "2.4G-3", "6a:93:7d:41:cc:3b", 4900L,
                    0, "AUTHENTICATION_FAILURE", "AUTH_FAILURE_WRONG_PSWD", 1000);
            ok("★uid=1000（系统）→ findWrongPsk 不命中",
                    WifiEvidenceProbe.findWrongPsk(
                            java.util.Collections.singletonList(sys), "2.4G-3",
                            "6a:93:7d:41:cc:3b", wFrom, wTo, true) == null);

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println("=== 采集窗口语义: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
