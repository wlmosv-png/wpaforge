package io.github.wlmosv_png.wpaforge.wps;

/**
 * 通道与能力诊断：把「一次观测到的通道/能力原始事实」映射成明确分类。
 *
 * <h3>为什么需要它</h3>
 * 任务书要求把以下两个概念彻底拆开，禁止合并成一个 verdict：
 * <ul>
 *   <li>本机通道是否可用（root / socket / SELinux / supplicant / 命令 / 数据）</li>
 *   <li>目标 AP 能力如何（支持 WPS / 支持 PIN 方法 / 允许外部 registrar / 是否实测可验证）</li>
 * </ul>
 * 修复前：`SELINUX=ok` 无条件打印、通道失效表现为「一屏 UNKNOWN / LINK_LOST」、
 * `verdict` 把「AP 支持 PIN」和「AP 现在愿意收」混成一个值。
 *
 * <h3>分类（每项独立判定、互不推导）</h3>
 * <pre>
 *   1. SOURCE_*    —— 本机通道状态（失败时给出原因，不再吞成 UNKNOWN）
 *   2. AP_*        —— 目标 AP 能力（广播帧事实，可能过期）
 *   3. 其它：VOID（无观测）/ OK（通道就绪）/ UNKNOWN（信息不足）
 * </pre>
 *
 * <p>纯逻辑：不碰 Android、不碰文件，可离线测试。
 */
public final class WpsChannelDiag {

    private WpsChannelDiag() {
    }

    // ---------------- 通道（本机侧） ----------------

    /** root 不可用。 */
    public static final String ROOT_UNAVAILABLE = "CH_ROOT_UNAVAILABLE";
    /** supplicant 控制 socket 不存在 / 打不开。 */
    public static final String SOCKET_MISSING = "CH_SOCKET_MISSING";
    /** SELinux 策略未生效（回包被拦）。 */
    public static final String SELINUX_BLOCKED = "CH_SELINUX_BLOCKED";
    /** supplicant 无响应（连不上 / 超时无回包）。 */
    public static final String SUPPLICANT_SILENT = "CH_SUPPLICANT_SILENT";
    /** 命令被 supplicant 拒绝（回包以 FAIL 开头）。 */
    public static final String COMMAND_REFUSED = "CH_COMMAND_REFUSED";
    /** 通道返回了数据但格式无法解析（字段缺失 / 结构不对）。 */
    public static final String DATA_INVALID = "CH_DATA_INVALID";
    /** 通道正常（能读到 supplicant 的回包）。 */
    public static final String CHANNEL_OK = "CHANNEL_OK";
    /** 没有任何观测（尚未自检）。 */
    public static final String NOT_SELF_TESTED = "CH_NOT_TESTED";

    // ---------------- AP 能力（目标侧） ----------------

    /** AP 支持 WPS（BSS 里有 WSC IE）。 */
    public static final String AP_WPS_SUPPORTED = "AP_WPS_SUPPORTED";
    /** AP 未发现 WPS 支持。 */
    public static final String AP_WPS_NONE = "AP_WPS_NONE";
    /** AP 声明支持 PIN 输入方法（config_methods 含 KEYPAD/LABEL）。 */
    public static final String AP_PIN_METHOD = "AP_PIN_METHOD";
    /** AP 未声明 PIN 输入方法。 */
    public static final String AP_PIN_METHOD_NONE = "AP_PIN_METHOD_NONE";
    /** 存在外部 Registrar 可能（Selected Registrar / Device Password ID 可知）。 */
    public static final String AP_EXT_REG_POSSIBLE = "AP_EXT_REG_POSSIBLE";
    /** 外部 Registrar 可能未被确认（属性缺失 = 信息不足，不是"不支持"）。 */
    public static final String AP_EXT_REG_UNKNOWN = "AP_EXT_REG_UNKNOWN";
    /** AP 报 AP Setup Locked。 */
    public static final String AP_LOCKED = "AP_LOCKED";
    /** 尚未对 AP 做能力探测。 */
    public static final String AP_CAP_NOT_PROBED = "AP_CAP_NOT_PROBED";

    // ---------------- 其它 ----------------

    /** 空观测。 */
    public static final String VOID = "VOID";
    /** 信息不足（不允许显示成"不支持"）。 */
    public static final String UNKNOWN = "UNKNOWN";

    // ================================================================ 通道判定

    /** 把 helper 的一次 raw 通道输出解析成一个通道诊断。 */
    public static final class ChannelObs {
        /** root 是否可用（能 su）。 */
        public boolean rootOk;
        /** socket 是否存在 / 可连（目录与文件探测）。 */
        public boolean socketExists;
        /** 是否能读到 supplicant 回包（非空 STATUS 或 BSS）。 */
        public boolean replyReceived;
        /** 回包是否以 FAIL 开头。 */
        public boolean commandRejected;
        /** 回包字段是否可解析（至少出现一个 key=value）。 */
        public boolean dataParseable;
        /** SELinux 是否判定为放行（由调用方传入注入/探测结果）。 */
        public boolean selinuxOk;

        public ChannelObs() {
        }
    }

    /**
     * 判定通道状态。
     *
     * <p>优先级：root → socket → selinux → supplicant 回包 → 命令被拒 → 数据格式。
     * 任一环节失败即返回该环节的错误；全部通过 → CHANNEL_OK。
     */
    public static String judgeChannel(ChannelObs o) {
        if (o == null) return VOID;
        if (!o.rootOk) return ROOT_UNAVAILABLE;
        if (!o.socketExists) return SOCKET_MISSING;
        if (!o.selinuxOk) return SELINUX_BLOCKED;
        if (!o.replyReceived) return SUPPLICANT_SILENT;
        if (o.commandRejected) return COMMAND_REFUSED;
        if (!o.dataParseable) return DATA_INVALID;
        return CHANNEL_OK;
    }

    /** 通道状态 → 中文文案。 */
    public static String channelLabel(String diag, boolean zh) {
        switch (diag == null ? UNKNOWN : diag) {
            case ROOT_UNAVAILABLE:
                return zh ? "通道异常：Root 不可用" : "channel error: root unavailable";
            case SOCKET_MISSING:
                return zh ? "通道异常：supplicant 控制口不存在" : "channel error: control socket missing";
            case SELINUX_BLOCKED:
                return zh ? "通道异常：SELinux 策略未生效（回包被拦）"
                        : "channel error: SELinux policy not applied";
            case SUPPLICANT_SILENT:
                return zh ? "通道异常：supplicant 无响应" : "channel error: supplicant silent";
            case COMMAND_REFUSED:
                return zh ? "通道异常：命令被 supplicant 拒绝" : "channel error: command refused";
            case DATA_INVALID:
                return zh ? "通道异常：返回数据格式无法解析" : "channel error: unparseable data";
            case CHANNEL_OK:
                return zh ? "通道正常" : "channel OK";
            case NOT_SELF_TESTED:
                return zh ? "通道尚未自检" : "channel not self-tested";
            default:
                return zh ? "通道状态未知" : "channel unknown";
        }
    }

    // ================================================================ AP 能力判定

    /** AP 能力观测（全部来自 BSS 广播帧 / BSS 查询）。 */
    public static final class CapObs {
        /** 是否拿到该 AP 的 BSS 块（非空、非 FAIL）。 */
        public boolean bssPresent;
        /** BSS 块里是否有 WSC IE（支持 WPS）。 */
        public boolean hasWsc;
        /** config_methods 是否有 PIN 输入能力位（KEYPAD/LABEL）。 */
        public boolean pinMethod;
        /** 是否 AP Setup Locked。 */
        public boolean locked;
        /** Selected Registrar 是否 = 1（正在配对待机）；-1 = 未知/属性缺失。 */
        public int selectedRegistrar = -1;
        /** Device Password ID；-1 = 未知。 */
        public int devPwdId = -1;

        public CapObs() {
        }
    }

    /**
     * 能力主状态：只回答「有没有观察到 WPS 支持」。
     * 结果集：AP_WPS_SUPPORTED / AP_WPS_NONE / UNKNOWN（BSS 缺失）。
     */
    public static String judgeCapability(CapObs o) {
        if (o == null || !o.bssPresent) return UNKNOWN;
        return o.hasWsc ? AP_WPS_SUPPORTED : AP_WPS_NONE;
    }

    /**
     * PIN 方法状态：AP 是否声明接受外部 PIN（KEYPAD / LABEL）。
     * 仅在 WPS 支持时才有意义；否则返回"不支持"。
     * 结果集：AP_PIN_METHOD / AP_PIN_METHOD_NONE / AP_WPS_NONE。
     */
    public static String judgePinMethod(CapObs o) {
        if (o == null || !o.bssPresent) return UNKNOWN;
        if (!o.hasWsc) return AP_WPS_NONE;
        return o.pinMethod ? AP_PIN_METHOD : AP_PIN_METHOD_NONE;
    }

    /**
     * 外部 Registrar 可能：Selected Registrar == 1 → 正在配对待机（可能）。
     * 属性缺失（-1）→ UNKNOWN，**绝不显示成"不支持"**。
     */
    public static String judgeExtRegistrar(CapObs o) {
        if (o == null || !o.bssPresent) return UNKNOWN;
        if (o.selectedRegistrar == 1) return AP_EXT_REG_POSSIBLE;
        return AP_EXT_REG_UNKNOWN;
    }

    /** 是否被锁定：是则 AP_LOCKED，否则（不含锁定判断时）返回 VOID 之外。
     *  简化：仅当 locked 已知（true/false 都有观测）时返回 AP_LOCKED 或空串。 */
    public static String judgeLocked(CapObs o) {
        if (o == null || !o.bssPresent) return UNKNOWN;
        return o.locked ? AP_LOCKED : "";
    }

    /** AP 能力主状态 → 中文（禁止把"未知"显示成"不支持"）。 */
    public static String capLabel(String diag, boolean zh) {
        switch (diag == null ? UNKNOWN : diag) {
            case AP_WPS_SUPPORTED:
                return zh ? "已确认支持 WPS" : "WPS supported";
            case AP_WPS_NONE:
                return zh ? "未发现支持 WPS" : "no WPS support found";
            case AP_PIN_METHOD:
                return zh ? "已确认支持 PIN 方法" : "PIN method supported";
            case AP_PIN_METHOD_NONE:
                return zh ? "未声明 PIN 输入方法" : "no PIN method declared";
            case AP_EXT_REG_POSSIBLE:
                return zh ? "存在外部 Registrar 可能" : "external registrar possible";
            case AP_EXT_REG_UNKNOWN:
                return zh ? "外部 Registrar 信息不足（非「不支持」）"
                        : "external registrar unknown (not 'unsupported')";
            case AP_LOCKED:
                return zh ? "AP 已锁定" : "AP locked";
            case AP_CAP_NOT_PROBED:
                return zh ? "尚未验证" : "not verified";
            case UNKNOWN:
                return zh ? "信息不足" : "unknown";
            default:
                return zh ? "信息不足" : "unknown";
        }
    }
}