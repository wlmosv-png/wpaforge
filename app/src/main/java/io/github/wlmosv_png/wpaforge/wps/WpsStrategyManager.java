package io.github.wlmosv_png.wpaforge.wps;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * WPS 验证策略的**注册表**（纯逻辑）。
 *
 * <h3>职责</h3>
 * 把「四种策略」的元信息（名称 / 说明 / 前置条件）与「候选生成」收在一处，
 * 供 UI 渲染与执行链查询。**不重写策略本身** —— 候选仍由
 * {@link WpsVendorPins} / {@link WpsCandidates} 产出，本类只做登记与前置判断。
 *
 * <h3>前置条件（不再让 UI 自行推断）</h3>
 * <ul>
 *   <li>厂商算法：只对存在公开可预测规则的 OUI / 固定值有意义；可选 SN 恒空 → 依赖 SN 的规则不入默认；</li>
 *   <li>内置弱 PIN 表：无额外前置；</li>
 *   <li>我的列表：需要用户先导入，否则候选为空；</li>
 *   <li>拆半：无数据前置，但耗时以十小时计且可能触发 AP 锁定。</li>
 * </ul>
 */
public final class WpsStrategyManager {

    public static final int MODE_VENDOR = 0;
    public static final int MODE_WEAK = 1;
    public static final int MODE_CUSTOM = 2;
    public static final int MODE_HALF = 3;

    /** 一个策略的登记项。 */
    public static final class Strategy {
        public final int mode;
        private final String zh, en;
        private final String descZh, descEn;

        Strategy(int mode, String zh, String en, String descZh, String descEn) {
            this.mode = mode;
            this.zh = zh;
            this.en = en;
            this.descZh = descZh;
            this.descEn = descEn;
        }

        public String label(boolean zhLang) {
            return zhLang ? zh : en;
        }

        public String description(boolean zhLang) {
            return zhLang ? descZh : descEn;
        }
    }

    /** 前置条件检查结果。 */
    public static final class Readiness {
        /** 是否具备执行条件（候选非空）。 */
        public boolean ready;
        /** 人话原因（ready=true 时为空串）。 */
        public String reasonZh = "";
        public String reasonEn = "";
        /** 按当前数据能产出的候选条数。 */
        public int candidateCount;

        public String reason(boolean zhLang) {
            return zhLang ? reasonZh : reasonEn;
        }
    }

    private static final Strategy[] REGISTRY = {
            new Strategy(MODE_VENDOR,
                    "厂商算法（BSSID 派生）", "Vendor algorithms",
                    "按 BSSID 算出有来源的厂商默认 PIN（D-Link / ASUS / Airocon 等）；"
                            + "只对存在公开可预测规则的机型有效，不保证成功。",
                    "Derive sourced factory PINs from the BSSID; only for models with "
                            + "publicly known rules - no success guarantee."),
            new Strategy(MODE_WEAK,
                    "内置弱 PIN 表", "Built-in weak PINs",
                    "65 个出厂默认 / 常见弱值，无需额外数据；覆盖老设备，不保证成功。",
                    "65 factory / common weak values; covers older devices, no guarantee."),
            new Strategy(MODE_CUSTOM,
                    "我的列表（导入）", "My list (imported)",
                    "你自己导入的候选（粘贴或从文件）。非法 8 位会原样保留并提示。",
                    "Your own imported candidates (paste or file); invalid kept verbatim."),
            new Strategy(MODE_HALF,
                    "拆半攻击（先前 4 位、再后 3 位）", "Half-PIN (prefix then suffix)",
                    "依次穷举前 4 位（1 万次）与后 3 位（1 千次），覆盖合法 8 位 PIN 的"
                            + "取值空间；耗时以十小时计，可能触发 AP 锁定，不保证成功。",
                    "Try first 4 digits then last 3; spans the valid 8-digit space. "
                            + "Hours-long, may trip AP lock-out - no guarantee."),
    };

    private WpsStrategyManager() {
    }

    /** 全部策略（按 mode 顺序）。 */
    public static List<Strategy> all() {
        List<Strategy> out = new ArrayList<>();
        for (Strategy s : REGISTRY) out.add(s);
        return out;
    }

    public static Strategy of(int mode) {
        for (Strategy s : REGISTRY) if (s.mode == mode) return s;
        return REGISTRY[0];
    }

    public static int count() {
        return REGISTRY.length;
    }

    // ---------------- 候选生成（委托现有实现，不重写） ----------------

    /**
     * 按策略生成候选。
     *
     * @param mode   策略
     * @param bssid  目标（厂商算法需要）
     * @param dir    应用私有目录（「我的列表」需要；可为 null）
     */
    public static List<String> candidates(int mode, String bssid, File dir) {
        List<String> out = new ArrayList<>();
        switch (mode) {
            case MODE_VENDOR:
                for (WpsVendorPins.Candidate c : WpsVendorPins.suggest(bssid, "")) {
                    out.add(c.pin);
                }
                break;
            case MODE_WEAK:
                out.addAll(WpsCandidates.builtinWeak());
                break;
            case MODE_CUSTOM:
                if (dir != null) out.addAll(WpsCandidates.loadCustom(dir));
                break;
            case MODE_HALF:
            default:
                // 拆半不产生"候选列表"（由 stage1Pin/stage2Pin 逐条生成）
                break;
        }
        return out;
    }

    /**
     * 前置条件检查（UI 显示"为什么不可用"，而不是点了没反应）。
     *
     * @param mode      策略
     * @param candidateCount 当前策略能产出的候选条数（调用方用 {@link #candidates} 算好传入）
     * @param hasTarget 是否已选目标
     */
    public static Readiness readiness(int mode, int candidateCount, boolean hasTarget) {
        Readiness r = new Readiness();
        r.candidateCount = candidateCount;
        if (!hasTarget) {
            r.ready = false;
            r.reasonZh = "尚未选择目标 AP";
            r.reasonEn = "no target selected yet";
            return r;
        }
        if (mode == MODE_HALF) {
            r.ready = true;      // 拆半不需要候选列表
            return r;
        }
        if (candidateCount <= 0) {
            r.ready = false;
            if (mode == MODE_VENDOR) {
                r.reasonZh = "该 BSSID 没有可用的厂商规则（或规则依赖序列号，本机拿不到）";
                r.reasonEn = "no applicable vendor rule for this BSSID";
            } else if (mode == MODE_CUSTOM) {
                r.reasonZh = "「我的列表」为空 —— 先导入候选，或换一个策略";
                r.reasonEn = "your list is empty - import candidates or switch strategy";
            } else {
                r.reasonZh = "当前策略没有可用候选";
                r.reasonEn = "no candidates for this strategy";
            }
            return r;
        }
        r.ready = true;
        return r;
    }
}
