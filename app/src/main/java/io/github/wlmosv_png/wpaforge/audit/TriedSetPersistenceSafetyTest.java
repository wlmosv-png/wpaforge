package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * TriedSet 写入安全门禁（2026-11-21）。
 *
 * <p><b>重建声明</b>：源码已丢失，按保留的编译产物重新编写，<b>不是</b>原始源码。
 *
 * <h3>必须仍然成立的三条硬约束</h3>
 * <ol>
 *   <li><b>只有 DIRECT 才能写 TriedSet</b>（{@code EvidenceGrade.persistable}）。</li>
 *   <li><b>SUCCESS 永远不写 TriedSet</b> —— 它证明候选是对的。</li>
 *   <li><b>新加的严格档不会误伤正确密码</b>：前置闸门真正确认干净时，
 *       即使 netId 取不到，正确密码仍必须判 SUCCESS + DIRECT。</li>
 * </ol>
 */
public final class TriedSetPersistenceSafetyTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void ok(String name, boolean cond) { ok(name, cond, ""); }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-TRIED";
    static final String BSSID = "02:00:00:00:00:11";

    /** 最小可用假壳：前置闸门干净（列表可判定、无目标），命令后目标连上。 */
    static final class Shell implements CrackEngine.ShellChannel {
        String cleanList = "Network Id      SSID            Security type\n"
                + "700          OTHER           wpa2-psk\n";
        String before = "Wifi is not connected\n";
        String after;
        boolean seen = false;
        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd == null) return "";
            if (cmd.contains("connect-network")) { seen = true; return ""; }
            if (cmd.startsWith("cmd wifi status")) return seen ? after : before;
            if (cmd.startsWith("cmd wifi list-networks")) return cleanList;
            if (cmd.startsWith("cmd wifi forget-network")) return "";
            if (cmd.contains("logcat")) {
                return LogcatCmd.RC + "0\n" + LogcatCmd.RC_GREP + "0\n";
            }
            return "";
        }
        @Override public String name() { return "tried-fake"; }
    }

    private static CrackEngine.Attempt run(Shell sh, String pw) throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);
        Method bypass = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        bypass.setAccessible(true);
        bypass.invoke(e);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(2_000_000L, 1000L, 0L));
        e.setShellChannel(sh);
        String aid = AttemptId.of(AttemptId.newRunId(1_764_000_000_000L, TARGET, 1), 1);
        return e.tryOne(new NetRecord(TARGET, BSSID, -55, 2412,
                NetRecord.Security.WPA2, false, ""), "WPA2", pw, 1200L, aid);
    }

    public static void main(String[] args) {
        System.out.println("== TriedSet 持久化安全 ==");
        try {
            // ------------------------------------------------ 1. persistable 契约
            System.out.println();
            System.out.println("-- 1. persistable 只有 DIRECT --");
            ok("DIRECT 可持久化", EvidenceGrade.persistable(EvidenceGrade.Level.DIRECT));
            ok("INFERRED 不可持久化",
                    !EvidenceGrade.persistable(EvidenceGrade.Level.INFERRED));
            ok("NONE 不可持久化", !EvidenceGrade.persistable(EvidenceGrade.Level.NONE));
            ok("advancesCursor 只有 DIRECT",
                    EvidenceGrade.advancesCursor(EvidenceGrade.Level.DIRECT)
                            && !EvidenceGrade.advancesCursor(EvidenceGrade.Level.INFERRED)
                            && !EvidenceGrade.advancesCursor(EvidenceGrade.Level.NONE));

            // ------------------------------------------------ 2. SUCCESS 不写
            System.out.println();
            System.out.println("-- 2. SUCCESS 不得进 TriedSet（它是正确密码）--");
            {
                ok("SUCCESS 的默认等级是 DIRECT（所以必须显式排除）",
                        EvidenceGrade.defaultOf(CrackEngine.Verdict.SUCCESS)
                                == EvidenceGrade.Level.DIRECT);
                String src = readSrc("CrackService.java");
                ok("★ 生产代码显式排除 SUCCESS 写入 TriedSet",
                        src.contains("a.verdict == CrackEngine.Verdict.SUCCESS")
                                && src.contains("明确不写"),
                        "未找到排除分支");
                ok("★ 写 TriedSet 的唯一门槛是 persistable(a.grade)",
                        src.contains("EvidenceGrade.persistable(a.grade)"));
            }

            // ------------------------------------------------ 3. 不误伤正确密码
            System.out.println();
            System.out.println("-- 3. ★ 前置闸门干净 + netId 缺失 → 正确密码仍必须 SUCCESS --");
            {
                Shell sh = new Shell();
                sh.after = "Wifi is connected to \"" + TARGET + "\"\n"
                        + "WifiInfo: SSID: \"" + TARGET + "\", BSSID: " + BSSID
                        + ", IP: /192.168.1.9, Supplicant state: COMPLETED\n";
                CrackEngine.Attempt a = run(sh, "RightPass123");
                info("verdict=" + a.verdict + " grade=" + a.grade + " by=" + a.confirmedBy);
                ok("★ 正确密码必须判 SUCCESS（不得因 netId 缺失而漏判）",
                        a.verdict == CrackEngine.Verdict.SUCCESS, "verdict=" + a.verdict);
                ok("★ 且必须是 DIRECT 级", a.grade == EvidenceGrade.Level.DIRECT,
                        "grade=" + a.grade);
                ok("该结论不会被写进 TriedSet（SUCCESS 被排除）",
                        a.verdict == CrackEngine.Verdict.SUCCESS);
            }

            // ------------------------------------------------ 4. 推断级失败不写
            System.out.println();
            System.out.println("-- 4. 推断级失败不得产生 DIRECT --");
            {
                Shell sh = new Shell();
                sh.after = "Wifi is not connected\n";
                CrackEngine.Attempt a = run(sh, "WrongPass99");
                info("verdict=" + a.verdict + " grade=" + a.grade);
                if (a.verdict == CrackEngine.Verdict.AUTH_FAIL) {
                    ok("AUTH_FAIL 时等级必须是 DIRECT（否则不得写盘）",
                            a.grade == EvidenceGrade.Level.DIRECT);
                } else {
                    ok("非 AUTH_FAIL 结论的等级必须不可持久化",
                            !EvidenceGrade.persistable(a.grade), "grade=" + a.grade);
                }
            }

            // ------------------------------------------------ 5. 失败不误升级
            System.out.println();
            System.out.println("-- 5. NETWORK_AUTH_FAIL 永不可持久化 --");
            ok("NETWORK_AUTH_FAIL 默认等级为 NONE",
                    EvidenceGrade.defaultOf(CrackEngine.Verdict.NETWORK_AUTH_FAIL)
                            == EvidenceGrade.Level.NONE);
            ok("AUTH_FAIL 默认等级为 INFERRED（保守）",
                    EvidenceGrade.defaultOf(CrackEngine.Verdict.AUTH_FAIL)
                            == EvidenceGrade.Level.INFERRED);
            CrackEngine.Verdict[] vs = {
                    CrackEngine.Verdict.TIMEOUT, CrackEngine.Verdict.UNKNOWN,
                    CrackEngine.Verdict.INCOMPLETE, CrackEngine.Verdict.TERMINATED_UNKNOWN,
                    CrackEngine.Verdict.ERROR, CrackEngine.Verdict.ABORTED};
            for (CrackEngine.Verdict v : vs) {
                ok(v + " 默认等级不可持久化",
                        !EvidenceGrade.persistable(EvidenceGrade.defaultOf(v)));
            }

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== TriedSet 持久化安全测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String readSrc(String name) {
        try {
            String p = io.github.wlmosv_png.wpaforge.TestPaths.javaPkgDir() + name;
            byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(p));
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
