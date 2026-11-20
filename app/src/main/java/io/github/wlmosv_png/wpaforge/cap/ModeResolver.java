package io.github.wlmosv_png.wpaforge.cap;

/**
 * **模式解析 —— 把用户意愿（可能含 AUTO）解析成本次任务固定的执行模式**
 * （2026-11-20）。
 *
 * <h3>要修的问题</h3>
 * 设置页可以选「自动」（{@code RunMode.AUTO = 2}），但执行入口三处判断都是
 * {@code runModeId == ROOTLESS}：
 * <pre>
 *   CrackService:1435   if (runModeId == ROOTLESS) {...} else {...}  ← AUTO 落到 else
 *   CrackService:1507   dRootless = (runModeId == ROOTLESS)
 *   CrackService:1588   shizukuMode = (runModeId == ROOTLESS) && ...
 * </pre>
 * 于是 **AUTO 被当成 Root 处理** —— 而用户选「自动」的意思恰恰是
 * 「有 root 用 root，没 root 用 Shizuku」。
 *
 * <h3>解析规则（与设置页文案一致）</h3>
 * <pre>
 *   ROOT      → 固定用 Root（即使 su 不可用也如实失败，不偷偷换）
 *   ROOTLESS  → 固定用无 Root（Shizuku 可用走 Shizuku；不可用走 Specifier）
 *   AUTO      → su 可用 → ROOT
 *               否则 Shizuku 可用 → ROOTLESS
 *               否则 → BLOCKED（明确阻止，不猜）
 * </pre>
 *
 * <p><b>解析完成后本次任务固定该模式</b>，执行中不再改变 —— 避免
 * 「跑到一半换执行器」导致证据链归因错乱。
 *
 * <p>纯逻辑、无 Android 依赖。
 */
public final class ModeResolver {

    private ModeResolver() {
    }

    /** 解析结果。 */
    public static final class Resolved {
        /** 实际执行模式（{@link RunMode#ROOT} 或 {@link RunMode#ROOTLESS}）。 */
        public final int mode;
        /** 是否可以开跑。false 时 {@link #blockReason} 非空。 */
        public final boolean canRun;
        /** 不能跑的原因（人话，可为空）。 */
        public final String blockReason;
        /** 用户原始意愿。 */
        public final int requested;

        Resolved(int mode, boolean canRun, String blockReason, int requested) {
            this.mode = mode;
            this.canRun = canRun;
            this.blockReason = blockReason == null ? "" : blockReason;
            this.requested = requested;
        }
    }

    /**
     * 解析实际模式。
     *
     * @param requested   用户意愿（{@link RunMode#ROOT} / ROOTLESS / AUTO）
     * @param rootUsable  {@code su -c id} 是否真的返回 uid=0
     * @param shizukuUsable Shizuku 是否可执行命令（{@link ChannelReadiness#usable}）
     */
    public static Resolved resolve(int requested, boolean rootUsable, boolean shizukuUsable) {
        if (requested == RunMode.ROOT) {
            // 用户明确要 Root：不可用就如实失败，不偷偷换通道
            return rootUsable
                    ? new Resolved(RunMode.ROOT, true, "", requested)
                    : new Resolved(RunMode.ROOT, false,
                    "选了 Root 模式，但 su 不可用（未 root 或未授权本应用）。"
                            + "请到 root 管理器授权，或改选「无 Root」。", requested);
        }
        if (requested == RunMode.ROOTLESS) {
            // 用户明确要无 Root：Shizuku 可用就走 Shizuku，否则走 Specifier（仍可跑）
            return new Resolved(RunMode.ROOTLESS, true, "", requested);
        }
        // AUTO：按优先级挑一个**已验证可用**的
        if (rootUsable) {
            return new Resolved(RunMode.ROOT, true, "", requested);
        }
        if (shizukuUsable) {
            return new Resolved(RunMode.ROOTLESS, true, "", requested);
        }
        return new Resolved(RunMode.ROOT, false,
                "自动模式：su 与 Shizuku 都不可用。请装 Shizuku 并用「无线调试」启动后授权本应用，"
                        + "或给本应用 root 授权。", requested);
    }

    /** 一行诊断（不含敏感信息）。 */
    public static String describe(Resolved r, boolean zh) {
        String reqName = RunMode.nameOf(r.requested);
        String realName = RunMode.nameOf(r.mode);
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "请求=" : "requested=").append(reqName)
                .append(zh ? " 实际=" : " resolved=").append(realName)
                .append(zh ? " 可运行=" : " canRun=").append(r.canRun);
        if (!r.canRun) sb.append(" 原因=").append(r.blockReason);
        return sb.toString();
    }
}
