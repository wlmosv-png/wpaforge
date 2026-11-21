package io.github.wlmosv_png.wpaforge.ui;

import java.util.List;

/**
 * 破解页统计区**布局/分类模型**测试（1.9.66 重设计）。
 *
 * <p>校验：网格列数、四项核心统计、尚无有效判定细分、以及「统计总数与分类总数一致」。
 * 纯逻辑，无 Android 依赖。
 */
public final class CrackStatsModelTest {

    private static int pass = 0, fail = 0;
    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; System.out.println("  OK   " + n); }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }
    private static void ok(String n, boolean c) { ok(n, c, ""); }

    public static void main(String[] args) {
        System.out.println("== 破解页统计区模型 (1.9.66) ==");
        try {
            // 网格列数
            ok("★360dp → 2 列", CrackStatsModel.gridColumns(360) == 2);
            ok("599dp → 2 列", CrackStatsModel.gridColumns(599) == 2);
            ok("★600dp → 4 列", CrackStatsModel.gridColumns(600) == 4);
            ok("★360dp → 2 行", CrackStatsModel.gridRows(360) == 2);
            ok("720dp → 1 行", CrackStatsModel.gridRows(720) == 1);

            // 核心四项：标签非空
            for (CrackStatsModel.Core c : CrackStatsModel.Core.values()) {
                ok("核心标签非空: " + c,
                        !CrackStatsModel.coreLabel(c, true).isEmpty()
                                && !CrackStatsModel.coreLabel(c, false).isEmpty());
            }

            // 尚无有效判定 = 疑似+网络+无法判断+环境
            ok("★noValidVerdict 求和", CrackStatsModel.noValidVerdict(2, 3, 4, 1) == 10);

            // coreCounts 顺序
            int[] cc = CrackStatsModel.coreCounts(1, 2, 3, 4);
            ok("★coreCounts 顺序 [成功,证实,未完成,无有效判定]",
                    cc[0] == 1 && cc[1] == 2 && cc[2] == 3 && cc[3] == 4);

            // 一致性：hit+confirmed+notFinished+noVerdict == total
            ok("★四项之和 == total（一致）",
                    CrackStatsModel.consistent4(1, 2, 3, 4, 10));
            ok("★四项之和不等于 total（不一致判红）",
                    !CrackStatsModel.consistent4(1, 2, 3, 4, 11));

            // 细分：固定顺序 + 零值隐藏
            List<CrackStatsModel.Item> b = CrackStatsModel.breakdown(4, 0, 2, 0);
            ok("★细分只含非零项（疑4 + 无法判断2 → 2 项）", b.size() == 2, b.toString());
            ok("顺序：疑似优先", b.get(0).cat == CrackStatsModel.Cat.SUSPECTED_WRONG);
            ok("顺序：无法判断次之", b.get(1).cat == CrackStatsModel.Cat.UNDECIDED);
            ok("全零 → 空", CrackStatsModel.breakdown(0, 0, 0, 0).isEmpty());

            // 四类全非零顺序
            List<CrackStatsModel.Item> b2 = CrackStatsModel.breakdown(1, 2, 3, 4);
            ok("★四类顺序固定", b2.size() == 4
                    && b2.get(0).cat == CrackStatsModel.Cat.SUSPECTED_WRONG
                    && b2.get(1).cat == CrackStatsModel.Cat.NETWORK_FAIL
                    && b2.get(2).cat == CrackStatsModel.Cat.UNDECIDED
                    && b2.get(3).cat == CrackStatsModel.Cat.ENV_ERROR, b2.toString());

            // 分类计数与总数一致：细分之和 == noValidVerdict
            int noV = CrackStatsModel.noValidVerdict(1, 2, 3, 4);
            ok("★细分之和 == 尚无有效判定",
                    CrackStatsModel.breakdownTotal(b2) == noV, "" + noV);

            // 标签非空（四细分）
            for (CrackStatsModel.Cat c : CrackStatsModel.Cat.values()) {
                ok("细分标签非空: " + c,
                        !CrackStatsModel.catLabelZh(c).isEmpty()
                                && !CrackStatsModel.catLabelEn(c).isEmpty());
            }

            // 渲染文案
            String r = CrackStatsModel.renderBreakdown(4, 0, 2, 0);
            ok("★渲染含「疑似密码错误 4」", r.contains("疑似密码错误") && r.contains("4"), r);
            ok("零值类别不出现", !r.contains("网络认证失败") && !r.contains("环境异常"), r);
            ok("全零 → 空串", CrackStatsModel.renderBreakdown(0, 0, 0, 0).isEmpty());

            // 四位数不截断
            ok("★四位数计数不截断",
                    CrackStatsModel.breakdown(1234, 0, 0, 0).get(0).count == 1234);

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
