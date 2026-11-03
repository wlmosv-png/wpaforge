package io.github.wlmosv_png.wpaforge.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 候选身份的**唯一来源**。
 *
 * <h3>为什么要有这个类</h3>
 * 2026-11-02 专项审计发现两套账本对「同一条候选」用了两套身份规则：
 * <ul>
 *   <li>{@code triedSet} 用完整 64 位 FNV-1a；</li>
 *   <li>{@code AttemptLedger} 用同一算法**再截断到 55 位**（给等级腾出 8 位）。</li>
 * </ul>
 * 两者相差 512 倍分辨率，且 FNV-1a 是非加密哈希 —— 审计要求不再把截断值
 * 当成可靠身份。现在两边统一到本类：
 *
 * <pre>
 *   身份 = SHA-256(口令 UTF-8) 的**前 8 字节**，按大端拼成一个 long（完整 64 位）
 * </pre>
 *
 * <h3>取舍说明</h3>
 * <ul>
 *   <li><b>不存明文</b>：只留摘要。摘要不可逆，且不参与任何展示。</li>
 *   <li><b>不截断</b>：不再为了「给等级腾位」牺牲位宽 —— 等级改为独立存储
 *       （见 {@link AttemptLedger} 的 v2 格式）。</li>
 *   <li><b>64 位而非完整 32 字节</b>：单目标候选规模是千级到百万级，
 *       64 位生日碰撞概率在 100 万条时约 2.7e-8；而完整摘要会把每条记录
 *       撑到 33 字节以上，收益与代价不成比例。这是**量化后的**取舍，不是省字节。</li>
 *   <li><b>不用 FNV-1a</b>：短口令空间里 FNV 的分布不如密码学哈希均匀，
 *       且它没有长度扩展之外的安全论据。</li>
 * </ul>
 *
 * <h3>顺序敏感性</h3>
 * {@code Arrays.sort(long[])} / 自定义比较一律使用**有符号序**，本类的
 * {@link #of} 返回值可能为负 —— 排序与查找只要都用有符号比较就自洽。
 * 这一点与旧实现「把等级塞进高位、破坏哈希单调性」的坑不同：现在排序键
 * **就是身份本身**，没有任何附加位。
 */
public final class CandidateId {

    private CandidateId() {}

    /**
     * 候选身份（SHA-256 前 8 字节，大端拼装，完整 64 位）。
     *
     * @param pw 候选口令；{@code null} 返回 0（调用方不应传 null）
     */
    public static long of(String pw) {
        if (pw == null) return 0L;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(pw.getBytes(StandardCharsets.UTF_8));
            long h = 0L;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (d[i] & 0xffL);
            }
            return h;
        } catch (Throwable t) {
            // 理论上不会发生（SHA-256 是 JDK 必备算法）。真发生就返回 0：
            // 0 是一个**合法但极不可能**的身份值，最坏后果是这一条被判为「已存在」，
            // 在保守档下不会跳过任何东西。
            return 0L;
        }
    }

    /** 身份的十六进制展示（排查日志用；不可逆）。 */
    public static String hex(long id) {
        return String.format(java.util.Locale.ROOT, "%016x", id);
    }
}
