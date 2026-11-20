package io.github.wlmosv_png.wpaforge.cap;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

import io.github.wlmosv_png.wpaforge.audit.RootProber;
import io.github.wlmosv_png.wpaforge.audit.ShellExec;

/**
 * 能力探测器：**把真实事实取回来，交给 {@link CapabilityProbe} 解释**。
 *
 * <h3>只做两件事</h3>
 * <ol>
 *   <li>取事实：{@code su -c id}、{@code cmd wifi} 用法表、WifiManager 是否可用、
 *       运行时权限是否齐。全部是**实测**，不从版本号推断；</li>
 *   <li>把结果缓存成不可变快照，界面各页读同一份 —— 不允许各页各测一遍。</li>
 * </ol>
 *
 * <h3>为什么「探测不到」不写成「不支持」</h3>
 * 见 {@link CapabilityProbe#explainRoot} 的注释。这里只保证一件事：
 * 拿不到的事实传 {@code null}，绝不传空串冒充「查过且没有」。
 */
public final class CapabilityDetector {

    /** 探测结果的缓存有效期：太短会反复起 su（每次约 137ms），太长会让用户改完权限看不到变化。 */
    public static final long CACHE_MS = 30_000L;

    private static volatile Capability.Set0 cached;
    private static volatile long cachedAtMs;
    private static volatile String cachedMode;

    private CapabilityDetector() {
    }

    /** 事件时间（与引擎的 EngineClock 分开：这里只需要墙钟，用来判断缓存是否过期）。 */
    private static long now() {
        return System.currentTimeMillis();
    }

    /** 强制重新探测。 */
    public static Capability.Set0 probe(Context ctx, int modeId) {
        List<Capability> items;
        List<Capability> explain;
        long at = now();

        if (modeId == RunMode.ROOTLESS) {
            CapabilityProbe.Facts f = new CapabilityProbe.Facts();
            f.suId = suId();
            f.wifiManager = hasWifiManager(ctx);
            f.scanPermission = hasScanPermission(ctx);
            f.wpaLogNonEmpty = readOwnSupplicantLogNonEmpty();
            // 真实探测在线验证能力：API 29+ 且有 ConnectivityManager 才算能用。
            // 这一步就是「UI 能不能点开始」的依据，必须来自实测而不是写死。
            String specWhy = specifierUnavailableReason(ctx);
            f.specifierUsable = specWhy == null;
            f.specifierReason = specWhy == null ? "" : specWhy;
            // Shizuku 探测：这是无 Root 自动连接的主路，必须实测。
            io.github.wlmosv_png.wpaforge.cap.ShizukuChannel shz =
                    new io.github.wlmosv_png.wpaforge.cap.ShizukuChannel(ctx);
            int st = shz.probe();
            f.shizukuUsable = shz.usable();
            f.shizukuReason = shz.usable() ? "" : shz.stateDetail();
            // **记录真实执行身份**（2026-11-20）。
            // 「Shizuku 可用」只说明拿到 binder；拿到的是 uid 0 / 1000 / 2000
            // 决定了哪些能力真的能做（例如 WifiConfigStore.xml 只有 root 能读）。
            f.shizukuIdentity = shz.usable() ? shz.identity() : null;
            f.probedAtMs = now();
            items = CapabilityProbe.explainRootless(f);
        } else {
            String su = suId();
            String usage = null;
            // **实际调用优先于用法表**（2026-11-20 真机事故后改）。
            // 只 grep 用法表会把好机器判成「没有 connect-network」——
            // 用户在安卓 13 上就撞到了（status 有、forget 有、中间那个没有）。
            CapabilityProbe.Facts rf = new CapabilityProbe.Facts();
            if (su != null && su.contains("uid=0")) {
                usage = RootProber.exec(null, new String[]{"su", "-c", "cmd wifi"}, 8000);
                rf.cmdConnectProbe = RootProber.exec(null,
                        new String[]{"su", "-c", "cmd wifi connect-network"}, 6000);
                rf.cmdForgetProbe = RootProber.exec(null,
                        new String[]{"su", "-c", "cmd wifi forget-network"}, 6000);
                rf.cmdStatusProbe = RootProber.exec(null,
                        new String[]{"su", "-c", "cmd wifi status"}, 6000);
            }
            items = CapabilityProbe.explainRoot(su, usage, rf,
                    hasWifiManager(ctx), hasScanPermission(ctx));
        }

        Capability.Set0 s = new Capability.Set0(RunMode.nameOf(modeId), at, items);
        cached = s;
        cachedAtMs = at;
        cachedMode = RunMode.nameOf(modeId);
        return s;
    }

    /**
     * 取能力快照（命中缓存就不重新探测）。
     *
     * <p><b>缓存按模式分开</b>：切模式时必须重新探测，否则无 Root 模式会拿到
     * Root 模式的结论（这正是旧代码「一个全局 mode 判所有通道」的老毛病）。
     */
    public static Capability.Set0 get(Context ctx, int modeId) {
        Capability.Set0 s = cached;
        String want = RunMode.nameOf(modeId);
        if (s != null && want.equals(cachedMode) && now() - cachedAtMs < CACHE_MS) {
            return s;
        }
        return probe(ctx, modeId);
    }

    /** 只读缓存，不触发探测（界面首帧用，避免开屏卡在 su 上）。 */
    public static Capability.Set0 peek() {
        return cached;
    }

    public static void invalidate() {
        cached = null;
        cachedAtMs = 0L;
        cachedMode = null;
    }

    // ------------------------------------------------------------ 事实采集

    private static String suId() {
        try {
            ShellExec.Result r = ShellExec.run(new String[]{"su", "-c", "id"}, 12000);
            if (r.timedOut) return null;
            String out = r.output;
            return out == null ? null : out.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 本机能否用 {@code WifiNetworkSpecifier} 做在线验证。
     *
     * @return {@code null} = 可用；否则是中文原因
     */
    static String specifierUnavailableReason(Context ctx) {
        if (ctx == null) return "没有应用上下文";
        if (Build.VERSION.SDK_INT < 29) {
            return "系统版本低于 Android 10（没有 WifiNetworkSpecifier）";
        }
        try {
            if (ctx.getSystemService(ConnectivityManager.class) == null) {
                return "系统没有提供 ConnectivityManager";
            }
        } catch (Throwable t) {
            return "读取 ConnectivityManager 失败：" + t.getClass().getSimpleName();
        }
        return null;
    }

    private static boolean hasWifiManager(Context ctx) {
        try {
            return ctx != null
                    && ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI)
                    && ctx.getSystemService(WifiManager.class) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 扫描权限：Android 13+ 是「附近设备」，12 及以下是定位。任一具备即可。 */
    public static boolean hasScanPermission(Context ctx) {
        if (ctx == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && ctx.checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES")
                    == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
            return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 以**本应用自己的 UID** 读一条 supplicant 日志，判断到底读不读得到。
     *
     * <p>不用非空串判「能读」，因为 {@code logcat -d -s <tag>} 在没权限时
     * 就是静默返回空 —— 空串本身证明不了什么。这里多问一次「有没有任何日志」，
     * 只有两类都拿到内容才算能读。
     */
    private static boolean readOwnSupplicantLogNonEmpty() {
        try {
            ShellExec.Result r = ShellExec.run(
                    new String[]{"logcat", "-d", "-s", "wpa_supplicant", "-t", "20"}, 6000);
            return !r.timedOut && r.output != null && r.output.trim().length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------ 模式判定

    /**
     * 决定**实际**运行模式。
     *
     * <p>规则（顺序即优先级）：
     * <ol>
     *   <li>用户明确选了无 Root → 用无 Root（不因为「有 root 就偷偷升级」——
     *       用户选什么就是什么，否则他无从判断自己在跑哪条链）；</li>
     *   <li>用户选了 Root 但 su 不可用 → 落到无 Root，并**必须**给出提示
     *       （默默降级就是让用户以为在跑 Root 链）；</li>
     *   <li>自动：su 可用 → Root；否则 → 无 Root。</li>
     * </ol>
     */
    public static RunMode resolve(Context ctx, int requestedMode) {
        Capability.Set0 rootCaps = get(ctx, RunMode.ROOT);
        Capability shellCap = rootCaps.get(Capability.C_SHELL);
        boolean suOk = Capability.STATE_AVAILABLE.equals(shellCap.state);

        if (requestedMode == RunMode.ROOTLESS) {
            return RunMode.rootless(RunMode.nameOf(RunMode.ROOTLESS), RunMode.SRC_USER,
                    get(ctx, RunMode.ROOTLESS).all());
        }
        if (requestedMode == RunMode.AUTO) {
            return suOk
                    ? RunMode.root(RunMode.nameOf(RunMode.AUTO), RunMode.SRC_AUTO, rootCaps.all())
                    : RunMode.rootless(RunMode.nameOf(RunMode.AUTO), RunMode.SRC_AUTO,
                            get(ctx, RunMode.ROOTLESS).all());
        }
        // 用户选了 Root
        if (suOk) {
            return RunMode.root(RunMode.nameOf(RunMode.ROOT), RunMode.SRC_USER, rootCaps.all());
        }
        List<Capability> rl = new ArrayList<>(get(ctx, RunMode.ROOTLESS).all());
        String whyZh = shellCap.reasonZh.isEmpty()
                ? "su 不可用：设备未 root，或 root 管理器没有给本应用授权"
                : shellCap.reasonZh;
        return RunMode.of(RunMode.ROOTLESS, RunMode.nameOf(RunMode.ROOT), RunMode.SRC_FALLBACK,
                "你选择的是 Root 模式，但本机 " + whyZh + "。已切到无 Root 模式："
                + "离线分析与字典功能照常，逐条关联与 WPS PIN 不可用。",
                "Root mode was selected, but " + shellCap.reasonEn
                + " Switched to rootless: offline analysis and dictionaries still work; "
                + "per-candidate association and WPS PIN do not.",
                whyZh, shellCap.reasonEn, rl);
    }
}
