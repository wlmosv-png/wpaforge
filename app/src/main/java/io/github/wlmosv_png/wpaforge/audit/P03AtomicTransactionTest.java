package io.github.wlmosv_png.wpaforge.audit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P0-3 事务写故障注入测试。
 *
 * <h3>覆盖的 8 类场景</h3>
 * <ol>
 *   <li>写入前失败（beginWrite 抛）</li>
 *   <li>写入过程中失败（finishWrite 返回 false）</li>
 *   <li>finishWrite 之前中断（事务未提交）</li>
 *   <li>写入完成后"重启"（新 Store 实例 / 重新读取）</li>
 *   <li>备份（快照）产生与保留</li>
 *   <li>磁盘写入异常</li>
 *   <li>已有隔离标记时尝试写入</li>
 *   <li>连续多次写入及失败后恢复</li>
 * </ol>
 *
 * <h3>断言口径</h3>
 * 全部断言**最终文件内容**（逐字节 / 解析出的任务 id），
 * 不使用「文件是否存在」作为唯一判据。
 */
public final class P03AtomicTransactionTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    // ------------------------------------------------------------ Store

    static final class TxStore implements StoreIo {
        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final Map<String, byte[]> staging = new ConcurrentHashMap<>();
        boolean failBegin = false;    // 场景 1
        boolean failFinish = false;   // 场景 2 / 6
        boolean dropCommit = false;   // 场景 3：commit 请求被丢弃（模拟进程中断）

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
        @Override public OutputStream openWrite(String n) throws IOException {
            return new ByteArrayOutputStream() {
                @Override public void close() throws IOException {
                    files.put(n, toByteArray());
                    super.close();
                }
            };
        }
        @Override public OutputStream beginWrite(String n) throws IOException {
            if (failBegin) throw new IOException("injected: beginWrite failed");
            return new ByteArrayOutputStream() {
                @Override public void close() throws IOException {
                    staging.put(n, toByteArray());
                    super.close();
                }
            };
        }
        @Override public boolean finishWrite(String n, OutputStream h) {
            if (failFinish) { staging.remove(n); return false; }
            if (dropCommit) { staging.remove(n); return false; }  // 未提交，等于中断
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
        @Override public String describe() { return "tx-mem"; }

        String text(String n) {
            byte[] b = files.get(n);
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        }
        void put(String n, String t) { files.put(n, t.getBytes(StandardCharsets.UTF_8)); }
    }

    private static TaskQueue.Task task(String id) {
        TaskQueue.Task t = new TaskQueue.Task();
        t.id = id;
        t.state = TaskQueue.State.PENDING;
        return t;
    }

    private static List<TaskQueue.Task> list(String... ids) {
        List<TaskQueue.Task> l = new ArrayList<>();
        for (String id : ids) l.add(task(id));
        return l;
    }

    private static String ids(TxStore s) {
        if (!s.isFile("queue.json")) return "<absent>";
        List<TaskQueue.Task> l = TaskQueue.load(null);
        StringBuilder sb = new StringBuilder();
        for (TaskQueue.Task t : l) sb.append(t.id).append(' ');
        return sb.toString().trim();
    }

    public static void main(String[] args) {
        System.out.println("== P0-3 事务写故障注入 ==");

        try { s1_beforeBegin(); } catch (Throwable t) { trip("S1", t); }
        try { s2_duringWrite(); } catch (Throwable t) { trip("S2", t); }
        try { s3_interruptBeforeCommit(); } catch (Throwable t) { trip("S3", t); }
        try { s4_restartAfterCommit(); } catch (Throwable t) { trip("S4", t); }
        try { s5_snapshot(); } catch (Throwable t) { trip("S5", t); }
        try { s6_diskError(); } catch (Throwable t) { trip("S6", t); }
        try { s7_quarantineBlocksWrite(); } catch (Throwable t) { trip("S7", t); }
        try { s8_sequential(); } catch (Throwable t) { trip("S8", t); }
        finally { TaskQueue.setStoreForTest(null); }

        System.out.println();
        System.out.println("=== P0-3 事务测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static void trip(String id, Throwable t) {
        fail++;
        System.out.println("  FAIL " + id + " 异常: " + t);
        t.printStackTrace(System.out);
    }

    /** 场景 1：写入前失败（beginWrite 抛）→ 原文件完全不变。 */
    private static void s1_beforeBegin() {
        System.out.println();
        System.out.println("-- S1 写入前失败 --");
        TxStore s = new TxStore();
        s.put("queue.json", "[{\"id\":\"orig\",\"ssid\":\"S\",\"state\":\"PENDING\"}]");
        String before = s.text("queue.json");
        TaskQueue.setStoreForTest(s);

        s.failBegin = true;
        boolean saved = TaskQueue.save(null, list("new1"));
        s.failBegin = false;

        info("save=" + saved + " / 内容=" + s.text("queue.json"));
        ok("beginWrite 失败时 save 返回 false", !saved, "返回 " + saved);
        ok("beginWrite 失败时原文件逐字节不变", before.equals(s.text("queue.json")),
                "内容被改: " + s.text("queue.json"));
    }

    /** 场景 2：写入过程中失败（finishWrite 返回 false）→ 回滚，原文件不变。 */
    private static void s2_duringWrite() {
        System.out.println();
        System.out.println("-- S2 写入过程中失败 --");
        TxStore s = new TxStore();
        s.put("queue.json", "[{\"id\":\"orig\",\"ssid\":\"S\",\"state\":\"PENDING\"}]");
        String before = s.text("queue.json");
        TaskQueue.setStoreForTest(s);

        s.failFinish = true;
        boolean saved = TaskQueue.save(null, list("new2"));
        s.failFinish = false;

        info("save=" + saved + " / 内容=" + s.text("queue.json"));
        ok("finishWrite 失败时 save 返回 false", !saved, "返回 " + saved);
        ok("finishWrite 失败时原文件逐字节不变", before.equals(s.text("queue.json")),
                "内容被改: " + s.text("queue.json"));
        ok("失败后暂存区已清空（无残留脏数据）", s.staging.isEmpty(),
                "staging=" + s.staging.keySet());
    }

    /** 场景 3：finishWrite 之前中断（提交被丢弃）→ 原文件不变。 */
    private static void s3_interruptBeforeCommit() {
        System.out.println();
        System.out.println("-- S3 提交前中断 --");
        TxStore s = new TxStore();
        s.put("queue.json", "[{\"id\":\"orig\",\"ssid\":\"S\",\"state\":\"PENDING\"}]");
        String before = s.text("queue.json");
        TaskQueue.setStoreForTest(s);

        s.dropCommit = true;
        boolean saved = TaskQueue.save(null, list("new3"));
        s.dropCommit = false;

        info("save=" + saved + " / 内容=" + s.text("queue.json"));
        ok("提交被丢弃时 save 返回 false", !saved, "返回 " + saved);
        ok("提交被丢弃时原文件逐字节不变", before.equals(s.text("queue.json")),
                "内容被改");
    }

    /** 场景 4：提交成功后"重启"（重新读取）→ 能读到新内容。 */
    private static void s4_restartAfterCommit() {
        System.out.println();
        System.out.println("-- S4 提交后重启 --");
        TxStore s = new TxStore();
        TaskQueue.setStoreForTest(s);

        boolean saved = TaskQueue.save(null, list("p", "q", "r"));
        info("save=" + saved + " / 内容=" + s.text("queue.json"));
        ok("提交成功返回 true", saved, "返回 " + saved);

        // 模拟重启：清掉进程内状态后重新读
        TaskQueue.resetLoadFailedForTest();
        List<TaskQueue.Task> back = TaskQueue.load(null);
        StringBuilder sb = new StringBuilder();
        for (TaskQueue.Task t : back) sb.append(t.id);
        info("重启后读到 " + back.size() + " 条: " + sb);
        ok("重启后读回 3 条且 id 全对",
                back.size() == 3 && sb.toString().equals("pqr"), "got=" + sb);
        ok("重启后状态为 COMPLETE",
                TaskQueue.lastReadState() == TaskQueue.ReadState.COMPLETE,
                "state=" + TaskQueue.lastReadState());
    }

    /** 场景 5：清空时产生快照，且快照内容 = 清空前内容。 */
    private static void s5_snapshot() {
        System.out.println();
        System.out.println("-- S5 清空快照 --");
        TxStore s = new TxStore();
        s.put("queue.json", "[{\"id\":\"keep1\",\"ssid\":\"S\",\"state\":\"PENDING\"},"
                + "{\"id\":\"keep2\",\"ssid\":\"S\",\"state\":\"PENDING\"}]");
        String before = s.text("queue.json");
        TaskQueue.setStoreForTest(s);

        boolean saved = TaskQueue.save(null, new ArrayList<TaskQueue.Task>());
        info("清空 save=" + saved + " / 目录=" + java.util.Arrays.toString(s.list()));

        String snap = null;
        for (String n : s.list()) if (n.startsWith("queue.json.snapshot-")) snap = n;
        ok("清空成功", saved, "返回 " + saved);
        ok("产生了快照文件", snap != null, "目录=" + java.util.Arrays.toString(s.list()));
        ok("快照内容 = 清空前的原始内容",
                snap != null && before.equals(s.text(snap)),
                "snap=" + (snap == null ? "null" : s.text(snap)));
        ok("**不再产生 <base>.bak**（避免与 AtomicFile 争用）",
                !s.isFile("queue.json.bak"),
                "出现了 queue.json.bak:" + java.util.Arrays.toString(s.list()));
    }

    /** 场景 6：磁盘写入异常（openWrite/beginWrite 抛）→ 原文件不变。 */
    private static void s6_diskError() {
        System.out.println();
        System.out.println("-- S6 磁盘写入异常 --");
        TxStore s = new TxStore();
        s.put("queue.json", "[{\"id\":\"orig\",\"ssid\":\"S\",\"state\":\"PENDING\"}]");
        String before = s.text("queue.json");
        TaskQueue.setStoreForTest(s);

        s.failBegin = true;
        boolean saved = TaskQueue.save(null, list("zz"));
        s.failBegin = false;

        ok("磁盘异常时 save 返回 false", !saved, "返回 " + saved);
        ok("磁盘异常时原文件逐字节不变", before.equals(s.text("queue.json")), "被改");
        ok("磁盘异常时无残留暂存", s.staging.isEmpty(), "staging=" + s.staging.keySet());
    }

    /** 场景 7：已有隔离标记时写 → 全部拒绝，两份原始文件字节不变。 */
    private static void s7_quarantineBlocksWrite() {
        System.out.println();
        System.out.println("-- S7 隔离标记存在时写入 --");
        TxStore s = new TxStore();
        s.put("queue.json", "[{\"id\":\"base1\",\"ssid\":\"S\",\"state\":\"PENDING\"}]");
        s.put("queue.json.bak", "[{\"id\":\"bak1\",\"ssid\":\"S\",\"state\":\"PENDING\"}]");
        TaskQueue.setStoreForTest(s);
        TaskQueue.load(null);  // 触发隔离
        String base0 = s.text("queue.json");
        String bak0 = s.text("queue.json.bak");
        info("隔离后 state=" + TaskQueue.lastReadState()
                + " 目录=" + java.util.Arrays.toString(s.list()));

        boolean saved = TaskQueue.save(null, list("attacker"));
        info("隔离期间 save=" + saved);

        ok("隔离状态下写入被拒", !saved, "返回 " + saved);
        ok("隔离期间 base 字节不变", base0.equals(s.text("queue.json")), "base 被改");
        ok("隔离期间 .bak 字节不变", bak0.equals(s.text("queue.json.bak")), "bak 被改");
        ok("隔离标记仍在（未被写入抹掉）", s.isFile("queue.json.quarantine"),
                "目录=" + java.util.Arrays.toString(s.list()));
    }

    /** 场景 8：连续多次写入，中间夹一次失败 → 最终内容正确。 */
    private static void s8_sequential() {
        System.out.println();
        System.out.println("-- S8 连续写入 + 中间失败 --");
        TxStore s = new TxStore();
        TaskQueue.setStoreForTest(s);

        boolean a = TaskQueue.save(null, list("v1"));
        boolean b = TaskQueue.save(null, list("v2", "v2b"));

        s.failFinish = true;
        boolean c = TaskQueue.save(null, list("BAD"));
        s.failFinish = false;

        boolean d = TaskQueue.save(null, list("v3", "v3b", "v3c"));
        info("序列 a=" + a + " b=" + b + " c(失败)=" + c + " d=" + d
                + " / 最终内容=" + s.text("queue.json"));

        ok("成功写入返回 true", a && b && d, "a=" + a + " b=" + b + " d=" + d);
        ok("中间失败返回 false", !c, "返回 " + c);
        ok("失败不污染最终结果（读到 v3,v3b,v3c）",
                "v3 v3b v3c".equals(ids(s)), "实际=" + ids(s));
    }
}
