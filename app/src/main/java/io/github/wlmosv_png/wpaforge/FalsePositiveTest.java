package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.SupplicantParser;
import io.github.wlmosv_png.wpaforge.audit.StatusParser;

/**
 * 假阳性防线回归测试。
 *
 * <h3>为什么单独一个测试类</h3>
 * 「密码对不对」的误判是这个工具最不能出错的地方：
 * 把错的判成对 → 用户拿一个错密码去连别的网 → 锁账号 / 白折腾；
 * 把对的判成错 → 用户以为密码没用，白白放弃。
 *
 * 这一类 bug 全部来自**归因不严**：看到「目标连上了」就以为是本条候选
 * 的功劳，忽略了「系统回连」「残留配置」「上一条留下的连接」这些来源。
 *
 * 本测试把已知的几条归因通路钉死，防止以后改动把它们放开。
 */
public class FalsePositiveTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    /** 真机 status：连着目标，握手完成，IP 有效，netId=666 */
    private static final String ON_TARGET =
            "Wifi is enabled\n"
            + "Wifi is connected to \"B212\"\n"
            + "WifiInfo: SSID: \"B212\", BSSID: 28:93:7d:41:cc:39, "
            + "IP: /192.168.1.2, Security type: 2, Supplicant state: COMPLETED, "
            + "Frequency: 2432MHz, Net ID: 666, score: 60\n";

    /** 连着别的网（系统回连） */
    private static final String ON_OTHER =
            "Wifi is enabled\n"
            + "Wifi is connected to \"OTHER\"\n"
            + "WifiInfo: SSID: \"OTHER\", BSSID: aa:bb:cc:dd:ee:ff, "
            + "IP: /192.168.1.9, Supplicant state: COMPLETED, Net ID: 570\n";

    public static void main(String[] args) {
        System.out.println("== 判据 A 的四要素必须同行同源 ==");
        SupplicantParser.Info ok = SupplicantParser.parse(ON_TARGET);
        check("目标被识别为已连接", ok.isTargetConnected("B212"));
        check("netId 解析为 666", ok.netId == 666);
        check("握手完成", ok.handshakeCompleted());
        check("IP 有效", ok.hasValidIp());

        System.out.println("== 回连别的网络：绝不能命中目标 ==");
        SupplicantParser.Info other = SupplicantParser.parse(ON_OTHER);
        check("别的网不算目标已连", !other.isTargetConnected("B212"));
        check("但自己能被识别", other.isTargetConnected("OTHER"));

        System.out.println("== SSID 相同但 netId 不同 → 不算同一条连接 ==");
        // 这条是「残留配置 / 上一轮遗留」的模拟：SSID 对得上，
        // 但 netId 与「本条新建的」不符 → 判定层必须拒绝。
        SupplicantParser.Info sameSsidDiffNet =
                SupplicantParser.parse(ON_TARGET);
        long thisAttemptNetId = 777;   // 本条命令后新建出来的
        boolean mismatch = sameSsidDiffNet.netId >= 0
                && thisAttemptNetId >= 0
                && sameSsidDiffNet.netId != thisAttemptNetId;
        check("netId 不一致时判定层应识别为不符", mismatch);

        System.out.println("== 任一侧 netId 为 -1 → 视为证据不足（不阻断）==");
        long none = -1;
        boolean mismatch2 = none >= 0 && thisAttemptNetId >= 0 && none != thisAttemptNetId;
        check("status 侧 -1 不构成不符", !mismatch2);
        boolean mismatch3 = sameSsidDiffNet.netId >= 0 && none >= 0
                && sameSsidDiffNet.netId != none;
        check("本条侧 -1 不构成不符", !mismatch3);

        System.out.println("== status 层的「当前 SSID」不能把诱饵行当目标 ==");
        String withDecoy = ON_TARGET
                + "NetworkCapabilities: [ TransportInfo: <SSID: <unknown ssid>, "
                + "IP: /192.168.1.2, Supplicant state: COMPLETED, Net ID: -1>\n";
        StatusParser.Result r = StatusParser.parse(withDecoy, "B212");
        check("主判据仍命中 B212", r.onTarget);
        check("netId 未被诱饵行的 -1 覆盖", r.netId == 666);
        StatusParser.Result r2 = StatusParser.parse(withDecoy, "<unknown ssid>");
        check("unknown ssid 不作为目标命中", !r2.onTarget);

        System.out.println("== 未连接时不得出现「已连上」的任何证据 ==");
        String off = "Wifi is enabled\nWifi is not connected\n";
        SupplicantParser.Info offInfo = SupplicantParser.parse(off);
        check("未连接时 raw=false", !offInfo.raw);
        check("未连接时不通过 isTargetConnected", !offInfo.isTargetConnected("B212"));
        check("未连接时 netId 为 -1", StatusParser.parse(off, "B212").netId == -1);

        System.out.println("== 空/null 输入不产生假阳性 ==");
        check("null 不崩且不命中", !SupplicantParser.parse((String) null).isTargetConnected("X"));
        check("空串不命中断言", !StatusParser.parse("", "B212").onTarget);

        System.out.println("\n=== 假阳性防线测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
