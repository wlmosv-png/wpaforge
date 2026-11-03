package io.github.wlmosv_png.wpaforge.audit;

/**
 * D2 失败测试：**成功判据必须使用 netId 归属，不能只比 BSSID**。
 *
 * <h3>缺陷</h3>
 * 系统用**旧配置 / PMKSA 缓存**回连同一 AP 时 BSSID 完全相同，
 * 那一次成功会被算到本条候选头上 → 错误口令判成命中。
 * 而 {@code CTRL-EVENT-CONNECTED} 行自带 {@code [id=N]}（已解析进
 * {@link WpaLogReader.LogEvent#netId}），原判定却完全没用。
 *
 * <h3>规则</h3>
 * 拿得到 netId 归属证据时必须用它；拿不到时保持原行为（不额外收紧）。
 *
 * <p>断言直接调用生产函数 {@link WpaLogReader#sawNegotiatedForAttempt}，
 * 它已接入 {@code CrackEngine} 的成功出口。
 */
public final class D2NetIdAttributionTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static final String BSSID = "02:00:00:00:00:11";

    private static String connectedLine(String bssid, Integer id) {
        String base = "10-31 12:00:01.000  1000  1000 I wpa_supplicant: "
                + "CTRL-EVENT-CONNECTED - Connection to " + bssid
                + " (auth) [id=" + (id == null ? "0" : id);
        return base + "] ";
    }

    private static String negotiatedLine(String bssid) {
        return "10-31 12:00:01.000  1000  1000 I wpa_supplicant: "
                + "WPA: Key negotiation completed with " + bssid;
    }

    public static void main(String[] args) {
        System.out.println("== D2 成功判据的 netId 归属 ==");

        // ---------- 1. CONNECTED 带 id，与本次一致 -> 命中
        String sameId = connectedLine(BSSID, 7);
        info("1 CONNECTED id=7 / target=7");
        ok("1 带 netId 且与本次一致 -> 命中",
                WpaLogReader.sawNegotiatedForAttempt(sameId, BSSID, 7), "");

        // ---------- 2. 核心：CONNECTED 带 id，与本次**不一致** -> 不得命中
        info("2 CONNECTED id=3 / target=7（旧配置回连）");
        ok("2 **带 netId 但与本次不一致 -> 不得命中**",
                !WpaLogReader.sawNegotiatedForAttempt(sameId, BSSID, 7)
                        || !WpaLogReader.sawNegotiatedForAttempt(
                                connectedLine(BSSID, 3), BSSID, 7),
                "旧配置回连同一 AP（BSSID 相同、netId 不同）被算成本条候选命中");

        // ---------- 3. 无 netId 信息 -> 保持原行为（仍按 BSSID 命中）
        String noId = negotiatedLine(BSSID);
        info("3 negotiated（无 id）/ target=7");
        ok("3 事件不带 netId -> 退回 BSSID 匹配（不额外收紧）",
                WpaLogReader.sawNegotiatedForAttempt(noId, BSSID, 7), "");

        // ---------- 4. 本次 netId 未知(-1) -> 保持原行为
        ok("4 本次 netId 未知 -> 退回 BSSID 匹配",
                WpaLogReader.sawNegotiatedForAttempt(sameId, BSSID, -1), "");

        // ---------- 5. BSSID 不同 -> 不得命中
        ok("5 BSSID 不匹配 -> 不得命中",
                !WpaLogReader.sawNegotiatedForAttempt(sameId, "02:00:00:00:00:99", 7), "");

        // ---------- 6. 目标 BSSID 为空 -> 不得命中（宁可不判成功）
        ok("6 目标 BSSID 为空 -> 不得命中",
                !WpaLogReader.sawNegotiatedForAttempt(sameId, "", 7), "");

        // ---------- 7. 混合切片：旧配置行在前、本次行在后 -> 命中
        String both = connectedLine(BSSID, 3) + "\n" + connectedLine(BSSID, 7);
        info("7 切片含 id=3 与 id=7 两行 / target=7");
        ok("7 切片中存在与本次匹配的行 -> 命中",
                WpaLogReader.sawNegotiatedForAttempt(both, BSSID, 7), "");

        // ---------- 8. 混合切片：只有不匹配的行 -> 不得命中
        String onlyOld = connectedLine(BSSID, 3) + "\n" + connectedLine(BSSID, 5);
        ok("8 **切片中只有与他次匹配的行 -> 不得命中**",
                !WpaLogReader.sawNegotiatedForAttempt(onlyOld, BSSID, 7),
                "两条都属于别的配置，本条不应命中");

        System.out.println();
        System.out.println("=== D2 netId 归属测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
