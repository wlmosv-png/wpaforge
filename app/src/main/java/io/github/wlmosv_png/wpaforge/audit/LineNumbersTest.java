package io.github.wlmosv_png.wpaforge.audit;

/**
 * 编辑器行号的纯逻辑测试（重构第一批）。
 *
 * <p>这些是**真实生产方法**（{@code DictEditorDialog} 直接调用），不是模拟。
 * 覆盖需求第六节点名的三条语义：逐行对应、光标行列准确、大文件降级。
 */
public final class LineNumbersTest {

    private static int pass = 0, fail = 0;

    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }

    public static void main(String[] args) {
        System.out.println("=== 编辑器行号（纯逻辑） ===");

        // ---- ① 行数 ----
        ok("null → 1 行", LineNumbers.countLines(null) == 1, "非 1");
        ok("空串 → 1 行", LineNumbers.countLines("") == 1, "非 1");
        ok("无换行 → 1 行", LineNumbers.countLines("abc") == 1, "非 1");
        ok("尾部换行 → 多一行（编辑器里能看到空行）",
                LineNumbers.countLines("a\nb\n") == 3, String.valueOf(LineNumbers.countLines("a\nb\n")));
        ok("三行 → 3", LineNumbers.countLines("a\nb\nc") == 3,
                String.valueOf(LineNumbers.countLines("a\nb\nc")));
        ok("空行也算一行", LineNumbers.countLines("a\n\nb") == 3,
                String.valueOf(LineNumbers.countLines("a\n\nb")));

        // ---- ② 光标 → 行 ----
        String t = "l1\nl2\nl3";
        ok("偏移 0 → 第 1 行", LineNumbers.lineOf(t, 0) == 1, "");
        ok("第 1 行中间 → 第 1 行", LineNumbers.lineOf(t, 1) == 1, "");
        ok("换行符之后 → 第 2 行", LineNumbers.lineOf(t, 3) == 2, String.valueOf(LineNumbers.lineOf(t, 3)));
        ok("第 3 行 → 第 3 行", LineNumbers.lineOf(t, 6) == 3, String.valueOf(LineNumbers.lineOf(t, 6)));
        ok("偏移越界 → 夹到末尾", LineNumbers.lineOf(t, 999) == 3, "");
        ok("负偏移 → 夹到 0（第 1 行）", LineNumbers.lineOf(t, -5) == 1, "");

        // ---- ③ 光标 → 列 ----
        ok("第 1 行首列 → 1", LineNumbers.columnOf(t, 0) == 1, "");
        ok("第 1 行第 2 列", LineNumbers.columnOf(t, 1) == 2, "");
        ok("换行后回到第 1 列", LineNumbers.columnOf(t, 3) == 1,
                String.valueOf(LineNumbers.columnOf(t, 3)));
        ok("第 3 行第 2 列", LineNumbers.columnOf(t, 7) == 2,
                String.valueOf(LineNumbers.columnOf(t, 7)));

        // ---- ④ 行号 → 偏移（定位行用）----
        ok("第 1 行起点 = 0", LineNumbers.offsetOfLine(t, 1) == 0, "");
        ok("第 2 行起点 = 3", LineNumbers.offsetOfLine(t, 2) == 3, "");
        ok("第 3 行起点 = 6", LineNumbers.offsetOfLine(t, 3) == 6, "");
        ok("超出范围 → 文本末尾", LineNumbers.offsetOfLine(t, 99) == t.length(), "");
        ok("定位行的往返：offsetOfLine → lineOf 一致",
                LineNumbers.lineOf(t, LineNumbers.offsetOfLine(t, 2)) == 2, "");
        ok("定位行的往返：第 3 行", LineNumbers.lineOf(t, LineNumbers.offsetOfLine(t, 3)) == 3, "");

        // ---- ⑤ 行号栏渲染 ----
        ok("10 行 → 1..10", LineNumbers.render(10, 0).equals("1\n2\n3\n4\n5\n6\n7\n8\n9\n10"),
                LineNumbers.render(10, 0));
        ok("行号数与文本行数严格相等",
                LineNumbers.render(7, 0).split("\n", -1).length == 7, "");
        ok("超过阈值 → 不渲染（大文件降级，返回空串）",
                LineNumbers.render(LineNumbers.DEFAULT_MAX_LINES + 1,
                        LineNumbers.DEFAULT_MAX_LINES).isEmpty(), "非空");
        ok("恰好等于阈值 → 正常渲染",
                !LineNumbers.render(LineNumbers.DEFAULT_MAX_LINES,
                        LineNumbers.DEFAULT_MAX_LINES).isEmpty(), "被误降级");
        ok("0 行 → 仍给第 1 行（界面不出现「0 行」）",
                LineNumbers.render(0, 0).equals("1"), LineNumbers.render(0, 0));

        // ---- ⑥ 规模：2500 行不产生超大文本 ----
        {
            String big = LineNumbers.render(2500, LineNumbers.DEFAULT_MAX_LINES);
            int lines = big.split("\n", -1).length;
            ok("2500 行行号渲染完整", lines == 2500, String.valueOf(lines));
            ok("行号文本长度可控（< 16KB）", big.length() < 16000, String.valueOf(big.length()));
        }

        // ---- ⑦ 行号绝不进入文件内容（结构性保证，这里钉住「渲染产物是独立字符串」）----
        {
            String text = "12345678\n88888888\n";
            String gutter = LineNumbers.render(LineNumbers.countLines(text), 0);
            ok("行号字符串不含字典内容", !gutter.contains("12345678"), gutter);
            ok("字典内容不含行号前缀", !text.contains("1 │") && !text.startsWith("1 "), text);
        }

        System.out.println();
        System.out.println("=== 行号: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
