package io.github.wlmosv_png.wpaforge;

/**
 * Shizuku **通道存活**门禁（2026-11-20）。
 *
 * <p>任务书硬要求：「Shizuku 未授权、服务退出、Binder 断开后仍继续使用旧通道」
 * 必须被禁止。本类用结构断言钉住这条 —— 因为真正的存活检查要碰 Binder，
 * 桌面 JVM 跑不了，所以断言「每次执行前确实做了检查」这个结构事实。
 */
public class ShizukuLivenessTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        ShizukuLivenessTest t = new ShizukuLivenessTest();
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
        execRechecksLiveness();
        livenessChecksBinderAndPermission();
        stateReflectsFailure();
        noStaleCacheInExecPath();
    }

    /** exec 必须先做存活检查，不能直接用缓存的 usable()。 */
    private void execRechecksLiveness() {
        String src = readSource("cap/ShizukuChannel.java");
        int execAt = src.indexOf("public Result exec(String command, long timeoutMs)");
        ok("exec 存在", execAt > 0);
        int end = src.indexOf("\n    }", execAt);
        String body = end > execAt ? src.substring(execAt, end) : "";
        ok("exec 内调用 stillUsable()", body.contains("stillUsable()"));
        ok("exec 不再直接调 usable()", !body.contains("if (!usable())"));
    }

    /** 存活检查必须同时查 Binder 与授权位。 */
    private void livenessChecksBinderAndPermission() {
        String src = readSource("cap/ShizukuChannel.java");
        int at = src.indexOf("private boolean stillUsable()");
        ok("stillUsable 存在", at > 0);
        int end = src.indexOf("\n    }", at);
        String body = end > at ? src.substring(at, end) : "";
        ok("检查 pingBinder", body.contains("pingBinder"));
        ok("检查 checkSelfPermission", body.contains("checkSelfPermission"));
        ok("Binder 断开 → S_NOT_RUNNING 或 S_NOT_INSTALLED",
                body.contains("S_NOT_RUNNING") && body.contains("S_NOT_INSTALLED"));
        ok("授权失效 → S_NO_PERMISSION", body.contains("S_NO_PERMISSION"));
    }

    /** 失败时必须更新 state，让界面与日志立刻反映真相。 */
    private void stateReflectsFailure() {
        String src = readSource("cap/ShizukuChannel.java");
        int at = src.indexOf("private boolean stillUsable()");
        int end = src.indexOf("\n    }", at);
        String body = end > at ? src.substring(at, end) : "";
        // 三个失败分支都必须写 state 与 stateDetail
        ok("失败分支更新 state", body.contains("state = "));
        ok("失败分支更新 stateDetail", body.contains("stateDetail = "));
        ok("提示重新启动服务", body.contains("重新启动服务"));
        ok("提示重新授权", body.contains("重新授权"));
    }

    /** 不允许在 exec 路径上残留「直接读缓存」的旧逻辑。 */
    private void noStaleCacheInExecPath() {
        String src = readSource("cap/ShizukuChannel.java");
        // usable() 仍可存在（给界面用），但不能是 exec 的唯一判据
        ok("usable() 保留（界面用）", src.contains("public boolean usable()"));
        // state() 的缓存语义保留（界面首帧安全），但 exec 不依赖它
        ok("state() 保留缓存语义", src.contains("return state < 0 ? probe() : state;"));
    }
}
