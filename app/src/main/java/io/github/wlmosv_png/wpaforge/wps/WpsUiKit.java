package io.github.wlmosv_png.wpaforge.wps;

/**
 * WPS 页面专属 UI 工具集。
 *
 * <h3>职责</h3>
 * 把 WPS 页反复出现的「状态胶囊 / 策略单选行 / 分区卡 / 确认框 / 帮助框」的
 * 构造收敛到一处，**沿用 WPAForge 既有视觉体系**（AmberTheme + NetDialog），
 * 不另造风格、不改其它页面。
 *
 * <h3>纯逻辑部分（可离线单测）</h3>
 * {@link #capStateOf(String)} / {@link #capVerdictOf(int)} / {@link #capStateLabel(int, boolean)}
 * —— 能力结论 ↔ UI 标签状态的映射。原来写在 MainActivity 里、与渲染混在一起，
 * 抽出来后可测，也保证 UI 不自行推断业务语义。
 */
public final class WpsUiKit {

    // ---- 能力标签状态（与 MainActivity 视觉一致） ----
    public static final int CAP_NONE = 0;       // 未选择目标
    public static final int CAP_UNKNOWN = 1;    // 未检测到 WPS 信息 / 能力未知
    public static final int CAP_OK = 2;         // 声明可接受 PIN
    public static final int CAP_NO_PIN = 3;     // 明确声明不收外部 PIN
    public static final int CAP_LOCKED = 4;     // AP 已锁定
    public static final int CAP_CONFLICT = 5;   // 两通道冲突
    public static final int CAP_INCOMPLETE = 6; // 数据不完整

    private WpsUiKit() {
    }

    // ---------------- 纯映射（可测） ----------------

    /** 能力结论 → 标签状态。 */
    public static int capStateOf(String verdict) {
        if (verdict == null || verdict.isEmpty()) return CAP_NONE;
        switch (verdict) {
            case WpsCapabilityManager.V_PIN_OK: return CAP_OK;
            case WpsCapabilityManager.V_NO_PIN: return CAP_NO_PIN;
            case WpsCapabilityManager.V_LOCKED: return CAP_LOCKED;
            case WpsCapabilityManager.V_CONFLICT: return CAP_CONFLICT;
            case WpsCapabilityManager.V_INCOMPLETE: return CAP_INCOMPLETE;
            default: return CAP_UNKNOWN;
        }
    }

    /** 标签状态 → 能力结论（往返闭合）。 */
    public static String capVerdictOf(int state) {
        switch (state) {
            case CAP_OK: return WpsCapabilityManager.V_PIN_OK;
            case CAP_NO_PIN: return WpsCapabilityManager.V_NO_PIN;
            case CAP_LOCKED: return WpsCapabilityManager.V_LOCKED;
            case CAP_CONFLICT: return WpsCapabilityManager.V_CONFLICT;
            case CAP_INCOMPLETE: return WpsCapabilityManager.V_INCOMPLETE;
            case CAP_NONE: return WpsCapabilityManager.V_UNKNOWN;
            default: return WpsCapabilityManager.V_UNKNOWN;
        }
    }

    /**
     * 标签文案。**未知 ≠ 不支持**：未知一律"能力未知"，不写成"不支持"。
     */
    public static String capStateLabel(int state, boolean zh) {
        switch (state) {
            case CAP_OK: return zh ? "声明可接受 PIN（未验证）" : "declares PIN (unverified)";
            case CAP_NO_PIN: return zh ? "明确声明不收外部 PIN" : "no external PIN";
            case CAP_LOCKED: return zh ? "AP 已锁定" : "AP locked";
            case CAP_CONFLICT: return zh ? "两通道结果冲突" : "channel conflict";
            case CAP_INCOMPLETE: return zh ? "数据不完整（能力未知）" : "incomplete (unknown)";
            case CAP_NONE: return zh ? "未选择" : "no target";
            default: return zh ? "能力未知" : "capability unknown";
        }
    }

    /**
     * 能力结论 → **完整人话**（卡片用；比标签长，含"这意味着什么"）。
     *
     * <p>措辞原则：**未知/不完整一律写成"没读到"，绝不写成"不支持"**；
     * 只有 AP 明确宣告才写"不接受"。
     */
    public static String capVerdictText(String verdict, boolean zh) {
        if (verdict == null) verdict = "";
        switch (verdict) {
            case WpsCapabilityManager.V_PIN_OK:
                return zh ? "AP 已宣告可接受 PIN（≠ 已验证）"
                          : "AP declares PIN accepted (NOT verified)";
            case WpsCapabilityManager.V_NO_PIN:
                return zh ? "AP 明确宣告不接受外部 PIN"
                          : "AP explicitly declines external PIN";
            case WpsCapabilityManager.V_LOCKED:
                return zh ? "AP 当前处于 WPS 锁定"
                          : "AP is WPS-locked";
            case WpsCapabilityManager.V_CONFLICT:
                return zh ? "两条通道结论冲突（不擅自选一个）"
                          : "two channels disagree";
            case WpsCapabilityManager.V_INCOMPLETE:
                return zh ? "未知（数据不完整，没读到 ≠ 不支持）"
                          : "unknown (incomplete data; not-read != unsupported)";
            default:
                return zh ? "未知（没读到，不等于不支持）"
                          : "unknown (not read; not a negative)";
        }
    }

    /** 能力来源 → 人话。 */
    public static String capSourceText(String source, boolean zh) {
        if (source == null) source = "";
        if (WpsCapabilityManager.SRC_ROOT.equals(source)) {
            return zh ? "root BSS 查询" : "root BSS query";
        }
        if (WpsCapabilityManager.SRC_LOCAL.equals(source)) {
            return zh ? "本地 IE 解析" : "local IE parse";
        }
        return zh ? "无可用来源" : "no source";
    }

    /** 标签是否表达"已确认可验证"—— **恒 false**：能力检测永远不等于验证通过。 */
    public static boolean capStateMeansVerified(int state) {
        return false;
    }

    // ---------------- Android 侧构造（沿用现有视觉体系） ----------------

    /** 状态胶囊的圆角半径（dp）。与既有目标卡状态标签一致。 */
    public static float chipRadiusDp() {
        return 14f;
    }

    /** 状态胶囊的内边距（dp）：左右 / 上下。 */
    public static int[] chipPaddingDp() {
        return new int[]{10, 4};
    }

    /** 策略单选行的内边距（dp）。 */
    public static int[] strategyRowPaddingDp() {
        return new int[]{14, 10};
    }

    /** 策略行之间的间距（dp）。 */
    public static int strategyRowGapDp() {
        return 6;
    }

    /**
     * 状态胶囊的背景参数（fill / edge / ripple 开关）——由调用方交给
     * {@code roundBgStroked}，本方法只给"用哪一对色"，避免 UI 各处自己挑色。
     *
     * @return {fillColorKey, edgeColorKey}，取值见 {@link #COLOR_ACCENT}/{@link #COLOR_OK}
     *         /{@link #COLOR_DANGER}/{@link #COLOR_DIM}
     */
    public static int[] capStateColors(int state) {
        switch (state) {
            case CAP_OK: return new int[]{COLOR_OK, COLOR_OK};
            case CAP_NO_PIN: return new int[]{COLOR_DANGER, COLOR_DANGER};
            case CAP_LOCKED: return new int[]{COLOR_DANGER, COLOR_DANGER};
            case CAP_CONFLICT: return new int[]{COLOR_WARN, COLOR_WARN};
            case CAP_INCOMPLETE: return new int[]{COLOR_DIM, COLOR_DIM};
            case CAP_NONE: return new int[]{COLOR_DIM, COLOR_DIM};
            default: return new int[]{COLOR_DIM, COLOR_DIM};
        }
    }

    // 颜色"键"（不是具体色值）——具体色值由 MainActivity 映射到 AmberTheme，
    // 这样本类不依赖 Android 图形类，仍可离线测试。
    public static final int COLOR_ACCENT = 0;
    public static final int COLOR_OK = 1;
    public static final int COLOR_DANGER = 2;
    public static final int COLOR_WARN = 3;
    public static final int COLOR_DIM = 4;
}
