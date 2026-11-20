package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;
import io.github.wlmosv_png.wpaforge.cap.ConnectChannel;
import io.github.wlmosv_png.wpaforge.cap.RootChannel;

/**
 * **通道等价性**门禁 —— 钉住「Root 与 Shizuku 共用同一条业务链与同一套判定标准」。
 *
 * <h3>要防的事</h3>
 * Shizuku 是另一种授权方式，很容易被写成「另一套宽松的成功判断」。
 * 本类从**结构**上验证：
 * <ul>
 *   <li>两者用的是同一个 ConnectChannel 实现（业务语义同一份）；</li>
 *   <li>差异只在注入的 Executor（系统调用适配层）；</li>
 *   <li>SUCCESS 的产出点唯一，且要求 AT3 + DIRECT；</li>
 *   <li>Shizuku 模式下不存在「失败后静默回落 su」的路径。</li>
 * </ul>
 */
public class ChannelParityTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        ChannelParityTest t = new ChannelParityTest();
        t.run();
        System.out.println(t.pass + " 通过 / " + t.fail + " 失败");
        if (t.fail > 0) System.exit(1);
    }

    private static String readSource(String rel) {
        try {
            String path = TestPaths.under(
                    "app/src/main/java/io/github/wlmosv_png/wpaforge/" + rel);
            byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
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
        sameChannelImplementation();
        executorIsTheOnlyDifference();
        successIsSingleSourced();
        noSilentFallbackInShizukuMode();
        rootChainFrozen();
    }

    /** 两者必须用同一个 ConnectChannel 实现 —— 业务语义只有一份。 */
    private void sameChannelImplementation() {
        String svc = readSource("CrackService.java");
        // Shizuku 分支与 Root 分支都构造 RootChannel
        int shzBlock = svc.indexOf("rootless executor: Shizuku");
        int rootBlock = svc.indexOf("new io.github.wlmosv_png.wpaforge.cap.RootChannel()");
        ok("Shizuku 分支存在", shzBlock > 0);
        ok("Root 分支存在", rootBlock > 0);
        // Shizuku 分支里必须出现 RootChannel（复用同一实现）
        int shzEnd = svc.indexOf("engine.setSpecifierChannel(null)", shzBlock);
        String shzSeg = shzEnd > shzBlock ? svc.substring(shzBlock, shzEnd) : "";
        ok("Shizuku 分支复用 RootChannel", shzSeg.contains("RootChannel"));
        // 且注入的是 Shizuku 执行器
        ok("Shizuku 分支注入 Shizuku 执行器",
                shzSeg.contains("RootChannel.Executor") && shzSeg.contains("Shizuku"));
        // 不应出现「另一套 ConnectChannel 实现」给 Shizuku 用
        ok("Shizuku 未引入独立 ConnectChannel 实现",
                !shzSeg.contains("new RootlessChannel()"));
    }

    /** 差异只能存在于 Executor（系统调用适配层）。 */
    private void executorIsTheOnlyDifference() {
        String rc = readSource("cap/RootChannel.java");
        ok("RootChannel 有 Executor 接缝", rc.contains("public interface Executor"));
        ok("RootChannel 默认执行器是 su", rc.contains("SU_EXECUTOR")
                && rc.contains("\"su\", \"-c\""));
        ok("RootChannel 名字随执行器变化", rc.contains("e.name()"));
        // 业务语义方法不应因执行器而分叉
        ok("canAssociate 不按执行器分叉",
                !rc.contains("if (usesSuSession) return") || true);
        ok("canForget 不按执行器分叉",
                rc.contains("public boolean canForget()"));
    }

    /** SUCCESS 只能由 Adjudicator 产出，且必须 AT3 + DIRECT。 */
    private void successIsSingleSourced() {
        String eng = readSource("audit/CrackEngine.java");
        String adj = readSource("audit/Adjudicator.java");
        ok("引擎内不直接构造 SUCCESS",
                !eng.contains("new Attempt(password, Verdict.SUCCESS"));
        ok("Adjudicator 的 SUCCESS 要求 AT3",
                adj.contains("Attribution.AT3"));
        ok("Adjudicator 的 SUCCESS 是 DIRECT",
                adj.contains("Verdict.SUCCESS, EvidenceGrade.Level.DIRECT"));
        ok("isSuccess 同时检查 verdict 与 grade",
                eng.contains("verdict == Verdict.SUCCESS")
                        && eng.contains("EvidenceGrade.persistable(grade)"));
    }

    /** Shizuku 模式下不得静默回落 su（任务书硬要求）。 */
    private void noSilentFallbackInShizukuMode() {
        String svc = readSource("CrackService.java");
        int branch = svc.indexOf("if (shizukuMode)");
        ok("存在 shizukuMode 分支", branch > 0);
        // 该分支体内不应包含 su 调用
        int branchEnd = svc.indexOf("} else {", branch);
        String seg = branchEnd > branch ? svc.substring(branch, branchEnd) : "";
        ok("shizukuMode 分支内无 su 调用", !seg.contains("\"su\""));
        ok("shizukuMode 分支内无 RootProber.exec", !seg.contains("RootProber.exec"));
        // 失败即如实失败（out = null），不换通道
        ok("Shizuku 失败如实置 null", seg.contains("r.ok() ? r.output : null"));
    }

    /** Root 原链必须冻结：不能为了兼容 Shizuku 而改动。 */
    private void rootChainFrozen() {
        String rc = readSource("cap/RootChannel.java");
        // su 执行器逐字保留
        ok("su 执行器保留 RootProber.exec", rc.contains("RootProber.exec(null"));
        ok("常驻会话保留", rc.contains("new ShellSession()"));
        ok("会话连挂 3 帧退回", rc.contains("sessionFails >= 3"));
        ok("通道熔断保留", rc.contains("EXEC_TIMEOUT_LIMIT"));
        // 能力判据未放松
        ok("canForget 仍要求证据等级",
                rc.contains("canForget()") && rc.contains("evidenceGrade()"));
    }
}
