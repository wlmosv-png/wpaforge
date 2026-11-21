package io.github.wlmosv_png.wpaforge.ui;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;

/**
 * {@link ResultCategory} 的语义修正测试（2026-11-22 新增）。
 *
 * <h3>它钉死两处真机证伪的 UI 语义</h3>
 * <ol>
 *   <li>{@code netid-conflict} 不再被当成「目标曾连上过」。
 *       真机 {@code 2.4G-3} 连续 14 次 {@code AUTHENTICATION_FAILURE}，
 *       一次都没连上，却被提示「请人工复核」——那是在让用户复核不存在的命中。</li>
 *   <li>「系统记录了失败但原因不是密钥错误」与「什么都没观察到」必须分开说。
 *       本机 {@code whoami} 的 14 条全是 {@code NEW_CONNECTION_ATTEMPT}，
 *       混进「没拿到证据」会让用户以为是应用没跑。</li>
 * </ol>
 *
 * <h3>不变量</h3>
 * <ul>
 *   <li>本类只做文案与分类，**不改 Verdict**；</li>
 *   <li>{@code SUSPECTED_WRONG} 的文案必须明确写「未证实」；</li>
 *   <li>任何 UNKNOWN 分支都不得暗示「已证实」。</li>
 * </ul>
 */
public final class ResultCategorySemanticsTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void ok(String name, boolean cond) { ok(name, cond, ""); }

    private static void info(String s) { System.out.println("  ·    " + s); }

    public static void main(String[] args) {
        System.out.println("== ResultCategory 语义（2026-11-22 修正）==");
        try {
            // ════════════════════════════ 1. netid-conflict 不再是「曾连上」
            System.out.println();
            System.out.println("-- 1. ★netid-conflict 不再被当成「曾连上」--");
            info("旧行为：targetMayHaveConnected(\"netid-conflict\") = true"
                    + " → 提示「请人工复核」");
            ok("★现在返回 false",
                    !ResultCategory.targetMayHaveConnected("netid-conflict"));
            ok("status-unclear 仍返回 true（它确实由「status 显示目标」触发）",
                    ResultCategory.targetMayHaveConnected("status-unclear"));
            ok("on-target-unattributed 仍返回 true",
                    ResultCategory.targetMayHaveConnected("on-target-unattributed"));
            ok("null → false", !ResultCategory.targetMayHaveConnected(null));
            ok("空串 → false", !ResultCategory.targetMayHaveConnected(""));

            String hintNetId = ResultCategory.hint(
                    ResultCategory.Kind.UNDECIDED, "netid-conflict", true);
            info("netid-conflict 的提示 = " + hintNetId);
            ok("★提示里不再出现「曾连上」", !hintNetId.contains("曾连上"));
            ok("★提示里不再要求「人工复核」", !hintNetId.contains("人工复核"));
            ok("提示说清了真实原因（netId 不符）", hintNetId.contains("netId"));
            ok("提示明确说「下次会重试」", hintNetId.contains("重试"));

            // ════════════════════════════ 2. 系统记录失败 vs 什么都没观察到
            System.out.println();
            System.out.println("-- 2. ★区分「系统记录了失败」与「什么都没观察到」--");
            ok("not-connected-terminated 属于「系统记录了失败」",
                    ResultCategory.systemRecordedFailure("not-connected-terminated"));
            ok("no-evidence 属于「系统记录了失败」",
                    ResultCategory.systemRecordedFailure("no-evidence"));
            ok("netid-conflict 不属于（它是归属问题，不是失败记录）",
                    !ResultCategory.systemRecordedFailure("netid-conflict"));
            ok("null → false", !ResultCategory.systemRecordedFailure(null));

            String hintRec = ResultCategory.hint(
                    ResultCategory.Kind.UNDECIDED, "not-connected-terminated", true);
            info("not-connected-terminated 的提示 = " + hintRec);
            ok("★提示明确说「不是密钥错误」", hintRec.contains("不是「密钥错误」"));
            ok("提示给了例子（关联超时 / AP 无响应）",
                    hintRec.contains("关联超时") || hintRec.contains("AP 无响应"));
            ok("提示明确说「未记录」", hintRec.contains("未记录"));

            // 默认分支仍保持原意
            String hintDefault = ResultCategory.hint(
                    ResultCategory.Kind.UNDECIDED, "something-new", true);
            info("未知 reason 的提示 = " + hintDefault);
            ok("未知 reason 落到默认文案", hintDefault.contains("没拿到可归因的证据"));

            // ════════════════════════════ 3. 分类与底层判定一致
            System.out.println();
            System.out.println("-- 3. 分类与底层 Verdict/grade 一致 --");
            Object[][] cases = {
                    {CrackEngine.Verdict.SUCCESS, EvidenceGrade.Level.DIRECT,
                            ResultCategory.Kind.VERIFIED_OK},
                    {CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.DIRECT,
                            ResultCategory.Kind.PROVEN_WRONG},
                    {CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.INFERRED,
                            ResultCategory.Kind.SUSPECTED_WRONG},
                    {CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.NONE,
                            ResultCategory.Kind.SUSPECTED_WRONG},
                    {CrackEngine.Verdict.NETWORK_AUTH_FAIL, EvidenceGrade.Level.NONE,
                            ResultCategory.Kind.NETWORK_FAIL},
                    {CrackEngine.Verdict.TIMEOUT, EvidenceGrade.Level.NONE,
                            ResultCategory.Kind.NOT_FINISHED},
                    {CrackEngine.Verdict.INCOMPLETE, EvidenceGrade.Level.NONE,
                            ResultCategory.Kind.NOT_FINISHED},
                    {CrackEngine.Verdict.TERMINATED_UNKNOWN, EvidenceGrade.Level.NONE,
                            ResultCategory.Kind.NOT_FINISHED},
                    {CrackEngine.Verdict.ABORTED, EvidenceGrade.Level.NONE,
                            ResultCategory.Kind.NOT_FINISHED},
                    {CrackEngine.Verdict.ERROR, EvidenceGrade.Level.NONE,
                            ResultCategory.Kind.ENV_ERROR},
                    {CrackEngine.Verdict.UNKNOWN, EvidenceGrade.Level.NONE,
                            ResultCategory.Kind.UNDECIDED},
            };
            for (Object[] c : cases) {
                CrackEngine.Verdict v = (CrackEngine.Verdict) c[0];
                EvidenceGrade.Level g = (EvidenceGrade.Level) c[1];
                ResultCategory.Kind want = (ResultCategory.Kind) c[2];
                ResultCategory.Kind got = ResultCategory.of(v, g, "");
                ok(v + "/" + g + " → " + want, got == want, "" + got);
            }

            // 环境类 reason 必须归 ENV_ERROR
            for (String r : new String[]{"gate-stale_config", "gate-unreadable",
                    "gate-disconnect_failed", "not-attempted", "channel-no-response"}) {
                ok("UNKNOWN + " + r + " → ENV_ERROR",
                        ResultCategory.of(CrackEngine.Verdict.UNKNOWN,
                                EvidenceGrade.Level.NONE, r) == ResultCategory.Kind.ENV_ERROR);
            }
            ok("UNKNOWN + netid-conflict → UNDECIDED（不是环境问题）",
                    ResultCategory.of(CrackEngine.Verdict.UNKNOWN,
                            EvidenceGrade.Level.NONE, "netid-conflict")
                            == ResultCategory.Kind.UNDECIDED);

            // ════════════════════════════ 4. 文案不得撒谎
            System.out.println();
            System.out.println("-- 4. ★文案不得暗示「已证实」--");
            String sus = ResultCategory.hint(ResultCategory.Kind.SUSPECTED_WRONG, "", true);
            info("疑似错误 = " + sus);
            ok("★疑似错误明确写「无法证明」", sus.contains("无法证明"));
            ok("★疑似错误明确写「未记录」", sus.contains("未记录"));

            String nf = ResultCategory.hint(ResultCategory.Kind.NETWORK_FAIL, "", true);
            info("网络级失败 = " + nf);
            ok("★网络级失败明确写「绑不到这条口令」", nf.contains("绑不到"));

            String und = ResultCategory.hint(ResultCategory.Kind.UNDECIDED,
                    "no-evidence", true);
            info("无结论 = " + und);
            ok("★无结论不得出现「密码错误」字样", !und.contains("密码错误"));

            // 七个 Kind 都有非空标签
            for (ResultCategory.Kind k : ResultCategory.Kind.values()) {
                ok("Kind." + k + " 有中文标签",
                        ResultCategory.label(k, true) != null
                                && !ResultCategory.label(k, true).isEmpty());
                ok("Kind." + k + " 有英文标签",
                        ResultCategory.label(k, false) != null
                                && !ResultCategory.label(k, false).isEmpty());
            }

            // ════════════════════════════ 5. 静态边界
            System.out.println();
            System.out.println("-- 5. ★静态核对：本类不产生 Verdict --");
            // 只看**代码行**（去掉注释）：注释里合法地解释「不参与 persistable」
            // 这类契约，按整文件字符串判断会把说明文字当成违规（第一版就误报了）。
            String code = stripComments(readSrc("ui/ResultCategory.java"));
            ok("代码里没有 new Attempt", !code.contains("new Attempt"));
            ok("代码里没有构造 AUTH_FAIL", !code.contains("Verdict.AUTH_FAIL"));
            ok("代码里没有调用 persistable", !code.contains("persistable("));
            ok("代码里没有 triedSet 操作", !code.toLowerCase().contains("triedset"));
            ok("代码里没有 EvidenceGrade 判定调用",
                    !code.contains("EvidenceGrade.persistable")
                            && !code.contains("EvidenceGrade.advancesCursor"));
            // 注释里必须明确写出这条边界（防止后人误以为它能改判定）
            String raw = readSrc("ui/ResultCategory.java");
            ok("注释里明确声明「不改变判定」", raw.contains("不改变判定"));

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== ResultCategory 语义测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String readSrc(String rel) {
        try {
            String p = io.github.wlmosv_png.wpaforge.TestPaths.javaPkgDir() + rel;
            byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(p));
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 去掉块注释与行注释，只留代码（避免把说明文字当成违规）。 */
    private static String stripComments(String src) {
        if (src == null) return "";
        String s1 = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder sb = new StringBuilder();
        for (String l : s1.split("\n")) {
            int k = l.indexOf("//");
            sb.append(k >= 0 ? l.substring(0, k) : l).append('\n');
        }
        return sb.toString();
    }
}
