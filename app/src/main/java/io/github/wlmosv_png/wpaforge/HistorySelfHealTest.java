package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AttemptLedger;
import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.TriedSet;

import java.util.ArrayList;
import java.util.List;

/**
 * 「对方改了密码 → 同一本字典再跑不跑了」的自愈门禁（2026-11-15）。
 *
 * <p>这个 bug 的形态是：一条日志都不像错误，只是**什么都没发生**。
 * 所以门禁的重点不是「自愈能不能触发」，而是**它不该触发的时候绝不触发** ——
 * 一旦误触发，用户会平白重跑一整本字典（几小时），而且会失去跨字典去重的收益。
 * 下面把三类误触发条件逐一钉住。
 */
public class HistorySelfHealTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  " + detail);
        }
    }

    private static void ok(String name, boolean cond) {
        ok(name, cond, "");
    }

    public static void main(String[] a) {
        System.out.println("== 判据：该触发的唯一情形 ==");
        ok("命中过 + 有历史 + 一条都跑不了 → 自愈",
                CrackService.shouldAutoClearHistory(false, true, true, 0));
        ok("同样情形（count 负值也当 0）",
                CrackService.shouldAutoClearHistory(false, true, true, -5));

        System.out.println("== 判据：不该触发的三种情形 ==");
        ok("没有历史命中记录 → 不动历史",
                !CrackService.shouldAutoClearHistory(false, false, true, 0));
        ok("没有已试/账本 → 过滤本来就不会拦",
                !CrackService.shouldAutoClearHistory(false, true, false, 0));
        ok("还有候选可跑 → 正常跑，不动历史",
                !CrackService.shouldAutoClearHistory(false, true, true, 1)
                        && !CrackService.shouldAutoClearHistory(false, true, true, 999));
        ok("用户已选强制重跑 → 无需自愈",
                !CrackService.shouldAutoClearHistory(true, true, true, 0));

        System.out.println("== 判据：四条件笛卡尔积穷举（16 组）==");
        int triggered = 0;
        for (int i = 0; i < 16; i++) {
            boolean fr = (i & 1) != 0;
            boolean hit = (i & 2) != 0;
            boolean hist = (i & 4) != 0;
            boolean zero = (i & 8) != 0;
            int cnt = zero ? 0 : 3;
            boolean got = CrackService.shouldAutoClearHistory(fr, hit, hist, cnt);
            boolean want = !fr && hit && hist && zero;
            if (got != want) {
                fail++;
                System.out.println("  FAIL 组合 fr=" + fr + " hit=" + hit + " hist=" + hist
                        + " zero=" + zero + " got=" + got + " want=" + want);
            } else if (got) {
                triggered++;
            }
        }
        if (triggered == 1) {
            pass++;
            System.out.println("  OK   16 组里恰好 1 组触发（其余 15 组都不动历史）");
        } else {
            fail++;
            System.out.println("  FAIL 触发组数应为 1，实际 " + triggered);
        }

        System.out.println("== runnableCount：接着跑会执行几条 ==");
        List<Candidate> work = cands("a1234567", "b1234567", "c1234567", "d1234567", "e1234567");
        ok("空历史 + offset 0 → 全部", runnable(work, new TriedSet(),
                new AttemptLedger(), AttemptLedger.SkipPolicy.CONSERVATIVE, 0, false) == 5);
        ok("空历史 + offset 3 → 剩 2", runnable(work, new TriedSet(),
                new AttemptLedger(), AttemptLedger.SkipPolicy.CONSERVATIVE, 3, false) == 2);
        ok("offset 到底 → 0", runnable(work, new TriedSet(),
                new AttemptLedger(), AttemptLedger.SkipPolicy.CONSERVATIVE, 5, false) == 0);
        ok("空列表 → 0", CrackService.runnableCount(new ArrayList<Candidate>(), work,
                new TriedSet(), new AttemptLedger(),
                AttemptLedger.SkipPolicy.CONSERVATIVE, 0, false) == 0);
        ok("过滤后为 null → 0", CrackService.runnableCount(null, work, new TriedSet(),
                new AttemptLedger(), AttemptLedger.SkipPolicy.CONSERVATIVE, 0, false) == 0);

        TriedSet tried = new TriedSet();
        for (Candidate c : work) tried.add(c.password);
        ok("全部都在已试集合 → 0（这正是用户遇到的「不跑了」）",
                runnable(work, tried, new AttemptLedger(),
                        AttemptLedger.SkipPolicy.CONSERVATIVE, 0, false) == 0);
        ok("强制重跑 → 忽略已试集合 → 全部",
                runnable(work, tried, new AttemptLedger(),
                        AttemptLedger.SkipPolicy.CONSERVATIVE, 0, true) == 5);

        TriedSet half = new TriedSet();
        half.add("a1234567");
        half.add("b1234567");
        ok("只试过前两条 → 剩 3",
                runnable(work, half, new AttemptLedger(),
                        AttemptLedger.SkipPolicy.CONSERVATIVE, 0, false) == 3);
        ok("只试过前两条 + offset 4 → 剩 1",
                runnable(work, half, new AttemptLedger(),
                        AttemptLedger.SkipPolicy.CONSERVATIVE, 4, false) == 1);

        System.out.println("== 目标比对 ==");
        ok("SSID 与 BSSID 都相同",
                CrackService.sameTarget("MyWifi", "MyWifi", "aa:bb:cc:dd:ee:ff", "AA:BB:CC:DD:EE:FF"));
        ok("SSID 不同 → 不是同一目标",
                !CrackService.sameTarget("MyWifi", "Other", "aa:bb:cc:dd:ee:ff", "aa:bb:cc:dd:ee:ff"));
        ok("缺 BSSID 时只比 SSID（宽容）",
                CrackService.sameTarget("MyWifi", "MyWifi", null, "aa:bb:cc:dd:ee:ff"));
        ok("缺 SSID 一律不算同一目标（保守）",
                !CrackService.sameTarget(null, "MyWifi", "aa:bb:cc:dd:ee:ff", "aa:bb:cc:dd:ee:ff"));

        System.out.println("== safeSize ==");
        ok("null → 0", CrackService.safeSize(null) == 0);
        ok("长度原样", CrackService.safeSize(work) == 5);

        System.out.println();
        System.out.println("=== 历史自愈: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /**
     * 与生产同口径的一步：先过滤，再按游标算「实际还会执行几条」。
     *
     * <p>刻意**不复刻** CrackService 里那段逻辑（那等于抄一遍被测代码来测它自己），
     * 而是把两个公开纯函数串起来 —— 门禁测的是它们串起来之后的行为。
     */
    private static int runnable(List<Candidate> work, TriedSet tried, AttemptLedger ledger,
                                AttemptLedger.SkipPolicy policy, int offset, boolean force) {
        List<Candidate> kept = CrackService.filterCandidates(work, tried, ledger, policy, force).kept;
        return CrackService.runnableCount(kept, work, tried, ledger, policy, offset, force);
    }

    private static List<Candidate> cands(String... pws) {
        List<Candidate> out = new ArrayList<>();
        for (String p : pws) out.add(new Candidate(p, Candidate.Origin.DIGITS));
        return out;
    }
}
