package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 字典审计引擎。
 *
 * 做的是"对已握有的材料做候选比对"，不是对空口发送尝试：
 *  - 模式 A：明文比对。已知某网络的真实口令，检查它在字典里的位次（弱口令评估）
 *  - 模式 B：握手比对。给定抓到的四次握手材料，逐条候选算 PMK→PTK→MIC 比对
 *
 * 两种模式都是纯本地计算，不发包、不注入、不联网。
 */
public final class AuditEngine {

    public interface Progress {
        /** @param tested 已测条数 @param total 总条数 @param found 命中内容（未命中为 null） */
        void onProgress(long tested, long total, String found);
        void onDone(Result result);
        void onError(String message);
    }

    /** 一次审计的结果 */
    public static final class Result {
        public final long tested;
        public final long elapsedMs;
        public final boolean hit;
        public final String hitPassword;
        public final Candidate.Origin hitOrigin;
        public final long hitIndex;      // 在字典中的位次，1 起
        public final double perSecond;

        Result(long tested, long elapsedMs, boolean hit, String hitPassword,
               Candidate.Origin hitOrigin, long hitIndex) {
            this.tested = tested;
            this.elapsedMs = elapsedMs;
            this.hit = hit;
            this.hitPassword = hitPassword;
            this.hitOrigin = hitOrigin;
            this.hitIndex = hitIndex;
            this.perSecond = elapsedMs > 0 ? (tested * 1000.0 / elapsedMs) : 0;
        }
    }

    // ---- 模式 A：弱口令位次评估 ----

    /**
     * 在字典中查找明文口令，返回位次（1 起），未找到返回 -1。
     * 用于回答"我家密码在常见弱口令表里排第几"。
     */
    public static long rankOf(List<Candidate> dict, String plainPassword) {
        if (dict == null || plainPassword == null) return -1;
        long i = 1;
        for (Candidate c : dict) {
            if (c.password.equals(plainPassword)) return i;
            i++;
        }
        return -1;
    }

    // ---- 模式 B：握手比对 ----

    /** 握手材料。全部是用户导入的离线数据。 */
    public static final class Handshake {
        public final String ssid;
        public final byte[] apMac;
        public final byte[] staMac;
        public final byte[] aNonce;
        public final byte[] sNonce;
        public final byte[] eapolFrame;   // MIC 已置零的帧
        public final int micOffset;
        public final byte[] expectedMic;

        public Handshake(String ssid, byte[] apMac, byte[] staMac,
                         byte[] aNonce, byte[] sNonce,
                         byte[] eapolFrame, int micOffset, byte[] expectedMic) {
            this.ssid = ssid;
            this.apMac = apMac;
            this.staMac = staMac;
            this.aNonce = aNonce;
            this.sNonce = sNonce;
            this.eapolFrame = eapolFrame;
            this.micOffset = micOffset;
            this.expectedMic = expectedMic;
        }

        /** 材料完整性检查，返回缺失项描述，空串表示齐全 */
        public String validate() {
            if (ssid == null || ssid.isEmpty()) return L.s("缺少 SSID", "Missing SSID");
            if (apMac == null || apMac.length != 6) return L.s("AP MAC 需 6 字节", "AP MAC must be 6 bytes");
            if (staMac == null || staMac.length != 6) return L.s("STA MAC 需 6 字节", "STA MAC must be 6 bytes");
            if (aNonce == null || aNonce.length != 32) return L.s("ANonce 需 32 字节", "ANonce must be 32 bytes");
            if (sNonce == null || sNonce.length != 32) return L.s("SNonce 需 32 字节", "SNonce must be 32 bytes");
            if (eapolFrame == null || eapolFrame.length == 0) return L.s("缺少 EAPOL 帧", "Missing EAPOL frame");
            if (micOffset < 0 || micOffset + 16 > eapolFrame.length) return L.s("MIC 偏移越界", "MIC offset out of range");
            if (expectedMic == null || expectedMic.length != 16) return L.s("期望 MIC 需 16 字节", "Expected MIC must be 16 bytes");
            return "";
        }
    }

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    /**
     * 对握手材料跑字典比对。阻塞调用，建议放在工作线程。
     */
    public void runHandshake(Handshake hs, List<Candidate> dict, Progress cb) {
        if (hs == null || dict == null) {
            cb.onError(L.s("参数缺失", "Missing parameters"));
            return;
        }
        String bad = hs.validate();
        if (!bad.isEmpty()) {
            cb.onError(L.s("握手材料不完整: ", "Incomplete handshake material: ") + bad);
            return;
        }
        cancelled.set(false);
        long total = dict.size();
        long tested = 0;
        long start = System.currentTimeMillis();
        int noticeEvery = Math.max(1, (int) (total / 100)); // 每 1% 回调一次

        for (Candidate c : dict) {
            if (cancelled.get()) {
                cb.onDone(new Result(tested, System.currentTimeMillis() - start,
                        false, null, null, -1));
                return;
            }
            tested++;
            boolean ok = WpaCore.checkCandidate(
                    c.password, hs.ssid, hs.apMac, hs.staMac,
                    hs.aNonce, hs.sNonce, hs.eapolFrame, hs.micOffset, hs.expectedMic);
            if (ok) {
                cb.onDone(new Result(tested, System.currentTimeMillis() - start,
                        true, c.password, c.origin, tested));
                return;
            }
            if (tested % noticeEvery == 0) {
                cb.onProgress(tested, total, null);
            }
        }
        cb.onDone(new Result(tested, System.currentTimeMillis() - start,
                false, null, null, -1));
    }

    /**
     * 批量评估一组明文口令在字典中的位次，输出弱口令报告。
     * 输入通常是本机已保存配置里的口令。
     */
    public static List<String> evaluatePlain(List<Candidate> dict,
                                             List<String[]> knownPairs) {
        List<String> lines = new ArrayList<>();
        for (String[] pair : knownPairs) {
            if (pair.length < 2) continue;
            String ssid = pair[0];
            String pw = pair[1];
            long rank = rankOf(dict, pw);
            if (rank > 0) {
                lines.add(ssid + " -> 命中字典第 " + rank + L.s(" 位 (弱口令)", " (weak passphrase)"));
            } else {
                lines.add(ssid + L.s(" -> 未在字典中 (相对安全)", " -> not in dictionary (relatively safe)"));
            }
        }
        return lines;
    }
}
