package io.github.wlmosv_png.wpaforge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 字典页展示层的门禁（2026-11-15）。
 *
 * <h3>为什么对 UI 做源码断言</h3>
 * 这一轮改的全是「删噪声」，而**噪声最难守**：删掉的东西没有任何功能测试会失败，
 * 下一次谁顺手加回来也没人知道。用户当时的原话是「看着一大堆」——
 * 一堆的来源就是这类每行都在、人人无视的负向文案。
 *
 * <p>所以这里把四件事钉成可判红：
 * <ol>
 *   <li>「没跑过」这类**零信息量的徽标**不许回到行渲染里；</li>
 *   <li>排序不许再变成一排和筛选同形的 chip（要收在一个入口里）；</li>
 *   <li>体积显示不许写死单位（{@code /1024/1024} →「0 MB」）；</li>
 *   <li>副行必须有省略号，不许硬裁。</li>
 * </ol>
 * 扫描前先**剥掉注释** —— 否则解释「为什么不要这个」的注释本身就会把门禁判红，
 * 那会逼着下一个人把注释删掉，正好把理由也一起删了。
 */
public class DictListUiTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  " + detail);
        }
    }

    private static void ok(String name, boolean cond) {
        ok(name, cond, "");
    }

    public static void main(String[] a) {
        String path = TestPaths.under(
                "app/src/main/java/io/github/wlmosv_png/wpaforge/DictActivity.java");
        String raw = read(path);
        ok("DictActivity 可读", raw != null, path);
        if (raw == null) {
            System.out.println("=== 字典页展示: " + pass + " 通过, " + fail + " 失败 ===");
            System.exit(1);
        }
        String code = stripComments(raw);
        ok("剥注释后仍有内容（防止把整个文件当注释剥光）", code.length() > raw.length() / 3,
                code.length() + " / " + raw.length());

        System.out.println("== 负向文案不许回到行渲染 ==");
        ok("行渲染里不再有「没跑过」徽标", !code.contains("L.s(\"没跑过\""),
                "出现了 L.s(\"没跑过\"");
        ok("徽标在无进度时返回 null", code.contains("if (p == null) return null;"));
        ok("调用点会跳过 null 徽标", code.contains("if (badge != null) box.addView(badge);"));

        System.out.println("== 排序必须收在一个入口里 ==");
        ok("不再有同形的 sortChip", !code.contains("private Button sortChip"));
        ok("有独立的排序入口按钮", code.contains("private Button sortButton()"));
        ok("排序选择表存在", code.contains("private void showSortPicker()"));
        ok("排序按钮显示当前方式", code.contains("排序："));

        System.out.println("== 筛选不许被排序挤成一排 ==");
        String inFilterRow = between(code, "private View filterRow(", "private Button filterChip");
        ok("filterRow 里只有 3 个筛选 chip", countOf(inFilterRow, "filterChip(") == 3,
                "" + countOf(inFilterRow, "filterChip("));
        // 注意数的是「加进这一排的那一次调用」：短片的尾部还包含 sortButton() 的**定义**，
        // 直接数 "sortButton()" 会数成 2 —— 门禁自己先踩过这个坑。
        ok("filterRow 里恰好 1 个排序入口",
                countOf(inFilterRow, "row.addView(sortButton())") == 1,
                "" + countOf(inFilterRow, "row.addView(sortButton())"));

        System.out.println("== 体积单位跟着量级走 ==");
        ok("统计行不再写死 MB", !code.contains("(totalBytes / 1024 / 1024)"));
        ok("统计行改走 sizeText", code.contains("sizeText(totalBytes)"));
        ok("0 字节 → 0 B", "0 B".equals(DictActivity.sizeText(0)));
        ok("500 字节 → 500 B", "500 B".equals(DictActivity.sizeText(500)));
        ok("2048 字节 → 2 KB", "2 KB".equals(DictActivity.sizeText(2048)));
        ok("175 KB 不再显示成 0 MB", "175 KB".equals(DictActivity.sizeText(179200)),
                DictActivity.sizeText(179200));
        ok("5 MB 保留一位小数", "5.0 MB".equals(DictActivity.sizeText(5L * 1024 * 1024)),
                DictActivity.sizeText(5L * 1024 * 1024));
        ok("200 MB 不再带小数", "200 MB".equals(DictActivity.sizeText(200L * 1024 * 1024)),
                DictActivity.sizeText(200L * 1024 * 1024));
        ok("负数安全", "0 B".equals(DictActivity.sizeText(-1)));

        System.out.println("== 副行溢出要省略号，不许硬裁 ==");
        ok("meta 行设了 ellipsize", code.contains("meta.setEllipsize("));

        System.out.println("== 添加字典：按意图分组 ==");
        String menu = between(code, "private void showAddMenu()", "private View filterRow(");
        ok("分组标题 >= 3 个", countOf(menu, "addHint(") >= 3, "" + countOf(menu, "addHint("));
        ok("不再有「── 管理 ──」假分节", !menu.contains("── 管理 ──"));
        ok("管理入口改成有名字的一项", menu.contains("管理字典"));
        ok("七个动作一个没少",
                menu.contains("pickFile()") && menu.contains("releaseBuiltin()")
                        && menu.contains("showOnlineSources()") && menu.contains("dialogNewPhoneDict()")
                        && menu.contains("buildEssentialPack()") && menu.contains("dialogNewPlain()")
                        && menu.contains("dialogNewRule()") && menu.contains("showActionSheet()"));

        System.out.println();
        System.out.println("=== 字典页展示: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- helpers

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 剥掉 // 与 /* *\/ 注释（字符串字面量里的 // 不处理 —— 本文件没有那种情况） */
    static String stripComments(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < src.length() && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) i++;
                i = Math.min(src.length(), i + 2);
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    static int countOf(String hay, String needle) {
        int n = 0, at = 0;
        while ((at = hay.indexOf(needle, at)) >= 0) {
            n++;
            at += needle.length();
        }
        return n;
    }

    /** 取 from 到 to 之间的片段；任一锚点缺失返回空串（门禁会因此判红，不会假绿） */
    static String between(String s, String from, String to) {
        int i = s.indexOf(from);
        if (i < 0) return "";
        int j = s.indexOf(to, i + from.length());
        if (j < 0) return "";
        return s.substring(i, j);
    }

}
