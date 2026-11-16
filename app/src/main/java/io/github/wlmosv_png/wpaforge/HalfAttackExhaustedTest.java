package io.github.wlmosv_png.wpaforge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 折半攻击「阶段 1 穷尽」的门禁（2026-11-15）。
 *
 * <h3>现场</h3>
 * 用户报：拆半跑完阶段 1（10000 个前 4 位）后点「继续」会**自动结束**，
 * 状态卡却仍显示「进行中」。代码根因（{@code MainActivity.runHalfAttack}）：
 *
 * <pre>
 *   if (pins.isEmpty()) { wpsLog("…仍未找到"); break; }        // 静默收工
 *   …
 *   if (wpsSession.halfPrefixHit) { halfPhase = 2; }          // 只有半接受才进阶段 2
 * </pre>
 *
 * 拆半的**前提**就是「AP 在前 4 位正确时给出半接受反馈」——没有它，阶段 2
 * 没有前缀可枚举，数学上就构造不出来。所以正确的产品行为不是"让它继续"，
 * 而是**把这件事讲清楚**：为什么停、还能做什么。
 *
 * <p>本条把三点钉住：① 穷尽时必须给出明确结论与后路；② 必须置会话语义标志；
 * ③ 状态卡必须优先显示该结论，不能在退出后仍写「进行中」。
 */
public class HalfAttackExhaustedTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    private static void ok(String name, boolean cond) {
        ok(name, cond, "");
    }

    public static void main(String[] a) {
        String main = read(TestPaths.under(
                "app/src/main/java/io/github/wlmosv_png/wpaforge/MainActivity.java"));
        String eng = read(TestPaths.under(
                "app/src/main/java/io/github/wlmosv_png/wpaforge/wps/WpsEngine.java"));
        ok("MainActivity 可读", main != null);
        ok("WpsEngine 可读", eng != null);
        if (main == null || eng == null) { System.exit(1); }

        String c = DictListUiTest.stripComments(main);
        String e = DictListUiTest.stripComments(eng);

        System.out.println("== 阶段 1 穷尽：必须给出结论与后路 ==");
        int i = c.indexOf("阶段 1 已试完全部 10000 个前 4 位，且");
        ok("有「未检测到半接受信号」的结论行", i > 0);
        ok("说明为什么阶段 2 无法继续", c.contains("阶段 2 无前缀可用，无法继续"));
        ok("给出后路①厂商算法/弱 PIN 表", c.contains("厂商算法 / 内置弱 PIN 表"));
        ok("给出后路②重试阶段 1", c.contains("重试阶段 1"));
        ok("给出后路③完整枚举及其不可行性判定", c.contains("完整枚举 10^7 次"));
        ok("置会话语义标志", c.contains("wpsSession.halfPhase1Exhausted = true;"));

        // 关键回归锁：这一支不得退回「只打一行日志就 break」
        String branch = c.substring(Math.max(0, i - 200), Math.min(c.length(), i + 2200));
        ok("穷尽分支里必须置标志后再 break",
                branch.indexOf("halfPhase1Exhausted = true") < branch.indexOf("break;"),
                "顺序不对或缺少标志");

        System.out.println("== 阶段 2 仍需 4 位前缀（不许凭空进入） ==");
        // 注意：源码里这行是**折行**写的（类名与 .stage2Pin 不在同一行），
        // 所以断言只能落在方法名上，不能要求整串连续。
        ok("阶段 2 通过 stage2Pin 按 4 位前缀展开", c.contains("stage2Pin("));
        ok("只有半接受命中才切阶段 2", c.contains("if (wpsSession.halfPrefixHit) {"));

        System.out.println("== 状态卡不许在退出后仍写「进行中」 ==");
        int ui = c.indexOf("s.halfPhase1Exhausted");
        ok("状态行优先显示该结论", ui > 0 && c.contains("阶段 1 已穷尽、未检出半接受"));
        ok("该判断排在退避倒计时之前",
                c.indexOf("s.halfPhase1Exhausted") < c.indexOf("wpsBackoff.waitText(remain, zh)"));

        System.out.println("== 标志位只做展示，不参与判定 ==");
        ok("Session 声明了该字段", e.contains("public boolean halfPhase1Exhausted;"));
        int first = e.indexOf("halfPhase1Exhausted");
        ok("WpsEngine 内除声明外不再引用它（不参与判据）",
                e.indexOf("halfPhase1Exhausted", first + 1) < 0);
        // 这条必须查**原始文本**：stripComments 会把 javadoc 一起剥掉。
        ok("文档写明不参与判定", eng.contains("不参与任何判定"));

        System.out.println();
        System.out.println("=== 折半阶段 1 穷尽: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return null;
        }
    }
}
