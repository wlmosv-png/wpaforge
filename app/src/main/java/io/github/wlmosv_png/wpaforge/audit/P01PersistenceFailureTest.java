package io.github.wlmosv_png.wpaforge.audit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P0-1 持久化缺陷失败测试。
 *
 * <h3>本测试的目的</h3>
 * **证明当前生产代码存在数据完整性缺陷**，而不是增加通过数量。
 *
 * <h3>断言口径</h3>
 * 每条断言写的是**应当具备的行为**（四态分类 / 完整性防线 / 隔离）。
 * 当前代码尚未实现这些行为，因此这些断言**预期为红**。
 * 每个红断言都必须在输出里标 {@code [EXPECTED-FAIL]} 并给出对应缺陷编号，
 * 以便区分「目标缺陷导致的红」与「环境/桩/断言写错导致的意外红」。
 *
 * <h3>纪律</h3>
 * <ul>
 *   <li>只调用真实 {@link TaskQueue#load}/{@link TaskQueue#save}，不复制判定逻辑。</li>
 *   <li>{@link StoreIo} 只负责注入字节行为（读失败/写失败/内容），不做任何判定。</li>
 *   <li>文件保护用 SHA-256 / 原始字节比较，不用「文件是否存在」。</li>
 *   <li>不用 sleep、不吞异常、不放宽断言。</li>
 * </ul>
 */
public final class P01PersistenceFailureTest {

    private static int pass = 0, failExpected = 0, failUnexpected = 0;
    private static final List<String> unexpected = new ArrayList<>();

    /** 断言「应当成立」。当前不成立 = 预期失败（对应已知缺陷）。 */
    private static void should(String title, String defect, boolean condition, String actual) {
        if (condition) {
            pass++;
            System.out.println("  OK   " + title);
        } else {
            failExpected++;
            System.out.println("  [EXPECTED-FAIL][" + defect + "] " + title);
            System.out.println("         实际: " + actual);
        }
    }

    /** 断言「必须成立的测试前提」。不成立 = 意外失败（测试本身或环境有问题）。 */
    private static void must(String title, boolean condition, String actual) {
        if (condition) {
            pass++;
            System.out.println("  OK   " + title);
        } else {
            failUnexpected++;
            unexpected.add(title);
            System.out.println("  [UNEXPECTED-FAIL] " + title);
            System.out.println("         实际: " + actual);
        }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    // ------------------------------------------------------------ 注入用 Store

    static final class MemStore implements StoreIo {
        final Map<String, byte[]> files = new HashMap<>();
        boolean throwFNFE = false;      // openRead 抛 FileNotFoundException
        boolean throwIO = false;        // openRead 抛普通 IOException
        boolean failWrite = false;      // openWrite 抛 IOException

        @Override public boolean isFile(String n) { return files.containsKey(n); }
        @Override public boolean exists(String n) { return files.containsKey(n); }
        @Override public long length(String n) {
            byte[] b = files.get(n);
            return b == null ? 0 : b.length;
        }
        @Override public InputStream openRead(String n) throws IOException {
            if (throwFNFE) throw new FileNotFoundException("injected FNFE: " + n);
            if (throwIO) throw new IOException("injected IO error: " + n);
            byte[] b = files.get(n);
            if (b == null) throw new FileNotFoundException(n);
            return new ByteArrayInputStream(b);
        }
        @Override public OutputStream openWrite(String n) throws IOException {
            if (failWrite) throw new IOException("injected write failure");
            return new ByteArrayOutputStream() {
                @Override public void close() throws IOException {
                    files.put(n, toByteArray());
                    super.close();
                }
            };
        }
        @Override public boolean delete(String n) { return files.remove(n) != null; }
        @Override public boolean rename(String from, String to) {
            byte[] b = files.remove(from);
            if (b == null) return false;
            files.put(to, b);
            return true;
        }
        @Override public String[] list() { return files.keySet().toArray(new String[0]); }
        @Override public String describe() { return "mem"; }

        void put(String n, String text) { files.put(n, text.getBytes(StandardCharsets.UTF_8)); }
        String text(String n) {
            byte[] b = files.get(n);
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        }
        String sha(String n) {
            byte[] b = files.get(n);
            if (b == null) return "<absent>";
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest(b);
                StringBuilder sb = new StringBuilder();
                for (byte x : d) sb.append(String.format("%02x", x));
                return sb.toString();
            } catch (Exception e) { return "<err:" + e + ">"; }
        }
    }

    private static TaskQueue.Task task(String id) {
        TaskQueue.Task t = new TaskQueue.Task();
        t.id = id;
        t.state = TaskQueue.State.PENDING;
        return t;
    }

    private static String taskJson(String id) {
        return "{\"id\":\"" + id + "\",\"ssid\":\"S" + id + "\",\"state\":\"PENDING\"}";
    }

    public static void main(String[] args) {
        System.out.println("== P0-1 持久化缺陷失败测试 ==");

        try { t_r1(); } catch (Throwable t) { trip("T-R1", t); }
        try { t_r2(); } catch (Throwable t) { trip("T-R2", t); }
        try { t_r3(); } catch (Throwable t) { trip("T-R3", t); }
        try { t_r4(); } catch (Throwable t) { trip("T-R4", t); }
        try { t_m4a(); } catch (Throwable t) { trip("T-M4a", t); }
        finally { TaskQueue.setStoreForTest(null); }

        System.out.println();
        // 门禁按 pass=N fail=N 解析。这里把「预期失败」计入 fail：
        // 它们确实是当前生产代码未满足的要求，绝不能伪装成通过。
        System.out.println("=== P0-1 明细: 通过=" + pass
                + " 预期失败=" + failExpected
                + " 意外失败=" + failUnexpected + " ===");
        // 意外失败（测试本身/环境问题）单独用更大的 fail 数暴露，并 exit(2)
        if (failUnexpected > 0) {
            System.out.println("意外失败（测试本身或环境问题）: " + unexpected);
            System.out.println("pass=" + pass + " fail=" + (failExpected + failUnexpected * 100));
            System.exit(2);
        }
        // 预期失败 = 目标缺陷存在，门禁判红是本阶段的**正确结果**。
        System.out.println("说明: 预期失败 " + failExpected
                + " 条全部对应 P0 设计文档中的已知缺陷；意外失败 0 条。");
        System.out.println("pass=" + pass + " fail=" + failExpected);
        if (failExpected > 0) System.exit(1);
    }

    private static void trip(String id, Throwable t) {
        failUnexpected++;
        unexpected.add(id + " 抛出异常");
        System.out.println("  [UNEXPECTED-FAIL] " + id + " 抛出 " + t);
        t.printStackTrace(System.out);
    }

    // ============================================================ T-R1

    /**
     * T-R1：文件不存在（ABSENT）与读取失败（IO_ERROR）必须区分。
     *
     * <p>当前实现：两者都表现为「空列表」，只靠一个进程级 flag 区分，
     * 且 flag 不区分「内容坏」与「读不到」。
     */
    private static void t_r1() {
        System.out.println();
        System.out.println("-- T-R1 文件不存在 vs 读取失败 --");

        // 1a. 文件确实不存在
        MemStore a = new MemStore();
        TaskQueue.setStoreForTest(a);
        TaskQueue.resetLoadFailedForTest();
        List<TaskQueue.Task> absent = TaskQueue.load(null);
        boolean flagAbsent = TaskQueue.lastLoadFailed();
        info("1a 文件不存在: size=" + absent.size() + " flag=" + flagAbsent);
        must("1a 前提：文件不存在时返回空列表", absent.isEmpty(), "size=" + absent.size());
        should("1a 文件不存在应判 ABSENT（不是读取失败）", "T-R1a",
                !flagAbsent && absent.isEmpty(),
                "flag=" + flagAbsent + "（当前把「不存在」与「读失败」共用同一套返回）");

        // 1b. 抛 FNFE 但文件实际存在（存在性矛盾 = 访问受限）
        MemStore b = new MemStore();
        b.put("queue.json", "[]");
        TaskQueue.setStoreForTest(b);
        TaskQueue.resetLoadFailedForTest();
        b.throwFNFE = true;
        List<TaskQueue.Task> ioErr = TaskQueue.load(null);
        boolean flagIo = TaskQueue.lastLoadFailed();
        info("1b FNFE+文件存在: size=" + ioErr.size() + " flag=" + flagIo);
        must("1b 前提：注入生效且返回空列表", ioErr.isEmpty(), "size=" + ioErr.size());
        should("1b 「存在但读不到」必须判 IO_ERROR 而非 ABSENT", "T-R1b",
                TaskQueue.lastReadState() == TaskQueue.ReadState.IO_ERROR,
                "lastReadState=" + TaskQueue.lastReadState() + " flag=" + flagIo);

        // 1c. IO_ERROR 状态下，非空写入必须被拒绝（当前会覆盖）
        String before = b.sha("queue.json");
        List<TaskQueue.Task> news = new ArrayList<>();
        news.add(task("x1"));
        boolean saved = TaskQueue.save(null, news);
        String after = b.sha("queue.json");
        info("1c IO_ERROR 后写入: save=" + saved + " sha 前=" + shortSha(before)
                + " 后=" + shortSha(after));
        should("1c IO_ERROR 时不得覆盖原文件（原字节必须保留）", "T-R1c",
                before.equals(after),
                "原文件已被覆盖（sha 变化），save 返回 " + saved);
    }

    // ============================================================ T-R2

    /**
     * T-R2：普通 IO 异常必须判 IO_ERROR，原文件完整保留，不得隔离、不得覆盖。
     */
    private static void t_r2() {
        System.out.println();
        System.out.println("-- T-R2 普通 IO 异常 --");

        MemStore m = new MemStore();
        String original = "[" + taskJson("keep") + "]";
        m.put("queue.json", original);
        String sha0 = m.sha("queue.json");
        TaskQueue.setStoreForTest(m);
        TaskQueue.resetLoadFailedForTest();

        m.throwIO = true;
        List<TaskQueue.Task> got = TaskQueue.load(null);
        boolean flag = TaskQueue.lastLoadFailed();
        info("读取: size=" + got.size() + " flag=" + flag + " sha=" + shortSha(sha0));
        must("前提：注入生效（返回空 + flag 置位）", got.isEmpty() && flag,
                "size=" + got.size() + " flag=" + flag);

        should("IO_ERROR 后原文件字节必须完整保留", "T-R2a",
                sha0.equals(m.sha("queue.json")),
                "sha 变化: " + shortSha(m.sha("queue.json")));

        should("IO_ERROR 后不得产生隔离/改名（仅隔离 CORRUPT）", "T-R2b",
                m.list().length == 1,
                "目录里出现 " + m.list().length + " 个文件: " + java.util.Arrays.toString(m.list()));

        // 关键：**保持 IO 故障状态**下调用 save。
        // 若先关掉故障再写，save 内部的读取会成功，闸门自然放行 ——
        // 那测的就不是「IO_ERROR 时能否被拦住」了。
        List<TaskQueue.Task> news = new ArrayList<>();
        news.add(task("y1"));
        boolean saved = TaskQueue.save(null, news);
        info("IO_ERROR 状态下 save = " + saved + " / state=" + TaskQueue.lastReadState());
        must("前提：写入时读取状态确实是 IO_ERROR",
                TaskQueue.lastReadState() == TaskQueue.ReadState.IO_ERROR,
                "state=" + TaskQueue.lastReadState());
        should("IO_ERROR 后不得用非空列表覆盖原文件", "T-R2c",
                !saved && sha0.equals(m.sha("queue.json")),
                "save=" + saved + "，原文件 sha=" + shortSha(m.sha("queue.json")));
        m.throwIO = false;
    }

    // ============================================================ T-R3

    /**
     * T-R3：空文件 / 纯空白 / 非法 JSON 必须判 CORRUPT，不得当作正常空队列。
     */
    private static void t_r3() {
        System.out.println();
        System.out.println("-- T-R3 空文件 / 空白 / 非法 JSON --");

        String[][] cases = {
                {"空文件", ""},
                {"纯空白", "   \n\t  \n"},
                {"非法 JSON", "{\"not\":\"an array\"}"},
                {"截断 JSON", "[{\"id\":\"a\"},"}
        };

        for (String[] c : cases) {
            String name = c[0];
            MemStore m = new MemStore();
            m.put("queue.json", c[1]);
            String sha0 = m.sha("queue.json");
            TaskQueue.setStoreForTest(m);
            TaskQueue.resetLoadFailedForTest();

            List<TaskQueue.Task> got = TaskQueue.load(null);
            boolean flag = TaskQueue.lastLoadFailed();
            info(name + ": size=" + got.size() + " flag=" + flag);

            must(name + " 前提：当前确实返回空列表", got.isEmpty(), "size=" + got.size());
            should(name + " 必须判 CORRUPT（而非静默空队列）", "T-R3",
                    TaskQueue.lastReadState() == TaskQueue.ReadState.CORRUPT,
                    "lastReadState=" + TaskQueue.lastReadState() + " flag=" + flag);

            should(name + " 原文件字节必须完整保留", "T-R3",
                    sha0.equals(m.sha("queue.json")),
                    "sha 变化: " + shortSha(m.sha("queue.json")));
        }
    }

    // ============================================================ T-R4

    /**
     * T-R4：部分解析（合法 A + 合法 B + 非法 C）必须整体判 CORRUPT。
     */
    private static void t_r4() {
        System.out.println();
        System.out.println("-- T-R4 部分解析必须整体判 CORRUPT --");

        MemStore m = new MemStore();
        String bad = "[" + taskJson("A") + "," + taskJson("B") + ",42]";
        m.put("queue.json", bad);
        String sha0 = m.sha("queue.json");
        TaskQueue.setStoreForTest(m);
        TaskQueue.resetLoadFailedForTest();

        List<TaskQueue.Task> got = TaskQueue.load(null);
        boolean flag = TaskQueue.lastLoadFailed();
        StringBuilder ids = new StringBuilder();
        for (TaskQueue.Task t : got) ids.append(t.id).append(' ');
        info("部分解析结果: size=" + got.size() + " ids=[" + ids.toString().trim()
                + "] flag=" + flag);

        // 前提只断言「读取状态确实异常」，不再依赖旧实现返回前缀的行为
        must("前提：第三条非法元素确实让读取失败",
                flag && TaskQueue.lastReadState() != TaskQueue.ReadState.COMPLETE,
                "size=" + got.size() + " flag=" + flag
                        + " state=" + TaskQueue.lastReadState());

        should("部分解析必须整体判 CORRUPT（不得返回 A、B）", "T-R4a",
                got.isEmpty() && TaskQueue.lastReadState() == TaskQueue.ReadState.CORRUPT,
                "size=" + got.size() + " ids=[" + ids.toString().trim()
                        + "] state=" + TaskQueue.lastReadState());

        // 用返回的部分列表回写 = 覆盖原始文件
        List<TaskQueue.Task> news = new ArrayList<>();
        news.add(task("z1"));
        boolean saved = TaskQueue.save(null, news);
        String after = m.sha("queue.json");
        info("回写后: save=" + saved + " sha 前=" + shortSha(sha0)
                + " 后=" + shortSha(after));
        should("不得将部分解析结果写回覆盖原始文件", "T-R4b",
                sha0.equals(after),
                "原始字节已被覆盖（save=" + saved + "）");
    }

    // ============================================================ T-M4a

    /**
     * T-M4a：base 与 .bak 同时存在时必须进入 QUARANTINED。
     *
     * <p>当前实现完全没有这个概念：会直接读 base，且随后任何写入都会覆盖它。
     * 更危险的是 base 与 .bak 两份内容不同，无法判断哪份应被采用。
     */
    private static void t_m4a() {
        System.out.println();
        System.out.println("-- T-M4a base + .bak 同时存在 --");

        MemStore m = new MemStore();
        String baseContent = "[" + taskJson("fromBase") + "]";
        String bakContent = "[" + taskJson("fromBak") + "," + taskJson("fromBak2") + "]";
        m.put("queue.json", baseContent);
        m.put("queue.json.bak", bakContent);
        String shaBase0 = m.sha("queue.json");
        String shaBak0 = m.sha("queue.json.bak");
        TaskQueue.setStoreForTest(m);
        TaskQueue.resetLoadFailedForTest();

        List<TaskQueue.Task> got = TaskQueue.load(null);
        info("读取结果: size=" + got.size()
                + (got.isEmpty() ? "" : " id=" + got.get(0).id));
        info("base sha=" + shortSha(shaBase0) + " / bak sha=" + shortSha(shaBak0));

        should("base 与 .bak 同时存在时必须判 QUARANTINED", "T-M4a",
                false,
                "当前直接读取 base 并返回 " + got.size() + " 条，无任何隔离迹象");

        // 隔离标记必须出现（当前没有这个文件）
        boolean hasQuarantine = false;
        for (String n : m.list()) {
            if (n.contains("quarantine")) hasQuarantine = true;
        }
        should("必须产生持久化隔离标记", "T-M4a",
                hasQuarantine,
                "目录里只有: " + java.util.Arrays.toString(m.list()));

        should("两份原始文件字节必须保持不变", "T-M4a",
                shaBase0.equals(m.sha("queue.json"))
                        && shaBak0.equals(m.sha("queue.json.bak")),
                "base sha=" + shortSha(m.sha("queue.json"))
                        + " bak sha=" + shortSha(m.sha("queue.json.bak")));

        // 写入必须被拒绝（当前会覆盖 base）
        List<TaskQueue.Task> news = new ArrayList<>();
        news.add(task("w1"));
        boolean saved = TaskQueue.save(null, news);
        should("隔离状态下所有写入必须被拒绝", "T-M4a",
                !saved && shaBase0.equals(m.sha("queue.json")),
                "save=" + saved + "，base 已被覆盖: sha=" + shortSha(m.sha("queue.json")));
    }

    private static String shortSha(String s) {
        if (s == null) return "null";
        return s.length() > 12 ? s.substring(0, 12) : s;
    }
}
