package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AttemptId;
import io.github.wlmosv_png.wpaforge.audit.BackoffPolicy;
import io.github.wlmosv_png.wpaforge.audit.AttemptMarker;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.FailoverRule;
import io.github.wlmosv_png.wpaforge.audit.StatusParser;
import io.github.wlmosv_png.wpaforge.audit.SuccessRule;
import io.github.wlmosv_png.wpaforge.audit.SupplicantParser;
import io.github.wlmosv_png.wpaforge.audit.WifiAuthFailureWatcher;
import io.github.wlmosv_png.wpaforge.audit.WpaLogReader;
import io.github.wlmosv_png.wpaforge.ui.Redact;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 判定链审计探针 · 第 2 轮（R3：终结条件 / 事件归属 / 提前结案出口）。
 *
 * <p><b>审计工具标记（AUDIT ONLY）</b>：<br>
 * · 不纳入 `wpf-gate.sh` 的正式门禁（文件名刻意不叫 `*Test.java`）；<br>
 * · <b>不执行任何真实 Wi-Fi 认证尝试</b>，只跑合成字符串与纯逻辑函数；<br>
 * · 不修改生产判定逻辑；保留此文件是为了让报告里的每条 VIOLATION 可被独立复现。<br>
 * 文件内的目标 SSID / BSSID / 口令向量**全部是合成值**，不含任何真实环境标识。
 *
 * <h3>为什么文件名不是 *Test.java</h3>
 * {@code wpf-gate.sh} 用 {@code find -name '*Test.class'} 收集测试类。
 * 本探针**故意沿用当前行为、并把契约违背项标成 VIOLATION 而不判红** ——
 * 它的任务是「把缺陷变成可复现的证据」，不是「维持绿灯」。
 * 生产判定逻辑一行不改；等修复方案获批后，这些用例再逐个转成
 * {@code R3ContractTest.java} 里的断言（那时才该判红）。
 *
 * <h3>方法的诚实边界（必读）</h3>
 * <ul>
 *   <li><b>EXECUTED</b>：直接调用生产代码里的纯逻辑函数
 *       （{@link FailoverRule} / {@link SuccessRule} / {@link WpaLogReader} /
 *       {@link StatusParser} / {@link SupplicantParser} /
 *       {@link WifiAuthFailureWatcher} 的静态判据 / {@link CrackEngine} 的静态判据、
 *       {@link Redact}）。结论是「跑出来的」。</li>
 *   <li><b>TRANSCRIBED</b>：{@link Loop} 是 {@code CrackEngine.tryOneShell}
 *       判定顺序的**人工转写**（每步标注源码行号）。它不在生产路径上，
 *       只用来让「顺序/时序带来的后果」可执行、可复现。
 *       凡结论来自它，报告里必须标注「模型推断」，不得说成「已在设备上验证」。</li>
 *   <li><b>SCANNED</b>：对源码文本做的静态计数（例如「明文口令进日志」的调用点数量）。
 *       结论是「源码里数出来的」，不是运行结果。</li>
 * </ul>
 *
 * <h3>安全边界</h3>
 * 不发起任何真实 Wi-Fi 认证尝试、不连接任何网络、不触碰设备状态。
 * 全部输入是合成字符串。默认退出码 0（报告模式）；加 {@code --strict}
 * 时只要出现 VIOLATION 就退出 1，便于将来接进门禁。
 */
public final class R3AuditProbe {

    // ---------------------------------------------------------------- 计数
    private static int pass = 0, viol = 0, notRun = 0;
    private static final java.util.List<String> violations = new java.util.ArrayList<>();

    private static void ok(String name) {
        pass++;
        System.out.println("  OK    " + name);
    }

    private static void ok(String id, String name, boolean cond) {
        if (cond) ok(name);
        else violation(id, name, "断言不成立（期望为真）");
    }

    private static void violation(String id, String name, String detail) {
        viol++;
        violations.add(id + " " + name);
        System.out.println("  VIOL  [" + id + "] " + name);
        if (detail != null && !detail.isEmpty()) System.out.println("        → " + detail);
    }

    private static void notRun(String name, String why) {
        notRun++;
        System.out.println("  N/RUN " + name + "（" + why + "）");
    }

    private static void note(String label, Object v) {
        System.out.println("  note  " + label + " = " + v);
    }

    private static void section(String s) {
        System.out.println();
        System.out.println("== " + s + " ==");
    }

    // ---------------------------------------------------------------- 合成素材
    /**
     * 模型里"本条候选"的固定 attemptId 与其标记行。
     *
     * <p>用来让**生产函数**（{@link AttemptMarker#sliceAfter} /
     * {@link AttemptMarker#evaluate}）真正参与模型推演，而不是在模型里另写一套。
     */
    private static final String MID = AttemptId.of(
            AttemptId.newRunId(1_764_000_000_000L, "LAB-TARGET", 0), 1);
    private static String mk() { return AttemptMarker.line(MID); }

    private static final String TARGET = "LAB-TARGET";
    private static final String TARGET_BSSID = "02:00:00:00:00:0a";

    /** 未连接 */
    private static final String ST_OFF = "Wifi is not connected\n";
    /** 连着别的已保存网络（COMPLETED） */
    private static final String ST_OTHER = "Wifi is connected to \"HomeWiFi\"\n"
            + "WifiInfo: SSID: \"HomeWiFi\", BSSID: aa:bb:cc:dd:ee:ff, "
            + "IP: /192.168.1.9, Supplicant state: COMPLETED, Net ID: 7\n";
    /** 目标已连上（三条件同行成立） */
    private static String stTarget(long netId) {
        return "Wifi is connected to \"" + TARGET + "\"\n"
                + "WifiInfo: SSID: \"" + TARGET + "\", BSSID: " + TARGET_BSSID + ", "
                + "IP: /192.168.1.3, Supplicant state: COMPLETED, Net ID: " + netId + "\n";
    }
    /** 目标正在关联（握手未完成） */
    private static final String ST_TARGET_ASSOC = "Wifi is connected to \"" + TARGET + "\"\n"
            + "WifiInfo: SSID: \"" + TARGET + "\", BSSID: " + TARGET_BSSID + ", "
            + "IP: /0.0.0.0, Supplicant state: ASSOCIATING, Net ID: 613\n";

    private static String logLine(String s) { return s + "\n"; }

    private static String m1(long i) {
        return "wlan0: RSN: RX message 1 of 4-Way Handshake from " + TARGET_BSSID
                + " (ver=2) [seq=" + i + "]\n";
    }

    private static String negotiated() {
        return "wlan0: WPA: Key negotiation completed with " + TARGET_BSSID + "\n";
    }

    private static String assoc() {
        return "wlan0: Trying to associate with SSID '" + TARGET + "'\n";
    }

    private static String wrongKey() {
        return "wlan0: CTRL-EVENT-SSID-TEMP-DISABLED ssid=\"" + TARGET
                + "\" bssid=" + TARGET_BSSID + " reason=WRONG_KEY\n";
    }

    // ================================================================ main
    public static void main(String[] args) throws Exception {
        boolean strict = false;
        String projectRoot = "/workspace/promo-wpaforge";
        for (String a : args) {
            if ("--strict".equals(a)) strict = true;
            else if (!a.startsWith("--")) projectRoot = a;
        }
        root = projectRoot;

        System.out.println("=== WPAForge 判定链审计探针 · R3 ===");
        System.out.println("目标（合成）: " + TARGET + " / bssid " + TARGET_BSSID);
        System.out.println("生产判定逻辑：本次未做任何修改（探针只读调用纯逻辑函数）");

        s1_event_sources();
        s2_failover_guard();
        s3_log_channel_attribution();
        s4_time_criteria();
        s5_terminal_uniqueness();
        s6_stale_task_events();
        s7_redaction(projectRoot);
        s8_diagnostics_truth(projectRoot);

        System.out.println();
        System.out.println("=== 汇总: " + pass + " OK / " + viol + " VIOLATION / "
                + notRun + " NOT-RUN ===");
        if (!violations.isEmpty()) {
            System.out.println("违背契约的项：");
            for (String v : violations) System.out.println("  · " + v);
        }
        if (strict && viol > 0) System.exit(1);
    }

    // ================================================================ S1
    /** 事件来源、能否排序、能否归属到某条候选。 */
    private static void s1_event_sources() {
        section("S1 事件来源 / 排序能力 / 归属粒度");

        // 来源 1：cmd wifi status（每轮轮询）。纯逻辑可执行。
        SupplicantParser.Info onTarget = SupplicantParser.parse(stTarget(613));
        ok("S1-b","status 源：三条件可同行取得（ssid/state/ip）", onTarget.raw
                && TARGET.equals(onTarget.ssid)
                && "COMPLETED".equals(onTarget.state)
                && "192.168.1.3".equals(onTarget.ip));
        note("SupplicantParser.Info", onTarget);

        // 来源 2：wpa_supplicant 日志。注意：解析结果**没有时间戳**。
        java.util.List<WpaLogReader.LogEvent> evs =
                WpaLogReader.parse(assoc() + m1(1) + negotiated());
        boolean allZeroAt = true;
        for (WpaLogReader.LogEvent e : evs) if (e.at != 0) allZeroAt = false;
        ok("S1-a", "日志源：每行能解析成事件", evs.size() == 3);
        boolean atConsumed = false;
        for (File f : javaFiles(root + "/app/src/main/java/io/github/wlmosv_png/wpaforge")) {
            String fn = f.getName();
            if (fn.equals("R3AuditProbe.java") || fn.endsWith("Test.java")) continue;
            String t = readFile(f.getAbsolutePath());
            if (t == null) continue;
            for (String line : t.split("\n")) {
                if (line.contains("LogEvent") && line.contains(".at") && !line.contains("static")) atConsumed = true;
            }
        }
        if (allZeroAt && !atConsumed) {
            violation("S1-1", "日志事件既无时间戳、也没有任何调用点消费时间戳",
                    "1 参 parse() 走 now=0（全部 at==0），而全仓没有任何生产代码读取 LogEvent.at → "
                            + "日志事件无法与 status/广播事件做时序比较，"
                            + "也无法证明「这一行属于第几条候选」");
        } else {
            ok("日志事件带时间戳");
        }
        note("parse(String).at 全部为 0", allZeroAt);

        // 来源 3：认证失败广播。只保留「最后一次」，无队列、无候选维度。
        ok("S1-c","广播源：判据是纯函数", WifiAuthFailureWatcher.authErrorImpliesWrongKey(2000, 1000, false));
        ok("S1-d","广播源：成功证据可压制它", !WifiAuthFailureWatcher.authErrorImpliesWrongKey(2000, 1000, true));
        // 已缓解（R3）：广播判据现在另加「本条确实被尝试过」前置（exercised），
        // 因此"上一条候选的广播落在本条窗口 + 本条根本没执行"这条最危险的组合被堵住。
        // 但广播本身仍无候选维度（只有 lastErrorAt/lastErrorSsid），故仍登记为残余。
        violation("S1-2", "广播源仍按「最近一次」取值（已加 exercise 前置缓解）",
                "WifiAuthFailureWatcher 只有 lastErrorAt/lastErrorSsid 两个 volatile；"
                        + "归属 = errorAt > commandAt + 可选 SSID 文本（读不到 SSID 时不过滤）"
                        + " + 本条已 exercise。仍无法把广播精确绑到某条候选（残余）");

        // 来源 4：ConnectivityManager 回调（NetworkWatcher）—— 已不参与判定。
        violation("S1-3", "判定已不读 watcher，但日志仍称「判定完全依赖系统回调」",
                "firstAvailableAfter 的唯一调用点在 verifyHit（CrackEngine:1510），"
                        + "而 verifyHit 全仓无调用者；run() 却打印"
                        + "「判定完全依赖系统回调…若回调拿不到 SSID，所有密码都会被判为无法确认」"
                        + "→ 用户据这条说明排查会走错方向");

        // 已修复（R6 接线）：不再是「缺失」，而是「必须有」——接线被删即判红。
        boolean wired = engineSrc().contains("AttemptId.newRunId(")
                && engineSrc().contains("AttemptId.of(currentRunId, cursor)")
                && engineSrc().contains("verdictTag(a.attemptId, cursor");
        ok("S1-4", "尝试标识已接线（runId/attemptId 进判词；接线被删即红）", wired);
    }

    // ================================================================ S2
    /** 回连（failover）判据：护栏是不是真的护栏。 */
    private static void s2_failover_guard() {
        section("S2 回连判据（FailoverRule）与它的 leftBaseline 护栏");

        // 2.1 纯函数本身的边界（EXECUTED）
        ok("S2-a","leftBaseline=false → 不算回连",
                !FailoverRule.looksLikeFailover(TARGET, "HomeWiFi", "COMPLETED", false, 5000));
        ok("S2-b","距今 2.4s（< 2.5s）→ 不算回连",
                !FailoverRule.looksLikeFailover(TARGET, "HomeWiFi", "COMPLETED", true, 2400));
        ok("S2-c","目标本身 → 不算回连",
                !FailoverRule.looksLikeFailover(TARGET, TARGET, "COMPLETED", true, 5000));
        ok("S2-d","其它网络但未 COMPLETED → 不算回连",
                !FailoverRule.looksLikeFailover(TARGET, "HomeWiFi", "ASSOCIATING", true, 5000));
        ok("S2-e","其它网络 + COMPLETED + 2.5s → 判为回连（即 AUTH_FAIL）",
                FailoverRule.looksLikeFailover(TARGET, "HomeWiFi", "COMPLETED", true, 2500));

        // 2.2 护栏的实际语义（TRANSCRIBED + EXECUTED）
        // 源码 CrackEngine:1125-1133
        //   onOtherNetwork = ssid 非空 && != target && state==COMPLETED
        //   if (!onOtherNetwork) leftBaseline = true;
        boolean left = transcribeLeftBaseline(new String[]{ST_OFF, ST_OFF});
        note("命令时未连接（status=\"Wifi is not connected\"）→ leftBaseline", left);
        // 已修复（R3）：leftBaseline 依然**不是**「离开过命令前那条连接」的证明，
        // 但回连判据现在另有归属前置（attemptExercised）与成功证据抢答闸，
        // 「护栏空转」不再直接导致错误结论。这里钉住修复后的契约本身。
        ok("S2-1", "无 exercise 证据时回连判据一律不成立（护栏空转不再致错）",
                left && !FailoverRule.looksLikeFailover(TARGET, "HomeWiFi", "COMPLETED",
                        left, 5000, false, false));
        note("残余（设计取舍，非违规）", "leftBaseline 仍是弱观测；归属改由"
                + " attemptExercised（命令生效 / 见过目标）承担");

        // 2.3 最坏组合：命令根本没生效 + 系统回连自家网络 → 该候选被记为「密码错」
        Result r = Loop.run(TARGET, TARGET_BSSID, 10_000L, -1L,
                new Round(0, ST_OFF, null),
                new Round(500, ST_OFF, ""),
                Round.w(3000, ST_OTHER, ""),
                Round.w(6000, ST_OTHER, ""));
        note("合成场景（命令被静默丢弃 + 3s 后回连自家网络）判定", r);
        ok("S2-2", "「本条候选从未被尝试」不再被判成 AUTH_FAIL（改为 not-attempted/ERROR）",
                r.verdict == CrackEngine.Verdict.ERROR
                        && r.why.contains("not-attempted"));

        // 2.4 同一轮内 AUTH_FAIL 出口排在 SUCCESS 出口之前
        Result r2 = Loop.run(TARGET, TARGET_BSSID, 10_000L, -1L,
                new Round(0, ST_OFF, ""),
                Round.w(3000, ST_OTHER, assoc() + m1(1) + negotiated()));
        note("同一轮同时含「回连证据」与「目标握手完成日志」", r2);
        ok("S2-3", "同轮内成功证据优先，失败判据不得抢答",
                r2.verdict == CrackEngine.Verdict.SUCCESS && "wpalog".equals(r2.by));
    }

    /** 转写 CrackEngine:1125-1133 的 leftBaseline 推导。 */
    private static boolean transcribeLeftBaseline(String[] roundStatus) {
        boolean leftBaseline = false;
        for (String st : roundStatus) {
            SupplicantParser.Info info = SupplicantParser.parse(st);
            boolean onOtherNetwork = info.ssid != null && !info.ssid.isEmpty()
                    && !TARGET.equals(info.ssid)
                    && info.state != null && "COMPLETED".equalsIgnoreCase(info.state);
            if (!onOtherNetwork) leftBaseline = true;
        }
        return leftBaseline;
    }

    // ================================================================ S3
    /** 日志通道的归属前提：logcat -c 是唯一隔离手段，且从不校验。 */
    private static void s3_log_channel_attribution() {
        section("S3 日志通道的归属前提（logcat -c）");

        // 3.1 陈旧缓冲会直接产出「密码错」
        String stale = assoc() + m1(1) + m1(2) + wrongKey();
        ok("S3-a","陈旧缓冲：M1 计数 ≥2 → 判据 m1retry 成立（真实函数）",
                CrackEngine.m1RetryImpliesWrongKey(
                        WpaLogReader.countHandshakeM1For(stale, TARGET_BSSID), false));
        ok("S3-b","陈旧缓冲：wrong_key 行带目标 SSID → sawWrongKeyFor 成立（真实函数）",
                WpaLogReader.sawWrongKeyFor(stale, TARGET));
        ok("S3-c","陈旧缓冲：关联行让 tried=true → 无归属的失败行也被采纳",
                WpaLogReader.sawAssociateFor(stale, TARGET)
                        && WpaLogReader.sawHandshakeFailure(stale));

        // 已修复（R6+R3）：① 清理结果现在被检查（clearChecked）；② 归属不再建立
        // 在"缓冲已清空"这个假设上，而是建立在**起点标记的切片**上 —— 即使清空失败，
        // 标记之前的陈旧行也会被切片排除。这里钉住这两条。
        ok("S3-1", "清理结果被检查，且归属改由标记切片承担（不再依赖清空假设）",
                engineSrc().contains("WifiStateLog.clearChecked(")
                        && !engineSrc().contains("WifiStateLog.clear(shell);")
                        && engineSrc().contains("AttemptMarker.sliceAfter("));
        // 已修复（R3）：日志判据只在「本候选起点标记之后」的切片上跑，
        // 标记之前（= 上一条候选残留）的失败行被结构性排除。
        String staleRaw = wrongKey() + m1(1) + m1(2) + assoc() + mk() + "\n" + assoc();
        String staleSlice = AttemptMarker.sliceAfter(staleRaw, MID);
        ok("S3-2", "陈旧行（标记之前）不再能被算到本条候选头上",
                !WpaLogReader.sawWrongKeyFor(staleSlice, TARGET)
                        && WpaLogReader.countHandshakeM1For(staleSlice, TARGET_BSSID) == 0
                        && WpaLogReader.sawAssociateFor(staleSlice, TARGET));
        ok("S3-2b", "标记缺失时切片为空 → 日志证据一律不可用",
                AttemptMarker.sliceAfter(staleRaw.replace(mk(), ""), MID).isEmpty());

        // 3.2 缺失 vs 未发生无法区分
        String empty = "";
        ok("S3-d","空日志下：所有日志判据都返回 false（真实函数）",
                !WpaLogReader.sawAssociateFor(empty, TARGET)
                        && !WpaLogReader.sawHandshakeFailure(empty)
                        && !WpaLogReader.sawWrongKeyFor(empty, TARGET)
                        && WpaLogReader.countHandshakeM1For(empty, TARGET_BSSID) == 0);
        violation("S3-3", "「日志缺失」与「事件未发生」不可区分",
                "ColorOS 会按配额丢日志（WpaLogReader 注释自述约 300 行/8KB），"
                        + "丢光后与「确实没发生」在解析结果上完全同形；"
                        + "引擎没有任何「读到过 N 行 / 是否被截断」的证据标记");
        note("分层摘要里的 logRounds 只统计「读到内容的轮数」，不能证明完整性", "见 S8-2");
    }

    // ================================================================ S4
    /** 时间判据（R2 交叉）：静默结案与「等待期限已到」。 */
    private static void s4_time_criteria() {
        section("S4 时间判据（静默结案 / 看门狗到期）");

        ok("S4-a","m1silent 语义（真实函数）：M1 后静默 ≥1200ms 且无协商完成 → 真",
                CrackEngine.m1SilentImpliesWrongKey(true, false, 1200, 1200));
        ok("S4-b","m1silent：有协商完成 → 假（被压制）",
                !CrackEngine.m1SilentImpliesWrongKey(true, true, 5000, 1200));

        // 时序：成功证据比静默阈值晚到时，候选已被判负
        Result late = Loop.run(TARGET, TARGET_BSSID, 10_000L, -1L,
                new Round(0, ST_OFF, ""),
                Round.w(400, ST_TARGET_ASSOC, assoc() + m1(1)),
                Round.w(1650, ST_TARGET_ASSOC, assoc() + m1(1)),           // 静默阈值到
                Round.w(1750, stTarget(613), assoc() + m1(1) + negotiated())); // 真证据稍晚
        note("成功证据比静默阈值晚 100ms 到", late);
        // 已修复（R2）：静默不再单独结案 → 成功证据晚到也能拿到 SUCCESS。
        ok("S4-1", "静默不再结案：成功证据晚于静默阈值 100ms 仍判成功",
                late.verdict == CrackEngine.Verdict.SUCCESS);

        // R2 补充：失败加速没有被一起删掉 —— AP 重发 M1 仍会判负（证据驱动）
        Result retryFast = Loop.run(TARGET, TARGET_BSSID, 10_000L, 613L,
                new Round(0, ST_OFF, ""),
                Round.w(400, ST_TARGET_ASSOC, assoc() + m1(1)),
                Round.w(1500, ST_TARGET_ASSOC, assoc() + m1(1) + m1(2)),
                Round.w(1700, ST_TARGET_ASSOC, assoc() + m1(1) + m1(2)));
        note("M1 被重发（协议层失败证据）的判定", retryFast);
        ok("S4-1b", "M1 重发仍是可结案的失败证据（加速保留）",
                retryFast.verdict == CrackEngine.Verdict.AUTH_FAIL
                        && "m1retry".equals(retryFast.by));

        // negotiated 的护栏在 bssid 为空时恒为假
        ok("S4-c","sawNegotiatedFor(空 bssid) == false（真实函数）→ 静默判据失去成功护栏",
                !WpaLogReader.sawNegotiatedFor(negotiated() + m1(1), ""));
        note("含义", "target.bssid 缺失时 m1silent 的唯一护栏（negotiated）恒为假；"
                + "此时只有 status 路径能救回正确密码");

        // 看门狗到期后的分类（TRANSCRIBED 末尾分支）
        Result silent = Loop.run(TARGET, TARGET_BSSID, 10_000L, 613L,
                new Round(0, ST_OFF, ""),
                Round.w(2000, ST_OFF, assoc()),
                Round.w(5000, ST_OFF, assoc()),
                Round.w(9500, ST_OFF, assoc()));
        note("看门狗到期：配置已建出、日志只看到目标关联行、无任何失败证据", silent);
        // 已修复（R3）：到期且无证据 → TIMEOUT（观察窗口结束），**不是**认证失败。
        ok("S4-2", "「等满时限且无证据」归 TIMEOUT，不再冒充认证失败",
                silent.verdict == CrackEngine.Verdict.TIMEOUT
                        && silent.why.contains("no-evidence"));
        // 已修复（R3）：TIMEOUT 有了**唯一且明确的生产出口** ——
        // classifyDeadline 的 no-evidence 分支（观察窗口结束、没有明确结论）。
        // 它不再与 AUTH_FAIL 混为一谈；BackoffPolicy 明确把 TIMEOUT 排除在
        // 「AP 的响应样本」之外，run() 的连续超时告警因此变为可达。
        CrackEngine.DeadlineOutcome noEv = CrackEngine.classifyDeadline(
                false, false, false, false, false);
        ok("S4-3", "TIMEOUT 有真实生产出口（到期且无证据），语义与 AUTH_FAIL 分离",
                noEv.verdict == CrackEngine.Verdict.TIMEOUT
                        && "no-evidence".equals(noEv.reason)
                        && !BackoffPolicy.countable(CrackEngine.Verdict.TIMEOUT));
    }

    // ================================================================ S5
    /** 终态唯一性：一个候选只能有一个终态，且不得被迟到事件覆盖。 */
    private static void s5_terminal_uniqueness() {
        section("S5 终态唯一性 / 覆盖 / 计数口径");

        Result hit = Loop.run(TARGET, TARGET_BSSID, 10_000L, -1L,
                new Round(0, ST_OFF, ""),
                new Round(900, stTarget(613), assoc() + m1(1) + negotiated()),
                new Round(1200, ST_OTHER, wrongKey()));
        ok("S5-a","SUCCESS 之后不再接受后续事件（终态不被覆盖）",
                hit.verdict == CrackEngine.Verdict.SUCCESS && hit.by.equals("supplicant"));
        note("后续轮次含其它网络 + wrong_key（合成）", hit);

        Result unk = Loop.run(TARGET, "", 10_000L, -1L,
                new Round(0, ST_OFF, ""),
                new Round(900, stTarget(613), ""));
        note("target.bssid 为空时的成功证据", unk);
        if (unk.verdict != CrackEngine.Verdict.SUCCESS) {
            note("说明", "bssid 为空 → 日志类成功判据无效；仅 status 路径可用（设计取舍，非缺陷）");
        }

        // hit 赋值点唯一性（SCANNED）
        String eng = readFile(root + "/app/src/main/java/io/github/wlmosv_png/wpaforge/audit/CrackEngine.java");
        if (eng == null) {
            notRun("hit 赋值点唯一性", "源码不可读");
        } else {
            int n = count(eng, "hit = c.password");
            ok("S5-b","CrackEngine 内 hit 赋值点恰好 1 处（SCANNED）", n == 1);
            if (n != 1) violation("S5-1", "hit 赋值点不唯一", "出现 " + n + " 次");
            int guard = count(eng, "if (a.isSuccess())");
            if (guard < 1) violation("S5-2", "未找到 SUCCESS 守卫", "hit 赋值可能未被 a.isSuccess() 守卫");
            else ok("hit 赋值在 a.isSuccess() 守卫内（SCANNED）");
        }

        violation("S5-3", "被提前结案的候选仍计入「已试条数」",
                "run() 在 tryOne 之前就 tested++ / cursor=i+1；若该候选因 failover、"
                        + "未尝试、通道异常等被判负，它照样计入 tested 与任务进度 → "
                        + "「试过 N 条全部不中」这句话在覆盖度上是虚高的");
    }

    // ================================================================ S6
    /** 旧任务事件在本次任务期间抵达。 */
    private static void s6_stale_task_events() {
        section("S6 旧任务 / 上一条候选的事件污染");

        // 广播：旧任务残留的认证失败，只要时刻晚于本候选 commandAt 就被采纳
        long prevTaskError = 100_000L;
        long thisCommandAt = 99_500L;
        ok("S6-a","上一条候选/上一任务的广播（读数不可得时）会被采纳（真实函数）",
                WifiAuthFailureWatcher.authErrorImpliesWrongKey(prevTaskError, thisCommandAt, false));
        ok("S6-b","SSID 读不到时不作过滤（真实函数）",
                WifiAuthFailureWatcher.ssidMatches(null, TARGET)
                        && WifiAuthFailureWatcher.ssidMatches("", TARGET));
        violation("S6-1", "广播归属仍无候选标识（已加 exercise 前置，残余同上）",
                "上一条候选的认证失败广播若落进本条窗口，且本条确实被尝试过，"
                        + "仍会被记成本条的结果 —— 现有信号里没有可用的候选维度");

        // 日志：陈旧缓冲（S3 已证）跨越候选甚至跨任务
        // 已修复（R3）：隔离单位从「缓冲」变成「标记之后的窗口切片」，
        // 且起点标记带 attemptId → 归属到候选，而不是靠"刚才清过缓冲"这句话。
        ok("S6-2", "日志隔离单位是「候选窗口切片」（带 attemptId），不再是缓冲",
                engineSrc().contains("wlogSlice")
                        && AttemptMarker.sliceAfter(mk() + "\nx", MID).contains("x"));

        // NetworkWatcher 的 events 有 run 级 clear（对比说明为什么它是唯一的正确做法）
        ok("S6-c","对照：NetworkWatcher 有 run 级 clear + 事件自带时刻（可归因）",
                sourceContains(projectRootOfThisRun(), "watcher.clear();"));
    }

    // ================================================================ S7
    /** 脱敏：逐条结果 / 最近判定 / 异常 / 调试 / 导出。 */
    private static void s7_redaction(String root) {
        section("S7 脱敏覆盖面（决策 4：默认全量脱敏）");

        ok("S7-a","Redact 标签稳定且不含明文",
                Redact.pw("sample-pw").equals("#29ff2d(len=9)")
                        && !Redact.pw("sample-pw").contains("sample"));
        ok("S7-b","带顺序号的标签可用于对回字典", Redact.pwAt(11, "sample-pw").equals("#11 #29ff2d(len=9)"));
        ok("S7-c","引擎判词已脱敏", CrackEngine.verdictTag(11, "sample-pw",
                CrackEngine.Verdict.AUTH_FAIL, "m1silent").startsWith("#11 #29ff2d(len=9)=✗"));

        String eng = readFile(root + "/app/src/main/java/io/github/wlmosv_png/wpaforge/audit/CrackEngine.java");
        String svc = readFile(root + "/app/src/main/java/io/github/wlmosv_png/wpaforge/CrackService.java");
        String hist = readFile(root + "/app/src/main/java/io/github/wlmosv_png/wpaforge/audit/HistStore.java");
        if (eng == null || svc == null) { notRun("源码扫描", "源码不可读"); return; }

        // 明文进日志：找 onLog(... c.password ...)
        int leak = 0;
        String[] engLines = eng.split("\n");
        for (int i = 0; i < engLines.length; i++) {
            if (!engLines[i].contains("onLog(")) continue;
            StringBuilder win = new StringBuilder();
            for (int k = i; k < Math.min(engLines.length, i + 4); k++) win.append(engLines[k]).append(' ');
            if (win.toString().contains("c.password") || win.toString().contains("a.password")) leak++;
        }
        if (leak > 0) {
            violation("S7-1", "明文口令进入任务日志文件（生产代码 " + leak + " 处）",
                    "CrackEngine:1795 附近的 onLog(\"⚡ 密钥命中：%s\" , c.password) → "
                            + "CrackService.onLog → log(line) → appendFileLog → "
                            + "/sdcard/Android/data/…/logs/WPAForge-log-*.txt。"
                            + "与 Redact 注释里「不落日志」的承诺直接冲突");
        } else {
            ok("未发现明文口令进入 onLog");
        }

        // notify / 通知栏
        int notif = 0;
        for (String line : svc.split("\n")) {
            if (line.contains("updateNotification(") && line.contains("candidate")) notif++;
        }
        int redacted = count(svc, "Redact.pw(candidate)");
        ok("S7-d","通知栏候选已脱敏（SCANNED）", redacted >= 1);

        // HistStore 明文
        if (hist != null) {
            boolean csvPlain = hist.contains("esc(password)");
            boolean exportPlain = hist.contains("r.ssid + \":\" + r.password");
            if (csvPlain || exportPlain) {
                violation("S7-2", "破解记录与其导出仍为明文（当前是「结果展示」豁免）",
                        "HistStore 写 CSV 明文口令，并导出 WPAForge-passwords-*.txt。"
                                + "决策 4 要求默认脱敏、明文改为用户主动启用的敏感操作 → "
                                + "需要一份 opt-in 契约（见报告第 6 节），本轮不改");
            } else {
                ok("破解记录未见明文写出");
            }
        }

        // 队列文件
        String tq = readFile(root + "/app/src/main/java/io/github/wlmosv_png/wpaforge/audit/TaskQueue.java");
        if (tq != null && tq.contains("hitPassword")) {
            violation("S7-3", "队列文件保存命中明文口令",
                    "queue.json 的 hitPassword 字段是明文；它同时也是「任务级结果」的持久化副本，"
                            + "属于结果展示豁免范围，但需要与 S7-2 一起进入 opt-in 契约");
        }
    }

    // ================================================================ S8
    /** 诊断真实性：日志说的事情和代码做的事是否一致。 */
    private static void s8_diagnostics_truth(String root) {
        section("S8 诊断真实性（说 / 做一致）");

        String eng = readFile(root + "/app/src/main/java/io/github/wlmosv_png/wpaforge/audit/CrackEngine.java");
        if (eng == null) { notRun("诊断扫描", "源码不可读"); return; }

        int decl = count(eng, "int logRounds = 0, statusRounds = 0, watcherHits = 0;");
        int inc = count(eng, "watcherHits++");
        ok("S8-a","watcherHits 只声明未自增（SCANNED）", decl == 1);
        if (inc == 0) {
            violation("S8-1", "分层摘要里的「事件N」恒为 0",
                    "watcherHits 没有任何自增点，却出现在每条未命中结果的"
                            + "「[层: 日志x/y · statusx/y · 事件N]」里 → 诊断数字是假的");
        }
        if (count(eng, "firstAvailableAfter") == 1) {
            violation("S8-2", "watcher 只在一个无调用者的方法里被使用",
                    "firstAvailableAfter 仅在 verifyHit 内被调用，而 verifyHit 全仓无调用者；"
                            + "run() 的说明文字却说判定完全依赖系统回调");
        }
        if (count(eng, "consecutiveTimeouts") >= 2 && count(eng, "Verdict.TIMEOUT") == 0) {
            violation("S8-3", "连续超时告警是死代码",
                    "run() 里 consecutiveTimeouts == TIMEOUT_ALERT 的告警依赖 Verdict.TIMEOUT，"
                            + "但引擎不再产出该值 → 「通道可能没真正生效」这条提示永远不会出现");
        }
        ok("S8-b","AttributionTest 与本探针引用同一枚举（可对照）",
                CrackEngine.Verdict.valueOf("UNKNOWN") == CrackEngine.Verdict.UNKNOWN);
    }

    // ================================================================ 工具
    private static String root = "/workspace/promo-wpaforge";

    private static String projectRootOfThisRun() { return root; }

    /** 引擎源码（懒加载，供多处断言共用）。 */
    private static String ENG;
    private static String engineSrc() {
        if (ENG == null) {
            ENG = readFile(root + "/app/src/main/java/io/github/wlmosv_png/wpaforge/"
                    + "audit/CrackEngine.java");
            if (ENG == null) ENG = "";
        }
        return ENG;
    }

    private static boolean sourceContains(String root, String needle) {
        String s = readFile(root + "/app/src/main/java/io/github/wlmosv_png/wpaforge/audit/CrackEngine.java");
        if (s == null) return false;
        return s.contains(needle) || readAllSources(root).contains(needle);
    }

    private static String readAllSources(String root) {
        StringBuilder sb = new StringBuilder();
        File dir = new File(root + "/app/src/main/java");
        collect(dir, sb);
        return sb.toString();
    }

    private static java.util.List<File> javaFiles(String dir) {
        java.util.List<File> out = new java.util.ArrayList<>();
        javaFiles(new File(dir), out);
        return out;
    }

    private static void javaFiles(File f, java.util.List<File> out) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null) return;
            for (File k : kids) javaFiles(k, out);
        } else if (f.getName().endsWith(".java")) {
            out.add(f);
        }
    }

    private static void collect(File f, StringBuilder sb) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null) return;
            for (File k : kids) collect(k, sb);
        } else if (f.getName().endsWith(".java")) {
            String s = readFile(f.getAbsolutePath());
            if (s != null) sb.append(s).append('\n');
        }
    }

    private static String readFile(String path) {
        try {
            return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int count(String hay, String needle) {
        if (hay == null || needle.isEmpty()) return 0;
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    // ================================================================ 模型
    /**
     * {@code CrackEngine.tryOneShell} 判定顺序的**人工转写**（TRANSCRIBED）。
     *
     * <p>它不在生产路径上。写入这里的每一步都在注释里标了源码行号，
     * 目的只有一个：让「事件的到达顺序 + 判定顺序」的后果可执行、可复现。
     * 凡结论出自本类的用例，报告里一律标注「模型推断」。
     */
    static final class Round {
        final long tMs;        // 相对命令发出的毫秒
        final String status;   // 该轮 cmd wifi status 输出（null = 该轮没读到，视为空）
        final String wlog;     // 该轮 logcat 预过滤输出（null = 没读到）
        /**
         * 该轮**日志窗口是否可用**（R6 起生产会用 AttemptMarker.evaluate 算出来）。
         * false = 起点标记不可见 / 检测到外来标记 → 日志证据一律不可用。
         */
        final boolean win;
        Round(long t, String status, String wlog) { this(t, status, wlog, true); }
        Round(long t, String status, String wlog, boolean win) {
            this.tMs = t; this.status = status; this.wlog = wlog; this.win = win;
        }
        /** 便捷：把本候选的起点标记拼到日志文本前面（模拟"窗口从标记开始"）。 */
        static Round w(long t, String status, String body) {
            return new Round(t, status, mk() + "\n" + body);
        }
    }

    static final class Result {
        final CrackEngine.Verdict verdict;
        final String by;
        final long atMs;
        final String why;
        Result(CrackEngine.Verdict v, String by, long at, String why) {
            this.verdict = v; this.by = by; this.atMs = at; this.why = why;
        }
        @Override public String toString() {
            return verdict + (by.isEmpty() ? "" : "(" + by + ")") + "@" + atMs + "ms ← " + why;
        }
    }

    static final class Loop {
        /**
         * @param targetNetId 源码：命令后前 4 轮探测目标配置的 netId（-1 = 没建出来）
         */
        static Result run(String target, String targetBssid, long timeoutMs,
                          long targetNetIdMade, Round... rounds) {
            long deadline = timeoutMs;
            long m1FirstSeenAt = 0L;
            long m1RetryArmedAt = 0L;
            boolean m1RetryArmed = false;
            long authArmedAt = 0L;
            boolean leftBaseline = false;          // CrackEngine:937
            boolean sawAttempt = false;            // CrackEngine:946
            long targetNetId = targetNetIdMade;    // 源码：前 4 轮探测「本条命令有没有建出配置」
            int r = 0;
            for (Round round : rounds) {
                r++;
                if (round.tMs >= deadline) break;

                // ---- 本轮日志切片（CrackEngine:1150-1170 转写）----
                // 直接调用生产实现：先按标记算窗口元数据，再取「标记之后」的切片。
                // 于是模型与生产在归属规则上是同一份代码，不是两份相似逻辑。
                String raw = round.wlog == null ? "" : round.wlog;
                AttemptMarker.Window win = AttemptMarker.evaluate(MID, true, true, raw);
                boolean logWinUsable = win.evidenceUsable();
                String slice = logWinUsable ? AttemptMarker.sliceAfter(raw, MID) : "";

                // ---- status 段（CrackEngine:1020-1160）----
                SupplicantParser.Info info = SupplicantParser.parse(
                        round.status == null ? "" : round.status);
                boolean channelAlive = round.status != null && !round.status.trim().isEmpty();

                if (!sawAttempt && info.ssid != null && target.equals(info.ssid)
                        && info.state != null && !info.state.isEmpty()) {
                    sawAttempt = true;
                    deadline = Math.max(deadline, round.tMs + 4000);   // 延长一次
                }

                // 判据 A（CrackEngine:1095）—— 唯一成功出口
                if (SuccessRule.isSuccess(target, info.ssid, info.state, info.ip)) {
                    return new Result(CrackEngine.Verdict.SUCCESS,
                            SuccessRule.evidence(info.netId, targetNetId), round.tMs, "判据A status 三条件");
                }
                // 回连（CrackEngine:1125-1138）
                boolean onOtherNetwork = info.ssid != null && !info.ssid.isEmpty()
                        && !target.equals(info.ssid)
                        && info.state != null && "COMPLETED".equalsIgnoreCase(info.state);
                if (!onOtherNetwork) {
                    leftBaseline = true;                                 // CrackEngine:1130
                } else if (FailoverRule.looksLikeFailover(target, info.ssid, info.state,
                        leftBaseline, round.tMs,
                        // R3：归属前置 —— 本条候选确实被尝试过
                        sawAttempt || targetNetId >= 0,
                        // R3：本轮已有目标的成功证据 → 失败判据不得抢答
                        !slice.isEmpty() && WpaLogReader.sawNegotiatedFor(slice, targetBssid))) {
                    return new Result(CrackEngine.Verdict.AUTH_FAIL, "failover",
                            round.tMs, "回连判据（已证明本条被尝试过，且本轮无目标成功证据）");
                }

                // ---- 日志段（CrackEngine:1310-1450）----
                String wlog = slice;
                if (!wlog.isEmpty()) {
                    boolean tried = WpaLogReader.sawAssociateFor(wlog, target);
                    if (tried) sawAttempt = true;
                    if (tried && WpaLogReader.sawNegotiatedFor(wlog, targetBssid)) {   // 判据 B
                        return new Result(CrackEngine.Verdict.SUCCESS, "wpalog", round.tMs, "判据B 日志协商完成");
                    }
                    boolean succ = WpaLogReader.sawNegotiatedFor(wlog, targetBssid);
                    // 第三级：广播（合成场景里不产事件，保持 false）
                    // 第二级：静默
                    boolean bssidKnown = targetBssid != null && !targetBssid.isEmpty();
                    int m1ForTarget = bssidKnown
                            ? WpaLogReader.countHandshakeM1For(wlog, targetBssid) : 0;
                    boolean m1Seen = bssidKnown ? (m1ForTarget >= 1)
                            : (tried && WpaLogReader.sawHandshakeM1(wlog));
                    if (m1Seen) {
                        // R2：静默（纯时间判据）**不再结案**，只记录首次看到 M1 的时刻。
                        // 失败一律交给下面的 m1retry（协议层：AP 重发了 M1）。
                        if (m1FirstSeenAt == 0L) m1FirstSeenAt = round.tMs;
                    }
                    int m1Count = bssidKnown ? m1ForTarget
                            : (tried ? WpaLogReader.countHandshakeM1(wlog) : 0);
                    if (CrackEngine.m1RetryImpliesWrongKey(m1Count, succ)) {
                        if (!m1RetryArmed) { m1RetryArmed = true; m1RetryArmedAt = round.tMs; }
                        else if (round.tMs - m1RetryArmedAt >= 120L) {
                            return new Result(CrackEngine.Verdict.AUTH_FAIL, "m1retry",
                                    round.tMs, "M1 重发 " + m1Count + " 次");
                        }
                    }
                    if (WpaLogReader.sawWrongKeyFor(wlog, target)
                            || (tried && (WpaLogReader.sawHandshakeFailure(wlog)
                                    || WpaLogReader.sawAssocReject(wlog)))) {
                        return new Result(CrackEngine.Verdict.AUTH_FAIL, "wpalog",
                                round.tMs, "带/不带归属的失败行");
                    }
                }
            }

            // ---- 循环后分类（CrackEngine:1320-1366）----
            boolean currentOnTarget = false;
            SupplicantParser.Info last = SupplicantParser.parse(
                    rounds.length == 0 ? "" : (rounds[rounds.length - 1].status == null
                            ? "" : rounds[rounds.length - 1].status));
            if (target.equals(last.ssid)) currentOnTarget = true;   // netIdBefore 未知 → 不回落
            boolean channelAlive = false;
            for (Round round : rounds) if (round.status != null && !round.status.trim().isEmpty()) channelAlive = true;

            // 与生产同源：直接调用 CrackEngine.classifyDeadline（纯函数）
            CrackEngine.DeadlineOutcome out = CrackEngine.classifyDeadline(
                    targetNetId < 0 && !sawAttempt && channelAlive,
                    currentOnTarget,
                    false,
                    !sawAttempt && !channelAlive,
                    !sawAttempt && channelAlive && target.equals(last.ssid));
            return new Result(out.verdict, "", timeoutMs, "classifyDeadline:" + out.reason);
        }
    }

    private R3AuditProbe() {}
}
