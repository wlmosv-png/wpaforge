package io.github.wlmosv_png.wpaforge.audit;

import java.util.List;

/**
 * 字典续命：按**内容身份**决定一本字典能不能继承历史进度。
 *
 * <h3>它解决什么</h3>
 * 同一本字典换了路径（改名/移动/重新导入）后，旧实现按 {@code dictPath} 找历史，
 * 路径一变就当成「新字典」，从第 0 条重跑 —— 已经在本目标验证过的候选被白白重试。
 * 本类改用 {@link DictResume} 依据的**内容 SHA-256**：
 * <ul>
 *   <li>内容相同（无论路径）→ 可以按既有安全规则继承原 offset；</li>
 *   <li>同路径但内容变了 → **不继承** offset，并明确标记 {@code contentChanged}
 *       让界面提示用户（不能悄悄拿旧进度去跑一份新字典）。</li>
 * </ul>
 *
 * <h3>边界（必须守）</h3>
 * <ul>
 *   <li>只读：本类不写任何文件、不改任何状态；</li>
 *   <li>只认同一目标：换 Wi-Fi 不继承（候选历史本来就按目标隔离）；</li>
 *   <li>不碰计划：候选来源是「队列任务」或「计划成员」，但本类只**读**它们；</li>
 *   <li>不改其它字典的 offset。</li>
 * </ul>
 */
public final class DictResume {

    /** 查到的历史进度。 */
    public static final class Info {
        public final boolean found;
        /** 上次跑到第几条。 */
        public final int offset;
        /** 上次记录的字典条数（0 = 未知）。 */
        public final int dictSize;
        public final String dictName;
        public final String dictPath;
        public final String sha;
        /** 内容相同但路径不同。 */
        public final boolean pathChanged;
        /** 同路径但内容变了（此时 found 仍可能为 false）。 */
        public final boolean contentChanged;
        /** 来源：{@code "plan"} / {@code "task"} / {@code ""}。 */
        public final String source;

        private Info(boolean found, int offset, int dictSize, String name, String path,
                     String sha, boolean pathChanged, boolean contentChanged, String source) {
            this.found = found; this.offset = offset; this.dictSize = dictSize;
            this.dictName = name == null ? "" : name;
            this.dictPath = path == null ? "" : path;
            this.sha = sha == null ? "" : sha;
            this.pathChanged = pathChanged;
            this.contentChanged = contentChanged;
            this.source = source == null ? "" : source;
        }

        public static Info none() {
            return new Info(false, 0, 0, "", "", "", false, false, "");
        }
    }

    private DictResume() {}

    /**
     * 查找「同一目标 + 同一内容」的历史进度。
     *
     * @param queue       当前队列（可为 null）
     * @param plan        当前目标的计划（可为 null）
     * @param targetKey   本次目标键（{@link TriedSet#targetKey}）
     * @param sha         本次字典内容 SHA-256（空串 = 拿不到，退化为路径匹配）
     * @param currentPath 本次字典路径
     */
    public static Info find(List<TaskQueue.Task> queue, DictPlan plan,
                            String targetKey, String sha, String currentPath) {
        String s = sha == null ? "" : sha;
        String cp = currentPath == null ? "" : currentPath;
        Info best = Info.none();
        boolean contentChanged = false;

        // ① 计划成员：内容身份 + 目标一致
        if (plan != null && plan.items != null) {
            for (DictPlan.Item it : plan.items) {
                if (it == null || it.sha256 == null || it.sha256.isEmpty()) continue;
                if (!s.isEmpty() && !it.sha256.equals(s)) {
                    if (!cp.isEmpty() && cp.equals(it.path) && !s.equals(it.sha256)) {
                        contentChanged = true;   // 同路径、不同内容
                    }
                    continue;
                }
                if (!s.isEmpty() && it.sha256.equals(s)) {
                    Info cand = new Info(true, Math.max(0, it.offset), Math.max(0, it.dictSize),
                            it.name, it.path, it.sha256,
                            !cp.isEmpty() && !cp.equals(it.path), false, "plan");
                    if (cand.offset > best.offset) best = cand;
                }
            }
        }

        // ② 队列任务：按目标键 + 内容身份（退化为路径）
        if (queue != null) {
            for (TaskQueue.Task t : queue) {
                if (t == null) continue;
                String tk = TriedSet.targetKey(t.bssid, t.ssid);
                if (targetKey != null && !targetKey.isEmpty() && !targetKey.equals(tk)) continue;

                String ts = t.dictSha == null ? "" : t.dictSha;
                String tp = t.dictPath == null ? "" : t.dictPath;
                boolean bySha = !s.isEmpty() && !ts.isEmpty() && s.equals(ts);
                boolean byPath = !cp.isEmpty() && cp.equals(tp);
                if (bySha) {
                    Info cand = new Info(true, Math.max(0, t.offset), Math.max(0, t.dictSize),
                            t.dictName, tp, ts,
                            !cp.isEmpty() && !cp.equals(tp), false, "task");
                    if (cand.offset > best.offset) best = cand;
                } else if (byPath && !ts.isEmpty() && !s.isEmpty() && !s.equals(ts)) {
                    contentChanged = true;           // 同路径、不同内容
                }
            }
        }

        if (contentChanged && !best.found) {
            return new Info(false, 0, 0, "", "", "", false, true, "");
        }
        return best;
    }

    /**
     * 续命时应使用的起始 offset。
     *
     * <p>内容不符（{@code contentChanged}）一律返回 0 —— 不拿旧进度去跑新字典。
     * 命中时把 offset 夹到当前字典长度内（防止越界死状态）。
     */
    public static int offsetFor(Info info, int currentSize) {
        if (info == null || !info.found || info.contentChanged) return 0;
        int off = Math.max(0, info.offset);
        if (currentSize > 0 && off > currentSize) return 0;   // 越界 → 保守重跑
        return off;
    }

    /** 续命提示文案（界面用；不含任何口令）。 */
    public static String describe(Info info, int currentSize) {
        if (info == null) return "";
        if (info.contentChanged) {
            return "字典内容已变化：不继承旧进度（从第 1 条重跑）";
        }
        if (!info.found) return "";
        int off = offsetFor(info, currentSize);
        if (off <= 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("发现《").append(info.dictName.isEmpty() ? "本字典" : info.dictName)
                .append("》在本目标上次跑到第 ").append(off).append(" 条");
        if (info.pathChanged) sb.append("（路径已变，按内容识别）");
        sb.append("，可续命继续");
        return sb.toString();
    }
}
