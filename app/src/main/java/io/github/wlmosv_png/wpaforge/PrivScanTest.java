package io.github.wlmosv_png.wpaforge;

import java.util.List;

import io.github.wlmosv_png.wpaforge.core.NetRecord;
import io.github.wlmosv_png.wpaforge.scan.PrivScanReader;

/**
 * PrivScanReader 解析测试。
 *
 * 输入是本机实测的 `cmd wifi list-scan-results` 原始输出，
 * 覆盖：常规 SSID、含下划线的 SSID、中文 SSID、含空格的 SSID、
 * 隐藏网络、5G 频段、WPS 标志、RSSI 带括号的写法。
 */
public final class PrivScanTest {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    /** 与设备实测输出同构的样本 */
    static final String SAMPLE =
        "    BSSID              Frequency      RSSI           Age(sec)     SSID                                 Flags\n" +
        "  28:93:7d:11:22:02       2432    -46(0:-49/1:-49)   169.150    B209                              [WPA2-PSK-CCMP+TKIP][RSN-PSK-CCMP+TKIP][WPA-PSK-CCMP+TKIP][ESS][WPS]\n" +
        "  cc:2d:21:11:22:10       5220    -90(0:-92/1:-94)   169.517    Tenda_B088D0_5G                   [WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]\n" +
        "  5c:de:34:11:22:0b       5765    -89(0:-93/1:-92)   169.392    CMCC-密码                          [WPA-PSK-CCMP][WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]\n" +
        "  f4:6a:92:11:22:0d       2467    -48(0:-50/1:-54)   168.473    FAST_E972                         [WPA-PSK-CCMP][WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]\n" +
        "  f4:83:cd:11:22:0e       2412    -70(0:-71/1:-77)   169.623    南面217                            [WPA2-PSK-CCMP][RSN-PSK-CCMP][WPA-PSK-CCMP][ESS]\n" +
        "  82:93:7d:11:22:07       2447    -64(0:-68/1:-67)   168.876    2.4G-3                            [WPA2-PSK-CCMP+TKIP][RSN-PSK-CCMP+TKIP][WPA-PSK-CCMP+TKIP][ESS][WPS]\n" +
        "  aa:bb:cc:dd:ee:ff       2437    -55                 12.0       My Home Net                       [WPA2-PSK-CCMP][ESS]\n" +
        "  11:22:33:44:55:66       2412    -80                 5.0                                          [ESS]\n";

    public static void main(String[] args) {
        System.out.println("== 特权扫描解析 ==");

        List<NetRecord> rs = PrivScanReader.parse(SAMPLE);

        ok("解析出 8 条", rs.size() == 8);

        NetRecord b209 = find(rs, "B209");
        ok("B209 存在", b209 != null);
        if (b209 != null) {
            ok("B209 RSSI=-46", b209.rssi == -46);
            ok("B209 频道 5", b209.channel == 5);
            ok("B209 非 5G", !b209.is5Ghz);
            ok("B209 判定 WPA2", b209.security == NetRecord.Security.WPA2);
            ok("B209 支持 WPS", b209.wpsSupported);
            ok("B209 BSSID 全大写", "28:93:7D:11:22:02".equals(b209.bssid));
        }

        NetRecord tenda = find(rs, "Tenda_B088D0_5G");
        ok("5G 网络识别", tenda != null && tenda.is5Ghz && tenda.channel == 44);

        NetRecord cmcc = find(rs, "CMCC-密码");
        ok("中文 SSID 正常", cmcc != null);

        NetRecord nan = find(rs, "南面217");
        ok("中文+数字 SSID 正常", nan != null);

        NetRecord space = find(rs, "My Home Net");
        ok("含空格 SSID 正确切分", space != null);
        if (space != null) {
            ok("含空格 SSID 未被截断", "My Home Net".equals(space.ssid));
            ok("RSSI 无括号也能解析", space.rssi == -55);
            ok("无 WPS 标志", !space.wpsSupported);
        }

        // 隐藏网络（SSID 为空）
        boolean hasHidden = false;
        for (NetRecord r : rs) if (r.isHidden()) hasHidden = true;
        ok("空 SSID 识别为隐藏网络", hasHidden);

        // 排序：按信号强度从强到弱
        boolean sorted = true;
        for (int i = 1; i < rs.size(); i++) {
            if (rs.get(i - 1).rssi < rs.get(i).rssi) { sorted = false; break; }
        }
        ok("按 RSSI 降序排列", sorted);

        // 异常输入不崩
        ok("null 输入返回空", PrivScanReader.parse(null).isEmpty());
        ok("空串返回空", PrivScanReader.parse("").isEmpty());
        ok("表头单独返回空", PrivScanReader.parse(
                "    BSSID              Frequency      RSSI           Age(sec)     SSID    Flags\n").isEmpty());
        ok("垃圾行不崩", PrivScanReader.parse("hello world\n=== ===\n").isEmpty());

        System.out.println("\n=== 特权解析测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static NetRecord find(List<NetRecord> rs, String ssid) {
        for (NetRecord r : rs) if (ssid.equals(r.ssid)) return r;
        return null;
    }
}
