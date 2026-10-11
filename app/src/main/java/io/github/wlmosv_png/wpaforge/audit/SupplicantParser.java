package io.github.wlmosv_png.wpaforge.audit;

/**
 * `cmd wifi status` 里 WifiInfo 行的解析（纯逻辑，无 Android 依赖）。
 *
 * <h3>为什么这是「保险」的判据</h3>
 * 判定「这个密码对不对」的本质是：**4-Way Handshake 有没有完成**。
 * 密码错误时握手必然失败，wpa_supplicant 永远到不了
 * {@code Supplicant state: COMPLETED}。这不是我们猜的状态，
 * 是系统对「密钥协商成功」的宣告。
 *
 * 再加一条 DHCP 成功（拿到有效 IP），就构成「真的连上并且能用」。
 *
 * <h3>为什么必须同行绑定</h3>
 * 实机踩过坑：错误密码时，几秒后系统会**回连别的已保存网络**，
 * 那条连接的 state 同样是 COMPLETED。如果只按「status 里出现过
 * COMPLETED」判断，就会把「连上了别人家」当成「连上了目标」。
 *
 * 所以 SSID / state / IP / netId **必须在同一行 WifiInfo 里**取，
 * 四者同源，才无法被伪造。
 */
public final class SupplicantParser {

    private SupplicantParser() {}

    /** 一帧 WifiInfo 的解析结果 */
    public static final class Info {
        public final String ssid;      // 去引号后的 SSID，取不到为 null
        public final String state;     // 形如 COMPLETED / ASSOCIATING / 4WAY_HANDSHAKE
        public final String ip;        // 形如 192.168.1.5，取不到为 null
        public final long netId;       // -1 表示取不到
        public final boolean raw;      // 这一行是不是 WifiInfo 行

        Info(String ssid, String state, String ip, long netId, boolean raw) {
            this.ssid = ssid;
            this.state = state;
            this.ip = ip;
            this.netId = netId;
            this.raw = raw;
        }

        /** 握手完成？ */
        public boolean handshakeCompleted() {
            return "COMPLETED".equals(state);
        }

        /** 有有效 IP（排除 0.0.0.0 与空）？ */
        public boolean hasValidIp() {
            if (ip == null || ip.isEmpty()) return false;
            if ("0.0.0.0".equals(ip)) return false;
            // 至少要有三段数字
            int dots = 0;
            for (int i = 0; i < ip.length(); i++) if (ip.charAt(i) == '.') dots++;
            return dots >= 2;
        }

        /** 这条是不是「目标 + 握手完成 + 有 IP」 */
        public boolean isTargetConnected(String targetSsid) {
            return raw
                    && targetSsid != null
                    && targetSsid.equals(ssid)
                    && handshakeCompleted()
                    && hasValidIp();
        }

        @Override
        public String toString() {
            return "ssid=" + ssid + " state=" + state + " ip=" + ip + " netId=" + netId;
        }
    }

    /**
     * 解析 `cmd wifi status` 的输出，取 WifiInfo 行。
     *
     * @return 解析结果；没有 WifiInfo 行时返回 {@code raw=false} 的空结果
     */
    public static Info parse(String statusOut) {
        if (statusOut == null) return new Info(null, null, null, -1, false);

        for (String line : statusOut.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("WifiInfo:")) continue;
            return parseWifiInfoLine(t);
        }
        return new Info(null, null, null, -1, false);
    }

    /**
     * 解析单条 WifiInfo 行。
     *
     * 形如：
     * <pre>
     * WifiInfo: SSID: "B209", BSSID: 28:93:..., IP: /192.168.1.3, Security type: 2,
     *           Supplicant state: COMPLETED, ..., Net ID: 613, ...
     * </pre>
     */
    public static Info parseWifiInfoLine(String line) {
        if (line == null) return new Info(null, null, null, -1, false);
        String t = line.trim();
        if (!t.startsWith("WifiInfo:")) return new Info(null, null, null, -1, false);

        String ssid = null;
        String state = null;
        String ip = null;
        long netId = -1;

        // SSID: "xxx"  —— 取第一个 SSID: 后的一对引号
        int k = t.indexOf("SSID:");
        if (k >= 0) {
            int a = t.indexOf('"', k);
            int b = t.indexOf('"', a + 1);
            if (a >= 0 && b > a) ssid = t.substring(a + 1, b);
        }
        if (ssid != null && "<unknown ssid>".equals(ssid)) ssid = null;

        // Supplicant state: XXX
        k = t.indexOf("Supplicant state:");
        if (k >= 0) {
            int s = k + "Supplicant state:".length();
            while (s < t.length() && t.charAt(s) == ' ') s++;
            int e = s;
            while (e < t.length() && (Character.isLetterOrDigit(t.charAt(e)) || t.charAt(e) == '_')) e++;
            if (e > s) state = t.substring(s, e);
        }

        // IP: /192.168.1.3   （也可能是 IP: <none> 或 0.0.0.0）
        k = t.indexOf("IP: ");
        if (k >= 0) {
            int s = k + 4;
            while (s < t.length() && (t.charAt(s) == '/' || t.charAt(s) == ' ')) s++;
            int e = s;
            while (e < t.length() && (Character.isDigit(t.charAt(e)) || t.charAt(e) == '.')) e++;
            if (e > s) ip = t.substring(s, e);
        }

        // Net ID: 613   —— 取最后一个，避免匹配到 BSSID 之类
        k = t.lastIndexOf("Net ID:");
        if (k >= 0) {
            int s = k + "Net ID:".length();
            while (s < t.length() && t.charAt(s) == ' ') s++;
            int e = s;
            if (e < t.length() && t.charAt(e) == '-') e++;
            while (e < t.length() && Character.isDigit(t.charAt(e))) e++;
            if (e > s) {
                try { netId = Long.parseLong(t.substring(s, e)); } catch (Throwable ignored) {}
            }
        }

        return new Info(ssid, state, ip, netId, true);
    }

    /** 便捷：这次 status 是否表明「目标已连上且握手完成且有 IP」 */
    public static boolean targetReady(String statusOut, String targetSsid) {
        return parse(statusOut).isTargetConnected(targetSsid);
    }
}
