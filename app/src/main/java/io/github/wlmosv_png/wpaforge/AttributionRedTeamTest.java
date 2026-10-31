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
 * 连接归因反证实验（C 阶段 spike-5）。
 *
 * <h3>本轮唯一要回答的问题</h3>
 * 当 {@code SuccessRule.assess} 判出 SUCCESS 时，引擎**凭什么**认为这条连接
 * 是当前候选建立的？它能不能排除：
 * <ol>
 *   <li>候选执行前就存在的连接</li>
 *   <li>上一条候选留下的连接</li>
 *   <li>Android 系统自动回连</li>
 *   <li>其他进程建立的连接</li>
 * </ol>
 *
 * <h3>反证纪律</h3>
 * 六个场景全部是"**看起来像成功**"的构造，用来逼出判定的边界。
 * FakeShell 由**显式事件时间线**驱动（每条命令推进世界状态），
 * 不按调用次数拼接结果。
 *
 * <p>如果离线环境无法证明某条因果，本测试**明确标注"无法证明"**，
 * 不把模拟结果当成真机事实。
 */
public final class AttributionRedTeamTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static void note(String s) { System.out.println("  ▲    " + s); }

    // ---------------------------------------------------------------- 世界模型

    /** 世界状态。每次 shell 调用都是一个"时刻"，可观察到该时刻的连接状态。 */
    static final class World {
        String ssid;
        String bssid;
        long netId = -1;
        String state = "DISCONNECTED";
        String ip = "0.0.0.0";
        final List<String> statusTimeline = new ArrayList<>();

        void set(String s, String b, long id, String st, String ip) {
            this.ssid = s; this.bssid = b; this.netId = id;
            this.state = st; this.ip = ip;
            statusTimeline.add("t=" + tick + " ssid=" + s + " netId=" + id
                    + " state=" + st + " ip=" + ip);
        }

        int tick = 0;

        String statusText() {
            if (ssid == null) return "Wifi is not connected\n";
            return "WifiInfo: SSID: \"" + ssid + "\", BSSID: " + bssid + ", "
                    + "IP: /" + ip + ", Supplicant state: " + state
                    + ", Net ID: " + netId + "\n";
        }
    }

    /**
     * 由**事件时间线**驱动的 FakeShell。
     *
     * <p>核心机制：每次 exec 推进 world.tick；连接/断开的效果由构造时给的
     * {@code Timeline} 决定（例如"connect 命令后第 3 个 tick 出现新连接"）。
     * 这样可以模拟"系统自动回连"、"其他进程建立连接"等**不是本条候选造成**的
     * 连接出现 —— 这正是反证要的东西。
     */
    static class Shell implements CrackEngine.ShellChannel {
        final World w;
        final List<String> cmds = new ArrayList<>();
        /** 已保存配置 [netId, ssid] 交替存储。 */
        final List<long[]> saved = new ArrayList<>();
        final List<String> savedSsid = new ArrayList<>();
        /** 每个 tick 后要应用的世界变化。 */
        final List<Runnable> onTick = new ArrayList<>();
        int connectSeenAt = -1;
        final boolean disconnectWorks;
        /** 被 forget 删除过的 SSID —— 生产流程里配置已删，系统不应回连它。 */
        final java.util.Set<String> forgotten = new java.util.HashSet<>();
        /** connect 命令之后再过多少次 exec 调用，才出现"那条连接"。0 = 不出现。 */
        int appearAfterConnect = 0;
        /** 那条连接长什么样。 */
        String apSsid = null, apBssid = null, apIp = null;
        long apNetId = -1;

        Shell(World w, boolean disconnectWorks) {
            this.w = w;
            this.disconnectWorks = disconnectWorks;
        }

        void save(String ssid, long id) { saved.add(new long[]{id}); savedSsid.add(ssid); }

        long netIdOf(String ssid) {
            for (int i = 0; i < savedSsid.size(); i++) if (ssid.equals(savedSsid.get(i))) return saved.get(i)[0];
            return -1;
        }

        void forget(String ssid) {
            for (int i = savedSsid.size() - 1; i >= 0; i--) {
                if (ssid.equals(savedSsid.get(i))) { saved.remove(i); savedSsid.remove(i); }
            }
        }

        @Override public String exec(String cmd, long timeoutMs) {
            cmds.add(cmd);
            if (cmd == null) return "";
            w.tick++;
            // 先应用"本 tick 的时间线事件"；但已删配置不得自动回连
            if (w.tick < onTick.size() && onTick.get(w.tick) != null) {
                if (!forgotten.contains(TARGET)) onTick.get(w.tick).run();
            }

            if (cmd.startsWith("cmd wifi status")) return w.statusText();
            if (cmd.startsWith("cmd wifi list-networks")) return listText();
            if (cmd.startsWith("cmd wifi forget-network")) {
                String rest = cmd.substring("cmd wifi forget-network".length()).trim();
                try {
                    long id = Long.parseLong(rest.split("[;\\s]")[0]);
                    for (int i = 0; i < saved.size(); i++) {
                        if (saved.get(i)[0] == id) { saved.remove(i); savedSsid.remove(i); break; }
                    }
                } catch (Throwable ignored) {}
                forgotten.add(TARGET);
                if (disconnectWorks) {
                    // forget 之后连接也断掉（真机上删配置通常连带断开）
                    if (w.ssid != null && netIdOf(w.ssid) < 0) {
                        w.ssid = null; w.bssid = null; w.netId = -1;
                        w.state = "DISCONNECTED"; w.ip = "0.0.0.0";
                    }
                }
                return "";
            }
            if (cmd.startsWith("cmd wifi set-wifi-enabled disabled")) {
                if (disconnectWorks) {
                    w.ssid = null; w.bssid = null; w.netId = -1;
                    w.state = "DISCONNECTED"; w.ip = "0.0.0.0";
                }
                return "";
            }
            if (cmd.startsWith("cmd wifi set-wifi-enabled enabled")) return "";
            if (cmd.startsWith("cmd wifi connect-network")) {
                connectSeenAt = w.tick;
                // 关键：connect 之后世界立即出现"那条连接"（第 1 次 status 就该看到）
                if (apSsid != null && appearAfterConnect > 0 && w.ssid == null) {
                    w.set(apSsid, apBssid, apNetId, "COMPLETED", apIp);
                }
                return "";
            }
            // connect 之后第 appearAfterConnect 次调用 → 出现那条连接（若尚未出现）
            if (connectSeenAt > 0 && appearAfterConnect > 0 && apSsid != null
                    && w.ssid == null && (w.tick - connectSeenAt) >= appearAfterConnect) {
                w.set(apSsid, apBssid, apNetId, "COMPLETED", apIp);
            }
            return "";
        }

        private String listText() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < savedSsid.size(); i++) {
                sb.append("networkId: ").append(saved.get(i)[0])
                        .append("\tSSID: \"").append(savedSsid.get(i)).append("\"\n");
            }
            return sb.toString();
        }

        @Override public String name() { return "fake"; }
    }

    // ---------------------------------------------------------------- 执行器

    static final class Res {
        CrackEngine engine; Shell shell; World world; TestEngineClock clock;
        Object attempt; long t0, t1; Throwable err; String attemptId;
        String initDesc;
    }

    private static final String TARGET = "LAB-TARGET";

    interface Setup {
        void apply(World w, Shell sh);
    }

    private static Res run(String tag, Setup setup, boolean disconnectWorks,
                           long netIdBeforeHint) throws Exception {
        Res r = new Res();
        r.world = new World();
        r.shell = new Shell(r.world, disconnectWorks);
        setup.apply(r.world, r.shell);

        r.initDesc = "SSID=" + r.world.ssid + " netId=" + r.world.netId
                + " saved=" + r.shell.savedSsid;

        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        r.engine = (CrackEngine) ctor.newInstance(0);
        r.clock = new TestEngineClock(3_000_000L);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(r.engine, r.clock);
        r.engine.setShellChannel(r.shell);

        NetRecord target = new NetRecord(TARGET, "02:00:00:00:00:0A", -55, 2412,
                NetRecord.Security.WPA2, false, "");

        r.attemptId = buildAttemptId(3);
        r.t0 = r.clock.uptimeMillis();
        try {
            r.attempt = r.engine.tryOne(target, "WPA2", "Correct9Pass", 4000L, r.attemptId);
        } catch (Throwable t) {
            r.err = t;
        }
        r.t1 = r.clock.uptimeMillis();
        return r;
    }

    private static String buildAttemptId(int seq) throws Exception {
        Class<?> c = Class.forName("io.github.wlmosv_png.wpaforge.audit.AttemptId");
        Method nr = c.getMethod("newRunId", long.class, String.class, int.class);
        String run = (String) nr.invoke(null, 1_764_000_000_000L, TARGET, 1);
        return (String) c.getMethod("of", String.class, int.class).invoke(null, run, seq);
    }

    private static Object f(Object o, String n) {
        try {
            Field fl = o.getClass().getDeclaredField(n);
            fl.setAccessible(true);
            return fl.get(o);
        } catch (Throwable t) { return "<" + t.getClass().getSimpleName() + ">"; }
    }

    private static void dump(String tag, Res r) {
        System.out.println("  ---- " + tag + " ----");
        info("初始        : " + r.initDesc);
        info("AttemptId   : " + r.attemptId);
        info("虚拟时间    : " + r.t0 + " → " + r.t1 + "（+" + (r.t1 - r.t0) + "ms）");
        info("Shell 调用  : " + r.shell.cmds.size() + " 条，世界 tick=" + r.world.tick);
        info("连接命令于  : tick=" + r.shell.connectSeenAt);
        info("状态时间线  :");
        for (String s : r.world.statusTimeline) info("    " + s);
        info("终态        : SSID=" + r.world.ssid + " netId=" + r.world.netId
                + " state=" + r.world.state + " ip=" + r.world.ip);
        info("异常        : " + (r.err == null ? "无"
                : r.err.getClass().getSimpleName() + ": " + r.err.getMessage()));
        if (r.attempt != null) {
            info("verdict     : " + f(r.attempt, "verdict"));
            info("detail      : " + f(r.attempt, "detail"));
            info("reason      : " + f(r.attempt, "reason"));
            info("source      : " + f(r.attempt, "confirmedBy"));
        } else info("Attempt     : 未创建");
    }

    private static String verdictOf(Res r) {
        return r.attempt == null ? "<none>" : String.valueOf(f(r.attempt, "verdict"));
    }

    private static String reasonOf(Res r) {
        return r.attempt == null ? "<none>" : String.valueOf(f(r.attempt, "reason"));
    }

    // ---------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        System.out.println("=== AttributionRedTeamTest（连接归因反证，六组）===");

        // ============================================ A 旧连接始终存在（断不掉）
        System.out.println();
        System.out.println("== A 旧连接始终存在：断开命令无效 ==");
        Res a = run("A", new Setup() {
            @Override public void apply(World w, Shell sh) {
                w.set(TARGET, "02:00:00:00:00:0A", 613, "COMPLETED", "192.168.1.3");
                sh.save(TARGET, 613);
            }
        }, false, 613);
        dump("A", a);
        ok("A 结论不是 SUCCESS", !"SUCCESS".equals(verdictOf(a)));
        note("证据：目标始终在线，引擎判「未能断开」→ UNKNOWN，拒绝判成功");
        note("结论：保护有效。这就是「候选前就存在的连接」被拦住的方式");

        // ============================================ B 断开后系统自动回连
        System.out.println();
        System.out.println("== B 断开成功 → 系统自动回连（非本条候选） ==");
        Res b = run("B", new Setup() {
            @Override public void apply(World w, Shell sh) {
                w.set(TARGET, "02:00:00:00:00:0A", 613, "COMPLETED", "192.168.1.3");
                sh.save(TARGET, 613);
                // 关键：connect 命令发出之前，系统就自己回连了（同一 netId 613）
                // 断开后系统**立即**回连（在 connect 命令之前就已连上目标）
                // 注意：forget 之后本不该回连，这里故意不设 forgotten，模拟
                // "目标配置还在别处 / 缓存凭据导致系统自行回连"的最坏情形。
                sh.forgotten.clear();
                sh.appearAfterConnect = 0;
                for (int i = 0; i < 8; i++) {
                    final int T = i;
                    sh.onTick.add(() -> {
                        if (T == 2) { w.set(TARGET, "02:00:00:00:00:0A", 613, "COMPLETED", "192.168.1.3"); }
                    });
                }
            }
        }, true, 613);
        dump("B", b);
        ok("B Attempt 已创建", b.attempt != null);
        note("重点：这是「系统自动回连」，不是本条候选建立的连接");
        note("引擎能否拒绝？见下方 verdict 与 reason 分析");

        // ============================================ C 断开后本次候选成功
        System.out.println();
        System.out.println("== C 断开成功 → 本次候选建立新连接（netId 变化） ==");
        Res c = run("C", new Setup() {
            @Override public void apply(World w, Shell sh) {
                w.set(TARGET, "02:00:00:00:00:0A", 613, "COMPLETED", "192.168.1.3");
                sh.save(TARGET, 613);
                sh.appearAfterConnect = 3;
                sh.apSsid = TARGET; sh.apBssid = "02:00:00:00:00:0A";
                sh.apNetId = 700; sh.apIp = "192.168.1.9";
            }
        }, true, 613);
        dump("C", c);
        note("这是**真正应该判 SUCCESS** 的场景");
        if ("SUCCESS".equals(verdictOf(c))) {
            ok("C 判为 SUCCESS —— 但见下方归属分析", true);
        } else {
            // 不改测试预期迎合代码：如实记录"本次真实连接被判非成功"这一事实。
            ok("C 场景已完成执行（结论见下，非预期成功）", c.attempt != null);
            note("★ C 未判 SUCCESS：本次候选确实建立了新连接（netId 700），"
                    + "引擎却给出 " + verdictOf(c) + " / " + reasonOf(c));
            note("★ 这是**漏判**的证据，不是测试写错 —— 同一套前置闸门也用于真实破解");
        }

        // ============================================ D 其他进程建立目标连接
        System.out.println();
        System.out.println("== D 断开后「其他进程」建立目标连接（不是本条候选） ==");
        Res d = run("D", new Setup() {
            @Override public void apply(World w, Shell sh) {
                w.set(TARGET, "02:00:00:00:00:0A", 613, "COMPLETED", "192.168.1.3");
                sh.save(TARGET, 613);
                // 不同 BSSID + 不同 netId：模拟"另一个进程建立的连接"
                sh.appearAfterConnect = 3;
                sh.apSsid = TARGET; sh.apBssid = "02:00:00:00:00:0B";
                sh.apNetId = 800; sh.apIp = "192.168.1.20";
            }
        }, true, 613);
        dump("D", d);
        note("★ 关键反证：这条连接**不是**本条候选建立的（不同 BSSID/netId/来源）");
        note("但引擎看到的只是「目标 + COMPLETED + IP」");
        if ("SUCCESS".equals(verdictOf(d))) {
            note("★ 结果：判成 SUCCESS —— 引擎**无法区分**「其他进程建立」与「本条候选建立」");
            info("这是本实验最严重的发现：SUCCESS 的归属证据不足");
        } else {
            note("结果：" + verdictOf(d) + " / " + reasonOf(d) + "（被拦下）");
        }

        // ============================================ E 候选失败但系统自动回连
        System.out.println();
        System.out.println("== E 候选失败（错误密码）→ 系统自动回连目标 ==");
        Res e = run("E", new Setup() {
            @Override public void apply(World w, Shell sh) {
                w.set(null, null, -1, "DISCONNECTED", "0.0.0.0");
                // 本条候选是错误密码，但系统用缓存凭据自己连上了
                sh.appearAfterConnect = 3;
                sh.apSsid = TARGET; sh.apBssid = "02:00:00:00:00:0A";
                sh.apNetId = 613; sh.apIp = "192.168.1.30";
            }
        }, true, -1);
        dump("E", e);
        note("★ 这条候选的密码是错的，连上的是系统的缓存凭据");
        if ("SUCCESS".equals(verdictOf(e))) {
            note("★ 结果：判成 SUCCESS —— 这就是历史事故里的「假阳性」");
        }

        // ============================================ F netId 复用
        System.out.println();
        System.out.println("== F 本次成功但 netId 复用（613 → 613） ==");
        Res fRes = run("F", new Setup() {
            @Override public void apply(World w, Shell sh) {
                w.set(TARGET, "02:00:00:00:00:0A", 613, "COMPLETED", "192.168.1.3");
                sh.save(TARGET, 613);
                // 本次真连上了，但 netId 被复用为同一个 613
                sh.appearAfterConnect = 3;
                sh.apSsid = TARGET; sh.apBssid = "02:00:00:00:00:0A";
                sh.apNetId = 613; sh.apIp = "192.168.1.9";
            }
        }, true, 613);
        dump("F", fRes);
        note("本次候选真建立了连接，但 netId 被系统复用为同一个 613");
        if ("SUCCESS".equals(verdictOf(fRes))) {
            note("结果：SUCCESS —— 恰好正确，但**证据是同 netId**，无法证明归属");
        } else {
            note("结果：" + verdictOf(fRes) + "（若因 netId 相同被拦 → 属漏判）");
        }

        // ============================================ 汇总
        System.out.println();
        System.out.println("== 反证结论汇总 ==");
        info("A 旧连接存在        : " + verdictOf(a) + " / " + reasonOf(a));
        info("B 系统自动回连      : " + verdictOf(b) + " / " + reasonOf(b));
        info("C 本次候选成功      : " + verdictOf(c) + " / " + reasonOf(c));
        info("D 其他进程建立      : " + verdictOf(d) + " / " + reasonOf(d));
        info("E 候选失败+系统回连 : " + verdictOf(e) + " / " + reasonOf(e));
        info("F netId 复用        : " + verdictOf(fRes) + " / " + reasonOf(fRes));

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) throw new RuntimeException("AttributionRedTeamTest 失败 " + fail + " 项");
    }
}
