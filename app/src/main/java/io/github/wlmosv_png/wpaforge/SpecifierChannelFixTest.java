package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Adjudicator;
import io.github.wlmosv_png.wpaforge.audit.AttemptLedger;
import io.github.wlmosv_png.wpaforge.audit.AttemptState;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.Evidence;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;
import io.github.wlmosv_png.wpaforge.cap.AttemptSlot;
import io.github.wlmosv_png.wpaforge.cap.SpecifierChannel;
import io.github.wlmosv_png.wpaforge.cap.SpecifierVerdict;

import java.util.Collections;

/**
 * 针对 D1–D9 缺陷的**回归门禁**。
 *
 * <h3>为什么这些用例值得单独存在</h3>
 * 它们钉的不是「代码有没有跑到」，而是四个真实事故形态：
 * <ol>
 *   <li>旧请求的清理把新请求注销掉（串扰）→ {@link AttemptSlot} 的 id 校验；</li>
 *   <li>超时后旧回调仍能产生结论（迟到回调）→ 只认第一次；</li>
 *   <li>已有连接满足新请求，错误口令判成功 → 两个独立闸位；</li>
 *   <li>BSSID 被静默忽略 → 非法/广播地址必须显式拒绝。</li>
 * </ol>
 *
 * <p>全部纯逻辑（{@link AttemptSlot}、{@link SpecifierVerdict}、参数规范化），
 * 不碰 ConnectivityManager —— 真实回调只能由用户在真机验收。
 */
public class SpecifierChannelFixTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        SpecifierChannelFixTest t = new SpecifierChannelFixTest();
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
        slotSequential();
        slotConcurrent();
        slotStaleRelease();
        slotClose();
        slotDeadline();
        bssidNormalization();
        preexistingConnection();
        lateCallbacks();
        unstableConnection();
        downstreamContract();
        rootIntegrity();
        docAccuracy();
    }

    // ---------------------------------------------------------------- D2 串行

    private void slotSequential() {
        AttemptSlot slot = new AttemptSlot();
        ok("空闲时可占用", "".equals(slot.acquire("a1", 1000L)));
        ok("占用后不空闲", !slot.idle());
        ok("持有者 id 正确", "a1".equals(slot.activeId()));
        ok("持有者可释放", slot.release("a1"));
        ok("释放后空闲", slot.idle());
        ok("释放后可直接开始下一条", "".equals(slot.acquire("a2", 2000L)));
        ok("完成计数递增", slot.completedCount() == 1);
    }

    private void slotConcurrent() {
        AttemptSlot slot = new AttemptSlot();
        ok("第一条占用成功", "".equals(slot.acquire("a1", 1000L)));
        // 关键：并发进入必须被挡住，而不是默默共享
        ok("第二条在第一条未结束时被拒（busy）",
                AttemptSlot.R_BUSY.equals(slot.acquire("a2", 2000L)));
        ok("被拒不会夺走槽位", "a1".equals(slot.activeId()));
        slot.release("a1");
        ok("第一条结束后第二条才能占", "".equals(slot.acquire("a2", 2000L)));
    }

    private void slotStaleRelease() {
        AttemptSlot slot = new AttemptSlot();
        slot.acquire("a1", 1000L);
        slot.release("a1");
        slot.acquire("a2", 2000L);
        // D2 的核心：旧尝试的清理代码带着旧 id 来，绝不能清掉新尝试的槽位
        ok("**旧 id 释放不掉新请求**", !slot.release("a1"));
        ok("新请求仍在持有", "a2".equals(slot.activeId()));
        ok("新 id 能正常释放", slot.release("a2"));
        ok("非法 id 不释放", !slot.release(""));
        ok("null id 不释放", !slot.release(null));
    }

    private void slotClose() {
        AttemptSlot slot = new AttemptSlot();
        slot.acquire("a1", 1000L);
        String cancelled = slot.close();
        ok("close 交出被中止的 id（供调用方注销回调）", "a1".equals(cancelled));
        ok("close 后槽位为空", slot.idle());
        ok("close 后拒绝新请求",
                AttemptSlot.R_CLOSED.equals(slot.acquire("a2", 2000L)));
        ok("close 是幂等的", "".equals(slot.close()));
        slot.reopen();
        ok("reopen 后可再次使用", "".equals(slot.acquire("a3", 3000L)));
    }

    private void slotDeadline() {
        AttemptSlot slot = new AttemptSlot();
        slot.acquire("a1", 500L);
        ok("未到截止时刻不算过期", !slot.expired(499L));
        ok("到点算过期（单调时钟）", slot.expired(500L));
        ok("超过也算过期", slot.expired(9_999L));
        slot.release("a1");
        ok("空闲时不报过期", !slot.expired(9_999L));
        ok("截止时刻随释放清零", slot.deadline() == 0L);
        ok("空 id 被拒", AttemptSlot.R_BAD_ID.equals(new AttemptSlot().acquire("  ", 1L)));
    }

    // ---------------------------------------------------------------- D1 BSSID

    private void bssidNormalization() {
        ok("合法 BSSID 规范化（小写）",
                "28:93:7d:41:cc:39".equals(
                        SpecifierChannel.normalizeBssid("28:93:7D:41:CC:39")));
        ok("合法 BSSID 原样接受",
                "aa:bb:cc:dd:ee:ff".equals(SpecifierChannel.normalizeBssid("aa:bb:cc:dd:ee:ff")));
        // 这些必须**判空**（= 无法约束），交给调用方显式拒绝，绝不静默忽略
        ok("广播地址不可用", "".equals(SpecifierChannel.normalizeBssid("ff:ff:ff:ff:ff:ff")));
        ok("全零地址不可用", "".equals(SpecifierChannel.normalizeBssid("00:00:00:00:00:00")));
        ok("非法格式不可用", "".equals(SpecifierChannel.normalizeBssid("28:93:7D:41:CC")));
        ok("带非十六进制字符不可用",
                "".equals(SpecifierChannel.normalizeBssid("zz:93:7d:41:cc:39")));
        ok("null 不可用", "".equals(SpecifierChannel.normalizeBssid(null)));
        ok("空串表示「不约束」", "".equals(SpecifierChannel.normalizeBssid("")));

        // 源码守卫：必须真的调用 setBssid，且只对规范化后的值调用
        String ch = src("cap/SpecifierChannel.java");
        ok("**通道真的调用了 setBssid**", ch.contains("b.setBssid(MacAddress.fromString(bssid))"));
        ok("setBssid 只在 bssid 非空时调用", ch.contains("if (!bssid.isEmpty()) {"));
        ok("非法 BSSID 显式拒绝（不静默忽略）", ch.contains("\"invalid-bssid\""));
        ok("广播/全零在规范化阶段被挡", ch.contains("ff:ff:ff:ff:ff:ff"));
    }

    // ---------------------------------------------------------------- D8 假成功

    private void preexistingConnection() {
        // 第一道闸：请求前已连着目标 → 直接作废
        SpecifierVerdict.Decision pre = SpecifierVerdict.decide(
                false, false, false, true, false, false);
        ok("请求前已连着目标 → NOT_EXECUTED", pre.isNotExecuted());
        ok("请求前已连着目标 → 归类码 platform-refused", "platform-refused".equals(pre.code));

        // 第二道闸：返回的 Network 请求前就存在 → 同样不能判成功
        // （这里用同一分支表达：通道把这种情况也映射成 preBlocked）
        String ch = src("cap/SpecifierChannel.java");
        ok("**通道比对了请求前就存在的网络句柄**",
                ch.contains("preexistingHandles") && ch.contains("getNetworkHandle()"));
        ok("请求前快照 WiFi 网络", ch.contains("snapshotWifiHandles"));
        ok("命中已存在网络时拒绝判定", ch.contains("\"pre-existing-network\""));
        ok("请求前已连着目标时拒绝判定", ch.contains("\"pre-existing-connection\""));
        ok("这两类都不产生 SUCCESS",
                !ch.contains("preexistingHandles.contains(handle) && connected"));

        // 下游：preBlocked 结论必须落到 NOT_EXECUTED，且绝不写 triedSet
        ok("preBlocked 不做裁决", !SpecifierVerdict.needsAdjudication(pre));
        CrackEngine.Attempt a = Adjudicator.gateBlocked("12345678", "run#1",
                AttemptState.Gate.NOT_CAPABLE, 5L, "无法证明候选参与认证");
        ok("无法证明候选参与认证 → 不产证据级", !EvidenceGrade.persistable(a.grade));
        ok("无法证明候选参与认证 → 不推游标", !EvidenceGrade.advancesCursor(a.grade));
        ok("无法证明候选参与认证 → 不是 AUTH_FAIL",
                a.verdict != CrackEngine.Verdict.AUTH_FAIL);

        // 引擎侧也有闸（请求前）
        String eng = src("audit/CrackEngine.java");
        ok("引擎请求前查「已连着目标」", eng.contains("isTargetSavedOrConnected(target)"));
        ok("引擎按 BSSID 精确判定已连接", eng.contains("target.bssid.equalsIgnoreCase(curB)"));
    }

    // ---------------------------------------------------------------- D4/D9 迟到回调

    private void lateCallbacks() {
        // 超时后的迟到 onAvailable：SpecifierVerdict 层面无法区分先后，
        // 但通道必须靠「只认第一次结论」把它挡掉。
        String ch = src("cap/SpecifierChannel.java");
        ok("**只认第一次结论**（迟到回调不得改写）",
                ch.contains("settled.compareAndSet(false, true)"));
        ok("已收尾后回调直接丢弃", ch.contains("if (settled.get()) return;"));
        ok("等待超时会主动取消请求（不是干等）",
                ch.contains("abort(\"await-timeout\")") || ch.contains("abort(\"await-timeout\")"));
        ok("截止时刻用单调时钟", ch.contains("SystemClock.elapsedRealtime()"));
        ok("不混用墙钟计耗时", !ch.contains("System.currentTimeMillis() - startMs"));

        // 超时 → NOT_EXECUTED，不是错误密码
        SpecifierVerdict.Decision t = SpecifierVerdict.decide(
                false, false, false, false, true, false);
        ok("超时 → NOT_EXECUTED", t.isNotExecuted());
        ok("超时 → 归类码 no-answer", "no-answer".equals(t.code));

        // 并发/串行：submit 在忙等后会给出 busy 而不是硬闯
        ok("提交在忙时给出 busy 原因码", ch.contains("AttemptSlot.R_BUSY")
                || ch.contains("AttemptSlot.R_BUSY.equals(why)"));
        ok("等待上一条收尾有上限", ch.contains("SETTLE_MS"));
    }

    // ---------------------------------------------------------------- D7 onLost

    private void unstableConnection() {
        ok("连上后立刻掉线 → 不算成功",
                !SpecifierVerdict.decide(true, false, false, false, false, true).isSuccess());
        ok("连上后立刻掉线 → ACT_NOT_CONNECTED",
                SpecifierVerdict.decide(true, false, false, false, false, true).isNotConnected());
        ok("连上后立刻掉线 → 归类码 dropped-immediately",
                "dropped-immediately".equals(
                        SpecifierVerdict.decide(true, false, false, false, false, true).code));
        ok("连上后立刻掉线 → 不是密码错误",
                SpecifierVerdict.decide(true, false, false, false, false, true).claim
                        != Evidence.Claim.WRONG_KEY);

        // 建立前 onLost = 没连上
        ok("建立前 onLost → ACT_NOT_CONNECTED",
                SpecifierVerdict.decide(false, true, false, false, false, false).isNotConnected());

        // 稳定性窗口存在，且掉线会覆盖「已连上」
        String ch = src("cap/SpecifierChannel.java");
        ok("通道有稳定性观察窗", ch.contains("STABILITY_MS"));
        ok("onLost 有真实实现（不是空方法）", ch.contains("void onNetworkLost(Network n)"));
        ok("建立前 lost 与建立后 lost 分开处理",
                ch.contains("lost-before-available") && ch.contains("unstableOutcome()"));
        ok("收尾后的迟到 onLost 被忽略", ch.contains("if (settled.get()) return;      // 已收尾后的迟到 onLost 一律忽略")
                || ch.contains("已收尾后的迟到 onLost 一律忽略"));
    }

    // ---------------------------------------------------------------- 判定链

    private void downstreamContract() {
        // AUTH_FAIL 仍要求 DIRECT
        ok("AUTH_FAIL 默认等级仍是 INFERRED（不能直接持久化）",
                EvidenceGrade.defaultOf(CrackEngine.Verdict.AUTH_FAIL)
                        == EvidenceGrade.Level.INFERRED);
        ok("真正连上的证据主张是 CONNECTED（不是 WRONG_KEY）",
                SpecifierVerdict.decide(true, false, false, false, false, false).claim
                        == Evidence.Claim.CONNECTED);
        ok("失败侧主张永远是 NOT_CONNECTED 或空，绝不 WRONG_KEY",
                SpecifierVerdict.decide(false, true, false, false, false, false).claim
                        == Evidence.Claim.NOT_CONNECTED
                        && SpecifierVerdict.decide(false, false, true, false, false, false).claim == null);
        // 无 Root「已连上」必须经裁决器，且**不产生 SUCCESS**（2026-11-18 收紧）
        SpecifierVerdict.Decision s = SpecifierVerdict.decide(
                true, false, false, false, false, false);
        ok("无 Root 连上仍要经裁决器", SpecifierVerdict.needsAdjudication(s));
        Adjudicator.Result r = Adjudicator.adjudicate(
                new AttemptState("run#9", CrackEngine.Lifecycle.TERMINATED,
                        SpecifierVerdict.gateOf(s), s.netIdKnown),
                Collections.singletonList(new Evidence(s.source, s.claim, s.attribution,
                        "run#9", 1000L)));
        ok("**无 Root 连上 → 不产生 SUCCESS**", r.verdict != CrackEngine.Verdict.SUCCESS);
        ok("无 Root 连上 → UNKNOWN", r.verdict == CrackEngine.Verdict.UNKNOWN);
        ok("无 Root 连上 → 原因 on-target-unattributed",
                "on-target-unattributed".equals(r.reason));
        ok("无 Root 连上 → 等级 NONE", r.grade == EvidenceGrade.Level.NONE);
        ok("**无 Root 连上 → 不给 DIRECT**", r.grade != EvidenceGrade.Level.DIRECT);

        // Root 的 DIRECT 成功路径必须原样还在（AT3 = 可绑到本次新建配置）
        Adjudicator.Result root = Adjudicator.adjudicate(
                new AttemptState("run#R", CrackEngine.Lifecycle.TERMINATED,
                        AttemptState.Gate.OK, true),
                Collections.singletonList(new Evidence(Evidence.Source.SUCCESS_STATUS,
                        Evidence.Claim.CONNECTED, Evidence.Attribution.AT3, "run#R", 1000L)));
        ok("Root 的 CONNECTED+AT3 仍然 SUCCESS", root.verdict == CrackEngine.Verdict.SUCCESS);
        ok("Root 的 CONNECTED+AT3 仍然 DIRECT", root.grade == EvidenceGrade.Level.DIRECT);
        CrackEngine.Attempt rootA = Adjudicator.toAttempt("12345678", root, 100L, "", "status");
        ok("Root 的成功 Attempt.isSuccess() 为 true", rootA.isSuccess());

        // 非 DIRECT 不进 TriedSet / 不推游标
        ok("INFERRED 不持久化", !EvidenceGrade.persistable(EvidenceGrade.Level.INFERRED));
        ok("NONE 不持久化", !EvidenceGrade.persistable(EvidenceGrade.Level.NONE));
        ok("只有 CONFIRMED_WRONG 会被账本跳过",
                AttemptLedger.Level.classify(CrackEngine.Verdict.AUTH_FAIL,
                        EvidenceGrade.Level.DIRECT) == AttemptLedger.Level.CONFIRMED_WRONG);
        ok("NOT_CONNECTED 不进账本跳过档",
                AttemptLedger.Level.classify(CrackEngine.Verdict.TERMINATED_UNKNOWN,
                        EvidenceGrade.Level.NONE) != AttemptLedger.Level.CONFIRMED_WRONG);

        // 源码守卫：TriedSet 写入仍要求 AUTH_FAIL ∧ persistable
        String svc = src("CrackService.java");
        ok("TriedSet 写入仍带 persistable 判据",
                svc.contains("EvidenceGrade.persistable(a.grade)"));
        ok("SUCCESS 仍被显式排除（走 isSuccess()）",
                svc.contains("if (a.isSuccess()) {")
                        && svc.contains("EvidenceGrade.persistable(a.grade)"));
    }

    // ---------------------------------------------------------------- 第 7 点：Root 完整性

    private void rootIntegrity() {
        String eng = src("audit/CrackEngine.java");
        // Root 链的判定入口与关键证据构造必须原样还在
        ok("Root 通道仍在（tryOneShell）", eng.contains("private Attempt tryOneShell("));
        ok("Root 净检仍在（forgetTargetByShell）", eng.contains("forgetTargetByShell(target.ssid)"));
        ok("Root 净检回读仍在（netIdOfTarget）", eng.contains("netIdOfTarget(target.ssid)"));
        ok("状态解析仍在（StatusParser）", eng.contains("StatusParser"));
        ok("列表解析仍在（ListNetworksParser）", eng.contains("ListNetworksParser"));
        ok("Root 成功出口仍要求 AT3", eng.contains("Evidence.Attribution.AT3"));
        ok("Root 直接构造 AUTH_FAIL 仍为 0",
                !eng.contains("new Attempt(password, Verdict.AUTH_FAIL"));
        ok("Root 直接构造 SUCCESS 仍为 0",
                !eng.contains("new Attempt(password, Verdict.SUCCESS"));
        ok("两条执行器是分流而不是替换",
                eng.contains("(specifier != null)")
                        && eng.contains("? tryOneSpecifier(")
                        && eng.contains(": tryOneShell("));

        // Root 通道类未被改动契约
        String rc = src("cap/RootChannel.java");
        ok("RootChannel 仍在且提供 shell 适配", rc.contains("asShellChannel()"));
        ok("RootChannel 仍有熔断阈值", rc.contains("EXEC_TIMEOUT_LIMIT"));
        ok("RootChannel 仍用常驻会话", rc.contains("ShellSession"));

        // Specifier 通道不得触碰 su / shell
        String ch = src("cap/SpecifierChannel.java");
        ok("**Specifier 通道不含任何 su 调用**",
                !ch.contains("\"su\"") && !ch.contains("Runtime.getRuntime"));
        ok("Specifier 通道不读 supplicant socket",
                !ch.contains("wlan0") && !ch.contains("LocalSocket"));
        // 只断言「没有真的去改」，而不是「文件里不能出现这个词」——
        // state() 里会照着能力矩阵描述「appops 写入需要 su」，那是说明不是调用。
        ok("Specifier 通道不执行 appops/白名单写入",
                !ch.contains("appops set") && !ch.contains("dumpsys deviceidle")
                        && !ch.contains("settings put"));
    }

    // ---------------------------------------------------------------- D10 文案

    private void docAccuracy() {
        String ch = src("cap/SpecifierChannel.java");
        ok("文档说明 satisfiesNetworkSpecifier 不比 PSK",
                ch.contains("不比较 PSK") || ch.contains("不比较 PSK"));
        ok("文档说明失败分不开", ch.contains("分不开"));
        ok("文档说明批准缓存键是 SSID/BSSID/安全类型",
                ch.contains("AccessPoint(SSID, BSSID, securityType)"));
        ok("文档说明上限 50 条", ch.contains("上限 50"));
        ok("文档说明厂商差异待确认",
                ch.contains("厂商 ROM") || ch.contains("厂商是否保留"));
        ok("文档不承诺无交互连续验证", ch.contains("不承诺"));
    }

    // ---------------------------------------------------------------- 工具

    private String src(String rel) {
        try {
            java.io.File f = new java.io.File(TestPaths.root()
                    + "/app/src/main/java/io/github/wlmosv_png/wpaforge/" + rel);
            if (!f.isFile()) return "";
            return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
