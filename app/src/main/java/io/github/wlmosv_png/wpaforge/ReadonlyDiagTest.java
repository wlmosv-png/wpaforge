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
        // 2026-11-20 第二阶段：诊断改走 ExecutorProvider + CommandExecutor（与正式任务同源）。
        // **意图不变**：诊断必须用正式执行器，不得另写一套通道。
        ok("诊断走 ExecutorProvider（唯一选择入口）",
                app.contains("io.github.wlmosv_png.wpaforge.cap.ExecutorProvider.resolve"));
        ok("诊断走 CommandExecutor（唯一执行路径）",
                app.contains("CommandExecutor"));
        ok("诊断不再直接调 shz.exec", !app.contains("shz.exec(rc, 8000)"));
        ok("有只读命令诊断段", app.contains("只读命令诊断（正式 Executor）"));
        // 七项输出必须都在
        ok("输出请求模式", app.contains("请求模式="));
        ok("输出实际模式", app.contains("实际模式="));
        ok("输出 Executor 类型", app.contains("Executor 类型="));
        ok("输出实际进程 UID", app.contains("实际进程 UID="));
        ok("输出 SELinux context", app.contains("SELinux context="));
        ok("输出 Binder 状态", app.contains("Binder 状态="));
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
        // 分类现在由 CommandExecutor 统一定义，诊断直接输出它的值。
        ok("分类含 TIMEOUT", readSource("cap/CommandExecutor.java").contains("F_TIMEOUT"));
        ok("分类含 NONZERO_EXIT",
                readSource("cap/CommandExecutor.java").contains("F_NONZERO_EXIT"));
        ok("诊断输出失败分类", app.contains("失败分类="));
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
