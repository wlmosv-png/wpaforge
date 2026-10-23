package io.github.wlmosv_png.wpaforge.audit;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 日志标记**可见性自检** —— 只回答一个问题：在这台设备、这条读取路径下，
 * {@link AttemptMarker} 写进去的起点标记能不能被生产读取方式看到。
 *
 * <h3>为什么需要它（发布前的未知量）</h3>
 * 整套日志类判据都建立在「本候选的起点标记可见」之上（{@code isolated}）。
 * 这一步在真机上从未验证过：{@code log -t} 是否存在、{@code logcat -d} 是否接受
 * 重复 {@code -s}、标记是否落在被读取的缓冲里，全部未知。自检把这三件事一次问清。
 *
 * <h3>安全边界（硬要求）</h3>
 * <ul>
 *   <li><b>不发起任何 Wi-Fi 操作</b>：不连接、不断开、不改开关、不用候选密码。
 *       只执行 {@code id -u} / {@code command -v log} / {@code log -t} /
 *       {@code logcat -d} / {@code logcat -c}；{@link #FORBIDDEN_VERBS} 由测试断言
 *       不在本类与自检脚本里出现。</li>
 *   <li><b>只写一个合成标记</b>：id 由 {@link AttemptId} 生成，SSID 固定为
 *       {@link #SYNTHETIC_SSID}（只参与哈希），与任何真实目标无关。</li>
 *   <li><b>不改判定契约</b>：不修改 {@link AttemptMarker} 的语义、不参与判定；
 *       不得为了让自检通过而放宽 {@code isolated}。</li>
 *   <li><b>不留污染</b>：结束时清空日志缓冲（与每条候选前的清空同一动作）并报告结果。
 *       即使清空失败，合成 id 与真实 runId 不同，也不会被当成某条真实候选的证据。</li>
 * </ul>
 *
 * <h3>输出不是布尔值</h3>
 * {@link Result#lines()} 给出：写入是否成功、读取是否成功、匹配到的标记行与 id、
 * 使用的过滤条件、以及**失败原因分类**（写入失败 / 过滤不匹配 / 读取窗口 / 权限限制）。
 * {@link #classify} 是纯函数：设备脚本只采集 KEY=VALUE 观测，判定仍由本类给出。
 *
 * <h3>结论边界（必须原样转述）</h3>
 * {@link Code#VISIBLE} <b>只</b>证明「标记机制在该设备与读取路径下可用」，
 * <b>不能</b>证明判定链已通过真实网络场景验证。
 */
public final class MarkerSelfCheck {

    /** 自检用的合成 SSID（只参与哈希，绝不出现在标记里）。 */
    public static final String SYNTHETIC_SSID = "selfcheck";

    /** 读取重试次数（覆盖「写入后立刻读、缓冲尚未可见」的窗口问题）。 */
    public static final int READ_ATTEMPTS = 3;

    /** 每次重试之间的等待（毫秒）。 */
    public static final long RETRY_WAIT_MS = 300L;

    /** 自检允许出现的命令关键词 —— 全部与 Wi-Fi 无关。 */
    public static final String[] ALLOWED_COMMANDS = {
            "id -u", "command -v log", "which log", "log -t ", "logcat -d", "logcat -c",
    };

    /** 自检**绝不能**出现的命令关键词（任何会改变网络状态的动词）。 */
    public static final String[] FORBIDDEN_VERBS = {
            "connect-network", "forget-network", "set-wifi-enabled", "svc wifi",
            "disconnect", "reconnect", "add-network", "enable-network", "list-networks",
    };

    private MarkerSelfCheck() {}

    // ---------------------------------------------------------------- 结论分类

    public enum Code {
        /** 标记写入并**被生产读取方式看到** → 机制可用。 */
        VISIBLE,
        /** 自检没运行（没有 shell 通道）。 */
        NO_CHANNEL,
        /** 设备上没有 `log` 命令（写标记的机制不存在）→ isolated 会恒为 no-marker。 */
        LOG_BINARY_MISSING,
        /** 合成 id 生成失败（不可能是网络问题）。 */
        MARKER_ID_INVALID,
        /** 写命令没有拿到帧（通道超时/失联）。 */
        WRITE_NO_FRAME,
        /** 写命令有输出且含错误（权限/参数等）。 */
        WRITE_ERROR,
        /** 读取命令没拿到帧，且控制命令也无输出（通道整体不可用）。 */
        READ_NO_FRAME,
        /** 控制命令正常、但读日志拿不到帧 → 权限/SELinux 限制。 */
        READER_BLOCKED,
        /** 宽读能看到标记、生产读取看不到 → 过滤不匹配（tag / 重复 `-s` / grep）。 */
        FILTER_MISMATCH,
        /** 宽读与生产读取都看不到标记 → 标记不可见（缓冲/级别/tag 被丢弃）。 */
        NOT_VISIBLE,
    }

    /** 一次自检的全部观测（可离线构造，便于单测；也可由设备脚本 KEY=VALUE 解析）。 */
    public static final class Obs {
        /** 自检是否真的开始执行（false = 没有通道）。 */
        public boolean ran;
        public String markerId = "";
        public String markerLine = "";
        public String writeCommand = "";
        public String readCommand = "";
        public String broadCommand = "";
        public String filter = "";
        public String controlOut = "";
        public boolean logBinaryFound;
        public boolean writeFrameOk;
        public String writeOutput = "";
        public int attempts;
        public boolean readFrameOk;
        public int readChars;
        public boolean readSawMarker;
        /** 命中的那一行原文（证据）。 */
        public String readMatchedLine = "";
        public boolean broadFrameOk;
        public int broadChars;
        public boolean broadSawMarker;
        public boolean bufferCleared;
        /** 设备上落盘的**原始读取文本**路径（用于用生产匹配器验证真机原文）。 */
        public String readRawFile = "";
        public String broadRawFile = "";
        /** 生产匹配器对原始文本的判定结果（由 main 回填，便于贴报告）。 */
        public boolean presentOnRaw;
        public String evaluateReasonOnRaw = "";
    }

    /** 把观测归类。**纯函数**：设备证据与单测走同一条路径。 */
    public static Code classify(Obs o) {
        if (o == null || !o.ran) return Code.NO_CHANNEL;
        if (!AttemptId.wellFormed(o.markerId)) return Code.MARKER_ID_INVALID;
        if (!o.logBinaryFound) return Code.LOG_BINARY_MISSING;
        if (!o.writeFrameOk) return Code.WRITE_NO_FRAME;
        if (hasWriteError(o.writeOutput)) return Code.WRITE_ERROR;
        if (o.readSawMarker) return Code.VISIBLE;
        if (o.broadSawMarker) return Code.FILTER_MISMATCH;
        if (!o.readFrameOk) {
            return (o.controlOut != null && !o.controlOut.trim().isEmpty())
                    ? Code.READER_BLOCKED : Code.READ_NO_FRAME;
        }
        return Code.NOT_VISIBLE;
    }

    /** 写命令输出里是否出现错误迹象（只认已知关键词，不猜）。 */
    public static boolean hasWriteError(String out) {
        if (out == null || out.trim().isEmpty()) return false;
        String t = out.toLowerCase();
        return t.contains("permission denied") || t.contains("not found")
                || t.contains("invalid") || t.contains("unknown option")
                || t.contains("error") || t.contains("denied")
                || t.contains("usage:");
    }

    /** 结论是否表示「标记机制可用」。 */
    public static boolean isVisible(Code c) { return c == Code.VISIBLE; }

    /** 人类可读结论（含结论边界说明）。 */
    public static String summary(Code c) {
        switch (c) {
            case VISIBLE:
                return "标记可见性通过：该设备与生产读取路径下，起点标记可写且可读。"
                        + "注意——这只证明标记机制可用，不能证明判定链已通过真实网络场景验证。";
            case NO_CHANNEL:
                return "自检未运行：没有可用的 shell 通道。";
            case MARKER_ID_INVALID:
                return "自检未运行：合成 id 生成失败（与网络无关，属实现问题）。";
            case LOG_BINARY_MISSING:
                return "写入失败：设备上没有 `log` 命令，起点标记机制不存在"
                        + "（isolated 将恒为 no-marker，日志类判据全部失效）。";
            case WRITE_NO_FRAME:
                return "写入未确认：写标记命令没有拿到帧（通道超时或会话失联），"
                        + "不能判定标记是否写入。";
            case WRITE_ERROR:
                return "写入被拒：写标记命令返回了错误输出（见证据），当前权限下机制不可用。";
            case READ_NO_FRAME:
                return "读取失败：logcat 与控制命令都拿不到输出，通道整体不可用。";
            case READER_BLOCKED:
                return "读取被限制：控制命令正常但 logcat 读不到帧 —— 权限/SELinux 限制，"
                        + "不是标记本身的问题。";
            case FILTER_MISMATCH:
                return "过滤不匹配：宽读能看到标记，生产读取方式看不到 —— "
                        + "tag / 重复 -s / grep 过滤式的问题（标记其实写成功了）。";
            case NOT_VISIBLE:
                return "标记不可见：宽读与生产读取都看不到 —— 标记没落进被读取的缓冲区"
                        + "（或级别/tag 被丢弃）。这不等于认证失败，只是日志通道不可用。";
            default:
                return "未分类。";
        }
    }

    // ---------------------------------------------------------------- 结果

    public static final class Result {
        public final Code code;
        public final Obs obs;
        public final List<String> steps = new ArrayList<>();

        public Result(Code c, Obs o) { this.code = c; this.obs = o; }

        public boolean ok() { return isVisible(code); }

        /** 逐行输出：结论 + 全部观测（可直接贴进报告）。 */
        public List<String> lines() {
            List<String> l = new ArrayList<>();
            l.add("结论: " + code);
            l.add("说明: " + summary(code));
            l.add("写入: frameOk=" + obs.writeFrameOk + " logBinary=" + obs.logBinaryFound);
            l.add("写入命令: " + obs.writeCommand);
            l.add("写入输出: " + (obs.writeOutput == null || obs.writeOutput.isEmpty()
                    ? "<空>" : obs.writeOutput.trim()));
            l.add("标记 id: " + obs.markerId);
            l.add("标记行: " + obs.markerLine);
            l.add("读取重试次数: " + obs.attempts);
            l.add("读取命令: " + obs.readCommand);
            l.add("过滤条件: " + obs.filter);
            l.add("读取: frameOk=" + obs.readFrameOk + " chars=" + obs.readChars
                    + " 命中标记=" + obs.readSawMarker);
            l.add("读取命中行: " + (obs.readMatchedLine.isEmpty() ? "<无>"
                    : obs.readMatchedLine.trim()));
            l.add("宽读命令: " + obs.broadCommand);
            if (obs.readSawMarker) {
                // 生产读取已命中 → 按设计不执行宽读（这不是"宽读失败"）
                l.add("宽读: 未执行（生产读取已命中，无需再区分过滤问题）");
            } else {
                l.add("宽读: frameOk=" + obs.broadFrameOk + " chars=" + obs.broadChars
                        + " 命中标记=" + obs.broadSawMarker);
            }
            l.add("控制命令(id -u)输出: " + (obs.controlOut == null ? "" : obs.controlOut.trim()));
            l.add("缓冲清空: " + obs.bufferCleared);
            l.add("原始读取文本: " + (obs.readRawFile.isEmpty() ? "<未落盘>"
                    : obs.readRawFile + "（present=" + obs.presentOnRaw
                            + " evaluate=" + obs.evaluateReasonOnRaw + "）"));
            for (String s : steps) l.add("  · " + s);
            return l;
        }
    }

    // ---------------------------------------------------------------- 设备侧执行

    /**
     * 在给定通道上执行自检：查 {@code log} 是否存在 → 写合成标记 →
     * 用**生产读取命令**读回（带重试）→ 若读不到再宽读一次（区分过滤问题与不可见）
     * → 清空缓冲。
     */
    public static Result run(CrackEngine.ShellChannel shell) {
        Obs o = new Obs();
        List<String> notes = new ArrayList<>();
        if (shell == null) {
            notes.add("没有 shell 通道，自检未运行");
            Result r0 = new Result(Code.NO_CHANNEL, o);
            r0.steps.addAll(notes);
            return r0;
        }
        o.ran = true;

        String runId = AttemptId.newRunId(System.currentTimeMillis(), SYNTHETIC_SSID, 0);
        o.markerId = AttemptId.of(runId, 1);
        o.markerLine = AttemptMarker.line(o.markerId);
        o.writeCommand = AttemptMarker.writeCommand(o.markerId);
        o.readCommand = WpaLogReader.readCommand();
        o.filter = WpaLogReader.SHELL_FILTER;
        o.broadCommand = "logcat -d | grep -F 'attempt=" + o.markerId + "'";

        // 1) 控制命令（证明通道能跑命令；与 Wi-Fi 无关）
        try { o.controlOut = shell.exec("id -u", 4000); } catch (Throwable ignored) {}
        // 2) log 命令是否存在
        try {
            String which = shell.exec("command -v log || which log", 4000);
            o.logBinaryFound = which != null && !which.trim().isEmpty();
            if (!o.logBinaryFound) notes.add("`log` 命令不存在: " + which);
        } catch (Throwable t) {
            notes.add("探测 log 命令异常: " + t.getClass().getSimpleName());
        }
        // 3) 写入标记（只在 log 存在时才有意义，但仍执行以便记录证据）
        try {
            o.writeOutput = shell.exec(o.writeCommand, 4000);
            o.writeFrameOk = o.writeOutput != null;
        } catch (Throwable t) {
            notes.add("写标记抛异常: " + t.getClass().getSimpleName());
        }
        // 4) 生产读取命令读回（带重试）
        for (int i = 0; i < READ_ATTEMPTS; i++) {
            o.attempts = i + 1;
            String read = null;
            try { read = shell.exec(o.readCommand, 6000); } catch (Throwable ignored) {}
            o.readFrameOk = read != null;
            o.readChars = read == null ? 0 : read.length();
            o.readSawMarker = read != null && AttemptMarker.present(read, o.markerId);
            if (o.readSawMarker) {
                o.readMatchedLine = lineWith(read, "attempt=" + o.markerId);
                break;
            }
            sleep(RETRY_WAIT_MS);
        }
        // 5) 宽读（不加 tag / 不加 grep -E）——区分「过滤不匹配」与「标记不可见」
        if (!o.readSawMarker) {
            String broad = null;
            try { broad = shell.exec(o.broadCommand, 6000); } catch (Throwable ignored) {}
            o.broadFrameOk = broad != null;
            o.broadChars = broad == null ? 0 : broad.length();
            o.broadSawMarker = broad != null && broad.contains("attempt=" + o.markerId);
            if (o.broadSawMarker) notes.add("宽读命中标记 → 问题在过滤/tag，不在写入");
        }
        // 6) 清空缓冲（与每条候选前同一动作）
        try {
            shell.exec("logcat -c", 4000);
            o.bufferCleared = true;
        } catch (Throwable ignored) {}

        Result out = new Result(classify(o), o);
        out.steps.addAll(notes);
        return out;
    }

    /** 取出包含给定子串的那一行（证据原文）。 */
    static String lineWith(String text, String needle) {
        if (text == null || needle == null) return "";
        for (String l : text.split("\n")) {
            if (l.contains(needle)) return l;
        }
        return "";
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ---------------------------------------------------------------- 观测解析

    /**
     * 解析设备脚本输出的 {@code KEY=VALUE} 观测（见 {@code tools/marker-selfcheck.sh}）。
     * 采集与判定分离：脚本里没有第二份判定逻辑。
     */
    public static Obs parseObs(String text) {
        Obs o = new Obs();
        if (text == null) return o;
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1);
            switch (k) {
                case "ran": o.ran = "1".equals(v.trim()); break;
                case "log_binary_found": o.logBinaryFound = "1".equals(v.trim()); break;
                case "write_frame_ok": o.writeFrameOk = "1".equals(v.trim()); break;
                case "write_output": o.writeOutput = unescape(v); break;
                case "marker_id": o.markerId = v.trim(); break;
                case "marker_line": o.markerLine = v.trim(); break;
                case "write_command": o.writeCommand = v.trim(); break;
                case "read_command": o.readCommand = v.trim(); break;
                case "filter": o.filter = v.trim(); break;
                case "broad_command": o.broadCommand = v.trim(); break;
                case "read_attempts": o.attempts = parseInt(v); break;
                case "read_frame_ok": o.readFrameOk = "1".equals(v.trim()); break;
                case "read_chars": o.readChars = parseInt(v); break;
                case "read_saw_marker": o.readSawMarker = "1".equals(v.trim()); break;
                case "read_matched_line": o.readMatchedLine = unescape(v); break;
                case "broad_frame_ok": o.broadFrameOk = "1".equals(v.trim()); break;
                case "broad_chars": o.broadChars = parseInt(v); break;
                case "broad_saw_marker": o.broadSawMarker = "1".equals(v.trim()); break;
                case "buffer_cleared": o.bufferCleared = "1".equals(v.trim()); break;
                case "read_raw_file": o.readRawFile = v.trim(); break;
                case "broad_raw_file": o.broadRawFile = v.trim(); break;
                case "control_out": o.controlOut = unescape(v); break;
                default: break;
            }
        }
        if (o.markerLine.isEmpty() && AttemptId.wellFormed(o.markerId)) {
            o.markerLine = AttemptMarker.line(o.markerId);
        }
        return o;
    }

    private static int parseInt(String v) {
        try { return Integer.parseInt(v.trim()); } catch (Throwable t) { return 0; }
    }

    private static String unescape(String v) {
        return v.replace("\\n", "\n").trim();
    }

    /**
     * 桌面侧入口：把设备脚本的观测文件喂给生产分类器并打印结论。
     *
     * <pre>java -cp &lt;classes:android.jar&gt; io.github...audit.MarkerSelfCheck obs.txt</pre>
     */
    public static void main(String[] args) throws Exception {
        String text = args.length > 0
                ? new String(Files.readAllBytes(new File(args[0]).toPath()), StandardCharsets.UTF_8)
                : "";
        Obs o = parseObs(text);
        // 用**生产匹配器**验证设备上落盘的原始读取文本 ——
        // 这是唯一能把「真实 logcat 格式」与「AttemptMarker 的匹配规则」对上的一步。
        if (!o.readRawFile.isEmpty()) {
            String raw = readFileOrNull(o.readRawFile);
            if (raw != null) {
                o.presentOnRaw = AttemptMarker.present(raw, o.markerId);
                AttemptMarker.Window w = AttemptMarker.evaluate(o.markerId, true, true, raw);
                o.evaluateReasonOnRaw = w.reason + "/iso=" + w.isolated;
                System.out.println("== 生产匹配器验证（设备原始文本）==");
                System.out.println("原文前 2 行:");
                String[] lines = raw.split("\n");
                for (int i = 0; i < Math.min(2, lines.length); i++) {
                    if (!lines[i].isEmpty()) System.out.println("   " + lines[i]);
                }
                System.out.println("present(raw, id) = " + o.presentOnRaw);
                System.out.println("evaluate(raw, id) = " + o.evaluateReasonOnRaw);
                System.out.println("sliceAfter 非空 = " + !AttemptMarker.sliceAfter(raw, o.markerId).isEmpty());
            } else {
                System.out.println("警告: 读不到原始文件 " + o.readRawFile);
            }
        }
        Result r = new Result(classify(o), o);
        for (String l : r.lines()) System.out.println(l);
    }

    private static String readFileOrNull(String path) {
        try {
            return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
