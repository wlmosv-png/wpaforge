package io.github.wlmosv_png.wpaforge.audit;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * **文件事务**：把「写坏一半」这件事从可能性里去掉。
 *
 * <h3>为什么单独一层</h3>
 * 2026-11-02 核对发现，同一个仓库里存在**两种互不兼容**的保存写法：
 * <pre>
 *   ✅ TriedSet.save  ： tmp.renameTo(f) || f.exists()        // 不删原文件
 *   ✅ HistStore.save ： tmp.renameTo(f) || f.isFile()        // 不删原文件
 *   ❌ DictPlan.save  ： if (!tmp.renameTo(f)) { f.delete(); ... }   // 先删原文件
 *   ❌ AttemptLedger.save：同上
 * </pre>
 * 后两者的第二个分支一旦失败，**原文件已经没了、新文件也没到位** —— 计划与账本
 * 会直接丢失。这是本类存在的理由：所有落盘走同一条路径，且这条路径
 * **永不删除目标文件**。
 *
 * <h3>写入顺序（每一步都不可省）</h3>
 * <pre>
 *   ① tmp = 同目录 + 唯一名（.tmp-<ts>-<rand>）   ← 同目录才能同文件系统 rename
 *   ② 写 tmp → flush → **fd.sync()** → close      ← 没有 sync 会「rename 成功但内容空」
 *   ③ 校验 tmp（调用方给的 Validator）             ← 校验不过就是失败，不是"差不多就行"
 *   ④ 原子替换：Files.move(ATOMIC_MOVE) → 退回 renameTo
 *   ⑤ 任何一步失败 → 删 tmp，**目标文件保持原样**
 * </pre>
 *
 * <h3>关于 renameTo 的语义（本机已实测，见 AtomicFileOpTest）</h3>
 * 在 Android 的 /data 分区上，<b>同目录</b>的 {@code renameTo} 覆盖一个已存在的
 * 目标会成功，且是 {@code rename(2)} 的原子替换。跨文件系统才会失败（EXDEV）。
 * 因此本类**先尝试 {@code Files.move(ATOMIC_MOVE)}**（语义更明确），
 * 失败再退回 {@code renameTo}，两者都失败才算失败。
 *
 * <p>纯 IO，无 Android 依赖，可在 JVM 完整测试（测试运行在 /workspace，
 * 即 Android /data 分区，与 App 私有目录同一文件系统）。
 */
public final class AtomicFileOp {

    private AtomicFileOp() {}

    /** 写入结果。**失败必须能被界面区分**，不能笼统说"保存失败"。 */
    public enum Result {
        OK,
        /** 写 tmp 失败（磁盘满、权限、IO）。 */
        FAILED_WRITE,
        /** 数据同步失败 —— 写进去了但没落盘，按失败处理。 */
        FAILED_SYNC,
        /** 写完的 tmp 没通过校验。 */
        FAILED_VALIDATION,
        /** 原子替换失败（跨文件系统 / 权限）。 */
        FAILED_MOVE,
        /** 目标路径存在但不是普通文件（目录 / 设备节点）。 */
        TARGET_NOT_REGULAR,
        FAILED_UNKNOWN;

        public boolean ok() { return this == OK; }
    }

    /** 把内容写进流；由调用方决定格式。 */
    public interface Body {
        void write(OutputStream out) throws Exception;
    }

    /** 校验临时文件是否是一份**完整可用**的产物。 */
    public interface Validator {
        boolean valid(File tmp);
    }

    /** 空校验（不校验）——仅在调用方另有保证时使用。 */
    public static final Validator NO_VALIDATION = tmp -> true;

    /**
     * 原子写：目标要么是旧内容，要么是完整的新内容，**不存在中间态**。
     *
     * @param target 目标文件（父目录不存在会自动创建）
     * @return 结构化结果；非 {@link Result#OK} 时目标文件**一定未被改动**
     */
    public static Result writeAtomic(File target, Body body, Validator validator) {
        if (target == null || body == null) return Result.FAILED_UNKNOWN;
        File dir = target.getParentFile();
        try {
            if (dir != null && !dir.exists() && !dir.mkdirs()) return Result.FAILED_WRITE;
            if (target.exists() && !target.isFile()) return Result.TARGET_NOT_REGULAR;

            File tmp = tmpFor(target);
            // ① 写 tmp
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                body.write(out);
                out.flush();
                // ② fsync：不做的话，rename 之后可能拿到一个长度为 0 的文件
                try {
                    out.getFD().sync();
                } catch (Throwable t) {
                    // 少数文件系统不支持 sync；此时按失败处理更安全
                    tmp.delete();
                    return Result.FAILED_SYNC;
                }
            } catch (Exception e) {
                tmp.delete();
                return Result.FAILED_WRITE;
            }

            // ③ 校验
            boolean valid;
            try {
                valid = validator == null || validator.valid(tmp);
            } catch (Throwable t) {
                valid = false;
            }
            if (!valid) {
                tmp.delete();
                return Result.FAILED_VALIDATION;
            }

            // ④ 原子替换（**绝不删除 target**）
            if (moveAtomic(tmp, target)) return Result.OK;
            tmp.delete();
            return Result.FAILED_MOVE;
        } catch (Throwable t) {
            return Result.FAILED_UNKNOWN;
        }
    }

    /**
     * 原子替换：先 {@code Files.move(ATOMIC_MOVE)}，失败退回 {@code renameTo}。
     *
     * <p><b>任何分支都不删除 {@code target}</b> —— 这是本层最硬的一条约定，
     * 有测试（{@code AtomicFileOpTest}）从源码与行为两侧钉住。
     */
    static boolean moveAtomic(File src, File target) {
        try {
            Files.move(src.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Throwable ignored) {
            // 落到 renameTo：同目录时它就是 rename(2)，覆盖已存在目标是原子的
        }
        try {
            return src.renameTo(target);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 唯一临时名：与目标同目录，避免跨文件系统的 rename 失败。 */
    public static File tmpFor(File target) {
        String n = target.getName() + ".tmp-" + System.currentTimeMillis()
                + "-" + Integer.toHexString(new java.util.Random().nextInt());
        File dir = target.getParentFile();
        return dir == null ? new File(n) : new File(dir, n);
    }

    /**
     * **实测**本目录所在的文件系统能否原子替换已存在的目标。
     *
     * <p>这不是"读文档推断"，而是真的建文件、真的替换、真的读回验证 ——
     * 结果由 {@code AtomicFileOpTest} 打印并断言。
     *
     * @return true = 覆盖已存在目标成功且内容正确
     */
    public static boolean probeAtomicReplace(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File target = new File(dir, ".atomic-probe-target");
        File tmp = new File(dir, ".atomic-probe-tmp");
        try {
            writeText(target, "OLD");
            writeText(tmp, "NEW");
            boolean moved = moveAtomic(tmp, target);
            String got = readText(target);
            return moved && "NEW".equals(got);
        } catch (Throwable t) {
            return false;
        } finally {
            target.delete();
            tmp.delete();
        }
    }

    /**
     * 清理残留的临时文件（上次失败/被杀留下的）。
     *
     * @param olderThanMs 只清理「最后修改时间早于 now - olderThanMs」的，避免误删正在写的
     */
    public static int cleanupStaleTmp(File dir, long olderThanMs) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        long cutoff = System.currentTimeMillis() - Math.max(0, olderThanMs);
        int n = 0;
        for (File f : fs) {
            if (!f.isFile()) continue;
            if (!f.getName().contains(".tmp-")) continue;
            if (f.lastModified() > cutoff) continue;
            if (f.delete()) n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- 小工具

    static void writeText(File f, String s) throws Exception {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            o.flush();
            try { ((FileOutputStream) o).getFD().sync(); } catch (Throwable ignored) { }
        }
    }

    static String readText(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            char[] b = new char[1024];
            int n;
            while ((n = r.read(b)) > 0) sb.append(b, 0, n);
        }
        return sb.toString();
    }

    /** 校验器：文件存在、非空。 */
    public static Validator nonEmpty() {
        return tmp -> tmp != null && tmp.isFile() && tmp.length() > 0;
    }
}
