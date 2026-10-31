package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.LogcatCmd;
import io.github.wlmosv_png.wpaforge.audit.WpaLogReader;

import java.util.List;

/**
 * P0 审计：确认生产解析链**真的**把 {@code [id=N]} 填进了 {@code LogEvent.netId}。
 *
 * <h3>为什么要专门测它</h3>
 * R1 报告里同时出现了两句看似矛盾的话：
 * <ol>
 *   <li>"7 处 production {@code new LogEvent(...)} 全部零改动（走旧构造）"</li>
 *   <li>"真实 CONNECTED 日志能解析出 netId=0"</li>
 * </ol>
 * 真相是：**旧构造调用的是 {code parse()} 内部调用的是 6 参构造**。
 * 那 7 处指的旧构造保留着、没被删，且**不等于**解析器还在用它。
 * 本测试用**完整生产入口**（{@code readEx} → {@code LogcatCmd.parse} → {@code parse}）
 * 走一遍，断言 netId 真的被填上 —— 而不是直接调 {@code parseNetId} 或 6 参构造。
 */
public final class WpaNetIdPipelineTest {

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

    /** 只记录收到的命令、返回预置文本的假 shell —— 模拟 logcat 原始输出。 */
    static final class StubShell implements CrackEngine.ShellChannel {
        final String reply;
        String lastCmd;
        StubShell(String reply) { this.reply = reply; }
        @Override public String exec(String cmd, long timeoutMs) {
            lastCmd = cmd;
            return reply;
        }
        @Override public String name() { return "stub"; }
    }

    /**
     * 一条真机格式的 logcat 行（含 logcat 自身的 "时间 pid tid 级别 tag:" 前缀）。
     * {@code LogcatCmd.parse} 期望的是 shell 输出，所以还要包一层退出码哨兵。
     */
    private static String logcatLine(String body) {
        return "10-31 12:00:00.000 28272 28272 I wpa_supplicant: wlan0: " + body + "\n";
    }

    /**
     * 走**完整生产链**：{@code WpaLogReader.readEx()} → {@code LogcatCmd.parse()}
     * → {@code WpaLogReader.parse()}。
     *
     * <p>不直接调 {@code parse()}，也不手动构造 {@code LogEvent} ——
     * 这样若生产链某一步丢了 netId，本测试必然红。
     */
    private static List<WpaLogReader.LogEvent> viaProductionPipeline(String rawLogcat) {
        // readEx 期望 shell 输出里带两枚退出码哨兵（LogcatCmd.wrap 加的）
        String withSentinel = rawLogcat
                + LogcatCmd.RC + "0\n"
                + LogcatCmd.RC_GREP + "0\n";
        StubShell shell = new StubShell(withSentinel);
        LogcatCmd.Result r = WpaLogReader.readEx(shell, 400, 0L);
        if (r == null || r.body == null) return java.util.Collections.emptyList();
        return WpaLogReader.parse(r.body, 0L);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== WpaNetIdPipelineTest（生产解析链真的填 netId 吗）===");

        // ============================================================ 1. id=0
        System.out.println();
        System.out.println("== P1 真机样本 id=0（走完整生产链）==");
        String raw0 = logcatLine(
                "CTRL-EVENT-CONNECTED - Connection to 28:93:7d:11:22:01 completed "
                        + "[id=0 id_str=%7B%22configKey%22%3A%22%5C%22B%22%7D]");
        List<WpaLogReader.LogEvent> ev0 = viaProductionPipeline(raw0);
        eq("P1-a 事件数 = 1（只有预期事件）", 1, ev0.size());
        if (ev0.size() == 1) {
            WpaLogReader.LogEvent e = ev0.get(0);
            eq("P1-b kind = connected", WpaLogReader.KIND_CONNECTED, e.kind);
            eq("P1-c bssid 正确", "28:93:7d:11:22:01", e.bssid.toLowerCase());
            eq("P1-d netId = 0", 0L, e.netId);
            ok("P1-e hasNetId() = true", e.hasNetId());
            ok("P1-f raw 原文完整保留", e.raw.contains("id_str=")
                    && e.raw.contains("CTRL-EVENT-CONNECTED"));
            System.out.println("       raw = " + e.raw.trim());
        }

        // ============================================================ 2. id=613
        System.out.println();
        System.out.println("== P2 普通 id=613（走完整生产链）==");
        String raw613 = logcatLine(
                "CTRL-EVENT-CONNECTED - Connection to 28:93:7d:11:22:01 completed "
                        + "[id=613 id_str=%7B%22configKey%22%3A%22%5C%22X%22%7D]");
        List<WpaLogReader.LogEvent> ev613 = viaProductionPipeline(raw613);
        eq("P2-a 事件数 = 1", 1, ev613.size());
        if (ev613.size() == 1) {
            eq("P2-b netId = 613", 613L, ev613.get(0).netId);
            ok("P2-c hasNetId() = true", ev613.get(0).hasNetId());
            eq("P2-d kind = connected", WpaLogReader.KIND_CONNECTED, ev613.get(0).kind);
        }

        // ============================================================ 3. 无 id
        System.out.println();
        System.out.println("== P3 无 id 字段（走完整生产链）==");
        String rawNoId = logcatLine(
                "CTRL-EVENT-CONNECTED - Connection to 28:93:7d:11:22:01 completed");
        List<WpaLogReader.LogEvent> evNo = viaProductionPipeline(rawNoId);
        eq("P3-a 事件数 = 1", 1, evNo.size());
        if (evNo.size() == 1) {
            eq("P3-b netId = -1（未知）", WpaLogReader.NETID_UNKNOWN, evNo.get(0).netId);
            ok("P3-c hasNetId() = false", !evNo.get(0).hasNetId());
        }

        // ============================================================ 4. 混合多种事件
        System.out.println();
        System.out.println("== P4 混合日志（associate + negotiated + connected 带 id）==");
        String rawMix = logcatLine("Trying to associate with SSID 'LAB-TARGET'")
                + logcatLine("WPA: Key negotiation completed with 28:93:7d:11:22:01")
                + logcatLine("CTRL-EVENT-CONNECTED - Connection to 28:93:7d:11:22:01 completed [id=42 id_str=x]");
        List<WpaLogReader.LogEvent> evMix = viaProductionPipeline(rawMix);
        eq("P4-a 事件数 = 3", 3, evMix.size());
        int kinds = 0;
        long foundNetId = -999;
        boolean hasAssoc = false, hasNeg = false, hasConn = false;
        for (WpaLogReader.LogEvent e : evMix) {
            if (WpaLogReader.KIND_ASSOCIATE.equals(e.kind)) hasAssoc = true;
            if (WpaLogReader.KIND_NEGOTIATED.equals(e.kind)) {
                hasNeg = true;
                eq("P4-b negotiated 的 netId 未知（不该有 id）",
                        WpaLogReader.NETID_UNKNOWN, e.netId);
            }
            if (WpaLogReader.KIND_CONNECTED.equals(e.kind)) {
                hasConn = true;
                foundNetId = e.netId;
            }
        }
        ok("P4-c 三种 kind 都在", hasAssoc && hasNeg && hasConn);
        eq("P4-d CONNECTED 的 netId = 42", 42L, foundNetId);

        // ============================================================ 5. 源码守卫
        System.out.println();
        System.out.println("== P5 源码守卫：生产 parse() 走的是 6 参构造 ==");
        String src = new String(java.nio.file.Files.readAllBytes(
                new java.io.File(TestPaths.javaPkgDir() + "audit/WpaLogReader.java").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        ok("P5-a parse() 内有 parseNetId( 调用",
                src.contains("parseNetId(connId)"));
        ok("P5-b CONNECTED 分支用 6 参构造",
                src.contains("KIND_CONNECTED, line, parseNetId(connId)"));
        System.out.println("  ·    说明：「7 处生产构造零改动」指的是"
                + "**旧构造函数保留、未被删除**；");
        System.out.println("  ·    不等于「解析器还在调用旧构造」。"
                + "CONNECTED 分支早已改用 6 参构造。");

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) throw new RuntimeException("WpaNetIdPipelineTest 失败 " + fail + " 项");
    }
}
