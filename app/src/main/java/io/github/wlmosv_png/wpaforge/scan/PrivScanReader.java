package io.github.wlmosv_png.wpaforge.scan;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

/**
 * 特权扫描读取器。
 *
 * 为什么需要它：
 *   普通应用走 WifiManager.getScanResults() 拿周边网络，在部分 ROM
 *   （ColorOS / MIUI / EMUI 等）上会被扫描节流、权限策略或
 *   NEARBY_WIFI_DEVICES 的 neverForLocation 语义限制，
 *   返回**空列表但不报错** —— 界面只能显示"还没扫描到网络"，
 *   用户完全不知道为什么。
 *
 *   而本工具本来就已经有 root 通道（破解功能就靠它）。
 *   同一套权限可以直接执行 `cmd wifi list-scan-results`，
 *   拿到的是系统权威数据，实测比 getScanResults 更完整。
 *
 * 通道顺序（由 Scanner 决定何时降级）：
 *   1. 普通 API（快、无副作用）

 *   3. su（root）
 *
 * 输出格式（实测，Android 12~15 通用）：
 *   BSSID              Frequency      RSSI           Age(sec)     SSID                                 Flags
 *   28:93:7d:45:dc:ed       2432    -46(0:-49/1:-49)   169.150    B209      [WPA2-PSK-CCMP+TKIP][ESS][WPS]
 *
 * 注意 SSID 里可能含空格，所以按「BSSID 固定列 + 频率 + RSSI + Age」
 * 之后的位置切分，不能简单地 split 空白。
 */
public final class PrivScanReader {

    private static final String TAG = "WPAForgePrivScan";

    /** 本次读取用了哪个通道，供界面显示 */
    private static volatile String lastChannel = "";

    public static String lastChannel() {
        return lastChannel;
    }

    private PrivScanReader() {}

    /** 特权通道（Root）是否可用：只用一条极轻的命令探一下，不实际扫描 */
    public static boolean hasPrivilegedChannel() {
        try {
            String out = execRoot("id", 4000);
            return out != null && out.contains("uid=0");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 读一次扫描结果。root 通道失败时返回空列表（不抛异常）。 */
    public static List<NetRecord> read() {
        String raw = tryRoot();

        if (isEmpty(raw)) {
            lastChannel = "";
            return new ArrayList<>();
        }

        List<NetRecord> out = parse(raw);
        android.util.Log.i(TAG, "特权通道 " + lastChannel + " 解析出 " + out.size() + " 条");
        return out;
    }

    private static String tryRoot() {
        try {
            String out = execRoot("cmd wifi list-scan-results", 12000);
            if (!isEmpty(out) && out.contains("BSSID")) {
                lastChannel = "Root";
                return out;
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "Root 读取失败: " + t);
        }
        return null;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 以 root 身份跑一条命令，返回 stdout（失败返回 null） */
    private static String execRoot(String cmd, long timeoutMs) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                long deadline = System.currentTimeMillis() + timeoutMs;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (System.currentTimeMillis() > deadline) break;
                }
            }
            p.waitFor(300, TimeUnit.MILLISECONDS);
            return sb.toString();
        } catch (Throwable e) {
            return null;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 解析 `cmd wifi list-scan-results` 的输出。
     *
     * 行结构：BSSID 空白 Frequency 空白 RSSI(可能带括号) 空白 Age 空白 SSID 空白 Flags
     * SSID 可能含空格，所以用最后一个 '[' 处切分 SSID 与 Flags。
     */
    public static List<NetRecord> parse(String raw) {
        Map<String, NetRecord> best = new LinkedHashMap<>();
        if (raw == null) return new ArrayList<>();

        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("BSSID")) continue;           // 表头
            if (!s.matches("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\\s+.*")) continue;

            try {
                String[] parts = s.split("\\s+");
                // [0]=BSSID [1]=Freq [2]=RSSI [3]=Age 之后是 SSID 与 Flags
                if (parts.length < 5) continue;

                String bssid = parts[0].toUpperCase();
                int freq = Integer.parseInt(parts[1]);

                // RSSI 形如 -46(0:-49/1:-49) 或 -46
                int rssi = parseRssi(parts[2]);

                // 从 Age 之后取剩余内容
                String rest = s.substring(s.indexOf(parts[3]) + parts[3].length()).trim();
                String ssid;
                String flags;
                // flags 以第一个 '[' 开始（SSID 里不会含方括号），
                // 用最后一个 '[' 会把 "[WPS]" 之类当成 SSID 尾部，切错。
                int lb = rest.indexOf('[');
                if (lb >= 0) {
                    ssid = rest.substring(0, lb).trim();
                    flags = rest.substring(lb).trim();
                } else {
                    ssid = rest.trim();
                    flags = "";
                }
                if (ssid.length() > 64) ssid = ssid.substring(0, 64).trim();

                NetRecord rec = new NetRecord(
                        ssid,
                        bssid,
                        rssi,
                        freq,
                        NetRecord.parseSecurity(flags),
                        NetRecord.parseWps(flags),
                        flags);

                String key = rec.isHidden() ? rec.bssid : rec.ssid + "@" + rec.bssid;
                NetRecord prev = best.get(key);
                if (prev == null || rec.rssi > prev.rssi) {
                    best.put(key, rec);
                }
            } catch (Throwable ignored) {
                // 单行解析失败不影响整批
            }
        }

        List<NetRecord> out = new ArrayList<>(best.values());
        Collections.sort(out, (a, b) -> Integer.compare(b.rssi, a.rssi));
        return out;
    }

    private static int parseRssi(String token) {
        try {
            int paren = token.indexOf('(');
            String num = paren > 0 ? token.substring(0, paren) : token;
            return Integer.parseInt(num.trim());
        } catch (Throwable e) {
            return -100;
        }
    }
}
