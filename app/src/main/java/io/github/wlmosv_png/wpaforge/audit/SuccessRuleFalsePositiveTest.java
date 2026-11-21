package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * P0-1 反例门禁：**读不到不能当成没有**。
 *
 * <p><b>重建声明</b>：本文件是 2026-11-21 事故后按保留的编译产物
 * （{@code javap -c -p} 的字节码与字符串常量）重新编写的，**不是**原始源码。
 * 用例名、断言措辞、被测方法均从字节码还原；实现代码为重新编写。
 *
 * <h3>修复前的事故链（本条测试要钉死它不得复活）</h3>
 * <pre>
 *   ① list-networks 返回非空错误文本（"Permission denied" / "Unknown command"）
 *   ② forgetTargetByShell 只判 `out == null`，于是把错误文本当"读到了"
 *      → netIdIn() 解析不出 → leftover = -1 → return true（"已删净"）
 *   ③ netIdOfTarget 同样返回 -1 → 净检 `staleNetId >= 0` 不成立 → 放行
 *   ④ preListOk = (text != null) → true → baselineDecision 判 DECIDE_CLEAR
 *   ⑤ 系统仍挂着目标旧配置 → status 报 "SSID + COMPLETED + IP"
 *   ⑥ SuccessRule 在 targetNetId = -1 时跳过冲突检查 → **SUCCESS**
 *   ⑦ finishTask 写 DONE_HIT + 命中密文 + HistStore 命中记录
 * </pre>
 * 结果：错误密码被记成命中。
 *
 * <p>纯离线，不连接任何网络、不触碰设备状态。
 */
public final class SuccessRuleFalsePositiveTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void ok(String name, boolean cond) { ok(name, cond, ""); }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-P0";
    static final String BSSID = "02:00:00:00:00:0E";

    /**
     * 可编程假壳。
     *
     * <p>关键：{@code listOut} 与 {@code statusOut} 都是**固定字符串**，
     * 不按调用次数变化 —— 这样才能确定性地复现「读不到但系统仍连着目标」。
     */
    static final class Shell implements CrackEngine.ShellChannel {
        String listOut;
        String statusOut;
        /** connect 命令发出后 status 变成这个（null = 不变化）。 */
        String statusAfterConnect;
        boolean connectSeen = false;
        final List<String> cmds = new ArrayList<>();

        Shell(String listOut, String statusOut) {
            this.listOut = listOut;
            this.statusOut = statusOut;
        }

        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd == null) return "";
            cmds.add(cmd);
            if (cmd.contains("connect-network")) { connectSeen = true; return ""; }
            if (cmd.startsWith("cmd wifi status")) {
                if (connectSeen && statusAfterConnect != null) return statusAfterConnect;
                return statusOut;
            }
            if (cmd.startsWith("cmd wifi list-networks")) return listOut;
            if (cmd.startsWith("cmd wifi forget-network")) return "";
            if (cmd.contains("logcat")) return LogcatCmd.RC + "0\n" + LogcatCmd.RC_GREP + "0\n";
            return "";
        }

        @Override public String name() { return "p0-fake"; }
    }

    private static String attemptIdOf(int seq) throws Exception {
        Class<?> c = Class.forName("io.github.wlmosv_png.wpaforge.audit.AttemptId");
        Method nr = c.getMethod("newRunId", long.class, String.class, int.class);
        String run = (String) nr.invoke(null, 1_764_000_000_000L, TARGET, 1);
        return (String) c.getMethod("of", String.class, int.class).invoke(null, run, seq);
    }

    private static CrackEngine engineWith(Shell sh) throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(2_000_000L, 1000L, 0L));
        Method bypass = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        bypass.setAccessible(true);
        bypass.invoke(e);
        e.setShellChannel(sh);
        return e;
    }

    private static NetRecord target() {
        return new NetRecord(TARGET, BSSID, -55, 2412,
                NetRecord.Security.WPA2, false, "");
    }

    private static CrackEngine.Attempt run(Shell sh, String pw) throws Exception {
        String aid = attemptIdOf(1);
        CrackEngine e = engineWith(sh);
        return e.tryOne(target(), "WPA2", pw, 1200L, aid);
    }

    /** 「系统仍连着目标」的 status 文本（旧连接 / 旧配置残留的现场形态）。 */
    private static String statusOnTarget() {
        return "Wifi is connected to \"" + TARGET + "\"\n"
                + "WifiInfo: SSID: \"" + TARGET + "\", BSSID: " + BSSID
                + ", IP: /192.168.1.7, Supplicant state: COMPLETED, Net ID: 613\n";
    }

    public static void main(String[] args) {
        System.out.println("== P0-1 假成功反例：读不到 != 没有 ==");
        try {
            // ---------------------------------------------------------- 1. 解析器三态
            System.out.println();
            System.out.println("-- 1. ListNetworksParser 三态 --");
            ok("空输出 = 合法空列表（确认没有）",
                    ListNetworksParser.findNetIdOrUnreadable("", TARGET) == -1L,
                    "got=" + ListNetworksParser.findNetIdOrUnreadable("", TARGET));
            ok("含表头的正常列表 = 可判定",
                    ListNetworksParser.looksLikeListOutput(
                            "Network Id      SSID            Security type\n"));
            String row = "613          " + TARGET + "      wpa2-psk\n";
            ok("正常列表找到目标 = netId 613",
                    ListNetworksParser.findNetIdOrUnreadable(row, TARGET) == 613L,
                    "got=" + ListNetworksParser.findNetIdOrUnreadable(row, TARGET));
            ok("正常列表但没有目标 = -1（确认没有）",
                    ListNetworksParser.findNetIdOrUnreadable(
                            "Network Id      SSID            Security type\n"
                                    + "700   OTHER   wpa2-psk\n", TARGET) == -1L);

            String[] errs = {
                    "Permission denied\n",
                    "cmd: Can't find service: wifi\n",
                    "Unknown command: list-networks\n",
                    "error: not found\n",
            };
            int errOk = 0;
            for (String e : errs) {
                long v = ListNetworksParser.findNetIdOrUnreadable(e, TARGET);
                if (v == ListNetworksParser.NETID_UNREADABLE) errOk++;
                else info("未判读不到: [" + e.trim() + "] -> " + v);
            }
            ok("4 种错误文本全部判 NETID_UNREADABLE（-2）", errOk == 4, "okCount=" + errOk);
            ok("null 输出判读不到",
                    ListNetworksParser.findNetIdOrUnreadable(null, TARGET)
                            == ListNetworksParser.NETID_UNREADABLE);
            ok("非零退出码判读不到（哪怕文本看起来正常）",
                    ListNetworksParser.findNetIdOrUnreadable(row, TARGET, 1)
                            == ListNetworksParser.NETID_UNREADABLE);
            ok("零退出码 + 正常文本仍能解析",
                    ListNetworksParser.findNetIdOrUnreadable(row, TARGET, 0) == 613L);
            ok("键值对格式（networkId: N  SSID: \"X\"）也能解析",
                    ListNetworksParser.findNetIdOrUnreadable(
                            "networkId: 613\tSSID: \"" + TARGET + "\"\n", TARGET) == 613L);

            // ---------------------------------------------------------- 2. SuccessRule 严格档
            System.out.println();
            System.out.println("-- 2. SuccessRule：闸门未确认干净时 netId 缺失不得判成功 --");
            SuccessRule.Assessment loose = SuccessRule.assess(
                    TARGET, TARGET, "COMPLETED", "192.168.1.7", -1, -1);
            ok("6 参（无闸门信息）保持 1.9.60 语义：判 SUCCESS（不漏判）",
                    loose.verdict == CrackEngine.Verdict.SUCCESS,
                    "verdict=" + loose.verdict);
            SuccessRule.Assessment strict = SuccessRule.assess(
                    TARGET, TARGET, "COMPLETED", "192.168.1.7", -1, -1, false);
            ok("7 参 + 闸门未确认 = UNKNOWN（不写命中）",
                    strict.verdict == CrackEngine.Verdict.UNKNOWN,
                    "verdict=" + strict.verdict);
            ok("该 UNKNOWN 的原因码是 netid-unverified",
                    "netid-unverified".equals(strict.reason), "reason=" + strict.reason);
            ok("闸门确认干净 + netId 缺失 = SUCCESS（慢 AP 不漏判）",
                    SuccessRule.assess(TARGET, TARGET, "COMPLETED", "192.168.1.7",
                            -1, -1, true).verdict == CrackEngine.Verdict.SUCCESS);
            ok("闸门确认干净 + 两侧 netId 一致 = SUCCESS",
                    SuccessRule.assess(TARGET, TARGET, "COMPLETED", "192.168.1.7",
                            613, 613, true).verdict == CrackEngine.Verdict.SUCCESS);
            ok("闸门确认干净 + netId 冲突 = UNKNOWN（不判成功）",
                    SuccessRule.assess(TARGET, TARGET, "COMPLETED", "192.168.1.7",
                            613, 700, true).verdict == CrackEngine.Verdict.UNKNOWN);

            // ---------------------------------------------------------- 3. 端到端反例
            System.out.println();
            System.out.println("-- 3. 端到端：错误文本 + 系统仍连着目标 --");
            {
                Shell sh = new Shell("Permission denied\n", statusOnTarget());
                sh.statusAfterConnect = statusOnTarget();
                CrackEngine.Attempt a = run(sh, "WrongPass99");
                info("verdict=" + a.verdict + " grade=" + a.grade
                        + " reason=" + a.reason + " by=" + a.confirmedBy);
                ok("★ 不得判 SUCCESS（修复前这里会假成功）",
                        a.verdict != CrackEngine.Verdict.SUCCESS, "verdict=" + a.verdict);
                ok("结论为 NONE 级（不写 triedSet / 不推 cursor）",
                        a.grade == EvidenceGrade.Level.NONE, "grade=" + a.grade);
            }
            {
                Shell sh = new Shell("Unknown command: list-networks\n", statusOnTarget());
                sh.statusAfterConnect = statusOnTarget();
                CrackEngine.Attempt a = run(sh, "WrongPass99");
                ok("★ 未知子命令同样不得判 SUCCESS",
                        a.verdict != CrackEngine.Verdict.SUCCESS, "verdict=" + a.verdict);
            }
            {
                Shell sh = new Shell(null, statusOnTarget());
                sh.statusAfterConnect = statusOnTarget();
                CrackEngine.Attempt a = run(sh, "WrongPass99");
                ok("★ list-networks 无返回同样不得判 SUCCESS",
                        a.verdict != CrackEngine.Verdict.SUCCESS, "verdict=" + a.verdict);
            }

            // ---------------------------------------------------------- 4. 旧配置仍在
            System.out.println();
            System.out.println("-- 4. 旧配置仍在（netId 613 挂在列表里）--");
            {
                String listHasTarget = "Network Id      SSID            Security type\n"
                        + "613          " + TARGET + "      wpa2-psk\n";
                Shell sh = new Shell(listHasTarget, statusOnTarget());
                sh.statusAfterConnect = statusOnTarget();
                CrackEngine.Attempt a = run(sh, "WrongPass99");
                info("verdict=" + a.verdict + " reason=" + a.reason);
                ok("★ 旧配置未清 → 闸门拦截，不得判 SUCCESS",
                        a.verdict != CrackEngine.Verdict.SUCCESS, "verdict=" + a.verdict);
                ok("闸门原因是 stale-config",
                        String.valueOf(a.reason).contains("stale_config"),
                        "reason=" + a.reason);
            }

            // ---------------------------------------------------------- 5. 正确路径仍必须成功
            System.out.println();
            System.out.println("-- 5. 正确路径：闸门干净 + 本次真的连上 --");
            {
                String cleanList = "Network Id      SSID            Security type\n"
                        + "700          OTHER           wpa2-psk\n";
                Shell sh = new Shell(cleanList, "Wifi is not connected\n");
                sh.statusAfterConnect = "Wifi is connected to \"" + TARGET + "\"\n"
                        + "WifiInfo: SSID: \"" + TARGET + "\", BSSID: " + BSSID
                        + ", IP: /192.168.1.9, Supplicant state: COMPLETED, Net ID: 700\n";
                CrackEngine.Attempt a = run(sh, "RightPass123");
                info("verdict=" + a.verdict + " grade=" + a.grade
                        + " by=" + a.confirmedBy);
                ok("★ 本次真实认证证据必须仍能产出 SUCCESS",
                        a.verdict == CrackEngine.Verdict.SUCCESS, "verdict=" + a.verdict);
                ok("且必须是 DIRECT 级（才允许写命中历史）",
                        a.grade == EvidenceGrade.Level.DIRECT, "grade=" + a.grade);
            }

            // ---------------------------------------------------------- 6. 证据不足不写命中
            System.out.println();
            System.out.println("-- 6. 证据不足时不得产生任何命中副作用 --");
            {
                Shell sh = new Shell("Permission denied\n", "Wifi is not connected\n");
                CrackEngine.Attempt a = run(sh, "SomePass99");
                ok("非 SUCCESS 结论的 grade 必为 NONE",
                        a.verdict == CrackEngine.Verdict.SUCCESS
                                || a.grade == EvidenceGrade.Level.NONE,
                        "verdict=" + a.verdict + " grade=" + a.grade);
                ok("非 SUCCESS 结论不会被当成命中（isSuccess=false）",
                        !a.isSuccess(), "isSuccess=" + a.isSuccess());
            }

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== P0-1 假成功反例测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
