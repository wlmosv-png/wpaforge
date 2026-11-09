package io.github.wlmosv_png.wpaforge.wps;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * WPS 候选数据管理（纯逻辑，导入/校验/去重/持久化）。
 *
 * <h3>职责</h3>
 * 统一「用户数据进来 → 解析 → 校验 → 去重 → 落盘 → 读回」的入口，
 * 并把解析反馈（有效/无效/重复/截断/每条原因）结构化返回。
 * 底层仍是 {@link WpsCandidates}（不重写算法），本类负责**入口唯一**与**结果可读**。
 */
public final class WpsDataManager {

    private WpsDataManager() {
    }

    /** 导入结果汇总（供 UI 展示，不含原始敏感内容）。 */
    public static final class ImportSummary {
        public int accepted;      // 去重后收录条数
        public int invalid;       // 被拒条数
        public int warned;        // 收录但有提醒（原样保留的非法校验位 / 补全的 7 位）
        public int overflow;      // 因上限未收录
        public boolean truncated;
        public int issues;
        public String firstIssue = "";
        public String textZh = "";
        public String textEn = "";

        public boolean anyProblem() {
            return invalid > 0 || warned > 0 || overflow > 0 || truncated || issues > 0;
        }
    }

    /** 解析文本（不落盘）。 */
    public static WpsCandidates.Report parse(String text) {
        return WpsCandidates.parse(text);
    }

    /** 把解析结果转成 UI 可读的汇总。 */
    public static ImportSummary summarize(WpsCandidates.Report rep, boolean zh) {
        ImportSummary s = new ImportSummary();
        if (rep == null) {
            s.textZh = "解析失败（无结果）";
            s.textEn = "parse failed (no result)";
            return s;
        }
        s.accepted = rep.accepted.size();
        s.invalid = rep.invalidCount;
        s.warned = rep.warnCount;
        s.overflow = rep.overflowCount;
        s.truncated = rep.truncated;
        s.issues = rep.issues.size();
        if (!rep.issues.isEmpty()) s.firstIssue = rep.issues.get(0).describe(zh);
        s.textZh = rep.summary(true);
        s.textEn = rep.summary(false);
        return s;
    }

    /**
     * 保存导入文本。
     *
     * @param dir    应用私有目录
     * @param text   原文
     * @param append true=追加，false=替换
     * @return 保存后的总条数；-1 = 失败
     */
    public static int save(File dir, String text, boolean append) {
        if (dir == null) return -1;
        try {
            return WpsCandidates.saveCustom(dir, text == null ? "" : text, append);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 读取「我的列表」。 */
    public static List<String> load(File dir) {
        if (dir == null) return new ArrayList<>();
        try {
            List<String> l = WpsCandidates.loadCustom(dir);
            return l == null ? new ArrayList<String>() : l;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /** 清空。 */
    public static boolean clear(File dir) {
        if (dir == null) return false;
        try {
            return WpsCandidates.clearCustom(dir);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前条数。 */
    public static int count(File dir) {
        return load(dir).size();
    }
}
