package io.github.wlmosv_png.wpaforge.audit;

/**
 * 编辑器的**行号与光标定位**纯逻辑。
 *
 * <h3>为什么单独抽出来</h3>
 * 「行号」看起来是纯 UI，但它有三条容易被做错的语义要求
 * （见 {@code /storage/emulated/0/无印/ww.txt} 第六节）：
 * <ol>
 *   <li><b>逐行对应</b>：行号必须和文本的每一行严格对齐，包括空行与结尾换行；</li>
 *   <li><b>绝不写进文件</b>：行号是渲染产物，不能混进保存内容；</li>
 *   <li><b>光标行列要准</b>：用户看到「第 128 行，共 2495 行」时必须真的在第 128 行。</li>
 * </ol>
 * 这三条都是可以离线穷举的整数运算，放在这里用测试钉死，
 * 比在 Activity 里靠肉眼核对可靠得多。
 *
 * <p><b>行号约定</b>：与编辑器一致，**1 起**；空文本 = 1 行（第 1 行是空行），
 * 这与大多数编辑器一致，也避免「0 行」这种在界面上无法解释的状态。
 */
public final class LineNumbers {

    private LineNumbers() {}

    /** 文本的行数（{@code null}/空 → 1 行）。 */
    public static int countLines(String text) {
        if (text == null || text.isEmpty()) return 1;
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') n++;
        }
        // 结尾就是换行时，"a\n" 是 1 行内容 + 一个空行 —— 编辑器里会看到第 2 行，
        // 所以计数保持 +1；这与「按 \n 切分」的直觉一致。
        return n;
    }

    /**
     * 光标偏移 → 行号（1 起）。
     *
     * @param offset 光标偏移；越界会被夹到 [0, len]
     */
    public static int lineOf(String text, int offset) {
        if (text == null) return 1;
        int o = Math.max(0, Math.min(offset, text.length()));
        int line = 1;
        for (int i = 0; i < o; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    /** 光标偏移 → 列号（1 起，按字符计，不按显示宽度）。 */
    public static int columnOf(String text, int offset) {
        if (text == null) return 1;
        int o = Math.max(0, Math.min(offset, text.length()));
        int lineStart = 0;
        for (int i = 0; i < o; i++) {
            if (text.charAt(i) == '\n') lineStart = i + 1;
        }
        return o - lineStart + 1;
    }

    /** 第 n 行（1 起）在文本中的起始偏移；越界返回文本长度。 */
    public static int offsetOfLine(String text, int line) {
        if (text == null) return 0;
        if (line <= 1) return 0;
        int cur = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                cur++;
                if (cur == line) return i + 1;
            }
        }
        return text.length();
    }

    /**
     * 生成行号栏文本（每行一个数字）。
     *
     * <p>{@code maxLines} 是**保护阈值**：超过它就不生成（返回空串），
     * 由调用方决定降级方式。行号栏本身是一整块文本，
     * 十万行会变成几十万个字符——那会拖慢渲染，而它并不承载任何业务信息。
     *
     * @return 行号文本；超过阈值返回 {@code ""}
     */
    public static String render(int lineCount, int maxLines) {
        if (lineCount <= 0) return "1";
        if (maxLines > 0 && lineCount > maxLines) return "";
        StringBuilder sb = new StringBuilder(lineCount * 4);
        for (int i = 1; i <= lineCount; i++) {
            if (i > 1) sb.append('\n');
            sb.append(i);
        }
        return sb.toString();
    }

    /** 行号栏默认的渲染上限（与 {@code DictEditorDialog} 的降级阈值一致）。 */
    public static final int DEFAULT_MAX_LINES = 20000;
}
