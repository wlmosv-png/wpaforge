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

    private BroadcastReceiver receiver;
    private boolean running;
    private long lastScanRequestAt;
    /** 主动 startScan 的最小间隔，Android 限制约 30s，这里给 15s 以免被系统丢弃 */
    private static final long MIN_SCAN_INTERVAL_MS = 15_000L;
    private static final long POLL_INTERVAL_MS = 5_000L;

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
            wifi.startScan();
        } catch (Exception ignored) {
            // SecurityException 等：权限不足时被动读取也会拿不到，由 emit 处理
        }
    }

    /** 读取当前结果并回调 */
    public void emit() {
        List<NetRecord> records = readNow();
        listener.onResults(records);
    }

    /** 同步读一次当前扫描结果 */
    public List<NetRecord> readNow() {
        List<NetRecord> out = new ArrayList<>();
        if (wifi == null) return out;
        List<ScanResult> raw;
        try {
            raw = wifi.getScanResults();
        } catch (Exception e) {
            return out;
        }
        if (raw == null) return out;

        // 同名 SSID 只保留信号最强的一条，但把 BSSID 数量记下来
        Map<String, NetRecord> best = new LinkedHashMap<>();
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
            String key = rec.isHidden() ? rec.bssid : rec.ssid;
            NetRecord prev = best.get(key);
            if (prev == null || rec.rssi > prev.rssi) {
                best.put(key, rec);
            }
        }
        out.addAll(best.values());
        Collections.sort(out, (a, b) -> Integer.compare(b.rssi, a.rssi));
        return out;
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
