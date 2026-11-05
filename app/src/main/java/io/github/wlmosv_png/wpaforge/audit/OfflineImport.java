package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 离线校验材料的导入判定 —— 纯逻辑，无 Android 依赖，可离线测试。
 *
 * <h3>为什么要单独一个类</h3>
 * 修复前「选择材料文件」把**所有**失败塌成同一句话：
 * 「没找到 .hc22000 / .22000 文件」。用户看到的只有这一种结果 ——
 * 没权限是它、目录列不出来是它、文件是空的也是它、格式不对还是它，
 * 等于什么都没说，下一步也无从下手。
 *
 * <p>这里把「读取阶段」和「解析阶段」的失败收敛成有限状态，
 * 每个状态对应一句可执行的下一步（文案由 UI 层用 {@code L.s} 出双语）。
 *
 * <h3>两类判定</h3>
 * <ul>
 *   <li>{@link #classifyReadFailure} —— 读不到（没有文件、没有授权、IO 出错）；</li>
 *   <li>{@link #analyze} —— 读到了但内容不对（空、不是 22000、记录类型不支持）。</li>
 * </ul>
 *
 * <p>本类不碰 Android API，只被 UI 与测试调用。
 */
public final class OfflineImport {

    private OfflineImport() {}

    /** 材料体积上限。离线校验针对单次抓包材料，再大就是误选了。 */
    public static final int MAX_BYTES = 4 * 1024 * 1024;

    /** hashcat 22000 的两种记录类型标记。 */
    private static final String TAG_PMKID = "01";
    private static final String TAG_EAPOL = "02";

    public enum Status {
        /** 解析成功，至少有一条可用材料 */
        OK,
        /** 文件是空的，或者只有空行 / 注释行 */
        EMPTY,
        /** 有内容，但没有一行是合法的 22000 记录 */
        FORMAT_INVALID,
        /** 是 22000 行，但记录类型不是 PMKID(01) / EAPOL(02) */
        UNSUPPORTED,
        /** 没有拿到文件（用户取消，或选择器没返回数据） */
        NO_FILE,
        /** 系统没有给读取授权 */
        NO_ACCESS,
        /** 有授权但读不出来（IO 错误） */
        READ_ERROR,
        /** 超过体积上限 */
        TOO_LARGE
    }

    /** 解析结果：状态 + 统计 + 材料列表。 */
    public static final class Result {
        public final Status status;
        public final List<Hc22000.Entry> entries;
        /** 参与判定的行数（不含空行与注释行） */
        public final int lines;
        /** 注释行（# 开头）数量 */
        public final int comments;
        /** 看起来是记录、但没通过校验的坏行数量 */
        public final int bad;
        /** 是 22000 行、但记录类型不支持的条数 */
        public final int unsupported;
        public final int pmkid;
        public final int eapol;

        Result(Status status, List<Hc22000.Entry> entries, int lines, int comments,
               int bad, int unsupported, int pmkid, int eapol) {
            this.status = status;
            this.entries = entries;
            this.lines = lines;
            this.comments = comments;
            this.bad = bad;
            this.unsupported = unsupported;
            this.pmkid = pmkid;
            this.eapol = eapol;
        }

        public boolean ok() {
            return status == Status.OK;
        }

        /** 解析成功时的条数；失败时为 0。 */
        public int count() {
            return entries == null ? 0 : entries.size();
        }
    }

    /** 失败结果的快捷构造。 */
    public static Result fail(Status status) {
        return new Result(status, new ArrayList<Hc22000.Entry>(), 0, 0, 0, 0, 0, 0);
    }

    // ------------------------------------------------------------------ 解析阶段

    /**
     * 解析整份文本。
     *
     * <p>判定优先级：**有可用材料 → OK**；否则按「空 → 类型不支持 → 格式不合法」逐级降。
     * 注释行与空行不计入坏行 —— 这两种在真实导出文件里很常见，不该把整份材料拖成失败。
     */
    public static Result analyze(String text) {
        List<Hc22000.Entry> entries = new ArrayList<Hc22000.Entry>();
        int lines = 0, comments = 0, bad = 0, unsupported = 0;

        if (text != null) {
            for (String raw : text.split("\r?\n")) {
                String s = raw.trim();
                if (s.isEmpty()) continue;
                if (s.startsWith("#")) {
                    comments++;
                    continue;
                }
                lines++;
                if (!s.startsWith("WPA*")) {
                    bad++;
                    continue;
                }
                String[] f = s.split("\\*", -1);
                if (f.length >= 2 && !TAG_PMKID.equals(f[1]) && !TAG_EAPOL.equals(f[1])) {
                    unsupported++;
                    continue;
                }
                Hc22000.Entry e = Hc22000.parseLine(s);
                if (e == null) {
                    bad++;
                    continue;
                }
                if (!e.validate().isEmpty()) {
                    bad++;
                    continue;
                }
                entries.add(e);
            }
        }

        int pmkid = 0;
        for (Hc22000.Entry e : entries) {
            if (e.isPmkid()) pmkid++;
        }
        int eapol = entries.size() - pmkid;

        if (!entries.isEmpty()) {
            return new Result(Status.OK, entries, lines, comments, bad, unsupported, pmkid, eapol);
        }
        if (lines == 0 && comments == 0) return fail(Status.EMPTY);
        if (unsupported > 0) return fail(Status.UNSUPPORTED);
        if (bad > 0) return fail(Status.FORMAT_INVALID);
        // 只有注释行 / 只有空行
        return fail(Status.EMPTY);
    }

    /** 从输入流读并解析。上限 {@link #MAX_BYTES}，超出即判 {@link Status#TOO_LARGE}。 */
    public static Result analyzeStream(java.io.InputStream in) throws java.io.IOException {
        if (in == null) return fail(Status.NO_FILE);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BYTES) return fail(Status.TOO_LARGE);
            bos.write(buf, 0, n);
        }
        return analyze(new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 读取阶段

    /**
     * 把「读取」阶段的异常映射成状态。
     *
     * <p>只吃异常**类名**和**消息**，不依赖 Android 类 —— 这样测试里可以直接
     * 构造字符串覆盖每条分支，不需要真机。
     *
     * <p>为什么 {@code FileNotFoundException} 还要再分：ContentResolver 在
     * 「URI 没有授权」时抛的也是它，但消息里带 {@code EACCES}；不分就会把
     * 「没授权」误报成「文件不存在」，用户会去反复确认文件在不在。
     */
    public static Status classifyReadFailure(String exceptionName, String message) {
        String ex = exceptionName == null ? "" : exceptionName;
        String msg = message == null ? "" : message.toLowerCase(Locale.ROOT);

        boolean perm = msg.contains("eacces")
                || msg.contains("eperm")
                || msg.contains("permission denied")
                || msg.contains("not allowed");

        if (ex.contains("SecurityException")) return Status.NO_ACCESS;
        if (ex.contains("FileNotFoundException")) return perm ? Status.NO_ACCESS : Status.NO_FILE;
        if (perm) return Status.NO_ACCESS;
        return Status.READ_ERROR;
    }

    /** 读取阶段的异常对象入口（UI 侧用）。 */
    public static Status classifyReadFailure(Throwable t) {
        if (t == null) return Status.READ_ERROR;
        return classifyReadFailure(t.getClass().getName(), t.getMessage());
    }

    /** 选择器返回是否拿到了文件。 */
    public static Status classifyPickerResult(boolean gotUri) {
        return gotUri ? Status.OK : Status.NO_FILE;
    }

    // ------------------------------------------------------------------ 杂项

    /**
     * 文件名是否像 22000 材料。
     *
     * <p>**只用于提示，不作为拒绝依据** —— SAF 返回的显示名未必带扩展名，
     * 内容才是唯一判据。
     */
    public static boolean looksLikeHcName(String name) {
        if (name == null) return false;
        String n = name.trim().toLowerCase(Locale.ROOT);
        return n.endsWith(".hc22000") || n.endsWith(".22000");
    }

    /** 该状态是否属于「读不到」这一类（用于 UI 选择提示措辞）。 */
    public static boolean isReadStage(Status s) {
        return s == Status.NO_FILE || s == Status.NO_ACCESS || s == Status.READ_ERROR
                || s == Status.TOO_LARGE;
    }
}
