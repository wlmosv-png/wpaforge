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

        /** 当前语言下的**显示名**（只用于界面） */
        public String name() {
            return L.s(nameZh, nameEn);
        }

        /**
         * 释放到磁盘时用的**文件名**。固定取中文名，**与语言无关**。
         *
         * <p>2026-11-15 修：以前用 {@link #name()}（会跟随语言）当文件名，
         * 于是切到英文会再写一份 `01 Built-in common weak.txt`，中文那份还留着 ——
         * 同一本字典在列表里出现两次，用户以为多了两本。
         * 磁盘上的文件名是**数据标识**，不该随界面语言变。
         */
        public String fileName() {
            return nameZh;
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
                "Cross-device weak passwords: 57 local hits, median position 2", 2493));

        ITEMS.add(new Item("digits.txt", "02 内置纯数字弱口令.txt", "02 Built-in digits weak.txt",
                TIER_CORE,
                "纯数字是最大的单一族（公开统计里占家用 WiFi 密码 21.7%），本机命中 10 次",
                "Pure digits are the largest single family (21.7% of home WiFi passwords)", 2147));

        ITEMS.add(new Item("top2025.txt", "08 年度高频口令 TOP 146.txt", "08 Yearly top 146.txt",
                TIER_CORE, "近年泄露库里排在最前列的口令，很短", "Top of recent breach lists, very short", 146));

        ITEMS.add(new Item("cn_top.txt", "09 中文高频口令.txt", "09 Chinese common.txt",
                TIER_CORE,
                "谐音数字 / 姓名拼音 + 数字 / 姓氏 + 数字 —— 英文数据集完全覆盖不到的那一块",
                "Number homophones, pinyin names + digits — missing from English datasets", 561));

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
                "13,255 alphanumeric entries; median hit position 102 — for deeper sweeps", 13255));

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

        // ── 手机号（2026-11-14 新增，见 PhoneSegs 的算账说明）───────────
        ITEMS.add(new Item("rule_phone_cn.txt", "93 规则-中国手机号段.txt", "93 Rule-China mobile segments.txt",
                TIER_BRUTE,
                "54 个公开号段（前 3 位）× 后 8 位全排列 ≈ 54 亿条。"
                        + "在线要跑一百多年 —— 只用来导出给 PC 显卡跑（小时级）",
                "54 public mobile prefixes x 8 trailing digits ~ 5.4B entries. "
                        + "Centuries online — export it for a PC GPU (hours)", 0));

        ITEMS.add(new Item("rule_phone_local.txt", "94 规则-本地号段模板.txt", "94 Rule-local segment template.txt",
                TIER_BRUTE,
                "模板：把「前缀」填成本地号段前 7 位。1 个号段 = 1 万条 ≈ 在线 1.9 小时，"
                        + "这是在线可承受的最大粒度",
                "Template: put local 7-digit segments in Prefix. One segment = 10k entries ~1.9 h online",
                0));
    }

    /**
     * 历史内置表的名字（中文名 / 英文名）。**只认名字，不再拿「出厂 sha256 完全相等」当判据。**
     *
     * <h3>为什么放弃哈希判据（2026-11-15，真机实证）</h3>
     * 旧实现只有「盘上内容与出厂哈希逐字节相等」才删。实测：
     * {@code 05 内置号码变形.txt}（4.8 MB）仍躺在字典目录里，
     * 它的 sha256 是 {@code 5810d7b3…}，而废弃表记的是 {@code 4d57649d…} ——
     * 哈希在任何一次细微漂移（换版本释放过、换行差异、用户动过一字节）之后就对不上，
     * 于是**删不掉、也没人知道删不掉**：那张 48 万条的废表还能在列表里被选中跑，
     * 白占 4.8 MB 和用户几小时。
     *
     * <p>新判据分两档，且**一律进回收站而不是直接删**（可恢复）：
     * <ol>
     *   <li>清单里有记录且内容没被改过 → 确定是我们放的 → 回收；</li>
     *   <li>清单里没记录（老版本放的，首次升级）→ 名字在我们的历史名单里 → 判为我们的 → 回收。</li>
     * </ol>
     * 第 2 档带一点推定成分（理论上用户可以恰好建一个同名文件），
     * 所以「进回收站」是这条方案的必要部分，不是可有可无的礼貌。
     *
     * <p>保留旧哈希值仅作历史证据，不参与任何判断。
     */
    private static final String[][] OBSOLETE_NAMES = {
            {"04 内置英文单词.txt", "04 Built-in English words.txt"},
            {"05 内置号码变形.txt", "05 Built-in number variants.txt"},
            {"06 WPA 高频真实口令 Top 447.txt", "06 Real-world WPA top 447.txt"},
            {"06 WPA 高频真实口令 TOP 447.txt", "06 Real-world WPA top 447.txt"},
    };

    /**
     * 释放版本。**改动内置内容（不只是在 ITEMS 里增删条目）时必须加一** ——
     * 加一以后 {@link #release} 才会重跑；而重跑会不会真的覆盖已有文件，
     * 由 {@link Manifest} 决定（见 {@link #release} 的判据表）。
     * 6 = 手机号段两条规则（93/94）之后，本轮修掉「内容改了不生效 / 废弃表删不掉」。
     * 7 = 清单改成 v2 格式（「来源不可证」记 {@code ?}，永不覆盖用户改动）——
     *     加一才能让 v6 那份**含有毒记录**的旧清单被丢弃重建。
     */
    private static final int RELEASE_VERSION = 7;
    private static final String MARK = ".builtin_v";
    /** 发行清单：记录**我们上次释放时**写下的内容哈希，用来区分「我们放的」与「用户改的」。 */
    private static final String MANIFEST = ".builtin_manifest";
    /**
     * 清单里表示「来源不可证」的哨兵值。
     *
     * <p>首次升级时没有清单，若盘上内容与出厂内容不同，我们**无法区分**
     * 「老版本放的旧内容」与「用户自己改的」。这种情况必须记 {@code ?} 而不是
     * 把盘上哈希记下来 —— 记了的话，下一次升级就会把用户改过的文件当成
     * 「我们上次放的」而覆盖掉，正是要保住的那条保护。
     * 真机证据：用户的 `01 内置弱口令常用` 是 2496 行（他自己加过条目）。
     */
    private static final String UNKNOWN = "?";
    /** 清单格式版本前缀。带前缀的行才认；老格式（无前缀）一律当成「来源不可证」。 */
    private static final String MANIFEST_V2 = "v2";

    /**
     * 内置表出处与许可（2026-11-15 落档）。
     *
     * <h3>为什么要单列一张表</h3>
     * 内置 assets 属于**随包再分发**，比「在线下载、落在用户机器上」严得多：
     * 前者的分发者是本应用，后者是用户自己。所以每一条内置表都必须能回答
     * 「从哪来、什么许可」。此前 `Item` 里只有名字/档位/理由，**没有任何出处字段** ——
     * 而 {@code AboutActivity} 的致谢里却笼统写着「来自 SecLists 等项目」，
     * 这个说法对其中几份**并不成立**。
     *
     * <h3>填写口径（只写能核实的）</h3>
     * <ul>
     *   <li>能核实的写具体：SecLists 的 LICENSE 实测为
     *       <b>MIT License, Copyright (c) 2018 Daniel Miessler</b>
     *       （danielmiessler/SecLists@master/LICENSE，2026-11-15 取）；</li>
     *   <li>核不实的**不许写成 MIT 之类的具体许可**，一律「未声明 / 待核实」，
     *       并在关于页如实披露 —— 宁可写得难看，也不能替第三方许加拿大。</li>
     * </ul>
     */
    private static final String[][] PROVENANCE = {
            {"wpa_top62.txt", "SecLists (danielmiessler/SecLists) 的 WPA 高频口令统计",
                    "MIT (c) 2018 Daniel Miessler"},
            {"wpa_top4800.txt", "SecLists (danielmiessler/SecLists) 的 WPA 主表",
                    "MIT (c) 2018 Daniel Miessler"},
            {"weak.txt", "搬运自某第三方 WiFi 工具的内置表（原工具未声明来源）", "未声明"},
            {"digits.txt", "搬运自某第三方 WiFi 工具的内置表（原工具未声明来源）", "未声明"},
            {"alnum.txt", "搬运自某第三方 WiFi 工具的内置表（原工具未声明来源）", "未声明"},
            {"cn_top.txt", "中文口令集，搬运自第三方工具内置表（原工具未声明来源）", "未声明"},
            {"top2025.txt", "年度高频口令榜（下载来源页未记录）", "未声明"},
            {"xato_top.txt", "疑为 SecLists 的 xato-net-10-million 系列，**未能核实对应文件**", "待核实"},
            {"rule_8digit.txt", "本项目自制规则（区间描述，不含第三方数据）", "本项目"},
            {"rule_10digit.txt", "本项目自制规则（区间描述，不含第三方数据）", "本项目"},
            {"rule_date.txt", "本项目自制规则（区间描述，不含第三方数据）", "本项目"},
            {"rule_phone_cn.txt", "本项目自制（号段取自工信部公告 → 中文维基号段分配表）", "本项目"},
            {"rule_phone_local.txt", "本项目自制（模板，不含量数据）", "本项目"},
    };

    /** 某 asset 的出处；没登记返回空串（门禁会判红）。 */
    public static String sourceOf(String asset) {
        for (String[] row : PROVENANCE) if (row[0].equals(asset)) return row[1];
        return "";
    }

    /** 某 asset 的许可；没登记返回空串（门禁会判红）。 */
    public static String licenseOf(String asset) {
        for (String[] row : PROVENANCE) if (row[0].equals(asset)) return row[2];
        return "";
    }

    /** 是否属于「许可未声明 / 待核实」—— 关于页要如实披露这一批。 */
    public static boolean licenseUnclear(String asset) {
        String lic = licenseOf(asset);
        return lic.isEmpty() || "未声明".equals(lic) || "待核实".equals(lic);
    }

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
        /** 新写入或按出厂内容更新成功的本数 */
        public final int released;
        /** 盘上内容已与出厂内容一致、无需动作的本数 */
        public final int skipped;
        /** 移入回收站的废弃内置表数量 */
        public final int purged;
        /** 因为「用户改过」而保留的本数 */
        public final int keptEdited;
        /** 疑似废弃但认不出来、保留的本数 */
        public final int keptUnknown;
        public final String message;

        Result(int r, int s, int p, int ke, int ku, String m) {
            released = r;
            skipped = s;
            purged = p;
            keptEdited = ke;
            keptUnknown = ku;
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
     *
     * <h3>每个文件走哪条分支（判据表）</h3>
     * <pre>
     *   盘上不存在                        → 写入                       (released++)
     *   盘上内容 == 出厂内容               → 不动，记进清单              (skipped++)
     *   盘上内容 == 清单记录               → 我们放的、用户没动过 → 覆盖   (released++)
     *   盘上内容 != 清单记录，且清单有记录   → 用户改过 → 原样保留          (keptEdited++)
     *   清单无记录（首次升级）              → 无法证明是我们放的 → 保留并记账 (keptEdited++)
     * </pre>
     *
     * <p>第 3 条是本轮修的核心：旧实现「存在就跳过」，于是**改了内置内容老用户永远拿不到**。
     * 现在「用户没动过」是可证的（清单里的哈希与盘上一致），所以能安全覆盖；
     * 用户动过的一律不碰 —— 保住原来那条保护，同时把更新打通。
     *
     * <p>废弃表一律**移入回收站**（{@link DictTrash}），不直接删：第一档能证明是我们的，
     * 第二档是推定，推定错了用户还能捞回来。
     */
    public static Result release(Context ctx) {
        File dir = DictStore.defaultDir(ctx);
        if (!dir.exists() && !dir.mkdirs()) {
            return new Result(0, 0, 0, 0, 0, L.s("无法创建字典文件夹", "Cannot create dictionary folder"));
        }

        Manifest mani = Manifest.load(dir);
        int written = 0, same = 0, keptEdited = 0;
        StringBuilder fail = new StringBuilder();

        for (Item item : ITEMS) {
            String fn = item.fileName();
            File out = new File(dir, fn);
            String assetHash = assetSha256(ctx, "builtin/" + item.asset);
            String diskHash = (out.isFile() && out.length() > 0) ? sha256(out) : null;

            if (diskHash == null) {
                if (copyAsset(ctx, "builtin/" + item.asset, out)) {
                    written++;
                    mani.put(fn, sha256(out));
                } else {
                    fail.append(fn).append(' ');
                }
                continue;
            }
            if (assetHash != null && assetHash.equals(diskHash)) {
                same++;
                mani.put(fn, diskHash);
                continue;
            }
            String last = mani.get(fn);
            if (last != null && !UNKNOWN.equals(last) && last.equals(diskHash)) {
                // 我们上次放的、用户没动过 → 可以安全地按当前出厂内容更新
                if (copyAsset(ctx, "builtin/" + item.asset, out)) {
                    written++;
                    mani.put(fn, sha256(out));
                } else {
                    fail.append(fn).append(' ');
                }
            } else {
                keptEdited++;
                // 首次升级（无记录）或记录本身就是「不可证」→ 一律记 ?,永不覆盖
                if (last == null || UNKNOWN.equals(last)) mani.put(fn, UNKNOWN);
            }
        }

        int trashed = 0, keptUnknown = 0;
        for (String[] row : OBSOLETE_NAMES) {
            for (int i = 0; i < row.length; i++) {
                String n = row[i];
                if (find(n) != null) continue;              // 这个名字又被启用了，不动
                File f = new File(dir, n);
                if (!f.isFile() || f.length() <= 0) continue;
                String h = sha256(f);
                String last = mani.get(n);
                // 有记录且被改过 → 保留；其余（无记录 / 记录为"?" / 内容未变）→ 判为我们的 → 回收
                if (last == null || UNKNOWN.equals(last) || last.equals(h)) {
                    if (DictTrash.trash(dir, f) != null) {
                        trashed++;
                        mani.remove(n);
                        mani.remove(row[0]);
                        mani.remove(row[1]);
                    }
                } else {
                    keptUnknown++;
                }
            }
        }

        mani.save(dir);
        cleanOldMarks(dir);
        writeMark(dir);

        String msg = L.s("释放 " + written + " 个，已是最新 " + same + " 个",
                written + " restored, " + same + " already current");
        if (keptEdited > 0) {
            msg += L.s("，保留你改过的 " + keptEdited + " 个（不覆盖）",
                    ", " + keptEdited + " kept as you edited them");
        }
        if (trashed > 0) {
            msg += L.s("，废弃表 " + trashed + " 个已移入回收站（可恢复）",
                    ", " + trashed + " obsolete moved to trash (recoverable)");
        }
        if (keptUnknown > 0) {
            msg += L.s("，另有 " + keptUnknown + " 个疑似废弃但已被改动，保留",
                    ", " + keptUnknown + " look obsolete but were modified; kept");
        }
        if (fail.length() > 0) msg += L.s("，失败: ", ", failed: ") + fail;
        return new Result(written, same, trashed, keptEdited, keptUnknown, msg);
    }

    /** 只保留最新一个标记文件（旧版本会在目录里堆 .builtin_v1…v5 五个） */
    private static void cleanOldMarks(File dir) {
        try {
            File[] fs = dir.listFiles();
            if (fs == null) return;
            String keep = MARK + RELEASE_VERSION;
            for (File f : fs) {
                String n = f.getName();
                if (!n.startsWith(MARK) || n.equals(keep)) continue;
                f.delete();
            }
        } catch (Throwable ignored) {}
    }

    private static void writeMark(File dir) {
        try (FileOutputStream f = new FileOutputStream(new File(dir, MARK + RELEASE_VERSION))) {
            f.write((System.currentTimeMillis() + "\n").getBytes());
        } catch (Exception ignored) {}
    }

    /**
     * 发行清单：文件名 → 我们上次释放时写下的内容哈希。
     *
     * <p>格式一行一条：{@code <sha256>\t<文件名>}（文件名可能带空格，所以用制表符分隔）。
     * 读不出来就当空清单 —— 代价只是本次不覆盖任何已有文件，**不会误删**。
     */
    static final class Manifest {
        private final java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();

        static Manifest load(File dir) {
            Manifest m = new Manifest();
            File f = new File(dir, MANIFEST);
            if (!f.isFile()) return m;
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new FileInputStream(f), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\t", -1);
                    // 只认 v2 格式：v2 <哈希或?> <文件名>
                    if (p.length < 3 || !MANIFEST_V2.equals(p[0].trim())) continue;
                    String h = p[1].trim();
                    String n = p[2].trim();
                    if (!h.isEmpty() && !n.isEmpty()) m.map.put(n, h);
                }
            } catch (Exception ignored) {}
            return m;
        }

        String get(String name) {
            return name == null ? null : map.get(name);
        }

        void put(String name, String hash) {
            if (name != null && hash != null) map.put(name, hash);
        }

        void remove(String name) {
            if (name != null) map.remove(name);
        }

        void save(File dir) {
            try {
                File dst = new File(dir, MANIFEST);
                File tmp = new File(dir, MANIFEST + ".tmp");
                try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                        new FileOutputStream(tmp), "UTF-8")) {
                    for (java.util.Map.Entry<String, String> e : map.entrySet()) {
                        w.write(MANIFEST_V2);
                        w.write('\t');
                        w.write(e.getValue());
                        w.write('\t');
                        w.write(e.getKey());
                        w.write('\n');
                    }
                }
                if (!tmp.renameTo(dst)) {
                    // 某些文件系统上 rename 覆盖会失败：退化成删了再改名
                    dst.delete();
                    if (!tmp.renameTo(dst)) tmp.delete();
                }
            } catch (Exception ignored) {}
        }
    }

    /** 文件 sha256；失败返回 null（读不了就当用户改过，保留） */
    static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return sha256(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** 流 sha256（同样用于 assets：要跟盘上的文件比对，就得能对 asset 也取哈希）。 */
    static String sha256(InputStream in) {
        try {
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

    /** 某个 asset 的 sha256；打不开返回 null（拿不到就当「不知道出厂内容」，退回保守分支）。 */
    static String assetSha256(Context ctx, String assetPath) {
        try (InputStream in = ctx.getAssets().open(assetPath)) {
            return sha256(in);
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
