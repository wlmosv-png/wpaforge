package io.github.wlmosv_png.wpaforge.audit;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 字典回收站。
 *
 * <p>需求（字典页第一批）要求删除走回收站，避免直接永久删除。
 * 这里把「移入回收站 / 列出 / 恢复 / 彻底删除」做成纯文件 IO，
 * 无 Android 运行时依赖，可在 JVM 完整测试。
 *
 * <h3>为什么用子目录而不是改扩展名</h3>
 * {@link DictStore#scan} 只 {@code listFiles()} 直接子项并过滤
 * {@code .txt/.lst/.dic}，<b>不递归</b>。回收站是字典目录下的一个子目录
 * （{@code .trash/}），它本身是目录、不是文件，天然不会出现在主列表里；
 * 而回收站里的文件保留原名原扩展名，恢复时无需改名就能搬回去。
 *
 * <h3>语义边界（必须守住）</h3>
 * <ul>
 *   <li>移入回收站 ≠ 删除计划成员 —— 计划成员状态在 {@code dictplan.json}，与文件无关；</li>
 *   <li>恢复 ≠ 重新加入计划 —— 文件回到目录，计划成员身份由计划的 path/sha 决定；</li>
 *   <li>彻底删除（purge）才是真正不可恢复的操作，必须单独二次确认。</li>
 * </ul>
 */
public final class DictTrash {

    /** 回收站子目录名。以 {@code .} 开头：既是目录（scan 不扫），也让文件管理器里靠边。 */
    public static final String TRASH_DIR_NAME = ".trash";

    private DictTrash() {}

    /** 回收站目录（字典目录下的 {@value #TRASH_DIR_NAME}）。 */
    public static File trashDir(File dictDir) {
        return new File(dictDir, TRASH_DIR_NAME);
    }

    /**
     * 把一个字典文件移入回收站。
     *
     * <p>重名时追加时间戳后缀（保留原扩展名），不会覆盖回收站里已有的同名文件。
     *
     * @return 移入后回收站里的新文件；失败（源不存在 / rename 失败）返回 {@code null}，
     *         且源文件保持原样。
     */
    public static File trash(File dictDir, File dictFile) {
        if (dictDir == null || dictFile == null || !dictFile.isFile()) return null;
        File dir = trashDir(dictDir);
        if (!dir.exists() && !dir.mkdirs()) return null;
        if (!dir.isDirectory()) return null;

        File target = new File(dir, dictFile.getName());
        if (target.exists()) {
            String name = dictFile.getName();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            long ts = System.currentTimeMillis();
            target = new File(dir, base + "." + ts + ext);
            int seq = 1;
            while (target.exists()) {
                target = new File(dir, base + "." + ts + "-" + (seq++) + ext);
            }
        }
        // 同目录 rename 才是原子 move；回收站是 dictDir 的子目录，同一文件系统。
        return dictFile.renameTo(target) ? target : null;
    }

    /** 列出回收站里所有条目（按名字排序，稳定展示）。 */
    public static List<File> list(File dictDir) {
        List<File> out = new ArrayList<>();
        File dir = trashDir(dictDir);
        if (dir == null || !dir.isDirectory()) return out;
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isFile()) out.add(f);
        }
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    /**
     * 恢复：把回收站里的文件搬回字典目录。
     *
     * <p>目标已存在时追加 {@code -恢复} 后缀，绝不覆盖主目录里的现有文件。
     *
     * @return 恢复后的目标文件；失败返回 {@code null}（回收站原文件保持不动）。
     */
    public static File restore(File dictDir, File trashed) {
        if (dictDir == null || trashed == null || !trashed.isFile()) return null;
        if (!dictDir.isDirectory() && !dictDir.mkdirs()) return null;

        File target = new File(dictDir, trashed.getName());
        if (target.exists()) {
            String name = trashed.getName();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            target = new File(dictDir, base + "-恢复" + ext);
            int seq = 1;
            while (target.exists()) {
                target = new File(dictDir, base + "-恢复" + (seq++) + ext);
            }
        }
        return trashed.renameTo(target) ? target : null;
    }

    /** 彻底删除回收站里的一个文件（不可恢复）。 */
    public static boolean purgeOne(File trashed) {
        return trashed != null && trashed.isFile() && trashed.delete();
    }

    /** 清空整个回收站（不可恢复）。返回删除的文件数。 */
    public static int purgeAll(File dictDir) {
        File dir = trashDir(dictDir);
        if (dir == null || !dir.isDirectory()) return 0;
        File[] fs = dir.listFiles();
        int n = 0;
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile() && f.delete()) n++;
            }
        }
        // 空回收站目录留着也无妨；不删目录，避免并发时刚 move 进来的文件被连锅端。
        return n;
    }
}
