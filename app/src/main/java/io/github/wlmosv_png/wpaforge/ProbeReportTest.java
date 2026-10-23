package io.github.wlmosv_png.wpaforge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.wlmosv_png.wpaforge.scan.Evidence;
import io.github.wlmosv_png.wpaforge.scan.LanProbe;
import io.github.wlmosv_png.wpaforge.scan.ProbeHistory;
import io.github.wlmosv_png.wpaforge.scan.ProbeReport;

/**
 * 自查报告生成测试。
 *
 * 报告是要拿给别人看的东西 —— 格式坏了比没有更糟。
 * 重点测：Markdown 表格转义、空数据不崩、可选字段（diff/证据）缺失时的降级。
 */
public class ProbeReportTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static LanProbe.Host h(String ip, String mac, String vendor, boolean gw, boolean self) {
        return new LanProbe.Host(ip, mac, vendor, gw, self);
    }

    public static void main(String[] args) {
        System.out.println("== 最小输入（无 diff、无证据）不崩 ==");
        List<LanProbe.Host> hosts = new ArrayList<>();
        hosts.add(h("192.168.1.1", "28:93:7D:11:22:04", "四川天邑康和", true, false));
        hosts.add(h("192.168.1.2", "86:64:BC:11:22:15", "", false, true));

        String r = ProbeReport.build("192.168.1.2", "B212", hosts, null, null, true);
        check("含标题", r.contains("# WPAForge 网络自查报告"));
        check("含 SSID", r.contains("B212"));
        check("含本机 IP", r.contains("192.168.1.2"));
        check("设备数 2", r.contains("| 在线设备 | 2 |"));
        check("端口可信", r.contains("| 端口结果 | 可信 |"));
        check("含网关厂商", r.contains("四川天邑康和"));
        check("角色标注-网关", r.contains("网关"));
        check("角色标注-本机", r.contains("本机"));
        check("无 diff 段落时不显示对比", !r.contains("与上次扫描对比"));

        System.out.println("== 端口不可信时给出警告 ==");
        String r2 = ProbeReport.build("192.168.1.2", "B212", hosts, null, null, false);
        check("标记不可信", r2.contains("不可信（已隐藏）"));
        check("含原因提示", r2.contains("代理") || r2.contains("TUN"));
        check("含重新扫描建议", r2.contains("重新扫描") || r2.contains("关闭代理"));

        System.out.println("== 空设备列表 ==");
        String r3 = ProbeReport.build("10.0.0.2", null, new ArrayList<>(), null, null, true);
        check("空列表提示", r3.contains("未发现设备"));
        check("SSID 空显示未知", r3.contains("(未知)"));

        System.out.println("== null 全传不崩 ==");
        String r4 = ProbeReport.build(null, null, null, null, null, true);
        check("null 不崩", r4.contains("# WPAForge"));
        check("null hosts 计数 0", r4.contains("| 在线设备 | 0 |"));

        System.out.println("== 对比段落 ==");
        Map<String, ProbeHistory.Dev> prevMap = new LinkedHashMap<>();
        prevMap.put("mac:AA:BB:CC:DD:EE:01",
                new ProbeHistory.Dev("192.168.1.1", "AA:BB:CC:DD:EE:01", "华为"));
        ProbeHistory.Snapshot prev = new ProbeHistory.Snapshot(System.currentTimeMillis(), prevMap);
        ProbeHistory.Diff diff = ProbeHistory.diff(prev, hosts);
        String r5 = ProbeReport.build("192.168.1.2", "B212", hosts, diff, null, true);
        check("含对比段", r5.contains("与上次扫描对比"));
        check("有新设备标", r5.contains("新出现"));
        check("含新设备小节", r5.contains("新出现的设备"));

        System.out.println("== 证据链段落 ==");
        List<Evidence.Claim> claims = new ArrayList<>();
        Evidence.Claim c = new Evidence.Claim("192.168.1.1 四川天邑康和");
        c.add("OUI 表", "28:93:7D → 四川天邑康和", true);
        c.add("ICMP", "有回应", true);
        claims.add(c);
        String r6 = ProbeReport.build("192.168.1.2", "B212", hosts, null, claims, true);
        check("含依据段", r6.contains("## 判断依据"));
        check("含可信度标签", r6.contains("[确认]"));
        check("含决定性符号", r6.contains("●"));
        check("含依据内容", r6.contains("28:93:7D"));

        System.out.println("== Markdown 表格转义 ==");
        List<LanProbe.Host> weird = new ArrayList<>();
        weird.add(h("192.168.1.9", "", "AE86|Intel", false, false));
        String r7 = ProbeReport.build("192.168.1.2", null, weird, null, null, true);
        check("竖线被转义", r7.contains("\\|"));
        check("未产生破损表格行", !r7.contains("| AE86|Intel |"));

        System.out.println("== 结尾声明 ==");
        check("含使用边界声明", r.contains("仅对自己有授权的网络使用"));

        System.out.println("\n=== 报告生成测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}