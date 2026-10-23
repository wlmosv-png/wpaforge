package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.BackoffPolicy;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;

/**
 * AP 侧退避策略测试。
 *
 * 这套的重点是「不误触发」—— 单条偶发变慢不该打断正常节奏，
 * 只有连续偏慢才说明目标在限流。
 */
public class BackoffPolicyTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) {
        System.out.println("== 样本不足不触发 ==");
        ok("null 输入", BackoffPolicy.suggestDelayMs(null, 0) == 0);
        ok("空数组", BackoffPolicy.suggestDelayMs(new long[0], 0) == 0);
        ok("只有 1 条", BackoffPolicy.suggestDelayMs(new long[]{9999}, 1) == 0);
        ok("只有 2 条", BackoffPolicy.suggestDelayMs(new long[]{9999, 9999}, 2) == 0);

        System.out.println("== 连续偏慢触发 ==");
        long[] slow = {13000, 14000, 15000};
        long d = BackoffPolicy.suggestDelayMs(slow, 3);
        ok("连续 3 条慢 -> 有冷却", d > 0);
        ok("冷却落在 [下限, 上限]",
                d >= BackoffPolicy.COOL_MIN_MS && d <= BackoffPolicy.COOL_MAX_MS);
        ok("同样输入结果确定（可复现）", BackoffPolicy.suggestDelayMs(slow, 3) == d);
        ok("刚好等于阈值也算慢",
                BackoffPolicy.suggestDelayMs(new long[]{BackoffPolicy.SLOW_MS,
                        BackoffPolicy.SLOW_MS, BackoffPolicy.SLOW_MS}, 3) > 0);

        System.out.println("== 不误触发 ==");
        ok("全部很快", BackoffPolicy.suggestDelayMs(new long[]{1000, 1200, 900}, 3) == 0);
        ok("最近 3 条里 2 慢 1 快 -> 不触发",
                BackoffPolicy.suggestDelayMs(new long[]{2000, 7000, 8000}, 3) == 0);
        ok("慢的是更早的、最近 3 条正常 -> 不触发",
                BackoffPolicy.suggestDelayMs(new long[]{9000, 9000, 9000, 1000, 1100, 1200}, 6) == 0);
        ok("窗口只看最近 3 条（早先的慢已滚出）",
                BackoffPolicy.suggestDelayMs(new long[]{9000, 9000, 9000, 500, 600, 700}, 6) == 0);

        System.out.println("== 只有「AP 给了明确结论」的尝试才算样本 ==");
        ok("AUTH_FAIL 可计数", BackoffPolicy.countable(CrackEngine.Verdict.AUTH_FAIL));
        ok("SUCCESS 可计数", BackoffPolicy.countable(CrackEngine.Verdict.SUCCESS));
        ok("TIMEOUT 不可计数（等满超时是本地固定值，不代表 AP 变慢）",
                !BackoffPolicy.countable(CrackEngine.Verdict.TIMEOUT));
        ok("UNKNOWN 不可计数（通道异常）",
                !BackoffPolicy.countable(CrackEngine.Verdict.UNKNOWN));
        ok("ERROR 不可计数", !BackoffPolicy.countable(CrackEngine.Verdict.ERROR));
        ok("TARGET_GONE 不可计数",
                !BackoffPolicy.countable(CrackEngine.Verdict.TARGET_GONE));

        System.out.println("== 环形窗口 ==");
        BackoffPolicy.Window w = new BackoffPolicy.Window();
        w.record(100); w.record(200); w.record(300);
        ok("count = 3", w.count() == 3);
        long[] r = w.recent();
        ok("顺序为最早 -> 最新", r.length == 3 && r[0] == 100 && r[1] == 200 && r[2] == 300);
        w.record(400); w.record(500); w.record(600); w.record(700);
        ok("count 封顶 6", w.count() == 6);
        long[] r2 = w.recent();
        ok("溢出后最旧被挤掉（200 起）", r2[0] == 200);
        ok("溢出后最新仍是 700", r2[5] == 700);
        ok("窗口内容都很快 -> 不触发", BackoffPolicy.suggestDelayMs(r2, 6) == 0);
        w.reset();
        ok("reset 后 count = 0", w.count() == 0);
        w.record(8000);
        ok("reset 后可继续记录", w.count() == 1 && w.recent()[0] == 8000);

        System.out.println();
        System.out.println("=== 退避策略测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
