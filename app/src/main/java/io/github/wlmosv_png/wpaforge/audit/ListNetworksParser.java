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

    /**
     * 「读不到 / 无法判定」的 netId 哨兵。
     *
     * <h3>为什么必须有它（2026-11-21 P0-1 修复）</h3>
     * 修复前「命令失败、超时、返回错误文本」与「命令成功、确实没有目标配置」
     * 都返回 {@code -1}。于是上层把「读不到」当成「已清干净」：
     * <ul>
     *   <li>{@code forgetTargetByShell} 判「删净成功」→ 闸门放行；</li>
     *   <li>{@code preListOk = (text != null)} 判「读到了」→ 基线判「干净」；</li>
     *   <li>此时系统若仍挂着目标的旧配置，命令后
     *       {@code SSID + COMPLETED + IP} 立刻成立 → 任意候选被判 SUCCESS。</li>
     * </ul>
     * 这就是假成功写入历史的入口。现在三者严格分离：
     * <pre>
     *   &gt;= 0  找到目标配置（带 netId）
     *   -1     命令成功，确认**没有**目标配置
     *   -2     读不到 / 命令失败 / 输出不可解析 —— **不得当作「没有」**
     * </pre>
     */
    public static final long NETID_UNREADABLE = -2L;

    private ListNetworksParser() {}

    /**
     * 这段输出是否是一份**可判定的** {@code cmd wifi list-networks} 响应。
     *
     * <h3>分支顺序与编译产物一致（javap -c 核对）</h3>
     * <ol>
     *   <li>{@code null} → false</li>
     *   <li>空白 → true（合法的「没有已保存网络」）</li>
     *   <li>{@code looksLikeError} → false</li>
     *   <li>逐行：表头 / SSID+Security / 数据行 / 键值对行 → true</li>
     *   <li>都不匹配 → false（不可判定，绝不猜）</li>
     * </ol>
     */
    public static boolean looksLikeListOutput(String out) {
        if (out == null) return false;
        String t = out.trim();
        if (t.isEmpty()) return true;
        if (looksLikeError(t)) return false;
        for (String line : t.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("Network Id")) return true;
            if (s.contains("SSID") && s.contains("Security")) return true;
            if (dataRowId(s) >= 0) return true;
            String low = s.toLowerCase(java.util.Locale.ROOT);
            if (low.contains("networkid:") && low.contains("ssid:")) return true;
        }
        return false;
    }

    /** 这段输出是不是一条明确的错误信息（权限 / 服务缺失 / 未知子命令 / 通用 error）。 */
    public static boolean looksLikeError(String out) {
        if (out == null) return false;
        String low = out.trim().toLowerCase(java.util.Locale.ROOT);
        if (low.isEmpty()) return false;
        if (low.contains("permission denied")) return true;
        if (low.contains("unknown command")) return true;
        if (low.contains("can't find service")) return true;
        if (low.contains("cannot find service")) return true;
        if (low.contains("not found")) return true;
        if (low.contains("no such file")) return true;
        if (low.startsWith("error") || low.contains("\nerror")) return true;
        if (low.contains("usage:") && low.contains("wifi")) return true;
        return false;
    }

    /** 数据行开头的 netId；不是数据行返回 -1。 */
    private static long dataRowId(String s) {
        if (s == null || s.isEmpty()) return -1;
        int sp = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { sp = i; break; }
            if (!Character.isDigit(c)) return -1;
        }
        if (sp <= 0) return -1;
        try { return Long.parseLong(s.substring(0, sp)); }
        catch (Throwable e) { return -1; }
    }

    /**
     * 三态版本：把「读不到」与「确实没有」分开。
     *
     * @param exitCode 命令退出码；{@code null} = 拿不到（老通道 / 测试假壳）
     * @return {@code >=0} 找到；{@code -1} 确认没有；{@link #NETID_UNREADABLE} 读不到
     */
    public static long findNetIdOrUnreadable(String listOutput, String ssid, Integer exitCode) {
        if (listOutput == null) return NETID_UNREADABLE;
        // 退出码是**最强**的判据：非 0 就是失败，不管文本长什么样。
        if (exitCode != null && exitCode != 0) return NETID_UNREADABLE;
        if (!looksLikeListOutput(listOutput)) return NETID_UNREADABLE;
        return findNetId(listOutput, ssid);
    }

    /** 无退出码版本（兼容老调用方）。 */
    public static long findNetIdOrUnreadable(String listOutput, String ssid) {
        return findNetIdOrUnreadable(listOutput, ssid, null);
    }

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

        /*
         * 第二种行格式（P0-1 修复时补，2026-11-21）：
         *     networkId: 613    SSID: "TARGET"
         *
         * 有些 ROM / 有些版本把 list-networks 打成长键值对，而不是 AOSP 的
         * 「netId   SSID   security」三列。旧解析器对它是**完全瞎的** ——
         * 找不到目标 → 返回 -1 → 上层当成「配置已删净」→ 假成功。
         * 三态化之后这种行会被判「不可判定」直接拦掉，但那会让这类 ROM
         * 一条都跑不了；所以正确做法是**把它认出来**。
         */
        Long kv = netIdOfKeyValueLine(t, ssid);
        if (kv != null) return kv;

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
     * 解析 {@code networkId: N ... SSID: "X"} 这种键值对行。
     *
     * <p>大小写不敏感；{@code SSID} 允许带引号或不带；netId 必须是纯数字。
     *
     * @return 匹配则返回 netId；不是这种行 / 不匹配返回 null
     */
    private static Long netIdOfKeyValueLine(String t, String ssid) {
        String low = t.toLowerCase(java.util.Locale.ROOT);
        int k = low.indexOf("networkid:");
        if (k < 0) k = low.indexOf("net id:");
        if (k < 0) return null;
        int s0 = t.indexOf(':', k) + 1;
        while (s0 < t.length() && Character.isWhitespace(t.charAt(s0))) s0++;
        int e0 = s0;
        while (e0 < t.length() && Character.isDigit(t.charAt(e0))) e0++;
        if (e0 <= s0) return null;
        long id;
        try { id = Long.parseLong(t.substring(s0, e0)); }
        catch (Throwable ex) { return null; }

        // 同一行里必须有 SSID，且与目标逐字相等（去引号）
        int si = low.indexOf("ssid:");
        if (si < 0) return null;
        int s1 = t.indexOf(':', si) + 1;
        while (s1 < t.length() && Character.isWhitespace(t.charAt(s1))) s1++;
        String rest = t.substring(s1).trim();
        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            if (end < 0) return null;
            rest = rest.substring(1, end);
        } else {
            int cut = rest.indexOf(',');
            if (cut >= 0) rest = rest.substring(0, cut).trim();
        }
        if (!ssid.equals(rest)) return null;
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
