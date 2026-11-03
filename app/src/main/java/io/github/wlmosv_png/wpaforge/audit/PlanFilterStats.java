package io.github.wlmosv_png.wpaforge.audit;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 计划级「真实候选交集」统计。
 *
 * <h3>为什么必须单独算，不能用账本总量</h3>
 * 需求（第七节）明确：过滤数量必须通过真实候选交集计算，不能用账本总量代替。
 * 账本里可能有 5 万条记录，但当前计划里的几本字典加起来只有 3 千条候选 ——
 * 把 5 万当「本次将跳过」是编数字。
 *
 * <h3>口径</h3>
 * <pre>
 *   候选集合 K = 计划内所有字典的候选（按候选身份去重，规则字典不展开）
 *   已证实错误 = |K ∩ ({triedSet} ∪ {账本 CONFIRMED_WRONG})|
 *   未确定     = |K ∩ {账本 executed 且非 CONFIRMED_WRONG}|
 *   将跳过     = 按策略真实会跳过的条数
 *   待执行     = |K| - 将跳过
 * </pre>
 *
 * <p>纯流式读取：不把整本字典读进内存（大字典 4.8MB / 20 万条也不会 OOM），
 * 每条只做一次哈希查询。命中上限（cap）时返回 reliable=false 并附原因 ——
 * 界面显示「暂不可统计」，绝不编数字。
 */
public final class PlanFilterStats {

    /** 可靠统计的候选上限（超过就标注不可靠，而不是硬算）。 */
    public static final int DEFAULT_CAP = 2_000_000;

    public final int totalCandidates;
    public final int confirmedWrong;
    public final int undetermined;
    public final int willSkip;
    public final int willRun;
    /** 是否可可靠统计。 */
    public final boolean reliable;
    /** 不可靠的原因（reliable=true 时为空）。 */
    public final String note;
    /** 计划里没有可读字典文件时为 true。 */
    public final boolean emptyPlan;

    private PlanFilterStats(int total, int cw, int un, int skip, int run,
                            boolean reliable, String note, boolean empty) {
        this.totalCandidates = total;
        this.confirmedWrong = cw;
        this.undetermined = un;
        this.willSkip = skip;
        this.willRun = run;
        this.reliable = reliable;
        this.note = note == null ? "" : note;
        this.emptyPlan = empty;
    }

    /** 空计划 / 不可读的占位结果。 */
    public static PlanFilterStats empty(String note) {
        return new PlanFilterStats(0, 0, 0, 0, 0, false, note, true);
    }

    /**
     * 计算真实交集。
     *
     * @param dictFiles 计划内字典文件（绝对路径；不存在/不可读的会被跳过）
     * @param tried    本目标的永久已试集合（可为 null）
     * @param ledger   本目标的执行账本（可为 null）
     * @param policy   过滤档位
     * @param cap      候选上限；超过则 reliable=false
     */
    public static PlanFilterStats compute(List<File> dictFiles, TriedSet tried,
                                          AttemptLedger ledger, AttemptLedger.SkipPolicy policy,
                                          int cap) {
        if (dictFiles == null || dictFiles.isEmpty()) {
            return empty("计划里没有字典");
        }
        int limit = cap <= 0 ? DEFAULT_CAP : cap;
        AttemptLedger.SkipPolicy pol = policy == null
                ? AttemptLedger.SkipPolicy.CONSERVATIVE : policy;

        java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();
        int total = 0, cw = 0, un = 0, skip = 0;
        boolean overflow = false;

        for (File f : dictFiles) {
            if (f == null || !f.isFile()) continue;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8), 1 << 16)) {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.replace("\uFEFF", "").trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    if (t.length() < 8 || t.length() > 63) continue;   // 与 DictStore.load 同口径
                    long id = CandidateId.of(t);
                    if (!seen.add(id)) continue;         // 计划内按身份去重
                    total++;
                    if (total > limit) { overflow = true; break; }

                    boolean byTried = tried != null && tried.contains(t);
                    AttemptLedger.Level lv = ledger == null
                            ? AttemptLedger.Level.NOT_EXECUTED : ledger.level(t);
                    boolean isCw = byTried || lv == AttemptLedger.Level.CONFIRMED_WRONG;
                    boolean isUn = !isCw && lv.executed;
                    if (isCw) cw++;
                    else if (isUn) un++;
                    if (isCw || pol.skips(lv)) skip++;
                }
            } catch (Throwable ignored) {
            }
            if (overflow) break;
        }

        if (overflow) {
            return new PlanFilterStats(total, cw, un, skip, total - skip, false,
                    "候选超过 " + limit + " 条，未完整统计", false);
        }
        return new PlanFilterStats(total, cw, un, skip, total - skip, true, "", total == 0);
    }
}
