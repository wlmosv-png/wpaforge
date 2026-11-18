package io.github.wlmosv_png.wpaforge.cap;

import io.github.wlmosv_png.wpaforge.audit.ConnectSpec;
import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.RootProber;
import io.github.wlmosv_png.wpaforge.audit.ShellSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Root 模式通道：{@code su + cmd wifi}。
 *
 * <h3>这是「搬运」，不是「重写」</h3>
 * 契约很简单：**Root 模式的行为必须与改造前逐字一致**。
 * 所以这里只做两件事 ——
 * <ol>
 *   <li>把改造前散在各处的 {@code exec("su","-c", cmd)} 原样搬进来；</li>
 *   <li>把「结论能不能用于证据」这一件事显式说出来（{@link #state()}）。</li>
 * </ol>
 * 常驻会话的降级策略（{@code ShellSession} 连挂 3 帧退回逐条 su）、
 * 通道熔断（连续超时）全部保持原语义，因为它们是**实测换来的**：
 * 每起一次 su 约 137ms × 一条候选约 28 次 ≈ 3.8 秒，这是「每条 5 秒」的大头。
 */
public final class RootChannel implements ConnectChannel {

    /** 连续多少次命令超时就判定通道被系统限制（原 CrackService 里的值）。 */
    public static final int EXEC_TIMEOUT_LIMIT = 6;

    private final Object lock = new Object();
    private ShellSession session;
    private boolean sessionOff;
    private int sessionFails;
    private int execTimeouts;
    private volatile boolean opened;
    private volatile Runnable onLost;

    /** 能力自述的缓存（{@link #probe()} 之后才非空；open 前一律 UNKNOWN）。 */
    private volatile Capability.Set0 probed;

    public RootChannel() {
    }

    // ------------------------------------------------------------ 生命周期

    /**
     * 起常驻会话。
     *
     * <p>失败**不影响可用性** —— 会退回逐条 su，只是慢一些。
     * 所以这里返回 true 的条件是「su 能用」，而不是「会话起来了」。
     */
    @Override
    public boolean open() {
        synchronized (lock) {
            if (!opened) {
                session = new ShellSession();
                sessionOff = !session.start();
                sessionFails = 0;
                execTimeouts = 0;
                opened = true;
            }
            return true;
        }
    }

    @Override
    public boolean isOpen() {
        synchronized (lock) {
            return opened;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (session != null) {
                try { session.close(); } catch (Throwable ignored) {}
            }
            session = null;
            opened = false;
        }
    }

    @Override
    public void setOnLost(Runnable r) {
        this.onLost = r;
    }

    @Override
    public String name() {
        return "Root";
    }

    // ------------------------------------------------------------ 底层 exec

    /**
     * 执行一条命令。**Root 模式所有命令最终都走这里。**
     *
     * <p>语义与改造前的 {@code ShellChannel.exec} 逐字一致：
     * 返回 {@code null} 表示「没拿到」（超时或会话死了），
     * 返回空串表示「拿到了但命令没输出」—— 这两者在判定链里含义不同，不能混。
     */
    public String exec(String cmd, long timeoutMs) {
        String out = null;
        synchronized (lock) {
            if (opened && !sessionOff && session != null) {
                out = session.exec(cmd, timeoutMs);
                if (out == null) {
                    if (++sessionFails >= 3) {
                        sessionOff = true;
                    }
                } else {
                    sessionFails = 0;
                }
            }
        }
        if (out == null) {
            out = RootProber.exec(null, new String[]{"su", "-c", cmd}, timeoutMs);
        }
        // 通道熔断：连续多次超时说明通道已经被系统限制，继续跑只是空转。
        if (RootProber.lastTimedOut()) {
            if (++execTimeouts >= EXEC_TIMEOUT_LIMIT) {
                execTimeouts = 0;
                Runnable r = onLost;
                if (r != null) {
                    try { r.run(); } catch (Throwable ignored) {}
                }
            }
        } else if (out != null) {
            execTimeouts = 0;
        }
        return out;
    }

    // ------------------------------------------------------------ 必需能力

    @Override
    public boolean canAssociate() {
        Capability c = capability(Capability.C_WIFI_CONNECT);
        return c == null || c.evidenceGrade();   // 未探测时按原行为放行，由执行期报错
    }

    @Override
    public boolean canForget() {
        Capability c = capability(Capability.C_WIFI_FORGET);
        return c == null || c.evidenceGrade();
    }

    @Override
    public boolean associate(String ssid, String security, String password,
                             String bssid, boolean macRotate) {
        if (!canAssociate()) return false;
        final String cmd = ConnectSpec.buildConnect(ssid, security, password, bssid, macRotate);
        new Thread(() -> {
            try { exec(cmd, 20000); } catch (Throwable ignored) {}
        }, "wpaforge-engine-conn").start();
        return true;
    }

    /**
     * 删净目标配置并回读确认。
     *
     * <p>实现在 {@code CrackEngine} 里（那里有回读校验与 netId 解析），
     * 由引擎注入；本方法只在没有引擎注入时作为兜底 —— 兜底同样要求回读确认，
     * 不做「发了命令就算删了」。
     */
    @Override
    public boolean forgetTarget(String ssid) {
        if (forgetImpl != null) return forgetImpl.forget(ssid);
        return false;
    }

    /** 由引擎注入的真实净检实现。未注入时 {@link #forgetTarget} 一律失败（fail-closed）。 */
    public interface ForgetImpl {
        boolean forget(String ssid);
    }

    private volatile ForgetImpl forgetImpl;

    public void setForgetImpl(ForgetImpl impl) {
        this.forgetImpl = impl;
    }

    // ------------------------------------------------------------ 可选能力

    @Override
    public String readState() {
        return exec("cmd wifi status", 8000);
    }

    @Override
    public String listSavedNetworks() {
        return exec("cmd wifi list-networks", 8000);
    }

    @Override
    public String readSupplicantLog() {
        return exec("logcat -d -s wpa_supplicant -t 400", 8000);
    }

    @Override
    public boolean canPrivilegedScan() {
        Capability c = capability(Capability.C_SCAN_PRIV);
        return c != null && c.evidenceGrade();
    }

    // ------------------------------------------------------------ 能力自述

    /** 已探测的能力快照；未探测返回 null（调用方按「原行为」处理）。 */
    public Capability.Set0 probedState() {
        return probed;
    }

    public void setProbedState(Capability.Set0 s) {
        this.probed = s;
    }

    private Capability capability(String id) {
        Capability.Set0 s = probed;
        if (s == null) return null;
        Capability c = s.get(id);
        return Capability.STATE_UNKNOWN.equals(c.state) ? null : c;
    }

    @Override
    public Capability.Set0 state() {
        Capability.Set0 s = probed;
        if (s != null) return s;
        List<Capability> list = new ArrayList<>();
        for (String id : Capability.ALL) {
            list.add(Capability.unknown(id, "尚未探测", "not probed yet"));
        }
        return new Capability.Set0("root", System.currentTimeMillis(), list);
    }

    /** 供引擎/诊断取用的直通（保持改造前的直接调用点可原样工作）。 */
    public CrackEngine.ShellChannel asShellChannel() {
        return new CrackEngine.ShellChannel() {
            @Override
            public String exec(String cmd, long timeoutMs) {
                return RootChannel.this.exec(cmd, timeoutMs);
            }

            @Override
            public String name() {
                return "Root";
            }

            @Override
            public void close() {
                RootChannel.this.close();
            }
        };
    }
}
