package io.github.wlmosv_png.wpaforge.audit;

import java.util.List;
import java.util.TreeSet;

/**
 * 续跑状态**有效性守卫**（1.9.69 加固，纯逻辑，可离线穷举单测）。
 *
 * <h3>它解决什么</h3>
 * 执行游标 {@code execIndex} 与待复验队列 {@code recheckCsv} 都是**原始字典下标（P 坐标）**。
 * 只要「原始候选列表」（P 空间 = 基础字典 + 目标派生 + 智能排序 + 线索）发生变化，
 * 这些下标就会指向**别的候选** —— 比「从头跑」更危险（会误跳正确密码）。
 *
 * <p>本类用一份**签名**把「P 空间 + 目标 + 是否强制重跑」压成一个 64 位值，随任务落盘。
 * 恢复时重算：不一致 → 判定「续跑状态失效」，返回 {@code reset}，由调用方安全重建。
 *
 * <h3>为什么不是简单比对字典 SHA</h3>
 * 需要失效的因素不止「内容」，还包括：顺序、目标派生、智能排序、目标网络、强制重跑。
 * 这些都会改变 P 空间里候选的**排列或身份**，因此签名直接覆盖**有序候选序列本身**，
 * 而不是只看文件哈希。
 *
 * <h3>失效后为什么安全</h3>
 * 失效 → 执行游标归 −1、复验队列清空、相位回 1、offset 归 0 → 本次从头跑。
 * 已由 DIRECT 证实错误的候选在 {@code TriedSet} 里，过滤时仍被跳过；
 * **尚未被 DIRECT 证实错误的候选一定会重跑**，不会因为「为了不重复」而漏判。
 */
public final class ResumeGuard {

    private ResumeGuard() {}

    /** 校验结论。 */
    public static final class Decision {
        /** true = 旧执行状态作废，必须从头重建。 */
        public final boolean reset;
        /** 清理后的执行游标（P 下标；−1 = 从头）。 */
        public final int execIndex;
        /** 清理后的待复验队列（P 下标；升序、去重、越界已剔除）。 */
        public final int[] recheckP;
        /** 归一后的相位：1 或 2。 */
        public final int phase;
        /** 本次算出的签名（十六进制）。 */
        public final String sig;
        /** 失效原因（未失效为空串，用于日志）。 */
        public final String reason;

        Decision(boolean reset, int execIndex, int[] recheckP, int phase,
                 String sig, String reason) {
            this.reset = reset;
            this.execIndex = execIndex;
            this.recheckP = recheckP;
            this.phase = phase;
            this.sig = sig;
            this.reason = reason == null ? "" : reason;
        }
    }

    /** FNV-1a 64 偏移基。 */
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private static long fnv(long h, String s) {
        if (s == null) {
            h ^= 0xffL;
            return h * FNV_PRIME;
        }
        for (int i = 0; i < s.length(); i++) {
            h ^= (s.charAt(i) & 0xffffL);
            h *= FNV_PRIME;
        }
        return h;
    }

    /**
     * 计算「P 空间 + 目标 + 强制重跑」的签名。
     *
     * <p>覆盖：字典内容、字典顺序、目标派生插入、智能排序、线索生成的候选、目标网络、
     * 是否强制重跑。任一变化都会改变签名。
     *
     * @param pSpace     原始候选序列（P 空间），顺序即身份
     * @param targetKey  {@link TriedSet#targetKey(String, String)}
     * @param forceRerun 本次是否强制重跑
     */
    public static long sigOf(List<Candidate> pSpace, String targetKey, boolean forceRerun) {
        long h = FNV_OFFSET;
        if (pSpace != null) {
            for (Candidate c : pSpace) {
                h = fnv(h, c == null ? null : c.password);
                h ^= 0x1fL;              // 分隔符，避免 ["ab","c"] 与 ["a","bc"] 撞车
                h *= FNV_PRIME;
            }
        }
        h = fnv(h, targetKey);
        h ^= (forceRerun ? 0x9e3779b97f4a7c15L : 0L);
        h *= FNV_PRIME;
        return h;
    }

    /** 十六进制签名（与落盘文本一致）。 */
    public static String sigHex(List<Candidate> pSpace, String targetKey, boolean forceRerun) {
        return Long.toHexString(sigOf(pSpace, targetKey, forceRerun));
    }

    /**
     * 校验并清理续跑状态。
     *
     * <p>规则：
     * <ol>
     *   <li>{@code storedSig} 为空（历史任务/首次加固）→ 不重置，只做范围清理并回填签名；</li>
     *   <li>{@code storedSig} 与本次不符 → {@code reset=true}（内容/顺序/目标/派生/重跑变化）；</li>
     *   <li>即便签名相同，也做防御性清理：越界游标 → 重置；越界复验项剔除；相位归一。</li>
     * </ol>
     *
     * @param pSpace     原始候选序列（P 空间）
     * @param targetKey  目标键
     * @param forceRerun 是否强制重跑
     * @param execIndex  落盘的执行游标（P）
     * @param recheckCsv 落盘的待复验队列（P，逗号分隔）
     * @param phase      落盘相位
     * @param storedSig  落盘签名（空 = 未记录）
     */
    public static Decision validate(List<Candidate> pSpace, String targetKey, boolean forceRerun,
                                    int execIndex, String recheckCsv, int phase, String storedSig) {
        int size = pSpace == null ? 0 : pSpace.size();
        String curHex = sigHex(pSpace, targetKey, forceRerun);

        String prev = storedSig == null ? "" : storedSig;
        if (!prev.isEmpty() && !prev.equals(curHex)) {
            // 内容/顺序/目标/派生/重跑发生变化 → 旧下标一律不可信。
            return new Decision(true, -1, new int[0], 1, curHex,
                    "resume-signature-changed");
        }

        // 越界执行游标 → 视为不一致，安全重建（防止把旧 P 下标套到更短的列表上）。
        int ei = execIndex;
        if (ei < -1 || ei > size) {
            return new Decision(true, -1, new int[0], 1, curHex,
                    "exec-index-out-of-range(" + ei + "/" + size + ")");
        }

        // 清理复验队列：剔除越界/负数、去重、升序（保序去重由 TreeSet 完成）。
        TreeSet<Integer> set = new TreeSet<>();
        int[] raw = parseCsv(recheckCsv);
        for (int p : raw) {
            if (p >= 0 && p < size) set.add(p);
        }
        int[] cleaned = new int[set.size()];
        int i = 0;
        for (int v : set) cleaned[i++] = v;

        int ph = (phase == 2 && cleaned.length > 0) ? 2 : 1;
        return new Decision(false, ei, cleaned, ph, curHex, "");
    }

    private static int[] parseCsv(String csv) {
        if (csv == null || csv.isEmpty()) return new int[0];
        String[] parts = csv.split(",");
        int[] tmp = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            try {
                int v = Integer.parseInt(p.trim());
                if (v >= 0) tmp[n++] = v;
            } catch (Exception ignored) { }
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }
}
