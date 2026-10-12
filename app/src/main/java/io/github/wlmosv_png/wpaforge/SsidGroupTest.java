package io.github.wlmosv_png.wpaforge;

import java.util.ArrayList;
import java.util.List;

import io.github.wlmosv_png.wpaforge.core.NetRecord;
import io.github.wlmosv_png.wpaforge.scan.ScanDetail;
import io.github.wlmosv_png.wpaforge.scan.SsidGroup;

/**
 * 展示层新组件测试：SSID 聚合 与 物理层细节。
 *
 * 这两个类都不参与判定链，但都会直接改变用户看到的东西，
 * 所以边界照样要钉住：空输入、隐藏网络、单 AP 退化、排序稳定性。
 *
 * ScanDetail 依赖 ScanResult（Android 类），离线环境造不出实例，
 * 所以只测它与 ScanResult 无关的部分（UNKNOWN / label / isEmpty）。
 * from() 的解析逻辑靠真机验收。
 */
public class SsidGroupTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static NetRecord rec(String ssid, String bssid, int rssi, int freq) {
        // capabilities 里塞个 WPA2，让 parseSecurity 有活干
        return new NetRecord(ssid, bssid, rssi, freq,
                NetRecord.parseSecurity("[WPA2-PSK-CCMP][ESS]"), false,
                "[WPA2-PSK-CCMP][ESS]");
    }

    public static void main(String[] args) {
        System.out.println("== 空输入 ==");
        check("null → 空列表", SsidGroup.group(null).isEmpty());
        check("空列表 → 空结果", SsidGroup.group(new ArrayList<>()).isEmpty());

        System.out.println("== 单 AP 不聚合（退化成原样）==");
        List<NetRecord> one = new ArrayList<>();
        one.add(rec("A", "aa:00:00:00:00:01", -50, 2432));
        List<SsidGroup.Group> g1 = SsidGroup.group(one);
        check("只有一组", g1.size() == 1);
        check("不是 mesh", !g1.get(0).isMesh());
        check("组内 1 个成员", g1.get(0).size() == 1);
        check("best 就是那一个", g1.get(0).best.rssi == -50);

        System.out.println("== 同 SSID 多 BSSID 聚合成一组 ==");
        List<NetRecord> many = new ArrayList<>();
        many.add(rec("CMCC", "aa:00:00:00:00:01", -84, 2412));
        many.add(rec("CMCC", "aa:00:00:00:00:02", -55, 5180));
        many.add(rec("CMCC", "aa:00:00:00:00:03", -61, 5745));
        List<SsidGroup.Group> g2 = SsidGroup.group(many);
        check("合成 1 组", g2.size() == 1);
        SsidGroup.Group cmcc = g2.get(0);
        check("3 个 AP", cmcc.size() == 3);
        check("识别为 mesh", cmcc.isMesh());
        check("best = 最强那个 (-55)", cmcc.best.rssi == -55);
        check("worst = 最弱那个 (-84)", cmcc.worstRssi() == -84);
        check("组内已按 RSSI 降序",
                cmcc.members.get(0).rssi >= cmcc.members.get(1).rssi
                && cmcc.members.get(1).rssi >= cmcc.members.get(2).rssi);
        check("跨 3 个信道", cmcc.distinctChannels() == 3);

        System.out.println("== 同 SSID 同信道（不重复计数）==");
        List<NetRecord> same = new ArrayList<>();
        same.add(rec("X", "aa:00:00:00:00:01", -60, 2432));
        same.add(rec("X", "aa:00:00:00:00:02", -70, 2432));
        SsidGroup.Group gx = SsidGroup.group(same).get(0);
        check("2 个 AP 同信道 → distinctChannels = 1", gx.distinctChannels() == 1);

        System.out.println("== 隐藏网络各自独立（不聚合）==");
        List<NetRecord> hid = new ArrayList<>();
        hid.add(rec("", "aa:00:00:00:00:01", -60, 2432));
        hid.add(rec("", "aa:00:00:00:00:02", -70, 2432));
        List<SsidGroup.Group> gh = SsidGroup.group(hid);
        check("两个隐藏网络 → 两组", gh.size() == 2);
        check("各自 1 个成员", gh.get(0).size() == 1 && gh.get(1).size() == 1);
        check("都不算 mesh", !gh.get(0).isMesh() && !gh.get(1).isMesh());

        System.out.println("== 组间按最强信号排序 ==");
        List<NetRecord> mixed = new ArrayList<>();
        mixed.add(rec("weak", "aa:00:00:00:00:01", -90, 2432));
        mixed.add(rec("weak", "aa:00:00:00:00:02", -88, 2432));
        mixed.add(rec("strong", "bb:00:00:00:00:01", -30, 2432));
        List<SsidGroup.Group> gm = SsidGroup.group(mixed);
        check("两组", gm.size() == 2);
        check("强的在前", "strong".equals(gm.get(0).ssid));
        check("弱的在后", "weak".equals(gm.get(1).ssid));

        System.out.println("== 含 null 元素不崩 ==");
        List<NetRecord> withNull = new ArrayList<>();
        withNull.add(rec("A", "aa:00:00:00:00:01", -50, 2432));
        withNull.add(null);
        check("null 被跳过", SsidGroup.group(withNull).size() == 1);

        System.out.println("== ScanDetail 与 ScanResult 无关的部分 ==");
        check("UNKNOWN 是空的", ScanDetail.UNKNOWN.isEmpty());
        check("UNKNOWN label 为空串", ScanDetail.UNKNOWN.label().isEmpty());
        check("UNKNOWN.from(null) 不崩", ScanDetail.from(null) == ScanDetail.UNKNOWN);

        System.out.println("\n=== SSID 聚合测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
