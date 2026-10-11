package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.DictGenerator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.util.List;

/**
 * 验证「内置生成器 → 落盘 → 读回」这条链。
 *
 * 这条链是三种模式能不能跑起来的关键：
 *   生成器只在内存里产生候选，不入队时落地的话，
 *   服务进程拿不到 dictPath，只能报「候选为空」。
 */
public class PersistTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 内置生成器落地 ==");

        // 1) \u751f\u6210\uff08\u4e0e MainActivity \u91cc\u5b8c\u5168\u76f8\u540c\u7684\u53c2\u6570\uff09
        DictGenerator g = new DictGenerator(100000)
                .classic().numericPatterns().phoneSegments()
                .wordVariants().wordDigits();
        List<Candidate> list = g.build();
        System.out.println("  生成条数: " + list.size());
        check("生成非空", !list.isEmpty());
        check("条数 > 100", list.size() > 100);

        // 2) \u843d\u76d8
        File tmp = File.createTempFile("wpaforge-gen-", ".txt");
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"))) {
            for (Candidate c : list) { w.write(c.password); w.write("\n"); }
        }
        check("文件已写出", tmp.length() > 0);
        System.out.println("  文件大小: " + tmp.length() + " B");

        // 3) \u8bfb\u56de
        int n = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(tmp))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) n++;
            }
        }
        System.out.println("  读回条数: " + n);
        check("读回条数与生成一致", n == list.size());

        // 4) \u5185\u5bb9\u4e00\u81f4\u6027\uff08\u9996\u5c3e\uff09
        try (BufferedReader r = new BufferedReader(new FileReader(tmp))) {
            String first = r.readLine();
            check("首条与生成一致", first != null && first.equals(list.get(0).password));
        }

        tmp.delete();

        System.out.println("\n=== 落地链结果: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
