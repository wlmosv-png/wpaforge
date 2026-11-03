package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

/**
 * 破解页「结算卡」文案的**纯状态映射**（无 Android 依赖，可穷举测试）。
 *
 * <h3>为什么单独抽出来</h3>
 * 需求要求「根据真实 Plan / Task / Queue 状态决定展示内容」，并且**不能**
 * 用「页面上有没有多字典计划」来猜。把「状态 → 展示语义」收成一个纯函数，
 * 界面只负责把结果填进控件，测试可以直接对真实状态穷举断言。
 *
 * <h3>语义表（调用时机：本本任务已结束、计划已 apply）</h3>
 * <pre>
 *   计划 RUNNING             → 准备下一本（**不说"正在执行"**）
 *   计划 PAUSED              → 已暂停（可继续，进度保留）
 *   计划 BLOCKED             → 被阻塞（环境不可用）—— 不伪装完成
 *   计划 ERROR               → 出错 —— 不伪装完成
 *   计划 DONE_HIT            → 已完成·已命中
 *   计划 DONE_MISS / STOPPED → 已完成（未命中 / 已停止）
 *   无计划（单字典）          → SINGLE（沿用原有单字典文案，体验不退化）
 * </pre>
 */
public final class PlanResultHint {

    /** 结算卡要展示的语义类别。 */
    public enum Kind {
        /** 没有生效中的计划 → 单字典完成文案（原有体验）。 */
        SINGLE,
        /** 计划已前进到下一本，正在准备接管。 */
        PREPARING_NEXT,
        /** 计划已暂停。 */
        PAUSED,
        /** 计划被环境阻塞。 */
        BLOCKED,
        /** 计划执行出错。 */
        ERROR,
        /** 计划全部跑完且命中。 */
        DONE_HIT,
        /** 计划全部跑完未命中。 */
        DONE_MISS,
        /** 用户主动停止计划。 */
        STOPPED
    }

    private PlanResultHint() {}

    /**
     * 真实状态 → 语义类别。**纯函数**。
     *
     * @param plan 服务端活计划；{@code null} 或空表示非计划模式（单字典）
     */
    public static Kind kindOf(DictPlan plan) {
        if (plan == null || plan.items.isEmpty()) return Kind.SINGLE;
        switch (plan.state) {
            case DONE_HIT:  return Kind.DONE_HIT;
            case DONE_MISS: return Kind.DONE_MISS;
            case STOPPED:   return Kind.STOPPED;
            case PAUSED:    return Kind.PAUSED;
            case BLOCKED:   return Kind.BLOCKED;
            case ERROR:     return Kind.ERROR;
            case RUNNING:
            case IDLE:
            default:        return Kind.PREPARING_NEXT;
        }
    }

    /** 是否属于「计划已完成」的终态族（用于决定按钮与是否给最终统计）。 */
    public static boolean isTerminalKind(Kind k) {
        return k == Kind.DONE_HIT || k == Kind.DONE_MISS || k == Kind.STOPPED;
    }

    /** 是否属于「不伪装成完成」的异常族。 */
    public static boolean isAbnormalKind(Kind k) {
        return k == Kind.BLOCKED || k == Kind.ERROR;
    }

    /** 标题。 */
    public static String title(Kind k) {
        switch (k) {
            case DONE_HIT:  return L.s("多字典计划已完成 · 已命中", "Plan finished · key found");
            case DONE_MISS: return L.s("多字典计划已完成", "Multi-dictionary plan finished");
            case STOPPED:   return L.s("计划已停止", "Plan stopped");
            case PAUSED:    return L.s("计划已暂停", "Plan paused");
            case BLOCKED:   return L.s("计划被阻塞（环境不可用）",
                    "Plan blocked (environment unavailable)");
            case ERROR:     return L.s("计划执行出错", "Plan error");
            case PREPARING_NEXT: return L.s("当前字典已完成，正在准备下一本字典",
                    "Dictionary finished — preparing the next one");
            case SINGLE:
            default:        return L.s("这本字典里没有", "Not in this dictionary");
        }
    }

    /** 副标题。 */
    public static String sub(Kind k, int idx, int total, String justName) {
        if (k == Kind.SINGLE) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(L.s("计划进度 " + idx + "/" + total, "plan " + idx + "/" + total));
        if (justName != null && !justName.isEmpty()) {
            sb.append(L.s(" · 刚完成「" + justName + "」", " · finished \"" + justName + "\""));
        }
        return sb.toString();
    }

    /**
     * 正文说明。
     *
     * @param nextName 计划里下一本的名字（PREPARING_NEXT 用；没有则为空）
     * @param sc       最后一本「已证实错误」跳过数（终态统计用）
     * @param su       最后一本「没跑出结果」跳过数
     */
    public static String why(Kind k, int idx, int total, String nextName, int sc, int su) {
        switch (k) {
            case PAUSED:
                return L.s("进度已经存下来了，点「继续」接着跑当前这本，不会从头再来。",
                        "Progress kept — Resume continues this dictionary from here.");
            case BLOCKED:
            case ERROR:
                return L.s("环境/通道暂时不可用，计划停在当前这本（进度保留）；处理后再继续。",
                        "Environment/channel unavailable; the plan stays here (progress kept).");
            case DONE_HIT:
            case DONE_MISS:
            case STOPPED: {
                String base = L.s("计划内 " + total + " 本已按顺序跑完"
                                + (k == Kind.DONE_HIT ? "，并已命中。"
                                   : k == Kind.STOPPED ? "（用户停止）。" : "，未命中。"),
                        "All " + total + " dictionaries ran in order"
                                + (k == Kind.DONE_HIT ? "; key found."
                                   : k == Kind.STOPPED ? " (user stopped)." : "; no match."));
                if (sc + su > 0) {
                    base += "\n" + L.s("最后一本跳过 " + (sc + su) + " 条（已证实错误 "
                                    + sc + " · 没跑出结果 " + su + "）",
                            "Last dictionary skipped " + (sc + su) + " (" + sc + " proven · "
                                    + su + " no result)");
                }
                return base;
            }
            case PREPARING_NEXT:
                return L.s("下一本「" + (nextName == null || nextName.isEmpty() ? "-" : nextName)
                                + "」即将开始（第 " + Math.min(idx + 1, total) + "/" + total + " 本）。",
                        "Next: \"" + (nextName == null || nextName.isEmpty() ? "-" : nextName)
                                + "\" (#" + Math.min(idx + 1, total) + " of " + total + ").");
            case SINGLE:
            default:
                return "";
        }
    }
}
