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
        /** 已命中口令，空表示未命中 */
        public String hitPassword;
        public State state = State.PENDING;
        public long createdAt;
        public long elapsedMs;
        /** 本次实际试过多少条（不算续跑前的） */
        public int testedThisRun;
        /** 是否启用目标派生（入队时记录，执行时按此决定） */
        public boolean useTargetDict = false;
        /** 是否启用智能排序 */
        public boolean useSmartSort = false;
        /** 破解模式：0=Root，1=免Root（入队时记录） */
        public int crackMode = 0;

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
                o.put("hit", hitPassword == null ? "" : hitPassword);
                o.put("state", state.name());
                o.put("created", createdAt);
                o.put("elapsed", elapsedMs);
                o.put("tested", testedThisRun);
                o.put("useTargetDict", useTargetDict);
                o.put("useSmartSort", useSmartSort);
                o.put("crackMode", crackMode);
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
            t.hitPassword = o.optString("hit", "");
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
            return t;
        }
    }

    private static final String FILE = "queue.json";

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    /** 读取整个队列 */
    public static List<Task> load(Context ctx) {
        List<Task> out = new ArrayList<>();
        File f = file(ctx);
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                out.add(Task.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** 写回整个队列 */
    public static boolean save(Context ctx, List<Task> tasks) {
        try {
            JSONArray arr = new JSONArray();
            for (Task t : tasks) arr.put(t.toJson());
            File f = file(ctx);
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
}
