package io.github.wlmosv_png.wpaforge.wps;

/**
 * WPS 命中判定：把「一次尝试观察到的原始事实」映射成「结论 + 证据事实位」。
 *
 * <h3>为什么要把判定抽出来</h3>
 * 修复前的判定只有一行：
 * <pre>
 *   curId != baseId &amp;&amp; "COMPLETED".equals(state) &amp;&amp; !"WPS".equals(keyMgmt)
 * </pre>
 * 同一帧里明明取到了 {@code ssid}，却从不比较；{@code ip_address} 也不看。
 * 后果是：手机在这段时间里连上**任何别的网络**（回连家里的 WiFi、framework 自动切换、
 * 另一频段上的旧配置），都会满足这个不等式，然后被记成「目标 PIN 命中」，
 * 并且把你当时正在试的那条 PIN 当成命中 PIN —— 用户拿这条 PIN 去连目标，连不上。
 *
 * <h3>四种事实必须分开（本类存在的全部理由）</h3>
 * <ul>
 *   <li>{@link #F_PROTOCOL}：AP 在协议层接受了这个 PIN（WPS 交换完成、凭据交付）；</li>
 *   <li>{@link #F_CREDENTIAL}：凭据已落进系统网络配置（出现了新的 netId 且已连上）；</li>
 *   <li>{@link #F_STORED}：该配置已持久化（App 侧只能由「新 netId」间接推断）；</li>
 *   <li>{@link #F_ONLINE}：此刻真的联网（IP 有效）。</li>
 * </ul>
 * 这四件事**互不推导**：DHCP 失败（没有 IP）不等于 PIN 错，也不等于凭据没交付；
 * 反过来「有 IP」也不证明这是本次 PIN 换来的。修复前把它们压成了一个布尔。
 *
 * <h3>结论等级与允许产生的行为</h3>
 * <table border="1">
 *   <tr><th>结论</th><th>条件</th><th>允许做什么</th></tr>
 *   <tr><td>{@code HIT_CONFIRMED}</td><td>状态成立 + 归属成立 + 本次与目标做过 WPS 会话 + 二次确认未失败</td>
 *       <td>可判「已命中」，可停止任务，可写历史命中</td></tr>
 *   <tr><td>{@code HIT_UNVERIFIED}</td><td>状态像成功，但归属或确认不齐</td>
 *       <td><b>只能记「疑似」</b>；不得判命中、不得停止、不得写历史命中</td></tr>
 *   <tr><td>{@code NOT_TARGET}</td><td>连接明确指向别的网络</td>
 *       <td>这条连接与本次 PIN 无关，必须丢弃；<b>不得</b>当命中，也不得判本次 PIN 失败</td></tr>
 *   <tr><td>{@code NO_HIT}</td><td>其余</td><td>继续跑</td></tr>
 * </table>
 *
 * <p>{@code ip_address} 为空 <b>不降级</b>为 UNVERIFIED —— 那只是「还没拿到 IP」这个事实，
 * 由 {@link #F_ONLINE} 单独记录。反过来，{@code ip} 非空也<b>不能</b>作为命中的唯一条件。
 *
 * <p>纯逻辑，不碰 Android API，可离线测试。
 */
public final class WpsHitJudge {

    private WpsHitJudge() {
    }

    // ---------------- 结论 ----------------

    /** 协议确认 + 目标归属成立 → 可以判「已命中」。 */
    public static final String C_CONFIRMED = "HIT_CONFIRMED";
    /** 像成功但证据不齐 → 只能记「疑似」，不得判命中。 */
    public static final String C_UNVERIFIED = "HIT_UNVERIFIED";
    /** 明确连到别的网络 → 这条连接不能归因给本次 PIN。 */
    public static final String C_NOT_TARGET = "NOT_TARGET";
    /** 不是命中。 */
    public static final String C_NO_HIT = "NO_HIT";

    /** 全部结论（供遍历/自检用）。 */
    public static final String[] ALL_CONCLUSIONS = {
            C_CONFIRMED, C_UNVERIFIED, C_NOT_TARGET, C_NO_HIT};

    /** 只有这个结论允许被渲染成「已命中」。 */
    public static boolean isHit(String verdict) {
        return C_CONFIRMED.equals(verdict);
    }

    /** 结论是否属于「必须保留为不确定」的档（不得强行归类成命中或失败）。 */
    public static boolean isUncertain(String verdict) {
        return C_UNVERIFIED.equals(verdict);
    }

    // ---------------- 事实位 ----------------

    /** 事实：AP 在协议层接受了这个 PIN。 */
    public static final String F_PROTOCOL = "PROTOCOL";
    /** 事实：凭据已交付并落进系统配置（新 netId + 已连上）。 */
    public static final String F_CREDENTIAL = "CREDENTIAL";
    /** 事实：该配置已持久化（由「新 netId」间接推断，无法直接证实）。 */
    public static final String F_STORED = "STORED";
    /** 事实：此刻真的联网（IP 有效）。 */
    public static final String F_ONLINE = "ONLINE";

    // ---------------- 输入 ----------------

    /** 一次观察到的原始事实（全部来自**同一个** STATUS 帧，外加 600ms 复核）。 */
    public static final class In {
        /** 目标 BSSID（小写十六进制，带冒号）。 */
        public String targetBssid = "";
        /** 目标 SSID；未知时留空（手动添加目标时可能未知）。 */
        public String targetSsid = "";
        /** 发 WPS_PIN 之前的 supplicant netId；-1 = 读失败。 */
        public int baseId = -1;
        /** 当前帧的 supplicant netId；-1 = 读失败。 */
        public int curId = -1;
        /** 同一帧的 wpa_state。 */
        public String state = "";
        /** 同一帧的 key_mgmt。 */
        public String keyMgmt = "";
        /** 同一帧的 bssid（当前连着的 AP）。 */
        public String connBssid = "";
        /** 同一帧的 ssid（当前连着的网络名）。 */
        public String connSsid = "";
        /** 同一帧的 ip_address。 */
        public String connIp = "";
        /** 600ms 后复核：1 = 仍 COMPLETED 且非 WPS；0 = 已掉；-1 = 没取到。 */
        public int confirm = -1;
        /** 本次尝试里有没有看到「与**目标**建立 km=WPS 关联」。 */
        public boolean sawTargetWpsSession;
        /** 本次尝试里只在**别的** BSSID 上见过 WPS 会话。 */
        public boolean sawWpsSessionElsewhere;

        public In() {
        }

        /** 便捷构造：只给核心字段（测试与调用方少写样板）。 */
        public In(String targetBssid, String targetSsid, int baseId, int curId,
                  String state, String keyMgmt) {
            this.targetBssid = targetBssid == null ? "" : targetBssid;
            this.targetSsid = targetSsid == null ? "" : targetSsid;
            this.baseId = baseId;
            this.curId = curId;
            this.state = state == null ? "" : state;
            this.keyMgmt = keyMgmt == null ? "" : keyMgmt;
        }
    }

    // ---------------- 判定 ----------------

    /**
     * 判定本次观察是什么。
     *
     * <p>规则顺序（每一步都必须可解释，出错时能直接读到是哪一条拦下的）：
     * <ol>
     *   <li>任一侧 netId 无效（&lt; 0）→ {@code NO_HIT}（判不了，不许当成功）；</li>
     *   <li>状态不是 COMPLETED、或 key_mgmt 仍是 WPS → {@code NO_HIT}；</li>
     *   <li>netId 与基线相同 → {@code NO_HIT}（没有任何变化）；</li>
     *   <li>归属冲突（SSID 已知且不一致，或 SSID 未知而 BSSID 不一致）→ {@code NOT_TARGET}；</li>
     *   <li>归属缺失（BSSID 与 SSID 都读不到、也没一项能对上）→ {@code HIT_UNVERIFIED}；</li>
     *   <li>本次没在目标上见过 WPS 会话（或只在别的 BSSID 上见过）→ {@code HIT_UNVERIFIED}；</li>
     *   <li>600ms 复核明确失败（confirm == 0）→ {@code HIT_UNVERIFIED}；</li>
     *   <li>以上都过 → {@code HIT_CONFIRMED}。</li>
     * </ol>
     *
     * <p>注意第 4 步的取向：<b>SSID 优先、BSSID 兜底</b>。同一个 SSID 的多个 BSSID
     * 属于同一张网，从 {@code ...2b} 漫游到 {@code ...2c} 不应该被判成「不是目标」。
     */
    public static String judge(In in) {
        if (in == null) return C_NO_HIT;
        if (in.baseId < 0 || in.curId < 0) return C_NO_HIT;
        if (!"COMPLETED".equals(in.state)) return C_NO_HIT;
        if ("WPS".equals(in.keyMgmt)) return C_NO_HIT;
        if (in.curId == in.baseId) return C_NO_HIT;

        boolean haveTargetSsid = !isBlank(in.targetSsid);
        boolean haveConnSsid = !isBlank(in.connSsid);
        boolean haveConnBssid = !isBlank(in.connBssid);
        boolean haveTargetBssid = !isBlank(in.targetBssid);

        boolean ssidMatch = haveTargetSsid && haveConnSsid && in.connSsid.equals(in.targetSsid);
        boolean bssidMatch = haveTargetBssid && haveConnBssid
                && in.connBssid.equalsIgnoreCase(in.targetBssid);

        // ④ 归属冲突
        if (haveConnSsid && haveTargetSsid && !ssidMatch) return C_NOT_TARGET;
        if (!haveConnSsid && haveConnBssid && haveTargetBssid && !bssidMatch) {
            return C_NOT_TARGET;
        }

        // ⑤ 归属缺失：连自己连到哪个网都读不出来，就不能把结论抬成命中
        if (!ssidMatch && !bssidMatch) return C_UNVERIFIED;

        // ⑥ 本次要有「跟目标做过 WPS 会话」这件事作陪
        if (!in.sawTargetWpsSession || in.sawWpsSessionElsewhere) return C_UNVERIFIED;

        // ⑦ 600ms 复核
        if (in.confirm == 0) return C_UNVERIFIED;

        return C_CONFIRMED;
    }

    /**
     * 本帧可确认的事实位（逗号分隔，可能为空串）。与结论互相独立：
     * 一条 {@code NOT_TARGET} 的连接可能仍有 {@code ONLINE} 事实。
     */
    public static String facts(In in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean attributed = attributed(in);
        boolean connected = "COMPLETED".equals(in.state) && !"WPS".equals(in.keyMgmt)
                && in.curId >= 0 && in.baseId >= 0 && in.curId != in.baseId;
        if (connected && attributed && in.sawTargetWpsSession && in.confirm != 0) {
            add(sb, F_PROTOCOL);
        }
        if (connected && attributed) {
            add(sb, F_CREDENTIAL);
            add(sb, F_STORED);
        }
        if (hasValidIp(in.connIp)) add(sb, F_ONLINE);
        return sb.toString();
    }

    /** 事实位串里是否包含某一项。 */
    public static boolean hasFact(String facts, String fact) {
        if (facts == null || fact == null || fact.isEmpty()) return false;
        for (String f : facts.split(",")) {
            if (fact.equals(f.trim())) return true;
        }
        return false;
    }

    /** IP 是否可用（非空、有小数点、且不是 0.0.0.0）。 */
    public static boolean hasValidIp(String ip) {
        if (isBlank(ip)) return false;
        String v = ip.trim();
        if (!v.contains(".")) return false;
        if ("0.0.0.0".equals(v)) return false;
        return true;
    }

    /** 归属是否成立（SSID 或 BSSID 至少一项对上，且无冲突）。 */
    public static boolean attributed(In in) {
        if (in == null) return false;
        boolean haveTargetSsid = !isBlank(in.targetSsid);
        boolean haveConnSsid = !isBlank(in.connSsid);
        boolean haveConnBssid = !isBlank(in.connBssid);
        if (haveTargetSsid && haveConnSsid) return in.connSsid.equals(in.targetSsid);
        if (haveConnBssid && !isBlank(in.targetBssid)) {
            return in.connBssid.equalsIgnoreCase(in.targetBssid);
        }
        return false;
    }

    // ---------------- 展示 ----------------

    /** 结论 → 人话（双语）。 */
    public static String text(String verdict, boolean zh) {
        if (C_CONFIRMED.equals(verdict)) {
            return zh ? "命中（协议确认 + 目标归属成立）"
                      : "HIT (protocol confirmed, attributed to target)";
        }
        if (C_UNVERIFIED.equals(verdict)) {
            return zh ? "疑似命中（证据不足，已记录，不判命中）"
                      : "SUSPECT (insufficient evidence - recorded, not a hit)";
        }
        if (C_NOT_TARGET.equals(verdict)) {
            return zh ? "连上的是别的网络（与本 PIN 无关）"
                      : "connected to another network (unrelated)";
        }
        return zh ? "未命中" : "no hit";
    }

    /** 为什么是这个结论（排查用，一行）。 */
    public static String why(In in, boolean zh) {
        if (in == null) return zh ? "无数据" : "no data";
        if (in.baseId < 0 || in.curId < 0) {
            return zh ? "netId 读不到（不可判定）" : "netId unreadable";
        }
        if (!"COMPLETED".equals(in.state)) {
            return zh ? ("状态不是 COMPLETED（" + in.state + "）") : ("state=" + in.state);
        }
        if ("WPS".equals(in.keyMgmt)) {
            return zh ? "仍在 WPS 会话中" : "still in WPS session";
        }
        if (in.curId == in.baseId) {
            return zh ? "netId 与基线相同（没有新配置）" : "netId unchanged";
        }
        if (C_NOT_TARGET.equals(judge(in))) {
            return zh ? ("连到了 " + in.connSsid + " / " + in.connBssid + "，不是目标")
                      : ("connected to " + in.connSsid + " / " + in.connBssid + ", not target");
        }
        if (!attributed(in)) {
            return zh ? "读不到当前连接的 SSID/BSSID（归属无法证实）"
                      : "connected SSID/BSSID unreadable";
        }
        if (!in.sawTargetWpsSession || in.sawWpsSessionElsewhere) {
            return zh ? "本次没在目标上看到 WPS 会话" : "no WPS session seen on target";
        }
        if (in.confirm == 0) {
            return zh ? "600ms 后连接已掉（未稳定）" : "dropped within 600ms";
        }
        return zh ? "全部条件成立" : "all conditions satisfied";
    }

    /** 证据明细（一行，落到历史与日志里）。 */
    public static String detail(In in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("base=").append(in.baseId).append(" id=").append(in.curId);
        sb.append(" state=").append(in.state).append(" km=").append(in.keyMgmt);
        sb.append(" connSsid=").append(in.connSsid).append(" connBssid=").append(in.connBssid);
        sb.append(" ip=").append(in.connIp).append(" confirm=").append(in.confirm);
        sb.append(" wpsSession=").append(in.sawTargetWpsSession ? 1 : 0);
        String f = facts(in);
        sb.append(" facts=").append(f.isEmpty() ? "-" : f);
        return sb.toString();
    }

    // ---------------- 可信等级（供命中详情页三级展示） ----------------

    /** 已确认：必要证据完整，能够证明结果。 */
    public static final String L_CONFIRMED = "已确认";
    /** 待验证：观察到了相关事件，但还不能证明完整结果。 */
    public static final String L_PENDING = "待验证";
    /** 未确认：缺少关键证据，或结果与本次会话无法建立可靠关联。 */
    public static final String L_UNCONFIRMED = "未确认";

    /** 缺失证据的键（供 UI 列出"缺了什么"）。 */
    public static final String MISS_WPS_SUCCESS = "wps_success";      // 协议终局事件（WPS-SUCCESS/M8）
    public static final String MISS_CONFIG_ATTRIB = "config_attrib";  // 配置归属（该 netId 是否本次新建）
    public static final String MISS_CREDENTIAL = "credential";       // 凭据存在性（配置里有真口令吗）
    public static final String MISS_CONNECT = "connect";             // 当前连接是否正常（IP）
    public static final String MISS_KEEP = "keep";                   // 连接保持观察

    /**
     * 可信等级：基于结论 + 缺失证据清单。
     *
     * <p>任务书要求：不能把超时等同于 PIN 错误，不能把关联等同于 PIN 正确，
     * 不能把新 netId 等同于凭据交付。SUCCESS 必须严格证据门槛。
     *
     * <ul>
     *   <li>结论 = HIT_CONFIRMED：先把能达到的证据都算上，若仍缺
     *       WPS-SUCCESS / 凭据存在性 中的关键项 → 降级为「待验证」；</li>
     *   <li>结论 = HIT_UNVERIFIED：证据不足，一律「待验证」；</li>
     *   <li>结论 = NOT_TARGET / NO_HIT：未确认。</li>
     * </ul>
     *
     * <p>历史旧记录（无本会话证据）不得自动升级成「已确认」 —— 由
     * {@link #legacyLevel()} 单独处理。
     */
    public static String level(In in) {
        String v = judge(in);
        if (C_CONFIRMED.equals(v)) {
            // 协议终局（M8/WPS-SUCCESS）与凭据存在性，是"已确认"的硬门槛。
            // 若本观测拿不到 → 只能「待验证」，不得硬报已确认。
            java.util.List<String> missing = missingEvidence(in, true);
            if (missing.contains(MISS_WPS_SUCCESS) || missing.contains(MISS_CREDENTIAL)) {
                return L_PENDING;
            }
            return L_CONFIRMED;
        }
        if (C_UNVERIFIED.equals(v)) return L_PENDING;
        return L_UNCONFIRMED;
    }

    /** 旧版 HIT 记录（无本会话观测）：只能「待验证」，不得自动升级成已确认。 */
    public static String legacyLevel() {
        return L_PENDING;
    }

    /**
     * 缺失证据清单（中文）。
     *
     * @param strict 是否按"已确认"的硬门槛评估（含协议终局/凭据存在性）。
     */
    public static java.util.List<String> missingEvidence(In in, boolean strict) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (in == null) {
            out.add(MISS_WPS_SUCCESS);
            out.add(MISS_CONFIG_ATTRIB);
            out.add(MISS_CREDENTIAL);
            out.add(MISS_CONNECT);
            out.add(MISS_KEEP);
            return out;
        }
        // 平台无法直接提供这些证据（Android 15 无 WPS API、本文档不读配置）→ 如实列出
        if (strict) {
            out.add(MISS_WPS_SUCCESS);      // 本实现不读 supplicant 的 M8/WPS-SUCCESS 事件
            out.add(MISS_CONFIG_ATTRIB);    // 本实现不做 LIST_NETWORKS 前后对比
            out.add(MISS_CREDENTIAL);       // 本实现不读取配置里的口令
        }
        if (!"COMPLETED".equals(in.state) || in.state.isEmpty()) out.add(MISS_CONNECT);
        if (!hasValidIp(in.connIp)) out.add(MISS_CONNECT);
        // 连接保持观察是任务级事实，单帧观测本就不含 → 恒列
        out.add(MISS_KEEP);
        return out;
    }

    /** 缺失证据键 → 中文说明。 */
    public static String missingLabel(String key, boolean zh) {
        if (MISS_WPS_SUCCESS.equals(key)) {
            return zh ? "协议终局证据（WPS-SUCCESS / M8）：本实现不读取 supplicant 事件流"
                      : "protocol terminal evidence (WPS-SUCCESS/M8): not read";
        }
        if (MISS_CONFIG_ATTRIB.equals(key)) {
            return zh ? "配置归属（该 netId 是否本次会话新建）：未做 LIST_NETWORKS 前后对比"
                      : "config attribution (netId created this session?): not verified";
        }
        if (MISS_CREDENTIAL.equals(key)) {
            return zh ? "凭据存在性（配置里是否有真口令）：本实现不读取口令"
                      : "credential presence: not read";
        }
        if (MISS_CONNECT.equals(key)) {
            return zh ? "当前连接正常（IP 有效）：未确认" : "connection normal (valid IP): unconfirmed";
        }
        if (MISS_KEEP.equals(key)) {
            return zh ? "连接保持观察：未做（需任务级观测）" : "connection keep: not observed";
        }
        return zh ? ("缺失证据：" + key) : ("missing evidence: " + key);
    }

    /** 可信等级 → 中文文案（含说明）。 */
    public static String levelText(String level, boolean zh) {
        if (L_CONFIRMED.equals(level)) {
            return zh ? "已确认（必要证据完整，能证明结果）"
                      : "confirmed (necessary evidence complete)";
        }
        if (L_PENDING.equals(level)) {
            return zh ? "待验证（观察到相关事件，但还不能证明完整结果）"
                      : "pending (observed events, cannot yet prove full result)";
        }
        return zh ? "未确认（缺少关键证据或无法建立可靠关联）"
                  : "unconfirmed (missing key evidence or unreliable association)";
    }

    // ---------------- internals ----------------

    private static void add(StringBuilder sb, String f) {
        if (sb.length() > 0) sb.append(',');
        sb.append(f);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
