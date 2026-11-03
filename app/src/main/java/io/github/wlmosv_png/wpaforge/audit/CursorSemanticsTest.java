package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 续跑游标语义测试（2026-11-02 真机漏判回归）。
 *
 * <h3>事故</h3>
 * 真机 etr 热点：字典第 100 行 123456987（正确密码）在候选 #99 被判 INFERRED
 * （日志窗口污染误判），随后用户点「继续」——任务直接跳过它继续跑。
 *
 * <h3>根因</h3>
 * 盘上 offset 被写成「已尝试数」(tested=101)，而不是「安全续跑边界」(cursor=99)。
 * 于是续跑从 101 开始，跳过了未排除的 #99 → 正确密码永久错过。
 *
 * <h3>契约（本测试钉死）</h3>
 * <ul>
 *   <li>{@code onProgress.tested} = 已尝试数（可含 INFERRED/NONE）→ 只用于显示；</li>
 *   <li>{@code onCursor.cursor} = 安全续跑边界（只由 DIRECT 推进）→ 必须用于写盘 offset。</li>
 * </ul>
 */
public class CursorSemanticsTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    static final String TARGET = "LAB-CS";
    static final String BSSID = "02:00:00:00:00:70";

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
        System.out.println("== 续跑游标语义（写盘必须用 cursor，不是 tested） ==");

        // ---------- 场景 1：全 INFERRED ----------
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
            final List<Integer> cursors = new ArrayList<>();
            CrackEngine.Listener cb = new CrackEngine.Listener() {
                @Override public void onAttemptStart(int index, int total, String candidate) {}
                @Override public void onAttemptResult(CrackEngine.Attempt a) {}
                @Override public void onProgress(int tested, int total, double rate, long etaMs) {
                    progress.add(tested);
                }
                @Override public void onCursor(int cursor) { cursors.add(cursor); }
                @Override public void onFinished(int tested, int total, String hitPassword, String outcome) {}
                @Override public void onBlocked(String title, String detail) {}
            };
            engine.run(t, cands, 0, 1000L, cb);

            info("progress=" + progress + " cursor=" + cursors);

            ok("[全INFERRED] 进度(tested) 递增到 4（显示用）",
                    !progress.isEmpty() && progress.get(progress.size() - 1) == 4,
                    "progress=" + progress);
            ok("[全INFERRED] 游标(cursor) 全程 0 —— 未排除任何候选",
                    !cursors.isEmpty() && cursors.get(cursors.size() - 1) == 0,
                    "cursor=" + cursors);
            ok("[全INFERRED] 写盘值(cursor) ≠ 显示值(tested=4)：不会跳过候选",
                    !cursors.isEmpty() && cursors.get(cursors.size() - 1) != 4,
                    "cursor 末尾=" + (cursors.isEmpty() ? "-" : cursors.get(cursors.size() - 1)));
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL [全INFERRED] 异常: " + t);
            t.printStackTrace(System.out);
        }

        // ---------- 场景 2：混合 DIRECT/INFERRED（关键回归） ----------
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
            final List<Integer> cursors = new ArrayList<>();
            CrackEngine.Listener cb = new CrackEngine.Listener() {
                @Override public void onAttemptStart(int index, int total, String candidate) {}
                @Override public void onAttemptResult(CrackEngine.Attempt a) {}
                @Override public void onProgress(int tested, int total, double rate, long etaMs) {
                    progress.add(tested);
                }
                @Override public void onCursor(int cursor) { cursors.add(cursor); }
                @Override public void onFinished(int tested, int total, String hitPassword, String outcome) {}
                @Override public void onBlocked(String title, String detail) {}
            };
            engine.run(t, cands, 0, 1000L, cb);

            info("progress=" + progress + " cursor=" + cursors);

            // 期望：cursor 推进 1（index0 DIRECT），然后钉住（index1 INFERRED）
            ok("[混合] 进度(tested) 递增到 4",
                    !progress.isEmpty() && progress.get(progress.size() - 1) == 4,
                    "progress=" + progress);
            ok("[混合] 游标钉在第一个 INFERRED（=1），不推进到 4",
                    !cursors.isEmpty() && cursors.get(cursors.size() - 1) == 1,
                    "cursor=" + cursors);
            ok("[混合] 写盘值 ≠ 显示值：续跑会从 1 重试，不跳过 #1/#3",
                    !cursors.isEmpty() && cursors.get(cursors.size() - 1) == 1
                            && progress.get(progress.size() - 1) == 4,
                    "cursor=" + cursors.get(cursors.size() - 1)
                            + " tested=" + progress.get(progress.size() - 1));
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL [混合] 异常: " + t);
            t.printStackTrace(System.out);
        }

        // ---------- 场景 3：全 DIRECT → cursor 与 tested 同步到底 ----------
        try {
            final Kind[] kinds = {Kind.DIRECT, Kind.DIRECT, Kind.DIRECT};
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

            final List<Integer> cursors = new ArrayList<>();
            CrackEngine.Listener cb = new CrackEngine.Listener() {
                @Override public void onAttemptStart(int index, int total, String candidate) {}
                @Override public void onAttemptResult(CrackEngine.Attempt a) {}
                @Override public void onProgress(int tested, int total, double rate, long etaMs) {}
                @Override public void onCursor(int cursor) { cursors.add(cursor); }
                @Override public void onFinished(int tested, int total, String hitPassword, String outcome) {}
                @Override public void onBlocked(String title, String detail) {}
            };
            engine.run(t, cands, 0, 1000L, cb);

            info("cursor=" + cursors);
            ok("[全DIRECT] 游标推进到 3（全部确定排除，续跑不重试）",
                    !cursors.isEmpty() && cursors.get(cursors.size() - 1) == 3,
                    "cursor=" + cursors);
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL [全DIRECT] 异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== 续跑游标语义测试: " + pass + " 通过, " + fail + " 失败 ===");
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
            @Override public String name() { return "cs-null"; }
        });
        return e;
    }
}
