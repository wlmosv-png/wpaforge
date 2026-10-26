package io.github.wlmosv_png.wpaforge.audit;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 内置中文高频口令表的加载器（assets/builtin/cn_top.txt）。
 *
 * 为什么要有这一份：旧版内置的三个「真实口令榜」全是英文社区数据集
 * （password / sunshine / football / superman），国内第一梯队 ——
 * 谐音数字、姓名拼音 + 数字、姓氏 + 数字 —— 一条都不在里面。
 *
 * 加载失败一律退化成空表，绝不用硬编码兜底 —— 宁可少一个桶，
 * 也不能让「猜」伪装成「表」。
 */
public final class CnHot {

    /** assets 相对路径，同时被 BuiltinDicts 用来释放到字典目录 */
    public static final String ASSET = "builtin/cn_top.txt";

    private static volatile List<String> cache;

    private CnHot() {}

    /** 读一次，进程内缓存。失败返回空表（不抛）。 */
    public static List<String> load(Context ctx) {
        List<String> c = cache;
        if (c != null) return c;
        List<String> list = new ArrayList<>();
        if (ctx != null) {
            try (InputStream in = ctx.getAssets().open(ASSET);
                 BufferedReader r = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
                list = parse(lines);
            } catch (Exception e) {
                android.util.Log.w("WPAForge", "内置中文高频表加载失败: " + e);
            }
        }
        cache = list;
        return list;
    }

    /**
     * 解析 + 过滤：跳过注释与空行，只留 WPA 合法长度（8~63），去重保序。
     * 纯函数，可在桌面单测里跑。
     */
    public static List<String> parse(List<String> lines) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (lines == null) return new ArrayList<>();
        for (String raw : lines) {
            if (raw == null) continue;
            String s = raw.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            that:
            {
                if (s.length() < 8 || s.length() > 63) break that;
                for (int i = 0; i < s.length(); i++) {
                    char ch = s.charAt(i);
                    if (ch < 33 || ch > 126) break that;   // 只收可见 ASCII，杜绝脏字符
                }
                set.add(s);
            }
        }
        return new ArrayList<>(set);
    }

    /** 单测用：清掉进程内缓存 */
    public static void clearCache() {
        cache = null;
    }
}
