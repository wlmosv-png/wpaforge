package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.TestPaths;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 生命周期三态穷举测试（B2）。
 *
 * <h3>它拦的是什么回归</h3>
 * 旧实现只有 boolean {@code connectionStillActive}：读不到状态、命令失败、
 * 解析失败统统返回 false —— 与「连接已确认终止」不可区分。
 * 于是「弱失败证据 + 状态读不到」会被升级成 AUTH_FAIL（真机事故 2026-11-02）。
 *
 * <p>三态的核心不变量只有一条：<b>UNKNOWN 绝不能当 TERMINATED 用</b>。
 * 本类用穷举把这条钉死，并且**经生产入口 tryOne 验证**两种真实输出的分流：
 * <pre>
 *   cmd 输出为空                  -> UNKNOWN           （读不到 != 没连接）
 *   Wifi is not connected         -> TERMINATED        （系统明确说没连上）
 * </pre>
 *
 * <p>全部判据都是纯函数；集成部分走生产入口 {@code tryOne(...)}，
 * 用假 ShellChannel 注入真实格式的 status / logcat。
 */
public final class LifecycleThreeStateTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String SSID = "LAB-LC";
    static final String BSSID = "02:00:00:00:00:0E";

    // ---------------------------------------------------------------- 集成用假通道

    /** 按脚本返回 status / logcat 的假通道。 */
    static final class ScriptedShell implements CrackEngine.ShellChannel {
        final List<String> statusScript;
        final List<String> logScript;
        int sc = 0, lc = 0;
        ScriptedShell(List<String> s, List<String> l) { statusScript = s; logScript = l; }
        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd == null) return "";
            if (cmd.startsWith("cmd wifi status")) {
                int i = Math.min(sc++, statusScript.size() - 1);
                return statusScript.get(Math.max(0, i));
            }
            if (cmd.startsWith("cmd wifi list-networks")) return "";
            if (cmd.contains("logcat")) {
                int i = Math.min(lc++, logScript.size() - 1);
                return logScript.get(Math.max(0, i)) + "__WP_LC_RC=0\n__WP_LC_GREP=0\n";
            }
            return "";
        }
        @Override public String name() { return "lc-scripted"; }
    }

    private static String markerLine(String id) {
        return "10-31 12:00:00.000  1234  1234 I " + AttemptMarker.TAG + ": "
                + AttemptMarker.FIELD + id + "\n";
    }
    private static String logLine(String b) {
        return "10-31 12:00:00.000 28272 28272 I wpa_supplicant: wlan0: " + b + "\n";
    }
    private static String assocLine() {
        return logLine("Trying to associate with SSID '" + SSID + "'");
    }
    /** AP-1：零字段握手失败（不带 SSID / BSSID）。 */
    private static String ap1Line() {
        return logLine("WPA: 4-Way Handshake failed - pre-shared key may be incorrect");
    }

    private static String attemptIdOf(int seq) throws Exception {
        Class<?> c = Class.forName("io.github.wlmosv_png.wpaforge.audit.AttemptId");
        Method nr = c.getMethod("newRunId", long.class, String.class, int.class);
        String run = (String) nr.invoke(null, 1_764_000_000_000L, SSID, 1);
        return (String) c.getMethod("of", String.class, int.class).invoke(null, run, seq);
    }

    /** 读仓库内文件；读不到返回空串（不抛，避免守卫项变成噪音）。 */
    private static String readOrEmpty(String root, String rel) {
        try {
            java.io.File f = new java.io.File(root, rel);
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static CrackEngine newEngine() throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(2_000_000L, 1L, 0L));
        Method bypass = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        bypass.setAccessible(true);
        bypass.invoke(e);
        return e;
    }

    /** 跑一次 tryOne：支B(AP-1) 命中 + 指定的 status 输出。 */
    private static CrackEngine.Attempt runWithStatus(String statusOut) throws Exception {
        String aid = attemptIdOf(1);
        List<String> st = new ArrayList<>();
        List<String> lg = new ArrayList<>();
        for (int i = 0; i < 3000; i++) { st.add(statusOut); lg.add(markerLine(aid) + assocLine() + ap1Line()); }

        CrackEngine e = newEngine();
        e.setShellChannel(new ScriptedShell(st, lg));
        NetRecord t = new NetRecord(SSID, BSSID, -55, 2412,
                NetRecord.Security.WPA2, false, "");
        return e.tryOne(t, "WPA2", "WrongPass99", 1200L, aid);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 生命周期三态穷举（B2） ===");

        // ---------------------------------------------------------- 1) lifecycleOf(state)
        System.out.println("-- 1) 由 Supplicant state 得出的三态 --");
        String[] activeStates = { "ASSOCIATING", "AUTHENTICATING", "FOUR_WAY_HANDSHAKE",
                "GROUP_HANDSHAKE", "SCANNING", "COMPLETED", "OBTAINING_IPADDR",
                "associating", "four_way_handshake" };
        int badActive = 0;
        for (String s : activeStates) {
            if (CrackEngine.lifecycleOf(s) != CrackEngine.Lifecycle.ACTIVE) {
                badActive++;
                info("  非 ACTIVE: " + s + " -> " + CrackEngine.lifecycleOf(s));
            }
        }
        ok("9 个活动状态全部判 ACTIVE（含大小写变体）", badActive == 0, "bad=" + badActive);

        String[] termStates = { "DISCONNECTED", "INACTIVE", "INTERFACE_DISABLED",
                "disconnected", "inactive" };
        int badTerm = 0;
        for (String s : termStates) {
            if (CrackEngine.lifecycleOf(s) != CrackEngine.Lifecycle.TERMINATED) badTerm++;
        }
        ok("5 个终止状态全部判 TERMINATED", badTerm == 0, "bad=" + badTerm);

        // 核心不变量：读不到 / 不认识 -> UNKNOWN，**绝不当 TERMINATED**
        ok("null -> UNKNOWN（不是 TERMINATED）",
                CrackEngine.lifecycleOf(null) == CrackEngine.Lifecycle.UNKNOWN, "");
        ok("空串 -> UNKNOWN（不是 TERMINATED）",
                CrackEngine.lifecycleOf("") == CrackEngine.Lifecycle.UNKNOWN, "");
        ok("无法识别的 state -> UNKNOWN（不是 TERMINATED）",
                CrackEngine.lifecycleOf("WAT_IS_THIS") == CrackEngine.Lifecycle.UNKNOWN, "");

        // ---------------------------------------------------------- 2) lifecycleOfStatus
        System.out.println();
        System.out.println("-- 2) 由 cmd wifi status 原始输出得出的三态 --");

        ok("输出 null -> UNKNOWN（读不到 != 没连接）",
                CrackEngine.lifecycleOfStatus(null, SSID) == CrackEngine.Lifecycle.UNKNOWN, "");
        ok("输出空串 -> UNKNOWN（命令失败 / 通道无响应）",
                CrackEngine.lifecycleOfStatus("", SSID) == CrackEngine.Lifecycle.UNKNOWN, "");
        ok("输出全空白 -> UNKNOWN",
                CrackEngine.lifecycleOfStatus("   \n  \n", SSID) == CrackEngine.Lifecycle.UNKNOWN, "");
        ok("Wifi is not connected -> TERMINATED（系统明确说没连上）",
                CrackEngine.lifecycleOfStatus("Wifi is not connected\n", SSID)
                        == CrackEngine.Lifecycle.TERMINATED, "");
        ok("Wifi is disabled -> TERMINATED",
                CrackEngine.lifecycleOfStatus("Wifi is disabled\n", SSID)
                        == CrackEngine.Lifecycle.TERMINATED, "");
        ok("有输出但不可识别 -> UNKNOWN（不敢断言）",
                CrackEngine.lifecycleOfStatus("something entirely unknown\n", SSID)
                        == CrackEngine.Lifecycle.UNKNOWN, "");

        String targetFrame = "WifiInfo: SSID: \"" + SSID + "\", BSSID: " + BSSID
                + ", IP: /0.0.0.0, Supplicant state: FOUR_WAY_HANDSHAKE, Net ID: 613\n";
        ok("目标的 FOUR_WAY_HANDSHAKE -> ACTIVE",
                CrackEngine.lifecycleOfStatus(targetFrame, SSID) == CrackEngine.Lifecycle.ACTIVE, "");

        String otherFrame = "WifiInfo: SSID: \"OTHER-NET\", BSSID: 02:00:00:00:00:FF"
                + ", IP: /192.168.1.9, Supplicant state: COMPLETED, Net ID: 700\n";
        ok("已回连**别的**网络 -> TERMINATED（本次尝试已结束）",
                CrackEngine.lifecycleOfStatus(otherFrame, SSID) == CrackEngine.Lifecycle.TERMINATED, "");

        String targetCompleted = "WifiInfo: SSID: \"" + SSID + "\", BSSID: " + BSSID
                + ", IP: /192.168.1.9, Supplicant state: COMPLETED, Net ID: 613\n";
        ok("目标自身 COMPLETED -> ACTIVE（成功判据独占，不能在这里抢答）",
                CrackEngine.lifecycleOfStatus(targetCompleted, SSID) == CrackEngine.Lifecycle.ACTIVE, "");

        // ---------------------------------------------------------- 3) 生产入口分流
        System.out.println();
        System.out.println("-- 3) 生产入口 tryOne：AP-1 + 两种 status 的分流 --");

        CrackEngine.Attempt aUnknown = runWithStatus("");
        info("status 输出为空      -> verdict=" + aUnknown.verdict + " grade=" + aUnknown.grade
                + " by=" + aUnknown.confirmedBy);
        ok("读不到状态时 **不得** 判 AUTH_FAIL",
                aUnknown.verdict != CrackEngine.Verdict.AUTH_FAIL, "verdict=" + aUnknown.verdict);
        ok("读不到状态 -> UNKNOWN（未知保持未知，不得升级成终止）",
                aUnknown.verdict == CrackEngine.Verdict.UNKNOWN, "verdict=" + aUnknown.verdict);
        ok("读不到状态 -> grade=NONE（不写 triedSet / 不推 cursor）",
                aUnknown.grade == EvidenceGrade.Level.NONE, "grade=" + aUnknown.grade);

        CrackEngine.Attempt aTerm = runWithStatus("Wifi is not connected\n");
        info("status 说没连上      -> verdict=" + aTerm.verdict + " grade=" + aTerm.grade
                + " by=" + aTerm.confirmedBy);
        ok("明确未连接 + AP-1 -> TERMINATED_UNKNOWN（不是 UNKNOWN）",
                aTerm.verdict == CrackEngine.Verdict.TERMINATED_UNKNOWN, "verdict=" + aTerm.verdict);
        ok("TERMINATED_UNKNOWN 不得判 AUTH_FAIL（连接终止 != 密码错）",
                aTerm.verdict != CrackEngine.Verdict.AUTH_FAIL, "verdict=" + aTerm.verdict);
        ok("TERMINATED_UNKNOWN -> grade=NONE（不永久排除候选）",
                aTerm.grade == EvidenceGrade.Level.NONE, "grade=" + aTerm.grade);

        ok("两种情形给出**不同**结论（三态真的在起作用）",
                aUnknown.verdict != aTerm.verdict,
                "unknown=" + aUnknown.verdict + " terminated=" + aTerm.verdict);

        // ---------------------------------------------------------- 4) 源码守卫
        System.out.println();
        System.out.println("-- 4) 源码守卫：不得再回退成 boolean 两态 --");
        try {
            String root = TestPaths.root();
            java.io.File f = new java.io.File(root,
                    "app/src/main/java/io/github/wlmosv_png/wpaforge/audit/CrackEngine.java");
            String code = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            ok("CrackEngine 暴露三态 Lifecycle 枚举", code.contains("public enum Lifecycle"), "未找到枚举");
            ok("存在基于原始输出的 lifecycleOfStatus", code.contains("lifecycleOfStatus("), "未找到方法");
            ok("tryOneShell 用 lastLifecycle（不是每次重算 state）",
                    code.contains("Lifecycle lastLifecycle"), "未接线");
            // C3 契约更新：支B 不再自己写 switch —— 它把
            // Evidence(WPALOG_B, NOT_CONNECTED, NONE) 交给统一 Adjudicator，
            // 由 Adjudicator 按生命周期分派。断言随之改为「必须经统一入口」。
            ok("支B 经统一裁决入口（不再自行分派）",
                    code.contains("Evidence.Source.WPALOG_B"), "未接入");
            ok("Adjudicator 负责生命周期分派",
                    readOrEmpty(root, "app/src/main/java/io/github/wlmosv_png/"
                            + "wpaforge/audit/Adjudicator.java").contains("case ACTIVE:"),
                    "未分派");
        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 源码守卫异常: " + t);
        }

        System.out.println();
        System.out.println("=== 生命周期三态测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
