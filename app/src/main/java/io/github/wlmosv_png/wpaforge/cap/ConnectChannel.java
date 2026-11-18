package io.github.wlmosv_png.wpaforge.cap;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;

/**
 * 逐条关联的执行通道（**能力接口**，业务层只认这个）。
 *
 * <h3>为什么要在 {@link CrackEngine.ShellChannel} 之外再加一层</h3>
 * {@code ShellChannel} 的签名是 {@code exec(String cmd)} —— 它假定了
 * 「拿到一条命令就能得到结果」。这在 Root 下成立，在无 Root 下**不成立**：
 * 没有 root，{@code cmd wifi} 根本不会被执行（实测：非 root uid 调用返回空输出），
 * 再往里传命令只会得到一片空白，而空白会被判定链误读成「没连上」。
 *
 * <p>所以能力接口必须在**语义层**开口子：不是「能不能执行这条命令」，
 * 而是「能不能在这个网络上逐条试口令」。做不到就是做不到，
 * 由 {@link #state()} 明说，绝不用空输出冒充失败结论。
 *
 * <p>Root 模式由 {@code RootChannel} 实现，行为与改造前逐字一致；
 * 无 Root 模式的实现在能力探测后决定（当前结论：不支持逐条关联）。
 */
public interface ConnectChannel {

    /**
     * 打开通道（可能会起常驻 shell）。
     *
     * @return 是否可用；不可用时 {@link #state()} 会给出具体原因
     */
    boolean open();

    boolean isOpen();

    /** 关通道。必须幂等。 */
    void close();

    /** 通道名字（诊断与日志用，例如 {@code "Root"}）。 */
    String name();

    // ------------------------------------------------------------ 必需能力

    /**
     * 发一条关联命令。<b>只允许在 {@link #canAssociate()} 为 true 时调用。</b>
     *
     * @return 是否真的把命令发出去了；false = 没发（调用方必须按「未尝试」处理）
     */
    boolean associate(String ssid, String security, String password,
                      String bssid, boolean macRotate);

    /**
     * 删除目标在系统里的已保存配置，并回读确认删净。
     *
     * <p>这一步是**假阳性的第一道闸**：旧配置不清，系统会拿之前保存的正确口令
     * 连上，于是任何候选都「成功」。所以删不掉就必须让本条尝试作废。
     */
    boolean forgetTarget(String ssid);

    // ------------------------------------------------------------ 可选能力

    /**
     * 读当前 Wi-Fi 状态原文（{@code cmd wifi status} 形态）。
     *
     * @return 原文；通道没有这个能力或没读到返回 {@code null}（**不是空串**）
     */
    String readState();

    /**
     * 读系统已保存网络列表原文（{@code cmd wifi list-networks} 形态）。
     *
     * @return 原文；读不到返回 {@code null}
     */
    String listSavedNetworks();

    /**
     * 读 {@code wpa_supplicant} 日志片段。
     *
     * @return 原文；读不到返回 {@code null}。**这个能力单独开关**，
     *         因为它决定 AUTH_FAIL 能不能升到 DIRECT 级
     */
    String readSupplicantLog();

    /** 本机是否还支持特权扫描兜底（{@code cmd wifi list-scan-results}）。 */
    boolean canPrivilegedScan();

    // ------------------------------------------------------------ 能力自述

    /** 逐条关联是否真的能做到（不是「配置里有 root」，是「实测能拿到结果」）。 */
    boolean canAssociate();

    /** 净检（删旧配置）是否真的能做到。 */
    boolean canForget();

    /** 通道当前的能力状态（含不可用原因与替代路径）。 */
    Capability.Set0 state();

    /** 掉通道时的回调（常驻会话断了、su 被收回）。默认不关心。 */
    default void setOnLost(Runnable r) {
    }
}
