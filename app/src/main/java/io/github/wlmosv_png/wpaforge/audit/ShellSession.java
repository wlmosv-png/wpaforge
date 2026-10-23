package io.github.wlmosv_png.wpaforge.audit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 常驻 root shell 会话 —— 把「每条候选 28 次 su」压成「一个会话里 28 条命令」。
 *
 * <h3>为什么要它（2026-10-23 真机实测）</h3>
 * 引擎每条候选要起 ~28 次 shell（前置 6 + netId 探测 2 + 每轮 status/logcat ×8 轮）。
 * App 里每次 {@code ShellExec.run(["su","-c",cmd])} 实测 **137ms**，
 * 而同样的命令从命令行起只要 27ms —— 差的不是命令，是
 * **在 App（大 JVM）里 fork + su 包装**的固定成本。于是 3.8 秒全花在起进程上。
 *
 * <p>实测同一个 {@code su -c sh} 会话里连打 30 条：<b>88ms/次 → 20ms/次</b>，
 * 且 30/30 帧全部正确。
 *
 * <h3>分帧协议</h3>
 * 每条命令写成两行：
 * <pre>
 *   &lt;命令&gt;
 *   echo __WPF_END__&lt;seq&gt;__$?
 * </pre>
 * 读到 {@code __WPF_END__<seq>__<code>} 才算这一帧完整。**读不到就必须当作失败** ——
 * 宁可让调用方走「通道异常」分支（只会漏判），也绝不能把上一次的输出当成这一次的
 * （那会误判）。
 *
 * <h3>失败与降级</h3>
 * 超时或失联 → 杀掉整棵会话进程树并返回 null；调用方会退回一次性
 * {@link ShellExec}，功能不丢（只是慢回去）。连续失败若干次后本次运行不再尝试会话，
 * 免得每次都白付一次启动成本。
 *
 * <p>纯逻辑部分（{@link #wrap} / {@link #split}）是静态的，可离线单测。
 */
public final class ShellSession {

    /** 分帧哨兵前缀。命令输出里不可能自然出现。 */
    static final String MARK = "__WPF_END__";

    /** 读取线程结束时塞进队列的哑元，用来把「会话死了」和「还没读到」区分开。 */
    private static final String EOF = "__WPF_EOF__";

    /** 一帧的结果 */
    public static final class Frame {
        public final String output;
        public final int code;

        Frame(String output, int code) {
            this.output = output == null ? "" : output;
            this.code = code;
        }
    }

    /** 纯函数：把一条命令包装成「命令 + 哨兵」两行。 */
    public static String wrap(String cmd, long seq) {
        return cmd + "\n" + "echo " + MARK + seq + "__$?\n";
    }

    /**
     * 纯函数：从读到的行里切出「本帧输出」与退出码。
     *
     * @return null = 没找到本帧哨兵（不完整，调用方必须当失败）
     */
    public static Frame split(List<String> lines, long seq) {
        if (lines == null) return null;
        String tag = MARK + seq + "__";
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l != null && l.startsWith(tag)) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < i; j++) sb.append(lines.get(j)).append('\n');
                int code = -1;
                try {
                    code = Integer.parseInt(l.substring(tag.length()).trim());
                } catch (Throwable ignored) {}
                return new Frame(sb.toString(), code);
            }
        }
        return null;
    }

    // ── 会话本体 ────────────────────────────────────────────────────────

    private final String[] argv;
    private final BlockingQueue<String> q = new LinkedBlockingQueue<>();
    private Process proc;
    private Writer w;
    private long seq = 0;
    /** 单帧最多收集多少字符（防止某条命令刷爆内存；超了只丢内容，仍继续找哨兵） */
    private static final int MAX_FRAME_CHARS = 512 * 1024;

    public ShellSession() {
        this(new String[]{"su", "-c", "sh"});
    }

    ShellSession(String[] argv) {
        this.argv = argv;
    }

    public synchronized boolean alive() {
        return proc != null && proc.isAlive() && w != null;
    }

    /** 起会话并做一次握手；成功返回 true。 */
    public synchronized boolean start() {
        close();
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            proc = pb.start();
            w = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8);
            final BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            Thread t = new Thread(() -> {
                try {
                    String line;
                    while ((line = r.readLine()) != null) q.offer(line);
                } catch (Throwable ignored) {
                }
                q.offer(EOF);
            }, "wpaforge-shell-reader");
            t.setDaemon(true);
            t.start();
            // 握手：能跑通一条命令才算可用
            String probe = exec("echo " + MARK + "ready", 4000);
            if (probe == null || !probe.contains(MARK + "ready")) {
                close();
                return false;
            }
            return true;
        } catch (Throwable t) {
            close();
            return false;
        }
    }

    /**
     * 在会话里跑一条命令。
     *
     * @return 命令输出；**null = 这一帧没拿到**（超时/会话失联），调用方必须降级或走异常分支
     */
    public synchronized String exec(String cmd, long timeoutMs) {
        if (!alive() && !start()) return null;

        long mySeq = ++seq;
        q.clear();                       // 丢掉上一帧可能残留的行
        try {
            w.write(wrap(cmd, mySeq));
            w.flush();
        } catch (Throwable t) {
            close();
            return null;
        }

        List<String> lines = new ArrayList<>();
        StringBuilder acc = new StringBuilder();
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                // 超时：把会话整个杀掉（里面可能还挂着半条命令），下次调用会重新起
                close();
                return null;
            }
            String line;
            try {
                line = q.poll(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                close();
                return null;
            }
            if (line == null) {
                close();
                return null;
            }
            if (EOF.equals(line)) {
                // 会话进程没了 —— 这一帧作废
                close();
                return null;
            }
            if (acc.length() < MAX_FRAME_CHARS) {
                acc.append(line).append('\n');
            }
            lines.add(line);
            if (line.startsWith(MARK + mySeq + "__")) break;
            // 越界保护：万一哨兵行被丢弃/异常，别无限攒
            if (lines.size() > 200000) {
                close();
                return null;
            }
        }

        Frame f = split(lines, mySeq);
        if (f == null) {
            close();
            return null;
        }
        return f.output;
    }

    /** 关掉会话与整棵进程树（并关流，让读线程立刻退出）。 */
    public synchronized void close() {
        Process p = proc;
        proc = null;
        w = null;
        if (p != null) {
            try { p.descendants().forEach(h -> {
                try { h.destroyForcibly(); } catch (Throwable ignored) {}
            }); } catch (Throwable ignored) {}
            try { p.destroyForcibly(); } catch (Throwable ignored) {}
            try { p.getInputStream().close(); } catch (Throwable ignored) {}
            try { p.getOutputStream().close(); } catch (Throwable ignored) {}
        }
        q.clear();
    }
}
