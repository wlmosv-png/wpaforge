package io.github.wlmosv_png.wpaforge.wps;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯 Java 的 WPS（WSC）Information Element 与属性解析器。
 *
 * <h3>为什么需要它</h3>
 * 现状只有一条能力检测通道：root 侧 {@code BSS <bssid>} 读 {@code wps_config_methods}。
 * 通道异常 / 无 root 时**完全拿不到能力**，界面只能显示"未知"。
 * 而 WPS 能力本来就写在 AP 广播的 Vendor IE（OUI {@code 00:50:F2} type {@code 04}）里，
 * 本地解析即可拿到 —— 这是第二条独立通道。
 *
 * <h3>参考来源</h3>
 * 协议常量与属性布局参考 hostap / wpa_supplicant 的公开定义（BSD-3-Clause）：
 * {@code src/wps/wps_defs.h}（属性 ID / config_methods 位 / dev password id）、
 * {@code src/wps/wps_attr_parse.c}（TLV 遍历与长度校验）、
 * {@code src/wps/wps_attr_build.c}（IE 封装：{@code 0xDD | len | 00:50:F2 | 04 | attrs}）。
 * 本类为**独立实现**，不含任何 GPL 代码。
 *
 * <h3>严格性要求（本类的存在意义）</h3>
 * <ul>
 *   <li>截断 / 长度不符 / 重复属性 → 记录到 {@link Result#issues}，**绝不静默丢弃**；</li>
 *   <li>数据不完整时 {@link Result#complete()} 为 false，调用方不得当成完整能力；</li>
 *   <li>解析失败 ≠ "不支持"，未读到的字段保持 {@code -1} / 空串；</li>
 *   <li>纯逻辑、无 Android 依赖、可离线单测。</li>
 * </ul>
 */
public final class WpsIeParser {

    private WpsIeParser() {
    }

    // ---------------- IE / OUI ----------------
    /** 802.11 Vendor Specific IE id。 */
    public static final int EID_VENDOR_SPECIFIC = 221;      // 0xDD
    /** WFA OUI（3 字节）。 */
    public static final int OUI_WFA = 0x0050F2;
    /** WPS 的 OUI type（紧跟 OUI 的那一字节）。 */
    public static final int WPS_OUI_TYPE = 0x04;

    // ---------------- 属性 ID（WSC 规范，wps_defs.h） ----------------
    public static final int ATTR_CONFIG_METHODS = 0x1008;
    public static final int ATTR_DEVICE_NAME = 0x1011;
    public static final int ATTR_DEV_PASSWORD_ID = 0x1012;
    public static final int ATTR_MANUFACTURER = 0x1021;
    public static final int ATTR_MODEL_NAME = 0x1023;
    public static final int ATTR_MODEL_NUMBER = 0x1024;
    public static final int ATTR_SELECTED_REGISTRAR = 0x1041;
    public static final int ATTR_WPS_STATE = 0x1044;
    public static final int ATTR_SELECTED_REGISTRAR_CFG_METHODS = 0x1053;
    public static final int ATTR_AP_SETUP_LOCKED = 0x1057;
    public static final int ATTR_VERSION = 0x104A;

    // ---------------- config_methods 位（wps_defs.h） ----------------
    public static final int CM_USBA = 0x0001;
    public static final int CM_ETHERNET = 0x0002;
    public static final int CM_LABEL = 0x0004;
    public static final int CM_DISPLAY = 0x0008;
    public static final int CM_EXT_NFC_TOKEN = 0x0010;
    public static final int CM_INT_NFC_TOKEN = 0x0020;
    public static final int CM_NFC_INTERFACE = 0x0040;
    public static final int CM_PUSHBUTTON = 0x0080;
    public static final int CM_KEYPAD = 0x0100;
    public static final int CM_P2PS = 0x1000;

    // ---------------- device password id ----------------
    public static final int DEV_PW_DEFAULT = 0x0000;
    public static final int DEV_PW_USER_SPECIFIED = 0x0001;
    public static final int DEV_PW_MACHINE_SPECIFIED = 0x0002;
    public static final int DEV_PW_REKEY = 0x0003;
    public static final int DEV_PW_PUSHBUTTON = 0x0004;
    public static final int DEV_PW_REGISTRAR_SPECIFIED = 0x0005;

    /** 未读到（区别于 0）。 */
    public static final int UNKNOWN = -1;

    // ---------------- 结果 ----------------

    /** 一条被解析出的属性（保留原始位置，便于报告问题）。 */
    public static final class Attr {
        public final int type;
        /** 在 WPS 属性区中的字节偏移。 */
        public final int offset;
        /** 声明长度（TLV 里的 len 字段）。 */
        public final int declaredLen;
        /** 实际取到的值（可能短于 declaredLen —— 截断时）。 */
        public final byte[] value;
        /** 是否因数据截断而取不满。 */
        public final boolean truncated;

        Attr(int type, int offset, int declaredLen, byte[] value, boolean truncated) {
            this.type = type;
            this.offset = offset;
            this.declaredLen = declaredLen;
            this.value = value;
            this.truncated = truncated;
        }
    }

    /** 解析结果。所有"未读到"的字段保持 {@link #UNKNOWN} / 空串。 */
    public static final class Result {
        /** 是否找到 WPS vendor IE。 */
        public boolean wpsIeFound;
        /** 发现的 WPS vendor IE 个数（>1 = 重复 IE，可疑）。 */
        public int wpsIeCount;
        /** IE 总字节数 / 已消费字节数（判断截断）。 */
        public int ieBytesTotal;
        public int wpsAttrBytesTotal;

        public int version = UNKNOWN;
        public int wpsState = UNKNOWN;
        public int configMethods = UNKNOWN;
        public int devPasswordId = UNKNOWN;
        public int selectedRegistrar = UNKNOWN;
        public int selectedRegistrarConfigMethods = UNKNOWN;
        public int apSetupLocked = UNKNOWN;
        public String deviceName = "";
        public String manufacturer = "";
        public String modelName = "";
        public String modelNumber = "";

        /** 解析出的属性总数（含未知类型）。 */
        public int attrCount;
        /** 未知属性类型的个数（正常，仅统计）。 */
        public int unknownAttrCount;
        /** 是否发生截断（数据不完整）。 */
        public boolean truncated;
        /** 解析问题清单（**绝不静默丢弃**）。 */
        public final List<String> issues = new ArrayList<>();
        /** 所有属性（按出现顺序）。 */
        public final List<Attr> attrs = new ArrayList<>();

        /** 是否找到了 WPS IE（唯一可以据此说"观察到 WPS"的条件）。 */
        public boolean observed() {
            return wpsIeFound;
        }

        /**
         * 数据是否"足以判断 PIN 能力"。
         *
         * <p>必须同时满足：找到 IE + 未截断 + config_methods 读到且长度合法。
         * 否则调用方只能显示"未知"，**不得**推断为"不支持"。
         */
        public boolean complete() {
            return wpsIeFound && !truncated && configMethods != UNKNOWN;
        }

        /** AP 是否声明可接受外部 PIN（KEYPAD 或 LABEL）——仅"声明"，不等于可验证。 */
        public boolean declaresPin() {
            if (configMethods == UNKNOWN) return false;
            return (configMethods & CM_KEYPAD) != 0 || (configMethods & CM_LABEL) != 0;
        }

        /** AP 是否声明支持 PBC（按钮）。 */
        public boolean declaresPbc() {
            return configMethods != UNKNOWN && (configMethods & CM_PUSHBUTTON) != 0;
        }

        /** AP 是否报 AP Setup Locked。 */
        public boolean locked() {
            return apSetupLocked == 1;
        }

        /** 问题摘要（空串 = 无问题）。 */
        public String issueSummary() {
            if (issues.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < issues.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(issues.get(i));
            }
            return sb.toString();
        }
    }

    // ---------------- 入口 ----------------

    /**
     * 解析 supplicant {@code BSS} 输出里的 {@code ie=} 十六进制串（全部 IE 拼接）。
     *
     * @param ieHex 形如 {@code 000b6d792d73736964dd0a0050f204...}；null/空 → 返回空结果
     */
    public static Result parseIeHex(String ieHex) {
        Result r = new Result();
        if (ieHex == null || ieHex.trim().isEmpty()) {
            r.issues.add("IE 为空");
            return r;
        }
        byte[] b = hexToBytes(ieHex.trim());
        if (b == null) {
            r.issues.add("IE 十六进制串非法（含非 hex 字符或长度为奇数）");
            return r;
        }
        r.ieBytesTotal = b.length;
        parseIeBlob(b, r);
        return r;
    }

    /**
     * 直接解析一段 WPS 属性区（已剥掉 {@code 0xDD | len | 00:50:F2 | 04} 之后的部分）。
     */
    public static Result parseWpsAttributes(byte[] wps) {
        Result r = new Result();
        if (wps == null || wps.length == 0) {
            r.issues.add("WPS 属性区为空");
            return r;
        }
        r.wpsIeFound = true;
        r.wpsIeCount = 1;
        r.wpsAttrBytesTotal = wps.length;
        parseAttrs(wps, 0, wps.length, r);
        return r;
    }

    // ---------------- 内部 ----------------

    private static void parseIeBlob(byte[] b, Result r) {
        int i = 0;
        while (i < b.length) {
            if (i + 2 > b.length) {
                r.truncated = true;
                r.issues.add("IE 头截断：偏移 " + i + " 处不足 id+len 两字节");
                break;
            }
            int id = b[i] & 0xFF;
            int len = b[i + 1] & 0xFF;
            int dataStart = i + 2;
            if (dataStart + len > b.length) {
                r.truncated = true;
                r.issues.add("IE(id=" + id + ") 声明长度 " + len
                        + " 超出剩余数据（偏移 " + i + "），按截断处理");
                // 尽量解析已有的部分（若这是 WPS IE）
                int avail = b.length - dataStart;
                if (id == EID_VENDOR_SPECIFIC && avail >= 4) {
                    byte[] partial = new byte[avail];
                    System.arraycopy(b, dataStart, partial, 0, avail);
                    maybeParseWpsVendor(partial, r, false);
                }
                break;
            }
            if (id == EID_VENDOR_SPECIFIC) {
                byte[] data = new byte[len];
                System.arraycopy(b, dataStart, data, 0, len);
                maybeParseWpsVendor(data, r, true);
            }
            i = dataStart + len;
        }
    }

    /** 判断是否 WPS vendor IE（OUI 00:50:F2 + type 0x04），是则解析其属性区。 */
    private static void maybeParseWpsVendor(byte[] data, Result r, boolean complete) {
        if (data.length < 4) {
            if (r.wpsIeCount > 0) {
                r.issues.add("WPS vendor IE 头不足 4 字节（OUI+type），无法确认");
            }
            return;
        }
        int oui = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        int type = data[3] & 0xFF;
        if (oui != OUI_WFA || type != WPS_OUI_TYPE) {
            return;     // 非 WPS 的 vendor IE（如 WMM），不关心
        }
        r.wpsIeFound = true;
        r.wpsIeCount++;
        if (r.wpsIeCount > 1) {
            r.issues.add("发现 " + r.wpsIeCount + " 个 WPS vendor IE（重复 IE，数据可能异常）");
        }
        if (!complete) {
            r.truncated = true;
            r.issues.add("WPS vendor IE 声明长度超出数据，属性区可能不完整");
        }
        int attrLen = data.length - 4;
        r.wpsAttrBytesTotal = attrLen;
        parseAttrs(data, 4, attrLen, r);
    }

    /** 遍历属性 TLV：type(2B BE) | len(2B BE) | value。 */
    private static void parseAttrs(byte[] buf, int start, int len, Result r) {
        int i = start;
        int end = start + len;
        while (i + 4 <= end) {
            int type = ((buf[i] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
            int alen = ((buf[i + 2] & 0xFF) << 8) | (buf[i + 3] & 0xFF);
            int vStart = i + 4;
            boolean trunc = false;
            int avail = end - vStart;
            int take = alen;
            if (take > avail) {
                take = avail;
                trunc = true;
                r.truncated = true;
                r.issues.add("属性 0x" + Integer.toHexString(type) + " 声明长度 " + alen
                        + " 超出剩余 " + avail + " 字节，按截断取值");
            }
            byte[] value = new byte[take];
            if (take > 0) System.arraycopy(buf, vStart, value, 0, take);
            Attr a = new Attr(type, i - start, alen, value, trunc);
            r.attrs.add(a);
            r.attrCount++;
            applyAttr(a, r);
            i = vStart + take;
            if (trunc) break;       // 后面不可能再对齐
        }
        if (i < end && i + 4 > end) {
            int leftover = end - i;
            if (leftover > 0) {
                r.truncated = true;
                r.issues.add("属性区尾部残留 " + leftover + " 字节（不足一个 TLV 头），已忽略");
            }
        }
    }

    /** 把一条属性的值写入结果；重复属性只取首个并记录问题。 */
    private static void applyAttr(Attr a, Result r) {
        switch (a.type) {
            case ATTR_VERSION:
                if (checkLen(a, 1, r)) r.version = a.value[0] & 0xFF;
                break;
            case ATTR_WPS_STATE:
                if (checkLen(a, 1, r)) r.wpsState = a.value[0] & 0xFF;
                break;
            case ATTR_CONFIG_METHODS:
                if (checkLen(a, 2, r)) {
                    if (r.configMethods == UNKNOWN) {
                        r.configMethods = u16(a.value);
                    } else {
                        r.issues.add("重复的 config_methods 属性，已保留首个（0x"
                                + Integer.toHexString(r.configMethods) + "）");
                    }
                }
                break;
            case ATTR_DEV_PASSWORD_ID:
                if (checkLen(a, 2, r)) {
                    if (r.devPasswordId == UNKNOWN) r.devPasswordId = u16(a.value);
                    else r.issues.add("重复的 device_password_id 属性，已保留首个");
                }
                break;
            case ATTR_SELECTED_REGISTRAR:
                if (checkLen(a, 1, r)) {
                    if (r.selectedRegistrar == UNKNOWN) r.selectedRegistrar = a.value[0] & 0xFF;
                    else r.issues.add("重复的 selected_registrar 属性，已保留首个");
                }
                break;
            case ATTR_SELECTED_REGISTRAR_CFG_METHODS:
                if (checkLen(a, 2, r)) {
                    if (r.selectedRegistrarConfigMethods == UNKNOWN) {
                        r.selectedRegistrarConfigMethods = u16(a.value);
                    } else {
                        r.issues.add("重复的 selected_registrar_config_methods 属性，已保留首个");
                    }
                }
                break;
            case ATTR_AP_SETUP_LOCKED:
                if (checkLen(a, 1, r)) {
                    if (r.apSetupLocked == UNKNOWN) r.apSetupLocked = a.value[0] & 0xFF;
                    else r.issues.add("重复的 ap_setup_locked 属性，已保留首个");
                }
                break;
            case ATTR_DEVICE_NAME:
                if (r.deviceName.isEmpty()) r.deviceName = utf8(a.value);
                else r.issues.add("重复的 device_name 属性，已保留首个");
                break;
            case ATTR_MANUFACTURER:
                if (r.manufacturer.isEmpty()) r.manufacturer = utf8(a.value);
                break;
            case ATTR_MODEL_NAME:
                if (r.modelName.isEmpty()) r.modelName = utf8(a.value);
                break;
            case ATTR_MODEL_NUMBER:
                if (r.modelNumber.isEmpty()) r.modelNumber = utf8(a.value);
                break;
            default:
                r.unknownAttrCount++;
                break;
        }
    }

    private static boolean checkLen(Attr a, int expect, Result r) {
        if (a.truncated) {
            r.issues.add("属性 0x" + Integer.toHexString(a.type) + " 被截断，长度不足");
            return false;
        }
        if (a.value.length != expect) {
            r.issues.add("属性 0x" + Integer.toHexString(a.type) + " 长度应为 " + expect
                    + "，实为 " + a.value.length + "，已忽略");
            return false;
        }
        return true;
    }

    private static int u16(byte[] v) {
        return ((v[0] & 0xFF) << 8) | (v[1] & 0xFF);
    }

    /** UTF-8 解码；非法字节替换而不是抛异常。 */
    private static String utf8(byte[] v) {
        if (v == null || v.length == 0) return "";
        try {
            return new String(v, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 严格 hex 解析：奇数长度或非 hex 字符 → null。 */
    static byte[] hexToBytes(String hex) {
        int n = hex.length();
        if (n % 2 != 0) return null;
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            int hi = hexVal(hex.charAt(i));
            int lo = hexVal(hex.charAt(i + 1));
            if (hi < 0 || lo < 0) return null;
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
}
