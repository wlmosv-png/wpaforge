package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 多字典执行计划：顺序、每本进度、状态机、单文件持久化。
 *
 * <h2>一、它解决什么</h2>
 * 用户想一次排好几本字典，按顺序跑；命中就停；没命中就自动换下一本；
 * 中途能暂停、能停止、能看每本跑到哪了。旧实现里这是「排多个任务」，
 * 于是出现两个真实问题：
 * <ol>
 *   <li><b>命中不停</b>：第一本命中了，第二本照跑（见 {@link #next} 的规则表）。</li>
 *   <li><b>进度串台</b>：几本字典共用一个 offset 语义，换一本就作废。</li>
 * </ol>
 *
 * <h2>二、单文件即唯一真相源（持久化方案）</h2>
 * 需求里点名的两个反例都成立：{"两个独立 AtomicFile 不自动构成跨文件事务"}、
 * {"保存计划后、保存队列前进程退出"}。这里的做法是**消除跨文件事务**：
 * <ul>
 *   <li>计划、每本字典的 offset、当前下标、状态、档位 —— **全部只写在
 *       {@code plan.json} 一个文件里**，一次 {@code rename} 原子替换；</li>
 *   <li>{@code queue.json} 只负责「跑一次」的任务，**不再承载计划内的续跑位置**；
 *       恢复时以计划文件为准，队列只是派生物。</li>
 * </ul>
 * 于是不存在「这份写了那份没写」的中间态：要么新计划生效，要么旧计划生效。
 * {@link #rev} 每次落盘 +1，故障注入测试用它断言「只可能前进」。
 *
 * <h2>三、失败语义：宁可重跑，绝不静默跳过</h2>
 * 计划文件损坏 / 版本不兼容 / 长度异常 → 一律返回**空计划**（= 从头开始）。
 * 少跑一遍的代价是时间，漏掉一个候选的代价是永远找不到密码。
 *
 * <h2>四、字典身份 = 完整内容 SHA-256</h2>
 * 需求明确要求「字典身份必须采用完整内容 SHA-256，而不是仅使用文件路径、
 * 大小和首行哈希」。这样做的好处是三条同时成立：
 * <ul>
 *   <li>改名 / 移动 / 换目录 → 身份不变 → 进度照旧（用户要求「正确识别」）；</li>
 *   <li>内容被替换（哪怕只改一行、大小不变）→ 身份变化 → 进度归零，
 *       不会拿着旧进度去对一份新字典（用户要求「重新识别」）；</li>
 *   <li>{@link #PARSE_RULES_VERSION} 变化时同样视为不同字典 ——
 *       解析规则变了，旧 offset 就不再指向同一批候选。</li>
 * </ul>
 *
 * <p>纯逻辑 + 文件 IO，无 Android 运行时依赖（{@link Context} 只在路径解析处用到），
 * 可在 JVM 完整测试。见 {@code DictPlanTest} / {@code DictPlanIoTest}。
 */
public final class DictPlan {

    /** 计划文件格式版本。不兼容时旧文件被当作空计划（重跑，不静默跳过）。 */
    public static final int FORMAT_VERSION = 1;

    /**
     * 候选解析规则版本。
     *
     * <p>它与 {@code DictStore.load} 的规则**成对变更**：BOM 剥离、trim、
     * 跳过空行与 {@code #} 注释、只收 8..63 位、按出现顺序去重。
     * 任何一条改了，同一份文件展开出的候选列表就不同 → 旧 offset 无意义。
     * 只改常量不会自动生效，必须同时改 {@code DictStore}；有测试钉住这一点。
     */
    public static final int PARSE_RULES_VERSION = 1;

    /** 一个计划最多放几本字典（防止误操作塞进上千本把界面拖死）。 */
    public static final int MAX_ITEMS = 64;

    // ================================================================ 状态

    /**
     * 计划状态。**终态与可继续态必须分清** —— 混淆它们会产生两个真实故障：
     * 「命中之后还在跑下一本」与「未命中被显示成整个计划失败」。
     */
    public enum State {
        /** 刚建好，还没开始。 */
        IDLE,
        /** 正在跑第 {@link DictPlan#index} 本。 */
        RUNNING,
        /** 停在第 {@link DictPlan#index} 本，可继续。**不是失败**。 */
        PAUSED,
        /** 用户主动停止。不自动前进。 */
        STOPPED,
        /** 计划内的字典**全部**跑完，都没有命中。 */
        DONE_MISS,
        /** 命中（第 {@link DictPlan#index} 本）。**整条计划立即终止**。 */
        DONE_HIT,
        /** 通道异常 / 环境不可用导致的阻塞。**不得伪装成正常未命中**。 */
        BLOCKED,
        /** 执行出错（例如字典读不出来）。不自动前进。 */
        ERROR;

        /** 整个计划是否已经结束（不会再自己动）。 */
        public boolean isTerminal() {
            return this == DONE_MISS || this == DONE_HIT || this == STOPPED;
        }

        /** 是否「停住了但仍可继续」。 */
        public boolean isResumable() {
            return this == PAUSED || this == BLOCKED || this == ERROR || this == IDLE;
        }
    }

    /** 一本字典跑完时的结论。**与 {@link State} 分开**：结论是输入，状态是结果。 */
    public enum Outcome {
        /** 命中。 */
        HIT,
        /** 正常跑完，未命中。 */
        MISS,
        /** 用户暂停。 */
        PAUSED,
        /** 用户停止。 */
        STOPPED,
        /** 通道异常 / 环境阻塞。 */
        BLOCKED,
        /** 执行出错。 */
        ERROR,
        /**
         * 没跑完就结束了（连接还没结束、被进程中断等）。
         *
         * <p><b>不得当作「已确定未命中」</b>：它既不是成功也不是失败，
         * 所以状态落在 {@link State#PAUSED}（停在当前这本，可重跑），
         * 绝不前进到下一本。
         */
        INCOMPLETE
    }

    /** 一本字典在计划里的位置与进度。 */
    public static final class Item {
        /** 绝对路径。**只用于展示与打开**，不参与身份判定（改名/移动不影响进度）。 */
        public String path = "";
        /** 展示名。 */
        public String name = "";
        /** **完整内容 SHA-256**。空串 = 拿不到（文件不存在 / 读失败）。 */
        public String sha256 = "";
        /** 展开候选时用的解析规则版本，见 {@link #PARSE_RULES_VERSION}。 */
        public int parseRules = PARSE_RULES_VERSION;
        /** 本字典**自己**的续跑位置 —— 每本独立，绝不互相污染。 */
        public int offset = 0;
        /** 候选条数（加载后回填）。 */
        public int dictSize = 0;
        /** 本字典自己的结论。 */
        public State local = State.IDLE;
        /** 本次跳过了多少候选（已确定错误 + 未确定），仅用于界面。 */
        public int skipped = 0;

        public Item() {}

        public Item(String path, String name) {
            this.path = path == null ? "" : path;
            this.name = name == null ? "" : name;
        }

        public float progress() {
            if (dictSize <= 0) return 0f;
            return Math.min(1f, (float) offset / dictSize);
        }
    }

    // ================================================================ 数据

    /** 计划标识（新建时生成；仅用于日志与故障注入断言，不参与恢复决策）。 */
    public String planId = "";
    /** 修订号：每次成功落盘 +1。故障注入测试用它断言「只前进、不回退」。 */
    public int rev = 0;
    public State state = State.IDLE;
    /** 当前字典下标（0 起）。始终落在 [0, items.size()-1] 内（空计划为 0）。 */
    public int index = 0;
    /** 自动连续执行下一本。**默认关闭** —— 与单字典模式行为一致。 */
    public boolean autoAdvance = false;
    /** 跨字典去重档位，见 {@link AttemptLedger.SkipPolicy}。 */
    public int skipPolicyId = 0;
    /** 本轮是否强制重跑（绕过历史过滤，不删历史）。 */
    public boolean forceRerun = false;
    /** 目标网络键（与 triedSet / 账本同源），用于界面显示与隔离。 */
    public String targetKey = "";
    /**
     * 目标网络快照。
     *
     * <h3>为什么计划里必须自带目标信息</h3>
     * 恢复时要能**独立于队列**重建任务：进程可能在「计划已推进、派生任务之前」
     * 被杀，那时队列里没有任何可用任务；也可能用户在崩溃后手动清了队列。
     * 只存一个 targetKey 不足以建出 {@link io.github.wlmosv_png.wpaforge.core.NetRecord}
     * （还需要 SSID/BSSID/加密方式），所以这里把目标快照一起落盘。
     *
     * <p>快照只包含**网络标识信息**，不含任何口令。
     */
    public String targetSsid = "";
    public String targetBssid = "";
    public int targetRssi = -70;
    public int targetFreq = 2437;
    /** {@link io.github.wlmosv_png.wpaforge.core.NetRecord.Security} 的 ordinal。 */
    public int targetSec = 3;
    public final List<Item> items = new ArrayList<>();

    /** 用目标快照重建 NetRecord（恢复补建任务时用）。 */
    public io.github.wlmosv_png.wpaforge.core.NetRecord toNetRecord() {
        io.github.wlmosv_png.wpaforge.core.NetRecord.Security[] all =
                io.github.wlmosv_png.wpaforge.core.NetRecord.Security.values();
        io.github.wlmosv_png.wpaforge.core.NetRecord.Security sec =
                (targetSec >= 0 && targetSec < all.length)
                        ? all[targetSec]
                        : io.github.wlmosv_png.wpaforge.core.NetRecord.Security.WPA2;
        return new io.github.wlmosv_png.wpaforge.core.NetRecord(
                targetSsid, targetBssid, targetRssi, targetFreq, sec, false, "");
    }

    // ================================================================ 状态机

    /** 一次状态转移的结果。 */
    public static final class Step {
        public final State state;
        public final int index;
        /** 是否前进到了下一本。 */
        public final boolean advanced;
        /** 整个计划是否结束。 */
        public final boolean terminal;
        /** 人读理由（写日志、给界面）。 */
        public final String why;

        Step(State s, int i, boolean adv, boolean term, String why) {
            this.state = s; this.index = i; this.advanced = adv;
            this.terminal = term; this.why = why;
        }
    }

    /**
     * 状态机（**纯函数**，可穷举）。规则表如下，全部有测试钉住：
     *
     * <table>
     *   <tr><th>本本结论</th><th>自动连跑</th><th>新状态</th><th>下标</th></tr>
     *   <tr><td>{@link Outcome#HIT} HIT</td><td>*</td>
     *       <td>{@link State#DONE_HIT}</td><td>不动</td></tr>
     *   <tr><td>{@link Outcome#BLOCKED}</td><td>*</td>
     *       <td>{@link State#BLOCKED}</td><td>不动</td></tr>
     *   <tr><td>{@link Outcome#ERROR}</td><td>*</td>
     *       <td>{@link State#ERROR}</td><td>不动</td></tr>
     *   <tr><td>{@link Outcome#STOPPED}</td><td>*</td>
     *       <td>{@link State#STOPPED}</td><td>不动</td></tr>
     *   <tr><td>{@link Outcome#PAUSED}</td><td>*</td>
     *       <td>{@link State#PAUSED}</td><td>不动</td></tr>
     *   <tr><td>{@link Outcome#INCOMPLETE}</td><td>*</td>
     *       <td>{@link State#PAUSED}</td><td>不动（绝不当前进）</td></tr>
     *   <tr><td>{@link Outcome#MISS}</td><td>开且有余本</td>
     *       <td>{@link State#RUNNING}</td><td>+1</td></tr>
     *   <tr><td>{@link Outcome#MISS}</td><td>开且无余本</td>
     *       <td>{@link State#DONE_MISS}</td><td>不动</td></tr>
     *   <tr><td>{@link Outcome#MISS}</td><td>关且有余本</td>
     *       <td>{@link State#PAUSED}</td><td>不动（等用户决定）</td></tr>
     *   <tr><td>{@link Outcome#MISS}</td><td>关且无余本</td>
     *       <td>{@link State#DONE_MISS}</td><td>不动</td></tr>
     * </table>
     *
     * <p><b>为什么「关自动连跑 + 有余本」落在 PAUSED 而不是 DONE_MISS</b>：
     * {@code DONE_MISS} 的语义是「计划内所有字典都跑完了，都没命中」。
     * 还有字典没跑就报 DONE_MISS，等于对用户说谎。PAUSED 才是实话 ——
     * 停在这本，用户点一下就能继续。而单字典场景（total=1）两者等价，
     * 所以「关闭自动连跑 = 与单字典模式一致」这条要求同时成立。
     *
     * @param cur   当前状态（用于区分「本来就没开始」等情形；不影响上表）
     * @param out   本本字典的结论
     * @param index 当前下标
     * @param total 字典总数
     * @param auto  是否开启自动连续执行
     */
    public static Step next(State cur, Outcome out, int index, int total, boolean auto) {
        if (out == null) out = Outcome.INCOMPLETE;
        int i = Math.max(0, Math.min(index, Math.max(0, total - 1)));
        boolean more = (i + 1) < total;
        switch (out) {
            case HIT:
                return new Step(State.DONE_HIT, i, false, true,
                        "命中：立即终止整个计划，不再执行后续字典");
            case BLOCKED:
                return new Step(State.BLOCKED, i, false, false,
                        "通道/环境阻塞：停在当前字典，不当作未命中");
            case ERROR:
                return new Step(State.ERROR, i, false, false,
                        "执行出错：停在当前字典，不自动前进");
            case STOPPED:
                return new Step(State.STOPPED, i, false, true,
                        "用户停止：不自动前进");
            case PAUSED:
                return new Step(State.PAUSED, i, false, false,
                        "已暂停：停留在当前字典");
            case INCOMPLETE:
                return new Step(State.PAUSED, i, false, false,
                        "本次未跑完：不得当作已确定未命中，停在当前字典可重跑");
            case MISS:
            default:
                if (!more) {
                    return new Step(State.DONE_MISS, i, false, true,
                            "计划内字典已全部跑完，未命中");
                }
                if (auto) {
                    return new Step(State.RUNNING, i + 1, true, false,
                            "本本未命中 → 自动执行下一本");
                }
                return new Step(State.PAUSED, i, false, false,
                        "本本未命中；自动连跑已关闭 → 停在当前字典，等用户决定");
        }
    }

    /**
     * 载入时的归一化：盘上是 {@link State#RUNNING} 说明上个进程**死在了执行中**。
     *
     * <p>归一为 {@link State#PAUSED} 并**留在当前字典**（不前进、不回退）。
     * 只有一件事是真的：这一本没跑完。所以它既不能算命中，也不能算未命中。
     * 与 {@code TaskQueue.normalizeInterrupted} 的既有语义一致。
     */
    public static State recover(State onDisk) {
        if (onDisk == State.RUNNING) return State.PAUSED;
        return onDisk == null ? State.IDLE : onDisk;
    }

    /**
     * 就地套用一步转移。
     *
     * <p><b>终态是绝对的</b>：一旦 {@link State#isTerminal()} 为真
     * （命中 / 用户停止 / 全部跑完），后续任何结论都**不再改变计划**。
     * 这条不靠调用方自觉 —— {@link State#DONE_HIT} 之后还会不会去跑下一本，
     * 是整个功能最硬的一条要求，所以防线放在状态机自己身上。
     */
    public Step apply(Outcome out) {
        if (state.isTerminal() || items.isEmpty()) {
            return new Step(state, index, false, state.isTerminal(),
                    "终态/空计划：不再接受新的结论");
        }
        Step s = next(state, out, index, items.size(), autoAdvance);

        // 本本的结论记在**本字典**上（不是记在「转移后的状态」上）：
        // 未命中要留在这一本，而不是被写成下一本的初始状态。
        Item done = items.get(Math.min(Math.max(index, 0), items.size() - 1));
        switch (out == null ? Outcome.INCOMPLETE : out) {
            case HIT:     done.local = State.DONE_HIT;  break;
            case MISS:    done.local = State.DONE_MISS; break;
            case PAUSED:
            case INCOMPLETE: done.local = State.PAUSED; break;
            case STOPPED: done.local = State.STOPPED;   break;
            case BLOCKED: done.local = State.BLOCKED;   break;
            case ERROR:   done.local = State.ERROR;     break;
            default:      break;
        }
        state = s.state;
        index = s.index;
        return s;
    }

    // ================================================================ 字典身份

    /**
     * 完整内容 SHA-256（小写十六进制）。失败返回空串。
     *
     * <p>流式读取，10 万行 / 1.7 MB 的字典在手机上约几十毫秒 —— 每个计划只算一次，
     * 代价可以接受。之所以不用「路径 + 大小 + 首行哈希」：那个组合有两个洞 ——
     * 同大小的两份不同字典会撞、首行相同而其余不同也会撞，撞了就会拿着
     * 别的字典的进度去跑。
     */
    public static String sha256Of(File f) {
        if (f == null || !f.isFile()) return "";
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(f);
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xf, 16))
                    .append(Character.forDigit(b & 0xf, 16));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 两份身份是否指同一本字典（内容 + 解析规则）。
     *
     * <p>空 SHA（读不到文件）一律判**不同** —— 宁可重跑，不要拿旧进度赌。
     */
    public static boolean sameIdentity(String shaA, int rulesA, String shaB, int rulesB) {
        if (shaA == null || shaB == null || shaA.isEmpty() || shaB.isEmpty()) return false;
        return shaA.equals(shaB) && rulesA == rulesB;
    }

    /** 按身份在新计划里找一本字典的下标；找不到返回 -1。 */
    public int indexOfIdentity(String sha, int rules) {
        for (int i = 0; i < items.size(); i++) {
            if (sameIdentity(items.get(i).sha256, items.get(i).parseRules, sha, rules)) return i;
        }
        return -1;
    }

    // ================================================================ 编辑

    /** 加一本；超过上限或重复（同身份）则不加。返回是否加成功。 */
    public boolean add(Item it) {
        if (it == null || items.size() >= MAX_ITEMS) return false;
        if (it.sha256 != null && !it.sha256.isEmpty()
                && indexOfIdentity(it.sha256, it.parseRules) >= 0) return false;
        items.add(it);
        clampIndex();
        return true;
    }

    public boolean removeAt(int i) {
        if (i < 0 || i >= items.size()) return false;
        items.remove(i);
        clampIndex();
        return true;
    }

    /** 上移 / 下移（交换顺序，进度与身份跟着条目走）。 */
    public boolean move(int i, int delta) {
        int j = i + delta;
        if (i < 0 || i >= items.size() || j < 0 || j >= items.size()) return false;
        Item a = items.get(i);
        items.set(i, items.get(j));
        items.set(j, a);
        if (index == i) index = j;
        else if (index == j) index = i;
        return true;
    }

    private void clampIndex() {
        if (items.isEmpty()) { index = 0; return; }
        if (index < 0) index = 0;
        if (index >= items.size()) index = items.size() - 1;
    }

    /** 当前字典（空计划返回 null）。 */
    public Item current() {
        if (items.isEmpty()) return null;
        return items.get(Math.min(Math.max(index, 0), items.size() - 1));
    }

    /** 整个计划的进度 0..1（按字典本数 + 当前本内部进度）。 */
    public float progress() {
        if (items.isEmpty()) return 0f;
        float base = index;
        Item it = current();
        if (it != null) base += it.progress();
        return Math.min(1f, base / items.size());
    }

    /** 「第 i+1 / 共 n 本」这类文案用的序号（1 起）。 */
    public int displayIndex() { return items.isEmpty() ? 0 : index + 1; }

    // ================================================================ 落盘

    /** 旧版单一计划文件名（迁移源）。 */
    public static final String LEGACY_FILE = "dictplan.json";
    /** 未绑定目标时的计划键。 */
    public static final String UNBOUND_KEY = "unbound";

    /**
     * 计划文件 —— **按目标键隔离**（与 triedSet / 账本同一套隔离语义）。
     *
     * <p>每个 Wi-Fi 目标一份计划：{@code plans/<targetKey>.plan.json}。
     * 切换目标不会丢失也不会串台 —— A 的计划与进度、B 的计划与进度互不相干。
     * {@code targetKey} 为空（还没选目标）时落到 {@value #UNBOUND_KEY}。
     */
    public static File fileFor(Context ctx, String targetKey) {
        if (ctx == null) return new File(LEGACY_FILE);
        String k = (targetKey == null || targetKey.trim().isEmpty()) ? UNBOUND_KEY : targetKey;
        return new File(new File(ctx.getFilesDir(), "plans"), k + ".plan.json");
    }

    /** 兼容旧调用：仍指向旧版单一计划文件（迁移用，别在生产路径里用）。 */
    public static File fileFor(Context ctx) {
        if (ctx == null) return new File(LEGACY_FILE);
        return new File(ctx.getFilesDir(), LEGACY_FILE);
    }

    public static File fileAt(File dir, String targetKey) {
        String k = (targetKey == null || targetKey.trim().isEmpty()) ? UNBOUND_KEY : targetKey;
        return new File(dir, k + ".plan.json");
    }

    /** 序列化（不含任何口令 —— 计划里本来就只有字典路径与进度）。 */
    public String toJsonString() {
        JSONObject o = new JSONObject();
        try {
            o.put("v", FORMAT_VERSION);
            o.put("rev", rev);
            o.put("planId", planId == null ? "" : planId);
            o.put("state", state.name());
            o.put("index", index);
            o.put("auto", autoAdvance);
            o.put("skipPolicy", skipPolicyId);
            o.put("forceRerun", forceRerun);
            o.put("targetKey", targetKey == null ? "" : targetKey);
            o.put("tSsid", targetSsid == null ? "" : targetSsid);
            o.put("tBssid", targetBssid == null ? "" : targetBssid);
            o.put("tRssi", targetRssi);
            o.put("tFreq", targetFreq);
            o.put("tSec", targetSec);
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject e = new JSONObject();
                e.put("path", it.path == null ? "" : it.path);
                e.put("name", it.name == null ? "" : it.name);
                e.put("sha", it.sha256 == null ? "" : it.sha256);
                e.put("rules", it.parseRules);
                e.put("offset", it.offset);
                e.put("size", it.dictSize);
                e.put("local", it.local.name());
                e.put("skipped", it.skipped);
                arr.put(e);
            }
            o.put("items", arr);
        } catch (Exception ignored) { }
        return o.toString();
    }

    /**
     * 反序列化。**任何异常、版本不符、结构异常一律返回空计划**。
     *
     * <p>为什么不是「尽力恢复一部分」：半个计划比没有计划更危险 ——
     * 它可能给出一个偏前的 offset，让本该重跑的候选被静默跳过。
     * 空计划只会让用户从头跑一遍。
     */
    public static DictPlan fromJsonString(String src) {
        DictPlan p = new DictPlan();
        if (src == null || src.trim().isEmpty()) return p;
        try {
            JSONObject o = new JSONObject(src);
            if (o.optInt("v", -1) != FORMAT_VERSION) return new DictPlan();
            p.rev = o.optInt("rev", 0);
            p.planId = o.optString("planId", "");
            p.state = State.valueOf(o.optString("state", State.IDLE.name()));
            p.index = o.optInt("index", 0);
            p.autoAdvance = o.optBoolean("auto", false);
            p.skipPolicyId = o.optInt("skipPolicy", 0);
            p.forceRerun = o.optBoolean("forceRerun", false);
            p.targetKey = o.optString("targetKey", "");
            p.targetSsid = o.optString("tSsid", "");
            p.targetBssid = o.optString("tBssid", "");
            p.targetRssi = o.optInt("tRssi", -70);
            p.targetFreq = o.optInt("tFreq", 2437);
            p.targetSec = o.optInt("tSec", 3);
            JSONArray arr = o.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length() && p.items.size() < MAX_ITEMS; i++) {
                    JSONObject e = arr.optJSONObject(i);
                    if (e == null) continue;
                    Item it = new Item();
                    it.path = e.optString("path", "");
                    it.name = e.optString("name", "");
                    it.sha256 = e.optString("sha", "");
                    it.parseRules = e.optInt("rules", PARSE_RULES_VERSION);
                    it.offset = Math.max(0, e.optInt("offset", 0));
                    it.dictSize = Math.max(0, e.optInt("size", 0));
                    try {
                        it.local = State.valueOf(e.optString("local", State.IDLE.name()));
                    } catch (Exception ex) { it.local = State.IDLE; }
                    it.skipped = Math.max(0, e.optInt("skipped", 0));
                    // offset 不能超过字典长度：越界说明文件被换过或写坏了，
                    // 保守归零（重跑这本），不要留下一个越界的死状态。
                    if (it.dictSize > 0 && it.offset > it.dictSize) it.offset = 0;
                    p.items.add(it);
                }
            }
            p.clampIndex();
        } catch (Throwable t) {
            return new DictPlan();
        }
        return p;
    }

    /**
     * 原子落盘：写 {@code .tmp} → {@code rename} 覆盖。
     *
     * <p>成功时 {@link #rev} 自增。**失败不改内存里的 rev**，
     * 这样调用方可以据此判断「盘上还是上一版」，而不是假装写成功了。
     */
    public boolean save(File f) {
        if (f == null) return false;
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return false;
            rev = rev + 1;
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(tmp), java.nio.charset.StandardCharsets.UTF_8)) {
                w.write(toJsonString());
                w.flush();
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                if (!tmp.renameTo(f)) {
                    rev = rev - 1;      // 没写成功就不装作写成功了
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static DictPlan load(File f) {
        if (f == null || !f.isFile() || f.length() <= 0 || f.length() > (1 << 20)) {
            return new DictPlan();
        }
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 14];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return fromJsonString(new String(bo.toByteArray(),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return new DictPlan();
        }
    }

    /** 删除指定目标的计划文件（用户点「清空计划」）。 */
    public static boolean delete(Context ctx, String targetKey) {
        File f = fileFor(ctx, targetKey);
        return !f.exists() || f.delete();
    }

    /** 兼容旧调用：删除旧版单一计划文件。 */
    public static boolean delete(Context ctx) {
        File f = fileFor(ctx);
        return !f.exists() || f.delete();
    }

    /**
     * 一次性迁移：旧版单一 {@code dictplan.json} → 按目标的 {@code plans/<key>.plan.json}。
     *
     * <p>只在目标文件不存在时执行；用旧计划内部已存的 {@code targetKey} 决定去向，
     * 拿不到就落 {@value #UNBOUND_KEY}。迁移后**不删旧文件**（保留可回退），
     * 后续读一律走按目标的新路径。
     *
     * @return 迁移到的目标键；没有旧文件返回 {@code null}。
     */
    public static String migrateLegacy(Context ctx) {
        if (ctx == null) return null;
        File legacy = fileFor(ctx);
        if (!legacy.isFile()) return null;
        DictPlan old = load(legacy);
        String key = (old.targetKey == null || old.targetKey.isEmpty())
                ? UNBOUND_KEY : old.targetKey;
        File dst = fileFor(ctx, key);
        if (dst.exists()) return key;   // 已迁移过，跳过
        try {
            if (dst.getParentFile() != null && !dst.getParentFile().exists()) {
                dst.getParentFile().mkdirs();
            }
            // 用纯字节流复制：android 的 java.nio.file.Files 是残缺子集，copy 未必可用。
            try (java.io.InputStream in = new java.io.FileInputStream(legacy);
                 java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[1 << 14];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return key;
        } catch (Throwable t) {
            return key;
        }
    }

    /**
     * 状态的**用户说法**。
     *
     * <p>界面与通知栏只出现这些词；{@code RUNNING} / {@code DONE_MISS}
     * 这类枚举名是内部词汇，不该出现在用户面前。
     */
    public static String stateLabel(State s) {
        if (s == null) return "未开始";
        switch (s) {
            case IDLE:      return "未开始";
            case RUNNING:   return "进行中";
            case PAUSED:    return "已暂停";
            case STOPPED:   return "已停止";
            case DONE_MISS: return "全部跑完，未命中";
            case DONE_HIT:  return "已命中";
            case BLOCKED:   return "被阻塞（环境不可用）";
            case ERROR:     return "出错";
            default:        return "未知";
        }
    }

    /** 一行摘要（日志 / 通知用，不含口令，也不含内部枚举名）。 */
    public String summary() {
        Item it = current();
        return L.f("计划第 %d/%d 本 · %s · 当前「%s」%s",
                "plan #%d of %d · %s · now \"%s\" %s",
                displayIndex(), items.size(), stateLabel(state),
                it == null ? "-" : it.name,
                it == null ? "" : (it.offset + "/" + it.dictSize));
    }
}
