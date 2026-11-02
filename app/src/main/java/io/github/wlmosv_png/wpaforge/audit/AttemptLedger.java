package io.github.wlmosv_png.wpaforge.audit;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Locale;

/**
 * 某个目标上**候选执行历史**账本（按目标分开存）。
 *
 * <h3>它解决什么</h3>
 * 1.9.32 起 {@code triedSet} 只收 {@code DIRECT}（候选级证伪）—— 这是判定正确性
 * 的硬要求，绝不能松。代价是：在 AT3 不可达的设备上（本机实测 supplicant
 * {@code id} 恒 0 vs Android netId 1573/74/75），所有失败结论都是
 * {@code NETWORK_AUTH_FAIL/NONE} → {@code triedSet} 几乎收不到东西 →
 * **换字典时重叠候选被整段重跑**（用户实报）。
 *
 * <p>本类**不参与永久排除**。它只回答两个问题：
 * <ol>
 *   <li>这条候选在**本目标**上被执行过吗？</li>
 *   <li>当时的结论有多强（已确定错误 / 已终止但未确定）？</li>
 * </ol>
 * 「要不要跳过」由 {@link SkipPolicy} 决定，而**默认档不跳任何未确定候选**。
 *
 * <h3>三层语义（务必分清）</h3>
 * <table>
 *   <tr><th>层</th><th>来源</th><th>写 triedSet</th><th>默认跳过</th></tr>
 *   <tr><td>{@link Level#CONFIRMED_WRONG}</td><td>AUTH_FAIL + DIRECT</td>
 *       <td>是（由 {@code triedSet} 负责）</td><td>是</td></tr>
 *   <tr><td>{@link Level#NETWORK_TERMINATED} / {@link Level#TERMINATED_UNKNOWN}
 *       / {@link Level#TIMEOUT}</td>
 *       <td>已确认终止的失败类结论</td>
 *       <td><b>否</b></td><td><b>否</b>（仅「省时」档）</td></tr>
 *   <tr><td>{@link Level#UNKNOWN_ON_TARGET}（记但不跳）</td>
 *       <td>UNKNOWN（当时仍连着目标）</td>
 *       <td>否</td><td><b>任何档都不跳</b>（连接没终止）</td></tr>
 *   <tr><td>不记</td><td>INCOMPLETE / ABORTED / 通道 ERROR</td>
 *       <td>否</td><td><b>任何档都不跳</b></td></tr>
 * </table>
 *
 * <h3>存储格式 v2（2026-11-02 审计后重构）</h3>
 * <pre>
 *   头 8 字节：magic "WPLG"(4) + version=2(4)
 *   之后每条 16 字节：身份(8, 见 CandidateId) + 等级(8)
 * </pre>
 * <b>为什么不再打包进一个 long</b>：v1 把「56 位哈希 + 8 位等级」压进一个 long，
 * 结果哈希必须截断到 55 位（保住有符号排序可用）。审计判定这等于拿候选身份
 * 的可靠性去换 8 个字节，不能接受。v2 让**排序键就是身份本身**，等级独立存放，
 * 且换用 SHA-256 前 8 字节（与 {@code triedSet} 统一口径，见 {@link CandidateId}）。
 *
 * <h3>旧数据</h3>
 * 任何非 v2 的文件（含 v1）在读入时被判为**空账本** —— 代价是重跑，
 * 不是漏判。这是审计明确要求的安全降级方向。
 *
 * <p>纯逻辑 + 文件 IO，可在 JVM 完整测试（见 {@code AttemptLedgerTest}）。
 */
public final class AttemptLedger {

    /** 单目标上限（16 字节/条，100 万条 = 16 MB）。 */
    public static final int MAX = 1_000_000;

    /** 文件布局版本（v2 起：身份与等级分开存，身份改用 SHA-256 摘要）。 */
    public static final int FORMAT_VERSION = 2;

    /** 文件魔数（"WPLG"）。旧文件没有它 → 判为旧格式 → 空账本。 */
    static final int MAGIC = 0x57504C47;

    /** 头部字节数。 */
    static final int HEADER_BYTES = 8;

    /** 每条记录字节数：身份 8 + 等级 8。 */
    static final int RECORD_BYTES = 16;

    /**
     * 候选在本目标上的执行结论等级。
     *
     * <p><b>数值越小＝证据越强</b>，这样「只允许更强覆盖」就是
     * {@code newLevel.rank < oldLevel.rank} 一个比较。
     *
     * <h3>为什么失败类要分四档，而不是压成一档</h3>
     * 四类「没连上」的证据性并不相同，压成一档会让界面只能给出一句笼统的
     * 「未确定」，也违反了「不得把 TIMEOUT / UNKNOWN / NETWORK_AUTH_FAIL
     * 粗暴视为同一种结果」：
     * <ul>
     *   <li>{@link #NETWORK_TERMINATED} —— 网络确实发生了认证失败（归属不明）；</li>
     *   <li>{@link #TERMINATED_UNKNOWN} —— 连接确认终止，但原因不明；</li>
     *   <li>{@link #TIMEOUT} —— 连接已终止且观察期内没拿到任何证据；</li>
     *   <li>{@link #UNKNOWN_ON_TARGET} —— 当时**仍连着目标**，无法归因。</li>
     * </ul>
     * 只有「省时」档才把前三档一并纳入跳过范围；{@link #UNKNOWN_ON_TARGET}
     * **永远不跳** —— 它连「已终止」都不成立。
     */
    public enum Level {
        /** 从未在本目标上执行过（不落盘，只是查询的返回值）。 */
        NOT_EXECUTED(9, false, false),
        /**
         * 已确定错误：AT3 的直接否认，即 {@code AUTH_FAIL + DIRECT}。
         * 它本来就会被 {@code triedSet} 永久排除，记在这里是为了让
         * 「跳过统计」与「跳过策略」有统一口径。
         */
        CONFIRMED_WRONG(1, true, true),
        /** 已执行、连接终止、网络确实发生过认证失败（归属到不了本条候选）。 */
        NETWORK_TERMINATED(2, true, true),
        /** 已执行、连接确认终止，但终止原因无法归因。 */
        TERMINATED_UNKNOWN(3, true, true),
        /** 已执行、连接已终止且无结论 —— 观察期内没拿到任何证据。 */
        TIMEOUT(4, true, true),
        /**
         * 已执行，但当时**仍连着目标**、无法确认是不是这条候选造成的。
         *
         * <p>与前三档的关键差别：连接**没有终止**。「省时」档跳过的是
         * 「已终止但未确定」的候选 —— 这一档不符合条件，任何档位都不跳。
         */
        UNKNOWN_ON_TARGET(5, true, false);

        /** 强度权重（越小越强）。 */
        public final int rank;
        /** 是否「已执行完成」（只有 NOT_EXECUTED 为假）。 */
        public final boolean executed;
        /** 「省时」档是否跳过它。「保守」档只跳 CONFIRMED_WRONG。 */
        public final boolean skippableInSaveTime;

        Level(int r, boolean ex, boolean sk) {
            this.rank = r; this.executed = ex; this.skippableInSaveTime = sk;
        }

        /**
         * 把一条**真实执行完成**的判定结论映射成账本等级。
         *
         * <h3>谁不记（返回 {@code null}）</h3>
         * {@code INCOMPLETE}（连接还没结束，只是我们等不下去了）、
         * {@code ABORTED}（用户停止）、{@code ERROR}（通道故障）、
         * {@code TARGET_GONE}（这条本来就没意义）—— 它们**都没跑完**，
         * 记进去等于把没验证过的候选标成「下次别试」，任何档位都不能这么做。
         *
         * <p>{@code SUCCESS} 也不记：命中会立即终止整个计划，它不属于历史。
         *
         * <p>{@code AUTH_FAIL} 必须同时是 {@link EvidenceGrade#DIRECT} 才算
         * 已确定错误 —— 这条口径与 {@code triedSet} 完全一致（同源判定）。
         *
         * <p><b>上游保证</b>：2026-11-02 起 {@code Adjudicator} 规则 3/4 要求
         * 生命周期已确认终止才会产出 {@code AUTH_FAIL/NETWORK_AUTH_FAIL}，
         * 规则 9 的 {@code UNKNOWN} 分支产出 {@code INCOMPLETE} 而非
         * {@code TIMEOUT}。因此本方法不会收到「请求尚未结束」的失败类结论。
         */
        public static Level classify(CrackEngine.Verdict v, EvidenceGrade.Level grade) {
            if (v == null) return null;
            switch (v) {
                case SUCCESS:
                    return null;                       // 命中 → 终止计划，不记历史
                case AUTH_FAIL:
                    return EvidenceGrade.persistable(grade)
                            ? CONFIRMED_WRONG             // 归因到本条候选的直接否认
                            : TERMINATED_UNKNOWN;         // 有失败但归不到本条 → 未确定
                case NETWORK_AUTH_FAIL:
                    return NETWORK_TERMINATED;
                case TERMINATED_UNKNOWN:
                    return TERMINATED_UNKNOWN;
                case TIMEOUT:
                    return TIMEOUT;
                case UNKNOWN:
                    return UNKNOWN_ON_TARGET;
                default:
                    return null;                       // 没跑完 / 无意义 → 不记
            }
        }
    }

    /**
     * 跨字典去重策略。
     *
     * <h3>为什么只有两档</h3>
     * 设计稿里曾写三档，但落下来第三档无处可去：{@code INCOMPLETE / ABORTED /
     * 通道 ERROR} 在任何档位都禁止跳过（它们严格说「没跑完」，根本不会进账本），
     * 而「已执行但未确定」的四个档位（NETWORK_TERMINATED / TERMINATED_UNKNOWN /
     * TIMEOUT / UNKNOWN_ON_TARGET）本来就是同一个决策边界。再多一档只是把
     * 同一个集合换个名字，不如老实给两档。
     */
    public enum SkipPolicy {
        /**
         * **默认**：不跳过任何未确定候选。
         *
         * <p>行为与 1.9.38 完全一致（只靠 {@code triedSet} 跳已确定错误），
         * 因此**不存在「默认策略导致漏判」**这种可能。
         */
        CONSERVATIVE(0),
        /**
         * 省时：额外跳过「已执行、且连接已确认终止、但未确定」的候选。
         *
         * <p><b>用户必须显式开启</b>，且开启前要看到跳过条数、未确定条数与风险提示。
         * 风险：若上轮遇到 AP 退避／信号差等**环境异常**，其中可能有正确密码
         * —— 这一次不跑，就等于把命中机会推后到用户主动重跑。
         */
        SAVE_TIME(1);

        public final int id;
        SkipPolicy(int id) { this.id = id; }

        public static SkipPolicy of(int id) {
            return id == 1 ? SAVE_TIME : CONSERVATIVE;
        }

        /** 这条等级在这个策略下是否跳过。**纯函数**，可穷举。 */
        public boolean skips(Level lv) {
            if (lv == null || !lv.executed) return false;   // 没执行完 → 绝不跳
            if (lv == Level.CONFIRMED_WRONG) return true;   // 两档都跳（triedSet 本来也管）
            return this == SAVE_TIME && lv.skippableInSaveTime;
        }
    }

    // ---------------------------------------------------------------- 存储

    /**
     * 成对存放：{@code buf[2i]} = 身份，{@code buf[2i+1]} = 等级 rank。
     *
     * <p>拆开存是 v2 的核心：排序键**只有身份**（同身份时再比等级），
     * 不再有任何附加位去挤压身份位宽。
     */
    private long[] buf;
    private int n;
    private int sortedN;
    private static final int SORT_CHUNK = 256;

    public AttemptLedger() {
        buf = new long[2 * 512];
    }

    public int size() { return n; }

    /** 候选身份（唯一的身份来源，见 {@link CandidateId}）。 */
    public static long idOf(String pw) {
        return CandidateId.of(pw);
    }

    /** 会真正出现在磁盘上的等级（{@link Level#NOT_EXECUTED} 只是查询返回值，不落盘）。 */
    static final Level[] PERSISTED = {
            Level.CONFIRMED_WRONG, Level.NETWORK_TERMINATED,
            Level.TERMINATED_UNKNOWN, Level.TIMEOUT, Level.UNKNOWN_ON_TARGET };

    /** 第 i 条的身份（测试与排查用）。 */
    long idAt(int i) { return buf[2 * i]; }

    /** 第 i 条的等级（测试与排查用）；未知 rank 返回 null。 */
    Level levelAt(int i) {
        int r = (int) buf[2 * i + 1];
        for (Level lv : PERSISTED) if (lv.rank == r) return lv;
        return null;
    }

    /**
     * 记一条执行结果。**只允许更强的证据覆盖**。
     *
     * @return true 表示确实写入了（新记录，或升级了等级）；false 表示无变化
     */
    /** 自上次成功 save 以来是否有新记录（避免无变化时重复整表落盘）。 */
    private boolean dirty = false;

    /** 是否需要落盘（与 TriedSet.isDirty() 同语义）。 */
    public boolean isDirty() { return dirty; }

    public boolean record(String pw, Level lv) {
        if (pw == null || lv == null || !lv.executed) return false;   // 没执行完的不落盘
        long id = idOf(pw);
        int idx = findIndex(id);
        if (idx >= 0) {
            Level old = levelAt(idx);
            if (old == null || lv.rank < old.rank) {     // 更强才覆盖
                buf[2 * idx] = id;
                buf[2 * idx + 1] = lv.rank;
                dirty = true;
                return true;
            }
            return false;                                 // 同等或更弱 → 保持不变
        }
        if (n >= MAX) return false;
        if (2 * n + 2 > buf.length) {
            int cap = buf.length < 4096 ? buf.length * 2
                    : buf.length + (buf.length >> 2);
            buf = Arrays.copyOf(buf, Math.min(cap, 2 * MAX));
        }
        buf[2 * n] = id;
        buf[2 * n + 1] = lv.rank;
        n++;
        dirty = true;
        if (n - sortedN > SORT_CHUNK) ensureSorted();
        return true;
    }

    /** 查这条候选在本目标上的执行等级；从未执行返回 {@link Level#NOT_EXECUTED}。 */
    public Level level(String pw) {
        if (pw == null || n == 0) return Level.NOT_EXECUTED;
        long id = idOf(pw);
        int idx = findIndex(id);
        if (idx < 0) return Level.NOT_EXECUTED;
        Level lv = levelAt(idx);
        return lv == null ? Level.NOT_EXECUTED : lv;
    }

    /** 这条候选在给定策略下是否跳过。 */
    public boolean shouldSkip(String pw, SkipPolicy p) {
        return p != null && p.skips(level(pw));
    }

    /** 按等级统计条数。 */
    public int count(Level lv) {
        int c = 0;
        for (int i = 0; i < n; i++) if (levelAt(i) == lv) c++;
        return c;
    }

    /**
     * 在这个策略下**会被跳过**的条数 —— 界面必须把它的构成拆开显示。
     *
     * <h3>为什么只数、不合并</h3>
     * 「跳过 120 条」这句话对用户毫无信息量：其中 119 条是已证实错误、
     * 1 条是上次超时，与「120 条全是超时」是完全不同的两件事。
     */
    public int countSkippable(SkipPolicy p) {
        if (p == null) return 0;
        int c = 0;
        for (int i = 0; i < n; i++) if (p.skips(levelAt(i))) c++;
        return c;
    }

    /**
     * 「已执行，但没有候选级证据」的条数（排除 {@link Level#CONFIRMED_WRONG}）。
     *
     * <p>这是省时档风险的量化：这些候选没有一条能证明密码是错的，
     * 若上一轮遇到 AP 退避 / 信号差，正确密码可能就在里面。
     */
    public int countUndetermined() {
        int c = 0;
        for (int i = 0; i < n; i++) {
            Level lv = levelAt(i);
            if (lv != null && lv.executed && lv != Level.CONFIRMED_WRONG) c++;
        }
        return c;
    }

    // ---------------------------------------------------------------- 排序

    /** 先查已排序前缀（二分），再线性扫尾巴。 */
    private int findIndex(long id) {
        ensureSorted();
        int lo = 0, hi = sortedN - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long v = buf[2 * mid];
            if (v == id) return mid;
            if (v < id) lo = mid + 1; else hi = mid - 1;
        }
        for (int i = sortedN; i < n; i++) if (buf[2 * i] == id) return i;
        return -1;
    }

    private void ensureSorted() {
        if (sortedN >= n) return;
        quickSort(0, n - 1);
        // 同身份只保留最强的那条（排序已保证 rank 升序，强在前）
        int w = 0;
        for (int i = 0; i < n; i++) {
            if (w > 0 && buf[2 * (w - 1)] == buf[2 * i]) continue;
            if (w != i) {
                buf[2 * w] = buf[2 * i];
                buf[2 * w + 1] = buf[2 * i + 1];
            }
            w++;
        }
        n = w;
        sortedN = n;
    }

    /** 按 (身份, 等级) 升序排。有符号比较 —— 与二分查找口径一致。 */
    private void quickSort(int lo, int hi) {
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            long ph = buf[2 * mid], pr = buf[2 * mid + 1];
            int i = lo, j = hi;
            while (i <= j) {
                while (less(i, ph, pr)) i++;
                while (less(ph, pr, j)) j--;
                if (i <= j) { swap(i, j); i++; j--; }
            }
            if (j - lo < hi - i) { quickSort(lo, j); lo = i; }
            else { quickSort(i, hi); hi = j; }
        }
    }

    /** 第 i 条 < (h, r)。 */
    private boolean less(int i, long h, long r) {
        long a = buf[2 * i], b = buf[2 * i + 1];
        return a < h || (a == h && b < r);
    }

    /** (h, r) < 第 j 条。 */
    private boolean less(long h, long r, int j) {
        long a = buf[2 * j], b = buf[2 * j + 1];
        return h < a || (h == a && r < b);
    }

    private void swap(int a, int b) {
        long h = buf[2 * a], r = buf[2 * a + 1];
        buf[2 * a] = buf[2 * b];
        buf[2 * a + 1] = buf[2 * b + 1];
        buf[2 * b] = h;
        buf[2 * b + 1] = r;
    }

    // ---------------------------------------------------------------- 落盘

    /**
     * 读。**任何异常 / 损坏 / 版本不符 / 旧格式都返回空账本** ——
     * 丢了的代价是重复劳动，不是漏判，所以降级做在内部。
     *
     * <p>v1（8 字节/条、无头部）会被这里判空。审计要求的方向是
     * 「旧格式不能无损迁移时，安全降级为不跳过，允许重新执行」。
     */
    public static AttemptLedger load(File f) {
        AttemptLedger t = new AttemptLedger();
        if (f == null || !f.isFile()) return t;
        long len = f.length();
        if (len <= HEADER_BYTES) return t;
        long body = len - HEADER_BYTES;
        if (body % RECORD_BYTES != 0) return t;
        int cnt = (int) (body / RECORD_BYTES);
        if (cnt <= 0 || cnt > MAX) return t;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] raw = new byte[(int) len];
            int read = 0;
            while (read < raw.length) {
                int r = in.read(raw, read, raw.length - read);
                if (r < 0) break;
                read += r;
            }
            if (read != raw.length) return t;      // 截断 → 空账本

            long head = readLong(raw, 0);
            if ((int) (head >>> 32) != MAGIC) return t;      // 旧格式/非本类文件
            if ((int) (head & 0xffffffffL) != FORMAT_VERSION) return t;

            t.buf = new long[Math.max(512, cnt) * 2];
            for (int i = 0; i < cnt; i++) {
                t.buf[2 * i] = readLong(raw, HEADER_BYTES + i * RECORD_BYTES);
                t.buf[2 * i + 1] = readLong(raw, HEADER_BYTES + i * RECORD_BYTES + 8);
            }
            t.n = cnt;
            t.sortedN = 0;      // 文件可能被外部改过 —— 查前补排序
        } catch (Exception e) {
            return new AttemptLedger();
        }
        return t;
    }

    /** 写：头部 + 每条 16 字节（小端）连续落盘。 */
    public boolean save(File f) {
        if (f == null) return false;
        try {
            ensureSorted();
            File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return false;
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] head = new byte[HEADER_BYTES];
                writeLong(head, 0, ((long) MAGIC << 32) | (FORMAT_VERSION & 0xffffffffL));
                out.write(head);
                byte[] chunk = new byte[RECORD_BYTES * 1024];
                int off = 0;
                for (int i = 0; i < n; i++) {
                    int base = (i % 1024) * RECORD_BYTES;
                    writeLong(chunk, base, buf[2 * i]);
                    writeLong(chunk, base + 8, buf[2 * i + 1]);
                    if ((i + 1) % 1024 == 0) {
                        out.write(chunk);
                        off = 0;
                    } else {
                        off = base + RECORD_BYTES;
                    }
                }
                if (off > 0) out.write(chunk, 0, off);
            }
            // 原子替换：**绝不先删目标**（旧写法先删后改名，失败即丢账本）。
            if (!AtomicFileOp.moveAtomic(tmp, f)) {
                tmp.delete();
                return false;
            }
            dirty = false;          // 只有真的落盘成功才清
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static long readLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v |= ((long) (b[off + i] & 0xff)) << (8 * i);
        return v;
    }

    private static void writeLong(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (8 * i));
    }

    // ---------------------------------------------------------------- 路径

    /** 账本文件名（纯函数，可离线测）：与 triedSet 的 {@code .tried} 分开，同名不同后缀。 */
    public static String fileNameOf(String targetKey) {
        return (targetKey == null ? "unknown" : targetKey) + ".ledger";
    }

    /** 账本文件：按**目标**隔离，规则与 triedSet 完全一致（BSSID 优先）。 */
    public static File fileFor(Context ctx, String targetKey) {
        if (ctx == null) return new File(fileNameOf(targetKey));
        return new File(new File(ctx.getFilesDir(), "ledger"), fileNameOf(targetKey));
    }

    public static File dirOf(Context ctx) {
        return new File(ctx.getFilesDir(), "ledger");
    }

    /** 删除某目标的账本（用户手动清空）。 */
    public static boolean deleteFile(Context ctx, String targetKey) {
        if (ctx == null || targetKey == null || targetKey.isEmpty()) return false;
        File f = fileFor(ctx, targetKey);
        return !f.exists() || f.delete();
    }

    /** 一行摘要（日志用，不含口令）。 */
    public String summary() {
        return String.format(Locale.ROOT, "账本 %d 条（已确定错误 %d · 未确定 %d）",
                n, count(Level.CONFIRMED_WRONG), countUndetermined());
    }
}
