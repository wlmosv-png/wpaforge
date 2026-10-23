package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.ui.Redact;

/**
 * 口令脱敏的规格测试。
 *
 * <p>规格由**用户决策**独立给出（"全量脱敏：禁止输出明文口令或密钥，
 * 保留排查所需的非敏感任务标识"），不是从生产实现反推的：
 * <ol>
 *   <li>输出里**不得出现**明文的任何子串</li>
 *   <li>同一条口令 → 同一个标签（可对照）；不同口令 → 不同标签（可区分）</li>
 *   <li>长度保留；null / 空串按空串处理且确定性</li>
 *   <li>固定的已知向量必须命中（防止算法被无意改掉）</li>
 * </ol>
 *
 * <p>第 4 条的向量是**独立算出来的**（`sha256("12345678")` 前 6 位 = `ef797c`），
 * 不调用被测代码生成期望值。
 */
public class RedactTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (!ok) System.out.println("       期望 " + want + "，实际 " + got);
        check(name, ok);
    }

    public static void main(String[] args) {
        System.out.println("== 口令脱敏 ==");

        // 1) 固定向量（独立算出）
        eq("12345678 → #ef797c(len=8)", "#ef797c(len=8)", Redact.pw("12345678"));
        eq("sample-pw → #29ff2d(len=9)", "#29ff2d(len=9)", Redact.pw("sample-pw"));
        eq("password → #5e8848(len=8)", "#5e8848(len=8)", Redact.pw("password"));
        eq("空串 → #e3b0c4(len=0)", "#e3b0c4(len=0)", Redact.pw(""));
        eq("null 按空串处理", "#e3b0c4(len=0)", Redact.pw(null));

        // 2) ★ 不得泄露明文（含子串）
        //
        // 规格边界（2026-10-24 审计发现）：6 位十六进制标签**对 1 个字符的口令
        // 不保证不含该字符**（哈希值可能偶然含它，实测 "a" → #ca9781(len=1) 里就有 a），
        // 空串更没意义（Java 里任何字符串都 contains("")）。
        // 判定链本身拒绝长度 <8 的口令，所以这个边界在实际路径上不可达；
        // 这里按"长度 ≥8 的口令"来钉规格。
        String[] secrets = {"12345678", "sample-pw", "password", "P@ssw0rd!2026",
                            "x".repeat(63), "口令12345678"};
        for (String s : secrets) {
            String tag = Redact.pw(s);
            check("长度≥8 的口令不泄露明文: " + s.substring(0, 4) + "…",
                    s.length() >= 8 && !tag.contains(s));
            check("不泄露前 2 字符: " + s.substring(0, 4) + "…",
                    !tag.contains(s.substring(0, 2)));
            check("不泄露后 2 字符: " + s.substring(0, 4) + "…",
                    !tag.contains(s.substring(s.length() - 2)));
        }
        // 标签字符集必须只有十六进制 + 固定壳，杜绝"意外带出别的字符"
        String tagShape = Redact.pw("sample-pw");
        check("标签形如 #hex(len=N)", tagShape.matches("#[0-9a-f]{6}\\(len=[0-9]+\\)"));

        // 3) 可对照：同 → 同；异 → 异
        eq("同口令标签稳定", Redact.pw("sample-pw"), Redact.pw("sample-pw"));
        check("不同口令标签不同", !Redact.pw("12345678").equals(Redact.pw("12345679")));
        check("长度相同但内容不同也要区分",
                !Redact.pw("aaaaaaaa").equals(Redact.pw("aaaaaaab")));

        // 4) 长度保留（长度本身就是排查信息）
        eq("长度 8", "#ef797c(len=8)", Redact.pw("12345678"));
        eq("长度 63", Redact.pw("x".repeat(63)), Redact.pw("x".repeat(63)));
        check("长度体现在标签里", Redact.pw("x".repeat(63)).endsWith("(len=63)"));

        // 5) 顺序号（非敏感标识，排查主力）
        eq("带顺序号", "#11 #29ff2d(len=9)", Redact.pwAt(11, "sample-pw"));
        eq("顺序号未知", "#? #29ff2d(len=9)", Redact.pwAt(0, "sample-pw"));
        eq("顺序号负数按未知", "#? #29ff2d(len=9)", Redact.pwAt(-3, "sample-pw"));

        // 6) 中文 / emoji 口令也要稳（按 UTF-8 字节算）
        String cn = "口令123";
        check("中文口令不泄露", Redact.pw(cn) != null && !Redact.pw(cn).contains(cn));
        eq("中文口令标签稳定", Redact.pw(cn), Redact.pw(cn));

        System.out.println();
        System.out.println("== 明文不得进入日志（用户既定决策）==");
        String eng = null;
        try {
            eng = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                    "/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                            + "wpaforge/audit/CrackEngine.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {}
        if (eng == null) {
            System.out.println("  N/RUN 日志明文守卫（源码不可读）");
        } else {
            // 精确取 onLog(...) 的**实参括号段**（按括号配平），再看里面有没有明文变量。
            // 不能用"附近几行"来判：邻近代码里的 c.password 会造成假阳性。
            boolean leak = false;
            int at = 0;
            while ((at = eng.indexOf("onLog(", at)) >= 0) {
                int i = at + "onLog(".length() - 1;
                int depth = 0;
                StringBuilder arg = new StringBuilder();
                for (int k = i; k < eng.length() && k < i + 600; k++) {
                    char c = eng.charAt(k);
                    if (c == '(') depth++;
                    arg.append(c);
                    if (c == ')') { depth--; if (depth == 0) break; }
                }
                // 先剥掉 Redact.pw(...)/Redact.pwAt(...) 的整段调用（那是允许的脱敏形式），
                // 再看实参里还有没有裸的明文变量 —— 只看"有没有 .password"会假阳性。
                String a = stripRedactedCalls(arg.toString());
                if (a.contains("c.password") || a.contains("a.password")) leak = true;
                at++;
            }
            check("引擎的 onLog 实参不含明文口令", !leak);
            check("命中判词改用脱敏标签", eng.contains("Redact.pwAt(cursor, c.password)"));
        }

        System.out.println("口令脱敏: pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /** 去掉 Redact.pw(...) / Redact.pwAt(...) 的整段调用（按括号配平）。 */
    private static String stripRedactedCalls(String arg) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < arg.length()) {
            int k = arg.indexOf("Redact.pw", i);
            if (k < 0) { out.append(arg.substring(i)); break; }
            out.append(arg, i, k);
            int j = arg.indexOf('(', k);
            if (j < 0) { out.append(arg.substring(k)); break; }
            int depth = 0;
            int m = j;
            for (; m < arg.length(); m++) {
                char c = arg.charAt(m);
                if (c == '(') depth++;
                else if (c == ')') { depth--; if (depth == 0) break; }
            }
            i = Math.min(m + 1, arg.length());
        }
        return out.toString();
    }
}
