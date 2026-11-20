package io.github.wlmosv_png.wpaforge.cap;

import android.content.Context;

import io.github.wlmosv_png.wpaforge.audit.ShellSubcommand;

/**
 * **三后端独立能力探测**（2026-11-20 第四阶段，任务书第 4 点）。
 *
 * <h3>要修的问题</h3>
 * 改造前「能不能跑在线认证」的结论来自**一个混合判断**：
 * <pre>
 *   if (shizukuUsable) → 认为能跑 cmd wifi connect-network
 * </pre>
 * 真机（OPPO Android 13 / shell uid=2000）的实际结果：
 * <pre>
 *   cmd wifi status          ✓ 可用
 *   cmd wifi list-networks   ✓ 可用
 *   cmd wifi forget-network  ✓ 可用
 *   cmd wifi connect-network ✗ SecurityException: Uid 2000 does not have access
 * </pre>
 * 于是「Shizuku 可用」被错误地当成了「能跑在线认证」，
 * 任务一路跑到消耗 3 条候选才熔断。
 *
 * <h3>现在的规则</h3>
 * 三个后端**各自独立探测、互不背书**：
 * <ul>
 *   <li>{@link Backend#ROOT_EXECUTOR} —— 只认 {@code su -c id} 返回 uid=0<\/li>
 *   <li>{@link Backend#SHIZUKU_EXECUTOR} —— 只认<b>实际跑通</b>的
 *       {@code connect-network}（权限被拒就是不可用）<\/li>
 *   <li>{@link Backend#ANDROID_PUBLIC_WIFI_API} —— 只认公开 API 可用
 *       （{@code WifiNetworkSpecifier} 能否构造 + 权限是否齐备）<\/li>
 * <\/ul>
 *
 * <p><b>任何一个后端的能力结论都不得用于另一个后端。</b>
 * Shizuku 能做只读诊断，不代表能跑在线认证。
 *
 * <p>纯逻辑部分（{@link Verdict} 的推导）无 Android 依赖，可离线穷举；
 * 实际探测在 {@link #probeAll} 里做。
 */
public final class BackendCapability {

    /** 三个后端。 */
    public enum Backend {
        ROOT_EXECUTOR("Root", "root"),
        SHIZUKU_EXECUTOR("Shizuku", "shizuku"),
        ANDROID_PUBLIC_WIFI_API("公开 API", "public-api");

        public final String zh;
        public final String key;

        Backend(String zh, String key) {
            this.zh = zh;
            this.key = key;
        }

        public String label(boolean zh) {
            return zh ? this.zh : this.key;
        }
    }

    /** 单个后端的探测结论。 */
    public static final class Probe {
        public final Backend backend;
        /** 能否执行**只读**诊断（status / list-networks）。 */
        public final boolean canReadStatus;
        /** 能否执行**在线认证**（逐条关联）。 */
        public final boolean canAssociate;
        /** 能否做净检（删旧配置）。 */
        public final boolean canForget;
        /** 能否取得可归因到本次候选的认证证据。 */
        public final boolean canAttribute;
        /** 人话原因（不能认证时说明缺什么）。 */
        public final String reasonZh;
        public final String reasonEn;
        /** 原始证据摘要（写日志用，不含敏感信息）。 */
        public final String evidence;

        public Probe(Backend backend, boolean canReadStatus, boolean canAssociate,
                     boolean canForget, boolean canAttribute,
                     String reasonZh, String reasonEn, String evidence) {
            this.backend = backend;
            this.canReadStatus = canReadStatus;
            this.canAssociate = canAssociate;
            this.canForget = canForget;
            this.canAttribute = canAttribute;
            this.reasonZh = reasonZh == null ? "" : reasonZh;
            this.reasonEn = reasonEn == null ? "" : reasonEn;
            this.evidence = evidence == null ? "" : evidence;
        }

        /** 是否允许启动在线认证任务。 */
        public boolean allowsOnlineAuth() {
            return canAssociate && canAttribute;
        }

        public String describe(boolean zh) {
            StringBuilder sb = new StringBuilder();
            sb.append(backend.label(zh)).append(": ");
            sb.append(zh ? "只读=" : "read=").append(canReadStatus ? "✓" : "✗");
            sb.append(" ").append(zh ? "认证=" : "auth=").append(canAssociate ? "✓" : "✗");
            sb.append(" ").append(zh ? "净检=" : "forget=").append(canForget ? "✓" : "✗");
            sb.append(" ").append(zh ? "归因=" : "attr=").append(canAttribute ? "✓" : "✗");
            String why = zh ? reasonZh : reasonEn;
            if (!why.isEmpty()) sb.append(" — ").append(why);
            return sb.toString();
        }
    }

    /** 三后端汇总。 */
    public static final class Set0 {
        public final Probe root;
        public final Probe shizuku;
        public final Probe publicApi;

        public Set0(Probe root, Probe shizuku, Probe publicApi) {
            this.root = root;
            this.shizuku = shizuku;
            this.publicApi = publicApi;
        }

        /** 是否**至少有一个**后端能跑在线认证。 */
        public boolean anyOnlineAuth() {
            return (root != null && root.allowsOnlineAuth())
                    || (shizuku != null && shizuku.allowsOnlineAuth())
                    || (publicApi != null && publicApi.allowsOnlineAuth());
        }

        /**
         * 选出一个可用于在线认证的后端；都没有则返回 null。
         *
         * <p>优先级：Root（证据最硬）→ Shizuku → 公开 API。
         * **不做跨后端背书**：每个都用自己的 {@code allowsOnlineAuth()}。
         */
        public Probe pickOnlineAuth() {
            if (root != null && root.allowsOnlineAuth()) return root;
            if (shizuku != null && shizuku.allowsOnlineAuth()) return shizuku;
            if (publicApi != null && publicApi.allowsOnlineAuth()) return publicApi;
            return null;
        }

        /** 一行汇总（写日志用）。 */
        public String summary(boolean zh) {
            StringBuilder sb = new StringBuilder();
            sb.append(root == null ? "" : root.describe(zh));
            sb.append(" ｜ ");
            sb.append(shizuku == null ? "" : shizuku.describe(zh));
            sb.append(" ｜ ");
            sb.append(publicApi == null ? "" : publicApi.describe(zh));
            return sb.toString();
        }
    }

    private BackendCapability() {
    }

    // ------------------------------------------------------------ 纯逻辑推导

    /**
     * 由**实际观测**推导 Shizuku 后端的结论（纯逻辑，可离线测）。
     *
     * @param usable         Shizuku 通道是否可用（binder + 授权）
     * @param identityUid    实际执行身份 uid（-1 = 未知）
     * @param connectNetwork connect-network 的探测结果
     *                       （{@link ShellSubcommand} 的常量，或用
     *                       {@link #CONNECT_DENIED} 表示权限被拒）
     * @param canReadLogcat  logcat 是否可读（决定能否取得握手日志证据）
     */
    public static Probe shizukuFrom(boolean usable, int identityUid, int connectNetwork,
                                     boolean canReadLogcat) {
        if (!usable) {
            return new Probe(Backend.SHIZUKU_EXECUTOR, false, false, false, false,
                    "Shizuku 服务未运行或未授权", "shizuku not running / not granted", "");
        }
        boolean status = true;      // binder 通了且授权，status 一般可用
        // **关键：connect-network 的探测结论直接决定能否在线认证**
        boolean canConnect = (connectNetwork == ShellSubcommand.AVAILABLE);
        boolean denied = (connectNetwork == CONNECT_DENIED);

        String reasonZh = "";
        String reasonEn = "";
        if (!canConnect) {
            if (denied) {
                // 真机现场：OPPO Android 13 / uid=2000
                reasonZh = "connect-network 被系统拒绝（Uid " + identityUid
                        + " does not have access）；"
                        + "该子命令要求 uid=0，shell 身份无法执行在线认证。"
                        + "仍可用于只读诊断。";
                reasonEn = "connect-network denied by system (Uid " + identityUid
                        + " does not have access); requires uid=0. Read-only only.";
            } else {
                reasonZh = "connect-network 不可用（探测结论="
                        + ShellSubcommand.label(connectNetwork, true) + "）";
                reasonEn = "connect-network unavailable (probe="
                        + ShellSubcommand.label(connectNetwork, false) + ")";
            }
        }

        // 归因能力：需要能读 status 拿四条件 + （最好）能读 supplicant 日志
        // 只有 canConnect 成立时，「归因」才有意义（否则根本没有本次连接）
        boolean canAttribute = canConnect && status;

        String evidence = "uid=" + identityUid
                + " connect-network=" + (denied ? "DENIED"
                        : ShellSubcommand.label(connectNetwork, false))
                + " logcat=" + (canReadLogcat ? "yes" : "no");

        return new Probe(Backend.SHIZUKU_EXECUTOR, status, canConnect,
                canConnect, canAttribute, reasonZh, reasonEn, evidence);
    }

    /** {@link ShellSubcommand} 之外新增的探测结论：**命令存在但权限被拒**。 */
    public static final int CONNECT_DENIED = -2;

    /**
     * 由**实际观测**推导 Root 后端的结论（纯逻辑）。
     *
     * @param suReturnsUid0 {@code su -c id} 是否真的返回 uid=0
     */
    public static Probe rootFrom(boolean suReturnsUid0) {
        if (!suReturnsUid0) {
            return new Probe(Backend.ROOT_EXECUTOR, false, false, false, false,
                    "su 不可用（未 root 或未授权本应用）",
                    "su unavailable (not rooted or not granted)", "");
        }
        // root 下 cmd wifi 全部子命令都可用（AOSP 白名单外要求 uid=0，root 满足）
        return new Probe(Backend.ROOT_EXECUTOR, true, true, true, true,
                "", "", "uid=0");
    }

    /**
     * 由**实际观测**推导公开 API 后端的结论（纯逻辑）。
     *
     * @param sdkAtLeast29 是否 Android 10+（WifiNetworkSpecifier 要求）
     * @param hasPermission 是否具备 NEARBY_WIFI_DEVICES（13+）或定位权限
     * @param cmAvailable   ConnectivityManager 是否可用
     */
    public static Probe publicApiFrom(boolean sdkAtLeast29, boolean hasPermission,
                                      boolean cmAvailable) {
        if (!sdkAtLeast29) {
            return new Probe(Backend.ANDROID_PUBLIC_WIFI_API, false, false, false, false,
                    "需要 Android 10+（WifiNetworkSpecifier）",
                    "requires Android 10+ (WifiNetworkSpecifier)", "");
        }
        if (!cmAvailable) {
            return new Probe(Backend.ANDROID_PUBLIC_WIFI_API, false, false, false, false,
                    "系统没有 ConnectivityManager", "no ConnectivityManager", "");
        }
        if (!hasPermission) {
            return new Probe(Backend.ANDROID_PUBLIC_WIFI_API, false, false, false, false,
                    "缺少 NEARBY_WIFI_DEVICES / 定位权限",
                    "missing NEARBY_WIFI_DEVICES / location permission", "");
        }
        // 公开 API 能读状态（有权限时）、能提交关联请求，但**不能净检**（删不掉已保存配置）
        // 归因能力：靠 NetworkCapabilities.getTransportInfo + LinkProperties 判「本次新建连接」，
        // 但拿不到「是不是这条 PSK 完成握手」—— 所以 canAttribute 只表示「能取得连接级证据」，
        // 最终仍由 PublicWifiVerdict 决定是否 CONFIRMED。
        return new Probe(Backend.ANDROID_PUBLIC_WIFI_API, true, true, false, true,
                "公开 API 无法删除已保存配置（净检不可用）；"
                        + "认证证据只能到「本次新建连接 + 目标 SSID + 有效地址」",
                "public API cannot forget saved configs; evidence limited to "
                        + "new-connection + SSID + address", "sdk>=29");
    }

    // ------------------------------------------------------------ 实际探测

    /**
     * **实际探测三个后端**（会起 su / 跑 cmd wifi，不要在主线程调用）。
     *
     * <p>每一步都独立，任何一个失败不影响其它两个的结论。
     */
    public static Set0 probeAll(Context ctx, ShizukuChannel shz) {
        // ① Root
        boolean suOk = ExecutorProvider.probeSu();
        Probe root = rootFrom(suOk);

        // ② Shizuku：必须实测 connect-network，不能只看「Shizuku 可用」
        Probe shzProbe;
        int uid = -1;
        int connectSt = ShellSubcommand.UNKNOWN;
        boolean logcat = false;
        if (shz == null || !ChannelReadiness.usable(shz.state())) {
            shzProbe = shizukuFrom(false, -1, ShellSubcommand.UNKNOWN, false);
        } else {
            try {
                ExecutorIdentity id = shz.identity();
                uid = id == null ? -1 : id.uid;
            } catch (Throwable ignored) {}
            // **关键：真的跑一次 connect-network（不给参数，只触发权限检查）**
            connectSt = probeConnectNetwork(shz);
            // logcat 可读性（只读）
            try {
                ShizukuChannel.Result lr = shz.exec("logcat -d -t 1 -s wpa_supplicant", 3000);
                logcat = lr != null && lr.ok() && !lr.timedOut;
            } catch (Throwable ignored) {}
            shzProbe = shizukuFrom(true, uid, connectSt, logcat);
        }

        // ③ 公开 API
        boolean sdkOk = android.os.Build.VERSION.SDK_INT >= 29;
        boolean perm = hasWifiPermission(ctx);
        boolean cmOk = false;
        try {
            cmOk = ctx != null
                    && ctx.getSystemService(android.net.ConnectivityManager.class) != null;
        } catch (Throwable ignored) {}
        Probe pub = publicApiFrom(sdkOk, perm, cmOk);

        return new Set0(root, shzProbe, pub);
    }

    /**
     * 探测 {@code cmd wifi connect-network} —— **不给参数，只触发权限检查**。
     *
     * <p>这不会连接任何网络：无参数时系统在参数解析阶段就返回，
     * 但在那之前**已经过了 UID 权限校验**，所以能拿到 SecurityException。
     */
    public static int probeConnectNetwork(ShizukuChannel shz) {
        if (shz == null) return ShellSubcommand.UNKNOWN;
        String out;
        try {
            ShizukuChannel.Result r = shz.exec("cmd wifi connect-network", 5000);
            if (r == null) return ShellSubcommand.UNKNOWN;
            if (!r.ok() && r.timedOut) return ShellSubcommand.UNKNOWN;
            out = r.output == null ? "" : r.output;
            // stderr 也要看（ShizukuChannel 已把 stderr 并入 output）
            if (r.stderr != null && !r.stderr.isEmpty()) {
                out = out + "\n" + r.stderr;
            }
        } catch (Throwable t) {
            return ShellSubcommand.UNKNOWN;
        }
        // **权限被拒优先判定**：它同时含 "connect-network"，不能落到「可用」
        if (isPermissionDenied(out)) return CONNECT_DENIED;
        return ShellSubcommand.detect(out, "connect-network");
    }

    /**
     * 输出里是否有权限拒绝（SecurityException / does not have access）。
     *
     * <p>这是本轮新增的第四种探测结论：命令**存在**但**没权限**。
     * 旧实现只有 AVAILABLE / MISSING / UNKNOWN，权限拒绝会落到 UNKNOWN，
     * 而「未知就放行」的规则让它一路跑到熔断。
     */
    public static boolean isPermissionDenied(String out) {
        if (out == null || out.isEmpty()) return false;
        String s = out.toLowerCase(java.util.Locale.ROOT);
        return s.contains("securityexception")
                || s.contains("does not have access")
                || s.contains("permission denied");
    }

    /** 是否具备 Wi-Fi 相关运行时权限（13+ 用 NEARBY_WIFI_DEVICES）。 */
    public static boolean hasWifiPermission(Context ctx) {
        if (ctx == null) return false;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                return ctx.checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
            return ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }
}
