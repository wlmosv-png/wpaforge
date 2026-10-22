package io.github.wlmosv_png.wpaforge.audit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * hashcat 22000 格式解析（.hc22000）。
 *
 * <h3>为什么选这个格式</h3>
 * 它是目前事实标准：hashcat、hcxtools（hcxdumptool/hcxpcapngtool）、aircrack-ng
 * 都能导出。手机上没法抓包（本机实测驱动不允许切 monitor），所以材料必然来自
 * 外部抓包工具 —— 那就直接支持社区格式，而不是自造一个。
 *
 * <h3>两种行格式</h3>
 * <pre>
 *   WPA*01*PMKID*MAC_AP*MAC_STA*ESSID***
 *   WPA*02*MIC*MAC_AP*MAC_STA*ESSID*ANONCE*EAPOL*MESSAGEPAIR
 * </pre>
 * 所有字段为十六进制，无分隔符；MAC 是 12 位（6 字节），ESSID 是 UTF-8 的 hex。
 *
 * <h3>两种材料的速度差异</h3>
 * PMKID（01）只需 PBKDF2 + 一次 HMAC —— 没有 PTK 派生、没有 MIC 校验，
 * 因此单位时间能试的候选数接近 EAPOL（02）的两倍。抓包时优先要 PMKID。
 *
 * <p>纯逻辑，无 Android 依赖，可离线测试。
 */
public final class Hc22000 {

    public static final int TYPE_PMKID = 1;
    public static final int TYPE_EAPOL = 2;

    /** EAPOL-Key 帧里 MIC 字段的偏移（从 EAPOL 头算起） */
    public static final int EAPOL_MIC_OFFSET = 81;

    private Hc22000() {}

    /** 一条可用的握手材料 */
    public static final class Entry {
        public final int type;
        public final byte[] micOrPmkid;   // 16 字节
        public final byte[] apMac;        // 6 字节
        public final byte[] staMac;       // 6 字节
        public final String ssid;
        public final byte[] aNonce;       // 仅 type=2：32 字节
        public final byte[] eapol;        // 仅 type=2：完整 EAPOL 帧（MIC 已置零）
        public final String messagePair;

        Entry(int type, byte[] micOrPmkid, byte[] apMac, byte[] staMac, String ssid,
              byte[] aNonce, byte[] eapol, String messagePair) {
            this.type = type;
            this.micOrPmkid = micOrPmkid;
            this.apMac = apMac;
            this.staMac = staMac;
            this.ssid = ssid;
            this.aNonce = aNonce;
            this.eapol = eapol;
            this.messagePair = messagePair;
        }

        public boolean isPmkid() {
            return type == TYPE_PMKID;
        }

        /** 给用户看的一行摘要 */
        public String label() {
            String kind = isPmkid() ? "PMKID" : "EAPOL";
            return kind + " · " + (ssid == null || ssid.isEmpty() ? "(无 SSID)" : ssid)
                    + " · AP " + hexMac(apMac);
        }

        /** 材料是否齐全；空串表示齐全 */
        public String validate() {
            if (micOrPmkid == null || micOrPmkid.length != 16) return "校验值需 16 字节";
            if (apMac == null || apMac.length != 6) return "AP MAC 需 6 字节";
            if (staMac == null || staMac.length != 6) return "STA MAC 需 6 字节";
            if (ssid == null || ssid.isEmpty()) return "缺少 SSID";
            if (type == TYPE_EAPOL) {
                if (aNonce == null || aNonce.length != 32) return "ANonce 需 32 字节";
                if (eapol == null || eapol.length < EAPOL_MIC_OFFSET + 16) return "EAPOL 帧过短";
            }
            return "";
        }
    }

    public static String hexMac(byte[] mac) {
        if (mac == null || mac.length != 6) return "??:??:??:??:??:??";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", mac[i]));
        }
        return sb.toString();
    }

    /** 解析一行；不是有效行就返回 null（不抛异常 —— 文件里常有空行/注释）。 */
    public static Entry parseLine(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty() || s.startsWith("#")) return null;
        String[] f = s.split("\\*", -1);
        if (f.length < 6) return null;
        if (!"WPA".equals(f[0])) return null;
        int type;
        if ("01".equals(f[1])) type = TYPE_PMKID;
        else if ("02".equals(f[1])) type = TYPE_EAPOL;
        else return null;

        byte[] check = WpaCore.hexToBytes(f[2]);
        byte[] ap = WpaCore.hexToBytes(f[3]);
        byte[] sta = WpaCore.hexToBytes(f[4]);
        byte[] essidBytes = WpaCore.hexToBytes(f[5]);
        if (check == null || ap == null || sta == null || essidBytes == null) return null;
        String ssid = new String(essidBytes, StandardCharsets.UTF_8);

        if (type == TYPE_PMKID) {
            return new Entry(type, check, ap, sta, ssid, null, null, "");
        }
        if (f.length < 9) return null;
        byte[] aNonce = WpaCore.hexToBytes(f[6]);
        byte[] eapol = WpaCore.hexToBytes(f[7]);
        if (aNonce == null || eapol == null || eapol.length < EAPOL_MIC_OFFSET + 16) return null;
        return new Entry(type, check, ap, sta, ssid, aNonce, eapol, f[8]);
    }

    /** 解析整个文件，跳过无效行。 */
    public static List<Entry> parse(String text) {
        List<Entry> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\\r?\\n")) {
            Entry e = parseLine(line);
            if (e != null && e.validate().isEmpty()) out.add(e);
        }
        return out;
    }

    /** 把材料翻成 PMKID 校验结构（仅 PMKID 类型）。 */
    public static AuditEngine.Pmkid toPmkid(Entry e) {
        if (e == null || e.type != TYPE_PMKID) return null;
        return new AuditEngine.Pmkid(e.ssid, e.apMac, e.staMac, e.micOrPmkid);
    }

    /** 把材料翻成离线校验引擎要的握手结构（仅 EAPOL 类型）。 */
    public static AuditEngine.Handshake toHandshake(Entry e) {
        if (e == null || e.type != TYPE_EAPOL) return null;
        return new AuditEngine.Handshake(e.ssid, e.apMac, e.staMac, e.aNonce, null,
                e.eapol, EAPOL_MIC_OFFSET, e.micOrPmkid);
    }
}
