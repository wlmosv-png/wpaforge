package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.cap.ShizukuChannel;

/**
 * **执行器选择**门禁（2026-11-20 真机根因）。
 *
 * <h3>两个根因</h3>
 * <ol>
 *   <li><b>shizukuMode 只认 S_READY</b>：`usable()` 接受 S_READY 和
 *       S_READY_NOT_SHELL（root 身份），但 shizukuMode 只判 S_READY。
 *       → Shizuku 是 root 身份时走 su → su 未授权 → 每条超时 10.9 秒。</li>
 *   <li><b>runModeId 用缓存</b>：RuntimeMode.modeId() 优先返回缓存，
 *       用户中途切模式后执行时仍用旧模式。</li>
 * </ol>
 */
public class ExecutorSelectionTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        ExecutorSelectionTest t = new ExecutorSelectionTest();
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
        shizukuModeAcceptsBothStates();
        runModeFromPrefsNotCache();
        resultHasDiagnostics();
        shizukuIdentityThreeWay();
    }

    /** 关键：shizukuMode 必须接受 usable() 的全部状态。 */
    private void shizukuModeAcceptsBothStates() {
        String svc = readSource("CrackService.java");
        // 不能再用 state() == S_READY 作为唯一判据
        ok("shizukuMode 不再只判 S_READY",
                !svc.contains("&& s0.state() == io.github.wlmosv_png.wpaforge.cap.ShizukuChannel.S_READY"));
        ok("shizukuMode 用 usable()",
                svc.contains("&& s0 != null && s0.usable()"));
        // 根因注释保留
        ok("注释说明 root 身份也会走 su 的坑",
                svc.contains("Shizuku 是 root 身份时 → shizukuMode = false"));
    }

    /** runModeId 必须用用户意愿，不用缓存。 */
    private void runModeFromPrefsNotCache() {
        String svc = readSource("CrackService.java");
        ok("runModeId 来自 Prefs.requestedMode",
                svc.contains("Prefs.requestedMode(this)"));
        ok("不再用 RuntimeMode.modeId 作为执行判据",
                !svc.contains("final int runModeId = io.github.wlmosv_png.wpaforge.cap.RuntimeMode.modeId"));
        ok("注释说明缓存风险",
                svc.contains("缓存可能是上一次"));
    }

    /** Result 必须有结构化诊断字段。 */
    private void resultHasDiagnostics() {
        String shz = readSource("cap/ShizukuChannel.java");
        ok("有 executorId", shz.contains("public String executorId"));
        ok("有 executorIdentity", shz.contains("public String executorIdentity"));
        ok("有 startedAtMs/endedAtMs",
                shz.contains("public long startedAtMs") && shz.contains("public long endedAtMs"));
        ok("有 stdout/stderr 分开",
                shz.contains("public String stdout") && shz.contains("public String stderr"));
        ok("有 cancelled 标记", shz.contains("public boolean cancelled"));
        ok("有 exceptionClass", shz.contains("public String exceptionClass"));
        ok("有 elapsedMs()", shz.contains("public long elapsedMs()"));
        ok("有 brief() 一行诊断", shz.contains("public String brief()"));
        ok("brief 不含输出正文（只说字节数）",
                shz.contains("\"B\"") && shz.contains("stdout.length()"));
    }

    /** Shizuku 身份必须三态区分。 */
    private void shizukuIdentityThreeWay() {
        String shz = readSource("cap/ShizukuChannel.java");
        ok("有 S_READY（shell）", shz.contains("S_READY = 3"));
        ok("有 S_READY_NOT_SHELL（root/system）", shz.contains("S_READY_NOT_SHELL = 4"));
        ok("usable() 接受两种",
                shz.contains("return s == S_READY || s == S_READY_NOT_SHELL;"));
        ok("有 identity() 读真实 uid", shz.contains("public ExecutorIdentity identity()"));
    }
}
