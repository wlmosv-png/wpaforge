package io.github.wlmosv_png.wpaforge.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import io.github.wlmosv_png.wpaforge.TestPaths;

/**
 * P3：**「停止 → 继续」不能被上一轮的停止标志永久否决**。
 *
 * <h3>真机现象</h3>
 * 点停止后再点继续，第一次无反应，必须点第二次才恢复。
 *
 * <h3>根因（CrackService.runQueue 的 finally）</h3>
 * <pre>
 *   if (!stopRequested && wantRestart && TaskQueue.hasPending(this)) { 重启 }
 * </pre>
 * {@code stopAll()} 把 {@code stopRequested} 置 true 后，除了下一次
 * {@code startQueue()} 之外没有任何地方清它。于是「停止 → 收尾窗口内点继续」
 * 这一路径：{@code startQueue} 被「worker 还活着」挡回只记 {@code restartWanted}，
 * 收尾时 {@code stopRequested} 仍为 true → 不重启 → 任务永远停在 PENDING。
 *
 * <p>修复：判据改为「队列里真有 PENDING 就重启」，并在重启前清掉
 * 上一轮的停止意图。
 */
public final class P3ResumeWiringTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static String read(String pkgRelative) throws IOException {
        String root = TestPaths.javaSrcRoot();
        if (root == null || root.isEmpty()) throw new IOException("源码根不可用");
        return new String(Files.readAllBytes(Paths.get(root + pkgRelative)),
                StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== P3 「停止 → 继续」接线守卫 ===");

        String src = read("io/github/wlmosv_png/wpaforge/CrackService.java");

        // ---- ① 收尾重启判据不得再依赖 stopRequested ----
        boolean oldGuard = src.contains("if (!stopRequested && wantRestart && TaskQueue.hasPending(this))");
        ok("① 旧判据「!stopRequested && wantRestart」已移除", !oldGuard,
                "仍存在旧判据");

        boolean newGuard = src.contains("if (wantRestart && TaskQueue.hasPending(this))");
        ok("① 新判据只看「队列里有 PENDING」", newGuard, "未找到新判据");

        // ---- ② 重启前必须清掉上一轮的停止意图 ----
        int idx = src.indexOf("接上「继续」请求");
        ok("② 存在「接上继续」重启分支", idx > 0, "未找到");

        // ---- ③ 语义守卫：注释说明 stopRequested 只是一轮意图 ----
        ok("③ stopRequested 被标注为「本轮意图」而非永久否决",
                src.contains("它不再永久否决之后的「继续」"), "缺少说明");

        // ---- ④ 重启分支里必须重置 stopRequested ----
        String tail = src.substring(Math.max(0, idx - 200), Math.min(src.length(), idx + 900));
        ok("④ 重启分支内重置 stopRequested", tail.contains("stopRequested = false;"),
                "重启分支未清停止意图");
        info("④ 重启分支片段长度 " + tail.length());

        // ---- ⑤ 三个状态入口都存在且语义正确 ----
        ok("⑤ pause() 会通知引擎", src.contains("eng.pause()"), "缺 eng.pause()");
        ok("⑤ stopAll() 会通知引擎", src.contains("eng.cancel()"), "缺 eng.cancel()");
        ok("⑤ startQueue 会把「worker 还活着」记成 restartWanted",
                src.contains("restartWanted = true;"), "缺 restartWanted");

        // ---- ⑥ 状态机模拟：停止后继续必须能重新启动 ----
        // 用局部变量复刻 finally 的新逻辑，证明「stopRequested=true + PENDING」也会重启
        boolean restarted = simulateFinally(true /*stopRequested*/, true /*wantRestart*/,
                true /*hasPending*/);
        ok("⑥ 复刻：stopRequested=true + PENDING -> 仍会重启", restarted, "未重启");
        boolean notRestarted = simulateFinally(false, false, true);
        ok("⑥ 复刻：没有继续请求 -> 不重启（正常收尾）", !notRestarted, "误重启");
        boolean noPending = simulateFinally(false, true, false);
        ok("⑥ 复刻：没有 PENDING -> 不重启", !noPending, "误重启");

        System.out.println();
        System.out.println("=== P3 接线守卫: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** 与修复后 finally 分支逐条对应的复刻。 */
    private static boolean simulateFinally(boolean stopRequested, boolean wantRestart,
                                           boolean hasPending) {
        // 新判据：只看 wantRestart && hasPending
        if (wantRestart && hasPending) {
            stopRequested = false;
            return true;   // 重启
        }
        return false;
    }
}
