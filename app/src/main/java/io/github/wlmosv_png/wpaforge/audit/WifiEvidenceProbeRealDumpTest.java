package io.github.wlmosv_png.wpaforge.audit;

import java.util.List;

/**
 * 用**整份真机 dumpsys**（4 MB / 18,187 行）做全量回归（2026-11-22 新增）。
 *
 * <h3>为什么需要它</h3>
 * {@link WifiEvidenceProbeTest} 用的是逐条摘录的样本，验证的是「单条格式认不认」。
 * 但生产代码拿到的是**整份 dump**，里面同时有：
 * <ul>
 *   <li>63 条 {@code mConnectionEvents}（跨 13 小时、跨多个 SSID）；</li>
 *   <li>122 行 {@code WifiConfigManager} 操作（含系统自己建的、我们建的、删除的）；</li>
 *   <li>其它段里大量**形似但无关**的行（例如 {@code WifiScoreReport} 里也有时间戳）。</li>
 * </ul>
 * 只测摘录样本会漏掉「在真实噪声里能不能只挑出该挑的」这个核心问题。
 *
 * <p>本类在**没有真机 dump 文件时自动跳过**（打印 SKIP，不判失败）——
 * 因为它依赖一个 4 MB 的外部样本，不能假定任何机器上都有。
 * 门禁在设备侧跑时会带上该文件，桌面侧跑就跳过。
 */
public final class WifiEvidenceProbeRealDumpTest {

    private static int pass = 0, fail = 0, skip = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void ok(String name, boolean cond) { ok(name, cond, ""); }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /**
     * 真机 dump 的候选路径。
     *
     * <p>第一项是设备侧原始位置；后两项经 {@link io.github.wlmosv_png.wpaforge.TestPaths}
     * 解析——门禁要求「读文件的测试必须经 TestPaths」，否则把项目根换一份跑时
     * 测试实际读的仍是写死的那份，属于假绿。
     */
    private static String[] dumpPaths() {
        String root;
        try {
            root = io.github.wlmosv_png.wpaforge.TestPaths.root();
        } catch (Throwable t) {
            root = null;
        }
        java.util.List<String> ps = new java.util.ArrayList<>();
        ps.add("/data/local/tmp/wpf-dumpsys.txt");
        if (root != null) {
            ps.add(root + "/wpf-dumpsys.txt");
            ps.add(root + "/logs/dumpsys-wifi-baseline-20261122.txt");
        }
        ps.add("/workspace/wpf-1964-audit/logs/dumpsys-wifi-baseline-20261122.txt");
        return ps.toArray(new String[0]);
    }

    public static void main(String[] args) {
        System.out.println("== 真机整份 dumpsys 全量回归 ==");
        try {
            String dumps = load();
            if (dumps == null) {
                skip++;
                System.out.println("  SKIP 未找到真机 dump 样本（4 MB），本测试跳过");
                System.out.println();
                System.out.println("=== 整份 dump 回归: " + pass + " 通过, " + fail
                        + " 失败, " + skip + " 跳过 ===");
                return;
            }
            info("样本大小 " + dumps.length() + " 字符");

            // ════════════════════════════════ 1. 连接事件：数量与分布
            System.out.println();
            System.out.println("-- 1. mConnectionEvents 全量 --");
            List<WifiEvidenceProbe.ConnEvent> conns =
                    WifiEvidenceProbe.connectionEvents(dumps);
            info("解析出 " + conns.size() + " 条");

            int wrongPsk = 0, authFail = 0, okRes = 0, failRes = 0;
            for (WifiEvidenceProbe.ConnEvent e : conns) {
                if (e.wrongPsk()) wrongPsk++;
                if (e.authFailure()) authFail++;
                if (e.connectionResult == 1) okRes++;
                else if (e.connectionResult == 0) failRes++;
            }
            info("AUTH_FAILURE_WRONG_PSWD = " + wrongPsk);
            info("AUTHENTICATION_FAILURE   = " + authFail);
            info("connectionResult=1(成功) = " + okRes);
            info("connectionResult=0(失败) = " + failRes);

            ok("解析出 60 条以上（真机 63 条）", conns.size() >= 60, "" + conns.size());
            ok("识别出 14 条以上 WRONG_PSWD", wrongPsk >= 14, "" + wrongPsk);
            ok("成功与失败都被区分", okRes > 0 && failRes > 0,
                    "ok=" + okRes + " fail=" + failRes);

            // 时间戳必须全部解析成功（1 位分钟不得漏）
            int noTime = 0;
            for (WifiEvidenceProbe.ConnEvent e : conns) {
                if (e.timeMs < 0) noTime++;
            }
            ok("★所有 startTime 都解析出毫秒（1 位分钟不漏）", noTime == 0,
                    "未解析 " + noTime + " 条");

            // ════════════════════════════════ 2. 关键目标：2.4G-3
            System.out.println();
            System.out.println("-- 2. 目标 2.4G-3（密码确实错误）--");
            List<WifiEvidenceProbe.ConnEvent> g3 =
                    WifiEvidenceProbe.connectionEvents(dumps);
            int g3Wrong = 0;
            for (WifiEvidenceProbe.ConnEvent e : g3) {
                if ("2.4G-3".equals(e.ssid) && e.wrongPsk() && e.uid == 0) g3Wrong++;
            }
            info("2.4G-3 的 uid=0 WRONG_PSWD = " + g3Wrong);
            ok("2.4G-3 至少 14 条 uid=0 的密码错", g3Wrong >= 14, "" + g3Wrong);

            // ════════════════════════════════ 3. 关键目标：whoami（无认证失败）
            System.out.println();
            System.out.println("-- 3. 目标 whoami（★本来就没有认证失败证据）--");
            WifiEvidenceProbe.ConnEvent whoamiWrong =
                    WifiEvidenceProbe.findWrongPsk(conns, "whoami", -1, -1, true);
            info("whoami 的 WRONG_PSWD = " + whoamiWrong);
            ok("★whoami 没有任何 WRONG_PSWD（判 UNKNOWN 是对的）",
                    whoamiWrong == null);

            WifiEvidenceProbe.ConnEvent whoamiFail =
                    WifiEvidenceProbe.findNonPskFailure(conns, "whoami", -1, -1);
            info("whoami 的非密码错失败 = " + whoamiFail);
            ok("whoami 有非密码错失败（NEW_CONNECTION_ATTEMPT）",
                    whoamiFail != null);
            ok("该失败确实是 NEW_CONNECTION_ATTEMPT",
                    whoamiFail != null
                            && "NEW_CONNECTION_ATTEMPT".equals(whoamiFail.level2FailureCode));

            // ════════════════════════════════ 4. WifiConfigManager 全量
            System.out.println();
            System.out.println("-- 4. WifiConfigManager 全量 --");
            List<WifiEvidenceProbe.ConfigEvent> cfgs =
                    WifiEvidenceProbe.configEvents(dumps);
            int adds = 0, removes = 0, sels = 0, wrongPw = 0, rootAdds = 0;
            for (WifiEvidenceProbe.ConfigEvent e : cfgs) {
                if (e.op == WifiEvidenceProbe.ConfigOp.ADD_OR_UPDATE) {
                    adds++;
                    if (e.uid == 0) rootAdds++;
                } else if (e.op == WifiEvidenceProbe.ConfigOp.REMOVE) removes++;
                else if (e.op == WifiEvidenceProbe.ConfigOp.SELECTION_STATUS) {
                    sels++;
                    if (e.wrongPassword()) wrongPw++;
                }
            }
            info("ADD=" + adds + "（其中 uid=0 " + rootAdds + "）REMOVE=" + removes
                    + " SELECTION=" + sels + "（WRONG_PASSWORD " + wrongPw + "）");

            ok("解析出 80 条以上配置操作", cfgs.size() >= 80, "" + cfgs.size());
            ok("识别出 30 条以上 DISABLED_BY_WRONG_PASSWORD", wrongPw >= 30, "" + wrongPw);
            ok("★识别出 uid=0 的 ADD（root 通道建的配置）", rootAdds >= 10, "" + rootAdds);

            int noCfgTime = 0;
            for (WifiEvidenceProbe.ConfigEvent e : cfgs) {
                if (e.timeMs < 0) noCfgTime++;
            }
            ok("所有配置事件时间戳都解析成功", noCfgTime == 0, "未解析 " + noCfgTime);

            // ════════════════════════════════ 5. 配置级归属：2.4G-3
            System.out.println();
            System.out.println("-- 5. 配置级归属（AT3 强度）--");
            WifiEvidenceProbe.ConfigEvent g3Cfg = WifiEvidenceProbe.findWrongPasswordDisable(
                    cfgs, "2.4G-3", -1, -1, true);
            info("2.4G-3 的 WRONG_PASSWORD 禁用 = " + g3Cfg);
            ok("2.4G-3 有 WRONG_PASSWORD 禁用记录", g3Cfg != null);
            ok("该记录确实 wrongPassword()", g3Cfg != null && g3Cfg.wrongPassword());

            ok("★whoami 没有任何 WRONG_PASSWORD 禁用（与 §3 一致）",
                    WifiEvidenceProbe.findWrongPasswordDisable(
                            cfgs, "whoami", -1, -1, true) == null);

            // ════════════════════════════════ 6. 时间窗隔离
            System.out.println();
            System.out.println("-- 6. 时间窗隔离（窗口外的失败不得被采信）--");
            long wFrom = WifiEvidenceProbe.parseStamp("11-22 0:44:20.000");
            long wTo = WifiEvidenceProbe.parseStamp("11-22 0:44:30.000");
            ok("窗口可信", WifiEvidenceProbe.windowUsable(wFrom, wTo));

            WifiEvidenceProbe.ConnEvent inWin = WifiEvidenceProbe.findWrongPsk(
                    conns, "2.4G-3", wFrom, wTo, true);
            ok("窗口内有 2.4G-3 的失败", inWin != null);
            if (inWin != null) {
                ok("该失败的 timeMs 确实落在窗口内",
                        inWin.timeMs >= wFrom && inWin.timeMs <= wTo,
                        "t=" + inWin.timeMs + " win=[" + wFrom + "," + wTo + "]");
            }

            long farFrom = WifiEvidenceProbe.parseStamp("11-22 3:00:00.000");
            long farTo = WifiEvidenceProbe.parseStamp("11-22 4:00:00.000");
            ok("★03:00-04:00 窗口内找不到 00:44 的失败（时间窗真的在起作用）",
                    WifiEvidenceProbe.findWrongPsk(conns, "2.4G-3", farFrom, farTo, true)
                            == null);

            // 跨天窗口必须不可信
            long yFrom = WifiEvidenceProbe.parseStamp("11-21 19:54:20.000");
            long tTo = WifiEvidenceProbe.parseStamp("11-22 00:44:30.000");
            ok("★跨天窗口被判为不可信", !WifiEvidenceProbe.windowUsable(yFrom, tTo));

            // ════════════════════════════════ 7. 内嵌 supplicant 行
            System.out.println();
            System.out.println("-- 7. 内嵌 supplicant 行（单次事故快照）--");
            List<WifiEvidenceProbe.SuppLine> sups =
                    WifiEvidenceProbe.supplicantLines(dumps);
            info("解析出 " + sups.size() + " 条");
            int wk = 0;
            for (WifiEvidenceProbe.SuppLine e : sups) {
                if (e.wrongKey) wk++;
                info("  " + e);
            }
            ok("至少 2 条 WRONG_KEY", wk >= 2, "" + wk);

            // ════════════════════════════════ 8. 性能
            System.out.println();
            System.out.println("-- 8. 解析耗时（整份 4 MB）--");
            long t0 = System.nanoTime();
            List<WifiEvidenceProbe.ConnEvent> c2 =
                    WifiEvidenceProbe.connectionEvents(dumps);
            long t1 = System.nanoTime();
            List<WifiEvidenceProbe.ConfigEvent> f2 =
                    WifiEvidenceProbe.configEvents(dumps);
            long t2 = System.nanoTime();
            long connMs = (t1 - t0) / 1_000_000L;
            long cfgMs = (t2 - t1) / 1_000_000L;
            info("connectionEvents " + connMs + "ms / configEvents " + cfgMs + "ms");
            ok("连接事件解析 < 500ms", connMs < 500, connMs + "ms");
            ok("配置事件解析 < 500ms", cfgMs < 500, cfgMs + "ms");
            ok("两次结果一致", c2.size() == conns.size() && f2.size() == cfgs.size());

            // ════════════════════════════════ 9. 反例：不得把成功当失败
            System.out.println();
            System.out.println("-- 9. ★反例：成功记录绝不能被当成密码错 --");
            int falsePos = 0;
            for (WifiEvidenceProbe.ConnEvent e : conns) {
                if (e.connectionResult == 1 && e.wrongPsk()) falsePos++;
            }
            ok("没有一条「成功但被判 WRONG_PSWD」", falsePos == 0, "" + falsePos);

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== 整份 dump 回归: " + pass + " 通过, " + fail
                + " 失败, " + skip + " 跳过 ===");
        if (fail > 0) System.exit(1);
    }

    private static String load() {
        for (String p : dumpPaths()) {
            try {
                java.io.File f = new java.io.File(p);
                if (!f.isFile() || f.length() < 100_000L) continue;
                byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                return new String(b, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
