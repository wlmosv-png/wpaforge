package io.github.wlmosv_png.wpaforge.wps;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * VulnUi 的离线测试：标记文案 + 状态诊断。
 *
 * 诊断这块每条文案都对应一种「用户接下来该干什么」，所以分支要逐个钉死 ——
 * 尤其是 NO_CHANNEL（通道被拦）不能退化成 NONE（都安全），
 * 那会让用户以为扫到的 AP 都没问题。
 */
public class VulnUiTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    private static void eq(String name, String got, String want) {
        ok(name, want.equals(got), "got=[" + got + "] want=[" + want + "]");
    }

    private static List<VulnWsc.Entry> L(String... lines) {
        return VulnWsc.parse(new ArrayList<>(Arrays.asList(lines)));
    }

    public static void main(String[] a) {
        System.out.println("== shortLabel ==");
        eq("未命中返回空串", VulnUi.shortLabel(null, true), "");
        eq("未命中返回空串（英文）", VulnUi.shortLabel(null, false), "");
        List<VulnWsc.Entry> es = L("D-Link Router DIR-615H1", "Tenda 123456",
                "Archer C50 1.0", "TP-Link TL-WR841N");
        VulnWsc.Match plain = VulnWsc.match("D-Link Router DIR-615H1", es);
        eq("普通命中", VulnUi.shortLabel(plain, true), "可打");
        eq("普通命中（英文）", VulnUi.shortLabel(plain, false), "Pixie");
        VulnWsc.Match pin = VulnWsc.match("Tenda", es);
        eq("带默认 PIN", VulnUi.shortLabel(pin, true), "可打 · PIN");
        eq("带默认 PIN（英文）", VulnUi.shortLabel(pin, false), "Pixie PIN");
        VulnWsc.Match vd = VulnWsc.match("Archer C50", es);
        eq("版本不同", VulnUi.shortLabel(vd, true), "可打?");
        eq("版本不同（英文）", VulnUi.shortLabel(vd, false), "Pixie?");

        System.out.println("== detail ==");
        eq("未命中无细节", VulnUi.detail(null, true), "");
        eq("普通命中无细节", VulnUi.detail(plain, true), "");
        eq("默认 PIN 细节", VulnUi.detail(pin, true), "默认 PIN 123456");
        eq("默认 PIN 细节（英文）", VulnUi.detail(pin, false), "default PIN 123456");
        eq("版本不同细节", VulnUi.detail(vd, true), "版本与名单不同");
        eq("版本不同细节（英文）", VulnUi.detail(vd, false), "version differs");

        System.out.println("== listButton ==");
        ok("下载中优先", VulnUi.listButton(true, 100, true, true).contains("下载"),
                VulnUi.listButton(true, 100, true, true));
        ok("未下载时提示下载", VulnUi.listButton(false, 0, false, true).contains("下载"),
                VulnUi.listButton(false, 0, false, true));
        ok("未下载时给体积（英文）", VulnUi.listButton(false, 0, false, false).contains("KB"),
                VulnUi.listButton(false, 0, false, false));
        ok("已下载显示条数", VulnUi.listButton(true, 164, false, true).contains("164"),
                VulnUi.listButton(true, 164, false, true));
        ok("文件在但解析 0 条要提示重下",
                VulnUi.listButton(true, 0, false, true).contains("重下"),
                VulnUi.listButton(true, 0, false, true));

        System.out.println("== diagnose：名单未下载 ==");
        VulnUi.Diag d = VulnUi.diagnose(false, 0, 23, 0, 0, true, true);
        ok("码 = NO_LIST", d.code == VulnUi.NO_LIST, "" + d.code);
        ok("要用户动手", d.action, "");
        ok("文案提到下载", d.text.contains("下载"), d.text);

        System.out.println("== diagnose：名单就绪但没扫到 WPS AP ==");
        d = VulnUi.diagnose(true, 164, 0, 0, 0, true, true);
        ok("码 = NO_AP", d.code == VulnUi.NO_AP, "" + d.code);
        ok("不需要动手", !d.action, "");
        ok("文案带名单条数", d.text.contains("164"), d.text);

        System.out.println("== diagnose：通道被拦（本机实测形态） ==");
        d = VulnUi.diagnose(true, 164, 23, 0, 0, false, true);
        ok("码 = NO_CHANNEL", d.code == VulnUi.NO_CHANNEL, "" + d.code);
        ok("要用户动手", d.action, "");
        ok("文案点名 SELinux", d.text.contains("SELinux"), d.text);
        ok("文案给出读数（0/23）", d.text.contains("0") && d.text.contains("23"), d.text);
        ok("英文版点名 SELinux",
                VulnUi.diagnose(true, 164, 23, 0, 0, false, false).text.contains("SELinux"),
                VulnUi.diagnose(true, 164, 23, 0, 0, false, false).text);

        System.out.println("== diagnose：通道通但没设备名 ==");
        d = VulnUi.diagnose(true, 164, 23, 0, 0, true, true);
        ok("码 = NO_NAME（不是 NO_CHANNEL）", d.code == VulnUi.NO_NAME, "" + d.code);
        ok("不需要动手", !d.action, "");

        System.out.println("== diagnose：命中 ==");
        d = VulnUi.diagnose(true, 164, 23, 5, 2, true, true);
        ok("码 = HIT", d.code == VulnUi.HIT, "" + d.code);
        ok("文案带命中数", d.text.contains("2"), d.text);
        ok("英文版带命中数",
                VulnUi.diagnose(true, 164, 23, 5, 2, true, false).text.contains("2"),
                VulnUi.diagnose(true, 164, 23, 5, 2, true, false).text);

        System.out.println("== diagnose：正常但没命中（本机补策略后的形态） ==");
        d = VulnUi.diagnose(true, 164, 23, 5, 0, true, true);
        ok("码 = NONE", d.code == VulnUi.NONE, "" + d.code);
        ok("不需要动手", !d.action, "");
        ok("文案带 5/23", d.text.contains("5") && d.text.contains("23"), d.text);

        System.out.println("=== VulnUi 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
