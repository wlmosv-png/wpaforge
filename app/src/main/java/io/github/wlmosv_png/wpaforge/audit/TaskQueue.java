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

    /** 读取整个队列 */
    public static List<Task> load(Context ctx) {
        List<Task> out = new ArrayList<>();
        File f = file(ctx);
        // 文件不存在 = 真空队列，合法
        if (!f.isFile()) { loadFailedFlag = false; return out; }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                out.add(Task.fromJson(arr.getJSONObject(i)));
            }
            loadFailedFlag = false;
        } catch (Exception e) {
            // **绝不能静默返回空**：调用方拿着空列表去 save，
            // 就等于把用户整个队列删了。
            loadFailedFlag = true;
            android.util.Log.e("WPAForge", "队列读取失败（不当作空队列）: " + f + " → " + e);
        }
        return out;
    }

    /** 写回整个队列 */
    public static boolean save(Context ctx, List<Task> tasks) {
        try {
            JSONArray arr = new JSONArray();
            for (Task t : tasks) arr.put(t.toJson());
            File f = file(ctx);
            // 读失败过还要写空？直接拒绝。
            // 否则一次临时的读失败（SELinux 标签、损坏、写到一半被杀）
            // 就会在用户点一下「删除」时把整个队列永久淹掉。
            if (refuseSave(tasks, loadFailedFlag)) {
                android.util.Log.e("WPAForge", "队列读取失败过，拒绝用空列表覆盖，避免误删");
                return false;
            }
            // 写空之前先备一份：即便真是用户清空，也还能把口令找回来。
            if (tasks.isEmpty() && f.isFile() && f.length() > 4) {
                try {
                    File bak = new File(ctx.getFilesDir(), FILE + ".bak");
                    java.io.InputStream in = new FileInputStream(f);
                    try {
                        java.io.OutputStream os = new FileOutputStream(bak);
                        try {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                        } finally { os.close(); }
                    } finally { in.close(); }
                    android.util.Log.w("WPAForge", "队列被清空，已备份为 " + bak);
                } catch (Exception ignored) {}
            }
            // 先写临时文件再改名，避免写一半崩溃导致队列损坏
            File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(arr.toString());
            }
            if (f.exists() && !f.delete()) {
                // 删不掉就直接覆盖
            }
            return tmp.renameTo(f) || f.exists();
        } catch (Exception e) {
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
        Task t = Task.from(target, dictPath, dictName, useTargetDict, useSmartSort, crackMode);
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
