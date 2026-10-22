package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AuditEngine;
import io.github.wlmosv_png.wpaforge.audit.Hc22000;
import io.github.wlmosv_png.wpaforge.audit.WpaCore;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * hashcat 22000 格式解析测试。
 *
 * 重点是「坏行不崩」—— 这个格式的文件常由不同工具导出，混杂注释、空行、
 * 截断行是常态，解析器不能因此整份丢掉。
 */
public class Hc22000Test {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static String toHex(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String eapolHex() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 120; i++) sb.append(String.format("%02x", (i * 7) & 0xFF));
        return sb.toString();
    }

    public static void main(String[] args) {
        String ap = "001122334455";
        String sta = "66778899aabb";
        String pmkid = "8547b061fd9759b2779ea0bdb7bda7f9";
        String line01 = "WPA*01*" + pmkid + "*" + ap + "*" + sta + "*" + toHex("TestSSID") + "***";
        String line02 = "WPA*02*" + pmkid.substring(0, 32) + "*" + ap + "*" + sta + "*" + toHex("TestSSID")
                + "*" + eapolHex().substring(0, 64) + "*" + eapolHex() + "*2";

        System.out.println("== PMKID 行（WPA*01）==");
        Hc22000.Entry e1 = Hc22000.parseLine(line01);
        ok("解析成功", e1 != null);
        ok("类型是 PMKID", e1 != null && e1.isPmkid());
        ok("校验值 16 字节", e1 != null && e1.micOrPmkid.length == 16);
        ok("AP MAC 解析正确", e1 != null && "00:11:22:33:44:55".equals(Hc22000.hexMac(e1.apMac)));
        ok("SSID 解码正确", e1 != null && "TestSSID".equals(e1.ssid));
        ok("材料完整", e1 != null && e1.validate().isEmpty());
        ok("转成 Pmkid 结构", Hc22000.toPmkid(e1) != null);
        ok("PMKID 行不能转 Handshake", Hc22000.toHandshake(e1) == null);

        System.out.println("== EAPOL 行（WPA*02）==");
        Hc22000.Entry e2 = Hc22000.parseLine(line02);
        ok("解析成功", e2 != null);
        ok("类型是 EAPOL", e2 != null && e2.type == Hc22000.TYPE_EAPOL);
        ok("ANonce 32 字节", e2 != null && e2.aNonce.length == 32);
        ok("EAPOL 帧长度足够", e2 != null && e2.eapol.length >= Hc22000.EAPOL_MIC_OFFSET + 16);
        ok("message pair 读到了", e2 != null && "2".equals(e2.messagePair));
        ok("MIC 偏移常量 = 81", Hc22000.EAPOL_MIC_OFFSET == 81);
        ok("材料完整", e2 != null && e2.validate().isEmpty());
        AuditEngine.Handshake hs = Hc22000.toHandshake(e2);
        ok("转成 Handshake 结构", hs != null);
        ok("Handshake MIC 偏移被带上", hs != null && hs.micOffset == Hc22000.EAPOL_MIC_OFFSET);
        ok("EAPOL 行不能转 Pmkid", Hc22000.toPmkid(e2) == null);

        System.out.println("== 中文 SSID ==");
        Hc22000.Entry e3 = Hc22000.parseLine(
                "WPA*01*" + pmkid + "*" + ap + "*" + sta + "*" + toHex("小卖部WiFi5") + "***");
        ok("中文 SSID 正确解码", e3 != null && "小卖部WiFi5".equals(e3.ssid));

        System.out.println("== 坏行不崩 ==");
        ok("null", Hc22000.parseLine(null) == null);
        ok("空行", Hc22000.parseLine("") == null);
        ok("注释行", Hc22000.parseLine("# 这是注释") == null);
        ok("不是 WPA 前缀", Hc22000.parseLine("XXX*01*" + pmkid + "*" + ap + "*" + sta + "*" + toHex("S")) == null);
        ok("未知类型 03", Hc22000.parseLine("WPA*03*" + pmkid + "*" + ap + "*" + sta + "*" + toHex("S")) == null);
        ok("字段不足", Hc22000.parseLine("WPA*01*" + pmkid + "*" + ap) == null);
        ok("非法 hex", Hc22000.parseLine("WPA*01*zzzz*" + ap + "*" + sta + "*" + toHex("S")) == null);
        ok("EAPOL 帧过短被拒", Hc22000.parseLine(
                "WPA*02*" + pmkid + "*" + ap + "*" + sta + "*" + toHex("S") + "*" + eapolHex().substring(0, 64)
                        + "*00ff*2") == null);

        System.out.println("== 整份文件 ==");
        String file = "# 由 hcxpcapngtool 导出\n\n" + line01 + "\n"
                + "这是一行垃圾数据\n" + line02 + "\n\n";
        List<Hc22000.Entry> list = Hc22000.parse(file);
        ok("只取到 2 条有效材料", list.size() == 2);
        ok("顺序保持（先 PMKID 后 EAPOL）",
                list.size() == 2 && list.get(0).isPmkid() && list.get(1).type == Hc22000.TYPE_EAPOL);
        ok("空文本 -> 空列表", Hc22000.parse("").isEmpty());
        ok("null -> 空列表", Hc22000.parse(null).isEmpty());

        System.out.println("== 与 PMKID 引擎串起来 ==");
        List<io.github.wlmosv_png.wpaforge.audit.Candidate> dict = new java.util.ArrayList<>();
        dict.add(new io.github.wlmosv_png.wpaforge.audit.Candidate("12345678",
                io.github.wlmosv_png.wpaforge.audit.Candidate.Origin.DIGITS));
        final boolean[] hit = {false};
        new AuditEngine().runPmkid(Hc22000.toPmkid(list.get(0)), dict,
                new AuditEngine.Progress() {
                    @Override public void onProgress(long t, long total, String found) {}
                    @Override public void onDone(AuditEngine.Result r) { hit[0] = r.hit; }
                    @Override public void onError(String message) {}
                });
        ok("从 hc22000 行一路跑到命中", hit[0]);
        ok("hexToBytes 健全性（32 位 hex = 16 字节）",
                WpaCore.hexToBytes(pmkid).length == 16);

        System.out.println();
        System.out.println("=== hc22000 解析测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
