package io.github.wlmosv_png.wpaforge.ui;

import java.util.List;

/**
 * 破解页统计区**布局/分类模型**测试（1.9.65 新增）。
 *
 * <p>覆盖任务书要求的可离线验证项：
 * <ul>
 *   <li>窄屏 2×2、宽屏 1×4 的网格列数切换；</li>
 *   <li>细分项固定顺序、零值隐藏；</li>
 *   <li>「未确定」核心三类之和 == inconclusive（分类计数与总数一致）；</li>
 *   <li>数字与标签分离（{@link CrackStatsModel.Item} 分别为 cat 与 count）；</li>
 *   <li>零值与非零值显示一致（零值不出现、非零值带正确数字）；</li>
 *   <li>渲染文案含标签不含「0」。</li>
 * </ul>
 */
public final class CrackStatsModelTest {

    private static int pass = 0, fail = 0;
    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; System.out.println("  OK   " + n); }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }
    private static void ok(String n, boolean c) { ok(n, c, ""); }

    public static void main(String[] args) {
        System.out.println("== 破解页统计区布局/分类模型 ==");
        try {
            // 1. 网格列数：窄屏 2，宽屏 4
            ok("★360dp → 2 列（2×2）", CrackStatsModel.gridColumns(360) == 2);
            ok("480dp → 2 列", CrackStatsModel.gridColumns(480) == 2);
            ok("599dp → 2 列", CrackStatsModel.gridColumns(599) == 2);
            ok("★600dp → 4 列（1×4）", CrackStatsModel.gridColumns(600) == 4);
            ok("★720dp → 4 列", CrackStatsModel.gridColumns(720) == 4);

            // 2. 网格行数
            ok("★360dp → 2 行", CrackStatsModel.gridRows(360) == 2);
            ok("★720dp → 1 行", CrackStatsModel.gridRows(720) == 1);

            // 3. 细分：固定顺序 + 零值隐藏
            List<CrackStatsModel.Item> b1 =
                    CrackStatsModel.breakdown(4, 0, 4, 0, 0);
            ok("★细分只含非零项（疑4 + 未完4 → 2 项）", b1.size() == 2, b1.toString());
            ok("顺序：疑似密码错误优先", b1.get(0).cat == CrackStatsModel.Cat.SUSPECTED_WRONG);
            ok("顺序：连接未完成次之", b1.get(1).cat == CrackStatsModel.Cat.NOT_FINISHED);
            ok("计数正确(4/4)", b1.get(0).count == 4 && b1.get(1).count == 4);

            // 4. 全零 → 空
            ok("全零 → 空列表", CrackStatsModel.breakdown(0, 0, 0, 0, 0).isEmpty());

            // 5. 五类都非零 → 顺序固定
            List<CrackStatsModel.Item> b2 = CrackStatsModel.breakdown(1, 2, 3, 4, 5);
            ok("★五类顺序固定", b2.size() == 5
                    && b2.get(0).cat == CrackStatsModel.Cat.SUSPECTED_WRONG
                    && b2.get(1).cat == CrackStatsModel.Cat.NETWORK_FAIL
                    && b2.get(2).cat == CrackStatsModel.Cat.NOT_FINISHED
                    && b2.get(3).cat == CrackStatsModel.Cat.ENV_ERROR
                    && b2.get(4).cat == CrackStatsModel.Cat.UNDECIDED, b2.toString());
            ok("五类计数逐一对应", b2.get(0).count == 1 && b2.get(1).count == 2
                    && b2.get(2).count == 3 && b2.get(3).count == 4 && b2.get(4).count == 5);

            // 6. 分类计数与总数一致：核心三类之和 == inconclusive
            ok("★网络+未完成+无法判断 == inconclusive",
                    CrackStatsModel.undeterminedConsistent(2, 3, 5, 10));
            ok("不一致时返回 false",
                    !CrackStatsModel.undeterminedConsistent(2, 3, 5, 11));

            // 7. 数字与标签分离（结构保证）：Item 的 cat 与 count 是两个独立字段
            CrackStatsModel.Item it = b2.get(0);
            ok("★标签与数字分离（cat 与 count 独立）",
                    it.cat != null && it.count == 1);

            // 8. 渲染：含标签、含数字、不含「0」噪声
            String r = CrackStatsModel.render(4, 0, 4, 0, 0);
            ok("★渲染含「疑似密码错误 4」", r.contains("疑似密码错误") && r.contains("4"), r);
            ok("渲染含「连接未完成 4」", r.contains("连接未完成"), r);
            ok("★零值类别不出现在渲染文本", !r.contains("网络认证失败") && !r.contains("环境异常"), r);
            ok("全零 → 渲染空串", CrackStatsModel.render(0, 0, 0, 0, 0).isEmpty());

            // 9. 四位数不裁剪（模型层：thousands 处理在 UI 层，这里验证计数原值）
            List<CrackStatsModel.Item> big = CrackStatsModel.breakdown(1234, 0, 0, 0, 0);
            ok("★四位数计数不被截断", big.size() == 1 && big.get(0).count == 1234);

            // 10. 标签非空（中英）
            for (CrackStatsModel.Cat c : CrackStatsModel.Cat.values()) {
                ok("标签非空: " + c,
                        !CrackStatsModel.labelZh(c).isEmpty() && !CrackStatsModel.labelEn(c).isEmpty());
            }

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println("=== 破解页统计区模型: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
