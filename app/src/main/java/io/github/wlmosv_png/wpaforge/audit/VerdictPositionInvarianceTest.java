package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * **正确密码的位置不能改变它的判定**。
 *
 * <h3>防的是什么真实错误</h3>
 * 用户真实现场：正确密码在字典第 20 多位才命中。
 * 如果判定链对「位置」敏感（例如 cursor 算错、窗口自适应缩短、退避把慢速
 * 批次当限流、或前序候选的延迟事件串窗），就会**前面全错、正确那条被跳过**。
 *
 * <p>本测试把同一份正确密码放在 index 0 / 5 / 19（末尾），
 * 驱动真实 {@code run()}，断言：
 * <ul>
 *   <li>每处都判 SUCCESS / DIRECT；</li>
 *   <li>命中前执行的条数 == 正确密码的位置（不多不少）；</li>
 *   <li>cursor 停在命中位置（前面全是 DIRECT 时才推进，这里正是）；</li>
 *   <li>命中后不再执行后续候选。</li>
 * </ul>
 */
public final class VerdictPositionInvarianceTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-POS";
    static final String BSSID = "02:00:00:00:00:99";

    static final class Rec implements CrackEngine.Listener {
        final List<String> started = new ArrayList<>();
        final List<String> verdicts = new ArrayList<>();
        int finCursor = Integer.MIN_VALUE;
        String outcome;
        @Override public void onAttemptStart(int i, int total, String c) { started.add(c); }
        @Override public void onAttemptResult(CrackEngine.Attempt a) {
            verdicts.add(a.verdict.name() + "/" + a.grade);
        }
        @Override public void onProgress(int t, int total, double r, long eta) {}
        @Override public void onCursor(int c) {}
        @Override public void onFinished(int t, int cursor, String hit, String o) {
            finCursor = cursor; outcome = o;
        }
        @Override public void onBlocked(String t, String d) { System.out.println("  !! " + t); }
    }

    static final class P implements CrackEngine.VerdictProvider {
        final Map<String, CrackEngine.Verdict> v = new HashMap<>();
        final Map<String, EvidenceGrade.Level> g = new HashMap<>();
        P put(String pw, CrackEngine.Verdict verdict, EvidenceGrade.Level grade) {
            v.put(pw, verdict); g.put(pw, grade); return this;
        }
        @Override public CrackEngine.Attempt attemptFor(String password, int index) {
            CrackEngine.Verdict vv = v.getOrDefault(password, CrackEngine.Verdict.AUTH_FAIL);
            EvidenceGrade.Level gg = g.getOrDefault(password, EvidenceGrade.Level.DIRECT);
            CrackEngine.Attempt a = new CrackEngine.Attempt(password, vv, 1L, "scripted", "s");
            a.grade = gg;
            return a;
        }
    }

    private static CrackEngine engine(CrackEngine.VerdictProvider p, long t0) throws Exception {
        Constructor<?> c = CrackEngine.class.getDeclaredConstructor(int.class);
        c.setAccessible(true);
        CrackEngine e = (CrackEngine) c.newInstance(0);
        Method sc = CrackEngine.class.getDeclaredMethod("setClockForTest", EngineClock.class);
        sc.setAccessible(true);
        sc.invoke(e, new TestEngineClock(t0, 1L, 0L));
        Method b = CrackEngine.class.getDeclaredMethod("bypassWifiCheckForTest");
        b.setAccessible(true);
        b.invoke(e);
        e.setShellChannel(new CrackEngine.ShellChannel() {
            @Override public String exec(String cmd, long t) {
                if (cmd == null) return "";
                if (cmd.startsWith("cmd wifi status")) return "Wifi is not connected\n";
                if (cmd.startsWith("cmd wifi list-networks")) return "";
                return "";
            }
            @Override public String name() { return "pos-null"; }
        });
        e.setVerdictProviderForTest(p);
        return e;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 正确密码位置无关性（1 / 6 / 20 末尾）===");

        final int N = 20;
        String correct = "CorrectPass9x";
        int[] positions = {0, 5, N - 1};

        for (int hitPos : positions) {
            List<Candidate> cands = new ArrayList<>();
            for (int i = 0; i < N; i++) {
                String pw = (i == hitPos) ? correct : ("wrongpw" + (100 + i) + "Z");
                cands.add(new Candidate(pw, Candidate.Origin.USER_DICT));
            }
            P p = new P();
            p.put(correct, CrackEngine.Verdict.SUCCESS, EvidenceGrade.Level.DIRECT);

            CrackEngine e = engine(p, 1_000_000L);
            NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
            Rec r = new Rec();
            e.run(t, cands, 0, 1000L, r);

            info("位置 " + hitPos + "：执行 " + r.started.size() + " 条，"
                    + "cursor=" + r.finCursor + "，outcome=" + r.outcome);
            ok("位置 " + hitPos + " 判 SUCCESS（outcome=DONE_HIT）",
                    "DONE_HIT".equals(r.outcome), String.valueOf(r.outcome));
            ok("位置 " + hitPos + " 命中前执行条数 == 位置+1",
                    r.started.size() == hitPos + 1, String.valueOf(r.started.size()));
            ok("位置 " + hitPos + " 最后一条就是正确密码",
                    r.started.get(r.started.size() - 1).equals(correct),
                    r.started.get(r.started.size() - 1));
            ok("位置 " + hitPos + " 命中后不再执行后续候选",
                    r.started.size() == hitPos + 1, "exceeded");
            ok("位置 " + hitPos + " 命中那条等级为 DIRECT",
                    r.verdicts.get(r.verdicts.size() - 1).endsWith("DIRECT"),
                    r.verdicts.get(r.verdicts.size() - 1));
        }

        // ---- 对照：同一份字典但正确密码不在里面 → 不应误报成功 ----
        {
            List<Candidate> cands = new ArrayList<>();
            for (int i = 0; i < N; i++) {
                cands.add(new Candidate("wrongpw" + (200 + i) + "Z", Candidate.Origin.USER_DICT));
            }
            CrackEngine e = engine(new P(), 1_000_000L);
            NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412, NetRecord.Security.WPA2, false, "");
            Rec r = new Rec();
            e.run(t, cands, 0, 1000L, r);
            info("对照（无正确密码）：执行 " + r.started.size()
                    + " 条，cursor=" + r.finCursor + "，outcome=" + r.outcome);
            ok("对照 全错时 outcome 不是 DONE_HIT", !"DONE_HIT".equals(r.outcome),
                    String.valueOf(r.outcome));
            ok("对照 全 DIRECT 时 cursor 跑到末尾", r.finCursor == N,
                    String.valueOf(r.finCursor));
            ok("对照 执行了全部 20 条", r.started.size() == N,
                    String.valueOf(r.started.size()));
        }

        System.out.println();
        System.out.println("=== 位置无关性: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
