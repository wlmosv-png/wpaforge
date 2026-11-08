package io.github.wlmosv_png.wpaforge.wps;

/**
 * 第三阶段专项：通道与能力诊断。
 *
 * <h3>要挡住的现状问题（第三阶段审计提炼）</h3>
 * <ul>
 *   <li>{@code SELINUX=ok} 无条件打印：注入失败无法从输出判定，通道失效表现为
 *       「一屏 UNKNOWN / LINK_LOST」，用户与开发者都看不出是通道问题。</li>
 *   <li>「AP 支持 PIN 方法」与「AP 现在愿意收」被合成一个 verdict；
 *       未知状态被显示成「不支持」。</li>
 * </ul>
 *
 * 本测试全部调用生产逻辑 {@link WpsChannelDiag}。
 */
public final class WpsChannelDiagTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static WpsChannelDiag.ChannelObs goodCh(boolean reply, boolean parseable) {
        WpsChannelDiag.ChannelObs o = new WpsChannelDiag.ChannelObs();
        o.rootOk = true;
        o.socketExists = true;
        o.selinuxOk = true;
        o.replyReceived = reply;
        o.commandRejected = false;
        o.dataParseable = parseable;
        return o;
    }

    private static WpsChannelDiag.CapObs goodCap(boolean wsc, boolean pin) {
        WpsChannelDiag.CapObs o = new WpsChannelDiag.CapObs();
        o.bssPresent = true;
        o.hasWsc = wsc;
        o.pinMethod = pin;
        o.locked = false;
        o.selectedRegistrar = -1;
        o.devPwdId = -1;
        return o;
    }

    public static void main(String[] a) {
        System.out.println("=== 通道诊断专项 ===");

        WpsChannelDiag.ChannelObs allGood = goodCh(true, true);
        ok("通道全正常 → CHANNEL_OK",
                WpsChannelDiag.CHANNEL_OK.equals(WpsChannelDiag.judgeChannel(allGood)),
                WpsChannelDiag.judgeChannel(allGood));

        WpsChannelDiag.ChannelObs noRoot = goodCh(true, true);
        noRoot.rootOk = false;
        ok("root 不可用 → ROOT_UNAVAILABLE",
                WpsChannelDiag.ROOT_UNAVAILABLE.equals(WpsChannelDiag.judgeChannel(noRoot)),
                WpsChannelDiag.judgeChannel(noRoot));

        WpsChannelDiag.ChannelObs noSock = goodCh(true, true);
        noSock.socketExists = false;
        ok("socket 缺失 → SOCKET_MISSING",
                WpsChannelDiag.SOCKET_MISSING.equals(WpsChannelDiag.judgeChannel(noSock)),
                WpsChannelDiag.judgeChannel(noSock));

        WpsChannelDiag.ChannelObs noSel = goodCh(true, true);
        noSel.selinuxOk = false;
        ok("SELinux 未放行 → SELINUX_BLOCKED",
                WpsChannelDiag.SELINUX_BLOCKED.equals(WpsChannelDiag.judgeChannel(noSel)),
                WpsChannelDiag.judgeChannel(noSel));

        WpsChannelDiag.ChannelObs silent = goodCh(false, false);
        ok("supplicant 无回包 → SUPPLICANT_SILENT",
                WpsChannelDiag.SUPPLICANT_SILENT.equals(WpsChannelDiag.judgeChannel(silent)),
                WpsChannelDiag.judgeChannel(silent));

        WpsChannelDiag.ChannelObs refused = goodCh(true, true);
        refused.commandRejected = true;
        ok("命令被拒 → COMMAND_REFUSED",
                WpsChannelDiag.COMMAND_REFUSED.equals(WpsChannelDiag.judgeChannel(refused)),
                WpsChannelDiag.judgeChannel(refused));

        WpsChannelDiag.ChannelObs badData = goodCh(true, false);
        ok("回包无法解析 → DATA_INVALID",
                WpsChannelDiag.DATA_INVALID.equals(WpsChannelDiag.judgeChannel(badData)),
                WpsChannelDiag.judgeChannel(badData));

        ok("null 观测 → VOID", WpsChannelDiag.VOID.equals(WpsChannelDiag.judgeChannel(null)), "");

        info("通道文案：不允许把「异常」显示成「不支持」");
        ok("SELinux 文案含「异常」",
                WpsChannelDiag.channelLabel(WpsChannelDiag.SELINUX_BLOCKED, true).contains("异常"), "");

        System.out.println("== AP 能力诊断专项 ==");

        WpsChannelDiag.CapObs wps = goodCap(true, false);
        ok("有 WSC → 已确认支持 WPS",
                WpsChannelDiag.AP_WPS_SUPPORTED.equals(WpsChannelDiag.judgeCapability(wps)),
                WpsChannelDiag.judgeCapability(wps));

        WpsChannelDiag.CapObs noWps = goodCap(false, false);
        ok("无 WSC → 未发现支持 WPS",
                WpsChannelDiag.AP_WPS_NONE.equals(WpsChannelDiag.judgeCapability(noWps)),
                WpsChannelDiag.judgeCapability(noWps));

        WpsChannelDiag.CapObs unknown = new WpsChannelDiag.CapObs();
        ok("BSS 缺失 → 信息不足（不是「不支持」）",
                WpsChannelDiag.UNKNOWN.equals(WpsChannelDiag.judgeCapability(unknown)),
                WpsChannelDiag.judgeCapability(unknown));
        ok("BSS 缺失的文案是「信息不足」",
                WpsChannelDiag.capLabel(WpsChannelDiag.judgeCapability(unknown), true).contains("信息不足"),
                WpsChannelDiag.capLabel(WpsChannelDiag.judgeCapability(unknown), true));

        WpsChannelDiag.CapObs pin = goodCap(true, true);
        ok("有 PIN 方法 → AP_PIN_METHOD",
                WpsChannelDiag.AP_PIN_METHOD.equals(WpsChannelDiag.judgePinMethod(pin)),
                WpsChannelDiag.judgePinMethod(pin));

        WpsChannelDiag.CapObs noPin = goodCap(true, false);
        ok("无 PIN 方法 → AP_PIN_METHOD_NONE",
                WpsChannelDiag.AP_PIN_METHOD_NONE.equals(WpsChannelDiag.judgePinMethod(noPin)),
                WpsChannelDiag.judgePinMethod(noPin));
        ok("无 WSC 时 PIN 方法 → AP_WPS_NONE",
                WpsChannelDiag.AP_WPS_NONE.equals(WpsChannelDiag.judgePinMethod(noWps)),
                WpsChannelDiag.judgePinMethod(noWps));

        WpsChannelDiag.CapObs pair = goodCap(true, true);
        pair.selectedRegistrar = 1;
        ok("Selected Registrar=1 → 外部 registrar 可能",
                WpsChannelDiag.AP_EXT_REG_POSSIBLE.equals(WpsChannelDiag.judgeExtRegistrar(pair)),
                WpsChannelDiag.judgeExtRegistrar(pair));

        WpsChannelDiag.CapObs noReg = goodCap(true, true);
        ok("Selected Registrar 缺失 → 信息不足（非「不支持」）",
                WpsChannelDiag.AP_EXT_REG_UNKNOWN.equals(WpsChannelDiag.judgeExtRegistrar(noReg)),
                WpsChannelDiag.judgeExtRegistrar(noReg));
        ok("默认能力文案不是「不支持」",
                WpsChannelDiag.capLabel(WpsChannelDiag.AP_EXT_REG_UNKNOWN, true).contains("信息不足"),
                WpsChannelDiag.capLabel(WpsChannelDiag.AP_EXT_REG_UNKNOWN, true));

        WpsChannelDiag.CapObs locked = goodCap(true, true);
        locked.locked = true;
        ok("AP 锁定 → AP_LOCKED",
                WpsChannelDiag.AP_LOCKED.equals(WpsChannelDiag.judgeLocked(locked)),
                WpsChannelDiag.judgeLocked(locked));
        ok("未锁 → 空串（不误报）", "".equals(WpsChannelDiag.judgeLocked(pin)),
                WpsChannelDiag.judgeLocked(pin));

        System.out.println();
        System.out.println("=== WpsChannelDiagTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}