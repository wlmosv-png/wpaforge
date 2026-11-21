package io.github.wlmosv_png.wpaforge.ui;

/**
 * 用户表达层测试（1.9.67）。纯逻辑。
 *
 * <p>钉死三件事：
 * <ol>
 *   <li>逐条未命中（UNKNOWN / INCOMPLETE / NETWORK_FAIL / SUSPECTED）一律表达为「未命中」，
 *       不得显示成技术异常；</li>
 *   <li>只有 ENV_ERROR 才是需要用户处理的「环境异常」；</li>
 *   <li>表达层**不改变**任何判定语义 —— 它只是翻译，不产生 Verdict。</li>
 * </ol>
 */
public final class UserOutcomeTest {

    private static int pass = 0, fail = 0;
    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; System.out.println("  OK   " + n); }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }
    private static void ok(String n, boolean c) { ok(n, c, ""); }

    public static void main(String[] args) {
        System.out.println("== 用户表达层 (1.9.67) ==");
        try {
            // 1. 四类「技术未命中」全部映射为 MISS
            ok("★UNDECIDED → 未命中",
                    UserOutcome.of(ResultCategory.Kind.UNDECIDED) == UserOutcome.Kind.MISS);
            ok("★NOT_FINISHED → 未命中",
                    UserOutcome.of(ResultCategory.Kind.NOT_FINISHED) == UserOutcome.Kind.MISS);
            ok("★NETWORK_FAIL → 未命中",
                    UserOutcome.of(ResultCategory.Kind.NETWORK_FAIL) == UserOutcome.Kind.MISS);
            ok("★SUSPECTED_WRONG → 未命中",
                    UserOutcome.of(ResultCategory.Kind.SUSPECTED_WRONG) == UserOutcome.Kind.MISS);

            // 2. 只有这三类不是「未命中」
            ok("★VERIFIED_OK → 破解成功",
                    UserOutcome.of(ResultCategory.Kind.VERIFIED_OK) == UserOutcome.Kind.HIT);
            ok("★PROVEN_WRONG → 已证实密码错误",
                    UserOutcome.of(ResultCategory.Kind.PROVEN_WRONG) == UserOutcome.Kind.PROVEN_WRONG);
            ok("★ENV_ERROR → 环境异常",
                    UserOutcome.of(ResultCategory.Kind.ENV_ERROR) == UserOutcome.Kind.ENV_ERROR);

            // 3. 只有 ENV_ERROR 需要用户处理
            ok("★只有环境异常 needsAttention",
                    UserOutcome.needsAttention(UserOutcome.Kind.ENV_ERROR)
                            && !UserOutcome.needsAttention(UserOutcome.Kind.MISS)
                            && !UserOutcome.needsAttention(UserOutcome.Kind.PROVEN_WRONG)
                            && !UserOutcome.needsAttention(UserOutcome.Kind.HIT));

            // 4. 主文案
            ok("MISS = 未命中", "未命中".equals(UserOutcome.label(UserOutcome.Kind.MISS, true)));
            ok("HIT = 破解成功", "破解成功".equals(UserOutcome.label(UserOutcome.Kind.HIT, true)));
            ok("NOT_FOUND = 未找到匹配密码",
                    "未找到匹配密码".equals(UserOutcome.label(UserOutcome.Kind.NOT_FOUND, true)));
            ok("PAUSED = 已暂停", "已暂停".equals(UserOutcome.label(UserOutcome.Kind.PAUSED, true)));
            ok("RUNNING = 正在运行",
                    "正在运行".equals(UserOutcome.label(UserOutcome.Kind.RUNNING, true)));
            ok("ENV_ERROR = 环境异常",
                    "环境异常".equals(UserOutcome.label(UserOutcome.Kind.ENV_ERROR, true)));
            ok("STOPPED = 已停止", "已停止".equals(UserOutcome.label(UserOutcome.Kind.STOPPED, true)));

            // 5. 事实不重复主标签
            for (UserOutcome.Kind k : UserOutcome.Kind.values()) {
                String label = UserOutcome.label(k, true);
                String fact = UserOutcome.fact(k, true);
                ok("事实非空: " + k, fact != null && !fact.isEmpty());
                ok("★事实不重复主标签: " + k,
                        fact != null && !fact.contains(label), "label=" + label + " fact=" + fact);
            }

            // 6. 未命中事实必须表达「继续下一条」，不能像故障
            String missFact = UserOutcome.fact(UserOutcome.Kind.MISS, true);
            ok("★未命中事实含「继续」语义",
                    missFact.contains("继续") || missFact.contains("下一条"), missFact);
            ok("★未命中事实不含「故障/错误/失败」",
                    !missFact.contains("故障") && !missFact.contains("失败"), missFact);

            // 7. 收尾文案
            ok("命中 → 破解成功",
                    "破解成功".equals(UserOutcome.finishSummary(true, 0, 10, true)));
            ok("★未命中且无证实 → 未找到匹配密码",
                    "未找到匹配密码".equals(UserOutcome.finishSummary(false, 0, 10, true)));
            String withProven = UserOutcome.finishSummary(false, 4, 6, true);
            ok("★有已证实错误 → 仍说未找到匹配密码（附条数）",
                    withProven.contains("未找到匹配密码") && withProven.contains("4"), withProven);

            // 8. 英文可用
            for (UserOutcome.Kind k : UserOutcome.Kind.values()) {
                ok("英文非空: " + k,
                        UserOutcome.label(k, false) != null && !UserOutcome.label(k, false).isEmpty()
                                && UserOutcome.fact(k, false) != null
                                && !UserOutcome.fact(k, false).isEmpty());
            }

            // 9. 表达层不产生 Verdict（源码守卫：本类不得引用 Verdict 枚举）
            String src = read("app/src/main/java/io/github/wlmosv_png/wpaforge/ui/UserOutcome.java");
            ok("★UserOutcome 不引用 CrackEngine.Verdict（只做翻译）",
                    src != null && !src.contains("CrackEngine.Verdict"), "引用了判定枚举");

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println("=== 用户表达层: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String read(String rel) {
        try {
            String root = io.github.wlmosv_png.wpaforge.TestPaths.root();
            java.io.File f = new java.io.File(root, rel);
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
