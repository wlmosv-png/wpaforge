package io.github.wlmosv_png.wpaforge.wps;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「已知可被 Pixie Dust 秒破」的设备名单匹配（纯逻辑，可离线测）。
 *
 * <h3>这份名单是什么</h3>
 * 一批老固件的 WPS 随机数实现有缺陷（E-S1/E-S2 用了固定值或劣质 PRNG），
 * 抓到一次注册交换就能**离线**把 PIN 算出来，不用在线穷举、也绕开了锁定。
 * 社区把已知可打的型号整理成名单，Kali 的 OneShot / pixiewps 扫描时就用它
 * 给目标打标记（文件名 {@code vulnwsc.txt}）。
 *
 * <h3>为什么标记有意义</h3>
 * WPS 页现在能判「PIN 可打 / 仅按钮 / 已锁」，那是**协议层**的门槛；
 * 名单回答的是另一个问题：**这台是不是"一次交换就能算出 PIN"的型号**。
 * 两个信息叠起来，用户才知道哪台值得动手 —— 前者说能不能打，后者说打起来多快。
 *
 * <h3>匹配口径</h3>
 * 拿 AP 自报的 WSC 设备名（{@code Device name} / Model，见 {@link WpsController.Target#wpsDevice}）
 * 与名单条目做**归一化后的双向包含**（忽略大小写、空格、下划线、连字符、点），
 * 并要求较短一方长度 ≥ {@link #MIN_MATCH_LEN}，避免"Router"这种词乱命中。
 * 名单里少数条目形如 {@code Tenda 123456}，末尾那串数字是**已知默认 PIN**，
 * 不是版本号 —— 解析时单独归到 {@link Entry#hintPin}。
 */
public final class VulnWsc {

    /** 短于这个长度不许参与匹配（否则 "net"、"wifi" 这种词会到处命中） */
    public static final int MIN_MATCH_LEN = 5;

    /** 名单里这些是"泛称"，不是型号：它们只说明设备自报了一个没有型号信息的大路货名字 */
    private static final String[] GENERIC = {
            "wirelessrouter", "wirelessaccesspoint", "wirelesswpsrouter", "wpsrouter",
            "wirelessgigabitap", "adslrouter", "adsl", "gponont", "na", "router",
            "accesspoint", "wireless", "wlan", "ap",
    };

    /** 名单里的一行 */
    public static final class Entry {
        /** 原始整行 */
        public final String raw;
        /** 去掉版本/默认 PIN 后的型号部分 */
        public final String base;
        /** 版本号（形如 4.0 / 1.0.0 / 13），没有则空串 */
        public final String version;
        /** 该条目里写明的已知默认 PIN（形如 123456），没有则空串 */
        public final String hintPin;
        /** 泛称条目（匹配上没有意义） */
        public final boolean generic;

        Entry(String raw, String base, String version, String hintPin, boolean generic) {
            this.raw = raw;
            this.base = base;
            this.version = version;
            this.hintPin = hintPin;
            this.generic = generic;
        }
    }

    /** 命中结果 */
    public static final class Match {
        public final Entry entry;
        /** 设备名里的版本与名单版本是否一致；名单没写版本时为 true */
        public final boolean versionMatched;

        Match(Entry entry, boolean versionMatched) {
            this.entry = entry;
            this.versionMatched = versionMatched;
        }
    }

    private VulnWsc() {}

    /** 解析名单文本（每行一条，# 开头为注释） */
    public static List<Entry> parse(List<String> lines) {
        List<Entry> out = new ArrayList<>();
        if (lines == null) return out;
        for (String line : lines) {
            if (line == null) continue;
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            out.add(parseOne(s));
        }
        return out;
    }

    private static Entry parseOne(String s) {
        String base = s, version = "", hint = "";
        int sp = s.lastIndexOf(' ');
        if (sp > 0) {
            String tail = s.substring(sp + 1);
            if (looksLikeVersion(tail)) {
                version = tail;
                base = s.substring(0, sp).trim();
            } else if (looksLikePin(tail)) {
                hint = tail;
                base = s.substring(0, sp).trim();
            }
        }
        String norm = normalize(base);
        boolean generic = false;
        for (String g : GENERIC) {
            if (norm.equals(g)) {
                generic = true;
                break;
            }
        }
        return new Entry(s, base, version, hint, generic);
    }

    /** 版本：带点（4.0 / 1.0.0）或 1~2 位纯数字 */
    private static boolean looksLikeVersion(String t) {
        if (t.isEmpty()) return false;
        if (t.matches("^\\d+(\\.\\d+)+$")) return true;
        return t.matches("^\\d{1,2}$") && t.length() <= 2;
    }

    /** 默认 PIN：4~8 位纯数字（比版本长，且不带点） */
    private static boolean looksLikePin(String t) {
        return t.matches("^\\d{4,8}$");
    }

    /** 归一化：小写、去掉分隔与括号，便于双向包含 */
    static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) {
                sb.append(c);
            } else if (c > 127) {
                // 非 ASCII（中文设备名）也保留：国产光猫常报中文
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 设备名（已归一化）是否只是泛称 —— 泛称不含型号信息，不能用来判「已知可打」。 */
    static boolean isGenericName(String normalizedDevice) {
        if (normalizedDevice == null) return false;
        for (String g : GENERIC) {
            if (normalizedDevice.equals(g)) return true;
        }
        return false;
    }

    /**
     * 设备名 → 命中条目。
     *
     * @param deviceName AP 自报的 WSC 设备名 / 型号
     * @return 命中的条目；没命中返回 null
     */
    public static Match match(String deviceName, List<Entry> entries) {
        String dev = normalize(deviceName);
        if (dev.length() < MIN_MATCH_LEN || entries == null) return null;
        // 设备名本身就是泛称 → 不含任何型号信息，匹配结果必然是噪声。
        // 实测（真实名单 164 条 × 裸泛称设备名）：「Wireless WPS Router」「Wireless Router」
        // 「Wireless Access Point」「ADSL Router」「Wireless」「WPS Router」全被误标成
        // 已知可打 —— 名单里的泛称词总是嵌在长条目中（如 "ADSL Router EV-2006-07-27"），
        // 「条目包含设备名」这条反向包含会把裸泛称整个吃进去。
        if (isGenericName(dev)) return null;

        Entry best = null;
        for (Entry e : entries) {
            if (e.generic) continue;
            String base = normalize(e.base);
            if (base.length() < MIN_MATCH_LEN) continue;
            boolean hit = dev.contains(base) || base.contains(dev);
            if (!hit) continue;
            // 命中多个时取最长的那个（越具体越可信）
            if (best == null || normalize(best.base).length() < base.length()) best = e;
        }
        if (best == null) return null;
        boolean vOk = best.version.isEmpty() || dev.contains(normalize(best.version));
        return new Match(best, vOk);
    }

    /** 命中 → 界面用的一句话 */
    public static String label(Match m, boolean zh) {
        if (m == null) return "";
        if (!m.entry.hintPin.isEmpty()) {
            return zh ? "已知可打 · 默认 PIN " + m.entry.hintPin
                      : "known vulnerable · default PIN " + m.entry.hintPin;
        }
        if (!m.versionMatched) {
            return zh ? "型号在名单里（版本不同）" : "model listed (version differs)";
        }
        return zh ? "已知可打（Pixie）" : "known Pixie-vulnerable";
    }
}
