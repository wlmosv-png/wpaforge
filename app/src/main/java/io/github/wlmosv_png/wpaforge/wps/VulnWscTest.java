package io.github.wlmosv_png.wpaforge.wps;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * VulnWsc 的离线测试：名单解析、归一化、匹配口径与文案。
 *
 * 这块的价值全在「判」上。名单是社区维护的外来文本，格式不保证统一；
 * 判宽了会把不相干的型号标成「已知可打」，用户跑去试一台根本打不动的机器；
 * 判窄了则该看到的信息看不到。所以三条口径要钉死：
 *   ① 行尾 token 的归属（版本号 vs 默认 PIN vs 谁都不是）
 *   ② 泛称条目（Router / Wireless Router）必须排除
 *   ③ 双向包含的最短长度门槛
 */
public class VulnWscTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    private static void eq(String name, String got, String want) {
        ok(name, want.equals(got), "got=[" + got + "] want=[" + want + "]");
    }

    /** 造一份名单（走公开入口 parse，不碰内部构造器） */
    private static List<VulnWsc.Entry> L(String... lines) {
        return VulnWsc.parse(new ArrayList<>(Arrays.asList(lines)));
    }

    /** 单行名单 → 第一条目，省掉 .get(0) 噪音 */
    private static VulnWsc.Entry one(String line) {
        return L(line).get(0);
    }

    public static void main(String[] a) {
        System.out.println("== parse: 空行与注释 ==");
        ok("parse(null) 返回空表", VulnWsc.parse(null).isEmpty(), "");
        ok("parse(空列表) 返回空表", VulnWsc.parse(new ArrayList<String>()).isEmpty(), "");
        ok("注释行被跳过", L("# 这是注释").isEmpty(), "");
        ok("缩进注释也被跳过", L("   # 缩进注释").isEmpty(), "");
        ok("纯空白行被跳过", L("", "   ", "\t").isEmpty(), "");
        ok("单行 null 元素被跳过", VulnWsc.parse(Arrays.asList("# x", null)).isEmpty(), "");

        System.out.println("== parse: 行尾 token 归属 ==");
        VulnWsc.Entry plain = one("Tenda W20E");
        eq("无行尾 token 时 base 保留整行", plain.base, "Tenda W20E");
        eq("无行尾 token 时 version 为空", plain.version, "");
        eq("无行尾 token 时 hintPin 为空", plain.hintPin, "");
        eq("raw 保留整行原文", plain.raw, "Tenda W20E");

        VulnWsc.Entry v2 = one("Tenda W20E 4.0");
        eq("带点版本号归 version", v2.version, "4.0");
        eq("带点版本号从 base 剥离", v2.base, "Tenda W20E");
        eq("三段版本号同样识别", one("Hitron CGN 1.0.0").version, "1.0.0");
        eq("1~2 位纯数字算版本", one("Edimax 13").version, "13");
        eq("1~2 位版本从 base 剥离", one("Edimax 13").base, "Edimax");

        VulnWsc.Entry p6 = one("Tenda 123456");
        eq("4~8 位纯数字归 hintPin", p6.hintPin, "123456");
        eq("hintPin 从 base 剥离", p6.base, "Tenda");
        eq("hintPin 不占用 version", p6.version, "");
        eq("8 位数字也算 PIN", one("Netgear 12345678").hintPin, "12345678");

        VulnWsc.Entry p3 = one("Tenda 123");
        eq("3 位数字既非版本也非 PIN（留在 base）", p3.base, "Tenda 123");
        eq("3 位数字不写进 version", p3.version, "");
        eq("3 位数字不写进 hintPin", p3.hintPin, "");
        eq("10 位数字不是 PIN", one("Tenda 1234567890").hintPin, "");
        eq("10 位数字留在 base", one("Tenda 1234567890").base, "Tenda 1234567890");

        VulnWsc.Entry tail = one("Tenda 4.0 123456");
        eq("只认最后一个 token（PIN 胜出）", tail.hintPin, "123456");
        eq("倒数第二个 token 不被回头解析", tail.version, "");
        eq("倒数第二个 token 留在 base", tail.base, "Tenda 4.0");

        eq("解析前先做 trim", one("  Tenda 123456  ").raw, "Tenda 123456");

        System.out.println("== parse: 泛称标记 ==");
        ok("Router 标记泛称", one("Router").generic, "");
        ok("Wireless Router 标记泛称", one("Wireless Router").generic, "");
        ok("分隔符不影响泛称判定", one("Wireless_Router").generic, "");
        ok("大小写不影响泛称判定", one("WIRELESS ROUTER").generic, "");
        ok("Access Point 标记泛称", one("Access Point").generic, "");
        ok("带品牌前缀不算泛称", !one("TP-Link Router").generic, "");
        ok("Gigabit Router 不在泛称表里", !one("Gigabit Router").generic, "");

        System.out.println("== normalize ==");
        eq("null → 空串", VulnWsc.normalize(null), "");
        eq("空串 → 空串", VulnWsc.normalize(""), "");
        eq("转小写", VulnWsc.normalize("TP-LINK"), "tplink");
        eq("去下划线与点", VulnWsc.normalize("W20E_4.0"), "w20e40");
        eq("去括号与连字符", VulnWsc.normalize("D-Link (DIR-615)"), "dlinkdir615");
        eq("去空格", VulnWsc.normalize("  a b  "), "ab");
        eq("中文保留", VulnWsc.normalize("华为 HG8145"), "华为hg8145");

        System.out.println("== match: 长度门槛 ==");
        ok("设备名 2 字符不参与", VulnWsc.match("AP", L("APX45")) == null, "");
        ok("设备名 4 字符不参与", VulnWsc.match("APX4", L("APX4")) == null, "");
        ok("设备名 5 字符参与", VulnWsc.match("APX45", L("APX45")) != null, "");
        ok("名单条目归一化后 4 字符不参与",
                VulnWsc.match("APX45XXXX", L("APX4")) == null, "");
        ok("名单条目归一化后 5 字符参与",
                VulnWsc.match("APX45XXXX", L("APX45")) != null, "");
        ok("设备名为 null 不参与", VulnWsc.match(null, L("Tenda W20E")) == null, "");
        ok("名单为 null 返回 null", VulnWsc.match("Tenda W20E", null) == null, "");
        ok("空名单返回 null", VulnWsc.match("Tenda W20E", L("# 空")) == null, "");

        System.out.println("== match: 双向包含 ==");
        ok("设备名包含条目（正向）",
                VulnWsc.match("TENDA W20E_4.0", L("Tenda W20E")) != null, "");
        ok("条目包含设备名（反向）",
                VulnWsc.match("TL-WR841N", L("TP-Link TL-WR841ND v4")) != null, "");
        ok("毫不相干不命中",
                VulnWsc.match("Netgear WNR2000", L("Tenda W20E")) == null, "");
        ok("大小写与分隔符差异不影响命中",
                VulnWsc.match("tenda-w20e", L("Tenda W20E")) != null, "");

        System.out.println("== match: 泛称与多命中 ==");
        ok("泛称条目不被命中",
                VulnWsc.match("Wireless Router",
                        L("Wireless Router", "Tenda W20E")) == null, "");
        ok("泛称条目混在名单里也不误命中",
                VulnWsc.match("Router", L("Router")) == null, "");
        VulnWsc.Match best = VulnWsc.match("Tenda W20E", L("Tenda", "Tenda W20E"));
        ok("多命中取更具体的那个",
                best != null && "Tenda W20E".equals(best.entry.base),
                best == null ? "null" : best.entry.base);

        System.out.println("== match: 版本比对 ==");
        ok("名单无版本时 versionMatched 为真",
                VulnWsc.match("Tenda W20E", L("Tenda W20E")).versionMatched, "");
        ok("设备名含同版本时 versionMatched 为真",
                VulnWsc.match("Tenda W20E 4.0", L("Tenda W20E 4.0")).versionMatched, "");
        VulnWsc.Match vDiff = VulnWsc.match("Tenda W20E", L("Tenda W20E 4.0"));
        ok("型号命中但版本不符时仍返回命中", vDiff != null, "");
        ok("型号命中但版本不符时标记 false",
                vDiff != null && !vDiff.versionMatched, "");

        System.out.println("== label（中英） ==");
        eq("未命中返回空串", VulnWsc.label(null, true), "");
        eq("未命中返回空串（英文）", VulnWsc.label(null, false), "");

        VulnWsc.Match hint = VulnWsc.match("Tenda", L("Tenda 123456"));
        eq("默认 PIN 文案", VulnWsc.label(hint, true), "已知可打 · 默认 PIN 123456");
        eq("默认 PIN 文案（英文）",
                VulnWsc.label(hint, false), "known vulnerable · default PIN 123456");
        eq("版本不同文案", VulnWsc.label(vDiff, true), "型号在名单里（版本不同）");
        eq("版本不同文案（英文）",
                VulnWsc.label(vDiff, false), "model listed (version differs)");

        VulnWsc.Match hit = VulnWsc.match("Tenda W20E", L("Tenda W20E"));
        eq("普通命中文案", VulnWsc.label(hit, true), "已知可打（Pixie）");
        eq("普通命中文案（英文）",
                VulnWsc.label(hit, false), "known Pixie-vulnerable");

        System.out.println("== 真实名单片段（OneShot 风格） ==");
        List<VulnWsc.Entry> real = L(
                "# OneShot vulnwsc.txt 摘录",
                "Wireless Router",
                "TP-Link TL-WR841N 4.0",
                "Tenda 123456",
                "N/A",
                "Netgear WNR2000v4");
        ok("注释被过滤，摘录解析出 5 条", real.size() == 5, "" + real.size());
        ok("泛称条目被标记", real.get(0).generic, "");
        ok("N/A 不产生命中（归一化后过短）",
                VulnWsc.match("N/A", real) == null, "");
        ok("泛称条目不产生命中",
                VulnWsc.match("Wireless Router", real) == null, "");
        VulnWsc.Match tl = VulnWsc.match("TL-WR841N", real);
        ok("TL-WR841N 命中", tl != null, "");
        ok("TL-WR841N 报版本不同",
                tl != null && !tl.versionMatched, "");
        VulnWsc.Match td = VulnWsc.match("Tenda", real);
        ok("Tenda 命中并带默认 PIN",
                td != null && "123456".equals(td.entry.hintPin), "");
        ok("Tenda 文案含 PIN",
                td != null && VulnWsc.label(td, true).contains("123456"), "");

        System.out.println("== 泛称设备名不得命中（真实名单复现：修复前 6/6 全误标） ==");
        List<VulnWsc.Entry> real2 = L(
                "ADSL Router EV-2006-07-27",
                "AIR3G WSC Wireless Access Point AIR3G WSC Device",
                "Enhanced Wireless Router F6D4230-4 v1",
                "Wireless WPS Router <#ZVMODELVZ#>",
                "AirLive Wireless Gigabit AP AirLive Wireless Gigabit AP",
                "WPS Router RT-N12E");
        ok("裸泛称 Wireless WPS Router 不命中",
                VulnWsc.match("Wireless WPS Router", real2) == null, "");
        ok("裸泛称 Wireless Router 不命中",
                VulnWsc.match("Wireless Router", real2) == null, "");
        ok("裸泛称 WPS Router 不命中",
                VulnWsc.match("WPS Router", real2) == null, "");
        ok("裸泛称 Wireless Access Point 不命中",
                VulnWsc.match("Wireless Access Point", real2) == null, "");
        ok("裸泛称 ADSL Router 不命中",
                VulnWsc.match("ADSL Router", real2) == null, "");
        ok("裸泛称 Wireless 不命中",
                VulnWsc.match("Wireless", real2) == null, "");
        ok("泛称+型号 仍必须命中（不能一刀切）",
                VulnWsc.match("Wireless WPS Router RT-N12E", real2) != null, "");
        ok("泛称+型号 被条目包含时也命中",
                VulnWsc.match("WPS Router RT-N12E", real2) != null, "");
        ok("重复泛称不算裸泛称（名单里真有这种条目，别误杀）",
                VulnWsc.match("Wireless Router Wireless Router",
                        L("Wireless Router Wireless Router")) != null, "");
        ok("isGenericName 认裸泛称", VulnWsc.isGenericName("wirelessaccesspoint"), "");
        ok("isGenericName 不认带型号", !VulnWsc.isGenericName("dlinkrouterdir615h1"), "");
        ok("isGenericName(null) 为假", !VulnWsc.isGenericName(null), "");
        ok("N/A 归一化后是 na，不再是死条目",
                VulnWsc.isGenericName(VulnWsc.normalize("N/A")), "");

        System.out.println("=== VulnWsc 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
