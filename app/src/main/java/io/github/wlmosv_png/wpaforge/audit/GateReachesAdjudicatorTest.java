package io.github.wlmosv_png.wpaforge.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import io.github.wlmosv_png.wpaforge.TestPaths;

/**
 * D4 守卫：**前置闸门必须真的进裁决器**。
 *
 * <h3>为什么需要可执行形状断言</h3>
 * 修复前 {@code CrackEngine.tryOneShell} 在四种闸门失败时直接
 * {@code new Attempt(password, Verdict.UNKNOWN, ...)}，从不构造
 * {@link AttemptState}。矩阵测试把 4 种 Gate 全部跑了一遍，
 * 但生产代码里 {@code Gate} 只出现过 {@code OK} ——
 * <b>测试覆盖的是一个不存在的输入维度</b>。
 *
 * <p>本类按**源码形状**守这条：生产源码里必须存在
 * {@code Gate.STALE_CONFIG} / {@code Gate.UNREADABLE} /
 * {@code Gate.DISCONNECT_FAILED} 的真实构造点，且闸门失败不得再用
 * 「直造 UNKNOWN + 不可信文案」的旧形状。
 */
public final class GateReachesAdjudicatorTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    /**
     * 被测源码根一律经 {@code TestPaths} 解析 —— 与本项目其它源码守卫测试同口径。
     * （硬编码绝对路径会被 GateRootTest 的「源码中无旧工作副本路径」门禁判红。）
     */
    private static String read(String pkgRelative) throws IOException {
        String root = TestPaths.javaSrcRoot();
        if (root == null || root.isEmpty()) throw new IOException("源码根不可用");
        return new String(Files.readAllBytes(Paths.get(root + pkgRelative)),
                StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== D4 闸门可达性守卫 ===");

        String src = read("io/github/wlmosv_png/wpaforge/audit/CrackEngine.java");

        // ① 三种失败闸门在生产里必须真实出现
        for (String g : new String[]{"Gate.STALE_CONFIG", "Gate.UNREADABLE",
                "Gate.DISCONNECT_FAILED"}) {
            ok("生产源码构造 " + g, src.contains(g), "未找到 " + g);
        }

        // ② 闸门失败必须经统一出口
        int blocked = count(src, "Adjudicator.gateBlocked(");
        ok("闸门失败经 Adjudicator.gateBlocked 出口（>=4 处）", blocked >= 4,
                "实际 " + blocked + " 处");

        // ③ 旧形状不得回流：直造 UNKNOWN + 「不可信」在同一 return 里
        String[] oldShapes = {
                "new Attempt(password, Verdict.UNKNOWN,\n                    clock().uptimeMillis() - t0,\n                    L.s(\"无法清除旧配置",
                "new Attempt(password, Verdict.UNKNOWN,\n                    clock().uptimeMillis() - t0,\n                    L.s(\"旧配置未清除",
                "new Attempt(password, Verdict.UNKNOWN,\n                        clock().uptimeMillis() - t0,\n                        L.s(\"上一条的连接未能断开",
        };
        int leftovers = 0;
        for (String o : oldShapes) if (src.contains(o)) leftovers++;
        ok("旧「直造 UNKNOWN」形状已清除", leftovers == 0, "残留 " + leftovers + " 处");

        // ④ classifyDeadline 必须只有一处 verdict 构造（走 Adjudicator）
        ok("classifyDeadline 调用 Adjudicator.adjudicate",
                src.contains("Adjudicator.Result r = Adjudicator.adjudicate(st,"),
                "未找到裁决调用");
        ok("classifyDeadline 六参重载存在",
                src.contains("boolean statusShowsTarget,\n                                                   Lifecycle lifecycle)"),
                "未找到六参重载");

        // ⑤ 二次覆盖必须消失
        ok("循环末不再二次覆盖 verdict 为 INCOMPLETE",
                !src.contains("boolean lifecycleActiveAtEnd"), "旧覆盖逻辑残留");

        // ⑥ 任何 Gate 失败路径都不得给出可持久化等级
        int gateOkInJudge = count(src, "AttemptState.Gate.OK,");
        ok("judge() 调用点仍传 OK（闸门已通过的循环内路径）", gateOkInJudge >= 7,
                "实际 " + gateOkInJudge);

        // ---- 行为：闸门失败产物一律 NONE / 不推进游标 ----
        for (AttemptState.Gate g : new AttemptState.Gate[]{
                AttemptState.Gate.STALE_CONFIG,
                AttemptState.Gate.UNREADABLE,
                AttemptState.Gate.DISCONNECT_FAILED}) {
            CrackEngine.Attempt a = Adjudicator.gateBlocked("pw", "r#1", g, 10L, "d");
            ok("闸门 " + g + " -> UNKNOWN/NONE",
                    a.verdict == CrackEngine.Verdict.UNKNOWN
                            && a.grade == EvidenceGrade.Level.NONE
                            && a.reason.startsWith("gate-"),
                    a.verdict + "/" + a.grade + "/" + a.reason);
        }

        System.out.println();
        System.out.println("=== 闸门守卫: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static int count(String s, String needle) {
        int n = 0, i = 0;
        while (true) {
            int k = s.indexOf(needle, i);
            if (k < 0) return n;
            n++; i = k + needle.length();
        }
    }
}
