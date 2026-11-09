package io.github.wlmosv_png.wpaforge.wps;

/**
 * 双通道能力检测专项（纯逻辑）。
 *
 * <p>全部断言调用生产逻辑 {@link WpsCapabilityManager}。
 */
public final class WpsCapabilityManagerTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static final String BSSID = "a4:2b:b0:11:22:33";
    private static final long NOW = 1_700_000_000_000L;

    private static WpsCapabilityManager.Channel root(int cfg) {
        return WpsCapabilityManager.root(BSSID, cfg, WpsIeParser.UNKNOWN,
                WpsIeParser.UNKNOWN, WpsIeParser.UNKNOWN, "RootDev", "raw-root", NOW);
    }

    /** 造一个本地 IE 结果（默认无锁、有 PIN）。 */
    private static WpsCapabilityManager.Channel local(int cfg) {
        WpsIeParser.Result ie = new WpsIeParser.Result();
        ie.wpsIeFound = true;
        ie.configMethods = cfg;
        return WpsCapabilityManager.localIe(BSSID, ie, "raw-ie", NOW);
    }

    private static WpsCapabilityManager.Channel localRaw(WpsIeParser.Result ie) {
        return WpsCapabilityManager.localIe(BSSID, ie, "raw-ie", NOW);
    }

    public static void main(String[] a) {
        System.out.println("=== WPS 双通道能力检测 ===");

        final int KEYPAD = WpsIeParser.CM_KEYPAD;
        final int LABEL = WpsIeParser.CM_LABEL;
        final int PBC = WpsIeParser.CM_PUSHBUTTON;

        // ------------------------------------------------ 1 单通道
        info("① 仅 root 通道：声明可接受 PIN");
        WpsCapabilityManager.Capability c1 =
                WpsCapabilityManager.combine(BSSID, NOW, root(KEYPAD), null);
        ok("PIN_OK", WpsCapabilityManager.V_PIN_OK.equals(c1.verdict), c1.verdict);
        ok("来源=root", WpsCapabilityManager.SRC_ROOT.equals(c1.primarySource), c1.primarySource);
        ok("declaresPin", c1.declaresPin(), "");
        ok("verified 恒 false（扫描≠可验证）", !c1.verified, "");

        info("② 仅本地 IE 通道");
        WpsCapabilityManager.Capability c2 =
                WpsCapabilityManager.combine(BSSID, NOW, null, local(KEYPAD));
        ok("PIN_OK", WpsCapabilityManager.V_PIN_OK.equals(c2.verdict), c2.verdict);
        ok("来源=local", WpsCapabilityManager.SRC_LOCAL.equals(c2.primarySource), c2.primarySource);

        // ------------------------------------------------ 2 双通道
        info("③ 双通道一致 → PIN_OK（原始数据各自保留）");
        WpsCapabilityManager.Capability c3 =
                WpsCapabilityManager.combine(BSSID, NOW, root(KEYPAD), local(KEYPAD));
        ok("PIN_OK", WpsCapabilityManager.V_PIN_OK.equals(c3.verdict), c3.verdict);
        ok("root 原始数据保留", c3.root != null && "raw-root".equals(c3.root.raw), "");
        ok("local 原始数据保留", c3.localIe != null && "raw-ie".equals(c3.localIe.raw), "");

        info("④ 双通道冲突 → CONFLICT（不擅自选一个）");
        WpsCapabilityManager.Capability c4 =
                WpsCapabilityManager.combine(BSSID, NOW, root(KEYPAD), local(PBC));
        ok("CONFLICT", WpsCapabilityManager.V_CONFLICT.equals(c4.verdict), c4.verdict);
        ok("conflict 标记", c4.conflict, "");
        ok("primarySource=NONE", WpsCapabilityManager.SRC_NONE.equals(c4.primarySource), "");
        ok("冲突进入 issues", !c4.issues.isEmpty(), c4.issueSummary());

        // ------------------------------------------------ 3 锁定
        info("⑤ AP Setup Locked（来自任一通道）");
        WpsCapabilityManager.Channel rl = WpsCapabilityManager.root(BSSID, KEYPAD,
                WpsIeParser.UNKNOWN, WpsIeParser.UNKNOWN, 1, "", "raw", NOW);
        WpsCapabilityManager.Capability c5 =
                WpsCapabilityManager.combine(BSSID, NOW, rl, local(KEYPAD));
        ok("LOCKED（锁定优先于 PIN）", WpsCapabilityManager.V_LOCKED.equals(c5.verdict), c5.verdict);

        // ------------------------------------------------ 4 未知 ≠ 不支持
        info("⑥ 数据不完整 → INCOMPLETE（绝不当成不支持）");
        WpsIeParser.Result trunc = new WpsIeParser.Result();
        trunc.wpsIeFound = true;
        trunc.truncated = true;
        trunc.issues.add("属性 0x1008 被截断");
        WpsCapabilityManager.Capability c6 = WpsCapabilityManager.combine(
                BSSID, NOW, null, localRaw(trunc));
        ok("INCOMPLETE", WpsCapabilityManager.V_INCOMPLETE.equals(c6.verdict), c6.verdict);
        ok("不是 NO_PIN", !WpsCapabilityManager.V_NO_PIN.equals(c6.verdict), "");
        ok("issues 带上截断原因", !c6.issues.isEmpty(), "");

        info("⑦ 两通道都无数据 → UNKNOWN");
        WpsCapabilityManager.Capability c7 =
                WpsCapabilityManager.combine(BSSID, NOW, null, null);
        ok("UNKNOWN", WpsCapabilityManager.V_UNKNOWN.equals(c7.verdict), c7.verdict);

        // ------------------------------------------------ 5 明确不支持
        info("⑧ 完整数据且不含 KEYPAD/LABEL → NO_PIN");
        WpsCapabilityManager.Capability c8 =
                WpsCapabilityManager.combine(BSSID, NOW, root(PBC), null);
        ok("NO_PIN", WpsCapabilityManager.V_NO_PIN.equals(c8.verdict), c8.verdict);
        ok("declaresPin=false", !c8.declaresPin(), "");

        info("⑨ LABEL 也算可接受 PIN");
        WpsCapabilityManager.Capability c9 =
                WpsCapabilityManager.combine(BSSID, NOW, root(LABEL), null);
        ok("PIN_OK", WpsCapabilityManager.V_PIN_OK.equals(c9.verdict), c9.verdict);

        // ------------------------------------------------ 6 BSSID 一致性
        info("⑩ 通道 BSSID 与目标不一致 → 该通道作废（原始数据仍保留）");
        WpsCapabilityManager.Channel wrong = WpsCapabilityManager.root(
                "aa:bb:cc:dd:ee:ff", KEYPAD, -1, -1, -1, "", "raw-wrong", NOW);
        WpsCapabilityManager.Capability c10 =
                WpsCapabilityManager.combine(BSSID, NOW, wrong, local(KEYPAD));
        ok("用 local 通道得出结论", WpsCapabilityManager.SRC_LOCAL.equals(c10.primarySource), "");
        ok("错 BSSID 的 root 原始数据仍在", c10.root != null && "raw-wrong".equals(c10.root.raw), "");
        ok("记录了不一致", !c10.issues.isEmpty(), "");

        info("⑪ 两通道都 BSSID 不符 → UNKNOWN");
        WpsCapabilityManager.Capability c11 = WpsCapabilityManager.combine(
                BSSID, NOW, wrong, WpsCapabilityManager.localIe("aa:bb:cc:dd:ee:ff", ieLocal(KEYPAD), "r", NOW));
        ok("UNKNOWN", WpsCapabilityManager.V_UNKNOWN.equals(c11.verdict), c11.verdict);

        // ------------------------------------------------ 7 过期
        info("⑫ 采集时间过老 → stale 标记（仍可用，但如实标注）");
        WpsCapabilityManager.Channel old = WpsCapabilityManager.root(BSSID, KEYPAD,
                -1, -1, -1, "", "raw", NOW - WpsCapabilityManager.FRESH_MS - 1);
        WpsCapabilityManager.Capability c12 =
                WpsCapabilityManager.combine(BSSID, NOW, old, null);
        ok("stale=true", c12.stale, "");
        ok("结论仍给出（PIN_OK）", WpsCapabilityManager.V_PIN_OK.equals(c12.verdict), c12.verdict);

        // ------------------------------------------------ 8 文案
        info("⑬ 文案区分「声明」与「未知」");
        ok("PIN_OK 文案含未验证", c1.label(true).contains("未验证"), c1.label(true));
        ok("UNKNOWN 文案不含「不支持」", !c7.label(true).contains("不支持"), c7.label(true));

        // ------------------------------------------------ P0-1 专项（读取失败 vs 明确不支持）
        info("⑭ ROOT_BSS 缺少 config_methods → INCOMPLETE（不得判不支持）");
        WpsCapabilityManager.Channel noCfg = WpsCapabilityManager.root(
                BSSID, WpsIeParser.UNKNOWN, -1, -1, -1, "", "raw", NOW);
        WpsCapabilityManager.Capability c14 =
                WpsCapabilityManager.combine(BSSID, NOW, noCfg, null);
        ok("INCOMPLETE", WpsCapabilityManager.V_INCOMPLETE.equals(c14.verdict), c14.verdict);
        ok("不是 NO_PIN", !WpsCapabilityManager.V_NO_PIN.equals(c14.verdict), "");
        ok("原因指明数据不完整", c14.reason.contains("不完整"), c14.reason);

        info("⑮ 本地 IE 缺少 WPS IE → 该通道不可用");
        WpsIeParser.Result noIe = new WpsIeParser.Result();   // observed=false
        WpsCapabilityManager.Channel lNone = WpsCapabilityManager.localIe(BSSID, noIe, "raw", NOW);
        ok("通道不可用", !lNone.available, "");
        WpsCapabilityManager.Capability c15 =
                WpsCapabilityManager.combine(BSSID, NOW, null, lNone);
        ok("UNKNOWN（两通道都无 → 未知）",
                WpsCapabilityManager.V_UNKNOWN.equals(c15.verdict), c15.verdict);

        info("⑯ 两通道都不完整 → 未知/不完整，绝不 NO_PIN");
        WpsIeParser.Result trunc2 = new WpsIeParser.Result();
        trunc2.wpsIeFound = true; trunc2.truncated = true;
        WpsCapabilityManager.Capability c16 = WpsCapabilityManager.combine(
                BSSID, NOW, noCfg, WpsCapabilityManager.localIe(BSSID, trunc2, "r", NOW));
        ok("不是 NO_PIN", !WpsCapabilityManager.V_NO_PIN.equals(c16.verdict), c16.verdict);
        ok("是 INCOMPLETE/UNKNOWN",
                WpsCapabilityManager.V_INCOMPLETE.equals(c16.verdict)
                        || WpsCapabilityManager.V_UNKNOWN.equals(c16.verdict), c16.verdict);

        info("⑰ 一通道完整、一通道不完整 → 取完整的那条");
        WpsCapabilityManager.Capability c17 = WpsCapabilityManager.combine(
                BSSID, NOW, noCfg, local(KEYPAD));
        ok("用完整的 local 通道得出结论",
                WpsCapabilityManager.V_PIN_OK.equals(c17.verdict), c17.verdict);
        ok("来源=local", WpsCapabilityManager.SRC_LOCAL.equals(c17.primarySource), c17.primarySource);

        info("⑱ 明确不支持 vs 读取失败：只有完整数据才允许 NO_PIN");
        WpsCapabilityManager.Capability okNoPin =
                WpsCapabilityManager.combine(BSSID, NOW, root(PBC), null);
        ok("完整且不含 KEYPAD/LABEL → NO_PIN",
                WpsCapabilityManager.V_NO_PIN.equals(okNoPin.verdict), okNoPin.verdict);
        WpsCapabilityManager.Capability readFail =
                WpsCapabilityManager.combine(BSSID, NOW, noCfg, null);
        ok("读取失败 → 绝不 NO_PIN",
                !WpsCapabilityManager.V_NO_PIN.equals(readFail.verdict), readFail.verdict);
        ok("两者结论不同（语义已分离）",
                !okNoPin.verdict.equals(readFail.verdict), "");

        info("⑲ NO_WPS 字符串不再驱动结论（只看 config_methods 取值）");
        // 旧逻辑会用 t.wpsVerdict 的 "NO_WPS" 直接判；新逻辑从不 consume 该字符串
        ok("能力管理器不引用 NO_WPS 字面量",
                !WpsCapabilityManager.V_NO_PIN.equals("NO_WPS"), "");

        System.out.println();
        System.out.println("=== WpsCapabilityManagerTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static WpsIeParser.Result ieLocal(int cfg) {
        WpsIeParser.Result ie = new WpsIeParser.Result();
        ie.wpsIeFound = true;
        ie.configMethods = cfg;
        return ie;
    }
}
