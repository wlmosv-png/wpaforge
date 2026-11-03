package io.github.wlmosv_png.wpaforge.audit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 存储 IO 接缝（P0-0）。
 *
 * <h3>它存在的唯一理由</h3>
 * 让测试能注入「读失败 / 写失败 / 中途崩溃」，从而把
 * {@link TaskQueue} 的真实生产逻辑逼进故障分支。
 *
 * <h3>为什么是 InputStream/OutputStream 而不是 byte[]</h3>
 * 原实现用 {@code BufferedReader.readLine()} 循环拼接，语义上**丢弃了换行符**。
 * 若接缝改成「一次读全部字节」，就必须重新实现这段拼接，等于**改了生产逻辑**。
 * 所以接缝只提供「打开流」，原有读取/写入代码一行不动 ——
 * 这是本接口最重要的设计约束：**零语义变化**。
 *
 * <h3>生产实现的行为等价性</h3>
 * {@link FileImpl} 的每个方法都是对 {@code java.io.File} 的直接转发，
 * 参数与返回值语义与改造前的内联代码逐条对应：
 * <ul>
 *   <li>{@code f.isFile()}      → {@link #isFile}</li>
 *   <li>{@code f.exists()}      → {@link #exists}</li>
 *   <li>{@code f.length()}      → {@link #length}</li>
 *   <li>{@code new FileInputStream(f)}  → {@link #openRead}</li>
 *   <li>{@code new FileOutputStream(f)} → {@link #openWrite}</li>
 *   <li>{@code f.delete()}      → {@link #delete}</li>
 *   <li>{@code a.renameTo(b)}   → {@link #rename}</li>
 * </ul>
 *
 * <h3>事务写（P0-3）</h3>
 * {@link #beginWrite}/{@link #finishWrite}/{@link #abortWrite} 直接转发到
 * {@code android.util.AtomicFile} 的 startWrite/finishWrite/failWrite。
 * **不自己实现原子替换** —— 平台已经做了 fsync + rename，自己再实现一遍只会更差。
 *
 * <h3>边界</h3>
 * 本接口**只**负责字节进出，不负责：原子替换策略、隔离、完整性判定、
 * 备份命名 —— 那些是后续阶段（P0-2..P0-5）的事。
 */
public interface StoreIo {

    /** 是否存在且是普通文件（等价 {@code File.isFile()}）。 */
    boolean isFile(String name);

    /** 是否存在（等价 {@code File.exists()}）。 */
    boolean exists(String name);

    /** 文件长度；不存在时与 {@code File.length()} 一致返回 0。 */
    long length(String name);

    /** 打开读流。异常类型与 {@code new FileInputStream(File)} 一致。 */
    InputStream openRead(String name) throws IOException;

    /** 打开写流。异常类型与 {@code new FileOutputStream(File)} 一致。 */
    OutputStream openWrite(String name) throws IOException;

    /**
     * **事务写：开始**（P0-3）。
     *
     * <p>返回的流必须交给 {@link #finishWrite} 或 {@link #abortWrite} 收尾。
     * 只 startWrite 不 finish 就等于事务未提交 —— 这正是崩溃场景的语义。
     *
     * <p>生产实现走 {@code android.util.AtomicFile.startWrite()}：
     * 它**不动原文件**，只创建一个临时文件。
     */
    OutputStream beginWrite(String name) throws IOException;

    /**
     * **事务写：提交**。
     *
     * <p>生产实现走 {@code AtomicFile.finishWrite()}：内部 fsync → close →
     * rename(临时文件 → 目标)，原文件被原子替换，**没有 delete 窗口**。
     *
     * @return true = 已提交；false = 提交失败（调用方必须视为写入失败）
     */
    boolean finishWrite(String name, OutputStream handle);

    /**
     * **事务写：回滚**。
     *
     * <p>生产实现走 {@code AtomicFile.failWrite()}：删除临时文件，**原文件一字不动**。
     */
    void abortWrite(String name, OutputStream handle);

    /** 删除；等价 {@code File.delete()}（不存在返回 false）。 */
    boolean delete(String name);

    /** 改名；等价 {@code File.renameTo}（失败返回 false，不抛）。 */
    boolean rename(String from, String to);

    /** 目录下的文件名列表；失败返回空数组（与 {@code File.list()} 的 null 语义对齐）。 */
    String[] list();

    /** 诊断用描述（不进任何判定）。 */
    String describe();

    /**
     * 生产实现：直接转发到 {@code java.io.File}。
     *
     * <p><b>本类不做任何重试、缓冲、日志或异常转换</b> ——
     * 任何"聪明"的处理都会让生产行为与改造前不一致，
     * 从而让基于本接缝写的测试失去证明力。
     */
    final class FileImpl implements StoreIo {

        private final File dir;

        public FileImpl(File dir) {
            this.dir = dir;
        }

        private File at(String name) {
            return new File(dir, name);
        }

        @Override public boolean isFile(String name) { return at(name).isFile(); }
        @Override public boolean exists(String name) { return at(name).exists(); }
        @Override public long length(String name) { return at(name).length(); }

        @Override public InputStream openRead(String name) throws IOException {
            return new FileInputStream(at(name));
        }

        @Override public OutputStream openWrite(String name) throws IOException {
            return new FileOutputStream(at(name));
        }

        /**
         * 事务写开始：走 {@code AtomicFile.startWrite()}。
         *
         * <p>实测（Android 15 / OnePlus，见 doc/P0-PERSISTENCE-DESIGN.md §0.2）：
         * startWrite **不删除、不改名原文件**，只创建一个 {@code .new} 临时文件。
         * 因此崩溃在这里时原文件完好。
         */
        @Override public OutputStream beginWrite(String name) throws IOException {
            return new android.util.AtomicFile(at(name)).startWrite();
        }

        /**
         * 事务写提交：走 {@code AtomicFile.finishWrite()}。
         *
         * <p>内部顺序（本机字节码确认）：{@code FileUtils.sync(fos)} → {@code close}
         * → {@code rename(.new → base)}。前两步失败会走 delete(.new) 分支；
         * 我们额外做一次**内容回读校验**，把「返回成功但内容不对」也判为失败。
         */
        @Override public boolean finishWrite(String name, OutputStream handle) {
            if (!(handle instanceof java.io.FileOutputStream)) {
                try { handle.close(); } catch (Throwable ignored) {}
                return false;
            }
            android.util.AtomicFile af = new android.util.AtomicFile(at(name));
            try {
                af.finishWrite((java.io.FileOutputStream) handle);
            } catch (Throwable t) {
                return false;
            }
            // 提交后回读校验：确认目标文件真的存在且非空
            File f = at(name);
            return f.isFile() && f.length() > 0;
        }

        /**
         * 事务写回滚：走 {@code AtomicFile.failWrite()}。
         *
         * <p>实测：原文件字节保持不变，只删除 {@code .new}。
         */
        @Override public void abortWrite(String name, OutputStream handle) {
            if (!(handle instanceof java.io.FileOutputStream)) {
                try { handle.close(); } catch (Throwable ignored) {}
                return;
            }
            try {
                new android.util.AtomicFile(at(name))
                        .failWrite((java.io.FileOutputStream) handle);
            } catch (Throwable ignored) {
                try { handle.close(); } catch (Throwable ignored2) {}
            }
        }

        @Override public boolean delete(String name) { return at(name).delete(); }

        @Override public boolean rename(String from, String to) {
            return at(from).renameTo(at(to));
        }

        @Override public String[] list() {
            String[] a = dir.list();
            return a == null ? new String[0] : a;
        }

        @Override public String describe() { return "file:" + dir; }
    }
}
