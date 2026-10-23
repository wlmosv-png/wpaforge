package io.github.wlmosv_png.wpaforge.scan;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 网卡厂商查询（OUI）。
 *
 * <h3>数据来源</h3>
 * assets/builtin/oui.txt，来自 **IEEE 官方 OUI 登记表**
 * （https://standards-oui.ieee.org/oui/oui.txt，MA-L 段，40319 条），
 * 已按「网络设备关键词」裁剪到 15913 条，并压缩了机构名的冗余后缀
 * （Co., Ltd. / Technologies → Tech 之类）。
 *
 * 为什么不用自己写一张「常见厂商前缀表」：
 *   凭印象编 MAC 前缀必然出错，而**错报厂商比不报更糟** ——
 *   用户会据此判断「这是不是我自己的路由器」。宁可查不到显示「未知」，
 *   也不能显示一个编出来的名字。
 *
 * <h3>为什么用 HashMap 而不是二分</h3>
 * 15913 条，一次加载约 1.5 MB 内存，查找 O(1)。
 * 换成排序数组 + 二分能省内存，但每次查找要读文件（assets 不能 mmap
 * 得很方便），反而更慢。折中：**懒加载 + 全局缓存**，只加载一次。
 *
 * <h3>关于随机化 MAC</h3>
 * Android 10+ 默认对每个 SSID 使用随机 MAC，特征是**首字节第二个 bit 为 1**
 * （本地管理位）。这类地址查 OUI 表必然落空，所以要单独判定并说明，
 * 否则用户会以为是「厂商没收录」。
 */
public final class OuiLookup {

    /** 前缀(6 位大写十六进制) -> 厂商名 */
    private static volatile Map<String, String> table = null;
    private static final Object LOCK = new Object();

    private OuiLookup() {}

    /**
     * 查厂商名。
     *
     * @param bssid 形如 "5C:DE:34:11:22:0B" 或 "5cde3426 2ef2"
     * @return 厂商名；查不到返回 null（调用方自行决定显示「未知」还是隐藏）
     */
    public static String vendor(Context ctx, String bssid) {
        String key = prefixOf(bssid);
        if (key == null) return null;
        Map<String, String> t = table(ctx);
        return t == null ? null : t.get(key);
    }

    /**
     * 这个 BSSID 是不是**随机化地址**。
     *
     * 判定依据：首字节的 bit1（0x02，本地管理位）为 1。
     * 全球唯一地址（有 IEEE 分配 OUI 的）这一位必为 0。
     * 随机化地址查 OUI 表必然无结果 —— 要跟「厂商未收录」区分开。
     */
    public static boolean isRandomized(String bssid) {
        String hex = normHex(bssid);
        if (hex == null || hex.length() < 2) return false;
        try {
            int first = Integer.parseInt(hex.substring(0, 2), 16);
            return (first & 0x02) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取出 6 位前缀（大写十六进制） */
    private static String prefixOf(String bssid) {
        String hex = normHex(bssid);
        if (hex == null || hex.length() < 6) return null;
        return hex.substring(0, 6);
    }

    /** 把各种写法归一成连续的十六进制大写串 */
    private static String normHex(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < s.length() && sb.length() < 12; i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.length() >= 6 ? sb.toString() : null;
    }

    /** 懒加载整表；失败则永久返回 null（不反复重试拖慢扫描） */
    private static Map<String, String> table(Context ctx) {
        Map<String, String> t = table;
        if (t != null) return t;
        synchronized (LOCK) {
            if (table != null) return table;
            try {
                Map<String, String> m = new HashMap<>(20000);
                InputStream in = ctx.getAssets().open("builtin/oui.txt");
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
                String line;
                while ((line = r.readLine()) != null) {
                    int sp = line.indexOf(' ');
                    if (sp != 6) continue;                 // 前缀固定 6 字符 + 空格
                    String k = line.substring(0, 6);
                    String v = line.substring(7).trim();
                    if (!v.isEmpty()) m.put(k, v);
                }
                r.close();
                table = m;
                return m;
            } catch (Throwable e) {
                android.util.Log.w("WPAForgeOui", "OUI 表加载失败: " + e);
                return null;
            }
        }
    }
}
