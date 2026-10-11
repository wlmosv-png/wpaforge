package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.ListNetworksParser;

/**
 * `cmd wifi list-networks` 行解析的离线自测。
 *
 * 这个解析是「旧配置是否清干净」判据的基石：解析错了会返回 null，
 * 净检被静默绕过，系统拿已保存的旧密码连上 → 假阳性。
 * 含空格的 SSID 曾经就是踩过的坑（split("\\s+") 取 parts[1]）。
 */
public class NetIdLineTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static Long p(String line, String ssid) {
        return ListNetworksParser.netIdOfLine(line, ssid);
    }

    public static void main(String[] args) {
        System.out.println("== 常规行（真机格式）==");
        String l1 = "2            227                              wpa2-psk";
        check("普通 SSID 解析出 2", p(l1, "227") != null && p(l1, "227") == 2L);
        check("不匹配的 SSID 返回 null", p(l1, "228") == null);

        String l2 = "606          B209                             wpa2-psk";
        check("B209 → 606", p(l2, "B209") != null && p(l2, "B209") == 606L);

        System.out.println("== 含空格的 SSID（曾经的坑）==");
        String l3 = "12           My Net 5G                       wpa2-psk";
        check("含空格 SSID 能解析", p(l3, "My Net 5G") != null && p(l3, "My Net 5G") == 12L);
        check("名字更长的 SSID（MyX）不匹配", p(l3, "MyX") == null);
        check("只取后半段不匹配", p(l3, "Net 5G") == null);

        System.out.println("== 前缀误配防护 ==");
        String l4 = "9            1234                             wpa2-psk";
        check("123 不会误配 1234", p(l4, "123") == null);
        check("1234 正常匹配", p(l4, "1234") != null && p(l4, "1234") == 9L);

        String l5 = "9            123                              wpa2-psk";
        check("纯数字 SSID 123 能匹配", p(l5, "123") != null && p(l5, "123") == 9L);

        System.out.println("== 表头 / 空行 / 垃圾 ==");
        check("表头被跳过", p("Network Id      SSID                         Security type", "SSID") == null);
        check("空行返回 null", p("", "X") == null);
        check("null 行返回 null", p(null, "X") == null);
        check("纯文字返回 null", p("garbage", "garbage") == null);
        check("分隔线被跳过", p("----------", "X") == null);

        System.out.println("== 同 SSID 多行（wpa2 + wpa3）==");
        String l6 = "606          B209                             wpa3-sae^";
        check("第二行也能解析", p(l6, "B209") != null && p(l6, "B209") == 606L);

        System.out.println("== null ssid ==");
        check("null ssid 不崩", p(l1, null) == null);

        System.out.println("\n=== netId 行解析测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
