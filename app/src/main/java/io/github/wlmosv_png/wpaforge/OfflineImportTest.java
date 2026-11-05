package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.OfflineImport;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * 离线校验导入链路测试：读取判定 + 解析判定。
 *
 * <p>覆盖用户报障里的每一条：没有选择文件、没有读取授权、文件不存在、
 * 文件为空、格式不合法、记录类型不支持、多条与混合记录、扩展名识别、
 * 体积上限。全部为纯逻辑，不需要真机与存储权限。
 */
public class OfflineImportTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private static void eq(String name, Object got, Object want) {
        boolean same = want == null ? got == null : want.equals(got);
        if (same) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "   got=" + got + "  want=" + want);
        }
    }

    private static String toHex(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String eapolHex() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 120; i++) sb.append(String.format("%02x", (i * 7) & 0xFF));
        return sb.toString();
    }

    private static final String AP = "001122334455";
    private static final String STA = "66778899aabb";
    private static final String PMKID = "8547b061fd9759b2779ea0bdb7bda7f9";

    private static String line01(String ssid) {
        return "WPA*01*" + PMKID + "*" + AP + "*" + STA + "*" + toHex(ssid) + "***";
    }

    private static String line02(String ssid) {
        return "WPA*02*" + PMKID + "*" + AP + "*" + STA + "*" + toHex(ssid)
                + "*" + eapolHex().substring(0, 64) + "*" + eapolHex() + "*2";
    }

    private static OfflineImport.Result stream(String s) throws Exception {
        return OfflineImport.analyzeStream(
                new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 扩展名识别（.hc22000 / .22000）==");
        ok(".hc22000", OfflineImport.looksLikeHcName("a.hc22000"));
        ok(".22000", OfflineImport.looksLikeHcName("a.22000"));
        ok("大写 .HC22000", OfflineImport.looksLikeHcName("A.HC22000"));
        ok("带路径", OfflineImport.looksLikeHcName("/sdcard/Download/WPAForge-hc/x.hc22000"));
        ok("其它扩展名不算", !OfflineImport.looksLikeHcName("a.cap"));
        ok("无扩展名不算（但不作为拒绝依据）", !OfflineImport.looksLikeHcName("capture"));
        ok("null 安全", !OfflineImport.looksLikeHcName(null));

        System.out.println("== 文件选择器返回 ==");
        eq("拿到 URI -> OK", OfflineImport.classifyPickerResult(true), OfflineImport.Status.OK);
        eq("取消操作 -> NO_FILE", OfflineImport.classifyPickerResult(false), OfflineImport.Status.NO_FILE);

        System.out.println("== 读取阶段异常映射 ==");
        eq("SecurityException -> 没有读取授权",
                OfflineImport.classifyReadFailure("java.lang.SecurityException", "denied"),
                OfflineImport.Status.NO_ACCESS);
        eq("FileNotFound + EACCES -> 没有读取授权",
                OfflineImport.classifyReadFailure("java.io.FileNotFoundException",
                        "/x/y.hc22000: open failed: EACCES (Permission denied)"),
                OfflineImport.Status.NO_ACCESS);
        eq("FileNotFound ENOENT -> 文件不存在",
                OfflineImport.classifyReadFailure("java.io.FileNotFoundException",
                        "/x/y.hc22000: open failed: ENOENT (No such file or directory)"),
                OfflineImport.Status.NO_FILE);
        eq("IOException -> 文件无法读取",
                OfflineImport.classifyReadFailure("java.io.IOException", "stream closed"),
                OfflineImport.Status.READ_ERROR);
        eq("null -> 文件无法读取",
                OfflineImport.classifyReadFailure(null, null),
                OfflineImport.Status.READ_ERROR);
        ok("读取阶段分类判定", OfflineImport.isReadStage(OfflineImport.Status.NO_ACCESS));
        ok("解析阶段不算读取阶段",
                !OfflineImport.isReadStage(OfflineImport.Status.FORMAT_INVALID));

        System.out.println("== 空文件 / 损坏文件 / 非法记录 ==");
        eq("空文件 -> 文件为空", stream("").status, OfflineImport.Status.EMPTY);
        eq("只有空行 -> 文件为空", stream("\n\n   \n").status, OfflineImport.Status.EMPTY);
        eq("只有注释行 -> 文件为空",
                stream("# exported by hcxpcapngtool\n#\n").status, OfflineImport.Status.EMPTY);
        eq("普通文本 -> 格式不合法",
                stream("hello world\n123456\n").status, OfflineImport.Status.FORMAT_INVALID);
        eq("pcap 改名 -> 格式不合法",
                stream("binary junk here\nmore junk\n").status, OfflineImport.Status.FORMAT_INVALID);
        eq("WPA*03 记录类型不支持",
                stream("WPA*03*aa*bb*cc*dd***\n").status, OfflineImport.Status.UNSUPPORTED);
        eq("字段不全的 01 行 -> 格式不合法",
                stream("WPA*01*" + PMKID + "*" + AP + "\n").status,
                OfflineImport.Status.FORMAT_INVALID);

        System.out.println("== PMKID 单条 ==");
        OfflineImport.Result r1 = stream(line01("TestSSID") + "\n");
        eq("解析成功", r1.status, OfflineImport.Status.OK);
        eq("PMKID 计数", r1.pmkid, 1);
        eq("EAPOL 计数", r1.eapol, 0);
        eq("材料条数", r1.count(), 1);
        eq("SSID 还原", r1.entries.get(0).ssid, "TestSSID");
        eq("AP MAC 还原", io.github.wlmosv_png.wpaforge.audit.Hc22000
                .hexMac(r1.entries.get(0).apMac), "00:11:22:33:44:55");

        System.out.println("== EAPOL 单条 ==");
        OfflineImport.Result r2 = stream(line02("TestSSID") + "\n");
        eq("解析成功", r2.status, OfflineImport.Status.OK);
        eq("EAPOL 计数", r2.eapol, 1);
        eq("PMKID 计数", r2.pmkid, 0);

        System.out.println("== 多条与混合记录 ==");
        String mixed = "# comment\n"
                + line01("Home") + "\n"
                + "\n"
                + line02("Office") + "\n"
                + "garbage line\n"
                + line01("Cafe") + "\n";
        OfflineImport.Result r3 = stream(mixed);
        eq("混合 -> 解析成功", r3.status, OfflineImport.Status.OK);
        eq("总条数 3", r3.count(), 3);
        eq("PMKID 2 条", r3.pmkid, 2);
        eq("EAPOL 1 条", r3.eapol, 1);
        eq("注释 1 行", r3.comments, 1);
        eq("坏行 1 行", r3.bad, 1);

        System.out.println("== 行尾与边界 ==");
        eq("CRLF 行尾", stream(line01("X") + "\r\n" + line02("Y") + "\r\n").count(), 2);
        eq("末尾无换行", stream(line01("X")).count(), 1);
        eq("前后空白", stream("  " + line01("X") + "  \n").count(), 1);

        System.out.println("== 体积上限（4 MB）==");
        StringBuilder big = new StringBuilder();
        String one = line01("S") + "\n";
        for (int i = 0; i < 70000; i++) big.append(one);
        ok("构造体量超过 4 MB", big.length() > OfflineImport.MAX_BYTES);
        eq("超限 -> TOO_LARGE",
                OfflineImport.analyzeStream(
                        new ByteArrayInputStream(big.toString().getBytes(StandardCharsets.UTF_8)))
                        .status,
                OfflineImport.Status.TOO_LARGE);

        System.out.println("== null 输入 ==");
        eq("null 流 -> 没有拿到文件",
                OfflineImport.analyzeStream(null).status, OfflineImport.Status.NO_FILE);
        eq("null 文本 -> 文件为空",
                OfflineImport.analyze(null).status, OfflineImport.Status.EMPTY);

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}
