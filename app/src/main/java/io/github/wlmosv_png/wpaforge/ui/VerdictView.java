package io.github.wlmosv_png.wpaforge.ui;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;

/**
 * **Verdict → UI 的唯一定义处**（U1）。
 *
 * <h3>它修的是什么</h3>
 * 修复前，{@code UiCallback.onAttemptResult(candidate, boolean hit, ...)} 只给界面
 * 一个布尔：{@code hit=true} 打 ✓，{@code hit=false} 一律打 ✗。
 * 于是六种完全不同的情况在界面上长得一模一样：
 * <pre>
 *   AUTH_FAIL（候选级失败）      ✗
 *   NETWORK_AUTH_FAIL（网络级）  ✗   ← 用户会以为这条密码错了
 *   TIMEOUT（没等到证据）        ✗
 *   INCOMPLETE（连接还在跑）     ✗   ← 可能马上就成功
 *   TERMINATED_UNKNOWN           ✗   ← 连接终止但原因不明
 *   ERROR（通道坏了）            ✗   ← 环境问题，与密码无关
 * </pre>
 * 用户据此判断「这批密码都错了」，是**错的结论**。
 *
 * <h3>规则（任何页面都必须用这里，不得自行解释 Verdict）</h3>
 * <table>
 *   <tr><th>Verdict</th><th>符号</th><th>是否算「试过」</th><th>是否算「已证实错误」</th><th>可重试</th></tr>
 *   <tr><td>SUCCESS</td><td>✓</td><td>是</td><td>否（是命中）</td><td>—</td></tr>
 *   <tr><td>AUTH_FAIL</td><td>✗</td><td>是</td><td><b>是</b></td><td>否</td></tr>
 *   <tr><td>NETWORK_AUTH_FAIL</td><td>网✗</td><td>是</td><td><b>否</b></td><td>是</td></tr>
 *   <tr><td>TIMEOUT</td><td>⏱</td><td>是</td><td>否</td><td>是</td></tr>
 *   <tr><td>INCOMPLETE</td><td>…</td><td>是</td><td>否</td><td>是</td></tr>
 *   <tr><td>TERMINATED_UNKNOWN</td><td>?</td><td>是</td><td>否</td><td>是</td></tr>
 *   <tr><td>UNKNOWN</td><td>?</td><td>是</td><td>否</td><td>是</td></tr>
 *   <tr><td>ERROR</td><td>!</td><td>否</td><td>否</td><td>是</td></tr>
 *   <tr><td>ABORTED</td><td>⏹</td><td>否</td><td>否</td><td>是</td></tr>
 *   <tr><td>TARGET_GONE</td><td>—</td><td>否</td><td>否</td><td>否</td></tr>
 * </table>
 *
 * <p>纯逻辑、无 Android 依赖，可离线穷举（见 {@code VerdictViewTest}）。
 */
public final class VerdictView {

    private VerdictView() {}

    /** 列表前缀符号。**每种结论必须唯一**，否则用户无法区分。 */
    public static String symbol(CrackEngine.Verdict v) {
        if (v == null) return "·";
        switch (v) {
            case SUCCESS: return "✓";
            case AUTH_FAIL: return "✗";
            case NETWORK_AUTH_FAIL: return "网✗";
            case TIMEOUT: return "⏱";
            case INCOMPLETE: return "…";
            // U3 修复：TERMINATED_UNKNOWN 与 UNKNOWN 曾共用 "?" ——
            // 这两种事实完全不同（连接确实终止 vs 状态读不到），
            // 共用符号会让用户无法分辨，也让「符号唯一」这条契约失效。
            case TERMINATED_UNKNOWN: return "⊘";
            case UNKNOWN: return "?";
            case ERROR: return "!";
            case ABORTED: return "⏹";
            case TARGET_GONE: return "—";
            default: return "·";
        }
    }

    /** 中文短标签（列表里跟在符号后面）。 */
    public static String label(CrackEngine.Verdict v) {
        if (v == null) return "无结论";
        switch (v) {
            case SUCCESS: return "命中";
            case AUTH_FAIL: return "密码错误";
            case NETWORK_AUTH_FAIL: return "网络报错";
            case TIMEOUT: return "超时";
            case INCOMPLETE: return "连接中";
            // P1b：原为「连接终止」—— 用户读成「网络断了 / 软件连不上」。
            // 真实含义是「本次尝试已结束，但没拿到能证明密码对错的证据」。
            case TERMINATED_UNKNOWN: return "未证实";
            case UNKNOWN: return "不确定";
            case ERROR: return "通道异常";
            case ABORTED: return "已中断";
            case TARGET_GONE: return "目标不在";
            default: return "无结论";
        }
    }

    /**
     * 一句话说明（给用户看的解释）。
     *
     * <p>这一栏的意义：让用户知道「我现在该做什么」。例如
     * {@code NETWORK_AUTH_FAIL} 必须明确写「不代表这条密码错了」。
     */
    public static String description(CrackEngine.Verdict v) {
        if (v == null) return "";
        switch (v) {
            case SUCCESS:
                return "已连上并取得 IP";
            case AUTH_FAIL:
                return "这条密码被目标拒绝";
            case NETWORK_AUTH_FAIL:
                // 收口精简（2026-11-02）：原文案 49 字，在手机上要折三行，
                // 用户读不完就滑过去了。核心只有一句「不是这条密码的错」。
                return "网络报错，但不是这条密码的错";
            case TIMEOUT:
                return "没等到可归因的证据";
            case INCOMPLETE:
                return "连接还在进行，稍后可能成功";
            case TERMINATED_UNKNOWN:
                // P1b 定调（改掉「连接已终止」这种底层措辞），
                // 收口再压缩到 15 字：① 没连上（事实）② 未证明密码错 ③ 会重试。
                return "没连上目标，未证明密码错，将重试";
            case UNKNOWN:
                return "状态读不到或归属不明";
            case ERROR:
                return "通道出错，与密码无关";
            case ABORTED:
                return "被用户中断";
            case TARGET_GONE:
                return "目标已不在范围";
            default:
                return "";
        }
    }

    /**
     * 是否计入「已测试条数」（进度用）。
     *
     * <p>与 {@code CrackEngine.attemptedCounts()} 同一口径：
     * ERROR / ABORTED / TARGET_GONE 不算「试过」，因为命令根本没生效或没跑完。
     */
    public static boolean countsAsTested(CrackEngine.Verdict v) {
        if (v == null) return false;
        switch (v) {
            case ERROR:
            case ABORTED:
            case TARGET_GONE:
                return false;
            default:
                return true;
        }
    }

    /**
     * 是否计入「已证实错误密码」。
     *
     * <p><b>只有 AUTH_FAIL（候选级）算。</b>这是本类最重要的一条：
     * {@code NETWORK_AUTH_FAIL} 绝不能算进去 —— 那是网络级事件。
     */
    public static boolean countsAsProvenWrong(CrackEngine.Verdict v) {
        return v == CrackEngine.Verdict.AUTH_FAIL;
    }

    /** 是否意味着「这条候选值得再试一次」。 */
    public static boolean retryable(CrackEngine.Verdict v) {
        if (v == null) return true;
        switch (v) {
            case SUCCESS:
            case AUTH_FAIL:      // 已证实错误，不必再试
            case TARGET_GONE:    // 目标都没了
                return false;
            default:
                return true;
        }
    }

    /** 是否允许推进续跑游标（与 {@code EvidenceGrade.advancesCursor} 同口径）。 */
    public static boolean advancesCursor(CrackEngine.Verdict v) {
        if (v == null) return false;
        return v == CrackEngine.Verdict.SUCCESS || v == CrackEngine.Verdict.AUTH_FAIL;
    }

    /**
     * 是否属于「需要用户注意」的状态（用于聚合提示）。
     *
     * <p>INCOMPLETE / TERMINATED_UNKNOWN / NETWORK_AUTH_FAIL 这三种最容易被误读成
     * 「失败」，但恰恰是**最该提示用户复核**的。
     */
    public static boolean needsAttention(CrackEngine.Verdict v) {
        if (v == null) return false;
        switch (v) {
            case INCOMPLETE:
            case TERMINATED_UNKNOWN:
            case NETWORK_AUTH_FAIL:
            case ERROR:
                return true;
            default:
                return false;
        }
    }
}