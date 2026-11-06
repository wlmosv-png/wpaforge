package io.github.wlmosv_png.wpaforge.audit;

import java.util.Locale;

/**
 * 离线校验的**一次结果** —— 明确的数据模型，不是一行日志。
 *
 * <h3>为什么要有它</h3>
 * 以前跑完只在运行记录里追加一行文字：「未命中：跑了 N 条，耗时 X ms」。
 * 于是页面一切换、应用一重开，结论就没了；连续跑几轮之后也分不清
 * 哪一行对应哪份材料、哪本字典。
 *
 * <p>这里把「结论 + 材料 + 字典 + 计数 + 时间」收成一个不可变对象，
 * 自带编解码（存 SharedPreferences 用），字段值做转义，可以被安全地
 * 反复读写。
 *
 * <p>纯逻辑，无 Android 依赖，可离线测试。
 */
public final class OfflineVerdict {

    /** 最终状态。**刻意把「取消」和「未命中」分开** —— 这是两回事。 */
    public enum State {
        /** 还没跑过 */
        NONE,
        /** 命中：材料对应的真实口令在字典里 */
        HIT,
        /** 未命中：字典确实被完整跑过，没找到 */
        MISS,
        /** 用户主动停止（不能算未命中） */
        CANCELLED,
        /** 材料不完整，根本没开跑（不能算未命中） */
        BAD_MATERIAL,
        /** 执行过程中出错 */
        FAILED
    }

    private static final char SEP = '|';
    /** 当前写盘格式。v1 = 13 字段（1.9.43），v2 = 17 字段（1.9.44，加字典指纹）。 */
    private static final String FORMAT = "v2";
    private static final String FORMAT_V1 = "v1";
    /** v2 的字段数。 */
    private static final int FIELDS_V2 = 17;
    /** v1 的字段数，仍要能读 —— 升级不让用户丢掉上一次的结果卡。 */
    private static final int FIELDS_V1 = 13;

    public final State state;
    /** 材料文件名（可能是 URI 末段） */
    public final String fileName;
    /** "PMKID" / "EAPOL" / "" */
    public final String materialKind;
    public final String ssid;
    public final String apMac;
    /** 当前字典的显示名 */
    public final String dictName;
    /** 字典候选总数 */
    public final long dictSize;
    /** 实际完成的候选数 */
    public final long tested;
    /** 执行耗时（毫秒） */
    public final long elapsedMs;
    /** 完成时间（epoch ms）；NONE 时为 0 */
    public final long finishedAt;
    /** 命中口令；仅 HIT 非空。**日志里绝不打印它** */
    public final String hitPassword;
    /** 取消原因 / 失败原因 / 材料缺什么；其余状态为空 */
    public final String note;

    /**
     * 本次实际使用的字典来源代码（{@code profile} / {@code generic} / {@code file}）。
     *
     * <p>1.9.44 起记在结果里：只有名字与条数时，事后无法证明「这次跑的到底是哪一份内容」。
     */
    public final String dictSource;
    /** 本次实际使用的字典**内容指纹**（{@link DictSnapshot#fingerprint()}）。 */
    public final String dictFingerprint;
    /** 本次实际使用的字典文件路径；未落盘为空串。 */
    public final String dictPath;
    /** 本次实际使用的字典**文件指纹**；未落盘为空串。 */
    public final String dictFileId;

    public OfflineVerdict(State state, String fileName, String materialKind, String ssid,
                          String apMac, String dictName, long dictSize, long tested,
                          long elapsedMs, long finishedAt, String hitPassword, String note) {
        this(state, fileName, materialKind, ssid, apMac, dictName, dictSize, tested,
                elapsedMs, finishedAt, hitPassword, note, "", "", "", "");
    }

    public OfflineVerdict(State state, String fileName, String materialKind, String ssid,
                          String apMac, String dictName, long dictSize, long tested,
                          long elapsedMs, long finishedAt, String hitPassword, String note,
                          String dictSource, String dictFingerprint,
                          String dictPath, String dictFileId) {
        this.state = state == null ? State.NONE : state;
        this.fileName = nz(fileName);
        this.materialKind = nz(materialKind);
        this.ssid = nz(ssid);
        this.apMac = nz(apMac);
        this.dictName = nz(dictName);
        this.dictSize = dictSize;
        this.tested = tested;
        this.elapsedMs = elapsedMs;
        this.finishedAt = finishedAt;
        this.hitPassword = nz(hitPassword);
        this.note = nz(note);
        this.dictSource = nz(dictSource);
        this.dictFingerprint = nz(dictFingerprint);
        this.dictPath = nz(dictPath);
        this.dictFileId = nz(dictFileId);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 还没有结果。 */
    public static OfflineVerdict none() {
        return new OfflineVerdict(State.NONE, "", "", "", "", "", 0, 0, 0, 0, "", "");
    }

    public boolean hasResult() {
        return state != State.NONE;
    }

    /** 这次是不是"确实把字典跑完了"。只有它才配叫未命中。 */
    public boolean dictionaryExhausted() {
        return state == State.MISS && dictSize > 0 && tested >= dictSize;
    }

    /**
     * 自洽性检查：状态与数字不能打架。
     *
     * <p>用途有二：写盘前挡掉脏数据；单测里当不变式断言。
     */
    public boolean consistent() {
        if (tested < 0 || dictSize < 0 || elapsedMs < 0) return false;
        switch (state) {
            case NONE:
                return tested == 0 && dictSize == 0 && finishedAt == 0
                        && hitPassword.isEmpty();
            case HIT:
                return !hitPassword.isEmpty() && tested >= 1;
            case MISS:
                return hitPassword.isEmpty() && dictSize > 0 && tested >= dictSize;
            case CANCELLED:
                // 取消时可能一条都没跑完，但绝不能报"跑满"
                return hitPassword.isEmpty() && tested <= dictSize;
            case BAD_MATERIAL:
                return hitPassword.isEmpty() && tested == 0;
            case FAILED:
                return hitPassword.isEmpty();
            default:
                return false;
        }
    }

    /** 材料那一栏的摘要，如 {@code EAPOL · TP-LINK_HASHCAT_TEST · AP 64:66:B3:8E:C3:FC}。 */
    public String materialSummary() {
        StringBuilder sb = new StringBuilder();
        if (!materialKind.isEmpty()) sb.append(materialKind);
        if (!ssid.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(ssid);
        }
        if (!apMac.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("AP ").append(apMac);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 编解码

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') sb.append("\\\\");
            else if (c == SEP) sb.append("\\p");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String unesc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            if (n == '\\') sb.append('\\');
            else if (n == 'p') sb.append(SEP);
            else if (n == 'n') sb.append('\n');
            else if (n == 'r') sb.append('\r');
            else sb.append(n);
        }
        return sb.toString();
    }

    /** 编成一行的持久化串。 */
    public String encode() {
        StringBuilder sb = new StringBuilder(256);
        sb.append(FORMAT).append(SEP);
        sb.append(state.name()).append(SEP);
        sb.append(esc(fileName)).append(SEP);
        sb.append(esc(materialKind)).append(SEP);
        sb.append(esc(ssid)).append(SEP);
        sb.append(esc(apMac)).append(SEP);
        sb.append(esc(dictName)).append(SEP);
        sb.append(dictSize).append(SEP);
        sb.append(tested).append(SEP);
        sb.append(elapsedMs).append(SEP);
        sb.append(finishedAt).append(SEP);
        sb.append(esc(hitPassword)).append(SEP);
        sb.append(esc(note)).append(SEP);
        sb.append(esc(dictSource)).append(SEP);
        sb.append(esc(dictFingerprint)).append(SEP);
        sb.append(esc(dictPath)).append(SEP);
        sb.append(esc(dictFileId));
        return sb.toString();
    }

    /**
     * 按未转义的 {@code |} 切分字段。
     *
     * <h3>为什么不能用 split 正则（真机事故级缺陷）</h3>
     * 原来这里是 {@code s.split("(?<!\\\\)\\|", -1)}：它只在分隔符**前一个字符不是
     * 反斜杠**时才切。而 {@link #esc} 把字段里的反斜杠编成 {@code \\}，于是
     * 「字段以反斜杠结尾」时编码结果是 {@code ...\\|} —— 分隔符前面正好是反斜杠
     * → **不切**，两个字段并成一个，字段数从 17 掉到 16，{@link #decode} 判定格式
     * 不合法后返回 {@link #none()}。
     *
     * <p>后果是结果卡**静默消失**（界面显示「还没有跑过」），最坏情况是刚命中的
     * 明文口令被丢掉。触发条件只需要某个字段以反斜杠结尾（SSID、口令、路径都可能）。
     *
     * <p>这里改成逐字符扫描：遇到反斜杠就把它和下一个字符当成一个整体（转义对）
     * 塞进当前字段，只有**不在转义对里**的 {@code |} 才是分隔符。
     */
    private static String[] splitFields(String s) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                cur.append(c).append(s.charAt(++i));
                continue;
            }
            if (c == SEP) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    /**
     * 解码失败一律退回 {@link #none()}，不抛。
     *
     * <p>同时接受 v1（1.9.43 写入的 13 字段）与 v2（17 字段）—— 版本升级不该
     * 让用户把上一次的结果卡丢掉。
     */
    public static OfflineVerdict decode(String s) {
        if (s == null || s.isEmpty()) return none();
        String[] f = splitFields(s);
        boolean v1 = FORMAT_V1.equals(f[0]) && f.length >= FIELDS_V1;
        boolean v2 = FORMAT.equals(f[0]) && f.length >= FIELDS_V2;
        if (!v1 && !v2) return none();
        State st;
        try {
            st = State.valueOf(f[1]);
        } catch (Throwable t) {
            return none();
        }
        long dictSize = parseLong(f[7]);
        long tested = parseLong(f[8]);
        long elapsed = parseLong(f[9]);
        long finished = parseLong(f[10]);
        OfflineVerdict v = new OfflineVerdict(st, unesc(f[2]), unesc(f[3]), unesc(f[4]),
                unesc(f[5]), unesc(f[6]), dictSize, tested, elapsed, finished,
                unesc(f[11]), unesc(f[12]),
                v2 ? unesc(f[13]) : "", v2 ? unesc(f[14]) : "",
                v2 ? unesc(f[15]) : "", v2 ? unesc(f[16]) : "");
        return v.consistent() ? v : none();
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 给日志用的一行（**不含密码明文**）。 */
    public String logLine() {
        switch (state) {
            case HIT:
                return String.format(Locale.ROOT, "命中（字典第 %d 位）", tested);
            case MISS:
                return String.format(Locale.ROOT, "未命中：跑完 %d 条", tested);
            case CANCELLED:
                return String.format(Locale.ROOT, "已取消：跑到 %d / %d", tested, dictSize);
            case BAD_MATERIAL:
                return "材料异常，未执行：" + note;
            case FAILED:
                return "执行失败：" + note;
            default:
                return "";
        }
    }
}
