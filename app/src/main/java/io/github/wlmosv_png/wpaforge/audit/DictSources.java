package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 在线字典源（2026-10-23 重做）。
 *
 * 三条设计原则：
 *
 *   1. **只提供入口，不打包数据。** 所有链接指向公开仓库，下载与否由用户决定，
 *      数据落在本机，与本应用仓库无关。各仓库许可状态在条目上如实标注 ——
 *      其中多数仓库**没有声明许可**，界面文案不能说成 MIT。
 *
 *   2. **国内网络可用。** raw.githubusercontent.com 在部分网络下直连不通，
 *      所以每条链接都会展开成多条通道：3 个 GitHub 代理 + jsDelivr 的
 *      国内可用 CDN + 直连。下载前**并行探测**，谁快用谁；本会话记住
 *      冠军通道，后续下载直接用，不再重复探测。
 *
 *   3. **按类型分组，而不是堆大表。** 在线关联式爆破每个候选都要真连一次 AP，
 *      实测约 1.5 条/秒 —— 10 万条要跑 18 小时，100 万条要 8 天。
 *      所以界面上必须写清「约 N 条 ≈ 跑多久」，否则用户下了百万大表
 *      只会以为软件卡死。{@link #estimateText(int)} 负责这件事。
 */
public final class DictSources {

    public static final class Source {
        public final String name;
        public final String desc;
        public final String url;
        public final String license;
        /** 形如「约 2492 条」，由条数自动生成 */
        public final String sizeHint;
        /** 分类，界面上按它分组 */
        public final String category;
        /** 实测/估算条数，用来算在线跑完要多久；0 = 未知 */
        public final int entries;

        public Source(String name, String desc, String url, String license, String sizeHint) {
            this.name = name;
            this.desc = desc;
            this.url = url;
            this.license = license;
            this.sizeHint = sizeHint;
            this.category = "";
            this.entries = 0;
        }

        public Source(String name, String desc, String url, String license,
                      String category, int entries) {
            this.name = name;
            this.desc = desc;
            this.url = url;
            this.license = license;
            this.category = category;
            this.entries = entries;
            this.sizeHint = entries > 0
                    ? L.s("约 " + entries + " 条", "~" + entries + " entries")
                    : "";
        }
    }

    // ── 分类标签 ────────────────────────────────────────────────
    // 注意：不用 static final 常量存 L.s(...) —— 那会在类加载时就求值，
    // 可能早于 L.init()，中文用户会看到英文。改成方法，调用时才求值。

    public static String catWifi() { return L.s("WiFi / WPA 专用", "WiFi / WPA specific"); }
    public static String catCn()   { return L.s("中文 / 国内常用", "Chinese / domestic"); }
    public static String catNum()  { return L.s("数字 / 生日 / 手机号", "Digits / birthdays / phones"); }
    public static String catDev()  { return L.s("设备与路由器默认口令", "Device & router defaults"); }
    public static String catGen()  { return L.s("通用高频", "Common high-frequency"); }

    /** 源仓库根地址（全部实测可下载，条数为 2026-10-23 实际统计） */
    private static final String SL  = "https://raw.githubusercontent.com/danielmiessler/SecLists/master/";
    private static final String WPA = "https://raw.githubusercontent.com/conwnet/wpa-dictionary/master/";
    private static final String DOP = "https://raw.githubusercontent.com/insightglacier/Dictionary-Of-Pentesting/master/";
    private static final String RX  = "https://raw.githubusercontent.com/oops4git/RedteamNotes-Wordlists-Chinese/main/";
    private static final String K8  = "https://raw.githubusercontent.com/k8gege/PasswordDic/master/";
    private static final String WFD = "https://raw.githubusercontent.com/tonyxiahua/WifiDic/master/";

    private static final String UA = "WPAForge/1.0";

    /**
     * GitHub 代理前缀（按实测可用性挑选）。
     *
     * 2026-10-23 在江苏移动网络实测：
     *   raw 直连 206 / 0.45s、ghproxy.net 206 / 0.94s、ghfast.top 206 / 0.64s、
     *   gh.llkk.cc 206 / 0.53s、jsdelivr(3 个域名) 206 / 1.1~2.8s；
     *   gh-proxy.com、hub.gitmirror.com、ghproxy.cc 全部拨不通，已移除 ——
     *   留着只会让每次下载白等一个 10 秒超时。
     *
     * 空字符串表示直连原站。实际顺序由 {@link #probeBest} 在下载前实测决定。
     */
    private static final String[] MIRRORS = {
            "https://ghproxy.net/",
            "https://ghfast.top/",
            "https://gh.llkk.cc/",
            "",
    };

    private static final String[] JSDELIVR = {
            "https://cdn.jsdelivr.net/",
            "https://gcore.jsdelivr.net/",
            "https://fastly.jsdelivr.net/",
    };

    /** 本会话实测最快的通道前缀；null = 还没测过 */
    private static volatile String bestPrefix = null;

    /** 非 ASCII 与空格做百分号编码 —— 仓库里有中文文件名和带空格的路径 */
    public static String encodeUrl(String url) {
        if (url == null) return null;
        StringBuilder sb = new StringBuilder(url.length() + 32);
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == ' ') {
                sb.append("%20");
            } else if (c < 128) {
                sb.append(c);
            } else {
                byte[] bs;
                try {
                    bs = String.valueOf(c).getBytes("UTF-8");
                } catch (Exception e) {
                    sb.append(c);
                    continue;
                }
                for (byte b : bs) {
                    sb.append('%');
                    sb.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
                    sb.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
            }
        }
        return sb.toString();
    }

    /** raw.githubusercontent.com/<owner>/<repo>/<branch>/<path> → jsDelivr 的 gh/<owner>/<repo>@<branch>/<path> */
    public static String jsdelivrPath(String rawUrl) {
        final String P = "https://raw.githubusercontent.com/";
        if (rawUrl == null || !rawUrl.startsWith(P)) return null;
        String[] parts = rawUrl.substring(P.length()).split("/", 4);
        if (parts.length < 4) return null;
        return "gh/" + parts[0] + "/" + parts[1] + "@" + parts[2] + "/" + parts[3];
    }

    /** 一个原始 URL 展开出的全部候选通道（直连 / 代理 / CDN） */
    public static String[] candidateUrls(String rawUrl) {
        String enc = encodeUrl(rawUrl);
        List<String> out = new ArrayList<>();
        for (String m : MIRRORS) {
            out.add(m.isEmpty() ? enc : m + enc);
        }
        String jd = jsdelivrPath(enc);
        if (jd != null) {
            for (String d : JSDELIVR) out.add(d + jd);
        }
        return out.toArray(new String[0]);
    }

    /** 通道名，用于在界面上告诉用户实际走的哪条路 */
    public static String mirrorLabel(String url) {
        if (url == null) return "";
        if (url.contains("ghproxy.net")) return "ghproxy.net";
        if (url.contains("ghfast.top")) return "ghfast.top";
        if (url.contains("gh.llkk.cc")) return "gh.llkk.cc";
        if (url.contains("jsdelivr.net")) return "jsDelivr";
        if (url.startsWith("https://raw.githubusercontent.com")) return L.s("GitHub 直连", "GitHub direct");
        return "";
    }

    /** URL 属于哪条通道（用于记住冠军通道） */
    public static String prefixOf(String url) {
        if (url == null) return "";
        for (String m : MIRRORS) {
            if (!m.isEmpty() && url.startsWith(m)) return m;
        }
        for (String d : JSDELIVR) {
            if (url.startsWith(d)) return d;
        }
        return "";
    }

    /**
     * 并行探测所有通道（只取头部 1KB），按响应快慢挑出最快的。
     * 全都不通时返回 null，交给调用方按原顺序硬试。
     *
     * 为什么要并行：串行逐个试，最坏情况要等 4 个超时（40 秒）才开始下载。
     */
    static String probeBest(String[] urls) {
        if (urls == null || urls.length == 0) return null;
        final Object lock = new Object();
        final String[] best = {null};
        final long[] bestMs = {Long.MAX_VALUE};
        List<Thread> threads = new ArrayList<>();
        for (final String u : urls) {
            Thread t = new Thread(() -> {
                long t0 = System.currentTimeMillis();
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", UA);
                    c.setRequestProperty("Range", "bytes=0-1023");
                    int code = c.getResponseCode();
                    if (code == 200 || code == 206) {
                        long ms = System.currentTimeMillis() - t0;
                        synchronized (lock) {
                            if (ms < bestMs[0]) { bestMs[0] = ms; best[0] = u; }
                        }
                    }
                } catch (Throwable ignored) {
                    // 这条通道不通，忽略
                } finally {
                    if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
                }
            });
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            try { t.join(6000); } catch (Throwable ignored) {}
        }
        synchronized (lock) { return best[0]; }
    }

    /** 把上次实测最快的通道提到最前，其余保持原顺序作为回退 */
    static String[] orderedUrls(String rawUrl) {
        String[] urls = candidateUrls(rawUrl);
        String best = bestPrefix;
        if (best == null) {
            String probed = probeBest(urls);
            if (probed != null) {
                best = prefixOf(probed);
                bestPrefix = best;
            }
        }
        if (best == null) return urls;

        List<String> out = new ArrayList<>();
        for (String u : urls) if (best.equals(prefixOf(u))) out.add(u);
        for (String u : urls) if (!out.contains(u)) out.add(u);
        return out.toArray(new String[0]);
    }

    /**
     * 在线关联式爆破的实测速率（条/秒）。
     * 每个候选都要真的发一次关联请求并等结果，不是本地哈希比对 ——
     * 所以「条数」必须翻译成「要跑多久」给用户看。
     */
    public static final double RATE_PER_SEC = 1.5;

    /** 把条数翻译成可读时长 */
    public static String estimateText(int entries) {
        if (entries <= 0) return "";
        long sec = (long) (entries / RATE_PER_SEC);
        if (sec < 60) return L.s("约 " + sec + " 秒", "~" + sec + " s");
        if (sec < 3600) return L.s("约 " + (sec / 60) + " 分钟", "~" + (sec / 60) + " min");
        if (sec < 86400) return L.s("约 " + (sec / 3600) + " 小时", "~" + (sec / 3600) + " h");
        return L.s("约 " + (sec / 86400) + " 天", "~" + (sec / 86400) + " d");
    }

    /** 超过这个条数，界面上要提醒「在线跑不完」 */
    public static final int HUGE_ENTRIES = 100000;

    /**
     * 全部在线源。按分类返回，界面按 category 分组即可。
     *
     * 条数不是估的 —— 2026-10-23 逐个下载统计（含长度过滤前的原始行数）。
     */
    public static List<Source> list() {
        List<Source> out = new ArrayList<>();

        // ── WiFi / WPA 专用 ─────────────────────────────────────
        out.add(new Source(
                L.s("WPA 中文高频 common", "WPA Chinese high-frequency: common"),
                L.s("conwnet/wpa-dictionary 的主表，专为 WPA 握手收集，最贴合本工具",
                        "Main table of conwnet/wpa-dictionary, collected for WPA handshakes — best fit"),
                WPA + "common.txt", L.s("未声明", "Not declared"), catWifi(), 2492));

        out.add(new Source(
                L.s("WPA 中文 other-0", "WPA Chinese: other-0"),
                L.s("同上作者的补充表，与主表不重复", "Companion table by the same author, no overlap"),
                WPA + "other-0.txt", L.s("未声明", "Not declared"), catWifi(), 2188));

        out.add(new Source(
                L.s("WPA 中文 other-2", "WPA Chinese: other-2"),
                L.s("更大一圈的补充表", "A larger companion table"),
                WPA + "other-2.txt", L.s("未声明", "Not declared"), catWifi(), 14387));

        out.add(new Source(
                L.s("WPA 中文大合集 other-1", "WPA Chinese: other-1 (large)"),
                L.s("16.8 万条，在线跑约 31 小时 —— 建议先跑小表", "168k entries; ~31 h online — run small tables first"),
                WPA + "other-1.txt", L.s("未声明", "Not declared"), catWifi(), 168068));

        out.add(new Source(
                L.s("WPA 专用 TOP 4800", "WPA-specific TOP 4800"),
                L.s("SecLists 为 WPA 握手统计的高频口令", "SecLists' high-frequency list for WPA handshakes"),
                SL + "Passwords/WiFi-WPA/probable-v2-wpa-top4800.txt", "MIT", catWifi(), 4800));

        out.add(new Source(
                L.s("WPA 专用 TOP 447", "WPA-specific TOP 447"),
                L.s("上面那份的精简版，5 分钟能跑完", "Trimmed version — finishes in ~5 minutes"),
                SL + "Passwords/WiFi-WPA/probable-v2-wpa-top447.txt", "MIT", catWifi(), 447));

        out.add(new Source(
                L.s("WPA 专用 TOP 62", "WPA-specific TOP 62"),
                L.s("最小的起步表，不到一分钟", "Smallest starter list, under a minute"),
                SL + "Passwords/WiFi-WPA/probable-v2-wpa-top62.txt", "MIT", catWifi(), 62));

        out.add(new Source(
                L.s("WiFi 弱口令 2000", "WiFi weak passphrases 2000"),
                L.s("Dictionary-Of-Pentesting 的 Wifi 分类表", "WiFi category table from Dictionary-Of-Pentesting"),
                DOP + "Wifi/wifipass.txt", L.s("未声明", "Not declared"), catWifi(), 1999));

        // ── 中文 / 国内常用 ─────────────────────────────────────
        out.add(new Source(
                L.s("中文口令 TOP 100", "Chinese passphrases TOP 100"),
                L.s("按命中概率分层的中文表，最小的起步档", "Hit-rate layered Chinese list — smallest tier"),
                RX + "redteamnotes-wordlists-chinese-100.txt", L.s("未声明", "Not declared"), catCn(), 99));

        out.add(new Source(
                L.s("中文口令 TOP 365", "Chinese passphrases TOP 365"),
                L.s("同上第二层", "Second tier of the same set"),
                RX + "redteamnotes-wordlists-chinese-365.txt", L.s("未声明", "Not declared"), catCn(), 369));

        out.add(new Source(
                L.s("中文口令 TOP 500", "Chinese passphrases TOP 500"),
                L.s("同上第三层", "Third tier"),
                RX + "redteamnotes-wordlists-chinese-500.txt", L.s("未声明", "Not declared"), catCn(), 525));

        out.add(new Source(
                L.s("中文口令 TOP 1000", "Chinese passphrases TOP 1000"),
                L.s("日常够用的一档，约 14 分钟", "Plenty for everyday use — ~14 minutes"),
                RX + "redteamnotes-wordlists-chinese-1000.txt", L.s("未声明", "Not declared"), catCn(), 1230));

        out.add(new Source(
                L.s("中文口令 TOP 1 万", "Chinese passphrases TOP 10k"),
                L.s("约 1.9 小时", "~1.9 hours"),
                RX + "redteamnotes-wordlists-chinese-10000.txt", L.s("未声明", "Not declared"), catCn(), 10001));

        out.add(new Source(
                L.s("全拼字典 16.6 万", "Full pinyin dictionary 166k"),
                L.s("中文拼音口令，跑完约 31 小时", "Pinyin passphrases; ~31 h to run"),
                RX + "redteamnotes-wordlists-chinese-pinyin.txt", L.s("未声明", "Not declared"), catCn(), 166056));

        out.add(new Source(
                L.s("中文弱口令集 3 万", "Chinese weak passphrases 31k"),
                L.s("WifiDic 整理的弱口令集，约 5.7 小时", "Weak-passphrase set from WifiDic — ~5.7 h"),
                WFD + "新字典6-弱口令集.txt", L.s("未声明", "Not declared"), catCn(), 30973));

        out.add(new Source(
                L.s("国内 TOP 1000", "Domestic TOP 1000"),
                L.s("k8gege 整理的 2011-2019 弱口令", "Weak passphrases 2011-2019, curated by k8gege"),
                K8 + "top1000.txt", L.s("未声明", "Not declared"), catCn(), 1000));

        out.add(new Source(
                L.s("国内 TOP 100（简）", "Domestic TOP 100 (short)"),
                L.s("最快的一份国内表", "Fastest domestic table"),
                K8 + "top100_cn.txt", L.s("未声明", "Not declared"), catCn(), 99));

        out.add(new Source(
                L.s("乌云弱口令 322", "WooYun weak passphrases 322"),
                L.s("来自乌云历史事件统计", "From historical WooYun incident statistics"),
                DOP + "Other/乌云弱口令.txt", L.s("未声明", "Not declared"), catCn(), 322));

        out.add(new Source(
                L.s("TOP 500 弱口令", "TOP 500 weak passphrases"),
                L.s("通用 500 条，约 6 分钟", "Generic 500 — ~6 minutes"),
                DOP + "Other/top500弱口令.txt", L.s("未声明", "Not declared"), catCn(), 501));

        out.add(new Source(
                L.s("12306 泄露 TOP 100", "12306 leak TOP 100"),
                L.s("13 万条泄露里挑出的前 100", "Top 100 from a 130k-entry leak"),
                DOP + "Other/12306 13W泄露密码，弱口令TOP100.txt", L.s("未声明", "Not declared"), catCn(), 99));

        // ── 数字 / 生日 / 手机号 ────────────────────────────────
        out.add(new Source(
                L.s("生日 dd-mm-yyyy 2.7 万", "Birthdays dd-mm-yyyy 27k"),
                L.s("日-月-年格式，约 5 小时", "Day-month-year format — ~5 h"),
                WPA + "birthday_ddmmyyyy.txt", L.s("未声明", "Not declared"), catNum(), 26718));

        out.add(new Source(
                L.s("生日全集 53.9 万", "All birthdays 539k"),
                L.s("多种格式的生日组合，在线跑不完（约 4.2 天）", "Birthday combos in many formats; ~4.2 days online"),
                WPA + "birthday.txt", L.s("未声明", "Not declared"), catNum(), 539314));

        out.add(new Source(
                L.s("生日 1955-2015 43.9 万", "Birthdays 1955-2015 439k"),
                L.s("国内最常用的生日区间，约 3.4 天", "Most common domestic birth-year range — ~3.4 days"),
                WFD + "新字典8-1955-2015生日密码.txt", L.s("未声明", "Not declared"), catNum(), 438660));

        out.add(new Source(
                L.s("十位数字字典 23.8 万", "Ten-digit numeric dictionary 238k"),
                L.s("纯十位数字的常见排列，约 1.8 天", "Common ten-digit permutations — ~1.8 days"),
                WFD + "字典2-十位数字字典.txt", L.s("未声明", "Not declared"), catNum(), 238328));

        out.add(new Source(
                L.s("生日+弱密码+单词 2.7 万", "Birthday + weak + words 27k"),
                L.s("混合型，约 5 小时", "Mixed set — ~5 h"),
                WFD + "新字典5-生日+弱密码+常用单词字典.txt", L.s("未声明", "Not declared"), catNum(), 27220));

        out.add(new Source(
                L.s("中国手机号 103 万", "Chinese mobile numbers 1.03M"),
                L.s("号段 × 全排列，在线跑不完（约 8 天）", "Segment × permutations; ~8 days online"),
                DOP + "Other/cnmobilepassword.txt", L.s("未声明", "Not declared"), catNum(), 1035749));

        // ── 设备与路由器默认口令 ────────────────────────────────
        out.add(new Source(
                L.s("路由器出厂弱口令 444", "Router factory defaults 444"),
                L.s("400+ 常见路由器/光猫出厂口令，几分钟跑完", "400+ common router/ONT factory passwords — minutes"),
                DOP + "Device/400+_factory_default_router_pass_list.txt", L.s("未声明", "Not declared"), catDev(), 444));

        out.add(new Source(
                L.s("设备默认口令 2042", "Device default credentials 2042"),
                L.s("摄像头、路由器、打印机等的默认口令", "Defaults for cameras, routers, printers, etc."),
                DOP + "Device/default_password_list.txt", L.s("未声明", "Not declared"), catDev(), 2042));

        out.add(new Source(
                L.s("设备口令表 3411", "Device password table 3411"),
                L.s("含厂商与型号标注的 CSV", "CSV with vendor and model annotations"),
                DOP + "Device/passwords.csv", L.s("未声明", "Not declared"), catDev(), 3411));

        out.add(new Source(
                L.s("默认凭据速查 3525", "Default credentials cheat sheet 3525"),
                L.s("DefaultCreds-cheat-sheet：厂商/用户名/口令", "DefaultCreds cheat sheet: vendor / user / password"),
                DOP + "Other/DefaultCreds-cheat-sheet.csv", L.s("未声明", "Not declared"), catDev(), 3525));

        // ── 通用高频 ────────────────────────────────────────────
        out.add(new Source(
                L.s("2025 年度 TOP 199", "Most-used 2025 TOP 199"),
                L.s("当年统计，时效性最好", "Freshest annual statistics"),
                SL + "Passwords/Common-Credentials/2025-199_most_used_passwords.txt", "MIT", catGen(), 199));

        out.add(new Source(
                L.s("2024 年度 TOP 197", "Most-used 2024 TOP 197"),
                L.s("上一年度统计", "Previous year's statistics"),
                SL + "Passwords/Common-Credentials/2024-197_most_used_passwords.txt", "MIT", catGen(), 197));

        out.add(new Source(
                L.s("通用高频 TOP 1 万", "Common high-frequency TOP 10k"),
                L.s("Pwdb 统计的 1 万条，约 1.9 小时", "10k from Pwdb statistics — ~1.9 h"),
                SL + "Passwords/Common-Credentials/Pwdb_top-10000.txt", "MIT", catGen(), 10000));

        out.add(new Source(
                L.s("高频口令 TOP 1.26 万", "High-frequency TOP 12.6k"),
                L.s("probable-v2 统计表", "probable-v2 statistics"),
                SL + "Passwords/Common-Credentials/probable-v2_top-12000.txt", "MIT", catGen(), 12645));

        out.add(new Source(
                L.s("经典 1 万条", "Classic 10k"),
                L.s("10k-most-common 经典表", "The classic 10k table"),
                SL + "Passwords/Common-Credentials/10k-most-common.txt", "MIT", catGen(), 10001));

        out.add(new Source(
                L.s("xato 千万库 TOP 1 万", "xato 10M library TOP 10k"),
                L.s("从千万级泄露库摘出的前 1 万", "Top 10k of a ten-million-entry corpus"),
                SL + "Passwords/Common-Credentials/xato-net-10-million-passwords-10000.txt", "MIT", catGen(), 10000));

        out.add(new Source(
                L.s("键盘走位 9608", "Keyboard walks 9608"),
                L.s("qwerty、1qaz2wsx 这类相邻键组合，约 1.8 小时",
                        "Adjacent-key patterns such as qwerty and 1qaz2wsx — ~1.8 h"),
                SL + "Passwords/Keyboard-Walks/Keyboard-Combinations.txt", "MIT", catGen(), 9608));

        out.add(new Source(
                L.s("NCSC 10 万条", "NCSC 100k"),
                L.s("量很大，在线要跑 18.5 小时 —— 建议配合智能排序",
                        "Very large; ~18.5 h online — pair with smart ranking"),
                SL + "Passwords/Common-Credentials/100k-most-used-passwords-NCSC.txt", "MIT", catGen(), 99840));

        return out;
    }

    /** 进度回调。onNote 有默认空实现，旧调用方不用改。 */
    public interface Progress {
        default void onNote(String line) { }
        void onStart(int totalBytes);
        void onProgress(long downloaded, int totalBytes);
        void onDone(File file, int lines);
        void onError(String message);
    }

    /**
     * 下载一个源到目标目录。会边下边过滤非法长度并去重，
     * 避免把 63 位以上的垃圾也写进去。
     *
     * 通道选择：先并行探测挑最快的一条，失败再按原顺序回退；
     * 成功的通道记在本会话里，下一个源直接用。
     */
    public static void download(Source src, File destDir, Progress cb) {
        HttpURLConnection conn = null;
        try {
            if (destDir == null) {
                cb.onError("字典目录不可用：无法写入任何存储位置");
                return;
            }
            if (!destDir.exists() && !destDir.mkdirs()) {
                cb.onError("无法创建字典目录: " + destDir.getAbsolutePath()
                        + "\n请检查存储权限或剩余空间");
                return;
            }
            // 写探针确认真的可写，否则文件选择器会给出难懂的 ENOENT
            try {
                File probe = new File(destDir, ".probe");
                try (FileOutputStream f = new FileOutputStream(probe)) { f.write(1); }
                //noinspection ResultOfMethodCallIgnored
                probe.delete();
            } catch (Exception e) {
                cb.onError("字典目录不可写: " + destDir.getAbsolutePath()
                        + "\n" + e.getClass().getSimpleName());
                return;
            }
            String safeName = src.name.replaceAll("[\\\\/:*?\"<>|]", "_");
            File out = new File(destDir, safeName + ".txt");

            String[] urls = orderedUrls(src.url);
            int code = -1;
            String lastErr = "";
            String usedUrl = null;
            for (int i = 0; i < urls.length; i++) {
                String u = urls[i];
                String label = mirrorLabel(u);
                if (i > 0 && cb != null) {
                    cb.onNote(L.f("换通道重试: %s", "Retrying via: %s", label));
                }
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", UA);
                    c.connect();
                    int cc = c.getResponseCode();
                    if (cc == 200) {
                        conn = c;
                        code = cc;
                        usedUrl = u;
                        if (cb != null && !label.isEmpty()) {
                            cb.onNote(L.f("通道: %s", "Channel: %s", label));
                        }
                        break;
                    }
                    lastErr = L.s("服务器返回 ", "Server returned ") + cc;
                    c.disconnect();
                } catch (Exception e) {
                    // 这条通道不行，换下一个
                    lastErr = e.getClass().getSimpleName();
                    if (c != null) {
                        try { c.disconnect(); } catch (Exception ignored) {}
                    }
                }
            }
            if (code != 200 || conn == null) {
                cb.onError(L.s("所有下载通道都不可用",
                        "All download channels are unavailable")
                        + "\n" + L.s("最后错误: ", "Last error: ") + lastErr
                        + "\n" + L.s("请检查网络，或用「导入字典文件」手动添加。",
                                "Check your network, or use \"Import dictionary file\" instead."));
                return;
            }
            // 这一条通了，记住它，后面的源直接用
            String win = prefixOf(usedUrl);
            if (!win.isEmpty()) bestPrefix = win;

            int total = conn.getContentLength();
            cb.onStart(total);

            // 边读边过滤
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            long downloaded = 0;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"), 1 << 16)) {
                String line;
                while ((line = r.readLine()) != null) {
                    downloaded += line.length() + 1;
                    String t = line.trim();
                    if (t.length() >= 8 && t.length() <= 63) seen.add(t);
                    if (seen.size() % 500 == 0) cb.onProgress(downloaded, total);
                }
            }

            try (FileOutputStream fo = new FileOutputStream(out)) {
                for (String s : seen) {
                    fo.write((s + "\n").getBytes("UTF-8"));
                }
            }
            cb.onDone(out, seen.size());
        } catch (Exception e) {
            cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
