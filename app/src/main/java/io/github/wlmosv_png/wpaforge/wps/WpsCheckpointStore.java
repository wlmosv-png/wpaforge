package io.github.wlmosv_png.wpaforge.wps;

/**
 * 拆半断点的落盘层：主副本 + 备份 + 旧版迁移。
 *
 * <h3>为什么要备份</h3>
 * 断点只有一份的话，任何一次写坏（进程在写 SharedPreferences 时被杀、
 * 磁盘满、字段被别的版本写进半截）就等于用户几小时进度归零。
 * 所以每次 {@link #save} 之前先把**旧主副本**挪进备份 ——
 * 任何时刻至少有一份完整可读的断点。
 *
 * <h3>为什么写失败要「不破坏已有主副本」</h3>
 * 这是原子性的最低要求：写了半个新断点、又丢了旧断点，
 * 比「这次没保存成功」糟糕得多。所以 {@link #save} 一旦发现写入返回 false，
 * 就尽力把主副本恢复成写之前的内容，并把结果如实返回 false。
 *
 * <h3>三种来源的优先级</h3>
 * 主副本 → 备份 → 旧版 5 个 key。旧版是 1.9.x 时期用
 * {@code wpsHalfBssid / Phase / Cursor / Cursor1 / Prefix} 五个独立键存的进度；
 * 迁移成功后会立刻写成新格式落回主副本，下次就只走新路径。
 *
 * <p>存储通过 {@link Blob} 抽象：App 侧用 SharedPreferences 实现，
 * 测试用内存实现，两边走的是**同一段生产逻辑**。
 */
public final class WpsCheckpointStore {

    /** 由 App 侧用 SharedPreferences 实现；测试用内存实现。 */
    public interface Blob {
        String read(String key);

        boolean write(String key, String value);

        boolean remove(String key);
    }

    public static final String KEY_PRIMARY = "wps_cp_v1";
    public static final String KEY_BACKUP = "wps_cp_v1_bak";
    // 旧版 5 个键（迁移用）
    public static final String KEY_LEGACY_BSSID = "wpsHalfBssid";
    public static final String KEY_LEGACY_PHASE = "wpsHalfPhase";
    public static final String KEY_LEGACY_CURSOR = "wpsHalfCursor";
    public static final String KEY_LEGACY_CURSOR1 = "wpsHalfCursor1";
    public static final String KEY_LEGACY_PREFIX = "wpsHalfPrefix";

    private final Blob blob;

    public WpsCheckpointStore(Blob blob) {
        this.blob = blob;
    }

    /**
     * 写主副本；写之前把旧主副本挪到备份。
     *
     * <p>写失败返回 false 且**不破坏**已有主副本（尽力回写旧值）。
     */
    public boolean save(WpsCheckpoint cp) {
        if (blob == null || cp == null) return false;
        try {
            String old = blob.read(KEY_PRIMARY);
            if (old != null && !old.isEmpty()) {
                // 备份失败不阻断主流程：备份只是保险，不是前置条件
                blob.write(KEY_BACKUP, old);
            }
            String enc = cp.encode();
            boolean written = blob.write(KEY_PRIMARY, enc);
            if (!written) {
                if (old != null) {
                    // 尽力恢复：把主副本写回写之前的内容
                    blob.write(KEY_PRIMARY, old);
                }
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 主 → 备份 → 旧版 5 键。任何一步失败都要在 error/note 里说清楚。 */
    public WpsCheckpoint.Parse load() {
        WpsCheckpoint.Parse out = new WpsCheckpoint.Parse();
        if (blob == null) {
            out.error = "断点不可用：存储通道不可用";
            return out;
        }
        try {
            String prim = blob.read(KEY_PRIMARY);
            String bak = blob.read(KEY_BACKUP);
            boolean primPresent = prim != null && !prim.trim().isEmpty();
            boolean bakPresent = bak != null && !bak.trim().isEmpty();

            if (primPresent) {
                WpsCheckpoint.Parse dp = WpsCheckpoint.decode(prim);
                if (dp.ok()) return dp;   // 正常路径：note 为空

                if (bakPresent) {
                    WpsCheckpoint.Parse bp = WpsCheckpoint.decode(bak);
                    if (bp.ok()) {
                        // 自愈：把备份内容写回主副本，免得下次还要走恢复路径
                        blob.write(KEY_PRIMARY, bp.cp.encode());
                        bp.note = "主副本损坏，已用备份恢复";
                        bp.error = "";
                        return bp;
                    }
                }
                // 主备都坏 → 还有旧版可试
                WpsCheckpoint.Parse lp = fromLegacyKeys();
                if (lp != null) return lp;

                WpsCheckpoint.Parse err = new WpsCheckpoint.Parse();
                err.error = "断点不可用：主副本与备份都无法解析（主副本：" + dp.error + "）";
                return err;
            }

            if (bakPresent) {
                WpsCheckpoint.Parse bp = WpsCheckpoint.decode(bak);
                if (bp.ok()) {
                    blob.write(KEY_PRIMARY, bp.cp.encode());
                    bp.note = "主副本缺失，已用备份恢复";
                    bp.error = "";
                    return bp;
                }
                WpsCheckpoint.Parse lp = fromLegacyKeys();
                if (lp != null) return lp;

                WpsCheckpoint.Parse err = new WpsCheckpoint.Parse();
                err.error = "断点不可用：没有主副本，备份也无法解析（备份：" + bp.error + "）";
                return err;
            }

            WpsCheckpoint.Parse lp = fromLegacyKeys();
            if (lp != null) return lp;

            out.error = "断点不可用：没有找到断点记录";
            return out;
        } catch (Throwable t) {
            WpsCheckpoint.Parse err = new WpsCheckpoint.Parse();
            err.error = "断点不可用：读取断点时出错（" + t + "）";
            return err;
        }
    }

    /**
     * 只在断点属于该目标+策略、且结构可用时返回 ok。
     *
     * <p>**不允许静默回退到「从头开始」而不给原因** —— 用户以为在续跑、
     * 实际在新目标上从头跑，是最容易误导的一种失败。
     */
    public WpsCheckpoint.Parse loadFor(String bssid, String ssid, int mode) {
        WpsCheckpoint.Parse p = load();
        if (!p.ok()) return p;
        WpsCheckpoint cp = p.cp;
        if (!cp.matches(bssid, ssid, mode)) {
            String why = cp.mismatchReason(bssid, ssid, mode, true);
            p.cp = null;
            p.error = why;
            return p;
        }
        String invalid = cp.invalidReason(true);
        if (!invalid.isEmpty()) {
            p.cp = null;
            p.error = invalid;
            return p;
        }
        return p;
    }

    /** 清断点（重置进度用）。**不得**触碰台账。返回是否清掉了至少一份。 */
    public boolean clear() {
        if (blob == null) return false;
        boolean any = false;
        try {
            if (blob.remove(KEY_PRIMARY)) any = true;
            if (blob.remove(KEY_BACKUP)) any = true;
            // 旧版键一并清掉：留着会在下一次 load() 被当成「可迁移的断点」复活
            if (blob.remove(KEY_LEGACY_BSSID)) any = true;
            if (blob.remove(KEY_LEGACY_PHASE)) any = true;
            if (blob.remove(KEY_LEGACY_CURSOR)) any = true;
            if (blob.remove(KEY_LEGACY_CURSOR1)) any = true;
            if (blob.remove(KEY_LEGACY_PREFIX)) any = true;
        } catch (Throwable t) {
            return any;
        }
        return any;
    }

    // ---------------- internals ----------------

    /**
     * 尝试从旧版 5 个键迁移。
     *
     * @return 有旧版痕迹时的 Parse（可能 ok=false 并带原因）；**完全没有旧版键**时返回 null，
     *         让调用方继续走「没有断点」的分支。
     */
    private WpsCheckpoint.Parse fromLegacyKeys() {
        String bssid = blob.read(KEY_LEGACY_BSSID);
        String phase = blob.read(KEY_LEGACY_PHASE);
        String cursor = blob.read(KEY_LEGACY_CURSOR);
        String cursor1 = blob.read(KEY_LEGACY_CURSOR1);
        String prefix = blob.read(KEY_LEGACY_PREFIX);

        boolean seen = !isBlank(bssid) || !isBlank(phase) || !isBlank(cursor)
                || !isBlank(cursor1) || !isBlank(prefix);
        if (!seen) return null;

        WpsCheckpoint.Parse p = WpsCheckpoint.fromLegacy(
                bssid, intOf(phase, 1), intOf(cursor, 0), intOf(cursor1, 0), prefix,
                System.currentTimeMillis());
        if (!p.ok()) return p;   // 例如缺 BSSID：把原因带出去

        blob.write(KEY_PRIMARY, p.cp.encode());
        // fromLegacy 已经给过「已从旧版断点迁移」，并可能附带「阶段值非法，已按阶段 1 处理」
        // 这类细节 —— 那是用户有权知道的事，不能在这里覆盖掉。
        if (p.note == null || p.note.isEmpty()) p.note = "已从旧版断点迁移";
        return p;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static int intOf(String s, int def) {
        if (s == null) return def;
        String v = s.trim();
        if (v.isEmpty()) return def;
        try {
            return Integer.parseInt(v);
        } catch (Throwable t) {
            return def;
        }
    }
}
