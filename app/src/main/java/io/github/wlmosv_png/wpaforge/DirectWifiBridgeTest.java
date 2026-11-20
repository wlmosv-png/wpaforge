package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.cap.ExecutorIdentity;

/**
 * **直调桥 + 执行身份**门禁（2026-11-20）。
 *
 * <h3>要钉住的两件事</h3>
 * <ol>
 *   <li><b>身份分级</b>：「Shizuku 已授权」≠「拿到 root」。uid 0 能读
 *       WifiConfigStore.xml，uid 2000 读不到（SELinux 实测）。</li>
 *   <li><b>直调优先</b>：净检必须先走 IWifiManager 直调（无 shell/管道开销），
 *       只有直调不可用（-2）才回落 shell。这是 11 秒事故的根治。</li>
 * </ol>
 */
public class DirectWifiBridgeTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        DirectWifiBridgeTest t = new DirectWifiBridgeTest();
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
        identityKinds();
        onlyRootReadsConfigStore();
        shellCanRunCmdWifi();
        directBridgePreferred();
        bridgeReturnsStructured();
        forgetNeedsReadback();
    }

    /** 三种身份的识别必须准确。 */
    private void identityKinds() {
        ok("uid 0 → ROOT", ExecutorIdentity.kindOf(0) == ExecutorIdentity.Kind.ROOT);
        ok("uid 1000 → SYSTEM", ExecutorIdentity.kindOf(1000) == ExecutorIdentity.Kind.SYSTEM);
        ok("uid 2000 → SHELL", ExecutorIdentity.kindOf(2000) == ExecutorIdentity.Kind.SHELL);
        ok("uid 10601 → APP", ExecutorIdentity.kindOf(10601) == ExecutorIdentity.Kind.APP);
        ok("uid -1 → UNKNOWN", ExecutorIdentity.kindOf(-1) == ExecutorIdentity.Kind.UNKNOWN);
    }

    /** 只有 root 才能读 WifiConfigStore.xml（实测 shell 被 SELinux 拦）。 */
    private void onlyRootReadsConfigStore() {
        ok("root 可读配置库",
                ExecutorIdentity.of(0, "u:r:magisk:s0", "Shizuku").canReadConfigStore());
        ok("shell 不可读配置库（SELinux 实测）",
                !ExecutorIdentity.of(2000, "u:r:shell:s0", "Shizuku").canReadConfigStore());
        ok("system 不假定可读（宁可少说）",
                !ExecutorIdentity.of(1000, "", "Shizuku").canReadConfigStore());
        ok("app 不可读", !ExecutorIdentity.app(10601).canReadConfigStore());
    }

    /** shell 及以上都能跑 cmd wifi 入口。 */
    private void shellCanRunCmdWifi() {
        ok("root 能跑 cmd wifi",
                ExecutorIdentity.of(0, "", "Root").canRunCmdWifi());
        ok("shell 能跑 cmd wifi",
                ExecutorIdentity.of(2000, "", "Shizuku").canRunCmdWifi());
        ok("app 不能跑 cmd wifi", !ExecutorIdentity.app(10601).canRunCmdWifi());
        ok("unknown 不能跑", !ExecutorIdentity.unknown("x").canRunCmdWifi());
    }

    /** 净检必须优先走直调。 */
    private void directBridgePreferred() {
        String eng = readSource("audit/CrackEngine.java");
        ok("引擎有 DirectWifi 接缝", eng.contains("public interface DirectWifi"));
        ok("引擎能注入直调", eng.contains("public void setDirectWifi"));
        int directAt = eng.indexOf("DirectWifi dw = direct;");
        int shellAt = eng.indexOf("if (shell == null) return false;",
                eng.indexOf("private boolean forgetTargetByShell"));
        ok("直调分支在 shell 之前", directAt > 0 && shellAt > directAt);
        ok("直调不可用才回落（-2 语义）", eng.contains("if (id == -2) break;"));

        String svc = readSource("CrackService.java");
        ok("服务注入直调桥", svc.contains("engine.setDirectWifi("));
        // 2026-11-20：改用统一判定 ChannelReadiness（缺陷 A 收口），
        // 断言意图不变：必须接受 S_READY 和 S_READY_NOT_SHELL 两种状态。
        // 2026-11-20 第二阶段：直调注入改为只认 plan.kind（与执行器选择同源）。
        // **意图不变**：只有本次任务固定用 Shizuku 时才注入直调桥。
        ok("只在本次固定为 Shizuku 时注入",
                svc.contains("plan.kind == io.github.wlmosv_png.wpaforge.cap.ExecutorProvider.Kind.SHIZUKU")
                        && svc.contains("&& dsz != null"));
        ok("直调注入不再自己判状态",
                !svc.contains("ChannelReadiness\n                    .usable(dsz.state())"));
        ok("直调注入不再只认 S_READY",
                !svc.contains("dsz.state() == io.github.wlmosv_png.wpaforge.cap.ShizukuChannel.S_READY"));
        ok("日志说明启用了直调", svc.contains("IWifiManager 直调"));
    }

    /** 桥必须返回结构化结果，不是一句文本。 */
    private void bridgeReturnsStructured() {
        String br = readSource("cap/WifiServiceBridge.java");
        ok("有 Outcome 结构", br.contains("public static final class Outcome"));
        ok("Outcome 带 ok/value/reason", br.contains("public final boolean ok")
                && br.contains("public final T value")
                && br.contains("public final String reason"));
        ok("用 ShizukuBinderWrapper", br.contains("ShizukuBinderWrapper"));
        ok("用 SystemServiceHelper", br.contains("SystemServiceHelper"));
        ok("冒充 shell 包名", br.contains("com.android.shell"));
        ok("有版本签名映射", br.contains("static Method method(Object svc, String name, int sdk)"));
    }

    /** forget 返回 true 不代表删成功，必须回读。 */
    private void forgetNeedsReadback() {
        String br = readSource("cap/WifiServiceBridge.java");
        ok("forget 注释点明需回读", br.contains("不代表配置真的被删了"));
        String eng = readSource("audit/CrackEngine.java");
        // 直调分支里必须有回读
        int from = eng.indexOf("DirectWifi dw = direct;");
        int to = eng.indexOf("if (shell == null) return false;", from);
        String seg = (from > 0 && to > from) ? eng.substring(from, to) : "";
        ok("直调分支有回读确认", seg.contains("dw.netIdOf(ssid)") && seg.contains("chk < 0"));
    }
}
