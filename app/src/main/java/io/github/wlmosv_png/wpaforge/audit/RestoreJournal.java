package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置恢复记录（R11：任务结束后的网络配置恢复）—— **纯逻辑，无 Android 依赖**。
 *
 * <h3>它解决的那个真实缺口</h3>
 * 引擎为了保证归因，会在**每条候选之前**把目标网络从系统里删掉，
 * 再用候选口令把它加回来（{@code cmd wifi forget-network} → {@code cmd wifi connect-network}）。
 * 于是任务跑完之后，系统里保存的是**最后一条候选的口令**：
 * <ul>
 *   <li>命中收尾 → 留的是正确口令 → 恰好等于恢复；</li>
 *   <li>未命中 / 暂停 / 取消 / 异常收尾 → 留的是**错误口令**，
 *       而用户原来的配置已经被删掉 → 手机连不上自己的网络，必须手输口令。</li>
 * </ul>
 * 而且原来的收尾只有 {@code finally}，**进程被杀时它不一定执行**。
 *
 * <h3>做法</h3>
 * 在任何破坏性动作**之前**，把目标网络的原始配置写成一条 journal 记录：
 * 原 netId、原口令（**加密后**）、加密方式、本次 runId、阶段。
 * 恢复流程分三步，每一步的结果都落到同一条记录里：
 * <ol>
 *   <li>{@link RestorePlan.Step#REMOVE_WRONG_CONFIG} 删掉候选留下的配置；</li>
 *   <li>{@link RestorePlan.Step#READD_ORIGINAL} 用**原口令**把目标加回去；</li>
 *   <li>{@link RestorePlan.Step#VERIFY} 读回状态确认真的恢复了。</li>
 * </ol>
 *
 * <h3>为什么不能只靠 {@code finally}</h3>
 * 进程被系统杀掉（ColorOS 后台冻结后强停）、被 SIGKILL、设备重启时，
 * {@code finally} 不一定跑到。所以记录**必须持久化**，并且区分阶段：
 * 下次进程启动时发现存在 {@code ARMED}（未完成）的记录，就补做一次恢复。
 *
 * <h3>口令怎么存</h3>
 * 原口令是**恢复所必需**的，不能脱敏掉；但也绝不能明文落盘。
 * 因此写到记录里的是 {@link SecretBox} 的密文（Android Keystore，AES/GCM）。
 * Keystore 不可用时 {@code sealedPsk} 为空 —— 此时**不再假装能恢复**，
 * 而是明确把结果标成“原口令无法还原”，见 {@link RestorePlan#judge}。
 */
public final class RestoreJournal {

    /** 已登记，尚未恢复（进程被杀后会停在这个阶段 → 下次启动补做）。 */
    public static final String STAGE_ARMED = "ARMED";
    /** 恢复完成并已读回验证。 */
    public static final String STAGE_RESTORED = "RESTORED";
    /** 恢复尝试过但失败 —— 必须保留记录与原因，不许静默丢弃。 */
    public static final String STAGE_FAILED = "FAILED";

    private RestoreJournal() {}

    // ---------------------------------------------------------------- 记录

    /** 一条恢复记录。字段全部可序列化，不含任何 Android 类型。 */
    public static final class Entry {
        public String stage = STAGE_ARMED;
        public String ssid = "";
        public String bssid = "";
        /** ConnectSpec 用的加密方式参数（wpa2 / wpa3 / open …）。 */
        public String security = "wpa2";
        /** 破坏性动作之前目标的 netId；-1 = 当时目标本来就不在已保存列表里。 */
        public long originalNetId = -1L;
        /** 原本是否存在已保存配置（决定要不要重新加回去）。 */
        public boolean hadPsk = false;
        /** {@link SecretBox} 密文；空串 = 拿不到安全的原口令。 */
        public String sealedPsk = "";
        /** 快照步骤本身是否成功（失败时恢复能力受限，必须如实报告）。 */
        public boolean snapshotOk = false;
        public String runId = "";
        /**
         * **任务归属**（F3）：这条记录属于哪个任务。
         *
         * <p>为什么必须有它：收尾恢复靠它判断「盘上那条是不是我自己的」。
         * 缺少这一层时，一个新任务在记录落盘失败后会拿**上一个任务的旧记录**
         * 去执行恢复 —— 那等于用另一个目标的口令去覆盖当前目标。
         * 旧数据没有这个字段 → 默认空串，一律判为「不属于任何任务」（不猜）。
         */
        public String taskId = "";
        /**
         * 采集当时是否读到了「已保存网络列表」（F6）。
         *
         * <p>{@code false} 表示连目标当前有没有配置都不知道 —— 这种快照不可信。
         */
        public boolean channelOk = false;
        public long armedAt = 0L;
        public long finishedAt = 0L;
        public String failStep = "";
        public String failDetail = "";

        public Entry() {}

        public boolean armed() { return STAGE_ARMED.equals(stage); }
        public boolean restored() { return STAGE_RESTORED.equals(stage); }

        /** 能不能真的把原配置加回来（有口令 + 有加密通道）。 */
        public boolean canReadd() { return hadPsk && sealedPsk != null && !sealedPsk.isEmpty(); }

        public String toText() {
            StringBuilder sb = new StringBuilder();
            kv(sb, "stage", stage);
            kv(sb, "ssid", ssid);
            kv(sb, "bssid", bssid);
            kv(sb, "security", security);
            kv(sb, "originalNetId", String.valueOf(originalNetId));
            kv(sb, "hadPsk", hadPsk ? "1" : "0");
            kv(sb, "sealedPsk", sealedPsk);
            kv(sb, "snapshotOk", snapshotOk ? "1" : "0");
            kv(sb, "runId", runId);
            kv(sb, "taskId", taskId);
            kv(sb, "channelOk", channelOk ? "1" : "0");
            kv(sb, "armedAt", String.valueOf(armedAt));
            kv(sb, "finishedAt", String.valueOf(finishedAt));
            kv(sb, "failStep", failStep);
            kv(sb, "failDetail", failDetail);
            return sb.toString();
        }

        /** 与 {@link #toText()} 对称；任一行无法解析时返回 {@code null}（不猜、不伪造）。 */
        public static Entry fromText(String text) {
            if (text == null || text.trim().isEmpty()) return null;
            Entry e = new Entry();
            for (String raw : text.split("\n")) {
                if (raw.isEmpty()) continue;
                int i = raw.indexOf('=');
                if (i <= 0) return null;
                String k = raw.substring(0, i);
                String v = unesc(raw.substring(i + 1));
                switch (k) {
                    case "stage": e.stage = v; break;
                    case "ssid": e.ssid = v; break;
                    case "bssid": e.bssid = v; break;
                    case "security": e.security = v; break;
                    case "originalNetId": e.originalNetId = parseLong(v, -1L); break;
                    case "hadPsk": e.hadPsk = "1".equals(v); break;
                    case "sealedPsk": e.sealedPsk = v; break;
                    case "snapshotOk": e.snapshotOk = "1".equals(v); break;
                    case "runId": e.runId = v; break;
                    case "taskId": e.taskId = v; break;
                    case "channelOk": e.channelOk = "1".equals(v); break;
                    case "armedAt": e.armedAt = parseLong(v, 0L); break;
                    case "finishedAt": e.finishedAt = parseLong(v, 0L); break;
                    case "failStep": e.failStep = v; break;
                    case "failDetail": e.failDetail = v; break;
                    default: break;   // 未知键：向前兼容，忽略而不是报错
                }
            }
            if (e.ssid == null || e.ssid.isEmpty()) return null;
            return e;
        }

        private static long parseLong(String s, long def) {
            try { return Long.parseLong(s); } catch (Throwable t) { return def; }
        }

        private static void kv(StringBuilder sb, String k, String v) {
            sb.append(k).append('=').append(esc(v == null ? "" : v)).append('\n');
        }

        private static String esc(String s) {
            return s.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\e");
        }

        private static String unesc(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char n = s.charAt(++i);
                    if (n == 'n') sb.append('\n');
                    else if (n == 'e') sb.append('=');
                    else if (n == '\\') sb.append('\\');
                    else sb.append(n);
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- 存储

    /** 记录载体。生产是应用私有目录里的一个文件；测试是内存。 */
    public interface Store {
        String read();
        boolean write(String text);
        boolean clear();
    }

    private static volatile Store store = null;

    /** 安装记录载体（幂等）。 */
    public static void install(Store s) { store = s; }

    /** 是否已安装载体。 */
    public static boolean installed() { return store != null; }

    /** 测试/预览用的内存载体。 */
    public static Store memory() {
        final String[] slot = new String[1];
        return new Store() {
            @Override public String read() { return slot[0]; }
            @Override public boolean write(String text) { slot[0] = text; return true; }
            @Override public boolean clear() { slot[0] = null; return true; }
        };
    }

    /** 读当前记录；没有或读不到返回 {@code null}。 */
    public static Entry load() {
        Store s = store;
        if (s == null) return null;
        try { return Entry.fromText(s.read()); } catch (Throwable t) { return null; }
    }

    /** 写记录。载体缺失或写失败返回 {@code false}（调用方必须把它当“恢复能力未知”）。 */
    public static boolean save(Entry e) {
        Store s = store;
        if (s == null || e == null) return false;
        try { return s.write(e.toText()); } catch (Throwable t) { return false; }
    }

    /** 清除记录（只在恢复成功后调用）。 */
    public static boolean clear() {
        Store s = store;
        if (s == null) return false;
        try { return s.clear(); } catch (Throwable t) { return false; }
    }

    /**
     * 是否存在**未完成**的恢复记录（进程被杀后下次启动要靠它补做）。
     * {@code FAILED} 也算未完成 —— 失败必须留下可追踪状态，不能自己消失。
     */
    public static boolean hasPending() {
        Entry e = load();
        return e != null && !e.restored();
    }

    /**
     * 盘上**有内容**，但读不出来 / 解析不了（F7）。
     *
     * <h3>为什么「未知」不能当成「没有」</h3>
     * {@link #load()} 把三种情况压成同一个 {@code null}：文件不存在、
     * 文件读失败、文件内容损坏。只在最后一种情况下，盘上其实**躺着一条记录**
     * —— 而它可能是**另一个目标**未完成的恢复。把它当成「没有待办」，
     * 下一次 {@link #save(Entry)} 就会直接把它覆盖掉：
     * 那个目标的原口令从此永久丢失，谁也补做不了，而且没人会发现。
     *
     * <p>所以这里把「有内容但不可用」单独标出来，让闸门按 <b>fail closed</b>
     * 处理（见 {@link RestoreGuard.Reason#OTHER_PENDING} 的语义）。
     *
     * @return true = 盘上有内容但不可解析 / 读异常（调用方**不得**当成“没有记录”）
     */
    public static boolean pendingUnknown() {
        Store s = store;
        if (s == null) return false;
        String raw;
        try {
            raw = s.read();
        } catch (Throwable t) {
            // 载体自己抛异常 —— 同样属于「不知道盘上是什么」，按未知处理。
            return true;
        }
        if (raw == null || raw.trim().isEmpty()) return false;
        return Entry.fromText(raw) == null;
    }

    /**
     * 写入后**回读校验**（纯逻辑，便于离线断言）。
     *
     * <p>为什么必须回读：载体完全可能「返回 true 但没真写下去」
     * （改名失败、目录不可写、旧文件删不掉…）。一旦谎报成功，
     * 闸门会以为已有补做依据，进程被杀后却拿着**盘上那份旧记录**去恢复 ——
     * 正是 R11 要堵的那条路。所以「写成功」的定义是**读回来的内容与我写的一致**。
     *
     * @return true = 回读内容与 {@code text} 完全一致
     */
    public static boolean verifyWritten(Store s, String text) {
        if (s == null || text == null) return false;
        try {
            return text.equals(s.read());
        } catch (Throwable t) {
            return false;
        }
    }
}
