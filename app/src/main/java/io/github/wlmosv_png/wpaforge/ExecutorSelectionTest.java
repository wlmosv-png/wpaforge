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

    /**
     * **执行器选择必须收口到唯一入口**（2026-11-20 第二阶段）。
     *
     * <p>旧断言钉的是「shizukuMode 写法」这个**字符串形态**；现在判据收口到
     * {@code ExecutorProvider} + {@code plan.kind}，字符串形态必然变。
     * 但**意图一字不改**：
     * <ol>
     *   <li>不允许各调用点自己判 Shizuku 状态；<\/li>
     *   <li>Shizuku 的两种可用状态（shell \/ 非 shell）都必须被接受；<\/li>
     *   <li>不允许回到「只认 S_READY」的旧坑。<\/li>
     * <\/ol>
     */
    private void shizukuModeAcceptsBothStates() {
        String svc = readSource("CrackService.java");
        // ① 不允许任何地方拿 state() == S_READY 当可用性判据
        ok("不再出现 state() == S_READY 当判据",
                !svc.contains("s0.state() == io.github.wlmosv_png.wpaforge.cap.ShizukuChannel.S_READY")
                        && !svc.contains("dsz.state() == io.github.wlmosv_png.wpaforge.cap.ShizukuChannel.S_READY"));
        // ② 执行分流只认 plan.kind（与选择同源）
        ok("执行分流走 plan.kind",
                svc.contains("plan.kind == io.github.wlmosv_png.wpaforge.cap.ExecutorProvider.Kind.SHIZUKU"));
        // ③ 选择入口唯一
        ok("选择入口唯一（ExecutorProvider.resolve）",
                svc.contains("io.github.wlmosv_png.wpaforge.cap.ExecutorProvider.resolve(this)"));
        ok("不再自己发 su 探测后直接判模式",
                !svc.contains("final boolean suUsableNow"));
        // ④ ChannelReadiness 仍是可用性的唯一判据（在 ExecutorProvider 内部）
        String prov = readSource("cap/ExecutorProvider.java");
        ok("ExecutorProvider 用 ChannelReadiness 判可用性",
                prov.contains("ChannelReadiness.mayStartTask(verdict)")
                        && prov.contains("ChannelReadiness.of(st)"));
    }

    /** **模式必须来自用户意愿 + 真实探测**，不用缓存。 */
    private void runModeFromPrefsNotCache() {
        String prov = readSource("cap/ExecutorProvider.java");
        String svc = readSource("CrackService.java");
        ok("用户意愿来自 Prefs.requestedMode",
                prov.contains("Prefs.requestedMode(ctx)"));
        ok("模式解析走 ModeResolver（唯一实现）",
                prov.contains("ModeResolver.resolve(requested, suOk, shzOk)"));
        ok("不再用 RuntimeMode.modeId 作为执行判据",
                !svc.contains("final int runModeId = io.github.wlmosv_png.wpaforge.cap.RuntimeMode.modeId"));
        ok("注释说明缓存风险",
                svc.contains("缓存可能是上一次"));
        // su 探测只起一次（旧写法连着调两次，任一抖动就翻结论）
        ok("su 探测只起一次", prov.contains("boolean suOk = probeSu();"));
        ok("su 判据看 uid=0（不是看文件在不在）",
                prov.contains("out.contains(\"uid=0\")"));
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
