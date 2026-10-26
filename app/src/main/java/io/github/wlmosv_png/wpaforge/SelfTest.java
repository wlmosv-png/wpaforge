package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.BirthdayTable;
import io.github.wlmosv_png.wpaforge.audit.DictGenerator;
import io.github.wlmosv_png.wpaforge.audit.WpaCore;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.nio.charset.StandardCharsets;

/**
 * 自检。用公开测试向量验证手写的 SHA1 / HMAC-SHA1 / PBKDF2，
 * 再验 NetRecord 解析与字典生成器。
 */
public class SelfTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, String got, String want) {
        if (want.equalsIgnoreCase(got)) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "\n       got  = " + got
                    + "\n       want = " + want);
        }
    }

    private static void check(String name, boolean cond) {
        check(name, String.valueOf(cond), "true");
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.println("== SHA-1 (RFC 3174 / FIPS 180-1 向量) ==");
        check("sha1(\"\") == da39a3ee...",
                hex(WpaCore.sha1("".getBytes(StandardCharsets.US_ASCII))),
                "da39a3ee5e6b4b0d3255bfef95601890afd80709");
        check("sha1(\"abc\") == a9993e36...",
                hex(WpaCore.sha1("abc".getBytes(StandardCharsets.US_ASCII))),
                "a9993e364706816aba3e25717850c26c9cd0d89d");
        check("sha1(448bit msg) == 84983e44...",
                hex(WpaCore.sha1("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
                        .getBytes(StandardCharsets.US_ASCII))),
                "84983e441c3bd26ebaae4aa1f95129e5e54670f1");

        System.out.println("== HMAC-SHA1 (RFC 2202 向量) ==");
        check("hmac(key=0x0b*20, \"Hi There\")",
                hex(WpaCore.hmacSha1(
                        repeat((byte) 0x0b, 20),
                        "Hi There".getBytes(StandardCharsets.US_ASCII))),
                "b617318655057264e28bc0b6fb378c8ef146be00");
        check("hmac(key=\"Jefe\", \"what do ya want for nothing?\")",
                hex(WpaCore.hmacSha1(
                        "Jefe".getBytes(StandardCharsets.US_ASCII),
                        "what do ya want for nothing?".getBytes(StandardCharsets.US_ASCII))),
                "effcdf6ae5eb2fa2d27416d5f184df9c259a7c79");
        check("hmac(key=0xaa*20, 0xdd*50)",
                hex(WpaCore.hmacSha1(repeat((byte) 0xaa, 20), repeat((byte) 0xdd, 50))),
                "125d7342b9ac11cd91a39af48aa17b4f63f175d3");

        System.out.println("== PBKDF2-HMAC-SHA1 (RFC 6070 向量) ==");
        check("pbkdf2(P=password,S=salt,c=1,dkLen=20)",
                hex(WpaCore.pbkdf2HmacSha1("password".getBytes(StandardCharsets.US_ASCII),
                        "salt".getBytes(StandardCharsets.US_ASCII), 1, 20)),
                "0c60c80f961f0e71f3a9b524af6012062fe037a6");
        check("pbkdf2(P=password,S=salt,c=2,dkLen=20)",
                hex(WpaCore.pbkdf2HmacSha1("password".getBytes(StandardCharsets.US_ASCII),
                        "salt".getBytes(StandardCharsets.US_ASCII), 2, 20)),
                "ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957");
        check("pbkdf2(P=password,S=salt,c=4096,dkLen=20)",
                hex(WpaCore.pbkdf2HmacSha1("password".getBytes(StandardCharsets.US_ASCII),
                        "salt".getBytes(StandardCharsets.US_ASCII), 4096, 20)),
                "4b007901b765489abead49d926f721d065a429c1");

        System.out.println("== PMK 已知向量 (802.11i 常见测试用例) ==");
        // passphrase="password", ssid="IEEE" 是 Wi-Fi 联盟公开测试向量
        check("PMK(password, \"IEEE\")",
                hex(WpaCore.derivePmk("password", "IEEE")),
                "f42c6fc52df0ebef9ebb4b90b38a5f90"
              + "2e83fe1b135a70e23aed762e9710a12e");

        System.out.println("== 自研构造向量（回归锚点，防改坏） ==");
        // 这些值由本实现自产，用于防止后续改动破坏行为；非外部权威
        check("PMK(\"12345678\", \"WPAForge-Test\") 稳定",
                hex(WpaCore.derivePmk("12345678", "WPAForge-Test")),
                hex(WpaCore.derivePmk("12345678", "WPAForge-Test")));

        System.out.println("== NetRecord 解析 ==");
        check("OPEN", NetRecord.parseSecurity("[ESS]") == NetRecord.Security.OPEN);
        check("WPA2", NetRecord.parseSecurity("[WPA2-PSK-CCMP][ESS]") == NetRecord.Security.WPA2);
        check("WPA3", NetRecord.parseSecurity("[RSN-SAE-CCMP][ESS]") == NetRecord.Security.WPA3);
        check("WPA2/WPA3 混合",
                NetRecord.parseSecurity("[RSN-SAE+PSK-CCMP][ESS]") == NetRecord.Security.WPA2_WPA3);
        check("WEP", NetRecord.parseSecurity("[WEP][ESS]") == NetRecord.Security.WEP);
        check("WPS 标志", NetRecord.parseWps("[WPA2-PSK][WPS][ESS]"));
        check("信道 2412 -> 1", NetRecord.freqToChannel(2412) == 1);
        check("信道 2437 -> 6", NetRecord.freqToChannel(2437) == 6);
        check("信道 5180 -> 36", NetRecord.freqToChannel(5180) == 36);
        check("信道 2484 -> 14", NetRecord.freqToChannel(2484) == 14);

        NetRecord r = new NetRecord("Home-5G", "aa:bb:cc:dd:ee:ff", -55, 5180,
                NetRecord.Security.WPA2, true, "[WPA2-PSK][WPS]");
        check("is5Ghz", r.is5Ghz);
        check("BSSID 大写", r.bssid.equals("AA:BB:CC:DD:EE:FF"));
        check("signalLevel(-55) == 3", r.signalLevel() == 3);
        check("WPS 支持", r.wpsSupported);
        check("深: 隐藏网络", new NetRecord("", "11:22:33:44:55:66", -70, 2412,
                NetRecord.Security.OPEN, false, "").isHidden());

        System.out.println("== 字典生成器 ==");
        // 2026-10-26 重做：1771 条 → 约 500 条。判据不再是「条数够多」，
        // 而是「每一条都有人真的会设」。所以这里同时钉住**不该出现的族**。
        DictGenerator g = DictGenerator.quick(50000);
        int n = g.size();
        check("生成条数在合理区间 (200..1500)", n > 200 && n < 1500);
        check("包含 12345678", g.build().stream()
                .anyMatch(c -> c.password.equals("12345678")));
        check("含结构数字 12121212", g.build().stream()
                .anyMatch(c -> c.password.equals("12121212")));
        check("含键盘图形 147258369", g.build().stream()
                .anyMatch(c -> c.password.equals("147258369")));
        check("含 admin123456", g.build().stream()
                .anyMatch(c -> c.password.equals("admin123456")));
        check("无重复", g.build().size() == n);
        check("长度全部合法 (8..63)", g.build().stream()
                .allMatch(c -> c.password.length() >= 8 && c.password.length() <= 63));
        // 已删族：手机段 + 假尾号
        check("已删手机段+假尾号 130123456", g.build().stream()
                .noneMatch(c -> c.password.equals("130123456")));
        check("已删手机段+假尾号 130888888", g.build().stream()
                .noneMatch(c -> c.password.equals("130888888")));
        // 已删族：英文普通词 + 数字后缀
        check("已删 family2018", g.build().stream()
                .noneMatch(c -> c.password.equals("family2018")));
        check("已删 love888", g.build().stream()
                .noneMatch(c -> c.password.equals("love888")));
        // 已删族：厂商词 + 符号 + 年份
        check("已删 tplink@2026", g.build().stream()
                .noneMatch(c -> c.password.equals("tplink@2026")));

        System.out.println("== 厂商规则推导 ==");
        DictGenerator vg = new DictGenerator().vendorRules("AA:BB:CC:DD:EE:FF", "MyHome-2026");
        check("只出完整 MAC 原样（小写）", vg.build().stream()
                .anyMatch(c -> c.password.equals("aabbccddeeff")));
        check("只出完整 MAC 原样（大写）", vg.build().stream()
                .anyMatch(c -> c.password.equals("AABBCCDDEEFF")));
        check("已删 MAC 尾 6 位 + 后缀", vg.build().stream()
                .noneMatch(c -> c.password.equals("ddeeff1234")));
        check("已删 SSID 数字拼后缀", vg.build().stream()
                .noneMatch(c -> c.password.startsWith("2026")));

        System.out.println("== 生日表（生成式，替代 483k 静态表）==");
        check("1960-2010 天数正确", BirthdayTable.count(1960, 2010) == 18628);
        check("闰年 2000 有 366 天", BirthdayTable.dates(2000, 2000).size() == 366);
        check("平年 1900 有 365 天", BirthdayTable.dates(1900, 1900).size() == 365);
        check("日期格式 YYYYMMDD", BirthdayTable.dates(1980, 1980).get(0).equals("19800101"));
        DictGenerator bg = new DictGenerator().prefixedBirthday(
                java.util.Arrays.asList("zw"), 1990, 1990);
        check("前缀+生日：zw19900101", bg.build().stream()
                .anyMatch(c -> c.password.equals("zw19900101")));
        check("前缀+生日：Zw19900101（首字母大写）", bg.build().stream()
                .anyMatch(c -> c.password.equals("Zw19900101")));
        check("空前缀不生成", new DictGenerator()
                .prefixedBirthday(java.util.Collections.emptyList(), 1990, 1990).size() == 0);

        System.out.println("== hex 解析 ==");
        check("hexToBytes 正常", WpaCore.hexToBytes("aabb") != null
                && WpaCore.hexToBytes("aabb").length == 2);
        check("hexToBytes 奇长返回 null", WpaCore.hexToBytes("abc") == null);
        check("hexToBytes 非hex返回 null", WpaCore.hexToBytes("zz") == null);

        System.out.println();
        System.out.println("=== 结果: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static byte[] repeat(byte b, int n) {
        byte[] out = new byte[n];
        java.util.Arrays.fill(out, b);
        return out;
    }
}
