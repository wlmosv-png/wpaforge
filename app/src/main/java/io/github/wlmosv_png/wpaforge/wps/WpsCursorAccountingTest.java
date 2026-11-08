package io.github.wlmosv_png.wpaforge.wps;

import java.util.ArrayList;
import java.util.List;

/**
 * 第二阶段专项：**游标记账**（暂停 / 预算耗尽 / 进程异常不得跳过候选）。
 *
 * <h3>要挡住的真实事故（第二阶段审计 P0-3）</h3>
 * 修复前 {@code halfCursor += pins.size()} 按**请求数**推进游标，而 helper
 * 会因为预算耗尽、暂停（进程被销毁）、异常提前结束，只返回真正跑过的几条。
 * 结果：没跑到的候选被记成已完成，并被写进断点，永久跳过 ——
 * 拆半「11000 次必中」的保证被静默破坏，而且没有任何提示。
 *
 * <p>本测试调用生产逻辑 {@link WpsBatchPlan} 与
 * {@link WpsCandidates}（候选生成），并做一次**端到端口径核对**：
 * 暂停后重新生成的那一批，必须仍然包含原来没跑到的那些候选。
 */
public final class WpsCursorAccountingTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /** 造一批候选（阶段 1，第 from..from+n-1 个）。 */
    private static List<String> batch(int from, int n) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < from + n; i++) {
            String p = WpsCandidates.stage1Pin(i);
            if (p != null) out.add(p);
        }
        return out;
    }

    /** 造 helper 结果：只对前 n 条给出结果（模拟提前结束）。 */
    private static List<WpsController.Result> results(List<String> pins, int n) {
        return results(pins, n, null);
    }

    private static List<WpsController.Result> results(List<String> pins, int n, String lastStatus) {
        List<WpsController.Result> out = new ArrayList<>();
        for (int i = 0; i < n && i < pins.size(); i++) {
            WpsController.Result r = new WpsController.Result();
            r.pin = pins.get(i);
            r.status = (i == n - 1 && lastStatus != null) ? lastStatus : "TIMEOUT";
            out.add(r);
        }
        return out;
    }

    public static void main(String[] a) {
        System.out.println("=== WPS 游标记账专项 ===");

        info("① 整批跑完 → 游标推进整批");
        List<String> p5 = batch(0, 5);
        WpsBatchPlan.Out o1 = WpsBatchPlan.account(p5, results(p5, 5));
        ok("advance = 5", o1.advance == 5, "" + o1.advance);
        ok("unprocessed = 0", o1.unprocessed == 0, "" + o1.unprocessed);
        ok("不算中断", !o1.cancelled, "" + o1.cancelled);
        ok("nextCursor", o1.nextCursor(0) == 5, "" + o1.nextCursor(0));

        info("② 预算耗尽（helper 只跑到第 2 条）→ 只推 2");
        WpsBatchPlan.Out o2 = WpsBatchPlan.account(p5, results(p5, 2));
        ok("advance = 2", o2.advance == 2, "" + o2.advance);
        ok("unprocessed = 3", o2.unprocessed == 3, "" + o2.unprocessed);
        ok("标记为中断", o2.cancelled, "");
        ok("note 说明剩余", o2.note.contains("3"), o2.note);

        info("③ 暂停发生在批次中间（第 3 条后进程被杀，无输出）");
        WpsBatchPlan.Out o3 = WpsBatchPlan.account(p5, results(p5, 3));
        ok("advance = 3", o3.advance == 3, "" + o3.advance);
        ok("剩余 2 条留给下次", o3.unprocessed == 2, "" + o3.unprocessed);
        ok("nextCursor(10) = 13", o3.nextCursor(10) == 13, "" + o3.nextCursor(10));

        info("④ 确证命中提前 break → 不算「意外中断」");
        List<WpsController.Result> hitRs = results(p5, 3, "SUCCESS");
        WpsBatchPlan.Out o4 = WpsBatchPlan.account(p5, hitRs);
        ok("hitStop", o4.hitStop, "");
        ok("不算 cancelled（任务已完成）", !o4.cancelled, "");
        ok("advance = 3", o4.advance == 3, "" + o4.advance);

        info("⑤ 疑似命中（SUCCESS_UNVERIFIED）不得当成停止理由");
        WpsBatchPlan.Out o5 = WpsBatchPlan.account(p5, results(p5, 2, "SUCCESS_UNVERIFIED"));
        ok("不是 hitStop", !o5.hitStop, "");
        ok("仍算中断（未跑完）", o5.cancelled, "");

        info("⑥ 结果与请求对不上 → 停在出错位置，不越过未知区间");
        List<WpsController.Result> bad = results(p5, 5);
        bad.get(2).pin = "00000000";     // 第三条对不上
        WpsBatchPlan.Out o6 = WpsBatchPlan.account(p5, bad);
        ok("advance 只到出错位置 (2)", o6.advance == 2, "" + o6.advance);
        ok("记下 mismatched", o6.mismatched == 1, "" + o6.mismatched);
        ok("note 说明原因", o6.note.contains("不一致"), o6.note);

        info("⑦ 空候选直接算处理过（不卡游标）");
        List<String> withBlank = new ArrayList<>();
        withBlank.add("00000000");
        withBlank.add("");
        withBlank.add("00010001");
        WpsBatchPlan.Out o7 = WpsBatchPlan.account(withBlank, results(withBlank, 3));
        ok("advance = 3", o7.advance == 3, "" + o7.advance);
        ok("blank = 1", o7.blank == 1, "" + o7.blank);

        info("⑧ SKIPPED_INVALID 计为已处理（helper 会如实上报）");
        List<WpsController.Result> skipRs = new ArrayList<>();
        WpsController.Result s0 = new WpsController.Result();
        s0.pin = p5.get(0);
        s0.status = "SKIPPED_INVALID";
        skipRs.add(s0);
        WpsBatchPlan.Out o8 = WpsBatchPlan.account(p5, skipRs);
        ok("advance = 1", o8.advance == 1, "" + o8.advance);
        ok("SKIPPED 不算命中", !o8.hitStop, "");

        info("⑨ helper 完全没输出（进程没起来）");
        WpsBatchPlan.Out o9 = WpsBatchPlan.account(p5, new ArrayList<>());
        ok("advance = 0", o9.advance == 0, "" + o9.advance);
        ok("unprocessed = 5", o9.unprocessed == 5, "" + o9.unprocessed);
        ok("标记中断", o9.cancelled, "");

        info("⑩ 结果多于请求（异常）→ 推进不得超过请求数");
        List<WpsController.Result> more = results(p5, 5);
        WpsController.Result extra = new WpsController.Result();
        extra.pin = "99999999";
        extra.status = "TIMEOUT";
        more.add(extra);
        WpsBatchPlan.Out o10 = WpsBatchPlan.account(p5, more);
        ok("advance = 5（不越界）", o10.advance == 5, "" + o10.advance);
        ok("unprocessed = 0", o10.unprocessed == 0, "" + o10.unprocessed);

        info("⑪ 端到端：暂停后重跑必须包含「原来没跑到」的候选");
        int cursor = 100;
        List<String> b1 = batch(cursor, 5);                 // 请求第 100..104
        WpsBatchPlan.Out acc1 = WpsBatchPlan.account(b1, results(b1, 2));  // 只跑到第 101
        int after1 = acc1.nextCursor(cursor);
        ok("游标只到 102", after1 == 102, "" + after1);
        ok("只跑过的 2 条被记为完成", acc1.advance == 2, "" + acc1.advance);
        List<String> b2 = batch(after1, 5);                 // 从 102 重新生成
        ok("重跑批次第一项 = 原来的第 3 项（00000102…）",
                b2.get(0).equals(b1.get(2)), b2.get(0) + " vs " + b1.get(2));
        ok("重跑批次完整覆盖未跑完的 3 条",
                b2.get(0).equals(b1.get(2)) && b2.get(1).equals(b1.get(3))
                        && b2.get(2).equals(b1.get(4)),
                b2.toString());
        ok("对比：修复前口径会跳过 " + (b1.size() - acc1.advance) + " 条",
                after1 < cursor + b1.size(), "");

        info("⑫ 阶段分母口径（UI 不再自己写死）");
        ok("阶段 1 分母 = 10000", WpsCandidates.phaseTotal(1) == 10000,
                "" + WpsCandidates.phaseTotal(1));
        ok("阶段 2 分母 = 1000", WpsCandidates.phaseTotal(2) == 1000,
                "" + WpsCandidates.phaseTotal(2));
        ok("两阶段合计 = halfTotal()",
                WpsCandidates.phaseTotal(1) + WpsCandidates.phaseTotal(2)
                        == WpsCandidates.halfTotal(), "");

        System.out.println();
        System.out.println("=== WpsCursorAccountingTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
