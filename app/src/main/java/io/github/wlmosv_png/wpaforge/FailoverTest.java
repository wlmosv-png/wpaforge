package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.FailoverRule;

/**
 * 回连失败判据测试。
 *
 * 这条判据决定「一条错误密码什么时候被判负」。它错在两边的代价都很高：
 * 判早了 → 正确密码被当失败跳过（假阴性，用户以为密码不对）；
 * 判晚了 → 等于没加速（每条仍等满超时）。
 * 所以每一种前置条件都要单独钉住。
 */
public class FailoverTest {

    private static int pass = 0, fail = 0;
    private static final String T = "CMCC-6339";

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static boolean f(String statusSsid, String state, boolean leftBaseline, long since) {
        return FailoverRule.looksLikeFailover(T, statusSsid, state, leftBaseline, since);
    }

    public static void main(String[] args) {
        System.out.println("== 前提条件：必须已离开原连接 ==");
        ok("没观察到离开原连接 -> 不算（哪怕其它条件都满足）",
                !f("B212", "COMPLETED", false, 5000));

        System.out.println("== 前提条件：最短等待 2.5 秒 ==");
        ok("刚过 1 秒 -> 不算（给握手留时间）",
                !f("B212", "COMPLETED", true, 1000));
        ok("2.4 秒 -> 不算", !f("B212", "COMPLETED", true, 2400));
        ok("正好 2.5 秒 -> 算（边界）", f("B212", "COMPLETED", true, FailoverRule.MIN_WAIT_MS));
        ok("2.5 秒 - 1ms -> 不算", !f("B212", "COMPLETED", true, FailoverRule.MIN_WAIT_MS - 1));
        ok("10 秒 -> 算", f("B212", "COMPLETED", true, 10000));

        System.out.println("== 目标本身不算回连 ==");
        ok("status 显示目标 -> 不算",
                !f(T, "COMPLETED", true, 5000));
        ok("目标大小写不同也不该算成别的网络（严格比较）-> 视为其它网络",
                f("cmcc-6339", "COMPLETED", true, 5000));

        System.out.println("== supplicant 状态必须是 COMPLETED ==");
        ok("ASSOCIATING -> 不算（还没连上别的网）",
                !f("B212", "ASSOCIATING", true, 5000));
        ok("4WAY_HANDSHAKE -> 不算",
                !f("B212", "4WAY_HANDSHAKE", true, 5000));
        ok("DISCONNECTED -> 不算",
                !f("B212", "DISCONNECTED", true, 5000));
        ok("state 为 null -> 不算", !f("B212", null, true, 5000));
        ok("state 小写 completed -> 算（大小写不敏感）",
                f("B212", "completed", true, 5000));

        System.out.println("== 异常输入不崩 ==");
        ok("statusSsid 为 null -> 不算", !f(null, "COMPLETED", true, 5000));
        ok("statusSsid 为空串 -> 不算", !f("", "COMPLETED", true, 5000));
        ok("target 为 null -> 不算",
                !FailoverRule.looksLikeFailover(null, "B212", "COMPLETED", true, 5000));

        System.out.println();
        System.out.println("=== 回连判据测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
