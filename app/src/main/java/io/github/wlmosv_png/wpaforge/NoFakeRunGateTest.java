package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;

/**
 * **禁止假运行**门禁（2026-11-20 真机事故）。
 *
 * <h3>真机现场（OPPO PDRM00 / Android 13 / Shizuku shell）</h3>
 * <pre>
 *   已执行 6 · 已验证成功 0 · 已证实错误 0 · 未确定 0 · 环境错误 6
 *   进度 7.7% · 每条 10.9 秒 · 显示「Root 通道」
 * </pre>
 * 6 条全环境错误，任务还在跑 —— 纯粹消耗候选却无任何有效验证。
 *
 * <h3>本类钉住四件事</h3>
 * <ol>
 *   <li>连续环境错误必须整体熔断（不是只停单条）</li>
 *   <li>只有通道级 ERROR 才熔断，候选不合法不该误伤</li>
 *   <li>UI 通道名必须来自真实执行器，不能写死「Root 通道」</li>
 *   <li>开跑前必须探测净检通路</li>
 * </ol>
 */
public class NoFakeRunGateTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        NoFakeRunGateTest t = new NoFakeRunGateTest();
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
        envErrorBreaksWholeTask();
        onlyChannelErrorsBreak();
        uiChannelNameIsReal();
        preflightProbesNetCheck();
        statisticsSeparateEnvErrors();
    }

    /** 连续环境错误必须整体熔断。 */
    private void envErrorBreaksWholeTask() {
        String eng = readSource("audit/CrackEngine.java");
        ok("有 ENV_ERROR_BREAK 常量", eng.contains("ENV_ERROR_BREAK"));
        ok("常量值为 3（真机 6 条太多）",
                eng.contains("ENV_ERROR_BREAK = 3"));
        ok("主循环里有熔断 break", eng.contains("consecutiveEnvErrors >= ENV_ERROR_BREAK"));
        ok("熔断时调 onBlocked（不是静默继续）",
                eng.contains("l.onBlocked(L.s(\"通道不可用，已停止\""));
        ok("熔断信息说明「没有被真正验证」",
                eng.contains("没有被真正验证"));
    }

    /** 只有通道级 ERROR 才熔断。 */
    private void onlyChannelErrorsBreak() {
        ok("有 isChannelError 判定",
                CrackEngine.isChannelError("exception"));
        ok("空 reason 按通道错处理", CrackEngine.isChannelError(""));
        ok("invalid-candidate 不算通道错",
                !CrackEngine.isChannelError("invalid-candidate"));
        ok("not-submitted 不算通道错",
                !CrackEngine.isChannelError("not-submitted"));
        ok("attempt-mismatch 不算通道错",
                !CrackEngine.isChannelError("attempt-mismatch"));
        ok("not-capable 不算通道错",
                !CrackEngine.isChannelError("not-capable"));

        String eng = readSource("audit/CrackEngine.java");
        ok("熔断条件用 isChannelError",
                eng.contains("a.verdict == Verdict.ERROR && isChannelError(a.reason)"));
    }

    /** UI 通道名必须来自真实执行器。 */
    private void uiChannelNameIsReal() {
        String ui = readSource("MainActivity.java");
        ok("UI 不再写死「Root 通道 ·」",
                !ui.contains("\"Root 通道 · %.1f 秒/条"));
        ok("UI 从服务读 executorLabel", ui.contains("svc.executorLabel()"));

        String svc = readSource("CrackService.java");
        ok("服务有 executorLabel()", svc.contains("public String executorLabel()"));
        ok("标签含真实身份", svc.contains("\"Shizuku / \" + id.describe(false)"));
        ok("未接入时明确说未接入", svc.contains("\"未接入\""));
    }

    /** 开跑前必须探测净检通路。 */
    private void preflightProbesNetCheck() {
        String eng = readSource("audit/CrackEngine.java");
        ok("有净检通路探测", eng.contains("净检通路不通"));
        ok("不通时明说未消耗候选", eng.contains("未消耗任何候选"));
        ok("给出具体排查方向", eng.contains("Shizuku 服务是否仍在运行"));
    }

    /** 统计必须把环境错误与密码验证分开。 */
    private void statisticsSeparateEnvErrors() {
        String tally = readSource("audit/VerdictTally.java");
        ok("有独立 environmentErrors 计数",
                tally.contains("private int environmentErrors"));
        ok("环境错误不进 executed", tally.contains("environmentErrors++"));
        ok("有 selfConsistent 恒等式",
                tally.contains("public boolean selfConsistent()"));
    }
}
