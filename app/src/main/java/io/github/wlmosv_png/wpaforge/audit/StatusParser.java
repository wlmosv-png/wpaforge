package io.github.wlmosv_png.wpaforge.audit;

/**
 * `cmd wifi status` 输出解析器（纯逻辑，无 Android 依赖）。
 *
 * <h3>为什么需要它</h3>
 * 通道以 shell(uid 2000) 身份运行时**读不到 logcat**
 * （Android logd 不给 shell uid 返回系统日志，实测 0 字节）。
 * 但它能跑 `cmd wifi status` —— 这是判定连接是否成功的可靠出口。
 *
 * <h3>输出格式（ColorOS / AOSP）</h3>
 * <pre>
 *   Wifi is connected to "B209"
 *   WifiInfo: SSID: "B209", BSSID: 28:93:..., Net ID: 613, score: 60
 * </pre>
 * 未连接时是 `Wifi is not connected`。
 */
public final class StatusParser {

    private StatusParser() {}

    /**
     * 解析结果。
     */
    public static final class Result {
        /** 是否连着指定 SSID */
        public final boolean onTarget;
        /** 当前网络的 netId，-1 表示未知 */
        public final long netId;
        /** 当前连着的 SSID，null 表示未连接 */
        public final String currentSsid;
        /**
         * supplicant 状态（正常是 {@code COMPLETED}）；取不到为 {@code null}。
         *
         * <p><b>与 {@link #currentSsid} 同源于同一行</b> —— 这是刻意的：
         * 该机 {@code cmd wifi status} 会打印多条 {@code WifiInfo}
         * （第二条起 SSID 是 {@code <unknown ssid>}、Net ID 是 -1），
         * 跨行拼出来的“SSID + 状态”组合根本不成立，历史上就是靠它伪造过成功。
         */
        public final String supplicantState;
        /** 同一行里的 IP（形如 {@code /192.168.1.2}，需再去掉前导斜杠）；取不到为 null */
        public final String ip;

        Result(boolean onTarget, long netId, String currentSsid) {
            this(onTarget, netId, currentSsid, null, null);
        }

        Result(boolean onTarget, long netId, String currentSsid,
               String supplicantState, String ip) {
            this.onTarget = onTarget;
            this.netId = netId;
            this.currentSsid = currentSsid;
            this.supplicantState = supplicantState;
            this.ip = ip;
        }
    }

    /** 取出 {@code IP: /x.x.x.x} 里的地址部分；取不到返回 null。 */
    static String ipIn(String line) {
        int k = line.indexOf("IP:");
        if (k < 0) return null;
        int s = k + 3;
        while (s < line.length() && line.charAt(s) == ' ') s++;
        int e = s;
        while (e < line.length()) {
            char c = line.charAt(e);
            if (c == ',' || c == ' ') break;
            e++;
        }
        if (e <= s) return null;
        String ip = line.substring(s, e).trim();
        if (ip.startsWith("/")) ip = ip.substring(1);
        return ip.isEmpty() ? null : ip;
    }

    /** 取出 {@code Supplicant state: COMPLETED} 里的状态码；取不到返回 null。 */
    static String stateIn(String line) {
        int k = line.indexOf("Supplicant state:");
        if (k < 0) return null;
        int s = k + "Supplicant state:".length();
        while (s < line.length() && line.charAt(s) == ' ') s++;
        int e = s;
        while (e < line.length()) {
            char c = line.charAt(e);
            if (c == ',' || c == ' ') break;
            e++;
        }
        if (e <= s) return null;
        String st = line.substring(s, e).trim();
        return st.isEmpty() ? null : st;
    }

    /**
     * 解析 `cmd wifi status` 输出。
     *
     * @param statusOut 命令输出
     * @param ssid      要判断的目标 SSID；可为 null（此时只看 netId/currentSsid）
     */
    public static Result parse(String statusOut, String ssid) {
        if (statusOut == null) return new Result(false, -1, null);

        boolean on = false;
        long netId = -1;
        String current = null;
        String state = null;
        String ip = null;
        // 是否已经把「SSID / 状态 / IP」绑定到**同一条** WifiInfo 行上。
        // 绑定只做一次：该机从第二条 WifiInfo 起是 <unknown ssid> + Net ID: -1，
        // 再往后覆盖就会把无关行拼进来。
        boolean infoBound = false;

        for (String line : statusOut.split("\n")) {
            String t = line.trim();

            // 主判据行：Wifi is connected to "SSID"
            if (t.startsWith("Wifi is connected to")) {
                int a = t.indexOf('"');
                int b = t.lastIndexOf('"');
                if (a >= 0 && b > a) {
                    current = t.substring(a + 1, b);
                    if (ssid != null && ssid.equals(current)) on = true;
                }
            }

            // netId 在 WifiInfo 行里
            if (t.startsWith("WifiInfo:")) {
                int k = t.indexOf("Net ID:");
                if (k >= 0) {
                    int s = k + 7;
                    while (s < t.length() && t.charAt(s) == ' ') s++;
                    int e = s;
                    while (e < t.length() && Character.isDigit(t.charAt(e))) e++;
                    if (e > s) {
                        try { netId = Long.parseLong(t.substring(s, e)); }
                        catch (Throwable ignored) {}
                    }
                }
                // 有些 ROM 只在 WifiInfo 里给 SSID，主判据行缺失
                if (current == null) {
                    int k2 = t.indexOf("SSID:");
                    if (k2 >= 0) {
                        int a2 = t.indexOf('"', k2);
                        int b2 = t.indexOf('"', a2 + 1);
                        if (a2 >= 0 && b2 > a2) {
                            current = t.substring(a2 + 1, b2);
                            if (ssid != null && ssid.equals(current)) on = true;
                        }
                    }
                }
                // 同源绑定：状态与 IP 必须来自**这一条**携带 SSID 的行
                if (!infoBound && t.contains("SSID:")) {
                    infoBound = true;
                    state = stateIn(t);
                    ip = ipIn(t);
                }
            }
        }
        return new Result(on, netId, current, state, ip);
    }

    /** 便捷：只问「是否连着目标」 */
    public static boolean connectedTo(String statusOut, String ssid) {
        return parse(statusOut, ssid).onTarget;
    }

    /** 便捷：只取当前 SSID */
    public static String currentSsid(String statusOut) {
        return parse(statusOut, null).currentSsid;
    }
}
