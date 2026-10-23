package io.github.wlmosv_png.wpaforge.ui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 口令脱敏（纯逻辑，桌面 JVM 可测）。
 *
 * <h3>为什么要有它</h3>
 * 判定链要能事后复盘，但**日志里不许出现明文口令**（真实口令属于敏感数据）。
 * 折中办法：给出一个**可对照、不可还原**的标签 ——
 *
 * <pre>
 *   #ef797c(len=8)      // "12345678"
 *   #29ff2d(len=9)      // "sample-pw"
 * </pre>
 *
 * 取 SHA-256 的前 6 位十六进制 + 明文长度：
 * <ul>
 *   <li><b>可对照</b>：同一条口令每次都是同一个标签，排查时能和候选序列一一对上
 *       （配合调用方记录的顺序号，例如 {@code #11 #29ff2d(9)}）</li>
 *   <li><b>不可还原</b>：6 位十六进制 = 24 bit，且不含任何明文字符</li>
 *   <li><b>长度保留</b>：长度本身能说明很多问题（<8 的非法长度、异常长短）</li>
 * </ul>
 *
 * <p><b>适用范围</b>：逐条结果、最近判定、异常日志、调试输出、导出内容、通知栏。
 * **不适用于**"结果展示"本身（命中卡片 / 正在试 / 破解记录）——那是产品的答案，
 * 明文只出现在用户自己的屏幕上，不落日志、不导出。
 */
public final class Redact {

    private Redact() {}

    /**
     * 口令的脱敏标签：{@code #<sha256前6>(len=N)}。
     *
     * <p>null 与空串都按空串处理（{@code #e3b0c4(len=0)}），保证确定性。
     */
    public static String pw(String plain) {
        String s = plain == null ? "" : plain;
        return "#" + hash6(s) + "(len=" + s.length() + ")";
    }

    /** 只给哈希（需要自己拼长度说明时用） */
    public static String hash6(String s) {
        String src = s == null ? "" : s;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(src.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 3; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            // 拿不到摘要时**绝不能退回明文**：给一个不可用的标记
            return "------";
        }
    }

    /**
     * 带顺序号的脱敏标签：{@code #11 #29ff2d(9)}。
     *
     * <p>顺序号（该候选在字典里的位置）是非敏感信息，却是排查时最有用的线索 ——
     * 有了它，"第 11 条是哪个口令"可以在本地用候选列表对上，日志本身不含明文。
     *
     * @param index 1 起的顺序号；<=0 表示未知，输出 {@code #?}
     */
    public static String pwAt(int index, String plain) {
        return (index > 0 ? "#" + index : "#?") + " " + pw(plain);
    }
}
