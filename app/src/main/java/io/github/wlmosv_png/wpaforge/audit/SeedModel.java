package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import java.util.ArrayList;
import java.util.List;

/**
 * 目标的已知线索（社会工程学画像）。
 *
 * 设计依据（2026-10-26 重做）：扒了 CUPP(6.6k★)、elpscrk(964★)、rhodiola、
 * pcfg_cracker 之后的结论 —— 主流工具做的是**画像**，不是字符串变形。
 * 它们只问"关于这个人你知道什么"，每个维度只出极少数**人真会写**的形态，
 * 再靠"维度之间互相组合"来扩展。
 *
 * 所以本类只负责"存事实"，不负责"变形"。变形由 {@link SeedDeriver} 按
 * 明确的档位与闸门来做。
 *
 * 三条硬约束：
 *  1. 全部字段可留空。为空时派生结果与"只有 SSID"的旧版本一致。
 *  2. 不用 org.json（android.jar 里是 stub），保持桌面单测可跑 —— 见 encode()/decode()。
 *  3. 只存本机；任何展示与日志都走 masked()，原值不外流。
 */
public final class SeedModel {

    /** 手机号，最多 4 个（户主 / 家人 / 宽带装机号） */
    public final List<String> phones = new ArrayList<>();
    /** 姓名 / 昵称 / 家人名 的拼音，最多 4 个（zhangwei、xiaobao…） */
    public final List<String> names = new ArrayList<>();
    /** 生日，原样存：19900101 / 900101 / 0101 / 1990-01-01 都接受 */
    public String birthday = "";
    /** 门牌 / 楼层 / 房间号 */
    public String houseNo = "";
    /** 固话或宽带账号 */
    public String landline = "";
    /**
     * 该目标**以前用过的口令**。
     *
     * 只允许填同一个目标的旧口令（人改密码基本是在旧密码上微调，
     * 这是画像里价值最高的一条）。**不做跨目标复用** ——
     * 拿 A 网的密码去试 B 网不在"自有/授权网络"这条线内。
     */
    public String oldPassword = "";
    /** 自由词，最多 8 条（常用数字、爱好、常去的店名…） */
    public final List<String> customs = new ArrayList<>();
    /**
     * 是否也生成"跟 SSID 相关"的候选。
     *
     * 默认关：本机 107 次真实命中里没有一次来自 SSID 派生，
     * 而它现在是候选量最大的一块，把前排位置挤掉了。
     * 想试的人可以打开，打开后它排在所有画像候选之后。
     */
    public boolean useSsid = false;

    public static final int MAX_PHONES = 4;
    public static final int MAX_NAMES = 4;
    public static final int MAX_CUSTOMS = 8;

    /** 单字段最长接受长度，防手滑粘进一整段文本 */
    private static final int MAX_FIELD = 32;

    public SeedModel() {}

    // ---------------------------------------------------------------- 语义

    /** 一条线索都没填 */
    public boolean isEmpty() {
        return phones.isEmpty()
                && names.isEmpty()
                && t(birthday).isEmpty()
                && t(houseNo).isEmpty()
                && t(landline).isEmpty()
                && t(oldPassword).isEmpty()
                && customs.isEmpty();
    }

    /** 非空字段数，用于界面与日志说"已启用 N 项" */
    public int count() {
        int n = 0;
        if (!phones.isEmpty()) n++;
        if (!names.isEmpty()) n++;
        if (!t(birthday).isEmpty()) n++;
        if (!t(houseNo).isEmpty()) n++;
        if (!t(landline).isEmpty()) n++;
        if (!t(oldPassword).isEmpty()) n++;
        if (!customs.isEmpty()) n++;
        return n;
    }

    /** 原值清单：排序器用它判断"这条候选精确等于某条线索" */
    public List<String> rawValues() {
        List<String> out = new ArrayList<>();
        out.addAll(phones);
        out.addAll(names);
        addIf(out, birthday);
        addIf(out, houseNo);
        addIf(out, landline);
        addIf(out, oldPassword);
        out.addAll(customs);
        return out;
    }

    /**
     * 指纹：内容变了指纹就变。
     * 只有一个用途 —— 落盘字典的文件名带指纹，避免换了画像还在跑旧候选。
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (String p : phones) sb.append('p').append(p);
        for (String x : names) sb.append('n').append(x.toLowerCase(java.util.Locale.ROOT));
        sb.append('b').append(t(birthday));
        sb.append('h').append(t(houseNo));
        sb.append('l').append(t(landline));
        sb.append('o').append(t(oldPassword));
        for (String c : customs) sb.append('c').append(c);
        sb.append('s').append(useSsid ? 1 : 0);
        if (sb.length() == 0) return "none";
        long h = 0xcbf29ce484222325L;
        for (byte b : sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return String.format(java.util.Locale.ROOT, "%08x", h & 0xffffffffL);
    }

    // ---------------------------------------------------------------- 掩码

    /** 掩码摘要。手机号留前三后四，其它只报"填了没"。原值不出本机存储。 */
    public String masked() {
        List<String> parts = new ArrayList<>();
        for (String p : phones) parts.add(mask(p));
        if (!names.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(mask(t(names.get(i))));
            }
            parts.add(L.s("拼音 ", "names ") + sb);
        }
        if (!t(birthday).isEmpty()) parts.add(L.s("生日", "birthday"));
        if (!t(houseNo).isEmpty()) parts.add(L.s("门牌 ", "house ") + mask(t(houseNo)));
        if (!t(landline).isEmpty()) parts.add(L.s("宽带/固话", "landline"));
        if (!t(oldPassword).isEmpty()) parts.add(L.s("旧口令(已记)", "old pw (kept)"));
        if (!customs.isEmpty()) parts.add(L.s("自由词 ", "custom ") + customs.size() + L.s(" 条", ""));
        if (useSsid) parts.add(L.s("含 SSID 派生", "with SSID"));
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
     * 最小转义格式：`phones=a,b§names=x,y§birthday=..§house=..§landline=..§old=..§custom=..§ssid=1`
     * 分隔符与转义符都做百分号转义，字段值里出现任何字符都不会破坏结构。
     * 老版本（1.9.26 测试版）写的 `pinyin=` 会被当作 names 读进来。
     */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        if (!phones.isEmpty()) sb.append("phones=").append(joinEsc(phones));
        if (!names.isEmpty()) {
            if (sb.length() > 0) sb.append('§');
            sb.append("names=").append(joinEsc(names));
        }
        append(sb, "birthday", t(birthday));
        append(sb, "house", t(houseNo));
        append(sb, "landline", t(landline));
        append(sb, "old", t(oldPassword));
        if (!customs.isEmpty()) {
            if (sb.length() > 0) sb.append('§');
            sb.append("custom=").append(joinEsc(customs));
        }
        if (useSsid) {
            if (sb.length() > 0) sb.append('§');
            sb.append("ssid=1");
        }
        return sb.toString();
    }

    /** 解回来。任何损坏都当空模型 —— 画像丢了大不了回退到旧行为，不能崩。 */
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
            } else if ("names".equals(k) || "pinyin".equals(k)) {
                for (String x : splitEsc(v)) addName(m, x);
            } else if ("birthday".equals(k)) {
                m.birthday = clip(unesc(v));
            } else if ("house".equals(k)) {
                m.houseNo = clip(unesc(v));
            } else if ("landline".equals(k)) {
                m.landline = clip(unesc(v));
            } else if ("old".equals(k)) {
                m.oldPassword = clip(unesc(v));
            } else if ("custom".equals(k)) {
                for (String x : splitEsc(v)) addCustom(m, x);
            } else if ("ssid".equals(k)) {
                m.useSsid = "1".equals(v);
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

    /** 加一个姓名拼音：只留字母转小写，2~24 字符 */
    public static boolean addName(SeedModel m, String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : t(raw).toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z') sb.append(c);
        }
        String v = sb.toString();
        if (v.length() < 2 || v.length() > 24) return false;
        if (m.names.contains(v)) return false;
        if (m.names.size() >= MAX_NAMES) return false;
        m.names.add(v);
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

    /**
     * 生日归一。返回 {8 位完整, 6 位YYMMDD, MMDD}，缺的给空串。
     * 接受 19900101 / 900101 / 0101 / 1990-01-01 这几种写法。
     * 放在这里而不是派生器里：它解析的是本类字段，跟"怎么用"无关。
     */
    public static String[] birthdayForms(String raw) {
        String d = digits(raw);
        String full = "", yy = "", mmdd = "";
        if (d.length() >= 8) {
            full = d.substring(d.length() - 8);
            yy = full.substring(2);
            mmdd = full.substring(4);
        } else if (d.length() == 6) {
            yy = d;
            full = "19" + d;
            mmdd = d.substring(2);
        } else if (d.length() == 4) {
            mmdd = d;
        } else if (d.length() == 5) {
            mmdd = d.substring(1);
        }
        return new String[]{full, yy, mmdd};
    }

    private static void addIf(List<String> out, String v) {
        String s = t(v);
        if (!s.isEmpty()) out.add(s);
    }

    private static void append(StringBuilder sb, String k, String v) {
        if (v.isEmpty()) return;
        if (sb.length() > 0) sb.append('§');
        sb.append(k).append('=').append(esc(v));
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
