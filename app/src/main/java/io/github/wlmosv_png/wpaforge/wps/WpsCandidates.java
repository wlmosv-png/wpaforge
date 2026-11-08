package io.github.wlmosv_png.wpaforge.wps;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * WPS 候选 PIN 的另两个来源：内置弱值表 + 用户导入的自定义列表。
 *
 * 为什么需要它们：厂商算法只覆盖「PIN 由 MAC 派生」的设备。剩下的设备里，
 * 相当一部分用的是出厂默认弱值或用户随手设的值 —— 这些只能靠弱值表命中；
 * 再剩下的，用户可能从别处拿到了候选，那就得能导进来。
 *
 * 解析契约（{@link #parse(String)}）：**绝不静默改写、绝不静默丢数据**。
 * 用户给的 8 位值原样收录（哪怕校验位不合法，只在 {@link Report#issues} 里提醒），
 * 无法识别的数字串进 Issue 并带位置，被上限截断也要报告。
 *
 * 纯逻辑 + 文件读写，可离线测试。
 */
public final class WpsCandidates {

    private WpsCandidates() {
    }

    public static final String FILE_NAME = "wps_pins.txt";

    /** 输入文本上限（字符）。超出即截断，并在 {@link Report} 里报告。 */
    public static final int MAX_INPUT_CHARS = 256 * 1024;

    /** 候选上限（去重后的条数）。超出即停止收录，并在 {@link Report} 里报告。 */
    public static final int MAX_CANDIDATES = 4096;

    /** Issue 清单上限：防止 256KB 的垃圾输入造出十万条错误对象。 */
    public static final int MAX_ISSUES = 4096;

    /** {@link Issue#raw} 的最大长度。 */
    public static final int RAW_MAX = 40;

    /**
     * 内置弱 PIN 表（前 7 位，第 8 位校验位由 {@link WpsPins#complete} 补）。
     *
     * 顺序即尝试顺序：
     *   1. 全同数字 / 连续数字 —— 真实世界最常见的出厂值
     *   2. 常见键盘模式
     *   3. 已知厂商固定默认 PIN（同 WpsVendorPins 里的静态表）
     */
    public static List<String> builtinWeak() {
        String[] heads = {
                // 全同
                "1234567", "0000000", "1111111", "2222222", "3333333",
                "4444444", "5555555", "6666666", "7777777", "8888888",
                "9999999",
                // 连续 / 逆向
                "0123456", "9876543", "2345678", "7654321", "4567890",
                "1234560", "1023456", "8987654", "9999990", "1000000",
                // 常见模式
                "1212121", "1231231", "1122334", "1020304", "0102030",
                "1112223", "1234321", "1234123", "1122330", "1010101",
                "2000000", "3000000", "4000000", "5000000", "0000001",
                "0707070", "1234432", "1111112", "2222223", "3141592",
                // 已知厂商固定默认 PIN（7 位形态）
                "2017252", "4626484", "7622990", "6232714", "1086411",
                "3195719", "3043203", "7141225", "6817554", "9566146",
                "9571911", "4856371", "2085483", "4397768", "529417",
                "9995604", "3561153", "6795814", "3425928", "9422988",
                "9575521", "1234568", "1234000", "0001112", "2222220",
        };
        Set<String> set = new LinkedHashSet<>();
        for (String h : heads) {
            String pin = WpsPins.complete(h);
            if (pin != null) set.add(pin);
        }
        return new ArrayList<>(set);
    }

    // ---------------- 拆半攻击（11000 次必然命中） ----------------
    //
    // WPS 校验 PIN 是分两半做的：前 4 位解 M2、后 4 位解 M4。
    // 所以固定一半、穷举另一半就够了：
    //   阶段 1：后 3 位固定 000，穷举前 4 位          → 10,000 次
    //   阶段 2：前 4 位已确认，穷举后 3 位（第 8 位是校验位）→ 1,000 次
    // 合计 11,000 次，而不是 10^7。

    static String pad(int v, int width) {
        String s = Integer.toString(v);
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < width; i++) sb.append('0');
        return sb.append(s).toString();
    }

    /** 前 4 位 + 后 3 位 → 合法 8 位 PIN（校验位自动补）。 */
    public static String halfPin(String prefix4, String suffix3) {
        if (prefix4 == null || suffix3 == null) return null;
        if (prefix4.length() != 4 || suffix3.length() != 3) return null;
        return WpsPins.complete(prefix4 + suffix3);
    }

    /** 阶段 1 第 i 个候选（i: 0..9999），后 3 位固定 000。 */
    public static String stage1Pin(int i) {
        if (i < 0 || i > 9999) return null;
        return halfPin(pad(i, 4), "000");
    }

    /** 阶段 2 第 i 个候选（i: 0..999），前 4 位用已确认的。 */
    public static String stage2Pin(String prefix4, int i) {
        if (i < 0 || i > 999) return null;
        return halfPin(prefix4, pad(i, 3));
    }

    /** 拆半总次数上界。 */
    public static int halfTotal() {
        return 10000 + 1000;
    }

    /**
     * 某一阶段的候选总数（**UI 的唯一分母出处**）。
     *
     * <p>修复前这个 10000 / 1000 是三目常量写死在 {@code refreshWpsProgress} 里的，
     * 与引擎真实的阶段游标分属两处，改一处就会不一致。
     */
    public static int phaseTotal(int phase) {
        return phase == 2 ? 1000 : 10000;
    }

    // ---------------- 自定义列表：解析 + 反馈 ----------------

    /** 一条被拒条目（或需要提醒的条目）。 */
    public static final class Issue {
        /** 在输入里的字符位置；对「上限」类问题为触发位置，未知为 -1。 */
        public final int pos;
        /** 原文片段（截断到 {@link #RAW_MAX} 字符）。 */
        public final String raw;
        /** 中文原因。 */
        public final String reason;

        public Issue(int pos, String raw, String reason) {
            this.pos = pos;
            this.raw = raw == null ? "" : raw;
            this.reason = reason == null ? "" : reason;
        }

        /** 单行可读描述。 */
        public String describe(boolean zh) {
            StringBuilder sb = new StringBuilder();
            sb.append(pos >= 0 ? (zh ? "位置 " + pos : "pos " + pos) : (zh ? "位置 ?" : "pos ?"));
            sb.append(zh ? " —— " : " -- ").append(reason);
            if (!raw.isEmpty()) sb.append(zh ? "；原文: " : "; raw: ").append(raw);
            return sb.toString();
        }
    }

    /** 解析结果：收录了什么、拒了什么、为什么、有没有被上限截断。 */
    public static final class Report {
        /** 去重后的候选，顺序 = 首次出现。 */
        public final List<String> accepted;
        /** 全部问题（拒收 + 提醒），按出现顺序。 */
        public final List<Issue> issues;
        /** 被拒条目数（未产生任何候选的条目）。 */
        public final int invalidCount;
        /** 是否因上限截断（输入过长 或 候选已达上限）。 */
        public final boolean truncated;
        /** 额外：被收录但有提醒的条目数（原样使用的非法校验位 / 补全过的 7 位）。 */
        public final int warnCount;
        /** 额外：因上限未收录的有效条目数。 */
        public final int overflowCount;
        /** 额外：issues 是否因 {@link #MAX_ISSUES} 被截断。 */
        public final boolean issuesTruncated;

        Report(List<String> accepted, List<Issue> issues, int invalidCount, int warnCount,
               int overflowCount, boolean truncated, boolean issuesTruncated) {
            this.accepted = Collections.unmodifiableList(new ArrayList<>(accepted));
            this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
            this.invalidCount = invalidCount;
            this.warnCount = warnCount;
            this.overflowCount = overflowCount;
            this.truncated = truncated;
            this.issuesTruncated = issuesTruncated;
        }

        /** 一句话结论；空输入 / 全无效 / 全有效都不会返回空串。 */
        public String summary(boolean zh) {
            if (accepted.isEmpty() && issues.isEmpty()) {
                return zh ? "未发现任何 PIN：输入为空或不含数字串"
                        : "No PIN found: empty input or no digit runs";
            }
            StringBuilder sb = new StringBuilder();
            if (zh) {
                sb.append("有效 ").append(accepted.size()).append(" 条 / 无效 ").append(invalidCount).append(" 条");
                if (warnCount > 0) sb.append("（其中 ").append(warnCount).append(" 条有问题但仍被收录）");
                appendTailZh(sb);
            } else {
                sb.append(accepted.size()).append(" valid / ").append(invalidCount).append(" invalid");
                if (warnCount > 0) sb.append(" (").append(warnCount).append(" kept with warnings)");
                appendTailEn(sb);
            }
            return sb.toString();
        }

        private void appendTailZh(StringBuilder sb) {
            int p = firstIssuePos();
            if (p >= 0) sb.append(" / 首个问题在位置 ").append(p);
            else if (!issues.isEmpty()) sb.append(" / 有 ").append(issues.size()).append(" 条问题");
            else if (invalidCount == 0 && warnCount == 0) sb.append("（全部合法）");
            if (overflowCount > 0) sb.append(" / 另有 ").append(overflowCount).append(" 条因上限未收录");
            if (truncated) sb.append("（已因上限截断）");
        }

        private void appendTailEn(StringBuilder sb) {
            int p = firstIssuePos();
            if (p >= 0) sb.append(" / first issue at pos ").append(p);
            else if (!issues.isEmpty()) sb.append(" / ").append(issues.size()).append(" issue(s)");
            else if (invalidCount == 0 && warnCount == 0) sb.append(" (all valid)");
            if (overflowCount > 0) sb.append(" / ").append(overflowCount).append(" dropped by cap");
            if (truncated) sb.append(" (truncated by limit)");
        }

        private int firstIssuePos() {
            for (Issue it : issues) if (it.pos >= 0) return it.pos;
            return -1;
        }

        /**
         * 逐条问题清单（含位置）。maxItems &lt;= 0 时只给标题与总数。
         *
         * 空输入 / 全无效 / 全有效都返回可读文案，不会是空串。
         */
        public String detail(boolean zh, int maxItems) {
            if (issues.isEmpty()) {
                if (accepted.isEmpty()) {
                    return zh ? "问题清单：无（输入里没有可识别的条目）"
                            : "Issues: none (nothing recognizable in the input)";
                }
                return zh ? "问题清单：无（所有条目都可用）" : "Issues: none (all entries usable)";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(zh ? "问题清单（共 " + issues.size() + " 条）"
                    : "Issues (" + issues.size() + "):");
            int shown = maxItems <= 0 ? 0 : Math.min(maxItems, issues.size());
            for (int i = 0; i < shown; i++) {
                sb.append('\n').append("  #").append(i + 1).append(' ')
                        .append(issues.get(i).describe(zh));
            }
            int rest = issues.size() - shown;
            if (rest > 0) sb.append('\n').append(zh ? "  …还有 " + rest + " 条未显示"
                    : "  ... " + rest + " more not shown");
            return sb.toString();
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static String clip(String s) {
        if (s == null) return "";
        if (s.length() <= RAW_MAX) return s;
        return s.substring(0, RAW_MAX - 1) + "…";
    }

    /** 解析累加器：把「收录 / 拒收 / 提醒 / 上限」的计数集中在一处。 */
    private static final class Acc {
        final Set<String> seen = new LinkedHashSet<>();
        final List<Issue> issues = new ArrayList<>();
        int invalidCount;
        int warnCount;
        int overflowCount;
        boolean issuesTruncated;
        boolean stopped;
        int firstOverflowPos = -1;
        String firstOverflowRaw = "";

        void issue(int pos, String raw, String reason) {
            if (issues.size() < MAX_ISSUES) {
                issues.add(new Issue(pos, clip(raw), reason));
            } else if (!issuesTruncated) {
                issuesTruncated = true;
                issues.add(new Issue(-1, "", "错误条目过多，仅显示前 " + MAX_ISSUES + " 条"));
            }
        }

        /** 收录一个（8 位）值。warnReason != null 表示「收录但有提醒」。 */
        void add(String pin, int pos, String raw, String warnReason) {
            if (!seen.contains(pin)) {
                if (seen.size() >= MAX_CANDIDATES) {
                    if (firstOverflowPos < 0) {
                        firstOverflowPos = pos;
                        firstOverflowRaw = raw;
                    }
                    overflowCount++;
                    stopped = true;
                    return;
                }
                seen.add(pin);
            }
            if (warnReason != null) {
                warnCount++;
                issue(pos, raw, warnReason);
            }
        }
    }

    /**
     * 把一段文本解析成候选列表（带完整反馈）。
     *
     * 规则（顺序即优先级）：
     *   1. 数字串长度 7 → 补校验位成 8 位，收录 + 提醒；
     *   2. 数字串长度 8 或 8 的整数倍 → 每 8 位一段；校验位合法直接收录，
     *      校验位不合法**原样收录**（绝不重算改写），只加一条提醒；
     *   3. 其它长度 → 整串拒收（不切尾巴），记一条带位置的 Issue；
     *   4. 非数字字符只作分隔（日期/时间里的数字不特殊对待，靠长度规则自然挡掉）；
     *   5. 输入超过 {@link #MAX_INPUT_CHARS} → 截断并报告（截断点若落在数字串中间，
     *      整段数字串长度未知、无法套用规则 3，故整段丢弃并说明，绝不猜长度）；
     *      候选超过 {@link #MAX_CANDIDATES} → 停止收录并报告剩余条数；
     *   6. 去重按最终 8 位值，顺序保留首次出现。
     */
    public static Report parse(String text) {
        String whole = text == null ? "" : text;
        Acc acc = new Acc();
        boolean truncated = false;
        String src = whole;

        if (whole.length() > MAX_INPUT_CHARS) {
            truncated = true;
            int cut = MAX_INPUT_CHARS;
            String partial = "";
            if (isDigit(whole.charAt(cut)) && isDigit(whole.charAt(cut - 1))) {
                // 截断点落在数字串中间：这段一定不完整，丢掉而不是当成候选。
                int s = cut - 1;
                while (s > 0 && isDigit(whole.charAt(s - 1))) s--;
                partial = whole.substring(s, cut);
                cut = s;
            }
            src = whole.substring(0, cut);
            int dropped = whole.length() - cut;
            String raw = partial.isEmpty()
                    ? whole.substring(cut, Math.min(whole.length(), cut + RAW_MAX)) : partial;
            acc.issue(cut, raw, "输入超过上限 " + MAX_INPUT_CHARS + " 字符，已截断（丢弃尾部 "
                    + dropped + " 字符）" + (partial.isEmpty() ? "" : "，末尾不完整的数字串也已丢弃"));
        }

        int i = 0;
        while (i < src.length()) {
            if (!isDigit(src.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < src.length() && isDigit(src.charAt(i))) i++;
            handleRun(src, start, i - start, acc);
            if (acc.stopped) {
                acc.overflowCount += countPinRuns(src, i);
                acc.issue(acc.firstOverflowPos, acc.firstOverflowRaw,
                        "候选已达上限 " + MAX_CANDIDATES + " 条，剩余 " + acc.overflowCount + " 条未收录");
                truncated = true;
                break;
            }
        }

        return new Report(new ArrayList<>(acc.seen), acc.issues, acc.invalidCount,
                acc.warnCount, acc.overflowCount, truncated, acc.issuesTruncated);
    }

    private static void handleRun(String src, int start, int len, Acc acc) {
        String run = src.substring(start, start + len);
        if (len == 7) {
            String pin = WpsPins.complete(run);
            if (pin == null) {
                acc.invalidCount++;
                acc.issue(start, run, "7 位数字但无法补全校验位");
                return;
            }
            acc.add(pin, start, run, "7 位数字，已补校验位 → " + pin);
            return;
        }
        if (len % 8 == 0) {
            // 长度 8 与长度 8 的倍数走**同一条**路径（历史上两条路径语义不一致）。
            for (int k = 0; k + 8 <= len; k += 8) {
                String seg = run.substring(k, k + 8);
                if (WpsPins.valid(seg)) acc.add(seg, start + k, seg, null);
                else acc.add(seg, start + k, seg, "校验位不合法，已原样使用（AP 可能直接拒绝）");
            }
            return;
        }
        acc.invalidCount++;
        acc.issue(start, run, "无法识别的数字串（长度 " + len
                + "）：既不是 7 位或 8 位，也不是 8 的整数倍");
    }

    /** 从 from 起还能凑出候选的数字串条数（用于报告「剩余未收录」）。 */
    private static int countPinRuns(String src, int from) {
        int n = 0;
        int i = from;
        while (i < src.length()) {
            if (!isDigit(src.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < src.length() && isDigit(src.charAt(i))) i++;
            int len = i - start;
            if (len == 7 || len % 8 == 0) n++;
        }
        return n;
    }

    /**
     * 兼容旧签名：只返回收录的候选。
     *
     * <p>语义已随 {@link #parse(String)} 一起收紧：不再把校验位不合法（但格式没错）用户值
     * 改写成重算值，也不再把长数字串左对齐切片丢尾巴 —— 这些差异只能从
     * {@link Report#issues} 看到，所以新代码请直接用 {@link #parse(String)}。
     */
    public static List<String> parseList(String text) {
        return parse(text).accepted;
    }

    /** 读取用户保存的列表；没有则返回空。 */
    public static List<String> loadCustom(File dir) {
        List<String> out = new ArrayList<>();
        if (dir == null) return out;
        File f = new File(dir, FILE_NAME);
        if (!f.exists() || f.length() == 0) return out;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return parseList(sb.toString());
        } catch (Throwable t) {
            return out;
        } finally {
            if (br != null) try {
                br.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 保存（append=false 覆盖，true 追加）。返回保存后的总数。 */
    public static int saveCustom(File dir, String text, boolean append) {
        if (dir == null || text == null) return 0;
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, FILE_NAME);
        List<String> merged = new ArrayList<>();
        if (append) merged.addAll(loadCustom(dir));
        Set<String> set = new LinkedHashSet<>(merged);
        set.addAll(parseList(text));

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f, false);
            StringBuilder sb = new StringBuilder();
            for (String p : set) sb.append(p).append('\n');
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Throwable t) {
            return merged.size();
        } finally {
            if (fos != null) try {
                fos.close();
            } catch (Throwable ignored) {
            }
        }
        return set.size();
    }

    /** 清空自定义列表。 */
    public static boolean clearCustom(File dir) {
        if (dir == null) return false;
        File f = new File(dir, FILE_NAME);
        try {
            return !f.exists() || f.delete();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 一次性把三个来源拼成交互顺序：厂商算法 → 弱值表 → 自定义 → （可选）完整枚举。 */
    public static List<WpsVendorPins.Candidate> tiered(String bssid, String sn,
                                                      File dir, boolean includeWeak,
                                                      boolean includeCustom) {
        List<WpsVendorPins.Candidate> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (WpsVendorPins.Candidate c : WpsVendorPins.suggest(bssid, sn)) {
            if (seen.add(c.pin)) out.add(c);
        }
        if (includeWeak) {
            for (String p : builtinWeak()) {
                if (seen.add(p)) out.add(new WpsVendorPins.Candidate(p, "weak", false));
            }
        }
        if (includeCustom) {
            for (String p : loadCustom(dir)) {
                if (seen.add(p)) out.add(new WpsVendorPins.Candidate(p, "custom", false));
            }
        }
        return out;
    }
}
