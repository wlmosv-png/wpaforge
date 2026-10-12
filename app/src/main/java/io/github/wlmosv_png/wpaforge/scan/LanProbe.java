package io.github.wlmosv_png.wpaforge.scan;

import java.util.ArrayList;
import java.util.List;

/**
 * 局域网设备探测 —— 纯逻辑部分（不碰系统，可离线测试）。
 *
 * <h3>为什么单独拆出来</h3>
 * 探测分两半：
 *   1. 怎么算出网段、怎么从 ping 结果归纳存活主机、怎么排端口 —— 纯计算
 *   2. 怎么调 ping / nc / ip 拿原始输出 —— 系统命令
 *
 * 第 1 半不依赖 Android，写成纯方法可以像 WPAForge 其它算法一样
 * 在桌面 JVM 上跑测试。第 2 半（执行器）交给 LanProbeExecutor。
 *
 * <h3>设计约束</h3>
 * · 只依赖系统自带二进制（ping / nc / ip），不打包任何外部程序
 * · 隔离 WiFi 下也要能出结果：至少给出本机与网关，不死在"扫空"
 * · 厂商识别复用 {@link OuiLookup}
 */
public final class LanProbe {

    /** 一台被发现的主机 */
    public static final class Host {
        public final String ip;
        public final String mac;       // 空 = 没拿到 MAC
        public final String vendor;    // OUI 厂商名，空 = 未知
        public final boolean isGateway;
        public final boolean isSelf;   // 本机自己

        public Host(String ip, String mac, String vendor, boolean gateway, boolean self) {
            this.ip = ip == null ? "" : ip;
            this.mac = mac == null ? "" : mac;
            this.vendor = vendor == null ? "" : vendor;
            this.isGateway = gateway;
            this.isSelf = self;
        }

        public String displayVendor() {
            if (!vendor.isEmpty()) return vendor;
            return isSelf ? "本机" : "未知";
        }

        /** 紧凑展示名，给终端行用 */
        public String line() {
            StringBuilder sb = new StringBuilder();
            sb.append(ip);
            if (!mac.isEmpty()) sb.append("   ").append(mac);
            String v = displayVendor();
            if (!v.isEmpty()) sb.append("   [").append(v).append("]");
            if (isGateway) sb.append("   (网关)");
            if (isSelf) sb.append("   (本机)");
            return sb.toString();
        }
    }

    /** 一个端口的探测结果 */
    public static final class Port {
        public final int port;
        public final boolean open;
        public final String service;

        public Port(int port, boolean open) {
            this.port = port;
            this.open = open;
            this.service = guessService(port);
        }

        public String line() {
            return String.format(java.util.Locale.US, "%5d/tcp  %-6s  %s", port, open ? "open" : "closed", service);
        }
    }

    private LanProbe() {}

    /** 常见需要探测的端口：SSH / HTTP / HTTPS 等，控制台排前 */
    private static final int[] COMMON_PORTS = {
        22, 80, 443, 53, 445, 139, 23, 21, 3389, 8080, 8443, 3306, 6379, 5432, 9200
    };

    /**
     * 从一个点分 IP 推导 /24 网段里的所有主机地址（1..254）。
     * 例：192.168.1.7 -> ["192.168.1.1" ... "192.168.1.254"]
     */
    public static List<String> hosts24(String selfIp) {
        List<String> out = new ArrayList<>();
        String addr = ipOnly(selfIp);
        if (addr == null) return out;
        int dot = addr.lastIndexOf('.');
        if (dot < 0) return out;
        String prefix = addr.substring(0, dot + 1);
        int base;
        try {
            base = Integer.parseInt(addr.substring(dot + 1));
        } catch (NumberFormatException e) {
            return out;
        }
        // 不扫 .0 和 .255（网络号与广播），也不扫自己
        for (int i = 1; i <= 254; i++) {
            if (i == base) continue;
            out.add(prefix + i);
        }
        return out;
    }

    /** 取纯 IP（去掉可能的 mask/前缀），非法返回 null */
    public static String ipOnly(String s) {
        if (s == null) return null;
        s = s.trim();
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        if (!s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return null;
        String[] p = s.split("\\.");
        for (String o : p) {
            int v;
            try { v = Integer.parseInt(o); } catch (NumberFormatException e) { return null; }
            if (v < 0 || v > 255) return null;
        }
        return s;
    }

    /** 返回要探测的端口列表 */
    public static List<Integer> probePorts() {
        List<Integer> out = new ArrayList<>();
        for (int p : COMMON_PORTS) out.add(p);
        return out;
    }

    /** 端口号 -> 常见服务名（内置表，够终端展示用） */
    public static String guessService(int port) {
        switch (port) {
            case 22: return "ssh";
            case 23: return "telnet";
            case 21: return "ftp";
            case 80: return "http";
            case 443: return "https";
            case 53: return "dns";
            case 139: return "netbios-ssn";
            case 445: return "microsoft-ds";
            case 3389: return "rdp";
            case 8080: return "http-proxy";
            case 8443: return "https-alt";
            case 3306: return "mysql";
            case 6379: return "redis";
            case 5432: return "postgresql";
            case 9200: return "elasticsearch";
            default: return "unknown";
        }
    }
}