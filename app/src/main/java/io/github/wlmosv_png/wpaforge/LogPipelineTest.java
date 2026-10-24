package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Lc;
import io.github.wlmosv_png.wpaforge.audit.LogcatCmd;
import io.github.wlmosv_png.wpaforge.audit.WpaLogReader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P0-3 回归测试：日志管道的退出状态必须可判。
 *
 * <h3>被修掉的那个缺陷</h3>
 * 读取命令是 {@code logcat … | grep …}，而 {@code ShellExec} 只取 stdout、不看退出码；
 * 管道里 {@code $?} 又是 grep 的。于是四种完全不同的情况在调用方看来都是「输出为空」：
 * <pre>
 *   上游 logcat 失败（例如 -t 格式错）
 *   窗口里确实没有匹配行
 *   grep 自己坏了（过滤式写错）
 *   命令根本没跑成（通道失联）
 * </pre>
 * 一旦把它们混同，判定链就会把「读不到日志」当成「没有失败证据」，
 * 进而可能推出「密码错」——这是**结论强于证据**的典型。
 *
 * <h3>本测试钉住的东西</h3>
 * <ol>
 *   <li>两枚哨兵都出现在正确的一侧（logcat 的在管道**内**，grep 的在管道**外**）；</li>
 *   <li>五种档位可区分，且缺失哨兵一律归「不可判」（不猜）；</li>
 *   <li>只有 {@code OK}/{@code NO_MATCH} 才允许被当作「日志证据可用」；</li>
 *   <li>生产读取命令确实带上了哨兵与时间锚点，且锚点在管道之前。</li>
 * </ol>
 */
public class LogPipelineTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        boolean c = (want == null) ? got == null : want.equals(got);
        if (!c) System.out.println("        want=" + want + " got=" + got);
        ok(name, c);
    }

    /** 拼一段模拟 shell 回显。 */
    private static String echo(int logcatRc, int grepRc, String body) {
        return (body == null ? "" : body)
                + LogcatCmd.RC + logcatRc + "\n"
                + LogcatCmd.RC_GREP + grepRc + "\n";
    }

    public static void main(String[] args) {
        System.out.println("== P0-3a 命令包裹：两侧退码各就各位 ==");
        String piped = LogcatCmd.wrap("logcat -d -s A | grep -iE 'x'");
        ok("以花括号分组开头（logcat 与它的退码必须在同一个管道里）",
                piped.startsWith("{ "));
        ok("logcat 一侧回显自己的退码", piped.contains("2>&1; echo \"" + LogcatCmd.RC + "$?\"; }"));
        ok("grep 的退码回显在管道**之外**（否则会被过滤式吃掉）",
                piped.endsWith("| grep -iE 'x'; echo \"" + LogcatCmd.RC_GREP + "$?\""));
        ok("管道没有被 `;` 截断（括号闭合后才接管道）",
                piped.indexOf("; }") < piped.indexOf("| grep"));
        ok("stderr 被收进 stdout（否则 logcat 的报错文本看不见）", piped.contains("2>&1"));

        String plain = LogcatCmd.wrap("logcat -d -e 'X'");
        ok("无管道时两枚退码都追加在末尾",
                plain.endsWith("echo \"" + LogcatCmd.RC + "$?\"; echo \""
                        + LogcatCmd.RC_GREP + "$?\""));
        eq("空串原样返回", "", LogcatCmd.wrap(""));
        eq("null 原样返回", null, LogcatCmd.wrap(null));

        System.out.println("== P0-3b 五档判定：四种情况不得混为一谈 ==");
        LogcatCmd.Result r;

        r = LogcatCmd.parse("1000 lines of noise\n" + echo(0, 0, "## evidence\n"));
        eq("上游 ok + 有匹配 → OK", LogcatCmd.Status.OK, r.status);
        ok("正文保留证据行", r.body.contains("## evidence"));
        ok("正文不含哨兵行", !r.body.contains(LogcatCmd.RC)
                && !r.body.contains(LogcatCmd.RC_GREP));
        ok("这一档允许当日志证据用", r.logUsable() && r.hasLines());

        r = LogcatCmd.parse(echo(0, 1, ""));
        eq("上游 ok + grep 报无匹配 → NO_MATCH", LogcatCmd.Status.NO_MATCH, r.status);
        ok("这是**可信的空**（允许当证据用，但没有行）", r.logUsable() && !r.hasLines());

        // 真机形状：哨兵行自己会被过滤式放行 ⇒ grep 退码恒为 0。
        // 这一档只能靠「剥掉哨兵后正文为空」识别（2026-10-24 设备实测）。
        r = LogcatCmd.parse(echo(0, 0, ""));
        eq("上游 ok + grep=0 但正文为空（真机上的空窗口）→ NO_MATCH",
                LogcatCmd.Status.NO_MATCH, r.status);
        ok("仍然算「日志可用」，只是没有行", r.logUsable() && !r.hasLines());
        r = LogcatCmd.parse(echo(0, 0, "   \n"));
        eq("只有空白也算空窗口", LogcatCmd.Status.NO_MATCH, r.status);

        r = LogcatCmd.parse("logcat: -t 'garbage' not in time format.\n" + echo(1, 1, ""));
        eq("上游失败 → UPSTREAM_FAILED（且压过 grep 的 1）",
                LogcatCmd.Status.UPSTREAM_FAILED, r.status);
        ok("这一档**不允许**被当成「没有日志」", !r.logUsable());

        r = LogcatCmd.parse(echo(0, 2, ""));
        eq("grep 坏掉 → GREP_FAILED", LogcatCmd.Status.GREP_FAILED, r.status);
        ok("这一档也不允许当证据", !r.logUsable());

        r = LogcatCmd.parse("随便一段没有哨兵的回显\n");
        eq("缺哨兵 → INDETERMINATE", LogcatCmd.Status.INDETERMINATE, r.status);
        ok("不可判 ≠ 没有日志", !r.logUsable() && !r.hasLines());

        r = LogcatCmd.parse(null);
        eq("null → INDETERMINATE", LogcatCmd.Status.INDETERMINATE, r.status);

        r = LogcatCmd.parse(LogcatCmd.RC + "0\n");     // 只有 logcat 侧
        eq("只有一侧哨兵 → INDETERMINATE（不猜另一侧）",
                LogcatCmd.Status.INDETERMINATE, r.status);

        r = LogcatCmd.parse(echo(0, 0, "").replace(LogcatCmd.RC, ""));
        eq("logcat 哨兵名缺失 → INDETERMINATE", LogcatCmd.Status.INDETERMINATE, r.status);

        System.out.println("== P0-3c 四档必须两两不同（同一输入不可能落两档） ==");
        String[] samples = {
                echo(0, 0, "x\n"),      // OK
                echo(0, 1, ""),         // NO_MATCH
                echo(2, 1, ""),         // UPSTREAM_FAILED
                echo(0, 2, ""),         // GREP_FAILED
                "no sentinel",          // INDETERMINATE
        };
        LogcatCmd.Status[] want = {
                LogcatCmd.Status.OK, LogcatCmd.Status.NO_MATCH,
                LogcatCmd.Status.UPSTREAM_FAILED, LogcatCmd.Status.GREP_FAILED,
                LogcatCmd.Status.INDETERMINATE,
        };
        int mismatch = 0;
        java.util.Set<LogcatCmd.Status> seen = new java.util.HashSet<>();
        for (int i = 0; i < samples.length; i++) {
            LogcatCmd.Status got = LogcatCmd.parse(samples[i]).status;
            if (got != want[i]) mismatch++;
            seen.add(got);
        }
        eq("五个用例各自落到预期档位", 0, mismatch);
        eq("五档互不相同（没有两档被合并）", 5, seen.size());

        System.out.println("== P0-3d 生产读取命令确实带上了哨兵与锚点 ==");
        long T = 1_764_000_000_000L;
        String base = WpaLogReader.readCommand();
        String since = WpaLogReader.readCommandSince(T);
        String guarded = WpaLogReader.readCommandGuarded(T);

        ok("基础命令仍是纯管道形状（无哨兵）", base.contains("| grep -iE"));
        ok("时间版把锚点放在管道之前（否则会落进 grep 参数）",
                since.contains(" -t '") && since.indexOf(" -t '") < since.indexOf('|'));
        ok("哨兵版带两侧退码", guarded.contains(LogcatCmd.RC) && guarded.contains(LogcatCmd.RC_GREP));
        ok("哨兵版仍是花括号分组", guarded.startsWith("{ "));
        ok("时间锚点仍在管道之前", guarded.indexOf(" -t '") < guarded.indexOf("| grep"));
        eq("锚点为 0 时不加 -t（降级路径）",
                true, WpaLogReader.readCommandGuarded(0L).indexOf(" -t '") < 0);
        ok("Lc 的锚点格式未变", Lc.anchor(T).matches("\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"));

        System.out.println("== P0-3e 过滤式必须放行 logcat 侧哨兵 ==");
        ok("SHELL_FILTER 含 __WP_LC_RC=（否则上游状态被 grep 亲手滤掉）",
                WpaLogReader.SHELL_FILTER.contains(LogcatCmd.RC));
        ok("哨兵行真的能穿过过滤式",
                java.util.regex.Pattern.compile(WpaLogReader.SHELL_FILTER,
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(LogcatCmd.RC + "0").find());
        ok("噪音仍然被挡", !java.util.regex.Pattern
                .compile(WpaLogReader.SHELL_FILTER, java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher("wlan0: CTRL-EVENT-SCAN-RESULTS").find());

        System.out.println("== P0-3f 引擎接线：只认结构化状态，不看 stdout 空不空 ==");
        String eng = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/audit/CrackEngine.java");
        String wlr = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/audit/WpaLogReader.java");
        if (eng == null || wlr == null) {
            System.out.println("  N/RUN 接线守卫（源码不可读）");
        } else {
            ok("引擎用 readEx 取结构化结果", eng.contains("WpaLogReader.readEx(shell, 400, windowFromMs)"));
            ok("引擎按 logUsable() 判定可用性（不是 isEmpty()）",
                    eng.contains("boolean logReadOk = lr != null && lr.logUsable();"));
            ok("可读性标志只在 logReadOk 时才置位",
                    eng.contains("if (!logReadOk)") && eng.contains("日志不可判: "));
            ok("窗口可用性 = 通道可读 且 窗口隔离",
                    eng.contains("lastWindow.usableForVerdict()"));
            ok("读取器把 stdout 交给 LogcatCmd 解析，不再直接当正文用",
                    wlr.contains("LogcatCmd.parse(out)"));
            ok("读取器保留 body 语义（哨兵行不外泄）",
                    wlr.contains("LogcatCmd.parse(out).body")
                            || wlr.contains("return LogcatCmd.parse(out);"));
        }

        System.out.println();
        System.out.println("=== P0-3 日志管道测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
