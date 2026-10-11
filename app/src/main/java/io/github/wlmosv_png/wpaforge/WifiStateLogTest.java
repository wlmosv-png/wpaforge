package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.WifiStateLog;

import java.util.List;

/**
 * WiFi 状态机日志解析的离线自测。
 *
 * 日志样本取自真机实测（Color OS 15 / Android 15，OnePlus PJX110）。
 * 这是本机判定命中的**主判据**，解析错了会导致「密码对也判不出」，
 * 必须锁死。
 */
public class WifiStateLogTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    /** 真机：用正确密码连上 B209 时的日志（成功证据） */
    private static final String SUCCESS_LOG = "" +
            "10-11 19:23:35.617  3734  4871 D WifiClientModeImpl[454191808:wlan0]:  ClientModeImpl$ConnectableState screen=on 12 0\n" +
            "10-11 19:23:35.631  3734  4871 D WifiClientModeImpl[454191808:wlan0]:  ClientModeImpl$ConnectableState screen=on 12 0\n" +
            "10-11 19:23:37.860  3734  4871 D WifiClientModeImpl[454191808:wlan0]:  ClientModeImpl$L2ConnectedState screen=on 36 0 \"B209\" 28:93:7d:45:dc:ed rssi=-48 f=2432 sc=60 link=300\n" +
            "10-11 19:23:39.875  3734  4871 D WifiClientModeImpl[454191808:wlan0]:  ClientModeImpl$L2ConnectedState screen=on 36 0 \"B209\" 28:93:7d:45:dc:ed rssi=-48 f=2432 sc=60 link=104";

    /** 真机：用错误密码连 B209 时，只出现「尝试」然后回连别的网络 */
    private static final String FAIL_LOG = "" +
            "10-11 19:22:18.326  3734  4871 D WifiClientModeImpl[454191808:wlan0]:  ClientModeImpl$ConnectableState screen=on 608 \"B209\"WPA_PSK nid=608 cuid=0 suid=0\n" +
            "10-11 19:22:18.327  3734  4871 D WifiClientModeImpl[454191808:wlan0]: setTargetBssid set to any key=\"B209\"WPA_PSK\n" +
            "10-11 19:22:39.963  3734  4871 D WifiClientModeImpl[454191808:wlan0]:  ClientModeImpl$L2ConnectedState screen=on \n" +
            "10-11 19:22:39.964  3734  4871 D WifiClientModeImpl[454191808:wlan0]:  ClientModeImpl$L2ConnectedState screen=on DhcpResultsParcelable{baseConfiguration: IP address 192.168.1.4/24 Gateway 192.168.1.1}\n" +
            "10-11 19:22:42.567  3734  4871 D WifiClientModeImpl[454191808:wlan0]:  ClientModeImpl$L3ConnectedState screen=on 1 0";

    /** 真机：错误密码时 AOSP 打的另一种「尝试」行格式（带 ssid: 前缀和 reasonCode） */
    private static final String FAIL_LOG_ALT = "" +
            "10-11 19:26:41.461  3734  4871 D WifiClientModeImpl[454191808:wlan0]:  ClientModeImpl$ConnectableState screen=on  ssid: \"B209\" bssid: 00:00:00:00:00:00 reasonCode: 2 locallyGenerated: false\n" +
            "10-11 19:26:44.100  3734  4871 D WifiClientModeImpl[454191808:wlan0]:  ClientModeImpl$L2ConnectedState screen=on 36 0 \"B217\" 28:93:7d:41:cd:dd rssi=-62";

    public static void main(String[] args) {
        System.out.println("== 成功场景（正确密码连上 B209）==");
        List<WifiStateLog.Event> evs = WifiStateLog.parse(SUCCESS_LOG, 0L);
        System.out.println("  提取事件数: " + evs.size());
        check("能提取到事件", !evs.isEmpty());
        check("识别到目标的「已连上」", WifiStateLog.sawConnectedFor(SUCCESS_LOG, "B209"));
        check("识别到目标的「尝试」", WifiStateLog.sawAttemptFor(SUCCESS_LOG, "B209"));
        check("别的 SSID 不算成功", !WifiStateLog.sawConnectedFor(SUCCESS_LOG, "B210"));
        check("lastConnectedSsid 正确", "B209".equals(WifiStateLog.lastConnectedSsid(SUCCESS_LOG)));

        System.out.println("== 失败场景（错误密码，回连别的网络）==");
        check("目标从未出现「已连上」", !WifiStateLog.sawConnectedFor(FAIL_LOG, "B209"));
        check("目标出现过「尝试」", WifiStateLog.sawAttemptFor(FAIL_LOG, "B209"));
        // 关键：无 SSID 的 L2ConnectedState（回连）绝不能算 B209 的成功
        check("回连别网的无 SSID L2ConnectedState 不算成功",
                !WifiStateLog.sawConnectedFor(FAIL_LOG, "B209"));
        check("lastConnectedSsid 为空（无带 SSID 的连接）",
                WifiStateLog.lastConnectedSsid(FAIL_LOG) == null);

        System.out.println("== 失败场景变体（ssid: 前缀 + 回连其它网络）==");
        check("带 ssid: 前缀的尝试行能识别",
                WifiStateLog.sawAttemptFor(FAIL_LOG_ALT, "B209"));
        check("回连 B217 不算 B209 成功",
                !WifiStateLog.sawConnectedFor(FAIL_LOG_ALT, "B209"));
        check("回连 B217 自身算它自己的成功",
                WifiStateLog.sawConnectedFor(FAIL_LOG_ALT, "B217"));

        System.out.println("== 边界 ==");
        check("null 输入返回空列表", WifiStateLog.parse(null).isEmpty());
        check("空串返回空列表", WifiStateLog.parse("").isEmpty());
        check("null SSID 查询不崩", !WifiStateLog.sawConnectedFor(SUCCESS_LOG, null));
        check("null 日志查询不崩", !WifiStateLog.sawConnectedFor(null, "B209"));
        check("垃圾行不崩", WifiStateLog.parse("garbage\n---\n").size() >= 0);

        System.out.println("== 带空格的 SSID ==");
        String spaced = "D WifiClientModeImpl[wlan0]:  ClientModeImpl$L2ConnectedState screen=on 36 0 "
                + "\"My Net 5G\" aa:bb:cc:dd:ee:ff rssi=-40";
        check("含空格 SSID 能正确解析",
                WifiStateLog.sawConnectedFor(spaced, "My Net 5G"));
        check("含空格 SSID 不误配其它名",
                !WifiStateLog.sawConnectedFor(spaced, "My Net"));

        System.out.println("\n=== wifi 状态日志解析测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
