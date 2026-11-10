package io.github.wlmosv_png.wpaforge;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 破解模式与速度统计的持久化。
 *
 * 拆出来单独放，避免都堆在 L 里（L 只管文案）。
 */
public final class Prefs {

    private static final String NAME = "wpaforge_prefs";

    /**
     * 执行方式。**现在只有一种：Root**（`su + cmd wifi`）。
     *
     * Shizuku 与免 Root 通道已在 1.9.24 彻底移除 —— 前者要在每次关联时借
     * binder、后者走 `WifiNetworkSpecifier` 每条候选都要弹一次系统确认框，
     * 都撑不起逐条字典遍历。常量保留是为了队列 JSON 里 `crackMode` 字段的
     * 兼容：老任务存的值不是 0 也一律当 Root。
     */
    public static final int MODE_ROOT = 0;

    private static final String K_PIN = "app_pin_sha256";   // 空表示未设 PIN
    private static final String K_RATE = "avg_rate_ms";     // 平均每条耗时（毫秒）
    private static final String K_RATE_N = "rate_samples";  // 样本数
    private static final String K_PIN_BSSID = "pin_bssid";   // 连接锁定到选中的那个 AP
    private static final String K_MAC_ROTATE = "mac_rotate";  // 每次尝试换随机 MAC
    private static final String K_LAST_BEAT = "last_beat_ms";  // 引擎心跳（检测被系统挂起）
    /**
     * 用户线索（手机号 / 生日 / 拼音 / 门牌 / 自由词）编码串，见 SeedModel.encode()。
     * 存本机私有 SP，不外传；界面与日志一律走 SeedModel.masked()。
     */
    private static final String K_SEEDS = "target_seeds";
    /**
     * 派生候选预算上限，默认 120。
     *
     * 为什么是 120：实测单条 ~3.6 秒，120 条约 7 分钟；
     * 而本机 107 次真实命中里 104 次落在前 50 条内 —— 120 已经是两倍余量。
     * 旧版按 20000 上限生成、实际出到 1500 条，等于白跑一小时。
     */
    private static final String K_BUDGET = "derive_budget";
    public static final int DEFAULT_BUDGET = 120;
    /** 可调范围。下限 30：再少就压不到有用候选；上限 2000：超过这个数
     *  单目标派生已经没有新内容，只是把长尾铺长（旧版铺到 1500 条等于白跑一小时）。 */
    public static final int BUDGET_MIN = 30;
    public static final int BUDGET_MAX = 2000;
    /** 设置页给的档位（不是连续滑块 —— 逐条耗时以秒计，微调没有意义）。 */
    public static final int[] BUDGET_CHOICES = {60, 120, 300, 600, 1000};
    /** 「字典怎么选」说明是否已看过（只看一次，之后从弹窗里手动打开） */
    private static final String K_DICT_GUIDE = "dict_guide_seen";
    /** 当前目标（线索档案与已试集合都按它分档） */
    private static final String K_TARGET_KEY = "target_key";
    private static final String K_TARGET_LABEL = "target_label";
    /** 老版本的单槽线索是否已迁到"默认模板" */
    private static final String K_PROFILE_MIGRATED = "profile_migrated";
    /** 跨字典去重档位（0=保守 / 1=省时）—— 新建任务时的默认值 */
    private static final String K_SKIP_POLICY = "skip_policy_id";
    /** 是否自动连续执行下一本字典（计划模式） */
    private static final String K_PLAN_AUTO = "plan_auto_advance";

    private Prefs() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ---------------- 目标线索 ----------------

    /** 线索编码串，空串表示没有 */
    public static String seeds(Context ctx) {
        return sp(ctx).getString(K_SEEDS, "");
    }

    public static void setSeeds(Context ctx, String encoded) {
        sp(ctx).edit().putString(K_SEEDS, encoded == null ? "" : encoded).apply();
    }

    public static void clearSeeds(Context ctx) {
        sp(ctx).edit().remove(K_SEEDS).apply();
    }

    // ---------------- 当前目标 ----------------

    /**
     * 记住"现在在弄哪个网"。线索档案与已试集合都按这个键分档 ——
     * 换网络时它们各自就位，不会把上一家的信息套过来。
     */
    public static void setTarget(Context ctx, String bssid, String ssid) {
        String key = io.github.wlmosv_png.wpaforge.audit.TriedSet.targetKey(bssid, ssid);
        String label = (ssid == null || ssid.isEmpty() ? "?" : ssid)
                + (bssid == null || bssid.isEmpty() ? "" : " · " + bssid);
        sp(ctx).edit().putString(K_TARGET_KEY, key)
                .putString(K_TARGET_LABEL, label).apply();
    }

    /** 当前目标键；没选过目标时返回空串 */
    public static String targetKey(Context ctx) {
        return sp(ctx).getString(K_TARGET_KEY, "");
    }

    /** 当前目标显示名（SSID · BSSID） */
    public static String targetLabel(Context ctx) {
        return sp(ctx).getString(K_TARGET_LABEL, "");
    }

    // ---------------- 线索迁移与原始读取 ----------------

    /** 老版本的单槽线索原文（迁移用） */
    public static String seedsRaw(Context ctx) {
        return sp(ctx).getString(K_SEEDS, "");
    }

    public static boolean profileMigrated(Context ctx) {
        return sp(ctx).getBoolean(K_PROFILE_MIGRATED, false);
    }

    public static void setProfileMigrated(Context ctx) {
        sp(ctx).edit().putBoolean(K_PROFILE_MIGRATED, true).apply();
    }

    /** 跨字典去重档位（0=保守 1=省时）。默认 0 = 绝不跳过未确定候选。 */
    public static int skipPolicyId(Context ctx) {
        int v = sp(ctx).getInt(K_SKIP_POLICY, 0);
        return v == 1 ? 1 : 0;
    }

    public static void setSkipPolicyId(Context ctx, int id) {
        sp(ctx).edit().putInt(K_SKIP_POLICY, id == 1 ? 1 : 0).apply();
    }

    /** 多字典自动连续执行。默认关 = 与单字典模式行为一致。 */
    public static boolean planAutoAdvance(Context ctx) {
        return sp(ctx).getBoolean(K_PLAN_AUTO, false);
    }

    public static void setPlanAutoAdvance(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(K_PLAN_AUTO, on).apply();
    }

    public static boolean dictGuideSeen(Context ctx) {
        return sp(ctx).getBoolean(K_DICT_GUIDE, false);
    }

    public static void setDictGuideSeen(Context ctx) {
        sp(ctx).edit().putBoolean(K_DICT_GUIDE, true).apply();
    }

    /** 派生预算（条）。界面可调，最小 30 最大 2000。 */
    public static int deriveBudget(Context ctx) {
        return clampBudget(sp(ctx).getInt(K_BUDGET, DEFAULT_BUDGET));
    }

    /** 把任意值收进合法区间。**纯函数**，可以离线单测（见 SettingsPageAuditTest）。 */
    public static int clampBudget(int v) {
        if (v < BUDGET_MIN) return BUDGET_MIN;
        if (v > BUDGET_MAX) return BUDGET_MAX;
        return v;
    }

    public static void setDeriveBudget(Context ctx, int v) {
        if (ctx == null) return;
        sp(ctx).edit().putInt(K_BUDGET, clampBudget(v)).apply();
    }


    // ---------------- 引擎心跳 ----------------

    /**
     * 引擎每条候选都会调一次，用来判断「切到后台期间是不是被系统挂起过」。
     * 回到前台时如果发现心跳间隔远大于正常值，就说明中间被冻过/卡过。
     */
    public static void beat(Context ctx, long nowMs) {
        sp(ctx).edit().putLong(K_LAST_BEAT, nowMs).apply();
    }

    public static long lastBeat(Context ctx) {
        return sp(ctx).getLong(K_LAST_BEAT, 0L);
    }

    // ---------------- 运行序号基线（D2） ----------------

    private static final String K_RUN_ORDINAL = "run_ordinal_max";

    /**
     * 上次进程用到的最大 runOrdinal（D2）。
     *
     * <h3>为什么必须落盘</h3>
     * runId 的三层唯一性里，bootId 读不到时会失效；而断点续跑队列是
     * **跨进程持久化**的。若每次进程都从 ordinal 0 起，同一毫秒的两次启动
     * 会算出逐字节相同的 runId，旧 Attempt 的日志窗口标记就会与新 Attempt
     * 互相匹配（证据串窗）。
     *
     * <p>把「已用到哪」落盘后，新进程从 base+1 起，唯一性不再依赖
     * bootId 可读性，也不再依赖时钟精度。读不到时返回 0（行为同旧版）。
     */
    public static int runOrdinalBase(Context ctx) {
        return (int) Math.max(0L, sp(ctx).getLong(K_RUN_ORDINAL, 0L));
    }

    /** 记录「已用到哪」：单调递增，绝不回退。 */
    public static void bumpRunOrdinal(Context ctx, int used) {
        if (used <= 0) return;
        SharedPreferences p = sp(ctx);
        if (used > p.getLong(K_RUN_ORDINAL, 0L)) {
            p.edit().putLong(K_RUN_ORDINAL, used).apply();
        }
    }

    // ---------------- 在线连接选项 ----------------

    /**
     * 是否把连接锁定到目标那一个 AP（命令行加 -b <bssid>）。
     *
     * 同名 SSID 有多个 AP 时，不锁定就等于把选择权交给系统 —— 它可能挑到
     * 信号最差的那个。默认关闭，保持与旧版本一致的行为。
     */
    public static boolean pinBssid(Context ctx) {
        return sp(ctx).getBoolean(K_PIN_BSSID, false);
    }

    public static void setPinBssid(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean(K_PIN_BSSID, v).apply();
    }

    /**
     * 是否每次尝试都用新的随机 MAC（命令行加 -r non_persistent）。
     *
     * 用于缓解目标 AP 侧的限流/黑名单。默认关闭：目标若做了 MAC 白名单，
     * 开着反而连不上。
     */
    public static boolean macRotate(Context ctx) {
        return sp(ctx).getBoolean(K_MAC_ROTATE, false);
    }

    public static void setMacRotate(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean(K_MAC_ROTATE, v).apply();
    }

    // ---------------- 破解模式 ----------------

    public static int mode(Context ctx) {
        // 只有 Root 一种执行方式。
        // 老版本可能存过 1（免 Root）/ 2（Shizuku），一律当 Root ——
        // 绝不能因为一个旧值就落到一条已经删掉的通道上，那会表现为「点开始没反应」。
        //
        // 2026-11-10：旧的 `crack_mode` 键与 `modeIsManual()` 已删除 ——
        // 上面这条 return 是唯一的真相来源，再留一个「用户是否手动指定过」
        // 的读数只会是永远 false 的死代码。
        return MODE_ROOT;
    }

    // ---------------- 恢复默认设置 ----------------

    /**
     * 「恢复默认设置」会重置的键 —— **这是一份契约，不是随手写的清单**。
     *
     * <h3>为什么单独抽出来</h3>
     * 「恢复默认」最容易犯的错是**顺手把用户数据也清了**（目标线索、当前目标、
     * 破解记录）。所以这里只列**纯偏好**，并且把「不能碰的」也在注释里点名，
     * 让测试能钉住两头（{@code SettingsPageAuditTest}）。
     *
     * <h3>不在此列（有意保留）</h3>
     * · {@code app_pin_sha256}（K_PIN）—— 安全项，恢复默认不该顺手把锁打开；
     * · {@code target_seeds} / {@code target_key} / {@code target_label} —— 用户数据；
     * · {@code run_ordinal_max} —— runId 唯一性的不变式，回退会撞号；
     * · {@code last_beat_ms} —— 运行期心跳，不是设置；
     * · {@code avg_rate_ms} / {@code rate_samples} —— **实测**统计，不是用户设置。
     *
     * <p>{@code dict_guide_seen} 在列：重置它，「字典怎么选」的首次说明会重新
     * 出现，符合「恢复默认」的预期。
     */
    public static final String[] RESET_KEYS = {
            K_PIN_BSSID, K_MAC_ROTATE, K_BUDGET, K_SKIP_POLICY, K_PLAN_AUTO, K_DICT_GUIDE,
    };

    /**
     * 恢复本类的用户偏好为出厂值。
     *
     * <p>实现方式是 **remove 而不是写默认值** —— 这样「默认值改了」以后
     * 老用户不会因为库里有旧默认值而继续用旧的。写完返回 false 只可能是
     * 上下文为空。
     *
     * <p>用户的字典目录（{@code picked_dict_dir}）与语言（{@code language}）
     * 由各自的模块负责，调用方一并处理。
     */
    public static boolean resetToDefaults(Context ctx) {
        if (ctx == null) return false;
        try {
            SharedPreferences.Editor e = sp(ctx).edit();
            for (String k : RESET_KEYS) e.remove(k);
            return e.commit();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 应用 PIN 锁 ----------------

    /** 已设置的 PIN 的 SHA-256；空串 = 未设置 */
    public static String pinHash(Context ctx) {
        return sp(ctx).getString(K_PIN, "");
    }

    public static boolean pinEnabled(Context ctx) {
        return !pinHash(ctx).isEmpty();
    }

    /**
     * 设置或修改 PIN（明文进来，存 SHA-256）。
     *
     * <p>返回**是否确认落盘**。这里刻意用 {@code commit()} 而不是 {@code apply()}：
     * PIN 是设置页里唯一的安全项，「提示成功但其实没写」的代价是用户以为锁上了
     * 而实际没锁。其余偏好项保持 {@code apply()} —— 它们是可以重复设置的廉价写入。
     */
    public static boolean setPin(Context ctx, String plain) {
        if (ctx == null) return false;
        String h = plain == null ? "" : sha256(plain.trim());
        try {
            return sp(ctx).edit().putString(K_PIN, h).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 清除 PIN。返回是否确认落盘（理由同 {@link #setPin}）。 */
    public static boolean clearPin(Context ctx) {
        if (ctx == null) return false;
        try {
            return sp(ctx).edit().remove(K_PIN).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 校验 PIN */
    public static boolean checkPin(Context ctx, String plain) {
        String h = pinHash(ctx);
        return h != null && !h.isEmpty() && h.equals(sha256(plain == null ? "" : plain.trim()));
    }

    private static String sha256(String s) {
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static String modeLabel(Context ctx) {
        return L.s("Root 模式", "Root mode");
    }

    // ---------------- 速度统计 ----------------

    /**
     * 记录本次实测速度，滚动平均。
     * 用于「开始前预估耗时」，比固定假设 1 秒/条准。
     */
    public static void recordRate(Context ctx, long totalMs, int count) {
        if (count <= 0 || totalMs <= 0) return;
        long perItem = totalMs / count;
        // 过滤异常样本。
        //
        // 上限从 60000 收到 20000：真机实测单条成本在 0.6~5 秒量级
        // （forget+list 209ms + status 100ms + connect 87ms + logcat 239ms，
        // 密码错时系统再花几秒放弃）。超过 20 秒的样本基本是
        // 「等满超时兜底结束」的那几条，不代表真实速度，不该进平均。
        // 之前 60000 的上限把这类样本全放进来了，均值被抬到 8 秒以上，
        // 用户看到「每条约 8.8 秒」与体感不符。
        if (perItem < 200 || perItem > 20000) return;

        SharedPreferences p = sp(ctx);
        long old = p.getLong(K_RATE, 0);
        int n = p.getInt(K_RATE_N, 0);
        long merged;
        if (old <= 0 || n <= 0) {
            merged = perItem;
        } else {
            // 指数滑动平均，新旧各半。
            // 原来 0.6/0.4 偏向旧值：一个偏高的历史值要好几轮才被拉回来。
            // 改成 0.5/0.5，让真值更快纠偏。
            merged = (long) (old * 0.5 + perItem * 0.5);
        }
        p.edit().putLong(K_RATE, merged).putInt(K_RATE_N, Math.min(n + 1, 50)).apply();
    }

    /** 平均每条耗时（毫秒）。没有样本时给 Root 通道的默认值。 */
    public static long avgMsPerItem(Context ctx) {
        long v = sp(ctx).getLong(K_RATE, 0);
        if (v > 0) return v;
        return 1000L;
    }

    public static String avgRateLabel(Context ctx) {
        long ms = avgMsPerItem(ctx);
        return String.format(java.util.Locale.US, "%.1f", 1000.0 / ms);
    }

    /**
     * 换算成「秒/条」。
     *
     * <p>为什么设置页改用这个口径：同一张卡上既写「实测约 1 秒/条」又写
     * 「当前实测速度: 0.1 条/秒」（= 10 秒/条），两个数字互相打架，
     * 用户会当成 bug。而「秒/条」才是用户真正在算的东西（120 条要多久）。
     */
    public static String avgSecLabel(Context ctx) {
        long ms = avgMsPerItem(ctx);
        return String.format(java.util.Locale.US, "%.1f", ms / 1000.0);
    }

    /** 依据平均速度估算一批候选要跑多久 */
    public static String estimate(Context ctx, int count) {
        long ms = avgMsPerItem(ctx) * count;
        long sec = ms / 1000;
        if (sec < 60) return L.f("%d 秒", "%ds", sec);
        if (sec < 3600) return L.f("%d 分钟", "%d min", sec / 60);
        if (sec < 86400) return L.f("%d 小时 %d 分钟", "%dh %dm", sec / 3600, (sec % 3600) / 60);
        return L.f("%d 天 %d 小时", "%dd %dh", sec / 86400, (sec % 86400) / 3600);
    }

    /** 有没有实测样本（决定文案是"预估"还是"实测"） */
    public static boolean hasRateSample(Context ctx) {
        return sp(ctx).getLong(K_RATE, 0) > 0;
    }
}
