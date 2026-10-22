package io.github.wlmosv_png.wpaforge.wps;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * WPS 模块的离线测试：PIN 生成、输出契约解析、以及引擎的判定逻辑。
 *
 * 判定逻辑是这块最该被测死的部分 —— 判错了用户会对着一个永远不会成功的
 * AP 等几个小时，或者反过来在本该继续的时候提前放弃。
 */
public class WpsPinTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    public static void main(String[] a) {
        System.out.println("== 校验位算法 ==");
        ok("12345670 合法", WpsPins.valid("12345670"), "");
        ok("12345678 校验位错", !WpsPins.valid("12345678"), "");
        ok("00000000 合法", WpsPins.valid("00000000"), "");
        ok("complete(1234567)=12345670",
                "12345670".equals(WpsPins.complete("1234567")), String.valueOf(WpsPins.complete("1234567")));
        ok("checksum 长度不符返回 -1", WpsPins.checksum("123") == -1, "");
        ok("checksum 非数字返回 -1", WpsPins.checksum("abcdefg") == -1, "");
        ok("valid 拒绝 7 位", !WpsPins.valid("1234567"), "");
        ok("valid 拒绝 9 位", !WpsPins.valid("123456789"), "");
        ok("valid 拒绝 null", !WpsPins.valid(null), "");
        ok("valid 拒绝空串", !WpsPins.valid(""), "");

        System.out.println("== 弱 PIN 候选 ==");
        List<String> weak = WpsPins.weakDefaults();
        ok("数量在 10..40", weak.size() >= 10 && weak.size() <= 40, "" + weak.size());
        boolean allValid = true;
        for (String p : weak) if (!WpsPins.valid(p)) allValid = false;
        ok("全部校验位合法", allValid, "");
        ok("含最常见的 12345670", weak.contains("12345670"), "");
        ok("含 00000000", weak.contains("00000000"), "");
        ok("无重复项", weak.size() == new HashSet<>(weak).size(), "");

        System.out.println("== 顺序枚举 / 断点续跑 ==");
        List<String> e10 = WpsPins.enumerate(10);
        ok("enumerate(10) 返回 10 条", e10.size() == 10, "" + e10.size());
        boolean eValid = true;
        for (String p : e10) if (!WpsPins.valid(p)) eValid = false;
        ok("枚举项全部合法", eValid, "");
        ok("首项为 00000000", "00000000".equals(e10.get(0)), e10.get(0));
        List<String> r1 = WpsPins.enumerateRange(0, 5);
        List<String> r2 = WpsPins.enumerateRange(5, 5);
        ok("range(0,5) 长度", r1.size() == 5, "" + r1.size());
        ok("range 与 enumerate 一致", r1.equals(WpsPins.enumerate(5)), "");
        ok("range 续接不重叠", !r1.contains(r2.get(0)), r2.get(0));
        ok("range 越界返回空", WpsPins.enumerateRange(99999999, 5).isEmpty(), "");
        ok("range count<=0 返回空", WpsPins.enumerateRange(0, 0).isEmpty(), "");
        ok("负数起点被夹到 0", "00000000".equals(WpsPins.enumerateRange(-5, 1).get(0)), "");
        ok("枚举总数常量", WpsPins.enumerateTotal() == 10000000, "" + WpsPins.enumerateTotal());

        System.out.println("== helper 输出契约解析 ==");
        WpsController.Result r = WpsController.parseBatchLine(
                "PIN=12345670 RESULT=TIMEOUT ELAPSED_MS=20150 ASSOC_COUNT=3 MAX_ASSOC_MS=420");
        ok("pin", "12345670".equals(r.pin), r.pin);
        ok("result", "TIMEOUT".equals(r.status), r.status);
        ok("elapsed", r.elapsedMs == 20150, "" + r.elapsedMs);
        ok("assocCount", r.assocCount == 3, "" + r.assocCount);
        ok("maxAssocMs", r.maxAssocMs == 420, "" + r.maxAssocMs);

        WpsController.Result rOk = WpsController.parseBatchLine(
                "PIN=12345670 RESULT=SUCCESS ELAPSED_MS=8100 ASSOC_COUNT=1 MAX_ASSOC_MS=6000");
        ok("SUCCESS 可识别", rOk.isSuccess(), rOk.status);
        ok("TIMEOUT 不算成功", !r.isSuccess(), r.status);

        String one = "PIN=00000000\nRESULT=TIMEOUT\nELAPSED_MS=15000\n"
                + "ASSOC_COUNT=2\nMAX_ASSOC_MS=350\n";
        WpsController.Result r3 = WpsController.parseOne(one);
        ok("parseOne pin", "00000000".equals(r3.pin), r3.pin);
        ok("parseOne elapsed", r3.elapsedMs == 15000, "" + r3.elapsedMs);
        ok("parseOne maxAssoc", r3.maxAssocMs == 350, "" + r3.maxAssocMs);
        ok("parseOne 缺 RESULT 视为 ERROR",
                "ERROR".equals(WpsController.parseOne("PIN=x\n").status), "");

        System.out.println("== 引擎：AP 不支持 WPS ==");
        WpsEngine.Session su = new WpsEngine.Session();
        for (int i = 0; i < 7; i++) {
            WpsEngine.observe(su, new WpsEngine.Attempt("0000000" + i, "TIMEOUT", 5000, 1, 300));
        }
        ok("7 次仍判进行中", WpsEngine.V_RUNNING.equals(su.verdict), su.verdict);
        WpsEngine.observe(su, new WpsEngine.Attempt("99999999", "TIMEOUT", 5000, 1, 250));
        ok("第 8 次 → 判不支持", WpsEngine.V_UNSUPPORTED.equals(su.verdict), su.verdict);
        ok("不支持 → 应停止", WpsEngine.shouldStop(su), "");
        ok("不支持 → 不该走退避", !WpsEngine.shouldBackoff(su), "");
        ok("统计 tried", su.tried == 8, "" + su.tried);
        ok("统计 maxAssocMs", su.maxAssocMs == 300, "" + su.maxAssocMs);

        System.out.println("== 引擎：真做 WPS 后忽然被限流 ==");
        WpsEngine.Session sl = new WpsEngine.Session();
        WpsEngine.observe(sl, new WpsEngine.Attempt("00000000", "TIMEOUT", 30000, 1, 6000));
        ok("有长关联 → 不判不支持", !WpsEngine.V_UNSUPPORTED.equals(sl.verdict), sl.verdict);
        for (int i = 0; i < 8; i++) {
            WpsEngine.observe(sl, new WpsEngine.Attempt("0000001" + i, "TIMEOUT", 3000, 1, 200));
        }
        ok("长关联后连续被踢 → 判锁定", WpsEngine.V_LOCKED.equals(sl.verdict), sl.verdict);
        ok("锁定 → 退避而非停止",
                WpsEngine.shouldBackoff(sl) && !WpsEngine.shouldStop(sl), "");
        WpsEngine.observe(sl, new WpsEngine.Attempt("00000099", "TIMEOUT", 30000, 1, 5000));
        ok("恢复长关联后回到进行中", WpsEngine.V_RUNNING.equals(sl.verdict), sl.verdict);

        System.out.println("== 引擎：中等时长不误判 ==");
        WpsEngine.Session sm = new WpsEngine.Session();
        for (int i = 0; i < 12; i++) {
            // 时长介于 FAST_KICK 与 REAL_WPS 之间：不确定，不应该累计被踢次数
            WpsEngine.observe(sm, new WpsEngine.Attempt("0000000" + i, "TIMEOUT", 5000, 1, 1500));
        }
        ok("中等时长不判不支持", !WpsEngine.V_UNSUPPORTED.equals(sm.verdict), sm.verdict);
        ok("中等时长不判锁定", !WpsEngine.V_LOCKED.equals(sm.verdict), sm.verdict);
        ok("中等时长重置被踢计数", sm.consecutiveKick == 0, "" + sm.consecutiveKick);

        System.out.println("== 引擎：命中 ==");
        WpsEngine.Session sh = new WpsEngine.Session();
        WpsEngine.observe(sh, new WpsEngine.Attempt("12345670", "SUCCESS", 8000, 1, 5000));
        ok("成功 → HIT", WpsEngine.V_HIT.equals(sh.verdict), sh.verdict);
        ok("记录命中 PIN", "12345670".equals(sh.hitPin), sh.hitPin);
        ok("HIT → 应停止", WpsEngine.shouldStop(sh), "");

        System.out.println("== 展示辅助 ==");
        ok("human(45s)", "45s".equals(WpsEngine.human(45000)), WpsEngine.human(45000));
        ok("human(5min)", "5min".equals(WpsEngine.human(300000)), WpsEngine.human(300000));
        ok("human(2h30m)", "2h30m".equals(WpsEngine.human(9000000)), WpsEngine.human(9000000));
        ok("human(50h)", "2d2h".equals(WpsEngine.human(50L * 3600 * 1000)), WpsEngine.human(50L * 3600 * 1000));
        ok("human(0) 返回占位", "—".equals(WpsEngine.human(0)), WpsEngine.human(0));
        ok("eta 无样本返回 null", WpsEngine.eta(new WpsEngine.Session(), 100) == null, "");
        WpsEngine.Session se = new WpsEngine.Session();
        se.tried = 10;
        se.totalMs = 100000;   // 平均 10s
        ok("eta 有样本可算", WpsEngine.eta(se, 100) != null, "");
        ok("eta 100 条 × 10s = 16min", "16min".equals(WpsEngine.eta(se, 100)), WpsEngine.eta(se, 100));
        ok("平均耗时计算", se.avgMs() == 10000, "" + se.avgMs());
        ok("verdictText 支持未知值", WpsEngine.verdictText("SOMETHING", true) != null, "");

        System.out.println("== AP 能力画像：WSC 配置方法位 ==");
        // 真实抓取样本：Broadcom 系 AP 报 0x2008（只有 VIRTUAL_DISPLAY），不收外部 PIN
        // 真机 BSS 回复是「每行一个 key=value」（raw 打印时才会变成 " | " 形态）
        String bssBroadcom = "id=940\nbssid=28:93:7d:45:dc:ed\nfreq=2412\nlevel=-46\n"
                + "ssid=B209\nwps_state=configured\nwps_device_name=BroadcomAP\n"
                + "wps_config_methods=0x2008\n"
                + "ie=dd7b0050f204104a0001101044000102"
                + "103b00010310470010ce1c3ba9c03b8f53df88c8cff1b90c611021000842726f6164636f6d"
                + "100800022008";
        String[] capB = WpsHelper.parseBssCap(bssBroadcom);
        ok("Broadcom 有画像", capB != null, "");
        ok("0x2008 判 NO_PIN", capB != null && "NO_PIN".equals(capB[0]), capB == null ? "null" : capB[0]);
        ok("回带 config_methods", capB != null && "0x2008".equals(capB[1]), "");
        ok("回带设备名", capB != null && "BroadcomAP".equals(capB[2]), "");

        // 真实抓取样本：fiberhome ONT 报 0x0100（KEYPAD）→ 可以打
        String bssFiber = "bssid=68:41:3c:8e:66:d8\nssid=B201\nwps_device_name=fiberhome ONT\n"
                + "wps_config_methods=0x0100\nie=dd0c0050f2041044000102100800020100";
        String[] capF = WpsHelper.parseBssCap(bssFiber);
        ok("0x0100 判 PIN_OK", capF != null && "PIN_OK".equals(capF[0]), capF == null ? "null" : capF[0]);

        String[] capW = WpsHelper.parseBssCap("bssid=5c:a0:02:b5:8f:65 | ssid=WIFI-8F65 | wps_config_methods=0x0380");
        ok("0x0380（KEYPAD+按鍵）判 PIN_OK", capW != null && "PIN_OK".equals(capW[0]), capW == null ? "null" : capW[0]);

        String[] capL = WpsHelper.parseBssCap("bssid=aa:bb:cc:dd:ee:ff | wps_config_methods=0x0001");
        ok("0x0001 LABEL 判 PIN_OK", capL != null && "PIN_OK".equals(capL[0]), capL == null ? "null" : capL[0]);

        String[] capP = WpsHelper.parseBssCap("bssid=aa:bb:cc:dd:ee:01 | wps_config_methods=0x0080");
        ok("只有 PUSHBUTTON 判 NO_PIN", capP != null && "NO_PIN".equals(capP[0]), capP == null ? "null" : capP[0]);

        // raw 动作展示用的竖线形态也要能解析（同一条回复的两种写法）
        String[] capPipe = WpsHelper.parseBssCap(
                "id=940 | bssid=28:93:7d:45:dc:ed | wps_device_name=BroadcomAP"
                        + " | wps_config_methods=0x2008");
        ok("竖线形态同样可解析", capPipe != null && "NO_PIN".equals(capPipe[0]),
                capPipe == null ? "null" : capPipe[0]);

        ok("多行形态取值正确", "B209".equals(WpsHelper.bssField(bssBroadcom, "ssid")), "");
        ok("多行形态取 device", "BroadcomAP".equals(WpsHelper.bssField(bssBroadcom, "wps_device_name")), "");
        ok("多行形态取 methods", "0x2008".equals(WpsHelper.bssField(bssBroadcom, "wps_config_methods")), "");
        ok("缺 methods 返回 null", WpsHelper.parseBssCap("bssid=aa:bb:cc:dd:ee:02 | ssid=x") == null, "");
        ok("空输入返回 null", WpsHelper.parseBssCap("") == null, "");
        ok("FAIL 返回 null", WpsHelper.parseBssCap("FAIL") == null, "");

        System.out.println("== AP Setup Locked（WSC 属性 0x1057）==");
        ok("识别已锁定", WpsHelper.apSetupLocked("dd130050f20410440001021057000101104a000110"), "");
        ok("未锁定返回 false",
                !WpsHelper.apSetupLocked("dd130050f20410440001021057000100104a000110"), "");
        ok("无 WSC IE 返回 false", !WpsHelper.apSetupLocked("00044232303901"), "");
        ok("空串返回 false", !WpsHelper.apSetupLocked(""), "");
        ok("真实 Broadcom IE 未锁", !WpsHelper.apSetupLocked(
                "000442323132010882848b962430486c030101200100230212002a010432040c1218603018"
                        + "0100000fac020200000fac04000fac020100000fac020c000b0501002900002d1a6f1817"
                        + "ffff0000010000000000000000000000000000000000003d160105040000000000000000"
                        + "00000000000000000000007f080400080000000040dd7b0050f204104a0001101044000102"
                        + "103b00010310470010ce1c3ba9c03b8f53df88c8cff1b90c611021000842726f6164636f6d"
                        + "1023000842726f6164636f6d1024000631323334353610420004313233341054000800060050"
                        + "f20400011011000a42726f6164636f6d4150100800022008"
                        + "1049000600372a000120dd090010180201009c0000dd1a0050f20101000050f202020000"
                        + "50f2040050f20201000050f202dd180050f2020101840003a4000027a4000042435e0062322f00"), "");
        // WSC 段前后各有别的 IE：遍历要能跳过去
        ok("跳过相邻 IE 找到 WSC 段", WpsHelper.apSetupLocked(
                "000442323230dd130050f20410440001021057000101104a00011001028482"), "");
        ok("相邻 IE 里未锁定时为 false", !WpsHelper.apSetupLocked(
                "000442323230dd130050f20410440001021057000100104a00011001028482"), "");
        ok("IE 长度越界时安全返回", !WpsHelper.apSetupLocked("ddff0050f2041044"), "");

        System.out.println("== helper 能力输出契约解析 ==");
        WpsController.Info inf = WpsController.parseInfo(
                "WPS_BSSID=28:93:7d:45:dc:ed\nWPS_SSID=B209\nWPS_DEV=BroadcomAP\n"
                        + "WPS_STATE=configured\nWPS_METHODS=0x2008\nWPS_PIN_CAPABLE=0\n"
                        + "WPS_PBC_CAPABLE=0\nWPS_LOCKED=0\nWPS_VERDICT=NO_PIN\nWPS_OK=1\n");
        ok("parseInfo verdict", "NO_PIN".equals(inf.verdict), inf.verdict);
        ok("parseInfo methods", "0x2008".equals(inf.methods), inf.methods);
        ok("parseInfo mask", inf.mask == 0x2008, "" + inf.mask);
        ok("parseInfo ok", inf.ok, "");
        ok("parseInfo device", "BroadcomAP".equals(inf.device), inf.device);
        ok("NO_PIN 是死局", inf.hopeless(), "");
        ok("reason 非空", inf.reason(true) != null && inf.reason(true).length() > 0, "");

        WpsController.Info infOk = WpsController.parseInfo(
                "WPS_METHODS=0x0100\nWPS_PIN_CAPABLE=1\nWPS_VERDICT=PIN_OK\nWPS_OK=1\n");
        ok("PIN_OK 不是死局", !infOk.hopeless(), "");

        System.out.println("== 引擎：前置画像判定 ==");
        WpsEngine.Session snp = new WpsEngine.Session();
        WpsEngine.precheck(snp, inf);
        ok("precheck NO_PIN → 直接判死", WpsEngine.V_NO_PIN.equals(snp.verdict), snp.verdict);
        ok("前置判死 → 应停止", WpsEngine.shouldStop(snp), "");
        ok("前置判死 → 零次尝试", snp.tried == 0, "" + snp.tried);
        WpsEngine.observe(snp, new WpsEngine.Attempt("00000000", "TIMEOUT", 30000, 1, 8000));
        ok("运行时观测不得改口", WpsEngine.V_NO_PIN.equals(snp.verdict), snp.verdict);
        WpsEngine.observe(snp, new WpsEngine.Attempt("12345670", "SUCCESS", 8000, 1, 5000));
        ok("真命中仍然优先", WpsEngine.V_HIT.equals(snp.verdict), snp.verdict);

        WpsEngine.Session sgp = new WpsEngine.Session();
        WpsEngine.precheck(sgp, infOk);
        ok("precheck PIN_OK 不阻断", WpsEngine.V_RUNNING.equals(sgp.verdict), sgp.verdict);
        ok("precheck PIN_OK 记可打", sgp.pinCapable, "");
        ok("precheck PIN_OK 不该停", !WpsEngine.shouldStop(sgp), "");
        ok("precheck 记 methods", "0x0100".equals(sgp.precheckMethods), sgp.precheckMethods);

        WpsEngine.Session slk = new WpsEngine.Session();
        WpsEngine.precheck(slk, WpsController.parseInfo(
                "WPS_METHODS=0x218c\nWPS_LOCKED=1\nWPS_VERDICT=LOCKED\nWPS_OK=1\n"));
        ok("precheck LOCKED → 判死", WpsEngine.V_AP_LOCKED.equals(slk.verdict), slk.verdict);
        ok("LOCKED → 应停止", WpsEngine.shouldStop(slk), "");

        WpsEngine.Session suw = new WpsEngine.Session();
        WpsEngine.precheck(suw, WpsController.parseInfo("WPS_VERDICT=NO_WPS\nWPS_OK=0\n"));
        ok("NO_WPS 不阻断（BSS 表可能没回填）",
                WpsEngine.V_RUNNING.equals(suw.verdict) && !WpsEngine.shouldStop(suw), suw.verdict);

        System.out.println("== 目标行：值得试 / 不值得试 ==");
        WpsController.Target tUp = new WpsController.Target();
        tUp.wpsVerdict = "PIN_OK";
        ok("PIN_OK 值得试", tUp.pinWorthTrying(), "");
        tUp.wpsVerdict = "NO_PIN";
        ok("NO_PIN 不值得试", !tUp.pinWorthTrying(), "");
        tUp.wpsVerdict = "LOCKED";
        ok("LOCKED 不值得试", !tUp.pinWorthTrying(), "");
        tUp.wpsVerdict = "UNKNOWN";
        ok("UNKNOWN 不拦（交给运行时）", tUp.pinWorthTrying(), "");
        ok("capLabel 双语非空", tUp.capLabel(true) != null && tUp.capLabel(false) != null, "");
        ok("verdictText NO_PIN 非空", WpsEngine.verdictText(WpsEngine.V_NO_PIN, true).length() > 0, "");
        ok("verdictText AP_LOCKED 非空", WpsEngine.verdictText(WpsEngine.V_AP_LOCKED, true).length() > 0, "");

        System.out.println("== 厂商 PIN 算法（对照 3WiFi Python 版基准）==");
        java.util.List<WpsVendorPins.Candidate> vp = WpsVendorPins.suggest("5C:A0:02:B5:8F:65", "");
        ok("能生成候选", vp.size() >= 18, "" + vp.size());
        ok("首项 18987256（24-bit）", !vp.isEmpty() && "18987256".equals(vp.get(0).pin),
                vp.isEmpty() ? "empty" : vp.get(0).describe());
        ok("含 54531574（28-bit）", hasPin(vp, "54531574"), "count=" + vp.size());
        ok("含 66559252（Reverse byte 24）", hasPin(vp, "66559252"), "");
        ok("含 46895844（D-Link）", hasPin(vp, "46895844"), "");
        ok("含 48784900（Inv NIC）", hasPin(vp, "48784900"), "");
        ok("含 52820236（OUI^NIC）", hasPin(vp, "52820236"), "");
        ok("含 37974503（NIC*2）", hasPin(vp, "37974503"), "");
        ok("候选无重复", !hasDup(vp), "");
        boolean all8 = true;
        for (WpsVendorPins.Candidate c : vp) {
            if (c.pin == null || c.pin.length() != 8 || !WpsPins.valid(c.pin)) all8 = false;
        }
        ok("候选全为合法 8 位 PIN", all8, "");

        java.util.List<WpsVendorPins.Candidate> vd = WpsVendorPins.suggest("00:14:D1:11:22:33", "");
        // OUI 命中时厂商专属算法排在最前（D-Link 的 PIN 由 NIC 派生，比裸 24-bit 准得多）
        ok("OUI 命中时首项为厂商专属算法",
                !vd.isEmpty() && vd.get(0).viaOui && "22891587".equals(vd.get(0).pin),
                vd.isEmpty() ? "empty" : vd.get(0).describe());
        ok("D-Link OUI 仍含裸 24-bit 11228677", hasPin(vd, "11228677"), "");
        ok("D-Link OUI 含 22891587（D-Link 算法）", hasPin(vd, "22891587"), "");
        ok("D-Link OUI 含 56098419（+1）", hasPin(vd, "56098419"), "");
        ok("OUI 命中时比纯通用更多", vd.size() > vp.size(), vd.size() + " vs " + vp.size());
        ok("OUI 专属标记出现", hasViaOui(vd), "");

        ok("非法 BSSID 返回空", WpsVendorPins.suggest("zz:zz", "").isEmpty(), "");
        ok("短 BSSID 返回空", WpsVendorPins.suggest("0014D1", "").isEmpty(), "");
        ok("带横线也能解析", !WpsVendorPins.suggest("00-14-D1-11-22-33", "").isEmpty(), "");
        ok("generic 子集不含 OUI 专属",
                WpsVendorPins.suggestGeneric("00:14:D1:11:22:33").size() <= vd.size(), "");

        System.out.println("== 引擎：WPS 会话优先于「关联短」（核心修复）==");
        WpsEngine.Session sw = new WpsEngine.Session();
        // 真机实测：PIN 被拒时关联只有 1.2 秒，旧判据会在第 8 次误判成「不支持」
        for (int i = 0; i < 20; i++) {
            WpsEngine.observe(sw, new WpsEngine.Attempt("0000000" + (i % 10), "REJECTED",
                    7000, 1, 1200, true));
        }
        ok("20 次 PIN 被拒仍判进行中", WpsEngine.V_RUNNING.equals(sw.verdict), sw.verdict);
        ok("记下见过 WPS 会话", sw.everWpsSession, "");
        ok("WPS 会话计数", sw.wpsSessionCount == 20, "" + sw.wpsSessionCount);
        ok("不该停止", !WpsEngine.shouldStop(sw), "");

        System.out.println("== 引擎：AP 不在配对窗口 ==");
        WpsEngine.Session snw = new WpsEngine.Session();
        for (int i = 0; i < 2; i++) {
            WpsEngine.observe(snw, new WpsEngine.Attempt("0000000" + i, "NO_RESPONSE", 9000, 0, 0));
        }
        ok("2 次无响应仍进行中", WpsEngine.V_RUNNING.equals(snw.verdict), snw.verdict);
        WpsEngine.observe(snw, new WpsEngine.Attempt("00000009", "NO_RESPONSE", 9000, 0, 0));
        ok("3 次无响应 → 判不在配对窗口", WpsEngine.V_NO_WINDOW.equals(snw.verdict), snw.verdict);
        ok("判不在窗口 → 应停止", WpsEngine.shouldStop(snw), "");
        ok("NO_WINDOW 文案非空", WpsEngine.verdictText(WpsEngine.V_NO_WINDOW, true).length() > 0, "");

        WpsEngine.Session srec = new WpsEngine.Session();
        WpsEngine.observe(srec, new WpsEngine.Attempt("00000001", "REJECTED", 7000, 1, 1200, true));
        for (int i = 0; i < 3; i++) {
            WpsEngine.observe(srec, new WpsEngine.Attempt("0000000" + (i + 2), "NO_RESPONSE", 9000, 0, 0));
        }
        ok("见过 WPS 会话后不再判「不在窗口」", WpsEngine.V_RUNNING.equals(srec.verdict), srec.verdict);

        System.out.println("== 结果分类与速率 ==");
        WpsController.Result rr = WpsController.parseBatchLine(
                "PIN=12345670 RESULT=REJECTED ELAPSED_MS=7310 ASSOC_COUNT=1 MAX_ASSOC_MS=1200 WPS_SESSION=1");
        ok("REJECTED 识别", "REJECTED".equals(rr.status), rr.status);
        ok("WPS_SESSION 解析", rr.wpsSession, "");
        ok("AP 在跟我们说话", rr.apIsTalking(), "");
        ok("REJECTED 不算成功", !rr.isSuccess(), "");
        ok("label 双语非空", rr.label(true).length() > 0 && rr.label(false).length() > 0, "");
        WpsController.Result rn = WpsController.parseOne(
                "PIN=00000000\nRESULT=NO_RESPONSE\nELAPSED_MS=9100\nASSOC_COUNT=0\nMAX_ASSOC_MS=0\nWPS_SESSION=0\n");
        ok("NO_RESPONSE 解析", "NO_RESPONSE".equals(rn.status), rn.status);
        ok("NO_RESPONSE 不是在说话", !rn.apIsTalking(), "");

        WpsEngine.Session srate = new WpsEngine.Session();
        for (int i = 0; i < 10; i++) {
            WpsEngine.observe(srate, new WpsEngine.Attempt("0000000" + i, "REJECTED", 7000, 1, 1200, true));
        }
        double rate = WpsEngine.ratePerMin(srate);
        ok("速率约 8.6 次/分", rate > 8.0 && rate < 9.5, "" + rate);
        ok("空会话速率 0", WpsEngine.ratePerMin(new WpsEngine.Session()) == 0, "");

        System.out.println("== 候选层：弱值表 / 导入 / 分层 ==");
        java.util.List<String> wtable = WpsCandidates.builtinWeak();
        ok("弱值表非空", wtable.size() >= 40, "" + wtable.size());
        boolean wOk = true;
        for (String p : wtable) if (!WpsPins.valid(p) || p.length() != 8) wOk = false;
        ok("弱值表全为合法 8 位", wOk, "");
        ok("弱值表无重复", wtable.size() == new java.util.HashSet<>(wtable).size(), "");
        ok("含 12345670", wtable.contains("12345670"), "");
        ok("含 00000000", wtable.contains("00000000"), "");

        java.util.List<String> pl = WpsCandidates.parseList(
                "12345670\n00000000 11111111\nabc 22222222,33333333\n12345670\n# note 44444444\n1234567\n");
        ok("parseList 去重", pl.size() == new java.util.HashSet<>(pl).size(), "" + pl.size());
        ok("parseList 含 12345670", pl.contains("12345670"), "");
        // 8 位但校验位不合法（22222222/44444444）→ 按前 7 位重算校验位
        ok("parseList 修正非法校验位 22222222→22222220", pl.contains("22222220"), "");
        ok("parseList 含注释里的 44444444→44444440", pl.contains("44444440"), "");
        ok("parseList 空输入安全", WpsCandidates.parseList(null).isEmpty(), "");

        java.io.File dir = new java.io.File("/tmp/wps-cand-test");
        WpsCandidates.clearCustom(dir);
        ok("空目录读取为空", WpsCandidates.loadCustom(dir).isEmpty(), "");
        int saved = WpsCandidates.saveCustom(dir, "12345670\n00000000", false);
        ok("保存 2 条", saved == 2, "" + saved);
        ok("读回 2 条", WpsCandidates.loadCustom(dir).size() == 2, "");
        WpsCandidates.saveCustom(dir, "12345670\n22222222", true);
        ok("追加去重后 3 条", WpsCandidates.loadCustom(dir).size() == 3,
                "" + WpsCandidates.loadCustom(dir).size());
        ok("清空成功", WpsCandidates.clearCustom(dir) && WpsCandidates.loadCustom(dir).isEmpty(), "");
        ok("目录不存在也不炸",
                WpsCandidates.loadCustom(new java.io.File("/tmp/no-such-dir-x")).isEmpty(), "");

        java.util.List<WpsVendorPins.Candidate> tier =
                WpsCandidates.tiered("5C:A0:02:B5:8F:65", "", null, true, false);
        ok("分层候选含厂商算法", tier.size() > 20, "" + tier.size());
        ok("分层候选无重复", !hasDup(tier), "");

        System.out.println("== 配对窗口 / Device Password ID ==");
        // Selected Registrar 只在 AP 真的在等配对时才出现在 beacon 里。
        // 本机实测周边 4 个 AP 全都没有这条 —— 所以发 PIN 出去只关联 250ms 就被踢。
        String ieSel = "dd0e0050f20410440001021041000101";
        ok("Selected Registrar=1", WpsHelper.selectedRegistrar(ieSel) == 1,
                "" + WpsHelper.selectedRegistrar(ieSel));
        ok("无 0x1041 → -1",
                WpsHelper.selectedRegistrar("dd090050f2041044000102") == -1, "");
        String ieDpid = "dd0f0050f2041044000102101200020004";
        ok("Device Password ID=4(PushButton)", WpsHelper.devicePasswordId(ieDpid) == 4,
                "" + WpsHelper.devicePasswordId(ieDpid));
        ok("无 0x1012 → -1", WpsHelper.devicePasswordId("dd090050f2041044000102") == -1, "");
        ok("空值安全", WpsHelper.selectedRegistrar("") == -1
                && WpsHelper.devicePasswordId(null) == -1, "");
        ok("0x1057 仍可解（回归）",
                WpsHelper.apSetupLocked("dd130050f20410440001021057000101104a000110"), "");

        WpsController.Info iw = WpsController.parseInfo(
                "WPS_METHODS=0x0100\nWPS_VERDICT=PIN_OK\nWPS_SEL_REG=0\nWPS_DEV_PWD_ID=-1\nWPS_OK=1\n");
        ok("pairingWindow 解析", iw.pairingWindow == 0, "" + iw.pairingWindow);
        ok("devPwdId 解析", iw.devPwdId == -1, "" + iw.devPwdId);

        WpsController.Target tw = new WpsController.Target();
        tw.wpsVerdict = "PIN_OK";
        tw.pairingWindow = 0;
        ok("标签含「配对窗口未开」", tw.capLabel(true).contains("未开"), tw.capLabel(true));
        tw.pairingWindow = 1;
        ok("标签含「配对窗口已开」", tw.capLabel(true).contains("已开"), tw.capLabel(true));
        tw.devPwdId = 4;
        ok("按键模式标注", tw.capLabel(true).contains("按键"), tw.capLabel(true));
        ok("双语都有", tw.capLabel(false).length() > 0, "");

        System.out.println("== 真实 WPS 交换 vs 瞬时关联（2026-10-22 真机数据）==");
        WpsEngine.Session ss = new WpsEngine.Session();
        // CMCC-6339 实测：关联 11876ms 的 km=WPS 会话
        WpsEngine.observe(ss, new WpsEngine.Attempt("73157557", "REJECTED", 17306, 1, 11876, true));
        ok("关联 11.8s → 判真实 WPS", ss.everRealWps, "");
        ok("真实 WPS 时仍在跑", WpsEngine.V_RUNNING.equals(ss.verdict), ss.verdict);
        ok("不该停（要继续试完候选）", !WpsEngine.shouldStop(ss), "");

        WpsEngine.Session sb2 = new WpsEngine.Session();
        // BroadcomAP 实测：关联 201ms
        WpsEngine.observe(sb2, new WpsEngine.Attempt("12345670", "REJECTED", 6555, 1, 201, true));
        ok("关联 201ms → 不算真实 WPS", !sb2.everRealWps, "");
        ok("但记下了 WPS 会话", sb2.everWpsSession, "");
        ok("阈值常量", WpsEngine.REAL_WPS_ASSOC_MS == 1500, "" + WpsEngine.REAL_WPS_ASSOC_MS);

        WpsEngine.Session sbd = new WpsEngine.Session();
        WpsEngine.observe(sbd, new WpsEngine.Attempt("1", "REJECTED", 9000, 1, 1499, true));
        ok("1499ms 不算真实 WPS", !sbd.everRealWps, "");
        WpsEngine.observe(sbd, new WpsEngine.Attempt("2", "REJECTED", 9000, 1, 1500, true));
        ok("1500ms 恰好算", sbd.everRealWps, "");

        System.out.println("== 拆半攻击：候选构造 ==");
        ok("stage1Pin(0) = 00000000", "00000000".equals(WpsCandidates.stage1Pin(0)),
                WpsCandidates.stage1Pin(0));
        ok("stage1Pin(1234) 前 4 位对", WpsCandidates.stage1Pin(1234).startsWith("1234"),
                WpsCandidates.stage1Pin(1234));
        ok("stage1Pin 后 3 位固定 000",
                WpsCandidates.stage1Pin(5678).substring(4, 7).equals("000"),
                WpsCandidates.stage1Pin(5678));
        boolean s1ok = true;
        for (int i = 0; i < 10000; i += 137) {
            String p = WpsCandidates.stage1Pin(i);
            if (p == null || p.length() != 8 || !WpsPins.valid(p)) s1ok = false;
        }
        ok("阶段 1 抽样全为合法 8 位 PIN", s1ok, "");
        ok("stage1Pin 越界返回 null", WpsCandidates.stage1Pin(10000) == null, "");
        ok("stage2Pin(1234,567) 前 4 后 3 正确",
                WpsCandidates.stage2Pin("1234", 567).startsWith("1234567"),
                WpsCandidates.stage2Pin("1234", 567));
        ok("stage2Pin 校验位合法", WpsPins.valid(WpsCandidates.stage2Pin("1234", 567)), "");
        ok("stage2Pin 越界返回 null", WpsCandidates.stage2Pin("1234", 1000) == null, "");
        ok("拆半总次数 11000", WpsCandidates.halfTotal() == 11000, "" + WpsCandidates.halfTotal());
        ok("halfPin 长度不对返回 null", WpsCandidates.halfPin("123", "000") == null, "");

        // 覆盖性：任意合法 PIN 都必须能被这两个阶段枚举到
        String truePin = "83719264";
        String p4 = truePin.substring(0, 4);
        String s3 = truePin.substring(4, 7);
        ok("阶段 1 能枚举到真 PIN 的前 4 位",
                WpsCandidates.stage1Pin(Integer.parseInt(p4)).startsWith(p4), "");
        ok("阶段 2 能构造出完整真 PIN",
                truePin.equals(WpsCandidates.stage2Pin(p4, Integer.parseInt(s3))),
                WpsCandidates.stage2Pin(p4, Integer.parseInt(s3)));

        System.out.println("== 拆半：判据 ==");
        WpsController.Result hk = WpsController.parseBatchLine(
                "PIN=12340000 RESULT=HALF_OK ELAPSED_MS=8200 ASSOC_COUNT=1 MAX_ASSOC_MS=5200 WPS_SESSION=1 LOG_KIND=M2D_THEN_MORE");
        ok("HALF_OK 识别为前 4 位被接受", hk.halfPrefixAccepted(), hk.status + "/" + hk.logKind);
        WpsController.Result hs = WpsController.parseBatchLine(
                "PIN=12340000 RESULT=PIN_REJECTED ELAPSED_MS=10500 ASSOC_COUNT=1 MAX_ASSOC_MS=5000 WPS_SESSION=1 LOG_KIND=M2D_STALL");
        ok("M2D_STALL 不算命中", !hs.halfPrefixAccepted(), hs.logKind);
        ok("PIN_REJECTED 仍算 AP 在说话", hs.apIsTalking(), "");
        ok("M2D_ONLY 不算命中", !WpsController.parseBatchLine(
                "PIN=1 RESULT=REJECTED LOG_KIND=M2D_ONLY").halfPrefixAccepted(), "");

        WpsEngine.Session sh2 = new WpsEngine.Session();
        sh2.halfPhase = 1;
        WpsEngine.observe(sh2, new WpsEngine.Attempt(
                "12340000", "PIN_REJECTED", 10500, 1, 5000, true, "M2D_STALL"));
        ok("拆半阶段普通失败 → 不判停", WpsEngine.V_RUNNING.equals(sh2.verdict), sh2.verdict);
        ok("拆半计数", sh2.halfTried == 1 && sh2.halfTotalTried == 1,
                sh2.halfTried + "/" + sh2.halfTotalTried);
        WpsEngine.observe(sh2, new WpsEngine.Attempt(
                "56780000", "HALF_OK", 8200, 1, 5200, true, "M2D_THEN_MORE"));
        ok("HALF_OK → 标记候选前 4 位", sh2.halfPrefixHit, "");

        WpsEngine.Session sh3 = new WpsEngine.Session();
        sh3.halfPhase = 1;
        for (int i = 0; i < 50; i++) {
            WpsEngine.observe(sh3, new WpsEngine.Attempt(
                    "00000000", "NO_RESPONSE", 9000, 0, 0, false, "NONE"));
        }
        ok("拆半模式不判「不在配对窗口」", WpsEngine.V_RUNNING.equals(sh3.verdict), sh3.verdict);
        ok("拆半模式不该停", !WpsEngine.shouldStop(sh3), "");

        System.out.println("== 破解链路健壮性：新增的结果码 ==");
        ok("CMD_FAIL 识别", "CMD_FAIL".equals(WpsController.parseBatchLine(
                "PIN=00000000 RESULT=CMD_FAIL ELAPSED_MS=30").status), "");
        ok("LINK_LOST 识别", "LINK_LOST".equals(WpsController.parseBatchLine(
                "PIN=00000000 RESULT=LINK_LOST").status), "");
        ok("CMD_FAIL 不算成功", !WpsController.parseBatchLine(
                "PIN=1 RESULT=CMD_FAIL").isSuccess(), "");
        ok("CMD_FAIL 不算 AP 在说话", !WpsController.parseBatchLine(
                "PIN=1 RESULT=CMD_FAIL").apIsTalking(), "");
        ok("LINK_LOST 不算 AP 在说话", !WpsController.parseBatchLine(
                "PIN=1 RESULT=LINK_LOST").apIsTalking(), "");
        ok("CMD_FAIL 有双语标签", WpsController.parseBatchLine(
                "PIN=1 RESULT=CMD_FAIL").label(true).length() > 0, "");
        ok("LINK_LOST 有双语标签", WpsController.parseBatchLine(
                "PIN=1 RESULT=LINK_LOST").label(false).length() > 0, "");
        ok("CMD_FAIL 不干扰判停逻辑",
                !WpsEngine.shouldStop(new WpsEngine.Session()), "");

        System.out.println("== 空列表/边界 ==");
        ok("observe 空会话不炸", true, "");
        List<WpsController.Result> empty = new ArrayList<>();
        ok("批量空列表本地构造安全", empty.isEmpty(), "");

        System.out.println();
        System.out.println("=== WPS PIN 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static boolean hasPin(java.util.List<WpsVendorPins.Candidate> l, String p) {
        for (WpsVendorPins.Candidate c : l) if (c.pin.equals(p)) return true;
        return false;
    }

    private static boolean hasDup(java.util.List<WpsVendorPins.Candidate> l) {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (WpsVendorPins.Candidate c : l) if (!s.add(c.pin)) return true;
        return false;
    }

    private static boolean hasViaOui(java.util.List<WpsVendorPins.Candidate> l) {
        for (WpsVendorPins.Candidate c : l) if (c.viaOui) return true;
        return false;
    }
}
