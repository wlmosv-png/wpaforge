package io.github.wlmosv_png.wpaforge.cap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能力状态：**一个功能能不能做、为什么不能做**。
 *
 * <h3>为什么不是 boolean</h3>
 * 这个项目反复吃「一句话结论」的亏：界面写「设备异常」，用户和排查者都不知道
 * 到底是没 root、没有权限、系统没有这个子命令，还是 ROM 裁剪了。
 * 所以状态是**五档 + 一句技术原因 + 一条替代路径**，
 * 每档都有明确含义，不允许用「功能不可用」这种笼统话糊过去。
 *
 * <h3>状态语义（互斥，不允许含糊）</h3>
 * <ul>
 *   <li>{@link #STATE_AVAILABLE} —— 已实测可用；</li>
 *   <li>{@link #STATE_NEEDS_GRANT} —— 本机**支持**，但用户还没授权。
 *       <b>这不算「不支持」</b>：补齐授权就能用；</li>
 *   <li>{@link #STATE_UNSUPPORTED} —— 本机/本系统**做不到**（例如非 root uid
 *       调不了 {@code cmd wifi}）。技术原因必须写清楚；</li>
 *   <li>{@link #STATE_UNKNOWN} —— **还没探测**。不能当可用，也不能当不可用；</li>
 *   <li>{@link #STATE_DEGRADED} —— 能做，但结论强度更低。<b>降级必须显式说出来</b>，
 *       否则用户会把弱证据当强证据。</li>
 * </ul>
 *
 * <p>纯逻辑、无 Android 依赖，可离线测。
 */
public final class Capability {

    public static final String STATE_AVAILABLE = "AVAILABLE";
    public static final String STATE_NEEDS_GRANT = "NEEDS_GRANT";
    public static final String STATE_UNSUPPORTED = "UNSUPPORTED";
    public static final String STATE_UNKNOWN = "UNKNOWN";
    public static final String STATE_DEGRADED = "DEGRADED";

    /** 能力标识。字符串常量而不是 enum —— 要能出现在日志与诊断里。 */
    public static final String C_SHELL = "shell";                 // 任意命令执行（root）
    public static final String C_WIFI_READ = "wifi_read";         // 读连接状态 / 判断是否连上
    public static final String C_WIFI_CONNECT = "wifi_connect";   // 用候选口令静默关联
    public static final String C_WIFI_FORGET = "wifi_forget";     // 删除目标旧配置（净检）
    public static final String C_WPA_LOG = "wpa_log";             // wpa_supplicant 日志取证
    public static final String C_SCAN = "scan";                   // 扫描周边 AP
    public static final String C_SCAN_PRIV = "scan_priv";         // 特权扫描兜底
    public static final String C_WPS_PIN = "wps_pin";             // WPS PIN 协议层执行
    public static final String C_WPS_CAP = "wps_cap";             // WPS 能力识别（可降级）
    public static final String C_SAVED_CFG = "saved_cfg";         // 读系统已保存网络
    public static final String C_KEEPALIVE = "keepalive";         // appops / 电池白名单
    public static final String C_LAN_PROBE = "lan_probe";         // 局域网探测

    /** 全部能力标识（顺序 = 界面展示顺序）。 */
    public static final String[] ALL = {
            C_SHELL, C_WIFI_READ, C_WIFI_CONNECT, C_WIFI_FORGET, C_WPA_LOG,
            C_SCAN, C_SCAN_PRIV, C_WPS_PIN, C_WPS_CAP, C_SAVED_CFG, C_KEEPALIVE, C_LAN_PROBE
    };

    public final String id;
    public final String state;
    /** 一句人话（中文）。不为空。 */
    public final String reasonZh;
    public final String reasonEn;
    /** 替代路径：这条能力不可用时，用户还能怎么达到目的。空串 = 没有替代。 */
    public final String alternativeZh;
    public final String alternativeEn;
    /** 结论强度：true = 用它得到的结论可进证据链；false = 只能展示，不能作判定依据。 */
    public final boolean evidenceGradeFlag;

    // ── 检测元数据（2026-11-20 新增，任务书第二节要求）──────────────────
    //
    // 「不要只保存一个 Boolean」。每一项检测必须能回答：
    //   检测对象是什么、走的哪条通道、什么时候测的、原始返回是什么、
    //   为什么是这个结论、还能不能重试、哪些功能受它影响。
    // 这些字段让用户/开发者能复核，而不是只看到一句「不支持」。

    /** 检测对象（人话），例：{@code cmd wifi connect-network}。 */
    public final String subject;
    /** 使用的执行通道，例：{@code Root} / {@code Shizuku} / {@code 应用自身 UID}。 */
    public final String channel;
    /** 检测时间（毫秒时间戳，0 = 未记录）。 */
    public final long probedAtMs;
    /** 原始返回或错误原因（已截断，**不含口令**）。 */
    public final String rawEvidence;
    /** 判断依据（为什么得出这个 state），人话。 */
    public final String basis;
    /** 是否允许重试（用户点「重新检测」是否有意义）。 */
    public final boolean retryable;
    /** 受影响的业务功能标识（{@link #C_*} 的列表，可为空）。 */
    public final String[] impacts;

    private Capability(String id, String state, String reasonZh, String reasonEn,
                       String alternativeZh, String alternativeEn, boolean evidenceGradeFlag) {
        this(id, state, reasonZh, reasonEn, alternativeZh, alternativeEn, evidenceGradeFlag,
                "", "", 0L, "", "", true, new String[0]);
    }

    private Capability(String id, String state, String reasonZh, String reasonEn,
                       String alternativeZh, String alternativeEn, boolean evidenceGradeFlag,
                       String subject, String channel, long probedAtMs,
                       String rawEvidence, String basis, boolean retryable, String[] impacts) {
        this.id = id;
        this.state = state == null ? STATE_UNKNOWN : state;
        this.reasonZh = reasonZh == null ? "" : reasonZh;
        this.reasonEn = reasonEn == null ? "" : reasonEn;
        this.alternativeZh = alternativeZh == null ? "" : alternativeZh;
        this.alternativeEn = alternativeEn == null ? "" : alternativeEn;
        this.evidenceGradeFlag = evidenceGradeFlag;
        this.subject = subject == null ? "" : subject;
        this.channel = channel == null ? "" : channel;
        this.probedAtMs = probedAtMs;
        this.rawEvidence = rawEvidence == null ? "" : rawEvidence;
        this.basis = basis == null ? "" : basis;
        this.retryable = retryable;
        this.impacts = impacts == null ? new String[0] : impacts;
    }

    /**
     * 带**完整检测证据**的副本。
     *
     * <p>用法：先按业务结论构造（{@code available/unsupported/...}），
     * 再在探测现场用本方法把原始事实挂上去 —— 这样既不必给每个工厂方法
     * 加六个参数，也不会让「没填证据」的项看起来像「有证据」。
     *
     * @param subject    检测对象
     * @param channel    执行通道
     * @param probedAtMs 检测时刻
     * @param raw        原始返回（调用方负责脱敏与截断）
     * @param basis      判断依据
     * @param retryable  是否允许重试
     * @param impacts    受影响功能
     */
    public Capability withEvidence(String subject, String channel, long probedAtMs,
                                   String raw, String basis, boolean retryable,
                                   String... impacts) {
        return new Capability(id, state, reasonZh, reasonEn, alternativeZh, alternativeEn,
                evidenceGradeFlag, subject, channel, probedAtMs, raw, basis, retryable, impacts);
    }

    /**
     * 一行诊断文本（**不含任何口令**，可安全写日志 / 导出）。
     *
     * <p>格式刻意保持稳定，便于用户复制给开发者：
     * <pre>
     *   [state] id · 对象 · 通道 · 依据 · 原始=... · 可重试=是 · 影响=a,b
     * </pre>
     */
    public String diagnosticLine(boolean zh) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(state).append("] ").append(id);
        if (!subject.isEmpty()) sb.append(" · ").append(subject);
        if (!channel.isEmpty()) sb.append(" · 通道=").append(channel);
        String reason = zh ? reasonZh : reasonEn;
        if (!reason.isEmpty()) sb.append(" · ").append(reason);
        if (!basis.isEmpty()) sb.append(" · 依据=").append(basis);
        if (!rawEvidence.isEmpty()) sb.append(" · 原始=").append(rawEvidence);
        sb.append(" · 可重试=").append(retryable ? (zh ? "是" : "yes") : (zh ? "否" : "no"));
        if (probedAtMs > 0) sb.append(" · 检测时刻=").append(probedAtMs);
        if (impacts.length > 0) sb.append(" · 影响=").append(joinImpacts());
        return sb.toString();
    }

    private String joinImpacts() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < impacts.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(impacts[i]);
        }
        return sb.toString();
    }

    /** 是否**确实**带了检测证据（不是「默认空值」）。 */
    public boolean hasEvidence() {
        return probedAtMs > 0 && (!subject.isEmpty() || !basis.isEmpty());
    }

    // ---------------------------------------------------------------- 构造

    public static Capability available(String id, String zh, String en) {
        return new Capability(id, STATE_AVAILABLE, zh, en, "", "", true);
    }

    /** 可用，但结论强度低（降级）。 */
    public static Capability degraded(String id, String zh, String en,
                                      String altZh, String altEn) {
        return new Capability(id, STATE_DEGRADED, zh, en, altZh, altEn, false);
    }

    /** 降级，但没有替代路径（明确写出「暂无替代」，不留空让人猜）。 */
    public static Capability degraded(String id, String zh, String en) {
        return degraded(id, zh, en, NO_ALT_ZH, NO_ALT_EN);
    }

    public static Capability needsGrant(String id, String zh, String en,
                                        String altZh, String altEn) {
        return new Capability(id, STATE_NEEDS_GRANT, zh, en, altZh, altEn, false);
    }

    public static Capability needsGrant(String id, String zh, String en) {
        return needsGrant(id, zh, en, NO_ALT_ZH, NO_ALT_EN);
    }

    public static Capability unsupported(String id, String zh, String en,
                                         String altZh, String altEn) {
        return new Capability(id, STATE_UNSUPPORTED, zh, en, altZh, altEn, false);
    }

    public static Capability unsupported(String id, String zh, String en) {
        return unsupported(id, zh, en, NO_ALT_ZH, NO_ALT_EN);
    }

    /** 「暂无替代」的统一措辞 —— 比留空诚实，比含糊的「暂不可用」有用。 */
    public static final String NO_ALT_ZH = "暂无替代";
    public static final String NO_ALT_EN = "no alternative";

    public static Capability unknown(String id, String zh, String en) {
        return new Capability(id, STATE_UNKNOWN, zh, en, "", "", false);
    }

    // ---------------------------------------------------------------- 判读

    /** 能用来做事（可用或降级可用）。 */
    public boolean usable() {
        return STATE_AVAILABLE.equals(state) || STATE_DEGRADED.equals(state);
    }

    /** 能用来产出**证据级**结论。降级与未授权一律 false —— 这是证据契约。 */
    public boolean evidenceGrade() {
        return usable() && evidenceGradeFlag;
    }

    /** 本机能否做到（区分「没授权」与「根本做不到」）。 */
    public boolean supportedOnThisDevice() {
        return !STATE_UNSUPPORTED.equals(state);
    }

    public String stateText(boolean zh) {
        switch (state) {
            case STATE_AVAILABLE:   return zh ? "可用" : "available";
            case STATE_NEEDS_GRANT: return zh ? "需要授权" : "needs grant";
            case STATE_UNSUPPORTED: return zh ? "本机不支持" : "unsupported on this device";
            case STATE_DEGRADED:    return zh ? "降级可用" : "degraded";
            default:                return zh ? "未探测" : "unknown";
        }
    }

    public String line(boolean zh) {
        StringBuilder sb = new StringBuilder();
        sb.append(name(id, zh)).append("：").append(stateText(zh));
        String reason = zh ? reasonZh : reasonEn;
        if (!reason.isEmpty()) sb.append(" —— ").append(reason);
        String alt = zh ? alternativeZh : alternativeEn;
        if (!alt.isEmpty()) sb.append("；").append(zh ? "替代：" : "instead: ").append(alt);
        return sb.toString();
    }

    /** 能力的中文/英文名（界面与诊断共用，避免各处各写一套文案）。 */
    public static String name(String id, boolean zh) {
        switch (id == null ? "" : id) {
            case C_SHELL:        return zh ? "命令通道（root）" : "command channel (root)";
            case C_WIFI_READ:    return zh ? "Wi-Fi 状态读取" : "Wi-Fi state read";
            case C_WIFI_CONNECT: return zh ? "静默关联（逐条尝试）" : "silent association";
            case C_WIFI_FORGET:  return zh ? "清除旧配置（净检）" : "clear stale config";
            case C_WPA_LOG:      return zh ? "supplicant 日志取证" : "supplicant log evidence";
            case C_SCAN:         return zh ? "周边扫描" : "nearby scan";
            case C_SCAN_PRIV:    return zh ? "特权扫描兜底" : "privileged scan fallback";
            case C_WPS_PIN:      return zh ? "WPS PIN 执行" : "WPS PIN execution";
            case C_WPS_CAP:      return zh ? "WPS 能力识别" : "WPS capability detection";
            case C_SAVED_CFG:    return zh ? "系统已保存网络" : "saved networks";
            case C_KEEPALIVE:    return zh ? "后台保活加固" : "background keep-alive";
            case C_LAN_PROBE:    return zh ? "局域网探测" : "LAN probe";
            default:             return id == null ? "" : id;
        }
    }

    public String toString() {
        return id + "=" + state;
    }

    // ---------------------------------------------------------------- 集合

    /** 一份能力快照。构造后不可变 —— 界面读到的永远是同一份。 */
    public static final class Set0 {

        private final Map<String, Capability> map;
        private final String modeId;
        private final long atMs;

        public Set0(String modeId, long atMs, List<Capability> items) {
            this.modeId = modeId == null ? "" : modeId;
            this.atMs = atMs;
            Map<String, Capability> m = new LinkedHashMap<>();
            if (items != null) {
                for (Capability c : items) {
                    if (c != null && c.id != null) m.put(c.id, c);
                }
            }
            this.map = Collections.unmodifiableMap(m);
        }

        public String modeId() {
            return modeId;
        }

        public long atMs() {
            return atMs;
        }

        /**
         * 取某项。
         *
         * <p><b>没探测过返回 UNKNOWN 对象，而不是 null</b> —— 调用方永远有事可做，
         * 也不会因为少写一个 null 检查就崩。
         */
        public Capability get(String id) {
            Capability c = map.get(id);
            return c != null ? c : unknown(id, "尚未探测", "not probed yet");
        }

        public boolean has(String id) {
            return map.containsKey(id);
        }

        public List<Capability> all() {
            List<Capability> out = new ArrayList<>();
            for (String id : ALL) out.add(get(id));
            for (Map.Entry<String, Capability> e : map.entrySet()) {
                boolean known = false;
                for (String id : ALL) if (id.equals(e.getKey())) { known = true; break; }
                if (!known) out.add(e.getValue());
            }
            return out;
        }

        /** 证据级可用的能力 id 列表（诊断与门禁用）。 */
        public List<String> evidenceGradeIds() {
            List<String> out = new ArrayList<>();
            for (String id : ALL) {
                Capability c = map.get(id);
                if (c != null && c.evidenceGrade()) out.add(id);
            }
            return out;
        }

        /** 一句话摘要：说清「本机不支持哪些」，而不是笼统的「异常」。 */
        public String summary(boolean zh) {
            List<String> blocked = new ArrayList<>();
            for (String id : ALL) {
                Capability c = map.get(id);
                if (c != null && STATE_UNSUPPORTED.equals(c.state)) blocked.add(id);
            }
            if (blocked.isEmpty()) return zh ? "全部能力已探测" : "all capabilities probed";
            StringBuilder sb = new StringBuilder(zh ? "本机不支持：" : "not supported here: ");
            for (int i = 0; i < blocked.size(); i++) {
                if (i > 0) sb.append(zh ? "、" : ", ");
                sb.append(name(blocked.get(i), zh));
            }
            return sb.toString();
        }
    }
}
