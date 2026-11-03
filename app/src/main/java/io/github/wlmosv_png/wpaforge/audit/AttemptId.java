package io.github.wlmosv_png.wpaforge.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 任务/候选标识（R6 契约）—— **纯逻辑，无 Android 依赖**。
 *
 * <h3>这个类解决什么</h3>
 * 判定链的每条证据（status 轮次、日志行、广播）当前只能靠
 * 「时刻晚于命令」+「SSID/BSSID 文本相等」来归属，没有任何一个
 * 「属于哪次任务、哪条候选」的标识。后果在 R3 审计里逐条列出
 * （见 {@code doc/R3-AUDIT.md} 第 6.1 节）。
 *
 * <h3>这个类绝不解决什么（**误用禁令**）</h3>
 * <b>attemptId 相等 ≠ 底层连接归属成立。</b>
 * 它只能回答「这条证据记在谁名下」，不能回答「这条连接是不是这条候选
 * 造成的」。后者需要独立证据（配置已删净并回读、命令前已断开、握手完成
 * 属于目标 BSSID…）。任何用它代替归属判定的写法都是错的 ——
 * {@link #attributionProvenByAttemptIdAlone()} 恒返回 {@code false}，
 * 就是为了让这种误用必须显式写出来才通得过评审。
 *
 * <h3>ID 形状</h3>
 * <pre>
 *   runId      = &lt;毫秒级时间36进制&gt;-&lt;哈希8位&gt;-&lt;进程内序号36进制&gt;
 *                例：m5k3p1x9-3f9a2b7c-1
 *   attemptId  = runId + "#" + 序号（1 起）
 *                例：m5k3p1-3f9a2b7c-1#11
 * </pre>
 * 设计取舍：
 * <ul>
 *   <li><b>runId 不含墙钟可读时间</b>（36 进制）、不含 SSID 明文，只含其哈希前 8 位
 *       —— 日志与队列里出现它不会泄露网络名或口令。</li>
 *   <li><b>序号 1 起</b>，与逐条判词的顺序号同源，便于「第 11 条是哪个候选」对回字典。</li>
 *   <li><b>有界</b>：{@link #MAX_LEN}，防止异常输入把日志撑爆。</li>
 *   <li>全部方法<b>不抛异常</b>：非法输入一律返回 {@code false} / {@code -1} / {@code null}，
 *       调用方必须显式处理「无法解析」而不是被异常打断判定流程。</li>
 * </ul>
 */
public final class AttemptId {

    /** 分隔符：entry 号与 runId 之间 */
    public static final char SEP = '#';

    /** 标识长度上限（含分隔符）。超出即判非法，避免日志/队列被撑爆。 */
    public static final int MAX_LEN = 64;

    private AttemptId() {}

    // ------------------------------------------------------------ 生成

    /**
     * 生成一个新的 runId。
     *
     * @param epochMs     任务启动时刻（毫秒，墙钟）。只用于取秒级精度
     * @param targetSsid  目标 SSID；只取其哈希前 8 位，不进明文
     * @param runOrdinal  同一次进程内第几次任务（0 起），保证同秒启动也不重号
     * @return runId；输入不可用时返回 {@code null}
     */
    public static String newRunId(long epochMs, String targetSsid, int runOrdinal) {
        return newRunId(epochMs, targetSsid, runOrdinal, null);
    }

    /**
     * 生成 runId（**带启动标识**版本）—— 解决「跨重启重号」。
     *
     * <h3>要修的那个缺陷</h3>
     * runId 由「秒级时间 + 目标 SSID 哈希 + 进程内序号」组成。断点续跑队列是
     * **跨重启持久化**的，而重启后同一个秒、同一个 SSID、同一条候选会算出
     * **完全相同的 runId** —— 于是队列里旧任务留下的 attemptId 会与重启后
     * 新生成的标记行**互相匹配**：
     * <pre>
     *   重启前那条「第 11 条候选」的窗口标记还在缓冲/队列里
     *   -> 重启后第 11 条候选读到它，把别人的日志证据算成自己的
     * </pre>
     *
     * <h3>做法</h3>
     * 把 {@code bootId}（{@code /proc/sys/kernel/random/boot_id}，每次启动唯一）
     * 混进哈希输入，而不是拼进可见格式 —— <b>runId 形状完全不变</b>，
     * 所有既有解析（{@link #runOf} / {@link #seqOf} / {@link #wellFormed}）
     * 与标记匹配逻辑一行都不用改。
     *
     * <p>{@code bootId} 为 null / 空时行为与旧版**逐字节一致**（向后兼容，
     * 也让所有既有测试与离线工具不受影响）。
     *
     * <p>边界（必读）：这只保证「不同启动生成的 id 不同」，**不**证明
     * 「同一启动内的 id 一定不同」之外的东西；更不替代底层连接归属。
     *
     * @param bootId 启动标识；null/空 = 退回旧行为
     */
    public static String newRunId(long epochMs, String targetSsid, int runOrdinal,
                                  String bootId) {
        if (targetSsid == null || targetSsid.isEmpty()) return null;
        if (runOrdinal < 0) return null;
        if (epochMs <= 0) return null;
        // C2：**毫秒**级时间戳（原来是 epochMs / 1000 = 秒级）。
        //
        // 为什么要改：秒级时间 + 进程内序号（从 0 起）在同一秒内进程重启会
        // 算出**逐字节相同**的 runId —— 断点续跑队列是跨进程持久化的，
        // 于是旧任务的 attemptId 会与重启后的标记行互相匹配（跨 Attempt 污染）。
        //
        // 唯一性论证（三层，缺一不可，且都不是概率）：
        //   ① 跨开机：bootId 由内核在每次启动时生成 UUID，混进哈希 -> 哈希段不同。
        //   ② 同开机跨进程：毫秒时间戳必然不同。进程被杀到重启完成历经
        //      AMS 回收 / fork / zygote / ART 初始化，实测量级是**几十到几百毫秒**，
        //      不可能落在同一毫秒；即使真落在同一毫秒，runOrdinal 在同进程内
        //      递增、跨进程都从 0 起 —— 此时唯一的保护是 bootId，而它不变。
        //      因此这一层额外要求：**同一次开机内不得在同一毫秒内重启进程**。
        //      这是可观测事实（不是概率），真机上未被观察到，已列入残余风险 R-11。
        //   ③ 同进程多次创建：runOrdinal 递增 -> 序号段不同。
        String t36 = Long.toString(epochMs, 36);
        String seed = (bootId == null || bootId.isEmpty())
                ? targetSsid : targetSsid + "|" + bootId;
        String h8 = ssidHash8(seed);
        if (h8 == null) return null;
        String ord36 = Integer.toString(runOrdinal, 36);
        String runId = t36 + "-" + h8 + "-" + ord36;
        if (runId.length() >= MAX_LEN) return null;
        return wellFormedRunId(runId) ? runId : null;
    }

    /**
     * 拼一条候选的 attemptId。
     *
     * @param seq 字典序号，1 起（与判词里的顺序号同源）
     * @return attemptId；参数非法返回 {@code null}
     */
    public static String of(String runId, int seq) {
        if (!wellFormedRunId(runId)) return null;
        if (seq < 1) return null;
        String id = runId + SEP + seq;
        return id.length() <= MAX_LEN ? id : null;
    }

    // ------------------------------------------------------------ 解析

    /** 取出 attemptId 里的 runId；非法返回 {@code null}。 */
    public static String runOf(String attemptId) {
        if (attemptId == null) return null;
        int i = attemptId.indexOf(SEP);
        if (i <= 0) return null;
        String run = attemptId.substring(0, i);
        return wellFormedRunId(run) ? run : null;
    }

    /** 取出候选序号；非法返回 {@code -1}（**不是 0** —— 0 会被误当成有效值）。 */
    public static int seqOf(String attemptId) {
        if (attemptId == null || attemptId.length() > MAX_LEN) return -1;
        int i = attemptId.indexOf(SEP);
        if (i <= 0 || i != attemptId.lastIndexOf(SEP)) return -1;
        if (runOf(attemptId) == null) return -1;
        String s = attemptId.substring(i + 1);
        if (s.isEmpty() || s.length() > 9) return -1;
        for (int k = 0; k < s.length(); k++) {
            if (!Character.isDigit(s.charAt(k))) return -1;
        }
        try {
            int n = Integer.parseInt(s);
            return n >= 1 ? n : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** attemptId 是否形状合法（可解析出 runId 与 ≥1 的序号）。 */
    public static boolean wellFormed(String attemptId) {
        return runOf(attemptId) != null && seqOf(attemptId) >= 1;
    }

    /** runId 是否形状合法：三段、各段字符集受限、长度有界。 */
    public static boolean wellFormedRunId(String runId) {
        if (runId == null || runId.isEmpty() || runId.length() >= MAX_LEN) return false;
        String[] p = runId.split("-", -1);
        if (p.length != 3) return false;
        if (!isAlnum36(p[0]) || p[0].isEmpty()) return false;      // 毫秒级时间（C2）
        if (p[1].length() != 8 || !isHex(p[1])) return false;      // SSID 哈希前 8
        if (!isAlnum36(p[2]) || p[2].isEmpty()) return false;      // 进程内序号
        return true;
    }

    // ------------------------------------------------------------ 比较

    /** 是否属于同一次任务（同 runId）。非法输入一律 false。 */
    public static boolean sameRun(String a, String b) {
        String ra = runOf(a), rb = runOf(b);
        return ra != null && ra.equals(rb);
    }

    /**
     * 是否严格是同一条候选（字符串完全相等且都合法）。
     *
     * <p><b>语义边界</b>：为真只证明「记为同一条候选」，
     * <b>不</b>证明底层连接归属。见类注释的误用禁令。
     */
    public static boolean exactlyMatches(String a, String b) {
        return wellFormed(a) && a.equals(b);
    }

    /**
     * 防误用哨兵：恒返回 {@code false}。
     *
     * <p>调用方若写 {@code if (AttemptId.attributionProvenByAttemptIdAlone())}，
     * 分支永远不可达 —— 意图是把「用 attemptId 代替归属判定」这件事
     * 变成一行必须显式写出来的错，而不是一个看起来能工作的捷径。
     */
    public static boolean attributionProvenByAttemptIdAlone() {
        return false;
    }

    // ------------------------------------------------------------ 失效与恢复

    /** 恢复凭据：新 run 的 id + 旧 run 的血缘 + 从哪条续跑。 */
    public static final class ResumeTicket {
        /** 本次（新）run 的 id —— 绝不复用旧 runId。 */
        public final String newRunId;
        /** 上一次 run 的 id（首次启动为 {@code null}）；仅供记录血缘，不得再用于新证据。 */
        public final String previousRunId;
        /** 从旧 run 的第几条候选之后续跑。 */
        public final int resumeFromOffset;

        ResumeTicket(String newRunId, String previousRunId, int resumeFromOffset) {
            this.newRunId = newRunId;
            this.previousRunId = previousRunId;
            this.resumeFromOffset = resumeFromOffset;
        }
    }

    /**
     * 进程重启/断点续跑时开一个新的 run。
     *
     * <h3>契约（三条都是硬要求）</h3>
     * <ol>
     *   <li><b>不复用</b>：返回的 {@code newRunId} 必须不同于 {@code previousRunId}。
     *       旧 run 的 attemptId 不得再出现在新 run 的证据里。</li>
     *   <li><b>血缘保留</b>：{@code previousRunId} 原样带出，便于事后追问
     *       「这条结果是不是上一次跑出来的」。</li>
     *   <li><b>游标沿用</b>：{@code resumeFromOffset} 原样带出，
     *       负值归 {@code 0}（不猜、不丢）。</li>
     * </ol>
     *
     * @param previousRunId 旧 runId，可为 {@code null}（首次启动）
     * @param previousOffset 旧 run 已试到第几条
     */
    public static ResumeTicket adoptForResume(String previousRunId, int previousOffset,
                                             long epochMs, String targetSsid, int runOrdinal) {
        String fresh = newRunId(epochMs, targetSsid, runOrdinal);
        if (fresh != null && fresh.equals(previousRunId)) {
            // 同一秒、同 SSID、同 ordinal 会撞号 —— 用 ordinal+1 再取一次。
            // 这是「绝不复用」这条契约的兜底，不是正常路径。
            fresh = newRunId(epochMs, targetSsid, runOrdinal + 1);
        }
        String prev = wellFormedRunId(previousRunId) ? previousRunId : null;
        return new ResumeTicket(fresh, prev, Math.max(0, previousOffset));
    }

    // ------------------------------------------------------------ 内部

    /** SSID 的哈希前 8 位（小写十六进制）。取不到返回 null。 */
    static String ssidHash8(String ssid) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(ssid.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isAlnum36(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z');
            if (!ok) return false;
        }
        return true;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) return false;
        }
        return true;
    }
}
