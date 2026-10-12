package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.scan.OuiLookup;

/**
 * OUI 厂商识别的纯逻辑测试。
 *
 * 表数据在 assets 里（运行时才算），这里只测**不依赖 assets 的部分**：
 * BSSID 归一化、前缀提取、随机化判定。
 * 这三块是最容易出错的：写错一位就可能把「随机 MAC」判成「厂商未收录」，
 * 或者把带点/带横线的写法当成不同地址。
 */
public class OuiTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) {
        System.out.println("== 随机化 MAC 判定（首字节 bit1）==");
        // 全局唯一地址：首字节 bit1 = 0
        check("28:93:7D:... 不是随机",
                !OuiLookup.isRandomized("28:93:7D:41:CC:39"));
        check("5C:DE:34:... 不是随机",
                !OuiLookup.isRandomized("5C:DE:34:26:2E:F2"));
        check("CC:2D:21:... 不是随机",
                !OuiLookup.isRandomized("CC:2D:21:B0:88:D4"));

        // 本地管理位为 1 → 随机化地址
        check("6A:93:7D:... 是随机（0x6A & 0x02）",
                OuiLookup.isRandomized("6A:93:7D:41:CC:3B"));
        check("82:93:7D:... 是随机",
                OuiLookup.isRandomized("82:93:7D:43:58:91"));
        check("8A:93:7D:... 是随机",
                OuiLookup.isRandomized("8A:93:7D:45:CF:07"));
        check("92:93:7D:... 是随机",
                OuiLookup.isRandomized("92:93:7D:45:DC:EF"));
        check("36:AB:95:... 是随机",
                OuiLookup.isRandomized("36:AB:95:3A:60:C5"));
        check("5E:DE:34:... 是随机",
                OuiLookup.isRandomized("5E:DE:34:46:2E:F1"));

        System.out.println("== 各种写法都要能解析（不崩、判定一致）==");
        check("冒号大写", !OuiLookup.isRandomized("28:93:7D:41:CC:39"));
        check("冒号小写", !OuiLookup.isRandomized("28:93:7d:41:cc:39"));
        check("连字符", !OuiLookup.isRandomized("28-93-7D-41-CC-39"));
        check("无分隔", !OuiLookup.isRandomized("28937D41CC39"));
        check("小写无分隔", !OuiLookup.isRandomized("28937d41cc39"));

        System.out.println("== 异常输入不崩 ==");
        check("null", !OuiLookup.isRandomized(null));
        check("空串", !OuiLookup.isRandomized(""));
        check("太短", !OuiLookup.isRandomized("28"));
        check("非十六进制", !OuiLookup.isRandomized("zz:zz:zz:zz:zz:zz"));
        check("混杂字符", !OuiLookup.isRandomized("28:93:7D!!"));

        System.out.println("== 全 0 / 全 F 的边界 ==");
        check("00:00:00 → bit1=0，非随机",
                !OuiLookup.isRandomized("00:00:00:00:00:00"));
        check("02:00:00 → bit1=1，随机",
                OuiLookup.isRandomized("02:00:00:00:00:00"));
        check("FF:FF:FF → bit1=1，随机",
                OuiLookup.isRandomized("FF:FF:FF:FF:FF:FF"));

        System.out.println("\n=== OUI 解析测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
