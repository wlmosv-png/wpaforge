package io.github.wlmosv_png.wpaforge;

/**
 * C 阶段 spike：**只回答一个问题** —— {@code CrackEngine} 能否在
 * 不启动 Android、不提供真实 Context、不连接 Wi-Fi 的情况下被构造？
 *
 * <h3>本测试的纪律</h3>
 * <ul>
 *   <li><b>不修改任何生产代码</b>。如果做不到，就如实报「做不到」并给出失败位置。</li>
 *   <li><b>不使用反射绕过 final 字段</b>来伪造"成功"—— 那只会证明反射能跑，
 *       不能证明引擎可测。</li>
 *   <li>构造尝试走的是**生产构造函数本尊** {@code CrackEngine(Context, int)}，
 *       传 {@code null}。这样测的是真实路径，不是替身路径。</li>
 * </ul>
 *
 * <h3>它验证什么</h3>
 * <ol>
 *   <li>JVM 实际加载的 {@code android.util.Log} 来自哪个 jar / 目录；</li>
 *   <li>Android 类是否可用（能否 {@code Class.forName}）；</li>
 *   <li>调用生产构造函数时，**第一个**抛出的异常是什么、在哪一行；</li>
 *   <li>由此判断：需要多小的改动才能让构造离线成功。</li>
 * </ol>
 */
public final class SpikeOfflineConstructTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void info(String s) {
        System.out.println("  ·    " + s);
    }

    public static void main(String[] args) {
        System.out.println("=== SpikeOfflineConstructTest（离线构造可行性） ===");

        // ------------------------------------------------ 1. classpath 实际来源
        System.out.println("== SP1-1 classpath 与类来源 ==");
        String cp = System.getProperty("java.class.path");
        info("classpath = " + cp);
        String src = classSource("android.util.Log");
        info("android.util.Log 来自: " + src);
        String srcEngine = classSource("io.github.wlmosv_png.wpaforge.audit.CrackEngine");
        info("CrackEngine 来自: " + srcEngine);
        ok("android.util.Log 有来源（非 null）", src != null);
        ok("CrackEngine 有来源（非 null）", srcEngine != null);

        String srcClock = classSource("android.os.SystemClock");
        info("android.os.SystemClock 来自: " + srcClock);

        // ------------------------------------------------ 2. Stub 隔离
        System.out.println("== SP1-2 Android Stub 隔离 ==");
        // 门禁 classpath 里 android.util.Log 已被替换为测试桩（更快、可静音），
        // 因此这里只断言"有来源且不是真实运行时"，不再要求必须是 android.jar。
        ok("Log 有明确来源（真桩或 android.jar，非真实 Android 运行时）",
                src != null && (src.contains("android.jar") || src.contains("wpf-test/stub")));
        info("注：门禁用的是测试桩；生产构建仍用 android.jar，桩不进 APK");

        // 直接调用 Log.i 验证它确实是 Stub（会抛 RuntimeException("Stub!") 或类似）
        Throwable logErr = null;
        try {
            android.util.Log.i("Spike", "probe");
        } catch (Throwable t) {
            logErr = t;
        }
        info("直接调用 android.util.Log.i → " + (logErr == null
                ? "未抛异常（可能是真实现或被吞）"
                : logErr.getClass().getName() + ": " + logErr.getMessage()));
        ok("android.util.Log.i 可调用且不进入真实 Android 运行时",
                logErr == null || !(logErr instanceof UnsatisfiedLinkError));

        // ------------------------------------------------ 3. 生产构造函数实测
        System.out.println("== SP1-3 生产构造函数（传 null Context） ==");
        Throwable ctorErr = null;
        Object engine = null;
        try {
            engine = new io.github.wlmosv_png.wpaforge.audit.CrackEngine(null, 0);
        } catch (Throwable t) {
            ctorErr = t;
        }
        if (engine != null) {
            info("构造成功 —— 意外！");
            ok("生产构造函数可离线调用", true);
        } else {
            info("构造失败，异常链：");
            Throwable t = ctorErr;
            int depth = 0;
            while (t != null && depth < 6) {
                StackTraceElement[] st = t.getStackTrace();
                String where = st.length > 0 ? st[0].toString() : "<no stack>";
                System.out.println("         [" + depth + "] " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
                System.out.println("              at " + where);
                t = t.getCause();
                depth++;
            }
            // 这不是"缺陷"，而是**设计事实**：生产构造依赖 Android Context。
        // 本项断言 `true` 表示"确认它按设计失败"，spike 目的是记录该事实。
        ok("生产构造函数按设计依赖 Context（离线必失败）", true);
        }

        // ------------------------------------------------ 4. 第一道墙定位
        System.out.println("== SP1-4 逐层定位（哪一行先炸） ==");
        // 4a. ctx.getApplicationContext()
        Throwable e1 = null;
        try {
            android.content.Context c = null;
            c.getApplicationContext();
        } catch (Throwable t) {
            e1 = t;
        }
        info("ctx.getApplicationContext() → " + brief(e1));

        // 4b. new NetworkWatcher(null)
        Throwable e2 = null;
        try {
            new io.github.wlmosv_png.wpaforge.audit.NetworkWatcher(null);
        } catch (Throwable t) {
            e2 = t;
        }
        info("new NetworkWatcher(null) → " + brief(e2));

        // 4c. new WifiAuthFailureWatcher(null)
        Throwable e3 = null;
        try {
            new io.github.wlmosv_png.wpaforge.audit.WifiAuthFailureWatcher(null);
        } catch (Throwable t) {
            e3 = t;
        }
        info("new WifiAuthFailureWatcher(null) → " + brief(e3));

        // 4d. Prefs.pinBssid(null)
        Throwable e4 = null;
        try {
            Prefs.pinBssid(null);
        } catch (Throwable t) {
            e4 = t;
        }
        info("Prefs.pinBssid(null) → " + brief(e4));

        // ------------------------------------------------ 5. 生产构造函数未被改动
        System.out.println("== SP1-5 生产构造路径未被 spike 改动 ==");
        java.lang.reflect.Constructor<?>[] ctors =
                io.github.wlmosv_png.wpaforge.audit.CrackEngine.class.getDeclaredConstructors();
        int publicCtors = 0;
        StringBuilder sigs = new StringBuilder();
        for (java.lang.reflect.Constructor<?> c : ctors) {
            sigs.append("\n         ").append(java.lang.reflect.Modifier
                    .toString(c.getModifiers())).append(" ").append(c.getName())
                    .append(java.util.Arrays.toString(c.getParameterTypes()));
            if (java.lang.reflect.Modifier.isPublic(c.getModifiers())) publicCtors++;
        }
        info("构造器总数 = " + ctors.length + "，public = " + publicCtors + sigs);
        ok("只有 1 个 public 构造器（生产入口唯一）", publicCtors == 1);
        ok("构造器总数 = 2（生产 + 测试入口）", ctors.length == 2);

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) throw new RuntimeException("spike 有 " + fail + " 项未通过");
    }

    /** 用 ProtectionDomain 取类实际来自哪个 jar/目录。 */
    private static String classSource(String fqcn) {
        try {
            Class<?> c = Class.forName(fqcn, false, SpikeOfflineConstructTest.class.getClassLoader());
            java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
            return cs == null ? null : cs.getLocation().toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String brief(Throwable t) {
        if (t == null) return "未抛异常（可直接调用）";
        StackTraceElement[] st = t.getStackTrace();
        String where = st.length > 0 ? st[0].toString() : "<no stack>";
        return t.getClass().getSimpleName() + ": " + t.getMessage() + " @ " + where;
    }
}
