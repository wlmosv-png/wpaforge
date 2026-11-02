package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * authcast 归属分级测试（B3b）—— 生产入口，带可靠注入。
 *
 * <h3>为什么这条路径此前完全没被测到</h3>
 * authcast 分支的前置是 {@code authWatch != null && authWatch.isRegistered()}。
 * 测试构造 {@code CrackEngine(int)} 把 authWatch 置 null（刻意的：不碰 Android
 * 运行时），JVM 里也没有真实 BroadcastReceiver 可注册 —— 于是
 * 「唯一能给 DIRECT 的失败出口」在测试里**不可达**。
 *
 * <p>本测试用两个显式注入点（生产代码路径不变）：
 * <ul>
 *   <li>{@code WifiAuthFailureWatcher.armAutoAuthFailureForTest(ssid)} ——
 *       让每条候选窗口内稳定收到一次认证失败（模拟真机「每次尝试都失败」）；</li>
 *   <li>{@code CrackEngine.setAuthWatchForTest(w)} —— 把实例装进测试构造的引擎。</li>
 * </ul>
 *
 * <h3>被钉死的行为（C1 定论）</h3>
 * <pre>
 *   authcast **永远** AUTH_FAIL / INFERRED。
 *
 *   为什么 targetNetId >= 0 不够：
 *     ① 当前命令创建了配置      —— 可证
 *     ② 当前配置获得了 netId    —— 可证
 *     ③ 广播携带了 netId        —— **不可能**：系统广播只有
 *          EXTRA_NEW_STATE 与 EXTRA_SUPPLICANT_ERROR
 *          （EXTRA_SSID / EXTRA_BSSID / EXTRA_NETWORK_INFO 在 Android 12+ 已废弃）
 *     ④ 广播与当前配置有关联    —— 无通道可证
 *     ⑤ 广播与当前 Attempt 有关 —— 无通道可证
 *   第 3 层就断了，所以「存在 netId」不构成关联证据。
 * </pre>
 * 两种情形都必须仍然是 AUTH_FAIL —— 判定能力不能因为收紧归属而丢掉。
 */
public final class AuthCastAttributionTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String SSID = "LAB-AC";
    static final String BSSID = "02:00:00:00:00:0F";

    /**
     * 假通道：命令发出前 status 一律「未连接」（真实前置状态），
     * 发出后切到目标 ASSOCIATING（连接正在推进 → exercised 成立）。
     */
    static final class GatedShell implements CrackEngine.ShellChannel {
        final String listOut;
        boolean attemptStarted = false;
        int sc = 0;
        GatedShell(String listOut) { this.listOut = listOut; }

        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd == null) return "";
            if (cmd.contains("connect-network") || cmd.contains("add-network")) attemptStarted = true;
            if (cmd.startsWith("cmd wifi status")) {
                sc++;
                if (!attemptStarted) return "Wifi is not connected\n";
                return "WifiInfo: SSID: \"" + SSID + "\", BSSID: " + BSSID
                        + ", IP: /0.0.0.0, Supplicant state: ASSOCIATING, Net ID: 613\n";
            }
            if (cmd.startsWith("cmd wifi list-networks")) {
                // 前置阶段不能回目标 netId，否则引擎会认为「配置没清干净」去走断开流程
                return attemptStarted ? listOut : "";
            }
            if (cmd.contains("logcat")) {
                StringBuilder sb = new StringBuilder();
                sb.append(line("attempt=" + aid)).append('\n');
                sb.append(line("wlan0: Trying to associate with SSID '" + SSID + "'")).append('\n');
                sb.append("__WP_LC_RC=0\n__WP_LC_GREP=0\n");
                return sb.toString();
            }
            return "";
        }
        private static String line(String body) {
            return "10-31 12:00:00.000 28272 28272 I wpa_supplicant: " + body;
        }
        @Override public String name() { return "ac-gated"; }
    }

    static String aid;

    private static String attemptIdOf() throws Exception {
        Class<?> c = Class.forName("io.github.wlmosv_png.wpaforge.audit.AttemptId");
        Method nr = c.getMethod("newRunId", long.class, String.class, int.class);
        String run = (String) nr.invoke(null, 1_764_000_000_000L, SSID, 1);
        return (String) c.getMethod("of", String.class, int.class).invoke(null, run, 1);
    }

    private static CrackEngine attemptWith(String listOut) throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(2_000_000L, 1L, 0L));
        Method bypass = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        bypass.setAccessible(true);
        bypass.invoke(e);
        // 关键：装一个**走生产判定代码**的 watcher，并让窗口内稳定有失败广播
        WifiAuthFailureWatcher w = new WifiAuthFailureWatcher(null);
        w.armAutoAuthFailureForTest(SSID);
        e.setAuthWatchForTest(w);
        e.setShellChannel(new GatedShell(listOut));
        return e;
    }

    public static void main(String[] args) {
        System.out.println("=== authcast 归属分级（B3b）===");
        try {
            aid = attemptIdOf();
            NetRecord t = new NetRecord(SSID, BSSID, -55, 2412,
                    NetRecord.Security.WPA2, false, "");

            // ---- 场景 A：本次配置确实建出来了（AT-3 成立）----
            CrackEngine eA = attemptWith("613   " + SSID + "   WPA2\n");
            CrackEngine.Attempt aA = eA.tryOne(t, "WPA2", "WrongPass77", 3000L, aid);
            info("A netId 可取(613) -> verdict=" + aA.verdict + " grade=" + aA.grade
                    + " by=" + aA.confirmedBy);
            ok("A 仍然产出 AUTH_FAIL（判定能力不能丢）",
                    aA.verdict == CrackEngine.Verdict.AUTH_FAIL, "verdict=" + aA.verdict);
            ok("A 确实由 authcast 判定", "authcast".equals(aA.confirmedBy),
                    "by=" + aA.confirmedBy);
            // C1 契约更新（收紧，不是放宽）：
            // `targetNetId >= 0` 只证明「本次建了配置并拿到 netId」，
            // **不**证明这条广播属于它 —— 广播本身不带 netId/BSSID。
            // 所以即使 netId 建出来了，也只能 INFERRED。
            ok("A 即使 netId 建出来（613）**也不得** DIRECT（广播与配置无关联证据）",
                    aA.grade == EvidenceGrade.Level.INFERRED, "grade=" + aA.grade);
            ok("A 的判词说明「无法证明属于本次配置」",
                    aA.detail != null && aA.detail.contains("无法证明属于本次配置"),
                    "detail=" + aA.detail);

            // ---- 场景 B：netId 取不到（AT-3 不成立）----
            CrackEngine eB = attemptWith("");
            CrackEngine.Attempt aB = eB.tryOne(t, "WPA2", "WrongPass77", 3000L, aid);
            info("B netId 取不到 -> verdict=" + aB.verdict + " grade=" + aB.grade
                    + " by=" + aB.confirmedBy
                    + " detail=" + (aB.detail == null ? "" : aB.detail.substring(
                            Math.max(0, aB.detail.length() - 40))));
            ok("B 仍然产出 AUTH_FAIL（本轮结论保留）",
                    aB.verdict == CrackEngine.Verdict.AUTH_FAIL, "verdict=" + aB.verdict);
            ok("B 由 authcast 判定", "authcast".equals(aB.confirmedBy), "by=" + aB.confirmedBy);
            ok("B 拿不到 netId -> INFERRED（**不永久排除**，续跑会重试）",
                    aB.grade == EvidenceGrade.Level.INFERRED, "grade=" + aB.grade);
            ok("B 的判词显式说明「仅本轮结论」",
                    aB.detail != null && aB.detail.contains("仅本轮结论"),
                    "detail=" + aB.detail);

            // ---- 场景 C：同 SSID 多配置（系统里还有一条旧配置）----
            // 这是 authcast 最危险的现场：旧配置（旧密码）重试失败也会广播同 SSID。
            CrackEngine eC = attemptWith("613   " + SSID + "   WPA2\n"
                    + "700   " + SSID + "   WPA2\n");
            CrackEngine.Attempt aC = eC.tryOne(t, "WPA2", "WrongPass77", 3000L, aid);
            info("C 同 SSID 两条配置 -> verdict=" + aC.verdict + " grade=" + aC.grade
                    + " by=" + aC.confirmedBy);
            ok("C 同 SSID 多配置仍然 INFERRED（不得永久排除：可能是旧配置在失败）",
                    aC.grade == EvidenceGrade.Level.INFERRED, "grade=" + aC.grade);
            ok("C 仍然是 AUTH_FAIL（判定能力保留）",
                    aC.verdict == CrackEngine.Verdict.AUTH_FAIL, "verdict=" + aC.verdict);

            // ---- 场景 D：netId 复用（同一个 netId 属于别的配置）----
            // 只证明「这个数字存在过」，不证明「这条广播属于它」。
            CrackEngine eD = attemptWith("613   OTHER-NET   WPA2\n");
            CrackEngine.Attempt aD = eD.tryOne(t, "WPA2", "WrongPass77", 3000L, aid);
            info("D netId 613 属别的 SSID -> verdict=" + aD.verdict + " grade=" + aD.grade
                    + " by=" + aD.confirmedBy);
            ok("D netId 复用场景下仍然 INFERRED", 
                    aD.grade == EvidenceGrade.Level.INFERRED, "grade=" + aD.grade);

            // ---- 场景 E：配置创建失败（list-networks 一直空）----
            CrackEngine eE = attemptWith("");
            CrackEngine.Attempt aE = eE.tryOne(t, "WPA2", "WrongPass77", 3000L, aid);
            info("E 配置创建失败 -> verdict=" + aE.verdict + " grade=" + aE.grade
                    + " by=" + aE.confirmedBy);
            ok("E 配置没建出来时也不得给 DIRECT", 
                    aE.grade != EvidenceGrade.Level.DIRECT, "grade=" + aE.grade);

            // ---- 不变量：INFERRED 不写 triedSet、不推 cursor ----
            ok("AUTH_FAIL/INFERRED 不进永久集合（persistable=false）",
                    !EvidenceGrade.persistable(EvidenceGrade.Level.INFERRED), "");
            ok("AUTH_FAIL/DIRECT 才进永久集合（persistable=true）",
                    EvidenceGrade.persistable(EvidenceGrade.Level.DIRECT), "");
            ok("INFERRED 不推进续跑游标",
                    CrackEngine.nextCursor(0, 0, EvidenceGrade.Level.INFERRED) == 0,
                    "cursor=" + CrackEngine.nextCursor(0, 0, EvidenceGrade.Level.INFERRED));

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== authcast 归属测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}