package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.TestPaths;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * L1 文件事务测试。
 *
 * <h3>本类里有两类断言，明确区分</h3>
 * <ol>
 *   <li><b>行为验证</b>（真建文件、真写、真替换、真读回）——
 *       {@link AtomicFileOp#probeAtomicReplace} 与各 {@code writeAtomic} 用例。</li>
 *   <li><b>源码契约</b>—— 「永不删除目标文件」这条约定在代码里的存在性。
 *       它**不能**替代行为验证，只是防止约定被改回去。</li>
 * </ol>
 *
 * <h3>测试落在哪个文件系统（重要）</h3>
 * 门禁的工作目录是 {@code /workspace}，它 bind-mount 到 Android 的
 * {@code /data/local/tmp/eta}，即 **Android /data 分区**。因此这里跑出来的
 * rename 语义与 App 私有目录（同在 /data）一致，不是"桌面 ext4 的结论"。
 * 本测试会把实测到的文件系统类型打印出来。
 */
public final class AtomicFileOpTest {

    private static int pass = 0, fail = 0;

    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static File tmpDir;

    public static void main(String[] args) throws Exception {
        System.out.println("=== L1 文件事务（原子替换实测 + 失败保护） ===");
        /* 测试目录刻意放在**仓库根之下**：门禁里 TestPaths.root() 指向
         * <root>，而 /workspace 是 Android /data 的
         * bind mount —— 与 App 私有目录同一文件系统。
         * 若用 Files.createTempDirectory（/tmp），测的就是 Linux rootfs，
         * rename 语义不能代表设备。跑完在 finally 里删除。 */
        tmpDir = new File(TestPaths.root(), ".atomic-test-" + System.currentTimeMillis());
        tmpDir.mkdirs();

        // ---------- ① 实测本机文件系统的原子替换语义 ----------
        {
            info("实测目录 : " + tmpDir.getCanonicalPath());
            info("落点说明 : /workspace 是 Android /data 的 bind mount"
                    + "（/data/local/tmp/eta），与 App 私有目录同一文件系统");
            info("可用空间 : " + (tmpDir.getUsableSpace() / 1024 / 1024) + " MB");

            boolean probe = AtomicFileOp.probeAtomicReplace(tmpDir);
            info("覆盖已存在目标的实测结果 : " + (probe ? "成功（原子替换可用）" : "失败"));
            ok("本机同目录 rename 覆盖已存在目标可用（实测，非推断）", probe,
                    "probe 返回 false");
        }

        // ---------- ② 正常写入 ----------
        {
            File t = new File(tmpDir, "ok.bin");
            AtomicFileOp.Result r = AtomicFileOp.writeAtomic(t,
                    out -> out.write("hello".getBytes(StandardCharsets.UTF_8)),
                    AtomicFileOp.nonEmpty());
            ok("正常写入 OK", r == AtomicFileOp.Result.OK, String.valueOf(r));
            ok("内容正确", "hello".equals(AtomicFileOp.readText(t)),
                    AtomicFileOp.readText(t));
        }

        // ---------- ③ 覆盖已存在文件（最常走的路径） ----------
        {
            File t = new File(tmpDir, "overwrite.bin");
            AtomicFileOp.writeText(t, "OLD");
            AtomicFileOp.Result r = AtomicFileOp.writeAtomic(t,
                    out -> out.write("NEW".getBytes(StandardCharsets.UTF_8)),
                    AtomicFileOp.nonEmpty());
            ok("覆盖已存在文件成功", r == AtomicFileOp.Result.OK, String.valueOf(r));
            ok("内容被完整替换（不是追加、不是空）",
                    "NEW".equals(AtomicFileOp.readText(t)), AtomicFileOp.readText(t));
        }

        // ---------- ④ 写入失败 → 原文件必须一字节未动 ----------
        {
            File t = new File(tmpDir, "protected.bin");
            AtomicFileOp.writeText(t, "ORIGINAL");
            AtomicFileOp.Result r = AtomicFileOp.writeAtomic(t,
                    out -> {
                        out.write("partial".getBytes(StandardCharsets.UTF_8));
                        throw new java.io.IOException("模拟磁盘满/中断");
                    },
                    AtomicFileOp.nonEmpty());
            ok("写入抛异常 → FAILED_WRITE", r == AtomicFileOp.Result.FAILED_WRITE,
                    String.valueOf(r));
            ok("原文件内容未被破坏（关键：不做先删后改）",
                    "ORIGINAL".equals(AtomicFileOp.readText(t)),
                    AtomicFileOp.readText(t));
            ok("失败后不留 tmp",
                    countTmp(tmpDir, "protected.bin") == 0,
                    String.valueOf(countTmp(tmpDir, "protected.bin")));
        }

        // ---------- ⑤ 校验失败 → 原文件不动 ----------
        {
            File t = new File(tmpDir, "validated.bin");
            AtomicFileOp.writeText(t, "KEEP");
            AtomicFileOp.Result r = AtomicFileOp.writeAtomic(t,
                    out -> out.write("x".getBytes(StandardCharsets.UTF_8)),
                    tmp -> false);
            ok("校验不通过 → FAILED_VALIDATION",
                    r == AtomicFileOp.Result.FAILED_VALIDATION, String.valueOf(r));
            ok("原文件未动", "KEEP".equals(AtomicFileOp.readText(t)),
                    AtomicFileOp.readText(t));
        }

        // ---------- ⑥ 校验器抛异常 → 也算失败（不能默默放过） ----------
        {
            File t = new File(tmpDir, "vex.bin");
            AtomicFileOp.writeText(t, "SAFE");
            AtomicFileOp.Result r = AtomicFileOp.writeAtomic(t,
                    out -> out.write("y".getBytes(StandardCharsets.UTF_8)),
                    tmp -> { throw new RuntimeException("校验器崩了"); });
            ok("校验器抛异常 → 按失败处理", r == AtomicFileOp.Result.FAILED_VALIDATION,
                    String.valueOf(r));
            ok("原文件未动", "SAFE".equals(AtomicFileOp.readText(t)),
                    AtomicFileOp.readText(t));
        }

        // ---------- ⑦ 目标不是普通文件 ----------
        {
            File d = new File(tmpDir, "a-directory");
            d.mkdirs();
            AtomicFileOp.Result r = AtomicFileOp.writeAtomic(d,
                    out -> out.write("z".getBytes(StandardCharsets.UTF_8)),
                    AtomicFileOp.NO_VALIDATION);
            ok("目标是目录 → TARGET_NOT_REGULAR",
                    r == AtomicFileOp.Result.TARGET_NOT_REGULAR, String.valueOf(r));
        }

        // ---------- ⑧ 新建（目标不存在） ----------
        {
            File t = new File(tmpDir, "brand-new.bin");
            AtomicFileOp.Result r = AtomicFileOp.writeAtomic(t,
                    out -> out.write("fresh".getBytes(StandardCharsets.UTF_8)),
                    AtomicFileOp.nonEmpty());
            ok("目标不存在时也能写", r == AtomicFileOp.Result.OK
                    && "fresh".equals(AtomicFileOp.readText(t)), String.valueOf(r));
        }

        // ---------- ⑨ 并发：唯一 tmp 名，不会互相覆盖 ----------
        {
            File t = new File(tmpDir, "concurrent.bin");
            final int[] done = {0};
            Thread[] th = new Thread[4];
            for (int i = 0; i < th.length; i++) {
                final int k = i;
                th[i] = new Thread(() -> {
                    AtomicFileOp.writeAtomic(t,
                            out -> out.write(("W" + k).getBytes(StandardCharsets.UTF_8)),
                            AtomicFileOp.nonEmpty());
                    synchronized (done) { done[0]++; }
                });
                th[i].start();
            }
            for (Thread x : th) x.join(5000);
            String got = AtomicFileOp.readText(t);
            ok("4 线程并发写全部完成", done[0] == 4, String.valueOf(done[0]));
            ok("最终内容是某一次的完整结果（不是两份混在一起）",
                    "W0".equals(got) || "W1".equals(got) || "W2".equals(got) || "W3".equals(got),
                    got);
        }

        // ---------- ⑩ 残留 tmp 清理（不动"正在写"的） ----------
        {
            File dir = new File(tmpDir, "cleanup");
            dir.mkdirs();
            File old = new File(dir, "x.json.tmp-111-aa");
            File fresh = new File(dir, "x.json.tmp-222-bb");
            AtomicFileOp.writeText(old, "old");
            AtomicFileOp.writeText(fresh, "fresh");
            old.setLastModified(System.currentTimeMillis() - 3_600_000L);
            int n = AtomicFileOp.cleanupStaleTmp(dir, 60_000L);
            ok("只清理过期 tmp", n == 1 && !old.exists() && fresh.exists(), "n=" + n);
        }

        // ---------- ⑪ 源码契约：绝不删除目标文件 ----------
        {
            File src = new File(TestPaths.javaPkgDir(), "audit/AtomicFileOp.java");
            if (!src.isFile()) {
                ok("AtomicFileOp.java 可读", false, src.getAbsolutePath());
            } else {
                String s = String.join("\n",
                        Files.readAllLines(src.toPath(), StandardCharsets.UTF_8));
                String code = stripComments(s);
                /* 精确到**替换路径**：probeAtomicReplace 里删的是它自己刚建的探测
                 * 文件（清理），不属于"先删目标再改名"。真正要拦的是
                 * moveAtomic / writeAtomic 的替换分支里出现目标的 delete。 */
                String mover = methodBody(code, "moveAtomic");
                ok("替换路径（moveAtomic）不删除目标文件",
                        !mover.contains("delete()"), "moveAtomic 里出现了 delete");
                ok("不出现「先删后改名」的旧缺陷模式 f.delete() + renameTo(f)",
                        !code.contains("f.delete();"), "出现了 f.delete();");
                ok("使用了 fsync（防止 rename 后内容为空）",
                        code.contains("getFD().sync()"), "缺少 sync");
                ok("临时文件与目标同目录（跨文件系统 rename 会失败）",
                        code.contains("dir == null ? new File(n) : new File(dir, n)"), "不同目录");
                ok("先试 ATOMIC_MOVE，再退回 renameTo",
                        code.contains("StandardCopyOption.ATOMIC_MOVE"), "缺 ATOMIC_MOVE");
            }
        }

        // ---------- ⑫ 既有两条正确写法与两条缺陷写法的对照（回归基线） ----------
        {
            String tried = readPkg("audit/TriedSet.java");
            String hist = readPkg("audit/HistStore.java");
            ok("TriedSet 用 renameTo(f) || f.exists() 模式（既有正确写法，不删原文件）",
                    tried.contains("tmp.renameTo(f) || f.exists()"), "写法已变");
            ok("HistStore 用 renameTo(f) || f.isFile() 模式（既有正确写法）",
                    hist.contains("tmp.renameTo(f) || f.isFile()"), "写法已变");
            info("DictPlan.save / AttemptLedger.save 的缺陷修复见本批 L1 修复提交");
        }

        deleteRec(tmpDir);
        System.out.println();
        System.out.println("=== L1: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** 递归删除测试目录（不给仓库留垃圾）。 */
    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) deleteRec(c);
        f.delete();
    }

    /** 截取某方法的方法体（从签名到下一个 4 空格缩进的右大括号）。 */
    private static String methodBody(String code, String methodName) {
        // 精确匹配方法声明，避免匹配到调用的地方（例如 if (moveAtomic(...))）
        int i = code.indexOf("boolean " + methodName + "(");
        if (i < 0) i = code.indexOf("void " + methodName + "(");
        if (i < 0) i = code.indexOf(methodName + "(");
        if (i < 0) return "";
        int j = code.indexOf("\n    }", i);
        return j < 0 ? code.substring(i) : code.substring(i, j);
    }

    /** 去掉注释行，只留代码 —— 避免"注释里提到某写法"被误判成"代码用了它"。 */
    private static String stripComments(String src) {
        StringBuilder sb = new StringBuilder();
        for (String line : src.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) continue;
            int p = line.indexOf("//");
            sb.append(p >= 0 ? line.substring(0, p) : line).append('\n');
        }
        return sb.toString();
    }

    private static String readPkg(String rel) throws Exception {
        File f = new File(TestPaths.javaPkgDir(), rel);
        if (!f.isFile()) return "";
        return String.join("\n", Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
    }

    private static int countTmp(File dir, String base) {
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) if (f.getName().startsWith(base) && f.getName().contains(".tmp-")) n++;
        return n;
    }
}
