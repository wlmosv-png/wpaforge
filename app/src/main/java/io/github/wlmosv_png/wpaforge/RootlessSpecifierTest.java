package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Adjudicator;
import io.github.wlmosv_png.wpaforge.audit.AttemptLedger;
import io.github.wlmosv_png.wpaforge.audit.AttemptState;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.Evidence;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;
import io.github.wlmosv_png.wpaforge.cap.SpecifierParams;
import io.github.wlmosv_png.wpaforge.cap.SpecifierVerdict;

import java.util.Collections;

/**
 * 无 Root **在线验证**（WifiNetworkSpecifier）的判定与参数门禁。
 *
 * <h3>要钉住的两件事</h3>
 * <ol>
 *   <li><b>参数门禁</b>：口令不合规时是「未提交」而不是「密码错」。
 *       如果把 builder 抛异常记成 AUTH_FAIL，会写进已试集合，
 *       以后有 root 时跳过一条根本没验过的口令。</li>
 *   <li><b>结论映射</b>：系统只说「没能建立连接」，我们就只能主张「没连上」。
 *       {@code onUnavailable} 一旦被当成 {@code WRONG_KEY}，就会升到
 *       AUTH_FAIL/DIRECT —— 那是把不确定说成已证实。</li>
 * </ol>
 *
 * <p>全部纯逻辑，不碰 ConnectivityManager（那条路只能在真机上测）。
 */
public class RootlessSpecifierTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        RootlessSpecifierTest t = new RootlessSpecifierTest();
        t.run();
        System.out.println(t.pass + " 通过 / " + t.fail + " 失败");
        if (t.fail > 0) System.exit(1);
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
        paramsGate();
        mapConnected();
        mapUnavailable();
        mapNonNetworkOutcomes();
        exhaustive();
        downstreamContract();
        honestWording();
    }

    // ---------------------------------------------------------------- 1. 参数门禁

    private void paramsGate() {
        // 合规：8~63 位普通口令
        ok("8 位口令可用", SpecifierParams.usable("wpa2", "12345678"));
        ok("63 位口令可用", SpecifierParams.usable("wpa2", rep('a', 63)));
        ok("64 位十六进制 PSK 可用", SpecifierParams.usable("wpa2", rep('a', 64)));
        ok("WPA3 同样按长度判", SpecifierParams.usable("wpa3", "12345678"));

        // 不合规：**必须**被判为「不能提交」，不是「密码错」
        ok("7 位口令不可提交", !SpecifierParams.usable("wpa2", "1234567"));
        ok("7 位的原因码是 invalid-candidate",
                SpecifierParams.Drop.INVALID_CANDIDATE.equals(
                        SpecifierParams.rejectReason("wpa2", "1234567")));
        ok("64 位但非十六进制不可用", !SpecifierParams.usable("wpa2", rep('z', 64)));
        ok("64 位十六进制形似可用（大小写都收）", SpecifierParams.usable("wpa2", rep('F', 64)));
        ok("空口令不可用", !SpecifierParams.usable("wpa2", ""));
        ok("null 口令不可用", !SpecifierParams.usable("wpa2", null));
        ok("含控制字符不可用", !SpecifierParams.usable("wpa2", "1234\u00015678"));
        ok("含 DEL 不可用", !SpecifierParams.usable("wpa2", "1234567\u007F"));

        // 加密方式
        ok("未知加密方式被判 unsupported",
                SpecifierParams.Drop.UNSUPPORTED_SECURITY.equals(
                        SpecifierParams.rejectReason("wep", "12345678")));
        ok("normSec 把 sai 归到 wpa3", "wpa3".equals(SpecifierParams.normSec("sae")));
        ok("normSec 空值默认 wpa2", "wpa2".equals(SpecifierParams.normSec("")));
        ok("normSec 大小写不敏感", "wpa3".equals(SpecifierParams.normSec("WPA3")));
        ok("open 不要求口令长度", SpecifierParams.usable("open", ""));
        ok("owe 不要求口令长度", SpecifierParams.usable("owe", null));
    }

    // ---------------------------------------------------------------- 2. 连上

    /**
     * 无 Root「已连上」的语义（2026-11-18 定稿）。
     *
     * <h3>为什么这里不再是 SUCCESS</h3>
     * {@code onAvailable} 只能证明「系统现在有一条符合请求条件的到目标连接」，
     * 证明不了「这条连接是用本条候选的 PSK 完成的握手」：
     * AOSP 的 {@code satisfiesNetworkSpecifier} 不比 PSK，PMKSA 缓存可复用会话密钥。
     * 所以归属只能到 AT2（SSID+BSSID 对得上），交给裁决器后落到
     * <b>UNKNOWN / on-target-unattributed / NONE</b> —— 既不当成功也不当失败。
     *
     * <p>这些断言取代了旧版「连上 → SUCCESS/DIRECT/AT3」那批用例：
     * 旧用例把「连上」当成「密码正确」，正是本次要封的假成功。
     */
    private void mapConnected() {
        SpecifierVerdict.Decision d = SpecifierVerdict.decide(true, false, false, false, false);
        ok("连上 → 不是成功动作（isSuccess 恒 false）", !d.isSuccess());
        ok("连上 → ACT_CONNECTED_UNATTRIBUTED", d.isConnectedUnattributed());
        ok("连上 → 主张 CONNECTED（确实观察到连接）", d.claim == Evidence.Claim.CONNECTED);
        // 关键：归属封顶 AT2 —— AT3 才是「可绑到本次候选的配置」
        ok("连上 → 归属 AT2 而不是 AT3", d.attribution == Evidence.Attribution.AT2);
        ok("连上 → netIdKnown=false（拿不到本次新建的 netId）", !d.netIdKnown);
        ok("连上 → 归类码 available-unattributed", "available-unattributed".equals(d.code));
        ok("连上仍要经裁决器（不自行下结论）", SpecifierVerdict.needsAdjudication(d));
        ok("连上的闸门是 OK", SpecifierVerdict.gateOf(d) == AttemptState.Gate.OK);

        // 真的走一遍裁决：必须是 UNKNOWN / NONE，绝不是 SUCCESS
        Adjudicator.Result r = adjudicate(d, "run#1");
        ok("连上 → 不产生 SUCCESS", r.verdict != CrackEngine.Verdict.SUCCESS);
        ok("连上 → 结果是 UNKNOWN", r.verdict == CrackEngine.Verdict.UNKNOWN);
        ok("连上 → 原因是 on-target-unattributed", "on-target-unattributed".equals(r.reason));
        ok("连上 → 等级 NONE", r.grade == EvidenceGrade.Level.NONE);
        ok("连上 → 不可持久化", !EvidenceGrade.persistable(r.grade));
        ok("连上 → 不推进游标", !EvidenceGrade.advancesCursor(r.grade));

        // 落到 Attempt 上：isSuccess 必须为 false（CrackService 据此不进成功分支）
        CrackEngine.Attempt a = Attempts.of(d, "run#1");
        ok("**连上后 Attempt.isSuccess() 为 false**", !a.isSuccess());
        ok("连上的 Attempt 结论是 UNKNOWN", a.verdict == CrackEngine.Verdict.UNKNOWN);
        ok("连上的 Attempt 等级 NONE", a.grade == EvidenceGrade.Level.NONE);
    }

    // ---------------------------------------------------------------- 3. 没连上（核心）

    private void mapUnavailable() {
        SpecifierVerdict.Decision d = SpecifierVerdict.decide(false, true, false, false, false);
        ok("没连上 → ACT_NOT_CONNECTED", d.isNotConnected());
        // 这一条是整个无 Root 适配最关键的一格
        ok("**没连上绝不主张 WRONG_KEY**", d.claim != Evidence.Claim.WRONG_KEY);
        ok("没连上的主张是 NOT_CONNECTED", d.claim == Evidence.Claim.NOT_CONNECTED);
        ok("没连上 → 归属 AT3（可归到本次请求）",
                d.attribution == Evidence.Attribution.AT3);

        Adjudicator.Result r = adjudicate(d, "run#2");
        ok("没连上 → 不是 AUTH_FAIL", r.verdict != CrackEngine.Verdict.AUTH_FAIL);
        ok("没连上 → 不是 SUCCESS", r.verdict != CrackEngine.Verdict.SUCCESS);
        ok("没连上 → TERMINATED_UNKNOWN（非终局）",
                r.verdict == CrackEngine.Verdict.TERMINATED_UNKNOWN);
        ok("没连上 → 等级 NONE（不写 triedSet）",
                r.grade == EvidenceGrade.Level.NONE);
        ok("没连上 → 不推进游标", !EvidenceGrade.advancesCursor(r.grade));
        ok("没连上 → 不算已证实错误",
                AttemptLedger.Level.classify(r.verdict, r.grade)
                        != AttemptLedger.Level.CONFIRMED_WRONG);
    }

    // ---------------------------------------------------------------- 4. 非网络结论

    private void mapNonNetworkOutcomes() {
        // 用户拒绝确认
        SpecifierVerdict.Decision u = SpecifierVerdict.decide(false, false, true, false, false);
        ok("用户拒绝 → ACT_NOT_EXECUTED", u.isNotExecuted());
        ok("用户拒绝 → 不产证据", u.claim == null && u.source == null);
        ok("用户拒绝 → 归类码 user-rejected", "user-rejected".equals(u.code));
        ok("用户拒绝 → 不经裁决器", !SpecifierVerdict.needsAdjudication(u));
        ok("用户拒绝 → 闸门 NOT_CAPABLE",
                SpecifierVerdict.gateOf(u) == AttemptState.Gate.NOT_CAPABLE);

        // 系统拒绝请求（权限/参数）
        SpecifierVerdict.Decision p = SpecifierVerdict.decide(false, false, false, true, false);
        ok("系统拒绝 → ACT_NOT_EXECUTED", p.isNotExecuted());
        ok("系统拒绝 → 归类码 platform-refused", "platform-refused".equals(p.code));

        // 无答复
        SpecifierVerdict.Decision t = SpecifierVerdict.decide(false, false, false, false, true);
        ok("无答复 → ACT_NOT_EXECUTED", t.isNotExecuted());
        ok("无答复 → 归类码 no-answer", "no-answer".equals(t.code));

        // 什么都没给（防御性）
        SpecifierVerdict.Decision n = SpecifierVerdict.decide(false, false, false, false, false);
        ok("全 false → 也是 NOT_EXECUTED（不猜）", n.isNotExecuted());

        // 优先级：连上优先于一切
        SpecifierVerdict.Decision both =
                SpecifierVerdict.decide(true, true, true, true, true);
        // 「连上」优先级最高 —— 但它的结论是「已连上未归属」，不是成功
        ok("连上优先于其它信号", both.isConnectedUnattributed() && !both.isSuccess());

        // 用户拒绝优先于「系统说不可用」
        SpecifierVerdict.Decision ur =
                SpecifierVerdict.decide(false, true, true, false, false);
        ok("用户拒绝优先于不可用（否则会误算成网络结论）", ur.isNotExecuted());
    }

    // ---------------------------------------------------------------- 5. 穷举

    private void exhaustive() {
        int combos = 0;
        int badAuthFail = 0;
        int badDirect = 0;
        int missingClaim = 0;
        for (int mask = 0; mask < 32; mask++) {
            boolean c = (mask & 1) != 0;
            boolean u = (mask & 2) != 0;
            boolean r = (mask & 4) != 0;
            boolean p = (mask & 8) != 0;
            boolean t = (mask & 16) != 0;
            combos++;
            SpecifierVerdict.Decision d = SpecifierVerdict.decide(c, u, r, p, t);
            // I1：没有 CONNECTED 的路径绝不能产出 AUTH_FAIL
            if (SpecifierVerdict.needsAdjudication(d)) {
                Adjudicator.Result res = adjudicate(d, "run#x");
                if (res.verdict == CrackEngine.Verdict.AUTH_FAIL) badAuthFail++;
                if (res.grade == EvidenceGrade.Level.DIRECT && !c) badDirect++;
                if (d.claim == null || d.source == null) missingClaim++;
            }
        }
        ok("5 个回调位组合全部覆盖（32 格）", combos == 32);
        ok("I1 没有任何组合能产出 AUTH_FAIL（无 Root 拿不到候选级证据）", badAuthFail == 0);
        ok("I2 只有「连上」能给 DIRECT（没连上绝不给）", badDirect == 0);
        ok("有证据的路径必须同时给 source 与 claim", missingClaim == 0);
    }

    // ---------------------------------------------------------------- 6. 下游契约

    private void downstreamContract() {
        // STALE_CONFIG（目标已被系统保存）必须让本条作废
        SpecifierVerdict.Decision sc = SpecifierVerdict.staleConfig();
        ok("净检失败 → ACT_STALE_CONFIG", sc.isStaleConfig());
        ok("净检失败 → 闸门 STALE_CONFIG",
                SpecifierVerdict.gateOf(sc) == AttemptState.Gate.STALE_CONFIG);
        ok("净检失败 → 不经裁决器", !SpecifierVerdict.needsAdjudication(sc));

        CrackEngine.Attempt a = Adjudicator.gateBlocked("12345678", "run#3",
                AttemptState.Gate.STALE_CONFIG, 10L, "系统里还保存着这个网络");
        ok("净检失败 → UNKNOWN", a.verdict == CrackEngine.Verdict.UNKNOWN);
        ok("净检失败 → NONE", a.grade == EvidenceGrade.Level.NONE);
        ok("净检失败 → 不写 triedSet", !EvidenceGrade.persistable(a.grade));
        ok("净检失败 → 不推游标", !EvidenceGrade.advancesCursor(a.grade));

        // 源码守卫：引擎必须真的在提交前做净检替代
        String eng = src("audit/CrackEngine.java");
        ok("引擎在提交前检查「目标已被系统保存/已连着」",
                eng.contains("isTargetSavedOrConnected(target)"));
        ok("引擎在提交前做候选格式校验",
                eng.contains("SpecifierParams") && eng.contains("rejectReason(security, password)"));
        ok("无 Root 分支走的是同一个裁决器", eng.contains("SpecifierVerdict.decide("));

        // 通道本身：canForget 必须恒 false（公开 API 删不掉配置）
        String ch = src("cap/SpecifierChannel.java");
        ok("通道明确声明不能删旧配置", ch.contains("public boolean canForget()")
                && ch.contains("return false;"));
        // 2026-11-18 修正：这里原来断言一个早已删掉的字符串 "forget-unsupported"。
        // 契约本身是「删不掉 + 说明原因 + 用两道替代闸」，按契约断言而不是按魔法字符串。
        ok("通道的 forgetTarget 恒 false 且说明为什么删不掉",
                ch.contains("public boolean forgetTarget(String ssid) {")
                        && ch.contains("公开 API 无法删除已保存配置")
                        && ch.contains("请求前确认没连着目标"));
    }

    // ---------------------------------------------------------------- 7. 文案诚实

    private void honestWording() {
        String ch = src("cap/SpecifierChannel.java");
        // 不能把「每个候选都要确认」包装成「自动破解」
        ok("通道注释承认每个候选要确认", ch.contains("每个不同的网络请求") || ch.contains("确认框"));
        ok("通道注释承认失败不可拆解",
                ch.contains("onUnavailable") && ch.contains("分不开"));
        // 2026-11-18 修正：原断言要求文案里出现「不能…全自动」，但 AOSP 的批准缓存
        // 说明首次批准后同一 AP 的后续候选**可能**不再弹框 —— 那句断言把已知不准确的
        // 结论写死了。改为按「不承诺无人值守 + 不承诺无交互」断言。
        ok("通道不承诺无人值守/无交互",
                (ch.contains("无人值守") || ch.contains("unattended"))
                        && (ch.contains("不承诺") || ch.contains("still need root")));

        // 能力状态里必须带「一次一个」的说明
        ok("能力说明写明一次一个候选",
                ch.contains("一次一个候选") || ch.contains("one confirmation per candidate"));

        // 2026-11-18 修正：原来断言「不能全自动」，但 AOSP 的批准缓存意味着
        // 首次批准后同一 AP 的后续候选**可能**不再弹框。旧断言把一个已知不准确的
        // 结论写死了。现在按**准确表述**断言：首次需确认 + 后续可能复用 + 厂商待确认。
        ok("说明首次需要一次系统确认",
                ch.contains("首次对某 AP 需你点一次确认")
                        || ch.contains("the first request for an AP needs one confirmation"));
        ok("说明后续候选可能复用授权（AOSP 行为）",
                ch.contains("系统可能不再弹框") || ch.contains("may skip it"));
        ok("说明厂商是否保留待真机确认",
                ch.contains("厂商是否保留待真机确认") || ch.contains("vendor builds unverified"));
        ok("不承诺无人值守",
                ch.contains("无人值守") || ch.contains("unattended"));

        // 全仓不许把无 Root 说成「破解成功」
        String all = ch + src("audit/CrackEngine.java");
        ok("不出现「无 Root 破解成功」这类措辞",
                !all.contains("rootless crack succeeded"));
    }

    // ---------------------------------------------------------------- 工具

    private static String rep(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    /** 把决策走完整裁决链落成 Attempt（与生产 tryOneSpecifier 同一入口）。 */
    private static final class Attempts {
        static CrackEngine.Attempt of(SpecifierVerdict.Decision d, String attemptId) {
            AttemptState st = new AttemptState(attemptId, CrackEngine.Lifecycle.TERMINATED,
                    SpecifierVerdict.gateOf(d), d.netIdKnown);
            Adjudicator.Result r = Adjudicator.adjudicate(st,
                    Collections.singletonList(new Evidence(d.source, d.claim, d.attribution,
                            attemptId, 1000L)));
            return Adjudicator.toAttempt("12345678", r, 100L, "", "specifier");
        }
    }

    private Adjudicator.Result adjudicate(SpecifierVerdict.Decision d, String attemptId) {
        Evidence e = new Evidence(d.source, d.claim, d.attribution, attemptId, 1000L);
        AttemptState st = new AttemptState(attemptId, CrackEngine.Lifecycle.TERMINATED,
                SpecifierVerdict.gateOf(d), d.netIdKnown);
        return Adjudicator.adjudicate(st, Collections.singletonList(e));
    }

    private String src(String rel) {
        try {
            java.io.File f = new java.io.File(TestPaths.root()
                    + "/app/src/main/java/io/github/wlmosv_png/wpaforge/" + rel);
            if (!f.isFile()) return "";
            return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
