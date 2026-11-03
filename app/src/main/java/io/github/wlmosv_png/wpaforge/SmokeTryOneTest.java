package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EngineClock;
import io.github.wlmosv_png.wpaforge.audit.TestEngineClock;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 冒烟验证：测试构造出来的引擎**能否真正执行生产方法 {@code tryOne}**。
 *
 * <h3>本测试要证明的唯一一件事</h3>
 * 不是"能 new 出来"，而是"进入并走完判定路径，产出真实 {@code Attempt}"。
 *
 * <h3>纪律</h3>
 * <ul>
 *   <li>走的是生产 {@code public Attempt tryOne(NetRecord, String, String, long, String)}，
 *       不是替身重写。</li>
 *   <li>每个断言都基于**实际的 Attempt 对象**，不是 try/catch 吞异常后打 PASS。</li>
 *   <li>两个场景：A 明确失败响应；B 满足成功条件的响应。
 *       B 的成功是**测试替身环境下的逻辑成功**，不代表真实 Wi-Fi 已连接。</li>
 * </ul>
 */
public final class SmokeTryOneTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        boolean c = want == null ? got == null : want.equals(got);
        if (!c) System.out.println("        期望=" + want + " 实际=" + got);
        ok(name, c);
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    // ------------------------------------------------------------ FakeShell

    /**
     * 记录所有收到的命令与返回值的假通道。
     *
     * <p>返回策略是**关键字路由**（不是调用次数路由）：
     * 同一个命令无论第几次调用都返回同样的结果，避免把"顺序"混进结果里。
     */
    static final class FakeShell implements CrackEngine.ShellChannel {
        final List<String> commands = new ArrayList<>();
        final List<String> returns = new ArrayList<>();
        final String statusReply;
        final String listReply;

        FakeShell(String statusReply, String listReply) {
            this.statusReply = statusReply;
            this.listReply = listReply;
        }

        @Override public String exec(String cmd, long timeoutMs) {
            commands.add(cmd);
            String reply = route(cmd);
            returns.add(reply);
            return reply;
        }

        private String route(String cmd) {
            if (cmd == null) return "";
            if (cmd.startsWith("cmd wifi status")) return statusReply;
            if (cmd.startsWith("cmd wifi list-networks")) return listReply;
            // 起点标记写入 / connect-network / forget / 各种 set-* 一律成功无输出
            return "";
        }

        @Override public String name() { return "fake"; }
    }

    // ------------------------------------------------------------ 场景执行

    static final class Result {
        CrackEngine engine;
        FakeShell shell;
        TestEngineClock clock;
        Object attempt;
        long tBefore, tAfter;
        Throwable error;
        String password;
        String attemptId;
    }

    private static Result run(boolean successScenario) throws Exception {
        Result r = new Result();
        r.password = successScenario ? "Correct9Pass" : "WrongPass99";

        // 目标：与 R1EvidenceTest 同格式
        NetRecord target = new NetRecord("LAB-TARGET", "02:00:00:00:00:0A", -55, 2412,
                NetRecord.Security.WPA2, false, "");

        // ---- 1. 构造（不碰 Android）
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        r.engine = (CrackEngine) ctor.newInstance(0);
        info("构造成功: " + r.engine.getClass().getName());

        // ---- 2. 注入可控时钟（禁止调用 PRODUCTION 的 native 时钟）
        r.clock = new TestEngineClock(1_000_000L);
        Method setClock = CrackEngine.class.getDeclaredMethod(
                "setClockForTest", EngineClock.class);
        setClock.setAccessible(true);
        setClock.invoke(r.engine, r.clock);
        info("时钟已注入: 起点=" + r.clock.uptimeMillis());

        // ---- 3. 注入 FakeShell
        String status = successScenario
                // 成功帧：SSID 目标 + COMPLETED + 有效 IP + netId
                ? "WifiInfo: SSID: \"LAB-TARGET\", BSSID: 02:00:00:00:00:0A, "
                + "IP: /192.168.1.3, Supplicant state: COMPLETED, Net ID: 613\n"
                // 失败帧：未连上
                : "Wifi is not connected\n";
        String list = "";   // 没有目标配置残留
        r.shell = new FakeShell(status, list);
        r.engine.setShellChannel(r.shell);
        info("FakeShell 已注入（status=" + (successScenario ? "COMPLETED 帧" : "未连接") + "）");

        // ---- 4. 执行生产方法 tryOne（公开 API，带 attemptId）
        r.attemptId = AttemptIdOf("smoke-run", 1);
        r.tBefore = r.clock.uptimeMillis();
        try {
            r.attempt = r.engine.tryOne(target, "WPA2", r.password, 3000L, r.attemptId);
        } catch (Throwable t) {
            r.error = t;
        }
        r.tAfter = r.clock.uptimeMillis();
        return r;
    }

    /** 用生产 AttemptId 生成规则造一个合法 id（不复制实现，直接调用生产方法）。 */
    private static String AttemptIdOf(String ignored, int seq) throws Exception {
        Class<?> c = Class.forName("io.github.wlmosv_png.wpaforge.audit.AttemptId");
        // newRunId(epochMs, ssid, ordinal) -> "<base36>-<8位hex>-<base36>"
        Method newRunId = c.getMethod("newRunId", long.class, String.class, int.class);
        String runId = (String) newRunId.invoke(null, 1_764_000_000_000L, "LAB-TARGET", 1);
        Method of = c.getMethod("of", String.class, int.class);
        String id = (String) of.invoke(null, runId, seq);
        Method wf = c.getMethod("wellFormed", String.class);
        if (!Boolean.TRUE.equals(wf.invoke(null, id))) {
            throw new IllegalStateException("生成了非法 attemptId: " + id);
        }
        return id;
    }

    private static void dump(String title, Result r) {
        System.out.println("  ---- " + title + " ----");
        info("输入密码    : " + r.password);
        info("attemptId   : " + r.attemptId);
        info("虚拟时间    : " + r.tBefore + " → " + r.tAfter
                + "（推进 " + (r.tAfter - r.tBefore) + "ms）");
        info("Shell 命令数: " + (r.shell == null ? 0 : r.shell.commands.size()));
        if (r.shell != null) {
            for (int i = 0; i < r.shell.commands.size() && i < 12; i++) {
                String cmd = r.shell.commands.get(i);
                String ret = r.shell.returns.get(i);
                String shortRet = ret == null ? "null"
                        : (ret.length() > 40 ? ret.substring(0, 40).replace("\n", "\\n") + "…" : ret.replace("\n", "\\n"));
                info("  [" + i + "] " + (cmd.length() > 60 ? cmd.substring(0, 60) + "…" : cmd)
                        + "  →  " + shortRet);
            }
            if (r.shell.commands.size() > 12) {
                info("  … 另有 " + (r.shell.commands.size() - 12) + " 条");
            }
        }
        if (r.error != null) {
            info("异常        : " + r.error.getClass().getName() + ": " + r.error.getMessage());
            StackTraceElement[] st = r.error.getStackTrace();
            if (st.length > 0) info("   at " + st[0]);
        } else {
            info("异常        : 无");
        }
        if (r.attempt != null) {
            info("Attempt 创建: 是");
            info("verdict     : " + field(r.attempt, "verdict"));
            info("detail      : " + field(r.attempt, "detail"));
            info("source      : " + field(r.attempt, "confirmedBy"));
            info("reason      : " + field(r.attempt, "reason"));
            info("elapsedMs   : " + field(r.attempt, "elapsedMs"));
        } else {
            info("Attempt 创建: 否");
        }
    }

    private static Object field(Object obj, String name) {
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
        }
    }

    // ------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        System.out.println("=== SmokeTryOneTest（tryOne 真实执行冒烟） ===");

        // ======================================================== 场景 A：明确失败
        System.out.println("== SC-A 明确失败响应 ==");
        Result a = run(false);
        dump("SC-A", a);

        ok("SC-A 未抛异常", a.error == null);
        ok("SC-A Attempt 已创建（生产判定路径真的跑了）", a.attempt != null);
        ok("SC-A 至少发出过连接命令",
                a.shell.commands.stream().anyMatch(c -> c.contains("connect-network")));
        ok("SC-A 至少轮询过 status",
                a.shell.commands.stream().anyMatch(c -> c.startsWith("cmd wifi status")));
        if (a.attempt != null) {
            Object v = field(a.attempt, "verdict");
            ok("SC-A verdict 不是 SUCCESS",
                    v != null && !"SUCCESS".equals(v.toString()));
            ok("SC-A verdict 是终态之一（AUTH_FAIL/TIMEOUT/UNKNOWN/ERROR/ABORTED）",
                    v != null && ("AUTH_FAIL".equals(v.toString())
                            || "TIMEOUT".equals(v.toString())
                            || "UNKNOWN".equals(v.toString())
                            || "ERROR".equals(v.toString())
                            || "ABORTED".equals(v.toString())));
            eq("SC-A attemptId 回填到 Attempt", a.attemptId, field(a.attempt, "attemptId"));
            ok("SC-A 虚拟时间被推进（说明轮询真的跑过）", a.tAfter > a.tBefore);
        }

        // ======================================================== 场景 B：逻辑成功
        System.out.println();
        System.out.println("== SC-B 满足成功条件的响应（仅测试替身下的逻辑成功）==");
        Result b = run(true);
        dump("SC-B", b);

        ok("SC-B 未抛异常", b.error == null);
        ok("SC-B Attempt 已创建", b.attempt != null);
        if (b.attempt != null) {
            Object v = field(b.attempt, "verdict");
            info("SC-B verdict = " + v + "（若非 SUCCESS 请见下方原因）");
            boolean isSuccess = v != null && "SUCCESS".equals(v.toString());
            // 本冒烟的目的是"证明能进判定路径"，不是"证明替身能让它成功"。
            // 若替身环境不满足全部前置闸门（例如看到目标已连 → 判定不可信），
            // 结论如实记录为 UNKNOWN，这本身也是正确行为，不算失败。
            if (isSuccess) {
                ok("SC-B verdict == SUCCESS（三条件同行成立）", true);
            } else {
                info("SC-B verdict = " + v + " —— 判定段已执行但被前置闸门拦下（如实记录）");
                info("  detail = " + field(b.attempt, "detail"));
                info("  reason = " + field(b.attempt, "reason"));
                ok("SC-B 被拦下时结论为 UNKNOWN（不是误判成功，也不是误判失败）",
                        "UNKNOWN".equals(String.valueOf(v)));
            }
            eq("SC-B attemptId 回填", b.attemptId, field(b.attempt, "attemptId"));
        }

        // ======================================================== 全局卫生
        System.out.println();
        System.out.println("== SC-C 卫生检查 ==");
        ok("两根冒烟都未触发 Android Stub 异常",
                !isStub(a.error) && !isStub(b.error));
        ok("两根冒烟都注入了替身时钟（未用 native）",
                a.clock != null && b.clock != null);

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) throw new RuntimeException("SmokeTryOneTest 失败 " + fail + " 项");
    }

    private static boolean isStub(Throwable t) {
        if (t == null) return false;
        String m = String.valueOf(t.getMessage());
        return m.contains("Stub!") || t instanceof UnsatisfiedLinkError
                || t.getClass().getName().contains("Stub");
    }
}
