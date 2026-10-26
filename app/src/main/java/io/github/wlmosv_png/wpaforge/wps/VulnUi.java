package io.github.wlmosv_png.wpaforge.wps;

/**
 * 「已知可打（Pixie）」这一块的门面逻辑：目标行标记 + 名单/通道状态诊断。
 *
 * <h3>为什么单独抽出来纯逻辑<\/h3>
 * 这里的每一条文案都对应一个**可验证的事实**：名单在不在、supplicant 控制通道
 * 通不通、读到了几个设备名、命中几个。判错的方向只有两种，都很贵：
 *   · 说成「已知可打」而其实没读到设备名 —— 用户拿着一个空数据去下结论；
 *   · 说成「没问题」而其实是通道被系统策略拦了 —— 用户以为这批 AP 都安全。
 * 所以判定与文案全在这里，界面只负责显示；测试也直接打这一层。
 */
public final class VulnUi {

    /** 名单还没下载 —— 标记功能整体不可用 */
    public static final int NO_LIST = 0;
    /** 名单就绪，但没扫到支持 WPS 的 AP */
    public static final int NO_AP = 1;
    /** supplicant 控制通道不通：回包被系统策略拦下，设备名\/能力都读不到 */
    public static final int NO_CHANNEL = 2;
    /** 通道通，但这些 AP 都没自报 WPS 设备名 */
    public static final int NO_NAME = 3;
    /** 有命中 */
    public static final int HIT = 4;
    /** 一切正常，只是没命中 */
    public static final int NONE = 5;

    /** 诊断结果：码 + 已本地化文案 + 是否要用户动手 */
    public static final class Diag {
        public final int code;
        public final String text;
        /** 需要用户处理（界面标红） */
        public final boolean action;

        Diag(int code, String text, boolean action) {
            this.code = code;
            this.text = text;
            this.action = action;
        }
    }

    private VulnUi() {}

    /** 目标行右侧胶囊里的短标记；没命中返回空串。 */
    public static String shortLabel(VulnWsc.Match m, boolean zh) {
        if (m == null) return "";
        if (!m.entry.hintPin.isEmpty()) return zh ? "可打 · PIN" : "Pixie PIN";
        if (!m.versionMatched) return zh ? "可打?" : "Pixie?";
        return zh ? "可打" : "Pixie";
    }

    /** 短标记放不下的补充细节（默认 PIN \/ 版本不同）；没有返回空串。 */
    public static String detail(VulnWsc.Match m, boolean zh) {
        if (m == null) return "";
        if (!m.entry.hintPin.isEmpty()) {
            return zh ? "默认 PIN " + m.entry.hintPin : "default PIN " + m.entry.hintPin;
        }
        if (!m.versionMatched) {
            return zh ? "版本与名单不同" : "version differs";
        }
        return "";
    }

    /** 名单按钮上的字。 */
    public static String listButton(boolean exists, int entries, boolean busy, boolean zh) {
        if (busy) return zh ? "正在下载名单…" : "Downloading list…";
        if (!exists) {
            return zh ? "下载「已知可打」名单（约 3.5 KB）"
                      : "Download Pixie-vulnerable list (~3.5 KB)";
        }
        if (entries <= 0) {
            return zh ? "名单文件在，但解析出 0 条（点此重下）"
                      : "List file present but parsed 0 entries (tap to re-download)";
        }
        return zh ? "已知可打名单：" + entries + " 条（点击更新）"
                  : "Pixie list: " + entries + " entries (tap to update)";
    }

    /**
     * 状态诊断。
     *
     * @param listExists  名单文件是否已落盘
     * @param listEntries 名单解析出的条目数
     * @param apCount     本次扫到的支持 WPS 的 AP 数
     * @param namedCount  其中读到 WPS 设备名的个数
     * @param hitCount    命中名单的个数
     * @param ctrlOk      supplicant 控制通道自检是否通过（回包收得到）
     */
    public static Diag diagnose(boolean listExists, int listEntries,
                               int apCount, int namedCount, int hitCount,
                               boolean ctrlOk, boolean zh) {
        if (!listExists) {
            return new Diag(NO_LIST, zh
                    ? "名单未下载 —— 已知可打标记不可用。点上面的按钮下载（约 3.5 KB，来源 OneShot）。"
                    : "List not downloaded - marker unavailable. Tap the button above "
                      + "(~3.5 KB, source OneShot).", true);
        }
        if (apCount <= 0) {
            return new Diag(NO_AP, zh
                    ? "名单 " + listEntries + " 条已就绪；本机没扫到支持 WPS 的 AP。"
                    : "List ready (" + listEntries + "). No WPS-capable AP found.", false);
        }
        if (!ctrlOk) {
            return new Diag(NO_CHANNEL, zh
                    ? "supplicant 控制通道不通：回包被系统 SELinux 拦下，读不到 WPS 设备名与能力"
                      + "（" + apCount + " 个目标里读到 " + namedCount + " 个设备名）→ 无法比对名单。"
                      + "root 设备需给 supplicant 补一条 sepolicy 放行规则（见 README「WPS 通道」）。"
                    : "Supplicant control channel blocked (SELinux denies the reply): WPS device "
                      + "names/capabilities unreadable (" + namedCount + " of " + apCount
                      + ") - cannot match the list. Rooted devices need one sepolicy allow "
                      + "rule (see README, WPS channel).", true);
        }
        if (namedCount <= 0) {
            return new Diag(NO_NAME, zh
                    ? "通道正常，但 " + apCount + " 个目标都没有自报 WPS 设备名，无从比对名单。"
                    : "Channel OK, but none of the " + apCount
                      + " targets report a WPS device name - nothing to match.", false);
        }
        if (hitCount > 0) {
            return new Diag(HIT, zh
                    ? "命中 " + hitCount + " 个已知可打（Pixie）目标，已在列表标出。"
                    : hitCount + " known Pixie-vulnerable target(s) - marked in the list.", false);
        }
        return new Diag(NONE, zh
                ? "名单 " + listEntries + " 条；" + apCount + " 个目标里 " + namedCount
                  + " 个读到设备名，均不在名单里。"
                : "List has " + listEntries + " entries; " + namedCount + " of " + apCount
                  + " targets report a device name, none listed.", false);
    }
}
