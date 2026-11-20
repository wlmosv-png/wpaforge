package io.github.wlmosv_png.wpaforge.cap;

import android.content.Context;

/**
 * **执行器选择的唯一入口**（2026-11-20 整改第二阶段）。
 *
 * <h3>要修的问题</h3>
 * 改造前，「用哪条通道执行命令」在四处各自决定：
 * <pre>
 *   CrackService:1435   runModeId == ROOTLESS ? ... : ...        ← AUTO 落到 Root
 *   CrackService:1509   dsz.state() == S_READY                   ← 只认 shell 身份
 *   CrackService:1588   s0.usable()                              ← 接受两种
 *   App:206 / App:368   new ShizukuChannel(App.this)             ← 诊断另建实例
 * </pre>
 * 于是同一个「Shizuku 可用」在不同路径上得出不同结论；诊断入口用的是
 * **另一个 ShizukuChannel 实例**，它的探测结果与正式任务毫无关系 ——
 * 「诊断通过」不能证明「正式任务会用同一个执行器」。
 *
 * <h3>现在的契约</h3>
 * <ol>
 *   <li>所有调用方（正式任务、启动自检、只读诊断、UI 状态条）只能问
 *       {@link #resolve} 或 {@link #resolveCached}，**不允许自己判 state()**。</li>
 *   <li>{@link Plan#executor} 是**唯一**能执行命令的对象。诊断与正式任务
 *       拿到的是同一个类的实例，走同一份 {@link ShizukuChannel#exec}。</li>
 *   <li>解析结果里**同时**给出请求模式、实际模式、执行器类型、真实身份、
 *       可用性结论 —— 界面与日志都从这里取，不再靠推断。</li>
 * </ol>
 *
 * <h3>为什么不能"Root 不可用就偷偷回落 su"</h3>
 * 用户明确选 Root 时，su 不可用必须**如实失败**（{@link ModeResolver} 已保证）。
 * 同样地，一旦解析出执行器是 Shizuku，本次任务就**固定**用它；
 * 它中途失效就如实报错并阻止任务，绝不悄悄换到另一条身份 ——
 * 否则「界面上写 Shizuku、证据来自 su」会让归因链彻底断掉。
 *
 * <p>本类只做**选择**与**持有**，不含判定逻辑；判定仍在 CrackEngine。
 */
public final class ExecutorProvider {

    /** 执行器种类。 */
    public enum Kind {
        /** 用 su 子进程。 */
        ROOT_SU,
        /** 用 Shizuku 提供的 binder 起子进程。 */
        SHIZUKU,
        /** 无特权通道（WifiNetworkSpecifier 逐条确认）。 */
        SPECIFIER,
        /** 什么都没有 —— 任务必须被阻止。 */
        NONE
    }

    /** 一次解析的完整结论。 */
    public static final class Plan {
        /** 用户在设置页选的（可能含 AUTO）。 */
        public final int requestedMode;
        /** 解析后的实际模式（只会是 ROOT 或 ROOTLESS）。 */
        public final int resolvedMode;
        /** 实际执行器种类。 */
        public final Kind kind;
        /** 通道可用性结论。 */
        public final ChannelReadiness.Verdict verdict;
        /** 能不能开跑。false 时 {@link #blockReason} 非空。 */
        public final boolean canRun;
        /** 不能跑的人话原因。 */
        public final String blockReason;
        /** Shizuku 通道实例（kind == SHIZUKU 时非 null）。 */
        public final ShizukuChannel shizuku;
        /** 真实执行身份（可能 UNKNOWN）。 */
        public final ExecutorIdentity identity;
        /** Shizuku 状态码（未探测为 -1）。 */
        public final int shizukuState;
        /** su 是否真的可用（返回过 uid=0）。 */
        public final boolean suUsable;
        /** 一行诊断（不含敏感信息）。 */
        public final String detail;

        Plan(int requestedMode, int resolvedMode, Kind kind,
             ChannelReadiness.Verdict verdict, boolean canRun, String blockReason,
             ShizukuChannel shizuku, ExecutorIdentity identity, int shizukuState,
             boolean suUsable, String detail) {
            this.requestedMode = requestedMode;
            this.resolvedMode = resolvedMode;
            this.kind = kind;
            this.verdict = verdict;
            this.canRun = canRun;
            this.blockReason = blockReason == null ? "" : blockReason;
            this.shizuku = shizuku;
            this.identity = identity == null ? ExecutorIdentity.unknown("") : identity;
            this.shizukuState = shizukuState;
            this.suUsable = suUsable;
            this.detail = detail == null ? "" : detail;
        }

        /** 执行器名（界面显示用，不含 uid）。 */
        public String kindName() {
            switch (kind) {
                case ROOT_SU: return "Root";
                case SHIZUKU: return "Shizuku";
                case SPECIFIER: return "Specifier";
                default: return "未接入";
            }
        }

        /**
         * 完整标签：{@code Shizuku / shell(uid=2000) · u:r:shell:s0}。
         *
         * <p>这是界面「执行通道」那行的**唯一来源** —— 它来自实际解析出的
         * 执行器与真实身份，不是从设置页的授权状态推断的。
         */
        public String label() {
            if (kind == Kind.NONE) return "未接入";
            StringBuilder sb = new StringBuilder(kindName());
            String idDesc = identity.describe(false);
            if (!idDesc.isEmpty() && identity.uid >= 0) {
                sb.append(" / ").append(idDesc);
            }
            return sb.toString();
        }
    }

    /** 缓存（避免 UI 每次刷新都起 su）。 */
    private static volatile Plan cached;
    private static volatile long cachedAtMs = 0L;
    /** 缓存有效期：只用于**展示**，执行前必须重新 resolve。 */
    private static final long CACHE_TTL_MS = 8000L;

    private ExecutorProvider() {
    }

    /**
     * **完整解析**（会起 su 探测，**不要在主线程调用**）。
     *
     * <p>正式任务在入口处调一次，然后把结果固定下来用整轮。
     *
     * @param ctx 上下文
     */
    public static Plan resolve(Context ctx) {
        int requested = io.github.wlmosv_png.wpaforge.Prefs.requestedMode(ctx);

        // ── su 探测：**只起一次**（旧实现连着调两次 RootProber.exec，
        //    既白付一次 su 启动成本，逻辑上也是错的：两次都非 null 才算可用，
        //    任一次抖动就把结论翻成不可用）。
        boolean suOk = probeSu();

        // ── Shizuku 探测：唯一可用性判据走 ChannelReadiness
        ShizukuChannel shz = new ShizukuChannel(ctx);
        int st;
        try {
            st = shz.probe();
        } catch (Throwable t) {
            st = -1;
        }
        ChannelReadiness.Verdict verdict = ChannelReadiness.of(st);
        boolean shzOk = ChannelReadiness.mayStartTask(verdict);

        // ── 模式解析（唯一实现，见 ModeResolver）
        ModeResolver.Resolved r = ModeResolver.resolve(requested, suOk, shzOk);

        // ── 执行器落点
        Kind kind;
        boolean canRun = r.canRun;
        String block = r.blockReason;

        if (!canRun) {
            kind = Kind.NONE;
        } else if (r.mode == RunMode.ROOT) {
            kind = Kind.ROOT_SU;
        } else if (shzOk) {
            kind = Kind.SHIZUKU;
        } else {
            // 无 Root 且 Shizuku 不可用 → 只能走系统确认框（仍可跑，但要逐次确认）
            kind = Kind.SPECIFIER;
        }

        // 无 Root + Shizuku 不可用：ModeResolver 认为「可以跑」，但那只是
        // 「Specifier 兜底还能走」。这里保留其结论，但在 detail 里写清楚，
        // 免得用户以为在跑 Shizuku。
        if (canRun && kind == Kind.SPECIFIER) {
            block = "";
        }

        ExecutorIdentity id;
        if (kind == Kind.SHIZUKU) {
            id = shz.identity();
        } else if (kind == Kind.ROOT_SU) {
            id = ExecutorIdentity.of(0, "", "su");
        } else {
            id = ExecutorIdentity.unknown("");
        }

        String detail = buildDetail(requested, r, kind, verdict, st, suOk, id);

        Plan p = new Plan(requested, r.mode, kind, verdict, canRun, block,
                (kind == Kind.SHIZUKU ? shz : null), id, st, suOk, detail);
        cached = p;
        cachedAtMs = System.currentTimeMillis();
        return p;
    }

    /**
     * **带缓存的解析**（只用于界面展示；执行前必须 {@link #resolve}）。
     *
     * <p>UI 每帧都调一次 resolve 会反复起 su —— 那是灾难。这里给一个
     * 8 秒有效期的快照，过期才重新探测。
     */
    public static Plan resolveCached(Context ctx) {
        Plan p = cached;
        long now = System.currentTimeMillis();
        if (p != null && now - cachedAtMs < CACHE_TTL_MS) return p;
        return resolve(ctx);
    }

    /** 只读缓存（不触发探测，UI 首帧安全）。 */
    public static Plan peek() {
        return cached;
    }

    /** 让缓存失效（用户改了模式、点了重新检测、Shizuku 授权回调）。 */
    public static void invalidate() {
        cached = null;
        cachedAtMs = 0L;
    }

    /**
     * su 探测：真的执行一次 {@code su -c id} 并检查输出里有没有 {@code uid=0}。
     *
     * <p>为什么不是「看 /system/xbin/su 在不在」：装了 su 但没给本应用授权时
     * 文件在、命令不通 —— 那种情况下「文件存在」会把结论判成可用，
     * 接着每条候选都等满超时（真机 11 秒/条的现场）。
     */
    public static boolean probeSu() {
        try {
            String out = io.github.wlmosv_png.wpaforge.audit.RootProber
                    .exec(null, new String[]{"su", "-c", "id"}, 3000);
            if (out == null) return false;
            // 必须真的看到 uid=0；只看「有输出」会把 "su: not found" 也算成可用
            return out.contains("uid=0");
        } catch (Throwable t) {
            return false;
        }
    }

    private static String buildDetail(int requested, ModeResolver.Resolved r, Kind kind,
                                      ChannelReadiness.Verdict verdict, int st,
                                      boolean suOk, ExecutorIdentity id) {
        StringBuilder sb = new StringBuilder();
        sb.append("请求=").append(RunMode.nameOf(requested))
                .append(" 实际=").append(RunMode.nameOf(r.mode))
                .append(" 执行器=").append(kind.name())
                .append(" su=").append(suOk ? "可用" : "不可用")
                .append(" Shizuku=").append(verdict.name()).append("(state=").append(st).append(')');
        if (id.uid >= 0) sb.append(" 身份=").append(id.describe(false));
        if (!r.canRun && !r.blockReason.isEmpty()) sb.append(" 阻止=").append(r.blockReason);
        return sb.toString();
    }
}
