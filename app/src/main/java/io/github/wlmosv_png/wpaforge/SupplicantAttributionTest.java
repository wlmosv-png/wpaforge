package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AttemptId;
import io.github.wlmosv_png.wpaforge.audit.AttemptMarker;
import io.github.wlmosv_png.wpaforge.audit.WpaLogReader;

import java.util.List;

/**
 * WpaLogReader / Supplicant 连接事件归因能力审计（C 阶段 spike-6）。
 *
 * <h3>本轮要回答的唯一问题</h3>
 * {@code CTRL-EVENT-CONNECTED} 能不能为「这条连接由当前候选建立」提供**可靠**证据？
 *
 * <h3>方法</h3>
 * 八组场景，全部喂**真实格式的 logcat 文本**给生产解析器
 * （{@link WpaLogReader#parse} / {@code sawNegotiatedFor} / {@link AttemptMarker#sliceAfter}），
 * 断言的是生产函数的行为，不是我自己重写的解析。
 *
 * <p>每组都独立断言（上一轮 3/3 的教训：场景数 ≠ 断言数）。
 */
public final class SupplicantAttributionTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }
    private static void note(String s) { System.out.println("  ▲    " + s); }

    // ---------------------------------------------------------------- 夹具

    static final String BSSID = "28:93:7d:11:22:01";
    static final String OTHER_BSSID = "28:93:7d:11:22:99";
    static final String SSID = "LAB-TARGET";

    private static String runId() {
        return AttemptId.newRunId(1_764_000_000_000L, SSID, 1);
    }

    private static String id(int seq) { return AttemptId.of(runId(), seq); }

    /** 一行 wpa_supplicant 日志（真机格式，见 WpaLogTest 的样本）。 */
    private static String wpa(String ts, String body) {
        return ts + " 28272 28272 I wpa_supplicant: wlan0: " + body + "\n";
    }

    private static String connected(String ts, String bssid, int configId) {
        return wpa(ts, "CTRL-EVENT-CONNECTED - Connection to " + bssid
                + " completed [id=" + configId + " id_str={\"configKey\":\"x\"}]");
    }

    private static String disconnected(String ts, String bssid) {
        return wpa(ts, "CTRL-EVENT-DISCONNECTED bssid=" + bssid + " reason=3");
    }

    private static String associate(String ts, String ssid) {
        return wpa(ts, "Trying to associate with SSID '" + ssid + "'");
    }

    private static String negotiated(String ts, String bssid) {
        return wpa(ts, "WPA: Key negotiation completed with " + bssid);
    }

    /** 标记行（AttemptMarker 写进 logcat 的形态）。 */
    private static String marker(String attemptId) {
        return "10-31 12:00:00.000  1234  1234 I " + AttemptMarker.TAG + ": "
                + AttemptMarker.FIELD + attemptId + "\n";
    }

    // ---------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        System.out.println("=== SupplicantAttributionTest（连接事件归因能力）===");

        String a1 = id(1);   // 候选 A
        String a2 = id(2);   // 候选 B

        // ============================================ 0. 解析器能力基线
        System.out.println();
        System.out.println("== P0 解析器对 CONNECTED 的识别能力 ==");
        String one = connected("10-31 12:00:01.000", BSSID, 7);
        List<WpaLogReader.LogEvent> ev = WpaLogReader.parse(one, 0L);
        info("解析结果: " + ev.size() + " 条");
        for (WpaLogReader.LogEvent e : ev) {
            info("  kind=" + e.kind + " bssid=" + e.bssid + " ssid=" + e.ssid);
        }
        ok("P0-1 CONNECTED 能被识别", ev.size() == 1);
        ok("P0-2 解析出 BSSID", ev.size() == 1 && BSSID.equalsIgnoreCase(ev.get(0).bssid));
        ok("P0-3 kind 被归为 negotiated（与 Key negotiation completed 同类）",
                ev.size() == 1 && "negotiated".equals(ev.get(0).kind));
        note("★ 关键：CTRL-EVENT-CONNECTED 的 kind 是 \"negotiated\"，"
                + "与 \"Key negotiation completed\" **混为一类**");
        ok("P0-4 sawNegotiatedFor(BSSID) 命中", WpaLogReader.sawNegotiatedFor(one, BSSID));
        ok("P0-5 sawNegotiatedFor(别的BSSID) 不命中",
                !WpaLogReader.sawNegotiatedFor(one, OTHER_BSSID));

        // 关键：CONNECTED 行里其实带 [id=N]，但解析器没有提取
        info("原始行: " + one.trim());
        note("★ 真机 CONNECTED 行**带 [id=N]**（即 netId），但 LogEvent 没有 netId 字段");
        ok("P0-6 LogEvent 没有 netId 字段（归因证据被丢弃）", !hasField("netId"));

        // ============================================ A 本次候选成功 → CONNECTED
        System.out.println();
        System.out.println("== A 当前候选连接成功，随后收到 CONNECTED ==");
        String logA = marker(a1) + associate("10-31 12:00:01.000", SSID)
                + negotiated("10-31 12:00:01.500", BSSID)
                + connected("10-31 12:00:01.520", BSSID, 7);
        String sliceA = AttemptMarker.sliceAfter(logA, a1);
        info("切片长度: " + sliceA.length());
        ok("A-1 切片含 CONNECTED 行", sliceA.contains("CTRL-EVENT-CONNECTED"));
        ok("A-2 sawNegotiatedFor 命中 BSSID", WpaLogReader.sawNegotiatedFor(sliceA, BSSID));
        ok("A-3 切片不含标记行本身", !sliceA.contains(AttemptMarker.FIELD + a1));

        // ============================================ B 错密码+系统回连 → CONNECTED
        System.out.println();
        System.out.println("== B 候选密码错误，但系统自动回连目标，随后收到 CONNECTED ==");
        String logB = marker(a2)
                + associate("10-31 12:00:02.000", SSID)                  // 我的尝试
                + wpa("10-31 12:00:02.300", "WPA: 4-Way Handshake failed - pre-shared key may be incorrect")
                + connected("10-31 12:00:04.000", BSSID, 613);            // 系统回连
        String sliceB = AttemptMarker.sliceAfter(logB, a2);
        ok("B-1 切片含 CONNECTED（系统回连的那条）", sliceB.contains("CTRL-EVENT-CONNECTED"));
        ok("B-2 sawNegotiatedFor 依然命中 —— 无法区分",
                WpaLogReader.sawNegotiatedFor(sliceB, BSSID));
        note("★ B-2 就是根问题：切片只能证明「标记之后有 CONNECTED」，"
                + "不能证明它是本条候选造成的");
        info("同一 BSSID、同一 SSID、同一格式 —— 与 A 组切片在证据上**无法区分**");
        ok("B-3 两个切片对同一探测器给出相同结果（不可区分）",
                WpaLogReader.sawNegotiatedFor(sliceA, BSSID)
                        == WpaLogReader.sawNegotiatedFor(sliceB, BSSID));

        // ============================================ C 其他进程建立 → CONNECTED
        System.out.println();
        System.out.println("== C 其他进程建立目标连接，随后收到 CONNECTED ==");
        String logC = marker(a1)
                + connected("10-31 12:00:03.000", OTHER_BSSID, 800);
        String sliceC = AttemptMarker.sliceAfter(logC, a1);
        ok("C-1 切片含 CONNECTED", sliceC.contains("CTRL-EVENT-CONNECTED"));
        ok("C-2 但 BSSID 不同 → sawNegotiatedFor(目标BSSID) 不命中",
                !WpaLogReader.sawNegotiatedFor(sliceC, BSSID));
        note("结论：**只有 BSSID 不同时**才能被排除；BSSID 相同时无法排除");

        // ============================================ D 历史 CONNECTED 残留
        System.out.println();
        System.out.println("== D 候选执行前已存在历史 CONNECTED ==");
        String logD = connected("10-31 11:59:00.000", BSSID, 613)   // 历史
                + marker(a1)
                + associate("10-31 12:00:05.000", SSID);
        String sliceD = AttemptMarker.sliceAfter(logD, a1);
        ok("D-1 历史 CONNECTED 在标记之前 → 被切掉",
                !sliceD.contains("CTRL-EVENT-CONNECTED"));
        ok("D-2 sawNegotiatedFor 不命中", !WpaLogReader.sawNegotiatedFor(sliceD, BSSID));
        note("结论：**标记切片确实能排除标记之前的历史事件** —— 这是现有机制的有效之处");
        // 但反例：标记写失败
        String logD2 = connected("10-31 11:59:00.000", BSSID, 613);
        String sliceD2 = AttemptMarker.sliceAfter(logD2, a1);
        ok("D-3 标记缺失时切成空串（不误判）", sliceD2.isEmpty());
        note("结论：标记写失败 → 切片为空 → 判据全 false，**不会误判成功**（安全）");

        // ============================================ E DISCONNECTED → 系统回连
        System.out.println();
        System.out.println("== E 候选执行期间 DISCONNECTED，随后系统回连 ==");
        String logE = marker(a2)
                + associate("10-31 12:00:06.000", SSID)
                + disconnected("10-31 12:00:06.400", BSSID)
                + connected("10-31 12:00:08.000", BSSID, 613);
        String sliceE = AttemptMarker.sliceAfter(logE, a2);
        ok("E-1 切片含 DISCONNECTED", sliceE.contains("CTRL-EVENT-DISCONNECTED"));
        ok("E-2 切片含 CONNECTED", sliceE.contains("CTRL-EVENT-CONNECTED"));
        ok("E-3 解析器没有「连接生命周期」概念", !hasLifecycleApi());
        note("★ E-3：parse() 只产出事件列表，**不产出 DISCONNECTED→CONNECTED 配对**，"
                + "也没有「这构成一次新建连接」的语义");
        note("结论：顺序信息在文本里存在，但生产代码从未使用它做归属");

        // ============================================ F CONNECTED 缺 id / bssid
        System.out.println();
        System.out.println("== F CONNECTED 事件缺少 id 或 bssid ==");
        String f1 = wpa("10-31 12:00:07.000", "CTRL-EVENT-CONNECTED - Connection to  completed");
        ok("F-1 无 BSSID 的行不被识别", WpaLogReader.parse(f1, 0L).isEmpty());
        String f2 = connected("10-31 12:00:07.000", BSSID, 0);
        ok("F-2 有 BSSID 无 id 仍能识别（丢 id 不影响识别）",
                WpaLogReader.parse(f2, 0L).size() == 1);
        note("★ F-2：id 从来不参与解析 → 丢了也「成功」识别，但归属信息缺失无人知晓");
        String f3 = connected("10-31 12:00:07.000", BSSID, 613);
        ok("F-3 带 id 与不带 id 的解析结果完全相同（id 被丢弃）",
                WpaLogReader.parse(f2, 0L).get(0).kind.equals(
                        WpaLogReader.parse(f3, 0L).get(0).kind));

        // ============================================ G 重复 / 乱序 / 延迟
        System.out.println();
        System.out.println("== G CONNECTED 重复 / 乱序 / 延迟到达 ==");
        String logG = marker(a1)
                + connected("10-31 12:00:08.000", BSSID, 613)
                + connected("10-31 12:00:08.100", BSSID, 613);   // 重复
        // logG = marker(不是 wpa 行，parse 不认) + 两条 CONNECTED
        ok("G-1 重复事件被重复解析（无去重，parse 出 2 条）",
                WpaLogReader.parse(logG, 0L).size() == 2);
        ok("G-2 重复不影响布尔判据（sawNegotiatedFor）",
                WpaLogReader.sawNegotiatedFor(AttemptMarker.sliceAfter(logG, a1), BSSID));
        note("★ G：重复/乱序不改变布尔结论，但**也无从区分哪一次是本条候选**");

        // ============================================ H 成功但日志没读到
        System.out.println();
        System.out.println("== H 候选成功，但日志事件未被读到 ==");
        String logH = marker(a1) + associate("10-31 12:00:09.000", SSID);  // 没有 CONNECTED
        String sliceH = AttemptMarker.sliceAfter(logH, a1);
        ok("H-1 无 CONNECTED → 日志判据为 false",
                !WpaLogReader.sawNegotiatedFor(sliceH, BSSID));
        note("结论：日志判据缺失时**只影响加速，不影响主判据**（主判据走 cmd wifi status）");
        note("但反过来说明：SUCCESS 的**唯一**依据是 status 三条件，日志只做加法");

        // ============================================ 汇总
        System.out.println();
        System.out.println("== 归因能力边界汇总 ==");
        info("能排除：标记之前的历史事件（D-1/D-2）");
        info("能排除：BSSID 不同的他网连接（C-2）");
        info("能排除：标记写入失败时的误判（D-3，切空 → 安全）");
        info("不能排除：同 BSSID 的系统回连（B-2）");
        info("不能排除：同 BSSID 的其他进程连接");
        info("不能证明：CONNECTED 与某条候选的因果关系（无 id 参与、无生命周期）");

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) throw new RuntimeException("SupplicantAttributionTest 失败 " + fail + " 项");
    }

    // ---------------------------------------------------------------- 反射探测

    private static boolean hasField(String name) {
        try {
            WpaLogReader.LogEvent.class.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private static boolean hasLifecycleApi() {
        for (java.lang.reflect.Method m : WpaLogReader.class.getDeclaredMethods()) {
            String n = m.getName().toLowerCase();
            if (n.contains("lifecycle") || n.contains("sequence") || n.contains("paired")) {
                return true;
            }
        }
        return false;
    }
}
