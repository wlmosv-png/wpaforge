package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.DictRule;

import java.util.List;

/**
 * 字典规则解析自检。规则字典是"用参数描述海量密码"的能力，
 * 也是最容易被写错的一块，单独测。
 */
public class RuleTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    public static void main(String[] a) {
        System.out.println("== 解析：幻影式规则文件 ==");
        String phantom = "\uFEFF前缀:\r\n后缀:\r\n起始:00000000\r\n末尾:99999999\n";
        DictRule r = DictRule.parse(phantom);
        ok("前缀为空", r.prefix.isEmpty(), "[" + r.prefix + "]");
        ok("后缀为空", r.suffix.isEmpty(), "[" + r.suffix + "]");
        ok("起始=00000000", "00000000".equals(r.start), r.start);
        ok("末尾=99999999", "99999999".equals(r.end), r.end);
        ok("步长默认1", r.step == 1, "" + r.step);
        ok("规则有效", r.isValid(), "");
        ok("估算 1亿 条", r.estimateCount() == 100000000L, "" + r.estimateCount());

        System.out.println("== 展开：保留前导零 ==");
        DictRule r2 = DictRule.parse("起始:00000098\n末尾:00000102");
        List<Candidate> c2 = r2.expand(100);
        ok("生成 5 条", c2.size() == 5, "得到 " + c2.size());
        ok("首条 = 00000098", c2.get(0).password.equals("00000098"), c2.get(0).password);
        ok("末条 = 00000102", c2.get(4).password.equals("00000102"), c2.get(4).password);
        ok("前导零保留", c2.get(1).password.length() == 8, c2.get(1).password);

        System.out.println("== 前缀 / 后缀拼接 ==");
        DictRule r3 = DictRule.parse("前缀:home\n后缀:88\n起始:0001\n末尾:0003");
        List<Candidate> c3 = r3.expand(10);
        ok("生成 3 条", c3.size() == 3, "" + c3.size());
        ok("拼接正确 = home000188", c3.get(0).password.equals("home000188"), c3.get(0).password);

        System.out.println("== 步长 ==");
        DictRule r4 = DictRule.parse("起始:1000\n末尾:1010\n步长:5");
        List<Candidate> c4 = r4.expand(100);
        ok("步长5 生成 3 条", c4.size() == 3, "" + c4.size());
        ok("第二条 = 1005", c4.get(1).password.equals("1005"), c4.get(1).password);

        System.out.println("== 上限保护 ==");
        DictRule big = DictRule.parse("起始:00000000\n末尾:99999999");
        List<Candidate> capped = big.expand(50);
        ok("硬上限生效(50)", capped.size() == 50, "" + capped.size());

        System.out.println("== 序列化往返 ==");
        DictRule r5 = DictRule.parse("前缀:P\n后缀:S\n起始:1\n末尾:9\n步长:2");
        DictRule r5b = DictRule.parse(r5.serialize());
        ok("往返后前缀一致", r5b.prefix.equals("P"), r5b.prefix);
        ok("往返后步长一致", r5b.step == 2, "" + r5b.step);

        System.out.println("== 非法输入 ==");
        ok("空串无效", !DictRule.parse("").isValid(), "");
        ok("缺末尾无效", !DictRule.parse("起始:1").isValid(), "");
        DictRule rev = DictRule.parse("起始:999\n末尾:100");
        ok("起止颠倒 条数=0", rev.estimateCount() == 0, "" + rev.estimateCount());

        System.out.println("== 字母模式 ==");
        DictRule al = DictRule.parse("起始:aaa\n末尾:aad\n进制:字母");
        List<Candidate> c6 = al.expand(100);
        ok("字母模式生成 4 条", c6.size() == 4, "" + c6.size());
        ok("首条 = aaa", c6.get(0).password.equals("aaa"), c6.get(0).password);
        ok("第三条 = aac", c6.get(2).password.equals("aac"), c6.get(2).password);

        System.out.println("== 兼容无冒号写法 ==");
        DictRule nc = DictRule.parse("起始 0001\n末尾 0003");
        ok("空格分隔也能解析", nc.isValid() && nc.estimateCount() == 3,
                nc.start + "-" + nc.end);

        System.out.println();
        System.out.println("=== 规则测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
