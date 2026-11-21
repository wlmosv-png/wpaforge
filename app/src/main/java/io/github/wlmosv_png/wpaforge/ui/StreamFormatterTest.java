package io.github.wlmosv_png.wpaforge.ui;

/**
 * 攻击流记录模型与格式化测试（1.9.66）。纯逻辑。
 *
 * <p>校验：① 主行含类别+序号+耗时且**不含**事实措辞（不重复）；② 合并保留
 * 首条序号并延伸末条序号（不丢独立尝试身份）；③ 不同 reason 不合并；
 * ④ 事实行与详情行各自只承担一件事；⑤ 时间范围仅在合并时出现。
 */
public final class StreamFormatterTest {

    private static int pass = 0, fail = 0;
    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; System.out.println("  OK   " + n); }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }
    private static void ok(String n, boolean c) { ok(n, c, ""); }

    private static StreamFormatter.Record rec(ResultCategory.Kind k, String reason, int idx,
                                              long ms, long at) {
        return new StreamFormatter.Record(k, reason, idx, ms, at,
                "run#1#" + idx, "UNKNOWN", "NONE", "status");
    }

    public static void main(String[] args) {
        System.out.println("== 攻击流记录模型 (1.9.66) ==");
        try {
            long t0 = 1000L * 60 * 60;   // 01:00 左右
            StreamFormatter.Record r = rec(ResultCategory.Kind.UNDECIDED, "no-evidence", 5, 180, t0);

            String main = StreamFormatter.mainLine(r, true);
            // 1.9.67：主行用**用户表达**（未命中），技术类别只在详情里。
            ok("★主行含用户表达（未命中）", main.contains("未命中"), main);
            ok("★主行含序号", main.contains("第 5 条"), main);
            ok("主行含耗时", main.contains("0.1s"), main);
            ok("★主行不含事实措辞（不重复）",
                    !main.contains("继续尝试") && !main.contains("下一条"), main);

            String fact = StreamFormatter.factLine(r, true);
            ok("事实行非空", !fact.isEmpty());
            ok("★事实行不含主行标签（不重复）", !fact.contains("未命中"), fact);
            ok("事实行以缩进开头", fact.startsWith("  "), fact);

            String detail = StreamFormatter.detailLine(r, true);
            ok("★详情含 attempt", detail.contains("attempt=run#1#5"), detail);
            ok("详情含 verdict/grade", detail.contains("verdict=UNKNOWN")
                    && detail.contains("grade=NONE"), detail);
            ok("详情含 reason/src", detail.contains("reason=no-evidence")
                    && detail.contains("src=status"), detail);

            // 序号范围
            ok("单条序号 = 第 5 条", "第 5 条".equals(StreamFormatter.candidateRange(5, 5, true)));
            ok("★合并序号 = 第 5–21 条",
                    "第 5–21 条".equals(StreamFormatter.candidateRange(5, 21, true)));

            // 合并：absorb 保首条、延伸末条、repeat 累加
            StreamFormatter.Record a = rec(ResultCategory.Kind.UNDECIDED, "no-evidence", 5, 180, t0);
            StreamFormatter.Record b = rec(ResultCategory.Kind.UNDECIDED, "no-evidence", 21, 220, t0 + 60000);
            a.absorb(b);
            ok("★合并后首条序号不变(5)", a.firstIdx == 5, "" + a.firstIdx);
            ok("★合并后末条序号延伸(21)", a.lastIdx == 21, "" + a.lastIdx);
            ok("★合并后 repeat=2", a.repeat == 2, "" + a.repeat);
            String merged = StreamFormatter.mainLine(a, true);
            ok("★合并主行含序号范围", merged.contains("第 5–21 条"), merged);
            ok("★合并主行含 ×2", merged.contains("×2"), merged);
            ok("★合并主行含时间范围", merged.contains("–"), merged);

            // 合并键：同类别不同 reason 不合并
            String k1 = StreamFormatter.mergeKey(ResultCategory.Kind.UNDECIDED, "no-evidence");
            String k2 = StreamFormatter.mergeKey(ResultCategory.Kind.UNDECIDED, "netid-conflict");
            // 1.9.67：普通未命中按用户语义合并（reason 不拆块），避免刷屏；
            // 只有环境异常才按 reason 分开（见下方用例）。
            ok("★普通未命中不同 reason 仍合并（防刷屏）", k1.equals(k2), k1 + " vs " + k2);
            String k3 = StreamFormatter.mergeKey(ResultCategory.Kind.UNDECIDED, "no-evidence");
            ok("同类别同 reason 键相同", k1.equals(k3));
            // 环境异常必须按 reason 分开（真正需要处理的问题不能并进别的错误）
            String e1 = StreamFormatter.mergeKey(ResultCategory.Kind.ENV_ERROR, "channel-no-response");
            String e2 = StreamFormatter.mergeKey(ResultCategory.Kind.ENV_ERROR, "gate-stale-config");
            ok("★环境异常不同 reason 键不同", !e1.equals(e2), e1 + " vs " + e2);

            // 合并键：不同类别不同
            ok("★不同用户类别键不同",
                    !StreamFormatter.mergeKey(ResultCategory.Kind.PROVEN_WRONG, "wrong-key")
                            .equals(StreamFormatter.mergeKey(ResultCategory.Kind.VERIFIED_OK,
                                    "wrong-key")));

            // 单条不显示 ×N
            StreamFormatter.Record single = rec(ResultCategory.Kind.PROVEN_WRONG, "wrong-key", 3, 4200, t0);
            String sm = StreamFormatter.mainLine(single, true);
            ok("★单条不显示 ×N", !sm.contains("×"), sm);
            ok("单条含 4.2s", sm.contains("4.2s"), sm);

            // 耗时格式化
            ok("fmtSeconds(120)=0.1s", "0.1s".equals(StreamFormatter.fmtSeconds(120)));
            ok("fmtSeconds(4200)=4.2s", "4.2s".equals(StreamFormatter.fmtSeconds(4200)));
            ok("fmtSeconds(12000)=12s", "12s".equals(StreamFormatter.fmtSeconds(12000)));
            ok("fmtSeconds(-1)=—", "—".equals(StreamFormatter.fmtSeconds(-1)));

            // 中文/英文都可渲染
            ok("英文主行非空", !StreamFormatter.mainLine(r, false).isEmpty());
            ok("英文事实行非空", !StreamFormatter.factLine(r, false).isEmpty());

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println("=== 攻击流记录模型: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
