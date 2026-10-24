package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

/**
 * Root 模式连接器。通过 su 调用系统自带的 `cmd wifi` 命令驱动 WiFi 连接。
 *
 * 为什么需要 root：
 *   Android 10+ 的 WifiNetworkSpecifier 会强制弹出系统确认框，普通应用无法静默连接，
 *   所以那条路做不了字典遍历。而 `cmd wifi connect-network` 是 shell 权限的命令，
 *   root 可以调用，不弹窗、能拿真实结果。
 *
 * 实测行为（本机验证）：
 *   正确密码 → 约 12 秒后 `Wifi is connected to "<SSID>"`
 *   错误密码 → 约 3 秒后 `Wifi is not connected`
 *
 * 关键细节：连接前必须先 forgetNetwork，否则系统可能复用已保存的旧密码，
 * 造成"不管传什么密码都成功"的假阳性。
 *
 * 取消机制：所有子进程都记录在案，cancel() 会直接杀掉它们，
 * 不用等阻塞调用自然返回。
 */
public final class RootProber {

    /*
     * F4（同族清理）：这里原来还有一整套**未加固的判定链** ——
     *   Verdict、Attempt、Listener、Outcome、tryOne、forgetTarget、
     *   disconnectTarget、execTracked、cancel、pause 与相关字段。
     *
     * 它们没有任何调用者（连实例化都不存在），却自带两条危险原语：
     *   其一删掉目标的已保存配置、其二用候选口令覆盖它；
     * 并自己产出 AUTH_FAIL / TIMEOUT 结论 —— 一旦被接回生产链，
     * 就**绕过全部新规则**（窗口隔离、归属闸门、收尾恢复），
     * 而且会与收尾恢复互相拆台。已整体删除。
     *
     * 现在本类只剩「环境自检 + 同步执行」四项：check / checkRoot / lastTimedOut / exec。
     * 需要跑候选请一律走 CrackEngine（它带 fail-closed 闸门与恢复流程）。
     */

    private static final String TAG = "WPAForgeRoot";

    /** 检测结果（带可操作的提示） */
    public static final class CheckResult {
        public final boolean ok;
        public final String title;    // 一句话结论
        public final String detail;   // 详细原因 / 怎么办

        public CheckResult(boolean ok, String title, String detail) {
            this.ok = ok;
            this.title = title;
            this.detail = detail;
        }
    }

    /**
     * 检查 root 与 cmd wifi 是否可用。
     *
     * 三重检测，逐级放宽，避免慢设备或输出截断造成的误判：
     *   1. su 是否可用（id 返回 uid=0）
     *   2. cmd wifi 是否存在
     *   3. connect-network 子命令是否存在
     *
     * 任何一步失败都给出「怎么办」，而不是一句"不支持"。
     */
    public static CheckResult check(Context ctx) {
        // 1) root
        String idOut = exec(null, new String[]{"su", "-c", "id"}, 12000);
        if (idOut == null) {
            return new CheckResult(false,
                    L.s("无法执行 su", "Cannot run su"),
                    L.s("没有检测到 root 权限。\n\n"
                            + "请确认：\n"
                            + "· 设备已 root（Magisk / KernelSU / APatch）\n"
                            + "· 在 root 管理器里给本应用授予了权限\n"
                            + "· 授权后重新打开本应用",
                        "Root was not detected.\n\n"
                            + "Check that:\n"
                            + "· the device is rooted (Magisk / KernelSU / APatch)\n"
                            + "· this app is granted root in your root manager\n"
                            + "· you reopen this app after granting"));
        }
        if (!idOut.contains("uid=0")) {
            return new CheckResult(false,
                    L.s("root 权限被拒绝", "Root denied"),
                    L.s("su 执行了但没有拿到 root。\n\n"
                            + "打开你的 root 管理器，在「超级用户」列表里\n"
                            + "找到 WPAForge 并允许。",
                        "su ran but did not return root.\n\n"
                            + "Open your root manager, find WPAForge under\n"
                            + "Superuser and allow it."));
        }

        // 2) cmd wifi 是否存在
        String wifiHelp = exec(null, new String[]{"su", "-c", "cmd wifi help"}, 20000);
        if (wifiHelp == null || wifiHelp.trim().isEmpty()) {
            return new CheckResult(false,
                    L.s("无法调用 cmd wifi", "Cannot invoke cmd wifi"),
                    L.s("系统没有响应 cmd wifi 命令。\n\n"
                            + "这通常说明：\n"
                            + "· 系统版本过低（Android 9 及以下没有这个命令）\n"
                            + "· 厂商裁剪了 wifi 服务\n\n"
                            + "本功能需要 Android 10 或更高版本。",
                        "The system did not respond to cmd wifi.\n\n"
                            + "Usually means:\n"
                            + "· Android 9 or lower (the command does not exist)\n"
                            + "· the vendor stripped the wifi service\n\n"
                            + "This feature needs Android 10 or higher."));
        }

        // 3) connect-network 子命令
        if (wifiHelp.contains("connect-network")) {
            return new CheckResult(true, "", "");
        }

        // 有些 ROM 的 help 输出被截断，再宽一点：直接问 add-network / forget-network
        String extra = exec(null, new String[]{"su", "-c", "cmd wifi list-networks"}, 20000);
        if (extra != null && (extra.contains("Network Id") || extra.contains("SSID"))) {
            // 能列出网络说明 wifi 服务是活着的，connect-network 大概率也在，
            // 只是 help 文本被截断。放行，真出错时执行阶段会报出来。
            return new CheckResult(true, "", "");
        }

        return new CheckResult(false,
                L.s("本机不支持连接命令", "Device lacks the connect command"),
                L.s("你的系统 cmd wifi 里没有 connect-network。\n\n"
                        + "常见于：\n"
                        + "· Android 10 以下的设备\n"
                        + "· 厂商深度定制的 ROM\n\n"
                        + "可以试试换一台 Android 11 以上的设备。",
                    "Your system's cmd wifi has no connect-network.\n\n"
                        + "Common on:\n"
                        + "· devices below Android 10\n"
                        + "· heavily customised ROMs\n\n"
                        + "Try an Android 11+ device."));
    }

    /** 兼容旧调用：只返回错误文案（空串表示可用） */
    public static String checkRoot() {
        CheckResult r = check(null);
        if (r.ok) return "";
        return r.title + (r.detail.isEmpty() ? "" : "\n" + r.detail);
    }

    /** 最近一次 exec 是否超时（供通道熔断判断） */
    private static volatile boolean lastTimedOut = false;

    public static boolean lastTimedOut() {
        return lastTimedOut;
    }

    /**
     * 同步执行并拿 stdout，超时返回 null。
     *
     * <p>实现委托给 {@link ShellExec} —— 超时后它终止的是**整棵进程树**并关闭管道，
     * 而不是只杀直接子进程。旧实现只杀 su，孙进程（sh → cmd wifi）仍持有管道写端，
     * 上层 {@code readLine()} 永远等不到 EOF，整条引擎线程就此卡死
     * （实测：切后台后任务完全不动，前台才继续）。
     */
    public static String exec(Object ctx, String[] cmd, long timeoutMs) {
        ShellExec.Result r = ShellExec.run(cmd, timeoutMs);
        lastTimedOut = r.timedOut;
        return r.timedOut ? null : r.output;
    }
}