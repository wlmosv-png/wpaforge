package io.github.wlmosv_png.wpaforge.ui;

/**
 * 破解页日志的分级（纯逻辑，桌面 JVM 可测）。
 *
 * <h3>要解决的问题</h3>
 * 改版前「攻击流」里一大半是**启动自检**（保活、目标派生、智能排序、引擎模式、
 * 悬浮窗、凭据缓存……），真正的运行信息被淹在里面。用户打开页面看到十几行
 * 安装日志，会以为「它到底在干活没有」。
 *
 * <h3>分级</h3>
 * <ul>
 *   <li>{@link Kind#MILESTONE} —— 任务级节点：开始 / 命中 / 暂停 / 停止 / 结束 / 续跑。
 *       这些是「出事了要看的」。</li>
 *   <li>{@link Kind#REASON} —— 为什么没成：早期失败 / 快速失败 / 超时 / 提示通道异常。</li>
 *   <li>{@link Kind#DETAIL} —— 其余全部：启动自检、逐条候选结果、耗时汇总、监听器诊断。</li>
 * </ul>
 *
 * <p>页面上只给两个视图：<b>关键</b>（MILESTONE + REASON）与<b>全部</b>。
 * 规则按「先匹配先赢」排，逐条候选结果（`✗ 口令 (…ms …)`）**必须**排在 REASON 的关键词之前，
 * 否则它会被「回连」「超时」这类词误判成关键，一卷就是几千行。
 */
public final class LogFilter {

    public enum Kind { MILESTONE, REASON, DETAIL }

    private LogFilter() {}

    /** 只匹配行首的这些前缀 ———— 逐条候选结果与命中结果 */
    private static final String CAND_FAIL = "✗  ";
    private static final String CAND_HIT = "✓ 成功";

    public static Kind classify(String line) {
        if (line == null) return Kind.DETAIL;
        String t = line.trim();
        if (t.isEmpty()) return Kind.DETAIL;

        // ① 逐条候选结果：先判掉，否则后面「回连/超时」会把它们判成关键
        if (t.startsWith(CAND_FAIL)) return Kind.DETAIL;
        if (t.startsWith(CAND_HIT)) return Kind.MILESTONE;

        // ② 任务级节点
        if (has(t, "===== 开始任务") || has(t, "开始任务:")) return Kind.MILESTONE;
        if (has(t, "密钥命中") || has(t, "★ HIT") || has(t, "⚡")) return Kind.MILESTONE;
        if (has(t, "已暂停，进度") || has(t, "已停止，进度")
                || has(t, "已中止（现场") || has(t, "任务完成，未命中")) return Kind.MILESTONE;
        if (has(t, "队列已结束") || has(t, "条继续") || has(t, "处于暂停，跳过")) return Kind.MILESTONE;

        // ③ 失败/异常原因
        if (has(t, "早期失败") || has(t, "快速失败") || has(t, "命令超时")) return Kind.REASON;
        if (has(t, "无法确认") || has(t, "不可信") || has(t, "没有结论")) return Kind.REASON;
        if (has(t, "旧配置未清除") || has(t, "通道可能没真正生效")) return Kind.REASON;
        if (has(t, "已退回逐条 su") || has(t, "常驻 shell 会话不可用")) return Kind.REASON;

        return Kind.DETAIL;
    }

    /** 「关键」视图是否显示这一类 */
    public static boolean isKey(Kind k) {
        return k == Kind.MILESTONE || k == Kind.REASON;
    }

    /** 某个视图下是否显示 */
    public static boolean visible(Kind k, boolean keyOnly) {
        return keyOnly ? isKey(k) : true;
    }

    private static boolean has(String line, String needle) {
        return line.contains(needle);
    }
}
