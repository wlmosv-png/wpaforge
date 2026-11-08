package io.github.wlmosv_png.wpaforge.wps;

import java.util.List;

/**
 * 第三阶段任务书第 4 节：SUCCESS 严格证据门槛 + 三级可信等级。
 *
 * <h3>要守住的原则</h3>
 * <ul>
 *   <li>不能把超时等同于 PIN 错误，不能把关联等同于 PIN 正确，不能把新 netId 等同于凭据交付。</li>
 *   <li>缺少协议终局 / 配置归属 / 凭据存在性 / 连接验证 → 不显示「已确认」，显示「待验证/未确认」。</li>
 *   <li>不确定结果不写入成功历史。</li>
 *   <li>历史旧记录（无本会话证据）不得自动升级成「已确认」。</li>
 * </ul>
 *
 * <p>全部调用生产逻辑：{@link WpsHitJudge} 与 {@link WpsEngine}。
 */
public final class WpsEvidenceLevelTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static WpsHitJudge.In confirmed(String ip) {
        WpsHitJudge.In in = new WpsHitJudge.In("a4:2b:b0:11:22:33", "TARGET", 3, 9,
                "COMPLETED", "WPA2-PSK");
        in.connSsid = "TARGET";
        in.connBssid = "a4:2b:b0:11:22:33";
        in.connIp = ip;
        in.confirm = 1;
        in.sawTargetWpsSession = true;
        return in;
    }

    public static void main(String[] a) {
        System.out.println("=== 可信等级与严格证据门槛专项 ===");

        info("① 命中判定本身（归属 + 协议 + 二次确认）仍正常");
        WpsHitJudge.In cIp = confirmed("192.168.1.5");
        ok("归属+协议+确认 → CONFIRMED", WpsHitJudge.isHit(WpsHitJudge.judge(cIp)),
                WpsHitJudge.judge(cIp));

        info("② 平台无法提供的证据必须如实列出（不伪造）");
        List<String> miss = WpsHitJudge.missingEvidence(cIp, true);
        ok("列缺失：协议终局", miss.contains(WpsHitJudge.MISS_WPS_SUCCESS), miss.toString());
        ok("列缺失：配置归属", miss.contains(WpsHitJudge.MISS_CONFIG_ATTRIB), miss.toString());
        ok("列缺失：凭据存在性", miss.contains(WpsHitJudge.MISS_CREDENTIAL), miss.toString());
        ok("列缺失：连接保持", miss.contains(WpsHitJudge.MISS_KEEP), miss.toString());
        ok("有 IP → 不列「连接正常」缺失", !miss.contains(WpsHitJudge.MISS_CONNECT), miss.toString());

        info("③ 硬门槛：即便判定 CONFIRMED，缺协议终局/凭据存在性 → 最多「待验证」");
        String lvl = WpsHitJudge.level(cIp);
        ok("带 IP 且判 CONFIRMED 仍只到待验证", WpsHitJudge.L_PENDING.equals(lvl), lvl
                + "（本实现读不到 WPS-SUCCESS 与凭据存在性，不能硬报已确认）");

        info("④ 无 IP → 多缺「连接正常」→ 待验证");
        WpsHitJudge.In noIp = confirmed("");
        ok("无 IP 判定仍可能是 CONFIRMED（归属+协议成立）",
                WpsHitJudge.isHit(WpsHitJudge.judge(noIp)), WpsHitJudge.judge(noIp));
        ok("无 IP 的缺失里含「连接正常」",
                WpsHitJudge.missingEvidence(noIp, true).contains(WpsHitJudge.MISS_CONNECT), "");
        ok("无 IP 等级待验证（不因无 IP 就判 PIN 错）",
                WpsHitJudge.L_PENDING.equals(WpsHitJudge.level(noIp)), WpsHitJudge.level(noIp));

        info("⑤ 二次确认失败 → 疑似 → 待验证");
        WpsHitJudge.In badCfm = confirmed("192.168.1.5");
        badCfm.confirm = 0;
        ok("confirm=0 → 疑似（不判命中）",
                WpsHitJudge.C_UNVERIFIED.equals(WpsHitJudge.judge(badCfm)),
                WpsHitJudge.judge(badCfm));
        ok("疑似 → 待验证", WpsHitJudge.L_PENDING.equals(WpsHitJudge.level(badCfm)),
                WpsHitJudge.level(badCfm));

        info("⑥ NOT_TARGET / 无关连接 → 未确认");
        WpsHitJudge.In other = confirmed("192.168.1.9");
        other.connSsid = "别的网";
        ok("连到别的网 → NOT_TARGET",
                WpsHitJudge.C_NOT_TARGET.equals(WpsHitJudge.judge(other)),
                WpsHitJudge.judge(other));
        ok("NOT_TARGET → 未确认", WpsHitJudge.L_UNCONFIRMED.equals(WpsHitJudge.level(other)),
                WpsHitJudge.level(other));

        info("⑦ 历史旧记录不自动升级");
        ok("旧记录走 legacyLevel → 待验证（非已确认）",
                WpsHitJudge.L_PENDING.equals(WpsHitJudge.legacyLevel()),
                WpsHitJudge.legacyLevel());
        ok("旧记录不进入已确认分支",
                !WpsHitJudge.L_CONFIRMED.equals(WpsHitJudge.legacyLevel()), "");

        info("⑧ 引擎：证据不足不写入成功历史（不判 HIT）");
        WpsEngine.Session s = new WpsEngine.Session();
        WpsController.Result r = new WpsController.Result();
        r.pin = "12345670";
        r.status = "SUCCESS_UNVERIFIED";
        r.hitVerdict = WpsHitJudge.C_UNVERIFIED;
        WpsEngine.observe(s, new WpsEngine.Attempt(r));
        ok("疑似 → 不判 HIT", !WpsEngine.V_HIT.equals(s.verdict), s.verdict);
        ok("不占 hitPin", s.hitPin == null, String.valueOf(s.hitPin));
        ok("不停止任务", !WpsEngine.shouldStop(s), s.verdict);
        ok("记入疑似计数", s.suspectCount == 1, "" + s.suspectCount);

        info("⑨ 引擎：确证命中但缺终局证据 —— 仍判 HIT（归属+协议成立），但 UI 用等级展示");
        WpsController.Result rOk = new WpsController.Result();
        rOk.pin = "99999999";
        rOk.status = "SUCCESS";
        rOk.hitVerdict = WpsHitJudge.C_CONFIRMED;
        rOk.hitIp = "192.168.1.5";
        rOk.hitConfirm = 1;
        rOk.hitSsid = "TARGET";
        rOk.hitBssid = "a4:2b:b0:11:22:33";
        rOk.targetSsid = "TARGET";
        rOk.sawTargetWpsSession = true;
        WpsEngine.observe(s, new WpsEngine.Attempt(rOk));
        ok("确证命中 → HIT", WpsEngine.V_HIT.equals(s.verdict), s.verdict);
        ok("命中 PIN 写入", "99999999".equals(s.hitPin), s.hitPin);

        info("⑩ 文案中文且可读");
        ok("三级文案非空",
                WpsHitJudge.levelText(WpsHitJudge.L_CONFIRMED, true).contains("已确认")
                        && WpsHitJudge.levelText(WpsHitJudge.L_PENDING, true).contains("待验证")
                        && WpsHitJudge.levelText(WpsHitJudge.L_UNCONFIRMED, true).contains("未确认"), "");
        ok("缺失证据文案中文",
                WpsHitJudge.missingLabel(WpsHitJudge.MISS_CREDENTIAL, true).contains("凭据"), "");

        System.out.println();
        System.out.println("=== WpsEvidenceLevelTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}