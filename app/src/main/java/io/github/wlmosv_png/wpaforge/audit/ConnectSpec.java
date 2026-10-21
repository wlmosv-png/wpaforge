package io.github.wlmosv_png.wpaforge.audit;

import java.util.Locale;

/**
 * 连接命令的拼装与转义。
 *
 * <h3>为什么单独抽出来</h3>
 * 同一份 {@code shellQuote} 原来在 {@code CrackEngine}、{@code RootProber}、
 * {@code MainActivity} 里各有一份实现。命令拼错不会报错，只会让 netId 建不出来、
 * 判定永远失败 —— 属于最难查的那类故障（本项目历史上就吃过「转义出错，
 * 命令被吞掉」的亏）。集中一处 + 单测钉死，比散着三份安全。
 *
 * <h3>两个可选参数</h3>
 * {@code cmd wifi connect-network} 本身支持：
 * <ul>
 *   <li>{@code -b <bssid>}：指定 BSSID。同名 SSID 有多个 AP 时，用它只跟选中的
 *       那一个打交道，而不是让系统自己挑（系统可能挑最弱的那个）。</li>
 *   <li>{@code -r auto|none|persistent|non_persistent}：本网络的 MAC 随机化方案。
 *       {@code non_persistent} = 每次连接都用新的随机 MAC，用于缓解目标 AP
 *       侧的限流/黑名单。</li>
 * </ul>
 * 两者都是**可选**的：不加就是原来的行为。
 */
public final class ConnectSpec {

    private ConnectSpec() {}

    /**
     * 单引号包裹，内部的单引号用 {@code '\''} 逃逸。
     *
     * <p>null 视为空串 —— 返回 {@code ''}，而不是抛异常或产生裸 null。
     */
    public static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * MAC 形状检查（{@code AA:BB:CC:DD:EE:FF}）。
     *
     * <p>不检查就直接拼进命令的话，一个脏值（空串、带空格的展示串）会让整条
     * 命令语法错误 —— 而 {@code cmd wifi} 对错误参数的反应是**静默失败**。
     * 宁可退回「不指定 BSSID」，也不能拼出跑不了的命令。
     */
    public static boolean looksLikeMac(String s) {
        return s != null && s.matches("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$");
    }

    /**
     * 拼一条 connect-network 命令。
     *
     * @param ssid      目标 SSID
     * @param security  open / owe / wpa2 / wpa3 / wep；空或 null 退回 wpa2
     * @param password  候选密码
     * @param bssid     指定 AP 的 MAC，可为 null；形状不合法时忽略
     * @param macRotate true = 每次尝试换随机 MAC
     */
    public static String buildConnect(String ssid, String security, String password,
                                      String bssid, boolean macRotate) {
        String sec = (security == null || security.isEmpty()) ? "wpa2" : security;
        StringBuilder sb = new StringBuilder("cmd wifi connect-network ")
                .append(shellQuote(ssid)).append(' ')
                .append(sec).append(' ')
                .append(shellQuote(password));
        if (looksLikeMac(bssid)) {
            sb.append(" -b ").append(bssid.toUpperCase(Locale.ROOT));
        }
        if (macRotate) {
            sb.append(" -r non_persistent");
        }
        return sb.toString();
    }
}
