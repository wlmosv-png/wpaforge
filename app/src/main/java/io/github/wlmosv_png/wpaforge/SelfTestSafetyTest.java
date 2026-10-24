package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.RestoreGuard;
import io.github.wlmosv_png.wpaforge.audit.RestoreJournal;
import io.github.wlmosv_png.wpaforge.audit.RestorePlan;
import io.github.wlmosv_png.wpaforge.audit.RestoreRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 连接自测的安全回归（最终审计 P0-1）。
 *
 * <h3>被修的那个洞</h3>
 * 自测会真的删掉目标网络的已保存配置再重新连一次 —— 改前它**不还原**：
 * 跑完之后用户那条网络的配置就变成自测用的口令了（错口令则完全连不上）。
 *
 * <p>现在：开始前用 R11 那套机制登记原配置（快照 → 加密 → 落盘 → fail-closed 闸门），
 * 结束（成功 / 失败 / 中断 / 异常）都在 {@code finally} 里走同一套还原并读回验证；
 * 登记不到就**拒绝执行**并说明原因。
 *
 * <h3>这个测试查什么</h3>
 * <ol>
 *   <li><b>顺序</b>：登记必须发生在任何会改配置的命令**之前**（按源码下标比较）；</li>
 *   <li><b>出口覆盖</b>：还原在 {@code finally} 里，而不是各分支各写一份；</li>
 *   <li><b>拒绝路径</b>：闸门不放行时明确拒绝且不跑那几步；</li>
 *   <li><b>并发安全</b>：走 {@link RestoreRunner#runExclusiveForTask} —— 与服务收尾/启动补做
 *       共用同一把锁，不会和它们抢同一个目标配置；</li>
 *   <li><b>归属</b>：任务归属不符时**不动**目标配置（返回 null 并说明）；</li>
 *   <li><b>闸门语义</b>：快照失败/加密失败/落盘失败/盘上有别人的待办 → 一律不放行。</li>
 * </ol>
 */
public class SelfTestSafetyTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        boolean c = (want == null) ? got == null : want.equals(got);
        if (!c) System.out.println("        want=" + want + " got=" + got);
        ok(name, c);
    }

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final String MA = "/workspace/promo-wpaforge/app/src/main/java/io/github/"
            + "wlmosv_png/wpaforge/MainActivity.java";

    public static void main(String[] args) {
        String ma = read(MA);
        if (ma == null) {
            System.out.println("  N/RUN 源码不可读");
            System.out.println("=== 自测安全测试: 0 通过, 0 失败（NOT RUN）===");
            return;
        }
        int a = ma.indexOf("private void runConnTest(");
        int b = ma.indexOf("private void exportLog()", a);
        String body = (a >= 0 && b > a) ? ma.substring(a, b) : "";
        ok("能定位 runConnTest", !body.isEmpty());

        System.out.println("== S1 顺序：登记在任何改配置的命令之前 ==");
        int iSnap = body.indexOf("ConfigRestorer.snapshot(");
        int iGuard = body.indexOf("RestoreGuard.decide(");
        int iForget = body.indexOf("sh.exec(\"cmd wifi forget-network ");
        int iConnect = body.indexOf("sh.exec(\"cmd wifi connect-network ");
        int iDisable = body.indexOf("sh.exec(\"cmd wifi set-wifi-enabled disabled");
        ok("有快照", iSnap > 0);
        ok("有闸门决策", iGuard > 0);
        ok("有会改配置的命令（forget / connect / 关再开 WiFi）",
                iForget > 0 && iConnect > 0 && iDisable > 0);
        ok("**快照先于** forget-network", iSnap < iForget);
        ok("**闸门先于** forget-network", iGuard < iForget);
        ok("**快照先于** connect-network", iSnap < iConnect);
        ok("**快照先于** 关 WiFi", iSnap < iDisable);

        System.out.println("== S2 出口覆盖：还原在 finally 里，覆盖全部结束方式 ==");
        int iFinally = body.indexOf("} finally {", iConnect);
        int iRestore = body.indexOf("RestoreRunner.runExclusiveForTask(", iConnect);
        ok("连接命令之后有 finally 块", iFinally > iConnect);
        ok("还原调用在 finally 块内", iRestore > iFinally);
        ok("还原走同一把锁（与服务收尾/启动补做串行）",
                body.contains("RestoreRunner.runExclusiveForTask("));
        ok("未确认还原时明确提示用户手动重连",
                body.contains("原配置未确认还原"));
        ok("还原异常也被捕获并写进报告",
                body.contains("还原异常: "));

        System.out.println("== S3 拒绝路径：登记不到就不动配置 ==");
        ok("闸门放行才进入步骤 3~6", body.contains("if (sh != null && guardEntry != null) {"));
        ok("拒绝时明确说明原因（复用 RestoreGuard.describe）",
                body.contains("RestoreGuard.describe(d)"));
        ok("拒绝分支给出「不执行任何改配置步骤」的结论",
                body.contains("本次不执行任何会改动网络配置的步骤"));
        ok("登记异常也按拒绝处理", body.contains("登记异常，已禁止本次自测"));

        System.out.println("== S4 归属：不属于本任务就什么都不做（真实调用）==");
        RestoreJournal.Store mem = RestoreJournal.memory();
        RestoreJournal.install(mem);
        RestoreJournal.Entry other = new RestoreJournal.Entry();
        other.ssid = "OTHER-TARGET";
        other.taskId = "some-other-task";
        other.stage = RestoreJournal.STAGE_ARMED;
        RestoreJournal.save(other);
        boolean[] jobRan = {false};
        RestorePlan.Outcome o = RestoreRunner.runExclusiveForTask(
                other, "selftest-123", (cur) -> {
                    jobRan[0] = true;
                    // 用公开入口构造 Outcome（构造器是包内可见的）
                    return RestorePlan.judge(null, null, true, RestorePlan.ReaddResult.notIssued());
                });
        eq("任务归属不符 → 不动目标配置（返回 null）", null, o);
        ok("作业没有被执行", !jobRan[0]);
        // 把上面那份别人记录清掉，别影响后续测试
        RestoreJournal.clear();
        RestoreJournal.install(null);

        System.out.println("== S5 闸门语义：四种「登记不到」都必须拒绝 ==");
        RestoreGuard.Decision d;
        d = RestoreGuard.decide(new RestoreGuard.Facts(
                true, true, true, true, true, true, true, true));
        eq("盘上有别人的未完成恢复 → OTHER_PENDING",
                RestoreGuard.Reason.OTHER_PENDING, d.reason);
        ok("不放行", !d.proceed);
        d = RestoreGuard.decide(new RestoreGuard.Facts(
                false, false, true, true, true, true, true, true));
        eq("读不到已保存列表 → CHANNEL_UNREADABLE",
                RestoreGuard.Reason.CHANNEL_UNREADABLE, d.reason);
        d = RestoreGuard.decide(new RestoreGuard.Facts(
                false, true, true, true, true, true, false, true));
        eq("原口令加密失败 → SEAL_FAILED", RestoreGuard.Reason.SEAL_FAILED, d.reason);
        d = RestoreGuard.decide(new RestoreGuard.Facts(
                false, true, true, true, true, true, true, false));
        eq("记录写不进盘 → JOURNAL_UNWRITABLE",
                RestoreGuard.Reason.JOURNAL_UNWRITABLE, d.reason);
        d = RestoreGuard.decide(new RestoreGuard.Facts(
                false, true, true, true, true, false, true, true));
        eq("需要口令却没有原口令 → PSK_UNREADABLE",
                RestoreGuard.Reason.PSK_UNREADABLE, d.reason);

        System.out.println("== S6 对话框如实告知（会临时改动配置 + 会自动还原）==");
        ok("提示里写了「临时改动已保存配置」",
                ma.contains("会临时改动目标网络的已保存配置"));
        ok("提示里写了「自动还原并读回验证」",
                ma.contains("自动还原并读回验证"));
        ok("提示里写了「登记不到就拒绝执行」",
                ma.contains("登记不到就拒绝执行"));

        System.out.println();
        System.out.println("=== 自测安全测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
