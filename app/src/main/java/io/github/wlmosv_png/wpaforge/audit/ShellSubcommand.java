package io.github.wlmosv_png.wpaforge.audit;

/**
 * 判断 {@code cmd wifi} 到底有没有某个子命令 —— **靠实际调用，不靠 grep 用法表**。
 *
 * <h3>为什么必须改（2026-11-20 真机事故）</h3>
 * 旧实现是去 {@code cmd wifi help} 的输出里搜字符串：
 * <pre>
 *   boolean hasConnect = usage.contains("connect-network");
 * </pre>
 * 用户在一台安卓 13 的机器上实测，自检报出这样一份结果：
 * <pre>
 *   ✓ status 子命令: 可用
 *   ✗ connect-network 子命令: 本机没有
 *   ✓ forget-network 子命令: 可用
 * </pre>
 * 而 {@code forget-network} 恰恰**必须**先有 {@code connect-network} 才有意义
 * （它删的是 connect 建的那条配置）。三个里中间那个"没有"，
 * 只能说明是**文本没搜到**，不是设备不支持。用户的反问一针见血：
 * 「没有的话，他拿什么连接的」。
 *
 * <h3>可靠的判据</h3>
 * AOSP 的 {@code ShellCommand} 对两种情况的输出是**固定句式**，实测确认：
 * <pre>
 *   子命令存在但缺参数 → Invalid args for connect-network: Argument expected after "connect-network"
 *   子命令不存在       → Unknown command: no-such-subcmd-xyz
 * </pre>
 * 所以：带一个空参数调一次，看返回哪一句即可。这比 grep 用法表稳得多 ——
 * 用法表可能被 ROM 裁剪、被终端宽度折行、被 shell 分帧截断，而这两种返回值
 * 是命令分发器自己打的，不会变。
 *
 * <p>纯逻辑、无 Android 依赖，可直接被门禁喂真实输出测。
 */
public final class ShellSubcommand {

    private ShellSubcommand() {
    }

    /** 确认存在。 */
    public static final int AVAILABLE = 1;
    /** 确认不存在。 */
    public static final int MISSING = 0;
    /** 问不到 / 输出无法判定 —— **不许当成不存在**。 */
    public static final int UNKNOWN = -1;

    /**
     * 从「实际调用该子命令」的输出判断它存不存在。
     *
     * @param probeOutput 调用 {@code cmd wifi <name>}（不给参数）拿到的 stdout+stderr
     * @param name        子命令名，如 {@code connect-network}
     */
    public static int detect(String probeOutput, String name) {
        if (probeOutput == null || name == null || name.isEmpty()) return UNKNOWN;
        String out = probeOutput;

        // ① 明确不存在：Unknown command: xxx
        if (hasUnknownCommand(out, name)) return MISSING;

        // ② 明确存在：Invalid args for xxx: ...
        if (hasInvalidArgs(out, name)) return AVAILABLE;

        // ③ **子命令不需要参数时会正常执行**（2026-11-20 补的盲区）。
        //    `cmd wifi status` 就是这样：不带参数调它，返回的是真实状态
        //    （"Wifi is enabled" / "WifiInfo: ..."），既不是 Invalid args
        //    也不是 Unknown command。旧判据在这里只能落到 UNKNOWN ——
        //    真机自检里 status 一直显示「未知（问不到）」，就是这个原因。
        //
        //    **但不能"有输出就算存在"**：那样 `Invalid args for status-x`
        //    这类输出也会命中（门禁抓到的）。所以要求输出里出现
        //    "wifi" 语义词 —— 那才是这个命令真的跑起来的证据。
        if (!out.trim().isEmpty() && !out.contains("commands:")
                && containsWifiSemantics(out)) {
            return AVAILABLE;
        }

        // ④ 兜底：如果输出里出现了「用法表」形态且含该名字，按存在处理
        //    （有些 ROM 对未知子命令也打用法表，这时以用法表为准）。
        if (out.contains("commands:") && containsToken(out, name)) return AVAILABLE;

        // ⑤ 其余一律未知 —— 空输出、超时、权限被拒都落在这里。
        return UNKNOWN;
    }

    /** 用法表里有没有这个子命令（弱证据，仅在无法实际调用时用）。 */
    public static boolean usageMentions(String usage, String name) {
        if (usage == null || usage.trim().isEmpty() || name == null || name.isEmpty()) {
            return false;
        }
        return containsToken(usage, name);
    }

    // ------------------------------------------------------------ 内部

    /** 输出里是否有 {@code Unknown command: <name>}。 */
    static boolean hasUnknownCommand(String out, String name) {
        String key = "Unknown command:";
        int i = out.indexOf(key);
        while (i >= 0) {
            String rest = out.substring(i + key.length()).trim();
            int nl = rest.indexOf('\n');
            if (nl >= 0) rest = rest.substring(0, nl);
            rest = rest.trim();
            if (rest.equals(name) || rest.startsWith(name + " ")
                    || rest.startsWith(name + "\t")) {
                return true;
            }
            i = out.indexOf(key, i + key.length());
        }
        return false;
    }

    /** 输出里是否有 {@code Invalid args for <name>}。 */
    static boolean hasInvalidArgs(String out, String name) {
        String key = "Invalid args for";
        int i = out.indexOf(key);
        while (i >= 0) {
            String rest = out.substring(i + key.length()).trim();
            if (rest.startsWith(name)) {
                int k = name.length();
                // 后面必须是「分隔符」：冒号/空白/行尾。
                // **不能只看 isLetterOrDigit** —— 'Invalid args for status-x' 里
                // status 后面是 '-'，非字母数字，会误判成命中（门禁抓到的真 bug）。
                if (rest.length() == k) return true;
                char c = rest.charAt(k);
                if (c == ':' || Character.isWhitespace(c) || c == '\n' || c == '\r') {
                    return true;
                }
            }
            i = out.indexOf(key, i + key.length());
        }
        return false;
    }

    /**
     * 整词包含（前后不是字母数字或连字符）。
     *
     * <p>不能用 {@code contains} 直接搜：{@code "connect-network"} 是
     * {@code "connect-network-v2"} 的前缀，也可能出现在别的词中间。
     */
    static boolean containsToken(String haystack, String token) {
        int from = 0;
        while (true) {
            int i = haystack.indexOf(token, from);
            if (i < 0) return false;
            boolean leftOk = i == 0 || !isWordChar(haystack.charAt(i - 1));
            int end = i + token.length();
            boolean rightOk = end >= haystack.length() || !isWordChar(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            from = i + 1;
        }
    }

    /**
     * 输出里有没有「wifi 命令真的执行了」的语义特征。
     *
     * <p>用几个稳定词：{@code Wifi is} / {@code Wi-Fi is} / {@code WifiInfo}
     * / {@code connected to} / {@code enabled} / {@code ClientModeManager}。
     * 这些是 AOSP WifiShellCommand 输出里必然出现的东西，
     * 而乱码、报错、别的命令的用法表都不会同时具备。
     */
    static boolean containsWifiSemantics(String out) {
        String lower = out.toLowerCase();
        return lower.contains("wifi is") || lower.contains("wi-fi is")
                || lower.contains("wifiinfo") || lower.contains("connected to")
                || lower.contains("clientmodemanager") || lower.contains("scanning");
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }

    /** 给人看的标签。 */
    public static String label(int state, boolean zh) {
        switch (state) {
            case AVAILABLE: return zh ? "可用" : "available";
            case MISSING:   return zh ? "本机没有" : "missing";
            default:        return zh ? "未知（问不到）" : "unknown (could not probe)";
        }
    }

    /** 给日志用的前缀符号。 */
    public static String mark(int state) {
        if (state == AVAILABLE) return "✓ ";
        if (state == MISSING) return "✗ ";
        return "· ";
    }
}
