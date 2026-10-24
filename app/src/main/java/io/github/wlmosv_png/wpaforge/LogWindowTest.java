package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AttemptMarker;
import io.github.wlmosv_png.wpaforge.audit.AttemptId;
import io.github.wlmosv_png.wpaforge.audit.Lc;
import io.github.wlmosv_png.wpaforge.audit.LogcatCmd;
import io.github.wlmosv_png.wpaforge.audit.WpaLogReader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * R11 回归测试：日志窗口从「清空全局缓冲」改为「时间锚点 + 起点标记」。
 *
 * <h3>为什么必须换掉 {@code logcat -c}</h3>
 * <ul>
 *   <li><b>全局副作用</b>：把整机其它组件的缓冲一起清掉，用户随后想抓现场就没了；</li>
 *   <li><b>从不校验</b>：清空失败时上一条候选的失败行会留在缓冲里被算到本条头上
 *       （R3 把它列为“高风险假设”）；</li>
 *   <li><b>ColorOS 配额</b>：清完常常一条都读不到，整层日志判据失效。</li>
 * </ul>
 *
 * <h3>替换后的两道防线</h3>
 * <ol>
 *   <li>{@link Lc} 给出的**时间锚点**：{@code logcat -d -t '<MM-DD HH:MM:SS.mmm>'}
 *       —— 只读该时刻之后的行；</li>
 *   <li>{@link AttemptMarker} 的**起点标记**：切片必须从本条标记之后开始。</li>
 * </ol>
 * 关键不变式：**时间锚点不能替代标记**。锚点只说明“不早于某时刻”，
 * 无法区分同一条候选窗口内、属于别的候选的行；只有标记能划出归属。
 */
public class LogWindowTest {

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

    public static void main(String[] args) {
        System.out.println("== R11L-1 时间锚点格式 ==");
        eq("0 → 没有锚点", null, Lc.anchor(0));
        eq("负数 → 没有锚点", null, Lc.anchor(-5));
        String a = Lc.anchor(1_764_000_000_000L);
        ok("非零时刻产出锚点", a != null);
        ok("形状符合 logcat -t（MM-dd HH:mm:ss.SSS）",
                a != null && a.matches("\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"));
        ok("锚点里没有年份（logcat 不接受年份）",
                a != null && !a.contains("2025") && !a.contains("2026"));
        eq("同一时刻稳定可复现", a, Lc.anchor(1_764_000_000_000L));

        System.out.println("== R11L-2 向前退让一秒（宁多读一行，不漏一行） ==");
        eq("正常退 1000ms", 1_763_999_999_000L, Lc.backOff(1_764_000_000_000L));
        eq("很小值不动（避免退成负数）", 500L, Lc.backOff(500L));
        eq("0 不动", 0L, Lc.backOff(0L));
        eq("负数不动", -3L, Lc.backOff(-3L));

        System.out.println("== R11L-3 命令拼接 ==");
        String base = "logcat -d -e 'ClientModeImpl'";
        eq("锚点为 0 时命令原样", base, Lc.withSince(base, 0L));
        String cmd = Lc.withSince(base, 1_764_000_000_000L);
        ok("带锚点命令以基础命令开头", cmd.startsWith(base));
        ok("锚点带单引号（里面有空格，不加引号会被拆参数）", cmd.contains(" -t '"));
        ok("单引号成对", cmd.indexOf('\'') > 0
                && cmd.indexOf('\'', cmd.indexOf('\'') + 1) > 0);

        System.out.println("== R11L-3b 带管道时锚点必须插在 logcat 一侧（关键） ==");
        // 真实读取命令是 `logcat … | grep -iE '…'`。锚点若拼到整条末尾，
        // 就落进 grep 的参数里 → grep 报错 → 输出为空 → 日志判据**静默**全灭。
        long T = 1_764_000_000_000L;
        String piped = Lc.withSince(WpaLogReader.readCommand(), T);
        int atFlag = piped.indexOf(" -t '");
        int atPipe = piped.indexOf('|');
        ok("命令里既有锚点也有管道（前提成立）", atFlag > 0 && atPipe > 0);
        ok("锚点出现在管道**之前**（给 logcat，不是给 grep）", atFlag < atPipe);
        ok("管道之后的过滤式原样保留",
                piped.substring(atPipe).contains("grep -iE"));
        ok("管道后不再多出第二个 -t",
                piped.indexOf(" -t '", atFlag + 1) < 0);
        String noPipe = Lc.withSince("logcat -d -e 'ClientModeImpl'", T);
        ok("无管道时锚点追加在末尾", noPipe.endsWith("'") && noPipe.contains(" -t '"));

        System.out.println("== R11L-4 两个读取器的命令形状 ==");
        String plain = WpaLogReader.readCommand();
        ok("基础命令仍是不带时间限制的老形状（离线/降级路径可用）",
                plain.contains("logcat -d") && !plain.contains(" -t '"));
        String since = WpaLogReader.readCommandSince(1_764_000_000_000L);
        ok("时间版带上了锚点", since.contains(" -t '"));
        ok("时间版与基础命令不同（确实插了锚点）", !since.equals(plain));
        ok("时间版仍保留基础命令的 logcat 段与过滤段",
                since.contains("logcat -d -s wpa_supplicant -s WPF_PROBE")
                        && since.contains("grep -iE"));
        String wlrSrc = sourceOf("audit/WpaLogReader.java");
        int execClear = 0, at = 0;
        while ((at = wlrSrc.indexOf("shell.exec(\"logcat -c\"", at)) >= 0) { execClear++; at++; }
        int depAt = wlrSrc.lastIndexOf("@Deprecated");
        int lastExecClear = wlrSrc.lastIndexOf("shell.exec(\"logcat -c\"");
        eq("执行版 logcat -c 只剩 1 处", 1, execClear);
        ok("那一处在弃用方法里（已被 @Deprecated 标注）",
                lastExecClear > 0 && depAt > 0 && depAt < lastExecClear);
        ok("读取路径走「时间锚点 + 退出码哨兵」版命令",
                wlrSrc.contains("readCommandGuarded(anchorMs)")
                        && wlrSrc.contains("LogcatCmd.wrap(readCommandSince(anchorMs))"));

        System.out.println("== R11L-5 标记与锚点是两道独立防线 ==");
        String id = AttemptId.of(AttemptId.newRunId(1_764_000_000_000L, "LAB-TARGET", 0), 1);
        ok("候选 id 合法", AttemptId.wellFormed(id));

        AttemptMarker.Window anchoredNoMarker =
                AttemptMarker.evaluate(id, true, true, "只有别的网络的日志\n", true);
        ok("窗口元数据记录了“用了时间锚点”", anchoredNoMarker.timeAnchored);
        ok("**但锚点不能替代标记**：没有自己的标记 → 仍然不可用",
                !anchoredNoMarker.evidenceUsable());
        eq("理由仍是 no-marker", "no-marker", anchoredNoMarker.reason);

        AttemptMarker.Window both =
                AttemptMarker.evaluate(id, true, true, AttemptMarker.line(id) + "\n证据行\n", true);
        ok("锚点 + 标记都在 → 可用", both.evidenceUsable());
        eq("理由 ok", "ok", both.reason);

        AttemptMarker.Window oldSig = AttemptMarker.evaluate(id, true, true,
                AttemptMarker.line(id) + "\n证据行\n");
        ok("旧签名（无锚点）行为不变 —— 既有测试与调用点不受影响",
                oldSig.evidenceUsable() && !oldSig.timeAnchored);

        System.out.println("== R11L-6b WPS 路径也不再清空全局缓冲（最终审计 P0-3）==");
        String wpsBase = "/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/wpaforge/";
        String wps = read(wpsBase + "wps/WpsHelper.java");
        if (wps == null) {
            System.out.println("  N/RUN WPS 接线守卫（源码不可读）");
        } else {
            ok("WPS 不再执行 logcat -c", !wps.contains("exec(\"logcat -c\""));
            ok("窗口起点改成记锚点", wps.contains("logAnchorMs = System.currentTimeMillis();"));
            ok("读取走时间锚点切片（Lc.withSince）",
                    wps.contains("io.github.wlmosv_png.wpaforge.audit.Lc.withSince("));
            ok("锚点缺失时仍有兜底（最近 5000 行）",
                    wps.contains("\"logcat -d -s wpa_supplicant:V -t 5000\""));
        }
        // 生产代码里除两处 @Deprecated helper 外，不应再有任何 logcat -c 执行点
        String[][] prod = {
                {"audit/WpaLogReader.java", read(wpsBase + "audit/WpaLogReader.java")},
                {"audit/WifiStateLog.java", read(wpsBase + "audit/WifiStateLog.java")},
                {"MainActivity.java", read(wpsBase + "MainActivity.java")},
        };
        for (String[] f : prod) {
            String src = f[1];
            if (src == null) { System.out.println("  N/RUN " + f[0]); continue; }
            int calls = 0, scan = 0;
            while ((scan = src.indexOf("exec(\"logcat -c\"", scan)) >= 0) { calls++; scan++; }
            if ("MainActivity.java".equals(f[0])) {
                eq(f[0] + " 无 logcat -c", 0, calls);
            } else {
                ok(f[0] + " 仅剩弃用 helper 里那一处（且被 @Deprecated 标注）",
                        calls <= 1 && src.contains("@Deprecated"));
            }
        }

        System.out.println("== R11L-6 接线守卫：生产主循环不再清空全局缓冲 ==");
        String eng = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/audit/CrackEngine.java");
        String wsl = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/audit/WifiStateLog.java");
        String wlr = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/audit/WpaLogReader.java");
        if (eng == null || wsl == null || wlr == null) {
            System.out.println("  N/RUN 接线守卫（源码不可读）");
        } else {
            ok("引擎不再调用 clearChecked", !eng.contains("clearChecked("));
            ok("引擎记录窗口起点时刻", eng.contains("windowFromMs"));
            ok("引擎把时刻交给读取器（并取结构化状态）",
                    eng.contains("WpaLogReader.readEx(shell, 400, windowFromMs)"));
            ok("引擎把「日志是否可读」一起交给窗口元数据",
                    eng.contains("wlog, true, logReadOk);")
                            && eng.contains("boolean logReadOk = lr != null && lr.logUsable();"));
            ok("日志不可判时留下可区分的原因（不再与「没日志」混同）",
                    eng.contains("日志不可判: "));
            ok("旧的断开重连式复验已删除（它曾用状态机日志做第二次判定）",
                    !eng.contains("WifiStateLog.read("));
            ok("状态机日志读取器仍带锚点与哨兵",
                    wsl.contains("Lc.withSince(\"logcat -d -e 'ClientModeImpl'\", anchorMs)")
                            && wsl.contains("LogcatCmd.wrap(cmd)"));
            ok("状态机读取器用时间锚点",
                    wsl.contains("Lc.withSince(\"logcat -d -e 'ClientModeImpl'\", anchorMs)"));
            ok("清空 API 已标记弃用（保留是为了对照历史审计，不再被生产调用）",
                    wsl.contains("@Deprecated") && wsl.contains("clearChecked"));
            ok("wpa 日志读取器提供时间版命令与哨兵版命令",
                    wlr.contains("readCommandSince") && wlr.contains("readCommandGuarded"));
            ok("过滤式放行 logcat 侧哨兵（否则上游状态会被 grep 亲手滤掉）",
                    WpaLogReader.SHELL_FILTER.contains(LogcatCmd.RC));
        }

        System.out.println();
        System.out.println("=== R11 日志窗口测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String sourceOf(String rel) {
        return read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/wpaforge/" + rel);
    }

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
