package io.github.wlmosv_png.wpaforge.cap;

import java.util.ArrayList;
import java.util.List;

/**
 * 无 Root 模式通道：只用公开 API，**明确做不到的事就说做不到**。
 *
 * <h3>这个类为什么几乎是空的</h3>
 * 无 Root 模式下「逐条关联」是**做不到**的，而且不是「难做」，是**物理上做不到**：
 * <ul>
 *   <li>本机实测（OnePlus PJX110 / Android 15，2026-11-18）：同一台手机
 *       用 {@code uid=2000(shell)} 执行 {@code cmd wifi status/list-networks}
 *       全部正常；用 {@code uid=10381(应用 UID)}（装在同一个 system 分区的
 *       APK 也一样）执行，{@code cmd wifi status} <b>返回空输出</b>，
 *       {@code dumpsys wifi} 直接 {@code Permission Denial}，
 *       {@code logcat -s wpa_supplicant} 也读不到 —— binder 服务按调用者 UID
 *       拦，不是按 SELinux 域拦，装到哪个分区都一样。</li>
 *   <li>{@code WifiNetworkSpecifier} 是唯一能让普通应用指定口令的公开接口，
 *       但它**每次关联都会弹系统确认框**，用户不点就没结果。逐条字典遍历
 *       等于要用户点几千次 —— 这不是「慢」，是「不可用」。</li>
 * </ul>
 *
 * <p>所以本类的职责不是「想办法绕过」，而是：
 * <ol>
 *   <li><b>把能力状态说清楚</b>（{@link #state()}）；</li>
 *   <li><b>把尝试变成「未尝试」而不是「失败」</b> —— 这是证据契约：
 *       没有观察到的失败，绝不能写成 AUTH_FAIL，更不能写进 TriedSet
 *       （那会让以后有 root 时跳过真正没试过的候选）。</li>
 * </ol>
 *
 * <h3>为什么不提供一个「伪通道」把命令吞掉</h3>
 * 因为那样判定链会拿到空输出，而空输出的语义是「命令跑了但没说话」，
 * 会被判成 notConnected。用户看到的会是「跑完了，密码都不对」——
 * 这是**伪造结论**，比直接说「本机做不到」坏得多。
 */
public final class RootlessChannel implements ConnectChannel {

    private volatile boolean opened;

    @Override
    public boolean open() {
        opened = true;
        return true;
    }

    @Override
    public boolean isOpen() {
        return opened;
    }

    @Override
    public void close() {
        opened = false;
    }

    @Override
    public String name() {
        return "Rootless";
    }

    // ------------------------------------------------------------ 必需能力

    @Override
    public boolean canAssociate() {
        return false;
    }

    @Override
    public boolean canForget() {
        return false;
    }

    /** **永远返回 false，且一条命令都不发。** 见类注释。 */
    @Override
    public boolean associate(String ssid, String security, String password,
                             String bssid, boolean macRotate) {
        return false;
    }

    @Override
    public boolean forgetTarget(String ssid) {
        return false;
    }

    // ------------------------------------------------------------ 可选能力

    /** 没有特权通道，读不到 —— 返回 null，而不是空串。 */
    @Override
    public String readState() {
        return null;
    }

    @Override
    public String listSavedNetworks() {
        return null;
    }

    @Override
    public String readSupplicantLog() {
        return null;
    }

    @Override
    public boolean canPrivilegedScan() {
        return false;
    }

    // ------------------------------------------------------------ 能力自述

    public static final String WHY_ZH =
            "非 root 应用看不到 Wi-Fi 服务的特权接口：本机实测，同一台手机上 shell 身份"
            + "能跑 cmd wifi，本应用的 UID 调用返回空输出、dumpsys wifi 被拒绝。"
            + "装到哪个分区都一样，这是 binder 按调用者 UID 拦；"
            + "Android 15 也没有给第三方应用开放 WifiNetworkSpecifier 之外的静默关联接口。";
    public static final String WHY_EN =
            "A non-root app cannot reach Wi-Fi's privileged interface: measured on this "
            + "device, the shell identity can run cmd wifi while this app's UID gets empty "
            + "output and dumpsys wifi is denied. It is gated by caller UID in the binder "
            + "service, so the install partition does not matter; Android 15 exposes no "
            + "silent-association API beyond WifiNetworkSpecifier.";

    public static final String ALT_ZH =
            "用「离线分析」把手里的握手/PMKID 文件跑字典；或者用本机字典功能准备候选，"
            + "换到已 root 的设备上执行。";
    public static final String ALT_EN =
            "Use Offline analysis on a captured handshake/PMKID, or prepare candidates here "
            + "and run them on a rooted device.";

    @Override
    public Capability.Set0 state() {
        List<Capability> list = new ArrayList<>();

        list.add(Capability.unsupported(Capability.C_SHELL,
                "无 Root 模式下不存在命令通道（应用 UID 调不了 cmd wifi）", WHY_EN,
                ALT_ZH, ALT_EN));
        list.add(Capability.available(Capability.C_WIFI_READ,
                "可用公开 API 读当前连接（WifiInfo / ConnectivityManager）",
                "current connection readable via public APIs"));
        list.add(Capability.unsupported(Capability.C_WIFI_CONNECT,
                "公开 API 无法静默关联，WifiNetworkSpecifier 每条都要用户点确认",
                "no silent association via public APIs; the specifier prompts every time",
                ALT_ZH, ALT_EN));
        list.add(Capability.unsupported(Capability.C_WIFI_FORGET,
                "删除系统已保存网络需要系统权限", "removing saved networks requires a system permission",
                ALT_ZH, ALT_EN));
        list.add(Capability.unsupported(Capability.C_WPA_LOG,
                "读不到 wpa_supplicant 日志（本应用 UID 的 logcat 不含该 tag）",
                "the wpa_supplicant log tag is not visible to this app's UID",
                ALT_ZH, ALT_EN));
        list.add(Capability.available(Capability.C_SCAN,
                "可用 startScan / getScanResults（需定位或附近设备权限）",
                "startScan / getScanResults available with the runtime permissions"));
        list.add(Capability.unsupported(Capability.C_SCAN_PRIV,
                "特权扫描兜底依赖 cmd wifi list-scan-results", "privileged scan needs cmd wifi",
                ALT_ZH, ALT_EN));
        list.add(Capability.unsupported(Capability.C_WPS_PIN,
                "WPS PIN 需要向 supplicant 控制口写命令，非 root 无此通路",
                "WPS PIN needs to write to the supplicant control socket",
                "可用「WPS 能力识别」判断 AP 声明了什么（弱证据，不能当验证结果）。",
                "Use WPS capability detection to read what the AP advertises (weak evidence)."));
        list.add(Capability.degraded(Capability.C_WPS_CAP,
                "只能读 ScanResult 里的 WPS 信息元素，无协议层证据",
                "only the WPS information element in ScanResult; no protocol-layer evidence",
                "结论标记为「数据不完整/能力未知」，不参与命中判定。",
                "Verdicts are marked incomplete/unknown and never count as a hit."));
        list.add(Capability.unsupported(Capability.C_SAVED_CFG,
                "WifiConfigStore.xml 在 /data/misc，非 root 不可读",
                "/data/misc WifiConfigStore.xml is unreadable without root",
                "改用系统的「已保存网络」页查看；本应用只显示当前连接。",
                "Check the system's saved-networks screen; this app shows the current one."));
        list.add(Capability.unsupported(Capability.C_KEEPALIVE,
                "appops / 电池白名单写入需要 su", "appops / allowlist writes need su",
                "改用系统的电池优化豁免页面手动关闭限制。",
                "Grant battery-optimisation exemption from system settings."));
        list.add(Capability.degraded(Capability.C_LAN_PROBE,
                "ping / ARP 表读取受 SELinux 限制，只有 TCP 探测可靠",
                "ping and /proc/net/arp are SELinux-restricted; only TCP probing is reliable",
                "以 TCP 端口探测为准，ping/ARP 结果只作辅助。",
                "Rely on TCP probing; treat ping/ARP as auxiliary."));

        return new Capability.Set0("rootless", System.currentTimeMillis(), list);
    }
}
