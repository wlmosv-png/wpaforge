package io.github.wlmosv_png.wpaforge.audit;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 判定链**重载绕行**静态门禁（2026-11-21 专项审计新增）。
 *
 * <h3>它防什么</h3>
 * {@code SuccessRule} 有两个 {@code assess} 重载：
 * <ul>
 *   <li><b>6 参</b>：把 {@code gateConfirmedClean} 硬编码为 {@code true}，
 *       等于**假设**「前置闸门已确认旧配置删净」。语义宽松，是 1.9.60 契约。</li>
 *   <li><b>7 参</b>：由调用方传真实闸门结论。</li>
 * </ul>
 *
 * <p>P0-1 修复的全部价值都建立在「闸门结论是实测出来的」这一点上。
 * 若生产代码误用 6 参版本，等于把闸门又变回「永远认为干净」——
 * {@code list-networks} 返回错误文本也会被判 SUCCESS，假成功复活。
 *
 * <p>本门禁通过**静态扫描源码**钉死这条约束。它是纯文本检查，
 * 不执行生产逻辑 —— 所以它只证明「调用点形状正确」，
 * 不能替代行为测试（行为面由 {@code SuccessRuleFalsePositiveTest} 覆盖）。
 *
 * <p>纯离线，无 Android 依赖。
 */
public final class VerdictOverloadGuardTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void ok(String name, boolean cond) { ok(name, cond, ""); }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static String src(String rel) {
        try {
            File f = new File(io.github.wlmosv_png.wpaforge.TestPaths.javaPkgDir() + rel);
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    public static void main(String[] args) {
        System.out.println("== 判定链重载绕行门禁 ==");
        try {
            String eng = src("audit/CrackEngine.java");
            String sr = src("audit/SuccessRule.java");
            String svc = src("CrackService.java");

            ok("CrackEngine.java 可读", !eng.isEmpty());
            ok("SuccessRule.java 可读", !sr.isEmpty());

            // ------------------------------------------------ 1. 6 参必须标注 @Deprecated
            System.out.println();
            System.out.println("-- 1. 宽松 6 参重载必须标注 @Deprecated --");
            ok("★ SuccessRule 6 参 assess 带 @Deprecated",
                    sr.contains("@Deprecated") , "未找到 @Deprecated");

            // ------------------------------------------------ 2. 生产调用点必须是 7 参
            System.out.println();
            System.out.println("-- 2. 生产调用点必须传闸门结论（7 参）--");
            ok("★ CrackEngine 调用 assess 时传 configConfirmedAbsent",
                    eng.contains("SuccessRule.assess(target.ssid, info.ssid,")
                            && eng.contains("configConfirmedAbsent)"),
                    "未找到 7 参调用");

            // 关键：不能出现「第 7 参是字面 true」的调用
            boolean literalTrue = eng.contains("targetNetId, true)")
                    || svc.contains("targetNetId, true)");
            ok("★ 生产代码不得把 gateConfirmedClean 硬编码成 true",
                    !literalTrue, "发现字面 true 传参");

            // ------------------------------------------------ 3. 闸门事实必须来自实测
            System.out.println();
            System.out.println("-- 3. 闸门事实必须由三态探测得出 --");
            ok("★ CrackEngine 使用 netIdOfTargetState（三态）",
                    eng.contains("netIdOfTargetState("), "未使用三态探测");
            ok("★ 存在 configConfirmedAbsent 变量（实测结论）",
                    eng.contains("configConfirmedAbsent"), "未找到该变量");
            ok("★ 存在 Probe 三态类型",
                    eng.contains("static final class Probe"), "未找到 Probe");

            // ------------------------------------------------ 4. 三态常量必须存在
            System.out.println();
            System.out.println("-- 4. NETID_UNREADABLE 必须存在且为负 --");
            String lnp = src("audit/ListNetworksParser.java");
            ok("★ ListNetworksParser 定义 NETID_UNREADABLE",
                    lnp.contains("NETID_UNREADABLE"), "缺失");
            ok("★ NETID_UNREADABLE 不等于 -1（与「确认没有」区分）",
                    lnp.contains("NETID_UNREADABLE = -2L"), "取值不是 -2");

            // ------------------------------------------------ 5. 成功出口不能被宽松档覆盖
            System.out.println();
            System.out.println("-- 5. 成功出口的唯一性 --");
            int assessCalls = countOccurrences(eng, "SuccessRule.assess(");
            info("CrackEngine 中 SuccessRule.assess 调用次数 = " + assessCalls);
            ok("CrackEngine 只有 1 个成功判定入口调用",
                    assessCalls == 1, "实际 " + assessCalls + " 处");

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== 重载绕行门禁: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static int countOccurrences(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}
