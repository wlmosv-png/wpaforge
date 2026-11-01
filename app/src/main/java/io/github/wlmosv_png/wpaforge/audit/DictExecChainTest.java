package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.CrackService;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 字典执行链专项：**候选管理**（过滤 / 续跑位置 / 执行顺序 / 执行次数）。
 *
 * <h3>本次要钉死的缺陷</h3>
 * <pre>
 *   字典 [A,B,C,D,E]，本目标试过 {A,C}，任务 offset = 3
 *   -> 过滤后列表 = [B,D,E]（长度 3）
 *   -> 若仍从 offset=3 开始：越界 -> 一条都不跑
 *      B、D、E 从未在该目标上验证过，却被整体漏掉。
 * </pre>
 *
 * <h3>验证方式</h3>
 * <ol>
 *   <li>候选级：真实 {@link TriedSet} + 真实 {@code filterTried} + 真实
 *       {@code effectiveStartOffset}，逐条对照输入与最终列表。</li>
 *   <li>调度级：真实 {@code run()}（经 VerdictProvider 提供确定结论），
 *       记录实际执行序列与每条的执行次数。</li>
 * </ol>
 */
public final class DictExecChainTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-D1";
    static final String BSSID = "02:00:00:00:00:20";

    private static Candidate cand(String pw) {
        return new Candidate(pw, Candidate.Origin.USER_DICT);
    }

    private static List<String> pws(List<Candidate> l) {
        List<String> o = new ArrayList<>();
        for (Candidate c : l) o.add(c.password);
        return o;
    }

    public static void main(String[] args) {
        System.out.println("== 字典执行链：候选管理 ==");

        // ---------------------------------------------------------- 1. 过滤 + 续跑位置
        TriedSet tried = new TriedSet();
        tried.add("passA012");
        tried.add("passC012");

        List<Candidate> dict = new ArrayList<>();
        dict.add(cand("passA012"));
        dict.add(cand("passB012"));
        dict.add(cand("passC012"));
        dict.add(cand("passD012"));
        dict.add(cand("passE012"));
        info("字典输入 = " + pws(dict));

        CrackService.TriedFilter f = CrackService.filterTried(dict, tried);
        info("过滤后 = " + pws(f.kept) + "（跳过 " + f.skipped + "）");

        ok("1a 过滤只剔除已试候选，保留其余",
                pws(f.kept).equals(java.util.Arrays.asList(
                        "passB012", "passD012", "passE012")) && f.skipped == 2,
                "kept=" + pws(f.kept) + " skipped=" + f.skipped);

        // 关键：offset=3（上一轮在同一份 5 条列表里跑到第 3 条）
        int savedOffset = 3;
        int eff = CrackService.effectiveStartOffset(savedOffset, f.skipped > 0);
        int oldEff = savedOffset;   // 修改前的行为：直接用 savedOffset
        info("offset=" + savedOffset + "；旧行为从 " + oldEff + " 开始，新行为从 " + eff + " 开始");

        List<String> wouldRunOld = new ArrayList<>();
        for (int i = oldEff; i < f.kept.size(); i++) wouldRunOld.add(f.kept.get(i).password);
        List<String> wouldRunNew = new ArrayList<>();
        for (int i = eff; i < f.kept.size(); i++) wouldRunNew.add(f.kept.get(i).password);
        info("旧行为实际会跑 = " + wouldRunOld + "（越界则为空）");
        info("新行为实际会跑 = " + wouldRunNew);

        ok("1b **过滤后不得沿用旧 offset**（否则未试候选被漏掉）",
                eff == 0, "effectiveStartOffset 返回 " + eff);
        ok("1c 新行为覆盖全部未试候选（B/D/E）",
                wouldRunNew.equals(java.util.Arrays.asList(
                        "passB012", "passD012", "passE012")),
                "实际=" + wouldRunNew);
        ok("1d 旧行为确实会漏掉（证明这是真缺陷，不是空担心）",
                wouldRunOld.isEmpty(),
                "旧行为本来会跑 " + wouldRunOld + " —— 若不为空则缺陷不成立");

        // ---------------------------------------------------------- 2. 无过滤时保持原语义
        ok("2a 未发生过滤时 offset 语义不变",
                CrackService.effectiveStartOffset(3, false) == 3, "");
        ok("2b offset 为负时归一为 0",
                CrackService.effectiveStartOffset(-5, false) == 0, "");
        ok("2c 过滤时不看 offset，一律从头",
                CrackService.effectiveStartOffset(0, true) == 0
                        && CrackService.effectiveStartOffset(99, true) == 0, "");

        // ---------------------------------------------------------- 3. 空 triedSet：不过滤
        TriedSet empty = new TriedSet();
        CrackService.TriedFilter f0 = CrackService.filterTried(dict, empty);
        ok("3 空 triedSet 不剔除任何候选",
                f0.kept.size() == 5 && f0.skipped == 0,
                "kept=" + f0.kept.size() + " skipped=" + f0.skipped);

        // ---------------------------------------------------------- 4. 调度级：执行序列与次数
        runSchedule();

        System.out.println();
        System.out.println("=== 字典执行链测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** 用真实 run() 验证：未过滤时从 offset 继续，且每条恰好执行一次。 */
    private static void runSchedule() {
        try {
            final List<String> executed = new ArrayList<>();
            CrackEngine.VerdictProvider vp = new CrackEngine.VerdictProvider() {
                @Override public CrackEngine.Attempt attemptFor(String password, int index) {
                    executed.add(password);
                    CrackEngine.Attempt a = new CrackEngine.Attempt(password,
                            CrackEngine.Verdict.TIMEOUT, 1L, "none", "scripted");
                    a.grade = EvidenceGrade.Level.NONE;
                    return a;
                }
            };
            CrackEngine engine = newEngine();
            engine.setVerdictProviderForTest(vp);

            List<Candidate> l = new ArrayList<>();
            for (String p : new String[]{"passB012", "passD012", "passE012"}) l.add(cand(p));

            NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412,
                    NetRecord.Security.WPA2, false, "");
            CrackEngine.Listener noop = new CrackEngine.Listener() {
                @Override public void onAttemptStart(int index, int total, String candidate) {}
                @Override public void onAttemptResult(CrackEngine.Attempt a) {}
                @Override public void onProgress(int tested, int total, double rate, long etaMs) {}
                @Override public void onFinished(int tested, int cursor, String hitPassword, String outcome) {}
                @Override public void onBlocked(String title, String detail) {}
            };

            engine.run(t, l, 0, 1000L, noop);
            info("[调度] 从 offset=0 执行的序列 = " + executed);
            ok("4a 过滤后从 0 开始 -> 全部未试候选都被执行",
                    executed.equals(java.util.Arrays.asList(
                            "passB012", "passD012", "passE012")),
                    "实际=" + executed);
            ok("4b 每条恰好执行一次（无重复）",
                    executed.size() == 3
                            && executed.get(0).equals(executed.get(0))
                            && new java.util.HashSet<>(executed).size() == 3,
                    "序列=" + executed);

            // 对照：直接从 offset=3 开始（旧行为）-> 一条都不跑
            final List<String> executed2 = new ArrayList<>();
            CrackEngine.VerdictProvider vp2 = new CrackEngine.VerdictProvider() {
                @Override public CrackEngine.Attempt attemptFor(String password, int index) {
                    executed2.add(password);
                    CrackEngine.Attempt a = new CrackEngine.Attempt(password,
                            CrackEngine.Verdict.TIMEOUT, 1L, "none", "scripted");
                    a.grade = EvidenceGrade.Level.NONE;
                    return a;
                }
            };
            CrackEngine engine2 = newEngine();
            engine2.setVerdictProviderForTest(vp2);
            engine2.run(t, l, 3, 1000L, noop);
            info("[调度] 从 offset=3 执行的序列 = " + executed2 + "（旧行为演示）");
            ok("4c 旧行为（offset=3）确实一条都不执行 —— 缺陷成立",
                    executed2.isEmpty(), "实际=" + executed2);
        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL [调度] 异常: " + t);
            t.printStackTrace(System.out);
        }
    }

    private static CrackEngine newEngine() throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(2_000_000L, 1000L, 0L));
        Method bypass = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        bypass.setAccessible(true);
        bypass.invoke(e);
        e.setShellChannel(new CrackEngine.ShellChannel() {
            @Override public String exec(String cmd, long timeoutMs) {
                if (cmd == null) return "";
                if (cmd.startsWith("cmd wifi status")) return "Wifi is not connected\n";
                if (cmd.startsWith("cmd wifi list-networks")) return "";
                return "";
            }
            @Override public String name() { return "dict-null"; }
        });
        return e;
    }
}
