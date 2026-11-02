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
 *       <td>NETWORK_AUTH_FAIL / TERMINATED_UNKNOWN / TIMEOUT</td>
 *       <td><b>否</b></td><td><b>否</b>（仅「省时」档）</td></tr>
 *   <tr><td>{@link Level#NOT_EXECUTED}（不记）</td>
 *       <td>INCOMPLETE / ABORTED / 通道 ERROR</td>
 *       <td>否</td><td><b>任何档都不跳</b></td></tr>
 * </table>
 *
 * <h3>三条硬约束</h3>
 * <ol>
 *   <li><b>不存明文</b> —— 只存 56 位哈希 + 8 位等级，沿用明文卫生原则。</li>
 *   <li><b>绝不跨目标共享</b> —— 复用 {@link TriedSet#targetKey} 的隔离规则
 *       （BSSID 优先，退化到 SSID 哈希）。</li>
 *   <li><b>损坏即降级为「没有记录」</b> —— 丢了的代价是重跑，不是漏判。</li>
 * </ol>
 *
 * <h3>单调升级</h3>
 * 同一候选多次执行时，只允许**更强**的证据覆盖：
 * {@code CONFIRMED_WRONG} 一旦写入，后来的「未确定」不会把它降级；
 * 反过来，先「未确定」后「已确定错误」会升级。
 *
 * <p>纯逻辑 + 文件 IO，可在 JVM 完整测试（见 {@code AttemptLedgerTest}）。
 */
public final class AttemptLedger {

    /** 单目标上限（8 字节/条，100 万条 = 8 MB）。 */
    public static final int MAX = 1_000_000;

    /** 文件布局版本。加版本位是为了将来能安全识别旧文件。 */
    public static final int FORMAT_VERSION = 1;

    /**
     * 候选在本目标上的执行结论等级。
     *
     * <p><b>数值越小＝证据越强</b>，这样「只允许更强覆盖」就是
     * {@code newLevel < oldLevel} 一个比较。
     *
     * <h3>为什么失败类要分四档，而不是压成一档</h3>
     * 四类「没连上」的证据性并不相同，压成一档会让界面只能给出一句笼统的
     * 「未确定」，也违反了「不得把 TIMEOUT / UNKNOWN / NETWORK_AUTH_FAIL
     * 粗暴视为同一种结果」：
     * <ul>
     *   <li>{@link #NETWORK_TERMINATED} —— 网络确实发生了认证失败（归属不明）；</li>
     *   <li>{@link #TERMINATED_UNKNOWN} —— 连接确认终止，但原因不明；</li>
     *   <li>{@link #TIMEOUT} —— 观察期内一条证据都没拿到；</li>
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
         * 省时：额外跳过「已执行且连接已终止，但未确定」的候选。
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
     * 每条 8 字节：**高 55 位哈希 + 低 8 位等级**（最高位恒为 0）。
     *
     * <h3>两个当场踩到的坑（都由本测试抓出，保留在此以免重犯）</h3>
     * <ol>
     *   <li><b>等级不能放高位</b>：第一版把等级放在 bit 56–63，于是
     *       {@code Arrays.sort} 会**先按等级分组**再排哈希；而二分查找假定
     *       数组按哈希有序 → 5000 条里 2079 条查不到。</li>
     *   <li><b>哈希只能占 55 位，不能占 56 位</b>：{@code Arrays.sort(long[])}
     *       是**有符号**排序。哈希占满 56 位时，bit 55 为 1 的条目
     *       {@code (hash<<8)|rank} 整体为负，被排到数组最前面，
     *       而无符号取值的二分又把它们当大数 → 顺序再次断裂，
     *       5000 条里有 2500 条查不到。截到 55 位后最高位恒为 0，
     *       有符号序与无符号序一致，问题消失。</li>
     * </ol>
     * <p>冲突率 2^-55（100 万条时约 3e-6），可忽略；换算成与 triedSet
     * 相同口径的代价只是少一位。
     */
    private long[] buf;
    private int n;
    private int sortedN;
    private static final int SORT_CHUNK = 256;

    public AttemptLedger() {
        buf = new long[512];
    }

    public int size() { return n; }

    /** 哈希位掩码：**55 位**，保证打包后最高位恒为 0（见类注释的第二个坑）。 */
    static final long HASH_MASK = 0x007FFFFFFFFFFFFFL;

    /** 打包：哈希（55 位）在高位、等级在低位。 */
    static long pack(long hash, Level lv) {
        return ((hash & HASH_MASK) << 8) | ((long) lv.rank & 0xffL);
    }

    static long hashOf(long packed) {
        return (packed >>> 8) & HASH_MASK;
    }

    static Level levelOf(long packed) {
        int r = (int) (packed & 0xffL);
        for (Level lv : PERSISTED) if (lv.rank == r) return lv;
        return null;   // 未知等级（将来加了新档的旧文件）→ 当作没有记录
    }

    /** 会真正出现在磁盘上的等级（{@link Level#NOT_EXECUTED} 只是查询返回值，不落盘）。 */
    static final Level[] PERSISTED = {
            Level.CONFIRMED_WRONG, Level.NETWORK_TERMINATED,
            Level.TERMINATED_UNKNOWN, Level.TIMEOUT, Level.UNKNOWN_ON_TARGET };

    /**
     * 候选哈希（截断到 56 位）。
     *
     * <p>复用 {@link TriedSet#hash} 的 FNV-1a —— 两套账本必须**认得同一条候选**，
     * 所以口径必须完全一致（有测试钉住）。
     *
     * <p>截断到 **55 位**是为了给等级留 8 位、同时让打包结果恒为正
     * （{@code Arrays.sort} 是有符号排序）。冲突率 2^-55，可忽略。
     *
     * <p><b>不做大小写归一</b>：WPA 口令是大小写敏感的，
     * {@code Password1} 与 {@code password1} 是两条不同的候选，归并会漏判。
     */
    static long hash56(String pw) {
        return TriedSet.hash(pw) & HASH_MASK;
    }

    /**
     * 记一条执行结果。**只允许更强的证据覆盖**。
     *
     * @return true 表示确实写入了（新记录，或升级了等级）；false 表示无变化
     */
    public boolean record(String pw, Level lv) {
        if (pw == null || lv == null || !lv.executed) return false;   // 没执行完的不落盘
        long h = hash56(pw);
        int idx = findIndex(h);
        if (idx >= 0) {
            Level old = levelOf(buf[idx]);
            if (old == null || lv.rank < old.rank) {     // 更强才覆盖
                buf[idx] = pack(h, lv);
                return true;
            }
            return false;                                 // 同等或更弱 → 保持不变
        }
        if (n >= MAX) return false;
        if (n == buf.length) {
            int cap = buf.length < 4096 ? buf.length * 2 : buf.length + (buf.length >> 2);
            buf = Arrays.copyOf(buf, Math.min(cap, MAX));
        }
        buf[n++] = pack(h, lv);
        if (n - sortedN > SORT_CHUNK) ensureSorted();
        return true;
    }

    /** 查这条候选在本目标上的执行等级；从未执行返回 {@link Level#NOT_EXECUTED}。 */
    public Level level(String pw) {
        if (pw == null || n == 0) return Level.NOT_EXECUTED;
        long h = hash56(pw);
        int idx = findIndex(h);
        if (idx < 0) return Level.NOT_EXECUTED;
        Level lv = levelOf(buf[idx]);
        return lv == null ? Level.NOT_EXECUTED : lv;
    }

    /** 这条候选在给定策略下是否跳过。 */
    public boolean shouldSkip(String pw, SkipPolicy p) {
        return p != null && p.skips(level(pw));
    }

    /** 按等级统计条数（给 UI 显示「跳过 N 条 / 其中 M 条未确定」）。 */
    public int count(Level lv) {
        int c = 0;
        for (int i = 0; i < n; i++) if (levelOf(buf[i]) == lv) c++;
        return c;
    }

    /**
     * 在这个策略下**会被跳过**的条数 —— 界面必须把它的构成拆开显示。
     *
     * <h3>为什么只数、不合并</h3>
     * 「跳过 120 条」这句话对用户毫无信息量：其中 119 条是已证实错误、
     * 1 条是上次超时，与「120 条全是超时」是完全不同的两件事。
     * 所以界面必须同时给出 {@link #countSkippable} 与 {@link #countUndetermined}
     * 的构成，并在省时档启用前把风险说清。
     */
    public int countSkippable(SkipPolicy p) {
        if (p == null) return 0;
        int c = 0;
        for (int i = 0; i < n; i++) if (p.skips(levelOf(buf[i]))) c++;
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
            Level lv = levelOf(buf[i]);
            if (lv != null && lv.executed && lv != Level.CONFIRMED_WRONG) c++;
        }
        return c;
    }

    /** 先查已排序前缀（二分），再线性扫尾巴。 */
    private int findIndex(long h) {
        ensureSorted();
        int lo = 0, hi = sortedN - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long v = hashOf(buf[mid]);
            if (v == h) return mid;
            if (v < h) lo = mid + 1; else hi = mid - 1;
        }
        for (int i = sortedN; i < n; i++) if (hashOf(buf[i]) == h) return i;
        return -1;
    }

    private void ensureSorted() {
        if (sortedN >= n) return;
        // 先按哈希排前缀，再处理尾巴：整体排序（尾巴很短，代价可接受）
        Arrays.sort(buf, 0, n);
        // 排序会打乱「哈希在前、等级在后」的包装顺序没问题（pack 后低 56 位是哈希，
        // 高 8 位是等级；同哈希不同等级时按等级排序，等级更小（更强）排前面）。
        // 去重：同哈希只保留最强的那条。
        int w = 0;
        for (int i = 0; i < n; i++) {
            if (w > 0 && hashOf(buf[w - 1]) == hashOf(buf[i])) {
                // 已按等级升序（强在前），保留前一条
                continue;
            }
            buf[w++] = buf[i];
        }
        n = w;
        sortedN = n;
    }

    // ---------------------------------------------------------------- 落盘

    /**
     * 读。**任何异常/损坏/版本不符都返回空账本** ——
     * 丢了的代价是重复劳动，不是漏判，所以降级做在内部。
     */
    public static AttemptLedger load(File f) {
        AttemptLedger t = new AttemptLedger();
        if (f == null || !f.isFile()) return t;
        long len = f.length();
        if (len <= 0 || len % 8 != 0 || len / 8 > MAX) return t;
        int cnt = (int) (len / 8);
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] raw = new byte[cnt * 8];
            int read = 0;
            while (read < raw.length) {
                int r = in.read(raw, read, raw.length - read);
                if (r < 0) break;
                read += r;
            }
            if (read != raw.length) return t;      // 截断 → 空账本
            t.buf = new long[Math.max(512, cnt)];
            for (int i = 0; i < cnt; i++) {
                long v = 0;
                for (int b = 0; b < 8; b++) {
                    v |= ((long) (raw[i * 8 + b] & 0xff)) << (8 * b);
                }
                t.buf[i] = v;
            }
            t.n = cnt;
            t.sortedN = 0;      // 文件可能被外部改过 —— 查前补排序
        } catch (Exception e) {
            return new AttemptLedger();
        }
        return t;
    }

    /** 写：排序去重后按 8 字节小端连续落盘（与 triedSet 同一布局）。 */
    public boolean save(File f) {
        if (f == null) return false;
        try {
            ensureSorted();
            File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return false;
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] chunk = new byte[8 * 4096];
                int off = 0;
                for (int i = 0; i < n; i++) {
                    long v = buf[i];
                    int base = (i % 4096) * 8;
                    for (int b = 0; b < 8; b++) chunk[base + b] = (byte) (v >>> (8 * b));
                    if ((i + 1) % 4096 == 0) {
                        out.write(chunk);
                        off = 0;
                    } else {
                        off = base + 8;
                    }
                }
                if (off > 0) out.write(chunk, 0, off);
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                return tmp.renameTo(f);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
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
}
