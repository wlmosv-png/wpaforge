package io.github.wlmosv_png.wpaforge.cap;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行模式的**唯一真相来源**：当前这套能力是怎么来的。
 *
 * <h3>为什么模式不能由「设置里存了个数字」决定</h3>
 * 旧实现 {@code Prefs.mode()} 恒返回 Root —— 设置项代表的是**用户意愿**，
 * 而设置项并不代表「这套能力真的可用」。结果就是：通道断了照跑，
 * 跑完几百条全是「未连上」，用户以为密码不对。
 *
 * <p>所以这里把两件事分开：
 * <ul>
 *   <li>{@link #modeId()} + {@link #source()} —— 这套能力**实际**是怎么来的；</li>
 *   <li>{@link #notice()} —— 当实际模式与用户选择不一致时，**必须明说**，
 *       不许默默降级（默默降级 = 让用户以为在跑 Root 链）。</li>
 * </ul>
 *
 * <p>纯逻辑、无 Android 依赖。
 */
public final class RunMode {

    /** Root 模式：{@code su + cmd wifi}，全链可用。 */
    public static final int ROOT = 0;
    /** 无 Root 模式：只用公开 API，判定链按能力裁剪。 */
    public static final int ROOTLESS = 1;
    /** 自动检测：结果落到上面两者之一，不会停留在 AUTO。 */
    public static final int AUTO = 2;

    public static final String SRC_USER = "USER";          // 用户在设置里选的
    public static final String SRC_AUTO = "AUTO";          // 自动探测得出
    public static final String SRC_FALLBACK = "FALLBACK";  // 首选不可用，降级到另一套

    private final int modeId;
    private final String requestedId;   // 用户选的原值（ROOT/ROOTLESS/AUTO）
    private final String source;
    private final String noticeZh;
    private final String noticeEn;
    private final String whyZh;
    private final String whyEn;
    private final List<Capability> caps;

    private RunMode(int modeId, String requestedId, String source,
                    String noticeZh, String noticeEn, String whyZh, String whyEn,
                    List<Capability> caps) {
        this.modeId = modeId;
        this.requestedId = requestedId == null ? "" : requestedId;
        this.source = source == null ? SRC_USER : source;
        this.noticeZh = noticeZh == null ? "" : noticeZh;
        this.noticeEn = noticeEn == null ? "" : noticeEn;
        this.whyZh = whyZh == null ? "" : whyZh;
        this.whyEn = whyEn == null ? "" : whyEn;
        this.caps = caps == null ? new ArrayList<Capability>() : caps;
    }

    public static RunMode root(String requested, String source, List<Capability> caps) {
        return new RunMode(ROOT, requested, source, "", "", "", "", caps);
    }

    public static RunMode rootless(String requested, String source, List<Capability> caps) {
        return new RunMode(ROOTLESS, requested, source, "", "", "", "", caps);
    }

    /** 带「为什么不一致」说明的模式（降级 / 升级到另一套时务必用这个）。 */
    public static RunMode of(int modeId, String requested, String source,
                             String noticeZh, String noticeEn,
                             String whyZh, String whyEn,
                             List<Capability> caps) {
        return new RunMode(modeId, requested, source, noticeZh, noticeEn, whyZh, whyEn, caps);
    }

    public int modeId() {
        return modeId;
    }

    public boolean isRoot() {
        return modeId == ROOT;
    }

    /** 存储 / 队列字段用的稳定标识。 */
    public String key() {
        return modeId == ROOT ? "root" : "rootless";
    }

    public String requested() {
        return requestedId;
    }

    public String source() {
        return source;
    }

    public String label(boolean zh) {
        return modeId == ROOT ? (zh ? "Root 模式" : "Root mode")
                              : (zh ? "无 Root 模式" : "Rootless mode");
    }

    /** 与用户选择不一致时的提示（空串 = 一致，无需提示）。 */
    public String notice(boolean zh) {
        return zh ? noticeZh : noticeEn;
    }

    public boolean hasNotice() {
        return !noticeZh.isEmpty() || !noticeEn.isEmpty();
    }

    public String why(boolean zh) {
        return zh ? whyZh : whyEn;
    }

    public Capability.Set0 capabilities() {
        Set0KeyHolder h = new Set0KeyHolder();
        return h.make(key(), caps);
    }

    public Capability cap(String id) {
        return capabilities().get(id);
    }

    /** 证据级能力是否齐备（Root 模式下 = 全链；无 Root 模式下 = 永不为 true）。 */
    public boolean canProduceDirectEvidence() {
        return cap(Capability.C_WIFI_CONNECT).evidenceGrade()
                && cap(Capability.C_WIFI_FORGET).evidenceGrade();
    }

    /**
     * jsonl / 队列 / 诊断文件里写的模式串。
     *
     * <p>旧值兼容：老队列里 {@code crackMode} 可能是 0/1/2 的历史含义
     * （0=Root，1=免 Root，2=Shizuku）。**1 与 2 一律按 Root 解释**——
     * 绝不能让一个旧值把任务落到「已经证明跑不动」的通道上。
     */
    public static int parseStoredMode(int stored) {
        return stored == ROOTLESS_MARKER ? ROOTLESS : ROOT;
    }

    /** 只会来自新版本自己写入的标记值；历史值不会命中它。 */
    public static final int ROOTLESS_MARKER = 9;

    public static int storedModeFor(int modeId) {
        return modeId == ROOTLESS ? ROOTLESS_MARKER : ROOT;
    }

    public static String nameOf(int modeId) {
        if (modeId == ROOTLESS) return "rootless";
        if (modeId == AUTO) return "auto";
        return "root";
    }

    /** 小型持有类：纯粹为了避免在 {@link #capabilities()} 里写一长串泛型。 */
    private static final class Set0KeyHolder {
        Capability.Set0 make(String key, List<Capability> caps) {
            return new Capability.Set0(key, 0L, caps);
        }
    }
}
