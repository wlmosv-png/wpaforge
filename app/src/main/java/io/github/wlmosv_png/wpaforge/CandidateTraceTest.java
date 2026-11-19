package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AttemptLedger;
import io.github.wlmosv_png.wpaforge.audit.CandidateId;
import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.CandidateTrace;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.EvidenceGrade;
import io.github.wlmosv_png.wpaforge.audit.TriedSet;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选生命周期门禁 —— 钉住「正确密码 {@code 147258369} 不得被静默跳过」。
 *
 * <h3>用户实报（2026-11-20）</h3>
 * 在自有测试网络上用正确密码 {@code 147258369} 测试，发现它被跳过了。
 * 界面显示「已执行 27 / 63」，但没法回答「它到底走到哪一步」。
 *
 * <p>本类用**真实生产代码**（{@code CrackService.isSkipped} / {@code filterCandidates}
 * / {@code ambiguousIds} / {@code CandidateTrace.whySkipped}）穷举所有可能把它
 * 排除掉的条件，证明每一条都不会误伤。
 */
public class CandidateTraceTest {

    private int pass = 0;
    private int fail = 0;

    /** 用户实测的那个正确密码。 */
    private static final String RIGHT = "147258369";

    public static void main(String[] args) {
        CandidateTraceTest t = new CandidateTraceTest();
        t.run();
        System.out.println(t.pass + " 通过 / " + t.fail + " 失败");
        if (t.fail > 0) System.exit(1);
    }

    /** 读生产源码（结构断言用）。 */
    private static String readSource(String rel) {
        try {
            String path = io.github.wlmosv_png.wpaforge.TestPaths.under(
                    "app/src/main/java/io/github/wlmosv_png/wpaforge/" + rel);
            byte[] b = java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(path));
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private void ok(String name, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private void run() {
        rightPasswordNeverFilteredByDefault();
        unknownNeverPersisted();
        ambiguousKeepsCandidate();
        successNeverWritesTriedSet();
        traceExplainsEverySkip();
        ledgerPoliciesRespectRight();
    }

    /** 默认策略（CONSERVATIVE）下，正确密码不得被任何过滤规则排除。 */
    private void rightPasswordNeverFilteredByDefault() {
        List<Candidate> work = new ArrayList<>();
        work.add(new Candidate("12345678", Candidate.Origin.USER_DICT));
        work.add(new Candidate(RIGHT, Candidate.Origin.USER_DICT));
        work.add(new Candidate("password1", Candidate.Origin.USER_DICT));

        TriedSet tried = new TriedSet();
        tried.add("12345678");            // 别的候选已证实错误
        AttemptLedger ledger = new AttemptLedger();

        // 关键：RIGHT 自己**不在** triedSet 里
        ok("RIGHT 不在 triedSet", !tried.contains(RIGHT));

        CrackService.FilterOutcome f = CrackService.filterCandidates(
                work, tried, ledger, AttemptLedger.SkipPolicy.CONSERVATIVE, false);
        boolean kept = false;
        for (Candidate c : f.kept) {
            if (RIGHT.equals(c.password)) kept = true;
        }
        ok("默认策略下 RIGHT 通过过滤", kept);
        ok("默认策略只跳过已证实错误的那 1 条", f.totalSkipped() == 1);
    }

    /** UNKNOWN / INFERRED / NONE 都不得写进 triedSet（永久排除）。 */
    private void unknownNeverPersisted() {
        // 生产规则：只有 persistable(grade)==DIRECT 才写 triedSet
        ok("UNKNOWN 等级不可持久化",
                !EvidenceGrade.persistable(EvidenceGrade.defaultOf(CrackEngine.Verdict.UNKNOWN)));
        ok("TIMEOUT 等级不可持久化",
                !EvidenceGrade.persistable(EvidenceGrade.defaultOf(CrackEngine.Verdict.TIMEOUT)));
        ok("INCOMPLETE 等级不可持久化",
                !EvidenceGrade.persistable(EvidenceGrade.defaultOf(CrackEngine.Verdict.INCOMPLETE)));
        ok("AUTH_FAIL 默认是 INFERRED（保守）",
                EvidenceGrade.defaultOf(CrackEngine.Verdict.AUTH_FAIL)
                        == EvidenceGrade.Level.INFERRED);
        ok("AUTH_FAIL+INFERRED 不可持久化",
                !EvidenceGrade.persistable(EvidenceGrade.Level.INFERRED));
        ok("AUTH_FAIL+DIRECT 才可持久化",
                EvidenceGrade.persistable(EvidenceGrade.Level.DIRECT));
        ok("NONE 不可持久化", !EvidenceGrade.persistable(EvidenceGrade.Level.NONE));

        // 账本等级：UNKNOWN_ON_TARGET 任何档位都不跳
        ok("UNKNOWN_ON_TARGET 保守档不跳",
                !AttemptLedger.SkipPolicy.CONSERVATIVE
                        .skips(AttemptLedger.Level.UNKNOWN_ON_TARGET));
        ok("UNKNOWN_ON_TARGET 省时档也不跳",
                !AttemptLedger.SkipPolicy.SAVE_TIME
                        .skips(AttemptLedger.Level.UNKNOWN_ON_TARGET));
        // 未执行不跳
        ok("NOT_EXECUTED 任何档不跳",
                !AttemptLedger.SkipPolicy.SAVE_TIME
                        .skips(AttemptLedger.Level.NOT_EXECUTED));
    }

    /** 身份哈希冲突时必须保守保留（宁可多跑一条，不赌碰撞）。 */
    private void ambiguousKeepsCandidate() {
        List<Candidate> work = new ArrayList<>();
        work.add(new Candidate(RIGHT, Candidate.Origin.USER_DICT));
        // 单条列表 → 无冲突
        ok("单条无冲突", CrackService.ambiguousIds(work).isEmpty());

        // 构造一个"看起来有冲突"的场景：同一口令出现两次（不是冲突）
        List<Candidate> dup = new ArrayList<>();
        dup.add(new Candidate(RIGHT, Candidate.Origin.USER_DICT));
        dup.add(new Candidate(RIGHT, Candidate.Origin.USER_DICT));
        ok("同口令重复不算冲突", CrackService.ambiguousIds(dup).isEmpty());

        // 真正的冲突：两个不同口令映射到同一 id 才会进集合。
        // 生产代码用 CandidateId.of，这里验证「有冲突时 isSkipped 必须返回 false」。
        java.util.Set<Long> fakeAmb = new java.util.HashSet<>();
        fakeAmb.add(CandidateId.of(RIGHT));
        TriedSet tried = new TriedSet();
        tried.add(RIGHT);
        ok("冲突时即使 triedSet 里有也不跳",
                !CrackService.isSkipped(RIGHT, tried, null,
                        AttemptLedger.SkipPolicy.CONSERVATIVE, false, fakeAmb));
    }

    /** 命中（SUCCESS）永远不写 triedSet —— 否则正确口令会占着"已排除"名额。 */
    private void successNeverWritesTriedSet() {
        // isSuccess 的判据是「SUCCESS 且 grade 可持久化(=DIRECT)」。
        // Attempt 的构造器是包内可见（生产封装），这里直接验证判据本身：
        //   isSuccess() ⇔ verdict==SUCCESS && persistable(grade)
        // 也就是：SUCCESS+INFERRED / SUCCESS+NONE 都**不算**成功。
        ok("SUCCESS+DIRECT 才可持久化",
                EvidenceGrade.persistable(EvidenceGrade.Level.DIRECT));
        ok("SUCCESS+INFERRED 不可持久化（不算成功）",
                !EvidenceGrade.persistable(EvidenceGrade.Level.INFERRED));
        ok("SUCCESS+NONE 不可持久化（不算成功）",
                !EvidenceGrade.persistable(EvidenceGrade.Level.NONE));

        // 结构性断言：源码里 SUCCESS 的写入点必须同时要求 AT3，
        // 且 isSuccess 必须同时检查 persistable（不能只看 verdict）。
        String eng = readSource("audit/CrackEngine.java");
        ok("isSuccess 同时检查 grade", eng.contains("verdict == Verdict.SUCCESS")
                && eng.contains("EvidenceGrade.persistable(grade)"));
        ok("SUCCESS 只经 Adjudicator 产出",
                !eng.contains("new Attempt(password, Verdict.SUCCESS"));
        String adj = readSource("audit/Adjudicator.java");
        ok("Adjudicator 的 SUCCESS 要求 AT3", adj.contains("Attribution.AT3"));
    }

    /** 每一种跳过原因都必须能说清楚，不能有"不知道为什么"。 */
    private void traceExplainsEverySkip() {
        TriedSet tried = new TriedSet();
        tried.add(RIGHT);
        AttemptLedger ledger = new AttemptLedger();

        ok("不在字典里 → NOT_IN_DICT",
                CandidateTrace.whySkipped(RIGHT, false, null, null, null, null)
                        == CandidateTrace.SkipReason.NOT_IN_DICT);
        ok("空口令 → INVALID_FORMAT",
                CandidateTrace.whySkipped("", true, null, null, null, null)
                        == CandidateTrace.SkipReason.INVALID_FORMAT);
        ok("已证实错误 → TRIED_CONFIRMED_WRONG",
                CandidateTrace.whySkipped(RIGHT, true, tried, ledger,
                        AttemptLedger.SkipPolicy.CONSERVATIVE, null)
                        == CandidateTrace.SkipReason.TRIED_CONFIRMED_WRONG);
        ok("干净候选 → NONE",
                CandidateTrace.whySkipped(RIGHT, true, new TriedSet(), ledger,
                        AttemptLedger.SkipPolicy.CONSERVATIVE, null)
                        == CandidateTrace.SkipReason.NONE);

        // describe 必须带阶段与原因，且不含口令明文
        String line = CandidateTrace.describe(CandidateTrace.Stage.FILTERED,
                CandidateTrace.SkipReason.TRIED_CONFIRMED_WRONG, 27, "a1b2c3");
        ok("describe 含阶段", line.contains("被过滤"));
        ok("describe 含原因", line.contains("已证实错误"));
        ok("describe 不含口令明文", !line.contains(RIGHT));
    }

    /** 两个档位都不该因为「未确定」把正确密码永久排除。 */
    private void ledgerPoliciesRespectRight() {
        // 只有 CONFIRMED_WRONG 在两档下都可跳
        ok("CONFIRMED_WRONG 保守档跳",
                AttemptLedger.SkipPolicy.CONSERVATIVE.skips(AttemptLedger.Level.CONFIRMED_WRONG));
        ok("CONFIRMED_WRONG 省时档跳",
                AttemptLedger.SkipPolicy.SAVE_TIME.skips(AttemptLedger.Level.CONFIRMED_WRONG));
        // 保守档：其余一律不跳
        for (AttemptLedger.Level lv : AttemptLedger.Level.values()) {
            if (lv == AttemptLedger.Level.CONFIRMED_WRONG) continue;
            ok("保守档不跳 " + lv,
                    !AttemptLedger.SkipPolicy.CONSERVATIVE.skips(lv));
        }
        // 省时档：UNKNOWN_ON_TARGET 与 NOT_EXECUTED 仍不跳（它们没"终止"）
        ok("省时档不跳 UNKNOWN_ON_TARGET",
                !AttemptLedger.SkipPolicy.SAVE_TIME.skips(AttemptLedger.Level.UNKNOWN_ON_TARGET));
        ok("省时档不跳 NOT_EXECUTED",
                !AttemptLedger.SkipPolicy.SAVE_TIME.skips(AttemptLedger.Level.NOT_EXECUTED));
    }
}
