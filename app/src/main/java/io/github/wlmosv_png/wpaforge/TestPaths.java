package io.github.wlmosv_png.wpaforge;

/**
 * 门禁测试的**唯一**源码根解析入口。
 *
 * <h3>为什么需要它（2026-10-31 审计 A 阶段）</h3>
 * 此前 24 个测试类各自把一份旧工作副本的绝对路径写死在常量里，
 * 于是把项目根换成 {@code /storage/emulated/0/Download/projects/netscope} 跑门禁时，
 * 这些测试**实际扫描的仍是另一份源码**——门禁结果与被测代码不是同一个东西，
 * 属于「假绿」风险。本类把路径解析收敛到一处，并支持显式覆盖。
 *
 * <h3>解析顺序</h3>
 * <ol>
 *   <li>系统属性 {@code wpaforge.root}（{@code java -Dwpaforge.root=...}）——
 *       门禁脚本用它显式钉死被测根目录；</li>
 *   <li>环境变量 {@code WPAFORGE_ROOT}；</li>
 *   <li>向上回溯：从当前工作目录（{@code user.dir}）逐级找
 *       {@code app/src/main/java/.../wpaforge} 存在的那一层。</li>
 * </ol>
 *
 * <p>三者都拿不到时**抛异常**，而不是退回某个默认路径——
 * 「猜一个路径然后扫它」正是要根除的行为。
 *
 * <p>本类**不碰** production 代码，只被 {@code *Test.java} 使用。
 */
public final class TestPaths {

    /** 系统属性名：由门禁脚本设置为其传入的项目根。 */
    public static final String PROP_ROOT = "wpaforge.root";
    /** 环境变量名：备用覆盖入口。 */
    public static final String ENV_ROOT = "WPAFORGE_ROOT";
    /** 包目录相对项目根的路径（门禁自检用）。 */
    public static final String PKG_REL = "app/src/main/java/io/github/wlmosv_png/wpaforge";

    private static volatile String cachedRoot;

    private TestPaths() {}

    /** 项目根（不含结尾斜杠）。每次调用都重新解析，失败即抛。 */
    public static String root() {
        String cached = cachedRoot;
        if (cached != null) return cached;

        String fromProp = trimToNull(System.getProperty(PROP_ROOT));
        if (fromProp != null) {
            cachedRoot = normalize(fromProp);
            return cachedRoot;
        }
        String fromEnv = trimToNull(System.getenv(ENV_ROOT));
        if (fromEnv != null) {
            cachedRoot = normalize(fromEnv);
            return cachedRoot;
        }
        String found = discoverFromCwd();
        if (found != null) {
            cachedRoot = found;
            return cachedRoot;
        }
        throw new IllegalStateException(
                "无法确定 WPAForge 项目根：请设置 -D" + PROP_ROOT + "=<项目根> 或环境变量 "
                        + ENV_ROOT + "，或在项目目录内运行。当前 user.dir="
                        + System.getProperty("user.dir"));
    }

    /** {@code <root>/app/src/main/java/io/github/wlmosv_png/wpaforge/}（带结尾斜杠）。 */
    public static String javaPkgDir() {
        return root() + "/" + PKG_REL + "/";
    }

    /** {@code <root>/app/src/main/java/}（带结尾斜杠）。 */
    public static String javaSrcRoot() {
        return root() + "/app/src/main/java/";
    }

    /** 项目根下的相对路径。 */
    public static String under(String rel) {
        return root() + "/" + rel;
    }

    /**
     * 门禁自证：返回**实际被测**的项目根，便于把身份写进测试输出。
     * 与 {@link #root()} 相同，单独命名是为了在日志里可检索。
     */
    public static String identity() {
        return root();
    }

    /** 仅供测试内部重置缓存（同一 JVM 内多次解析）。 */
    static void resetForTest() {
        cachedRoot = null;
    }

    // ---------------------------------------------------------------- internals

    private static String discoverFromCwd() {
        String cwd = trimToNull(System.getProperty("user.dir"));
        if (cwd == null) return null;
        java.io.File dir = new java.io.File(cwd).getAbsoluteFile();
        for (int hops = 0; dir != null && hops < 8; hops++) {
            if (new java.io.File(dir, PKG_REL).isDirectory()) {
                return normalize(dir.getAbsolutePath());
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    private static String normalize(String p) {
        String s = p.trim();
        while (s.endsWith("/") && s.length() > 1) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
