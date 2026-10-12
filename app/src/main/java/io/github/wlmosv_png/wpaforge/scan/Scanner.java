package io.github.wlmosv_png.wpaforge.scan;

import io.github.wlmosv_png.wpaforge.L;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.wlmosv_png.wpaforge.core.NetRecord;

/**
 * 扫描器。用广播接收 + 轮询兜底两条路并行：
 *  - 主动 startScan() 触发扫描，结果通过 SCAN_RESULTS_AVAILABLE_ACTION 回来
 *  - 同时按周期直接读 getScanResults()（部分 ROM 广播不可靠）
 *
 * 权限说明（Android 13+ 需要 NEARBY_WIFI_DEVICES，之前是定位权限）：
 * 缺失时不会静默失败，而是把原因回传给上层展示。
 */
public final class Scanner {

    public interface Listener {
        void onResults(List<NetRecord> records);
        /** 扫描失败时会给出可读原因，而不是无声无息 */
        void onNotice(String message, boolean isError);
    }

    private final Context appCtx;
    private final WifiManager wifi;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private static final String TAG = "WPAForgeScan";
    private BroadcastReceiver receiver;
    private boolean running;
    private long lastScanRequestAt;
    /** 主动 startScan 的最小间隔，Android 限制约 30s，这里给 15s 以免被系统丢弃 */
    private static final long MIN_SCAN_INTERVAL_MS = 15_000L;
    private static final long POLL_INTERVAL_MS = 5_000L;
    /** 特权通道两次尝试之间的最小间隔，避免频繁起 su 进程 */
    private static final long PRIV_MIN_INTERVAL_MS = 20_000L;

    /** 上次用特权通道读的时间 */
    private long lastPrivAt;
    /** 特权通道是否曾经成功过（成功过就优先用它，避免反复降级失败） */
    private boolean privEverWorked;
    /** 上次特权通道失败的原因，供诊断展示 */
    private volatile String privDiag = "";

    /**
     * BSSID -> 物理层细节（带宽 / 代际）。
     *
     * 为什么不塞进 NetRecord：NetRecord 是判定链的载体，每多一个字段
     * 就多一处可能出错的地方。这些字段纯粹给人看，不能污染判定。
     * 所以按 BSSID 旁挂在这里，界面按需取，取不到就是空。
     *
     * 每次普通 API 解析成功后整体替换，不留陈旧条目。
     */
    private volatile Map<String, ScanDetail> details = new LinkedHashMap<>();

    public Scanner(Context ctx, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.wifi = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
        this.listener = listener;
    }

    /** 检查权限是否齐，返回缺失项的可读名 */
    public List<String> missingPermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (!granted("android.permission.NEARBY_WIFI_DEVICES")) {
                missing.add(L.s("附近设备(NEARBY_WIFI_DEVICES)", "Nearby devices (NEARBY_WIFI_DEVICES)"));
            }
        }
        if (!granted("android.permission.ACCESS_FINE_LOCATION")) {
            missing.add(L.s("精确位置(ACCESS_FINE_LOCATION)", "Precise location (ACCESS_FINE_LOCATION)"));
        }
        return missing;
    }

    private boolean granted(String perm) {
        return appCtx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    /** WiFi 开关是否打开 */
    public boolean isWifiEnabled() {
        try {
            return wifi != null && wifi.isWifiEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    public void start() {
        if (running) return;
        running = true;

        if (wifi == null) {
            listener.onNotice(L.s("此设备无法访问 WiFi 服务", "This device cannot access the Wi-Fi service"), true);
            return;
        }

        List<String> missing = missingPermissions();
        if (!missing.isEmpty()) {
            listener.onNotice(L.s("缺少权限: ", "Missing permissions: ") + String.join("、", missing)
                    + L.s("。没有它们系统不会返回扫描结果。", ". Without them the system returns no scan results."), true);
        }
        if (!isWifiEnabled()) {
            listener.onNotice(L.s("WiFi 未开启，请先打开 WiFi 开关", "Wi-Fi is off — turn it on first"), true);
            return;
        }

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                    emit();
                }
            }
        };
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appCtx.registerReceiver(receiver, filter);
        }

        // 立刻来一次，然后进循环
        requestScan();
        emit();
        handler.post(pollLoop);
    }

    private final Runnable pollLoop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            requestScan();
            emit();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    /** 主动请求扫描，受系统频率限制，失败不报错（被动路径会兜底） */
    private void requestScan() {
        long now = System.currentTimeMillis();
        if (now - lastScanRequestAt < MIN_SCAN_INTERVAL_MS) return;
        lastScanRequestAt = now;
        try {
            boolean ok = wifi.startScan();
            if (!ok) {
                // startScan 返回 false = 系统拒绝了这次扫描
                // （通常是频率限制：两次扫描间隔太近）
                android.util.Log.d(TAG, "startScan 被拒绝（可能触发频率限制）");
            }
        } catch (SecurityException e) {
            lastDiag = "SCAN_PERMISSION: " + e.getMessage();
            android.util.Log.w(TAG, "startScan 权限不足", e);
        } catch (Exception e) {
            lastDiag = "SCAN_" + e.getClass().getSimpleName();
            android.util.Log.w(TAG, "startScan 失败", e);
        }
    }

    /** 读取当前结果并回调。空结果时附带诊断，让界面能说清原因。 */
    public void emit() {
        List<NetRecord> records = readNow();
        if (records.isEmpty()) {
            String d = lastDiag;
            if (d.startsWith("PERMISSION")) {
                listener.onNotice(L.s("读取扫描结果被拒绝：权限不足",
                        "Reading scan results was denied: missing permission"), true);
            } else if (d.startsWith("SCAN_PERMISSION")) {
                listener.onNotice(L.s("发起扫描被拒绝：权限不足",
                        "Starting a scan was denied: missing permission"), true);
            } else if ("EMPTY_RESULT".equals(d)) {
                listener.onNotice(L.s("扫描完成但一个网络都没发现（可能周围确实没有）",
                        "Scan finished but found nothing nearby"), false);
            } else if (d.startsWith("NULL") || d.startsWith("WIFI_SERVICE")) {
                listener.onNotice(L.s("系统没有返回扫描数据",
                        "The system returned no scan data"), true);
            }
        }
        listener.onResults(records);
    }

    /** 最近一次读取的诊断信息，供界面展示 */
    private volatile String lastDiag = "";

    public String lastDiagnostic() {
        return lastDiag;
    }

    /**
     * 同步读一次当前扫描结果。
     *
     * 注意：getScanResults 抛异常时**不能静默返回空列表** ——
     * 那样界面只能显示"还没扫描到网络"，用户根本不知道是权限问题、
     * Wi-Fi 关着、还是系统限制。这里把原因记下来供界面显示。
     */
    public List<NetRecord> readNow() {
        List<NetRecord> out = new ArrayList<>();
        if (wifi == null) {
            lastDiag = "WIFI_SERVICE_NULL";
            return out;
        }

        List<ScanResult> raw = null;
        try {
            raw = wifi.getScanResults();
        } catch (SecurityException e) {
            // 权限不足是最常见的原因
            lastDiag = "PERMISSION: " + e.getMessage();
            android.util.Log.w(TAG, "getScanResults 权限不足", e);
            return fallbackOr(out);
        } catch (Exception e) {
            lastDiag = e.getClass().getSimpleName() + ": " + e.getMessage();
            android.util.Log.w(TAG, "getScanResults 失败", e);
            return fallbackOr(out);
        }

        if (raw == null) {
            lastDiag = "NULL_RESULT";
            return fallbackOr(out);
        }
        if (raw.isEmpty()) {
            lastDiag = "EMPTY_RESULT";
            return fallbackOr(out);
        }
        lastDiag = "OK(" + raw.size() + ")";

        /*
         * 注意：这里**不再**按 SSID 去重。
         *
         * 旧行为是「同名 SSID 只保留信号最强的一条」，单 AP 环境没问题，
         * 但 Mesh / 宿舍多基站场景会把「这网其实有三个 AP、5G 那条特别强」
         * 这类关键信息抹掉，而用户恰恰要靠它决定连哪个。
         *
         * 现在全量保留，由 SsidGroup 在展示层聚合。判定链不受影响：
         * 它处理的始终是单个 NetRecord，只是候选变多了。
         */
        Map<String, ScanDetail> fresh = new LinkedHashMap<>();
        for (ScanResult sr : raw) {
            if (sr == null) continue;
            String caps = sr.capabilities == null ? "" : sr.capabilities;
            NetRecord rec = new NetRecord(
                    sr.SSID,
                    sr.BSSID,
                    sr.level,
                    sr.frequency,
                    NetRecord.parseSecurity(caps),
                    NetRecord.parseWps(caps),
                    caps);
            out.add(rec);
            fresh.put(rec.bssid, ScanDetail.from(sr));
        }
        details = fresh;
        Collections.sort(out, (a, b) -> Integer.compare(b.rssi, a.rssi));
        return out;
    }

    /**
     * 取某条记录的物理层细节（带宽 / 代际）。
     * 拿不到返回 {@link ScanDetail#UNKNOWN}，调用方不必判空。
     */
    public ScanDetail detailOf(NetRecord r) {
        if (r == null) return ScanDetail.UNKNOWN;
        ScanDetail d = details.get(r.bssid);
        return d == null ? ScanDetail.UNKNOWN : d;
    }

    /**
     * 普通 API 拿不到东西时的兜底：走 root / Shizuku 读系统扫描结果。
     *
     * 为什么需要这条：
     *   部分 ROM（ColorOS 等）上 getScanResults() 会**静默返回空** ——
     *   不抛异常、不报错，界面只能干显示"还没扫描到网络"。
     *   而本工具本来就有特权通道，用它跑 `cmd wifi list-scan-results`
     *   拿的是系统权威数据，实测比普通 API 更完整。
     *
     * 触发条件（三者同时满足才走，避免无谓开销）：
     *   · 普通 API 这一轮确实没读到网络
     *   · 距上次特权读取超过 PRIV_MIN_INTERVAL_MS
     *   · 上次特权读取成功过，或还没试过
     */
    private List<NetRecord> readPrivilegedIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastPrivAt < PRIV_MIN_INTERVAL_MS) return null;
        lastPrivAt = now;

        try {
            List<NetRecord> priv = PrivScanReader.read(false);
            if (priv != null && !priv.isEmpty()) {
                privEverWorked = true;
                privDiag = "";
                return priv;
            }
            privDiag = PrivScanReader.lastChannel().isEmpty()
                    ? "PRIV_UNAVAILABLE"        // 没有可用通道（没 root、Shizuku 没跑）
                    : "PRIV_EMPTY";             // 通道在，但系统也没数据
        } catch (Throwable t) {
            privDiag = "PRIV_ERR:" + t.getClass().getSimpleName();
        }
        return null;
    }

    /** 特权通道诊断信息，供界面/日志展示 */
    public String privilegedDiagnostic() {
        return privDiag;
    }

    /**
     * 普通 API 读不到时的统一出口：
     * 先试特权通道，拿到就用特权数据，拿不到就返回原来的空列表。
     * 这样调用方（emit / 界面）完全不用感知通道差异。
     */
    private List<NetRecord> fallbackOr(List<NetRecord> empty) {
        List<NetRecord> priv = readPrivilegedIfNeeded();
        if (priv != null && !priv.isEmpty()) {
            lastDiag = "PRIV(" + priv.size() + ")";
            return priv;
        }
        return empty;
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(pollLoop);
        if (receiver != null) {
            try {
                appCtx.unregisterReceiver(receiver);
            } catch (Exception ignored) {}
            receiver = null;
        }
    }
}
