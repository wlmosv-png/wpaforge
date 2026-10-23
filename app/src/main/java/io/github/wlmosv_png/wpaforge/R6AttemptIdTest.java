package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AttemptId;
import io.github.wlmosv_png.wpaforge.audit.AttemptMarker;

/**
 * R6 契约测试：任务标识的生成 / 传递 / 失效 / 恢复，以及日志起点标记。
 *
 * <p>预期值全部来自 {@code doc/R6-ATTEMPT-ID.md} 的契约条文，
 * 不复制被测实现的判断条件来生成期望值。纯逻辑，无设备依赖，
 * 桌面 JVM 可跑（因此进正式门禁 {@code wpf-gate.sh}）。
 */
public class R6AttemptIdTest {

    private static int pass = 0, fail = 0;
    private static final String SSID = "LAB-TARGET";
    private static final long T0 = 1_764_000_000_000L;   // 固定墙钟，保证可复现

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
        System.out.println("== R6-C1 生成：序号 1 起、严格递增、不重复 ==");
        String run = AttemptId.newRunId(T0, SSID, 0);
        ok("runId 生成成功", run != null);
        ok("runId 形状合法", AttemptId.wellFormedRunId(run));
        String a1 = AttemptId.of(run, 1);
        String a2 = AttemptId.of(run, 2);
        String a11 = AttemptId.of(run, 11);
        ok("seq=1 可生成", a1 != null);
        eq("seqOf 往返", 11, AttemptId.seqOf(a11));
        ok("相邻序号不相等", !a1.equals(a2));
        ok("序号 1 起（0 被拒）", AttemptId.of(run, 0) == null);
        ok("序号负值被拒", AttemptId.of(run, -1) == null);
        ok("确定性：同输入同输出", a11.equals(AttemptId.of(run, 11)));

        System.out.println("== R6-C2 解析与非法输入（不抛异常）==");
        eq("runOf", run, AttemptId.runOf(a11));
        ok("wellFormed(合法)=true", AttemptId.wellFormed(a11));
        ok("wellFormed(null)=false", !AttemptId.wellFormed(null));
        ok("wellFormed(\"\")=false", !AttemptId.wellFormed(""));
        ok("没有 # → 非法", !AttemptId.wellFormed(run));
        ok("两个 # → 非法", !AttemptId.wellFormed(run + "#1#2"));
        ok("seq 非数字 → 非法", !AttemptId.wellFormed(run + "#abc"));
        ok("runId 段数不对 → 非法", !AttemptId.wellFormed("abc#1"));
        ok("SSID 哈希段长度不对 → 非法", !AttemptId.wellFormed("m5k3p1-3f9a2b7-1#1"));
        eq("非法输入 seqOf 返回 -1（不是 0）", -1, AttemptId.seqOf("garbage"));
        eq("runOf(非法) 返回 null", null, AttemptId.runOf("garbage"));
        ok("超长输入被拒", !AttemptId.wellFormed(repeat("x", AttemptId.MAX_LEN + 1) + "#1"));
        ok("MAX_LEN 内合法", AttemptId.wellFormed(AttemptId.of(run, 123456789)));

        System.out.println("== R6-C3 失效：跨 run 永不相等 ==");
        String run2 = AttemptId.newRunId(T0 + 60_000L, SSID, 0);
        ok("不同时刻 → 不同 runId", run2 != null && !run.equals(run2));
        String b1 = AttemptId.of(run2, 1);
        ok("同 seq、不同 run → exactlyMatches=false", !AttemptId.exactlyMatches(a1, b1));
        ok("同 seq、不同 run → sameRun=false", !AttemptId.sameRun(a1, b1));
        ok("同 run 内 → sameRun=true", AttemptId.sameRun(a1, a11));
        ok("同一条 → exactlyMatches=true", AttemptId.exactlyMatches(a11, AttemptId.of(run, 11)));
        ok("非法 vs 合法 → exactlyMatches=false", !AttemptId.exactlyMatches("garbage", a1));

        System.out.println("== R6-C4 误用禁令：attemptId 不是归属的充分证明 ==");
        ok("attributionProvenByAttemptIdAlone() 恒为 false",
                !AttemptId.attributionProvenByAttemptIdAlone());
        ok("「记为同一条」不推出「连接归属成立」",
                AttemptId.exactlyMatches(a11, a11)
                        && !AttemptId.attributionProvenByAttemptIdAlone());

        System.out.println("== R6-C5 恢复：绝不复用旧 runId、血缘保留、游标沿用 ==");
        AttemptId.ResumeTicket t = AttemptId.adoptForResume(run, 39, T0 + 3_600_000L, SSID, 1);
        ok("新 runId 生成成功", t.newRunId != null);
        ok("新 runId ≠ 旧 runId（不复用）", !t.newRunId.equals(run));
        eq("血缘保留", run, t.previousRunId);
        eq("游标沿用", 39, t.resumeFromOffset);
        ok("旧 run 的候选不属于新 run",
                !AttemptId.sameRun(AttemptId.of(run, 39), AttemptId.of(t.newRunId, 39)));
        AttemptId.ResumeTicket first = AttemptId.adoptForResume(null, -5, T0, SSID, 0);
        eq("首次启动 previousRunId=null", null, first.previousRunId);
        eq("负游标归 0", 0, first.resumeFromOffset);
        AttemptId.ResumeTicket sameSec = AttemptId.adoptForResume(run, 0, T0, SSID, 0);
        ok("同秒同序号的极端情形也不复用（兜底 +1）",
                sameSec.newRunId != null && !sameSec.newRunId.equals(run));

        System.out.println("== R6-C6 不泄密：ID 内不含 SSID 明文与口令 ==");
        String pw = "sample-pw";
        ok("runId 不含 SSID 明文", !run.contains(SSID));
        ok("attemptId 不含 SSID 明文", !a11.contains(SSID));
        ok("attemptId 不含口令明文", !a11.contains(pw));
        ok("长度有界", a11.length() <= AttemptId.MAX_LEN && run.length() <= AttemptId.MAX_LEN);
        eq("SSID 哈希稳定（同 SSID 同哈希）",
                AttemptId.newRunId(T0, SSID, 0), AttemptId.newRunId(T0, SSID, 0));
        ok("不同 SSID → 不同哈希段",
                !AttemptId.newRunId(T0, "OTHER-AP", 0).equals(run));

        System.out.println("== R6-C7 日志起点标记：构造与命令 ==");
        String mk = AttemptMarker.line(a11);
        eq("标记行形状", "WPF_PROBE|attempt=" + a11, mk);
        eq("非法 id 不给标记行", null, AttemptMarker.line("garbage"));
        ok("写标记命令包含 tag 与 id",
                AttemptMarker.writeCommand(a11) != null
                        && AttemptMarker.writeCommand(a11).contains(AttemptMarker.TAG)
                        && AttemptMarker.writeCommand(a11).contains(a11));
        eq("非法 id 不给命令", null, AttemptMarker.writeCommand(null));

        System.out.println("== R6-C8 隔离判定：缺标记 / 有外来标记 / 顺序正确 ==");
        String onlyMine = mk + "\n"
                + "wlan0: Trying to associate with SSID 'LAB-TARGET'\n"
                + "wlan0: WPA: 4-Way Handshake failed - pre-shared key may be incorrect\n";
        ok("有本条标记 → present", AttemptMarker.present(onlyMine, a11));
        ok("有本条标记且无外来 → isolated", AttemptMarker.isolated(onlyMine, a11));

        String missing = "wlan0: WPA: 4-Way Handshake failed - pre-shared key may be incorrect\n";
        ok("缺本条标记 → present=false", !AttemptMarker.present(missing, a11));
        ok("缺本条标记 → isolated=false（正是 S3-1/S3-2 的修法）",
                !AttemptMarker.isolated(missing, a11));

        String foreign = mk + "\n外星日志\n" + AttemptMarker.line(a2) + "\n"
                + "wlan0: WPA: 4-Way Handshake failed\n";
        ok("本条之后有别的候选标记 → isolated=false",
                !AttemptMarker.isolated(foreign, a11));
        ok("外来标记检测为真（同 run 别的序号）", AttemptMarker.foreignMarkerAfter(foreign, a11));

        String prevRunOnly = AttemptMarker.line(b1) + "\n外星日志\n" + mk + "\n";
        ok("本条之前的标记不算污染（读到上一个窗口的尾巴不算越界）",
                AttemptMarker.isolated(prevRunOnly, a11));
        eq("lastId 取最后一个标记", a11, AttemptMarker.lastId(prevRunOnly));
        eq("没有标记时 lastId=null", null, AttemptMarker.lastId("只有普通日志\n"));
        eq("idIn 忽略形状非法的内容", null,
                AttemptMarker.idIn("WPF_PROBE|attempt=not-an-id"));

        System.out.println();
        System.out.println("=== R6 attemptId 契约测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
