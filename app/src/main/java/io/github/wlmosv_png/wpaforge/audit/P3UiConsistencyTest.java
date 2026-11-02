package io.github.wlmosv_png.wpaforge.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import io.github.wlmosv_png.wpaforge.TestPaths;

/**
 * P3：**任务结束后「正在尝试」必须被改掉**，且不得冒充成功。
 *
 * <h3>真机现象（2026-11-02）</h3>
 * 命中后同一屏同时出现「✓ 已经命中」和「正在尝试 77777777」——
 * 前者来自结果卡，后者是上一个候选的残留标签。
 *
 * <h3>根因</h3>
 * {@code candLabel} 只在 {@code startConnect()} 里被设成「正在尝试」，
 * 任务结束路径（onTaskDone）从未改它。
 */
public final class P3UiConsistencyTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static String read(String pkgRel) throws IOException {
        String root = TestPaths.javaSrcRoot();
        if (root == null || root.isEmpty()) throw new IOException("no root");
        return new String(Files.readAllBytes(Paths.get(root + pkgRel)), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== P3 UI 一致性守卫 ===");
        String ma = read("io/github/wlmosv_png/wpaforge/MainActivity.java");

        // ① onTaskDone 里必须改 candLabel
        int doneIdx = ma.indexOf("public void onTaskDone(TaskQueue.Task task");
        ok("① 找到 onTaskDone", doneIdx > 0, "not found");
        String doneBody = ma.substring(doneIdx, Math.min(ma.length(), doneIdx + 12000));
        ok("① onTaskDone 内会改写 candLabel", doneBody.contains("candLabel.setText("),
                "未改写标签");

        // ② 命中用「已命中」，其余用「最后尝试」
        ok("② 命中态文案为「已命中」", doneBody.contains("已命中 · 该候选")
                || doneBody.contains("已命中"), "缺少命中文案");
        ok("② 非命中态文案为「最后尝试」", doneBody.contains("最后尝试"), "缺少最后尝试");

        // ③ 不得把非命中写成成功
        ok("③ 非命中态不出现「命中」以外的成功词（不冒充）",
                !doneBody.contains("破解成功\")") || doneBody.contains("DONE_HIT"),
                "存在无条件成功文案");

        // ④ 「正在尝试」的**赋值点**只应在运行期（构造 + startConnect 各一处）
        int assign = count(ma, "\"正在尝试\", \"TRYING\"");
        ok("④「正在尝试」只有运行期赋值点（<=2 处）", assign <= 2, "赋值 " + assign + " 处");

        // ⑤ 状态词表：运行用主动语态、结束用陈述语态
        ok("⑤ 存在「最后尝试」陈述语态", ma.contains("最后尝试"), "缺失");

        System.out.println();
        System.out.println("=== P3 UI 守卫: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static int count(String s, String n) {
        int c = 0, i = 0;
        while ((i = s.indexOf(n, i)) >= 0) { c++; i += n.length(); }
        return c;
    }
}
