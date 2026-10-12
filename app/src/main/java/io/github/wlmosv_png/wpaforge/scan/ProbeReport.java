package io.github.wlmosv_png.wpaforge.scan;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 自查报告生成。
 *
 * <h3>为什么需要</h3>
 * 扫完、破解完，结果只在这台手机里。想发给别人 / 存档 / 贴工单，只能截图。
 * 但这个工具做的判断是有依据的（证据链），**截图丢掉了依据**。
 *
 * 导出成文本，等于把「结论 + 依据」一起带走 —— 接收方能复核，
 * 而不是只能信一张图。
 *
 * <h3>格式</h3>
 * Markdown：既是纯文本（随便哪个编辑器都能开），
 * 又能直接贴到 GitHub / 论坛 / 笔记软件里正常渲染。
 *
 * 纯字符串拼接，不依赖 Android API（除了拿网段那一步），可离线测。
 */
public final class ProbeReport {

    private ProbeReport() {}

    /**
     * 生成报告正文。
     *
     * @param selfIp   本机 IP
     * @param ssid     当前 WiFi 名（可为空）
     * @param hosts    设备列表
     * @param diff     与上次的对比（可为 null）
     * @param claims   证据链（可为空）
     * @param portsTrustworthy 端口结果是否可信
     */
    public static String build(String selfIp, String ssid,
                               List<LanProbe.Host> hosts,
                               ProbeHistory.Diff diff,
                               List<Evidence.Claim> claims,
                               boolean portsTrustworthy) {
        StringBuilder sb = new StringBuilder();
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());

        sb.append("# WPAForge 网络自查报告\n\n");
        sb.append("| 项 | 值 |\n|---|---|\n");
        sb.append("| 生成时间 | ").append(ts).append(" |\n");
        sb.append("| 当前网络 | ").append(ssid == null || ssid.isEmpty() ? "(未知)" : ssid).append(" |\n");
        sb.append("| 本机 IP | ").append(selfIp == null ? "(未知)" : selfIp).append(" |\n");
        sb.append("| 在线设备 | ").append(hosts == null ? 0 : hosts.size()).append(" |\n");
        sb.append("| 端口结果 | ").append(portsTrustworthy ? "可信" : "不可信（已隐藏）").append(" |\n");
        sb.append('\n');

        if (!portsTrustworthy) {
            sb.append("> ⚠ 端口结果不可信：原因通常是本机开了代理/VPN（如 Clash TUN），\n")
              .append("> 或所在网络对任意目标都回 SYN-ACK。关闭代理后重新扫描。\n\n");
        }

        // ---- 设备清单 ----
        sb.append("## 在线设备\n\n");
        if (hosts == null || hosts.isEmpty()) {
            sb.append("(未发现设备)\n\n");
        } else {
            sb.append("| IP | MAC | 厂商 | 角色 |\n|---|---|---|---|\n");
            for (LanProbe.Host h : hosts) {
                sb.append("| ").append(h.ip)
                  .append(" | ").append(h.mac.isEmpty() ? "—" : h.mac)
                  .append(" | ").append(esc(h.displayVendor()))
                  .append(" | ").append(roleOf(h))
                  .append(" |\n");
            }
            sb.append('\n');
        }

        // ---- 与上次对比 ----
        if (diff != null && diff.hasBaseline) {
            sb.append("## 与上次扫描对比\n\n");
            sb.append("- 新出现：").append(diff.added.size()).append(" 台\n");
            sb.append("- 已离开：").append(diff.removed.size()).append(" 台\n");
            sb.append("- 保持不变：").append(diff.kept.size()).append(" 台\n\n");
            if (!diff.added.isEmpty()) {
                sb.append("### ⚠ 新出现的设备\n\n");
                for (ProbeHistory.Dev d : diff.added) {
                    sb.append("- ").append(d.ip);
                    if (!d.mac.isEmpty()) sb.append("  `").append(d.mac).append('`');
                    if (!d.vendor.isEmpty()) sb.append("  ").append(esc(d.vendor));
                    sb.append('\n');
                }
                sb.append('\n');
            }
            if (!diff.removed.isEmpty()) {
                sb.append("### 本次未出现\n\n");
                for (ProbeHistory.Dev d : diff.removed) {
                    sb.append("- ").append(d.ip);
                    if (!d.mac.isEmpty()) sb.append("  `").append(d.mac).append('`');
                    sb.append('\n');
                }
                sb.append('\n');
            }
        }

        // ---- 证据链 ----
        if (claims != null && !claims.isEmpty()) {
            sb.append("## 判断依据\n\n");
            sb.append("> ● 决定性证据　○ 辅助信息\n\n");
            for (Evidence.Claim c : claims) {
                sb.append("### [").append(c.confidenceLabel()).append("] ")
                  .append(esc(c.statement)).append("\n\n");
                for (Evidence.Item it : c.items) {
                    sb.append("- ").append(it.decisive ? "●" : "○").append(' ')
                      .append(esc(it.source)).append(" — ")
                      .append(esc(it.detail)).append('\n');
                }
                sb.append('\n');
            }
        }

        sb.append("---\n\n");
        sb.append("由 WPAForge 生成。仅对自己有授权的网络使用。\n");
        return sb.toString();
    }

    private static String roleOf(LanProbe.Host h) {
        if (h.isGateway && h.isSelf) return "网关/本机";
        if (h.isGateway) return "网关";
        if (h.isSelf) return "本机";
        return "—";
    }

    /** Markdown 表格里 | 会破坏结构，转义掉 */
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ");
    }
}
