package io.github.wlmosv_png.wpaforge.cap;

import java.util.ArrayList;
import java.util.List;

/**
 * 能力探测的**纯逻辑**部分：把探测到的原始事实解释成能力状态。
 *
 * <h3>为什么解释逻辑要单独拿出来</h3>
 * 真正碰设备的代码（起一个 {@code su -c id}、读 WifiManager）在 Android 上跑，
 * 桌面 JVM 加载不起来，也就测不到。而这个项目历史上最贵的 bug，
 * 恰恰都是**解释**错了而不是**执行**错了：
 * <ul>
 *   <li>「命令返回空」被当成「失败结论」，而它其实只是「问不到」；</li>
 *   <li>「help 首行含 Wi-Fi」被当成「通道已接入」，于是 Android 9 上也报可用；</li>
 *   <li>「探测不到子命令」被当成「本机没有子命令」，把好机器挡在门外。</li>
 * </ul>
 * 所以判读规则全部收敛到这里，用真实输出喂养的用例钉住。
 *
 * <p>纯逻辑、无 Android 依赖。
 */
public final class CapabilityProbe {

    private CapabilityProbe() {
    }

    /** 一次探测拿到的原始事实（全部来自真实执行，缺省值表示「没问」）。 */
    public static final class Facts {
        /** {@code su -c id} 的输出；null = 没执行过。 */
        public String suId;
        /** {@code cmd wifi} 用法表输出（非 root 身份执行的那份）。 */
        public String cmdWifiUsage;
        /** {@code cmd wifi status} 的输出（可空）。 */
        public String cmdWifiStatus;
        /** 平台：WifiManager 是否可用。 */
        public boolean wifiManager;
        /** 运行时权限是否齐（定位 或 附近设备）。 */
        public boolean scanPermission;
        /** 本应用 UID 读 wpa_supplicant 日志是否**真的拿到了非空输出**（空串不算能读）。 */
        public boolean wpaLogNonEmpty;
        /** 本机能不能走 WifiNetworkSpecifier 在线验证（API 29+ 且有 ConnectivityManager）。 */
        public boolean specifierUsable;
        /**
         * Shizuku 能不能提供 shell 身份（= 能执行 cmd wifi connect-network）。
         *
         * <p>这是无 Root 自动连接的**主路**：有它就能走完整 Root 判定链，
         * 不需要用户逐条确认。没有才退回 Specifier。
         */
        public boolean shizukuUsable;
        /** Shizuku 不可用的具体原因（可用时为空）。 */
        public String shizukuReason = "";
        /** 不能用的原因（能用时为空）。 */
        public String specifierReason = "";

        public static Facts empty() {
            return new Facts();
        }
    }

    /** 无 Root 模式能力集（纯逻辑解释，不碰设备）。 */
    public static List<Capability> explainRootless(Facts f) {
        Facts x = f == null ? Facts.empty() : f;
        List<Capability> out = new ArrayList<>();

        boolean suUsable = x.suId != null && x.suId.contains("uid=0");
        out.add(suUsable
                ? Capability.available(Capability.C_SHELL,
                        "su 可用（root 已授权）—— 但当前选择的是无 Root 模式",
                        "su is available; rootless mode is selected anyway")
                : Capability.unsupported(Capability.C_SHELL,
                        "本应用 UID 无法获得 root（未 root 或未授权）",
                        "this app UID has no root"));

        out.add(Capability.available(Capability.C_WIFI_READ,
                "公开 API 可读当前连接状态", "current connection readable via public APIs"));

        // 关联：能不能做到，取决于公开接口是否给出「不弹框」的通路 —— 目前没有。
        // 逐条关联（2026-11-20 修正）：这里原来写死「不支持」，于是 UI 闸门把
        // 整条无 Root 在线验证挡在门外 —— 明明 SpecifierChannel 已经实现可用。
        // 现在按**真实能力**判：API 29+ 且有 ConnectivityManager 就是降级可用。
        // 用 DEGRADED 而不是 AVAILABLE：能跑，但失败拆不开、成功进不了 DIRECT。
        if (x.shizukuUsable) {
            // Shizuku 给的是 shell 身份 → 与 Root 同一条判定链（AT3 可达）。
            // 这是**唯一**能让无 root 用户自动跑整本字典的路。
            out.add(Capability.available(Capability.C_WIFI_CONNECT,
                    "通过 Shizuku 借 shell 身份执行 cmd wifi —— 自动逐条关联，"
                            + "与 Root 同一条判定链（需先安装并在 Shizuku 里授权）",
                    "uses Shizuku to borrow the shell identity and run cmd wifi - "
                            + "fully automatic per-candidate association on the same "
                            + "judgment chain as root (install Shizuku and grant access)"));
        } else if (x.specifierUsable) {
            out.add(Capability.degraded(Capability.C_WIFI_CONNECT,
                    "可用系统确认框逐次在线验证：首次对某 AP 需确认一次，"
                            + "之后对同一 AP 系统可能不再弹框（AOSP 行为，厂商待真机确认）；"
                            + "失败只代表「系统未能建立连接」，不会记成已证实的错误密码",
                    "per-candidate online verification via the system approval flow: the first "
                            + "request for an AP needs one confirmation, later ones may skip it "
                            + "(AOSP behaviour; vendor unverified). A failure only means "
                            + "'the system could not establish the connection'",
                    "把它当「逐条验证」：一次一个候选、独立收尾；"
                            + "要无人值守跑整本字典仍需 root。",
                    "Treat it as per-candidate verification, one at a time; unattended "
                            + "dictionary runs still need root."));
        } else {
            String why = !x.shizukuReason.isEmpty() ? x.shizukuReason
                    : (x.specifierReason.isEmpty()
                            ? "本机没有可用的在线验证接口" : x.specifierReason);
            out.add(Capability.unsupported(Capability.C_WIFI_CONNECT, why,
                    "no usable online-verification interface on this device",
                    "安装 Shizuku 并用「无线调试」启动服务后授权本应用，即可自动逐条关联；"
                            + "或换到已 root 的设备。",
                    "Install Shizuku, start it via Wireless debugging, and grant this app "
                            + "access to get automatic per-candidate association; "
                            + "alternatively use a rooted device."));
        }

        out.add(Capability.unsupported(Capability.C_WIFI_FORGET,
                "删除系统已保存网络需要系统级权限",
                "removing saved networks needs a system permission"));

        // 日志：只有拿到非空输出才算能读。空输出 = 读不到，不是「没内容」。
        if (x.wpaLogNonEmpty) {
            out.add(Capability.degraded(Capability.C_WPA_LOG,
                    "能读到部分 supplicant 日志，但覆盖范围未经验证",
                    "some supplicant log is readable but coverage is unverified"));
        } else {
            out.add(Capability.unsupported(Capability.C_WPA_LOG,
                    "本应用 UID 的 logcat 读不到 wpa_supplicant tag（系统只对特权身份开放）",
                    "this app UID cannot read the wpa_supplicant tag"));
        }

        if (x.wifiManager && x.scanPermission) {
            out.add(Capability.available(Capability.C_SCAN,
                    "startScan / getScanResults 可用且权限齐备",
                    "startScan / getScanResults available with permissions"));
        } else if (x.wifiManager) {
            out.add(Capability.needsGrant(Capability.C_SCAN,
                    "扫描接口存在，但缺少「附近设备/定位」运行时权限",
                    "scan API exists but the runtime permission is missing",
                    "在系统设置「应用权限」里打开「附近的设备」（Android 12 及以下为「位置」）。",
                    "Grant Nearby devices (Location on Android 12 and below) in system settings."));
        } else {
            out.add(Capability.unsupported(Capability.C_SCAN,
                    "本设备没有可用的 Wi-Fi 扫描服务", "no usable Wi-Fi scan service"));
        }

        out.add(Capability.unsupported(Capability.C_SCAN_PRIV,
                "特权扫描依赖 cmd wifi list-scan-results", "privileged scan needs cmd wifi"));

        out.add(Capability.unsupported(Capability.C_WPS_PIN,
                "WPS PIN 需要向 wpa_supplicant 控制口写命令，非 root 无此通路",
                "WPS PIN needs writes to the wpa_supplicant control socket",
                "可用「WPS 能力识别」看 AP 声明了什么（弱证据，不计入命中）。",
                "Use WPS capability detection for what the AP advertises (weak evidence)."));

        out.add(Capability.degraded(Capability.C_WPS_CAP,
                "只能解析 ScanResult 里的 WPS 信息元素，没有协议层证据",
                "only the WPS IE from ScanResult; no protocol-layer evidence",
                "结论只会是「能力未知/数据不完整」，永不作为命中依据。",
                "Verdicts stay unknown/incomplete and never count as a hit."));

        out.add(Capability.unsupported(Capability.C_SAVED_CFG,
                "WifiConfigStore.xml 位于 /data/misc，非 root 不可读",
                "/data/misc WifiConfigStore.xml is unreadable without root"));

        out.add(Capability.unsupported(Capability.C_KEEPALIVE,
                "appops / 电池白名单写入需要 su", "appops / allowlist writes need su",
                "用系统设置里的电池优化豁免手动关闭限制。",
                "Grant battery-optimisation exemption from system settings."));

        out.add(Capability.degraded(Capability.C_LAN_PROBE,
                "ping 与 ARP 表读取受 SELinux 限制，只有 TCP 探测可靠",
                "ping and /proc/net/arp are SELinux-restricted; only TCP probing is reliable",
                "以 TCP 端口探测为准。", "Rely on TCP probing."));

        // 额外事实：cmd wifi 的实际可行性 —— 作为证据放进 shell 能力的替代说明。
        if (x.cmdWifiUsage != null) {
            boolean empty = x.cmdWifiUsage.trim().isEmpty();
            out.add(Capability.unknown("cmd_wifi_probe",
                    empty ? "非 root 身份执行 cmd wifi 返回空输出（= 被 UID 拦住）"
                          : "非 root 身份能执行 cmd wifi（本机例外，但接口仍不保证可用）",
                    empty ? "cmd wifi returns empty output for this UID"
                          : "cmd wifi runs for this UID (works, but still not a supported API)"));
        }
        return out;
    }

    /**
     * 解释 root 侧探测结果：su 能不能用、{@code cmd wifi} 有没有破解必需的子命令。
     *
     * <p>关键规则（历史事故换来的）：
     * <ul>
     *   <li>用法表**拿不到** ≠ 子命令不存在 → 一律按「未知」放过，
     *       免得把能用但 help 被截断的机器挡在门外；</li>
     *   <li>用法表**拿到了**但没有 {@code connect-network} → 才是真的不支持；</li>
     *   <li>{@code status} 是否可用单独判定，因为它决定「连上了没有」能不能读。</li>
     * </ul>
     */
    public static List<Capability> explainRoot(String suId, String usage,
                                               boolean wifiManager, boolean scanPermission) {
        List<Capability> out = new ArrayList<>();
        boolean suOk = suId != null && suId.contains("uid=0");

        out.add(suOk
                ? Capability.available(Capability.C_SHELL, "su 可用（uid=0）", "su works (uid=0)")
                : Capability.needsGrant(Capability.C_SHELL,
                        "su 不可用：设备未 root，或 root 管理器没有给本应用授权",
                        "su unavailable: not rooted, or root not granted",
                        "在 root 管理器里允许本应用；或切到「无 Root 模式」使用离线功能。",
                        "Allow this app in your root manager, or switch to rootless mode."));

        if (!suOk) {
            // 没有 su 就无从判断子命令，其余全部未知 —— 不许猜。
            out.add(Capability.needsGrant(Capability.C_WIFI_CONNECT,
                    "需要 root 授权后才能探测", "needs root before this can be probed"));
            out.add(Capability.needsGrant(Capability.C_WIFI_FORGET,
                    "需要 root 授权后才能探测", "needs root before this can be probed"));
            out.add(Capability.needsGrant(Capability.C_WIFI_READ,
                    "需要 root 授权后才能探测", "needs root before this can be probed"));
            out.add(Capability.needsGrant(Capability.C_WPA_LOG,
                    "需要 root 授权后才能探测", "needs root before this can be probed"));
            out.add(Capability.needsGrant(Capability.C_WPS_PIN,
                    "需要 root 授权后才能探测", "needs root before this can be probed"));
            out.add(Capability.needsGrant(Capability.C_SAVED_CFG,
                    "需要 root 授权后才能探测", "needs root before this can be probed"));
            out.add(Capability.needsGrant(Capability.C_KEEPALIVE,
                    "需要 root 授权后才能探测", "needs root before this can be probed"));
            out.add(Capability.needsGrant(Capability.C_SCAN_PRIV,
                    "需要 root 授权后才能探测", "needs root before this can be probed"));
        } else {
            boolean usageKnown = usage != null && !usage.trim().isEmpty();
            boolean hasConnect = usageKnown && usage.contains("connect-network");
            boolean hasForget = usageKnown && usage.contains("forget-network");
            boolean hasStatus = usageKnown && usage.contains("status");

            String unknown = "用法表拿不到（可能是 ROM 截断了 help 输出）——按未知处理，不判死";
            String unknownEn = "usage table unavailable (help may be truncated) — treated as unknown";

            // 关联
            if (!usageKnown) {
                out.add(Capability.unknown(Capability.C_WIFI_CONNECT, unknown, unknownEn));
            } else if (hasConnect) {
                out.add(Capability.available(Capability.C_WIFI_CONNECT,
                        "cmd wifi connect-network 可用", "cmd wifi connect-network is available"));
            } else {
                out.add(Capability.unsupported(Capability.C_WIFI_CONNECT,
                        "本机 cmd wifi 没有 connect-network 子命令（Android 9 及更早只有 4 条调试命令）",
                        "this device's cmd wifi has no connect-network subcommand",
                        "换 Android 11+ 的设备；或改用离线分析。",
                        "Use an Android 11+ device, or use offline analysis."));
            }

            // 净检
            if (!usageKnown) {
                out.add(Capability.unknown(Capability.C_WIFI_FORGET, unknown, unknownEn));
            } else if (hasForget) {
                out.add(Capability.available(Capability.C_WIFI_FORGET,
                        "cmd wifi forget-network 可用", "cmd wifi forget-network is available"));
            } else {
                out.add(Capability.unsupported(Capability.C_WIFI_FORGET,
                        "本机 cmd wifi 没有 forget-network —— 删不掉旧配置就无法净检，"
                        + "任何「破解成功」都不可信",
                        "no forget-network: without it stale configs cannot be cleared, "
                        + "so no success can be trusted",
                        "换 Android 11+ 的设备。", "Use an Android 11+ device."));
            }

            // 状态读取
            if (!usageKnown) {
                out.add(Capability.unknown(Capability.C_WIFI_READ, unknown, unknownEn));
            } else if (hasStatus) {
                out.add(Capability.available(Capability.C_WIFI_READ,
                        "cmd wifi status 可用", "cmd wifi status is available"));
            } else {
                out.add(Capability.degraded(Capability.C_WIFI_READ,
                        "cmd wifi status 不可用，退回 App 侧 WifiInfo（可能返回 <unknown ssid> 或过期的 netId）",
                        "cmd wifi status missing; falling back to WifiInfo (may be stale/unknown)",
                        "结论会降级为 UNKNOWN，不会判「密码错」。",
                        "Verdicts degrade to UNKNOWN; they never become wrong-password."));
            }

            out.add(Capability.available(Capability.C_WPA_LOG,
                    "root 可读 logcat 的 wpa_supplicant tag", "root can read the supplicant log"));
            out.add(Capability.available(Capability.C_WPS_PIN,
                    "root + app_process 可驱动 supplicant 控制口", "root can drive the supplicant socket"));
            out.add(Capability.available(Capability.C_SAVED_CFG,
                    "root 可读 WifiConfigStore.xml", "root can read WifiConfigStore.xml"));
            out.add(Capability.available(Capability.C_KEEPALIVE,
                    "root 可写 appops 与电池白名单", "root can write appops and the allowlist"));
            out.add(Capability.available(Capability.C_SCAN_PRIV,
                    "root 可用 cmd wifi list-scan-results 兜底", "privileged scan fallback available"));
        }

        // 扫描：普通 API 始终是主通道
        if (wifiManager && scanPermission) {
            out.add(Capability.available(Capability.C_SCAN,
                    "startScan / getScanResults 可用", "startScan / getScanResults available"));
        } else if (wifiManager) {
            out.add(Capability.needsGrant(Capability.C_SCAN,
                    "缺少「附近设备/定位」运行时权限",
                    "the nearby-devices/location runtime permission is missing",
                    "在系统设置「应用权限」里打开「附近的设备」（Android 12 及以下为「位置」）。",
                    "Grant Nearby devices (Location on Android 12 and below) in system settings."));
        } else {
            out.add(Capability.unsupported(Capability.C_SCAN,
                    "本设备没有可用的 Wi-Fi 扫描服务", "no usable Wi-Fi scan service"));
        }

        out.add(Capability.available(Capability.C_WPS_CAP,
                "可走 root BSS 查询读 WPS 能力（另有本地 IE 通道可用）",
                "root BSS query available for WPS capability (plus local IE)"));
        out.add(Capability.degraded(Capability.C_LAN_PROBE,
                "ping / ARP 可读，但受目标网络策略影响",
                "ping/ARP readable but subject to network policy",
                "以 TCP 探测为准。", "Rely on TCP probing."));
        return out;
    }
}
