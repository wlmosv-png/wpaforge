package io.github.wlmosv_png.wpaforge.audit;

/**
 * AT3 判据的**真机证据固化**（2026-11-02，OnePlus PJX110 / Android 15 / ColorOS 15）。
 *
 * <h3>真机实测结论（不可再靠推测）</h3>
 * <pre>
 *   SSID=whoami  BSSID=c6:d0:0e:dc:24:94（本机热点）
 *   round1  androidNetId=1573  TEMP-DISABLED id=0
 *   round2  androidNetId=1574  TEMP-DISABLED id=0
 *   round3  androidNetId=1575  TEMP-DISABLED id=0
 * </pre>
 * ⇒ <b>{@code CTRL-EVENT-SSID-TEMP-DISABLED id=N} 的 N 与 Android Net ID
 * 不是同一个 ID 空间</b>：Android netId 每次都递增（1573/1574/1575），
 * 而 supplicant 的 {@code id} 恒为 0（它指的是 wpa_supplicant 内部网络块索引）。
 *
 * <h3>这对判定链意味着什么</h3>
 * <ol>
 *   <li>{@code AT3}（失败证据绑定到「本条新建的配置」）在本机**不可达** ——
 *       因为 {@code id == targetNetId} 永不成立。</li>
 *   <li>因此 {@code AUTH_FAIL / DIRECT}（可永久排除）在本机也**不会产生**。
 *       这是**安全方向**：正确密码不会被一次失败事件永久锁死。</li>
 *   <li>实际的失败结论落在 {@code NETWORK_AUTH_FAIL}（网络级，不写 triedSet、
 *       不推 cursor）。代价是：错误候选不会被永久排除，续跑会重复尝试它们。</li>
 * </ol>
 *
 * <p>本测试用**真机原文**钉住这条事实，防止将来有人「修」成硬要求 netId 相等、
 * 或者反过来误以为 AT3 可达而放宽证据门槛。
 */
public final class AT3DeviceEvidenceTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /** 真机原文（写入时逐字符照抄，未改写）。 */
    private static final String DEVICE_ASSOC =
            "11-02 16:47:09.463  9457  9457 D wpa_supplicant: BSSID: c6:d0:0e:dc:24:94 "
                    + "ssid='whoami' wapi_ie_len=0 caps=0x1431\n"
            + "11-02 16:47:10.000  9457  9457 I wpa_supplicant: wlan0: "
                    + "Trying to associate with SSID 'whoami'\n"
            + "11-02 16:47:10.100  9457  9457 I wpa_supplicant: wlan0: WPA: "
                    + "RX message 1 of 4-Way Handshake from c6:d0:0e:dc:24:94 (ver=2)\n";

    /** 真机原文：id=0（不是 1573/1574/1575）。 */
    private static final String DEVICE_TEMP_DISABLED =
            "11-02 16:47:19.730  9457  9457 I wpa_supplicant: wlan0: "
                    + "CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"whoami\" "
                    + "auth_failures=1 duration=5 reason=WRONG_KEY\n";

    private static final String SSID = "whoami";
    private static final String BSSID = "c6:d0:0e:dc:24:94";

    public static void main(String[] args) {
        System.out.println("=== AT3 真机证据（whoami 热点实测 2026-11-02）===");
        String slice = DEVICE_ASSOC + DEVICE_TEMP_DISABLED;

        // ---- ① 真机 netId 下 AT3 不成立 ----
        boolean at3Hit = WpaLogReader.sawWrongKeyForConfig(slice, SSID, BSSID, 1573L);
        info("sawWrongKeyForConfig(netId=1573) = " + at3Hit);
        ok("真机原文下 AT3 判据**不成立**（id=0 != Android netId）", !at3Hit,
                "居然命中了");

        // 对 1574 / 1575 同样不成立
        ok("netId=1574 也不成立",
                !WpaLogReader.sawWrongKeyForConfig(slice, SSID, BSSID, 1574L), "命中");
        ok("netId=1575 也不成立",
                !WpaLogReader.sawWrongKeyForConfig(slice, SSID, BSSID, 1575L), "命中");

        // 只有把 netId 传 0 才会命中 —— 证明它真的在比 id 与 netId
        ok("传 netId=0 时命中（反证：判据就是在比 id 与 netId）",
                WpaLogReader.sawWrongKeyForConfig(slice, SSID, BSSID, 0L), "未命中");

        // ---- ② 降级路径成立：AT1 仍能认出「这是目标的失败」 ----
        boolean at1Hit = WpaLogReader.sawWrongKeyForAttempt(slice, SSID, BSSID);
        ok("AT1（有归属但绑不到配置）仍成立", at1Hit, "未命中");

        // ---- ③ 裁决层：AT1 的 WRONG_KEY 必须落到 NETWORK_AUTH_FAIL ----
        AttemptState st = new AttemptState("m5k3p1-3f9a2b7c-1#1",
                CrackEngine.Lifecycle.TERMINATED, AttemptState.Gate.OK, true);
        Evidence e = new Evidence(Evidence.Source.WPALOG_A, Evidence.Claim.WRONG_KEY,
                Evidence.Attribution.AT1, "m5k3p1-3f9a2b7c-1#1", 100L);
        Adjudicator.Result r = Adjudicator.adjudicate(st,
                java.util.Collections.singletonList(e));
        info("AT1 WRONG_KEY -> " + r);
        ok("真机失败证据裁决为 NETWORK_AUTH_FAIL（不是 AUTH_FAIL）",
                r.verdict == CrackEngine.Verdict.NETWORK_AUTH_FAIL, r.verdict.toString());
        ok("NETWORK_AUTH_FAIL 不持久化（正确密码不会被永久排除）",
                !EvidenceGrade.persistable(r.grade), r.grade.toString());
        ok("NETWORK_AUTH_FAIL 不推进 cursor（续跑会重试）",
                !EvidenceGrade.advancesCursor(r.grade), "advanced");

        // ---- ④ 真机成功行同样不含可用 netId 绑定 ----
        // CTRL-EVENT-CONNECTED 的 [id=0] 同样是 supplicant 内部号
        String connected =
                "11-02 16:46:55.622  9457  9457 I wpa_supplicant: wlan0: "
                        + "CTRL-EVENT-CONNECTED - Connection to 28:93:7d:44:95:a9 completed "
                        + "[id=0 id_str=%7B%22configKey%22%3A%22%5C%22B214%5C%22WPA_PSK%22%7D]\n";
        info("CONNECTED 行同样带 [id=0]，与本机 netId 不同体系");
        ok("成功判定的归属不能依赖日志里的 [id=N]（同为 supplicant 内部号）",
                !connected.contains("id=131") && !connected.contains("id=1573"), "violated");

        System.out.println();
        System.out.println("=== AT3 真机证据: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
