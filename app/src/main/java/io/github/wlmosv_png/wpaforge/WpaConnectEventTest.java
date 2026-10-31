package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AttemptId;
import io.github.wlmosv_png.wpaforge.audit.AttemptMarker;
import io.github.wlmosv_png.wpaforge.audit.WpaLogReader;

import java.util.List;

/**
 * R1 + R3 的解析器测试。
 *
 * <h3>R1：netId 解析</h3>
 * {@code CTRL-EVENT-CONNECTED ... completed [id=N]} 里的 N 必须被完整保存；
 * 任何异常格式都落到 {@link WpaLogReader#NETID_UNKNOWN}，绝不抛异常、绝不用 0 冒充未知。
 *
 * <h3>R3：CONNECTED 独立 kind</h3>
 * 与 {@code negotiated} 分开，但**判定上仍属成功证据**（{@code sawNegotiatedFor} 同时认）。
 *
 * <h3>纪律</h3>
 * 每个场景都有断言，不用 note() 代替 assert。
 * 本测试**不验证任何归属语义** —— netId 只是数据，不是归属证明。
 */
public final class WpaConnectEventTest {

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

    private static final String BSSID = "28:93:7d:11:22:01";

    /** 造一行真机格式的 wpa_supplicant 日志。 */
    private static String wpa(String body) {
        return "10-31 12:00:00.000 28272 28272 I wpa_supplicant: wlan0: " + body + "\n";
    }

    private static String conn(String bssid, String tail) {
        return wpa("CTRL-EVENT-CONNECTED - Connection to " + bssid + " completed" + tail);
    }

    private static WpaLogReader.LogEvent only(String text) {
        List<WpaLogReader.LogEvent> ev = WpaLogReader.parse(text, 123L);
        return ev.size() == 1 ? ev.get(0) : null;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== WpaConnectEventTest（R1 netId + R3 独立 kind）===");

        // ============================================================ R1 正常
        System.out.println();
        System.out.println("== R1-1 真机样本（id=0）==");
        String real = conn(BSSID, " [id=0 id_str=%7B%22configKey%22%3A%22%5C%22B%22%7D]");
        WpaLogReader.LogEvent e1 = only(real);
        ok("R1-1a 解析出 1 条事件", e1 != null);
        eq("R1-1b kind = connected", WpaLogReader.KIND_CONNECTED, e1 == null ? null : e1.kind);
        eq("R1-1c bssid 正确", BSSID.toLowerCase(), e1 == null ? null : e1.bssid.toLowerCase());
        eq("R1-1d netId = 0（0 是合法值，不能被当成未知）", 0L, e1 == null ? null : e1.netId);
        ok("R1-1e hasNetId() = true", e1 != null && e1.hasNetId());
        ok("R1-1f raw 原文完整保留", e1 != null && e1.raw.contains("id_str="));

        System.out.println();
        System.out.println("== R1-2 普通 id ==");
        WpaLogReader.LogEvent e2 = only(conn(BSSID, " [id=613 id_str=x]"));
        eq("R1-2 netId = 613", 613L, e2 == null ? null : e2.netId);

        System.out.println();
        System.out.println("== R1-3 缺少 id ==");
        WpaLogReader.LogEvent e3 = only(conn(BSSID, ""));
        eq("R1-3a netId = NETID_UNKNOWN(-1)", WpaLogReader.NETID_UNKNOWN,
                e3 == null ? null : e3.netId);
        ok("R1-3b hasNetId() = false", e3 != null && !e3.hasNetId());
        ok("R1-3c 事件本身仍被识别", e3 != null);

        System.out.println();
        System.out.println("== R1-4 非数字 id（不得崩溃）==");
        WpaLogReader.LogEvent e4 = only(conn(BSSID, " [id=abc id_str=x]"));
        eq("R1-4 netId 退回 UNKNOWN", WpaLogReader.NETID_UNKNOWN, e4 == null ? null : e4.netId);
        ok("R1-4b 事件仍被识别（不因 id 异常而丢弃）", e4 != null);

        System.out.println();
        System.out.println("== R1-5 超大数字（溢出）==");
        WpaLogReader.LogEvent e5 = only(conn(BSSID, " [id=99999999999999999999999 id_str=x]"));
        eq("R1-5 netId 退回 UNKNOWN", WpaLogReader.NETID_UNKNOWN, e5 == null ? null : e5.netId);

        System.out.println();
        System.out.println("== R1-6 负数 id ==");
        WpaLogReader.LogEvent e6 = only(conn(BSSID, " [id=-5 id_str=x]"));
        eq("R1-6 负数一律视为未知", WpaLogReader.NETID_UNKNOWN, e6 == null ? null : e6.netId);

        System.out.println();
        System.out.println("== R1-7 多个 id 字段（取第一个）==");
        WpaLogReader.LogEvent e7 = only(conn(BSSID, " [id=12 id=34 id_str=x]"));
        eq("R1-7 取第一个 id=12", 12L, e7 == null ? null : e7.netId);

        System.out.println();
        System.out.println("== R1-8 不从 id_str 推测（id_str 里有数字也不行）==");
        WpaLogReader.LogEvent e8 = only(conn(BSSID, " [id_str=%7B%22configKey%22%3A%22613%22%7D]"));
        eq("R1-8 只有 id_str 无 id → UNKNOWN", WpaLogReader.NETID_UNKNOWN,
                e8 == null ? null : e8.netId);

        // ============================================================ R1 兼容性
        System.out.println();
        System.out.println("== R1-9 旧构造入口兼容 ==");
        WpaLogReader.LogEvent old3 = newLegacy3();
        eq("R1-9a 3 参构造 netId = UNKNOWN", WpaLogReader.NETID_UNKNOWN,
                old3 == null ? null : old3.netId);
        WpaLogReader.LogEvent old5 = newLegacy5();
        eq("R1-9b 5 参构造 netId = UNKNOWN", WpaLogReader.NETID_UNKNOWN,
                old5 == null ? null : old5.netId);

        // ============================================================ R3 kind 分类
        System.out.println();
        System.out.println("== R3-1 CONNECTED 与 NEGOTIATED 分开 ==");
        List<WpaLogReader.LogEvent> mix = WpaLogReader.parse(
                conn(BSSID, " [id=7 id_str=x]")
                        + wpa("WPA: Key negotiation completed with " + BSSID)
                        + wpa("Trying to associate with SSID 'LAB-TARGET'"), 0L);
        eq("R3-1a 共 3 条事件", 3, mix.size());
        int nConn = 0, nNeg = 0, nAssoc = 0;
        for (WpaLogReader.LogEvent e : mix) {
            if (WpaLogReader.KIND_CONNECTED.equals(e.kind)) nConn++;
            else if (WpaLogReader.KIND_NEGOTIATED.equals(e.kind)) nNeg++;
            else if (WpaLogReader.KIND_ASSOCIATE.equals(e.kind)) nAssoc++;
        }
        eq("R3-1b CONNECTED 单独计数 = 1", 1, nConn);
        eq("R3-1c negotiated 单独计数 = 1", 1, nNeg);
        eq("R3-1d associate 单独计数 = 1", 1, nAssoc);

        System.out.println();
        System.out.println("== R3-2 negotiated 语义不变 ==");
        ok("R3-2a isSuccessEvidenceKind(negotiated) = true",
                WpaLogReader.isSuccessEvidenceKind(WpaLogReader.KIND_NEGOTIATED));
        ok("R3-2b isSuccessEvidenceKind(connected) = true",
                WpaLogReader.isSuccessEvidenceKind(WpaLogReader.KIND_CONNECTED));
        ok("R3-2c isSuccessEvidenceKind(wrong_key) = false",
                !WpaLogReader.isSuccessEvidenceKind(WpaLogReader.KIND_WRONG_KEY));
        ok("R3-2d isSuccessEvidenceKind(handshake_failed) = false",
                !WpaLogReader.isSuccessEvidenceKind(WpaLogReader.KIND_HANDSHAKE_FAILED));
        ok("R3-2e isSuccessEvidenceKind(null) = false",
                !WpaLogReader.isSuccessEvidenceKind(null));

        System.out.println();
        System.out.println("== R3-3 sawNegotiatedFor 同时认两种 kind（判定行为不变）==");
        String onlyConn = conn(BSSID, " [id=7 id_str=x]");
        String onlyNeg = wpa("WPA: Key negotiation completed with " + BSSID);
        ok("R3-3a 只有 CONNECTED 时仍命中", WpaLogReader.sawNegotiatedFor(onlyConn, BSSID));
        ok("R3-3b 只有 negotiated 时仍命中", WpaLogReader.sawNegotiatedFor(onlyNeg, BSSID));
        ok("R3-3c 别的 BSSID 不命中",
                !WpaLogReader.sawNegotiatedFor(onlyConn, "28:93:7d:11:22:99"));

        // ============================================================ A~D 场景
        System.out.println();
        System.out.println("== A 正常 CONNECTED + id + BSSID ==");
        ok("A 有 bssid 且有 netId", e1 != null && e1.bssid != null && e1.hasNetId());

        System.out.println("== B CONNECTED + id 缺失 ==");
        ok("B 有 bssid 但 netId 未知", e3 != null && e3.bssid != null && !e3.hasNetId());

        System.out.println("== C CONNECTED + BSSID 缺失 ==");
        String noBssid = wpa("CTRL-EVENT-CONNECTED - Connection to  completed [id=5 id_str=x]");
        ok("C 无 BSSID 的行不被识别为事件", WpaLogReader.parse(noBssid, 0L).isEmpty());

        System.out.println("== D CONNECTED + 异常 id ==");
        ok("D 异常 id 不崩溃且事件保留", e4 != null && e4.netId == WpaLogReader.NETID_UNKNOWN);

        // ============================================================ E~H
        System.out.println();
        System.out.println("== E ASSOCIATED / NEGOTIATED 不得误判为 CONNECTED ==");
        ok("E-1 associate 不是 connected",
                !WpaLogReader.KIND_CONNECTED.equals(only(
                        wpa("Trying to associate with SSID 'X'")).kind));
        ok("E-2 negotiated 不是 connected",
                !WpaLogReader.KIND_CONNECTED.equals(only(
                        wpa("WPA: Key negotiation completed with " + BSSID)).kind));

        System.out.println();
        System.out.println("== F 原有握手失败判定不回归 ==");
        String failLog = wpa("WPA: 4-Way Handshake failed - pre-shared key may be incorrect");
        ok("F-1 sawHandshakeFailure 仍为 true", WpaLogReader.sawHandshakeFailure(failLog));
        String wrongKey = wpa("CTRL-EVENT-SSID-TEMP-DISABLED ssid=\"LAB-TARGET\" id=7 reason=WRONG_KEY");
        ok("F-2 sawWrongKeyFor 仍为 true",
                WpaLogReader.sawWrongKeyFor(wrongKey, "LAB-TARGET"));
        ok("F-3 wrong_key 事件正常解析",
                !WpaLogReader.parse(wrongKey, 0L).isEmpty());

        System.out.println();
        System.out.println("== G 标记前后切片不回归 ==");
        String a1 = AttemptId.of(AttemptId.newRunId(1_764_000_000_000L, "LAB-TARGET", 1), 1);
        String withMarker = "10-31 12:00:00.000  1  1 I " + AttemptMarker.TAG + ": "
                + AttemptMarker.FIELD + a1 + "\n" + conn(BSSID, " [id=9 id_str=x]");
        String slice = AttemptMarker.sliceAfter(withMarker, a1);
        ok("G-1 切片含 CONNECTED", slice.contains("CTRL-EVENT-CONNECTED"));
        ok("G-2 切片不含标记行", !slice.contains(AttemptMarker.FIELD + a1));
        ok("G-3 切片里 sawNegotiatedFor 命中", WpaLogReader.sawNegotiatedFor(slice, BSSID));
        String before = conn(BSSID, " [id=9 id_str=x]")
                + "10-31 12:00:01.000  1  1 I " + AttemptMarker.TAG + ": "
                + AttemptMarker.FIELD + a1 + "\n";
        ok("G-4 标记之前的 CONNECTED 被切掉",
                !AttemptMarker.sliceAfter(before, a1).contains("CTRL-EVENT-CONNECTED"));

        System.out.println();
        System.out.println("== H SHELL_FILTER 保留新增事件 ==");
        ok("H-1 FILTER 含 CONNECTED", WpaLogReader.SHELL_FILTER.contains("CONNECTED"));
        ok("H-2 CONNECTED 行能穿过过滤式", passesFilter(onlyConn));
        ok("H-3 带 id 的 CONNECTED 行能穿过过滤式",
                passesFilter(conn(BSSID, " [id=613 id_str=x]")));

        // ============================================================ 声明
        System.out.println();
        System.out.println("== 边界声明 ==");
        System.out.println("  ·    netId 仅作数据保存与展示，不参与任何归属或成功判定");
        System.out.println("  ·    未修改 SuccessRule / classifyDeadline / CrackEngine 判定");
        ok("Z-1 SuccessRule 未变（源码守卫）", successRuleUnchanged());
        ok("Z-2 CrackEngine 判定段未变（源码守卫）", crackEngineVerdictUnchanged());

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) throw new RuntimeException("WpaConnectEventTest 失败 " + fail + " 项");
    }

    // ---------------------------------------------------------------- 辅助

    private static WpaLogReader.LogEvent newLegacy3() {
        try {
            java.lang.reflect.Constructor<WpaLogReader.LogEvent> c =
                    WpaLogReader.LogEvent.class.getDeclaredConstructor(
                            long.class, String.class, String.class, String.class);
            c.setAccessible(true);
            return c.newInstance(0L, "S", "associate", "raw");
        } catch (Throwable t) {
            return null;
        }
    }

    private static WpaLogReader.LogEvent newLegacy5() {
        try {
            java.lang.reflect.Constructor<WpaLogReader.LogEvent> c =
                    WpaLogReader.LogEvent.class.getDeclaredConstructor(
                            long.class, String.class, String.class, String.class, String.class);
            c.setAccessible(true);
            return c.newInstance(0L, "S", "AA:BB:CC:DD:EE:FF", "negotiated", "raw");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 与 WpaFilterTest 相同的口径：行里必须命中过滤式的某一项。 */
    private static boolean passesFilter(String line) {
        for (String key : WpaLogReader.SHELL_FILTER.split("\\|")) {
            if (!key.isEmpty() && line.contains(key)) return true;
        }
        return false;
    }

    private static boolean successRuleUnchanged() {
        try {
            String src = new String(java.nio.file.Files.readAllBytes(
                    new java.io.File(TestPaths.javaPkgDir() + "audit/SuccessRule.java").toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            return src.contains("if (targetSsid == null || targetSsid.isEmpty()) return false;")
                    && src.contains("\"COMPLETED\".equalsIgnoreCase(supplicantState)")
                    && src.contains("\"0.0.0.0\".equals(ip)");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean crackEngineVerdictUnchanged() {
        try {
            String src = new String(java.nio.file.Files.readAllBytes(
                    new java.io.File(TestPaths.javaPkgDir() + "audit/CrackEngine.java").toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            return src.contains("SuccessRule.assess(target.ssid, info.ssid,")
                    && src.contains("if (onTargetNow) return new DeadlineOutcome(Verdict.UNKNOWN,");
        } catch (Throwable t) {
            return false;
        }
    }
}
