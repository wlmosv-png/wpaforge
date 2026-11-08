package io.github.wlmosv_png.wpaforge.wps;

import java.util.List;

/**
 * 第二阶段专项：**命中归属**。
 *
 * <h3>要挡住的真实事故</h3>
 * 修复前判命中只看一行：
 * <pre>
 *   curId != baseId &amp;&amp; COMPLETED &amp;&amp; key_mgmt != WPS
 * </pre>
 * 于是「手机在这段时间里连上了**别的**网络（回连家里 WiFi、framework 自动切换、
 * 另一频段上的旧配置）」完全满足条件 —— 判定把它算成目标的 PIN 命中，
 * 并把用户当时正在试的那条 PIN 当成命中 PIN。用户拿这条 PIN 去连目标，连不上。
 *
 * <p>本测试全部调用生产函数（{@link WpsHitJudge}、{@link WpsHelper}、
 * {@link WpsController}、{@link WpsEngine}），不复制判定逻辑。
 * 其中「helper 输出行 → App 解析 → 引擎结论」这一段用
 * {@link WpsHelper#evidenceLine} **真的生成**一遍再喂给解析端，
 * 保证格式漂移会被这条测试当场抓住。
 */
public final class WpsHitAttributionTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /** 构造一次「像成功」的观察：netId 变了、已连上、非 WPS。 */
    private static WpsHitJudge.In hit(String targetSsid, String targetBssid,
                                      String connSsid, String connBssid, String ip) {
        WpsHitJudge.In in = new WpsHitJudge.In(targetBssid, targetSsid, 3, 9,
                "COMPLETED", "WPA2-PSK");
        in.connSsid = connSsid;
        in.connBssid = connBssid;
        in.connIp = ip;
        in.confirm = 1;
        in.sawTargetWpsSession = true;
        return in;
    }

    /** 造一份 Stats：helper 侧采集到的原始事实。 */
    private static WpsHelper.Stats stats(String targetSsid, String connSsid,
                                         String connBssid, String ip, int confirm) {
        WpsHelper.Stats st = new WpsHelper.Stats();
        st.targetBssid = "a4:2b:b0:11:22:33";
        st.targetSsid = targetSsid;
        st.baseNetId = 3;
        st.hitNetId = 9;
        st.hitState = "COMPLETED";
        st.hitKeyMgmt = "WPA2-PSK";
        st.hitSsid = connSsid;
        st.hitBssid = connBssid;
        st.hitIp = ip;
        st.hitConfirm = confirm;
        st.sawTargetWpsSession = true;
        WpsHitJudge.In ev = WpsHelper.evidenceOf(st);
        st.hitVerdict = WpsHitJudge.judge(ev);
        st.hitFacts = WpsHitJudge.facts(ev);
        st.hitWhy = WpsHitJudge.why(ev, false);
        return st;
    }

    public static void main(String[] a) {
        System.out.println("=== WPS 命中归属专项 ===");

        // ---------------------------------------------------------------- 1 归属
        info("① SSID 不一致 → 必须判「不是目标」");
        String v1 = WpsHitJudge.judge(hit("TARGET-AP", "a4:2b:b0:11:22:33",
                "我家WiFi", "aa:bb:cc:dd:ee:01", "192.168.1.9"));
        ok("连到别的 SSID → NOT_TARGET", WpsHitJudge.C_NOT_TARGET.equals(v1), v1);
        ok("NOT_TARGET 不得被当成命中", !WpsHitJudge.isHit(v1), v1);

        info("② 目标 SSID 未知时，BSSID 不一致 → 必须判「不是目标」");
        String v2 = WpsHitJudge.judge(hit("", "a4:2b:b0:11:22:33",
                "", "aa:bb:cc:dd:ee:01", ""));
        ok("BSSID 不一致 → NOT_TARGET", WpsHitJudge.C_NOT_TARGET.equals(v2), v2);

        info("③ 同 SSID 不同 BSSID（漫游）= 仍是目标");
        String v3 = WpsHitJudge.judge(hit("TARGET-AP", "a4:2b:b0:11:22:33",
                "TARGET-AP", "a4:2b:b0:11:22:2c", "192.168.1.7"));
        ok("同 SSID 漫游 → 仍判命中", WpsHitJudge.isHit(v3), v3);

        info("④ 归属读不到（SSID/BSSID 都空）→ 只能记疑似");
        String v4 = WpsHitJudge.judge(hit("TARGET-AP", "a4:2b:b0:11:22:33", "", "", ""));
        ok("归属无法证实 → UNVERIFIED", WpsHitJudge.C_UNVERIFIED.equals(v4), v4);
        ok("疑似不得当命中", !WpsHitJudge.isHit(v4), v4);

        info("⑤ 归属成立 + WPS 会话在目标上 + 复核通过 → 命中");
        String v5 = WpsHitJudge.judge(hit("TARGET-AP", "a4:2b:b0:11:22:33",
                "TARGET-AP", "a4:2b:b0:11:22:33", "192.168.1.5"));
        ok("全部条件成立 → CONFIRMED", WpsHitJudge.isHit(v5), v5);

        // ---------------------------------------------------------------- 2 netId 基线
        info("⑥ netId 基线与变化");
        WpsHitJudge.In same = hit("TARGET-AP", "a4:2b:b0:11:22:33",
                "TARGET-AP", "a4:2b:b0:11:22:33", "192.168.1.5");
        same.curId = same.baseId;
        ok("curId == baseId → 不是命中", WpsHitJudge.C_NO_HIT.equals(WpsHitJudge.judge(same)),
                WpsHitJudge.judge(same));
        WpsHitJudge.In badBase = hit("TARGET-AP", "a4:2b:b0:11:22:33",
                "TARGET-AP", "a4:2b:b0:11:22:33", "");
        badBase.baseId = -1;
        ok("baseId 读失败 → 不是命中（D3 回归）",
                WpsHitJudge.C_NO_HIT.equals(WpsHitJudge.judge(badBase)),
                WpsHitJudge.judge(badBase));
        WpsHitJudge.In badCur = hit("TARGET-AP", "a4:2b:b0:11:22:33",
                "TARGET-AP", "a4:2b:b0:11:22:33", "");
        badCur.curId = -1;
        ok("curId 读失败 → 不是命中", WpsHitJudge.C_NO_HIT.equals(WpsHitJudge.judge(badCur)),
                WpsHitJudge.judge(badCur));

        // ---------------------------------------------------------------- 3 IP 不是唯一条件
        info("⑦ IP 空 ≠ PIN 错（DHCP 失败不得把协议确认判成失败）");
        String noIp = WpsHitJudge.judge(hit("TARGET-AP", "a4:2b:b0:11:22:33",
                "TARGET-AP", "a4:2b:b0:11:22:33", ""));
        ok("IP 空但归属成立 → 仍算命中", WpsHitJudge.isHit(noIp), noIp);
        WpsHitJudge.In ipIn = hit("TARGET-AP", "a4:2b:b0:11:22:33",
                "TARGET-AP", "a4:2b:b0:11:22:33", "");
        ok("没有 IP → 不含 ONLINE 事实",
                !WpsHitJudge.hasFact(WpsHitJudge.facts(ipIn), WpsHitJudge.F_ONLINE), "");
        WpsHitJudge.In ipOk = hit("TARGET-AP", "a4:2b:b0:11:22:33",
                "TARGET-AP", "a4:2b:b0:11:22:33", "192.168.1.5");
        String factsOk = WpsHitJudge.facts(ipOk);
        ok("有 IP → 含 ONLINE 事实",
                WpsHitJudge.hasFact(factsOk, WpsHitJudge.F_ONLINE), factsOk);
        ok("有 IP → 同时含 CREDENTIAL/PROTOCOL/STORED 事实",
                WpsHitJudge.hasFact(factsOk, WpsHitJudge.F_PROTOCOL)
                        && WpsHitJudge.hasFact(factsOk, WpsHitJudge.F_CREDENTIAL)
                        && WpsHitJudge.hasFact(factsOk, WpsHitJudge.F_STORED), factsOk);

        info("⑧ 「别的网络」不得产生协议/凭据事实");
        String factsOther = WpsHitJudge.facts(hit("TARGET-AP", "a4:2b:b0:11:22:33",
                "我家WiFi", "aa:bb:cc:dd:ee:01", "192.168.1.9"));
        ok("NOT_TARGET 不含 PROTOCOL", !WpsHitJudge.hasFact(factsOther, WpsHitJudge.F_PROTOCOL),
                factsOther);
        ok("NOT_TARGET 不含 CREDENTIAL",
                !WpsHitJudge.hasFact(factsOther, WpsHitJudge.F_CREDENTIAL), factsOther);
        ok("NOT_TARGET 仍可含 ONLINE（当前确实在网）",
                WpsHitJudge.hasFact(factsOther, WpsHitJudge.F_ONLINE), factsOther);

        // ---------------------------------------------------------------- 4 生产者→消费者契约
        info("⑨ helper 证据行 → App 解析（用生产函数真造一遍）");
        WpsHelper.Stats stOk = stats("TARGET-AP", "TARGET-AP", "a4:2b:b0:11:22:33",
                "192.168.1.5", 1);
        String line = WpsHelper.evidenceLine(stOk, "12345670");
        info("    证据行：" + line);
        List<WpsController.Result> rs = new java.util.ArrayList<>();
        WpsController.Result r = WpsController.parseBatchLine("PIN=12345670 RESULT=SUCCESS");
        WpsController.applyEvidence(r, line);
        rs.add(r);
        ok("证据行可解析出结论", WpsHitJudge.C_CONFIRMED.equals(r.hitVerdict), r.hitVerdict);
        ok("SSID 传递无空格丢失", "TARGET-AP".equals(r.hitSsid), r.hitSsid);
        ok("BSSID 传递", "a4:2b:b0:11:22:33".equals(r.hitBssid), r.hitBssid);
        ok("IP 传递", "192.168.1.5".equals(r.hitIp), r.hitIp);
        ok("netId/base 传递", r.hitNetId == 9 && r.baseNetId == 3,
                r.hitNetId + "/" + r.baseNetId);
        ok("confirm 传递", r.hitConfirm == 1, "" + r.hitConfirm);
        ok("事实位传递（含 ONLINE）",
                WpsHitJudge.hasFact(r.hitFacts, WpsHitJudge.F_ONLINE), r.hitFacts);
        ok("目标 SSID 传递", "TARGET-AP".equals(r.targetSsid), r.targetSsid);

        info("⑩ 带空格的 SSID 不得把证据行切碎");
        WpsHelper.Stats stSp = stats("我家 的 路由器", "我家 的 路由器",
                "a4:2b:b0:11:22:33", "10.0.0.2", 1);
        String lineSp = WpsHelper.evidenceLine(stSp, "12345670");
        WpsController.Result rSp = WpsController.parseBatchLine("PIN=12345670 RESULT=SUCCESS");
        WpsController.applyEvidence(rSp, lineSp);
        ok("带空格 SSID 往返一致", "我家 的 路由器".equals(rSp.hitSsid), "[" + rSp.hitSsid + "]");
        ok("带空格 SSID 仍判命中", WpsHitJudge.isHit(rSp.hitVerdict), rSp.hitVerdict);

        info("⑪ 「别的网络」经同一条链 → 结论必须仍是 NOT_TARGET");
        WpsHelper.Stats stOther = stats("TARGET-AP", "我家WiFi",
                "aa:bb:cc:dd:ee:01", "192.168.1.9", 1);
        String lineOther = WpsHelper.evidenceLine(stOther, "12345670");
        WpsController.Result rOther = WpsController.parseBatchLine(
                "PIN=12345670 RESULT=SUCCESS_UNVERIFIED");
        WpsController.applyEvidence(rOther, lineOther);
        ok("别的网络 → NOT_TARGET", WpsHitJudge.C_NOT_TARGET.equals(rOther.hitVerdict),
                rOther.hitVerdict);
        ok("别的网络不算命中", !rOther.isSuccess(), rOther.status);

        // ---------------------------------------------------------------- 5 引擎侧守卫
        info("⑫ 引擎：证据说「不是目标」时，状态说 SUCCESS 也不许判命中");
        WpsEngine.Session sOtherEv = new WpsEngine.Session();
        WpsEngine.observe(sOtherEv, new WpsEngine.Attempt(rOther));
        ok("有证据且为 NOT_TARGET → 不判 HIT",
                !WpsEngine.V_HIT.equals(sOtherEv.verdict), sOtherEv.verdict);
        ok("记下 notTarget 次数", sOtherEv.notTargetCount == 1, "" + sOtherEv.notTargetCount);
        ok("不得停止任务", !WpsEngine.shouldStop(sOtherEv), sOtherEv.verdict);

        info("⑬ 引擎：无证据字段（历史/兼容路径）仍按 SUCCESS 处理");
        WpsEngine.Session sLegacy = new WpsEngine.Session();
        WpsEngine.observe(sLegacy, new WpsEngine.Attempt("12345670", "SUCCESS", 8000, 1, 5000));
        ok("兼容：无证据的 SUCCESS 仍为 HIT", WpsEngine.V_HIT.equals(sLegacy.verdict),
                sLegacy.verdict);

        info("⑭ 引擎：确认命中后证据落到会话上（可写台账）");
        WpsEngine.Session sHit = new WpsEngine.Session();
        WpsEngine.observe(sHit, new WpsEngine.Attempt(r));
        ok("判 HIT", WpsEngine.V_HIT.equals(sHit.verdict), sHit.verdict);
        ok("命中 PIN", "12345670".equals(sHit.hitPin), sHit.hitPin);
        ok("证据结论落到会话", WpsHitJudge.C_CONFIRMED.equals(sHit.hitVerdict), sHit.hitVerdict);
        ok("命中时联网事实", sHit.hitOnline, "");
        ok("证据摘要非空且含 ssid",
                !WpsEngine.evidenceSummary(sHit).isEmpty()
                        && WpsEngine.evidenceSummary(sHit).contains("TARGET-AP"),
                WpsEngine.evidenceSummary(sHit));

        System.out.println();
        System.out.println("=== WpsHitAttributionTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
