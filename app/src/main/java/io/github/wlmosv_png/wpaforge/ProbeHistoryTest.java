package io.github.wlmosv_png.wpaforge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.wlmosv_png.wpaforge.scan.LanProbe;
import io.github.wlmosv_png.wpaforge.scan.ProbeHistory;

/**
 * ProbeHistory 的差分逻辑测试（不碰文件，只测纯计算）。
 *
 * 「新设备告警」是这个功能的立身之本 —— 误报会让人白紧张，
 * 漏报等于功能失效。所以边界必须钉死：
 *   · 首次扫描不该满屏"新设备"
 *   · IP 变了但 MAC 没变（DHCP 换地址）不该算新设备
 *   · 没 MAC 的设备只能按 IP 认，会误判，这是已知取舍
 */
public class ProbeHistoryTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static LanProbe.Host h(String ip, String mac, String vendor) {
        return new LanProbe.Host(ip, mac, vendor, false, false);
    }

    private static ProbeHistory.Snapshot snap(LanProbe.Host... hosts) {
        Map<String, ProbeHistory.Dev> m = new LinkedHashMap<>();
        for (LanProbe.Host h : hosts) {
            ProbeHistory.Dev d = new ProbeHistory.Dev(h.ip, h.mac, h.vendor);
            m.put(d.key(), d);
        }
        return new ProbeHistory.Snapshot(System.currentTimeMillis(), m);
    }

    public static void main(String[] args) {
        System.out.println("== 首次扫描：不报警 ==");
        List<LanProbe.Host> now1 = new ArrayList<>();
        now1.add(h("192.168.1.1", "AA:BB:CC:DD:EE:01", "华为"));
        now1.add(h("192.168.1.2", "AA:BB:CC:DD:EE:02", "小米"));
        ProbeHistory.Diff d1 = ProbeHistory.diff(null, now1);
        check("首次 hasBaseline=false", !d1.hasBaseline);
        check("首次无新设备（不误报）", d1.added.isEmpty());
        check("首次全部算已知", d1.kept.size() == 2);
        check("首次无离开", d1.removed.isEmpty());

        System.out.println("== 同样两台：无变化 ==");
        ProbeHistory.Snapshot s1 = snap(now1.toArray(new LanProbe.Host[0]));
        ProbeHistory.Diff d2 = ProbeHistory.diff(s1, now1);
        check("有基线", d2.hasBaseline);
        check("无新设备", d2.added.isEmpty());
        check("全部已知", d2.kept.size() == 2);

        System.out.println("== 多了一台：报新设备 ==");
        List<LanProbe.Host> now2 = new ArrayList<>(now1);
        now2.add(h("192.168.1.99", "AA:BB:CC:DD:EE:99", "腾达"));
        ProbeHistory.Diff d3 = ProbeHistory.diff(s1, now2);
        check("识别 1 台新设备", d3.added.size() == 1);
        check("新设备 IP 对", "192.168.1.99".equals(d3.added.get(0).ip));
        check("已知仍 2 台", d3.kept.size() == 2);

        System.out.println("== 少了一台：报离开 ==");
        List<LanProbe.Host> now3 = new ArrayList<>();
        now3.add(h("192.168.1.1", "AA:BB:CC:DD:EE:01", "华为"));
        ProbeHistory.Diff d4 = ProbeHistory.diff(s1, now3);
        check("识别 1 台离开", d4.removed.size() == 1);
        check("离开的是 .2", "192.168.1.2".equals(d4.removed.get(0).ip));
        check("无新设备", d4.added.isEmpty());

        System.out.println("== 关键：IP 变了但 MAC 没变 → 不算新设备 ==");
        List<LanProbe.Host> moved = new ArrayList<>();
        moved.add(h("192.168.1.1", "AA:BB:CC:DD:EE:01", "华为"));
        // .2 换成了 .7，但 MAC 相同（DHCP 重发）
        moved.add(h("192.168.1.7", "AA:BB:CC:DD:EE:02", "小米"));
        ProbeHistory.Diff d5 = ProbeHistory.diff(s1, moved);
        check("MAC 相同 → 不算新设备", d5.added.isEmpty());
        check("也不算离开", d5.removed.isEmpty());
        check("仍算 2 台已知", d5.kept.size() == 2);

        System.out.println("== 无 MAC 设备：只能按 IP 认（已知取舍）==");
        List<LanProbe.Host> noMac1 = new ArrayList<>();
        noMac1.add(h("192.168.1.1", "", "未知"));
        ProbeHistory.Snapshot s2 = snap(noMac1.toArray(new LanProbe.Host[0]));
        List<LanProbe.Host> noMac2 = new ArrayList<>();
        noMac2.add(h("192.168.1.1", "", "未知"));
        check("无 MAC 同 IP → 不算新", ProbeHistory.diff(s2, noMac2).added.isEmpty());
        List<LanProbe.Host> noMac3 = new ArrayList<>();
        noMac3.add(h("192.168.1.5", "", "未知"));
        check("无 MAC 换 IP → 会误判为新（已知取舍）",
              ProbeHistory.diff(s2, noMac3).added.size() == 1);

        System.out.println("== MAC 大小写不敏感 ==");
        List<LanProbe.Host> lower = new ArrayList<>();
        lower.add(h("192.168.1.1", "aa:bb:cc:dd:ee:01", "华为"));
        check("小写 MAC 也匹配", ProbeHistory.diff(s1, lower).added.isEmpty());

        System.out.println("== 空列表 ==");
        ProbeHistory.Diff d6 = ProbeHistory.diff(s1, new ArrayList<>());
        check("当前为空 → 全部离开", d6.removed.size() == 2);
        check("无新设备", d6.added.isEmpty());

        System.out.println("\n=== ProbeHistory 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}