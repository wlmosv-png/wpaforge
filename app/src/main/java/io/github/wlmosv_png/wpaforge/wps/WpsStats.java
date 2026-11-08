package io.github.wlmosv_png.wpaforge.wps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * WPS 专项统计：分类口径 + 耗时分布 + 可落盘 + 可与台账对账。
 *
 * <h3>为什么要独立一份「分类」</h3>
 * UI 与统计必须**共用同一套结果分类**，否则同一批结果在两处显示成两种东西。
 * 三条底线（本项目历史上的假阳性都出在这一层）：
 * <ul>
 *   <li>{@code UNKNOWN} 与 {@code TIMEOUT} **不得**被并进「成功」或「失败」——
 *       它们是各自独立的分类，占比分开算。</li>
 *   <li>不认识的串**一律** {@link #C_INSUFFICIENT}，绝不猜（宁可说"证据不足"）。</li>
 *   <li>{@code SKIPPED_INVALID}（本地校验位不合法直接跳过）**不是对 AP 的结果**，
 *       不计入任何分类，只累加「本地跳过」计数。</li>
 * </ul>
 *
 * <h3>status → 分类映射（{@link #classify(String)}，硬规则，测试逐条断言）</h3>
 * <pre>
 *   SUCCESS                                   → CONFIRMED        （终局：拿到口令）
 *   SUCCESS_UNVERIFIED                        → INSUFFICIENT     （疑似，证据不齐）
 *   CMD_FAIL / FAIL*                          → CMD_REFUSED      （命令被 supplicant 拒）
 *   NO_RESPONSE                               → NO_RESPONSE      （含 AP 不理）
 *   REJECTED / PIN_REJECTED / NO_ASSOC        → REJECTED         （明确拒绝）
 *   HALF_OK                                   → HALF_EVIDENCE    （前半部分证据 M3）
 *   TIMEOUT                                   → TIMEOUT
 *   LINK_LOST / ERROR                         → CHANNEL_ERROR    （通道异常）
 *   BUDGET / 空 / null / 其它                  → INSUFFICIENT     （绝不猜）
 *   SKIPPED_INVALID                           → 不进分类，只累加 skipped
 * </pre>
 *
 * <p>「锁定风险」不是 supplicant 的 status（它是我们自己观察到的 AP 行为），
 * 因此**不能**从 {@link #classify(String)} 到达：只能走 {@link #noteLockRisk(String)}
 * 或显式 {@link #addClass(String, long)}({@link #C_LOCK_RISK})。锁定是**独立轴**，
 * 不计入 {@link #total()}（它不是一次 PIN 尝试的结果），但在 summary/detail 里单列。
 *
 * <h3>耗时口径</h3>
 * {@link #avgMs()} / {@link #p50()} / {@link #p90()} 用的是**同一份真实样本窗口**
 * （{@link #MAX_SAMPLES} 个，超出后滑窗丢最旧，并把 {@code truncatedSamples} 置 1）。
 * {@code elapsedMs <= 0} 视为「没有测量」，不进样本（不是真实耗时）。
 * 样本不足 {@link #MIN_SAMPLES_FOR_PERCENTILE} 个时 {@link #p50()}/{@link #p90()} 返回 {@code -1}
 * ——**不许用一个样本编出 P90**。
 *
 * <h3>「暂无数据」</h3>
 * 没有真实数据的指标一律返回哨兵 {@code -1}（{@link #avgMs()} / {@link #p50()} /
 * {@link #p90()} / {@link #ratioOf(String)}），由调用方渲染成「暂无数据」；
 * {@link #summary(boolean)} / {@link #detail(boolean)} 自己会把「暂无数据」写出来。
 *
 * <p>纯逻辑类：只依赖 {@code java.*} 与同包的 {@link WpsHelper#enc}/{@link WpsHelper#dec}。
 * 不碰 Android API，可离线测试。
 */
public final class WpsStats {

    /** 落盘格式版本（= {@link #encode()} 首行的 {@code v=}）。 */
    public static final int SCHEMA = 1;

    // ---------------- 统一结果分类（UI 与统计共用） ----------------
    // 注意：不得把 UNKNOWN / TIMEOUT 合并进成功或失败 —— 各自独立占一行。

    /** 命令被 supplicant 拒绝。 */
    public static final String C_CMD_REFUSED   = "CMD_REFUSED";
    /** 无响应（含 AP 不理）。 */
    public static final String C_NO_RESPONSE   = "NO_RESPONSE";
    /** 观察到协议交互（km=WPS 会话 / M2D）。 */
    public static final String C_PROTOCOL      = "PROTOCOL";
    /** 前半部分证据（M3 / HALF_OK）。 */
    public static final String C_HALF_EVIDENCE = "HALF_EVIDENCE";
    /** 明确拒绝（REJECTED / PIN_REJECTED / NO_ASSOC）。 */
    public static final String C_REJECTED      = "REJECTED";
    /** 超时。 */
    public static final String C_TIMEOUT       = "TIMEOUT";
    /** 通道异常（LINK_LOST / ERROR）。 */
    public static final String C_CHANNEL_ERROR = "CHANNEL_ERROR";
    /** 锁定风险（独立轴，不进 total）。 */
    public static final String C_LOCK_RISK     = "LOCK_RISK";
    /** 证据不足（SUCCESS_UNVERIFIED / 未知状态）。 */
    public static final String C_INSUFFICIENT  = "INSUFFICIENT";
    /** 已确认终局结果（SUCCESS，或证据完整的命中）。 */
    public static final String C_CONFIRMED     = "CONFIRMED";

    /** 本地跳过（校验位不合法）——**不是**对 AP 的结果，不计入任何分类。 */
    public static final String ST_SKIPPED_INVALID = "SKIPPED_INVALID";

    /**
     * 计入分类的顺序 = {@link #detail(boolean)} 的行顺序（固定下来，UI 才能对齐）。
     * 10 行，含 {@link #C_LOCK_RISK}。
     */
    private static final String[] ORDER = {
            C_CONFIRMED,
            C_PROTOCOL,
            C_HALF_EVIDENCE,
            C_REJECTED,
            C_CMD_REFUSED,
            C_NO_RESPONSE,
            C_TIMEOUT,
            C_CHANNEL_ERROR,
            C_LOCK_RISK,
            C_INSUFFICIENT,
    };

    /** 锁定轴在 {@link #ORDER} 里的下标（依赖声明顺序，改动 ORDER 会自动跟随）。 */
    private static final int LOCK_IDX = idxOf(C_LOCK_RISK);

    /** 耗时样本上限（超出按滑窗丢最旧的样本）。 */
    public static final int MAX_SAMPLES = 5000;

    /** 少于这么多样本就不给分位数（不许编）。 */
    public static final int MIN_SAMPLES_FOR_PERCENTILE = 5;

    // ---------------- 落盘格式 ----------------

    private static final String HEADER = "WPS_STATS";

    /** 最近一次 {@link #decode(String)} 的失败原因；成功时为空串（永不为 null）。 */
    private static volatile String lastErr = "";

    // ---------------- 状态 ----------------

    /** 各分类计数（含 {@link #C_LOCK_RISK} 槽位）。 */
    private final int[] cnt = new int[ORDER.length];

    /** 计入分类的结果总数（**不含** skipped、**不含** lock）。 */
    private int total;

    /** 本地跳过计数。 */
    private int skipped;

    /** 耗时样本环形缓冲（滑窗）。 */
    private final long[] ring = new long[MAX_SAMPLES];
    /** 窗口内样本数。 */
    private int n;
    /** 下一个写入位置（保证续写时挤掉的是最旧的样本）。 */
    private int next;
    /** 窗口内样本和。 */
    private long sum;
    /** 已被挤出的样本数。 */
    private long dropped;
    /** 是否发生过封顶丢弃（truncatedSamples）。 */
    private boolean truncated;

    /** 最近一次锁定风险的说明（可空串）。 */
    private String lockWhy = "";

    public WpsStats() {
    }

    // ---------------- 分类 ----------------

    /** 全部分类（顺序与 {@link #detail(boolean)} 一致）。 */
    public static List<String> classes() {
        return Collections.unmodifiableList(java.util.Arrays.asList(ORDER.clone()));
    }

    /**
     * helper 的 status（或任意结果串）→ 统一分类。
     *
     * <p>只做 {@code trim()}，**不做**大小写折叠：小写 {@code "success"} 不是 helper 的
     * status（helper 一律大写），归 {@link #C_INSUFFICIENT}；宁可少记一次成功，
     * 也不把来路不明的串算成成功。
     *
     * <p>额外接受一组**协议证据**串（helper 的 logKind / 会话标记）→ {@link #C_PROTOCOL}：
     * {@code PROTOCOL}、{@code M2D}、{@code M2D_ONLY}、{@code M2D_THEN_MORE}、
     * {@code M2D_STALL}、{@code WPS_SESSION}。这是为了让「观察到协议交互」这个分类
     * 也能由 status 触达；仅 EAP 帧（{@code EAP_ONLY}/{@code EAP_STALL}）**不算**协议交互。
     */
    public static String classify(String status) {
        if (status == null) return C_INSUFFICIENT;
        String s = status.trim();
        if (s.isEmpty()) return C_INSUFFICIENT;

        // —— 硬规则：helper 的 status ——
        if ("SUCCESS".equals(s)) return C_CONFIRMED;
        if ("SUCCESS_UNVERIFIED".equals(s)) return C_INSUFFICIENT;
        if ("CMD_FAIL".equals(s)) return C_CMD_REFUSED;
        if ("NO_RESPONSE".equals(s)) return C_NO_RESPONSE;
        if ("REJECTED".equals(s)) return C_REJECTED;
        if ("PIN_REJECTED".equals(s)) return C_REJECTED;
        if ("NO_ASSOC".equals(s)) return C_REJECTED;
        if ("HALF_OK".equals(s)) return C_HALF_EVIDENCE;
        if ("TIMEOUT".equals(s)) return C_TIMEOUT;
        if ("LINK_LOST".equals(s)) return C_CHANNEL_ERROR;
        if ("ERROR".equals(s)) return C_CHANNEL_ERROR;
        if ("BUDGET".equals(s)) return C_INSUFFICIENT;
        // FAIL*：包含裸 FAIL 与 FAIL_xxx 之类的变体，都归「命令被拒」。
        if (s.startsWith("FAIL")) return C_CMD_REFUSED;

        // —— 协议证据（见方法注释；不影响上面任何一条）——
        if ("PROTOCOL".equals(s)) return C_PROTOCOL;
        if ("WPS_SESSION".equals(s)) return C_PROTOCOL;
        if ("M2D".equals(s)) return C_PROTOCOL;
        if ("M2D_ONLY".equals(s)) return C_PROTOCOL;
        if ("M2D_THEN_MORE".equals(s)) return C_PROTOCOL;
        if ("M2D_STALL".equals(s)) return C_PROTOCOL;

        // SKIPPED_INVALID 在这里没有分类（它由 add() 单独计 skipped）；
        // LOCK_RISK 也不是 status（只能走 noteLockRisk）。
        return C_INSUFFICIENT;
    }

    /** 分类的中文/英文标签（未知分类原样返回）。 */
    public static String label(String cls, boolean zh) {
        String c = norm(cls);
        if (zh) {
            if (C_CONFIRMED.equals(c)) return "已确认（CONFIRMED）";
            if (C_PROTOCOL.equals(c)) return "协议交互（PROTOCOL）";
            if (C_HALF_EVIDENCE.equals(c)) return "半证据（HALF_EVIDENCE）";
            if (C_REJECTED.equals(c)) return "明确拒绝（REJECTED）";
            if (C_CMD_REFUSED.equals(c)) return "命令被拒（CMD_REFUSED）";
            if (C_NO_RESPONSE.equals(c)) return "无响应（NO_RESPONSE）";
            if (C_TIMEOUT.equals(c)) return "超时（TIMEOUT）";
            if (C_CHANNEL_ERROR.equals(c)) return "通道异常（CHANNEL_ERROR）";
            if (C_LOCK_RISK.equals(c)) return "锁定风险（LOCK_RISK）";
            if (C_INSUFFICIENT.equals(c)) return "证据不足（INSUFFICIENT）";
        } else {
            if (C_CONFIRMED.equals(c)) return "confirmed (CONFIRMED)";
            if (C_PROTOCOL.equals(c)) return "protocol (PROTOCOL)";
            if (C_HALF_EVIDENCE.equals(c)) return "half evidence (HALF_EVIDENCE)";
            if (C_REJECTED.equals(c)) return "rejected (REJECTED)";
            if (C_CMD_REFUSED.equals(c)) return "command refused (CMD_REFUSED)";
            if (C_NO_RESPONSE.equals(c)) return "no response (NO_RESPONSE)";
            if (C_TIMEOUT.equals(c)) return "timeout (TIMEOUT)";
            if (C_CHANNEL_ERROR.equals(c)) return "channel error (CHANNEL_ERROR)";
            if (C_LOCK_RISK.equals(c)) return "lock risk (LOCK_RISK)";
            if (C_INSUFFICIENT.equals(c)) return "insufficient (INSUFFICIENT)";
        }
        return c.isEmpty() ? (zh ? "未知" : "unknown") : c;
    }

    // ---------------- 累加 ----------------

    /**
     * 按 status 记一条结果（线程安全）。
     *
     * <p>{@code SKIPPED_INVALID} 只累加 skipped（不进分类、不进样本）；
     * {@code LOCK_RISK} 不是 status → 归 {@link #C_INSUFFICIENT}。
     */
    public synchronized void add(String status, long elapsedMs) {
        String s = status == null ? "" : status.trim();
        if (ST_SKIPPED_INVALID.equals(s)) {
            skipped++;
            return;
        }
        addClass(classify(s), elapsedMs);
    }

    /**
     * 按分类记一条结果（线程安全）。
     *
     * <p>{@code cls} 为空 / 未知 → 归 {@link #C_INSUFFICIENT}（绝不新建分类）；
     * {@link #C_LOCK_RISK} → 只累加锁定轴（不进 {@link #total()}、不进耗时样本，
     * 它不带耗时）；{@link #ST_SKIPPED_INVALID} → 只累加 skipped。
     */
    public synchronized void addClass(String cls, long elapsedMs) {
        String c = norm(cls);
        if (ST_SKIPPED_INVALID.equals(c)) {
            skipped++;
            return;
        }
        int i = idxOf(c);
        if (i < 0) i = idxOf(C_INSUFFICIENT);
        cnt[i]++;
        if (i != LOCK_IDX) total++;
        if (elapsedMs > 0) pushSample(elapsedMs);
    }

    /**
     * 记一次「锁定风险」（**不是** helper 的 status，独立轴）。
     *
     * @param why 人类可读原因（只保留最近一条非空值，供 summary 说明）
     */
    public synchronized void noteLockRisk(String why) {
        cnt[LOCK_IDX]++;
        if (why != null) {
            String w = why.trim();
            if (!w.isEmpty()) lockWhy = w;
        }
    }

    private void pushSample(long v) {
        if (n < MAX_SAMPLES) {
            ring[next] = v;
            next = (next + 1) % MAX_SAMPLES;
            n++;
            sum += v;
        } else {
            // 滑窗：挤掉最旧的那个（就是 next 指向的那个），换上新的。
            sum -= ring[next];
            ring[next] = v;
            sum += v;
            next = (next + 1) % MAX_SAMPLES;
            dropped++;
            truncated = true;
        }
    }

    // ---------------- 读取 ----------------

    /** 计入分类的结果总数（不含 skipped、不含锁定风险）。 */
    public synchronized int total() {
        return total;
    }

    /** 本地跳过（校验位不合法）的条数。 */
    public synchronized int skippedCount() {
        return skipped;
    }

    /** 锁定风险次数。 */
    public synchronized int lockCount() {
        return cnt[LOCK_IDX];
    }

    /** 最近一次锁定风险的说明（没有则空串）。 */
    public synchronized String lockWhy() {
        return lockWhy;
    }

    /** 窗口内耗时样本数。 */
    public synchronized int sampleCount() {
        return n;
    }

    /** 是否发生过样本封顶丢弃。 */
    public synchronized boolean samplesTruncated() {
        return truncated;
    }

    /** 已被挤出的样本数。 */
    public synchronized long droppedSamples() {
        return dropped;
    }

    /** 平均耗时（ms）；**没有真实样本时返回 -1**（调用方显示"暂无数据"）。 */
    public synchronized long avgMs() {
        return n <= 0 ? -1 : sum / n;
    }

    /** P50（真实样本，最近秩法）；样本不足 {@link #MIN_SAMPLES_FOR_PERCENTILE} 个返回 -1。 */
    public synchronized long p50() {
        return percentile(50);
    }

    /** P90（真实样本，最近秩法）；样本不足 {@link #MIN_SAMPLES_FOR_PERCENTILE} 个返回 -1。 */
    public synchronized long p90() {
        return percentile(90);
    }

    /**
     * 最近秩（nearest-rank）分位数：{@code rank = ceil(p/100 * n) - 1}，升序取第 rank 个。
     * 例如 1..100 → P50 = 50、P90 = 90。
     */
    private long percentile(int p) {
        if (n < MIN_SAMPLES_FOR_PERCENTILE) return -1;
        long[] a = new long[n];
        System.arraycopy(ring, 0, a, 0, n);
        java.util.Arrays.sort(a);
        int rank = (int) Math.ceil(p / 100.0 * n) - 1;
        if (rank < 0) rank = 0;
        if (rank > n - 1) rank = n - 1;
        return a[rank];
    }

    /** 某分类的条数；未知分类 / skipped 返回 0。 */
    public synchronized int countOf(String cls) {
        int i = idxOf(norm(cls));
        return i < 0 ? 0 : cnt[i];
    }

    /**
     * 某分类占「已记录事件」的比例（0..1）。
     *
     * <p>{@code total()==0} → {@code -1}（**不是** 0，调用方据此显示"暂无数据"）。
     * 常规分类的分母是 {@link #total()}；{@link #C_LOCK_RISK} 是独立轴，
     * 分母取 {@code total + lockCount}（锁定时长不在尝试数里，用同一个分母会 >1）。
     */
    public synchronized double ratioOf(String cls) {
        if (total <= 0) return -1;
        String c = norm(cls);
        if (C_LOCK_RISK.equals(c)) {
            int den = total + cnt[LOCK_IDX];
            return den <= 0 ? -1 : (double) cnt[LOCK_IDX] / den;
        }
        int i = idxOf(c);
        return i < 0 ? -1 : (double) cnt[i] / total;
    }

    /** 是否**全部**关键指标都还没有真实样本（总数、耗时样本、锁定风险、本地跳过全为 0）。 */
    public synchronized boolean empty() {
        return total == 0 && n == 0 && cnt[LOCK_IDX] == 0 && skipped == 0;
    }

    // ---------------- 落盘 ----------------

    /**
     * 单行文本（可落盘）：首行 {@code WPS_STATS v=1}，其后每行 {@code k=v}，
     * 值一律经 {@link WpsHelper#enc} 编码。
     */
    public synchronized String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append(" v=").append(SCHEMA).append('\n');
        kv(sb, "schema", SCHEMA);
        kv(sb, "total", total);
        kv(sb, "skipped", skipped);
        kv(sb, "truncated", truncated ? 1 : 0);
        kv(sb, "dropped", dropped);
        kv(sb, "samples", n);
        kv(sb, "ring_next", next);
        kvs(sb, "lock_why", lockWhy);
        for (int i = 0; i < ORDER.length; i++) kv(sb, "c_" + ORDER[i], cnt[i]);
        for (int i = 0; i < n; i++) kv(sb, "sample", ring[i]);
        return sb.toString();
    }

    /**
     * 反解 {@link #encode()}。
     *
     * <p>**解不开绝不抛**：任何坏输入都返回一个**空统计**，原因可经
     * {@link #lastDecodeError()} 取到。宁可显示「暂无数据」，也不显示半份错数。
     */
    public static WpsStats decode(String raw) {
        lastErr = "";
        try {
            if (raw == null) return fail("输入为空（null）");
            String[] lines = raw.split("\n", -1);
            String head = lines.length > 0 ? lines[0].trim() : "";
            if (!head.startsWith(HEADER)) {
                return fail("缺少表头 " + HEADER + "（实际：" + snip(head) + "）");
            }
            int v = versionOf(head);
            if (v != SCHEMA) {
                return fail("版本不支持：v=" + v + "，本版只认 v=" + SCHEMA);
            }

            WpsStats s = new WpsStats();
            int[] c = new int[ORDER.length];
            int tot = 0;
            int schemaSeen = -1;
            int samplesDeclared = -1;
            int ringNext = 0;
            long droppedSeen = 0;
            boolean truncatedSeen = false;
            String why = "";
            List<Long> samples = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                String ln = lines[i].trim();
                if (ln.isEmpty()) continue;
                int eq = ln.indexOf('=');
                if (eq <= 0) return fail("第 " + (i + 1) + " 行不是 k=v：" + snip(ln));
                String k = ln.substring(0, eq).trim();
                String val = WpsHelper.dec(ln.substring(eq + 1).trim());
                if (k.startsWith("c_")) {
                    int ci = idxOf(k.substring(2));
                    if (ci < 0) return fail("未知分类：" + k);
                    c[ci] = (int) num(k, val);
                } else if ("sample".equals(k)) {
                    samples.add(num(k, val));
                } else if ("schema".equals(k)) {
                    schemaSeen = (int) num(k, val);
                } else if ("total".equals(k)) {
                    tot = (int) num(k, val);
                } else if ("skipped".equals(k)) {
                    s.skipped = (int) num(k, val);
                } else if ("truncated".equals(k)) {
                    truncatedSeen = num(k, val) != 0;
                } else if ("dropped".equals(k)) {
                    droppedSeen = num(k, val);
                } else if ("samples".equals(k)) {
                    samplesDeclared = (int) num(k, val);
                } else if ("ring_next".equals(k)) {
                    ringNext = (int) num(k, val);
                } else if ("lock_why".equals(k)) {
                    why = val;
                } else {
                    // 未知字段一律判错：静默丢弃字段会让"显示的数"与"落盘的数"悄悄分家。
                    return fail("未知字段：" + k);
                }
            }

            if (schemaSeen != SCHEMA) {
                return fail("schema 不支持：" + schemaSeen + "，本版只认 " + SCHEMA);
            }
            if (samplesDeclared < 0) return fail("缺少 samples 字段");
            if (samplesDeclared != samples.size()) {
                return fail("样本数不一致：声明 " + samplesDeclared + "，实际 " + samples.size());
            }
            if (samplesDeclared > MAX_SAMPLES) {
                return fail("样本数超上限：声明 " + samplesDeclared + " > " + MAX_SAMPLES);
            }
            if (truncatedSeen != (droppedSeen > 0)) {
                return fail("封顶标记与丢弃数不一致：truncated=" + truncatedSeen
                        + "，dropped=" + droppedSeen);
            }
            int clsSum = 0;
            for (int i = 0; i < c.length; i++) if (i != LOCK_IDX) clsSum += c[i];
            if (clsSum != tot) {
                return fail("分类合计 " + clsSum + " 条与 total " + tot + " 条不符：差 " + (tot - clsSum));
            }
            if (ringNext < 0 || ringNext >= (samplesDeclared == 0 ? 1 : MAX_SAMPLES)) {
                return fail("ring_next 越界：" + ringNext);
            }
            // 窗口未满时，写入位置必然等于样本数（样本是从下标 0 顺序填的）；
            // 不等说明文件被改过，继续用会在续写时漏掉样本 —— 宁可判错。
            if (samplesDeclared > 0 && samplesDeclared < MAX_SAMPLES && ringNext != samplesDeclared) {
                return fail("ring_next 与样本数不符：ring_next=" + ringNext
                        + "，samples=" + samplesDeclared);
            }

            System.arraycopy(c, 0, s.cnt, 0, c.length);
            s.total = tot;
            s.truncated = truncatedSeen;
            s.dropped = droppedSeen;
            s.lockWhy = why == null ? "" : why;
            s.n = samplesDeclared;
            s.next = samplesDeclared == 0 ? 0 : ringNext;
            for (int i = 0; i < samplesDeclared; i++) {
                long sv = samples.get(i);
                s.ring[i] = sv;
                s.sum += sv;
            }
            return s;
        } catch (Throwable t) {
            return fail("解析失败：" + (t.getMessage() == null ? String.valueOf(t) : t.getMessage()));
        }
    }

    /** 最近一次 {@link #decode(String)} 的失败原因；成功时为空串（永不为 null）。 */
    public static String lastDecodeError() {
        String e = lastErr;
        return e == null ? "" : e;
    }

    // ---------------- 文本 ----------------

    /**
     * 人类可读摘要（中文/英文）：总计、各分类占比、平均耗时、P50/P90、样本封顶提示。
     * **没有真实数据的指标写「暂无数据」**。
     */
    public synchronized String summary(boolean zh) {
        StringBuilder sb = new StringBuilder();
        String nd = zh ? "暂无数据" : "no data";

        // 1) 总览
        sb.append(zh ? "WPS 统计：总计 " + total + " 条 · 本地跳过 " + skipped + " 条 · 锁定风险 "
                : "WPS stats: total " + total + " · skipped " + skipped + " · lock risk ");
        sb.append(cnt[LOCK_IDX]);
        sb.append(zh ? " 次" : " events");
        if (!lockWhy.isEmpty()) sb.append(zh ? "（最近：" + lockWhy + "）" : " (latest: " + lockWhy + ")");
        sb.append('\n');

        // 2) 分类占比
        if (total <= 0) {
            sb.append(zh ? "分类占比：暂无数据" : "class ratios: no data");
        } else {
            sb.append(zh ? "分类占比：" : "class ratios: ");
            for (int i = 0; i < ORDER.length; i++) {
                if (i > 0) sb.append(" · ");
                if (zh) {
                    sb.append(label(ORDER[i], true)).append(' ').append(cnt[i]).append(" 条 ")
                            .append(pct(ratioOf(ORDER[i]))).append('%');
                } else {
                    sb.append(label(ORDER[i], false)).append(' ').append(cnt[i]).append(' ')
                            .append(pct(ratioOf(ORDER[i]))).append('%');
                }
            }
        }
        sb.append('\n');

        // 3) 平均耗时
        long avg = avgMs();
        if (avg < 0) sb.append(zh ? "平均耗时：暂无数据" : "avg: no data");
        else sb.append(zh ? "平均耗时 " + avg + " ms" : "avg " + avg + " ms");
        sb.append('\n');

        // 4) 分位数
        long p50 = p50();
        long p90 = p90();
        if (p50 < 0 || p90 < 0) {
            sb.append(zh
                    ? "P50/P90：暂无数据（样本 " + n + " 个，不足 " + MIN_SAMPLES_FOR_PERCENTILE + " 个）"
                    : "P50/P90: " + nd + " (samples " + n + " < " + MIN_SAMPLES_FOR_PERCENTILE + ")");
        } else {
            sb.append("P50 ").append(p50).append(" ms · P90 ").append(p90).append(" ms");
        }
        sb.append('\n');

        // 5) 样本与封顶
        sb.append(zh ? "样本 " + n + " / 上限 " + MAX_SAMPLES : "samples " + n + " / cap " + MAX_SAMPLES);
        sb.append("（truncatedSamples=").append(truncated);
        if (truncated) {
            sb.append(zh ? "，已丢弃最旧 " + dropped + " 个样本）" : ", dropped oldest " + dropped + ")");
        } else {
            sb.append(zh ? "）" : ")");
        }
        return sb.toString();
    }

    /**
     * 逐类明细（**固定 10 行**，顺序同 {@link #classes()}）。
     * 没有数据的分类写「暂无数据」。
     */
    public synchronized String detail(boolean zh) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ORDER.length; i++) {
            if (i > 0) sb.append('\n');
            boolean lockCls = (i == LOCK_IDX);
            int v = cnt[i];
            if (total <= 0 && !(lockCls && v > 0)) {
                sb.append(label(ORDER[i], zh)).append(zh ? "：暂无数据" : ": no data");
                continue;
            }
            sb.append(label(ORDER[i], zh)).append(zh ? "：" : ": ").append(v);
            sb.append(lockCls ? (zh ? " 次" : " events") : (zh ? " 条" : ""));
            double r = ratioOf(ORDER[i]);
            if (r >= 0) sb.append(" · ").append(pct(r)).append('%');
            else if (lockCls) sb.append(zh ? "（总数为 0，不给占比）" : " (no ratio: total=0)");
        }
        return sb.toString();
    }

    // ---------------- 台账对账 ----------------

    /**
     * 与台账对账：一致返回**空串**，不一致返回中文说明。
     *
     * <p>校验三项：① {@code ledgerDoneCount} == {@link #total()}（不含 skipped）；
     * ② {@code ledgerHitCount} == {@link #countOf(String)}({@link #C_CONFIRMED})；
     * ③ 内部不变式：各分类合计 == total。
     *
     * @see #reconcile(WpsStats, int, int, int)
     */
    public static String reconcile(WpsStats s, int ledgerDoneCount, int ledgerHitCount) {
        return reconcile(s, ledgerDoneCount, ledgerHitCount, -1);
    }

    /**
     * 带「锁定计数」的完整对账（{@code ledgerLockedCount < 0} = 台账没有这一列，不校验）。
     *
     * <p>命中数对不上时会把「半证据」与「锁定风险」条数一并写进说明——
     * 这两类正是「统计说没命中、台账却记了一条」的常见来源，别让人去猜。
     */
    public static String reconcile(WpsStats s, int ledgerDoneCount, int ledgerHitCount,
                                   int ledgerLockedCount) {
        if (s == null) return "统计不可用（null），无法与台账对账";
        List<String> bad = new ArrayList<>();
        int t;
        int sk;
        int hits;
        int locks;
        int half;
        synchronized (s) {
            t = s.total;
            sk = s.skipped;
            hits = s.cnt[idxOf(C_CONFIRMED)];
            locks = s.cnt[LOCK_IDX];
            half = s.cnt[idxOf(C_HALF_EVIDENCE)];
            int clsSum = 0;
            for (int i = 0; i < s.cnt.length; i++) if (i != LOCK_IDX) clsSum += s.cnt[i];
            if (clsSum != t) bad.add("分类合计 " + clsSum + " 条与总计 " + t + " 条不符：差 " + (t - clsSum));
        }
        if (ledgerDoneCount != t) {
            bad.add("统计 " + t + " 条，台账 " + ledgerDoneCount + " 条：差 " + (t - ledgerDoneCount));
        }
        if (ledgerHitCount != hits) {
            bad.add("命中不一致：统计已确认 " + hits + " 条，台账命中 " + ledgerHitCount
                    + " 条：差 " + (hits - ledgerHitCount));
        }
        if (ledgerLockedCount >= 0 && ledgerLockedCount != locks) {
            bad.add("锁定风险不一致：统计 " + locks + " 次，台账 " + ledgerLockedCount
                    + " 次：差 " + (locks - ledgerLockedCount));
        }
        if (bad.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bad.size(); i++) {
            if (i > 0) sb.append("；");
            sb.append(bad.get(i));
        }
        if (sk > 0) sb.append("（本地跳过 ").append(sk).append(" 条不计入统计）");
        if (ledgerHitCount != hits) {
            sb.append("；另：半证据 ").append(half).append(" 条、锁定风险 ").append(locks).append(" 次");
        }
        return sb.toString();
    }

    // ---------------- internals ----------------

    private static void kv(StringBuilder sb, String k, long v) {
        sb.append(k).append('=').append(WpsHelper.enc(Long.toString(v))).append('\n');
    }

    private static void kvs(StringBuilder sb, String k, String v) {
        sb.append(k).append('=').append(WpsHelper.enc(v)).append('\n');
    }

    private static String norm(String cls) {
        return cls == null ? "" : cls.trim();
    }

    private static int idxOf(String cls) {
        String c = norm(cls);
        if (c.isEmpty()) return -1;
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i].equals(c)) return i;
        return -1;
    }

    private static long num(String k, String val) {
        try {
            return Long.parseLong(val);
        } catch (Throwable t) {
            throw new IllegalArgumentException("数值不合法：" + k + "=" + val);
        }
    }

    /** {@code WPS_STATS v=1} → 1；拿不到版本号返回 -1。 */
    private static int versionOf(String head) {
        int i = head.indexOf("v=");
        if (i < 0) return -1;
        String tail = head.substring(i + 2).trim();
        int end = 0;
        while (end < tail.length() && Character.isDigit(tail.charAt(end))) end++;
        if (end == 0) return -1;
        return Integer.parseInt(tail.substring(0, end));
    }

    private static WpsStats fail(String why) {
        lastErr = why == null ? "未知原因" : why;
        return new WpsStats();
    }

    private static String snip(String s) {
        if (s == null) return "";
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }

    private static String pct(double r) {
        if (r < 0) return "0.0";
        return String.format(Locale.ROOT, "%.1f", r * 100.0);
    }
}
