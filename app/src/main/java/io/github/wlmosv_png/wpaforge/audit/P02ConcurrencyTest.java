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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P0-2 并发测试（覆盖审查项 A）。
 *
 * <h3>本测试回答什么</h3>
 * <ol>
 *   <li>测试 Store 换成线程安全实现后，{@link TaskQueue} 真实代码并发调用
 *       不会崩溃、不会产生非法状态。</li>
 *   <li><b>写回闸门在并发下不被绕过</b>：只要任一线程处于 CORRUPT/IO_ERROR，
 *       save 就必须失败，原文件字节不变。</li>
 * </ol>
 *
 * <h3>本测试**不**回答什么（必须诚实标注）</h3>
 * 「并发读改写零丢失」不在本阶段范围内 ——
 * {@code save} 的「读→改→写」尚未加锁（P0-4 才做）。
 * 因此这里不断言「最终条数等于期望值」，只断言：
 * 状态合法、不崩溃、闸门生效。
 */
public final class P02ConcurrencyTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /** 线程安全的内存 Store（ConcurrentHashMap）。 */
    static final class SafeStore implements StoreIo {
        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        volatile boolean throwIO = false;

        @Override public boolean isFile(String n) { return files.containsKey(n); }
        @Override public boolean exists(String n) { return files.containsKey(n); }
        @Override public long length(String n) {
            byte[] b = files.get(n);
            return b == null ? 0 : b.length;
        }
        @Override public InputStream openRead(String n) throws IOException {
            if (throwIO) throw new IOException("injected");
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

        // ---- 事务写（P0-3）：按 AtomicFile 语义模拟
        // beginWrite 只造暂存区、**不动原文件**；
        // finishWrite 才原子替换；abortWrite 丢弃暂存区、原文件不变。
        private final Map<String, byte[]> staging = new java.util.concurrent.ConcurrentHashMap<>();

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

        @Override public void abortWrite(String n, OutputStream h) {
            staging.remove(n);
        }

        @Override public boolean delete(String n) { return files.remove(n) != null; }
        @Override public boolean rename(String from, String to) {
            byte[] b = files.remove(from);
            if (b == null) return false;
            files.put(to, b);
            return true;
        }
        @Override public String[] list() { return files.keySet().toArray(new String[0]); }
        @Override public String describe() { return "safe-mem"; }
    }

    private static TaskQueue.Task task(String id) {
        TaskQueue.Task t = new TaskQueue.Task();
        t.id = id;
        t.state = TaskQueue.State.PENDING;
        return t;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== P0-2 并发：不崩溃 / 状态合法 / 闸门不被绕过 ==");

        try {
            // ---------- 1. 多线程并发 load/save 不崩溃，状态始终合法
            SafeStore st = new SafeStore();
            st.files.put("queue.json", ("[{\"id\":\"seed\",\"ssid\":\"S\",\"state\":\"PENDING\"}]")
                    .getBytes(StandardCharsets.UTF_8));
            TaskQueue.setStoreForTest(st);

            final int THREADS = 16;
            final int ROUNDS = 30;
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(THREADS);
            final AtomicInteger exceptions = new AtomicInteger();
            final AtomicInteger illegalState = new AtomicInteger();

            for (int t = 0; t < THREADS; t++) {
                final int tid = t;
                new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < ROUNDS; i++) {
                            List<TaskQueue.Task> l = TaskQueue.load(null);
                            TaskQueue.ReadState s1 = TaskQueue.lastReadState();
                            if (s1 != TaskQueue.ReadState.COMPLETE
                                    && s1 != TaskQueue.ReadState.ABSENT
                                    && s1 != TaskQueue.ReadState.CORRUPT
                                    && s1 != TaskQueue.ReadState.IO_ERROR) {
                                illegalState.incrementAndGet();
                            }
                            List<TaskQueue.Task> news = new ArrayList<>();
                            news.add(task("t" + tid + "-" + i));
                            TaskQueue.save(null, news);
                        }
                    } catch (Throwable ex) {
                        exceptions.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                }, "p02-conc-" + t).start();
            }
            start.countDown();
            done.await();

            info("并发线程=" + THREADS + " 轮次=" + ROUNDS
                    + " 异常=" + exceptions.get() + " 非法状态=" + illegalState.get());
            ok("并发 load/save 不抛异常", exceptions.get() == 0, "异常=" + exceptions.get());
            ok("并发下不出现非法 ReadState", illegalState.get() == 0,
                    "非法=" + illegalState.get());

            // 结束后状态必须仍是合法四态之一
            TaskQueue.load(null);
            TaskQueue.ReadState fin = TaskQueue.lastReadState();
            ok("并发结束后状态合法",
                    fin == TaskQueue.ReadState.COMPLETE || fin == TaskQueue.ReadState.ABSENT
                            || fin == TaskQueue.ReadState.CORRUPT
                            || fin == TaskQueue.ReadState.IO_ERROR,
                    "state=" + fin);
            info("并发结束后 state=" + fin);

            // ---------- 2. 闸门不被并发绕过：IO 故障期间写入必须全失败
            SafeStore st2 = new SafeStore();
            st2.files.put("queue.json", "[{\"id\":\"keep\",\"ssid\":\"S\",\"state\":\"PENDING\"}]"
                    .getBytes(StandardCharsets.UTF_8));
            byte[] before = st2.files.get("queue.json").clone();
            TaskQueue.setStoreForTest(st2);
            st2.throwIO = true;

            final AtomicInteger okSaves = new AtomicInteger();
            final AtomicInteger failSaves = new AtomicInteger();
            final CountDownLatch d2 = new CountDownLatch(8);
            for (int t = 0; t < 8; t++) {
                final int tid = t;
                new Thread(() -> {
                    List<TaskQueue.Task> news = new ArrayList<>();
                    news.add(task("x" + tid));
                    if (TaskQueue.save(null, news)) okSaves.incrementAndGet();
                    else failSaves.incrementAndGet();
                    d2.countDown();
                }).start();
            }
            d2.await();
            st2.throwIO = false;

            byte[] after = st2.files.get("queue.json");
            boolean same = java.util.Arrays.equals(before, after);
            info("IO 故障期间: 成功写入=" + okSaves.get() + " 被拒=" + failSaves.get()
                    + " 原文件未变=" + same);
            ok("IO 故障期间所有写入被拒", okSaves.get() == 0, "成功=" + okSaves.get());
            ok("IO 故障期间原文件字节不变", same, "字节已变");

            // ---------- 3. 隔离状态下并发写入全部被拒
            // P0-2e：健康 base + bak 不再隔离（bak 会被归档）。隔离场景必须用损坏 base。
            SafeStore st3 = new SafeStore();
            st3.files.put("queue.json", "NOT-JSON{{{"
                    .getBytes(StandardCharsets.UTF_8));
            st3.files.put("queue.json.bak", "[{\"id\":\"b\",\"ssid\":\"S\",\"state\":\"PENDING\"}]"
                    .getBytes(StandardCharsets.UTF_8));
            TaskQueue.setStoreForTest(st3);
            TaskQueue.load(null);   // 触发隔离
            byte[] base0 = st3.files.get("queue.json").clone();
            byte[] bak0 = st3.files.get("queue.json.bak").clone();

            final AtomicInteger okSaves3 = new AtomicInteger();
            final CountDownLatch d3 = new CountDownLatch(8);
            for (int t = 0; t < 8; t++) {
                final int tid = t;
                new Thread(() -> {
                    List<TaskQueue.Task> news = new ArrayList<>();
                    news.add(task("q" + tid));
                    if (TaskQueue.save(null, news)) okSaves3.incrementAndGet();
                    d3.countDown();
                }).start();
            }
            d3.await();

            info("隔离期间: 成功写入=" + okSaves3.get()
                    + " base未变=" + java.util.Arrays.equals(base0, st3.files.get("queue.json"))
                    + " bak未变=" + java.util.Arrays.equals(bak0, st3.files.get("queue.json.bak")));
            ok("隔离期间并发写入全部被拒", okSaves3.get() == 0, "成功=" + okSaves3.get());
            ok("隔离期间 base 字节不变",
                    java.util.Arrays.equals(base0, st3.files.get("queue.json")), "base 被改");
            ok("隔离期间 .bak 字节不变",
                    java.util.Arrays.equals(bak0, st3.files.get("queue.json.bak")), "bak 被改");

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        } finally {
            TaskQueue.setStoreForTest(null);
        }

        System.out.println();
        System.out.println("=== P0-2 并发测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
