package io.github.wlmosv_png.wpaforge.wps;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * WpsVendorPins 登记表的离线测试。
 *
 * <p>重点不是「算得对不对」（算式没动，公开基准那部分由 {@code WpsPinTest} 继续对照），
 * 而是<b>登记元数据与出口契约</b>：
 * <ul>
 *   <li>每个可用厂商算法 / 固定值都有「MAC → 期望 PIN」的输入输出断言；</li>
 *   <li>序列号依赖项（Belkin / EasyBox / Livebox）必须缺席默认策略；</li>
 *   <li>无门槛通用变换必须缺席默认策略、只能从 {@code unprovenTransforms} 取；</li>
 *   <li>固定值单独归类（isFixed）、去重按 PIN 值、任何路径都受 MAX_CANDIDATES 封顶；</li>
 *   <li>非法 BSSID 不抛异常、返回空表。</li>
 * </ul>
 *
 * <p>下面的期望值都是「先按实现算出真实值、再固化进断言」得到的（固定值那 22 条的
 * 第 8 位校验位我另外用独立脚本按 WPS 校验位规则复算过一次），不是猜的。
 * 其中 D-Link / D-Link +1 / 24-bit / 28-bit / Reverse byte 24 / Inv NIC / NIC*2 / OUI^NIC
 * 这几条的期望值同时是既有 {@code WpsPinTest} 的公开基准，属交叉核对。
 */
public class WpsVendorPinsTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  " + detail);
        }
    }

    private static String[] TS_ALL = {
            WpsVendorPins.TS_BASELINE, WpsVendorPins.TS_IO, WpsVendorPins.TS_UNVERIFIED};
    private static String[] CONF_ALL = {"高", "中", "低"};

    private static boolean in(String[] arr, String v) {
        for (String s : arr) if (s.equals(v)) return true;
        return false;
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static WpsVendorPins.Candidate find(List<WpsVendorPins.Candidate> l, String pin) {
        for (WpsVendorPins.Candidate c : l) if (pin.equals(c.pin)) return c;
        return null;
    }

    private static int countAlgo(List<WpsVendorPins.Candidate> l, String algoId) {
        int n = 0;
        for (WpsVendorPins.Candidate c : l) if (algoId.equals(c.algoId)) n++;
        return n;
    }

    private static boolean hasDupPin(List<WpsVendorPins.Candidate> l) {
        Set<String> s = new HashSet<>();
        for (WpsVendorPins.Candidate c : l) if (!s.add(c.pin)) return true;
        return false;
    }

    private static boolean allPinsValid(List<WpsVendorPins.Candidate> l) {
        for (WpsVendorPins.Candidate c : l) {
            if (c.pin == null || c.pin.length() != 8 || !WpsPins.valid(c.pin)) return false;
        }
        return true;
    }

    private static WpsVendorPins.Algo algoById(String id) {
        for (WpsVendorPins.Algo a : WpsVendorPins.algorithms()) if (id.equals(a.id)) return a;
        return null;
    }

    /** 固定出厂值 22 条：{登记 id, 一个命中该条 OUI 的 MAC, 期望 8 位 PIN}。 */
    private static final String[][] FIXED = {
            {"static:cisco", "00:1A:2B:11:11:11", "12345670"},
            {"static:broadcom1", "00:1A:A9:11:11:11", "20172527"},
            {"static:broadcom2", "14:D6:4D:11:11:11", "46264848"},
            {"static:broadcom3", "14:D6:4D:11:11:11", "76229909"},
            {"static:broadcom4", "14:D6:4D:11:11:11", "62327145"},
            {"static:broadcom5", "14:D6:4D:11:11:11", "10864111"},
            {"static:broadcom6", "14:D6:4D:11:11:11", "31957199"},
            {"static:airocon1", "18:1E:78:11:11:11", "30432031"},
            {"static:airocon2", "84:A4:23:11:11:11", "71412252"},
            {"static:dsl2740r", "00:26:5A:11:11:11", "68175542"},
            {"static:realtek1", "00:0C:42:11:11:11", "95661469"},
            {"static:realtek2", "00:72:63:11:11:11", "95719115"},
            {"static:realtek3", "08:C6:B3:11:11:11", "48563710"},
            {"static:upvel", "78:44:76:11:11:11", "20854836"},
            {"static:ur814ac", "D4:BF:7F:60:11:11", "43977680"},
            {"static:ur825ac", "D4:BF:7F:51:11:11", "05294176"},
            {"static:onlime", "00:14:D1:11:11:11", "99956042"},
            {"static:edimax", "00:E0:4C:11:11:11", "35611530"},
            {"static:thomson", "00:26:24:11:11:11", "67958146"},
            {"static:hg532x", "00:66:4B:11:11:11", "34259283"},
            {"static:h108l", "4C:09:B4:11:11:11", "94229882"},
            {"static:cbono", "5C:35:3B:11:11:11", "95755212"},
    };

    /** 序列号依赖项：必须缺席默认策略。 */
    private static final String[] SN_ALGOS = {"belkin", "easybox", "livebox"};

    /** 若干用于遍历的 MAC（含厂商 OUI、固定值 OUI、华为随机样本）。 */
    private static final String[] MACS = {
            "00:14:D1:11:22:33", "14:D6:4D:11:11:11", "00:1E:A6:11:22:33",
            "00:0E:F4:E7:11:22", "00:1A:2B:11:11:11", "5C:A0:02:B5:8F:65",
            "08:86:3B:11:22:33", "18:83:BF:11:22:33", "00:26:4D:11:22:33",
            "AA:BB:CC:DD:EE:FF", "FF:FF:FF:FF:FF:FF", "00:00:00:00:00:01",
    };

    private static final String[] BAD_MACS = {
            null, "", "   ", "zz:zz", "0014D1", "00:1E:A6:11:22:3",
            "00:14:D1:11:22:33:44", "GG:HH:II:JJ:KK:LL", ":::::",
    };

    public static void main(String[] a) {
        System.out.println("== 1. 登记表元数据完整性 ==");
        ok("MAX_CANDIDATES 硬上限为 64", WpsVendorPins.MAX_CANDIDATES == 64,
                "" + WpsVendorPins.MAX_CANDIDATES);

        List<WpsVendorPins.Algo> all = WpsVendorPins.algorithms();
        ok("algorithms() 非空", !all.isEmpty(), "" + all.size());
        ok("algorithms() = 厂商 7 + 固定值 22 + 无门槛 22",
                all.size() == 7 + FIXED.length + 22, "" + all.size());

        boolean metaOk = true;
        String metaBad = "";
        boolean tsOk = true, confOk = true, availHasSn = false;
        Set<String> ids = new HashSet<>();
        boolean idDup = false;
        for (WpsVendorPins.Algo al : all) {
            if (!nonEmpty(al.id) || !nonEmpty(al.name) || !nonEmpty(al.source)
                    || !nonEmpty(al.conditions) || !nonEmpty(al.params)
                    || !nonEmpty(al.testStatus) || !nonEmpty(al.confidence)) {
                metaOk = false;
                metaBad = al.id;
            }
            if (!in(TS_ALL, al.testStatus)) {
                tsOk = false;
                metaBad = al.id + " ts=" + al.testStatus;
            }
            if (!in(CONF_ALL, al.confidence)) {
                confOk = false;
                metaBad = al.id + " conf=" + al.confidence;
            }
            if (al.available && al.params.contains("序列号")) availHasSn = true;
            if (!ids.add(al.id)) idDup = true;
        }
        ok("每个 Algo 的 id/name/source/conditions/params/testStatus/confidence 非空",
                metaOk, metaBad);
        ok("testStatus 只能是三档之一", tsOk, metaBad);
        ok("confidence 只能是 高/中/低", confOk, metaBad);
        ok("Algo id 无重复", !idDup, "");
        ok("没有「可用但依赖序列号」的项", !availHasSn, "");

        List<WpsVendorPins.Algo> avail = WpsVendorPins.availableAlgorithms();
        ok("availableAlgorithms() = 4 厂商 + 22 固定值",
                avail.size() == 4 + FIXED.length, "" + avail.size());
        boolean availFlagOk = true;
        for (WpsVendorPins.Algo al : avail) if (!al.available) availFlagOk = false;
        ok("availableAlgorithms() 全部 available==true", availFlagOk, "");

        System.out.println("== 2. 序列号依赖项：不可用、且必须缺席默认策略 ==");
        for (String id : SN_ALGOS) {
            WpsVendorPins.Algo al = algoById(id);
            ok("登记项 " + id + " 存在", al != null, "");
            if (al == null) continue;
            ok(id + " available==false", !al.available, "" + al.available);
            ok(id + " params 含「序列号」", al.params.contains("序列号"), al.params);
            ok(id + " testStatus = 未验证", WpsVendorPins.TS_UNVERIFIED.equals(al.testStatus),
                    al.testStatus);
            ok(id + " conditions 写明参数缺失原因",
                    al.conditions.contains(WpsVendorPins.SN_MISSING_NOTE), al.conditions);
            ok(id + " 不在 availableAlgorithms()",
                    algoByIdIn(avail, id) == null, "");
        }
        List<WpsVendorPins.Candidate> belkinSuggest = WpsVendorPins.suggest("08:86:3B:11:22:33", "");
        ok("Belkin OUI 的 suggest() 为空（SN 恒空 → 先验 1/65536，不猜）",
                belkinSuggest.isEmpty(), dump(belkinSuggest));
        List<WpsVendorPins.Candidate> easyboxSuggest = WpsVendorPins.suggest("00:26:4D:11:22:33", "");
        ok("EasyBox OUI 的 suggest() 不含 dslMacSn 系列",
                countAlgo(easyboxSuggest, "easybox") == 0
                        && countAlgo(easyboxSuggest, "belkin") == 0
                        && countAlgo(easyboxSuggest, "livebox") == 0, dump(easyboxSuggest));
        List<WpsVendorPins.Candidate> liveboxSuggest = WpsVendorPins.suggest("18:83:BF:11:22:33", "");
        ok("Livebox OUI 的 suggest() 为空", liveboxSuggest.isEmpty(), dump(liveboxSuggest));

        System.out.println("== 3. 厂商算法的输入输出（D-Link / ASUS / Airocon）==");
        List<WpsVendorPins.Candidate> dl = WpsVendorPins.suggest("00:14:D1:11:22:33", "");
        WpsVendorPins.Candidate dl0 = dl.isEmpty() ? null : dl.get(0);
        ok("00:14:D1:11:22:33 → 首项 D-Link PIN = 22891587",
                dl0 != null && "22891587".equals(dl0.pin) && "dlink".equals(dl0.algoId),
                dl0 == null ? "empty" : dl0.describe());
        ok("D-Link 项 viaOui=true 且 isFixed=false",
                dl0 != null && dl0.viaOui && !dl0.isFixed, "");
        WpsVendorPins.Candidate dl1 = find(dl, "56098419");
        ok("00:14:D1:11:22:33 → D-Link PIN +1 = 56098419",
                dl1 != null && "dlink+1".equals(dl1.algoId),
                dl1 == null ? "missing" : dl1.describe());
        ok("D-Link 来源文案来自 3WiFi", dl0 != null && dl0.source.contains("3WiFi"),
                dl0 == null ? "" : dl0.source);

        List<WpsVendorPins.Candidate> asus = WpsVendorPins.suggest("00:1E:A6:11:22:33", "");
        ok("00:1E:A6:11:22:33（ASUS OUI 001EA6）→ 31184625",
                asus.size() == 1 && "31184625".equals(asus.get(0).pin)
                        && "asus".equals(asus.get(0).algoId),
                dump(asus));

        List<WpsVendorPins.Candidate> airocon = WpsVendorPins.suggest("00:0E:F4:E7:11:22", "");
        ok("00:0E:F4:E7:11:22（Airocon OUI 000EF4E7）→ 48581448",
                airocon.size() == 1 && "48581448".equals(airocon.get(0).pin)
                        && "airocon".equals(airocon.get(0).algoId),
                dump(airocon));

        WpsVendorPins.Algo asusAlgo = algoById("asus");
        ok("ASUS 登记项 testStatus=有输入输出测试",
                asusAlgo != null && WpsVendorPins.TS_IO.equals(asusAlgo.testStatus),
                asusAlgo == null ? "" : asusAlgo.testStatus);
        WpsVendorPins.Algo dlinkAlgo = algoById("dlink");
        ok("D-Link 登记项 testStatus=已用公开基准对照",
                dlinkAlgo != null && WpsVendorPins.TS_BASELINE.equals(dlinkAlgo.testStatus),
                dlinkAlgo == null ? "" : dlinkAlgo.testStatus);
        ok("D-Link 登记项 confidence=高",
                dlinkAlgo != null && "高".equals(dlinkAlgo.confidence), "");

        System.out.println("== 4. 固定出厂值清单（22 条逐条输入输出）==");
        int fixedHit = 0;
        for (String[] row : FIXED) {
            List<WpsVendorPins.Candidate> fx = WpsVendorPins.fixedValues(row[1]);
            WpsVendorPins.Candidate c = find(fx, row[2]);
            boolean good = c != null && row[0].equals(c.algoId) && c.isFixed && c.viaOui;
            if (good) fixedHit++;
            ok(row[0] + " : " + row[1] + " → " + row[2], good,
                    "fixed=" + dump(fx));
        }
        ok("22 条固定值全部命中（" + fixedHit + "/22）", fixedHit == FIXED.length, "" + fixedHit);

        List<WpsVendorPins.Candidate> fxAll = WpsVendorPins.fixedValues("14:D6:4D:11:11:11");
        ok("fixedValues() 非空", !fxAll.isEmpty(), dump(fxAll));
        boolean allFixed = true, allOui = true;
        for (WpsVendorPins.Candidate c : fxAll) {
            if (!c.isFixed) allFixed = false;
            if (!c.viaOui) allOui = false;
        }
        ok("fixedValues() 全部 isFixed=true", allFixed, "");
        ok("fixedValues() 全部 viaOui=true", allOui, "");
        ok("fixedValues() PIN 全为合法 8 位", allPinsValid(fxAll), "");

        // 与弱 PIN 表重复：Cisco 的 12345670 两边都有 —— 按规则保留在固定值清单，
        // 去重交给 suggest()/tiered() 统一做。
        ok("弱值表里也有 Cisco 的 12345670（重复已在注释注明）",
                WpsCandidates.builtinWeak().contains("12345670"), "");
        List<WpsVendorPins.Candidate> ciscoSuggest = WpsVendorPins.suggest("00:1A:2B:11:11:11", "");
        ok("固定值进 suggest()：Cisco OUI → 含 12345670（isFixed）",
                find(ciscoSuggest, "12345670") != null
                        && find(ciscoSuggest, "12345670").isFixed, dump(ciscoSuggest));
        ok("Cisco OUI 的 suggest() 里 12345670 只出现一次",
                countAlgo(ciscoSuggest, "static:cisco") == 1
                        && !hasDupPin(ciscoSuggest), dump(ciscoSuggest));

        System.out.println("== 5. 无门槛通用变换：不进默认，单独入口 ==");
        List<WpsVendorPins.Candidate> un = WpsVendorPins.unprovenTransforms("5C:A0:02:B5:8F:65");
        ok("unprovenTransforms() 非空", !un.isEmpty(), "" + un.size());
        ok("5C:A0:02:B5:8F:65 含 18987256（24-bit，公开基准）", find(un, "18987256") != null, "");
        ok("5C:A0:02:B5:8F:65 含 54531574（28-bit，公开基准）", find(un, "54531574") != null, "");
        ok("5C:A0:02:B5:8F:65 含 66559252（Reverse byte 24，公开基准）",
                find(un, "66559252") != null, "");
        ok("5C:A0:02:B5:8F:65 含 48784900（Inv NIC，公开基准）", find(un, "48784900") != null, "");
        ok("5C:A0:02:B5:8F:65 含 37974503（NIC*2，公开基准）", find(un, "37974503") != null, "");
        ok("5C:A0:02:B5:8F:65 含 52820236（OUI^NIC，公开基准）", find(un, "52820236") != null, "");
        ok("无门槛项全部 viaOui=false 且 isFixed=false",
                allViaOuiFalse(un) && allFixedFalse(un), dump(un));
        ok("unprovenTransforms() PIN 无重复且全合法",
                !hasDupPin(un) && allPinsValid(un), "");
        ok("兼容入口 suggestGeneric() 与 unprovenTransforms() 同值同序",
                samePins(WpsVendorPins.suggestGeneric("5C:A0:02:B5:8F:65"), un), "");
        ok("D-Link 已不在无门槛表里（规则 1：只留一处）",
                countAlgo(un, "dlink") == 0 && countAlgo(un, "dlink+1") == 0, "");
        boolean noSigInGeneric = true;
        for (WpsVendorPins.Algo al : WpsVendorPins.algorithms()) {
            if (al.conditions.startsWith("无门槛") && al.name.contains("D-Link")) noSigInGeneric = false;
        }
        ok("登记表里没有「无门槛 + D-Link」的重复项", noSigInGeneric, "");

        int leak = 0;
        String leakWhere = "";
        for (String mac : MACS) {
            for (WpsVendorPins.Candidate c : WpsVendorPins.suggest(mac, "")) {
                if (!c.viaOui && !c.isFixed) {
                    leak++;
                    leakWhere = mac + " " + c.describe();
                }
            }
        }
        ok("suggest() 里没有任何 viaOui==false && !isFixed 的候选", leak == 0, leakWhere);

        System.out.println("== 6. 去重与上限 ==");
        boolean dupFree = true, capOk = true, validOk = true;
        String dupWhere = "";
        for (String mac : MACS) {
            List<WpsVendorPins.Candidate> s = WpsVendorPins.suggest(mac, "");
            if (hasDupPin(s)) {
                dupFree = false;
                dupWhere = mac;
            }
            if (!allPinsValid(s)) validOk = false;
            if (s.size() > WpsVendorPins.MAX_CANDIDATES
                    || WpsVendorPins.fixedValues(mac).size() > WpsVendorPins.MAX_CANDIDATES
                    || WpsVendorPins.unprovenTransforms(mac).size() > WpsVendorPins.MAX_CANDIDATES) {
                capOk = false;
            }
        }
        ok("suggest() 结果无重复 PIN", dupFree, dupWhere);
        ok("suggest() 结果全为合法 8 位 PIN", validOk, "");
        ok("三条输出路径都不超过 MAX_CANDIDATES", capOk, "");

        List<WpsVendorPins.Candidate> bcm = WpsVendorPins.suggest("14:D6:4D:11:11:11", "");
        ok("D-Link OUI 上 dlink 只出现一次", countAlgo(bcm, "dlink") == 1, dump(bcm));
        ok("D-Link OUI 上 dlink+1 只出现一次", countAlgo(bcm, "dlink+1") == 1, dump(bcm));
        String pinD0 = pinOfAlgo(bcm, "dlink"), pinD1 = pinOfAlgo(bcm, "dlink+1");
        ok("dlink 与 dlink+1 是同 MAC 上的两个不同值",
                pinD0 != null && pinD1 != null && !pinD0.equals(pinD1), pinD0 + " / " + pinD1);
        int dlinkEntries = 0, dlink1Entries = 0;
        for (WpsVendorPins.Algo al : WpsVendorPins.algorithms()) {
            if ("dlink".equals(al.id)) dlinkEntries++;
            if ("dlink+1".equals(al.id)) dlink1Entries++;
        }
        ok("登记表里 dlink / dlink+1 各恰好一条",
                dlinkEntries == 1 && dlink1Entries == 1,
                dlinkEntries + "/" + dlink1Entries);

        System.out.println("== 7. 非法输入 ==");
        for (String bad : BAD_MACS) {
            String label = bad == null ? "null" : ("\"" + bad + "\"");
            boolean threw = false;
            boolean empty = false;
            try {
                empty = WpsVendorPins.suggest(bad, "").isEmpty()
                        && WpsVendorPins.suggest(bad, null).isEmpty()
                        && WpsVendorPins.unprovenTransforms(bad).isEmpty()
                        && WpsVendorPins.fixedValues(bad).isEmpty()
                        && WpsVendorPins.suggestGeneric(bad).isEmpty();
            } catch (Throwable t) {
                threw = true;
            }
            ok("非法 BSSID " + label + "：不抛异常且返回空表", !threw && empty,
                    threw ? "threw" : "empty=" + empty);
        }
        ok("合法 BSSID 仍能解析（带横线 / 大写）",
                !WpsVendorPins.suggest("00-14-D1-11-22-33", "").isEmpty()
                        && !WpsVendorPins.suggest("00:14:d1:11:22:33", "").isEmpty(), "");

        System.out.println("=== WpsVendorPinsTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static WpsVendorPins.Algo algoByIdIn(List<WpsVendorPins.Algo> l, String id) {
        for (WpsVendorPins.Algo al : l) if (id.equals(al.id)) return al;
        return null;
    }

    private static boolean allViaOuiFalse(List<WpsVendorPins.Candidate> l) {
        for (WpsVendorPins.Candidate c : l) if (c.viaOui) return false;
        return true;
    }

    private static boolean allFixedFalse(List<WpsVendorPins.Candidate> l) {
        for (WpsVendorPins.Candidate c : l) if (c.isFixed) return false;
        return true;
    }

    private static boolean samePins(List<WpsVendorPins.Candidate> a,
                                    List<WpsVendorPins.Candidate> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).pin.equals(b.get(i).pin)) return false;
        return true;
    }

    private static String pinOfAlgo(List<WpsVendorPins.Candidate> l, String algoId) {
        for (WpsVendorPins.Candidate c : l) if (algoId.equals(c.algoId)) return c.pin;
        return null;
    }

    private static String dump(List<WpsVendorPins.Candidate> l) {
        StringBuilder sb = new StringBuilder("[" + l.size() + "] ");
        for (WpsVendorPins.Candidate c : l) sb.append(c.describe()).append(' ');
        return sb.toString().trim();
    }
}
