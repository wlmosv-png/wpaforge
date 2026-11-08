package io.github.wlmosv_png.wpaforge.wps;

/**
 * 第二阶段专项：**WSC 能力位**。
 *
 * <h3>要挡住的真实事故（第二阶段审计 P1-3）</h3>
 * {@code CM_LABEL} 原来写成 {@code 0x0001} —— 按 WSC 规范（wpa_supplicant 的
 * wps_defs.h）那是 <b>USBA</b> 的位，Label 是 {@code 0x0004}。组合逻辑
 * （KEYPAD|LABEL）本来是对的，错的是常量值。后果：
 * {@code config_methods = 0x0004}（AP 上贴着印好的 PIN）会被判成
 * {@code NO_PIN} —— 界面写「该 AP 未声明 PIN 输入能力」，体检还会直接跳过它。
 * 用户于是以为打不动，其实是能力判定错了。
 *
 * <p>全部断言调用生产函数（{@link WpsHelper#pinCapable}、
 * {@link WpsHelper#parseBssCap}、{@link WpsController#parseInfo}）。
 */
public final class WpsCapabilityBitsTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /** 造一段 supplicant 的 BSS 回复（每行一个 key=value）。 */
    private static String bss(String methods, String ie) {
        StringBuilder sb = new StringBuilder();
        sb.append("id=3\n");
        sb.append("bssid=a4:2b:b0:11:22:33\n");
        sb.append("ssid=TEST-AP\n");
        sb.append("wps_device_name=TestDevice\n");
        sb.append("wps_config_methods=").append(methods).append('\n');
        if (ie != null) sb.append("ie=").append(ie).append('\n');
        return sb.toString();
    }

    public static void main(String[] a) {
        System.out.println("=== WSC 能力位专项 ===");

        info("① 位常量本身（防回退成 0x0001）");
        ok("CM_LABEL == 0x0004", WpsHelper.CM_LABEL == 0x0004, "" + WpsHelper.CM_LABEL);
        ok("CM_KEYPAD == 0x0100", WpsHelper.CM_KEYPAD == 0x0100, "" + WpsHelper.CM_KEYPAD);
        ok("CM_PUSHBUTTON == 0x0080", WpsHelper.CM_PUSHBUTTON == 0x0080,
                "" + WpsHelper.CM_PUSHBUTTON);
        ok("CM_LABEL != 0x0001（USBA 的位）", WpsHelper.CM_LABEL != 0x0001, "");

        info("② pinCapable 判据");
        ok("KEYPAD(0x0100) → 可打", WpsHelper.pinCapable(0x0100), "");
        ok("LABEL(0x0004) → 可打", WpsHelper.pinCapable(0x0004), "");
        ok("现场值 0x210c（含 KEYPAD）→ 可打", WpsHelper.pinCapable(0x210c), "");
        ok("0x0380（按键+虚拟按键）→ 可打", WpsHelper.pinCapable(0x0380), "");
        ok("USBA(0x0001) → 不可打（修复前会误判可打）", !WpsHelper.pinCapable(0x0001), "");
        ok("0x2008（自带屏幕显示型）→ 不可打", !WpsHelper.pinCapable(0x2008), "");
        ok("ETHERNET(0x0002) → 不可打", !WpsHelper.pinCapable(0x0002), "");
        ok("纯按键(0x0080) → 不是 PIN 能力", !WpsHelper.pinCapable(0x0080), "");

        info("③ parseBssCap → verdict");
        String[] cap04 = WpsHelper.parseBssCap(bss("0x0004", null));
        ok("0x0004（贴纸 PIN）→ PIN_OK", cap04 != null && "PIN_OK".equals(cap04[0]),
                cap04 == null ? "null" : cap04[0] + "");
        String[] cap00 = WpsHelper.parseBssCap(bss("0x0001", null));
        ok("0x0001（USBA）→ NO_PIN", cap00 != null && "NO_PIN".equals(cap00[0]),
                cap00 == null ? "null" : cap00[0] + "");
        String[] cap20 = WpsHelper.parseBssCap(bss("0x2008", null));
        ok("0x2008 → NO_PIN", cap20 != null && "NO_PIN".equals(cap20[0]),
                cap20 == null ? "null" : cap20[0] + "");
        String[] cap21 = WpsHelper.parseBssCap(bss("0x210c", null));
        ok("0x210c（现场那台）→ PIN_OK", cap21 != null && "PIN_OK".equals(cap21[0]),
                cap21 == null ? "null" : cap21[0] + "");
        ok("methods 原文保留", cap21 != null && "0x210c".equals(cap21[1]),
                cap21 == null ? "null" : cap21[1] + "");
        ok("device_name 解析", cap21 != null && "TestDevice".equals(cap21[2]),
                cap21 == null ? "null" : cap21[2] + "");
        ok("ssid 解析", cap21 != null && "TEST-AP".equals(cap21[3]),
                cap21 == null ? "null" : cap21[3] + "");

        info("④ AP Setup Locked（WSC 属性 0x1057）");
        // IE: id=0xdd len=0x09 data=[OUI 0050f2][type 04][attr 1057 len 0001 = 01]
        String lockedIe = "dd090050f2041057000101";
        ok("带 0x1057=1 → LOCKED",
                "LOCKED".equals(WpsHelper.parseBssCap(bss("0x210c", lockedIe))[0]),
                WpsHelper.parseBssCap(bss("0x210c", lockedIe))[0] + "");
        ok("apSetupLocked 直接判据", WpsHelper.apSetupLocked(lockedIe), "");
        ok("无 IE → 不判锁定", !WpsHelper.apSetupLocked(""), "");

        info("⑤ FAIL / 空回复不得造出能力");
        ok("FAIL → null", WpsHelper.parseBssCap("FAIL") == null, "");
        ok("空 → null", WpsHelper.parseBssCap("") == null, "");
        ok("无 methods 字段 → null",
                WpsHelper.parseBssCap("id=1\nbssid=aa:bb:cc:dd:ee:ff\n") == null, "");

        info("⑥ App 侧 Info 与 Target 语义");
        WpsController.Info inf = WpsController.parseInfo(
                "WPS_METHODS=0x0004\nWPS_PIN_CAPABLE=1\nWPS_VERDICT=PIN_OK\nWPS_OK=1\n");
        ok("PIN_OK → hopeless=false", !inf.hopeless(), inf.verdict);
        WpsController.Target t = new WpsController.Target();
        t.wpsVerdict = "PIN_OK";
        ok("PIN_OK 目标可打", t.pinWorthTrying(), t.wpsVerdict);
        t.wpsVerdict = "NO_PIN";
        ok("NO_PIN 目标不值得跑", !t.pinWorthTrying(), t.wpsVerdict);
        t.wpsVerdict = "UNKNOWN";
        ok("未知能力不阻断（运行时兜底）", t.pinWorthTrying(), t.wpsVerdict);

        System.out.println();
        System.out.println("=== WpsCapabilityBitsTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
