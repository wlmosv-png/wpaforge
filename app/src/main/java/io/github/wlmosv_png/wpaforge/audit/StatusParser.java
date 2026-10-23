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

        Result(boolean onTarget, long netId, String currentSsid) {
            this.onTarget = onTarget;
            this.netId = netId;
            this.currentSsid = currentSsid;
        }
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
            }
        }
        return new Result(on, netId, current);
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
