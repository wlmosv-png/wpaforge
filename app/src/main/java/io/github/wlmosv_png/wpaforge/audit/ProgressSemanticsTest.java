package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 进度语义测试（P0 修复回归）。
 *
 * 防的 bug（2026-11-01 真机 ll 热点实测）：
 * l.onProgress(cursor, ...) 把「安全续跑边界」当「已尝试数」传出去。
 * D5 之后 cursor 只在 DIRECT 时推进；候选普遍落在 INFERRED 时
 * cursor 钉死在起点 —— 界面「已完成 0 / 0.0%」，磁盘 offset 不涨，
 * 直到任务结束一次性补写。
 *
 * 修复后 onProgress 传 start + tested（实际已尝试的绝对位置）。
 */
public class ProgressSemanticsTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-PR";
    static final String BSSID = "02:00:00:00:00:50";

    enum Kind { DIRECT, INFERRED, NONE, SUCCESS }

    private static CrackEngine.Attempt make(Kind k, int index) {
        switch (k) {
            case SUCCESS: {
                CrackEngine.Attempt a = new CrackEngine.Attempt("pw-" + index,
                        CrackEngine.Verdict.SUCCESS, 1L, "hit", "scripted");
                a.grade = EvidenceGrade.Level.DIRECT;
                return a;
            }
            case DIRECT: {
                CrackEngine.Attempt a = new CrackEngine.Attempt("pw-" + index,
                        CrackEngine.Verdict.AUTH_FAIL, 1L, "direct", "scripted");
                a.grade = EvidenceGrade.Level.DIRECT;
                return a;
            }
            case INFERRED: {
                CrackEngine.Attempt a = new CrackEngine.Attempt("pw-" + index,
                        CrackEngine.Verdict.AUTH_FAIL, 1L, "inferred", "scripted");
                a.grade = EvidenceGrade.Level.INFERRED;
                return a;
            }
            default: {
                CrackEngine.Attempt a = new CrackEngine.Attempt("pw-" + index,
                        CrackEngine.Verdict.TIMEOUT, 1L, "none", "scripted");
                a.grade = EvidenceGrade.Level.NONE;
                return a;
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("== 进度语义（onProgress 必须反映实际尝试数） ==");

        // ---------- 场景 1：全 INFERRED —— 旧代码进度恒 0 ----------
        try {
            final Kind[] kinds = {Kind.INFERRED, Kind.INFERRED, Kind.INFERRED, Kind.INFERRED};
            CrackEngine engine = newEngine();
            engine.setVerdictProviderForTest(new CrackEngine.VerdictProvider() {
                @Override public CrackEngine.Attempt attemptFor(String password, int index) {
                    Kind k = (index - 1 >= 0 && index - 1 < kinds.length)
                            ? kinds[index - 1] : Kind.NONE;
                    return make(k, index);
                }
            });

            NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412,
                    NetRecord.Security.WPA2, false, "");
            List<Candidate> cands = new ArrayList<>();
            for (int i = 0; i < kinds.length; i++)
                cands.add(new Candidate("pw-" + i + "-x", Candidate.Origin.USER_DICT));

            final List<Integer> progress = new ArrayList<>();
            final int[] finishedCursor = {Integer.MIN_VALUE};
            CrackEngine.Listener cb = new CrackEngine.Listener() {
                @Override public void onAttemptStart(int index, int total, String candidate) {}
                @Override public void onAttemptResult(CrackEngine.Attempt a) {}
                @Override public void onProgress(int tested, int total, double rate, long etaMs) {
                    progress.add(tested);
                }
                @Override public void onFinished(int tested, int cursor, String hitPassword, String outcome) {
                    finishedCursor[0] = cursor;
                }
                @Override public void onBlocked(String title, String detail) {
                    System.out.println("  !! blocked " + title);
                }
            };
            engine.run(t, cands, 0, 1000L, cb);

            info("progress=" + progress + " cursor=" + finishedCursor[0]);

            ok("[全INFERRED] 进度至少回调一次",
                    !progress.isEmpty(), "progress=" + progress);
            ok("[全INFERRED] 最后进度 = 候选总数（全部已尝试）",
                    !progress.isEmpty() && progress.get(progress.size() - 1) == kinds.length,
                    "最后=" + (progress.isEmpty() ? -1 : progress.get(progress.size() - 1)));
            boolean mono = true;
            for (int i = 1; i < progress.size(); i++)
                if (progress.get(i) < progress.get(i - 1)) mono = false;
            ok("[全INFERRED] 进度单调不减", mono, "progress=" + progress);

            // D5 语义不动：全 INFERRED 时 cursor 必须仍是 0
            ok("[全INFERRED] cursor 仍为 0（D5 语义保持）",
                    finishedCursor[0] == 0, "cursor=" + finishedCursor[0]);
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL [全INFERRED] 异常: " + t);
            t.printStackTrace(System.out);
        }

        // ---------- 场景 2：混合 DIRECT/INFERRED ----------
        try {
            final Kind[] kinds = {Kind.DIRECT, Kind.INFERRED, Kind.DIRECT, Kind.INFERRED};
            CrackEngine engine = newEngine();
            engine.setVerdictProviderForTest(new CrackEngine.VerdictProvider() {
                @Override public CrackEngine.Attempt attemptFor(String password, int index) {
                    Kind k = (index - 1 >= 0 && index - 1 < kinds.length)
                            ? kinds[index - 1] : Kind.NONE;
                    return make(k, index);
                }
            });

            NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412,
                    NetRecord.Security.WPA2, false, "");
            List<Candidate> cands = new ArrayList<>();
            for (int i = 0; i < kinds.length; i++)
                cands.add(new Candidate("pw-" + i + "-x", Candidate.Origin.USER_DICT));

            final List<Integer> progress = new ArrayList<>();
            final int[] finishedCursor = {Integer.MIN_VALUE};
            CrackEngine.Listener cb = new CrackEngine.Listener() {
                @Override public void onAttemptStart(int index, int total, String candidate) {}
                @Override public void onAttemptResult(CrackEngine.Attempt a) {}
                @Override public void onProgress(int tested, int total, double rate, long etaMs) {
                    progress.add(tested);
                }
                @Override public void onFinished(int tested, int cursor, String hitPassword, String outcome) {
                    finishedCursor[0] = cursor;
                }
                @Override public void onBlocked(String title, String detail) {
                    System.out.println("  !! blocked " + title);
                }
            };
            engine.run(t, cands, 0, 1000L, cb);

            info("progress=" + progress + " cursor=" + finishedCursor[0]);

            ok("[混合] 最后进度 = 4（全部已尝试）",
                    !progress.isEmpty() && progress.get(progress.size() - 1) == 4,
                    "最后=" + (progress.isEmpty() ? -1 : progress.get(progress.size() - 1)));
            ok("[混合] cursor 钉在第一个 INFERRED（=1）",
                    finishedCursor[0] == 1, "cursor=" + finishedCursor[0]);
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL [混合] 异常: " + t);
            t.printStackTrace(System.out);
        }

        // ---------- 场景 3：续跑 start>0，进度是绝对位置 ----------
        try {
            final Kind[] kinds = {Kind.INFERRED, Kind.INFERRED};
            CrackEngine engine = newEngine();
            engine.setVerdictProviderForTest(new CrackEngine.VerdictProvider() {
                @Override public CrackEngine.Attempt attemptFor(String password, int index) {
                    Kind k = (index - 1 >= 0 && index - 1 < kinds.length)
                            ? kinds[index - 1] : Kind.NONE;
                    return make(k, index);
                }
            });

            NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412,
                    NetRecord.Security.WPA2, false, "");
            List<Candidate> cands = new ArrayList<>();
            for (int i = 0; i < 5; i++)
                cands.add(new Candidate("pw-" + i + "-x", Candidate.Origin.USER_DICT));

            final List<Integer> progress = new ArrayList<>();
            CrackEngine.Listener cb = new CrackEngine.Listener() {
                @Override public void onAttemptStart(int index, int total, String candidate) {}
                @Override public void onAttemptResult(CrackEngine.Attempt a) {}
                @Override public void onProgress(int tested, int total, double rate, long etaMs) {
                    progress.add(tested);
                }
                @Override public void onFinished(int tested, int cursor, String hitPassword, String outcome) {}
                @Override public void onBlocked(String title, String detail) {
                    System.out.println("  !! blocked " + title);
                }
            };
            engine.run(t, cands, 3, 1000L, cb);

            info("progress=" + progress);

            ok("[续跑] 进度是绝对位置（最终 = 5）",
                    !progress.isEmpty() && progress.get(progress.size() - 1) == 5,
                    "最后=" + (progress.isEmpty() ? -1 : progress.get(progress.size() - 1)));
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL [续跑] 异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== 进度语义测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
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
            @Override public String name() { return "pr-null"; }
        });
        return e;
    }
}
