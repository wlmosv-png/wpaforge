package io.github.wlmosv_png.wpaforge.wps;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * WpsCandidates 导入解析的离线测试。
 *
 * 这块的契约只有一句：**绝不静默改写、绝不静默丢数据**。
 * 所以每条测试都在钉「用户给了什么 → 我们收录/拒绝了什么 → 有没有告诉他为什么」。
 */
public class WpsCandidatesTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  " + detail);
        }
    }

    public static void main(String[] a) {
        System.out.println("== 常量 ==");
        ok("t_limits_values", WpsCandidates.MAX_INPUT_CHARS == 256 * 1024
                && WpsCandidates.MAX_CANDIDATES == 4096,
                WpsCandidates.MAX_INPUT_CHARS + " / " + WpsCandidates.MAX_CANDIDATES);

        System.out.println("== 8 位合法 ==");
        WpsCandidates.Report r1 = WpsCandidates.parse("12345670");
        ok("t_valid8_accepted", eq(r1.accepted, "12345670"), str(r1.accepted));
        ok("t_valid8_no_issue", r1.issues.isEmpty() && r1.invalidCount == 0 && r1.warnCount == 0,
                "issues=" + r1.issues.size() + " invalid=" + r1.invalidCount + " warn=" + r1.warnCount);
        ok("t_valid8_not_truncated", !r1.truncated, "");
        ok("t_valid8_summary_all_valid",
                r1.summary(true).contains("有效 1 条") && r1.summary(true).contains("全部合法"),
                r1.summary(true));

        System.out.println("== 8 位但校验位不合法：原样保留，不再改写 ==");
        WpsCandidates.Report r2 = WpsCandidates.parse("12345678");
        ok("t_bad8_kept_verbatim", eq(r2.accepted, "12345678"), str(r2.accepted));
        ok("t_bad8_not_rewritten_to_recomputed", !r2.accepted.contains("12345670"), str(r2.accepted));
        ok("t_bad8_issue_present", r2.issues.size() == 1
                && r2.issues.get(0).reason.contains("原样使用"), str(r2.issues));
        ok("t_bad8_invalidcount_semantics", r2.invalidCount == 0 && r2.warnCount == 1,
                "invalid=" + r2.invalidCount + " warn=" + r2.warnCount);
        ok("t_bad8_issue_pos_and_raw", r2.issues.get(0).pos == 0
                && "12345678".equals(r2.issues.get(0).raw), str(r2.issues));
        ok("t_bad8_summary_warns_but_counts_valid",
                r2.summary(true).startsWith("有效 1 条 / 无效 0 条")
                        && r2.summary(true).contains("仍被收录"), r2.summary(true));

        WpsCandidates.Report r2b = WpsCandidates.parse("12345678 12345678");
        ok("t_bad8_dedup_keeps_warning_per_occurrence",
                r2b.accepted.size() == 1 && r2b.warnCount == 2 && r2b.issues.size() == 2,
                "acc=" + r2b.accepted.size() + " warn=" + r2b.warnCount + " issues=" + r2b.issues.size());

        System.out.println("== 7 位：补校验位（文档化补全） ==");
        WpsCandidates.Report r3 = WpsCandidates.parse("1234567");
        ok("t_7digit_completed", eq(r3.accepted, "12345670"), str(r3.accepted));
        ok("t_7digit_issue_note", r3.issues.size() == 1 && r3.warnCount == 1
                && r3.issues.get(0).reason.contains("已补校验位")
                && r3.issues.get(0).pos == 0, str(r3.issues));
        ok("t_7digit_invalidcount_zero", r3.invalidCount == 0, "" + r3.invalidCount);

        System.out.println("== 长度既不是 7/8 也不是 8 倍数：整串拒收，不许丢尾巴 ==");
        WpsCandidates.Report r4 = WpsCandidates.parse("912345670");
        ok("t_9digit_no_candidate", r4.accepted.isEmpty(), str(r4.accepted));
        ok("t_9digit_one_issue", r4.issues.size() == 1, str(r4.issues));
        ok("t_9digit_issue_has_pos_and_raw", r4.issues.get(0).pos == 0
                && "912345670".equals(r4.issues.get(0).raw)
                && r4.issues.get(0).reason.contains("长度 9"), str(r4.issues));
        ok("t_9digit_invalidcount_one", r4.invalidCount == 1 && !r4.truncated,
                "invalid=" + r4.invalidCount);
        ok("t_9digit_summary_readable",
                r4.summary(true).contains("有效 0 条 / 无效 1 条")
                        && r4.summary(true).contains("位置 0"), r4.summary(true));
        ok("t_9digit_detail_has_pos_and_raw",
                r4.detail(true, 10).contains("位置 0") && r4.detail(true, 10).contains("912345670"),
                r4.detail(true, 10));
        ok("t_parseList_drops_nothing_silently",
                WpsCandidates.parseList("912345670").isEmpty() && r4.issues.size() == 1, "");

        WpsCandidates.Report r5 = WpsCandidates.parse("123456701234567012");
        ok("t_18digit_no_candidate", r5.accepted.isEmpty(), str(r5.accepted));
        ok("t_18digit_one_issue", r5.issues.size() == 1
                && r5.issues.get(0).reason.contains("长度 18"), str(r5.issues));
        ok("t_18digit_invalidcount_one", r5.invalidCount == 1, "" + r5.invalidCount);

        WpsCandidates.Report r5b = WpsCandidates.parse("1" .repeat(100));
        ok("t_long_run_rejected_with_clipped_raw",
                r5b.accepted.isEmpty() && r5b.issues.size() == 1
                        && r5b.issues.get(0).raw.length() <= WpsCandidates.RAW_MAX
                        && r5b.issues.get(0).reason.contains("长度 100"),
                r5b.issues.isEmpty() ? "no issue" : ("" + r5b.issues.get(0).raw.length()));

        System.out.println("== 去重：按最终 8 位值，顺序 = 首次出现 ==");
        WpsCandidates.Report r6 = WpsCandidates.parse("12345670 12345670");
        ok("t_dedup_same_value", r6.accepted.size() == 1, str(r6.accepted));
        WpsCandidates.Report r6b = WpsCandidates.parse("00000000 12345670 00000000");
        ok("t_dedup_keeps_first_occurrence_order",
                r6b.accepted.equals(Arrays.asList("00000000", "12345670")), str(r6b.accepted));

        System.out.println("== 16 位（8 倍数）：切两段，两段同一套规则 ==");
        WpsCandidates.Report r7 = WpsCandidates.parse("0102030405060708");
        ok("t_16digit_two_candidates",
                r7.accepted.equals(Arrays.asList("01020304", "05060708")), str(r7.accepted));
        ok("t_16digit_bad_chunk_kept_verbatim", r7.accepted.contains("05060708")
                && !r7.accepted.contains("05060702"), str(r7.accepted));
        ok("t_16digit_warn_and_invalid_counts",
                r7.warnCount == 1 && r7.invalidCount == 0,
                "warn=" + r7.warnCount + " invalid=" + r7.invalidCount);
        WpsCandidates.Report r7b = WpsCandidates.parse("010203040506070812345670");
        ok("t_24digit_three_candidates", r7b.accepted.size() == 3, str(r7b.accepted));

        // 单串 8 位 与 长串切片 必须走同一套规则：同一段值得到同一结果。
        WpsCandidates.Report single = WpsCandidates.parse("12345678");
        WpsCandidates.Report chunked = WpsCandidates.parse("1234567812345678");
        ok("t_same_rules_single_vs_sliced", single.accepted.equals(chunked.accepted)
                && chunked.issues.size() == 2, str(chunked.accepted));

        System.out.println("== 混排：文字/注释/日期里的数字串 ==");
        String mix = "PIN: 12345670,\n 00000000  # 备注 2026-11-08";
        int p2026 = mix.indexOf("2026");
        int p11 = mix.indexOf("11", p2026);
        int p08 = mix.indexOf("08", p11);
        WpsCandidates.Report r8 = WpsCandidates.parse(mix);
        ok("t_mixed_two_candidates",
                r8.accepted.equals(Arrays.asList("12345670", "00000000")), str(r8.accepted));
        ok("t_mixed_issues_are_the_date_fragments", r8.issues.size() == 3
                        && r8.issues.get(0).pos == p2026
                        && r8.issues.get(1).pos == p11
                        && r8.issues.get(2).pos == p08,
                str(r8.issues));
        ok("t_mixed_invalidcount_three", r8.invalidCount == 3 && r8.warnCount == 0,
                "invalid=" + r8.invalidCount + " warn=" + r8.warnCount);
        ok("t_mixed_summary_readable",
                r8.summary(true).contains("有效 2 条 / 无效 3 条")
                        && r8.summary(true).contains("位置 " + p2026), r8.summary(true));
        ok("t_mixed_detail_lists_each", r8.detail(true, 10).contains("位置 " + p11)
                && r8.detail(true, 10).contains("2026"), r8.detail(true, 10));

        System.out.println("== 候选上限 ==");
        // 用合法 8 位值构造，避免 7 位补全带来的提醒噪音干扰上限断言。
        StringBuilder capIn = new StringBuilder();
        int capEntries = WpsCandidates.MAX_CANDIDATES + 100;
        for (int i = 0; i < capEntries; i++) capIn.append(pin8(i)).append('\n');
        ok("t_cap_input_within_input_limit",
                capIn.length() <= WpsCandidates.MAX_INPUT_CHARS, "" + capIn.length());
        WpsCandidates.Report rc = WpsCandidates.parse(capIn.toString());
        ok("t_cap_exactly_max_candidates",
                rc.accepted.size() == WpsCandidates.MAX_CANDIDATES, "" + rc.accepted.size());
        ok("t_cap_truncated_flag", rc.truncated, "");
        ok("t_cap_issue_explains_remaining",
                rc.issues.size() == 1 && rc.issues.get(0).reason.contains("上限")
                        && rc.issues.get(0).reason.contains("未收录")
                        && rc.issues.get(0).reason.contains("剩余 100 条"),
                str(rc.issues));
        ok("t_cap_overflow_count_100", rc.overflowCount == 100, "" + rc.overflowCount);
        ok("t_cap_accounting_closes",
                rc.accepted.size() + rc.overflowCount == capEntries && rc.invalidCount == 0,
                rc.accepted.size() + "+" + rc.overflowCount + " vs " + capEntries);
        ok("t_cap_first_candidate_is_00000000",
                "00000000".equals(rc.accepted.get(0)), rc.accepted.get(0));
        ok("t_cap_summary_mentions_overflow",
                rc.summary(true).contains("未收录") && rc.summary(true).contains("截断"),
                rc.summary(true));

        System.out.println("== 输入上限 ==");
        StringBuilder big = new StringBuilder();
        int bigEntries = 0;
        while (big.length() <= WpsCandidates.MAX_INPUT_CHARS + 5000) {
            big.append(pin8(bigEntries));
            for (int k = 0; k < 200; k++) big.append(' ');
            big.append('\n');
            bigEntries++;
        }
        ok("t_inputcap_text_longer_than_limit",
                big.length() > WpsCandidates.MAX_INPUT_CHARS, "" + big.length());
        WpsCandidates.Report rb = WpsCandidates.parse(big.toString());
        ok("t_inputcap_truncated_flag", rb.truncated, "");
        ok("t_inputcap_issue_explains_truncation",
                rb.issues.size() == 1 && rb.issues.get(0).reason.contains("截断")
                        && rb.issues.get(0).reason.contains("" + WpsCandidates.MAX_INPUT_CHARS),
                str(rb.issues));
        ok("t_inputcap_some_entries_kept_some_dropped",
                rb.accepted.size() >= 1000 && rb.accepted.size() < bigEntries,
                rb.accepted.size() + " / " + bigEntries);
        ok("t_inputcap_no_other_noise",
                rb.invalidCount == 0 && rb.overflowCount == 0,
                "invalid=" + rb.invalidCount + " overflow=" + rb.overflowCount);
        ok("t_inputcap_all_results_eight_digits_and_valid", allValid8(rb.accepted), "");

        // 截断点落在数字串中间：这一段长度未知，按规则 3 不能猜 → 整段丢弃并报告，
        // 不制造「猜出来的候选」。
        String giant = "1".repeat(WpsCandidates.MAX_INPUT_CHARS + 504);
        WpsCandidates.Report rg = WpsCandidates.parse(giant);
        ok("t_inputcap_partial_run_truncated_flag", rg.truncated, "");
        ok("t_inputcap_partial_run_dropped_with_reason",
                rg.accepted.isEmpty() && rg.issues.size() == 1
                        && rg.issues.get(0).reason.contains("不完整")
                        && rg.issues.get(0).raw.length() <= WpsCandidates.RAW_MAX,
                str(rg.issues) + " / " + str(rg.accepted));
        ok("t_inputcap_partial_run_no_fabricated_candidate",
                rg.invalidCount == 0 && rg.warnCount == 0, "" + rg.invalidCount);

        System.out.println("== 空 / null / 纯符号 ==");
        for (String in : new String[]{null, "", "   ", "+-*/ ### ！！"}) {
            WpsCandidates.Report re = WpsCandidates.parse(in);
            ok("t_empty_input_safe[" + in + "]",
                    re.accepted.isEmpty() && re.issues.isEmpty() && re.invalidCount == 0
                            && !re.truncated, str(re.issues));
            ok("t_empty_summary_readable[" + in + "]",
                    !re.summary(true).isEmpty() && !re.summary(false).isEmpty(),
                    re.summary(true) + " | " + re.summary(false));
            ok("t_empty_detail_readable[" + in + "]",
                    !re.detail(true, 10).isEmpty() && !re.detail(false, 10).isEmpty(),
                    re.detail(true, 10) + " | " + re.detail(false, 10));
        }

        System.out.println("== 全部无效 / 全部有效 的文案 ==");
        WpsCandidates.Report ri = WpsCandidates.parse("ab 123 456");
        ok("t_all_invalid_counts", ri.accepted.isEmpty() && ri.invalidCount == 2
                && ri.issues.size() == 2, str(ri.issues));
        ok("t_all_invalid_summary",
                ri.summary(true).contains("有效 0 条 / 无效 2 条")
                        && ri.summary(true).contains("位置 3"), ri.summary(true));
        ok("t_all_invalid_detail_lists_both",
                ri.detail(true, 10).contains("位置 3") && ri.detail(true, 10).contains("位置 7"),
                ri.detail(true, 10));
        ok("t_all_invalid_detail_limits_items",
                ri.detail(true, 1).contains("#1") && ri.detail(true, 1).contains("还有 1 条未显示"),
                ri.detail(true, 1));
        ok("t_all_valid_summary_zh_en",
                r1.summary(true).contains("有效 1 条") && r1.summary(false).contains("1 valid"),
                r1.summary(false));
        ok("t_no_issue_detail_readable",
                !r1.detail(true, 10).isEmpty() && !r1.detail(false, 10).isEmpty(),
                r1.detail(true, 10));

        System.out.println("== 兼容旧签名 parseList ==");
        ok("t_parseList_matches_accepted",
                WpsCandidates.parseList("12345678").equals(WpsCandidates.parse("12345678").accepted),
                str(WpsCandidates.parseList("12345678")));
        ok("t_parseList_null_safe", WpsCandidates.parseList(null).isEmpty(), "");
        ok("t_parseList_never_rewrites",
                WpsCandidates.parseList("22222222").equals(Arrays.asList("22222222")),
                str(WpsCandidates.parseList("22222222")));

        System.out.println("== saveCustom / loadCustom 往返：值不被改写 ==");
        File dir = new File("/tmp/wps-cand-test-roundtrip");
        WpsCandidates.clearCustom(dir);
        int saved = WpsCandidates.saveCustom(dir, "12345678\n912345670", false);
        List<String> back = WpsCandidates.loadCustom(dir);
        ok("t_roundtrip_count", saved == 1 && back.size() == 1, saved + " / " + str(back));
        ok("t_roundtrip_value_not_rewritten", back.equals(Arrays.asList("12345678")), str(back));
        ok("t_roundtrip_no_recomputed_value", !back.contains("12345670"), str(back));
        WpsCandidates.saveCustom(dir, "22222222\n12345678", true);
        List<String> back2 = WpsCandidates.loadCustom(dir);
        ok("t_roundtrip_append_keeps_invalid_verbatim",
                back2.equals(Arrays.asList("12345678", "22222222")), str(back2));
        ok("t_roundtrip_clear", WpsCandidates.clearCustom(dir)
                && WpsCandidates.loadCustom(dir).isEmpty(), str(WpsCandidates.loadCustom(dir)));

        System.out.println("=== WpsCandidatesTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String pad7(int v) {
        String s = Integer.toString(v);
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < 7; i++) sb.append('0');
        return sb.append(s).toString();
    }

    /** 第 i 个合法 8 位 PIN（互不相同）。 */
    private static String pin8(int i) {
        return WpsPins.complete(pad7(i));
    }

    private static boolean eq(List<String> l, String one) {
        return l.size() == 1 && one.equals(l.get(0));
    }

    private static boolean allValid8(List<String> l) {
        for (String p : l) {
            if (p.length() != 8 || !WpsPins.valid(p)) return false;
        }
        return !l.isEmpty();
    }

    private static String str(List<?> l) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(l.get(i));
        }
        return sb.append(']').toString();
    }
}
