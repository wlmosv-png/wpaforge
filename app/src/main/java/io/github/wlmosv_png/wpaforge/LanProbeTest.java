package io.github.wlmosv_png.wpaforge;

import java.util.List;

import io.github.wlmosv_png.wpaforge.scan.LanProbe;

/**
 * LanProbe 纯逻辑测试。
 *
 * 只测不依赖系统的部分：/24 网段推导、IP 校验、端口服务名。
 * 执行器（ping/nc）属于系统命令调用，留待真机冒烟。
 */
public class LanProbeTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) {
        System.out.println("== IP 校验 ==");
        check("合法 IP", "192.168.1.7".equals(LanProbe.ipOnly("192.168.1.7")));
        check("带 /24", "192.168.1.7".equals(LanProbe.ipOnly("192.168.1.7/24")));
        check("非法 IP → null", LanProbe.ipOnly("999.1.1.1") == null);
        check("非 IP → null", LanProbe.ipOnly("abc") == null);
        check("null → null", LanProbe.ipOnly(null) == null);
        check("空串 → null", LanProbe.ipOnly("") == null);

        System.out.println("== /24 网段推导 ==");
        List<String> h = LanProbe.hosts24("192.168.1.7");
        check("共 253 个（.0/.255/自己 排除）", h.size() == 253);
        check("第一个是 .1", h.get(0).equals("192.168.1.1"));
        check("不含自己 .7", !h.contains("192.168.1.7"));
        check("含 .255 吗？", !h.contains("192.168.1.255"));   // 广播地址应排除
        check("含 .254", h.contains("192.168.1.254"));
        check("含 .100", h.contains("192.168.1.100"));

        System.out.println("== 边界 IP ==");
        check("本机 .1 时", LanProbe.hosts24("192.168.1.1").size() == 253);
        check("本机 .254 时", LanProbe.hosts24("192.168.1.254").size() == 253);
        check("IP 不同段", LanProbe.hosts24("10.0.0.5").get(0).startsWith("10.0.0."));
        check("非法 IP → 空", LanProbe.hosts24("bad").isEmpty());

        System.out.println("== 端口服务名 ==");
        check("22 → ssh", "ssh".equals(LanProbe.guessService(22)));
        check("80 → http", "http".equals(LanProbe.guessService(80)));
        check("443 → https", "https".equals(LanProbe.guessService(443)));
        check("3389 → rdp", "rdp".equals(LanProbe.guessService(3389)));
        check("未知 → unknown", "unknown".equals(LanProbe.guessService(9999)));

        System.out.println("== Port 行输出 ==");
        LanProbe.Port open = new LanProbe.Port(22, true);
        check("开端口行", open.line().contains("22/tcp") && open.line().contains("open") && open.line().contains("ssh"));
        LanProbe.Port closed = new LanProbe.Port(8080, false);
        check("关端口行", closed.line().contains("closed") && closed.line().contains("http-proxy"));

        System.out.println("== Host 行输出 ==");
        LanProbe.Host gw = new LanProbe.Host("192.168.1.1", "28:93:7D:11:22:04", "华为", true, false);
        check("网关行", gw.line().contains("192.168.1.1") && gw.line().contains("华为") && gw.line().contains("(网关)"));
        LanProbe.Host self = new LanProbe.Host("192.168.1.7", "", "", false, true);
        check("本机行（无 MAC/厂商）", self.line().contains("本机") && self.line().contains("192.168.1.7"));
        check("本机 displayVendor", "本机".equals(self.displayVendor()));

        System.out.println("\n=== LanProbe 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}