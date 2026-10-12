package io.github.wlmosv_png.wpaforge.scan;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 局域网设备探测执行器 —— 用 root 调系统自带二进制（ping / nc / ip / cat /proc/net/arp），
 * 逐行回传给 UI，模拟「终端在跑命令」的观感。
 *
 * <h3>为什么用 root + 系统命令而不是打包 nmap</h3>
 * 见 LanProbe 类注释。这里只负责把命令跑起来并把输出吐出来，
 * 判定（哪些主机存活、哪个端口开）由 UI 层按需成行打印。
 *
 * <h3>并发设计</h3>
 * ping 全段 254 个地址，串行要几分钟；这里分成 3 波并发，每波 ~40 个进程。
 * 实测全段约 7~10 秒，能接受。
 *
 * <h3>输出契约</h3>
 * 所有中间过程都会调用 {@code onLine(text)}，调用方把它追加进一个"终端视图"。
 * 区分正文与进度：正文（主机/端口结果）和进度（扫描百分比）都走 onLine，
 * 由前端判断前缀格式化。这里统一给纯文本，不做 UI 决策。
 */
public final class LanProbeExecutor {

    /** 逐行输出回调（在后台线程调用，UI 需自行 post） */
    public interface LineSink {
        void line(String text);
    }

    private static final int PING_TIMEOUT_MS = 1500;   // ping -W 毫秒
    /** 单个端口的 TCP connect 超时 */
    private static final int PORT_TIMEOUT_MS = 700;
    private static final int BATCH_SIZE      = 40;     // 每波并发 ping 数

    private LanProbeExecutor() {}

    /**
     * 拿当前 WiFi 的本机 IP。拿不到返回 null。
     */
    public static String selfIp(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            int ip = info.getIpAddress();
            if (ip == 0) return null;
            return String.format(java.util.Locale.US, "%d.%d.%d.%d",
                    (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 拿网关 IP：优先从路由表解析，失败返回 x.y.z.1 */
    public static String gatewayIp(String selfIp) {
        String g = null;
        try {
            Process p = Runtime.getRuntime().exec("ip route");
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("default")) {
                    String[] t = line.split("\\s+");
                    for (int i = 0; i < t.length - 1; i++) {
                        if ("via".equals(t[i])) { g = t[i + 1]; break; }
                    }
                }
            }
            p.waitFor();
            r.close();
        } catch (Throwable ignored) {
        }
        if (g != null && LanProbe.ipOnly(g) != null) return g;
        // 退化：同网段 .1
        String addr = LanProbe.ipOnly(selfIp);
        if (addr != null) {
            int dot = addr.lastIndexOf('.');
            if (dot > 0) return addr.substring(0, dot + 1) + "1";
        }
        return null;
    }

    /**
     * 全段并发 ping，检测存活，并从 /proc/net/arp 收集 MAC。
     * 回调逐台报告。返回存活主机列表（含 MAC/vendor 由调用方补全）。
     *
     * @param ip       本机 IP（用于排除自己）
     * @param gateway  网关 IP（标记）
     * @param sink     逐行回调
     */
    public static List<LanProbe.Host> pingSweep(final String ip, final String gateway,
                                                 final LineSink sink, final Context ctx) {
        List<String> hosts = LanProbe.hosts24(ip);
        final Map<String, Boolean> alive = new LinkedHashMap<>();
        final List<LanProbe.Host> result = new ArrayList<>();

        // 分波并发
        final int total = hosts.size();
        final int[] done = {0};
        for (int base = 0; base < total; base += BATCH_SIZE) {
            int end = Math.min(base + BATCH_SIZE, total);
            int n = end - base;
            final CountDownLatch latch = new CountDownLatch(n);
            for (int i = base; i < end; i++) {
                final String target = hosts.get(i);
                Thread t = new Thread(() -> {
                    try {
                        if (pingOk(target)) alive.put(target, Boolean.TRUE);
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                }, "wpa-lan-ping-" + target);
                t.setDaemon(true);
                t.start();
            }
            try {
                latch.await(PING_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            done[0] += n;
            int pct = (int) (((double) done[0] / total) * 100);
            if (sink != null) sink.line(String.format(
                    "[*] 扫描 %s...  %d%% ", ipPrefix(ip), pct) + "\u2588".repeat(Math.max(0, pct / 10)));
        }

        // ping 完等一小会儿再读 ARP 表。
        //
        // 为什么：ping 通过后内核才把 (IP -> MAC) 写进 ARP 表，
        // 立刻读会拿到空的 HW address —— 真机实测过，手动 ping 后
        // 稍等再读就有了。少了厂商识别，这个功能就废了一半。
        // 轮询读 ARP，直到网络里出现带 MAC 的表项（最多 3 轮 ×400ms）。
        //
        // 为什么轮询而不是固定 sleep：ping 通过后内核才把 (IP→MAC)
        // 写进 ARP 表，时间不确定；固定等 600ms 实测仍经常读到空值。
        for (int r = 0; r < 3; r++) {
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            if (arpHasMac()) break;
        }

        // 汇总：网关 + 本机 + 存活主机，各自构建 Host
        String gwMac = macOf(gateway);
        String myMac = macOf(ip);
        if (gateway != null) {
            result.add(new LanProbe.Host(gateway, gwMac,
                    vendorOf(ctx, gwMac), true, gateway.equals(ip)));
        }
        boolean selfIsGateway = ip != null && ip.equals(gateway);
        for (Map.Entry<String, Boolean> e : alive.entrySet()) {
            String a = e.getKey();
            if (a.equals(gateway)) continue;      // 网关已在上面
            if (a.equals(ip)) {
                // 把自己标出来
                result.add(new LanProbe.Host(a, myMac, vendorOf(ctx, myMac), false, true));
                continue;
            }
            String mac = macOf(a);
            result.add(new LanProbe.Host(a, mac, vendorOf(ctx, mac), false, false));
        }
        if (!selfIsGateway && ip != null && !ip.equals(gateway)) {
            // 确保本机总在列表（即使 ping 没标活）
            boolean hasSelf = false;
            for (LanProbe.Host h : result) if (h.isSelf) { hasSelf = true; break; }
            if (!hasSelf) result.add(new LanProbe.Host(ip, myMac, vendorOf(ctx, myMac), false, true));
        }
        return result;
    }

    private static String ipPrefix(String ip) {
        String a = LanProbe.ipOnly(ip);
        if (a == null) return "";
        int dot = a.lastIndexOf('.');
        return dot > 0 ? a.substring(0, dot) + ".x" : a;
    }

    private static boolean pingOk(String target) {
        try {
            Process p = new ProcessBuilder("ping", "-c", "1", "-W", "1", target)
                    .redirectErrorStream(true).start();
            // 等 1 秒
            p.waitFor(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return p.exitValue() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** ARP 表里是否已经有带 MAC 的表项 */
    private static boolean arpHasMac() {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new java.io.FileInputStream("/proc/net/arp"), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String[] t = line.trim().split("\\s+");
                if (t.length >= 4 && !"00:00:00:00:00:00".equals(t[3])
                        && !"HW".equals(t[0])) {
                    r.close();
                    return true;
                }
            }
            r.close();
        } catch (Throwable ignored) {}
        return false;
    }

    private static String macOf(String ip) {
        if (ip == null) return "";
        try {
            // 读 /proc/net/arp
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new java.io.FileInputStream("/proc/net/arp"), StandardCharsets.UTF_8));
            String line;
            String mac = "";
            while ((line = r.readLine()) != null) {
                String[] t = line.trim().split("\\s+");
                if (t.length >= 4 && t[0].equals(ip)) {
                    mac = t[3];
                    break;
                }
            }
            r.close();
            return "00:00:00:00:00:00".equals(mac) ? "" : mac.toUpperCase();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String vendorOf(Context ctx, String mac) {
        if (ctx == null || mac == null || mac.isEmpty()) return "";
        try {
            return OuiLookup.vendor(ctx, mac);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 对一个 IP 的常用端口做 nc 探测。逐行回调结果。
     * 返回开放端口列表。
     */
    public static List<Integer> portScan(final String ip, final LineSink sink) {
        List<Integer> ports = LanProbe.probePorts();
        List<Integer> open = new ArrayList<>();
        for (int p : ports) {
            boolean isOpen = ncCheck(ip, p);
            if (isOpen) open.add(p);
            if (sink != null) {
                sink.line(new LanProbe.Port(p, isOpen).line());
            }
        }
        return open;
    }

    /**
     * 抓取服务 banner（第一行）。拿不到返回空串。
     *
     * 两种模式：
     *   · 被动：很多服务（SSH/SMTP/FTP）连上就主动打招呼，直接读
     *   · 主动：HTTP/HTTPS 需要先发一个请求，否则永远等不到响应
     * 这里对 80/8080 发 HEAD，对 443/8443 不发（TLS 握手太复杂，跳过）。
     */
    public static String grabBanner(String ip, int port) {
        java.net.Socket sock = null;
        try {
            sock = new java.net.Socket();
            sock.connect(new java.net.InetSocketAddress(ip, port), 1200);
            sock.setSoTimeout(1200);

            // HTTP 类：先问一句，否则服务不会主动说话
            if (port == 80 || port == 8080) {
                try {
                    OutputStream os = sock.getOutputStream();
                    os.write(("HEAD / HTTP/1.0" + "\r\n" + "Host: " + ip + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    os.flush();
                } catch (Throwable ignored) {}
            } else if (port == 443 || port == 8443) {
                // TLS 端口不抓：握手要证书交换，超时又没意义
                return "";
            }

            BufferedReader r = new BufferedReader(new InputStreamReader(
                    sock.getInputStream(), StandardCharsets.ISO_8859_1));
            String line = r.readLine();
            if (line == null) return "";
            line = line.trim();
            if (line.length() > 60) line = line.substring(0, 60);
            return line;
        } catch (Throwable t) {
            return "";
        } finally {
            if (sock != null) try { sock.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 网络是否对任意目标都回 SYN-ACK（端口结果不可信）。
     *
     * 拿两个**确定不该有服务**的靶子试探：
     *   ① 本网段的空地址（+250）
     *   ② RFC 5737 保留地址 203.0.113.99（公网概念上不存在）
     * 任一报 open，就说明这个网络对任何目标都「答是」，端口扫描没有意义。
     *
     * @return true = 网络可信（端口结果可用）；false = 不可信，别展示端口
     */
    public static boolean portsAreTrustworthy(String selfIp) {
        // 靶子一：本网段空地址
        String probe1 = emptyAddrInSubnet(selfIp);
        if (probe1 != null && ncCheck(probe1, 80)) return false;
        if (probe1 != null && ncCheck(probe1, 9999)) return false;
        // 靶子二：RFC 5737 保留地址
        if (ncCheck("203.0.113.99", 80)) return false;
        return true;
    }

    /** 本网段里一个正常不会被分配的地址（+250） */
    private static String emptyAddrInSubnet(String selfIp) {
        String a = LanProbe.ipOnly(selfIp);
        if (a == null) return null;
        int dot = a.lastIndexOf('.');
        if (dot <= 0) return null;
        return a.substring(0, dot + 1) + "250";
    }

    /** 端口是否开放（public，供 UI 复用）。内部就是 nc -z。 */
    public static boolean portOpen(String ip, int port) {
        return ncCheck(ip, port);
    }

    /**
     * 端口是否开放。用 Java Socket 做真实 TCP connect。
     *
     * 不用 busybox `nc -z`：真机实测它对不存在的主机和端口
     * 同样报 open（nc -z 192.168.1.250 22 → OPEN，而这台主机不存在），
     * 结果完全不可信。Socket.connect 是内核 TCP 握手，连不上就抛异常。
     */
    private static boolean ncCheck(String ip, int port) {
        java.net.Socket sock = new java.net.Socket();
        try {
            sock.connect(new java.net.InetSocketAddress(ip, port), PORT_TIMEOUT_MS);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { sock.close(); } catch (Throwable ignored) {}
        }
    }

}