package io.github.wlmosv_png.wpaforge.cap;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * **Shizuku 通道**：借 shell 身份（uid 2000）执行 {@code cmd wifi}。
 *
 * <h3>为什么这条路成立</h3>
 * 实测（OnePlus PJX110 / Android 15）：
 * <pre>
 *   uid=2000(shell) 执行 cmd wifi status      → 正常返回 WifiInfo
 *   uid=2000(shell) 执行 cmd wifi help        → 含 connect-network / forget-network
 *   应用 UID 执行 cmd wifi status             → 空输出（binder 按调用者 UID 拦）
 * </pre>
 * AOSP {@code WifiShellCommand} 的 {@code connect-network} 分支**没有 ROOT_UID 检查**
 * （只有少数子命令有），它按 {@code SHELL_PACKAGE_NAME} 调用
 * {@code WifiService.connect(...)}。也就是说：**拿到 shell 身份就能自动连接，
 * 不需要 root、不需要解锁 BootLoader、不需要 Magisk。**
 *
 * <h3>Shizuku 怎么给这个身份</h3>
 * Shizuku 是用户手动启动的特权服务（Android 11+ 可用「无线调试」在机上直接启动，
 * 不需要电脑）。它把 {@code IShizukuService} binder 暴露给已授权应用，
 * 其中 {@code newProcess(String[] cmd, String[] env, String dir)}（transaction 7）
 * 会以 Shizuku 自己的 UID（ADB 启动时 = 2000 shell）fork 子进程。
 *
 * <h3>为什么不依赖 aidl 编译器</h3>
 * 构建环境里 {@code aidl} 是 Android arm64 二进制，桌面 JVM 跑不了。
 * 所以这里**手写 Parcel 事务**，只用到 3 个方法（newProcess / waitFor / destroy），
 * 用到的 {@code IRemoteProcess} 调用同样手写。不引入任何第三方依赖，
 * 只用已经放在 {@code app/libs/shizuku-api.jar} 里的官方 API 拿 binder。
 *
 * <h3>失败要如实说</h3>
 * Shizuku 没装 / 没启动 / 没授权，三者给不同提示，绝不假装能用。
 */
public final class ShizukuChannel {

    /** Shizuku 包名。 */
    public static final String PKG = "moe.shizuku.privileged.api";

    /**
     * IShizukuService 的事务码。
     *
     * <p>**必须是 8，不是 7**（2026-11-20 实测更正）。官方 13.x 的
     * {@code IShizukuService.Stub} 声明顺序是：
     * getVersion=3 / getUid=4 / checkPermission=5 /
     * <b>newProcess=8</b> / getSELinuxContext=9 / …
     * 早先凭印象写的 7 会命中 {@code setSystemProperty}，
     * 结果要么 RemoteException，要么静默改系统属性，命令永远跑不起来。
     */
    private static final int TX_NEW_PROCESS = 8;
    /** IRemoteProcess 的事务码（按 AIDL 声明顺序，1 起）。 */
    private static final int RP_GET_OUTPUT_STREAM = 1;
    private static final int RP_GET_INPUT_STREAM = 2;
    private static final int RP_GET_ERROR_STREAM = 3;
    private static final int RP_WAIT_FOR = 4;
    private static final int RP_EXIT_VALUE = 5;
    private static final int RP_DESTROY = 6;

    /** 状态码。 */
    public static final int S_NOT_INSTALLED = 0;
    public static final int S_NOT_RUNNING = 1;
    public static final int S_NO_PERMISSION = 2;
    public static final int S_READY = 3;
    /** 已授权但身份不是 shell（例如用 root 启动的 Shizuku）。 */
    public static final int S_READY_NOT_SHELL = 4;

    private final Context ctx;
    private volatile int state = -1;
    private volatile String stateDetail = "";

    public ShizukuChannel(Context ctx) {
        this.ctx = ctx == null ? null : ctx.getApplicationContext();
    }

    // ------------------------------------------------------------ 状态探测

    /**
     * 探测当前可用性。**每一项都是实测**，不看包是否安装就下结论。
     *
     * @return {@link #S_READY} 等状态码
     */
    public int probe() {
        if (ctx == null) {
            state = S_NOT_INSTALLED;
            stateDetail = "没有应用上下文";
            return state;
        }
        // 0) 先确保 binder 真的到了本进程。
        //    ShizukuProvider 在进程启动时塞 binder，但如果进程先于 Shizuku 服务起来
        //    （或服务重启过），binder 会是空的。这里主动要一次。
        ensureBinder(ctx);
        // 1) API 类能不能加载 —— 这是最先要判的（jar 没进 dex 时后面全是白搭）
        Class<?> shz;
        try {
            shz = Class.forName("rikka.shizuku.Shizuku");
        } catch (Throwable t) {
            state = S_NOT_INSTALLED;
            stateDetail = "应用内缺少 Shizuku API（构建问题：jar 未打进 dex）";
            return state;
        }
        // 2) 服务在不在跑 —— **直接问 binder，不靠包名判断**。
        //    Android 11+ 包可见性会让 getPackageInfo 抛 NameNotFound，
        //    早先的实现据此判「没装」，把整条路自己堵死了（2026-11-20 修）。
        boolean alive;
        try {
            alive = Boolean.TRUE.equals(shz.getMethod("pingBinder").invoke(null));
        } catch (Throwable t) {
            alive = false;
        }
        if (!alive) {
            // binder 不在 → 再区分「没装」还是「装了没启动」，只影响文案
            boolean installed = isPackageInstalled(ctx, PKG);
            state = installed ? S_NOT_RUNNING : S_NOT_INSTALLED;
            stateDetail = installed
                    ? "Shizuku 已安装但没有运行（请在 Shizuku 里启动服务）"
                    : "没有安装 Shizuku（需要它提供 shell 身份）";
            return state;
        }
        // 3) 有没有给本应用授权
        try {
            Object perm = shz.getMethod("checkSelfPermission").invoke(null);
            int granted = perm instanceof Integer ? (Integer) perm : -1;
            if (granted != PackageManager.PERMISSION_GRANTED) {
                state = S_NO_PERMISSION;
                stateDetail = "Shizuku 正在运行，但还没有授权给本应用";
                return state;
            }
            Object uidObj = shz.getMethod("getUid").invoke(null);
            int uid = uidObj instanceof Integer ? (Integer) uidObj : -1;
            if (uid == Process.ROOT_UID) {
                // 用 root 启动的 Shizuku 给的是 uid 0 —— 那已经不算「无 Root」了，
                // 但我们仍然能用；只是要如实标出来，免得用户以为在跑无 Root 路径。
                state = S_READY_NOT_SHELL;
                stateDetail = "Shizuku 以 root 身份运行（当前不是纯无 Root 环境）";
                return state;
            }
            if (uid == 2000) {
                state = S_READY;
                stateDetail = "Shizuku 以 shell 身份运行，可执行 cmd wifi";
                return state;
            }
            state = S_READY_NOT_SHELL;
            stateDetail = "Shizuku 身份异常：uid=" + uid;
            return state;
        } catch (Throwable t) {
            state = S_NOT_RUNNING;
            stateDetail = "读取 Shizuku 状态失败：" + t.getClass().getSimpleName();
            return state;
        }
    }

    /**
     * 确保 Shizuku binder 已就绪。
     *
     * <p>ShizukuProvider 会在进程启动时把 binder 塞进来；但若进程比服务先起、
     * 或服务重启过，本进程里 binder 可能是空的。这时主动拉一次。
     */
    private static void ensureBinder(Context ctx) {
        try {
            Class<?> shz = Class.forName("rikka.shizuku.Shizuku");
            Object alive = shz.getMethod("pingBinder").invoke(null);
            if (Boolean.TRUE.equals(alive)) return;
            // 通过 provider 重新索取 binder（官方提供 requestBinder 路径）
            try {
                Class<?> prov = Class.forName("rikka.shizuku.ShizukuProvider");
                prov.getMethod("requestBinderForNonProviderProcess", android.content.Context.class)
                        .invoke(null, ctx);
            } catch (Throwable ignored) {
                // 老版本没有这个方法；provider 已在 manifest 里，进程重启后会自己到位
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 请求 Shizuku 授权（**必须调用**，否则应用不会出现在授权列表里）。
     *
     * <p>这是用户看到「列表里没有 WPAForge」的直接原因：不调这个，
     * Shizuku 根本不知道有应用要授权。
     *
     * @return 当前是否已授权
     */
    public boolean ensurePermission() {
        try {
            Class<?> shz = Class.forName("rikka.shizuku.Shizuku");
            Object granted = shz.getMethod("checkSelfPermission").invoke(null);
            if (granted instanceof Integer
                    && (Integer) granted == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
            shz.getMethod("requestPermission", int.class).invoke(null, REQ_CODE);
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 授权请求码（随便取，只要回调时能对上）。 */
    public static final int REQ_CODE = 0x5750;

    /** 包是否可见。Android 11+ 需要 manifest 里的 &lt;queries&gt;，否则恒 false。 */
    private static boolean isPackageInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public int state() {
        return state < 0 ? probe() : state;
    }

    public String stateDetail() {
        return stateDetail;
    }

    /** 能不能真的执行命令（READY 或 READY_NOT_SHELL）。 */
    public boolean usable() {
        int s = state();
        return s == S_READY || s == S_READY_NOT_SHELL;
    }

    /**
     * **轻量存活检查**：每次执行前调用，不走完整 probe()（那会做包名查询等重活）。
     *
     * <p>只查两件最要紧的事：
     * <ol>
     *   <li>{@code pingBinder()} —— Binder 还活着吗（服务退出/重启会变 false）</li>
     *   <li>{@code checkSelfPermission()} —— 授权还在吗（用户撤销会变 denied）</li>
     * </ol>
     *
     * <p>任一不成立就把 {@code state} 更新成实际状态，让界面与日志立刻反映真相，
     * 而不是继续用旧的 READY。
     */
    private boolean stillUsable() {
        try {
            Class<?> shz = Class.forName("rikka.shizuku.Shizuku");
            Object alive = shz.getMethod("pingBinder").invoke(null);
            if (!Boolean.TRUE.equals(alive)) {
                // Binder 不在了 —— 服务退出或重启中
                state = isPackageInstalled(ctx, PKG) ? S_NOT_RUNNING : S_NOT_INSTALLED;
                stateDetail = state == S_NOT_RUNNING
                        ? "Shizuku 服务已退出（Binder 断开），请重新启动服务"
                        : "Shizuku 已被卸载";
                return false;
            }
            Object perm = shz.getMethod("checkSelfPermission").invoke(null);
            int granted = perm instanceof Integer ? (Integer) perm : -1;
            if (granted != PackageManager.PERMISSION_GRANTED) {
                state = S_NO_PERMISSION;
                stateDetail = "Shizuku 授权已失效（用户撤销或服务重启），请重新授权";
                return false;
            }
            // 都通过 —— 保持原状态（可能是 READY 或 READY_NOT_SHELL）
            return state == S_READY || state == S_READY_NOT_SHELL;
        } catch (Throwable t) {
            state = S_NOT_RUNNING;
            stateDetail = "Shizuku 通道检查失败：" + t.getClass().getSimpleName();
            return false;
        }
    }

    /**
     * **直调 IWifiManager 读当前连接**（2026-11-20，优先路径）。
     *
     * <p>不走 shell，因此没有进程启动、没有管道、没有 500ms 的 countDownLatch 等待 ——
     * 这是 WiFi 工具箱的实际做法（{@code ShizukuUtil.getConnectionInfo}）。
     *
     * <p>返回 {@code Outcome}；value 是反射拿到的 {@code WifiInfo}。
     * 调用方自己取字段，**不要**把「拿到了 WifiInfo」当成「密码正确」。
     */
    public WifiServiceBridge.Outcome<Object> getConnectionInfoDirect() {
        if (!usable()) {
            return WifiServiceBridge.Outcome.fail("not-usable", null);
        }
        return WifiServiceBridge.getConnectionInfo(android.os.Build.VERSION.SDK_INT);
    }

    /**
     * **直调 IWifiManager 列已保存网络**（2026-11-20，优先路径）。
     *
     * <p>对应 {@code cmd wifi list-networks}，但走 binder 而非 shell。
     */
    public WifiServiceBridge.Outcome<java.util.List<Object>> listConfiguredNetworksDirect() {
        if (!usable()) {
            return WifiServiceBridge.Outcome.fail("not-usable", null);
        }
        return WifiServiceBridge.listConfiguredNetworks(android.os.Build.VERSION.SDK_INT);
    }

    /**
     * 当前通道的**真实执行身份**（2026-11-20）。
     *
     * <p>「Shizuku 已授权」只说明拿到 binder，不说明拿到的是 uid 0 / 1000 / 2000。
     * 能力探测必须按真实身份分级，不能一律当 shell 或一律当 root。
     *
     * <p>参考 WiFi 工具箱 {@code ShizukuUtil.kt:572} 的同一做法。
     */
    public ExecutorIdentity identity() {
        if (!usable()) return ExecutorIdentity.unknown("Shizuku");
        try {
            Class<?> shz = Class.forName("rikka.shizuku.Shizuku");
            Object uidObj = shz.getMethod("getUid").invoke(null);
            int uid = uidObj instanceof Integer ? (Integer) uidObj : -1;
            String se = "";
            try {
                Object ctxObj = shz.getMethod("getSELinuxContext").invoke(null);
                if (ctxObj instanceof String) se = (String) ctxObj;
            } catch (Throwable ignored) {}
            return ExecutorIdentity.of(uid, se, "Shizuku");
        } catch (Throwable t) {
            return ExecutorIdentity.unknown("Shizuku");
        }
    }

    /** 需要用户去 Shizuku 里点授权。 */
    public boolean needsPermission() {
        return state() == S_NO_PERMISSION;
    }

    /** 主动请求授权（Shizuku 会弹它自己的确认框）。 */
    public void requestPermission() {
        try {
            Class<?> shz = Class.forName("rikka.shizuku.Shizuku");
            // requestPermission(int requestCode)
            shz.getMethod("requestPermission", int.class).invoke(null, REQ_CODE);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 执行

    /** 一次命令执行的结果。 */
    public static final class Result {
        public final String output;
        public final int exitCode;
        public final boolean timedOut;
        public final String error;

        // ── 结构化诊断（2026-11-20，任务书第三阶段要求）────────────────
        //
        // 每次执行必须能回答：用的哪个执行器、执行身份是什么、
        // 花了多久、stdout/stderr 各是什么、是不是超时/被取消。
        // 以前只有 output/exitCode/error 三个字段，无法区分
        // 「命令没跑」与「跑了但没输出」与「跑了但被拦」。

        /** 执行器名（Shizuku / Root / App）。 */
        public String executorId = "";
        /** 实际执行身份（可空）。 */
        public String executorIdentity = "";
        /** 命令开始时刻（单调时钟）。 */
        public long startedAtMs = 0L;
        /** 命令结束时刻。 */
        public long endedAtMs = 0L;
        /** stdout（与 output 同源，保留语义清晰）。 */
        public String stdout = "";
        /** stderr。 */
        public String stderr = "";
        /** 是否被取消（区别于超时）。 */
        public boolean cancelled = false;
        /** 原始异常类名（无异常为空）。 */
        public String exceptionClass = "";

        // ── 耗时分解（2026-11-20 第二阶段，任务书第四部分）────────────
        //
        // 真机「每条 11.7 秒」这类问题，只有把三段分开才定位得了：
        //   · binderMs —— transact 建进程 / 取流（服务端卡住就在这里）
        //   · waitMs   —— 子进程等待（waitForTimeout 轮询；子进程不退就在这里）
        //   · drainMs  —— 读 stdout/stderr 到 EOF（管道死锁就在这里）
        // 旧实现只有端到端，无法区分「命令没跑」与「跑了但读不到」。
        /** Binder 往返耗时（建进程 + 取流）。 */
        public long binderMs = 0L;
        /** 子进程等待耗时。 */
        public long waitMs = 0L;
        /** 读流耗时。 */
        public long drainMs = 0L;

        Result(String output, int exitCode, boolean timedOut, String error) {
            this.output = output == null ? "" : output;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.error = error == null ? "" : error;
        }

        public boolean ok() {
            return !timedOut && error.isEmpty() && !cancelled;
        }

        /** 耗时（毫秒）；未记录时 0。 */
        public long elapsedMs() {
            return endedAtMs > startedAtMs ? endedAtMs - startedAtMs : 0L;
        }

        /**
         * 一行结构化诊断（**不含命令内容与输出正文**，避免口令泄露）。
         *
         * <p>格式固定，便于日志聚合与 grep：
         * <pre>
         *   [Shizuku/root(uid=0)] 111ms exit=0 timeout=false out=234B err=0B
         * </pre>
         */
        public String brief() {
            return "[" + (executorId.isEmpty() ? "?" : executorId)
                    + (executorIdentity.isEmpty() ? "" : "/" + executorIdentity)
                    + "] " + elapsedMs() + "ms exit=" + exitCode
                    + " timeout=" + timedOut
                    + (cancelled ? " cancelled" : "")
                    + " out=" + stdout.length() + "B"
                    + " err=" + stderr.length() + "B"
                    + " binder=" + binderMs + "ms"
                    + " wait=" + waitMs + "ms"
                    + " drain=" + drainMs + "ms"
                    + (error.isEmpty() ? "" : " err=" + error)
                    + (exceptionClass.isEmpty() ? "" : " ex=" + exceptionClass);
        }
    }

    /**
     * 以 shell 身份执行一条命令。
     *
     * <p>实现走 {@code IShizukuService.newProcess(cmd, env, dir)}（transaction 7），
     * 然后从 {@code IRemoteProcess} 读 stdout / stderr 并 {@code waitFor}。
     *
     * @param command 要执行的命令（单条字符串，用 {@code sh -c} 包装）
     * @param timeoutMs 超时；到点 {@code destroy()} 子进程
     */
    public Result exec(String command, long timeoutMs) {
        // **执行前必须重新确认通道真的还活着**（2026-11-20 修）。
        //
        // 旧实现调 usable() → state() → 返回**缓存**的 state。
        // 于是：Shizuku 服务退出、用户撤销授权、Binder 断开之后，
        // 只要第一次探测成功过，后面每条命令都还会「以为自己在用 Shizuku」，
        // 实际 transact 全部失败 —— 任务书明确禁止这种
        // 「Shizuku 未授权、服务退出、Binder 断开后仍继续使用旧通道」。
        //
        // 现在：每次 exec 前做一次**轻量存活检查**（pingBinder + 授权位），
        // 只有都通过才继续；失败则把状态置为实际状态并如实返回错误。
        if (!stillUsable()) {
            return new Result("", -1, false, stateDetail);
        }
        final long tExec0 = System.currentTimeMillis();
        long binderMsAcc = 0L;
        long waitMsAcc = 0L;
        long drainMsAcc = 0L;
        IBinder remote = null;
        long tBinder0 = System.currentTimeMillis();
        try {
            remote = remoteProcess(new String[]{"sh", "-c", command});
            binderMsAcc += System.currentTimeMillis() - tBinder0;
        } catch (Throwable t) {
            return new Result("", -1, false, "创建进程失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "：" + t.getMessage()));
        }
        if (remote == null) {
            return new Result("", -1, false, "Shizuku 拒绝创建进程（授权可能已失效）");
        }

        long deadline = System.currentTimeMillis() + Math.max(1000L, timeoutMs);
        String out;
        String err;
        int code = -1;
        boolean timeout = false;
        try {
            long tFd0 = System.currentTimeMillis();
            ParcelFileDescriptor outFd = transactFd(remote, RP_GET_INPUT_STREAM);
            ParcelFileDescriptor errFd = transactFd(remote, RP_GET_ERROR_STREAM);
            binderMsAcc += System.currentTimeMillis() - tFd0;
            // **必须先并发读流，再 waitFor**（2026-11-20 修）。
            // 反过来会死锁：子进程输出超过管道缓冲（Linux 默认 64KB）时
            // 它会阻塞在 write，永远不会退出 → waitFor 永远不返回 →
            // 只能等超时，然后读到的还是空。`cmd wifi help` 的输出就够触发。
            // 所以开两个后台线程边读边攒，主线程只负责 waitFor。
            final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            Thread tOut = drain(outFd, outBuf);
            Thread tErr = drain(errFd, errBuf);
            final long tWait0 = System.currentTimeMillis();
            while (true) {
                Parcel p = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    p.writeInterfaceToken("moe.shizuku.server.IRemoteProcess");
                    p.writeLong(200L);
                    p.writeString("MILLISECONDS");
                    remote.transact(RP_WAIT_FOR_TIMEOUT, p, r, 0);
                    r.readException();
                    boolean finished = r.readInt() != 0;
                    if (finished) {
                        code = readExitCode(remote);
                        break;
                    }
                } finally {
                    r.recycle();
                    p.recycle();
                }
                if (System.currentTimeMillis() >= deadline) {
                    timeout = true;
                    break;
                }
            }
            // **硬超时兜底**（2026-11-20 真机事故）。
            //
            // 上面的循环理论上到 deadline 就 break，但如果
            // `waitForTimeout` 的 Binder 调用本身阻塞（Shizuku 服务侧实现
            // 有 bug、或服务正被冻结），控制权根本回不到 deadline 检查。
            // 真机现象：一条 list-networks 卡满 5 秒、一条 forget 卡满 6 秒，
            // 78 条候选白跑 14 分钟。
            //
            // 这里补一道：无论循环怎么退出，只要已过 deadline 就标超时，
            // 让上层熔断器能看见（它靠 Result.ok()==false 计数）。
            waitMsAcc += System.currentTimeMillis() - tWait0;
            if (System.currentTimeMillis() >= deadline) {
                timeout = true;
            }
            // ══════════════════════════════════════════════════════════
            //  **必须先关流再 join**（2026-11-20 真机事故的真正根因）
            //
            //  旧顺序：join(tOut, 3s) → join(tErr, 3s) → close(fd) → destroy
            //  进程没退出时，两个 drain 线程阻塞在 read() 上，
            //  join 只能等满超时 —— 每个 3 秒，两个就是 6 秒。
            //  于是「5 秒 deadline + 3 + 3 = 11 秒」，与用户截图
            //  「通道异常 · 11s」**完全吻合**。78 条 = 14 分钟白跑。
            //
            //  现在：先 close 两个 fd（read() 立刻返回 -1，drain 线程自然结束），
            //  再 join（此时通常毫秒级返回）；超时则 destroy 子进程。
            // ══════════════════════════════════════════════════════════
            final long tDrain0 = System.currentTimeMillis();
            if (timeout) {
                // 超时：先杀掉子进程，避免它继续占着管道
                destroy(remote);
            }
            closeQuietly(outFd);
            closeQuietly(errFd);
            joinQuietly(tOut);
            joinQuietly(tErr);
            drainMsAcc += System.currentTimeMillis() - tDrain0;
            out = outBuf.toString("UTF-8");
            err = errBuf.toString("UTF-8");
        } catch (Throwable t) {
            return new Result("", -1, false, "读取输出失败：" + t.getClass().getSimpleName());
        } finally {
            if (timeout) {
                destroy(remote);
            }
            // 释放远端进程引用
            try { transactVoid(remote, RP_DESTROY); } catch (Throwable ignored) {}
        }
        if (timeout) {
            Result r = new Result(out, code, true, "命令超时（" + timeoutMs + "ms）");
            fillDiag(r, out, err, tExec0);
            r.binderMs = binderMsAcc;
            r.waitMs = waitMsAcc;
            r.drainMs = drainMsAcc;
            return r;
        }
        String combined = err.isEmpty() ? out : (out + (out.isEmpty() ? "" : "\n") + err);
        Result okR = new Result(combined, code, false, "");
        fillDiag(okR, out, err, tExec0);
        okR.binderMs = binderMsAcc;
        okR.waitMs = waitMsAcc;
        okR.drainMs = drainMsAcc;
        return okR;
    }

    /** IRemoteProcess.waitForTimeout 的事务码（AIDL 第 8 个方法）。 */
    private static final int RP_WAIT_FOR_TIMEOUT = 8;

    // ------------------------------------------------------------ 底层事务

    /** 调 {@code IShizukuService.newProcess}，返回 {@code IRemoteProcess} binder。 */
    private IBinder remoteProcess(String[] cmd) throws Exception {
        Class<?> shz = Class.forName("rikka.shizuku.Shizuku");
        IBinder service = (IBinder) shz.getMethod("getBinder").invoke(null);
        if (service == null) return null;

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService");
            data.writeStringArray(cmd);
            data.writeStringArray(null);      // env
            data.writeString(null);           // dir
            if (!service.transact(TX_NEW_PROCESS, data, reply, 0)) return null;
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * 取 {@code IRemoteProcess} 的流（stdout / stderr）。
     *
     * <p>**必须按 AIDL 生成的写法读**（2026-11-20 实测更正）：
     * <pre>
     *   reply.readException();
     *   if (reply.readInt() != 0) {
     *       fd = ParcelFileDescriptor.CREATOR.createFromParcel(reply);
     *   }
     * </pre>
     * 早先用的是 {@code reply.readFileDescriptor()} —— 那是**另一套编码**
     * （把首个 int 当 fd 数组下标，配 writeFileDescriptor 用）。
     * AIDL 这里写的是 ParcelFileDescriptor 对象（首 int 是「存在」标志），
     * 两者不兼容 → 恒拿到 null → 命令 exit=0 但输出永远是空字符串。
     */
    private ParcelFileDescriptor transactFd(IBinder rp, int code) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("moe.shizuku.server.IRemoteProcess");
            rp.transact(code, data, reply, 0);
            reply.readException();
            if (reply.readInt() != 0) {
                return ParcelFileDescriptor.CREATOR.createFromParcel(reply);
            }
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private int readExitCode(IBinder rp) {
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("moe.shizuku.server.IRemoteProcess");
                rp.transact(RP_EXIT_VALUE, data, reply, 0);
                reply.readException();
                return reply.readInt();
            } finally {
                reply.recycle();
                data.recycle();
            }
        } catch (Throwable t) {
            return -1;
        }
    }

    private void destroy(IBinder rp) {
        try { transactVoid(rp, RP_DESTROY); } catch (Throwable ignored) {}
    }

    private void transactVoid(IBinder rp, int code) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("moe.shizuku.server.IRemoteProcess");
            rp.transact(code, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** 起一个线程把流读进 buf（到 EOF 或上限为止）。 */
    private static Thread drain(final ParcelFileDescriptor fd, final ByteArrayOutputStream buf) {
        Thread t = new Thread(() -> {
            if (fd == null) return;
            try (InputStream in = new FileInputStream(fd.getFileDescriptor())) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) {
                    buf.write(b, 0, n);
                    if (buf.size() > 512 * 1024) break;   // 有界
                }
            } catch (Throwable ignored) {
            }
        }, "shz-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * 等 drain 线程收尾。
     *
     * <p>**300ms 而不是 3000ms**（2026-11-20）：调用方现在会**先 close 流**，
     * read() 立刻返回 -1，drain 线程随即结束 —— 正常情况毫秒级返回。
     * 给 300ms 是兜底（防止极端情况下 read 不响应 close），
     * 而不是当正常等待用。旧值 3000ms × 2 个线程 = 6 秒，正是「每条 11 秒」的来源。
     */
    /**
     * 填充结构化诊断字段（2026-11-20）。
     *
     * <p>不动判定逻辑，只补「这次执行到底发生了什么」的可观测性。
     */
    private void fillDiag(Result r, String stdout, String stderr, long startedAt) {
        try {
            r.executorId = "Shizuku";
            ExecutorIdentity id = identity();
            r.executorIdentity = id == null ? "" : id.describe(false);
            r.startedAtMs = startedAt;
            r.endedAtMs = System.currentTimeMillis();
            r.stdout = stdout == null ? "" : stdout;
            r.stderr = stderr == null ? "" : stderr;
        } catch (Throwable t) {
            r.exceptionClass = t.getClass().getSimpleName();
        }
    }

    private static void joinQuietly(Thread t) {
        if (t == null) return;
        try { t.join(300L); } catch (Throwable ignored) {}
    }

    private static String readAll(ParcelFileDescriptor fd) {
        if (fd == null) return "";
        try (InputStream in = new FileInputStream(fd.getFileDescriptor())) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 512 * 1024) break;   // 有界
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        if (fd != null) {
            try { fd.close(); } catch (Throwable ignored) {}
        }
    }
}
