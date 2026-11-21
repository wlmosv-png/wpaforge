package io.github.wlmosv_png.wpaforge.ui;

/**
 * 1.9.68 破解页视觉与统计口径契约测试。纯逻辑。
 *
 * <p>钉死本轮三件事：
 * <ol>
 *   <li>统计口径：**未命中** = 连接未完成 + 疑似 + 网络级 + 无法判断（合并展示，口径不变）；</li>
 *   <li>状态图标：每个 Phase 都有 Canvas 图标，不再依赖 Unicode 字符；</li>
 *   <li>用户文案：未命中一律「未命中」，环境异常单独存在。</li>
 * </ol>
 */
public final class CrackStatsUnifyTest {

    private static int pass = 0, fail = 0;
    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; System.out.println("  OK   " + n); }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }
    private static void ok(String n, boolean c) { ok(n, c, ""); }

    public static void main(String[] args) {
        System.out.println("== 1.9.68 统计口径与状态组件契约 ==");
        try {
            // ---- 1. 未命中合并口径 ----
            ok("★missTotal = 未完成+疑似+网络级+无法判断",
                    CrackStatsModel.missTotal(3, 4, 2, 1) == 10);
            ok("全零 → 0", CrackStatsModel.missTotal(0, 0, 0, 0) == 0);

            // ---- 2. 四格标签 ----
            ok("★第三格 = 未命中",
                    "未命中".equals(CrackStatsModel.coreLabel(CrackStatsModel.Core.MISS, true)));
            ok("第一格 = 已确认成功",
                    "已确认成功".equals(
                            CrackStatsModel.coreLabel(CrackStatsModel.Core.VERIFIED_OK, true)));
            ok("第二格 = 已证实错误",
                    "已证实错误".equals(
                            CrackStatsModel.coreLabel(CrackStatsModel.Core.PROVEN_WRONG, true)));
            ok("★第四格 = 环境异常（单列）",
                    "环境异常".equals(
                            CrackStatsModel.coreLabel(CrackStatsModel.Core.ENV_ERROR, true)));

            // ---- 3. 恒等式：成功+已证实+未命中+环境异常 == 总数 ----
            ok("★四项之和 == 总数", CrackStatsModel.consistent4(1, 2, 3, 4, 10));
            ok("不一致 → false", !CrackStatsModel.consistent4(1, 2, 3, 4, 11));

            // ---- 4. 不再有「连接未完成」这个统计标签 ----
            boolean hasNotFinishedLabel = false;
            for (CrackStatsModel.Core c : CrackStatsModel.Core.values()) {
                if ("连接未完成".equals(CrackStatsModel.coreLabel(c, true))) hasNotFinishedLabel = true;
            }
            ok("★统计区不再出现「连接未完成」", !hasNotFinishedLabel);

            // ---- 5. 状态图标：每个 Phase 都有 Canvas 图标 ----
            for (CrackPhaseView.Phase p : CrackPhaseView.Phase.values()) {
                ok("状态有图标: " + p, p.icon != null);
            }
            ok("★RUNNING 用 PLAY 图标",
                    CrackPhaseView.Phase.RUNNING.icon == StatusIcons.Kind.PLAY);
            ok("PAUSED 用 PAUSE", CrackPhaseView.Phase.PAUSED.icon == StatusIcons.Kind.PAUSE);
            ok("VERIFIED 用 CHECK", CrackPhaseView.Phase.VERIFIED.icon == StatusIcons.Kind.CHECK);
            ok("PROVEN_WRONG 用 CROSS",
                    CrackPhaseView.Phase.PROVEN_WRONG.icon == StatusIcons.Kind.CROSS);
            ok("ENV_ERROR 用 WARN", CrackPhaseView.Phase.ENV_ERROR.icon == StatusIcons.Kind.WARN);
            ok("★未命中用 DOT（中性，不抢眼）",
                    CrackPhaseView.Phase.UNCERTAIN.icon == StatusIcons.Kind.DOT
                            && CrackPhaseView.Phase.NETWORK_FAIL.icon == StatusIcons.Kind.DOT);

            // ---- 6. 状态文案：未命中统一 ----
            ok("★UNCERTAIN 文案 = 未命中", "未命中".equals(CrackPhaseView.Phase.UNCERTAIN.zh));
            ok("★NETWORK_FAIL 文案 = 未命中",
                    "未命中".equals(CrackPhaseView.Phase.NETWORK_FAIL.zh));
            ok("★ENV_ERROR 文案 = 环境异常",
                    "环境异常".equals(CrackPhaseView.Phase.ENV_ERROR.zh));

            // ---- 7. 用户表达层一致性 ----
            ok("★三类未命中都映射 MISS",
                    UserOutcome.of(ResultCategory.Kind.UNDECIDED) == UserOutcome.Kind.MISS
                            && UserOutcome.of(ResultCategory.Kind.NOT_FINISHED)
                                    == UserOutcome.Kind.MISS
                            && UserOutcome.of(ResultCategory.Kind.NETWORK_FAIL)
                                    == UserOutcome.Kind.MISS);
            ok("环境异常不并入未命中",
                    UserOutcome.of(ResultCategory.Kind.ENV_ERROR) == UserOutcome.Kind.ENV_ERROR);

            // ---- 8. 源码守卫：状态文字里不再带字符图标 ----
            String ma = read("app/src/main/java/io/github/wlmosv_png/wpaforge/MainActivity.java");
            // 主状态行必须是纯文字（图标交给 StatusIcons Canvas 自绘）；
            // 队列徽章 / 日志行属于另一套组件，不在本契约范围。
            boolean stateRowClean = ma != null
                    && ma.contains("case RUNNING:      return L.s(\"正在运行\", \"Running\");")
                    && !ma.contains("L.s(\"▶ 正在运行\"")
                    && !ma.contains("L.s(\"■ 已停止\"");
            ok("★主状态行文字不带字符图标", stateRowClean, "主状态行仍带字符图标");
            ok("★按钮文字不再带字符图标",
                    ma != null && ma.contains("L.s(\"开始破解\", \"Start\")")
                            && ma.contains("L.s(\"暂停\", \"Pause\")")
                            && ma.contains("L.s(\"停止\", \"Stop\")"));
            String si = read("app/src/main/java/io/github/wlmosv_png/wpaforge/ui/StatusIcons.java");
            ok("★StatusIcons 为 Canvas 自绘（不 extends TextView/不画字符）",
                    si != null && si.contains("extends Drawable") && si.contains("canvas.draw"));

            // ---- 9. 判定链未被触碰（只做展示） ----
            String uo = read("app/src/main/java/io/github/wlmosv_png/wpaforge/ui/UserOutcome.java");
            ok("★表达层不引用 Verdict 枚举",
                    uo != null && !uo.contains("CrackEngine.Verdict"));

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println("=== 1.9.68 契约: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String read(String rel) {
        try {
            String root = io.github.wlmosv_png.wpaforge.TestPaths.root();
            return new String(java.nio.file.Files.readAllBytes(
                    new java.io.File(root, rel).toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
