package io.github.wlmosv_png.wpaforge.wps;

/**
 * WPS IE 解析器专项（纯逻辑，边界 + 畸形数据）。
 *
 * <p>全部断言调用生产逻辑 {@link WpsIeParser}，测试侧不复制解析实现。
 */
public final class WpsIeParserTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /** 组装一个 WPS vendor IE 的十六进制串。 */
    private static String ie(int... attrBytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("0050f204");                       // OUI + type
        for (int b : attrBytes) sb.append(String.format("%02x", b));
        int len = sb.length() / 2;
        return String.format("dd%02x", len) + sb;
    }

    /** 一条属性：type(2) len(2) value... */
    private static int[] attr(int type, int... val) {
        int[] out = new int[4 + val.length];
        out[0] = (type >> 8) & 0xFF;
        out[1] = type & 0xFF;
        out[2] = (val.length >> 8) & 0xFF;
        out[3] = val.length & 0xFF;
        System.arraycopy(val, 0, out, 4, val.length);
        return out;
    }

    private static int[] cat(int[]... parts) {
        int n = 0;
        for (int[] p : parts) n += p.length;
        int[] out = new int[n];
        int k = 0;
        for (int[] p : parts) { System.arraycopy(p, 0, out, k, p.length); k += p.length; }
        return out;
    }

    public static void main(String[] a) {
        System.out.println("=== WPS IE 解析器 ===");

        // ---------------------------------------------------------- 1 正常
        info("① 完整 WPS IE：字段全部读出");
        String full = ie(cat(
                attr(WpsIeParser.ATTR_VERSION, 0x10),
                attr(WpsIeParser.ATTR_CONFIG_METHODS, 0x01, 0x00),          // KEYPAD
                attr(WpsIeParser.ATTR_DEV_PASSWORD_ID, 0x00, 0x04),         // PUSHBUTTON
                attr(WpsIeParser.ATTR_SELECTED_REGISTRAR, 0x01),
                attr(WpsIeParser.ATTR_AP_SETUP_LOCKED, 0x00),
                attr(WpsIeParser.ATTR_DEVICE_NAME, 'T', 'e', 's', 't', 'A', 'P')));
        WpsIeParser.Result r = WpsIeParser.parseIeHex(full);
        info("    issues=" + (r.issueSummary().isEmpty() ? "(无)" : r.issueSummary()));
        ok("找到 WPS IE", r.observed(), "");
        ok("config_methods=0x0100", r.configMethods == 0x0100,
                "0x" + Integer.toHexString(r.configMethods));
        ok("声明支持 PIN（KEYPAD）", r.declaresPin(), "");
        ok("dev_password_id=PUSHBUTTON", r.devPasswordId == WpsIeParser.DEV_PW_PUSHBUTTON, "");
        ok("selected_registrar=1", r.selectedRegistrar == 1, "");
        ok("ap_setup_locked=0 → 未锁定", !r.locked(), "");
        ok("device_name=TestAP", "TestAP".equals(r.deviceName), r.deviceName);
        ok("数据完整（complete）", r.complete(), "");
        ok("无解析问题", r.issues.isEmpty(), r.issueSummary());

        // ---------------------------------------------------------- 2 没有 WPS IE
        info("② 只有非 WPS 的 vendor IE → 不算观察到 WPS，也不得当成不支持");
        WpsIeParser.Result r2 = WpsIeParser.parseIeHex("dd070050f202000100");   // WMM
        ok("未找到 WPS IE", !r2.observed(), "");
        ok("complete=false（未知）", !r2.complete(), "");
        ok("config_methods 保持 UNKNOWN", r2.configMethods == WpsIeParser.UNKNOWN, "");
        ok("declaresPin=false 但 supported 未知（不得当不支持）", !r2.declaresPin(), "");

        // ---------------------------------------------------------- 3 空 / 非法
        info("③ 空串与非法十六进制 → 明确报问题，不静默");
        ok("null → 有问题记录", !WpsIeParser.parseIeHex(null).issues.isEmpty(), "");
        ok("空串 → 有问题记录", !WpsIeParser.parseIeHex("").issues.isEmpty(), "");
        ok("奇数长度 → 有问题记录", !WpsIeParser.parseIeHex("abc").issues.isEmpty(), "");
        ok("含非 hex → 有问题记录", !WpsIeParser.parseIeHex("zzzz").issues.isEmpty(), "");

        // ---------------------------------------------------------- 4 截断
        info("④ IE 声明长度超出数据 → 截断，不伪造完整");
        WpsIeParser.Result r4 = WpsIeParser.parseIeHex("dd20" + "0050f204" + "1008000201");
        ok("标记截断", r4.truncated, "");
        ok("有截断类问题记录", !r4.issues.isEmpty(), r4.issueSummary());
        ok("complete=false", !r4.complete(), "");

        info("⑤ 属性声明长度超出剩余 → 取值截断并记录");
        // 手工构造：属性头声明 2 字节，实际只给 1 字节 → 截断
        WpsIeParser.Result r5 = WpsIeParser.parseIeHex(
                "dd09" + "0050f204" + "1008000200");
        ok("标记截断", r5.truncated, "");
        ok("config_methods 保持 UNKNOWN（长度不符不采信）",
                r5.configMethods == WpsIeParser.UNKNOWN, "");
        ok("complete=false", !r5.complete(), "");
        ok("问题可读（含属性 ID 0x1008）", r5.issueSummary().contains("1008"), r5.issueSummary());

        // ---------------------------------------------------------- 6 重复属性
        info("⑥ 重复 config_methods → 保留首个并记录问题");
        WpsIeParser.Result r6 = WpsIeParser.parseIeHex(ie(cat(
                attr(WpsIeParser.ATTR_CONFIG_METHODS, 0x01, 0x00),
                attr(WpsIeParser.ATTR_CONFIG_METHODS, 0x00, 0x80))));
        ok("取首个 0x0100", r6.configMethods == 0x0100, "0x" + Integer.toHexString(r6.configMethods));
        ok("记录了重复问题", r6.issueSummary().contains("重复"), r6.issueSummary());

        info("⑦ 重复 WPS vendor IE → 记录可疑");
        WpsIeParser.Result r7 = WpsIeParser.parseIeHex(full + full);
        ok("计数 > 1", r7.wpsIeCount > 1, "" + r7.wpsIeCount);
        ok("记录了重复 IE 问题", r7.issueSummary().contains("重复"), r7.issueSummary());

        // ---------------------------------------------------------- 8 锁定
        info("⑧ ap_setup_locked=1 → locked()");
        WpsIeParser.Result r8 = WpsIeParser.parseIeHex(ie(cat(
                attr(WpsIeParser.ATTR_CONFIG_METHODS, 0x01, 0x00),
                attr(WpsIeParser.ATTR_AP_SETUP_LOCKED, 0x01))));
        ok("locked=true", r8.locked(), "");

        // ---------------------------------------------------------- 9 未知属性
        info("⑨ 未知属性 → 计数，不影响已知字段");
        WpsIeParser.Result r9 = WpsIeParser.parseIeHex(ie(cat(
                attr(WpsIeParser.ATTR_CONFIG_METHODS, 0x00, 0x04),      // LABEL
                attr(0x1099, 1, 2, 3, 4))));                            // 未知类型
        ok("config_methods=0x0004", r9.configMethods == 0x0004, "");
        ok("declaresPin（LABEL 也算）", r9.declaresPin(), "");
        ok("未知属性计数=1", r9.unknownAttrCount == 1, "" + r9.unknownAttrCount);

        // ---------------------------------------------------------- 10 尾部残留
        info("⑩ 尾部残留不足一个 TLV → 记录，不静默");
        WpsIeParser.Result r10 = WpsIeParser.parseIeHex(ie(cat(
                attr(WpsIeParser.ATTR_CONFIG_METHODS, 0x01, 0x00), new int[]{0xAB, 0xCD})));
        ok("记录尾部残留", r10.issueSummary().contains("残留"), r10.issueSummary());

        // ---------------------------------------------------------- 11 直接解析属性区
        info("⑪ parseWpsAttributes 直入口");
        byte[] wps = WpsIeParser.hexToBytes("10080002" + "0080");   // PUSHBUTTON
        WpsIeParser.Result r11 = WpsIeParser.parseWpsAttributes(wps);
        ok("declaresPbc", r11.declaresPbc(), "");
        ok("不声明 PIN", !r11.declaresPin(), "");
        ok("observed=true", r11.observed(), "");

        System.out.println();
        System.out.println("=== WpsIeParserTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
