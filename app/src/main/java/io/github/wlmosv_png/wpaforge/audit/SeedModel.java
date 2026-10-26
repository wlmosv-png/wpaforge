package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;

import io.github.wlmosv_png.wpaforge.L;

/**
 * 用户提供的线索（已知信息）。
 *
 * 为什么需要它：审计自家网络时，用户知道的信息远多于 App 能从 SSID 猜到的。
 * 手机号、生日、门牌、姓名拼音这些东西，任何公开词表里都不会有 ——
 * 但它们在真实家庭网络里的命中率远高于「SSID 变形」。
 *
 * 设计约束：
 *  1. 全部字段可留空。为空时派生结果与「只有 SSID」的旧版本完全一致。
 *  2. 不依赖 Android，且**不使用 org.json** —— 本类的序列化必须能在桌面单测里跑
 *     （android.jar 里的 org.json 是 stub，架构文档 D5 已记录这条约束）。
 *     所以自己写了一个最小转义格式，见 encode()/decode()。
 *  3. 只存本机。任何展示或日志都走 masked()，绝不写出原值。
 */
public final class SeedModel {

    /** 手机号，最多 4 个（户主 / 家人 / 宽带装机号） */
    public final List<String> phones = new ArrayList<>();
    /** 固话或宽带账号 */
    public String landline = "";
    /** 生日，原样存：19900101 / 900101 / 0101 / 1990-01-01 都接受 */
    public String birthday = "";
    /** 姓名或昵称拼音，如 zhangwei */
    public String namePinyin = "";
    /** 门牌 / 楼层 / 房间号，如 802 */
    public String houseNo = "";
    /** 自由词，最多 8 条 */
    public final List<String> customs = new ArrayList<>();

    public static final int MAX_PHONES = 4;
    public static final int MAX_CUSTOMS = 8;

    /** 单字段最长接受长度，防手滑粘进一整段文本 */
    private static final int MAX_FIELD = 32;

    public SeedModel() {}

    // ---------------------------------------------------------------- 语义

    /** 一条线索都没填 */
    public boolean isEmpty() {
        return phones.isEmpty()
                && t(landline).isEmpty()
                && t(birthday).isEmpty()
                && t(namePinyin).isEmpty()
                && t(houseNo).isEmpty()
                && customs.isEmpty();
    }

    /** 非空字段数，用于 UI 与日志说「已启用 N 项」 */
    public int count() {
        int n = phones.isEmpty() ? 0 : 1;
        if (!t(landline).isEmpty()) n++;
        if (!t(birthday).isEmpty()) n++;
        if (!t(namePinyin).isEmpty()) n++;
        if (!t(houseNo).isEmpty()) n++;
        if (!customs.isEmpty()) n++;
        return n;
    }

    /** 原值清单：给排序器做「精确等于某条线索」判断 */
    public List<String> rawValues() {
        List<String> out = new ArrayList<>();
        out.addAll(phones);
        addIf(out, landline);
        addIf(out, birthday);
        addIf(out, namePinyin);
        addIf(out, houseNo);
        out.addAll(customs);
        return out;
    }

    /**
     * 指纹：线索内容变了 → 指纹变。
     * 用途只有一个 —— 落盘字典的文件名带指纹，避免换了线索还在跑旧候选。
     *
     * 手写 FNV-1a：不需要密码学强度，只要「同一台设备上内容同 → 指纹同」。
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (String p : phones) sb.append('p').append(p);
        sb.append('l').append(t(landline));
        sb.append('b').append(t(birthday));
        sb.append('n').append(t(namePinyin).toLowerCase(java.util.Locale.ROOT));
        sb.append('h').append(t(houseNo));
        for (String c : customs) sb.append('c').append(c);
        if (sb.length() == 0) return "none";
        long h = 0xcbf29ce484222325L;
        for (byte b : sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return String.format(java.util.Locale.ROOT, "%08x", h & 0xffffffffL);
    }

    // ---------------------------------------------------------------- 掩码

    /**
     * 掩码摘要。手机号只留前三后四，其它字段只报「填了没」或首字母。
     * 日志、界面提示一律走这里 —— 原值不出本机存储。
     */
    public String masked() {
        List<String> parts = new ArrayList<>();
        for (String p : phones) parts.add(mask(p));
        if (!t(landline).isEmpty()) parts.add(L.s("固话", "landline"));
        if (!t(birthday).isEmpty()) parts.add(L.s("生日", "birthday"));
        if (!t(namePinyin).isEmpty()) parts.add(L.s("拼音 ", "pinyin ") + mask(t(namePinyin)));
        if (!t(houseNo).isEmpty()) parts.add(L.s("门牌 ", "house ") + mask(t(houseNo)));
        if (!customs.isEmpty()) parts.add(L.s("自由词 ", "custom ") + customs.size() + L.s(" 条", ""));
        return String.join(" / ", parts);
    }

    private static String mask(String v) {
        String s = t(v);
        if (s.isEmpty()) return "";
        if (s.length() <= 4) return s.charAt(0) + "***";
        return s.substring(0, Math.min(3, s.length() - 4)) + "****"
                + s.substring(s.length() - 4);
    }

    // ---------------------------------------------------------------- 序列化

    /**
     * 最小转义格式：`phones=a,b§landline=x§birthday=y§pinyin=z§house=h§custom=c,d`
     * 分隔符与转义符都做百分号转义，字段值里出现任何字符都不会破坏结构。
     */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        if (!phones.isEmpty()) sb.append("phones=").append(joinEsc(phones));
        append(sb, "landline", t(landline));
        append(sb, "birthday", t(birthday));
        append(sb, "pinyin", t(namePinyin));
        append(sb, "house", t(houseNo));
        if (!customs.isEmpty()) {
            if (sb.length() > 0) sb.append('§');
            sb.append("custom=").append(joinEsc(customs));
        }
        return sb.toString();
    }

    /** 解回来。任何损坏都当空模型处理 —— 线索丢了大不了回退到旧行为，不能崩。 */
    public static SeedModel decode(String s) {
        SeedModel m = new SeedModel();
        if (s == null || s.isEmpty()) return m;
        for (String kv : s.split("§")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq);
            String v = kv.substring(eq + 1);
            if ("phones".equals(k)) {
                for (String x : splitEsc(v)) addPhone(m, x);
            } else if ("landline".equals(k)) {
                m.landline = clip(unesc(v));
            } else if ("birthday".equals(k)) {
                m.birthday = clip(unesc(v));
            } else if ("pinyin".equals(k)) {
                m.namePinyin = clip(unesc(v));
            } else if ("house".equals(k)) {
                m.houseNo = clip(unesc(v));
            } else if ("custom".equals(k)) {
                for (String x : splitEsc(v)) addCustom(m, x);
            }
        }
        return m;
    }

    // ---------------------------------------------------------------- 写入校验

    /** 加一个手机号：只留数字，必须 1 开头的 11 位；重复忽略 */
    public static boolean addPhone(SeedModel m, String raw) {
        String d = digits(raw);
        if (d.length() != 11 || d.charAt(0) != '1') return false;
        if (m.phones.contains(d)) return false;
        if (m.phones.size() >= MAX_PHONES) return false;
        m.phones.add(d);
        return true;
    }

    public static boolean addCustom(SeedModel m, String raw) {
        String v = clip(t(raw));
        if (v.isEmpty() || m.customs.contains(v)) return false;
        if (m.customs.size() >= MAX_CUSTOMS) return false;
        m.customs.add(v);
        return true;
    }

    // ---------------------------------------------------------------- 工具

    private static void append(StringBuilder sb, String k, String v) {
        if (v.isEmpty()) return;
        if (sb.length() > 0) sb.append('§');
        sb.append(k).append('=').append(esc(v));
    }

    private static void addIf(List<String> out, String v) {
        String s = t(v);
        if (!s.isEmpty()) out.add(s);
    }

    static String t(String s) { return s == null ? "" : s.trim(); }

    private static String clip(String s) {
        return s.length() <= MAX_FIELD ? s : s.substring(0, MAX_FIELD);
    }

    static String digits(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : t(s).toCharArray()) if (Character.isDigit(c)) sb.append(c);
        return sb.toString();
    }

    private static String joinEsc(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String x : list) {
            if (sb.length() > 0) sb.append(',');
            sb.append(esc(x));
        }
        return sb.toString();
    }

    private static List<String> splitEsc(String v) {
        List<String> out = new ArrayList<>();
        if (v.isEmpty()) return out;
        for (String x : v.split(",", -1)) out.add(unesc(x));
        return out;
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '%': sb.append("%25"); break;
                case ',': sb.append("%2C"); break;
                case '§': sb.append("%A7"); break;
                case '=': sb.append("%3D"); break;
                case '|': sb.append("%7C"); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                String hex = s.substring(i + 1, i + 3);
                try {
                    sb.append((char) Integer.parseInt(hex, 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {}
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
