package io.github.wlmosv_png.wpaforge.audit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.content.Context;

/**
 * {@link RestoreJournal.Store} 的文件实现：应用私有目录里的 {@code restore.json}。
 *
 * <h3>为什么要单独一个文件，而不是塞进 queue.json</h3>
 * 恢复记录的生命周期比任务长：**任务被杀、进程被杀、甚至设备重启之后**
 * 它都必须还在。塞进队列会遇到两个问题：
 * <ul>
 *   <li>队列会被用户「清空已完成任务」整批重写，一不小心把恢复记录一起删了；</li>
 *   <li>队列的读失败保护（{@code refuseSave}）会让写入偶发失败 ——
 *       恢复记录写不进去就等于没有保护，不能接受这种偶发。</li>
 * </ul>
 *
 * <h3>写入方式</h3>
 * 先写 {@code .tmp} 再改名：掉电/被杀最多丢掉这一次写入，
 * 绝不会留下半截内容 —— 半截内容解析必然失败，那就等于没有记录。
 * 文件权限靠应用私有目录本身的 0700 保证。
 *
 * <h3>1.9.28：一个文件里可以有**多条**记录</h3>
 * 格式由 {@link RestoreJournal#encode} 决定（首行 {@code version=2}，
 * 每条以 {@code ---} 起头）。旧格式（整份就是一条）仍能读。
 * 新增 {@link #backup(Context)}：清理记录前先留一份可回溯的备份。
 */
public final class RestoreStoreFile {

    private static final String NAME = "restore.json";
    private static final String TMP = NAME + ".tmp";

    private RestoreStoreFile() {}

    public static RestoreJournal.Store file(Context ctx) {
        final Context app = ctx.getApplicationContext();
        return new RestoreJournal.Store() {
            @Override public String read() {
                try {
                    File f = new File(app.getFilesDir(), NAME);
                    if (!f.isFile()) return null;
                    byte[] buf = new byte[(int) Math.min(f.length(), 256 * 1024)];
                    try (FileInputStream in = new FileInputStream(f)) {
                        int n = in.read(buf);
                        if (n <= 0) return null;
                        return new String(buf, 0, n, StandardCharsets.UTF_8);
                    }
                } catch (Throwable t) {
                    return null;
                }
            }

            @Override public boolean write(String text) {
                if (text == null) return false;
                File tmp = new File(app.getFilesDir(), TMP);
                File dst = new File(app.getFilesDir(), NAME);
                try (OutputStreamWriter w = new OutputStreamWriter(
                        new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                    w.write(text);
                    w.flush();
                } catch (Throwable t) {
                    return false;
                }
                if (dst.exists() && !dst.delete()) {
                    // 删不掉也继续尝试改名覆盖
                }
                if (!tmp.renameTo(dst)) {
                    // 改名失败 ⇒ 盘上那份要么没变、要么还是**别人的旧记录**。
                    // 这里绝不能因为「文件存在」就返回成功：闸门会以为补做依据
                    // 已就位，进程被杀后却拿旧记录去恢复另一个目标（F7）。
                    tmp.delete();
                    return false;
                }
                // 回读校验：只有盘上内容与本次写入完全一致，才算真的落盘。
                return RestoreJournal.verifyWritten(this, text);
            }

            @Override public boolean clear() {
                try {
                    File dst = new File(app.getFilesDir(), NAME);
                    boolean a = !dst.exists() || dst.delete();
                    File tmp = new File(app.getFilesDir(), TMP);
                    if (tmp.exists()) tmp.delete();
                    return a;
                } catch (Throwable t) {
                    return false;
                }
            }
        };
    }

    /** 记录文件的绝对路径（界面展示用）。 */
    public static String path(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), NAME).getAbsolutePath();
    }

    /**
     * 把当前记录文件另存一份带时间戳的备份。
     *
     * <p>为什么必须有：清理记录是**不可逆**的操作（原口令密文只在这份文件里），
     * 而用户点“清理”时往往并不清楚自己放弃了什么。先备份，再删。
     *
     * @return 备份文件绝对路径；没有记录可备份时返回 {@code null}
     */
    public static String backup(Context ctx) {
        try {
            File dir = ctx.getApplicationContext().getFilesDir();
            File src = new File(dir, NAME);
            if (!src.isFile() || src.length() <= 0) return null;
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File dst = new File(dir, NAME + ".bak-" + ts);
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            }
            return dst.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }
}
