package io.github.wlmosv_png.wpaforge.audit;

/**
 * 成功判定的**唯一入口**。
 *
 * <h3>规则</h3>
 * 目标 SSID + supplicant 状态 COMPLETED + 有效 IP —— 三条同时成立即判成功。
 *
 * <h3>为什么 netId 绝不参与判定</h3>
 * {@code targetNetId} 是「命令发出后去查到的值」，时序敏感：
 * 系统还没把配置建好时它是 -1，系统换了连接之后它可能已经是别的连接的值。
 * 一旦拿它当否决条件，就会出现「密码正确、系统确实连上了目标，却因为 netId
 * 对不上而整段跳过成功判断」—— 实测事故：正确密码 12 秒全超时（漏判）。
 *
 * <p>归因本来就不需要靠它：命令之前有两道前置闸门
 * ①<em>目标配置已删净并回读校验</em> ②<em>命令前若连着目标已断开并确认</em>，
 * 所以命令之后出现的「目标 + 握手完成 + 有 IP」只可能来自本条候选。
 * netId 相符只作为**证据强度的标注**（{@code supplicant+netId}）。
 *
 * <h3>反向约束</h3>
 * 判定必须严：SSID 必须逐字相等、状态必须 COMPLETED、IP 必须不是 0.0.0.0。
 * 少了任何一条都不算成功 —— 宁可报「不确定」，也不能把错的当成对的。
 *
 * <p>纯逻辑，无 Android 依赖。
 */
public final class SuccessRule {

    private SuccessRule() {}

    /** 三条硬条件；netId 不在此列 */
    public static boolean isSuccess(String targetSsid, String statusSsid,
                                    String supplicantState, String ip) {
        if (targetSsid == null || targetSsid.isEmpty()) return false;
        if (statusSsid == null || !targetSsid.equals(statusSsid)) return false;
        if (supplicantState == null || !"COMPLETED".equalsIgnoreCase(supplicantState)) return false;
        if (ip == null || ip.isEmpty() || "0.0.0.0".equals(ip)) return false;
        return true;
    }

    /** 证据强度标注：netId 相符只是加分，不影响是否判成功 */
    public static String evidence(long statusNetId, long targetNetId) {
        return (statusNetId >= 0 && statusNetId == targetNetId)
                ? "supplicant+netId" : "supplicant";
    }
}
