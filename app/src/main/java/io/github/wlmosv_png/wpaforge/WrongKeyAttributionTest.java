package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AttemptId;
import io.github.wlmosv_png.wpaforge.audit.AttemptMarker;
import io.github.wlmosv_png.wpaforge.audit.WpaLogReader;

import java.util.List;

/**
 * 复现：{@code wrong_key} 日志判据**仅按 SSID 归属**，会把「不属于本次候选的
 * 失败行」算成本条候选的结论，从而把正确密码判成 AUTH_FAIL。
 *
 * <h3>被测的生产链</h3>
 * <pre>
 * CrackEngine.java:1631
 *   if (logWinUsable
 *           && (WpaLogReader.sawWrongKeyFor(wlogSlice, target.ssid)   ← 只比 SSID
 *               || (tried && (sawHandshakeFailure(...) || sawAssocReject(...)))))
 *       return AUTH_FAIL ...
 *
 * WpaLogReader.sawWrongKeyFor(...)
 *   if ("wrong_key".equals(e.kind) && targetSsid.equals(e.ssid)) return true;   ← 无其它归属
 * </pre>
 *
 * <h3>本测试固定的是「判据的行为」，不是「引擎的判定语义」</h3>
 * 它不修改任何生产代码，只断言：**当窗口里出现一条「SSID 相同、但并非本次连接造成」
 * 的 WRONG_KEY 行时，生产判据仍然返回 true** —— 这是缺陷的事实证据。
 *
 * <p>修复落地后，这个测试应当反过来变成「拒绝该行」，届时本类改为断言 false。
 */
public final class WrongKeyAttributionTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static final String SSID = "B212";
    private static final String BSSID = "28:93:7d:41:cc:39";

    private static String a1() {
        return AttemptId.of(AttemptId.newRunId(1_764_000_000_000L, SSID, 1), 1);
    }

    private static String wpa(String body) {
        return "10-31 12:00:00.000 28272 28272 I wpa_supplicant: wlan0: " + body + "\n";
    }

    private static String marker(String id) {
        return "10-31 12:00:00.000  1234  1234 I " + AttemptMarker.TAG + ": "
                + AttemptMarker.FIELD + id + "\n";
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== WrongKeyAttributionTest（wrong_key 判据归属缺失）===");

        String id = a1();

        // ============================================================ 场景 1：正常
        System.out.println();
        System.out.println("== S1 本次候选自己的 WRONG_KEY（正常路径）==");
        String own = marker(id) + wpa(
                "CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"" + SSID
                        + "\" auth_failures=1 duration=5 reason=WRONG_KEY");
        String sliceOwn = AttemptMarker.sliceAfter(own, id);
        ok("S1-a 切片含该行", sliceOwn.contains("WRONG_KEY"));
        ok("S1-b 旧判据返回 true（只比 SSID）",
                WpaLogReader.sawWrongKeyFor(sliceOwn, SSID));
        ok("S1-c 新判据也需前导证据 —— 当前无 associate/failure，故拒绝",
                !WpaLogReader.sawWrongKeyForAttempt(sliceOwn, SSID, BSSID));

        // ============================================================ 场景 2：别处产生的
        System.out.println();
        System.out.println("== S2 同一窗口内，一条『不是本次连接造成』的 WRONG_KEY ==");
        // 构造：标记 → 本候选在跑 → 系统后台用旧配置重试同一 SSID 并失败
        // 这行在真机上与本次失败**完全同构**，唯一区别是它并非本次 connect 的结果。
        String foreign = marker(id)
                + wpa("Trying to associate with SSID '" + SSID + "'")
                + wpa("CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"" + SSID
                        + "\" auth_failures=3 duration=5 reason=WRONG_KEY");
        String sliceForeign = AttemptMarker.sliceAfter(foreign, id);
        boolean oldVerdict = WpaLogReader.sawWrongKeyFor(sliceForeign, SSID);
        boolean newVerdict = WpaLogReader.sawWrongKeyForAttempt(sliceForeign, SSID, BSSID);
        info("旧判据返回: " + oldVerdict + "  新判据返回: " + newVerdict);
        ok("S2-a 切片里确实只有一条 wrong_key", countKind(sliceForeign, "wrong_key") == 1);
        ok("S2-b 旧判据无法区分（返回 true）—— 缺陷原始证据", oldVerdict);
        ok("S2-c 新判据【拒绝】：该切片无 4-Way failed / RX message 1 前导",
                !newVerdict);

        // ============================================================ 场景 3：不同 SSID 被拒
        System.out.println();
        System.out.println("== S3 不同 SSID 的 WRONG_KEY 应被拒（对照组）==");
        String other = marker(id) + wpa(
                "CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"OTHER-AP\""
                        + " auth_failures=1 duration=5 reason=WRONG_KEY");
        ok("S3 SSID 不同 → 判据返回 false（这部分是对的）",
                !WpaLogReader.sawWrongKeyFor(AttemptMarker.sliceAfter(other, id), SSID));

        // ============================================================ 场景 4：无 id 的行
        System.out.println();
        System.out.println("== S4 该行自带 id=N —— D5 之后生产判据**已经用它**（AT3 绑定）==");
        List<WpaLogReader.LogEvent> ev = WpaLogReader.parse(
                wpa("CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"" + SSID
                        + "\" auth_failures=1 duration=5 reason=WRONG_KEY"), 0L);
        ok("S4-a 解析出 wrong_key 事件", ev.size() == 1
                && WpaLogReader.KIND_WRONG_KEY.equals(ev.get(0).kind));
        info("raw = " + (ev.isEmpty() ? "<none>" : ev.get(0).raw.trim()));
        // D5 契约更新（收紧）：wrong_key 行的 `id=0` 现在会被解析进 LogEvent.netId。
        // 这是 AT3 绑定的唯一字段 —— 之前它恒为 UNKNOWN，所以永远不可能有 AT3。
        ok("S4-b wrong_key 事件的 netId 已解析（id=0 -> 0，不再是 UNKNOWN）",
                ev.size() == 1 && ev.get(0).netId == 0L);
        info("解析出的 id = " + (ev.isEmpty() ? "<none>" : String.valueOf(ev.get(0).netId)));

        // 绑定判据：id 与本次配置 netId 一致才给 AT3
        String bound = wpa("wlan0: Trying to associate with SSID '" + SSID + "'")
                + wpa("RSN: RX message 1 of 4-Way Handshake from " + BSSID)
                + wpa("CTRL-EVENT-SSID-TEMP-DISABLED id=613 ssid=\"" + SSID
                        + "\" auth_failures=1 duration=5 reason=WRONG_KEY");
        ok("S4-c id=613 与本次配置 netId=613 一致 -> 绑定成立（AT3 可达）",
                WpaLogReader.sawWrongKeyForConfig(bound, SSID, BSSID, 613L));
        ok("S4-d id=613 但本次配置 netId=700（不同体系/别的配置）-> 不绑定",
                !WpaLogReader.sawWrongKeyForConfig(bound, SSID, BSSID, 700L));
        ok("S4-e netId 没取到（-1）-> 不绑定（无从证明，安全降级）",
                !WpaLogReader.sawWrongKeyForConfig(bound, SSID, BSSID, -1L));
        String noId = wpa("wlan0: Trying to associate with SSID '" + SSID + "'")
                + wpa("RSN: RX message 1 of 4-Way Handshake from " + BSSID)
                + wpa("CTRL-EVENT-SSID-TEMP-DISABLED ssid=\"" + SSID
                        + "\" auth_failures=1 reason=WRONG_KEY");
        ok("S4-f 该行没有 id= 字段 -> 不绑定（不会因为「反正对不上」而误放行）",
                !WpaLogReader.sawWrongKeyForConfig(noId, SSID, BSSID, 613L));

        // ============================================================ 场景 5：历史污染
        System.out.println();
        System.out.println("== S5 标记之前的 WRONG_KEY 应被切片挡掉（现有保护）==");
        String hist = wpa("CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"" + SSID
                + "\" auth_failures=1 duration=5 reason=WRONG_KEY")
                + marker(id)
                + wpa("Trying to associate with SSID '" + SSID + "'");
        ok("S5 标记之前的行被切掉 → 不误判",
                !WpaLogReader.sawWrongKeyFor(AttemptMarker.sliceAfter(hist, id), SSID));

        // ============================================================ 场景 6：完整前导
        System.out.println();
        System.out.println("== S6 本次真实失败（带完整前导）—— 新判据必须接受 ==");
        String realFail = marker(id)
                + wpa("Trying to associate with SSID '" + SSID + "'")
                + wpa("WPA: 4-Way Handshake failed - pre-shared key may be incorrect")
                + wpa("CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"" + SSID
                        + "\" auth_failures=1 duration=5 reason=WRONG_KEY");
        String sliceReal = AttemptMarker.sliceAfter(realFail, id);
        ok("S6-a 新判据接受本次真实失败",
                WpaLogReader.sawWrongKeyForAttempt(sliceReal, SSID, BSSID));
        ok("S6-b 旧判据同样接受", WpaLogReader.sawWrongKeyFor(sliceReal, SSID));

        // ============================================================ 场景 7：M1 前导
        System.out.println();
        System.out.println("== S7 用 RX message 1 做前导（无 4-Way failed）==");
        String m1Fail = marker(id)
                + wpa("Trying to associate with SSID '" + SSID + "'")
                + "10-31 12:00:01.000 28272 28272 I wpa_supplicant: wlan0: "
                + "RSN: RX message 1 of 4-Way Handshake from " + BSSID + "\n"
                + wpa("CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"" + SSID
                        + "\" auth_failures=1 duration=5 reason=WRONG_KEY");
        ok("S7 新判据接受（M1 也是失败前导）",
                WpaLogReader.sawWrongKeyForAttempt(
                        AttemptMarker.sliceAfter(m1Fail, id), SSID, BSSID));

        // ============================================================ 场景 8：auth_failures
        System.out.println();
        System.out.println("== S8 auth_failures 解析 ==");
        java.util.List<WpaLogReader.LogEvent> ev8 = WpaLogReader.parse(
                wpa("CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"" + SSID
                        + "\" auth_failures=3 duration=5 reason=WRONG_KEY"), 0L);
        ok("S8 auth_failures=3 被解析出来",
                ev8.size() == 1 && ev8.get(0).authFailures == 3);
        java.util.List<WpaLogReader.LogEvent> ev8b = WpaLogReader.parse(
                wpa("CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"" + SSID
                        + "\" reason=WRONG_KEY"), 0L);
        ok("S8-b 缺 auth_failures 时为 UNKNOWN",
                ev8b.size() == 1
                        && ev8b.get(0).authFailures == WpaLogReader.LogEvent.AUTH_FAILURES_UNKNOWN);

        // ============================================================ 汇总
        System.out.println();
        System.out.println("== 缺陷结论 ==");
        info("S2 证明：窗口内只要出现一条 ssid=目标 的 WRONG_KEY，");
        info("         无论它是否由本次连接造成，判据都返回 true。");
        info("S4 证明：该行自带的 [id=N] 已能解析，但判据从未使用。");
        info("S5 证明：切片只挡『标记之前』，挡不住『标记之后但非本条造成』。");

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) throw new RuntimeException("WrongKeyAttributionTest 失败 " + fail + " 项");
    }

    private static int countKind(String logText, String kind) {
        int n = 0;
        for (WpaLogReader.LogEvent e : WpaLogReader.parse(logText, 0L)) {
            if (kind.equals(e.kind)) n++;
        }
        return n;
    }
}
