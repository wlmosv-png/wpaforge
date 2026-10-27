package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 配置恢复记录 —— **纯逻辑，无 Android 依赖**。
 *
 * <h3>它解决的那个真实缺口</h3>
 * 引擎为了保证归因，会在**每条候选之前**把目标网络从系统里删掉，
 * 再用候选口令把它加回来（{@code cmd wifi forget-network} → {@code cmd wifi connect-network}）。
 * 于是任务跑完之后，系统里保存的是**最后一条候选的口令**：
 * <ul>
 *   <li>命中收尾 → 留的就是**正确口令** → 不再删除，直接保留（见
 *       {@link RestorePlan.Step#KEEP_HIT_CONFIG}）；</li>
 *   <li>未命中 / 暂停 / 取消 / 异常收尾 → 留的是**错误口令**，
 *       而用户原来的配置已经被删掉 → 手机连不上自己的网络，必须手输口令。</li>
 * </ul>
 * 而且原来的收尾只有 {@code finally}，**进程被杀时它不一定执行**。
 *
 * <h3>1.9.28 的根因修复：状态机与生命周期</h3>
 * 1.9.27 及以前只有 ARMED / RESTORED / KEPT / FAILED 四个阶段，
 * 而“要不要动手”= 「不是 RESTORED / KEPT」。于是：
 * <ol>
 *   <li><b>把连接观察当成了恢复成败</b>：{@code cmd wifi connect-network} 之后
 *       {@code VERIFY} 要求“读回确认已连上 + 有 IP”。目标**不在覆盖范围内**时
 *       这条永远不成立，记录被判 {@code FAILED} —— 哪怕原配置其实已经写回系统。</li>
 *   <li><b>FAILED 永久阻塞一切</b>：{@code FAILED} 仍被算作“未完成的待办”，
 *       于是 {@link RestoreGuard} 用 {@code OTHER_PENDING} 挡掉**所有**新任务，
 *       而且每次启动还会再试一次、再失败一次，永远出不来。</li>
 *   <li><b>没有出口</b>：既没有重试上限，也没有归档/放弃机制，界面上更没有任何入口。</li>
 * </ol>
 *
 * <p>现在把两件事**分开**：
 * <ul>
 *   <li><b>配置层面</b>（写回是否被独立读回确认）→ 决定 {@link #writeBackConfirmed}
 *       —— 它才是恢复成败的唯一判据；</li>
 *   <li><b>连接层面</b>（此刻是否连上、是否拿到 IP）→ 只记进
 *       {@link #connObserved} / {@link #ipObserved}，仅作观察，**不参与成败**。</li>
 * </ul>
 *
 * <h3>阶段与生命周期</h3>
 * <pre>
 * ARMED ──收尾/补做──┬─→ RESTORED   原配置已写回并回读确认（成功终态）
 *                    ├─→ KEPT       命中保留（成功终态）
 *                    ├─→ RESOLVED   原本无需还原且无残留（成功终态）
 *                    ├─→ FAILED     可重试失败（attempts &lt; MAX，阻塞同目标）
 *                    └─→ ARCHIVED   超过重试上限 / 旧格式归档 / 人工放弃（终态）
 * </pre>
 * 终态记录不再阻塞新任务；唯一还会挡路的，是**同一目标**尚有未解决风险
 * （{@link #hasUnresolvedRisk()}），或盘上记录压根解析不出来（fail closed）。
 *
 * <h3>为什么文件里可以有多条记录</h3>
 * 1.9.27 只存一条：写新记录就会**覆盖**上一条，于是只能靠“盘上有别人的记录就
 * 一律不许开新任务”来保命 —— 这正是永久锁死的另一半。现在按 taskId 分槽，
 * 新任务写自己的槽，别人的记录留在原地等补做，互不覆盖。
 *
 * <h3>口令怎么存</h3>
 * 原口令是**恢复所必需**的，不能脱敏掉；但也绝不能明文落盘。
 * 因此写到记录里的是 {@link SecretBox} 的密文（Android Keystore，AES/GCM）。
 * Keystore 不可用时 {@code sealedPsk} 为空 —— 此时**不再假装能恢复**，
 * 而是明确把结果标成“原口令无法还原”，见 {@link RestorePlan#judge}。
 */
public final class RestoreJournal {

    /** 已登记，尚未收尾（进程被杀后会停在这个阶段 → 下次启动补做）。 */
    public static final String STAGE_ARMED = "ARMED";
    /** 原配置已写回系统，并经**独立回读**确认。成功终态。 */
    public static final String STAGE_RESTORED = "RESTORED";
    /**
     * 命中收尾：**保留**了目标那条配置（它就是正确口令），因此没有做任何删除。
     * 成功终态 —— 否则“启动补做”会把刚保留的正确配置当成候选残留删掉。
     */
    public static final String STAGE_KEPT = "KEPT";
    /** 原本就没有已保存配置（或本来就不需要口令），且已确认没有候选残留。成功终态。 */
    public static final String STAGE_RESOLVED = "RESOLVED";
    /** 恢复尝试过但失败，**仍可重试**（未到上限）。阻塞同目标的新任务。 */
    public static final String STAGE_FAILED = "FAILED";
    /**
     * 终态归档：旧格式迁移、重试到上限、或用户手动放弃。
     *
     * <p>不再自动重试、不再阻塞（除同目标且仍有残留风险外）。
     * 记录本身保留 —— 失败原因必须可追踪，不许静默消失。
     */
    public static final String STAGE_ARCHIVED = "ARCHIVED";

    /** 当前记录格式版本。{@code <2} 或缺失 = 1.9.27 及以前的旧格式。 */
    public static final int SCHEMA = 2;
    /** 自动重试上限（含启动补做与收尾）。到顶即归档，不再无限自锁。 */
    public static final int MAX_ATTEMPTS = 3;
    /** 两次自动重试之间的最小间隔：反复启动应用不得把同一条记录刷成死循环。 */
    public static final long RETRY_COOLDOWN_MS = 10L * 60L * 1000L;
    /** 盘上待处理记录的上限：超过即要求人工清理（防止无限累积）。 */
    public static final int MAX_PENDING = 6;

    private static final String V2_HEADER = "version=2";
    private static final String SEP = "---";

    private RestoreJournal() {}

    // ---------------------------------------------------------------- 记录

    /** 一条恢复记录。字段全部可序列化，不含任何 Android 类型。 */
    public static final class Entry {
        /** 记录格式版本；旧数据没有 → 0，解析时按旧语义迁移。 */
        public int schema = 0;

        public String stage = STAGE_ARMED;
        public String ssid = "";
        public String bssid = "";
        /** ConnectSpec 用的加密方式参数（wpa2 / wpa3 / open …）。 */
        public String security = "wpa2";
        /** 破坏性动作之前目标的 netId；-1 = 当时目标本来就不在已保存列表里。 */
        public long originalNetId = -1L;
        /** 原本是否存在已保存配置（决定要不要重新加回去）。 */
        public boolean hadPsk = false;
        /** {@link SecretBox} 密文；空串 = 拿不到安全的原口令。 */
        public String sealedPsk = "";
        /** 快照步骤本身是否成功（失败时恢复能力受限，必须如实报告）。 */
        public boolean snapshotOk = false;
        public String runId = "";
        /**
         * **任务归属**：这条记录属于哪个任务。
         *
         * <p>缺少这一层时，一个新任务在记录落盘失败后会拿**上一个任务的旧记录**
         * 去执行恢复 —— 那等于用另一个目标的口令去覆盖当前目标。
         * 旧数据没有这个字段 → 默认空串，一律判为「不属于任何任务」（不猜）。
         */
        public String taskId = "";
        /**
         * 采集当时是否读到了「已保存网络列表」。
         *
         * <p>{@code false} 表示连目标当前有没有配置都不知道 —— 这种快照不可信。
         */
        public boolean channelOk = false;
        public long armedAt = 0L;
        public long finishedAt = 0L;
        public String failStep = "";
        public String failDetail = "";

        // ── 1.9.28 新增：把「配置恢复」与「连接观察」彻底分开 ──────────────

        /**
         * **配置层面是否成功**：原配置写回系统后，是否被独立回读确认。
         *
         * <p>这是恢复成败的**唯一**判据。目标不在覆盖范围内、当前连着别的网、
         * 连接观察超时 —— 都不影响本字段。
         */
        public boolean writeBackConfirmed = false;
        /** 收尾时是否**观察到**已连上目标（仅观察，不参与成败）。 */
        public boolean connObserved = false;
        /** 收尾时是否读到有效 IP（仅观察，不参与成败）。 */
        public boolean ipObserved = false;
        /** 是否可能仍有候选残留（错误口令还留在系统里）—— 决定要不要继续挡同目标。 */
        public boolean residualRisk = false;
        /** 自动重试次数（每尝试一次 +1）。 */
        public int attempts = 0;
        /** 最近一次尝试时间（epoch ms）。 */
        public long lastAttemptAt = 0L;
        /** 人工放弃标记（UI 上点过「清理记录」或「不再重试」）。 */
        public boolean abandoned = false;

        // ── 审计要求：三类计数必须分开，不能只留一个 attempts ────────────────
        /**
         * **实际发出写回命令**的次数（{@code cmd wifi connect-network} 真的被执行）。
         *
         * <p>与 {@link #attempts} 的区别：{@code attempts} 计“恢复流程跑了几次”，
         * 其中相当一部分会因为拿不到原口令密文（{@code canReadd()=false}）
         * 而**根本没写回**。把两者混成一个数字，用户就会看到“已试 3/3”
         * 而实际上一次都没写回。旧记录缺这个字段 → 0（不猜历史）。
         */
        public int writeBackIssuedCount = 0;
        /**
         * **读回验证未通过**的次数（UNVERIFIED / READD-FAILED / READD-NOT-WRITTEN）。
         *
         * <p>与 {@code attempts} 的区别：一次尝试可能连写回命令都没发出去
         * （那不算“验证失败”，而是“没写回”）。
         */
        public int verifyFailures = 0;

        public Entry() {}

        public boolean armed() { return STAGE_ARMED.equals(stage); }
        public boolean restored() { return STAGE_RESTORED.equals(stage); }

        /** 阶段名不认识（记录损坏 / 更高版本写的）→ 一律按未知处理，不猜。 */
        public boolean broken() {
            return !(STAGE_ARMED.equals(stage) || STAGE_RESTORED.equals(stage)
                    || STAGE_KEPT.equals(stage) || STAGE_RESOLVED.equals(stage)
                    || STAGE_FAILED.equals(stage) || STAGE_ARCHIVED.equals(stage));
        }

        /**
         * 是否**终态**（没有待办了）。
         *
         * <p>判断“还要不要动手”一律用 {@link #blocking()}；
         * 本方法只回答“这条记录的自动流程走完了没有”。
         */
        public boolean settled() {
            return restored() || STAGE_KEPT.equals(stage)
                    || STAGE_RESOLVED.equals(stage) || STAGE_ARCHIVED.equals(stage);
        }

        /**
         * 是否**需要自动重试**（未终结且未归档）。
         *
         * <p>= {@link #blocking()} 且没到重试上限。
         */
        public boolean needsRetry() {
            return armed() || STAGE_FAILED.equals(stage);
        }

        /**
         * 是否应当阻塞**同一目标**的新任务。
         *
         * <p>注意：它**不再是**“挡住所有新任务”的依据 —— 跨目标任务由
         * {@link RestoreRunner#blocksNewTask} 判定（别人的记录不再一票否决）。
         */
        public boolean blocking() {
            if (STAGE_ARCHIVED.equals(stage)) return false;
            if (armed() || STAGE_FAILED.equals(stage)) return true;
            return broken();   // 未知阶段：fail closed
        }

        /**
         * 是否仍有**未解决的配置污染风险**：可能还留着候选（错误）口令，
         * 或者状态压根未知。
         *
         * <p>它决定“同一目标的新任务还能不能被放行”：
         * 有风险 + 同目标 → 必须先处理这条记录。
         */
        public boolean hasUnresolvedRisk() {
            return residualRisk || armed() || broken();
        }

        /**
         * 这条记录是不是**这个目标**的。
         *
         * <h3>匹配规则（审计要求写清）</h3>
         * <ul>
         *   <li><b>按 SSID 比，不比 BSSID</b>：系统里同一个 SSID 只有**一条**已保存配置，
         *       口令是共享的 —— 换一个 AP（BSSID 不同）仍然指向同一条配置，
         *       所以「同 SSID 不同 BSSID」必须照样算同目标；</li>
         *   <li><b>BSSID 未知（空）时同样按 SSID 判</b>：不猜、也不因为缺字段就放行；</li>
         *   <li><b>SSID 不同 → 不同目标</b>：两条配置互不影响，不该互相阻塞。</li>
         * </ul>
         */
        public boolean sameTarget(String target) {
            if (target == null || target.isEmpty()) return false;
            return target.equals(ssid);
        }

        /** 还能自动重试几次。 */
        public int remainingAttempts() {
            int left = MAX_ATTEMPTS - attempts;
            return left < 0 ? 0 : left;
        }

        /** 冷却是否已过（反复启动应用时用它挡住密集重试）。 */
        public boolean cooldownPassed(long now) {
            if (lastAttemptAt <= 0L) return true;
            return now - lastAttemptAt >= RETRY_COOLDOWN_MS;
        }

        /** 能不能真的把原配置加回来（有口令 + 有加密通道）。 */
        public boolean canReadd() {
            return hadPsk && sealedPsk != null && !sealedPsk.isEmpty();
        }

        /** 供日志/界面使用的阶段标签。 */
        public String stageLabel() {
            if (STAGE_ARMED.equals(stage)) return "待收尾";
            if (STAGE_RESTORED.equals(stage)) return "已还原";
            if (STAGE_KEPT.equals(stage)) return "已保留命中配置";
            if (STAGE_RESOLVED.equals(stage)) return "无需还原";
            if (STAGE_FAILED.equals(stage)) return "恢复失败（可重试）";
            if (STAGE_ARCHIVED.equals(stage)) return "已归档";
            return "记录损坏";
        }

        /** 记一次尝试（收尾与启动补做都要走这里，保证计数一致）。 */
        public void noteAttempt(long now) {
            attempts++;
            lastAttemptAt = now <= 0L ? System.currentTimeMillis() : now;
        }

        /**
         * 旧格式（1.9.27 及以前）迁移。
         *
         * <p>关键点：<b>不虚报成功</b>。旧 {@code FAILED} 记录只留下
         * {@code failStep}/{@code failDetail} 两个线索，于是：
         * <ul>
         *   <li>失败在 {@code REMOVE_WRONG_CONFIG}（删残留都没做到）→ 判为
         *       {@code residualRisk=true}：真有污染风险，同目标仍然要挡；</li>
         *   <li>失败在 {@code VERIFY}（原配置已写回、只是没连上）→
         *       {@code writeBackConfirmed=true}、{@code residualRisk=false}：
         *       风险已消除，不再挡任何任务；</li>
         *   <li>两者之外 → 保守按“有风险”处理。</li>
         * </ul>
         * <h3>审计修复：不再伪造尝试次数</h3>
         * 旧实现把 {@code attempts} 直接抬到 {@code MAX_ATTEMPTS-1}，于是界面上
         * 那条事故记录显示「已试 3/3」—— 而它**真实尝试只有 1 次**（其余 2 次是
         * 迁移凭空写上的）。这种数字会误导排查。现在迁移只做“按新状态机重新判定”，
         * 计数从 0 开始按真实尝试累加；上限仍为 {@link #MAX_ATTEMPTS} 次，
         * 配合 {@link #RETRY_COOLDOWN_MS} 依然保证有限、不会自锁。
         */
        public void migrateLegacy() {
            if (schema >= SCHEMA) return;
            schema = SCHEMA;
            if (!STAGE_FAILED.equals(stage)) return;
            boolean removeFailed = "REMOVE_WRONG_CONFIG".equals(failStep)
                    || (failDetail != null && failDetail.contains("REMOVE-FAILED"));
            if (removeFailed) {
                residualRisk = true;
                writeBackConfirmed = false;
            } else {
                residualRisk = false;
                writeBackConfirmed = true;
            }
            // 审计修复：这里**不再**伪造尝试次数（旧实现写成 MAX_ATTEMPTS-1）。
            // 真实次数从 attempts 现状开始累加；上限与冷却照旧兜底。
            failDetail = (failDetail == null ? "" : failDetail)
                    + " · 迁移(1.9.27→1.9.28)：按新状态机重新判定，不伪造尝试次数";
        }

        public String toText() {
            StringBuilder sb = new StringBuilder();
            kv(sb, "schema", String.valueOf(schema));
            kv(sb, "stage", stage);
            kv(sb, "ssid", ssid);
            kv(sb, "bssid", bssid);
            kv(sb, "security", security);
            kv(sb, "originalNetId", String.valueOf(originalNetId));
            kv(sb, "hadPsk", hadPsk ? "1" : "0");
            kv(sb, "sealedPsk", sealedPsk);
            kv(sb, "snapshotOk", snapshotOk ? "1" : "0");
            kv(sb, "runId", runId);
            kv(sb, "taskId", taskId);
            kv(sb, "channelOk", channelOk ? "1" : "0");
            kv(sb, "armedAt", String.valueOf(armedAt));
            kv(sb, "finishedAt", String.valueOf(finishedAt));
            kv(sb, "failStep", failStep);
            kv(sb, "failDetail", failDetail);
            kv(sb, "writeBackConfirmed", writeBackConfirmed ? "1" : "0");
            kv(sb, "connObserved", connObserved ? "1" : "0");
            kv(sb, "ipObserved", ipObserved ? "1" : "0");
            kv(sb, "residualRisk", residualRisk ? "1" : "0");
            kv(sb, "attempts", String.valueOf(attempts));
            kv(sb, "lastAttemptAt", String.valueOf(lastAttemptAt));
            kv(sb, "abandoned", abandoned ? "1" : "0");
            kv(sb, "writeBackIssuedCount", String.valueOf(writeBackIssuedCount));
            kv(sb, "verifyFailures", String.valueOf(verifyFailures));
            return sb.toString();
        }

        /** 与 {@link #toText()} 对称；任一行无法解析时返回 {@code null}（不猜、不伪造）。 */
        public static Entry fromText(String text) {
            if (text == null || text.trim().isEmpty()) return null;
            Entry e = new Entry();
            for (String raw : text.split("\n")) {
                if (raw.isEmpty()) continue;
                int i = raw.indexOf('=');
                if (i <= 0) return null;
                String k = raw.substring(0, i);
                String v = unesc(raw.substring(i + 1));
                switch (k) {
                    case "schema": e.schema = (int) parseLong(v, 0L); break;
                    case "stage": e.stage = v; break;
                    case "ssid": e.ssid = v; break;
                    case "bssid": e.bssid = v; break;
                    case "security": e.security = v; break;
                    case "originalNetId": e.originalNetId = parseLong(v, -1L); break;
                    case "hadPsk": e.hadPsk = "1".equals(v); break;
                    case "sealedPsk": e.sealedPsk = v; break;
                    case "snapshotOk": e.snapshotOk = "1".equals(v); break;
                    case "runId": e.runId = v; break;
                    case "taskId": e.taskId = v; break;
                    case "channelOk": e.channelOk = "1".equals(v); break;
                    case "armedAt": e.armedAt = parseLong(v, 0L); break;
                    case "finishedAt": e.finishedAt = parseLong(v, 0L); break;
                    case "failStep": e.failStep = v; break;
                    case "failDetail": e.failDetail = v; break;
                    case "writeBackConfirmed": e.writeBackConfirmed = "1".equals(v); break;
                    case "connObserved": e.connObserved = "1".equals(v); break;
                    case "ipObserved": e.ipObserved = "1".equals(v); break;
                    case "residualRisk": e.residualRisk = "1".equals(v); break;
                    case "attempts": e.attempts = (int) parseLong(v, 0L); break;
                    case "lastAttemptAt": e.lastAttemptAt = parseLong(v, 0L); break;
                    case "abandoned": e.abandoned = "1".equals(v); break;
                    case "writeBackIssuedCount":
                        e.writeBackIssuedCount = (int) parseLong(v, 0L); break;
                    case "verifyFailures": e.verifyFailures = (int) parseLong(v, 0L); break;
                    default: break;   // 未知键：向前兼容，忽略而不是报错
                }
            }
            if (e.ssid == null || e.ssid.isEmpty()) return null;
            return e;
        }

        private static long parseLong(String s, long def) {
            try { return Long.parseLong(s); } catch (Throwable t) { return def; }
        }

        private static void kv(StringBuilder sb, String k, String v) {
            sb.append(k).append('=').append(esc(v == null ? "" : v)).append('\n');
        }

        private static String esc(String s) {
            return s.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\e");
        }

        private static String unesc(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char n = s.charAt(++i);
                    if (n == 'n') sb.append('\n');
                    else if (n == 'e') sb.append('=');
                    else if (n == '\\') sb.append('\\');
                    else sb.append(n);
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- 存储

    /** 记录载体。生产是应用私有目录里的一个文件；测试是内存。 */
    public interface Store {
        String read();
        boolean write(String text);
        boolean clear();
    }

    private static volatile Store store = null;

    /**
     * 读改写锁。
     *
     * <p>为什么必须有：{@link #save(Entry)} 是「读全部 → 换掉自己那一槽 → 整份写回」，
     * 两个任务（或收尾恢复与启动补做）同时进来就会**互相覆盖**：后写的赢，
     * 前一条记录连同它的口令密文一起消失，而且没有任何报错。
     * 生产侧虽然有 {@link RestoreRunner} 的全局锁，但任务开始时登记记录
     * 并不走那把锁 —— 所以原子性必须在 journal 自己这一层保证。
     */
    private static final Object IO = new Object();

    /** 安装记录载体（幂等）。 */
    public static void install(Store s) { store = s; }

    /** 是否已安装载体。 */
    public static boolean installed() { return store != null; }

    /** 测试/预览用的内存载体。 */
    public static Store memory() {
        final String[] slot = new String[1];
        return new Store() {
            @Override public String read() { return slot[0]; }
            @Override public boolean write(String text) { slot[0] = text; return true; }
            @Override public boolean clear() { slot[0] = null; return true; }
        };
    }

    // ---------------------------------------------------------------- 编解码

    /** 把一个块解析成记录；解析失败返回 {@code null}。 */
    static Entry parseBlock(String chunk) {
        if (chunk == null || chunk.trim().isEmpty()) return null;
        return Entry.fromText(chunk);
    }

    /**
     * 把整份文本编码成 v2（多条）格式。
     *
     * <p>格式刻意保持人类可读：首行 {@code version=2}，之后每条以一行
     * {@code ---} 起头 —— 与旧格式（整份就是一条记录）一眼可分。
     */
    static String encode(List<Entry> list) {
        StringBuilder sb = new StringBuilder();
        sb.append(V2_HEADER).append('\n');
        if (list != null) {
            for (Entry e : list) {
                if (e == null) continue;
                sb.append(SEP).append('\n');
                sb.append(e.toText());
            }
        }
        return sb.toString();
    }

    /**
     * 解码整份文本。
     *
     * <p>三种情况：
     * <ul>
     *   <li>空 → 空列表（没有待办，不是损坏）；</li>
     *   <li>无 {@code version=2} 头 → 按**旧格式**整体解析为一条（并迁移）；</li>
     *   <li>有头 → 按 {@code ---} 分块，逐块解析。</li>
     * </ul>
     * 任何一块解析不出来，都会产出一条 {@link Entry#broken()} 记录 ——
     * 让“盘上有内容但读不出来”显式化，而不是静默当成“没有待办”（F7）。
     */
    static List<Entry> decode(String raw) {
        List<Entry> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        String text = raw.replace("\r\n", "\n");
        // 认不出的版本头 → 一律按“未知记录”处理（不猜内容，更不能误报成功）。
        if (text.startsWith("version=") && !text.startsWith(V2_HEADER)) {
            out.add(brokenEntry(text));
            return out;
        }
        if (!text.startsWith(V2_HEADER)) {
            Entry e = parseBlock(text);
            if (e == null) {
                out.add(brokenEntry(text));
                return out;
            }
            e.migrateLegacy();
            out.add(e);
            return out;
        }
        String body = text.substring(V2_HEADER.length());
        String[] parts = body.split("(?m)^\\s*" + SEP + "\\s*$");
        for (String chunk : parts) {
            if (chunk == null || chunk.trim().isEmpty()) continue;
            Entry e = parseBlock(chunk);
            if (e == null) {
                out.add(brokenEntry(chunk));
                continue;
            }
            e.migrateLegacy();
            out.add(e);
        }
        return out;
    }

    /** 一条“读得出来但认不出”的记录：不猜内容，只标记为损坏。 */
    private static Entry brokenEntry(String raw) {
        Entry e = new Entry();
        e.schema = SCHEMA;
        e.stage = "BROKEN";
        e.ssid = "";
        e.failStep = "PARSE";
        e.failDetail = "记录无法解析（长度 " + (raw == null ? 0 : raw.length())
                + "）：不猜内容，按未知处理";
        return e;
    }

    // ---------------------------------------------------------------- 读写

    /** 读全部记录（已迁移；解析失败的以 broken 记录出现）。 */
    public static List<Entry> loadAll() {
        synchronized (IO) {
            return loadAllLocked();
        }
    }

    private static List<Entry> loadAllLocked() {
        Store s = store;
        if (s == null) return Collections.emptyList();
        String raw;
        try {
            raw = s.read();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
        try {
            return decode(raw);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /**
     * 取一条**需要处理**的记录（兼容旧调用点的便捷方法）。
     *
     * <p>顺序：先阻塞中的，再可重试的；都没有则返回 {@code null}。
     * 需要精确语义时用 {@link #loadFor(String)} / {@link #loadForTarget(String)}。
     */
    public static Entry load() {
        List<Entry> all = loadAll();
        for (Entry e : all) if (e.blocking()) return e;
        for (Entry e : all) if (e.needsRetry()) return e;
        return null;
    }

    /** 按任务归属取记录。 */
    public static Entry loadFor(String taskId) {
        if (taskId == null || taskId.isEmpty()) return null;
        for (Entry e : loadAll()) if (taskId.equals(e.taskId)) return e;
        return null;
    }

    /** 按目标 SSID 取记录（取最后一条，即最新登记的那条）。 */
    public static Entry loadForTarget(String ssid) {
        if (ssid == null || ssid.isEmpty()) return null;
        Entry found = null;
        for (Entry e : loadAll()) if (ssid.equals(e.ssid)) found = e;
        return found;
    }

    /**
     * 写入/更新一条记录（按 {@code taskId} 分槽；taskId 为空按 ssid 分槽）。
     *
     * <p>写入策略：整份文件重写 + **回读校验**。回读不一致 = 没写进去。
     */
    public static boolean save(Entry e) {
        synchronized (IO) {
            return saveLocked(e);
        }
    }

    private static boolean saveLocked(Entry e) {
        Store s = store;
        if (s == null || e == null) return false;
        e.schema = SCHEMA;
        List<Entry> all = loadAll();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (sameSlot(all.get(i), e)) {
                all.set(i, e);
                replaced = true;
                break;
            }
        }
        if (!replaced) all.add(e);
        String text = encode(all);
        try {
            if (!s.write(text)) return false;
        } catch (Throwable t) {
            return false;
        }
        return verifyWritten(s, text);
    }

    /** 两条记录是否占同一个槽（同任务；任务缺失时同目标）。 */
    static boolean sameSlot(Entry a, Entry b) {
        if (a == null || b == null) return false;
        String ka = slotKey(a);
        String kb = slotKey(b);
        return !ka.isEmpty() && ka.equals(kb);
    }

    private static String slotKey(Entry e) {
        if (e.taskId != null && !e.taskId.isEmpty()) return "t:" + e.taskId;
        if (e.ssid != null && !e.ssid.isEmpty()) return "s:" + e.ssid;
        return "";
    }

    /** 删掉某任务/某目标的记录（不存在也算成功）。 */
    public static boolean remove(String key) {
        synchronized (IO) {
            return removeLocked(key);
        }
    }

    private static boolean removeLocked(String key) {
        Store s = store;
        if (s == null) return false;
        if (key == null || key.isEmpty()) return false;
        List<Entry> all = loadAll();
        List<Entry> keep = new ArrayList<>();
        boolean removed = false;
        for (Entry e : all) {
            if (key.equals(e.taskId) || key.equals(e.ssid)) { removed = true; continue; }
            keep.add(e);
        }
        if (!removed) return true;
        if (keep.isEmpty()) return clearAll();
        try {
            return s.write(encode(keep)) && verifyWritten(s, encode(keep));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 删掉所有记录（清空文件）。 */
    public static boolean clearAll() {
        synchronized (IO) {
            return clearAllLocked();
        }
    }

    private static boolean clearAllLocked() {
        Store s = store;
        if (s == null) return false;
        try {
            return s.clear();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 剔除**解析不出来**的那些记录（其余原样保留）。
     *
     * <p>为什么需要单独一条：损坏记录没有可用的 ssid/taskId，
     * 按 key 删根本删不掉 —— 而它又会让 {@link #pendingUnknown()} 恒为真、
     * 于是新任务永远被 fail closed 挡住。这就是“记录文件坏了就再也用不了”的死角。
     *
     * @return true = 已不再有损坏记录
     */
    public static boolean removeBroken() {
        synchronized (IO) {
            return removeBrokenLocked();
        }
    }

    private static boolean removeBrokenLocked() {
        Store s = store;
        if (s == null) return false;
        List<Entry> all = loadAll();
        List<Entry> keep = new ArrayList<>();
        boolean any = false;
        for (Entry e : all) {
            if (e.broken()) { any = true; continue; }
            keep.add(e);
        }
        if (!any) return true;
        if (keep.isEmpty()) return clearAll();
        String text = encode(keep);
        try {
            return s.write(text) && verifyWritten(s, text);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 兼容旧名：清空记录。 */
    public static boolean clear() { return clearAll(); }

    /**
     * 一条记录是否需要**人工关注**（唯一口径）。
     *
     * <p>审计修复：以前 {@link #pendingCount()} 用「blocking 或 needsRetry」，
     * 而 {@link RestoreRunner#checkNewTask} 用「needsRetry 或 hasUnresolvedRisk」——
     * 两个口径不一致时，界面上的条数与闸门实际拦的东西会对不上
     * （典型：一条 ARCHIVED 但仍有残留风险的记录，界面说“没有待办”，
     * 闸门却照样拦同目标）。现在两边共用这一个判据。
     */
    public static boolean needsAttention(Entry e) {
        return e != null && (e.needsRetry() || e.blocking() || e.hasUnresolvedRisk());
    }

    /** 是否存在**未解决**的记录（仍需重试、仍阻塞，或仍有残留风险）。 */
    public static boolean hasPending() {
        for (Entry e : loadAll()) if (needsAttention(e)) return true;
        return false;
    }

    /** 盘上需要关注的记录条数（与 {@link RestoreRunner#checkNewTask} 同一口径）。 */
    public static int pendingCount() {
        int n = 0;
        for (Entry e : loadAll()) if (needsAttention(e)) n++;
        return n;
    }

    /** 需要关注的记录（按盘上顺序）。 */
    public static java.util.List<Entry> pendingEntries() {
        java.util.List<Entry> out = new java.util.ArrayList<>();
        for (Entry e : loadAll()) if (needsAttention(e)) out.add(e);
        return out;
    }

    /**
     * 盘上**有内容**，但读不出来 / 解析不了（F7）。
     *
     * <h3>为什么「未知」不能当成「没有」</h3>
     * {@link #loadAll()} 把三种情况压成同一个空列表：文件不存在、
     * 文件读失败、文件内容损坏。只在后两种情况下，盘上其实**躺着一条记录**
     * —— 而它可能是**另一个目标**未完成的恢复。当成“没有待办”就会直接覆盖掉：
     * 那个目标的原口令从此永久丢失，谁也补做不了，而且没人会发现。
     *
     * @return true = 盘上有内容但不可解析 / 读异常（调用方**不得**当成“没有记录”）
     */
    public static boolean pendingUnknown() {
        synchronized (IO) {
            return pendingUnknownLocked();
        }
    }

    private static boolean pendingUnknownLocked() {
        Store s = store;
        if (s == null) return false;
        String raw;
        try {
            raw = s.read();
        } catch (Throwable t) {
            // 载体自己抛异常 —— 同样属于「不知道盘上是什么」，按未知处理。
            return true;
        }
        if (raw == null || raw.trim().isEmpty()) return false;
        List<Entry> all;
        try {
            all = decode(raw);
        } catch (Throwable t) {
            return true;
        }
        if (all.isEmpty()) return true;
        for (Entry e : all) if (e.broken()) return true;
        return false;
    }

    /**
     * 写入后**回读校验**（纯逻辑，便于离线断言）。
     *
     * <p>为什么必须回读：载体完全可能「返回 true 但没真写下去」
     * （改名失败、目录不可写、旧文件删不掉…）。一旦谎报成功，
     * 闸门会以为已有补做依据，进程被杀后却拿着**盘上那份旧记录**去恢复 ——
     * 正是 R11 要堵的那条路。所以「写成功」的定义是**读回来的内容与我写的一致**。
     *
     * @return true = 回读内容与 {@code text} 完全一致
     */
    public static boolean verifyWritten(Store s, String text) {
        if (s == null || text == null) return false;
        try {
            return text.equals(s.read());
        } catch (Throwable t) {
            return false;
        }
    }
}
