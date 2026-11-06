package io.github.wlmosv_png.wpaforge.audit;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 字典任务快照 —— 「界面上显示的那本字典」与「真正喂给引擎的候选序列」的同一个身份。
 *
 * <h3>为什么必须有它</h3>
 * 之前审计页只有零散的三个字段（{@code currentDictName} / {@code currentDictPath} /
 * {@code currentDict} 这个 List），谁都可能单独被改掉：
 *
 * <ul>
 *   <li>{@code deriveForSsid()} 派生完只改了内存里的 List 与名字，**没有落盘**，
 *       于是 {@code currentDictPath} 是空的 —— 界面显示「XX 派生 · 200 条」，
 *       而任何按路径读字典的下游（服务进程、续跑、跨字典过滤）拿到的是上一本；</li>
 *   <li>结果卡只记了字典**名字**与条数，没有指纹，事后无法证明「这次跑的到底是哪一份内容」；</li>
 *   <li>名字里的条数、内存里的条数、文件里的条数三者可以不一致，而界面上看不出差别。</li>
 * </ul>
 *
 * <h3>两个哈希各管一件事</h3>
 * <ul>
 *   <li>{@link #contentSha256} —— 对**候选序列本身**（每条 password + {@code \n}，
 *       UTF-8）取的 SHA-256。它是「本次实际执行了什么」的权威身份，
 *       与文件在不在、路径对不对无关；</li>
 *   <li>{@link #fileSha256} —— 落盘文件**字节**的 SHA-256。它回答「这份候选是从哪个
 *       文件来的、文件后来有没有被人换掉」。没有落盘时为空串。</li>
 * </ul>
 *
 * <p>{@link #count} 一律由传入列表的 {@code size()} 得到，**不接受调用方自报的条数** ——
 * 界面显示什么，就等于执行什么。
 *
 * <p>纯逻辑，无 Android 依赖（只用 java.io / java.security），可离线测试。
 */
public final class DictSnapshot {

    /** 未选择字典。 */
    public static final String SRC_NONE = "";
    /** 按目标/线索派生。 */
    public static final String SRC_PROFILE = "profile";
    /** 内置生成器。 */
    public static final String SRC_GENERIC = "generic";
    /** 从字典文件加载。 */
    public static final String SRC_FILE = "file";

    private static final char SEP = '|';
    private static final String FORMAT = "ds1";
    private static final String EMPTY_HASH = "";

    public final String name;
    /** 来源代码：{@link #SRC_PROFILE} / {@link #SRC_GENERIC} / {@link #SRC_FILE} / 空。 */
    public final String source;
    /** 落盘文件绝对路径；未落盘为空串。 */
    public final String filePath;
    /** 文件字节 SHA-256（小写十六进制）；无文件为空串。 */
    public final String fileSha256;
    /** 候选序列 SHA-256（小写十六进制）；无候选为空串。 */
    public final String contentSha256;
    /** 候选条数，== 实际传入引擎的那个列表的长度。 */
    public final long count;
    /** 建立快照的时间（epoch ms）。 */
    public final long createdAt;

    public DictSnapshot(String name, String source, String filePath, String fileSha256,
                        String contentSha256, long count, long createdAt) {
        this.name = nz(name);
        this.source = nz(source);
        this.filePath = nz(filePath);
        this.fileSha256 = nz(fileSha256);
        this.contentSha256 = nz(contentSha256);
        this.count = count;
        this.createdAt = createdAt;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 没有字典。 */
    public static DictSnapshot none() {
        return new DictSnapshot("", SRC_NONE, "", "", "", 0, 0);
    }

    /** 按当前时间建快照。 */
    public static DictSnapshot of(String name, String source, String filePath,
                                  List<Candidate> dict) {
        return of(name, source, filePath, dict, System.currentTimeMillis());
    }

    /**
     * 建快照。
     *
     * <p>{@code count} 与 {@code contentSha256} **只由 {@code dict} 决定**；
     * {@code filePath} 只在文件确实可读时才写入 {@code fileSha256}，
     * 读不到就是空串（不猜、不冒充）。
     */
    public static DictSnapshot of(String name, String source, String filePath,
                                  List<Candidate> dict, long now) {
        int n = dict == null ? 0 : dict.size();
        if (n == 0) {
            return new DictSnapshot(name, source, filePath, "", "", 0, now);
        }
        String content = sha256OfCandidates(dict);
        String fileHash = sha256OfFile(filePath);
        return new DictSnapshot(name, source, filePath, fileHash, content, n, now);
    }

    /** 空字典。 */
    public boolean empty() {
        return count <= 0;
    }

    /** 有字典。 */
    public boolean present() {
        return count > 0;
    }

    /** 是否已落盘（界面上能不能说「已写入文件」）。 */
    public boolean onDisk() {
        return !filePath.isEmpty() && !fileSha256.isEmpty();
    }

    /**
     * 自洽性：状态与数字不能打架。
     *
     * <p>写盘前挡脏数据；单测里当不变式断言。
     */
    public boolean consistent() {
        if (count < 0 || createdAt < 0) return false;
        if (count == 0) {
            // 没有候选就不该有内容指纹；名字/来源可以空（未选择）
            return contentSha256.isEmpty();
        }
        if (contentSha256.length() != 64) return false;
        if (!fileSha256.isEmpty() && fileSha256.length() != 64) return false;
        // 有文件指纹就必须有路径，否则指纹无主
        if (fileSha256.length() == 64 && filePath.isEmpty()) return false;
        return true;
    }

    /**
     * 这份快照描述的候选序列，是否就是手上这一份。
     *
     * <p>启动离线校验前的最后一道闸：不相等就说明界面与执行对象已经脱节，
     * 必须挡下而不是照跑（宁可报错，也不跑一份不知道是什么的字典）。
     */
    public boolean matches(List<Candidate> dict) {
        int n = dict == null ? 0 : dict.size();
        if (n != count) return false;
        if (n == 0) return contentSha256.isEmpty();
        return contentSha256.equals(sha256OfCandidates(dict));
    }

    /** 内容指纹的显示形式（前 12 位，大写）。 */
    public String fingerprint() {
        return shortOf(contentSha256, 12);
    }

    /** 文件指纹的显示形式（前 12 位，大写）；未落盘返回空串。 */
    public String fileId() {
        return shortOf(fileSha256, 12);
    }

    private static String shortOf(String h, int n) {
        if (h == null || h.isEmpty()) return "";
        return h.substring(0, Math.min(n, h.length())).toUpperCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------------ 指纹计算

    /** 候选序列的 SHA-256：每条 password + {@code \n}，UTF-8。 */
    public static String sha256OfCandidates(List<Candidate> dict) {
        if (dict == null || dict.isEmpty()) return EMPTY_HASH;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Candidate c : dict) {
                if (c == null || c.password == null) continue;
                md.update(c.password.getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            return hex(md.digest());
        } catch (Throwable t) {
            return EMPTY_HASH;
        }
    }

    /** 文件字节的 SHA-256；路径空、文件不存在或读失败一律返回空串。 */
    public static String sha256OfFile(String path) {
        if (path == null || path.isEmpty()) return EMPTY_HASH;
        InputStream in = null;
        try {
            File f = new File(path);
            if (!f.isFile() || f.length() <= 0) return EMPTY_HASH;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(f);
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            return hex(md.digest());
        } catch (Throwable t) {
            return EMPTY_HASH;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 任意字节的 SHA-256（小写十六进制）；空输入返回空串。 */
    public static String sha256OfBytes(byte[] b) {
        if (b == null || b.length == 0) return EMPTY_HASH;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(b);
            return hex(md.digest());
        } catch (Throwable t) {
            return EMPTY_HASH;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
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
        StringBuilder sb = new StringBuilder(192);
        sb.append(FORMAT).append(SEP);
        sb.append(esc(name)).append(SEP);
        sb.append(esc(source)).append(SEP);
        sb.append(esc(filePath)).append(SEP);
        sb.append(esc(fileSha256)).append(SEP);
        sb.append(esc(contentSha256)).append(SEP);
        sb.append(count).append(SEP);
        sb.append(createdAt);
        return sb.toString();
    }

    /** 解码失败一律退回 {@link #none()}，不抛。 */
    public static DictSnapshot decode(String s) {
        if (s == null || s.isEmpty()) return none();
        String[] f = s.split("(?<!\\\\)\\" + SEP, -1);
        if (f.length < 8 || !FORMAT.equals(f[0])) return none();
        DictSnapshot d = new DictSnapshot(unesc(f[1]), unesc(f[2]), unesc(f[3]),
                unesc(f[4]), unesc(f[5]), parseLong(f[6]), parseLong(f[7]));
        return d.consistent() ? d : none();
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    @Override
    public String toString() {
        return "DictSnapshot{" + name + " src=" + source + " n=" + count
                + " id=" + fingerprint() + " file=" + fileId() + "}";
    }
}
