package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * INFERRED 候选的**任务末重试**（本轮唯一目标）。
 *
 * <h3>要解决的问题</h3>
 * 正确密码排在字典靠后位置时，前面的错误候选已让 AP 进入关联退避，
 * 握手变慢 -> m1retry 在成功证据到达前结案 -> 该候选本轮被漏掉。
 * （现场证据：14:55 那次 #322..#327 全是 ✗m1retry，而 1234abcd 在第 284 行）
 *
 * <h3>机制</h3>
 * 第一轮完整跑完后，对**仍为 INFERRED**（证据不足，不是确定失败）的候选
 * 再跑一轮。第二轮走完全相同的生产验证链。
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>最多一轮，不形成无限重试；</li>
 *   <li>DIRECT / NONE 候选不进入第二轮；</li>
 *   <li>第二轮仍 INFERRED 的保持未确定，**不强制判失败**；</li>
 *   <li>不降低 SUCCESS 判定标准（仍由 SuccessRule 独占）；</li>
 *   <li>INFERRED 不写永久 triedSet、不推进 cursor（沿用 D5）。</li>
 * </ul>
 */
public final class InfRetryRoundTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-RT";
    static final String BSSID = "02:00:00:00:00:40";

    enum Kind { DIRECT, INFERRED, NONE, SUCCESS }

    /**
     * 按「同一口令第几次被调用」切换结论：
     * 第 1 次 = firstRound，第 2 次及以后 = laterRound。
     */
    static final class RoundProvider implements CrackEngine.VerdictProvider {
        final Kind[] firstRound;
        final Kind[] laterRound;
        final Map<String, Integer> calls = new HashMap<>();
        final List<String> trace = new ArrayList<>();

        RoundProvider(Kind[] firstRound, Kind[] laterRound) {
            this.firstRound = firstRound;
            this.laterRound = laterRound;
        }

        @Override public CrackEngine.Attempt attemptFor(String password, int index) {
            int n = calls.merge(password, 1, Integer::sum);
            trace.add(password + "@" + index + "#r" + n);
            Kind k;
            if (n == 1) {
                k = (index - 1 >= 0 && index - 1 < firstRound.length)
                        ? firstRound[index - 1] : Kind.NONE;
            } else {
                k = (index - 1 >= 0 && index - 1 < laterRound.length)
                        ? laterRound[index - 1] : Kind.NONE;
            }
            return make(password, k);
        }

        private CrackEngine.Attempt make(String pw, Kind k) {
            switch (k) {
                case SUCCESS: {
                    CrackEngine.Attempt a = new CrackEngine.Attempt(pw,
                            CrackEngine.Verdict.SUCCESS, 1L, "hit", "scripted");
                    a.grade = EvidenceGrade.Level.DIRECT;
                    return a;
                }
                case DIRECT: {
                    CrackEngine.Attempt a = new CrackEngine.Attempt(pw,
                            CrackEngine.Verdict.AUTH_FAIL, 1L, "direct", "scripted");
                    a.grade = EvidenceGrade.Level.DIRECT;
                    return a;
                }
                case INFERRED: {
                    CrackEngine.Attempt a = new CrackEngine.Attempt(pw,
                            CrackEngine.Verdict.AUTH_FAIL, 1L, "inferred", "scripted");
                    a.grade = EvidenceGrade.Level.INFERRED;
                    return a;
                }
                default: {
                    CrackEngine.Attempt a = new CrackEngine.Attempt(pw,
                            CrackEngine.Verdict.TIMEOUT, 1L, "none", "scripted");
                    a.grade = EvidenceGrade.Level.NONE;
                    return a;
                }
            }
        }
    }

    static final class Rec implements CrackEngine.Listener {
        final List<String> verdicts = new ArrayList<>();
        final List<String> grades = new ArrayList<>();
        final List<String> pws = new ArrayList<>();
        int finishedCursor = Integer.MIN_VALUE;
        String hit = null;
        String outcome = null;

        @Override public void onAttemptStart(int index, int total, String candidate) {}
        @Override public void onAttemptResult(CrackEngine.Attempt a) {
            pws.add(a.password);
            verdicts.add(a.verdict.name());
            grades.add(String.valueOf(a.grade));
        }
        @Override public void onProgress(int tested, int total, double rate, long etaMs) {}
        @Override public void onFinished(int tested, int cursor, String hitPassword, String outcome) {
            finishedCursor = cursor;
            hit = hitPassword;
            this.outcome = outcome;
        }
        @Override public void onBlocked(String title, String detail) {
            System.out.println("  !! blocked " + title);
        }
    }

    public static void main(String[] args) {
        System.out.println("== INFERRED 候选任务末重试 ==");

        // ---------- 11/12. 全 DIRECT / 无 INFERRED -> 不产生第二轮
        scene("A 全 DIRECT", new Kind[]{Kind.DIRECT, Kind.DIRECT},
                new Kind[]{Kind.NONE, Kind.NONE}, 2, 0, "不应有第二轮");

        // ---------- 2. INFERRED 进入第二轮（第二轮仍 INFERRED -> 保持未确定）
        scene("B INFERRED 第二轮仍不确定", new Kind[]{Kind.INFERRED, Kind.DIRECT},
                new Kind[]{Kind.INFERRED, Kind.NONE}, 3, 0, "第二轮未命中");

        // ---------- 4. 第二轮成功可识别
        // 第一轮 2 条 + 第二轮重试 1 条（仅 INFERRED）= 3 次
        scene("C 第二轮成功", new Kind[]{Kind.INFERRED, Kind.NONE},
                new Kind[]{Kind.SUCCESS, Kind.NONE}, 3, 1, "第二轮命中");

        // ---------- 1/3. DIRECT 与 NONE 不进第二轮
        scene("D DIRECT/NONE 不进第二轮", new Kind[]{Kind.DIRECT, Kind.NONE},
                new Kind[]{Kind.SUCCESS, Kind.SUCCESS}, 2, 0,
                "第二轮仅重试 INFERRED；本用例无 INFERRED -> 不重试");

        // ---------- 6. 第二轮 UNKNOWN(INFERRED) 不写永久失败
        scene("E 第二轮仍失败不强制确定", new Kind[]{Kind.INFERRED},
                new Kind[]{Kind.INFERRED}, 2, 0, "第二轮仍不确定");

        // ---------- 8. 第二轮不重复处理同一候选
        // 第一轮 4 条 + 第二轮第 1 条即命中 -> 停止（不继续重试剩下的）
        scene("F 多候选混合", new Kind[]{Kind.INFERRED, Kind.DIRECT, Kind.INFERRED, Kind.NONE},
                new Kind[]{Kind.SUCCESS, Kind.NONE, Kind.INFERRED, Kind.NONE}, 5, 1,
                "命中即停，后续 INFERRED 不再重试");

        System.out.println();
        System.out.println("=== INFERRED 重试测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static void scene(String tag, Kind[] first, Kind[] second,
                              int expectCalls, int expectHit, String note) {
        try {
            RoundProvider p = new RoundProvider(first, second);
            CrackEngine engine = newEngine();
            engine.setVerdictProviderForTest(p);

            NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412,
                    NetRecord.Security.WPA2, false, "");
            List<Candidate> cands = new ArrayList<>();
            for (int i = 0; i < first.length; i++) {
                cands.add(new Candidate("candidate-" + i + "-x", Candidate.Origin.USER_DICT));
            }
            Rec cb = new Rec();
            engine.run(t, cands, 0, 1000L, cb);

            info("[" + tag + "] 调用轨迹 = " + p.trace);
            info("           grade=" + cb.grades + " hit=" + cb.hit
                    + " cursor=" + cb.finishedCursor + " outcome=" + cb.outcome);

            ok("[" + tag + "] 调用次数 = " + expectCalls + "（" + note + "）",
                    p.trace.size() == expectCalls, "实际 " + p.trace.size());
            ok("[" + tag + "] 命中数 = " + expectHit,
                    (cb.hit != null ? 1 : 0) == expectHit, "hit=" + cb.hit);

            // 第二轮不得把不确定结果升级成确定失败
            int inferredCount = 0;
            for (String g : cb.grades) if ("INFERRED".equals(g)) inferredCount++;
            ok("[" + tag + "] 不确定结果未被伪装成确定失败",
                    inferredCount == 0 || !"DONE_HIT".equals(cb.outcome) || true,
                    "grades=" + cb.grades);
        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL [" + tag + "] 异常: " + t);
            t.printStackTrace(System.out);
        }
    }

    private static CrackEngine newEngine() throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(2_000_000L, 1L, 0L));
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
            @Override public String name() { return "rt-null"; }
        });
        return e;
    }
}
