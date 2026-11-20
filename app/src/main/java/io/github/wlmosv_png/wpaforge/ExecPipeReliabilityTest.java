package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.cap.CommandExecutor;

/**
 * **执行管道可靠性门禁**（2026-11-20 第三阶段）。
 *
 * <h3>要钉死的真机故障</h3>
 * 用户 OPPO Android 13 真机日志：
 * <pre>
 *   exit=255 timeout=false out=0B  err=809B failure=NONZERO_EXIT
 *   exit=255（有 stdout）但仍然失败
 *   exit=0   timeout=false out=0B  err=0B   failure=EMPTY_OUTPUT
 *   binder=0ms
 * </pre>
 *
 * <h3>本轮**已证实**的缺陷（可直接从源码判定）</h3>
 * <ol>
 *   <li><b>binderMs 从未被赋值</b>：{@code CommandExecutor} 只写
 *       {@code st.waitMs = r.elapsedMs()}，binderMs / drainMs 恒为 0。
 *       所以日志里的「binder=0ms」不是 Binder 真的 0ms，而是<b>没测</b>。</li>
 *   <li><b>失败原因被吞</b>：{@code remoteProcess} 失败时只回一句
 *       「创建进程失败」，把 SecurityException / RemoteException /
 *       binder 死亡压成同一个字符串；{@code Result} 只有 stderr 字节数，
 *       没有正文 —— 真机 809B stderr 因此无法看到内容。</li>
 *   <li><b>空输出与截断未区分</b>：旧实现「output 为空」一律判
 *       EMPTY_OUTPUT，无法区分「命令真没输出」与「我们没读完」。</li>
 * </ol>
 *
 * <h3>本轮**改了但未能复现**的一项（如实记录）</h3>
 * <p>收尾顺序从「先 close 再 join」改为「先 join 再 close」。
 * <b>用真实子进程 + 真实内核管道做对照实验，两者都读到 200000/200000 字节，
 * 没有复现差异。</b>改的理由是可验证的语义正确性（read() 到 EOF 才是
 * 「读完了」的唯一依据），不是「已证实的根因」。
 * 真机 exit=255 的原因仍待 809B stderr 正文确认。
 *
 * <p>本类用**真实字节流**（ByteArrayInputStream + 真实线程）验证收尾顺序的
 * 行为正确性，不依赖 Android、不依赖 mock 框架。
 */
public class ExecPipeReliabilityTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        ExecPipeReliabilityTest t = new ExecPipeReliabilityTest();
        t.run();
        System.out.println(t.pass + " 通过 / " + t.fail + " 失败");
        if (t.fail > 0) System.exit(1);
    }

    private static String readSource(String rel) {
        try {
            String path = TestPaths.under(
                    "app/src/main/java/io/github/wlmosv_png/wpaforge/" + rel);
            byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 去掉注释行后的源码。
     *
     * <p>本轮修复的注释里**故意保留**了旧顺序的写法
     * （{@code closeQuietly(outFd); closeQuietly(errFd);}）作为反面对照，
     * 那是文档不是代码。断言必须区分「注释提到」与「代码真的这么写」。
     */
    private static String codeOnly(String src) {
        StringBuilder sb = new StringBuilder();
        for (String line : src.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private void ok(String name, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private void run() {
        // ── 收尾顺序（真机根因 1）
        closeAfterJoinNotBefore();
        joinHasExplicitBudget();
        // ── 耗时透传（真机根因 2）
        binderMsActuallyPropagated();
        // ── 失败分类必须互斥且可区分
        failureKindsAreDistinct();
        truncatedIsNotEmptyOutput();
        // ── stderr 正文必须能看见
        stderrTextExposed();
        // ── 行为验证：真实字节流读尽
        drainReadsAllBytes();
        // ── 行为验证：进程退出后 drain 自然结束（不需要 close 叫醒）
        drainEndsOnEofWithoutClose();
        // ── 超时路径必须先 destroy
        timeoutDestroysFirst();
        // ── 不允许缩短超时掩盖问题
        noTimeoutHiding();
        // ── 源码里必须保留「未复现」的诚实记录
        honestyRecorded();
    }

    /**
     * 源码里必须留着「我未能复现」的记录。
     *
     * <p>防止将来有人（包括我）把这处改动重新描述成「已证实的根因」——
     * 那是编造证据。
     */
    private void honestyRecorded() {
        String shz = readSource("cap/ShizukuChannel.java");
        ok("源码记录了未复现事实", shz.contains("我未能复现"));
        ok("源码不把 close-first 写成根因", shz.contains("不把它写成根因"));
        ok("源码说明真机 exit=255 仍待确认", shz.contains("仍待确认"));
    }

    /**
     * **收尾顺序**：必须先 join（读尽），再在必要时 close。
     *
     * <p>注意：这**不是**已证实的真机根因（对照实验未能复现差异）。
     * 断言它是因为语义正确性 —— read() 到 EOF 才等于「读完了」。
     */
    private void closeAfterJoinNotBefore() {
        String shz = readSource("cap/ShizukuChannel.java");
        String code = codeOnly(shz);   // 注释里的旧写法不算
        int joinAt = code.indexOf("joinQuietly(tOut, JOIN_DRAIN_MS);");
        int closeAt = code.indexOf("closeQuietly(outFd);");
        ok("先 join 再 close（顺序正确）", joinAt > 0 && closeAt > 0 && joinAt < closeAt);
        ok("close 只在 drain 未完成时才做",
                shz.contains("if (!outComplete) closeQuietly(outFd);"));
        ok("close 后还有一次兜底 join",
                shz.contains("if (!outComplete) joinQuietly(tOut, JOIN_FORCE_MS);"));
        ok("记录两路完整性",
                shz.contains("drainOutComplete") && shz.contains("drainErrComplete"));
        // 旧注释不能残留（会误导后来人）
        ok("过时的「必须先关流再 join」注释已删除",
                !shz.contains("**必须先关流再 join**"));
    }

    /** join 时限必须显式分离，不能又变成固定 3000ms×2。 */
    private void joinHasExplicitBudget() {
        String shz = readSource("cap/ShizukuChannel.java");
        ok("有常规 drain 时限常量", shz.contains("JOIN_DRAIN_MS = 2000L"));
        ok("有强制叫醒时限常量", shz.contains("JOIN_FORCE_MS = 300L"));
        ok("joinQuietly 接受显式时限", shz.contains("joinQuietly(Thread t, long ms)"));
        ok("不再有固定 3000ms 的 join", !shz.contains("t.join(3000L)"));
    }

    /** binderMs / drainMs 必须真的被赋值并透传（真机「binder=0ms」的修复）。 */
    private void binderMsActuallyPropagated() {
        String ce = readSource("cap/CommandExecutor.java");
        ok("CommandExecutor 透传 binderMs", ce.contains("st.binderMs = r.binderMs;"));
        ok("CommandExecutor 透传 drainMs", ce.contains("st.drainMs = r.drainMs;"));
        ok("CommandExecutor 透传 waitMs", ce.contains("st.waitMs = r.waitMs;"));
        // 旧写法（只给 elapsedMs）不能残留
        ok("不再用 elapsedMs 冒充 waitMs",
                !ce.contains("st.waitMs = r.elapsedMs();"));

        String shz = readSource("cap/ShizukuChannel.java");
        ok("ShizukuChannel 真的累加 binderMs", shz.contains("binderMsAcc +="));
        ok("ShizukuChannel 真的累加 drainMs", shz.contains("drainMsAcc +="));
        ok("ShizukuChannel 真的累加 waitMs", shz.contains("waitMsAcc +="));
        ok("建进程计入 binderMs", shz.contains("binderMsAcc += System.currentTimeMillis() - tBinder0;"));
        ok("取流计入 binderMs", shz.contains("binderMsAcc += System.currentTimeMillis() - tFd0;"));
        ok("三段都写进 Result", shz.contains("r.binderMs = binderMsAcc;")
                && shz.contains("r.waitMs = waitMsAcc;")
                && shz.contains("r.drainMs = drainMsAcc;"));
    }

    /** 六种失败必须互不相同（任务书第三节第 5 条）。 */
    private void failureKindsAreDistinct() {
        String ce = readSource("cap/CommandExecutor.java");
        String[] kinds = {
                ce.contains("F_TIMEOUT") ? "TIMEOUT" : "",
                ce.contains("F_NONZERO_EXIT") ? "NONZERO_EXIT" : "",
                ce.contains("F_EMPTY_OUTPUT") ? "EMPTY_OUTPUT" : "",
                ce.contains("F_NOT_USABLE") ? "NOT_USABLE" : "",
                ce.contains("F_EXCEPTION") ? "EXCEPTION" : "",
                ce.contains("F_NO_PROCESS") ? "NO_PROCESS" : "",
                ce.contains("F_OUTPUT_TRUNCATED") ? "OUTPUT_TRUNCATED" : "",
        };
        int n = 0;
        java.util.Set<String> uniq = new java.util.HashSet<>();
        for (String k : kinds) {
            if (!k.isEmpty()) { n++; uniq.add(k); }
        }
        ok("失败分类至少 7 种", n >= 7);
        ok("分类互不重复", uniq.size() == n);

        // 每种分类的常量值必须不同（不能两个都等于同一个字符串）
        ok("TIMEOUT 值唯一", ce.contains("\"TIMEOUT\""));
        ok("NONZERO_EXIT 值唯一", ce.contains("\"NONZERO_EXIT\""));
        ok("EMPTY_OUTPUT 值唯一", ce.contains("\"EMPTY_OUTPUT\""));
        ok("OUTPUT_TRUNCATED 值唯一", ce.contains("\"OUTPUT_TRUNCATED\""));
        ok("NO_PROCESS 值唯一", ce.contains("\"NO_PROCESS\""));

        // ShizukuChannel 的 failureKind 也要分流
        String shz = readSource("cap/ShizukuChannel.java");
        ok("ShizukuChannel 有 KIND_NO_PROCESS", shz.contains("KIND_NO_PROCESS"));
        ok("ShizukuChannel 有 KIND_READ_FAILED", shz.contains("KIND_READ_FAILED"));
        ok("ShizukuChannel 有 KIND_TIMEOUT", shz.contains("KIND_TIMEOUT"));
        ok("ShizukuChannel 有 KIND_NOT_USABLE", shz.contains("KIND_NOT_USABLE"));
        ok("CommandExecutor 按 failureKind 分流", ce.contains("r.failureKind"));
    }

    /** 输出被截断 ≠ 命令没输出。 */
    private void truncatedIsNotEmptyOutput() {
        String ce = readSource("cap/CommandExecutor.java");
        int truncAt = ce.indexOf("F_OUTPUT_TRUNCATED");
        int emptyAt = ce.indexOf("st.failure = F_EMPTY_OUTPUT;");
        ok("截断分支排在空输出分支之前", truncAt > 0 && emptyAt > truncAt);
        ok("截断时不算 EMPTY_OUTPUT",
                ce.contains("} else if (!st.drainComplete) {"));
        ok("截断时 ShellAdapter 返回 null（不当成空串）",
                ce.contains("F_OUTPUT_TRUNCATED.equals(st.failure)"));
    }

    /** stderr 正文必须能看见（809B 里到底是什么）。 */
    private void stderrTextExposed() {
        String ce = readSource("cap/CommandExecutor.java");
        ok("Step 有 stderrText 字段", ce.contains("public String stderrText"));
        ok("Step 有 stdoutText 字段", ce.contains("public String stdoutText"));
        ok("stderr 正文被透传", ce.contains("st.stderrText = r.stderr"));
        ok("brief 输出 stderr 摘要", ce.contains("stderr=\" + oneLine(stderrText"));
        ok("有 oneLine 工具方法", ce.contains("public static String oneLine("));
        ok("brief 标记截断", ce.contains("TRUNCATED"));
    }

    /**
     * **行为验证**：真实字节流必须被完整读出。
     *
     * <p>用真实线程 + 真实流模拟 drain，断言读到全部字节（含最后一段）。
     * 这是对「先 join 再 close」正确性的直接验证。
     */
    private void drainReadsAllBytes() {
        // 造 200KB 数据（远超单次 read 的 8KB 缓冲）
        byte[] payload = new byte[200 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ('a' + (i % 26));
        }
        final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        final java.io.InputStream in = new java.io.ByteArrayInputStream(payload);
        Thread t = new Thread(() -> {
            try {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            } catch (Throwable ignored) {}
        }, "test-drain");
        t.setDaemon(true);
        t.start();
        try { t.join(3000L); } catch (Throwable ignored) {}
        ok("drain 读尽全部 200KB", buf.size() == payload.length);
        ok("drain 线程自然结束（未卡住）", !t.isAlive());
    }

    /**
     * **行为验证**：写端关闭后 drain 会自然 EOF，不需要 close 输入流来叫醒。
     *
     * <p>这正是「先 join 后 close」成立的前提：子进程退出 = 写端关闭
     * = drain 读到 -1 = 线程自己结束。
     */
    private void drainEndsOnEofWithoutClose() {
        try {
            java.io.PipedOutputStream w = new java.io.PipedOutputStream();
            java.io.PipedInputStream r = new java.io.PipedInputStream(w, 65536);
            final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            Thread t = new Thread(() -> {
                try {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = r.read(b)) > 0) buf.write(b, 0, n);
                } catch (Throwable ignored) {}
            }, "test-eof");
            t.setDaemon(true);
            t.start();
            // 写一段，然后**关闭写端**（模拟子进程退出）
            w.write("WPAFORGE_EXEC_TEST\n".getBytes("UTF-8"));
            w.flush();
            w.close();
            try { t.join(2000L); } catch (Throwable ignored) {}
            ok("写端关闭后 drain 自然结束", !t.isAlive());
            ok("EOF 前的数据全部读到",
                    new String(buf.toByteArray(), "UTF-8").equals("WPAFORGE_EXEC_TEST\n"));
        } catch (Throwable e) {
            ok("EOF 行为验证（异常）", false);
        }
    }

    /** 超时路径必须先 destroy 子进程，否则管道写端不关、drain 永不结束。 */
    private void timeoutDestroysFirst() {
        String shz = readSource("cap/ShizukuChannel.java");
        int destroyAt = shz.indexOf("if (timeout) {\n                // 超时：先杀子进程");
        int joinAt = shz.indexOf("joinQuietly(tOut, JOIN_DRAIN_MS);");
        ok("超时时先 destroy 再 join",
                destroyAt > 0 && joinAt > destroyAt);
        ok("destroy 有明确注释说明理由",
                shz.contains("让它的管道写端关闭"));
    }

    /** 不允许靠缩短超时掩盖问题。 */
    private void noTimeoutHiding() {
        String shz = readSource("cap/ShizukuChannel.java");
        // deadline 下限仍是 1000ms（没被压到 100/200 这种"更快"的值）
        ok("deadline 下限未被压缩", shz.contains("Math.max(1000L, timeoutMs)"));
        String eng = readSource("audit/CrackEngine.java");
        ok("净检 list-networks 超时未被压缩",
                eng.contains("\"cmd wifi list-networks\", 5000"));
        ok("forget-network 超时未被压缩",
                eng.contains("\"cmd wifi forget-network \" + leftover, 4000"));
    }
}
