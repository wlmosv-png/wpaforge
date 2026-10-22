package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.ConnectSpec;

/**
 * 连接命令拼装与转义测试。
 *
 * 这一套存在的意义：命令拼错**不会报错**，只会让 netId 建不出来、
 * 判定永远失败。所以「命令长什么样」必须被测试钉死，而不是靠肉眼看。
 */
public class ConnectSpecTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, String got, String want) {
        if (want.equals(got)) { pass++; System.out.println("  OK   " + name); }
        else {
            fail++;
            System.out.println("  FAIL " + name + "\n       got  = " + got + "\n       want = " + want);
        }
    }

    public static void main(String[] args) {
        System.out.println("== shellQuote 转义 ==");
        eq("普通串", ConnectSpec.shellQuote("abc"), "'abc'");
        eq("含空格", ConnectSpec.shellQuote("My Net"), "'My Net'");
        eq("含单引号", ConnectSpec.shellQuote("it's"), "'it'\\''s'");
        eq("含双引号", ConnectSpec.shellQuote("a\"b"), "'a\"b'");
        eq("含 $ 与反引号不被解释", ConnectSpec.shellQuote("$x`y`"), "'$x`y`'");
        eq("含分号不被当分隔符", ConnectSpec.shellQuote("a;rm -rf /"), "'a;rm -rf /'");
        eq("null -> 空引号", ConnectSpec.shellQuote(null), "''");
        eq("空串 -> 空引号", ConnectSpec.shellQuote(""), "''");
        eq("中文 SSID", ConnectSpec.shellQuote("小卖部WiFi"), "'小卖部WiFi'");

        System.out.println("== looksLikeMac ==");
        check("标准大写", ConnectSpec.looksLikeMac("AA:BB:CC:DD:EE:FF"));
        check("标准小写", ConnectSpec.looksLikeMac("aa:bb:cc:dd:ee:ff"));
        check("混合大小写", ConnectSpec.looksLikeMac("Aa:bB:0c:Dd:Ee:1f"));
        check("少一段不算", !ConnectSpec.looksLikeMac("AA:BB:CC:DD:EE"));
        check("连字符写法不算", !ConnectSpec.looksLikeMac("AA-BB-CC-DD-EE-FF"));
        check("非十六进制不算", !ConnectSpec.looksLikeMac("GG:BB:CC:DD:EE:FF"));
        check("空串不算", !ConnectSpec.looksLikeMac(""));
        check("null 不算", !ConnectSpec.looksLikeMac(null));
        check("带空格不算", !ConnectSpec.looksLikeMac(" AA:BB:CC:DD:EE:FF"));

        System.out.println("== describe（日志用，参数是否带上要看得见）==");
        check("未指定 BSSID 时明确写出来",
                ConnectSpec.describe("N", "wpa2", null, false).contains("未指定"));
        check("指定 BSSID 时写出来",
                ConnectSpec.describe("N", "wpa2", "aa:bb:cc:dd:ee:ff", false)
                        .contains("-b AA:BB:CC:DD:EE:FF"));
        check("脏 BSSID 视为未指定（不会写进日志冒充生效）",
                ConnectSpec.describe("N", "wpa2", "not-a-mac", false).contains("未指定"));
        check("MAC 轮换开启时写出来",
                ConnectSpec.describe("N", "wpa2", null, true).contains("non_persistent"));
        check("MAC 轮换关闭时写未启用",
                ConnectSpec.describe("N", "wpa2", null, false).contains("未启用"));
        check("describe 里不含密码占位以外的敏感内容",
                !ConnectSpec.describe("N", "wpa2", null, false).contains("password"));

        System.out.println("== buildConnect 基础形态 ==");
        eq("不带可选参数",
                ConnectSpec.buildConnect("B212", "wpa2", "12345678", null, false),
                "cmd wifi connect-network 'B212' wpa2 '12345678'");
        eq("SSID 含空格整段被引号包住",
                ConnectSpec.buildConnect("My Net", "wpa2", "pw", null, false),
                "cmd wifi connect-network 'My Net' wpa2 'pw'");
        eq("密码含单引号正确逃逸",
                ConnectSpec.buildConnect("N", "wpa2", "it's", null, false),
                "cmd wifi connect-network 'N' wpa2 'it'\\''s'");
        eq("security 为 null 退回 wpa2",
                ConnectSpec.buildConnect("N", null, "pw", null, false),
                "cmd wifi connect-network 'N' wpa2 'pw'");
        eq("security 为空串退回 wpa2",
                ConnectSpec.buildConnect("N", "", "pw", null, false),
                "cmd wifi connect-network 'N' wpa2 'pw'");

        System.out.println("== 参数 -b（锁定 AP）==");
        eq("合法 BSSID 被拼上",
                ConnectSpec.buildConnect("N", "wpa2", "pw", "AA:BB:CC:DD:EE:FF", false),
                "cmd wifi connect-network 'N' wpa2 'pw' -b AA:BB:CC:DD:EE:FF");
        eq("小写 BSSID 转成大写",
                ConnectSpec.buildConnect("N", "wpa2", "pw", "aa:bb:cc:dd:ee:ff", false),
                "cmd wifi connect-network 'N' wpa2 'pw' -b AA:BB:CC:DD:EE:FF");
        eq("脏 BSSID 被忽略（绝不拼进命令）",
                ConnectSpec.buildConnect("N", "wpa2", "pw", "not-a-mac", false),
                "cmd wifi connect-network 'N' wpa2 'pw'");
        eq("空 BSSID 被忽略",
                ConnectSpec.buildConnect("N", "wpa2", "pw", "", false),
                "cmd wifi connect-network 'N' wpa2 'pw'");

        System.out.println("== 参数 -r（随机 MAC）==");
        eq("开启时带 non_persistent",
                ConnectSpec.buildConnect("N", "wpa2", "pw", null, true),
                "cmd wifi connect-network 'N' wpa2 'pw' -r non_persistent");
        eq("两个参数可同时出现",
                ConnectSpec.buildConnect("N", "wpa3", "pw", "11:22:33:44:55:66", true),
                "cmd wifi connect-network 'N' wpa3 'pw' -b 11:22:33:44:55:66 -r non_persistent");
        check("不带 -r 时不出现 non_persistent",
                !ConnectSpec.buildConnect("N", "wpa2", "pw", null, false).contains("-r"));

        System.out.println();
        System.out.println("=== 连接命令测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
