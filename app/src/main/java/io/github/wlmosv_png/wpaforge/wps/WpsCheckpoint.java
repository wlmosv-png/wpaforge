package io.github.wlmosv_png.wpaforge.wps;

/**
 * 拆半攻击的断点（纯数据 + 编解码，<b>永不抛异常</b>）。
 *
 * <h3>为什么需要断点</h3>
 * 拆半攻击要跑 10000（阶段 1）+ 1000（阶段 2）次尝试，真机上一轮几小时到几天。
 * 中途 App 被杀、手机重启、用户切走，进度一旦只存在内存里就等于全丢 ——
 * 用户回来看到「已完成 0 条」，只能重跑。断点就是把「当前阶段**实际完成**的候选数」
 * 落盘，下次启动从这里接着试。
 *
 * <h3>两个游标不是一回事</h3>
 * <ul>
 *   <li>{@link #cursor}：**当前阶段**已完成的候选数。阶段切换时归零重新计；<\/li>
 *   <li>{@link #cursor1}：阶段 1 的断点。阶段 2 期间它必须保留 ——
 *       阶段 2 跑完（或用户回退）要能回到阶段 1 的进度继续，而不是从头找前 4 位。<\/li>
 * </ul>
 *
 * <h3>只认「完成」不认「请求」<\/h3>
 * {@code cursor} 记的是**跑完并拿到结论**的候选数，不是「发出去了几次」。
 * 用请求数记的话，一次崩溃会让最后那条被重复试（无害）或漏试（有害：
 * 如果它恰好是正确答案，整轮就白跑了）。
 *
 * <h3>编码形态<\/h3>
 * <pre>
 *   WPS_CP v=1
 *   taskId=wps-2233-1699999999999
 *   bssid=a4%3A2b%3Ab0%3A11%3A22%3A33
 *   ssid=My+Home+AP
 *   ...
 * <\/pre>
 * 单条文本，值一律走 {@link WpsHelper#enc}（空格 → {@code +}），
 * 所以**值里不可能出现空白与换行**，按行按 {@code =} 切都安全。
 * SSID 带空格、带 {@code =}、带中文都不会破坏结构。
 *
 * <p>{@link #decode} 的任何一步异常都被吃掉转成 {@link Parse#error} 文本：
 * 断点文件是「用户可见的外部输入」，脏数据不能让 App 崩在启动路径上。
 */
public final class WpsCheckpoint {

    /** 当前结构版本。写入时进首行 {@code WPS_CP v=}。 */
    public static final int SCHEMA = 1;

    /** 首行标识。 */
    public static final String HEADER = "WPS_CP";

    /** 无法识别（没有版本行 / 版本号不是数字）。 */
    public static final String E_FORMAT = "断点格式无法识别";

    public int schema = SCHEMA;
    /** 与台账 {@code Task.taskId} 对应。 */
    public String taskId = "";
    /** 目标 BSSID（小写，带冒号）。 */
    public String bssid = "";
    /** 目标 SSID（可能为空 = 未知）。 */
    public String ssid = "";
    /** 策略档位；拆半 = 3。 */
    public int mode = 3;
    /** 拆半阶段：1 或 2。 */
    public int phase = 1;
    /** 当前阶段**实际完成**的候选数（不是请求数）。 */
    public int cursor = 0;
    /** 阶段 1 的断点（跨阶段切换保留）。 */
    public int cursor1 = 0;
    /** 阶段 2 已确认的前 4 位。 */
    public String prefix = "";
    public long updatedAtMs = 0;

    public WpsCheckpoint() {
    }

    /** 深拷贝（调用方拿去改状态时不得影响原对象）。 */
    public WpsCheckpoint copy() {
        WpsCheckpoint c = new WpsCheckpoint();
        c.schema = schema;
        c.taskId = taskId;
        c.bssid = bssid;
        c.ssid = ssid;
        c.mode = mode;
        c.phase = phase;
        c.cursor = cursor;
        c.cursor1 = cursor1;
        c.prefix = prefix;
        c.updatedAtMs = updatedAtMs;
        return c;
    }

    /** 单条文本（可落盘）：首行 "WPS_CP v=1"，其后每行 "key=value"，值用 {@link WpsHelper#enc} 编码。 */
    public String encode() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(HEADER).append(" v=").append(SCHEMA).append('\n');
            sb.append("schema=").append(SCHEMA).append('\n');
            sb.append("taskId=").append(WpsHelper.enc(taskId)).append('\n');
            sb.append("bssid=").append(WpsHelper.enc(bssid)).append('\n');
            sb.append("ssid=").append(WpsHelper.enc(ssid)).append('\n');
            sb.append("mode=").append(mode).append('\n');
            sb.append("phase=").append(phase).append('\n');
            sb.append("cursor=").append(cursor).append('\n');
            sb.append("cursor1=").append(cursor1).append('\n');
            sb.append("prefix=").append(WpsHelper.enc(prefix)).append('\n');
            sb.append("updatedAtMs=").append(updatedAtMs).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            // 结构数据不可能抛；真抛了也保证返回一个「可识别的头」，让上层按损坏处理而不是拿到 null。
            return HEADER + " v=" + SCHEMA + "\n";
        }
    }

    /** {@link #decode} 的结果包。 */
    public static final class Parse {
        /** null = 不可用。 */
        public WpsCheckpoint cp;
        /** 失败原因（人话，中文）；成功时为空串。 */
        public String error = "";
        /** 信息性说明（例如「已从旧版断点迁移」）。 */
        public String note = "";
        /** 由旧版 5 个 key 迁移而来。 */
        public boolean fromLegacy;

        public boolean ok() {
            return cp != null;
        }
    }

    /**
     * 解码；任何异常都必须被吃掉并转成 error 文本，绝不抛。
     *
     * <p>规则：
     * <ul>
     *   <li>无 {@code v} 行或版本号解析失败 → {@link #E_FORMAT}；<\/li>
     *   <li>{@code v} 大于 {@link #SCHEMA} → 明确说「更新的版本写入的」，不猜结构；<\/li>
     *   <li>{@code v} 小于等于 {@link #SCHEMA} → 按当前结构读，缺字段用默认值；<\/li>
     *   <li>{@code phase} 非 1/2 → 当 1 并在 {@link Parse#note} 说明；<\/li>
     *   <li>{@code cursor}/{@code cursor1} 为负 → 夹到 0 并在 note 说明。<\/li>
     * <\/ul>
     */
    public static Parse decode(String raw) {
        Parse out = new Parse();
        try {
            if (raw == null) {
                out.error = E_FORMAT;
                return out;
            }
            String[] lines = raw.split("\n", -1);

            int headerIdx = -1;
            int version = -1;
            boolean versionBad = false;
            for (int i = 0; i < lines.length; i++) {
                String ln = lines[i] == null ? "" : lines[i].trim();
                if (ln.isEmpty()) continue;
                String[] tk = ln.split("\\s+");
                if (tk.length > 0 && HEADER.equals(tk[0])) {
                    headerIdx = i;
                    for (String t : tk) {
                        if (t.startsWith("v=")) {
                            Integer v = tryInt(t.substring(2));
                            if (v == null) versionBad = true;
                            else version = v;
                        }
                    }
                    break;
                }
            }
            if (headerIdx < 0) {
                out.error = E_FORMAT;
                return out;
            }
            if (versionBad || version < 0) {
                out.error = E_FORMAT;
                return out;
            }
            if (version > SCHEMA) {
                out.error = "断点由更新的版本写入（v=" + version + "），本版本不识别";
                return out;
            }

            WpsCheckpoint cp = new WpsCheckpoint();
            cp.schema = version;
            StringBuilder notes = new StringBuilder();

            for (int i = 0; i < lines.length; i++) {
                if (i == headerIdx) continue;
                String ln = lines[i] == null ? "" : lines[i].trim();
                if (ln.isEmpty()) continue;
                int eq = ln.indexOf('=');
                if (eq <= 0) continue;
                String k = ln.substring(0, eq);
                String v = WpsHelper.dec(ln.substring(eq + 1));
                switch (k) {
                    case "schema":
                        // 结构版本以首行为准，字段只是冗余记录
                        break;
                    case "taskId":
                        cp.taskId = v;
                        break;
                    case "bssid":
                        cp.bssid = normBssid(v);
                        break;
                    case "ssid":
                        cp.ssid = v;
                        break;
                    case "mode":
                        cp.mode = intOr(v, cp.mode);
                        break;
                    case "phase": {
                        Integer iv = tryInt(v);
                        if (iv == null) {
                            cp.phase = 1;
                            notes.append("阶段值无法解析，已按阶段 1 处理; ");
                        } else if (iv != 1 && iv != 2) {
                            cp.phase = 1;
                            notes.append("阶段值非法（").append(iv).append("），已按阶段 1 处理; ");
                        } else {
                            cp.phase = iv;
                        }
                        break;
                    }
                    case "cursor": {
                        Integer iv = tryInt(v);
                        if (iv == null) {
                            cp.cursor = 0;
                            notes.append("游标无法解析，已归零; ");
                        } else if (iv < 0) {
                            cp.cursor = 0;
                            notes.append("游标为负（").append(iv).append("），已归零; ");
                        } else {
                            cp.cursor = iv;
                        }
                        break;
                    }
                    case "cursor1": {
                        Integer iv = tryInt(v);
                        if (iv == null) {
                            cp.cursor1 = 0;
                            notes.append("阶段 1 游标无法解析，已归零; ");
                        } else if (iv < 0) {
                            cp.cursor1 = 0;
                            notes.append("阶段 1 游标为负（").append(iv).append("），已归零; ");
                        } else {
                            cp.cursor1 = iv;
                        }
                        break;
                    }
                    case "prefix":
                        cp.prefix = v;
                        break;
                    case "updatedAtMs":
                        cp.updatedAtMs = longOr(v, cp.updatedAtMs);
                        break;
                    default:
                        // 未知字段一律忽略：向前兼容，不为多出来的键报错
                        break;
                }
            }

            String note = trimNote(notes.toString());
            out.cp = cp;
            out.note = note;
            out.error = "";
            return out;
        } catch (Throwable t) {
            Parse q = new Parse();
            q.error = E_FORMAT;
            return q;
        }
    }

    /**
     * 旧版（SharedPreferences 的 5 个 key）→ 新结构。
     *
     * <p>{@code bssid} 为空 → {@code ok=false} + error：旧版没有 BSSID 时
     * 无从判断这段进度属于哪个目标，沿用会打到邻居家的路由器上。
     */
    public static Parse fromLegacy(String bssid, int phase, int cursor, int cursor1,
                                  String prefix, long nowMs) {
        Parse out = new Parse();
        try {
            String b = normBssid(bssid);
            if (b.isEmpty()) {
                out.error = "旧版断点没有记录目标 BSSID，无法确定这段进度属于哪个目标";
                return out;
            }
            WpsCheckpoint cp = new WpsCheckpoint();
            cp.schema = SCHEMA;
            cp.taskId = "";
            cp.bssid = b;
            cp.ssid = "";
            cp.mode = 3;   // 旧版只有拆半一种模式

            StringBuilder notes = new StringBuilder();
            if (phase != 1 && phase != 2) {
                cp.phase = 1;
                notes.append("旧版阶段值非法（").append(phase).append("），已按阶段 1 处理; ");
            } else {
                cp.phase = phase;
            }
            if (cursor < 0) {
                cp.cursor = 0;
                notes.append("旧版游标为负（").append(cursor).append("），已归零; ");
            } else {
                cp.cursor = cursor;
            }
            if (cursor1 < 0) {
                cp.cursor1 = 0;
                notes.append("旧版阶段 1 游标为负（").append(cursor1).append("），已归零; ");
            } else {
                cp.cursor1 = cursor1;
            }
            cp.prefix = prefix == null ? "" : prefix;
            cp.updatedAtMs = nowMs;

            out.cp = cp;
            out.fromLegacy = true;
            String extra = trimNote(notes.toString());
            out.note = "已从旧版断点迁移" + (extra.isEmpty() ? "" : "（" + extra + "）");
            return out;
        } catch (Throwable t) {
            Parse q = new Parse();
            q.error = "旧版断点无法迁移";
            return q;
        }
    }

    /**
     * 断点是否属于这个目标与策略。
     *
     * <p>归属判据：BSSID 必须一致（忽略大小写）；{@code mode} 必须一致；
     * SSID 两侧都非空且不等时才拒绝（目标 SSID 未知时只用 BSSID + mode 判）。
     */
    public boolean matches(String targetBssid, String targetSsid, int mode) {
        try {
            String b = normBssid(bssid);
            if (b.isEmpty()) return false;
            String tb = normBssid(targetBssid);
            if (tb.isEmpty()) return false;
            if (!b.equals(tb)) return false;
            if (this.mode != mode) return false;
            String ts = targetSsid == null ? "" : targetSsid.trim();
            String cs = ssid == null ? "" : ssid.trim();
            if (ts.isEmpty()) return true;
            if (cs.isEmpty()) return true;
            return cs.equals(ts);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 不匹配时的人话原因；匹配时返回空串。 */
    public String mismatchReason(String targetBssid, String targetSsid, int mode, boolean zh) {
        try {
            if (matches(targetBssid, targetSsid, mode)) return "";
            String b = normBssid(bssid);
            String tb = normBssid(targetBssid);
            if (b.isEmpty()) {
                return zh ? "断点没有记录目标 BSSID，无法确认归属"
                          : "checkpoint has no target BSSID";
            }
            if (tb.isEmpty()) {
                return zh ? "当前目标没有 BSSID，无法与断点比对"
                          : "current target has no BSSID to compare";
            }
            if (!b.equals(tb)) {
                return zh ? ("断点属于另一个目标（" + tagOf(b) + "），当前目标是 " + tagOf(tb))
                          : ("checkpoint belongs to another target (" + tagOf(b)
                             + "), current target is " + tagOf(tb));
            }
            if (this.mode != mode) {
                return zh ? ("断点属于另一个策略档位（M" + this.mode + "），当前是 M" + mode)
                          : ("checkpoint belongs to another mode (M" + this.mode
                             + "), current mode is M" + mode);
            }
            return zh ? ("断点属于另一个目标（SSID " + str(ssid) + "），当前目标是 " + str(targetSsid))
                      : ("checkpoint belongs to another SSID (" + str(ssid) + "), current is "
                         + str(targetSsid));
        } catch (Throwable t) {
            return zh ? "断点与当前目标不匹配" : "checkpoint does not match current target";
        }
    }

    /** 阶段 2 必须有 4 位前缀，否则不可续跑（返回原因；可续跑返回空串）。 */
    public String invalidReason(boolean zh) {
        try {
            if (phase != 1 && phase != 2) {
                return zh ? ("断点阶段值非法（" + phase + "），不可续跑")
                          : ("invalid checkpoint phase (" + phase + ")");
            }
            if (phase == 2) {
                String pf = prefix == null ? "" : prefix;
                if (pf.length() != 4) {
                    return zh ? "阶段 2 缺少已确认的前 4 位，无法续跑"
                              : "phase 2 has no confirmed first-4-digits prefix - cannot resume";
                }
            }
            // phase == 1 带 prefix 不算错：那是「阶段 1 顺手记下前 4 位候选」，
            // 阶段 1 自己本来就要重扫 0..9999，忽略即可。
            return "";
        } catch (Throwable t) {
            return zh ? "断点结构不可用" : "checkpoint unusable";
        }
    }

    /** 一行人话（给 UI / 日志用）。 */
    public String describe(boolean zh) {
        try {
            String pf = prefix == null ? "" : prefix;
            String target = normBssid(bssid);
            String ss = ssid == null ? "" : ssid;
            if (zh) {
                StringBuilder sb = new StringBuilder();
                sb.append("阶段 ").append(phase);
                sb.append(" · 已完成 ").append(cursor).append(" 条");
                if (phase == 2) sb.append(" · 前 4 位 ").append(pf.isEmpty() ? "（缺失）" : pf);
                sb.append(" · 阶段 1 断点 ").append(cursor1);
                sb.append(" · 目标 ").append(target.isEmpty() ? "（未知）" : target);
                if (!ss.isEmpty()) sb.append("（SSID: ").append(ss).append("）");
                sb.append(" · 策略 M").append(mode);
                if (updatedAtMs > 0) sb.append(" · 更新于 ").append(updatedAtMs);
                return sb.toString();
            }
            StringBuilder sb = new StringBuilder();
            sb.append("phase ").append(phase);
            sb.append(" · done ").append(cursor);
            if (phase == 2) sb.append(" · prefix ").append(pf.isEmpty() ? "(missing)" : pf);
            sb.append(" · phase-1 cursor ").append(cursor1);
            sb.append(" · target ").append(target.isEmpty() ? "(unknown)" : target);
            if (!ss.isEmpty()) sb.append(" (SSID: ").append(ss).append(")");
            sb.append(" · mode M").append(mode);
            if (updatedAtMs > 0) sb.append(" · updated ").append(updatedAtMs);
            return sb.toString();
        } catch (Throwable t) {
            return zh ? "断点（无法描述）" : "checkpoint (undescribable)";
        }
    }

    // ---------------- internals ----------------

    /** BSSID 归一化：去空白、小写。 */
    static String normBssid(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 目标短标签：B + BSSID 十六进制后 3 位（例如 B208）。用于「属于另一个目标」的人话。 */
    static String tagOf(String bssid) {
        String b = normBssid(bssid);
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) hex.append(c);
        }
        if (hex.length() == 0) return "B???";
        int from = Math.max(0, hex.length() - 3);
        return "B" + hex.substring(from).toUpperCase();
    }

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

    private static String trimNote(String s) {
        if (s == null) return "";
        String v = s.trim();
        while (v.endsWith(";")) v = v.substring(0, v.length() - 1).trim();
        return v;
    }
}
