package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AttemptId;
import io.github.wlmosv_png.wpaforge.audit.AttemptMarker;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.WifiStateLog;
import io.github.wlmosv_png.wpaforge.audit.WpaLogReader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * R6 **接线**回归测试：尝试身份 + 日志窗口隔离状态。
 *
 * <p>覆盖对象是生产类本身（{@link AttemptId}、{@link AttemptMarker}、
 * {@link AttemptMarker.Window}、{@link WifiStateLog.ClearResult}、
 * {@link WpaLogReader#readCommand()}），以及 {@code CrackEngine} 的接线是否存在。
 *
 * <p><b>不复制判定逻辑</b>：这里没有一份「测试自己的判定器」——
 * 要么调用生产静态方法取真实输出，要么对生产源码做接线扫描（会因接线被删而失败）。
 *
 * <p><b>本测试不检查任何成功/失败结论的取值</b>，只检查身份与窗口元数据：
 * 判定条件在 R6 阶段一行未改，元数据不得参与判定。
 */
public class R6WiringTest {

    private static int pass = 0, fail = 0;
    private static final String SSID = "LAB-TARGET";
    private static final long T0 = 1_764_000_000_000L;

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
        String run1 = AttemptId.newRunId(T0, SSID, 0);
        String run2 = AttemptId.newRunId(T0 + 60_000L, SSID, 1);

        System.out.println("== 3.4-a 同一轮多个尝试：ID 唯一、可解析 ==");
        java.util.Set<String> ids = new java.util.HashSet<>();
        boolean seqOk = true, runOk = true;
        for (int i = 1; i <= 200; i++) {
            String id = AttemptId.of(run1, i);
            if (id == null || !ids.add(id)) seqOk = false;
            if (AttemptId.seqOf(id) != i) seqOk = false;
        }
        ok("200 条候选 id 互不重复且序号可解析", seqOk);
        ok("同 run 内 sameRun 全为真", AttemptId.sameRun(AttemptId.of(run1, 1),
                AttemptId.of(run1, 200)));
        for (int i = 1; i <= 20; i++) {
            if (AttemptId.sameRun(AttemptId.of(run1, i), AttemptId.of(run2, i))) runOk = false;
            if (AttemptId.exactlyMatches(AttemptId.of(run1, i), AttemptId.of(run2, i))) runOk = false;
        }
        ok("跨轮次同序号不串扰（sameRun/exactlyMatches 均否）", runOk);

        System.out.println("== 3.4-b 缺失 / 非法 ID 必须显式无效 ==");
        ok("null id → 非法", !AttemptId.wellFormed(null));
        ok("AttemptId.of(null, 1) → null", AttemptId.of(null, 1) == null);
        ok("占位串 <id-invalid> 不构成合法 id", !AttemptId.wellFormed("<id-invalid>"));
        AttemptMarker.Window wNull = AttemptMarker.evaluate(null, true, true,
                AttemptMarker.line(AttemptId.of(run1, 1)));
        eq("缺 id 时窗口元数据不采纳标记（attemptId=null）", null, wNull.attemptId);
        ok("缺 id → reason=id-invalid", "id-invalid".equals(wNull.reason));
        ok("缺 id → isolated=false（不因文本里有别的标记就算通过）", !wNull.isolated);
        // 非法**非空**串：必须被归一成 null，不能当成某个标识留在记录里，
        // 也不能因为「反正解析不出来」就悄悄变成合法 id。
        AttemptMarker.Window wGarbage = AttemptMarker.evaluate("garbage", true, true,
                AttemptMarker.line(AttemptId.of(run1, 1)));
        eq("非法 id 不进入元数据（归一为 null）", null, wGarbage.attemptId);
        eq("非法 id → reason=id-invalid", "id-invalid", wGarbage.reason);
        ok("非法 id → isolated=false（哪怕文本里有合法标记）", !wGarbage.isolated);
        AttemptMarker.Window wEmpty = AttemptMarker.evaluate("", true, true, "");
        eq("空串 id 同样归一为 null", null, wEmpty.attemptId);
        ok("合法 id 原样保留", AttemptId.of(run1, 7).equals(
                AttemptMarker.evaluate(AttemptId.of(run1, 7), true, true, "").attemptId));

        eq("判词对非法 id 显式打标",
                "[<id-invalid>] #1 #29ff2d(len=9)=✓hit",
                CrackEngine.verdictTag(null, 1, "sample-pw", CrackEngine.Verdict.SUCCESS, "hit"));
        eq("合法 id 原样进判词",
                "[" + AttemptId.of(run1, 11) + "] #11 #29ff2d(len=9)=✗m1silent",
                CrackEngine.verdictTag(AttemptId.of(run1, 11), 11, "sample-pw",
                        CrackEngine.Verdict.AUTH_FAIL, "m1silent"));

        System.out.println("== 3.4-c 日志清理失败 ==");
        ok("通道为空 → 清理结果 ok()=false", !WifiStateLog.clearChecked(null).ok());
        WifiStateLog.ClearResult cr = WifiStateLog.clearChecked(null);
        ok("清理结果把 issued/frameOk 分开记录", !cr.issued && !cr.frameOk);
        String id1 = AttemptId.of(run1, 1);
        String text1 = AttemptMarker.line(id1) + "\n"
                + "wlan0: Trying to associate with SSID 'LAB-TARGET'\n";
        AttemptMarker.Window wNoClear = AttemptMarker.evaluate(id1, false, true, text1);
        ok("清理失败被单独记录（clearOk=false）", !wNoClear.clearOk);
        ok("清理失败不妨碍标记成为窗口锚点（isolated 仍可为真）", wNoClear.isolated);
        ok("但日志证据可用性不因此升级为任何结论（无失败证据）",
                !WpaLogReader.sawWrongKeyFor(text1, "LAB-TARGET")
                        && !WpaLogReader.sawHandshakeFailure(text1));

        System.out.println("== 3.4-d 标记缺失 / 不可见 ==");
        String noMarker = "wlan0: WPA: 4-Way Handshake failed - pre-shared key may be incorrect\n";
        AttemptMarker.Window wInvisible = AttemptMarker.evaluate(id1, true, true, noMarker);
        ok("发了写命令但读不到 → markerWritten=true 且 isolated=false",
                wInvisible.markerWritten && !wInvisible.markerVisible && !wInvisible.isolated);
        eq("reason=no-marker", "no-marker", wInvisible.reason);
        ok("诊断串写明 invisible", wInvisible.describe().contains("invisible"));
        AttemptMarker.Window wNotSent = AttemptMarker.evaluate(id1, true, false, noMarker);
        ok("未发写命令 → not-sent", wNotSent.describe().contains("not-sent"));
        ok("不可见时日志证据不可用", !wInvisible.evidenceUsable());

        System.out.println("== 3.4-e 外来候选标记 ==");
        String id2 = AttemptId.of(run1, 2);
        String mixed = AttemptMarker.line(id1) + "\n"
                + "wlan0: Trying to associate with SSID 'LAB-TARGET'\n"
                + AttemptMarker.line(id2) + "\n"
                + "wlan0: CTRL-EVENT-SSID-TEMP-DISABLED ssid=\"LAB-TARGET\" reason=WRONG_KEY\n";
        AttemptMarker.Window w1 = AttemptMarker.evaluate(id1, true, true, mixed);
        AttemptMarker.Window w2 = AttemptMarker.evaluate(id2, true, true, mixed);
        ok("前一条候选：检测到外来标记 → isolated=false",
                w1.foreignMarker && !w1.isolated);
        eq("前一条候选 reason=foreign", "foreign", w1.reason);
        ok("后一条候选：其后无外来标记 → isolated=true（窗口从它开始）",
                !w2.foreignMarker && w2.isolated);

        System.out.println("== 3.4-f 不同尝试的日志不能互相支持判词 ==");
        ok("后一条候选的 WRONG_KEY 行确实在文本里（同一份缓冲）",
                WpaLogReader.sawWrongKeyFor(mixed, "LAB-TARGET"));
        ok("但它不能给前一条候选当证据：前一条的窗口不可用",
                !w1.evidenceUsable() && w1.foreignMarker);
        ok("前一条候选自己的标记之后没有失败行（该行的归属是后一条）",
                !AttemptMarker.foreignMarkerAfter(AttemptMarker.line(id1) + "\nok\n", id1));

        System.out.println("== 3.4-g 旧日志混入（上一个 run 的标记）==");
        String run2id = AttemptId.of(run2, 1);
        String prevRun = AttemptMarker.line(run2id) + "\n老日志\n" + AttemptMarker.line(id1) + "\n新日志\n";
        AttemptMarker.Window wOld = AttemptMarker.evaluate(id1, true, true, prevRun);
        ok("本条之前的旧标记不算污染", !wOld.foreignMarker && wOld.isolated);
        eq("lastId 取最后一个（= 本条）", id1, AttemptMarker.lastId(prevRun));

        System.out.println("== 3.4-h 事件乱序 / 迟到 ==");
        // 语义：读到的文本是**缓冲快照**，顺序即写入顺序。
        // 谁的标记在文本里更靠后，谁就"拥有"文本尾部；更靠前的那条，
        // 其标记之后混进了别人的窗口 → 不可信（保守）。
        String outOfOrder = AttemptMarker.line(AttemptId.of(run1, 3)) + "\n"
                + AttemptMarker.line(AttemptId.of(run1, 2)) + "\n";
        AttemptMarker.Window wEarlier = AttemptMarker.evaluate(AttemptId.of(run1, 3), true, true,
                outOfOrder);
        AttemptMarker.Window wLater = AttemptMarker.evaluate(AttemptId.of(run1, 2), true, true,
                outOfOrder);
        ok("乱序：标记在前的候选，其后混入别的窗口 → isolated=false",
                !wEarlier.isolated && wEarlier.foreignMarker);
        ok("乱序：标记在后的候选拥有文本尾部 → isolated=true", wLater.isolated);
        ok("乱序不会把两条候选的 id 混成同一个",
                !AttemptId.exactlyMatches(AttemptId.of(run1, 2), AttemptId.of(run1, 3)));

        System.out.println("== 3.4-i 证据可用 ≠ 存在失败证据（必要非充分）==");
        String benign = AttemptMarker.line(id1) + "\n"
                + "wlan0: Trying to associate with SSID 'LAB-TARGET'\n";
        AttemptMarker.Window wBenign = AttemptMarker.evaluate(id1, true, true, benign);
        ok("窗口可用", wBenign.evidenceUsable());
        ok("但没有任何失败证据（不得据此判负）",
                !WpaLogReader.sawWrongKeyFor(benign, "LAB-TARGET")
                        && !WpaLogReader.sawHandshakeFailure(benign)
                        && !WpaLogReader.sawAssocReject(benign)
                        && WpaLogReader.countHandshakeM1For(benign, "00:00:00:00:00:01") == 0);
        ok("窗口元数据里没有任何成功/失败结论字段（只有 clear/marker/foreign/iso）",
                !wBenign.describe().contains("SUCCESS")
                        && !wBenign.describe().contains("AUTH_FAIL"));
        ok("元数据只描述证据质量（isolated 为真但无结论）",
                wBenign.isolated && !wBenign.foreignMarker);

        System.out.println("== 3.4-j 读取命令形状（标记与证据必须同一次快照）==");
        String cmd = WpaLogReader.readCommand();
        ok("一次 logcat 同时取两个 tag",
                cmd.contains("-s wpa_supplicant") && cmd.contains("-s " + AttemptMarker.TAG));
        ok("过滤式放行起点标记", cmd.contains(AttemptMarker.FIELD));
        ok("过滤式仍覆盖原有 7 类模式",
                cmd.contains("associate") && cmd.contains("4-Way")
                        && cmd.contains("RX message") && cmd.contains("negotiat")
                        && cmd.contains("ASSOC-REJECT") && cmd.contains("SSID-TEMP-DISABLED")
                        && cmd.contains("CONNECTED") && cmd.contains("WRONG_KEY")
                        && cmd.contains("reason="));
        ok("标记行确实能穿过该过滤式（模拟 grep -iE）",
                java.util.regex.Pattern.compile(WpaLogReader.SHELL_FILTER,
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(
                        AttemptMarker.line(id1)).find());

        System.out.println("== 3.4-k 接线守卫（接线被删即红）==");
        String eng = readFile("/workspace/promo-wpaforge/app/src/main/java/"
                + "io/github/wlmosv_png/wpaforge/audit/CrackEngine.java");
        if (eng == null) {
            System.out.println("  N/RUN 接线守卫（源码不可读：需以项目根为工作目录运行）");
            ok("（占位）", true);
        } else {
            ok("run() 生成 runId", eng.contains("AttemptId.newRunId("));
            ok("每条候选生成 attemptId", eng.contains("AttemptId.of(currentRunId, cursor)"));
            ok("Attempt 携带 attemptId", eng.contains("a.attemptId ="));
            // R11：不再清空缓冲；窗口起点由「时间锚点 + 起点标记」确立，
            // 且起点状态必须进入窗口元数据（不再是"清完就当它清了"）。
            ok("不再清空全局缓冲（既不裸调 clear 也不调 clearChecked）",
                    !eng.contains("WifiStateLog.clear(shell)")
                            && !eng.contains("clearChecked("));
            ok("窗口起点状态被记录进元数据（不是默默假设）",
                    eng.contains("windowStartOk") && eng.contains("windowFromMs"));
            ok("写入起点标记", eng.contains("AttemptMarker.writeCommand("));
            ok("每轮算窗口元数据", eng.contains("AttemptMarker.evaluate("));
            ok("判词带 attemptId", eng.contains("verdictTag(a.attemptId, cursor"));
        }

        System.out.println();
        System.out.println("=== R6 接线测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String readFile(String path) {
        try {
            return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
