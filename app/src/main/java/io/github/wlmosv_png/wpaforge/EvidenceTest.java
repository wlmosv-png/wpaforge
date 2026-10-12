package io.github.wlmosv_png.wpaforge;

import java.util.ArrayList;
import java.util.List;

import io.github.wlmosv_png.wpaforge.scan.Evidence;

/**
 * 证据链测试。
 *
 * 核心规则必须钉死：**没有决定性证据的结论不能展示成事实**。
 * 这是整个证据链存在的意义 —— 如果随便什么都能标「确认」，
 * 那这层就是装饰品。
 */
public class EvidenceTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) {
        System.out.println("== 结论与依据 ==");
        Evidence.Claim c = new Evidence.Claim("192.168.1.1 华为");
        c.add("OUI 表", "28:93:7D → 华为", true);
        c.add("ICMP", "192.168.1.1 对 ping 有回应", true);
        check("statement 保留", "192.168.1.1 华为".equals(c.statement));
        check("依据 2 条", c.items.size() == 2);
        check("决定性 2 条", c.decisiveCount() == 2);
        check("supported = true", c.supported());

        System.out.println("== 无可信依据 → 标「推测」==");
        Evidence.Claim weak = new Evidence.Claim("某台设备");
        weak.add("猜测", "看起来像路由器", false);
        check("无决定性证据", !weak.supported());
        check("标签是「推测」", "推测".equals(weak.confidenceLabel()));

        System.out.println("== 1 条决定性 → 「判定」；2 条 → 「确认」==");
        Evidence.Claim one = new Evidence.Claim("x");
        one.add("ICMP", "有回应", true);
        check("1 条 -> 判定", "判定".equals(one.confidenceLabel()));

        Evidence.Claim two = new Evidence.Claim("y");
        two.add("ICMP", "有回应", true);
        two.add("ARP", "有 MAC", true);
        check("2 条 -> 确认", "确认".equals(two.confidenceLabel()));

        System.out.println("== 快捷工厂 ==");
        check("OUI 命中", Evidence.ouiHit("28:93:7D", "华为").decisive);
        check("OUI 未收录是非决定性",
              !Evidence.ouiMissing("AA:BB:CC").decisive);
        check("随机化 MAC 是决定性",
              Evidence.randomizedMac("86:64:BC").decisive);
        check("TCP open 是决定性",
              Evidence.tcpOpen("1.2.3.4", 80).decisive);
        check("canary 失败是决定性",
              Evidence.canaryFailed("203.0.113.99:80").decisive);

        System.out.println("== 空结论不崩 ==");
        Evidence.Claim e = new Evidence.Claim(null);
        check("null statement -> 空串", "".equals(e.statement));
        check("空 claim supported=false", !e.supported());
        check("空 claim 标签是推测", "推测".equals(e.confidenceLabel()));

        System.out.println("== Item 行格式 ==");
        String line = Evidence.ouiHit("28:93:7D", "华为").line();
        check("决定性用 ●", line.startsWith("● "));
        String line2 = Evidence.ouiMissing("AA:BB:CC").line();
        check("非决定性用 ○", line2.startsWith("○ "));

        System.out.println("== render 输出 ==");
        List<Evidence.Claim> claims = new ArrayList<>();
        claims.add(c);
        claims.add(weak);
        String text = Evidence.render(claims);
        check("含第一条结论", text.contains("192.168.1.1 华为"));
        check("含可信度标签", text.contains("[确认]"));
        check("含第二条的推测标", text.contains("[推测]"));
        check("依据行缩进", text.contains("    ●") || text.contains("    ○"));

        System.out.println("== add(Item) 重载 ==");
        Evidence.Claim c2 = new Evidence.Claim("z");
        c2.add(Evidence.tcpOpen("1.2.3.4", 22));
        check("add(Item) 生效", c2.items.size() == 1);
        check("add(null) 安全", c2.add((Evidence.Item) null).items.size() == 1);

        System.out.println("\n=== 证据链测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}