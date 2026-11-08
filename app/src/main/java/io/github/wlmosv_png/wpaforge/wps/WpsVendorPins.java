package io.github.wlmosv_png.wpaforge.wps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 厂商默认 WPS PIN 算法<b>登记表</b>（原来是「撒网」清单：所有算法一把梭）。
 *
 * <p>为什么值钱：很多路由器的出厂 PIN 是由 MAC 派生的固定值 ——
 * 本地算出 1 个候选就能命中，完全不用碰 10^7 的穷举。
 * 但「能算出来」和「值得默认去试」是两件事，所以本表给每一项都挂了元数据，
 * 并把它分成三条互不混同的通道：
 * <ol>
 *   <li>厂商推导算法（{@link #algorithms()} 里 kind=vendor）：必须 OUI 门槛命中才输出，
 *       主力是 D-Link / ASUS / Airocon；依赖设备序列号的三个（Belkin / EasyBox / Livebox）
 *       标记为不可用。</li>
 *   <li>固定出厂值清单（{@link #fixedValues(String)}）：厂商所有设备共用一个常量 PIN，
 *       OUI 命中即输出，{@code isFixed=true}。</li>
 *   <li>无门槛通用变换（{@link #unprovenTransforms(String)}）：不依赖 OUI，
 *       <b>不进默认策略</b>。</li>
 * </ol>
 *
 * <h3>来源（只允许下面这几处，取不到写「未知」）</h3>
 * <ul>
 *   <li>厂商算法与固定值清单：3WiFi WPS PIN Generator（https://3wifi.stascorp.com/wpspin）
 *       的算法集合，经 fr0stb1rd 的 Python 移植
 *       （github.com/fr0stb1rd/3wifi-wpspin）取出并改写为 Java。
 *       算法本身是逆向厂商固件得到的，不是猜的。</li>
 *   <li>{@code dslMacSn} 骨架：原注释标注「逆向自 Stas'M」。</li>
 * </ul>
 * 本次重构<b>没有引入任何新来源</b>，也<b>没有改动任何算法算式</b>，
 * 只改登记的元数据与对外出口。
 *
 * <h3>available 口径（= 可以进默认策略）</h3>
 * 参数齐备（不依赖设备序列号）&amp;&amp; 有可判定的适用条件（OUI 前缀门槛或固定出厂值）
 * &amp;&amp; 有来源依据。<br>
 * 所以无门槛通用变换虽然「参数齐备、也有来源」，但它<b>没有任何门槛可判</b>，
 * 对每台 AP 都会产出候选，只能由 {@link #unprovenTransforms(String)} 显式取用，
 * {@code available=false}。
 *
 * <h3>testStatus 口径（封闭三档，不许含糊）</h3>
 * <ul>
 *   <li>{@link #TS_BASELINE}：既有 {@code WpsPinTest} 对
 *       {@code 5C:A0:02:B5:8F:65} / {@code 00:14:D1:11:22:33} 有硬编码期望值的那几条。</li>
 *   <li>{@link #TS_IO}：本次新增 {@code WpsVendorPinsTest} 里写了「MAC → 期望 PIN」断言的那几条。</li>
 *   <li>{@link #TS_UNVERIFIED}：其余，<b>包括参数缺失的序列号依赖项</b>。</li>
 * </ul>
 *
 * <h3>confidence 口径（= 用作默认策略时的期望收益，不是「算法算得对不对」）</h3>
 * <ul>
 *   <li>高：有来源依据 + 有 OUI 门槛 + 有公开基准对照。</li>
 *   <li>中：有来源依据 + 有 OUI 门槛，但只有本次输入输出测试（无公开基准）。</li>
 *   <li>低：未验证 / 参数缺失 / 无门槛。</li>
 * </ul>
 * 因此「已用公开基准对照」的无门槛变换仍然只能是「低」——
 * 算得对不等于值得默认试（先验命中率 ≈ 2.2e-6，见 {@link #unprovenTransforms(String)}）。
 *
 * <p>纯逻辑、不碰 Android API，可离线测试。
 */
public final class WpsVendorPins {

    private WpsVendorPins() {
    }

    /**
     * 任何输出路径的硬上限（{@link #cap}）。
     * 默认策略是「先试几十个便宜候选」的东西，一旦超过这个数就说明表本身出了问题，
     * 宁可截断也不要让调用方以为候选表还能一轮跑完。
     */
    public static final int MAX_CANDIDATES = 64;

    /** testStatus 三档之一：既有 WpsPinTest 有硬编码期望值。 */
    public static final String TS_BASELINE = "已用公开基准对照";
    /** testStatus 三档之一：本次新增测试里写了输入输出断言。 */
    public static final String TS_IO = "有输入输出测试";
    /** testStatus 三档之一：其余（含参数缺失的序列号依赖项）。 */
    public static final String TS_UNVERIFIED = "未验证";

    /** 参数缺失的说明文案（序列号依赖项）。 */
    public static final String SN_MISSING_NOTE = "参数缺失，生产环境不读取序列号，未验证";

    /** 来源：3WiFi 算法集合（厂商算法 / 通用变换）。 */
    public static final String SRC_3WIFI =
            "3WiFi WPS PIN Generator（3wifi.stascorp.com/wpspin，逆向厂商固件）/ fr0stb1rd Python 移植";
    /** 来源：dslMacSn 骨架，原注释标注逆向自 Stas'M。 */
    public static final String SRC_3WIFI_SN =
            "3WiFi / fr0stb1rd Python 移植（dslMacSn 骨架逆向自 Stas'M）";
    /** 来源：固定出厂 PIN 清单，属同一 3WiFi 算法集合。 */
    public static final String SRC_STATIC = "3WiFi 固定出厂 PIN 清单（同一 3WiFi 算法集合）";
    /** 来源缺失时的占位（本文件当前没有用到）。 */
    public static final String SRC_UNKNOWN = "未知";

    // 登记项分类（内部用，不做门面 API）。
    static final int KIND_VENDOR = 0;
    static final int KIND_STATIC = 1;
    static final int KIND_GENERIC = 2;

    // ---------------- 登记项 ----------------

    /**
     * 一条登记项：算法本身的算式 + 它需要的门槛 + 它到底能不能进默认策略。
     *
     * <p>算式（{@link Calc}）与固定值（{@code fixedFirst7}）都是包内私有载荷，
     * 对 UI 只需要读上面那几个 public 字段。
     */
    public static final class Algo {

        /** 稳定短标识，如 "dlink" / "asus" / "static:cisco" / "gen:24bit"。 */
        public final String id;
        /** 展示名。 */
        public final String name;
        /** 来源（无依据写「未知」）。 */
        public final String source;
        /** 适用条件（OUI 前缀 / 固定值 / 无门槛）。 */
        public final String conditions;
        /** 参数要求（"MAC" / "MAC+设备序列号" / …）。 */
        public final String params;
        /** 测试状态，只能是 {@link #TS_BASELINE}/{@link #TS_IO}/{@link #TS_UNVERIFIED} 之一。 */
        public final String testStatus;
        /** 可信度：高 / 中 / 低，口径见类注释。 */
        public final String confidence;
        /** 参数齐备 &amp;&amp; 有依据 &amp;&amp; 有门槛 → 可进默认策略。 */
        public final boolean available;

        /** 分类：{@link #KIND_VENDOR} / {@link #KIND_STATIC} / {@link #KIND_GENERIC}。 */
        final int kind;
        /** OUI 门槛前缀（空格分隔）；null/空 = 无门槛。 */
        final String prefixes;
        /** 算式；固定值项为 null。 */
        final Calc calc;
        /** 固定值项的「前 7 位」头部；算式项为 -1。 */
        final long fixedFirst7;

        Algo(String id, String name, String source, String conditions, String params,
             String testStatus, String confidence, boolean available,
             int kind, String prefixes, Calc calc, long fixedFirst7) {
            this.id = id;
            this.name = name;
            this.source = source;
            this.conditions = conditions;
            this.params = params;
            this.testStatus = testStatus;
            this.confidence = confidence;
            this.available = available;
            this.kind = kind;
            this.prefixes = prefixes;
            this.calc = calc;
            this.fixedFirst7 = fixedFirst7;
        }
    }

    /** 一个候选 PIN 及其出处。 */
    public static final class Candidate {

        /** 8 位合法 PIN（第 8 位是校验位）。 */
        public final String pin;
        /** 来自哪个登记项 / 清单（"dlink" / "static:cisco" / "weak" / "custom" …）。 */
        public final String algoId;
        /** 该登记项的来源。 */
        public final String source;
        /** true = 命中 OUI 门槛才产出（厂商算法与固定值）。 */
        public final boolean viaOui;
        /** true = 来自固定出厂值清单（与厂商推导算法不混同）。 */
        public final boolean isFixed;
        /**
         * 旧字段名，等价 {@link #algoId}。
         * 既有调用方（{@code WpsCandidates.tiered} 的 3 参构造）与旧测试还在用，保留。
         */
        public final String algo;

        Candidate(String pin, String algoId, String source, boolean viaOui, boolean isFixed) {
            this.pin = pin;
            this.algoId = algoId;
            this.source = source;
            this.viaOui = viaOui;
            this.isFixed = isFixed;
            this.algo = algoId;
        }

        /** 兼容旧签名（{@code WpsCandidates.tiered} 里的弱值表 / 自定义表用）。 */
        Candidate(String pin, String algoId, boolean viaOui) {
            this(pin, algoId, "", viaOui, false);
        }

        public String describe() {
            return pin + "  (" + algoId + ")";
        }

        @Override
        public String toString() {
            return describe() + (viaOui ? "[oui]" : "") + (isFixed ? "[fixed]" : "");
        }
    }

    // ---------------- 基础工具（算式支撑，未改动） ----------------

    /** 去掉分隔符并大写；失败返回空串。 */
    static String norm(String bssid) {
        if (bssid == null) return "";
        return bssid.replace(":", "").replace("-", "").replace(".", "")
                .replace(" ", "").trim().toUpperCase(Locale.ROOT);
    }

    /** BSSID → 48 位整数；非法返回 -1。 */
    static long macOf(String bssid) {
        String s = norm(bssid);
        if (s.length() != 12) return -1;
        try {
            return Long.parseLong(s, 16);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 48 位整数 → 12 位大写 hex。 */
    static String hex12(long mac) {
        String s = Long.toHexString(mac & 0xFFFFFFFFFFFFL).toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < 12; i++) sb.append('0');
        return sb.append(s).toString();
    }

    static String hexN(long mac, int digits) {
        String s = Long.toHexString(mac & 0xFFFFFFFFFFFFL).toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < digits; i++) sb.append('0');
        sb.append(s);
        return sb.substring(sb.length() - digits);
    }

    static int[] bytesOf(long mac) {
        String s = hex12(mac);
        int[] b = new int[6];
        for (int i = 0; i < 6; i++) b[i] = (int) Long.parseLong(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    /** 按字符反转（= 半字节序反转）。 */
    static String reverse(String s) {
        return new StringBuilder(s).reverse().toString();
    }

    /**
     * 按字节反转（每 2 个 hex 字符一组）。
     * 注意和 reverse() 不是一回事 —— Python 版里 `Reverse byte` 用前者、
     * `Reverse nibble` 用后者，我第一版混用了，离线基准立刻抓出来。
     */
    static String reverseBytes(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i >= 2; i -= 2) {
            sb.append(s, i - 2, i);
        }
        return sb.toString();
    }

    static long reverseBits(long v, int bits) {
        long r = 0;
        for (int i = 0; i < bits; i++) {
            r = (r << 1) | ((v >> i) & 1L);
        }
        return r;
    }

    /**
     * 前 7 位 → 带校验位的 8 位 PIN。
     * 所有算法算出来的都是「前 7 位」，第 8 位统一由 WPS 校验位规则补。
     */
    static String complete(long first7) {
        long p = Math.floorMod(first7, 10000000L);
        String s = Long.toString(p);
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < 7; i++) sb.append('0');
        sb.append(s);
        return WpsPins.complete(sb.toString());
    }

    // ---------------- 算式（未改动） ----------------

    /** D-Link：对 NIC 做一串 XOR，再保证是 7 位。 */
    static long dlink(long mac) {
        long m = mac & 0xFFFFFFL;
        m ^= 0x55AA55L;
        m ^= (((m & 0xFL) << 4) | ((m & 0xFL) << 8) | ((m & 0xFL) << 12)
                | ((m & 0xFL) << 16) | ((m & 0xFL) << 20));
        m %= 10000000L;
        if (m < 1000000L) {
            m += ((m % 9L) * 1000000L) + 1000000L;
        }
        return m;
    }

    /** ASUS：每位的模数随前 5 字节之和变化。 */
    static long asus(long mac) {
        int[] b = bytesOf(mac);
        int sum = b[1] + b[2] + b[3] + b[4] + b[5];
        StringBuilder pin = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            int mod = 10 - ((i + sum) % 7);
            if (mod <= 0) mod = 1;
            pin.append((b[i % 6] + b[5]) % mod);
        }
        try {
            return Long.parseLong(pin.toString());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Airocon（Realtek 代工）：相邻字节之和拼成 7 位。 */
    static long airocon(long mac) {
        int[] b = bytesOf(mac);
        return ((b[0] + b[1]) % 10L)
                + (((b[5] + b[0]) % 10L) * 10L)
                + (((b[4] + b[5]) % 10L) * 100L)
                + (((b[3] + b[4]) % 10L) * 1000L)
                + (((b[2] + b[3]) % 10L) * 10000L)
                + (((b[1] + b[2]) % 10L) * 100000L)
                + (((b[0] + b[1]) % 10L) * 1000000L);
    }

    /**
     * 通用 DSL 家族算法（Belkin / EasyBox / Arcadyan / Livebox 共用一个骨架，
     * 只是 bx 位掩码不同）。逆向自 Stas'M，照着 Python 版逐行搬。
     *
     * @param sn 序列号（可空；空则用全 0 兜底 —— 但生产调用点恒传空，见登记表里的说明）
     */
    static long dslMacSn(long mac, String sn, int[] bx, int bk1, int bk2) {
        String s = sn == null ? "" : sn;
        if (s.length() < 4) {
            StringBuilder sb = new StringBuilder();
            for (int i = s.length(); i < 4; i++) sb.append('0');
            s = sb.append(s).toString();
        }
        if (s.length() > 4) s = s.substring(s.length() - 4);

        int[] snNib = new int[4];
        for (int i = 0; i < 4; i++) {
            int v;
            try {
                v = Integer.parseInt(s.substring(i, i + 1), 16);
            } catch (Throwable t) {
                v = 0;
            }
            snNib[i] = v;
        }

        int[] nic = new int[]{
                (int) ((mac & 0xFFFFL) >> 12),
                (int) ((mac & 0xFFFL) >> 8),
                (int) ((mac & 0xFFL) >> 4),
                (int) (mac & 0xFL)
        };

        int k1 = 0;
        int t = bk1;
        int i = 0;
        while (t != 0) {
            if ((t & 1) != 0) {
                k1 += nib(i, nic, snNib);
                k1 &= 0xF;
            }
            t >>= 1;
            i++;
        }

        int k2 = 0;
        t = bk2;
        i = 0;
        while (t != 0) {
            if ((t & 1) != 0) {
                k2 += nib(i, nic, snNib);
                k2 &= 0xF;
            }
            t >>= 1;
            i++;
        }

        long pin = 0;
        for (int mask : bx) {
            int xor = 0;
            int j = 0;
            int m2 = mask;
            while (m2 != 0) {
                if ((m2 & 1) != 0) {
                    if (j > 4) xor ^= nib(j - 4, nic, snNib);
                    else if (j > 1) xor ^= nic[j - 1];
                    else if (j > 0) xor ^= k2;
                    else xor ^= k1;
                }
                m2 >>= 1;
                j++;
            }
            pin = (pin << 4) | (xor & 0xFL);
        }
        return pin % 10000000L;
    }

    private static int nib(int i, int[] nic, int[] snNib) {
        if (i < 4) return i >= 0 && i < 4 ? nic[i] : 0;
        int k = i - 4;
        return (k >= 0 && k < snNib.length) ? snNib[k] : 0;
    }

    /** 一项算法的算式：MAC（+可选序列号）→ 前 7 位。 */
    interface Calc {
        long run(long mac, String sn);
    }

    // ---------------- OUI 前缀表（未改动） ----------------

    private static final String P_DLINK =
            "0014D1 14D64D 1C7EE5 28107B 84C9B2 A0AB1B B8A386 C0A0BB CCB255 D8EB97 FC7516";
    private static final String P_DLINK1 =
            "0014D1 0018E7 00195B 001CF0 001E58 002191 0022B0 002401 00265A 14D64D 1C7EE5 "
                    + "340804 5CD998 84C9B2 B8A386 C8BE19 C8D3A3 CCB255";
    private static final String P_BELKIN = "08863B 94103E B4750E C05627 EC1A59";
    private static final String P_EASYBOX = "00264D 38229D 7C4FB5";
    private static final String P_LIVEBOX =
            "1883BF 488D36 4C09D4 507E5D 5CDC96 743170 849CA6 880355 9C80DF A8D3F7 D0052A D463FE";
    private static final String P_ASUS =
            "00072624 0008A1D3 00177C 001EA6 00304FB 00E04C0 048D38 049226 04D9F5 081077 "
                    + "081078 081079 083E5D 08606E 0862669 107B44 10BF48 10C37B 10FEED3C 14DDA9 "
                    + "181E78 1C4419 1C872C 1CB72C 2420C7 247F20 2C56DC 2CAB25 2CFDA1 305A3A "
                    + "3085A98C 382C4A 38D547 3C1E04 40167E 40F201 44E9DD 48EE0C 50465D 5464D9 "
                    + "54A050 54B80A 587BE906 6045CB 60A44C 60D1AA21 64517E 64D954 6C198F 6C7220 "
                    + "6CFDB9 704D7B 7062B8 74D02B 7824AF 78542E 78D99FD 7C2664 803F5DF6 84A423 "
                    + "88A6C6 88D7F6 8C10D4 8C882B00 904D4A 907282 90F65290 94FBB2 9C5C8E A01B29 "
                    + "A0F3C1E A8F7E00 AC220B AC9E17 ACA213 B06EBF B85510 B8EE0E BC3400 BC9680 "
                    + "BCEE7B C0A0BB8C C412F5 C4A81D C860007 C891F9 D00ED90 D017C2 D084B0 D850E6 "
                    + "D8FEE3 E03F49 E4BEED E894F6F6 E8CC18 EC1A5971 EC2280 EC4C4D F0795978 F42853 "
                    + "F43E61 F46BEF F832E4 F8AB05 F8E903F4 FC8B97";
    private static final String P_AIROCON =
            "0007262F 000B2B4A 000EF4E7 001333B 00177C 001AEF 00E04BB3 02101801 0810734 "
                    + "08107710 1013EE0 2CAB25C7 788C54 803F5DF6 94FBB2 BC9680 F43E61 FC8B97";

    private static final int[] BX_BELKIN = {6, 129, 209, 10, 24, 3, 39};
    private static final int[] BX_EASYBOX = {129, 65, 6, 10, 136, 80, 33};

    /**
     * 固定出厂 PIN：某些厂商所有设备共用一个出厂 PIN，只要 OUI 对上就试这一个。
     * 每行 = {id 后缀, 展示名, 7 位头部（第 8 位校验位由 complete 补）, OUI 前缀}。
     *
     * <p>注意重复：这 22 条的 7 位头部**同时出现在 {@code WpsCandidates.builtinWeak()}（弱值表）里**，
     * 也就是说同一个 PIN 会由两条路径产出（例：Cisco 的 {@code 1234567} → {@code 12345670}）。
     * 这里按规则保留在固定值清单里（它确实是一条「出厂常量」而不是弱值猜测），
     * 重复由调用方统一按 PIN 值去重完成 —— 见 {@link #fixedValues(String)} 与 {@link #suggest(String, String)}。
     */
    private static final String[][] STATIC_PINS = {
            {"cisco", "Cisco", "1234567", "001A2B 00248C 002618 344DEB 7054F5 7071BC E06995 E0CB4E"},
            {"broadcom1", "Broadcom 1", "2017252", "001AA9 14144B 988B5D ACF1DF BCF685 C8D3A3 EC6264"},
            {"broadcom2", "Broadcom 2", "4626484", "14D64D 1C7EE5 28107B 84C9B2 B8A386 BCF685 C8BE19"},
            {"broadcom3", "Broadcom 3", "7622990", "14D64D 1C7EE5 28107B 7C034C B8A386 BCF685 C8BE19"},
            {"broadcom4", "Broadcom 4", "6232714",
                    "14D64D 18622C 1C7EE5 204E7F 28107B 4C17EB 7C03D8 84C9B2 B8A386 BCF685 "
                            + "C8BE19 C8D3A3 CCB255 D86CE9 FC7516"},
            {"broadcom5", "Broadcom 5", "1086411",
                    "14D64D 18622C 1C7EE5 204E7F 28107B 4C17EB 7C03D8 84C9B2 B8A386 BCF685 "
                            + "C8BE19 C8D3A3 CCB255 D86CE9 FC7516"},
            {"broadcom6", "Broadcom 6", "3195719",
                    "14D64D 18622C 1C7EE5 204E7F 28107B 4C17EB 7C03D8 84C9B2 B8A386 BCF685 "
                            + "C8BE19 C8D3A3 CCB255 D86CE9 FC7516"},
            {"airocon1", "Airocon 1", "3043203", "181E78 40F201 44E9DD D084B0"},
            {"airocon2", "Airocon 2", "7141225", "84A423 88A6C6 8C10D4"},
            {"dsl2740r", "DSL-2740R", "6817554", "00265A 1CBDB9 340804 5CD998 84C9B2 FC7516"},
            {"realtek1", "Realtek 1", "9566146", "000C42 000EE8 0014D1"},
            {"realtek2", "Realtek 2", "9571911", "007263 E4BEED"},
            {"realtek3", "Realtek 3", "4856371", "08C6B3"},
            {"upvel", "Upvel", "2085483", "784476 D4BF7F0 F8C091"},
            {"ur814ac", "UR-814AC", "4397768", "D4BF7F60"},
            {"ur825ac", "UR-825AC", "529417", "D4BF7F5"},
            {"onlime", "Onlime", "9995604", "0014D1 144D67 784476 D4BF7F F8C091"},
            {"edimax", "Edimax", "3561153", "00E04C 801F02"},
            {"thomson", "Thomson", "6795814", "002624 4432C8 88F7C7 CC03FA"},
            {"hg532x", "HG532x", "3425928",
                    "00664B 086361 087A4C 0C96BF 14B968 2008ED 2469A5 346BD3 786A89 88E3AB "
                            + "9CC172 ACE215 CCA223 D07AB5 E8CD2D F80113 F83DFF"},
            {"h108l", "H108L", "9422988",
                    "4C09B4 4CAC0A 84742A4 9CD24B B075D5 C864C7 DC028E FCC897"},
            {"cbono", "CBN ONO", "9575521", "5C353B DC537C"},
    };

    // ---------------- 登记表本体 ----------------

    private static final List<Algo> VENDOR = vendorTable();
    private static final List<Algo> FIXED = fixedTable();
    private static final List<Algo> GENERIC = genericTable();
    private static final List<Algo> ALL = allTable();

    /** 厂商推导算法（可用 4 条 + 序列号依赖 3 条）。 */
    private static List<Algo> vendorTable() {
        List<Algo> t = new ArrayList<>();

        // D-Link：原来在「厂商表」和「通用表」各注册一次（裸 D-Link PIN / D-Link PIN +1），
        // 只有 OUI 命中时它才是厂商推导，所以只在下面这一处登记；通用表里已删除。
        t.add(new Algo("dlink", "D-Link PIN", SRC_3WIFI, ouiConds(P_DLINK), "MAC",
                TS_BASELINE, "高", true, KIND_VENDOR, P_DLINK, (m, s) -> dlink(m), -1));
        t.add(new Algo("dlink+1", "D-Link PIN +1", SRC_3WIFI, ouiConds(P_DLINK1), "MAC",
                TS_BASELINE, "高", true, KIND_VENDOR, P_DLINK1, (m, s) -> dlink(m + 1), -1));
        t.add(new Algo("asus", "ASUS PIN", SRC_3WIFI, ouiConds(P_ASUS), "MAC",
                TS_IO, "中", true, KIND_VENDOR, P_ASUS, (m, s) -> asus(m), -1));
        t.add(new Algo("airocon", "Airocon（Realtek 代工）PIN", SRC_3WIFI, ouiConds(P_AIROCON), "MAC",
                TS_IO, "中", true, KIND_VENDOR, P_AIROCON, (m, s) -> airocon(m), -1));

        // 以下三条依赖设备序列号，生产调用点恒传空 SN → 算式实际是 f(MAC, SN="0000")。
        // 真序列号是 4 位 hex 半字节，未知时有 65536 种可能 → 先验正确率约 1/65536，
        // 与「猜一个 PIN」没有本质区别，因此 available=false 且永不进 suggest()。
        t.add(new Algo("belkin", "Belkin PIN", SRC_3WIFI_SN, ouiConds(P_BELKIN) + "；" + SN_MISSING_NOTE,
                "MAC+设备序列号", TS_UNVERIFIED, "低", false, KIND_VENDOR, P_BELKIN,
                (m, s) -> dslMacSn(m, s, BX_BELKIN, 60, 195), -1));
        t.add(new Algo("easybox", "Vodafone EasyBox", SRC_3WIFI_SN,
                ouiConds(P_EASYBOX) + "；" + SN_MISSING_NOTE, "MAC+设备序列号",
                TS_UNVERIFIED, "低", false, KIND_VENDOR, P_EASYBOX,
                (m, s) -> dslMacSn(m, (s == null || s.isEmpty())
                        ? Long.toHexString(m & 0xFFFFL).toUpperCase(Locale.ROOT) : s,
                        BX_EASYBOX, 60, 195), -1));
        t.add(new Algo("livebox", "Livebox Arcadyan", SRC_3WIFI_SN,
                ouiConds(P_LIVEBOX) + "；" + SN_MISSING_NOTE, "MAC+设备序列号",
                TS_UNVERIFIED, "低", false, KIND_VENDOR, P_LIVEBOX,
                (m, s) -> dslMacSn(m - 2, s, BX_EASYBOX, 60, 195), -1));
        return t;
    }

    /** 固定出厂值清单（22 条，常量 PIN，OUI 命中即出）。 */
    private static List<Algo> fixedTable() {
        List<Algo> t = new ArrayList<>();
        for (String[] sp : STATIC_PINS) {
            long head;
            try {
                head = Long.parseLong(sp[2]);
            } catch (Throwable e) {
                continue;   // 表里写坏了一行就不登记，不要让它冒出一个错误常量
            }
            t.add(new Algo("static:" + sp[0], sp[1], SRC_STATIC, ouiConds(sp[3]),
                    "MAC（仅需 OUI 命中，PIN 为厂商常量）",
                    TS_IO, "中", true, KIND_STATIC, sp[3], null, head));
        }
        return t;
    }

    /**
     * 无门槛通用变换。
     *
     * <p>不进默认策略的理由：它对<b>每一台</b> AP 都会产出候选，
     * 22 条候选落在 10^7 的 PIN 空间上，先验命中概率 ≈ 22/10^7 = 2.2e-6 ——
     * 比拆半攻击（11000 次必然覆盖）低好几个数量级，只有用户明确要求时才值得试。
     */
    private static List<Algo> genericTable() {
        List<Algo> t = new ArrayList<>();

        int[] bitsList = {24, 28, 32, 36, 40, 44, 48};
        for (int bits : bitsList) {
            final int n = bits;
            final long mask = (bits == 48) ? 0xFFFFFFFFFFFFL : ((1L << bits) - 1);
            t.add(generic("gen:" + n + "bit", n + "-bit PIN",
                    (mac, sn) -> mac & mask, n == 24 || n == 28));
        }

        t.add(generic("gen:revbyte24", "Reverse byte 24-bit",
                (mac, sn) -> Long.parseLong(reverseBytes(hexN(mac & 0xFFFFFFL, 6)), 16), true));
        t.add(generic("gen:revbyte32", "Reverse byte 32-bit",
                (mac, sn) -> Long.parseLong(reverseBytes(hexN(mac & 0xFFFFFFFFL, 8)), 16), false));
        t.add(generic("gen:revbyte48", "Reverse byte 48-bit",
                (mac, sn) -> Long.parseLong(reverseBytes(hex12(mac)), 16), false));

        t.add(generic("gen:revnib24", "Reverse nibble 24-bit",
                (mac, sn) -> Long.parseLong(reverse(hexN(mac & 0xFFFFFFL, 6)), 16) & 0xFFFFFFL, false));
        t.add(generic("gen:revnib32", "Reverse nibble 32-bit",
                (mac, sn) -> Long.parseLong(reverse(hexN(mac & 0xFFFFFFFFL, 8)), 16) & 0xFFFFFFFFL, false));
        t.add(generic("gen:revnib48", "Reverse nibble 48-bit",
                (mac, sn) -> Long.parseLong(reverse(hex12(mac)), 16), false));

        t.add(generic("gen:revbits24", "Reverse bits 24-bit",
                (mac, sn) -> reverseBits(mac & 0xFFFFFFL, 24), false));
        t.add(generic("gen:revbits32", "Reverse bits 32-bit",
                (mac, sn) -> reverseBits(mac & 0xFFFFFFFFL, 32), false));
        t.add(generic("gen:revbits48", "Reverse bits 48-bit",
                (mac, sn) -> reverseBits(mac & 0xFFFFFFFFFFFFL, 48), false));

        t.add(generic("gen:invnic", "Inv NIC to PIN",
                (mac, sn) -> (~mac) & 0xFFFFFFL, true));
        t.add(generic("gen:nic2", "NIC * 2",
                (mac, sn) -> (mac & 0xFFFFFFL) * 2, true));
        t.add(generic("gen:nic3", "NIC * 3",
                (mac, sn) -> (mac & 0xFFFFFFL) * 3, false));

        t.add(generic("gen:ouisum", "OUI + NIC", (mac, sn) -> {
            long macL = mac & 0xFFFFFFFFFFFFL;
            long oui = macL >>> 24;
            long nic = macL & 0xFFFFFFL;
            return (oui + nic) % 0x1000000L;
        }, false));
        t.add(generic("gen:ouidiff", "OUI - NIC", (mac, sn) -> {
            long macL = mac & 0xFFFFFFFFFFFFL;
            long oui = macL >>> 24;
            long nic = macL & 0xFFFFFFL;
            return nic < oui ? (oui - nic) : ((oui + 0x1000000L - nic) & 0xFFFFFFL);
        }, false));
        t.add(generic("gen:ouixor", "OUI ^ NIC", (mac, sn) -> {
            long macL = mac & 0xFFFFFFFFFFFFL;
            return (macL >>> 24) ^ (macL & 0xFFFFFFL);
        }, true));

        return t;
    }

    /**
     * 一条无门槛变换。
     *
     * @param hasBaseline 该条在既有 WpsPinTest 里是否有硬编码期望值
     *                    （只有 24/28-bit、Reverse byte 24、Inv NIC、NIC*2、OUI^NIC 有）
     */
    private static Algo generic(String id, String name, Calc calc, boolean hasBaseline) {
        return new Algo(id, name, SRC_3WIFI,
                "无门槛：任意合法 BSSID 都会输出（先验命中率 ≈ 2.2e-6，不进默认策略）",
                "MAC", hasBaseline ? TS_BASELINE : TS_UNVERIFIED, "低", false,
                KIND_GENERIC, null, calc, -1);
    }

    private static List<Algo> allTable() {
        List<Algo> t = new ArrayList<>();
        t.addAll(VENDOR);
        t.addAll(FIXED);
        t.addAll(GENERIC);
        return t;
    }

    private static String ouiConds(String prefixes) {
        return "OUI 前缀门槛：" + prefixes;
    }

    // ---------------- 对外 ----------------

    /** 全部登记项（含不可用），供 UI 展示。顺序：厂商算法 → 固定值 → 无门槛变换。 */
    public static List<Algo> algorithms() {
        return new ArrayList<>(ALL);
    }

    /** 默认策略可用项（available=true），供 UI 判断「哪些会真的被算出来」。 */
    public static List<Algo> availableAlgorithms() {
        List<Algo> out = new ArrayList<>();
        for (Algo a : ALL) if (a.available) out.add(a);
        return out;
    }

    /**
     * 默认建议（生产用）：只包含 available 的厂商算法 + 固定出厂值清单。
     *
     * <p>顺序 = 命中概率顺序：厂商专属算法（OUI 命中才出，通常 0~3 个）→ 固定出厂值。
     * 去重按 PIN 值，最后受 {@link #MAX_CANDIDATES} 封顶。
     * <p>序列号依赖项（Belkin / EasyBox / Livebox）不在此列 —— 见 {@link #vendorTable()}。
     * <p>无门槛通用变换也不在此列 —— 见 {@link #unprovenTransforms(String)}。
     */
    public static List<Candidate> suggest(String bssid, String sn) {
        List<Candidate> out = new ArrayList<>();
        long mac = macOf(bssid);
        if (mac < 0) return out;
        String macHex = hex12(mac);
        String snStr = sn == null ? "" : sn;
        Map<String, Boolean> seen = new LinkedHashMap<>();

        for (Algo a : VENDOR) {
            if (!a.available) continue;
            if (!prefixMatch(macHex, a.prefixes)) continue;
            add(out, seen, a.calc.run(mac, snStr), a, true, false);
        }
        for (Algo a : FIXED) {
            if (!prefixMatch(macHex, a.prefixes)) continue;
            add(out, seen, a.fixedFirst7, a, true, true);
        }
        return cap(out);
    }

    /**
     * 无门槛通用变换（保留但<b>不进默认</b>），单独入口。
     *
     * <p>登记 22 条：原来的通用表是 24 条，其中 D-Link PIN / D-Link PIN +1 与厂商表重复注册，
     * 按「一个算法只登记一处」合并进厂商表（那里才是它们真正有门槛的地方），所以这里剩 22 条。
     * 又因为去重按 PIN 值，实际产出更少（实测 5C:A0:02:B5:8F:65 出 20 条、
     * 00:14:D1:11:22:33 出 19 条 —— 28-bit 与 32-bit 这类在高位上相同的情况会撞值）。
     *
     * <p>为什么不用它做默认：产出对每台 AP 都有，前 7 位的有效位宽又常常小于名义位宽，
     * 先验命中概率 ≈ 22/10^7 = 2.2e-6，性价比低于拆半（11000 次必然覆盖）。
     * 只有用户明确要求「把通用变换也试一遍」时才用。
     */
    public static List<Candidate> unprovenTransforms(String bssid) {
        List<Candidate> out = new ArrayList<>();
        long mac = macOf(bssid);
        if (mac < 0) return out;
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Algo a : GENERIC) {
            add(out, seen, a.calc.run(mac, ""), a, false, false);
        }
        return cap(out);
    }

    /** 22 条固定出厂值，单独归类（{@code isFixed=true}，与厂商推导算法不混同）。 */
    public static List<Candidate> fixedValues(String bssid) {
        List<Candidate> out = new ArrayList<>();
        long mac = macOf(bssid);
        if (mac < 0) return out;
        String macHex = hex12(mac);
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Algo a : FIXED) {
            if (!prefixMatch(macHex, a.prefixes)) continue;
            add(out, seen, a.fixedFirst7, a, true, true);
        }
        return cap(out);
    }

    /**
     * 兼容入口：旧代码里的「只要通用算法（不依赖 OUI）」。
     *
     * <p>语义已收敛到 {@link #unprovenTransforms(String)}（同一批无门槛变换）。
     * 之所以不删：既有测试（{@code WpsPinTest}）还在调用，删掉就编译不过；
     * 且 Map 体系的调用方可能仍在用。新代码请直接用 {@link #unprovenTransforms(String)}。
     */
    public static List<Candidate> suggestGeneric(String bssid) {
        return unprovenTransforms(bssid);
    }

    // ---------------- 内部 ----------------

    private static void add(List<Candidate> out, Map<String, Boolean> seen,
                            long first7, Algo a, boolean viaOui, boolean isFixed) {
        if (first7 < 0) return;
        String pin = complete(first7);
        if (pin == null || pin.length() != 8) return;
        if (seen.containsKey(pin)) return;
        seen.put(pin, Boolean.TRUE);
        out.add(new Candidate(pin, a.id, a.source, viaOui, isFixed));
    }

    /** 硬上限：任何输出路径都过这里，{@link #MAX_CANDIDATES} 不得被突破。 */
    private static List<Candidate> cap(List<Candidate> l) {
        if (l.size() <= MAX_CANDIDATES) return l;
        return new ArrayList<>(l.subList(0, MAX_CANDIDATES));
    }

    static boolean prefixMatch(String macHex, String prefixes) {
        if (prefixes == null || prefixes.isEmpty()) return false;
        for (String p : prefixes.split(" ")) {
            if (!p.isEmpty() && macHex.startsWith(p)) return true;
        }
        return false;
    }
}
