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
