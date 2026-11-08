package io.github.wlmosv_png.wpaforge.wps;

/**
 * 台账落盘：一行一个任务，原子写 + 备份 + 坏行容错。
 *
 * <h3>为什么不用 SharedPreferences</h3>
 * 台账是**追加型历史**（可能几百条），而 SharedPreferences 每次写都要序列化整个文件、
 * 且没有「单条删除」的概念。用文件还顺便得到一个好处：坏行能跳过，
 * 一条写坏的记录不会让整份历史读不出来。
 *
 * <h3>原子写三步<\/h3>
 * <pre>
 *   1) 全量写 file.tmp
 *   2) 现有 file → file.bak
 *   3) file.tmp → renameTo(file)
 * <\/pre>
 * 任何一步失败都返回 false 且**不动现有文件**：宁可这次没保存成功，
 * 也不能出现「新数据没写进去、旧数据也没了」。rename 在同一个目录内，
 * POSIX 上是原子替换。
 *
 * <h3>读的三层兜底<\/h3>
 * 主文件 → 备份 → 空表。坏行跳过并计数（{@link #lastLoadSkipped}），
 * 说明放在 {@link #lastLoadNote} 里，让 UI 能如实告诉用户「有几条读不出来」，
 * 而不是静默少几条历史。
 *
 * <h3>删除只有一个入口<\/h3>
 * {@link #deleteTask} 是**唯一**允许删历史的入口（用户主动删除）。
 * 重置进度、清断点都走 {@link WpsCheckpointStore#clear}，那是另一份存储，
 * 碰不到这里。
 */
public final class WpsLedgerStore {

    private final java.io.File file;
    private int lastLoadSkipped = 0;
    private String lastLoadNote = "";

    /** 传 null 时所有方法安全降级（{@link #load} 返回空表）。 */
    public WpsLedgerStore(java.io.File file) {
        this.file = file;
    }

    /** 读台账；坏行跳过并计数；主文件坏 → 用 .bak。 */
    public java.util.List<WpsLedger.Task> load() {
        lastLoadSkipped = 0;
        lastLoadNote = "";
        java.util.List<WpsLedger.Task> empty = new java.util.ArrayList<>();
        if (file == null) {
            lastLoadNote = "未配置台账文件";
            return empty;
        }
        try {
            java.io.File bak = bakFile();

            if (!file.isFile()) {
                if (bak != null && bak.isFile()) {
                    Read r = read(bak);
                    lastLoadSkipped = r.skipped;
                    lastLoadNote = "主文件不存在，已用备份"
                            + (r.skipped > 0 ? "（跳过 " + r.skipped + " 行无法解析的记录）" : "");
                    return r.tasks;
                }
                lastLoadNote = "还没有台账文件";
                return empty;
            }

            Read r = read(file);
            if (r.tasks.isEmpty() && bak != null && bak.isFile()) {
                Read rb = read(bak);
                if (!rb.tasks.isEmpty()) {
                    lastLoadSkipped = rb.skipped;
                    lastLoadNote = "主文件损坏，已用备份"
                            + (rb.skipped > 0 ? "（备份中跳过 " + rb.skipped + " 行）" : "");
                    return rb.tasks;
                }
            }
            lastLoadSkipped = r.skipped;
            lastLoadNote = r.skipped > 0 ? ("跳过 " + r.skipped + " 行无法解析的记录") : "";
            return r.tasks;
        } catch (Throwable t) {
            lastLoadNote = "读取台账失败：" + t;
            return empty;
        }
    }

    /** 原子写：先写 .tmp，再备份现有为 .bak，最后改名。 */
    public boolean upsert(WpsLedger.Task t) {
        if (file == null || t == null) return false;
        java.util.List<WpsLedger.Task> all = load();
        return writeAll(WpsLedger.upsert(all, t));
    }

    /** **唯一**允许删历史的入口（用户主动删除）。 */
    public boolean deleteTask(String taskId) {
        if (file == null || taskId == null) return false;
        java.util.List<WpsLedger.Task> all = load();
        java.util.List<WpsLedger.Task> next = new java.util.ArrayList<>();
        boolean removed = false;
        for (WpsLedger.Task t : all) {
            if (t == null) continue;
            if (taskId.equals(t.taskId)) removed = true;
            else next.add(t);
        }
        if (!removed) return false;
        return writeAll(next);
    }

    /** 上次 load 跳过的坏行数。 */
    public int lastLoadSkipped() {
        return lastLoadSkipped;
    }

    /** 上次 load 的说明（例如「主文件损坏，已用备份」）。 */
    public String lastLoadNote() {
        return lastLoadNote;
    }

    // ---------------- internals ----------------

    private java.io.File bakFile() {
        return file == null ? null : new java.io.File(file.getPath() + ".bak");
    }

    private static final class Read {
        final java.util.List<WpsLedger.Task> tasks = new java.util.ArrayList<>();
        int skipped;
    }

    /** 逐行读；空行忽略，解不出来的行计数并跳过。 */
    private static Read read(java.io.File f) {
        Read r = new Read();
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                WpsLedger.Task t = WpsLedger.Task.decode(line);
                if (t == null) {
                    r.skipped++;
                    continue;
                }
                r.tasks.add(t);
            }
        } catch (Throwable t) {
            // 读失败：已读到的记录保留（好过整份丢），不作坏行计数
        } finally {
            closeQuietly(br);
        }
        return r;
    }

    private boolean writeAll(java.util.List<WpsLedger.Task> list) {
        if (file == null) return false;
        java.io.File tmp = new java.io.File(file.getPath() + ".tmp");
        try {
            java.io.File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();

            // 1) tmp 全量写
            java.io.Writer w = null;
            try {
                w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(tmp), "UTF-8");
                if (list != null) {
                    for (WpsLedger.Task t : list) {
                        if (t == null) continue;
                        String line = t.encode();
                        if (line == null || line.isEmpty()) continue;
                        w.write(line);
                        w.write("\n");
                    }
                }
                w.flush();
            } finally {
                closeQuietly(w);
            }

            // 2) 现有文件 → .bak
            if (file.isFile()) {
                java.io.File bak = bakFile();
                if (bak == null || !copy(file, bak)) {
                    deleteQuietly(tmp);
                    return false;
                }
            }

            // 3) tmp → file（同目录 rename，原子替换）
            if (!tmp.renameTo(file)) {
                deleteQuietly(tmp);
                return false;
            }
            return true;
        } catch (Throwable t) {
            deleteQuietly(tmp);
            return false;
        }
    }

    private static boolean copy(java.io.File src, java.io.File dst) {
        java.io.FileInputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            in = new java.io.FileInputStream(src);
            out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
            // 关不掉不影响结果
        }
    }

    private static void deleteQuietly(java.io.File f) {
        if (f == null) return;
        try {
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {
            // 删不掉就留着，不影响返回值
        }
    }
}
