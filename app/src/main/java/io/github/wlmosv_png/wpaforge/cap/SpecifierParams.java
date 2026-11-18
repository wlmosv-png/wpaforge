package io.github.wlmosv_png.wpaforge.cap;

import java.util.Locale;

/**
 * 候选口令 → {@link android.net.wifi.WifiNetworkSpecifier} 参数（**纯逻辑**）。
 *
 * <h3>为什么要把「口令能不能用」这件事单独判</h3>
 * 无 Root 在线验证走的是公开 API，而公开 API 对口令有**硬性格式要求**。
 * 一条 8 位以下、或含控制字符的候选，扔给给 builder 要么抛
 * {@code IllegalArgumentException}，要么被系统当成「无效配置」而**不是**
 * 「认证失败」。后者最危险：如果把「系统拒绝了我的参数」记成「这条密码错了」，
 * 就会误写进已试集合，以后有 root 时跳过一条根本没验过的口令。
 *
 * <p>所以这里把判定前移到**发请求之前**：不合格就返回
 * {@link Drop#INVALID_CANDIDATE}，调用方必须按「未执行」处理（NOT_EXECUTED），
 * 绝不能当成 AUTH_FAIL。
 *
 * <p>与 {@code Root} 链的关系：Root 模式下 {@code cmd wifi connect-network}
 * 对 {@code 8~63 位} 的要求一致，但 WPA2 的 {@code 64 位十六进制 PSK}
 * 在两条链里都能用 —— 所以这里只做「长度 + 字符集」判定，不擅自截断或改写。
 *
 * <p>纯逻辑、无 Android 依赖，可离线测。
 */
public final class SpecifierParams {

    /** WPA/WPA2 个人版口令的最短长度（WPA-PSK 规范与 Android 校验一致）。 */
    public static final int MIN_WPA2 = 8;
    /** 口令最长长度。 */
    public static final int MAX_WPA2 = 63;
    /** 64 位十六进制 PSK 的长度。 */
    public static final int HEX_PSK_LEN = 64;

    /** 加密方式（对 builder 说话的口径）。 */
    public static final String SEC_WPA2 = "wpa2";
    public static final String SEC_WPA3 = "wpa3";
    public static final String SEC_OPEN = "open";
    public static final String SEC_OWE = "owe";

    private SpecifierParams() {
    }

    /** 候选被丢弃的原因（机器可读，写日志与统计用）。 */
    public static final class Drop {
        public static final String INVALID_CANDIDATE = "invalid-candidate";
        public static final String UNSUPPORTED_SECURITY = "unsupported-security";

        private Drop() {}
    }

    /**
     * 这次候选能不能交给系统（而不是「密码对不对」）。
     *
     * @return 空串 = 可以用；否则是丢弃原因码 {@link Drop}
     */
    public static String rejectReason(String security, String password) {
        // WEP 先单独挡：WifiNetworkSpecifier **没有** WEP 的口子
        //（Builder 只有 setWpa2Passphrase / setWpa3Passphrase / 无口令三种）。
        // 若不挡，normSec 的兜底会把它当 wpa2 提交 —— 用户白点一次确认框，
        // 拿回一个「没连上」，而他根本不知道这次试的是错加密方式。
        if (security != null && "wep".equals(security.trim().toLowerCase(Locale.ROOT))) {
            return Drop.UNSUPPORTED_SECURITY;
        }
        String sec = normSec(security);
        if (!SEC_WPA2.equals(sec) && !SEC_WPA3.equals(sec)
                && !SEC_OPEN.equals(sec) && !SEC_OWE.equals(sec)) {
            return Drop.UNSUPPORTED_SECURITY;
        }
        // 开放网络：密码参数会被忽略，但仍要求调用方给一个占位（见 WifiNetworkSpecifier 用法）
        if (SEC_OPEN.equals(sec) || SEC_OWE.equals(sec)) return "";
        if (password == null) return Drop.INVALID_CANDIDATE;
        if (password.length() == HEX_PSK_LEN && isHex(password)) return "";
        if (password.length() < MIN_WPA2 || password.length() > MAX_WPA2) {
            return Drop.INVALID_CANDIDATE;
        }
        // 含控制字符的候选在公开 API 里会被判无效；别把它当成认证失败
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (c < 0x20 || c == 0x7F) return Drop.INVALID_CANDIDATE;
        }
        return "";
    }

    public static boolean usable(String security, String password) {
        return rejectReason(security, password).isEmpty();
    }

    public static boolean isHex(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    public static String normSec(String security) {
        if (security == null) return SEC_WPA2;
        String s = security.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return SEC_WPA2;
        if (s.startsWith("wpa3") || "sae".equals(s)) return SEC_WPA3;
        if ("owe".equals(s)) return SEC_OWE;
        if ("open".equals(s) || "none".equals(s)) return SEC_OPEN;
        return SEC_WPA2;
    }

    public static boolean isWpa3(String security) {
        return SEC_WPA3.equals(normSec(security));
    }
}
