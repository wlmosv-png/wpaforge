package io.github.wlmosv_png.wpaforge.audit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P0-0 接缝验证：证明测试可以驱动 {@link TaskQueue} 的**真实生产逻辑**。
 *
 * <h3>本测试要证明的三件事</h3>
 * <ol>
 *   <li>注入 {@link StoreIo} 后，{@link TaskQueue#load}/{@link TaskQueue#save}
 *       走的是**同一段生产代码**（JSON 序列化/解析、去重、refuseSave 等全部经过），
 *       只是字节从内存来、往内存去。</li>
 *   <li>注入后**完全不触碰** Android Context（可以传 {@code null}）——
 *       这是接缝成立的证据：{@code ctx.getFilesDir()} 只在未注入时才会被调用。</li>
 *   <li>三种故障（读失败 / 写失败 / 写一半）都能被**确定性注入**，
 *       为后续阶段（P0-1）的失败测试铺路。</li>
 * </ol>
 *
 * <h3>纪律</h3>
 * <ul>
 *   <li>不复制任何生产逻辑：拉到的 {@code Task} 由 {@link TaskQueue} 自己解析产生。</li>
 *   <li>断言基于真实对象与真实字段，不看日志文本。</li>
 *   <li>测试结束必须 {@code setStoreForTest(null)} 复原，避免污染同进程其它用例。</li>
 * </ul>
 */
public final class StoreSeamTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    // ------------------------------------------------------------ 内存 Store

    /** 内存文件系统；记录每次调用，用于证明「真的走了接缝」。 */
    static final class MemStore implements StoreIo {
        final Map<String, byte[]> files = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        /** 注入：openRead 抛 IOException */
        boolean failRead = false;
        /** 注入：openWrite 抛 IOException */
        boolean failWrite = false;
        /** 注入：写一半后抛（模拟中途崩溃/磁盘满） */
        boolean partialWrite = false;

        @Override public boolean isFile(String n) {
            calls.add("isFile:" + n);
            return files.containsKey(n);
        }
        @Override public boolean exists(String n) {
            calls.add("exists:" + n);
            return files.containsKey(n);
        }
        @Override public long length(String n) {
            calls.add("length:" + n);
            byte[] b = files.get(n);
            return b == null ? 0 : b.length;
        }
        @Override public InputStream openRead(String n) throws IOException {
            calls.add("openRead:" + n);
            if (failRead) throw new IOException("injected read failure");
            byte[] b = files.get(n);
            if (b == null) throw new java.io.FileNotFoundException(n);
            return new ByteArrayInputStream(b);
        }
        @Override public OutputStream openWrite(String n) throws IOException {
            calls.add("openWrite:" + n);
            if (failWrite) throw new IOException("injected write failure");
            final boolean half = partialWrite;
            return new ByteArrayOutputStream() {
                @Override public void close() throws IOException {
                    byte[] all = toByteArray();
                    if (half) {
                        // 只落一半，然后抛：模拟写中途失败
                        files.put(n, java.util.Arrays.copyOf(all, all.length / 2));
                        throw new IOException("injected partial write");
                    }
                    files.put(n, all);
                    super.close();
                }
            };
        }

        // ---- 事务写（P0-3）：按 AtomicFile 语义模拟
        // beginWrite 只造暂存区、**不动原文件**；
        // finishWrite 才原子替换；abortWrite 丢弃暂存区、原文件不变。
        private final Map<String, byte[]> staging = new java.util.concurrent.ConcurrentHashMap<>();

        @Override public OutputStream beginWrite(String n) throws IOException {
            calls.add("beginWrite:" + n);
            if (failWrite) throw new IOException("injected write failure");
            // 注意：partialWrite 不在这里抛 —— TaskQueue 用 flush 而不是 close
            // 来交出句柄，异常只有在 close 时才触发，永远不会被看到。
            // 半写场景改由 finishWrite 返回 false 模拟（见下）。
            return new ByteArrayOutputStream() {
                @Override public void close() throws IOException {
                    staging.put(n, toByteArray());
                    super.close();
                }
            };
        }

        @Override public boolean finishWrite(String n, OutputStream h) {
            // 半写场景：提交阶段失败（模拟 fsync/rename 失败），原文件必须保持不变
            if (partialWrite) {
                staging.remove(n);
                calls.add("finishWrite-failed:" + n);
                return false;
            }
            if (h instanceof ByteArrayOutputStream) {
                staging.put(n, ((ByteArrayOutputStream) h).toByteArray());
            }
            byte[] b = staging.remove(n);
            if (b == null) return false;
            calls.add("finishWrite-ok:" + n);
            files.put(n, b);
            return true;
        }

        @Override public void abortWrite(String n, OutputStream h) {
            staging.remove(n);
        }

        @Override public boolean delete(String n) {
            calls.add("delete:" + n);
            return files.remove(n) != null;
        }
        @Override public boolean rename(String from, String to) {
            calls.add("rename:" + from + "->" + to);
            byte[] b = files.remove(from);
            if (b == null) return false;
            files.put(to, b);
            return true;
        }
        @Override public String[] list() {
            calls.add("list");
            return files.keySet().toArray(new String[0]);
        }
        @Override public String describe() { return "mem"; }

        String text(String n) {
            byte[] b = files.get(n);
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        }
    }

    private static TaskQueue.Task task(String id, String ssid) {
        TaskQueue.Task t = new TaskQueue.Task();
        t.id = id;
        t.ssid = ssid;
        t.state = TaskQueue.State.PENDING;
        t.dictName = "d";
        return t;
    }

    public static void main(String[] args) {
        System.out.println("== P0-0 存储接缝：能否驱动真实生产逻辑 ==");

        MemStore mem = new MemStore();
        try {
            // ---------- 1. 注入后完全不需要 Context ----------
            TaskQueue.setStoreForTest(mem);

            List<TaskQueue.Task> src = new ArrayList<>();
            src.add(task("t1", "AAA"));
            src.add(task("t2", "BBB"));

            // ctx 传 null：若接缝不成立，这里会 NPE
            boolean saved = TaskQueue.save(null, src);
            info("save(null ctx) 返回 = " + saved);
            ok("注入后 save 不依赖 Android Context", saved, "返回 false");

            // ---------- 2. 写出的字节来自真实序列化 ----------
            String written = mem.text("queue.json");
            info("写入内容 = " + (written == null ? "(null)" : written));
            ok("save 真的写进了注入的 Store（走真实序列化）",
                    written != null && written.contains("\"t1\"") && written.contains("\"t2\""),
                    "内容=" + written);
            ok("save 走**事务写**（调用序列含 beginWrite 与提交）",
                    mem.calls.contains("beginWrite:queue.json"),
                    "calls=" + mem.calls);

            // ---------- 3. 读回走真实解析 ----------
            mem.calls.clear();
            List<TaskQueue.Task> back = TaskQueue.load(null);
            info("load 返回条数 = " + back.size());
            ok("load 真的从注入的 Store 读（走真实 JSON 解析）", back.size() == 2,
                    "size=" + back.size());
            if (back.size() == 2) {
                ok("解析出的字段与写入一致",
                        "t1".equals(back.get(0).id) && "AAA".equals(back.get(0).ssid)
                                && "t2".equals(back.get(1).id) && "BBB".equals(back.get(1).ssid),
                        back.get(0).id + "/" + back.get(0).ssid + " , "
                                + back.get(1).id + "/" + back.get(1).ssid);
                ok("解析出的状态与写入一致",
                        back.get(0).state == TaskQueue.State.PENDING,
                        String.valueOf(back.get(0).state));
            }
            ok("load 调用序列含 openRead", mem.calls.contains("openRead:queue.json"),
                    "calls=" + mem.calls);

            // ---------- 4. 故障注入：读失败能确定性触发 ----------
            mem.failRead = true;
            TaskQueue.resetLoadFailedForTest();
            List<TaskQueue.Task> afterFail = TaskQueue.load(null);
            boolean flag = TaskQueue.lastLoadFailed();
            mem.failRead = false;
            info("读失败后 load 返回条数 = " + afterFail.size() + " / lastLoadFailed=" + flag);
            ok("读失败被真实捕获（loadFailedFlag 置位，内容为空）",
                    afterFail.isEmpty() && flag,
                    "size=" + afterFail.size() + " flag=" + flag);

            // ---------- 5. 故障注入：写失败不能假装成功 ----------
            mem.failWrite = true;
            boolean saveFail = TaskQueue.save(null, src);
            mem.failWrite = false;
            info("写失败时 save 返回 = " + saveFail);
            ok("写失败时 save 返回 false（不假装成功）", !saveFail, "返回 " + saveFail);
            ok("写失败后 beginWrite 确实被调用过（注入点生效）",
                    mem.calls.contains("beginWrite:queue.json"), "calls 缺 beginWrite");

            // ---------- 6. 故障注入：写一半（模拟崩溃） ----------
            // 先把 queue.json 恢复成「已知完整内容」，以便判断是否被半截污染
            mem.files.put("queue.json", written.getBytes(StandardCharsets.UTF_8));
            String baseline = mem.text("queue.json");

            mem.partialWrite = true;
            boolean halfSave = TaskQueue.save(null, src);
            mem.partialWrite = false;

            String afterHalf = mem.text("queue.json");
            String tmpAfter = mem.text("queue.json.tmp");
            info("写一半后 save 返回 = " + halfSave);
            info("queue.json 长度=" + (afterHalf == null ? "null" : afterHalf.length())
                    + " / tmp 长度=" + (tmpAfter == null ? "null" : tmpAfter.length()));

            ok("写一半时 save 返回 false（失败未被吞掉）", !halfSave, "返回 " + halfSave);
            ok("**写一半不得污染 base**（事务未提交，原内容完整保留）",
                    baseline != null && baseline.equals(afterHalf),
                    "base 被改成了=" + afterHalf);
            ok("半截内容未成为新版本（长度与基准不同或未写入）",
                    tmpAfter == null || baseline == null || !tmpAfter.equals(baseline),
                    "tmp=" + (tmpAfter == null ? "null" : String.valueOf(tmpAfter.length())));

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        } finally {
            TaskQueue.setStoreForTest(null);
        }

        System.out.println();
        System.out.println("=== P0-0 接缝测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
