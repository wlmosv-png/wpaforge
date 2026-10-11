package io.github.wlmosv_png.wpaforge.audit;

/**
 * `cmd wifi list-networks` 输出解析（纯逻辑，无 Android 依赖）。
 *
 * <h3>为什么单独成类</h3>
 * 这是「旧配置是否清干净」判据的基石。解析错了会返回 null，
 * 净检被静默绕过 → 系统拿已保存的旧密码连上 → 假阳性。
 *
 * 而它又必须是可离线测试的纯函数（放在 CrackEngine 里会因为
 * 类加载触发 Android 依赖，跑不了 JVM 单测）。
 *
 * <h3>踩过的坑</h3>
 * 曾经用 {@code split("\\s+")} 取 {@code parts[1]} 当 SSID。
 * SSID 含空格时（"My Net 5G"）只会取到 "My"，匹配失败、返回 -1，
 * 净检被绕过。现在改为「取开头数字 + 余下部分按前缀匹配」。
 */
public final class ListNetworksParser {

    private ListNetworksParser() {}

    /**
     * 从一行里解析出 netId。
     *
     * 行格式：`&lt;netId&gt;   &lt;SSID&gt;   &lt;security&gt;`
     *
     * @return 匹配则返回 netId；不匹配返回 null
     */
    public static Long netIdOfLine(String line, String ssid) {
        if (line == null || ssid == null || ssid.isEmpty()) return null;
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("Network Id") || t.startsWith("---")) return null;

        // 开头的连续数字 = netId
        int sp = -1;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c)) { sp = i; break; }
            if (!Character.isDigit(c)) return null;   // 开头不是数字，不是数据行
        }
        if (sp <= 0) return null;
        long id;
        try { id = Long.parseLong(t.substring(0, sp)); }
        catch (Throwable e) { return null; }

        String rest = t.substring(sp).trim();
        if (!rest.startsWith(ssid)) return null;
        // SSID 之后必须是空白或行尾，避免 "123" 误配 "1234..."
        if (rest.length() > ssid.length()) {
            if (!Character.isWhitespace(rest.charAt(ssid.length()))) return null;
        }
        return id;
    }

    /**
     * 在整段 `cmd wifi list-networks` 输出里找目标的 netId。
     *
     * @return 找到返回 netId，没找到返回 -1
     */
    public static long findNetId(String listOutput, String ssid) {
        if (listOutput == null || ssid == null || ssid.isEmpty()) return -1;
        for (String line : listOutput.split("\n")) {
            Long id = netIdOfLine(line, ssid);
            if (id != null) return id;
        }
        return -1;
    }
}
