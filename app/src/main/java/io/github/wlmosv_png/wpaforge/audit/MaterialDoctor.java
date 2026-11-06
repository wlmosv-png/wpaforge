package io.github.wlmosv_png.wpaforge.audit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 材料完整度诊断 —— 判断一份 22000 材料**能不能真的跑**。
 *
 * <h3>为什么不能只看字段非空</h3>
 * 22000 是导出的文本格式，字段"存在"不等于"有意义"。真实会遇到的坏情况：
 *
 * <ul>
 *   <li>EAPOL 帧里的 MIC 字段**必须全零**（格式约定：MIC 由导出工具抹掉，
 *       校验时再算）。非零说明导出时没抹 —— 拿它去算 MIC 必然对不上，
 *       而用户会以为"密码错了"；</li>
 *   <li>EAPOL 帧的 key nonce（offset 17..48）全零 = 废帧，PTK 派生无意义；</li>
 *   <li>key version（key info 低 3 位）不是 1/2/3 时，不知道该用
 *       HMAC-MD5 / HMAC-SHA1 / AES-CMAC 哪一种，跑出来的结论没有意义；</li>
 *   <li>ESSID 超过 32 字节不可能是真实 SSID；</li>
 *   <li>PMKID 值为全零 = 根本没抓到。</li>
 * </ul>
 *
 * <p>把这些判成 blocker 之后，材料异常就**不会被包装成"密码未命中"**。
 *
 * <h3>2026-11-06（1.9.44）：给出四种明确状态</h3>
 * 用户要求「使用明确状态：可执行 / 信息不完整 / 格式错误 / 不支持」。
 * 光有一句「材料不完整」不够 —— 用户要知道**是自己抓少了还是文件本身是坏的**：
 * <ul>
 *   <li>{@link Status#UNSUPPORTED}：记录类型不在支持范围（WPA*01 / WPA*02 之外）；</li>
 *   <li>{@link Status#MALFORMED}：字段长度/取值根本不合格式（长度错、nonce 全零、
 *       MIC 字段没被抹零、Key Version 读不出）—— 文件本身是坏的，重新导出才行；</li>
 *   <li>{@link Status#INCOMPLETE}：格式没问题，但**关键信息缺失**
 *       （没有 SSID、SSID 超长、校验值全零）—— 抓包时没抓到有效值；</li>
 *   <li>{@link Status#RUNNABLE}：可以执行。</li>
 * </ul>
 * 分类只看 blocker 的性质，与文案无关；界面直接显示 {@link Status#label()}。
 *
 * <p>纯逻辑，无 Android 依赖。
 */
public final class MaterialDoctor {

    /** EAPOL-Key 帧固定部分的最小长度：MIC 偏移 81 + MIC 16 + key data length 2。 */
    private static final int EAPOL_MIN = Hc22000.EAPOL_MIC_OFFSET + 16 + 2;
    /** 802.11 的 SSID 长度上限。 */
    private static final int SSID_MAX = 32;

    /** 材料的可用性状态。 */
    public enum Status {
        /** 具备执行条件。 */
        RUNNABLE,
        /** 格式有问题（长度/取值不合格式，文件本身坏了）。 */
        MALFORMED,
        /** 信息不完整（格式对，但关键字段缺失/全零）。 */
        INCOMPLETE,
        /** 记录类型不支持。 */
        UNSUPPORTED;

        /** 界面用的一句话。 */
        public String label() {
            switch (this) {
                case RUNNABLE:    return io.github.wlmosv_png.wpaforge.L.s("可执行", "Runnable");
                case MALFORMED:   return io.github.wlmosv_png.wpaforge.L.s("格式错误", "Malformed");
                case INCOMPLETE:  return io.github.wlmosv_png.wpaforge.L.s("信息不完整", "Incomplete");
                default:          return io.github.wlmosv_png.wpaforge.L.s("不支持", "Unsupported");
            }
        }
    }

    /** 阻断项的性质（决定 {@link Status}）。 */
    private enum Tag { MALFORMED, INCOMPLETE, UNSUPPORTED }

    private MaterialDoctor() {}

    /** 诊断结论。 */
    public static final class Report {
        /** true = 具备执行条件 */
        public final boolean executable;
        /** 阻断项：不修就不能跑 */
        public final List<String> blockers;
        /** 阻断项的性质，与 {@link #blockers} 下标一一对应 */
        public final List<Tag> blockerTags;
        /** 警告：能跑，但结果可能不可信 */
        public final List<String> warnings;
        /** 展示用事实：{字段名, 值} */
        public final List<String[]> facts;

        Report(boolean executable, List<String> blockers, List<Tag> blockerTags,
               List<String> warnings, List<String[]> facts) {
            this.executable = executable;
            this.blockers = blockers;
            this.blockerTags = blockerTags;
            this.warnings = warnings;
            this.facts = facts;
        }

        /**
         * 四分状态。
         *
         * <p>优先级：不支持 &gt; 格式错误 &gt; 信息不完整 —— 类型都不在支持范围时，
         * 再谈字段完整性没有意义。
         */
        public Status status() {
            if (executable) return Status.RUNNABLE;
            boolean malformed = false;
            boolean incomplete = false;
            for (Tag t : blockerTags) {
                if (t == Tag.UNSUPPORTED) return Status.UNSUPPORTED;
                if (t == Tag.MALFORMED) malformed = true;
                if (t == Tag.INCOMPLETE) incomplete = true;
            }
            if (malformed) return Status.MALFORMED;
            if (incomplete) return Status.INCOMPLETE;
            return Status.MALFORMED;   // 有阻断项但没打标签：按格式问题处理，不假装可执行
        }

        /** 状态 + 一句话。 */
        public String statusLabel() {
            Status s = status();
            if (s == Status.RUNNABLE) {
                return warnings.isEmpty()
                        ? io.github.wlmosv_png.wpaforge.L.s("可执行", "Runnable")
                        : io.github.wlmosv_png.wpaforge.L.s("可执行（有警告）",
                                "Runnable (with warnings)");
            }
            return s.label();
        }

        /** 缺什么、为什么不能跑（给用户看的完整句子）。 */
        public String whyNot() {
            if (executable) return "";
            StringBuilder sb = new StringBuilder();
            for (String b : blockers) {
                if (sb.length() > 0) sb.append("；");
                sb.append(b);
            }
            return sb.toString();
        }

        /** 一句话结论。 */
        public String summary() {
            if (executable) {
                return warnings.isEmpty()
                        ? io.github.wlmosv_png.wpaforge.L.s("材料完整，可执行",
                                "Material complete, runnable")
                        : io.github.wlmosv_png.wpaforge.L.s("材料可执行（有警告）",
                                "Material runnable (with warnings)");
            }
            return io.github.wlmosv_png.wpaforge.L.s(
                    "材料不完整，未执行：", "Material incomplete, not executed: ")
                    + (blockers.isEmpty()
                        ? io.github.wlmosv_png.wpaforge.L.s("未知原因", "unknown reason")
                        : blockers.get(0));
        }

        /** 所有问题拼成一行（阻断 + 警告）。 */
        public String allIssues() {
            StringBuilder sb = new StringBuilder();
            for (String b : blockers) {
                if (sb.length() > 0) sb.append("；");
                sb.append(b);
            }
            for (String w : warnings) {
                if (sb.length() > 0) sb.append("；");
                sb.append(io.github.wlmosv_png.wpaforge.L.s("(警告) ", "(warning) ")).append(w);
            }
            return sb.toString();
        }
    }

    /** 诊断一份材料。 */
    public static Report examine(Hc22000.Entry e) {
        List<String> blockers = new ArrayList<String>();
        List<Tag> tags = new ArrayList<Tag>();
        List<String> warnings = new ArrayList<String>();
        List<String[]> facts = new ArrayList<String[]>();

        if (e == null) {
            blockers.add(io.github.wlmosv_png.wpaforge.L.s("材料为空", "material is null"));
            tags.add(Tag.INCOMPLETE);
            return new Report(false, blockers, tags, warnings, facts);
        }

        // ---- 公共字段 ----
        String kind = e.isPmkid() ? "PMKID" : "EAPOL";
        facts.add(new String[]{"类型", kind});
        facts.add(new String[]{"SSID", e.ssid == null || e.ssid.isEmpty() ? "(空)" : e.ssid});
        facts.add(new String[]{"AP MAC", Hc22000.hexMac(e.apMac)});
        facts.add(new String[]{"STA MAC", Hc22000.hexMac(e.staMac)});

        if (e.type != Hc22000.TYPE_PMKID && e.type != Hc22000.TYPE_EAPOL) {
            blockers.add("记录类型不支持（" + e.type + "）");
            tags.add(Tag.UNSUPPORTED);
        }
        if (e.micOrPmkid == null || e.micOrPmkid.length != 16) {
            blockers.add("校验值不是 16 字节");
            tags.add(Tag.MALFORMED);
        } else if (allZero(e.micOrPmkid)) {
            blockers.add("校验值全零（没抓到有效值）");
            tags.add(Tag.INCOMPLETE);
        }
        if (e.apMac == null || e.apMac.length != 6) {
            blockers.add("AP MAC 不是 6 字节");
            tags.add(Tag.MALFORMED);
        }
        if (e.staMac == null || e.staMac.length != 6) {
            blockers.add("STA MAC 不是 6 字节");
            tags.add(Tag.MALFORMED);
        }
        if (e.apMac != null && e.staMac != null && e.apMac.length == 6 && e.staMac.length == 6
                && sameBytes(e.apMac, e.staMac)) {
            warnings.add("AP MAC 与 STA MAC 相同，不像真实抓包");
        }

        byte[] essid = e.ssid == null ? new byte[0] : e.ssid.getBytes(StandardCharsets.UTF_8);
        if (essid.length == 0) {
            blockers.add("缺少 SSID");
            tags.add(Tag.INCOMPLETE);
        } else if (essid.length > SSID_MAX) {
            blockers.add("SSID 超过 " + SSID_MAX + " 字节（802.11 上限）");
            tags.add(Tag.INCOMPLETE);
        } else {
            for (byte b : essid) {
                if (b == 0) {
                    warnings.add("SSID 含 NUL 字节");
                    break;
                }
            }
        }

        // ---- PMKID 专属 ----
        if (e.type == Hc22000.TYPE_PMKID) {
            facts.add(new String[]{"PMKID", hex(e.micOrPmkid)});
            facts.add(new String[]{"计算量", "PBKDF2 + 1 次 HMAC（比 EAPOL 快约一倍）"});
        }

        // ---- EAPOL 专属 ----
        if (e.type == Hc22000.TYPE_EAPOL) {
            byte[] frame = e.eapol;
            facts.add(new String[]{"MIC", hex(e.micOrPmkid)});
            facts.add(new String[]{"ANonce", hex(e.aNonce)});
            if (frame == null) {
                blockers.add("缺少 EAPOL 帧");
                tags.add(Tag.INCOMPLETE);
            } else {
                facts.add(new String[]{"EAPOL 帧长度", frame.length + " 字节"});
                int keyVer = keyVersion(frame);
                facts.add(new String[]{"Key Version", keyVer <= 0 ? "(读不到)" : String.valueOf(keyVer)});
                facts.add(new String[]{"Message Pair",
                        e.messagePair == null || e.messagePair.isEmpty() ? "(空)" : e.messagePair});

                if (frame.length < EAPOL_MIN) {
                    blockers.add("EAPOL 帧过短（" + frame.length + " < " + EAPOL_MIN + " 字节）");
                    tags.add(Tag.MALFORMED);
                } else {
                    // 帧内 nonce：material 实际提供且能可靠提取时才展示
                    byte[] frameNonce = Hc22000.sNonceOf(frame);
                    facts.add(new String[]{"SNonce(帧内)", hex(frameNonce)});
                    if (frameNonce == null || allZero(frameNonce)) {
                        blockers.add("EAPOL 帧的 key nonce 全零（废帧）");
                        tags.add(Tag.MALFORMED);
                    }
                    // MIC 字段必须被抹零
                    byte[] micField = slice(frame, Hc22000.EAPOL_MIC_OFFSET, 16);
                    if (micField != null && !allZero(micField)) {
                        blockers.add("EAPOL 帧的 MIC 字段未置零（导出时没抹掉，结果必然对不上）");
                        tags.add(Tag.MALFORMED);
                    }
                    // key version 决定 MIC 算法
                    if (keyVer < 1 || keyVer > 3) {
                        blockers.add("Key Version 不是 1/2/3，无法确定 MIC 算法");
                        tags.add(Tag.MALFORMED);
                    }
                    // 声明的长度与实际长度
                    int declared = declaredLength(frame);
                    if (declared > 0) {
                        int actual = frame.length - 4;
                        if (Math.abs(actual - declared) > 8) {
                            warnings.add("帧声明长度 " + declared + " 与实际 " + actual
                                    + " 差得多，可能被截断");
                        }
                    }
                }
            }
            if (e.aNonce == null || e.aNonce.length != 32) {
                blockers.add("ANonce 不是 32 字节");
                tags.add(Tag.MALFORMED);
            } else if (allZero(e.aNonce)) {
                blockers.add("ANonce 全零");
                tags.add(Tag.INCOMPLETE);
            }
        }

        return new Report(blockers.isEmpty(), blockers, tags, warnings, facts);
    }

    // ------------------------------------------------------------------ 小工具

    private static int keyVersion(byte[] frame) {
        if (frame == null || frame.length < 7) return -1;
        int keyInfo = ((frame[5] & 0xFF) << 8) | (frame[6] & 0xFF);
        return keyInfo & 0x07;   // 802.11: Key Descriptor Version 占低 3 位
    }

    private static int declaredLength(byte[] frame) {
        if (frame == null || frame.length < 4) return -1;
        return ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
    }

    private static byte[] slice(byte[] a, int off, int len) {
        if (a == null || off < 0 || off + len > a.length) return null;
        byte[] out = new byte[len];
        System.arraycopy(a, off, out, 0, len);
        return out;
    }

    private static boolean allZero(byte[] a) {
        if (a == null || a.length == 0) return true;
        for (byte b : a) {
            if (b != 0) return false;
        }
        return true;
    }

    private static boolean sameBytes(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    /** 十六进制展示；null/空返回 {@code "(空)"}。 */
    public static String hex(byte[] a) {
        if (a == null || a.length == 0) return "(空)";
        StringBuilder sb = new StringBuilder(a.length * 2);
        for (byte b : a) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
