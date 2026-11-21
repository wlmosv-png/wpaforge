package io.github.wlmosv_png.wpaforge.ui;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;

/**
 * 结果文案/语义色映射测试（1.9.66）。纯逻辑。
 *
 * <p>校验：① 七类各有主标签与非空的语义色调；② 事实行**不重复**主标签措辞；
 * ③ 详情行含全部可追溯字段；④ 持久化/游标以 EvidenceGrade 为准（不在 UI 推导）。
 */
public final class ResultCopyTest {

    private static int pass = 0, fail = 0;
    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; System.out.println("  OK   " + n); }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }
    private static void ok(String n, boolean c) { ok(n, c, ""); }

    public static void main(String[] args) {
        System.out.println("== 结果文案/语义色 (1.9.66) ==");
        try {
            // 主标签：关键语义
            ok("★VERIFIED_OK → 已确认成功",
                    "已确认成功".equals(ResultCopy.primaryLabel(ResultCategory.Kind.VERIFIED_OK, true)));
            ok("★PROVEN_WRONG → 已证实密码错误",
                    "已证实密码错误".equals(ResultCopy.primaryLabel(ResultCategory.Kind.PROVEN_WRONG, true)));
            ok("SUSPECTED_WRONG → 疑似密码错误",
                    "疑似密码错误".equals(ResultCopy.primaryLabel(ResultCategory.Kind.SUSPECTED_WRONG, true)));
            ok("NETWORK_FAIL → 网络认证失败",
                    "网络认证失败".equals(ResultCopy.primaryLabel(ResultCategory.Kind.NETWORK_FAIL, true)));
            ok("NOT_FINISHED → 连接未完成",
                    "连接未完成".equals(ResultCopy.primaryLabel(ResultCategory.Kind.NOT_FINISHED, true)));
            ok("ENV_ERROR → 环境异常",
                    "环境异常".equals(ResultCopy.primaryLabel(ResultCategory.Kind.ENV_ERROR, true)));
            ok("★UNDECIDED → 尚无有效判定",
                    "尚无有效判定".equals(ResultCopy.primaryLabel(ResultCategory.Kind.UNDECIDED, true)));

            // 色调
            ok("★成功=OK", ResultCopy.toneOf(ResultCategory.Kind.VERIFIED_OK) == ResultCopy.Tone.OK);
            ok("★已证实=PROVEN", ResultCopy.toneOf(ResultCategory.Kind.PROVEN_WRONG) == ResultCopy.Tone.PROVEN);
            ok("疑似=SUSPECTED", ResultCopy.toneOf(ResultCategory.Kind.SUSPECTED_WRONG) == ResultCopy.Tone.SUSPECTED);
            ok("未完成=NEUTRAL", ResultCopy.toneOf(ResultCategory.Kind.NOT_FINISHED) == ResultCopy.Tone.NEUTRAL);
            ok("★无有效判定=NEUTRAL", ResultCopy.toneOf(ResultCategory.Kind.UNDECIDED) == ResultCopy.Tone.NEUTRAL);
            ok("★环境=ENV", ResultCopy.toneOf(ResultCategory.Kind.ENV_ERROR) == ResultCopy.Tone.ENV);

            // 每类：事实非空 + 不重复主标签
            for (ResultCategory.Kind k : ResultCategory.Kind.values()) {
                String label = ResultCopy.primaryLabel(k, true);
                String fact = ResultCopy.shortFact(k, "no-evidence", true);
                ok("事实非空: " + k, fact != null && !fact.isEmpty());
                ok("★事实不重复主标签: " + k,
                        fact != null && !fact.contains(label),
                        "label=" + label + " fact=" + fact);
            }

            // 详情行：DIRECT 写盘/推进，NONE 不写/不推进
            String dDirect = ResultCopy.detailLine("run#7",
                    CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.DIRECT,
                    "wrong-key", "dumpsys", true);
            ok("★详情含 attempt", dDirect.contains("attempt=run#7"), dDirect);
            ok("详情含 verdict", dDirect.contains("verdict=AUTH_FAIL"), dDirect);
            ok("详情含 grade", dDirect.contains("grade=DIRECT"), dDirect);
            ok("详情含 reason", dDirect.contains("reason=wrong-key"), dDirect);
            ok("详情含 src", dDirect.contains("src=dumpsys"), dDirect);
            ok("★DIRECT → triedSet=写入", dDirect.contains("triedSet=写入"), dDirect);
            ok("★DIRECT → cursor=推进", dDirect.contains("cursor=推进"), dDirect);

            String dNone = ResultCopy.detailLine("", CrackEngine.Verdict.UNKNOWN,
                    EvidenceGrade.Level.NONE, null, null, true);
            ok("空 attempt → attempt=—", dNone.contains("attempt=—"), dNone);
            ok("★NONE → triedSet=不写", dNone.contains("triedSet=不写"), dNone);
            ok("★NONE → cursor=不推进", dNone.contains("cursor=不推进"), dNone);

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println("=== 结果文案/语义色: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
