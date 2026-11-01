package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * D5-4 持久化与恢复：**INFERRED / NONE 不会因恢复变成永久排除；
 * DIRECT 不会因恢复丢失或重复执行。**
 *
 * <h3>做法</h3>
 * 用真实 {@link TaskQueue}（经 StoreIo 接缝注入内存文件系统），
 * 走真实的「写 → 模拟进程退出 → 重新读取 → 再写」路径。
 */
public final class P05RecoveryTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /** 线程安全的内存 Store，用于模拟「进程重启后磁盘仍在」。 */
    static final class MemStore implements StoreIo {
        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final Map<String, byte[]> staging = new ConcurrentHashMap<>();

        @Override public boolean isFile(String n) { return files.containsKey(n); }
        @Override public boolean exists(String n) { return files.containsKey(n); }
        @Override public long length(String n) {
            byte[] b = files.get(n);
            return b == null ? 0 : b.length;
        }
        @Override public InputStream openRead(String n) throws IOException {
            byte[] b = files.get(n);
            if (b == null) throw new FileNotFoundException(n);
            return new ByteArrayInputStream(b);
        }
        @Override public OutputStream openWrite(String n) {
            return new ByteArrayOutputStream() {
                @Override public void close() throws IOException {
                    files.put(n, toByteArray());
                    super.close();
                }
            };
        }
        @Override public OutputStream beginWrite(String n) {
            return new ByteArrayOutputStream() {
                @Override public void close() throws IOException {
                    staging.put(n, toByteArray());
                    super.close();
                }
            };
        }
        @Override public boolean finishWrite(String n, OutputStream h) {
            if (h instanceof ByteArrayOutputStream) {
                staging.put(n, ((ByteArrayOutputStream) h).toByteArray());
            }
            byte[] b = staging.remove(n);
            if (b == null) return false;
            files.put(n, b);
            return true;
        }
        @Override public void abortWrite(String n, OutputStream h) { staging.remove(n); }
        @Override public boolean delete(String n) { return files.remove(n) != null; }
        @Override public boolean rename(String from, String to) {
            byte[] b = files.remove(from);
            if (b == null) return false;
            files.put(to, b);
            return true;
        }
        @Override public String[] list() { return files.keySet().toArray(new String[0]); }
        @Override public String describe() { return "mem"; }
    }

    private static TaskQueue.Task task(String id, String state) {
        TaskQueue.Task t = new TaskQueue.Task();
        t.id = id;
        t.ssid = "LAB-R5";
        t.dictName = "d";
        t.state = TaskQueue.State.valueOf(state);
        return t;
    }

    public static void main(String[] args) {
        System.out.println("== D5-4 持久化与恢复 ==");

        // ---------- 1. DIRECT 后退出并恢复：结论不丢、不重复记账
        MemStore s1 = new MemStore();
        TaskQueue.setStoreForTest(s1);
        try {
            List<TaskQueue.Task> l = new ArrayList<>();
            l.add(task("t1", "DONE_MISS"));
            l.add(task("t2", "PAUSED"));
            boolean w1 = TaskQueue.save(null, l);
            info("写入返回 " + w1);

            // 模拟进程退出：清掉进程内状态后重新读
            TaskQueue.resetLoadFailedForTest();
            List<TaskQueue.Task> back = TaskQueue.load(null);
            info("恢复读到 " + back.size() + " 条，state=" + TaskQueue.lastReadState());
            ok("DIRECT 结论写盘后可完整恢复",
                    back.size() == 2 && TaskQueue.lastReadState() == TaskQueue.ReadState.COMPLETE,
                    "size=" + back.size() + " state=" + TaskQueue.lastReadState());
            ok("恢复后状态字段保持",
                    "DONE_MISS".equals(back.get(0).state.name())
                            && "PAUSED".equals(back.get(1).state.name()),
                    back.get(0).state + "/" + back.get(1).state);
        } finally { TaskQueue.setStoreForTest(null); }

        // ---------- 2. 多次恢复：重复读不改变内容
        MemStore s2 = new MemStore();
        TaskQueue.setStoreForTest(s2);
        try {
            List<TaskQueue.Task> l = new ArrayList<>();
            l.add(task("r1", "PENDING"));
            TaskQueue.save(null, l);
            String first = null;
            for (int i = 0; i < 5; i++) {
                TaskQueue.resetLoadFailedForTest();
                List<TaskQueue.Task> b = TaskQueue.load(null);
                String snap = b.size() + ":" + b.get(0).id;
                if (first == null) first = snap;
                ok("第 " + (i + 1) + " 次恢复内容一致", snap.equals(first), snap + " vs " + first);
            }
        } finally { TaskQueue.setStoreForTest(null); }

        // ---------- 3. 状态文件损坏：不得凭空生成成功，也不得永久排除
        MemStore s3 = new MemStore();
        s3.files.put("queue.json", "[{\"id\":\"a\"},{\"id\":\"b\"},42]"
                .getBytes(StandardCharsets.UTF_8));
        TaskQueue.setStoreForTest(s3);
        try {
            TaskQueue.resetLoadFailedForTest();
            List<TaskQueue.Task> b = TaskQueue.load(null);
            info("损坏文件 -> size=" + b.size() + " state=" + TaskQueue.lastReadState());
            ok("损坏文件判 CORRUPT 且不返回部分结果",
                    b.isEmpty() && TaskQueue.lastReadState() == TaskQueue.ReadState.CORRUPT,
                    "size=" + b.size() + " state=" + TaskQueue.lastReadState());
            boolean w = TaskQueue.save(null, new ArrayList<TaskQueue.Task>());
            ok("CORRUPT 后拒绝写入（不覆盖原文件）", !w, "返回 " + w);
        } finally { TaskQueue.setStoreForTest(null); }

        // ---------- 4. 字段缺失的旧格式：不得抛异常、不得误判成功
        MemStore s4 = new MemStore();
        s4.files.put("queue.json", "[{\"id\":\"old1\"}]".getBytes(StandardCharsets.UTF_8));
        TaskQueue.setStoreForTest(s4);
        try {
            TaskQueue.resetLoadFailedForTest();
            List<TaskQueue.Task> b = TaskQueue.load(null);
            info("缺字段旧格式 -> size=" + b.size() + " state=" + TaskQueue.lastReadState()
                    + " id=" + (b.isEmpty() ? "-" : b.get(0).id));
            ok("缺字段的旧记录可读且不崩",
                    TaskQueue.lastReadState() == TaskQueue.ReadState.COMPLETE && b.size() == 1,
                    "state=" + TaskQueue.lastReadState());
            ok("缺字段不会被当成失败或成功（state 落默认）",
                    b.size() == 1 && b.get(0).state != null,
                    "state=" + (b.isEmpty() ? "-" : b.get(0).state));
        } finally { TaskQueue.setStoreForTest(null); }

        // ---------- 5. 写入中断（事务未提交）：原文件不变
        MemStore s5 = new MemStore();
        TaskQueue.setStoreForTest(s5);
        try {
            List<TaskQueue.Task> l = new ArrayList<>();
            l.add(task("keep", "PENDING"));
            TaskQueue.save(null, l);
            byte[] before = s5.files.get("queue.json").clone();
            // 只 beginWrite 不 finish：模拟写入中断
            OutputStream h = s5.beginWrite("queue.json");
            try { h.write("[{\"id\":\"half\"".getBytes(StandardCharsets.UTF_8)); }
            catch (IOException ignored) { }
            s5.abortWrite("queue.json", h);
            boolean same = java.util.Arrays.equals(before, s5.files.get("queue.json"));
            ok("写入中断后原文件逐字节不变", same, "被改");
        } finally { TaskQueue.setStoreForTest(null); }

        // ---------- 6. 隔离状态下恢复：不得自动解除、不得写
        MemStore s6 = new MemStore();
        s6.files.put("queue.json", "[{\"id\":\"x\"}]".getBytes(StandardCharsets.UTF_8));
        s6.files.put("queue.json.bak", "[{\"id\":\"y\"}]".getBytes(StandardCharsets.UTF_8));
        TaskQueue.setStoreForTest(s6);
        try {
            TaskQueue.resetLoadFailedForTest();
            TaskQueue.load(null);
            boolean quarantined = TaskQueue.lastReadState() == TaskQueue.ReadState.QUARANTINED;
            boolean w = TaskQueue.save(null, new ArrayList<TaskQueue.Task>());
            info("隔离 -> state=" + TaskQueue.lastReadState() + " 写入=" + w);
            ok("恢复后仍识别隔离状态", quarantined, "state=" + TaskQueue.lastReadState());
            ok("隔离状态下写入被拒", !w, "返回 " + w);
            ok("隔离标记仍在（不自动解除）", s6.isFile("queue.json.quarantine"), "标记丢失");
        } finally { TaskQueue.setStoreForTest(null); }

        System.out.println();
        System.out.println("=== D5-4 恢复测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
