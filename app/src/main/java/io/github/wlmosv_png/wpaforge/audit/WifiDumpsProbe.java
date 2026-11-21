package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code dumpsys wifi} 里的**断开事件**解析（2026-11-22 新增，纯逻辑）。
 *
 * <h3>为什么需要它</h3>
 * 真机实测（PJX110 / ColorOS 15 / Android 15）：
 * <pre>
 *   logcat -s wpa_supplicant        → 0 行   ← 日志配额砍掉，完全不可用
 *   logcat ClientModeImpl/WifiService → 0 行
 *   dumpsys wifi                    → 可用，含 NETWORK_DISCONNECTION_EVENT
 * </pre>
 * 即：本机**唯一**能拿到「断开事件」的通道是 {@code dumpsys wifi}。
 *
 * <h3>它能提供什么</h3>
 * <pre>
 *   rec[0]: time=11-22 01:01:15.896 processed=ConnectingOrConnectedState
 *           org=L2ConnectingState dest=&lt;null&gt; what=NETWORK_DISCONNECTION_EVENT
 *           screen=on  ssid: "whoami" bssid: 66:54:ef:ae:ce:80
 *           reasonCode: 3 locallyGenerated: true
 * </pre>
 * 关键字段：{@code time}（可绑定窗口）、{@code ssid}、{@code bssid}、
 * {@code reasonCode}、{@code locallyGenerated}。
 *
 * <h3>它**不能**提供什么（重要，防止被误用）</h3>
 * <ul>
 *   <li><b>不能</b>证明「密码错误」：{@code reasonCode} 是
 *       {@code WifiAnnotations.DisconnectReasonCode}，本机实测值恒为 3
 *       （WIFI_DISABLED_BY_SYSTEM，我们自己 disable WiFi 造成的），
 *       <b>不是</b> supplicant 的 {@code reason=WRONG_KEY}。</li>
 *   <li><b>不能</b>达到 AT3：事件里没有 supplicant 配置 id，也没有
 *       Android Net ID 与本次配置的对应关系。</li>
 *   <li><b>不能</b>区分「认证失败」与「AP 拒绝关联」——两者 reasonCode 相同。</li>
 * </ul>
 * 因此本类**只用于诊断与 UI 说明**，绝不参与 AUTH_FAIL / SUCCESS 判定。
 * 把它当判据使用会立刻违反「宁可漏判不可误判」的原则。
 *
 * <p>纯逻辑，无 Android 依赖，可离线穷举。
 */
public final class WifiDumpsProbe {

    /** 一条断开事件。 */
    public static final class Disconnect {
        /** 事件时间（原文形如 {@code 11-22 01:01:15.896}）；解析不到为空。 */
        public final String time;
        public final String ssid;
        public final String bssid;
        /** {@code WifiAnnotations.DisconnectReasonCode}；-1 = 未给出。 */
        public final int reasonCode;
        /** true = 本应用/系统主动发起（例如我们 disable WiFi），**不是**认证失败。 */
        public final boolean locallyGenerated;
        /** 触发时所在状态机，例如 {@code ConnectingOrConnectedState}。 */
        public final String processed;

        Disconnect(String time, String ssid, String bssid, int reasonCode,
                   boolean locallyGenerated, String processed) {
            this.time = time == null ? "" : time;
            this.ssid = ssid;
            this.bssid = bssid;
            this.reasonCode = reasonCode;
            this.locallyGenerated = locallyGenerated;
            this.processed = processed == null ? "" : processed;
        }

        @Override public String toString() {
            return "disconnect(" + time + " ssid=" + ssid + " bssid=" + bssid
                    + " code=" + reasonCode + " local=" + locallyGenerated + ")";
        }
    }

    private static final Pattern REC = Pattern.compile(
            "time=([0-9\\-]+ [0-9:.]+).*?what=NETWORK_DISCONNECTION_EVENT"
                    + ".*?ssid: \"([^\"]*)\""
                    + "(?:.*?bssid: ([0-9a-fA-F:]{17}))?"
                    + "(?:.*?reasonCode: (-?\\d+))?"
                    + "(?:.*?locallyGenerated: (true|false))?");
    private static final Pattern PROC = Pattern.compile("processed=(\\w+)");

    private WifiDumpsProbe() {}

    /**
     * 解析 {@code dumpsys wifi} 输出里的全部断开事件。
     *
     * <p>只认同时含 {@code NETWORK_DISCONNECTION_EVENT} 与 {@code ssid: "..."} 的行；
     * 其余行忽略。解析失败的行**不抛异常**，直接跳过 —— 这是诊断用途，
     * 不能因为一行格式变化就让整条链崩掉。
     */
    public static List<Disconnect> disconnects(String dumps) {
        List<Disconnect> out = new ArrayList<>();
        if (dumps == null || dumps.isEmpty()) return out;
        for (String line : dumps.split("\n")) {
            if (line.indexOf("NETWORK_DISCONNECTION_EVENT") < 0) continue;
            if (line.indexOf("ssid: \"") < 0) continue;
            Matcher m = REC.matcher(line);
            if (!m.find()) continue;
            int code = -1;
            try {
                if (m.group(4) != null) code = Integer.parseInt(m.group(4));
            } catch (Throwable ignored) {}
            String proc = "";
            Matcher pm = PROC.matcher(line);
            if (pm.find()) proc = pm.group(1);
            out.add(new Disconnect(m.group(1), m.group(2), m.group(3), code,
                    "true".equalsIgnoreCase(m.group(5)), proc));
        }
        return out;
    }

    /**
     * 统计「针对指定 SSID、且**不是**本应用主动发起」的断开事件数。
     *
     * <p>这是诊断信号：若某候选窗口内出现多条 {@code locallyGenerated=false}
     * 的目标断开，说明系统确实在反复尝试并失败 —— 但**仍不足以**判 AUTH_FAIL，
     * 因为无法区分「密码错」与「AP 拒绝关联 / 信号太差」。
     *
     * <p>用途：写进判词 detail，让用户/排查者知道「系统在反复失败」，
     * 而不是「什么都没发生」。
     */
    public static int nonLocalDisconnectsFor(List<Disconnect> list, String ssid) {
        if (list == null || ssid == null || ssid.isEmpty()) return 0;
        int n = 0;
        for (Disconnect d : list) {
            if (!ssid.equals(d.ssid)) continue;
            if (d.locallyGenerated) continue;
            n++;
        }
        return n;
    }

    /**
     * 诊断文案：把「观察到什么」如实写出来，**不下结论**。
     *
     * @return 无可报告内容时返回空串
     */
    public static String describe(List<Disconnect> list, String ssid) {
        if (list == null || list.isEmpty()) return "";
        int local = 0, nonLocal = 0;
        for (Disconnect d : list) {
            if (!ssid.equals(d.ssid)) continue;
            if (d.locallyGenerated) local++; else nonLocal++;
        }
        if (local == 0 && nonLocal == 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("dumpsys: ").append(ssid)
          .append(" 断开 ").append(nonLocal).append(" 次（系统）")
          .append(" / ").append(local).append(" 次（本应用主动）");
        if (nonLocal > 0) {
            sb.append(" —— 系统在反复失败，但无法区分密码错与 AP 拒绝/信号差");
        }
        return sb.toString();
    }
}
