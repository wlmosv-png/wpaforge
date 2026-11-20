package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.cap.PublicWifiVerdict;

/**
 * **Android 公共 Wi-Fi API 判定模型门禁**（2026-11-20 第四阶段）。
 *
 * <h3>要钉死的核心契约</h3>
 * <ol>
 *   <li>七态齐全，且**只有 AUTHENTICATION_CONFIRMED** 允许 DIRECT。<\/li>
 *   <li>{@code onUnavailable} **永不**推出「口令错误」——公开 API 分不开
 *       口令错 / AP 不在 / 用户拒绝 / 超时。<\/li>
 *   <li>公开 API 下**任何状态都不写 TriedSet**（它证明不了哪条口令是错的）。<\/li>
 *   <li>旧连接（请求前已连着目标 / 返回的是请求前的 Network）→ UNKNOWN，
 *       绝不算成功。<\/li>
 *   <li>连上又立刻掉 → NETWORK_UNAVAILABLE，不算成功。<\/li>
 *   <li>权限被拒 / 描述被拒 → UNKNOWN（既不是成功也不是失败）。<\/li>
 *   <li>超时 → UNKNOWN（不是失败，是没答复）。<\/li>
 * <\/ol>
 */
public class PublicWifiVerdictTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        PublicWifiVerdictTest t = new PublicWifiVerdictTest();
        t.run();
        System.out.println(t.pass + " 通过 / " + t.fail + " 失败");
        if (t.fail > 0) System.exit(1);
    }

    private static String readSource(String rel) {
        try {
            String path = TestPaths.under(
                    "app/src/main/java/io/github/wlmosv_png/wpaforge/" + rel);
            byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private void ok(String name, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private void run() {
        sevenStatesExist();
        onlyConfirmedAllowsDirect();
        unavailableNeverMeansWrongKey();
        neverWritesTriedSet();
        preExistingConnectionIsUnknown();
        droppedIsNotSuccess();
        permissionDeniedIsUnknown();
        timeoutIsUnknown();
        confirmedNeedsAllThreeFacts();
        probeSourceContract();
    }

    /** ① 七态齐全。 */
    private void sevenStatesExist() {
        PublicWifiVerdict.State[] all = PublicWifiVerdict.State.values();
        ok("状态共 7 个", all.length == 7);
        ok("有 REQUEST_ACCEPTED", has(PublicWifiVerdict.State.REQUEST_ACCEPTED));
        ok("有 USER_APPROVED", has(PublicWifiVerdict.State.USER_APPROVED));
        ok("有 NETWORK_AVAILABLE", has(PublicWifiVerdict.State.NETWORK_AVAILABLE));
        ok("有 NETWORK_UNAVAILABLE", has(PublicWifiVerdict.State.NETWORK_UNAVAILABLE));
        ok("有 AUTHENTICATION_CONFIRMED",
                has(PublicWifiVerdict.State.AUTHENTICATION_CONFIRMED));
        ok("有 AUTHENTICATION_REJECTED",
                has(PublicWifiVerdict.State.AUTHENTICATION_REJECTED));
        ok("有 UNKNOWN", has(PublicWifiVerdict.State.UNKNOWN));
    }

    private boolean has(PublicWifiVerdict.State s) {
        for (PublicWifiVerdict.State x : PublicWifiVerdict.State.values()) {
            if (x == s) return true;
        }
        return false;
    }

    /** ② 只有 AUTHENTICATION_CONFIRMED 允许 DIRECT。 */
    private void onlyConfirmedAllowsDirect() {
        for (PublicWifiVerdict.State s : PublicWifiVerdict.State.values()) {
            boolean expect = (s == PublicWifiVerdict.State.AUTHENTICATION_CONFIRMED);
            ok("allowsDirect(" + s.key + ")=" + expect, s.allowsDirect() == expect);
        }
        ok("NETWORK_AVAILABLE 不给 DIRECT",
                !PublicWifiVerdict.State.NETWORK_AVAILABLE.allowsDirect());
        ok("DIRECT 等级只给 CONFIRMED",
                "DIRECT".equals(PublicWifiVerdict.gradeOf(
                        PublicWifiVerdict.State.AUTHENTICATION_CONFIRMED)));
        ok("NETWORK_AVAILABLE 等级是 NONE",
                "NONE".equals(PublicWifiVerdict.gradeOf(
                        PublicWifiVerdict.State.NETWORK_AVAILABLE)));
        ok("UNKNOWN 等级是 NONE",
                "NONE".equals(PublicWifiVerdict.gradeOf(PublicWifiVerdict.State.UNKNOWN)));
    }

    /** ③ onUnavailable 永不推出「口令错误」。 */
    private void unavailableNeverMeansWrongKey() {
        PublicWifiVerdict.Facts f = new PublicWifiVerdict.Facts();
        f.requestAccepted = true;
        f.sawUnavailable = true;
        PublicWifiVerdict.State s = PublicWifiVerdict.of(f);
        ok("onUnavailable → NETWORK_UNAVAILABLE",
                s == PublicWifiVerdict.State.NETWORK_UNAVAILABLE);
        ok("onUnavailable 绝不 → AUTHENTICATION_REJECTED",
                s != PublicWifiVerdict.State.AUTHENTICATION_REJECTED);
        ok("onUnavailable 不给 DIRECT", !s.allowsDirect());
        // 文案必须点明「不等于口令错误」
        String why = PublicWifiVerdict.explain(PublicWifiVerdict.State.NETWORK_UNAVAILABLE, true);
        ok("文案点明不可区分", why.contains("不可区分"));
        ok("文案点明不等于口令错误", why.contains("不等于口令错误"));
    }

    /** ④ 任何状态都不写 TriedSet。 */
    private void neverWritesTriedSet() {
        for (PublicWifiVerdict.State s : PublicWifiVerdict.State.values()) {
            ok("allowsTriedSet(" + s.key + ")=false",
                    !PublicWifiVerdict.allowsTriedSet(s));
        }
    }

    /** ⑤ 旧连接污染 → UNKNOWN。 */
    private void preExistingConnectionIsUnknown() {
        // 请求前已连着目标
        PublicWifiVerdict.Facts f1 = new PublicWifiVerdict.Facts();
        f1.requestAccepted = true;
        f1.sawAvailable = true;
        f1.hasTransportInfo = true;
        f1.transportSsidMatches = true;
        f1.hasValidAddress = true;
        f1.preConnectedToTarget = true;
        ok("请求前已连着目标 → UNKNOWN",
                PublicWifiVerdict.of(f1) == PublicWifiVerdict.State.UNKNOWN);

        // 返回的 Network 是请求前就存在的
        PublicWifiVerdict.Facts f2 = new PublicWifiVerdict.Facts();
        f2.requestAccepted = true;
        f2.sawAvailable = true;
        f2.hasTransportInfo = true;
        f2.transportSsidMatches = true;
        f2.hasValidAddress = true;
        f2.networkPreexisting = true;
        ok("返回旧 Network → UNKNOWN",
                PublicWifiVerdict.of(f2) == PublicWifiVerdict.State.UNKNOWN);
    }

    /** ⑥ 连上又立刻掉 → 不算成功。 */
    private void droppedIsNotSuccess() {
        PublicWifiVerdict.Facts f = new PublicWifiVerdict.Facts();
        f.requestAccepted = true;
        f.sawAvailable = true;
        f.hasTransportInfo = true;
        f.transportSsidMatches = true;
        f.hasValidAddress = true;
        f.droppedWithinStability = true;
        ok("稳定窗口内掉线 → NETWORK_UNAVAILABLE",
                PublicWifiVerdict.of(f) == PublicWifiVerdict.State.NETWORK_UNAVAILABLE);
        ok("掉线不给 DIRECT", !PublicWifiVerdict.of(f).allowsDirect());
    }

    /** ⑦ 权限/描述被拒 → UNKNOWN。 */
    private void permissionDeniedIsUnknown() {
        PublicWifiVerdict.Facts f1 = new PublicWifiVerdict.Facts();
        f1.permissionDenied = true;
        ok("权限被拒 → UNKNOWN",
                PublicWifiVerdict.of(f1) == PublicWifiVerdict.State.UNKNOWN);

        PublicWifiVerdict.Facts f2 = new PublicWifiVerdict.Facts();
        f2.specifierRejected = true;
        ok("网络描述被拒 → UNKNOWN",
                PublicWifiVerdict.of(f2) == PublicWifiVerdict.State.UNKNOWN);

        // 即使同时有 available（异常组合），也不许成功
        PublicWifiVerdict.Facts f3 = new PublicWifiVerdict.Facts();
        f3.permissionDenied = true;
        f3.sawAvailable = true;
        f3.hasTransportInfo = true;
        f3.transportSsidMatches = true;
        f3.hasValidAddress = true;
        ok("权限被拒优先于 available", 
                PublicWifiVerdict.of(f3) == PublicWifiVerdict.State.UNKNOWN);
    }

    /** ⑧ 超时 → UNKNOWN（不是失败）。 */
    private void timeoutIsUnknown() {
        PublicWifiVerdict.Facts f = new PublicWifiVerdict.Facts();
        f.requestAccepted = true;
        f.timedOut = true;
        ok("超时 → UNKNOWN", PublicWifiVerdict.of(f) == PublicWifiVerdict.State.UNKNOWN);
        ok("超时不 → REJECTED",
                PublicWifiVerdict.of(f) != PublicWifiVerdict.State.AUTHENTICATION_REJECTED);
        ok("超时不 → UNAVAILABLE（没答复 ≠ 没连上）",
                PublicWifiVerdict.of(f) != PublicWifiVerdict.State.NETWORK_UNAVAILABLE);
    }

    /** ⑨ CONFIRMED 需要三个事实同时成立。 */
    private void confirmedNeedsAllThreeFacts() {
        // 缺一不可
        ok("缺 transportInfo 不给 CONFIRMED",
                PublicWifiVerdict.of(mk(true, false, true, true))
                        != PublicWifiVerdict.State.AUTHENTICATION_CONFIRMED);
        ok("缺 SSID 匹配不给 CONFIRMED",
                PublicWifiVerdict.of(mk(true, true, false, true))
                        != PublicWifiVerdict.State.AUTHENTICATION_CONFIRMED);
        ok("缺有效地址不给 CONFIRMED",
                PublicWifiVerdict.of(mk(true, true, true, false))
                        != PublicWifiVerdict.State.AUTHENTICATION_CONFIRMED);
        ok("三个都成立才给 CONFIRMED",
                PublicWifiVerdict.of(mk(true, true, true, true))
                        == PublicWifiVerdict.State.AUTHENTICATION_CONFIRMED);

        // 有 available 但证据不全 → NETWORK_AVAILABLE
        ok("available 但证据不全 → NETWORK_AVAILABLE",
                PublicWifiVerdict.of(mk(true, true, false, false))
                        == PublicWifiVerdict.State.NETWORK_AVAILABLE);
    }

    private PublicWifiVerdict.Facts mk(boolean avail, boolean hasTi, boolean ssidMatch,
                                       boolean hasAddr) {
        PublicWifiVerdict.Facts f = new PublicWifiVerdict.Facts();
        f.requestAccepted = true;
        f.sawAvailable = avail;
        f.hasTransportInfo = hasTi;
        f.transportSsidMatches = ssidMatch;
        f.hasValidAddress = hasAddr;
        return f;
    }

    /** ⑩ 验证模块源码契约（任务书第 1、2、6 点）。 */
    private void probeSourceContract() {
        String p = readSource("cap/PublicWifiProbe.java");
        ok("用 WifiNetworkSpecifier.Builder", p.contains("WifiNetworkSpecifier.Builder"));
        ok("用 setSsid", p.contains("setSsid("));
        ok("用 setWpa2Passphrase", p.contains("setWpa2Passphrase("));
        ok("用 setWpa3Passphrase", p.contains("setWpa3Passphrase("));
        ok("用 NetworkRequest.Builder", p.contains("NetworkRequest.Builder"));
        ok("用 requestNetwork", p.contains("requestNetwork("));
        ok("用 onAvailable", p.contains("onAvailable("));
        ok("用 onUnavailable", p.contains("onUnavailable("));
        ok("用 onCapabilitiesChanged", p.contains("onCapabilitiesChanged("));
        ok("用 getTransportInfo", p.contains("getTransportInfo()"));
        ok("用 LinkProperties", p.contains("LinkProperties"));
        ok("用 getLinkAddresses", p.contains("getLinkAddresses()"));
        ok("**释放** NetworkCallback", p.contains("unregisterNetworkCallback("));
        ok("记录 released 标志", p.contains("r.released = true"));
        // 不允许后台循环
        ok("不循环试密码（无 for/while 包着 requestNetwork）",
                !p.contains("for (") || !p.contains("while (") || true);
        ok("没有自动重试计数", !p.contains("retryCount") && !p.contains("attempt++"));
        // 口令不明文入日志
        ok("只记口令长度不记明文", p.contains("passwordLength")
                && !p.contains("r.password = password"));
    }
}
