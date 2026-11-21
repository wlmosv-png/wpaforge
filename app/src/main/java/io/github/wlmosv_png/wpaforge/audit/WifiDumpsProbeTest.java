package io.github.wlmosv_png.wpaforge.audit;

import java.util.List;

/**
 * {@link WifiDumpsProbe} 契约测试（2026-11-22 新增）。
 *
 * <p>用**真机原文**验证解析；并钉死「它不得参与判定」这条边界。
 */
public final class WifiDumpsProbeTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void ok(String name, boolean cond) { ok(name, cond, ""); }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /** 真机原文（PJX110 / ColorOS 15，逐字符照抄）。 */
    private static final String REAL =
            " rec[0]: time=11-22 01:01:15.896 processed=ConnectingOrConnectedState"
            + " org=L2ConnectingState dest=<null> what=NETWORK_DISCONNECTION_EVENT"
            + " screen=on  ssid: \"whoami\" bssid: 66:54:ef:ae:ce:80 reasonCode: 3"
            + " locallyGenerated: true\n"
            + " rec[24]: time=11-21 18:57:01.919 processed=L3ConnectedState"
            + " org=L3ConnectedState dest=DisconnectedState what=NETWORK_DISCONNECTION_EVENT"
            + " screen=off  ssid: \"227\" bssid: 74:26:ff:3a:08:26 reasonCode: -1"
            + " locallyGenerated: true\n"
            + " rec[98]: time=11-21 19:54:29.531 processed=L3ConnectedState"
            + " org=L3ConnectedState dest=DisconnectedState what=NETWORK_DISCONNECTION_EVENT"
            + " screen=on  ssid: \"B212\" bssid: 28:93:7d:41:cc:39 reasonCode: 3"
            + " locallyGenerated: false\n";

    public static void main(String[] args) {
        System.out.println("== dumpsys wifi 断开事件解析 ==");
        try {
            // ------------------------------------------------ 1. 真机原文解析
            System.out.println();
            System.out.println("-- 1. 真机原文解析 --");
            List<WifiDumpsProbe.Disconnect> list = WifiDumpsProbe.disconnects(REAL);
            info("解析出 " + list.size() + " 条");
            for (WifiDumpsProbe.Disconnect d : list) info("  " + d);

            ok("真机原文解析出 3 条", list.size() == 3, "" + list.size());
            ok("第 1 条 ssid=whoami", list.size() > 0 && "whoami".equals(list.get(0).ssid));
            ok("第 1 条 bssid 正确",
                    list.size() > 0 && "66:54:ef:ae:ce:80".equals(list.get(0).bssid));
            ok("第 1 条 reasonCode=3", list.size() > 0 && list.get(0).reasonCode == 3);
            ok("第 1 条 locallyGenerated=true",
                    list.size() > 0 && list.get(0).locallyGenerated);
            ok("第 2 条 reasonCode=-1",
                    list.size() > 1 && list.get(1).reasonCode == -1);
            ok("第 3 条 locallyGenerated=false（系统发起）",
                    list.size() > 2 && !list.get(2).locallyGenerated);
            ok("processed 字段被解析",
                    list.size() > 0
                            && "ConnectingOrConnectedState".equals(list.get(0).processed));

            // ------------------------------------------------ 2. 边界
            System.out.println();
            System.out.println("-- 2. 解析边界（不得因格式变化崩掉）--");
            ok("null → 空列表", WifiDumpsProbe.disconnects(null).isEmpty());
            ok("空串 → 空列表", WifiDumpsProbe.disconnects("").isEmpty());
            ok("无关行被忽略",
                    WifiDumpsProbe.disconnects("hello world\nfoo=bar\n").isEmpty());
            ok("缺 ssid 的断开事件被忽略",
                    WifiDumpsProbe.disconnects(
                            "what=NETWORK_DISCONNECTION_EVENT reasonCode: 3\n").isEmpty());
            ok("截断行不抛异常",
                    WifiDumpsProbe.disconnects(
                            "time=11-22 01:01:15.896 what=NETWORK_DISCONNECTION_EVENT"
                                    + " ssid: \"X\"\n").size() == 1);

            // ------------------------------------------------ 3. 统计
            System.out.println();
            System.out.println("-- 3. 按 SSID 统计非本应用发起的断开 --");
            ok("whoami 的非本地断开 = 0（第 1 条是 local）",
                    WifiDumpsProbe.nonLocalDisconnectsFor(list, "whoami") == 0);
            ok("B212 的非本地断开 = 1",
                    WifiDumpsProbe.nonLocalDisconnectsFor(list, "B212") == 1);
            ok("不存在的 SSID = 0",
                    WifiDumpsProbe.nonLocalDisconnectsFor(list, "NOPE") == 0);
            ok("null ssid = 0",
                    WifiDumpsProbe.nonLocalDisconnectsFor(list, null) == 0);

            // ------------------------------------------------ 4. 诊断文案
            System.out.println();
            System.out.println("-- 4. 诊断文案（只描述事实，不下结论）--");
            String d1 = WifiDumpsProbe.describe(list, "B212");
            info("B212: " + d1);
            ok("B212 文案含「系统在反复失败」", d1.contains("反复失败"));
            ok("B212 文案明确说「无法区分密码错与 AP 拒绝/信号差」",
                    d1.contains("无法区分"));
            String d2 = WifiDumpsProbe.describe(list, "whoami");
            info("whoami: " + d2);
            ok("whoami 只有本应用主动断开时不说「反复失败」",
                    !d2.contains("反复失败"));
            ok("无匹配 SSID → 空串",
                    WifiDumpsProbe.describe(list, "NOPE").isEmpty());

            // ------------------------------------------------ 5. 边界：不得参与判定
            System.out.println();
            System.out.println("-- 5. ★边界：本类不得参与判定（静态核对）--");
            String src = readSrc("audit/WifiDumpsProbe.java");
            ok("源码不含 Verdict 判定", !src.contains("Verdict.AUTH_FAIL"));
            ok("源码不含 DIRECT", !src.contains("Level.DIRECT"));
            ok("源码不含 persistable", !src.contains("persistable"));
            ok("注释明确写了「不参与判定」",
                    src.contains("不参与") || src.contains("只用于诊断"));

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== dumpsys 断开事件解析测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String readSrc(String rel) {
        try {
            String p = io.github.wlmosv_png.wpaforge.TestPaths.javaPkgDir() + rel;
            byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(p));
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
