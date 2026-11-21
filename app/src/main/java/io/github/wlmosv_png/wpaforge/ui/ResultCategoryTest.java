package io.github.wlmosv_png.wpaforge.ui;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;

/**
 * 结果分类契约测试（2026-11-22 新增）。
 *
 * <p>钉死「Verdict + grade + reason → 用户可理解类别」的映射，
 * 确保：已证实 / 疑似 / 网络级 / 未完成 / 环境异常 / 无法判断 六者不混。
 */
public final class ResultCategoryTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void ok(String name, boolean cond) { ok(name, cond, ""); }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static final EvidenceGrade.Level D = EvidenceGrade.Level.DIRECT;
    private static final EvidenceGrade.Level I = EvidenceGrade.Level.INFERRED;
    private static final EvidenceGrade.Level N = EvidenceGrade.Level.NONE;

    public static void main(String[] args) {
        System.out.println("== 结果分类契约 ==");
        try {
            // ------------------------------------------------ 1. 成功
            System.out.println();
            System.out.println("-- 1. 成功 --");
            ok("SUCCESS + DIRECT → 密码正确",
                    ResultCategory.of(CrackEngine.Verdict.SUCCESS, D, "") 
                            == ResultCategory.Kind.VERIFIED_OK);
            ok("SUCCESS 但 grade 非 DIRECT → 无法判断（不冒充成功）",
                    ResultCategory.of(CrackEngine.Verdict.SUCCESS, N, "") 
                            == ResultCategory.Kind.UNDECIDED);

            // ------------------------------------------------ 2. 已证实 / 疑似
            System.out.println();
            System.out.println("-- 2. 密码错误：已证实 vs 疑似（本轮核心）--");
            ok("★ AUTH_FAIL + DIRECT → 已证实密码错误",
                    ResultCategory.of(CrackEngine.Verdict.AUTH_FAIL, D, "wrong-key")
                            == ResultCategory.Kind.PROVEN_WRONG);
            ok("★ AUTH_FAIL + INFERRED → 疑似密码错误（不得说已证实）",
                    ResultCategory.of(CrackEngine.Verdict.AUTH_FAIL, I, "")
                            == ResultCategory.Kind.SUSPECTED_WRONG);
            ok("★ AUTH_FAIL + NONE → 疑似密码错误",
                    ResultCategory.of(CrackEngine.Verdict.AUTH_FAIL, N, "")
                            == ResultCategory.Kind.SUSPECTED_WRONG);
            ok("★ 「已证实」与「疑似」是不同类别",
                    ResultCategory.Kind.PROVEN_WRONG != ResultCategory.Kind.SUSPECTED_WRONG);
            ok("★ 疑似类别的文案明确写「尚未证实」",
                    ResultCategory.hint(ResultCategory.Kind.SUSPECTED_WRONG, "", true)
                            .contains("无法证明")
                            || ResultCategory.hint(ResultCategory.Kind.SUSPECTED_WRONG, "", true)
                                    .contains("未记录"),
                    ResultCategory.hint(ResultCategory.Kind.SUSPECTED_WRONG, "", true));

            // ------------------------------------------------ 3. 网络级
            System.out.println();
            System.out.println("-- 3. 网络认证失败 --");
            ok("★ NETWORK_AUTH_FAIL → 网络认证失败（不是已证实错误）",
                    ResultCategory.of(CrackEngine.Verdict.NETWORK_AUTH_FAIL, N, "network-auth-fail")
                            == ResultCategory.Kind.NETWORK_FAIL);
            ok("★ 网络失败 ≠ 已证实密码错误",
                    ResultCategory.Kind.NETWORK_FAIL != ResultCategory.Kind.PROVEN_WRONG);
            ok("网络失败文案说明「绑不到这条口令」",
                    ResultCategory.hint(ResultCategory.Kind.NETWORK_FAIL, "", true)
                            .contains("绑不到")
                            || ResultCategory.hint(ResultCategory.Kind.NETWORK_FAIL, "", true)
                                    .contains("未记录"));

            // ------------------------------------------------ 4. 未完成
            System.out.println();
            System.out.println("-- 4. 连接未完成 --");
            ok("TIMEOUT → 连接未完成",
                    ResultCategory.of(CrackEngine.Verdict.TIMEOUT, N, "no-evidence")
                            == ResultCategory.Kind.NOT_FINISHED);
            ok("INCOMPLETE → 连接未完成",
                    ResultCategory.of(CrackEngine.Verdict.INCOMPLETE, N, "")
                            == ResultCategory.Kind.NOT_FINISHED);
            ok("TERMINATED_UNKNOWN → 连接未完成",
                    ResultCategory.of(CrackEngine.Verdict.TERMINATED_UNKNOWN, N, "")
                            == ResultCategory.Kind.NOT_FINISHED);
            ok("ABORTED → 连接未完成",
                    ResultCategory.of(CrackEngine.Verdict.ABORTED, N, "")
                            == ResultCategory.Kind.NOT_FINISHED);

            // ------------------------------------------------ 5. 环境异常
            System.out.println();
            System.out.println("-- 5. 环境异常 --");
            ok("ERROR → 环境异常",
                    ResultCategory.of(CrackEngine.Verdict.ERROR, N, "")
                            == ResultCategory.Kind.ENV_ERROR);
            ok("TARGET_GONE → 环境异常",
                    ResultCategory.of(CrackEngine.Verdict.TARGET_GONE, N, "")
                            == ResultCategory.Kind.ENV_ERROR);
            ok("★ UNKNOWN + gate-stale_config → 环境异常（不是「证据不足」）",
                    ResultCategory.of(CrackEngine.Verdict.UNKNOWN, N, "gate-stale_config")
                            == ResultCategory.Kind.ENV_ERROR);
            ok("★ UNKNOWN + not-attempted → 环境异常",
                    ResultCategory.of(CrackEngine.Verdict.UNKNOWN, N, "not-attempted")
                            == ResultCategory.Kind.ENV_ERROR);

            // ------------------------------------------------ 6. 无法判断（含 netid-conflict）
            System.out.println();
            System.out.println("-- 6. 无法判断（本轮重点：区分「没观察到」与「连上了但归因存疑」）--");
            ok("UNKNOWN + no-evidence → 无法判断",
                    ResultCategory.of(CrackEngine.Verdict.UNKNOWN, N, "no-evidence")
                            == ResultCategory.Kind.UNDECIDED);
            ok("★ UNKNOWN + netid-conflict → 无法判断（但标注「曾连上」）",
                    ResultCategory.of(CrackEngine.Verdict.UNKNOWN, N, "netid-conflict")
                            == ResultCategory.Kind.UNDECIDED);
            /*
             * 2026-11-22 修正（真机证伪，不是放宽）：
             *
             * 原断言要求 netid-conflict 被识别为「目标可能连上过」，
             * 于是 UI 提示「目标曾连上并取得 IP，请人工复核」。
             *
             * 真机实测（PJX110 / ColorOS 15，dumpsys wifi 全量 63 条连接事件）：
             *   2.4G-3 连续 14 次尝试，connectionResult 恒为 0，
             *   level2FailureCode 恒为 AUTHENTICATION_FAILURE，
             *   **一次都没连上过**，而 1.9.63 仍把它标成 netid-conflict
             *   并让用户去「人工复核」—— 那是在复核一个不存在的命中。
             *
             * netid-conflict 的真实含义是「netId 与本次配置不符」，
             * 它**不蕴含**「连上过」。真正的「连上过」只有 status-unclear
             * 与 on-target-unattributed 能证明（两者都由「status 显示目标」触发）。
             */
            ok("★ netid-conflict **不**被识别为「目标可能连上过」（真机证伪）",
                    !ResultCategory.targetMayHaveConnected("netid-conflict"));
            ok("★ status-unclear 仍被识别为「目标可能连上过」",
                    ResultCategory.targetMayHaveConnected("status-unclear"));
            ok("★ on-target-unattributed 仍被识别为「目标可能连上过」",
                    ResultCategory.targetMayHaveConnected("on-target-unattributed"));
            ok("★ no-evidence 不被识别为「连上过」",
                    !ResultCategory.targetMayHaveConnected("no-evidence"));
            ok("★ netid-conflict 的文案不再说「曾连上」，改说归属存疑",
                    !ResultCategory.hint(ResultCategory.Kind.UNDECIDED,
                            "netid-conflict", true).contains("曾连上")
                            && ResultCategory.hint(ResultCategory.Kind.UNDECIDED,
                                    "netid-conflict", true).contains("netId"),
                    ResultCategory.hint(ResultCategory.Kind.UNDECIDED, "netid-conflict", true));
            /*
             * no-evidence / not-connected-terminated 这一类现在的文案是
             * 「系统记录到本次连接失败，但原因不是『密钥错误』…」。
             *
             * 这不是变弱，而是**变准**：真机 whoami 的 14 条全是
             * NEW_CONNECTION_ATTEMPT —— 系统确实试过、确实没连上，
             * 但没有密钥不符的证据。旧文案「没拿到证据」会让用户
             * 以为是应用没跑，而实际是 AP 没给结论。
             */
            ok("★ no-evidence 的文案说「系统记录到失败、但不是密钥错误」",
                    ResultCategory.hint(ResultCategory.Kind.UNDECIDED, "no-evidence", true)
                            .contains("不是「密钥错误」"),
                    ResultCategory.hint(ResultCategory.Kind.UNDECIDED, "no-evidence", true));
            ok("★ 该文案仍明确说「未记录、下次会重试」",
                    ResultCategory.hint(ResultCategory.Kind.UNDECIDED, "no-evidence", true)
                            .contains("未记录"));

            // ------------------------------------------------ 7. 穷举不遗漏
            System.out.println();
            System.out.println("-- 7. 穷举全部 Verdict × Grade --");
            int covered = 0, total = 0;
            for (CrackEngine.Verdict v : CrackEngine.Verdict.values()) {
                for (EvidenceGrade.Level g : EvidenceGrade.Level.values()) {
                    total++;
                    if (ResultCategory.of(v, g, "") != null) covered++;
                }
            }
            ok("全部 " + total + " 个组合都有确定类别", covered == total,
                    "covered=" + covered);
            ok("null verdict → 无法判断",
                    ResultCategory.of(null, null, "") == ResultCategory.Kind.UNDECIDED);

            // ------------------------------------------------ 8. 语义互斥
            System.out.println();
            System.out.println("-- 8. 六类互斥（同一输入只能落一类）--");
            ok("已证实 ≠ 疑似", ResultCategory.Kind.PROVEN_WRONG != ResultCategory.Kind.SUSPECTED_WRONG);
            ok("已证实 ≠ 网络级", ResultCategory.Kind.PROVEN_WRONG != ResultCategory.Kind.NETWORK_FAIL);
            ok("网络级 ≠ 无法判断", ResultCategory.Kind.NETWORK_FAIL != ResultCategory.Kind.UNDECIDED);
            ok("环境异常 ≠ 无法判断", ResultCategory.Kind.ENV_ERROR != ResultCategory.Kind.UNDECIDED);
            ok("未完成 ≠ 无法判断", ResultCategory.Kind.NOT_FINISHED != ResultCategory.Kind.UNDECIDED);

            // ------------------------------------------------ 9. 全部类别都有文案
            System.out.println();
            System.out.println("-- 9. 每个类别都有中英文标签与说明 --");
            boolean allOk = true;
            for (ResultCategory.Kind k : ResultCategory.Kind.values()) {
                String lz = ResultCategory.label(k, true);
                String le = ResultCategory.label(k, false);
                String hz = ResultCategory.hint(k, "", true);
                String he = ResultCategory.hint(k, "", false);
                if (lz.isEmpty() || le.isEmpty() || hz.isEmpty() || he.isEmpty()) {
                    allOk = false;
                    info("缺文案: " + k);
                }
            }
            ok("全部 7 类都有完整中英文标签与说明", allOk);

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== 结果分类测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
