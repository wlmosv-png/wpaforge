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
    /**
     * 输出未完整读取（drain 被强制中断，可能截断）。
     *
     * <p>与 {@link #F_EMPTY_OUTPUT} 的差别是本质的：
     * 那个是「命令真的什么都没输出」，这个是「我们没读完」。
     * 真机 `exit=255 out=0B err=809B` 就属于需要区分的情形。
     */
    public static final String F_OUTPUT_TRUNCATED = "OUTPUT_TRUNCATED";
    /** 远程进程启动失败（Shizuku 拒绝 newProcess）。 */
    public static final String F_NO_PROCESS = "NO_PROCESS";

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
        /**
         * stderr **正文**（截断到 2000 字符）。
         *
         * <p>真机报障时最需要的就是它：`err=809B` 只告诉你有东西，
         * 不告诉你是什么。809 字节的 SecurityException 与 809 字节的
         * "not found" 是完全不同的问题。
         */
        public String stderrText = "";
        /** stdout 正文（截断到 2000 字符，诊断用）。 */
        public String stdoutText = "";
        /**
         * 两路输出是否**完整读到 EOF**。
         *
         * <p>{@code false} = drain 被强制中断，输出可能被截断。
         * 这种情况下的「空输出」不能当成「命令没输出」。
         */
        public boolean drainComplete = true;
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
                    + (exceptionClass.isEmpty() ? "" : " ex=" + exceptionClass)
                    + (drainComplete ? "" : " TRUNCATED")
                    + (stderrText.isEmpty() ? "" : " stderr=" + oneLine(stderrText, 300));
        }

        @Override
        public String toString() {
            return brief();
        }
    }

    /** 把多行文本压成一行并截断（诊断输出用，不含口令）。 */
    public static String oneLine(String s, int max) {
        if (s == null || s.isEmpty()) return "";
        String t = s.replace("\r", "").replace("\n", " \u23ce ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "\u2026";
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
            // **三段耗时真实透传**（2026-11-20 第三阶段修）。
            //
            // 旧实现只写 `st.waitMs = r.elapsedMs()`，binderMs 与 drainMs
            // 恒为 0 —— 真机日志里「binder=0ms」就是这么来的：**不是 Binder
            // 真的花了 0ms，而是这个字段从来没被赋过值**。
            // 现在按 ShizukuChannel.Result 的三段原样透传。
            st.binderMs = r.binderMs;
            st.waitMs = r.waitMs;
            st.drainMs = r.drainMs;
            st.drainComplete = r.drainOutComplete && r.drainErrComplete;
            // stderr 正文：真机要看 809B 里到底是什么，不能只给字节数。
            st.stderrText = r.stderr == null ? "" : r.stderr;
            st.stdoutText = r.stdout == null ? "" : r.stdout;
            // **先按 ShizukuChannel 给出的失败种类分流**（2026-11-20 第三阶段）。
            //
            // 关键区分（任务书第三节第 5 条）：
            //   进程没起来  ≠ 命令跑了但非零退出  ≠ 读流失败  ≠ 超时  ≠ 通道不可用
            // 旧实现把它们全压成「有 error 就算 EXCEPTION」，
            // 于是 SecurityException、RemoteException、binder 死亡长得一模一样。
            String kind = r.failureKind == null ? "" : r.failureKind;
            if (io.github.wlmosv_png.wpaforge.cap.ShizukuChannel.Result.KIND_NO_PROCESS.equals(kind)) {
                st.failure = F_NO_PROCESS;
                st.exceptionClass = r.error;
                st.stderrText = r.error;
                return st;
            }
            if (io.github.wlmosv_png.wpaforge.cap.ShizukuChannel.Result.KIND_NOT_USABLE.equals(kind)) {
                st.failure = F_NOT_USABLE;
                st.exceptionClass = r.error;
                return st;
            }
            if (io.github.wlmosv_png.wpaforge.cap.ShizukuChannel.Result.KIND_READ_FAILED.equals(kind)) {
                st.failure = F_OUTPUT_TRUNCATED;
                st.exceptionClass = r.error;
                return st;
            }
            if (r.timedOut) {
                st.failure = F_TIMEOUT;
            } else if (!r.error.isEmpty()) {
                st.failure = F_EXCEPTION;
                st.exceptionClass = r.error;
            } else if (!st.drainComplete) {
                // **输出没读完整** —— 这不是「命令没输出」，
                // 是读流被中断（管道/进程管理问题）。必须单独归类，
                // 否则会被误诊成 EMPTY_OUTPUT 而掩盖真因。
                st.failure = F_OUTPUT_TRUNCATED;
            } else if (r.exitCode != 0) {
                st.failure = F_NONZERO_EXIT;
            } else if (r.output.isEmpty()) {
                // 只有「两路都读到 EOF 且确实一个字节都没有」才算空输出
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
            // 「没拿到」统一返回 null（判定链里 null 与空串语义不同，不能合并）：
            //   超时 / 异常 / 通道不可用 / 进程启动失败 / 输出被截断
            // 输出被截断也算「没拿到」—— 半截输出拿去判定只会得到假结论。
            if (F_TIMEOUT.equals(st.failure) || F_EXCEPTION.equals(st.failure)
                    || F_NOT_USABLE.equals(st.failure) || F_NO_PROCESS.equals(st.failure)
                    || F_OUTPUT_TRUNCATED.equals(st.failure)) {
                return null;
            }
            return st.output;
        }

        public String name() {
            return plan == null ? "未接入" : plan.kindName();
        }
    }
}
