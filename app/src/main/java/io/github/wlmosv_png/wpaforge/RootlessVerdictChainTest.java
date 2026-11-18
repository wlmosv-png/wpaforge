package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Adjudicator;
import io.github.wlmosv_png.wpaforge.audit.AttemptLedger;
import io.github.wlmosv_png.wpaforge.audit.AttemptState;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;
import io.github.wlmosv_png.wpaforge.audit.TriedSet;
import io.github.wlmosv_png.wpaforge.cap.Capability;
import io.github.wlmosv_png.wpaforge.cap.ConnectChannel;
import io.github.wlmosv_png.wpaforge.cap.RootlessChannel;

import java.util.ArrayList;
import java.util.List;

/**
 * 无 Root 模式下的判定链行为门禁。
 *
 * <h3>要钉住的事只有一件</h3>
 * 「没能力执行」**不能变成任何形式的失败结论**。具体拆成六条（对应任务书
 * 「判定链专项门禁」）：
 * <ol>
 *   <li>没有无线证据时不能判密码错；</li>
 *   <li>无 Root 不能把「权限不足」判成「目标认证失败」；</li>
 *   <li>连接成功不能冒充 WPS PIN 验证成功；</li>
 *   <li>不完整日志不能产生 DIRECT 级证据；</li>
 *   <li>不允许错误写入 TriedSet；</li>
 *   <li>不允许无证据推进任务游标。</li>
 * </ol>
 *
 * <p>本类全部是纯逻辑（不碰设备），但走的是**生产代码**的裁决与记账路径，
 * 不是另写一套断言 —— 只有这样才能证明改的是那条链本身。
 */
public class RootlessVerdictChainTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        RootlessVerdictChainTest t = new RootlessVerdictChainTest();
        t.run();
        System.out.println(t.pass + " 通过 / " + t.fail + " 失败");
        if (t.fail > 0) System.exit(1);
    }

    private void ok(String name, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private void run() {
        notCapableGate();
        adjudicatorRules();
        triedSetContract();
        cursorContract();
        noFakeSuccess();
        wpsSeparation();
        modeIsolation();
        channelContract();
    }

    // ---------------------------------------------------------------- 1. 能力闸本身

    private void notCapableGate() {
        // NOT_CAPABLE 必须存在，且**不能**被判成闸门通过
        AttemptState st = new AttemptState("run#1", CrackEngine.Lifecycle.UNKNOWN,
                AttemptState.Gate.NOT_CAPABLE, false);
        ok("NOT_CAPABLE 不是 gateOk", !st.gateOk());

        CrackEngine.Attempt a = Adjudicator.gateBlocked("12345678", "run#1",
                AttemptState.Gate.NOT_CAPABLE, 12L, "无 Root 模式无法逐条关联");
        ok("NOT_CAPABLE → NOT_EXECUTED（不是 UNKNOWN）",
                a.verdict == CrackEngine.Verdict.NOT_EXECUTED);
        ok("NOT_CAPABLE → 证据等级 NONE", a.grade == EvidenceGrade.Level.NONE);
        ok("NOT_CAPABLE → 归类码 not-capable", "not-capable".equals(a.reason));
        ok("NOT_CAPABLE → 不产证据级", !EvidenceGrade.persistable(a.grade));
        ok("NOT_CAPABLE → 不推进游标", !EvidenceGrade.advancesCursor(a.grade));
        // 与「旧配置没删净」必须能被区分（否则文案会撒谎）
        CrackEngine.Attempt b = Adjudicator.gateBlocked("12345678", "run#1",
                AttemptState.Gate.STALE_CONFIG, 12L, "旧配置未清除");
        ok("NOT_CAPABLE 与 STALE_CONFIG 归类可区分", !a.reason.equals(b.reason));
        ok("NOT_CAPABLE 判词不冒充失败", !a.detail.contains("密码错")
                && !a.detail.contains("认证失败"));

        // gateBlocked 仍拒绝 OK（老契约不变）
        boolean threw = false;
        try {
            Adjudicator.gateBlocked("x", "run#1", AttemptState.Gate.OK, 1L, "");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        ok("gateBlocked 仍拒绝 OK", threw);

        // 裁决器对 NOT_CAPABLE 走规则 0a，即使塞进再强的证据也不能翻案
        io.github.wlmosv_png.wpaforge.audit.Evidence wrongKey =
                new io.github.wlmosv_png.wpaforge.audit.Evidence(
                        io.github.wlmosv_png.wpaforge.audit.Evidence.Source.WPALOG_A,
                        io.github.wlmosv_png.wpaforge.audit.Evidence.Claim.WRONG_KEY,
                        io.github.wlmosv_png.wpaforge.audit.Evidence.Attribution.AT3,
                        "run#1", 0L);
        List<io.github.wlmosv_png.wpaforge.audit.Evidence> ev = new ArrayList<>();
        ev.add(wrongKey);
        Adjudicator.Result r = Adjudicator.adjudicate(st, ev);
        ok("NOT_CAPABLE 下证据不得翻案为 AUTH_FAIL",
                r.verdict != CrackEngine.Verdict.AUTH_FAIL);
        ok("NOT_CAPABLE 下证据也不得翻案为成功",
                r.verdict != CrackEngine.Verdict.SUCCESS
                        && r.verdict == CrackEngine.Verdict.NOT_EXECUTED);
        ok("NOT_CAPABLE 下证据不得翻案为证据级", r.grade == EvidenceGrade.Level.NONE);
    }

    // ---------------------------------------------------------------- 2. 裁决表老规则不回退

    private void adjudicatorRules() {
        // 规则 0b 仍有效：STALE_CONFIG 下强证据也不采信
        AttemptState stale = new AttemptState("run#2", CrackEngine.Lifecycle.TERMINATED,
                AttemptState.Gate.STALE_CONFIG, true);
        List<io.github.wlmosv_png.wpaforge.audit.Evidence> ev = new ArrayList<>();
        ev.add(new io.github.wlmosv_png.wpaforge.audit.Evidence(
                io.github.wlmosv_png.wpaforge.audit.Evidence.Source.WPALOG_A,
                io.github.wlmosv_png.wpaforge.audit.Evidence.Claim.WRONG_KEY,
                io.github.wlmosv_png.wpaforge.audit.Evidence.Attribution.AT3,
                "run#2", 0L));
        Adjudicator.Result r = Adjudicator.adjudicate(stale, ev);
        ok("STALE_CONFIG 仍是闸门失败", r.verdict == CrackEngine.Verdict.UNKNOWN);
        ok("STALE_CONFIG 归类码保持 gate- 前缀", r.reason.startsWith("gate-"));

        // 正常闸门 + 空证据：只能是 UNKNOWN/INCOMPLETE，绝不能是 AUTH_FAIL
        AttemptState okState = new AttemptState("run#3", CrackEngine.Lifecycle.UNKNOWN,
                AttemptState.Gate.OK, false);
        Adjudicator.Result r2 = Adjudicator.adjudicate(okState,
                java.util.Collections.<io.github.wlmosv_png.wpaforge.audit.Evidence>emptyList());
        ok("无证据不判 AUTH_FAIL", r2.verdict != CrackEngine.Verdict.AUTH_FAIL);
        ok("无证据不产证据级", r2.grade == EvidenceGrade.Level.NONE);
    }

    // ---------------------------------------------------------------- 3. TriedSet 契约

    private void triedSetContract() {
        // 契约来源：CrackService 里「只有 AUTH_FAIL ∧ persistable 才写」，SUCCESS 显式排除
        ok("根契约：只有 DIRECT 可持久化",
                EvidenceGrade.persistable(EvidenceGrade.Level.DIRECT));
        ok("根契约：INFERRED 不可持久化",
                !EvidenceGrade.persistable(EvidenceGrade.Level.INFERRED));
        ok("根契约：NONE 不可持久化",
                !EvidenceGrade.persistable(EvidenceGrade.Level.NONE));
        ok("根契约：只有 DIRECT 推进游标",
                EvidenceGrade.advancesCursor(EvidenceGrade.Level.DIRECT));
        ok("根契约：INFERRED 不推进游标",
                !EvidenceGrade.advancesCursor(EvidenceGrade.Level.INFERRED));

        // NOT_CAPABLE 的结论不能落进这两条路径
        CrackEngine.Attempt a = Adjudicator.gateBlocked("00000000", "run#4",
                AttemptState.Gate.NOT_CAPABLE, 5L, "no capability");
        boolean wouldPersist = a.verdict == CrackEngine.Verdict.AUTH_FAIL
                && EvidenceGrade.persistable(a.grade);
        boolean wouldAdvance = EvidenceGrade.advancesCursor(a.grade);
        ok("无能力结论不写 TriedSet", !wouldPersist);
        ok("无能力结论不推进游标", !wouldAdvance);

        // 源码守卫：写 TriedSet 的唯一出口必须同时要求 AUTH_FAIL 与 persistable
        String svc = readSource("CrackService.java");
        ok("CrackService 里 TriedSet 写入仍带 persistable 判据",
                svc.contains("EvidenceGrade.persistable(a.grade)"));
        ok("CrackService 里 SUCCESS 仍被显式排除",
                svc.contains("a.verdict == CrackEngine.Verdict.SUCCESS"));
    }

    // ---------------------------------------------------------------- 4. 游标契约

    private void cursorContract() {
        // 账本分级：只有「已确认错误」进账，其余不记
        // 「已确认错误」的唯一入口是 AUTH_FAIL + DIRECT；其余一律不是它
        ok("账本 classify(AUTH_FAIL, DIRECT) = CONFIRMED_WRONG",
                AttemptLedger.Level.classify(CrackEngine.Verdict.AUTH_FAIL,
                        EvidenceGrade.Level.DIRECT) == AttemptLedger.Level.CONFIRMED_WRONG);
        ok("账本 classify(UNKNOWN, NONE) 不是已确认错误",
                AttemptLedger.Level.classify(CrackEngine.Verdict.UNKNOWN,
                        EvidenceGrade.Level.NONE) != AttemptLedger.Level.CONFIRMED_WRONG);
        ok("账本 classify(AUTH_FAIL, INFERRED) 不是已确认错误",
                AttemptLedger.Level.classify(CrackEngine.Verdict.AUTH_FAIL,
                        EvidenceGrade.Level.INFERRED) != AttemptLedger.Level.CONFIRMED_WRONG);
        ok("账本 SUCCESS 不记",
                AttemptLedger.Level.classify(CrackEngine.Verdict.SUCCESS,
                        EvidenceGrade.Level.DIRECT) == null);

        // 源码守卫：引擎的能力闸必须在**第一条命令之前**（在 forget 之前）
        String eng = readSource("audit/CrackEngine.java");
        int gateAt = eng.indexOf("AttemptState.Gate.NOT_CAPABLE");
        int forgetAt = eng.indexOf("forgetTargetByShell(target.ssid)");
        ok("能力闸的代码存在", gateAt > 0);
        ok("能力闸写在 forget 之前", gateAt > 0 && forgetAt > 0 && gateAt < forgetAt);
        // 2026-11-18 真机补丁：批量开跑前也必须挡一次 ——
        // 否则「一条命令都不发」的字典会在几秒内「跑完」，界面显示跑过一轮。
        int runGateAt = eng.indexOf("if (!canAttemptAssociation()) {");
        int channelProbeAt = eng.indexOf("ChannelProbe probe = probeChannel();");
        ok("run() 开跑前也有能力闸", runGateAt > 0);
        ok("开跑前的闸在通道自检之前", runGateAt > 0 && channelProbeAt > 0
                && runGateAt < channelProbeAt);
    }

    // ---------------------------------------------------------------- 5. 不许伪造成功

    private void noFakeSuccess() {
        // 无 Root 模式下不可能产出 SUCCESS：任何「成功」都必须走 Adjudicator，
        // 而它给出的 SUCCESS 必须带 DIRECT 且具备连接证据。
        String eng = readSource("audit/CrackEngine.java");
        ok("引擎里 SUCCESS 只经 judge 产出（不再直接 new Attempt(SUCCESS)）",
                !eng.contains("new Attempt(password, Verdict.SUCCESS"));
        // 判定链的成功出口必须标 AT3（唯一归属）
        ok("成功出口仍要求 AT3 归属",
                eng.contains("Evidence.Attribution.AT3"));

        // 无能力时，即使「系统当前连着目标」也不能当成本次成功
        // —— 这一点由闸门保证：NOT_CAPABLE 在取任何证据之前返回
        ok("能力闸在取证据之前", eng.indexOf("Gate.NOT_CAPABLE")
                < eng.indexOf("SuccessRule.assess"));
    }

    // ---------------------------------------------------------------- 6. WPS 与连接分离

    private void wpsSeparation() {
        // WPS 命中判定不看「连接是否成功」
        String judge = readSource("wps/WpsHitJudge.java");
        ok("WPS 判定有终局成功事实", judge.contains("F_WPS_TERMINAL_SUCCESS"));
        ok("WPS 终局成功当前恒 false（不伪造）",
                judge.contains("terminalSuccessKnown(In in)") && judge.contains("return false"));
        // 连接链与 WPS 链各用各的证据类型
        ok("WPS 判定不引用 CrackEngine.Verdict",
                !judge.contains("CrackEngine.Verdict"));
        String eng = readSource("audit/CrackEngine.java");
        ok("连接链不引用 WPS 命中结论",
                !eng.contains("WpsHitJudge") && !eng.contains("HIT_CONFIRMED"));
    }

    // ---------------------------------------------------------------- 7. 模式隔离

    private void modeIsolation() {
        // TriedSet / 账本必须按模式隔离，否则无 Root 的弱结论会污染 Root 模式
        String rootKey = TriedSet.targetKey("28:93:7d:41:cc:39", "B212", "root");
        String rlKey = TriedSet.targetKey("28:93:7d:41:cc:39", "B212", "rootless");
        String legacyKey = TriedSet.targetKey("28:93:7d:41:cc:39", "B212");

        // Root 模式的键必须与改造前逐字一致 —— 否则老用户的历史会「凭空消失」
        ok("Root 模式键与旧键一致", rootKey.equals(legacyKey));
        ok("无 Root 模式键与 Root 不同", !rlKey.equals(rootKey));
        ok("旧键本身不带前缀", !legacyKey.contains("~"));
        ok("无 Root 键带模式前缀", rlKey.startsWith("rootless~"));
        ok("剥离前缀可还原", TriedSet.stripScope(rlKey).equals(legacyKey));

        // 账本与 tried 共用同一个键 → 天然同源隔离
        ok("账本文件名由同一键导出",
                AttemptLedger.fileNameOf(rlKey).equals(rlKey + ".ledger"));
        ok("两个存储的键不同源就不会同源隔离",
                !AttemptLedger.fileNameOf(rlKey).equals(AttemptLedger.fileNameOf(rootKey)));

        // SSID 缺失时也不能串号
        ok("无 BSSID 时两模式仍分开",
                !TriedSet.targetKey(null, "X", "root")
                        .equals(TriedSet.targetKey(null, "X", "rootless")));

        // 源码守卫：服务端取键时必须带模式
        String svc = readSource("CrackService.java");
        ok("CrackService 取键时传入了运行模式",
                svc.contains("TriedSet.targetKey(task.bssid, task.ssid,"));
        ok("账本键仍与 tried 键同源", svc.contains("ledgerKey = triedKey"));
    }

    // ---------------------------------------------------------------- 8. 通道契约

    private void channelContract() {
        ConnectChannel rl = new RootlessChannel();
        ok("无 Root 通道不能关联", !rl.canAssociate());
        ok("无 Root 通道不能净检", !rl.canForget());
        ok("无 Root 通道关联恒 false", !rl.associate("X", "wpa2", "12345678", null, false));
        ok("无 Root 通道净检恒 false", !rl.forgetTarget("X"));
        ok("无 Root 通道读不到状态（null 而不是空串）", rl.readState() == null);
        ok("无 Root 通道读不到网络列表", rl.listSavedNetworks() == null);
        ok("无 Root 通道读不到日志", rl.readSupplicantLog() == null);

        Capability.Set0 s = rl.state();
        for (String id : new String[]{Capability.C_WIFI_CONNECT, Capability.C_WIFI_FORGET,
                Capability.C_WPA_LOG, Capability.C_WPS_PIN}) {
            ok("无 Root 下 " + id + " 不是证据级", !s.get(id).evidenceGrade());
        }
        ok("无 Root 下证据级集合不含关联/净检/日志/WPS",
                !s.evidenceGradeIds().contains(Capability.C_WIFI_CONNECT)
                        && !s.evidenceGradeIds().contains(Capability.C_WIFI_FORGET)
                        && !s.evidenceGradeIds().contains(Capability.C_WPA_LOG)
                        && !s.evidenceGradeIds().contains(Capability.C_WPS_PIN));

        // 引擎侧：未接通道时保持旧行为（不因为新增闸门挡死老调用点）
        // 未接通道时（改造前的调用形态）不能因为新增闸门而被挡死
        CrackEngine shellOnly = shellEngine();
        ok("只接 shell、未接能力层 → 按旧行为放行", shellOnly.canAttemptAssociation());
        // 真实代码路径：闸门放行 = null
        ok("只接 shell → 闸门放行（返回 null）",
                ReflectUtil.probeGate(shellOnly, "12345678", "run#9") == null);

        CrackEngine e = shellEngine();
        e.setConnectChannel(rl);
        ok("接入无 Root 通道后 canAttempt=false", !e.canAttemptAssociation());
        ok("无能力原因非空", !e.inabilityReason(true).isEmpty());
        // 真实代码路径：闸门挡住 = NOT_CAPABLE 的 UNKNOWN
        CrackEngine.Attempt blocked = ReflectUtil.probeGate(e, "12345678", "run#9");
        ok("无 Root 下闸门真的挡住了（非 null）", blocked != null);
        ok("挡住的结论是 NOT_EXECUTED", blocked != null
                && blocked.verdict == CrackEngine.Verdict.NOT_EXECUTED);
        ok("挡住的结论不产证据级", blocked != null
                && blocked.grade == EvidenceGrade.Level.NONE);
        ok("挡住的归类码是 not-capable", blocked != null
                && "not-capable".equals(blocked.reason));
        ok("挡住的判词点名「无法执行」", blocked != null
                && (blocked.detail.contains("无法执行") || blocked.detail.contains("cannot")));
        // 挡住时必须**一条命令都没发** —— 用会抛异常的通道验证
        CrackEngine spy = shellEngine();
        spy.setShellChannel(new CrackEngine.ShellChannel() {
            @Override
            public String exec(String cmd, long timeoutMs) {
                throw new AssertionError("无 Root 模式下不应发出任何命令：" + cmd);
            }

            @Override
            public String name() {
                return "Spy";
            }
        });
        spy.setConnectChannel(rl);
        CrackEngine.Attempt noCmd = ReflectUtil.probeGate(spy, "12345678", "run#9");
        ok("挡住时没有发出任何命令", noCmd != null
                && noCmd.verdict == CrackEngine.Verdict.NOT_EXECUTED);
        // 新增：这条结论绝不能进「无结论」统计，否则界面会说「336 条没结论」
        ok("未执行不进 inconclusive 统计",
                countInconclusive(CrackEngine.Verdict.NOT_EXECUTED) == 0);
        ok("未执行不计入 executed",
                countExecuted(CrackEngine.Verdict.NOT_EXECUTED) == 0);
        ok("未执行单列在 notExecuted",
                countNotExecuted(CrackEngine.Verdict.NOT_EXECUTED) == 1);
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 构造测试引擎。
     *
     * <p>两个构造器都不可直接调用（{@code CrackEngine(int)} 是包级可见、
     * 带 Context 的那个会去碰系统服务），而日志里又明确说过不许改它们。
     * 所以用反射拿包级构造 —— 只为**构造**，构造出来的引擎跑的是同一份生产代码。
     */
    private static final class ReflectUtil {
        static CrackEngine newEngine(int mode) {
            try {
                java.lang.reflect.Constructor<?> c =
                        CrackEngine.class.getDeclaredConstructor(int.class);
                c.setAccessible(true);
                return (CrackEngine) c.newInstance(mode);
            } catch (Throwable t) {
                throw new IllegalStateException("无法构造 CrackEngine: " + t, t);
            }
        }

        static CrackEngine.Attempt probeGate(CrackEngine e, String pw, String id) {
            try {
                java.lang.reflect.Method m = CrackEngine.class.getDeclaredMethod(
                        "probeCapabilityGateForTest", String.class, String.class);
                m.setAccessible(true);
                return (CrackEngine.Attempt) m.invoke(e, pw, id);
            } catch (Throwable t) {
                throw new IllegalStateException("无法调用能力闸探针: " + t, t);
            }
        }
    }

    private int countInconclusive(CrackEngine.Verdict v) {
        io.github.wlmosv_png.wpaforge.audit.VerdictTally t =
                new io.github.wlmosv_png.wpaforge.audit.VerdictTally();
        t.add(v, EvidenceGrade.defaultOf(v));
        return t.inconclusive();
    }

    private int countExecuted(CrackEngine.Verdict v) {
        io.github.wlmosv_png.wpaforge.audit.VerdictTally t =
                new io.github.wlmosv_png.wpaforge.audit.VerdictTally();
        t.add(v, EvidenceGrade.defaultOf(v));
        return t.executed();
    }

    private int countNotExecuted(CrackEngine.Verdict v) {
        io.github.wlmosv_png.wpaforge.audit.VerdictTally t =
                new io.github.wlmosv_png.wpaforge.audit.VerdictTally();
        t.add(v, EvidenceGrade.defaultOf(v));
        return t.notExecuted();
    }

    /** 只接 shell、不接能力层的引擎（= 改造前的调用形态）。 */
    private CrackEngine shellEngine() {
        CrackEngine e = ReflectUtil.newEngine(0);
        e.setShellChannel(new CrackEngine.ShellChannel() {
            @Override
            public String exec(String cmd, long timeoutMs) {
                return "";
            }

            @Override
            public String name() {
                return "UnitTest";
            }
        });
        return e;
    }

    private String readSource(String rel) {
        try {
            java.io.File f = new java.io.File(io.github.wlmosv_png.wpaforge.TestPaths.root()
                    + "/app/src/main/java/io/github/wlmosv_png/wpaforge/" + rel);
            if (!f.isFile()) return "";
            return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
