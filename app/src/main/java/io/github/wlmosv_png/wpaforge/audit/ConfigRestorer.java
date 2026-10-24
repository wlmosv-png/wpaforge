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

    /** 重建后等它连上的最长时间。 */
    private static final long READD_WAIT_MS = 15000L;
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
            return new RestoreGuard.Facts(otherPending, channelOk, configReadable,
                    entry != null && entry.originalNetId >= 0, pskRequired,
                    entry != null && entry.hadPsk, sealedOk, journalSaved);
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
     * 执行恢复。**任何路径下都会尝试**：命中、未命中、暂停、取消、异常。
     *
     * @return 结论；调用方必须把它写进记录与界面，不许吞掉
     */
    public static RestorePlan.Outcome restore(RestoreJournal.Entry e) {
        if (e == null) {
            return RestorePlan.judge(null, null, false, RestorePlan.ReaddResult.notIssued());
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
                if (readd.issued) waitConnected(e.ssid, READD_WAIT_MS);
            }
        }

        RestorePlan.Verify v = verify(e.ssid);
        RestorePlan.Outcome out = RestorePlan.judge(e, v, removeOk, readd);

        // 兜底：既没还原、还可能留着候选口令 → 再删一次，把“静默故障”变成“可见故障”。
        if (RestorePlan.needsCleanupRetry(out)) {
            boolean again = removeTarget(e.ssid);
            if (again) {
                out = RestorePlan.judge(e, verify(e.ssid), true, readd);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- shell 层

    private static String sh(String cmd, long timeoutMs) {
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
