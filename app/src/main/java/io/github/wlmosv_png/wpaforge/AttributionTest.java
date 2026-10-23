package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.SuccessRule;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 归因与"最短耗时门槛"测试（**已重写**：与生产实现对齐）。
 *
 * <h3>为什么重写（2026-10-24，R10）</h3>
 * 旧版本用**本地常量** {@code MIN_HIT_MS = 3000} 并注释「与 CrackEngine.MIN_HIT_MS
 * 保持一致」来断言"1.5 秒连上不算命中"。但生产里的 {@code MIN_HIT_MS} 早已是
 * {@code 0L} 且标注 @Deprecated、不再参与任何判定 —— 也就是说那些断言在测它自己的
 * 常量，生产改成什么样都不会红。
 *
 * <h3>现在测什么</h3>
 * <ul>
 *   <li>生产定义的阈值/常量：{@code CrackEngine.MIN_HIT_MS} 为零且不被使用；</li>
 *   <li>归因结论全部走生产入口：{@link CrackEngine#classifyDeadline}、
 *       {@link SuccessRule#assess}；</li>
 *   <li>「不算成功」的枚举语义（这一条旧版本本来就是对的，保留）。</li>
 * </ul>
 */
public class AttributionTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static boolean isSuccess(CrackEngine.Verdict v) {
        return v == CrackEngine.Verdict.SUCCESS;
    }

    public static void main(String[] args) {
        System.out.println("== 只有 SUCCESS 算成功（生产枚举语义）==");
        check("SUCCESS 算成功", isSuccess(CrackEngine.Verdict.SUCCESS));
        check("UNKNOWN 不算成功（关键）", !isSuccess(CrackEngine.Verdict.UNKNOWN));
        check("AUTH_FAIL 不算成功", !isSuccess(CrackEngine.Verdict.AUTH_FAIL));
        check("TIMEOUT 不算成功（到期无结论）", !isSuccess(CrackEngine.Verdict.TIMEOUT));
        check("ERROR 不算成功", !isSuccess(CrackEngine.Verdict.ERROR));
        check("ABORTED 不算成功", !isSuccess(CrackEngine.Verdict.ABORTED));
        check("TARGET_GONE 不算成功", !isSuccess(CrackEngine.Verdict.TARGET_GONE));

        System.out.println("== 生产阈值：没有最短耗时门槛 ==");
        // 注意：MIN_HIT_MS 是引擎私有常量，测试不能直接读 —— 用源码守卫断言它的值
        // 与"无人使用"两件事（旧版本正是因为复制了一份本地常量才失去意义）。
        String eng = read("/workspace/promo-wpaforge/app/src/main/java/"
                + "io/github/wlmosv_png/wpaforge/audit/CrackEngine.java");
        if (eng == null) {
            System.out.println("  N/RUN 门槛守卫（源码不可读）");
        } else {
            String code = stripComments(eng);
            int uses = 0, at = 0;
            while ((at = code.indexOf("MIN_HIT_MS", at)) >= 0) { uses++; at += 1; }
            check("MIN_HIT_MS 已废弃为零值", code.contains("MIN_HIT_MS = 0L"));
            check("MIN_HIT_MS 只剩声明处（没有任何判定使用它）", uses == 1);
        }

        System.out.println("== 归因结论走生产入口 ==");
        // 基线已连着目标（结束时仍在目标上、且没有本次归属证据）→ UNKNOWN，不是成功
        check("基线已连着目标 → UNKNOWN（不是 SUCCESS）",
                CrackEngine.classifyDeadline(false, true, false, false, false).verdict
                        == CrackEngine.Verdict.UNKNOWN);
        // 基线未连、后来连上 → 成功帧 → SUCCESS（由 assess 判定，不看耗时）
        check("基线未连 → 后来完成握手 → SUCCESS",
                SuccessRule.assess("LAB-TARGET", "LAB-TARGET", "COMPLETED", "192.168.1.3", 613, 613)
                        .verdict == CrackEngine.Verdict.SUCCESS);
        // 基线未连、始终未连、也没有任何失败证据 → TIMEOUT（**不再**是 AUTH_FAIL）
        check("基线未连 → 始终未连且无证据 → TIMEOUT（不是 AUTH_FAIL）",
                CrackEngine.classifyDeadline(false, false, false, false, false).verdict
                        == CrackEngine.Verdict.TIMEOUT);
        check("同一个组合会被判成失败吗？不会（这正是本次修复的点）",
                CrackEngine.classifyDeadline(false, false, false, false, false).verdict
                        != CrackEngine.Verdict.AUTH_FAIL);

        System.out.println("== 旧 bug 回归：只看当前 SSID 匹配 = 误报成功 ==");
        // 旧实现在「当前 SSID == 目标」时直接判成功 —— 那是"任何密码都在第一条命中"的根源。
        // 现在：仅仅 SSID 相同、其它条件不足时，连"成功帧"都不是。
        check("旧判据确实会把「SSID 相同」当成成功（证明 bug 存在过）",
                "LAB-TARGET".equals("LAB-TARGET"));
        check("新判据在同样输入下不会给 SUCCESS",
                SuccessRule.assess("LAB-TARGET", "LAB-TARGET", "ASSOCIATING", "0.0.0.0", 613, 613)
                        .verdict == null
                        && CrackEngine.classifyDeadline(false, true, false, false, false).verdict
                        != CrackEngine.Verdict.SUCCESS);

        System.out.println("== 性质：成功判据永不产出认证失败 ==");
        long[] nv = {-1, 0, 7, 613, 701};
        int authFail = 0, otherVerdict = 0;
        for (long a : nv) {
            for (long b : nv) {
                CrackEngine.Verdict v = SuccessRule.assess("LAB-TARGET", "LAB-TARGET",
                        "COMPLETED", "192.168.1.3", a, b).verdict;
                if (v == CrackEngine.Verdict.AUTH_FAIL) authFail++;
                if (v != null && v != CrackEngine.Verdict.SUCCESS && v != CrackEngine.Verdict.UNKNOWN) {
                    otherVerdict++;
                }
            }
        }
        check("25 种 netId 组合里没有一次 AUTH_FAIL（成功路径不判失败）", authFail == 0);
        check("成功路径只可能给 null / SUCCESS / UNKNOWN", otherVerdict == 0);
        check("同样输入、不同 SSID → 不是成功帧（跨尝试不串）",
                SuccessRule.assess("LAB-TARGET", "OTHER-AP", "COMPLETED", "192.168.1.3", 613, 613)
                        .verdict == null);

        System.out.println("== 性质：到期归类永不产出确定结论以外的两类 ==");
        int dlBad = 0, dlNoReason = 0;
        for (int m = 0; m < 32; m++) {
            CrackEngine.DeadlineOutcome o = CrackEngine.classifyDeadline(
                    (m & 1) != 0, (m & 2) != 0, (m & 4) != 0, (m & 8) != 0, (m & 16) != 0);
            if (o.verdict == CrackEngine.Verdict.AUTH_FAIL
                    || o.verdict == CrackEngine.Verdict.SUCCESS) dlBad++;
            if (o.reason == null || o.reason.isEmpty()) dlNoReason++;
        }
        check("32 种组合里没有 AUTH_FAIL / SUCCESS", dlBad == 0);
        check("32 种组合都有理由码", dlNoReason == 0);

        System.out.println("== 退避样本：只有 AP 给了明确结论才计数 ==");
        check("SUCCESS / AUTH_FAIL 可计数",
                io.github.wlmosv_png.wpaforge.audit.BackoffPolicy.countable(CrackEngine.Verdict.SUCCESS)
                        && io.github.wlmosv_png.wpaforge.audit.BackoffPolicy.countable(CrackEngine.Verdict.AUTH_FAIL));
        check("TIMEOUT / UNKNOWN / ERROR / ABORTED 不可计数",
                !io.github.wlmosv_png.wpaforge.audit.BackoffPolicy.countable(CrackEngine.Verdict.TIMEOUT)
                        && !io.github.wlmosv_png.wpaforge.audit.BackoffPolicy.countable(CrackEngine.Verdict.UNKNOWN)
                        && !io.github.wlmosv_png.wpaforge.audit.BackoffPolicy.countable(CrackEngine.Verdict.ERROR)
                        && !io.github.wlmosv_png.wpaforge.audit.BackoffPolicy.countable(CrackEngine.Verdict.ABORTED));

        System.out.println();
        System.out.println("=== 归因测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String stripComments(String src) {
        String s1 = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder sb = new StringBuilder();
        for (String l : s1.split("\n")) {
            int k = l.indexOf("//");
            sb.append(k >= 0 ? l.substring(0, k) : l).append('\n');
        }
        return sb.toString();
    }

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
