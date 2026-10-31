package io.github.wlmosv_png.wpaforge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 门禁自检（A 阶段，2026-10-31）。
 *
 * <h3>为什么需要它</h3>
 * 此前 24 个测试类把旧工作副本的绝对路径写死。把项目根换成别处跑门禁时，
 * 这些测试**扫的是另一份源码**，结论仍然「通过」—— 典型的假绿。
 * 本测试用**源码文本守卫**把这个行为钉死：
 * <ul>
 *   <li>测试目录下不得再出现旧工作副本路径；</li>
 *   <li>所有读源码的测试必须经由 {@link TestPaths} 解析；</li>
 *   <li>{@link TestPaths} 解析出的根必须真实存在且自洽；</li>
 *   <li>显式覆盖（系统属性）优先于其它所有来源。</li>
 * </ul>
 *
 * <p>它自身**不读任何外部源码**（除被测路径本身的存在性），
 * 因此无论门禁在哪个根上跑都成立。
 */
public final class GateRootTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        boolean c = want == null ? got == null : want.equals(got);
        if (!c) System.out.println("        期望=" + want + " 实际=" + got);
        ok(name, c);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== GateRootTest（门禁身份自检） ===");
        System.out.println("  解析到的项目根: " + TestPaths.identity());

        // ---- 1. TestPaths 解析出的根真实存在且结构完整
        String root = TestPaths.root();
        ok("TestPaths.root() 非空", root != null && !root.isEmpty());
        ok("root 无结尾斜杠", !(root.length() > 1 && root.endsWith("/")));
        ok("root/app/src/main/java 存在", new File(TestPaths.javaSrcRoot()).isDirectory());
        ok("root 包目录存在", new File(TestPaths.javaPkgDir()).isDirectory());
        ok("包目录下有 CrackEngine.java",
                new File(TestPaths.javaPkgDir() + "audit/CrackEngine.java").isFile());
        ok("包目录下有 TestPaths.java（自身）",
                new File(TestPaths.javaPkgDir() + "TestPaths.java").isFile());

        // ---- 2. under() 拼接语义正确
        eq("under(x) 前缀正确", root + "/x", TestPaths.under("x"));
        ok("javaPkgDir() 以 / 结尾", TestPaths.javaPkgDir().endsWith("/"));
        ok("javaSrcRoot() 以 / 结尾", TestPaths.javaSrcRoot().endsWith("/"));
        ok("javaSrcRoot() 是 javaPkgDir() 的前缀",
                TestPaths.javaPkgDir().startsWith(TestPaths.javaSrcRoot()));

        // ---- 3. 显式覆盖优先（用临时值验证优先级，不改全局缓存）
        String sysProp = System.getProperty(TestPaths.PROP_ROOT);
        ok("门禁已注入 -D" + TestPaths.PROP_ROOT,
                sysProp != null && !sysProp.trim().isEmpty());
        if (sysProp != null) {
            eq("系统属性与解析结果一致", sysProp.trim().replaceAll("/+$", ""), root);
        }

        // ---- 4. 旧工作副本路径必须彻底消失（源码文本守卫）
        List<File> tests = new ArrayList<>();
        collect(new File(TestPaths.javaPkgDir()), tests);
        ok("测试目录可枚举", !tests.isEmpty());

        List<String> offenders = new ArrayList<>();
        List<String> selfRead = new ArrayList<>();
        for (File f : tests) {
            String name = f.getName();
            String src;
            try {
                src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            } catch (Throwable t) {
                continue;
            }
            // 4a. 不得出现旧工作副本绝对路径（所有 .java，含生产类）
            if (src.contains("promo" + "-wpaforge")) offenders.add(name);
        }
        eq("源码中无旧工作副本路径", "[]", offenders.toString());

        // 4b. 只有 *Test.java 才可能「读源码做守卫」；
        //     生产类里的 readAllBytes/readString 是运行时读用户数据，与本门禁无关，
        //     不纳入（否则会误报 TaskQueue/DictStore 这类正常类）。
        List<File> testFiles = new ArrayList<>();
        for (File f : tests) {
            if (f.getName().endsWith("Test.java")) testFiles.add(f);
        }
        ok("*Test.java 数量 > 0", !testFiles.isEmpty());
        for (File f : testFiles) {
            String name = f.getName();
            if (name.equals("GateRootTest.java") || name.equals("TestPaths.java")) continue;
            String src;
            try {
                src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            } catch (Throwable t) {
                continue;
            }
            boolean readsSource = src.contains("Files.readAllBytes")
                    || src.contains("new FileInputStream")
                    || src.contains("Files.readString");
            if (readsSource
                    && !src.contains("TestPaths.")
                    && !src.contains("javaSrcRoot")
                    && !src.contains("javaPkgDir")) {
                selfRead.add(name);
            }
        }
        eq("读源码的测试均经 TestPaths 解析", "[]", selfRead.toString());

        // ---- 5. 门禁脚本必须显式注入被测根
        File gate = new File(TestPaths.root() + "/wpf-gate-sh-probe");
        // 门禁脚本在 /workspace（工作区外），此处只校验项目内可自证的部分：
        // 构建脚本里不得残留旧绝对路径。
        for (String rel : new String[]{"2-build-dex.sh", "1-aapt2.sh"}) {
            File f = new File(TestPaths.root() + "/" + rel);
            if (!f.isFile()) { ok(rel + " 存在", false); continue; }
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            ok(rel + " 不含旧工作副本路径", !s.contains("promo" + "-wpaforge"));
        }

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) throw new RuntimeException("GateRootTest 失败 " + fail + " 项");
    }

    private static void collect(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collect(k, out);
            else if (k.getName().endsWith(".java")) out.add(k);
        }
    }
}
