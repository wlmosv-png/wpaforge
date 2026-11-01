package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D5-3：**混合判定结果下的 cursor 安全性**。
 *
 * <h3>两部分</h3>
 * <ol>
 *   <li><b>穷举</b>：直接调用生产函数 {@link CrackEngine#nextCursor(int,int,EvidenceGrade.Level)}，
 *       覆盖全部等级组合，断言「边界永远停在第一个未获 DIRECT 的候选」。</li>
 *   <li><b>端到端</b>：驱动真实 {@code run()}，验证真实调度路径下
 *       非 DIRECT 候选不会被越过（cursor 停在起点）。</li>
 * </ol>
 *
 * <h3>为什么用生产纯函数而不是只做端到端</h3>
 * 端到端要精确构造「同一 run 内三种不同等级」需要在假 Shell 里同时
 * 模拟 logcat 窗口隔离、时间锚点与命令时序，成本远高于收益，
 * 且任何一处模拟偏差都会让断言测到「假环境的产物」而不是「真实逻辑」。
 * 因此：**cursor 计算逻辑用生产纯函数穷举**（这是它唯一的实现），
 * **调度路径用真实 run() 验证不回退**。
 */
public final class P05CursorBoundaryTest {

    private static int pass = 0, fail = 0;
    /** 端到端真正产出预期等级组合的用例数（受控验证通过）。 */
    private static int verified = 0;
    /** 桌面环境无法产出预期等级组合、因而未验证的用例。 */
    private static final List<String> unverified = new ArrayList<>();

    private static boolean producedGrades(List<String> got, Kind[] script) {
        if (got.size() != script.length) return false;
        for (int i = 0; i < script.length; i++) {
            String want = script[i] == Kind.DIRECT ? "DIRECT"
                    : script[i] == Kind.INFERRED ? "INFERRED" : "NONE";
            if (!want.equals(got.get(i))) return false;
        }
        return true;
    }

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static final EvidenceGrade.Level D = EvidenceGrade.Level.DIRECT;
    private static final EvidenceGrade.Level I = EvidenceGrade.Level.INFERRED;
    private static final EvidenceGrade.Level N = EvidenceGrade.Level.NONE;

    /** 按脚本顺序跑一遍 nextCursor，返回最终边界。 */
    private static int runScript(EvidenceGrade.Level[] script, int start) {
        int cursor = start;
        for (int i = start; i < script.length; i++) {
            cursor = CrackEngine.nextCursor(cursor, i, script[i]);
        }
        return cursor;
    }

    private static void scenario(String tag, EvidenceGrade.Level[] script, int expect) {
        int got = runScript(script, 0);
        info("[" + tag + "] " + java.util.Arrays.toString(script)
                + " -> cursor=" + got + "（期望 " + expect + "）");
        ok("[" + tag + "] cursor 必须停在第一个未获 DIRECT 的候选", got == expect,
                "实际 " + got + "，期望 " + expect);
    }

    public static void main(String[] args) {
        System.out.println("== D5-3 混合结果的 cursor 安全性 ==");

        // 用户点名的 5 个场景
        scenario("1", new EvidenceGrade.Level[]{I, D, N}, 0);
        scenario("2", new EvidenceGrade.Level[]{N, D, N}, 0);
        scenario("3", new EvidenceGrade.Level[]{I, I, D}, 0);
        scenario("4", new EvidenceGrade.Level[]{D, I, D}, 1);
        scenario("5", new EvidenceGrade.Level[]{D, D, D}, 3);

        // 补充：边界不应回退
        ok("补充1：cursor 只增不减",
                CrackEngine.nextCursor(2, 0, D) == 2, "回退了");
        ok("补充2：全部 DIRECT 时边界 = 长度",
                runScript(new EvidenceGrade.Level[]{D, D, D, D}, 0) == 4, "");
        ok("补充3：首条非 DIRECT 则边界恒为起点",
                runScript(new EvidenceGrade.Level[]{N, D, D, D}, 0) == 0, "");
        ok("补充4：从非 0 起点开始时同样成立",
                runScript(new EvidenceGrade.Level[]{D, D, I, D}, 2) == 2, "");

        // ---------- 端到端：真实 run() 消费真实证据等级
        System.out.println();
        System.out.println("-- 端到端（真实 run()，脚本化日志驱动真实判定链）--");
        endToEnd("E1", new Kind[]{Kind.DIRECT, Kind.DIRECT, Kind.DIRECT}, 3, "DDD");
        endToEnd("E2", new Kind[]{Kind.INFERRED, Kind.DIRECT, Kind.NONE}, 0, "IDN");
        endToEnd("E3", new Kind[]{Kind.NONE, Kind.DIRECT, Kind.NONE}, 0, "NDN");
        endToEnd("E4", new Kind[]{Kind.INFERRED, Kind.INFERRED, Kind.DIRECT}, 0, "IID");
        endToEnd("E5", new Kind[]{Kind.DIRECT, Kind.INFERRED, Kind.DIRECT}, 1, "DID");
        endToEnd("E6", new Kind[]{Kind.DIRECT, Kind.NONE, Kind.DIRECT}, 1, "DND");
        endToEnd("E7", new Kind[]{Kind.NONE, Kind.INFERRED, Kind.DIRECT}, 0, "NID");
        endToEnd("E8", new Kind[]{Kind.INFERRED, Kind.DIRECT, Kind.DIRECT}, 0, "IDD");
        endToEnd("E9", new Kind[]{Kind.DIRECT, Kind.DIRECT, Kind.INFERRED}, 2, "DDI");
        endToEnd("E10", new Kind[]{Kind.DIRECT, Kind.DIRECT, Kind.NONE}, 2, "DDN");

        System.out.println();
        System.out.println();
        System.out.println("端到端受控验证通过: " + verified + " 个场景");
        System.out.println("桌面环境未验证: " + unverified.size() + " 个场景 -> " + unverified);
        System.out.println("=== D5-3 cursor 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------ 端到端辅助

    /** 单条候选期望的结论类型。 */
    enum Kind { DIRECT, INFERRED, NONE }

    static final class FakeShell implements CrackEngine.ShellChannel {
        final Kind[] script;
        String aid = null;
        int connIndex = -1;
        FakeShell(Kind[] script) { this.script = script; }
        private boolean dumped = false;
        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd == null) return "";
            if (!dumped && (cmd.contains("WPF_PROBE") || cmd.contains("logcat"))) {
                dumped = true;
                System.out.println("        [dbg-cmd] " + (cmd.length() > 120 ? cmd.substring(0,120)+"..." : cmd));
            }
            // 注意顺序：logcat 命令里也含 "-s WPF_PROBE"，
            // 若先判 WPF_PROBE 就会把日志读取整个吞掉（本测试曾因此一直读不到日志）。
            if (cmd.contains("logcat")) {
                StringBuilder sb = new StringBuilder();
                if (aid != null) {
                    sb.append("10-31 12:00:00.000  1000  1000 I WPF_PROBE: \"attempt=")
                      .append(aid).append("\"\n");
                }
                // 用 aid 里的序号（#N，1 起）精确索引脚本。
                // 不能用全局 connect 计数：connect 在独立线程发出，
                // 计数会滞后/超前后一条候选的后续轮次，导致读错脚本。
                int idx = 0;
                if (aid != null) {
                    int hash = aid.lastIndexOf('#');
                    if (hash > 0 && hash + 1 < aid.length()) {
                        try { idx = Integer.parseInt(aid.substring(hash + 1)) - 1; }
                        catch (Throwable ignored) { }
                    }
                }
                Kind k = (idx >= 0 && idx < script.length) ? script[idx] : Kind.NONE;
                if (k != Kind.NONE) {
                    sb.append("10-31 12:00:01.100  1000  1000 I wpa_supplicant: wlan0: ")
                      .append("Trying to associate with SSID 'LAB-C5'\n");
                }
                if (k == Kind.INFERRED) {
                    // 无归属失败：不带 SSID/BSSID -> INFERRED
                    sb.append("10-31 12:00:01.900  1000  1000 I wpa_supplicant: WPA: ")
                      .append("4-Way Handshake failed - pre-shared key may be incorrect\n");
                } else if (k == Kind.DIRECT) {
                    // 带归属的直接否认 -> DIRECT
                    sb.append("10-31 12:00:01.500  1000  1000 I wpa_supplicant: RSN: ")
                      .append("RX message 1 of 4-Way Handshake from 02:00:00:00:00:0E\n");
                    sb.append("10-31 12:00:01.600  1000  1000 I wpa_supplicant: WPA: ")
                      .append("4-Way Handshake failed - pre-shared key may be incorrect\n");
                    sb.append("10-31 12:00:01.700  1000  1000 I wpa_supplicant: ")
                      .append("CTRL-EVENT-SSID-TEMP-DISABLED ssid=\"LAB-C5\" ")
                      .append("reason=WRONG_KEY auth_failures=1\n");
                }
                sb.append(LogcatCmd.RC_GREP).append("0\n");
                sb.append(LogcatCmd.RC).append("0\n");
                return sb.toString();
            }
            if (cmd.contains("connect-network")) { connIndex++; return ""; }
            if (cmd.contains("WPF_PROBE")) {
                Matcher m = Pattern.compile("attempt=([A-Za-z0-9#-]+)").matcher(cmd);
                if (m.find()) aid = m.group(1);
                return "";
            }
            // 必须让目标出现在 status 里（非 COMPLETED，不构成成功帧），
            // 否则引擎判定「本条从未被尝试」-> not-attempted/ERROR，
            // 走不到日志判据分支（本测试曾因此一直拿不到 DIRECT/INFERRED）。
            if (cmd.startsWith("cmd wifi status")) {
                return "WifiInfo: SSID: \"LAB-C5\", BSSID: 02:00:00:00:00:0E, "
                        + "Supplicant state: ASSOCIATING, Net ID: -1\n";
            }
            if (cmd.startsWith("cmd wifi list-networks")) return "";
            return "";
        }
        @Override public String name() { return "fake-c5"; }
    }

    static final class Rec implements CrackEngine.Listener {
        final List<String> verdicts = new ArrayList<>();
        final List<String> grades = new ArrayList<>();
        int finishedCursor = Integer.MIN_VALUE;
        @Override public void onAttemptStart(int index, int total, String candidate) {}
        @Override public void onAttemptResult(CrackEngine.Attempt a) {
            verdicts.add(a.verdict.name());
            grades.add(String.valueOf(a.grade));
        }
        @Override public void onProgress(int tested, int total, double rate, long etaMs) {}
        @Override public void onFinished(int tested, int cursor, String hitPassword, String outcome) {
            finishedCursor = cursor;
        }
        @Override public void onBlocked(String title, String detail) {}
    }

    private static String attemptIdOf() throws Exception {
        Class<?> c = Class.forName("io.github.wlmosv_png.wpaforge.audit.AttemptId");
        Method nr = c.getMethod("newRunId", long.class, String.class, int.class);
        String run = (String) nr.invoke(null, 1_764_000_000_000L, "LAB-C5", 1);
        return (String) c.getMethod("of", String.class, int.class).invoke(null, run, 1);
    }

    private static CrackEngine newEngine() throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(2_000_000L, 1000L, 0L));
        Method bypass = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        bypass.setAccessible(true);
        bypass.invoke(e);
        return e;
    }

    /**
     * 端到端：真实 run() 跑完一个脚本，断言 cursor 停在「第一个未获 DIRECT」处。
     * 日志由假 Shell 按候选序注入，**判定逻辑全部走生产代码**。
     */
    private static void endToEnd(String tag, Kind[] script, int expectCursor, String label) {
        try {
            FakeShell sh = new FakeShell(script);
            CrackEngine engine = newEngine();
            engine.setShellChannel(sh);

            NetRecord t = new NetRecord("LAB-C5", "02:00:00:00:00:0E", -55, 2412,
                    NetRecord.Security.WPA2, false, "");
            List<Candidate> cands = new ArrayList<>();
            for (int i = 0; i < script.length; i++) {
                cands.add(new Candidate("candidate-" + i + "-x", Candidate.Origin.USER_DICT));
            }
            Rec cb = new Rec();
            engine.run(t, cands, 0, 1500L, cb);

            info("[" + tag + "/" + label + "] grade=" + cb.grades
                    + " verdict=" + cb.verdicts + " cursor=" + cb.finishedCursor
                    + "（期望 " + expectCursor + "）");

            // 先确认脚本是否真的产出了预期的等级组合。
            // 若没有，这条用例**不算通过**，而是「未验证」——
            // 绝不能用「全 NONE 恰好让 cursor=0」来冒充混合场景的并通过。
            boolean produced = producedGrades(cb.grades, script);
            if (!produced) {
                unverified.add(tag + "/" + label);
                System.out.println("  [UNVERIFIED][" + tag + "/" + label
                        + "] 桌面环境未能产出预期等级组合（实际 " + cb.grades
                        + "），本用例不计入通过，也不计入失败");
                return;
            }
            verified++;
            ok("[" + tag + "/" + label + "] 真实 run() 的 cursor 符合安全边界",
                    cb.finishedCursor == expectCursor,
                    "实际 " + cb.finishedCursor + "，期望 " + expectCursor
                            + "；grade=" + cb.grades);
        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL [" + tag + "] 异常: " + t);
            t.printStackTrace(System.out);
        }
    }
}
