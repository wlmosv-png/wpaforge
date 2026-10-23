package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AttemptId;
import io.github.wlmosv_png.wpaforge.audit.AttemptMarker;
import io.github.wlmosv_png.wpaforge.audit.MarkerSelfCheck;
import io.github.wlmosv_png.wpaforge.audit.WpaLogReader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 日志标记可见性自检的**离线**测试（进主门禁，不碰设备）。
 *
 * <p>覆盖三件事：
 * <ol>
 *   <li>{@link MarkerSelfCheck#classify} 的 10 种结论都能从合成观测得到
 *       —— 包括「过滤不匹配」与「标记不可见」的区分、以及权限受限的区分；</li>
 *   <li>自检**只**使用与生产一致的写入/过滤/读取命令，且**不含任何 Wi-Fi 动词**
 *       （不连接、不断开、不改开关、不用候选密码）；</li>
 *   <li>自检与判定链**分离**：判定链不引用它，{@code isolated} 契约未被放宽。</li>
 * </ol>
 *
 * <p>设备侧的真实可见性由 {@code tools/marker-selfcheck.sh} 采集、再由
 * {@link MarkerSelfCheck#main} 用同一份分类器解释 —— 本测试保证那两份命令与
 * 生产常量逐字一致（脚本漂移即判红）。
 */
public class MarkerSelfCheckTest {

    private static int pass = 0, fail = 0;
    private static final String ROOT = "/workspace/promo-wpaforge";

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        boolean c = (want == null) ? got == null : want.equals(got);
        if (!c) System.out.println("        want=" + want + " got=" + got);
        ok(name, c);
    }

    /** 造一份"一切正常、标记可见"的观测。 */
    private static MarkerSelfCheck.Obs visible() {
        MarkerSelfCheck.Obs o = new MarkerSelfCheck.Obs();
        o.ran = true;
        o.markerId = AttemptId.of(AttemptId.newRunId(1_764_000_000_000L,
                MarkerSelfCheck.SYNTHETIC_SSID, 0), 1);
        o.markerLine = AttemptMarker.line(o.markerId);
        o.logBinaryFound = true;
        o.writeFrameOk = true;
        o.controlOut = "0";
        o.attempts = 1;
        o.readFrameOk = true;
        o.readChars = 120;
        o.readSawMarker = true;
        o.bufferCleared = true;
        return o;
    }

    public static void main(String[] args) {
        System.out.println("== 分类：10 种结论都能从合成观测得到 ==");
        eq("标记可见 → VISIBLE", MarkerSelfCheck.Code.VISIBLE,
                MarkerSelfCheck.classify(visible()));

        MarkerSelfCheck.Obs o = visible();
        o.readSawMarker = false;
        o.broadSawMarker = true;
        eq("宽读能见、生产读取不能见 → FILTER_MISMATCH",
                MarkerSelfCheck.Code.FILTER_MISMATCH, MarkerSelfCheck.classify(o));

        o = visible();
        o.readSawMarker = false;
        o.broadSawMarker = false;
        eq("两种读法都看不到 → NOT_VISIBLE", MarkerSelfCheck.Code.NOT_VISIBLE,
                MarkerSelfCheck.classify(o));

        o = visible();
        o.logBinaryFound = false;
        eq("没有 log 命令 → LOG_BINARY_MISSING", MarkerSelfCheck.Code.LOG_BINARY_MISSING,
                MarkerSelfCheck.classify(o));

        o = visible();
        o.writeFrameOk = false;
        eq("写命令没拿到帧 → WRITE_NO_FRAME", MarkerSelfCheck.Code.WRITE_NO_FRAME,
                MarkerSelfCheck.classify(o));

        o = visible();
        o.writeOutput = "log: Permission denied";
        eq("写命令报权限错 → WRITE_ERROR", MarkerSelfCheck.Code.WRITE_ERROR,
                MarkerSelfCheck.classify(o));

        o = visible();
        o.readSawMarker = false;
        o.readFrameOk = false;
        o.broadSawMarker = false;
        o.controlOut = "";
        eq("读取没帧且控制命令也无输出 → READ_NO_FRAME",
                MarkerSelfCheck.Code.READ_NO_FRAME, MarkerSelfCheck.classify(o));

        o = visible();
        o.readSawMarker = false;
        o.readFrameOk = false;
        o.broadSawMarker = false;
        o.controlOut = "0";                       // 控制命令正常 → 权限/SELinux 限制
        eq("控制命令正常但读不到帧 → READER_BLOCKED", MarkerSelfCheck.Code.READER_BLOCKED,
                MarkerSelfCheck.classify(o));

        o = visible();
        o.ran = false;
        eq("未运行 → NO_CHANNEL", MarkerSelfCheck.Code.NO_CHANNEL,
                MarkerSelfCheck.classify(o));

        o = visible();
        o.markerId = "garbage";
        eq("合成 id 非法 → MARKER_ID_INVALID", MarkerSelfCheck.Code.MARKER_ID_INVALID,
                MarkerSelfCheck.classify(o));
        eq("null 观测也不崩 → NO_CHANNEL", MarkerSelfCheck.Code.NO_CHANNEL,
                MarkerSelfCheck.classify(null));

        System.out.println("== 结论语义：不可见 ≠ 认证失败 ==");
        ok("只有 VISIBLE 才算通过", MarkerSelfCheck.isVisible(MarkerSelfCheck.Code.VISIBLE)
                && !MarkerSelfCheck.isVisible(MarkerSelfCheck.Code.NOT_VISIBLE));
        String s = MarkerSelfCheck.summary(MarkerSelfCheck.Code.NOT_VISIBLE);
        ok("NOT_VISIBLE 的说明写明「这不等于认证失败」", s.contains("不等于认证失败"));
        ok("VISIBLE 的说明写明「不能证明判定链已通过真实网络场景验证」",
                MarkerSelfCheck.summary(MarkerSelfCheck.Code.VISIBLE)
                        .contains("不能证明判定链已通过真实网络场景验证"));

        System.out.println("== 观测解析（KEY=VALUE，采集与判定分离）==");
        MarkerSelfCheck.Obs parsed = MarkerSelfCheck.parseObs(
                "ran=1\n"
                        + "marker_id=" + visible().markerId + "\n"
                        + "log_binary_found=1\nwrite_frame_ok=1\nwrite_output=\n"
                        + "read_attempts=2\nread_frame_ok=1\nread_chars=88\n"
                        + "read_saw_marker=1\nread_matched_line=10-24 09:00:00.000 x WPF_PROBE|attempt="
                        + visible().markerId + "\n"
                        + "control_out=0\nbuffer_cleared=1\n");
        eq("解析后判定为 VISIBLE", MarkerSelfCheck.Code.VISIBLE,
                MarkerSelfCheck.classify(parsed));
        eq("命中的标记行被保留", true, parsed.readMatchedLine.contains(visible().markerId));
        MarkerSelfCheck.Obs p2 = MarkerSelfCheck.parseObs(
                "ran=1\nmarker_id=" + visible().markerId + "\nlog_binary_found=1\n"
                        + "write_frame_ok=1\nread_frame_ok=1\nread_saw_marker=0\n"
                        + "broad_frame_ok=1\nbroad_saw_marker=0\ncontrol_out=0\n");
        eq("解析后判定为 NOT_VISIBLE", MarkerSelfCheck.Code.NOT_VISIBLE,
                MarkerSelfCheck.classify(p2));
        eq("空输入不崩", MarkerSelfCheck.Code.NO_CHANNEL,
                MarkerSelfCheck.classify(MarkerSelfCheck.parseObs(null)));

        System.out.println("== 禁止动词：自检不得触碰 Wi-Fi ==");
        String jc = read(ROOT + "/app/src/main/java/io/github/wlmosv_png/wpaforge/audit/"
                + "MarkerSelfCheck.java");
        String sh = read(ROOT + "/tools/marker-selfcheck.sh");
        if (jc == null || sh == null) {
            System.out.println("  N/RUN 命令守卫（源码不可读）");
        } else {
            int badJava = 0, badSh = 0;
            for (String v : MarkerSelfCheck.FORBIDDEN_VERBS) {
                // 每个禁用动词在 Java 里**只允许出现 1 次**：常量表的声明本身。
                // 出现第 2 次就说明有人真的把它当命令用了。
                int n = 0, at = 0;
                String need = "\"" + v + "\"";
                while ((at = jc.indexOf(need, at)) >= 0) { n++; at += need.length(); }
                if (n != 1) badJava++;
            }
            for (String v : MarkerSelfCheck.FORBIDDEN_VERBS) {
                if (shWithoutComments(sh).contains(v)) badSh++;
            }
            eq("Java 里没有任何 Wi-Fi 动词（含字符串）", 0, badJava);
            eq("脚本代码段（去掉注释）没有任何 Wi-Fi 动词", 0, badSh);
            ok("脚本注释里明确列出禁用动词（给人看的安全约束）",
                    sh.contains("不得出现 connect-network"));
            ok("Java 只执行允许的命令（控制/探测/写入/读取/清空）",
                    jc.contains("\"id -u\"") && jc.contains("command -v log || which log")
                            && jc.contains("logcat -c"));
        }

        System.out.println("== 命令与生产逐字一致（脚本漂移即红）==");
        String writeCmd = AttemptMarker.writeCommand(
                AttemptId.of(AttemptId.newRunId(1_764_000_000_000L,
                        MarkerSelfCheck.SYNTHETIC_SSID, 0), 1));
        ok("脚本用的是生产写入形式 log -t " + AttemptMarker.TAG + " \"attempt=<id>\"",
                sh != null && sh.contains("log -t ${MARKER_TAG}")
                        && sh.contains("${MARKER_FIELD}${MARKER_ID}")
                        && writeCmd != null && writeCmd.startsWith("log -t " + AttemptMarker.TAG));
        ok("脚本的读取命令与 WpaLogReader.readCommand() 形状一致",
                sh != null && sh.contains("logcat -d -s wpa_supplicant -s ${MARKER_TAG}")
                        && sh.contains("grep -iE '${SHELL_FILTER}'")
                        && WpaLogReader.readCommand().contains("-s wpa_supplicant")
                        && WpaLogReader.readCommand().contains("-s " + AttemptMarker.TAG));
        ok("脚本内嵌的过滤式与 SHELL_FILTER 逐字一致",
                sh != null && sh.contains(WpaLogReader.SHELL_FILTER));
        ok("脚本内嵌的 SSID 哈希与 AttemptId 生成的哈希段一致",
                sh != null && sh.contains(ssidHash8()));
        // 反向：脚本里出现的过滤式只能有这一份
        int filterMentions = 0;
        for (String line : (sh == null ? "" : sh).split("\n")) {
            if (!line.startsWith("#") && line.contains("SHELL_FILTER=")) filterMentions++;
        }
        eq("过滤式在脚本里只定义一次", 1, filterMentions);

        System.out.println("== 设备实证回归：真实 logcat 行必须能被生产匹配器认出 ==");
        // 2026-10-24 设备自检抓到的真问题：真实输出是 `TAG: "message"`，
        // 而原匹配器要求子串 `TAG|message` → present/sliceAfter 永远为假。
        // 下面这行取自设备原始输出（/data/local/tmp/marker-selfcheck-raw.txt）。
        String devId = "1792791569-60578b9b-0#1";
        String devLine = "10-24 05:39:29.215 28234 28234 I WPF_PROBE: \"attempt=" + devId + "\"";
        ok("真实设备行能被 present 认出", AttemptMarker.present(devLine, devId));
        ok("真实设备行的切片不再为空",
                !AttemptMarker.sliceAfter(devLine + "\nwlan0: 后续证据", devId).isEmpty());
        ok("切片内容正确（标记之后）",
                AttemptMarker.sliceAfter(devLine + "\nwlan0: 后续证据", devId)
                        .trim().equals("wlan0: 后续证据"));
        ok("真实设备行能让窗口判定为可用（isolated=true）",
                AttemptMarker.evaluate(devId, true, true, devLine).evidenceUsable());
        eq("窗口理由码 = ok", "ok",
                AttemptMarker.evaluate(devId, true, true, devLine).reason);
        ok("兼容旧的合成写法（TAG|attempt=id）",
                AttemptMarker.present(AttemptMarker.line(devId), devId));
        // 相邻编号不能被误认（#1 不能匹配到 #11 上）
        String id1 = "1792791569-60578b9b-0#1";
        String id11 = "1792791569-60578b9b-0#11";
        ok("#1 不会误匹配 #11 的标记行",
                !AttemptMarker.present("WPF_PROBE: \"attempt=" + id11 + "\"", id1));
        ok("#11 能正确匹配自己的标记行",
                AttemptMarker.present("WPF_PROBE: \"attempt=" + id11 + "\"", id11));
        ok("外来标记检测在真实格式下也成立",
                AttemptMarker.foreignMarkerAfter(
                        devLine + "\n" + "I WPF_PROBE: \"attempt=1792791569-60578b9b-0#2\"",
                        devId));

        System.out.println("== 原始文本落盘（供生产匹配器验证设备原文）==");
        MarkerSelfCheck.Obs pr = MarkerSelfCheck.parseObs(
                "ran=1\nmarker_id=" + visible().markerId + "\nlog_binary_found=1\n"
                        + "write_frame_ok=1\nread_frame_ok=1\nread_saw_marker=1\n"
                        + "read_raw_file=/data/local/tmp/marker-selfcheck-read.txt\n"
                        + "broad_raw_file=/data/local/tmp/marker-selfcheck-broad.txt\n");
        eq("解析出原始读取文本路径", "/data/local/tmp/marker-selfcheck-read.txt", pr.readRawFile);
        eq("解析出宽读原始文本路径", "/data/local/tmp/marker-selfcheck-broad.txt", pr.broadRawFile);
        ok("脚本把读取原文落盘并输出路径",
                sh != null && sh.contains("marker-selfcheck-read.txt")
                        && sh.contains("read_raw_file=${READ_RAW_FILE}"));

        System.out.println("== 输出诚实性：未执行 != 执行失败 ==");
        MarkerSelfCheck.Obs vis = visible();
        String visText = String.join("\n",
                new MarkerSelfCheck.Result(MarkerSelfCheck.Code.VISIBLE, vis).lines());
        ok("命中时写明「宽读未执行」，不与失败混为一谈", visText.contains("宽读: 未执行"));
        MarkerSelfCheck.Obs inv = visible();
        inv.readSawMarker = false;
        inv.readFrameOk = true;
        String invText = String.join("\n",
                new MarkerSelfCheck.Result(MarkerSelfCheck.Code.NOT_VISIBLE, inv).lines());
        ok("未命中时才逐项报宽读结果", invText.contains("宽读: frameOk="));

        System.out.println("== 与判定链分离 + 契约未被放宽 ==");
        String eng = read(ROOT + "/app/src/main/java/io/github/wlmosv_png/wpaforge/audit/"
                + "CrackEngine.java");
        String mk = read(ROOT + "/app/src/main/java/io/github/wlmosv_png/wpaforge/audit/"
                + "AttemptMarker.java");
        if (eng == null || mk == null) {
            System.out.println("  N/RUN 分离守卫（源码不可读）");
        } else {
            ok("判定链（CrackEngine）不引用自检类",
                    !eng.contains("MarkerSelfCheck"));
            ok("AttemptMarker 不引用自检类", !mk.contains("MarkerSelfCheck"));
            ok("isolated 契约未被放宽（仍是 标记可见 且 其后无外来标记）",
                    mk.contains("this.isolated = markerVisible && !foreignMarker;"));
            ok("自检没有新的判定出口（不产出 Verdict）",
                    jc != null && !jc.contains("CrackEngine.Verdict."));
        }

        System.out.println();
        System.out.println("=== 标记自检测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** 去掉 shell 注释行后的代码段（用于「禁止动词」扫描）。 */
    private static String shWithoutComments(String sh) {
        StringBuilder sb = new StringBuilder();
        for (String l : sh.split("\n")) {
            if (l.trim().startsWith("#")) continue;
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    private static String ssidHash8() {
        String run = AttemptId.newRunId(1_764_000_000_000L, MarkerSelfCheck.SYNTHETIC_SSID, 0);
        if (run == null) return "";
        return run.split("-")[1];
    }

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
