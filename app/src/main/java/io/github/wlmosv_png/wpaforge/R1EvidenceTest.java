package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.SuccessRule;
import io.github.wlmosv_png.wpaforge.audit.SupplicantParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * R1 回归测试：成功判定的证据充分性与归属。
 *
 * <h3>成功判定所需的证据与来源</h3>
 * <pre>
 *   ① SSID == 目标            ┐
 *   ② Supplicant state == COMPLETED  ├─ 三者必须同源于**同一行 WifiInfo**
 *   ③ IP 有效（非空、非 0.0.0.0）    ┘   来源：shell `cmd wifi status` 的一条 WifiInfo 行
 *   ④ 日志路径另有一条独立成功证据：目标 BSSID 的 Key negotiation completed
 *      来源：wpa_supplicant 日志（且必须落在本候选的窗口切片内）
 *   ⑤ netId：**归属证据**，不是必要条件 —— 只有两侧都拿到有效非零值且不一致时
 *      才降级为未确认（UNKNOWN + reason=netid-conflict）；取不到或为 0 一律不否决
 * </pre>
 *
 * <p>被测对象是生产实现：{@link SuccessRule#assess}、{@link SuccessRule#isSuccess}、
 * {@link SupplicantParser#parse}，以及对引擎与 README 的接线/一致性守卫。
 */
public class R1EvidenceTest {

    private static int pass = 0, fail = 0;
    private static final String T = "LAB-TARGET";
    private static final String BSSID = "02:00:00:00:00:0a";

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        boolean c = (want == null) ? got == null : want.equals(got);
        if (!c) System.out.println("        want=" + want + " got=" + got);
        ok(name, c);
    }

    /** 造一条 WifiInfo 行（真机格式）。 */
    private static String frame(String ssid, String state, String ip, long netId) {
        return "WifiInfo: SSID: \"" + ssid + "\", BSSID: " + BSSID
                + ", IP: /" + ip + ", Supplicant state: " + state
                + ", Net ID: " + netId + "\n";
    }

    private static String status(String ssid, String state, String ip, long netId) {
        return "Wifi is connected to \"" + ssid + "\"\n" + frame(ssid, state, ip, netId);
    }

    public static void main(String[] args) {
        System.out.println("== R1-1 正向：三条件同行 + netId 两侧一致（来源：cmd wifi status）==");
        SupplicantParser.Info good = SupplicantParser.parse(status(T, "COMPLETED", "192.168.1.3", 613));
        ok("三条件都能从同一行取到", T.equals(good.ssid) && "COMPLETED".equals(good.state)
                && "192.168.1.3".equals(good.ip) && good.raw);
        SuccessRule.Assessment a1 = SuccessRule.assess(T, good.ssid, good.state, good.ip, 613, 613);
        ok("判定 SUCCESS", a1.verdict == CrackEngine.Verdict.SUCCESS);
        eq("证据标签 = supplicant+netId", "supplicant+netId", a1.evidence);
        eq("理由码 = ok", "ok", a1.reason);

        System.out.println("== R1-2 反向：任一条件缺失 → 根本不是成功帧 ==");
        String[][] bad = {
                {null, "COMPLETED", "192.168.1.3"},
                {"OTHER-AP", "COMPLETED", "192.168.1.3"},
                {T, "ASSOCIATING", "192.168.1.3"},
                {T, "4WAY_HANDSHAKE", "192.168.1.3"},
                {T, "COMPLETED", "0.0.0.0"},
                {T, "COMPLETED", ""},
                {T, "COMPLETED", null},
                {T, null, "192.168.1.3"},
        };
        int notFrame = 0;
        for (String[] c : bad) {
            SuccessRule.Assessment a = SuccessRule.assess(T, c[0], c[1], c[2], 613, 613);
            if (a.verdict == null) notFrame++;
        }
        eq("8 种残缺组合全部不是成功帧", 8, notFrame);
        ok("isSuccess 与 assess 的协议层判断一致",
                !SuccessRule.isSuccess(T, T, "COMPLETED", "0.0.0.0")
                        && SuccessRule.isSuccess(T, T, "COMPLETED", "192.168.1.3"));

        System.out.println("== R1-3 缺失 / 零值：netId 一律不否决（决策 2）==");
        long[] nonVeto = {-1, 0};
        int okCount = 0;
        for (long s1 : nonVeto) {
            for (long s2 : nonVeto) {
                SuccessRule.Assessment a = SuccessRule.assess(T, T, "COMPLETED", "192.168.1.3", s1, s2);
                if (a.verdict == CrackEngine.Verdict.SUCCESS
                        && "supplicant".equals(a.evidence)) okCount++;
            }
        }
        eq("4 种「取不到/为 0」组合全部判 SUCCESS 且证据标签为 supplicant", 4, okCount);
        ok("单侧缺失也不否决",
                SuccessRule.assess(T, T, "COMPLETED", "192.168.1.3", 613, -1).verdict
                        == CrackEngine.Verdict.SUCCESS
                        && SuccessRule.assess(T, T, "COMPLETED", "192.168.1.3", -1, 613).verdict
                        == CrackEngine.Verdict.SUCCESS);
        ok("单侧为 0 也不否决",
                SuccessRule.assess(T, T, "COMPLETED", "192.168.1.3", 0, 613).verdict
                        == CrackEngine.Verdict.SUCCESS);

        System.out.println("== R1-4 冲突：两侧有效且不一致 → 未确认（非成功、非失败）==");
        SuccessRule.Assessment cf = SuccessRule.assess(T, T, "COMPLETED", "192.168.1.3", 613, 701);
        ok("判 UNKNOWN（既不是 SUCCESS 也不是 AUTH_FAIL）",
                cf.verdict == CrackEngine.Verdict.UNKNOWN);
        eq("reason = netid-conflict", "netid-conflict", cf.reason);
        eq("证据标签标明冲突", "supplicant-netid-conflict", cf.evidence);
        ok("**不**判密码错（冲突不等于失败）", cf.verdict != CrackEngine.Verdict.AUTH_FAIL);
        ok("**不**判成功（冲突不等于命中）", cf.verdict != CrackEngine.Verdict.SUCCESS);

        System.out.println("== R1-5 穷举 netId 组合（10 × 10）==");
        long[] vals = {-1, 0, 1, 2, 613, 614, 700, 701, 999, 1000};
        int success = 0, unknown = 0, other = 0, wrongEvidence = 0;
        for (long x : vals) {
            for (long y : vals) {
                SuccessRule.Assessment a = SuccessRule.assess(T, T, "COMPLETED", "192.168.1.3", x, y);
                if (a.verdict == CrackEngine.Verdict.SUCCESS) {
                    success++;
                    boolean bothValid = x > 0 && y > 0;
                    String want = bothValid ? "supplicant+netId" : "supplicant";
                    if (bothValid && x != y) wrongEvidence++;      // 冲突却判成功 = 严重错误
                    if (!want.equals(a.evidence)) wrongEvidence++;
                } else if (a.verdict == CrackEngine.Verdict.UNKNOWN) {
                    unknown++;
                    if (!(x > 0 && y > 0 && x != y)) wrongEvidence++;  // 不该冲突却冲突
                } else {
                    other++;
                }
            }
        }
        // 期望值由输入直接推导，不写死常数（写错的常数会掩盖真实行为）
        long validCount = 0;
        for (long v : vals) if (v > 0) validCount++;
        long expectConflict = validCount * validCount - validCount;   // 两侧有效且不等
        long expectSuccess = (long) vals.length * vals.length - expectConflict;  // 其余
        eq("成功数 = 总组合 - 冲突组合", expectSuccess, (long) success);
        eq("未确认数 = 两侧有效且不等的组合数", expectConflict, (long) unknown);
        eq("没有其它结论（到期归类不能从成功路径产出）", 0, other);
        eq("没有任何组合出现「冲突却判成功」或「不该冲突却冲突」", 0, wrongEvidence);

        System.out.println("== R1-6 同源要求：不同行的字段不得拼成一条成功 ==");
        // 状态帧 A：目标的 SSID + COMPLETED，但 IP 是 0.0.0.0（还没拿到地址）
        // 状态帧 B：别的网络的 SSID + 有效 IP
        String twoFrames = status(T, "COMPLETED", "0.0.0.0", 613)
                + "WifiInfo: SSID: \"OTHER-AP\", BSSID: aa:bb:cc:dd:ee:ff, "
                + "IP: /192.168.1.9, Supplicant state: COMPLETED, Net ID: 7\n";
        SupplicantParser.Info p2 = SupplicantParser.parse(twoFrames);
        ok("parse 只取第一条 WifiInfo 帧（同源约束来自这里）",
                T.equals(p2.ssid) && "0.0.0.0".equals(p2.ip));
        ok("因此不会把 B 行的 IP 拼到目标的 SSID 上（不判成功）",
                SuccessRule.assess(T, p2.ssid, p2.state, p2.ip, p2.netId, 613).verdict == null);

        System.out.println("== R1-7 跨尝试：状态帧其实来自上一次尝试 ==");
        // 上一次尝试建的配置 netId=701，本条命令建的 netId=613；
        // 状态帧（仍是上一条的连接）显示目标 SSID + COMPLETED + 有 IP + Net ID 701
        SuccessRule.Assessment cross = SuccessRule.assess(T, T, "COMPLETED", "192.168.1.3", 701, 613);
        ok("跨尝试残留被识别为冲突 → 未确认", cross.verdict == CrackEngine.Verdict.UNKNOWN
                && "netid-conflict".equals(cross.reason));
        ok("不会把它记成本次命中", cross.verdict != CrackEngine.Verdict.SUCCESS);

        System.out.println("== R1-8 接线守卫 + README 一致性 ==");
        String eng = read("/workspace/promo-wpaforge/app/src/main/java/"
                + "io/github/wlmosv_png/wpaforge/audit/CrackEngine.java");
        String zh = read("/workspace/promo-wpaforge/README.md");
        String en = read("/workspace/promo-wpaforge/README_EN.md");
        if (eng == null) {
            System.out.println("  N/RUN 接线守卫（源码不可读）");
        } else {
            ok("成功路径走生产评估函数", eng.contains("SuccessRule.assess("));
            ok("成功路径存在「冲突 → UNKNOWN」分支",
                    eng.contains("sa.verdict == Verdict.UNKNOWN"));
            ok("冲突分支不产出 AUTH_FAIL",
                    !eng.contains("sa.verdict == Verdict.UNKNOWN) {\n                        Attempt conflict = new Attempt(password, Verdict.AUTH_FAIL"));
            ok("不再用旧的两参证据标签（已并入 assess）",
                    !eng.contains("SuccessRule.evidence(statusNetId, targetNetId)"));
        }
        if (zh != null) {
            ok("README 不再声称 netId 是必要条件",
                    !zh.contains("四条必须来自同一行") && zh.contains("归属证据"));
            ok("README 写明缺失/为 0 不否决", zh.contains("取不到或为 0 时一律不否决"));
        }
        if (en != null) {
            ok("README_EN 同步：netId 是归属证据", en.contains("attribution evidence"));
            ok("README_EN 同步：缺失/零值不否决",
                    en.contains("missing or zero") && en.contains("never"));
            ok("README_EN 不再声称 netId 是必要条件", !en.contains("IP and netId must all come"));
        }

        System.out.println();
        System.out.println("=== R1 证据测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
