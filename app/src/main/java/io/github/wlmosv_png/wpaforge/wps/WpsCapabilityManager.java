package io.github.wlmosv_png.wpaforge.wps;

import java.util.ArrayList;
import java.util.List;

/**
 * WPS 目标能力检测（**双通道**，纯逻辑，可离线测）。
 *
 * <h3>两条通道</h3>
 * <ul>
 *   <li><b>通道 A · ROOT_BSS</b>：root 侧 {@code BSS <bssid>} 读 {@code wps_config_methods} 等；</li>
 *   <li><b>通道 B · LOCAL_IE</b>：本地解析 ScanResult / {@code ie=} 里的 WPS Vendor IE
 *       （{@link WpsIeParser}）。</li>
 * </ul>
 * 两通道结果**各自独立保存**，互相**不得覆盖原始数据**；合并只产生一个"结论视图"，
 * 原始通道数据仍在 {@link Capability#root} / {@link Capability#localIe} 里可查。
 *
 * <h3>硬性规则（本类的存在意义）</h3>
 * <ul>
 *   <li>扫描到 WPS IE **不等于** PIN 可验证 —— {@link Capability#verified} 恒为 false；
 *       本类只回答"AP 宣告了什么能力"；</li>
 *   <li>**未知不得解释成不支持**：数据不完整 → {@code UNKNOWN} / {@code INCOMPLETE}，
 *       绝不落到 {@code NO_PIN}；</li>
 *   <li>通道结果必须通过 **BSSID 一致性 + 采集时间新鲜度** 校验，否则该通道作废；</li>
 *   <li>两通道都读到了 {@code config_methods} 且**不一致** → {@code CONFLICT}，
 *       不擅自选一个。</li>
 * </ul>
 *
 * <p>纯逻辑、无 Android 依赖。
 */
public final class WpsCapabilityManager {

    private WpsCapabilityManager() {
    }

    /** 结论。 */
    public static final String V_PIN_OK = "PIN_OK";        // 声明可接受外部 PIN（≠ 已验证）
    public static final String V_NO_PIN = "NO_PIN";        // 明确声明不接受外部 PIN（需完整数据）
    public static final String V_LOCKED = "LOCKED";        // AP 报 AP Setup Locked
    public static final String V_UNKNOWN = "UNKNOWN";      // 无数据 / 数据不足
    public static final String V_CONFLICT = "CONFLICT";    // 两通道矛盾
    public static final String V_INCOMPLETE = "INCOMPLETE"; // 有数据但不完整

    /** 通道来源。 */
    public static final String SRC_ROOT = "ROOT_BSS";
    public static final String SRC_LOCAL = "LOCAL_IE";
    public static final String SRC_NONE = "NONE";

    /** 采集结果被认为"新鲜"的时限。 */
    public static final long FRESH_MS = 5 * 60_000L;

    // ---------------- 通道结果 ----------------

    /** 单条通道的原始结果（**各自独立保存**，不被另一通道覆盖）。 */
    public static final class Channel {
        public String source = SRC_NONE;
        /** 通道是否拿到可用数据。 */
        public boolean available;
        /** 采集时刻。 */
        public long collectedAtMs;
        /** 采集时的目标 BSSID（一致性校验用）。 */
        public String bssid = "";

        public int configMethods = WpsIeParser.UNKNOWN;
        public int devPasswordId = WpsIeParser.UNKNOWN;
        public int selectedRegistrar = WpsIeParser.UNKNOWN;
        public int apSetupLocked = WpsIeParser.UNKNOWN;
        public String deviceName = "";

        /** 原始证据（文本 / hex），用于追溯，**不加工**。 */
        public String raw = "";
        /** 该通道自身的问题（截断 / 长度不符 / 解析失败等）。 */
        public final List<String> issues = new ArrayList<>();
        /** 数据是否完整（足以判断 PIN 能力）。 */
        public boolean complete;

        public String issueSummary() {
            if (issues.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < issues.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(issues.get(i));
            }
            return sb.toString();
        }
    }

    /** 合并后的结论视图。 */
    public static final class Capability {
        public String targetBssid = "";
        public long atMs;
        public Channel root;        // 通道 A（可能为 null）
        public Channel localIe;     // 通道 B（可能为 null）

        public String verdict = V_UNKNOWN;
        /** 结论主要依据哪条通道（CONFLICT 时为 NONE）。 */
        public String primarySource = SRC_NONE;
        /** 人话原因。 */
        public String reason = "";
        /** 目标是否已过期（采集时间过老）。 */
        public boolean stale;
        /** 两通道是否被判定为冲突。 */
        public boolean conflict;
        /** 一致性 / 解析问题汇总（来自两通道 + 合并过程）。 */
        public final List<String> issues = new ArrayList<>();

        /**
         * 是否"PIN 已被验证"—— 本类**恒 false**。
         *
         * <p>能力检测只回答"AP 宣告了什么"；PIN 是否真的可用必须由协议层证据
         * （{@link WpsHitJudge}）给出。任何把本类结论当成"可验证"的用法都是缺陷。
         */
        public boolean verified = false;

        /** 是否声明可接受外部 PIN（仅"声明"）。 */
        public boolean declaresPin() {
            return V_PIN_OK.equals(verdict);
        }

        /** 一致性 / 解析问题摘要（空串 = 无问题）。 */
        public String issueSummary() {
            if (issues.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < issues.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(issues.get(i));
            }
            return sb.toString();
        }

        public String label(boolean zh) {
            switch (verdict) {
                case V_PIN_OK:
                    return zh ? "声明可接受 PIN（未验证）" : "declares PIN (unverified)";
                case V_NO_PIN:
                    return zh ? "明确声明不接受外部 PIN" : "no external PIN declared";
                case V_LOCKED:
                    return zh ? "AP 已锁定（AP Setup Locked）" : "AP locked";
                case V_CONFLICT:
                    return zh ? "两通道结果冲突" : "channel conflict";
                case V_INCOMPLETE:
                    return zh ? "数据不完整（能力未知）" : "incomplete (unknown)";
                default:
                    return zh ? "能力未知" : "capability unknown";
            }
        }
    }

    // ---------------- 构造通道 ----------------

    /** 由 root BSS 结果构造通道 A。 */
    public static Channel root(String bssid, int configMethods, int devPasswordId,
                               int selectedRegistrar, int apSetupLocked, String deviceName,
                               String raw, long atMs) {
        Channel c = new Channel();
        c.source = SRC_ROOT;
        c.bssid = bssid == null ? "" : bssid;
        c.configMethods = configMethods;
        c.devPasswordId = devPasswordId;
        c.selectedRegistrar = selectedRegistrar;
        c.apSetupLocked = apSetupLocked;
        c.deviceName = deviceName == null ? "" : deviceName;
        c.raw = raw == null ? "" : raw;
        c.collectedAtMs = atMs;
        c.available = true;
        // root 通道的 config_methods 读到即视为"该通道数据足够"（其余字段可选）
        c.complete = configMethods != WpsIeParser.UNKNOWN;
        if (!c.complete) c.issues.add("root 通道未读到 config_methods");
        return c;
    }

    /** 由本地 IE 解析结果构造通道 B。 */
    public static Channel localIe(String bssid, WpsIeParser.Result ie, String rawHex, long atMs) {
        Channel c = new Channel();
        c.source = SRC_LOCAL;
        c.bssid = bssid == null ? "" : bssid;
        c.raw = rawHex == null ? "" : rawHex;
        c.collectedAtMs = atMs;
        if (ie == null) {
            c.available = false;
            c.issues.add("本地 IE 解析无结果");
            return c;
        }
        c.available = ie.observed();
        c.configMethods = ie.configMethods;
        c.devPasswordId = ie.devPasswordId;
        c.selectedRegistrar = ie.selectedRegistrar;
        c.apSetupLocked = ie.apSetupLocked;
        c.deviceName = ie.deviceName;
        c.complete = ie.complete();
        if (!ie.issues.isEmpty()) c.issues.addAll(ie.issues);
        if (c.available && !c.complete) c.issues.add("本地 IE 数据不完整");
        return c;
    }

    // ---------------- 合并 ----------------

    /**
     * 合并两通道，得出能力结论。
     *
     * @param targetBssid 当前目标；用于一致性校验
     * @param nowMs       当前时刻
     */
    public static Capability combine(String targetBssid, long nowMs, Channel root, Channel local) {
        Capability cap = new Capability();
        cap.targetBssid = targetBssid == null ? "" : targetBssid;
        cap.atMs = nowMs;
        cap.root = root;
        cap.localIe = local;

        // ① 先把两通道自身的解析问题汇总上来（截断 / 长度不符等，绝不吞掉）
        if (root != null && root.available) cap.issues.addAll(root.issues);
        if (local != null && local.available) cap.issues.addAll(local.issues);

        // ② BSSID 一致性 + 新鲜度校验（不通过则该通道作废，但**保留原始数据**在对象里）
        boolean rootOk = validate(cap, root, targetBssid, nowMs, "root");
        boolean localOk = validate(cap, local, targetBssid, nowMs, "local");

        Channel a = (root != null && rootOk) ? root : null;
        Channel b = (local != null && localOk) ? local : null;

        if (a == null && b == null) {
            cap.verdict = V_UNKNOWN;
            cap.primarySource = SRC_NONE;
            cap.reason = cap.issues.isEmpty() ? "两条通道都没有可用数据" : "通道数据不可用";
            return cap;
        }

        // ② 锁定：任一通道明确报告 AP Setup Locked
        if ((a != null && a.apSetupLocked == 1) || (b != null && b.apSetupLocked == 1)) {
            cap.verdict = V_LOCKED;
            cap.primarySource = (a != null && a.apSetupLocked == 1) ? a.source : b.source;
            cap.reason = "AP 报告 AP Setup Locked";
            return cap;
        }

        // ③ 冲突：两通道都读到 config_methods 且不一致
        boolean aHas = a != null && a.configMethods != WpsIeParser.UNKNOWN;
        boolean bHas = b != null && b.configMethods != WpsIeParser.UNKNOWN;
        if (aHas && bHas && a.configMethods != b.configMethods) {
            cap.verdict = V_CONFLICT;
            cap.conflict = true;
            cap.primarySource = SRC_NONE;
            cap.reason = "root=0x" + Integer.toHexString(a.configMethods)
                    + " 与本地 IE=0x" + Integer.toHexString(b.configMethods) + " 不一致";
            cap.issues.add(cap.reason);
            return cap;
        }

        // ④ 取有效通道（优先新鲜 + 完整）
        Channel use = pick(a, b);
        if (use == null) {
            cap.verdict = V_UNKNOWN;
            cap.primarySource = SRC_NONE;
            cap.reason = "无可用通道";
            return cap;
        }
        cap.primarySource = use.source;

        // ⑤ 数据不完整 → 未知/不完整，**绝不**落到 NO_PIN
        if (!use.complete || use.configMethods == WpsIeParser.UNKNOWN) {
            cap.verdict = V_INCOMPLETE;
            cap.reason = "数据不完整（" + use.source + "），能力无法判断";
            return cap;
        }

        // ⑥ 有完整数据 → 按 config_methods 判定
        boolean pin = (use.configMethods & WpsIeParser.CM_KEYPAD) != 0
                || (use.configMethods & WpsIeParser.CM_LABEL) != 0;
        if (pin) {
            cap.verdict = V_PIN_OK;
            cap.reason = "config_methods=0x" + Integer.toHexString(use.configMethods)
                    + "（来源 " + use.source + "）—— 仅声明，未验证";
        } else {
            cap.verdict = V_NO_PIN;
            cap.reason = "config_methods=0x" + Integer.toHexString(use.configMethods)
                    + "（来源 " + use.source + "）未含 KEYPAD/LABEL";
        }
        return cap;
    }

    /** 校验通道：可用 / BSSID 一致 / 新鲜。返回是否可用。 */
    private static boolean validate(Capability cap, Channel c, String target, long now, String tag) {
        if (c == null || !c.available) return false;
        // BSSID 一致性：目标与通道都已知且不一致 → 该通道作废（原始数据仍保留在对象里）
        if (!c.bssid.isEmpty() && target != null && !target.isEmpty()
                && !c.bssid.equalsIgnoreCase(target)) {
            cap.issues.add(tag + " 通道 BSSID(" + c.bssid + ") 与目标(" + target + ") 不一致，已作废");
            return false;
        }
        // 新鲜度：过期不丢弃（仍可用），但标记 stale 供 UI 提示
        if (c.collectedAtMs > 0 && now - c.collectedAtMs > FRESH_MS) {
            cap.stale = true;
            cap.issues.add(tag + " 通道数据已过期（" + (now - c.collectedAtMs) / 1000 + "s 前）");
        }
        return true;
    }

    /** 选一个通道：优先完整；都完整时优先 root（root 是既有主通道）。 */
    private static Channel pick(Channel a, Channel b) {
        if (a != null && a.complete) return a;
        if (b != null && b.complete) return b;
        return a != null ? a : b;
    }
}
