package io.github.wlmosv_png.wpaforge.cap;

/**
 * **执行身份**模型 —— 把「谁在执行」从「哪个通道」里独立出来。
 *
 * <h3>为什么需要它（2026-11-20 真机事故）</h3>
 * 之前整个代码库把「Shizuku 可用」当成一个布尔量，然后用它推断一切能力。
 * 但 Shizuku 提供的**执行身份**可能是三种完全不同的东西：
 * <pre>
 *   uid 0    (root)   → 能读 WifiConfigStore.xml、能跑特权命令
 *   uid 1000 (system) → 部分特权，需实测
 *   uid 2000 (shell)  → 能跑 cmd wifi，但读不到 /data/misc
 * </pre>
 * 「Shizuku 已授权」只说明拿到了 binder，**不说明拿到的是哪个身份**。
 * 把三者混为一谈，就会出现「界面上说 Shizuku 可用，实际某能力根本做不到」
 * 或者反过来「实际有 root 能力，却只按 shell 限制自己」。
 *
 * <p>参考：WiFi 工具箱 `ShizukuUtil.kt:572-576` 就是这么做的 ——
 * <pre>
 *   val user = when (Shizuku.getUid()) {
 *       0 -> "root"; 1000 -> "system"; else -> "shell"
 *   }
 * </pre>
 *
 * <p>纯逻辑、无 Android 依赖。
 */
public final class ExecutorIdentity {

    /** 身份种类。 */
    public enum Kind {
        /** 应用自身 UID（无任何特权）。 */
        APP,
        /** uid 2000，Android shell。 */
        SHELL,
        /** uid 1000，system。 */
        SYSTEM,
        /** uid 0，root。 */
        ROOT,
        /** 未知（探测失败）。 */
        UNKNOWN
    }

    /** 实际 uid（-1 = 未知）。 */
    public final int uid;
    /** 身份种类。 */
    public final Kind kind;
    /** SELinux context（可空，仅用于诊断展示）。 */
    public final String selinuxContext;
    /** 提供这个身份的通道名（Root / Shizuku / App）。 */
    public final String via;

    private ExecutorIdentity(int uid, Kind kind, String selinux, String via) {
        this.uid = uid;
        this.kind = kind;
        this.selinuxContext = selinux == null ? "" : selinux;
        this.via = via == null ? "" : via;
    }

    /** 由 uid 推断身份种类。 */
    public static Kind kindOf(int uid) {
        if (uid == 0) return Kind.ROOT;
        if (uid == 1000) return Kind.SYSTEM;
        if (uid == 2000) return Kind.SHELL;
        if (uid < 0) return Kind.UNKNOWN;
        return Kind.APP;
    }

    public static ExecutorIdentity of(int uid, String selinux, String via) {
        return new ExecutorIdentity(uid, kindOf(uid), selinux, via);
    }

    /** 应用自身身份。 */
    public static ExecutorIdentity app(int appUid) {
        return new ExecutorIdentity(appUid, Kind.APP, "", "App");
    }

    /** 探测失败。 */
    public static ExecutorIdentity unknown(String via) {
        return new ExecutorIdentity(-1, Kind.UNKNOWN, "", via);
    }

    // ------------------------------------------------------------ 能力推断

    /** 是否具备 root 级身份（uid 0）。 */
    public boolean isRoot() {
        return kind == Kind.ROOT;
    }

    /** 是否具备 system 级身份（uid 1000）。 */
    public boolean isSystem() {
        return kind == Kind.SYSTEM;
    }

    /** 是否具备 shell 级或更高身份（uid 0 / 1000 / 2000）。 */
    public boolean hasShellOrBetter() {
        return kind == Kind.ROOT || kind == Kind.SYSTEM || kind == Kind.SHELL;
    }

    /**
     * 能否读 {@code /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml}。
     *
     * <p><b>实测结论</b>（PJX110 / Android 15 / ColorOS 15）：
     * <ul>
     *   <li>uid 0 → 可读（读到 XML 头）</li>
     *   <li>uid 2000 + u:r:shell:s0 → {@code Permission denied}（SELinux 拦）</li>
     * </ul>
     * 所以只有 root 才敢说能读；system 需要单独实测，这里按**不能**处理
     * （宁可少说一个能力，不可谎报）。
     */
    public boolean canReadConfigStore() {
        return isRoot();
    }

    /**
     * 能否执行 {@code cmd wifi} 全系子命令。
     *
     * <p>实测：shell 身份能跑 status / list-networks / forget-network；
     * connect-network 在 AOSP 要求 root UID，但部分 OEM 放宽（本机 ColorOS 放宽）。
     * **所以这里只回答「能跑 cmd wifi 这个入口」，具体子命令要单独探测。**
     */
    public boolean canRunCmdWifi() {
        return hasShellOrBetter();
    }

    /** 一行诊断（不含敏感信息）。 */
    public String describe(boolean zh) {
        String k;
        switch (kind) {
            case ROOT: k = zh ? "root" : "root"; break;
            case SYSTEM: k = zh ? "system" : "system"; break;
            case SHELL: k = zh ? "shell" : "shell"; break;
            case APP: k = zh ? "应用自身" : "app"; break;
            default: k = zh ? "未知" : "unknown"; break;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(k).append("(uid=").append(uid).append(')');
        if (!via.isEmpty()) sb.append(zh ? " 经由 " : " via ").append(via);
        if (!selinuxContext.isEmpty()) sb.append(" · ").append(selinuxContext);
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe(false);
    }
}
