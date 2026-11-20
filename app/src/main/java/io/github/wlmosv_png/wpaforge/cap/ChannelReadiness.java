package io.github.wlmosv_png.wpaforge.cap;

/**
 * **通道可用性判定 —— 唯一入口**（2026-11-20）。
 *
 * <h3>为什么必须收口</h3>
 * 改造前，「Shizuku 能不能用」这个判断散落在 **7 个调用点**，各自写法不同：
 * <pre>
 *   CrackService:1509   dsz.state() == S_READY          ← 只认 shell
 *   CrackService:1588   s0.usable()                     ← 接受两种
 *   App:370             S_READY || S_READY_NOT_SHELL
 *   MainActivity:11792  st != S_READY && st != S_READY_NOT_SHELL
 *   MainActivity:14707  st == S_READY || st == S_READY_NOT_SHELL
 *   ...
 * </pre>
 * 于是同一个「Shizuku 可用」在不同路径上得出不同结论 ——
 * 主链路走 Shizuku、直调注入却以为不可用，或反过来。
 *
 * <p>现在：所有调用点只能问本类，不允许自己判 {@code state()}。
 *
 * <p>纯逻辑、无 Android 依赖，可离线穷举。
 */
public final class ChannelReadiness {

    private ChannelReadiness() {
    }

    /** 可用性结论。 */
    public enum Verdict {
        /** 可以执行命令（shell 或 root 身份都算）。 */
        USABLE,
        /** 装了但服务没起。 */
        NOT_RUNNING,
        /** 没装。 */
        NOT_INSTALLED,
        /** 服务在跑但本应用没被授权。 */
        NO_PERMISSION,
        /** 状态未知（探测失败 / Binder 死亡 / 身份读取失败）。 */
        UNKNOWN
    }

    /**
     * 由 ShizukuChannel 的状态码判定可用性。
     *
     * <p><b>关键</b>：{@code S_READY}（shell 身份）与 {@code S_READY_NOT_SHELL}
     * （root/system 身份）**都算可用** —— 身份差异由 {@link ExecutorIdentity}
     * 表达，不该影响「能不能用」。
     *
     * @param stateCode {@link ShizukuChannel} 的 S_* 常量；负数视为未探测
     */
    public static Verdict of(int stateCode) {
        if (stateCode == ShizukuChannel.S_READY
                || stateCode == ShizukuChannel.S_READY_NOT_SHELL) {
            return Verdict.USABLE;
        }
        if (stateCode == ShizukuChannel.S_NOT_RUNNING) return Verdict.NOT_RUNNING;
        if (stateCode == ShizukuChannel.S_NOT_INSTALLED) return Verdict.NOT_INSTALLED;
        if (stateCode == ShizukuChannel.S_NO_PERMISSION) return Verdict.NO_PERMISSION;
        return Verdict.UNKNOWN;
    }

    /** 是否可执行命令。 */
    public static boolean usable(int stateCode) {
        return of(stateCode) == Verdict.USABLE;
    }

    /** 人话原因（界面与日志共用）。 */
    public static String reasonZh(Verdict v) {
        switch (v) {
            case USABLE: return "可执行命令";
            case NOT_RUNNING: return "已安装但服务未运行";
            case NOT_INSTALLED: return "未安装 Shizuku";
            case NO_PERMISSION: return "服务在运行，但本应用未获授权";
            default: return "状态未知（探测失败或 Binder 断开）";
        }
    }

    public static String reasonEn(Verdict v) {
        switch (v) {
            case USABLE: return "can execute commands";
            case NOT_RUNNING: return "installed but service not running";
            case NOT_INSTALLED: return "Shizuku not installed";
            case NO_PERMISSION: return "service running but this app is not granted";
            default: return "unknown (probe failed or binder died)";
        }
    }

    /**
     * 该结论是否**允许启动需要特权通道的任务**。
     *
     * <p>只有 USABLE 允许；其余全部阻止。这是「后端未准备好不许进候选循环」
     * 的单点判据。
     */
    public static boolean mayStartTask(Verdict v) {
        return v == Verdict.USABLE;
    }
}
