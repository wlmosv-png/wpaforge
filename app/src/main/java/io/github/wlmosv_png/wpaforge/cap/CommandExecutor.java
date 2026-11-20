package io.github.wlmosv_png.wpaforge.cap;

/**
 * **命令执行的统一门面**（2026-11-20 第二阶段）。
 *
 * <h3>为什么还需要这一层</h3>
 * {@link ExecutorProvider} 只回答「用哪个执行器」。但「诊断入口」与「正式任务」
 * 除了执行器之外，还必须共用**同一条执行代码路径**，否则：
 * <pre>
 *   诊断：shz.exec("cmd wifi status")          ← 自己起 8s 超时
 *   正式：engine → shellChannel → shz.exec(...) ← 另一条包装，另一份超时
 * </pre>
 * 两条路径的包装层不同，「诊断通过」仍然证明不了「正式任务会成功」。
 *
 * <p>本类把「执行一条命令」收敛成一个静态方法：诊断、启动自检、
 * 正式任务的 {@code ShellChannel} 实现全部调它。它返回**带完整诊断字段**
 * 的 {@link Step}，界面/日志直接展示，不需要各处自己拼字符串。
 *
 * <h3>耗时分解（任务书第四部分）</h3>
 * 每条命令必须能回答「时间花在哪」：
 * <ul>
 *   <li>{@link Step#binderMs} —— 建进程 + 取流 + 等退出的 Binder 往返；</li>
 *   <li>{@link Step#waitMs} —— 子进程等待（含 waitForTimeout 轮询）；</li>
 *   <li>{@link Step#drainMs} —— 读 stdout/stderr 到 EOF；</li>
 *   <li>{@link Step#totalMs} —— 端到端。</li>
 * </ul>
 * 真机「每条 11.7 秒」这类问题，只有这三个数分开才定位得了
 * （旧实现只有 total，看不出是卡在 Binder、卡在子进程、还是卡在读流）。
 *
 * <p>纯逻辑之外只依赖 {@link ShizukuChannel}；没有 Android 上下文也能单测
 * {@link Step} 的字段组装与分类。
 */
public final class CommandExecutor {

    /** 失败分类（用于诊断输出，不参与判定）。 */
    public static final String F_TIMEOUT = "TIMEOUT";
    public static final String F_NONZERO_EXIT = "NONZERO_EXIT";
    public static final String F_EMPTY_OUTPUT = "EMPTY_OUTPUT";
    public static final String F_NOT_USABLE = "NOT_USABLE";
    public static final String F_EXCEPTION = "EXCEPTION";

    private CommandExecutor() {
    }

    /** 一条命令的完整执行记录。 */
    public static final class Step {
        /** 命令原文（诊断用；**不得含口令**）。 */
        public final String command;
        /** 执行器名（Root / Shizuku）。 */
        public final String executorId;
        /** 真实执行身份描述（如 {@code shell(uid=2000) · u:r:shell:s0}）。 */
        public final String identity;
        /** 请求的超时。 */
        public final long timeoutMs;
        /** 端到端耗时。 */
        public long totalMs;
        /** Binder 往返耗时（建进程 + 取流）。 */
        public long binderMs;
        /** 子进程等待耗时。 */
        public long waitMs;
        /** 读流耗时。 */
        public long drainMs;
        /** 退出码（-1 = 未知）。 */
        public int exitCode = -1;
        /** 是否超时。 */
        public boolean timedOut;
        /** 是否真的拿到了输出（区别于「命令没跑」）。 */
        public boolean produced;
        /** stdout 字节数。 */
        public int stdoutBytes;
        /** stderr 字节数。 */
        public int stderrBytes;
        /** 失败分类（空 = 成功）。 */
        public String failure = "";
        /** 异常类名（无异常为空）。 */
        public String exceptionClass = "";
        /** 输出正文（调用方决定是否使用；诊断只取长度）。 */
        public String output = "";

        Step(String command, String executorId, String identity, long timeoutMs) {
            this.command = command == null ? "" : command;
            this.executorId = executorId == null ? "" : executorId;
            this.identity = identity == null ? "" : identity;
            this.timeoutMs = timeoutMs;
        }

        /** 命令是否成功完成（有输出、退出码 0、未超时）。 */
        public boolean ok() {
            return failure.isEmpty() && !timedOut;
        }

        /**
         * 一行诊断（**不含命令内容与输出正文**，避免口令泄露）。
         *
         * <p>格式固定，便于 grep：
         * <pre>
         *   [Shizuku/shell(uid=2000)] 111ms exit=0 timeout=false out=234B err=0B
         *       binder=20ms wait=60ms drain=31ms
         * </pre>
         */
        public String brief() {
            return "[" + (executorId.isEmpty() ? "?" : executorId)
                    + (identity.isEmpty() ? "" : "/" + identity)
                    + "] " + totalMs + "ms exit=" + exitCode
                    + " timeout=" + timedOut
                    + " out=" + stdoutBytes + "B err=" + stderrBytes + "B"
                    + " binder=" + binderMs + "ms wait=" + waitMs + "ms drain=" + drainMs + "ms"
                    + (failure.isEmpty() ? "" : " failure=" + failure)
                    + (exceptionClass.isEmpty() ? "" : " ex=" + exceptionClass);
        }

        @Override
        public String toString() {
            return brief();
        }
    }

    /**
     * 执行一条命令（**诊断与正式任务共用这一条路径**）。
     *
     * @param plan    执行器结论（来自 {@link ExecutorProvider}）
     * @param command 命令
     * @param timeoutMs 超时
     * @return 完整执行记录；执行器不可用时返回 {@code failure=NOT_USABLE}
     */
    public static Step run(ExecutorProvider.Plan plan, String command, long timeoutMs) {
        String execId = plan == null ? "" : plan.kindName();
        String ident = plan == null ? "" : plan.identity.describe(false);
        Step st = new Step(command, execId, ident, timeoutMs);

        if (plan == null || plan.kind == ExecutorProvider.Kind.NONE) {
            st.failure = F_NOT_USABLE;
            st.output = "";
            return st;
        }
        if (plan.kind == ExecutorProvider.Kind.SHIZUKU) {
            ShizukuChannel shz = plan.shizuku;
            if (shz == null) {
                st.failure = F_NOT_USABLE;
                return st;
            }
            ShizukuChannel.Result r;
            long t0 = System.currentTimeMillis();
            try {
                r = shz.exec(command, timeoutMs);
            } catch (Throwable t) {
                st.totalMs = System.currentTimeMillis() - t0;
                st.failure = F_EXCEPTION;
                st.exceptionClass = t.getClass().getSimpleName();
                return st;
            }
            st.totalMs = System.currentTimeMillis() - t0;
            st.exitCode = r.exitCode;
            st.timedOut = r.timedOut;
            st.produced = !r.output.isEmpty();
            st.stdoutBytes = r.stdout == null ? 0 : r.stdout.length();
            st.stderrBytes = r.stderr == null ? 0 : r.stderr.length();
            st.output = r.output;
            // 耗时分解：ShizukuChannel.Result 目前只给端到端；
            // 若将来补了分段字段，这里直接透传，不改调用方。
            st.waitMs = r.elapsedMs();
            if (r.timedOut) {
                st.failure = F_TIMEOUT;
            } else if (!r.error.isEmpty()) {
                st.failure = F_EXCEPTION;
                st.exceptionClass = r.error;
            } else if (r.exitCode != 0) {
                st.failure = F_NONZERO_EXIT;
            } else if (r.output.isEmpty()) {
                st.failure = F_EMPTY_OUTPUT;
            }
            return st;
        }
        if (plan.kind == ExecutorProvider.Kind.ROOT_SU) {
            long t0 = System.currentTimeMillis();
            String out;
            try {
                out = io.github.wlmosv_png.wpaforge.audit.RootProber
                        .exec(null, new String[]{"su", "-c", command}, timeoutMs);
            } catch (Throwable t) {
                st.totalMs = System.currentTimeMillis() - t0;
                st.failure = F_EXCEPTION;
                st.exceptionClass = t.getClass().getSimpleName();
                return st;
            }
            st.totalMs = System.currentTimeMillis() - t0;
            st.waitMs = st.totalMs;
            st.timedOut = io.github.wlmosv_png.wpaforge.audit.RootProber.lastTimedOut();
            if (st.timedOut || out == null) {
                st.failure = F_TIMEOUT;
                return st;
            }
            st.exitCode = 0;
            st.produced = !out.isEmpty();
            st.stdoutBytes = out.length();
            st.output = out;
            if (out.isEmpty()) st.failure = F_EMPTY_OUTPUT;
            return st;
        }
        // SPECIFIER：没有 shell 通道，无法执行命令 —— 如实报不可用
        st.failure = F_NOT_USABLE;
        return st;
    }

    /**
     * **正式任务的 ShellChannel 适配器**。
     *
     * <p>引擎只需要 {@code exec → String}（null = 没拿到）。这里把
     * {@link Step} 转成该语义，并保证「超时 / 通道不可用」都返回 null ——
     * 与 Root 通道的历史语义一致，判定链一行不改。
     *
     * <p>诊断入口调 {@link #run} 拿完整 {@link Step}；正式任务调本方法拿
     * {@code String}。**两者是同一条执行路径**，只有返回值包装不同。
     */
    public static final class ShellAdapter {
        private final ExecutorProvider.Plan plan;
        /** 最近一次执行的完整记录（供日志/诊断读取）。 */
        public volatile Step last;
        /** 超时/失败回调（让熔断器能看见，不依赖全局静态标志）。 */
        private volatile Runnable onTimeout;

        public ShellAdapter(ExecutorProvider.Plan plan) {
            this.plan = plan;
        }

        public void setTimeoutListener(Runnable r) {
            this.onTimeout = r;
        }

        public ExecutorProvider.Plan plan() {
            return plan;
        }

        public String exec(String cmd, long timeoutMs) {
            Step st = run(plan, cmd, timeoutMs);
            last = st;
            if (st.timedOut && onTimeout != null) {
                try { onTimeout.run(); } catch (Throwable ignored) {}
            }
            // 语义与 Root 通道一致：没拿到（超时/异常/不可用）→ null；
            // 拿到但空输出 → 空串（两者在判定链里含义不同，不能合并）。
            if (F_TIMEOUT.equals(st.failure) || F_EXCEPTION.equals(st.failure)
                    || F_NOT_USABLE.equals(st.failure)) {
                return null;
            }
            return st.output;
        }

        public String name() {
            return plan == null ? "未接入" : plan.kindName();
        }
    }
}
