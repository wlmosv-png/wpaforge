package io.github.wlmosv_png.wpaforge.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import io.github.wlmosv_png.wpaforge.TestPaths;

/**
 * 破解页 UI 第二轮改造的**可执行验收**。
 *
 * <h3>四个信息层级（用户要求）</h3>
 * <ol>
 *   <li>当前目标 / 当前状态 / 整体进度</li>
 *   <li>已执行 / 已证实错误 / 未确定 / 总数</li>
 *   <li>暂停 / 继续 / 停止</li>
 *   <li>详细日志（默认折叠，原始证据不删）</li>
 * </ol>
 */
public final class UiRefactorTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static String read(String rel) throws IOException {
        String root = TestPaths.javaSrcRoot();
        if (root == null || root.isEmpty()) throw new IOException("no root");
        return new String(Files.readAllBytes(Paths.get(root + rel)), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 破解页 UI 第二轮 ===");
        String ma = read("io/github/wlmosv_png/wpaforge/MainActivity.java");
        String cs = read("io/github/wlmosv_png/wpaforge/CrackService.java");
        String ce = read("io/github/wlmosv_png/wpaforge/audit/CrackEngine.java");

        // ---- ① 日志默认折叠 ----
        ok("① 日志默认折叠（streamCollapsed = true）",
                ma.contains("private boolean streamCollapsed = true;"), "未默认折叠");

        // 折叠高度已收敛
        ok("① 折叠高度 110dp（原 160dp）", ma.contains("streamParams.height = px(110);"),
                "高度未收敛");
        ok("① 折叠态最多 4 行", ma.contains("connectStreamView.setMaxLines(4);"), "行数未收");

        // ---- ② 统计四列 ----
        for (String label : new String[]{"已执行", "已证实错误", "未确定", "总数"}) {
            ok("② 统计列存在: " + label, ma.contains("\"" + label + "\""), label);
        }
        ok("② 有独立的未确定视图（statUnkView）", ma.contains("statUnkView"), "缺失");
        ok("② 有独立的已证实错误视图（statWrongView）", ma.contains("statWrongView"), "缺失");
        ok("② 「已证实错误」用危险色、非零才点亮",
                ma.contains("statWrongView.setTextColor(confirmed > 0 ? DANGER"), "配色缺失");

        // ---- ③ 实时分布来自引擎唯一统计入口 ----
        ok("③ UiCallback 有 onTally 回调", cs.contains("default void onTally("), "缺失");
        ok("③ CrackService 在每条结果后回调 onTally", cs.contains("cb.onTally("), "未回调");
        ok("③ 回调取引擎累计值（不自己累加）",
                cs.contains("eng.confirmedFailCount()"), "口径不一致");
        ok("③ 引擎暴露 networkAuthFailCount", ce.contains("public int networkAuthFailCount()"),
                "缺失");

        // ---- ④ 日志降噪 ----
        ok("④ 存在降噪入口 appendStreamDedup", ma.contains("private void appendStreamDedup("),
                "缺失");
        ok("④ 合并键用 Verdict 本身（不二次推断）",
                ma.contains("String key = v.name();"), "键来源不对");
        ok("④ 合并显示带 ×N", ma.contains("\"   ×\" + l.repeat"), "未显示次数");
        ok("④ 说明行同键只出现一次", ma.contains("lastWhyKey"), "缺少说明去重");

        // ---- ⑤ 原始证据未被删 ----
        ok("⑤ 全量行仍保留在 streamLines", ma.contains("streamLines.add(new LogLine("), "丢失");
        ok("⑤ 合并只替换最后一行，不丢历史",
                ma.contains("streamLines.set(streamLines.size() - 1"), "实现不对");

        // ---- ⑥ 命中仍会展开日志（让证据可见）----
        ok("⑥ 命中时展开日志（不隐藏证据）",
                ma.contains("if (streamCollapsed) toggleStream();"), "缺失");

        System.out.println();
        System.out.println("=== UI 第二轮: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
