package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.ConfigRestorer;
import io.github.wlmosv_png.wpaforge.audit.SavedWifiReader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Android 8~10 兼容回归：WiFi 配置存储的**两条路径**与**两种字段结构**。
 *
 * <h3>被修的是什么（真实缺陷，不是假设）</h3>
 * <ol>
 *   <li><b>配置路径只认一条</b>：{@link ConfigRestorer} 写死
 *       {@code /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml}。
 *       那是 Android 11 把 Wi-Fi 变成 Mainline 模块之后的位置；
 *       Android 8~10 一直在 {@code /data/misc/wifi/}。旧代码在 10 及更早的机器上
 *       {@code cat} 必然失败 → 快照判「配置不可读」→ 收尾恢复一律
 *       {@code CONFIG_UNREADABLE}，等于**删掉配置还原不回去**。</li>
 *   <li><b>旧结构的字段读不出</b>：{@link SavedWifiReader#parseXml} 只认
 *       {@code <string name="SSID">}。若脚本按旧结构给出
 *       {@code <string name="KeyMgmt">WPA_PSK</string>}，安全类型会被判成兜底的
 *       {@code "WPA"}（旧代码判断的是 {@code ConfigKey}）—— 这条同样钉住。</li>
 * </ol>
 *
 * <p>本类全部是**纯逻辑**断言（喂 XML 文本 / 查源码常量），不碰设备、不改系统。
 */
public class ConfigStoreCompatTest {

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

    private static void eq(String name, Object want, Object got) {
        boolean c = (want == null) ? got == null : want.equals(got);
        if (!c) System.out.println("        want=" + want + " got=" + got);
        ok(name, c);
    }

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Android 8~10 的 WifiConfigStore.xml 片段（真实结构，字段名逐字照抄）。 */
    private static final String LEGACY_XML =
            "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n"
                    + "<WifiConfigStoreData>\n"
                    + "<int name=\"Version\" value=\"1\" />\n"
                    + "<NetworkList>\n"
                    + "  <Network>\n"
                    + "    <string name=\"ConfigKey\">&quot;MyHome&quot;WPA_PSK</string>\n"
                    + "    <string name=\"SSID\">&quot;MyHome&quot;</string>\n"
                    + "    <string name=\"PreSharedKey\">&quot;abc12345&quot;</string>\n"
                    + "    <string name=\"KeyMgmt\">WPA_PSK</string>\n"
                    + "    <int name=\"netId\" value=\"12\" />\n"
                    + "  </Network>\n"
                    + "  <Network>\n"
                    + "    <string name=\"ConfigKey\">&quot;FreeCafe&quot;NONE</string>\n"
                    + "    <string name=\"SSID\">&quot;FreeCafe&quot;</string>\n"
                    + "    <string name=\"KeyMgmt\">NONE</string>\n"
                    + "    <int name=\"netId\" value=\"13\" />\n"
                    + "  </Network>\n"
                    + "</NetworkList>\n"
                    + "</WifiConfigStoreData>\n";

    /** Android 11+ 的片段：没有 SSID 字段，SSID 只在 ConfigKey 里。 */
    private static final String MODERN_XML =
            "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n"
                    + "<WifiConfigStoreData>\n"
                    + "<NetworkList>\n"
                    + "  <Network>\n"
                    + "    <string name=\"ConfigKey\">&quot;Office-5G&quot;WPA_PSK</string>\n"
                    + "    <string name=\"PreSharedKey\">&quot;Str0ng!pass&quot;</string>\n"
                    + "    <int name=\"netId\" value=\"7\" />\n"
                    + "  </Network>\n"
                    + "</NetworkList>\n"
                    + "</WifiConfigStoreData>\n";

    /** 供本包外的测试访问包内可见的 ConfigKey 拆解（生产代码不受影响）。 */
    private static String ssidOf(String configKey) {
        try {
            java.lang.reflect.Method m = SavedWifiReader.class
                    .getDeclaredMethod("ssidFromConfigKey", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, configKey);
        } catch (Throwable t) {
            return "__REFLECT_FAIL__:" + t.getClass().getSimpleName();
        }
    }

    public static void main(String[] a) {
        System.out.println("== C1 配置文件路径：新旧两条都认 ==");
        // 路径一律经 TestPaths 解析（门禁钉死被测根；不许在本类写死目录）
        String restorer = read(TestPaths.under(
                "app/src/main/java/io/github/wlmosv_png/wpaforge/audit/ConfigRestorer.java"));
        ok("ConfigRestorer 可读", restorer != null);
        if (restorer != null) {
            ok("保留 Android 11+ 的 apexdata 路径",
                    restorer.contains("/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml"));
            ok("补上 Android 10- 的 /data/misc/wifi 路径",
                    restorer.contains("\"/data/misc/wifi/WifiConfigStore.xml\""));
            ok("读配置时两条路径都试（不是只留一条）",
                    restorer.contains("readIfReadable(CONFIG_XML)")
                            && restorer.contains("readIfReadable(CONFIG_XML_LEGACY)"));
            ok("读之前先判可读，别把 cat 的报错喂给解析器",
                    restorer.contains("test -r "));
        }

        System.out.println();
        System.out.println("== C2 旧结构（Android 8~10）：SSID / KeyMgmt 能读出来 ==");
        List<SavedWifiReader.Saved> legacy = SavedWifiReader.parseXml(LEGACY_XML);
        eq("解析出 2 条", 2, legacy.size());
        SavedWifiReader.Saved home = SavedWifiReader.find(legacy, "MyHome");
        ok("找到 MyHome", home != null);
        if (home != null) {
            eq("口令取到", "abc12345", home.password);
            eq("安全类型来自 KeyMgmt（旧代码会误判成 WPA）", "WPA-PSK", home.security);
        }
        SavedWifiReader.Saved cafe = SavedWifiReader.find(legacy, "FreeCafe");
        ok("开放网络也认出来", cafe != null);
        if (cafe != null) {
            ok("开放网络没有口令", !cafe.hasPassword());
        }

        System.out.println();
        System.out.println("== C3 新结构（Android 11+）：SSID 只在 ConfigKey 里也能读 ==");
        List<SavedWifiReader.Saved> modern = SavedWifiReader.parseXml(MODERN_XML);
        eq("解析出 1 条", 1, modern.size());
        SavedWifiReader.Saved office = SavedWifiReader.find(modern, "Office-5G");
        ok("找到 Office-5G（旧代码在无 SSID 字段时会整条丢弃）", office != null);
        if (office != null) {
            eq("口令取到", "Str0ng!pass", office.password);
            eq("安全类型来自 ConfigKey", "WPA-PSK", office.security);
        }

        System.out.println();
        System.out.println("== C4 ConfigKey 拆解：只按引号取，不猜 ==");
        eq("标准形态", "MyNet", ssidOf("\"MyNet\"WPA_PSK"));
        eq("含空格", "My Home 2.4", ssidOf("\"My Home 2.4\"WPA_PSK"));
        eq("只有安全类型 → null（不许把 NONE 当 SSID）",
                null, ssidOf("WPA_PSK"));
        eq("null 输入 → null", null, ssidOf(null));
        eq("空串 → null", null, ssidOf(""));

        System.out.println();
        System.out.println("== C5 不相关文本不会被当成配置 ==");
        eq("随便一段 HTML → 0 条", 0, SavedWifiReader.parseXml("<html><body>hi</body></html>").size());
        eq("null → 0 条", 0, SavedWifiReader.parseXml(null).size());

        System.out.println();
        System.out.println("=== 配置存储兼容: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
