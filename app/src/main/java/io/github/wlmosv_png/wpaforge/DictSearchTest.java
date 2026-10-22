package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.DictSearch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 字典搜索测试。
 *
 * 重点两处：**正则非法不能崩**（用户手输的，什么都有可能）、
 * **必须有上限**（十万条字典 + 宽泛关键词，不限量界面先死）。
 */
public class DictSearchTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static List<String> dict() {
        return new ArrayList<>(Arrays.asList(
                "12345678", "password", "password123", "Password1",
                "88888888", "qwerty2026", "abc2026xyz", "redskins"));
    }

    public static void main(String[] args) {
        System.out.println("== 包含（默认）==");
        List<DictSearch.Match> m1 = DictSearch.search(dict(), "password", DictSearch.MODE_CONTAINS, 0);
        ok("命中 2 条", m1.size() == 2);
        ok("行号是 1 起的真实行号", m1.get(0).lineNo == 2 && m1.get(1).lineNo == 3);
        ok("大小写敏感（包含模式）",
                DictSearch.search(dict(), "PASSWORD", DictSearch.MODE_CONTAINS, 0).isEmpty());

        System.out.println("== 前缀 ==");
        List<DictSearch.Match> m2 = DictSearch.search(dict(), "password", DictSearch.MODE_PREFIX, 0);
        ok("前缀命中 2 条（password / password123）", m2.size() == 2);

        System.out.println("== 后缀 ==");
        List<DictSearch.Match> m3 = DictSearch.search(dict(), "2026", DictSearch.MODE_SUFFIX, 0);
        ok("后缀命中 1 条（qwerty2026）", m3.size() == 1);
        ok("命中内容是 qwerty2026", "qwerty2026".equals(m3.get(0).text));

        System.out.println("== 正则 ==");
        List<DictSearch.Match> m4 = DictSearch.search(dict(), "^\\d{8}$", DictSearch.MODE_REGEX, 0);
        ok("八位纯数字命中 2 条", m4.size() == 2);
        List<DictSearch.Match> m5 = DictSearch.search(dict(), "20\\d\\d", DictSearch.MODE_REGEX, 0);
        ok("正则 \\d 能用", m5.size() == 2);

        System.out.println("== 非法输入不崩 ==");
        ok("非法正则 -> 不匹配且不抛",
                DictSearch.search(dict(), "([", DictSearch.MODE_REGEX, 0).isEmpty());
        ok("正则空串 -> 空结果",
                DictSearch.search(dict(), "", DictSearch.MODE_REGEX, 0).isEmpty());
        ok("关键词 null -> 空结果",
                DictSearch.search(dict(), null, DictSearch.MODE_CONTAINS, 0).isEmpty());
        ok("字典 null -> 空结果",
                DictSearch.search(null, "a", DictSearch.MODE_CONTAINS, 0).isEmpty());
        ok("matches 面对 null 行不崩",
                !DictSearch.matches(null, "a", DictSearch.MODE_CONTAINS));

        System.out.println("== 条数上限 ==");
        List<String> big = new ArrayList<>();
        for (int i = 0; i < 5000; i++) big.add("abc" + i);
        ok("默认上限 300", DictSearch.search(big, "abc", DictSearch.MODE_CONTAINS, 0).size() == 300);
        ok("自定义上限 10", DictSearch.search(big, "abc", DictSearch.MODE_CONTAINS, 10).size() == 10);
        ok("没有命中时不返回垃圾", DictSearch.search(big, "zzz", DictSearch.MODE_CONTAINS, 0).isEmpty());

        System.out.println("== 模式标签 ==");
        ok("包含", "包含".equals(DictSearch.modeLabel(DictSearch.MODE_CONTAINS)));
        ok("正则", "正则".equals(DictSearch.modeLabel(DictSearch.MODE_REGEX)));

        System.out.println();
        System.out.println("=== 字典搜索测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
