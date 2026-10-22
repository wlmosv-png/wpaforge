package io.github.wlmosv_png.wpaforge.audit;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 带硬超时的进程执行，超时后终止**整棵进程树**并关闭管道。
 *
 * <h3>为什么单独抽一个类</h3>
 * 这里曾经有一个会让整条引擎线程**永久卡死**的 bug：超时后只
 * {@code destroyForcibly()} 了直接子进程，而 {@code su -c "cmd wifi ..."} 会派生
 * 孙进程（su → sh → cmd），孙进程仍然持有管道写端，上层 {@code readLine()}
 * 便永远等不到 EOF。
 *
 * <p>实测后果（2026-10-22）：切到后台后任务完全不动 —— 进度 offset 45 秒不变、
 * 进程 CPU 10 秒消耗 0 ticks，线程栈停在
 * {@code libcore.io.Linux.readBytes} 与 {@code Process.waitFor}；
 * 回到前台才继续。
 *
 * <p>抽成纯逻辑类是为了能被测试钉住（见 {@code ShellExecTest} 的「孙进程不退出」用例）：
 * 引擎类依赖 Android，在桌面 JVM 上加载不起来，也就测不到。
 *
 * <p>纯逻辑，无 Android 依赖。
 */
public final class ShellExec {

    /** 执行结果 */
    public static final class Result {
        public final String output;
        /** true = 到点被强制终止（输出可能不完整） */
        public final boolean timedOut;

        Result(String output, boolean timedOut) {
            this.output = output == null ? "" : output;
            this.timedOut = timedOut;
        }

        public boolean ok() {
            return !timedOut;
        }
    }

    private ShellExec() {}

    /** 执行命令；到点强制终止整棵树，返回已读到的输出。 */
    public static Result run(String[] cmd, long timeoutMs) {
        Process p = null;
        final AtomicBoolean killed = new AtomicBoolean(false);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            final Process fp = p;
            Thread killer = new Thread(() -> {
                try {
                    if (!fp.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                        killed.set(true);
                        killTree(fp);
                    }
                } catch (Throwable ignored) {
                }
            });
            killer.setDaemon(true);
            killer.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(500, TimeUnit.MILLISECONDS);
            return new Result(sb.toString(), killed.get());
        } catch (Throwable t) {
            return new Result("", killed.get());
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 终止整棵进程树，并**关闭三个流**。
     *
     * <p>关流是这一步的关键：即便子进程都被杀掉，阻塞在 {@code readLine()} 上的
     * 线程也可能不醒（管道被孙进程继承时尤其如此）。关掉读端能让它立刻抛
     * IOException 返回，而不是永久等待。
     */
    static void killTree(Process p) {
        if (p == null) return;
        try {
            // Android 低版本没有 ProcessHandle 时这里会抛，靠下面的关流兜底
            p.descendants().forEach(h -> {
                try { h.destroyForcibly(); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
        try { p.destroyForcibly(); } catch (Throwable ignored) {}
        closeQuietly(p.getInputStream());
        closeQuietly(p.getOutputStream());
        closeQuietly(p.getErrorStream());
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) {}
    }
}
