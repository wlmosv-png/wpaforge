package io.github.wlmosv_png.wpaforge;

/**
 * **只读诊断入口**门禁（2026-11-20 真机验收准备）。
 *
 * <h3>硬约束</h3>
 * <ol>
 *   <li>诊断必须复用**正式 Executor**（{@code ShizukuChannel.exec}），
 *       不能另写一套测试通道 —— 否则诊断通过不代表真实执行通过。</li>
 *   <li>只允许四条只读命令，绝不出现 connect/forget/disconnect。</li>
 *   <li>每条必须输出：执行器 / 身份 / 耗时 / exitCode / timeout / stdout / stderr。</li>
 * </ol>
 */
public class ReadonlyDiagTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        ReadonlyDiagTest t = new ReadonlyDiagTest();
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
        diagUsesFormalExecutor();
        diagOnlyReadonlyCommands();
        diagHasFullFields();
        diagNoSilentFallback();
    }

    /** 诊断必须复用正式 Executor。 */
    private void diagUsesFormalExecutor() {
        String app = readSource("App.java");
        ok("诊断用 ShizukuChannel（正式 Executor）",
                app.contains("new io.github.wlmosv_png.wpaforge.cap.ShizukuChannel(App.this)"));
        ok("诊断调 shz.exec", app.contains("shz.exec(rc, 8000)"));
        ok("有只读命令诊断段", app.contains("只读命令诊断（正式 Executor）"));
        // 不能另写一套 shell 调用
        ok("诊断不自己起 su",
                !app.contains("RootProber.exec(null, new String[]{\"su\", \"-c\", rc"));
    }

    /** 只允许四条只读命令。 */
    private void diagOnlyReadonlyCommands() {
        String app = readSource("App.java");
        ok("有 id", app.contains("\"id\","));
        ok("有 cmd wifi status", app.contains("\"cmd wifi status\","));
        ok("有 cmd wifi list-networks", app.contains("\"cmd wifi list-networks\","));
        ok("有 dumpsys wifi", app.contains("\"dumpsys wifi\""));
        // 绝不能出现在诊断数组里
        ok("诊断不含 connect-network", !app.contains("\"cmd wifi connect-network\""));
        ok("诊断不含 forget-network", !app.contains("\"cmd wifi forget-network\""));
        ok("诊断不含 disconnect", !app.contains("\"cmd wifi disconnect\""));
        ok("诊断不含 set-wifi-enabled", !app.contains("\"cmd wifi set-wifi-enabled\""));
    }

    /** 每条必须输出完整字段。 */
    private void diagHasFullFields() {
        String app = readSource("App.java");
        ok("输出执行器", app.contains("执行器="));
        ok("输出身份", app.contains("身份="));
        ok("输出耗时", app.contains("耗时="));
        ok("输出 exit", app.contains("exit="));
        ok("输出 timeout", app.contains("timeout="));
        ok("输出 stdout 字节数", app.contains("stdout("));
        ok("输出 stderr", app.contains("stderr("));
        ok("有失败分类", app.contains("失败分类="));
        ok("分类含 TIMEOUT", app.contains("TIMEOUT"));
        ok("分类含 NONZERO_EXIT", app.contains("NONZERO_EXIT"));
    }

    /** 失败不能静默回退到其他通道。 */
    private void diagNoSilentFallback() {
        String app = readSource("App.java");
        // 诊断段内不能出现「失败就换 su」
        int from = app.indexOf("只读命令诊断（正式 Executor）");
        int to = app.indexOf("Shizuku 探测异常", from);
        String seg = (from > 0 && to > from) ? app.substring(from, to) : "";
        ok("诊断段内无 su 回落", !seg.contains("\"su\""));
        ok("失败只记录不换通道", seg.contains("失败分类="));
    }
}
