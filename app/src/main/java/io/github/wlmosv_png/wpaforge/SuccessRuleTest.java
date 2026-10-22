package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.SuccessRule;

/**
 * 成功判定测试 —— 两个方向都要钉死：
 * ①**不能误判**：条件不全绝不能判成功；②**不能漏判**：条件全了就必须判成功，
 * 尤其是 netId 对不上时（历史上就是这里漏判过）。
 */
public class SuccessRuleTest {

    private static int pass = 0, fail = 0;
    private static final String T = "2.4G-3";

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) {
        System.out.println("== 防漏判：三条全满足就必须判成功 ==");
        ok("目标 + COMPLETED + 有效 IP",
                SuccessRule.isSuccess(T, T, "COMPLETED", "192.168.1.5"));
        ok("状态小写 completed 也算",
                SuccessRule.isSuccess(T, T, "completed", "192.168.1.5"));
        ok("netId 对不上不影响判成功（关键：历史上这里漏判过）",
                SuccessRule.isSuccess(T, T, "COMPLETED", "192.168.1.5"));
        ok("证据标注：netId 吻合时更强",
                "supplicant+netId".equals(SuccessRule.evidence(7, 7)));
        ok("证据标注：netId 拿不到时降级但不否决",
                "supplicant".equals(SuccessRule.evidence(-1, 7)));
        ok("证据标注：netId 不符时也只算普通证据",
                "supplicant".equals(SuccessRule.evidence(8, 7)));

        System.out.println("== 防误判：少一条都不行 ==");
        ok("SSID 不同 -> 不算成功", !SuccessRule.isSuccess(T, "B217", "COMPLETED", "192.168.1.5"));
        ok("SSID 是别的网络 -> 不算成功（假阳性主路径）",
                !SuccessRule.isSuccess(T, "B212", "COMPLETED", "10.0.0.2"));
        ok("状态不是 COMPLETED -> 不算成功",
                !SuccessRule.isSuccess(T, T, "ASSOCIATING", "192.168.1.5"));
        ok("状态是 4WAY_HANDSHAKE -> 不算成功",
                !SuccessRule.isSuccess(T, T, "4WAY_HANDSHAKE", "192.168.1.5"));
        ok("状态为 null -> 不算成功", !SuccessRule.isSuccess(T, T, null, "192.168.1.5"));
        ok("IP 是 0.0.0.0 -> 不算成功（还没拿到地址）",
                !SuccessRule.isSuccess(T, T, "COMPLETED", "0.0.0.0"));
        ok("IP 为空 -> 不算成功", !SuccessRule.isSuccess(T, T, "COMPLETED", ""));
        ok("IP 为 null -> 不算成功", !SuccessRule.isSuccess(T, T, "COMPLETED", null));
        ok("statusSsid 为 null -> 不算成功", !SuccessRule.isSuccess(T, null, "COMPLETED", "192.168.1.5"));
        ok("目标 SSID 为空 -> 不算成功", !SuccessRule.isSuccess("", T, "COMPLETED", "192.168.1.5"));

        System.out.println();
        System.out.println("=== 成功判定测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
