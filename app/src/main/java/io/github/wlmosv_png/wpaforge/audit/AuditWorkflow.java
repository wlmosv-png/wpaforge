package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 审计工作台的状态机 —— 把「审计页现在能做什么、下一步是什么、为什么不能开始」
 * 从界面代码里抽出来，变成可离线测试的纯逻辑。
 *
 * <h3>要解决的六个问题（用户原话）</h3>
 * <ol>
 *   <li>不知道离线校验是什么；</li>
 *   <li>不知道操作顺序；</li>
 *   <li>不知道材料是否有有效；</li>
 *   <li>不知道当前到底使用哪本字典；</li>
 *   <li>不知道执行结果意味着什么；</li>
 *   <li>找不到停止与历史结果。</li>
 * </ol>
 * 前五个都由这里给出**确定答案**（步骤状态 + 阻断原因 + 总览行 + 字典快照），
 * 界面只负责画出来。
 *
 * <h3>五步工作流</h3>
 * <pre>
 *   ① 导入材料 → ② 材料诊断 → ③ 选择字典 → ④ 开始离线校验 → ⑤ 结果
 * </pre>
 * 每一步的 {@link StepState} 由真实数据推导，不由界面自己猜。
 *
 * <h3>字典一致性</h3>
 * 字典一律以 {@link DictSnapshot} 描述；{@link #beginRun()} 会把「本次要跑的候选」
 * 冻结成一个不可变副本，之后界面上再怎么换字典都动不了这次任务
 * （见 {@link Frozen}）。{@link #beginRun()} 在冻结前会再做一次
 * {@link DictSnapshot#matches} 校验，脱节就直接拒绝开跑 —— 宁可报错，
 * 也不跑一份不知道是什么的字典。
 *
 * <p>纯逻辑，无 Android 依赖，可离线测试。
 */
public final class AuditWorkflow {

    /**
     * 四步，顺序固定。
     *
     * <p>1.9.45 由「五步」并成「四步」：原来把「导入材料」和「材料诊断」拆成两步，
     * 界面上就变成两张卡 + 每条材料两条整行按钮，真机反馈是「很杂乱、不知道点哪」。
     * 现在 MATERIAL 一步同时表达「导入了没 / 选了一条没 / 那条能不能跑」。
     */
    public enum Step {
        MATERIAL, DICT, RUN, RESULT
    }

    /** 一步的展示状态。 */
    public enum StepState {
        /** 前置条件还没满足，做不了。 */
        TODO,
        /** 现在就该做这一步。 */
        READY,
        /** 正在进行。 */
        ACTIVE,
        /** 已完成。 */
        DONE,
        /** 被卡住了（材料有问题 / 字典为空）。 */
        BLOCKED
    }

    /** 总览行的语气（配色交给界面）。 */
    public enum Tone {
        NORMAL, OK, WARN, DANGER, DIM
    }

    /**
     * 「最近一次结果」与「当前工作区」是否一致。
     *
     * <h3>为什么必须显式算出来</h3>
     * 结果卡是常驻的（跨页面、跨重启），材料与字典却是随时会换的。只把两者
     * 并排显示，用户根本判断不出「刚才那个 1937/13255 到底跑的是不是我手上这份」——
     * 真机反馈就是这么来的。所以这里把结论算成一个确定的状态，界面直接写出来。
     */
    public enum Match {
        /** 还没跑过。 */
        NO_RESULT,
        /** 材料与字典都和当前一致。 */
        SAME,
        /** 材料换了（字典一致）。 */
        MATERIAL_CHANGED,
        /** 字典换了（材料一致）。 */
        DICT_CHANGED,
        /** 材料与字典都换了。 */
        BOTH_CHANGED
    }

    /** 总览里的一行：{@code 标签 + 取值 + 语气}。 */
    public static final class Row {
        public final String label;
        public final String value;
        public final Tone tone;

        public Row(String label, String value, Tone tone) {
            this.label = label == null ? "" : label;
            this.value = value == null ? "" : value;
            this.tone = tone == null ? Tone.NORMAL : tone;
        }

        @Override
        public String toString() {
            return label + "=" + value + "(" + tone + ")";
        }
    }

    /** 一步的展示信息。 */
    public static final class StepInfo {
        public final Step step;
        public final int ordinal;      // 1 起
        public final StepState state;
        public final String title;
        public final String detail;

        StepInfo(Step step, int ordinal, StepState state, String title, String detail) {
            this.step = step;
            this.ordinal = ordinal;
            this.state = state;
            this.title = title;
            this.detail = detail;
        }
    }

    /**
     * 一次任务的冻结包。
     *
     * <p>{@link #dict} 是开跑那一刻候选序列的**不可变副本**：任务是任务，
     * 界面是界面，中途换字典不影响已经跑起来的那一次。
     */
    public static final class Frozen {
        public final DictSnapshot snap;
        public final List<Candidate> dict;
        public final long startedAt;

        Frozen(DictSnapshot snap, List<Candidate> dict, long startedAt) {
            this.snap = snap;
            this.dict = Collections.unmodifiableList(dict);
            this.startedAt = startedAt;
        }

        public int candidateCount() {
            return dict.size();
        }

        /**
         * 把冻结快照交给引擎 —— 界面与测试走**同一条路**。
         *
         * <p>这样「界面显示 B、实际跑 A」这种问题在测试里就能被抓出来：
         * 测试直接读 {@link #dict} 与 {@link #snap}，而不是看界面文字。
         */
        public void run(AuditEngine engine, Hc22000.Entry e, AuditEngine.Progress cb) {
            if (engine == null || e == null || cb == null) return;
            try {
                if (e.isPmkid()) {
                    engine.runPmkid(Hc22000.toPmkid(e), dict, cb);
                } else {
                    engine.runHandshake(Hc22000.toHandshake(e), dict, cb);
                }
            } catch (Throwable t) {
                cb.onError(String.valueOf(t));
            }
        }
    }

    // ------------------------------------------------------------------ 状态

    // ---- 材料 ----
    private String matName = "";
    private long matBytes;
    private String matSha = "";
    private int matRecords, matPmkid, matEapol;
    /** 材料级不可用原因（读取 / 解析失败）；空串表示这一步没出错。 */
    private String matProblem = "";
    /** 已选中的材料条目下标；-1 = 还没选。 */
    private int matSelected = -1;
    /** 已选中条目的诊断结论（能不能跑）。 */
    private boolean matExecutable;
    /** 已选中条目的诊断摘要（缺什么 / 为什么不能跑）。 */
    private String matExecReason = "";
    /** 这份材料是**什么时候**导入的（epoch ms）。0 = 没导入过。 */
    private long matAt;

    // ---- 字典 ----
    private DictSnapshot snap = DictSnapshot.none();
    private List<Candidate> dict = new ArrayList<Candidate>();

    // ---- 运行 ----
    private Frozen frozen;

    // ------------------------------------------------------------------ 材料

    /** 开始读一份新材料：清掉上一份的一切痕迹。 */
    public void resetMaterial() {
        matName = "";
        matBytes = 0;
        matSha = "";
        matRecords = 0;
        matPmkid = 0;
        matEapol = 0;
        matProblem = "";
        matSelected = -1;
        matExecutable = false;
        matExecReason = "";
        matAt = 0;
    }

    /** 读取 / 解析失败。材料名与原因都要留下来，不能只剩一句「失败」。 */
    public void onMaterialProblem(String fileName, String problem) {
        resetMaterial();
        matName = nz(fileName);
        matProblem = nz(problem);
        matSelected = -1;
        matExecutable = false;
    }

    /** 解析成功。{@code entries} 条材料，选中状态清零，由调用方决定是否自动选第一条。 */
    public void onMaterialOk(String fileName, long bytes, String sha256,
                             int records, int pmkid, int eapol) {
        matName = nz(fileName);
        matBytes = Math.max(0, bytes);
        matSha = nz(sha256);
        matRecords = Math.max(0, records);
        matPmkid = Math.max(0, pmkid);
        matEapol = Math.max(0, eapol);
        matProblem = "";
        matSelected = -1;
        matExecutable = false;
        matExecReason = "";
        matAt = System.currentTimeMillis();
    }

    /** 这份材料是什么时候导入的（epoch ms）；0 = 没导入过。 */
    public long materialAt() {
        return matAt;
    }

    /** 用户显式清除材料（清完这一步回到「未导入」）。 */
    public void clearMaterial() {
        resetMaterial();
        selectedEntryIndexReset();
    }

    private void selectedEntryIndexReset() {
        matSelected = -1;
        matExecutable = false;
        matExecReason = "";
    }

    /** 选中第 {@code index} 条材料，并记下它的诊断结论。 */
    public void selectEntry(int index, boolean executable, String reason) {
        if (index < 0 || index >= matRecords) {
            matSelected = -1;
            matExecutable = false;
            matExecReason = "";
            return;
        }
        matSelected = index;
        matExecutable = executable;
        matExecReason = nz(reason);
    }

    public String materialName() {
        return matName;
    }

    public long materialBytes() {
        return matBytes;
    }

    public String materialSha256() {
        return matSha;
    }

    public int materialRecords() {
        return matRecords;
    }

    public int materialPmkid() {
        return matPmkid;
    }

    public int materialEapol() {
        return matEapol;
    }

    public String materialProblem() {
        return matProblem;
    }

    public int selectedIndex() {
        return matSelected;
    }

    public boolean materialExecutable() {
        return matExecutable;
    }

    public String materialExecReason() {
        return matExecReason;
    }

    /** 有没有成功导入一份（哪怕还不能跑）。 */
    public boolean materialImported() {
        return matRecords > 0 && matProblem.isEmpty();
    }

    // ------------------------------------------------------------------ 字典

    /**
     * 设置当前字典。
     *
     * @return true = 字典非空且已生效；false = 空字典（当前字典**不变**）
     */
    public boolean setDictionary(String name, String source, String path,
                                 List<Candidate> list) {
        if (list == null || list.isEmpty()) return false;
        DictSnapshot s = DictSnapshot.of(name, source, path, list);
        if (!s.consistent()) return false;
        dict = list;
        snap = s;
        return true;
    }

    /**
     * 派生结果落库。
     *
     * <p>与 {@link #setDictionary} 同一条路，单独命名是为了把语义写清楚：
     * <b>派生为空、派生失败、用户取消派生都不改变当前字典</b>，
     * 完全不会出现「派生失败把原来那本冲掉」。
     *
     * @return true = 已替换；false = 未生效，当前字典原样不动
     */
    public boolean applyDerived(String name, String source, String path,
                                List<Candidate> list) {
        return setDictionary(name, source, path, list);
    }

    /** 当前字典快照。未选择时是 {@link DictSnapshot#none()}。 */
    public DictSnapshot dictionary() {
        return snap;
    }

    /** 当前候选序列（只读视图；界面加载/派生后替换整个列表）。 */
    public List<Candidate> dictionaryList() {
        return Collections.unmodifiableList(dict);
    }

    public int dictionaryCount() {
        return dict.size();
    }

    /** 快照与候选序列是否仍然一致。 */
    public boolean dictionaryConsistent() {
        return snap.matches(dict);
    }

    /** 用户显式清除字典。 */
    public void clearDictionary() {
        dict = new ArrayList<Candidate>();
        snap = DictSnapshot.none();
    }

    // ------------------------------------------------------------------ 运行

    /**
     * 可不可以开始。
     *
     * @return 空串 = 可以开始；否则是**给用户看**的阻断原因
     */
    public String blocker() {
        if (frozen != null) {
            return L.s("已有一次比对在进行，先等它结束或点停止。",
                    "A comparison is already running - wait or stop it.");
        }
        if (matRecords <= 0 || !matProblem.isEmpty()) {
            if (!matProblem.isEmpty()) return matProblem;
            return L.s("还没有导入握手材料。先在上面第 1 步导入一份。",
                    "No capture imported yet - import one in step 1.");
        }
        if (matSelected < 0) {
            return matRecords > 1
                    ? L.s("这份文件里有多条材料，请在第 2 步选一条要校验的。",
                          "This file holds several entries - pick one in step 2.")
                    : L.s("请在第 2 步选一条材料。", "Pick an entry in step 2.");
        }
        if (!matExecutable) {
            return L.s("选中的材料不完整，不能执行：",
                    "The selected material is incomplete: ") + matExecReason;
        }
        if (snap.empty()) {
            return L.s("当前字典是空的。请在第 3 步选择或派生一本字典。",
                    "The dictionary is empty - pick or derive one in step 3.");
        }
        if (!dictionaryConsistent()) {
            return L.s("当前字典与显示的快照不一致，已阻止开跑（请重新选择字典）。",
                    "Dictionary and its snapshot disagree - run blocked (re-pick the dictionary).");
        }
        return "";
    }

    public boolean canStart() {
        return blocker().isEmpty();
    }

    /**
     * 冻结本次任务。返回 null 表示条件不满足（调用方用 {@link #blocker()} 拿原因）。
     */
    public Frozen beginRun() {
        if (frozen != null) return null;
        if (!canStart()) return null;
        Frozen f = new Frozen(snap, new ArrayList<Candidate>(dict), System.currentTimeMillis());
        if (!f.snap.matches(f.dict)) return null;   // 最后一道闸
        frozen = f;
        return f;
    }

    /** 任务结束（成功、未命中、取消、失败都要调 —— 否则永远卡在「运行中」）。 */
    public void endRun() {
        frozen = null;
    }

    public boolean running() {
        return frozen != null;
    }

    /** 当前冻结包；没在跑时为 null。 */
    public Frozen frozen() {
        return frozen;
    }

    // ------------------------------------------------------------------ 步骤与总览

    /** 某一步此刻的状态。 */
    public StepState stateOf(Step s, boolean hasVerdict) {
        switch (s) {
            case MATERIAL:
                if (!matProblem.isEmpty()) return StepState.BLOCKED;
                if (!materialImported()) return StepState.READY;
                if (matSelected < 0) return StepState.READY;
                return matExecutable ? StepState.DONE : StepState.BLOCKED;
            case DICT:
                if (!materialImported()) return StepState.TODO;
                if (snap.empty()) return StepState.READY;
                return StepState.DONE;
            case RUN:
                if (running()) return StepState.ACTIVE;
                if (snap.empty() || !materialImported()) return StepState.TODO;
                if (matSelected < 0 || !matExecutable) return StepState.TODO;
                return StepState.READY;
            case RESULT:
                if (running()) return StepState.TODO;
                return hasVerdict ? StepState.DONE : StepState.TODO;
            default:
                return StepState.TODO;
        }
    }

    /** 四步的展示信息（状态条副标题也从这里取）。 */
    public StepInfo[] steps(boolean hasVerdict) {
        StepInfo[] out = new StepInfo[4];
        out[0] = new StepInfo(Step.MATERIAL, 1, stateOf(Step.MATERIAL, hasVerdict),
                L.s("握手材料", "Handshake material"),
                !matProblem.isEmpty() ? matProblem
                        : (!materialImported()
                                ? L.s(".hc22000 / .22000", ".hc22000 / .22000")
                                : matName + " · " + matRecords + L.s(" 条", " entries")
                                  + (matSelected < 0
                                        ? L.s("（还没选一条）", " (no entry picked)")
                                        : (matExecutable ? "" : L.s("（不可执行）",
                                              " (not runnable)")))));
        out[1] = new StepInfo(Step.DICT, 2, stateOf(Step.DICT, hasVerdict),
                L.s("候选口令字典", "Candidate dictionary"),
                snap.empty()
                        ? L.s("还没有字典", "No dictionary yet")
                        : snap.name + " · " + snap.count + L.s(" 条 · 指纹 ", " entries · id ")
                          + snap.fingerprint());
        out[2] = new StepInfo(Step.RUN, 3, stateOf(Step.RUN, hasVerdict),
                L.s("开始离线校验", "Run the offline check"),
                running()
                        ? L.s("正在比对…", "Comparing...")
                        : (canStart() ? L.s("条件已满足", "Ready")
                                      : blocker()));
        out[3] = new StepInfo(Step.RESULT, 4, stateOf(Step.RESULT, hasVerdict),
                L.s("离线校验结果", "Offline check result"),
                hasVerdict ? L.s("最近一次结果已保存", "Last result is stored")
                           : L.s("还没有结果", "No result yet"));
        return out;
    }

    /**
     * 审计总览的行。
     *
     * <p>刻意**不做安全评分**：没有数据就写「未导入」「未跑过」，
     * 有异常就直接说异常是什么。
     *
     * @param last       最近一次结果（可为 {@code OfflineVerdict.none()}）
     * @param pendingWork 待处理的恢复 / 异常项数量
     */
    public List<Row> overview(OfflineVerdict last, int pendingWork) {
        List<Row> rows = new ArrayList<Row>();
        boolean hasVerdict = last != null && last.hasResult();

        // ① 当前状态
        String stateText;
        Tone stateTone;
        if (!matProblem.isEmpty()) {
            stateText = L.s("材料有问题，无法执行", "Material problem - cannot run");
            stateTone = Tone.DANGER;
        } else if (running()) {
            stateText = L.s("正在比对本机字典…", "Comparing against the local dictionary...");
            stateTone = Tone.NORMAL;
        } else if (blocker().isEmpty()) {
            stateText = L.s("可以开始离线校验", "Ready to run the offline check");
            stateTone = Tone.OK;
        } else if (!materialImported()) {
            stateText = L.s("等待导入材料", "Waiting for material");
            stateTone = Tone.DIM;
        } else if (snap.empty()) {
            stateText = L.s("等待选择字典", "Waiting for a dictionary");
            stateTone = Tone.DIM;
        } else {
            stateText = blocker();
            stateTone = Tone.WARN;
        }
        rows.add(new Row(L.s("当前状态", "State"), stateText, stateTone));

        // ② 当前字典 + 候选数量
        if (snap.empty()) {
            rows.add(new Row(L.s("当前字典", "Dictionary"),
                    L.s("未选择（去 ② 新建或挑一本）",
                        "none (create or pick one in 2)"), Tone.DIM));
        } else {
            String v = snap.name + " · " + snap.count + L.s(" 条", " entries");
            if (snap.present() && !snap.fingerprint().isEmpty()) {
                v += " · " + snap.fingerprint();
            }
            rows.add(new Row(L.s("当前字典", "Dictionary"), v, Tone.NORMAL));
        }

        // ③ 已导入材料
        if (!materialImported()) {
            rows.add(new Row(L.s("已导入材料", "Material"),
                    matProblem.isEmpty() ? L.s("未导入", "none") : matProblem,
                    matProblem.isEmpty() ? Tone.DIM : Tone.DANGER));
        } else {
            rows.add(new Row(L.s("已导入材料", "Material"),
                    matName + " · " + matRecords + L.s(" 条", " entries")
                            + "（PMKID " + matPmkid + " / EAPOL " + matEapol + "）",
                    Tone.NORMAL));
        }

        // ④ 最近一次离线校验
        if (!hasVerdict) {
            rows.add(new Row(L.s("最近一次校验", "Last check"),
                    L.s("未跑过", "not run yet"), Tone.DIM));
        } else {
            String v = verdictShort(last);
            rows.add(new Row(L.s("最近一次校验", "Last check"), v,
                    verdictTone(last.state)));
        }

        // ⑤ 待处理异常
        if (pendingWork > 0) {
            rows.add(new Row(L.s("待处理", "Needs attention"),
                    L.f("%d 项待处理（见下方「恢复状态」）",
                            "%d item(s) pending (see Restore state below)", pendingWork),
                    Tone.WARN));
        } else if (!matProblem.isEmpty()) {
            rows.add(new Row(L.s("待处理", "Needs attention"),
                    L.s("材料异常，需要重新导出或换一份材料",
                            "Material problem - re-export or pick another file"),
                    Tone.DANGER));
        } else {
            rows.add(new Row(L.s("待处理", "Needs attention"),
                    L.s("无", "none"), Tone.DIM));
        }
        return rows;
    }

    /**
     * 把「最近一次结果」与「当前工作区」比一比。
     *
     * @param curName 当前选中材料的文件名（没选就传空串）
     * @param curKind 当前选中材料的类型（PMKID / EAPOL；没选传空串）
     * @param curSsid 当前选中材料的 SSID
     * @param curApMac 当前选中材料的 AP MAC
     */
    public Match matchOf(OfflineVerdict last, String curName, String curKind,
                         String curSsid, String curApMac) {
        if (last == null || !last.hasResult()) return Match.NO_RESULT;
        boolean matSame = !nz(curKind).isEmpty()
                && nz(curName).equals(nz(last.fileName))
                && nz(curKind).equals(nz(last.materialKind))
                && nz(curSsid).equals(nz(last.ssid))
                && nz(curApMac).equals(nz(last.apMac));
        // 旧结果（v1）没有字典指纹 —— 无法证明字典变了，就不误报「字典已更换」。
        boolean dictSame = last.dictFingerprint.isEmpty()
                || (snap.present() && last.dictFingerprint.equals(snap.fingerprint()));
        if (matSame && dictSame) return Match.SAME;
        if (!matSame && !dictSame) return Match.BOTH_CHANGED;
        if (!matSame) return Match.MATERIAL_CHANGED;
        return Match.DICT_CHANGED;
    }

    /** 最近一次结果里那本字典的显示名（结果卡文案用）。 */
    public static String verdictDictLabel(OfflineVerdict v) {
        if (v == null) return "";
        return v.dictName.isEmpty() ? L.s("(未命名)", "(unnamed)") : v.dictName;
    }

    /** 当前字典的显示名（结果卡文案用）。 */
    public String currentDictLabel() {
        return snap.empty() ? L.s("(未选择)", "(none)")
                : snap.name + " · " + snap.fingerprint();
    }

    /** 结果卡里那句「这条结果当初用的是哪本字典」。 */
    public static String verdictDictLabelFull(OfflineVerdict v) {
        if (v == null) return "";
        String fp = v.dictFingerprint.isEmpty()
                ? L.s("(旧结果无指纹)", "(old result, no id)") : v.dictFingerprint;
        return verdictDictLabel(v) + " · " + v.dictSize + L.s(" 条", " entries")
                + " · " + fp;
    }

    /** 最近结果的短句（不含密码明文）。 */
    public String verdictShort(OfflineVerdict v) {
        if (v == null || !v.hasResult()) return L.s("未跑过", "not run yet");
        String head;
        switch (v.state) {
            case HIT: head = L.s("已命中", "hit"); break;
            case MISS: head = L.s("已完成，未命中", "done, no hit"); break;
            case CANCELLED: head = L.s("用户已取消", "cancelled by user"); break;
            case BAD_MATERIAL: head = L.s("材料不完整（未执行）", "material incomplete (not run)"); break;
            case FAILED: head = L.s("执行失败", "failed"); break;
            default: head = L.s("未跑过", "not run yet");
        }
        return head + " · " + v.tested + "/" + v.dictSize + L.s(" 条", " entries");
    }

    private static Tone verdictTone(OfflineVerdict.State st) {
        if (st == null) return Tone.DIM;
        switch (st) {
            case HIT: return Tone.OK;
            case MISS: return Tone.WARN;
            case CANCELLED: return Tone.NORMAL;
            case BAD_MATERIAL: return Tone.WARN;
            case FAILED: return Tone.DANGER;
            default: return Tone.DIM;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
