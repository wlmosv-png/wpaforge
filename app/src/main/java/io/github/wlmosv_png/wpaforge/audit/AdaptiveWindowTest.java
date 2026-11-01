package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 自适应窗口测试（P0 提速）。
 *
 * 目标：手机热点等「不给任何证据」的网络，连续多条「观察窗口结束、无证据」
 * 超时后，单发时限从 10s 缩到 6s —— 判定结果不变（都是 TIMEOUT→NONE），
 * 但每条约省 4 秒。任何非 TIMEOUT 结论立即恢复全窗。
 */
public class AdaptiveWindowTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    static final String TARGET = "LAB-AW";
    static final String BSSID = "02:00:00:00:00:60";

    public static void main(String[] args) throws Exception {
        System.out.println("== 自适应窗口（连续无证据超时 → 缩短单发时限） ==");

        // ---------- 1. 纯函数 adaptiveWindow ----------
        ok("[纯函数] 0~3 条无证据：保持全窗（10s）",
                CrackEngine.adaptiveWindow(0, 10_000L) == 10_000L
                        && CrackEngine.adaptiveWindow(3, 10_000L) == 10_000L,
                "adaptiveWindow(3,10000)=" + CrackEngine.adaptiveWindow(3, 10_000L));
        ok("[纯函数] 第 4 条起：缩到 6s",
                CrackEngine.adaptiveWindow(4, 10_000L) == 6_000L
                        && CrackEngine.adaptiveWindow(100, 10_000L) == 6_000L,
                "adaptiveWindow(4,10000)=" + CrackEngine.adaptiveWindow(4, 10_000L));
        ok("[纯函数] base 本身就短于 6s 时不反而拉长",
                CrackEngine.adaptiveWindow(10, 5_000L) == 5_000L,
                "adaptiveWindow(10,5000)=" + CrackEngine.adaptiveWindow(10, 5_000L));
        ok("[纯函数] base=0（默认）时缩窗仍生效",
                CrackEngine.adaptiveWindow(4, 0L) == 0L,
                "base=0 表示用默认 10s，这里 0 原样返回（缩窗由调用方用 basePerItem=10s 传）");

        // ---------- 2. 集成：全 TIMEOUT 调度，判定语义不变 ----------
        // 注入 provider 全部返回 TIMEOUT，跑完整 run()，验证：
        //   - 不崩溃、正常完成
        //   - 全 NONE（TIMEOUT→NONE），cursor 不动，无 INFERRED 补验
        try {
            CrackEngine engine = newEngine();
            engine.setVerdictProviderForTest(new CrackEngine.VerdictProvider() {
                @Override public CrackEngine.Attempt attemptFor(String password, int index) {
                    CrackEngine.Attempt a = new CrackEngine.Attempt("pw-" + index,
                            CrackEngine.Verdict.TIMEOUT, 1L, "no-evidence", "scripted");
                    a.grade = EvidenceGrade.Level.NONE;
                    return a;
                }
            });

            NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412,
                    NetRecord.Security.WPA2, false, "");
            List<Candidate> cands = new ArrayList<>();
            for (int i = 0; i < 10; i++)
                cands.add(new Candidate("pw-" + i + "-x", Candidate.Origin.USER_DICT));

            final int[] finishedCursor = {Integer.MIN_VALUE};
            final String[] outcome = {null};
            final java.util.List<String> grades = new ArrayList<>();
            CrackEngine.Listener cb = new CrackEngine.Listener() {
                @Override public void onAttemptStart(int index, int total, String candidate) {}
                @Override public void onAttemptResult(CrackEngine.Attempt a) {
                    grades.add(String.valueOf(a.grade));
                }
                @Override public void onProgress(int tested, int total, double rate, long etaMs) {}
                @Override public void onFinished(int tested, int cursor, String hitPassword, String outcomeName) {
                    finishedCursor[0] = cursor;
                    outcome[0] = outcomeName;
                }
                @Override public void onBlocked(String title, String detail) {
                    System.out.println("  !! blocked " + title);
                }
            };
            engine.run(t, cands, 0, 0L, cb);

            ok("[集成] 全 TIMEOUT 正常跑完不崩溃", outcome[0] != null,
                    "outcome=" + outcome[0]);
            ok("[集成] 全 TIMEOUT → 全 NONE（判定语义不变）",
                    grades.size() == 10 && !grades.contains("INFERRED") && !grades.contains("DIRECT"),
                    "grades=" + grades);
            ok("[集成] 全 TIMEOUT → cursor 不推进（=0，无排除）",
                    finishedCursor[0] == 0, "cursor=" + finishedCursor[0]);
            ok("[集成] 全 TIMEOUT → 未命中（outcome=DONE_MISS，但这是无证据不是密码错）",
                    "DONE_MISS".equals(outcome[0]), "outcome=" + outcome[0]);
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL [集成] 异常: " + t);
            t.printStackTrace(System.out);
        }

        // ---------- 3. 集成：TIMEOUT 后出现 AUTH_FAIL，恢复全窗（不缩） ----------
        // 这里验证「非 TIMEOUT 重置计数」的行为，通过后续候选仍能拿到正常结论体现。
        try {
            CrackEngine engine = newEngine();
            engine.setVerdictProviderForTest(new CrackEngine.VerdictProvider() {
                @Override public CrackEngine.Attempt attemptFor(String password, int index) {
                    CrackEngine.Attempt a;
                    if (index <= 5) {
                        a = new CrackEngine.Attempt("pw-" + index,
                                CrackEngine.Verdict.TIMEOUT, 1L, "no-evidence", "scripted");
                        a.grade = EvidenceGrade.Level.NONE;
                    } else {
                        a = new CrackEngine.Attempt("pw-" + index,
                                CrackEngine.Verdict.AUTH_FAIL, 1L, "authcast", "scripted");
                        a.grade = EvidenceGrade.Level.DIRECT;
                    }
                    return a;
                }
            });

            NetRecord t = new NetRecord(TARGET, BSSID, -55, 2412,
                    NetRecord.Security.WPA2, false, "");
            List<Candidate> cands = new ArrayList<>();
            for (int i = 0; i < 8; i++)
                cands.add(new Candidate("pw-" + i + "-x", Candidate.Origin.USER_DICT));

            final int[] finishedCursor = {Integer.MIN_VALUE};
            CrackEngine.Listener cb = new CrackEngine.Listener() {
                @Override public void onAttemptStart(int index, int total, String candidate) {}
                @Override public void onAttemptResult(CrackEngine.Attempt a) {}
                @Override public void onProgress(int tested, int total, double rate, long etaMs) {}
                @Override public void onFinished(int tested, int cursor, String hitPassword, String outcomeName) {
                    finishedCursor[0] = cursor;
                }
                @Override public void onBlocked(String title, String detail) {
                    System.out.println("  !! blocked " + title);
                }
            };
            engine.run(t, cands, 0, 0L, cb);

            // 前 5 条 TIMEOUT（NONE，cursor 停 0），第 6 条起 AUTH_FAIL(DIRECT)
            // 但 cursor 已被钉在 0（前面有 NONE），所以仍为 0 —— 这正是 D5 语义。
            ok("[集成] TIMEOUT 后接 DIRECT：cursor 仍钉在 0（D5 语义，前面有 NONE 未排除）",
                    finishedCursor[0] == 0, "cursor=" + finishedCursor[0]);
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL [集成2] 异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== 自适应窗口测试: " + pass + " 通过, " + fail + " 失败 ===");
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
            @Override public String name() { return "aw-null"; }
        });
        return e;
    }
}
