package io.github.wlmosv_png.wpaforge.wps;

import java.util.List;

/**
 * 批次记账：把「请求了哪些候选」与「helper 实际处理了哪些」对齐。
 *
 * <h3>要挡住的真实事故（第二阶段审计 P0-3）</h3>
 * 修复前主循环里是：
 * <pre>
 *   runWpsBatch(pins);
 *   halfCursor += pins.size();     // ← 按**请求数**推进
 *   saveHalfState();
 * </pre>
 * 而 helper 会因为「预算耗尽」「暂停（App 销毁进程）」「进程异常」提前结束，
 * 只返回它**真正跑过**的那几条。于是：
 * <ul>
 *   <li>暂停发生在批次中间 → 没跑到的候选被记成「已跑过」，永久跳过；</li>
 *   <li>预算耗尽同理 —— 拆半「11000 次必中」的保证被静默破坏；</li>
 *   <li>而且这批跳过的位置会被写进断点，重启也补不回来。</li>
 * </ul>
 *
 * <h3>规则</h3>
 * <ol>
 *   <li>只有 helper **真的报了结果**（含 {@code SKIPPED_INVALID}）的候选才算「已处理」；</li>
 *   <li>空候选不需要执行，直接记为已处理（否则会卡在同一个位置）；</li>
 *   <li>结果与请求的顺序必须对得上；对不上就**停在出错位置**，绝不越过未知区间
 *       —— 宁可少推进（下次重跑几条），也不能跳过没跑过的候选；</li>
 *   <li>确证命中（{@code SUCCESS}）导致的中断不算「意外中断」：剩下的候选本来也不需要跑了。</li>
 * </ol>
 *
 * <p>纯逻辑：不碰 Android、不碰文件，可离线测试。
 */
public final class WpsBatchPlan {

    private WpsBatchPlan() {
    }

    /** 记账结果。 */
    public static final class Out {
        /** 游标应推进的条数（= 空候选 + 真正处理过的候选）。 */
        public int advance;
        /** 本批里没跑到的条数 —— 必须留到下次，不得记为已完成。 */
        public int unprocessed;
        /** 是否意外中断（暂停 / 预算耗尽 / 进程异常），此时 unprocessed > 0。 */
        public boolean cancelled;
        /** 是否因**确证命中**而停止。 */
        public boolean hitStop;
        /** 空候选条数（无需执行）。 */
        public int blank;
        /** 结果与请求对不上的条数（记账异常，> 0 时 advance 只到出错位置）。 */
        public int mismatched;
        /** 人话说明（写日志用，可为空）。 */
        public String note = "";

        /** 下一批应从「当前游标 + advance」继续。 */
        public int nextCursor(int cursor) {
            return cursor + advance;
        }
    }

    /**
     * 记账。
     *
     * @param requested 本批请求的候选（顺序即执行顺序）
     * @param results   helper 返回的结果（顺序即执行顺序）；null 视为空
     */
    public static Out account(List<String> requested, List<WpsController.Result> results) {
        Out out = new Out();
        if (requested == null || requested.isEmpty()) {
            out.note = "空批次";
            return out;
        }

        int matched = 0;
        for (int i = 0; i < requested.size(); i++) {
            String want = requested.get(i);
            if (want == null || want.trim().isEmpty()) {
                // 空候选不需要执行，直接算处理过（否则游标会卡在这一格）
                out.blank++;
                matched++;
                continue;
            }
            if (results == null || matched >= results.size()) break;   // 没跑到这里
            WpsController.Result r = results.get(matched);
            String got = r == null ? null : r.pin;
            if (got == null || !got.trim().equals(want.trim())) {
                // 对不上：停在出错位置，不越过未知区间
                out.mismatched++;
                out.note = "第 " + (matched + 1) + " 条结果与请求不一致（请求 " + want
                        + "，结果 " + got + "）—— 已停止推进";
                break;
            }
            matched++;
            if (r.isSuccess()) {
                out.hitStop = true;
                break;
            }
        }

        out.advance = matched;
        out.unprocessed = requested.size() - matched;
        out.cancelled = out.unprocessed > 0 && !out.hitStop;
        if (out.note.isEmpty() && out.cancelled) {
            out.note = "本批未跑完（剩余 " + out.unprocessed + " 条留到下次）";
        }
        return out;
    }
}
