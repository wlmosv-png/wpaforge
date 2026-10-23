package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.SuccessRule;

/**
 * R1 · 判定结果契约的合成测试（**只读阶段，不改生产判定**）。
 *
 * <h3>规格来源</h3>
 * 期望值来自用户决策与设计意图（见 `doc/VERDICT-CONTRACT.md` §5 的决策表），
 * **不是**从生产实现反推 —— 表里的每个数字都是先定契约、再拿来检验代码。
 *
 * <h3>本测试回答用户的五个问题</h3>
 * <ol>
 *   <li>netId 一致 / 不一致 / 缺失 / 无效时的行为 → 决策表逐案断言</li>
 *   <li>其它字段与 netId 互相矛盾时的行为 → 矛盾组合用例</li>
 *   <li>旧任务证据与当前任务证据混淆的可能性 → 差异集（现状判成功、提案判归属不匹配）</li>
 *   <li>当前三条件判定与"候选级证据关联"之间的差异 → **差异集打印**</li>
 *   <li>是否存在把未知状态误判成成功的路径 → 全格穷举 + 蕴含断言</li>
 * </ol>
 *
 * <h3>提案的参考实现</h3>
 * {@link #proposed} 是本文件内的**局部**函数，用于验证"恢复 netId 条件否决"的行为规格。
 * **生产代码里没有它** —— 落地与否等确认。
 */
public class VerdictContractTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (!ok) System.out.println("       期望 " + want + "，实际 " + got);
        check(name, ok);
    }

    // ─────────── 提案契约的参考实现（局部，不参与生产） ───────────
    static final String OK = "SUCCESS";
    static final String NO = "NON_SUCCESS";
    static final String MISMATCH = "UNKNOWN_MISMATCH";

    /**
     * 提案：三条件之上追加「两侧都有有效 netId 时必须相等」。
     * 缺任何一侧（-1 / 0）都**不否决** —— 否则会重演"正确密码永远判不出来"。
     */
    static String proposed(String target, String ssid, String state, String ip,
                           long statusNetId, long targetNetId) {
        boolean three = SuccessRule.isSuccess(target, ssid, state, ip);
        if (!three) return NO;
        if (targetNetId > 0 && statusNetId > 0 && statusNetId != targetNetId) return MISMATCH;
        return OK;
    }

    // ─────────── 独立规格表（= doc/VERDICT-CONTRACT.md §5） ───────────
    private static final Object[][] TABLE = {
        // 名称, 目标SSID, status.SSID, state, ip, statusNetId, targetNetId, 现状期望, 提案期望
        {"理想命中", "B212", "B212", "COMPLETED", "192.168.1.3", 613, 613, OK, OK},
        {"netId 不匹配（归属可疑）", "B212", "B212", "COMPLETED", "192.168.1.3", 613, 700, OK, MISMATCH},
        {"targetNetId 缺失", "B212", "B212", "COMPLETED", "192.168.1.3", 613, -1, OK, OK},
        {"statusNetId 缺失", "B212", "B212", "COMPLETED", "192.168.1.3", -1, 700, OK, OK},
        {"两侧都缺失", "B212", "B212", "COMPLETED", "192.168.1.3", -1, -1, OK, OK},
        {"未拿到 IP", "B212", "B212", "COMPLETED", "0.0.0.0", 613, 613, NO, NO},
        {"握手未完成", "B212", "B212", "DISCONNECTED", "192.168.1.3", 613, 613, NO, NO},
        {"SSID 是别的网（回连）", "B212", "B209", "COMPLETED", "192.168.1.3", 613, 613, NO, NO},
        {"SSID 读不到", "B212", null, "COMPLETED", "192.168.1.3", 613, 613, NO, NO},
        {"state 带尾空格（未知格式）", "B212", "B212", "COMPLETED ", "192.168.1.3", 613, 613, NO, NO},
        {"statusNetId=0（无效值）", "B212", "B212", "COMPLETED", "192.168.1.3", 0, 700, OK, OK},
        {"targetNetId=0（无效值）", "B212", "B212", "COMPLETED", "192.168.1.3", 613, 0, OK, OK},
        // 矛盾组合（用户第 2 项）
        {"矛盾：SSID 别的网 但 netId 相同", "B212", "B209", "COMPLETED", "192.168.1.3", 613, 613, NO, NO},
        {"矛盾：COMPLETED 但 IP 为空", "B212", "B212", "COMPLETED", "", 613, 613, NO, NO},
        {"矛盾：netId 匹配但握手未完成", "B212", "B212", "ASSOCIATING", "192.168.1.3", 613, 613, NO, NO},
        {"矛盾：SSID 目标但连着别的配置", "B212", "B212", "COMPLETED", "192.168.1.3", 700, 613, OK, MISMATCH},
    };

    public static void main(String[] args) {
        System.out.println("== R1 判定结果契约 ==");
        System.out.println();

        // ── 1) 逐案对照（现状 vs 提案） ──
        System.out.println("  案件".length() > 0 ? "" : "");
        int diff = 0;
        StringBuilder diffList = new StringBuilder();
        for (Object[] r : TABLE) {
            String name = (String) r[0];
            String tgt = (String) r[1], ssid = (String) r[2], st = (String) r[3], ip = (String) r[4];
            long sId = ((Number) r[5]).longValue(), tId = ((Number) r[6]).longValue();
            String expCur = (String) r[7], expPro = (String) r[8];

            boolean curOk = SuccessRule.isSuccess(tgt, ssid, st, ip);
            String cur = curOk ? OK : NO;
            String pro = proposed(tgt, ssid, st, ip, sId, tId);

            eq("现状: " + name, expCur, cur);
            eq("提案: " + name, expPro, pro);

            if (curOk && !OK.equals(pro)) {
                diff++;
                diffList.append("        · ").append(name)
                        .append("  (statusNetId=").append(sId)
                        .append(", targetNetId=").append(tId).append(")\n");
            }
        }

        // ── 2) 差异集：现状判成功、但归属不成立（= 假阳性窗口） ──
        System.out.println();
        System.out.println("  ══ 差异集（现状判成功，但候选级归属不成立）：" + diff + " 例");
        System.out.print(diffList);
        // 表里只有两例属于"现状成功但归属不成立"：
        //   · netId 不匹配（statusNetId=613 vs targetNetId=700）
        //   · SSID 是目标、但连着的是别的配置（statusNetId=700 vs targetNetId=613）
        check("差异集恰好是「归属可疑」的那两例", diff == 2);

        // ── 3) 蕴含不变量：提案不许造出新的成功 ──
        int introduced = 0;
        for (Object[] r : TABLE) {
            String tgt = (String) r[1], ssid = (String) r[2], st = (String) r[3], ip = (String) r[4];
            long sId = ((Number) r[5]).longValue(), tId = ((Number) r[6]).longValue();
            if (OK.equals(proposed(tgt, ssid, st, ip, sId, tId))
                    && !SuccessRule.isSuccess(tgt, ssid, st, ip)) {
                introduced++;
            }
        }
        eq("提案不会引入新的成功（proposed ⇒ isSuccess）", 0, introduced);

        // ── 4) 全格穷举：未知/无效输入绝不可成功（用户第 5 项） ──
        String[] tgts = {"B212", null, "", "B209"};
        String[] ssids = {"B212", null, "", "B209", "<unknown ssid>"};
        String[] states = {"COMPLETED", "completed", null, "", "UNKNOWN", "DISCONNECTED", "COMPLETED "};
        String[] ips = {"192.168.1.3", null, "", "0.0.0.0", "fe80::1"};
        long[] ids = {-1, 0, 5, 999};

        int accepted = 0, badAccept = 0, proAccepted = 0, proBad = 0;
        for (String t : tgts) for (String s : ssids) for (String st : states) for (String ip : ips) {
            boolean ok = SuccessRule.isSuccess(t, s, st, ip);
            if (ok) {
                accepted++;
                // 接受成功时，三个字段必须都"明确有效"：非空、非通配、状态确为 COMPLETED
                boolean fieldsExplicit = t != null && !t.isEmpty()
                        && t.equals(s)
                        && st != null && "COMPLETED".equalsIgnoreCase(st)
                        && ip != null && !ip.isEmpty() && !"0.0.0.0".equals(ip);
                if (!fieldsExplicit) badAccept++;
            }
            for (long sid : ids) for (long tid : ids) {
                String p = proposed(t, s, st, ip, sid, tid);
                if (OK.equals(p)) {
                    proAccepted++;
                    if (!ok) proBad++;
                }
            }
        }
        // 按规格推导：目标 SSID 非空 2 种 × state 恰为 COMPLETED（大小写不敏感）2 种
        // × IP 有效 2 种（"192.168.1.3" 与 "fe80::1" —— 规则只排除空串与 0.0.0.0，
        //   IPv6 链路本地地址算"有效"，这里如实记录该边界）= 8
        eq("穷举中「被接受为成功」的组合数（现状，按规格推导 = 8）", 8, accepted);
        eq("其中字段不明确的（现状）", 0, badAccept);
        // 注：proAccepted 是在 (目标×SSID×state×IP×netId对) 的细粒度上计数的，
        // 与 accepted（只数前四维）不可直接比较 —— 之前这里比错了粒度，已改。
        check("提案不是空拒绝（确有接受的组合）", proAccepted > 0);
        eq("提案接受的组合在现状里也成立（proposed ⇒ isSuccess）", 0, proBad);

        // ── 5) 漏判风险集（提案必须"不否决"的那些） ──
        System.out.println();
        System.out.println("  ══ 提案不做否决的情形（漏判风险边界，必须保持成功）");
        String r1 = proposed("B212", "B212", "COMPLETED", "192.168.1.3", -1, 700);
        String r2 = proposed("B212", "B212", "COMPLETED", "192.168.1.3", 613, -1);
        String r3 = proposed("B212", "B212", "COMPLETED", "192.168.1.3", -1, -1);
        String r4 = proposed("B212", "B212", "COMPLETED", "192.168.1.3", 0, 700);
        eq("探测缺失(targetNetId=-1) → 不否决", OK, r1);
        eq("status 没报(statusNetId=-1) → 不否决", OK, r2);
        eq("两侧都缺 → 不否决", OK, r3);
        eq("无效值 0 → 不否决", OK, r4);

        // ── 6) evidence() 标注不得撒谎 ──
        eq("netId 相等 → supplicant+netId",
                "supplicant+netId", SuccessRule.evidence(613, 613));
        eq("netId 不等 → 只报 supplicant",
                "supplicant", SuccessRule.evidence(613, 700));
        eq("两侧 -1 → 不得声称带 netId",
                "supplicant", SuccessRule.evidence(-1, -1));
        eq("一侧 -1 → 不得声称带 netId",
                "supplicant", SuccessRule.evidence(-1, 613));

        System.out.println();
        System.out.println("R1 契约: pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
