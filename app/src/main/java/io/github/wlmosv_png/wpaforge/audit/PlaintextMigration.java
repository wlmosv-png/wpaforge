package io.github.wlmosv_png.wpaforge.audit;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 旧数据的**明文口令迁移**（R12）。
 *
 * <h3>要清的是什么</h3>
 * 1.9.25 及更早版本把命中口令**明文**写进两处：
 * <ul>
 *   <li>{@code files/history.csv} 第 9 列；</li>
 *   <li>{@code files/queue.json} 的 {@code "hit"} 字段。</li>
 * </ul>
 * 任务日志早就脱敏了，这两处一直漏着 —— 磁盘上留着一份长期口令清单。
 *
 * <h3>怎么清（三条硬纪律）</h3>
 * <ol>
 *   <li><b>先备份</b>：原文件各复制一份 {@code *.plain.bak}，
 *       已存在就**不覆盖**（避免把最早的版本盖掉）。</li>
 *   <li><b>不删用户数据</b>：只把明文换成「标签 + 密文」，记录条数、时间、目标、
 *       条数、耗时全部原样保留。</li>
 *   <li><b>幂等</b>：跑第二遍没有任何可迁移内容，也不会再动文件。</li>
 * </ol>
 *
 * <h3>迁移会不会破坏功能</h3>
 * 读路径对新旧格式都认（见 {@link HistStore.Record#fromLine} 与
 * {@code TaskQueue.Task.fromJson}）。迁移后命中口令由 {@link SecretBox}
 * 解密还原 —— 换设备或改了签名会解不开，此时界面显示脱敏标签而不是编造值。
 */
public final class PlaintextMigration {

    public static final String HISTORY_BAK = "history.csv.plain.bak";
    public static final String QUEUE_BAK = "queue.json.plain.bak";

    private PlaintextMigration() {}

    /** 迁移结果。 */
    public static final class Report {
        public final boolean historyMigrated;
        public final boolean queueMigrated;
        public final int historyRows;
        public final int queueTasks;
        public final boolean historyBackupWritten;
        public final boolean queueBackupWritten;
        public final String note;

        Report(boolean hm, boolean qm, int hr, int qt, boolean hb, boolean qb, String note) {
            this.historyMigrated = hm;
            this.queueMigrated = qm;
            this.historyRows = hr;
            this.queueTasks = qt;
            this.historyBackupWritten = hb;
            this.queueBackupWritten = qb;
            this.note = note == null ? "" : note;
        }

        public String describe() {
            if (!historyMigrated && !queueMigrated) return "无需迁移";
            return "已迁移：历史 " + historyRows + " 行 / 队列 " + queueTasks + " 条"
                    + "（备份 " + (historyBackupWritten ? HISTORY_BAK : "已存在")
                    + " · " + (queueBackupWritten ? QUEUE_BAK : "已存在") + "）";
        }
    }

    // ------------------------------------------------------------ 判定（纯函数）

    /**
     * 历史 CSV 里是否还有明文命中口令（纯函数，可离线断言）。
     *
     * <p>判据：某行有 **恰好 9 列**（旧格式）且第 8 列（命中标志）为 {@code 1}、
     * 第 9 列非空。逗号在写入时被转义成 {@code \c}，所以 {@code split(",")} 是安全的。
     */
    public static boolean needsHistoryMigration(String csv) {
        if (csv == null || csv.isEmpty()) return false;
        for (String line : csv.split("\n")) {
            if (line.isEmpty()) continue;
            String[] p = line.split(",", -1);
            if (p.length == 9 && "1".equals(p[7]) && !p[8].isEmpty()) return true;
        }
        return false;
    }

    /** 队列 JSON 里是否还有明文命中口令（转发给 {@link TaskQueue#hasLegacyPlaintextHit}）。 */
    public static boolean needsQueueMigration(String json) {
        return TaskQueue.hasLegacyPlaintextHit(json);
    }

    // ------------------------------------------------------------ 执行

    /** 是否需要迁移（读盘判定）。读不到文件视为不需要。 */
    public static boolean needed(Context ctx) {
        try {
            String h = readText(new File(ctx.getFilesDir(), "history.csv"));
            if (needsHistoryMigration(h)) return true;
            String q = readText(new File(ctx.getFilesDir(), "queue.json"));
            return needsQueueMigration(q);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 执行迁移。失败时返回的 Report 里两个标志都为 false（调用方据此判断“没动”）；
     * 任何一步失败都**不会**留下半截文件（历史是整表重写，队列是 tmp+rename）。
     */
    public static Report run(Context ctx) {
        boolean hm = false, qm = false, hb = false, qb = false;
        int hr = 0, qt = 0;
        StringBuilder note = new StringBuilder();

        // ---- 历史 ----
        try {
            File hf = new File(ctx.getFilesDir(), "history.csv");
            String raw = readText(hf);
            if (needsHistoryMigration(raw)) {
                hb = backup(hf, new File(ctx.getFilesDir(), HISTORY_BAK));
                java.util.List<HistStore.Record> rows = HistStore.load(ctx);
                hm = HistStore.rewrite(ctx, rows);
                hr = rows.size();
                if (!hm) note.append("history-rewrite-failed; ");
            }
        } catch (Throwable t) {
            note.append("history:").append(t.getClass().getSimpleName()).append("; ");
        }

        // ---- 队列 ----
        try {
            File qf = new File(ctx.getFilesDir(), "queue.json");
            String raw = readText(qf);
            if (needsQueueMigration(raw)) {
                qb = backup(qf, new File(ctx.getFilesDir(), QUEUE_BAK));
                java.util.List<TaskQueue.Task> tasks = TaskQueue.load(ctx);
                qt = tasks.size();
                qm = TaskQueue.save(ctx, tasks);
                if (!qm) note.append("queue-rewrite-failed; ");
            }
        } catch (Throwable t) {
            note.append("queue:").append(t.getClass().getSimpleName()).append("; ");
        }

        return new Report(hm, qm, hr, qt, hb, qb, note.toString());
    }

    // ------------------------------------------------------------ 工具

    /** 复制备份；目标已存在则**不覆盖**并返回 false。 */
    static boolean backup(File src, File dst) {
        try {
            if (!src.isFile() || dst.exists()) return false;
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readText(File f) {
        try {
            if (f == null || !f.isFile()) return "";
            byte[] buf = new byte[(int) Math.min(f.length(), 8 * 1024 * 1024)];
            try (FileInputStream in = new FileInputStream(f)) {
                int n = in.read(buf);
                return n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return "";
        }
    }
}
