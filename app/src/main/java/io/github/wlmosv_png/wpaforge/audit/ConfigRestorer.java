package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.util.List;

/**
 * 目标网络配置的**快照与还原**（R11）。
 *
 * <h3>它接管的那件事</h3>
 * 引擎为了归因，每条候选之前都会把目标从系统里删掉再用候选口令加回来。
 * 跑完之后系统里留下的是“最后一条候选的口令”。本类负责把这一切收回来：
 * <ol>
 *   <li>{@link #snapshot} —— 在**任何破坏性动作之前**记下原 netId + 原口令（加密后）；</li>
 *   <li>{@link #settle} —— 收尾入口：**命中保留**（配置就是正确口令，不删）、
 *       其余终态走 {@link #restore}；</li>
 *   <li>{@link #restore} —— 删掉候选残留，再用原口令把目标加回去，最后**读回验证**。</li>
 * </ol>
 *
 * <h3>三个刻意的保守设计</h3>
 * <ul>
 *   <li><b>不猜口令</b>：原口令只从系统自己的 WifiConfigStore 读，读不到就
 *       明确标记 {@code hadPsk=false}，而不是拿候选里的任何东西去凑。</li>
 *   <li><b>不假装成功</b>：{@code cmd wifi connect-network} 是哑命令
 *       （成功失败都无输出、退出码都是 0），所以恢复成功的唯一判据是
 *       **读回状态确认连上目标**，见 {@link RestorePlan#judge}。</li>
 *   <li><b>不把明文带出本类</b>：原口令只在方法体内的局部变量里存在，
 *       写进记录时先过 {@link SecretBox}；本类不打印任何口令。</li>
 * </ul>
 */
public final class ConfigRestorer {

    private static final String TAG = "WPAForgeRestore";

    /** WifiConfigStore 的读取路径（Android 10+）。 */
    public static final String CONFIG_XML =
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml";

    /**
     * 重建后等它连上的最长时间。
     *
     * <p>1.9.28：它**只影响「有没有连上」这个观察值**，不再影响恢复成败 ——
     * 目标不在覆盖范围内时等再久也连不上，而那不该把“配置已写回”判成失败。
     */
    private static final long READD_WAIT_MS = 6000L;
    /** 轮询间隔。 */
    private static final long POLL_MS = 500L;

    private ConfigRestorer() {}

    /** 与 {@code CrackEngine.securityArg} 同一口径（{@code cmd wifi connect-network} 的参数）。 */
    public static String securityArg(NetRecord.Security s) {
        if (s == null) return "wpa2";
        switch (s) {
            case OPEN: return "open";
            case OWE:  return "owe";
            case WEP:  return "wep";
            case WPA3: return "wpa3";
            default:   return "wpa2";
        }
    }

    // ---------------------------------------------------------------- 快照

    /**
     * 记录目标的原始配置。**必须在第一次 {@code forget-network} 之前调用。**
     *
     * @param ssid     目标 SSID
     * @param bssid    目标 BSSID（可空）
     * @param security 加密方式（{@link #securityArg}）
     * @param runId    本次运行的标识（血缘）
     * @return 记录；调用方负责 {@link RestoreJournal#save}
     */
    public static final class Snapshot {
        public RestoreJournal.Entry entry;
        /** `cmd wifi list-networks` 是否读到（读不到 = 连目标有没有配置都不知道）。 */
        public boolean channelOk;
        /** WifiConfigStore 是否读到。 */
        public boolean configReadable;
        /** 目标是否需要口令（开放网络不需要 —— 那种情况没口令也不算失败）。 */
        public boolean pskRequired;
        /** 读到的口令是否已成功加密（拿不到密钥就不许落盘）。 */
        public boolean sealedOk = true;

        /** 交给 {@link RestoreGuard} 的事实包。 */
        public RestoreGuard.Facts facts(boolean otherPending, boolean journalSaved) {
            return facts(otherPending, journalSaved, false, 0);
        }

        /**
         * 交给 {@link RestoreGuard} 的事实包（1.9.28：附带「记录不可解析」与「堆积条数」）。
         *
         * @param journalUnreadable 盘上有记录但解析不出来（未知 → fail closed）
         * @param pendingCount      盘上待处理记录条数
         */
        public RestoreGuard.Facts facts(boolean otherPending, boolean journalSaved,
                                        boolean journalUnreadable, int pendingCount) {
            return new RestoreGuard.Facts(otherPending, channelOk, configReadable,
                    entry != null && entry.originalNetId >= 0, pskRequired,
                    entry != null && entry.hadPsk, sealedOk, journalSaved,
                    journalUnreadable, pendingCount);
        }
    }

    public static Snapshot snapshot(String taskId, String ssid, String bssid,
                                    String security, String runId) {
        Snapshot s = new Snapshot();
        RestoreJournal.Entry e = new RestoreJournal.Entry();
        s.entry = e;
        e.taskId = taskId == null ? "" : taskId;
        e.ssid = ssid == null ? "" : ssid;
        e.bssid = bssid == null ? "" : bssid;
        e.security = (security == null || security.isEmpty()) ? "wpa2" : security;
        e.runId = runId == null ? "" : runId;
        e.armedAt = System.currentTimeMillis();

        String list = sh("cmd wifi list-networks", 6000);
        s.channelOk = (list != null);
        e.channelOk = s.channelOk;
        e.originalNetId = (list == null) ? -1L
                : ListNetworksParser.findNetId(list, e.ssid);

        String xml = readConfigXml();
        s.configReadable = (xml != null);
        SavedWifiReader.Saved saved = s.configReadable ? SavedWifiReader.findIn(xml, e.ssid) : null;

        // 需要口令吗？只有三种情况算「不需要」：明确是开放网络。
        // 其余一律按「需要口令」处理 —— 包括「列表里有目标、但配置里查不到它」
        // 这种自相矛盾的状态（那时磁盘上确实有一条我们读不出来的凭据）。
        if (saved == null) {
            s.pskRequired = (e.originalNetId >= 0);
        } else {
            String sec = saved.security == null ? "" : saved.security;
            s.pskRequired = !(sec.contains("开放") || sec.contains("Open")
                    || sec.contains("NONE"));
        }

        if (saved != null && saved.hasPassword()) {
            e.hadPsk = true;
            String sealed = SecretBox.seal(saved.password);   // 明文只在这里的局部变量里
            e.sealedPsk = sealed == null ? "" : sealed;
            s.sealedOk = !e.sealedPsk.isEmpty();
        } else {
            s.sealedOk = !s.pskRequired;   // 不需要口令时没有秘密要保护
        }

        // 快照是否可信：通道必须读到；目标是已保存网络时配置也必须读到。
        e.snapshotOk = s.channelOk && (s.configReadable || e.originalNetId < 0);
        return s;
    }

    // ---------------------------------------------------------------- 恢复

    /**
     * 执行恢复（1.9.28：把「配置恢复」与「连接观察」彻底分开）。
     *
     * <h3>成败判据</h3>
     * 恢复的目标是「把原配置放回系统」，**不是**「此刻必须连上」：
     * <ul>
     *   <li>写回被独立回读确认 → 成功（连接与 IP 只作为观察值落盘）；</li>
     *   <li>写回没确认（命令没发出 / 列表里看不到目标）→ 失败，且标出残留风险；</li>
     *   <li>目标不在覆盖范围内、当前连着别的网、观察窗口内没等到 —— 都不判失败。</li>
     * </ul>
     * 判定全部交给 {@link RestorePlan#judge}，本方法只负责采集事实。
     *
     * @param e       记录；{@code null} → NO_RECORD
     * @param keepHit 本轮是否命中（命中走「保留」，其余走「删干净 + 写回原配置」）
     * @return 结论；调用方必须把它写进记录与界面，不许吞掉
     */
    public static RestorePlan.Outcome restore(RestoreJournal.Entry e, boolean keepHit) {
        if (e == null) {
            return RestorePlan.judge(null, null, false, RestorePlan.ReaddResult.notIssued());
        }
        if (keepHit && RestorePlan.keepsHitConfig(e, true)) {
            RestorePlan.Outcome kept = keepHit(e);
            if (kept != null) return kept;
        }

        List<RestorePlan.Step> steps = RestorePlan.steps(e);
        if (steps.isEmpty()) {
            return RestorePlan.judge(e, verify(e.ssid), true, RestorePlan.ReaddResult.written());
        }

        boolean removeOk = removeTarget(e.ssid);

        RestorePlan.ReaddResult readd = RestorePlan.ReaddResult.notAttempted();
        if (steps.contains(RestorePlan.Step.READD_ORIGINAL)) {
            String psk = SecretBox.open(e.sealedPsk);
            if (psk == null) {
                // 加密通道不可用 → 不许退回明文，也不许假装能还原
                android.util.Log.w(TAG, "原口令无法解开，跳过重建（不落明文）");
                readd = RestorePlan.ReaddResult.notIssued();
            } else {
                readd = readd(e.ssid, e.security, psk);
                // 只是给「有没有连上」这个观察值一点时间窗口；等不到也不判失败。
                if (readd.issued) waitConnected(e.ssid, READD_WAIT_MS);
            }
        }

        RestorePlan.Verify v = verify(e.ssid);
        RestorePlan.Outcome out = RestorePlan.judge(e, v, removeOk, readd);

        // 兜底：既没写回确认、还可能留着候选口令 → 再删一次，
        // 把“静默故障”变成“可见故障”。写回确认过的一律不再删（会把正确配置删掉）。
        if (RestorePlan.needsCleanupRetry(out)) {
            boolean again = removeTarget(e.ssid);
            if (again) {
                out = RestorePlan.judge(e, verify(e.ssid), true, readd);
            }
        }
        return out;
    }

    /** 兼容旧签名：非命中路径。 */
    public static RestorePlan.Outcome restore(RestoreJournal.Entry e) {
        return restore(e, false);
    }

    // ---------------------------------------------------------------- 只读安全检查

    /** 只读安全检查的结果（不改动任何系统状态）。 */
    public static final class Safety {
        public final String ssid;
        /** 目标当前是否在已保存网络列表里。 */
        public final boolean configPresent;
        /** 此刻是否连着目标。 */
        public final boolean connected;
        /** 是否有有效 IP。 */
        public final boolean ipOk;
        public final String detail;

        Safety(String ssid, boolean configPresent, boolean connected, boolean ipOk, String detail) {
            this.ssid = ssid == null ? "" : ssid;
            this.configPresent = configPresent;
            this.connected = connected;
            this.ipOk = ipOk;
            this.detail = detail == null ? "" : detail;
        }

        /** 一句话人读结论。 */
        public String describe() {
            if (ssid.isEmpty()) return "未指定目标";
            StringBuilder sb = new StringBuilder();
            sb.append("目标「").append(ssid).append("」");
            sb.append(configPresent ? "有已保存配置" : "没有已保存配置");
            if (connected) sb.append("，此刻已连上");
            if (ipOk) sb.append("，有有效 IP");
            sb.append("（").append(detail).append("）");
            return sb.toString();
        }
    }

    /**
     * **只读**检查某目标当前的配置状态。
     *
     * <p>用途：清理记录前的安全检查、界面「查看详情」、以及“同目标是否还有残留”的判断。
     * 它读 {@code cmd wifi list-networks} 与 {@code cmd wifi status}，**不改任何东西**。
     */
    public static Safety inspect(String ssid) {
        if (ssid == null || ssid.isEmpty()) return new Safety("", false, false, false, "无目标");
        boolean present = targetPresent(ssid);
        RestorePlan.Verify v = verify(ssid);
        return new Safety(ssid, present, v.connectedTo(ssid),
                RestorePlan.isIpValid(v.ip), v.describe());
    }

    // ---------------------------------------------------------------- 命中保留

    /**
     * 命中收尾：**保留**目标当前那条配置（= 刚验证过的正确口令），全程不做删除。
     *
     * <h3>为什么可以保留</h3>
     * 命中是「目标 SSID + 握手完成 + 有 IP + netId 对得上」四条件同行判出来的，
     * 而系统里那条配置正是**这一条候选口令**写进去的 —— 它就是要的结果。
     * 走标准恢复把它删掉（旧行为）才会让用户困惑：明明破解出来了，
     * 手机却掉线、下次还得再破一遍。
     *
     * <h3>唯一判据</h3>
     * 回读确认目标**在已保存列表里**（{@link #targetPresent}）。读不到就不算保留成立。
     *
     * @return 结论；**返回 {@code null} 表示保留条件不成立**，调用方必须退回
     *         {@link #restore(RestoreJournal.Entry)}（旧行为：删干净 + 还原原配置）
     */
    public static RestorePlan.Outcome keepHit(RestoreJournal.Entry e) {
        if (e == null) return RestorePlan.judgeKept(null, null);
        // 配置根本没写进系统 → 没什么可保留的，交给标准恢复（它会如实报告）。
        if (!e.settled() && !targetPresent(e.ssid)) {
            logQuiet("命中保留不成立（列表里没有 " + e.ssid + "），退回标准恢复");
            return null;
        }
        return RestorePlan.judgeKept(e, verify(e.ssid));
    }

    /** 日志不许成为故障源：JVM 单测里 {@code android.util.Log} 是 stub（调用即抛）。 */
    private static void logQuiet(String msg) {
        try {
            android.util.Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 收尾总入口（1.9.25）：命中 → 保留；其余终态 → 标准恢复。
     *
     * <p>保留条件不成立时**自动退回标准恢复** —— 不允许出现
     * 「既没保留、也没清理」的第三种结局。
     *
     * @param hit 本轮是否命中
     * @param e   恢复记录
     */
    public static RestorePlan.Outcome settle(boolean hit, RestoreJournal.Entry e) {
        if (RestorePlan.keepsHitConfig(e, hit)) {
            RestorePlan.Outcome kept = keepHit(e);
            if (kept != null) return kept;
        }
        return restore(e);
    }

    // ---------------------------------------------------------------- shell 层

    /**
     * shell 执行接缝。
     *
     * <p>生产恒为 {@code su -c <cmd>}（见 {@link RootProber#exec}）；测试装一个假壳，
     * 就能把 {@link #keepHit} / {@link #settle} 这条**真路径**（含
     * `cmd wifi list-networks` 的解析、保留不成立的退回）在 JVM 里断言，
     * 而不是只测纯逻辑。与 {@link RestoreJournal#install} 同一套路子。
     */
    public interface Shell {
        String exec(String cmd, long timeoutMs);
    }

    private static volatile Shell shell = null;

    /** 装/卸假壳（测试专用；传 {@code null} 恢复生产实现）。 */
    public static void installShell(Shell s) { shell = s; }

    private static String sh(String cmd, long timeoutMs) {
        Shell s = shell;
        if (s != null) {
            try {
                return s.exec(cmd, timeoutMs);
            } catch (Throwable t) {
                return null;
            }
        }
        try {
            return RootProber.exec(null, new String[]{"su", "-c", cmd}, timeoutMs);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readConfigXml() {
        return sh("cat " + CONFIG_XML, 12000);
    }

    /** 目标当前是否还在已保存列表里（netId ≥ 0 即在）。 */
    static boolean targetPresent(String ssid) {
        String list = sh("cmd wifi list-networks", 6000);
        return list != null && ListNetworksParser.findNetId(list, ssid) >= 0;
    }

    /** 删掉目标配置；返回“确认已不在列表里”。 */
    static boolean removeTarget(String ssid) {
        if (ssid == null || ssid.isEmpty()) return true;
        for (int round = 0; round < 3; round++) {
            String list = sh("cmd wifi list-networks", 6000);
            if (list == null) return false;
            long id = ListNetworksParser.findNetId(list, ssid);
            if (id < 0) return true;
            sh("cmd wifi forget-network " + id, 5000);
            try { Thread.sleep(200L); } catch (InterruptedException ignored) {}
        }
        return !targetPresent(ssid);
    }

    /**
     * 用给定口令把目标加回已保存列表并发起连接，**并回读复核**。
     *
     * <p>为什么不只看命令返回值：{@code cmd wifi connect-network} 是哑命令 ——
     * 成功失败都无输出、退出码都是 0。只凭「拿到帧」就把配置算作已写回，
     * 会在命令被静默丢弃时把「配置丢了」报成「没连上」（F5）。
     * 所以这里再读一次已保存列表：只有**看得到目标**才算写回。
     */
    static RestorePlan.ReaddResult readd(String ssid, String security, String psk) {
        try {
            String cmd = ConnectSpec.buildConnect(ssid, security, psk, null, false);
            String out = sh(cmd, 20000);
            if (out == null) return RestorePlan.ReaddResult.notIssued();
            String list = sh("cmd wifi list-networks", 6000);
            if (list == null) {
                // 回读失败：不能凭命令成功就认定写回（保守按未写回处理，
                // 由 RestorePlan 用 verify() 的列表再观察一次）
                return RestorePlan.ReaddResult.issuedButMissing();
            }
            return ListNetworksParser.findNetId(list, ssid) >= 0
                    ? RestorePlan.ReaddResult.written()
                    : RestorePlan.ReaddResult.issuedButMissing();
        } catch (Throwable t) {
            return RestorePlan.ReaddResult.notIssued();
        }
    }

    /** 轮询直到连上目标或超时（不抛异常）。 */
    static void waitConnected(String ssid, long budgetMs) {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < budgetMs) {
            if (verify(ssid).connectedTo(ssid)) return;
            try { Thread.sleep(POLL_MS); } catch (InterruptedException ignored) { return; }
        }
    }

    /** 读回快照：列表是否含目标 + status 里的 SSID/状态/IP（同源绑定，见 StatusParser）。 */
    static RestorePlan.Verify verify(String ssid) {
        boolean present = targetPresent(ssid);
        String st = sh("cmd wifi status", 5000);
        if (st == null) return new RestorePlan.Verify(present, null, null, null);
        StatusParser.Result r = StatusParser.parse(st, ssid);
        return new RestorePlan.Verify(present, r.currentSsid, r.supplicantState, r.ip);
    }

    /** 供日志使用的一行（**不含口令**）。 */
    public static String describe(RestoreJournal.Entry e) {
        if (e == null) return "无恢复记录";
        return L.f("目标 %s · 原 netId=%d · 原口令%s · 快照%s",
                "target %s · original netId=%d · original passphrase %s · snapshot %s",
                e.ssid, e.originalNetId,
                e.canReadd() ? L.s("已加密保存", "sealed") : L.s("不可用", "unavailable"),
                e.snapshotOk ? "OK" : "PARTIAL");
    }
}
