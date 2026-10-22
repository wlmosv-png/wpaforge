package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.DictSources;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 在线字典源测试。
 *
 * 重点三处：
 *   1. **URL 编码** —— 仓库里有中文文件名和带空格的路径，编错了就是 404，
 *      而 404 在界面上只会表现为「所有通道都不可用」，最难查。
 *   2. **多通道展开** —— 国内可用性靠它：代理前缀 + jsDelivr 重写 + 直连。
 *   3. **清单自洽** —— 分类不能空、URL 不能重复、条数不能为 0。
 */
public class DictSourcesTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) {
        System.out.println("== URL 编码 ==");
        ok("null 进 null 出", DictSources.encodeUrl(null) == null);
        ok("纯 ASCII 路径原样不动",
                "https://raw.githubusercontent.com/a/b/master/c.txt"
                        .equals(DictSources.encodeUrl("https://raw.githubusercontent.com/a/b/master/c.txt")));
        ok("空格编成 %20",
                DictSources.encodeUrl("https://x/y 12306 z.txt").endsWith("/y%2012306%20z.txt"));
        String cn = DictSources.encodeUrl("https://raw.githubusercontent.com/t/w/master/新字典6-弱口令集.txt");
        ok("中文名编成百分号（新 → %E6%96%B0）", cn.contains("%E6%96%B0"));
        ok("中文名里没有裸的非 ASCII 字符", cn.chars().allMatch(c -> c < 128));
        ok("斜杠没被编码（否则路径全废）",
                cn.startsWith("https://raw.githubusercontent.com/t/w/master/"));

        System.out.println("== jsDelivr 重写 ==");
        String jd = DictSources.jsdelivrPath(
                "https://raw.githubusercontent.com/danielmiessler/SecLists/master/Passwords/x.txt");
        ok("owner/repo@branch/path 结构正确",
                "gh/danielmiessler/SecLists@master/Passwords/x.txt".equals(jd));
        ok("分支 main 也认",
                "gh/oops4git/RedteamNotes-Wordlists-Chinese@main/a.txt"
                        .equals(DictSources.jsdelivrPath(
                                "https://raw.githubusercontent.com/oops4git/RedteamNotes-Wordlists-Chinese/main/a.txt")));
        ok("非 raw 地址返回 null", DictSources.jsdelivrPath("https://example.com/a") == null);
        ok("层级不足返回 null", DictSources.jsdelivrPath("https://raw.githubusercontent.com/a/b") == null);

        System.out.println("== 多通道展开 ==");
        String raw = "https://raw.githubusercontent.com/danielmiessler/SecLists/master/Passwords/x.txt";
        String[] urls = DictSources.candidateUrls(raw);
        boolean hasDirect = false, hasGhproxy = false, hasJsdelivr = false;
        for (String u : urls) {
            if (u.equals(raw)) hasDirect = true;
            if (u.startsWith("https://ghproxy.net/")) hasGhproxy = true;
            if (u.contains("jsdelivr.net")) hasJsdelivr = true;
        }
        ok("通道数 >= 6（3 代理 + 3 CDN + 直连）", urls.length >= 6);
        ok("含直连", hasDirect);
        ok("含 ghproxy 代理", hasGhproxy);
        ok("含 jsDelivr CDN", hasJsdelivr);
        ok("每条都是 https", java.util.Arrays.stream(urls).allMatch(u -> u.startsWith("https://")));

        System.out.println("== 通道识别 ==");
        ok("识别 ghproxy", "ghproxy.net".equals(DictSources.mirrorLabel("https://ghproxy.net/" + raw)));
        ok("识别 ghfast", "ghfast.top".equals(DictSources.mirrorLabel("https://ghfast.top/" + raw)));
        ok("识别 llkk", "gh.llkk.cc".equals(DictSources.mirrorLabel("https://gh.llkk.cc/" + raw)));
        ok("识别 jsDelivr", "jsDelivr".equals(DictSources.mirrorLabel("https://cdn.jsdelivr.net/gh/a/b@m/c")));
        ok("直连有标签", DictSources.mirrorLabel(raw).length() > 0);
        ok("prefixOf 直连为空串", "".equals(DictSources.prefixOf(raw)));
        ok("prefixOf 认得代理", "https://ghproxy.net/".equals(DictSources.prefixOf("https://ghproxy.net/" + raw)));

        System.out.println("== 耗时换算 ==");
        ok("小于 60 秒 → 秒", DictSources.estimateText(30).contains("秒"));
        ok("几百条 → 分钟", DictSources.estimateText(600).contains("分钟"));
        ok("1 万条 → 小时", DictSources.estimateText(10000).contains("小时"));
        ok("10 万条 → 小时或天", DictSources.estimateText(100000).contains("小时")
                || DictSources.estimateText(100000).contains("天"));
        ok("百万条 → 天", DictSources.estimateText(1035749).contains("天"));
        ok("0 条返回空串", DictSources.estimateText(0).isEmpty());
        ok("负数不崩且返回空串", DictSources.estimateText(-5).isEmpty());

        System.out.println("== 清单自洽 ==");
        List<DictSources.Source> all = DictSources.list();
        ok("源数量 >= 30（原来只有 11）", all.size() >= 30);
        Set<String> seen = new HashSet<>();
        int dup = 0, noCat = 0, noEntries = 0, notRaw = 0;
        int huge = 0;
        for (DictSources.Source s : all) {
            if (!seen.add(s.url)) dup++;
            if (s.category == null || s.category.isEmpty()) noCat++;
            if (s.entries <= 0) noEntries++;
            if (!s.url.startsWith("https://raw.githubusercontent.com/")) notRaw++;
            if (s.entries > DictSources.HUGE_ENTRIES) huge++;
            if (s.name == null || s.name.isEmpty()) ok("有源没有名字", false);
        }
        ok("没有重复 URL（去重后仍是 " + seen.size() + " 条）", dup == 0);
        ok("每个源都有分类", noCat == 0);
        ok("每个源都有条数", noEntries == 0);
        ok("每个源都指向 raw.githubusercontent（多通道靠它展开）", notRaw == 0);
        ok("有超大表被标记出来（" + huge + " 个）", huge >= 3);
        ok("每个源都有许可字段", all.stream().allMatch(s -> s.license != null && !s.license.isEmpty()));

        System.out.println("== 分类齐全 ==");
        Set<String> cats = new HashSet<>();
        for (DictSources.Source s : all) cats.add(s.category);
        ok("至少 5 个分类", cats.size() >= 5);
        ok("有 WiFi 专用分类", cats.contains(DictSources.catWifi()));
        ok("有中文分类", cats.contains(DictSources.catCn()));
        ok("有数字/生日分类", cats.contains(DictSources.catNum()));
        ok("有设备默认口令分类", cats.contains(DictSources.catDev()));
        ok("有通用高频分类", cats.contains(DictSources.catGen()));

        System.out.println("== 诚实性 ==");
        long mit = all.stream().filter(s -> "MIT".equals(s.license)).count();
        long undeclared = all.stream().filter(s -> !"MIT".equals(s.license)).count();
        ok("MIT 与未声明分开统计（MIT " + mit + " / 未声明 " + undeclared + "）", mit > 0 && undeclared > 0);
        ok("未声明不等于 MIT（不能混标）", undeclared >= 20);

        System.out.println();
        System.out.println("=== 字典源测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
