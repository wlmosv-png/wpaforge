package io.github.wlmosv_png.wpaforge;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 应用入口。
 *
 * 这里装一个全局未捕获异常处理器，目标都是**让应用在任何环境都能打开**：
 *
 *   1. 装一个全局未捕获异常处理器
 *      即使真的崩了，也把堆栈写进文件，用户能拿来反馈，
 *      不用非得连电脑抓 logcat。
 */
public class App extends Application {

    private static final String TAG = "WPAForge";

    /**
     * 日志子目录名。
     *
     * 放在应用自己的外部目录下（getExternalFilesDir），因为
     * targetSdk 30+ 的 scoped storage 下应用无权直接写 /sdcard/Download ——
     * 实测对那个路径 open 会返回 Permission denied。
     * 应用私有外部目录则任何 ROM 上都必然可写，用户也能在
     * 文件管理器里找到（Android/data/<包名>/files/）。
     */
    private static final String LOG_SUBDIR = "logs";

    @Override
    public void onCreate() {
        super.onCreate();


        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            writeCrashLog(throwable);
            if (prev != null) prev.uncaughtException(thread, throwable);
        });

        // R11 / R12：基础设施与旧数据迁移。
        //   · SecretBox          —— 敏感串加密（Android Keystore，AES/GCM）；
        //                           装不上时**拒绝落明文**（宁可少显示，不留明文）
        //   · RestoreJournal     —— 配置恢复记录载体（进程被杀后靠它补做恢复）
        //   · PlaintextMigration —— 旧版本的明文命中口令：**先备份，后重写**
        try {
            io.github.wlmosv_png.wpaforge.audit.SecretBox.install(
                    io.github.wlmosv_png.wpaforge.audit.SecretBox.device(this));
            io.github.wlmosv_png.wpaforge.audit.RestoreJournal.install(
                    io.github.wlmosv_png.wpaforge.audit.RestoreStoreFile.file(this));
        } catch (Throwable t) {
            Log.w(TAG, "secret infra unavailable: " + t);
        }

        // F1/F2：补做入口从 CrackService 扩到**应用启动** —— 否则用户只打开 App
        // 不再起服务时，上一次未完成的配置恢复永远不会被补做。
        // 内部是「异步 + 单飞 + 一把锁」，不会与任务收尾恢复并发操作同一份 journal，
        // 也不会阻塞主线程。
        try {
            io.github.wlmosv_png.wpaforge.audit.RestoreRunner
                    .requestStartupRecovery("app-start");
        } catch (Throwable t) {
            Log.w(TAG, "restore runner unavailable: " + t);
        }

        new Thread(() -> {
            try {
                if (!io.github.wlmosv_png.wpaforge.audit.PlaintextMigration.needed(this)) return;
                io.github.wlmosv_png.wpaforge.audit.PlaintextMigration.Report r =
                        io.github.wlmosv_png.wpaforge.audit.PlaintextMigration.run(this);
                Log.i(TAG, "plaintext migration: " + r.describe());
            } catch (Throwable t) {
                Log.w(TAG, "plaintext migration failed: " + t);
            }
        }, "wpaforge-migrate").start();

        // 启动自证：把环境信息写一份，用户不接电脑也能反馈
        writeStartupLog();
    }

    /**
     * 写启动日志。
     *
     * 目的很明确：**让应用自己把环境说清楚**。
     * 之前排查"列表空"只能靠连电脑抓 logcat，用户拿不到日志就没法定位。
     * 现在每次启动都会覆盖写一份，里面包含权限、WiFi 状态、特权通道可用性。
     */
    private void writeStartupLog() {
        new Thread(() -> {
            try {
                File dir = logDir();
                File f = new File(dir, "startup-last.txt");

                StringBuilder sb = new StringBuilder();
                sb.append("WPAForge 启动自检\n");
                sb.append("时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        Locale.US).format(new Date())).append('\n');
                sb.append("设备: ").append(Build.MANUFACTURER).append(' ')
                        .append(Build.MODEL).append('\n');
                sb.append("系统: Android ").append(Build.VERSION.RELEASE)
                        .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
                sb.append("应用: ").append(getPackageName()).append('\n');
                try {
                    sb.append("版本: ").append(getPackageManager()
                            .getPackageInfo(getPackageName(), 0).versionName).append('\n');
                } catch (Throwable ignored) {}
                sb.append('\n');

                // P2-8：敏感串加解密自检。
                // 只用应用自己生成的**合成串**（不是任何真实口令、不落盘），
                // 走一遍 seal → open，只写结论 —— 恢复原口令完全依赖这条通道，
                // 而 open() 在真机上从来没有被执行过。
                sb.append("-- 敏感串（Android Keystore）--\n");
                sb.append("  加解密通道: ")
                        .append(io.github.wlmosv_png.wpaforge.audit.SecretBox.available()
                                ? "可用" : "不可用（将拒绝落任何秘密）")
                        .append('\n');
                sb.append("  seal→open 往返自检: ")
                        .append(io.github.wlmosv_png.wpaforge.audit.SecretBox.roundTrip(
                                io.github.wlmosv_png.wpaforge.audit.SecretBox.selfTestProbe())
                                ? "PASS" : "FAIL")
                        .append('\n');
                sb.append('\n');

                // 权限
                sb.append("-- 权限 --\n");
                for (String p : new String[]{
                        "android.permission.NEARBY_WIFI_DEVICES",
                        "android.permission.ACCESS_FINE_LOCATION",
                        "android.permission.ACCESS_WIFI_STATE",
                        "android.permission.CHANGE_WIFI_STATE",
                        "android.permission.POST_NOTIFICATIONS"}) {
                    int r = checkSelfPermission(p);
                    sb.append(r == android.content.pm.PackageManager.PERMISSION_GRANTED
                            ? "  [有] " : "  [缺] ").append(p).append('\n');
                }
                sb.append('\n');

                // WiFi 与扫描
                sb.append("-- 网络 --\n");
                try {
                    android.net.wifi.WifiManager wm =
                            (android.net.wifi.WifiManager) getSystemService(WIFI_SERVICE);
                    sb.append("  WiFi 开关: ").append(wm != null && wm.isWifiEnabled()
                            ? "开" : "关").append('\n');
                    if (wm != null) {
                        int n = -1;
                        try {
                            java.util.List<?> res = wm.getScanResults();
                            n = res == null ? -1 : res.size();
                        } catch (Throwable t) {
                            sb.append("  getScanResults 抛异常: ")
                                    .append(t.getClass().getSimpleName()).append('\n');
                        }
                        sb.append("  getScanResults 条数: ").append(n).append('\n');
                    } else {
                        sb.append("  WifiManager 不可用\n");
                    }
                } catch (Throwable t) {
                    sb.append("  WiFi 查询失败: ").append(t.getClass().getSimpleName()).append('\n');
                }
                sb.append('\n');

                // 定位开关（系统级，不在权限体系里）：关着时扫描结果一定是空的。
                // 历史事故：用户装完给了定位权限、系统开关没开，日志里查不到任何线索。
                sb.append("-- 定位开关 --\n");
                try {
                    android.location.LocationManager lm =
                            (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
                    boolean locOn = lm == null || (Build.VERSION.SDK_INT >= 28
                            ? lm.isLocationEnabled()
                            : android.provider.Settings.Secure.getInt(getContentResolver(),
                                    android.provider.Settings.Secure.LOCATION_MODE, 0) != 0);
                    sb.append(locOn ? "  定位开关: 开\n" : "  定位开关: 关（扫描一定是空的）\n");
                } catch (Throwable t) {
                    sb.append("  定位开关: 查询失败 ").append(t.getClass().getSimpleName()).append('\n');
                }
                sb.append('\n');

                // 特权通道（Root / Shizuku）
                sb.append("-- 特权通道 --\n");
                // Shizuku 探测：这是无 Root 自动连接的主路，启动时就把真实状态写进日志，
                // 免得用户报「点了没反应」时无从判断。
                // 先诊断类加载：这是「点了没反应」最常见的根因
                try {
                    Class.forName("rikka.shizuku.Shizuku");
                    sb.append("  Shizuku 类加载: OK\n");
                } catch (Throwable t) {
                    sb.append("  Shizuku 类加载: FAIL ").append(t.getClass().getSimpleName())
                            .append(" / loader=")
                            .append(App.class.getClassLoader() == null ? "boot"
                                    : App.class.getClassLoader().getClass().getSimpleName())
                            .append('\n');
                }
                try {
                    // ══════════════════════════════════════════════════════════
                    //  **诊断与正式任务同源**（2026-11-20 第二阶段）
                    //
                    //  改造前这里自己 new 了一个 ShizukuChannel，
                    //  它的探测结果与正式任务毫无关系 ——
                    //  「诊断通过」证明不了「正式任务会用同一个执行器」。
                    //
                    //  现在：走同一个 ExecutorProvider.resolve，拿到同一份 Plan；
                    //  每条命令走同一个 CommandExecutor.run。
                    //  下面输出的七项全部来自该 Plan 与该 Step，不靠推断。
                    // ══════════════════════════════════════════════════════════
                    final io.github.wlmosv_png.wpaforge.cap.ExecutorProvider.Plan plan =
                            io.github.wlmosv_png.wpaforge.cap.ExecutorProvider.resolve(App.this);
                    final io.github.wlmosv_png.wpaforge.cap.ShizukuChannel shz =
                            plan.shizuku != null ? plan.shizuku
                                    : new io.github.wlmosv_png.wpaforge.cap.ShizukuChannel(App.this);
                    int st = plan.shizukuState >= 0 ? plan.shizukuState : shz.probe();
                    sb.append("  Shizuku 状态: ").append(st)
                            .append(" (").append(shz.stateDetail()).append(")\n");
                    sb.append("  Shizuku 可用: ").append(shz.usable() ? "是" : "否").append('\n');
                    // ── 执行器解析结论（与正式任务同一份）───────────────────
                    sb.append("  -- 执行器解析（与正式任务同源）--\n");
                    sb.append("     请求模式=")
                            .append(io.github.wlmosv_png.wpaforge.cap.RunMode
                                    .nameOf(plan.requestedMode)).append('\n');
                    sb.append("     实际模式=")
                            .append(io.github.wlmosv_png.wpaforge.cap.RunMode
                                    .nameOf(plan.resolvedMode)).append('\n');
                    sb.append("     Executor 类型=").append(plan.kind.name())
                            .append(" (").append(plan.kindName()).append(")\n");
                    sb.append("     实际进程 UID=").append(plan.identity.uid).append('\n');
                    sb.append("     SELinux context=")
                            .append(plan.identity.selinuxContext.isEmpty()
                                    ? "<未知>" : plan.identity.selinuxContext).append('\n');
                    sb.append("     Binder 状态=").append(plan.verdict.name())
                            .append(" (state=").append(plan.shizukuState).append(")\n");
                    sb.append("     su 可用=").append(plan.suUsable ? "是" : "否").append('\n');
                    sb.append("     可开跑=").append(plan.canRun ? "是" : "否");
                    if (!plan.canRun) sb.append("  阻止原因=").append(plan.blockReason);
                    sb.append('\n');
                    if (plan.kind != io.github.wlmosv_png.wpaforge.cap.ExecutorProvider.Kind.NONE) {
                        // ══════════════════════════════════════════════════════
                        //  **只读诊断：逐条命令的完整证据**（2026-11-20 任务书第四部分）
                        //
                        //  全部走**正式 Executor**（ShizukuChannel.exec），
                        //  不另写测试通道 —— 诊断结果与真实执行同源。
                        //
                        //  只跑这四条只读命令，绝不碰 connect/forget/disconnect：
                        //    id / cmd wifi status / cmd wifi list-networks / dumpsys wifi
                        //
                        //  每条输出：执行器名 / UID / SELinux / 命令原文 /
                        //            起止时刻 / 耗时 / exitCode / timeout / stdout / stderr
                        // ══════════════════════════════════════════════════════
                        sb.append("  -- 只读命令诊断（正式 Executor）--\n");
                        String[] readonlyCmds = {
                                "id",
                                "cmd wifi status",
                                "cmd wifi list-networks",
                                "dumpsys wifi"
                        };
                        for (String rc : readonlyCmds) {
                            // **同一条执行路径**：与正式任务共用 CommandExecutor。
                            io.github.wlmosv_png.wpaforge.cap.CommandExecutor.Step rr;
                            try {
                                rr = io.github.wlmosv_png.wpaforge.cap.CommandExecutor
                                        .run(plan, rc, 8000);
                            } catch (Throwable t) {
                                sb.append("    [").append(rc).append("] EX ")
                                        .append(t.getClass().getSimpleName()).append('\n');
                                continue;
                            }
                            sb.append("    [").append(rc).append("]\n");
                            sb.append("      执行器=").append(rr.executorId)
                                    .append(" 身份=").append(rr.identity).append('\n');
                            sb.append("      耗时=").append(rr.totalMs).append("ms")
                                    .append(" exit=").append(rr.exitCode)
                                    .append(" timeout=").append(rr.timedOut)
                                    .append(" ok=").append(rr.ok()).append('\n');
                            // ── 耗时分解（定位「每条 11 秒」的关键）──
                            sb.append("      binder=").append(rr.binderMs).append("ms")
                                    .append(" 子进程等待=").append(rr.waitMs).append("ms")
                                    .append(" 读流=").append(rr.drainMs).append("ms").append('\n');
                            if (!rr.failure.isEmpty()) {
                                sb.append("      failure=").append(rr.failure).append('\n');
                            }
                            if (!rr.exceptionClass.isEmpty()) {
                                sb.append("      exception=").append(rr.exceptionClass).append('\n');
                            }
                            // stdout 只取首行 + 字节数（避免日志膨胀，也不泄露口令）
                            String so = rr.output == null ? "" : rr.output;
                            String soFirst = so;
                            int snl = soFirst.indexOf('\n');
                            if (snl > 0) soFirst = soFirst.substring(0, snl);
                            if (soFirst.length() > 100) soFirst = soFirst.substring(0, 100) + "…";
                            sb.append("      stdout(").append(so.length()).append("B) 首行=")
                                    .append(soFirst).append('\n');
                            String se = "";
                            if (!se.isEmpty()) {
                                String seFirst = se;
                                int enl = seFirst.indexOf('\n');
                                if (enl > 0) seFirst = seFirst.substring(0, enl);
                                if (seFirst.length() > 100) {
                                    seFirst = seFirst.substring(0, 100) + "…";
                                }
                                sb.append("      stderr(").append(se.length()).append("B) 首行=")
                                        .append(seFirst).append('\n');
                            }
                            // 失败分类
                            if (!rr.ok()) {
                                sb.append("      失败分类=")
                                        .append(rr.failure.isEmpty() ? "UNKNOWN" : rr.failure)
                                        .append('\n');
                            }
                        }

                        // **执行身份 + IWifiManager 直调自检**（2026-11-20）
                        //
                        // 「Shizuku 可用」只说明拿到 binder；真实 uid 决定能力边界
                        // （uid 0 能读 WifiConfigStore.xml，uid 2000 读不到）。
                        // 直调自检是**只读**的（列已保存网络 / 读当前连接），
                        // 不删不改任何配置。
                        try {
                            io.github.wlmosv_png.wpaforge.cap.ExecutorIdentity id =
                                    shz.identity();
                            sb.append("  Shizuku 执行身份: ").append(id.describe(true))
                                    .append('\n');
                            sb.append("  可读 WifiConfigStore: ")
                                    .append(id.canReadConfigStore() ? "是" : "否").append('\n');

                            long dt0 = System.currentTimeMillis();
                            io.github.wlmosv_png.wpaforge.cap.WifiServiceBridge.Outcome<
                                    java.util.List<Object>> lst =
                                    io.github.wlmosv_png.wpaforge.cap.WifiServiceBridge
                                            .listConfiguredNetworks(
                                                    android.os.Build.VERSION.SDK_INT);
                            long dtMs = System.currentTimeMillis() - dt0;
                            sb.append("  IWifiManager 直调(只读): ").append(lst.describe())
                                    .append(" 耗时=").append(dtMs).append("ms\n");
                            if (lst.ok) {
                                sb.append("    已保存网络 ").append(lst.value.size())
                                        .append(" 条\n");
                            }

                            long it0 = System.currentTimeMillis();
                            io.github.wlmosv_png.wpaforge.cap.WifiServiceBridge.Outcome<Object>
                                    info = io.github.wlmosv_png.wpaforge.cap.WifiServiceBridge
                                            .getConnectionInfo(
                                                    android.os.Build.VERSION.SDK_INT);
                            long itMs = System.currentTimeMillis() - it0;
                            if (info.ok) {
                                Object ss = io.github.wlmosv_png.wpaforge.cap.WifiServiceBridge
                                        .infoField(info.value, "ssid");
                                Object ni = io.github.wlmosv_png.wpaforge.cap.WifiServiceBridge
                                        .infoField(info.value, "networkId");
                                sb.append("  IWifiManager 读连接: ssid=").append(ss)
                                        .append(" netId=").append(ni)
                                        .append(" 耗时=").append(itMs).append("ms\n");
                            } else {
                                sb.append("  IWifiManager 读连接: ").append(info.describe())
                                        .append('\n');
                            }
                            // 直调失败时给出链路诊断（只读）
                            if (!lst.ok || !info.ok) {
                                sb.append("  直调诊断: ")
                                        .append(io.github.wlmosv_png.wpaforge.cap
                                                .WifiServiceBridge.diagnose())
                                        .append('\n');
                            }
                        } catch (Throwable t) {
                            sb.append("  直调自检异常: ")
                                    .append(t.getClass().getSimpleName()).append('\n');
                        }
                    }
                } catch (Throwable t) {
                    sb.append("  Shizuku 探测异常: ").append(t.getClass().getSimpleName())
                            .append('\n');
                }
                try {
                    boolean hasPriv = io.github.wlmosv_png.wpaforge.scan.PrivScanReader.hasPrivilegedChannel();
                    sb.append("  Root 通道可用: ").append(hasPriv ? "是" : "否（su 不可用）").append('\n');
                } catch (Throwable t) {
                    sb.append("  特权通道查询失败: ").append(t.getClass().getSimpleName()).append('\n');
                }

                // 引擎通道自检（含判定信号可用性）
                //
                // 用户反复遇到「密码对却识别不到」，而线上看不到任何证据。
                // 现在每次启动都把它写进这份文件，报障时直接发文件就行。
                try {
                    sb.append('\n').append("-- 引擎通道自检 --\n");
                    int mode = io.github.wlmosv_png.wpaforge.Prefs.mode(App.this);
                    // 模式标签不能写死 "Root"（2026-11-20 修）：无 Root 模式下
                    // 日志说"Root"会把人带偏，报障时最容易被误读成"明明有 root 却不行"。
                    sb.append("  当前模式: ")
                      .append(io.github.wlmosv_png.wpaforge.cap.RunMode.nameOf(mode)).append('\n');
                    io.github.wlmosv_png.wpaforge.audit.CrackEngine eng =
                            new io.github.wlmosv_png.wpaforge.audit.CrackEngine(App.this, mode);
                    // ═════════════════════════════════════════════════════════
                    //  **自检也走同一个执行器选择**（2026-11-20 第二阶段）
                    //
                    //  改造前这里自己 new ShizukuChannel、自己判状态，
                    //  且失败后**静默回落到 su** ——
                    //  于是「自检通过」可能是 su 给的结果，而界面上写的是 Shizuku。
                    //
                    //  现在：同一份 ExecutorProvider.Plan，同一个 CommandExecutor，
                    //  中途绝不回落。
                    // ═════════════════════════════════════════════════════════
                    final io.github.wlmosv_png.wpaforge.cap.ExecutorProvider.Plan selfPlan =
                            io.github.wlmosv_png.wpaforge.cap.ExecutorProvider.resolve(App.this);
                    final io.github.wlmosv_png.wpaforge.cap.CommandExecutor.ShellAdapter selfExec =
                            new io.github.wlmosv_png.wpaforge.cap.CommandExecutor.ShellAdapter(selfPlan);
                    final boolean rootlessSelf =
                            (mode == io.github.wlmosv_png.wpaforge.cap.RunMode.ROOTLESS);
                    eng.setShellChannel(new io.github.wlmosv_png.wpaforge.audit.CrackEngine.ShellChannel() {
                        @Override
                        public String exec(String cmd, long timeoutMs) {
                            // 不回落：plan 选了哪条就只用哪条，失败如实返 null
                            return selfExec.exec(cmd, timeoutMs);
                        }
                        @Override public String name() {
                            return selfPlan.kindName();
                        }
                    });
                    if (rootlessSelf
                            && selfPlan.kind == io.github.wlmosv_png.wpaforge.cap.ExecutorProvider.Kind.SPECIFIER) {
                        // Shizuku 不可用 → 挂上 Specifier，让自检按真实通道判，
                        // 而不是拿一条不存在的 su 通道去报「未接入」。
                        try {
                            eng.setSpecifierChannel(
                                    new io.github.wlmosv_png.wpaforge.cap.SpecifierChannel(
                                            App.this, new android.os.Handler(
                                                    android.os.Looper.getMainLooper())));
                            eng.setConnectChannel(
                                    new io.github.wlmosv_png.wpaforge.cap.RootlessChannel());
                        } catch (Throwable ignored) {
                        }
                    }
                    io.github.wlmosv_png.wpaforge.audit.CrackEngine.ChannelProbe cp = eng.probeChannel();
                    // 拼成一个字符串再一次性追加。
                    // 之前逐行 append 出现过 steps 全部空白的情况，
                    // 排查成本太高，改成先 join 再写。
                    StringBuilder ps = new StringBuilder();
                    ps.append("  结论: ")
                      .append(cp.ok ? "已接入" : "未接入")
                      .append(" (").append(cp.summary).append(')').append('\n');
                    ps.append("  检查项 ").append(cp.steps.size()).append(" 条:").append('\n');
                    for (int i = 0; i < cp.steps.size(); i++) {
                        ps.append("    [").append(i + 1).append("] ")
                          .append(cp.steps.get(i)).append('\n');
                    }
                    if (!cp.detail.isEmpty()) {
                        ps.append("  详情:").append('\n');
                        ps.append(cp.detail).append('\n');
                    }
                    sb.append(ps);
                } catch (Throwable t) {
                    sb.append("  引擎自检异常: ")
                            .append(t.getClass().getSimpleName()).append('\n');
                }

                try (FileWriter w = new FileWriter(safeTruncate(f))) {
                    w.write(sb.toString());
                }
                Log.i(TAG, "启动自检已写入 " + f.getAbsolutePath());
            } catch (Throwable t) {
                // 不能再静默吞掉了 —— 之前就是这样导致"文件没出来但不知道为啥"。
                // 把异常本身也尽量落盘。
                Log.e(TAG, "启动自检写入失败", t);
                try {
                    File dir = logDir();
                    try (FileWriter w = new FileWriter(safeTruncate(new File(dir, "startup-error.txt")))) {
                        w.write("启动自检写入失败\n");
                        w.write(t.toString() + "\n\n");
                        java.io.StringWriter sw = new java.io.StringWriter();
                        t.printStackTrace(new java.io.PrintWriter(sw));
                        w.write(sw.toString());
                    }
                } catch (Throwable ignored) {}
            }
        }, "wpaforge-startup-log").start();
    }

    private static File safeTruncate(File f) {
        try {
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {}
        return f;
    }

    /** 日志目录（应用私有外部目录下的 logs/），保证可写 */
    private File logDir() {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        File dir = new File(base, LOG_SUBDIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 把崩溃堆栈写到外部目录，方便用户反馈 */
    private void writeCrashLog(Throwable t) {
        try {
            File dir = logDir();
            File f = new File(dir, "crash-" + System.currentTimeMillis() + ".txt");

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    Locale.US).format(new Date()));
            pw.println("设备: " + Build.MANUFACTURER + " " + Build.MODEL);
            pw.println("系统: Android " + Build.VERSION.RELEASE
                    + " (API " + Build.VERSION.SDK_INT + ")");
            pw.println();
            t.printStackTrace(pw);
            pw.flush();

            try (FileWriter w = new FileWriter(f)) {
                w.write(sw.toString());
            }
            Log.e(TAG, "崩溃日志已写入 " + f.getAbsolutePath());
        } catch (Throwable ignored) {
            // 写日志本身失败就算了，不能让处理崩溃的过程再崩一次
        }
    }
}
