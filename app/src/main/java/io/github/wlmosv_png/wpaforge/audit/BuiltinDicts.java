package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置字典释放器（2026-10-26 精选版）。
 *
 * <p><b>这一版砍掉了什么、为什么。</b>上一版把幻影 WiFi 的 8 个 assets
 * 整包搬了进来 —— 逐字节相同（md5 全等），其中两份大表对本机毫无贡献：
 *
 * <ul>
 *   <li>{@code 04 内置英文单词}（168,068 条英文词典：aardvark、abacinate…）
 *       —— 本机历史里**从未被跑过**，因为没人拿牛津词典给家用路由器当密码；</li>
 *   <li>{@code 05 内置号码变形}（483,522 条 = 26 字母 × 18,597 个 8 位日期）
 *       —— 形态是真的（字母 + 生日），但作为**静态表**没意义：本机 6 次使用
 *       全在第 2 条就命中，后面 48 万条白占 5.3 MB 包体。已改成按需生成
 *       （见 {@link BirthdayTable}）。</li>
 * </ul>
 *
 * <p>判据不是「表大不大」，是**本机的真实战绩**：18 个目标、107 次命中里
 * 95% 发生在前 20 条以内，中位数是第 2 条，0 次来自 1000 条以后
 * （见 {@link SourceRank}）。所以留下的都是「有出处 + 本机命中过」的小表。
 *
 * <p>释放策略不变：用标记文件记录版本，已存在的同名文件不覆盖（用户可能改过）；
 * 另外增加一步**按内容哈希清理**已停用的旧内置表 —— 只有哈希与出厂内容完全
 * 一致（说明用户没动过）才删，动过的一律保留。
 */
public final class BuiltinDicts {

    /** 档位：核心（默认跑这套） */
    public static final int TIER_CORE = 1;
    /** 档位：扩展（可选，表更大、本机命中位次更深） */
    public static final int TIER_EXT = 2;
    /** 档位：穷举（规则生成，按区间展开，先看时间预估再决定） */
    public static final int TIER_BRUTE = 3;

    /** 一个内置字典 */
    public static final class Item {
        public final String asset;
        /** 释放到字典目录后的中文名 */
        public final String nameZh;
        /** 英文名（英文系统下用它，匹配时两种都认） */
        public final String nameEn;
        public final int tier;
        /** 为什么留在内置里 —— 界面上直接显示，不含糊 */
        public final String reasonZh;
        public final String reasonEn;
        /** 实测条数 */
        public final int entries;

        Item(String asset, String zh, String en, int tier,
             String reasonZh, String reasonEn, int entries) {
            this.asset = asset;
            this.nameZh = zh;
            this.nameEn = en;
            this.tier = tier;
            this.reasonZh = reasonZh;
            this.reasonEn = reasonEn;
            this.entries = entries;
        }

        /** 当前语言下的显示名 */
        public String name() {
            return L.s(nameZh, nameEn);
        }

        /** 当前语言下的「为什么在」 */
        public String reason() {
            return L.s(reasonZh, reasonEn);
        }

        /**
         * 名称是否指这一项。
         *
         * <p>中英文名都认，**有无扩展名也都认**：字典列表里的名字来自
         * {@link DictStore.Entry}，它会把 {@code .txt} 去掉；而任务历史里
         * 记的是同一份去扩展名的名字。少这一步的话，界面上就认不出
         * 「这本是内置的哪一项」，也显示不出档位。
         */
        public boolean matches(String n) {
            if (n == null) return false;
            String s = strip(n.trim());
            return s.equals(strip(nameZh)) || s.equals(strip(nameEn));
        }

        private static String strip(String s) {
            return s.replaceAll("\\.(txt|lst|dic)$", "");
        }
    }

    /**
     * 内置清单。**顺序不代表执行顺序** —— 执行顺序由
     * {@link SourceRank#order} 按本机战绩决定，这里只是清单。
     */
    private static final List<Item> ITEMS = new ArrayList<>();

    static {
        // ── 核心：有出处、短、本机命中过 ─────────────────────────────
        ITEMS.add(new Item("wpa_top62.txt", "10 WPA 起步 TOP 62.txt", "10 WPA starter top 62.txt",
                TIER_CORE,
                "SecLists（MIT）为 WPA 握手统计的高频口令，不到一分钟跑完 —— 先踩一脚",
                "SecLists (MIT) WPA top 62; finishes in under a minute", 62));

        ITEMS.add(new Item("weak.txt", "01 内置弱口令常用.txt", "01 Built-in common weak.txt",
                TIER_CORE,
                "跨设备通用弱口令，本机命中 57 次、中位第 2 条即中",
                "Cross-device weak passwords: 57 local hits, median position 2", 2494));

        ITEMS.add(new Item("digits.txt", "02 内置纯数字弱口令.txt", "02 Built-in digits weak.txt",
                TIER_CORE,
                "纯数字是最大的单一族（公开统计里占家用 WiFi 密码 21.7%），本机命中 10 次",
                "Pure digits are the largest single family (21.7% of home WiFi passwords)", 2188));

        ITEMS.add(new Item("top2025.txt", "08 年度高频口令 TOP 146.txt", "08 Yearly top 146.txt",
                TIER_CORE, "近年泄露库里排在最前列的口令，很短", "Top of recent breach lists, very short", 146));

        ITEMS.add(new Item("cn_top.txt", "09 中文高频口令.txt", "09 Chinese common.txt",
                TIER_CORE,
                "谐音数字 / 姓名拼音 + 数字 / 姓氏 + 数字 —— 英文数据集完全覆盖不到的那一块",
                "Number homophones, pinyin names + digits — missing from English datasets", 570));

        ITEMS.add(new Item("xato_top.txt", "07 通用口令榜 TOP 3336.txt", "07 Common passwords top 3336.txt",
                TIER_CORE, "公开口令榜前列，本机 14 次使用全中、中位第 11 条",
                "Top of a public password list: 14/14 local hits, median position 11", 3336));

        ITEMS.add(new Item("wpa_top4800.txt", "06 WPA 专用 TOP 4800.txt", "06 WPA-specific top 4800.txt",
                TIER_CORE,
                "SecLists（MIT）的 WPA 主表，比 447 那本更全，命中位次中位 61 条",
                "SecLists (MIT) WPA main list; median hit position 61", 4800));

        // ── 扩展：更大、命中位次更深，用户按需勾选 ────────────────────
        ITEMS.add(new Item("alnum.txt", "03 内置字母数字混合.txt", "03 Built-in alphanumeric.txt",
                TIER_EXT,
                "15,146 条字母数字混合，本机命中位次中位 102 条 —— 想深挖再跑",
                "15,146 alphanumeric entries; median hit position 102 — for deeper sweeps", 15146));

        // ── 穷举：规则区间，点开先看时间预估 ─────────────────────────
        ITEMS.add(new Item("rule_8digit.txt", "90 规则-8位数字.txt", "90 Rule-8digit.txt",
                TIER_BRUTE, "8 位数字全区间，1 亿条 —— 手机端跑不完，只作离线导出用",
                "All 8-digit numbers: 100M entries — not feasible on phone; for offline export only", 0));
        ITEMS.add(new Item("rule_10digit.txt", "91 规则-10位数字.txt", "91 Rule-10digit.txt",
                TIER_BRUTE, "10 位数字全区间，90 亿条 —— 同上",
                "All 10-digit numbers: 9B entries — same as above", 0));
        ITEMS.add(new Item("rule_date.txt", "92 规则-日期区间.txt", "92 Rule-date range.txt",
                TIER_BRUTE, "1960–2014 的日期形态区间（YYYYMMDD）",
                "Date-shaped range 1960–2014 (YYYYMMDD)", 0));
    }

    /**
     * 已停用的旧内置表：文件名 → 出厂内容 sha256。
     * 释放时只有**内容和出厂完全一致**才删（用户改过的一律保留）。
     */
    private static final String[][] OBSOLETE = {
            {"04 内置英文单词.txt", "04 Built-in English words.txt",
                    "8b1d5b88071767afc014c7ed47368daecd61250531a5957bb172363af7527ed4"},
            {"05 内置号码变形.txt", "05 Built-in number variants.txt",
                    "4d57649d86eff837fd35c4d0d9b0014dedfc2b3c004124a4709d215dbfeddf5a"},
            {"06 WPA 高频真实口令 TOP 447.txt", "06 Real-world WPA top 447.txt",
                    "1be4e215c15d200c04e080c3dd2a7a8c893c5bfe5d400049d669619eac765f2d"},
    };

    /** 释放版本。改动内置内容时加一，下次启动会重新释放。4 = 精选版（删两份大表、加 SecLists 两份） */
    private static final int RELEASE_VERSION = 4;
    private static final String MARK = ".builtin_v";

    private BuiltinDicts() {}

    /** 全部内置项（只读用途：界面展示档位与理由） */
    public static List<Item> items() {
        return new ArrayList<>(ITEMS);
    }

    /** 某一档的全部项 */
    public static List<Item> itemsOfTier(int tier) {
        List<Item> out = new ArrayList<>();
        for (Item it : ITEMS) if (it.tier == tier) out.add(it);
        return out;
    }

    /** 名称 → 内置项；不是内置则 null */
    public static Item find(String name) {
        for (Item it : ITEMS) if (it.matches(name)) return it;
        return null;
    }

    /** 核心档总条数（界面显示「默认这套约 N 条」） */
    public static int coreEntries() {
        int n = 0;
        for (Item it : itemsOfTier(TIER_CORE)) n += it.entries;
        return n;
    }

    public static final class Result {
        public final int released;
        public final int skipped;
        /** 清理掉的旧内置表数量 */
        public final int purged;
        public final String message;

        Result(int r, int s, int p, String m) {
            released = r;
            skipped = s;
            purged = p;
            message = m;
        }
    }

    /** 是否需要释放 */
    public static boolean needsRelease(Context ctx) {
        File mark = new File(DictStore.defaultDir(ctx), MARK + RELEASE_VERSION);
        return !mark.exists();
    }

    /**
     * 释放内置字典到字典目录，并清理停用的旧内置表。
     * 已存在的同名文件不覆盖（用户可能改过），只补缺失的。
     */
    public static Result release(Context ctx) {
        File dir = DictStore.defaultDir(ctx);
        if (!dir.exists() && !dir.mkdirs()) {
            return new Result(0, 0, 0, L.s("无法创建字典目录", "Cannot create dictionary folder"));
        }

        int ok = 0, skip = 0;
        StringBuilder fail = new StringBuilder();

        for (Item item : ITEMS) {
            File out = new File(dir, item.name());
            if (out.exists() && out.length() > 0) {
                skip++;
                continue;
            }
            if (copyAsset(ctx, "builtin/" + item.asset, out)) {
                ok++;
            } else {
                fail.append(item.name()).append(" ");
            }
        }

        int purged = purgeObsolete(dir);

        try {
            File mark = new File(dir, MARK + RELEASE_VERSION);
            try (FileOutputStream f = new FileOutputStream(mark)) {
                f.write((System.currentTimeMillis() + "\n").getBytes());
            }
        } catch (Exception ignored) {}

        String msg = L.s("释放 ", "Restored ") + ok + L.s(" 个，已存在跳过 ", " entries, skipped ")
                + skip + L.s(" 个", " existing");
        if (purged > 0) {
            msg += L.s("，清理旧内置 " + purged + " 个（两份搬运来的大表，未经改动才删）",
                    ", removed " + purged + " obsolete built-ins");
        }
        if (fail.length() > 0) msg += L.s("，失败: ", ", failed: ") + fail;
        return new Result(ok, skip, purged, msg);
    }

    /** 按内容哈希清理停用项；返回删除个数 */
    private static int purgeObsolete(File dir) {
        int n = 0;
        for (String[] row : OBSOLETE) {
            for (int i = 0; i <= 1; i++) {
                File f = new File(dir, row[i]);
                if (!f.isFile()) continue;
                String h = sha256(f);
                if (h != null && h.equalsIgnoreCase(row[2]) && f.delete()) n++;
            }
        }
        return n;
    }

    /** 文件 sha256；失败返回 null（读不了就当用户改过，保留） */
    static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean copyAsset(Context ctx, String assetPath, File dest) {
        try (InputStream in = ctx.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return dest.length() > 0;
        } catch (Exception e) {
            android.util.Log.w("WPAForge", L.s("释放 ", "Restored ") + assetPath + " 失败: " + e);
            return false;
        }
    }

    /** 内置字典总数（供界面展示） */
    public static int count() {
        return ITEMS.size();
    }
}
