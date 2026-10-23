package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.WpaLogReader;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 服务端预过滤（`logcat ... | grep -iE SHELL_FILTER`）的**完备性**测试。
 *
 * <p>为什么这个测试重要：日志预过滤把每轮传输量从 33KB 压到 0.6KB（50 倍），
 * 但代价是「过滤式漏掉一类行 = 静默丢一条判定」。丢的是失败判据就会变慢，
 * 丢的是成功判据就可能**漏判**。所以这里逐条钉住：
 * {@link WpaLogReader} 里每个 Pattern 认得的样例行，都必须被 SHELL_FILTER 放行。
 *
 * <p>将来新增 Pattern 时，这个测试会立刻红 —— 那一刻请同步更新 SHELL_FILTER。
 */
public class WpaFilterTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    /** 判定要用的 7 类原始日志行（照真机格式写的样例） */
    private static final String[] NEEDED = {
            "wlan0: Trying to associate with SSID 'B212'",
            "wlan0: WPA: Key negotiation completed with 28:93:7d:11:22:01 [PTK=CCMP GTK=CCMP]",
            "wlan0: WPA: 4-Way Handshake failed - pre-shared key may be incorrect",
            "wlan0: CTRL-EVENT-ASSOC-REJECT bssid=28:93:7d:11:22:01 status_code=1",
            "wlan0: WPA: RX message 1 of 4-Way Handshake from 28:93:7d:11:22:01 (ver=2)",
            "wlan0: CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"B212\" auth_failures=1 "
                    + "duration=10 reason=WRONG_KEY",
            "wlan0: CTRL-EVENT-CONNECTED - Connection to 28:93:7d:11:22:01 completed [id=0 id_str=]",
            "wlan0: RSN: RX message 1 of 4-Way Handshake from 28:93:7d:11:22:01 (ver=2)",
    };

    /** 该被丢掉的噪音（本机一轮 293 行里绝大多数是这类） */
    private static final String[] NOISE = {
            "wlan0: CTRL-EVENT-SCAN-RESULTS",
            "wlan0: nl80211: Received scan results (12 bsses)",
            "wlan0: Setting scan request: 9.000000 sec",
            "wlan0: BSS: Add new id 7 BSSID 28:93:7d:11:22:03",
    };

    public static void main(String[] args) {
        System.out.println("== 日志预过滤完备性 ==");

        Pattern filter = Pattern.compile(WpaLogReader.SHELL_FILTER, Pattern.CASE_INSENSITIVE);

        // 1) ★ 不变式：判定认得的每一类行，都必须过得了过滤
        int missing = 0;
        for (String line : NEEDED) {
            if (!filter.matcher(line).find()) {
                missing++;
                System.out.println("       被过滤式漏掉: " + line);
            }
        }
        check("判定用的 7 类行全部放行（漏 " + missing + " 类）", missing == 0);

        // 1a) ★ 覆盖守卫（发布前复核新增）：原来这里自称"将来新增 Pattern 时这个测试会
        //     立刻红"，但**没有任何机制保证** —— 样例是硬编码的 7 行，新增一个 Pattern
        //     却忘了同步 SHELL_FILTER 时，本测试照样绿（静默丢判据 = 过滤遗漏）。
        //     现在把 WpaLogReader 里的 Pattern 字段与"是否被过滤式覆盖"显式绑定：
        //     源码里出现任何未登记的 P_ 字段即判红。
        String src = readSource("app/src/main/java/io/github/wlmosv_png/wpaforge/audit/"
                + "WpaLogReader.java");
        java.util.Set<String> declared = new java.util.LinkedHashSet<>();
        if (src != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("Pattern\\s+(P_[A-Z_0-9]+)\\s*=").matcher(src);
            while (m.find()) declared.add(m.group(1));
        }
        // 每个 Pattern 字段 → 是否必须被 SHELL_FILTER 覆盖（true）、或为什么不需要
        java.util.Map<String, String> registry = new java.util.LinkedHashMap<>();
        registry.put("P_ASSOC", "1");
        registry.put("P_NEGOTIATED", "1");
        registry.put("P_HANDSHAKE_FAIL", "1");
        registry.put("P_ASSOC_REJECT", "1");
        registry.put("P_HANDSHAKE_M1", "1");
        registry.put("P_WRONG_KEY", "1");
        registry.put("P_CONNECTED", "1");
        // P_TS 作用于**已经过过滤的**文本（M1 去重取时间戳），不经 SHELL_FILTER：
        registry.put("P_TS", "0");
        check("源码里的 Pattern 字段全部已登记（漏登记即判红）",
                src != null && declared.equals(registry.keySet()));
        if (src != null) {
            java.util.Set<String> missingReg = new java.util.LinkedHashSet<>(declared);
            missingReg.removeAll(registry.keySet());
            if (!missingReg.isEmpty()) System.out.println("       未登记: " + missingReg);
            java.util.Set<String> stale = new java.util.LinkedHashSet<>(registry.keySet());
            stale.removeAll(declared);
            if (!stale.isEmpty()) System.out.println("       已删除: " + stale);
        }
        int needFilter = 0;
        for (String v : registry.values()) if ("1".equals(v)) needFilter++;
        check("需要过滤式覆盖的 Pattern = 7 个（其余必须显式说明原因）", needFilter == 7);
        check("P_TS 明确标注为不参与过滤式（它作用于已过滤文本）",
                "0".equals(registry.get("P_TS")));


        // 1b) R6：起点标记也要过同一个过滤式，否则窗口隔离无从证明。
        //     标记与证据必须来自同一次 logcat 快照（见 WpaLogReader.readCommand）。
        java.lang.String markerLine = io.github.wlmosv_png.wpaforge.audit.AttemptMarker
                .line("m5k3p1-3f9a2b7c-1#11");
        check("起点标记放行", markerLine != null && filter.matcher(markerLine).find());
        check("读取命令同时取 wpa_supplicant 与标记 tag",
                WpaLogReader.readCommand().contains("-s wpa_supplicant")
                        && WpaLogReader.readCommand().contains(
                                io.github.wlmosv_png.wpaforge.audit.AttemptMarker.TAG));

        // 2) 噪音该被丢掉（这就是 50 倍收益的来源）
        int kept = 0;
        for (String line : NOISE) {
            if (filter.matcher(line).find()) kept++;
        }
        check("噪音行全部被挡（放过 " + kept + " 行）", kept == 0);

        // 3) parse 在过滤后的输入上仍认得全（预过滤不改变判定语义）
        StringBuilder sb = new StringBuilder();
        for (String line : NEEDED) sb.append(line).append('\n');
        String filtered = sb.toString();
        List<WpaLogReader.LogEvent> ev = WpaLogReader.parse(filtered);
        int assoc = 0, neg = 0, failEv = 0, m1 = 0;
        for (WpaLogReader.LogEvent e : ev) {
            if ("associate".equals(e.kind)) assoc++;
            else if ("negotiated".equals(e.kind)) neg++;
            else if ("handshake_failed".equals(e.kind)) failEv++;
            else if ("handshake_m1".equals(e.kind)) m1++;
        }
        check("过滤后仍解析出关联行", assoc >= 1);
        check("过滤后仍解析出协商完成", neg >= 1);
        check("过滤后仍解析出握手失败", failEv >= 1);
        check("过滤后仍解析出 M1（含 RSN 变体）", m1 >= 2);

        // 4) 用现有判据函数核对（真正被引擎调用的那几个）
        check("sawAssociateFor 命中", WpaLogReader.sawAssociateFor(filtered, "B212"));
        check("sawNegotiatedFor 命中", WpaLogReader.sawNegotiatedFor(filtered, "28:93:7d:11:22:01"));
        check("sawWrongKeyFor 命中", WpaLogReader.sawWrongKeyFor(filtered, "B212"));
        check("sawHandshakeFailure 命中", WpaLogReader.sawHandshakeFailure(filtered));
        check("sawAssocReject 命中", WpaLogReader.sawAssocReject(filtered));
        check("sawHandshakeM1 命中", WpaLogReader.sawHandshakeM1(filtered));
        check("countHandshakeM1For 计数 = 2",
                WpaLogReader.countHandshakeM1For(filtered, "28:93:7d:11:22:01") == 2);

        // 5) 空/异常输入不炸
        check("空串不炸", WpaLogReader.parse("").isEmpty());
        check("只有噪音 → 无事件",
                WpaLogReader.parse("wlan0: CTRL-EVENT-SCAN-RESULTS\n").isEmpty());

        System.out.println();
        System.out.println("日志预过滤: pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /** 读项目源码（相对项目根）。读不到返回 null，调用方按 NOT RUN 处理。 */
    private static String readSource(String rel) {
        try {
            return new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("/workspace/promo-wpaforge/" + rel)),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
