package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;
import io.github.wlmosv_png.wpaforge.audit.VerdictTally;

/**
 * 「已验证成功」独立列门禁（2026-11-20，任务书第十部分）。
 *
 * <p>要求：「成功数量必须独立显示，不能把 SUCCESS 隐藏在其他分类里」，
 * 且「执行候选数 = 所有已执行最终分类之和」恒成立。
 */
public class SuccessColumnTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        SuccessColumnTest t = new SuccessColumnTest();
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

    private void run() {
        successIsCounted();
        successIsSeparate();
        identityHolds();
        uiHasSuccessColumn();
        engineExposesSuccess();
    }

    private void successIsCounted() {
        VerdictTally t = new VerdictTally();
        t.add(CrackEngine.Verdict.SUCCESS, EvidenceGrade.Level.DIRECT);
        ok("SUCCESS+DIRECT 计入 success", t.success() == 1);
        ok("SUCCESS 计入 executed", t.executed() == 1);
        ok("统计自洽", t.selfConsistent());
    }

    /** 成功不能混进「已证实错误」或「未确定」。 */
    private void successIsSeparate() {
        VerdictTally t = new VerdictTally();
        t.add(CrackEngine.Verdict.SUCCESS, EvidenceGrade.Level.DIRECT);
        ok("不混进已证实错误", t.confirmedFail() == 0);
        ok("不混进推断失败", t.inferred() == 0);
        ok("不混进无结论", t.inconclusive() == 0);
        ok("不混进网络级", t.networkAuthFail() == 0);
    }

    /** 恒等式：executed = 已证实 + 推断 + 网络级 + 无结论 + 成功。 */
    private void identityHolds() {
        VerdictTally t = new VerdictTally();
        t.add(CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.DIRECT);
        t.add(CrackEngine.Verdict.AUTH_FAIL, EvidenceGrade.Level.INFERRED);
        t.add(CrackEngine.Verdict.NETWORK_AUTH_FAIL, EvidenceGrade.Level.NONE);
        t.add(CrackEngine.Verdict.TIMEOUT, EvidenceGrade.Level.NONE);
        t.add(CrackEngine.Verdict.SUCCESS, EvidenceGrade.Level.DIRECT);
        ok("五类混合后自洽", t.selfConsistent());
        ok("executed=5", t.executed() == 5);
        ok("confirmedFail=1", t.confirmedFail() == 1);
        ok("inferred=1", t.inferred() == 1);
        ok("networkAuthFail=1", t.networkAuthFail() == 1);
        ok("inconclusive=1", t.inconclusive() == 1);
        ok("success=1", t.success() == 1);
        ok("和等于 executed",
                t.confirmedFail() + t.inferred() + t.networkAuthFail()
                        + t.inconclusive() + t.success() == t.executed());
    }

    /** 界面必须有「已验证成功」列，且用绿色。 */
    private void uiHasSuccessColumn() {
        String ui = readSource("MainActivity.java");
        ok("界面有 statHitView 字段", ui.contains("statHitView"));
        ok("界面有「已验证成功」标签",
                ui.contains("\"已验证成功\"") || ui.contains("已验证成功"));
        ok("成功用 OK_GREEN", ui.contains("success > 0 ? OK_GREEN"));
        ok("onTallyFull 已实现", ui.contains("onTallyFull"));
    }

    /** 引擎必须暴露 successCount。 */
    private void engineExposesSuccess() {
        String eng = readSource("audit/CrackEngine.java");
        ok("引擎有 successCount()", eng.contains("public int successCount()"));
        String svc = readSource("CrackService.java");
        ok("服务回调带 success", svc.contains("onTallyFull"));
        ok("服务传 successCount", svc.contains("eng.successCount()"));
    }
}
