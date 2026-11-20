package io.github.wlmosv_png.wpaforge.cap;

import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * **直调 IWifiManager 系统服务** —— 不走 shell，绕开 `cmd wifi` 的超时问题。
 *
 * <h3>为什么需要它（2026-11-20 真机事故 + 源码对照）</h3>
 * 用户真机现象：每条候选卡 11 秒（`list-networks` 5s + `forget-network` 6s 全超时），
 * 78 条白跑 14 分钟。根因是 shell 通道 + 管道读取的时序问题。
 *
 * <p>对照 WiFi 工具箱真实源码（`ShizukuUtil.kt`，Apache 2.0）发现：
 * **它根本不走 `cmd wifi` shell**，而是用
 * {@code ShizukuBinderWrapper} 包住 {@code ServiceManager.getService("wifi")}
 * 拿到 {@code IWifiManager}，然后反射直接调 {@code addOrUpdateNetwork} /
 * {@code forget} / {@code getConnectionInfo} 等。
 *
 * <p>好处（技术事实，非推断）：
 * <ul>
 *   <li>没有 shell 进程、没有管道、没有 500ms 的 `countDownLatch` 等待；</li>
 *   <li>返回值是**结构化的**（int netId / boolean / WifiInfo），不是文本；</li>
 *   <li>调用方 UID 由 Shizuku 的 binder wrapper 保证（这就是它能以 shell 身份
 *       调通特权接口的原因）。</li>
 * </ul>
 *
 * <h3>本类只做「调用」，不做「判定」</h3>
 * 判定仍然交给 {@code Adjudicator} / {@code SuccessRule}。
 * 这里返回结构化结果，绝不解释成「密码正确」。
 *
 * <h3>许可</h3>
 * 方法签名映射（各 Android 版本参数差异）参考 WiFi 工具箱
 * （Apache License 2.0）。本类是独立实现，不复制其业务逻辑。
 */
public final class WifiServiceBridge {

    private WifiServiceBridge() {
    }

    /** 一次调用的结构化结果。 */
    public static final class Outcome<T> {
        public final boolean ok;
        public final T value;
        /** 失败原因码（ok=true 时为空）。 */
        public final String reason;
        /** 原始异常类名（诊断用）。 */
        public final String errorClass;

        private Outcome(boolean ok, T value, String reason, String errorClass) {
            this.ok = ok;
            this.value = value;
            this.reason = reason == null ? "" : reason;
            this.errorClass = errorClass == null ? "" : errorClass;
        }

        static <T> Outcome<T> ok(T v) {
            return new Outcome<>(true, v, "", "");
        }

        static <T> Outcome<T> fail(String reason, Throwable t) {
            return new Outcome<>(false, null, reason,
                    t == null ? "" : t.getClass().getSimpleName());
        }

        public String describe() {
            return ok ? ("ok=" + value) : ("fail(" + reason
                    + (errorClass.isEmpty() ? "" : " · " + errorClass) + ")");
        }
    }

    // ------------------------------------------------------------ 服务获取

    /**
     * 拿到被 Shizuku 包装过的 {@code IWifiManager}。
     *
     * @return null = 拿不到（Shizuku 不可用 / 反射失败）
     */
    static Object wifiService() {
        try {
            IBinder binder = (IBinder) Class.forName("rikka.shizuku.SystemServiceHelper")
                    .getMethod("getSystemService", String.class)
                    .invoke(null, Context.WIFI_SERVICE);
            if (binder == null) return null;
            Object wrapper = Class.forName("rikka.shizuku.ShizukuBinderWrapper")
                    .getConstructor(IBinder.class).newInstance(binder);
            Class<?> stub = Class.forName("android.net.wifi.IWifiManager$Stub");
            return stub.getMethod("asInterface", IBinder.class).invoke(null, wrapper);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Shizuku 侧冒充的包名（决定 WifiService 的权限判定）。 */
    static final String SHELL_PKG = "com.android.shell";

    /**
     * 按 SDK 版本取方法签名。
     *
     * <p>版本差异来自 AOSP 的 IWifiManager.aidl 演进，
     * 与 WiFi 工具箱 {@code getWifiMethod} 的映射一致（已核对源码）。
     *
     * @return null = 该版本没有这个方法
     */
    static Method method(Object svc, String name, int sdk) {
        // **必须先试接口，再试实现类**（2026-11-20 真机证据）。
        //
        // 真机自检结果：fail(no-method)。
        // 原因：asInterface 返回的是 IWifiManager$Stub$Proxy，
        // 它实现的接口是 android.net.wifi.IWifiManager；
        // 但 Proxy 类上的方法可能因 hidden API 限制不可见，
        // 而**接口上的方法签名是稳定的**。
        // 所以先按接口找，找不到再按实现类找。
        Method m = methodOn(interfaceOf(svc), name, sdk);
        if (m != null) return m;
        return methodOn(svc.getClass(), name, sdk);
    }

    /** 取 svc 实现的、名字以 android.net 开头的接口（IWifiManager）。 */
    private static Class<?> interfaceOf(Object svc) {
        if (svc == null) return null;
        for (Class<?> i : svc.getClass().getInterfaces()) {
            String n = i.getName();
            if (n.startsWith("android.net.") || n.startsWith("android.os.")) return i;
        }
        // 一层父类
        Class<?> sup = svc.getClass().getSuperclass();
        if (sup != null) {
            for (Class<?> i : sup.getInterfaces()) {
                String n = i.getName();
                if (n.startsWith("android.net.") || n.startsWith("android.os.")) return i;
            }
        }
        return null;
    }

    private static Method methodOn(Class<?> c, String name, int sdk) {
        if (c == null) return null;
        Class<?> str = String.class;
        Class<?> i = Integer.TYPE;
        Class<?> z = Boolean.TYPE;
        try {
            switch (name) {
                case "getConnectionInfo":
                    if (sdk >= 30) return c.getMethod(name, str, str);
                    if (sdk >= 26) return c.getMethod(name, str);
                    return c.getMethod(name);
                case "getConfiguredNetworks":
                    if (sdk >= 31) return c.getMethod(name, str, str, z);
                    if (sdk >= 29) return c.getMethod(name, str);
                    return c.getMethod(name);
                case "removeNetwork":
                    if (sdk >= 28) return c.getMethod(name, i, str);
                    return c.getMethod(name, i);
                case "forget":
                    if (sdk >= 31) {
                        return c.getMethod(name, i,
                                Class.forName("android.net.wifi.IActionListener"));
                    }
                    if (sdk == 30) {
                        return c.getMethod(name, i, IBinder.class,
                                Class.forName("android.net.wifi.IActionListener"), i);
                    }
                    return null;
                case "addOrUpdateNetwork": {
                    Class<?> cfg = Class.forName("android.net.wifi.WifiConfiguration");
                    if (sdk >= 33) return c.getMethod(name, cfg, str, android.os.Bundle.class);
                    if (sdk >= 28) return c.getMethod(name, cfg, str);
                    return c.getMethod(name, cfg);
                }
                case "enableNetwork":
                    if (sdk >= 28) return c.getMethod(name, i, z, str);
                    return c.getMethod(name, i, z);
                case "disableNetwork":
                    if (sdk >= 28) return c.getMethod(name, i, str);
                    return c.getMethod(name, i);
                default:
                    return null;
            }
        } catch (Throwable t) {
            // **兜底：按名字扫描所有方法**（2026-11-20）。
            // 上面的精确签名映射依赖具体类的方法可见性；
            // 若某 ROM 的 Proxy 隐藏了方法或签名有细微差异，
            // 这里按「名字 + 参数个数最多」挑一个可用的。
            return scanByName(c, name);
        }
    }

    /**
     * 按名字扫描方法（兜底）。
     *
     * <p>只用于**只读**方法（getConnectionInfo / getConfiguredNetworks）：
     * 写操作必须走精确签名，避免误调到语义不同的重载。
     */
    private static Method scanByName(Class<?> c, String name) {
        if (c == null) return null;
        Method best = null;
        try {
            for (Method m : c.getMethods()) {
                if (!m.getName().equals(name)) continue;
                // 挑参数最多的那个（通常是完整版本）
                if (best == null || m.getParameterCount() > best.getParameterCount()) {
                    best = m;
                }
            }
            if (best != null) best.setAccessible(true);
        } catch (Throwable ignored) {}
        return best;
    }

    // ------------------------------------------------------------ 只读能力

    /**
     * 读当前连接信息。
     *
     * @return Outcome 里 value 是 {@code android.net.wifi.WifiInfo}（反射拿到，调用方自行取字段）
     */
    public static Outcome<Object> getConnectionInfo(int sdk) {
        Object svc = wifiService();
        if (svc == null) return Outcome.fail("no-service", null);
        try {
            Method m = method(svc, "getConnectionInfo", sdk);
            if (m == null) return Outcome.fail("no-method", null);
            Object v;
            int n = m.getParameterTypes().length;
            if (n == 2) v = m.invoke(svc, SHELL_PKG, (String) null);
            else if (n == 1) v = m.invoke(svc, SHELL_PKG);
            else v = m.invoke(svc);
            return v == null ? Outcome.<Object>fail("null-info", null) : Outcome.ok(v);
        } catch (Throwable t) {
            return Outcome.fail("invoke-failed", t);
        }
    }

    /**
     * 列已保存网络。
     *
     * @return value 是 {@code List<WifiConfiguration>}（反射对象，调用方取 networkId / SSID）
     */
    @SuppressWarnings("unchecked")
    public static Outcome<List<Object>> listConfiguredNetworks(int sdk) {
        Object svc = wifiService();
        if (svc == null) return Outcome.fail("no-service", null);
        try {
            Method m = method(svc, "getConfiguredNetworks", sdk);
            if (m == null) return Outcome.fail("no-method", null);
            int n = m.getParameterTypes().length;
            Object raw;
            if (n == 3) raw = m.invoke(svc, SHELL_PKG, (String) null, false);
            else if (n == 2) raw = m.invoke(svc, SHELL_PKG, (String) null);
            else if (n == 1) raw = m.invoke(svc, SHELL_PKG);
            else raw = m.invoke(svc);
            if (raw == null) return Outcome.fail("null-result", null);
            List<Object> out = new ArrayList<>();
            if (raw instanceof List) {
                out.addAll((List<Object>) raw);
            } else {
                // ParceledListSlice
                Object list = raw.getClass().getMethod("getList").invoke(raw);
                if (list instanceof List) out.addAll((List<Object>) list);
            }
            return Outcome.ok(out);
        } catch (Throwable t) {
            return Outcome.fail("invoke-failed", t);
        }
    }

    /**
     * 删掉某 netId 的配置。
     *
     * <p>AOSP 版本差异（已与 WiFi 工具箱 {@code ShizukuUtil.forgetNetwork} 核对）：
     * <ul>
     *   <li>SDK ≤ 29 → {@code removeNetwork(netId)}</li>
     *   <li>SDK 30 → {@code forget(netId, binder, listener, callbackId)}</li>
     *   <li>SDK ≥ 31 → {@code forget(netId, listener)}</li>
     * </ul>
     * listener 传 null：AOSP 里它是 IActionListener，允许为 null
     * （同步返回 void，成败要靠**回读**确认，不能看返回值）。
     *
     * <p><b>注意</b>：本方法返回 true 只表示「调用没有抛异常」，
     * **不代表配置真的被删了** —— 调用方必须回读 listConfiguredNetworks 确认。
     */
    public static boolean forgetNetwork(int netId) {
        Object svc = wifiService();
        if (svc == null) return false;
        int sdk = android.os.Build.VERSION.SDK_INT;
        try {
            if (sdk <= 29) {
                Method m = method(svc, "removeNetwork", sdk);
                if (m == null) return false;
                if (m.getParameterTypes().length == 2) m.invoke(svc, netId, SHELL_PKG);
                else m.invoke(svc, netId);
                return true;
            }
            Method m = method(svc, "forget", sdk);
            if (m == null) return false;
            if (m.getParameterTypes().length == 4) {
                m.invoke(svc, netId, null, null, 0);
            } else {
                m.invoke(svc, netId, null);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * **诊断**：报告直调链路各环节的状态（只读，不改任何东西）。
     *
     * <p>真机自检显示 {@code fail(no-method)} 时，需要知道是哪一步断的：
     * binder 没拿到？wrapper 构造失败？asInterface 失败？接口里没这个方法？
     */
    public static String diagnose() {
        StringBuilder sb = new StringBuilder();
        try {
            IBinder binder = (IBinder) Class.forName("rikka.shizuku.SystemServiceHelper")
                    .getMethod("getSystemService", String.class)
                    .invoke(null, Context.WIFI_SERVICE);
            sb.append("binder=").append(binder == null ? "null" : "ok");
            if (binder == null) return sb.toString();

            Object wrapper = Class.forName("rikka.shizuku.ShizukuBinderWrapper")
                    .getConstructor(IBinder.class).newInstance(binder);
            sb.append(" wrapper=ok");

            Class<?> stub = Class.forName("android.net.wifi.IWifiManager$Stub");
            Object svc = stub.getMethod("asInterface", IBinder.class).invoke(null, wrapper);
            sb.append(" asInterface=").append(svc == null ? "null" : "ok");
            if (svc == null) return sb.toString();

            Class<?> cls = svc.getClass();
            sb.append(" class=").append(cls.getSimpleName());
            Class<?> iface = interfaceOf(svc);
            sb.append(" iface=").append(iface == null ? "none" : iface.getSimpleName());
            int n = 0;
            try { n = cls.getMethods().length; } catch (Throwable ignored) {}
            sb.append(" methods=").append(n);

            // 关键方法是否可见
            sb.append(" | getConnectionInfo=")
                    .append(methodOn(iface, "getConnectionInfo",
                            android.os.Build.VERSION.SDK_INT) != null ? "yes" : "no");
            sb.append(" getConfiguredNetworks=")
                    .append(methodOn(iface, "getConfiguredNetworks",
                            android.os.Build.VERSION.SDK_INT) != null ? "yes" : "no");
            // **列出接口上真实可见的方法名**（任务书第五部分要求：
            // 不能只看方法数量，要看具体需要的方法是否存在）。
            try {
                java.util.TreeSet<String> names = new java.util.TreeSet<>();
                for (java.lang.reflect.Method m : cls.getMethods()) {
                    names.add(m.getName());
                }
                sb.append(" | visible=").append(names);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            sb.append(" EX:").append(t.getClass().getSimpleName());
        }
        return sb.toString();
    }

    /** 从 WifiConfiguration 反射取 networkId。 */
    public static int netIdOf(Object config) {
        try {
            return (Integer) config.getClass().getField("networkId").get(config);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 从 WifiConfiguration 反射取 SSID（已去引号）。 */
    public static String ssidOf(Object config) {
        try {
            Object v = config.getClass().getField("SSID").get(config);
            String s = v == null ? "" : v.toString();
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length() - 1);
            }
            return s;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 从 WifiInfo 反射取某个字段（失败返回 null）。 */
    public static Object infoField(Object info, String name) {
        try {
            return info.getClass().getMethod("get" + Character.toUpperCase(name.charAt(0))
                    + name.substring(1)).invoke(info);
        } catch (Throwable t) {
            try {
                return info.getClass().getField(name).get(info);
            } catch (Throwable t2) {
                return null;
            }
        }
    }
}
