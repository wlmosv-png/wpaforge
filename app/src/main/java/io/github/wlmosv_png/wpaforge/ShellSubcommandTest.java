package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.ShellSubcommand;

/**
 * {@link ShellSubcommand} 的判据门禁 —— 全部用**真机实测输出**喂养。
 *
 * <h3>要钉住的事</h3>
 * 「问不到」不能变成「不存在」。历史上正是因为把前者当后者，
 * 一台安卓 13 的好机器被判定成「本机不支持（缺子命令）」。
 */
public class ShellSubcommandTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        ShellSubcommandTest t = new ShellSubcommandTest();
        t.run();
        System.out.println(t.pass + " 通过 / " + t.fail + " 失败");
        if (t.fail > 0) System.exit(1);
    }

    private void ok(String name, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private void run() {
        realDeviceOutputs();
        noArgSubcommand();
        unknownIsNotMissing();
        tokenBoundary();
    }

    /** 真机实测的两种输出形态（2026-11-20，OnePlus PJX110 / Android 15）。 */
    private void realDeviceOutputs() {
        // 存在：带空参数调用 → Invalid args for ...
        String present = "Invalid args for connect-network: java.lang.IllegalArgumentException: "
                + "Argument expected after \"connect-network\"\n";
        ok("存在 → AVAILABLE",
                ShellSubcommand.detect(present, "connect-network") == ShellSubcommand.AVAILABLE);

        // 不存在：Unknown command: ...
        String missing = "Unknown command: no-such-subcmd-xyz\n";
        ok("不存在 → MISSING",
                ShellSubcommand.detect(missing, "no-such-subcmd-xyz") == ShellSubcommand.MISSING);

        // 关键回归：Unknown 判的必须是**被问的那个名字**，
        // 不能因为输出里出现 "Unknown command:" 就把别的子命令也判成没有。
        ok("Unknown 只对同名生效",
                ShellSubcommand.detect(missing, "connect-network") != ShellSubcommand.MISSING);

        // 用真实的那句去问 connect-network，必须是 MISSING
        ok("Unknown command: connect-network → MISSING",
                ShellSubcommand.detect("Unknown command: connect-network\n", "connect-network")
                        == ShellSubcommand.MISSING);
    }

    /** 不需要参数的子命令（status）会正常执行 —— 这是 2026-11-20 补的盲区。 */
    private void noArgSubcommand() {
        // 真机实测：`cmd wifi status` 直接返回状态，既无 Invalid args 也无 Unknown command
        String realStatus = "Wifi is enabled\n"
                + "Wifi scanning is always available\n"
                + "==== ClientModeManager instance ====\n"
                + "Wifi is connected to \"211\"\n";
        ok("status 正常执行 → AVAILABLE",
                ShellSubcommand.detect(realStatus, "status") == ShellSubcommand.AVAILABLE);

        // 但仍要能识别真正不存在的
        ok("Unknown command 优先于「有输出」",
                ShellSubcommand.detect("Unknown command: status\n", "status")
                        == ShellSubcommand.MISSING);
    }

    /** 问不到 ≠ 不存在 —— 这条是整个类存在的理由。 */
    private void unknownIsNotMissing() {
        ok("空输出 → UNKNOWN", ShellSubcommand.detect("", "connect-network")
                == ShellSubcommand.UNKNOWN);
        ok("null → UNKNOWN", ShellSubcommand.detect(null, "connect-network")
                == ShellSubcommand.UNKNOWN);
        ok("无关输出 → UNKNOWN", ShellSubcommand.detect("some random text\n", "connect-network")
                == ShellSubcommand.UNKNOWN);
        // 用户截图里的真实形态：用法表被截断，只有 status / forget-network 两行，
        // 中间那行丢了。这种用法表**不能**用来判 connect-network 不存在。
        String truncated = "Wi-Fi (wifi) commands:\n"
                + "  status\n"
                + "  forget-network <networkId>\n";
        ok("截断的用法表不判 MISSING",
                ShellSubcommand.detect(truncated, "connect-network") != ShellSubcommand.MISSING);

        // 但用法表里**明确有**时，可以作为弱证据判存在
        String full = "Wi-Fi (wifi) commands:\n"
                + "  connect-network <ssid> open|owe|wpa2|wpa3|wep [<passphrase>]\n";
        ok("完整用法表可作弱证据", ShellSubcommand.usageMentions(full, "connect-network"));
        ok("用法表里没有 → usageMentions=false",
                !ShellSubcommand.usageMentions(truncated, "connect-network"));
        ok("空用法表 → usageMentions=false", !ShellSubcommand.usageMentions("", "connect-network"));
    }

    /** 整词匹配：不能把 connect-network-v2 当成 connect-network。 */
    private void tokenBoundary() {
        ok("前缀不算命中",
                !ShellSubcommand.usageMentions("connect-network-v2", "connect-network"));
        ok("后缀不算命中",
                !ShellSubcommand.usageMentions("my-connect-network", "connect-network"));
        ok("独立出现算命中",
                ShellSubcommand.usageMentions("  connect-network <ssid>", "connect-network"));
        ok("行尾算命中",
                ShellSubcommand.usageMentions("  forget-network", "forget-network"));
        // Invalid args 的边界
        ok("Invalid args 整词",
                ShellSubcommand.detect("Invalid args for status: x", "status")
                        == ShellSubcommand.AVAILABLE);
        ok("Invalid args 前缀不误判",
                ShellSubcommand.detect("Invalid args for status-x: y", "status")
                        != ShellSubcommand.AVAILABLE);
    }
}
