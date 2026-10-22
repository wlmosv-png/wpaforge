package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 字典内容搜索（纯逻辑）。
 *
 * <h3>为什么单独抽出来</h3>
 * 两件事都容易出错、又都需要能验证：
 * <ol>
 *   <li><b>匹配语义</b>：包含 / 前缀 / 后缀 / 正则四种。正则是用户输入，
 *       非法模式必须安静地"不匹配"，不能把整个界面搞崩。</li>
 *   <li><b>剪枝与上限</b>：十万条字典逐行 contains 也要几十毫秒，
 *       而且结果可能上万条 —— 必须有条数上限，否则界面先卡死。</li>
 * </ol>
 *
 * <p>纯逻辑，无 Android 依赖。
 */
public final class DictSearch {

    public static final int MODE_CONTAINS = 0;
    public static final int MODE_PREFIX = 1;
    public static final int MODE_SUFFIX = 2;
    public static final int MODE_REGEX = 3;

    /** 默认上限：结果太多时界面自己先卡死，这里兜住 */
    public static final int DEFAULT_LIMIT = 300;

    public static final class Match {
        /** 行号，1 起（和编辑器里看到的一致） */
        public final int lineNo;
        public final String text;

        public Match(int lineNo, String text) {
            this.lineNo = lineNo;
            this.text = text;
        }
    }

    private DictSearch() {}

    /** 单行是否命中；正则非法时返回 false（不抛） */
    public static boolean matches(String line, String keyword, int mode) {
        if (line == null || keyword == null || keyword.isEmpty()) return false;
        switch (mode) {
            case MODE_PREFIX:
                return line.startsWith(keyword);
            case MODE_SUFFIX:
                return line.endsWith(keyword);
            case MODE_REGEX:
                try {
                    return Pattern.compile(keyword).matcher(line).find();
                } catch (Throwable t) {
                    return false;
                }
            default:
                return line.contains(keyword);
        }
    }

    /**
     * 逐行搜索，最多返回 limit 条。
     *
     * @param lines   字典内容（调用方负责按行切好）
     * @param limit   &lt;=0 时用 {@link #DEFAULT_LIMIT}
     */
    public static List<Match> search(List<String> lines, String keyword, int mode, int limit) {
        List<Match> out = new ArrayList<>();
        if (lines == null || keyword == null || keyword.isEmpty()) return out;
        int cap = limit <= 0 ? DEFAULT_LIMIT : limit;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (matches(line, keyword, mode)) {
                out.add(new Match(i + 1, line));
                if (out.size() >= cap) break;
            }
        }
        return out;
    }

    /** 匹配模式的名字（给界面用） */
    public static String modeLabel(int mode) {
        switch (mode) {
            case MODE_PREFIX: return io.github.wlmosv_png.wpaforge.L.s("前缀", "prefix");
            case MODE_SUFFIX: return io.github.wlmosv_png.wpaforge.L.s("后缀", "suffix");
            case MODE_REGEX:  return io.github.wlmosv_png.wpaforge.L.s("正则", "regex");
            default:          return io.github.wlmosv_png.wpaforge.L.s("包含", "contains");
        }
    }
}
