package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.ShellSubcommand;
import io.github.wlmosv_png.wpaforge.cap.BackendCapability;

/**
 * **三后端独立能力门禁**（2026-11-20 第四阶段，任务书第 4 点）。
 *
 * <h3>要钉死的真机事实</h3>
 * OPPO Android 13 / Shizuku shell uid=2000 实测：
 * <pre>
 *   cmd wifi status          ✓
 *   cmd wifi list-networks   ✓
 *   cmd wifi forget-network  ✓
 *   cmd wifi connect-network ✗ SecurityException: Uid 2000 does not have access
 * </pre>
 * 旧实现只看 status 就把通道判成「已接入」，于是任务一路跑到消耗 3 条候选
 * 才由熔断器停下。用户看到的是「跑了 3 条然后说通道不可用」。
 *
 * <h3>核心契约</h3>
 * <ol>
 *   <li>三后端**各自独立**探测，任何一方结论不得给另一方背书。<\/li>
 *   <li>{@code connect-network} 权限被拒 → Shizuku 后端<b>不得</b>
 *       {@code allowsOnlineAuth()}，但**仍可**只读诊断。<\/li>
 *   <li>权限拒绝必须被识别成独立结论（不是 UNKNOWN）。<\/li>
 *   <li>公开 API 的净检恒为 false（删不掉已保存配置）。<\/li>
 * </ol>
 */
public class BackendCapabilityTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        BackendCapabilityTest t = new BackendCapabilityTest();
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
        threeBackendsExist();
        // ── 真机核心：connect-network 被拒
        deniedBlocksOnlineAuth();
        deniedStillAllowsReadOnly();
        deniedIsNotUnknown();
        // ── Root 后端
        rootNeedsUid0();
        // ── 公开 API 后端
        publicApiCannotForget();
        publicApiNeedsSdk29();
        publicApiNeedsPermission();
        // ── 不做跨后端背书
        noCrossBackendEndorsement();
        // ── 闸门在候选循环前生效
        gateBlocksBeforeCandidates();
        // ── 权限拒绝识别
        permissionDeniedDetection();
    }

    private void threeBackendsExist() {
        BackendCapability.Backend[] all = BackendCapability.Backend.values();
        ok("后端共 3 个", all.length == 3);
        ok("有 ROOT_EXECUTOR",
                BackendCapability.Backend.ROOT_EXECUTOR != null);
        ok("有 SHIZUKU_EXECUTOR",
                BackendCapability.Backend.SHIZUKU_EXECUTOR != null);
        ok("有 ANDROID_PUBLIC_WIFI_API",
                BackendCapability.Backend.ANDROID_PUBLIC_WIFI_API != null);
    }

    /** 真机核心：connect-network 权限被拒 → 不许在线认证。 */
    private void deniedBlocksOnlineAuth() {
        BackendCapability.Probe p = BackendCapability.shizukuFrom(
                true, 2000, BackendCapability.CONNECT_DENIED, false);
        ok("权限被拒 → canAssociate=false", !p.canAssociate);
        ok("权限被拒 → canAttribute=false", !p.canAttribute);
        ok("权限被拒 → 不许在线认证", !p.allowsOnlineAuth());
        // 原因必须点明 uid 与 connect-network
        ok("原因提到 uid", p.reasonZh.contains("2000"));
        ok("原因提到 connect-network", p.reasonZh.contains("connect-network"));
        ok("原因说明仍可只读", p.reasonZh.contains("只读"));
        ok("原因提到需要 uid=0", p.reasonZh.contains("uid=0"));
    }

    /** 权限被拒时**只读仍然可用** —— 不能一并判死。 */
    private void deniedStillAllowsReadOnly() {
        BackendCapability.Probe p = BackendCapability.shizukuFrom(
                true, 2000, BackendCapability.CONNECT_DENIED, false);
        ok("权限被拒 → 只读仍可用", p.canReadStatus);
        ok("只读可用不等于在线认证可用",
                p.canReadStatus && !p.allowsOnlineAuth());
    }

    /** 权限拒绝必须是**独立结论**，不能落到 UNKNOWN。 */
    private void deniedIsNotUnknown() {
        ok("CONNECT_DENIED 与 UNKNOWN 不同值",
                BackendCapability.CONNECT_DENIED != ShellSubcommand.UNKNOWN);
        ok("CONNECT_DENIED 与 MISSING 不同值",
                BackendCapability.CONNECT_DENIED != ShellSubcommand.MISSING);
        ok("CONNECT_DENIED 与 AVAILABLE 不同值",
                BackendCapability.CONNECT_DENIED != ShellSubcommand.AVAILABLE);

        // 真机 stderr 原文必须被识别为权限拒绝
        String realStderr = "Exception occurred while executing 'connect-network':\n"
                + "java.lang.SecurityException: Uid 2000 does not have access to "
                + "connect-network wifi command (or such command doesn't exist)\n"
                + "\tat com.android.server.wifi.WifiShellCommand.onCommand("
                + "WifiShellCommand.java:427)";
        ok("识别真机 SecurityException", BackendCapability.isPermissionDenied(realStderr));
        ok("识别 does not have access",
                BackendCapability.isPermissionDenied("Uid 2000 does not have access to x"));
        ok("识别 permission denied",
                BackendCapability.isPermissionDenied("Permission denied"));
        // 正常输出不能被误判
        ok("正常 status 输出不误判",
                !BackendCapability.isPermissionDenied("Wifi is enabled\nWifi is connected"));
        ok("Invalid args 不误判",
                !BackendCapability.isPermissionDenied(
                        "Invalid args for connect-network: Argument expected"));
        ok("空输出不误判", !BackendCapability.isPermissionDenied(""));
    }

    /** Root 后端只认 uid=0。 */
    private void rootNeedsUid0() {
        BackendCapability.Probe no = BackendCapability.rootFrom(false);
        ok("su 不可用 → 不许在线认证", !no.allowsOnlineAuth());
        ok("su 不可用 → 原因非空", !no.reasonZh.isEmpty());

        BackendCapability.Probe yes = BackendCapability.rootFrom(true);
        ok("su 可用 → 允许在线认证", yes.allowsOnlineAuth());
        ok("su 可用 → 能净检", yes.canForget);
        ok("su 可用 → 能归因", yes.canAttribute);
    }

    /** 公开 API 删不掉已保存配置。 */
    private void publicApiCannotForget() {
        BackendCapability.Probe p = BackendCapability.publicApiFrom(true, true, true);
        ok("公开 API → canForget=false", !p.canForget);
        ok("公开 API → 原因说明净检不可用",
                p.reasonZh.contains("无法删除") || p.reasonZh.contains("净检"));
        ok("公开 API → 可提交关联", p.canAssociate);
    }

    private void publicApiNeedsSdk29() {
        BackendCapability.Probe p = BackendCapability.publicApiFrom(false, true, true);
        ok("Android 10 以下 → 不许在线认证", !p.allowsOnlineAuth());
        ok("Android 10 以下 → 原因提到版本", p.reasonZh.contains("Android 10"));
    }

    private void publicApiNeedsPermission() {
        BackendCapability.Probe p = BackendCapability.publicApiFrom(true, false, true);
        ok("缺权限 → 不许在线认证", !p.allowsOnlineAuth());
        ok("缺权限 → 原因提到权限",
                p.reasonZh.contains("NEARBY_WIFI_DEVICES") || p.reasonZh.contains("权限"));
    }

    /** 不做跨后端背书：任一后端可用不代表其它可用。 */
    private void noCrossBackendEndorsement() {
        // 场景：Shizuku 只读可用但认证被拒；Root 不可用；公开 API 可用
        BackendCapability.Probe root = BackendCapability.rootFrom(false);
        BackendCapability.Probe shz = BackendCapability.shizukuFrom(
                true, 2000, BackendCapability.CONNECT_DENIED, false);
        BackendCapability.Probe pub = BackendCapability.publicApiFrom(true, true, true);
        BackendCapability.Set0 set = new BackendCapability.Set0(root, shz, pub);

        ok("有后端可用（公开 API）", set.anyOnlineAuth());
        BackendCapability.Probe pick = set.pickOnlineAuth();
        ok("选中的是公开 API（不是 Shizuku）",
                pick != null && pick.backend == BackendCapability.Backend.ANDROID_PUBLIC_WIFI_API);
        ok("Shizuku 未被误选为可认证", !shz.allowsOnlineAuth());
        ok("Root 未被误选为可认证", !root.allowsOnlineAuth());

        // 全不可用
        BackendCapability.Set0 none = new BackendCapability.Set0(
                BackendCapability.rootFrom(false),
                BackendCapability.shizukuFrom(true, 2000,
                        BackendCapability.CONNECT_DENIED, false),
                BackendCapability.publicApiFrom(false, false, false));
        ok("全不可用 → anyOnlineAuth=false", !none.anyOnlineAuth());
        ok("全不可用 → pickOnlineAuth=null", none.pickOnlineAuth() == null);
    }

    /** 闸门必须在候选循环前生效（源码契约）。 */
    private void gateBlocksBeforeCandidates() {
        String eng = readSource("audit/CrackEngine.java");
        ok("probeChannel 识别 CONNECT_DENIED",
                eng.contains("CONNECT_DENIED"));
        ok("权限被拒直接 return 失败 ChannelProbe",
                eng.contains("connect-network 权限被拒"));
        ok("失败信息说明一条候选都不消耗",
                eng.contains("一条候选都不会消耗"));
        ok("给出替代路径（root / 公开 API）",
                eng.contains("让 Shizuku 以 root 身份启动服务")
                        || eng.contains("公开 Wi-Fi API"));
        // 顺序：CONNECT_DENIED 分支必须在「未知放行」之前
        int deniedAt = eng.indexOf("connect-network 权限被拒");
        int unknownAt = eng.indexOf("connect-network 探测没拿到判定性输出");
        ok("DENIED 判定排在「未知放行」之前",
                deniedAt > 0 && unknownAt > 0 && deniedAt < unknownAt);
    }

    /** 权限拒绝的识别必须优先于普通 detect。 */
    private void permissionDeniedDetection() {
        String eng = readSource("audit/CrackEngine.java");
        ok("probeSubcommand 先查权限拒绝",
                eng.contains("isPermissionDenied(out)"));
        int deniedAt = eng.indexOf("isPermissionDenied(out)");
        int detectAt = eng.indexOf("ShellSubcommand.detect(out, name)");
        ok("权限判定在 detect 之前", deniedAt > 0 && detectAt > deniedAt);
        ok("DENIED 标签为「权限被拒」", eng.contains("权限被拒"));
    }
}
