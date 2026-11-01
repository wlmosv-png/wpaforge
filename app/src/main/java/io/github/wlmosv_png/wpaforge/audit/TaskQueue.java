package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

/**
 * 破解任务队列，持久化到磁盘。
 *
 * 用于三件事：
 *  1. 多目标排队 —— 队列本身
 *  2. 断点续跑 —— 每个任务里的 offset 记录跑到哪了
 *  3. 后台运行 —— 服务被系统回收后能恢复现场
 *
 * 存储格式是 JSON（android 自带 org.json，无第三方依赖）。
 */
public final class TaskQueue {

    /** 任务状态 */
    public enum State {
        PENDING,     // 排队中
        RUNNING,     // 正在跑
        PAUSED,      // 暂停（可续跑）
        DONE_HIT,    // 完成，已命中
        DONE_MISS,   // 完成，未命中
        STOPPED      // 用户手动停止
    }

    /** 一个破解任务 */
    public static final class Task {
        public String id;
        public String ssid;
        public String bssid;
        public int rssi;
        public int frequencyMhz;
        /** 加密类型序号（对应 NetRecord.Security.ordinal） */
        public int securityOrdinal;
        /** 字典文件绝对路径，空表示用内置生成器 */
        public String dictPath;
        public String dictName;
        /** 字典总条数（加载后回填） */
        public int dictSize;
        /** 已测试到第几条 —— 断点续跑的关键字段 */
        public int offset;
        /** 已命中口令（**内存值**，空表示未命中）。落盘走 {@link #hitTag} + {@link #hitSeal}。 */
        public String hitPassword;
        /**
         * 命中口令的**脱敏标签**（R12）—— 落盘的是它，可对照、不可还原。
         * 例：{@code #29ff2d(len=9)}。
         */
        public String hitTag = "";
        /**
         * 命中口令的密文（R12）：{@link SecretBox} 的 Android Keystore AES/GCM 输出。
         * 空串 = 加密通道不可用 —— 此时**不落明文**，代价是重启后看不到口令。
         */
        public String hitSeal = "";
        /** 这条任务是不是从**旧格式（明文 hit）**读出来的 —— 迁移用，不落盘。 */
        public transient boolean legacyHitPlain = false;
        public State state = State.PENDING;
        public long createdAt;
        public long elapsedMs;
        /** 本次实际试过多少条（不算续跑前的） */
        public int testedThisRun;
        /**
         * 上次运行里**已证实失败**（AUTH_FAIL）的条数。
         * <p>与 {@link #missInconclusive} 一起回答：「未命中」到底是"试过了没有"，
         * 还是"根本没拿到证据"。旧数据缺这两个字段 → 0（表示未记录，不猜）。
         */
        public int missConfirmedFail;
        /**
         * 上次运行里**无结论**（TIMEOUT/UNKNOWN）的条数。
         * <p>&gt;0 时界面不得把这次结束说成"未命中"—— 那是证据不足，不是密码错。
         */
        public int missInconclusive;
        /** 是否启用目标派生（入队时记录，执行时按此决定） */
        public boolean useTargetDict = false;
        /** 是否启用智能排序 */
        public boolean useSmartSort = false;
        /**
         * 用户线索（手机号 / 生日 / 拼音 / 门牌 / 自由词）的编码串，见 SeedModel.encode()。
         *
         * 必须随任务落盘：续跑时要用**完全相同的规则**重建候选列表，
         * 否则 offset 会对不上（这条在 executeTask 里已经写着，这里是它的延伸）。
         * 空串表示没有线索 —— 此时行为与旧版本一致。
         */
        public String seedsJson = "";
        /** 破解模式：恒为 0=Root（历史字段，1/2 已在 1.9.24 废弃） */
        public int crackMode = 0;
        /**
         * 本轮是否**被进程中断**过（R7）。
         *
         * <p>进程被杀时盘上会留下 {@code RUNNING}；下次启动由
         * {@link TaskQueue#normalizeInterrupted} 归一为 PAUSED 并置本标志。
         * 注意：它只说明「上一轮没跑完」，**不代表**上一轮的实时证据仍然有效 ——
         * 续跑会用新的 runId 重新确立证据。旧数据没有这个字段 → 默认 false。
         */
        public boolean interrupted = false;
        /**
         * 上一次运行（run）的标识（R7）：续跑时作为**血缘**保留，
         * 只用于事后追问「这条结果出自哪一次运行」，新 run 绝不复用该 id。
         * 旧数据没有这个字段 → 默认空串。
         */
        public String lastRunId = "";

        public Task() {}

        public NetRecord toNetRecord() {
            NetRecord.Security[] secur = NetRecord.Security.values();
            NetRecord.Security sec = (securityOrdinal >= 0 && securityOrdinal < secur.length)
                    ? secur[securityOrdinal] : NetRecord.Security.WPA2;
            NetRecord r = new NetRecord(ssid, bssid, rssi, frequencyMhz, sec, false, "");
            return r;
        }

        public static Task from(NetRecord r, String dictPath, String dictName,
                                boolean useTargetDict, boolean useSmartSort, int crackMode) {
            return from(r, dictPath, dictName, useTargetDict, useSmartSort, crackMode, "");
        }

        /** 带线索的构造：seedsJson 见 {@link SeedModel#encode()}，空串表示无线索 */
        public static Task from(NetRecord r, String dictPath, String dictName,
                                boolean useTargetDict, boolean useSmartSort, int crackMode,
                                String seedsJson) {
            Task t = new Task();
            t.id = "t" + System.currentTimeMillis() + "_" + (int) (Math.random() * 1000);
            t.ssid = r.ssid;
            t.bssid = r.bssid;
            t.rssi = r.rssi;
            t.frequencyMhz = r.frequencyMhz;
            t.securityOrdinal = r.security.ordinal();
            t.dictPath = dictPath == null ? "" : dictPath;
            t.dictName = dictName == null ? "" : dictName;
            t.createdAt = System.currentTimeMillis();
            t.useTargetDict = useTargetDict;
            t.useSmartSort = useSmartSort;
            t.crackMode = crackMode;
            t.seedsJson = seedsJson == null ? "" : seedsJson;
            return t;
        }

        /** 进度 0..1 */
        public float progress() {
            if (dictSize <= 0) return 0f;
            return Math.min(1f, (float) offset / dictSize);
        }

        public String stateLabel() {
            switch (state) {
                case PENDING: return L.s("排队中", "Queued");
                case RUNNING: return L.s("进行中", "Running");
                case PAUSED: return L.s("已暂停", "Paused");
                case DONE_HIT: return L.s("已命中", "Hit");
                case DONE_MISS: return L.s("未命中", "No match");
                case STOPPED: return L.s("已停止", "Stopped");
                default: return "?";
            }
        }

        /**
         * 设置命中口令：同时算出脱敏标签与密文（R12 的唯一写入口）。
         *
         * <p>直接给 {@link #hitPassword} 赋值会让标签/密文不一致 ——
         * 所以所有生产路径都走这里，有测试钉住。
         */
        public void setHitPassword(String plain) {
            hitPassword = plain == null ? "" : plain;
            if (hitPassword.isEmpty()) {
                hitTag = "";
                hitSeal = "";
                return;
            }
            hitTag = io.github.wlmosv_png.wpaforge.ui.Redact.pw(hitPassword);
            String seal = SecretBox.seal(hitPassword);
            hitSeal = seal == null ? "" : seal;
        }

        /** 这条记录的落盘形态里是否还残留明文口令（迁移判定用）。 */
        public boolean hasPlaintextOnDisk() {
            return legacyHitPlain;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("ssid", ssid == null ? "" : ssid);
                o.put("bssid", bssid == null ? "" : bssid);
                o.put("rssi", rssi);
                o.put("freq", frequencyMhz);
                o.put("sec", securityOrdinal);
                o.put("dictPath", dictPath == null ? "" : dictPath);
                o.put("dictName", dictName == null ? "" : dictName);
                o.put("dictSize", dictSize);
                o.put("offset", offset);
                o.put("missFail", missConfirmedFail);
                o.put("missUnk", missInconclusive);
                // R12：**绝不写明文**。
                // 原来这一行把命中口令原样写进 queue.json —— 任务日志做过脱敏，
                // 队列却一直漏着，等于在磁盘上留一份长期口令清单。
                // 现在只写「脱敏标签 + 密文」，明文只活在内存里给界面用。
                o.put("hit", "");
                o.put("hitTag", hitTag == null ? "" : hitTag);
                o.put("hitSeal", hitSeal == null ? "" : hitSeal);
                o.put("state", state.name());
                o.put("created", createdAt);
                o.put("elapsed", elapsedMs);
                o.put("tested", testedThisRun);
                o.put("useTargetDict", useTargetDict);
                o.put("useSmartSort", useSmartSort);
                o.put("seeds", seedsJson == null ? "" : seedsJson);
                o.put("crackMode", crackMode);
                o.put("interrupted", interrupted);
                o.put("lastRunId", lastRunId == null ? "" : lastRunId);
            } catch (Exception ignored) {}
            return o;
        }

        public static Task fromJson(JSONObject o) {
            Task t = new Task();
            t.id = o.optString("id", "t" + System.currentTimeMillis());
            t.ssid = o.optString("ssid", "");
            t.bssid = o.optString("bssid", "");
            t.rssi = o.optInt("rssi", -60);
            t.frequencyMhz = o.optInt("freq", 2432);
            t.securityOrdinal = o.optInt("sec", NetRecord.Security.WPA2.ordinal());
            t.dictPath = o.optString("dictPath", "");
            t.dictName = o.optString("dictName", "");
            t.dictSize = o.optInt("dictSize", 0);
            t.offset = o.optInt("offset", 0);
            // 旧数据缺这两个字段 → 0（不猜）
            t.missConfirmedFail = o.optInt("missFail", 0);
            t.missInconclusive = o.optInt("missUnk", 0);
            // R12 读取：新格式看 hitSeal/hitTag；旧数据的 "hit" 是明文，读进内存过渡，
            // 由 PlaintextMigration 在下次启动时**先备份后重写**。
            t.hitSeal = o.optString("hitSeal", "");
            t.hitTag = o.optString("hitTag", "");
            String legacyHit = o.optString("hit", "");
            if (!legacyHit.isEmpty()) {
                t.setHitPassword(legacyHit);
                t.legacyHitPlain = true;
            } else if (!t.hitSeal.isEmpty()) {
                String pw = SecretBox.open(t.hitSeal);
                t.hitPassword = pw == null ? "" : pw;
            }
            try {
                t.state = State.valueOf(o.optString("state", "PENDING"));
            } catch (Exception e) {
                t.state = State.PENDING;
            }
            t.createdAt = o.optLong("created", System.currentTimeMillis());
            t.elapsedMs = o.optLong("elapsed", 0);
            t.testedThisRun = o.optInt("tested", 0);
            t.useTargetDict = o.optBoolean("useTargetDict", false);
            t.useSmartSort = o.optBoolean("useSmartSort", false);
            t.seedsJson = o.optString("seeds", "");
            t.crackMode = o.optInt("crackMode", 0);
            // 旧版本数据没有这两个字段 → 取默认值，不抛异常、不伪造
            t.interrupted = o.optBoolean("interrupted", false);
            t.lastRunId = o.optString("lastRunId", "");
            return t;
        }
    }

    // ---------------------------------------------------------------- R7 状态恢复

    /**
     * 进程重启后的**状态归一**：把盘上残留的 {@code RUNNING} 归为可恢复状态。
     *
     * <h3>为什么必须做</h3>
     * {@code RUNNING} 只由正在跑的服务写入（见 {@code CrackService.executeTask}）。
     * 进程被杀 / 服务被回收后没人再改它 —— 于是界面会**永久**显示「进行中」，
     * 而实际上没有任何线程在跑（审计 R7）。
     *
     * <h3>归类（三条都要明确）</h3>
     * <ul>
     *   <li><b>可恢复</b>：{@code RUNNING → PAUSED}（offset 原样保留，点「继续」即可续跑）</li>
     *   <li><b>必须标为中断</b>：{@code interrupted = true}（用户能看到「上一轮被中断」）</li>
     *   <li><b>必须重新验证</b>：续跑不继承上一轮的实时证据 —— 由新的 runId +
     *       每个候选自己的窗口标记重新确立（见 {@code AttemptMarker}）</li>
     * </ul>
     *
     * <p>幂等：第二次调用不会再把 PAUSED 改回什么，也不会重复计数。
     *
     * @return 被归一的条数（0 = 没有残留的 RUNNING）
     */
    public static int normalizeInterrupted(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) return 0;
        int n = 0;
        for (Task t : tasks) {
            if (t == null) continue;
            if (t.state == State.RUNNING) {
                t.state = State.PAUSED;
                t.interrupted = true;
                n++;
            }
        }
        return n;
    }

    private static final String FILE = "queue.json";

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    // ------------------------------------------------------------------ 存储接缝（P0-0）

    /**
     * 测试注入的存储实现；{@code null} = 使用生产实现。
     *
     * <p>包级可见，生产代码在包内也没有调用者（只有本类与测试会用）。
     * 默认 null 时行为与改造前完全一致。
     */
    private static volatile StoreIo testStore;

    /**
     * **仅供测试**：注入存储实现。传 {@code null} 恢复生产实现。
     *
     * <p>它只替换「字节怎么进出」，不改变 {@link #load}/{@link #save} 的任何判断逻辑。
     */
    static void setStoreForTest(StoreIo io) {
        testStore = io;
    }

    /** 当前生效的存储实现（注入优先）。 */
    private static StoreIo store(Context ctx) {
        StoreIo injected = testStore;
        if (injected != null) return injected;
        return new StoreIo.FileImpl(ctx.getFilesDir());
    }

    // 上一次 load 是不是**读失败**（而不是队列真的空）
    //
    // 2026-10-23 真事：队列里 16 个任务（含 5 条已爆破出的口令）被写成了 `[]`。
    // 链条是这样的：
    //   queue.json 读不到（比如 SELinux 标签不对）
    //     → load() 静默吞掉异常、返回空列表
    //     → 调用方（删除、清理、入队都一样）拿着空列表回写
    //     → 整个队列**永久消失**
    // 所以 load 失败必须留下痕迹，并且**拒绝接下来的空写入**。
    private static volatile boolean loadFailedFlag = false;

    /** 上一次 load 是否读失败 */
    public static boolean lastLoadFailed() { return loadFailedFlag; }

    /**
     * **仅供测试**：复位读取失败标志。
     *
     * <p>生产代码没有任何调用者；它只是让测试能在同一进程内构造
     * 「先失败、再成功」的序列。默认值本来就是 false，因此复位不改变生产语义。
     */
    static void resetLoadFailedForTest() { loadFailedFlag = false; }

    /**
     * 纯逻辑：这次写盘要不要拒绝。
     *
     * <p>只有「刚刚读失败过」+「要写空列表」同时成立才拒绝 ——
     * 用户自己清空队列（读取正常）不受影响，不能把正常功能也堵了。
     */
    public static boolean refuseSave(List<?> tasks, boolean loadFailed) {
        return loadFailed && (tasks == null || tasks.isEmpty());
    }

    /**
     * 原始 queue.json 文本里是否还有**明文命中口令**（纯函数，可离线断言）。
     *
     * <p>新格式永远写 {@code "hit":""}，所以只要出现非空的 {@code "hit":"…"}，
     * 就说明这份数据是旧版本留下的。
     */
    public static boolean hasLegacyPlaintextHit(String rawJson) {
        if (rawJson == null || rawJson.isEmpty()) return false;
        int at = 0;
        while ((at = rawJson.indexOf("\"hit\"", at)) >= 0) {
            int colon = rawJson.indexOf(':', at);
            if (colon < 0) return false;
            int q = rawJson.indexOf('"', colon + 1);
            if (q < 0) return false;
            if (q + 1 < rawJson.length() && rawJson.charAt(q + 1) != '"') return true;
            at = colon + 1;
        }
        return false;
    }

    // ------------------------------------------------------------------ 四态读取（P0-2a）

    /**
     * 一次读取的**完整状态**。
     *
     * <h3>为什么必须区分这四种</h3>
     * 旧实现只有「读到 / 没读到」两种表现，且都返回空列表 ——
     * 于是「文件本来就不存在」「文件坏了」「读不到（权限/IO）」在调用方眼里
     * **完全一样**，任何一次读失败后紧跟的写入都会把原文件覆盖掉。
     *
     * <ul>
     *   <li>{@link #COMPLETE} —— 存在、读全、解析全成功。**唯一允许据此写回的「有数据」状态。**</li>
     *   <li>{@link #ABSENT}   —— 确认文件不存在。写空是合法的（初始化新队列）。</li>
     *   <li>{@link #CORRUPT}  —— 存在但内容为空/非法/截断/部分元素损坏。**禁止覆盖。**</li>
     *   <li>{@link #IO_ERROR} —— 读的过程中发生真实 IO 异常。**禁止覆盖。**</li>
     * </ul>
     */
    public enum ReadState {
        COMPLETE, ABSENT, CORRUPT, IO_ERROR,
        /**
         * 已隔离（P0-2d）：base 与 .bak 同时存在，无法判断哪份可信。
         *
         * <p>两份原始文件**原样保留**，禁止任何写入，直到用户显式选择恢复来源。
         * 该状态由磁盘上的隔离标记持久化，**进程重启后仍然有效**。
         */
        QUARANTINED
    }

    /** 一次读取的结果（状态 + 数据 + 诊断）。 */
    public static final class ReadResult {
        public final ReadState state;
        /** COMPLETE 时是全部任务；其余状态一律为空列表（绝不给「部分结果」）。 */
        public final List<Task> tasks;
        /** 诊断串（不含敏感数据，不进任何判定）。 */
        public final String detail;

        ReadResult(ReadState state, List<Task> tasks, String detail) {
            this.state = state;
            this.tasks = tasks;
            this.detail = detail == null ? "" : detail;
        }

        public boolean usable() { return state == ReadState.COMPLETE; }
    }

    /** 最近一次读取的状态。**生产判定用它，不再依赖裸 boolean。** */
    private static volatile ReadState lastReadState = ReadState.ABSENT;

    /** 最近一次读取的状态（排查与测试用）。 */
    public static ReadState lastReadState() { return lastReadState; }

    private static ReadResult corrupt(String detail) {
        return new ReadResult(ReadState.CORRUPT, new ArrayList<Task>(), detail);
    }

    private static ReadResult ioError(String detail) {
        return new ReadResult(ReadState.IO_ERROR, new ArrayList<Task>(), detail);
    }

    // ------------------------------------------------------------------ 隔离（P0-2d）

    /** 隔离标记文件名。 */
    static final String QUARANTINE = FILE + ".quarantine";
    /** AtomicFile 会优先使用的事务备份名；与应用层冲突，正是隔离的触发条件。 */
    static final String LEGACY_BAK = FILE + ".bak";

    /** 是否已被隔离（只看标记，不碰数据文件）。 */
    static boolean isQuarantined(StoreIo io) {
        return io.isFile(QUARANTINE);
    }

    /**
     * 是否需要进入隔离：base 与 .bak **同时存在**。
     *
     * <p>不判断哪份更新 —— 无法证明的事不能赌（M4 裁定）。
     */
    static boolean needsQuarantine(StoreIo io) {
        return io.isFile(FILE) && io.isFile(LEGACY_BAK);
    }

    /** 读全部字节并算 SHA-256；失败返回 null。 */
    static String sha256Of(StoreIo io, String name) {
        try (java.io.InputStream in = io.openRead(name)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 建立隔离标记。**必须先于任何文件移动/隔离动作**。
     *
     * @return null = 成功；否则为失败原因
     */
    static String tryQuarantine(StoreIo io) {
        String baseSha = sha256Of(io, FILE);
        String bakSha = sha256Of(io, LEGACY_BAK);
        if (baseSha == null || bakSha == null) {
            return "无法读取原始文件以计算校验值（base=" + baseSha + " bak=" + bakSha + "）";
        }
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("store", FILE);
            o.put("reason", "BASE_AND_BAK");
            o.put("since", System.currentTimeMillis());
            org.json.JSONArray arr = new org.json.JSONArray();
            org.json.JSONObject b1 = new org.json.JSONObject();
            b1.put("role", "base");
            b1.put("name", FILE);
            b1.put("size", io.length(FILE));
            b1.put("sha256", baseSha);
            arr.put(b1);
            org.json.JSONObject b2 = new org.json.JSONObject();
            b2.put("role", "bak");
            b2.put("name", LEGACY_BAK);
            b2.put("size", io.length(LEGACY_BAK));
            b2.put("sha256", bakSha);
            arr.put(b2);
            o.put("entries", arr);
        } catch (Throwable t) {
            return "构造标记失败: " + t;
        }
        String payload = o.toString();
        try (java.io.OutputStream os = io.openWrite(QUARANTINE)) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return "写标记失败: " + t;
        }
        // 回读校验：写不进去等于没隔离，绝不能当作成功
        String back = null;
        try (java.io.InputStream in = io.openRead(QUARANTINE)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            back = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "回读标记失败: " + t;
        }
        if (!payload.equals(back)) return "标记回读不一致（写入不完整）";
        return null;
    }

    /**
     * 读取队列的**唯一实现**：状态判定 + 全量解析。
     *
     * <p><b>部分解析必须整体失败</b>：任何一个元素坏掉，整个文件判 CORRUPT，
     * 不返回「前缀」。因为调用方拿到前缀后会原样写回，等于把尾部任务永久删除。
     */
    static ReadResult read(StoreIo io) {
        // ---- 0. 隔离检查（先于一切读取）
        if (isQuarantined(io)) {
            return new ReadResult(ReadState.QUARANTINED,
                    new ArrayList<Task>(), "已隔离，等待用户选择恢复来源");
        }
        if (needsQuarantine(io)) {
            String err = tryQuarantine(io);
            if (err == null) {
                return new ReadResult(ReadState.QUARANTINED,
                        new ArrayList<Task>(), "base 与 .bak 同时存在，已隔离（两份原样保留）");
            }
            // 隔离失败 = 无法保证原文件安全 → 一律按 IO_ERROR 处理，禁止后续写入
            return ioError("隔离失败，拒绝继续: " + err);
        }

        // ---- 1. 存在性
        if (!io.isFile(FILE)) {
            // 存在但不是普通文件（目录/特殊文件）→ 内容不可信
            if (io.exists(FILE)) {
                return corrupt(FILE + " 存在但不是普通文件");
            }
            return new ReadResult(ReadState.ABSENT, new ArrayList<Task>(), "文件不存在");
        }

        // ---- 2. 读字节
        String text;
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    io.openRead(FILE), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            text = sb.toString();
        } catch (java.io.FileNotFoundException e) {
            // FNFE **不能无条件当 ABSENT**：文件明明在，却报找不到 = 访问受限
            if (io.exists(FILE)) {
                return ioError("存在但打开时报 FileNotFound: " + e.getMessage());
            }
            return new ReadResult(ReadState.ABSENT, new ArrayList<Task>(), "读取时文件已不存在");
        } catch (java.io.IOException e) {
            return ioError("IO 异常: " + e);
        } catch (Throwable t) {
            return ioError("读取异常: " + t);
        }

        // ---- 3. 内容判定
        if (text.trim().isEmpty()) {
            // 空文件 / 纯空白：与「合法的空数组 []」必须区分
            return corrupt("内容为空（区别于合法的空数组）");
        }
        JSONArray arr;
        try {
            arr = new JSONArray(text);
        } catch (Throwable t) {
            return corrupt("JSON 非法: " + t);
        }
        List<Task> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            try {
                out.add(Task.fromJson(arr.getJSONObject(i)));
            } catch (Throwable t) {
                // 任一元素坏 → 整体 CORRUPT，**不返回前缀**
                return corrupt("第 " + i + " 个元素非法: " + t);
            }
        }
        return new ReadResult(ReadState.COMPLETE, out, "ok");
    }

    /** 读取整个队列（对外兼容入口：返回列表，状态经 {@link #lastReadState()} 查询）。 */
    public static List<Task> load(Context ctx) {
        ReadResult r = read(store(ctx));
        lastReadState = r.state;
        // 兼容旧语义：只有 COMPLETE/ABSENT 不算「读失败」
        loadFailedFlag = (r.state != ReadState.COMPLETE && r.state != ReadState.ABSENT);
        if (r.state != ReadState.COMPLETE && r.state != ReadState.ABSENT) {
            android.util.Log.e("WPAForge",
                    "队列读取失败（不当作空队列）: state=" + r.state
                            + " " + ctx(store(ctx)) + "/" + FILE + " → " + r.detail);
        }
        return r.tasks;
    }

    private static String ctx(StoreIo io) { return io == null ? "?" : io.describe(); }

    /**
     * 写回整个队列。
     *
     * <h3>写回前安全闸门（P0-2b）</h3>
     * <b>闸门判断依据是「本次写入前自己读到的状态」，不是调用方先前的 load 结果。</b>
     * 这样才能挡住「读失败 → 调用方拿空列表 → 写回」这条覆盖链，
     * 也不会被调用方之间的时序差异绕过。
     *
     * <pre>
     *   COMPLETE  → 允许写
     *   ABSENT    → 允许写（初始化新队列）
     *   CORRUPT   → 拒绝（原文件可能还有救，绝不覆盖）
     *   IO_ERROR  → 拒绝（读都读不到，写入等于赌命）
     * </pre>
     */
    public static boolean save(Context ctx, List<Task> tasks) {
        if (tasks == null) return false;
        try {
            StoreIo io = store(ctx);

            // ---- 闸门：以本次读取的真实状态为准
            ReadResult gate = read(io);
            lastReadState = gate.state;
            loadFailedFlag = (gate.state == ReadState.CORRUPT
                    || gate.state == ReadState.IO_ERROR);
            // 白名单：只有 COMPLETE / ABSENT 允许写。
            // 新增状态（QUARANTINED 等）默认拒绝 —— 宁可拒绝一次合法写入，
            // 也不能在无法证明原始数据安全的情况下覆盖用户数据。
            if (gate.state != ReadState.COMPLETE && gate.state != ReadState.ABSENT) {
                android.util.Log.e("WPAForge",
                        "拒绝写回：队列状态 " + gate.state + "（" + gate.detail + "）");
                return false;
            }

            JSONArray arr = new JSONArray();
            for (Task t : tasks) arr.put(t.toJson());
            // 二次防线（保留旧语义，QueueGuardTest 覆盖）
            if (refuseSave(tasks, loadFailedFlag)) {
                android.util.Log.e("WPAForge", "队列读取失败过，拒绝用空列表覆盖，避免误删");
                return false;
            }
            // ---- 写空之前先备一份（P0-3：**不再使用 <base>.bak**）
            //
            // 为什么改名：android.util.AtomicFile 把 `<base>.bak` 当作它自己的
            // 事务备份，并在 openRead 时**优先用它覆盖 base**。应用层若继续写
            // 同名文件，两套机制会互相吞掉对方的数据（已实测，见设计文档 §0.2）。
            // 因此应用层快照改用 `.snapshot-<ts>`，与 AtomicFile 的文件空间完全隔离。
            if (tasks.isEmpty() && io.isFile(FILE) && io.length(FILE) > 4) {
                String snapName = snapshotName(io);
                if (!copyFile(io, FILE, snapName)) {
                    // 备份失败必须明确处理：宁可拒绝这次清空，也不静默丢保护能力
                    android.util.Log.e("WPAForge", "清空前快照失败，拒绝本次清空: " + snapName);
                    return false;
                }
                android.util.Log.w("WPAForge", "队列被清空，已快照为 " + snapName);
            }

            // ---- 事务写（P0-3）：startWrite → 写 → finishWrite / failWrite
            java.io.OutputStream handle = null;
            try {
                handle = io.beginWrite(FILE);
                java.io.OutputStreamWriter w =
                        new java.io.OutputStreamWriter(handle, StandardCharsets.UTF_8);
                w.write(arr.toString());
                w.flush();
                // 注意：**不能 close**，句柄要交给 finishWrite 处理
            } catch (Throwable t) {
                if (handle != null) io.abortWrite(FILE, handle);
                android.util.Log.e("WPAForge", "写入事务失败（已回滚）: " + t);
                return false;
            }
            if (!io.finishWrite(FILE, handle)) {
                io.abortWrite(FILE, handle);
                android.util.Log.e("WPAForge", "提交事务失败（已回滚），原文件保持不变");
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 快照文件名：`.snapshot-<毫秒>`，并保证不与已有文件重名（防时间戳碰撞）。 */
    static String snapshotName() {
        long ts = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            String name = FILE + ".snapshot-" + ts + (i == 0 ? "" : "-" + i);
            // 冲突检查交给调用方（需要 io），这里只给出候选；实际由 snapshotName(io) 保证
            return name;
        }
        return FILE + ".snapshot-" + ts;
    }

    /** 带冲突检测的快照命名（供 save 使用）。 */
    static String snapshotName(StoreIo io) {
        long ts = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            String name = FILE + ".snapshot-" + ts + (i == 0 ? "" : "-" + i);
            if (!io.exists(name) && !io.isFile(name)) return name;
        }
        return FILE + ".snapshot-" + ts + "-overflow";
    }

    /** 复制文件（用流，保证跨实现可用）；失败返回 false。 */
    static boolean copyFile(StoreIo io, String from, String to) {
        try (java.io.InputStream in = io.openRead(from);
             java.io.OutputStream os = io.openWrite(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 加一个任务到队尾 */
    /** 兼容旧调用：默认两个开关都关 */
    public static Task enqueue(Context ctx, NetRecord target, String dictPath, String dictName) {
        return enqueue(ctx, target, dictPath, dictName, false, false, 0);
    }

    public static Task enqueue(Context ctx, NetRecord target, String dictPath, String dictName,
                               boolean useTargetDict, boolean useSmartSort) {
        return enqueue(ctx, target, dictPath, dictName, useTargetDict, useSmartSort, 0);
    }

    public static Task enqueue(Context ctx, NetRecord target, String dictPath, String dictName,
                               boolean useTargetDict, boolean useSmartSort, int crackMode) {
        return enqueue(ctx, target, dictPath, dictName, useTargetDict, useSmartSort, crackMode, "");
    }

    /** 带线索入队。seedsJson 必须落盘 —— 续跑要靠它重建同一份候选列表。 */
    public static Task enqueue(Context ctx, NetRecord target, String dictPath, String dictName,
                               boolean useTargetDict, boolean useSmartSort, int crackMode,
                               String seedsJson) {
        List<Task> list = load(ctx);
        // 同一个目标 + 同一个字典，避免重复排队
        for (Task t : list) {
            if (t.ssid.equals(target.ssid)
                    && t.dictPath.equals(dictPath == null ? "" : dictPath)
                    && (t.state == State.PENDING || t.state == State.RUNNING
                        || t.state == State.PAUSED)) {
                return t;
            }
        }
        Task t = Task.from(target, dictPath, dictName, useTargetDict, useSmartSort, crackMode,
                seedsJson);
        list.add(t);
        save(ctx, list);
        return t;
    }

    /** 取下一个待处理任务（跳过已完成的） */
    public static Task nextPending(Context ctx) {
        for (Task t : load(ctx)) {
            if (t.state == State.PENDING || t.state == State.PAUSED
                    || t.state == State.RUNNING) {
                return t;
            }
        }
        return null;
    }

    /** 更新某个任务（按 id 匹配） */
    public static boolean update(Context ctx, Task task) {
        List<Task> list = load(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(task.id)) {
                list.set(i, task);
                return save(ctx, list);
            }
        }
        list.add(task);
        return save(ctx, list);
    }

    /** 删除某个任务 */
    public static boolean remove(Context ctx, String id) {
        List<Task> list = load(ctx);
        List<Task> out = new ArrayList<>();
        for (Task t : list) if (!t.id.equals(id)) out.add(t);
        return save(ctx, out);
    }

    /** 清空已完成/已停止的任务 */
    public static int clearFinished(Context ctx) {
        List<Task> list = load(ctx);
        List<Task> out = new ArrayList<>();
        int removed = 0;
        for (Task t : list) {
            if (t.state == State.DONE_HIT || t.state == State.DONE_MISS
                    || t.state == State.STOPPED) {
                removed++;
            } else {
                out.add(t);
            }
        }
        save(ctx, out);
        return removed;
    }

    /** 队列里是否还有活干 */
    public static boolean hasWork(Context ctx) {
        for (Task t : load(ctx)) {
            if (t.state == State.PENDING || t.state == State.PAUSED
                    || t.state == State.RUNNING) {
                return true;
            }
        }
        return false;
    }

    /**
     * 只更新进度字段（offset / elapsedMs），**不碰 state**。
     *
     * <p>为什么必须单独开一个入口：任务运行期间盘上的 state 可能已经被 UI 改过
     * （用户点暂停 / 继续）。拿内存里那个**任务开始时的快照**整对象回写，
     * 就会把用户刚点出来的新状态覆盖回旧值 —— 实测表现就是
     * 「暂停收尾期点了继续，却没反应」（2026-10-23 08:52:38 实机）。
     */
    public static boolean updateProgress(Context ctx, String id, int offset, long elapsedMs) {
        List<Task> list = load(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.get(i).offset = offset;
                list.get(i).elapsedMs = elapsedMs;
                return save(ctx, list);
            }
        }
        return false;
    }

    /**
     * 是否**确实有人点了继续**（只有 PENDING 才算）。
     *
     * <p>与 {@link #hasWork} 的区别：hasWork 把 PAUSED / RUNNING 也算「有事可做」，
     * 拿它判断「要不要补一次启动」会把普通的重复 start 也算进去，导致空转。
     */
    public static boolean hasPending(Context ctx) {
        for (Task t : load(ctx)) {
            if (t.state == State.PENDING) return true;
        }
        return false;
    }
}
