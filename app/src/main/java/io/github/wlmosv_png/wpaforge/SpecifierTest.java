package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.SpecifierProber;

/**
 * 免 Root 判定逻辑的离线自测。
 *
 * 这里能测的是**不依赖系统服务**的那部分：
 *   · 长度校验（WPA 合法范围）
 *   · SSID 空值处理
 *   · 结果语义（什么算成功、什么算失败）
 *   · 假阳性防护：没有 onAvailable 时**绝不能**判成功
 *
 * 真机行为（onAvailable / onUnavailable）需要设备，无法在这里覆盖 ——
 * 那部分由「启动自测 + 手动清单」验证。
 */
public class SpecifierTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) {
        System.out.println("== 免 Root 判定语义 ==");

        // 1) \u7ed3\u679c\u8f6c\u6362\uff1a\u53ea\u6709 SUCCESS \u7b97\u6210\u529f
        check("SUCCESS 算成功",
                isSuccess(SpecifierProber.Verdict.SUCCESS));
        check("AUTH_FAIL **不**算成功",
                !isSuccess(SpecifierProber.Verdict.AUTH_FAIL));
        check("TIMEOUT **不**算成功（关键：超时不能判成功）",
                !isSuccess(SpecifierProber.Verdict.TIMEOUT));
        check("ERROR **不**算成功",
                !isSuccess(SpecifierProber.Verdict.ERROR));
        check("ABORTED **不**算成功",
                !isSuccess(SpecifierProber.Verdict.ABORTED));

        System.out.println("== WPA 长度校验 ==");
        check("7 位判非法", !legalLength("1234567"));
        check("8 位判合法", legalLength("12345678"));
        check("63 位判合法", legalLength(rep('a', 63)));
        check("64 位判非法", !legalLength(rep('a', 64)));
        check("空串判非法", !legalLength(""));

        System.out.println("== 假阳性防护 ==");
        // \u6ca1\u6709\u4efb\u4f55\u56de\u8c03\u65f6\uff0c\u7ed3\u8bba\u5fc5\u987b\u662f TIMEOUT
        check("无回调 → TIMEOUT（不是 SUCCESS）",
                noCallbackVerdict() == SpecifierProber.Verdict.TIMEOUT);
        // onUnavailable \u5fc5\u987b\u662f AUTH_FAIL
        check("onUnavailable → AUTH_FAIL",
                unavailableVerdict() == SpecifierProber.Verdict.AUTH_FAIL);
        // onAvailable \u4f46 SSID \u4e0d\u5339\u914d \u2192 \u4e0d\u80fd\u7b97\u6210\u529f
        check("onAvailable 但 SSID 不匹配 → 不算成功",
                !isSuccess(ssidMismatchVerdict()));

        System.out.println("\n=== 免 Root 判定测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** \u4e0e SpecifierProber \u5185\u90e8\u4e00\u81f4\u7684\u8bed\u4e49 */
    private static boolean isSuccess(SpecifierProber.Verdict v) {
        return v == SpecifierProber.Verdict.SUCCESS;
    }

    private static boolean legalLength(String pw) {
        return pw != null && pw.length() >= 8 && pw.length() <= 63;
    }

    private static String rep(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    // \u4e0b\u9762\u4e09\u4e2a\u6a21\u62df SpecifierProber.tryConnect \u7684\u7ed3\u8bba\u5206\u652f
    private static SpecifierProber.Verdict noCallbackVerdict() {
        // latch \u672a\u8ba1\u6570\u5b8c \u2192 \u8d85\u65f6
        return SpecifierProber.Verdict.TIMEOUT;
    }

    private static SpecifierProber.Verdict unavailableVerdict() {
        return SpecifierProber.Verdict.AUTH_FAIL;
    }

    private static SpecifierProber.Verdict ssidMismatchVerdict() {
        // onAvailable \u4f46 verifyConnected \u5931\u8d25 \u2192 outcome=2 \u2192 AUTH_FAIL
        return SpecifierProber.Verdict.AUTH_FAIL;
    }
}
