package io.github.wlmosv_png.wpaforge.wps;

/**
 * WPS 任务台账：**独立于** {@code HistStore} 的密码记录模型。
 *
 * <h3>为什么不并进 HistStore<\/h3>
 * 密码记录回答的问题是「我拿到过哪些网络的密码」；台账回答的是
 * 「我跑过哪些任务、跑到哪儿、有没有命中」。两件事的生命周期完全不同：
 * <ul>
 *   <li>**重置进度**要清断点，但**一条命中历史都不能少** —— 命中是用户的收获，
 *       断点只是运行状态；<\/li>
 *   <li>密码记录会去重、会按 SSID 聚合；台账一条任务就是一条，**新任务不得覆盖旧任务**
 *       （同一个路由器今天跑一次、下周再跑一次，是两条独立记录）。<\/li>
 * <\/ul>
 * 把两者塞进一张表，任何一个「清理」动作都会顺手删掉另一种数据。
 *
 * <h3>{@code doneCount} 的口径<\/h3>
 * 记的是**实际完成**的候选数（跑完并拿到结论），不是「发出去几次」。
 * 请求数与完成数在崩溃/超时场景下会分叉，用请求数会让 ETA 虚高、断点对不上。
 *
 * <h3>命中是不可丢的事实<\/h3>
 * {@link #hits} / {@link #latestHit} 只看「有没有 hitPin」，
 * 与 status、doneCount、后续任何 upsert 无关 —— 命中之后任务又被更新十次，
 * {@code hitPin} 也还在（{@link #upsert} 按 taskId 覆盖整条，不合并字段，
 * 所以调用方更新时**必须**把 hitPin 一起带上）。
 */
public final class WpsLedger {

    public static final int SCHEMA = 1;

    /** 单行文本的标识头。 */
    public static final String HEADER = "WPS_TASK";

    public static final String ST_RUNNING = "RUNNING";
    public static final String ST_PAUSED = "PAUSED";
    public static final String ST_DONE = "DONE";        // 自然结束（跑完候选）
    public static final String ST_STOPPED = "STOPPED";  // 用户停止
    public static final String ST_FAILED = "FAILED";    // 异常/进程死亡

    /** 一条 WPS 任务。 */
    public static final class Task {
        public int schema = SCHEMA;
        public String taskId = "";
        public long createdAtMs, startedAtMs, endedAtMs, updatedAtMs;
        public String bssid = "", ssid = "";
        public int mode = 0;
        public String status = ST_RUNNING;
        /** **实际完成**的候选数（不是请求数）。 */
        public int doneCount = 0;
        public int phase = 1;
        public String lastPin = "", lastResult = "";
        public String pauseReason = "", endReason = "";
        /** 命中 PIN（空 = 没命中）。 */
        public String hitPin = "";
        /** {@link WpsHitJudge} 的结论。 */
        public String hitVerdict = "";
        /** 证据明细（{@link WpsEngine#evidenceSummary} 形态）。 */
        public String evidence = "";
        /** 命中时是否真的联网（独立事实）。 */
        public boolean hitOnline = false;
        /** 连接验证结果（可为空 = 未验证）。 */
        public String connVerify = "";

        /** 单行文本（值用 {@link WpsHelper#enc} 编码；不得含换行）。 */
        public String encode() {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append(HEADER).append(" v=").append(SCHEMA);
                sb.append(" schema=").append(SCHEMA);
                sb.append(" taskId=").append(WpsHelper.enc(taskId));
                sb.append(" createdAtMs=").append(createdAtMs);
                sb.append(" startedAtMs=").append(startedAtMs);
                sb.append(" endedAtMs=").append(endedAtMs);
                sb.append(" updatedAtMs=").append(updatedAtMs);
                sb.append(" bssid=").append(WpsHelper.enc(bssid));
                sb.append(" ssid=").append(WpsHelper.enc(ssid));
                sb.append(" mode=").append(mode);
                sb.append(" status=").append(WpsHelper.enc(status));
                sb.append(" doneCount=").append(doneCount);
                sb.append(" phase=").append(phase);
                sb.append(" lastPin=").append(WpsHelper.enc(lastPin));
                sb.append(" lastResult=").append(WpsHelper.enc(lastResult));
                sb.append(" pauseReason=").append(WpsHelper.enc(pauseReason));
                sb.append(" endReason=").append(WpsHelper.enc(endReason));
                sb.append(" hitPin=").append(WpsHelper.enc(hitPin));
                sb.append(" hitVerdict=").append(WpsHelper.enc(hitVerdict));
                sb.append(" evidence=").append(WpsHelper.enc(evidence));
                sb.append(" hitOnline=").append(hitOnline ? 1 : 0);
                sb.append(" connVerify=").append(WpsHelper.enc(connVerify));
                return sb.toString();
            } catch (Throwable t) {
                return "";
            }
        }

        /** 行 → Task。失败返回 null，绝不抛。 */
        public static Task decode(String line) {
            try {
                if (line == null) return null;
                String s = line.trim();
                if (s.isEmpty()) return null;
                String[] tk = s.split("\\s+");
                if (tk.length < 2 || !HEADER.equals(tk[0])) return null;

                int version = -1;
                for (String t : tk) {
                    if (t.startsWith("v=")) {
                        Integer v = tryInt(t.substring(2));
                        if (v != null) version = v;
                    }
                }
                if (version < 1) return null;

                Task t = new Task();
                // 版本比本版新时，仍按已知字段读、忽略未知键 ——
                // 台账是历史记录，读不认识的键就整条丢弃会让用户凭空少一条历史。
                t.schema = version <= SCHEMA ? version : SCHEMA;
                for (int i = 1; i < tk.length; i++) {
                    int eq = tk[i].indexOf('=');
                    if (eq <= 0) continue;
                    String k = tk[i].substring(0, eq);
                    String v = WpsHelper.dec(tk[i].substring(eq + 1));
                    switch (k) {
                        case "schema":
                            break;
                        case "taskId":
                            t.taskId = v;
                            break;
                        case "createdAtMs":
                            t.createdAtMs = longOr(v, 0L);
                            break;
                        case "startedAtMs":
                            t.startedAtMs = longOr(v, 0L);
                            break;
                        case "endedAtMs":
                            t.endedAtMs = longOr(v, 0L);
                            break;
                        case "updatedAtMs":
                            t.updatedAtMs = longOr(v, 0L);
                            break;
                        case "bssid":
                            t.bssid = WpsCheckpoint.normBssid(v);
                            break;
                        case "ssid":
                            t.ssid = v;
                            break;
                        case "mode":
                            t.mode = intOr(v, 0);
                            break;
                        case "status":
                            t.status = v;
                            break;
                        case "doneCount":
                            t.doneCount = intOr(v, 0);
                            break;
                        case "phase":
                            t.phase = intOr(v, 1);
                            break;
                        case "lastPin":
                            t.lastPin = v;
                            break;
                        case "lastResult":
                            t.lastResult = v;
                            break;
                        case "pauseReason":
                            t.pauseReason = v;
                            break;
                        case "endReason":
                            t.endReason = v;
                            break;
                        case "hitPin":
                            t.hitPin = v;
                            break;
                        case "hitVerdict":
                            t.hitVerdict = v;
                            break;
                        case "evidence":
                            t.evidence = v;
                            break;
                        case "hitOnline":
                            t.hitOnline = "1".equals(v) || "true".equalsIgnoreCase(v);
                            break;
                        case "connVerify":
                            t.connVerify = v;
                            break;
                        default:
                            break;
                    }
                }
                return t;
            } catch (Throwable t) {
                return null;
            }
        }

        /** 有没有命中（只看 hitPin，与状态/计数无关）。 */
        public boolean hasHit() {
            return hitPin != null && !hitPin.trim().isEmpty();
        }

        /** 排序用时间戳：结束时间 → 更新时间 → 创建时间。 */
        long stamp() {
            if (endedAtMs > 0) return endedAtMs;
            if (updatedAtMs > 0) return updatedAtMs;
            return createdAtMs;
        }
    }

    private WpsLedger() {
    }

    /** 稳定可测的 taskId：{@code wps-<bssid 去掉冒号的后 4 位>-<nowMs>}。 */
    public static String newTaskId(String bssid, long nowMs) {
        String b = WpsCheckpoint.normBssid(bssid);
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) hex.append(c);
        }
        String tail = hex.length() <= 4 ? hex.toString()
                : hex.substring(hex.length() - 4);
        return "wps-" + tail + "-" + nowMs;
    }

    public static Task start(String taskId, String bssid, String ssid, int mode, long nowMs) {
        Task t = new Task();
        t.schema = SCHEMA;
        t.taskId = taskId == null ? "" : taskId;
        t.bssid = WpsCheckpoint.normBssid(bssid);
        t.ssid = ssid == null ? "" : ssid;
        t.mode = mode;
        t.status = ST_RUNNING;
        t.phase = 1;
        t.doneCount = 0;
        t.createdAtMs = nowMs;
        t.startedAtMs = nowMs;
        t.updatedAtMs = nowMs;
        return t;
    }

    /** 按 taskId 覆盖同一条，其余历史原样保留（新任务不得覆盖旧任务）。 */
    public static java.util.List<Task> upsert(java.util.List<Task> all, Task t) {
        java.util.List<Task> out = new java.util.ArrayList<>();
        if (all != null) {
            for (Task e : all) {
                if (e != null) out.add(e);
            }
        }
        if (t == null) return out;
        String id = t.taskId == null ? "" : t.taskId;
        if (!id.isEmpty()) {
            for (int i = 0; i < out.size(); i++) {
                Task e = out.get(i);
                if (id.equals(e.taskId)) {
                    out.set(i, t);
                    return out;
                }
            }
        }
        out.add(t);
        return out;
    }

    public static Task find(java.util.List<Task> all, String taskId) {
        if (all == null || taskId == null) return null;
        for (Task t : all) {
            if (t != null && taskId.equals(t.taskId)) return t;
        }
        return null;
    }

    /** 历史命中：所有带命中 PIN 的任务（新→旧）。 */
    public static java.util.List<Task> hits(java.util.List<Task> all) {
        java.util.List<Task> out = new java.util.ArrayList<>();
        if (all == null) return out;
        for (Task t : all) {
            if (t != null && t.hasHit()) out.add(t);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Task>() {
            @Override
            public int compare(Task a, Task b) {
                long x = a.stamp(), y = b.stamp();
                if (x == y) return 0;
                return x > y ? -1 : 1;   // 新 → 旧
            }
        });
        return out;
    }

    /** 最近一次命中（没有则 null）——**跨任务**，重置进度不应影响它。 */
    public static Task latestHit(java.util.List<Task> all) {
        java.util.List<Task> h = hits(all);
        return h.isEmpty() ? null : h.get(0);
    }

    /** 台账概览一行（给 UI 用）：任务数 / 命中数 / 最近一条状态。 */
    public static String summary(java.util.List<Task> all, boolean zh) {
        try {
            int n = 0;
            Task last = null;
            if (all != null) {
                for (Task t : all) {
                    if (t == null) continue;
                    n++;
                    if (last == null || t.stamp() > last.stamp()) last = t;
                }
            }
            int h = hits(all).size();
            if (n == 0) {
                return zh ? "暂无 WPS 任务记录" : "no WPS task records";
            }
            String target = last == null ? "" : WpsCheckpoint.normBssid(last.bssid);
            String tag = target.isEmpty() ? "" : (zh ? "（" + target + "）" : " (" + target + ")");
            String st = last == null ? "" : str(last.status);
            if (zh) {
                return "任务 " + n + " 条 · 命中 " + h + " 条 · 最近：" + st + tag;
            }
            return "tasks " + n + " · hits " + h + " · latest: " + st + tag;
        } catch (Throwable t) {
            return zh ? "台账概览不可用" : "ledger summary unavailable";
        }
    }

    // ---------------- internals ----------------

    private static String str(String v) {
        return v == null ? "" : v;
    }

    private static Integer tryInt(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        try {
            return Integer.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intOr(String s, int def) {
        Integer v = tryInt(s);
        return v == null ? def : v;
    }

    private static long longOr(String s, long def) {
        if (s == null) return def;
        String v = s.trim();
        if (v.isEmpty()) return def;
        try {
            return Long.parseLong(v);
        } catch (Throwable t) {
            return def;
        }
    }
}
