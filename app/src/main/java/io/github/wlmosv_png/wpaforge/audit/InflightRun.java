package io.github.wlmosv_png.wpaforge.audit;

/**
 * 「有一次比对正在跑」的**落盘痕迹**。
 *
 * <h3>为什么需要它</h3>
 * 运行状态只在内存里，进程被杀（或 Activity 被回收）后一点痕迹都不剩。
 * 于是重开应用时，用户看到的是**上一次**的结果卡，而它跟"上次那个被中断的运行"
 * 毫无关系 —— 只要材料和字典碰巧一样，结果卡上的一致性徽标还会写
 * 「这条结果就是当前材料 + 当前字典，可以直接采信」，把两种情况混为一谈。
 *
 * <p>这里在开跑时落一条最小痕迹（材料名 / 字典名与指纹 / 开始时间），
 * 正常结束就清掉；重开时若还在，界面就能明确说"上次那次没跑完"。
 *
 * <p>纯数据模型 + 编解码，无 Android 依赖，可离线测试。
 */
public final class InflightRun {

    private static final char SEP = '\u0001';

    /** 材料显示名（可能为空）。 */
    public final String materialName;
    /** 字典显示名。 */
    public final String dictName;
    /** 字典内容指纹（用来判断"当前这本"是不是当时那本）。 */
    public final String dictFingerprint;
    /** 开始时间（epoch ms）。 */
    public final long startedAt;

    public InflightRun(String materialName, String dictName, String dictFingerprint,
                       long startedAt) {
        this.materialName = nz(materialName);
        this.dictName = nz(dictName);
        this.dictFingerprint = nz(dictFingerprint);
        this.startedAt = startedAt;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public boolean valid() {
        return startedAt > 0L;
    }

    /** 同一份材料 + 同一本字典？ */
    public boolean sameSubject(String material, String fingerprint) {
        return nz(material).equals(materialName)
                && nz(fingerprint).equals(dictFingerprint);
    }

    public String encode() {
        StringBuilder sb = new StringBuilder(96);
        sb.append(startedAt).append(SEP);
        sb.append(esc(materialName)).append(SEP);
        sb.append(esc(dictName)).append(SEP);
        sb.append(esc(dictFingerprint));
        return sb.toString();
    }

    /** 解不开返回 null（不抛）。 */
    public static InflightRun decode(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] f = s.split(String.valueOf(SEP), -1);
        if (f.length < 4) return null;
        long at;
        try {
            at = Long.parseLong(f[0]);
        } catch (Throwable t) {
            return null;
        }
        if (at <= 0L) return null;
        return new InflightRun(unesc(f[1]), unesc(f[2]), unesc(f[3]), at);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
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
            if (n == 'n') sb.append('\n');
            else if (n == 'r') sb.append('\r');
            else if (n == '\\') sb.append('\\');
            else sb.append(n);
        }
        return sb.toString();
    }
}
