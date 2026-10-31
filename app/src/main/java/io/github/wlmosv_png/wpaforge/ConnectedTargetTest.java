package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EngineClock;
import io.github.wlmosv_png.wpaforge.audit.TestEngineClock;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 「已连接目标 → UNKNOWN」的四场景复现（C 阶段 spike-4）。
 *
 * <h3>要回答的问题</h3>
 * 当设备在执行候选之前就已经连着目标 SSID 时，为什么 {@code tryOne} 返回
 * 「UNKNOWN: 上一条的连接未能断开，本条判定不可信」？
 * 是主动保护、清理失败、错误归属，还是只有 SSID 比较、缺少来源证据？
 *
 * <h3>方法</h3>
 * 四个场景各自构造一份 **可编程 FakeShell**（按命令路由，不按调用次数），
 * 每个场景都记录：初始 SSID / BSSID / Net ID / 连接状态、断开命令与返回值、
 * 连接命令与返回值、每次状态采集、虚拟时间、AttemptId、最终 verdict/detail。
 *
 * <h3>纪律</h3>
 * 不改生产代码、不改判定规则、不为"让结果好看"而伪造成功条件。
 * 结果如实打印，包括"保护生效"和"可能误拦截"两类。
 */
public final class ConnectedTargetTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    // ------------------------------------------------------------ 场景建模

    /**
     * 一个可按场景编程的 Wi-Fi 世界模型。
     *
     * <p>状态机只包含引擎真正会观察到的几样东西：
     * <ul>
     *   <li>当前连接（SSID / netId / state / ip）</li>
     *   <li>已保存配置列表（list-networks 的输出）</li>
     * </ul>
     * 所有命令都只读/改这个模型，**不按调用次数返回不同结果**。
     */
    static final class World {
        String connSsid;      // 当前连着的 SSID（null = 未连接）
        long connNetId = -1;  // 当前连接的 netId
        String connState = "DISCONNECTED";
        String connIp = "0.0.0.0";
        final List<long[]> saved = new ArrayList<>();  // 已保存配置 [[netId, ...]]
        final List<String> savedSsid = new ArrayList<>();

        // 记录
        final List<String> log = new ArrayList<>();

        void save(String ssid, long netId) { saved.add(new long[]{netId}); savedSsid.add(ssid); }

        void forgetAll(String ssid) {
            for (int i = savedSsid.size() - 1; i >= 0; i--) {
                if (ssid.equals(savedSsid.get(i))) { saved.remove(i); savedSsid.remove(i); }
            }
        }

        long netIdOf(String ssid) {
            for (int i = 0; i < savedSsid.size(); i++) {
                if (ssid.equals(savedSsid.get(i))) return saved.get(i)[0];
            }
            return -1;
        }

        String statusText() {
            if (connSsid == null) return "Wifi is not connected\n";
            return "WifiInfo: SSID: \"" + connSsid + "\", BSSID: 02:00:00:00:00:0A, "
                    + "IP: /" + connIp + ", Supplicant state: " + connState
                    + ", Net ID: " + connNetId + "\n";
        }

        String listText() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < savedSsid.size(); i++) {
                sb.append("networkId: ").append(saved.get(i)[0])
                        .append("\tSSID: \"").append(savedSsid.get(i)).append("\"\n");
            }
            return sb.toString();
        }
    }

    /** 按命令路由的 FakeShell（不按调用次数拼接结果）。 */
    static class Shell implements CrackEngine.ShellChannel {
        final World w;
        /** 断开命令是否真的生效（模拟"关 Wi-Fi 也断不掉"的故障）。 */
        final boolean disconnectEffective;
        final List<String> cmds = new ArrayList<>();

        Shell(World w, boolean disconnectEffective) {
            this.w = w;
            this.disconnectEffective = disconnectEffective;
        }

        @Override public String exec(String cmd, long timeoutMs) {
            cmds.add(cmd);
            if (cmd == null) return "";
            if (cmd.startsWith("cmd wifi status")) return w.statusText();
            if (cmd.startsWith("cmd wifi list-networks")) return w.listText();
            if (cmd.startsWith("cmd wifi forget-network")) {
                // 从配置列表里删掉
                String rest = cmd.substring("cmd wifi forget-network".length()).trim();
                String idStr = rest.split("[;\\s]")[0];
                try {
                    long id = Long.parseLong(idStr);
                    for (int i = 0; i < w.saved.size(); i++) {
                        if (w.saved.get(i)[0] == id) {
                            w.saved.remove(i); w.savedSsid.remove(i); break;
                        }
                    }
                } catch (Throwable ignored) {}
                return "";
            }
            if (cmd.startsWith("cmd wifi set-wifi-enabled disabled")) {
                if (disconnectEffective) {
                    w.connSsid = null; w.connNetId = -1; w.connState = "DISCONNECTED";
                }
                return "";
            }
            if (cmd.startsWith("cmd wifi set-wifi-enabled enabled")) return "";
            if (cmd.startsWith("cmd wifi connect-network")) {
                // 连接命令：只有"本次连接成功"的场景才改状态
                return "";
            }
            return "";
        }

        @Override public String name() { return "fake"; }
    }

    // ------------------------------------------------------------ 执行

    static final class Res {
        CrackEngine engine;
        Shell shell;
        World world;
        TestEngineClock clock;
        Object attempt;
        long t0, t1;
        Throwable err;
        String initialSnapshot;
        String statusReads;
    }

    private static final String TARGET = "LAB-TARGET";
    private static final String OTHER = "OTHER-AP";

    /**
     * @param initialConn     初始连着的 SSID（null = 未连接）
     * @param initialNetId    初始 netId
     * @param keepSaved       初始已保存配置（netId，-1 = 不保存）
     * @param afterConnect    连接命令后世界变成什么样："stay" / "target" / "none"
     * @param disconnectWorks 断开命令是否生效
     */
    private static Res run(String initialConn, long initialNetId, long keepSaved,
                           String afterConnect, boolean disconnectWorks) throws Exception {
        Res r = new Res();
        r.world = new World();
        r.world.connSsid = initialConn;
        r.world.connNetId = initialNetId;
        r.world.connState = initialConn == null ? "DISCONNECTED" : "COMPLETED";
        r.world.connIp = initialConn == null ? "0.0.0.0" : "192.168.1.3";
        if (keepSaved >= 0) r.world.save(TARGET, keepSaved);

        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        r.engine = (CrackEngine) ctor.newInstance(0);

        r.clock = new TestEngineClock(2_000_000L);
        Method setClock = CrackEngine.class.getDeclaredMethod(
                "setClockForTest", EngineClock.class);
        setClock.setAccessible(true);
        setClock.invoke(r.engine, r.clock);

        // 连接成功后世界的演化：用包装壳实现（连接命令前状态不变，之后可变）
        World w = r.world;
        final boolean[] connected = {false};
        Shell base = new Shell(w, disconnectWorks);
        r.shell = new Shell(w, disconnectWorks) {
            @Override public String exec(String cmd, long timeoutMs) {
                String out = base.exec(cmd, timeoutMs);
                cmds.addAll(base.cmds);
                base.cmds.clear();
                if (cmd != null && cmd.startsWith("cmd wifi connect-network")) {
                    connected[0] = true;
                }
                if (connected[0]) {
                    if ("target".equals(afterConnect)) {
                        w.connSsid = TARGET; w.connNetId = 700;
                        w.connState = "COMPLETED"; w.connIp = "192.168.1.9";
                    } else if ("none".equals(afterConnect)) {
                        w.connSsid = null; w.connNetId = -1;
                        w.connState = "DISCONNECTED"; w.connIp = "0.0.0.0";
                    }
                }
                return out;
            }
        };
        r.engine.setShellChannel(r.shell);

        r.initialSnapshot = "SSID=" + initialConn + " netId=" + initialNetId
                + " saved=" + (keepSaved >= 0 ? keepSaved : "无");

        NetRecord target = new NetRecord(TARGET, "02:00:00:00:00:0A", -55, 2412,
                NetRecord.Security.WPA2, false, "");

        String attemptId = buildAttemptId(7);
        r.t0 = r.clock.uptimeMillis();
        try {
            r.attempt = r.engine.tryOne(target, "WPA2", "Correct9Pass", 6000L, attemptId);
        } catch (Throwable t) {
            r.err = t;
        }
        r.t1 = r.clock.uptimeMillis();
        r.statusReads = String.valueOf(countStatus(r.shell.cmds));
        return r;
    }

    private static int countStatus(List<String> cmds) {
        int n = 0;
        for (String c : cmds) if (c != null && c.startsWith("cmd wifi status")) n++;
        return n;
    }

    private static String buildAttemptId(int seq) throws Exception {
        Class<?> c = Class.forName("io.github.wlmosv_png.wpaforge.audit.AttemptId");
        Method newRunId = c.getMethod("newRunId", long.class, String.class, int.class);
        String runId = (String) newRunId.invoke(null, 1_764_000_000_000L, TARGET, 1);
        Method of = c.getMethod("of", String.class, int.class);
        return (String) of.invoke(null, runId, seq);
    }

    private static Object field(Object o, String n) {
        try {
            Field f = o.getClass().getDeclaredField(n);
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable t) { return "<" + t.getClass().getSimpleName() + ">"; }
    }

    private static void dump(String tag, Res r) {
        System.out.println("  ---- " + tag + " ----");
        info("初始快照    : " + r.initialSnapshot);
        info("虚拟时间    : " + r.t0 + " → " + r.t1 + "（+" + (r.t1 - r.t0) + "ms）");
        info("Shell 调用  : " + r.shell.cmds.size() + " 条（status 读取 "
                + r.statusReads + " 次）");
        // 关键命令摘录
        int shown = 0;
        for (String c : r.shell.cmds) {
            if (shown >= 8) break;
            boolean key = c.startsWith("cmd wifi forget-network")
                    || c.contains("set-wifi-enabled")
                    || c.startsWith("cmd wifi connect-network")
                    || c.startsWith("cmd wifi list-networks");
            if (key) {
                info("  命令      : " + (c.length() > 72 ? c.substring(0, 72) + "…" : c));
                shown++;
            }
        }
        info("异常        : " + (r.err == null ? "无"
                : r.err.getClass().getSimpleName() + ": " + r.err.getMessage()));
        if (r.attempt != null) {
            info("verdict     : " + field(r.attempt, "verdict"));
            info("detail      : " + field(r.attempt, "detail"));
            info("reason      : " + field(r.attempt, "reason"));
            info("source      : " + field(r.attempt, "confirmedBy"));
        } else {
            info("Attempt     : 未创建");
        }
    }

    // ------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        System.out.println("=== ConnectedTargetTest（已连接目标的四场景）===");

        // ============================================================ A
        System.out.println();
        System.out.println("== 场景 A：未连接目标 · 本次连接失败 ==");
        Res a = run(null, -1, -1, "none", true);
        dump("SC-A", a);
        ok("A 未抛异常", a.err == null);
        ok("A Attempt 已创建", a.attempt != null);
        ok("A 未出现「旧的连接未能断开」误判",
                !String.valueOf(field(a.attempt, "detail")).contains("未能断开"));
        String av = String.valueOf(field(a.attempt, "verdict"));
        ok("A 结论为 TIMEOUT/ERROR/UNKNOWN（非 SUCCESS）",
                !"SUCCESS".equals(av));

        // ============================================================ B
        System.out.println();
        System.out.println("== 场景 B：已连接目标 · 断开无效 · 仍连目标 ==");
        Res b = run(TARGET, 613, 613, "stay", false);
        dump("SC-B", b);
        ok("B 未抛异常", b.err == null);
        String bd = String.valueOf(field(b.attempt, "detail"));
        ok("B 触发旧连接保护（detail 含「未能断开」）", bd.contains("未能断开"));
        ok("B verdict == UNKNOWN", "UNKNOWN".equals(String.valueOf(field(b.attempt, "verdict"))));
        // 关键：观察它是否真的尝试了断开
        boolean triedForget = false, triedDisable = false;
        for (String c : b.shell.cmds) {
            if (c.startsWith("cmd wifi forget-network")) triedForget = true;
            if (c.contains("set-wifi-enabled disabled")) triedDisable = true;
        }
        info("B 是否尝试 forget-network : " + triedForget);
        info("B 是否尝试关闭 Wi-Fi      : " + triedDisable);

        // ============================================================ C
        System.out.println();
        System.out.println("== 场景 C：已连接其他网络 · 本次连接目标成功 ==");
        Res c = run(OTHER, 611, -1, "target", true);
        dump("SC-C", c);
        ok("C 未抛异常", c.err == null);
        ok("C Attempt 已创建", c.attempt != null);
        String cv = String.valueOf(field(c.attempt, "verdict"));
        info("C 结论 = " + cv + "（注意：FakeShell 不模拟回连，见下方说明）");

        // ============================================================ D
        System.out.println();
        System.out.println("== 场景 D：已连接目标 · 断开有效 · 由本次候选重连目标 ==");
        Res d = run(TARGET, 613, 613, "target", true);
        dump("SC-D", d);
        ok("D 未抛异常", d.err == null);
        ok("D Attempt 已创建", d.attempt != null);
        String dd = String.valueOf(field(d.attempt, "detail"));
        info("D 是否被旧连接保护拦下: " + dd.contains("未能断开"));
        String dv = String.valueOf(field(d.attempt, "verdict"));
        info("D 结论 = " + dv);

        // ============================================================ 汇总事实
        System.out.println();
        System.out.println("== 事实汇总（用于回答判据到底是什么）==");
        info("B/D 差异只在于「断开命令是否生效」");
        info("B: disconnectEffective=false → 12s 轮询 + 关 Wi-Fi + 6s 轮询全失败 → UNKNOWN");
        info("D: disconnectEffective=true  → 3s 稳定窗口满足 → 放行 → 继续执行");

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) throw new RuntimeException("ConnectedTargetTest 失败 " + fail + " 项");
    }
}
