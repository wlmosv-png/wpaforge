package io.github.wlmosv_png.wpaforge.audit;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;

import io.github.wlmosv_png.wpaforge.L;
import io.github.wlmosv_png.wpaforge.Prefs;

/**
 * 全链路环境诊断。
 *
 * <h3>为什么需要它</h3>
 * 用户报障时只能看到「没连上」「跳过判定」这类结果，看不到**卡在哪一层**。
 * 判定链有 4 层判据，执行通道只有 Root（su + cmd wifi），
 * 任何一层不通，表现都是同一句「没判定」。
 *
 * 这个类把每一层能测的都测一遍，逐项给出「通了 / 不通 / 为什么」，
 * 结果既打到界面也写进日志文件，用户直接发出来就能定位。
 */
public final class EnvDiagnostics {

    /** 诊断结果 */
    public static final class Report {
        public final StringBuilder text = new StringBuilder();
        public boolean allCriticalOk = true;

        public void line(String s) {
            text.append(s).append('\n');
        }

        /** 关键项失败时标记（不影响非关键项） */
        public void failCritical() {
            allCriticalOk = false;
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    private EnvDiagnostics() {}

    /**
     * 跑一遍完整诊断。
     *
     * @param mode     当前执行方式（Prefs.MODE_*）
     * @param shell    shell 通道（可能为 null）
     */
    public static Report run(Context ctx, int mode, CrackEngine.ShellChannel shell) {
        Report r = new Report();

        r.line("══════ 环境诊断 ══════");

        // ---------- 1. 设备与系统 ----------
        r.line("① 设备环境");
        r.line("  厂商/型号: " + Build.MANUFACTURER + " " + Build.MODEL);
        r.line("  Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        r.line("  安全补丁: " + Build.VERSION.SECURITY_PATCH);
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?";
        r.line("  ABI: " + abi);
        r.line("");

        // ---------- 2. 权限 ----------
        r.line("② 应用权限");
        checkPerm(r, ctx, android.Manifest.permission.ACCESS_FINE_LOCATION, "定位（Android 12 以下扫描必需）");
        if (Build.VERSION.SDK_INT >= 33) {
            checkPerm(r, ctx, "android.permission.NEARBY_WIFI_DEVICES", "附近设备（Android 13+ 扫描必需）");
        }
        checkPerm(r, ctx, android.Manifest.permission.ACCESS_WIFI_STATE, "读取 WiFi 状态");
        checkPerm(r, ctx, android.Manifest.permission.CHANGE_WIFI_STATE, "修改 WiFi 状态");
        checkPerm(r, ctx, android.Manifest.permission.ACCESS_NETWORK_STATE, "读取网络状态");
        r.line("");

        // ---------- 3. 执行通道 ----------
        r.line("③ 执行通道（模式: " + modeName(mode) + "）");

        {
            r.line("  · Root 模式");
            String id = io.github.wlmosv_png.wpaforge.audit.RootProber.exec(
                    null, new String[]{"su", "-c", "id"}, 12000);
            if (id == null) {
                r.line("    ✗ su 不可用 —— 未 root 或未授予本应用 root");
                r.failCritical();
            } else if (!id.contains("uid=0")) {
                r.line("    ✗ su 返回非 root: " + id.trim());
                r.failCritical();
            } else {
                r.line("    ✓ root 可用 (" + id.trim() + ")");
            }
        }
        r.line("");

        // ---------- 4. shell 通道实测 ----------
        if (shell != null) {
            r.line("④ shell 通道实测");
            long t0 = System.currentTimeMillis();
            String st = null;
            try { st = shell.exec("cmd wifi status", 8000); } catch (Throwable t) {
                r.line("    ✗ exec 抛异常: " + t.getClass().getSimpleName());
            }
            long cost = System.currentTimeMillis() - t0;
            r.line("    cmd wifi status: " + (st == null ? "无返回" : (st.trim().isEmpty() ? "返回空" : "OK"))
                    + "（" + cost + "ms）");
            if (st == null || st.trim().isEmpty()) {
                r.line("    ✗ shell 命令没有输出 —— 通道未真正生效");
                r.failCritical();
            } else {
                String cur = StatusParser.currentSsid(st);
                r.line("    当前 SSID: " + (cur == null ? "（未连接）" : cur));
            }

            // logcat 可读性
            //
            // 一次性探测会误报：此刻系统可能刚好没有这个 tag 的新日志
            // （实测：同一台机器上有时读到 300+ 行、有时读到 0 行，
            //   系统对每个进程有日志配额；而 App 自己的通道自检曾据此打印过
            //   「logcat 可读: 否」，误导排查方向）。
            // 改成两步探测：先立即读一次，没内容就等 600ms 再读一次；
            // 仍为空时说明「本刻未读到」而不是断言「不可读」。
            String log = WifiStateLog.read(shell, 50);
            boolean logOk = log != null && !log.trim().isEmpty();
            if (!logOk) {
                try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                String log2 = WifiStateLog.read(shell, 50);
                logOk = log2 != null && !log2.trim().isEmpty();
            }
            if (logOk) {
                r.line("    logcat 可读: 是（判定可用「状态机日志」层）");
            } else {
                r.line("    logcat 可读: 本刻未读到内容（可能本刻无新日志，或系统日志配额丢弃）；"
                        + "判定会自动回退到 status+netId 层，这不代表通道不可用");
            }

            // list-networks（netId 归因需要）
            String nets = null;
            try { nets = shell.exec("cmd wifi list-networks", 5000); } catch (Throwable ignored) {}
            r.line("    list-networks: " + (nets != null && nets.contains("SSID") ? "OK" : "无输出"));
            r.line("");
        } else {
            r.line("④ shell 通道: 未设置");
            r.line("");
        }

        // ---------- 5. 判定信号 ----------
        r.line("⑤ 判定信号");
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            String cur = null;
            if (wm != null && wm.getConnectionInfo() != null) {
                cur = wm.getConnectionInfo().getSSID();
                if (cur != null && cur.startsWith("\"") && cur.endsWith("\"") && cur.length() >= 2) {
                    cur = cur.substring(1, cur.length() - 1);
                }
            }
            boolean known = cur != null && !cur.isEmpty() && !"<unknown ssid>".equals(cur);
            r.line("    WifiInfo 当前 SSID: " + (known ? cur : "读不到")
                    + (known ? "" : " （⚠ 缺定位权限时读不到，会严重影响判定）"));
            if (!known) {
                r.line("    ⚠ 若刚连上网络仍读不到，请检查定位权限是否已授予");
            }
        } catch (Throwable t) {
            r.line("    ✗ 读 WifiInfo 失败: " + t.getClass().getSimpleName());
        }
        r.line("");

        r.line("══════ 诊断结束 ══════");
        if (!r.allCriticalOk) {
            r.line("结论: 有关键项不通，先解决上面标 ✗ 的条目。");
        } else {
            r.line("结论: 环境检查通过。若仍判不出，请看每次尝试的分层日志。");
        }

        return r;
    }

    private static void checkPerm(Report r, Context ctx, String perm, String label) {
        try {
            int res = ctx.checkSelfPermission(perm);
            boolean ok = res == PackageManager.PERMISSION_GRANTED;
            r.line("  " + (ok ? "✓" : "✗") + " " + label);
            if (!ok && perm.contains("LOCATION")) {
                r.line("      → 缺定位权限会让扫描/判定拿不到 SSID");
            }
        } catch (Throwable t) {
            r.line("  ? " + label + "（无法查询）");
        }
    }

    private static String yesNo(boolean b) {
        return b ? "是" : "否";
    }

    private static String modeName(int mode) {
        return "Root";
    }
}
