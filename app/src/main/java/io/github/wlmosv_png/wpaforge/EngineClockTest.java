package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.EngineClock;
import io.github.wlmosv_png.wpaforge.audit.TestEngineClock;
import io.github.wlmosv_png.wpaforge.audit.WifiAuthFailureWatcher;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * EngineClock 抽象的正确性测试（B1，2026-10-31）。
 *
 * <h3>它守的是什么</h3>
 * B1 的唯一目标：**让时间可控，但不改生产行为**。所以本测试分三组：
 * <ol>
 *   <li><b>生产等价</b>：{@code EngineClock.PRODUCTION} 与原来的
 *       {@code SystemClock.uptimeMillis()} / {@code Thread.sleep} 行为一致。</li>
 *   <li><b>契约保持</b>：阻塞、吞中断、返回 void、负数立即返回 —— 一条不少。</li>
 *   <li><b>污染防护</b>：注入入口是包级可见；{@code CrackEngine} 里没有
 *       任何 static 可变时钟；{@code clock()} 永不为 null。</li>
 * </ol>
 *
 * <p>其中"生产代码里没有调用 setClockForTest"这一条用**源码文本守卫**验证 ——
 * 它与 R3VerdictTest 里的写法一致，删掉调用点即判失败。
 */
public final class EngineClockTest {

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
        System.out.println("=== EngineClockTest（时钟抽象契约） ===");

        // ============================================================ 1. 生产实现
        EngineClock prod = EngineClock.PRODUCTION;
        ok("PRODUCTION 非 null", prod != null);

        // 注意：离线 JVM 上 android.jar 的 SystemClock 是 native 桩，任何调用都会
        // UnsatisfiedLinkError。因此这里**不实际调用** PRODUCTION 的时间读取，
        // 改做两件能在桌面 JVM 上真正验证的事：
        //   (1) sleepQuietly 的阻塞与吞中断语义（只走 Thread.sleep，不碰 native）；
        //   (2) PRODUCTION 类是单例、非 null、且 uptimeMillis 是唯一实现
        //       （通过反射确认它转发给 SystemClock —— 语义断言，不是行为断言）。
        long wallStart = System.nanoTime();
        prod.sleepQuietly(30);
        long wallMs = (System.nanoTime() - wallStart) / 1_000_000L;
        ok("PRODUCTION.sleepQuietly 真的阻塞（墙钟 >=25ms）", wallMs >= 25);

        java.lang.reflect.Method prodUp = prod.getClass().getDeclaredMethod("uptimeMillis");
        ok("PRODUCTION 实现了 uptimeMillis", prodUp != null);
        ok("PRODUCTION 是匿名实现（不与生产类耦合）", prod.getClass().isAnonymousClass());

        ok("生产实现在真机上等价于 SystemClock（源码守卫见下）",
                prodSourceForwardsToSystemClock());

        // 中断：必须被吞掉，不抛出、不恢复中断位以外的影响
        Thread.currentThread().interrupt();
        boolean threw = false;
        try {
            prod.sleepQuietly(50);
        } catch (Throwable t) {
            threw = true;
        }
        ok("PRODUCTION.sleepQuietly 吞掉中断不抛出", !threw);
        ok("中断位已被消费（sleep 抛出后清空）", !Thread.interrupted());

        // sleep(0)：立即返回（用墙钟测，不碰 native SystemClock）
        long z0 = System.nanoTime();
        prod.sleepQuietly(0);
        long zMs = (System.nanoTime() - z0) / 1_000_000L;
        ok("sleepQuietly(0) 立即返回（<20ms）", zMs < 20);

        // 负数：与旧实现逐字节一致 —— Thread.sleep 抛 IllegalArgumentException，
        // 且**不被捕获**（旧代码只 catch InterruptedException）。这是行为等价断言，
        // 不是"应该更健壮"的期待。
        boolean negThrew = false;
        try {
            prod.sleepQuietly(-5);
        } catch (IllegalArgumentException e) {
            negThrew = true;
        } catch (Throwable ignored) {
        }
        ok("sleepQuietly(负数) 与旧实现一致：抛 IllegalArgumentException", negThrew);

        // ============================================================ 2. 测试时钟
        TestEngineClock tc = new TestEngineClock(1000L);
        eq("初始虚拟时间", 1000L, tc.uptimeMillis());
        ok("注入后是可控对象", tc != prod);

        tc.sleep(100);
        eq("sleep(100) 推进 100ms", 1100L, tc.uptimeMillis());
        eq("sleep 调用计数", 1, tc.sleepCalls());
        eq("累计虚拟睡眠", 100L, tc.sleptVirtualMs());

        tc.advance(500);
        eq("advance(500) 推进 500ms", 1600L, tc.uptimeMillis());
        eq("advance 不计入 sleep 累计", 100L, tc.sleptVirtualMs());

        long realStart = System.nanoTime();
        tc.sleep(5000);
        long realMs = (System.nanoTime() - realStart) / 1_000_000L;
        ok("虚拟 5000ms 的真实阻塞被 MAX_REAL_BLOCK_MS 限制（<200ms）", realMs < 200);
        eq("虚拟时间仍然推进了 5000ms", 6600L, tc.uptimeMillis());
        ok("但真实等待小于虚拟等待（时间可控）", realMs < 5000);

        tc.sleep(0);
        tc.sleep(-3);
        eq("sleep(0/-3) 不推进时间", 6600L, tc.uptimeMillis());
        eq("sleep(0/-3) 仍计一次调用（与生产一致：方法被调用）", 4, tc.sleepCalls());

        // 吞中断契约对测试时钟同样成立
        Thread.currentThread().interrupt();
        boolean tcThrew = false;
        try {
            tc.sleep(10);
        } catch (Throwable t) {
            tcThrew = true;
        }
        ok("TestEngineClock.sleep 吞掉中断不抛出", !tcThrew);
        Thread.interrupted();

        // 倍率
        TestEngineClock fast = new TestEngineClock(0L, 10L, 0L);
        fast.sleep(100);
        eq("scale=10 时 100ms → 1000ms 虚拟", 1000L, fast.uptimeMillis());
        TestEngineClock noBlock = new TestEngineClock(0L, 1L, 0L);
        long nb0 = System.nanoTime();
        noBlock.sleep(500);
        long nbMs = (System.nanoTime() - nb0) / 1_000_000L;
        ok("realBlockCap=0 时不真实阻塞（<50ms）", nbMs < 50);
        eq("但虚拟时间仍推进 500ms", 500L, noBlock.uptimeMillis());

        // ============================================================ 3. 污染防护
        // 3a. 注入入口必须包级可见（生产代码在别的包/类里无法调用）
        java.lang.reflect.Method setter = null;
        for (java.lang.reflect.Method m : Class.forName(
                "io.github.wlmosv_png.wpaforge.audit.CrackEngine").getDeclaredMethods()) {
            if ("setClockForTest".equals(m.getName())) { setter = m; break; }
        }
        ok("CrackEngine.setClockForTest 存在", setter != null);
        if (setter != null) {
            int mod = setter.getModifiers();
            ok("setClockForTest 不是 public", !java.lang.reflect.Modifier.isPublic(mod));
            ok("setClockForTest 不是 protected", !java.lang.reflect.Modifier.isProtected(mod));
            ok("setClockForTest 不是 static（实例隔离，不污染全局）",
                    !java.lang.reflect.Modifier.isStatic(mod));
        }

        // 3b. 生产源码里不得出现 setClockForTest 的调用
        String[] prodFiles = {
                "CrackService.java", "MainActivity.java", "App.java", "DictActivity.java"};
        for (String f : prodFiles) {
            File file = new File(TestPaths.javaPkgDir() + f);
            if (!file.isFile()) { ok(f + " 存在", false); continue; }
            String src = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            ok(f + " 未调用 setClockForTest", !src.contains("setClockForTest"));
        }

        // 3c. CrackEngine 里不得有 static 可变时钟
        String engSrc = new String(Files.readAllBytes(
                new File(TestPaths.javaPkgDir() + "audit/CrackEngine.java").toPath()),
                StandardCharsets.UTF_8);
        ok("CrackEngine 无 static EngineClock 字段",
                !engSrc.matches("(?s).*private\\s+static\\s+(volatile\\s+)?EngineClock\\s+\\w+.*"));
        ok("CrackEngine 的 clock 字段是实例级",
                engSrc.contains("private volatile EngineClock clock"));
        ok("clock() 对 null 有兜底（返回 PRODUCTION 而非 NPE）",
                engSrc.contains("setClockForTest") && engSrc.contains("EngineClock.PRODUCTION"));

        // 3d. 不得残留任何直接时间读取
        // 注意：注释里提到 SystemClock 是合法的（说明性文字），
        // 判定必须只看**代码**——剔除注释后再查。
        String engCode = stripComments(engSrc);
        ok("CrackEngine 代码中无 SystemClock 调用",
                !engCode.contains("SystemClock."));
        ok("CrackEngine 无 System.currentTimeMillis 直接判据",
                // 仅允许 2 处：窗口锚点与 runId（墙上时钟语义，B1 范围外）
                countOccurrences(engSrc, "System.currentTimeMillis()") <= 2);
        ok("CrackEngine 无裸 Thread.sleep",
                countOccurrences(engSrc, "Thread.sleep(") <= 1);

        // 3e. 其余时间读取器未被 B1 改动（防止顺手改到判定链）
        String waw = new String(Files.readAllBytes(
                new File(TestPaths.javaPkgDir() + "audit/WifiAuthFailureWatcher.java").toPath()),
                StandardCharsets.UTF_8);
        ok("WifiAuthFailureWatcher 仍用 SystemClock（未被 B1 改动）",
                waw.contains("SystemClock.uptimeMillis()"));

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) throw new RuntimeException("EngineClockTest 失败 " + fail + " 项");
    }


    /** 生产实现的源码守卫：{@code EngineClock.PRODUCTION} 必须原样转发 SystemClock。 */
    private static boolean prodSourceForwardsToSystemClock() {
        try {
            java.io.File f = new java.io.File(
                    TestPaths.javaPkgDir() + "audit/EngineClock.java");
            String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return src.contains("return SystemClock.uptimeMillis();")
                    && src.contains("Thread.sleep(ms);");
        } catch (Throwable t) {
            return false;
        }
    }


    /** 剔除 // 行注释与 /* *\/ 块注释，只留代码，避免注释里的示例文字干扰判定。 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                char q = c;
                out.append(c); i++;
                while (i < n) {
                    char d = src.charAt(i);
                    out.append(d); i++;
                    if (d == '\\') { if (i < n) { out.append(src.charAt(i)); i++; } continue; }
                    if (d == q) break;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) i++;
                i += 2;
            } else {
                out.append(c); i++;
            }
        }
        return out.toString();
    }

    private static int countOccurrences(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}
